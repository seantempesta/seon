(ns seon.runtime.recovery
  "Unexpected-exit recovery as one durable, fenced database transition.

   Recovery is deliberately conservative. Every nonterminated agent that
   still owns a run pointer is returned to derived `:idle`: an open run is
   closed `:crashed`, a running turn is marked `:interrupted`, and even a
   stale pointer to an already-closed run is cleared. All pointer assertions,
   repairs, and the one recovery anchor commit in a single transaction.

   The anchor stores only the unexpected-exit fact. [[pending-notices]] joins
   its transaction to the pointer/run/turn datoms changed there and derives
   which agents still have no later run. No affected refs, acknowledgement,
   or rendered notification is persisted."
  (:require
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Durable recovery fact — one small anchor, never a materialized report.
;; ---------------------------------------------------------------------------

(schema/register!
  :seon.runtime.recovery/id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! :seon.runtime.recovery/reason
                  [:enum :unexpected-exit])
(schema/register! :seon.runtime.recovery/detail [:string {:max 2048}])

(schema/register! :seon.runtime.recovery
  [:map {:seon.db/entity true}
   [:seon.runtime.recovery/id :seon.runtime.recovery/id]
   [:seon.runtime.recovery/reason :seon.runtime.recovery/reason]
   [:seon.runtime.recovery/detail
    {:optional true} :seon.runtime.recovery/detail]])

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
(schema/register! ::recover-request
  [:map
   [:seon.runtime.recovery/detail
    {:optional true} :seon.runtime.recovery/detail]])
(schema/register! ::recover-response
  [:or
   [:map
    [::repaired? ::repaired?]
    [::agent-ids ::agent-ids]
    [::run-ids ::run-ids]
    [::turn-ids ::turn-ids]
    [:seon.runtime.recovery/id
     {:optional true} :seon.runtime.recovery/id]]
   :seon.db/transact-response])

(defn- repair-targets
  "Current nonterminated agents that still own a run pointer."
  [database]
  (->> (db/query
         {:seon.db/db database
          :seon.db/query
          '[:find ?agent-id ?run-id ?status
            :where
            [?agent :seon.agent/id ?agent-id]
            [?agent :seon.agent/run ?run]
            [?run :seon.agent.run/id ?run-id]
            [?run :seon.agent.run/status ?status]
            (not [?agent :seon.agent/terminated-at _])]})
       (sort-by (juxt first second))
       vec))

(defn- running-turns
  "Running turn ids grouped with their pointed-at run ids."
  [database target-run-ids]
  (let [target-run-ids (set target-run-ids)]
    (->> (db/query
           {:seon.db/db database
            :seon.db/query
            '[:find ?run-id ?turn-id
              :where
              [?run :seon.agent.run/id ?run-id]
              [?turn :seon.agent.turn/run ?run]
              [?turn :seon.agent.turn/id ?turn-id]
              [?turn :seon.agent.turn/status :running]]})
         (filter (fn [[run-id _]] (contains? target-run-ids run-id)))
         (sort-by (juxt first second))
         vec)))

