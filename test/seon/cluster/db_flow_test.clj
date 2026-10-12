(ns seon.cluster.db-flow-test
  "db<->flow's decision, `seon.cluster.agent/plan`, as a table: facts read on
  main and the roster's live graphs in, the actions db<->flow applies out."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.message :as message]
            [seon.cluster.registry :as registry]
            [seon.cluster.squash :as squash]
            [seon.batch]
            [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as flow]
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

(defn- landed!
  "Await the end of `task-name`'s attempt on its own branch, where the agent writes it, then land it on main with
  one db<->flow pass, as the end datom's wake asks the cluster process to; the end."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.db/connection :string :keyword] :seon.task.attempt/end]}
  [handle connection task-name event]
  (let [[[key branch]] (seq (attempts connection task-name))
        on-branch (registry/active-branch-connection {:seon.store/store (agent/handle-store handle) :seon.store/branch branch})
        end (await-on! on-branch #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]
                                        (db/db on-branch) key)
                       event)]
    (pass! handle (:seon.agent/routing handle))
    end))

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
  "Call `body` with the chain world's cluster handle over `connection` once `tasks` (name → needs, proof body and
  optional step kind) are ingested, each with its one proof (none for a nil body: a predecessor writes it), and set
  :running; every graph db<->flow started is released after."
  {:malli/schema [:=> [:cat :seon.db/connection [:map-of :string [:or [:tuple [:set :string] [:maybe :string]]
                                                                   [:tuple [:set :string] [:maybe :string] :seon.task/step]]]
                       [:=> [:cat :seon.agent/context-source] :seon.schema/value]]
                  :seon.schema/value]}
  [connection tasks body]
  (test-support/seed-cluster! connection "tq-chain")
  (task/index-tasks! {:seon.db/connection connection
                      :seon.task/files {(str "resources/seon/tasks/" *space* ".edn") (str "[" (apply str (for [[task-name [needs _ step]] (sort tasks)]
                                                                       (str "#:seon.task{:name \"" task-name "\" :kind :work :intent \"Do " task-name ".\""
                                                                            (when step (str " :step " step))
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
                        (apply str (for [[task-name [_ proof]] (sort tasks) :when proof]
                                     (str "(clojure.test/deftest ^{:seon/tasks #{'" *space* "/" task-name "}} proves-" task-name " "
                                          proof ")\n"))))
  (test-support/transacted! connection (mapv #(vector :db/add [:seon.task/id (chain-handle %)] :seon.task/execution :running)
                                             (keys tasks)))
  (let [environment (test-support/environment "tq-chain" connection)]
    (with-open [launcher (test-support/closeable
                         (flow/start-work-launcher!
                          {:seon.env/environment environment
                           :seon.flow/configuration (select-keys (config/effective (db/db connection) "tq-chain")
                                                                 flow/flow-workload-attributes)})
                         flow/stop-work-launcher!)]
  (let [ctx (test-support/fork-cluster-ctx connection "tq-chain")
        handle (test-support/cluster-handle
                {:seon.env/environment (assoc environment :seon.flow/work-launcher @launcher)
                 :seon.db/connection connection :seon.cluster/name "tq-chain"
                 :seon.sci.eval/ctx ctx
                 ;; the executor each agent's turn bound runs on, as boot's handle carries it
                 :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                 :seon.db.process/id cluster/boot-process-identity})]
    (try (body handle)
         (finally
           (release! (:seon.agent/routing handle))
           (doseq [branch (db/q '[:find [?branch ...] :in $ [?handle ...]
                                  :where [?attempt :seon.task/assigned ?handle]
                                         [?attempt :seon.task/branch ?branch]]
                                (db/db connection) (mapv chain-handle (keys tasks)))]
             (registry/retire-branch! {:seon.store/store (agent/handle-store handle)
                                      :seon.store/branch branch}))))))))

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
              end (landed! handle connection "g" ::g-ended)
              branch-db (db/branch-database (db/db connection) branch)]
          (is (= :done end) "its first pass ran its green proofs and ended it")
          (is (seq (proof-runs branch-db (proof "g"))) "on its own branch")
          (is (zero? (model-turns branch-db agent-id)) "with no model call"))))))))

