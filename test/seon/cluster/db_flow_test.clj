(ns seon.cluster.db-flow-test
  "db<->flow's decision, `seon.cluster.agent/launch-actions`, as a table: facts read on
  main and the registry's live graphs in, the actions db<->flow applies out."
  (:require [clojure.core.async :as async]
            [my.root]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [my.task :as my.task]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.message :as message]
            [seon.cluster.registry :as registry]
            [seon.cluster.wake :as wake]
            [seon.mcp :as mcp]
            [seon.merge :as merge]
            [seon.batch]
            [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.schedule :as schedule]
            [seon.ai :as ai]
            [seon.flow :as flow]
            [seon.fn]
            [seon.program]
            [seon.id :as id]
            [seon.profile :as profile]
            [seon.sci.eval :as sci.eval]
            [seon.task :as task]
            [seon.test]
            [seon.test.runner]
            [seon.test-support :as test-support]
            [seon.turn :as turn]
            [seon.turn.world :as world]))

(defn- facts
  "Plan facts over `overrides`: nothing startable, nothing open, a running cluster."
  {:malli/schema [:=> [:cat :map] :seon.flow.actions/facts]}
  [overrides]
  (merge {:seon.flow.actions/startable #{} :seon.flow.actions/invalid #{} :seon.flow.actions/open []
          :seon.flow.actions/attempts-made {} :seon.flow.actions/relaunch {} :seon.flow.actions/created {}
          :seon.flow.actions/cluster :running}
         overrides))

