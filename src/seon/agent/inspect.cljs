(ns seon.agent.inspect
  "Agent self-introspection: 'what am I seeing right now?'

   Two verbs:
     - `ctx-preview` — the FULL prompt the agent would receive on its
       next render: the HARDCODED system block FIRST (read via the SAME
       fn the adapters call, `seon.ai/effective-system-prompt` — the
       system-specific mechanics, NOT the soul/any file), then the
       assembled AI-context via `seon.agent.ctx/render-context` — the SINGLE
       producer the loop's prompt path (`seon.agent.turn/render-prompt`)
       also routes through, over the SAME unfiltered `@*conn*`. The
       `:seon.render/text` is byte-identical to what the LLM receives
       (system message + context), with an explicit boundary between
       them. Per-section texts (left pane) lead with the system block;
       the per-section html twins (right pane) mirror the context
       sections only (which now include the SOUL.md / AGENTS.md
       file-sections). System block + context derive from the same
       sources the real call uses, so divergence is impossible.
     - `handlers` — the live handler registry visible to the agent
       (core + per-agent).
     - `turn` / `turn-diff` — turn replay: reconstruct any persisted
       turn from its `:seon.agent.turn/rendered-as-of` basis-t + prompt
       and reply blobs; diff two turns (tokens + basis-t delta).

   All map-in, map-out. Defaults `:seon.agent/id` to
   `(seon.db/current-agent-id)` so REPL calls from inside an agent
   scope work with no argument."
  (:require
    [clojure.string :as str]
    [my.blob :as blob]
    [seon.ai :as ai]
    [seon.ai.tokens :as tokens]
    [seon.agent.ctx :as ctx]
    [seon.agent.turn]
    [seon.db :as db]
    [seon.schema :as schema]))

(schema/register! :seon.agent.inspect/request
  [:map [:seon.agent/id {:optional true} :string]])

;; One rendered section of the assembled context — name + the exact
;; text that section contributed to the joined prompt. Consumed by the
;; inspector's left pane so static sections can collapse per-section
;; instead of re-showing the full static bulk on every view.
(schema/register! :seon.agent.inspect/section-text
  [:map
   [:seon.agent.ctx/name :seon.agent.ctx/name]
   [:seon.render/text :string]])

(schema/register! :seon.agent.inspect/ctx-response
  [:map
   [:seon.render/text :string]
   [:seon.render/section-texts [:vector :seon.agent.inspect/section-text]]
   [:seon.render/section-html [:vector :seon.agent.ctx/block-html]]
   [:seon.render/token-estimate :int]])

(defn- resolve-id
  [id]
  (or id
      (db/current-agent-id)
      (throw (ex-info
               "seon.agent.inspect: no agent-id — pass :seon.agent/id or call inside (seon.db/with-agent id ...)."
               {:seon.agent.inspect/error :no-agent-id}))))

