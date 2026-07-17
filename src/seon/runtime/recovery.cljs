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
    [seon.db.protocol :as protocol]
    [seon.error :as error]
    [seon.eval.internal :as eval.internal]
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
(schema/register! ::eval-ids [:vector ::db.id/compact-value])
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

(defn- query-member
  [query]
  {::protocol/operation protocol/query-operation
   ::protocol/query-form query
   ::protocol/arguments []})

(defn- member-value!
  [operation member]
  (if (true? (::protocol/success? member))
    (:datahike.query/result member)
    (throw (ex-info (str "Recovery " operation " failed.")
                    {:seon.error/kind :core-bug
                     ::protocol/error (::protocol/error member)
                     ::protocol/error-kind (::protocol/error-kind member)}))))

(defn- ^:async acquire-recovery!
  [database]
  (let [acquired
        (await
         (db/execute-many
          {::db/db database
           ::db/members
           (mapv query-member
                 [repair-targets-query running-turns-query
                  running-evals-query recovery-policy-query])}))]
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
                        (eval.internal/terminal-tx-data
                         {:seon.eval/id eval-id
                          :seon.eval/status :interrupted})))
              eval-ids)
        anchor (cond->
                 {:seon.runtime.recovery/id recovery-id
                  :seon.runtime.recovery/reason :unexpected-exit}
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
   closes, `:interrupted` running turns, and one unexpected-exit anchor. A
   terminated agent is never touched. With no repair targets, performs no
   transaction and returns `::repaired? false`.

   The caller supplies root/boot transaction provenance. Database failures are
   returned as direct error values; no partial repair can commit."
  {:malli/schema [:=> [:catn [::request ::recover-request]]
                  ::recover-response]}
  [{:seon.runtime.recovery/keys [detail]}]
  (try
    (let [database (await (db/db))]
      (if (error-value? database)
        database
        (let [{::keys [targets generator] :as acquired}
              (await (acquire-recovery! database))]
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
                {::repaired? true
                 ::agent-ids (::agent-ids compiled)
                 ::run-ids (::run-ids compiled)
                 ::turn-ids (::turn-ids compiled)
                 ::eval-ids (::eval-ids compiled)
                 :seon.runtime.recovery/id recovery-id}))))))
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