(defn- plan-registry
  "A registry holding `started` graphs, `paused` among them."
  {:malli/schema [:function [:=> [:cat] :seon.flow.actions/registry]
                  [:=> [:cat :seon.flow.actions/started :seon.flow.actions/paused] :seon.flow.actions/registry]]}
  ([] (plan-registry #{} #{}))
  ([started paused] {:seon.flow.actions/started started :seon.flow.actions/paused paused}))

(def ^:private a "aaaaaaaaaaaa")
(def ^:private b "bbbbbbbbbbbb")
(def ^:private key-a (id/id [a 1]))
(def ^:private agent-a (str "attempt/" key-a))

(defn- open
  "The open attempt 1 of `a` under `intent`."
  {:malli/schema [:=> [:cat :seon.task/execution] :seon.flow.actions/open-attempt]}
  [intent]
  {:seon.task/assigned a :seon.task/assignment key-a :seon.agent/id agent-a
   :seon.task/branch (keyword (str "attempt-" key-a)) :seon.task/execution intent})

(defn- ops
  "The plan's actions as [op task] pairs, in order."
  {:malli/schema [:=> [:cat :seon.flow.actions/actions] [:vector [:tuple :seon.flow.actions/op :seon.task/assigned]]]}
  [actions]
  (mapv (juxt :seon.flow.actions/op :seon.task/assigned) actions))

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt}}
  plan-writes-the-attempt-first-and-makes-flow-follow
  (testing "a fresh leaf: its attempt, then its flow, in one pass"
    (let [[attempt start :as actions] (agent/launch-actions (facts {:seon.flow.actions/startable #{a}}) (plan-registry) 4)]
      (is (= [[:attempt a] [:start-flow a]] (ops actions)))
      (is (= {:seon.task/assignment key-a :seon.task.attempt/number 1 :seon.agent/id agent-a
              :seon.task/branch (keyword (str "attempt-" key-a)) :seon.flow.actions/fork-point :main}
             (select-keys attempt [:seon.task/assignment :seon.task.attempt/number :seon.agent/id
                                   :seon.task/branch :seon.flow.actions/fork-point])))
      (is (= :running (:seon.task/execution start)))))
  (testing "two passes over the same facts plan the same attempt, so the second write is a no-op"
    (is (= (agent/launch-actions (facts {:seon.flow.actions/startable #{a}}) (plan-registry) 4)
           (agent/launch-actions (facts {:seon.flow.actions/startable #{a}}) (plan-registry) 4))))
  (testing "once written and started, nothing"
    (is (= [] (agent/launch-actions (facts {:seon.flow.actions/startable #{a} :seon.flow.actions/open [(open :running)]})
                          (plan-registry #{agent-a} #{}) 4))))
  (testing "a restart: the open attempt with no graph is started again, never attempted again"
    (is (= [[:start-flow a]]
           (ops (agent/launch-actions (facts {:seon.flow.actions/startable #{a} :seon.flow.actions/open [(open :running)]})
                            (plan-registry) 4)))))
  (testing "an attempt after earlier ones is numbered after the attempts made"
    (is (= (id/id [a 3])
           (:seon.task/assignment (first (agent/launch-actions (facts {:seon.flow.actions/startable #{a}
                                                             :seon.flow.actions/attempts-made {a 2}})
                                                     (plan-registry) 4))))))
  (testing "a relaunched leaf's attempt and flow fork its predecessor's end commit"
    (let [commit (random-uuid)]
      (is (= [commit commit]
             (mapv :seon.flow.actions/fork-point (agent/launch-actions (facts {:seon.flow.actions/startable #{a}
                                                                  :seon.flow.actions/relaunch {a commit}})
                                                          (plan-registry) 4))))))
  (testing "max-running counts open attempts: ten ready under four start the four oldest"
    (let [handles (mapv #(format "%012d" %) (range 10))
          created (zipmap handles (range 100 0 -10))
          attempts (filter (comp #{:attempt} :seon.flow.actions/op)
                           (agent/launch-actions (facts {:seon.flow.actions/startable (set handles) :seon.flow.actions/created created})
                                       (plan-registry) 4))]
      (is (= (take 4 (reverse handles)) (map :seon.task/assigned attempts)))
      (is (= [] (filter (comp #{:attempt} :seon.flow.actions/op)
                        (agent/launch-actions (facts {:seon.flow.actions/startable (set handles)
                                            :seon.flow.actions/open (mapv #(assoc (open :running) :seon.task/assigned %
                                                                               :seon.agent/id (str "attempt/" %))
                                                                       (take 4 handles))})
                                    (plan-registry (set (map #(str "attempt/" %) (take 4 handles))) #{}) 4))))))
  (testing "a chain task's attempt and flow live on its chain branch and fork nothing"
    (is (= [[:chain-a :chain-a nil] [:chain-a :chain-a nil]]
           (mapv (juxt :seon.task/branch :seon.chain/branch :seon.flow.actions/fork-point)
                 (agent/launch-actions (facts {:seon.flow.actions/startable #{a} :seon.flow.actions/chains {a :chain-a}})
                                       (plan-registry) 4)))))
  (testing "an invalid running leaf is a problem, never an attempt"
    (is (= [[:problem b] [:attempt a] [:start-flow a]]
           (ops (agent/launch-actions (facts {:seon.flow.actions/startable #{a} :seon.flow.actions/invalid #{b}}) (plan-registry) 4))))))

(deftest a-publication-wakes-the-pass-that-deletes-the-merged-branch
  ;; `seon.merge/publish!` writes :seon.task/git-commit and nothing else after it; the branch's
  ;; :delete-branch is derived by the next pass, so that fact must be one of the wakes that start it
  (test-support/with-database
   (fn [connection]
     (is (contains? (wake/starting-attributes (db/db connection)) :seon.task/git-commit)
         "the publication's own write wakes db<->flow"))))

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
    (is (= expected (mapv (juxt :seon.flow.actions/op :seon.task/execution)
                          (agent/launch-actions (facts {:seon.flow.actions/open [(open intent)] :seon.flow.actions/cluster cluster})
                                      (plan-registry started paused) 4)))
        "each intent and registry state plans exactly its Flow verb")))

(deftest
  each-ended-assignment-derives-its-lifecycle-from-its-facts
  (let [c1 (random-uuid) c2 (random-uuid)
        held (fn [facts] (merge {:db/id 1 :seon.task/assignment key-a :seon.task/assigned a :seon.agent/id agent-a
                                 :seon.task/branch (keyword (str "attempt-" key-a))
                                 :seon.flow.actions/head c1 :seon.flow.actions/started? false :seon.flow.actions/kept? false
                                 :seon.flow.actions/allowance-left 0 :seon.flow.actions/work-left? false :seon.flow.actions/refused? false}
                                facts
                                ;; main merged c1: its history records that merge (`seon.merge/merged?`)
                                {:seon.flow.actions/exited? (not (:seon.flow.actions/started? facts))
                                 :seon.flow.actions/merged? (= c1 (:seon.flow.actions/head facts c1))}))]
    (doseq [[facts expected why]
            [[{:seon.flow.actions/head c2 :seon.flow.actions/started? true} [] "running: no end anywhere"]
             [{:seon.flow.actions/head c2 :seon.flow.actions/started? true :seon.flow.actions/publish? true} [:publish]
              "running, with declarations root merged: they publish"]
             [{:seon.task.attempt/end :done :seon.flow.actions/head c2 :seon.flow.actions/started? true} [:stop]
              "ended on its branch: the graph stops first"]
             [{:seon.task.attempt/end :done :seon.flow.actions/head c2} [:merge] "then, exited, the branch merges"]
             [{:seon.task.attempt/end :done :seon.flow.actions/main-end :done :seon.flow.actions/head c1} [:delete-branch]
              "root merged it: deleted"]
             [{:seon.task.attempt/end :done :seon.flow.actions/main-end :done} [:delete-branch] "merged: deleted"]
             [{:seon.task.attempt/end :done :seon.flow.actions/main-end :done :seon.flow.actions/publish? true} [:publish]
              "merged declarations main has not published: published before the branch is deleted"]
             [{:seon.task.attempt/end :done :seon.flow.actions/main-end :done :seon.flow.actions/kept? true} [] "merged and kept"]
             [{:seon.task.attempt/end :done :seon.flow.actions/main-end :done :seon.flow.actions/head c2} [:merge]
              "written after its merge: merged again"]
             [{:seon.task.attempt/end :sent-back :seon.flow.actions/head c2} [:merge] "a sent-back end merges its facts"]
             [{:seon.task.attempt/end :capped :seon.flow.actions/head c2 :seon.flow.actions/started? true} [:stop]
              "an end that merges nothing stops first"]
             [{:seon.task.attempt/end :capped :seon.flow.actions/head c2} [:problem :close]
              "then, with no allowance, is written on main with root's problem"]
             [{:seon.task.attempt/end :cancelled :seon.flow.actions/head c2} [:close] "a cancelled end closes with nothing for root"]
             [{:seon.task.attempt/end :capped :seon.flow.actions/main-end :capped :seon.flow.actions/head c2} [] "and kept"]
             [{:seon.task.attempt/end :error :seon.flow.actions/main-end :error :seon.flow.actions/head c2} []
              "a failed merge's :error end stops the lifecycle"]]]
      (is (= expected (mapv :seon.flow.actions/op (agent/next-actions [(into {} (remove (comp nil? val)) (held facts))])))
          why))))

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
  (task/task-handle (symbol *space*) task-name))

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
  executions, as a process exit drops its registry; the fixture's own execution stays."
  {:malli/schema [:=> [:cat :seon.agent/routing] :nil]}
  [routing]
  (doseq [[branch child] (:seon.agent/children @routing)
          :when (str/starts-with? (name branch) "attempt-")]
    (doseq [agent-id (keys (:seon.agent/started @child))]
      (agent/stop-flow! {:seon.agent/id agent-id :seon.agent/routing child}))
    (doseq [execution (vals (:seon.agent/contexts @child))
            :when (or (:seon.agent/owns-connection? execution) (:seon.agent/owns-branch? execution))]
      (agent/release-context! execution)))
  ;; root's chain routes hold their branches' connections; a chain branch is retired only after
  (agent/release-chain-routes! routing)
  nil)

(defn- eventually
  "Poll `ready?` every 20 ms for at most two seconds; its last answer. Branch deletion is detached io work."
  {:malli/schema [:=> [:cat [:=> [:cat] :seon.schema/value]] :seon.schema/value]}
  [ready?]
  (loop [n 100]
    (let [answer (ready?)]
      (if (or answer (zero? n)) answer (do (Thread/sleep 20) (recur (dec n)))))))

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
                                                                       (str "#:seon.task{:name \"" task-name "\" :plan \"Do " task-name ".\""
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

(defn- fixed-chain-world
  "Call `body` with the chain world of `tasks` (as `chain-world`), each proof red at its fork point, and a provider
  answering each model turn, in the order of `fixed`, by redefining that task's proof green on the agent's branch:
  each step reaches done through its own loop."
  {:malli/schema [:=> [:cat :seon.db/connection [:vector :string] :map [:=> [:cat :seon.agent/context-source] :seon.schema/value]]
                  :seon.schema/value]}
  [connection fixed tasks body]
  (let [fix #(str "(in-ns '" *space* "-proofs)\n(clojure.test/deftest ^{:seon/tasks #{'" *space* "/" % "}} proves-" %
                  " (clojure.test/is (= 1 1)))")]
    (test-support/with-provider
     (atom []) (test-support/reply-queue (mapv fix fixed))
     (fn [server]
       (chain-world
        connection tasks
        (fn [handle]
          (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                          :seon.config/manifest (assoc (test-support/provider-config server) :seon.config.ai/no-provider false)})
          (body handle)))))))

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt}}
  a-task-green-at-the-base-opens-no-model-turn
  ;; the prove pass runs the proof at the base program: green there, the merge would accept, so no model turn opens
  (binding [*space* "tq.chaing"]
  (test-support/with-database
   (fn [connection]
     (chain-world
      connection {"g" [#{} "(clojure.test/is (= 1 1))"]}
      (fn [handle]
        (pass! handle (:seon.agent/routing handle))
        (let [[[key branch agent-id]] (seq (attempts connection "g"))
              on-branch (registry/active-branch-connection {:seon.store/store (agent/handle-store handle) :seon.store/branch branch})
              _ (await-on! on-branch #(:green (:seon.context/counts (context/proofs (db/db on-branch) (symbol *space* "g")))) ::g-proved)
              branch-db (db/db on-branch)
              verdict (context/step-verdict branch-db agent-id)]
          (is (= :done (:seon.task/status verdict)) "its proof green at the base: its merge would accept as it stands")
          (is (not= :open (:seon.turn.work/situation (turn/next-agent-work branch-db {:seon.agent/id agent-id})))
              "no model turn opens: a done step's only work is the prove pass whose close ends it")
          (is (zero? (model-turns branch-db agent-id)) "none was taken")
          (is (= :done (await-on! on-branch #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]
                                                         (db/db on-branch) key)
                                       ::g-ended))
              "it ends done, a proposal root's merge decides"))))))))

(deftest root-starts-a-chains-first-task-on-its-branch
  ;; TASK-CHAIN S3a-1 (`docs/seon/schedule/cards/TASK-CHAIN.md#2-flow`, Run a task): after `seon.task/chain!` alone,
  ;; root's pass writes the first task's attempt and agent on the chain branch and starts its graph there; main
  ;; holds neither, the held second task waits, and main's unrelated :running task is never the chain's work
  (binding [*space* "tq.chainrun"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "c")}}
                                    {:seon.task/ns (symbol *space*) :seon.task/name "ignored"}])
                branch (:seon.chain/branch chain)
                [first-task second-task] (:seon.task/handles chain)
                key (id/id [first-task 1])
                agent-id (str "attempt/" key)
                rows (fn [database] (set (db/q '[:find ?h ?b ?id :in $ [?h ...]
                                                 :where [?a :seon.task/assigned ?h] [?a :seon.task/branch ?b]
                                                 [?a :seon.task/agent ?g] [?g :seon.agent/id ?id]]
                                               database [first-task second-task (chain-handle "u")])))
                on-chain #(set (filter (comp #{branch} second) (rows (db/branch-database (db/db connection) branch))))]
            (try
              (pass! handle routing)
              (pass! handle routing)
              (is (= #{[first-task branch agent-id]} (on-chain))
                  "the first task's one attempt and its agent are on the chain branch, written once over two passes")
              (is (= branch (:seon.agent/branch (db/pull (db/branch-database (db/db connection) branch)
                                                         [:seon.agent/branch] [:seon.agent/id agent-id])))
                  "its agent is homed on the chain branch")
              (is (some? (some-> (get-in @routing [:seon.agent/children branch]) deref (get-in [:seon.agent/started agent-id])))
                  "its graph runs on the chain branch")
              (let [main (db/db connection)]
                (is (empty? (filter (comp #{first-task second-task} first) (rows main))) "main holds no chain attempt")
                (is (nil? (db/pull main [:db/id] [:seon.agent/id agent-id])) "main holds no chain agent")
                (is (every? #(not= branch (second %)) (rows main))
                    "main's unrelated :running task is not launched on the chain branch"))
              (finally
                (when-let [child (get-in @routing [:seon.agent/children branch])]
                  (doseq [started (keys (:seon.agent/started @child))]
                    (agent/stop-flow! {:seon.agent/id started :seon.agent/routing child}))
                  (doseq [execution (vals (:seon.agent/contexts @child))
                          :when (or (:seon.agent/owns-connection? execution) (:seon.agent/owns-branch? execution))]
                    (agent/release-context! execution)))
                (agent/release-chain-route! routing branch)
                (registry/retire-branch! {:seon.store/store store :seon.store/branch branch}))))))))))

(deftest ^{:seon.test/long "One chain world: a chain fork, its first attempt's prove pass and one scripted model turn over three db<->flow passes, then a green chain's prove pass ending it done; measured 10.5 s for the first part." :seon.test/long-ms 30000}
  a-chain-agent-makes-its-first-model-call-with-no-wake
  ;; TASK-CHAIN S3a-2 (`docs/seon/schedule/cards/TASK-CHAIN.md#2-flow`, Work): a chain agent starts with no wake; it
  ;; reads its task's execution on its own branch and its proofs from the task's field, so its red proof opens its
  ;; first model turn: the scripted model is called exactly once over repeated passes
  (binding [*space* "tq.chainfirst"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                              :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                            (db/db connection) connection)
                                              :seon.batch/sink (seon.batch/sink)
                                              :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                              :seon.agent/id "tq-chain-author"})
                                (symbol (str *space* "-proofs"))
                                "(clojure.test/deftest proves-c (clojure.test/is (= 1 2)))\n")
          (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                          :seon.config/manifest {:seon.config.ai/no-provider false :seon.config.ai/no-auth true
                                                 :seon.config.ai/endpoint "http://127.0.0.1:9/chat"
                                                 :seon.config.ai/model "fixture-provider"}})
          (let [calls (atom 0)
                handle (assoc handle :seon.turn.loop/complete (fn [_projection _request]
                                                                (swap! calls inc)
                                                                {:seon.ai/text "(my.turn/wait {:my.turn/note \"Waiting for root.\"})"}))
                routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "c")}}])
                branch (:seon.chain/branch chain)
                agent-id (str "attempt/" (id/id [(first (:seon.task/handles chain)) 1]))]
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                (await-on! on-branch #(when (pos? (model-turns (db/db on-branch) agent-id)) true) ::first-model-turn)
                (pass! handle routing)
                (pass! handle routing)
                (is (= 1 @calls) "the scripted model is called once")
                (is (= 1 (model-turns (db/db on-branch) agent-id)) "the agent took one model turn on the chain branch")
                (is (empty? (db/q '[:find [?m ...] :in $ ?id :where [?g :seon.agent/id ?id] [?m :seon.message/to ?g]]
                                  (db/db on-branch) agent-id))
                    "with no wake addressed to it"))
              ;; TASK-CHAIN §2.3: a chain task whose proofs are all green at its start ends :done with no model call
              (let [green (task/chain! {:seon.db/connection connection :seon.store/store store}
                                       [{:seon.task/ns (symbol *space*) :seon.task/name "g" :seon.task/plan "Do g."
                                         :seon.task/proofs #{(proof "u")}}])
                    green-branch (:seon.chain/branch green)
                    key (id/id [(first (:seon.task/handles green)) 1])
                    green-agent (str "attempt/" key)]
                (try
                  (pass! handle routing)
                  (let [on-green (registry/active-branch-connection {:seon.store/store store :seon.store/branch green-branch})]
                    (is (= :done (await-on! on-green #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                                 [?a :seon.task.attempt/end ?e]]
                                                               (db/db on-green) key)
                                            ::green-ended))
                        "a chain task green at its start ends done")
                    (is (zero? (model-turns (db/db on-green) green-agent)) "with no model turn")
                    (is (= 1 @calls) "and no model call"))
                  (finally
                    (when-let [child (get-in @routing [:seon.agent/children green-branch])]
                      (doseq [started (keys (:seon.agent/started @child))]
                        (agent/stop-flow! {:seon.agent/id started :seon.agent/routing child}))
                      (doseq [execution (vals (:seon.agent/contexts @child))
                              :when (or (:seon.agent/owns-connection? execution) (:seon.agent/owns-branch? execution))]
                        (agent/release-context! execution)))
                    (agent/release-chain-route! routing green-branch)
                    (registry/retire-branch! {:seon.store/store store :seon.store/branch green-branch}))))
              (finally
                (when-let [child (get-in @routing [:seon.agent/children branch])]
                  (doseq [started (keys (:seon.agent/started @child))]
                    (agent/stop-flow! {:seon.agent/id started :seon.agent/routing child}))
                  (doseq [execution (vals (:seon.agent/contexts @child))
                          :when (or (:seon.agent/owns-connection? execution) (:seon.agent/owns-branch? execution))]
                    (agent/release-context! execution)))
                (agent/release-chain-route! routing branch)
                (registry/retire-branch! {:seon.store/store store :seon.store/branch branch}))))))))))

(defn- record-on-main!
  "Run `tests` on `connection`'s head through the chain world's `handle` with no task, as root's central round
  does; the run."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.db/connection [:set :symbol]] :map]}
  [handle connection tests]
  (let [ran (seon.test/run {:seon.test/execution handle
                            :seon.test/recording-connection connection :seon.test/policy :named
                            :seon.test/identities tests})]
    (when (:seon.error/at ran) (throw (ex-info (:seon.error/message ran) ran)))
    ran))

(defn- release-chain!
  "Stop the graphs db<->flow started on chain `branch` under `routing`, release their executions, retire the branch."
  {:malli/schema [:=> [:cat :seon.agent/routing :seon.store/store :seon.chain/branch] :nil]}
  [routing store branch]
  (when-let [child (get-in @routing [:seon.agent/children branch])]
    (doseq [started (keys (:seon.agent/started @child))]
      (agent/stop-flow! {:seon.agent/id started :seon.agent/routing child}))
    (doseq [execution (vals (:seon.agent/contexts @child))
            :when (or (:seon.agent/owns-connection? execution) (:seon.agent/owns-branch? execution))]
      (agent/release-context! execution)))
  (agent/release-chain-route! routing branch)
  (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})
  nil)

(deftest ^{:seon.test/long "One chain world: a chain fork, its first attempt capped after one scripted model turn and bailed over three db<->flow passes, then root's run and the second attempt's start and close, and root's run of that closed attempt's task and the third attempt's start." :seon.test/long-ms 30000}
  a-capped-chain-attempt-bails-to-root-once-and-root-runs-it-again
  ;; TASK-CHAIN S3c-1 (`docs/seon/schedule/cards/TASK-CHAIN.md#2-flow`, Bail or fail): an end that is not done is
  ;; processed once by root's pass: one problem task on main named by the attempt, the attempt :closed with its
  ;; reason and the task :held on the chain branch, the branch and chain fact kept, no successor started;
  ;; `my.root/run!` then starts exactly one new attempt with a new agent on the same branch, which root lists and
  ;; closes with its own tools
  (binding [*space* "tq.chainbail"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                              :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                            (db/db connection) connection)
                                              :seon.batch/sink (seon.batch/sink)
                                              :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                              :seon.agent/id "tq-chain-author"})
                                (symbol (str *space* "-proofs"))
                                "(clojure.test/deftest proves-c (clojure.test/is (= 1 2)))\n")
          ;; one turn: the attempt's red proof opens it, the scripted model waits, and the spent budget ends it :capped
          (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                          :seon.config/manifest {:seon.config.ai/no-provider false :seon.config.ai/no-auth true
                                                 :seon.config.ai/endpoint "http://127.0.0.1:9/chat"
                                                 :seon.config.ai/model "fixture-provider"
                                                 :seon.config.run/max-episode-runs 1}})
          (let [handle (assoc handle :seon.turn.loop/complete (fn [_projection _request]
                                                                {:seon.ai/text "(my.turn/wait {:my.turn/note \"Waiting.\"})"}))
                routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "c")}}
                                    {:seon.task/ns (symbol *space*) :seon.task/name "ignored"}])
                branch (:seon.chain/branch chain)
                [first-task second-task] (:seon.task/handles chain)
                key-1 (id/id [first-task 1])
                key-2 (id/id [first-task 2])
                key-3 (id/id [first-task 3])
                context {:seon.db/connection connection :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                         :my.program/base-ctx (:seon.sci.eval/ctx handle) :seon.store/store store}
                problems #(set (db/q '[:find [?n ...] :where [?t :seon.task/ns seon.task] [?t :seon.task/name ?n]
                                       [(clojure.string/starts-with? ?n "unfinished-task-")]]
                                     (db/db connection)))
                chain-db #(db/branch-database (db/db connection) branch)
                attempt-rows (fn [database] (set (db/q '[:find ?k ?n ?id :in $ ?h
                                                         :where [?t :seon.task/id ?h] [?a :seon.task/attempt-of ?t]
                                                         [?a :seon.task/assignment ?k] [?a :seon.task.attempt/number ?n]
                                                         [?a :seon.task/agent ?g] [?g :seon.agent/id ?id]]
                                                       database first-task)))]
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                (is (= :capped (await-on! on-branch #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                             [?a :seon.task.attempt/end ?e]]
                                                           (db/db on-branch) key-1)
                                          ::capped))
                    "the first attempt ends :capped")
                (pass! handle routing)
                (pass! handle routing)
                (pass! handle routing)
                (is (= #{(str "unfinished-task-" key-1)} (problems)) "exactly one problem task on main, named by the attempt")
                (let [database (db/db on-branch)
                      row (db/pull database [:seon.task/outcome :seon.task/reason] [:seon.task/assignment key-1])]
                  (is (= :closed (:seon.task/outcome row)) "the attempt is :closed on the chain branch")
                  (is (string? (:seon.task/reason row)) "with its reason")
                  (is (= :held (:seon.task/execution (db/pull database [:seon.task/execution] [:seon.task/id first-task])))
                      "its task is :held")
                  (is (= #{[key-1 1 (str "attempt/" key-1)]} (attempt-rows database)) "no attempt was started again")
                  (is (= :held (:seon.task/execution (db/pull database [:seon.task/execution] [:seon.task/id second-task])))
                      "no successor started"))
                (is (contains? (registry/branches store) branch) "the branch is kept")
                (is (some? (db/q '[:find ?c . :in $ ?b :where [?c :seon.chain/branch ?b]] (db/db connection) branch))
                    "the chain fact is kept")
                (let [ran ((requiring-resolve (quote my.root/run!)) {:my.program/context context :seon.task/assigned first-task})]
                  (is (= {:seon.task/assigned first-task :seon.chain/branch branch :seon.task/execution :running
                          :seon.task.attempt/number 2 :seon.task/assignment key-2}
                         ran)
                      "root runs the held task again on its branch, answering the attempt its next pass starts"))
                (pass! handle routing)
                (is (= #{[key-1 1 (str "attempt/" key-1)] [key-2 2 (str "attempt/" key-2)]} (attempt-rows (db/db on-branch)))
                    "exactly one new attempt, the next number, with a new agent on the same branch")
                (is (some? (some-> (get-in @routing [:seon.agent/children branch]) deref
                                   (get-in [:seon.agent/started (str "attempt/" key-2)])))
                    "its agent started on the chain branch")
                (is (not-any? #(= key-2 (:seon.task/assignment %))
                              (my.root/merge-requests {:seon.db/db (db/db connection) :seon.store/store store}))
                    "a running chain attempt is not a merge request")
                (is (= :closed (:seon.task/outcome (my.root/close! {:my.program/context context :seon.task/assignment key-2
                                                                     :seon.task/reason "Root abandons this attempt."})))
                    "root closes the chain attempt with its own tool")
                (is (= :closed (:seon.task/outcome (db/pull (chain-db) [:seon.task/outcome] [:seon.task/assignment key-2])))
                    "on the chain branch")
                (is (contains? (registry/branches store) branch) "the branch stays for root")
                ;; S5 run 5: an attempt root closed before its agent reported an end is decided, not open, so running
                ;; its task again starts exactly the next attempt with a new agent
                (is (= {:seon.task.attempt/number 3 :seon.task/assignment key-3}
                       (select-keys ((requiring-resolve (quote my.root/run!)) {:my.program/context context :seon.task/assigned first-task})
                                    [:seon.task.attempt/number :seon.task/assignment]))
                    "root runs the task whose attempt it closed with no end, answering the next attempt")
                (pass! handle routing)
                (is (= #{[key-1 1 (str "attempt/" key-1)] [key-2 2 (str "attempt/" key-2)] [key-3 3 (str "attempt/" key-3)]}
                       (attempt-rows (db/db on-branch)))
                    "a closed attempt with no end starts exactly one new attempt, the next number, with a new agent")
                (is (some? (some-> (get-in @routing [:seon.agent/children branch]) deref
                                   (get-in [:seon.agent/started (str "attempt/" key-3)])))
                    "and its agent starts"))
              (finally (release-chain! routing store branch))))))))))

(defn- held-chain-world
  "Call `body` with the chain world's handle and a one-task chain c, its proof red, whose task is :held from its start,
  so no attempt and no graph ever ran on its branch; the branch is released and retired after."
  {:malli/schema [:=> [:cat :seon.db/connection [:=> [:cat :seon.agent/context-source :seon.task/chain :map] :seon.schema/value]]
                  :seon.schema/value]}
  [connection body]
  (chain-world
   connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
   (fn [handle]
     (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                         :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                       (db/db connection) connection)
                                         :seon.batch/sink (seon.batch/sink)
                                         :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                         :seon.agent/id "tq-chain-author"})
                           (symbol (str *space* "-proofs"))
                           "(clojure.test/deftest proves-c (clojure.test/is (= 1 2)))\n")
     (let [routing (:seon.agent/routing handle)
           store (agent/handle-store handle)
           chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                              [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                :seon.task/proofs #{(proof "c")} :seon.task/execution :held}])
           branch (:seon.chain/branch chain)]
       (try
         (body handle chain {:seon.db/connection connection :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                             :my.program/base-ctx (:seon.sci.eval/ctx handle) :seon.store/store store})
         (finally (release-chain! routing store branch)))))))

(defn- chain-attempt-started?
  "Whether attempt `n` of chain `chain`'s first task has its row on the chain branch and its graph in `routing`."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/routing :seon.task/chain :int] :boolean]}
  [connection routing {branch :seon.chain/branch [h] :seon.task/handles} n]
  (let [key (id/id [h n])]
    (boolean
     (and (db/q '[:find ?a . :in $ ?k :where [?a :seon.task/assignment ?k]] (db/branch-database (db/db connection) branch) key)
          (some-> (get-in @routing [:seon.agent/children branch]) deref (get-in [:seon.agent/started (str "attempt/" key)]))))))

(deftest ^{:seon.test/long "One chain world: a held chain's first db<->flow pass, root's run and the pass its wake asks for, which starts the first attempt's graph." :seon.test/long-ms 30000}
  a-held-chain-with-no-graph-runs-from-run-alone
  ;; reactive start P2 (`docs/research/agent-platform/2026-10-15/reactive-start-plan.md`): attempt 4 of the S5 chain
  ;; was lost because no route listened on a chain branch no graph held. Root holds a route on every chain branch main
  ;; names, so `my.root/run!` alone wakes it: the plan channel holds that wake, and the pass it asks for launches.
  (binding [*space* "tq.chainheld"]
    (test-support/with-database
     (fn [connection]
       (held-chain-world
        connection
        (fn [handle {branch :seon.chain/branch [h] :seon.task/handles :as chain} context]
          (let [routing (:seon.agent/routing handle)
                plan (:seon.cluster.wake/channel handle)]
            (pass! handle routing)
            (is (contains? (:seon.agent/chain-routes @routing) branch) "root listens on the chain branch with no graph there")
            (is (not (chain-attempt-started? connection routing chain 1)) "the held task has no attempt and no graph")
            (async/poll! plan)
            (is (= :running (:seon.task/execution ((requiring-resolve 'my.root/run!) {:my.program/context context :seon.task/assigned h})))
                "root runs the held task")
            (is (= ::wake/wake (test-support/await-event! plan ::run-woke-root))
                "its commit alone offers root's plan channel a wake, through the chain route")
            (pass! handle routing)
            (is (chain-attempt-started? connection routing chain 1) "the pass that wake asks for writes the attempt and starts its graph"))))))))

(deftest ^{:seon.test/long "One chain world: a held chain's run, its first attempt started, stopped and closed, root's second run and the pass its wake asks for." :seon.test/long-ms 30000}
  a-chain-route-outlives-its-attempts
  ;; reactive start P2, the narrow check's C4: a graph's release unlistens its own route key; the chain's route has its
  ;; own, so an ended attempt's release leaves root listening and the next run! still wakes it
  (binding [*space* "tq.chainroute"]
    (test-support/with-database
     (fn [connection]
       (held-chain-world
        connection
        (fn [handle {branch :seon.chain/branch [h] :seon.task/handles :as chain} context]
          (let [routing (:seon.agent/routing handle)
                plan (:seon.cluster.wake/channel handle)
                run! #((requiring-resolve 'my.root/run!) {:my.program/context context :seon.task/assigned h})]
            (pass! handle routing)
            (run!)
            (pass! handle routing)
            (is (chain-attempt-started? connection routing chain 1) "attempt 1 runs")
            ;; attempt 1 ends as db<->flow's :stop ends it: its graph stopped, its owned execution released
            (let [child (get-in @routing [:seon.agent/children branch])]
              (doseq [started (keys (:seon.agent/started @child))]
                (agent/stop-flow! {:seon.agent/id started :seon.agent/routing child}))
              (doseq [execution (vals (:seon.agent/contexts @child))
                      :when (:seon.agent/owns-connection? execution)]
                (agent/release-context! execution)))
            (is (= :closed (:seon.task/outcome ((requiring-resolve 'my.root/close!)
                                                {:my.program/context context :seon.task/assignment (id/id [h 1])
                                                 :seon.task/reason "Root closes attempt 1."})))
                "root closes attempt 1 and its task is held")
            (async/poll! plan)
            (is (= 2 (:seon.task.attempt/number (run!))) "root runs it again")
            (is (= ::wake/wake (test-support/await-event! plan ::second-run-woke-root))
                "the chain route outlived attempt 1's release: the second run wakes root")
            (pass! handle routing)
            (is (chain-attempt-started? connection routing chain 2) "and attempt 2 starts"))))))))

(deftest ^{:seon.test/long "One chain world: a task-reading proof recorded green on main with no task, a chain whose first attempt proves it red and makes one scripted model call; then a chain whose proof main already holds green, ended done by its prove pass; measured with the S3a-2 row's world." :seon.test/long-ms 30000}
  a-green-recorded-without-the-task-never-proves-a-chain-task
  ;; S5 run 1 D1 (`docs/research/agent-platform/2026-10-14/s5-run-1.md`): a test reading its task (`seon.test/task`)
  ;; has a result identity that includes its task input (`seon.test.runner/reach-digests`), so root's input-less green
  ;; on main never answers for a chain task holding that proof; D2: a task green at its start by results it found
  ;; still ends :done (TASK-CHAIN §2.3)
  (binding [*space* "tq.chaininput"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (let [reader (symbol (str *space* "-readers") "reads-no-task")]
            (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                                :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                              (db/db connection) connection)
                                                :seon.batch/sink (seon.batch/sink)
                                                :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                                :seon.agent/id "tq-chain-author"})
                                  (symbol (str *space* "-readers"))
                                  (str "(clojure.test/deftest reads-no-task (clojure.test/is (nil? (seon.test/task))))\n"
                                       ;; S5 run 2 D4: a task reader whose body runs a definition outside its closure
                                       "(defn helper {:malli/schema [:=> [:cat] :int]} [] 1)\n"
                                       "(clojure.test/deftest reads-dynamically (clojure.test/is (some? (seon.test/task)))"
                                       " (clojure.test/is (= 2 ((resolve (symbol \"tq.chaininput-readers\" \"helper\"))))))\n"))
            (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                            :seon.config/manifest {:seon.config.ai/no-provider false :seon.config.ai/no-auth true
                                                   :seon.config.ai/endpoint "http://127.0.0.1:9/chat"
                                                   :seon.config.ai/model "fixture-provider"}})
            (is (true? (:seon.test/passed? (record-on-main! handle connection #{reader (proof "u")})))
                "main holds the reader green with no task, and u green")
            (let [calls (atom 0)
                  handle (assoc handle :seon.turn.loop/complete (fn [_projection _request]
                                                                  (swap! calls inc)
                                                                  {:seon.ai/text "(my.turn/wait {:my.turn/note \"Waiting for root.\"})"}))
                  routing (:seon.agent/routing handle)
                  store (agent/handle-store handle)
                  chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                     [{:seon.task/ns (symbol *space*) :seon.task/name "r" :seon.task/plan "Do r."
                                       :seon.task/proofs #{reader (symbol (str *space* "-readers") "reads-dynamically")}}])
                  branch (:seon.chain/branch chain)
                  agent-id (str "attempt/" (id/id [(first (:seon.task/handles chain)) 1]))]
              (try
                (pass! handle routing)
                (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                  (await-on! on-branch #(when (pos? (model-turns (db/db on-branch) agent-id)) true) ::first-model-turn)
                  (is (= 1 @calls) "main's input-less green proves nothing for the task: its red proving opens a model call"))
                (finally (release-chain! routing store branch)))
              (let [green (task/chain! {:seon.db/connection connection :seon.store/store store}
                                       [{:seon.task/ns (symbol *space*) :seon.task/name "g" :seon.task/plan "Do g."
                                         :seon.task/proofs #{(proof "u")}}])
                    green-branch (:seon.chain/branch green)
                    key (id/id [(first (:seon.task/handles green)) 1])]
                (try
                  (pass! handle routing)
                  (let [on-green (registry/active-branch-connection {:seon.store/store store :seon.store/branch green-branch})]
                    (is (= :done (await-on! on-green #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                                 [?a :seon.task.attempt/end ?e]]
                                                               (db/db on-green) key)
                                            ::green-ended))
                        "a chain task green at its start by the result main holds ends done")
                    (is (= 1 @calls) "with no model call"))
                  (finally (release-chain! routing store green-branch))))))))))))


(defn- chain-attempts
  "Every attempt row on `database` of the chain tasks `handles`: task, attempt number, agent, end, outcome."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.task/handle]]
                  [:set [:tuple :seon.task/handle :int :seon.agent/id :keyword :keyword]]]}
  [database handles]
  (set (db/q '[:find ?h ?n ?id ?e ?o :in $ [?h ...]
               :where [?t :seon.task/id ?h] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/number ?n]
               [?a :seon.task/agent ?g] [?g :seon.agent/id ?id]
               [(get-else $ ?a :seon.task.attempt/end :open) ?e] [(get-else $ ?a :seon.task/outcome :none) ?o]]
             database handles)))

(deftest ^{:seon.test/long "One two-task chain world: task one's start test run and done end, the advance, task two's start test run and one scripted model turn over five db<->flow passes." :seon.test/long-ms 30000}
  a-two-task-chain-reaches-its-second-model-call
  ;; TASK-CHAIN S3a-3 (`docs/seon/schedule/cards/TASK-CHAIN.md#2-flow`, Advance): task one ends done, root advances
  ;; the chain on its branch (`seon.task/advance-tx`) and the same pass starts task two's NEW agent there, whose red
  ;; proof opens its first model call with no hand act; a repeated pass starts nothing again and writes no task datom
  (binding [*space* "tq.chainadvance"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                              :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                            (db/db connection) connection)
                                              :seon.batch/sink (seon.batch/sink)
                                              :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                              :seon.agent/id "tq-chain-author"})
                                (symbol (str *space* "-proofs"))
                                "(clojure.test/deftest proves-c-2 (clojure.test/is (= 1 2)))\n")
          (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                          :seon.config/manifest {:seon.config.ai/no-provider false :seon.config.ai/no-auth true
                                                 :seon.config.ai/endpoint "http://127.0.0.1:9/chat"
                                                 :seon.config.ai/model "fixture-provider"}})
          (let [calls (atom 0)
                plan "Do c with `seon.task/advance-tx`."
                handle (assoc handle :seon.turn.loop/complete (fn [_projection _request]
                                                                (swap! calls inc)
                                                                {:seon.ai/text "(my.turn/wait {:my.turn/note \"Waiting for root.\"})"}))
                routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan plan
                                     :seon.task/proofs #{(proof "u")}}
                                    {:seon.task/ns (symbol *space*) :seon.task/name "ignored"
                                     :seon.task/proofs #{(proof "c-2")}}])
                branch (:seon.chain/branch chain)
                [one two] (:seon.task/handles chain)
                key-one (id/id [one 1])
                agent-one (str "attempt/" key-one)
                agent-two (str "attempt/" (id/id [two 1]))
                head #(db/branch-database (db/db connection) branch)
                pulled #(db/pull (head) [:seon.task/plan :seon.task/references :seon.task/proofs :seon.task/execution]
                                 [:seon.task/id two])]
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                (is (= :done (await-on! on-branch #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                         [?a :seon.task.attempt/end ?e]]
                                                       (db/db on-branch) key-one)
                                        ::one-ended))
                    "task one ends done")
                (pass! handle routing)
                (is (= :done (db/q '[:find ?o . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task/outcome ?o]]
                                   (head) key-one))
                    "root records its attempt's outcome :done on the chain branch")
                (let [{p :seon.task/plan r :seon.task/references proofs :seon.task/proofs e :seon.task/execution} (pulled)]
                  (is (= :running e) "the next task runs")
                  (is (= plan p) "the plan flows to the next task")
                  (is (= #{'seon.task/advance-tx} (set r)) "with its references derived again")
                  (is (= #{(proof "c-2")} (set proofs)) "the next task keeps its own proofs: owned, they never flow"))
                (is (= 1 (await-on! on-branch #(when (pos? (model-turns (db/db on-branch) agent-two)) @calls) ::two-called))
                    "task two's agent makes its first model call with no hand act")
                (let [datoms (count (db/datoms (head) :eavt [:seon.task/id two]))]
                  (pass! handle routing)
                  (pass! handle routing)
                  (is (= datoms (count (db/datoms (head) :eavt [:seon.task/id two]))) "a repeated pass writes no task datom")
                  (is (= [] (task/advance-tx (head) key-one)) "a repeated advance answers no transaction data"))
                (let [rows (chain-attempts (head) [one two])]
                  (is (= #{[one 1 agent-one :done :done]} (into #{} (filter (comp #{one} first)) rows))
                      "task one never starts again")
                  (is (= #{agent-one agent-two} (into #{} (map #(nth % 2)) rows))
                      "exactly two distinct agents share the one branch"))
                (is (zero? (model-turns (head) agent-one)) "task one took no model turn")
                (is (= 1 @calls) "the scripted model was called once, by task two"))
              (finally
                (when-let [child (get-in @routing [:seon.agent/children branch])]
                  (doseq [started (keys (:seon.agent/started @child))]
                    (agent/stop-flow! {:seon.agent/id started :seon.agent/routing child}))
                  (doseq [execution (vals (:seon.agent/contexts @child))
                          :when (or (:seon.agent/owns-connection? execution) (:seon.agent/owns-branch? execution))]
                    (agent/release-context! execution)))
                (agent/release-chain-route! routing branch)
                (registry/retire-branch! {:seon.store/store store :seon.store/branch branch}))))))))))

(deftest ^{:seon.test/long "One two-task chain world: task one's start test run over three db<->flow passes, one eval request through the MCP entrance, its test step and done end, then the advance."
           :seon.test/long-ms 30000}
  an-outside-task-takes-no-model-turn-and-is-worked-through-the-repl
  ;; TASK-CHAIN S3f-2 (`docs/seon/schedule/cards/TASK-CHAIN.md#2-flow`, Run a task: the model is per task): an
  ;; `:outside` task's agent is started as any task's, but its graph takes no model turn over repeated passes; the MCP
  ;; entrance enters it by id on its chain branch (minting no agent or branch), its eval request's test step runs the
  ;; task's proof, and its done rule ends it and advances the chain
  (binding [*space* "tq.chainoutside"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                              :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                            (db/db connection) connection)
                                              :seon.batch/sink (seon.batch/sink)
                                              :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                              :seon.agent/id "tq-chain-author"})
                                (symbol (str *space* "-proofs"))
                                "(clojure.test/deftest proves-c (clojure.test/is (= 1 2)))\n")
          (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                          :seon.config/manifest {:seon.config.ai/no-provider false :seon.config.ai/no-auth true
                                                 :seon.config.ai/endpoint "http://127.0.0.1:9/chat"
                                                 :seon.config.ai/model "fixture-provider"}})
          (let [calls (atom 0)
                handle (assoc handle :seon.turn.loop/complete (fn [_projection _request]
                                                                (swap! calls inc)
                                                                {:seon.ai/text "(my.turn/wait {:my.turn/note \"Waiting for root.\"})"}))
                routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "c")} :seon.task/model :outside}
                                    {:seon.task/ns (symbol *space*) :seon.task/name "ignored"
                                     :seon.task/proofs #{(proof "u")} :seon.task/model :outside}])
                branch (:seon.chain/branch chain)
                [one two] (:seon.task/handles chain)
                key-one (id/id [one 1])
                agent-id (str "attempt/" key-one)
                head #(db/branch-database (db/db connection) branch)
                agent-branches (fn [] (set (filter #(str/starts-with? (name %) "agent-") (registry/branches store))))]
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                (is (seq (await-on! on-branch #(not-empty (proof-runs (db/db on-branch) (proof "c"))) ::proved))
                    "its start's test step runs its proof on the chain branch")
                (pass! handle routing)
                (pass! handle routing)
                (is (= 0 @calls) "an :outside task's agent made no model call over repeated passes")
                (is (zero? (model-turns (db/db on-branch) agent-id)) "and took no model turn")
                (let [before (agent-branches)
                      custody (#'mcp/execution {:seon.turn.loop/cluster handle :seon.store/store store} agent-id nil)
                      answer (agent/evaluate-source!
                              (assoc custody :seon.agent/segmenter 'seon.turn/code-sources
                                     :seon.cluster.reply/text
                                     (str "(in-ns '" *space* "-proofs)\n"
                                          "(clojure.test/deftest proves-c (clojure.test/is (= 1 1)))")))]
                  (is (identical? (get-in @routing [:seon.agent/children branch]) (:seon.agent/routing custody))
                      "the MCP entrance enters the chain agent where its graph runs, on its chain branch")
                  (is (some? (db/q '[:find ?r . :in $ ?run ?id :where [?r :seon.turn/id ?run] [?r :seon.turn/agent ?g]
                                     [?g :seon.agent/id ?id]]
                                   (db/db on-branch) (:seon.turn/id answer) agent-id))
                      "its eval request is the chain agent's run, recorded on the chain branch")
                  (is (nil? (db/pull (db/db connection) [:db/id] [:seon.agent/id agent-id])) "main mints no agent of its id")
                  (is (= before (agent-branches)) "and no branch")
                  (is (= [0] (mapv :seon.test/fail-count (seon.test.runner/latest-results (db/db on-branch) [(proof "c")])))
                      "its test step records the task's proof green on the chain branch"))
                (is (= :done (await-on! on-branch #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                         [?a :seon.task.attempt/end ?e]]
                                                       (db/db on-branch) key-one)
                                        ::outside-ended))
                    "its done rule ends the task done")
                (pass! handle routing)
                (is (= :running (:seon.task/execution (db/pull (head) [:seon.task/execution] [:seon.task/id two])))
                    "root advances the chain to its next task")
                (is (= 0 @calls) "still with no model call"))
              (finally
                (release-chain! routing store branch))))))))))

