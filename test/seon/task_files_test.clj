(ns seon.task-files-test
  "Task files (resources/seon/tasks/<ns>.edn): declarations as reviewable data, reconciled
  into main. A new entry is created held; an unchanged one costs nothing and keeps what main
  learned (the improved plan); a changed one updates what it states; a removed one is
  cancelled, never deleted."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.task :as task] [my.root :as root]))

(defn- empty-db [] (ts/test-db))

(def ^:private file-a
  "[{:seon.task/name \"parse\" :seon.task/plan \"Parse the rows.\" :seon.task/proofs #{demo-test/parse-test}}
    {:seon.task/name \"index\" :seon.task/plan \"Index the rows.\" :seon.task/needs #{\"demo/parse\"}
     :seon.task/instructions \"One pass.\" :seon.task/proofs #{demo-test/index-test demo-test/index-is-one-pass}}]")

(defn- load-text
  "db after loading `text` as demo's task file."
  [db text]
  (let [tx (task/load-tx db {'demo (task/file-declarations 'demo text)})]
    (is (vector? tx) (pr-str tx))
    (d/db-with db tx)))

(deftest a-task-file-is-one-vector-of-declarations-named-by-its-namespace
  (let [ds (task/file-declarations 'demo file-a)]
    (is (= ["parse" "index"] (mapv :seon.task/name ds)))
    (is (every? #(= 'demo (:seon.task/ns %)) ds) "the file's name is the owning namespace")
    (is (= #{"demo/parse"} (:seon.task/needs (second ds)))))
  (testing "nothing is evaluated; a file that is not one vector of declarations is refused as data"
    (doseq [bad ["[{:seon.task/name \"x\"}]" "{:a 1}" "[1 2]" "[{:seon.task/name \"x\" :seon.task/plan \"p\" :bogus 1}]" "#=(+ 1 2)"]]
      (let [r (task/file-declarations 'demo bad)]
        (is (= :seon.task/refused (:seon.error/type r)) bad)
        (is (string? (:seon.error/message r)))))))

(deftest new-entries-are-created-held-and-remember-their-declaration
  (let [db (load-text (empty-db) file-a)
        idx (task/task db "demo/index")]
    (is (= :held (:seon.task/execution idx)))
    (is (= #{"demo/parse"} (:seon.task/needs idx)))
    (is (= "One pass." (:seon.task/instructions idx)))
    (is (some? (d/q '[:find ?s . :where [?e :seon.task/id "demo/index"] [?e :seon.task/declared ?s]] db)))))

(deftest an-unchanged-file-costs-nothing-and-keeps-what-main-learned
  (let [db (load-text (empty-db) file-a)
        db (d/db-with db [{:seon.task/id "demo/index" :seon.task/plan "Index the rows; group by key first (learned)."}])]
    (is (= [] (task/load-tx db {'demo (task/file-declarations 'demo file-a)})))
    (is (= "Index the rows; group by key first (learned)." (:seon.task/plan (task/task db "demo/index"))))))

(deftest a-changed-entry-updates-what-it-states-and-retracts-what-it-dropped
  (let [db (load-text (empty-db) file-a)
        changed "[{:seon.task/name \"parse\" :seon.task/plan \"Parse the rows.\" :seon.task/proofs #{demo-test/parse-test}}
                  {:seon.task/name \"index\" :seon.task/plan \"Index the rows by key.\" :seon.task/proofs #{demo-test/index-test}}]"
        db (load-text db changed)
        idx (task/task db "demo/index")]
    (is (= "Index the rows by key." (:seon.task/plan idx)))
    (is (= #{'demo-test/index-test} (:seon.task/proofs idx)) "a proof the file dropped is retracted")
    (is (= #{} (:seon.task/needs idx)) "a need the file dropped is retracted")
    (is (nil? (:seon.task/instructions idx)))
    (is (= :held (:seon.task/execution idx)) "execution is root's, never the file's")))

(deftest a-removed-entry-is-cancelled-not-deleted
  (let [db (load-text (empty-db) file-a)
        db (load-text db "[{:seon.task/name \"parse\" :seon.task/plan \"Parse the rows.\" :seon.task/proofs #{demo-test/parse-test}}]")]
    (is (= :cancelled (:seon.task/execution (task/task db "demo/index"))))
    (is (= :held (:seon.task/execution (task/task db "demo/parse"))))
    (testing "declaring it again revives it as held"
      (is (= :held (:seon.task/execution (task/task (load-text db file-a) "demo/index")))))))

(defn- refused-type
  "The error type of loading text as demo's file onto db, or nil."
  [db text] (:seon.error/type (task/load-tx db {'demo (task/file-declarations 'demo text)})))

(deftest needs-that-form-a-cycle-or-name-nothing-are-refused
  (let [db (load-text (empty-db) file-a)]
    (is (= :seon.task/refused
           (refused-type db "[{:seon.task/name \"parse\" :seon.task/plan \"p\" :seon.task/needs #{\"demo/index\"}}
                            {:seon.task/name \"index\" :seon.task/plan \"i\" :seon.task/needs #{\"demo/parse\"}}]"))
        "a cycle through a changed entry and a stored one")
    (is (= :seon.task/refused
           (refused-type db "[{:seon.task/name \"x\" :seon.task/plan \"p\" :seon.task/needs #{\"demo/nope\"}}]"))
        "a need that names no task")))

(deftest the-shipped-task-files-load
  ;; task seon.task/the-shipped-task-files-load: every file in the shipped task directory
  ;; reads and loads onto a test branch with no error
  (let [files (root/task-files)]
    (is (seq files))
    (is (= [] (vec (for [[ns-sym ds] files :when (:seon.error/type ds)] ns-sym))) "every file reads")
    (is (not (:seon.error/type (task/load-tx (empty-db) files))) "and loads")))

(deftest a-file-task-merges-itself
  ;; task seon.reconcile/a-work-task-merges-itself-through-the-one-gate: a task loaded from a
  ;; task file is one :whole task (seon.template/request), so no merge task is written and a
  ;; need on another file's task is a need on it
  (let [dir (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-task-files-" (random-uuid))) (.mkdirs))
        _ (spit (java.io.File. dir "demo.edn") file-a)
        load! (fn [db] (d/db-with db (task/load-tx db (root/task-files (str dir)))))
        db (load! (empty-db))]
    (is (= :whole (:seon.task/kind (task/task db "demo/parse"))) "parse works and merges itself")
    (is (nil? (task/task db "demo/parse-merge")) "and has no merge task")
    (is (= #{"demo/parse"} (:seon.task/needs (task/task db "demo/index"))) "a need stays on the task")
    (is (= [] (task/load-tx db (root/task-files (str dir)))) "an unchanged file writes nothing")
    (spit (java.io.File. dir "demo.edn") "[{:seon.task/name \"parse\" :seon.task/plan \"Parse the rows.\" :seon.task/proofs #{demo-test/parse-test}}]")
    (is (= :cancelled (:seon.task/execution (task/task (load! db) "demo/index"))) "a removed task cancels")))

(deftest the-shipped-task-files-are-whole-tasks-that-merge-themselves
  ;; every shipped declaration is one :whole task, none a merge task
  (let [ds (mapcat val (root/task-files))]
    (is (seq ds))
    (is (every? #(= :whole (:seon.task/kind %)) ds))
    (is (= (count ds) (count (distinct (map (juxt :seon.task/ns :seon.task/name) ds)))))))
