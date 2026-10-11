(ns seon.cluster.db-flow-test
  "db<->flow's decision, `seon.cluster.agent/plan`, as a table: facts read on
  main and the roster's live graphs in, the actions db<->flow applies out."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.batch]
            [seon.config :as config]
            [seon.db :as db]
            [seon.id :as id]
            [seon.sci.eval :as sci.eval]
            [seon.task :as task]
            [seon.test-support :as test-support]
            [seon.turn.world :as world]))

(defn- facts
  "Plan facts over `overrides`: nothing startable, nothing open, a running cluster."
  {:malli/schema [:=> [:cat :map] :seon.flow.plan/facts]}
  [overrides]
  (merge {:seon.flow.plan/startable #{} :seon.flow.plan/invalid #{} :seon.flow.plan/open []
          :seon.flow.plan/ended {} :seon.flow.plan/created {} :seon.flow.plan/basis {}
          :seon.flow.plan/cluster :running}
         overrides))

(defn- roster
  "A roster holding `started` graphs, `paused` among them."
  {:malli/schema [:function [:=> [:cat] :seon.flow.plan/roster]
                  [:=> [:cat :seon.flow.plan/started :seon.flow.plan/paused] :seon.flow.plan/roster]]}
  ([] (roster #{} #{}))
  ([started paused] {:seon.flow.plan/started started :seon.flow.plan/paused paused}))

(def ^:private a "aaaaaaaaaaaa")
(def ^:private b "bbbbbbbbbbbb")
(def ^:private key-a (id/id [a 1]))
(def ^:private agent-a (str "attempt/" key-a))

(defn- open
  "The open attempt 1 of `a` under `intent`."
  {:malli/schema [:=> [:cat :seon.task/execution] :seon.flow.plan/open-attempt]}
  [intent]
  {:seon.task/assigned a :seon.task/assignment key-a :seon.agent/id agent-a
   :seon.task/branch (keyword (str "attempt-" key-a)) :seon.task/execution intent})

(defn- ops
  "The plan's actions as [op task] pairs, in order."
  {:malli/schema [:=> [:cat :seon.flow.plan/actions] [:vector [:tuple :seon.flow.plan/op :seon.task/assigned]]]}
  [actions]
  (mapv (juxt :seon.flow.plan/op :seon.task/assigned) actions))

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt}}
  plan-writes-the-attempt-first-and-makes-flow-follow
  (testing "a fresh leaf: its attempt, then its flow, in one pass"
    (let [[attempt start :as actions] (agent/plan (facts {:seon.flow.plan/startable #{a}}) (roster) 4)]
      (is (= [[:attempt a] [:start-flow a]] (ops actions)))
      (is (= {:seon.task/assignment key-a :seon.task.attempt/number 1 :seon.agent/id agent-a
              :seon.task/branch (keyword (str "attempt-" key-a)) :seon.flow.plan/fork-point :main}
             (select-keys attempt [:seon.task/assignment :seon.task.attempt/number :seon.agent/id
                                   :seon.task/branch :seon.flow.plan/fork-point])))
      (is (= :running (:seon.task/execution start)))))
  (testing "two passes over the same facts plan the same attempt, so the second write is a no-op"
    (is (= (agent/plan (facts {:seon.flow.plan/startable #{a}}) (roster) 4)
           (agent/plan (facts {:seon.flow.plan/startable #{a}}) (roster) 4))))
  (testing "once written and started, nothing"
    (is (= [] (agent/plan (facts {:seon.flow.plan/startable #{a} :seon.flow.plan/open [(open :running)]})
                          (roster #{agent-a} #{}) 4))))
  (testing "a restart: the open attempt with no graph is started again, never attempted again"
    (is (= [[:start-flow a]]
           (ops (agent/plan (facts {:seon.flow.plan/startable #{a} :seon.flow.plan/open [(open :running)]})
                            (roster) 4)))))
  (testing "an attempt after ended ones is numbered after them"
    (is (= (id/id [a 3])
           (:seon.task/assignment (first (agent/plan (facts {:seon.flow.plan/startable #{a}
                                                             :seon.flow.plan/ended {a 2}})
                                                     (roster) 4))))))
  (testing "a successor forks from its predecessor's end commit"
    (let [commit #uuid "00000000-0000-0000-0000-00000000000a"]
      (is (= #{commit}
             (:seon.flow.plan/fork-point
              (first (agent/plan (facts {:seon.flow.plan/startable #{b} :seon.flow.plan/basis {b #{commit}}})
                                 (roster) 4)))))))
  (testing "max-running counts open attempts: ten ready under four start the four oldest"
    (let [handles (mapv #(format "%012d" %) (range 10))
          created (zipmap handles (range 100 0 -10))
          attempts (filter (comp #{:attempt} :seon.flow.plan/op)
                           (agent/plan (facts {:seon.flow.plan/startable (set handles) :seon.flow.plan/created created})
                                       (roster) 4))]
      (is (= (take 4 (reverse handles)) (map :seon.task/assigned attempts)))
      (is (= [] (filter (comp #{:attempt} :seon.flow.plan/op)
                        (agent/plan (facts {:seon.flow.plan/startable (set handles)
                                            :seon.flow.plan/open (mapv #(assoc (open :running) :seon.task/assigned %
                                                                               :seon.agent/id (str "attempt/" %))
                                                                       (take 4 handles))})
                                    (roster (set (map #(str "attempt/" %) (take 4 handles))) #{}) 4))))))
  (testing "an invalid running leaf is a problem, never an attempt"
    (is (= [[:problem b] [:attempt a] [:start-flow a]]
           (ops (agent/plan (facts {:seon.flow.plan/startable #{a} :seon.flow.plan/invalid #{b}}) (roster) 4))))))

(deftest
  each-intent-is-one-flow-action
  (doseq [[intent started paused cluster expected]
          [[:running #{} #{} :running [[:start-flow :running]]]
           [:paused #{} #{} :running [[:start-flow :paused]]]
           [:held #{agent-a} #{} :running [[:pause nil]]]
           [:paused #{agent-a} #{} :running [[:pause nil]]]
           [:paused #{agent-a} #{agent-a} :running []]
           [:running #{agent-a} #{agent-a} :running [[:resume nil]]]
           [:running #{agent-a} #{} :paused [[:pause nil]]]
           [:stopped #{agent-a} #{} :running [[:stop nil]]]
           [:stopped #{} #{} :running []]
           [:cancelled #{agent-a} #{} :running [[:cancel nil]]]]]
    (is (= expected (mapv (juxt :seon.flow.plan/op :seon.task/execution)
                          (agent/plan (facts {:seon.flow.plan/open [(open intent)] :seon.flow.plan/cluster cluster})
                                      (roster started paused) 4)))
        "each intent and roster state plans exactly its Flow verb")))

;;; The two-step chain through the real db<->flow pass on a fixture branch.

(def ^:private ^:dynamic *space*
  "The task namespace of the chain world under test: each test names its own, so no two share proofs."
  "tq.chain")

(defn- proof
  "The proof of the chain world's task `task-name`."
  {:malli/schema [:=> [:cat :string] :qualified-symbol]}
  [task-name]
  (symbol (str *space* "-proofs") (str "proves-" task-name)))

(defn- chain-handle
  "The handle of the chain world's task `task-name`."
  {:malli/schema [:=> [:cat :string] :seon.task/handle]}
  [task-name]
  (task/task-handle (symbol *space*) task-name :work))

(defn- attempts
  "Every attempt row of the chain world's task `task-name` at `connection`'s head: key, branch, agent, end."
  {:malli/schema [:=> [:cat :seon.db/connection :string] [:set [:tuple :seon.task/assignment :seon.task/branch :seon.agent/id :keyword]]]}
  [connection task-name]
  (set (db/q '[:find ?k ?b ?id ?e :in $ ?h
               :where [?a :seon.task/assigned ?h] [?a :seon.task/assignment ?k] [?a :seon.task/branch ?b]
               [?a :seon.task/agent ?g] [?g :seon.agent/id ?id] [(get-else $ ?a :seon.task.attempt/end :open) ?e]]
             (db/db connection) (chain-handle task-name))))

(defn- pass!
  "One db<->flow pass over `handle` and `routing`, as boot invokes it: synchronous."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/routing] :nil]}
  [handle routing]
  (agent/db<->flow {:seon.turn.loop/cluster (assoc handle :seon.agent/routing routing) :seon.agent/routing routing}
                   :seon.flow/plan :seon.agent/test-wake)
  nil)

(defn- release!
  "Stop every graph db<->flow started on an attempt branch under `routing` and release those branch
  executions, as a process exit drops its roster; the fixture's own execution stays."
  {:malli/schema [:=> [:cat :seon.agent/routing] :nil]}
  [routing]
  (doseq [[branch child] (:seon.agent/children @routing)
          :when (str/starts-with? (name branch) "attempt-")]
    (doseq [agent-id (keys (:seon.agent/started @child))]
      (agent/stop-flow! {:seon.agent/id agent-id :seon.agent/routing child}))
    (doseq [execution (vals (:seon.agent/contexts @child))
            :when (or (:seon.agent/owns-connection? execution) (:seon.agent/owns-branch? execution))]
      (agent/release-context! execution)))
  nil)

(defn- await-on!
  "Await `observe` of `connection`'s head answering non-nil, re-read after each transaction; its answer."
  {:malli/schema [:=> [:cat :seon.db/connection [:=> [:cat] :seon.schema/value] :keyword] :seon.schema/value]}
  [connection observe event]
  (let [seen (async/promise-chan)
        key (keyword "seon.cluster.db-flow-test" (str (random-uuid)))
        ;; the writer thread runs the listener: it observes under this test's bindings
        check! (bound-fn [_] (when-some [value (observe)] (async/offer! seen value)))]
    (d/listen connection key check!)
    (try (check! nil)
         (test-support/await-event! seen event)
         (finally (d/unlisten connection key)))))

(defn- model-turns
  "How many model turns `agent-id` took on `database`: turns with a provider reply."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:int {:min 0}]]}
  [database agent-id]
  (or (db/q '[:find (count ?t) . :in $ ?id :where [?a :seon.agent/id ?id] [?t :seon.turn/agent ?a] [?t :seon.turn/reply-size _]]
            database agent-id)
      0))

(defn- proof-runs
  "The test runs of `proof` recorded on `database` destined for its own branch."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol] [:set :string]]}
  [database proof]
  (set (db/q '[:find [?id ...] :in $ ?branch ?proof
               :where [?run :seon.test.run/branch ?branch] [?run :seon.test.run/identities ?proof] [?run :seon.test.run/id ?id]]
             database (get-in database [:config :branch]) proof)))

(defn- proof-runs-on
  "The test runs of `proof` recorded on `database` for any branch."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol] [:set :string]]}
  [database proof]
  (set (db/q '[:find [?id ...] :in $ ?proof :where [?run :seon.test.run/identities ?proof] [?run :seon.test.run/id ?id]]
             database proof)))

(defn- chain-world
  "Call `body` with the chain world's cluster handle over `connection` once `tasks` (name → needs, proof body) are
  ingested, each with its one proof, and set :running; every graph db<->flow started is released after."
  {:malli/schema [:=> [:cat :seon.db/connection [:map-of :string [:tuple [:set :string] :string]]
                       [:=> [:cat :seon.agent/context-source] :seon.schema/value]]
                  :seon.schema/value]}
  [connection tasks body]
  (test-support/seed-cluster! connection "tq-chain")
  (task/index-tasks! {:seon.db/connection connection
                      :seon.task/files {(str "resources/seon/tasks/" *space* ".edn") (str "[" (apply str (for [[task-name [needs _]] (sort tasks)]
                                                                       (str "#:seon.task{:name \"" task-name "\" :kind :work :intent \"Do " task-name ".\""
                                                                            (when (seq needs)
                                                                              (str " :needs #{" (apply str (map #(str "[:seon.task/sym " *space* "/" % "] ") needs)) "}"))
                                                                            "} ")))
                                                        "]")}})
  ;; root, whom an attempt's end tells, homed on this cluster's branch, and the proofs' author
  (test-support/transacted! connection (test-support/agent-tx (db/db connection) "root" 'user))
  (test-support/transacted! connection (test-support/agent-tx (db/db connection) "tq-chain-author"))
  ;; the proofs as an agent defines them: a fresh attempt's world acquires them from the program facts
  (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                      :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                    (db/db connection) connection)
                                      :seon.batch/sink (seon.batch/sink)
                                      :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                      :seon.agent/id "tq-chain-author"})
                        (symbol (str *space* "-proofs"))
                        (apply str (for [[task-name [_ proof]] (sort tasks)]
                                     (str "(clojure.test/deftest ^{:seon/tasks #{'" *space* "/" task-name "}} proves-" task-name " "
                                          proof ")\n"))))
  (test-support/transacted! connection (mapv #(vector :db/add [:seon.task/id (chain-handle %)] :seon.task/execution :running)
                                             (keys tasks)))
  (let [ctx (test-support/fork-cluster-ctx connection "tq-chain")
        handle (test-support/cluster-handle
                {:seon.env/environment (test-support/environment "tq-chain" connection)
                 :seon.db/connection connection :seon.cluster/name "tq-chain"
                 :seon.sci.eval/ctx ctx
                 ;; the executor each agent's turn bound runs on, as boot's handle carries it
                 :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                 :seon.db.process/id cluster/boot-process-identity})]
    (try (body handle)
         (finally (release! (:seon.agent/routing handle))))))

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt}}
  a-fresh-attempt-proves-first-and-green-proofs-end-it-with-no-model-call
  (binding [*space* "tq.chaing"]
  (test-support/with-database
   (fn [connection]
     (chain-world
      connection {"g" [#{} "(clojure.test/is (= 1 1))"]}
      (fn [handle]
        (pass! handle (:seon.agent/routing handle))
        (let [[[_ branch agent-id]] (seq (attempts connection "g"))
              end (await-on! connection #(some (fn [[_ _ _ e]] (when (not= :open e) e)) (attempts connection "g"))
                             ::g-ended)
              branch-db (db/branch-database (db/db connection) branch)]
          (is (= :done end) "its first pass ran its green proofs and ended it")
          (is (seq (proof-runs branch-db (proof "g"))) "on its own branch")
          (is (zero? (model-turns branch-db agent-id)) "with no model call"))))))))

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt}}
  a-chain-starts-its-first-step-and-its-end-starts-the-next-from-its-commit
  (binding [*space* "tq.chain"]
  (test-support/with-database
   (fn [connection]
     (chain-world
      connection {"a" [#{} "(clojure.test/is (= 1 2))"] "b" [#{"a"} "(clojure.test/is (= 1 2))"]}
      (fn [handle]
     (let [routing (:seon.agent/routing handle)
           started (fn [routing] (:seon.flow.plan/started (agent/roster-facts routing)))]
       (try
         (pass! handle routing)
         (let [[[key-a branch-a agent-a]] (seq (attempts connection "a"))]
           (is (= 1 (count (attempts connection "a"))) "setting both :running attempts the first step")
           (is (empty? (attempts connection "b")) "and not the step that needs it")
           (is (= #{agent-a} (started routing)) "the attempt's graph runs")
           (pass! handle routing)
           (is (= #{[key-a branch-a agent-a :open]} (attempts connection "a")) "a second pass writes nothing")
           (is (= #{agent-a} (started routing)) "and starts nothing")
           (let [a-connection (registry/active-branch-connection {:seon.store/store (agent/handle-store handle)
                                                                  :seon.store/branch branch-a})]
             (is (seq (await-on! a-connection #(not-empty (proof-runs (db/db a-connection) (proof "a"))) ::a-proved))
                 "its first pass ran its red proofs on its branch"))
           ;; A's work on its branch, through the connection its execution holds
           (let [a-connection (registry/active-branch-connection {:seon.store/store (agent/handle-store handle)
                                                                  :seon.store/branch branch-a})]
             (test-support/transacted! a-connection (test-support/agent-tx (db/db a-connection) "tq-chain-marker"))
             (test-support/transacted! connection
                                       [[:db/add [:seon.task/assignment key-a] :seon.task.attempt/end :done]
                                        [:db/add [:seon.task/assignment key-a] :seon.task.attempt/commit
                                         (db/commit-id (db/db a-connection))]]))
           (pass! handle routing)
           (let [[[_ branch-b agent-b]] (seq (attempts connection "b"))
                 b-database (db/branch-database (db/db connection) branch-b)]
             (is (some? agent-b) "A's green end starts B")
             (is (some? (db/q '[:find ?a . :where [?a :seon.agent/id "tq-chain-marker"]] b-database))
                 "forked from A's end commit")
             (is (contains? (started routing) agent-b))
             (let [b-connection (registry/active-branch-connection {:seon.store/store (agent/handle-store handle)
                                                                    :seon.store/branch branch-b})]
               (await-on! b-connection #(not-empty (proof-runs (db/db b-connection) (proof "b"))) ::b-proved))
             (release! routing)
             (is (empty? (started routing)) "a restart drops the roster")
             (pass! handle routing)
             (is (= 1 (count (attempts connection "b"))) "the restart writes no attempt")
             (is (contains? (started routing) agent-b) "and starts the open attempt on its branch")
             (is (empty? (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection)))
                 "with no fault recorded")))
         (finally (release! routing))))))))))

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt}}
  a-green-first-step-ends-itself-and-its-end-starts-the-next-from-its-commit
  (binding [*space* "tq.chaingreen"]
  (test-support/with-database
   (fn [connection]
     (chain-world
      connection {"a" [#{} "(clojure.test/is (= 1 1))"] "b" [#{"a"} "(clojure.test/is (= 1 1))"]}
      (fn [handle]
        (let [ended (fn [task-name] #(some (fn [[_ _ _ e]] (when (not= :open e) e)) (attempts connection task-name)))]
          (pass! handle (:seon.agent/routing handle))
          (is (empty? (attempts connection "b")) "B waits on A")
          (is (= :done (await-on! connection (ended "a") ::a-ended)) "A's green proofs end it, with no model call")
          (pass! handle (:seon.agent/routing handle))
          (let [[[_ branch-b]] (seq (attempts connection "b"))]
            (is (seq (proof-runs-on (db/branch-database (db/db connection) branch-b) (proof "a")))
                "B's branch forked from A's end commit: it holds A's proof run")
            (is (= :done (await-on! connection (ended "b") ::b-ended)) "and B ends the chain")))))))))

(deftest ^{:seon/tasks #{'seon.turn/a-fresh-attempt-is-work-without-a-message 'seon.turn/a-send-back-names-its-target-on-the-attempt}}
  a-red-fresh-attempt-takes-one-model-turn-whose-send-back-names-its-target
  (binding [*space* "tq.chainred"]
  (test-support/with-database
   (fn [connection]
     (let [ledger (atom [])]
       (test-support/with-provider
        ledger (test-support/reply-queue [(str "(my.task/send-back! {:my.task/reason \"The proof asserts 1 is 2.\""
                                               " :my.task/target '" *space* "/t})")])
        (fn [server]
          (chain-world
           connection {"r" [#{} "(clojure.test/is (= 1 2))"] "t" [#{"r"} "(clojure.test/is (= 1 2))"]}
           (fn [handle]
             (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                             :seon.config/manifest (assoc (test-support/provider-config server) :seon.config.ai/no-provider false)})
             (pass! handle (:seon.agent/routing handle))
             (let [[[key-r]] (seq (attempts connection "r"))
                   end (await-on! connection #(some (fn [[_ _ _ e]] (when (not= :open e) e)) (attempts connection "r"))
                                  ::r-ended)]
               (is (= 1 (count @ledger)) "its red proofs were its work: one model call, with no wake")
               (is (= :sent-back end) "that turn's send-back ended it")
               (is (= {:seon.task/sym (symbol *space* "t")}
                      (:seon.task.attempt/target (db/pull (db/db connection) [{:seon.task.attempt/target [:seon.task/sym]}]
                                                          [:seon.task/assignment key-r])))
                   "naming its target on the attempt")))))))))))