(defn ctx-preview
  "Return the FULL prompt the agent would see on its next render.

   The EXACT bytes the LLM receives: the HARDCODED system block FIRST, then
   the assembled context. The system block is read via the SAME fn the
   adapters call (`seon.ai/effective-system-prompt` — the system-specific
   seon mechanics, NOT the soul/any file; explicit-override logic), so
   the debug text is byte-identical to the real system message; the
   context comes from `seon.agent.ctx/context-root` → render (and now CARRIES
   the SOUL.md / AGENTS.md file-sections). Divergence is impossible — both
   surfaces derive from the same sources the real call uses.
   `:seon.render/text` = system + boundary + context.
   `:seon.render/section-texts` leads with a `:system` section (left pane
   shows the system message too); `:seon.render/section-html` mirrors the
   context section twins only (the system block is the system message,
   not a context section). `:seon.render/token-estimate` counts the WHOLE
   prompt (system included). Renders against the live `@*conn*` — the SAME
   unfiltered db value the loop renders the prompt over — so the two are
   byte-identical (no per-agent `d/filter` divergence)."
  {:malli/schema [:=> [:cat :seon.agent.inspect/request] :seon.agent.inspect/ctx-response]}
  [{:seon.agent/keys [id]}]
  (let [id  (resolve-id id)
        ;; THE SAME db the prompt path renders against — the live cluster
        ;; conn, UNFILTERED. The loop renders the prompt over `@*conn*`
        ;; ([[seon.agent.ctx/render-context]] / `render-prompt`); the inspector
        ;; must use the SAME db value or it would not be byte-identical (and
        ;; the old per-agent `d/filter` actively DROPPED inbound peer-message
        ;; content whose datom lived in the peer's tx — the inspector lied).
        db  @db/*conn*
        ctx {:seon.agent/id id :seon.db/db db}
        ;; THE SAME single producer the prompt path uses — both route
        ;; through `seon.agent.ctx/render-context`, so the LLM prompt and this
        ;; human inspector are byte-identical by construction.
        text          (ctx/render-context ctx)
        ;; Per-section breakdown for the panes, derived from the SAME root +
        ;; render (left pane folds per section; right pane one html card per
        ;; renderable).
        {:seon.render/keys [section-texts section-html]} (ctx/ctx-sections ctx)
        ;; Block 1 — the hardcoded system message, via the EXACT fn the
        ;; adapters call (no re-implementation, no drift). No override is
        ;; passed, so this returns the system-specific seon mechanics —
        ;; the normal call's system message.
        system        (ai/effective-system-prompt {})
        ;; The FULL prompt = system + boundary + context, via the SAME fn
        ;; the adapters call so the two debug surfaces can't drift.
        full-text     (ai/debug-full-prompt {:seon.ai/ctx text})]
    {:seon.render/text            full-text
     :seon.render/section-texts   (into [{:seon.agent.ctx/name     :system
                                          :seon.render/text  system}]
                                        section-texts)
     :seon.render/section-html    section-html
     ;; Estimate over the WHOLE prompt — same units as the composer
     ;; (~4 chars/token, via seon.ai.tokens), so the count grows by the
     ;; system-block length.
     :seon.render/token-estimate  (tokens/estimate full-text)}))

;;; ============================================================
;;; Turn replay — reconstruct any persisted turn from its capture:
;;; rendered-as-of (the frozen basis-t), the prompt blob, the reply blob,
;;; and the tx trail (the :seon.db/turn-id tx-meta join). Errors are
;;; values throughout — an unknown id / missing blob is a guiding map.
;;; ============================================================

(schema/register! ::ok?           :boolean)
(schema/register! ::error         :string)
(schema/register! ::prompt        :string)
(schema/register! ::reply         :string)
(schema/register! ::prompt-tokens :int)
(schema/register! ::reply-tokens  :int)
(schema/register! ::txs           [:vector :int])

(schema/register! ::turn-request
  [:map [:seon.agent.turn/id :seon.agent.turn/id]])

(schema/register! ::turn-response
  [:map
   [::ok? ::ok?]
   [:seon.agent.turn/id :seon.agent.turn/id]
   [::error                          {:optional true} ::error]
   [:seon.agent.turn/status         {:optional true} :seon.agent.turn/status]
   [:seon.agent.turn/at             {:optional true} :seon.agent.turn/at]
   [:seon.agent.turn/rendered-as-of {:optional true} :seon.agent.turn/rendered-as-of]
   [:seon.agent.turn/error          {:optional true} :seon.agent.turn/error]
   [::prompt        {:optional true} ::prompt]
   [::prompt-tokens {:optional true} ::prompt-tokens]
   [::reply         {:optional true} ::reply]
   [::reply-tokens  {:optional true} ::reply-tokens]
   [::txs           {:optional true} ::txs]])

(defn- turn-eid
  "The entity id stored under a turn id, or nil — query, never a
   lookup-ref (safe on a store where no turn has landed yet)."
  [turn-id]
  (db/query '[:find ?e . :in $ ?id :where [?e :seon.agent.turn/id ?id]]
            turn-id))

(defn- blob-text
  "Read a captured blob ref's content back — `{::k text ::k-tokens n}`
   under the given keys, `{}` when the turn has no such ref, or an
   `::error` note naming the blob when the ref exists but won't read."
  [pulled ref-attr text-key tokens-key]
  (if-let [hash (get-in pulled [ref-attr :my.blob/hash])]
    (let [{:my.blob/keys [ok? content tokens error]}
          (blob/get {:my.blob/hash hash})]
      (if ok?
        {text-key content tokens-key tokens}
        {::error (str (name ref-attr) " blob " hash " unreadable: " error)}))
    {}))

(defn turn
  "Reconstruct one persisted turn: basis-t, verbatim prompt, raw reply.

   Map-in/map-out — `{:seon.agent.turn/id id}` returns the turn's
   `:seon.agent.turn/rendered-as-of` (re-derive its whole structured
   context with `(db/as-of conn t)`), the VERBATIM prompt and raw reply
   read back from their blobs (with token estimates), the turn's stored
   error (when it errored), and `::txs` — every tx this turn wrote (the
   `:seon.db/turn-id` tx-meta join). An unknown id or unreadable blob
   returns `::ok? false` plus a guiding `::error`; nothing throws."
  {:malli/schema [:=> [:cat ::turn-request] ::turn-response]}
  [{turn-id :seon.agent.turn/id}]
  (if-let [eid (turn-eid turn-id)]
    (let [t (db/pull {:seon.db/pull-pattern
                      [:seon.agent.turn/id :seon.agent.turn/at
                       :seon.agent.turn/status :seon.agent.turn/rendered-as-of
                       :seon.agent.turn/error
                       {:seon.agent.turn/prompt-blob [:my.blob/hash]}
                       {:seon.agent.turn/reply-blob  [:my.blob/hash]}]
                      :seon.db/ref eid})
          p (blob-text t :seon.agent.turn/prompt-blob ::prompt ::prompt-tokens)
          r (blob-text t :seon.agent.turn/reply-blob  ::reply  ::reply-tokens)
          txs (vec (sort (db/query '[:find [?tx ...] :in $ ?tid
                                     :where [?tx :seon.db/turn-id ?tid]]
                                   turn-id)))
          errs (keep ::error [p r])]
      (cond-> (merge (select-keys t [:seon.agent.turn/id :seon.agent.turn/at
                                     :seon.agent.turn/status
                                     :seon.agent.turn/rendered-as-of
                                     :seon.agent.turn/error])
                     (dissoc p ::error)
                     (dissoc r ::error)
                     {::ok? (empty? errs) ::txs txs})
        (seq errs) (assoc ::error (str/join "; " errs))))
    {::ok? false
     :seon.agent.turn/id turn-id
     ::error (str "no turn stored under " (pr-str turn-id))}))

;;; turn-diff — what changed between two turns, as a summary an agent can
;;; budget on: basis-t delta (how many txs advanced the world between the
;;; two renders) + a token/line summary of the prompt drift + both replies.

(schema/register! ::from :seon.agent.turn/id)
(schema/register! ::to   :seon.agent.turn/id)

(schema/register! ::turn-diff-request
  [:map [::from ::from] [::to ::to]])

(schema/register! ::basis-t-delta        :int)
(schema/register! ::prompt-token-delta   :int)
(schema/register! ::prompt-lines-added   :int)
(schema/register! ::prompt-lines-removed :int)

(schema/register! ::turn-diff-response
  [:map
   [::ok? ::ok?]
   [::error {:optional true} ::error]
   [::from-turn {:optional true} ::turn-response]
   [::to-turn   {:optional true} ::turn-response]
   [::basis-t-delta        {:optional true} ::basis-t-delta]
   [::prompt-token-delta   {:optional true} ::prompt-token-delta]
   [::prompt-lines-added   {:optional true} ::prompt-lines-added]
   [::prompt-lines-removed {:optional true} ::prompt-lines-removed]])

(defn- line-delta
  "Multiset line diff `from` → `to`: `[added removed]` line counts."
  [from to]
  (let [f (frequencies (str/split-lines (or from "")))
        t (frequencies (str/split-lines (or to "")))
        ks (into (set (keys f)) (keys t))]
    (reduce (fn [[a r] k]
              (let [d (- (get t k 0) (get f k 0))]
                [(+ a (max 0 d)) (+ r (max 0 (- d)))]))
            [0 0] ks)))

(defn turn-diff
  "What changed between two persisted turns — a budgetable summary.

   Map-in/map-out — `{::from id ::to id}` returns both [[turn]] bundles
   plus: `::basis-t-delta` (txs the world advanced between the two
   renders — `rendered-as-of` distance), `::prompt-token-delta` (tokens,
   the ONE estimator), and a multiset line summary of the prompt drift
   (`::prompt-lines-added` / `-removed` — cache-stability instrument:
   frozen bytes that moved show up here). `::ok? false` with a guiding
   `::error` when either turn is missing; partial capture degrades to
   the fields both sides carry."
  {:malli/schema [:=> [:cat ::turn-diff-request] ::turn-diff-response]}
  [{::keys [from to]}]
  (let [ft (turn {:seon.agent.turn/id from})
        tt (turn {:seon.agent.turn/id to})]
    (if (or (nil? (turn-eid from)) (nil? (turn-eid to)))
      {::ok? false
       ::error (str/join "; " (keep ::error [ft tt]))}
      (let [[added removed] (line-delta (::prompt ft) (::prompt tt))
            f-as-of (:seon.agent.turn/rendered-as-of ft)
            t-as-of (:seon.agent.turn/rendered-as-of tt)]
        (cond-> {::ok? (and (::ok? ft) (::ok? tt))
                 ::from-turn ft
                 ::to-turn   tt
                 ::prompt-lines-added   added
                 ::prompt-lines-removed removed}
          (and f-as-of t-as-of)
          (assoc ::basis-t-delta (- t-as-of f-as-of))
          (and (::prompt-tokens ft) (::prompt-tokens tt))
          (assoc ::prompt-token-delta (- (::prompt-tokens tt)
                                         (::prompt-tokens ft)))
          (seq (keep ::error [ft tt]))
          (assoc ::error (str/join "; " (keep ::error [ft tt]))))))))