(deftest ^{:seon.test/long "One chain world: task one's start test run, one eval request through the MCP entrance whose last form sends the task back, and two db<->flow passes that bail it."
           :seon.test/long-ms 30000}
  an-outside-agents-send-back-ends-its-attempt-and-root-bails-it-once
  ;; TASK-CHAIN 2.2 and 2.7: an :outside agent's end is the same fact a model agent's is. Its eval request whose last
  ;; form is `my.task/send-back!` records the end on its attempt, the reply carries that recorded end, and root's
  ;; pass bails it once: one problem task on main, the attempt :closed and the task :held on the chain branch
  (binding [*space* "tq.chainoutsideback"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                              :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                            (db/db connection) connection)
                                              :seon.batch/sink (seon.batch/sink)
                                              :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                              :seon.agent/id "tq-chain-author"})
                                (symbol (str *space* "-proofs"))
                                "(clojure.test/deftest proves-c (clojure.test/is (= 1 2)))\n")
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "c")} :seon.task/model :outside}])
                branch (:seon.chain/branch chain)
                [one] (:seon.task/handles chain)
                key-one (id/id [one 1])
                agent-id (str "attempt/" key-one)
                problems #(set (db/q '[:find [?n ...] :where [?t :seon.task/ns seon.task] [?t :seon.task/name ?n]
                                       [(clojure.string/starts-with? ?n "unfinished-task-")]]
                                     (db/db connection)))]
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})
                    _ (await-on! on-branch #(not-empty (proof-runs (db/db on-branch) (proof "c"))) ::proved)
                    custody (#'mcp/execution {:seon.turn.loop/cluster handle :seon.store/store store} agent-id nil)
                    answer (agent/evaluate-source!
                            (assoc custody :seon.agent/segmenter 'seon.turn/code-sources
                                   :seon.cluster.reply/text
                                   "(my.task/send-back! {:my.task/reason \"The proof contradicts the plan.\"})"))
                    end-of #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]
                                  (db/db on-branch) key-one)]
                (is (= :sent-back (end-of)) "the eval request records the end on the attempt")
                (is (= "The proof contradicts the plan."
                       (db/q '[:find ?r . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/reason ?r]]
                             (db/db on-branch) key-one))
                    "with its reason")
                (is (= {:seon.task/assignment key-one :seon.task.attempt/end :sent-back :seon.turn.loop/processed-by :root}
                       (some #(get-in % [:seon.sci.eval/evaluation :my.turn/value :seon.turn.loop/end])
                             (:seon.turn.loop/evaluated-sources answer)))
                    "its reply carries the recorded end, for root to process")
                (pass! handle routing)
                (pass! handle routing)
                (is (= #{(str "unfinished-task-" key-one)} (problems)) "root bails it once: one problem task on main")
                (let [database (db/db on-branch)]
                  (is (= :closed (:seon.task/outcome (db/pull database [:seon.task/outcome] [:seon.task/assignment key-one])))
                      "the attempt is :closed on the chain branch")
                  (is (= :held (:seon.task/execution (db/pull database [:seon.task/execution] [:seon.task/id one])))
                      "and its task :held")))
              (finally
                (release-chain! routing store branch))))))))))

