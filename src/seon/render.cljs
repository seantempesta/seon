(ns seon.render
  "Render surfaces — two today (`:seon.render/ai` and
   `:seon.render/html`), `:seon.render/canvas` planned (v2/v3).

   Each agent entity carries one slot per surface — a fully-qualified
   symbol naming the fn to call. `*-render` resolves the symbol via
   `seon.eval/lookup-value` and calls it; if the slot is nil,
   unqualified, or points at an unresolvable symbol, falls through to
   `seon.render.default/pretty-*` so the surface never crashes.

   See [[../prds/agent-runtime/v1.md]] §5 for the AI surface (the
   section composer producing the turn's prompt text) and
   [[../prds/agent-runtime/v2.md]] 'Per-section HTML composer' for the
   HTML mirror (section fns grow `:seon.render/hiccup` in their
   return map alongside `:seon.render/text`).

   ## Naming note

   `ai-render` / `html-render` are thin: resolve symbol → call fn →
   fall back to pretty-print. They are NOT multimethod dispatch.
   V2's per-entity Malli-specificity dispatch is the real
   data-shape-driven pick-the-renderer — it lives in `seon.eval`
   alongside the program-graph queries it needs.

   ## Late-bound symbol lookup

   `seon.eval/lookup-value` walks `js/globalThis` with `cljs.core/munge`
   per segment — works for core fns (shadow-cljs precompiled
   bundle) AND agent-defined fns (written by `cljs.js/eval-str` at the
   same munged paths). Single path, no boot-time wire-up needed."
  (:require
    [cljs.pprint :as pprint]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.db :as db]
    [seon.error :as err]
    [seon.eval :as eval]
    [seon.render.default :as default]
    [seon.render.live-tile :as live-tile]
    [seon.render.sci :as render-sci]
    [seon.schema :as schema]
    [seon.ui.html :as html]
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
(schema/register! :seon.render/children
  [:vector {:seon.db/component true} :seon.db/ref])      ;; OPTIONAL authored nesting; derived sections query instead

;; The `:seon.render/ai-response` envelope — the LIVE-TILE / default
;; slot-primitive return shape (`ai-render` → `seon.render.default/pretty-ai`
;; and `seon.render.chat`). The old `:seon.render/text` second arm was
;; DELETED with the V4 composer rewrite (context-render keystone): the ONE
;; producer of it (`seon.ctx/assemble-context`) is gone, and the keystone's
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
;; `:seon.render/ai` — the OPTIONAL text twin (live-tiles U1, PRD §2):
;; how the agent knows what its human sees. Tile fns return it
;; alongside the hiccup; the awareness section renders it into the
;; agent's context every turn. Same twin idea as `:seon.ctx/section`.
;;
;; `:seon.render/error` — present when the renderer THREW: the hiccup
;; is the human fallback card and this entry carries the envelope so
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
  "Materialize an :seon.render/ai slot. Slot is a qualified symbol;
   if it doesn't resolve (or is nil / unqualified), fall through to
   seon.render.default/pretty-ai so the agent always gets some ctx."
  {:malli/schema [:=> [:cat :any :map] :seon.render/ai-response]}
  [sym input-map]
  (let [f (or (eval/lookup-value sym) default/pretty-ai)]
    (f input-map)))

