(ns seon.handlers.wake
  "Core handler: wake-on-message.

   When a `:seon.agent.message/to <agent-id>` datom lands, emit a `:wake`
   effect for each target agent. The actual wake interpreter
   (`run-agent-loop!` re-entry, ALS rebinding) lives in
   `seon.runtime` and isn't part of this v0 step — we only produce
   the effect descriptor.

   In v0 the dispatcher does not auto-fire (no `d/listen!` bus yet);
   this fn is invoked manually from the REPL or from
   `seon.runtime/dispatch-tx!` in a follow-up step. The SHAPE is what
   matters — handler fn takes the dispatcher's input map and returns
   `{:effects [...]}`."
  (:require
    [datahike.api]
    [seon.db :as db]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — `:seon.agent.message/from` / `:seon.agent.message/to` are owned by
;; `seon.agent` (the `:seon.agent.message` kind owner) since the from/to
;; migration (unit 1.5, 2026-06-09): from is a single ref (the sender
;; entity — user or agent), to is a vector of refs so one message can
;; fan out to N agents. This handler only CONSUMES `:seon.agent.message/to`.
;; ============================================================

(defn ^:async bootstrap-schema!
  "Declare the datahike schema for the message-trigger attrs the wake
   handler depends on. Idempotent — datahike upserts on `:db/ident`.
   Both attrs are refs (from/to migration — identity is the ref)."
  []
  (await (datahike.api/transact! seon.db/*conn*
           {:tx-data
            [{:db/ident :seon.agent.message/to
              :db/valueType :db.type/ref
              :db/cardinality :db.cardinality/many}
             {:db/ident :seon.agent.message/from
              :db/valueType :db.type/ref
              :db/cardinality :db.cardinality/one}]}))
  {:seon.handlers.wake/bootstrapped? true})

;; ============================================================
;; Handler input — what the dispatcher hands to the handler fn.
;; Mirrors the unified-loop-v1.md §2.3 contract (handler receives
;; `{:seon.db/db, :seon.db/tx-report, :seon.agent/id}` or — in v0 —
;; the post-commit db + an explicit set of new datoms).
;; ============================================================

(schema/register! :seon.handler/input
  [:map
   [:seon.db/db         :any]
   [:seon.db/tx-report  {:optional true} :any]
   [:seon.db/attr-index {:optional true} :map]
   [:seon.agent/id      {:optional true} :string]])

(schema/register! :seon.effect/wake-request
  [:map
   [:seon.effect/fn  :symbol]
   [:seon.agent/id   :string]])

(schema/register! :seon.handler/output
  [:map
   [:tx       {:optional true} :any]
   [:effects  {:optional true} [:vector :map]]])

;; ============================================================
;; The handler itself.
;; ============================================================

(defn wake-on-message
  "Core handler. For each new `:seon.agent.message/to <agent-ref>`
   datom in the tx, emit a `:wake` effect descriptor naming the
   target agent. The dispatcher's effect interpreter handles the
   actual wake (setTimeout + with-agent re-entry — v0 leaves this
   for `seon.runtime`).

   Input map keys (only the ones this handler reads):
     :seon.db/db          post-commit db value
     :seon.db/attr-index  pre-grouped {attr [datom ...]} (cheap lookup)
     :seon.db/datoms      flat datoms vec (fallback when attr-index absent)

   Returns `{:effects [{:seon.effect/fn 'seon.effects/wake
                        :seon.agent/id '<id>'} ...]}`."
  {:malli/schema [:=> [:cat :seon.handler/input]
                       :seon.handler/output]}
  [{:seon.db/keys [db attr-index datoms]}]
  (let [to-datoms (or (get attr-index :seon.agent.message/to)
                      (filter #(= :seon.agent.message/to (:seon.db/a %))
                              (or datoms [])))
        added-to  (filter :seon.db/added? to-datoms)
        ;; Each datom's :v is the eid of the target agent entity.
        target-ids (->> added-to
                        (keep (fn [{eid :seon.db/v}]
                                (:seon.agent/id
                                  (db/entity {:seon.db/db db :seon.db/ref eid}))))
                        distinct
                        vec)]
    {:effects
     (mapv (fn [aid]
             {:seon.effect/fn 'seon.effects/wake
              :seon.agent/id  aid})
           target-ids)}))
