(ns seon.render
  "The two renders every renderable carries — `:seon.render/ai` (the
   prompt text) and `:seon.render/html` (a tile) — selected by key
   presence, never a stored discriminator.

   Each slot is a fully-qualified symbol (or a literal: a verbatim
   string for ai, a hiccup vector for html). `*-render` resolves the
   symbol via `seon.eval/lookup-value` and calls it; a nil, unqualified,
   or unresolvable symbol falls through to `seon.render.default/pretty-*`
   so a render never crashes.

   ## The engine

   `render` is the whole system: one recursive, guarded walker over a
   node's children in two views (`:seon.render/ai` → String,
   `:seon.render/html` → hiccup). It injects a view-bound recursion
   handle (`:seon.render/render`) so a parent renders its children
   through the same dispatch, and a `:seon.render/slot` handle so a
   layout places a named block's html into a slot ([[slot]]). A throwing
   or missing render degrades to a legible value, never a crash.

   ## Late-bound symbol lookup

   `seon.eval/lookup-value` walks `js/globalThis` with `cljs.core/munge`
   per segment — works for core fns (shadow-cljs precompiled
   bundle) AND agent-defined fns (written by `cljs.js/eval-str` at the
   same munged paths). Single path, no boot-time wire-up needed."
  (:require
    [cljs.pprint :as pprint]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.config :as config]
    [seon.db :as db]
    [seon.error :as err]
    [seon.error.instrument :as einstrument]
    [seon.eval :as eval]
    [seon.agent.ctx.render-fns :as render-fns]
    [seon.render.default :as default]
    [seon.render.live-tile :as live-tile]
    [seon.render.sci :as render-sci]
    [seon.render.value :as value]
    [seon.ai.tokens :as tokens]
    [seon.schema :as schema]
    [seon.ui.clojure :as cljhl]
    [seon.ui.html :as html]
    [seon.ui.markdown :as md]
    [seon.web.reactive.transform :as transform]))

;; ============================================================
;; Schemas — every shape this surface reads or writes (spec-05 §15.1).
;; ============================================================

;; `:seon.db/db` + `:seon.db/conn` (the runtime db/conn handles) are
;; registered in their owning ns `seon.db` (loaded before this ns via the
;; require above) — a reference here just resolves them, no inline `:any`.

;; Hiccup has exactly ONE schema representation:
;; `:seon.render.live-tile/hiccup` — the registered pure-data shallow
;; bound (vector with keyword head), defined in seon.render.live-tile
;; (loads first). The deep recursive walk happens at the render
;; boundary only, via the PLAIN fn
;; `seon.render.live-tile/valid-hiccup?` (registered forms must be
;; pure data — platform law; recursive seqex schemas additionally trip
;; `:malli.core/potentially-recursive-seqex` inside instrumented fn
;; schemas). The old deep registered `:seon.render/hiccup` schema and
;; this ns's forwarding `valid-hiccup?` def were deleted in the render
;; sweep (2026-06-11) — `:seon.render/hiccup` remains ONLY as the map
;; KEY in `:seon.render/html-response`.

;; The render SLOTS (self-context spec, 2026-06-10 — relaxed from
;; symbol-only):
;;
;;   :seon.render/ai   — string (verbatim doctrine — content as source,
;;                       not cached output) OR a qualified symbol
;;                       resolved LATE at every render.
;;   :seon.render/html — qualified symbol OR a literal hiccup vector
;;                       (static badge), same slot.
;;
;; Storage: a mixed-type :or can't map to one datahike valueType, so
;; the seon.db bridge stores these as pr-str'd EDN strings
;; (`:db.type/string`); `seon.db/decode-edn-value` is the read-side
;; inverse used by every consumer here.
(schema/register! :seon.render/ai   [:or :string :symbol])

;; `:seon.render/html` REFERENCES the canonical value-or-fn shape
;; `:seon.render.live-tile/content` (live-tiles U1) — same shape,
;; registered once. The definition lives in seon.render.live-tile
;; because that ns loads first (this ns requires it) and register!'s
;; compilability guard rejects forward references.
(schema/register! :seon.render/html :seon.render.live-tile/content)

