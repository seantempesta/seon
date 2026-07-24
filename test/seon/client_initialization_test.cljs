(ns seon.client-initialization-test
  (:require
   [cljs.test :refer [async deftest is testing]]
   [cognitect.transit :as transit]
   [malli.core :as m]
   [my.skills :as skills]
   [seon.agent :as agent]
   [seon.agent.ctx :as ctx]
    [seon.agent.ctx.admin :as ctx.admin]
   [seon.agent.loop :as agent-loop]
   [seon.agent.lifecycle :as lifecycle]
   [seon.ai.generate-code :as generate-code]
   [seon.client :as client]
   [seon.config :as config]
   [seon.config.resolve :as config.resolve]
   [seon.db :as db]
   [seon.db.internal :as db.internal]
   [seon.db.protocol :as protocol]
   [seon.launch :as launch]
   [seon.error :as error]
   [seon.runtime.admission :as admission]
   [seon.runtime.recovery :as recovery]
   [seon.schema :as schema]
   [seon.runtime.state :as state]
   [shadow.cljs.devtools.client.env :as shadow-env]
   [shadow.cljs.devtools.client.node :as shadow-node]))

(def ^:private digest
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

(def ^:private legacy-cold-boot-attributes
  "Persisted attributes carried by the deleted hand-maintained boot list whose
   entity declarations were incomplete when the population became computed."
  #{:my.kb.shared/at
    :my.kb.shared/text
    :my.kb/confidence
    :my.kb/source-line
    :my.kb/source-path
    :my.kb/verified-at
    :seon.agent.ctx/priority
    :seon.agent.testrun/agent
    :seon.agent.testrun/errors
    :seon.agent.testrun/failed
    :seon.agent.testrun/framework
    :seon.agent.testrun/line
    :seon.agent.testrun/message
    :seon.agent.testrun/passed
    :seon.agent.testrun/path
    :seon.agent.testrun/test-name
    :seon.db.id/generator
    :seon.render/full?})

