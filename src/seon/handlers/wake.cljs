(ns seon.handlers.wake
  "Substrate handler: wake-on-message.

   When a `:seon.message/to <agent-id>` datom lands, emit a `:wake`
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
    [seon.db :as db]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — the message-to attr the wake handler matches on, plus
;; the related `:seon.message/from` projection. Vector of refs so
;; one user message can fan out to N agents (the spec's mental model).
;;
;; `:seon.message/to` lives in this file (not seon.agent.cljs) because
;; it's the *trigger* attribute for the wake handler — the handler
;; OWNS the schema for the attr it depends on. The existing
;; `:seon.message/agent` (single ref, set by `chat` for user msgs) is
;; preserved unchanged so existing flows keep working.
;; ============================================================

(schema/register! :seon.message/to
  [:vector :seon.db/ref])

(schema/register! :seon.message/from
  [:or :keyword :seon.db/ref])

(defn ^:async bootstrap-schema!
  "Declare the datahike schema for the message-trigger attrs the wake
   handler depends on. Idempotent — datahike upserts on `:db/ident`.
   `:seon.message/from` is stored as keyword for v0 (the spec allows
   `:keyword | :seon.db/ref` but v0 only flows the keyword form
   `:user`)."
  []
  (await (datahike.api/transact! seon.db/*conn*
           {:tx-data
            [{:db/ident :seon.message/to
              :db/valueType :db.type/ref
              :db/cardinality :db.cardinality/many}
             {:db/ident :seon.message/from
              :db/valueType :db.type/keyword
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
  "Substrate handler. For each new `:seon.message/to <agent-ref>`
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
  (let [to-datoms (or (get attr-index :seon.message/to)
                      (filter #(= :seon.message/to (:seon.db/a %))
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
