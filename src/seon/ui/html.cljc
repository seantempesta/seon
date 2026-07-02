(ns seon.ui.html
  "Hiccup → HTML-string. Pure data transform; portable across CLJ + CLJS.

   Per spec-05 §10.2 A-3: this is the pod-side server renderer. The
   pod writes hiccup data structures via `:seon.render/html` slots and
   needs to serialize them to HTML strings for the SSE broadcast
   pipeline (`event: datastar-patch-elements\\n data: <morph string>`).

   ## Why we don't use a library

   The canonical Clojure hiccup libraries (`weavejester/hiccup`,
   `onionpancakes/chassis`) are JVM-only — they lean on Java types
   (`StringBuilder`, `Appendable`, `clojure.lang.IPersistentVector`)
   that have no CLJS analog. The historical CLJS port
   `teropa/hiccups` is unmaintained (2017) and doesn't escape text
   content by default — an XSS hole. Writing it fresh is ~100 lines
   of considered code with no licensing or staleness baggage. If a
   JVM hot path ever needs this surface, the file is already `.cljc`;
   we'd just stop using it there in favor of chassis's
   StringBuilder-based emitter for perf.

   ## What this implements

   - Tag shorthand: `:div.foo#bar` → `<div id=\"bar\" class=\"foo\">`
   - Attribute map detection (second element if it's a map)
   - **Text content escaped by default** — `&`, `<`, `>`, `\"`, `'`.
     This is the XSS-safe default. To embed pre-rendered HTML / inline
     `<script>` / `<style>` bodies, wrap with `(raw \"...\")`.
   - HTML5 void elements emitted self-closing without `</tag>`:
     area base br col embed hr img input link meta param source track wbr.
   - `nil`, `false`, empty seqs elided from children.
   - Seq children flattened (so `(for [x xs] [:li x])` works inline).
   - Attribute values: `true` → bare attribute (`<input checked>`),
     `false` / `nil` → attribute omitted, anything else → escaped value.
   - `:class` accepts a string OR a collection (joined with spaces).
   - `:style` accepts a string OR a map (`{:color \"red\"}` →
     `color: red;`). Map keys are normalized to kebab-case CSS
     property names (`:fontSize` → `font-size`, `:WebkitMask` →
     `-webkit-mask`); custom properties (`--*`) pass through.

   ## What this does NOT implement

   - No `<!DOCTYPE>` emission. Callers prepend `\"<!DOCTYPE html>\"`
     manually before the rendered root element (spec-05 §15.6's
     `root-html` does this).
   - No XML mode / self-closing-everywhere. HTML5 only.
   - No compile-time hiccup optimization (chassis's claim to fame).
     The pod renders ~N agents per tx; that's not perf-sensitive at
     V0/V0.5 scale.

   ## Public surface

     (->string [:div.foo \"bar\"])
     => \"<div class=\\\"foo\\\">bar</div>\"

     (->string [:script {:type \"module\"}
                (raw \"console.log('hi');\")])
     => \"<script type=\\\"module\\\">console.log('hi');</script>\""
  (:require [clojure.string :as str]))

;; ============================================================
;; Raw-string escape hatch — for content the caller has already
;; serialized (inline JS/CSS bodies, third-party HTML fragments).
;;
;; A defrecord gives us a value that `raw?` can check via `instance?`
;; and that the renderer short-circuits past escape-html for. Using a
;; record (not just a tagged map) makes the type detection sharp:
;; pre-escaped strings can NEVER be conflated with attribute maps or
;; arbitrary content.
;; ============================================================

(defrecord ^:no-doc Raw [s])

(defn raw
  "Wrap a string so the renderer emits it without escaping.

   Use for
   pre-serialized HTML, inline `<script>` bodies, inline `<style>`,
   etc. ANY caller-supplied content wrapped in `raw` becomes an XSS
   surface — the wrapping signals 'I have escaped this myself'."
  {:malli/schema [:=> [:cat :any] :any]}
  [s]
  (->Raw (str s)))

(defn raw?
  "True iff `x` was produced by `raw`."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x]
  (instance? Raw x))

;; ============================================================
;; HTML5 void elements — render self-closing without `</tag>`.
;; Source: https://html.spec.whatwg.org/multipage/syntax.html#void-elements
;; ============================================================

(def ^:private void-elements
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link"
    "meta" "param" "source" "track" "wbr"})

;; ============================================================
;; Escaping. Five characters cover the OWASP HTML-context list:
;; ============================================================

(def ^:private text-escapes
  {\& "&amp;"
   \< "&lt;"
   \> "&gt;"
   \" "&quot;"
   \' "&#39;"})

(defn ^:no-doc escape-html
  "Escape the five HTML-special characters in `s`. Returns a string."
  {:malli/schema [:=> [:cat :any] :string]}
  [s]
  (str/escape (str s) text-escapes))

;; ============================================================
;; Tag-shorthand parsing. `:div.foo.bar#baz` → tag `div`, id `baz`,
;; classes `[\"foo\" \"bar\"]`. We do this with a single regex pass
;; rather than character-by-character so the implementation reads
;; left-to-right as the spec.
;;
;; Regex notes:
;;   ^([^.#]+)      tag name — one+ chars that aren't `.` or `#`
;;   (?:#([^.#]+))? optional id — `#` then one+ non-dot non-hash
;;   (?:\.([^#]+))? optional class group — `.` then one+ non-hash
;;                  (multiple classes are split on `.` below)
;;
;; A bare `:#foo` (no tag) is invalid hiccup; we surface that as
;; an ex-info with the original tag value for caller diagnostics.
;; ============================================================

(def ^:private re-tag
  #"^([^.#]+)(?:#([^.#]+))?(?:\.(.+))?$")

(defn- name-of
  [x]
  (cond
    (keyword? x) (name x)
    (symbol? x)  (name x)
    (string? x)  x
    :else        (throw (ex-info "Invalid tag — must be keyword, symbol, or string."
                                 {:tag x :type (type x)}))))

(defn- parse-tag
  "Returns `{:tag tag :id id-or-nil :classes [classes...]}`."
  [tag]
  (let [tag-str (name-of tag)
        [_ tag-name id class-str] (re-matches re-tag tag-str)]
    (when (nil? tag-name)
      (throw (ex-info (str "Unparseable tag: " (pr-str tag))
                      {:tag tag})))
    {:tag     tag-name
     :id      id
     :classes (if class-str (str/split class-str #"\.") [])}))

;; ============================================================
;; Attribute rendering.
;;
;; Three special cases:
;;   :class — string OR collection (joined with spaces). Merged with
;;            classes parsed from the tag shorthand.
;;   :style — string OR map (rendered as `prop: val; prop: val;`,
;;            keys normalized camelCase → kebab-case).
;;   true   — emit bare attribute (`<input checked>`).
;;   false / nil — omit attribute entirely.
;;
;; Attribute names are stringified via `name-of` (keywords + symbols
;; → name; strings pass through). We don't restrict allowed attr
;; names — Datastar's `data-on-click__post`, `:hx-get`, custom
;; attributes etc. all flow through unchanged.
;;
;; Sort attribute output by key string so the same attrs in different
;; map literal order produce identical HTML. Makes tests stable AND
;; makes SSE diff-and-skip (spec §15.4) cache-stable.
;; ============================================================

(defn- style-key->css
  "Normalize a style-map key to its CSS property name (React
   hyphenateStyleName semantics). camelCase → kebab-case
   (`:fontSize` → `font-size`); a leading capital marks a vendor
   prefix and gains a leading dash (`:WebkitMask` → `-webkit-mask`);
   CSS custom properties (keys starting `--`) pass through untouched;
   already-kebab keys are unchanged. LLMs carry React priors and
   write camelCase style keys forever — without this they render
   verbatim into the style attribute and are silently dead in the
   browser."
  [k]
  (let [s (name-of k)]
    (if (str/starts-with? s "--")
      s
      (let [kebab (-> s
                      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                      str/lower-case)]
        (if (re-find #"^[A-Z]" s)
          (str "-" kebab)
          kebab)))))

(defn- render-style
  "Render a `:style` value. Strings pass through; maps become
   `prop: val; prop: val` (no trailing semicolon) with keys
   normalized via `style-key->css` and sorted by the normalized
   name for deterministic output."
  [v]
  (cond
    (string? v) v
    (map? v)    (->> v
                     (map (fn [[k v]] [(style-key->css k) v]))
                     (sort-by first)
                     (map (fn [[k v]] (str k ": " v)))
                     (str/join "; "))
    :else       (str v)))

(defn- render-class
  "Render a `:class` value (and the parsed tag-shorthand classes).
   Strings split on whitespace; collections flatten + stringify;
   nil-elements drop. Result is a space-joined string OR nil if empty."
  [tag-classes attr-class]
  (let [pieces (concat tag-classes
                       (cond
                         (nil? attr-class)         nil
                         (string? attr-class)      [attr-class]
                         (sequential? attr-class)  (map name-of (remove nil? attr-class))
                         :else                     [(name-of attr-class)]))
        joined (->> pieces
                    (remove str/blank?)
                    (str/join " "))]
    (when-not (str/blank? joined) joined)))

(defn- render-attribute
  "Render one [k v] pair into a string fragment like ` k=\"v\"`, or
   `\"\"` for omitted attrs, or ` k` for bare-true attrs. Caller is
   responsible for `:class` / `:style` preprocessing."
  [[k v]]
  (let [k-str (name-of k)]
    (cond
      (or (nil? v) (false? v))
      ""

      (true? v)
      (str " " k-str)

      :else
      (str " " k-str "=\"" (escape-html v) "\""))))

(defn- render-attrs
  "Render the attribute map for a tag, merging the parsed tag-shorthand
   id/classes with the user-supplied attrs. Returns the leading-space
   attribute string (possibly empty)."
  [{:keys [id classes]} attrs]
  (let [;; Merge classes — shorthand classes come first, then attr classes.
        merged-class (render-class classes (:class attrs))
        ;; :id from shorthand wins ONLY when attrs map doesn't carry one.
        merged-id    (or (:id attrs) id)
        ;; Build the effective attrs map, dropping the slots we just
        ;; specially-handled so they don't get re-emitted.
        final        (-> attrs
                         (dissoc :class :id :style)
                         (cond->
                           merged-id    (assoc :id merged-id)
                           merged-class (assoc :class merged-class)
                           (:style attrs) (assoc :style (render-style (:style attrs)))))]
    ;; Stable order: sort by key-name so identical attrs in different
    ;; insertion order produce byte-identical HTML.
    (->> final
         (sort-by (comp name-of key))
         (map render-attribute)
         (apply str))))

;; ============================================================
;; Element + tree rendering.
;;
;; render-content walks children: nil/false → elide; strings →
;; escape; raw → emit unescaped; vectors → render-element; seqs →
;; flatten + recurse; everything else → str + escape.
;;
;; render-element handles the four cases:
;;   - void element: <tag attrs>     (no closing tag, no content)
;;   - empty content + non-void: <tag attrs></tag>  (always close —
;;     keeps Datastar morph behavior consistent; `<div></div>` is
;;     valid HTML5 and ensures element-id targeting stays stable)
;;   - normal: <tag attrs>...children...</tag>
;;
;; Children may be:
;;   ([:p \"hi\"] [:p \"bye\"])        ; multiple top-level
;;   ([:ul (for [x xs] [:li x])])     ; seq inside vector
;;   ([:div nil [:p \"hi\"]])         ; nil interspersed (elided)
;; ============================================================

(declare render-element)

(defn ^:no-doc render-content
  "Render a single child node OR seq-of-children.

   Returns the
   HTML-string fragment."
  {:malli/schema [:=> [:cat :any] :string]}
  [x]
  (cond
    (or (nil? x) (false? x))  ""
    (raw? x)                  (:s x)
    (vector? x)               (render-element x)
    (seq? x)                  (apply str (map render-content x))
    (string? x)               (escape-html x)
    ;; A bare map is NEVER a valid hiccup child (an attrs map only belongs in
    ;; element position 2). Elide it — never pr-str it as page text — so a
    ;; malformed tile (e.g. an agent that puts `:seon.render/ai` INSIDE its
    ;; `:seon.render/hiccup` instead of as a sibling key) can't leak raw EDN into
    ;; the human view. (Fail-loud rejection of such tiles is `valid-hiccup?`'s job
    ;; at the render boundary — flagged to R; this is the serializer backstop.)
    (map? x)                  ""
    :else                     (escape-html (str x))))

(defn- attrs-map?
  "Attrs detection: true if `x` is a map but NOT a Raw record. Raw is
   a defrecord (record? + map? both return true), so the naive
   `(map? (first body))` would mis-grab `(h/raw \"...\")` as the
   attrs map."
  [x]
  (and (map? x) (not (raw? x))))

(defn ^:no-doc render-element
  "Render a single hiccup vector to an HTML string."
  {:malli/schema [:=> [:cat :any] :string]}
  [[tag & body]]
  (let [parsed             (parse-tag tag)
        [attrs children]   (if (attrs-map? (first body))
                             [(first body) (next body)]
                             [{} body])
        attrs-str          (render-attrs parsed attrs)
        tag-name           (:tag parsed)]
    (if (contains? void-elements tag-name)
      ;; Void element — no closing tag, no content. Children silently
      ;; ignored (hiccup-author error; cheaper to elide than throw).
      (str "<" tag-name attrs-str ">")
      ;; Normal element — always emit a closing tag, even when empty.
      (str "<" tag-name attrs-str ">"
           (apply str (map render-content children))
           "</" tag-name ">"))))

(defn ->string
  "Render a hiccup value to an HTML string.

   `x` may be a hiccup vector (`[:div ...]`), a seq of hiccup vectors
   (rendered as concatenated fragments), a raw-string (rendered
   unescaped), `nil`/`false` (rendered as the empty string), or any
   other value (rendered as its escaped string form).

   Pure of state; safe to call from anywhere. Output is deterministic
   — same hiccup input always produces the same byte-string."
  {:malli/schema [:=> [:cat :any] :string]}
  [x]
  (render-content x))
