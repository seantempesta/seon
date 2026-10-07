(ns seon.print
  "Emits admitted print nodes as fipp-laid-out text and structural hiccup."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fipp.engine :as fipp]
            [clojure.test.check.generators :as gen]
            [seon.ai.tokens :as tokens]
            [seon.schema :as schema]
            #?(:clj [seon.schema.edn :as schema.edn])
            [malli.core :as m]
            [seon.schema.internal :as internal]))

(defn- literal
  [value]
  (binding [*print-readably* true *print-dup* false
            *print-length* nil *print-level* nil *print-meta* false]
    (pr-str value)))

(defn string-literal
  "`s` as a readable string literal whose newlines stay line breaks, so a
  multi-line string (a function's source) reads as its lines."
  {:malli/schema [:=> [:cat :string] :string]}
  [s]
  #?(:clj (str "\"" (str/escape s #(when-not (= \newline %) (char-escape-string %))) "\"")
     :cljs (literal s)))

(defn print-number?
  "True for a number with a stock Clojure printed form."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (number? value))

(def number-generator
  "Numbers spanning the stock printer's distinct suffix and special forms."
  (gen/one-of
   #?(:clj
      [gen/small-integer
       gen/double
       (gen/fmap clojure.core/bigint gen/small-integer)
       (gen/fmap clojure.core/bigdec gen/small-integer)
       (gen/fmap (fn [[n d]] (/ n (if (zero? d) 1 d)))
                 (gen/tuple gen/small-integer gen/small-integer))
       (gen/elements [Float/POSITIVE_INFINITY Float/NEGATIVE_INFINITY Float/NaN
                      Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY
                      Double/NaN])]
      :cljs
      [gen/small-integer gen/double])))

(defn print-char?
  "True for a character with a stock Clojure printed form."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (char? value))

(def char-generator
  "Characters spanning named and escaped stock printed forms."
  (gen/elements [\a \space \newline \tab \return \backspace \formfeed]))

(def projected-node-generator
  "A readable terminal projection for recursive print-node generation."
  (gen/return {::face ::projected
               :seon.render/output :seon.render/ai
               ::value "nil"}))

(def elision-node-generator
  "A complete refusal-bearing elision for recursive print-node generation."
  (gen/return {::face ::elided
               ::omitted 1
               ::elision-unit :subtree
               :seon.render.data/path []
               :seon.render.data/next-offset 0
               :seon.render.profile/id :seon.render.profile/agent
               ::requery-refusal "generated values have no stable identity"}))

(declare emit-text)

(def ^:private generated-item-options
  ;; The generator's own canonical text: no length cut, no level cut, no
  ;; soft-wrap column. Two generated children are the SAME set item exactly
  ;; when they emit the same complete text, which is the equality the EDN
  ;; reader applies when it reads the emitted literal back.
  {::length nil ::level nil ::width 0
   ::namespace-maps? true ::table? false})

