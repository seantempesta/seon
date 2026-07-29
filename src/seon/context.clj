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
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ai.tokens :as tokens]
            [seon.cluster.message :as message]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.eval :as sci.eval]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/context.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The core projections — each present exactly while its facts are
;;; ---------------------------------------------------------------------------

(defn identity-ai
  "Who the agent is and where its defns land. Always present.

  The namespace is stated as a fact about where evaluation ALREADY
  happens (`seon.sci.eval/agent-namespace`, the one derivation shared
  with the evaluator), never as something to arrange: the first live
  drive's model read `your namespace is X` and dutifully emitted
  `(in-ns X)`, which failed. If the prompt and the evaluator ever
  disagreed here the agent would be told a lie it then reasons from."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (let [agent-id (get unit :seon.cluster.agent/id)]
    (str "You are agent " agent-id
         ". Your namespace is " (sci.eval/agent-namespace agent-id)
         " — defns you write land there.")))

(defn peers-ai
  "Every OTHER agent in this cluster, and how to reach one.

  Derived on every prompt, so an agent created a minute ago is
  addressable a minute later and one that never existed is never named.
  Nil when the agent is alone — the sentence is present exactly while
  the facts that cause it are.

  THE EXAMPLE USES A REAL ID, and that is not polish. The first live
  drive's model read \"your namespace is my.agents.alice\" two lines
  above \"other agents: bob\" and wrote
  `(my.message/send \"my.agents.bob\" …)` — an agent this cluster does
  not have. A prompt that shows the agent the exact string to pass
  cannot be read that way."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (let [db (get unit :seon.db/db)
        agent-id (get unit :seon.cluster.agent/id)
        others (->> (d/q '[:find [?id ...]
                           :where [?agent :seon.cluster.agent/id ?id]]
                         db)
                    (remove #{agent-id})
                    sort
                    vec)]
    (when (seq others)
      (str "Other agents in this cluster, by id: "
           (str/join ", " others)
           ". To ask one for something, return "
           "(my.message/send \"" (first others)
           "\" \"what you want to say\") from a form — that "
           "delivers it and wakes them. Use the bare id exactly as "
           "listed above; it is not a namespace. Their answer "
           "comes back to you later as a new request, so pause "
           "with my.run/wait after asking. Return a vector of "
           "sends to message several."))))

(defn trigger-ai
  "What the agent was asked, and WHO asked when the sender is another
  agent. The unit carries the HELD RUN's id, and the trigger is the
  run's recorded cause — the creating transaction's `:seon.db/trigger`
  read back by `seon.cluster.message/trigger` — so a later queued
  message can never displace it.

  A message with no `from` came from outside the population (the human,
  or the error recorder), and then the prompt says \"you have been
  asked\" rather than inventing a sender. Nil only when the unit names
  no run or the run's transaction names no trigger — the prompt path
  refused `::no-trigger` before this projection ever ran."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (let [db (get unit :seon.db/db)
        run-id (get unit :seon.cluster.run/id)]
    (when-let [message-id (and run-id (message/trigger db run-id))]
      (let [content (d/q '[:find ?content .
                           :in $ ?message-id
                           :where
                           [?message :seon.cluster.message/id ?message-id]
                           [?message :seon.cluster.message/content ?content]]
                         db message-id)
            sender (d/q '[:find ?agent-id .
                          :in $ ?message-id
                          :where
                          [?message :seon.cluster.message/id ?message-id]
                          [?message :seon.cluster.message/from ?agent]
                          [?agent :seon.cluster.agent/id ?agent-id]]
                        db message-id)]
        (when content
          (if sender
            (str "Agent " sender " sent you:\n\n" content)
            (str "You have been asked:\n\n" content)))))))

(defn execution-ai
  "The reply grammar: how this agent's answer is evaluated, and the
  dispositions available to it. Always present — an agent that is never
  told the grammar cannot end a run on purpose."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [_unit]
  (str "Reply with Clojure forms to run, in order. "
       "Finish with (my.run/complete \"your reply\") when you are "
       "done, or (my.run/wait \"why\") to pause this run — pause "
       "when you are waiting on another agent, and put everything "
       "you will need to finish into the note, because your next "
       "run starts fresh and that note is what it reads."))

;;; ---------------------------------------------------------------------------
;;; The pre-provider capture
;;; ---------------------------------------------------------------------------

(defn- contribution-row
  "One durable contribution row: evidence, not content. No stored kind
  (constant `:seon.render/ai` on a prompt capture — a stored
  derivation), no stored text (hash + position + the prompt blob
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
             (:seon.context.contribution/tokens record)
             :seon.context.contribution/band
             (:seon.context.contribution/band record)}
      (:seon.render/projection record)
      (assoc :seon.render/projection (:seon.render/projection record))
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
  {:malli/schema [:=> [:cat :seon.context/capture-request] [:vector :any]]}
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
;;; The one estimator seam, re-exported nowhere — contributions call it
;;; ---------------------------------------------------------------------------

;; `seon.cluster.prompt` builds the in-memory records (it owns position
;; and reduction); the hash and token derivations it uses are
;; `seon.schema/sha-256` and `seon.ai.tokens/estimate` — named here so
;; the capture row's two evidence fields have exactly one owner each.

(defn contribution-hash
  "SHA-256 hex of the contribution's exact UTF-8 text — the one digest
  owner (`schema/sha-256`) applied to the one evidence field."
  {:malli/schema [:=> [:cat :string] :seon.context.contribution/hash]}
  [text]
  (schema/sha-256 [(.getBytes ^String text "UTF-8")]))

(defn contribution-tokens
  "ESTIMATED tokens of one contribution's text (house rule:
  human-visible sizes are estimated tokens, never raw characters)."
  {:malli/schema [:=> [:cat :string] :seon.context.contribution/tokens]}
  [text]
  (tokens/estimate text))