;; ── render-CONTROL attrs (context-render keystone) — all OPTIONAL; ANY
;; entity (a domain row OR a section) may carry them. A renderable is a
;; DOMAIN ENTITY rendered by its schema — there is NO stored render kind,
;; NO render id (the handle is the entity's own id), NO render timestamp
;; (time is the :db/txInstant), NO ordinal, NO churn attr.
(schema/register! :seon.render/clip
  ;; per-item / per-view clip override — an int cap, a per-view map, or
  ;; :none to opt the section out of clipping (e.g. the transcript).
  [:or :int
       [:map [:seon.render/ai   {:optional true} :int]
             [:seon.render/html {:optional true} :int]]
       [:enum :none]])
(schema/register! :seon.render/hidden? :boolean)        ;; self-prune: drop from render, keep the row
;; NO-CLIP opt-out — a block / value / eval-row carrying `true` renders
;; WHOLE past the authored-content char/token cap (the single
;; `seon.agent.ctx/clip-or-full` gate honors it). Default is to clip, with a
;; loud marker, so the safety cap still fires for UNFLAGGED huge dumps; the
;; flag only bypasses it. A shared shape referenced by every clip site.
(schema/register! :seon.render/full? :boolean)
;; The citable result-body render cap (tokens) an eval row is clipped to —
;; selected per-eval by AGE from the transcript block's `::result-decay`
;; levels (config-driven agent-init CP-3 move 4). Absent → the fixed
;; [[seon.agent.ctx/result-body-render-cap]] default.
(schema/register! :seon.render/result-body-cap [:int {:min 0}])
;; The render/turn TIME COORDINATE — the basis-t (datahike tx-id int) of the
;; db value a render/turn is computed over ("now" = `(db/basis-t)`), replayable
;; via `(db/as-of db at)`. An INJECTABLE: a map-in fn declaring it
;; `{:optional true}` gets the current basis filled at the eval boundary
;; (`seon.instrument/injectables`); explicit args win (forensic replay passes
;; a past t). An int tx-id, NOT an inst — the tx-id is the exact reproducible
;; coordinate; wall-clock is derivable from the tx's `:db/txInstant`.
(schema/register! :seon.render/at [:int {:min 0}])

;; `:seon.agent/id` — registered HERE (not in its owning ns `seon.agent`)
;; because this is the FIRST-loading ns whose load-time schema references
;; it (`:seon.render/section-request` below; seon.agent loads after this
;; ns via the seon.agent.ctx require chain, and register!'s compilability
;; guard rejects forward references — same precedent as `:seon.ns/name`
;; living in seon.agent.ctx.render-fns). The literal "root" id (the
;; orchestrator-root base case) is the ONE agent id exempt from the
;; 14-char minted-id shape — every other agent id is a `:seon.db/id`.
;; The `:or` bridges to the SAME datahike schema: the CLJS bridge
;; (seon.db.internal/form->datahike-value-type) walks `:and` → base, then
;; the `:or` (one mappable type :db.type/string via :seon.db/id + the
;; unmappable `[:= "root"]`) → :db.type/string; the
;; `{:seon.db/identity true}` prop still yields :db.unique/identity.
;; Because the resolved head is `:and` (not `:or`), `edn-encoded-attr?`
;; is FALSE — "root" stores as a plain string, NOT pr-str'd EDN.
(schema/register! :seon.agent/id
  [:and {:seon.db/identity true} [:or [:= "root"] :seon.db/id]])

;; The ONE section/render-fn REQUEST shape (owner-ruled 2026-07-05).
;; Every block/section/converter fn the render engine calls declares
;; `[:cat :seon.render/section-request]` instead of a bare `[:cat :map]`.
;; OPEN map — the engine composes extra keys per call site
;; (`:seon.render/node`, `:seon.agent/entity`, `:seon.render/render`, …);
;; what it NAMES is the injectable contract: the three
;; `seon.instrument/injectables` keys, all optional (explicit args win;
;; absent keys are filled at the eval boundary — "me/now/current db").
;; Referenced schemas, never inline shapes (shared-shape rule).
(schema/register! :seon.render/section-request
  [:map
   [:seon.db/db     {:optional true} :seon.db/db]
   [:seon.agent/id  {:optional true} :seon.agent/id]
   [:seon.render/at {:optional true} :seon.render/at]])

(schema/register! :seon.render/children
  [:vector {:seon.db/component true} :seon.db/ref])      ;; OPTIONAL authored nesting; derived sections query instead

;; The `:seon.render/ai-response` envelope — the LIVE-TILE / default
;; slot-primitive return shape (`ai-render` → `seon.render.default/pretty-ai`
;; and `seon.render.chat`). The old `:seon.render/text` second arm was
;; DELETED with the V4 composer rewrite (context-render keystone): the ONE
;; producer of it (`seon.agent.ctx/assemble-context`) is gone, and the keystone's
;; CONVERTERS return BARE Strings (not this envelope). One key, one meaning.
(schema/register! :seon.render/ai-response
  [:map
   [:seon.render/ai :string]])

;; The error envelope a failed render carries (live-tiles U1) — the
;; standard `:seon.error/*` shape, registered in seon.db.
(schema/register! :seon.render/error :seon.db/error)

;; The hiccup slot carries the PURE-DATA shallow bound
;; `:seon.render.live-tile/hiccup` (vector with keyword head) — NOT
;; the deep recursive `:seon.render/hiccup` above (recursive seqex
;; trips :malli.core/potentially-recursive-seqex inside instrumented
;; fn schemas) and NOT `[:fn valid-hiccup?]` (registered forms must
;; be pure data — fn objects don't survive the form round-trip; see
;; the platform-law comment in seon.render.live-tile). The deep walk
;; happens at the render boundary (html->string + valid-hiccup?).
;; `:nil` accepts render fns that explicitly return
;; `{:seon.render/hiccup nil}` to mean "render nothing"
;; (render-entity-html callers already handle nil via `or`).
;;
;; `:seon.render/ai` — the OPTIONAL ai render (PRD §2): how the agent
;; knows what its human sees. Tile fns return it alongside the hiccup;
;; the awareness section renders it into the agent's context every turn.
;; Same render idea as `:seon.agent.ctx/block`.
;;
;; `:seon.render/error` — present when the renderer THREW: the hiccup
;; is the human fallback tile and this entry carries the envelope so
;; the agent sees its own renderer is broken (vanish = banned).
(schema/register! :seon.render/html-response
  [:map
   [:seon.render/hiccup [:or :nil :seon.render.live-tile/hiccup]]
   [:seon.render/ai    {:optional true} :string]
   [:seon.render/error {:optional true} :seon.render/error]])

;; System renderer input — for `seon.render.default/*` and other
;; non-agent-namespaced fns. Doesn't know which agent ahead of time;
;; carries `:seon.agent/id` and pulls the entity itself.
(schema/register! :seon.render/system-input
  [:map
   [:seon.db/db    :seon.db/db]
   [:seon.agent/id :string]])

;; ============================================================
;; Renderers — one per surface. Both fall through to pretty-print
;; on miss; html-render additionally accepts literal hiccup.
;; ============================================================

(defn ai-render
  "Materialize an `:seon.render/ai` slot to agent-facing text.

   Slot is a qualified symbol; if it doesn't resolve (or is nil /
   unqualified), fall through to
   seon.render.default/pretty-ai so the agent always gets some ctx."
  {:malli/schema [:=> [:cat :any :map] :seon.render/ai-response]}
  [sym input-map]
  (let [f (or (eval/lookup-value sym) default/pretty-ai)]
    (f input-map)))

(defn html-render
  "Materialize an `:seon.render/html` slot to hiccup.

   Slot is either a symbol (resolved + called), a literal hiccup vector
   (used as-is), or
   anything else (pretty-printed)."
  {:malli/schema [:=> [:cat :any :map] :seon.render/html-response]}
  [slot input-map]
  (cond
    (qualified-symbol? slot)
    (if-let [f (eval/lookup-value slot)]
      (f input-map)
      (default/pending-html slot))

    (vector? slot)
    {:seon.render/hiccup slot}

    :else
    (default/pretty-html input-map)))

;; ============================================================
;; Entity-shape resolution (the `:seon.schema`-driven renderer dispatch).
;; An entity has no kind — it is its attributes; a renderable SHAPE is a
;; `:seon.schema` row matched by ATTRIBUTE-PRESENCE.
;;
;; Renderable entity SHAPES are `:seon.schema` rows carrying both an
;; id-attr and a `:seon.schema/render-fn`. `entity-primary-schema` picks
;; the most-specific schema whose required-attrs are all present on an
;; entity; `entity-render` / `render-entity-html` / `render-entity-ai`
;; resolve a render symbol from that schema (or a per-entity override).
;;
;; Shared by the test-capture-as-data rendering (`render-entity-html`
;; etc.); the inspector's debug right pane mirrors the left's block set
;; via these same converters.
;; ============================================================

(defn- renderable-schemas
  "Datalog-driven enumeration of every RENDERABLE entity-shape
   `:seon.schema` row in the DB — rows carrying BOTH an id-attr and a
   `:seon.schema/render-fn`. Returns a seq of `{:schema <key> :id-attr <kw>
   :ai <sym> :html <sym>}` (`:schema` is the `:seon.schema/key`). Each
   schema entity is materialized at agent
   boot from `seon.schema/all-entity-schemas-tx-data` (and on every
   subsequent `register!`), so the renderer reads schemas from
   core state instead of walking the in-memory
   `seon.schema/*schemas` atom.

   The render-fn clause is load-bearing, not cosmetic: a row WITHOUT a
   renderer has no symbol for `entity-render` to resolve, and its
   id-attr could still be registered-but-never-transacted — `d/datoms`
   THROWS (\"Bad entity attribute … not defined in current schema\") on
   such an attr, e.g. the request/response envelopes the registry's
   id-attr derivation over-matches (context-v3 unit 2; same gate as
   `seon.agent/schema-catalog-section`).

   `:seon.schema/render-html-fn` stays optional — a side query merged
   in Clojure rather than datahike-cljs's `get-else` (avoids
   per-backend quirks)."
  [db]
  (let [base  (d/q '[:find ?key ?id-attr ?ai
                     :where
                     [?s :seon.schema/key ?key]
                     [?s :seon.schema/id-attr ?id-attr]
                     [?s :seon.schema/render-fn ?ai]]
                   db)
        htmls (into {} (d/q '[:find ?key ?html
                              :where
                              [?s :seon.schema/key ?key]
                              [?s :seon.schema/render-html-fn ?html]]
                            db))]
    (map (fn [[k id-attr ai]]
           {:schema  k
            :id-attr id-attr
            :ai      ai
            :html    (get htmls k)})
         base)))

