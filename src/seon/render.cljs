(ns seon.render
  "The two renders every renderable carries — `:seon.render/ai` (the
   prompt text) and `:seon.render/html` (a surface) — selected by key
   presence, never a stored discriminator.

   Each render attribute is a fully-qualified symbol or a literal value.
   [[render]] resolves symbols through `seon.eval/lookup-value`, selects the
   registered shape renderer when no explicit value exists, and otherwise
   uses its universal data renderer.

   ## The engine

   `render` is the whole system: one recursive, guarded walker over a
   node's children in two views (`:seon.render/ai` → String,
   `:seon.render/html` → hiccup). It injects a view-bound recursion
   handle (`:seon.render/render`) so a parent renders its children
   through the same dispatch. A throwing or missing render degrades
   to a legible value, never a crash.

   ## Late-bound symbol lookup

   `seon.eval/lookup-value` walks `js/globalThis` with `cljs.core/munge`
   per segment — works for core fns (shadow-cljs precompiled
   bundle) AND agent-defined fns (written by `cljs.js/eval-str` at the
   same munged paths). Single path, no boot-time wire-up needed."
  (:require
    [cljs.pprint :as pprint]
    [clojure.string :as str]
    [seon.config :as config]
    [seon.db :as db]
    [seon.error :as err]
    [seon.error.instrument :as einstrument]
    [seon.eval :as eval]
    [seon.render.canvas :as canvas]
    [seon.render.schema]
    [seon.render.sci :as render-sci]
    [seon.render.value :as value]
    [seon.code :as code]
    [seon.ai.tokens :as tokens]
    [seon.schema :as schema]
    [seon.ui.clojure :as cljhl]
    [seon.ui.html :as html]
    [seon.ui.markdown :as md]))

;; ============================================================
;; Schemas — every shape this surface reads or writes (spec-05 §15.1).
;; ============================================================

;; `:seon.db/db` + `:seon.db/conn` (the runtime db/conn handles) are
;; registered in their owning ns `seon.db` (loaded before this ns via the
;; require above) — a reference here just resolves them, no inline `:any`.

