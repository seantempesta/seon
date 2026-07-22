(ns seon.config-test
  "Unit tests for the config-read layer (`seon.config`).

   Most tests are pure data: the resolvers take a manifest map + raw seed data
   and return curated data. One config-apply contract test drives the real
   client/runtime-state reconcile path against authority-shaped responses.
   Covers schema validity, the config-absent identity (the `{}` manifest =
   byte-identical to a no-config boot), route curation, the render-bounds
   section, and the env accessors.

   Run via bin/test-cljs, or interactively via MCP eval:
     (require 'seon.config-test :reload)
     (cljs.test/run-tests 'seon.config-test)"
  (:require
    [cljs.test :refer [async deftest is testing]]
    [clojure.string :as str]
    [malli.core :as m]
    [my.skills :as skills]
    [seon.agent :as agent]
    [seon.agent.ctx :as agent.ctx]
    [seon.agent.message]
    [seon.agent.web]
    [seon.client :as client]
    [seon.config :as config]
    [seon.config.resolve :as resolve]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.launch :as launch]
    [seon.runtime.state :as state]
    [seon.schema :as schema]
    [seon.test.async :as test.async]))

(def ^:private routes
  [{:seon.route/name :seon.route/root  :seon.route/pattern "/"}
   {:seon.route/name :seon.route/legacy-page :seon.route/pattern "/legacy"}])

(defn- selected-configuration
  []
  (config/resolve-config-singleton (or (config/load-manifest) {})))

(def ^:private fixed-hardware
  {:seon.hardware/cores 8
   :seon.hardware/system-memory-bytes (* 32 1024 1024 1024)
   :seon.hardware/fd-soft-limit 2048})

(deftest operational-envelope-enforces-every-operational-key
  (let [dispositions
        (:seon.launch.envelope/dispositions
         (resolve/resolve-envelope {} fixed-hardware 1))]
    (is (= #{}
           (into #{}
                 (keep (fn [[attribute disposition]]
                         (when (= :carried disposition) attribute)))
                 dispositions))
        "no operational attribute remains carried")
    (is (= (set resolve/operational-keys)
           (into #{}
                 (keep (fn [[attribute disposition]]
                         (when (= :enforced disposition) attribute)))
                 dispositions))
        "every operational attribute is enforced")))

(deftest maximum-frame-bytes-covers-proven-boot-pages
  (is (thrown-with-msg?
       js/Error #"proven boot floor"
       (resolve/resolve-operational-values
        {:seon.config/database
         {:seon.config.database.transport/maximum-frame-bytes 65535}}
        fixed-hardware)))
  (is (= 65536
         (:seon.config.database.transport/maximum-frame-bytes
          (resolve/resolve-operational-values
           {:seon.config/database
            {:seon.config.database.transport/maximum-frame-bytes 65536}}
           fixed-hardware)))))

(deftest every-operational-key-is-manifest-declarable
  (let [operational (resolve/resolve-operational-values {} fixed-hardware)
        manifest {:seon.config/database operational}]
    (is (= (set resolve/operational-keys) (set (keys operational))))
    (is (m/validate :seon.config/manifest manifest))
    (is (= operational
           (select-keys
            (resolve/resolve-config-singleton manifest {} fixed-hardware)
            resolve/operational-keys)))))

(deftest operational-footguns-reject-with-key-floor-and-reason
  (doseq [[attribute value expected-floor]
          [[:seon.config.database.writer/jvm-heap-mb 1 2]
           [:seon.config.database.read/max-result-weight 59999 60000]
           [:seon.config.database.transport/maximum-frame-bytes 65535 65536]
           [:seon.config.database.transport/maximum-connections 1 2]
           [:seon.config.database.executor/maximum-queued-request-bytes 65539 65540]
           [:seon.config.database.transport/maximum-input-bytes 65539 65540]
           [:seon.config.database.transport/maximum-output-bytes 65535 65536]
           [:seon.config.database.transport/maximum-session-output-bytes 65535 65536]]]
    (let [error
          (try
            (resolve/resolve-operational-values
             {:seon.config/database
              {:seon.config.database.transport/maximum-frame-bytes 65536
               attribute value}}
             fixed-hardware)
            nil
            (catch js/Error error error))]
      (is (some? error) (str attribute " rejects its footgun value"))
      (is (= value (get (ex-data error) attribute)))
      (is (= expected-floor (:seon.config/floor (ex-data error))))
      (is (string? (:seon.config/reason (ex-data error))))
      (is (re-find (re-pattern (str attribute))
                   (:seon.config/steering (ex-data error))))))
  (doseq [[smaller-key smaller larger-key larger]
          [[:seon.config.database.transport/maximum-session-response-slots 3
            :seon.config.database.transport/maximum-response-slots 2]
           [:seon.config.database.transport/maximum-session-output-bytes 65537
            :seon.config.database.transport/maximum-output-bytes 65536]]]
    (let [error
          (try
            (resolve/resolve-operational-values
             {:seon.config/database
              {:seon.config.database.transport/maximum-frame-bytes 65536
               smaller-key smaller
               larger-key larger}}
             fixed-hardware)
            nil
            (catch js/Error error error))]
      (is (= smaller (get (ex-data error) smaller-key)))
      (is (= larger (get (ex-data error) larger-key)))
      (is (string? (:seon.config/reason (ex-data error)))))))

(deftest liveness-relations-reject-zero-turn-configurations
  (let [deadline-error
        (try
          (resolve/resolve-config-singleton
           {:seon.config/run {:seon.config.run/deadline-ms 359999}
            :seon.config/model-variants
            {:planning {:seon.ai/agent-attempt-timeout-ms 360000}}}
           {}
           fixed-hardware)
          nil
          (catch js/Error error error))
        watchdog-error
        (try
          (resolve/resolve-config-singleton
           {:seon.config/watchdog {:seon.config.watchdog/stale-ms 900000}}
           {"SEON_TURN_TIMEOUT_MS" "900000"}
           fixed-hardware)
          nil
          (catch js/Error error error))]
    (is (= 360000 (:seon.config/floor (ex-data deadline-error))))
    (is (= 900001 (:seon.config/floor (ex-data watchdog-error))))
    (is (m/validate
         :seon.config/manifest
         {:seon.config/run {:seon.config.run/batch-turn-limit 1
                            :seon.config.run/stream-form-limit 1}
          :seon.config/schedule-breaker
          {:seon.config.breaker/crash-count 1
           :seon.config.breaker/window-ms 1}})
        "taste-sensitive liveness values retain their structural floor of one")))

(deftest shared-resolver-is-pure-and-delegated
  (let [manifest {:seon.config/repl-mode :batch}
        environment {"SEON_AI_PROVIDER" "deepseek"}
        first-value (resolve/resolve-config-singleton
                     manifest environment fixed-hardware)
        second-value (resolve/resolve-config-singleton
                      manifest environment fixed-hardware)]
    (is (= first-value second-value))
    (is (= first-value
           (with-redefs [config/process-environment (constantly environment)]
             (config/resolve-config-singleton manifest fixed-hardware))))
    (is (= 2048
           (:seon.config.database.writer/jvm-heap-mb first-value)))
    (is (= 8
           (:seon.config.database.executor/selected-processors first-value)))
    (is (= 112
           (:seon.config.database.transport/maximum-connections first-value)))))

(deftest selected-processors-is-manifest-selected-and-hardware-bounded
  (is (= 3
         (:seon.config.database.executor/selected-processors
          (resolve/resolve-config-singleton
           {:seon.config/database
            {:seon.config.database.executor/selected-processors 3}}
           {}
           fixed-hardware))))
  (is (= 8
         (:seon.config.database.executor/selected-processors
          (resolve/resolve-config-singleton
           {:seon.config/database
            {:seon.config.database.executor/selected-processors 64}}
           {}
           fixed-hardware))))
  (is (thrown-with-msg?
       js/Error #"positive integer"
       (resolve/resolve-operational-values
        {:seon.config/database
         {:seon.config.database.executor/selected-processors 0}}
        fixed-hardware))))

(deftest shipped-manifest-has-a-stable-resolved-golden-value
  (let [manifest (config/read-config-file "config/system.edn")
        configuration
        (resolve/resolve-config-singleton manifest {} fixed-hardware)]
    (is (= {:seon.config/repl-mode :stream
            :seon.config.database.writer/jvm-heap-mb 2048
            :seon.config.database.executor/selected-processors 8
            :seon.config.database.transport/maximum-frame-bytes (* 4 1024 1024)
            :seon.config.database.transport/maximum-connections 112}
           (select-keys
            configuration
            [:seon.config/repl-mode
             :seon.config.database.writer/jvm-heap-mb
             :seon.config.database.executor/selected-processors
             :seon.config.database.transport/maximum-frame-bytes
             :seon.config.database.transport/maximum-connections])))
    (is (= [{:seon.ai/id "config"
             :seon.ai/provider :deepseek
             :seon.ai/model "deepseek-v4-pro"
             :seon.ai/base-url "https://api.deepseek.com"
             :seon.ai/api-key-env "DEEPSEEK_API_KEY"}]
           (config/resolve-ai-config manifest)))))

(deftest cluster-default-ai-selection-is-closed-and-optional
  (let [selection {:seon.ai/provider :deepseek
                   :seon.ai/model "deepseek-v4-pro"
                   :seon.ai/base-url "https://api.deepseek.com"
                   :seon.ai/api-key-env "DEEPSEEK_API_KEY"
                   :seon.ai/thinking "false"}
        manifest {:seon.config/ai selection}]
    (is (m/validate :seon.config/manifest manifest))
    (is (= [(assoc selection :seon.ai/id "config")]
           (resolve/resolve-ai-config manifest)))
    (is (= (resolve/resolve-ai-config manifest)
           (config/resolve-ai-config manifest))
        "the pod delegates cluster-row resolution to the portable owner")
    (is (= [] (config/resolve-ai-config {}))
        "an absent declaration contributes no desired entity")
    (is (not (m/validate :seon.config/manifest
                         {:seon.config/ai
                          (assoc selection :seon.ai/api-key "secret")}))
        "a secret value is not part of the closed manifest surface")
    (is (not (m/validate :seon.config/manifest
                         {:seon.config/ai
                          (assoc selection :seon.ai/unknown true)}))
        "unknown cluster-selection keys fail manifest validation")))

(deftest acme-explicitly-preserves-its-typeahead-default
  (let [manifest (config/read-config-file "config/acme.edn")]
    (is (= [{:seon.ai/id "config" :seon.ai/provider :typeahead}]
           (config/resolve-ai-config manifest)))))

(deftest declared-ai-reclaims-repl-provenance-and-repeat-is-zero-operation
  (async done
    (let [manifest
          {:seon.config/ai
           {:seon.ai/provider :deepseek
            :seon.ai/model "deepseek-v4-pro"
            :seon.ai/base-url "https://api.deepseek.com"
            :seon.ai/api-key-env "DEEPSEEK_API_KEY"}}
          singleton (resolve/resolve-config-singleton
                     manifest {} fixed-hardware)
          desired-ai (first (resolve/resolve-ai-config manifest))
          repl-ai (assoc desired-ai
                         :seon.ai/provider :openai-compat
                         :seon.ai/model "agent-authored")
          database-before
          {:db-name "config-adoption-test"
           :t 10
           :as-of nil
           :since nil
           :history false
           :datahike/commit-id
           #uuid "00000000-0000-0000-0000-000000000010"}
          database-after
          (assoc database-before
                 :t 11
                 :datahike/commit-id
                 #uuid "00000000-0000-0000-0000-000000000011")
          installed
          (into {}
                (map (juxt :db/ident identity))
                (db/malli->datahike-schema
                 (into (vec (keys singleton)) (keys desired-ai))))
          stored-singleton
          (into {}
                (remove (fn [[attribute value]]
                          (and (= :db.cardinality/many
                                  (:db/cardinality (get installed attribute)))
                               (empty? value))))
                (first (db/encode-edn-slot-values [singleton])))
          phase (atom :foreign)
          writes (atom [])
          original-db db/db
          original-execute-many db/execute-many
          original-transact db/transact!
          original-routes config/resolve-routes
          original-skills skills/seed-skills-tx-data
          original-host-coordinates agent/reconcile-host-coordinates!
          original-migrate agent.ctx/migrate-plan-surface-default!
          success (fn [m] (protocol/success m))
          entity-rows
          (fn [identity-attr]
            (case identity-attr
              :seon.ai/id
              [[41 (assoc (if (= :foreign @phase) repl-ai desired-ai)
                          :db/id 41)]]

              :seon.config/id
              (if (= :managed @phase)
                [[42 (assoc stored-singleton :db/id 42)]]
                [])

              []))
          provenance-rows
          (fn [identity-attr]
            (case identity-attr
              :seon.ai/id [[41 100]]
              :seon.config/id (if (= :managed @phase) [[42 101]] [])
              []))
          process-rows
          (fn [identity-attr]
            (case identity-attr
              :seon.ai/id [[100 :seon.db.process/repl]]
              :seon.config/id
              (if (= :managed @phase)
                [[101 :seon.db.process/config]]
                [])
              []))
          query-result
          (fn [member]
            (let [query (::protocol/query-form member)
                  identity-attr (first (::protocol/arguments member))]
              (success
               {:datahike.query/result
                (cond
                  (= query (deref #'state/reconcile-state-query))
                  (entity-rows identity-attr)

                  (= query (deref #'state/reconcile-provenance-query))
                  (provenance-rows identity-attr)

                  (= query
                     (deref #'state/reconcile-transaction-process-query))
                  (process-rows identity-attr)

                  (= query (deref #'state/reconcile-lookup-ref-query))
                  (entity-rows identity-attr)

                  :else
                  (throw (js/Error. "unexpected reconcile query")))})))
          payload
          {:seon.config/manifest manifest
           :seon.config/singleton singleton
           ::launch/operational-envelope
           (resolve/resolve-envelope manifest fixed-hardware 7)
           ::launch/config-apply-generation 7}
          restore!
          (fn []
            (set! db/db original-db)
            (set! db/execute-many original-execute-many)
            (set! db/transact! original-transact)
            (set! config/resolve-routes original-routes)
            (set! skills/seed-skills-tx-data original-skills)
            (set! agent/reconcile-host-coordinates! original-host-coordinates)
            (set! agent.ctx/migrate-plan-surface-default! original-migrate))]
      (set! db/db
            (fn
              ([] (js/Promise.resolve
                   (if (= :foreign @phase) database-before database-after)))
              ([_] (js/Promise.resolve
                    (if (= :foreign @phase) database-before database-after)))))
      (set! db/execute-many
            (fn [request]
              (js/Promise.resolve
               {::db/results
                (mapv (fn [member]
                        (if (= protocol/schema-operation
                               (::protocol/operation member))
                          (success {::protocol/schema installed})
                          (query-result member)))
                      (::db/members request))})))
      (set! db/transact!
            (fn [& [request]]
              (swap! writes conj request)
              (reset! phase :managed)
              (js/Promise.resolve
               {:db-before database-before
                :db-after database-after
                :tx-data (::db/tx-data request)
                :tempids {}})))
      (set! config/resolve-routes (fn [_ _] []))
      (set! skills/seed-skills-tx-data (fn [_] []))
      (set! agent/reconcile-host-coordinates!
            (fn [_]
              (js/Promise.resolve
               {::agent/host-coordinate-ok? true
                ::agent/host-coordinate-changed? false
                ::agent/host-coordinate-operations 0})))
      (set! agent.ctx/migrate-plan-surface-default!
            (fn []
              (js/Promise.resolve
               {::agent.ctx/ok? true
                ::agent.ctx/changed? false
                ::agent.ctx/operations 0})))
      (-> (client/apply-config! payload)
          (.then
           (fn [first-apply]
             (is (true? (::state/ok? first-apply)))
             (is (true? (::state/changed? first-apply)))
             (is (= 1 (count @writes)))
             (let [tx-data (::db/tx-data (first @writes))]
               (is (some #{[:db.fn/retractAttribute
                            [:seon.ai/id "config"]
                            :seon.ai/provider]}
                         tx-data)
                   "the declaration retracts the agent-authored provider")
               (is (some (fn [operation]
                           (and (map? operation)
                                (= "config" (:seon.ai/id operation))
                                (= :deepseek (:seon.ai/provider operation))
                                (= "deepseek-v4-pro"
                                   (:seon.ai/model operation))))
                         tx-data)
                   "the fixed config identity receives the declaration"))
             (client/apply-config! payload)))
          (.then
           (fn [second-apply]
             (is (= {::state/ok? true
                     ::state/changed? false
                     ::state/operations 0
                     ::state/attempts 1}
                    second-apply))
             (is (= 1 (count @writes))
                 "a converged repeat submits no transaction")))
          (.finally restore!)
          (test.async/settle! done)))))

(deftest manifest-schema-validity
  (testing "a representative manifest validates against :seon.config/manifest"
    (is (m/validate :seon.config/manifest
                    {:seon.config/skills-dir    "seon-skills"
                     :seon.config/routes        [{:seon.config/removes [:seon.route/agent-call]}]
                     :seon.config/run           {:seon.config.run/batch-turn-limit 100
                                                 :seon.config.run/stream-form-limit 300
                                                 :seon.config.run/deadline-ms 1800000}
                     :seon.config/model-transport
                     {:seon.config.model-transport/response-identity-cap 53
                      :seon.config.model-transport/endpoint-cap 257}
                     :seon.config/model-variants
                     {:planning
                      {:seon.ai/agent-provider :openai-compat
                       :seon.ai/agent-model "kimi-k3"
                       :seon.ai/agent-base-url "https://api.moonshot.ai/v1"
                       :seon.ai/agent-api-key-env "MOONSHOT_API_KEY"}}
                     :seon.config/agent-context
                     {:seon.agent/ctx [{:seon.agent.ctx/name :transcript
                                        :seon.agent.ctx/priority 100}]}})))
  (testing "the empty manifest (config absent) is valid — every key optional"
    (is (m/validate :seon.config/manifest {})))
  (testing "the render-bounds section validates"
    (is (m/validate :seon.config/manifest
                    {:seon.config/render {:seon.config.render/value-width 72
                                          :seon.config.render/database-edn-cap 16384
                                          :seon.config.render/value-max-path-segments 32
                                          :seon.config.render/value-max-path-bytes 4096
                                          :seon.config.render/value-max-realized-items 1024}}))
    (doseq [attribute [:seon.config.render/value-max-path-segments
                       :seon.config.render/value-max-path-bytes
                       :seon.config.render/value-max-realized-items]
            invalid [0 -1 1.5]]
      (is (not (m/validate :seon.config/manifest
                           {:seon.config/render {attribute invalid}}))
          (str attribute " rejects " invalid)))
    (is (not (m/validate :seon.config/manifest
                         {:seon.config/render
                          {:seon.config.render/value-max-path-segments 32
                           :seon.config.render/unknown-drill-cap 1}}))
        "the render policy remains closed"))
  (testing "a minimal-cluster-shaped manifest validates (system-text + repl-mode + explicit ctx)"
    (is (m/validate :seon.config/manifest
                    {:seon.config/system-text "; ── system ──\n; the minimal prompt"
                     :seon.config/repl-mode   :batch
                     :seon.config/agent-context
                     {:seon.agent/ctx [{:seon.agent.ctx/name :transcript
                                        :seon.agent.ctx/priority 100}]}
                     :seon.config/root-context {}}))))

(deftest config-function-schemas-are-pure-data
  (doseq [v [#'config/namespaces-policy #'config/database-edn-cap]]
    (let [form (:malli/schema (meta v))]
      (is (some? (m/schema form)))
      (is (not-any? #(and (seq? %) (= 'quote (first %)))
                    (tree-seq coll? seq form))
          "runtime indexing must not receive an unevaluated quoted predicate"))))

(deftest every-config-singleton-attribute-has-a-datahike-shape
  (let [form  (schema/schema-definition :seon.config/singleton)
        attrs (->> (rest form)
                   (remove map?)
                   (mapv first))
        facets (db/malli->datahike-schema attrs)]
    (is (= (set attrs) (into #{} (map :db/ident) facets))
        "every config fact written at cold boot bridges to one database attr")
    (is (= {:db/ident :seon.agent.web/allowed-domains
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/many}
           (first (db/malli->datahike-schema
                    [:seon.agent.web/allowed-domains])))
        "the existing web allowlist remains cardinality-many strings"))
  (is (= {:db/ident :seon.config/always
          :db/valueType :db.type/symbol
          :db/cardinality :db.cardinality/many}
         (first (db/malli->datahike-schema [:seon.config/always])))
      "the always-source policy stores native cardinality-many symbols")
  (is (= {:db/ident :seon.config/skills-dir
          :db/valueType :db.type/string
          :db/cardinality :db.cardinality/one}
         (first (db/malli->datahike-schema [:seon.config/skills-dir])))
      "the optional skill corpus input stores one ordinary string")
  (is (= {:db/ident :seon.config/model-variants
          :db/valueType :db.type/ref
          :db/cardinality :db.cardinality/many
          :db/isComponent true}
         (first (db/malli->datahike-schema [:seon.config/model-variants])))
      "named models are component children of the config singleton")
  (is (= {:db/ident :seon.config/model-variant
          :db/valueType :db.type/keyword
          :db/cardinality :db.cardinality/one
          :db/unique :db.unique/identity}
         (first (db/malli->datahike-schema [:seon.config/model-variant])))
      "each model variant has one native keyword identity")
  (doseq [attribute (vals resolve/repair-class-attributes)]
    (is (= {:db/ident attribute
            :db/valueType :db.type/boolean
            :db/cardinality :db.cardinality/one}
           (first (db/malli->datahike-schema [attribute])))
        "each repair switch is one native boolean fact")))

(deftest config-absent-is-identity
  (testing "the {} manifest leaves the route seed untouched"
    (is (= routes (config/resolve-routes routes {})))))

(deftest execution-policy-is-one-defaulted-config-fact-set
  (is (m/validate :seon.config/manifest
                  {:seon.config/execution
                   {:seon.config.execution/host-tier? true
                    :seon.config.execution/host-respawn-backoff-ms 1000}}))
  (let [defaults (config/resolve-config-singleton {})
        selected
        (config/resolve-config-singleton
         {:seon.config/execution
          {:seon.config.execution/host-tier? true
           :seon.config.execution/host-respawn-backoff-ms 2500}})]
    (is (false? (:seon.config.execution/host-tier? defaults)))
    (is (= 1000
           (resolve/execution-host-respawn-backoff-ms defaults)))
    (is (true? (:seon.config.execution/host-tier? selected)))
    (is (= 2500
           (resolve/execution-host-respawn-backoff-ms selected))))
  (let [error
        (try
          (config/resolve-config-singleton
           {:seon.config/execution
            {:seon.config.execution/host-respawn-backoff-ms 999}})
          nil
          (catch js/Error exception exception))]
    (is (= 999 (get (ex-data error)
                    :seon.config.execution/host-respawn-backoff-ms)))
    (is (= 1000 (:seon.config/floor (ex-data error))))
    (is (string? (:seon.config/reason (ex-data error))))
    (is (re-find #"host-respawn-backoff-ms"
                 (:seon.config/steering (ex-data error)))))
  (is (not
       (m/validate :seon.config/manifest
                   {:seon.config/execution
                    {:seon.config.execution/unknown? true}}))))

(deftest package-accessors-use-the-open-wp-k-posture
  (let [configuration (config/resolve-config-singleton {})]
    (is (= :open (config/packages-policy configuration)))
    (is (= #{} (config/packages-allowlist configuration)))
    (is (= :all (config/packages-trusted-lifecycle-scripts configuration)))
    (is (= 120000 (config/packages-install-deadline-ms configuration)))
    (is (= 256 (config/packages-max-rows configuration)))
    (is (= 3 (config/packages-host-sessions configuration)))
    (is (= 120000 (config/packages-host-call-deadline-ms configuration)))
    (is (= 30000 (config/packages-host-ready-timeout-ms configuration)))
    (is (= 1000 (config/packages-host-respawn-backoff-ms configuration)))
    (is (= 5000 (config/packages-host-swap-queue-deadline-ms configuration)))
    (is (= 512 (config/packages-host-jvm-heap-mb configuration)))
    (is (= 64 (config/handle-per-channel-cap configuration)))
    (is (= 40 (config/handle-summary-token-cap configuration)))
    (is (= :enabled (config/packages-exploration-ops configuration)))))

(deftest package-accessors-read-explicit-future-facts
  (let [configuration
        (merge (config/resolve-config-singleton {})
               {:seon.config.packages/policy :closed
                :seon.config.packages/allowlist #{"cheerio" 'org.clojure/data.csv}
                :seon.config.packages/trusted-lifecycle-scripts #{"sharp"}
                :seon.config.packages/install-deadline-ms 1
                :seon.config.packages/max-rows 2
                :seon.config.packages.host/sessions 4
                :seon.config.packages.host/call-deadline-ms 5
                :seon.config.packages.host/ready-timeout-ms 6
                :seon.config.packages.host/respawn-backoff-ms 7
                :seon.config.packages.host/swap-queue-deadline-ms 8
                :seon.config.packages.host/jvm-heap-mb 9
                :seon.config.handle/per-channel-cap 10
                :seon.config.handle/summary-token-cap 11
                :seon.config.packages/exploration-ops :disabled})]
    (is (= :closed (config/packages-policy configuration)))
    (is (= #{"cheerio" 'org.clojure/data.csv}
           (config/packages-allowlist configuration)))
    (is (= #{"sharp"}
           (config/packages-trusted-lifecycle-scripts configuration)))
    (is (= 1 (config/packages-install-deadline-ms configuration)))
    (is (= 2 (config/packages-max-rows configuration)))
    (is (= 4 (config/packages-host-sessions configuration)))
    (is (= 5 (config/packages-host-call-deadline-ms configuration)))
    (is (= 6 (config/packages-host-ready-timeout-ms configuration)))
    (is (= 7 (config/packages-host-respawn-backoff-ms configuration)))
    (is (= 8 (config/packages-host-swap-queue-deadline-ms configuration)))
    (is (= 9 (config/packages-host-jvm-heap-mb configuration)))
    (is (= 10 (config/handle-per-channel-cap configuration)))
    (is (= 11 (config/handle-summary-token-cap configuration)))
    (is (= :disabled (config/packages-exploration-ops configuration)))))

(deftest render-explicit-char-knobs-validate-and-default
  ;; transcript-render redesign: the new whitespace/tabs/trailing-ws/layout/
  ;; line-number knobs validate, and an ABSENT section reproduces today's
  ;; bytes — every accessor defaults off.
  (testing "the knobs validate in the manifest"
    (is (m/validate :seon.config/manifest
                    {:seon.config/render
                     {:seon.config.render/whitespace     :visible
                      :seon.config.render/tabs           :arrow
                      :seon.config.render/trailing-ws    :dot
                      :seon.config.render/content-layout :single-line
                      :seon.config.render/line-numbers   true}})))
  (testing "an absent section defaults to today's byte-identical render"
    ;; The redefed empty manifest drives the pre-attach defaults.
    (let [configuration (config/resolve-config-singleton {})]
      (is (= :raw        (config/render-whitespace configuration)))
      (is (= :literal    (config/render-tabs configuration)))
      (is (= :off        (config/render-trailing-ws configuration)))
      (is (= :structured (config/render-content-layout configuration)))
      (is (false?        (config/render-line-numbers? configuration))))))

(deftest effective-value-drill-limits-only-narrow-host-policy
  (let [configuration
        (merge (config/resolve-config-singleton {})
               {:seon.config.render/value-max-path-segments 20
                :seon.config.render/value-max-path-bytes 2000
                :seon.config.render/value-max-realized-items 200
                :seon.config.render/value-max-depth 12
                :seon.config.render/value-max-string 160
                :seon.config.render/value-shape-sample 16
                :seon.config.render/value-max-items 10})
        normalize (fn [operation-limits]
                    (config/effective-value-drill-limits
                      (cond-> {:seon.config/configuration configuration}
                        operation-limits
                        (assoc :seon.render.value/operation-limits
                               operation-limits))))
        host {:seon.config.render/value-max-path-segments 20
              :seon.config.render/value-max-path-bytes 2000
              :seon.config.render/value-max-realized-items 200
              :seon.config.render/value-max-depth 12
              :seon.config.render/value-max-string 160
              :seon.config.render/value-shape-sample 16
              :seon.render.value/page-size 10}
        narrowed {:seon.config.render/value-max-path-segments 8
                  :seon.config.render/value-max-path-bytes 500
                  :seon.config.render/value-max-realized-items 100
                  :seon.config.render/value-max-depth 6
                  :seon.config.render/value-max-string 80
                  :seon.config.render/value-shape-sample 8
                  :seon.render.value/page-size 4}]
    (is (= host (normalize nil)) "absence resolves exactly to host policy")
    (is (= narrowed (normalize narrowed)) "every smaller field narrows")
    (is (= host
           (normalize {:seon.config.render/value-max-path-segments 200
                       :seon.config.render/value-max-path-bytes 20000
                       :seon.config.render/value-max-realized-items 2000
                       :seon.config.render/value-max-depth 120
                       :seon.config.render/value-max-string 1600
                       :seon.config.render/value-shape-sample 160
                       :seon.render.value/page-size 100})))
    (is (= {:seon.config.render/value-max-path-segments 8
            :seon.config.render/value-max-path-bytes 2000
            :seon.config.render/value-max-realized-items 200
            :seon.config.render/value-max-depth 12
            :seon.config.render/value-max-string 160
            :seon.config.render/value-shape-sample 16
            :seon.render.value/page-size 10}
           (normalize {:seon.config.render/value-max-path-segments 8}))
        "fields normalize independently")
    (is (= narrowed (normalize (normalize narrowed)))
        "normalization is idempotent")
    (is (= narrowed
           (config/effective-value-drill-limits
             {:seon.config/configuration configuration
              :seon.render.value/operation-limits narrowed}))
        "same-policy parent and child bytes are identical")
    (let [narrow-child
          (assoc configuration
                 :seon.config.render/value-max-realized-items 50)
          child-effective
          (config/effective-value-drill-limits
            {:seon.config/configuration narrow-child
             :seon.render.value/operation-limits narrowed})]
      (is (= 50 (:seon.config.render/value-max-realized-items child-effective)))
      (is (not= narrowed child-effective)
          "a narrower child is visible for later frame-consistency refusal"))))

(defn- ctx-block-names [id override]
  (into #{} (map :seon.agent.ctx/name)
        (:seon.agent/ctx
         (config/resolve-agent-context id override (selected-configuration)))))

(deftest absent-config-has-no-hidden-context-default
  (with-redefs [config/load-manifest (fn [] {})]
    (is (empty? (ctx-block-names "worker-x" nil)))
    (is (empty? (ctx-block-names "root" nil)))))

;;; Explicit `:seon.agent/ctx` = the COMPLETE tree (agent-ctx Phase 3) — the
;;; documented replaces-wholesale contract extends to the identity file-blocks:
;;; an on-disk AGENTS.md/SOUL.md must not smuggle a block into a cluster that
;;; enumerated its tree (config/minimal.edn depends on this).

(deftest explicit-ctx-declares-the-complete-tree
  (let [transcript-only [{:seon.agent.ctx/name :transcript
                          :seon.agent.ctx/priority 100
                          :seon.render/ai 'seon.agent.ctx.transcript/transcript-block}]]
    (testing "manifest agent-context with explicit ctx → exactly that tree, no identity blocks"
      (with-redefs [config/load-manifest
                    (fn [] {:seon.config/agent-context
                            {:seon.agent/ctx transcript-only}})]
        (is (= #{:transcript} (ctx-block-names "worker-x" nil)))
        (is (= #{:transcript} (ctx-block-names "root" nil))
            "root with an absent root-context gets the same explicit tree")))
    (testing "a per-mint override with explicit ctx → exactly that tree"
      (with-redefs [config/load-manifest (fn [] {})]
        (is (= #{:transcript} (ctx-block-names "worker-x"
                                               {:seon.agent/ctx transcript-only})))))
    (testing "no explicit ctx → no hidden code or file-backed block tree"
      (with-redefs [config/load-manifest (fn [] {})]
        (is (empty? (ctx-block-names "worker-x" nil)))))))

;;; Persisted agent-level dials — `:seon.agent.lifecycle/wake?` / `:seon.eval/home-requires`
;;; carry NO schema default (the CONSUMER owns the default), so a no-config agent
;;; gets NO datom (byte-parity) and the manifest sets the key only to OVERRIDE.

(deftest agent-level-dials-are-override-only
  (with-redefs [config/load-manifest (fn [] {})]
    (testing "a default agent-context carries NEITHER wake? nor home-requires (consumer owns the default)"
      (let [ctx (config/resolve-agent-context
                 "worker-x" nil (selected-configuration))]
        (is (not (contains? ctx :seon.agent.lifecycle/wake?))
            "no wake? datom on a default agent → seed transacts nothing → parity")
        (is (not (contains? ctx :seon.eval/home-requires))
            "no home-requires datom on a default agent → home-requires-for uses the const")))
    (testing "a per-mint override carries the key into the atomic birth map"
      (let [ctx (config/resolve-agent-context
                 "worker-x"
                 {:seon.agent.lifecycle/wake? false
                  :seon.eval/home-requires '[[seon.db :as db]]}
                 (selected-configuration))]
        (is (false? (:seon.agent.lifecycle/wake? ctx)))
        (is (= '[[seon.db :as db]] (:seon.eval/home-requires ctx)))))))

(deftest per-agent-model-config-resolves-through-the-birth-context
  (let [base {:seon.ai/agent-provider :openai-compat
              :seon.ai/agent-model "kimi-k3"
              :seon.ai/agent-max-tokens 16384
              :seon.ai/agent-completion-limit-field :max-completion-tokens
              :seon.ai/agent-thinking "false"
              :seon.ai/agent-timeout-ms 180000
              :seon.ai/agent-attempt-timeout-ms 240000
              :seon.ai/agent-base-url "https://api.moonshot.ai/v1"
              :seon.ai/agent-api-key-env "MOONSHOT_API_KEY"
              :seon.ai/agent-extra-body-edn "{:planner true}"
              :seon.ai/agent-max-retries 1}
        configuration {:seon.config/id config/cluster-config-id
                       :seon.config/agent-context base
                       :seon.config/root-context
                       {:seon.ai/agent-model "muse-spark-1.1"
                        :seon.ai/agent-base-url "https://api.meta.ai/v1"
                        :seon.ai/agent-api-key-env "META_API_KEY"}}
        ordinary (config/resolve-agent-context "worker-x" nil configuration)
        root (config/resolve-agent-context "root" nil configuration)]
    (is (m/validate :seon.config/manifest
                    (dissoc configuration :seon.config/id)))
    (is (= base (dissoc ordinary :seon.agent/ctx))
        "ordinary births retain every logical agent model value")
    (is (= "kimi-k3" (:seon.ai/agent-model ordinary)))
    (is (= "https://api.moonshot.ai/v1"
           (:seon.ai/agent-base-url ordinary)))
    (is (= "muse-spark-1.1" (:seon.ai/agent-model root)))
    (is (= "https://api.meta.ai/v1" (:seon.ai/agent-base-url root)))
    (is (= "META_API_KEY" (:seon.ai/agent-api-key-env root)))
    (is (= 180000 (:seon.ai/agent-timeout-ms root))
        "a sparse root override inherits the remaining model fields")
    (is (= "kimi-k3"
           (:seon.ai/agent-model
            (config/resolve-agent-context
             "worker-x" {:seon.ai/agent-model "kimi-k3"} configuration)))
        "per-mint model attributes use the same resolver")))

(deftest named-model-variants-are-sparse-closed-launch-overrides
  (let [planning
        {:seon.ai/agent-provider :openai-compat
         :seon.config/repl-mode :batch
         :seon.ai/agent-model "kimi-k3"
         :seon.ai/agent-max-tokens 16384
         :seon.ai/agent-completion-limit-field :max-completion-tokens
         :seon.ai/agent-timeout-ms 180000
         :seon.ai/agent-attempt-timeout-ms 240000
         :seon.ai/agent-base-url "https://api.moonshot.ai/v1"
         :seon.ai/agent-api-key-env "MOONSHOT_API_KEY"}
        manifest {:seon.config/agent-context
                  {:seon.ai/agent-thinking "false"
                   :seon.ai/agent-max-retries 2}
                  :seon.config/model-variants {:planning planning}}
        configuration (config/resolve-config-singleton manifest)
        selected (get (config/model-variants configuration) :planning)
        resolved (config/resolve-agent-context "planner" selected configuration)]
    (is (= [{:seon.config/model-variant :planning
             :seon.ai/agent-provider :openai-compat
             :seon.config/repl-mode :batch
             :seon.ai/agent-model "kimi-k3"
             :seon.ai/agent-max-tokens 16384
             :seon.ai/agent-completion-limit-field :max-completion-tokens
             :seon.ai/agent-timeout-ms 180000
             :seon.ai/agent-attempt-timeout-ms 240000
             :seon.ai/agent-base-url "https://api.moonshot.ai/v1"
             :seon.ai/agent-api-key-env "MOONSHOT_API_KEY"}]
           (:seon.config/model-variants configuration))
        "resolution emits identified component children")
    (is (= {:planning planning} (config/model-variants configuration)))
    (is (= {} (config/model-variants
               (config/resolve-config-singleton {}))))
    (is (= "kimi-k3" (:seon.ai/agent-model resolved)))
    (is (= :max-completion-tokens
           (:seon.ai/agent-completion-limit-field resolved)))
    (is (= 240000 (:seon.ai/agent-attempt-timeout-ms resolved)))
    (is (= :batch (:seon.config/repl-mode resolved))
        "a named planning variant selects multi-namespace batch grammar")
    (is (= "false" (:seon.ai/agent-thinking resolved))
        "a sparse variant inherits ordinary agent-context values")
    (is (= 2 (:seon.ai/agent-max-retries resolved)))
    (is (not (m/validate
              :seon.config/manifest
              {:seon.config/model-variants
               {:planning (assoc planning :unrelated/value true)}}))
        "variant maps reject attributes outside the existing agent model surface")))

;;; Block-override MERGES by name — a manifest overriding a block need only name
;;; the sub-keys it changes; the default block's other attrs survive (the
;;; third-party-first contract). Proven via the root-context `:canvas` block,
;;; which by default carries `:seon.agent.ctx/priority` + `:seon.render/ai` +
;;; `:seon.render.canvas/content`.

(deftest block-override-merges-preserving-sub-keys
  (testing "root-context overriding ONE :canvas sub-key keeps the default block's other attrs"
    (with-redefs [config/load-manifest
                  (fn [] {:seon.config/agent-context
                          {:seon.agent/ctx
                           [{:seon.agent.ctx/name :canvas
                             :seon.agent.ctx/priority 35
                             :seon.render/ai 'probe/canvas}]}
                          :seon.config/root-context
                          {:seon.agent/ctx
                           [{:seon.agent.ctx/name :canvas
                             :seon.render.canvas/content [:div "ACME-CUSTOM"]}]}})]
      (let [blocks (:seon.agent/ctx
                    (config/resolve-agent-context
                     "root" nil (selected-configuration)))
            lt     (first (filter #(= :canvas (:seon.agent.ctx/name %)) blocks))]
        (is (= [:div "ACME-CUSTOM"] (:seon.render.canvas/content lt))
            "the overridden sub-key wins")
        (is (contains? lt :seon.agent.ctx/priority)
            "the default block's priority survives a sparse override")
        (is (contains? lt :seon.render/ai)
            "the default block's render fn survives a sparse override"))))
  (testing "a root-context block whose name is NOT in the base is appended as new"
    (with-redefs [config/load-manifest
                  (fn [] {:seon.config/agent-context
                          {:seon.agent/ctx
                           [{:seon.agent.ctx/name :canvas
                             :seon.agent.ctx/priority 35
                             :seon.render/ai 'probe/canvas}]}
                          :seon.config/root-context
                          {:seon.agent/ctx
                           [{:seon.agent.ctx/name :acme-extra
                             :seon.agent.ctx/priority 99
                             :seon.render/ai 'my.ui/extra}]}})]
      (let [names (ctx-block-names "root" nil)]
        (is (contains? names :acme-extra) "the new block is seeded")
        (is (contains? names :canvas) "default blocks remain")))))

(deftest root-home-requires-extend-the-complete-base-toolbelt
  (let [manifest
        {:seon.config/agent-context
         {:seon.agent/ctx []
          :seon.eval/home-requires
          '[[seon.db :as db]
            [seon.agent.message :as message]
            [acme.brand :as brand]]}
         :seon.config/root-context
         {:seon.eval/home-requires
          '[[seon.agent :as agent]
            [seon.db :as database]]}}]
    (let [configuration (config/resolve-config-singleton manifest)]
      (is (= '[[seon.db :as db]
               [seon.agent.message :as message]
               [acme.brand :as brand]]
             (:seon.eval/home-requires
               (config/resolve-agent-context
                "worker-x" nil configuration)))
          "an ordinary agent keeps the exact configured toolbelt")
      (is (= '[[seon.db :as database]
               [seon.agent.message :as message]
               [acme.brand :as brand]
               [seon.agent :as agent]]
             (:seon.eval/home-requires
               (config/resolve-agent-context "root" nil configuration)))
          "root inherits downstream capabilities, refines by ns, and appends"))))

;;; The `#merge` COMPOSITION trap (config-merge, 2026-07-11) — a per-cluster
;;; manifest composes as `#merge [#include "base" {overrides}]`. Aero's shipped
;;; `#merge` is a SHALLOW map merge, so a sparse override that sets only
;;; `:seon.eval/home-requires` USED to silently DROP the base's `:seon.agent/ctx`
;;; block tree (the schema `:default` then quietly filled the LEGACY tree — acme
;;; ran the wrong context for a day, the 1bd1d21d cutover regression). The
;;; manifest-aware `#merge` override in `seon.config` (loaded by this ns's
;;; require) applies `resolve-agent-context`'s replaces-wholesale rule to the
;;; `:seon.config/agent-context` key: a sparse override INHERITS `:seon.agent/ctx`,
;;; an explicit one REPLACES it wholesale. Pinned hermetically via temp edn files
;;; read through the same aero seam `load-manifest` uses (temp dir is gitignored).

(defn- write-tmp!
  "Write `content` to `tmp/config-merge-test/rel`, return the path."
  [rel content]
  (let [fs   (js/require "fs")
        path (js/require "path")
        dir  "tmp/config-merge-test"]
    (.mkdirSync fs dir #js {:recursive true})
    (let [p (.join path dir rel)]
      (.writeFileSync fs p content)
      p)))

(defn- manifest-via-config
  "Drive `config/load-manifest` at `path` through a `SEON_CONFIG` swap — the real
   read+validate seam (so the `#merge` reader override is exercised), env restored."
  [path]
  (let [env (.. js/globalThis -process -env) old (aget env "SEON_CONFIG")]
    (try (aset env "SEON_CONFIG" path) (config/load-manifest)
         (finally (if (nil? old) (js-delete env "SEON_CONFIG") (aset env "SEON_CONFIG" old))))))

(deftest explicit-inherit-is-a-guided-agent-override-error
  (doseq [[label manifest]
          [["agent-context"
            {:seon.config/agent-context
             {:seon.ai/agent-max-tokens :inherit}}]
           ["root-context"
            {:seon.config/root-context
             {:seon.ai/agent-max-tokens :inherit}}]
           ["model-variant"
            {:seon.config/model-variants
             {:planning {:seon.ai/agent-max-tokens :inherit}}}]]]
    (is (false? (m/validate :seon.config/manifest manifest))
        (str label " rejects the removed sentinel"))
    (let [path (write-tmp! (str "invalid-inherit-" label ".edn")
                           (pr-str manifest))
          error (try
                  (manifest-via-config path)
                  nil
                  (catch js/Error exception exception))]
      (is (some? error) (str label " fails at the manifest declaration door"))
      (is (= :user-input (:seon.error/kind (ex-data error))))
      (is (re-find #"absence means inherit" (ex-message error))
          "the steering error names the omission rule"))))

(deftest merge-agent-context-inherits-or-replaces-block-tree
  (let [base-blocks [{:seon.agent.ctx/name :namespaces :seon.agent.ctx/priority 20}
                     {:seon.agent.ctx/name :transcript :seon.agent.ctx/priority 100}]]
    (write-tmp! "base.edn"
                (pr-str {:seon.config/agent-context
                         {:seon.agent/ctx           base-blocks
                          :seon.eval/home-requires  '[[a :as a]]}}))
    (testing "a SPARSE override (only home-requires) INHERITS the base :seon.agent/ctx (the trap)"
      (let [p  (write-tmp! "sparse.edn"
                           (str "#merge\n[#include \"base.edn\"\n"
                                " {:seon.config/agent-context"
                                "  {:seon.eval/home-requires [[b :as b]]}}]"))
            ac (:seon.config/agent-context (manifest-via-config p))]
        (is (= (mapv :seon.agent.ctx/name base-blocks)
               (mapv :seon.agent.ctx/name (:seon.agent/ctx ac)))
            "the base :seon.agent/ctx block tree survives a sparse override")
        (is (= '[[a :as a] [b :as b]] (:seon.eval/home-requires ac))
            "the sparse manifest adds capabilities without copying the base")))
    (testing "an override that DECLARES :seon.agent/ctx replaces the tree WHOLESALE"
      (let [p  (write-tmp! "explicit.edn"
                           (str "#merge\n[#include \"base.edn\"\n"
                                " {:seon.config/agent-context"
                                "  {:seon.agent/ctx [{:seon.agent.ctx/name :plan"
                                "                     :seon.agent.ctx/priority 45}]}}]"))
            ac (:seon.config/agent-context (manifest-via-config p))]
        (is (= [:plan] (mapv :seon.agent.ctx/name (:seon.agent/ctx ac)))
            "the explicit tree wins wholesale (base blocks dropped)")
        (is (not (contains? ac :seon.eval/home-requires))
            "wholesale replace drops the base's other keys (consumer-default fallback)")))))

(defn- with-env
  "Set process.env[k]=v, run f, restore — so the env-reading accessors/config
   get a known value without touching the ambient pod env."
  [k v f]
  (let [env (.. js/globalThis -process -env) old (aget env k)]
    (try (if (nil? v) (js-delete env k) (aset env k v)) (f)
         (finally (if (nil? old) (js-delete env k) (aset env k old))))))

(deftest root-context-resolves-one-ordered-block-tree
  (with-env
    "SEON_CONFIG" "config/system.edn"
    (fn []
      (let [configuration (selected-configuration)
              ordinary (config/resolve-agent-context
                        "worker-x" nil configuration)
              root (config/resolve-agent-context "root" nil configuration)
              order (fn [context]
                      (->> (:seon.agent/ctx context)
                           (sort-by (juxt :seon.agent.ctx/priority
                                          (comp str :seon.agent.ctx/name)))
                           (mapv (juxt :seon.agent.ctx/name
                                       :seon.agent.ctx/priority))))]
          (is (= [[:namespaces 20]
                  [:canvas 35]
                  [:warnings 40]
                  [:plan 45]
                  [:transcript 100]]
                 (order ordinary))
              "ordinary agents keep the shared context tree")
          (is (= 'my.plan/plan-surface
                 (->> (:seon.agent/ctx ordinary)
                      (filter #(= :plan (:seon.agent.ctx/name %)))
                      first
                      :seon.render/html))
              "new agents seed the current plan surface directly")
          (is (= [[:root-role 15]
                  [:namespaces 20]
                  [:warnings 40]
                  [:core-faults 41]
                  [:instrumentation-gaps 42]
                  [:orphaned-agents 43]
                  [:plan 45]
                  [:canvas 90]
                  [:transcript 100]
                  [:free-dynamic-tail 1000]]
                 (order root))
              "root ends with one capped free dynamic tail")))))

(deftest acme-manifest-inherits-context-and-adds-only-product-tools
  (with-env
    "SEON_CONFIG" "config/acme.edn"
    (fn []
      (let [configuration (selected-configuration)
              ordinary (config/resolve-agent-context
                        "acme-worker" nil configuration)
              root     (config/resolve-agent-context "root" nil configuration)
              targets  (fn [context]
                         (into #{} (map first)
                               (:seon.eval/home-requires context)))
              blocks   (into #{} (map :seon.agent.ctx/name)
                             (:seon.agent/ctx ordinary))]
          (is (= #{:namespaces :canvas :warnings :plan :transcript} blocks)
              "experimental function-menu/typeahead blocks stay off")
          (is (every? (targets ordinary) '[acme.brand acme.widget my.ns my.skills]))
          (is (not-any? (targets ordinary) '[acme.helpers acme.notes]))
          (is (every? (targets root)
                      '[acme.brand acme.widget my.ns my.skills
                        seon.agent seon.agent.shell seon.agent.web])
              "root inherits the complete ordinary/downstream capability set")))))

(deftest route-removes
  (testing "a route spec drops the named seeded routes"
    (let [m {:seon.config/routes [{:seon.config/removes [:seon.route/legacy-page]}]}]
      (is (= [:seon.route/root]
             (mapv :seon.route/name (config/resolve-routes routes m)))))))

;;; RENDER BOUNDS — the global display caps live in the manifest's
;;; :seon.config/render section (#46). The accessor reads the section with a
;;; literal fallback equal to the manifest default, so an absent section is
;;; byte-identical to the shipped value.

(deftest render-caps-read-the-manifest
  ;; These are pure pre-attach manifest resolver tests. Authority-backed
  ;; config reads belong to the database facade contract, not a local conn.
  (testing "an absent :seon.config/render section → the accessor's literal fallback"
    (let [configuration (config/resolve-config-singleton {})]
      (is (= 72 (config/value-width configuration)))
      (is (= 16384 (config/database-edn-cap configuration)))
      (is (= 3 (config/value-max-depth configuration)))
      (is (= 32 (config/value-max-path-segments configuration)))
      (is (= 4096 (config/value-max-path-bytes configuration)))
      (is (= 1024 (config/value-max-realized-items configuration)))))
  (testing "a manifest value overrides the fallback"
    (let [configuration
          (config/resolve-config-singleton
           {:seon.config/render
            {:seon.config.render/value-width 40
             :seon.config.render/database-edn-cap 999
             :seon.config.render/value-max-path-segments 31
             :seon.config.render/value-max-path-bytes 4095
             :seon.config.render/value-max-realized-items 1023}})]
      (is (= 40 (config/value-width configuration)))
      (is (= 999 (config/database-edn-cap configuration)))
      (is (= 31 (config/value-max-path-segments configuration)))
      (is (= 4095 (config/value-max-path-bytes configuration)))
      (is (= 1023 (config/value-max-realized-items configuration)))
      ;; an unset key in a present section still falls back to the literal
      (is (= 3 (config/value-max-depth configuration))))))

(deftest reactive-policy-is-resolved-into-the-config-singleton
  (let [defaults (config/resolve-config-singleton {})
        configured
        (config/resolve-config-singleton
         {:seon.config/reactive
          {:seon.config/reactive-settle-ms 7
           :seon.config/reactive-structural-settle-ms 70
           :seon.config/reactive-max-latency-ms 700}})]
    (is (= {:seon.config/reactive-settle-ms 16
            :seon.config/reactive-structural-settle-ms 300
            :seon.config/reactive-max-latency-ms 500}
           (config/reactive-policy defaults)))
    (is (= {:seon.config/reactive-settle-ms 7
            :seon.config/reactive-structural-settle-ms 70
            :seon.config/reactive-max-latency-ms 700}
           (config/reactive-policy configured)))))

(deftest database-read-policy-is-resolved-into-the-config-singleton
  (let [defaults (config/resolve-config-singleton {})
        configured
        (config/resolve-config-singleton
         {:seon.config/database
          {:seon.config.database.query/max-work 7000000
           :seon.config.database.query/max-results 70000
           :seon.config.database.query/max-result-weight 700000
           :seon.config.database.pull/max-work 8000000
           :seon.config.database.pull/max-results 80000
           :seon.config.database.pull/max-result-weight 800000}})]
    (is (= config/default-database-query-policy
           (config/database-query-policy defaults)))
    (is (= config/default-database-pull-policy
           (config/database-pull-policy defaults)))
    (is (= {:seon.config.database.query/max-work 7000000
            :seon.config.database.query/max-results 70000
            :seon.config.database.query/max-result-weight 700000}
           (config/database-query-policy configured)))
    (is (= {:seon.config.database.pull/max-work 8000000
            :seon.config.database.pull/max-results 80000
            :seon.config.database.pull/max-result-weight 800000}
           (config/database-pull-policy configured)))))

(deftest reactive-environment-overrides-resolve-before-database-seeding
  (with-env
    "SEON_REACTIVE_SETTLE_MS" "8"
    (fn []
      (with-env
        "SEON_REACTIVE_STRUCTURAL_SETTLE_MS" "80"
        (fn []
          (with-env
            "SEON_REACTIVE_MAX_LATENCY_MS" "800"
            (fn []
              (let [manifest (manifest-via-config "config/system.edn")]
                (is (= {:seon.config/reactive-settle-ms 8
                        :seon.config/reactive-structural-settle-ms 80
                        :seon.config/reactive-max-latency-ms 800}
                       (:seon.config/reactive manifest)))))))))))

;;; CONFIG ACCESSORS — process-only env coercion plus the explicit,
;;; singleton-owned corpus directory contract.
;;; ([[with-env]] is defined above with the soul-block tests.)

(deftest env-int-coerces-positive-or-default
  (testing "positive int parses; blank/non-numeric/non-positive fall to default"
    (with-env "SEON_TEST_CAP" "350"
      #(is (= 350 (config/env-int "SEON_TEST_CAP" 99))))
    (with-env "SEON_TEST_CAP" "0"
      #(is (= 99 (config/env-int "SEON_TEST_CAP" 99))))   ; non-positive → default
    (with-env "SEON_TEST_CAP" "abc"
      #(is (= 99 (config/env-int "SEON_TEST_CAP" 99))))   ; non-numeric → default
    (with-env "SEON_TEST_CAP" nil
      #(is (= 99 (config/env-int "SEON_TEST_CAP" 99)))))) ; unset → default

(deftest env-string-nil-when-blank
  (with-env "SEON_TEST_STR" "  "
    #(is (nil? (config/env-string "SEON_TEST_STR"))))    ; blank → nil
  (with-env "SEON_TEST_STR" "x"
    #(is (= "x" (config/env-string "SEON_TEST_STR")))))

(deftest manifest-input-is-explicit
  (with-env "SEON_CONFIG" nil
    #(is (nil? (config/load-manifest))))
  (with-env "SEON_CONFIG" "tmp/no-such-config.edn"
    #(is (thrown? js/Error (config/load-manifest)))))

(deftest skills-dir-precedence
  (testing "one declared string wins and absence means no corpus"
    (is (= "from/manifest"
           (config/skills-dir
            {:seon.config/skills-dir "from/manifest"})))
    (with-env "SEON_SKILLS_DIR" "ignored"
      #(is (nil? (config/skills-dir {}))))))

(deftest native-config-declarations-reject-explicit-nil-or-empty
  (doseq [[label manifest]
          [["nil skills directory" {:seon.config/skills-dir nil}]
           ["empty skills directory" {:seon.config/skills-dir ""}]
           ["nil always policy"
            {:seon.config/namespaces {:seon.config/always nil}}]
           ["empty always policy"
            {:seon.config/namespaces {:seon.config/always []}}]]]
    (is (false? (m/validate :seon.config/manifest manifest)) label)
    (let [path (write-tmp! (str "invalid-native-config-"
                                (str/replace label #" " "-") ".edn")
                           (pr-str manifest))
          error (try
                  (manifest-via-config path)
                  nil
                  (catch js/Error exception exception))]
      (is (some? error) (str label " fails at the declaration door"))
      (is (= :user-input (:seon.error/kind (ex-data error))))
      (is (re-find #"omit|must contain" (ex-message error))
          "the validation error steers toward absence or a non-empty value"))))

;;; ============================================================
;;; CONFIG → DB (config-db-migration 2026-07-10). `resolve-config-singleton` is
;;; the ONE resolver (seed source + pre-conn fallback); the boot reconcile seeds
;;; it as the `:seon.config` singleton; every accessor reads it back from the db.
;;; ============================================================

(deftest resolve-config-singleton-defaults-and-overrides
  (testing "the {} manifest resolves every knob to its byte-parity default"
    (let [s (config/resolve-config-singleton {})]
      (is (= "cluster" (:seon.config/id s)))
      ;; repl-mode's default is per-MODEL (env-derived) — pinned by its own
      ;; deftest below, not here (this test runs under the suite's ambient env).
      (is (= 1500      (:seon.config.render/eval-cap s)))
      (is (= 100       (:seon.config.run/batch-turn-limit s)))
      (is (= 300       (:seon.config.run/stream-form-limit s)))
      (is (= 1800000   (:seon.config.run/deadline-ms s)))
      (is (= 32        (:seon.config.render/value-max-path-segments s)))
      (is (= 4096      (:seon.config.render/value-max-path-bytes s)))
      (is (= 1024      (:seon.config.render/value-max-realized-items s)))
      (is (not (contains? s
                          :seon.config.model-transport/response-identity-cap))
          "config-free history preserves absence")
      (is (= :symbols  (:seon.config.repair/level s)))
      (is (= :public-only (:seon.agent.web/policy s)))
      (is (= 1         (:seon.config/spawn-depth-cap s)))
      (is (= 1200000   (:seon.config.watchdog/stale-ms s)))
      (is (= 12        (:seon.config.root/recent-limit s)))
      (is (= 16        (:seon.config/reactive-settle-ms s)))
      (is (= 300       (:seon.config/reactive-structural-settle-ms s)))
      (is (= 500       (:seon.config/reactive-max-latency-ms s)))
      (is (= {}        (config/repair-classes s)))
      (is (not-any? #(contains? s %)
                    (vals resolve/repair-class-attributes))
          "absent repair switches remain absent native facts")
      (is (= []        (:seon.agent.web/allowed-domains s)))
      (is (not (contains? s :seon.config/current-ns))
          "namespace render selection belongs to the namespaces block")
      ;; system-text has NO default — absent from a bare manifest
      (is (not (contains? s :seon.config/system-text)))))
  (testing "the namespace manifest has one source-storage option"
    (is (m/validate :seon.config/manifest
                    {:seon.config/namespaces
                     {:seon.config/always '[my.kb seon.agent.message]}}))
    (is (not (m/validate :seon.config/manifest
                         {:seon.config/namespaces
                          {:seon.config/current-ns :off}}))
        "the removed duplicate render switch fails instead of being ignored"))
  (testing "an absent cardinality-many allowlist reads as an empty vector"
    (is (= {:seon.agent.web/policy :public-only
            :seon.agent.web/allowed-domains []}
           (config/web-policy {}))))
  (testing "a manifest value overrides the resolved knob"
    (let [agent-context {:seon.agent/ctx
                         [{:seon.agent.ctx/name :transcript}]}
          root-context {:seon.agent/ctx
                        [{:seon.agent.ctx/name :canvas}]}
          skills-dir "seon-skills"
          s (config/resolve-config-singleton
              {:seon.config/render
               {:seon.config.render/eval-cap 42
                :seon.config.render/value-max-path-segments 17
                :seon.config.render/value-max-path-bytes 2048
                :seon.config.render/value-max-realized-items 512}
               :seon.config/run {:seon.config.run/batch-turn-limit 7
                                 :seon.config.run/stream-form-limit 19
                                 :seon.config.run/deadline-ms 123456}
               :seon.config/root {:seon.config.root/recent-limit 9}
               :seon.config/model-transport
               {:seon.config.model-transport/response-identity-cap 17
                :seon.config.model-transport/endpoint-cap 29}
               :seon.config/on-core-error :log
               :seon.config/system-text "you are a helpful agent"
               :seon.config/skills-dir skills-dir
               :seon.config/agent-context agent-context
               :seon.config/root-context root-context})]
      (is (= 42 (:seon.config.render/eval-cap s)))
      (is (= 17 (:seon.config.render/value-max-path-segments s)))
      (is (= 2048 (:seon.config.render/value-max-path-bytes s)))
      (is (= 512 (:seon.config.render/value-max-realized-items s)))
      (is (= 7 (:seon.config.run/batch-turn-limit s)))
      (is (= 19 (:seon.config.run/stream-form-limit s)))
      (is (= 123456 (:seon.config.run/deadline-ms s)))
      (is (= 9 (:seon.config.root/recent-limit s)))
      (is (= 17 (:seon.config.model-transport/response-identity-cap s)))
      (is (= 29 (:seon.config.model-transport/endpoint-cap s)))
      (is (= :log (:seon.config/on-core-error s)))
      (is (= "you are a helpful agent" (:seon.config/system-text s)))
      (is (= skills-dir (:seon.config/skills-dir s)))
      (is (= agent-context (:seon.config/agent-context s)))
      (is (= root-context (:seon.config/root-context s))))))

(deftest native-config-values-never-enter-the-edn-slot-encoder
  (let [singleton (config/resolve-config-singleton
                   {:seon.config/skills-dir "seon-skills"
                    :seon.config/namespaces
                    {:seon.config/always '[my.kb seon.agent.message]}
                    :seon.config/repair
                    {:seon.config.repair/classes
                     {:seon.repl.parse.repair/undeclared-var false}}
                    :seon.config/model-variants
                    {:planning {:seon.ai/agent-model "planner"}}})
        native-values (select-keys singleton
                                   [:seon.config/id
                                    :seon.config/always
                                    :seon.config/skills-dir
                                    :seon.config.repair.class/undeclared-var?
                                    :seon.config/model-variants])]
    (is (= native-values
           (first (db/encode-edn-slot-values [native-values]))))
    (is (= #{'my.kb 'seon.agent.message}
           (:seon.config/always singleton)))
    (is (= "seon-skills" (:seon.config/skills-dir singleton)))
    (is (false? (:seon.config.repair.class/undeclared-var? singleton)))
    (is (= {:planning {:seon.ai/agent-model "planner"}}
           (config/model-variants singleton)))))

(deftest pulled-model-variant-components-decode-to-the-consumer-map
  (let [pulled {:seon.config/id "cluster"
                :seon.config/model-variants
                [{:db/id 42
                  :seon.config/model-variant :planning
                  :seon.ai/agent-model "planner"}
                 {:db/id 43
                  :seon.config/model-variant :muse
                  :seon.ai/agent-thinking "minimal"}]}
        decoded (db/decode-edn-values pulled)]
    (is (= {:planning {:seon.ai/agent-model "planner"}
            :muse {:seon.ai/agent-thinking "minimal"}}
           (config/model-variants decoded))
        "the authority's pulled component vector becomes the consumer map")))

(deftest reconcile-replaces-model-variant-components-exactly
  (let [identity [:seon.config/id config/cluster-config-id]
        installed {:seon.config/model-variants
                   {:db/valueType :db.type/ref
                    :db/cardinality :db.cardinality/many
                    :db/isComponent true}}
        current {:seon.config/id config/cluster-config-id
                 :seon.config/model-variants
                 [{:db/id 42 :seon.config/model-variant :planning
                   :seon.ai/agent-model "old"}
                  {:db/id 43 :seon.config/model-variant :muse
                   :seon.ai/agent-model "muse"}]}
        desired {:seon.config/id config/cluster-config-id
                 :seon.config/model-variants
                 [{:seon.config/model-variant :planning
                   :seon.ai/agent-model "new"}
                  {:seon.config/model-variant :execution
                   :seon.ai/agent-model "fast"}]}
        tx-data (#'state/entity-exact-tx
                 {} {} installed identity desired current)]
    (is (= [[:db.fn/retractAttribute identity :seon.config/model-variants]
            desired]
           tx-data)
        "one reconcile retracts removed children before adding the exact tree")))

(deftest pulled-cardinality-many-set-attrs-decode-to-their-registered-shape
  ;; Datahike materializes cardinality-many values as VECTORS on pull/entity.
  ;; The singleton acquisition decodes through the one boundary
  ;; (db/decode-edn-values), which must reconstruct the registered :set shape
  ;; — the live boot failure this guards: namespaces-policy rejected the
  ;; pulled vector at instrumentation and the pod died before readiness.
  (let [pulled {:seon.config/id "cluster"
                :seon.config/always ['my.kb 'seon.agent.message]}
        decoded (db/decode-edn-values pulled)]
    (is (= #{'my.kb 'seon.agent.message} (:seon.config/always decoded))
        "the pulled vector decodes to the registered set shape")
    (is (= {:seon.config/always #{'my.kb 'seon.agent.message}}
           (config/namespaces-policy decoded))
        "the decoded singleton satisfies the resolved policy schema")))

(deftest reconcile-replaces-the-always-source-set-exactly
  (let [identity [:seon.config/id config/cluster-config-id]
        installed {:seon.config/always
                   {:db/valueType :db.type/symbol
                    :db/cardinality :db.cardinality/many}}
        current {:seon.config/id config/cluster-config-id
                 :seon.config/always #{'my.kb 'my.ui 'old.ns}}
        desired {:seon.config/id config/cluster-config-id
                 :seon.config/always #{'my.kb 'my.ui 'new.ns}}
        tx-data (#'state/entity-exact-tx
                 {} {} installed identity desired current)]
    (is (= [[:db.fn/retractAttribute identity :seon.config/always]
            desired]
           tx-data)
        "one reconcile retracts the old many attr before asserting the exact set")))

(deftest agent-context-is-derived-from-explicit-config-data
  (let [stored {:seon.config/id config/cluster-config-id
                :seon.config/agent-context
                {:seon.agent/ctx [{:seon.agent.ctx/name :transcript}]}
                :seon.config/root-context
                {:seon.agent/ctx [{:seon.agent.ctx/name :canvas}]}}]
    (with-redefs [config/load-manifest
                  (fn [] (throw (js/Error. "external config must not be read")))]
      (is (= #{:transcript}
             (into #{} (map :seon.agent.ctx/name)
                   (:seon.agent/ctx
                    (config/resolve-agent-context "worker-x" nil stored)))))
      (is (= #{:transcript :canvas}
             (into #{} (map :seon.agent.ctx/name)
                   (:seon.agent/ctx
                    (config/resolve-agent-context "root" nil stored))))))))

(deftest repl-mode-default-is-per-model
  ;; The manifest-absent repl-mode default is computed from the model
  ;; identity the :seon.ai/config row seeds from (measured 2026-07-10:
  ;; DeepSeek fabricates in :batch, :stream removes it structurally;
  ;; Spark-class models are ~0-fab in :batch and :stream only costs them
  ;; latency). Env is stashed/restored — the suite's ambient values differ.
  (let [env     (.-env js/process)
        saved-p (.-SEON_AI_PROVIDER env)
        saved-m (.-SEON_AI_MODEL env)
        mode-of (fn [m] (:seon.config/repl-mode (config/resolve-config-singleton m)))]
    (try
      (testing "env unset (the shipped :deepseek default) → :stream"
        (js-delete env "SEON_AI_PROVIDER")
        (js-delete env "SEON_AI_MODEL")
        (is (= :stream (mode-of {}))))
      (testing "a non-deepseek gateway model → :batch"
        (set! (.-SEON_AI_PROVIDER env) "openai-compat")
        (set! (.-SEON_AI_MODEL env) "muse-spark-1.1")
        (is (= :batch (mode-of {}))))
      (testing "a deepseek MODEL through a generic gateway → :stream"
        (set! (.-SEON_AI_MODEL env) "deepseek-v4-flash")
        (is (= :stream (mode-of {}))))
      (testing "an explicit manifest value always wins"
        (js-delete env "SEON_AI_PROVIDER")
        (js-delete env "SEON_AI_MODEL")
        (is (= :batch (mode-of {:seon.config/repl-mode :batch}))))
      (finally
        (if (some? saved-p)
          (set! (.-SEON_AI_PROVIDER env) saved-p)
          (js-delete env "SEON_AI_PROVIDER"))
        (if (some? saved-m)
          (set! (.-SEON_AI_MODEL env) saved-m)
          (js-delete env "SEON_AI_MODEL"))))))
