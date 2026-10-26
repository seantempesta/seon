(ns seon.template
  "Task templates (owner: tasks chain through `:needs`; each step is a new
  agent with improved context).

  Every kind (work, design, implement, test, merge, whole) is a default template that overrides
  a few fields; dependencies are :needs refs only, and a task's plan and proofs accumulate
  along them (seon.task/tasks: :seon.task/accumulated), so each step refines.
  - `with-merge`: each declared work task is followed by its merge task, an ordinary task
    from the merge template (kind :merge, on the work task's branch, needing it, its plan
    and proofs inherited). A task that needs another's work needs its merge, so it starts
    from a main that holds that work.
  - a request (one declaration: what the user asked) becomes, by default (`my.root/request!`),
    one :whole task that designs, builds and merges itself (`request`); for experiments,
    `design-build` makes its design-build task (kind :work: the red proof, then green on its
    branch) followed by its merge task (`with-merge`), and `stages` design -> implement -> merge
    on design's branch. `request-tx` writes any of these released in one transaction (DECISIONS, A
    request's whole linked task set is one transaction on main, before any agent forks): the
    reconciler forks only from a committed main, so every agent of the
    set starts from the same graph.
  A failed run attaches no tasks: its task is held for root (seon.reconcile/hold-tx)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.task :as task] [clojure.string :as str]))

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
  "What each step's agent does, by task kind (its own work: instructions never flow): its goal,
  when it is done and by what means, short, the standard format's stage descriptions (seon.judge's). How every agent works at the REPL is the system prompt every agent reads first
  (seon.context/system-page), and the help page it reads on demand (seon.context/help-page)."
  {:design (str "Your task: turn the plan below into a plan the implement "
                "step can follow without exploring. You are done when the plan names each function to change, cited with three "
                "backticks, and the proofs that will show the change are written, registered "
                "and red for the reason the plan gives. You do it at the REPL, proof first: reproduce what the plan says is wrong "
                "as a deftest, evaluated in its test namespace (``(in-ns 'ns.x-test)``, then the deftest: what you evaluate is "
                "your branch's program, so you write no file), register it (``(my.task/update! :seon.task/proofs conj "
                "'ns.x-test/a-proof)``) and run it red. Then read what the change touches (``(clojure.repl/source ns/f)``; "
                "``(clojure.repl/source ns)`` lists its every definition; ``(my.agent/callers 'ns/f)``). Once the proof is red, write the plan: "
                "``(my.task/update! :seon.task/plan str \"\\n\\nWhat the implement step changes: \" \"<each function and what it does then>\")``, "
                "with each new function's defn, its contract and a stub body, each new schema registered, and a cost proof "
                "(``seon.cost/proof``) for a function whose input grows. Do not try the fix: the implement step makes it, from "
                "your red proof. Then end your turn with prose.")
   :implement (str "Your task: make the change the plan describes. You are done when every proof passes, the cost proofs "
                   "included. You do it by redefining the functions the plan cites, in their own namespaces, keeping their "
                   "contracts, and running the proofs (`(my.task/status)` shows how) until they are green. Never loosen a "
                   "contract or a proof to make it pass: if the plan is wrong, fail the task with the evidence "
                   "(``(my.task/fail! \"why\" evidence)``). Then end your turn with prose.")
   :merge (str "Your task: bring main's work into this branch and keep the proofs green. Main has been merged in "
               "(`my.branch/merge-main!`); `(my.branch/unmerged)` lists what both sides changed. You are done when nothing is "
               "unmerged and every proof passes on the combined program. You do it by redefining each unmerged var with what "
               "both sides need, or keeping one side (``(my.branch/resolve! 'ns/var :theirs)``), and running the proofs. Then end "
               "your turn with prose.")
   :work (str "Your task: do what the plan below asks: design it and build it. You are done when its proofs pass on your "
              "branch. First reproduce what the plan says is wrong in one eval and read its output: if the output is already "
              "right, the premise is false, so fail the task with that output at once (``(my.task/fail! \"why\" evidence)``). "
              "Once the output is wrong, stop reading: write it as a deftest in its test namespace (``(in-ns 'ns.x-test)``, then "
              "the deftest: what you evaluate is your branch's program, so you write no file), its input built as the function's "
              "existing tests build theirs (their helpers and fixtures, never the live db, vector literals where order matters), "
              "register it (``(my.task/update! :seon.task/proofs conj 'ns.x-test/a-proof)``) and run it red. Then make it pass "
              "by redefining the functions it needs, in their own namespaces, keeping their contracts, with a cost proof "
              "(``seon.cost/proof``) for a function whose input grows. Never loosen a contract or a proof to make it pass. Its "
              "merge task brings main's work in after you.")
   :whole (str "Your task: do what the plan below asks, whole. You are done when its proofs pass on your branch with main's "
               "work merged in. First reproduce what the plan says is wrong in one eval and read its output: if the output is "
               "already right, the premise is false, so fail the task with that output at once "
               "(``(my.task/fail! \"why\" evidence)``). Once the output is wrong, stop reading: write it as a deftest in its test "
               "namespace (``(in-ns 'ns.x-test)``, then the deftest: you write no file), its input built as the function's existing "
               "tests build theirs (their helpers and fixtures, never the live db, vector literals where order matters), register "
               "it (``(my.task/update! :seon.task/proofs conj 'ns.x-test/a-proof)``) and run it red. Then make it pass by "
               "redefining the functions it needs, in their own namespaces, keeping their contracts, with a cost proof "
               "(``seon.cost/proof``) for a function whose input grows. Never loosen a contract or a proof to make it pass. Your "
               "status's :merge says what main changed under you and what merging it would conflict on: redefine each such "
               "var with what both sides need. Once :merge says :ready, your run ends and root merges your branch.")
   :test (str "Your task: test what the plan changed. You are done when the proofs and the tests of what changed pass, and "
              "each behaviour that broke has a deftest of its own. You do it by running them (`(my.agent/prove)`), writing a "
              "deftest in the proofs' namespace for each break, and fixing the code until all pass. Then end your turn with prose.")})

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
  of decls become needs on its merge. (A generated input holds at most 4 declarations: each is
  a large nested map, and up to ~100 of them made this one contract's mg/check 78 s of the
  step's 136; 4 already cover a need on work, on a merge and outside.)"
  {:malli/schema [:=> [:cat [:vector {:gen/max 4} :seon.task/declaration]] [:vector :seon.task/declaration]]}
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

(defn design-build
  "Request decl (what the user asked: its plan, perhaps one line, and any proofs) as its
  design-build task (decl, kind :work, the :work instructions: one agent writes the red proof
  and makes it green on its branch, and does not merge), followed by its merge task
  (with-merge), which merges main into the branch, keeps the proofs green and merges the branch
  into main. An experiment's choice; `request` is the default (my.root/request!)."
  {:malli/schema [:=> [:cat :seon.task/declaration] [:vector :seon.task/declaration]]}
  [decl]
  (with-merge [(assoc decl :seon.task/kind :work :seon.task/instructions (instructions :work))]))

(defn request
  "Request decl (what the user asked: its plan, perhaps one line, and any proofs) as the task
  that works it, the default (my.root/request!; owner: \"keep experimenting with having the agent
  do everything in one task\"): one task of kind :whole on its own branch, which one agent works
  through: it writes the red proof, makes it green, merges main and keeps it green, and its green
  branch merges into main itself (seon.task/merges?). `design-build` and `stages` are the
  experiments' choices."
  {:malli/schema [:=> [:cat :seon.task/declaration] [:vector :seon.task/declaration]]}
  [decl]
  [(-> decl (assoc :seon.task/kind :whole :seon.task/instructions (instructions :whole))
       (update :seon.task/branch #(or % (task/default-branch (task/task-id (:seon.task/ns decl) (:seon.task/name decl))))))])

(defn stages
  "Request decl as staged tasks, for experiments, each stage an agent of its own.
  Decl's name is the design step, then <name>-implement needing it, then <name>-merge needing
  that, all on the design step's branch (so implement holds
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
  "The one transaction that writes a request's tasks ds (request's, design-build's or stages') on main,
  released: the reconciler sees the whole set at once and forks its first agent only once it
  is committed. An error (seon.task/create-tx's) answers as it is."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :seon.task/declaration]]
                  [:or [:vector :any] [:map [:seon.error/type [:= :seon.task/refused]] [:seon.error/message :string]]]]}
  [db ds]
  (let [tx (task/create-tx db ds)]
    (if (contract/error? tx)
      tx
      (into tx (task/execution-tx (mapv #(task/task-id (:seon.task/ns %) (:seon.task/name %)) ds) :running)))))