(deftest doctored-launch-envelope-records-the-divergent-keys
  (async done
    (let [recorded (atom nil)
          facts (zipmap config.resolve/operational-keys (repeat 1))
          envelope
          (merge facts
                 {:seon.config.database.executor/selected-processors 8})
          original-with-configuration error/with-configuration
          original-record error/record!
          cleanup! (fn []
                     (set! error/with-configuration original-with-configuration)
                     (set! error/record! original-record)
                     (done))]
      (set! error/with-configuration (fn [_ thunk] (thunk)))
      (set! error/record! (fn [fault] (reset! recorded fault)))
      (-> (js/Promise.resolve nil)
          (.then (fn []
                   (#'client/prove-launch-configuration! envelope facts)))
          (.then (fn [_]
                   (is false "a divergent launch envelope was accepted")))
          (.catch
           (fn [_]
             (is (= #{:seon.config.database.executor/selected-processors}
                    (set (keys (:seon.config/divergences
                                (ex-data (:seon.error/raw @recorded)))))))
             (is (= :core (:seon.error/fault @recorded)))))
          (.finally cleanup!)))))

(deftest namespace-identities-use-the-symbol-storage-contract
  (is (m/validate :seon.ns/name 'my.orders))
  (is (not (m/validate :seon.ns/name :my.orders)))
  (is (= :db.type/symbol
         (:db/valueType
          (first (db/malli->datahike-schema [:seon.ns/name])))))
  (is (= [{:db/id [:seon.ns/name 'my.orders]}]
         (db.internal/normalize-entity-ref-keys
          (db.internal/coerce-identity-symbol-idents
           [{:seon.db/ref [:seon.ns/name 'my.orders]}]))))
  (is (= [{:seon.ns/name 'my.orders}]
         (db.internal/coerce-identity-symbol-idents
          [{:seon.ns/name 'my.orders}])))
  (is (= [[:db/add [:seon.ns/name 'my.orders]
           :seon.agent/namespace [:seon.ns/name 'my.orders]]]
         (db.internal/coerce-identity-symbol-idents
          [[:db/add [:seon.ns/name 'my.orders]
            :seon.agent/namespace [:seon.ns/name 'my.orders]]]))))

(defn- descriptor []
  {::launch/runtime {::launch/execution-digest digest}})

(def ^:private configuration
  (config/resolve-config-singleton {}))

(deftest public-edn-slot-encoder-preserves-transaction-data-shape
  (is (= [{:seon.render/ai "my.render/status"
           :seon.user/id "user"
           :seon.client-initialization-test/nested
           {:seon.render/ai "[:status :ok]"}}
          [:db/add [:seon.user/id "user"]
           :seon.render/ai "my.render/status"]]
         (db/encode-edn-slot-values
          [{:seon.render/ai 'my.render/status
            :seon.user/id "user"
            :seon.client-initialization-test/nested
            {:seon.render/ai [:status :ok]}}
           [:db/add [:seon.user/id "user"]
            :seon.render/ai 'my.render/status]]))))

(deftest managed-identity-attrs-follow-desired-registered-entities
  (let [identity-attrs (deref #'client/desired-identity-attrs)
        desired [{:seon.route/name :root}
                 {:my.skills/name :search}
                 {:seon.config/id :seon.config/system}
                 {:example.managed/id "fourth"}]
        catalog [{:seon.schema.catalog/id-attr :seon.route/name}
                 {:seon.schema.catalog/id-attr :my.skills/name}
                 {:seon.schema.catalog/id-attr :seon.config/id}
                 {:seon.schema.catalog/id-attr :example.managed/id}
                 {:seon.schema.catalog/id-attr :example.absent/id}]]
    (with-redefs [schema/entity-catalog (constantly catalog)]
      (is (= #{:seon.route/name
               :my.skills/name
               :seon.config/id
               :example.managed/id}
             (identity-attrs desired))
          "a newly registered desired entity family joins reconciliation")
      (is (not (contains? (identity-attrs desired) :example.absent/id))
          "registered families absent from this desired population stay out"))))

(deftest initial-agent-errors-fail-startup
  (is (true? (#'client/initial-agent-failure?
              {:seon.error/message "database read failed"})))
  (is (true? (#'client/initial-agent-failure? {:seon.db/ok? false})))
  (is (false? (#'client/initial-agent-failure?
               {:seon.agent/initial-created? false}))))

(deftest shadow-node-reload-notification-reflects-javascript-import
  (let [original-autoload shadow-env/autoload
        original-import shadow-node/closure-import
        effects (atom [])
        message {:info {:sources [{:ns 'example.reload
                                   :resource-id "example.cljs"
                                   :output-name "example.js"}]
                        :compiled #{"example.cljs"}
                        :warnings []}
                 :reload-info {:never-load #{} :always-load #{}}}]
    (try
      (set! shadow-env/autoload true)
      (set! shadow-node/closure-import
            (fn [_] (throw (js/Error. "import failed"))))
      (shadow-node/handle-build-complete
       nil message
       #(swap! effects conj [:complete %])
       #(swap! effects conj [:failure %]))
      (is (= [:failure] (mapv first @effects)))
      (is (= "Error: import failed"
             (::shadow-node/reload-error (second (first @effects)))))

      (reset! effects [])
      (set! shadow-node/closure-import (fn [_] true))
      (shadow-node/handle-build-complete
       nil message
       #(swap! effects conj [:complete %])
       #(swap! effects conj [:failure %]))
      (is (= [[:complete message]] @effects))
      (finally
        (set! shadow-env/autoload original-autoload)
        (set! shadow-node/closure-import original-import)))))

(def ^:private config-digest
  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")

(def ^:private canonical-test-schema-rows
  "One wall-clock-stable schema population shared by page-plan fixtures."
  (client/index-schemas))

(defn- build-page-plan
  [program page-rows]
  (client/build-page-plan
   {:seon.execution/artifact-digest digest
    :seon.db.initialization/config-manifest-digest config-digest
    :seon.db.initialization/page-rows page-rows
    :seon.db/program (into canonical-test-schema-rows program)}))

(deftest page-plan-is-one-deterministic-complete-value
  (let [program
        [{:seon.schema/key :example/id :seon.schema/form ":int"}
         {:seon.ns/name 'example.core :seon.ns/source "(ns example.core)"}
         {:seon.fn/sym "example.core/identity"
          :seon.fn/ns [:seon.ns/name 'example.core]
          :seon.fn/source "(defn identity [value] value)"
          :seon.fn/spec "[:=> [:cat :example/id] :example/id]"}]
        forward (build-page-plan program 64)
        replay (build-page-plan program 64)
        pages (:seon.db/initialization-pages forward)
        attributes (into #{} (mapcat :seon.db/attributes) pages)
        initial-data (into [] (mapcat :seon.db/initial-data) pages)]
    (is (= forward replay))
    (is (= digest (:seon.execution/artifact-digest forward)))
    (is (= config-digest
           (:seon.db.initialization/config-manifest-digest forward)))
    (is (every? #(= 64 (:seon.db.initialization/page-rows %)) pages))
    (is (every? attributes [:my.plan/namespace :my.plan/claim]))
    (is (every? attributes [:seon.ns/doc :seon.ns/summary]))
    (is (every? attributes legacy-cold-boot-attributes))
    (is (some #{:my.kb/source-line-end} attributes))
    (is (= (db/encode-edn-slot-values
            [{:seon.user/id "user"} {:my.kb.shared/id "shared"}])
           initial-data))
    (is (not-any? #(contains? % :seon.config/id) initial-data)
        "config writes belong only to reconcile-config!")))

(deftest page-plan-fingerprint-binds-config-manifest-digest
  (let [program [{:seon.schema/key :example/id :seon.schema/form ":int"}]
        first-plan (build-page-plan program 64)
        second-plan
        (client/build-page-plan
         {:seon.execution/artifact-digest digest
          :seon.db.initialization/config-manifest-digest
          "123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0"
          :seon.db.initialization/page-rows 64
          :seon.db/program (into canonical-test-schema-rows program)})]
    (is (not=
         (:seon.db.initialization/fingerprint
          (first (:seon.db/initialization-pages first-plan)))
         (:seon.db.initialization/fingerprint
          (first (:seon.db/initialization-pages second-plan)))))))

(deftest ten-times-synthetic-corpus-keeps-every-page-plan-frame-bounded
  (let [base-program
        [{:seon.schema/key :example/id :seon.schema/form ":int"}
         {:seon.ns/name 'example.core :seon.ns/source "(ns example.core)"}]
        synthetic-program (into [] cat (repeat 500 base-program))
        page-plan (build-page-plan synthetic-program 64)
        pages (:seon.db/initialization-pages page-plan)
        writer (transit/writer :json)
        encoder (js/TextEncoder.)
        frame-sizes
        (mapv
         (fn [page]
           (.-byteLength
            (.encode
             encoder
             (transit/write
              writer
              (protocol/ensure-database-request
               {::protocol/request-id
                (str "frame/"
                     (:seon.db.initialization/page-index page))
                ::protocol/database-name "synthetic"
                ::protocol/backend :memory
                :seon.db/initialization-page page})))))
         pages)
        maximum-frame-bytes (apply max frame-sizes)
        evidence {:synthetic-program-rows (count synthetic-program)
                  :page-count (count pages)
                  :maximum-frame-bytes maximum-frame-bytes
                  :protocol-frame-ceiling protocol/maximum-frame-bytes}]
    (js/console.log "INITPAGE_10X_MEASUREMENT" (pr-str evidence))
    (is (> (count pages) 10)
        (pr-str evidence))
    (is (< maximum-frame-bytes (* 1024 1024))
        (pr-str evidence))
    (is (every? #(< % protocol/maximum-frame-bytes) frame-sizes)
        (pr-str evidence))))

(deftest identical-applied-identity-submits-no-transaction
  (async done
    (let [fingerprint "page-plan-fingerprint"
          expected
          {:seon.db.initialization/fingerprint fingerprint
           :seon.db.initialization/release-digest digest
           :seon.db.initialization/config-manifest-digest config-digest}
          original-db db/db
          original-entity db/entity
          original-transact db/transact!
          transactions (atom 0)
          cleanup!
          (fn []
            (set! db/db original-db)
            (set! db/entity original-entity)
            (set! db/transact! original-transact)
            (done))]
      (set! db/db
            (fn
              ([]
               (js/Promise.resolve
                {:db-name "r45s3" :t 42
                 :datahike/commit-id (random-uuid)}))
              ([_request]
               (js/Promise.resolve
                {:db-name "r45s3" :t 42
                 :datahike/commit-id (random-uuid)}))))
      (set! db/entity
            (fn
              ([_ref]
               (js/Promise.resolve
                (assoc expected
                       :seon.db.initialization/id "database"
                       :seon.db.initialization/page-count 9
                       :seon.db.initialization/status
                       :seon.db.initialization.status/complete)))
              ([_database _ref]
               (js/Promise.resolve
                (assoc expected
                       :seon.db.initialization/id "database"
                       :seon.db.initialization/page-count 9
                       :seon.db.initialization/status
                       :seon.db.initialization.status/complete)))))
      (set! db/transact!
            (fn [_request]
              (swap! transactions inc)
              (js/Promise.resolve {})))
      (-> (js/Promise.resolve nil)
          (.then
           (fn ^:async run []
             (let [result
                   (await (#'client/stamp-applied-identity! expected))]
               (is (false? (:seon.cluster.apply/changed? result)))
               (is (zero? @transactions)))))
          (.catch #(is false (str %)))
          (.finally cleanup!)))))

(deftest startup-recovery-accepts-domain-data-and-throws-direct-errors
  (let [recovery-result! (deref #'client/recovery-result!)
        domain-result {::recovery/repaired? false
                       ::recovery/agent-ids []
                       ::recovery/run-ids []
                       ::recovery/turn-ids []
                       ::recovery/eval-ids []}
        direct-error {:seon.error/message "expected database is stale"
                      :seon.error/kind :user-input}
        thrown (try
                 (recovery-result! direct-error)
                 nil
                 (catch :default exception exception))]
    (is (identical? domain-result (recovery-result! domain-result)))
    (is (= direct-error (ex-data thrown)))))

(deftest config-free-startup-initializes-from-retained-config
  (async done
    (let [open-startup! (deref #'client/open-startup-session!)
          original-open client/open-database-session!
          original-db db/db
          original-entity db/entity
          original-resolve config/resolve-config-singleton
          retained (assoc configuration
                          :seon.config/always #{'custom.core})
          stored retained
          cleanup!
          (fn []
            (set! client/open-database-session! original-open)
            (set! db/db original-db)
            (set! db/entity original-entity)
            (set! config/resolve-config-singleton original-resolve)
            (done))
          requests (atom [])]
      (set! client/open-database-session!
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve {::db/db {:db-name "default"}})))
      (set! db/db
            (fn
              ([] (js/Promise.resolve {:db-name "default"}))
              ([_request] (js/Promise.resolve {:db-name "default"}))))
      (set! db/entity
            (fn
              ([_entity-id] (js/Promise.resolve stored))
              ([_database _entity-id] (js/Promise.resolve stored))))
      (set! config/resolve-config-singleton
            (fn [_]
              (throw
               (js/Error. "config-free reopen must not resolve defaults"))))
      (try
        (-> (open-startup! true nil)
            (.then
             (fn [_]
               (is (= [{::client/initialize? false}
                       {::client/initialize? true
                        ::client/configuration retained}]
                      @requests)
                   "attach first, then initialize from the retained decoded policy")))
            (.catch
             (fn [error]
               (is false (str "config-free startup rejected: " error
                              "\n" (.-stack error)))))
            (.finally cleanup!))
        (catch :default error
          (is false (str "config-free startup threw synchronously: " error))
          (cleanup!))))))

(deftest selected-startup-shares-one-resolved-configuration
  (async done
    (let [select-configuration
          (deref #'client/selected-startup-configuration)
          open-startup! (deref #'client/open-startup-session!)
          original-resolve config/resolve-config-singleton
          original-open client/open-database-session!
          original-skills skills/seed-skills-tx-data
          original-reconcile state/reconcile!
          original-migrate ctx.admin/migrate-plan-surface-default!
          resolved (assoc configuration :seon.config.render/eval-cap 42)
          resolve-count (atom 0)
          opened-configuration (atom nil)
          applied-configuration (atom nil)
          applied-request (atom nil)
          cleanup!
          (fn []
            (set! config/resolve-config-singleton original-resolve)
            (set! client/open-database-session! original-open)
            (set! skills/seed-skills-tx-data original-skills)
            (set! state/reconcile! original-reconcile)
            (set! ctx.admin/migrate-plan-surface-default! original-migrate)
            (done))
          manifest {:seon.config/render
                    {:seon.config.render/eval-cap 42}}]
      (set! config/resolve-config-singleton
            (fn
              ([_]
               (swap! resolve-count inc)
               resolved)
              ([_ _]
               (swap! resolve-count inc)
               resolved)))
      (set! client/open-database-session!
            (fn [request]
              (reset! opened-configuration (::client/configuration request))
              (js/Promise.resolve {::db/db {:db-name "default"}})))
      (set! skills/seed-skills-tx-data
            (fn
              ([] [])
              ([_directory] [])))
      (set! state/reconcile!
            (fn [request]
              (reset! applied-request request)
              (reset! applied-configuration
                      (last (:seon.runtime.state/desired request)))
              (js/Promise.resolve
               {:seon.runtime.state/ok? true
                :seon.runtime.state/changed? false
                :seon.runtime.state/operations 0
                :seon.runtime.state/attempts 1})))
      (set! ctx.admin/migrate-plan-surface-default!
            (fn []
              (js/Promise.resolve
               {::ctx/ok? true ::ctx/changed? false ::ctx/operations 0})))
      (try
        (let [selected (select-configuration manifest)]
          (-> (open-startup! true selected)
              (.then
               (fn [_]
                 (#'client/reconcile-config! manifest selected)))
              (.then
               (fn [_]
                 (is (= 1 @resolve-count))
                 (is (identical? selected @opened-configuration))
                 (is (identical? selected @applied-configuration))
                 (is (= #{:seon.db.process/boot
                          :seon.db.process/config}
                        (:seon.db/managed-scope @applied-request)))))
              (.catch
               (fn [error]
                 (is false (str "selected startup rejected: " error
                                "\n" (.-stack error)))))
              (.finally cleanup!)))
        (catch :default error
          (is false (str "selected startup threw synchronously: " error))
          (cleanup!))))))

(deftest declared-ai-selection-joins-the-one-config-reconcile
  (async done
    (let [original-skills skills/seed-skills-tx-data
          original-reconcile state/reconcile!
          original-migrate ctx.admin/migrate-plan-surface-default!
          applied-request (atom nil)
          manifest {:seon.config/ai
                    {:seon.ai/provider :deepseek
                     :seon.ai/model "deepseek-v4-pro"
                     :seon.ai/base-url "https://api.deepseek.com"
                     :seon.ai/api-key-env "DEEPSEEK_API_KEY"}}
          expected-ai (first (config/resolve-ai-config manifest))
          cleanup!
          (fn []
            (set! skills/seed-skills-tx-data original-skills)
            (set! state/reconcile! original-reconcile)
            (set! ctx.admin/migrate-plan-surface-default! original-migrate)
            (done))]
      (set! skills/seed-skills-tx-data (fn [_directory] []))
      (set! state/reconcile!
            (fn [request]
              (reset! applied-request request)
              (js/Promise.resolve
               {:seon.runtime.state/ok? true
                :seon.runtime.state/changed? false
                :seon.runtime.state/operations 0
                :seon.runtime.state/attempts 1})))
      (set! ctx.admin/migrate-plan-surface-default!
            (fn []
              (js/Promise.resolve
               {::ctx/ok? true ::ctx/changed? false ::ctx/operations 0})))
      (-> (js/Promise.resolve
           (#'client/reconcile-config! manifest configuration))
          (.then
           (fn [_]
             (let [desired (:seon.runtime.state/desired @applied-request)]
               (is (= expected-ai (last desired)))
               (is (= configuration (nth desired (- (count desired) 2))))
               (is (contains?
                    (:seon.db/managed-identity-attrs @applied-request)
                    :seon.ai/id)
                   "the declared row joins the existing managed population")
               (is (= #{[:seon.ai/id "config"]}
                      (::state/adopt-identities @applied-request))
                   "only the explicitly declared row is adopted"))))
          (.catch
           (fn [error]
             (is false (str "declared AI reconciliation rejected: " error
                            "\n" (.-stack error)))))
          (.finally cleanup!)))))

(deftest acme-request-adopts-only-its-declared-typeahead-selection
  (async done
    (let [original-skills skills/seed-skills-tx-data
          original-reconcile state/reconcile!
          original-host-coordinates agent/reconcile-host-coordinates!
          original-migrate ctx.admin/migrate-plan-surface-default!
          applied-request (atom nil)
          manifest (config/read-config-file "config/acme.edn")
          expected-ai {:seon.ai/id "config"
                       :seon.ai/provider :typeahead}
          cleanup!
          (fn []
            (set! skills/seed-skills-tx-data original-skills)
            (set! state/reconcile! original-reconcile)
            (set! agent/reconcile-host-coordinates! original-host-coordinates)
            (set! ctx.admin/migrate-plan-surface-default! original-migrate)
            (done))]
      (set! skills/seed-skills-tx-data (fn [_directory] []))
      (set! state/reconcile!
            (fn [request]
              (reset! applied-request request)
              (js/Promise.resolve
               {:seon.runtime.state/ok? true
                :seon.runtime.state/changed? false
                :seon.runtime.state/operations 0
                :seon.runtime.state/attempts 1})))
      (set! agent/reconcile-host-coordinates!
            (fn [_configuration]
              (js/Promise.resolve
               {::agent/host-coordinate-ok? true
                ::agent/host-coordinate-changed? false
                ::agent/host-coordinate-operations 0})))
      (set! ctx.admin/migrate-plan-surface-default!
            (fn []
              (js/Promise.resolve
               {::ctx/ok? true ::ctx/changed? false ::ctx/operations 0})))
      (-> (js/Promise.resolve
           (#'client/reconcile-config!
            manifest
            (config/resolve-config-singleton manifest)))
          (.then
           (fn [_]
             (is (= expected-ai
                    (last (:seon.runtime.state/desired @applied-request)))
                 "ACME's own declaration produces the Typeahead desired row")
             (is (= #{[:seon.ai/id "config"]}
                    (::state/adopt-identities @applied-request))
                 "the request adopts exactly the identity ACME declared")))
          (.catch
           (fn [error]
             (is false (str "ACME request construction rejected: " error
                            "\n" (.-stack error)))))
          (.finally cleanup!)))))

(deftest absent-ai-selection-stays-out-of-config-reconcile
  (async done
    (let [original-skills skills/seed-skills-tx-data
          original-reconcile state/reconcile!
          original-migrate ctx.admin/migrate-plan-surface-default!
          applied-request (atom nil)
          cleanup!
          (fn []
            (set! skills/seed-skills-tx-data original-skills)
            (set! state/reconcile! original-reconcile)
            (set! ctx.admin/migrate-plan-surface-default! original-migrate)
            (done))]
      (set! skills/seed-skills-tx-data (fn [_directory] []))
      (set! state/reconcile!
            (fn [request]
              (reset! applied-request request)
              (js/Promise.resolve
               {:seon.runtime.state/ok? true
                :seon.runtime.state/changed? false
                :seon.runtime.state/operations 0
                :seon.runtime.state/attempts 1})))
      (set! ctx.admin/migrate-plan-surface-default!
            (fn []
              (js/Promise.resolve
               {::ctx/ok? true ::ctx/changed? false ::ctx/operations 0})))
      (-> (js/Promise.resolve
           (#'client/reconcile-config! {} configuration))
          (.then
           (fn [_]
             (is (= configuration
                    (last (:seon.runtime.state/desired @applied-request))))
             (is (not (contains?
                       (:seon.db/managed-identity-attrs @applied-request)
                       :seon.ai/id))
                 "an absent section cannot retract or rewrite an existing row")
             (is (nil? (::state/adopt-identities @applied-request))
                 "manifest silence adopts no identity")))
          (.catch
           (fn [error]
             (is false (str "absent AI reconciliation rejected: " error
                            "\n" (.-stack error)))))
          (.finally cleanup!)))))

(deftest config-apply-sequences-and-folds-plan-default-migration
  (async done
    (let [original-skills skills/seed-skills-tx-data
          original-reconcile state/reconcile!
          original-migrate ctx.admin/migrate-plan-surface-default!
          original-host-coordinates agent/reconcile-host-coordinates!
          effects (atom [])
          reconcile-result (atom nil)
          migration-result (atom nil)
          migration-calls (atom 0)
          cleanup!
          (fn []
            (set! skills/seed-skills-tx-data original-skills)
            (set! state/reconcile! original-reconcile)
            (set! ctx.admin/migrate-plan-surface-default! original-migrate)
            (set! agent/reconcile-host-coordinates! original-host-coordinates)
            (done))
          apply! (fn []
                   (#'client/reconcile-config! {} configuration))]
      (set! skills/seed-skills-tx-data
            (fn
              ([] [])
              ([_directory] [])))
      (set! state/reconcile!
            (fn [_request]
              (swap! effects conj :reconcile)
              (js/Promise.resolve @reconcile-result)))
      (set! ctx.admin/migrate-plan-surface-default!
            (fn []
              (swap! effects conj :migrate)
              (swap! migration-calls inc)
              (js/Promise.resolve @migration-result)))
      (set! agent/reconcile-host-coordinates!
            (fn [_configuration]
              (swap! effects conj :host-coordinates)
              (js/Promise.resolve
               {::agent/host-coordinate-ok? true
                ::agent/host-coordinate-changed? false
                ::agent/host-coordinate-operations 0})))
      (reset! reconcile-result
              {:seon.runtime.state/ok? false
               :seon.runtime.state/error "reconcile failed"
               :seon.runtime.state/attempts 1})
      (-> (apply!)
          (.then
           (fn [failed-reconcile]
             (is (= @reconcile-result failed-reconcile))
             (is (= [:reconcile] @effects)
                 "a failed managed reconciliation never runs the migration")
             (is (zero? @migration-calls))
             (reset! effects [])
             (reset! reconcile-result
                     {:seon.runtime.state/ok? true
                      :seon.runtime.state/changed? false
                      :seon.runtime.state/operations 2
                      :seon.runtime.state/attempts 1})
             (reset! migration-result
                     {::ctx/ok? false ::ctx/error "migration failed"})
             (apply!)))
          (.then
           (fn [failed-migration]
             (is (= {:seon.error/message "migration failed"
                     :seon.error/kind :core-bug}
                    failed-migration))
             (is (= [:reconcile :host-coordinates :migrate] @effects))
             (reset! effects [])
             (reset! migration-result
                     {::ctx/ok? true
                      ::ctx/changed? true
                      ::ctx/operations 3})
             (apply!)))
          (.then
           (fn [combined]
             (is (= {:seon.runtime.state/ok? true
                     :seon.runtime.state/changed? true
                     :seon.runtime.state/operations 5
                     :seon.runtime.state/attempts 1}
                    combined))
             (is (= [:reconcile :host-coordinates :migrate] @effects)
                 "host-coordinate and plan reconciliation follow config")))
          (.catch (fn [error] (is false (str error))))
          (.finally cleanup!)))))

(deftest config-retry-observes-the-commit-before-a-migration-failure
  (async done
    (let [original-routes config/resolve-routes
          original-skills skills/seed-skills-tx-data
          original-db db/db
          original-execute db/execute-many
          original-transact db/transact!
          original-host-coordinates agent/reconcile-host-coordinates!
          original-migrate ctx.admin/migrate-plan-surface-default!
          singleton {:seon.config/id config/cluster-config-id}
          database (atom {:db-name "default"
                          :t 42
                          :as-of nil
                          :since nil
                          :history false
                          :datahike/commit-id
                          #uuid "00000000-0000-0000-0000-000000000042"})
          committed (atom nil)
          transactions (atom [])
          acquisitions (atom [])
          migration-calls (atom 0)
          host-calls (atom 0)
          installed {:seon.config/id
                     {:db/unique :db.unique/identity}}
          acquisition
          (fn []
            (let [entity-rows
                  (if-let [entity @committed]
                    [[41 (assoc entity :db/id 41)]]
                    [])
                  provenance-rows (if @committed [[41 100]] [])
                  process-rows
                  (if @committed [[100 :seon.db.process/config]] [])]
              {::db/results
               [(protocol/success {::protocol/schema installed})
                (protocol/success
                 {:datahike.query/result entity-rows})
                (protocol/success
                 {:datahike.query/result provenance-rows})
                (protocol/success
                 {:datahike.query/result process-rows})]}))
          cleanup!
          (fn []
            (set! config/resolve-routes original-routes)
            (set! skills/seed-skills-tx-data original-skills)
            (set! db/db original-db)
            (set! db/execute-many original-execute)
            (set! db/transact! original-transact)
            (set! agent/reconcile-host-coordinates! original-host-coordinates)
            (set! ctx.admin/migrate-plan-surface-default! original-migrate)
            (done))
          apply! (fn [] (#'client/reconcile-config! {} singleton))]
      ;; Keep the desired population to the real config singleton so the test
      ;; isolates commit/retry behavior from the shipped route and skill data.
      (set! config/resolve-routes (fn [_routes _manifest] []))
      (set! skills/seed-skills-tx-data (fn [_directory] []))
      (set! db/db
            (fn
              ([] (js/Promise.resolve @database))
              ([_request] (js/Promise.resolve @database))))
      (set! db/execute-many
            (fn [request]
              (swap! acquisitions conj
                     {:seon.db/db (::db/db request)
                      :seon.db/member-count (count (::db/members request))
                      :seon.client-initialization-test/committed @committed})
              (js/Promise.resolve (acquisition))))
      (set! db/transact!
            (fn [& [request]]
              (let [before @database
                    tx-data (::db/tx-data request)
                    after (assoc before
                                 :t (inc (:t before))
                                 :datahike/commit-id
                                 #uuid "00000000-0000-0000-0000-000000000043")]
                (swap! transactions conj request)
                (reset! committed (first tx-data))
                (reset! database after)
                (js/Promise.resolve
                 {:db-before before
                  :db-after after
                  :tx-data tx-data
                  :tempids {}
                  :tx-meta {}}))))
      (set! agent/reconcile-host-coordinates!
            (fn [_configuration]
              (swap! host-calls inc)
              (js/Promise.resolve
               {::agent/host-coordinate-ok? true
                ::agent/host-coordinate-changed? false
                ::agent/host-coordinate-operations 0})))
      (set! ctx.admin/migrate-plan-surface-default!
            (fn []
              (if (= 1 (swap! migration-calls inc))
                (js/Promise.resolve
                 {::ctx/ok? false ::ctx/error "injected migration failure"})
                (js/Promise.resolve
                 {::ctx/ok? true ::ctx/changed? false ::ctx/operations 0}))))
      (-> (apply!)
          (.then
           (fn [failed]
             (is (= :core-bug (:seon.error/kind failed)))
             (is (= singleton @committed)
                 "the managed config transaction committed before migration")
             (is (= 1 (count @transactions)))
             (apply!)))
          (.then
           (fn [retried]
             (is (= {:seon.runtime.state/ok? true
                     :seon.runtime.state/changed? false
                     :seon.runtime.state/operations 0
                     :seon.runtime.state/attempts 1}
                    retried)
                 "retry reacquires the committed row and converges")
             (is (= 1 (count @transactions))
                 "convergence submits no replacement transaction")
             (is (= [nil singleton]
                    (mapv :seon.client-initialization-test/committed
                          @acquisitions))
                 "the second real reconcile acquisition sees the first commit")
             (is (every? #(= 4 (:seon.db/member-count %)) @acquisitions))
             (is (= 2 @host-calls))
             (is (= 2 @migration-calls))))
          (.catch
           (fn [error]
             (is false (str "stateful config retry rejected: " error
                            "\n" (.-stack error)))))
          (.finally cleanup!)))))

(defn- shadow-ready-state
  []
  (let [owner (js-obj)]
    (assoc @client/!state
           ::client/launch-capability {::client/autonomous? true}
           ::client/runtime-phase :seon.client.runtime/running
           ::client/advertisement-owner owner
           ::client/advertisement-interest-key :runtime-advertisement
           ::client/resumable-agent-ids ["root"])))

(deftest completed-reload-ensures-before-publication-and-rehosting
  (async done
    (let [original-state @client/!state
          original-attached? db/attached?
          original-db db/db
          original-entity db/entity
          original-open client/open-database-session!
          original-begin admission/begin-publication!
          original-publish admission/publish-committed!
          original-unavailable admission/mark-unavailable!
          original-resume lifecycle/resume!
          original-restore generate-code/restore-root-schedulers!
          original-install agent-loop/install-ticker!
          original-heartbeat client/start-heartbeat!
          effects (atom [])
          attached? (atom false)
          finish-resume (atom nil)
          reload-settled-resolve (atom nil)
          reload-settled
          (js/Promise.
           (fn [resolve _] (reset! reload-settled-resolve resolve)))
          finish (atom nil)
          finished (js/Promise. (fn [resolve _] (reset! finish resolve)))
          publish!
          (fn []
            (swap! effects conj :publish)
            (js/Promise.resolve
             {::admission/published? true
              ::admission/instrumentation {}}))]
      (reset! client/!state (shadow-ready-state))
      (set! db/attached? #(deref attached?))
      (set! db/db
            (fn
              ([] (js/Promise.resolve {:db-name "default"}))
              ([_request] (js/Promise.resolve {:db-name "default"}))))
      (set! db/entity
            (fn
              ([_entity-id]
               (js/Promise.resolve configuration))
              ([_database _entity-id]
               (js/Promise.resolve configuration))))
      (set! admission/begin-publication!
            (fn [] (swap! effects conj :close) true))
      (set! client/open-database-session!
            (fn [request]
              (swap! effects conj [::ensure-acquire request])
              (reset! attached? true)
              (js/Promise.resolve {::db/db {:db-name "default"}})))
      (set! admission/publish-committed!
            (fn
              ([] (publish!))
              ([_request] (publish!))))
      (set! admission/mark-unavailable!
            (fn [failure]
              (swap! effects conj :unavailable)
              (@reload-settled-resolve
               {::reload-outcome :unavailable
                ::reload-failure failure})
              true))
      (set! lifecycle/resume!
            (fn [request]
              (swap! effects conj [::resume request])
              (@reload-settled-resolve {::reload-outcome :rehost})
              (js/Promise.
               (fn [resolve _]
                 (reset! finish-resume resolve)))))
      (set! generate-code/restore-root-schedulers!
            (fn [request]
              (swap! effects conj [::restore-schedulers request])
              (js/Promise.resolve [])))
      (set! agent-loop/install-ticker!
            (fn [configuration]
              (swap! effects conj [:ticker configuration])))
      (set! client/start-heartbeat!
            (fn []
              (swap! effects conj :heartbeat)
              (@finish true)))
      (is (true? (client/shadow-build-notify! {:type :build-start})))
      (is (true? (client/shadow-build-notify! {:type :build-complete})))
      (-> reload-settled
          (.then
           (fn [{::keys [reload-outcome reload-failure] :as settled}]
             (is (= :rehost reload-outcome)
                 (str "reload became unavailable before rehosting: "
                      (pr-str reload-failure)))
             (if (= :rehost reload-outcome)
               (do
                 (is (= [:close
                         [::ensure-acquire
                          {::client/initialize? true
                           ::client/configuration configuration}]
                         :publish
                         [::resume {:seon.agent/id "root"}]]
                        @effects)
                     "a running runtime recovers its lost session before rehosting")
                 (@finish-resume
                  {:seon.agent.lifecycle/resumed? true
                   :seon.agent/id "root"})
                 (-> finished
                     (.then (fn [_] settled))))
               settled)))
          (.then
           (fn [{::keys [reload-outcome]}]
             (when (= :rehost reload-outcome)
               (is (= [:close
                       [::ensure-acquire
                        {::client/initialize? true
                         ::client/configuration configuration}]
                       :publish
                       [::resume {:seon.agent/id "root"}]
                       [::restore-schedulers
                        {::db/db {:db-name "default"}
                         :seon.config/model-variant :execution}]
                       [:ticker configuration]
                       :heartbeat]
                      @effects)
                   "reload has one ensure/acquire, publication, and rehost order"))))
          (.catch
           (fn [error]
             (is false (str "completed reload rejected: " error
                            "\n" (.-stack error)))))
          (.finally
           (fn []
             (reset! client/!state original-state)
             (set! db/attached? original-attached?)
             (set! db/db original-db)
             (set! db/entity original-entity)
             (set! client/open-database-session! original-open)
             (set! admission/begin-publication! original-begin)
             (set! admission/publish-committed! original-publish)
             (set! admission/mark-unavailable! original-unavailable)
             (set! lifecycle/resume! original-resume)
             (set! generate-code/restore-root-schedulers! original-restore)
             (set! agent-loop/install-ticker! original-install)
             (set! client/start-heartbeat! original-heartbeat)
             (done)))))))

(deftest missing-reload-config-keeps-admission-closed-and-skips-rehost
  (async done
    (let [original-state @client/!state
          original-attached? db/attached?
          original-db db/db
          original-entity db/entity
          original-open client/open-database-session!
          original-begin admission/begin-publication!
          original-publish admission/publish-committed!
          original-unavailable admission/mark-unavailable!
          original-resume lifecycle/resume!
          original-install agent-loop/install-ticker!
          original-heartbeat client/start-heartbeat!
          effects (atom [])
          admission-open? (atom true)
          finish (atom nil)
          finished (js/Promise. (fn [resolve _] (reset! finish resolve)))]
      (reset! client/!state (shadow-ready-state))
      (set! db/attached? (constantly true))
      (set! db/db
            (fn
              ([] (js/Promise.resolve {:db-name "default"}))
              ([_request] (js/Promise.resolve {:db-name "default"}))))
      (set! db/entity
            (fn
              ([_entity-id] (js/Promise.resolve nil))
              ([_database _entity-id] (js/Promise.resolve nil))))
      (set! admission/begin-publication!
            (fn []
              (reset! admission-open? false)
              (swap! effects conj :close)
              true))
      (set! client/open-database-session!
            (fn [_]
              (swap! effects conj :ensure-acquire)
              (js/Promise.reject (js/Error. "ensure failed"))))
      (set! admission/publish-committed!
            (fn []
              (reset! admission-open? true)
              (swap! effects conj :publish)
              (js/Promise.resolve {::admission/published? true})))
      (set! admission/mark-unavailable!
            (fn [_]
              (reset! admission-open? false)
              (swap! effects conj :unavailable)
              (@finish true)
              true))
      (set! lifecycle/resume!
            (fn [_]
              (swap! effects conj :rehost)
              (js/Promise.resolve {:seon.agent.lifecycle/resumed? true})))
      (set! agent-loop/install-ticker!
            (fn [_configuration] (swap! effects conj :ticker)))
      (set! client/start-heartbeat!
            (fn [] (swap! effects conj :heartbeat)))
      (is (true? (client/shadow-build-notify! {:type :build-start})))
      (is (true? (client/shadow-build-notify! {:type :build-complete})))
      (-> finished
          (.then
           (fn [_]
             (is (false? @admission-open?))
             (is (= [:close :unavailable] @effects)
                 "missing retained config cannot ensure, publish, rehost, tick, or heartbeat")))
          (.catch
           (fn [error]
             (is false (str "missing config proof rejected: " error
                            "\n" (.-stack error)))))
          (.finally
           (fn []
             (reset! client/!state original-state)
             (set! db/attached? original-attached?)
             (set! db/db original-db)
             (set! db/entity original-entity)
             (set! client/open-database-session! original-open)
             (set! admission/begin-publication! original-begin)
             (set! admission/publish-committed! original-publish)
             (set! admission/mark-unavailable! original-unavailable)
             (set! lifecycle/resume! original-resume)
             (set! agent-loop/install-ticker! original-install)
             (set! client/start-heartbeat! original-heartbeat)
             (done)))))))
