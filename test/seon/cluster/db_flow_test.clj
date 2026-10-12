(ns seon.cluster.db-flow-test
  "db<->flow's decision, `seon.cluster.agent/plan`, as a table: facts read on
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
            [seon.merge :as merge]
            [seon.batch]
            [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.fn]
            [seon.program]
            [seon.id :as id]
            [seon.profile :as profile]
            [seon.sci.eval :as sci.eval]
            [seon.task :as task]
            [seon.test-support :as test-support]
            [seon.turn :as turn]
            [seon.turn.world :as world]))

(defn- facts
  "Plan facts over `overrides`: nothing startable, nothing open, a running cluster."
  {:malli/schema [:=> [:cat :map] :seon.flow.plan/facts]}
  [overrides]
  (merge {:seon.flow.plan/startable #{} :seon.flow.plan/invalid #{} :seon.flow.plan/open []
          :seon.flow.plan/attempts-made {} :seon.flow.plan/relaunch {} :seon.flow.plan/created {}
          :seon.flow.plan/cluster :running}
         overrides))

(defn- plan-registry
  "A registry holding `started` graphs, `paused` among them."
  {:malli/schema [:function [:=> [:cat] :seon.flow.plan/registry]
                  [:=> [:cat :seon.flow.plan/started :seon.flow.plan/paused] :seon.flow.plan/registry]]}
  ([] (plan-registry #{} #{}))
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
    (let [[attempt start :as actions] (agent/plan (facts {:seon.flow.plan/startable #{a}}) (plan-registry) 4)]
      (is (= [[:attempt a] [:start-flow a]] (ops actions)))
      (is (= {:seon.task/assignment key-a :seon.task.attempt/number 1 :seon.agent/id agent-a
              :seon.task/branch (keyword (str "attempt-" key-a)) :seon.flow.plan/fork-point :main}
             (select-keys attempt [:seon.task/assignment :seon.task.attempt/number :seon.agent/id
                                   :seon.task/branch :seon.flow.plan/fork-point])))
      (is (= :running (:seon.task/execution start)))))
  (testing "two passes over the same facts plan the same attempt, so the second write is a no-op"
    (is (= (agent/plan (facts {:seon.flow.plan/startable #{a}}) (plan-registry) 4)
           (agent/plan (facts {:seon.flow.plan/startable #{a}}) (plan-registry) 4))))
  (testing "once written and started, nothing"
    (is (= [] (agent/plan (facts {:seon.flow.plan/startable #{a} :seon.flow.plan/open [(open :running)]})
                          (plan-registry #{agent-a} #{}) 4))))
  (testing "a restart: the open attempt with no graph is started again, never attempted again"
    (is (= [[:start-flow a]]
           (ops (agent/plan (facts {:seon.flow.plan/startable #{a} :seon.flow.plan/open [(open :running)]})
                            (plan-registry) 4)))))
  (testing "an attempt after earlier ones is numbered after the attempts made"
    (is (= (id/id [a 3])
           (:seon.task/assignment (first (agent/plan (facts {:seon.flow.plan/startable #{a}
                                                             :seon.flow.plan/attempts-made {a 2}})
                                                     (plan-registry) 4))))))
  (testing "a relaunched leaf's attempt and flow fork its predecessor's end commit"
    (let [commit (random-uuid)]
      (is (= [commit commit]
             (mapv :seon.flow.plan/fork-point (agent/plan (facts {:seon.flow.plan/startable #{a}
                                                                  :seon.flow.plan/relaunch {a commit}})
                                                          (plan-registry) 4))))))
  (testing "max-running counts open attempts: ten ready under four start the four oldest"
    (let [handles (mapv #(format "%012d" %) (range 10))
          created (zipmap handles (range 100 0 -10))
          attempts (filter (comp #{:attempt} :seon.flow.plan/op)
                           (agent/plan (facts {:seon.flow.plan/startable (set handles) :seon.flow.plan/created created})
                                       (plan-registry) 4))]
      (is (= (take 4 (reverse handles)) (map :seon.task/assigned attempts)))
      (is (= [] (filter (comp #{:attempt} :seon.flow.plan/op)
                        (agent/plan (facts {:seon.flow.plan/startable (set handles)
                                            :seon.flow.plan/open (mapv #(assoc (open :running) :seon.task/assigned %
                                                                               :seon.agent/id (str "attempt/" %))
                                                                       (take 4 handles))})
                                    (plan-registry (set (map #(str "attempt/" %) (take 4 handles))) #{}) 4))))))
  (testing "an invalid running leaf is a problem, never an attempt"
    (is (= [[:problem b] [:attempt a] [:start-flow a]]
           (ops (agent/plan (facts {:seon.flow.plan/startable #{a} :seon.flow.plan/invalid #{b}}) (plan-registry) 4))))))

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
    (is (= expected (mapv (juxt :seon.flow.plan/op :seon.task/execution)
                          (agent/plan (facts {:seon.flow.plan/open [(open intent)] :seon.flow.plan/cluster cluster})
                                      (plan-registry started paused) 4)))
        "each intent and registry state plans exactly its Flow verb")))

(deftest
  each-ended-assignment-derives-its-lifecycle-from-its-facts
  (let [c1 (random-uuid) c2 (random-uuid)
        held (fn [facts] (merge {:db/id 1 :seon.task/assignment key-a :seon.task/assigned a :seon.agent/id agent-a
                                 :seon.task/branch (keyword (str "attempt-" key-a)) :seon.task/merge-base c1
                                 :seon.flow.plan/head c1 :seon.flow.plan/started? false :seon.flow.plan/kept? false}
                                facts))]
    (doseq [[facts expected why]
            [[{:seon.flow.plan/head c2 :seon.flow.plan/started? true} [] "running: no end anywhere"]
             [{:seon.task.attempt/end :done :seon.flow.plan/head c2 :seon.flow.plan/started? true} [:stop :merge]
              "ended on its branch: the graph stops, then the branch merges"]
             [{:seon.task.attempt/end :done :seon.flow.plan/main-end :done :seon.flow.plan/head c1} [:delete-branch]
              "root merged it: deleted"]
             [{:seon.task.attempt/end :done :seon.flow.plan/main-end :done} [:delete-branch] "merged: deleted"]
             [{:seon.task.attempt/end :done :seon.flow.plan/main-end :done :seon.flow.plan/publish? true} [:publish]
              "merged declarations main has not published: published before the branch is deleted"]
             [{:seon.task.attempt/end :done :seon.flow.plan/main-end :done :seon.flow.plan/kept? true} [] "merged and kept"]
             [{:seon.task.attempt/end :done :seon.flow.plan/main-end :done :seon.flow.plan/head c2} [:merge]
              "written after its merge: merged again"]
             [{:seon.task.attempt/end :sent-back :seon.task/merge-base nil :seon.flow.plan/head c2} [:merge-base :merge]
              "no merge base yet: written first"]
             [{:seon.task.attempt/end :done :seon.flow.plan/main-end :done :seon.task/merge-base nil :seon.flow.plan/head c2}
              [:delete-branch] "copied to main before merge bases existed"]
             [{:seon.task.attempt/end :capped :seon.flow.plan/head c2 :seon.flow.plan/started? true} [:stop :close]
              "an end that merges nothing: written on main"]
             [{:seon.task.attempt/end :capped :seon.flow.plan/main-end :capped :seon.flow.plan/head c2} [] "and kept"]
             [{:seon.task.attempt/end :error :seon.flow.plan/main-end :error :seon.flow.plan/head c2} []
              "a failed merge's :error end stops the lifecycle"]]]
      (is (= expected (mapv :seon.flow.plan/op (agent/next-actions [(into {} (remove (comp nil? val)) (held facts))])))
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
                                                                       (str "#:seon.task{:name \"" task-name "\" :intent \"Do " task-name ".\""
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

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt 'seon.context/a-proof-green-before-the-work-is-not-done}}
  a-task-satisfied-at-the-base-opens-no-model-turn
  ;; the prove pass runs the proof at the base program: green there, the step has nothing to do
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
          (is (= :satisfied (:seon.task/status verdict)) "its proof was green at its fork point")
          (is (= #{(proof "g")} (:seon.context/green-before verdict)) "and names it")
          (is (nil? (turn/next-agent-work branch-db {:seon.agent/id agent-id})) "no model turn opens")
          (is (zero? (model-turns branch-db agent-id)) "none was taken")
          (is (= :satisfied (await-on! on-branch #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]
                                                         (db/db on-branch) key)
                                       ::g-ended))
              "it ends satisfied, never done"))))))))

(deftest a-prove-step-whose-product-main-holds-closes-satisfied-and-its-successor-forks-main
  ;; the product, the successor's proof red by design, is on main before the Prove step forks: nothing to do
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
          (is (= :satisfied end) "the Prove step ends satisfied")
          (is (zero? (model-turns (db/db on-branch) agent-id)) "with no model call")
          (pass! handle routing)
          (is (= :closed (db/q '[:find ?o . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task/outcome ?o]] (db/db connection) key))
              "main closes it without a merge")
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
                (is (= 1 (count (db/q '[:find ?error :where [?error :seon.error/signature]] (db/db connection))))
                    "the refused merge is the only fault"))))))))))

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
  "The transactions on main `database` that merged `branch`'s facts or wrote its end, as transaction ids."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch] [:set :int]]}
  [database branch]
  (set (db/q '[:find [?tx ...] :in $ ?b :where [?tx :seon.squash/source-branch ?b]] database branch)))

(deftest
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
                      [tx :as txs] (seq (copies database branch))]
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
                  (is (not (contains? (:seon.flow.plan/started (agent/registry-facts routing)) agent-id))
                      "its graph is stopped"))
                (pass! handle routing)
                (is (= 1 (count (copies (db/db connection) branch))) "a second pass merges nothing")
                (if keep?
                  (is (contains? (registry/branches store) branch) "the dial keeps the ended branch for audit")
                  (is (eventually #(not (contains? (registry/branches store) branch)))
                      "with no merge request the branch is deleted"))
                (is (empty? (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection)))
                    "with no fault recorded"))))))))))

(deftest
  a-merged-assignment-whose-graph-survived-a-crash-is-stopped-and-deleted
  ;; root merged the end (its merge base is the branch head) and the process went down before the graph stopped:
  ;; the next pass derives the stop and the deletion from the facts alone
  (binding [*space* "tq.crashmerged"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"e" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)]
            (pass! handle routing)
            (let [[key branch agent-id] (end-on-branch! handle connection "e" :done)]
              (db/with-branch {:seon.db/connection connection}
                              #(merge/merge! {:seon.db/connection connection :seon.store/store store} key))
              (is (contains? (:seon.flow.plan/started (agent/registry-facts routing)) agent-id) "the graph survived the merge")
              (pass! handle routing)
              (is (not (contains? (:seon.flow.plan/started (agent/registry-facts routing)) agent-id))
                  "one pass stops it")
              (is (eventually #(not (contains? (registry/branches store) branch))) "and deletes its branch")
              (is (= "Refined on the branch." (:seon.task/intent (db/pull (db/db connection) [:seon.task/intent]
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
                intent #(:seon.task/intent (db/pull (db/db connection) [:seon.task/intent] [:seon.task/id (chain-handle "e")]))]
            (pass! handle routing)
            (let [[key branch] (end-on-branch! handle connection "e" :given-back)
                  problem #(db/pull (db/db connection) [:seon.task/intent]
                                    [:seon.task/sym (symbol "seon.task" (str "attempt-ended-" key))])]
              (pass! handle routing)
              (is (= #{[key branch (str "attempt/" key) :given-back]} (attempts connection "e")) "main holds the end")
              (is (= "Do e." (intent)) "and no refinement")
              (is (contains? (registry/branches store) branch) "the branch stays")
              (is (str/includes? (str (:seon.task/intent (problem))) (name branch))
                  "root's problem task names the branch")
              (is (str/includes? (str (:seon.task/intent (problem))) "The proof asks the impossible.")
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
                  intent #(str (:seon.task/intent (db/pull (db/db connection) [:seon.task/intent]
                                                           [:seon.task/sym (symbol "seon.task" (str "attempt-ended-" key))])))]
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

(deftest ^{:seon/tasks #{'seon.cluster.agent/a-requested-green-end-merges-and-publishes-with-no-hand-act}}
  a-requested-end-whose-files-no-checkout-holds-merges-nothing
  ;; db<->flow merges a requested green end like any done end; this world's cluster names no checkout that
  ;; holds the proofs' file, so the gate refuses before acceptance: nothing reaches main but the :error end
  (binding [*space* "tq.endcustody"]
    (test-support/with-database
     (fn [connection]
       (fixed-chain-world
        connection ["g"] {"g" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)]
            (pass! handle routing)
            (let [[[key branch agent-id]] (seq (attempts connection "g"))
                  a (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/assignment key]))
                  child (get-in @routing [:seon.agent/children branch])
                  graph (get-in @child [:seon.agent/started agent-id :seon.flow/executor])
                  _ (landed! handle connection "g" ::g-ended)
                  database (db/db connection)
                  head (db/branch-database database branch)]
              (is (not= (db/commit-id head) (:seon.task/merge-base (db/pull database [:seon.task/merge-base] a)))
                  "its base did not move")
              (is (= {:seon.task.attempt/end :error}
                     (select-keys (db/pull database [:seon.task.attempt/end :seon.task/outcome] a) [:seon.task.attempt/end :seon.task/outcome]))
                  "the refused merge is the assignment's :error end, not an outcome")
              (let [intents (db/q '[:find [?i ...] :in $ ?k :where [?t :seon.task/sym ?s] [(namespace ?s) ?n] [(= ?n "seon.task")]
                                    [?t :seon.task/intent ?i] [(clojure.string/includes? ?i ?k)]]
                                  database key)]
                (is (= 1 (count intents)) "the refusal is delivered to root as one problem task naming the assignment")
                (is (every? #(clojure.string/includes? % ":seon.source/write-back-error") intents) "which names the refusal's type"))
              (is (contains? (registry/branches (agent/handle-store handle)) branch) "its branch stays")
              (is (.isTerminated ^java.util.concurrent.ExecutorService graph) "its graph was stopped and joined")
              (is (= 1 (count (db/q '[:find ?e :where [?e :seon.error/signature]] database)))
                  "and the refusal is one fault for root")))))))))

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
                declaration (fn [database] (task/declaration (db/carried-projection database) (db/pull database '[*] [:seon.task/id h])))
                refine! (fn [conn agent-id intent]
                          (task/create! {:seon.db/connection conn :seon.agent/id agent-id :seon.task/ns (symbol *space*)
                                         :seon.task/name "r" :seon.task/intent intent
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
                                                             :seon.task/intent "A child of mine."}))))
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
                  base (:seon.task/merge-base (db/pull main [:seon.task/merge-base] [:seon.task/assignment key-c]))]
              (is (= 1 (count cs)) "both needs merged: c is assigned")
              (is (= (registry/base-commit-id main branch-c) base)
                  "its merge base is the commit its branch forked at")
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
              (is (not (contains? (:seon.flow.plan/started (agent/registry-facts routing)) agent-id)) "the graph is stopped")
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
                                       [(name ?s) ?n] [(clojure.string/starts-with? ?n "attempt-ended-")]]
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
                started #(:seon.flow.plan/started (agent/registry-facts routing))]
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
             (is (= 1 (count @ledger)) "after the one model call that wrote it"))))))))))

(deftest a-prove-step-admitted-by-its-successor-opens-a-model-turn
  ;; q is a :prove step with no proof of its own: admission starts it because i needs it (`plan-facts`), so its
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
           ;; z, already green, only gives the world a program to define; satisfied, it takes no model turn
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
        (fn [_]
          (let [observed (agent/plan-facts (db/db connection))]
            (is (= (set (map chain-handle ["p" "q"])) (:seon.flow.plan/startable observed))
                "Prove starts with own proofs or a successor declaration")
            (is (= (set (map chain-handle ["r" "s"])) (:seon.flow.plan/invalid observed))
                "Prove without either and Implement without proofs cannot start"))))))))

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
    (test-support/transacted! connection
                              [[:db/add [:seon.task/assignment key-p] :seon.task.attempt/end :done]
                               [:db/add [:seon.task/assignment key-p] :seon.task.attempt/commit (db/commit-id (db/db p-connection))]])
    (pass! handle routing)
    key-p))

(deftest ^{:seon.test/long "Two chain worlds, each one Prove attempt and one gate (a nested seon.test/run on a fresh merge branch) over two db<->flow passes."
           :seon.test/long-ms 120000}
  a-prove-steps-red-product-merges-into-main
  (doseq [[space stub designed?] [["tq.pvdesign" "(throw (ex-info \"not yet\" {:seon.error/type :seon.error/unimplemented}))" true]
                                  ["tq.pvsetup" "(throw (ex-info \"boom\" {}))" false]]]
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
              (if designed?
                (testing "a red by an unimplemented stub, tagged with the step after it, is the Prove step's product"
                  (is (= :merged (:seon.task/outcome (db/pull database [:seon.task/outcome] [:seon.task/assignment key-p])))
                      "one pass merges the Prove step")
                  (is (held? :seon.test/sym (symbol (str space "-rows") "twice-doubles")) "main holds its red deftest")
                  (is (held? :seon.fn/sym (symbol (str space "-rows") "twice")) "and its contracted stub"))
                (testing "a red by any other error is no design: nothing of the branch merges"
                  (is (not= :merged (:seon.task/outcome (db/pull database [:seon.task/outcome] [:seon.task/assignment key-p]))))
                  (is (not (held? :seon.test/sym (symbol (str space "-rows") "twice-doubles"))) "main lacks the deftest")
                  (is (empty? (attempts connection "i")) "and the step after it waits")))))))))))

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
                base (:seon.task/merge-base (db/pull main [:seon.task/merge-base] [:seon.task/assignment key-i]))
                i-connection (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch-i})]
            (is (= :done p-end) "the Prove step ended itself: its product is red by design")
            (is (= :merged (outcome key-p)) "the Prove step's designed reds merge as its product")
            (is (= 1 (count assigned)) "its successor is assigned once it merged")
            (is (= (registry/base-commit-id main branch-i) base) "forked from main, its merge base")
            (is (seq (db/datoms (db/commit-database main base) :avet :seon.test/sym (symbol rows "twice-doubles")))
                "whose commit holds the Prove step's red deftest")
            (let [[before] (get-in (context/current-state (db/db i-connection) agent-i) [:seon.context/chain :seon.context/before])]
              (is (= :merged (:seon.task/outcome before)) "the Implement block names the step before it as merged")
              (is (some #{(symbol rows "twice-doubles")} (map :seon.ref/name (get-in before [:seon.context/merged :seon.context/tests])))
                  "with the tests it merged"))
            ;; the Implement step's work: the stub's body, then its end at that commit
            (define-on! i-connection (symbol rows) "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))")
            (test-support/transacted! connection
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
                problem #(:seon.task/intent (db/pull (db/db connection) [:seon.task/intent]
                                                     [:seon.task/sym (symbol "seon.task" (str "attempt-ended-" %))]))]
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
              (let [first-row (db/pull (db/db connection) [:seon.task.attempt/end :seon.task.attempt/reason :seon.task.attempt/relaunch]
                                       [:seon.task/assignment key-1])]
                (is (= :error (:seon.task.attempt/end first-row)) "the refused merge ends the attempt")
                (is (str/includes? (str (:seon.task.attempt/reason first-row)) ":seon.source/uncontracted-error")
                    "naming the refusal")
                (is (string? (:seon.task.attempt/relaunch first-row)) "and relaunches it, as a capped end with work")
                (is (nil? (problem key-1)) "with no problem task for root"))
              (pass! handle routing)
              (let [[[key-2 branch-2 agent-2]] (seq (remove #(= key-1 (first %)) (attempts connection "u")))
                    on-2 (when branch-2 (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch-2}))
                    step (when on-2 (:seon.context/step (context/current-state (db/db on-2) agent-2)))]
                (is (string? key-2) "the second attempt starts")
                (is (= {:seon.task/status :working :seon.context/uncontracted [doubled]}
                       (select-keys step [:seon.task/status :seon.context/uncontracted]))
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
  (db/pull database [:seon.task/merge-base :seon.task.attempt/commit :seon.task.attempt/relaunch] [:seon.task/assignment key]))

(deftest a-capped-attempt-with-changes-relaunches-from-its-commit
  (binding [*space* "tq.relaunch"]
    (test-support/with-database
     (fn [connection]
       (chain-world
        connection {"e" [#{} "(clojure.test/is (= 1 2))"]}
        (fn [handle]
          (let [routing (:seon.agent/routing handle)
                store (agent/handle-store handle)
                problem #(:seon.task/intent (db/pull (db/db connection) [:seon.task/intent]
                                                     [:seon.task/sym (symbol "seon.task" (str "attempt-ended-" %))]))
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
                (is (= (:seon.task/merge-base first-row) (:seon.task/merge-base (relaunch-row main key-2)))
                    "keeping the first attempt's merge base")
                (is (= (:seon.task/merge-base first-row) (registry/base-commit-id main branch-2))
                    "its branch's merge base is the first attempt's, so its merge takes both attempts' changes")
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
