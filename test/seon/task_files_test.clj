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

(def ^:private still-worked
  "Task entries whose branch merged and whose work goes on under the same entry, each with its reason."
  {"seon.reconcile/one-observe-descends-once" "merged as the stored-shape measurement only; the one multi-prefix datoms-diff walk its plan asks for is still a per-attribute loop (low priority in its plan)"
   "seon.render/walk" "render work still moving: later branches finish it"
   "seon.render/page" "render work still moving: later branches finish it"
   "seon.db/a-roots-heap-costs-what-it-reads" "the merge that names it filed it (jolt/test-audit, 3eface7bab); its holder census and fix are still to do"
   "seon.db/opening-a-store-costs-what-it-opens" "merged its install-missing! half (jolt/test-audit); ids!'s walk of every branch head is Datahike's set-ids!, still to do"})

(deftest no-shipped-task-is-merged-work
  ;; task seon.task/merged-work-leaves-the-task-files: the merge that merges a task's work removes its
  ;; entry in the same commit (git keeps the plan, test/ keeps the proofs); a task Seon agents work keeps
  ;; its derived state. An entry is merged work left in the files when main's first-parent history holds
  ;; a "Merge jolt/<name>" or "Land jolt/<name>" commit for its name (a worktree session's branch is named
  ;; for its task), or a Merge/Land commit whose subject or body names its qualified name <ns>/<name>
  ;; (a branch named otherwise, e.g. jolt/fileref for seon.ref/files-namespaces-and-symbols-are-plain-markdown)
  ;; when the entry was already in the files before that merge: a merge that files a task names it too
  ;; (3eface7bab filed seon.db/a-roots-heap-costs-what-it-reads).
  ;; It reads main as this repository holds it: the next branch's run names an entry a merge left behind
  ;; (the merge's own run precedes its commit)
  (let [root (config/checkout)
        commits (for [rec (str/split (:out (sh/sh "git" "-C" root "log" "--first-parent" "--format=%H%x1f%s%x1f%b%x1e" "main")) #"\x1e")
                      :let [[sha subject body] (str/split (str/trim rec) #"\x1f" 3)
                            subject (str/trim (str subject))]
                      :when (re-find #"^(?:Merge|Land) " subject)]
                  {:sha sha :subject subject :text (str subject "\n" body)})
        merged (set (for [{:keys [subject]} commits :let [m (re-find #"^(?:Merge|Land) jolt/([^\s:]+)" subject)] :when m] (second m)))
        held-before? (fn [{:keys [sha]} ns-sym tname]
                       (str/includes? (:out (sh/sh "git" "-C" root "show" (str sha "^1:resources/seon/tasks/" ns-sym ".edn")))
                                      (pr-str tname)))
        left (for [[ns-sym ds] (root/task-files) :when (vector? ds) d ds
                   :let [k (str ns-sym "/" (:seon.task/name d))]
                   :when (and (or (merged (:seon.task/name d))
                                  (some #(and (str/includes? (:text %) k) (held-before? % ns-sym (:seon.task/name d))) commits))
                              (not (still-worked k)) (not= :merge (:seon.task/kind d)))]
               k)]
    (is (seq merged) "main's merges are read")
    (is (= [] (vec left)) "each is merged work: remove its entry from resources/seon/tasks/ (still-worked names the exceptions)")))

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
