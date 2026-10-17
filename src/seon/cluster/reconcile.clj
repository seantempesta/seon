(ns seon.cluster.reconcile
  "The pure reconciler: each task's state matched to its one row of `transitions`, the start group admitted by
  `admit`, and each branch matched to its one row of `branch-transitions`. Desired and observed state in, the minimal
  actions out; each action's actor writes the fact that closes its row. An agent's own proc steps by its one row of
  `agent-transitions` for its phase and the event that arrived."
  (:require [seon.agent :as-alias agent]
            [seon.flow.actions :as-alias fa]
            [seon.task :as-alias task]))

(def transitions
  "The task transition table: disjoint rows over `:seon.flow.actions/matched-state`, a steady row wherever no action
  is due, so every possible state matches exactly one row."
  (let [hosted {::fa/graph :running ::fa/latest :open}
        open {::fa/graph :absent ::fa/latest :open}
        working (assoc open ::fa/execution :running ::fa/agent-env #{:none :same})
        awaiting {::fa/graph :absent ::fa/latest :awaiting ::fa/execution #{:held :running}}
        ended-done (assoc awaiting ::fa/attempt :done)
        idle {::fa/latest #{:none :closed}}
        wanted (assoc idle ::fa/wanted? true)
        built (assoc wanted ::fa/branch :current ::fa/config :converged)]
    [{::fa/row "G1" ::fa/when {::fa/graph :running ::fa/latest :awaiting} ::fa/then [:stop]}
     {::fa/row "G2" ::fa/when (assoc hosted ::fa/execution #{:held :cancelled}) ::fa/then [:stop]}
     {::fa/row "G5" ::fa/when (assoc hosted ::fa/execution :running ::fa/agent-env :differs) ::fa/then [:stop]}
     {::fa/row "G6" ::fa/when (assoc hosted ::fa/execution :running ::fa/agent-env :same ::fa/branch :current)
      ::fa/then [] ::fa/steady "working"}
     {::fa/row "G7" ::fa/when (assoc hosted ::fa/execution :running ::fa/agent-env :same ::fa/branch :stale)
      ::fa/then [:merge-main]}
     {::fa/row "O1" ::fa/when (assoc open ::fa/execution :cancelled) ::fa/then [:close-cancelled]}
     {::fa/row "O2" ::fa/when (assoc open ::fa/execution :held) ::fa/then [] ::fa/steady "held with its attempt open"}
     {::fa/row "O3" ::fa/when (assoc open ::fa/execution :running ::fa/agent-env :differs) ::fa/then [:close-environment]}
     {::fa/row "O4" ::fa/when (assoc working ::fa/branch :stale) ::fa/then [:merge-main]}
     {::fa/row "O5" ::fa/when (assoc working ::fa/branch :current ::fa/step :unjudged) ::fa/then [:judge]}
     {::fa/row "O6" ::fa/when (assoc working ::fa/branch :current ::fa/step :empty) ::fa/then [:done-step]}
     {::fa/row "O7" ::fa/when (assoc working ::fa/branch :current ::fa/step :work-left) ::fa/then [:start-graph]}
     {::fa/row "A1" ::fa/when (assoc awaiting ::fa/execution :cancelled) ::fa/then [:close-cancelled]}
     {::fa/row "A2" ::fa/when (assoc awaiting ::fa/attempt #{:given-back :capped :stalled :sent-back :cancelled :error})
      ::fa/then [:bail]}
     {::fa/row "A3" ::fa/when (assoc ended-done ::fa/branch :stale) ::fa/then [:merge-main]}
     {::fa/row "A4" ::fa/when (assoc ended-done ::fa/branch :current ::fa/step :unjudged) ::fa/then [:judge]}
     {::fa/row "A5" ::fa/when (assoc ended-done ::fa/branch :current ::fa/step :empty) ::fa/then [:done-step]}
     {::fa/row "A6" ::fa/when (assoc ended-done ::fa/branch :current ::fa/step :work-left) ::fa/then [:bail]}
     {::fa/row "S1" ::fa/when {::fa/latest :settled ::fa/publication #{:none :done}} ::fa/then [] ::fa/steady "done"}
     {::fa/row "S2" ::fa/when {::fa/latest :settled ::fa/publication :due} ::fa/then [:publish]}
     {::fa/row "N1" ::fa/when (assoc idle ::fa/wanted? false) ::fa/then []
      ::fa/steady "not released, or waiting on needs"}
     {::fa/row "N2" ::fa/when (assoc wanted ::fa/branch :absent) ::fa/then [:build] ::fa/start? true}
     {::fa/row "N3" ::fa/when (assoc wanted ::fa/branch :stale) ::fa/then [:rebuild] ::fa/start? true}
     {::fa/row "N4" ::fa/when (assoc wanted ::fa/branch :current ::fa/config :differs) ::fa/then [:converge-config]
      ::fa/start? true}
     {::fa/row "N5" ::fa/when (assoc built ::fa/environment :incomplete) ::fa/then [:problem-hold]}
     {::fa/row "N6" ::fa/when (assoc built ::fa/environment :complete) ::fa/then [:start-step] ::fa/start? true}]))

(def branch-transitions
  "The branch transition table: disjoint rows over `:seon.flow.actions/branch-state`."
  (let [idle {::fa/active? false ::fa/route? false}]
    [{::fa/row "B1" ::fa/when {::fa/active? true ::fa/route? false} ::fa/then [:listen]}
     {::fa/row "B2" ::fa/when {::fa/active? true ::fa/route? true} ::fa/then [] ::fa/steady "listening"}
     {::fa/row "B3" ::fa/when {::fa/active? false ::fa/route? true} ::fa/then [:release-route]}
     {::fa/row "B4" ::fa/when (assoc idle ::fa/finished? true ::fa/ended? false) ::fa/then [:end-branch]}
     {::fa/row "B5" ::fa/when (assoc idle ::fa/finished? true ::fa/ended? true) ::fa/then [] ::fa/steady "kept"}
     {::fa/row "B6" ::fa/when (assoc idle ::fa/finished? false) ::fa/then [] ::fa/steady "held work waits"}]))

(def agent-transitions
  "The agent's phase table: disjoint rows over `:seon.flow.actions/agent-state`, one per possible phase and event. Each
  row moves the proc to its next phase and runs at most one op there, whose answer is the next event; a row that runs
  no op names the steady phase it waits in for flow."
  (let [long-op #{:calling :evaluating :testing}]
    [{::fa/row "F1" ::fa/when {::fa/phase :paused ::fa/event :resume} ::fa/next :idle ::fa/then [:derive]}
     {::fa/row "F2" ::fa/when {::fa/phase #{:idle :parked :ended} ::fa/event :pause} ::fa/next :paused ::fa/then []
      ::fa/steady "paused"}
     {::fa/row "F3" ::fa/when {::fa/phase #{:paused :idle :parked :ended} ::fa/event :stop} ::fa/next :stopped
      ::fa/then [:release]}
     {::fa/row "I1" ::fa/when {::fa/phase :idle ::fa/event :wake} ::fa/next :idle ::fa/then [:derive]}
     {::fa/row "I2" ::fa/when {::fa/phase #{:idle :parked} ::fa/event :submission} ::fa/next :evaluating
      ::fa/then [:evaluate]}
     {::fa/row "I3" ::fa/when {::fa/phase :parked ::fa/event :wake} ::fa/next :parked ::fa/then []
      ::fa/steady "parked: a wake never repeats a refused write"}
     {::fa/row "I4" ::fa/when {::fa/phase :ended ::fa/event :wake} ::fa/next :ended ::fa/then [] ::fa/steady "ended"}
     {::fa/row "I5" ::fa/when {::fa/phase :ended ::fa/event :submission} ::fa/next :ended ::fa/then [:refuse-submission]}
     {::fa/row "D1" ::fa/when {::fa/phase :idle ::fa/event :attempt} ::fa/next :testing ::fa/then [:test-step]}
     {::fa/row "D2" ::fa/when {::fa/phase :idle ::fa/event :work} ::fa/next :calling ::fa/then [:call-model]}
     {::fa/row "D3" ::fa/when {::fa/phase :idle ::fa/event :nothing} ::fa/next :idle ::fa/then [] ::fa/steady "idle"}
     {::fa/row "D4" ::fa/when {::fa/phase :idle ::fa/event :admission-closed} ::fa/next :ended ::fa/then []
      ::fa/steady "ended"}
     {::fa/row "M1" ::fa/when {::fa/phase :calling ::fa/event :model-reply} ::fa/next :evaluating ::fa/then [:evaluate]}
     {::fa/row "M2" ::fa/when {::fa/phase :calling ::fa/event :model-error} ::fa/next :judging ::fa/then [:judge]}
     {::fa/row "E1" ::fa/when {::fa/phase :evaluating ::fa/event :evaluated} ::fa/next :testing ::fa/then [:test-step]}
     {::fa/row "T1" ::fa/when {::fa/phase :testing ::fa/event :tests-reported} ::fa/next :judging ::fa/then [:judge]}
     {::fa/row "J1" ::fa/when {::fa/phase :judging ::fa/event :task-ended} ::fa/next :ended ::fa/then []
      ::fa/steady "ended"}
     {::fa/row "J2" ::fa/when {::fa/phase :judging ::fa/event :continues} ::fa/next :idle ::fa/then [:derive]}
     {::fa/row "W1" ::fa/when {::fa/phase long-op ::fa/event :write-refused} ::fa/next :idle ::fa/then [:derive]}
     {::fa/row "W2" ::fa/when {::fa/phase long-op ::fa/event :refusal-bound} ::fa/next :parked ::fa/then []
      ::fa/steady "parked"}
     {::fa/row "X1" ::fa/when {::fa/phase long-op ::fa/event :interrupted} ::fa/next :idle ::fa/then []
      ::fa/steady "interrupted, its stop queued"}]))

(def agent-answers
  "Each op of `agent-transitions` and the answers it can give, as `:seon.flow.actions/answers`: its own work's, and
  those only another actor causes (the writer refusing, a stop interrupting, the model choosing to continue, which the
  attempt's turn cap `seon.turn/turns-left` bounds)."
  (let [long-op {:closed [:refusal-bound] :external [:write-refused :interrupted]}]
    {:derive {:closed [:attempt :work :nothing :admission-closed]}
     :call-model (update long-op :closed into [:model-reply :model-error])
     :evaluate (update long-op :closed conj :evaluated)
     :test-step (update long-op :closed conj :tests-reported)
     :judge {:closed [:task-ended] :external [:continues]}
     :release {:closed []}
     :refuse-submission {:closed []}}))

(def ^:private start-ops
  (into #{} (comp (filter ::fa/start?) (mapcat ::fa/then)) transitions))

(defn task-state
  "`state` with the derived values rows read: its latest attempt's phase
  and whether it is wanted."
  {:malli/schema [:=> [:cat :seon.flow.actions/task-state] [:and :seon.flow.actions/task-state :seon.flow.actions/matched-state]]}
  [{::fa/keys [execution needs attempt outcome] :as state}]
  (assoc state
         ::fa/latest (cond (= :none attempt) :none
                           (#{:done :merged} outcome) :settled
                           (= :closed outcome) :closed
                           (= :unended attempt) :open
                           :else :awaiting)
         ::fa/wanted? (and (= :running execution) (not= :pending needs))))

(defn row
  "The row of `table` whose condition `state` meets: per key, a value or
  a set of values."
  {:malli/schema [:=> [:cat [:vector :seon.flow.actions/transition]
                       [:or :seon.flow.actions/matched-state :seon.flow.actions/branch-state
                        :seon.flow.actions/agent-state]]
                  :seon.flow.actions/transition]}
  [table state]
  (let [meets? (fn [[k allowed]] (if (set? allowed) (contains? allowed (get state k)) (= allowed (get state k))))]
    (some #(when (every? meets? (::fa/when %)) %) table)))

(defn agent-transition
  "The row of `agent-transitions` for `state`, a phase and the event that arrived in it: the phase the agent's proc
  moves to and the op it runs there."
  {:malli/schema [:=> [:cat :seon.flow.actions/agent-state] :seon.flow.actions/transition]}
  [state]
  (row agent-transitions state))

(defn effects
  "The states `op`'s actor can leave `state` in: `:closed`, its own write
  with each answer the world gives (a judgment, a config read back), and
  `:external`, one only another writer causes (main moved under the done
  step's basis fence)."
  {:malli/schema [:=> [:cat :seon.flow.actions/op [:or :seon.flow.actions/task-state :seon.flow.actions/branch-state]]
                  [:map [:closed [:vector :map]] [:external {:optional true} [:vector :map]]]]}
  [op state]
  (let [judged (fn [s] (mapv #(assoc s ::fa/step %) [:empty :work-left]))
        configs (fn [s] (mapv #(assoc s ::fa/config %) [:converged :differs]))]
    (case op
      :build {:closed (configs (assoc state ::fa/branch :current))}
      :rebuild {:closed (configs (assoc state ::fa/branch :current ::fa/attempt :none ::fa/outcome :none
                                        ::fa/step :none ::fa/agent-env :none))}
      :converge-config {:closed [(assoc state ::fa/config :converged)]}
      :start-step {:closed (judged (assoc state ::fa/attempt :unended ::fa/outcome :none ::fa/agent-env :same))}
      :problem-hold {:closed [(assoc state ::fa/execution :held)]}
      :judge {:closed (judged state)}
      :done-step (if (= :main (::fa/target state))
                   {:closed (mapv #(assoc state ::fa/outcome :merged ::fa/publication %) [:due :done])
                    :external [(assoc state ::fa/branch :stale)]}
                   {:closed [(assoc state ::fa/outcome :done)]})
      :start-graph {:closed [(assoc state ::fa/graph :running ::fa/agent-env :same)]}
      :stop {:closed [(assoc state ::fa/graph :absent)]}
      (:close-cancelled :close-environment) {:closed [(assoc state ::fa/outcome :closed)]}
      :bail {:closed [(assoc state ::fa/execution :held ::fa/outcome :closed)]}
      :merge-main {:closed (judged (assoc state ::fa/branch :current))}
      :publish {:closed [(assoc state ::fa/publication :done)]}
      :listen {:closed [(assoc state ::fa/route? true)]}
      :release-route {:closed [(assoc state ::fa/route? false)]}
      :end-branch {:closed [(assoc state ::fa/ended? true)]})))

(defn admit
  "`answers` with the start group admitted: oldest first, one per branch
  no open attempt holds, while open attempts are fewer than `max-running`."
  {:malli/schema [:=> [:cat :seon.flow.actions/reconcile-actions [:map-of :seon.task/handle :seon.flow.actions/task]
                       :seon.config.agent/max-running]
                  :seon.flow.actions/reconcile-actions]}
  [answers tasks max-running]
  (let [open (filterv #(= :open (::fa/latest (task-state (::fa/task-state %)))) (vals tasks))
        start? (comp start-ops ::fa/op)
        admitted (->> (filter start? answers)
                      (sort-by (comp ::fa/created-t tasks ::task/handle))
                      (reduce (fn [[held out] {::task/keys [branch] :as answer}]
                                (if (held branch) [held out] [(conj held branch) (conj out answer)]))
                              [(into #{} (map ::task/branch) open) []])
                      second
                      (take (max 0 (- max-running (count open))))
                      set)]
    (filterv #(or (not (start? %)) (admitted %)) answers)))

(defn reconcile
  "The minimal actions closing desired against observed state: each
  task's row's actions, oldest first, the start group admitted, then each
  branch's row's actions."
  {:malli/schema [:=> [:cat :seon.flow.actions/observed :seon.config.agent/max-running]
                  :seon.flow.actions/reconcile-actions]}
  [{::fa/keys [tasks branch-states]} max-running]
  (let [answers (vec (for [[handle {state ::fa/task-state :as t}] (sort-by (comp ::fa/created-t val) tasks)
                           op (::fa/then (row transitions (task-state state)))]
                       (merge {::fa/op op ::task/handle handle} (select-keys t [::task/branch ::agent/id]))))]
    (into (admit answers tasks max-running)
          (for [[branch state] (sort-by key branch-states)
                op (::fa/then (row branch-transitions state))]
            {::fa/op op ::task/branch branch}))))
