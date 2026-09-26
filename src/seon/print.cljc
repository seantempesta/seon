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
           (gen/fmap (fn [rows]
                       {::face ::map
                        ::entries (distinct-generated-nodes first rows)})
                     entries)
           (gen/fmap (fn [[record-name rows]]
                       {::face ::record ::name record-name
                        ::entries (distinct-generated-nodes first rows)})
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

(defn value-at
  "Read a path through maps, sets, vectors, and lists without changing keys."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.render.data/path] :seon.schema/value]}
  [value path]
  (reduce (fn [parent key]
            (if (and (sequential? parent) (integer? key) (not (neg? key)))
              (nth parent key nil)
              (get parent key)))
          value path))

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

(defn- requery-form
  [identity path]
  (let [source (cond
                 (symbol? identity) identity
                 (seq? identity) identity
                 (or (vector? identity) (int? identity))
                 (list 'seon.db/pull (list 'quote '[*])
                       (list 'quote identity)))]
    (when source
      (list 'seon.print/value-at source
            (if (some #(or (symbol? %) (coll? %)) path)
              (list 'quote path) path)))))

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
  against them. Restating a coordinate in a floored estimate would leave the
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
;;; the two outputs cannot disagree about a token. A `:text` op carries its
;;; CSS class and, for a terminal projection, the hiccup it stands for; a
;;; structural `:begin`/`:end` carries the node it opens.

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

(defn- leaf-ops
  [node depth]
  (let [value (::value node)]
    (case (::face node)
      ::nil [(text-op ::nil "nil")]
      (::boolean ::number ::keyword ::symbol ::char ::string)
      [(text-op (::face node) (literal value))]
      (::inst ::uuid) [(text-op ::tag (literal value))]
      ::var [(text-op ::symbol (str "#'" (::name node)))]
      (::type ::class) [(text-op ::symbol (::name node))]
      ::object [(text-op ::object
                         (if-some [class-text (object-class-text node)]
                           (str "#object[" class-text
                                (when-some [rep (::rep node)] (str " " rep)) "]")
                           ;; An empty `#object[]` would read as content.
                           (diagnostic-text "The object print node names no class." node)))]
      ::truncated-string [(text-op ::string (literal (str value "…")))]
      ::failed [(text-op ::object (str "#object[" (::class node) " "
                                       (literal (str "projection failed: " (::message node))) "]"))]
      ::elided [(text-op ::elision (render-elision-ai node))]
      ::pruned [(text-op ::prune "#")]
      ::projected (let [output (:seon.render/output node)]
                    (cond
                      (and (pos? depth) (= :seon.render/ai output))
                      [(text-op ::string (literal value))]
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
                 (every? some? namespaces)
                 (apply = namespaces))
        (first namespaces)))))

(defn- unqualified-key-node
  [node]
  (update node ::value (if (= ::keyword (::face node))
                         #(keyword (name %))
                         #(symbol (name %)))))

(declare flat-text)

(def ^:private valued-kinds
  #{::nil ::boolean ::number ::keyword ::symbol ::char ::string ::inst ::uuid})

(defn- node-order
  "Canonical order of two child nodes: nodes carrying a scalar value in
  `compare-values` order (the order the value walk selects by), then every
  other node by its flat text."
  [left right]
  (let [left-value? (contains? valued-kinds (::face left))
        right-value? (contains? valued-kinds (::face right))]
    (cond
      ;; `compare-values`'s own scalar order, without an armed call per pair:
      ;; its category is the value's type, then `compare` within it.
      (and left-value? right-value?)
      (let [category #(let [value (::value %)] (if (nil? value) "nil" (str (type value))))]
        (compare [(category left) (::value left)] [(category right) (::value right)]))
      left-value? -1
      right-value? 1
      :else (compare (flat-text left) (flat-text right)))))

(defn- ordered-children
  "A node's children in canonical order, elisions last: map entries by key,
  unordered set members by themselves (`node-order`)."
  [node]
  (let [ordered (fn [elided? key-of children]
                  (let [{cuts true members false} (group-by elided? children)]
                    (into (vec (sort-by key-of node-order members)) cuts)))]
    (case (::face node)
      (::map ::record) (ordered (complement vector?) first (::entries node))
      ::set (if (::ordered? node)
              (::items node)
              (ordered #(= ::elided (::face %)) identity (::items node)))
      (::items node))))

(defn- summary
  [node path]
  (str (name (::face node)) " "
       (or (:seon.render.data/total node)
           (count (or (::items node) (::entries node))))
       " items, depth " (count path)))

(defn- collection-work
  "The work one collection expands to: its open group, members and close."
  [node options depth path]
  (let [children (vec (ordered-children node))
        length (::length options)
        shown (if (and length (< length (count children))) (into [] (take length) children) children)
        cut? (< (count shown) (count children))
        map-like? (contains? #{::map ::record} (::face node))
        ;; A cut among map entries is read back as an entry: it takes the
        ;; first `:seon.print/elision[-n]` key no shown entry already uses.
        shown (if map-like?
                (let [used (into #{} (keep #(when (vector? %) (::value (first %)))) shown)
                      free (remove used (map #(keyword "seon.print" (str "elision" (when (pos? %) (str "-" %))))
                                             (range)))]
                  (first (reduce (fn [[entries free] entry]
                                   (if (vector? entry)
                                     [(conj entries entry) free]
                                     [(conj entries [{::face ::keyword ::value (first free)} entry]) (rest free)]))
                                 [[] free] shown)))
                shown)
        lifted (when (and (= ::map (::face node)) (::namespace-maps? options))
                 (same-key-namespace shown))
        shown (if lifted (mapv #(if (vector? %) (update % 0 unqualified-key-node) %) shown) shown)
        open (case (::face node)
               ::vector "[" ::list "(" ::set "#{"
               ::map (str (when lifted (str "#:" lifted)) "{")
               ::record (str "#" (::name node) "{"))
        close (case (::face node) ::vector "]" ::list ")" "}")
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

(defn- flat-text
  [node]
  (case (::face node)
    (::boolean ::number ::keyword ::symbol ::char ::string) (literal (::value node))
    (ops-text (node-ops node generated-item-options) 0)))

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

(defn- requery-fields
  [profile]
  (if-some [identity (::requery-id profile)]
    {::requery-id identity}
    {::requery-refusal
     (or (::requery-refusal profile)
         "the source has no stable requery identity")}))

(defn elision
  "One complete elision node: what was omitted, where, and how to ask again.

  Refitting and value projection use this constructor, so count, path and
  requery identity travel with every cut. The AI emitter prints these as
  ordinary EDN. Missing coordinates remain absent. A node without a requery
  identity retains the reason internally and prints no invented requery."
  {:malli/schema [:=> [:cat :seon.print/elision-request] :seon.print/node]}
  [{::keys [requery-id requery-refusal omitted elision-unit prefix bound-by]
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
    (some? requery-id) (assoc ::requery-id requery-id)
    (requery-form requery-id (or path []))
    (assoc ::requery-form (requery-form requery-id (or path [])))
    (and (nil? requery-id) (some? requery-refusal))
    (assoc ::requery-refusal requery-refusal)
    (and (nil? requery-id) (nil? requery-refusal))
    (assoc ::requery-refusal "the source has no stable requery identity")))

(defn- elision-node
  ;; ABSENT MEANS NO KEY, here most of all: every field of
  ;; `:seon.print/elision-request` is optional, and an optional key present
  ;; as nil is a contract violation — which is what turned every live
  ;; presentation cut into a refusal instead of an elision the moment `fit`
  ;; reached this constructor.
  ;;
  ;; THE STRIP IS FOR WHAT IS GENUINELY OPTIONAL — `prefix`, `bound-by`, an
  ;; unknown `total`. IT IS NOT FOR THE REQUERY COORDINATES. A cut that lost
  ;; its path and offset reads as an ordinary cut, and the reader asking
  ;; "what was omitted, and how do I see it" gets silence: absence reported
  ;; as health, one constructor down. A missing path is a defect AT THE
  ;; CALLER, so this refuses instead of quietly producing a coordinate-less
  ;; elision. Every call site in this namespace supplies both, so the
  ;; refusal is unreachable from ordinary rendering by construction.
  {:malli/schema [:=> [:cat :map :seon.render.data/path :int :int [:or :nil :int] :seon.print/elision-unit [:or :nil :string]] :seon.print/node]}
  [profile path next-offset omitted total unit prefix]
  (when-not (and (vector? path) (int? next-offset))
    (throw
     (ex-info
      (str "An elision must carry its requery coordinates: "
           ":seon.render.data/path " (literal path)
           " and :seon.render.data/next-offset " (literal next-offset) ".")
      {::elision-without-requery-coordinates true
       :seon.render.data/path path
       :seon.render.data/next-offset next-offset
       ::elision-unit unit})))
  (elision
   (into (requery-fields profile)
         (remove (comp nil? val))
         {::omitted omitted
          ::elision-unit unit
          ::prefix prefix
          ::bound-by (::bound-by profile)
          :seon.render.data/total total
          :seon.render.profile/id (:seon.render.profile/id profile)
          ;; guaranteed present by the refusal above, so the strip cannot
          ;; reach them
          :seon.render.data/path path
          :seon.render.data/next-offset next-offset})))

(defn- preserve-requery
  [cut carried]
  (cond
    (::requery-id carried)
    (-> cut
        (dissoc ::requery-refusal)
        (assoc ::requery-id (::requery-id carried)
               ::requery-form (requery-form (::requery-id carried)
                                            (:seon.render.data/path cut []))))

    (::requery-refusal carried)
    (-> cut
        (dissoc ::requery-id ::requery-form)
        (assoc ::requery-refusal (::requery-refusal carried)))

    :else cut))

(declare fit-node)

(defn- fit-entry
  [entry profile depth path child-limit string-limit]
  (if (vector? entry)
    (let [[key-node value-node] entry
          value-path (or (:seon.render.data/path value-node)
                         (try
                           (conj (pop path)
                                 (edn/read-string (emit-text key-node generated-item-options)))
                           (catch #?(:clj Throwable :cljs :default) _ (pop path))))]
      [key-node (fit-node value-node profile depth value-path
                          child-limit string-limit)])
    entry))

(defn- fit-children
  [children profile depth path child-limit string-limit child-fit]
  (let [children (vec children)
        carried-elision (when (and (= ::elided (::face (peek children)))
                                  (= :children (::elision-unit (peek children)))
                                  (= path (:seon.render.data/path (peek children))))
                          (peek children))
        children (if carried-elision (pop children) children)
        admitted-total (count children)
        total (or (when (empty? path)
                    (:seon.render.data/total profile))
                  (:seon.render.data/total carried-elision)
                  (when-some [omitted (::omitted carried-elision)]
                    (+ admitted-total omitted))
                  admitted-total)
        limit (min child-limit admitted-total)
        ;; Keys are coordinates, so a key cannot be replaced by a different
        ;; value. If a key needs elision, omit the whole remaining entries.
        key-cut (when (= child-fit fit-entry)
                  (first
                   (keep-indexed
                    (fn [index entry]
                      (when (vector? entry)
                        (when-let [cut
                                   (some #(when (= ::elided (::face %)) %)
                                         (tree-seq coll? seq
                                                   (fit-node (first entry) profile
                                                             (inc depth) path
                                                             child-limit string-limit)))]
                          [index (::bound-by cut)])))
                    (subvec children 0 limit))))
        retained (or (first key-cut) limit)
        fitted-elision
        (when (< retained total)
          (preserve-requery
           (elision-node (assoc profile
                                ::bound-by (or (second key-cut)
                                               (when (= retained admitted-total)
                                                 (::bound-by carried-elision))
                                               (::bound-by profile)
                                               :seon.render.profile/max-children))
                         path retained (- total retained) total
                         :children nil)
           carried-elision))]
    (cond->
     (mapv (fn [index child]
             (child-fit child profile (inc depth) (conj path index)
                        child-limit string-limit))
           (range retained)
           (subvec children 0 retained))
      fitted-elision
      (conj fitted-elision))))

(defn- projected-text
  [node]
  (let [value (::value node)]
    (if (string? value) value (literal value))))

(defn- fit-text
  "Clip one over-long string to the characters its profile admits.

  A CUT NEVER OMITS ITS WHOLE SUBJECT. Emitting an elision whose omitted
  count equalled the total was the silent case wearing a count: the reader
  learned the size of what it was not shown and nothing else. The characters
  that fit ride the cut as `::prefix`, the offset names how many were shown,
  and the count names only the remainder — so the reader sees content, knows
  exactly what is missing, and can continue from a real coordinate."
  [node profile path string-limit]
  (let [value (if (= ::projected (::face node))
                (projected-text node)
                (::value node))
        original (long (or (::length node) (count value)))
        kept (max 0 (min (count value) (long string-limit)))]
    (if (or (> original string-limit) (< (count value) original))
      (elision-node (assoc profile
                           ::bound-by
                           (or (::bound-by node)
                               (::bound-by profile)
                               (when (:seon.render.profile/max-string-length profile)
                                 :seon.render.profile/max-string-length)
                               :seon.render.profile/token-budget))
                    path kept (- original kept) original :characters
                    (when (pos? kept) (subs value 0 kept)))
      node)))

(defn- structural-elision
  [node profile path]
  (let [children (vec (or (::items node) (::entries node) []))
        carried (when (and (= ::elided (::face (peek children)))
                           (= :children (::elision-unit (peek children)))
                           (= path (:seon.render.data/path (peek children))))
                  (peek children))
        admitted (if carried (dec (count children)) (count children))
        total (or (:seon.render.data/total carried)
                  (when-some [omitted (::omitted carried)]
                    (+ admitted omitted))
                  admitted)]
    (preserve-requery
     (elision-node (assoc profile ::bound-by (or (::bound-by profile)
                                                :seon.render.profile/max-depth))
                   path 0 (max 1 total) total :subtree nil)
     carried)))

(defn- fit-node
  [node profile depth path child-limit string-limit]
  (let [node (case (::face node)
               (::map ::record) (assoc node ::entries (ordered-children node))
               ::set (assoc node ::items (vec (ordered-children node)))
               node)
        face (::face node)
        path (or (:seon.render.data/path node) path)]
    (cond
      (and (>= depth (:seon.render.profile/max-depth profile))
           (contains? structural-kinds face)
           (seq (or (::items node) (::entries node))))
      (structural-elision node profile path)

      :else
      (case face
        (::vector ::list ::set)
        (assoc node ::items
               (fit-children (::items node) profile depth path child-limit
                             string-limit fit-node))

        (::map ::record)
        (assoc node ::entries
               (fit-children (::entries node) profile depth path child-limit
                             string-limit fit-entry))

        ::throwable
        (update node ::value fit-node profile (inc depth)
                (conj path ::throwable) child-limit string-limit)

        ::projected
        (if (= :seon.render/ai (:seon.render/output node))
          (fit-text node (assoc profile ::bound-by ::rendered-token-ceiling)
                    path (::rendered-chars profile))
          (fit-text node profile path string-limit))

        (::string ::truncated-string)
        (fit-text node profile path string-limit)

        node))))

(defn- rendered-text-chars
  "Characters of declared AI renderer text in a node tree: the text the raw
  budget does not pay for."
  [node]
  (loop [pending [node] chars 0]
    (if-some [current (peek pending)]
      (recur (into (pop pending) (child-nodes current))
             (cond-> chars
               (and (= ::projected (::face current))
                    (= :seon.render/ai (:seon.render/output current)))
               (+ (count (projected-text current)))))
      chars)))

(defn fit
  "Fit one admitted node to one declared presentation profile.

  THIS IS THE AI CONTEXT GENERATION BOUNDARY'S ONE ELISION, and the only
  place presentation size cuts a value at all (owner ruling, 2026-09-07).
  HTML is not bounded; the AI projection is bounded HERE, by the render profile the request
  carried. A caller that is not generating AI context does not call this —
  a request with no presentation decision must not make one.

  THE BUDGET IS CHOSEN BY PROVENANCE (owner 2026-09-26). Raw values spend
  the profile's token budget, children and depth. Text a declared AI render
  function produced is not charged to that budget and is never halved: it is
  cut only beyond `:seon.print/rendered-token-ceiling`.

  Token size is measured only through `seon.ai.tokens/estimate`. Structural
  cuts remain ordinary elision nodes carrying their count, path and requery
  identity, so a cut names its own source and can be asked again.

  THE SEARCH HAS A FLOOR AND THE FLOOR IS STRUCTURAL, NOT NUMERIC: one child,
  one level, one character. Driving any limit to zero produced a projection
  that showed NOTHING and reported a count — absence read as a bound, the
  failure class AGENTS.md §2.4 names. Below the floor the candidate may
  exceed the budget; that is the honest answer, and the cut it carries says
  which bound it hit."
  {:malli/schema
   [:function
    [:=> [:cat :seon.print/node :seon.render.profile/profile] :seon.print/node]
    [:=> [:cat :seon.print/node :seon.render.profile/profile :seon.print/options]
     :seon.print/node]]}
  ([node profile] (fit node profile (default-options)))
  ([node profile options]
  (let [budget (:seon.render.profile/token-budget profile)
        initial-children (:seon.render.profile/max-children profile)
        initial-depth (:seon.render.profile/max-depth profile)
        ;; Measured on the text the caller will save: the same width and cuts.
        options (assoc (effective-options options) ::length nil ::level nil)
        ;; Chosen at entry by provenance: renderer text is bounded only by
        ;; the ceiling and is not charged to the raw value's budget.
        profile (assoc profile ::rendered-chars
                       (tokens/estimate-chars (::rendered-token-ceiling options)))
        raw-tokens (fn [candidate]
                     (tokens/estimate-of-characters
                      (max 0 (- (count (emit-text candidate options))
                                (rendered-text-chars candidate)))))
        initial-strings (or (:seon.render.profile/max-string-length profile)
                            (tokens/estimate-chars budget))]
    (loop [child-limit initial-children
           depth-limit initial-depth
           string-limit initial-strings]
      (let [candidate (fit-node node
                                (assoc profile
                                       :seon.render.profile/max-depth
                                       depth-limit
                                       ::bound-by (when (or (< child-limit initial-children)
                                                           (< depth-limit initial-depth)
                                                           (< string-limit initial-strings))
                                                    :seon.render.profile/token-budget))
                                0 [] child-limit string-limit)]
        (cond
          (<= (raw-tokens candidate) budget)
          candidate

          ;; BREADTH AND TEXT DEGRADE TOGETHER. Spending one dimension to its
          ;; floor before touching the other destroys that dimension to save
          ;; the one that caused the overflow, and both orders were measured
          ;; doing exactly that: shrinking members first deleted
          ;; `:seon.effect/result-edn` outright to pay for its own length
          ;; (`seon.render.value-test/background-poll-keeps-identity-while-payloads-grow`),
          ;; and shrinking text first clipped a 36-character one-liner to
          ;; nothing to pay for 116 siblings
          ;; (`fit-preserves-breadth-and-long-strings`). Halving both at once
          ;; is symmetric, keeps every member visible for as long as the text
          ;; inside it, and converges in the logarithm of the budget instead
          ;; of one child at a time. Depth is last because it costs the most:
          ;; a cut level takes a whole subtree with it.
          (or (> string-limit 1) (> child-limit 1))
          (recur (max 1 (quot child-limit 2)) depth-limit
                 (max 1 (quot string-limit 2)))

          (> depth-limit 1)
          (recur child-limit (dec depth-limit) string-limit)

          :else candidate))))))

(schema/register-core-predicate! 'seon.print/node? node?)
(schema/register-core-predicate! 'seon.print/node-child? node-child?)
(schema/register-core-predicate! 'seon.print/print-number? print-number?)
(schema/register-core-predicate! 'seon.print/print-char? print-char?)

#?(:clj (schema.edn/load! {}))
