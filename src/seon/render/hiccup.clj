(ns seon.render.hiccup
  "Hiccup → an HTML string. The one serializer, and the one grammar.

  CONTRACT LAYER (drafted 2026-07-27 for ORCHESTRATOR SEAL — N4 package
  1). `hiccup?`, the generator and `raw` are REAL because the schema
  gate resolves and runs them at load; every transform below throws
  `awaits implementation`.

  QUARRIED, NOT COPIED. `src-old/seon/ui/html.cljc` (353 lines) is a
  well-considered file and most of its decisions survive verbatim as
  requirements below: escape by default, `raw` as a record so a
  pre-escaped string can never be mistaken for an attribute map, the
  HTML5 void-element set, tag shorthand, `:class` as string-or-
  collection, `:style` as string-or-map with React-camelCase keys
  normalized (LLMs write `:fontSize` forever), and attributes emitted in
  sorted key order so identical attributes in different literal order
  produce identical bytes.

  THREE THINGS CHANGE, and each is a reason the file is rewritten rather
  than moved:

  1. THE CLJS CONSTRAINT IS GONE. That file's own docstring explains it
     avoided `StringBuilder` only because CLJS has no analog, and says
     outright that a JVM hot path would want a StringBuilder emitter.
     The CLJS build is off (owner, 2026-07-27) and the owner's
     performance bar is a 16 ms frame budget under churn, so this one is
     JVM and appends into ONE `StringBuilder` per call. `str`-tree
     concatenation allocates an intermediate string per element and per
     attribute; at 60 fps under churn that is the budget spent on
     garbage. `bench/seon/render_bench.clj` measures it; nothing here is
     asserted.

  2. THE GRAMMAR IS STRICT, AND THE SERIALIZER IS STILL TOTAL. The old
     one accepted anything and silently elided a map child — its own
     comment calls that a backstop for a failure `valid-hiccup?` should
     have caught, and flags it. Silently swallowing an agent's mistake
     is the failure class the standing rulings ban: the map vanished,
     the page looked fine, and nobody learned. Here `hiccup?` IS the
     grammar, the block layer admits against it and turns a refusal into
     an error card naming the block, and `->string` is total over
     admitted hiccup. Fail loud, do not fall down: the bad block
     screams, the page renders.

  3. NOTHING THROWS. The old `parse-tag` threw `ex-info` on an
     unparseable tag, on a path that reaches agent-authored values. An
     unparseable tag is simply not hiccup, so it fails admission before
     serialization, and `->string` has no throw left to make.

  Lazy sequences are safe here BY CONSTRUCTION rather than by care:
  every agent-authored value crosses `seon.sci.admit/admit`, which
  deeply realizes inside the armed boundary, so no unrealized seq can
  reach this namespace from an agent. System-authored hiccup may still
  use `for` freely — that seq realizes on this thread, which is exactly
  where a runaway would be visible.

  Crash walk: pure, allocates one buffer, holds nothing. A kill during a
  render loses a string nobody had sent."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; The escape hatch — a record, so pre-escaped content can never be
;;; mistaken for an attribute map (both are `map?`; only one is `raw?`).
;;; ---------------------------------------------------------------------------

(defrecord ^:no-doc Raw [string])

(defn raw
  "Wrap `string` so the serializer emits it unescaped.
  For content the caller has already serialized: an inline `<style>`
  body, a `<script>` body, a third-party HTML fragment. Wrapping is an
  assertion — \"I have escaped this myself\" — so anything an agent
  supplied that reaches here is an injection surface, which is why
  nothing agent-authored may construct one."
  {:malli/schema [:=> [:cat :string] :any]}
  [string]
  (->Raw (str string)))

(defn raw?
  "True when `x` came from `raw`."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x]
  (instance? Raw x))

;;; ---------------------------------------------------------------------------
;;; The grammar — real, because the schema gate resolves and runs it
;;; ---------------------------------------------------------------------------