(deftest a-prove-step-whose-product-main-holds-merges-and-its-successor-forks-main
  ;; the product, the successor's proof red by design, is on main before the Prove step forks: its merge would
  ;; accept as it stands, so it ends done with no model call and root's merge completes it
  (binding [*space* "tq.chainheld"]
  (test-support/with-database
   (fn [connection]
     (chain-world
      connection {"p" [#{} nil :prove] "i" [#{"p"} "(clojure.test/is (= 1 2))"]}
      (fn [handle]
        (let [routing (:seon.agent/routing handle)
              _ (pass! handle routing)
              [[key branch agent-id]] (seq (attempts connection "p"))
              on-branch (registry/active-branch-connection {:seon.store/store (agent/handle-store handle) :seon.store/branch branch})
              end (await-on! on-branch #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]
                                              (db/db on-branch) key)
                             ::held-product-ended)]
          (is (= :done end) "the Prove step ends done")
          (is (zero? (model-turns (db/db on-branch) agent-id)) "with no model call")
          (pass! handle routing)
          (is (= :merged (db/q '[:find ?o . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task/outcome ?o]] (db/db connection) key))
              "main merges it through an accepted candidate")
          (is (task/done? (db/db connection) (chain-handle "p")) "its successor's need is met")
          (is (contains? (task/ready (db/db connection)) (chain-handle "i")) "its successor is ready")
          (pass! handle routing)
          (is (seq (attempts connection "i")) "and forks main")
          (is (eventually #(not (contains? (registry/branches (agent/handle-store handle)) branch)))
              "the closed Prove step's branch is deleted, as a merged one is"))))))))

(deftest ^{:seon/tasks #{'seon.turn/a-closed-attempt-releases-its-connection-before-branch-deletion}}
  an-ended-attempts-closed-merge-releases-its-connection-and-branch
  (doseq [from-attempt? [false true]]
    (binding [*space* (if from-attempt? "tq.retirelocal" "tq.retireroot")]
      (test-support/with-database
       (fn [connection]
         (fixed-chain-world
          connection ["g"] {"g" [#{} "(clojure.test/is (= 1 2))"]}
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
                    ;; the end stops the graph and releases its connections; its merge, refused in a world with
                    ;; no checkout for the proofs' file, keeps the branch for root
                    _ (pass! handle routing)
                    _ (is (contains? (registry/branches store) branch) "the refused merge keeps the branch for root")
                    closed (task/close! {:seon.db/connection connection :seon.store/store store
                                         :seon.agent/routing (if from-attempt? child routing)}
                                        key :closed "The lifecycle proof closes this request.")]
                (is (some? branch-connection) "the attempt owned an open connection before closing")
                (is (= :closed (:seon.task/outcome closed)))
                (is (nil? (:seon.task/delete-refusals closed)))
                (is (.isTerminated ^java.util.concurrent.ExecutorService graph) "the actual graph exited")
                (is (= :released @(:wrapped-atom branch-connection)))
                (is (nil? (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})))
                (is (not (contains? (registry/branches store) branch)))
                (is (empty? (db/q '[:find ?error :where [?error :seon.error/signature]] (db/db connection)))
                    "the refused merge is the candidate's, never a fault"))))))))))

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

(defn- define-on!
  "Define `source` in namespace `ns-sym` on `connection`'s branch, as an agent of the chain world would."
  {:malli/schema [:=> [:cat :seon.db/connection :symbol :string] :seon.schema/value]}
  [connection ns-sym source]
  (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                      :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                    (db/db connection) connection)
                                      :seon.batch/sink (seon.batch/sink)
                                      :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                      :seon.agent/id "tq-chain-author"})
                        ns-sym source))

(defn- checkout!
  "Name a disposable git checkout holding `files` (relative path to text) as the chain world's
  `:seon.config.fs/working-root` on `connection`, its `test` files declared on main as publication declares
  them: the checkout its merges are proven against and published into. Its path."
  {:malli/schema [:=> [:cat :seon.db/connection [:map-of :string :string]] :string]}
  [connection files]
  (let [root (test-support/temp-root "chain-checkout")]
    (doseq [[path text] files]
      (.mkdirs (.getParentFile (java.io.File. root ^String path)))
      (spit (java.io.File. root ^String path) text))
    (doseq [args [["init" "-q"] ["config" "user.email" "chain@test.invalid"] ["config" "user.name" "chain-test"]
                  ["add" "."] ["commit" "-q" "-m" "fixture"]]]
      (apply shell/sh "git" "-C" root args))
    (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                    :seon.config/manifest {:seon.config.fs/working-root root}})
    (let [entities (seon.fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["test"]})]
      (test-support/transacted! connection (seon.fn/reconcile-tx (db/db connection) entities
                                                                 (mapv seon.program/row-identity entities))))
    root))

;;; P2: an attempt's end lands on main from its branch, once, and its branch goes or stays.

(defn- end-on-branch!
  "Write the attempt of `task-name`'s end `end` on its own branch, as its last transaction does: the
  task's intent refined, a message to root and the end on the attempt, after `work` (source defined in
  `proof-sym`'s namespace, when given) once `proof-sym` ran there. Answers [key branch agent-id attempt]."
  {:malli/schema [:function
                  [:=> [:cat :seon.agent/context-source :seon.db/connection :string :seon.task.attempt/end]
                   [:tuple :seon.task/assignment :seon.task/branch :seon.agent/id :int]]
                  [:=> [:cat :seon.agent/context-source :seon.db/connection :string :seon.task.attempt/end
                        :qualified-symbol [:maybe :string]]
                   [:tuple :seon.task/assignment :seon.task/branch :seon.agent/id :int]]]}
  ([handle connection task-name end] (end-on-branch! handle connection task-name end (proof task-name) nil))
  ([handle connection task-name end proof-sym work]
  (let [[[key branch agent-id]] (seq (attempts connection task-name))
        branch-connection (registry/active-branch-connection {:seon.store/store (agent/handle-store handle)
                                                              :seon.store/branch branch})]
    ;; the agent's first pass ran its red proofs: it is idle when its work and end are written
    (await-on! branch-connection #(not-empty (proof-runs (db/db branch-connection) proof-sym)) ::proved)
    (when work (define-on! branch-connection (symbol (namespace proof-sym)) work))
    (test-support/transacted!
     branch-connection
     (into [{:db/id [:seon.task/id (chain-handle task-name)] :seon.task/plan "Refined on the branch."}
            [:db/add [:seon.task/assignment key] :seon.task.attempt/end end]
            [:db/add [:seon.task/assignment key] :seon.task.attempt/commit (db/commit-id (db/db branch-connection))]]
           (concat (when (not= :done end)
                     [[:db/add [:seon.task/assignment key] :seon.task.attempt/reason "The proof asks the impossible."]])
                   (message/send-call (db/db branch-connection)
                                      (message/candidate {:my.message/to "root" :my.message/content "Only on my branch."})
                                      agent-id))))
    [key branch agent-id (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/assignment key]))])))

(defn- copies
  "The transactions on main `database` that merged `branch`'s facts or wrote its end, as transaction ids."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch] [:set :int]]}
  [database branch]
  (set (db/q '[:find [?tx ...] :in $ ?b :where [?tx :seon.squash/source-branch ?b]] database branch)))

(defn- done-checkout!
  "The chain world's checkout holding `e`'s proof in `<space>-rows` beside the stub `twice` it reaches
  (`checkout!`): a :done end merges only through an accepted candidate. The proof's symbol."
  {:malli/schema [:=> [:cat :seon.db/connection] :qualified-symbol]}
  [connection]
  (let [rows (str *space* "-rows")]
    (checkout! connection {(str "test/" (str/replace rows #"[.-]" {"." "/" "-" "_"}) ".clj")
                           (str "(ns " rows "\n  (:require [clojure.test :refer [deftest is]]))\n\n"
                                "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] x)\n\n"
                                "(deftest ^{:seon/tasks #{'" *space* "/e}} proves-e (is (= 4 (twice 2))))\n")})
    (symbol rows "proves-e")))

(def ^:private twice-work "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))")

(deftest ^{:seon.test/long "Two chain worlds (the keep dial off and on), each one attempt's accepted merge (a nested seon.test/run on a fresh merge branch) and its publication over four db<->flow passes; measured 6.6-7.4 s."
           :seon.test/long-ms 30000}
  an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted
  (doseq [keep? [false true]]
    (binding [*space* (if keep? "tq.endkeep" "tq.endcopy")]
      (test-support/with-database
       (fn [connection]
         (chain-world
          connection {"e" [#{} nil] "z" [#{} "(clojure.test/is (= 1 1))"]}
          (fn [handle]
            (let [routing (:seon.agent/routing handle)
                  store (agent/handle-store handle)
                  proves-e (done-checkout! connection)]
              (when keep?
                (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                :seon.config/manifest {:seon.config.agent/keep-ended-branches? true}}))
              (pass! handle routing)
              (let [[key branch agent-id a] (end-on-branch! handle connection "e" :done proves-e twice-work)
                    intent #(:seon.task/plan (db/pull (db/db connection) [:seon.task/plan] [:seon.task/id (chain-handle "e")]))]
                (is (= #{[key branch agent-id :open]} (attempts connection "e")) "before the pass main lacks the end")
                (pass! handle routing)
                (let [database (db/db connection)
                      [tx :as txs] (seq (copies database branch))]
                  (is (= 1 (count txs)) "one end copy")
                  (is (= tx (db/q '[:find ?tx . :in $ ?a :where [?a :seon.task.attempt/end :done ?tx]] database a))
                      "carrying the end")
                  (is (= tx (db/q '[:find ?tx . :in $ ?h :where [?t :seon.task/id ?h] [?t :seon.task/plan "Refined on the branch." ?tx]]
                                  database (chain-handle "e")))
                      "and the refinement")
                  (is (= branch (db/q '[:find ?b . :in $ ?tx :where [?tx :seon.squash/source-branch ?b]] database tx))
                      "with its provenance")
                  (is (empty? (db/q '[:find ?m :where [?m :seon.message/content "Only on my branch."]] database))
                      "and no message")
                  (is (= "Refined on the branch." (intent)))
                  (is (not (contains? (:seon.flow.actions/started (agent/registry-facts routing)) agent-id))
                      "its graph is stopped"))
                (pass! handle routing)
                (is (= 1 (count (copies (db/db connection) branch))) "a second pass merges nothing")
                ;; that pass published the merge; publication's own write wakes the pass that deletes the branch
                (pass! handle routing)
                (if keep?
                  (is (contains? (registry/branches store) branch) "the dial keeps the ended branch for audit")
                  (is (eventually #(not (contains? (registry/branches store) branch)))
                      "with no merge request the branch is deleted"))
                (is (empty? (db/q '[:find [?m ...] :where [?e :seon.error/signature] [?e :seon.error/message ?m]] (db/db connection)))
                    "with no fault recorded"))))))))))

(deftest ^{:seon.test/long "One chain world, one attempt's accepted merge (a nested seon.test/run on a fresh merge branch), its publication, its sibling's merge and up to six db<->flow passes; measured 6.6 s."
           :seon.test/long-ms 30000}
  a-merged-assignment-whose-graph-survived-a-crash-is-stopped-and-deleted
  ;; root merged the end (its merge base is the branch head) and the process went down before the graph stopped:
  ;; the passes after it derive the stop, the publication and the deletion from the facts alone
  (binding [*space* "tq.crashmerged"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"e" [#{} nil] "z" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                proves-e (done-checkout! connection)]
            (pass! handle routing)
            (let [[key branch agent-id] (end-on-branch! handle connection "e" :done proves-e twice-work)]
              (db/with-branch {:seon.db/connection connection}
                              #(merge/merge! {:seon.db/connection connection :seon.store/store store} key))
              (is (contains? (:seon.flow.actions/started (agent/registry-facts routing)) agent-id) "the graph survived the merge")
              (pass! handle routing)
              (is (not (contains? (:seon.flow.actions/started (agent/registry-facts routing)) agent-id))
                  "one pass stops it")
              ;; each pass's own writes (the publication, the deletion, the sibling z's end) wake the next, as in production
              (let [due #(agent/next-actions (agent/lifecycle-facts (db/db connection) store (registry/branches store) (agent/registry-facts routing)))]
                (dotimes [_ 4] (when (seq (due)) (pass! handle routing)))
                (is (empty? (mapv :seon.flow.actions/op (due))) "the passes its writes wake leave nothing to derive"))
              (is (not (contains? (registry/branches store) branch)) "and its branch is deleted")
              (is (= "Refined on the branch." (:seon.task/plan (db/pull (db/db connection) [:seon.task/plan]
                                                                          [:seon.task/id (chain-handle "e")])))
                  "while main keeps what merged")
              (let [measured (profile/measure #(pass! handle routing))]
                (is (< (:seon.profile/ms measured) 50) "and a pass with nothing left to derive stays under 50 ms"))))))))))

(deftest
  an-error-end-copies-nothing-and-writes-roots-problem-task
  (binding [*space* "tq.enderror"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"e" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                intent #(:seon.task/plan (db/pull (db/db connection) [:seon.task/plan] [:seon.task/id (chain-handle "e")]))]
            (pass! handle routing)
            (let [[key branch] (end-on-branch! handle connection "e" :given-back)
                  problem #(db/pull (db/db connection) [:seon.task/plan]
                                    [:seon.task/sym (symbol "seon.task" (str "unfinished-task-" key))])]
              (pass! handle routing)
              (is (= #{[key branch (str "attempt/" key) :given-back]} (attempts connection "e")) "main holds the end")
              (is (= "Do e." (intent)) "and no refinement")
              (is (contains? (registry/branches store) branch) "the branch stays")
              (is (str/includes? (str (:seon.task/plan (problem))) (name branch))
                  "root's problem task names the branch")
              (is (str/includes? (str (:seon.task/plan (problem))) "The proof asks the impossible.")
                  "and the reason")
              (pass! handle routing)
              (is (= 1 (count (copies (db/db connection) branch))) "a second pass writes nothing")
              (testing "root's by-hand merge lands what the success path would"
                (let [merge! #(db/with-branch {:seon.db/connection connection}
                                              (fn [] (merge/merge! {:seon.db/connection connection :seon.store/store store} key)))
                      merged (merge!)]
                  (is (uuid? (:seon.task/merge-commit merged)))
                  (is (= "Refined on the branch." (intent)))
                  (let [main (db/commit-id (db/db connection))]
                    (is (nil? (:seon.task/merge-commit (merge!))) "and a second merge transacts nothing")
                    (is (= main (db/commit-id (db/db connection)))))))))))))))

(deftest
  a-capped-attempt-whose-branch-changed-no-program-gets-a-problem-task-without-a-merge-offer
  (binding [*space* "tq.endcapped"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"e" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)]
            (pass! handle routing)
            (let [[key] (end-on-branch! handle connection "e" :capped)
                  intent #(str (:seon.task/plan (db/pull (db/db connection) [:seon.task/plan]
                                                           [:seon.task/sym (symbol "seon.task" (str "unfinished-task-" key))])))]
              (pass! handle routing)
              (is (= 1 (count (attempts connection "e"))) "an end with no work is not relaunched")
              (is (not (str/includes? (intent) "my.root/merge!")) "an empty diff offers no merge")
              (is (str/includes? (intent) "my.root/close!") "it offers closing"))))))))) 

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
        connection {"a" [#{} nil] "b" [#{"a"} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                rows (str *space* "-rows")]
            (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                            :seon.config/manifest {:seon.config.agent/keep-ended-branches? true}})
            ;; a's red proof lives in the checkout, beside the function it reaches: a's merge is accepted into it
            (checkout! connection {(str "test/" (str/replace rows #"[.-]" {"." "/" "-" "_"}) ".clj")
                                   (str "(ns " rows "\n  (:require [clojure.test :refer [deftest is]]))\n\n"
                                        "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] x)\n\n"
                                        "(deftest ^{:seon/tasks #{'" *space* "/a}} proves-a (is (= 4 (twice 2))))\n")})
            (pass! handle routing)
            (let [[[key-a branch-a agent-a]] (seq (attempts connection "a"))
                  a-db #(db/branch-database (db/db connection) branch-a)
                  on-a (registry/active-branch-connection {:seon.store/store (agent/handle-store handle) :seon.store/branch branch-a})]
              (is (= (row-ids (db/db connection) key-a agent-a) (row-ids (a-db) key-a agent-a))
                  "a fork of main after the attempt write inherits its row and agent")
              ;; a's agent does its work and ends :done on its own branch; only the accepted merge completes it
              (await-on! on-a #(not-empty (proof-runs (db/db on-a) (symbol rows "proves-a"))) ::proved)
              (define-on! on-a (symbol rows) "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))")
              (test-support/transacted! on-a
                                        [[:db/add [:seon.task/assignment key-a] :seon.task.attempt/end :done]
                                         [:db/add [:seon.task/assignment key-a] :seon.task.attempt/commit
                                          (db/commit-id (db/db on-a))]])
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

(deftest ^{:seon/tasks #{'seon.cluster.agent/a-requested-green-end-merges-and-publishes-with-no-hand-act}}
  a-requested-end-whose-files-no-checkout-holds-merges-nothing
  ;; db<->flow merges a requested green end like any done end; this world's cluster names no checkout that
  ;; holds the proofs' file, so the gate refuses before acceptance: nothing reaches main but the refusal and,
  ;; with no relaunch allowed, the end closed for root
  (binding [*space* "tq.endcustody"]
    (test-support/with-database
     (fn [connection]
       (fixed-chain-world
        connection ["g"] {"g" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)]
            (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                            :seon.config/manifest {:seon.config.run/relaunches 0}})
            (pass! handle routing)
            (let [[[key branch agent-id]] (seq (attempts connection "g"))
                  a (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/assignment key]))
                  child (get-in @routing [:seon.agent/children branch])
                  graph (get-in @child [:seon.agent/started agent-id :seon.flow/executor])
                  _ (landed! handle connection "g" ::g-ended)
                  database (db/db connection)
                  head (db/branch-database database branch)]
              (is (not (merge/merged? database (db/commit-id head))) "main records no merge of its head")
              (is (= {:seon.task.attempt/end :done :seon.task/refusal :seon.source/write-back-error}
                     (select-keys (db/pull database [:seon.task.attempt/end :seon.task/outcome :seon.task/refusal] a)
                                  [:seon.task.attempt/end :seon.task/outcome :seon.task/refusal]))
                  "the refused merge is recorded beside the assignment's own end, not an outcome")
              (let [intents (db/q '[:find [?i ...] :in $ ?k :where [?t :seon.task/sym ?s] [(namespace ?s) ?n] [(= ?n "seon.task")]
                                    [?t :seon.task/plan ?i] [(clojure.string/includes? ?i ?k)]]
                                  database key)]
                (is (= 1 (count intents)) "the refusal is delivered to root as one problem task naming the assignment")
                (is (every? #(clojure.string/includes? % ":seon.source/write-back-error") intents) "which names the refusal's type"))
              (is (contains? (registry/branches (agent/handle-store handle)) branch) "its branch stays")
              (is (.isTerminated ^java.util.concurrent.ExecutorService graph) "its graph was stopped and joined")
              (is (empty? (db/q '[:find ?e :where [?e :seon.error/signature]] database))
                  "and the candidate's refusal is no fault of Seon's")))))))))

(deftest ^{:seon/tasks #{'seon.task/a-refinement-on-a-branch-reaches-main-only-at-the-end}}
  a-refinement-on-a-branch-reaches-main-only-at-the-end
  (binding [*space* "tq.refine"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"r" [#{} nil] "s" [#{"r"} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                rows (str *space* "-rows")
                h (chain-handle "r")
                declaration (fn [database] (task/declaration (db/carried-projection database) (db/pull database '[*] [:seon.task/id h])))
                refine! (fn [conn agent-id intent]
                          (task/create! {:seon.db/connection conn :seon.agent/id agent-id :seon.task/ns (symbol *space*)
                                         :seon.task/name "r" :seon.task/plan intent
                                         :seon.task/basis {h (declaration (db/db conn))}}))
                intent #(:seon.task/plan (db/pull (db/db connection) [:seon.task/plan] [:seon.task/id h]))
                child #(db/pull (db/db connection) [:seon.task/plan] [:seon.task/sym (symbol *space* "rc")])
                problems #(set (db/q '[:find [?s ...] :where [?t :seon.task/sym ?s] [(namespace ?s) ?n] [(= ?n "seon.task")]
                                       [(str ?s) ?text] [(clojure.string/includes? ?text "squash-conflict")]]
                                     (db/db connection)))]
            ;; r's red proof lives in the checkout, beside the function it reaches: r's merge is accepted into it
            (checkout! connection {(str "test/" (str/replace rows #"[.-]" {"." "/" "-" "_"}) ".clj")
                                   (str "(ns " rows "\n  (:require [clojure.test :refer [deftest is]]))\n\n"
                                        "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] x)\n\n"
                                        "(deftest ^{:seon/tasks #{'" *space* "/r}} proves-r (is (= 4 (twice 2))))\n")})
            (pass! handle routing)
            (let [[[key branch agent-id]] (seq (attempts connection "r"))
                  on-branch (registry/active-branch-connection {:seon.store/store (agent/handle-store handle)
                                                                :seon.store/branch branch})]
              (await-on! on-branch #(not-empty (proof-runs (db/db on-branch) (symbol rows "proves-r"))) ::proved)
              (is (true? (:seon.task/changed? (refine! on-branch agent-id "Refined by the agent."))) "the agent refines its task")
              (is (true? (:seon.task/changed? (task/create! {:seon.db/connection on-branch :seon.agent/id agent-id
                                                             :seon.task/ns (symbol *space*) :seon.task/name "rc"
                                                             :seon.task/plan "A child of mine."}))))
              (is (= "Do r." (intent)) "main's intent is unchanged while the attempt runs")
              (is (nil? (:seon.task/plan (child))) "and main holds no child task yet")
              (is (true? (:seon.task/changed? (refine! connection "root" "Refined by root."))) "root edits the same intent on main")
              ;; its work, so its :done end is accepted
              (define-on! on-branch (symbol rows) "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))")
              (test-support/transacted! on-branch
                                        [[:db/add [:seon.task/assignment key] :seon.task.attempt/end :done]
                                         [:db/add [:seon.task/assignment key] :seon.task.attempt/commit (db/commit-id (db/db on-branch))]])
              (pass! handle routing)
              (is (= "Refined by root." (intent)) "after the end copy main keeps root's concurrent edit")
              (is (= 1 (count (problems))) "and writes exactly one problem task for it")
              (is (= "A child of mine." (:seon.task/plan (child))) "the agent's child task reached main with the end")))))))))

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

(defn- merged-by-hand!
  "Write on main an assignment of the chain world's task `task-name` merged into main, its branch long
  deleted, as root's merge leaves it: end :done, outcome :merged."
  {:malli/schema [:=> [:cat :seon.db/connection :string] :nil]}
  [connection task-name]
  (let [agent-id (str "tq-merged-" task-name)
        branch (keyword (str "tq-merged-" task-name))]
    (test-support/transacted! connection (agent/creation-tx {:seon.agent/id agent-id :seon.ns/name 'user
                                                             :seon.cluster/name "tq-chain" :seon.agent/branch branch}))
    (let [[row] (task/attempt-tx (db/db connection) (chain-handle task-name) agent-id branch)
          key [:seon.task/assignment (:seon.task/assignment row)]]
      (test-support/transacted! connection [row])
      (test-support/transacted! connection [[:db/add key :seon.task.attempt/end :done]
                                            [:db/add key :seon.task/outcome :merged]
                                            [:db/add key :seon.task.attempt/commit (db/commit-id (db/db connection))]])
      nil)))

;;; Every assignment forks main once every need merged (hybrid-done-and-merge M10).

(deftest a-join-forks-main-after-both-needs-merge
  (binding [*space* "tq.join"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"a" [#{} "(clojure.test/is (= 1 2))"] "b" [#{} "(clojure.test/is (= 1 2))"]
                    "c" [#{"a" "b"} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)]
            ;; b waits held, so only root's merges decide c
            (test-support/transacted! connection [[:db/add [:seon.task/id (chain-handle "b")] :seon.task/execution :held]])
            (merged-by-hand! connection "a")
            (pass! handle routing)
            (is (empty? (attempts connection "c")) "no successor assignment while one need is unmerged")
            (merged-by-hand! connection "b")
            (pass! handle routing)
            (let [[[key-c branch-c agent-c] :as cs] (seq (attempts connection "c"))
                  main (db/db connection)
                  base (registry/fork-point main (registry/head-commit-id main branch-c) (get-in main [:config :branch]))]
              (is (= 1 (count cs)) "both needs merged: c is assigned")
              (is (some? (:db/id (db/pull (db/commit-database main base) [:db/id] [:seon.task/assignment key-c])))
                  "a commit of main holding its own assignment: it forked main after its row was written")
              (is (some? (db/q '[:find ?a . :in $ ?id :where [?a :seon.agent/id ?id]]
                               (db/branch-database main branch-c) agent-c))
                  "its branch holds its agent")
              (is (contains? (registry/branches store) branch-c))
              (is (empty? (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection)))
                  "with no fault recorded")))))))))

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
              (is (not (contains? (:seon.flow.actions/started (agent/registry-facts routing)) agent-id)) "the graph is stopped")
              (is (seq (proof-runs database (proof "s"))) "after its pass recorded its proof run")
              (is (empty? (db/q '[:find ?e :where [?e :seon.cluster.eval/interrupted-at _]] database))
                  "with nothing interrupted")))))))))

