(ns seon.render.live-tile.internal
  "Framework-internal PLUMBING for the live tile — validation,
   serializer-faithful structure checking, wired-value resolution, and
   the legible error-response card. NOT agent-facing: `*.internal` is
   the structural filter that keeps this out of the namespaces context
   section. Other FRAMEWORK namespaces (`seon.render`, `seon.ctx.live-tile`)
   and tests require it directly.

   The agent-teaching surface — the tile-authoring tutorial, the `welcome`
   worked example, and `wiring-source` — lives in the slim, whitelisted
   `seon.render.live-tile`. This ns holds the mechanism that ns teaches
   ABOUT.

   KEYWORD NOTE: `::foo` here expands to
   `:seon.render.live-tile.internal/foo`. The shared vocabulary keywords
   (`:seon.render.live-tile/content`, `:seon.render.live-tile/hiccup`)
   are OWNED by the public ns and are referenced here FULLY-QUALIFIED;
   only NEW internal-only schemas use `::`.

   PLATFORM LAW (2026-06-11, sci-not-available incident): registered
   schema forms must be PURE DATA — no `[:fn]`, no function objects.
   Deep-structure validation that needs a predicate belongs at a FN
   boundary (instrumentation on a compiled fn never round-trips as a
   form) — see [[valid-hiccup?]]. `:seon.render.live-tile/hiccup` is the
   PRAGMATIC STRUCTURAL BOUND (a vector with a keyword head); the deep
   walk runs here at the render boundary, where [[valid-hiccup?]] stays a
   PLAIN fn."
  (:require
    [seon.db :as db]
    ;; require (not refer) the public ns ONLY to guarantee its
    ;; vocabulary schemas (`:seon.render.live-tile/content` / `/source`)
    ;; are registered before THIS ns's schemas reference them. No var
    ;; from it is used at load time (welcome is referenced as a literal
    ;; symbol), so this is a load-ORDER edge, NOT a cycle: the public ns
    ;; does not require this one.
    [seon.render.live-tile]
    [seon.schema :as schema]
    [seon.ui.html :as html]))

;; ============================================================
;; The hiccup predicate — a PLAIN render-boundary fn (NOT a registered
;; schema form: registered forms must be pure data; a recursive seqex
;; additionally trips :malli.core/potentially-recursive-seqex inside an
;; instrumented fn schema). `:seon.render.live-tile/hiccup` carries the
;; shallow pure-data shape; this fn is the deep validator.
;; ============================================================

(declare valid-hiccup?)

(defn valid-hiccup-elem?
  "True if `x` is a valid hiccup ELEMENT — string, int, nil, or a
   nested vector that starts with a keyword."
  {:malli/schema [:=> [:catn [::elem :any]] :boolean]}
  [x]
  (or (string? x)
      (int? x)
      (nil? x)
      (valid-hiccup? x)))

(defn valid-hiccup?
  "True if `x` is a valid hiccup VECTOR: starts with a keyword tag,
   optional second-position attrs map, zero or more children where
   each child is a valid hiccup element. Non-recursive Malli idiom —
   handles arbitrary-depth nesting without
   :malli.core/potentially-recursive-seqex.

   A PLAIN fn for render-boundary checks and fn instrumentation —
   deliberately NOT inside any `register!` form (registered schema
   forms must be pure data; see the platform-law note above).
   (`:any` input — this IS the validator for arbitrary values.)"
  {:malli/schema [:=> [:catn [::x :any]] :boolean]}
  [x]
  (and (vector? x)
       (keyword? (first x))
       (let [rest-x (rest x)
             [_maybe-attrs children]
             (if (map? (first rest-x))
               [(first rest-x) (rest rest-x)]
               [nil rest-x])]
         (every? valid-hiccup-elem? children))))

;; ============================================================
;; Serialization-boundary structural check: [[valid-hiccup?]] is the
;; strict AUTHORING shape, deliberately narrower than what the
;; serializer accepts (seqs, numbers, raw, stringifiable values render
;; fine via seon.ui.html/->string) — so it CANNOT gate the render path
;; without falsely erroring legitimate tiles. The fns below mirror the
;; SERIALIZER's acceptance exactly and return the FIRST fatal defect
;; with its path, so a broken tile degrades to [[error-response]] with a
;; legible message instead of throwing later at page serialization and
;; 500ing /agent/<id> + the grid.
;; ============================================================

(schema/register! ::structure-path [:vector :int])
(schema/register! ::structure-message :string)
(schema/register! ::structure-error
  [:map
   [::structure-path    ::structure-path]
   [::structure-message ::structure-message]])

