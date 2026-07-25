(ns seon.runtime.recovery
  "Claimant-session recovery as one durable, fenced database transition.

   Recovery is deliberately conservative. Every nonterminated agent that
   still owns a run pointer is returned to derived `:idle`: an open run is
   closed `:crashed`, a running turn is marked `:interrupted`, and even a
   stale pointer to an already-closed run is cleared. All pointer assertions,
   repairs, and the one recovery anchor commit in a single transaction.

   The anchor stores only the claimant-session-loss fact. [[pending-notices]]
   joins its transaction to the pointer/run/turn datoms changed there and
   derives which agents still have no later run. No affected refs,
   acknowledgement, or rendered notification is persisted."
  (:require
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.protocol :as protocol]
    [seon.error :as error]
    [seon.eval.receipt :as eval.receipt]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Durable recovery fact — one small anchor, never a materialized report.
;; ---------------------------------------------------------------------------

(def maximum-detail-characters
  "Character cap for the optional recovery detail.

   W1 relocates this named recovery policy into an aero-backed database fact."
  2048)

(schema/register!
  :seon.runtime.recovery/id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! :seon.runtime.recovery/reason
                  [:enum :claimant-session-loss])
(schema/register! :seon.runtime.recovery/detail
                  [:string {:max maximum-detail-characters}])
(schema/register! :seon.runtime.recovery/eval :seon.db/ref)

(schema/register! :seon.runtime.recovery
  [:map {:seon.db/entity true}
   [:seon.runtime.recovery/id :seon.runtime.recovery/id]
   [:seon.runtime.recovery/reason :seon.runtime.recovery/reason]
   [:seon.runtime.recovery/detail
    {:optional true} :seon.runtime.recovery/detail]
   [:seon.runtime.recovery/eval
    {:optional true} :seon.runtime.recovery/eval]])

;; ---------------------------------------------------------------------------
;; Recovery operation contract.
;; ---------------------------------------------------------------------------

(schema/register! ::repaired? :boolean)
;; These are response values, not entity attributes. Use their shared value
;; shapes so recovery has no hidden load-order dependency on the namespaces
;; that own the corresponding identity attributes.
(schema/register! ::agent-ids [:vector :string])
(schema/register! ::run-ids [:vector ::db.id/compact-value])
(schema/register! ::turn-ids [:vector ::db.id/compact-value])
(schema/register! ::eval-ids [:vector ::db.id/compact-value])
(schema/register!
 ::recover-request
 [:or
  [:map {:closed true}
   [:seon.runtime.recovery/detail
    {:optional true} :seon.runtime.recovery/detail]]
  [:map {:closed true}
   [:seon.agent/id :string]
   [:seon.agent.run/id ::db.id/compact-value]
   [:seon.runtime.recovery/detail
    {:optional true} :seon.runtime.recovery/detail]]])
(schema/register! ::recover-response
  [:or
   [:map
    [::repaired? ::repaired?]
    [::agent-ids ::agent-ids]
    [::run-ids ::run-ids]
    [::turn-ids ::turn-ids]
    [::eval-ids ::eval-ids]
    [:seon.runtime.recovery/id
     {:optional true} :seon.runtime.recovery/id]]
   ::db/error])

(def ^:private repair-targets-query
  '[:find ?agent-id ?run-id ?status
    :where
    [?agent :seon.agent/id ?agent-id]
    [?agent :seon.agent/run ?run]
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/status ?status]
    (not [?agent :seon.agent/terminated-at _])])

(def ^:private running-turns-query
  '[:find ?run-id ?turn-id
    :where
    [?agent :seon.agent/run ?run]
    (not [?agent :seon.agent/terminated-at _])
    [?run :seon.agent.run/id ?run-id]
    [?turn :seon.agent.turn/run ?run]
    [?turn :seon.agent.turn/id ?turn-id]
    [?turn :seon.agent.turn/status :running]])

(def ^:private running-evals-query
  '[:find ?run-id ?turn-id ?eval-id
    :where
    [?agent :seon.agent/run ?run]
    (not [?agent :seon.agent/terminated-at _])
    [?run :seon.agent.run/id ?run-id]
    [?turn :seon.agent.turn/run ?run]
    [?turn :seon.agent.turn/id ?turn-id]
    [?turn :seon.agent.turn/evals ?eval]
    [?eval :seon.eval/id ?eval-id]
    [?eval :seon.eval/status :running]])

(def ^:private recovery-policy-query
  '[:find ?generator .
    :where
    [?schema :seon.schema/key :seon.runtime.recovery/id]
    [?schema :seon.db.id/generator ?generator]])

