(ns seon.render
  "Resolve the model and human twins of derived renders.

   Each render attribute is a fully-qualified symbol or a literal value.
   [[render]] classifies symbols before resolution. Trusted core symbols come
   only from the caller's immutable compiled table; agent-authored symbols
   execute only through the caller's guarded SCI invocation door.

   ## The engine

   `render` is the whole system: one recursive, guarded walker over a
   node's children in two views (`:seon.render/ai` → String,
   `:seon.render/html` → hiccup). It injects a view-bound recursion
   handle (`:seon.render/render`) so a parent renders its children
   through the same dispatch. A throwing or missing render degrades
   to a legible value, never a crash.

   ## Late-bound symbol lookup

   `seon.error/agent-authored-sym?` is the structural trust split. A hostile
   stored symbol cannot gain compiled authority by choosing a core-looking
   name: a non-authored symbol absent from the static table is missing."
  (:require
    [clojure.string :as str]
    #?(:cljs [seon.config :as config])
    [seon.db :as db]
    [seon.db.id]
    [seon.error :as err]
    [seon.error.instrument :as einstrument]
    [seon.render.canvas :as canvas]
    [seon.render.configuration :as rconfig]
    [seon.render.core :as core]
    [seon.render.schema]
    [seon.render.value :as value]
    [seon.code :as code]
    [seon.ai.tokens :as tokens]
    [seon.schema :as schema]
    [seon.ui.clojure :as cljhl]
    [seon.ui.html :as html]
    [seon.ui.markdown :as md]))

