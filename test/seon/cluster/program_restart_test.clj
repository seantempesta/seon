(ns ^{:seon.test/long "The namespace proves program acquisition across a real cluster restart."}
  seon.cluster.program-restart-test
  "Live restart proof for database-backed SCI program acquisition."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [datahike.core :as datahike]
            [sci.core :as sci]
            [seon.ai :as ai]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.message :as message]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.test-support :as test-support]))

(defn- transact-inbound!
  [connection agent-id content]
  (db/transact!
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
                           (mapv #(db/pull @connection
                                          [:seon.cluster.eval/ordinal
                                           :seon.cluster.eval/result-edn
                                           :seon.cluster.eval/error]
                                          %)
                                 (db/q '[:find [?receipt ...]
                                        :where
                                        [?receipt :seon.cluster.eval/ordinal _]]
                                      @connection))})))
        observed)
      (finally
        (datahike/unlisten! connection listener-key)))))

(defn- program-present?
  [db]
  (and
   (some? (db/pull db [:seon.fn/sym]
                  [:seon.fn/sym "my.agents.restart-a/persisted"]))
   (some? (db/pull db [:seon.schema/key]
                  [:seon.schema/key :my.agents.restart-a/nonnegative]))
   (some? (db/pull db [:seon.test/sym]
                  [:seon.test/sym
                   "my.agents.restart-a/persisted-test"]))))

(defn- authored-receipt-count
  [db agent-id]
  (db/q '[:find (count ?receipt) .
         :in $ ?agent-id ?bootstrap-run-id
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?run :seon.cluster.run/agent ?agent]
         [?run :seon.cluster.run/id ?run-id]
         [(not= ?run-id ?bootstrap-run-id)]
         [?receipt :seon.cluster.eval/run ?run]]
       db agent-id (bootstrap/run-id agent-id)))

(defn- semantic-result
  [result-edn]
  (#'admit/semantic-value (edn/read-string result-edn)))

(defn- authored-result
  [db agent-id ordinal]
  (db/q '[:find ?result .
         :in $ ?agent-id ?bootstrap-run-id ?ordinal
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?run :seon.cluster.run/agent ?agent]
         [?run :seon.cluster.run/id ?run-id]
         [(not= ?run-id ?bootstrap-run-id)]
         [?receipt :seon.cluster.eval/run ?run]
         [?receipt :seon.cluster.eval/ordinal ?ordinal]
         [?receipt :seon.cluster.eval/result-edn ?result]]
       db agent-id (bootstrap/run-id agent-id) ordinal))

(defn- active-agent-id
  "The agent whose durable run is currently open."
  [db]
  (db/q '[:find ?agent-id .
         :where
         [?run :seon.cluster.run/agent ?agent]
         [?agent :seon.cluster.agent/id ?agent-id]
         (not [?run :seon.cluster.run/closed-at _])]
       db))

(defn- agent-open-run?
  [db agent-id]
  (boolean
   (db/q '[:find ?run .
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          [?run :seon.cluster.run/agent ?agent]
          (not [?run :seon.cluster.run/closed-at _])]
        db agent-id)))

(defn- agent-authored-closed-run-count
  [db agent-id]
  (db/q '[:find (count ?run) .
         :in $ ?agent-id ?bootstrap-run-id
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?run :seon.cluster.run/agent ?agent]
         [?run :seon.cluster.run/id ?run-id]
         [(not= ?run-id ?bootstrap-run-id)]
         [?run :seon.cluster.run/closed-at _]]
       db agent-id (bootstrap/run-id agent-id)))

(defn- await-bootstrap!
  [connection agent-id]
  (await-commit!
   connection
   (fn [db]
     (:seon.cluster.run/closed-at
      (db/pull db [:seon.cluster.run/closed-at]
              [:seon.cluster.run/id (bootstrap/run-id agent-id)])))
   (constantly nil)))

(defn- authored-program-settled?
  "The complete first-agent plan committed, including its final disposition."
  [db]
  (and (program-present? db)
       (= 21 (authored-receipt-count db "restart-a"))
       (= 1 (agent-authored-closed-run-count db "restart-a"))
       (not (agent-open-run? db "restart-a"))))

(defn- restarted-call-present?
  [db]
  (boolean
   (some #{"OK"}
         (map semantic-result
              (db/q '[:find [?result ...]
                     :where
                     [?agent :seon.cluster.agent/id "restart-b"]
                     [?run :seon.cluster.run/agent ?agent]
                     [?receipt :seon.cluster.eval/run ?run]
                     [?receipt :seon.cluster.eval/ordinal 0]
                     [?receipt :seon.cluster.eval/result-edn ?result]]
                   db)))))