(defn- generated-item-identity
  "The identity the EDN reader itself would give one generated child.

  THE READER IS THE AUTHORITY ON DUPLICATES, not the node and not its text.
  Node equality is too strict — `{::face ::nil}` and the `::projected` node
  whose value is the string nil print the same literal — and text equality
  is too strict too: `[]` and `()` are different literals and the SAME set
  member, which is exactly the counterexample the round-trip property
  shrank to. So the key is the value the emitted literal reads back as, and
  the literal itself only when it does not read."
  [node]
  (let [text (emit-text node generated-item-options)]
    (try
      [::read (edn/read-string {:readers {'error identity}} text)]
      (catch #?(:clj Throwable :cljs :default) _
        [::text text]))))

(defn- distinct-generated-nodes
  "The generated children that read back as distinct values, in order.

  A `#{}` literal and a `{}` literal CANNOT CARRY A DUPLICATE, so a
  generator that emits one is dishonest about the grammar and the round-trip
  it feeds refuses with `Duplicate key`."
  [key-fn nodes]
  (first
   (reduce (fn [[kept seen] node]
             (let [identity (generated-item-identity (key-fn node))]
               (if (contains? seen identity)
                 [kept seen]
                 [(conj kept node) (conj seen identity)])))
           [[] #{}]
           nodes)))

(def node-generator
  "Whole print-node trees spanning every declared kind.

  `:seon.print/node` is validated by a predicate rather than a recursive
  Malli ref (see `node?`), so the domain declares its own generator instead
  of inheriting one from a recursive reference. Every kind the emitter is
  total over appears here, and containers recur under test.check's own
  depth bound."
  (let [named (fn [kind]
                (gen/fmap (fn [name] {::face kind ::name name})
                          gen/string-alphanumeric))
        scalar
        (gen/one-of
         [(gen/return {::face ::nil ::value nil})
          (gen/fmap (fn [value] {::face ::boolean ::value value}) gen/boolean)
          (gen/fmap (fn [value] {::face ::number ::value value})
                    number-generator)
          (gen/fmap (fn [value] {::face ::keyword ::value value}) gen/keyword)
          (gen/fmap (fn [value] {::face ::symbol ::value value}) gen/symbol)
          (gen/fmap (fn [value] {::face ::char ::value value}) char-generator)
          (gen/fmap (fn [value] {::face ::string ::value value}) gen/string)
          #?(:clj (gen/fmap (fn [ms] {::face ::inst
                                      ::value (java.util.Date. (long ms))})
                            gen/nat))
          (gen/fmap (fn [value] {::face ::uuid ::value value}) gen/uuid)
          (named ::var)
          (named ::type)
          (named ::class)
          (gen/fmap (fn [class-name] {::face ::object ::class class-name})
                    gen/string-alphanumeric)
          (gen/fmap (fn [value] {::face ::truncated-string
                                 ::value value
                                 ::length (inc (count value))
                                 ::bound-by
                                 :seon.config.eval.result/max-string})
                    gen/string)
          (gen/fmap (fn [[class-name message]]
                      {::face ::failed ::class class-name ::message message})
                    (gen/tuple gen/string-alphanumeric gen/string-alphanumeric))
          elision-node-generator
          projected-node-generator
          (gen/return {::face ::pruned})])]
    (gen/recursive-gen
     (fn [inner]
       (let [entries (gen/vector (gen/tuple inner inner) 0 3)]
         (gen/one-of
          [(gen/fmap (fn [items] {::face ::vector ::items items})
                     (gen/vector inner 0 3))
           (gen/fmap (fn [items] {::face ::list ::items items})
                     (gen/vector inner 0 3))
           (gen/fmap (fn [items]
                       {::face ::set
                        ::items (distinct-generated-nodes identity items)})
                     (gen/vector inner 0 3))
           (gen/fmap (fn [entities]
                       {::face ::map
                        ::entries (distinct-generated-nodes first entities)})
                     entries)
           (gen/fmap (fn [[record-name entities]]
                       {::face ::record ::name record-name
                        ::entries (distinct-generated-nodes first entities)})
                     (gen/tuple gen/string-alphanumeric entries))
           (gen/fmap (fn [value] {::face ::throwable ::value value}) inner)])))
     scalar)))

(def ^:private shipped-option-defaults
  (delay
    (let [forms #?(:clj (schema.edn/packaged-forms)
                   :cljs {})]
      (into {}
            (keep
             (fn [entry]
               (when (vector? entry)
                 (let [attribute (first entry)
                       properties
                       (m/properties (schema/structural-schema
                                      (get forms attribute)))]
                   (when (contains? properties ::default)
                     [attribute (::default properties)])))))
            (internal/entity-entries
             (schema/structural-schema (get forms ::options)))))))

(defn- option-defaults
  []
  ;; Every emit resolves these defaults, so the declaration population is read
  ;; ONCE here and each option read with `get`. Asking
  ;; `schema/schema-definition` per option re-reads and re-merges every schema
  ;; resource per option (measured 2026-08-07: 67.9 ms / 912 resource reads for
  ;; one call with no projection supplied — issue
  ;; packaged-forms-rereads-every-schema-resource-per-call).
  @shipped-option-defaults)

(defn default-options
  "The complete shipped print options derived from their declarations."
  {:malli/schema [:=> [:cat] :seon.print/options]}
  []
  (option-defaults))

(defn- effective-options
  [options]
  (merge (option-defaults) options))

(defn path-step
  "The member one path step names in `parent`, or `missing`.

  Design one-render-path §3b. The value printer emits a typed step wherever a plain key
  would be ambiguous: `[:seon.render.data/entry i :seon.render.data/key]` or
  `[... :seon.render.data/val]` is entry `i` of a map in its iteration order,
  `[:seon.render.data/nth i]` member `i` of a set or seq. A plain step is
  `get-in`'s: a map key, or a sequence index."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value :seon.schema/value] :seon.schema/value]}
  [parent step missing]
  (let [tag (when (vector? step) (first step))
        index (when tag (second step))]
    (cond
      (= tag :seon.render.data/entry)
      (let [entry (when (and (map? parent) (nat-int? index)) (nth (seq parent) index nil))]
        (case (when entry (nth step 2 nil))
          :seon.render.data/key (key entry)
          :seon.render.data/val (val entry)
          missing))
      (= tag :seon.render.data/nth)
      (if (and (or (sequential? parent) (set? parent)) (nat-int? index)) (nth (seq parent) index missing) missing)
      (map? parent) (if-some [entry (find parent step)] (val entry) missing)
      (and (sequential? parent) (nat-int? step)) (nth parent step missing)
      (set? parent) (if (contains? parent step) step missing)
      :else missing)))

