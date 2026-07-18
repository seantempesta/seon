(ns seon.client-initialization-test
  (:require
   [cljs.test :refer [async deftest is testing]]
   [my.skills :as skills]
   [seon.agent :as agent]
   [seon.agent.loop :as agent-loop]
   [seon.client :as client]
   [seon.config :as config]
   [seon.db :as db]
   [seon.launch :as launch]
   [seon.runtime.admission :as admission]
   [seon.runtime.recovery :as recovery]
   [seon.state :as state]))

(def ^:private digest
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

(defn- descriptor []
  {::launch/runtime {::launch/execution-digest digest}})

(def ^:private configuration
  (config/resolve-config-singleton {}))

(defn- with-program-builders
  [core schemas body]
  (let [original-core client/index-core!
        original-schemas client/index-schemas]
    (set! client/index-core! (fn [_configuration] core))
    (set! client/index-schemas (fn [] schemas))
    (try
      (body)
      (finally
        (set! client/index-core! original-core)
        (set! client/index-schemas original-schemas)))))

(deftest initialization-is-one-deterministic-complete-value
  (let [namespace-row
        {:seon.ns/name :example.core
         :seon.ns/source "(ns example.core)"}
        function-row
        {:seon.fn/sym "example.core/identity"
         :seon.fn/ns [:seon.ns/name :example.core]
         :seon.fn/source "(defn identity [value] value)"
         :seon.fn/spec "[:=> [:cat :example/id] :example/id]"
         :seon.fn/created-at (js/Date. 1)}
        schema-row
        {:seon.schema/key :example/id
         :seon.schema/form ":int"
         :seon.schema/created-at (js/Date. 2)}
        build (deref #'client/database-initialization)
        forward
        (with-program-builders
          [function-row namespace-row]
          [schema-row]
          #(build (descriptor) configuration))
        reverse
        (with-program-builders
          [namespace-row function-row]
          [schema-row]
          #(build (descriptor) configuration))]
    (is (= forward reverse))
    (is (= digest (:seon.execution/artifact-digest forward)))
    (is (= client/agent-bootstrap-attrs (:seon.db/attributes forward)))
    (is (some #{:seon.render/full?} (:seon.db/attributes forward)))
    (is (= [:seon.ns/name :seon.fn/sym :seon.schema/key]
           (mapv (fn [row]
                   (cond
                     (:seon.ns/name row) :seon.ns/name
                     (:seon.fn/sym row) :seon.fn/sym
                     (:seon.schema/key row) :seon.schema/key))
                 (:seon.db/program forward))))
    (is (not-any? #(or (contains? % :seon.fn/created-at)
                       (contains? % :seon.schema/created-at))
                  (:seon.db/program forward)))
    (is (= [configuration
            {:seon.user/id "user"}
            {:my.kb.shared/id "shared"}]
           (:seon.db/initial-data forward)))))

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
                          :seon.config/always #{:custom.core}
                          :seon.config/current-ns :off)
          stored (update retained :seon.config/always pr-str)
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
          resolved (assoc configuration :seon.config/current-ns :off)
          resolve-count (atom 0)
          opened-configuration (atom nil)
          applied-configuration (atom nil)
          cleanup!
          (fn []
            (set! config/resolve-config-singleton original-resolve)
            (set! client/open-database-session! original-open)
            (set! skills/seed-skills-tx-data original-skills)
            (set! state/reconcile! original-reconcile)
            (done))
          manifest {:seon.config/namespaces
                    {:seon.config/current-ns :off}}]
      (set! config/resolve-config-singleton
            (fn [_]
              (swap! resolve-count inc)
              resolved))
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
              (reset! applied-configuration
                      (last (:seon.state/desired request)))
              (js/Promise.resolve
               {:seon.state/ok? true
                :seon.state/changed? false
                :seon.state/operations 0
                :seon.state/attempts 1})))
      (try
        (let [selected (select-configuration manifest)]
          (-> (open-startup! true selected)
              (.then
               (fn [_]
                 (client/apply-config!
                  {:seon.config/manifest manifest
                   ::client/configuration selected})))
              (.then
               (fn [_]
                 (is (= 1 @resolve-count))
                 (is (identical? selected @opened-configuration))
                 (is (identical? selected @applied-configuration))))
              (.catch
               (fn [error]
                 (is false (str "selected startup rejected: " error
                                "\n" (.-stack error)))))
              (.finally cleanup!)))
        (catch :default error
          (is false (str "selected startup threw synchronously: " error))
          (cleanup!))))))

