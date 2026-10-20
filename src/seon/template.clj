(ns seon.template
  "Task templates (owner, 2026-10-20: tasks chain through `:needs`; each step is a new
  agent with improved context).

  Every kind (work, design, implement, test, merge) is a default template that overrides
  a few fields; dependencies are :needs refs only, and a task's plan and proofs accumulate
  along them (seon.task/tasks: :seon.task/accumulated), so each step refines.
  - `with-merge`: each declared work task is followed by its merge task, an ordinary task
    from the merge template (kind :merge, on the work task's branch, needing it, its plan
    and proofs inherited). A task that needs another's work needs its merge, so it starts
    from a main that holds that work.
  - `attach-tx`: a failed merge attaches design -> implement -> test -> merge on a fresh
    branch from main. Each starts from the failed task's improved plan and its measured
    evidence (the conflicts with base, ours and theirs; the red proofs). The failed merge
    is cancelled, and every task that needed it needs the new merge."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.task :as task] [clojure.string :as str]))

(defn merge-name
  "The name of work task nm's merge task."
  {:malli/schema [:=> [:cat :seon.task/name] :seon.task/name]}
  [nm] (str nm "-merge"))

(defn- merge-plan
  {:malli/schema [:=> [:cat :seon.task/id] :string]}
  [work-id]
  (str "Merge the work of task " work-id " (on this branch) into main: merge main into the branch, resolve every conflict "
       "at the REPL, and make the proofs pass on the combined program."))

(def instructions
  "What each step's agent does, by task kind (its own work: instructions never flow)."
  {:merge (str "Main has been merged into your branch (my.branch/merge-main!). Resolve each unmerged entry: re-defn the var "
               "with the content both sides need, or (my.branch/resolve! 'ns/var :theirs|:ours). Make every proof pass on the "
               "combined program. Improve the plan with what you learn (my.task/update! :seon.task/plan str ...).")
   :design (str "A merge failed: main changed under this task's design. Read the evidence (conflicts: base, ours, theirs; red "
                "proofs). Decide how the change fits main as it is now. Write the interface: each function's defn with its "
                "contract (a stub body is fine), and improve the plan (my.task/update! :seon.task/plan str ...) so the "
                "implement step knows exactly what to do. For every function whose input grows, add a cost proof first: "
                "(deftest f-is-one-pass (seon.cost/proof {:of 'ns/f :input 'ns-test/input :n \"what N counts\" :work \"the least "
                "work the data needs\" :growth :linear :ops {...units from seon.cost/units...} :sizes [1000 10000 100000]})). "
                "Derive it from what the work must do, never from a run. Then (my.turn/wait).")
   :implement (str "Implement what the plan describes on this branch until the proofs pass, the cost proofs included: do the "
                   "work their :work states and no more. Improve the plan with what you learn.")
   :test (str "Run the proofs and every program test. For any behaviour the evidence shows broke, add a deftest in the "
              "proofs namespace. Make everything pass.")})

(defn with-merge
  "decls (task declarations of work) followed by their merge tasks; needs on a work task
  of decls become needs on its merge."
  {:malli/schema [:=> [:cat [:vector :seon.task/declaration]] [:vector :seon.task/declaration]]}
  [decls]
  (let [id #(task/task-id (:seon.task/ns %) (:seon.task/name %))
        work (set (map id decls))
        merge-of (fn [wid] (let [[ns nm] (str/split wid #"/" 2)] (str ns "/" (merge-name nm))))]
    (vec (mapcat (fn [d]
                   (let [wid (id d)
                         branch (or (:seon.task/branch d) (task/default-branch wid))
                         d (cond-> (assoc d :seon.task/branch branch)
                             (seq (:seon.task/needs d)) (update :seon.task/needs #(set (map (fn [n] (if (work n) (merge-of n) n)) %))))]
                     [d (cond-> {:seon.task/ns (:seon.task/ns d) :seon.task/name (merge-name (:seon.task/name d))
                                 :seon.task/kind :merge :seon.task/plan (merge-plan wid)
                                 :seon.task/instructions (instructions :merge)
                                 :seon.task/needs #{wid} :seon.task/branch branch}
                          (:seon.task/model d) (assoc :seon.task/model (:seon.task/model d))
                          (:seon.task/config d) (assoc :seon.task/config (:seon.task/config d)))]))
                 decls))))

(defn evidence
  "The measured evidence of failed attempt a: its conflicts (read from the branch by root's
  judge) and the proofs not passing in root's own test run."
  {:malli/schema [:=> [:cat :seon.attempt/attempt] :seon.task/evidence]}
  [a]
  {:from (:seon.attempt/id a)
   :conflicts (vec (:seon.attempt/conflicts a []))
   :unmerged (vec (:seon.attempt/unmerged a []))
   :red (vec (sort-by str (for [[t s] (:seon.attempt/results a) :when (not= :pass s)] t)))})

(defn attach-tx
  "The tasks a failed merge attaches, in one transaction: design -> implement -> test ->
  merge, released, on one fresh branch; the failed merge task cancelled; its dependents
  moved to the new merge; the failed attempt closed. db: main."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:attempt :seon.attempt/id] [:task :seon.task/id]]] [:vector :any]]}
  [db {:keys [attempt task]}]
  (let [m (task/task db task)
        a (task/attempt db attempt)
        g (inc (:seon.task/generation m 0))
        nm (:seon.task/name m)
        stem (str (if (str/ends-with? nm "-merge") (subs nm 0 (- (count nm) 6)) nm) "-r" g)
        tns (:seon.task/ns m)
        tid #(task/task-id tns (str stem "-" %))
        branch (task/default-branch (task/task-id tns stem))
        ev (evidence a)
        ;; the chain starts from the failed task's accumulated plan and proofs (design
        ;; holds them as its own); each later step inherits them along :needs and adds its own
        step (fn [kind needs]
               (cond-> {:seon.task/ns tns :seon.task/name (str stem "-" (name kind)) :seon.task/kind kind
                        :seon.task/plan "" :seon.task/instructions (instructions kind)
                        :seon.task/branch branch :seon.task/evidence ev :seon.task/generation g}
                 (nil? needs) (assoc :seon.task/plan (:plan (:seon.task/accumulated m))
                                     :seon.task/proofs (:proofs (:seon.task/accumulated m)))
                 needs (assoc :seon.task/needs #{needs})
                 (:seon.task/model m) (assoc :seon.task/model (:seon.task/model m))))
        decls [(step :design nil) (step :implement (tid "design")) (step :test (tid "implement")) (step :merge (tid "test"))]
        new-merge (tid "merge")
        dependents (for [[id t] (task/tasks db) :when ((:seon.task/needs t) task)] id)]
    (vec (concat (task/create-tx db decls)
                 (task/execution-tx (mapv #(task/task-id tns (:seon.task/name %)) decls) :running)
                 [{:seon.task/id task :seon.task/execution :cancelled
                   :seon.task/problem (str "merge failed (attempt " attempt "); replaced by " new-merge)}]
                 (mapcat (fn [id] [[:db/retract [:seon.task/id id] :seon.task/needs [:seon.task/id task]]
                                   [:db/add [:seon.task/id id] :seon.task/needs new-merge]])
                         dependents)
                 (task/status-tx db attempt (:seon.attempt/status a) :closed {})))))