(schema/register! ::trusted-renderers [:map-of :symbol 'fn?])
(schema/register! ::invoke-authored! 'fn?)
(schema/register! ::function-symbol :symbol)
(schema/register! ::arguments [:vector :any])
(schema/register!
 ::authored-invocation
 [:map {:closed true}
  [::function-symbol ::function-symbol]
  [::arguments ::arguments]])

(declare unwrap-response)

(defn- error-value?
  [x]
  (and (map? x) (string? (:seon.error/message x))))

(defn- symbol-call
  "Resolve and invoke one stored render symbol through its trust boundary."
  [input sym arguments]
  (if (err/agent-authored-sym? sym)
    (when-let [invoke-authored! (::invoke-authored! input)]
      (invoke-authored! {::function-symbol sym ::arguments (vec arguments)}))
    (when-let [f (get (or (::trusted-renderers input) core/renderers) sym)]
      (apply f arguments))))

(defn- render-result
  [view value]
  (if (error-value? value)
    value
    (unwrap-response
     (case view
       :html :seon.render/html
       :ai :seon.render/ai
       view)
     value)))

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
;; The render/turn BASIS TRANSACTION — the Datahike transaction id of the
;; db value a render/turn is computed over ("now" = `(db/basis-t)`), replayable
;; via `(db/as-of db at)`. An INJECTABLE: a map-in fn declaring it
;; `{:optional true}` gets the current basis filled at the eval boundary
;; (`seon.instrument/injectables`); explicit args win (forensic replay passes
;; a past t). An int tx-id, NOT an inst — the tx-id is the exact reproducible
;; database value; wall-clock is derivable from the tx's `:db/txInstant`.
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
   [:seon.agent.run/id {:optional true} :seon.db.id/compact-value]
   [:seon.render/at {:optional true} :seon.render/at]
   [:seon.schema/projection {:optional true} :seon.schema/projection]])

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
                      (catch #?(:clj Throwable :cljs :default) e2
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
  {:malli/schema [:=> [:cat :seon.config/singleton :any :any] [:maybe :nil]]}
  [configuration where e]
  (when #?(:cljs (config/render-strict? configuration)
           :clj
           (true?
            (rconfig/value configuration
                           :seon.config.render-context/render-strict?
                           false)))
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

   The schema's html symbol IS a converter (`seon.render.handlers.*/render-html`)
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
        (let [r (symbol-call input sym
                             [(assoc input :seon.render/node entity)])]
          ;; Converters return BARE hiccup; a per-entity renderer
          ;; (agent-authored, test fixture, the canvas contract) may
          ;; return the {:seon.render/hiccup h …} envelope — unwrapped via
          ;; the ONE shared path so every renderer obeys one contract.
          (render-result :seon.render/html r))
        (catch #?(:clj Throwable :cljs :default) e
          ;; Classify by the render symbol (fault-for): an agent-authored
          ;; converter → :agent, a core `seon.render.handlers.*` converter → :core.
          ;; Record BEFORE strict-fail! (which re-throws in strict mode,
          ;; bypassing the tail). recorded? skips an inner funnel's datom.
          (when-not (err/recorded? e)
            (err/record! {:seon.error/raw e :seon.error/fault (err/fault-for sym)}))
          ;; STRICT dial: dev/test/benchmark → re-throw LOUD; prod → graceful guard.
          (strict-fail! (:seon.config/configuration input) sym e)
          (canvas/error-card
            {:seon.error/message (str sym " threw: " (or (ex-message e) (str e)))
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
(schema/register! :seon.render/schema-key :keyword)
(schema/register! :seon.render/eval-id :string)
(schema/register! :seon.render/entity-id :int)
(schema/register! :seon.render/value-selector
  [:or
   [:map {:closed true}
    [:seon.render/eval-id :seon.render/eval-id]]
   [:map {:closed true}
    [:seon.render/entity-id :seon.render/entity-id]]])
(schema/register! :seon.render/value-route-base :string)
(schema/register! :seon.render/value-request
  [:map {:closed true}
   [:seon.render/value-route-base :seon.render/value-route-base]
   [:seon.render/value-selector :seon.render/value-selector]
   [:seon.render/value-projection :seon.render.value/drilled-projection]])

(defn- message-block? [x]
  (and (map? x) (contains? x :seon.render/markdown)))

(defn- source-block? [x]
  (and (map? x) (contains? x :seon.render/source)))

(defn- data-projection? [x]
  (and (map? x) (contains? x :seon.render.value/tree)))

(defn- value-request? [x]
  (and (map? x) (contains? x :seon.render/value-projection)))

(defn- value-leaf
  "A non-container `render-html-data` skeleton node → a styled inline token.
   Mirrors `seon.render.value`'s marker tokens (datom / opaque / clipped
   string), plus plain scalars."
  [x]
  (cond
    (and (map? x) (contains? x :seon.eval/datom))
    [:span {:class "text-keyword font-mono"} (value/datom-token x)]

    (and (map? x) (contains? x :seon.eval/opaque))
    [:span {:class "text-text-400 font-mono italic"}
     (value/opaque-token x)]

    (and (map? x) (contains? x :seon.render.value/string-len))
    (let [token (value/clipped-string-token x)
          suffix-start (str/last-index-of token "⟨")]
      [:span {:class "text-success font-mono break-all"}
       (subs token 0 suffix-start)
       [:span {:class "ml-1"} (subs token suffix-start)]])

    :else
    [:span {:class (str "font-mono break-all "
                        (cond (string? x)  "text-success"
                              (keyword? x) "text-keyword"
                              (number? x)  "text-eval"
                              (nil? x)     "text-text-600"
                              :else        "text-text-200"))}
     (pr-str x)]))

(defn- selector-query-entry [selector]
  (if-let [eval-id (:seon.render/eval-id selector)]
    ["eval" eval-id]
    ["entity" (:seon.render/entity-id selector)]))

(defn- value-url
  [{:seon.render/keys [value-route-base value-selector]} path offset]
  #?(:cljs
     (let [[selector-name selector-value] (selector-query-entry value-selector)
           params (js/URLSearchParams.)]
       (.append params selector-name (str selector-value))
       (.append params "path" (pr-str path))
       (.append params "offset" (str offset))
       (str value-route-base "?" (.toString params)))
     :clj nil))

(defn value-unit-id
  "Stable DOM id for one authorized logical value subtree."
  {:malli/schema
   [:=> [:catn [::render-request :seon.render/section-request]
                [::value-selector :seon.render/value-selector]
                [::path :seon.render.value/path]]
    :string]}
  [render-request value-selector path]
  (value/value-unit-id render-request value-selector path))

(defn- value-identity
  [render-request value-request path]
  (value-unit-id render-request
                 (:seon.render/value-selector value-request)
                 path))

(defn- drill-control [value-request path offset label]
  #?(:cljs
     [:button {:type "button"
               :class (str "text-2xs font-mono text-amber-400/80 "
                           "hover:text-amber-300 underline underline-offset-2")
               (keyword "data-on:click")
               (str "@get(" (js/JSON.stringify
                               (value-url value-request path offset)) ")")}
      label]
     :clj
     [:span {:class "text-2xs font-mono text-text-600"} label]))

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

(defn- map-node [m depth render-request value-request path drillable?]
  (let [wrapped? (contains? m :seon.render.value/map-entries)
        elided (when wrapped? (:seon.render.value/elided-keys m))
        non-drillable (when wrapped?
                        (set (:seon.render.value/non-drillable-key-indexes m)))
        pairs  (if wrapped?
                 (:seon.render.value/map-entries m)
                 (seq m))
        n      (count pairs)
        rows   (cond-> (vec (map-indexed
                              (fn [index [k v]]
                                (let [child-drillable?
                                      (and drillable?
                                           (not (contains? non-drillable index)))
                                      child-path (when child-drillable?
                                                   (conj path k))]
                                  [:div {:class "flex items-start gap-1.5 text-xs min-w-0"}
                                   [:span {:class "text-keyword shrink-0 font-mono"}
                                    (tokens/bounded-pr-str k 20)]
                                   (value-node v (inc depth) render-request value-request
                                               child-path child-drillable? true)]))
                              pairs))
                 elided
                 (conj [:div {:class "text-2xs text-text-600 font-mono"}
                        (if (= :more elided)
                          "… +more keys"
                          (str "… +" elided " more key"
                               (when (not= 1 elided) "s")))])
                 (seq non-drillable)
                 (conj [:div {:class "text-2xs text-text-600 font-mono"}
                        (str (count non-drillable) " non-drillable key"
                             (when (not= 1 (count non-drillable)) "s")
                             " shown safely")]))]
    (value-details
      [:span {:class "text-text-400 font-mono"}
       (str "{} " n " key" (when (not= 1 n) "s")
            (when elided (if (= :more elided)
                           " +more hidden"
                           (str " +" elided " hidden")))
            (when (seq non-drillable)
              (str " · " (count non-drillable) " safe key label"
                   (when (not= 1 (count non-drillable)) "s"))))]
      rows depth)))

(defn- seqish-node [m depth render-request value-request path drillable?]
  (let [{:seon.render.value/keys [kind shown elided shape]} m
        [open close] (case kind :vector ["[" "]"] :set ["#{" "}"] ["(" ")"])
        n     (count shown)
        rows  (cond-> (vec (map-indexed
                             (fn [i v]
                               (let [child-drillable? (and drillable? (= :vector kind))
                                     child-path (when child-drillable? (conj path i))]
                                 [:div {:class "flex items-start gap-1.5 text-xs min-w-0"}
                                  [:span {:class "text-text-600 shrink-0 font-mono"} (str i)]
                                  (value-node v (inc depth) render-request value-request
                                              child-path child-drillable? true)]))
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
  [x value-request path drillable?]
  [:span {:class "inline-flex items-center gap-1 text-2xs text-text-500 font-mono"
          :title "deeper than the bounded view — drill the live result/<id> var"}
   [:span {:class "text-text-600"} (value/pruned-token x)]
   (if drillable?
     (drill-control value-request path 0 "▸ inspect")
     [:span {:class "text-text-700"} "▸ deeper"])])

(defn- value-node
  "Recursively render one `render-html-data` `:tree` node to hiccup. Containers
   (map / seqish) become `<details>`; everything else is an inline token."
  [x depth render-request value-request path drillable? wrap?]
  (let [node (cond
               (and (map? x) (contains? x :seon.render.value/pruned))
               (pruned-marker x value-request path drillable?)

               (and (map? x) (contains? x :seon.render.value/kind))
               (seqish-node x depth render-request value-request path drillable?)

               (and (map? x)
                    (not (contains? x :seon.eval/datom))
                    (not (contains? x :seon.eval/opaque))
                    (not (contains? x :seon.render.value/string-len)))
               (map-node x depth render-request value-request path drillable?)

               :else (value-leaf x))]
    (if (and wrap? drillable?)
      [:div {:id (value-identity render-request value-request path)
             :class "min-w-0"}
       node]
      node)))

(defn- schema-statuses [{:seon.render.value/keys [schemas explanation]}]
  (when (seq schemas)
    (into
      [:div {:class "flex flex-wrap items-center gap-1 text-2xs font-mono"}]
      (map (fn [{:seon.schema/keys [key]
                 :seon.render.value/keys [status]}]
             [:span (cond-> {:class (str "rounded border px-1 py-0.5 "
                                         (case status
                                           :valid "border-success/50 text-success"
                                           :invalid "border-error/50 text-error"
                                           "border-base-600 text-text-500"))}
                      (and (= :invalid status) explanation)
                      (assoc :title (tokens/bounded-pr-str explanation 240)))
              (str key)]))
      schemas)))

(defn- data-panel
  "Render one bounded value projection, optionally with trusted drill UI."
  [configuration render-request value-request projection]
  (let [{:seon.render.value/keys [tree summary truncated? path offset
                                  page-size more?]} projection
        interactive? (some? value-request)
        root-id (when interactive?
                  (value-identity render-request value-request path))]
    [:div (cond-> {:class "flex flex-col gap-1"}
            root-id (assoc :id root-id))
     [:div {:class "flex flex-wrap items-center gap-2 text-2xs text-text-500 font-mono mb-0.5"}
      [:span (str summary (when truncated? " · partial"))]
      (schema-statuses projection)
      (when (and interactive? more?
                 (<= (+ offset page-size page-size)
                     (rconfig/value
                       configuration
                       :seon.config.render/value-max-realized-items
                       1024)))
        (drill-control value-request path (+ offset page-size) "next page"))]
     (value-node tree 0 render-request value-request path interactive? false)]))

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

(defn- custom-render-selection [view render-request prepared override]
  ;; A custom fn sees the original value, so it may run only when the bounded
  ;; sampler proved that value complete. Schema validity is already present in
  ;; `prepared`; consulting it avoids a second recursive Malli validation.
  (when-not (:seon.render.value/truncated? prepared)
    (let [property (case view :html :seon.render/html :ai :seon.render/ai)]
      (if override
        {:seon.render/custom-symbol override}
        (when-let [projection (:seon.schema/projection render-request)]
          (when-let [schema-key
                     (some (fn [{:seon.schema/keys [key]
                                 :seon.render.value/keys [status]}]
                             (when (and (= :valid status)
                                        (get-in projection
                                                [:seon.schema.projection/shape-rows
                                                 key property]))
                               key))
                           (:seon.render.value/schemas prepared))]
            {:seon.render/custom-symbol
             (get-in projection [:seon.schema.projection/shape-rows
                                 schema-key property])
             :seon.render/schema-key schema-key}))))))

(defn- invoke-custom-render
  [view configuration render-request x
   {:seon.render/keys [custom-symbol schema-key]}]
  (let [input (cond-> (assoc render-request
                             :seon.config/configuration configuration
                             :seon.render/node x)
                schema-key (assoc :seon.render/schema-key schema-key))
        resolved? (if (err/agent-authored-sym? custom-symbol)
                    (fn? (::invoke-authored! input))
                    (contains? (or (::trusted-renderers input)
                                   core/renderers)
                               custom-symbol))]
    (when-not resolved?
      (throw (ex-info (str "Missing custom renderer " custom-symbol ".")
                      {:seon.render/custom-symbol custom-symbol})))
    (try
      (render-result
       view
       (symbol-call input custom-symbol [input]))
      (catch #?(:clj Throwable :cljs :default) e
        (throw (ex-info (str custom-symbol " threw: " (err/->message e))
                        {:seon.render/custom-symbol custom-symbol}
                        e))))))

(defn- generic-sample-options [catalog-row]
  (if-some [id-attr (:seon.schema.catalog/id-attr catalog-row)]
    {:preferred-keys #{id-attr}}
    {}))

(defn- generic-data-projection
  [configuration render-request x]
  (let [projection (or (:seon.schema/projection render-request)
                       (schema/current-projection)
                       (schema/build-projection (schema/snapshot)))
        catalog-row (when (map? x) (entity-primary-schema x))]
    (value/render-html-data-in
      configuration "" projection x (generic-sample-options catalog-row))))

(defn- generic-ai-render
  "Render generic data as metadata first, one bounded sample, then continuation."
  [configuration x projection]
  (let [catalog-row (when (map? x) (entity-primary-schema x))
        schema-key (or (:seon.schema.catalog/key catalog-row)
                       (some-> projection :seon.render.value/schemas first
                               :seon.schema/key))
        id-attr (:seon.schema.catalog/id-attr catalog-row)
        identity-value (when (and id-attr (contains? x id-attr))
                         (get x id-attr))]
    (str "; schema " (if schema-key (pr-str schema-key) "unregistered")
         (when id-attr
           (str " · identity " (pr-str id-attr) " "
                (if (some? identity-value)
                  (tokens/bounded-pr-str identity-value 40)
                  "absent")))
         " · " (:seon.render.value/summary projection)
         "\n"
         (value/render-ai-data configuration "" projection))))

(defn block
  "THE typed-block renderer for a tagged value in `:html` or `:ai`.

   `(block view configuration render-request x)` — `view` is `:html` (→ hiccup)
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
                             [::render-request :seon.render/section-request]
                             [::x :any]] :any]}
  [view configuration render-request x]
  (try
    (let [built-in? (or (code/block? x)
                        (message-block? x)
                        (source-block? x)
                        (value-request? x)
                        (data-projection? x)
                        (error-value? x)
                        (canvas/valid-hiccup? x))
          projection (:seon.schema/projection render-request)
          custom-candidate? (and (not built-in?)
                                 (map? x)
                                 (or projection
                                     (contains? x
                                                (case view
                                                  :html :seon.render/html
                                                  :ai :seon.render/ai))))
          property (case view :html :seon.render/html :ai :seon.render/ai)
          catalog-row (when (map? x) (entity-primary-schema x))
          sample (when custom-candidate?
                   (value/sample configuration x
                                 (generic-sample-options catalog-row)))
          complete? (and sample (value/complete-sample? sample))
          override (when (and complete? (contains? x property))
                     (db/decode-edn-value property (get x property)))
          ;; An explicit override preserves its matcher short-circuit after
          ;; completeness is proven. Schema projection is needed only for an
          ;; incomplete honest fallback or schema-property selection.
          prepared (when (and sample (or (not complete?) (not override)))
                     (let [projection (or projection
                                          (schema/current-projection)
                                          (schema/build-projection
                                            (schema/snapshot)))]
                       (value/render-html-sample-data-in
                         "" projection x sample)))
          selected-custom (when prepared
                            (custom-render-selection
                              view render-request prepared override))
          custom (or selected-custom
                     (when (and complete? override)
                       {:seon.render/custom-symbol override}))]
      (case view
        :html
        (cond
          (code/block? x)    (md/md->hiccup (code-fenced x))
          (message-block? x) (md/md->hiccup (:seon.render/markdown x))
          (source-block? x)  (cljhl/clj->hiccup (:seon.render/source x))
          (value-request? x)
          (if (schema/valid-candidate-value? :seon.render/value-request x)
            (data-panel configuration render-request x (:seon.render/value-projection x))
            (throw (ex-info "Malformed value render request."
                            {:seon.render/value-request x})))
          (data-projection? x) (data-panel configuration render-request nil x)
          (error-value? x)   (canvas/error-card x)
          (canvas/valid-hiccup? x) x
          custom             (invoke-custom-render view configuration render-request x custom)
          prepared           (data-panel configuration render-request nil prepared)
          :else              (data-panel
                               configuration render-request nil
                               (generic-data-projection
                                 configuration render-request x)))

        :ai
        (cond
          (code/block? x)    (code-fenced x)
          (message-block? x) (:seon.render/markdown x)
          (source-block? x)  (:seon.render/source x)
          (value-request? x)
          (if (schema/valid-candidate-value? :seon.render/value-request x)
            (let [projection (:seon.render/value-projection x)]
              (str (:seon.render.value/summary projection)
                   (when (:seon.render.value/truncated? projection) " (partial)")))
            (throw (ex-info "Malformed value render request."
                            {:seon.render/value-request x})))
          (data-projection? x) (str (:seon.render.value/summary x)
                                     (when (:seon.render.value/truncated? x) " (partial)"))
          (error-value? x)   (:seon.error/message x)
          (canvas/valid-hiccup? x) (hiccup-text x)
          custom             (invoke-custom-render view configuration render-request x custom)
          prepared           (generic-ai-render configuration x prepared)
          :else              (generic-ai-render
                               configuration x
                               (generic-data-projection
                                 configuration render-request x)))))
    (catch #?(:clj Throwable :cljs :default) e
      ;; `block` dispatches to CORE renderers (md->hiccup, clj->hiccup, the
      ;; value panels) — a throw is our machinery (:core). Record BEFORE
      ;; strict-fail! (re-throws in strict mode); recorded? skips a funnel dup.
      (when-not (err/recorded? e)
        (err/record! {:seon.error/raw e
                      :seon.error/fault
                      (if-let [sym (:seon.render/custom-symbol (ex-data e))]
                        (err/fault-for sym)
                        :core)}))
      ;; STRICT dial: dev/test/benchmark → re-throw LOUD; prod → graceful guard.
      (strict-fail! configuration :block e)
      (let [msg (str "block render failed: " (err/->message e))]
        (case view
          :html (canvas/error-card {:seon.error/message msg :seon.error/where :block})
          :ai   msg)))))

(defn render-entity-ai
  "Render `entity` to text via its resolved `:seon.render/ai` symbol.
   Per-entity override wins; else schema property for the entity's
   primary schema. Returns nil if no symbol resolves OR the fn returns
   nil. Mirror of `render-entity-html` for the AI path.

   The schema's ai symbol IS a converter (`seon.render.handlers.*/render-ai`)
   returning a BARE String, called with the entity under
   `:seon.render/node` (`:seon.render/entity` tolerated)."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :string]]}
  [{:seon.render/keys [entity node] :as input}]
  (let [entity (or node entity)]
    (when-let [sym (entity-render entity :ai)]
      (try
        (let [r (symbol-call input sym
                             [(assoc input :seon.render/node entity)])]
          ;; Converters return a BARE String; a per-entity renderer may
          ;; return the {:seon.render/ai s …} envelope — unwrapped via the
          ;; ONE shared path (the ai twin of render-entity-html).
          (render-result :seon.render/ai r))
        ;; A throwing AI renderer is LEGIBLE, never nil-vanished — the
        ;; agent reading its context sees its own renderer is broken
        ;; (mirror of the html banner above / the canvas's error render).
        (catch #?(:clj Throwable :cljs :default) e
          ;; Classify by the render symbol (fault-for): agent-authored
          ;; converter → :agent, core converter → :core. Record BEFORE
          ;; strict-fail! (re-throws in strict mode); recorded? skips a dup.
          (when-not (err/recorded? e)
            (err/record! {:seon.error/raw e :seon.error/fault (err/fault-for sym)}))
          ;; STRICT dial: dev/test/benchmark → re-throw LOUD; prod → legible line.
          (strict-fail! (:seon.config/configuration input) sym e)
          (str "[render error — " sym " threw: "
               (or (ex-message e) (str e)) "]"))))))

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
  (or (:seon.agent.ctx/name node)
      (when-let [id-attr (some-> (and (map? node)
                                      (entity-primary-schema node))
                                 :seon.schema.catalog/id-attr)]
        (get node id-attr))
      (:db/id node)))

(declare render)

(defn- generic-default-renderer
  "The GENERIC default — renders ANY structure when there is no slot and no
   schema match. Both views use the same prepared `render.value` projection;
   AI orders schema/identity/type before the sample and continuation."
  [view]
  (case view
    :seon.render/ai
    (fn [{:seon.render/keys [node] :as input}]
      (let [configuration (:seon.config/configuration input)
            node (apply dissoc node render-control-attrs)
            projection (generic-data-projection configuration input node)]
        (generic-ai-render configuration node projection)))
    :seon.render/html
    (fn [{:seon.render/keys [node] :as input}]
      (let [configuration (:seon.config/configuration input)
            node (apply dissoc node render-control-attrs)
            projection (generic-data-projection configuration input node)]
        (data-panel configuration input nil projection)))))

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
     3. fn-symbol → the compiled fn in the isolated execution child;
     4. absent → the schema-default (the node's primary schema's converter);
     5. none → the GENERIC default (any data → Clojure / a dump)."
  [view node input]
  (let [slot-val (get node view)]
    (cond
      (string? slot-val) (fn [_] slot-val)
      (vector? slot-val) (fn [_] slot-val)
      (symbol? slot-val)
      (let [resolved? (if (err/agent-authored-sym? slot-val)
                        (fn? (::invoke-authored! input))
                        (contains? (or (::trusted-renderers input)
                                       core/renderers)
                                   slot-val))]
        (if resolved?
          (fn [input] (render-result view
                                     (symbol-call input slot-val [input])))
          (fn [_] (missing-render view (renderable-id node) slot-val))))
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
    (let [f  (resolve-render view node ctx)
          in (assoc ctx :seon.render/node   node
                        :seon.render/render #(render view ctx %))]
      (try
        (unwrap-response view (f in))           ;; bare OR html-response envelope
        (catch #?(:clj Throwable :cljs :default) e
          ;; Classify by the node's slot value: an agent-authored render
          ;; symbol → :agent, anything else (core section,
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
          (strict-fail! (:seon.config/configuration ctx)
                        (renderable-id node) e)
          (if (= view :seon.render/ai)
            (str ";; ⚠ [" (renderable-id node) "] render failed: " (ex-message e))
            (canvas/error-card
              {:seon.error/message (str (renderable-id node) " — " (ex-message e))})))))))