(deftest a-cancelled-assignment-closes-without-a-task-for-root
  (binding [*space* "tq.cancelclose"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"c" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                problems #(set (db/q '[:find [?n ...] :where [?t :seon.task/sym ?s] [(namespace ?s) ?ns] [(= ?ns "seon.task")]
                                       [(name ?s) ?n] [(clojure.string/starts-with? ?n "unfinished-task-")]]
                                     (db/db connection)))]
            (pass! handle routing)
            (let [[[key branch]] (seq (attempts connection "c"))]
              (test-support/transacted! connection [[:db/add [:seon.task/id (chain-handle "c")] :seon.task/execution :cancelled]])
              (pass! handle routing)
              (pass! handle routing)
              (let [row (db/pull (db/db connection) [:seon.task.attempt/end :seon.task/outcome] [:seon.task/assignment key])]
                (is (= :cancelled (:seon.task.attempt/end row)) "the close writes the end on main")
                (is (= :closed (:seon.task/outcome row)) "and closes the assignment")
                (is (empty? (problems)) "with no problem task for root")
                (is (contains? (registry/branches (agent/handle-store handle)) branch) "the branch stays"))))))))))

(deftest a-failed-start-ends-its-attempt-and-the-next-plans
  ;; x's branch already stands with no merge base (a fork interrupted before its base was retained): its start
  ;; fails, and the failure is x's assignment's end, so no pass starts it again while y, ready beside it, starts
  (binding [*space* "tq.failstart"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"a" [#{} "(clojure.test/is (= 1 2))"] "x" [#{"a"} "(clojure.test/is (= 1 2))"]
                    "y" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                started #(:seon.flow.actions/started (agent/registry-facts routing))]
            (test-support/transacted! connection (agent/creation-tx {:seon.agent/id "tq-gone-agent" :seon.ns/name 'user
                                                                     :seon.cluster/name "tq-chain"
                                                                     :seon.agent/branch :tq-gone-branch}))
            (registry/branch! {:seon.store/store (agent/handle-store handle)
                               :seon.store/branch (keyword (str "attempt-" (id/id [(chain-handle "x") 1])))
                               :seon.cluster.registry/from (get-in (db/db connection) [:config :branch])})
            (let [[row] (task/attempt-tx (db/db connection) (chain-handle "a") "tq-gone-agent" :tq-gone-branch)]
              (test-support/transacted! connection [row])
              (test-support/transacted! connection
                                        [[:db/add [:seon.task/assignment (:seon.task/assignment row)] :seon.task.attempt/end :done]
                                         [:db/add [:seon.task/assignment (:seon.task/assignment row)] :seon.task/outcome :merged]
                                         [:db/add [:seon.task/assignment (:seon.task/assignment row)] :seon.task.attempt/commit
                                          (db/commit-id (db/db connection))]]))
            (pass! handle routing)
            (let [[[key-x _ _ end] :as xs] (seq (attempts connection "x"))]
              (is (= 1 (count xs)) "x got its first assignment")
              (is (= :error end) "whose failed start ended it in the same run")
              (is (string? (:seon.task.attempt/reason (db/pull (db/db connection) [:seon.task.attempt/reason]
                                                               [:seon.task/assignment key-x])))
                  "with the failure as its reason"))
            (pass! handle routing)
            (is (= 1 (count (attempts connection "x"))) "the next run never assigns x again")
            (let [[[_ _ agent-y end-y] :as ys] (seq (attempts connection "y"))]
              (is (= 1 (count ys)) "and y has its assignment")
              (is (= :open end-y))
              (is (contains? (started) agent-y) "whose graph runs")))))))))

;;; Chain experiment 1 (docs/research/agent-platform/2026-10-12/chain-experiment-1.md): a Design+Prove step's output
;;; is a red proof for its successor, written on its own branch.

(deftest a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red
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
           connection {"p" [#{} (str "(clojure.test/is (contains? (:seon.task/proofs (seon.task/obligations (seon.db/db) '"
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
             (is (= 1 (count @ledger)) "after the one model call that wrote it"))))))))))

