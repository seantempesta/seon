(ns seon.task-files-test
  "Task files (resources/seon/tasks/<ns>.edn): declarations as reviewable data, reconciled
  into main. A new entry is created held; an unchanged one costs nothing and keeps what main
  learned (the improved plan); a changed one updates what it states; a removed one is
  cancelled, never deleted."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.task :as task] [my.root :as root] [clojure.string :as str] [clojure.java.shell :as sh] [seon.config :as config]))

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

(def ^:private still-worked
  "Task entries whose branch merged and whose work goes on under the same entry, each with its reason."
  {"seon.render/walk" "render work still moving: later branches finish it"
   "seon.render/page" "render work still moving: later branches finish it"})

(deftest no-shipped-task-is-merged-work
  ;; task seon.task/merged-work-leaves-the-task-files: the merge that merges a task's work removes its
  ;; entry in the same commit (git keeps the plan, test/ keeps the proofs); a task Seon agents work keeps
  ;; its derived state. A worktree session's branch is named for its task, so an entry whose name is a
  ;; "Merge jolt/<name>" commit on main's first-parent history is merged work left in the files
  (let [root (config/checkout)
        merged (set (for [l (str/split-lines (:out (sh/sh "git" "-C" root "log" "--first-parent" "--format=%s" "main")))
                          :let [m (re-find #"^Merge jolt/([^\s:]+)" l)] :when m]
                      (second m)))
        left (for [[ns-sym ds] (root/task-files) :when (vector? ds) d ds
                   :let [k (str ns-sym "/" (:seon.task/name d))]
                   :when (and (merged (:seon.task/name d)) (not (still-worked k)) (not= :merge (:seon.task/kind d)))]
               k)]
    (is (seq merged) "main's merges are read")
    (is (= [] (vec left)) "each is merged work: remove its entry from resources/seon/tasks/ (still-worked names the exceptions)")))

(deftest a-file-task-reaches-main-through-its-merge-task
  ;; task seon.task/a-file-task-reaches-main: a work task loaded from its task file is
  ;; followed by its merge task, as seon.template/with-merge gives e2e's tasks, so a done
  ;; task's change reaches main (v1: a single task is followed by a merge task)
  (let [dir (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-task-files-" (random-uuid))) (.mkdirs))
        _ (spit (java.io.File. dir "demo.edn") file-a)
        load! (fn [db] (d/db-with db (task/load-tx db (root/task-files (str dir)))))
        db (load! (empty-db))
        merge-of (fn [db id] (d/q '[:find ?m . :in $ ?w :where [?t :seon.task/id ?w] [?mt :seon.task/needs ?t] [?mt :seon.task/kind :merge] [?mt :seon.task/id ?m]] db id))]
    (is (some? (merge-of db "demo/parse")) "parse's merge task needs parse")
    (is (some? (merge-of db "demo/index")) "and index's")
    (is (= #{"demo/parse-merge"} (:seon.task/needs (task/task db "demo/index"))) "a need on a work task is a need on its merge")
    (is (= [] (task/load-tx db (root/task-files (str dir)))) "an unchanged file writes nothing")
    (spit (java.io.File. dir "demo.edn") "[{:seon.task/name \"parse\" :seon.task/plan \"Parse the rows.\" :seon.task/proofs #{demo-test/parse-test}}]")
    (let [db (load! db)]
      (is (= :cancelled (:seon.task/execution (task/task db "demo/index-merge"))) "a removed work task cancels its merge task")
      (is (= :held (:seon.task/execution (task/task db "demo/parse-merge")))))))
