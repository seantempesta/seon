(ns seon.task-test
  "The task writer derives what a task cites (seon.docs.edn's one-reference-reader, task
  seon.task/a-task-cites-what-it-needs): references from the plan, never authored, and a
  citation that resolves to nothing answered beside the write."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is]] [datahike.api :as d]
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

(deftest a-continued-branch-takes-its-refinements-tasks-from-main
  ;; root's ruling (2026-10-22): a refinement continues the failed branch, which was forked
  ;; before main held the refinement's tasks; the step E dry run's r1 design read its task on
  ;; its branch as nil and threw (seon.reconcile/dials). onto-tx puts the task and what it
  ;; needs there, as main holds them, and leaves what the branch already holds
  (let [db0 (ts/test-db)
        branch (d/db-with db0 (task/create-tx db0 [{:seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "Branch's own a."}]))
        main (d/db-with branch (task/create-tx branch [{:seon.task/ns 'demo :seon.task/name "r1-design" :seon.task/plan "Refine." :seon.task/needs #{"demo/a"}}
                                                       {:seon.task/ns 'demo :seon.task/name "r1-implement" :seon.task/plan "" :seon.task/needs #{"demo/r1-design"}}]))
        main (d/db-with main [{:seon.task/id "demo/a" :seon.task/plan "Main's a."}])
        tx (task/onto-tx main branch "demo/r1-implement")
        after (d/db-with branch tx)]
    (is (= #{"demo/r1-design" "demo/r1-implement"} (set (map :seon.task/id tx))) "the task and what it needs that the branch lacks")
    (is (= "Refine." (:seon.task/plan (task/task after "demo/r1-design"))))
    (is (= #{"demo/r1-design"} (:seon.task/needs (task/task after "demo/r1-implement"))) "its needs resolve on the branch")
    (is (= "Branch's own a." (:seon.task/plan (task/task after "demo/a"))) "a task the branch holds is left as the branch has it")
    (is (= [] (task/onto-tx main after "demo/r1-implement")) "nothing when the branch holds them")))