(defn- pr-str-bounded
  "pr-str `x` clipped to ~120 chars — error messages quote the
   offending node without dumping a whole hiccup tree."
  [x]
  (let [s (pr-str x)]
    (if (> (count s) 120) (str (subs s 0 120) "…") s)))

(defn- structure-error-at
  "Walk `x` the way seon.ui.html renders it; return the first fatal
   structural defect as {::structure-path ::structure-message}, or
   nil. Fatal = exactly what makes ->string THROW: a vector element
   whose tag slot isn't a keyword/symbol/string. Everything the
   serializer tolerates (nil/false, raw, seqs, stringifiable scalars)
   passes.

   ATTRS-POSITION RULE (#42): in a hiccup element [tag attrs? & children]
   the attrs map MUST be the SECOND element (immediately after the tag),
   before any children. The serializer reads attrs ONLY in that position;
   a map placed later among the children silently degrades to garbage
   content. This walk reports that one unambiguous misplaced-attrs case
   — the 2nd slot is a non-map child AND a (non-raw) map sits at child
   index ≥ 1 — as a specific ::structure-message. It deliberately does
   NOT flag the genuinely-ambiguous shapes (a single map that COULD be
   intended as the attrs: [:div {…}] is correct; [:h3 \"x\"] has no map)."
  [x path]
  (cond
    (or (nil? x) (false? x)) nil
    (html/raw? x)            nil

    (vector? x)
    (let [tag (nth x 0 nil)]
      (cond
        (vector? tag)
        {::structure-path path
         ::structure-message
         (str "vector-of-vectors child — the element at this path is a "
              "vector whose first slot is itself a vector ("
              (pr-str-bounded tag) "). Splice the children into the "
              "parent vector, or emit them as a seq — "
              "(list [:div …] [:div …]) — never a nested vector of "
              "elements.")}

        (not (or (keyword? tag) (symbol? tag) (string? tag)))
        {::structure-path path
         ::structure-message
         (str "invalid tag — must be a keyword, symbol, or string; got "
              (pr-str-bounded tag))}

        :else
        (let [body      (rest x)
              attrs?    (and (map? (first body))
                             (not (html/raw? (first body))))
              children  (if attrs? (rest body) body)
              offset    (if attrs? 2 1)
              ;; MISPLACED-ATTRS (#42): the unambiguous case — the 2nd
              ;; slot is a NON-map child (so it's read as content, not
              ;; attrs) yet an attrs-looking map sits LATER among the
              ;; children. The serializer reads attrs only in 2nd
              ;; position, so this map silently becomes garbage content.
              ;; CONSERVATIVE on purpose: fires ONLY when the 2nd slot is
              ;; already a non-map child AND a (non-raw) map appears at
              ;; child index ≥ 1 — never on a valid tile ([:h3 "x"] has no
              ;; map; [:div {:k 1} "x"] has the map in correct 2nd
              ;; position so attrs? is true and this branch is skipped).
              misplaced-i (when (and (seq body) (not attrs?))
                            (first
                              (keep-indexed
                                (fn [i c]
                                  (when (and (pos? i)
                                             (map? c)
                                             (not (html/raw? c)))
                                    i))
                                children)))]
          (if misplaced-i
            {::structure-path (conj path (+ offset misplaced-i))
             ::structure-message
             (str "misplaced attrs map — the attrs map must be the SECOND "
                  "element (immediately after the tag), before any children; "
                  "got a map at child index " misplaced-i " ("
                  (pr-str-bounded (nth children misplaced-i))
                  "). Move it to the second slot, e.g. [" (pr-str (nth x 0))
                  " {…} child …], or drop it if it was meant as content.")}
            (some identity
                  (map-indexed
                    (fn [i c] (structure-error-at c (conj path (+ offset i))))
                    children))))))

    (seq? x)
    (some identity
          (map-indexed (fn [i c] (structure-error-at c (conj path i)))
                       x))

    :else nil))

(defn hiccup-structure-error
  "Serializer-faithful structural check for a tile's hiccup. Returns
   nil when `seon.ui.html/->string` would serialize `x` cleanly, or
   `{::structure-path […] ::structure-message \"…\"}` locating the
   FIRST fatal defect (e.g. a vector-of-vectors child). A PLAIN fn at
   the render boundary, like [[valid-hiccup?]] — never inside a
   registered form. (`:any` input — this IS the validator for
   arbitrary values.)"
  {:malli/schema [:=> [:catn [::x :any]] [:maybe ::structure-error]]}
  [x]
  (structure-error-at x []))

;; ============================================================
;; Resolution — which value is wired.
;; ============================================================

(schema/register! ::wired-request
  [:map [:seon.render/entity :map]])

(schema/register! ::wired-response
  [:map
   [:seon.render.live-tile/source :seon.render.live-tile/source]
   [:seon.render.live-tile/value  :seon.render.live-tile/content]])

(defn wired-content
  "Resolve WHICH value is the agent's live tile, with provenance.

   Resolution on the pulled agent `:seon.render/entity`:
   `:seon.render.live-tile/content` (THE tile key) when present, else
   the core welcome (`'seon.render.live-tile/welcome`). Neither the
   per-entity `:seon.render/html` nor the `:seon.agent` KIND default is
   consulted — that key means ONLY the generic entity-card render slot
   (one key, one meaning).

   Values arrive pr-str-encoded from the mixed-:or bridge; the attr
   read decodes via `seon.db/decode-edn-value`."
  {:malli/schema [:=> [:cat ::wired-request] ::wired-response]}
  [{:seon.render/keys [entity]}]
  (let [content (some->> (:seon.render.live-tile/content entity)
                         (db/decode-edn-value :seon.render.live-tile/content))]
    (if (some? content)
      {:seon.render.live-tile/source :seon.render.live-tile/content
       :seon.render.live-tile/value  content}
      ;; welcome-sym lives in the public ns; reference it as the LITERAL
      ;; symbol (pure data) to keep this internal ns free of a require
      ;; on its parent (would form a load cycle).
      {:seon.render.live-tile/source :seon.render.live-tile/welcome
       :seon.render.live-tile/value  'seon.render.live-tile/welcome})))

(defn wired-label
  "The awareness-section header identity for a [[wired-content]]
   result — the wired fn's fully-qualified name (its source is one
   `:seon.fn`/catalog lookup away) or \"literal hiccup on your
   entity\", with provenance (legacy slot / core default), so
   the agent reading the section always sees HOW to change the
   display."
  {:malli/schema [:=> [:cat ::wired-response] :string]}
  [{:seon.render.live-tile/keys [source value]}]
  (case source
    :seon.render.live-tile/content
    (if (symbol? value)
      (str value " (a fn on your entity)")
      "literal hiccup on your entity")

    :seon.render.live-tile/welcome
    (str value " (the core default — wire your own)")))

;; ============================================================
;; Errors are legible — a broken tile never silently vanishes.
;; ============================================================

(schema/register! ::error-request
  [:map
   [:seon.db/error :seon.db/error]
   [:seon.render.live-tile/content {:optional true}
    :seon.render.live-tile/content]])

(defn error-response
  "Build the html-response for a tile fn that THREW. THE HUMAN sees a calm,
   nicely-formatted 'updating this panel' card — never a scary error, never a
   blank (vanish is indistinguishable from unwired, banned). THE AGENT is told
   the truth: the `:seon.render/ai` twin carries the failure (awareness
   section) and the full `:seon.error/*` envelope rides on `:seon.render/error`.
   Breakage is a DERIVED surface only (#43 / D2) — the
   `:seon.ctx.live-tile/live-tile-section` re-derives this twin into the
   agent's context EVERY turn (a pure fn of state, no stored flag,
   self-healing on the next clean render). There is NO active push: a forged
   self-message would wake the agent and defeat the loop's halt. So the human
   stays calm while the agent learns of the breakage by reading its own
   context."
  {:malli/schema [:=> [:cat ::error-request] :seon.render/html-response]}
  [{error :seon.db/error wired :seon.render.live-tile/content}]
  (let [msg       (:seon.error/message error)
        wired-str (if (symbol? wired)
                    (str wired)
                    "literal hiccup on your entity")]
    {:seon.render/hiccup
     [:div {:class "seon-tile"}
      [:div {:class "seon-tile-compact flex flex-col gap-1 p-3"}
       [:div {:class "text-sm text-text-200"} "Updating this panel…"]]
      [:div {:class "seon-tile-expanded flex flex-col gap-2 p-4"}
       [:div {:class "text-base text-text-100"} "Updating this panel…"]
       [:div {:class "text-xs text-text-400 italic"}
        "I'm refining what I show here."]]]
     :seon.render/ai
     (str "YOUR LIVE TILE IS BROKEN — the wired renderer (" wired-str
          ") threw: " msg ". Your human sees a calm 'updating this panel' "
          "placeholder, not your content. Fix the fn, or transact a working "
          "value onto :seon.render.live-tile/content.")
     :seon.render/error error}))
