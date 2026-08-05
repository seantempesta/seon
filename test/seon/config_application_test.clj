(ns seon.config-application-test
  "Standing proof that every registered config entry reaches a runtime consumer.

  The CONSUMER half is a query, not a list: the program graph records the
  qualified keywords each indexed declaration reads, so \"which function
  consumes `:seon.config.fs/max-depth`\" is derived from `:seon.fn/keywords`.
  A registered dial no function reads is a dead dial and fails this namespace.

  `application-modes` keeps only what the graph genuinely cannot see — WHEN an
  applied value takes effect. `:creation-fixed` values are settled when the
  store is created; `:arm-time` values shape structural runtime state and
  require re-arm after apply; `:live` values query the current database value
  on each pass; `:mixed` has both paths."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.flow :as flow]
            [seon.fn :as seon.fn]
            [seon.render.web :as web]
            [seon.test-support :as test-support]))

(def ^:private application-modes
  "Registered config attribute -> when its applied value takes effect."
  {:seon.config/initialization :creation-fixed
   :seon.config.db/keep-history? :creation-fixed
   :seon.config.flow.compute/queue-depth :arm-time
   :seon.config.flow.compute/concurrency :arm-time
   :seon.config.flow.io/queue-depth :arm-time
   :seon.config.flow.io/concurrency :arm-time
   :seon.config.flow/ping-timeout-ms :live
   :seon.config.effect/long-call-ms :live
   :seon.config.shell/home :live
   :seon.config.shell/lang :live
   :seon.config.shell/path :live
   :seon.config.shell/inline-output-bytes :live
   :seon.config.shell/preview-bytes :live
   :seon.config.shell/stdin-max-bytes :live
   :seon.config.shell/termination-grace-ms :live
   :seon.config.shell/time-limit-ms :live
   :seon.config.eval.result/max-depth :arm-time
   :seon.config.eval.result/max-collection :arm-time
   :seon.config.eval.result/max-string :arm-time
   :seon.config.eval.result/max-nodes :arm-time
   :seon.config.eval.result/blob-threshold :live
   :seon.config.render.agent/token-budget :live
   :seon.config.render.agent/max-depth :live
   :seon.config.render.agent/max-children :live
   :seon.config.render.agent/composition :live
   :seon.config.eval/time-limit-ms :arm-time
   :seon.config.error/recurrence-limit :mixed
   :seon.config.error/escalate-to :mixed
   :seon.config/on-core-error :mixed
   :seon.config.maintenance/min-usable-bytes :live
   :seon.config.maintenance/min-usable-ratio :live
   :seon.config.maintenance/log-max-bytes :live
   :seon.config.maintenance/log-retained-files :live
   :seon.config.operator/event-silence-backstop-ms :arm-time
   :seon.config.message/max-chain :arm-time
   :seon.config.run/max-episode-runs :live
   :seon.config.web/port :arm-time
   :seon.config.web/timeout-ms :live
   :seon.config.web/max-response-bytes :live
   :seon.config.web/max-inline-bytes :live
   :seon.config.web/max-redirects :live
   :seon.config.web/max-search-results :live
   :seon.config.web/search-endpoint :live
   :seon.config.web/search-api-key-variable :live
   :seon.config.web/search-result-projection :live
   :seon.config.render/coalesce-ms :live
   :seon.render.value/max-collection :live
   :seon.print/length :live
   :seon.print/level :live
   :seon.config.fs/working-root :live
   :seon.config.fs/roots :live
   :seon.config.fs/max-read-bytes :live
   :seon.config.fs/max-inline-bytes :live
   :seon.config.fs/max-write-bytes :live
   :seon.config.fs/max-glob-results :live
   :seon.config.fs/max-traversal-entries :live
   :seon.config.fs/max-depth :live
   :seon.config.ai/endpoint :live
   :seon.config.ai/model :live
   :seon.config.ai/max-tokens :live
   :seon.config.ai/thinking :live
   :seon.config.ai/temperature :live
   :seon.config.ai/top-p :live
   :seon.config.ai/frequency-penalty :live
   :seon.config.ai/presence-penalty :live
   :seon.config.ai/stop :live
   :seon.config.ai/response-format :live
   :seon.config.ai/extra-body-edn :live
   :seon.config.ai/api-key-variable :live
   :seon.config.ai/no-auth :live
   :seon.config.ai/timeout-ms :live
   :seon.config.ai.backup/model :live
   :seon.config.ai.backup/endpoint :live
   :seon.config.ai.backup/api-key-variable :live
   :seon.config.ai.backup/timeout-ms :live
   :seon.config.ai.retry/base-delay-ms :live
   :seon.config.ai.retry/multiplier :live
   :seon.config.ai.retry/jitter-fraction :live
   :seon.config.ai.retry/maximum-delay-ms :live
   :seon.config.ai.retry/maximum-retries :live
   :seon.config.ai.retry/maximum-total-delay-ms :live})

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
   :seon.config.eval.result/max-nodes 21
   :seon.config.eval/time-limit-ms 1234
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
    (cluster/refresh-source! root)
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