(deftest ^{:seon/tasks #{'seon.turn/a-closed-attempt-releases-its-connection-before-branch-deletion}}
  an-ended-attempts-closed-merge-releases-its-connection-and-branch
  (doseq [from-attempt? [false true]]
    (binding [*space* (if from-attempt? "tq.retirelocal" "tq.retireroot")]
      (test-support/with-database
       (fn [connection]
         (chain-world
          connection {"g" [#{} "(clojure.test/is (= 1 1))"]}
          (fn [handle]
            (let [routing (:seon.agent/routing handle)
                  store (agent/handle-store handle)]
              (pass! handle routing)
              (let [[[key branch agent-id]] (seq (attempts connection "g"))
                    branch-connection (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})
                    _ (is (= :done (await-on! branch-connection
                                              #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]
                                                     (db/db branch-connection) key)
                                              ::retire-ended)))
                    child (get-in @routing [:seon.agent/children branch])
                    graph (get-in @child [:seon.agent/started agent-id :seon.flow/executor])
                    ;; the end's landing stops the graph and releases its connections; the open merge request keeps the branch
                    _ (pass! handle routing)
                    merge-task (first (db/q '[:find [?merge ...] :in $ ?id
                                              :where [?agent :seon.agent/id ?id] [?work :seon.task/agent ?agent]
                                              [?request :seon.task/of-assignment ?work] [?request :seon.task/merge ?merge]]
                                            (db/db connection) agent-id))
                    _ (is (contains? (registry/roster store) branch) "landing keeps the branch for its open merge request")
                    closed (task/close! {:seon.db/connection connection :seon.store/store store
                                         :seon.agent/routing (if from-attempt? child routing)}
                                        merge-task :closed "The lifecycle proof closes this request.")]
                (is (some? branch-connection) "the attempt owned an open connection before closing")
                (is (= :closed (:seon.task/outcome closed)))
                (is (nil? (:seon.task/delete-refusals closed)))
                (is (.isTerminated ^java.util.concurrent.ExecutorService graph) "the actual graph exited")
                (is (= :released @(:wrapped-atom branch-connection)))
                (is (nil? (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})))
                (is (not (contains? (registry/roster store) branch)))
                (is (empty? (db/q '[:find ?error :where [?error :seon.error/signature]] (db/db connection)))))))))))))

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
       ;; A's hand-written end has no merge request, so landing it would delete A's branch before B forks from
       ;; its end commit: the dial keeps it, as an open merge request does in the real chain
       (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                       :seon.config/manifest {:seon.config.agent/keep-ended-branches? true}})
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

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt
                        'seon.turn/a-merge-request-comes-only-from-a-step-no-task-needs}}
  a-green-first-step-ends-itself-and-its-end-starts-the-next-from-its-commit
  (binding [*space* "tq.chain"]
  (test-support/with-database
   (fn [connection]
     (chain-world
      connection {"a" [#{} "(clojure.test/is (= 1 1))"] "b" [#{"a"} "(clojure.test/is (= 1 1))"]}
      (fn [handle]
        (do
          (pass! handle (:seon.agent/routing handle))
          (is (empty? (attempts connection "b")) "B waits on A")
          (is (= :done (landed! handle connection "a" ::a-ended)) "A's green proofs end it, with no model call")
          (is (empty? (db/q '[:find ?r :in $ ?h :where [?a :seon.task/assigned ?h]
                              [?r :seon.task/of-assignment ?a] [?r :seon.task/merge-request _]]
                            (db/db connection) (chain-handle "a")))
              "a step its successor needs records no merge request")
          (pass! handle (:seon.agent/routing handle))
          (let [[[_ branch-b]] (seq (attempts connection "b"))]
            (is (seq (proof-runs-on (db/branch-database (db/db connection) branch-b) (proof "a")))
                "B's branch forked from A's end commit: it holds A's proof run")
            (is (= :done (landed! handle connection "b" ::b-ended)) "and B ends the chain")
            (is (seq (db/q '[:find ?r :in $ ?h :where [?a :seon.task/assigned ?h]
                             [?r :seon.task/of-assignment ?a] [?r :seon.task/merge-request _]]
                           (db/db connection) (chain-handle "b")))
                "the step no task needs records its merge request")))))))))

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
                   end (landed! handle connection "r" ::r-ended)]
               (is (= 1 (count @ledger)) "its red proofs were its work: one model call, with no wake")
               (is (= :sent-back end) "that turn's send-back ended it")
               (is (= {:seon.task/sym (symbol *space* "t")}
                      (:seon.task.attempt/target (db/pull (db/db connection) [{:seon.task.attempt/target [:seon.task/sym]}]
                                                          [:seon.task/assignment key-r])))
                   "naming its target on the attempt")))))))))))

