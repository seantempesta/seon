(ns my.root
  "Root's verbs on tasks (lanes R1 and M1's proto.agents.root): write desired state on main;
  the reconciler proc does the rest. Each takes root's system (seon.root/start!)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.root :as root] [seon.task :as task] [seon.problems :as problems] [clojure.java.io :as io]))

(defn create!
  "Write task declarations as held tasks. Answers the writer's answer, or the refusal."
  {:malli/schema [:=> [:cat :seon.root/sys [:vector :seon.task/declaration]] :map]}
  [sys decls]
  (let [tx (task/create-tx @(:conn sys) decls)]
    (if (:seon.error/type tx) tx @(root/transact-main! sys tx))))

(defn run!
  "Release tasks ids: the one release (nothing autoruns)."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids] @(root/transact-main! sys (task/execution-tx ids :running)))

(defn cancel!
  "Cancel tasks ids: a live attempt is killed; tasks that need them stay blocked."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids] @(root/transact-main! sys (task/execution-tx ids :cancelled)))

(defn task-files
  "The task files under dir (resources/seon/tasks in this checkout by default), read:
  {owning-ns declarations or refusal}. A file's name is its owning namespace."
  {:malli/schema [:function [:=> [:cat] [:map-of :symbol :any]] [:=> [:cat :string] [:map-of :symbol :any]]]}
  ([] (task-files (str (or (System/getenv "JOLT_PWD") ".") "/resources/seon/tasks")))
  ([dir]
   (into (sorted-map)
         (for [f (sort-by #(.getName %) (or (seq (.listFiles (io/file dir))) []))
               :let [n (.getName f)] :when (.endsWith n ".edn")
               :let [ns-sym (symbol (subs n 0 (- (count n) 4)))]]
           [ns-sym (task/file-declarations ns-sym (slurp f))]))))

(defn load-tasks!
  "Reconcile the task files into main (seon.task/load-tx): new entries created held, changed
  ones updated, removed ones cancelled; unchanged ones write nothing. Answers the writer's
  answer with the counts, or the refusal. Releasing stays run!'s."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys :string] :map]]}
  ([sys] (load-tasks! sys nil))
  ([sys dir]
   (let [files (if dir (task-files dir) (task-files))
         tx (task/load-tx @(:conn sys) files)]
     (cond (:seon.error/type tx) tx
           (empty? tx) {:ok true :files (count files) :changed 0}
           :else (assoc @(root/transact-main! sys tx) :files (count files) :datoms (count tx))))))

(defn problems
  "Every problem recorded on main, by class (seon.problems/report): recorded errors counted
  by signature (:platform :eval :agent :effect) and attempts' red proofs, cost verdicts,
  merges, deaths, errors and waste, each row with its producer, owning namespace, evidence
  as data, reproducing proofs and the tasks already answering it. Reads main only; costs
  the problems, not the program."
  {:malli/schema [:=> [:cat :seon.root/sys] :seon.problems/report]}
  [sys] (problems/report @(:conn sys)))
