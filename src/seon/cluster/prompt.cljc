(ns seon.cluster.prompt
  "The prompt is a projection of the database, not a stored artifact.

  THE PROMPT FORMATTER IS A RENDER-UNIT APPLICATION, never a parallel
  system (context-blocks contract, sealed 2026-07-28). This namespace
  keeps exactly four jobs: block SELECTION (the agent's membership, AI
  declarations only), AI-contribution VALIDATION (string | nil | flat
  error), ORDERED REDUCTION, and the returned rendered-context value.
  Every prose piece it used to own is a named block whose stored
  `:seon.render/ai` symbol points at a `seon.context` projection
  through the ONE router — so replacing a block's projection symbol
  changes the next prompt with no edit here, and the same membership →
  unit → router derivation serves the prompt, the page, debug and the
  capture at one database value, one cap set, one snapshot.

  THE REQUEST NAMES THE HELD RUN — never a message id. The run's
  creating transaction recorded its exact trigger as `:seon.db/trigger`
  tx-meta and `seon.cluster.message/trigger` reads it back, so a later
  queued message can never replace the recorded cause (plan review
  finding 6, repaired).

  VALIDATION IS THE THREE-VALUED RULE (rulings 1 and 3, 2026-07-28):

  - a STRING contribution is admitted against the request's caps — the
    one bound; a projection cannot flood the prompt;
  - NIL contributes no text and no record — nil-punning omission, and
    an empty string is the same nothing;
  - a FLAT ERROR VALUE contributes a bounded, block-named statement —
    the agent is told its context is incomplete, never silently handed
    a shorter prompt — and the record carries the flat value exactly.

  The returned text is EXACTLY the reduction of the contribution texts
  joined by \"\\n\\n\"; no consumer reruns a projection to reconstruct
  metadata — capture, debug and token accounting all read the records.

  Nothing about this is stored, so nothing about it can go stale: every
  sentence is present exactly while the facts that cause it are."
  (:require [clojure.string :as str]
            [seon.cluster.message :as message]
            [seon.context :as context]
            [seon.render :as render]
            [seon.render.block :as block]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/prompt.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Validation — string | nil | flat error
;;; ---------------------------------------------------------------------------

(defn- refuse!
  [rule data]
  (throw (ex-info (str "prompt refused: " (name rule))
                  (assoc data :seon.error/kind ::refused ::rule rule))))

(defn- bound
  "One string, bounded by the request's one cap set. The SAME admission
  codec that bounds eval results and generic panels — a second set of
  size dials here would drift from the first."
  [caps text]
  (:seon.sci.admit/value
   (admit/admit {:seon.sci.admit/value text
                 :seon.sci.admit/caps caps
                 ;; nothing is armed: this is not an eval, and
                 ;; admission's bounds are the whole guard here
                 :seon.sci.admit/interrupt-fn (fn [])
                 :seon.config/on-core-error :record})))

(defn- contribution
  "One block's validated AI contribution record, or nil for omission.
  Text is ALWAYS present on a record — a failed block contributes a
  bounded, block-named statement rather than silence (silent omission
  is confabulation fuel); `:seon.error/value` presence IS \"failed\"."
  [caps block rendered]
  (let [name (:seon.render.block/name block)
        declaration (get block :seon.render/ai)
        output (get rendered :seon.render/output)
        failure (cond
                  (:seon.error/kind rendered) rendered

                  ;; a projection may FAIL BY VALUE: a returned closed
                  ;; flat error rides through EXACTLY — the record
                  ;; names its block but adds no key to that shape
                  (schema/valid-candidate-value? :seon.error/value output)
                  output

                  ;; the one check the router cannot make: the ai
                  ;; kind's grammar is prose, and this is its consumer
                  (and (some? output) (not (string? output)))
                  {:seon.error/kind ::not-text
                   :seon.error/message
                   (str "The " name " block's ai render returned something "
                        "that is not text.")
                   :seon.error/data
                   {:seon.render.block/name name
                    ::shape #?(:clj (.getName (class output))
                               :cljs (pr-str (type output)))}}

                  :else nil)
        text (cond
               failure (bound caps
                              (str "The " name " context block failed to "
                                   "render, so your context is incomplete "
                                   "here: " (:seon.error/message failure)))
               ;; nil-punning omission — and an empty string is the
               ;; same nothing (a record's text is `{:min 1}` by seal)
               (or (nil? output) (str/blank? output)) nil
               :else (bound caps output))]
    (when text
      (cond-> {:seon.render.block/name name
               :seon.render/kind :seon.render/ai
               :seon.context.contribution/text text
               :seon.context.contribution/hash (context/contribution-hash text)
               :seon.context.contribution/tokens
               (context/contribution-tokens text)
               :seon.context.contribution/band
               (get block :seon.render.block/band :dynamic)}
        (qualified-symbol? declaration)
        (assoc :seon.render/projection declaration)
        failure
        (assoc :seon.error/value failure)))))

;;; ---------------------------------------------------------------------------
;;; Contract
;;; ---------------------------------------------------------------------------

(defn prompt
  "Derive the rendered context for the agent holding the request's run.
  Selection → one router request per AI-declaring
  block in membership order → validation (string | nil | flat error) →
  ordered reduction.

  - The trigger is the HELD RUN's recorded cause: `message/trigger`
    reads the run's creating transaction's `:seon.db/trigger`. Refuses
    `::no-trigger` when that transaction names none — a prompt with
    nothing to answer is a caller bug, not an agent outcome.
  - Refuses at request construction (`assert-inputs!`) when the
    membership declares a trusted input the request omits — before any
    projection runs.

  Returns {text, contributions, db}. The text is EXACTLY the reduction
  of the contribution texts joined by \"\\n\\n\"."
  {:malli/schema [:=> [:cat :any :seon.cluster.prompt/request]
                  :seon.cluster.prompt/rendered-context]}
  [db request]
  (let [run-id (:seon.cluster.run/id request)
        agent-id (:seon.cluster.agent/id request)
        caps (:seon.sci.admit/caps request)
        _ (or (message/trigger db run-id)
              (refuse! ::no-trigger request))
        candidates (block/membership db agent-id)
        _ (block/assert-inputs! candidates (assoc request :seon.db/db db))
        unit-request (merge {:seon.db/db db}
                            (select-keys request
                                         [:seon.cluster.agent/id
                                          :seon.sci.admit/caps
                                          ;; the ONE render request's
                                          ;; distance, threaded exactly
                                          ;; as caps are (owner ruling
                                          ;; 2026-07-28 post-midnight).
                                          ;; The reduction is unchanged;
                                          ;; this is the same optional
                                          ;; parameter the surfaces,
                                          ;; page and expansion requests
                                          ;; already carry, reaching the
                                          ;; one unit builder so a
                                          ;; projection is CALLED with
                                          ;; the hops it may spend.
                                          :seon.render/distance
                                          :seon.cluster.run/live-processes]))
        records
        (into []
              (comp
               ;; A DECLARATION DECIDES PLACEMENT — an html-only widget
               ;; costs the prompt zero tokens.
               (filter (fn [candidate]
                         (render/declaration?
                          (get candidate :seon.render/ai))))
               (keep (fn [candidate]
                       ;; the unit for `:trigger` must carry the held
                       ;; run id: one more qualified key on the open
                       ;; unit map, read by `get`
                       (contribution
                        caps candidate
                        (render/render
                         {:seon.render/unit
                          (assoc (block/unit unit-request candidate)
                                 :seon.cluster.run/id run-id)
                          :seon.render/kind :seon.render/ai})))))
              candidates)
        contributions
        (into []
              (map-indexed
               (fn [position record]
                 (assoc record
                        :seon.context.contribution/position (long position))))
              records)]
    {:seon.cluster.prompt/text
     (str/join "\n\n" (map :seon.context.contribution/text contributions))
     :seon.context/contributions contributions
     :seon.db/db db}))