(defn hiccup?
  "True when `x` is hiccup this serializer accepts.

  THE GRAMMAR, and the whole of it:
  - `nil` and `false` — nothing, elided;
  - a string, number, keyword, symbol, `true`, or an `inst` — text;
  - a `raw` value — unescaped text;
  - a VECTOR whose head is a keyword, symbol or string — an element,
    optionally an attribute map in second position, then children;
  - a SEQUENTIAL that is not a vector — a fragment, so
    `(for [x xs] [:li x])` composes inline.

  Everything else is NOT hiccup, and the two exclusions are the point: a
  bare MAP in child position is the mistake an agent makes when it puts
  a sibling key inside its hiccup, and a vector with a non-tag head is
  the mistake it makes when it returns children without wrapping them.
  The old serializer rendered the first as nothing and the second as a
  crash; both are now refusals that name the block.

  Total: never throws, for any input.

  ORDER IS PERFORMANCE HERE, and it was measured rather than guessed.
  This predicate runs over every block's output on the render path, so
  the branch order is the hot order — elements and text first, scalars
  last. The first draft tested nine cheap predicates before `vector?`
  and indexed with `[head & body]` destructuring, which allocates a seq
  per element; over a 250-event page that cost 8.07 ms p50 against
  0.45 ms to SERIALIZE the same tree. Indexing with `nth` over a counted
  vector, and testing the common shapes first, is the whole fix."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x]
  (cond
    (vector? x)
    (let [length (count x)]
      (if (zero? length)
        false
        (let [head (nth x 0)]
          (and (or (keyword? head) (symbol? head) (string? head))
               (let [second-node (when (> length 1) (nth x 1))
                     start (if (and (map? second-node) (not (raw? second-node)))
                             2
                             1)]
                 (loop [index start]
                   (cond
                     (>= index length) true
                     (hiccup? (nth x index)) (recur (inc index))
                     :else false)))))))
    (string? x) true
    (nil? x) true
    ;; BEFORE map?, because Raw is a record
    (raw? x) true
    (map? x) false
    (sequential? x) (every? hiccup? x)
    (number? x) true
    (boolean? x) true
    (keyword? x) true
    (symbol? x) true
    (inst? x) true
    :else false))