(defn ^:async recover!
  "Fence interrupted ownership and restore every affected agent to idle.

   Reads one frozen current database value, then commits one transaction:
   every old→old pointer CAS first, exact pointer retractions, `:crashed` run
   closes, `:interrupted` running turns, and one unexpected-exit anchor. A
   terminated agent is never touched. With no repair targets, performs no
   transaction and returns `::repaired? false`.

   The caller supplies root/boot transaction provenance. Database failures are
   returned as the ordinary error envelope; no partial repair can commit."
  {:malli/schema [:=> [:cat ::recover-request] ::recover-response]}
  [{:seon.runtime.recovery/keys [detail]}]
  (let [database @db/*conn*
        targets (repair-targets database)]
    (if (empty? targets)
      {::repaired? false ::agent-ids [] ::run-ids [] ::turn-ids []}
      (let [agent-ids (mapv first targets)
            run-ids (mapv second targets)
            open-run-ids (->> targets
                              (keep (fn [[_ run-id status]]
                                      (when (= :open status) run-id)))
                              distinct
                              vec)
            turn-rows (running-turns database run-ids)
            turn-ids (->> turn-rows (map second) sort vec)
            closed-at (js/Date.)
            envelope
            (await
              (db.id/allocate!
                {::db.id/allocations
                 [{::db.id/key :seon.runtime.recovery/id
                   ::db.id/identity-attr :seon.runtime.recovery/id}]
                 ::db.id/transaction-builder
                 (fn [ids]
                   (let [recovery-id
                         (get ids :seon.runtime.recovery/id)
                         fences
                         (mapv
                           (fn [[agent-id run-id _]]
                             (db/cas-assert
                               [:seon.agent/id agent-id]
                               :seon.agent/run
                               [:seon.agent.run/id run-id]))
                           targets)
                         pointer-retractions
                         (mapv
                           (fn [[agent-id run-id _]]
                             [:db/retract
                              [:seon.agent/id agent-id]
                              :seon.agent/run
                              [:seon.agent.run/id run-id]])
                           targets)
                         run-closes
                         (mapv
                           (fn [run-id]
                             {:seon.agent.run/id run-id
                              :seon.agent.run/status :closed
                              :seon.agent.run/closed-reason :crashed
                              :seon.agent.run/closed-at closed-at})
                           open-run-ids)
                         turn-closes
                         (mapv
                           (fn [turn-id]
                             {:seon.agent.turn/id turn-id
                              :seon.agent.turn/status :interrupted})
                           turn-ids)
                         anchor
                         (cond->
                           {:seon.runtime.recovery/id recovery-id
                            :seon.runtime.recovery/reason :unexpected-exit}
                           detail
                           (assoc :seon.runtime.recovery/detail detail))]
                     {:seon.db/tx-data
                      (-> fences
                          (into pointer-retractions)
                          (into run-closes)
                          (into turn-closes)
                          (conj anchor))}))
                 :seon.db/conn db/*conn*}))
            recovery-id
            (get-in envelope [::db.id/ids :seon.runtime.recovery/id])]
        (if (false? (:seon.db/ok? envelope))
          envelope
          {::repaired? true
           ::agent-ids agent-ids
           ::run-ids run-ids
           ::turn-ids turn-ids
           :seon.runtime.recovery/id recovery-id})))))

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
  [:map [:seon.db/db :seon.db/db-val]])
(schema/register! ::pending-notices-response [:vector ::notice])

(defn- anchor-rows
  [history]
  (db/query
    {:seon.db/db history
     :seon.db/query
     '[:find ?id ?tx ?at
       :where
       [?anchor :seon.runtime.recovery/id ?id ?tx true]
       [?tx :db/txInstant ?at]]}))

(defn- repaired-agent-runs
  [history transaction]
  (->> (db/query
         {:seon.db/db history
          :seon.db/query
          '[:find ?agent-id ?run-id
            :in $ ?transaction
            :where
            [?agent :seon.agent/run ?run ?transaction false]
            [?agent :seon.agent/id ?agent-id _ true]
            [?run :seon.agent.run/id ?run-id _ true]]
          :seon.db/args [transaction]})
       (sort-by (juxt first second))
       vec))

(defn- interrupted-run-turns
  [history transaction]
  (->> (db/query
         {:seon.db/db history
          :seon.db/query
          '[:find ?run-id ?turn-id
            :in $ ?transaction
            :where
            [?turn :seon.agent.turn/status :interrupted ?transaction true]
            [?turn :seon.agent.turn/id ?turn-id _ true]
            [?turn :seon.agent.turn/run ?run _ true]
            [?run :seon.agent.run/id ?run-id _ true]]
          :seon.db/args [transaction]})
       (sort-by (juxt first second))
       vec))

(defn- later-run?
  [history agent-id recovery-transaction]
  (boolean
    (db/query
      {:seon.db/db history
       :seon.db/query
       '[:find ?run .
         :in $ ?agent-id ?recovery-transaction
         :where
         [?agent :seon.agent/id ?agent-id _ true]
         [?run :seon.agent.run/agent ?agent ?later-transaction true]
         [(> ?later-transaction ?recovery-transaction)]]
       :seon.db/args [agent-id recovery-transaction]})))

(defn pending-notices
  "Recovery facts whose affected agents still have no later run.

   Each result is derived from the anchor transaction's pointer retractions,
   interrupted-turn assertions, and the current history. Once an affected
   agent opens a later run it disappears from that notice; when none remain,
   the notice disappears. This is the root canvas/AI-twin read model."
  {:malli/schema
   [:=> [:cat ::pending-notices-request] ::pending-notices-response]}
  [{:seon.db/keys [db]}]
  (let [history (db/history db)]
    (->> (anchor-rows history)
         (sort-by second >)
         (keep
           (fn [[recovery-id transaction at]]
             (let [agent-runs (repaired-agent-runs history transaction)
                   pending-agent-runs
                   (remove
                     (fn [[agent-id _]]
                       (later-run? history agent-id transaction))
                     agent-runs)
                   pending-agent-ids (->> pending-agent-runs (map first) distinct vec)
                   pending-run-ids (->> pending-agent-runs (map second) distinct vec)
                   pending-run-set (set pending-run-ids)
                   pending-turn-ids
                   (->> (interrupted-run-turns history transaction)
                        (keep (fn [[run-id turn-id]]
                                (when (contains? pending-run-set run-id)
                                  turn-id)))
                        distinct
                        sort
                        vec)]
               (when (seq pending-agent-ids)
                 (let [anchor
                       (db/entity
                         {:seon.db/db db
                          :seon.db/ref
                          [:seon.runtime.recovery/id recovery-id]})]
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
                            (:seon.runtime.recovery/detail anchor))))))))
         vec)))
