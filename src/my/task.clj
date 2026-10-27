(ns my.task
  "The agent's task functions (Seon's my.task): read its task, improve it, or mark it failed.
  Writes go to the agent's own branch; root sees them when the branch merges, and a
  failure at the run's end (root's judge reads the branch)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.print :as print] [seon.task :as task] [seon.turn :as turn] [seon.context :as context] [seon.contract :as contract] [seon.db :as pdb]))

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
      (do (agent/transact! tx) (get (current) member)))))

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
  "This agent's task now, as its turn reads it: each proof's status (a red one with its
  failures, source and calls), what else the done rule refuses, a failed test run, the turn of
  how many, what happened to its last reply and its unmerged entries (seon.context/turn-state), as
  this turn's observe measured them; what the branch's merge carries, whether it can merge, the
  stale wiki sections and its tests are the task render's views (seon.render.ui/divergence-view,
  merge-view, caused-view, tests-view). Before any turn (a model's, or an outside agent's context
  read) has observed the task in this process: :seon.turn/not-observed, and (my.agent/prove) runs its proofs."
  {:malli/schema [:=> [:cat] :any]}
  []
  (if-let [{:keys [input]} (turn/input)]
    (context/turn-state (agent/db) (current) input)
    (contract/error :seon.turn/not-observed #'my.task/status "no turn has observed this agent's task yet: a model turn or a context read (the server's :context, seon_context) measures it; (my.agent/prove) runs its proofs" {})))

(defn related
  "What this task's plan, as the branch holds it now, is likely to need beyond what it cites: the
  wiki sections, program functions and schema keys it most likely needs, by meaning, words and
  references (seon.context/related, a render over the plan's vectors), :seon.config.render/related
  of each at :seon.config.render/relevance-summary or more, each a line with its chance and the
  forms that read one whole and search further (seon.context/related-view). Given cut, every item
  at or above it, uncounted (0: every item scored; nil: the configured cut and count). (my.agent/related \"words\" k) refines it by any text."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:maybe number?]] :map]]}
  ([] (related nil))
  ([cut]
  (let [db (agent/db)]
    (if-let [r (when-let [te (some->> (current) :seon.task/id (pdb/eid db :seon.task/id))]
                 (context/related db te (cond-> {} cut (assoc :cut cut))))]
      (or (context/related-view db r) {:nothing "nothing nearer the plan than what it cites"})
      {:none "none: the count is 0 (:seon.config.render/related), or the plan has no vector yet; (my.agent/related \"your words\") searches the wiki by any text"}))))
