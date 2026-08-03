(ns seon.config-application-test
  "Standing proof that every registered config entry reaches a runtime consumer.

  `application-ledger` documents update truth per entry. `:arm-time` values
  shape structural runtime state and require re-arm after apply; `:live` values
  query the current database value on each pass; `:mixed` has both paths."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.flow :as flow]
            [seon.render.web :as web]
            [seon.test-support :as test-support]))

(def ^:private application-ledger
  {:seon.config.db/keep-history?
   {:mode :creation-fixed :consumer 'seon.cluster/acquire-root-store!}
   :seon.config.flow.compute/queue-depth
   {:mode :arm-time :consumer 'seon.flow/start-work-launcher!}
   :seon.config.flow.compute/concurrency
   {:mode :arm-time :consumer 'seon.flow/start-work-launcher!}
   :seon.config.eval.result/max-depth
   {:mode :arm-time :consumer 'seon.cluster/loop-handle}
   :seon.config.eval.result/max-collection
   {:mode :arm-time :consumer 'seon.cluster/loop-handle}
   :seon.config.eval.result/max-string
   {:mode :arm-time :consumer 'seon.cluster/loop-handle}
   :seon.config.eval.result/max-nodes
   {:mode :arm-time :consumer 'seon.cluster/loop-handle}
   :seon.config.eval.result/blob-threshold
   {:mode :live
    :consumer '[seon.cluster.loop/store-session-values!
                seon.cluster.loop/settlement-result
                seon.cluster.loop/record-attempt!]}
   :seon.config.eval/time-limit-ms
   {:mode :arm-time :consumer 'seon.cluster/loop-handle}
   :seon.config.error/recurrence-limit
   {:mode :mixed :consumer 'seon.cluster/commit-fault!}
   :seon.config.error/escalate-to
   {:mode :mixed :consumer 'seon.cluster/commit-fault!}
   :seon.config/on-core-error
   {:mode :mixed :consumer 'seon.flow/start-error-fanout!}
   :seon.config.message/max-chain
   {:mode :arm-time :consumer 'seon.cluster/loop-handle}
   :seon.config.run/max-episode-runs
   {:mode :live :consumer 'seon.cluster.work/max-episode-runs}
   :seon.config.web/port
   {:mode :arm-time :consumer 'seon.cluster/serve!}
   :seon.config.render/coalesce-ms
   {:mode :live :consumer 'seon.render.web/coalesce-floor}
   :seon.render.value/max-collection
   {:mode :live
    :consumer '[seon.cluster.loop/settlement-result
                seon.render.web/data-response]}
   :seon.config.ai/endpoint
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/model
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/max-tokens
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/thinking
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/temperature
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/top-p
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/frequency-penalty
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/presence-penalty
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/stop
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/response-format
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/extra-body-edn
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/api-key-variable
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/no-auth
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai/timeout-ms
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai.backup/model
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai.backup/endpoint
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai.backup/api-key-variable
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai.backup/timeout-ms
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai.retry/base-delay-ms
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai.retry/multiplier
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai.retry/jitter-fraction
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai.retry/maximum-delay-ms
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai.retry/maximum-retries
   {:mode :live :consumer 'seon.cluster.loop/turn}
   :seon.config.ai.retry/maximum-total-delay-ms
   {:mode :live :consumer 'seon.cluster.loop/turn}})

(def ^:private applied
  {:seon.config.db/keep-history? true
   :seon.config.flow.compute/queue-depth 3
   :seon.config.flow.compute/concurrency 1
   :seon.config.eval.result/max-depth 2
   :seon.config.eval.result/max-collection 3
   :seon.config.eval.result/max-string 17
   :seon.config.eval.result/max-nodes 21
   :seon.config.eval/time-limit-ms 1234
   :seon.config.error/recurrence-limit 2
   :seon.config.error/escalate-to "root"
   :seon.config/on-core-error :record
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

(deftest every-config-entry-has-an-honest-application-contract
  (let [registered
        (set (keys (edn/read-string (slurp config/default-manifest-path))))]
    (is (= registered (set (keys application-ledger)))
        "a newly registered entry cannot ship without an application owner")
    (is (= #{:creation-fixed :arm-time :live :mixed}
           (set (map (comp :mode val) application-ledger))))))

(deftest applied-values-shape-the-running-system
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
                 (get-in @(:seon.store/connection
                            (:seon.store/store instance))
                         [:config :keep-history?]))))
          (testing "flow structure consumes its applied values"
            (is (= (select-keys applied
                                [:seon.config.flow.compute/queue-depth
                                 :seon.config.flow.compute/concurrency])
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
             {:seon.config/connection connection
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

(deftest no-auth-is-consumed-as-the-credential-alternative
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