(deftest a-prove-step-admitted-by-its-successor-opens-a-model-turn
  ;; q is a :prove step with no proof of its own: admission starts it because i needs it (`launch-facts`), so its
  ;; fresh attempt is work. Its one model reply writes i's red proof, its product, and it ends :done.
  (binding [*space* "tq.chainbare"]
  (test-support/with-database
   (fn [connection]
     (let [ledger (atom [])]
       (test-support/with-provider
        ledger (test-support/reply-queue
                [(str "(in-ns 'tq.chainbare-behavior)\n"
                      "(clojure.test/deftest ^{:seon/tasks #{'" *space* "/i}} i-behaves (clojure.test/is (= 1 2)))")])
        (fn [server]
          (chain-world
           ;; z, already green, only gives the world a program to define; green at its base, it takes no model turn
           connection {"q" [#{} nil :prove] "i" [#{"q"} nil] "z" [#{} "(clojure.test/is (= 1 1))"]}
           (fn [handle]
             (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                             :seon.config/manifest (assoc (test-support/provider-config server) :seon.config.ai/no-provider false)})
             (pass! handle (:seon.agent/routing handle))
             (is (= 1 (count (attempts connection "q"))) "admission started q by its successor's declaration")
             (is (= :done (landed! handle connection "q" ::q-ended))
                 "its fresh attempt opened a model turn, whose reply wrote its product")
             (is (= 1 (count @ledger)) "after the one model call that wrote it"))))))))))


(deftest a-send-back-reason-reaches-the-targets-next-attempt
  ;; chain experiment 2: the Implement agent's counterexample reached root's end message only, so the relaunched
  ;; Design+Prove agent never saw why it was sent back. a sends itself back with a reason; its next attempt reads it.
  (binding [*space* "tq.chainback"]
  (test-support/with-database
   (fn [connection]
     (let [ledger (atom [])
           reason "Assertion 3 can never pass: its eid is nil."]
       (test-support/with-provider
        ledger (test-support/reply-queue
                [(str "(my.task/send-back! {:my.task/reason \"" reason "\" :my.task/target '" *space* "/a})")])
        (fn [server]
          (chain-world
           connection {"a" [#{} "(clojure.test/is (= 1 2))"]}
           (fn [handle]
             (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                             :seon.config/manifest (assoc (test-support/provider-config server) :seon.config.ai/no-provider false)})
             (pass! handle (:seon.agent/routing handle))
             (is (= :sent-back (landed! handle connection "a" ::a-sent-back)) "a's one reply sent it back")
             (pass! handle (:seon.agent/routing handle))
             (let [[_ branch agent-id] (some (fn [[k :as row]]
                                               (when (= 2 (:seon.task.attempt/number (db/pull (db/db connection) [:seon.task.attempt/number] [:seon.task/assignment k])))
                                                 row))
                                             (attempts connection "a"))
                   on-branch (registry/active-branch-connection {:seon.store/store (agent/handle-store handle) :seon.store/branch branch})]
               (is (some #(= reason (:seon.task.attempt/reason %))
                         (:seon.context/send-backs (my.task/current {:seon.db/db (db/db on-branch) :seon.agent/id agent-id})))
                   "the relaunched attempt's task names why it was sent back")))))))))))

(deftest
  admission-reads-a-prove-steps-proof-or-successor-declaration
  (binding [*space* "tq.admission"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"p" [#{} "(clojure.test/is (= 1 2))" :prove]
                    "q" [#{} nil :prove] "i" [#{"q"} "(clojure.test/is (= 1 2))"]
                    "r" [#{} nil :prove]
                    "s" [#{} nil] "j" [#{"s"} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [observed (agent/launch-facts (db/db connection) (registry/branches (agent/handle-store handle)))]
            (is (= (set (map chain-handle ["p" "q"])) (:seon.flow.actions/startable observed))
                "Prove starts with own proofs or a successor declaration")
            (is (= (set (map chain-handle ["r" "s"])) (:seon.flow.actions/invalid observed))
                "Prove without either and Implement without proofs cannot start"))))))))

(defn- rows-source
  "The Prove step's work in the checkout's namespace `<space>-rows`: the contracted stub `twice` with body `stub`
  and i's red proof `twice-doubles`, as a Prove step adds a row beside an existing table test."
  {:malli/schema [:=> [:cat :string] :string]}
  [stub]
  (str "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] " stub ")\n"
       "(clojure.test/deftest ^{:seon/tasks #{'" *space* "/i}} twice-doubles (clojure.test/is (= 4 (twice 2))))"))

(defn- rows-checkout!
  "The chain world's checkout holding the namespace `<space>-rows` with one green table test (`checkout!`)."
  {:malli/schema [:=> [:cat :seon.db/connection] :string]}
  [connection]
  (let [rows (str *space* "-rows")]
    (checkout! connection {(str "test/" (str/replace rows #"[.-]" {"." "/" "-" "_"}) ".clj")
                           (str "(ns " rows "\n  (:require [clojure.test :refer [deftest is]]))\n\n"
                                "(deftest rows (is (= 1 1)))\n")})))

(defn- proved!
  "Start the chain world's Prove step `p`, whose successor is `i`; on p's branch define `rows-source` with body
  `stub`; end p :done at that commit on main and pass once, so the merge gate alone judges its red. p's
  assignment key."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.db/connection :string] :seon.task/assignment]}
  [handle connection stub]
  (let [routing (:seon.agent/routing handle)
        _ (rows-checkout! connection)
        _ (pass! handle routing)
        [[key-p branch-p]] (seq (attempts connection "p"))
        p-connection (registry/active-branch-connection {:seon.store/store (agent/handle-store handle)
                                                         :seon.store/branch branch-p})]
    (define-on! p-connection (symbol (str *space* "-rows")) (rows-source stub))
    ;; p ends on its own branch: merge! reads the end from the source commit
    (test-support/transacted! p-connection
                              [[:db/add [:seon.task/assignment key-p] :seon.task.attempt/end :done]
                               [:db/add [:seon.task/assignment key-p] :seon.task.attempt/commit (db/commit-id (db/db p-connection))]])
    (pass! handle routing)
    key-p))

(deftest ^{:seon.test/long "Two chain worlds, each one Prove attempt and one gate (a nested seon.test/run on a fresh merge branch) over two db<->flow passes."
           :seon.test/long-ms 120000}
  a-prove-steps-red-product-merges-into-main
  (doseq [[space stub] [["tq.pvdesign" "(throw (ex-info \"not yet\" {:seon.error/type :seon.error/unimplemented}))"]
                        ["tq.pvthrow" "(throw (ex-info \"boom\" {}))"]]]
    (binding [*space* space]
      (test-support/with-database
       (fn [connection]
         (chain-world
          ;; z, already green, only gives the world a program to define
          connection {"p" [#{} nil :prove] "i" [#{"p"} nil] "z" [#{} "(clojure.test/is (= 1 1))"]}
          (fn [handle]
            (let [key-p (proved! handle connection stub)
                  database (db/db connection)
                  held? #(seq (db/datoms database :avet %1 %2))]
              (testing "a red by an error its subject throws, an unimplemented stub or any other, tagged with the step after it, is the Prove step's product"
                (is (= :merged (:seon.task/outcome (db/pull database [:seon.task/outcome] [:seon.task/assignment key-p])))
                    "one pass merges the Prove step")
                (is (held? :seon.test/sym (symbol (str space "-rows") "twice-doubles")) "main holds its red deftest")
                (is (held? :seon.fn/sym (symbol (str space "-rows") "twice")) "and its contracted stub"))))))))))

(deftest ^{:seon.test/long "One chain world, two attempts and one gate (a nested seon.test/run on a fresh merge branch) over three db<->flow passes."
           :seon.test/long-ms 120000}
  a-two-step-chains-implement-step-forks-main-holding-the-prove-steps-product
  (binding [*space* "tq.twostep"]
    (test-support/with-database
     (fn [connection]
      (test-support/with-provider
       (atom []) (test-support/reply-queue
                  [(str "(in-ns '" *space* "-rows)\n"
                        (rows-source "(throw (ex-info \"not yet\" {:seon.error/type :seon.error/unimplemented}))"))])
       (fn [server]
       (chain-world
        connection {"p" [#{} nil :prove] "i" [#{"p"} nil] "z" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                rows (str *space* "-rows")
                outcome #(:seon.task/outcome (db/pull (db/db connection) [:seon.task/outcome] [:seon.task/assignment %]))
                _ (rows-checkout! connection)
                _ (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                  :seon.config/manifest (assoc (test-support/provider-config server) :seon.config.ai/no-provider false)})
                _ (pass! handle routing)
                [[key-p]] (seq (attempts connection "p"))
                ;; the Prove step's one reply wrote its product; its step derives done and ends it, and one pass merges it
                p-end (landed! handle connection "p" ::p-ended)
                ;; the Implement step's work below is written by hand: no model turn answers it
                _ (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                  :seon.config/manifest {:seon.config.ai/no-provider true}})
                _ (pass! handle routing)
                [[key-i branch-i agent-i] :as assigned] (seq (attempts connection "i"))
                main (db/db connection)
                base (registry/fork-point main (registry/head-commit-id main branch-i) (get-in main [:config :branch]))
                i-connection (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch-i})]
            (is (= :done p-end) "the Prove step ended itself: its product is red by design")
            (is (= :merged (outcome key-p)) "the Prove step's designed reds merge as its product")
            (is (= 1 (count assigned)) "its successor is assigned once it merged")
            (is (seq (db/datoms (db/commit-database main base) :avet :seon.test/sym (symbol rows "twice-doubles")))
                "whose commit holds the Prove step's red deftest")
            ;; the Implement step's work: the stub's body, then its end at that commit
            (define-on! i-connection (symbol rows) "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))")
            ;; i ends on its own branch: merge! reads the end from the source commit
            (test-support/transacted! i-connection
                                      [[:db/add [:seon.task/assignment key-i] :seon.task.attempt/end :done]
                                       [:db/add [:seon.task/assignment key-i] :seon.task.attempt/commit (db/commit-id (db/db i-connection))]])
            (pass! handle routing)
            (is (= :merged (outcome key-i)) "its green proof merges the Implement step")
            (is (task/done? (db/db connection) (chain-handle "i")) "and the chain is done")
            (is (empty? (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection)))
                "with no fault recorded"))))))))))

;;; F45: a merge refused for a reason its agent can fix relaunches, the reason shown in the next block.

(deftest ^{:seon.test/long "One chain world, one attempt's refused gate (a nested seon.test/run on a fresh merge branch), its relaunch and the relaunched attempt's merge gate over four db<->flow passes."
           :seon.test/long-ms 180000}
  a-merge-refused-for-a-missing-contract-relaunches-with-the-refusal-shown
  (binding [*space* "tq.uncontracted"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        ;; z, already green, only gives the world a program to define
        connection {"u" [#{} nil] "z" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                rows (str *space* "-rows")
                doubled (symbol rows "doubled")
                problem #(:seon.task/plan (db/pull (db/db connection) [:seon.task/plan]
                                                     [:seon.task/sym (symbol "seon.task" (str "unfinished-task-" %))]))]
            ;; u's red proof lives in the checkout, beside the function it reaches
            (checkout! connection {(str "test/" (str/replace rows #"[.-]" {"." "/" "-" "_"}) ".clj")
                                   (str "(ns " rows "\n  (:require [clojure.test :refer [deftest is]]))\n\n"
                                        "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] x)\n\n"
                                        "(deftest ^{:seon/tasks #{'" *space* "/u}} proves-u (is (= 4 (twice 2))))\n")})
            (pass! handle routing)
            (let [[[key-1 branch-1]] (seq (attempts connection "u"))
                  on-1 (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch-1})]
              ;; the agent's first pass ran its red proof: it is idle when its work and end are written
              (await-on! on-1 #(not-empty (proof-runs (db/db on-1) (symbol rows "proves-u"))) ::proved)
              ;; the work: a correct body through a private helper that declares no contract
              (define-on! on-1 (symbol rows) (str "(defn- doubled [x] (* 2 x))\n"
                                                  "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] (doubled x))"))
              (test-support/transacted! on-1 [[:db/add [:seon.task/assignment key-1] :seon.task.attempt/end :done]
                                              [:db/add [:seon.task/assignment key-1] :seon.task.attempt/commit (db/commit-id (db/db on-1))]])
              (pass! handle routing)
              (is (= :seon.source/uncontracted-error
                     (:seon.task/refusal (db/pull (db/db connection) [:seon.task/refusal] [:seon.task/assignment key-1])))
                  "the merge's returned refusal is recorded on the assignment")
              (let [first-row (db/pull (db/db connection) [:seon.task.attempt/end :seon.task.attempt/relaunch]
                                       [:seon.task/assignment key-1])]
                (is (= :done (:seon.task.attempt/end first-row)) "main holds the attempt's own end")
                (is (string? (:seon.task.attempt/relaunch first-row)) "and relaunches it, as a capped end with work")
                (is (nil? (problem key-1)) "with no problem task for root"))
              (pass! handle routing)
              (let [[[key-2 branch-2 agent-2]] (seq (remove #(= key-1 (first %)) (attempts connection "u")))
                    on-2 (when branch-2 (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch-2}))
                    step (when on-2 (:seon.context/step (context/current-state (db/db on-2) agent-2)))]
                (is (string? key-2) "the second attempt starts")
                (is (= [:working [doubled]] [(:seon.task/status step) (some :seon.source/uncontracted (:seon.context/refusals step))])
                    "its block names the uncontracted helper the merge refused")
                ;; the second attempt re-touches only the helper; twice keeps attempt 1's change
                (await-on! on-2 #(not-empty (proof-runs (db/db on-2) (symbol rows "proves-u"))) ::proved-2)
                (define-on! on-2 (symbol rows) "(defn- doubled {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))")
                (test-support/transacted! on-2 [[:db/add [:seon.task/assignment key-2] :seon.task.attempt/end :done]
                                                [:db/add [:seon.task/assignment key-2] :seon.task.attempt/commit (db/commit-id (db/db on-2))]])
                (let [digest (fn [database sym] (db/q '[:find ?d . :in $ ?s :where [?f :seon.fn/sym ?s] [?f :seon.program/definition-digest ?d]]
                                                      database sym))
                      branch-2-value (db/db on-2)
                      _ (pass! handle routing)
                      main (db/db connection)]
                  (is (= :merged (:seon.task/outcome (db/pull main [:seon.task/outcome] [:seon.task/assignment key-2])))
                      "the relaunched attempt merges")
                  (is (= (digest branch-2-value doubled) (digest main doubled)) "main holds the helper attempt 2 contracted")
                  (is (= (digest branch-2-value (symbol rows "twice")) (digest main (symbol rows "twice")))
                      "and the body of twice only attempt 1 changed")))))))))))

;;; H5: a capped attempt with work continues from its end commit, up to :seon.config.run/relaunches.

(defn- relaunch-row
  "Attempt `key`'s merge base, end commit and relaunch reason on main `database`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment] :map]}
  [database key]
  (db/pull database [:seon.task.attempt/commit :seon.task.attempt/relaunch] [:seon.task/assignment key]))

(deftest a-capped-attempt-with-changes-relaunches-from-its-commit
  (binding [*space* "tq.relaunch"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"e" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                problem #(:seon.task/plan (db/pull (db/db connection) [:seon.task/plan]
                                                     [:seon.task/sym (symbol "seon.task" (str "unfinished-task-" %))]))
                work 'tq.relaunch-work/worked
                holds-work? #(some? (db/q '[:find ?f . :in $ ?s :where [?f :seon.fn/sym ?s]] % work))]
            (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                            :seon.config/manifest {:seon.config.run/relaunches 1}})
            (pass! handle routing)
            (let [[[key-1 branch-1]] (seq (attempts connection "e"))
                  on-1 (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch-1})]
              (define-on! on-1 'tq.relaunch-work "(defn worked [] 1)")
              (end-on-branch! handle connection "e" :capped)
              (pass! handle routing)
              (let [main (db/db connection)
                    first-row (relaunch-row main key-1)
                    [[key-2 branch-2 agent-2]] (seq (remove #(= key-1 (first %)) (attempts connection "e")))]
                (is (string? (:seon.task.attempt/relaunch first-row)) "the capped end with work is relaunched")
                (is (nil? (problem key-1)) "with no problem task")
                (is (and (string? key-2) (not= key-1 key-2) (not= branch-1 branch-2)) "a new assignment and branch")
                (is (= (registry/fork-point main (registry/head-commit-id main branch-1) (get-in main [:config :branch]))
                       (registry/fork-point main (registry/head-commit-id main branch-2) (get-in main [:config :branch])))
                    "its branch keeps the first attempt's fork point, so its merge takes both attempts' changes")
                (let [on-2 (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch-2})
                      [row agent :as ids] (row-ids (db/db on-2) key-2 agent-2)]
                  (is (and (int? row) (int? agent) (= (row-ids main key-2 agent-2) ids))
                      "its branch holds its row and agent under main's ids")
                  (is (holds-work? (db/db on-2)) "and the first attempt's work")
                  ;; the second attempt caps too: the dial is used
                  (await-on! on-2 #(not-empty (proof-runs (db/db on-2) (proof "e"))) ::proved)
                  (test-support/transacted! on-2 [[:db/add [:seon.task/assignment key-2] :seon.task.attempt/end :capped]
                                                  [:db/add [:seon.task/assignment key-2] :seon.task.attempt/commit
                                                   (db/commit-id (db/db on-2))]])
                  (pass! handle routing)
                  (is (= 2 (count (attempts connection "e"))) "the used dial launches no third attempt")
                  (is (nil? (:seon.task.attempt/relaunch (relaunch-row (db/db connection) key-2))))
                  (is (str/includes? (str (problem key-2)) "my.root/close!") "the task closes with one root task")
                  (is (not (str/includes? (str (problem key-2)) "my.root/merge!")) "offering no merge")
                  ;; a removed attempt never re-mints its identity
                  (test-support/transacted! connection [[:db/retractEntity [:seon.task/assignment key-1]]])
                  (is (= (id/id [(chain-handle "e") 3])
                         (:seon.task/assignment (first (task/attempt-tx (db/db connection) (chain-handle "e") agent-2 :tq-p2))))
                      "the next attempt is the third")))))))))))

;;; TASK-CHAIN A6 (`docs/seon/schedule/cards/TASK-CHAIN.md#interfaces`): every end's actions come from one derivation.