;; Hiccup has exactly ONE schema representation:
;; `:seon.render.canvas/hiccup` — the registered pure-data shallow
;; bound (vector with keyword head), defined in seon.render.canvas
;; (loads first). The deep recursive walk happens at the render
;; boundary only, via the PLAIN fn
;; `seon.render.canvas/valid-hiccup?` (registered forms must be
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
;; `:seon.render/ai` and `:seon.render/html` are registered once in the
;; dependency-free `seon.render.schema` leaf shared with context compilation.

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

;; `:seon.agent/id` is registered in seon.agent.ctx.render-fns — the
;; FIRST-loading ns whose load-time schema references it (C58 made
;; `::derived-blocks-request` there reference the registered shape;
;; register!'s compilability guard rejects forward references, so the
;; registration lives with the earliest referencer — the first-loading-ns
;; rule, the `:seon.ns/name` precedent). Moved seon.agent → here in C54,
;; here → render-fns in C58.

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

;; The `:seon.render/ai-response` envelope returned by AI-side renderers.
;; The old `:seon.render/text` second arm was
;; DELETED with the V4 composer rewrite (context-render keystone): the ONE
;; producer of it (`seon.agent.ctx/assemble-context`) is gone, and the keystone's
;; CONVERTERS return BARE Strings (not this envelope). One key, one meaning.
(schema/register! :seon.render/ai-response
  [:map
   [:seon.render/ai :string]])

;; The error envelope a failed render carries (canvas U1) — the
;; standard `:seon.error/*` shape, registered in seon.db.
(schema/register! :seon.render/error :seon.db/error)

;; The hiccup slot carries the PURE-DATA shallow bound
;; `:seon.render.canvas/hiccup` (vector with keyword head) — NOT
;; the deep recursive `:seon.render/hiccup` above (recursive seqex
;; trips :malli.core/potentially-recursive-seqex inside instrumented
;; fn schemas) and NOT `[:fn valid-hiccup?]` (registered forms must
;; be pure data — fn objects don't survive the form round-trip; see
;; the platform-law comment in seon.render.canvas). The deep walk
;; happens at the render boundary (html->string + valid-hiccup?).
;; `:nil` accepts render fns that explicitly return
;; `{:seon.render/hiccup nil}` to mean "render nothing"
;; (render-entity-html callers already handle nil via `or`).
;;
;; `:seon.render/ai` — the OPTIONAL ai render (PRD §2): how the agent
;; knows what its human sees. Canvas fns return it alongside the hiccup;
;; the awareness section renders it into the agent's context every turn.
;; Same render idea as `:seon.agent.ctx/block`.
;;
;; `:seon.render/error` — present when the renderer THREW: the hiccup
;; is the human fallback card and this entry carries the envelope so
;; the agent sees its own renderer is broken (vanish = banned).
(schema/register! :seon.render/html-response
  [:map
   [:seon.render/hiccup [:or :nil :seon.render.canvas/hiccup]]
   [:seon.render/ai    {:optional true} :string]
   [:seon.render/error {:optional true} :seon.render/error]
   [:seon.render.canvas/wired {:optional true}
    :seon.render.canvas/wired-response]])

;; A renderer receives ordinary discovery data. Database acquisition remains
;; with the operation that invokes it; the renderer is a pure projection of
;; the immutable values that operation selected.
(schema/register! :seon.render/system-input
  [:map
   [:seon.agent/id :string]
   [:seon.agent/entity :map]])

;; ============================================================
;; Entity-shape resolution from the active schema projection.
;; An entity has no kind — it is its attributes. `entity-primary-schema`
;; picks the most-specific catalog row whose required attrs are present;
;; `entity-render` resolves its renderer or a per-entity override.
;;
;; Shared by the test-capture-as-data rendering (`render-entity-html`
;; etc.); the debug view's right pane mirrors the left's block set
;; via these same converters.
;; ============================================================

(defn- entity-primary-schema
  "Pick the most-specific catalog shape whose required attrs are
   ALL present on `entity` (attribute-presence — an entity has no kind).
   Pure in-memory subset test against the active immutable projection.

   A schema 'fully matches' when every required attr is present on the
   entity. Among full matches, the schema with the most required attrs
   wins (specificity). Tie-broken alphabetically by `:seon.schema/key`
   for stable output (research §D)."
  [entity]
  (let [present (set (filter keyword? (keys entity)))]
    (when (seq present)
      (let [full (keep
                   (fn [catalog-row]
                     (let [req (:seon.schema.catalog/required-attrs
                                 catalog-row)]
                       (when (and (seq req)
                                  (every? #(contains? present %) req))
                         [catalog-row (count req)])))
                   (schema/entity-catalog))]
        (when (seq full)
          (->> full
               (sort-by
                 (juxt (comp - second)
                       (comp str :seon.schema.catalog/key first)))
               ffirst))))))

(defn- entity-render
  "Resolve the render value for `entity` in `render` (`:html` or `:ai`)
   — the two-step resolution both renders share: per-entity attr override
   wins (`:seon.render/html` / `:seon.render/ai`, bridge-decoded), else
   the entity's primary `:seon.schema` shape's default symbol. nil when
   neither step yields a value. The entity map and process-local schema
   catalog are the complete input; dispatch never observes a database."
  [entity render]
  (let [attr (case render :html :seon.render/html :ai :seon.render/ai)]
    (or (some->> (get entity attr)
                 (db/decode-edn-value attr))
        (let [catalog-row (entity-primary-schema entity)
              render-key (case render
                           :html :seon.schema.catalog/render-html
                           :ai   :seon.schema.catalog/render-ai)]
          (get catalog-row render-key)))))

;; ============================================================
;; The ONE envelope-unwrap — every render path consumes the SAME
;; `:seon.render/html-response` MAP contract here.
;;
;; A render fn (an entity converter, a ctx-block render, the canvas)
;; may return BARE content — hiccup for the html view, a String for the
;; ai view — OR the established `:seon.render/html-response` MAP envelope
;; `{:seon.render/hiccup <h> :seon.render/ai <s> …}`. This is the ONLY
;; place the envelope is unwrapped, so entity surfaces, context blocks,
;; and recursive sections never leak a raw map into hiccup (the
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

(defn unwrap-response
  "Extract `view`'s value from a render result `r`.

   When `r` is the
   `:seon.render/html-response` MAP envelope carrying `view`'s key, return
   that value (the hiccup or the ai String); otherwise pass the bare value
   through unchanged. A non-envelope map (one lacking the key) passes
   through too — only the established contract is unwrapped."
  {:malli/schema [:=> [:cat :keyword :any] :any]}
  [view r]
  (let [k (view->response-key view)]
    (if (and (map? r) (contains? r k))
      (get r k)
      r)))

;; ============================================================
;; Fail-loud render dial — the ONE place every render swallow-guard routes
;; its caught exception. When `seon.config/render-strict?` is ON (dev / test
;; / benchmark), a render/converter failure RE-THROWS with the
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
   posture as the canvas's `error-response`): the surface becomes a
   legible error banner naming the fn + message, siblings render
   untouched, the page stays 200.

   `input` is ordinary render data every render fn receives:
     {:seon.agent/id <agent-id>
      :seon.render/node <entity-map>}
   (`:seon.render/entity` is tolerated as the node key for older callers.)"
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :any]]}
  [{:seon.render/keys [entity node] :as input}]
  (let [entity (or node entity)]
    (when-let [sym (entity-render entity :html)]
      (try
        (let [f (eval/lookup-value sym)
              r (when f (f (assoc input :seon.render/node entity)))]
          ;; Converters return BARE hiccup; a per-entity renderer
          ;; (agent-authored, test fixture, the canvas contract) may
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
          ;; STRICT dial: dev/test/benchmark → re-throw LOUD; prod → graceful guard.
          (strict-fail! sym e)
          (canvas/error-card
            {:seon.error/message (str sym " threw: " (or (.-message e) (str e)))
             :seon.error/symbol  sym}))))))

