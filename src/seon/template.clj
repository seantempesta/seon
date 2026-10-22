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
  - `request`: a request (one declaration: what the user asked) becomes design ->
    implement -> merge on design's branch, so each step holds the last one's work, and
    `request-tx` writes the whole linked set released in one transaction (owner,
    2026-10-22): the reconciler forks only from a committed main, so every agent of the
    set starts from the same graph.
  A failed attempt attaches no tasks: its task is held for root (seon.reconcile/hold-tx)."
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
  "What each step's agent does, by task kind (its own work: instructions never flow). How
  every agent moves around the system is the help page every agent reads first
  (seon.context/help-page)."
  {:merge (str "Main has been merged into your branch (`my.branch/merge-main!`). Resolve each unmerged entry: re-defn the var "
               "with the content both sides need, or keep one side with `my.branch/resolve!`: ``(my.branch/resolve! 'ns/var :theirs)``; `(my.branch/unmerged)` lists them. "
               "Make every proof pass on the combined program. Record what you learn in the plan, then wait.")
   :design (str "Turn the plan, however rough (it may be one line from a user: \"store my workouts\"), into a plan an implement step can "
                "follow without exploring. Explore first: find the owners of what it touches, their callers, the schemas on the data's "
                "path and the library functions that already do the work, and try the idea at the REPL. If an earlier attempt failed, "
                "start from its evidence. Leave on this branch: (1) the plan rewritten with `my.task/update!`: what must become true, each owner, "
                "library function and schema it touches cited as a backticked fully qualified name, and what you found; (2) each new or changed "
                "schema registered; (3) each function's defn with its contract and a stub body; (4) the proofs, written and red, asserting "
                "the behaviour on data; (5) for every function whose input grows, a cost proof with `seon.cost/proof`: ``(deftest f-is-one-pass (seon.cost/proof "
                "{:of 'ns/f :input 'ns-test/input :n \"what N counts\" :work \"the least work the data needs\" :growth :linear "
                ":ops {...} :sizes [1000 10000 100000]}))``, its :ops in the units of `seon.cost/units`, derived from what the work must do, never from a run. "
                "Do not write the bodies: that is the implement step's. Then wait.")
   :implement (str "Implement what the plan describes on this branch until the proofs pass, the cost proofs included: do the "
                   "work their :work states and no more, at the owners the plan cites. Never loosen a contract or a proof to make it pass; "
                   "if the plan is wrong, fail the task with the form that shows it (the help page's Ending). Record what you learn in the plan, then wait.")
   :test (str "Run the proofs and the tests of what you changed. For any behaviour the evidence shows broke, add a deftest in the "
              "proofs namespace. Make everything pass, record what you learn in the plan, then wait.")})

(defn- merge-decl
  "Merge task nm (merge-name of its work's) of work declaration d (id wid), on d's branch,
  needing it."
  {:malli/schema [:=> [:cat :seon.task/declaration :seon.task/id :seon.task/name] :seon.task/declaration]}
  [d wid nm]
  (cond-> {:seon.task/ns (:seon.task/ns d) :seon.task/name nm
           :seon.task/kind :merge :seon.task/plan (merge-plan wid)
           :seon.task/instructions (instructions :merge)
           :seon.task/needs #{wid}}
    (:seon.task/branch d) (assoc :seon.task/branch (:seon.task/branch d))
    (:seon.task/model d) (assoc :seon.task/model (:seon.task/model d))
    (:seon.task/config d) (assoc :seon.task/config (:seon.task/config d))))

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
                         d (cond-> (update d :seon.task/branch #(or % (task/default-branch wid)))
                             (seq (:seon.task/needs d)) (update :seon.task/needs #(set (map (fn [n] (if (work n) (merge-of n) n)) %))))]
                     [d (merge-decl d wid (merge-name (:seon.task/name d)))]))
                 decls))))

(defn request
  "Request decl (what the user asked: its plan, perhaps one line, and any proofs) as the
  linked tasks that work it: decl's name as the design step, then <name>-implement needing
  it, then <name>-merge needing that, all on the design step's branch (so implement holds
  design's schemas, stubs and red proofs, and merge holds both). The request's proofs are
  design's and accumulate along :needs (seon.task/accumulate)."
  {:malli/schema [:=> [:cat :seon.task/declaration] [:vector :seon.task/declaration]]}
  [decl]
  (let [tns (:seon.task/ns decl)
        did (task/task-id tns (:seon.task/name decl))
        design (-> decl (assoc :seon.task/kind :design :seon.task/instructions (instructions :design))
                   (update :seon.task/branch #(or % (task/default-branch did))))
        implement (cond-> (-> (select-keys design [:seon.task/ns :seon.task/branch :seon.task/model :seon.task/config])
                              (assoc :seon.task/name (str (:seon.task/name decl) "-implement") :seon.task/kind :implement
                                     :seon.task/plan "" :seon.task/instructions (instructions :implement)
                                     :seon.task/needs #{did})))]
    [design implement (merge-decl implement (task/task-id tns (:seon.task/name implement)) (merge-name (:seon.task/name decl)))]))

(defn request-tx
  "The one transaction that writes request decl's linked tasks (request) on main, released:
  the reconciler sees the whole set at once and forks its first agent only once it is
  committed. A refusal (seon.task/create-tx's) answers as it is."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/declaration]
                  [:or [:vector :any] [:map [:seon.error/type [:= :seon.task/refused]] [:seon.error/message :string]]]]}
  [db decl]
  (let [ds (request decl)
        tx (task/create-tx db ds)]
    (if (:seon.error/type tx)
      tx
      (into tx (task/execution-tx (mapv #(task/task-id (:seon.task/ns %) (:seon.task/name %)) ds) :running)))))
