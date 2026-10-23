(ns seon.task-test
  "The task writer derives what a task cites (seon.docs.edn's one-reference-reader, task
  seon.task/a-task-cites-what-it-needs): references from the plan, never authored, and a
  citation that resolves to nothing answered beside the write."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [datahike.api :as d] [seon.repl :as repl]
            [seon.db :as pdb] [seon.task :as task] [seon.ref :as ref] [seon.template :as template]))

(defn- db-with-program []
  ;; one recorded definition, its form (seon.program's facts), written as data
  (d/db-with (ts/test-db)
             [{:db/id "ns" :ns/name 'demo}
              {:db/id "v" :var/name 'demo/f :var/ns "ns" :var/form "form"}
              {:db/id "form" :form/id "demo/f" :form/src "(defn f [x] x)" :form/ns "ns" :form/order 0 :form/defines ["v"]}]))

(defn- decl [plan] {:seon.task/ns 'demo :seon.task/name "t" :seon.task/plan plan})

(deftest the-writer-derives-a-tasks-references
  (let [plan "Make `demo/f` pass; ``demo/later`` is only mentioned; `(demo/f 1)` is code."
        [written] (task/create-tx (db-with-program) [(decl plan)])]
    (is (= #{'demo/f} (set (:seon.task/references written))) "a cited name, not a literal or a form")
    (is (not (contains? (first (task/create-tx (db-with-program) [(decl "No names here.")])) :seon.task/references))
        "no names, no key")))

(deftest a-templates-instructions-are-cited-as-a-plan-is
  ;; owner, 2026-10-22: instructions are cited text; a var a kind's instructions cite reaches
  ;; the task's references, and so its context, exactly as a plan's does (the navigation functions
  ;; every kind shares are the help page's, docs/agent.md)
  (doseq [kind [:design :implement :test :merge]
          :let [[written] (task/create-tx (db-with-program) [(assoc (decl "") :seon.task/instructions (template/instructions kind))])]]
    (is (= (set (ref/names (template/instructions kind))) (set (:seon.task/references written))) (str kind ": every name, derived"))))

(deftest unresolved-citations-are-answered-not-refused
  (let [db (db-with-program)
        ds [(decl "Make `demo/f` and `demo/a` pass.")]]
    (is (= [{:task "demo/t" :name 'demo/a :problem :unresolved}] (task/citation-problems db ds)))
    (is (vector? (task/create-tx db ds)) "the write still happens")))

(deftest the-reader-answers-every-stored-member
  ;; research row 20: the reader pulls what main stores (Datahike's wildcard), so a task
  ;; attribute is written in one place; references were stored and never read back
  (let [db (d/db-with (db-with-program) (task/create-tx (db-with-program) [(decl "Make `demo/f` pass.")]))
        t (task/task db "demo/t")]
    (is (= #{'demo/f} (:seon.task/references t)))
    (is (not-any? #{:db/id :seon.task/declared} (keys t)) "the stored declaration is load-tx's, not the reader's")
    (is (= t (get (task/tasks db) "demo/t")))))

(deftest a-resumed-branch-takes-its-needs-from-main
  ;; a kept branch resumed for a run may have been forked before main held the tasks its
  ;; task needs (the step E dry run read its task on its branch as nil and threw).
  ;; onto-tx puts the task and what it needs there, as main holds them, and leaves another
  ;; task the branch already holds as the branch has it
  (let [db0 (ts/test-db)
        branch (d/db-with db0 (task/create-tx db0 [{:seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "Branch's own a."}]))
        main (d/db-with branch (task/create-tx branch [{:seon.task/ns 'demo :seon.task/name "schema" :seon.task/plan "Register the schema." :seon.task/needs #{"demo/a"}}
                                                       {:seon.task/ns 'demo :seon.task/name "store" :seon.task/plan "" :seon.task/needs #{"demo/schema"}}]))
        main (d/db-with main [{:seon.task/id "demo/a" :seon.task/plan "Main's a."}])
        tx (task/onto-tx main branch "demo/store")
        after (d/db-with branch tx)]
    (is (= #{"demo/schema" "demo/store"} (set (map :seon.task/id tx))) "the task and what it needs that the branch lacks")
    (is (= "Register the schema." (:seon.task/plan (task/task after "demo/schema"))))
    (is (= #{"demo/schema"} (:seon.task/needs (task/task after "demo/store"))) "its needs resolve on the branch")
    (is (= "Branch's own a." (:seon.task/plan (task/task after "demo/a"))) "a task the branch holds is left as the branch has it")
    (is (= [] (task/onto-tx main after "demo/store")) "nothing when the branch holds them")))

(deftest a-released-branch-takes-its-task-from-main
  ;; owner, 2026-10-23: a refused run holds its task for root, and root's release runs it
  ;; again on the same, kept branch; root's judge wrote the failure and the evidence on main
  ;; after the branch last moved, so the resumed agent takes its task as main holds it
  (let [db0 (ts/test-db)
        branch (d/db-with db0 (task/create-tx db0 [{:seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "Make a true."}]))
        ev {:from "demo/a#1" :conflicts [] :unmerged [] :red ['demo-test/a-test] :problem "proof-red demo-test/a-test"}
        main (d/db-with branch [{:seon.task/id "demo/a" :seon.task/evidence ev :seon.task/failure {:reason "proof-red demo-test/a-test" :run "demo/a#1"}}])
        after (d/db-with branch (task/onto-tx main branch "demo/a"))]
    (is (= ev (:seon.task/evidence (task/task after "demo/a"))) "the evidence")
    (is (= "demo/a#1" (:run (:seon.task/failure (task/task after "demo/a")))) "and the failure it answers")
    (is (= [] (task/onto-tx main after "demo/a")) "nothing when the branch holds it as main does")))

;; ---- the links between root, an agent, its run, turns and messages are refs (task
;; seon.render/the-agents-links-are-refs): a pull follows them ----------------------------

(deftest an-agents-links-pull-from-its-root
  ;; root records its agent on main; the reconciler records the run, naming the agent by its key;
  ;; the agent's branch records a turn of the run, its evaluations and the message it took: each
  ;; written by its producer's own function, each link a ref a pull follows from root down
  (ts/test-branch!
   (fn [cfg conn]
     (let [main (:branch cfg) b (keyword (str (name main) "-a3"))
           k (task/agent-key "b1" :a3)]
       (d/transact conn [{:seon.root/id :root :seon.root/boot "b1"}])
       (d/transact conn [{:seon.task/id "demo/f" :seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :task-demo-f}])
       (d/transact conn (task/agent-tx "b1" :a3 {:status :idle :pid 77 :running {:form "(f 2)"}}))
       (d/branch! conn main b)
       (let [bc (d/connect (assoc cfg :branch b))]
         (try
           (d/transact conn (task/run-start-tx "demo/f#1" "demo/f" "b1" k {:branch b :model "m" :max-turns 8} 0 "p"))
           (d/transact conn (task/message-tx "m-1" "owner" "demo/f#1" "check g"))
           ;; on the branch, which forked before main held the run or the message
           (d/transact bc [{:db/id "run" :seon.run/id "demo/f#1"} {:seon.turn/id "demo/f#1/0" :seon.turn/run "run" :seon.turn/n 0 :seon.turn/origin :model}])
           (d/transact bc (task/message-tx "m-1" "owner" "demo/f#1" "check g"))
           (d/transact bc (repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "a3-77-1" [{:id 1 :tag :ret :form "(f 2)" :printed "4"}] nil))
           (testing "from root on main: its agents, each agent's run"
             (is (= [{:seon.agent/id :a3 :seon.agent/status :idle :seon.agent/pid 77 :seon.agent/running "(f 2)"
                      :seon.run/_agent [{:seon.run/id "demo/f#1"}]}]
                    (:seon.agent/_root (d/pull @conn '[{:seon.agent/_root [:seon.agent/id :seon.agent/status :seon.agent/pid :seon.agent/running
                                                                           {:seon.run/_agent [:seon.run/id]}]}]
                                               [:seon.root/id :root])))))
           (testing "the message is to the run, on main"
             (is (= [{:seon.message/body "check g"}] (:seon.message/_to (d/pull @conn '[{:seon.message/_to [:seon.message/body]}] [:seon.run/id "demo/f#1"])))))
           (testing "on the branch: the run's turns and the message it took, the agent's evaluations"
             (let [run (d/pull @bc '[{:seon.turn/_run [:seon.turn/id]} {:seon.message/_to [:seon.message/id]}] [:seon.run/id "demo/f#1"])]
               (is (= [{:seon.turn/id "demo/f#1/0"}] (:seon.turn/_run run)))
               (is (= [{:seon.message/id "m-1"}] (:seon.message/_to run))))
             (is (= ["a3-77-1/1"] (mapv :seon.cluster.eval/id (:seon.cluster.eval/_agent (d/pull @bc '[{:seon.cluster.eval/_agent [:seon.cluster.eval/id]}] [:seon.agent/key k]))))))
           (testing "the run, the message and the agent are one entity on the branch and on main (content ids)"
             (doseq [ref [[:seon.run/id "demo/f#1"] [:seon.message/id "m-1"] [:seon.agent/key k]]]
               (is (= (:db/id (d/entity @conn ref)) (:db/id (d/entity @bc ref))) (pr-str ref))))
           (testing "the run's reader names its agent by id"
             (is (= :a3 (:seon.run/agent (task/run @conn "demo/f#1")))))
           (finally (d/release bc) (d/delete-branch! conn b))))))))

(deftest an-agents-live-state-is-facts-on-its-entity
  ;; what root observes of an agent is recorded on the agent's entity: a member no longer true
  ;; is retracted, and an id that starts again in a later boot is another agent
  (let [db0 (d/db-with (ts/test-db) [{:seon.root/id :root :seon.root/boot "b1"}])
        db1 (d/db-with db0 (task/agent-tx "b1" :a3 {:status :running :pid 77 :running {:form "(f 2)" :since-ms 5} :mem-mb 120.5}))
        db2 (d/db-with db1 (task/agent-tx "b1" :a3 {:status :idle :running nil}))
        db3 (d/db-with db2 (task/agent-tx "b2" :a3 {:status :spawning}))
        pull (fn [db boot] (d/pull db [:seon.agent/status :seon.agent/pid :seon.agent/running :seon.agent/mem-mb {:seon.agent/root [:seon.root/id]}]
                                   [:seon.agent/key (task/agent-key boot :a3)]))]
    (is (= {:seon.agent/status :running :seon.agent/pid 77 :seon.agent/running "(f 2)" :seon.agent/mem-mb 120.5 :seon.agent/root {:seon.root/id :root}} (pull db1 "b1")))
    (is (= {:seon.agent/status :idle :seon.agent/pid 77 :seon.agent/mem-mb 120.5 :seon.agent/root {:seon.root/id :root}} (pull db2 "b1"))
        "the form returned: no longer running")
    (is (= :spawning (:seon.agent/status (pull db3 "b2"))) "the next boot's a3")
    (is (= :idle (:seon.agent/status (pull db3 "b1"))) "is not the earlier boot's")))

(deftest a-new-boot-leaves-the-earlier-boots-agents
  ;; root's agents are this boot's: a boot retracts the earlier boots' agents' link to root
  (let [db (d/db-with (ts/test-db) (into [{:seon.root/id :root :seon.root/boot "b1"}] (task/agent-tx "b1" :a1 {:status :idle})))
        db2 (d/db-with db (into (task/boot-tx "b2" 4) (task/earlier-agents-tx db)))
        db3 (d/db-with db2 (task/agent-tx "b2" :a1 {:status :spawning}))
        agents (fn [db] (set (map :seon.agent/key (:seon.agent/_root (d/pull db '[{:seon.agent/_root [:seon.agent/key]}] [:seon.root/id :root]))))) ]
    (is (= #{"b1/a1"} (agents db)))
    (is (= #{} (agents db2)) "the new boot holds none of the earlier boot's")
    (is (= #{"b2/a1"} (agents db3)) "and its own as they start")
    (is (some? (d/entity db3 [:seon.agent/key "b1/a1"])) "the earlier agent is kept, not deleted")))
