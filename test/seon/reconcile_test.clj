(ns seon.reconcile-test
  "(actions-for db), index then actions, answers exactly the expected actions for a table of task graphs: needs
  DAGs, max-running, held and cancelled tasks, environment changes, a previous root's
  work, the end of an agent's work judged once by the done rule (`judgment`) and stored on its task, dead agents."
  (:require [seon.test-store :as ts] [seon.profile :as profile] [clojure.test :refer [deftest is are testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.task :as task]
            [seon.reconcile :as r] [my.root]
            [seon.template :as tpl] [seon.problems :as problems] [seon.merge :as pm] [seon.sock :as sock] [clojure.edn] [seon.contract :as contract]
            [clojure.string :as str] [clojure.set] [seon.config :as cfg] [seon.edn :as sedn]
            [seon.program :as program] [seon.writer :as writer] [clojure.java.shell :as sh] [seon.turn :as turn] [clojure.core.async :as a] [seon.cost :as cost]))

(defn- empty-db [] (ts/test-db))

(defn- actions-for "The actions that move db toward its desired state: index, then actions." [db] (r/actions (r/index db)))

(defn- decl [nm & {:keys [needs proofs model]}]
  (cond-> {:seon.task/ns 'demo :seon.task/name nm :seon.task/plan (str "Make " nm " true.")
           :seon.task/proofs (or proofs #{(symbol "demo-test" (str nm "-test"))})}
    needs (assoc :seon.task/needs (set (map #(str "demo/" %) needs)))
    model (assoc :seon.task/model model)))

(def diamond [(decl "a") (decl "b" :needs ["a"]) (decl "c" :needs ["a"]) (decl "d" :needs ["b" "c"])])

(defn- work
  "Task nm's work as root records it (ts/work-tx): its outcome and its agent a-<nm> of boot (a merge task's agent is on its
  work task's branch). Options: :boot (\"boot-1\"), :model, :members (more :seon.task/* facts its judging wrote), :taken (the
  messages its agent had taken: :seon.agent/taken)."
  [nm outcome & {:keys [boot model members taken] :or {boot "boot-1"}}]
  (let [id (str "demo/" nm)
        br (task/default-branch (str "demo/" (str/replace nm #"-merge$" "")))]
    (cond-> (ts/work-tx id outcome {:boot boot :agent (keyword (str "a-" nm)) :branch br :model model :members members
                                    :max-turns (cfg/value :seon.config.agent/max-turns)})
      taken (conj {:seon.agent/key (str boot "/a-" nm) :seon.agent/taken taken}))))

(defn- akey "The key of task nm's agent in boot-1." [nm] (str "boot-1/a-" nm))

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
  (tx-data) and the end written on it; the flight's other members are options. Answers {:db main after the judging :outcome :tx}."
  [db nm end & {:keys [results diff deferred run-problems worked-error turn ended-turn branch on-branch] :or {turn 1}}]
  (let [id (str "demo/" nm)
        branch (cond-> (or branch db) on-branch (d/db-with on-branch) end (ended nm end :turn turn :ended-turn ended-turn))
        {:keys [tx outcome]} (#'r/judge-tx (st-of db) {:act {:op :spawn :task id :env {:branch (task/default-branch id)}} :deferred deferred
                                                    :run-problems run-problems :worked-error worked-error}
                                        (or results {})
                                        (merge {:db branch :base db :base-t (:max-tx db) :ours [] :uncontracted [] :unmerged [] :caused [] :theirs 0} diff))]
    {:db (d/db-with db tx) :outcome outcome :tx tx}))

(defn- judgment-of
  "What `r/judgment` decides for task nm of db with facts (the measured ones it is not given are none, as seon.reconcile/judge's are)."
  [db nm facts] (r/judgment (r/index db) (str "demo/" nm) (merge {:results {} :uncontracted #{} :unmerged [] :caused #{} :deferred #{} :behinds 0} facts)))

(defn- merged [nm] (work nm :merged))
(defn- spawn [nm & {:keys [model]}]
  {:op :spawn :task (str "demo/" nm)
   :env {:branch (task/default-branch (str "demo/" nm)) :model model :max-turns (cfg/value :seon.config.agent/max-turns)}})
(defn- t [nm] (symbol "demo-test" (str nm "-test")))
(def conflict-f {:key [:var 'app/f] :kind :both-modified :base {:src "(defn f [x] x)"}
                 :ours {:src "(defn f [x] (inc x))"} :theirs {:src "(defn f [x] (dec x))"}})

(defn- mdb
  "A main db of decls with their merge tasks (template/with-merge), all released."
  [decls & {:keys [extra max-running] :or {max-running 4}}]
  (let [db (empty-db)
        ds (tpl/with-merge decls)
        db (d/db-with db (task/create-tx db ds))
        db (-> db (d/db-with (task/execution-tx (mapv #(task/task-id 'demo (:seon.task/name %)) ds) :running))
               (d/db-with (task/boot-tx "boot-1" max-running)))]
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
                (d/db-with (task/boot-tx "boot-1" max-running)))
      (seq extra) (d/db-with extra))))

(deftest task-state-is-what-happened
  ;; the one derivation of a task's observed state, from the index
  (let [db (db-of diamond :extra (into (merged "a") (concat (work "b" :done) (work "c" :running))))
        idx (r/index db)]
    (is (= {"demo/a" :merged "demo/b" :done "demo/c" :running "demo/d" :blocked}
           (into {} (for [id ["demo/a" "demo/b" "demo/c" "demo/d"]] [id (r/task-state idx id)]))))
    (is (= :held (r/task-state (r/index (db-of diamond :released [])) "demo/a")) "never released")))

(deftest needs-dag-starts-in-dependency-order
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

(deftest max-running-caps-parallel-agents
  (let [three [(decl "x") (decl "y") (decl "z")]]
    (are [db expected] (= expected (actions-for db))
      (db-of three :max-running 1) [(spawn "x")]
      (db-of three :max-running 2) [(spawn "x") (spawn "y")]
      ;; x running: one slot left of two
      (db-of three :max-running 2 :extra (work "x" :running)) [(spawn "y")]
      ;; two running: none left
      (db-of three :max-running 2 :extra (concat (work "x" :running) (work "y" :running))) [])))

(deftest nothing-autoruns-held-and-cancelled
  (let [three [(decl "x") (decl "y") (decl "z")]]
    (are [db expected] (= expected (actions-for db))
      ;; written tasks are held
      (db-of three :released []) []
      ;; only the released one starts
      (db-of three :released ["demo/y"]) [(spawn "y")]
      ;; cancelled after release
      (db-of three :released ["demo/y"] :extra (task/execution-tx ["demo/y"] :cancelled)) [])))

(deftest cancel-kills-and-blocks-dependents
  (let [db (db-of diamond :extra (concat (merged "a") (work "b" :running) (work "c" :running)
                                         (task/execution-tx ["demo/b"] :cancelled)))]
    (is (= [{:op :kill :task "demo/b" :reason :cancelled}] (actions-for db)))
    (is (= #{"demo/b" "demo/c"} (r/blocked-by (r/index db) "demo/d")))
    ;; killed: nothing restarts it, and d stays blocked once c merges
    (let [db (d/db-with db (concat (task/move-tx "demo/b" :running :killed {})
                                   (task/move-tx "demo/c" :running :merged {})))]
      (is (= [] (actions-for db)))
      (is (= #{"demo/b"} (r/blocked-by (r/index db) "demo/d"))))))

(deftest held-kills-without-restart
  (let [db (db-of [(decl "x")] :extra (concat (work "x" :running) (task/execution-tx ["demo/x"] :held)))]
    (is (= [{:op :kill :task "demo/x" :reason :held}] (actions-for db)))))

(deftest an-outside-agent-is-not-killed-for-the-tasks-own-model
  ;; task seon.root/an-outside-agent-is-a-first-class-worker (gap 5): attach starts an agent whose env
  ;; says model "outside" and leaves the task's model alone, so the task's next agent is its own
  (let [db (db-of [(decl "x" :model "m1")] :extra (work "x" :running :model "outside"))]
    (is (= [] (actions-for db)) "a live outside agent of a task of another model is not an env change"))
  (let [db (db-of [(decl "x" :model "m1")] :extra (concat (work "x" :running :model "outside")
                                                          (task/env-tx "demo/x" {:model "m2"})))]
    (is (= [] (actions-for db)) "nor when the task's model changes: the agent is the outside agent's"))
  (let [db (db-of [(decl "x" :model "m1")] :extra (work "x" :killed :model "outside"))]
    (is (= [(spawn "x" :model "m1")] (actions-for db)) "its next agent is the task's own model, not outside"))
  ;; a root restart lost an attached task, and its next agent took the task's own model (none
  ;; configured: "no model provider", the task held); a lost task was ended by nobody
  (let [db (db-of [(decl "x" :model "m1")] :extra (work "x" :lost :model "outside"))]
    (is (= [(spawn "x" :model "outside")] (actions-for db)) "a lost outside task's next agent is outside"))
  (let [db (db-of [(decl "x" :model "m1")] :extra (work "x" :running :model "outside" :boot "boot-0"))]
    (is (= [{:op :reap :task "demo/x"}] (actions-for db)) "a reap is the task's one action this pass")
    (is (= [(spawn "x" :model "outside")] (actions-for (d/db-with db (task/move-tx "demo/x" :running :lost {})))) "and the next agent of one the restart reaped stays outside"))
  (testing "root cannot take back a task that is done: merged work keeps :running"
    (let [db (db-of [(decl "z")] :extra (work "z" :merged))
          {:keys [taken not-running]} (my.root/take-back-tx db ["demo/z"] "x")]
      (is (= [] taken))
      (is (= ["task demo/z is done, not running: nothing to take back"] (map :seon.error/message not-running))))))

(deftest root-takes-a-running-task-back-and-releases-it-again
  ;; task seon.root/an-outside-agent-is-a-first-class-worker (gap 6): my.root/hold! pauses a running
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
      (is (= [(spawn "x")] (actions-for released)) "released again, its next work is a new agent's start on the same task"))))

(deftest a-cancel-never-makes-a-task
  ;; 2026-10-28: a bare name given as a task's id (ns/name) was upserted alone, an entity with no
  ;; namespace, name or plan, and every reader of tasks refused it at root's next boot
  (let [db (db-of [(decl "x")])
        {:keys [tx unknown]} (my.root/cancel-tx db ["demo/x" "x" "demo/none"])
        after (d/db-with db tx)]
    (is (= :cancelled (:seon.task/execution (task/task after "demo/x"))) "the task that exists is cancelled")
    (is (= #{"x" "demo/none"} (set (map :task unknown))) "each id that names no task is answered")
    (is (= [:seon.root/unknown-task :seon.root/unknown-task] (mapv :seon.error/type unknown)))
    (is (nil? (pdb/eid after :seon.task/id "x")) "and no task is made for it")
    (is (nil? (pdb/eid after :seon.task/id "demo/none")))))

(deftest environment-change-restarts-clean
  (let [db (db-of [(decl "x" :model "m1")] :extra (concat (work "x" :running :model "m1")
                                                          (task/env-tx "demo/x" {:model "m2"})))]
    (is (= [{:op :kill :task "demo/x" :reason :env-changed}] (actions-for db)))
    (let [db (d/db-with db (task/move-tx "demo/x" :running :killed {}))]
      (is (= [(spawn "x" :model "m2")] (actions-for db))))))

(deftest a-previous-roots-work-is-reaped-and-resumed
  (let [db (db-of [(decl "x") (decl "y" :needs ["x"])] :extra (work "x" :running :boot "boot-0"))]
    (is (= [{:op :reap :task "demo/x"}] (actions-for db)) "one action a task: its flight is one, and the spawn is the next pass's, once the reap has written :lost")
    (is (= #{} (:live (r/index db))))))

(deftest ended-work-is-judged-by-the-done-rule
  ;; the decision is made once, where an end's facts are in hand (`judgment`, pure), and stored on the task
  (let [db (db-of diamond :extra (work "a" :running))
        j (fn [facts] (judgment-of db "a" facts))
        red [{:reason :proof-red :symbol 'demo-test/a-test}]]
    (are [facts expected] (= expected (j facts))
      ;; green: a work task is done on its branch (b's red proof is b's own)
      {:end {:outcome :green} :results {(t "a") :pass (t "b") :fail}} {:outcome :done :reasons []}
      ;; its proof red: the agent is told (a verdict, which wakes it)
      {:end {:outcome :red} :results {(t "a") :fail}} {:outcome :told :reasons red :verdict red}
      ;; out of turns with its proof red: it stays, at its cap (the budget is the agent's), never held
      {:end {:outcome :cap} :results {(t "a") :fail}} {:outcome :cap :reasons red}
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

(deftest an-agent-with-no-end-records-no-fake-spend
  ;; task seon.model/budgets-are-data-and-spend-is-measured: the agent wrote no end (it died
  ;; or was killed before write-end!), so its turns, tokens and time are unknown: absent, never 0
  (let [db (db-of diamond :extra (work "a" :running))
        {:keys [db outcome]} (judge! db "a" nil)
        tk (task/task db "demo/a")]
    (is (= :closed outcome))
    (is (= "the agent wrote no end" (:seon.task/reason tk)) "said so")
    (is (not-any? #(contains? tk %) [:seon.task/waste :seon.task/costs :seon.task/failures]) "and nothing measured of it is recorded")))

(defn- fn-tx
  "A recorded definition of fn app.gaps/nm (as seon.program/record-tx writes it), with its
  contract c or none."
  [db nm c]
  (program/record-tx db {:id (str "app.gaps/" nm (when c "-contracted")) :src (str "(defn " nm (when c (str " {:malli/schema " c "}")) " [x] x)")
                         :ns 'app.gaps :ns-form? false :defs [(cond-> {:var (symbol "app.gaps" nm) :kind :fn} c (assoc :contract c))]
                         :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}}))

(deftest the-judge-and-the-merge-gate-agree-on-contract-gaps
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
           bc (d/connect (assoc cfg :branch ab))]
       (try
         (d/transact bc (fn-tx @bc "u" nil))
         (let [w (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer (a/chan 4) :events (a/chan 4)})
               ask (fn [req] (:r (first (:to-api (second (#'writer/writer-proc w :in (assoc req :dest {:rid 1})))))))
               diff (ask {:op :branch-diff :branch ab})
               gate (first (:results (ask {:op :octopus-merge :branches [ab]})))
               db (db-of [(decl "a")] :extra (work "a" :running))
               judged (judgment-of db "a" {:end {:outcome :green} :results {(t "a") :pass} :uncontracted (set (:uncontracted diff))})]
           (is (= #{'app.gaps/u} (set (pm/uncontracted (pm/delta-changes base @bc #{})))) "the agent's done check")
           (is (= '[app.gaps/u] (:uncontracted diff)) "the writer's diff")
           (is (= [{:reason :contract-gap :symbol 'app.gaps/u}] (:reasons judged)) "root's judge: g is main's gap, fixed on main")
           (is (= :seon.merge/behind (:seon.error/type gate)) "the gate: behind first, its gaps judged once main merges in"))
         (finally (d/release bc)))))))

(deftest an-agent-without-an-end-reads-nil
  ;; no end written (a silent death) is nothing to read, not an empty end; an end of an earlier wake is none of this one's
  (let [db (db-of diamond :extra (work "a" :running))]
    (is (nil? (r/pull-end db (akey "a"))))
    (is (= {:outcome :green :reason "done" :ended-turn 2} (r/pull-end (ended db "a" {:outcome :green :reason "done"} :turn 2) (akey "a")))
        "an end names its turn")))

(deftest a-wake-that-wrote-no-end-is-judged-so
  ;; each wake clears the end before it (seon.agent/clear-end!, seon.agent-test/a-wake-that-ran-no-turn-has-no-end): one that
  ;; died or threw silently has none on its branch, and is judged on what root measured, never on another wake's words
  (let [db (db-of diamond :extra (work "a" :running))
        none (judge! db "a" nil :results {(t "a") :pass})
        tk (task/task (:db none) "demo/a")]
    (is (= "the agent wrote no end" (:seon.task/reason tk)))
    (is (= :done (:outcome none)) "the proofs root measured are green: the done rule holds whatever the agent wrote")
    (testing "with no end, red results are held, not told as a green end would be"
      (is (= :closed (:outcome (judge! db "a" nil :results {(t "a") :fail}))))
      (is (= :told (:outcome (judge! db "a" {:outcome :green :reason "all green"} :results {(t "a") :fail})))))))

(deftest a-death-names-its-cause
  ;; task seon.agent/a-death-names-its-cause: a dead agent's task carries the end it wrote
  ;; on its branch before it exited (its cause), beside the death root observed (exit status);
  ;; a silent death (no end) is named by its exit; a turn loop that threw (the agent's REPL
  ;; answered the error, the process lives) is named by that error, not "wrote no end"
  (let [died (contract/error :seon.agent/died 'seon.root/api-proc "agent a1 died: exit 70" {:agent :a1 :exit {:exit 70}})
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

(deftest an-agent-dead-in-its-turn-loop-records-its-cause
  ;; root answers the turn loop's eval with the death itself (the one error map, no eval
  ;; item): the flight reads the branch's end next and keeps the death, exit and stderr,
  ;; rather than asking the dead agent for tests and recording "no live agent"
  (let [died (contract/error :seon.agent/died 'seon.root/api-proc "agent a1 died: exit 70" {:agent :a1 :exit {:exit 70}})
        st (st-of (db-of [(decl "a")]) :flights {"demo/a" {:act {:op :spawn :task "demo/a" :env {:branch :demo/a}} :agent :a1 :step :worked}})
        [st' outs] (#'r/advance st "demo/a" :worked died)]
    (is (= died (get-in st' [:flights "demo/a" :died])) "the death is the flight's cause")
    (is (= [{:op :write :req {:op :branch-db :branch :demo/a}}] (mapv :req outs)) "its end is read from the branch next")))

(deftest a-refused-end-is-a-verdict-that-wakes-and-the-same-one-again-holds
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
        (is (= [:verdict (task/default-branch "demo/a")] [(:seon.message/kind v) (:seon.message/branch v)]))
        (is (= {:reasons red :results fail} (select-keys (sedn/decode (:seon.message/body v)) [:reasons :results])))))
    (testing "told, its process gone: the next start is a spawn, never a wake (a told task is not parked)"
      (let [told (db-of [(decl "a")] :extra (concat (work "a" :told :taken 0 :members {:seon.task/reasons red})
                                                    (task/message-tx "v-demo/a-1" :verdict "root" (task/default-branch "demo/a") (sedn/encode {:reasons red}))))]
        (is (= [(spawn "a")] (actions-for told)))))
    (testing "the same reasons again: held for root, saying so, and no message"
      (let [{:keys [db outcome]} (judge! (told red) "a" {:outcome :red} :results fail)
            tk (task/task db "demo/a")]
        (is (= :closed outcome))
        (is (= :held (:seon.task/execution tk)))
        (is (str/starts-with? (get-in tk [:seon.task/failure :reason]) "the same verdict again: "))
        (is (nil? (verdict-of db)) "and no message")))
    (testing "behind alone is main moving under the branch, not the agent's news: the same count again is a verdict
              again (swarm's s9: behind s0's 3 keys, then s1's), held only once :seon.config.merge/max-behind of
              the task's judgings were behind"
      (let [behind [{:reason :behind :behind 3}]
            judged-behind (fn [k] ;; the task was judged behind k times (its outcome's history), told it last, and works again
                            (let [db (reduce (fn [db _] (-> db (d/db-with [{:seon.task/id "demo/a" :seon.task/outcome :behind}])
                                                            (d/db-with [{:seon.task/id "demo/a" :seon.task/outcome :running}])))
                                             (told behind) (range k))]
                              (judge! db "a" {:outcome :green} :results {(t "a") :pass} :diff {:theirs 3})))
            max-b (cfg/value :seon.config.merge/max-behind)]
        (let [{:keys [db outcome]} (judged-behind 2)]
          (is (= :behind outcome) "under the bound: told again, as behind")
          (is (= :verdict (:seon.message/kind (verdict-of db))))
          (is (= :running (:seon.task/execution (task/task db "demo/a"))) "and not held"))
        (let [{:keys [db outcome]} (judged-behind max-b)]
          (is (= :closed outcome) "at the bound: held for root")
          (is (str/starts-with? (get-in (task/task db "demo/a") [:seon.task/failure :reason]) "the same verdict again: behind")))))
    (testing "different reasons: a verdict again, which wakes"
      (let [{:keys [db outcome]} (judge! (told [{:reason :no-proofs}]) "a" {:outcome :red} :results fail)]
        (is (= :told outcome))
        (is (= :verdict (:seon.message/kind (verdict-of db))))))
    (testing "a merge refused by the done rule holds"
      (let [mdb' (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :running)))
            j (judgment-of mdb' "a-merge" {:end {:outcome :red} :results fail})]
        (is (= :closed (:outcome j)))
        (is (some? (:hold j)))))))

(deftest a-judging-retracts-what-it-does-not-set
  ;; the judged attributes are the end's, never an earlier end's: an error and deferred results a task held from its last judging
  ;; do not survive a later one that has none (seon.task/outcome-tx)
  (let [stale {:seon.task/error {:seon.error/type :seon.fx/io-error :seon.error/message "the last gate failed"}
               :seon.task/deferred #{'seon.root-test/boots} :seon.task/deferred-results {'seon.root-test/boots :error}
               :seon.task/waste {'seon.doc/resolve-ref {:forms 2 :turns 1}}}
        db (db-of [(decl "a")] :extra (work "a" :running :members stale))
        {after :db} (judge! db "a" {:outcome :green} :results {(t "a") :pass})
        tk (task/task after "demo/a")]
    (is (= :done (:seon.task/outcome tk)))
    (is (= stale (select-keys (task/task db "demo/a") (keys stale))) "the earlier end's facts are on the task before")
    (is (not-any? #(contains? tk %) (keys stale)) "and gone after a judging that set none of them")))

(deftest a-wake-whose-parked-process-is-gone-is-lost-and-the-next-start-a-spawn
  ;; flow-lab's hazard 6 (design agent-loop section 4): the parked process died before the wake's
  ;; work! reached it, so root answers that agent ended; the task took nothing, so it is :lost, not a
  ;; death, and the session's next start is a spawn, a load of the session, never a wake of a
  ;; process that is gone
  (let [ended (contract/error :seon.root/ended 'seon.root/api-proc "agent a-a1 ended" {:agent :a-a1})
        flight {:act {:op :wake :task "demo/a" :env {:branch :demo/a}} :agent :a-a1 :step :worked}
        db (db-of [(decl "a")] :extra (work "a" :running))
        st (st-of db :flights {"demo/a" flight})
        [_ outs] (#'r/advance st "demo/a" :worked ended)
        tx (get-in (first outs) [:req :req :tx-data])
        lost (task/task (d/db-with db tx) "demo/a")]
    (is (some #(= [:db/cas [:seon.task/id "demo/a"] :seon.task/outcome :running :lost] %) tx) "the wake's task is lost")
    (is (str/includes? (:seon.task/reason lost) "agent a-a1 ended") "with why")
    (is (= [(spawn "a")]
           (actions-for (db-of [(decl "a")] :extra (concat (work "a" :lost :taken 0)
                                                           (task/message-tx "m1" :message "user" (task/default-branch "demo/a") "carry on")))))
        "the next start is a spawn")))

(deftest the-judge-reads-its-branch-in-one-request
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

(deftest a-task-is-judged-with-how-far-behind-main-its-branch-was
  ;; the wiring of gap 7: the writer's :theirs reaches the judging as the done rule's :behind, and the reasons say so
  (let [db (db-of [(decl "a")] :extra (work "a" :running))
        flight {:act {:op :spawn :task "demo/a" :env {:branch :demo/a}} :agent :a-a :step :diffed :results {}}
        diff (fn [theirs] {:ok true :db db :base db :base-t (:max-tx db) :ours [] :theirs theirs :conflicts 0 :unmerged [] :uncontracted [] :caused [] :vars #{}})
        reasons-of (fn [theirs] (let [[_ outs] (#'r/advance (st-of db :flights {"demo/a" flight}) "demo/a" :diffed (diff theirs))]
                                  (:seon.task/reasons (task/task (d/db-with db (get-in (second outs) [:req :req :tx-data])) "demo/a"))))]
    (is (= {:reason :behind :behind 4} (first (reasons-of 4))) "behind by 4 keys, leading the reasons")
    (is (not-any? #(= :behind (:reason %)) (reasons-of 0)) "not behind, nothing recorded")
    (is (= {:reason :behind :behind 4} (first (:verdict (judgment-of db "a" {:end {:outcome :green} :results {(t "a") :pass} :behind 4}))))
        "the verdict told to the session leads with how far behind it was")))

(deftest a-refused-diff-ends-the-task-with-its-cause
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

(deftest a-judged-task-that-is-no-longer-running-is-closed
  ;; a merge ready for the octopus merge whose task was cancelled meanwhile is closed, never merged
  (let [db (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :ready) (task/execution-tx ["demo/a-merge"] :cancelled)))]
    (is (= [{:op :close :task "demo/a-merge"}] (actions-for db)))
    (is (= {:outcome :closed :reasons []}
           (judgment-of (db-of diamond :extra (concat (work "a" :running) (task/execution-tx ["demo/a"] :cancelled))) "a"
                        {:end {:outcome :green} :results {(t "a") :pass}}))
        "and a cancelled task whose agent ended is judged closed")))

(deftest done-explain-table
  (are [in expected] (= expected (mapv (juxt :reason :symbol) (r/done-explain in)))
    {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{}} []
    {:proofs #{} :results {} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:no-proofs nil]]
    {:proofs #{'p/a} :results {'p/a :error} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:proof-red 'p/a]]
    ;; a proof that never ran is unknown, not red
    {:proofs #{'p/a} :results {} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:proof-unknown 'p/a]]
    {:proofs #{'p/a} :results {'p/a :pass 'p/m :fail} :main-green #{'p/m} :allowed-red #{} :uncontracted #{}} [[:regression 'p/m]]
    {:proofs #{'p/a} :results {'p/a :pass 'p/o :fail} :main-green #{} :allowed-red #{'p/o} :uncontracted #{}} []
    {:proofs #{'p/a} :results {'p/a :pass 'p/s :fail} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:stray-red 'p/s]]
    ;; red on main too, at the branch's merge base: main's red, not the work's
    {:proofs #{'p/a} :results {'p/a :pass 'p/s :fail} :main-green #{} :allowed-red #{} :main-red #{'p/s} :uncontracted #{}} []
    {:proofs #{'p/a} :results {'p/a :pass 'p/m :fail} :main-green #{'p/m} :allowed-red #{} :main-red #{'p/m} :uncontracted #{}} []
    {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{'app/f}} [[:contract-gap 'app/f]]
    {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{} :unmerged ["[:var app/f]"]} [[:unmerged nil]]))

(deftest the-writer-refuses-cycles-and-unknown-needs
  (let [db (empty-db)]
    (is (= :seon.task/refused (:seon.error/type (task/create-tx db [(decl "a" :needs ["b"]) (decl "b" :needs ["a"])]))))
    (is (= :seon.task/refused (:seon.error/type (task/create-tx db [(decl "a" :needs ["nope"])]))))
    (is (vector? (task/create-tx db diamond)))))

(deftest regression-is-judged-against-main-at-the-judgings-basis
  ;; x merged at main t 100; y (independent of x) was observed against main at t 50,
  ;; before x's merge, so x's red proof there is not a regression of the main y holds
  (let [two [(decl "x") (decl "y")]
        db (db-of two :extra (concat (work "x" :merged :members {:seon.task/merged-t 100}) (work "y" :running)))
        y (fn [basis] (judgment-of db "y" {:end {:outcome :green} :results {(t "x") :fail (t "y") :pass} :basis basis}))]
    (is (= {:outcome :done :reasons []} (y 50)))
    (is (= :told (:outcome (y 150))) "refused: told the agent")))

(deftest contracts-are-checked
  ;; with the contracts on (:seon.config.contract/instrument, config: in every digest), a call
  ;; outside its contract is refused at entry with the one error map. (It read an inherited
  ;; PROTO_INSTRUMENT, an input no digest held; pure-tests 2026-10-23.)
  (when contract/on?
    (is (= :seon.instrument/contract-error
           (:seon.error/type (try (task/task-id "not-a-symbol" "x") (catch Exception e (ex-data e))))))))

;; ---- true branches: merge tasks, unmerged entries, behind, a refused run held for root ----

(deftest plan-and-proofs-accumulate-along-needs
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

(deftest an-index-observed-from-the-last-is-the-index-listed
  (observed-steps (db-of diamond)
                  [["a plan improved" [{:seon.task/id "demo/b" :seon.task/plan "Make b truer."}]]
                   ["a task started" (work "a" :running)]
                   ["a task judged done" [{:seon.task/id "demo/a" :seon.task/outcome :done}]]
                   ["a task merged" [{:seon.task/id "demo/a" :seon.task/outcome :merged :seon.task/merged-t 7}]]
                   ["a need added" [{:seon.task/id "demo/c" :seon.task/needs #{"demo/a" "demo/b"}}]]
                   ["a proof added" [{:seon.task/id "demo/a" :seon.task/proofs #{(t "a") 'demo-test/more}}]]
                   ["a new boot" (task/boot-tx "boot-2" 4)]]))

(deftest an-agents-boot-or-taken-moving-re-derives-every-task-that-names-it
  ;; on a stage two tasks hold one agent (:seon.task/agent on both): the agent's boot or taken count moving moves what the index
  ;; derives of BOTH, and the index observed from the last is the one listed
  (let [br (task/default-branch "demo/d")
        stage (fn [outcome] (-> (db-of [(decl "d") (decl "i")])
                                (d/db-with (ts/work-tx "demo/d" outcome {:agent :a1 :branch br}))
                                (d/db-with (ts/work-tx "demo/i" outcome {:agent :a1 :branch br}))))
        k "boot-1/a1"]
    (is (= k (:seon.task/agent (task/task (stage :running) "demo/d")) (:seon.task/agent (task/task (stage :running) "demo/i"))) "one agent holds both")
    (testing "the boot: both are live in this one's, both are reaped once it is an earlier boot's"
      (let [[_ _ [after]] (observed-steps (stage :running) [["the agent's boot moved" [{:seon.agent/key k :seon.agent/boot "boot-0"}]]])]
        (is (= #{"demo/d" "demo/i"} (:live (r/index (stage :running)))))
        (is (= [#{} #{"demo/d" "demo/i"}] [(:live after) (:reap after)]))))
    (testing "the taken count: both are ready while main holds a message the agent has not taken, neither once it has"
      (let [db (d/db-with (stage :parked) (task/message-tx "m1" :message "user" br "carry on"))
            ready #(set (map second (:ready %)))
            [_ _ [taken]] (observed-steps db [["the agent took it" [{:seon.agent/key k :seon.agent/taken 1}]]])]
        (is (= #{"demo/d" "demo/i"} (ready (r/index db))))
        (is (= #{} (ready taken)))))))

(deftest actions-read-the-judgment-stored-on-the-task
  ;; the decision is made once, at the agent's end (`judgment`) and written; `actions` reads the outcome stored, never the done rule
  (let [octopus {:op :octopus-merge :task "demo/a-merge" :members [{:task "demo/a-merge"}]}
        gate {:seon.root/id :root :seon.config.source/gate-checkout "/the/merges/tree"}
        boots 'seon.root-test/boots]
    (testing ":ready is the octopus merge"
      (is (= [octopus] (actions-for (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :ready)))))))
    (testing ":gate is the gate, with the tests its judging deferred"
      (is (= [{:op :gate :task "demo/a-merge" :tests [boots]}]
             (actions-for (mdb [(decl "a")] :extra (concat (work "a" :done) [gate] (work "a-merge" :gate :members {:seon.task/deferred #{boots}})))))))
    (testing "a judged task no longer running is closed, never merged"
      (is (= [{:op :close :task "demo/a-merge"}]
             (actions-for (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :ready) (task/execution-tx ["demo/a-merge"] :cancelled)))))))
    (testing ":running but not live (an earlier boot's): reaped, then started again"
      (is (= [{:op :reap :task "demo/a"}] (actions-for (db-of [(decl "a")] :extra (work "a" :running :boot "boot-0"))))))
    (testing ":closed derives nothing: the hold was written with the outcome, and the task waits for a person to release it"
      (is (= [] (actions-for (db-of [(decl "a")] :extra (concat (work "a" :closed :members {:seon.task/reasons [{:reason :proof-red :symbol (t "a")}]})
                                                                (task/execution-tx ["demo/a"] :held)))))))))

(deftest a-parked-task-is-woken-only-by-messages-it-has-not-taken
  ;; idx-ready?: parked, ready again only when main holds more messages to its agent's branch than :seon.agent/taken
  ;; (docs/turn.md#parked); a task at its cap is never woken by a message, only by a raised budget and a release
  (let [br (task/default-branch "demo/a")
        msg (fn [& ids] (mapcat #(task/message-tx % :message "user" br "carry on") ids))
        wake {:op :wake :task "demo/a" :agent :a-a
              :env {:branch br :model nil :max-turns (cfg/value :seon.config.agent/max-turns)}}
        acts (fn [outcome taken & ids] (actions-for (db-of [(decl "a")] :extra (concat (work "a" outcome :taken taken) (apply msg ids)))))]
    (is (= [] (acts :parked 0)) "no message")
    (is (= [] (acts :parked 1 "m1")) "one message, already taken")
    (is (= [wake] (acts :parked 0 "m1")) "one it has not taken")
    (is (= [wake] (acts :parked 1 "m1" "m2")) "a second")
    (is (= [] (acts :cap 0 "m1" "m2")) "at its cap no message wakes it")
    (is (= [] (actions-for (db-of [(decl "a")] :extra (concat (work "a" :parked :taken 0) (task/message-tx "m9" :message "user" :task-other "x")))))
        "a message to another branch is none of its agent's")))

(def ^:private observed-sizes [10 30 100])

(def ^:private disk-store
  "This process's one store on disk for the cost proofs below, seeded once with the test store's 2,000 datoms
  (seon.db/seed-tx), under target/tmp: each proof size is a branch of it (one store, many branches, as in
  production and as the test store is; a seeded store of its own per size cost 366 MB of allocation each, a
  gigabyte under the quadratic schema transaction), and its nodes are at addresses, which is why these proofs
  are not on the in-memory test store. Removed when the process ends."
  (delay (let [path (str (seon.config/checkout) "/target/tmp/observed-" (random-uuid))]
           (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (sh/sh "rm" "-rf" path))))
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
    (d/transact conn (task/boot-tx "boot-1" 4))
    (let [db0 @conn]
      (d/transact conn [{:seon.task/id "demo/u0" :seon.task/plan "Make u0 truer."}])
      [(r/index db0) db0 @conn])))

(deftest an-index-is-observed-at-the-cost-of-what-changed
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
                 ;; (every diff walked its attribute); the 84 descents are task seon.reconcile/one-observe-descends-once
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
    (d/transact conn (task/boot-tx "boot-1" 4))
    [@conn]))

(deftest an-index-is-listed-at-the-cost-of-its-tasks
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
                 :fixed {:ticks 200000.0 :bytes 600000.0 :calls 20.0}
                 :per-n {:ticks 36000.0 :bytes 180000.0 :calls 11.0}
                 :sizes listed-sizes})))

(deftest with-merge-chains-work-through-merges
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

(deftest green-merge-task-merges
  ;; an octopus merge of one: no other merge runs
  (let [db (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :running)))]
    (is (= {:outcome :ready :reasons []} (judgment-of db "a-merge" {:end {:outcome :green} :results {(t "a") :pass}})) "a green end is a merge ready")
    (is (= [{:op :octopus-merge :task "demo/a-merge" :members [{:task "demo/a-merge"}]}]
           (actions-for (d/db-with db (task/move-tx "demo/a-merge" :running :ready {})))))))

(deftest a-whole-task-works-in-parallel-and-its-green-merges
  ;; owner, 2026-10-26 (seon.template/request): a whole task's agents start as work does, not one at a
  ;; time as merges do; its green end merges into main as a merge task's does; out of turns, it stays at its cap
  (let [whole (fn [nm] (assoc (decl nm) :seon.task/kind :whole))
        db (db-of [(whole "a")] :extra (work "a" :running))
        j (fn [facts] (judgment-of db "a" facts))]
    (is (= [(spawn "a") (spawn "b")] (actions-for (db-of [(whole "a") (whole "b")]))) "two at once")
    (is (= {:outcome :ready :reasons []} (j {:end {:outcome :green} :results {(t "a") :pass}})))
    (is (= [{:op :octopus-merge :task "demo/a" :members [{:task "demo/a"}]}]
           (actions-for (db-of [(whole "a")] :extra (work "a" :ready)))))
    (is (= :cap (:outcome (j {:end {:outcome :cap} :results {(t "a") :fail}}))))
    ;; owner, 2026-10-26 ("test for it"): a whole task that ended red, or green with main's merge
    ;; unresolved on its branch, is not merged: its verdict goes back to its own session, the
    ;; done rule's reasons as data (a merge task's holds for root)
    (is (= {:outcome :told :reasons [{:reason :proof-red :symbol (t "a")}] :verdict [{:reason :proof-red :symbol (t "a")}]}
           (j {:end {:outcome :red} :results {(t "a") :fail}})))
    (is (= [:told [:unmerged]]
           ((juxt :outcome #(mapv :reason (:verdict %))) (j {:end {:outcome :green} :results {(t "a") :pass} :unmerged ["[:var app/f]"]}))))))

(deftest a-requests-green-work-is-merged-by-its-merge-task
  ;; owner, 2026-10-26: "design-build and merge" (seon.template/design-build, an experiment's choice): the green design-build task is done, not merged
  ;; by itself; its merge task then runs on the branch, and its green end is the octopus merge
  (let [db-req (fn [extra] (let [db (empty-db) ds (tpl/design-build (decl "a"))]
                             (-> db (d/db-with (tpl/request-tx db ds)) (d/db-with (task/boot-tx "boot-1" 4)) (d/db-with extra))))]
    (is (= [(spawn "a")] (actions-for (db-req []))) "the work task forks first, alone")
    (is (= {:outcome :done :reasons []} (judgment-of (db-req (work "a" :running)) "a" {:end {:outcome :green} :results {(t "a") :pass}}))
        "its green end is accepted, and merges nothing")
    (is (= [(mspawn "a-merge")] (actions-for (db-req (work "a" :done)))) "once it is done, its merge task runs")
    (is (= [{:op :octopus-merge :task "demo/a-merge" :members [{:task "demo/a-merge"}]}]
           (actions-for (db-req (concat (work "a" :done) (work "a-merge" :ready)))))
        "and the merge task's green end is the merge")))

(deftest a-merge-with-deferred-tests-is-gated-with-nobody-asking
  ;; the owner's ruling (2026-10-27: process tests the change reaches run automatically at the
  ;; judge; it replaces 2026-10-23's "never automatically" for merges): the process tests a
  ;; branch's change reaches run before its merge, on a worktree of the root's declared gate
  ;; checkout, with no one asking for it, and gate it like proofs: unrun, the
  ;; member is gated first (alone); all passing, it merges; a red, or a run that could not run
  ;; them (:error), is refused as a red proof is, never hidden. With no checkout to run them on
  ;; (a root with no source), it is held, saying so: never merged with them unrun
  (let [gate {:seon.root/id :root :seon.config.source/gate-checkout "/the/merges/tree"}
        boots 'seon.root-test/boots
        other 'seon.root-test/other
        self (assoc gate :seon.root/started-by "seon.root-test/boots")
        ;; the merge task working, the work before it done; judged on a green end with the tests its change reaches deferred
        judged (fn [root deferred] (judgment-of (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :running) (when root [root])))
                                                "a-merge" {:end {:outcome :green} :results {(t "a") :pass} :deferred deferred}))
        ;; ... and gated: its outcome :gate, the gate's answer in
        gated (fn [root rs unread err]
                (let [db (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :gate :members {:seon.task/deferred #{boots}}) (when root [root])))]
                  (d/db-with db (#'r/gated (st-of db) "demo/a-merge" rs unread err))))
        octopus [{:op :octopus-merge :task "demo/a-merge" :members [{:task "demo/a-merge"}]}]]
    (is (= {:outcome :gate :reasons [] :deferred [boots]} (judged gate [boots])) "unrun: gated first")
    (is (= {:outcome :ready :reasons []} (judged gate [])) "none deferred: ready")
    (testing "all passing, it is ready and merges"
      (let [db (gated gate {boots :pass} [] nil)]
        (is (= :ready (:seon.task/outcome (task/task db "demo/a-merge"))))
        (is (= {boots :pass} (:seon.task/deferred-results (task/task db "demo/a-merge"))))
        (is (= octopus (actions-for db)))))
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
    (testing "the test whose process started this root is left out: a test cannot be its own gate (:seon.root/started-by)"
      (is (= {:outcome :ready :reasons []} (judged self [boots])) "the only deferred test is the root's own: merged, nothing run")
      (is (= {:outcome :gate :reasons [] :deferred [other]} (judged self [boots other])) "another test beside it is still gated, itself not named")
      (let [db (mdb [(decl "a")] :extra (concat (work "a" :done) [self] (work "a-merge" :gate :members {:seon.task/deferred #{boots other}})))]
        (is (= [{:op :gate :task "demo/a-merge" :tests [other]}] (actions-for db)))))
    (testing "no checkout to run them on (a root with no source): held, never merged with them unrun"
      (let [j (judged nil [boots])]
        (is (= :closed (:outcome j)))
        (is (str/includes? (:hold j) "no checkout to run them on"))))))

(deftest a-deferred-proof-is-neither-red-nor-unknown
  ;; owner's ruling (2026-10-23): a process test never runs in an agent's turn or root's judge,
  ;; a proof among them; the done rule reads it as deferred to root's gate, and still refuses a
  ;; proof that is neither green nor deferred
  (let [in {:proofs #{'app/p 'app/q} :results {'app/p :pass} :main-green #{} :allowed-red #{} :uncontracted #{}}]
    (is (= [{:reason :proof-unknown :symbol 'app/q}] (r/done-explain in)))
    (is (= [] (r/done-explain (assoc in :deferred #{'app/q}))))
    (is (= [{:reason :proof-red :symbol 'app/p}] (r/done-explain (assoc in :results {'app/p :fail} :deferred #{'app/q}))))))

(deftest merges-run-one-at-a-time
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

(deftest boot-records-merge-max-running-one
  ;; DECISIONS: octopus merges stay off (one merge at a time) until the octopus merge's combined test run
  ;; and the par-200 hang are fixed; the config value in force is a fact on main a person can read
  (let [n (cfg/value :seon.config.agent/max-running)
        db (d/db-with (empty-db) (into (task/boot-tx "boot-1" n) (cfg/facts cfg/config {})))]
    (is (= 1 (cfg/value :seon.config.merge/max-running)))
    (is (= {:boot "boot-1" :max-running n :max-merging 1} (task/root db)) "both config keys are recorded at boot")))

(deftest two-ready-disjoint-merges-run-one-at-a-time-by-default
  ;; x and y change disjoint definitions: an octopus merge could take both, but the config says 1
  (let [two [(decl "x") (decl "y")]
        extra (concat (work "x" :done :members {:seon.task/changed #{"[:var app.m/x]"}}) (work "y" :done :members {:seon.task/changed #{"[:var app.m/y]"}}))]
    (is (= ["demo/x-merge"] (map :task (filter #(= :spawn (:op %)) (actions-for (mdb two :extra extra))))))
    (testing "an octopus merge is turned on by writing the fact"
      (is (= ["demo/x-merge" "demo/y-merge"]
             (map :task (filter #(= :spawn (:op %)) (actions-for (mdb two :extra (concat extra [{:seon.root/id :root :seon.config.merge/max-running 8}]))))))))))

(deftest unmerged-refuses-done
  (let [db (db-of diamond :extra (work "a" :running))
        reasons [{:reason :unmerged :key "[:var app/f]"}]]
    (is (= {:outcome :told :reasons reasons :verdict reasons}
           (judgment-of db "a" {:end {:outcome :green} :results {(t "a") :pass} :unmerged ["[:var app/f]"]})))))

(deftest an-open-problem-its-work-caused-refuses-done
  ;; docs/health.md: recorded at its end (seon.problems/caused), refused by
  ;; the done rule, so the agent is told naming it (a :verdict); its evaluations were never refused
  (let [msg "docs/x.md#y cites app/f, which names nothing"
        reasons [{:reason :open-problem :key msg}]]
    (is (= {:outcome :told :reasons reasons :verdict reasons}
           (judgment-of (db-of diamond :extra (work "a" :running)) "a" {:end {:outcome :green} :results {(t "a") :pass} :caused #{msg}})))
    (is (= reasons
           (r/done-explain {:proofs #{(t "a")} :results {(t "a") :pass} :main-green #{} :allowed-red #{} :uncontracted #{} :caused #{msg}})))))

(deftest behind-merge-gets-a-new-agent
  (testing "root's merge found main moved: the merge task starts again, the same session; no catch-up"
    (let [db (mdb [(decl "a")] :extra (concat (work "a" :done) (work "a-merge" :behind)))]
      (is (= [(mspawn "a-merge")] (actions-for db))))))

(deftest a-merge-behind-again-and-again-is-held-for-root
  ;; task seon.root/a-behind-merge-is-bounded-and-merges-main-first (gap 4): a :behind answer is green, so max-merge-turns
  ;; never counted it and a merge refused behind ran again for ever; past :seon.config.merge/max-behind
  ;; behind judgings of one merge task (its outcome's history) the next behind answer holds the task for root with the evidence
  (let [cap (cfg/value :seon.config.merge/max-behind)
        answer (fn [prior]
                 (let [db (reduce (fn [db _] (-> db (d/db-with [{:seon.task/id "demo/add-merge" :seon.task/outcome :behind}])
                                                 (d/db-with [{:seon.task/id "demo/add-merge" :seon.task/outcome :running}])))
                                  (mdb [(decl "add")] :extra (concat (work "add" :done) (work "add-merge" :running)))
                                  (range prior))
                       db (d/db-with db [{:seon.task/id "demo/add-merge" :seon.task/outcome :ready}])
                       act (first (filter #(= :octopus-merge (:op %)) (actions-for db)))
                       [st _] (#'r/start (st-of db) act)
                       behind (contract/error :seon.merge/behind 'seon.writer/merge-refused "main changed 2 key(s) since the branch's merge base: merge main into the branch first" {:tx 9 :behind 2})
                       [_ outs] (#'r/advance st "demo/add-merge" :octopus-merged {:ok true :results [behind]})
                       tx (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= :write (get-in % [:req :op])) outs)))]
                   (task/task (d/db-with db tx) "demo/add-merge")))]
    (let [tk (answer (dec cap))]
      (is (= :running (:seon.task/execution tk)) "under the bound, behind runs again")
      (is (= :behind (:seon.task/outcome tk))))
    (let [tk (answer cap)]
      (is (= :held (:seon.task/execution tk)) "past it, the task is held for root")
      (is (re-find #"behind" (:reason (:seon.task/failure tk))))
      (is (= :closed (:seon.task/outcome tk)))
      (is (= :seon.merge/behind (:seon.error/type (:seon.task/error tk))) "with the writer's evidence"))))

(deftest a-branch-behind-main-is-told-so-first
  ;; task seon.root/a-behind-merge-is-bounded-and-merges-main-first (gap 7): a branch behind main was judged
  ;; on a program main had moved from and read "proof-unknown"; the done rule leads with the fix, and the judging
  ;; records how far behind it was (a-task-is-judged-with-how-far-behind-main-its-branch-was)
  (let [in {:proofs #{'p/a} :results {} :main-green #{} :allowed-red #{} :uncontracted #{} :behind 3}]
    (is (= [{:reason :behind :behind 3} {:reason :proof-unknown :symbol 'p/a}] (r/done-explain in)) "behind leads")
    (is (= "behind 3; proof-unknown p/a" (r/explain-text (r/done-explain in))))
    (is (= [{:reason :proof-unknown :symbol 'p/a}] (r/done-explain (dissoc in :behind))) "a branch not behind is judged as before")))

(deftest a-green-work-task-judged-behind-is-done-and-its-merge-runs
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches: a green end of a work task judged behind main's newer
  ;; merges is done, and its merge task (which merges main into the branch and is gated) runs next; told again it
  ;; was held for root once :seon.config.merge/max-behind of its judgings were behind a busy main (bin/check swarm held
  ;; sw/s8 and sw/s9 so, 2026-10-31). A task no merge task follows still merges main itself and is told so
  (let [merge-decl {:seon.task/ns 'demo :seon.task/name "a-merge" :seon.task/kind :merge :seon.task/plan "Merge a." :seon.task/needs #{"demo/a"}}
        judged (fn [decls] (judgment-of (db-of decls :extra (work "a" :running)) "a" {:end {:outcome :green} :results {(t "a") :pass} :behind 4}))]
    (is (= {:outcome :done :reasons []} (judged [(decl "a") merge-decl])) "followed by its merge task: no verdict, the task is done")
    (is (= {:reason :behind :behind 4} (first (:verdict (judged [(decl "a")])))) "no merge task: told it is behind")
    (is (= {:reason :behind :behind 4} (first (:verdict (judged [(decl "a") (decl "b" :needs ["a"])]))))
        "a follower that is no merge task brings no main in: told it is behind")))

(deftest design-may-leave-its-proofs-red
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
    (-> db (d/db-with (task/execution-tx ["demo/a"] :running)) (d/db-with (task/boot-tx "boot-1" 4)) (d/db-with extra))))

(deftest a-design-step-is-not-done-at-its-start
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

(deftest a-design-step-is-done-when-it-improved-the-plan
  ;; root's judge carries the plan the agent improved on its branch to main (judge)
  (let [better "Make a true. Design: (defn f [xs] ...) one pass over xs."
        {:keys [db outcome]} (judge! (design-db (work "a" :running)) "a" {:outcome :green} :results {(t "a") :fail}
                                     :on-branch [{:seon.task/id "demo/a" :seon.task/plan better}])]
    (is (= :done outcome))
    (is (= better (:seon.task/plan (task/task db "demo/a"))) "and the plan is on main")))

(deftest a-refused-end-holds-its-task-and-creates-none
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
        died (contract/error :seon.agent/died 'seon.root/api-proc "agent a-add died: exit 70" {:agent :a-add})
        dead-db (mdb two-tasks :extra (work "add" :running))]
    (doseq [[why id db' problem outcome]
            [["a red proof" "demo/add" (:db (judge! (told red) "add" {:outcome :red} :results {(t "add") :fail}))
              "the same verdict again: proof-red demo-test/add-test" :closed]
             ["its proof never run" "demo/add" (:db (judge! (told unknown) "add" {:outcome :red} :results {}))
              "the same verdict again: proof-unknown demo-test/add-test" :closed]
             ["a dead agent" "demo/add" (d/db-with dead-db (#'r/dead-tx (st-of dead-db) "demo/add" died nil))
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
          (is (= (set (keys (task/tasks before))) (set (keys (task/tasks db')))) "no new task")
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
    (testing "a merge the octopus merge refused: the reconciler proc's answer to the writer"
      (let [db (mdb [(decl "add")] :extra (concat (work "add" :done) (work "add-merge" :ready)))
            [st outs] (r/reconciler-proc (r/reconciler-proc {:boot "boot-1" :conn conn}) :main (ts/commit-of conn db))
            asked (first (filter #(= :octopus-merge (get-in % [:req :req :op])) (:to-api outs)))
            reason (contract/error :seon.writer/refused 'seon.writer/octopus-merge "conflicts with main at app/f" {:merged? false})
            [_ outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r {:ok true :results [reason]}})
            tx (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= :write (get-in % [:req :op])) (:to-api outs2))))
            db' (d/db-with db tx)
            tk (task/task db' "demo/add-merge")]
        (is (some? asked) "the ready merge is asked of the writer")
        (is (= (set (keys (task/tasks db))) (set (keys (task/tasks db')))) "no new task")
        (is (= :held (:seon.task/execution tk)))
        (is (= {:reason "merge refused: conflicts with main at app/f" :agent "boot-1/a-add-merge"} (:seon.task/failure tk)))
        (is (= :closed (:seon.task/outcome tk)))
        (is (= reason (:seon.task/error tk)) "the writer's reason, on the task")))))
))

(deftest a-merge-of-source-with-no-file-records-what-it-wrote-no-file-for
  (ts/test-branch! (fn [_ conn]
  ;; a merged member whose changed forms name no source file: no write-back runs, so its task
  ;; holds a commit-error naming them, never silence; one that changed no source stays silent
  (let [db (mdb [(decl "add")] :extra (concat (work "add" :done) (work "add-merge" :ready)))
        [st outs] (r/reconciler-proc (r/reconciler-proc {:boot "boot-1" :conn conn}) :main (ts/commit-of conn db))
        st (assoc st :source {:branch "jolt/seon-merges"})
        asked (first (filter #(= :octopus-merge (get-in % [:req :req :op])) (:to-api outs)))
        answered (fn [x] (let [[_ outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r {:ok true :results [x]}})
                               tx (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= :write (get-in % [:req :op])) (:to-api outs2))))]
                           (task/task (d/db-with db tx) "demo/add-merge")))
        unfiled (answered {:merged? true :tx 9 :keys 2 :files [] :unfiled {'app.no-file [[:var 'app.no-file/probe]]}})
        data (answered {:merged? true :tx 9 :keys 1 :files [] :unfiled {}})
        ;; filed and unfiled: its write-back records the sha of what it wrote, and the error beside it
        ;; names what no file holds
        mixed (answered {:merged? true :tx 9 :keys 3 :files ["src/app/m.clj"] :unfiled {'app.no-file [[:var 'app.no-file/probe]]}})]
    (is (some? asked))
    (is (= :merged (:seon.task/outcome unfiled)))
    (is (= "merged at t 9; these changed forms name no source file to write back: app.no-file [:var app.no-file/probe]"
           (:seon.task/commit-error unfiled)))
    (is (= :merged (:seon.task/outcome data)))
    (is (nil? (:seon.task/commit-error data)) "a change of no source names no file, and says nothing")
    (is (= "merged at t 9; these changed forms name no source file to write back: app.no-file [:var app.no-file/probe]"
           (:seon.task/commit-error mixed))
        "a member that wrote some files names the forms no file holds: none is dropped without a word")
    (testing "a write-back whose files main holds no text for: no commit, and each member's task says so"
      (let [step (fn [st tag r] (r/reconciler-proc st :answers {:tag tag :r r}))
            write-of (fn [outs] (first (filter #(= :write (get-in % [:req :op])) (:to-api outs))))
            [st2 outs2] (step st (:tag asked) {:ok true :results [{:merged? true :tx 9 :keys 1 :files ["src/app/none.clj"] :unfiled {}}]})
            merged-tx (get-in (write-of outs2) [:req :req :tx-data])
            [st3 outs3] (step st2 (:tag (write-of outs2)) {:ok true})
            [_ outs4] (step st3 (:tag (write-of outs3)) {:db db})
            tk (task/task (d/db-with db (vec (concat merged-tx (get-in (write-of outs4) [:req :req :tx-data])))) "demo/add-merge")]
        (is (not-any? :seon.task/commit-error (filter map? merged-tx)) "a member with files is the write-back's to record")
        (let [two (update-in st [:flights "demo/add-merge" :act :members] conj {:task "demo/data-merge"})
              [st2' _] (step two (:tag asked) {:ok true :results [{:merged? true :tx 9 :keys 1 :files ["src/app/none.clj"] :unfiled {}}
                                                                   {:merged? true :tx 9 :keys 1 :files [] :unfiled {}}]})]
          (is (= [{:task "demo/add-merge"}] (get-in st2' [:flights "demo/add-merge" :writeback :members]))
              "a merged member with no files is no member of the write-back: no sha for files it never touched"))
        (is (= {:op :branch-db :branch :db} (get-in (write-of outs3) [:req :req])) "the write-back reads main")
        (is (= ["src/app/none.clj"] (:seon.git/files (:seon.task/error tk))) "held as a lost write-back, named by file")
        (is (nil? (:seon.task/commit-error tk)) "and no untyped note beside it"))))))
)

(deftest a-failed-write-back-is-a-root-cause-until-a-commit-holds-its-files
  ;; the :git effect refused the write-back of a merged task (a locked ref): it holds a typed error naming the files,
  ;; quiescence names it, and a later commit of them (my.root/write-back!) clears it, beside
  ;; the note of forms that name no file, which stays; a commit that left a file out (main has no text
  ;; for it) holds the error again, for that file alone
  (let [note "merged at t 9; these changed forms name no source file to write back: app.no-file [:var app.no-file/probe]"
        db (mdb [(decl "add")] :extra (concat (work "add" :done) (work "add-merge" :merged)
                                              [{:seon.task/id "demo/add-merge" :seon.task/commit-error note}]))
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
    (is (= [] (r/root-causes (r/index db))) "a merged task that lost nothing (and one with only the no-file note) is none")
    (is (= "abc123" (:seon.task/commit (task-of fixed))))
    (is (nil? (:seon.task/error (task-of fixed))) "a commit holds them: the error is gone")
    (is (= note (:seon.task/commit-error (task-of fixed))) "and the note of forms that name no file stays: a retry does not replace it")
    (is (= [] (r/root-causes (r/index fixed))))
    (testing "a commit that left a file out holds the error for that file alone, never silently clearing it"
      (is (= "def456" (:seon.task/commit (task-of partial))))
      (is (= ["src/app/m.clj"] (:seon.git/files (:seon.task/error (task-of partial)))))
      (is (= 1 (count (r/root-causes (r/index partial))))))))

(deftest a-held-task-shows-its-reason-among-the-problems
  (let [red [{:reason :proof-red :symbol (t "add")}]
        {db' :db} (judge! (mdb [(decl "add")] :extra (work "add" :running :members {:seon.task/reasons red})) "add" {:outcome :red} :results {(t "add") :fail})
        [row & more] (get-in (problems/report db') [:classes :failed :rows])]
    (is (nil? more))
    (is (= {:task "demo/add" :reason "the same verdict again: proof-red demo-test/add-test" :open? true}
           (select-keys row [:task :reason :open?])))
    (is (= [(t "add")] (get-in row [:evidence :red])) "with its measured evidence")
    (is (= [] (:answered-by row)) "its own evidence is not an answer to it")))

(deftest roots-release-starts-again-on-the-same-branch-with-the-evidence
  (let [red [{:reason :proof-red :symbol (t "add")}]
        db (mdb [(decl "add")] :extra (work "add" :running :members {:seon.task/reasons red}))
        {db' :db} (judge! db "add" {:outcome :red} :results {(t "add") :fail})
        problem "the same verdict again: proof-red demo-test/add-test"
        released (d/db-with db' (task/execution-tx ["demo/add"] :running))
        [sp & more] (actions-for released)]
    (is (nil? more))
    (is (= (mspawn "add") sp) "a new agent's start, on the task's own branch")
    (is (= (:seon.agent/branch (d/entity db [:seon.agent/key "boot-1/a-add"])) (get-in sp [:env :branch])))
    (testing "the resumed branch takes the task as main holds it: its failure and evidence"
      (let [on-branch (d/db-with db (task/onto-tx released db "demo/add"))
            tk (task/task on-branch "demo/add")]
        (is (= problem (:problem (:seon.task/evidence tk))))
        (is (= [(t "add")] (:red (:seon.task/evidence tk))))
        (is (= "boot-1/a-add" (:agent (:seon.task/failure tk))))))
    (is (not= :failed (r/task-state (r/index released) "demo/add")) "released, it is no longer failed")))

(deftest an-idle-system-does-nothing-after-a-reason
  (ts/test-branch! (fn [_ conn]
  ;; the reconciler proc's own steps: the reason's hold is one write, made where it was judged (the judging); once it is committed
  ;; nothing is left to do (quiet, naming the held task as a root cause); a root-only commit after it asks nothing
  (let [red [{:reason :proof-red :symbol (t "add")}]
        db (:db (judge! (mdb [(decl "add") (decl "twice" :needs ["add"])] :extra (work "add" :running :members {:seon.task/reasons red}))
                        "add" {:outcome :red} :results {(t "add") :fail}))
        commit (fn [db tx] (let [rep (d/with db tx)] [(:db-after rep) (pdb/report->tx rep)]))
        main (fn [st db ops] (r/reconciler-proc st :main (assoc (ts/commit-of conn db) :ops ops)))
        reqs (fn [outs] (remove #(= :stat (:op %)) (:to-api outs)))
        [st outs] (main (r/reconciler-proc {:boot "boot-1" :conn conn}) db [])
        quiet (some #(get-in % [:req :req :tx-data 0 :seon.root/quiescent]) (reqs outs))]
    (is (= :held (:seon.task/execution (task/task db "demo/add"))))
    (is (= [{:task "demo/add" :cause "the same verdict again: proof-red demo-test/add-test" :agent "boot-1/a-add"
             :blocks ["demo/add-merge" "demo/twice" "demo/twice-merge"]}]
           (:root-causes quiet)) "quiet, naming the held task and what it blocks")
    (is (not-any? #(= :spawn (get-in % [:req :op])) (reqs outs)) "nothing starts")
    (let [[db3 ops3] (commit db [{:seon.root/id :root :seon.root/quiescent quiet}])
          [_ outs3] (main st db3 ops3)]
      (is (empty? (reqs outs3)) "a commit of root's own report asks nothing")))))
)

(deftest stuck-names-cancelled-and-held-roots
  (let [db (db-of diamond :extra (concat (merged "a") (task/execution-tx ["demo/b"] :cancelled) (task/execution-tx ["demo/c"] :held)))]
    (is (= [{:task "demo/b" :cause "cancelled" :blocks ["demo/d"]} {:task "demo/c" :cause "held" :blocks ["demo/d"]}]
           (r/root-causes (r/index db))))))

(deftest hairy-rule
  ;; refs: the task's proofs and the names its plan's cites hold (the facts its write derived, never its text re-parsed)
  (let [refs '#{app/g app/f app.proofs/a-test}
        c (fn [k kind] {:key [:var k] :kind kind})
        merge-cfg {:max-conflicts 3 :max-merge-turns 2}]
    (are [conflicts merge-turns rule] (= rule (:rule (r/hairy (merge merge-cfg {:conflicts conflicts :refs refs :merge-turns merge-turns}))))
      [] 0 nil
      [(c 'app/f :both-modified)] 0 nil
      [(c 'app/f :both-modified) (c 'app/h :both-modified) (c 'app/i :both-added)] 0 nil
      [(c 'app/f :both-modified) (c 'app/h :both-modified) (c 'app/i :both-added) (c 'app/j :both-added)] 0 :too-many-conflicts
      [(c 'app/g :deleted-by-them)] 0 :deleted-by-them-referenced
      [(c 'app/z :deleted-by-them)] 0 nil
      [(c 'app.proofs/a-test :deleted-by-them)] 0 :deleted-by-them-referenced
      [(c 'app/f :both-modified)] 3 :merge-turns)))

(deftest quiescence-is-reported-when-nothing-can-move
  (ts/test-branch! (fn [_ conn]
  ;; the reconciler proc's own steps over real commits: a task held for root and its dependents, nothing live
  (let [db (mdb [(decl "add") (decl "twice" :needs ["add"])]
                :extra (concat (work "add" :closed)
                               [{:seon.task/id "demo/add" :seon.task/execution :held :seon.task/failure {:reason "capped"}}]))
        ;; main's commits are the test branch's: the first holds db's datoms, the others are transacted on it
        db (d/commit-as-db conn (:commit (ts/commit-of conn db)))
        commit (fn [_ tx] (let [rep (d/transact conn {:tx-data tx})] [(:db-after rep) (pdb/report->tx rep)]))
        step (fn [st db ops] (let [[st outs] (r/reconciler-proc st :main (note db (:t st) ops))] [st (:to-api outs)]))
        report-in (fn [outs] (some #(get-in % [:req :req :tx-data 0 :seon.root/quiescent]) outs))
        [st outs] (step (r/reconciler-proc {:boot "boot-1" :conn conn}) db [])
        report (report-in outs)
        blocked ["demo/add-merge" "demo/twice" "demo/twice-merge"]]
    (testing "nothing to start, nothing in flight: one positive report, naming the held root and what it blocks"
      (is (empty? (:flights st)))
      (is (= {:t (:max-tx db) :root-causes [{:task "demo/add" :cause "capped" :blocks blocked}]} report))
      (is (some #(= {:op :collect} (get-in % [:req :req])) outs) "and asks the writer to collect the store, once a spell")
      (is (:quiet st)))
    (let [[db2 ops2] (commit db [{:seon.root/id :root :seon.root/quiescent report}])
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

(deftest every-way-a-task-leaves-running-writes-its-outcome
  ;; root, 2026-10-26: a task with no outcome reads as a running one (an agent's ctx and its page's tile read it so),
  ;; so every way root moves a task off :running writes one with its reason: a reap (root restarted), a kill of a
  ;; live agent, a death, the writer refusing to read its branch; a refused spawn and a lost wake (their own tests), and
  ;; the judge's end (the agent's, or none)
  (let [db (db-of [(decl "a")] :extra (work "a" :running))
        st (st-of db)
        flight {:act {:op :spawn :task "demo/a" :env {:branch :task-demo-a}} :agent :a-a}
        outcome (fn [[_ outs]] (:seon.task/outcome (task/task (d/db-with db (mapcat #(get-in % [:req :req :tx-data]) outs)) "demo/a")))
        died (contract/error :seon.agent/died 'seon.root/end-agent "agent a-a died" {:agent :a-a})]
    (is (= :lost (outcome (#'r/start st {:op :reap :task "demo/a"}))) "a reap: lost")
    (is (= :killed (outcome (#'r/start st {:op :kill :task "demo/a" :reason :cancelled}))) "a kill of a live agent: killed")
    (is (= :dead (outcome (#'r/advance (assoc-in st [:flights "demo/a"] (assoc flight :died died)) "demo/a" :dead-read {:db nil})))
        "a death: dead")
    (is (= :closed (outcome (#'r/advance (assoc-in st [:flights "demo/a"] flight) "demo/a" :diffed
                                         (contract/error :seon.writer/refused 'seon.writer/refused "no branch :task-demo-a" {}))))
        "the writer refused its branch: closed, with its reason")))

(deftest a-stall-task-starts-no-agent
  ;; owner, 2026-10-26: "Hold off launching paid agents for now": root's stall task is created and
  ;; shown, and worked by a Claude session through seon_attach; the reconciler forks nothing for it
  (let [db (db-of [(tpl/stall :a1 "demo/t#1" {:quiet-ms 4000 :pid 77})] :released [])]
    (is (some? (task/task db "root/stall-a1-demo-t-1")) "the task is on main")
    (is (not-any? #(#{:spawn :wake} (:op %)) (actions-for db)) "and no agent is started for it")))

(def ^:private held-why "branch :demo/a is held by live agent a9")

(deftest a-refused-spawn-writes-the-refused-outcome
  (ts/test-branch! (fn [_ conn]
  ;; root refuses the spawn (the branch is held, or too many live agents): the refused answer is
  ;; the task's outcome, written on main, and the next pass starts the task again
  (let [db (db-of [(decl "a")])
        [st outs] (r/reconciler-proc (r/reconciler-proc {:boot "boot-1" :conn conn}) :main (ts/commit-of conn db))
        asked (first (filter #(= :spawn (get-in % [:req :op])) (:to-api outs)))
        held (contract/error :seon.root/refused 'seon.root/spawn! held-why {:branch :demo/a :agent :a9})
        [_ outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r held})
        written (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= :write (get-in % [:req :op])) (:to-api outs2))))
        tk (task/task (d/db-with db written) "demo/a")]
    (is (some? asked) "the first pass asks for the spawn")
    (is (= :refused (:seon.task/outcome tk)) "the refused answer is written as the task's outcome")
    (is (= held-why (:seon.task/reason tk)) "with root's reason")
    (is (nil? (:seon.task/agent tk)) "and no agent, since none was made")
    (is (= [(spawn "a")] (actions-for (d/db-with db written))) "the next pass starts the task again"))))
)

(deftest a-refused-task-asks-again-when-main-moves
  (ts/test-branch! (fn [_ conn]
  ;; if nothing has changed, asking again is refused again: neither the refused answer nor
  ;; its own commit starts the task; other work on main lets it ask again
  (let [commit (fn [db tx] (let [rep (d/with db tx)] [(:db-after rep) (pdb/report->tx rep)]))
        main (fn [st db ops] (r/reconciler-proc st :main (assoc (ts/commit-of conn db) :ops ops)))
        spawns (fn [outs] (vec (keep #(when (= :spawn (get-in % [:req :op])) (get-in % [:req :opts :task])) (:to-api outs))))
        db (db-of [(decl "a")])
        [st outs] (main (r/reconciler-proc {:boot "boot-1" :conn conn}) db [])
        asked (first (filter #(= :spawn (get-in % [:req :op])) (:to-api outs)))
        [st outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r (contract/error :seon.root/refused 'seon.root/spawn! held-why {:branch :demo/a :agent :a9})})
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

(deftest quiescence-names-a-task-that-cannot-start
  ;; a refused task is a root cause at quiescence, naming what it blocks
  (let [db (db-of [(decl "a") (decl "b" :needs ["a"])]
                  :extra [{:seon.task/id "demo/a" :seon.task/outcome :refused :seon.task/reason held-why}])]
    (is (= [{:task "demo/a" :cause held-why :blocks ["demo/b"]}] (r/root-causes (r/index db))))))

(deftest a-task-marked-failed-is-held-for-root
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
    (is (= [] (actions-for judged)) "nothing refines or retries it")
    (let [[row & more] (get-in (problems/report judged) [:classes :failed :rows])]
      (is (nil? more))
      (is (= {:task "demo/a" :reason "the premise is refuted" :counterexample "(= 1 2)" :open? true}
             (select-keys row [:task :reason :counterexample :open?])) "root is told: a :failed row, the one producer of what is wrong"))
    (let [released (d/db-with judged (task/execution-tx ["demo/a"] :running))]
      (is (not= :failed (r/task-state (r/index released) "demo/a")) "a release is root's answer")
      (is (false? (:open? (first (get-in (problems/report released) [:classes :failed :rows])))) "and the row closes"))
    (is (nil? (:seon.task/failure (task/task (:db (judge (assoc f :turn 0))) "demo/a")))
        "an earlier turn's failure on a reused branch is not this end's")))

(deftest a-branch-carries-every-task-it-changed-and-records-a-two-sided-edit
  ;; owner, 2026-10-22 (D4): when a branch's agent ends, a task version newer than main's
  ;; overwrites it; one edited on both sides differently is a conflict for root
  (let [base (db-of [(decl "a") (decl "b") (decl "c")] :extra (work "a" :running))
        branch (d/db-with base [{:seon.task/id "demo/b" :seon.task/plan "b, as a's agent learned it"}
                                {:seon.task/id "demo/c" :seon.task/plan "c, the branch's"}])
        main (d/db-with base [{:seon.task/id "demo/c" :seon.task/plan "c, main's"}])
        judged (:db (judge! main "a" nil :branch branch :diff {:base base}))
        conflict {:task "demo/c" :attr :seon.task/plan :base "Make c true." :ours "c, the branch's" :theirs "c, main's"}]
    (is (= "b, as a's agent learned it" (:seon.task/plan (task/task judged "demo/b"))) "another task's edit reaches main at the agent's end")
    (is (= "Make a true." (:seon.task/plan (task/task judged "demo/a"))) "an unchanged task stays as main holds it")
    (is (= "c, main's" (:seon.task/plan (task/task judged "demo/c"))) "a two-sided edit is never resolved here")
    (is (= [conflict] (:seon.task/task-conflicts (task/task judged "demo/a"))))
    (is (= [(assoc conflict :from "demo/a")] (map :evidence (get-in (problems/report judged) [:classes :task-conflict :rows])))
        "root sees it among the problems")
    (testing "main's task plans never put a merge behind: merging merges the program, no task data"
      (is (empty? (:theirs (pm/three-way (pm/program base #{}) (pm/program branch #{}) (pm/program main #{}))))))))

(deftest a-waiting-agent-parks-its-task-and-a-message-wakes-it
  ;; design agent-loop sections 1-2 (owner, 2026-10-25: "Always park"): an agent that ends waiting
  ;; or without progress parks its session; out of turns, it stays at its cap. The task is parked, never held, and stays
  ;; released. Until a message to the session comes, the task does not start again; once one
  ;; comes, its next start is a wake on the parked process when that process is of this boot
  (let [red {(t "a") :fail}
        parked (fn [outcome taken] (work "a" outcome :taken taken))
        msg (fn [mid] (task/message-tx mid :message "user" (task/default-branch "demo/a") "carry on"))
        wake {:op :wake :task "demo/a" :agent :a-a
              :env {:branch (task/default-branch "demo/a") :model nil :max-turns (cfg/value :seon.config.agent/max-turns)}}
        db0 (db-of [(decl "a")] :extra (work "a" :running))]
    (testing "judged: a park outcome parks the task (the cap stays at its cap); a red outcome is a verdict to the agent"
      (is (= :parked (:outcome (judgment-of db0 "a" {:end {:outcome :waiting} :results red}))))
      (is (= :cap (:outcome (judgment-of db0 "a" {:end {:outcome :cap} :results red}))))
      (is (= :told (:outcome (judgment-of db0 "a" {:end {:outcome :red} :results red})))))
    (testing "parked, nothing new: nothing starts"
      (is (= [] (actions-for (db-of [(decl "a")] :extra (parked :parked 0)))))
      (is (= [] (actions-for (db-of [(decl "a")] :extra (into (parked :cap 1) (msg "m1"))))) "one message, already taken")
      (is (= [{:task "demo/a" :cause "parked: cap, waiting for a message" :agent (akey "a") :blocks ["demo/b"]}]
             (r/root-causes (r/index (db-of [(decl "a") (decl "b" :needs ["a"])] :extra (parked :cap 0)))))
          "quiet names it, with what it blocks"))
    (testing "a wake in flight: more messages start nothing more, since one process runs one work! (flow-lab's hazard 4); the wake takes them, or parks short of them and wakes again"
      (is (= [] (actions-for (db-of [(decl "a")] :extra (concat (work "a" :running :taken 0) (msg "m1") (msg "m2")))))))
    (testing "a message it has not taken: a wake on its process (one that came while it ran is counted too: flow-lab's lost wake, hazard 3)"
      (is (= [wake] (actions-for (db-of [(decl "a")] :extra (into (parked :parked 0) (msg "m1"))))))
      (is (= [wake] (actions-for (db-of [(decl "a")] :extra (into (parked :parked 1) (concat (msg "m1") (msg "m2"))))))))
    (testing "parked, its task cancelled or held: its process is stopped (a kill of the parked agent), nothing starts"
      (is (= [{:op :kill :task "demo/a" :reason :cancelled}]
             (actions-for (db-of [(decl "a")] :extra (into (parked :parked 0) (concat (msg "m1") (task/execution-tx ["demo/a"] :cancelled)))))))
      (is (= [{:op :kill :task "demo/a" :reason :held}]
             (actions-for (db-of [(decl "a")] :extra (into (parked :parked 0) (task/execution-tx ["demo/a"] :held)))))))
    (testing "parked in an earlier boot: its process is gone, so the next start is a spawn (a load of the session)"
      (is (= [(spawn "a")] (actions-for (db-of [(decl "a")] :extra (into (work "a" :parked :boot "boot-0" :taken 0) (msg "m1")))))))
    (testing "it waited with its proofs green: done, never parked (start stops its kept process)"
      (is (= {:outcome :done :reasons []} (judgment-of db0 "a" {:end {:outcome :waiting} :results {(t "a") :pass}}))))
    (testing "its task moved to another branch: the parked process (forked on the old one) is stopped, and the next start is a spawn"
      (let [db (db-of [(decl "a")] :extra (concat (parked :parked 0) (msg "m1") [{:seon.task/id "demo/a" :seon.task/branch :elsewhere}]))]
        (is (= [{:op :kill :task "demo/a" :reason :env-changed}] (actions-for db)) "a task has one flight, so one action a pass")
        (is (= [(assoc-in (spawn "a") [:env :branch] :elsewhere)] (actions-for (d/db-with db (task/move-tx "demo/a" :parked :killed {})))))))
    (testing "the message count is observed at each commit as listing finds it, whichever way it came"
      (let [db0 (db-of [(decl "a")] :extra (parked :parked 0))]
        (observed-steps db0 [["a first message" (msg "m1")] ["a second" (msg "m2")]])
        (is (= 2 (get-in (r/index (d/db-with db0 (concat (msg "m1") (msg "m2")))) [:msgs (task/default-branch "demo/a")])))))))

(deftest a-sessions-next-stage-opens-with-roots-message
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

(deftest a-step-reads-the-db-its-own-write-made
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
        stage (fn [outs] (filter #(and (= :message (get-in % [:req :op])) (= :stage (get-in % [:req :kind]))) (reqs outs)))]
    (is (= "demo/i" (get-in asked [:req :opts :task])) "the next stage's spawn")
    (is (empty? (reqs outs3)) "its write's answer waits: main has not reached the write")
    (is (= 1 (count (stage outs4))) "main's commit of it takes the answer: the stage's entry, read off the db holding the start")
    (is (empty? (:behind st4)) "and holds it no longer")
    (is (= 1 (count (stage outs5))) "a later commit than the write's takes it as well"))))
)

(deftest a-proof-a-branch-registers-reaches-main-at-the-agents-end
  ;; owner, 2026-10-25 (Dogfood rulings): a design registers the red proofs it writes
  ;; (my.task/update! :seon.task/proofs conj 't); the judge carries them onto main like the plan,
  ;; so the done rule sees them at design and the implement step inherits them
  (let [base (db-of [(decl "a" :proofs #{}) (decl "b" :proofs #{'demo-test/kept-test 'demo-test/dropped-test})] :extra (work "a" :running))
        branch (d/db-with base [[:db/add [:seon.task/id "demo/a"] :seon.task/proofs 'demo-test/a-design-wrote-test]
                                [:db/retract [:seon.task/id "demo/b"] :seon.task/proofs 'demo-test/dropped-test]])
        judged (:db (judge! base "a" nil :branch branch))]
    (is (= #{'demo-test/a-design-wrote-test} (set (:seon.task/proofs (task/task judged "demo/a")))) "an added proof reaches main")
    (is (= #{'demo-test/kept-test} (set (:seon.task/proofs (task/task judged "demo/b")))) "a dropped one leaves it")
    (is (= #{'demo-test/a-design-wrote-test} (get-in (r/index judged) [:acc "demo/a"])) "the done rule's input holds it")
    (testing "the implement step that needs the design inherits it, observed as the reconciler does (live finding F3)"
      (let [base (d/db-with base (task/create-tx base [(decl "c" :needs ["a"] :proofs #{})]))
            branch (d/db-with base [[:db/add [:seon.task/id "demo/a"] :seon.task/proofs 'demo-test/a-design-wrote-test]])
            judge (fn [main nm] (:db (judge! main nm nil :branch branch :diff {:base base})))
            judged (judge (d/db-with base (into (work "a" :running) (task/execution-tx ["demo/c"] :running))) "a")
            idx0 (r/index base)]
        (is (= #{'demo-test/a-design-wrote-test} (get-in (r/index judged) [:acc "demo/c"])) "listed")
        (is (= #{'demo-test/a-design-wrote-test} (get-in (r/index idx0 base judged) [:acc "demo/c"])) "observed from the index before")
        (let [judged2 (judge (d/db-with judged (work "c" :running)) "c")]
          (is (= #{'demo-test/a-design-wrote-test} (:seon.task/proofs (task/task judged2 "demo/a")))
              "the implement's judge, on the same branch from the same base, keeps the design's proof on main")
          (is (= #{'demo-test/a-design-wrote-test} (get-in (r/index judged2) [:acc "demo/c"]))))))
    (testing "a proof set main changed too, differently, is a conflict for root"
      (let [main (d/db-with base [[:db/add [:seon.task/id "demo/b"] :seon.task/proofs 'demo-test/mains-test]])
            {:keys [tx conflicts]} (task/carry-tx base branch main)]
        (is (not-any? #(= [:seon.task/id "demo/b"] (second %)) tx))
        (is (= [{:task "demo/b" :attr :seon.task/proofs :base #{'demo-test/kept-test 'demo-test/dropped-test}
                 :ours #{'demo-test/kept-test} :theirs #{'demo-test/kept-test 'demo-test/dropped-test 'demo-test/mains-test}}]
               (filter #(= "demo/b" (:task %)) conflicts)))))))

(deftest a-refused-merge-candidate-is-the-gates-error
  ;; the Sonnet review of gate-schema (2026-10-30): :gate-read took the candidate's :text with no check, so a
  ;; writer that refused the candidate sent the gate no files, and it would have tested main as it is
  (let [id "demo/a-merge"
        refused (contract/error :seon.root/refused #'seon.reconcile-test/a-refused-merge-candidate-is-the-gates-error "no branch demo/x")
        st {:db (ts/test-db) :boot "b1" :rid 0 :pending {} :flights {id {:act {:op :gate :task id :tests ['seon.root-test/boots]}}}}
        [_ outs] (#'r/advance st id :gate-read refused)
        reqs (map :req outs)]
    (is (not-any? #(= :tests (:kind %)) reqs) "no tests run on a checkout with no candidate")
    (let [tx (some #(get-in % [:req :tx-data]) reqs)]
      (is (some #(= {'seon.root-test/boots :error} (:seon.task/deferred-results %)) tx)
          (str "each deferred test :error: " (pr-str tx)))
      (is (some #(= refused (:seon.task/error %)) tx) "with why it was refused as the task's error"))))

(clojure.test/deftest
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
