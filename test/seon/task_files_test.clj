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
  ;; reads and loads onto a test branch with no error (a task file is no input of the digest: an edit
  ;; to one is found by the full run, bin/test --all)
  (let [files (root/task-files)]
    (is (seq files))
    (is (= [] (vec (for [[ns-sym ds] files :when (:seon.error/type ds)] ns-sym))) "every file reads")
    (is (not (:seon.error/type (task/load-tx (empty-db) files))) "and loads")))

(deftest no-shipped-task-is-merged-work
  ;; docs/merging.md#a-merge-says-what-it-completes: the merge
  ;; that completes a task's work says so in its message, a `Completes: ns/name` line, and removes its entry in
  ;; the same commit (git keeps the plan, test/ keeps the proofs). Each such line on main's first-parent history
  ;; names no entry left in the files. Nothing is guessed from the message's prose: a merge that names a task it
  ;; filed, or one it moved without finishing, completes nothing (twice on 2026-10-31 a merge naming a filed task
  ;; read as merged work left behind, and a list of exceptions had grown to thirteen).
  ;; It reads main as this repository holds it: the next branch's run names an entry a merge left behind
  (let [root (config/checkout)
        bodies (str/split (:out (sh/sh "git" "-C" root "log" "--first-parent" "--merges" "--format=%B%x1e" "main")) #"\x1e")
        completes (set (for [b bodies m (re-seq #"(?m)^Completes: (\S+)\s*$" b)] (second m)))
        left (for [[ns-sym ds] (root/task-files) :when (vector? ds) d ds
                   :let [k (str ns-sym "/" (:seon.task/name d))]
                   :when (completes k)]
               k)]
    (is (seq bodies) "main's merges are read")
    (is (= [] (vec left)) "each is completed work: remove its entry from resources/seon/tasks/ in the merge that says Completes: it")))

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
  ;; every shipped declaration is one :whole task, none a merge task (found by the full run when a
  ;; task file changes: the file is no input of the digest)
  (let [ds (mapcat val (filter (comp vector? val) (root/task-files)))]
    (is (seq ds))
    (is (every? #(= :whole (:seon.task/kind %)) ds))
    (is (= (count ds) (count (distinct (map (juxt :seon.task/ns :seon.task/name) ds)))))))

(deftest loads-of-the-task-files-read-and-write-under-one-lock
  ;; found on the standing root 2026-10-28: a fresh store's task load reported "Conflicting upsert". The boot's
  ;; load (under the watcher's lock) and a REPL (my.root/load-tasks! sys) read main's db and wrote against it
  ;; with nothing between them, so the second wrote what it had decided from a db the first had since
  ;; changed (the same declarations loaded from one db twice, in order, fail: Conflicting upsert). A load
  ;; takes the lock the other loaders take, so a second waits for the first's write: here the lock is held
  ;; and a load started on another thread must be blocked on it, never past it
  (let [checkout (seon.config/checkout)
        path (ts/process-store checkout)
        main (str "load-lock-" (random-uuid))
        runs (str checkout "/target/runs/" main "-runs")
        _ (.mkdirs (java.io.File. (str runs "/tasks")))
        _ (spit (str runs "/tasks/demo.edn") (pr-str [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work}]))
        root (ts/start-root! {:store path :main main :runs runs})
        ask (fn [code] (ts/value-of (:response (ts/ask root {:op :eval :agent "root" :code code}))))]
    (try
      (is (= :blocked (ask "(let [lock @#'my.root/load-lock
                                  state (promise)]
                              (locking lock
                                (let [t (Thread. ^Runnable (fn [] (my.root/load-tasks! sys)))]
                                  (.start t)
                                  ;; until it waits on the lock or has finished (no sleep: the state is read)
                                  (loop [] (let [s (str (.getState t))] (if (#{\"BLOCKED\" \"TERMINATED\"} s) (deliver state s) (do (Thread/yield) (recur)))))
                                  (case @state \"BLOCKED\" :blocked :ran-past-the-lock))))"))
          "a load started while the lock is held waits on it")
      (finally (ts/stop-root! root) (ts/drop-main! path main) (clojure.java.shell/sh "rm" "-rf" runs)))))

(deftest a-stale-task-load-never-creates-a-task-it-did-not-declare
  ;; found on the standing root 2026-10-28: 199 invalid-input errors of seon.task/task-value, a task whose
  ;; :seon.task/ns was nil. A map {:seon.task/id id ...} upserts, so a removal or revival written against a db
  ;; that lacks the task made a task entity of only that id; the write names the entity by lookup ref
  ;; instead, so a stale one is refused by the transaction and creates nothing
  (let [db0 (empty-db)
        db1 (load-text db0 file-a)
        gone (task/load-tx db1 {'demo (task/file-declarations 'demo "[]")})]
    (is (some #(= [:db/add [:seon.task/id "demo/parse"] :seon.task/execution :cancelled] %) (filter vector? gone)) "removing the file's tasks cancels them (as data)")
    (let [ghosts (fn [db] (d/q '[:find [?e ...] :where [?e :seon.task/id] (not [?e :seon.task/ns])] db))]
      (is (empty? (ghosts (d/db-with db1 gone))))
      (is (thrown? Exception (d/db-with db0 gone)) "the same removal against a db that lacks the tasks is refused")
      (is (empty? (ghosts db0))))))
