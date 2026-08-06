(ns seon.context
  "The compiled core AI projections, and the pre-provider capture.

  THE PROMPT'S PROSE LIVES HERE NOW, one named block projection per
  piece (context-blocks contract §3.5, sealed 2026-07-28) — and it is
  SHRINKING TOWARD THE SCAFFOLD, which is the 2026-07-28 post-midnight
  ruling working as intended. What survives here says what an agent
  cannot see by looking: who it is, how its reply is evaluated, who its
  peers are, which message this run answers. What LEFT — `interruption-ai`
  and `continuity-ai` — restated neighbourhood facts, and now lives in
  the lenses of the families that own those facts
  (`seon.cluster.run/render-ai`, `render-receipt-ai`), where a page, a
  debug view and another agent's neighbourhood are told the same true
  thing by the same function instead of only the prompt.
  `seon.cluster.prompt` keeps only selection, validation, ordered
  reduction and the returned rendered-context value; every sentence it
  used to own is a block whose stored `:seon.render/ai` symbol points
  at one of these functions through the ONE router. Replacing a
  block's projection symbol changes the next prompt with no edit to
  the prompt, a route, or a page consumer — the structural falsifier.

  EACH PROJECTION IS PURE OVER ITS UNIT and returns `[:maybe :string]`
  (in-memory return — ruling 1, 2026-07-28: nil is omission, read with
  `get`, never `contains?`). Each owns both its facts-query and its
  guidance — the colocation rule — so the query and the prose that
  explains it can never drift. The stored block symbol is the
  authority; a projection may later move beside its facts owner
  (`seon.problems/block` already models that) with only a data edit.

  THE CAPTURE is ruling 4: the exact prompt text, the rendered database
  basis, the ordered contribution records and the trusted-input
  snapshot commit in ONE turn-owned transaction BEFORE the unobservable
  remote call. `capture-tx` is PURE — tx-data out, the LOOP commits —
  and identity is derived from provenance (`<run-id>-context-<basis-t>`),
  so re-deriving the same prompt at the same basis upserts rather than
  double-writing, and a released run re-entering `:call` at a new basis
  creates the new capture the honestly-different prompt deserves.

  Crash walk: everything here is pure. The capture's crash story is the
  loop's (contract §5): kill before the capture commits — no capture,
  no attempt, a plain interrupted run; kill between capture and attempt
  — capture with no attempt row, evidence the call may never have
  fired; kill after — today's attempt-row story. Nothing re-executes."
  (:require [seon.ai.tokens :as tokens]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Message custody in one run's rendered context
;;; ---------------------------------------------------------------------------

(defn message-custody
  "Classify one message relative to the run whose prompt is being rendered.

  The run's recorded trigger is its current instruction. An inbound message
  to the same agent with no run pointing at it is pending work, even when it
  arrived later and therefore interleaves honestly in the transcript. All
  other messages are history. This is derived from refs on the supplied
  database value; no prompt-local flag or message status is stored."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:maybe :seon.cluster.run/id]
         :seon.cluster.agent/id
         :int]
    :keyword]}
  [database run-id agent-id message-eid]
  (if-not run-id
    ::history
    (let [current-trigger?
          (some?
           (db/q '[:find ?run .
                   :in $ ?run-id ?message
                   :where
                   [?run :seon.cluster.run/id ?run-id]
                   [?run :seon.cluster.run/trigger ?message]]
                 database run-id message-eid))
          inbound?
          (some?
           (db/q '[:find ?message .
                   :in $ ?agent-id ?message
                   :where
                   [?agent :seon.cluster.agent/id ?agent-id]
                   [?message :seon.cluster.message/to ?agent]]
                 database agent-id message-eid))
          claimed?
          (some?
           (db/q '[:find ?run .
                   :in $ ?message
                   :where
                   [?run :seon.cluster.run/trigger ?message]]
                 database message-eid))]
      (cond
        current-trigger? ::current-trigger
        (and inbound? (not claimed?)) ::pending
        :else ::history))))

;;; ---------------------------------------------------------------------------
;;; The pre-provider capture
;;; ---------------------------------------------------------------------------

(defn capture-ai
  "Omit a recorded prompt from later AI context.

  A capture is durable evidence of what an earlier turn saw, not new context
  for a later turn. The walk remains total and still visits this entity; this
  family lens alone decides that the AI projection has nothing relevant to
  say."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [_unit]
  nil)

(defn capture-html
  "Expose a recorded prompt in one compact debug disclosure."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [prompt (:seon.context.capture/prompt unit)]
    [:details {:class "seon-family-entry seon-context-capture-entry"}
     [:summary
      (str "Context capture at database basis "
           (:seon.context.capture/basis-t unit)
           " — approximately " (tokens/estimate prompt) " tokens")]
     [:pre {:class "seon-context-capture-prompt"} prompt]]))

(defn- contribution-row
  "One durable contribution row: evidence, not content. No stored output tag
  (a prompt capture is necessarily AI), no stored text (hash + position + the prompt blob
  reconstruct it). A FAILED contribution is presence of the error keys."
  [capture-id record]
  (let [position (:seon.context.contribution/position record)
        failure (get record :seon.error/value)]
    (cond-> {:seon.context.contribution/id (str capture-id "-" position)
             :seon.context.contribution/position position
             :seon.render.block/name (:seon.render.block/name record)
             :seon.context.contribution/hash
             (:seon.context.contribution/hash record)
             :seon.context.contribution/tokens
             (:seon.context.contribution/tokens record)}
      failure
      (assoc :seon.error/kind (:seon.error/kind failure)
             :seon.context.contribution/error (:seon.error/message failure)))))

(defn capture-tx
  "Transaction data for one context capture. PURE — the loop commits.
  Derives the capture id from (run-id, basis-t of the rendered db
  value), one component contribution row per in-memory record carrying
  position/name/hash/tokens/band/projection (+ error kind and bounded
  message when failed), the exact prompt text, and the live-process
  snapshot when the request carried one. Idempotent by derived
  identity: re-deriving the same prompt at the same basis upserts,
  never double-writes."
  {:malli/schema [:=> [:cat :seon.context/capture-request]
                  :seon.store/transaction-data]}
  [request]
  (let [{run-id :seon.cluster.run/id
         rendered :seon.cluster.prompt/rendered-context
         live :seon.cluster.run/live-processes} request
        db (:seon.db/db rendered)
        basis-t (long (:max-tx db))
        capture-id (str run-id "-context-" basis-t)]
    [(cond-> {:seon.context.capture/id capture-id
              :seon.context.capture/run [:seon.cluster.run/id run-id]
              :seon.context.capture/basis-t basis-t
              :seon.context.capture/prompt (:seon.cluster.prompt/text rendered)
              :seon.context.capture/contributions
              (mapv (fn [record] (contribution-row capture-id record))
                    (:seon.context/contributions rendered))}
       live (assoc :seon.cluster.run/live-processes live))]))

;;; ---------------------------------------------------------------------------
;;; The one digest seam
;;; ---------------------------------------------------------------------------

;; `seon.cluster.prompt` builds the in-memory records and owns token
;; estimation. The capture owner derives only the durable digest evidence.

(defn contribution-hash
  "SHA-256 hex of the contribution's exact UTF-8 text — the one digest
  owner (`schema/sha-256`) applied to the one evidence field."
  {:malli/schema [:=> [:cat :string] :seon.context.contribution/hash]}
  [text]
  (schema/sha-256 [(.getBytes ^String text "UTF-8")]))
