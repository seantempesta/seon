(ns seon.cluster.program-restart-test
  "Live restart proof for database-backed SCI program acquisition."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.core :as datahike]
            [sci.core :as sci]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.message :as message]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]))

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
                          {:seon.error/kind ::commit-timeout
                           ::receipts
                           (d/q '[:find ?ordinal ?result ?error
                                  :where
                                  [?receipt :seon.cluster.eval/ordinal ?ordinal]
                                  [?receipt :seon.cluster.eval/result-edn ?result]
                                  [(get-else $ ?receipt
                                             :seon.cluster.eval/error nil)
                                   ?error]]
                                @connection)})))
        observed)
      (finally
        (datahike/unlisten! connection listener-key)))))

(defn- program-present?
  [db]
  (and
   (some? (d/pull db [:seon.fn/sym]
                  [:seon.fn/sym "my.agents.restart-a/persisted"]))
   (some? (d/pull db [:seon.schema/key]
                  [:seon.schema/key :my.agents.restart-a/nonnegative]))
   (some? (d/pull db [:seon.test/sym]
                  [:seon.test/sym
                   "my.agents.restart-a/persisted-test"]))))

(defn- receipt-count
  [db]
  (d/q '[:find (count ?receipt) .
         :where [?receipt :seon.cluster.eval/id _]]
       db))

(defn- restarted-call-present?
  [db]
  (boolean
   (d/q '[:find ?receipt .
          :where
          [?agent :seon.cluster.agent/id "restart-b"]
          [?run :seon.cluster.run/agent ?agent]
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/ordinal 0]
          [?receipt :seon.cluster.eval/result-edn "\"OK\""]]
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
                   "(require '[seon.schema :as schema])\n"
                   "(require '[clojure.string :as s1])\n"
                   "(require '[clojure.string :as s2])\n"
                   "(require '[clojure.string :refer [upper-case] "
                   ":rename {upper-case up}])\n"
                   "(require 'clojure.set)\n"
                   "(require '[missing.restart.namespace :as-alias ghost])\n"
                   "(require '[clojure.test :refer [deftest]])\n"
                   "(defn ^{:malli/schema [:=> [:cat :string] :string]} "
                   "persisted [x] (do (s1/lower-case x) "
                   "(s2/lower-case x) (name ::ghost/value) (up x)))\n"
                   "(schema/register! ::nonnegative "
                   "(vector :int {:min 0}))\n"
                   "(deftest persisted-test :reopened)\n"
                   "(my.run/complete \"program committed\")")
                  "(my.run/complete \"unexpected agent\")")})]
            (await-commit!
             connection
             program-present?
             #(transact-inbound! connection "restart-a"
                                 "Define a durable function, schema, and test.")))
          (is (program-present? @connection))
          (is (pos? (receipt-count @connection)))
          (let [namespace-row
                (d/pull @connection [:seon.ns/requires]
                        [:seon.ns/name 'my.agents.restart-a])]
            (is (some #{'clojure.set} (:seon.ns/requires namespace-row))
                "a plain require remains an exact load dependency")
            (is (not (some #{'missing.restart.namespace}
                           (:seon.ns/requires namespace-row)))
                ":as-alias is not reclassified as a load dependency"))
          (finally
            (cluster/stop! first-instance))))

      (let [second-instance
            (cluster/start! {:seon.boot/cluster-name cluster-name
                             :seon.boot/root root})
            connection (:seon.boot/cluster-connection second-instance)]
        (try
          (is (program-present? @connection)
              "the reopened database retains every current declaration")
          (is (not (contains?
                    (d/pull @connection '[*]
                            [:seon.schema/key
                             :my.agents.restart-a/nonnegative])
                    :seon.schema/ns))
              "the reopened global schema identity has no namespace owner")
          (let [receipts-before-acquire (receipt-count @connection)
                ctx (sci/fork (sci.eval/base))
                acquired
                (sci.eval/acquire!
                 {:seon.sci.eval/ctx ctx
                  :seon.db/db @connection})
                projection (:seon.schema/projection acquired)
                validate-nonnegative
                (schema/projection-validator
                 projection :my.agents.restart-a/nonnegative)]
            (is (= receipts-before-acquire (receipt-count @connection))
                "reopen acquisition materializes current state without replay")
            (is (true? (validate-nonnegative 4)))
            (is (false? (validate-nonnegative -1))
                "the reopened schema is active in the fresh projection")
            (is (= "OK"
                   (sci/eval-string*
                    ctx "(my.agents.restart-a/persisted \"ok\")"))
                "the reopened function is installed in the fresh ctx")
            (let [{:keys [aliases refers] :as bindings}
                  (sci/namespace-bindings ctx 'my.agents.restart-a)]
              (is (= 'clojure.string (get aliases 's1)))
              (is (= 'clojure.string (get aliases 's2))
                  "multiple aliases to one target survive acquisition")
              (is (= 'missing.restart.namespace (get aliases 'ghost))
                  ":as-alias survives without loading its target")
              (is (= 'clojure.string/upper-case (get refers 'up))
                  "a renamed refer retains its target Var identity")
              (is (some #{'clojure.set} (:requires bindings)))
              (is (not (some #{'missing.restart.namespace}
                             (:requires bindings)))))
            (is (= :reopened
                   (sci/eval-string*
                    ctx
                    (str "((:test (meta (resolve "
                         "'my.agents.restart-a/persisted-test))))")))
                "the reopened test Var's :test function executes"))
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
                   "(my.agents.restart-a/persisted \"ok\")\n"
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
        (test-support/delete-recursively! root)))))