(deftest an-agent-definition-survives-restart-and-another-agent-calls-it
  (let [root (str "tmp/program-restart-test/" (random-uuid))
        cluster-name (str "program-restart-" (random-uuid))]
    (.mkdirs (io/file root))
    (try
      (cluster/refresh-source! root)
      (let [first-instance
            (cluster/start! {:seon.boot/cluster-name cluster-name
                             :seon.boot/root root})
            connection (:seon.boot/cluster-connection first-instance)]
        (try
          (await-bootstrap! connection "root")
          (cluster/ensure-entity!
           connection
           (get-in first-instance
                   [:seon.cluster.loop/cluster
                    :seon.cluster.run/process])
           {:seon.cluster.agent/id "restart-a"
            :seon.cluster/name cluster-name
            :seon.ns/name 'my.agents.restart-a})
          (await-bootstrap! connection "restart-a")
          (with-redefs
            [ai/complete
             (fn [_]
               {:seon.ai/text
                (if (= "restart-a" (active-agent-id @connection))
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
                     "(defn scratch [x] (inc x))\n"
                     "(scratch 3)\n"
                     "(defn- scratch-private [x] (inc x))\n"
                     "(scratch-private 4)\n"
                     "(require (if true '[clojure.set :as dynamic] "
                     "'[clojure.string :as dynamic]))\n"
                     "{:resolved ::dynamic/after-require}\n"
                     "(missing-independent-form 4)\n"
                     "(deftest persisted-test :reopened)\n"
                     "(clojure.core/ns-unmap *ns* (symbol \"String\"))\n"
                     "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                     "removed-before-restart [x] (inc x))\n"
                     "(clojure.core/ns-unmap "
                     "(find-ns 'my.agents.restart-a) "
                     "(symbol \"removed-before-restart\"))\n"
                     "(my.run/complete \"program committed\")")
                  "(my.run/complete \"unexpected agent\")")})]
            (await-commit!
             connection
             authored-program-settled?
             #(transact-inbound! connection "restart-a"
                                 "Define a durable function, schema, and test.")))
          (is (program-present? @connection))
          (is (= 21 (authored-receipt-count @connection "restart-a"))
              "twenty valid forms and one independent refusal settle")
          (is (= 1 (agent-authored-closed-run-count
                    @connection "restart-a"))
              "the terminal disposition closes the original run")
          (is (= 4
                 (semantic-result
                  (authored-result @connection "restart-a" 10)))
              "a process-local scratch def is lintable by the next form")
          (is (= 5
                 (semantic-result
                  (authored-result @connection "restart-a" 12)))
              "a private scratch def remains callable inside its namespace")
          (is (nil? (db/pull @connection [:db/id]
                            [:seon.fn/sym
                             "my.agents.restart-a/scratch"]))
              "an uncontracted scratch def remains outside the program graph")
          (is (= {:resolved :clojure.set/after-require}
                 (semantic-result
                  (authored-result @connection "restart-a" 14)))
              "a computed require changes lint and eval state for the next form")
          (is (= :seon.cluster.loop/lint-rejected
                 (:seon.error/kind
                  (semantic-result
                   (authored-result @connection "restart-a" 15))))
              "the invalid ordinal is one flat value, not a plan-wide abort")
          (is (nil? (db/pull @connection [:db/id]
                            [:seon.fn/sym
                             "my.agents.restart-a/removed-before-restart"])))
          (is (pos? (authored-receipt-count @connection "restart-a")))
          (let [namespace-row
                (db/pull @connection
                        [{:seon.ns/requires [:seon.ns/name]}]
                        [:seon.ns/name 'my.agents.restart-a])
                required-names
                (into #{}
                      (map :seon.ns/name)
                      (:seon.ns/requires namespace-row))]
            (is (contains? required-names 'clojure.set)
                "a plain require remains an exact load dependency")
            (is (not (contains? required-names 'missing.restart.namespace))
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
                    (db/pull @connection '[*]
                            [:seon.schema/key
                             :my.agents.restart-a/nonnegative])
                    :seon.schema/ns))
              "the reopened global schema identity has no namespace owner")
          (let [receipts-before-acquire
                (authored-receipt-count @connection "restart-a")
                ctx (:seon.sci.eval/ctx second-instance)
                projection (:seon.schema/projection ctx)
                validate-nonnegative
                (schema/projection-validator
                 projection :my.agents.restart-a/nonnegative)]
            (is (= receipts-before-acquire
                   (authored-receipt-count @connection "restart-a"))
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
              (is (= 'clojure.set (get aliases 'dynamic))
                  "a computed require's alias survives acquisition")
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
                "the reopened test Var's :test function executes")
            (is (nil?
                 (sci/eval-string*
                  ctx
                  "(resolve 'my.agents.restart-a/removed-before-restart)"))
                "a computed qualified ns-unmap survives process restart")
            (is (nil?
                 (:val
                  (sci/eval-string+
                   ctx
                   "(resolve 'String)"
                   {:ns (sci/create-ns 'my.agents.restart-a)})))
                "an import-only ns-unmap survives cluster reopen"))
          (cluster/ensure-entity!
           connection
           (get-in second-instance
                   [:seon.cluster.loop/cluster
                    :seon.cluster.run/process])
           {:seon.cluster.agent/id "restart-b"
            :seon.cluster/name cluster-name
            :seon.ns/name 'my.agents.restart-b})
          (await-bootstrap! connection "restart-b")
          (with-redefs
            [ai/complete
             (fn [_]
               {:seon.ai/text
                (if (= "restart-b" (active-agent-id @connection))
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