;; ============================================================
;; THE typed-block renderer — `block`. One guarded `value → render` fn
;; that DISPATCHES ON VALUE-KIND so every surface (transcript entry, canvas,
;; /debug) "just displays the block." It is NOT a new mechanism: it
;; GENERALIZES the same unwrap/guard seam `render-entity-html` centralizes
;; (the ONE `unwrap-response`) from "dispatch on the entity's render symbol"
;; to "dispatch on the value's KIND," reusing the existing renderers that
;; already exist (md->hiccup, the value panel, clj->hiccup, the error-card
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
(schema/register! :seon.render/formats [:set :seon.render/view])

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

(defn- code-fenced
  "A `seon.code/block` value as a lang-tagged markdown fenced code block —
   `\"```<lang>\\n<text>\\n```\"`. Reuses the ONE markdown path (`md/md->hiccup`
   for html, the string itself for ai) so `#code` renders as highlighted
   code, NOT an escaped Clojure string. Ensures a trailing newline before
   the closing fence so a payload without one still closes cleanly."
  [x]
  (let [txt (code/text x)
        txt (if (str/ends-with? txt "\n") txt (str txt "\n"))]
    (str "```" (name (:seon.code/lang x)) "\n" txt "```")))

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
  {:malli/schema [:=> [:catn [::view :seon.render/view]
                             [:seon.config/configuration
                              :seon.config/singleton]
                             [::x :any]] :any]}
  [view configuration x]
  (try
    (case view
      :html
      (cond
        (code/block? x)    (md/md->hiccup (code-fenced x))
        (message-block? x) (md/md->hiccup (:seon.render/markdown x))
        (source-block? x)  (cljhl/clj->hiccup (:seon.render/source x))
        (data-projection? x) (data-panel x)
        (error-value? x)   (canvas/error-card x)
        (canvas/valid-hiccup? x) x
        :else              (data-panel
                             (value/render-html-data configuration "inline" x)))

      :ai
      (cond
        (code/block? x)    (code-fenced x)
        (message-block? x) (:seon.render/markdown x)
        (source-block? x)  (:seon.render/source x)
        (data-projection? x) (str (:seon.render.value/summary x)
                                   (when (:seon.render.value/truncated? x) " (partial)"))
        (error-value? x)   (:seon.error/message x)
        (canvas/valid-hiccup? x) (hiccup-text x)
        :else              (value/render-ai configuration "inline" x)))
    (catch :default e
      ;; `block` dispatches to CORE renderers (md->hiccup, clj->hiccup, the
      ;; value panels) — a throw is our machinery (:core). Record BEFORE
      ;; strict-fail! (re-throws in strict mode); recorded? skips a funnel dup.
      (when-not (err/recorded? e)
        (err/record! {:seon.error/raw e :seon.error/fault :core}))
      ;; STRICT dial: dev/test/benchmark → re-throw LOUD; prod → graceful guard.
      (strict-fail! :block e)
      (let [msg (str "block render failed: " (err/->message e))]
        (case view
          :html (canvas/error-card {:seon.error/message msg :seon.error/where :block})
          :ai   msg)))))