;; Single-slot cache for the schema lookup tables, keyed by db value
;; identity. `entity-primary-schema` used to run one datalog query
;; PER ENTITY through the FilteredDB (each datom access re-runs the
;; filter pred) — the dominant cost of an inspector render on the
;; file-backed store. The tables derive purely from `:seon.schema`
;; rows, which are immutable for a given db value, so one slot keyed
;; by `identical?` is correct and survives exactly as long as the
;; render that's using it.
(defonce ^:private !schema-cache (atom nil))

(defn- schema-tables
  "Return `{:schemas <renderable-schemas seq> :schemas-by-key {<key> <info>}
   :required-by-schema {<key> #{<attr> …}}}` for `db`, computed once per
   db value (single-slot identity-keyed cache)."
  [db]
  (let [c @!schema-cache]
    (if (and c (identical? (:db c) db))
      (:tables c)
      (let [schemas  (renderable-schemas db)
            req-rows (d/q '[:find ?key ?req
                            :where
                            [?s :seon.schema/key ?key]
                            [?s :seon.schema/required-attrs ?req]]
                          db)
            required (reduce (fn [m [k req]]
                               (update m k (fnil conj #{}) req))
                             {} req-rows)
            tables   {:schemas            schemas
                      :schemas-by-key     (into {} (map (juxt :schema identity) schemas))
                      :required-by-schema required}]
        (reset! !schema-cache {:db db :tables tables})
        tables))))

(defn- entity-primary-schema
  "Pick the most-specific `:seon.schema` shape whose required-attrs are
   ALL present on `entity` (attribute-presence — an entity has no kind).
   Pure in-memory subset test against the per-db cached
   `:required-by-schema` table ([[schema-tables]]) — the former
   per-entity datalog query was the inspector's render bottleneck on the
   file store.

   A schema 'fully matches' when every required attr is present on the
   entity. Among full matches, the schema with the most required attrs
   wins (specificity). Tie-broken alphabetically by `:seon.schema/key`
   for stable output (research §D)."
  [db entity]
  (let [present (set (filter keyword? (keys entity)))]
    (when (seq present)
      (let [{:keys [required-by-schema]} (schema-tables db)
            full (keep (fn [[k req]]
                         (when (and (seq req)
                                    (every? #(contains? present %) req))
                           [k (count req)]))
                       required-by-schema)]
        (when (seq full)
          (->> full
               (sort-by (juxt (comp - second) (comp str first)))
               ffirst))))))

(defn- entity-render
  "Resolve the render value for `entity` in `render` (`:html` or `:ai`)
   — the two-step resolution both renders share: per-entity attr override
   wins (`:seon.render/html` / `:seon.render/ai`, bridge-decoded), else
   the entity's primary `:seon.schema` shape's default symbol. nil when
   neither step yields a value."
  [db entity render]
  (let [attr (case render :html :seon.render/html :ai :seon.render/ai)]
    (or (some->> (get entity attr)
                 (db/decode-edn-value attr))
        (let [{:keys [schemas-by-key]} (schema-tables db)
              schema (entity-primary-schema db entity)]
          ;; NOTE: `(get schemas-by-key schema)`, NOT
          ;; `(some-> schemas-by-key schema …)` — the latter invokes
          ;; `schema` as a fn and throws a TypeError when
          ;; entity-primary-schema returns nil (no schema matched).
          (get (get schemas-by-key schema) render)))))

;; ============================================================
;; The ONE envelope-unwrap — every render path consumes the SAME
;; `:seon.render/html-response` MAP contract here.
;;
;; A render fn (an entity converter, a ctx-block render, the live tile)
;; may return BARE content — hiccup for the html view, a String for the
;; ai view — OR the established `:seon.render/html-response` MAP envelope
;; `{:seon.render/hiccup <h> :seon.render/ai <s> …}`. This is the ONLY
;; place the envelope is unwrapped, so entity tiles AND ctx-block slots
;; AND recursive sections never leak a raw map into hiccup (the
;; map-renders-empty bug). No second extraction site, no second contract.
;; ============================================================

(defn- view->response-key
  "The `:seon.render/html-response` key carrying `view`'s value:
   `:seon.render/html` → `:seon.render/hiccup`; `:seon.render/ai` →
   `:seon.render/ai`."
  [view]
  (case view
    :seon.render/html :seon.render/hiccup
    :seon.render/ai   :seon.render/ai))

(defn- unwrap-response
  "Extract `view`'s value from a render result `r`. When `r` is the
   `:seon.render/html-response` MAP envelope carrying `view`'s key, return
   that value (the hiccup or the ai String); otherwise pass the bare value
   through unchanged. A non-envelope map (one lacking the key) passes
   through too — only the established contract is unwrapped."
  [view r]
  (let [k (view->response-key view)]
    (if (and (map? r) (contains? r k))
      (get r k)
      r)))

;; ============================================================
;; Fail-loud render dial — the ONE place every render swallow-guard routes
;; its caught exception. When `seon.config/render-strict?` is ON (dev / test
;; / gym / benchmark), a render/converter failure RE-THROWS with the
;; offending block name + the full malli explain (a silent render failure
;; SCREAMS the moment it happens); when OFF (a live prod agent), it returns
;; nil so the caller falls back to today's graceful guard — no block ever
;; hard-crashes a prod turn. See `seon.config/render-strict?` for the policy.
;; ============================================================

(defn loud-explain
  "A LOUD one-string diagnosis of a caught render exception `e`.

   For block `where`: the offending block name + the exception message
   + (when `e` is a Malli instrumentation envelope) the FULL humanized
   `explain`. The string the strict-mode throw carries and the graceful
   guard would otherwise hide behind a bare `:malli.core/invalid-input`."
  {:malli/schema [:=> [:catn [::where :any] [::e :any]] :string]}
  [where e]
  (let [data (ex-data e)
        malli? (einstrument/instrument-error? data)
        detail (when malli?
                 (try (einstrument/render-malli-error data)
                      (catch :default e2
                        ;; OUR renderer failing on OUR OWN malli envelope is a
                        ;; core bug (:core); the diagnosis still degrades to the
                        ;; bare base message (detail nil).
                        (err/record! {:seon.error/raw e2 :seon.error/fault :core})
                        nil)))]
    (str "[" (name (or where :unnamed)) "] render failed: " (err/->message e)
         (when detail (str "\n" detail)))))

(defn strict-fail!
  "Route a caught render exception through the strict-mode dial.

   [[seon.config/render-strict?]]: STRICT ON → throw an ex-info naming
   `where` + carrying the full
   [[loud-explain]] (so ANY render failure screams, never a swallowed
   one-liner). STRICT OFF → return nil, signalling the caller to fall back
   to its graceful guard (a live prod agent must not hard-crash on one bad
   block). The single seam every render swallow-guard calls."
  {:malli/schema [:=> [:catn [::where :any] [::e :any]] [:maybe :nil]]}
  [where e]
  (when (config/render-strict?)
    (throw (ex-info (loud-explain where e)
                    {:seon.render/strict?     true
                     :seon.render/where       where
                     :seon.render/cause-message (err/->message e)})))
  nil)

(defn render-entity-html
  "Render `entity` to hiccup via its resolved `:seon.render/html` symbol.
   Per-entity override wins; else falls back to the entity's primary
   schema's default html symbol. Returns nil when no symbol resolves OR
   the resolved fn returns nil.

   The schema's html symbol IS a converter (`seon.handlers.*/render-html`)
   that returns BARE hiccup, called with the entity under
   `:seon.render/node`. A renderer that THROWS does NOT vanish (same
   posture as the live tile's `error-response`): the tile becomes a
   legible error banner naming the fn + message, siblings render
   untouched, the page stays 200.

   `input` is the system-input shape every render fn receives:
     {:seon.db/db    <db>
      :seon.agent/id <agent-id>
      :seon.render/node <entity-map>}
   (`:seon.render/entity` is tolerated as the node key for older callers.)"
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :any]]}
  [{:seon.db/keys [db] :seon.render/keys [entity node] :as input}]
  (let [db     (or db @db/*conn*)
        entity (or node entity)]
    (when-let [sym (entity-render db entity :html)]
      (try
        (let [f (eval/lookup-value sym)
              r (when f (f (assoc input :seon.render/node entity)))]
          ;; Converters return BARE hiccup; a per-entity renderer
          ;; (agent-authored, test fixture, the live-tile contract) may
          ;; return the {:seon.render/hiccup h …} envelope — unwrapped via
          ;; the ONE shared path so every renderer obeys one contract.
          (unwrap-response :seon.render/html r))
        (catch :default e
          ;; Classify by the render symbol (fault-for): an agent-authored
          ;; converter → :agent, a core `seon.handlers.*` converter → :core.
          ;; Record BEFORE strict-fail! (which re-throws in strict mode,
          ;; bypassing the tail). recorded? skips an inner funnel's datom.
          (when-not (err/recorded? e)
            (err/record! {:seon.error/raw e :seon.error/fault (err/fault-for sym)}))
          ;; STRICT dial: dev/test/gym → re-throw LOUD; prod → graceful guard.
          (strict-fail! sym e)
          (live-tile/error-tile
            {:seon.error/message (str sym " threw: " (or (.-message e) (str e)))
             :seon.error/symbol  sym}))))))

;; ============================================================
;; THE typed-block renderer — `block`. One guarded `value → render` fn
;; that DISPATCHES ON VALUE-KIND so every surface (transcript entry, canvas,
;; /debug) "just displays the block." It is NOT a new mechanism: it
;; GENERALIZES the same unwrap/guard seam `render-entity-html` centralizes
;; (the ONE `unwrap-response`) from "dispatch on the entity's render symbol"
;; to "dispatch on the value's KIND," reusing the inventory of renderers that
;; already exist (md->hiccup, the value panel, clj->hiccup, the error-tile
;; seam). The converters (`handlers/*/render-html`) become THIN — each tags
;; its fields and hands them to `block`.
;;
;; THE TAGGED-VALUE CONTRACT (the discriminator is the namespaced key ON the
;; value — never a stored `:kind` field; house rule). A converter tags a
;; field by wrapping it in the marker map; `block` dispatches on the marker:
;;
;;   message → {:seon.render/markdown "<md string>"}     ; → md->hiccup
;;   source  → {:seon.render/source   "<clj string>"}    ; → clj->hiccup
;;   data    → render.value/render-html-data projection  ; key :…value/tree
;;   error   → a :seon/error value                       ; key :seon.error/message
;;   hiccup  → a literal hiccup vector                   ; [keyword … ] passthrough
;;   else    → any raw value → projected via render-html-data (never throws)
;; ============================================================

(schema/register! :seon.render/markdown :string)
(schema/register! :seon.render/source   :string)
(schema/register! :seon.render/message-block [:map [:seon.render/markdown :seon.render/markdown]])
(schema/register! :seon.render/source-block  [:map [:seon.render/source   :seon.render/source]])
(schema/register! :seon.render/view [:enum :html :ai])

(defn- message-block? [x]
  (and (map? x) (contains? x :seon.render/markdown)))

(defn- source-block? [x]
  (and (map? x) (contains? x :seon.render/source)))

(defn- data-projection? [x]
  (and (map? x) (contains? x :seon.render.value/tree)))

(defn- error-value? [x]
  (and (map? x) (string? (:seon.error/message x))))

(defn- value-leaf
  "A non-container `render-html-data` skeleton node → a styled inline token.
   Mirrors `seon.render.value`'s marker tokens (datom / opaque / clipped
   string), plus plain scalars."
  [x]
  (cond
    (and (map? x) (contains? x :seon.eval/datom))
    (let [[e a v] (:seon.eval/datom x)]
      [:span {:class "text-keyword font-mono"}
       (str "#datom[" e " " (pr-str a) " " (pr-str v) "]")])

    (and (map? x) (contains? x :seon.eval/opaque))
    [:span {:class "text-text-400 font-mono italic"}
     (str "#‹" (:seon.eval/opaque x)
          (when-some [s (:seon.eval/summary x)] (str " " s)) "›")]

    (and (map? x) (contains? x :seon.render.value/string-len))
    [:span {:class "text-success font-mono break-all"}
     (str (pr-str (str (:seon.render.value/head x) "…"))
          " ⟨" (tokens/chars->tokens (:seon.render.value/string-len x)) " tokens⟩")]

    :else
    [:span {:class (str "font-mono break-all "
                        (cond (string? x)  "text-success"
                              (keyword? x) "text-keyword"
                              (number? x)  "text-eval"
                              (nil? x)     "text-text-600"
                              :else        "text-text-200"))}
     (pr-str x)]))

(declare value-node)

(defn- value-details
  "A collapsible container row (`<details>`) for a map / seqish skeleton node.
   Open for the first two depths (value at a glance), collapsed below."
  [summary-hiccup child-rows depth]
  [:details (cond-> {:class "value-node min-w-0"}
              (< depth 2) (assoc :open "open"))
   [:summary {:class "cursor-pointer text-xs select-none hover:text-amber-300 marker:text-text-600"}
    summary-hiccup]
   (into [:div {:class "pl-3 ml-0.5 border-l border-base-700 mt-1 flex flex-col gap-1 min-w-0"}]
         child-rows)])

(defn- map-node [m depth]
  (let [elided (:seon.render.value/elided-keys m)
        m      (dissoc m :seon.render.value/elided-keys)
        pairs  (seq m)
        n      (count pairs)
        rows   (cond-> (vec (for [[k v] pairs]
                              [:div {:class "flex items-start gap-1.5 text-xs min-w-0"}
                               [:span {:class "text-keyword shrink-0 font-mono"} (pr-str k)]
                               (value-node v (inc depth))]))
                 elided (conj [:div {:class "text-2xs text-text-600 font-mono"}
                               (str "… +" elided " more key" (when (not= 1 elided) "s"))]))]
    (value-details
      [:span {:class "text-text-400 font-mono"}
       (str "{} " n " key" (when (not= 1 n) "s")
            (when elided (str " +" elided " hidden")))]
      rows depth)))

(defn- seqish-node [m depth]
  (let [{:seon.render.value/keys [kind shown elided shape]} m
        [open close] (case kind :vector ["[" "]"] :set ["#{" "}"] ["(" ")"])
        n     (count shown)
        rows  (cond-> (vec (map-indexed
                             (fn [i v]
                               [:div {:class "flex items-start gap-1.5 text-xs min-w-0"}
                                [:span {:class "text-text-600 shrink-0 font-mono"} (str i)]
                                (value-node v (inc depth))])
                             shown))
                (and elided (not= 0 elided))
                (conj [:div {:class "text-2xs text-text-600 font-mono"}
                       (if (= :more elided) "… +more" (str "… +" elided " more"))]))]
    (value-details
      [:span {:class "text-text-400 font-mono"}
       (str open close " " n " shown"
            (cond (= :more elided) " +more"
                  (and elided (not= 0 elided)) (str " +" elided)
                  :else "")
            (when shape (str " · each {" (str/join " " (map pr-str shape)) "}")))]
      rows depth)))

(defn- pruned-marker
  "A depth/breadth boundary the sampler stopped at — the deeper value is NOT
   in the tree. Rendered as a passive 'deeper' hint."
  [x]
  (let [k (:seon.render.value/pruned x) c (:seon.render.value/count x)
        [o cl] (case k :map ["{" "}"] :set ["#{" "}"] :vector ["[" "]"] ["(" ")"])
        unit   (if (= k :map) "keys" "items")]
    [:span {:class "inline-flex items-center gap-1 text-2xs text-text-500 font-mono"
            :title "deeper than the bounded view — drill the live result/<id> var"}
     [:span {:class "text-text-600"} (str o "…" (when c (str c " " unit)) cl)]
     [:span {:class "text-text-700"} "▸ deeper"]]))

(defn- value-node
  "Recursively render one `render-html-data` `:tree` node to hiccup. Containers
   (map / seqish) become `<details>`; everything else is an inline token."
  [x depth]
  (cond
    (and (map? x) (contains? x :seon.render.value/pruned)) (pruned-marker x)
    (and (map? x) (contains? x :seon.render.value/kind))   (seqish-node x depth)
    (and (map? x)
         (not (contains? x :seon.eval/datom))
         (not (contains? x :seon.eval/opaque))
         (not (contains? x :seon.render.value/string-len)))
    (map-node x depth)
    :else (value-leaf x)))

(defn- data-panel
  "The DATA-kind html render — a collapsible drill-down over a
   `seon.render.value/render-html-data` projection (`:tree`/`:summary`/
   `:truncated?`). The whole bounded tree ships in one render; expand/collapse
   is a client CSS toggle (`<details>`), no round-trip."
  [{:seon.render.value/keys [tree summary truncated? eval-id]}]
  [:div {:class "flex flex-col gap-1"}
   [:div {:class "text-2xs text-text-500 font-mono mb-0.5"}
    (str summary
         (when eval-id (str " · result/" eval-id))
         (when truncated? " · partial"))]
   (value-node tree 0)])

(defn- hiccup-text
  "Best-effort prompt TEXT for the `:ai` view of a literal hiccup value —
   the concatenated string leaves, attrs/tags stripped. Never throws."
  [h]
  (->> (tree-seq vector? seq h)
       (filter string?)
       (str/join " ")
       str/trim))

(defn block
  "THE typed-block renderer for a tagged value in `:html` or `:ai`.

   `(block view x)` — `view` is `:html` (→ hiccup)
   or `:ai` (→ prompt String). Dispatches on the value-KIND of `x` via the
   namespaced key the value carries (the tagged-value contract above) and
   delegates to the renderer that already owns that kind. GUARDED like
   `render-entity-html`: a throwing render becomes an error card (`:html`) or
   an error String (`:ai`) — siblings intact, never an exception. Unknown
   values fall through to the data panel (projected via `render-html-data`),
   so `block` renders ANYTHING."
  {:malli/schema [:=> [:catn [::view :seon.render/view] [::x :any]] :any]}
  [view x]
  (try
    (case view
      :html
      (cond
        (message-block? x) (md/md->hiccup (:seon.render/markdown x))
        (source-block? x)  (cljhl/clj->hiccup (:seon.render/source x))
        (data-projection? x) (data-panel x)
        (error-value? x)   (live-tile/error-tile x)
        (live-tile/valid-hiccup? x) x
        :else              (data-panel (value/render-html-data "inline" x)))

      :ai
      (cond
        (message-block? x) (:seon.render/markdown x)
        (source-block? x)  (:seon.render/source x)
        (data-projection? x) (str (:seon.render.value/summary x)
                                   (when (:seon.render.value/truncated? x) " (partial)"))
        (error-value? x)   (:seon.error/message x)
        (live-tile/valid-hiccup? x) (hiccup-text x)
        :else              (value/render-ai "inline" x)))
    (catch :default e
      ;; `block` dispatches to CORE renderers (md->hiccup, clj->hiccup, the
      ;; value panels) — a throw is our machinery (:core). Record BEFORE
      ;; strict-fail! (re-throws in strict mode); recorded? skips a funnel dup.
      (when-not (err/recorded? e)
        (err/record! {:seon.error/raw e :seon.error/fault :core}))
      ;; STRICT dial: dev/test/gym → re-throw LOUD; prod → graceful guard.
      (strict-fail! :block e)
      (let [msg (str "block render failed: " (err/->message e))]
        (case view
          :html (live-tile/error-tile {:seon.error/message msg :seon.error/where :block})
          :ai   msg)))))

;; ============================================================
;; Agent tile (live-tiles U1) — the agent's ONE always-visible HTML
;; surface. Resolution (seon.render.live-tile/wired-content):
;; per-entity `:seon.render.live-tile/content` → the core
;; welcome. Neither `:seon.render/html` nor the `:seon.agent` schema
;; default is consulted for the TILE — that key means only the
;; generic entity-tile render (one key, one meaning; PRD §8.1).
;; ============================================================

(def ^:private tile-entity-pattern
  "What tile rendering READS of the agent entity — the wired slot
   (wired-content), the welcome's purpose/id, and the run pointer (derived
   state). Deliberately NOT '[*]: the full pull would inline the agent's whole
   component tree per tile render (T5's amplifier finding, open-issues
   2026-06-11). Tile fns needing more get `:seon.db/db` in their input and
   query for it."
  [:db/id
   :seon.agent/id
   :seon.agent/run
   :seon.agent/purpose
   :seon.render.live-tile/content])

(schema/register! :seon.render/tile-request
  [:map
   [:seon.agent/id :string]
   [:seon.db/db    {:optional true} :seon.db/db]])

(defn render-agent-tile
  "Render the agent's live tile — the one surface it rewrites.

   Dynamically rewritten by transacting a qualified fn symbol or
   literal hiccup onto `:seon.render.live-tile/content` on its own
   agent entity; see seon.render.live-tile's ns docstring for the
   full contract).

   Returns `:seon.render/html-response`. A renderer that THROWS does
   NOT vanish: the response is `seon.render.live-tile/error-response`
   — fallback tile for the human, `:seon.render/error` envelope +
   `:seon.render/ai` render for the agent. nil hiccup only when the
   agent entity doesn't exist (the tile never crashes its caller)."
  {:malli/schema [:=> [:cat :seon.render/tile-request] :seon.render/html-response]}
  [{:seon.agent/keys [id] :seon.db/keys [db]}]
  (let [db  (or db @db/*conn*)
        ;; Guarded pull (seon.db/pull, 65dfc90): registered-but-never-
        ;; installed attrs (e.g. ::content on a fresh store) are
        ;; filtered, typos throw legibly. The remaining try covers only
        ;; the unresolvable-lookup-ref throw (missing agent → nil
        ;; hiccup, the documented contract).
        ent (try (db/pull db tile-entity-pattern [:seon.agent/id id])
                 ;; probe: the only throw this covers is the unresolvable
                 ;; lookup-ref of a MISSING agent — nil ent is the documented
                 ;; contract (tile renders nothing), an expected absence.
                 (catch :default _ nil))]
    (if (nil? (:seon.agent/id ent))
      {:seon.render/hiccup nil}
      (let [;; No pin stored → the DERIVED default: the agent's
            ;; last-updated tile (context.md §canvas — derive the
            ;; default, store only the pin). Guarded: a derivation
            ;; failure means no derived candidate → the welcome.
            derived
            (when (nil? (:seon.render.live-tile/content ent))
              (try (:seon.agent.ctx.render-fns/tile-sym
                     (render-fns/last-updated-tile
                       {:seon.db/db db :seon.agent/id id}))
                   ;; core derivation (last-updated-tile) throwing is a defect
                   ;; (:core) — a genuine "no candidate" RETURNS nil, it does
                   ;; not throw; the tile still degrades to the welcome.
                   (catch :default e
                     (when-not (err/recorded? e)
                       (err/record! {:seon.error/raw e :seon.error/fault :core}))
                     nil)))
            {:seon.render.live-tile/keys [value]}
            (live-tile/wired-content
              (cond-> {:seon.render/entity ent}
                (some? derived)
                (assoc :seon.render.live-tile/derived derived)))
            input {:seon.db/db         db
                   :seon.agent/id      id
                   :seon.render/entity ent}]
        (try
          (let [;; AGENT-authored tile fns run under an SCI wall-clock
                ;; interrupt so a non-terminating tile (a sync loop/recur)
                ;; aborts in-process instead of freezing the single pod thread
                ;; (tile-isolation PRD Layer 1). The core `welcome`, core
                ;; section fns, and literal hiccup stay on the fast compiled
                ;; `html-render` path untouched.
                resp   (if (and (render-sci/bounding-enabled?)
                                (err/agent-authored-sym? value))
                         (let [r (render-sci/invoke-bounded value input)]
                           (cond
                             ;; deadline tripped — reset the tile to welcome +
                             ;; warn the agent (async, deduped), and render the
                             ;; known-good welcome for the human this turn.
                             (:seon.render.sci/interrupt r)
                             (do (render-sci/recover-hung-tile!
                                   id value render-sci/default-budget-ms)
                                 (html-render live-tile/welcome-sym input))
                             ;; SCI could not run the fn — FAIL-LOUD: throw
                             ;; into the guard below (strict dial → loud;
                             ;; prod → the calm error-response + the derived
                             ;; agent-facing truth). NEVER the unbounded
                             ;; compiled path: a hang there would wedge the
                             ;; single-threaded pod.
                             (:seon.render.sci/error r)
                             (throw (ex-info
                                      (str "tile fn " value " could not run "
                                           "under SCI bounding — "
                                           (get-in r [:seon.render.sci/error
                                                      :seon.error/message]))
                                      {:seon/error (:seon.render.sci/error r)}))
                             ;; the TILE contract is the html-response MAP
                             ;; envelope (nil tolerated — renders nothing);
                             ;; a bare value is a broken tile fn → the same
                             ;; guard below (legible error-response), never
                             ;; an unbounded compiled re-run.
                             (or (nil? r) (map? r)) r
                             :else
                             (throw (ex-info
                                      (str "tile fn " value " returned a bare "
                                           "value — a tile fn must return the "
                                           "{:seon.render/hiccup … "
                                           ":seon.render/ai …} map envelope")
                                      {:seon.render.live-tile/content value}))))
                         (html-render value input))
                ;; INTERACTIVITY: rewrite agent fn-call / fn-ref handler slots
                ;; in AGENT-authored hiccup into standard Datastar
                ;; `@post('/call?…')` (seon.web.reactive.transform). Bare
                ;; handler symbols qualify to the authoring namespace; /call
                ;; routes by that namespace into the owning agent's sandbox.
                ;;
                ;; The authoring ns is the tile fn's ns when an agent wired a
                ;; SYMBOL, but a LITERAL-HICCUP tile (the easiest path the
                ;; live-tile guidance pushes) has no symbol — it is still
                ;; agent-authored, so we qualify its bare handlers to the
                ;; agent's OWN home ns `my.agent.<id>` (the same id /call
                ;; routes by). Without this, a literal `[:button {:on-click …}]`
                ;; emitted no @post → a dead button.
                ;;
                ;; No-op on core hiccup (welcome/section symbols → not
                ;; agent-authored) and on hiccup with no interactive handlers.
                resp   (let [ns-sym (cond
                                      (err/agent-authored-sym? value)
                                      (symbol (namespace value))
                                      (vector? value)        ; literal hiccup
                                      (symbol (str "my.agent." id)))]
                         (if ns-sym
                           (update resp :seon.render/hiccup
                                   (fn [h]
                                     (if h
                                       (transform/transform-hiccup ns-sym h)
                                       h)))
                           resp))
                hiccup (:seon.render/hiccup resp)]
            ;; SERIALIZATION joins the same guarded path as invocation
            ;; (serialization-boundary hardening): a structurally-broken hiccup (e.g. a
            ;; vector-of-vectors child) doesn't throw at html-render —
            ;; it used to escape here and detonate LATER at page
            ;; serialization, 500ing /agent/<id>, the grid, and
            ;; mid-boot-replay renders. Two layers, one catch:
            (when (some? hiccup)
              ;; (a) serializer-faithful structural walk — a legible
              ;;     message locating the defect (path included);
              (when-some [{:seon.render.live-tile/keys
                           [structure-path structure-message]}
                          (live-tile/hiccup-structure-error hiccup)]
                (throw (ex-info (str "invalid tile hiccup — "
                                     structure-message
                                     " (at path " (pr-str structure-path)
                                     ")")
                                {:seon.render.live-tile/structure-path
                                 structure-path})))
              ;; (b) backstop: PROVE the hiccup serializes. ->string is
              ;;     pure + deterministic, so success here guarantees
              ;;     the page render embedding this hiccup cannot throw
              ;;     on this tile.
              (html/->string hiccup))
            resp)
          (catch :default e
            ;; The tile `value` decides the population: an agent-authored
            ;; symbol OR literal hiccup (qualified to my.agent.<id> above) is
            ;; the agent's own tile → :agent; a core tile symbol → :core.
            ;; recorded? skips the datom when the SCI bounding funnel already
            ;; recorded an agent tile-fn failure (re-thrown at :sci/error).
            ;; Record BEFORE strict-fail! (re-throws in strict mode).
            (when-not (err/recorded? e)
              (err/record! {:seon.error/raw   e
                            :seon.error/fault (if (or (err/agent-authored-sym? value)
                                                      (vector? value))
                                                :agent :core)}))
            ;; STRICT dial: dev/test/gym → re-throw LOUD (catch a broken tile
            ;; the moment it renders); prod → the calm derived banner below.
            (strict-fail! :live-tile e)
            ;; A broken tile must never crash the render and never show the
            ;; human a scary error: return the calm 'updating this tile' placeholder
            ;; for the human. The agent is NOT actively pushed a message (#43 /
            ;; D2 — a forged self-message wakes + defeats the halt); breakage
            ;; is a DERIVED surface: error-response's :seon.render/ai render
            ;; ("YOUR LIVE TILE IS BROKEN …") is re-derived into the agent's
            ;; live-tile context section every turn, self-healing on the next
            ;; clean render. No stored flag, no notification.
            (live-tile/error-response
              {:seon.db/error                 (err/->map e)
               :seon.render.live-tile/content value})))))))

(defn render-entity-ai
  "Render `entity` to text via its resolved `:seon.render/ai` symbol.
   Per-entity override wins; else schema property for the entity's
   primary schema. Returns nil if no symbol resolves OR the fn returns
   nil. Mirror of `render-entity-html` for the AI path.

   The schema's ai symbol IS a converter (`seon.handlers.*/render-ai`)
   returning a BARE String, called with the entity under
   `:seon.render/node` (`:seon.render/entity` tolerated)."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :string]]}
  [{:seon.db/keys [db] :seon.render/keys [entity node] :as input}]
  (let [db     (or db @db/*conn*)
        entity (or node entity)]
    (when-let [sym (entity-render db entity :ai)]
      (try
        (let [f (eval/lookup-value sym)
              r (when f (f (assoc input :seon.render/node entity)))]
          ;; Converters return a BARE String; a per-entity renderer may
          ;; return the {:seon.render/ai s …} envelope — unwrapped via the
          ;; ONE shared path (the ai twin of render-entity-html).
          (unwrap-response :seon.render/ai r))
        ;; A throwing AI renderer is LEGIBLE, never nil-vanished — the
        ;; agent reading its context sees its own renderer is broken
        ;; (mirror of the html banner above / the tile's error render).
        (catch :default e
          ;; Classify by the render symbol (fault-for): agent-authored
          ;; converter → :agent, core converter → :core. Record BEFORE
          ;; strict-fail! (re-throws in strict mode); recorded? skips a dup.
          (when-not (err/recorded? e)
            (err/record! {:seon.error/raw e :seon.error/fault (err/fault-for sym)}))
          ;; STRICT dial: dev/test/gym → re-throw LOUD; prod → legible line.
          (strict-fail! sym e)
          (str "[render error — " sym " threw: "
               (or (.-message e) (str e)) "]"))))))

;; ============================================================
;; The recursive render (context-render keystone).
;;
;; `render` is the WHOLE system: one walker, two views. It takes a VIEW
;; (`:seon.render/ai` → a String, `:seon.render/html` → hiccup), the injected
;; context, and a NODE (a renderable — a domain entity OR a section), and
;; returns the rendered value. It injects a `:seon.render/render` handle bound
;; to the same view so a SECTION renders its children through the same dispatch
;; — never re-walking. There is NO stored render kind/id/at/ordinal: the handle
;; is the node's own id, time is the :db/txInstant.
;; ============================================================

(def render-control-attrs
  "The OPTIONAL render-control attrs ANY renderable may carry — stripped by
   the generic default so a data dump shows only domain attrs."
  [:seon.render/ai :seon.render/html :seon.render/clip
   :seon.render/hidden? :seon.render/full? :seon.render/children
   :seon.agent.ctx/priority])

(defn renderable-id
  "A node's stable HANDLE — its identity attr, or a section name.

   Dispatch by presence on its own identity attr, else the section's
   name. Shown in the transcript / inspector so the agent can
   reference or override it. Never a stored :seon.render/id."
  {:malli/schema [:=> [:cat :any] :any]}
  [node]
  (or (:seon.agent.message/id node)
      (:seon.eval/id node)
      (:my.plan/id node)
      (:seon.agent.ctx/name node)
      (:db/id node)))

(defn renderable-inst
  "A node's TIME for sorting — the `:db/txInstant` it was asserted at.

   The `:db/txInstant` the store
   stamped when the row was first asserted (UNIVERSAL; no per-kind :at attr).
   Per-node fallback for an arbitrary pulled entity (the events query joins it
   in once for the whole list)."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [db node]
  (when-let [eid (:db/id node)]
    (ffirst (db/query {:seon.db/db db
                       :seon.db/query '[:find (min ?t) :in $ ?e :where
                                        [?e _ _ ?tx] [?tx :db/txInstant ?t]]
                       :seon.db/args [eid]}))))

(declare render slot)

(defn- generic-default-renderer
  "The GENERIC default — renders ANY structure when there is no slot and no
   schema match. AI: readable Clojure (id header + pprint, control attrs
   stripped). HTML: a monospace edn dump (the recursive data-tree is a P2
   refinement). This is what makes \"all data is viewable by both\" free."
  [view]
  (case view
    :seon.render/ai
    (fn [{:seon.render/keys [node]}]
      (str ";; " (renderable-id node) "\n"
           (str/trimr
             (with-out-str
               (pprint/pprint (apply dissoc node render-control-attrs))))))
    :seon.render/html
    (fn [{:seon.render/keys [node]}]
      [:pre {:class "p-2 text-xs font-mono bg-base-900 text-text-200 overflow-auto"}
       (with-out-str (pprint/pprint (apply dissoc node render-control-attrs)))])))

(defn- schema-default-renderer
  "resolve-render step 4 — the renderer the node's primary `:seon.schema`
   shape registers (or a per-entity slot override), via the existing
   `entity-render` / `entity-primary-schema` dispatch. Calls the resolved
   converter symbol (bare value); nil when no schema matches."
  [view node input]
  (let [db (or (:seon.db/db input) @db/*conn*)
        in (assoc input :seon.db/db db :seon.render/node node)]
    (case view
      :seon.render/html (render-entity-html in)
      (render-entity-ai in))))

(defn- missing-render
  "A legible, self-healing line for a slot symbol that resolves NOWHERE
   (neither SCI source nor a compiled var). Surfaces loudly instead of
   silently dropping the block — the agent sees what to fix; defining the
   fn self-heals the block next render. nil hiccup for the html view."
  [view id sym]
  (when (= view :seon.render/ai)
    (str "[" (name (or id :unnamed)) "] render failed: fn " sym
         " does not resolve — define it (or fix the symbol) and this "
         "block self-heals next render")))

(defn- resolve-render
  "The render fn for `node` in `view`:
     1. read the slot (already decoded — DB-pulled blocks are slot-decoded
        before they become nodes; in-memory blocks carry literal values);
     2. string → verbatim; shallow-hiccup vector → verbatim;
     3. fn-symbol → the fn. An AGENT-authored symbol is invoked SCI-BOUNDED
        (a runaway agent fn must not freeze the single-threaded pod);
        a core symbol calls direct (fast, trusted);
     4. absent → the schema-default (the node's primary schema's converter);
     5. none → the GENERIC default (any data → Clojure / a dump)."
  [view node]
  (let [slot-val (get node view)]
    (cond
      (string? slot-val) (fn [_] slot-val)
      (vector? slot-val) (fn [_] slot-val)
      (symbol? slot-val)
      (if (err/agent-authored-sym? slot-val)
        (fn [in]
          (let [r (render-sci/invoke-bounded slot-val in view)]
            (cond
              ;; deadline tripped → render nothing (a block never crashes
              ;; its siblings; the recovery path warns the agent).
              (and (map? r) (:seon.render.sci/interrupt r)) nil
              ;; SCI could not run it — FAIL-LOUD. A symbol that resolves
              ;; NOWHERE is a genuinely-missing slot → the legible self-heal
              ;; line; anything else throws into the walker's guard
              ;; (strict dial → loud; prod → the in-place ⚠ line / error
              ;; tile). NEVER the unbounded compiled call: a hang there
              ;; would wedge the single-threaded pod.
              (and (map? r) (:seon.render.sci/error r))
              (if (nil? (eval/lookup-value slot-val))
                (missing-render view (renderable-id node) slot-val)
                (throw (ex-info
                         (str "render fn " slot-val " could not run under "
                              "SCI bounding — "
                              (get-in r [:seon.render.sci/error
                                         :seon.error/message]))
                         {:seon/error (:seon.render.sci/error r)})))
              :else r)))
        (let [f (eval/lookup-value slot-val)]
          (if f f (fn [_] (missing-render view (renderable-id node) slot-val)))))
      ;; no explicit slot: try the node's primary-schema converter; if no
      ;; schema matches (nil), fall to the generic any-data default.
      :else (fn [input]
              (or (schema-default-renderer view node input)
                  ((generic-default-renderer view) input))))))

(defn render
  "Render ONE node in `view`, recursively + guarded.

   The fn receives the full
   injected context PLUS the node and a view-bound recursion handle
   (`:seon.render/render`) so a section renders its children through the same
   dispatch. A render fn may return BARE content OR the
   `:seon.render/html-response` MAP envelope — unwrapped via the one shared
   path, so the result is always a String (`:seon.render/ai`) or hiccup
   (`:seon.render/html`), never a raw map. A hidden node contributes a
   one-line prune note (ai) or nothing (html); a throwing html render
   becomes the overridable error tile, never crashes."
  {:malli/schema [:=> [:cat :keyword :map :any] :any]}
  [view ctx node]
  (if (:seon.render/hidden? node)
    (when (= view :seon.render/ai)
      (str ";; (1 pruned — " (renderable-id node)
           "; (seon.agent/unprune! …) to restore)"))
    (let [f  (resolve-render view node)
          in (assoc ctx :seon.render/node   node
                        :seon.render/render  #(render view ctx %)
                        :seon.render/slot    #(slot ctx %))]
      (try
        (unwrap-response view (f in))           ;; bare OR html-response envelope
        (catch :default e
          ;; Classify by the node's slot value: an agent-authored render
          ;; symbol → :agent (its SCI-bounding funnel usually recorded it
          ;; already — recorded? skips the dup), anything else (core section,
          ;; schema/generic default converter) → :core. Record BEFORE
          ;; strict-fail! (re-throws in strict mode).
          (when-not (err/recorded? e)
            (err/record! {:seon.error/raw   e
                          :seon.error/fault (let [sv (get node view)]
                                              (if (and (symbol? sv)
                                                       (err/agent-authored-sym? sv))
                                                :agent :core))}))
          ;; STRICT dial: dev/test/gym → re-throw LOUD (block name + full
          ;; malli explain); prod → fall through to the graceful guard below.
          (strict-fail! (renderable-id node) e)
          (if (= view :seon.render/ai)
            (str ";; ⚠ [" (renderable-id node) "] render failed: " (ex-message e))
            (live-tile/error-tile
              {:seon.error/message (str (renderable-id node) " — " (ex-message e))})))))))

;; ============================================================
;; The `slot` primitive — place a named block's html render into a
;; layout hole. `(slot ctx :canvas)` looks the block up by
;; `:seon.agent.ctx/name` in the agent's OWN `:seon.agent/ctx`, renders
;; its `:seon.render/html` through the guarded engine, and wraps it as
;; `[:div {:id "tile-<name>" :data-slot "<name>"} <html>]` — a stable DOM
;; id for idiomorph. GUARDED: a missing block or a throwing render
;; becomes a `:seon/error` value rendered as an error tile in the slot,
;; so a sibling slot never crashes (never-crash-always-surface). The same
;; handle is injected into every render ctx as `:seon.render/slot`, so a
;; core or agent layout calls `((:seon.render/slot in) :canvas)`.
;; ============================================================

(defn- agent-ctx-block
  "The agent's `:seon.agent/ctx` block named `block-name` (its render
   slots EDN-decoded), or nil when the agent has no such block. Pulls the
   block components from `db` directly — `seon.render` cannot require
   `seon.agent.ctx` (that ns requires this one), so the lookup + decode
   live here rather than calling `seon.agent.ctx/agent-blocks`."
  [db agent-id block-name]
  (when (and db agent-id block-name)
    (let [ent (try (db/pull db '[{:seon.agent/ctx [*]}] [:seon.agent/id agent-id])
                   ;; probe: an unresolvable lookup-ref (agent absent) → nil
                   ;; block, the documented "no such block" path; expected
                   ;; absence, not a defect.
                   (catch :default _ nil))]
      (when-let [b (->> (:seon.agent/ctx ent)
                        (filter #(= block-name (:seon.agent.ctx/name %)))
                        first)]
        (cond-> b
          (contains? b :seon.render/html)
          (update :seon.render/html #(db/decode-edn-value :seon.render/html %))
          (contains? b :seon.render/ai)
          (update :seon.render/ai #(db/decode-edn-value :seon.render/ai %)))))))

(schema/register! ::slot-request
  [:map
   [:seon.db/db    {:optional true} :seon.db/db]
   [:seon.agent/id {:optional true} :string]])

(defn slot
  "Place the agent's block named `block-name` into a named tile slot.
   Looks the block up by `:seon.agent.ctx/name` in the agent's OWN
   `:seon.agent/ctx` (`ctx` carries `:seon.db/db` + `:seon.agent/id`),
   renders its `:seon.render/html` through the guarded engine, and wraps
   it as `[:div {:id \"tile-<name>\" :data-slot \"<name>\"} <html>]` — a
   stable DOM id for idiomorph. GUARDED: a missing block or a throwing
   render becomes a `:seon/error` value surfaced as an error tile, so a
   sibling slot never crashes (never-crash-always-surface). Injected into
   every render ctx as `:seon.render/slot`."
  {:malli/schema [:=> [:catn [::ctx ::slot-request] [::block-name :keyword]]
                  :seon.render.live-tile/hiccup]}
  [ctx block-name]
  (let [db    (or (:seon.db/db ctx) @db/*conn*)
        id    (:seon.agent/id ctx)
        block (agent-ctx-block db id block-name)
        body  (if (nil? block)
                (live-tile/error-tile
                  {:seon.error/message (str "no block named " block-name " on "
                                            (or id "this agent"))
                   :seon.error/where   block-name
                   :seon.error/hint    "install! it (or fix the slot name)"})
                (try
                  (render :seon.render/html (assoc ctx :seon.db/db db) block)
                  (catch :default e
                    ;; The inner `render` walker records + classifies the
                    ;; block-render failure (recorded? then skips here); a
                    ;; throw that reaches this slot machinery unrecorded is our
                    ;; own (:core). Record BEFORE strict-fail! (re-throws in
                    ;; strict mode).
                    (when-not (err/recorded? e)
                      (err/record! {:seon.error/raw e :seon.error/fault :core}))
                    ;; STRICT dial: dev/test/gym → re-throw LOUD; prod → guard.
                    (strict-fail! block-name e)
                    (live-tile/error-tile
                      {:seon.error/message (str block-name " render failed: "
                                                (err/->message e))
                       :seon.error/where   block-name}))))]
    [:div {:id (str "tile-" (name block-name)) :data-slot (name block-name)}
     body]))

