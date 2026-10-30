(ns my.task
  "The agent's task functions (Seon's my.task): read its task, improve it, or mark it failed.
  Writes go to the agent's own branch; root sees them when the branch merges, and a
  failure at the run's end (root's judge reads the branch)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.print :as print] [seon.task :as task] [seon.proofs :as proofs] [seon.turn :as turn] [seon.context :as context] [seon.contract :as contract] [seon.db :as pdb] [datahike.api :as d]))

(defn current
  "The task this agent works, as data (:seon.task/task)."
  {:malli/schema [:=> [:cat] [:maybe :seon.task/task]]}
  [] (some->> (turn/current-task) (task/task (agent/db))))

(defn data
  "This agent's task as data (seon.context/task-data): its task and plan, its write set (each
  var the plan cites, its source and callers), how to run each proof, the sections it cites; what
  each turn of its run is sent after the help page, the form a cut of it reads its rest through."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  [] (some->> (current) (context/task-data (agent/db))))

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

(defn fail!
  "Mark this agent's task failed: reason, and a counterexample (a form or value showing
  it), written on the task on this branch (:seon.task/failure, with this run). A turn
  loop ends after this turn; at the run's end root's judge carries the failure to main
  and holds the task for root (state :failed): no retry. Answers the failure."
  {:malli/schema [:=> [:cat :string :any] :seon.task/failure]}
  [reason counterexample]
  (let [f {:reason reason :counterexample (print/text counterexample (:part print/budgets)) :run (:run @agent/state)}]
    (agent/transact! [{:seon.task/id (turn/current-task) :seon.task/failure f}])
    (turn/request-stop! :failed reason)
    f))

(defn status
  "How this agent's work on its task stands now, as its page and its model read it
  (seon.context/status, its model render): each proof by the verdict its branch holds (a red one
  with its failures, source and calls), what else the done rule refuses with the form that does
  it, the functions without a contract, the problems its work caused, what its merge carries, its
  unmerged entries, a test run that failed as a whole. A verdict is what the branch's last run
  recorded, reused greens included: (my.agent/prove) runs what changed since."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [db (agent/db)]
    (context/status-model {} (context/status db (pdb/eid db :seon.agent/key (:key @agent/state)) {}))))