(defn render-entity-ai
  "Render `entity` to text via its resolved `:seon.render/ai` symbol.
   Per-entity override wins; else schema property for the entity's
   primary schema. Returns nil if no symbol resolves OR the fn returns
   nil. Mirror of `render-entity-html` for the AI path.

   The schema's ai symbol IS a converter (`seon.handlers.*/render-ai`)
   returning a BARE String, called with the entity under
   `:seon.render/node` (`:seon.render/entity` tolerated)."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :string]]}
  [{:seon.render/keys [entity node] :as input}]
  (let [entity (or node entity)]
    (when-let [sym (entity-render entity :ai)]
      (try
        (let [f (eval/lookup-value sym)
              r (when f (f (assoc input :seon.render/node entity)))]
          ;; Converters return a BARE String; a per-entity renderer may
          ;; return the {:seon.render/ai s …} envelope — unwrapped via the
          ;; ONE shared path (the ai twin of render-entity-html).
          (unwrap-response :seon.render/ai r))
        ;; A throwing AI renderer is LEGIBLE, never nil-vanished — the
        ;; agent reading its context sees its own renderer is broken
        ;; (mirror of the html banner above / the canvas's error render).
        (catch :default e
          ;; Classify by the render symbol (fault-for): agent-authored
          ;; converter → :agent, core converter → :core. Record BEFORE
          ;; strict-fail! (re-throws in strict mode); recorded? skips a dup.
          (when-not (err/recorded? e)
            (err/record! {:seon.error/raw e :seon.error/fault (err/fault-for sym)}))
          ;; STRICT dial: dev/test/benchmark → re-throw LOUD; prod → legible line.
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
   name. Shown in the transcript / web UI so the agent can
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
  "resolve-render step 4 — the renderer the node's primary `:seon.schema`
   shape registers (or a per-entity slot override), via the existing
   `entity-render` / `entity-primary-schema` dispatch. Calls the resolved
   converter symbol (bare value); nil when no schema matches. Selection uses
   only the ordinary node and process-local schema catalog."
  [view node input]
  (let [in (assoc input :seon.render/node node)]
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
              ;; canvas). Never the unbounded compiled call: a hang there
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
   becomes the overridable error card, never crashes."
  {:malli/schema [:=> [:cat :keyword :map :any] :any]}
  [view ctx node]
  (if (:seon.render/hidden? node)
    (when (= view :seon.render/ai)
      (str ";; (1 pruned — " (renderable-id node)
           "; (seon.agent/unprune! …) to restore)"))
    (let [f  (resolve-render view node)
          in (assoc ctx :seon.render/node   node
                        :seon.render/render #(render view ctx %))]
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
          ;; STRICT dial: dev/test/benchmark → re-throw LOUD (block name + full
          ;; malli explain); prod → fall through to the graceful guard below.
          (strict-fail! (renderable-id node) e)
          (if (= view :seon.render/ai)
            (str ";; ⚠ [" (renderable-id node) "] render failed: " (ex-message e))
            (canvas/error-card
              {:seon.error/message (str (renderable-id node) " — " (ex-message e))})))))))