(def ^:private scoped-repair-target-query
  '[:find ?agent-id ?run-id ?status
    :in $ ?agent-id ?run-id
    :where
    [?agent :seon.agent/id ?agent-id]
    [?agent :seon.agent/run ?run]
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/status ?status]
    (not [?agent :seon.agent/terminated-at _])])

(def ^:private scoped-running-turns-query
  '[:find ?run-id ?turn-id
    :in $ ?agent-id ?run-id
    :where
    [?agent :seon.agent/id ?agent-id]
    [?agent :seon.agent/run ?run]
    [?run :seon.agent.run/id ?run-id]
    [?turn :seon.agent.turn/run ?run]
    [?turn :seon.agent.turn/id ?turn-id]
    [?turn :seon.agent.turn/status :running]])

(def ^:private scoped-running-evals-query
  '[:find ?run-id ?turn-id ?eval-id
    :in $ ?agent-id ?run-id
    :where
    [?agent :seon.agent/id ?agent-id]
    [?agent :seon.agent/run ?run]
    [?run :seon.agent.run/id ?run-id]
    [?turn :seon.agent.turn/run ?run]
    [?turn :seon.agent.turn/id ?turn-id]
    [?turn :seon.agent.turn/evals ?eval]
    [?eval :seon.eval/id ?eval-id]
    [?eval :seon.eval/status :running]])

(defn- query-member
  ([query] (query-member query []))
  ([query arguments]
   {::protocol/operation protocol/query-operation
    ::protocol/query-form query
    ::protocol/arguments arguments}))

(defn- member-value!
  [operation member]
  (if (true? (::protocol/success? member))
    (:datahike.query/result member)
    (throw (ex-info (str "Recovery " operation " failed.")
                    {:seon.error/kind :core-bug
                     ::protocol/error (::protocol/error member)
                     ::protocol/error-kind (::protocol/error-kind member)}))))