(def hiccup-generator
  "An honest generator: its output domain is a subset of `hiccup?`'s
  acceptance and it covers the partitions the serializer branches on —
  text needing escapes, a void element, an element with attributes
  (`:class` as a collection, `:style` as a camelCase map, a bare-true
  and an omitted attribute, a Datastar action attribute), nesting, a
  fragment child, `raw`, and the elided `nil`. Depth is bounded because
  the shrinker walks it; deep-nesting behaviour is a property over
  explicitly constructed values, never something a generator is trusted
  to reach."
  (let [leaf (gen/one-of [gen/string-alphanumeric
                          (gen/return "a & b < c > d \" e ' f")
                          gen/small-integer
                          (gen/return nil)
                          (gen/return false)
                          (gen/return :a-keyword)
                          (gen/fmap raw (gen/return "<b>already escaped</b>"))])
        attrs (gen/elements
               [{}
                {:class "flex gap-2"}
                {:class ["flex" "gap-2"]}
                {:style {:fontSize "12px" :color "red"}}
                {:disabled true :hidden false}
                {:data-on-click "@post('/call')"}])
        tag (gen/elements [:div :span :p :ul :li :br :input :div.card
                           :section#main :div.a.b#c])]
    (gen/recursive-gen
     (fn [inner]
       (gen/one-of
        [(gen/fmap (fn [[t a cs]] (into [t a] cs))
                   (gen/tuple tag attrs (gen/vector inner 0 3)))
         (gen/fmap (fn [[t cs]] (into [t] cs))
                   (gen/tuple tag (gen/vector inner 0 3)))
         (gen/fmap seq (gen/vector inner 1 3))]))
     leaf)))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/block.edn (the population is global, so the
;;; file that owns a kind's first consumer owns its declaration)
;;;
;;; The grammar is a REGISTERED core predicate, not merely a resolvable
;;; symbol: the one admission gate refuses a `[:fn]` naming anything
;;; else, which is what keeps a schema from pointing at a function
;;; nobody vouched for.
;;; ---------------------------------------------------------------------------

(schema/register-core-predicate! 'seon.render.hiccup/hiccup? hiccup?)

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The transform
;;;
;;; ONE StringBuilder threads the whole walk. Every helper below appends
;;; into it and returns a boolean — true when what it wrote is hiccup,
;;; false the moment the grammar is violated. Refusal therefore costs no
;;; second traversal: `->string` discards the buffer and answers "".
;;; ---------------------------------------------------------------------------

(def ^:private void-elements
  "HTML5's void elements. They emit self-closing and take no children;
  https://html.spec.whatwg.org/multipage/syntax.html#void-elements"
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link"
    "meta" "param" "source" "track" "wbr"})

(defn- attribute-name
  [x]
  (cond
    (keyword? x) (name x)
    (symbol? x) (name x)
    (string? x) x
    :else (str x)))

(defn- append-escaped!
  "Append `value`'s string form with the five characters escaped.
  A char-at loop rather than `str/escape`: `str/escape` allocates a
  StringBuilder of its own and returns a string this would immediately
  copy again, which is exactly the per-attribute garbage the budget
  cannot afford."
  [^StringBuilder builder value]
  (let [^String text (if (string? value) value (str value))
        length (.length text)]
    (loop [index 0]
      (when (< index length)
        (let [character (.charAt text index)]
          (case character
            \& (.append builder "&amp;")
            \< (.append builder "&lt;")
            \> (.append builder "&gt;")
            \" (.append builder "&quot;")
            \' (.append builder "&#39;")
            (.append builder character)))
        (recur (inc index))))))

(defn escape
  "The five HTML-special characters, escaped: `& < > \" '`.
  The OWASP HTML-context list, applied to text and to every attribute
  value. Escaping BY DEFAULT is the reason `raw` has to be explicit."
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  (let [builder (StringBuilder. (count text))]
    (append-escaped! builder text)
    (.toString builder)))

(defn shorthand
  "Parse a tag shorthand.
  `:div.card.wide#main` → `{:seon.render.hiccup/tag \"div\"
                            :seon.render.hiccup/id \"main\"
                            :seon.render.hiccup/classes [\"card\" \"wide\"]}`.
  A tag with no id yields an ABSENT `:seon.render.hiccup/id`, never a
  stored nil; a tag with no classes yields an empty vector, because the
  caller concatenates it.

  A head `hiccup?` would have refused returns a flat `:seon.error/value`
  naming what arrived — never nil (`[:maybe]` is banned, and a nil here
  would read downstream as an empty tag) and never a throw. The caller
  has already admitted its input, so a throw would be a second opinion
  about the same value: exactly the behaviour the quarry's `parse-tag`
  had and this rewrite drops."
  {:malli/schema [:=> [:cat :any]
                  [:or [:map {:closed true}
                        [:seon.render.hiccup/tag :string]
                        [:seon.render.hiccup/id {:optional true} :string]
                        [:seon.render.hiccup/classes [:vector :string]]]
                   :seon.error/value]]}
  [head]
  (if-not (or (keyword? head) (symbol? head) (string? head))
    {:seon.error/kind ::unparseable-tag
     :seon.error/message (str "A tag must be a keyword, symbol or string, not "
                              (if (nil? head) "nil" (.getName (class head))) ".")
     :seon.error/data {::head (pr-str head)}}
    ;; ONE left-to-right scan, and it accepts the shorthand in EITHER
    ;; order. The quarry's regex (`^([^.#]+)(?:#([^.#]+))?(?:\.(.+))?$`,
    ;; `src-old/seon/ui/html.cljc`) required `#id` before `.class`, so
    ;; `:div.card#main` silently parsed its id as part of the last class
    ;; name — a wrong id is a morph that never lands, and the shorthand
    ;; is authored by hand and by models that do not know the order.
    (let [text (if (string? head) head (name head))
          length (.length ^String text)
          boundary (fn [from]
                     (loop [index from]
                       (if (or (>= index length)
                               (let [character (.charAt ^String text index)]
                                 (or (= \. character) (= \# character))))
                         index
                         (recur (inc index)))))
          stop (boundary 0)
          tag (subs text 0 stop)]
      (if (zero? (count tag))
        {:seon.error/kind ::unparseable-tag
         :seon.error/message "A tag shorthand carries no tag name."
         :seon.error/data {::head (pr-str head)}}
        (loop [index stop
               id nil
               classes []]
          (if (>= index length)
            (cond-> {:seon.render.hiccup/tag tag
                     :seon.render.hiccup/classes classes}
              ;; absent, never a stored nil
              id (assoc :seon.render.hiccup/id id))
            (let [marker (.charAt ^String text index)
                  stop (boundary (inc index))
                  token (subs text (inc index) stop)]
              (if (zero? (count token))
                {:seon.error/kind ::unparseable-tag
                 :seon.error/message
                 (str "A tag shorthand carries an empty " marker " segment.")
                 :seon.error/data {::head (pr-str head)}}
                (if (= \# marker)
                  ;; last id wins, matching the attribute map's precedence
                  ;; rule one level up: the more specific statement wins
                  (recur stop token classes)
                  (recur stop id (conj classes token)))))))))))

;;; ---------------------------------------------------------------------------
;;; Attributes
;;; ---------------------------------------------------------------------------

(defn- style-property
  "A style-map key as its CSS property name, React's hyphenation rules.
  Models carry React priors and write `:fontSize` forever; without this
  the key renders verbatim and is silently dead in the browser, which is
  the failure class that gets no error and teaches nobody."
  [key]
  (let [text (attribute-name key)]
    (if (str/starts-with? text "--")
      text
      (let [kebab (-> text
                      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                      str/lower-case)]
        (if (re-find #"^[A-Z]" text)
          (str "-" kebab)
          kebab)))))

(defn- style-value
  [value]
  (cond
    (string? value) value
    (map? value) (->> value
                      (map (fn [[key setting]] [(style-property key) setting]))
                      (sort-by first)
                      (map (fn [[property setting]] (str property ": " setting)))
                      (str/join "; "))
    :else (str value)))

(defn- class-value
  "Shorthand classes first, then the attribute's, blanks dropped.
  Returns nil when nothing survives, so the attribute is omitted rather
  than emitted empty."
  [shorthand-classes attribute]
  (let [pieces (concat shorthand-classes
                       (cond
                         (nil? attribute) nil
                         (string? attribute) [attribute]
                         (sequential? attribute) (keep (fn [entry]
                                                         (when entry
                                                           (attribute-name entry)))
                                                       attribute)
                         :else [(attribute-name attribute)]))
        joined (str/join " " (remove str/blank? pieces))]
    (when-not (str/blank? joined) joined)))

(defn- append-attributes!
  [^StringBuilder builder parsed attributes]
  (let [classes (class-value (:seon.render.hiccup/classes parsed)
                             (:class attributes))
        ;; the attribute map is the more specific statement, so its id
        ;; wins over the shorthand's
        id (or (:id attributes) (:seon.render.hiccup/id parsed))
        style (:style attributes)
        final (cond-> (dissoc attributes :class :id :style)
                id (assoc :id id)
                classes (assoc :class classes)
                (some? style) (assoc :style (style-value style)))]
    ;; SORTED, so the same attributes in any literal order produce the
    ;; same bytes. Equality suppression and the SSE diff rest on this.
    (doseq [[key value] (sort-by (comp attribute-name first) (seq final))]
      (cond
        (or (nil? value) (false? value)) nil
        (true? value) (do (.append builder " ")
                          (.append builder ^String (attribute-name key)))
        :else (do (.append builder " ")
                  (.append builder ^String (attribute-name key))
                  (.append builder "=\"")
                  (append-escaped! builder value)
                  (.append builder "\""))))))

;;; ---------------------------------------------------------------------------
;;; The walk
;;; ---------------------------------------------------------------------------

(declare append-node!)

(defn- append-children!
  [^StringBuilder builder children]
  (loop [remaining (seq children)]
    (cond
      (nil? remaining) true
      (append-node! builder (first remaining)) (recur (next remaining))
      :else false)))

(defn- attributes?
  "A Raw is a record, so `map?` is true of it. A serializer detecting
  attributes with `map?` alone would swallow every element's first raw
  child."
  [x]
  (and (map? x) (not (raw? x))))

(defn- append-element!
  [^StringBuilder builder element]
  (let [parsed (if (seq element)
                 (shorthand (nth element 0))
                 ;; `[]` has no head at all; `hiccup?` refuses it, and so
                 ;; must this, without indexing past the end
                 {:seon.error/kind ::unparseable-tag})]
    (if (:seon.error/kind parsed)
      false
      (let [body (subvec element 1)
            attributed? (attributes? (first body))
            attributes (if attributed? (first body) {})
            children (if attributed? (subvec body 1) body)
            tag ^String (:seon.render.hiccup/tag parsed)]
        (.append builder "<")
        (.append builder tag)
        (append-attributes! builder parsed attributes)
        (.append builder ">")
        (if (contains? void-elements tag)
          ;; children on a void element are the author's error and are
          ;; elided — but they are still WALKED, so the grammar reaches
          ;; them and `->string` refuses exactly what `hiccup?` refuses.
          ;; Rendering then rewinding is how one walk does both.
          (let [mark (.length builder)
                admitted (append-children! builder children)]
            (.setLength builder mark)
            admitted)
          (let [admitted (append-children! builder children)]
            (.append builder "</")
            (.append builder tag)
            (.append builder ">")
            admitted))))))

(defn- append-node!
  [^StringBuilder builder node]
  ;; hot order, same reasoning as `hiccup?`: elements and text dominate
  (cond
    (vector? node) (append-element! builder node)
    (string? node) (do (append-escaped! builder node) true)
    (nil? node) true
    (false? node) true
    ;; BEFORE map?, because Raw is a record
    (raw? node) (do (.append builder ^String (:string node)) true)
    (map? node) false
    (sequential? node) (append-children! builder node)
    ;; a number needs no escaping
    (number? node) (do (.append builder (str node)) true)
    (keyword? node) (do (append-escaped! builder (str node)) true)
    (symbol? node) (do (append-escaped! builder (str node)) true)
    (true? node) (do (.append builder "true") true)
    (inst? node) (do (append-escaped! builder (str node)) true)
    :else false))

(defn ->string
  "Serialize admitted hiccup to one HTML string.

  ONE `StringBuilder` per call, appended into by the whole walk. This is
  the innermost loop of every morph and the owner's bar is a 16 ms frame
  under churn, so the implementation may not build an intermediate
  string per element or per attribute.

  DETERMINISM IS A CONTRACT, not a convenience: attributes emit in
  sorted key order, so one value always produces one byte string.
  Equality suppression, the SSE diff, and byte identity across a
  replacement process all rest on it.

  Total over hiccup. For a value `hiccup?` refuses the result is the
  empty string — the caller admits first and renders an error card, so
  arriving here with a refused value is OUR bug, and emitting nothing is
  the one answer that cannot leak raw EDN into a human's page.

  The rules the quarry established, which this keeps:
  - void elements (`area base br col embed hr img input link meta param
    source track wbr`) emit self-closing, with no closing tag; children
    on a void element are the author's error and are elided;
  - every non-void element emits its closing tag even when empty, so
    `<div id=\"surface-x\"></div>` stays a stable morph target;
  - `nil` and `false` children are elided; a seq child flattens in place;
  - an attribute whose value is `true` emits bare (`<input disabled>`);
    `false` and `nil` omit the attribute entirely;
  - `:class` accepts a string or a collection and merges with the tag
    shorthand's classes, shorthand first;
  - `:style` accepts a string or a map; map keys normalize to CSS
    property names (`:fontSize` → `font-size`, `:WebkitMask` →
    `-webkit-mask`, `--custom` untouched) and emit sorted;
  - an `:id` in the attribute map wins over the tag shorthand's;
  - no doctype: a shell prepends it around the rendered root."
  {:malli/schema [:=> [:cat :any] :string]}
  [hiccup]
  (let [builder (StringBuilder. 256)]
    (if (append-node! builder hiccup)
      (.toString builder)
      "")))
