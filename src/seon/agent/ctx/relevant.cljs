(ns seon.agent.ctx.relevant
  "The `:relevant-source` context section — the top-k embedding-retrieval
   hits for THIS turn's query, surfaced (as a single-`;` `relevant context`
   comment-block) as source the agent can read inline.
   Symbol-wired into the composer layout (`seon.config/default-ctx-blocks`) as
   `'seon.agent.ctx.relevant/relevant-source-block` at priority 48 (the VOLATILE
   half — query-dependent content must stay out of the cacheable stable prefix).

   The HITS are NOT computed here. They are PREFETCHED by the async
   `seon.agent/run-turn!` (which awaits the wire `knn-search`) and stashed in a
   fiber-local `seon.embed.stash` so this SYNCHRONOUS section can read them
   without making `assemble-context` async (which would ripple to the web UI
   + the gym). This section is a pure reader of the stash.

   REACTIVE + default-OFF: when no prefetch ran (the `SEON_EMBED`
   toggle is unset → `run-turn!` never calls `with-hits`), `(current-hits)` is
   nil and this returns \"\" — the composer drops the section, the assembled
   prompt is byte-identical to today. When the prefetch ran but produced no
   hits (empty index, or a fail-soft nil), this also returns \"\".

   Self-bound (core sections aren't charged to the agent budget): `top-k` and
   `source-char-cap` are constants here — worst case ~7.5k chars.

   The section name `:relevant-source` is CORE-RESERVED (it lives in
   `seon.config/default-ctx-blocks`); an agent that names a section `:relevant-source`
   overrides this by the composer's override-by-name merge."
  (:require
    [clojure.string :as str]
    [seon.ai.tokens :as tokens]
    [seon.embed.stash :as stash]))

(def ^:const top-k
  "How many retrieval hits to render. The prefetch in `run-turn!` requests this
   many (`:seon.embed/k`); this is the render-side cap as a defence in depth."
  5)

(def ^:const source-char-cap
  "Per-hit cap on the rendered body (the entity's longest string attr — the
   embedded text). Worst case top-k * this ≈ 7.5k chars — a core section, not
   charged to the agent's budget."
  1500)

(def ^:private relevant-header
  (str "; relevant context — the top-" top-k " ENTITIES nearest your latest\n"
       "; request by embedding similarity (KNN over the embedding index — any\n"
       "; indexable kind: functions, knowledge-base entries, …). These are\n"
       "; CANDIDATES surfaced for THIS turn; read them, reuse what fits.\n"
       "; Long bodies are truncated — pull the full row with\n"
       ";   (seon.db/pull '[*] <the entity's identity>)"))

(defn- cap-body
  "Truncate a body string to `source-char-cap` with a loud marker so the cut
   is visible to the agent (never a silent partial form)."
  [body]
  (if (> (count body) source-char-cap)
    (str (subs body 0 source-char-cap)
         "\n; … TRUNCATED (~" (tokens/estimate body) " tokens total) — pull the full row")
    body))

(defn- block
  "A rendered hit block: a `; <title>` header + a char-capped body. `title` may
   be nil (the generic fallback falls back to `<unknown>` so a block is never
   header-less); a nil `body` renders header-only — never blank, never throws."
  [title body]
  (str "; " (or title "<unknown>") "\n"
       (when body (cap-body body))))

(defn- entity-identity
  "Best-effort identity label for an entity of ANY kind — NO hard-coded attr
   names: the SHORTEST string-valued attr (an id/sym/title is short; the payload
   body is long), else `:db/id`, so a block is never header-less."
  [entity]
  (or (->> (dissoc entity :db/id) vals (filter string?) (sort-by count) first)
      (some-> (:db/id entity) str)))

(defn- longest-string-attr
  "The longest string-valued attr value on `entity` — the best-effort 'the
   embedded text' for a kind we don't have a bespoke renderer for. nil when the
   entity carries no string attr (header-only block)."
  [entity]
  (->> (vals entity)
       (filter string?)
       (sort-by count)
       last))

(defn- render-hit
  "Render ONE hit GENERICALLY — NO hard-coded attr names (the attribute IS the
   type, per the no-`:seon/kind` design): header = the entity's identity
   ([[entity-identity]] — its shortest string attr, else `:db/id`); body = its
   longest string-valued attr ([[longest-string-attr]], the embedded text). Works
   for any indexable kind a consumer registers. A hit whose entity lost its eid
   (raced retraction → no `:seon.embed/entity`) renders a header-only `<unknown>`
   block — never blank, never throws."
  [{:seon.embed/keys [entity]}]
  (let [title (entity-identity entity)
        body  (longest-string-attr entity)]
    (block title (when (not= title body) body))))

(defn relevant-source-block
  "DEPRECATED — pull-first idea (relevant-source); see context-rebuild.

   The `:relevant-source` section — top retrieval-stash hits for this turn.

   PURE reader of the per-turn retrieval
   stash ([[seon.embed.stash/current-hits]]) — renders the top-`top-k` hits,
   each rendered GENERICALLY by [[render-hit]] (the entity's identity + its
   longest string attr; any indexable kind, no hard-coded attr names) under a
   single-`;` `relevant context` comment-block header.

   REACTIVE: returns \"\" when no hits are stashed (default-OFF — no prefetch
   ran — OR the prefetch found nothing), so the composer drops the section and
   the prompt is unchanged. Self-bound; not charged to the agent budget."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [_input]
  (let [hits (stash/current-hits)]
    (if (seq hits)
      (let [blocks (->> hits
                        (take top-k)
                        (map render-hit))]
        (str relevant-header "\n\n"
             (str/join "\n\n" blocks)))
      "")))