(defn- ^:async acquire-recovery!
  [database agent-id run-id]
  (let [scoped? (and agent-id run-id)
        arguments (if scoped? [agent-id run-id] [])
        queries (if scoped?
                  [scoped-repair-target-query scoped-running-turns-query
                   scoped-running-evals-query]
                  [repair-targets-query running-turns-query
                   running-evals-query])
        acquired
        (await
         (db/execute-many
          {::db/db database
           ::db/members
           (conj (mapv #(query-member % arguments) queries)
                 (query-member recovery-policy-query))}))]
    (if (:seon.error/message acquired)
      acquired
      (let [[targets turns evals policy] (::db/results acquired)]
        {::db/db database
         ::targets (->> (member-value! "target acquisition" targets)
                        (sort-by (juxt first second))
                        vec)
         ::turns (->> (member-value! "turn acquisition" turns)
                      (sort-by (juxt first second))
                      vec)
         ::evals (->> (member-value! "eval acquisition" evals)
                      (sort-by (juxt first second #(nth % 2)))
                      vec)
         ::generator (member-value! "generator-policy acquisition" policy)}))))

(declare error-value?)

(defn- compile-recovery
  [{::keys [targets turns evals] ::db/keys [db]}
   recovery-id closed-at detail]
  (let [agent-ids (mapv first targets)
        run-ids (mapv second targets)
        open-run-ids (->> targets
                          (keep (fn [[_ run-id status]]
                                  (when (= :open status) run-id)))
                          distinct
                          vec)
        turn-ids (->> turns (map second) sort vec)
        eval-ids (->> evals (map #(nth % 2)) sort vec)
        interrupted-eval-id (first eval-ids)
        fences (mapv (fn [[agent-id run-id _]]
                       (db/cas-assert
                        [:seon.agent/id agent-id]
                        :seon.agent/run
                        [:seon.agent.run/id run-id]))
                     targets)
        pointer-retractions
        (mapv (fn [[agent-id run-id _]]
                [:db/retract
                 [:seon.agent/id agent-id]
                 :seon.agent/run
                 [:seon.agent.run/id run-id]])
              targets)
        run-closes
        (mapv (fn [run-id]
                {:seon.agent.run/id run-id
                 :seon.agent.run/status :closed
                 :seon.agent.run/closed-reason :crashed
                 :seon.agent.run/closed-at closed-at})
              open-run-ids)
        turn-closes
        (mapv (fn [turn-id]
                {:seon.agent.turn/id turn-id
                 :seon.agent.turn/status :interrupted})
              turn-ids)
        eval-closes
        (into []
              (mapcat (fn [eval-id]
                        (eval.receipt/terminal-tx-data
                         {:seon.eval/id eval-id
                          :seon.eval/status :interrupted})))
              eval-ids)
        anchor (cond->
                 {:seon.runtime.recovery/id recovery-id
                  :seon.runtime.recovery/reason :claimant-session-loss}
                 interrupted-eval-id
                 (assoc :seon.runtime.recovery/eval
                        [:seon.eval/id interrupted-eval-id])
                 detail
                 (assoc :seon.runtime.recovery/detail detail))]
    {::db/expected-db db
     ::db/tx-data (-> fences
                      (into pointer-retractions)
                      (into run-closes)
                      (into turn-closes)
                      (into eval-closes)
                      (conj anchor))
     ::agent-ids agent-ids
     ::run-ids run-ids
     ::turn-ids turn-ids
     ::eval-ids eval-ids}))

(defn- error-value?
  [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- transaction-report?
  [value]
  (and (map? value)
       (contains? value :db-before)
       (contains? value :db-after)
       (contains? value :tx-data)))

(defn ^:async recover!
  "Fence interrupted ownership and restore every affected agent to idle.

   Reads one frozen current database value, then commits one transaction:
   every old→old pointer CAS first, exact pointer retractions, `:crashed` run
   closes, `:interrupted` running turns, and one claimant-session-loss anchor. A
   terminated agent is never touched. With no repair targets, performs no
   transaction and returns `::repaired? false`.

   The caller supplies root/boot transaction provenance. Database failures are
   returned as direct error values; no partial repair can commit."
  {:malli/schema [:=> [:catn [::request ::recover-request]]
                  ::recover-response]}
  [{:seon.runtime.recovery/keys [detail]
    agent-id :seon.agent/id
    run-id :seon.agent.run/id}]
  (try
    (let [database (await (db/db))]
      (if (error-value? database)
        database
        (let [{::keys [targets generator] :as acquired}
              (await (acquire-recovery! database agent-id run-id))]
          (cond
            (error-value? acquired)
            acquired

            (empty? targets)
            {::repaired? false
             ::agent-ids []
             ::run-ids []
             ::turn-ids []
             ::eval-ids []}

            :else
            (let [closed-at (js/Date.)
                  result
                  (await
                   (db.id/allocate!
                    {::db/db database
                     ::db.id/allocations
                     [{::db.id/key :seon.runtime.recovery/id
                       ::db.id/identity-attr :seon.runtime.recovery/id}]
                     ::db.id/generator-policies
                     {:seon.runtime.recovery/id generator}
                     ::db.id/transaction-builder
                     (fn [ids]
                       (select-keys
                        (compile-recovery
                         acquired
                         (get ids :seon.runtime.recovery/id)
                         closed-at
                         detail)
                        [::db/expected-db ::db/tx-data]))}))
                  recovery-id
                  (get-in result [::db.id/ids :seon.runtime.recovery/id])
                  compiled
                  (when (transaction-report? result)
                    (compile-recovery acquired recovery-id closed-at detail))]
              (cond
                (error-value? result)
                result

                (not (transaction-report? result))
                {:seon.error/message
                 "Recovery allocation returned neither a transaction report nor an error."
                 :seon.error/kind :core-bug
                 :seon.error/data {:seon.runtime.recovery/result result}}

                :else
                (cond->
                 {::repaired? true
                  ::agent-ids (::agent-ids compiled)
                  ::run-ids (::run-ids compiled)
                  ::turn-ids (::turn-ids compiled)
                  ::eval-ids (::eval-ids compiled)
                  :seon.runtime.recovery/id recovery-id})))))))
    (catch :default exception
      (let [value (error/->map exception)]
        (cond-> value
          (nil? (:seon.error/kind value))
          (assoc :seon.error/kind :core-bug))))))

;; ---------------------------------------------------------------------------
;; Root notice projection — transaction joins, never copied anchor data.
;; ---------------------------------------------------------------------------

(schema/register! ::transaction :int)
(schema/register! ::at :inst)
(schema/register! ::agents [:vector :string])
(schema/register! ::runs [:vector ::db.id/compact-value])
(schema/register! ::turns [:vector ::db.id/compact-value])
(schema/register! ::notice
  [:map
   [:seon.runtime.recovery/id :seon.runtime.recovery/id]
   [:seon.runtime.recovery/reason :seon.runtime.recovery/reason]
   [:seon.runtime.recovery/detail
    {:optional true} :seon.runtime.recovery/detail]
   [::transaction ::transaction]
   [::at ::at]
   [::agents ::agents]
   [::runs ::runs]
   [::turns ::turns]])
(schema/register! ::pending-notices-request
  [:map [:seon.db/db :seon.db/db]])
(schema/register! ::pending-notices-response
  [:or [:vector ::notice] ::db/error])

(defn- ^:async anchor-rows
  [history]
  (await
   (db/query
    {:seon.db/db history
     :seon.db/query
     '[:find ?id ?tx ?at
       :where
       [?anchor :seon.runtime.recovery/id ?id ?tx true]
       [?tx :db/txInstant ?at]]})))

(defn- ^:async repaired-agent-runs
  [history transaction]
  (let [rows (await
              (db/query
               {:seon.db/db history
                :seon.db/query
                '[:find ?agent-id ?run-id
                  :in $ ?transaction
                  :where
                  [?agent :seon.agent/run ?run ?transaction false]
                  [?agent :seon.agent/id ?agent-id _ true]
                  [?run :seon.agent.run/id ?run-id _ true]]
                :seon.db/args [transaction]}))]
    (if (error-value? rows)
      rows
      (->> rows (sort-by (juxt first second)) vec))))

(defn- ^:async interrupted-run-turns
  [history transaction]
  (let [rows (await
              (db/query
               {:seon.db/db history
                :seon.db/query
                '[:find ?run-id ?turn-id
                  :in $ ?transaction
                  :where
                  [?turn :seon.agent.turn/status :interrupted ?transaction true]
                  [?turn :seon.agent.turn/id ?turn-id _ true]
                  [?turn :seon.agent.turn/run ?run _ true]
                  [?run :seon.agent.run/id ?run-id _ true]]
                :seon.db/args [transaction]}))]
    (if (error-value? rows)
      rows
      (->> rows (sort-by (juxt first second)) vec))))

(defn- ^:async later-run?
  [history agent-id recovery-transaction]
  (let [run (await
             (db/query
              {:seon.db/db history
               :seon.db/query
               '[:find ?run .
                 :in $ ?agent-id ?recovery-transaction
                 :where
                 [?agent :seon.agent/id ?agent-id _ true]
                 [?run :seon.agent.run/agent ?agent ?later-transaction true]
                 [(> ?later-transaction ?recovery-transaction)]]
               :seon.db/args [agent-id recovery-transaction]}))]
    (if (error-value? run)
      run
      (boolean run))))

(defn- ^:async pending-agent-runs
  [history transaction agent-runs]
  (loop [remaining agent-runs
         pending []]
    (if (empty? remaining)
      pending
      (let [[agent-id _ :as row] (first remaining)
            later (await (later-run? history agent-id transaction))]
        (if (error-value? later)
          later
          (recur (rest remaining)
                 (if later pending (conj pending row))))))))

(defn- ^:async notice-for-anchor
  [db history [recovery-id transaction at]]
  (let [agent-runs (await (repaired-agent-runs history transaction))]
    (if (error-value? agent-runs)
      agent-runs
      (let [pending (await (pending-agent-runs history transaction agent-runs))]
        (if (error-value? pending)
          pending
          (let [pending-agent-ids (->> pending (map first) distinct vec)]
            (when (seq pending-agent-ids)
              (let [pending-run-ids (->> pending (map second) distinct vec)
                    pending-run-set (set pending-run-ids)
                    run-turns (await (interrupted-run-turns history transaction))]
                (if (error-value? run-turns)
                  run-turns
                  (let [pending-turn-ids
                        (->> run-turns
                             (keep (fn [[run-id turn-id]]
                                     (when (contains? pending-run-set run-id)
                                       turn-id)))
                             distinct
                             sort
                             vec)
                        anchor
                        (await
                         (db/entity
                          {:seon.db/db db
                           :seon.db/ref
                           [:seon.runtime.recovery/id recovery-id]}))]
                    (if (error-value? anchor)
                      anchor
                      (cond->
                       {:seon.runtime.recovery/id recovery-id
                        :seon.runtime.recovery/reason
                        (:seon.runtime.recovery/reason anchor)
                        ::transaction transaction
                        ::at at
                        ::agents pending-agent-ids
                        ::runs pending-run-ids
                        ::turns pending-turn-ids}
                        (:seon.runtime.recovery/detail anchor)
                        (assoc :seon.runtime.recovery/detail
                               (:seon.runtime.recovery/detail anchor))))))))))))))

(defn ^:async pending-notices
  "Recovery facts whose affected agents still have no later run.

   Each result is derived from the anchor transaction's pointer retractions,
   interrupted-turn assertions, and the current history. Once an affected
   agent opens a later run it disappears from that notice; when none remain,
   the notice disappears. This is the root canvas/AI-twin read model.
   Database failures return as direct `:seon/error` values."
  {:malli/schema
   [:=> [:cat ::pending-notices-request] ::pending-notices-response]}
  [{:seon.db/keys [db]}]
  (let [history (db/history db)
        rows (await (anchor-rows history))]
    (if (error-value? rows)
      rows
      (loop [anchors (sort-by second > rows)
             notices []]
        (if (empty? anchors)
          notices
          (let [notice (await (notice-for-anchor db history (first anchors)))]
            (cond
              (error-value? notice) notice
              (nil? notice) (recur (rest anchors) notices)
              :else (recur (rest anchors) (conj notices notice)))))))))
