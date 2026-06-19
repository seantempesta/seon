(ns seon.ctx.relevant
  "The `<relevant-source>` context section — the top-k embedding-retrieval
   hits for THIS turn's query, surfaced as source the agent can read inline.
   Symbol-wired into the composer layout (`seon.ctx/core-default-ctx`) as
   `'seon.ctx.relevant/relevant-source-section` at priority 48 (the VOLATILE
   half — query-dependent content must stay out of the cacheable stable prefix).

   The HITS are NOT computed here. They are PREFETCHED by the async
   `seon.agent/run-turn!` (which awaits the wire `knn-search`) and stashed in a
   fiber-local `seon.embed.stash` so this SYNCHRONOUS section can read them
   without making `assemble-context` async (which would ripple to the inspector
   + the gym). This section is a pure reader of the stash.

   REACTIVE + default-OFF: when no prefetch ran (the `SEON_EMBED_RETRIEVAL`
   toggle is unset → `run-turn!` never calls `with-hits`), `(current-hits)` is
   nil and this returns \"\" — the composer drops the section, the assembled
   prompt is byte-identical to today. When the prefetch ran but produced no
   hits (empty index, or a fail-soft nil), this also returns \"\".

   Self-bound (core sections aren't charged to the agent budget): `top-k` and
   `source-char-cap` are constants here — worst case ~7.5k chars.

   The section name `:relevant-source` is CORE-RESERVED (it lives in
   `core-default-ctx`); an agent that names a section `:relevant-source`
   overrides this by the composer's override-by-name merge."
  (:require
    [clojure.string :as str]
    [seon.embed.stash :as stash]))

(def ^:const top-k
  "How many retrieval hits to render. The prefetch in `run-turn!` requests this
   many (`:seon.embed/k`); this is the render-side cap as a defence in depth."
  5)

(def ^:const source-char-cap
  "Per-hit cap on the rendered body (`:seon.fn/source`, `:my.kb/body`, or the
   generic-fallback longest string attr). Worst case top-k * this ≈ 7.5k chars
   — a core section, not charged to the agent's budget."
  1500)

(def ^:private relevant-header
  (str ";; relevant context — the top-" top-k " ENTITIES nearest your latest\n"
       ";; request by embedding similarity (KNN over the embedding index — any\n"
       ";; indexable kind: functions, knowledge-base entries, …). These are\n"
       ";; CANDIDATES surfaced for THIS turn; read them, reuse what fits.\n"
       ";; Long bodies are truncated — pull the full row with\n"
       ";;   (seon.db/pull '[*] <the entity's identity>)"))

(defn- cap-body
  "Truncate a body string to `source-char-cap` with a loud marker so the cut
   is visible to the agent (never a silent partial form)."
  [body]
  (if (> (count body) source-char-cap)
    (str (subs body 0 source-char-cap)
         "\n;; … TRUNCATED (" (count body) " chars total) — pull the full row")
    body))

(defn- block
  "A rendered hit block: a `;; <title>` header + a char-capped body. `title` may
   be nil (the generic fallback falls back to `<unknown>` so a block is never
   header-less); a nil `body` renders header-only — never blank, never throws."
  [title body]
  (str ";; " (or title "<unknown>") "\n"
       (when body (cap-body body))))

(defn- entity-identity
  "Best-effort identity string for an entity of an UNKNOWN kind: a domain
   `*/id` attr value (more meaningful to the agent than a raw eid), falling
   back to `:db/id`, so the generic fallback never renders blank."
  [entity]
  (let [id-val (or (some (fn [[k v]] (when (= "id" (name k)) v))
                         (dissoc entity :db/id))
                   (:db/id entity))]
    (some-> id-val str)))

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
  "Render ONE hit by ENTITY-KIND, dispatched on which display attrs are PRESENT
   (the attribute IS the type — no `:seon/kind` enum):
     - `:seon.fn/sym`  → `;; <sym>` + char-capped `:seon.fn/source`.
     - `:my.kb/body`   → `;; <title>` + char-capped `:my.kb/body`.
     - else            → a generic fallback: the entity's identity (any `*/id`,
                         else `:db/id`) + its longest string-valued attr, so a
                         future embeddable kind never renders blank.
   A hit whose entity lost its eid (raced retraction → no `:seon.embed/entity`)
   renders a header-only `<unknown>` block — never blank, never throws."
  [{:seon.embed/keys [entity]}]
  (cond
    (:seon.fn/sym entity)
    (block (:seon.fn/sym entity) (:seon.fn/source entity))

    (:my.kb/body entity)
    (block (or (:my.kb/title entity) (:my.kb/id entity)) (:my.kb/body entity))

    :else
    (block (entity-identity entity) (longest-string-attr entity))))

(defn relevant-source-section
  "The `<relevant-source>` section. PURE reader of the per-turn retrieval
   stash ([[seon.embed.stash/current-hits]]) — renders the top-`top-k` hits,
   each rendered by ENTITY-KIND ([[render-hit]]: fn / kb / generic fallback,
   dispatched on which display attrs the hit's `:seon.embed/entity` carries)
   behind a `<relevant-source>` tag.

   REACTIVE: returns \"\" when no hits are stashed (default-OFF — no prefetch
   ran — OR the prefetch found nothing), so the composer drops the section and
   the prompt is unchanged. Self-bound; not charged to the agent budget."
  {:malli/schema [:=> [:cat :map] :string]}
  [_input]
  (let [hits (stash/current-hits)]
    (if (seq hits)
      (let [blocks (->> hits
                        (take top-k)
                        (map render-hit))]
        (str "<relevant-source>\n"
             relevant-header "\n\n"
             (str/join "\n\n" blocks)
             "\n</relevant-source>"))
      "")))