(deftest invalid-complete-program-fails-before-session-open
  (async done
    (let [original-descriptor launch/process-launch-descriptor
          original-open db/open-session!
          original-core client/index-core!
          original-schemas client/index-schemas
          opened? (atom false)]
      (set! launch/process-launch-descriptor (descriptor))
      (set! client/index-core!
            (fn [_configuration]
              [{:seon.ns/name :example.core
                :seon.ns/source "(ns example.core)"}
               {:seon.fn/sym "example.core/broken"
                :seon.fn/ns [:seon.ns/name :example.core]
                :seon.fn/source "(defn broken [value] value)"
                :seon.fn/spec
                "[:=> [:cat :example/missing] :example/missing]"}]))
      (set! client/index-schemas
            (fn []
              [{:seon.schema/key :example/id :seon.schema/form ":int"}]))
      (set! db/open-session!
            (fn [_]
              (reset! opened? true)
              (js/Promise.resolve {})))
      (-> (client/open-database-session!
           {:seon.client/initialize? true
            :seon.client/configuration configuration})
          (.then
           (fn [_]
             (is false "invalid complete projection was admitted")))
          (.catch
           (fn [_]
             (testing "the full schema and function projection is validated first"
               (is (false? @opened?)))))
          (.finally
           (fn []
             (set! launch/process-launch-descriptor original-descriptor)
             (set! client/index-core! original-core)
             (set! client/index-schemas original-schemas)
             (set! db/open-session! original-open)
             (done)))))))

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
          original-resume agent/resume!
          original-install agent-loop/install-ticker!
          original-heartbeat client/start-heartbeat!
          effects (atom [])
          attached? (atom false)
          finish-resume (atom nil)
          rehost-started-resolve (atom nil)
          rehost-started
          (js/Promise.
           (fn [resolve _] (reset! rehost-started-resolve resolve)))
          finish (atom nil)
          finished (js/Promise. (fn [resolve _] (reset! finish resolve)))]
      (reset! client/!state (shadow-ready-state))
      (set! db/attached? #(deref attached?))
      (set! db/db
            (fn
              ([] (js/Promise.resolve {:db-name "default"}))
              ([_request] (js/Promise.resolve {:db-name "default"}))))
      (set! db/entity
            (fn
              ([_entity-id]
               (js/Promise.resolve
                (update configuration :seon.config/always pr-str)))
              ([_database _entity-id]
               (js/Promise.resolve
                (update configuration :seon.config/always pr-str)))))
      (set! admission/begin-publication!
            (fn [] (swap! effects conj :close) true))
      (set! client/open-database-session!
            (fn [request]
              (swap! effects conj [::ensure-acquire request])
              (reset! attached? true)
              (js/Promise.resolve {::db/db {:db-name "default"}})))
      (set! admission/publish-committed!
            (fn []
              (swap! effects conj :publish)
              (js/Promise.resolve
               {::admission/published? true
                ::admission/instrumentation {}})))
      (set! admission/mark-unavailable!
            (fn [_] (swap! effects conj :unavailable) true))
      (set! agent/resume!
            (fn [request]
              (swap! effects conj [::resume request])
              (@rehost-started-resolve true)
              (js/Promise.
               (fn [resolve _]
                 (reset! finish-resume resolve)))))
      (set! agent-loop/install-ticker!
            (fn [] (swap! effects conj :ticker)))
      (set! client/start-heartbeat!
            (fn []
              (swap! effects conj :heartbeat)
              (@finish true)))
      (is (true? (client/shadow-build-notify! {:type :build-start})))
      (is (true? (client/shadow-build-notify! {:type :build-complete})))
      (-> rehost-started
          (.then
           (fn [_]
             (is (= [:close
                     [::ensure-acquire
                      {::client/initialize? true
                       ::client/configuration configuration}]
                     :publish
                     [::resume {:seon.agent/id "root"}]]
                    @effects)
                 "a running runtime recovers its lost session before rehosting")
             (@finish-resume
              {:seon.agent.runtime/resumed? true
               :seon.agent/id "root"})
             finished))
          (.then
           (fn [_]
             (is (= [:close
                     [::ensure-acquire
                      {::client/initialize? true
                       ::client/configuration configuration}]
                     :publish
                     [::resume {:seon.agent/id "root"}]
                     :ticker
                     :heartbeat]
                    @effects)
                 "reload has one ensure/acquire, publication, and rehost order")))
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
             (set! agent/resume! original-resume)
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
          original-resume agent/resume!
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
      (set! agent/resume!
            (fn [_]
              (swap! effects conj :rehost)
              (js/Promise.resolve {:seon.agent.runtime/resumed? true})))
      (set! agent-loop/install-ticker!
            (fn [] (swap! effects conj :ticker)))
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
             (set! agent/resume! original-resume)
             (set! agent-loop/install-ticker! original-install)
             (set! client/start-heartbeat! original-heartbeat)
             (done)))))))
