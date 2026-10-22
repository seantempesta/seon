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