;;; P2: an attempt's end lands on main from its branch, once, and its branch goes or stays.

(defn- eventually
  "Poll `ready?` every 20 ms for at most two seconds; its last answer. Branch deletion is detached io work."
  {:malli/schema [:=> [:cat [:=> [:cat] :seon.schema/value]] :seon.schema/value]}
  [ready?]
  (loop [n 100]
    (let [answer (ready?)]
      (if (or answer (zero? n)) answer (do (Thread/sleep 20) (recur (dec n)))))))

(defn- end-on-branch!
  "Write the attempt of `task-name`'s end `end` on its own branch, as its last transaction does: the
  task's intent refined, a message to root and the end on the attempt. Answers [key branch agent-id attempt]."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.db/connection :string :seon.task.attempt/end]
                  [:tuple :seon.task/assignment :seon.task/branch :seon.agent/id :int]]}
  [handle connection task-name end]
  (let [[[key branch agent-id]] (seq (attempts connection task-name))
        branch-connection (registry/active-branch-connection {:seon.store/store (agent/handle-store handle)
                                                              :seon.store/branch branch})]
    ;; the agent's first pass ran its red proofs: it is idle when its end is written
    (await-on! branch-connection #(not-empty (proof-runs (db/db branch-connection) (proof task-name))) ::proved)
    (test-support/transacted!
     branch-connection
     (into [{:db/id [:seon.task/id (chain-handle task-name)] :seon.task/intent "Refined on the branch."}
            [:db/add [:seon.task/assignment key] :seon.task.attempt/end end]
            [:db/add [:seon.task/assignment key] :seon.task.attempt/commit (db/commit-id (db/db branch-connection))]]
           (concat (when (not= :done end)
                     [[:db/add [:seon.task/assignment key] :seon.task.attempt/reason "The proof asks the impossible."]])
                   (message/send-call (db/db branch-connection)
                                      (message/candidate {:my.message/to "root" :my.message/content "Only on my branch."})
                                      agent-id))))
    [key branch agent-id (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/assignment key]))]))