(defn- unapplied-families
  "Config keyword namespaces no indexed function reads at all.

  Application is asserted at family grain because two shipped consumers apply
  a whole family through a COMPUTED key rather than a literal one:
  `seon.ai/primary-setting-entries` renames every `:seon.config.ai/*` dial to
  its `:seon.ai/*` request field, and `seon.shell.jvm/environment-overrides`
  applies every dial whose schema declares `:seon.shell/environment`. Static
  analysis sees literal keywords only, so a per-attribute assertion would call
  those live dials dead. Tightening to per-attribute grain is
  `docs/seon/issues/config-ai-request-idents-are-derived-by-string-surgery.md`."
  [rows attributes]
  (let [consumed (set (keys (consumers-by-attribute rows attributes)))]
    (set/difference (into #{} (map namespace) attributes)
                    (into #{} (map namespace) consumed))))

(defn- source-rows []
  (into []
        (mapcat :seon.fn.file/rows)
        (:seon.fn.manifest/artifacts
         (seon.fn/build-manifest {:seon.fn/roots ["src" "script"]}))))

(deftest every-config-entry-has-an-honest-application-contract
  (let [registered
        (set (keys (edn/read-string (slurp config/default-manifest-path))))]
    (is (= registered (set (keys application-modes)))
        "a newly registered entry cannot ship without a declared update mode")
    (is (= #{:creation-fixed :arm-time :live :mixed}
           (set (vals application-modes))))
    (testing "the program graph, not a hand list, names each dial's consumer"
      (let [rows (source-rows)]
        (is (= #{} (unapplied-families rows registered))
            (str "a registered config family no indexed first-party function reads "
                 "is a dead dial: nothing in the running system applies it"))
        (is (= ["seon.fs.jvm/glob"]
               (vec (get (consumers-by-attribute rows registered)
                         :seon.config.fs/max-depth)))
            (str "the consumer is derived, so it stays correct without "
                 "maintenance; the retired hand ledger named path-plan here"))))))

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
    (is (= #{"seon.config.orphan"} (unapplied-families rows attributes))
        "a dial only a test reads has no application owner and must fail")))

(deftest ^{:seon.test/long "Starts a real cluster to observe applied runtime configuration."}
  applied-values-shape-the-running-system
  (let [root (fresh-root)
        name "application-proof"]
    (try
      (let [instance
            (cluster/start! {:seon.boot/root root
                             :seon.boot/cluster-name name
                             :seon.config/manifest applied})
            connection (:seon.boot/cluster-connection instance)
            handle (:seon.cluster.loop/cluster instance)
            launcher (:seon.flow/work-launcher instance)]
        (try
          (testing "the database representation is fixed at store creation"
            (is (true?
                 (get-in @(:seon.store/connection-object
                            (:seon.store/store instance))
                         [:config :keep-history?]))))
          (testing "flow structure consumes its applied values"
            (is (= (select-keys applied
                                [:seon.config.flow.compute/queue-depth
                                 :seon.config.flow.compute/concurrency
                                 :seon.config.flow.io/queue-depth
                                 :seon.config.flow.io/concurrency])
                   (::flow/configuration launcher))))
          (testing "eval, error, and message structure consumes applied values"
            (is (= (config/result-caps applied)
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
                                (filter #(str/starts-with? (namespace %)
                                                           "seon.config.ai")
                                        (keys applied)))
                   (select-keys (config/effective @connection name)
                                (filter #(str/starts-with? (namespace %)
                                                           "seon.config.ai")
                                        (keys applied)))))
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
                     (ns-resolve 'seon.cluster.work 'max-episode-runs))
                    @connection))))
          (finally
            (cluster/stop! instance))))
      (finally
        (test-support/delete-recursively! root)))))

(deftest ^{:seon.test/long "Starts a real cluster to exercise credential selection."}
  no-auth-is-consumed-as-the-credential-alternative
  (let [root (fresh-root)]
    (try
      (let [instance
            (cluster/start!
             {:seon.boot/root root
              :seon.boot/cluster-name "application-no-auth"
              :seon.config/manifest
              (-> applied
                  (assoc :seon.config.ai/no-auth true)
                  (dissoc :seon.config.ai.backup/model
                          :seon.config.ai.backup/endpoint
                          :seon.config.ai.backup/api-key-variable
                          :seon.config.ai.backup/timeout-ms))})
            connection (:seon.boot/cluster-connection instance)
            primary
            (-> (config/effective @connection "application-no-auth")
                ai/targets
                :seon.ai/primary)]
        (try
          (is (true? (:seon.config.ai/no-auth primary)))
          (is (not (contains? primary :seon.ai/api-key-variable)))
          (finally
            (cluster/stop! instance))))
      (finally
        (test-support/delete-recursively! root)))))
