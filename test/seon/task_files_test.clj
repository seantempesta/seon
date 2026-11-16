(ns seon.task-files-test
  "Task files (resources/seon/tasks/<ns>.edn): declarations as reviewable data, reconciled
  into main. A new entry is created held; an unchanged one costs nothing and keeps what main
  learned (the improved plan); a changed one updates what it states; a removed one is
  cancelled, never deleted."
  (:require [seon.lib] [seon.test-store :as ts] [seon.agent-test :as at] [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.task :as task] [my.root :as root] [clojure.string :as str] [clojure.java.shell :as sh] [seon.config :as config]
            [seon.ref :as ref] [seon.doc :as doc] [seon.system] [test-runner :as runner]))

(defn- empty-db [] (ts/test-db))

(defn- shipped-task-files
  "The task files the checkout ships (seon.system/task-files of its resources/seon/tasks), never the running
  root's :seon.config.root/tasks-dir, which a root may point elsewhere."
  [] (seon.system/task-files (str (config/checkout) "/resources/seon/tasks")))

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

(deftest ^{:seon.test/estimate {:ticks 1000000}} a-task-file-is-one-vector-of-declarations-named-by-its-namespace
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

(deftest a-file-task-merges-itself
  ;; a task loaded from a
  ;; task file is one :whole task (seon.template/request), so no merge task is written and a
  ;; need on another file's task is a need on it
  (let [dir (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-task-files-" (random-uuid))) (.mkdirs))
        _ (spit (java.io.File. dir "demo.edn") file-a)
        load! (fn [db] (d/db-with db (task/load-tx db (seon.system/task-files (str dir)))))
        db (load! (empty-db))]
    (is (= :whole (:seon.task/kind (task/task db "demo/parse"))) "parse works and merges itself")
    (is (nil? (task/task db "demo/parse-merge")) "and has no merge task")
    (is (= #{"demo/parse"} (:seon.task/needs (task/task db "demo/index"))) "a need stays on the task")
    (is (= [] (task/load-tx db (seon.system/task-files (str dir)))) "an unchanged file writes nothing")
    (spit (java.io.File. dir "demo.edn") "[{:seon.task/name \"parse\" :seon.task/plan \"Parse the rows.\" :seon.task/proofs #{demo-test/parse-test}}]")
    (is (= :cancelled (:seon.task/execution (task/task (load! db) "demo/index"))) "a removed task cancels")))

(deftest ^{:seon.test/estimate {:ticks 12000000}} the-shipped-task-files-are-whole-tasks-that-merge-themselves
  ;; every shipped declaration is one :whole task, none a merge task (found by the full run when a
  ;; task file changes: the file is no input of the digest). The claim is about every file, so it reads them all through
  ;; seon.system/task-files (which derives the kind, never written in a file): the floor is the read of 1.7 MB of EDN, 7.5 M
  ;; ticks measured, plus the contract check and the derivation of ~680 declarations, 10.3 M ticks measured in all.
  ;; Estimate 12 M: the floor and a margin; not under 50 ms, as it reads the whole of the shipped data.
  (let [ds (mapcat val (filter (comp vector? val) (shipped-task-files)))]
    (is (seq ds))
    (is (every? #(= :whole (:seon.task/kind %)) ds))
    (is (= (count ds) (count (distinct (map (juxt :seon.task/ns :seon.task/name) ds)))))))

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

;; T1 for the refusals (they are decided before any write, so no root is asked: sys is never read) and T2 for the idempotent
;; load (it transacts on main, through the shared root). Estimate: 3 small file reads and 3 EDN reads, ~1 M ticks; the load is
;; one agent on the shared root, 2 loads and 2 counts in one eval.
(defn- example-files
  "Make a directory under target/runs holding the example session's file and three that are no example session's: {:runs :file :key :id :missing :unreadable :bad}."
  []
  (let [checkout (seon.config/checkout)
        id (seon.lib/unique-id)
        runs (str (System/getProperty "java.io.tmpdir") "/load-example-" id)
        _ (.mkdirs (java.io.File. runs))
        file (str runs "/records.edn")
        key (str "example/" id)]
    (spit file (pr-str [[{:seon.agent/key key :seon.agent/status :ended :seon.agent/description "Make h."}]
                        [{:seon.eval/id (str key "/s1") :seon.eval/by {:seon.agent/key "src"} :seon.eval/agent [:seon.agent/key key]
                          :seon.eval/code "(+ 1 2)" :seon.eval/response {:handle (symbol (str "result/example-" id "-s1")) :value 3} :seon.eval/call-id (str "example-" id "-call_1")}]]))
    (spit (str runs "/bad.edn") "[[{:seon.agent/key \"someone\"}]]")
    (spit (str runs "/unreadable.edn") "[[{:seon.agent/key")
    {:runs runs :file file :key key :id id :missing (str runs "/nothing-here.edn") :unreadable (str runs "/unreadable.edn") :bad (str runs "/bad.edn")}))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-missing-unreadable-or-misshapen-example-file-is-an-error-and-writes-nothing
  ;; my.root/load-example!: a file that is not there, does not read or is not a vector of transactions whose first writes
  ;; an example agent is the :seon.system/example-refused error, answered by my.root/example-txs, the edge load-example! reads first, before root is asked for anything (T1: no root)
  (let [{:keys [runs missing unreadable bad]} (example-files)]
    (try
      (let [refused (fn [f] (let [r (my.root/example-txs f)] [(:seon.error/type r) (:path r) (:seon.error/message r)]))
            [type p message] (refused missing)]
        (is (= [:seon.system/example-refused missing] [type p]))
        (is (str/includes? message "not found"))
        (is (str/includes? (nth (refused unreadable) 2) "not readable EDN"))
        (is (str/includes? (nth (refused bad) 2) "not a non-empty vector of transactions")))
      (finally (seon.lib/remove-tree! runs)))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} loading-an-example-twice-changes-nothing
  ;; my.root/load-example!: an example session's file (seon.ctx.session/session-tx's transactions, written with pr-str)
  ;; is transacted once; a second load of it writes nothing (main's db does not move). In-process on a branch of the test
  ;; store, no root: the writer (seon.system/transact-main!) is stood in for by a d/transact on the sys's conn, so what is
  ;; proved is load-example!'s own decision of what main already holds. Estimate: 2 small EDN reads, a branch, 2 transacts
  ;; of ~3 datoms and 6 eid seeks, ~1 M ticks (0.69 M measured). See docs/tasks.md#a-task-names-examples.
  (let [{:keys [runs file key]} (example-files)]
    (try
      (ts/test-branch!
       (fn [_ conn]
         (let [sys {:graph {} :ext (clojure.core.async/chan) :conn conn}
               writes (atom 0)
               before (:max-tx @conn)]
           (with-redefs [seon.system/transact-main! (fn [sys tx] (swap! writes inc) (doto (promise) (deliver (assoc (d/transact (:conn sys) tx) :ok true))))]
             (let [a (my.root/load-example! sys file)
                   t1 (:max-tx @conn)
                   b (my.root/load-example! sys file)]
               (is (= {:first 2 :second {:ok true :changed 0 :batches 0} :moved false :writes 2 :evals 1 :agents 1}
                      {:first (:batches a) :second (select-keys b [:ok :changed :batches]) :moved (not= t1 (:max-tx @conn)) :writes @writes
                       :evals (count (d/q '[:find [?e ...] :in $ ?id :where [?e :seon.eval/id ?id]] @conn (str key "/s1")))
                       :agents (count (d/q '[:find [?e ...] :in $ ?k :where [?e :seon.agent/key ?k]] @conn key))})
                   "the first load writes its two transactions; the second writes nothing")
               (is (not= before t1) "and the first did move main"))))))
      (finally (seon.lib/remove-tree! runs)))))

;; See docs/tasks.md#a-request-is-one-transaction
(deftest a-task-from-a-file-holds-its-plans-search-as-a-requested-one-does
  ;; a task made from a request held a search of its plan's words, the first context's ranked matches over code and wiki; a task
  ;; declared in a file held none (0 searches on a root of 331 file tasks, 2026-11-10), so its agent found the code by grep. Every
  ;; task is made by seon.task/create-tx, so the search is made there, once, the same for both
  (let [db (load-text (empty-db) file-a)
        search-of (fn [db id] (d/pull db [:seon.search/query] [:seon.search/id (str id "/request")]))]
    (is (= "Parse the rows." (:seon.search/query (search-of db "demo/parse"))) "a file task holds its plan's search")
    (is (= "Index the rows." (:seon.search/query (search-of db "demo/index"))))
    (testing "a task held before every task got one gains it on the next load, once"
      (let [old (d/db-with db [[:db/retractEntity [:seon.search/id "demo/parse/request"]]])
            again (load-text old file-a)]
        (is (= "Parse the rows." (:seon.search/query (search-of again "demo/parse"))))
        (is (= [] (task/load-tx again {'demo (task/file-declarations 'demo file-a)})) "and a load with nothing new writes nothing")))))
