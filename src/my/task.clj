(ns my.task
  "The agent's task functions (Seon's my.task): read its task, improve it, or mark it failed.
  Writes go to the agent's own branch; root sees them when the branch merges, and a
  failure at the run's end (root's judge reads the branch)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.print :as print] [seon.task :as task] [seon.proofs :as proofs] [seon.turn :as turn] [seon.ctx.status :as status] [seon.ctx] [seon.contract :as contract] [seon.db :as pdb] [seon.learn :as learn] [datahike.api :as d]))

(defn current
  "The task this agent works, as data (:seon.task/task)."
  {:malli/schema [:=> [:cat] [:maybe :seon.task/task]]}
  [] (some->> (turn/current-task) (task/task (agent/db))))

(defn data
  "This agent's task as data (seon.ctx.status/task-data): its task and plan, its write set (each
  var the plan cites, its source and callers), how to run each proof, the sections it cites; what
  each turn of its run is sent after the help page, the form a cut of it reads its rest through."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  [] (when-let [t (current)] (status/task-data (agent/db) t (task/task-cites (agent/db) (:seon.task/id t)))))

(defn run
  "Task id's last run, folded (seon.learn/run): each turn with what it read, the vars its final definitions used, and what it
  lost (the waste rows, by producer), read from its agent's branch. What a refine reads first. Answers
  :seon.db/unresolved when the branch is gone. See docs/tasks.md#a-refine-curates-its-own-task."
  {:malli/schema [:=> [:cat :seon.task/id] [:or :map :seon.db/unresolved]]}
  [id]
  (let [a (:seon.task/agent (d/entity (agent/db) [:seon.task/id id]))
        db (pdb/at (:store-conn @agent/state) (agent/db) {:branch (pdb/on-main (pdb/main-branch (:cfg @agent/state)) (:seon.agent/branch a))})]
    (if (contract/error? db) db (learn/run db (:seon.agent/key a)))))

(defn update!
  "clojure.core/update on a content member of this agent's task (:seon.task/plan,
  :seon.task/instructions, :seon.task/proofs, the set of its proofs: a design registers each
  red proof it writes with `(my.task/update! :seon.task/proofs conj 'ns.x-test/a-proof)`, or a stall
  task's :seon.task/cause: `(my.task/update! :seon.task/cause merge {:what .. :evidence {..} :bug? ..})`), written
  on its branch (seon.task/member-tx) and carried to main at the run's end. Answers the member's
  value after the update, as clojure.core/swap! answers the new value (the whole task, its
  instructions and task data was most of each answer: the live agents' rounds), or
  :seon.task/invalid-member, writing nothing, for a value the member's schema does not admit."
  {:malli/schema [:=> [:cat [:enum :seon.task/plan :seon.task/instructions :seon.task/proofs :seon.task/cause] :seon.host/fn [:* :any]] [:or :string :seon.task/proofs :seon.task/cause :seon.task/invalid-member]]}
  [member f & args]
  (let [tx (task/member-tx (current) member f (vec args))]
    (if (contract/error? tx)
      tx
      (do ;; its plan or instructions rewritten: the write carries what the new text cites (seon.agent/transact!)
          (agent/transact! tx)
          (get (current) member)))))

(defn launch!
  "Start an agent on work of its own: a released task whose plan is text, from a template by name (seon.template/templates; :whole by default: it designs, builds, proves and merges by itself); root tells this agent how it ends (seon.reconcile/tell-tx). Answers {:task id}, or the refusal. See docs/agent.md#working-for-a-person."
  {:malli/schema [:function [:=> [:cat :string] :map] [:=> [:cat :string :keyword] :map]]}
  ([text] (launch! text :whole))
  ([text template]
   (agent/launch! text template)))

(defn fail!
  "Mark this agent's task failed: reason, and a counterexample (a form or value showing
  it), written on the task on this branch (:seon.task/failure, with this run). A turn
  loop ends after this turn; at the run's end root's judge carries the failure to main
  and holds the task for root (state :failed): no retry. Answers the failure."
  {:malli/schema [:=> [:cat :string :any] :seon.task/failure]}
  [reason counterexample]
  (let [f {:reason reason :counterexample (print/text counterexample (:part print/budgets)) :agent (:key @agent/state) :turn (agent/turn-now)}]
    (agent/transact! [{:seon.task/id (turn/current-task) :seon.task/failure f}])
    (turn/request-stop! :failed reason)
    f))

(defn- proof-as-read
  "A proof's entry x of the status as (my.task/status) answers it: one that does not pass keeps its :source and the :calls it makes, as
  the page's status part draws them (docs/agent.md: the same answer as the page); a passing one neither. Never its :shape."
  {:malli/schema [:=> [:cat :map] :map]}
  [x] (if (= :pass (:status x)) (dissoc x :calls :shape :source) (dissoc x :shape)))

(defn status
  "How this agent's work on its task stands now, as its page and its model read it
  (seon.ctx.status/status, drawn by seon.render.ui/status): each proof by the verdict its branch holds (a red one
  with its failures), what else the done rule refuses with the form that does
  it, the functions without a contract, the problems to clear, what its merge carries, its
  unmerged entries, a test run that failed as a whole. A verdict is what the branch's last run
  recorded, reused greens included: (my.agent/prove) runs what changed since."
  {:malli/schema [:=> [:cat] :any]}
  []
  ;; from the agent's record (@seon.ctx/ctx: its run, its task's proofs), as the page's status part reads it; never
  ;; the walk from inside an eval (drawing the page within an eval spun the agent: seon.server-test's my.task/status)
  (let [st (dissoc (status/status (agent/db) @seon.ctx/ctx {}) :reasons)]
    ;; what an agent reads as data: its proofs and what blocks; a proof that does not pass keeps its :source and the :calls it
    ;; makes, as the page's status part draws them (docs/agent.md: the same answer as the page), a passing one neither
    (cond-> (update st :proofs #(mapv proof-as-read %)) (:refused st) (update :refused #(mapv proof-as-read %)))))