(defn- copies
  "The end copies on main `database` naming attempt `a`, as transaction ids."
  {:malli/schema [:=> [:cat :seon.db/database-value :int] [:set :int]]}
  [database a]
  (set (db/q '[:find [?tx ...] :in $ ?a :where [?tx :seon.squash/attempt ?a]] database a)))

(deftest ^{:seon/tasks #{'seon.flow/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted}}
  an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted
  (doseq [keep? [false true]]
    (binding [*space* (if keep? "tq.endkeep" "tq.endcopy")]
      (test-support/with-database
       (fn [connection]
         (chain-world
          connection {"e" [#{} "(clojure.test/is (= 1 2))"]}
          (fn [handle]
            (let [routing (:seon.agent/routing handle)
                  store (agent/handle-store handle)]
              (when keep?
                (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                :seon.config/manifest {:seon.config.agent/keep-ended-branches? true}}))
              (pass! handle routing)
              (let [[key branch agent-id a] (end-on-branch! handle connection "e" :done)
                    intent #(:seon.task/intent (db/pull (db/db connection) [:seon.task/intent] [:seon.task/id (chain-handle "e")]))]
                (is (= #{[key branch agent-id :open]} (attempts connection "e")) "before the pass main lacks the end")
                (pass! handle routing)
                (let [database (db/db connection)
                      [tx :as txs] (seq (copies database a))]
                  (is (= 1 (count txs)) "one end copy")
                  (is (= tx (db/q '[:find ?tx . :in $ ?a :where [?a :seon.task.attempt/end :done ?tx]] database a))
                      "carrying the end")
                  (is (= tx (db/q '[:find ?tx . :in $ ?h :where [?t :seon.task/id ?h] [?t :seon.task/intent "Refined on the branch." ?tx]]
                                  database (chain-handle "e")))
                      "and the refinement")
                  (is (= branch (db/q '[:find ?b . :in $ ?tx :where [?tx :seon.squash/source-branch ?b]] database tx))
                      "with its provenance")
                  (is (empty? (db/q '[:find ?m :where [?m :seon.message/content "Only on my branch."]] database))
                      "and no message")
                  (is (= "Refined on the branch." (intent)))
                  (is (not (contains? (:seon.flow.plan/started (agent/roster-facts routing)) agent-id))
                      "its graph is stopped"))
                (pass! handle routing)
                (is (= 1 (count (copies (db/db connection) a))) "a second pass copies nothing")
                (if keep?
                  (is (contains? (registry/roster store) branch) "the dial keeps the ended branch for audit")
                  (is (eventually #(not (contains? (registry/roster store) branch)))
                      "with no merge request the branch is deleted"))
                (is (empty? (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection)))
                    "with no fault recorded"))))))))))

(deftest ^{:seon/tasks #{'seon.flow/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted}}
  an-error-end-copies-nothing-and-writes-roots-problem-task
  (binding [*space* "tq.enderror"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"e" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                intent #(:seon.task/intent (db/pull (db/db connection) [:seon.task/intent] [:seon.task/id (chain-handle "e")]))]
            (pass! handle routing)
            (let [[key branch _ a] (end-on-branch! handle connection "e" :given-back)
                  problem #(db/pull (db/db connection) [:seon.task/intent]
                                    [:seon.task/sym (symbol "seon.task" (str "attempt-ended-" key))])]
              (pass! handle routing)
              (is (= #{[key branch (str "attempt/" key) :given-back]} (attempts connection "e")) "main holds the end")
              (is (= "Do e." (intent)) "and no refinement")
              (is (contains? (registry/roster store) branch) "the branch stays")
              (is (str/includes? (str (:seon.task/intent (problem))) (name branch))
                  "root's problem task names the branch")
              (is (str/includes? (str (:seon.task/intent (problem))) "The proof asks the impossible.")
                  "and the reason")
              (pass! handle routing)
              (is (= 1 (count (copies (db/db connection) a))) "a second pass writes nothing")
              (testing "root's by-hand copy lands what the success path would"
                (let [copied (squash/squash-merge! {:seon.db/connection connection} branch nil)]
                  (is (pos? (:seon.squash/datoms copied)))
                  (is (= "Refined on the branch." (intent)))
                  (is (zero? (:seon.squash/datoms (squash/squash-merge! {:seon.db/connection connection} branch nil)))
                      "and a second copy writes nothing new")))))))))))

;;; P4: an attempt's world holds its own assignment, so nothing it reads reaches main.

(defn- row-ids
  "The entity ids of attempt `key`'s row and its agent on `database`, or nil where absent."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment :seon.agent/id] [:tuple [:maybe :int] [:maybe :int]]]}
  [database key agent-id]
  [(:db/id (db/pull database [:db/id] [:seon.task/assignment key]))
   (:db/id (db/pull database [:db/id] [:seon.agent/id agent-id]))])

(deftest ^{:seon/tasks #{'seon.task/a-refinement-on-a-branch-reaches-main-only-at-the-end}}
  an-attempts-branch-holds-its-attempt-and-agent-under-mains-ids
  (binding [*space* "tq.seedrows"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"a" [#{} "(clojure.test/is (= 1 2))"] "b" [#{"a"} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)]
            (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                            :seon.config/manifest {:seon.config.agent/keep-ended-branches? true}})
            (pass! handle routing)
            (let [[[key-a branch-a agent-a]] (seq (attempts connection "a"))
                  a-db #(db/branch-database (db/db connection) branch-a)]
              (is (= (row-ids (db/db connection) key-a agent-a) (row-ids (a-db) key-a agent-a))
                  "a fork of main after the attempt write inherits its row and agent")
              (test-support/transacted! connection
                                        [[:db/add [:seon.task/assignment key-a] :seon.task.attempt/end :done]
                                         [:db/add [:seon.task/assignment key-a] :seon.task.attempt/commit
                                          (db/commit-id (a-db))]])
              (pass! handle routing)
              (let [[[key-b branch-b agent-b]] (seq (attempts connection "b"))
                    b-db (db/branch-database (db/db connection) branch-b)
                    [row agent :as ids] (row-ids b-db key-b agent-b)]
                (is (and (int? row) (int? agent)) "a fork of its need's end commit is given its row and agent")
                (is (= (row-ids (db/db connection) key-b agent-b) ids) "under main's entity ids")
                (is (= (chain-handle "b") (when (int? row)
                                                    (get-in (db/pull b-db [{:seon.task/attempt-of [:seon.task/id]}] row)
                                                            [:seon.task/attempt-of :seon.task/id])))
                    "naming its task")
                (is (empty? (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection)))
                    "with no fault recorded"))))))))))

(deftest ^{:seon/tasks #{'seon.task/a-refinement-on-a-branch-reaches-main-only-at-the-end}}
  a-hand-assignment-writes-the-attempt-on-the-agents-own-branch
  (binding [*space* "tq.handrows"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"h" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [agent-id "tq-hand-agent"
                branch :tq-hand-branch]
            (test-support/transacted! connection (agent/creation-tx {:seon.agent/id agent-id :seon.ns/name 'user
                                                                     :seon.cluster/name "tq-chain"
                                                                     :seon.agent/branch branch}))
            (registry/fork! {:seon.store/store (agent/handle-store handle) :seon.store/branch branch
                             :seon.cluster.registry/from (get-in (db/db connection) [:config :branch])
                             :seon.db/db (db/db connection)})
            (let [execution (agent/acquire-context! handle nil {:seon.agent/branch branch})
                  assigned (task/assign! {:seon.db/connection connection :seon.task/assigned (chain-handle "h")
                                          :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})
                  key (:seon.task/assignment assigned)]
              (try
                (is (string? key) "root assigns the task on main")
                (is (= (row-ids (db/db connection) key agent-id) (row-ids (db/db (:seon.db/connection execution)) key agent-id))
                    "and the agent's branch holds the same attempt and agent")
                (is (= (chain-handle "h") (task/agent-task (db/db (:seon.db/connection execution)) agent-id))
                    "so the agent reads its task in its own world")
                (finally (agent/release-context! execution)
                         (registry/retire-branch! {:seon.store/store (agent/handle-store handle)
                                                   :seon.store/branch branch})))))))))))

(deftest ^{:seon/tasks #{'seon.task/a-refinement-on-a-branch-reaches-main-only-at-the-end}}
  a-green-end-is-written-on-its-branch-and-reaches-main-only-by-its-copy
  (binding [*space* "tq.endcustody"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"g" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)]
            (pass! handle routing)
            (let [[[key branch agent-id]] (seq (attempts connection "g"))
                  a (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/assignment key]))
                  child (get-in @routing [:seon.agent/children branch])
                  graph (get-in @child [:seon.agent/started agent-id :seon.flow/executor])
                  _ (landed! handle connection "g" ::g-ended)
                  copy (first (copies (db/db connection) a))
                  database (db/db connection)]
              (is (= copy (db/q '[:find ?tx . :in $ ?a :where [?a :seon.task.attempt/end :done ?tx]] database a))
                  "the end reached main in its copy, never written there by the agent")
              (is (= copy (db/q '[:find ?tx . :in $ ?a :where [?r :seon.task/of-assignment ?a] [?r :seon.task/merge-request _ ?tx]]
                                database a))
                  "and its merge request with it")
              (is (empty? (db/q '[:find ?m :in $ ?id :where [?g :seon.agent/id ?id] [?m :seon.message/from ?g]] database agent-id))
                  "with no message from the agent on main")
              (is (= :done (db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]
                                 (db/branch-database database branch) key))
                  "the end lives on the agent's branch, kept for its open merge request")
              (is (.isTerminated ^java.util.concurrent.ExecutorService graph) "its graph was stopped and joined")
              (is (empty? (db/q '[:find ?e :where [?e :seon.cluster.eval/interrupted-at _]] (db/branch-database database branch)))
                  "without interrupting the pass that wrote the end")
              (is (empty? (db/q '[:find ?e :where [?e :seon.error/signature]] database))
                  "with no fault recorded")))))))))

(deftest ^{:seon/tasks #{'seon.task/a-refinement-on-a-branch-reaches-main-only-at-the-end}}
  a-refinement-on-a-branch-reaches-main-only-at-the-end
  (binding [*space* "tq.refine"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"r" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                h (chain-handle "r")
                declaration (fn [database] (#'task/declared (db/carried-projection database) (db/pull database '[*] [:seon.task/id h])))
                refine! (fn [conn agent-id intent]
                          (task/create! {:seon.db/connection conn :seon.agent/id agent-id :seon.task/ns (symbol *space*)
                                         :seon.task/name "r" :seon.task/kind :work :seon.task/intent intent
                                         :seon.task/basis {h (declaration (db/db conn))}}))
                intent #(:seon.task/intent (db/pull (db/db connection) [:seon.task/intent] [:seon.task/id h]))
                child #(db/pull (db/db connection) [:seon.task/intent] [:seon.task/sym (symbol *space* "rc")])
                problems #(set (db/q '[:find [?s ...] :where [?t :seon.task/sym ?s] [(namespace ?s) ?n] [(= ?n "seon.task")]
                                       [(str ?s) ?text] [(clojure.string/includes? ?text "squash-conflict")]]
                                     (db/db connection)))]
            (pass! handle routing)
            (let [[[key branch agent-id]] (seq (attempts connection "r"))
                  on-branch (registry/active-branch-connection {:seon.store/store (agent/handle-store handle)
                                                                :seon.store/branch branch})]
              (await-on! on-branch #(not-empty (proof-runs (db/db on-branch) (proof "r"))) ::proved)
              (is (true? (:seon.task/changed? (refine! on-branch agent-id "Refined by the agent."))) "the agent refines its task")
              (is (true? (:seon.task/changed? (task/create! {:seon.db/connection on-branch :seon.agent/id agent-id
                                                             :seon.task/ns (symbol *space*) :seon.task/name "rc"
                                                             :seon.task/kind :work :seon.task/intent "A child of mine."}))))
              (is (= "Do r." (intent)) "main's intent is unchanged while the attempt runs")
              (is (nil? (:seon.task/intent (child))) "and main holds no child task yet")
              (is (true? (:seon.task/changed? (refine! connection "root" "Refined by root."))) "root edits the same intent on main")
              (test-support/transacted! on-branch
                                        [[:db/add [:seon.task/assignment key] :seon.task.attempt/end :done]
                                         [:db/add [:seon.task/assignment key] :seon.task.attempt/commit (db/commit-id (db/db on-branch))]])
              (pass! handle routing)
              (is (= "Refined by root." (intent)) "after the end copy main keeps root's concurrent edit")
              (is (= 1 (count (problems))) "and writes exactly one problem task for it")
              (is (= "A child of mine." (:seon.task/intent (child))) "the agent's child task reached main with the end")))))))))