(defn html-render
  "Materialize an :seon.render/html slot. Slot is either a symbol
   (resolved + called), a literal hiccup vector (used as-is), or
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
;; Entity-kind resolution (the `:seon.schema`-driven renderer dispatch).
;;
;; Renderable entity KINDS are `:seon.schema` rows carrying both an
;; id-attr and a `:seon.schema/render-fn`. `entity-primary-kind` picks
;; the most-specific kind whose required-attrs are all present on an
;; entity; `entity-render-slot` / `render-entity-html` / `render-entity-ai`
;; resolve a render symbol from that kind (or a per-entity override).
;;
;; This machinery is shared by the test-capture-as-data rendering
;; (`render-entity-html` etc.). The legacy per-entity inspector window
;; (`visible-entities` / `renderable-entities`) that also rode on it was
;; deleted when the debug right pane switched to section html twins
;; (debug-view-section-twins-2026-06-18) — the right pane now mirrors the
;; left's section set rather than a last-N-by-tx-time entity window.
;; ============================================================

(defn- renderable-kinds
  "Datalog-driven enumeration of every RENDERABLE entity-shape
   `:seon.schema` row in the DB — rows carrying BOTH an id-attr and a
   `:seon.schema/render-fn`. Returns a seq of `{:kind <kw> :id-attr <kw>
   :ai <sym> :html <sym>}`. Each schema entity is materialized at agent
   boot from `seon.schema/all-entity-schemas-tx-data` (and on every
   subsequent `register!`), so the renderer reads schemas from
   core state instead of walking the in-memory
   `seon.schema/*schemas` atom.

   The render-fn clause is load-bearing, not cosmetic: a row WITHOUT a
   renderer has no symbol for `entity-render-slot` to resolve, and its
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
           {:kind    k
            :id-attr id-attr
            :ai      ai
            :html    (get htmls k)})
         base)))

;; Single-slot cache for the schema-kind lookup tables, keyed by db
;; value identity. `entity-primary-kind` used to run one datalog query
;; PER ENTITY through the FilteredDB (each datom access re-runs the
;; filter pred) — the dominant cost of an inspector render on the
;; file-backed store. The tables derive purely from `:seon.schema`
;; rows, which are immutable for a given db value, so one slot keyed
;; by `identical?` is correct and survives exactly as long as the
;; render that's using it.
(defonce ^:private !kind-cache (atom nil))