(deftest ^{:seon/tasks #{'seon.task/chain-actions}}
  ends-share-one-action-owner
  (let [c1 (random-uuid) c2 (random-uuid)
        held (fn [observed] (merge {:db/id 1 :seon.task/assignment key-a :seon.task/assigned a :seon.agent/id agent-a
                                 :seon.task/branch (keyword (str "attempt-" key-a))
                                 :seon.flow.actions/head c2 :seon.flow.actions/started? false :seon.flow.actions/kept? false
                                 :seon.flow.actions/merged? false :seon.flow.actions/allowance-left 1 :seon.flow.actions/exited? true
                                 :seon.flow.actions/work-left? true :seon.flow.actions/refused? false}
                                observed))
        actions #(agent/next-actions [(held %)])
        op-set #(set (map :seon.flow.actions/op (actions %)))]
    (doseq [[observed expected why]
            [[{:seon.task.attempt/end :capped :seon.flow.actions/started? true :seon.flow.actions/exited? false} #{:stop}
              "an ended graph that has not exited only stops"]
             [{:seon.task.attempt/end :capped} #{:relaunch} "a capped end with work and allowance left relaunches"]
             [{:seon.task.attempt/end :stalled} #{:relaunch} "so does a stalled end"]
             [{:seon.task.attempt/end :done :seon.flow.actions/refused? true} #{:relaunch}
              "so does a done end whose merge was refused"]
             [{:seon.task.attempt/end :capped :seon.flow.actions/allowance-left 0 :seon.flow.actions/refused? true} #{:close :problem}
              "spent allowance leaves the unfinished work visible: closed with a problem, never relaunched or deleted"]
             [{:seon.task.attempt/end :sent-back :seon.task.attempt/reason "The proof is wrong."}
              #{:merge} "a send-back merges its work, whose merge reopens its target, never relaunching the sender"]
             [{:seon.task.attempt/end :done :seon.flow.actions/main-end :done :seon.flow.actions/head c1 :seon.flow.actions/merged? true :seon.flow.actions/exited? false
               :seon.flow.actions/work-left? false}
              #{} "an accepted end retires only after its graph exits"]
             [{:seon.task.attempt/end :done :seon.flow.actions/main-end :done :seon.flow.actions/head c1 :seon.flow.actions/merged? true :seon.flow.actions/work-left? false}
              #{:delete-branch} "and then retires"]]]
      (is (= expected (op-set observed)) why))
    (is (= #{:unfinished-task} (set (keep :seon.flow.actions/class (actions {:seon.task.attempt/end :capped :seon.flow.actions/allowance-left 0}))))
        "the problem is an unfinished task")))

(deftest ^{:seon/tasks #{'seon.task/chain-actions}}
  every-agent-start-has-a-task
  ;; root ruling: a graph starts only from an assignment, and `seon.cluster.agent/launch-actions` is the one owner that starts it
  (binding [*space* "tq.taskless"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"z" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)]
            ;; root's maintenance schedules, as boot seeds them: root holds no assignment for them on HEAD
            (test-support/transacted! connection [[:db.fn/call #'schedule/root-maintenance-seed-call]])
            (let [planned (agent/launch-actions (agent/launch-facts (db/db connection) (registry/branches (agent/handle-store handle))) (plan-registry) 4)]
              (is (seq (filter #(= :start-flow (:seon.flow.actions/op %)) planned)) "the plan starts the task's attempt")
              (is (every? :seon.task/assignment (filter #(= :start-flow (:seon.flow.actions/op %)) planned))
                  "every start the plan returns names an assignment"))
            (is (seq (db/q '[:find [?t ...] :where [?r :seon.agent/id "root"] [?t :seon.schedule.task/owner ?r]] (db/db connection)))
                "root owns scheduled work")
            (try
            (pass! handle routing)
            (let [started (into (set (keys (:seon.agent/started @routing)))
                                (mapcat (comp keys :seon.agent/started deref val))
                                (:seon.agent/children @routing))
                  assigned (set (db/q '[:find [?id ...] :where [?a :seon.task/assignment] [?a :seon.task/agent ?g] [?g :seon.agent/id ?id]]
                                      (db/db connection)))]
              (is (seq started) "the pass started the assignment's graph")
              (is (= #{} (into #{} (remove assigned) started))
                  "no graph starts without an assignment")
              (is (not (contains? started "root"))
                  "a schedule owner with no assignment is not started"))
            (finally
              ;; whatever the pass started outside an attempt branch is stopped and released here
              (doseq [agent-id (keys (:seon.agent/started @routing))]
                (agent/stop-flow! {:seon.agent/id agent-id :seon.agent/routing routing}))
              (doseq [child (vals (:seon.agent/children @routing))]
                (doseq [agent-id (keys (:seon.agent/started @child))]
                  (agent/stop-flow! {:seon.agent/id agent-id :seon.agent/routing child}))
                (doseq [execution (vals (:seon.agent/contexts @child))
                        :when (or (:seon.agent/owns-connection? execution) (:seon.agent/owns-branch? execution))]
                  (agent/release-context! execution))))))))))))

(deftest ^{:seon.test/long "Three chain tasks each ending done at its start (one test step per agent) and one final merge (one prepare-merge!: a nested seon.test/run on a new candidate commit, 60 s is merge-test's measured bound for one)."
           :seon.test/long-ms 120000}
  a-three-task-chain-ends-merged-with-no-hand-act
  ;; TASK-CHAIN S3b (`docs/seon/schedule/cards/TASK-CHAIN.md#2-flow`, Finish): each task's proof is green at its
  ;; start, so each ends done with no model call; root advances the chain, and the last task's done end is merged
  ;; to main once, through `seon.merge/merge!` with the terminal attempt as its source; the branch and the chain
  ;; fact are gone after
  (binding [*space* "tq.chainmerged"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "u")}}
                                    {:seon.task/ns (symbol *space*) :seon.task/name "ignored" :seon.task/proofs #{(proof "u")}}
                                    {:seon.task/ns (symbol *space*) :seon.task/name "ignored" :seon.task/proofs #{(proof "u")}}])
                branch (:seon.chain/branch chain)
                handles (:seon.task/handles chain)
                attempts (mapv #(id/id [% 1]) handles)]
            (try
              (doseq [[i k] (map-indexed vector attempts)]
                (pass! handle routing)
                (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                  (await-on! on-branch #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]
                                              (db/db on-branch) k)
                             (keyword "seon.cluster.db-flow-test" (str "chain-task-" (inc i) "-ended")))))
              (pass! handle routing)
              (pass! handle routing)
              (let [main (db/db connection)]
                (is (= [:done :done :merged]
                       (mapv #(:seon.task/outcome (db/pull main [:seon.task/outcome] [:seon.task/assignment %])) attempts))
                    "one merge admits every attempt: the earlier ones :done, the terminal one :merged")
                (is (= (set handles) (set (db/q '[:find [?h ...] :in $ [?h ...] :where [_ :seon.task/id ?h]] main handles)))
                    "main holds every chain task")
                ;; the branch's deletion is io work on the launcher (`seon.task/delete-branch!`): await its exit
                (is (not (loop [n 0] (if (and (contains? (registry/branches store) branch) (< n 50))
                                       (do (Thread/sleep 100) (recur (inc n)))
                                       (contains? (registry/branches store) branch))))
                    "the chain branch is retired")
                (is (nil? (db/pull main [:db/id] [:seon.chain/first (first handles)])) "and its chain fact is gone"))
              (finally
                (when-let [child (get-in @routing [:seon.agent/children branch])]
                  (doseq [started (keys (:seon.agent/started @child))]
                    (agent/stop-flow! {:seon.agent/id started :seon.agent/routing child}))
                  (doseq [execution (vals (:seon.agent/contexts @child))
                          :when (or (:seon.agent/owns-connection? execution) (:seon.agent/owns-branch? execution))]
                    (agent/release-context! execution)))
                (agent/release-chain-route! routing branch)
                (when (contains? (registry/branches store) branch)
                  (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))))))))

(deftest ^{:seon.test/long "One chain world: a one-task chain whose attempt's hand-written work and done end, one refused final merge (prepare-merge!'s write-back refuses) and its bail over three db<->flow passes, then root's run and the second attempt's start."
           :seon.test/long-ms 60000}
  a-refused-final-merge-bails-to-root-once-with-its-refusal
  ;; TASK-CHAIN §2.7 (`docs/seon/schedule/cards/TASK-CHAIN.md#2-flow`, Bail or fail), S5 run 8: a done terminal
  ;; attempt whose final merge refuses bails once like every other end that is not done: one problem task on main
  ;; naming the refusal, the attempt :closed with the refusal's message as its reason and its type kept, the task
  ;; :held, branch and chain fact kept; `my.root/run!` then starts the next attempt
  (binding [*space* "tq.mergebail"]
    (test-support/with-database
     (fn [connection]
       ;; the attempt's work redefines its red proof green on the branch, a file no checkout of this world holds,
       ;; so the final merge's write-back refuses
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "u")}}])
                branch (:seon.chain/branch chain)
                [first-task] (:seon.task/handles chain)
                key-1 (id/id [first-task 1])
                key-2 (id/id [first-task 2])
                context {:seon.db/connection connection :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                         :my.program/base-ctx (:seon.sci.eval/ctx handle) :seon.store/store store}
                ;; the problem tasks naming this chain's task: main holds other worlds' too
                problems #(into {} (db/q '[:find ?n ?p :in $ ?h :where [?t :seon.task/ns seon.task] [?t :seon.task/name ?n]
                                           [(clojure.string/starts-with? ?n "unfinished-task-")] [?t :seon.task/plan ?p]
                                           [(clojure.string/includes? ?p ?h)]]
                                         (db/db connection) first-task))]
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                ;; the agent's work, by hand: its proof redefined green on the branch, then its end at that commit
                (await-on! on-branch #(not-empty (proof-runs (db/db on-branch) (proof "u"))) ::merge-bail-proved)
                (define-on! on-branch (symbol (str *space* "-proofs")) "(clojure.test/deftest proves-u (clojure.test/is (= 1 1)))")
                (is (= 1 (await-on! on-branch #(:green (:seon.context/counts (context/proofs (db/db on-branch) (symbol *space* "c"))))
                                 ::merge-bail-green))
                    "its proof is green on the branch")
                (test-support/transacted! on-branch [[:db/add [:seon.task/assignment key-1] :seon.task.attempt/end :done]
                                                     [:db/add [:seon.task/assignment key-1] :seon.task.attempt/commit
                                                      (db/commit-id (db/db on-branch))]])
                (pass! handle routing)
                (pass! handle routing)
                (pass! handle routing)
                (let [database (db/db on-branch)
                      row (db/pull database [:seon.task/outcome :seon.task/reason :seon.task/refusal] [:seon.task/assignment key-1])
                      found (problems)]
                  (is (= [(str "unfinished-task-" key-1)] (keys found)) "exactly one problem task on main, named by the attempt")
                  (is (keyword? (:seon.task/refusal row)) "the attempt keeps the refusal's type")
                  (is (some-> (vals found) first (str/includes? (str (:seon.task/refusal row)))) "which the problem task names")
                  (is (= :closed (:seon.task/outcome row)) "the attempt is :closed on the chain branch")
                  (is (str/includes? (str (:seon.task/reason row)) (str (:seon.task/refusal row) ": "))
                      "with the refusal's type and its own message as its reason")
                  (is (= :held (:seon.task/execution (db/pull database [:seon.task/execution] [:seon.task/id first-task])))
                      "its task is :held"))
                (is (nil? (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/assignment key-1]))) "nothing merged to main")
                (is (contains? (registry/branches store) branch) "the branch is kept")
                (is (some? (db/q '[:find ?c . :in $ ?b :where [?c :seon.chain/branch ?b]] (db/db connection) branch))
                    "the chain fact is kept")
                (is (= {:seon.task.attempt/number 2 :seon.task/assignment key-2}
                       (select-keys ((requiring-resolve (quote my.root/run!)) {:my.program/context context :seon.task/assigned first-task})
                                    [:seon.task.attempt/number :seon.task/assignment]))
                    "root runs the held task again")
                (pass! handle routing)
                (is (some? (some-> (get-in @routing [:seon.agent/children branch]) deref
                                   (get-in [:seon.agent/started (str "attempt/" key-2)])))
                    "and the next attempt's agent starts on the chain branch"))
              (finally (release-chain! routing store branch))))))))))

(defn- with-merge-template!
  "Ingest the merge step's template (`resources/seon/tasks/seon.step.merge.edn`) into the chain world on `connection`,
  as boot ingests the task files."
  {:malli/schema [:=> [:cat :seon.db/connection] :nil]}
  [connection]
  (let [path "resources/seon/tasks/seon.step.merge.edn"
        indexed (task/index-tasks! {:seon.db/connection connection :seon.task/files {path (slurp path)}})]
    (when (:seon.error/at indexed) (throw (ex-info (:seon.error/message indexed) indexed)))
    nil))

(defn- merge-branches
  "The store's merge branches (`:merge-<id>`)."
  {:malli/schema [:=> [:cat :seon.store/store] [:set :keyword]]}
  [store]
  (into #{} (filter #(str/starts-with? (name %) "merge-")) (registry/branches store)))

(deftest ^{:seon.test/long "One chain world: a chain green at its start ends done, root's pass refuses its final merge on a red gate and holds a merge step, then two more passes; measured with the S3c-1 row's world." :seon.test/long-ms 30000}
  a-refused-final-merge-keeps-its-merge-branch-and-holds-a-merge-step
  ;; MERGE-STEP (`docs/seon/schedule/cards/MERGE-STEP.md`), rows i and iv: main moves after the chain forks so the
  ;; chain's proof is red on main's latest head; root's final merge refuses on its gate, keeps the merge branch it
  ;; built and appends ONE held merge step to the chain carrying the refusal and that branch; nothing starts it
  (binding [*space* "tq.mergestep"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (with-merge-template! connection)
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "u")}}])
                branch (:seon.chain/branch chain)
                [last-task] (:seon.task/handles chain)
                key (id/id [last-task 1])
                before (merge-branches store)]
            ;; main moves after the fork: the chain's proof is red on main's latest head, green on the chain branch
            (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                                :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                              (db/db connection) connection)
                                                :seon.batch/sink (seon.batch/sink)
                                                :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                                :seon.agent/id "tq-chain-author"})
                                  (symbol (str *space* "-proofs"))
                                  "(clojure.test/deftest proves-u (clojure.test/is (= 1 2)))\n")
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                (is (= :done (await-on! on-branch #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                           [?a :seon.task.attempt/end ?e]]
                                                         (db/db on-branch) key)
                                        ::chain-done))
                    "the chain's last task, green at its start, ends done")
                (pass! handle routing)
                (let [kept (into #{} (remove before) (merge-branches store))
                      chain-db (db/db on-branch)
                      step (get-in (db/pull chain-db [{:seon.task/next [:seon.task/id]}] [:seon.task/id last-task])
                                   [:seon.task/next :seon.task/id])
                      held (when step (db/pull chain-db [:seon.task/execution :seon.task/branch :seon.task/refusal
                                                         :seon.task/proofs :seon.task/instructions :seon.task/plan]
                                               [:seon.task/id step]))]
                  (is (= 1 (count kept)) "the refused merge keeps the one merge branch it built")
                  (is (string? step) "root appends a task after the chain's last task")
                  (is (= {:seon.task/execution :held :seon.task/branch (first kept) :seon.task/proofs #{(proof "u")}}
                         (select-keys held [:seon.task/execution :seon.task/branch :seon.task/proofs]))
                      "the merge step is held, names the kept merge branch and its proofs are the tests the gate found red")
                  (is (qualified-keyword? (:seon.task/refusal held)) "it carries the refusal's type")
                  (is (= "Do c." (:seon.task/plan held)) "the last task's plan flows to it")
                  (is (= :closed (:seon.task/outcome (db/pull chain-db [:seon.task/outcome] [:seon.task/assignment key])))
                      "the refused terminal attempt is closed as a bail")
                  ;; row iv: nothing starts it
                  (pass! handle routing)
                  (pass! handle routing)
                  (is (and step (empty? (db/q '[:find ?a :in $ ?h :where [?t :seon.task/id ?h] [?a :seon.task/attempt-of ?t]]
                                              (db/db on-branch) step)))
                      "no attempt of the merge step starts by itself over repeated passes")
                  (is (= kept (into #{} (remove before) (merge-branches store))) "and its merge branch stays")))
              (finally
                (doseq [kept (remove before (merge-branches store))]
                  (registry/retire-branch! {:seon.store/store store :seon.store/branch kept}))
                (release-chain! routing store branch))))))))))

(deftest ^{:seon.test/long "One chain world: a branch forked from main and a chain whose task names it, its attempt started by one db<->flow pass." :seon.test/long-ms 30000}
  a-task-naming-a-branch-starts-its-attempt-there
  ;; TASK-CHAIN §2.2 as MERGE-STEP amends it: a task selects its context by data; root starts the attempt of a task
  ;; carrying `:seon.task/branch` on that branch (its agent homed and its graph running there), and a task naming none
  ;; on its chain's branch (`root-starts-a-chains-first-task-on-its-branch`)
  (binding [*space* "tq.taskbranch"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                main (db/db connection)
                elsewhere :tq-elsewhere
                forked (registry/fork! {:seon.store/store store :seon.store/branch elsewhere :seon.db/db main
                                        :seon.branch/target (get-in main [:config :branch])
                                        :seon.cluster.registry/from (get-in main [:config :branch])})
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "u")} :seon.task/branch elsewhere}])
                branch (:seon.chain/branch chain)
                key (id/id [(first (:seon.task/handles chain)) 1])
                agent-id (str "attempt/" key)]
            (try
              (is (not (:seon.error/at forked)) "the named branch exists")
              (pass! handle routing)
              (let [there (db/branch-database (db/db connection) elsewhere)]
                (is (= elsewhere (:seon.task/branch (db/pull there [:seon.task/branch] [:seon.task/assignment key])))
                    "the attempt's row names the task's branch, on that branch")
                (is (= elsewhere (:seon.agent/branch (db/pull there [:seon.agent/branch] [:seon.agent/id agent-id])))
                    "its agent is homed on the task's branch"))
              (is (some? (some-> (get-in @routing [:seon.agent/children elsewhere]) deref (get-in [:seon.agent/started agent-id])))
                  "its graph runs on the task's branch")
              (is (nil? (some-> (get-in @routing [:seon.agent/children branch]) deref (get-in [:seon.agent/started agent-id])))
                  "not on the chain's branch")
              ;; its agent writes its end where it works; root reads it there (`seon.task/attempt-rows`)
              (let [there (registry/active-branch-connection {:seon.store/store store :seon.store/branch elsewhere})]
                (is (= :done (await-on! there #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                      [?a :seon.task.attempt/end ?e]]
                                                    (db/db there) key)
                                        ::elsewhere-done))
                    "the attempt, green at its start, ends done on the task's branch")
                (is (= :done (some #(when (= key (:seon.task/assignment %)) (:seon.task.attempt/end %))
                                   (task/chain-attempts (db/db connection) store)))
                    "root reads that end on the branch its row names"))
              (finally
                (release-chain! routing store elsewhere)
                (release-chain! routing store branch))))))))))

