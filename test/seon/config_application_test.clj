(ns seon.config-application-test
  "Standing proof that every registered config entry reaches a runtime consumer.

  The CONSUMER half is a query, not a list: the program graph records the
  qualified keywords each indexed declaration reads, so \"which function
  consumes `:seon.config.fs/max-depth`\" is derived from `:seon.fn/keywords`.
  A registered dial no function reads is a dead dial and fails this namespace.

  Computed request and environment routes are explicit schema properties;
  the application census is per attribute, never per namespace family."
  (:require [malli.core] [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.config :as config]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.fn :as seon.fn]
            [seon.operator.runtime :as operator.runtime]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(def ^:private applied
  {:seon.config.db/keep-history? true
   :seon.config.flow.compute/concurrency 1
   :seon.config.flow.io/queue-depth 5
   :seon.config.flow.io/concurrency 2
   :seon.config.flow/ping-timeout-ms 11
   :seon.config.eval.result/max-depth 2
   :seon.config.eval.result/max-collection 3
   :seon.config.eval.result/max-string 17
   :seon.config.eval.result/max-source 31
   :seon.config.eval.result/max-nodes 21
   :seon.config.eval/time-limit-ms 1234
   :seon.config.test/auto-check-cases 7
   :seon.config.agent/turn-completion-backstop-ms 600000
   :seon.config.error/recurrence-limit 2
   :seon.config.error/escalate-to "root"
   :seon.config.maintenance/min-usable-bytes 1048576
   :seon.config.maintenance/min-usable-ratio 0.01
   :seon.config.maintenance/log-max-bytes 1024
   :seon.config.maintenance/log-retained-files 1
   :seon.config.message/max-chain 5
   :seon.config.run/max-episode-runs 4
   :seon.config.web/port 0
   :seon.config.render/coalesce-ms 7
   :seon.config.ai/endpoint "http://127.0.0.1:1/primary"
   :seon.config.ai/model "application-proof"
   :seon.config.ai/max-tokens 123
   :seon.config.ai/prompt-token-budget 32
   :seon.config.ai/chars-per-token-prior 3.25
   :seon.config.ai/thinking :high
   :seon.config.ai/temperature 0.5
   :seon.config.ai/top-p 0.75
   :seon.config.ai/frequency-penalty -0.25
   :seon.config.ai/presence-penalty 0.25
   :seon.config.ai/stop ["END"]
   :seon.config.ai/response-format :json-object
   :seon.config.ai/extra-body-edn "{\"vendor_probe\" true}"
   :seon.config.ai/api-key-variable "SEON_APPLICATION_PROOF_KEY"
   :seon.config.ai/timeout-ms 222})

(defn- fresh-root []
  (let [root (str "tmp/config-application-test/" (random-uuid))]
    (.mkdirs (io/file root))
    (test-support/populate-published-root! root)
    root))

(defn- consumers-by-attribute
  "Attribute -> the function symbols among `rows` whose source reads it.

  Only `:seon.fn/sym` rows are consumers. A test that reads a dial proves the
  dial is exercised, never that anything in the running system applies it, so
  `:seon.test/sym` rows are deliberately excluded."
  [entities attributes]
  (reduce
   (fn [consumers entity]
     (if-let [function-symbol (:seon.fn/sym entity)]
       (reduce (fn [consumers used]
                 (cond-> consumers
                   (contains? attributes used)
                   (update used (fnil conj (sorted-set)) function-symbol)))
               consumers
               (:seon.fn/keywords entity))
       consumers))
   {}
   entities))

(defn- unapplied-attributes
  "Dials lacking a literal reader or a declared request/environment route."
  [entities forms attributes]
  (let [literal (set (keys (consumers-by-attribute entities attributes)))
        request-routes (set (keys (ai/request-attributes (seon.schema/build-projection forms))))
        environment-routes
        (into #{} (keep (fn [[attribute definition]]
                          (when (:seon.shell/environment
                                 (malli.core/properties (seon.schema/structural-schema definition)))
                            attribute))) forms)]
    (set/difference attributes literal request-routes environment-routes)))

