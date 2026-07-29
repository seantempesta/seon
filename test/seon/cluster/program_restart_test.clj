(ns seon.cluster.program-restart-test
  "Live restart proof for database-backed SCI program acquisition."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.core :as datahike]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.message :as message]))

(defn- delete-recursively! [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [child (reverse (file-seq file))]
        (.delete ^java.io.File child)))))

(defn- transact-inbound!
  [connection agent-id content]
  (d/transact
   connection
   {:tx-data
    [[:db.fn/call
      #'message/inbound-tx
      {:seon.cluster.agent/id agent-id
       :seon.cluster.message/inbound-content content
       :seon.cluster.message/at (java.util.Date.)
       :seon.config.eval.result/max-string 4096}]]}))

(defn- await-commit!
  "Wait on Datahike's commit event, with a loud test-only backstop."
  [connection predicate publish!]
  (let [result (promise)
        listener-key (random-uuid)
        observe! (fn [db]
                   (when (predicate db)
                     (deliver result db)))]
    (datahike/listen! connection listener-key
                      #(observe! (:db-after %)))
    (try
      (observe! @connection)
      (publish!)
      (let [observed (deref result 20000 ::timeout)]
        (when (= ::timeout observed)
          (throw (ex-info "The scratch cluster did not commit the expected fact."
                          {:seon.error/kind ::commit-timeout})))
        observed)
      (finally
        (datahike/unlisten! connection listener-key)))))

(defn- function-present?
  [db]
  (some? (d/pull db [:seon.fn/sym]
                 [:seon.fn/sym "my.agents.restart-a/persisted"])))

(defn- restarted-call-present?
  [db]
  (boolean
   (d/q '[:find ?receipt .
          :where
          [?agent :seon.cluster.agent/id "restart-b"]
          [?run :seon.cluster.run/agent ?agent]
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/ordinal 0]
          [?receipt :seon.cluster.eval/result-edn "42"]]
        db)))

(deftest an-agent-definition-survives-restart-and-another-agent-calls-it
  (let [root (str "tmp/program-restart-test/" (random-uuid))
        cluster-name (str "program-restart-" (random-uuid))]
    (.mkdirs (io/file root))
    (try
      (let [first-instance
            (cluster/start! {:seon.boot/cluster-name cluster-name
                             :seon.boot/root root})
            connection (:seon.boot/cluster-connection first-instance)]
        (try
          (d/transact
           connection
           (agent/creation-tx
            {:seon.cluster.agent/id "restart-a"
             :seon.ns/name 'my.agents.restart-a}))
          (with-redefs
            [ai/complete
             (fn [{prompt :seon.ai/prompt}]
               {:seon.ai/text
                (if (str/includes? prompt "You are agent restart-a")
                  (str
                   "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                   "persisted [x] (inc x))\n"
                   "(my.run/complete \"definition committed\")")
                  "(my.run/complete \"unexpected agent\")")})]
            (await-commit!
             connection
             function-present?
             #(transact-inbound! connection "restart-a"
                                 "Define the durable increment function.")))
          (is (function-present? @connection))
          (finally
            (cluster/stop! first-instance))))

      (let [second-instance
            (cluster/start! {:seon.boot/cluster-name cluster-name
                             :seon.boot/root root})
            connection (:seon.boot/cluster-connection second-instance)]
        (try
          (is (function-present? @connection)
              "the reopened cluster reads the committed definition")
          (d/transact
           connection
           (agent/creation-tx
            {:seon.cluster.agent/id "restart-b"
             :seon.ns/name 'my.agents.restart-b}))
          (with-redefs
            [ai/complete
             (fn [{prompt :seon.ai/prompt}]
               {:seon.ai/text
                (if (str/includes? prompt "You are agent restart-b")
                  (str
                   "(my.agents.restart-a/persisted 41)\n"
                   "(my.run/complete \"restarted definition called\")")
                  "(my.run/complete \"unexpected agent\")")})]
            (await-commit!
             connection
             restarted-call-present?
             #(transact-inbound! connection "restart-b"
                                 "Call the restarted durable function.")))
          (is (restarted-call-present? @connection)
              "a fresh agent ctx acquired and called the prior definition")
          (finally
            (cluster/stop! second-instance))))
      (finally
        (delete-recursively! root)))))
