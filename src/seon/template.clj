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
  (seon.context/system-page), and the help page it reads on demand (seon.context/help-page). How a work or whole task is worked is a section of the help page its instructions cite whole (docs/agent.md#working-a-task), so it is a part of the agent's context drawn from the wiki and changes as the wiki does: the instructions say only the goal."
  {:design (str "Your task: turn the request below into the task the implement step works from: its plan, what the "
                "plan cites and its red proofs. You are done when an agent with only that page could make the change without "
                "exploring, and its proofs are written, registered and red for the reason the plan gives. How a task is "
                "designed, step by step: ```docs/agent.md#designing-a-task```. Then end your turn with prose.")
   :implement (str "Your task: make the change the plan describes. You are done when every proof passes, the cost proofs "
                   "included. How a task is worked, step by step: ```docs/agent.md#working-a-task```. Then end your turn with prose.")
   :merge (str "Your task: bring main's work into this branch and keep the proofs green. Main has been merged in "
               "(`my.branch/merge-main!`). You are done when nothing is unmerged and every proof passes on the combined program. "
               "How: ```docs/agent.md#merging```. Then end your turn with prose.")
   :work (str "Your task: do what the plan below asks: design it and build it. You are done when its proofs pass on your "
              "branch. How a task is worked, step by step: ```docs/agent.md#working-a-task```; main's work reaches your "
              "branch as main moves, and you resolve what conflicts: ```docs/agent.md#merging```.")
   :whole (str "Your task: do what the plan below asks, whole. You are done when its proofs pass on your branch with main's "
               "work merged in. How a task is worked, step by step: ```docs/agent.md#working-a-task```. Your status's :merge says "
               "what main changed under you and what merging would conflict on: redefine each such var for both sides "
               "(```docs/agent.md#merging```). Once :merge says :ready, your run ends and root merges your branch.")
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
  is committed. A task that starts from a request (:design, :whole) holds a search of its request's
  words (:seon.search/search-fact), which its page draws on the branch's db
  (seon.render.ui/searched): the place to start, before any citation. The query is the plan as the
  request wrote it, copied once: the agent owns it after, and changes it with a swap! on ctx (an edit of
  the plan later leaves it as the agent last set it). An error (seon.task/create-tx's)
  answers as it is."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :seon.task/declaration]]
                  [:or [:vector :any] [:map [:seon.error/type [:= :seon.task/refused]] [:seon.error/message :string]]]]}
  [db ds]
  (let [tx (task/create-tx db ds)]
    (if (contract/error? tx)
      tx
      (-> tx
          (into (task/execution-tx (mapv #(task/task-id (:seon.task/ns %) (:seon.task/name %)) ds) :running))
          (into (for [d ds :when (#{:design :whole} (:seon.task/kind d))
                      :let [id (task/task-id (:seon.task/ns d) (:seon.task/name d))]]
                  ;; of the task by its tempid, its id, as create-tx's cites name it
                  {:seon.search/id (str id "/request") :seon.search/of id
                   :seon.search/query (:seon.task/plan d)}))))))

(def stall-proofs
  "A stall task's boundary conditions, its proofs (seon.stall-proofs, read from its
  :seon.task/cause): the stall ended, its cause is recorded with its evidence, and a defect task is
  filed when the cause is a bug."
  '#{seon.stall-proofs/the-stall-ended seon.stall-proofs/its-cause-is-recorded seon.stall-proofs/a-bug-is-filed})

(defn stall
  "The task for root to deal with stall s of agent on run (seon.root's first write of a stall in a
  live run): one per agent and run (its name), in ns root, its cause pre-filled with the stall
  (s: :quiet-ms, :last-op, :pid), its proofs the boundary conditions (stall-proofs). Its model is
  \"outside\": a Claude session works it through seon_attach, and the reconciler forks no model's
  agent for it (it is written held, and attached it starts no turn loop)."
  {:malli/schema [:=> [:cat :seon.agent/id :seon.run/id [:map [:quiet-ms :int] [:pid {:optional true} [:maybe :int]]
                                                       [:last-op {:optional true} [:maybe :seon.agent/last-op]]
                                                       [:api {:optional true} :map]]]
                  :seon.task/declaration]}
  [agent run s]
  (let [pid (:pid s)
        stall (cond-> {:agent agent :run run :quiet-ms (:quiet-ms s)} pid (assoc :pid pid) (:last-op s) (assoc :last-op (:last-op s)) (:api s) (assoc :api (:api s)))]
    {:seon.task/ns 'root :seon.task/name (str "stall-" (name agent) "-" (str/replace run #"[^A-Za-z0-9]+" "-"))
     :seon.task/kind :work :seon.task/model "outside" :seon.task/proofs stall-proofs
     :seon.task/cause {:stall stall}
     :seon.task/plan (str "Agent " (name agent) " stalled on run " run ": it sent root nothing for " (:quiet-ms s)
                          " ms with a request in flight" (some->> (:last-op s) :kind name (str ", "))
                          (some->> (:last-op s) :form (str " "))
                          " (its :seon.process/stall on main, an open :stalled problem in my.root/problems).\n\n"
                          "Find why, while it is stuck, and capture the evidence"
                          (if pid (str " of its process " pid " and its children: `sample " pid " 3`, `lsof -p " pid
                                       "`, `ps -o pid,ppid,stat,etime,%cpu,command -g $(ps -o pgid= -p " pid ")`")
                              ": its process and children (sample, lsof, ps)")
                          ". Record it: (my.task/update! :seon.task/cause merge {:what \"<the cause>\" :evidence {:sample .. :lsof .. :ps ..} :bug? <true when it is a defect>}); "
                          "for a bug, file its defect task and name it (:defect \"<ns/name>\"). "
                          "Done when the stall has ended (the agent spoke or ended), the cause is recorded with evidence, "
                          "and a bug's defect is filed: its proofs, " (str/join ", " (sort stall-proofs)) ".")}))

(defn finding
  "The task a :long-form finding asks for (task seon.template/a-finding-is-a-task): row's var split under
  the bound, its behaviour unchanged, the tests reaching it its proofs; held, as every template's task
  is, until root or the owner releases it. Its name is the var's, shrink-<name>, in the var's namespace,
  one task a var. See docs/health.md#a-form-too-long."
  {:malli/schema [:=> [:cat [:map [:var :qualified-symbol] [:tokens :int] [:bound :int]] [:set :qualified-symbol]]
                  :seon.task/declaration]}
  [{:keys [var tokens bound]} tests]
  {:seon.task/ns (symbol (namespace var))
   :seon.task/name (str "shrink-" (str/replace (name var) #"[^A-Za-z0-9-]+" "-"))
   :seon.task/kind :work
   :seon.task/proofs tests
   :seon.task/plan (str "`" var "` is " tokens " tokens, over the bound of " bound " (:seon.config.shrink/form-tokens, "
                        "its :long-form problem: docs/health.md#a-form-too-long). An agent reads a function whole, so a long one "
                        "costs every reader its length. Split it by what it does into named functions, each under the bound "
                        "and each with its contract and docstring, its behaviour unchanged: every caller sees the same results. "
                        "First measure what it costs now, (my.agent/cost '" var "), and read the tests reaching it, "
                        "(my.agent/tests-reaching '" var "). Done when its proofs (the tests reaching it) are green, it is "
                        "under the bound (its :long-form row closed) and its cost per call is no more than before.")})