;;; P7: a message travels on the recipient's inbox channel and is written once, by the recipient, on its own branch.

(deftest ^{:seon/tasks #{'seon.cluster.agent/a-received-message-is-written-on-the-recipients-branch-and-rendered-in-its-context}}
  a-received-message-is-written-on-the-recipients-branch-and-rendered-in-its-context
  (binding [*space* "tq.inbox"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"m" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)]
            (pass! handle routing)
            (let [[[_ branch agent-id]] (seq (attempts connection "m"))
                  on-branch (registry/active-branch-connection {:seon.store/store (agent/handle-store handle)
                                                                :seon.store/branch branch})
                  _ (await-on! on-branch #(not-empty (proof-runs (db/db on-branch) (proof "m"))) ::proved)
                  held (fn [conn id] (db/q '[:find ?m . :in $ ?id :where [?m :seon.message/id ?id]] (db/db conn) id))
                  send! #(message/send! {:my.message/to agent-id :my.message/content % :seon.agent/routing routing}
                                        connection "root")
                  first-sent (send! "Look at the proof.")
                  id (:seon.message/id first-sent)
                  _ (when id (await-on! on-branch #(held on-branch id) ::received))
                  started (System/nanoTime)
                  warm (send! "And again.")
                  _ (await-on! on-branch #(held on-branch (:seon.message/id warm)) ::received-warm)
                  elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
              (is (= {:seon.message/accepted true} (dissoc first-sent :seon.message/id)) "the send is an accepted offer")
              (is (some? (held on-branch id)) "the recipient's branch holds the message by identity")
              (is (nil? (held connection id)) "main does not")
              (is (some #(= id (:my.message/id %))
                        (get-in (seon.context/current-state (db/db on-branch) agent-id) [:seon.context/unread :seon.context/items]))
                  "and the recipient's context shows it")
              (is (< elapsed-ms 30) "offer to write within 30 ms warm")))))))))

;;; A chain step forks its predecessor's end commit, recorded on its attempt; nothing falls back to main.

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt}}
  a-successor-forks-its-predecessors-end-commit-recorded-on-its-attempt
  (binding [*space* "tq.forkpoint"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"a" [#{} "(clojure.test/is (= 1 2))"] "b" [#{"a"} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)]
            (pass! handle routing)
            (let [[[key-a branch-a]] (seq (attempts connection "a"))
                  a-connection (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch-a})]
              (test-support/transacted! a-connection (test-support/agent-tx (db/db a-connection) "tq-fork-marker"))
              (end-on-branch! handle connection "a" :done)
              (pass! handle routing)
              (let [end-commit (:seon.task.attempt/commit (db/pull (db/db connection) [:seon.task.attempt/commit]
                                                                   [:seon.task/assignment key-a]))
                    [[key-b branch-b]] (seq (attempts connection "b"))]
                (is (uuid? end-commit) "A's end landed on main with its commit")
                (is (= end-commit (:seon.task.attempt/fork (db/pull (db/db connection) [:seon.task.attempt/fork]
                                                                    [:seon.task/assignment key-b])))
                    "B's attempt records A's end commit as its fork point")
                (is (some? (db/q '[:find ?a . :where [?a :seon.agent/id "tq-fork-marker"]]
                                 (db/branch-database (db/db connection) branch-b)))
                    "and B's branch forked from it, not from main")
                (pass! handle routing)
                (is (eventually #(not (contains? (registry/roster store) branch-a)))
                    "once B forked, A's kept branch is deleted")
                (is (empty? (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection)))
                    "with no fault recorded"))))))))))

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt}}
  detached-work-without-a-launcher-refuses-and-never-runs-inline
  (binding [*space* "tq.detached"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"d" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [bare (-> handle (dissoc env/state-carrier) (env/carry (dissoc (env/of handle) :seon.flow/work-launcher)))
                ran (promise)
                refused (try (#'agent/detached! bare "tq-detached" #(do (deliver ran true) nil)) nil
                             (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
            (is (= "tq-detached" (:seon.flow/submission-id refused)) "a handle with no launcher refuses the work by name")
            (is (not (realized? ran)) "and never runs it inline"))))))))

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt}}
  a-stopped-attempt-finishes-its-current-pass
  (binding [*space* "tq.stopfinish"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"s" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)]
            (pass! handle routing)
            ;; the attempt's first pass runs its proofs now; stopping meanwhile lets that pass finish
            (test-support/transacted! connection [[:db/add [:seon.task/id (chain-handle "s")] :seon.task/execution :stopped]])
            (pass! handle routing)
            (let [[[_ branch agent-id]] (seq (attempts connection "s"))
                  database (db/branch-database (db/db connection) branch)]
              (is (not (contains? (:seon.flow.plan/started (agent/roster-facts routing)) agent-id)) "the graph is stopped")
              (is (seq (proof-runs database (proof "s"))) "after its pass recorded its proof run")
              (is (empty? (db/q '[:find ?e :where [?e :seon.cluster.eval/interrupted-at _]] database))
                  "with nothing interrupted")))))))))