(defn- kind-tables
  "Return `{:kinds <renderable-kinds seq> :kinds-by-kw {<kw> <info>}
   :required-by-kind {<kw> #{<attr> …}}}` for `db`, computed once per
   db value (single-slot identity-keyed cache)."
  [db]
  (let [c @!kind-cache]
    (if (and c (identical? (:db c) db))
      (:tables c)
      (let [kinds    (renderable-kinds db)
            req-rows (d/q '[:find ?key ?req
                            :where
                            [?s :seon.schema/key ?key]
                            [?s :seon.schema/required-attrs ?req]]
                          db)
            required (reduce (fn [m [k req]]
                               (update m k (fnil conj #{}) req))
                             {} req-rows)
            tables   {:kinds            kinds
                      :kinds-by-kw      (into {} (map (juxt :kind identity) kinds))
                      :required-by-kind required}]
        (reset! !kind-cache {:db db :tables tables})
        tables))))

(defn- entity-primary-kind
  "Pick the most-specific `:seon.schema` kind whose required-attrs are
   ALL present on `entity`. Pure in-memory subset test against the
   per-db cached `:required-by-kind` table ([[kind-tables]]) — the
   former per-entity datalog query was the inspector's render
   bottleneck on the file store.

   A schema 'fully matches' when every required attr is present on the
   entity. Among full matches, the schema with the most required attrs
   wins (specificity). Tie-broken alphabetically by `:seon.schema/key`
   for stable output (research §D)."
  [db entity]
  (let [present (set (filter keyword? (keys entity)))]
    (when (seq present)
      (let [{:keys [required-by-kind]} (kind-tables db)
            full (keep (fn [[k req]]
                         (when (and (seq req)
                                    (every? #(contains? present %) req))
                           [k (count req)]))
                       required-by-kind)]
        (when (seq full)
          (->> full
               (sort-by (juxt (comp - second) (comp str first)))
               ffirst))))))

(defn- entity-render-slot
  "Resolve the render value for `entity` on `surface` (`:html` or
   `:ai`) — THE two-step resolution both twins share: per-entity attr
   override wins (`:seon.render/html` / `:seon.render/ai`,
   bridge-decoded), else the entity's primary `:seon.schema` kind's
   default symbol. nil when neither step yields a value.

   (Replaces the former entity-html-sym/entity-ai-sym twins — one
   resolution path, one dispatch; render sweep 2026-06-11.)"
  [db entity surface]
  (let [attr (case surface :html :seon.render/html :ai :seon.render/ai)]
    (or (some->> (get entity attr)
                 (db/decode-edn-value attr))
        (let [{:keys [kinds-by-kw]} (kind-tables db)
              kind (entity-primary-kind db entity)]
          ;; NOTE: `(get kinds-by-kw kind)`, NOT `(some-> kinds-by-kw kind …)`
          ;; — the latter invokes `kind` as a fn and throws a TypeError
          ;; when entity-primary-kind returns nil (no kind matched).
          (get (get kinds-by-kw kind) surface)))))

(defn render-entity-html
  "Render `entity` to hiccup via its resolved `:seon.render/html` symbol.
   Per-entity override wins; else falls back to the entity-kind schema's
   default html symbol. Returns nil when no symbol resolves OR the
   resolved fn returns nil.

   The kind's html symbol IS a converter (`seon.handlers.*/render-html`)
   that returns BARE hiccup, called with the entity under
   `:seon.render/node`. A renderer that THROWS does NOT vanish (same
   posture as the live tile's `error-response`): the card becomes a
   legible error banner naming the fn + message, siblings render
   untouched, the page stays 200.

   `input` is the system-input shape every render fn receives:
     {:seon.db/db    <db>
      :seon.agent/id <agent-id>
      :seon.render/node <entity-map>}
   (`:seon.render/entity` is tolerated as the node key for older callers.)"
  {:malli/schema [:=> [:cat :map] [:maybe :any]]}
  [{:seon.db/keys [db] :seon.render/keys [entity node] :as input}]
  (let [db     (or db @db/*conn*)
        entity (or node entity)]
    (when-let [sym (entity-render-slot db entity :html)]
      (try
        (let [f (eval/lookup-value sym)
              r (when f (f (assoc input :seon.render/node entity)))]
          ;; Converters return BARE hiccup; tolerate a per-entity renderer
          ;; (agent-authored, test fixture) still returning the old
          ;; {:seon.render/hiccup h} envelope.
          (if (and (map? r) (contains? r :seon.render/hiccup))
            (:seon.render/hiccup r)
            r))
        (catch :default e
          [:div {:class (str "flex flex-col gap-1 p-3 border "
                             "border-error/40 bg-error/10 rounded")}
           [:div {:class "text-xs text-error font-mono font-bold"}
            "⚠ render error"]
           [:div {:class "text-xs font-mono text-text-300 break-all"}
            (str sym " threw: " (or (.-message e) (str e)))]])))))

;; ============================================================
;; Agent tile (live-tiles U1) — the agent's ONE always-visible HTML
;; surface. Resolution (seon.render.live-tile/wired-content):
;; per-entity `:seon.render.live-tile/content` → the core
;; welcome. Neither `:seon.render/html` nor the `:seon.agent` KIND
;; default is consulted for the TILE — that key means only the
;; generic entity-card render (one key, one meaning; PRD §8.1).
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
  "Render the agent's live tile — the one HTML surface the agent
   dynamically rewrites (by transacting a qualified fn symbol or
   literal hiccup onto `:seon.render.live-tile/content` on its own
   agent entity; see seon.render.live-tile's ns docstring for the
   full contract).

   Returns `:seon.render/html-response`. A renderer that THROWS does
   NOT vanish: the response is `seon.render.live-tile/error-response`
   — fallback card for the human, `:seon.render/error` envelope +
   `:seon.render/ai` twin for the agent. nil hiccup only when the
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
                 (catch :default _ nil))]
    (if (nil? (:seon.agent/id ent))
      {:seon.render/hiccup nil}
      (let [{:seon.render.live-tile/keys [value]}
            (live-tile/wired-content {:seon.render/entity ent})
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
                                (render-sci/agent-authored-sym? value))
                         (let [r (render-sci/invoke-bounded value input)]
                           (cond
                             ;; deadline tripped — reset the tile to welcome +
                             ;; warn the agent (async, deduped), and render the
                             ;; known-good welcome for the human this turn.
                             (:seon.render.sci/interrupt r)
                             (do (render-sci/recover-hung-tile!
                                   id value render-sci/default-budget-ms)
                                 (html-render live-tile/welcome-sym input))
                             ;; no stored source to interpret — use the normal
                             ;; compiled path (no worse than today).
                             (:seon.render.sci/fallthrough r)
                             (html-render value input)
                             :else r))
                         (html-render value input))
                ;; INTERACTIVITY: rewrite agent fn-call / fn-ref handler slots
                ;; in AGENT-authored hiccup into standard Datastar
                ;; `@post('/call?…')` (seon.web.reactive.transform). Bare
                ;; handler symbols qualify to the tile fn's namespace; /call
                ;; routes by that namespace into the owning agent's sandbox.
                ;; No-op on core hiccup + hiccup with no interactive handlers.
                resp   (if (render-sci/agent-authored-sym? value)
                         (update resp :seon.render/hiccup
                                 (fn [h]
                                   (if h
                                     (transform/transform-hiccup
                                       (symbol (namespace value)) h)
                                     h)))
                         resp)
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
            ;; A broken tile must never crash the render and never show the
            ;; human a scary error: return the calm 'updating this panel' card
            ;; for the human. The agent is NOT actively pushed a message (#43 /
            ;; D2 — a forged self-message wakes + defeats the halt); breakage
            ;; is a DERIVED surface: error-response's :seon.render/ai twin
            ;; ("YOUR LIVE TILE IS BROKEN …") is re-derived into the agent's
            ;; live-tile context section every turn, self-healing on the next
            ;; clean render. No stored flag, no notification.
            (live-tile/error-response
              {:seon.db/error                 (err/->map e)
               :seon.render.live-tile/content value})))))))

(defn render-entity-ai
  "Render `entity` to text via its resolved `:seon.render/ai` symbol.
   Per-entity override wins; else schema property for the entity's
   primary kind. Returns nil if no symbol resolves OR the fn returns
   nil. Mirror of `render-entity-html` for the AI path.

   The kind's ai symbol IS a converter (`seon.handlers.*/render-ai`)
   returning a BARE String, called with the entity under
   `:seon.render/node` (`:seon.render/entity` tolerated)."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [{:seon.db/keys [db] :seon.render/keys [entity node] :as input}]
  (let [db     (or db @db/*conn*)
        entity (or node entity)]
    (when-let [sym (entity-render-slot db entity :ai)]
      (try
        (let [f (eval/lookup-value sym)
              r (when f (f (assoc input :seon.render/node entity)))]
          ;; Converters return a BARE String; tolerate a per-entity renderer
          ;; still returning the old {:seon.render/ai s} envelope.
          (if (and (map? r) (contains? r :seon.render/ai))
            (:seon.render/ai r)
            r))
        ;; A throwing AI renderer is LEGIBLE, never nil-vanished — the
        ;; agent reading its context sees its own renderer is broken
        ;; (mirror of the html banner above / the tile's error twin).
        (catch :default e
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
   :seon.render/hidden? :seon.render/children :seon.ctx/priority])

(defn renderable-id
  "A node's stable HANDLE — its own identity attr (dispatch by presence), or a
   section's name. Shown in the transcript / inspector so the agent can
   reference or override it. Never a stored :seon.render/id."
  {:malli/schema [:=> [:cat :any] :any]}
  [node]
  (or (:seon.agent.message/id node)
      (:seon.eval/id node)
      (:seon.agent.todo/id node)
      (:seon.ctx/name node)
      (:db/id node)))

(defn renderable-inst
  "A node's TIME for sorting + relative display — the :db/txInstant the store
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

(declare render)

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
  "resolve-slot step 4 — the renderer the node's primary `:seon.schema` kind
   registers (or a per-entity slot override), via the existing
   `entity-render-slot` / `entity-primary-kind` dispatch. Calls the resolved
   converter symbol (bare value); nil when no kind matches."
  [view node input]
  (let [db (or (:seon.db/db input) @db/*conn*)
        in (assoc input :seon.db/db db :seon.render/node node)]
    (case view
      :seon.render/html (render-entity-html in)
      (render-entity-ai in))))

(defn- missing-slot-render
  "A legible, self-healing line for a slot symbol that resolves NOWHERE
   (neither SCI source nor a compiled var). Surfaces loudly instead of
   silently dropping the section — the agent sees what to fix; defining the
   fn self-heals the section next render. nil hiccup for the html view."
  [view id sym]
  (when (= view :seon.render/ai)
    (str "[" (name (or id :unnamed)) "] render failed: fn " sym
         " does not resolve — define it (or fix the symbol) and this "
         "section self-heals next render")))

(defn- resolve-slot
  "The render fn for `node` in `view`:
     1. read the slot (already decoded — DB-pulled sections are slot-decoded
        before they become nodes; in-memory sections carry literal values);
     2. string → verbatim; shallow-hiccup vector → verbatim;
     3. fn-symbol → the fn. An AGENT-authored symbol is invoked SCI-BOUNDED
        (a runaway agent fn must not freeze the single-threaded pod);
        a core symbol calls direct (fast, trusted);
     4. absent → the schema-default (the node's primary kind's converter);
     5. none → the GENERIC default (any data → Clojure / a dump)."
  [view node]
  (let [slot (get node view)]
    (cond
      (string? slot) (fn [_] slot)
      (vector? slot) (fn [_] slot)
      (symbol? slot)
      (if (render-sci/agent-authored-sym? slot)
        (fn [in]
          (let [r (render-sci/invoke-bounded slot in view)]
            (cond
              ;; deadline tripped → render nothing (a section never crashes
              ;; its siblings; the recovery path warns the agent).
              (and (map? r) (:seon.render.sci/interrupt r)) nil
              ;; SCI could not run it — fall back to the COMPILED fn (the SCI
              ;; env was just incomplete). If the symbol resolves nowhere, it
              ;; is a genuinely-missing slot → a legible self-heal line.
              (and (map? r) (:seon.render.sci/fallthrough r))
              (if-let [f (eval/lookup-value slot)]
                (f in)
                (missing-slot-render view (renderable-id node) slot))
              :else r)))
        (let [f (eval/lookup-value slot)]
          (if f f (fn [_] (missing-slot-render view (renderable-id node) slot)))))
      ;; no explicit slot: try the node's schema-kind converter; if no kind
      ;; matches (nil), fall to the generic any-data default.
      :else (fn [input]
              (or (schema-default-renderer view node input)
                  ((generic-default-renderer view) input))))))

(defn render
  "Render ONE node in `view`, recursively + guarded. The fn receives the full
   injected context PLUS the node and a view-bound recursion handle
   (`:seon.render/render`) so a section renders its children through the same
   dispatch. Returns a String (`:seon.render/ai`) or hiccup
   (`:seon.render/html`). A hidden node contributes a one-line prune note (ai)
   or nothing (html); a throwing fn renders a legible error, never crashes."
  {:malli/schema [:=> [:cat :keyword :map :any] :any]}
  [view ctx node]
  (if (:seon.render/hidden? node)
    (when (= view :seon.render/ai)
      (str ";; (1 pruned — " (renderable-id node)
           "; (seon.agent/unprune! …) to restore)"))
    (let [f  (resolve-slot view node)
          in (assoc ctx :seon.render/node   node
                        :seon.render/render  #(render view ctx %))]
      (try
        (f in)                                  ;; converters return bare String / hiccup
        (catch :default e
          (if (= view :seon.render/ai)
            (str ";; ⚠ [" (renderable-id node) "] render failed: " (ex-message e))
            [:div.render-error "⚠ " (str (renderable-id node)) " — " (ex-message e)]))))))

