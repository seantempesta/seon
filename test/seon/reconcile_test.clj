(ns seon.reconcile-test
  "(actions-for db), index then actions, answers exactly the expected actions for a table of task graphs: needs
  DAGs, max-running, held and cancelled tasks, environment changes, a previous root's
  work, the end of an agent's work judged once by the done rule (`judgment`) and stored on its task, dead agents."
  (:require [seon.lib] [seon.test-store :as ts] [seon.profile :as profile] [clojure.test :refer [deftest is are testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.task :as task] [seon.doc]
            [seon.reconcile :as r] [my.root] [my.test]
            [seon.template :as tpl] [seon.problems :as problems] [seon.merge :as pm] [seon.sock :as sock] [clojure.edn] [seon.contract :as contract]
            [clojure.string :as str] [clojure.set] [seon.config :as cfg] [seon.edn :as sedn]
            [seon.program :as program] [seon.writer :as writer] [seon.source :as source] [clojure.java.shell :as sh] [seon.turn :as turn] [clojure.core.async :as a] [seon.cost :as cost] [seon.reader :as reader]))

(defn- empty-db [] (ts/test-db))
(defn- booted-tx
  "Root's boot fact on a test's main, with the one config key the reconciler reads, max-running n (the config in force, seon.task/config-tx, holds the rest)."
  [boot n] (into (task/boot-tx boot "sean") (seon.task/config-tx {:seon.config.agent/max-running n} {})))


(defn- actions-for
  "The actions that move db toward its desired state: index, the wakes of the agents with keys :woken (a message reached each: `r/wake`), then actions."
  [db & {:keys [woken]}] (r/actions (reduce #(r/wake %1 db %2) (r/index db) woken)))

(defn- decl [nm & {:keys [needs proofs model]}]
  (cond-> {:seon.task/ns 'demo :seon.task/name nm :seon.task/plan (str "Make " nm " true.")
           :seon.task/proofs (or proofs #{(symbol "demo-test" (str nm "-test"))})}
    needs (assoc :seon.task/needs (set (map #(str "demo/" %) needs)))
    model (assoc :seon.task/model model)))

(def diamond [(decl "a") (decl "b" :needs ["a"]) (decl "c" :needs ["a"]) (decl "d" :needs ["b" "c"])])

(defn- work
  "Task nm's work as root records it (ts/work-tx): its outcome and its agent a-<nm> of boot (a merge task's agent is on its
  work task's branch). Options: :boot (\"boot-1\"), :model, :members (more :seon.task/* facts its judging wrote)."
  [nm outcome & {:keys [boot model members] :or {boot "boot-1"}}]
  (let [id (str "demo/" nm)
        br (task/default-branch (str "demo/" (str/replace nm #"-merge$" "")))]
    (ts/work-tx id outcome {:boot boot :agent (keyword (str "a-" nm)) :branch br :model model :members members
                            :max-turns (cfg/value :seon.config.agent/max-turns)})))

(defn- akey "The key of task nm's agent in boot-1." [nm] (str "a-" nm))

(defn- st-of
  "A reconciler proc state over main db: its index, no flight (or flights, by task id)."
  [db & {:keys [flights]}]
  {:db db :idx (r/index db) :boot "boot-1" :rid 0 :pending {} :flights (or flights {})})

(defn- ended
  "db with task nm's agent's own end written on it, as its branch holds it (seon.agent/write-end!): end's members
  (:outcome :reason :error :waste) under :seon.agent/*, naming the turn the end came on (:turn, 1) as the agent's turn now
  unless :ended-turn says an earlier one (a wake that died before it wrote its own)."
  [db nm end & {:keys [turn ended-turn] :or {turn 1}}]
  (d/db-with db [(into {:seon.agent/key (akey nm) :seon.agent/turn turn :seon.agent/ended-turn (or ended-turn turn)}
                       (for [[k v] end] [(keyword "seon.agent" (name k)) v]))]))

(defn- judge!
  "Root judges task nm's end on main db (`#'r/judge-tx`): end is what its agent wrote on its branch (nil: none), results root's
  own proof results, diff the writer's branch read (members over empty ones). The branch is main (or :branch) with :on-branch
  (tx-data) and the end written on it; the flight's other members are options. A run that worked by default: its diff changed one key
  (:ours, written on the branch as a var; else the done rule's :no-change) and it ended at its turn (:ended-turn, else it gets no refine). Answers {:db main after the judging :outcome :tx}."
  [db nm end & {:keys [results diff deferred run-problems worked-error turn ended-turn branch on-branch] :or {turn 1}}]
  (let [id (str "demo/" nm)
        ;; the work the writer's :ours names, on the branch as content, which root's judging measures (seon.merge/changed?); none when :ours is []
        branch (cond-> (or branch db) on-branch (d/db-with on-branch) (not= [] (:ours diff)) (d/db-with [{:var/name 'demo/worked}])
                 end (ended nm end :turn turn :ended-turn ended-turn))
        {:keys [tx outcome]} (#'r/judge-tx (st-of db) {:act {:op :spawn :task id :env {:branch (task/default-branch id)}} :deferred deferred
                                                    :run-problems run-problems :worked-error worked-error}
                                        (or results {})
                                        ;; :ours as the writer's branch read gives it: the worked key and the tasks the branch changed ([:task id] keys)
                                        (merge {:db branch :base db :base-t (:max-tx db) :uncontracted [] :unmerged [] :caused [] :theirs 0
                                                :ours (into [[:var 'demo/worked]] (filter #(= :task (first %))) (keys (pm/delta-changes (:base diff db) branch #{})))}
                                               diff))]
    {:db (d/db-with db tx) :outcome outcome :tx tx}))

(defn- judgment-of
  "What `r/judgment` decides for task nm of db with facts (the measured ones it is not given are none, as seon.reconcile/judge's are)."
  {:malli/schema [:=> [:cat :seon.host/db :string :map] :map]}
  [db nm facts] (r/judgment (r/index db) (str "demo/" nm) (merge {:results {} :uncontracted #{} :unmerged [] :caused #{} :deferred #{}} facts)))

(defn- merged [nm] (work nm :merged))
(defn- spawn [nm & {:keys [model]}]
  {:op :spawn :task (str "demo/" nm)
   :env {:branch (task/default-branch (str "demo/" nm)) :model model :max-turns (cfg/value :seon.config.agent/max-turns)}})
(defn- t [nm] (symbol "demo-test" (str nm "-test")))
(def conflict-f {:key [:var 'app/f] :base {:src "(defn f [x] x)"}
                 :ours {:src "(defn f [x] (inc x))"} :theirs {:src "(defn f [x] (dec x))"}})

(defn- mdb
  "A main db of decls with their merge tasks (template/with-merge), all released."
  [decls & {:keys [extra max-running] :or {max-running 4}}]
  (let [db (empty-db)
        ds (tpl/with-merge decls)
        db (d/db-with db (task/create-tx db ds))
        db (-> db (d/db-with (task/execution-tx (mapv #(task/task-id 'demo (:seon.task/name %)) ds) :running))
               (d/db-with (booted-tx "boot-1" max-running)))]
    (cond-> db (seq extra) (d/db-with extra))))

(defn- note
  "The writer's notice for db, a commit of a test branch, that follows prev (main's t before it)."
  [db prev ops] {:t (:max-tx db) :prev prev :ops ops :commit (d/commit-id db) :ns 0})

(defn- mspawn [nm] {:op :spawn :task (str "demo/" nm)
                    :env {:branch (task/default-branch (str "demo/" (str/replace nm #"-merge$" ""))) :model nil :max-turns (cfg/value :seon.config.agent/max-turns)}})

(defn- observed-steps
  "Fold the steps ([what tx-data]) over db0: after each, the index observed from the one before, as a diff of the two dbs and as
  a commit's ops, is the index of the db listed. Answers the last db and the indexes of every step."
  [db0 steps]
  (reduce (fn [[db idx idxs] [what tx]]
            (let [{db' :db-after txd :tx-data} (d/with db tx)
                  ops (for [dt txd] [(if (:added dt) :db/add :db/retract) (:e dt) (:a dt) (:v dt)])
                  listed (r/index db')]
              (testing what
                (is (= listed (r/index idx db db')) "a diff")
                (is (= listed (r/observe idx db' ops)) "a commit's ops"))
              [db' listed (conj idxs listed)]))
          [db0 (r/index db0) []] steps))

(defn- db-of
  "A main db: decls written, the ids in `released` released (all when :all), root's boot,
  then extra tx-data (runs, execution changes)."
  [decls & {:keys [released max-running extra] :or {released :all max-running 4}}]
  (let [db (empty-db)
        db (d/db-with db (task/create-tx db decls))
        ids (if (= :all released) (map #(task/task-id 'demo (:seon.task/name %)) decls) released)]
    (cond-> (-> db (d/db-with (task/execution-tx (vec ids) :running))
                (d/db-with (booted-tx "boot-1" max-running)))
      (seq extra) (d/db-with extra))))

;; Estimate: 3 transacts or db-withs x 50k ~ 200,000 ticks
(deftest ^{:seon.test/estimate {:ticks 200000}} who-launched-a-task-hears-how-it-ends
  ;; docs/agent.md#working-for-a-person: every end tells them, since a launched agent that died can't say so itself
  (let [db (ts/test-db)
        id "demo/child"
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "child" :seon.task/kind :whole :seon.task/plan "p"}]))
        asked (d/db-with db [{:seon.agent/key "p" :seon.agent/branch :task-parent}
                             {:seon.task/id id :seon.task/started-by [:seon.agent/key "p"]}])
        told (fn [db to & more] (r/tell-tx db id (into [[:db/cas [:seon.task/id id] :seon.task/outcome :running to]] more) 1000))]
    (is (= [[:seon.agent/key "p"]] (:seon.message/to (first (told asked :merged)))) "the message goes to the agent that launched it")
    (is (re-find #"merged to main" (:seon.message/body (first (told asked :merged)))))
    (is (re-find #"its proof went red" (:seon.message/body (first (told asked :closed {:seon.task/failure {:reason "its proof went red"}})))) "a stop says why")
    (is (re-find #"waiting" (:seon.message/body (first (told asked :parked {:seon.task/reason "which colour?"})))))
    (is (empty? (told asked :running)) "a task still working tells nobody")
    (is (empty? (told db :merged)) "and one no agent launched tells nobody")
    (is (not= (:seon.message/id (first (told asked :parked)))
              (:seon.message/id (first (told (d/db-with asked [{:seon.task/id id :seon.task/plan "q"}]) :parked))))
        "parking again is a new message, which wakes it again")))

;; Estimate: 2 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} task-state-is-what-happened
  ;; the one derivation of a task's observed state, from the index
  (let [db (db-of diamond :extra (into (merged "a") (concat (work "b" :done) (work "c" :running))))
        idx (r/index db)]
    (is (= {"demo/a" :merged "demo/b" :done "demo/c" :running "demo/d" :blocked}
           (into {} (for [id ["demo/a" "demo/b" "demo/c" "demo/d"]] [id (r/task-state idx id)]))))
    (is (= :held (r/task-state (r/index (db-of diamond :released [])) "demo/a")) "never released")))

;; Estimate: 5 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} needs-dag-starts-in-dependency-order
  (are [db expected] (= expected (actions-for db))
    ;; nothing done: only the root of the diamond can start
    (db-of diamond) [(spawn "a")]
    ;; a merged: b and c start, d waits for both
    (db-of diamond :extra (merged "a")) [(spawn "b") (spawn "c")]
    ;; b merged, c not yet: d still waits
    (db-of diamond :extra (concat (merged "a") (merged "b"))) [(spawn "c")]
    ;; b and c merged: d starts
    (db-of diamond :extra (concat (merged "a") (merged "b") (merged "c"))) [(spawn "d")]
    ;; all merged: nothing to do
    (db-of diamond :extra (mapcat merged ["a" "b" "c" "d"])) []))

;; Estimate: 4 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} max-running-caps-parallel-agents
  (let [three [(decl "x") (decl "y") (decl "z")]]
    (are [db expected] (= expected (actions-for db))
      (db-of three :max-running 1) [(spawn "x")]
      (db-of three :max-running 2) [(spawn "x") (spawn "y")]
      ;; x running: one slot left of two
      (db-of three :max-running 2 :extra (work "x" :running)) [(spawn "y")]
      ;; two running: none left
      (db-of three :max-running 2 :extra (concat (work "x" :running) (work "y" :running))) [])))

;; Estimate: 3 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} nothing-autoruns-held-and-cancelled
  (let [three [(decl "x") (decl "y") (decl "z")]]
    (are [db expected] (= expected (actions-for db))
      ;; written tasks are held
      (db-of three :released []) []
      ;; only the released one starts
      (db-of three :released ["demo/y"]) [(spawn "y")]
      ;; cancelled after release
      (db-of three :released ["demo/y"] :extra (task/execution-tx ["demo/y"] :cancelled)) [])))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 4 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 transacts or db-withs x 50k ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} cancel-kills-and-blocks-dependents
  (let [db (db-of diamond :extra (concat (merged "a") (work "b" :running) (work "c" :running)
                                         (task/execution-tx ["demo/b"] :cancelled)))]
    (is (= [{:op :kill :task "demo/b" :reason :cancelled}] (actions-for db)))
    (is (= #{"demo/b" "demo/c"} (r/blocked-by (r/index db) "demo/d")))
    ;; killed: nothing restarts it, and d stays blocked once c merges
    (let [db (d/db-with db (concat (task/move-tx "demo/b" :running :killed {})
                                   (task/move-tx "demo/c" :running :merged {})))]
      (is (= [] (actions-for db)))
      (is (= #{"demo/b"} (r/blocked-by (r/index db) "demo/d"))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} held-kills-without-restart
  (let [db (db-of [(decl "x")] :extra (concat (work "x" :running) (task/execution-tx ["demo/x"] :held)))]
    (is (= [{:op :kill :task "demo/x" :reason :held}] (actions-for db)))))

;; Estimate: 5 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 5 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 6,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 6000000}} an-outside-agent-is-not-killed-for-the-tasks-own-model
  ;; task seon.system/an-outside-agent-is-a-first-class-worker (gap 5): attach starts an agent whose env
  ;; says model "outside" and leaves the task's model alone, so the task's next agent is its own
  (let [db (db-of [(decl "x" :model "m1")] :extra (work "x" :running :model "outside"))]
    (is (= [] (actions-for db)) "a live outside agent of a task of another model is not an env change"))
  (let [db (db-of [(decl "x" :model "m1")] :extra (concat (work "x" :running :model "outside")
                                                          (task/env-tx "demo/x" {:model "m2"})))]
    (is (= [] (actions-for db)) "nor when the task's model changes: the agent is the outside agent's"))
  (let [db (db-of [(decl "x" :model "m1")] :extra (work "x" :killed :model "outside"))]
    (is (= [(spawn "x" :model "m1")] (actions-for db)) "its next agent is the task's own model, not outside"))
  ;; a root restart lost an attached task: nothing drives it until someone attaches, and a process here would race that attach (seon.server's attach!)
  (let [db (db-of [(decl "x" :model "m1")] :extra (work "x" :lost :model "outside"))]
    (is (= [] (actions-for db)) "a lost outside task waits for its attach"))
  (let [db (db-of [(decl "x" :model "m1")] :extra (work "x" :running :model "outside" :boot "boot-0"))]
    (is (= [{:op :reap :task "demo/x"}] (actions-for db)) "a reap is the task's one action this pass")))

;; Estimate: 2 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 3 transacts or db-withs x 50k ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} root-takes-a-running-task-back-and-releases-it-again
  ;; task seon.system/an-outside-agent-is-a-first-class-worker (gap 6): my.root/hold! pauses a running
  ;; task (cancel! is final); run! releases it and its next agent continues the branch
  (let [db (db-of [(decl "x") (decl "y")] :extra (concat (work "x" :running) (task/execution-tx ["demo/y"] :held)))
        {:keys [taken tx not-running]} (my.root/take-back-tx db ["demo/x" "demo/y" "demo/none"] "taken back for a hand repair")
        held (d/db-with db tx)]
    (is (= ["demo/x"] taken) "only the running task")
    (is (= :held (:seon.task/execution (task/task held "demo/x"))))
    (is (= {:reason "taken back for a hand repair"} (:seon.task/failure (task/task held "demo/x"))))
    (is (= #{["demo/y" :held] ["demo/none" nil]} (set (map (juxt :task :execution) not-running))) "the others are answered, not written")
    (is (= [{:op :kill :task "demo/x" :reason :held}] (actions-for held)) "the reconciler kills the live agent and keeps the task")
    (let [ended (d/db-with held (task/move-tx "demo/x" :running :killed {}))
          released (d/db-with ended (vec (task/execution-tx ["demo/x"] :running)))]
      (is (= [(spawn "x")] (actions-for released)) "released again, its next work is a new agent's start on the same task")))
  (let [db (db-of [(decl "z")] :extra (work "z" :merged))
        {:keys [taken not-running]} (my.root/take-back-tx db ["demo/z"] "x")]
    (is (= [] taken) "a task that is done is not taken back: merged work keeps :running")
    (is (= ["task demo/z is done, not running: nothing to take back"] (map :seon.error/message not-running)))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 transacts or db-withs x 50k ~ 600,000 ticks
(deftest ^{:seon.test/estimate {:ticks 600000}} a-cancel-never-makes-a-task
  ;; 2026-10-28: a bare name given as a task's id (ns/name) was upserted alone, an entity with no
  ;; namespace, name or plan, and every reader of tasks refused it at root's next boot
  (let [db (db-of [(decl "x")])
        {:keys [tx unknown]} (my.root/cancel-tx db ["demo/x" "x" "demo/none"])
        after (d/db-with db tx)]
    (is (= :cancelled (:seon.task/execution (task/task after "demo/x"))) "the task that exists is cancelled")
    (is (= #{"x" "demo/none"} (set (map :task unknown))) "each id that names no task is answered")
    (is (= [:seon.system/unknown-task :seon.system/unknown-task] (mapv :seon.error/type unknown)))
    (is (nil? (pdb/eid after :seon.task/id "x")) "and no task is made for it")
    (is (nil? (pdb/eid after :seon.task/id "demo/none")))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 transacts or db-withs x 50k ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} environment-change-restarts-clean
  (let [db (db-of [(decl "x" :model "m1")] :extra (concat (work "x" :running :model "m1")
                                                          (task/env-tx "demo/x" {:model "m2"})))]
    (is (= [{:op :kill :task "demo/x" :reason :env-changed}] (actions-for db)))
    (let [db (d/db-with db (task/move-tx "demo/x" :running :killed {}))]
      (is (= [(spawn "x" :model "m2")] (actions-for db))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-previous-roots-work-is-reaped-and-resumed
  (let [db (db-of [(decl "x") (decl "y" :needs ["x"])] :extra (work "x" :running :boot "boot-0"))]
    (is (= [{:op :reap :task "demo/x"}] (actions-for db)) "one action a task: its flight is one, and the spawn is the next pass's, once the reap has written :lost")
    (is (= #{} (:live (r/index db))))))

;; A lone work task, which nothing on its branch needs, merges itself (seon.task/merges?): its green end is :ready, the merge's
;; to make, where a work task its merge task follows is :done (a-green-work-task-judged-behind-is-done-and-its-merge-runs)
;; Estimate: 2 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 2 judgings x 0.7M (the index and the judge-tx) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} ended-work-is-judged-by-the-done-rule
  ;; the decision is made once, where an end's facts are in hand (`judgment`, pure), and stored on the task
  (let [db (db-of diamond :extra (work "a" :running))
        j (fn [facts] (judgment-of db "a" facts))
        red [{:reason :proof-red :symbol 'demo-test/a-test}]]
    (are [facts expected] (= expected (j facts))
      ;; green: a work task is done on its branch (b's red proof is b's own)
      {:end {:outcome :green} :results {(t "a") :pass (t "b") :fail}} {:outcome :ready :reasons []}
      ;; its proof red: the agent is told (a verdict, which wakes it)
      {:end {:outcome :red} :results {(t "a") :fail}} {:outcome :told :reasons red :verdict red}
      ;; out of turns with its proof red: it stays, at its cap (the budget is the agent's), never held
      {:end {:outcome :cap} :results {(t "a") :fail}} {:outcome :cap :reasons red :hold "it used all its turns with its work not done"}
      ;; waiting with its proof red: parked, never held (design agent-loop section 1)
      {:end {:outcome :waiting} :results {(t "a") :fail}} {:outcome :parked :reasons red}
      ;; a provider's error with its proof red: held with its cause, never parked
      {:end {:outcome :error} :results {(t "a") :fail}} {:outcome :closed :reasons red :hold "ended error: proof-red demo-test/a-test"}
      ;; its proof never ran: unknown, which refuses done as red does
      {:end {:outcome :green} :results {}} {:outcome :told :reasons [{:reason :proof-unknown :symbol 'demo-test/a-test}]
                                           :verdict [{:reason :proof-unknown :symbol 'demo-test/a-test}]}
      ;; a stray red: a red test that is no task's proof
      {:end {:outcome :green} :results {(t "a") :pass 'demo-test/other :error}}
      {:outcome :told :reasons [{:reason :stray-red :symbol 'demo-test/other}] :verdict [{:reason :stray-red :symbol 'demo-test/other}]}
      ;; its agent died
      {:died {:seon.error/type :seon.agent/died}} {:outcome :dead :reasons [] :hold "its agent died"})
    (testing "b needs a, so b carries a's proof (accumulated along :needs): red there is b's own red"
      (let [db (db-of diamond :extra (concat (merged "a") (work "b" :running)))]
        (is (= {:outcome :told :reasons red :verdict red}
               (judgment-of db "b" {:end {:outcome :green} :results {(t "a") :fail (t "b") :pass}})))
        (is (= [(spawn "c")] (actions-for db)) "and c, which needs a only, starts beside it")))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 judgings x 0.7M (the index and the judge-tx) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-done-counts-only-the-reds-its-change-reaches
  ;; docs/tasks.md#the-done-rule: base holds demo-test/other red and the branch's change does not reach it (main-red:
  ;; its digest is base's, or base's verdict at it is red), the task's proof green: the task is done; the same red
  ;; reached by the change (not main's) holds it, and a main red that the task names as a proof still holds it
  (let [db (db-of diamond :extra (work "a" :running))
        j (fn [facts] (judgment-of db "a" (merge {:end {:outcome :green} :results {(t "a") :pass 'demo-test/other :error}} facts)))]
    (is (= {:outcome :ready :reasons []} (j {:main-red #{'demo-test/other}})) "main's red is not this task's")
    (is (= :told (:outcome (j {:main-red #{}}))) "reached by the change: it holds the task")
    (is (= [{:reason :stray-red :symbol 'demo-test/other}] (:reasons (j {:main-red #{'demo-test/x}}))) "another test's main red excuses nothing")
    (is (= [{:reason :proof-red :symbol (t "a")}]
           (:reasons (j {:results {(t "a") :fail} :main-red #{(t "a")}}))) "a proof the task names always counts")))

;; docs/merging.md#a-branch-lands-with-binland: whether a task is the merge step of another is read from its needs and its branch (the
;; task needs one on its own branch), never its kind: the :merging mark, the done rule's behind and told, what a park keeps, the
;; start's one-merge-at-a-time and the cites a need leaves out each follow it. A :work task with such a need is a merge step; a :merge
;; task with none is not.
(def ^:private step-world
  "Tasks planted for the merge step's proofs: w, its merge step w-merge (its kind says :work, it needs w on w's branch), f (its kind
  says :merge, it needs nothing) and l, a lone task."
  (let [step (assoc (decl "w-merge" :needs ["w"]) :seon.task/kind :work :seon.task/branch (task/default-branch "demo/w"))
        fake (assoc (decl "f") :seon.task/kind :merge)]
    {:step step :fake fake :decls [(decl "w") step fake (decl "l")]}))

;; Estimate: 4 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 4 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 judgings x 0.7M (the index and the judge-tx) ~ 6,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 6000000}} a-merge-step-is-known-by-its-needs-and-branch
  (let [{:keys [decls step]} step-world
        j (fn [db nm facts] (judgment-of db nm facts))]
    (testing "the :merging mark: the step while it works, a task that merges itself once judged, a :merge kind with no need never while it works"
      (let [db (db-of decls :extra (concat (work "w" :done) (work "w-merge" :running) (work "f" :running) (work "l" :ready)))]
        (is (= #{"demo/w-merge" "demo/l"} (:merging (r/index db))))))
    (testing "a step added later takes its need's merge from it: the index observed holds what the index listed does"
      (let [db0 (db-of [(decl "w")] :extra (work "w" :ready))
            [db _ idxs] (observed-steps db0 [["the step is written" (task/create-tx db0 [step])]
                                             ["and released" (task/execution-tx ["demo/w-merge"] :running)]])]
        (is (= #{"demo/w"} (:merging (r/index db0))) "w, judged, merges itself while nothing follows it")
        (is (= #{} (:merging (first idxs))) "then the step, which merges it, is the merge in flight")))
    (testing "a need that moves to another branch: its step is a step no longer, in the index observed as in the index listed"
      (let [db0 (db-of [(decl "w") step] :extra (concat (work "w" :ready) (work "w-merge" :running)))
            [db _ idxs] (observed-steps db0 [["w moves to another branch" [{:seon.task/id "demo/w" :seon.task/branch :main/elsewhere}]]])]
        (is (= #{"demo/w-merge"} (:merging (r/index db0))) "before: the step merges w while it works")
        (is (= (:merging (r/index db)) (:merging (first idxs))) "the mark observed is the mark listed")
        (is (not (contains? (:merging (first idxs)) "demo/w-merge")) "and the task that needed w on its branch no longer merges it")))
    (testing "green work is done when a step follows it on its branch, ready when it merges itself"
      (let [db (db-of decls :extra (concat (work "w" :running) (work "l" :running)))
            green {:end {:outcome :green} :results {(t "w") :pass (t "l") :pass}}]
        (is (= {:outcome :done :reasons []} (j db "w" green)) "w is followed by a step on its branch")
        (is (= {:outcome :ready :reasons []} (j db "l" green)) "a lone task merges itself")))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 4 judgings x 0.7M (the index and the judge-tx) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-merge-step-keeps-no-process-and-holds-where-a-task-that-merges-itself-is-told
  (let [{:keys [decls]} step-world
        db (db-of decls :extra (concat (work "w-merge" :running) (work "f" :running)))
        waiting (fn [nm] {:end {:outcome :waiting} :results {(t nm) :fail}})
        red (fn [nm] {:end {:outcome :red} :results {(t nm) :fail}})]
    (is (not= :parked (:outcome (judgment-of db "w-merge" (waiting "w-merge")))) "the step keeps no process")
    (is (= :parked (:outcome (judgment-of db "f" (waiting "f")))) "the :merge kind with no need to merge does")
    (is (= :closed (:outcome (judgment-of db "w-merge" (red "w-merge")))) "the step's red holds for root")
    (is (= :told (:outcome (judgment-of db "f" (red "f")))) "the other is told")))

;; Estimate: 2 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} merges-start-one-at-a-time-for-a-merge-step-read-from-data
  (let [{:keys [fake]} step-world
        spawned #(set (map :task (filter (comp #{:spawn} :op) (actions-for %))))
        step-of (fn [nm] (assoc (decl (str nm "-merge") :needs [nm]) :seon.task/kind :work :seon.task/branch (task/default-branch (str "demo/" nm))))]
    (is (= 1 (count (spawned (db-of [(decl "a") (step-of "a") (decl "b") (step-of "b")] :extra (concat (work "a" :done) (work "b" :done)))))) "two steps, one starts")
    (is (= #{"demo/f" "demo/g"} (spawned (db-of [fake (assoc (decl "g") :seon.task/kind :merge)]))) "two tasks of kind :merge with no need, both")))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 2 transacts or db-withs x 50k ~ 600,000 ticks
(deftest ^{:seon.test/estimate {:ticks 600000}} a-need-that-is-a-merge-step-leaves-its-cites-out-of-the-tasks
  (let [{:keys [step fake]} step-world
        db0 (d/db-with (empty-db) [{:seon.doc/address "docs/vector.md#the-pyramid" :seon.doc/heading "The pyramid"}
                                   {:seon.doc/address "docs/agent.md#working-a-task" :seon.doc/heading "Working a task"}])
        ds [(decl "w") (assoc step :seon.task/plan "Read [it](docs/vector.md#the-pyramid).")
            (assoc fake :seon.task/plan "Read [it](docs/agent.md#working-a-task).")
            (assoc (decl "x" :needs ["w-merge" "f"]) :seon.task/plan "Nothing cited.")]
        db (d/db-with db0 (task/create-tx db0 ds))]
    (is (= ["docs/agent.md#working-a-task"] (mapv task/cited-name (task/task-cites db "demo/x"))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 judgings x 0.7M (the index and the judge-tx) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} an-agent-with-no-end-records-no-fake-spend
  ;; task seon.model/budgets-are-data-and-spend-is-measured: the agent wrote no end (it died
  ;; or was killed before write-end!), so its turns, tokens and time are unknown: absent, never 0
  (let [db (db-of diamond :extra (work "a" :running))
        {:keys [db outcome]} (judge! db "a" nil)
        tk (task/task db "demo/a")]
    (is (= :closed outcome))
    (is (= "the agent wrote no end" (:seon.task/reason tk)) "said so")
    (is (not-any? #(contains? tk %) [:seon.task/waste :seon.task/costs :seon.task/failures]) "and nothing measured of it is recorded")))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 3 judgings x 0.7M (the index and the judge-tx) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} an-ends-words-the-judgment-does-not-share-are-named-as-its-agents
  ;; docs/reconcile.md#a-task-holds-how-its-work-ended: Context's loop D3, a task capped saying "proof-unknown ..." while root,
  ;; reading its proof green at its digest, found nothing open: the sentence stood as the task's reason beside root's outcome
  ;; and misled whoever read it. Named as its agent's, beside what root found; words root agrees with stay as they are
  (let [db (db-of [(decl "a")] :extra (work "a" :running))
        reason #(:seon.task/reason (task/task (:db %) "demo/a"))]
    (is (= "its agent ended cap saying \"proof-unknown demo-test/a-test\"; the done rule found nothing open"
           (reason (judge! db "a" {:outcome :cap :reason "proof-unknown demo-test/a-test"} :results {(t "a") :pass}))))
    (is (= "proof-red demo-test/a-test" (reason (judge! db "a" {:outcome :red :reason "proof-red demo-test/a-test"} :results {(t "a") :fail})))
        "a red end root agrees with keeps its words")
    (is (= "all green" (reason (judge! db "a" {:outcome :green :reason "all green"} :results {(t "a") :pass}))) "a green end keeps its words")))

(defn- fn-tx
  "A recorded definition of fn app.gaps/nm (as seon.program/record-tx writes it), with its
  contract c or none."
  [db nm c]
  (program/record-tx db {:id (str "app.gaps/" nm (when c "-contracted")) :src (str "(defn " nm (when c (str " {:malli/schema " c "}")) " [x] x)")
                         :ns 'app.gaps :ns-form? false :defs [(cond-> {:var (symbol "app.gaps" nm) :meta #{:arglists}} c (assoc :contract c))]
                         :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}}))

;; Estimate: 1 test-branch! 1M + 3 fn-tx transacts 0.3M + 1 branch and connect 0.4M + 1 :branch-diff step ~1M + 1 :merge step ~2M + 1 db-of 0.5M + 1 judgment 0.7M + uncontracted over a delta ~1M ~ 7M ~ 7,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 7000000}} the-judge-and-the-merge-gate-agree-on-contract-gaps
  ;; one question, asked by the agent's done check (seon.turn), the writer's diff that root's
  ;; judge reads, and the merge gate: which functions the BRANCH adds without a contract. A
  ;; branch behind a main that fixed its own gap after the fork still holds main's old
  ;; uncontracted g; that gap is main's, not the branch's (the judge used to refuse it while
  ;; the gate took the branch). With contracts off (seon.contract/on? false, a process
  ;; setting) all three answer none: they ask the one function, seon.merge/uncontracted.
  (ts/test-branch!
   (fn [cfg conn]
     (let [c "[:=> [:cat :int] :int]"
           _ (d/transact conn (fn-tx @conn "g" nil))
           ab (pdb/on-main (:branch cfg) :gaps)
           _ (d/branch! conn (:branch cfg) ab)
           base @conn
           _ (d/transact conn (fn-tx @conn "g" c))
           bc (pdb/connect (assoc cfg :branch ab))]
       (try
         (d/transact bc (fn-tx @bc "u" nil))
         (let [w (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer (a/chan 4) :events (a/chan 4)})
               ask (fn [req] (:r (first (:to-api (second (#'writer/writer-proc w :in (assoc req :dest {:rid 1})))))))
               diff (ask {:op :branch-diff :branch ab})
               gate (ask {:op :merge :branch ab :proved-t (:max-tx @conn)})
               db (db-of [(decl "a")] :extra (work "a" :running))
               judged (judgment-of db "a" {:end {:outcome :green} :results {(t "a") :pass} :uncontracted (set (:uncontracted diff))})]
           (is (= #{'app.gaps/u} (set (pm/uncontracted (pm/delta-changes base @bc #{})))) "the agent's done check")
           (is (= '[app.gaps/u] (:uncontracted diff)) "the writer's diff")
           (is (= [{:reason :contract-gap :symbol 'app.gaps/u}] (:reasons judged)) "root's judge: g is main's gap, fixed on main")
           (is (= [:seon.merge/uncontracted '[app.gaps/u]] [(:seon.error/type gate) (:uncontracted gate)])
               (str "the merge: main merged in first, so only the branch's own gap refuses it: " (pr-str gate))))
         (finally (d/release bc)))))))

;; docs/merging.md#a-merge-leaves-no-name-unbound: a merge is judged against what it merges into, so a removed definition another form
;; still calls is seen whichever side the call is on: (1) two branches, one removing f and one calling it, each passing alone: the
;; first merges, and the second, main merged into it, must not; (2) one branch removing f that main's unchanged g calls must not merge at all.
;; Read from the forms' recorded references (seon.merge/dangling): Jolt compiles x/f with no var as a lookup at each call, so a load would not see it
;; Estimate: 1 test-branch! 1M + 5 source/refresh-tx of 2-line files (~0.5M each = 2.5M) + 3 branches and connects (0.5M each = 1.5M) + 3 :merge steps (~10M each: main merged into the branch, its candidate judged, main's commit, as seon.procs-test/a-branch-merges-into-a-moved-main-in-one-writer-step measured 12.5M; the octopus step this replaced was ~2M, judging every member against one main) + 1 merge-candidate step ~2M ~ 37M ~ 40,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 40000000}} a-merge-leaves-no-name-unbound
  (ts/test-branch!
   (fn [cfg conn]
     (let [x "src/app/oct/x.clj" y "src/app/oct/y.clj"
           s "{:malli/schema [:=> [:cat] :int]}"
           x-with-f (str "(ns app.oct.x)\n(defn f " s " [] 1)\n(defn h " s " [] 2)\n")
           x-without-f (str "(ns app.oct.x)\n(defn h " s " [] 2)\n")
           y-calls-f (str "(ns app.oct.y (:require [app.oct.x :as x]))\n(defn g " s " [] (x/f))\n")
           on (fn [c files] (d/transact c (source/refresh-tx @c files)))
           branch (fn [nm] (let [b (pdb/on-main (:branch cfg) nm)] (d/branch! conn (:branch cfg) b) [b (pdb/connect (assoc cfg :branch b))]))
           writer (fn [] (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer (a/chan 4) :events (a/chan 4)}))
           ask (fn [w req] (:r (first (:to-api (second (#'writer/writer-proc w :in (assoc req :dest {:rid 1})))))))
           conns (atom [])]
       (try
         (on conn {x x-with-f})
         (let [[a ac] (branch :oct-a) [b bc] (branch :oct-b)]
           (swap! conns into [ac bc])
           (on ac {x x-without-f})
           (on bc {y y-calls-f})
           (let [w (writer)
                 ra (ask w {:op :merge :branch a :proved-t (:max-tx @conn)})
                 rb (ask w {:op :merge :branch b :proved-t (:max-tx @conn)})]
             (is (:merged? ra) (pr-str ra))
             (is (= :seon.merge/dangling (:seon.error/type rb)) (pr-str rb))
             (is (re-find #"app.oct.y/.* calls app.oct.x/f, which the merge removes" (str (:seon.error/message rb))) "the second is refused, told why")
             (is (nil? (d/entity @conn [:var/name 'app.oct.y/g])) "and main never held the call")))
         ;; main has f again, and g calling it; a branch removes f
         (on conn {x x-with-f y y-calls-f})
         (let [[c cc] (branch :oct-c)]
           (swap! conns conj cc)
           (on cc {x x-without-f})
           (let [w (writer)
                 gate (ask w {:op :merge-candidate :branch c})
                 rc (ask w {:op :merge :branch c :proved-t (:max-tx @conn)})]
             (is (= :seon.merge/dangling (:seon.error/type gate)) (pr-str gate))
             (is (= :seon.merge/dangling (:seon.error/type rc)) (pr-str rc))
             (is (= ['app.oct.x/f] (mapv :ref (:dangling rc))) "naming what main's g still calls")))
         (finally (run! d/release @conns)))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M) ~ 500,000 ticks
(deftest ^{:seon.test/estimate {:ticks 500000}} an-agent-without-an-end-reads-nil
  ;; no end written (a silent death) is nothing to read, not an empty end; an end of an earlier wake is none of this one's
  (let [db (db-of diamond :extra (work "a" :running))]
    (is (nil? (r/pull-end db (akey "a"))))
    (is (= {:outcome :green :reason "done" :ended-turn 2} (r/pull-end (ended db "a" {:outcome :green :reason "done"} :turn 2) (akey "a")))
        "an end names its turn")))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 3 judgings x 0.7M (the index and the judge-tx) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-wake-that-wrote-no-end-is-judged-so
  ;; each wake clears the end before it (seon.agent/clear-end!, seon.agent-test/a-wake-that-ran-no-turn-has-no-end): one that
  ;; died or threw silently has none on its branch, and is judged on what root measured, never on another wake's words
  (let [db (db-of diamond :extra (work "a" :running))
        none (judge! db "a" nil :results {(t "a") :pass})
        tk (task/task (:db none) "demo/a")]
    (is (= "the agent wrote no end" (:seon.task/reason tk)))
    (is (= :ready (:outcome none)) "the proofs root measured are green: the done rule holds whatever the agent wrote")
    (testing "with no end, red results are held, not told as a green end would be"
      (is (= :closed (:outcome (judge! db "a" nil :results {(t "a") :fail}))))
      (is (= :told (:outcome (judge! db "a" {:outcome :green :reason "all green"} :results {(t "a") :fail})))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 judgings x 0.7M (the index and the judge-tx); 1 transacts or db-withs x 50k ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-death-names-its-cause
  ;; task seon.agent/a-death-names-its-cause: a dead agent's task carries the end it wrote
  ;; on its branch before it exited (its cause), beside the death root observed (exit status);
  ;; a silent death (no end) is named by its exit; a turn loop that threw (the agent's REPL
  ;; answered the error, the process lives) is named by that error, not "wrote no end"
  (let [died (contract/error :seon.agent/died 'seon.system/api-proc "agent a1 died: exit 70" {:agent :a1 :exit {:exit 70}})
        cause (contract/error :seon.agent/eval-error 'seon.repl/serve! "boom" {:class "clojure.lang.ExceptionInfo"})
        db (db-of diamond :extra (work "a" :running))
        after (fn [tx] (task/task (d/db-with db tx) "demo/a"))]
    (testing "an end written before the death: its reason and cause, and the exit"
      (let [tk (after (#'r/dead-tx (st-of db) "demo/a" died {:outcome :error :reason "the agent's process died: boom" :error cause}))]
        (is (= :dead (:seon.task/outcome tk)))
        (is (= "the agent's process died: boom (agent a1 died: exit 70)" (:seon.task/reason tk)))
        (is (= "boom" (get-in tk [:seon.task/error :cause :seon.error/message])))
        (is (= {:exit 70} (:exit (:seon.task/error tk))))))
    (testing "a silent death: named by its exit"
      (let [tk (after (#'r/dead-tx (st-of db) "demo/a" (assoc died :exit {:signal 9} :seon.error/message "agent a1 died: signal 9") nil))]
        (is (= "agent a1 died: signal 9" (:seon.task/reason tk)))
        (is (= {:signal 9} (:exit (:seon.task/error tk))))))
    (testing "the turn loop threw and the agent lives: its error is the task's reason"
      (let [tk (task/task (:db (judge! db "a" nil :worked-error cause)) "demo/a")]
        (is (= "seon.turn/work! threw: boom" (:seon.task/reason tk)))
        (is (= "boom" (:seon.error/message (:seon.task/error tk))))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M) ~ 500,000 ticks
(deftest ^{:seon.test/estimate {:ticks 500000}} an-agent-dead-in-its-turn-loop-records-its-cause
  ;; root answers the turn loop's eval with the death itself (the one error map, no eval
  ;; item): the flight reads the branch's end next and keeps the death, exit and stderr,
  ;; rather than asking the dead agent for tests and recording "no live agent"
  (let [died (contract/error :seon.agent/died 'seon.system/api-proc "agent a1 died: exit 70" {:agent :a1 :exit {:exit 70}})
        st (st-of (db-of [(decl "a")]) :flights {"demo/a" {:act {:op :spawn :task "demo/a" :env {:branch :demo/a}} :agent :a1 :step :worked}})
        [st' outs] (#'r/advance st "demo/a" :worked died)]
    (is (= died (get-in st' [:flights "demo/a" :died])) "the death is the flight's cause")
    (is (= [{:op :write :req {:op :branch-db :branch :demo/a}}] (mapv :req outs)) "its end is read from the branch next")))

;; Estimate: 3 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 5 judgings x 0.7M (the index and the judge-tx); 2 transacts or db-withs x 50k ~ 8,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 8000000}} a-refused-end-is-a-verdict-that-wakes-and-the-same-one-again-holds
  ;; design one-derivation 4.1 section 5 and root's ruling (2026-10-26): a work task's end the done
  ;; rule refuses is words to the agent, a :verdict message with the reasons and the facts root
  ;; judged on (v-<task>-<turn>), which wakes its session; the same reasons again hold the task
  ;; for root (told the same twice is no news); different reasons wake it again. A merge refused by the done rule holds
  (let [red [{:reason :proof-red :symbol (t "a")}]
        fail {(t "a") :fail}
        db (db-of [(decl "a")] :extra (work "a" :running))
        verdict-of (fn [db] (d/entity db [:seon.message/id "v-demo/a-1"]))
        told (fn [reasons] (db-of [(decl "a")] :extra (work "a" :running :members {:seon.task/reasons reasons})))]
    (testing "the first: a refused :red end is told, with the done rule's reasons and what root judged on, to the branch's session"
      (let [{:keys [db outcome]} (judge! db "a" {:outcome :red} :results fail)
            v (verdict-of db)]
        (is (= :told outcome))
        (is (= :told (:seon.task/outcome (task/task db "demo/a"))))
        (is (= red (:seon.task/reasons (task/task db "demo/a"))) "the reasons are the task's, so the next judging can compare")
        (is (= :running (:seon.task/execution (task/task db "demo/a"))) "and the task stays released")
        (is (= [:root #{(akey "a")}] [(:seon.system/id (d/entity db (:db/id (:seon.message/from v)))) (set (map :seon.agent/key (:seon.message/to v)))]) "from the system, to the agent")
        (is (= {:reasons red :results fail} (select-keys (sedn/decode (:seon.message/body v)) [:reasons :results])))))
    (testing "told, its process gone: the next start is a spawn, never a wake (a told task is not parked)"
      (let [told (db-of [(decl "a")] :extra (concat (work "a" :told :members {:seon.task/reasons red})
                                                    (task/message-tx "v-demo/a-1" seon.task/system-sender [(akey "a")] (sedn/encode {:reasons red}) 1000)))]
        (is (= [(spawn "a")] (actions-for told :woken [(akey "a")])))
        (is (= [] (actions-for told)) "until it is woken")))
    (testing "the same reasons again: held for root, saying so, and no message"
      (let [{:keys [db outcome]} (judge! (told red) "a" {:outcome :red} :results fail)
            tk (task/task db "demo/a")]
        (is (= :closed outcome))
        (is (= :held (:seon.task/execution tk)))
        (is (str/starts-with? (get-in tk [:seon.task/failure :reason]) "the same verdict again: "))
        (is (nil? (verdict-of db)) "and no message")))
    (testing "behind alone is no reason (plan item 10): a green task behind main is ready, and the one :merge merges main in
              its own step, never told to merge main and never held for main's motion"
      (let [{:keys [outcome]} (judge! (told [{:reason :no-proofs}]) "a" {:outcome :green} :results {(t "a") :pass} :diff {:theirs 3})]
        (is (= :ready outcome) "ready, whatever main moved")))
    (testing "different reasons: a verdict again, which wakes"
      (let [{:keys [db outcome]} (judge! (told [{:reason :no-proofs}]) "a" {:outcome :red} :results fail)]
        (is (= :told outcome))
        (is (= :root (:seon.system/id (d/entity db (:db/id (:seon.message/from (verdict-of db)))))) "from the system")))
    (testing "a merge refused by the done rule holds"
      (let [mdb' (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :running)))
            j (judgment-of mdb' "a-merge" {:end {:outcome :red} :results fail})]
        (is (= :closed (:outcome j)))
        (is (some? (:hold j)))))))

;; docs/reconcile.md#a-task-holds-how-its-work-ended: an outside agent's green work behind main alone is ready, never closed for main's
;; motion: the one :merge merges main into its branch in its own step (plan item 10). Red before: closed, "ended green: behind 78"
;; (Data model's real task on the standing system, 2026-11-11); then told to merge main, up to :seon.config.merge/max-behind
;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 3 judgings x 0.7M (the index and the judge-tx) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} an-outside-agents-green-work-behind-main-is-ready-not-closed
  (let [db (db-of [(decl "a")] :extra (work "a" :running :model "outside"))
        facts {:end {:outcome :green} :results {(t "a") :pass}}]
    (is (= :ready (:outcome (judgment-of db "a" facts))) "behind main alone: ready, the one :merge takes main in its step (plan item 10)")
    (is (= :closed (:outcome (judgment-of db "a" (assoc facts :end {:outcome :red} :results {(t "a") :fail})))) "red is no motion of main: closed as before")))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 judgings x 0.7M (the index and the judge-tx) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-judging-retracts-what-it-does-not-set
  ;; the judged attributes are the end's, never an earlier end's: an error and deferred results a task held from its last judging
  ;; do not survive a later one that has none (seon.task/outcome-tx)
  (let [stale {:seon.task/error {:seon.error/type :seon.fx/io-error :seon.error/operation 'seon.fx/write-file :seon.error/message "the last gate failed"}
               :seon.task/deferred {'seon.system-test/boots "d-boots"} :seon.task/deferred-results {'seon.system-test/boots :error}
               :seon.task/waste {'seon.doc/resolve-ref {:forms 2 :turns 1}}}
        db (db-of [(decl "a")] :extra (work "a" :running :members stale))
        {after :db} (judge! db "a" {:outcome :green} :results {(t "a") :pass})
        tk (task/task after "demo/a")]
    (is (= :ready (:seon.task/outcome tk)))
    (is (= stale (select-keys (task/task db "demo/a") (keys stale))) "the earlier end's facts are on the task before")
    (is (not-any? #(contains? tk %) (keys stale)) "and gone after a judging that set none of them")))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-woken-task-whose-agent-never-started-is-spawned-under-its-key
  ;; docs/users.md#roots-agent: a parked task names an agent no root has started (a key and no boot, as root's session is at boot); a
  ;; message to that key makes the task ready, and its action is a spawn, which takes the session's key (seon.task/agent-key). The
  ;; woken check reads the task's agent key itself, so what an agent is in the index does not change (Optimization, 2026-11-08)
  (let [db (db-of [(decl "a")] :extra [{:db/id "ag" :seon.agent/key "never-started"}
                                        {:seon.task/id "demo/a" :seon.task/agent "ag" :seon.task/outcome :parked}])]
    (is (= [] (actions-for db)) "parked, it waits")
    (is (= [(spawn "a")] (actions-for db :woken ["never-started"])) "woken, it is spawned")
    (is (= "never-started" (task/agent-key db "demo/a")) "under its own key")))

;; Estimate: 2 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 transacts or db-withs x 50k ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-wake-whose-parked-process-is-gone-is-lost-and-the-next-start-a-spawn
  ;; flow-lab's hazard 6 (design agent-loop section 4): the parked process died before the wake's
  ;; work! reached it, so root answers that agent ended; the task took nothing, so it is :lost, not a
  ;; death, and the session's next start is a spawn, a load of the session, never a wake of a
  ;; process that is gone
  (let [ended (contract/error :seon.system/ended 'seon.system/api-proc "agent a-a1 ended" {:agent :a-a1})
        flight {:act {:op :wake :task "demo/a" :env {:branch :demo/a}} :agent :a-a1 :step :worked}
        db (db-of [(decl "a")] :extra (work "a" :running))
        st (st-of db :flights {"demo/a" flight})
        [_ outs] (#'r/advance st "demo/a" :worked ended)
        tx (get-in (first outs) [:req :req :tx-data])
        lost (task/task (d/db-with db tx) "demo/a")]
    (is (some #(= [:db/cas [:seon.task/id "demo/a"] :seon.task/outcome :running :lost] %) tx) "the wake's task is lost")
    (is (str/includes? (:seon.task/reason lost) "agent a-a1 ended") "with why")
    (is (= [(spawn "a")]
           (actions-for (db-of [(decl "a")] :extra (work "a" :lost)) :woken [(akey "a")]))
        "the next start is a spawn")))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M) ~ 500,000 ticks
(deftest ^{:seon.test/estimate {:ticks 500000}} the-judge-reads-its-branch-in-one-request
  ;; task seon.reconcile/the-judge-reads-its-branch-in-one-request: once the tests answer, the flight
  ;; asks the writer once (:branch-diff, whose answer holds the head, its merge base and what it
  ;; changed), never :branch-db then :branch-diff; that one answer is what the task is judged on
  (let [db (db-of [(decl "a")] :extra (work "a" :running))
        flight {:act {:op :spawn :task "demo/a" :env {:branch :demo/a}} :agent :a-a}
        st (st-of db :flights {"demo/a" (assoc flight :step :tested)})
        [st' outs] (#'r/advance st "demo/a" :tested {:reports []})]
    (is (= [{:op :write :req {:op :branch-diff :branch :demo/a}}] (mapv :req outs)) "one request, the diff")
    (is (= :diffed (get-in st' [:pending (:tag (first outs)) 1])) "answered at :diffed, with no :read step between")
    (let [st (assoc-in st [:flights "demo/a"] (assoc flight :step :diffed :results {}))
          [_ outs] (#'r/advance st "demo/a" :diffed {:ok true :db db :base db :base-t (:max-tx db) :ours [] :theirs 0 :conflicts 0
                                                      :unmerged [] :uncontracted [] :caused [] :vars #{}})]
      (is (= {:op :kill :id :a-a :ended true} (:req (first outs))) "its agent is stopped")
      (is (some #(= [:db/cas [:seon.task/id "demo/a"] :seon.task/outcome :running :closed] %) (get-in (second outs) [:req :req :tx-data]))
          "and its end judged from that one answer"))))

;; Estimate: 1 db-of x 0.5M + 3 advance calls (one run-digest of a small main ~0.3M) ~ 1,500,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1500000}} the-judge-asks-main-of-a-red-nobody-knew-before-it-judges
  ;; docs/tasks.md#the-done-rule (seon.proofs/a-red-main-holds-is-mains-whatever-the-digest): every red of the judge's run that is
  ;; not main-red already is asked of main by name, and the flight waits, then reads the branch: red there, main's; answered
  ;; nothing (a process test), :main-unknown. Red before: the flight went to the diff at once, the red the task's
  (let [db (db-of [(decl "a")] :extra (work "a" :running))
        x 'demo-test/x
        flight {:act {:op :spawn :task "demo/a" :env {:branch :demo/a}} :agent :a-a}
        st (st-of db :flights {"demo/a" (assoc flight :step :tested)})
        [st' outs] (#'r/advance st "demo/a" :tested {:reports [{:var "#'demo-test/x" :type :fail}] :main-red #{}})]
    (is (= [{:branch :main :names #{x} :mode :judge}] (mapv #(select-keys (:req %) [:branch :names :mode]) outs)) "one ask of main, by name")
    (is (= :main-asked (get-in st' [:pending (:tag (first outs)) 1])) "answered at :main-asked, nothing read before it")
    (let [[st2 outs] (#'r/advance st' "demo/a" :main-asked {:reports [{:var "#'demo-test/x" :type :fail}]})]
      (is (= [{:op :write :req {:op :branch-diff :branch :demo/a}}] (mapv :req outs)) "then the branch, read once")
      (is (= #{x} (set (get-in st2 [:flights "demo/a" :main-red]))) "red on main: main's"))
    (let [[st2 _] (#'r/advance st' "demo/a" :main-asked {:reports [{:var "#'demo-test/x" :type :pass}]})]
      (is (empty? (get-in st2 [:flights "demo/a" :main-red])) "green on main: the task's own")
      (is (empty? (get-in st2 [:flights "demo/a" :main-unknown]))))
    (let [[st2 _] (#'r/advance st' "demo/a" :main-asked {:reports []})]
      (is (= #{x} (get-in st2 [:flights "demo/a" :main-unknown])) "answered nothing: unknown, never the task's"))
    ;; the wait's end: the test service answers every waiter, a failed run with its error (seon.prove-test/a-failed-run-answers-its-waiters-with-the-error);
    ;; an error answer ends this step typed, the asked reds unknown (held), and the flight goes on to the branch's read
    (let [[st2 outs] (#'r/advance st' "demo/a" :main-asked {:seon.error/type :seon.prove/run-failed :seon.error/operation 'seon.prove/request :seon.error/message "the run failed"})]
      (is (= #{x} (get-in st2 [:flights "demo/a" :main-unknown])) "main's error answer: unknown, held, never the task's")
      (is (= [{:op :write :req {:op :branch-diff :branch :demo/a}}] (mapv :req outs)) "and the flight goes on"))
    (testing "a red main-red holds already is not asked again"
      (let [[_ outs] (#'r/advance st "demo/a" :tested {:reports [{:var "#'demo-test/x" :type :fail}] :main-red #{x}})]
        (is (= [{:op :write :req {:op :branch-diff :branch :demo/a}}] (mapv :req outs)))))))

;; Estimate: 1 db-of x 0.5M + 2 advance calls ~ 700,000 ticks
(deftest ^{:seon.test/estimate {:ticks 700000}} an-answer-for-a-flight-that-ended-advances-nothing
  ;; docs/reconcile.md: an answer that arrives after its flight ended (the task closed, its agent killed, a second hand-back)
  ;; is no step of any flight: red before, :main-asked made a flight of the answer alone, with no :act and no :results, and
  ;; judged it (seon.reconcile/done-input refused results nil: the pilot's seventh seon_done, 2026-11-15)
  (let [db (db-of [(decl "a")] :extra (work "a" :running))
        st (st-of db)
        [st' outs] (#'r/advance st "demo/a" :main-asked {:reports [{:var "#'demo-test/x" :type :fail}]})]
    (is (= [] outs) "nothing asked, nothing written")
    (is (nil? (get-in st' [:flights "demo/a"])) "and no flight made of the answer")))

;; Estimate: the done rule is a pure function over a small map: ~50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} a-red-whose-main-verdict-is-unknown-holds-and-is-no-stray
  ;; docs/tasks.md#the-done-rule: a red main was asked of and answered nothing is :main-unknown, never :stray-red
  (let [j (fn [m] (r/done-explain (merge {:proofs #{'demo-test/a} :results {'demo-test/a :pass 'demo-test/p :fail} :main-green #{} :allowed-red #{} :uncontracted #{}} m)))]
    (is (= [{:reason :stray-red :symbol 'demo-test/p}] (j {})) "unasked: the branch's")
    (is (= [{:reason :main-unknown :symbol 'demo-test/p}] (j {:main-unknown #{'demo-test/p}})) "main's verdict unknown: held, named so")
    (is (= [] (j {:main-red #{'demo-test/p} :main-unknown #{'demo-test/p}})) "main's red wins")))

;; Estimate: 1 db-of x 0.5M + 3 advance calls (one run-digest of a small main ~0.3M) ~ 1,500,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1500000}} the-judge-writes-what-it-asked-main-and-how-it-read-it
  ;; docs/tasks.md#the-done-rule: a judged end carries what the judge asked main of the run's reds and how it read the answer
  ;; (:seon.task/main-asked), so a reader sees why a red was main's, unknown or the task's. Red before: nothing of it was written,
  ;; and the pilot's run 11 judged two of main's reds stray with no trace of main's answer
  (let [db (db-of [(decl "a")] :extra (work "a" :running))
        x 'demo-test/x y 'demo-test/y
        flight {:act {:op :spawn :task "demo/a" :env {:branch :demo/a}} :agent :a-a}
        st (st-of db :flights {"demo/a" (assoc flight :step :tested)})
        [st1 _] (#'r/advance st "demo/a" :tested {:reports [{:var "#'demo-test/x" :type :fail} {:var "#'demo-test/y" :type :fail}] :main-red #{}})
        [st2 _] (#'r/advance st1 "demo/a" :main-asked {:reports [{:var "#'demo-test/x" :type :fail}]})
        [_ outs] (#'r/advance st2 "demo/a" :diffed {:ok true :db db :base db :base-t (:max-tx db) :ours [] :theirs 0 :conflicts 0
                                                     :unmerged [] :uncontracted [] :caused [] :vars #{}})
        tx (some #(get-in % [:req :req :tx-data]) outs)
        asked (some #(when (and (vector? %) (= :seon.task/main-asked (nth % 2 nil))) (nth % 3)) tx)]
    (is (= {:main-red #{x} :asked {x :fail y :unknown}} asked) (pr-str tx))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 transacts or db-withs x 50k ~ 600,000 ticks
(deftest ^{:seon.test/estimate {:ticks 600000}} a-typed-error-from-work-or-the-judge-holds-the-task-and-is-no-death
  ;; docs/errors.md: an error flows to whoever can fix it. :briefed, :worked and :tested each took any typed error for the
  ;; agent's death: data's agent "died" before turn 1 when its prove answered no verdict (tests, 2026-11-12). Only
  ;; :seon.agent/died, :seon.system/no-agent and :seon.system/ended are deaths (as :spawned reads them); any other error holds
  ;; the task with it as its reason, its agent left running, which reads it at its next turn. Red before: a :dead-read step
  (let [db (db-of [(decl "a")] :extra (work "a" :running))
        flight {:act {:op :spawn :task "demo/a" :env {:branch :demo/a}} :agent :a-a}
        no-verdict (contract/error :seon.writer/refused 'seon.writer/refused "the run answered no verdict" {})
        held (fn [step]
               (let [[st outs] (#'r/advance (st-of db :flights {"demo/a" (assoc flight :step step)}) "demo/a" step no-verdict)
                     tx (mapcat #(get-in % [:req :req :tx-data]) outs)]
                 [(set (map (comp :op :req) outs))
                  (some #(= [:db/cas [:seon.task/id "demo/a"] :seon.task/execution :running :held] %) tx)
                  (:seon.task/failure (task/task (d/db-with db tx) "demo/a"))]))]
    (doseq [step [:briefed :worked :tested]]
      (let [[ops on-hold failure] (held step)]
        (is (not (contains? ops :kill)) (str step ": its agent is not stopped"))
        (is (not (some #(= :dead-read %) (map second (vals (:pending (first (#'r/advance (st-of db :flights {"demo/a" (assoc flight :step step)}) "demo/a" step no-verdict)))))))
            (str step ": no death is read"))
        (is on-hold (str step ": the task is held"))
        (is (re-find #"no verdict" (str (:reason failure))) (str step ": with the error as its reason: " (pr-str failure)))))
    (testing "a death is still a death"
      (let [died (contract/error :seon.agent/died 'seon.system/end-agent "agent a-a died" {:agent :a-a})
            [st _] (#'r/advance (st-of db :flights {"demo/a" (assoc flight :step :tested)}) "demo/a" :tested died)]
        (is (some #(= :dead-read %) (map second (vals (:pending st)))) "it reads the agent's end")))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 transacts or db-withs x 50k ~ 600,000 ticks
(deftest ^{:seon.test/estimate {:ticks 600000}} a-refused-diff-ends-the-task-with-its-cause
  ;; the writer refuses to diff the branch: the task is not judged on facts nobody
  ;; measured (no changed keys, no gaps); its agent is stopped and the task ends with the
  ;; reason as its error, the read not asked again
  (let [no (contract/error :seon.writer/refused 'seon.writer/writer-proc "no branch :demo/a" {})
        db (db-of [(decl "a")] :extra (work "a" :running))
        st (st-of db :flights {"demo/a" {:act {:op :spawn :task "demo/a" :env {:branch :demo/a}} :agent :a-a :step :diffed}})
        [st' outs] (#'r/advance st "demo/a" :diffed no)
        tk (task/task (d/db-with db (get-in (second outs) [:req :req :tx-data])) "demo/a")]
    (is (= {:op :kill :id :a-a :ended true} (:req (first outs))))
    (is (= [:closed "no branch :demo/a" no] ((juxt :seon.task/outcome :seon.task/reason :seon.task/error) tk)))
    (is (= :done (get-in st' [:pending (:tag (second outs)) 1])) "its flight ends with the write")))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 judgings x 0.7M (the index and the judge-tx) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-judged-task-that-is-no-longer-running-is-closed
  ;; a merge ready for the writer's :merge whose task was cancelled meanwhile is closed, never merged
  (let [db (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :ready) (task/execution-tx ["demo/a-merge"] :cancelled)))]
    (is (= [{:op :close :task "demo/a-merge"}] (actions-for db)))
    (is (= {:outcome :closed :reasons []}
           (judgment-of (db-of diamond :extra (concat (work "a" :running) (task/execution-tx ["demo/a"] :cancelled))) "a"
                        {:end {:outcome :green} :results {(t "a") :pass}}))
        "and a cancelled task whose agent ended is judged closed")))

;; Estimate: pure data in, pure data out, a few 10k ticks ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} done-explain-table
  (are [in expected] (= expected (mapv (juxt :reason :symbol) (r/done-explain in)))
    {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{}} []
    {:proofs #{} :results {} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:no-proofs nil]]
    {:proofs #{'p/a} :results {'p/a :error} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:proof-red 'p/a]]
    ;; a proof that never ran is unknown, not red
    {:proofs #{'p/a} :results {} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:proof-unknown 'p/a]]
    {:proofs #{'p/a} :results {'p/a :pass 'p/m :fail} :main-green #{'p/m} :allowed-red #{} :uncontracted #{}} [[:regression 'p/m]]
    {:proofs #{'p/a} :results {'p/a :pass 'p/o :fail} :main-green #{} :allowed-red #{'p/o} :uncontracted #{}} []
    {:proofs #{'p/a} :results {'p/a :pass 'p/s :fail} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:stray-red 'p/s]]
    {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{'app/f}} [[:contract-gap 'app/f]]
    {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{} :unmerged ["[:var app/f]"]} [[:unmerged nil]]))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M) ~ 500,000 ticks
(deftest ^{:seon.test/estimate {:ticks 500000}} the-writer-refuses-cycles-and-unknown-needs
  (let [db (empty-db)]
    (is (= :seon.task/refused (:seon.error/type (task/create-tx db [(decl "a" :needs ["b"]) (decl "b" :needs ["a"])]))))
    (is (= :seon.task/refused (:seon.error/type (task/create-tx db [(decl "a" :needs ["nope"])]))))
    (is (vector? (task/create-tx db diamond)))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 judgings x 0.7M (the index and the judge-tx) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} regression-is-judged-against-main-at-the-judgings-basis
  ;; x merged at main t 100; y (independent of x) was observed against main at t 50,
  ;; before x's merge, so x's red proof there is not a regression of the main y holds
  (let [two [(decl "x") (decl "y")]
        db (db-of two :extra (concat (work "x" :merged :members {:seon.task/merged-t 100}) (work "y" :running)))
        y (fn [basis] (judgment-of db "y" {:end {:outcome :green} :results {(t "x") :fail (t "y") :pass} :basis basis}))]
    (is (= {:outcome :ready :reasons []} (y 50)))
    (is (= :told (:outcome (y 150))) "refused: told the agent")))

;; Estimate: pure data in, pure data out, a few 10k ticks ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} contracts-are-checked
  ;; with the contracts on (:seon.config.contract/instrument, config: in every digest), a call
  ;; outside its contract is refused at entry with the one error map. (It read an inherited
  ;; PROTO_INSTRUMENT, an input no digest held; pure-tests 2026-10-23.)
  (when contract/on?
    (is (= :seon.instrument/contract-error
           (:seon.error/type (try (task/task-id "not-a-symbol" "x") (catch Exception e (ex-data e))))))))

;; ---- true branches: merge tasks, unmerged entries, behind, a refused run held for root ----

;; Estimate: 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} plan-and-proofs-accumulate-along-needs
  (let [db (mdb [(decl "a") (decl "b" :needs ["a"])])
        acc #(:seon.task/accumulated (task/task db %))]
    (testing "a merge task inherits its work task's plan and proofs"
      (is (= #{(t "a")} (:proofs (acc "demo/a-merge"))))
      (is (str/includes? (:plan (acc "demo/a-merge")) "Make a true.")))
    (testing "b, after a's merge, carries a's proofs too: a refinement only tightens"
      (is (= #{(t "a") (t "b")} (:proofs (acc "demo/b"))))
      (is (= #{(t "a") (t "b")} (:proofs (acc "demo/b-merge")))))
    (testing "the done rule judges every accumulated proof"
      (is (= [[:proof-red (t "a")]]
             (mapv (juxt :reason :symbol)
                   (r/done-explain (r/done-input (r/index db) "demo/b-merge" {(t "b") :pass (t "a") :fail} #{} [] nil nil #{}))))))))

;; Estimate: 1 db-of 0.5M + 7 steps, each a d/with 0.05M + the full index listed at the new db 0.6M + the index observed from the last by a diff 0.3M + by a commit's ops 0.3M = 1.25M each ~ 9.3M, about 1e7 ~ 10,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} an-index-observed-from-the-last-is-the-index-listed
  (observed-steps (db-of diamond)
                  [["a plan improved" [{:seon.task/id "demo/b" :seon.task/plan "Make b truer."}]]
                   ["a task started" (work "a" :running)]
                   ["a task judged done" [{:seon.task/id "demo/a" :seon.task/outcome :done}]]
                   ["a task merged" [{:seon.task/id "demo/a" :seon.task/outcome :merged :seon.task/merged-t 7}]]
                   ["a need added" [{:seon.task/id "demo/c" :seon.task/needs #{"demo/a" "demo/b"}}]]
                   ["a proof added" [{:seon.task/id "demo/a" :seon.task/proofs #{(t "a") 'demo-test/more}}]]
                   ["a new boot" (booted-tx "boot-2" 4)]]))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 3 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 2 transacts or db-withs x 50k ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} an-agents-boot-moving-re-derives-every-task-that-names-it
  ;; on a stage two tasks hold one agent (:seon.task/agent on both): the agent's boot moving moves what the index
  ;; derives of BOTH, and the index observed from the last is the one listed
  (let [br (task/default-branch "demo/d")
        stage (fn [outcome] (-> (db-of [(decl "d") (decl "i")])
                                (d/db-with (ts/work-tx "demo/d" outcome {:agent :a1 :branch br}))
                                (d/db-with (ts/work-tx "demo/i" outcome {:agent :a1 :branch br}))))
        k "a1"]
    (is (= k (:seon.task/agent (task/task (stage :running) "demo/d")) (:seon.task/agent (task/task (stage :running) "demo/i"))) "one agent holds both")
    (testing "the boot: both are live in this one's, both are reaped once it is an earlier boot's"
      (let [[_ _ [after]] (observed-steps (stage :running) [["the agent's boot moved" [{:seon.agent/key k :seon.agent/boot "boot-0"}]]])]
        (is (= #{"demo/d" "demo/i"} (:live (r/index (stage :running)))))
        (is (= [#{} #{"demo/d" "demo/i"}] [(:live after) (:reap after)]))))
    (testing "a wake: both are ready once the agent is woken, neither before"
      (let [db (stage :parked)
            ready #(set (map second (:ready %)))]
        (is (= #{} (ready (r/index db))))
        (is (= #{"demo/d" "demo/i"} (ready (r/wake (r/index db) db k))))))))

;; Estimate: 2 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 3 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 5 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 8,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 8000000}} actions-read-the-judgment-stored-on-the-task
  ;; the decision is made once, at the agent's end (`judgment`) and written; `actions` reads the outcome stored, never the done rule
  (let [merge {:op :merge :task "demo/a-merge"}
        gate {:seon.system/id :root :seon.config.source/gate-checkout "/the/merges/tree"}
        boots 'seon.system-test/boots]
    (testing ":ready is the writer's :merge"
      (is (= [merge] (actions-for (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :ready)))))))
    (testing ":gate is the gate, with the tests its judging deferred"
      (is (= [{:op :gate :task "demo/a-merge" :tests [boots]}]
             (actions-for (mdb [(decl "a")] :extra (concat (work "a" :done) (ts/root-tx gate) (work "a-merge" :gate :members {:seon.task/deferred {boots "d-boots"}})))))))
    (testing "a judged task no longer running is closed, never merged"
      (is (= [{:op :close :task "demo/a-merge"}]
             (actions-for (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :ready) (task/execution-tx ["demo/a-merge"] :cancelled)))))))
    (testing ":running but not live (an earlier boot's): reaped, then started again"
      (is (= [{:op :reap :task "demo/a"}] (actions-for (db-of [(decl "a")] :extra (work "a" :running :boot "boot-0"))))))
    (testing ":closed derives nothing: the hold was written with the outcome, and the task waits for a person to release it"
      (is (= [] (actions-for (db-of [(decl "a")] :extra (concat (work "a" :closed :members {:seon.task/reasons [{:reason :proof-red :symbol (t "a")}]})
                                                                (task/execution-tx ["demo/a"] :held)))))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M) ~ 500,000 ticks
(deftest ^{:seon.test/estimate {:ticks 500000}} every-message-a-reconciler-write-holds-wakes-its-recipient
  ;; docs/users.md#messages: a write the reconciler builds (seon.reconcile/write) holds a wake for every :seon.message/to its transaction
  ;; carries, not only for the one tell-tx adds, so a verdict the judge writes to the agent itself (:told) wakes it as a person's message
  ;; does (a refused green waited for a restart: an audit, 2026-11-08)
  (let [db (db-of [(decl "a")] :extra (work "a" :running))
        verdict (task/message-tx "m-verdict" seon.task/system-sender [(akey "a")] "The done rule refused it: make the proof green." 1)
        [st _] (#'r/write (st-of db) "demo/a" :done verdict)]
    (is (contains? (get-in st [:idx :late]) (akey "a")) "the agent the verdict is to is held for waking")
    (let [e (:db/id (d/entity db [:seon.agent/key (akey "a")]))
          by-id [(assoc (first verdict) :seon.message/to [e "a-tempid"])]
          [st _] (#'r/write (st-of db) "demo/a" :done by-id)]
      (is (= #{(akey "a")} (get-in st [:idx :late])) "a recipient named by its entity id too; a tempid, an entity the write makes, is owed nothing"))))

;; Estimate: 2 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 3 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-parked-task-is-woken-only-by-a-wake
  ;; idx-ready?: parked or told, a task is ready again only when its agent is woken (r/wake: a message reached it, over the
  ;; reconciler's :wake port); a wake for a running agent starts nothing and is held until it waits (docs/users.md#messages)
  (let [br (task/default-branch "demo/a")
        wake {:op :wake :task "demo/a" :agent :a-a
              :env {:branch br :model nil :max-turns (cfg/value :seon.config.agent/max-turns)}}
        acts (fn [outcome & ks] (actions-for (db-of [(decl "a")] :extra (work "a" outcome)) :woken ks))]
    (is (= [] (acts :parked)) "no wake")
    (is (= [wake] (acts :parked (akey "a"))) "woken")
    (is (= [] (acts :parked "other")) "another agent's wake is none of it")
    (testing "a wake for a running agent: nothing starts, the wake is held for when it waits"
      (let [db (db-of [(decl "a")] :extra (work "a" :running))
            idx (r/wake (r/index db) db (akey "a"))]
        (is (= [] (r/actions idx)))
        (is (= [#{} #{(akey "a")}] [(:woken idx) (:late idx)]))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index and actions call x 0.7M ~ 1,200,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1200000}} a-message-to-an-outside-agent-starts-no-process
  ;; docs/users.md#messages: a session's agent (model \"outside\") is driven by its session alone: a message to it,
  ;; parked, starts no process (its session reads it by seon_inbox, which needs none), so nothing rewrites its model.
  ;; Red before: the wake started it on the task's environment (no model), start-tx retracted it, and the session's later
  ;; messages went :from \"user\" (data's agent o5ea016d73bdcb88 at t 536917491, the standing root, 2026-11-16). A model's agent is woken.
  (let [db (db-of [(decl "a") (decl "b")] :extra (into (work "a" :parked :model "outside") (work "b" :parked)))
        acts (actions-for db :woken [(akey "a") (akey "b")])]
    (is (= ["demo/b"] (mapv :task acts)) (str "the outside agent starts nothing, the model's is woken: " (pr-str acts)))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 transacts or db-withs x 50k ~ 600,000 ticks
(deftest ^{:seon.test/estimate {:ticks 600000}} a-wake-raises-max-turns-by-its-allowance
  ;; docs/users.md#messages: the write that starts a woken agent raises :seon.agent/max-turns by :seon.config.agent/wake-turns, whoever sent
  ;; the message and whatever the task stood at, a cap included; a start with no wake raises nothing; the start consumes the wake
  (let [allowance (cfg/value :seon.config.agent/wake-turns)
        k (akey "a")
        wake-act {:op :wake :task "demo/a" :agent :a-a :env {:branch (task/default-branch "demo/a") :model nil :max-turns (cfg/value :seon.config.agent/max-turns)}}
        max-of (fn [db] (:seon.agent/max-turns (d/entity db [:seon.agent/key k])))
        started (fn [db woken?]
                  (let [st (cond-> (st-of db) woken? (update :idx r/wake db k))
                        [st' outs] (#'r/start st wake-act)]
                    {:db (d/db-with db (get-in (first outs) [:req :req :tx-data])) :woken (get-in st' [:idx :woken])}))]
    (doseq [outcome [:parked]]
      (let [db (db-of [(decl "a")] :extra (work "a" outcome))
            {after :db woken :woken} (started db true)]
        (is (= (+ (max-of db) allowance) (max-of after)) (str "woken at " outcome ": the budget is raised by the allowance, in the write that starts it"))
        (is (not (contains? woken k)) "and the start consumes the wake")
        (is (= (max-of db) (max-of (:db (started db false)))) "a start with no wake leaves the budget")))))

;; Estimate: 1 test-branch! x 1M (a pointer write, connect, release, delete); 3 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-kept-message-does-not-wake-again
  ;; docs/users.md#messages: only a send wakes. A wake for an agent that was running is held until it waits, then looked at against its branch:
  ;; a message not on its branch wakes it; one it holds (and keeps) does not
  (ts/test-branch!
   (fn [cfg conn]
     (let [k (akey "a") br (task/default-branch "demo/a") sb (pdb/on-main (:branch cfg) br)
           _ (d/transact conn (task/create-tx @conn [(decl "a")]))
           _ (d/transact conn (into (task/execution-tx ["demo/a"] :running) (booted-tx "boot-1" 4)))
           _ (d/transact conn (work "a" :running))
           _ (d/branch! conn (:branch cfg) sb)
           _ (d/transact conn (task/message-tx "m1" (seon.task/sender "owner") [k] "carry on" 1000))
           at (fn [late] (let [db @conn] {:db db :conn conn :idx (assoc (r/index db) :late late :woken #{}) :boot "boot-1" :rid 0 :pending {} :flights {}}))
           running (at #{})
           held (r/wake (:idx running) (:db running) k)]
       (is (= [#{} #{k}] [(:woken held) (:late held)]) "a wake for a running agent is held, not acted on")
       (is (= held (:idx (#'r/settle-late (assoc running :idx held)))) "and stays held while it runs")
       (d/transact conn (task/move-tx "demo/a" :running :parked {}))
       (testing "parked with a message not on its branch: woken, and ready"
         (let [st (#'r/settle-late (at #{k}))]
           (is (= [#{k} #{}] [(get-in st [:idx :woken]) (get-in st [:idx :late])]))
           (is (= ["demo/a"] (mapv :task (r/actions (:idx st)))))))
       (testing "parked with the message on its branch (kept there, or removed): not woken, nothing starts"
         (let [bc (pdb/connect (assoc cfg :branch sb))]
           (try (d/transact bc (task/message-tx "m1" (seon.task/sender "owner") [k] "carry on" 1000))
                (let [st (#'r/settle-late (at #{k}))]
                  (is (= [#{} #{}] [(get-in st [:idx :woken]) (get-in st [:idx :late])]))
                  (is (= [] (r/actions (:idx st)))))
                (finally (d/release bc)))))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 2 transacts or db-withs x 50k ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} two-agents-answering-each-other-end
  ;; docs/users.md#messages: each message wakes the agent it is to and raises its budget by the allowance; the exchange ends because an agent
  ;; stops answering (a reply with no message sent), and for no other reason. Nothing in the system bounds a pair that keeps answering:
  ;; each wake adds the allowance, and the sum grows with the exchange (a total limit is undecided).
  (let [allowance (cfg/value :seon.config.agent/wake-turns)
        [ka kb] [(akey "a") (akey "b")]
        db (db-of [(decl "a") (decl "b")] :extra (concat (work "a" :parked) (work "b" :parked)))
        budget (fn [db k] (:seon.agent/max-turns (d/entity db [:seon.agent/key k])))
        ;; one wake: the agent woken, started (its budget raised), its answer sent or not, and parked again
        round (fn [db k to-whom]
                (let [t (if (= k ka) "demo/a" "demo/b")
                      st (update (st-of db) :idx r/wake db k)
                      [_ outs] (#'r/start st {:op :wake :task t :agent (keyword (str "a-" (peek (str/split t #"/")))) :env {:branch (task/default-branch t) :model nil :max-turns 1}})
                      db (d/db-with db (get-in (first outs) [:req :req :tx-data]))]
                  [(d/db-with db (task/move-tx t :running :parked {})) to-whom]))
        start0 (budget db ka)
        ;; a sends to b, b answers a, a answers b, b stops: 4 wakes of the 3 answers
        [db1 _] (round db kb ka)
        [db2 _] (round db1 ka kb)
        [db3 _] (round db2 kb ka)]
    (is (= [start0 start0] [(budget db ka) (budget db kb)]))
    (is (= [(+ start0 allowance) (+ start0 (* 2 allowance))] [(budget db2 ka) (budget db3 kb)])
        "each wake adds the allowance to the agent woken: b twice, a once, as the exchange went")
    (is (= [] (r/actions (:idx (st-of db3)))) "and it ends when nothing more is sent: no wake, no start")))

(def ^:private observed-sizes [10 30 100])

(def ^:private disk-store
  "This process's one store on disk for the cost proofs below, seeded once with the test store's 2,000 datoms
  (seon.db/seed-tx), under target/tmp: each proof size is a branch of it (one store, many branches, as in
  production and as the test store is; a seeded store of its own per size cost 366 MB of allocation each, a
  gigabyte under the quadratic schema transaction), and its nodes are at addresses, which is why these proofs
  are not on the in-memory test store. Removed when the process ends."
  (delay (let [path (str (System/getProperty "java.io.tmpdir") "/seon-observed-" (random-uuid))]
           (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (seon.lib/remove-tree! path))))
           path)))

(defn- disk-main
  "The connection to main branch b of the proofs' disk store (made from its seeded :db when it has none)."
  {:malli/schema [:=> [:cat :keyword] :seon.host/conn]}
  [b] (:conn (pdb/open! :lmdb @disk-store 50 b)))

(defn observed-input
  "The cost proof's input at n, on a store on disk of its own (the one-store rule's last resort:
  a stored db keeps its index nodes at addresses, so datoms-diff skips the subtrees two values
  share; the test store's in-memory values keep none, and there every diff walks its attribute's
  datoms, a cost no root's main has): main seeded as the test store is with n unrelated tasks
  released, its index, that db, and the db after one task's plan improved (one datom differs).
  The store is the proofs' one disk store (disk-store), n's branch of it."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.reconcile/index :seon.host/db :seon.host/db]]}
  [n]
  (let [;; seeded with the test store's 2,000 datoms (seon.db/seed-tx), so the two inputs differ only in where they keep their nodes
        conn (disk-main (keyword (str "observed-" n)))
        decls (mapv #(decl (str "u" %)) (range n))]
    (d/transact conn (task/create-tx @conn decls))
    (d/transact conn (task/execution-tx (mapv #(task/task-id 'demo (:seon.task/name %)) decls) :running))
    (d/transact conn (booted-tx "boot-1" 4))
    (let [db0 @conn]
      (d/transact conn [{:seon.task/id "demo/u0" :seon.task/plan "Make u0 truer."}])
      [(r/index db0) db0 @conn])))

;; Estimate: the proofs' disk store seeded once per process (2,000 datoms; this test runs first so it pays it, 10M by 5k ticks a datom, measured about 70M) + sizes 10, 30, 100: each its input (140 tasks x ~60k create-tx = 8M, three transacts a size, the index and the changed plan) + the proof's own per-size work (fixed 1.05M x 3 + 100k x 140 = 17M) ~ 40M by the constants; 1e8 holds the measured seed ~ 100,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 100000000}} an-index-is-observed-at-the-cost-of-what-changed
  (do
    (cost/proof {:of 'seon.reconcile/index :input 'seon.reconcile-test/observed-input
                 :n "unrelated tasks on main, beside one whose plan changed"
                 :work (str "a datahike.api/datoms-diff of each of the 84 watched attributes' ranges, each one cached "
                            "descent where nothing changed (the stored subtrees both values share are skipped), one "
                            "pull of the entity that differs and its task re-derived. Listing the index again, as "
                            "seon.turn/observe did every turn, settles all n tasks: n^1.75, 258M ticks and 922 MB at "
                            "1,000 (92.0M ticks, 384 MB on a root's main of 680 tasks)")
                 :growth :log
                 ;; measured on LMDB at 10, 100, 300: 1.35M ticks / 5.90 MB, 1.68M / 6.53 MB, 1.78M / 6.66 MB (and, at the
                 ;; sizes this proof ran before, 1.47M / 5.8 MB at 1,000: flat): 84 descents of 5-11k ticks, the change itself
                 ;; about 30k, the descents' depth a staircase in the index (a level more every so many entries), which is log work.
                 ;; On the address-less test store the same observe read 4.71M / 17.9 MB at 10 and 5.36M / 18.3 MB at 500
                 ;; (every diff walked its attribute); the 84 descents are one cached :aevt diff for each watched attribute
                 :fixed {:ticks 1050000.0 :bytes 5200000.0}
                 :per-n {:ticks 100000.0 :bytes 250000.0}
                 :sizes observed-sizes})))

(def ^:private listed-sizes [5 15 50])

(defn listed-input
  "The listing proof's input at n: main on a store on disk of its own (as observed-input's,
  at listed-path) holding n tasks shaped as a root's main holds them: a plan of about 1.4 KB
  (out of line, as a task file's is), three proofs, and chains of five along :needs, released."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db]]}
  [n]
  (let [conn (disk-main (keyword (str "listed-" n)))
        plan (apply str (repeat 40 "Make the reconciler's index cost its tasks. "))
        decls (mapv (fn [i] (cond-> (assoc (decl (str "u" i)) :seon.task/plan (str plan i)
                                           :seon.task/proofs (set (map #(symbol "demo-test" (str "u" i "-" %)) (range 3))))
                              (pos? (mod i 5)) (assoc :seon.task/needs #{(str "demo/u" (dec i))})))
                    (range n))]
    (d/transact conn (task/create-tx @conn decls))
    (d/transact conn (task/execution-tx (mapv #(task/task-id 'demo (:seon.task/name %)) decls) :running))
    (d/transact conn (booted-tx "boot-1" 4))
    [@conn]))

;; Estimate: sizes 5, 15, 50 on the shared disk store (its seed paid by the observed proof): 70 tasks x ~75k create-tx with a 1.4 KB plan = 5M + the listing proof's own work (fixed 0.2M x 3 + 100k x 70 = 7.6M) ~ 12.6M, about 1e7 ~ 10,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} an-index-is-listed-at-the-cost-of-its-tasks
  ;; an agent's first request build lists the index (seon.agent/reconcile-index); every reconciler helper
  ;; takes the index, so its contract checks it shallowly (each entry is checked where it enters)
  (do
    (cost/proof {:of 'seon.reconcile/index :input 'seon.reconcile-test/listed-input
                 :n "tasks on main, in chains of five along :needs"
                 :work (str "one pull of each task, run and root entity, then each task derived once in "
                            "the order of its needs (depth, accumulated proofs, claims, ready); the index's "
                            "contract is checked on every helper call at the cost of its keys, never of its "
                            "entries. Checked deeply on every call, a listing cost tasks x tasks: 156M ticks "
                            "and 636 MB on a root's main of 903 tasks, 26.8M and 149 MB with the contracts off")
                 :growth :linear
                 ;; measured on LMDB (sizes 5, 15, 50 now; first at 10, 300 and 600): 0.36M ticks / 1.7 MB at 10, 10.1M / 50.6 MB at 300, 20.3M / 102 MB at 600, 34k ticks
                 ;; and 169 KB a task (about 26k / 135 KB its pull, seon.task/entity-value); 10 contracted calls a task and 12 fixed.
                 ;; Checked deeply, the same input read n^1.5 and more (each helper validated every entry)
                 ;; a task is pulled by its schema's named attributes (seon.task's task-pull): on Datahike's pull today each named
                 ;; attribute is a seek of its own, 35 seeks a task in place of the one :eavt range read '* made, so a task costs about
                 ;; 100k ticks and 320 KB (measured at 5 and 15). Deep fixes' named-pull item (a named pull reads the entity's
                 ;; :eavt range once) should bring it back under the old figure, 36k ticks and 180 KB a task
                 :fixed {:ticks 200000.0 :bytes 600000.0}
                 :per-n {:ticks 100000.0 :bytes 320000.0}
                 :sizes listed-sizes})))

;; Estimate: 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 3 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 transacts or db-withs x 50k ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} with-merge-chains-work-through-merges
  (let [db (mdb [(decl "a") (decl "b" :needs ["a"])])]
    (is (= {:seon.task/kind :merge :seon.task/needs #{"demo/a"} :seon.task/branch :task-demo-a}
           (select-keys (task/task db "demo/a-merge") [:seon.task/kind :seon.task/needs :seon.task/branch])))
    (is (= #{"demo/a-merge"} (:seon.task/needs (task/task db "demo/b"))))
    (testing "a's work first; accepted on its branch, its merge task starts on the same branch"
      (is (= [(mspawn "a")] (actions-for db)))
      (let [db (d/db-with db (work "a" :done))]
        (is (= [(mspawn "a-merge")] (actions-for db)))
        (testing "b waits for a's merge, not a's work"
          (is (= #{"demo/a-merge"} (r/blocked-by (r/index db) "demo/b"))))))))

;; Estimate: 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 judgings x 0.7M (the index and the judge-tx); 1 transacts or db-withs x 50k ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} green-merge-task-merges
  ;; one :merge, the task alone: no other merge runs
  (let [db (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :running)))]
    (is (= {:outcome :ready :reasons []} (judgment-of db "a-merge" {:end {:outcome :green} :results {(t "a") :pass}})) "a green end is a merge ready")
    (is (= [{:op :merge :task "demo/a-merge"}]
           (actions-for (d/db-with db (task/move-tx "demo/a-merge" :running :ready {})))))))

;; Estimate: 3 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 judgings x 0.7M (the index and the judge-tx) ~ 4,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 4000000}} a-whole-task-works-in-parallel-and-its-green-merges
  ;; owner, 2026-10-26 (seon.template/request): a whole task's agents start as work does, not one at a
  ;; time as merges do; its green end merges into main as a merge task's does; out of turns, it stays at its cap
  (let [whole (fn [nm] (assoc (decl nm) :seon.task/kind :whole))
        db (db-of [(whole "a")] :extra (work "a" :running))
        j (fn [facts] (judgment-of db "a" facts))]
    (is (= [(spawn "a") (spawn "b")] (actions-for (db-of [(whole "a") (whole "b")]))) "two at once")
    (is (= {:outcome :ready :reasons []} (j {:end {:outcome :green} :results {(t "a") :pass}})))
    (is (= [{:op :merge :task "demo/a"}]
           (actions-for (db-of [(whole "a")] :extra (work "a" :ready)))))
    (is (= :cap (:outcome (j {:end {:outcome :cap} :results {(t "a") :fail}}))))
    ;; owner, 2026-10-26 ("test for it"): a whole task that ended red, or green with main's merge
    ;; unresolved on its branch, is not merged: its verdict goes back to its own session, the
    ;; done rule's reasons as data (a merge task's holds for root)
    (is (= {:outcome :told :reasons [{:reason :proof-red :symbol (t "a")}] :verdict [{:reason :proof-red :symbol (t "a")}]}
           (j {:end {:outcome :red} :results {(t "a") :fail}})))
    (is (= [:told [:unmerged]]
           ((juxt :outcome #(mapv :reason (:verdict %))) (j {:end {:outcome :green} :results {(t "a") :pass} :unmerged ["[:var app/f]"]}))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 3 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 judgings x 0.7M (the index and the judge-tx); 3 transacts or db-withs x 50k ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-requests-green-work-is-merged-by-its-merge-task
  ;; owner, 2026-10-26: "design-build and merge" (seon.template/design-build, an experiment's choice): the green design-build task is done, not merged
  ;; by itself; its merge task then runs on the branch, and its green end is the merge
  (let [db-req (fn [extra] (let [db (empty-db) ds (tpl/design-build (decl "a"))]
                             (-> db (d/db-with (tpl/request-tx db ds)) (d/db-with (booted-tx "boot-1" 4)) (d/db-with extra))))]
    (is (= [(spawn "a")] (actions-for (db-req []))) "the work task forks first, alone")
    (is (= {:outcome :done :reasons []} (judgment-of (db-req (work "a" :running)) "a" {:end {:outcome :green} :results {(t "a") :pass}}))
        "its green end is accepted, and merges nothing")
    (is (= [(mspawn "a-merge")] (actions-for (db-req (work "a" :done)))) "once it is done, its merge task runs")
    (is (= [{:op :merge :task "demo/a-merge"}]
           (actions-for (db-req (concat (work "a" :done) (work "a-merge" :ready)))))
        "and the merge task's green end is the merge")))

;; Estimate: 3 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 3 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 judgings x 0.7M (the index and the judge-tx); 1 transacts or db-withs x 50k ~ 6,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 6000000}} a-merge-with-deferred-tests-is-gated-with-nobody-asking
  ;; the owner's ruling (2026-10-27: process tests the change reaches run automatically at the
  ;; judge; it replaces 2026-10-23's "never automatically" for merges): the process tests a
  ;; branch's change reaches run before its merge, on a worktree of the root's declared gate
  ;; checkout, with no one asking for it, and gate it like proofs: unrun, the
  ;; member is gated first (alone); all passing, it merges; a red, or a run that could not run
  ;; them (:error), is refused as a red proof is, never hidden. With no checkout to run them on
  ;; (a root with no source), it is held, saying so: never merged with them unrun
  (let [gate {:seon.system/id :root :seon.config.source/gate-checkout "/the/merges/tree"}
        boots 'seon.system-test/boots
        other 'seon.system-test/other
        self (assoc gate :seon.system/started-by "seon.system-test/boots")
        ;; the merge task working, the work before it done; judged on a green end with the tests its change reaches deferred
        judged (fn [root deferred] (judgment-of (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :running) (when root (ts/root-tx root))))
                                                "a-merge" {:end {:outcome :green} :results {(t "a") :pass} :deferred deferred}))
        ;; ... and gated: its outcome :gate, the gate's answer in
        gated (fn [root rs unread err]
                (let [db (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :gate :members {:seon.task/deferred {boots "d-boots"}}) (when root (ts/root-tx root))))]
                  (d/db-with db (#'r/gated (st-of db) "demo/a-merge" rs unread err #{} #{}))))
        merges [{:op :merge :task "demo/a-merge"}]]
    (is (= {:outcome :gate :reasons [] :deferred [boots]} (judged gate [boots])) "unrun: gated first")
    (is (= {:outcome :gate :reasons [] :deferred []} (judged gate [])) "none deferred: its tree is still analyzed, with no checkout (docs/merging.md#a-merge-runs-what-the-change-reaches)")
    (is (= :ready (:outcome (judged nil []))) "a root with no source has no candidate to analyze")
    (testing "all passing, it is ready and merges"
      (let [db (gated gate {boots :pass} [] nil)]
        (is (= :ready (:seon.task/outcome (task/task db "demo/a-merge"))))
        (is (= {boots :pass} (:seon.task/deferred-results (task/task db "demo/a-merge"))))
        (is (= merges (actions-for db)))))
    (testing "a gate that answers an error with no test named is no pass: its candidate didn't analyze"
      (let [db (gated gate {} [] {:seon.error/type :seon.writer/refused :seon.error/operation 'seon.writer/refused :seon.error/message "the merged tree doesn't analyze: x"})]
        (is (= :closed (:seon.task/outcome (task/task db "demo/a-merge"))))
        (is (clojure.string/includes? (str (:seon.error/message (:seon.task/error (task/task db "demo/a-merge")))) "doesn't analyze"))))
    (testing "a red, or a run that could not run them (:error), is refused as a red proof is"
      (doseq [s [:fail :error]]
        (let [db (gated gate {boots s} [] nil)
              tk (task/task db "demo/a-merge")]
          (is (= [:closed :held] ((juxt :seon.task/outcome :seon.task/execution) tk)) "held for root")
          (is (str/includes? (get-in tk [:seon.task/failure :reason]) (str boots " " (name s))))
          (is (= [] (actions-for db)) "and nothing merges"))))
    (testing "a gate that could not run them says why: the effect's error and the namespaces it could not put over the checkout"
      (let [err (contract/error :seon.fx/io-error 'seon.fx/run-tests "bin/worktree --at exited 1: no such branch" {})
            tk (task/task (gated gate {boots :error} [{:ns 'seon.generation :reason "no :ns/file on the branch"}] err) "demo/a-merge")]
        (is (str/includes? (get-in tk [:seon.task/failure :reason])
                           "the gate could not run them: bin/worktree --at exited 1: no such branch; seon.generation: no :ns/file on the branch")
            (get-in tk [:seon.task/failure :reason]))))
    (testing "the test whose process started this root is left out: a test cannot be its own gate (:seon.system/started-by)"
      (is (= {:outcome :gate :reasons [] :deferred []} (judged self [boots])) "the only deferred test is the root's own: no test runs, and the tree is still analyzed")
      (is (= {:outcome :gate :reasons [] :deferred [other]} (judged self [boots other])) "another test beside it is still gated, itself not named")
      (let [db (mdb [(decl "a")] :extra (concat (work "a" :done) (ts/root-tx self) (work "a-merge" :gate :members {:seon.task/deferred {boots "d-boots" other nil}})))]
        (is (= [{:op :gate :task "demo/a-merge" :tests [other]}] (actions-for db)))))
    (testing "no checkout to run them on (a root with no source): held, never merged with them unrun"
      (let [j (judged nil [boots])]
        (is (= :closed (:outcome j)))
        (is (str/includes? (:hold j) "no checkout to run them on"))))))

;; Estimate: 4 db builds (test-db fork 0.1M + the root-tx transact 50k = 0.15M each, 0.6M) + 7 task/proof? and unjudged calls of ~10k each ~ 0.7M ~ 700,000 ticks
(deftest ^{:seon.test/estimate {:ticks 700000}} what-no-gate-runs-is-said-and-what-a-task-owes-is-its-proofs
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches: seon.task/proof? is the one reading, a test var against the accumulated proofs (a
  ;; namespace's name is no proof); seon.task/unjudged says what the gate leaves and why, for the status and the run's end
  (let [t 'seon.system-test/boots u 'seon.system-test/other
        tk {:seon.task/kind :work :seon.task/accumulated {:proofs #{t}}}
        db (fn [root] (d/db-with (ts/test-db) (when root (ts/root-tx (merge {:seon.system/boot "b" :seon.config.agent/max-running 1} root)))))
        gate {:seon.config.source/gate-checkout "/the/tree"}]
    (is (task/proof? tk t))
    (is (not (task/proof? tk u)))
    (is (not (task/proof? {:seon.task/accumulated {:proofs #{'seon.system-test}}} t)) "like with like: a test var, never a namespace")
    (is (nil? (task/unjudged (db gate) tk [t u])) "the gate runs them all")
    (is (= [t u] (:tests (task/unjudged (db nil) tk [t u]))) "no checkout: all, with why")
    (is (= [t u] (:tests (task/unjudged (db gate) (assoc tk :seon.task/kind :design) [t u]))) "a design: all")
    (is (= {:tests [u]} (select-keys (task/unjudged (db (assoc gate :seon.system/started-by (str u))) tk [t u]) [:tests])) "the test that started this root: itself")))

;; Estimate: pure data in, pure data out, a few 10k ticks ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} a-deferred-proof-is-neither-red-nor-unknown
  ;; owner's ruling (2026-10-23): a process test never runs in an agent's turn or root's judge,
  ;; a proof among them; the done rule reads it as deferred to root's gate, and still refuses a
  ;; proof that is neither green nor deferred
  (let [in {:proofs #{'app/p 'app/q} :results {'app/p :pass} :main-green #{} :allowed-red #{} :uncontracted #{}}]
    (is (= [{:reason :proof-unknown :symbol 'app/q}] (r/done-explain in)))
    (is (= [] (r/done-explain (assoc in :deferred #{'app/q}))))
    (is (= [{:reason :proof-red :symbol 'app/p}] (r/done-explain (assoc in :results {'app/p :fail} :deferred #{'app/q}))))))

;; Estimate: 2 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} merges-run-one-at-a-time
  ;; git's merge queue: a merge agent's last merge-main and root's merge see the same main,
  ;; so a merge is never behind; work tasks still run in parallel
  (let [three [(decl "x") (decl "y") (decl "z")]
        done-work (mapcat #(work % :done) ["x" "y" "z"])]
    (are [extra expected] (= expected (map :task (filter #(= :spawn (:op %)) (actions-for (mdb three :extra extra)))))
      done-work ["demo/x-merge"]
      ;; one merge running: none starts
      (concat done-work (work "x-merge" :running)) []
      ;; one ready, awaiting root's merge: still the queue's
      (concat done-work (work "x-merge" :ready)) []
      ;; merged: the next one starts
      (concat done-work (work "x-merge" :merged)) ["demo/y-merge"])
    (testing "work tasks are not queued"
      (is (= ["demo/x" "demo/y" "demo/z"] (map :task (actions-for (mdb three))))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 transacts or db-withs x 50k ~ 600,000 ticks
(deftest ^{:seon.test/estimate {:ticks 600000}} boot-records-merge-max-running-one
  ;; DECISIONS: one merge task at a time until the par-200 hang is fixed; the config value in force is a fact on main a person can read
  (let [n (cfg/value :seon.config.agent/max-running)
        m (cfg/value :seon.config.merge/max-running)
        db (d/db-with (empty-db) (into (task/boot-tx "boot-1" "sean") (seon.task/config-tx cfg/config {})))]
    (is (= {:boot "boot-1" :max-running n :max-merging m} (task/root db)) "both config keys are recorded at boot, as the config states them")))

;; Estimate: 2 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} two-ready-disjoint-merges-run-one-at-a-time-by-default
  ;; x and y change disjoint definitions: both could work at once, but the config says 1
  (let [two [(decl "x") (decl "y")]
        extra (concat (work "x" :done :members {:seon.task/changed #{"[:var app.m/x]"}}) (work "y" :done :members {:seon.task/changed #{"[:var app.m/y]"}}))]
    (is (= ["demo/x-merge"] (map :task (filter #(= :spawn (:op %)) (actions-for (mdb two :extra extra))))))
    (testing "more at once is turned on by writing the fact"
      (is (= ["demo/x-merge" "demo/y-merge"]
             (map :task (filter #(= :spawn (:op %)) (actions-for (mdb two :extra (concat extra (ts/root-tx {:seon.config.merge/max-running 8})))))))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 judgings x 0.7M (the index and the judge-tx) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} unmerged-refuses-done
  (let [db (db-of diamond :extra (work "a" :running))
        reasons [{:reason :unmerged :key "[:var app/f]"}]]
    (is (= {:outcome :told :reasons reasons :verdict reasons}
           (judgment-of db "a" {:end {:outcome :green} :results {(t "a") :pass} :unmerged ["[:var app/f]"]})))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 judgings x 0.7M (the index and the judge-tx) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} an-open-problem-its-work-caused-refuses-done
  ;; docs/health.md: recorded at its end (seon.problems/caused), refused by
  ;; the done rule, so the agent is told naming it (a :verdict); its evaluations were never refused
  (let [msg "docs/x.md#y cites app/f, which names nothing"
        reasons [{:reason :open-problem :key msg}]]
    (is (= {:outcome :told :reasons reasons :verdict reasons}
           (judgment-of (db-of diamond :extra (work "a" :running)) "a" {:end {:outcome :green} :results {(t "a") :pass} :caused #{msg}})))
    (is (= reasons
           (r/done-explain {:proofs #{(t "a")} :results {(t "a") :pass} :main-green #{} :allowed-red #{} :uncontracted #{} :caused #{msg}})))))

;; Estimate: 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-reprove-merge-gets-a-new-agent
  (testing "root's merge found main's program moved since the proof: the merge task starts again, the same session; no catch-up"
    (let [db (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :reprove)))]
      (is (= [(mspawn "a-merge")] (actions-for db))))))

;; Estimate: 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 4 transacts or db-withs x 50k ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-ready-task-merges-alone-and-a-reprove-proves-again-never-held
  ;; plan item 10: a ready task merges alone through the writer's one :merge, with the t of main its proof read (its :ready outcome's
  ;; transaction); a :reprove answer (main's program moved since) tells it to prove again on its branch, which holds main now, and is
  ;; never counted or held. Red before: one octopus act for every ready task, and a :seon.merge/behind answer held the task past
  ;; :seon.config.merge/max-behind; then (C2) an act named :octopus-merge with members, and the outcome :behind for a reprove
  (let [db (mdb [(decl "add")] :extra (concat (work "add" :done) (work "add-merge" :running)))
        db (d/db-with db [{:seon.task/id "demo/add-merge" :seon.task/outcome :ready}])
        ready-t (:tx (first (d/datoms db :eavt (:db/id (d/entity db [:seon.task/id "demo/add-merge"])) :seon.task/outcome)))
        act (first (filter #(= :merge (:op %)) (actions-for db)))
        [st outs] (#'r/start (st-of db) act)
        req (some #(get-in % [:req :req]) (filter #(= :write (get-in % [:req :op])) outs))
        answer (fn [r] (let [[_ outs] (#'r/advance st "demo/add-merge" :merged r)
                             tx (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= :write (get-in % [:req :op])) outs)))]
                         (task/task (d/db-with db tx) "demo/add-merge")))]
    (is (= :merge (:op req)) (str "the writer's one :merge, a task alone: " (pr-str req)))
    (is (= ready-t (:proved-t req)) "with the t its proof read")
    (let [tk (answer {:merged? false :reprove true :tx 9})]
      (is (= :running (:seon.task/execution tk)) "a reprove runs again, never held")
      (is (= :reprove (:seon.task/outcome tk)) "told to prove again on its branch"))
    (is (= {:op :merge :task "demo/add-merge"} act) "one act a task, with no octopus members")))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 judgings x 0.7M (the index and the judge-tx) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-green-work-task-judged-behind-is-done-and-its-merge-runs
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches: a green end of a work task judged behind main's newer
  ;; merges is done, and its merge task runs next. A task no merge task follows merges itself: ready, the writer's one :merge taking
  ;; main into its branch in its own step (plan item 10); before it was told to merge main, and held past a count (bin/check swarm held
  ;; sw/s8 and sw/s9 so, 2026-10-31)
  (let [merge-decl {:seon.task/ns 'demo :seon.task/name "a-merge" :seon.task/kind :merge :seon.task/plan "Merge a." :seon.task/needs #{"demo/a"} :seon.task/branch :task-demo-a}
        judged (fn [decls] (judgment-of (db-of decls :extra (work "a" :running)) "a" {:end {:outcome :green} :results {(t "a") :pass}}))]
    (is (= {:outcome :done :reasons []} (judged [(decl "a") merge-decl])) "followed by its merge task: no verdict, the task is done")
    (is (= :ready (:outcome (judged [(decl "a")]))) "no merge task: it merges itself, ready, the one :merge taking main (plan item 10)")
    (is (= :ready (:outcome (judged [(decl "a") (decl "b" :needs ["a"])]))) "a follower that is no merge task: the same")))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 4 transacts or db-withs x 50k ~ 700,000 ticks
(deftest ^{:seon.test/estimate {:ticks 700000}} design-may-leave-its-proofs-red
  ;; a design step's work is the plan: done only when the plan differs from the one it began with
  (are [in expected] (= expected (mapv :reason (r/done-explain in)))
    {:kind :design :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{} :plan "B" :plan-before "A"} []
    {:kind :design :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{'app/f} :plan "B" :plan-before "A"} [:contract-gap]
    {:kind :design :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{} :plan "A" :plan-before "A"} [:no-design]
    ;; red is a design's to leave; a proof with no result was never written (or never ran):
    ;; the step E dry run's first real design ended at its plan with its proof undefined, and
    ;; implement spent 25 turns looking for it
    {:kind :design :proofs #{'p/a} :results {} :main-green #{} :allowed-red #{} :uncontracted #{} :plan "B" :plan-before "A"} [:proof-unknown]
    ;; a design that only rewrote its plan is not done: live agents, 2026-10-25, a design ended
    ;; green at its first plan edit, the proofs its plan named never written, and its implement
    ;; started with none (the archived paper live-2026-10-25.md (git history) D3)
    {:kind :design :proofs #{} :results {} :main-green #{} :allowed-red #{} :uncontracted #{} :plan "B" :plan-before "A"} [:no-proofs]
    {:kind :work :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{}} [:proof-red]))

(defn- design-db
  "Main with design task demo/a released, extra after."
  [extra]
  (let [db (empty-db)
        d [(assoc (decl "a") :seon.task/kind :design)]
        db (d/db-with db (task/create-tx db d))]
    (-> db (d/db-with (task/execution-tx ["demo/a"] :running)) (d/db-with (booted-tx "boot-1" 4)) (d/db-with extra))))

;; Estimate: 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 judgings x 0.7M (the index and the judge-tx) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-design-step-is-not-done-at-its-start
  ;; measured 2026-10-21: a design ended :done :green with 0 turns; its proofs may stay
  ;; red, so at its start nothing refused it
  (let [db (design-db (work "a" :running))
        plan "Make a true."]
    (testing "at its start (the plan as it began), the done rule refuses :no-design"
      (is (= [:no-design]
             (mapv :reason (r/done-explain (r/done-input (r/index db) "demo/a" {(t "a") :fail} #{} [] nil plan #{}))))))
    (testing "ended green with the plan unchanged: refused, so the agent is told why, never accepted"
      (is (= {:outcome :told :reasons [{:reason :no-design}] :verdict [{:reason :no-design}]}
             (judgment-of db "a" {:end {:outcome :green} :results {(t "a") :fail} :plan plan :plan-before plan}))))))

;; Estimate: the design world 0.6M; one judging: the done rule's reads of the world ~4M (the judge with no task merge, 4.6M
;; measured by parts 11-14) = 5,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-design-step-is-done-when-it-improved-the-plan
  ;; root's judge reads the plan the agent improved on its branch, and the plan reaches main only with the branch's one :merge, never at
  ;; the judging beside it (owner, 2026-11-16, via root: one merge carries everything). Red before: the judge merged the plan to main
  (let [better "Make a true. Design: (defn f [xs] ...) one pass over xs."
        before (:seon.task/plan (task/task (design-db (work "a" :running)) "demo/a"))
        {:keys [db outcome]} (judge! (design-db (work "a" :running)) "a" {:outcome :green} :results {(t "a") :fail}
                                     :on-branch [{:seon.task/id "demo/a" :seon.task/plan better}])]
    (is (= :ready outcome) "the improved plan makes the design done")
    (is (= before (:seon.task/plan (task/task db "demo/a"))) "and main keeps its plan until the branch merges")))

;; Estimate: 1 test-branch! x 1M (a pointer write, connect, release, delete); 6 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 4 judgings x 0.7M (the index and the judge-tx); 2 transacts or db-withs x 50k ~ 10,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-refused-end-holds-its-task-and-creates-none
  (ts/test-branch! (fn [_ conn]
  ;; owner, 2026-10-23: "no automatic retries, send it to root to decide if it should be
  ;; retried", and root, 2026-10-26: a work task's refused end is told to the agent once (a
  ;; :verdict), and the same verdict again holds. Every hold holds its task as my.task/fail!
  ;; does: its failure (the reason, the agent), the measured evidence, the outcome :closed (:dead for a death); no task is
  ;; created or cancelled
  (let [red [{:reason :proof-red :symbol (t "add")}]
        unknown [{:reason :proof-unknown :symbol (t "add")}]
        two-tasks [(decl "add") (decl "twice" :needs ["add"])]
        told (fn [reasons] (mdb two-tasks :extra (work "add" :running :members {:seon.task/reasons reasons})))
        died (contract/error :seon.agent/died 'seon.system/api-proc "agent a-add died: exit 70" {:agent :a-add})
        dead-db (mdb two-tasks :extra (work "add" :running))]
    (doseq [[why id db' problem outcome]
            [["a red proof" "demo/add" (:db (judge! (told red) "add" {:outcome :red} :results {(t "add") :fail}))
              "the same verdict again: proof-red demo-test/add-test" :closed]
             ["its proof never run" "demo/add" (:db (judge! (told unknown) "add" {:outcome :red} :results {}))
              "the same verdict again: proof-unknown demo-test/add-test" :closed]
             ["a dead agent" "demo/add" (d/db-with dead-db (#'r/dead-tx (st-of dead-db) "demo/add" died {:outcome :dead :ended-turn 3}))
              "its agent died: agent a-add died: exit 70" :dead]
             ["a merge conflict" "demo/add-merge"
              (let [db (mdb two-tasks :extra (concat (work "add" :done) (work "add-merge" :running)))]
                (:db (judge! db "add-merge" {:outcome :red} :results {(t "add") :pass}
                             :on-branch [(assoc (pm/conflict-tx conflict-f) :seon.merge/main-t 5)] :diff {:unmerged [[:var 'app/f]]})))
              "ended red: unmerged [:var app/f]" :closed]]]
      (testing why
        (let [tk (task/task db' id)
              before (mdb two-tasks)]
          (is (= problem (get-in tk [:seon.task/failure :reason])))
          (is (= (if (= id "demo/add-merge") #{} #{(str id "-refine") (str id "-refine-review")}) (set (remove (set (keys (task/tasks before))) (keys (task/tasks db')))))
              "no new task but its refine and the refine's review (docs/tasks.md#a-refine-curates-its-own-task); a merge task continues its work's branch and gets none")
          (when-let [rf (task/task db' (str id "-refine"))] (is (= :running (:seon.task/execution rf)) "a task that ended not done gets its refine released: work, refine, rerun"))
          (is (not-any? #(= :cancelled (:seon.task/execution %)) (vals (task/tasks db'))) "none cancelled")
          (is (= :held (:seon.task/execution tk)))
          (is (= (:seon.task/agent tk) (get-in tk [:seon.task/failure :agent])) "naming the agent")
          (is (= id (:from (:seon.task/evidence tk))))
          (is (= outcome (:seon.task/outcome tk)) "the task is answered once")
          (is (= :failed (r/task-state (r/index db') id))))))
    (testing "a merge conflict's evidence is the branch's entries"
      (let [db (mdb two-tasks :extra (concat (work "add" :done) (work "add-merge" :running)))
            {db' :db} (judge! db "add-merge" {:outcome :red} :results {(t "add") :pass}
                              :on-branch [(assoc (pm/conflict-tx conflict-f) :seon.merge/main-t 5)] :diff {:unmerged [[:var 'app/f]]})]
        (is (= [conflict-f] (mapv #(select-keys % (keys conflict-f)) (:conflicts (:seon.task/evidence (task/task db' "demo/add-merge"))))))))
    (testing "a merge the writer's :merge refused: the reconciler proc's answer to the writer"
      (let [db (mdb [(decl "add")] :extra (concat (work "add" :done) (work "add-merge" :ready)))
            [st outs] (r/reconciler-proc (r/reconciler-proc {:boot "boot-1" :conn conn}) :main (ts/commit-of conn db))
            asked (first (filter #(= :merge (get-in % [:req :req :op])) (:to-api outs)))
            reason (contract/error :seon.writer/refused 'seon.writer/merge-into-main "conflicts with main at app/f" {:merged? false})
            [_ outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r reason})
            tx (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= :write (get-in % [:req :op])) (:to-api outs2))))
            db' (d/db-with db tx)
            tk (task/task db' "demo/add-merge")]
        (is (some? asked) "the ready merge is asked of the writer")
        (is (= (set (keys (task/tasks db))) (set (keys (task/tasks db')))) "no new task")
        (is (= :held (:seon.task/execution tk)))
        (is (= {:reason "merge refused: conflicts with main at app/f" :agent "a-add-merge"} (:seon.task/failure tk)))
        (is (= :closed (:seon.task/outcome tk)))
        (is (= reason (:seon.task/error tk)) "the writer's reason, on the task")))))
))

;; Estimate: 1 test-branch! x 1M (a pointer write, connect, release, delete); 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 2 transacts or db-withs x 50k ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-merge-of-source-with-no-file-records-what-it-wrote-no-file-for
  (ts/test-branch! (fn [_ conn]
  ;; a merged task that changed no source file: no write-back runs and its task holds no error;
  ;; one whose files main holds no text for holds a typed :seon.git/commit-failed error naming them
  (let [db (mdb [(decl "add")] :extra (concat (work "add" :done) (work "add-merge" :ready)))
        [st outs] (r/reconciler-proc (r/reconciler-proc {:boot "boot-1" :conn conn}) :main (ts/commit-of conn db))
        st (assoc st :source {:branch "jolt/seon-merges"})
        asked (first (filter #(= :merge (get-in % [:req :req :op])) (:to-api outs)))
        answered (fn [x] (let [[_ outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r x})
                               tx (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= :write (get-in % [:req :op])) (:to-api outs2))))]
                           (task/task (d/db-with db tx) "demo/add-merge")))
        data (answered {:merged? true :tx 9 :keys 1 :files [] :unfiled {}})]
    (is (some? asked))
    (is (= :merged (:seon.task/outcome data)))
    (is (nil? (:seon.task/error data)) "a change of no source names no file, and holds no error")
    (testing "a write-back whose files main holds no text for: no commit, and its task says so"
      (let [step (fn [st tag r] (r/reconciler-proc st :answers {:tag tag :r r}))
            write-of (fn [outs] (first (filter #(= :write (get-in % [:req :op])) (:to-api outs))))
            [st2 outs2] (step st (:tag asked) {:merged? true :tx 9 :keys 1 :files ["src/app/none.clj"] :unfiled {}})
            merged-tx (get-in (write-of outs2) [:req :req :tx-data])
            [st3 outs3] (step st2 (:tag (write-of outs2)) {:ok true})
            [_ outs4] (step st3 (:tag (write-of outs3)) {:db db})
            tk (task/task (d/db-with db (vec (concat merged-tx (get-in (write-of outs4) [:req :req :tx-data])))) "demo/add-merge")]
        (is (not-any? :seon.task/error (filter map? merged-tx)) "a task with files is the write-back's to write: the merge's own write holds no error")
        (is (= [{:task "demo/add-merge"}] (get-in st2 [:flights "demo/add-merge" :writeback :members])) "the write-back is the merged task's")
        (is (= {:op :branch-db :branch :db} (get-in (write-of outs3) [:req :req])) "the write-back reads main")
        (is (= ["src/app/none.clj"] (:seon.git/files (:seon.task/error tk))) "held as a lost write-back, named by file"))))))
)

;; Estimate: 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 4 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 3 transacts or db-withs x 50k ~ 4,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 4000000}} a-failed-write-back-is-a-root-cause-until-a-commit-holds-its-files
  ;; the :git effect refused the write-back of a merged task (a locked ref): it holds a typed error naming the files,
  ;; quiescence names it, and a later commit of them (my.root/write-back!) clears it; a commit that
  ;; left a file out (main has no text for it) holds the error again, for that file alone
  (let [db (mdb [(decl "add")] :extra (concat (work "add" :done) (work "add-merge" :merged)))
        members [{:task "demo/add-merge"}]
        refused (assoc (contract/error :seon.fx/io-error 'seon.fx/git-commit "cannot lock ref refs/heads/jolt/seon-merges" {:path "/x"}) :seon.fx/at-ms 5)
        lost (d/db-with db (r/commit-tx db refused members ["src/app/m.clj" "src/app/a.clj"] []))
        causes (r/root-causes (r/index lost))
        fixed (d/db-with lost (r/commit-tx lost {:value {:sha "abc123" :files 2}} members ["src/app/a.clj" "src/app/m.clj"] ["src/app/a.clj" "src/app/m.clj"]))
        partial (d/db-with lost (r/commit-tx lost {:value {:sha "def456" :files 1}} members ["src/app/a.clj" "src/app/m.clj"] ["src/app/a.clj"]))
        task-of #(task/task % "demo/add-merge")]
    (is (= {:seon.error/type :seon.git/commit-failed :seon.error/message "cannot lock ref refs/heads/jolt/seon-merges"
            :seon.git/files ["src/app/a.clj" "src/app/m.clj"]}
           (select-keys (:seon.task/error (task-of lost)) [:seon.error/type :seon.error/message :seon.git/files]))
        "the task holds the typed error, with the files no commit holds")
    (is (= [{:task "demo/add-merge" :blocks []
             :cause "write-back lost: cannot lock ref refs/heads/jolt/seon-merges [src/app/a.clj, src/app/m.clj]; (my.root/write-back! sys [\"demo/add-merge\"]) writes it again"}]
           causes)
        "a merged task with that error is a root cause, naming the function that writes them again")
    (is (= [] (r/root-causes (r/index db))) "a merged task that lost nothing is none")
    (is (= "abc123" (:seon.task/commit (task-of fixed))))
    (is (nil? (:seon.task/error (task-of fixed))) "a commit holds them: the error is gone")
    (is (= [] (r/root-causes (r/index fixed))))
    (testing "a commit that left a file out holds the error for that file alone, never silently clearing it"
      (is (= "def456" (:seon.task/commit (task-of partial))))
      (is (= ["src/app/m.clj"] (:seon.git/files (:seon.task/error (task-of partial)))))
      (is (= 1 (count (r/root-causes (r/index partial))))))))

;; Estimate: 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 1 judgings x 0.7M (the index and the judge-tx) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-held-task-shows-its-reason-among-the-problems
  (let [red [{:reason :proof-red :symbol (t "add")}]
        {db' :db} (judge! (mdb [(decl "add")] :extra (work "add" :running :members {:seon.task/reasons red})) "add" {:outcome :red} :results {(t "add") :fail})
        [row & more] (get-in (problems/report db') [:classes :failed :rows])]
    (is (nil? more))
    (is (= {:task "demo/add" :reason "the same verdict again: proof-red demo-test/add-test" :open? true}
           (select-keys row [:task :reason :open?])))
    (is (= [(t "add")] (get-in row [:evidence :red])) "with its measured evidence")
    (is (= [] (:answered-by row)) "its own evidence is not an answer to it")))

;; Estimate: 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 judgings x 0.7M (the index and the judge-tx); 2 transacts or db-withs x 50k ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} roots-release-starts-again-on-the-same-branch-with-the-evidence
  (let [red [{:reason :proof-red :symbol (t "add")}]
        db (mdb [(decl "add")] :extra (work "add" :running :members {:seon.task/reasons red}))
        {db' :db} (judge! db "add" {:outcome :red} :results {(t "add") :fail})
        problem "the same verdict again: proof-red demo-test/add-test"
        released (d/db-with db' (task/execution-tx ["demo/add"] :running))
        ;; its refine starts too (docs/tasks.md#a-refine-curates-its-own-task); the release is the task's own
        [sp & more] (filter #(= "demo/add" (:task %)) (actions-for released))]
    (is (nil? more))
    (is (= (mspawn "add") sp) "a new agent's start, on the task's own branch")
    (is (= (:seon.agent/branch (d/entity db [:seon.agent/key "a-add"])) (get-in sp [:env :branch])))
    (testing "the resumed branch takes the task as main holds it: its failure and evidence"
      (let [on-branch (d/db-with db (task/onto-tx released db "demo/add"))
            tk (task/task on-branch "demo/add")]
        (is (= problem (:problem (:seon.task/evidence tk))))
        (is (= [(t "add")] (:red (:seon.task/evidence tk))))
        (is (= "a-add" (:agent (:seon.task/failure tk))))))
    (is (not= :failed (r/task-state (r/index released) "demo/add")) "released, it is no longer failed")))

;; Estimate: 1 test-branch! x 1M (a pointer write, connect, release, delete); 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 1 judgings x 0.7M (the index and the judge-tx); 2 transacts or db-withs x 50k ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} an-idle-system-does-nothing-after-a-reason
  (ts/test-branch! (fn [_ conn]
  ;; the reconciler proc's own steps: the reason's hold is one write, made where it was judged (the judging); once it is committed
  ;; nothing is left to do (quiet, naming the held task as a root cause); a root-only commit after it asks nothing
  (let [red [{:reason :proof-red :symbol (t "add")}]
        ;; its refine (released: the task ended not done) cancelled by root, so what is left is the held task alone
        db (-> (:db (judge! (mdb [(decl "add") (decl "twice" :needs ["add"])] :extra (work "add" :running :members {:seon.task/reasons red}))
                            "add" {:outcome :red} :results {(t "add") :fail}))
               (d/db-with (task/execution-tx ["demo/add-refine"] :cancelled)))
        commit (fn [db tx] (let [rep (d/with db tx)] [(:db-after rep) (pdb/report->tx rep)]))
        main (fn [st db ops] (r/reconciler-proc st :main (assoc (ts/commit-of conn db) :ops ops)))
        reqs (fn [outs] (remove #(= :stat (:op %)) (:to-api outs)))
        [st outs] (main (r/reconciler-proc {:boot "boot-1" :conn conn}) db [])
        quiet (some #(get-in % [:req :req :tx-data 0 :seon.system/quiescent]) (reqs outs))]
    (is (= :held (:seon.task/execution (task/task db "demo/add"))))
    (is (= [{:task "demo/add" :cause "the same verdict again: proof-red demo-test/add-test" :agent "a-add"
             :blocks ["demo/add-merge" "demo/twice" "demo/twice-merge"]}]
           (:root-causes quiet)) "quiet, naming the held task and what it blocks")
    (is (not-any? #(= :spawn (get-in % [:req :op])) (reqs outs)) "nothing starts")
    (let [[db3 ops3] (commit db [{:seon.system/id :root :seon.system/quiescent quiet}])
          [_ outs3] (main st db3 ops3)]
      (is (empty? (reqs outs3)) "a commit of root's own report asks nothing")))))
)

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} stuck-names-cancelled-and-held-roots
  (let [db (db-of diamond :extra (concat (merged "a") (task/execution-tx ["demo/b"] :cancelled) (task/execution-tx ["demo/c"] :held)))]
    (is (= [{:task "demo/b" :cause "cancelled" :blocks ["demo/d"]} {:task "demo/c" :cause "held" :blocks ["demo/d"]}]
           (r/root-causes (r/index db))))))

;; Estimate: pure data in, pure data out, a few 10k ticks ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} hairy-rule
  ;; refs: the task's proofs and the names its plan's cites hold (the facts its write derived, never its text re-parsed)
  (let [refs '#{app/g app/f app.proofs/a-test}
        c (fn [k sides] (into {:key [:var k]} (for [s sides] [s {:src "x"}])))
        merge-cfg {:max-conflicts 3 :max-merge-turns 2}]
    (are [conflicts merge-turns rule] (= rule (:rule (r/hairy (merge merge-cfg {:conflicts conflicts :refs refs :merge-turns merge-turns}))))
      [] 0 nil
      [(c 'app/f [:base :ours :theirs])] 0 nil
      [(c 'app/f [:base :ours :theirs]) (c 'app/h [:base :ours :theirs]) (c 'app/i [:ours :theirs])] 0 nil
      [(c 'app/f [:base :ours :theirs]) (c 'app/h [:base :ours :theirs]) (c 'app/i [:ours :theirs]) (c 'app/j [:ours :theirs])] 0 :too-many-conflicts
      [(c 'app/g [:base :ours])] 0 :deleted-by-them-referenced
      [(c 'app/z [:base :ours])] 0 nil
      [(c 'app.proofs/a-test [:base :ours])] 0 :deleted-by-them-referenced
      [(c 'app/f [:base :ours :theirs])] 3 :merge-turns)))

;; Estimate: 1 test-branch! x 1M (a pointer write, connect, release, delete); 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} quiescence-is-reported-when-nothing-can-move
  (ts/test-branch! (fn [_ conn]
  ;; the reconciler proc's own steps over real commits: a task held for root and its dependents, nothing live
  (let [db (mdb [(decl "add") (decl "twice" :needs ["add"])]
                :extra (concat (work "add" :closed)
                               [{:seon.task/id "demo/add" :seon.task/execution :held :seon.task/failure {:reason "capped"}}]))
        ;; main's commits are the test branch's: the first holds db's datoms, the others are transacted on it
        db (d/commit-as-db conn (:commit (ts/commit-of conn db)))
        commit (fn [_ tx] (let [rep (d/transact conn {:tx-data tx})] [(:db-after rep) (pdb/report->tx rep)]))
        step (fn [st db ops] (let [[st outs] (r/reconciler-proc st :main (note db (:t st) ops))] [st (:to-api outs)]))
        report-in (fn [outs] (some #(get-in % [:req :req :tx-data 0 :seon.system/quiescent]) outs))
        [st outs] (step (r/reconciler-proc {:boot "boot-1" :conn conn}) db [])
        report (report-in outs)
        blocked ["demo/add-merge" "demo/twice" "demo/twice-merge"]]
    (testing "nothing to start, nothing in flight: one positive report, naming the held root and what it blocks"
      (is (empty? (:flights st)))
      (is (= {:t (:max-tx db) :root-causes [{:task "demo/add" :cause "capped" :blocks blocked}]} report))
      (is (some #(= {:op :collect} (get-in % [:req :req])) outs) "and asks the writer to collect the store, once a spell")
      (is (:quiet st)))
    (let [[db2 ops2] (commit db [{:seon.system/id :root :seon.system/quiescent report}])
          [st2 outs2] (step st db2 ops2)]
      (testing "the report's own commit (root only) makes no second report; main holds it"
        (is (:quiet st2))
        (is (nil? (report-in outs2)))
        (is (= report (r/quiescent db2))))
      (testing "a commit touching tasks ends the spell: nothing can move still, so it is reported again at the new t"
        (let [[db3 ops3] (commit db2 [{:seon.task/id "demo/twice" :seon.task/plan "Make twice true, again."}])
              [_ outs3] (step st2 db3 ops3)]
          (is (= (:max-tx db3) (:t (report-in outs3))))))
      (testing "work to start is not quiet: root's release of add starts it, and no report goes out"
        (let [[db4 ops4] (commit db2 (task/execution-tx ["demo/add"] :running))
              [st4 outs4] (step st2 db4 ops4)]
          (is (= ["demo/add"] (keep #(get-in % [:req :opts :task]) outs4)))
          (is (not (:quiet st4)))
          (is (nil? (report-in outs4)))))))))
)

;; switch-over step 0 (seon.system/an-update-loses-nothing, docs/storage.md#collection): old snapshots are swept at quiescence by the
;; :collect effect, off the writer and never at boot, only where root is the store's sole writer; its record is written through
;; the writer when it answers. Estimate: two reconciler steps over a small task db (~2M) and one answer step = ~3000000
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-quiet-spell-asks-the-sole-writers-sweep-and-writes-its-record
  (ts/test-branch! (fn [_ conn]
  ;; a task held for root and nothing live: the reconciler is quiet at its first pass
  (let [db (d/commit-as-db conn (:commit (ts/commit-of conn (mdb [(decl "add")]
                                                                 :extra (concat (work "add" :closed)
                                                                                [{:seon.task/id "demo/add" :seon.task/execution :held :seon.task/failure {:reason "capped"}}])))))
        step (fn [st db ops] (let [[st outs] (r/reconciler-proc st :main (note db (:t st) ops))] [st (:to-api outs)]))
        [_ outs] (step (r/reconciler-proc {:boot "boot-1" :conn conn}) db [])]
    (testing "a root that shares its store (sole-writer? off, as here) asks no sweep, as its boot never swept"
      (is (not-any? #(= :collect (get-in % [:req :kind])) outs)))
    (let [value seon.config/value
          ;; the root as the store's sole writer: that one key read as the standing root's config sets it, every other as it is
          sole (fn ([k] (if (= k :seon.config.store/sole-writer?) true (value k))) ([k ctx] (if (= k :seon.config.store/sole-writer?) true (value k ctx))))
          [st outs] (with-redefs [seon.config/value sole] (step (r/reconciler-proc {:boot "boot-1" :conn conn}) db []))
          sweep (some #(when (= :collect (get-in % [:req :kind])) %) outs)
          rec {:swept 3 :ms 1.0 :generation 42}
          [_ after] (r/reconciler-proc st :answers {:tag (:tag sweep) :r {:value rec}})]
      (testing "the sole writer's quiet spell asks the :collect effect for the sweep, with the keep the config states"
        (is (= {:op :fx :kind :collect :req {:keep (seon.config/value :seon.config.store/keep-commits)}} (:req sweep))))
      (testing "its answer's record is written on main through the writer"
        (is (some #(= (pdb/retained-tx rec) (get-in % [:req :req :tx-data])) (:to-api after)) (pr-str (:to-api after)))))))))


;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 transacts or db-withs x 50k ~ 600,000 ticks
(deftest ^{:seon.test/estimate {:ticks 600000}} every-way-a-task-leaves-running-writes-its-outcome
  ;; root, 2026-10-26: a task with no outcome reads as a running one (an agent's ctx and its page's tile read it so),
  ;; so every way root moves a task off :running writes one with its reason: a reap (root restarted), a kill of a
  ;; live agent, a death, the writer refusing to read its branch; a refused spawn and a lost wake (their own tests), and
  ;; the judge's end (the agent's, or none)
  (let [db (db-of [(decl "a")] :extra (work "a" :running))
        st (st-of db)
        flight {:act {:op :spawn :task "demo/a" :env {:branch :task-demo-a}} :agent :a-a}
        outcome (fn [[_ outs]] (:seon.task/outcome (task/task (d/db-with db (mapcat #(get-in % [:req :req :tx-data]) outs)) "demo/a")))
        died (contract/error :seon.agent/died 'seon.system/end-agent "agent a-a died" {:agent :a-a})]
    (is (= :lost (outcome (#'r/start st {:op :reap :task "demo/a"}))) "a reap: lost")
    (is (= :killed (outcome (#'r/start st {:op :kill :task "demo/a" :reason :cancelled}))) "a kill of a live agent: killed")
    (is (= :dead (outcome (#'r/advance (assoc-in st [:flights "demo/a"] (assoc flight :died died)) "demo/a" :dead-read {:db nil})))
        "a death: dead")
    (is (= :closed (outcome (#'r/advance (assoc-in st [:flights "demo/a"] flight) "demo/a" :diffed
                                         (contract/error :seon.writer/refused 'seon.writer/refused "no branch :task-demo-a" {}))))
        "the writer refused its branch: closed, with its reason")))

(def ^:private held-why "branch :demo/a is held by live agent a9")

;; Estimate: 1 test-branch! x 1M (a pointer write, connect, release, delete); 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 2 transacts or db-withs x 50k ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-refused-spawn-writes-the-refused-outcome
  (ts/test-branch! (fn [_ conn]
  ;; root refuses the spawn (the branch is held, or too many live agents): the refused answer is
  ;; the task's outcome, written on main, and the next pass starts the task again
  (let [db (db-of [(decl "a")])
        [st outs] (r/reconciler-proc (r/reconciler-proc {:boot "boot-1" :conn conn}) :main (ts/commit-of conn db))
        asked (first (filter #(= :spawn (get-in % [:req :op])) (:to-api outs)))
        held (contract/error :seon.system/refused 'seon.system/spawn! held-why {:branch :demo/a :agent :a9})
        [_ outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r held})
        written (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= :write (get-in % [:req :op])) (:to-api outs2))))
        tk (task/task (d/db-with db written) "demo/a")]
    (is (some? asked) "the first pass asks for the spawn")
    (is (= :refused (:seon.task/outcome tk)) "the refused answer is written as the task's outcome")
    (is (= held-why (:seon.task/reason tk)) "with root's reason")
    (is (nil? (:seon.task/agent tk)) "and no agent, since none was made")
    (is (= [(spawn "a")] (actions-for (d/db-with db written))) "the next pass starts the task again"))))
)

;; Estimate: 1 test-branch! x 1M (a pointer write, connect, release, delete); 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 transacts or db-withs x 50k ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-refused-task-asks-again-when-main-moves
  (ts/test-branch! (fn [_ conn]
  ;; if nothing has changed, asking again is refused again: neither the refused answer nor
  ;; its own commit starts the task; other work on main lets it ask again
  (let [commit (fn [db tx] (let [rep (d/with db tx)] [(:db-after rep) (pdb/report->tx rep)]))
        main (fn [st db ops] (r/reconciler-proc st :main (assoc (ts/commit-of conn db) :ops ops)))
        spawns (fn [outs] (vec (keep #(when (= :spawn (get-in % [:req :op])) (get-in % [:req :opts :task])) (:to-api outs))))
        db (db-of [(decl "a")])
        [st outs] (main (r/reconciler-proc {:boot "boot-1" :conn conn}) db [])
        asked (first (filter #(= :spawn (get-in % [:req :op])) (:to-api outs)))
        [st outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r (contract/error :seon.system/refused 'seon.system/spawn! held-why {:branch :demo/a :agent :a9})})
        w (first (filter #(= :write (get-in % [:req :op])) (:to-api outs2)))
        [st outs3] (r/reconciler-proc st :answers {:tag (:tag w) :r {:ok true}})
        [db2 ops2] (commit db (get-in w [:req :req :tx-data]))
        [st outs4] (main st db2 ops2)
        [db3 ops3] (commit db2 [{:seon.task/id "demo/a" :seon.task/plan "Make a true, again."}])
        [_ outs5] (main st db3 ops3)]
    (is (= ["demo/a"] (spawns outs)))
    (is (= [] (spawns outs3)) "the write's answer starts nothing")
    (is (= [] (spawns outs4)) "nor does the refused answer's own commit")
    (is (= [{:task "demo/a" :cause held-why :blocks []}] (get-in st [:quiescent :root-causes])) "quiet, naming it")
    (is (= ["demo/a"] (spawns outs5)) "other work on main: it asks again"))))
)

;; docs/reconcile.md#a-task-holds-how-its-work-ended: a start refused because its branch is held by an agent whose own task is
;; judged (a done task's process still ending) asks again once that agent is stopped, with nothing else moving main. The order is
;; forced: the holder's end comes after the refusal. Red before: the refusal waited for main to move for another reason, the
;; holder's end moves no fact the index reads, and the task stayed refused (batch 78's e2e-newns, 2026-11-11)
;; Estimate: 1 test-branch! x 1M (a pointer write, connect, release, delete); 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 transacts or db-withs x 50k ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-refused-start-is-asked-again-when-its-holder-ends
  (ts/test-branch! (fn [_ conn]
  (let [commit (fn [db tx] (let [rep (d/with db tx)] [(:db-after rep) (pdb/report->tx rep)]))
        main (fn [st db ops] (r/reconciler-proc st :main (assoc (ts/commit-of conn db) :ops ops)))
        spawns (fn [outs] (vec (keep #(when (= :spawn (get-in % [:req :op])) (get-in % [:req :opts :task])) (:to-api outs))))
        kills (fn [outs] (vec (keep #(when (= :kill (get-in % [:req :op])) (get-in % [:req :id])) (:to-api outs))))
        why "branch :demo/b is held by live agent a-a working demo/a: its seon_done or an end frees it"
        ;; a is done (its agent's process still ending); b, ready, wants a branch a's agent holds
        db (db-of [(decl "a") (decl "b" :needs ["a"])] :extra (work "a" :done))
        [st outs] (main (r/reconciler-proc {:boot "boot-1" :conn conn}) db [])
        asked (first (filter #(= :spawn (get-in % [:req :op])) (:to-api outs)))
        [st outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r (contract/error :seon.system/refused 'seon.system/spawn! why {:branch :demo/b :agent :a-a :task "demo/a"})})
        w (first (filter #(= :write (get-in % [:req :op])) (:to-api outs2)))
        k (first (filter #(= :kill (get-in % [:req :op])) (:to-api outs2)))
        [st outs3] (r/reconciler-proc st :answers {:tag (:tag w) :r {:ok true}})
        [db2 ops2] (commit db (get-in w [:req :req :tx-data]))
        [st outs4] (main st db2 ops2)
        ;; root answers the stop: an answer is no fact, and asks nothing again
        [st outs5] (if k (r/reconciler-proc st :answers {:tag (:tag k) :r {:killed :a-a}}) [st {}])
        ;; the holder's end, after the refusal: root records its process ended (seon.task/agent-tx's :seon.process/ended-ms)
        [db3 ops3] (commit db2 [{:seon.process/id "p-a-a" :seon.process/ended-ms 1}])
        [_ outs6] (main st db3 ops3)]
    (is (= ["demo/b"] (spawns outs)) "b is asked for")
    (is (= [:a-a] (kills outs2)) "refused by a done task's agent: that agent is stopped")
    (is (= [] (spawns outs3)) "the write's answer starts nothing")
    (is (= [] (spawns outs4)) "nor does the refused answer's own commit")
    (is (= [] (spawns outs5)) "nor the stop's answer: it is no fact")
    (is (= ["demo/b"] (spawns outs6)) "the holder's end, a fact on main: b asks again, nothing else having moved main")))))

;; docs/reconcile.md#a-task-holds-how-its-work-ended (context's worktree run, 2026-11-12 18:01): a task told and woken while its own
;; agent's process is still ending is refused, its branch held by that process; nothing is stopped (its task is told, not done), and
;; it asked again only when main moved for another reason: it stood :running/:refused for an hour. The process's end is a fact on
;; main (:seon.process/ended-ms), and a commit holding one lets a refused start ask again.
;; Estimate: as its neighbour: 1 test-branch! x 1M, 1 db-of x 0.5M, three commits and the reconciler's steps ~0.5M ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-wake-refused-by-its-own-ending-process-asks-again-at-its-end
  (ts/test-branch! (fn [_ conn]
  (let [commit (fn [db tx] (let [rep (d/with db tx)] [(:db-after rep) (pdb/report->tx rep)]))
        main (fn [st db ops] (r/reconciler-proc st :main (assoc (ts/commit-of conn db) :ops ops)))
        spawns (fn [outs] (vec (keep #(when (= :spawn (get-in % [:req :op])) (get-in % [:req :opts :task])) (:to-api outs))))
        why "branch :demo/a is held by live agent a-a working demo/a: its seon_done or an end frees it"
        db (db-of [(decl "a")])
        [st outs] (main (r/reconciler-proc {:boot "boot-1" :conn conn}) db [])
        asked (first (filter #(= :spawn (get-in % [:req :op])) (:to-api outs)))
        ;; refused by its own agent, whose task (this one) is no done task: nothing is stopped
        [st outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r (contract/error :seon.system/refused 'seon.system/spawn! why {:branch :demo/a :agent :a-a :task "demo/a"})})
        w (first (filter #(= :write (get-in % [:req :op])) (:to-api outs2)))
        [st _] (r/reconciler-proc st :answers {:tag (:tag w) :r {:ok true}})
        [db2 ops2] (commit db (get-in w [:req :req :tx-data]))
        [st outs4] (main st db2 ops2)
        [db3 ops3] (commit db2 [{:seon.process/id "p-a-a" :seon.process/ended-ms 1 :seon.process/exit 0}])
        [_ outs5] (main st db3 ops3)]
    (is (= ["demo/a"] (spawns outs)) "a is asked for")
    (is (not-any? #(= :kill (get-in % [:req :op])) (:to-api outs2)) "its own agent is not stopped: its task is not done")
    (is (= [] (spawns outs4)) "the refused answer's own commit asks nothing again")
    (is (= ["demo/a"] (spawns outs5)) "its process's end, a fact on main: a asks again")))))

;; Estimate: 1 test-branch! x 1M (a pointer write, connect, release, delete); 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-start-refused-by-a-working-agent-stops-nothing
  ;; the other side of the rule: a holder whose task is still running is working the branch, and is never stopped for another
  (ts/test-branch! (fn [_ conn]
  (let [db (db-of [(decl "a") (decl "b")] :extra (work "a" :running))
        [st outs] (r/reconciler-proc (r/reconciler-proc {:boot "boot-1" :conn conn}) :main (ts/commit-of conn db))
        asked (first (filter #(and (= :spawn (get-in % [:req :op])) (= "demo/b" (get-in % [:req :opts :task]))) (:to-api outs)))
        [_ outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r (contract/error :seon.system/refused 'seon.system/spawn! "held" {:branch :demo/b :agent :a-a :task "demo/a"})})]
    (is (some? asked) (pr-str (:to-api outs)))
    (is (not-any? #(= :kill (get-in % [:req :op])) (:to-api outs2)) "nothing is stopped")))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} quiescence-names-a-task-that-cannot-start
  ;; a refused task is a root cause at quiescence, naming what it blocks
  (let [db (db-of [(decl "a") (decl "b" :needs ["a"])]
                  :extra [{:seon.task/id "demo/a" :seon.task/outcome :refused :seon.task/reason held-why}])]
    (is (= [{:task "demo/a" :cause held-why :blocks ["demo/b"]}] (r/root-causes (r/index db))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 3 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 judgings x 0.7M (the index and the judge-tx); 1 transacts or db-withs x 50k ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-task-marked-failed-is-held-for-root
  ;; owner, 2026-10-22: a failure is task data and root reacts to it; D2(a): held for root,
  ;; no automatic retry. my.task/fail! writes the failure on the branch; root's judge
  ;; carries this agent's turn's onto main with the task held, and the task is closed
  (let [main (db-of [(decl "a")] :extra (work "a" :running))
        f {:reason "the premise is refuted" :counterexample "(= 1 2)" :agent (akey "a") :turn 1}
        judge (fn [failure] (judge! main "a" {:outcome :green} :on-branch [{:seon.task/id "demo/a" :seon.task/failure failure}]))
        {judged :db outcome :outcome} (judge f)
        idx (r/index judged)]
    (is (= :closed outcome))
    (is (= :held (:seon.task/execution (task/task judged "demo/a"))) "held for root, without a merge")
    (is (= f (:seon.task/failure (task/task judged "demo/a"))) "the failure, on main")
    (is (nil? (d/entity judged [:seon.message/id "v-demo/a-1"])) "no verdict is told for an end its agent marked failed")
    (is (= :failed (r/task-state idx "demo/a")))
    (is (= ["demo/a-refine"] (map :task (actions-for judged))) "nothing retries it; its refine starts (docs/tasks.md#a-refine-curates-its-own-task)")
    (let [[row & more] (get-in (problems/report judged) [:classes :failed :rows])]
      (is (nil? more))
      (is (= {:task "demo/a" :reason "the premise is refuted" :counterexample "(= 1 2)" :open? true}
             (select-keys row [:task :reason :counterexample :open?])) "root is told: a :failed row, the one producer of what is wrong"))
    (let [released (d/db-with judged (task/execution-tx ["demo/a"] :running))]
      (is (not= :failed (r/task-state (r/index released) "demo/a")) "a release is root's answer")
      (is (false? (:open? (first (get-in (problems/report released) [:classes :failed :rows])))) "and the row closes"))
    (is (nil? (:seon.task/failure (task/task (:db (judge (assoc f :turn 0))) "demo/a")))
        "an earlier turn's failure on a reused branch is not this end's")))

;; Estimate: 1 db-of of 3 tasks 0.5M + 3 db-withs 0.15M + 1 judging 0.7M + 4 task pulls 0.4M + the problems report (index + derive, ~1M)
;; ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-branchs-task-edits-reach-main-with-its-merge-and-a-two-sided-edit-is-shown
  ;; owner, 2026-11-16 (via root, ruling a): one merge carries everything, so a branch's task edits reach main with its :merge, never
  ;; when its agent ends (it replaces 2026-10-22's D4, which merged them at the end). A task member both sides changed is an entry of the
  ;; one merge on the branch, shown on the task member by member and among the problems (a feature, kept). Red before: the judge merged
  ;; demo/b's edit to main and read the conflict from its own three-way
  (let [base (db-of [(decl "a") (decl "b") (decl "c")] :extra (work "a" :running))
        branch (d/db-with base [{:seon.task/id "demo/b" :seon.task/plan "b, as a's agent learned it"}
                                {:seon.task/id "demo/c" :seon.task/plan "c, the branch's"}])
        main (d/db-with base [{:seon.task/id "demo/c" :seon.task/plan "c, main's"}])
        ;; the one merge of main into the branch found demo/c's plan changed on both sides: its entry on the branch (merge-main-into's)
        c (:db/id (d/entity branch [:seon.task/id "demo/c"]))
        branch (d/db-with branch (pm/unmerged-tx branch [{:key [:datum c :seon.task/plan] :base "Make c true." :ours "c, the branch's" :theirs "c, main's"}]
                                                 (:max-tx main)))
        judged (:db (judge! main "a" nil :branch branch :diff {:base base :ours [[:var 'demo/worked] [:task "demo/b"] [:task "demo/c"]]}))]
    (is (= "Make b true." (:seon.task/plan (task/task judged "demo/b"))) "another task's edit stays on the branch until it merges")
    (is (= "c, main's" (:seon.task/plan (task/task judged "demo/c"))) "a two-sided edit is never resolved here")
    (let [member {:task "demo/c" :attr :seon.task/plan :base "Make c true." :ours "c, the branch's" :theirs "c, main's"}]
      (is (= [member] (:seon.task/task-conflicts (task/task judged "demo/a"))) "and shown on the task, member by member (a feature: kept)")
      (is (= [(assoc member :from "demo/a")] (map :evidence (get-in (problems/report judged) [:classes :task-conflict :rows])))
          "root sees it among the problems"))))

;; Estimate: 11 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 11 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 4 judgings x 0.7M (the index and the judge-tx); 1 transacts or db-withs x 50k ~ 20,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 20000000}} a-waiting-agent-parks-its-task-and-a-message-wakes-it
  ;; design agent-loop sections 1-2 (owner, 2026-10-25: "Always park"): an agent that ends waiting
  ;; or without progress parks its session; out of turns, it stays at its cap. The task is parked, never held, and stays
  ;; released. Until a message to the session comes, the task does not start again; once one
  ;; comes, its next start is a wake on the parked process when that process is of this boot
  (let [red {(t "a") :fail}
        parked (fn [outcome] (work "a" outcome))
        wake {:op :wake :task "demo/a" :agent :a-a
              :env {:branch (task/default-branch "demo/a") :model nil :max-turns (cfg/value :seon.config.agent/max-turns)}}
        db0 (db-of [(decl "a")] :extra (work "a" :running))]
    (testing "judged: a park outcome parks the task (the cap stays at its cap); a red outcome is a verdict to the agent"
      (is (= :parked (:outcome (judgment-of db0 "a" {:end {:outcome :waiting} :results red}))))
      (is (= :cap (:outcome (judgment-of db0 "a" {:end {:outcome :cap} :results red}))))
      (is (= :told (:outcome (judgment-of db0 "a" {:end {:outcome :red} :results red})))))
    (testing "parked, nothing new: nothing starts"
      (is (= [] (actions-for (db-of [(decl "a")] :extra (parked :parked)))))
      (is (= [{:task "demo/a" :cause "parked: told, waiting for a message" :agent (akey "a") :blocks ["demo/b"]}]
             (r/root-causes (r/index (db-of [(decl "a") (decl "b" :needs ["a"])] :extra (parked :told)))))
          "quiet names it, with what it blocks")
      ;; a session parked for its person (outcome :parked) is a root cause only while it holds up other work: one that blocks nothing is
      ;; at rest (root's own session, made at every boot, parks so). Red before: every parked session was listed, root's included
      (is (= [] (r/root-causes (r/index (db-of [(decl "a")] :extra (parked :parked)))))
          "a parked session that blocks nothing is no cause")
      (is (= [{:task "demo/a" :cause "parked: parked, waiting for a message" :agent (akey "a") :blocks ["demo/b"]}]
             (r/root-causes (r/index (db-of [(decl "a") (decl "b" :needs ["a"])] :extra (parked :parked)))))
          "and one that holds up released work is"))
    (testing "a wake in flight: more wakes start nothing more, since one process runs one work! (flow-lab's hazard 4)"
      (is (= [] (actions-for (db-of [(decl "a")] :extra (work "a" :running)) :woken [(akey "a") (akey "a")]))))
    (testing "woken: a wake on its process"
      (is (= [wake] (actions-for (db-of [(decl "a")] :extra (parked :parked)) :woken [(akey "a")]))))
    (testing "parked, its task cancelled or held: its process is stopped (a kill of the parked agent), nothing starts"
      (is (= [{:op :kill :task "demo/a" :reason :cancelled}]
             (actions-for (db-of [(decl "a")] :extra (into (parked :parked) (task/execution-tx ["demo/a"] :cancelled))) :woken [(akey "a")])))
      (is (= [{:op :kill :task "demo/a" :reason :held}]
             (actions-for (db-of [(decl "a")] :extra (into (parked :parked) (task/execution-tx ["demo/a"] :held)))))))
    (testing "parked in an earlier boot: its process is gone, so the next start is a spawn (a load of the session)"
      (is (= [(spawn "a")] (actions-for (db-of [(decl "a")] :extra (work "a" :parked :boot "boot-0")) :woken ["a-a"]))))
    (testing "it waited with its proofs green: done, never parked (start stops its kept process)"
      (is (= {:outcome :ready :reasons []} (judgment-of db0 "a" {:end {:outcome :waiting} :results {(t "a") :pass}}))))
    (testing "its task moved to another branch: the parked process (forked on the old one) is stopped, and the next start is a spawn"
      (let [db (db-of [(decl "a")] :extra (concat (parked :parked) [{:seon.task/id "demo/a" :seon.task/branch :elsewhere}]))]
        (is (= [{:op :kill :task "demo/a" :reason :env-changed}] (actions-for db :woken [(akey "a")])) "a task has one flight, so one action a pass")
        (is (= [(assoc-in (spawn "a") [:env :branch] :elsewhere)] (actions-for (d/db-with db (task/move-tx "demo/a" :parked :killed {})) :woken [(akey "a")])))))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 3 transacts or db-withs x 50k ~ 600,000 ticks
(deftest ^{:seon.test/estimate {:ticks 600000}} a-sessions-next-stage-opens-with-roots-message
  ;; live finding A1 (2026-10-25): a session's next stage (another task, the same agent) opens
  ;; with root's message naming the new task and its task data; a wake of the same task opens with none. The task the
  ;; agent worked before is the one its :seon.agent/task held before (d/history)
  (let [db (-> (db-of [(decl "d") (decl "i" :needs ["d"])])
               (d/db-with (ts/work-tx "demo/d" :done {:agent :a1 :start-ms 1000}))
               (d/db-with (ts/work-tx "demo/i" :running {:agent :a1 :start-ms 2000})))
        body (#'r/stage-boundary db "demo/i")]
    (is (str/starts-with? body "demo/d is done. Your task now is demo/i") body)
    (is (str/includes? body "Its plan: Make d true.\n\nMake i true.\n\nIts proofs: demo-test/d-test demo-test/i-test") "with the new task's plan and proofs, as they accumulated")
    (is (str/includes? body "demo-test/i-test") "and its proofs")
    (is (nil? (#'r/stage-boundary db "demo/d")) "a session's first stage opens with none")
    (is (nil? (#'r/stage-boundary (d/db-with db (ts/work-tx "demo/i" :running {:agent :a1 :start-ms 3000})) "demo/i")) "nor does a wake of the same task")))

;; Estimate: 1 test-branch! x 1M (a pointer write, connect, release, delete); 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-step-reads-the-db-its-own-write-made
  (ts/test-branch! (fn [_ conn]
  ;; the stage-boundary red (seon.agent-test/a-requests-implement-continues-its-designs-request):
  ;; the writer answers a write before main's commit reaches the reconciler (two ins), so :started
  ;; read a db without the start :spawned wrote, found no earlier task on its agent, and wrote
  ;; no :stage entry. An answer main has not reached waits for main's commit of its t. A commit's
  ;; :ns is 0: the proc measures a stat from it, which nothing here reads (no clock in the test)
  (let [commit (fn [_ tx] (let [rep (d/transact conn {:tx-data tx})] [(:db-after rep) (pdb/report->tx rep)]))
        main (fn [st db ops] (r/reconciler-proc st :main (note db (:t st) ops)))
        reqs (fn [outs] (remove #(= :stat (:op %)) (:to-api outs)))
        ;; the session's earlier stage: demo/d, done, on the agent the spawn will answer
        d1 (ts/work-tx "demo/d" :done {:agent :a1})
        db (db-of [(decl "d") (decl "i")] :released ["demo/i"] :extra d1)
        db (d/commit-as-db conn (:commit (ts/commit-of conn db)))
        [st outs] (main (r/reconciler-proc {:boot "boot-1" :conn conn}) db [])
        asked (first (filter #(= :spawn (get-in % [:req :op])) (reqs outs)))
        [st outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r {:id :a1}})
        w (first (filter #(= :write (get-in % [:req :op])) (reqs outs2)))
        [db2 ops2] (commit db (get-in w [:req :req :tx-data]))
        ;; the answer first, as the writer sends it, then main's commit
        [st outs3] (r/reconciler-proc st :answers {:tag (:tag w) :r {:ok true :t (:max-tx db2) :datoms (count ops2)}})
        [st4 outs4] (main st db2 ops2)
        ;; a commit main's buffer has not brought yet: a later one takes the answer too
        [db3 ops3] (commit db2 [{:seon.task/id "demo/d" :seon.task/plan "Make d true, again."}])
        [_ outs5] (main st db3 ops3)
        stage (fn [outs] (filter #(and (= :message (get-in % [:req :op])) (= :root (get-in % [:req :from]))) (reqs outs)))]
    (is (= "demo/i" (get-in asked [:req :opts :task])) "the next stage's spawn")
    (is (empty? (reqs outs3)) "its write's answer waits: main has not reached the write")
    (is (= 1 (count (stage outs4))) "main's commit of it takes the answer: the stage's entry, read off the db holding the start")
    (is (empty? (:behind st4)) "and holds it no longer")
    (is (= 1 (count (stage outs5))) "a later commit than the write's takes it as well"))))
)

;; Estimate: 1 db-of x 0.5M + 3 judgings x 0.7M + one decide over a few facts ~0.2M ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-proof-a-branch-registers-reaches-main-at-the-agents-end
  ;; owner, 2026-10-25 (Dogfood rulings): a design registers the red proofs it writes (my.task/update! :seon.task/proofs conj 't), and
  ;; the implement step inherits them; owner, 2026-11-16 (via root): one merge carries everything, so they reach main with the branch's
  ;; :merge, and the judge reads them from the branch it judges (seon.reconcile/branch-acc). Red first: with the early copy gone the
  ;; judge read the design's proofs from main and found none (:no-proofs)
  (let [base (db-of [(decl "a" :proofs #{}) (decl "b" :proofs #{'demo-test/kept-test 'demo-test/dropped-test})] :extra (work "a" :running))
        branch (d/db-with base [[:db/add [:seon.task/id "demo/a"] :seon.task/proofs 'demo-test/a-design-wrote-test]
                                [:db/retract [:seon.task/id "demo/b"] :seon.task/proofs 'demo-test/dropped-test]])
        reasons (fn [db] (set (map (juxt :reason :symbol) (:seon.task/reasons (task/task db "demo/a")))))
        judged (:db (judge! base "a" {:outcome :green} :branch branch))]
    (is (contains? (reasons judged) [:proof-unknown 'demo-test/a-design-wrote-test]) (str "the done rule judges the proof the branch registered: " (reasons judged)))
    (is (empty? (:seon.task/proofs (task/task judged "demo/a"))) "and main holds it only once the branch merges")
    (is (= #{'demo-test/kept-test 'demo-test/dropped-test} (set (:seon.task/proofs (task/task judged "demo/b")))) "a dropped one too")
    (testing "the implement step that needs the design inherits it, on the same branch (live finding F3)"
      (let [base (d/db-with base (task/create-tx base [(decl "c" :needs ["a"] :proofs #{})]))
            branch (d/db-with base [[:db/add [:seon.task/id "demo/a"] :seon.task/proofs 'demo-test/a-design-wrote-test]])
            judged (:db (judge! (d/db-with base (into (work "c" :running) (task/execution-tx ["demo/c"] :running))) "c" {:outcome :green}
                                :branch branch :diff {:base base}))]
        (is (contains? (set (map (juxt :reason :symbol) (:seon.task/reasons (task/task judged "demo/c")))) [:proof-unknown 'demo-test/a-design-wrote-test])
            "the implement's done rule holds the design's proof")))
    (testing "a proof set main changed too merges per proof in the one merge: the branch's drop and main's addition both hold"
      (let [main (d/db-with base [[:db/add [:seon.task/id "demo/b"] :seon.task/proofs 'demo-test/mains-test]])]
        (is (empty? (:conflicts (pm/decide base branch main))) "no conflict: each proof is its own fact")))))

;; Estimate: pure data in, pure data out, a few 10k ticks ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} a-refused-merge-candidate-is-the-gates-error
  ;; the Sonnet review of gate-schema (2026-10-30): the gate took the candidate's :text with no check, so a
  ;; writer that refused the candidate sent the gate no files, and it would have tested main as it is
  (let [id "demo/a-merge"
        refused (contract/error :seon.system/refused #'seon.reconcile-test/a-refused-merge-candidate-is-the-gates-error "no branch demo/x")
        st {:db (ts/test-db) :boot "b1" :rid 0 :pending {} :flights {id {:act {:op :gate :task id :tests ['seon.system-test/boots]}}}}
        [_ outs] (#'r/advance st id :gated refused)
        reqs (map :req outs)]
    (is (not-any? #(= :tests (:kind %)) reqs) "no tests run on a checkout with no candidate")
    (let [tx (some #(get-in % [:req :tx-data]) reqs)]
      (is (some #(= {'seon.system-test/boots :error} (:seon.task/deferred-results %)) tx)
          (str "each deferred test :error: " (pr-str tx)))
      (is (some #(= refused (:seon.task/error %)) tx) "with why it was refused as the task's error"))))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 2 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 2 transacts or db-withs x 50k ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-test-red-on-main-at-its-base-decides-nothing-at-the-gate
  ;; docs/tasks.md#the-done-rule: a deferred test red on main at the task's base digest (the :tests effect's :main-red,
  ;; seon.proofs/main-red) is main's: recorded with the rest, left out of the gate's decision, and told once as the system's
  ;; problem (:seon.prove/full-run-red); a red of the task's own still holds it
  (let [id "demo/a-merge"
        boots 'seon.system-test/boots other 'seon.system-test/other
        db (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :gate :members {:seon.task/deferred {boots "d-boots" other "d-other"}})))
        st (assoc (st-of db) :flights {id {:act {:op :gate :task id :tests [boots other]}}})
        answer (fn [rs mains] {:value {:results rs :main-red mains}})
        after (fn [outs] (d/db-with db (some #(get-in % [:req :req :tx-data]) outs)))]
    (testing "its only red is main's: the task is ready, its results kept whole, and the system told"
      (let [[_ outs] (#'r/advance st id :gated (answer {boots :fail other :pass} #{boots}))
            tk (task/task (after outs) id)]
        (is (= :ready (:seon.task/outcome tk)))
        (is (= {boots :fail other :pass} (:seon.task/deferred-results tk)))
        (is (= [[:platform :seon.prove/full-run-red [boots]]]
               (for [o outs :when (= :problem (:op o))] [(:class o) (get-in o [:error :seon.error/type]) (get-in o [:error :tests])])))))
    (testing "a red of its own beside main's: asked of main, green there, held, naming only its own"
      (let [[st2 _] (#'r/advance st id :gated (answer {boots :fail other :fail} #{boots}))
            [_ outs] (#'r/advance st2 id :gate-main-asked {:reports [{:var (str "#'" other) :type :pass}]})
            tk (task/task (after outs) id)]
        (is (= [:closed :held] ((juxt :seon.task/outcome :seon.task/execution) tk)))
        (is (str/includes? (get-in tk [:seon.task/failure :reason]) (str other " fail")))
        (is (not (str/includes? (get-in tk [:seon.task/failure :reason]) (str boots))))))
    (testing "a test the run answered nothing of is recorded :none and holds the task, though the only red is main's"
      (let [[_ outs] (#'r/advance st id :gated (answer {boots :fail} #{boots}))
            tk (task/task (after outs) id)]
        (is (= {boots :fail other :none} (:seon.task/deferred-results tk)))
        (is (= :closed (:seon.task/outcome tk)))
        (is (str/includes? (get-in tk [:seon.task/failure :reason]) (str other " none")))))
    (testing "no :main-red and main green: the red is the task's, and no problem is told"
      (let [[st2 _] (#'r/advance st id :gated (answer {boots :fail other :pass} nil))
            [_ outs] (#'r/advance st2 id :gate-main-asked {:reports [{:var (str "#'" boots) :type :pass}]})]
        (is (= :closed (:seon.task/outcome (task/task (after outs) id))))
        (is (not-any? #(= :problem (:op %)) outs))))))

;; Estimate: 1 mdb x 1M (as the gate test above) + 3 advance calls x 0.7M (one run-digest of a small main ~0.3M each) ~ 3,500,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3500000}} a-red-nobody-knew-on-main-is-asked-of-main-before-the-gate-decides
  ;; docs/tasks.md#the-done-rule (seon.proofs/a-red-main-holds-is-mains-whatever-the-digest): a red whose verdict at base nobody
  ;; knows yet (main's own run had not reached it: the first real seon_done closed on six such) is asked of main by name, and the
  ;; gate decides on main's answer: red there, main's; green there, the task's own. Red before: the gate decided at once, every
  ;; such red the task's
  (let [id "demo/a-merge"
        boots 'seon.system-test/boots other 'seon.system-test/other
        db (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :gate :members {:seon.task/deferred {boots "d-boots" other "d-other"}})))
        st (assoc (st-of db) :flights {id {:act {:op :gate :task id :tests [boots other]}}})
        gate {:value {:results {boots :fail other :pass} :main-red #{}}}
        main (fn [status] {:reports [{:var (str "#'" boots) :type status}]})
        after (fn [outs] (d/db-with db (some #(get-in % [:req :req :tx-data]) outs)))
        [st2 outs] (#'r/advance st id :gated gate)
        ask (some #(when (= :prove (get-in % [:req :op])) (:req %)) outs)]
    (testing "the red nobody knew is asked of main by name, and nothing is decided before its answer"
      (is (= {:branch :main :names #{boots} :mode :judge} (select-keys ask [:branch :names :mode])) (pr-str outs))
      (is (not-any? #(get-in % [:req :req :tx-data]) outs) "no write yet"))
    (testing "main holds it red: main's, so the task is ready and the system is told"
      (let [[_ outs] (#'r/advance st2 id :gate-main-asked (main :fail))]
        (is (= :ready (:seon.task/outcome (task/task (after outs) id))))
        (is (= [boots] (for [o outs :when (= :problem (:op o))] (first (get-in o [:error :tests])))))))
    (testing "main holds it green: the task's own red, held"
      (let [[_ outs] (#'r/advance st2 id :gate-main-asked (main :pass))]
        (is (= :closed (:seon.task/outcome (task/task (after outs) id))))))
    (testing "main answered nothing of it (a process test): held, its reason saying main's verdict is unknown"
      (let [[_ outs] (#'r/advance st2 id :gate-main-asked {:reports []})
            tk (task/task (after outs) id)]
        (is (= :closed (:seon.task/outcome tk)))
        (is (str/includes? (get-in tk [:seon.task/failure :reason]) "main's verdict unknown") (pr-str (:seon.task/failure tk)))))))

;; Estimate: 1 db-of 0.5M + 1 d/with of a retractEntity 50k + the index listed twice (0.6M each) + 1 observe from the last 0.3M ~ 2M ticks
(clojure.test/deftest ^{:seon.test/estimate {:ticks 2000000}}
 an-observed-retraction-leaves-no-residue
 (let [db (db-of diamond)
       {db' :db-after txd :tx-data} (d/with db [[:db/retractEntity [:seon.task/id "demo/d"]]])
       ops (for [dt txd] [(if (:added dt) :db/add :db/retract) (:e dt) (:a dt) (:v dt)])
       idx (r/index db)
       obs (r/observe idx db' ops)
       listed (r/index db')]
   (testing "a retraction observed from the last index is the index listed"
     (is (= listed obs)))
   (testing "no entry of the gone task is left behind"
     (is (not (contains? (:tasks obs) "demo/d")))
     (is (not (contains? (:acc obs) "demo/d")))
     (is (not (contains? (:depth obs) "demo/d")))
     (is (not (contains? (:done obs) "demo/d")))
     (is (not (contains? (:needed-by obs) "demo/d")))
     (is (every? seq (vals (:needed-by obs))) "no empty needed-by set"))))

;; Estimate: 1 transacts or db-withs x 50k ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} an-end-that-parks-runs-no-tests
  ;; docs/turn.md#parked: an agent with no proofs that says something and waits, or spends its turns, has claimed nothing, so nothing is judged by tests: a chat reply isn't "working" through a test run
  (is (= :diffed (r/after-work {:value {:outcome :waiting}} nil :work)))
  (is (= :tested (r/after-work {:value {:outcome :cap}} #{} :work)) "a cap is an end, not a park: it is judged")
  (is (= :tested (r/after-work {:value {:outcome :waiting}} #{'demo-test/t} :work)) "a task with proofs has them judged, parked or not")
  (is (= :tested (r/after-work {:value {:outcome :green}} nil :work)) "a claim of done is judged")
  (is (= :tested (r/after-work {:value {:outcome :failed}} nil :work)))
  (is (= :tested (r/after-work {:ex {:seon.error/message "threw"}} nil :work)) "and so is a loop that threw"))

;; Estimate: 3 db-of x 0.5M; 2 judgings x 0.7M; 3 advances or starts x 0.2M; 4 db-withs x 50k ~ 3,500,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3500000}} a-persons-session-never-ends-held-or-dead
  ;; docs/turn.md#parked, seon.task/ends-parked: a person's session (a :user task, request/root) waits for the person. On
  ;; 2026-11-15 request/root's errored turn ("no model provider") went to a test batch, which answered no verdict, so it was held,
  ;; then killed, and no message woke it (wake acts on :parked and :told). Every end of its run parks it, its reason kept, and
  ;; it runs again only on a message (wake), so a turn that always fails costs one run a message
  (let [p (-> (decl "p") (dissoc :seon.task/proofs) (assoc :seon.task/kind :user))
        db (db-of [p] :extra (work "p" :running))
        flight {:act {:op :spawn :task "demo/p" :env {:branch :demo/p}} :agent :a-p}
        tx-of (fn [outs] (vec (mapcat #(get-in % [:req :req :tx-data]) outs)))
        held? (fn [tx] (some #(= [:db/cas [:seon.task/id "demo/p"] :seon.task/execution :running :held] %) tx))
        after (fn [tx] (task/task (d/db-with db tx) "demo/p"))]
    (testing "its turn's end is read, never judged by a test batch, whatever it is"
      (is (= :diffed (r/after-work {:value {:outcome :error}} nil :user)))
      (is (= :diffed (r/after-work {:ex {:seon.error/message "threw"}} nil :user))))
    (testing "an end that would hold a work task parks it"
      (is (= :parked (:outcome (judgment-of db "p" {:end {:outcome :error}}))))
      (is (= :parked (:outcome (judgment-of db "p" {:died {:seon.error/type :seon.agent/died}}))) "and so does a death"))
    (testing "a typed error from its work: parked, not held, its error its reason"
      (let [err (contract/error :seon.writer/refused 'seon.writer/refused "the run answered no verdict" {})
            [_ outs] (#'r/advance (st-of db :flights {"demo/p" (assoc flight :step :worked)}) "demo/p" :worked err)
            tx (tx-of outs)]
        (is (not (held? tx)) "not held")
        (is (= :parked (:seon.task/outcome (after tx))))
        (is (re-find #"no verdict" (str (:seon.task/reason (after tx)))))))
    (testing "its agent's death: parked, its execution still running"
      (let [died (contract/error :seon.agent/died 'seon.system/end-agent "agent a-p died: signal 9" {:agent :a-p :exit {:signal 9}})
            tk (after (#'r/dead-tx (st-of db) "demo/p" died nil))]
        (is (= :parked (:seon.task/outcome tk)))
        (is (= :running (:seon.task/execution tk)))))
    (testing "a restart reaps it parked, not lost"
      (let [db0 (db-of [p] :extra (work "p" :running :boot "boot-0"))
            act (first (filter #(= :reap (:op %)) (actions-for db0)))
            [_ outs] (#'r/start (st-of db0) act)]
        (is (some? act) "the previous root's run is reaped")
        (is (= :parked (:seon.task/outcome (task/task (d/db-with db0 (tx-of outs)) "demo/p"))))))))

(defn- with-refine-section
  "db holding the section a refine cites (docs/tasks.md#a-refine-curates-its-own-task), as a root's store holds the wiki: a test
  world holds no page but the prompt's stub, and a plan's cite is written only to a section the store holds."
  [db] (d/db-with db (seon.doc/load-tx db "docs" {"docs/tasks.md" "# Tasks\n\n## A refine curates its own task\n\nA refine curates the task it needs.\n"})))

;; Estimate: 1 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 transacts or db-withs x 50k ~ 600,000 ticks
(deftest ^{:seon.test/estimate {:ticks 600000}} every-end-gets-a-refine-after-it-and-its-review
  ;; docs/tasks.md#a-refine-curates-its-own-task (the owner, 2026-11-12: "It has proofs and a needs pointing at the task and the normal
  ;; task machinery should just work"): a task's end writes its refine, a :work task after it on a branch of its own, proved by what it
  ;; edits and the task's own proofs, released; and the refine's review, its merge step, held for a session
  (let [;; the call a refine reads its run with, as a store holding the program holds it
        db (d/db-with (with-refine-section (db-of [(decl "a")])) [{:var/name 'my.task/run}])
        tx (task/refine-tx db "demo/a")
        db1 (d/db-with db tx)
        r (task/task db1 "demo/a-refine")
        v (task/task db1 "demo/a-refine-review")]
    (is (seq tx) "an ended task gets its refine")
    (is (contains? (set (keep task/cited-name (task/task-cites db1 "demo/a-refine"))) 'my.task/run)
        "its page cites the one call that reads the run folded (my.task/run): the loop task's refine spent 24 turns writing that read")
    (is (= :work (:seon.task/kind r)) "a task like any: judged by its proofs")
    (is (= #{"demo/a"} (:seon.task/after r)) "after the task it refines: it starts when that one has ended")
    (is (empty? (:seon.task/needs r)) "it needs nothing: no result of the task is wanted")
    (is (= #{'seon.task-files-test/the-shipped-task-files-are-whole-tasks-that-merge-themselves} (set (:seon.task/proofs r)))
        "proved by what it edits alone: it changes no code, so the task's red proofs could never pass on its branch (loop 2, 2026-11-12)")
    (is (not= (:seon.task/branch (task/task db1 "demo/a")) (:seon.task/branch r)) "on a branch of its own: a new agent works it")
    (is (= :running (:seon.task/execution r)) "released")
    (is (task/refine? db1 "demo/a-refine") "a refine by its cite of the section")
    (is (= #{"demo/a-refine"} (:seon.task/needs v)) "the review needs the refine")
    (is (= (:seon.task/branch r) (:seon.task/branch v)) "on its branch: the refine's merge step")
    (is (= :merge (:seon.task/kind v)))
    (is (= :held (:seon.task/execution v)) "held: a session works it, never a model")
    (is (nil? (task/refine-tx db1 "demo/a")) "none twice")
    (is (nil? (task/refine-tx db1 "demo/a-refine")) "a refine gets none")
    (is (nil? (task/refine-tx db1 "demo/a-refine-review")) "nor its review, a merge step")))

;; docs/tasks.md#a-refine-curates-its-own-task: a refine nobody acted on (cancelled, or ended with no change: held at its cap, dead)
;; is superseded by the next end's, read from its own facts: one stale refine wrote none again for a task, ever (root, 2026-11-13: a
;; refine capped with the old proofs blocked every later end of seon.ctx/a-require-at-the-repl-says-the-form-that-records-it)
(deftest ^{:seon.test/estimate {:ticks 20000000}} a-refine-nobody-acted-on-is-superseded-by-the-next-ends
  (let [db (with-refine-section (db-of [(decl "a")]))
        db1 (d/db-with db (task/refine-tx db "demo/a"))
        branch0 (:seon.task/branch (task/task db1 "demo/a-refine"))
        ;; what a cancel writes (my.root/cancel-tx): each held or running task :cancelled
        cancelled (d/db-with db1 [{:seon.task/id "demo/a-refine" :seon.task/execution :cancelled} {:seon.task/id "demo/a-refine-review" :seon.task/execution :cancelled}])
        capped (d/db-with db1 [{:seon.task/id "demo/a-refine" :seon.task/execution :held :seon.task/outcome :cap}])
        ;; a hold as the reconciler writes it (r/hold-tx): its failure and evidence beside the outcome
        failed (d/db-with db1 (r/hold-tx db1 "demo/a-refine" [{:seon.task/id "demo/a-refine" :seon.task/outcome :dead}] "its agent died" {:red ['demo/a-test]}))]
    (is (nil? (task/refine-tx db1 "demo/a")) "a refine in flight is not superseded")
    (is (nil? (task/refine-tx (d/db-with db1 [{:seon.task/id "demo/a-refine" :seon.task/execution :held :seon.task/outcome :gate}]) "demo/a"))
        "nor one waiting on its gate: it never ended (the outcome schema's sets: ended less finished)")
    (is (= (task/refine-tx capped "demo/a") (task/refine-tx capped "demo/a")) "one end names one fresh branch: a replay writes the same")
    (is (every? (d/entity failed [:seon.task/id "demo/a-refine"]) task/held-attrs) "the hold wrote what failed and its evidence")
    (doseq [[how db2] [["cancelled" cancelled] ["ended at its cap" capped] ["held by its failure" failed]]]
      (let [tx (task/refine-tx db2 "demo/a")
            db3 (when (seq tx) (d/db-with db2 tx))
            r (some-> db3 (task/task "demo/a-refine"))]
        (is (seq tx) (str "the next end writes its refine over one " how))
        (is (= :running (:seon.task/execution r)) (str "released again over one " how))
        (is (empty? (filter (d/entity db3 [:seon.task/id "demo/a-refine"]) task/end-attrs)) "holding none of the facts the end it supersedes wrote (task/end-attrs)")
        (is (not= branch0 (:seon.task/branch r)) "on a fresh branch: a new agent, none of the old one's records")
        (is (= (:seon.task/branch r) (:seon.task/branch (task/task db3 "demo/a-refine-review"))) "its review on its branch")
        (is (= :held (:seon.task/execution (task/task db3 "demo/a-refine-review"))) "the review held again")))
    ;; a refine declared when its proofs were others (a test since deleted) states its declaration's proofs again, as a changed
    ;; task file's entry does (entity-tx): the loop task's superseded refine kept the deleted test beside the new one and capped
    (let [stale (d/db-with capped [[:db/add [:seon.task/id "demo/a-refine"] :seon.task/proofs 'gone-test/deleted]])
          db3 (d/db-with stale (task/refine-tx stale "demo/a"))]
      (is (= (set (:seon.task/proofs (first (task/refine-decls {:seon.task/id "demo/a" :seon.task/ns 'demo :seon.task/name "a"}))))
             (set (:seon.task/proofs (task/task db3 "demo/a-refine"))))
          "only its declaration's proofs: a dropped proof is retracted"))))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-refine-is-done-when-its-task-names-a-function-it-did-not
  ;; docs/tasks.md#a-refine-curates-its-own-task (root, 2026-11-13): a refine's one proof is green before it starts, so it never knew
  ;; what done is: three refines of the loop task, 120 turns, no write. It is refused :no-change-site until the plan of the task it
  ;; refines cites a program function that plan did not cite at the refine's merge base: red before its write, green after; the
  ;; agent's check and root's judging read it by one function (seon.task/names-new-site?). Estimate: three small worlds, a few pulls
  (let [base (d/db-with (with-refine-section (db-of [(decl "a")])) [{:var/name 'app/f}])
        base (d/db-with base (task/refine-tx base "demo/a"))
        named (d/db-with base (task/create-tx base [(assoc (decl "a") :seon.task/plan "Make a true: the change is in `app/f`.")]))
        reworded (d/db-with base (task/create-tx base [(assoc (decl "a") :seon.task/plan "Make a true, carefully.")]))
        in (fn [named?] {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{} :changed? true :named-site? named?})]
    (is (false? (task/names-new-site? base base "demo/a-refine")) "before its write: the task's plan names no new function")
    (is (false? (task/names-new-site? base reworded "demo/a-refine")) "a reworded plan that names none is no change site")
    (is (true? (task/names-new-site? base named "demo/a-refine")) "the plan cites app/f, which it did not before")
    (is (nil? (task/names-new-site? base named "demo/a")) "a task that is no refine is not asked")
    (is (true? (task/names-new-site? base (d/db-with base [{:seon.task/id "demo/a-refine" :seon.task/found-nothing "The run read what its plan cited; nothing it lacked."}]) "demo/a-refine"))
        "or it found nothing, a typed end with its reason (my.task/found-nothing!), never a site invented to pass")
    (is (= [:no-change-site] (mapv :reason (r/done-explain (in false)))) "refused until then")
    (is (= [] (r/done-explain (in true))) "done once it names one")
    (is (= [] (r/done-explain (dissoc (in true) :named-site?))) "not a refine: the rule says nothing")))

(deftest ^{:seon.test/estimate {:ticks 10000000}} a-refine-that-found-nothing-ends-done-not-capped
  ;; runtime's review of 91caa85fca: a refine with nothing to find was refused :no-change-site every turn, so it capped and was
  ;; declared again (refine-tx's supersede), a loop with nothing to find. Its typed end, the reason it found nothing, is done, and
  ;; the reason reaches main for the held review to read. Estimate: one world, two judgings
  (let [db0 (with-refine-section (mdb [(decl "a")] :extra (work "a" :running)))
        {db1 :db} (judge! db0 "a" {:outcome :cap} :results {(t "a") :fail})
        ;; its refine started, as root records a start
        db1 (d/db-with db1 (work "a-refine" :running))
        why "The run read what its plan cited; nothing it lacked."
        found (judge! db1 "a-refine" {:outcome :green} :results {'seon.task-files-test/the-shipped-task-files-are-whole-tasks-that-merge-themselves :pass}
                      :on-branch [{:seon.task/id "demo/a-refine" :seon.task/found-nothing why}])
        silent (judge! db1 "a-refine" {:outcome :green} :results {'seon.task-files-test/the-shipped-task-files-are-whole-tasks-that-merge-themselves :pass})]
    (is (not-any? #{:no-change-site} (map :reason (:seon.task/reasons (task/task (:db found) "demo/a-refine")))) "found nothing: done")
    (is (#{:ready :gate :done} (:outcome found)) (str "judged done, its review next, not told again: " (:outcome found)))
    (is (= why (:seon.task/found-nothing (d/entity (:db found) [:seon.task/id "demo/a-refine"]))) "its reason on main, for the review")
    (is (some #{:no-change-site} (map :reason (:seon.task/reasons (task/task (:db silent) "demo/a-refine")))) "silent: still refused")))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-refines-proofs-are-tests-the-tree-holds
  ;; docs/tasks.md#a-refine-curates-its-own-task: a refine's proofs are named in src (seon.task/refine-proofs), and a name nothing
  ;; checks goes stale: the task-files test it named was deleted (219aea54d5), so every refine's proof had no verdict and it capped
  ;; whatever it did. Each proof a refine declares is a deftest of the checkout's test tree, read from its file. Estimate: one file read
  ;; and parsed a proof
  (doseq [p (:seon.task/proofs (first (task/refine-decls {:seon.task/id "demo/a" :seon.task/ns 'demo :seon.task/name "a"})))
          :let [f (java.io.File. (str (cfg/checkout) "/test/" (-> (namespace p) (str/replace "." "/") (str/replace "-" "_")) ".clj"))
                tests (when (.exists f) (set (for [x (reader/read-text (slurp f) 'user) :when (and (seq? x) (= "deftest" (name (first x))))] (second x))))]]
    (is (contains? tests (symbol (name p))) (str p " names no test the tree holds"))))

;; Estimate: 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions); 1 judgings x 0.7M (the index and the judge-tx) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-task-starts-again-with-a-fresh-agent-on-a-fresh-branch
  ;; docs/tasks.md#a-refine-curates-its-own-task (context, 11-13: done by hand twice for the loop task): a held task restarted is on a
  ;; branch named by its last ending, with none of its end's facts and no agent, its instructions naming the run it left, released; the
  ;; reconciler's next action for it is a spawn. One write (seon.task/restart-tx) serves this, a refine's supersede and attach's fresh.
  ;; Estimate: one small world, one judging, one restart
  (let [db0 (with-refine-section (mdb [(decl "a")] :extra (work "a" :running)))
        {db1 :db} (judge! db0 "a" {:outcome :cap} :results {(t "a") :fail})
        ;; specified on the branch it leaves (seon.turn/specified-tx): the next run starts from main, unspecified until its proof is red there
        db1 (d/db-with db1 [{:seon.task/id "demo/a" :seon.task/specified-turn 4}])
        old (task/task db1 "demo/a")
        db2 (d/db-with db1 (my.root/restart-tx db1 ["demo/a"]))
        a (d/entity db2 [:seon.task/id "demo/a"])]
    (is (= [:held :cap] [(:seon.task/execution old) (:seon.task/outcome old)]) "capped and held, as the loop task was")
    (is (not= (:seon.task/branch old) (:seon.task/branch a)) "a fresh branch")
    (is (= (:seon.task/branch a) (:branch (task/restart-tx db1 "demo/a"))) "named by its ending: the same write names the same branch")
    (is (empty? (filter a seon.task/end-attrs)) "none of the end's facts, no agent")
    (is (nil? (:seon.task/specified-turn a)) "nor when it was specified: its next run is told to specify it again")
    (is (str/includes? (str (:seon.task/instructions a)) "a-a") "its instructions name the agent of the run it left")
    (is (= :running (:seon.task/execution a)) "released")
    (is (some #(and (= :spawn (:op %)) (= "demo/a" (:task %))) (actions-for db2)) "its next action is a new agent")))

(deftest ^{:seon.test/estimate {:ticks 3000000}} the-turns-ledger-is-one-read-of-the-store
  ;; docs/plan.md's measure (the owner, 2026-11-14): one row per task an agent worked, its runs' turns, merged or not and why, read from
  ;; the store, never kept by hand. A run's turns are its agent's highest model-call number on its own branch, where the turn loop
  ;; writes them; a task merged holds :seon.task/merged-t; one not merged, the done rule's reasons. Estimate: two branches read once each
  (let [db (-> (mdb [(decl "a") (decl "b")])
               (d/db-with [{:seon.agent/key "k1" :seon.agent/task [:seon.task/id "demo/a"] :seon.agent/branch :ba :seon.agent/started-ms 1}
                           {:seon.agent/key "k2" :seon.agent/task [:seon.task/id "demo/a"] :seon.agent/branch :ba2 :seon.agent/started-ms 2}
                           {:seon.agent/key "k3" :seon.agent/task [:seon.task/id "demo/b"] :seon.agent/branch :bb :seon.agent/started-ms 3}
                           {:seon.task/id "demo/a" :seon.task/reasons [{:reason :proof-red :symbol (t "a")}]}
                           {:seon.task/id "demo/b" :seon.task/merged-t 42}]))
        calls (fn [k ns] (for [n ns] {:seon.model-call/id (str k "/" n) :seon.model-call/agent [:seon.agent/key k] :seon.model-call/n n}))
        ;; each branch holds only its own agent's calls, as an agent's branch does
        branches {:ba (d/db-with db (calls "k1" [1 2 3])) :ba2 (d/db-with db (calls "k2" [1 2])) :bb (d/db-with db (calls "k3" [1 2 3 4]))}
        read (volatile! [])
        rows (my.root/turns-of db (fn [b] (vswap! read conj b) (branches b)))
        by (into {} (for [r rows] [(:seon.task/id r) r]))]
    (is (= ["demo/a" "demo/b"] (mapv :seon.task/id rows)) "one row per task an agent worked")
    (is (= [["k1" 3] ["k2" 2]] (mapv (juxt :seon.agent/key :seon.agent/turn) (:seon.agent/_task (by "demo/a")))) "its runs in the order they started, each its turns")
    (is (= [:proof-red] (map :reason (:seon.task/reasons (by "demo/a")))) "not merged: why, the done rule's reasons")
    (is (= 42 (:seon.task/merged-t (by "demo/b"))) "merged: when")
    (is (= #{:ba :ba2 :bb} (set @read)) "each branch read")
    (is (= 3 (count @read)) "once")))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-merge-tells-the-agents-whose-tasks-need-it
  ;; plan Stage 1.8: when a task merges, the live agents of the tasks that need it are told (a session waiting on it among them), with
  ;; the form that takes it; no one else is. One function answers who and what (seon.task/landed-messages); the reconciler and bin/land send
  (let [db (-> (mdb [(decl "a") (decl "b") (decl "c") (decl "d")])
               (d/db-with [{:seon.agent/key "kb" :seon.agent/task [:seon.task/id "demo/b"]}
                           {:seon.agent/key "kc" :seon.agent/task [:seon.task/id "demo/c"]}
                           {:seon.agent/key "kd" :seon.agent/task [:seon.task/id "demo/d"] :seon.agent/ended-ms 5}
                           {:seon.task/id "demo/a" :seon.task/merged-t 42}
                           {:seon.task/id "demo/b" :seon.task/agent [:seon.agent/key "kb"] :seon.task/needs [[:seon.task/id "demo/a"]]}
                           {:seon.task/id "demo/c" :seon.task/agent [:seon.agent/key "kc"]}
                           {:seon.task/id "demo/d" :seon.task/agent [:seon.agent/key "kd"] :seon.task/needs [[:seon.task/id "demo/a"]]}]))
        ms (task/landed-messages db ["demo/a"])]
    (is (= ["kb"] (mapv :to ms)) "the live agent of the task that needs it, and no other")
    (is (every? #(str/includes? (:body (first ms)) %) ["demo/a" "demo/b" "(my.branch/merge-main!)"])
        (str "what landed, the task it unblocks, and the form that takes it: " (pr-str ms)))
    (is (empty? (task/landed-messages db ["demo/c"])) "a task nothing needs tells no one")
    (let [[m] (task/landed-tx db ["demo/a"] 7)]
      (is (= [[[:seon.agent/key "kb"]] seon.task/system-sender (:body (first ms))] [(:seon.message/to m) (:seon.message/from m) (:seon.message/body m)])
          "written as one message from the system to it, which the merge's write carries and so wakes (seon.reconcile/write)")
      (is (= m (first (task/landed-tx db ["demo/a"] 7))) "the same landing on the same db names the same message"))))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-cap-ends-the-run-held-and-gets-its-refine
  ;; docs/tasks.md#a-refine-curates-its-own-task (Optimization's restated claim, 2026-11-12): a cap ends the run, failed in its number
  ;; of turns; nothing parks at a cap; it is held, its release runs it again from its branch's head, and it gets its refine as any end
  (let [db0 (with-refine-section (mdb [(decl "a")] :extra (work "a" :running)))
        {db1 :db} (judge! db0 "a" {:outcome :cap} :results {(t "a") :fail})
        a (d/entity db1 [:seon.task/id "demo/a"])]
    (is (= :cap (:seon.task/outcome a)))
    (is (= :held (:seon.task/execution a)) "held: no agent waits parked on it")
    (is (= :running (:seon.task/execution (task/task db1 "demo/a-refine"))) "its refine, released")
    (is (some #(and (= :spawn (:op %)) (= "demo/a-refine" (:task %))) (actions-for db1))
        "the refine starts as any task does: a new agent on its own branch")))

;; Estimate: 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 1 judgings x 0.7M (the index and the judge-tx) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-green-end-gets-its-refine-too
  ;; docs/tasks.md#a-refine-curates-its-own-task: the owner's flow, every end; a done task's refine curates what worked
  (let [db0 (with-refine-section (mdb [(decl "a")] :extra (work "a" :running)))
        {db1 :db} (judge! db0 "a" {:outcome :green} :results {(t "a") :pass})]
    (is (task/task db1 "demo/a-refine"))))

;; Estimate: 2 db-of x 0.5M (test-db fork 0.1M + ~4 tasks x 60k create-tx + execution and boot transacts 0.1M); 1 index or actions calls x 0.7M (0.2M + ~100k a task x ~4, then the actions) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-task-after-another-starts-when-it-ends-whatever-its-outcome
  ;; docs/tasks.md#a-refine-curates-its-own-task (Data model's ruling, 2026-11-12): :seon.task/after waits for the END of a task, its
  ;; outcome whatever it is, where :seon.task/needs waits for its result (merged or done); a refine is after the task it refines
  (let [decls [(decl "a") (assoc (decl "r") :seon.task/after #{"demo/a"}) (decl "n" :needs ["a"])]
        starts (fn [db] (set (keep #(when (#{:spawn :wake} (:op %)) (:task %)) (actions-for db))))
        running (db-of decls :extra (work "a" :running))
        capped (db-of decls :extra (work "a" :cap))]
    (is (not (contains? (starts running) "demo/r")) "not while the task it is after runs")
    (is (contains? (starts capped) "demo/r") "a cap is an end: the task after it starts")
    (is (not (contains? (starts capped) "demo/n")) "a task that needs a's result still waits: a cap is no result")))

;; docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program: replaces the `swarm` check step's claim (test/seon/e2e.clj swarm, the deleted step's specs).
;; The convergence lives in the writer's one :merge and seon.merge, not in the reconciler's step (which only starts merges one at a time,
;; merges-start-one-at-a-time-for-a-merge-step-read-from-data), so it is held there: n tasks over 5 shared vars, each task's branch rewrites its
;; var; every round asks each pending branch's :merge in turn (main merged into it in the writer's step), a branch whose var an earlier one
;; moved gets its unmerged entries, keeps its own side (as swarm's script does) and merges in the next round. Estimate: 12 branches, 5+12
;; refresh-txs, 3 rounds of at most 12 :merge steps (each a decide and replay over one key), 7 settles; about 40 transactions of a few
;; hundred datoms each at ~5,000 ticks apiece, plus a branch fork each.
(deftest ^{:seon.test/estimate {:ticks 100000000}} concurrent-merges-into-main-converge-whatever-the-order
  {:seon.test/estimate {:ticks 500000}}
  (ts/test-branch!
   (fn [cfg conn]
     (let [n 12
           path "src/app/sw.clj"
           s "{:malli/schema [:=> [:cat :int] :int]}"
           text (fn [bodies] (str "(ns app.sw)\n" (apply str (for [j (range 5)] (str "(defn v" j " " s " [x] " (get bodies j "x") ")\n")))))
           on (fn [c files] (d/transact c (source/refresh-tx @c files)))
           branch (fn [nm] (let [b (pdb/on-main (:branch cfg) nm)] (d/branch! conn (:branch cfg) b) [b (pdb/connect (assoc cfg :branch b))]))
           w (volatile! (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer (a/chan 4) :events (a/chan 4)}))
           ask (fn [req] (let [[st outs] (#'writer/writer-proc @w :in (assoc req :dest {:rid 1}))] (vreset! w st) (:r (first (:to-api outs)))))
           src-of (fn [db j] (:form/src (:var/form (d/entity db [:var/name (symbol (str "app.sw/v" j))]))))
           ;; keep the branch's own side of each entry its :merge left (my.branch/resolve!), on a connection of its own: the writer wrote them
           settle! (fn [b] (let [c (pdb/connect (assoc cfg :branch b))]
                             (try (let [es (pm/unmerged @c)] (doseq [e es] (d/transact c (pm/resolve-tx @c (:key e)))) (count es))
                                  (finally (d/release c)))))
           conns (atom [])]
       (try
         (on conn {path (text {})})
         (let [tasks (vec (for [k (range n)
                                :let [[b bc] (branch (keyword (str "sw-s" k)))]]
                            (do (swap! conns conj bc)
                                (on bc {path (text {(mod k 5) (str "(+ x " (mod k 5) (apply str (repeat (quot k 5) " 0")) ")")})})
                                {:k k :branch b :conn bc :body (str "(+ x " (mod k 5) (apply str (repeat (quot k 5) " 0")) ")")})))]
           (loop [pending tasks rounds 0 conflicts 0 order []]
             (if (and (seq pending) (< rounds 10))
               (let [rs (mapv #(ask {:op :merge :branch (:branch %) :proved-t (:max-tx @conn)}) pending)
                     done (keep identity (map (fn [t r] (when (:merged? r) t)) pending rs))
                     left (remove (set done) pending)]
                 (is (seq done) "every round merges at least one: no branch waits on nothing")
                 (is (every? #(seq (:conflicts %)) (remove :merged? rs)) (str "what is not merged holds its entries, never lost: " (pr-str (remove :merged? rs))))
                 (recur (vec left) (inc rounds) (+ conflicts (reduce + (map #(settle! (:branch %)) left))) (into order done)))
               (do
                 (is (empty? pending) "every task's merge went in")
                 (is (= 3 rounds) "the rounds are the most tasks one var has")
                 (is (= n (count order)))
                 (is (pos? conflicts) "the tasks on one var did conflict, and each was settled on its branch")
                 (doseq [j (range 5)]
                   (is (= (:body (last (filter #(= j (mod (:k %) 5)) order)) ) (second (re-find #"\[x\] (.*)\)$" (src-of @conn j))))
                       (str "main holds v" j " as the last task merged on it wrote it")))
                 (is (every? #(not= "x" (second (re-find #"\[x\] (.*)\)$" (src-of @conn %)))) (range 5)) "no var is left as it began")))))
         (finally (run! d/release @conns)))))))

;; Estimate: 1 mdb x 1M (as db-of with a merge task for each of ~4 tasks); 3 judgings x 0.7M (the index and the judge-tx); 1 transacts or db-withs x 50k ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-refine-is-done-by-its-own-change-and-a-run-that-took-no-turn-gets-none
  ;; docs/tasks.md#a-refine-curates-its-own-task (context's loops, 2026-11-12): loop 1's refine was done at turn 0 (its proofs the task's,
  ;; green), loop 2's capped never able to pass (the task's, red), and data's run died before turn 1 and its refine capped with
  ;; nothing to read. The done rule refuses a branch that changed nothing (:no-change, red first for every task); a refine proves
  ;; what it edits; a run that took no turn gets no refine
  (is (= [:no-change] (mapv :reason (r/done-explain {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{} :changed? false})))
      "proofs green, nothing changed: proved nothing")
  (is (= [] (r/done-explain {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{} :changed? true})) "green and changed: done")
  (is (= [] (r/done-explain {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{}})) "not measured: the rule says nothing")
  (is (= [] (r/done-explain {:kind :merge :proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{} :changed? false}))
      "a merge task's work is the merge: its diff can be empty once main is in (swarm's merge steps)")
  (let [db0 (with-refine-section (mdb [(decl "a")] :extra (work "a" :running)))]
    (is (= :told (:outcome (judge! db0 "a" {:outcome :green} :results {(t "a") :pass} :diff {:ours []})))
        "the judge refuses a green end whose branch changed nothing: told to its agent")
    (is (some #(= :no-change (:reason %)) (:seon.task/reasons (task/task (:db (judge! db0 "a" {:outcome :green} :results {(t "a") :pass} :diff {:ours []})) "demo/a")))
        "and says why")
    (is (nil? (task/task (:db (judge! db0 "a" {:outcome :cap} :results {(t "a") :fail} :ended-turn 0)) "demo/a-refine"))
        "a run that ended at turn 0 gets no refine")
    (let [died (contract/error :seon.agent/died 'seon.system/api-proc "agent a-a died: exit 70" {:agent :a-a})]
      (is (nil? (task/task (d/db-with db0 (#'r/dead-tx (st-of db0) "demo/a" died nil)) "demo/a-refine"))
          "nor one that died before it wrote an end"))))


;; Estimate: a test db and two pulls = 1000000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-commit-on-gits-main-moves-mains-source-fact
  ;; docs/source.md#commit-the-merges-branch: a write-back committed on git's main from the commit main was imported from moves main's
  ;; source fact to it (its commit and tree), in the same write; a commit on the merges branch, or a refusal, moves nothing
  (let [db (d/db-with (ts/test-db) [{:seon.system/id :root :seon.system/source {:tree "t0" :commit "c0" :dirs ["src"]}}])]
    (is (= [{:seon.system/id :root :seon.system/source {:tree "t1" :commit "c1" :dirs ["src"] :t 7}}]
           (r/source-moved-tx db {:value {:sha "c1" :files 1 :expected "c0" :tree "t1"}} 7)))
    (is (= [] (r/source-moved-tx db {:value {:sha "c1" :files 1}} 7)) "the merges branch: main's files are not in git's main")
    (is (= [] (r/source-moved-tx db {:value {:sha "c1" :files 1 :expected "c9" :tree "t1"}} 7)) "a catch-up moved main's fact in between: never written over")
    (is (= [] (r/source-moved-tx db {:seon.error/type :seon.fx/ref-moved :expected "c0" :found "c2"} 7)) "refused: nothing moved")))

;; Estimate: four calls of a pure function = 100000 ticks
;; task seon.fx/proving-runs-as-flow (agreed with data): the source fact says which main t its tree holds, so the merge gate's base
;; (the write-back's tree) knows how far it lags main (d/changes from that t). Red before: the fact held no :t
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-write-back-records-the-main-t-its-tree-holds
  (let [db (d/db-with (ts/test-db) [{:seon.system/id :root :seon.system/source {:tree "t0" :commit "c0" :dirs ["src"]}}])]
    (is (= [{:seon.system/id :root :seon.system/source {:tree "t1" :commit "c1" :dirs ["src"] :t 536870920}}]
           (r/source-moved-tx db {:value {:sha "c1" :files 1 :expected "c0" :tree "t1"}} 536870920))
        "the t of the db whose files it wrote, in the write that moves :commit")))

(deftest ^{:seon.test/estimate {:ticks 100000}} a-write-back-commits-only-its-roots-merges-branch
  ;; docs/source.md#commit-the-merges-branch: a test root sourced from a session's worktree (on jolt/data) committed its scratch
  ;; task's file onto jolt/data, and the session's landing carried four onto main (test/mbca737a4b73ed79_test.clj, 2026-11-14):
  ;; the write-back took the branch the checkout had checked out, not the root's own
  (is (= {:branch "jolt/seon-merges-x"} (r/write-back-target {:commit "c0" :branch "jolt/data"} "jolt/seon-merges-x"))
      "a checkout on another branch is never written")
  (is (= {:branch "jolt/merges" :expect "c0"} (r/write-back-target {:commit "c0" :branch "jolt/merges"} "jolt/merges"))
      "imported from the root's own branch: a compare-and-swap from that commit")
  (is (= {:branch "jolt/merges"} (r/write-back-target {:commit "c0"} "jolt/merges")) "a detached head: the merges branch")
  (is (= {:branch "jolt/merges"} (r/write-back-target nil "jolt/merges")) "imported from no commit: the merges branch"))

;; Estimate: the test branch 1M + 2 branches and 3 small transactions 2M + one merge-main in the writer (merge-main over a few datoms
;; ~3M, a with and a force-branch! commit ~2M) = 10000000 ticks
;; Estimate: a test branch ~3M + 4 small transactions ~1M + 5 writer asks (a connect and a transact, or a force-branch, each ~0.5M) ~3M = 8000000
(deftest ^{:seon.test/estimate {:ticks 8000000}} the-writer-repairs-a-branch-no-live-agent-holds
  ;; docs/merging.md#root-repairs-a-failed-merge-from-its-repl (Stage 0, 2026-11-15): lb76 was repaired from root's REPL with d/force-branch! and
  ;; a raw connect, since the writer had no op to write on a branch or move its head; now it has both, through the one path, refused
  ;; while a live agent writes its own branch
  (ts/test-branch!
   (fn [cfg conn]
     (let [w (volatile! (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer (a/chan 4) :events (a/chan 4)}))
           ask (fn [req] (let [[st outs] (#'writer/writer-proc @w :in (assoc req :dest {:rid 1}))] (vreset! w st) (:r (first (:to-api outs)))))
           b (pdb/on-main (:branch cfg) :repair-branch)
           _ (d/branch! conn (:branch cfg) b)
           c0 (d/commit-id (pdb/head conn b))
           r (ask {:op :tx :branch b :tx-data [{:seon.agent/key "rp-own" :seon.agent/status :idle}]})
           head (pdb/head conn b)]
       (is (:ok r) (pr-str r))
       (is (= :idle (:seon.agent/status (d/entity head [:seon.agent/key "rp-own"]))) "written on the branch")
       (is (= b (:seon.tx/branch (d/entity head (:max-tx head)))) "by a transaction naming it")
       (is (nil? (d/entity @conn [:seon.agent/key "rp-own"])) "and not on main")
       (testing "its head moved back to a commit it held, while it is still where the asker saw it"
         (is (contract/error? (ask {:op :reset-branch :branch b :to c0 :expected c0})) "a stale head is refused")
         (let [r (ask {:op :reset-branch :branch b :to c0 :expected (d/commit-id head)})]
           (is (:ok r) (pr-str r))
           (is (nil? (d/entity (pdb/head conn b) [:seon.agent/key "rp-own"])) "the branch reads as it was")))
       (testing "a live agent's branch is its own to write"
         (d/transact conn {:tx-data [{:seon.agent/key "rp-live" :seon.agent/status :busy :seon.agent/branch b}] :tx-meta (pdb/tx-meta (:branch cfg))})
         (is (= "rp-live" (:agent (ask {:op :tx :branch b :tx-data [{:seon.agent/key "rp-2" :seon.agent/status :idle}]}))))
         (is (contract/error? (ask {:op :reset-branch :branch b :to c0 :expected (d/commit-id (pdb/head conn b))})))
         (is (contract/error? (ask {:op :drop-branch :branch b})) "nor dropped while it lives"))
       (testing "a branch no live agent holds is dropped; main never"
         (d/transact conn {:tx-data [[:db/add [:seon.agent/key "rp-live"] :seon.agent/status :ended]] :tx-meta (pdb/tx-meta (:branch cfg))})
         (is (= {:ok true :dropped true} (select-keys (ask {:op :drop-branch :branch b}) [:ok :dropped])))
         (is (not (pdb/branch? conn b)) "its head is gone")
         (is (= {:ok true :dropped false} (select-keys (ask {:op :drop-branch :branch b}) [:ok :dropped])) "a second drop finds none")
         (is (contract/error? (ask {:op :drop-branch :branch (:branch cfg)})) "main is never dropped"))))))

;; Estimate: the test branch's store and the writer proc ~5M + one merge of main into a branch (~2.3M, measured at root) + one fresh
;; branch! ~0.5M = ~8M, 10000000
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-resumed-spawn-merges-main-into-its-branch-before-the-fork
  ;; docs/merging.md#a-branch-merges-main-in: a task resumed across a pin kept its branch's old root source tree, so its process was
  ;; refused at init! (seon.fx/runtime-for) before any turn merged main, and every retry was killed at its hello (the owner's chat,
  ;; 2026-11-15). The writer's :spawn of a branch that exists merges main into it first; a fresh branch is main's head as before
  (ts/test-branch!
   (fn [cfg conn]
     (let [w (volatile! (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer (a/chan 4) :events (a/chan 4)}))
           ask (fn [req] (let [[st outs] (#'writer/writer-proc @w :in (assoc req :dest {:rid 1}))] (vreset! w st) (:r (first (:to-api outs)))))
           b (pdb/on-main (:branch cfg) :rs-branch)
           fresh (pdb/on-main (:branch cfg) :rs-fresh)
           _ (d/branch! conn (:branch cfg) b)
           bc (pdb/connect (assoc cfg :branch b))]
       (try
         (d/transact bc {:tx-data [{:seon.agent/key "rs-own" :seon.agent/status :busy}] :tx-meta (pdb/tx-meta b)})
         ;; main moves after the branch was cut (a pin's landing moves main's root facts the same way)
         (d/transact conn {:tx-data [{:seon.agent/key "rs-main" :seon.agent/status :idle}] :tx-meta (pdb/tx-meta (:branch cfg))})
         (let [r (ask {:op :spawn :agent :rs1 :branch b}) head (pdb/head conn b)]
           (is (and (:ok r) (:resumed r)) (pr-str r))
           (is (:ok (:merged r)) (str "the spawn answers the merge it made: " (pr-str (:merged r))))
           (is (some? (d/entity head [:seon.agent/key "rs-main"])) "the resumed branch holds what main gained since it was cut")
           (is (some? (d/entity head [:seon.agent/key "rs-own"])) "and keeps its own work"))
         (let [r (ask {:op :spawn :agent :rs1b :branch b})]
           (is (= 0 (:keys (:merged r))) (str "spawned again with main unmoved, its base is main's head: nothing merged: " (pr-str (:merged r)))))
         (let [r (ask {:op :spawn :agent :rs2 :branch fresh})]
           (is (and (:ok r) (not (:resumed r)) (nil? (:merged r))) (str "a fresh branch is main's head, no merge: " (pr-str r))))
         (finally (d/release bc)))))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} the-writer-merges-main-into-a-branch-on-mains-program
  ;; docs/merging.md#a-branch-merges-main-in: agent lb76's branch held the old seon.merge, so merging itself it ran the old code; the
  ;; merge is decided by the writer, where main's program runs, reading the branch as data, and written onto the branch as main's head
  ;; plus the branch's own datoms, one commit with both parents
  (ts/test-branch!
   (fn [cfg conn]
     (let [w (volatile! (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer (a/chan 4) :events (a/chan 4)}))
           ask (fn [req] (let [[st outs] (#'writer/writer-proc @w :in (assoc req :dest {:rid 1}))] (vreset! w st) (:r (first (:to-api outs)))))
           b (pdb/on-main (:branch cfg) :mm-branch)
           _ (d/branch! conn (:branch cfg) b)
           bc (pdb/connect (assoc cfg :branch b))]
       (try
         (d/transact bc {:tx-data (task/create-tx @bc [{:seon.task/ns 'demo :seon.task/name "mm-own" :seon.task/plan "The branch's own." :seon.task/proofs #{}}]) :tx-meta (pdb/tx-meta b)})
         (d/transact conn {:tx-data [{:seon.agent/key "mm-main" :seon.agent/status :idle}] :tx-meta (pdb/tx-meta (:branch cfg))})
         ;; a task both hold, whose plan the branch changes to cite a var: the cites are derived from its text, never merged, so
         ;; the merge makes them again where it lands
         (d/transact conn {:tx-data (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "mm" :seon.task/plan "Make mm."
                                                             :seon.task/proofs #{'demo-test/mm}}])
                           :tx-meta (pdb/tx-meta (:branch cfg))})
         (let [b2 (pdb/on-main (:branch cfg) :mm-branch-2)
               _ (d/branch! conn (:branch cfg) b2)
               bc2 (pdb/connect (assoc cfg :branch b2))
               t {:seon.task/id "demo/mm" :seon.task/plan "Change `app.m0/f1`."}]
           (try
             (d/transact bc2 {:tx-data (into [[:db/add [:seon.task/id "demo/mm"] :seon.task/plan (:seon.task/plan t)]] (task/rederive-tx @bc2 t))
                              :tx-meta (pdb/tx-meta b2)})
             (d/transact conn {:tx-data [{:seon.agent/key "mm-main-2" :seon.agent/status :idle}] :tx-meta (pdb/tx-meta (:branch cfg))})
             (let [before (mapv task/cited-name (task/cites-of @bc2 (pdb/eid @bc2 :seon.task/id "demo/mm")))
                   r (ask {:op :merge-main :branch b2}) merged (pdb/head conn b2)]
               (is (seq before) "the branch's plan cites the var")
               (is (:ok r) (pr-str r))
               (is (= "Change `app.m0/f1`." (:seon.task/plan (d/entity merged [:seon.task/id "demo/mm"]))) "the branch's plan")
               (is (= before (mapv task/cited-name (task/cites-of merged (pdb/eid merged :seon.task/id "demo/mm"))))
                   "and its cites, made again from it on the merged branch"))
             (finally (d/release bc2))))
         (let [r (ask {:op :merge-main :branch b})
               merged (pdb/head conn b)]
           (is (:ok r) (pr-str r))
           (is (= "The branch's own." (:seon.task/plan (d/entity merged [:seon.task/id "demo/mm-own"]))) "the branch's own fact (what root observes of an agent is root's, never a branch's: {:seon.db/root-only true})")
           (is (= :idle (:seon.agent/status (d/entity merged [:seon.agent/key "mm-main"]))) "and main's"))
         (finally (d/release bc)))))))

;; docs/errors.md#nothing-crashes-and-nothing-hangs: a flight's last write (its :done step) that the writer refused left the task
;; :running with no flight and no problem: it stayed live, the reconciler never went quiet, and seon_done's await-db waited for
;; good (root's read, 2026-11-15: reconcile.clj's :done dropped the flight whatever its answer). Now a refused :done is the
;; reconciler's :platform problem, which the server's :done reads beside its judge's answer (answer-judged). Red before: no output. Estimate: one step of a map, ~0.1M
(deftest ^{:seon.test/estimate {:ticks 300000}} a-flights-refused-last-write-is-a-problem-never-a-silent-end
  (let [st {:db (seon.db/empty-db false) :boot "b" :flights {"t/a" {:act {:op :spawn :task "t/a"} :agent :a1}} :pending {} :rid 0}
        refused (seon.contract/error :seon.writer/refused #'seon.writer/commit-batch "the cas found :ready, not :running" {})
        [st' outs] (#'seon.reconcile/advance st "t/a" :done refused)
        p (first outs)]
    (is (nil? (get-in st' [:flights "t/a"])) "the flight ends")
    (is (= [:problem :platform :seon.reconcile/write-refused] [(:op p) (:class p) (get-in p [:error :seon.error/type])]) (pr-str outs))
    (is (= "t/a" (get-in p [:error :task])) "naming the task")
    (is (= refused (get-in p [:error :refused])) "and the refusal")
    (is (= [] (second (#'seon.reconcile/advance st "t/a" :done {:ok true :t 5}))) "an answered write ends it with nothing to say")
    ;; a task cancelled (or held) while its flight ran: its last write's cas meets the move, an expected refusal, no defect
    ;; (e2e-calc's cancel of calc/neg, the land of 2026-11-15 08:20)
    (let [st (assoc st :db (d/db-with (:db st) [{:seon.task/id "t/a" :seon.task/execution :cancelled}]))]
      (is (= [] (second (#'seon.reconcile/advance st "t/a" :done refused))) "a cancelled task's refused write is no problem"))))

;; docs/server.md#ops: seon_done waits on its judge's own end (seon.reconcile/answer-judged), never on the whole root's quiet: a
;; :running task of anyone's kept the reconciler from quiet, and a judge that left its task :running waited for good (context's
;; 06:31 capture [M]: flights 0, live 1, quiet false, the :done pending). Each order of the judge's end and the wait, red first.
;; Estimate: three steps of the reconciler over a db of one task, ~1M
(deftest ^{:seon.test/estimate {:ticks 1500000}} a-judges-waiter-is-answered-by-the-judges-end-in-either-order
  (let [db (fn [o] (d/db-with (seon.db/empty-db false) [{:seon.task/id "demo/a" :seon.task/outcome o}]))
        st (fn [o] {:db (db o) :boot "b" :flights {} :pending {} :rid 0 :waits [] :judged {} :behind []})
        step (fn [st in m] (first (r/reconciler-proc st in m)))]
    (testing "the wait after the judge's end (no flight left): answered at once from the db, no window"
      (let [p (promise) _ (step (st :closed) :judge {:op :judged :task "demo/a" :p p})]
        (is (= {:task "demo/a" :outcome :closed} (deref p 0 :unanswered)))))
    (testing "the judge left its task :running: the typed error, never a wait for good"
      (let [p (promise) _ (step (st :running) :judge {:op :judged :task "demo/a" :p p})]
        (is (= :seon.reconcile/judge-left-running (:seon.error/type (deref p 0 :unanswered))))))
    (testing "the wait before the judge's end: held while its flight is in the air, answered by the step that ends it"
      (let [p (promise)
            s (assoc (st :running) :flights {"demo/a" {:act {:op :spawn :task "demo/a"} :agent :a1}})
            s (step s :judge {:op :judged :task "demo/a" :p p})]
        (is (= :unanswered (deref p 0 :unanswered)) "held: the judge is still in the air")
        (#'seon.reconcile/answer-judged (assoc s :flights {} :db (db :failed)))
        (is (= {:task "demo/a" :outcome :failed} (deref p 0 :unanswered)) "its end answers it")))
    (testing "while a merge flies, it waits; with none flying and no live merge, nothing will merge it (flow's review)"
      (let [p (promise)
            s (step (assoc (st :ready) :flights {"demo/m" {:act {:op :merge :task "demo/m"}}} :idx {:live #{:x} :merging #{}})
                    :judge {:op :judged :task "demo/a" :p p})]
        (is (= :unanswered (deref p 0 :unanswered)) "a merge in flight: wait, whatever else is live")
        (#'seon.reconcile/answer-judged (assoc s :flights {}))
        (is (= :seon.reconcile/judge-left-ready (:seon.error/type (deref p 0 :unanswered))) "no merge flying or coming, other agents live: answered")))))

;; docs/server.md#ops: a seon_done at a :gate with process tests deferred answers with :next (settled?), its gate running on. web's
;; land through seon_done (2026-11-16, result/ab5418b0): the task at :gate with its tests deferred and its agent ended, the done still
;; waiting, since answer-judged skipped any task with a flight, and at the gate the flight is the gate's run of every reached test
;; (13 min on root's pilot; the 300 s overrun of a-sessions-done-with-its-checkout-merges-its-change-into-main). Red before: held.
;; Estimate: one answer-judged step over a db of one task, ~0.1M
(deftest ^{:seon.test/estimate {:ticks 500000}} a-done-at-a-gate-with-tests-is-answered-while-the-gate-runs
  (let [db (d/db-with (seon.db/empty-db false) [{:seon.task/id "demo/a" :seon.task/outcome :gate :seon.task/deferred {'demo-test/p "d1"}}])
        p (promise)
        st {:db db :boot "b" :pending {} :rid 0 :waits [] :behind [] :judged {"demo/a" [p]}
            :flights {"demo/a" {:act {:op :gate :task "demo/a" :tests ['demo-test/p]}}}}
        st (#'seon.reconcile/answer-judged st)]
    (is (= {:task "demo/a" :outcome :gate} (deref p 0 :unanswered)) "answered at the gate, its tests still running")
    (is (contains? (:flights st) "demo/a") "and the gate's flight goes on")))

;; docs/errors.md#nothing-crashes-and-nothing-hangs: a merge whose request the writer answered with an error (a step that threw,
;; answered at its :dest) moved its task off :ready: (:results r) was nil, so its seon_done waited for its merge for good, and the
;; next pass merged it again (root's question, 2026-11-15). Now it moves :ready -> :closed, held, naming the error. Red before: it
;; stayed :ready. Estimate: one step over a db of one task, ~1M
(deftest ^{:seon.test/estimate {:ticks 1500000}} a-merge-answered-an-error-moves-its-task-off-ready
  (let [db (d/db-with (seon.db/empty-db true) [{:seon.task/id "demo/a" :seon.task/outcome :ready}])
        st {:db db :boot "b" :pending {} :rid 0 :idx {:tasks {"demo/a" {:seon.task/outcome :ready}}}
            :flights {"demo/a" {:act {:op :merge :task "demo/a"}}}}
        e (seon.contract/error :seon.system/proc-defect #'seon.system/flow-error "the merge threw" {})
        [_ outs] (#'seon.reconcile/advance st "demo/a" :merged e)
        tx (set (mapcat #(get-in % [:req :req :tx-data]) outs))]
    (is (contains? tx [:db/cas [:seon.task/id "demo/a"] :seon.task/outcome :ready :closed]) (str "it moves off :ready: " (pr-str tx)))))

;; docs/reconcile.md#the-reconcilers-index: a :running task whose agent's process ended (an outside agent a test ended, an attach
;; whose process died) is not live: it held root's quiet for good, so every later await-quiet in a shard's shared root waited past
;; its bound (the 08:35 capture [M]: reconciler live 1, flights 0, judged [], one quiet wait held). It is reaped :lost, typed.
;; Red before: live. Estimate: one classify of a map, ~50k ticks
(deftest ^{:seon.test/estimate {:ticks 300000}} a-running-task-whose-agent-ended-is-reaped-not-live
  (let [idx (fn [ended] (assoc r/empty-index :root {:boot "b"} :tasks {"t/a" {:seon.task/id "t/a" :seon.task/ns (quote t) :seon.task/name "a" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :b :seon.task/proofs #{} :seon.task/needs #{} :seon.task/kind :work :seon.task/outcome :running :seon.task/agent "k7"}}
                               :agents {"k7" (cond-> {:seon.agent/boot "b" :seon.agent/started-ms 10 :seon.agent/env {:branch :b}} ended (assoc :seon.agent/ended-ms ended))}))
        c (fn [i] (#'seon.reconcile/classify i "t/a"))]
    (is (contains? (:live (c (idx nil))) "t/a") "its agent runs: live")
    (is (not (contains? (:live (c (idx 20))) "t/a")) "its agent ended after it started: not live")
    (is (contains? (:reap (c (idx 20))) "t/a") "reaped")
    (is (contains? (:live (c (idx 5))) "t/a") "an end from before this start (the session's earlier process) is no end of it")))

;; docs/reconcile.md#the-reconcilers-index: the index's agent row is pulled from the store (seon.task/indexed-agent), so the rule
;; that an ended agent's task is not live holds only if the pull carries the end. Red before: the pull read no :seon.agent/ended-ms
;; (nor started-ms), so landing 1's rule never fired in production (web-2's 09:48 overrun) while its classify test passed on a
;; hand-made index. Estimate: one pull of one entity, ~0.2M ticks
(deftest ^{:seon.test/estimate {:ticks 500000}} the-indexed-agent-carries-when-it-started-and-ended
  (let [db (d/db-with (seon.db/empty-db false) [{:seon.agent/key "k7" :seon.agent/boot "b" :seon.agent/started-ms 10 :seon.agent/ended-ms 20}])
        e (:db/id (d/entity db [:seon.agent/key "k7"]))
        a (#'seon.task/indexed-agent db e)]
    (is (= 10 (:seon.agent/started-ms a)) (pr-str a))
    (is (= 20 (:seon.agent/ended-ms a)) "the end the index's live rule reads")))

;; Estimate: one db of two tasks (~1M) and one answer-judged step (~10k); ~1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-done-is-answered-while-another-agent-runs
  ;; docs/plan.md, THE PLAN item 1 (":done waits on the judge's own end"; its done-proof): seon_done's wait on the judge
  ;; (seon.server's :done, c/judge!) is answered by the reconciler when its own task has no flight and is settled
  ;; (seon.reconcile/answer-judged), never when the whole root is quiet: another task's agent still working holds nothing.
  ;; Red under the mutation that brings back the old rule (answer only when no flight at all)
  (let [db (db-of [(decl "a") (decl "b")] :extra (concat (work "a" :merged) (work "b" :running)))
        p (promise)
        st (-> (st-of db :flights {"demo/b" {:act {:op :spawn :task "demo/b"} :agent :a-b :step :worked}})
               (assoc :judged {"demo/a" [p]}))
        st (#'r/answer-judged st)]
    (is (realized? p) "the judge's wait for demo/a is answered while demo/b's agent still runs")
    (is (= {:task "demo/a" :outcome :merged} (deref p 0 nil)) "with demo/a's own outcome")
    (is (contains? (:flights st) "demo/b") "and demo/b's flight goes on untouched")))

;; docs/tasks.md#the-done-rule, plan item 8: a session's seon_done is judged by what its change adds against main. Root's pilot
;; (2026-11-16) was parked on 28 reasons none of which its two-line change caused: a session imports a worktree that merged a newer
;; main, so main's changes reach the branch as its own and the done rule read them against the old merge base; and every :user task
;; was refused :open, so no session could merge. Red before: main's uncontracted var counted as the branch's, and :open and
;; :no-proofs refused the hand-back. Estimate: three small world dbs (~3M), one delta-changes (~1M), the measures (~0.5M): 6,000,000
(deftest ^{:seon.test/estimate {:ticks 6000000 :bytes 60000000}} a-hand-back-is-judged-by-what-its-change-adds
  (let [rec (fn [nm] (fn [db] (seon.program/record-tx db {:id (str "app/" nm) :src (str "(defn " nm " [x] x)") :ns 'app :ns-form? false
                                                           :defs [{:var (symbol "app" nm) :meta #{:arglists}}]
                                                           :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}})))
        base (my.test/world {:history? true})
        main (my.test/with base (rec "mains-own"))
        head (my.test/with base (rec "mains-own") (rec "the-branchs-own"))
        m (seon.writer/against-main base head main (seon.merge/delta-changes base head #{}) #{})]
    (is (= ['app/the-branchs-own] (:uncontracted m)) (str "only the branch's own uncontracted var: " (pr-str m)))
    (is (not-any? #{"app/mains-own"} (map str (:uncontracted m))) "main's own change, carried by the branch, is main's"))
  (let [reasons (fn [input] (set (map :reason (r/done-explain input))))
        base-input {:proofs #{} :results {} :main-green #{} :allowed-red #{} :uncontracted #{} :unmerged [] :deferred #{}}]
    (is (contains? (reasons (assoc base-input :kind :user)) :open) "a :user task's own parked end stays open")
    (is (= #{} (reasons (assoc base-input :kind :user :hand-back? true))) "its hand-back is judged by its change: no :open, no :no-proofs")
    (is (contains? (reasons (assoc base-input :kind :work :hand-back? true)) :no-proofs) "a work task's hand-back still needs its proofs")))

;; docs/users.md#messages: the reconciler's first listing wakes each parked agent owed a message, reading its branch against main
;; (boot-wakes, owed?). A parked agent whose branch was dropped (the writer's :drop-branch refuses only a live agent's) read as nil
;; there and threw, so no :main step ever completed and every commit relisted every task (seon.reconcile/index). A dropped branch
;; is owed nothing.
;; Estimate: 1 test-branch! 1M + a branch made and dropped 0.5M + owed? (one roster read) ~0.1M ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-dropped-branch-is-owed-nothing
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (pdb/on-main (:branch cfg) :dropped)]
       (d/branch! conn (:branch cfg) b)
       (d/delete-branch! conn b)
       (is (false? (#'r/owed? conn @conn "agent-of-a-dropped-branch" :dropped))
           "an agent whose branch is gone is owed nothing, and the reconciler's step goes on")))))