;;; Chain experiment 1 (docs/research/agent-platform/2026-10-12/chain-experiment-1.md): a Design+Prove step's output
;;; is a red proof for its successor, written on its own branch.

(deftest ^{:seon/tasks #{'seon.context/a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red
                        'seon.turn/a-merge-request-comes-only-from-a-step-no-task-needs}}
  a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red
  ;; p is a :prove step: its own proof is green once a deftest tagged i exists, and that deftest, i's red proof,
  ;; is its product. The one model reply writes it, red as it must be: new at p's fork point, so p broke nothing
  ;; and ends :done.
  (binding [*space* "tq.chainprove"]
  (test-support/with-database
   (fn [connection]
     (let [ledger (atom [])]
       (test-support/with-provider
        ledger (test-support/reply-queue
                [(str "(in-ns 'tq.chainprove-behavior)\n"
                      "(clojure.test/deftest ^{:seon/tasks #{'" *space* "/i}} i-behaves (clojure.test/is (= 1 2)))")])
        (fn [server]
          (chain-world
           connection {"p" [#{} (str "(clojure.test/is (contains? (:seon.task/proofs (seon.task/task-edges (seon.db/db) '"
                                     *space* "/i)) 'tq.chainprove-behavior/i-behaves))")
                            :prove]
                       "i" [#{"p"} nil]}
           (fn [handle]
             (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                             :seon.config/manifest (assoc (test-support/provider-config server) :seon.config.ai/no-provider false)})
             (pass! handle (:seon.agent/routing handle))
             (let [[[_ branch-p]] (seq (attempts connection "p"))
                   p-connection (registry/active-branch-connection {:seon.store/store (agent/handle-store handle)
                                                                    :seon.store/branch branch-p})]
               (is (= {:green 1} (await-on! p-connection
                                            #(let [counts (:seon.context/counts (context/proofs (db/db p-connection) (symbol *space* "p")))]
                                               (when (= {:green 1} counts) counts))
                                            ::p-proof-green))
                   "the model's reply turned p's step proof green on its branch"))
             (is (= :done (landed! handle connection "p" ::p-ended))
                 "its step proof is green and its only red test is its product, new at its fork point")
             (is (empty? (db/q '[:find ?r :in $ ?h :where [?a :seon.task/assigned ?h]
                                 [?r :seon.task/of-assignment ?a] [?r :seon.task/merge-request _]]
                               (db/db connection) (chain-handle "p")))
                 "the Prove predecessor records no merge request")
             (is (= 1 (count @ledger)) "after the one model call that wrote it"))))))))))