(deftest ^{:seon.test/long "One chain world: a refused final merge holds a merge step; root runs it, its scripted turn repairs the merge branch, and root's pass merges that branch; about eight db<->flow passes." :seon.test/long-ms 60000}
  a-done-merge-step-merges-its-branch-onto-main
  ;; MERGE-STEP row iii: root runs the held merge step, whose proofs are the test the gate found red; its agent, on the merge branch, makes the test green there, ends done, and root's
  ;; pass takes that attempt, read where its row names, to the final merge of the merge branch
  (binding [*space* "tq.mergedone"]
    (test-support/with-database
     (fn [connection]
       (fixed-chain-world
        connection ["u"] {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (with-merge-template! connection)
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "u")}}])
                branch (:seon.chain/branch chain)
                [last-task] (:seon.task/handles chain)
                key (id/id [last-task 1])
                before (merge-branches store)
                context {:seon.db/connection connection :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                         :my.program/base-ctx (:seon.sci.eval/ctx handle) :seon.store/store store}
                end-of (fn [on k] (await-on! on #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                         [?a :seon.task.attempt/end ?e]]
                                                       (db/db on) k)
                                             ::ended))]
            (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                                :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                              (db/db connection) connection)
                                                :seon.batch/sink (seon.batch/sink)
                                                :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                                :seon.agent/id "tq-chain-author"})
                                  (symbol (str *space* "-proofs"))
                                  "(clojure.test/deftest proves-u (clojure.test/is (= 1 2)))\n")
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                (is (= :done (end-of on-branch key)) "the chain's last task ends done")
                (pass! handle routing)
                (let [step (get-in (db/pull (db/db on-branch) [{:seon.task/next [:seon.task/id]}] [:seon.task/id last-task])
                                   [:seon.task/next :seon.task/id])
                      kept (:seon.task/branch (db/pull (db/db on-branch) [:seon.task/branch] [:seon.task/id (or step "")]))
                      step-key (id/id [step 1])]
                  (is (keyword? kept) "the refused merge holds a merge step on its kept merge branch")
                  (is (= :running (:seon.task/execution ((requiring-resolve 'my.root/run!) {:my.program/context context :seon.task/assigned step})))
                      "root runs the held merge step")
                  (pass! handle routing)
                  (let [there (registry/active-branch-connection {:seon.store/store store :seon.store/branch kept})]
                    (is (= :done (end-of there step-key)) "its agent repairs the merge branch and ends done there"))
                  (pass! handle routing)
                  ;; this world's cluster names no checkout that holds the repaired proof's file, so the merge refuses at
                  ;; the write-back root (`seon.cluster.source/root-refusal`), after the done rule passed on the merge
                  ;; branch: the merge itself is shown with a checkout in `seon.cluster.merge-test`
                  (is (str/starts-with? (str (:seon.task/reason (db/pull (db/db on-branch) [:seon.task/reason] [:seon.task/assignment step-key])))
                                        "The final merge refused it, :seon.source/write-back-error")
                      "root's pass judges the step done on its merge branch and merges that attempt")))
              (finally
                (doseq [kept (remove before (merge-branches store))]
                  (release-chain! routing store kept))
                (release-chain! routing store branch))))))))))

(deftest ^{:seon.test/long "One chain world: a refused final merge holds a merge step, root reruns the step's predecessor, which ends done again, and root's pass merges it again; about six db<->flow passes." :seon.test/long-ms 60000}
  a-rerun-predecessor-of-a-held-merge-step-merges-and-the-step-stays-held
  ;; MERGE-STEP, S5 run 14: a held merge step starts only by root's run!. Root reruns the task before it; that task's
  ;; done is a final merge again (a new merge branch from main's latest head), never an advance into the held step
  (binding [*space* "tq.mergererun"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (with-merge-template! connection)
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "u")}}])
                branch (:seon.chain/branch chain)
                [last-task] (:seon.task/handles chain)
                before (merge-branches store)
                context {:seon.db/connection connection :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                         :my.program/base-ctx (:seon.sci.eval/ctx handle) :seon.store/store store}
                end-of (fn [on k] (await-on! on #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                         [?a :seon.task.attempt/end ?e]]
                                                       (db/db on) k)
                                             ::ended))]
            (test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                                :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                              (db/db connection) connection)
                                                :seon.batch/sink (seon.batch/sink)
                                                :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                                :seon.agent/id "tq-chain-author"})
                                  (symbol (str *space* "-proofs"))
                                  "(clojure.test/deftest proves-u (clojure.test/is (= 1 2)))\n")
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                (is (= :done (end-of on-branch (id/id [last-task 1]))) "the chain's last task ends done")
                (pass! handle routing)
                (let [step (get-in (db/pull (db/db on-branch) [{:seon.task/next [:seon.task/id]}] [:seon.task/id last-task])
                                   [:seon.task/next :seon.task/id])
                      rerun (id/id [last-task 2])]
                  (is (string? step) "the refused merge holds a merge step")
                  (is (= 2 (:seon.task.attempt/number ((requiring-resolve 'my.root/run!) {:my.program/context context :seon.task/assigned last-task})))
                      "root reruns the step's predecessor")
                  (pass! handle routing)
                  (is (= :done (end-of on-branch rerun)) "the rerun ends done")
                  (pass! handle routing)
                  (pass! handle routing)
                  (let [chain-db (db/db on-branch)]
                    (is (= :held (:seon.task/execution (db/pull chain-db [:seon.task/execution] [:seon.task/id step])))
                        "the held merge step stays held")
                    (is (empty? (db/q '[:find ?a :in $ ?h :where [?t :seon.task/id ?h] [?a :seon.task/attempt-of ?t]] chain-db step))
                        "and starts no attempt")
                    (is (str/starts-with? (str (:seon.task/reason (db/pull chain-db [:seon.task/reason] [:seon.task/assignment rerun])))
                                          "The final merge refused it")
                        "the rerun's done is a final merge again: main's proof is still red, so it refuses"))))
              (finally
                (doseq [kept (remove before (merge-branches store))]
                  (registry/retire-branch! {:seon.store/store store :seon.store/branch kept}))
                (release-chain! routing store branch))))))))))

(deftest ^{:seon.test/long "One chain world: a refused final merge holds a merge step; main is fixed and merged forward into the merge branch, root runs the step and it ends done at its start; about six db<->flow passes." :seon.test/long-ms 60000}
  a-merge-step-green-at-its-start-ends-done-with-no-model-call
  ;; S5 merge step attempt 2 (2026-10-15): root fixed main, merged main forward into the kept merge branch and ran the
  ;; step; its proof (the merge gate's deftest) was not the refused test, so nothing at its start ran that test, the
  ;; step opened a model turn with no provider and stalled. Its proofs are the tests the gate found red: green at its
  ;; start, it ends done with no model call, and root's pass merges its branch
  (binding [*space* "tq.mergegreen"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (with-merge-template! connection)
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "u")}}])
                branch (:seon.chain/branch chain)
                [last-task] (:seon.task/handles chain)
                before (merge-branches store)
                context {:seon.db/connection connection :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                         :my.program/base-ctx (:seon.sci.eval/ctx handle) :seon.store/store store}
                author #(test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                                            :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                                          (db/db connection) connection)
                                                            :seon.batch/sink (seon.batch/sink)
                                                            :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                                            :seon.agent/id "tq-chain-author"})
                                              (symbol (str *space* "-proofs"))
                                              (str "(clojure.test/deftest proves-u (clojure.test/is (= 1 " % ")))\n"))
                end-of (fn [on k] (await-on! on #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                         [?a :seon.task.attempt/end ?e]]
                                                       (db/db on) k)
                                             ::ended))]
            (author 2)
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                (is (= :done (end-of on-branch (id/id [last-task 1]))) "the chain's last task ends done")
                (pass! handle routing)
                (let [step (get-in (db/pull (db/db on-branch) [{:seon.task/next [:seon.task/id]}] [:seon.task/id last-task])
                                   [:seon.task/next :seon.task/id])
                      kept (:seon.task/branch (db/pull (db/db on-branch) [:seon.task/branch] [:seon.task/id (or step "")]))
                      step-key (id/id [step 1])]
                  (is (keyword? kept) "the refused merge holds a merge step on its kept merge branch")
                  ;; root fixes main and merges main forward into the merge branch
                  (author 1)
                  (is (not (:seon.error/at ((requiring-resolve 'my.root/merge-main!) {:my.program/context context :seon.chain/branch kept})))
                      "root merges main forward into the merge branch")
                  ((requiring-resolve 'my.root/run!) {:my.program/context context :seon.task/assigned step})
                  (pass! handle routing)
                  (let [there (registry/active-branch-connection {:seon.store/store store :seon.store/branch kept})]
                    (is (= :done (end-of there step-key)) "green at its start, the merge step ends done")
                    (is (zero? (model-turns (db/db there) (str "attempt/" step-key))) "with no model turn"))))
              (finally
                (doseq [kept (remove before (merge-branches store))]
                  (release-chain! routing store kept))
                (release-chain! routing store branch))))))))))

(deftest ^{:seon.test/long "One chain world: an attempt whose proofs cannot be run, its prove pass and two db<->flow passes." :seon.test/long-ms 30000}
  an-attempt-whose-proofs-never-ran-ends-stalled-and-bails
  ;; S5 merge step attempt 3 (2026-10-15): the start prove pass's run could not happen (a branch test whose
  ;; namespace no longer loads), so its proof had no result, no model turn opened and no end was written: the
  ;; attempt waited silently. Every end that is not done bails to root (TASK-CHAIN §2.7): an attempt that took no turn
  ;; and has nothing to open is stalled. Its proof here is undefined, which opens a model turn
  ;; (`a-pending-proof-opens-the-first-model-turn`); with no provider in this world that turn fails and it stalls
  (binding [*space* "tq.neverproved"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(symbol (str *space* "-proofs") "proves-nothing-defined")}}])
                branch (:seon.chain/branch chain)
                key (id/id [(first (:seon.task/handles chain)) 1])]
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                (is (= :stalled (await-on! on-branch #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                              [?a :seon.task.attempt/end ?e]]
                                                            (db/db on-branch) key)
                                           ::never-proved))
                    "an attempt whose proofs never ran ends stalled instead of waiting")
                (pass! handle routing)
                (is (= :closed (:seon.task/outcome (db/pull (db/db on-branch) [:seon.task/outcome] [:seon.task/assignment key])))
                    "and root bails it"))
              (finally (release-chain! routing store branch))))))))))

(deftest ^{:seon.test/long "One chain world: a chain whose branch defines a function, its refused final merge, a merge step after main is fixed and merged forward, and root's merge of the step; about six db<->flow passes." :seon.test/long-ms 60000}
  a-merge-steps-merge-carries-the-chains-declarations
  ;; S5 (2026-10-15): the merge step's merge was recorded :merged while main received none of the chain's program.
  ;; The merge branch had no fork entity (`seon.cluster/candidate-gate!` made it with `branch!`), so after root's
  ;; forward merge its own delta walked from main's t and missed the chain's changes written before it. A merge step's
  ;; merge carries the chain's declarations, or it is not recorded :merged
  (binding [*space* "tq.mergecarry"]
    (test-support/with-database
     (fn [connection]
       ;; the chain's own change: its agent's one scripted turn defines a function and the proof reaching it
       (test-support/with-provider
        (atom []) (test-support/reply-queue [(str "(in-ns '" *space* "-proofs)\n"
                                                  "(defn chain-made {:malli/schema [:=> [:cat] :int]} [] 7)\n"
                                                  "(clojure.test/deftest proves-made (clojure.test/is (= 7 (chain-made))))")])
        (fn [server]
       (chain-world
        connection {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          ;; a checkout the merge proves its files against: an empty directory, so the chain's new namespace is a new file
          (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                          :seon.config/manifest (assoc (test-support/provider-config server) :seon.config.ai/no-provider false
                                                       :seon.config.fs/working-root
                                                       (str (java.nio.file.Files/createTempDirectory
                                                             "tq-mergecarry" (make-array java.nio.file.attribute.FileAttribute 0))))})
          (with-merge-template! connection)
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                main-author #(test-support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                                                                 :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                                               (db/db connection) connection)
                                                                 :seon.batch/sink (seon.batch/sink)
                                                                 :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                                                 :seon.agent/id "tq-chain-author"})
                                                   (symbol (str *space* "-proofs")) %)
                ;; the chain's second proof exists, red, when it forks: its agent's turn makes it green
                _ (main-author "(clojure.test/deftest proves-made (clojure.test/is (= 1 2)))\n")
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Do c."
                                     :seon.task/proofs #{(proof "u") (proof "made")}}])
                branch (:seon.chain/branch chain)
                [last-task] (:seon.task/handles chain)
                before (merge-branches store)
                made (symbol (str *space* "-proofs") "chain-made")
                context {:seon.db/connection connection :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                         :my.program/base-ctx (:seon.sci.eval/ctx handle) :seon.store/store store}
                author (fn [on source]
                         (test-support/define! (world/world {:seon.db/connection on :seon.boot/cluster-name "tq-chain"
                                                             :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db on))
                                                                                                           (db/db on) on)
                                                             :seon.batch/sink (seon.batch/sink)
                                                             :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                                             :seon.agent/id "tq-chain-author"})
                                               (symbol (str *space* "-proofs")) source))
                end-of (fn [on k] (await-on! on #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                         [?a :seon.task.attempt/end ?e]]
                                                       (db/db on) k)
                                             ::ended))]
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})
                    _ (is (= :done (end-of on-branch (id/id [last-task 1]))) "the chain's last task ends done, its change made")
                    ;; main moves so the chain's proof is red on main's head
                    _ (author connection "(clojure.test/deftest proves-u (clojure.test/is (= 1 2)))\n")
                    _ (pass! handle routing)
                    step (get-in (db/pull (db/db on-branch) [{:seon.task/next [:seon.task/id]}] [:seon.task/id last-task])
                                 [:seon.task/next :seon.task/id])
                    kept (:seon.task/branch (db/pull (db/db on-branch) [:seon.task/branch] [:seon.task/id (or step "")]))
                    step-key (id/id [step 1])]
                (is (keyword? kept) "the refused merge (its gate: a REPL declaration has no file here) holds a merge step")
                (author connection "(clojure.test/deftest proves-u (clojure.test/is (= 1 1)))\n")
                (is (not (:seon.error/at ((requiring-resolve 'my.root/merge-main!) {:my.program/context context :seon.chain/branch kept})))
                    "root merges main forward into the merge branch")
                ((requiring-resolve 'my.root/run!) {:my.program/context context :seon.task/assigned step})
                (pass! handle routing)
                (let [there (registry/active-branch-connection {:seon.store/store store :seon.store/branch kept})]
                  (is (= :done (end-of there step-key)) "green at its start, the merge step ends done"))
                (pass! handle routing)
                (let [main (db/db connection)
                      merged? (= :merged (:seon.task/outcome (db/pull main [:seon.task/outcome] [:seon.task/assignment step-key])))
                      carried? (some? (:db/id (db/pull main [:db/id] [:seon.fn/sym made])))]
                  (is (or (not merged?) carried?)
                      "main never records the merge step :merged without the chain's declaration")))
              (finally
                (doseq [kept (remove before (merge-branches store))]
                  (release-chain! routing store kept))
                (release-chain! routing store branch))))))))))))

(deftest ^{:seon.test/long "One chain world: a chain task whose one proof no deftest defines yet, a scripted model turn that defines it green, over two db<->flow passes." :seon.test/long-ms 30000}
  a-pending-proof-opens-the-first-model-turn
  ;; Live run 2026-10-15 (chain-10d1fd5956a9, attempt e86970ee8841): a task whose proof the agent is to write ended
  ;; :stalled with no turn, its provider on. A proof no deftest defines yet is red by its absence: work the model
  ;; must do, so it opens the first turn as a red proof does (AGENTS.md: a chain agent's first turn comes from its
  ;; task's red proofs)
  (binding [*space* "tq.pendingproof"]
    (test-support/with-database
     (fn [connection]
       (fixed-chain-world
        connection ["c"] {"u" [#{} "(clojure.test/is (= 1 1))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                   [{:seon.task/ns (symbol *space*) :seon.task/name "c" :seon.task/plan "Write and pass c's proof."
                                     :seon.task/proofs #{(proof "c")}}])
                branch (:seon.chain/branch chain)
                key (id/id [(first (:seon.task/handles chain)) 1])]
            (try
              (pass! handle routing)
              (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
                (is (= :done (await-on! on-branch #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                           [?a :seon.task.attempt/end ?e]]
                                                         (db/db on-branch) key)
                                        ::pending-proof))
                    "the model's turn defines the pending proof green and the task ends done")
                (is (= 1 (model-turns (db/db on-branch) (str "attempt/" key))) "after one model turn"))
              (finally (release-chain! routing store branch))))))))))
