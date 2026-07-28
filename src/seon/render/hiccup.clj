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
  (:require [clojure.test.check.generators :as gen]
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

  Total: never throws, for any input."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x]
  (cond
    (nil? x) true
    (boolean? x) true
    (string? x) true
    (number? x) true
    (keyword? x) true
    (symbol? x) true
    (inst? x) true
    (raw? x) true
    (map? x) false
    (vector? x)
    (let [[head & body] x]
      (and (or (keyword? head) (symbol? head) (string? head))
           (every? hiccup?
                   (if (and (map? (first body)) (not (raw? (first body))))
                     (rest body)
                     body))))
    (sequential? x) (every? hiccup? x)
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
;;; ---------------------------------------------------------------------------

(defn escape
  "The five HTML-special characters, escaped: `& < > \" '`.
  The OWASP HTML-context list, applied to text and to every attribute
  value. Escaping BY DEFAULT is the reason `raw` has to be explicit."
  {:malli/schema [:=> [:cat :string] :string]}
  [_text]
  (throw (ex-info "seon.render.hiccup/escape awaits implementation" {})))

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
  [_head]
  (throw (ex-info "seon.render.hiccup/shorthand awaits implementation" {})))

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
  [_hiccup]
  (throw (ex-info "seon.render.hiccup/->string awaits implementation" {})))