(defn value-at
  "Read a path of `path-step`s; nil where it leaves the value."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.render.data/path] :seon.schema/value]}
  [value path]
  (reduce #(path-step %1 %2 nil) value path))

(defn full
  "`value`, marked to print whole as this evaluation's shown text: no token
  budget and no member, depth or string bound, for this one evaluation only
  (`(seon.print/full result/e7b3)`). The opt-in is recorded with the
  evaluation. Reading a part is plain Clojure on the handle instead (`keys`,
  `count`, `get-in`, `take`, `drop`, `take-last`), and each cut prints the form
  that reads its rest. A value that cannot carry metadata (a string, a number)
  is returned unchanged; `println` prints a long string whole."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Any value an agent holds may be printed whole; it is returned unchanged apart from the print mark.", :gen/elements [nil false 0 "" :k [] {}]}]]
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Returns its argument, marked when it can carry metadata.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [value]
  (if #?(:clj (instance? clojure.lang.IObj value) :cljs (satisfies? IWithMeta value))
    (vary-meta value assoc ::full true)
    value))

(defn compare-values
  "Order whole values structurally without rendering their contents or invoking pairs."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}]] :int]}
  [left right]
  (letfn [(category [value]
            (cond (nil? value) "nil"
                  (map? value) "map"
                  (set? value) "set"
                  (vector? value) "vector"
                  (sequential? value) "list"
                  :else (str (type value))))
          (items [left right]
            (loop [left (seq left) right (seq right)]
              (cond (nil? left) (if (nil? right) 0 -1)
                    (nil? right) 1
                    :else (let [order (order-value (first left) (first right))]
                            (if (zero? order)
                              (recur (next left) (next right)) order)))))
          (order-value [left right]
            (let [kind (compare (category left) (category right))]
              (cond
                (not (zero? kind)) kind
                (identical? left right) 0
                (map? left) (items (sort-by key order-value left)
                                   (sort-by key order-value right))
                (set? left) (items (sort order-value left) (sort order-value right))
                (sequential? left) (items left right)
                :else (try (compare left right)
                           (catch #?(:clj ClassCastException :cljs :default) _ 0)))))]
    (order-value left right)))

(defn- requery-source
  [source-identity]
  (cond
    (symbol? source-identity) source-identity
    (seq? source-identity) source-identity
    (or (vector? source-identity) (int? source-identity))
    (list 'seon.db/pull (list 'quote '[*]) (list 'quote source-identity))))

(defn- requery-form
  [source-identity path]
  (when-let [source (requery-source source-identity)]
    (list 'seon.print/value-at source
          (if (some #(or (symbol? %) (coll? %)) path)
            (list 'quote path) path))))

(defn continuation
  "The copyable form that reads what one elision omitted, from its next
  offset: `(drop n …)` for members, `(subs … n)` for characters, over the
  cut's path reached with plain `get-in` when every step is a key or index
  and with `seon.print/value-at` when a step is typed. Nil when the cut has no
  source to read from (its `::requery-refusal` says why). A cut of a whole
  collection at the root is the handle itself, a symbol, which reads it all.

      (drop 32 result/e7b3)
      (subs (get-in result/e7b3 [:rows 0 :title]) 16)"
  {:malli/schema [:=> [:cat :map] [:maybe :seon.print/continuation]]}
  [{::keys [requery-id elision-unit] path :seon.render.data/path
    next-offset :seon.render.data/next-offset}]
  (when-let [source (requery-source requery-id)]
    (let [path (vec path)
          target (cond (empty? path) source
                       (every? #(or (keyword? %) (nat-int? %)) path) (list 'get-in source path)
                       :else (list 'seon.print/value-at source (list 'quote path)))
          offset (or next-offset 0)]
      (cond (zero? offset) target
            (= :characters elision-unit) (list 'subs target offset)
            :else (list 'drop offset target)))))

(defn form-text
  "A generated form as the text an agent would type: `'x` for `(quote x)`,
  every other value as readable EDN under fixed print bindings."
  {:malli/schema [:=> [:cat :seon.schema/value] :string]}
  [form]
  (cond (and (seq? form) (= 'quote (first form)) (= 2 (count form))) (str "'" (form-text (second form)))
        (seq? form) (str "(" (str/join " " (map form-text form)) ")")
        :else (literal form)))

(defn elided-text
  "The one text of a cut: `…N` after shown members, `\"prefix\"…+N`
  after a string's shown characters (render text's prefix as written, then
  `…+N` on its own line), `…?` for a pending tail, each followed by
  its continuation form when the cut has a source. The value printer's allocator
  measures cuts with this same function."
  {:malli/schema [:=> [:cat :map] :string]}
  [node]
  (let [prefix (or (::prefix node) "")
        text (case (::elision-unit node)
               :characters (if (::raw? node)
                             ;; Render text is shown as written, its cut on a line of its own.
                             (str prefix (when-not (str/ends-with? prefix "\n") "\n") "…+" (::omitted node))
                             (str (string-literal prefix) "…+" (::omitted node)))
               :pending "…?"
               (str "…" (::omitted node "?")))]
    (if-some [form (::continuation node)]
      (str text " " (form-text form))
      text)))

(defn render-elision-ai
  "Render omitted data as one readable EDN value with its coordinates.

  THE RETAINED PREFIX IS PART OF THE CUT'S EVIDENCE. A character cut keeps
  the text it did show in `::prefix`; dropping it here is what turned an
  over-long value into a count of what the reader was not told.

  THE SIZE AN AGENT BUDGETS BY IS ESTIMATED TOKENS (AGENTS.md §2.4), and
  `:seon.ai.tokens/estimate` is that figure for the omitted remainder. The
  declared fields are NOT rewritten into tokens, because `next-offset` and
  `total` are the reader's COORDINATES into the value, not display sizes:
  `seon.render.value-test/explicit-structural-results-retain-attributes-through-real-evaluation`
  derives both from `(count original)` and then executes the requery form
  against them. Restating a coordinate in a rounded-down estimate would leave the
  cut naming a position that does not exist — one honest number replaced by
  three that no longer add up. A member cut has no derivable token size, so
  the field is absent there rather than invented.

  A cut with no requery identity says so: `::requery-refusal` is the typed
  unknown §2.4 requires, not silence."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [shown (select-keys unit
                           [::omitted ::elision-unit ::bound-by ::prefix
                            :seon.render.data/path :seon.render.data/next-offset
                            :seon.render.data/total ::requery-form
                            ::requery-refusal])]
    (literal
     (into (sorted-map)
           (cond-> shown
             (and (= :characters (::elision-unit unit)) (int? (::omitted unit)))
             (assoc :seon.ai.tokens/estimate
                    (max 1 (tokens/estimate-of-characters (::omitted unit)))))))))

;;; EMISSION: one explicit-stack walk turns a node tree into fipp's own
;;; operation maps (`reference-code/fipp/src/fipp/engine.cljc:12–65`), so
;;; depth never uses the call stack. Text is fipp's own three stages over that
;;; stream (`engine.cljc:68–223`); hiccup is a fold over the same stream, so
;;; the two outputs share every token boundary. A `:text` op carries its CSS
;;; class and, where the faces spell a token differently, the hiccup it stands
;;; for: a string shows its own characters in HTML (its quotes are CSS) and
;;; its readable literal in text. A structural `:begin`/`:end` carries the
;;; node it opens.

(def ^:private scalar-kinds
  #{::nil ::boolean ::number ::keyword ::symbol ::char ::string
    ::inst ::uuid ::var ::type ::class ::object ::truncated-string
    ::failed ::elided ::pruned})

(def ^:private structural-kinds
  #{::vector ::list ::set ::map ::record ::throwable})

(def ^:private nest-depth
  ;; Indentation stops growing past this many open collections, so a deep
  ;; value's laid-out text grows linearly with depth, not quadratically.
  6)

(def ^:private line {:op :line :inline " " :terminate ""})

(defn- css-class
  [kind]
  (str "seon-print-" (name kind)))

(defn- text-op
  ([css text] {:op :text :text text ::class css})
  ([css text hiccup] {:op :text :text text ::class css ::hiccup hiccup}))

(defn- object-class-text
  "The class an object node names, under either key it may carry.

  Admission mints `:seon.print/class`; nodes stored before that spelling carry
  the same string under `:seon.print/name`."
  [node]
  (let [named (or (::class node) (::name node))]
    (when (and (string? named) (not (str/blank? named)))
      named)))

(defn- diagnostic-text
  [message node & {:as extra}]
  (literal (merge {:seon.error/message message
                   :seon.error/data {::kind (::face node)
                                     ::node-keys (vec (sort-by str (keys node)))}}
                  extra)))

(defn- elided-hiccup
  "A cut as HTML: a string cut's shown characters, then its marker and
  continuation form, as the text face prints them. The marker links to the
  continuation page when the cut has `::href`; a cut with no continuation
  names why in its title; a string cut with `::text-href` also links to the
  whole text."
  {:malli/schema [:=> [:cat :map :string] :seon.render/hiccup]}
  [node text]
  (let [prefix (or (::prefix node) "")
        shown (cond (not= :characters (::elision-unit node)) nil
                    (::raw? node) (subs text 0 (str/index-of text "…+" (count prefix)))
                    :else prefix)
        label (if shown (subs text (if (::raw? node) (count shown) (count (string-literal prefix)))) text)
        ;; The label already shows the continuation form; a cut without one says why.
        reason (when-not (::continuation node) (::requery-refusal node))
        attributes (cond-> {:class (css-class ::elision)} reason (assoc :title reason))
        marker (if-some [href (::href node)] [:a (assoc attributes :href href) label] [:span attributes label])]
    (if shown
      (cond-> [:span [:span {:class (css-class (if (::raw? node) ::projected ::string))} shown] marker]
        (::text-href node) (conj " " [:a {:class "seon-print-whole-text" :href (::text-href node)} "whole text"]))
      marker)))

(defn- leaf-ops
  "The text ops of one leaf print node at `depth`; a malformed or unknown node
  is one diagnostic op."
  {:malli/schema [:=> [:cat :map :int] [:vector :map]]}
  [node depth]
  (let [value (::value node)]
    (case (::face node)
      ;; A scalar the value printer selected carries its classified spelling in
      ;; `::text`, made from the value's own fields, never through `pr-str`.
      ::nil [(text-op ::nil "nil")]
      (::boolean ::number ::keyword ::symbol ::char)
      [(text-op (::face node) (or (::text node) (literal value)))]
      ::string [(text-op ::string (or (::text node) (string-literal value))
                         [:span {:class (css-class ::string)} (or (::text node) value)])]
      (::inst ::uuid) [(text-op ::tag (or (::text node) (literal value)))]
      ::var [(text-op ::symbol (str "#'" (::name node)))]
      (::type ::class) [(text-op ::symbol (::name node))]
      ::object [(text-op ::object
                         (if-some [class-text (object-class-text node)]
                           (str "#object[" class-text
                                (when-some [rep (::rep node)] (str " " rep)) "]")
                           ;; An empty `#object[]` would read as content.
                           (diagnostic-text "The object print node names no class." node)))]
      ::truncated-string [(text-op ::string (string-literal (str value "…"))
                                   [:span {:class (css-class ::string)} (str value "…")])]
      ::failed [(text-op ::object (str "#object[" (::class node) " "
                                       (literal (str "projection failed: " (::message node))) "]"))]
      ;; ONE CUT FACE (design §3e): `…N` after shown members, `"prefix"…+N`
      ;; after a string's shown characters, `…?` for a pending tail. The cut's
      ;; coordinates stay on the node for HTML and continuation.
      ;; A cut with an expander (`::href`, the continuation at its path and
      ;; next offset) is a link in HTML (`elided-hiccup`).
      ::elided (let [text (elided-text node)]
                 [(text-op ::elision text (elided-hiccup node text))])
      ::pruned [(text-op ::prune "#")]
      ::projected (let [output (:seon.render/output node)]
                    (cond
                      (and (pos? depth) (= :seon.render/ai output))
                      [(text-op ::string (string-literal value) [:span {:class (css-class ::string)} value])]
                      (= :seon.render/ai output)
                      [(text-op ::projected value [:span {:class (css-class ::projected)} value])]
                      :else [(text-op ::projected (literal value) value)]))
      ;; The walk is the terminal outward boundary: an undeclared or absent
      ;; kind is one flat diagnostic value, never a second exception.
      [(text-op ::object (diagnostic-text "The admitted value has no declared print kind." node
                                          ::unknown-kind (::face node)))])))

(defn- same-key-namespace
  [entries]
  (when (< 1 (count entries))
    (let [key-nodes (map first entries)
          namespaces (map (comp namespace ::value) key-nodes)]
      (when (and (every? #(contains? #{::keyword ::symbol} (::face %)) key-nodes)
                 ;; A key shown as a cut name keeps its own spelling.
                 (every? #(or (nil? (::text %)) (= (::text %) (literal (::value %)))) key-nodes)
                 (every? some? namespaces)
                 (apply = namespaces))
        (first namespaces)))))

(defn- unqualified-key-node
  [node]
  (-> node
      (dissoc ::text)
      (update ::value (if (= ::keyword (::face node))
                        #(keyword (name %))
                        #(symbol (name %))))))

(defn- summary
  [node path]
  (str (name (::face node)) " "
       (or (:seon.render.data/total node)
           (count (or (::items node) (::entries node))))
       " items, depth " (count path)))

(defn- collection-work
  "The work one collection expands to: its open group, members and close."
  [node options depth path]
  ;; Held order: the producer's order is the printed order (design §3b).
  (let [children (vec (or (::items node) (::entries node)))
        ;; A quoted form prints as the reader writes it, `'x`, so a dig or
        ;; requery form inside data reads as the form an agent would type.
        quoted? (and (= ::list (::face node)) (= 2 (count children))
                     (= ::symbol (::face (first children))) (= 'quote (::value (first children))))
        children (if quoted? (subvec children 1) children)
        length (::length options)
        shown (if (and length (< length (count children))) (into [] (take length) children) children)
        cut? (< (count shown) (count children))
        map-like? (contains? #{::map ::record} (::face node))
        lifted (when (and (= ::map (::face node)) (::namespace-maps? options))
                 (same-key-namespace (filterv vector? shown)))
        shown (if lifted (mapv #(if (vector? %) (update % 0 unqualified-key-node) %) shown) shown)
        open (case (::face node)
               ::vector "[" ::list (if quoted? "'" "(") ::set "#{"
               ::map (str (when lifted (str "#:" lifted)) "{")
               ::record (str "#" (::name node) "{"))
        close (case (::face node) ::vector "]" ::list (if quoted? "" ")") "}")
        indent (fn [offset] {:op :nest :offset (if (< depth nest-depth) offset 0)})
        d (inc depth)
        fill? (and (not map-like?) (every? (comp scalar-kinds ::face) shown))
        member (fn [index child]
                 (let [child-path (conj path index)]
                   (cond
                     (and map-like? (vector? child))
                     [{:op :begin} [(first child) d (conj child-path 0)] (indent 2)
                      line [(second child) d (conj child-path 1)] {:op :outdent} {:op :end}]
                     (or (zero? index) map-like? (not fill?)) [[child d child-path]]
                     :else [{:op :begin} line [child d child-path] {:op :end}])))
        separator (fn [index]
                    (cond (zero? index) []
                          map-like? [(text-op ::separator ",") line]
                          fill? []
                          :else [line]))]
    (-> (reduce-kv (fn [work index child]
                     (reduce conj! work (into (separator index) (member index child))))
                   (transient [{:op :begin ::open (assoc node ::path path)}
                               (text-op ::delimiter open)
                               (indent (if (= ::set (::face node)) 2 1))])
                   shown)
        (cond-> cut? (as-> work (reduce conj! work
                                        (conj (if (seq shown)
                                                (if fill? [line] (separator (count shown)))
                                                [])
                                              (text-op ::elision "...")))))
        (conj! {:op :outdent})
        (conj! (text-op ::delimiter close))
        (conj! {:op :end ::close node})
        persistent!)))

(defn- node-work
  [node options depth path]
  (let [level (::level options)]
    (cond
      (and (contains? structural-kinds (::face node)) level (>= depth level))
      [(text-op ::prune "#")]

      (= ::throwable (::face node))
      [(text-op ::tag "#error ") [(::value node) depth (conj path ::throwable)]]

      (contains? structural-kinds (::face node))
      (collection-work node options depth path)

      :else (leaf-ops node depth))))

(defn- node-ops
  "One node tree as fipp operation maps, walked with an explicit stack."
  [node options]
  (loop [stack (list [node 0 []])
         ops (transient [])]
    (if-some [work (first stack)]
      (if (map? work)
        (recur (rest stack) (conj! ops work))
        (let [[node depth path] work]
          (recur (into (rest stack) (rseq (node-work node options depth path))) ops)))
      (persistent! ops))))

(defn- ops-text
  "Lay operations out to `width` with fipp's own stages; width 0 is one line."
  [ops width]
  (let [out #?(:clj (StringBuilder.) :cljs (goog.string.StringBuffer.))
        append #?(:clj (fn ([result] result)
                         ([^StringBuilder result ^String text] (.append result text)))
                  :cljs (fn ([result] result) ([result text] (.append result text))))]
    (if (pos? width)
      (let [layout {:width width}]
        (transduce (comp fipp/annotate-rights (fipp/annotate-begins layout) (fipp/format-nodes layout))
                   append out ops))
      (transduce (keep #(case (:op %) :text (:text %) :line (:inline %) nil)) append out ops))
    (str out)))

(defn- close-frame
  [{::keys [node children]}]
  [:details
   {:class (str "seon-print-node " (css-class (::face node)))
    :data-seon-path (literal (::path node))}
   [:summary {:class "seon-print-summary"} (summary node (::path node))]
   (into [:span {:class "seon-print-content"}] children)])

(defn- ops-hiccup
  "The same operations as structural hiccup: a `<details>` per collection."
  [ops]
  (let [append (fn [{::keys [stack] :as state} child]
                 (if (seq stack)
                   (update-in state [::stack (dec (count stack)) ::children] conj child)
                   (update state ::roots conj child)))
        {::keys [roots]}
        (reduce (fn [state {:keys [op] :as event}]
                  (cond
                    (::open event) (update state ::stack conj {::node (::open event) ::children []})
                    (::close event) (let [frame (peek (::stack state))]
                                      (append (update state ::stack pop) (close-frame frame)))
                    (= :text op) (append state (or (::hiccup event)
                                                   [:span {:class (css-class (::class event))} (:text event)]))
                    (= :line op) (append state [:span {:class (css-class ::separator)} (:inline event)])
                    :else state))
                {::stack [] ::roots []}
                ops)]
    (case (count roots)
      0 [:span {:class "seon-print-empty"} ""]
      1 (first roots)
      (into [:span {:class "seon-print-root"}] roots))))

(defn emit-text
  "Emit one admitted node as REPL-faithful text laid out to its width."
  {:malli/schema
   [:=> [:cat :seon.print/node :seon.print/options] :string]}
  [node options]
  (let [options (effective-options options)]
    (ops-text (node-ops node options) (::width options))))

(defn emit-hiccup
  "Emit one admitted node as structural hiccup."
  {:malli/schema
   [:=> [:cat :seon.print/node :seon.print/options] :seon.render/hiccup]}
  [node options]
  (ops-hiccup (node-ops node (effective-options options))))

(defn emit-both
  "Emit text and hiccup from one operation stream."
  {:malli/schema
   [:=> [:cat :seon.print/node :seon.print/options] :seon.print/result]}
  [node options]
  (let [options (effective-options options)
        ops (node-ops node options)]
    {::text (ops-text ops (::width options))
     ::hiccup (ops-hiccup ops)}))

(defn- scalar-node-value
  [node]
  (when (contains? #{::nil ::boolean ::number ::keyword ::symbol ::char
                     ::string ::inst ::uuid}
                   (::face node))
    (::value node)))

(defn- lookup-reference
  [identity-attributes node]
  (when (and (= ::vector (::face node))
             (= 2 (count (::items node))))
    (let [[attribute-node value-node] (::items node)
          attribute (scalar-node-value attribute-node)
          value (scalar-node-value value-node)]
      (when (and (contains? identity-attributes attribute)
                 (some? value))
        [attribute value]))))

(defn- entity-references
  [identity-attributes node]
  (when (contains? #{::map ::record} (::face node))
    (into #{}
          (keep (fn [entry]
                  (when (vector? entry)
                    (let [[attribute-node value-node] entry
                          attribute (scalar-node-value attribute-node)
                          value (scalar-node-value value-node)]
                      (when (and (contains? identity-attributes attribute)
                                 (some? value))
                        [attribute value])))))
          (::entries node))))

(defn- child-nodes
  [node]
  (case (::face node)
    (::vector ::list ::set) (::items node)
    (::map ::record) (mapcat #(if (vector? %) % [%]) (::entries node))
    ::throwable [(::value node)]
    []))

(defn references
  "Return every symbol and entity identity structurally present in a print node.

  The caller supplies the schema-derived identity attributes. This walker has
  no knowledge of agent, message, namespace, or other domain shapes: a symbol
  in a value is a reference, and a lookup ref or identity-bearing map in a
  value is an entity reference. The generated-opening pull applies membership
  after this walk; this function only reports what the settled value exposed."
  {:malli/schema [:=> [:cat :seon.print/identity-attributes
                       :seon.print/node]
                  :seon.print/references]}
  [identity-attributes node]
  (loop [pending [node]
         found #{}]
    (if-let [current (peek pending)]
      (let [symbol-value (when (= ::symbol (::face current))
                           (::value current))
            lookup (lookup-reference identity-attributes current)
            entity-refs (entity-references identity-attributes current)]
        (recur (into (pop pending) (child-nodes current))
               (cond-> (into found entity-refs)
                 symbol-value (conj symbol-value)
                 lookup (conj lookup))))
      found)))

(defn node-child?
  "True when `value` occupies one print node's child slot."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (map? value) (keyword? (::face value))))

(def ^:private node-kind-validator*
  ;; THE KIND TABLE IS CORE-OWNED AND SHIPPED: no cluster, agent or delta
  ;; registers a `:seon.print/node-kind`, so one validator compiled from the
  ;; packaged declaration population is the same answer every projection
  ;; would give — and compiling it per call (or reading the declaration
  ;; population per call, which is what asking the ambient registry costs)
  ;; is the measured performance trap this project already named.
  (delay
    (schema/projection-validator
     (schema/declaration-projection
      #?(:clj (schema.edn/packaged-forms) :cljs {}))
     ::node-kind)))

(defn- node-kind-validator
  []
  @node-kind-validator*)

(defn node?
  "True when `value` is a print node, one node at a time.

  ITERATIVE BY CONSTRUCTION. A recursive Malli `:ref` validates a node by
  recursing once per level: at depth 3,510 the contract on an admitted
  value answered `java.lang.StackOverflowError` — an `Error` escaping a
  total operation at exactly the boundary law 2.4 requires a flat value
  from, and the walk that produced the node is iterative. Each node is
  checked against the declared per-kind shape (`:seon.print/node-kind`)
  and its children are handed back to this loop, so the kind table is
  declared once and the depth a contract admits is the depth admission
  admits."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (let [valid? (node-kind-validator)]
    (loop [pending (list value)]
      (if-some [remaining (seq pending)]
        (let [node (first remaining)]
          (if (valid? node)
            (recur (into (rest remaining) (child-nodes node)))
            false))
        true))))

(defn elision
  "One complete elision node: what was omitted, where, and how to ask again.

  Refitting and value projection use this constructor, so count, path and
  requery identity travel with every cut. The AI emitter prints these as
  ordinary EDN. Missing coordinates remain absent. A node without a requery
  identity retains the reason internally and prints no invented requery."
  {:malli/schema [:=> [:cat :seon.print/elision-request] :seon.print/node]}
  [{::keys [requery-id requery-refusal omitted elision-unit prefix bound-by raw?]
    supplied ::continuation
    profile-id :seon.render.profile/id
    path :seon.render.data/path
    next-offset :seon.render.data/next-offset
    total :seon.render.data/total}]
  (cond-> {::face ::elided}
    (some? omitted) (assoc ::omitted (max 1 (long omitted)))
    (some? elision-unit) (assoc ::elision-unit elision-unit)
    (some? path) (assoc :seon.render.data/path (vec path))
    (some? next-offset)
    (assoc :seon.render.data/next-offset (long next-offset))
    (some? profile-id) (assoc :seon.render.profile/id profile-id)
    (some? total) (assoc :seon.render.data/total (long total))
    (some? prefix) (assoc ::prefix prefix)
    (some? bound-by) (assoc ::bound-by bound-by)
    raw? (assoc ::raw? true)
    (some? requery-id) (assoc ::requery-id requery-id)
    (requery-form requery-id (or path []))
    (assoc ::requery-form (requery-form requery-id (or path [])))
    (some? supplied) (assoc ::continuation supplied)
    (and (nil? supplied) (requery-source requery-id))
    (assoc ::continuation (continuation
                           {::requery-id requery-id ::elision-unit elision-unit
                            :seon.render.data/path (or path [])
                            :seon.render.data/next-offset next-offset}))
    (and (nil? requery-id) (some? requery-refusal))
    (assoc ::requery-refusal requery-refusal)
    (and (nil? requery-id) (nil? requery-refusal))
    (assoc ::requery-refusal "the source has no stable requery identity")))

(schema/register-core-predicate! 'seon.print/node? node?)
(schema/register-core-predicate! 'seon.print/node-child? node-child?)
(schema/register-core-predicate! 'seon.print/print-number? print-number?)
(schema/register-core-predicate! 'seon.print/print-char? print-char?)

#?(:clj (schema.edn/load! {}))
