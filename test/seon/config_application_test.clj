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
            [clojure.test :refer [deftest is testing]]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.fn :as seon.fn]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(def ^:private applied
  {:seon.config.db/keep-history? true
   :seon.config.flow.compute/queue-depth 3
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
   :seon.config.bootstrap/beyond-closure-token-budget 512
   :seon.config.error/recurrence-limit 2
   :seon.config.error/escalate-to "root"
   :seon.config/on-core-error :record
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
   :seon.config.ai/timeout-ms 222
   :seon.config.ai.backup/model "application-proof-backup"
   :seon.config.ai.backup/endpoint "http://127.0.0.1:1/backup"
   :seon.config.ai.backup/api-key-variable "SEON_APPLICATION_PROOF_BACKUP_KEY"
   :seon.config.ai.backup/timeout-ms 333
   :seon.config.ai.retry/base-delay-ms 11
   :seon.config.ai.retry/multiplier 1.5
   :seon.config.ai.retry/jitter-fraction 0.1
   :seon.config.ai.retry/maximum-delay-ms 99
   :seon.config.ai.retry/maximum-retries 1
   :seon.config.ai.retry/maximum-total-delay-ms 100})

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
  [rows attributes]
  (reduce
   (fn [consumers row]
     (if-let [function-symbol (:seon.fn/sym row)]
       (reduce (fn [consumers used]
                 (cond-> consumers
                   (contains? attributes used)
                   (update used (fnil conj (sorted-set)) function-symbol)))
               consumers
               (:seon.fn/keywords row))
       consumers))
   {}
   rows))

(defn- unapplied-attributes
  "Dials lacking a literal reader or a declared request/environment route."
  [rows forms attributes]
  (let [literal (set (keys (consumers-by-attribute rows attributes)))
        request-routes (set (keys (ai/request-attributes (seon.schema/build-projection forms))))
        environment-routes
        (into #{} (keep (fn [[attribute definition]]
                          (when (:seon.shell/environment
                                 (malli.core/properties (seon.schema/structural-schema definition)))
                            attribute))) forms)]
    (set/difference attributes literal request-routes environment-routes)))

(defn- source-rows []
  (into []
        (mapcat :seon.fn.file/rows)
        (:seon.fn.manifest/artifacts
         (seon.fn/build-manifest
          {:seon.fn/roots ["src" "script/seon/fresh_operator.clj"]}))))

(deftest every-config-entry-has-an-honest-application-contract
  (test-support/with-database
    (fn [connection]
      (let [forms (:seon.schema.projection/forms
                   (schema/projection-from-database @connection))
            registered (config/dial-attributes (seon.schema/build-projection forms))
            rows (source-rows)]
        (is (seq registered) "The canonical dial population must be present.")
        (is (= #{} (unapplied-attributes rows forms registered))
            "Each admitted dial has a literal consumer or a declared application route.")))))

(deftest an-unread-config-attribute-is-detected-as-a-dead-dial
  (let [attributes #{:seon.config.sample/applied :seon.config.orphan/dial}
        rows [{:seon.fn/sym "sample.consumer/apply-setting"
               :seon.fn/keywords [:seon.config.sample/applied
                                  :sample.consumer/unrelated]}
              {:seon.test/sym "sample.consumer-test/orphan-is-covered"
               :seon.fn/keywords [:seon.config.orphan/dial]}]
        consumers (consumers-by-attribute rows attributes)]
    (is (= {:seon.config.sample/applied #{"sample.consumer/apply-setting"}}
           consumers)
        "keywords outside the registered set never enter the derivation")
    (is (= #{:seon.config.orphan/dial} (unapplied-attributes rows {} attributes))
        "a dial only a test reads has no application owner and must fail")))

(deftest ^{:seon.test/fixture-observation "Real store creation, executors and listening ports must consume the applied configuration during boot."} ^{:seon.test/long "Starts a real cluster to observe applied runtime configuration."}
  applied-values-shape-the-running-system
  (let [root (fresh-root)
        name "application-proof"]
    (try
      (let [instance
            (cluster/start! {:seon.boot/root root
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
            ;; membership, not an exact census — carried accretions
            ;; (the turn-completion backstop rides here now) never
            ;; break the four consumed values being applied
            (let [flow-keys [:seon.config.flow.compute/queue-depth
                             :seon.config.flow.compute/concurrency
                             :seon.config.flow.io/queue-depth
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
                                 :seon.config/on-core-error
                                 :seon.config.message/max-chain])
                   (select-keys
                    handle
                    [:seon.config.eval/time-limit-ms
                     :seon.config.error/recurrence-limit
                     :seon.config.error/escalate-to
                     :seon.config/on-core-error
                     :seon.config.message/max-chain]))))
          (testing "AI settings remain live facts rather than armed values"
            (is (= (select-keys applied
                                (keys (ai/request-attributes (seon.schema/build-projection (schema/declaration-population)))))
                   (select-keys (config/effective @connection name)
                                (keys (ai/request-attributes (seon.schema/build-projection (schema/declaration-population)))))))
            (is (= name (:seon.cluster/name handle)))
            (is (empty? (select-keys handle
                                     [:seon.ai/primary
                                      :seon.ai/backup
                                      :seon.ai.retry/strategy]))
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
            (cluster/stop! instance))))
      (finally
        (test-support/delete-recursively! root)))))

(deftest no-auth-is-consumed-as-the-credential-alternative
  (let [starts (atom 0)
        start cluster/start!]
    (with-redefs [cluster/start!
                  (fn [& args] (swap! starts inc) (apply start args))]
      (test-support/with-database
       (fn [connection]
         (config/apply!
          {:seon.db/connection connection
           :seon.boot/cluster-name "application-no-auth"
           :seon.config/manifest
           (-> applied
               (assoc :seon.config.ai/no-auth true)
               (dissoc :seon.config.ai.backup/model
                       :seon.config.ai.backup/endpoint
                       :seon.config.ai.backup/api-key-variable
                       :seon.config.ai.backup/timeout-ms))})
         (let [primary (:seon.ai/primary
                        (ai/targets (schema/handed-projection)
                                    (config/effective (db/db connection) "application-no-auth")))]
           (is (true? (:seon.config.ai/no-auth primary)))
           (is (not (contains? primary :seon.ai/api-key-variable)))))))
    (is (zero? @starts)
        "Credential selection is proved without starting a cluster.")))
