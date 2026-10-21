(ns seon.task-test
  "The task writer derives what a task cites (seon.docs.edn's one-reference-reader, task
  seon.task/a-task-cites-what-it-needs): references from the plan, never authored, and a
  citation that resolves to nothing answered beside the write."
  (:require [clojure.test :refer [deftest is]] [datahike.api :as d]
            [seon.db :as pdb] [seon.task :as task]))

(defn- db-with-program []
  (d/db-with @(second (pdb/test-branch!))
             [{:db/id "ns" :ns/name 'demo} {:var/name 'demo/f :var/ns "ns" :var/src "(defn f [x] x)"}]))

(defn- decl [plan] {:seon.task/ns 'demo :seon.task/name "t" :seon.task/plan plan})

(deftest the-writer-derives-a-tasks-references
  (let [plan "Make `demo/f` pass; ``demo/later`` is only mentioned; `(demo/f 1)` is code."
        [written] (task/create-tx (db-with-program) [(decl plan)])]
    (is (= #{'demo/f} (set (:seon.task/references written))) "a cited name, not a literal or a form")
    (is (not (contains? (first (task/create-tx (db-with-program) [(decl "No names here.")])) :seon.task/references))
        "no names, no key")))

(deftest unresolved-citations-are-answered-not-refused
  (let [db (db-with-program)
        ds [(decl "Make `demo/f` and `demo/a` pass.")]]
    (is (= [{:task "demo/t" :name 'demo/a :problem :unresolved}] (task/citation-problems db ds)))
    (is (vector? (task/create-tx db ds)) "the write still happens")))