(defn- running-system-entities
  "The function rows of the running system's source — `src` and the operator
  script — each with the keywords it reads.

  WANTED: the `src` census is a query of the program rows the test's own
  branch already holds (derived from those files), never a re-analysis of the
  tree: rebuilding the manifest of `src` cost 4.2 s of this test's 5.3 s
  (measured 2026-09-27 on default); the query costs 55 ms. The operator script
  is outside the program's source roots, so its one file is analyzed (190 ms)."
  [database]
  (into (->> (db/q '[:find ?symbol ?keyword ?path
                     :where [?function :seon.fn/sym ?symbol]
                            [?function :seon.fn/keywords ?keyword]
                            [?function :seon.fn/file ?file]
                            [?file :seon.fn.file/relative-path ?path]]
                   database)
             (filter (fn [[_ _ path]] (clojure.string/starts-with? path "src/")))
             (reduce (fn [keywords [function-symbol attribute _]]
                       (update keywords function-symbol (fnil conj #{}) attribute))
                     {})
             (mapv (fn [[function-symbol keywords]]
                     {:seon.fn/sym function-symbol :seon.fn/keywords keywords})))
        (mapcat :seon.fn.file/rows)
        (:seon.fn.manifest/artifacts
         (seon.fn/build-manifest {:seon.fn/roots ["script/seon/operator.clj"]}))))

(deftest every-config-entry-has-an-honest-application-contract
  (test-support/with-database
    (fn [connection]
      (let [forms (:seon.schema.projection/forms
                   (schema/projection-from-database @connection))
            registered (config/dial-attributes (seon.schema/build-projection forms))
            entities (running-system-entities (db/db connection))]
        (is (seq registered) "The canonical dial population must be present.")
        (is (= #{} (unapplied-attributes entities forms registered))
            "Each admitted dial has a literal consumer or a declared application route.")))))

(deftest an-unread-config-attribute-is-detected-as-a-dead-dial
  (let [attributes #{:seon.config.sample/applied :seon.config.orphan/dial}
        entities [{:seon.fn/sym "sample.consumer/apply-setting"
               :seon.fn/keywords [:seon.config.sample/applied
                                  :sample.consumer/unrelated]}
              {:seon.test/sym "sample.consumer-test/orphan-is-covered"
               :seon.fn/keywords [:seon.config.orphan/dial]}]
        consumers (consumers-by-attribute entities attributes)]
    (is (= {:seon.config.sample/applied #{"sample.consumer/apply-setting"}}
           consumers)
        "keywords outside the registered set never enter the derivation")
    (is (= #{:seon.config.orphan/dial} (unapplied-attributes entities {} attributes))
        "a dial only a test reads has no application owner and must fail")))

(deftest ^{:seon.test/fixture-observation "Real store creation, executors and listening ports must consume the applied configuration during boot."} ^{:seon.test/long "Starts a real cluster to observe applied runtime configuration."}
  applied-values-shape-the-running-system
  (let [root (fresh-root)
        name "application-proof"]
    (try
      (let [instance
            (boot/start! {:seon.boot/root root
                             :seon.boot/cluster-name name
                             :seon.config/manifest applied})
            connection (:seon.boot/cluster-connection instance)
            handle (:seon.turn.loop/cluster instance)
            launcher (:seon.flow/work-launcher instance)]
        (try
          (testing "the database representation is fixed at store creation"
            (is (true?
                 (get-in @(:seon.store/connection-object
                            (:seon.store/store instance))
                         [:config :keep-history?]))))
          (testing "flow structure consumes its applied values"
            ;; WANTED: the work launcher carries the applied IO dials; it has
            ;; had no compute half since d2604ede9. Membership, not a census.
            (let [flow-keys [:seon.config.flow.io/queue-depth
                             :seon.config.flow.io/concurrency]]
              (is (= (select-keys applied flow-keys)
                     (select-keys (::flow/configuration launcher)
                                  flow-keys)))))
          (testing "eval, error, and message structure consumes applied values"
            (is (= (config/result-caps (config/effective @connection name))
                   (:seon.sci.admit/caps handle)))
            (is (= (select-keys applied
                                [:seon.config.eval/time-limit-ms
                                 :seon.config.error/recurrence-limit
                                 :seon.config.error/escalate-to
                                 :seon.config.message/max-chain])
                   (select-keys
                    handle
                    [:seon.config.eval/time-limit-ms
                     :seon.config.error/recurrence-limit
                     :seon.config.error/escalate-to
                     :seon.config.message/max-chain]))))
          (testing "AI settings remain live facts rather than armed values"
            (is (= (select-keys applied
                                (keys (ai/request-attributes (seon.schema/build-projection (schema/declaration-population)))))
                   (select-keys (config/effective @connection name)
                                (keys (ai/request-attributes (seon.schema/build-projection (schema/declaration-population)))))))
            (is (= name (:seon.cluster/name handle)))
            (is (empty? (select-keys handle [:seon.ai/primary]))
                "the loop cannot retain a boot-captured AI projection"))
          (testing "the selected web port reaches the armed server"
            (let [url (get-in instance
                              [:seon.render.web/served
                               :seon.render.web/url])
                  bound-port (.getPort (java.net.URI. url))]
              (is (not= (web/derived-port name) bound-port)
                  "explicit port 0 reaches bind; the derived named port does not")))
          (testing "hot entries re-read the applied database value"
            (config/apply!
             {:seon.db/connection connection
              :seon.boot/cluster-name name
              :seon.config/manifest
              (assoc applied
                     :seon.config.render/coalesce-ms 31
                     :seon.config.run/max-episode-runs 6)})
            (is (= 31
                   ((var-get
                     (ns-resolve 'seon.render.web 'coalesce-floor))
                    @connection)))
            (is (= 6
                   ((var-get
                     (ns-resolve 'seon.turn 'max-episode-runs))
                    @connection "root"))))
          (finally
            (boot/stop! instance))))
      (finally
        (test-support/delete-recursively! root)))))

(deftest no-auth-is-consumed-as-the-credential-alternative
  ;; WANTED: credential selection is proved without starting a cluster,
  ;; observed on the process registry `boot/start!` publishes into, never by
  ;; redefining `boot/start!` (no Var redefinitions in tests).
  (let [cluster-name "application-no-auth"]
    (test-support/with-database
     (fn [connection]
       (config/apply!
        {:seon.db/connection connection
         :seon.boot/cluster-name cluster-name
         :seon.config/manifest
         (-> applied
             (assoc :seon.config.ai/no-auth true))})
       (let [primary (:seon.ai/primary
                      (ai/targets (schema/handed-projection)
                                  (config/effective (db/db connection) cluster-name)))]
         (is (true? (:seon.config.ai/no-auth primary)))
         (is (not (contains? primary :seon.ai/api-key-variable))))))
    (is (not (contains? @operator.runtime/running-instances cluster-name))
        "Credential selection is proved without starting a cluster.")))