(deftest ^{:seon/tasks #{'seon.cluster.agent/a-successor-whose-proof-its-predecessor-wrote-starts}}
  a-successor-whose-proof-its-predecessor-wrote-starts
  ;; i's only proof is written on a's branch before a ends :done at that commit; i forks from that commit, where its
  ;; proof is, so i is startable there: it gets its attempt and no :invalid-task problem is written on main.
  (binding [*space* "tq.chainsucc"]
  (test-support/with-database
   (fn [connection]
     (chain-world
      connection {"a" [#{} "(clojure.test/is (= 1 2))"] "i" [#{"a"} nil]}
      (fn [handle]
        (let [routing (:seon.agent/routing handle)]
          (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                          :seon.config/manifest {:seon.config.agent/keep-ended-branches? true}})
          (pass! handle routing)
          (let [[[key-a branch-a]] (seq (attempts connection "a"))
                a-connection (registry/active-branch-connection {:seon.store/store (agent/handle-store handle)
                                                                 :seon.store/branch branch-a})]
            (await-on! a-connection #(not-empty (proof-runs (db/db a-connection) (proof "a"))) ::a-proved)
            ;; a's work: i's red proof, defined on a's branch as a's agent would
            (test-support/define! (world/world {:seon.db/connection a-connection :seon.boot/cluster-name "tq-chain"
                                                :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db a-connection))
                                                                                              (db/db a-connection) a-connection)
                                                :seon.batch/sink (seon.batch/sink)
                                                :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                                :seon.agent/id "tq-chain-author"})
                                  'tq.chainsucc-behavior
                                  (str "(clojure.test/deftest ^{:seon/tasks #{'" *space* "/i}} i-behaves (clojure.test/is (= 1 2)))"))
            (test-support/transacted! connection
                                      [[:db/add [:seon.task/assignment key-a] :seon.task.attempt/end :done]
                                       [:db/add [:seon.task/assignment key-a] :seon.task.attempt/commit
                                        (db/commit-id (db/db a-connection))]])
            (pass! handle routing)
            (is (= 1 (count (attempts connection "i"))) "a's green end starts i from a's commit")
            (is (empty? (db/q '[:find ?t :where [?t :seon.task/name ?n] [(clojure.string/starts-with? ?n "invalid-task")]]
                              (db/db connection)))
                "and writes no :invalid-task problem for it")))))))))

(deftest ^{:seon/tasks #{'seon.cluster.agent/a-successor-whose-proof-its-predecessor-wrote-starts}}
  admission-reads-a-prove-steps-proof-or-successor-declaration
  (binding [*space* "tq.admission"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"p" [#{} "(clojure.test/is (= 1 2))" :prove]
                    "q" [#{} nil :prove] "i" [#{"q"} "(clojure.test/is (= 1 2))"]
                    "r" [#{} nil :prove]
                    "s" [#{} nil] "j" [#{"s"} "(clojure.test/is (= 1 2))"]}
        (fn [_]
          (let [observed (agent/plan-facts (db/db connection))]
            (is (= (set (map chain-handle ["p" "q"])) (:seon.flow.plan/startable observed))
                "Prove starts with own proofs or a successor declaration")
            (is (= (set (map chain-handle ["r" "s"])) (:seon.flow.plan/invalid observed))
                "Prove without either and Implement without proofs cannot start"))))))))
