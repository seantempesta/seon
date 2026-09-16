(ns seon.print
  "Emits admitted print nodes through text and hiccup sinks."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [seon.ai.tokens :as tokens]
            [seon.schema :as schema]
            #?(:clj [seon.schema.edn :as schema.edn])
            [seon.schema.form :as schema.form]))

(defn- literal
  [value]
  (binding [*print-readably* true *print-dup* false
            *print-length* nil *print-level* nil *print-meta* false]
    (pr-str value)))

(defprotocol Sink
  (-open [sink node] "Enter one structural node.")
  (-token [sink face text] "Emit one lexical token.")
  (-fragment [sink output value] "Emit one terminal producer projection.")
  (-close [sink node] "Leave one structural node."))

(defn sink?
  "True when a value consumes the admitted emitter event stream."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (satisfies? Sink value))

(defn print-number?
  "True for a number with a stock Clojure print face."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (number? value))

(def number-generator
  "Numbers spanning the stock printer's distinct suffix and special faces."
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
  "True for a character with a stock Clojure print face."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (char? value))

(def char-generator
  "Characters spanning named and escaped stock print faces."
  (gen/elements [\a \space \newline \tab \return \backspace \formfeed]))

(declare text-sink)

(def sink-generator
  "A concrete text sink for opaque sink-schema generation."
  (gen/fmap (fn [_] (text-sink {})) (gen/return nil)))

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
  "Whole print-node trees spanning every declared face.

  `:seon.print/node` is validated by a predicate rather than a recursive
  Malli ref (see `node?`), so the domain declares its own generator instead
  of inheriting one from a recursive reference. Every face the emitter is
  total over appears here, and containers recur under test.check's own
  depth bound."
  (let [named (fn [face]
                (gen/fmap (fn [name] {::face face ::name name})
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

(defn- append-chunk!
  [state text]
  (let [text (str text)
        last-newline (str/last-index-of text "\n")]
    (vswap! state
            (fn [current]
              (-> current
                  (update ::chunks conj text)
                  (assoc ::column
                         (if last-newline
                           (- (count text) last-newline 1)
                           (+ (::column current) (count text))))))))
  nil)

(defn- soft-separator
  [state width text]
  (if (and (pos? width) (>= (::column @state) width))
    (let [punctuation (str/trimr text)
          indent (apply str (repeat (* 2 (::depth @state)) " "))]
      (str punctuation "\n" indent))
    text))

(deftype ^:private TextSink [state options]
  Sink
  (-open [_ node]
    (append-chunk! state (::begin node))
    (vswap! state update ::depth inc))
  (-token [_ face text]
    (append-chunk! state
                   (if (= ::separator face)
                     (soft-separator state (::width options) text)
                     text)))
  (-fragment [_ output value]
    (append-chunk! state
                   (if (= :seon.render/ai output)
                     value
                     (literal value))))
  (-close [_ node]
    (vswap! state update ::depth dec)
    (append-chunk! state (::end node))))

(defn text-sink
  "Create a text sink using explicit print options."
  {:malli/schema [:=> [:cat :seon.print/options] :seon.print/sink]}
  [options]
  (TextSink. (volatile! {::chunks [] ::column 0 ::depth 0}) options))

(defn- face-class
  [face]
  (str "seon-print-" (name face)))

(defn- hiccup-token
  [face text]
  [:span {:class (face-class face)} text])

(defn- append-hiccup!
  [state child]
  (vswap! state
          (fn [{::keys [stack] :as current}]
            (if (seq stack)
              (update-in current [::stack (dec (count stack)) ::children]
                         conj child)
              (update current ::roots conj child))))
  nil)

(defn- close-frame
  [{::keys [node children]}]
  (if-let [{::keys [columns rows]} (::table node)]
    [:div
     {:class "seon-print-node seon-print-table"
      :data-seon-path (literal (::path node))}
     (into [:span {:class "seon-print-content" :hidden "hidden"}] children)
     [:table {:class "seon-print-visual"}
      [:thead
       (into [:tr] (map (fn [column] [:th column])) columns)]
      (into [:tbody]
            (map (fn [row]
                   (into [:tr] (map (fn [cell] [:td cell])) row)))
            rows)]]
    [:details
     {:class (str "seon-print-node " (face-class (::kind node)))
      :data-seon-path (literal (::path node))}
     [:summary {:class "seon-print-summary"}
      (::summary node)]
     (into [:span {:class "seon-print-content"}] children)]))

(deftype ^:private HiccupSink [state]
  Sink
  (-open [_ node]
    (vswap! state update ::stack conj
            {::node node
             ::children [(hiccup-token ::delimiter (::begin node))]}))
  (-token [_ face text]
    (append-hiccup! state (hiccup-token face text)))
  (-fragment [_ output value]
    (append-hiccup! state
                    (if (= :seon.render/html output)
                      value
                      (hiccup-token ::projected value))))
  (-close [_ node]
    (append-hiccup! state (hiccup-token ::delimiter (::end node)))
    (let [frame (peek (::stack @state))]
      (vswap! state update ::stack pop)
      (append-hiccup! state (close-frame frame)))))

(defn hiccup-sink
  "Create a structural hiccup sink."
  {:malli/schema [:=> [:cat] :seon.print/sink]}
  []
  (HiccupSink. (volatile! {::stack [] ::roots []})))

(deftype ^:private TeeSink [left right]
  Sink
  (-open [_ node]
    (-open left node)
    (-open right node))
  (-token [_ face text]
    (-token left face text)
    (-token right face text))
  (-fragment [_ output value]
    (-fragment left output value)
    (-fragment right output value))
  (-close [_ node]
    (-close left node)
    (-close right node)))

(defn tee-sink
  "Create one sink that forwards every event to two sinks."
  {:malli/schema
   [:=> [:cat :seon.print/sink :seon.print/sink] :seon.print/sink]}
  [left right]
  (TeeSink. left right))

(defn- sink-result
  [sink]
  (cond
    (instance? TextSink sink)
    (apply str (::chunks @(.-state ^TextSink sink)))

    (instance? HiccupSink sink)
    (let [roots (::roots @(.-state ^HiccupSink sink))]
      (case (count roots)
        0 [:span {:class "seon-print-empty"} ""]
        1 (first roots)
        (into [:span {:class "seon-print-root"}] roots)))

    :else nil))

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
                       (schema.form/attr-form-properties
                        (get forms attribute))]
                   (when (contains? properties ::default)
                     [attribute (::default properties)])))))
            (get forms ::options)))))

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

(defn- structural-cut?
  [options depth]
  (let [level (::level options)]
    (and (some? level) (>= depth level))))

(defn- visible-items
  [items options]
  (let [length (::length options)]
    (if (or (nil? length) (<= (count items) length))
      [items false]
      [(subvec (vec items) 0 length) true])))

(defn- node-description
  [node path begin end separator _summary]
  {::kind (::face node)
   ::path path
   ::begin begin
   ::end end
   ::separator separator
   ::summary (str (name (::face node)) " "
                  (or (:seon.render.data/total node)
                      (count (or (::items node) (::entries node))))
                  " items, depth " (count path))})

(declare emit-node emit-text)

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

  SIZES SHOWN TO AN AGENT ARE ESTIMATED TOKENS (AGENTS.md §2.4). A character
  cut's stored counts stay characters — that is the storage projection — and
  this AI text reports them through `seon.ai.tokens/estimate-of-characters`,
  saying `:tokens` so the reader knows which unit it is reading. Child and
  subtree cuts already count members, which is their own honest unit.

  A cut with no requery identity says so: `::requery-refusal` is the typed
  unknown §2.4 requires, not silence."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [shown (select-keys unit
                           [::omitted ::elision-unit ::bound-by ::prefix
                            :seon.render.data/path :seon.render.data/next-offset
                            :seon.render.data/total ::requery-form
                            ::requery-refusal])
        size (fn [characters]
               (when (int? characters)
                 (max 1 (tokens/estimate-of-characters characters))))
        offset (fn [characters]
                 (when (int? characters)
                   (tokens/estimate-of-characters characters)))]
    (literal
     (into (sorted-map)
           (cond-> shown
             (= :characters (::elision-unit unit))
             (into (into {::elision-unit :tokens}
                         (remove (comp nil? val))
                         {::omitted (size (::omitted shown))
                          :seon.render.data/total
                          (size (:seon.render.data/total shown))
                          :seon.render.data/next-offset
                          (offset (:seon.render.data/next-offset shown))})))))))

(def ^:private scalar-faces
  #{::nil ::boolean ::number ::keyword ::symbol ::char ::string
    ::inst ::uuid ::var ::type ::class ::object ::truncated-string
    ::failed ::elided ::pruned})

(defn- table-row
  [node]
  (when (and (= ::map (::face node))
             (every? vector? (::entries node)))
    (into {} (::entries node))))

(defn- table-data
  [node options]
  (let [choice (::table? options)
        row-maps (mapv table-row (::items node))
        maps? (every? some? row-maps)
        columns (when maps? (vec (sort-by #(emit-text % generated-item-options)
                                         (map first (::entries (first (::items node)))))))
        same-columns? (and maps?
                           (every? #(= (set columns) (set (keys %))) row-maps))
        scalar-values? (and maps?
                            (every? #(every? (comp scalar-faces ::face val) %)
                                    row-maps))
        eligible? (case choice
                    false false
                    true (and (seq row-maps) maps? scalar-values?)
                    :derived (and (<= 2 (count row-maps))
                                  same-columns? scalar-values?)
                    false)]
    (when eligible?
      (let [render #(emit-text % (assoc options ::width 0 ::table? false))]
        {::columns (mapv render columns)
         ::rows (mapv (fn [row]
                        (mapv #(render (get row % {::face ::nil ::value nil}))
                              columns))
                      row-maps)}))))

(defn- pad-left
  [width text]
  (str (apply str (repeat (- width (count text)) " ")) text))

(defn- table-text
  [{::keys [columns rows]}]
  (let [widths (mapv (fn [index]
                       (apply max
                              (count (nth columns index))
                              (map #(count (nth % index)) rows)))
                     (range (count columns)))
        line (fn [cells]
               (str "| "
                    (str/join " | "
                              (mapv pad-left widths cells))
                    " |\n"))]
    (str "\n"
         (line columns)
         "|" (str/join "+" (map #(apply str (repeat (+ % 2) "-")) widths))
         "|\n"
         (apply str (map line rows)))))

(defn- emit-table
  [node table sink path]
  (let [descriptor (assoc (node-description node path "" "" "" "table")
                          ::kind ::table
                          ::table table)]
    (-open sink descriptor)
    (-token sink ::table (table-text table))
    (-close sink descriptor)))

(defn- emit-separated
  [children sink options depth path separator child-emitter]
  (doseq [[index child] (map-indexed vector children)]
    (when (pos? index)
      (-token sink ::separator separator))
    (child-emitter child sink options (inc depth) (conj path index))))

(defn- emit-sequential
  [node sink options depth path begin end separator]
  (if (structural-cut? options depth)
    (-token sink ::prune "#")
    (let [items (::items node)
          [visible cut?] (visible-items items options)
          descriptor (node-description node path begin end separator
                                       (str begin end " " (count items) " items"))]
      (-open sink descriptor)
      (emit-separated visible sink options depth path separator emit-node)
      (when cut?
        (when (seq visible) (-token sink ::separator separator))
        (-token sink ::elision "..."))
      (-close sink descriptor))))

(defn- same-key-namespace
  [entries]
  (when (seq entries)
    (let [key-nodes (map first entries)
          values (map ::value key-nodes)
          namespaces (map namespace values)]
      (when (and (every? #(contains? #{::keyword ::symbol} (::face %))
                         key-nodes)
                 (every? some? namespaces)
                 (apply = namespaces))
        (first namespaces)))))

(defn- unqualified-key-node
  [node]
  (let [value (::value node)]
    (assoc node ::value
           (if (= ::keyword (::face node))
             (keyword (name value))
             (symbol (name value))))))

(defn- emit-entry
  [entry sink options depth path]
  (if (= ::elided (::face entry))
    ;; An elision is ordinary data with count, path, profile, and requery
    ;; evidence. Emitting a bare marker here discarded every one of those
    ;; facts specifically when a map was cut.
    (emit-node entry sink options depth path)
    (let [[key-node value-node] entry]
      (emit-node key-node sink options (inc depth) (conj path 0))
      (-token sink ::separator " ")
      (emit-node value-node sink options (inc depth) (conj path 1)))))

(defn- emit-map-like
  [node sink options depth path begin summary]
  (if (structural-cut? options depth)
    (-token sink ::prune "#")
    (let [entries (::entries node)
          length (::length options)
          visible-count (if (nil? length) (count entries)
                            (min length (count entries)))
          visible (subvec (vec entries) 0 visible-count)
          cut? (< visible-count (count entries))
          keys-present (into #{} (keep #(when (vector? %) (::value (first %)))) visible)
          elision-key (first (remove keys-present
                                     (map #(keyword "seon.print"
                                                    (str "elision" (when (pos? %) (str "-" %))))
                                          (range))))
          visible (mapv #(if (= ::elided (::face %))
                           [{::face ::keyword ::value elision-key} %]
                           %) visible)
          data-entries (filterv vector? visible)
          lifted-ns (when (::namespace-maps? options)
                      (same-key-namespace data-entries))
          visible (if lifted-ns
                    (mapv (fn [entry]
                            (if (vector? entry)
                              (update entry 0 unqualified-key-node)
                              entry))
                          visible)
                    visible)
          begin (str (when lifted-ns (str "#:" lifted-ns)) begin)
          descriptor (node-description node path begin "}" ", " summary)]
      (-open sink descriptor)
      (emit-separated visible sink options depth path ", " emit-entry)
      (when cut?
        (when (seq visible) (-token sink ::separator ", "))
        (-token sink ::elision "..."))
      (-close sink descriptor))))

(defmulti ^:private emit
  (fn [node _sink _options _depth _path]
    (::face node)))

(defmethod emit ::nil
  [_ sink _ _ _]
  (-token sink ::nil "nil"))

(doseq [[face token-face]
        [[::boolean ::boolean]
         [::number ::number]
         [::keyword ::keyword]
         [::symbol ::symbol]
         [::char ::char]
         [::string ::string]
         [::inst ::tag]
         [::uuid ::tag]]]
  (defmethod emit face
    [node sink _ _ _]
    (-token sink token-face (literal (::value node)))))

(defmethod emit ::vector
  [node sink options depth path]
  (emit-sequential node sink options depth path "[" "]" " "))

(defmethod emit ::list
  [node sink options depth path]
  (emit-sequential node sink options depth path "(" ")" " "))

(defmethod emit ::set
  [node sink options depth path]
  (if (structural-cut? options depth)
    (-token sink ::prune "#")
    (let [items (::items node)
          [visible cut?] (visible-items items options)
          descriptor (node-description node path "#{" "}" " "
                                       (str "#{} " (count items) " members"))]
      (-open sink descriptor)
      (emit-separated visible sink options depth path " " emit-node)
      (when cut?
        (when (seq visible) (-token sink ::separator " "))
        (-token sink ::elision "..."))
      (-close sink descriptor))))

(defmethod emit ::map
  [node sink options depth path]
  (emit-map-like node sink options depth path "{"
                 (str "{} " (count (::entries node)) " keys")))

(defmethod emit ::record
  [node sink options depth path]
  (emit-map-like node sink options depth path
                 (str "#" (::name node) "{")
                 (str "#" (::name node))))

(defmethod emit ::var
  [node sink _ _ _]
  (-token sink ::symbol (str "#'" (::name node))))

(defmethod emit ::type
  [node sink _ _ _]
  (-token sink ::symbol (::name node)))

(defmethod emit ::class
  [node sink _ _ _]
  (-token sink ::symbol (::name node)))

(defn- object-class-text
  "The class an object node names, under either key it may carry.

  Admission mints `:seon.print/class`; nodes stored before that spelling — and
  the other named faces this grammar shares brackets with — carry the same
  string under `:seon.print/name`. Reading both is what stops a node that
  KNOWS its class from printing as `#object[]`."
  [node]
  (let [named (or (::class node) (::name node))]
    (when (and (string? named) (not (str/blank? named)))
      named)))

(defmethod emit ::object
  [node sink _ _ _]
  (-token sink ::object
          (if-some [class-text (object-class-text node)]
            (str "#object[" class-text
                 (when-some [rep (::rep node)] (str " " rep)) "]")
            ;; AN EMPTY `#object[]` IS THE ABSENCE-READS-AS-CONTENT CLASS: the
            ;; reader cannot tell an unrenderable value from an empty one. A
            ;; node that names no class says so, in the same flat diagnostic
            ;; shape the unknown face uses.
            (literal
             {:seon.error/kind ::object-without-class
              :seon.error/message "The object print node names no class."
              :seon.error/data
              {::face (::face node)
               ::node-keys (vec (sort-by str (keys node)))}}))))

(defmethod emit ::truncated-string
  [node sink _ _ _]
  (-token sink ::string (literal (str (::value node) "…"))))

(defmethod emit ::failed
  [node sink _ _ _]
  (-token sink ::object
          (str "#object[" (::class node) " "
               (literal (str "projection failed: " (::message node))) "]")))

(defmethod emit ::throwable
  [node sink options depth path]
  (if (structural-cut? options depth)
    (-token sink ::prune "#")
    (do
      (-token sink ::tag "#error ")
      (emit-node (::value node) sink options depth (conj path ::throwable)))))

(defmethod emit ::elided
  [node sink _ _ _]
  (-token sink ::elision (render-elision-ai node)))

(defmethod emit ::projected
  [node sink _ depth _]
  (if (and (pos? depth) (= :seon.render/ai (:seon.render/output node)))
    (-token sink ::string (literal (::value node)))
    (-fragment sink (:seon.render/output node) (::value node))))

(defmethod emit ::pruned
  [_ sink _ _ _]
  (-token sink ::prune "#"))

(defmethod emit :default
  [node sink _ _ _]
  ;; The emitter is the terminal outward boundary and therefore cannot turn a
  ;; diagnostic value into a second exception. An undeclared or absent face is
  ;; rendered as the same flat error value in both sinks, naming the observed
  ;; face and node keys. Admission should make this branch rare; totality makes
  ;; it safe and diagnosable when an old artifact or host caller reaches it.
  (-token sink ::object
          (literal
           {:seon.error/kind ::unknown-face
            :seon.error/message "The admitted value has no declared print face."
            :seon.error/data
            {::face (::face node)
             ::node-keys (vec (sort-by str (keys node)))} :seon.print/unknown-face (:?_current-ns_?/face node)})))

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

(defn- emit-node
  [node sink options depth path]
  (let [order-key #(emit-text % generated-item-options)
        node (case (::face node)
               (::map ::record)
               (update node ::entries
                       #(vec (sort-by (fn [entry]
                                        (if (vector? entry)
                                          [0 (order-key (first entry))] [1 ""])) %)))
               ::set
               (if (::ordered? node) node
                 (update node ::items
                       #(vec (sort-by (fn [item]
                                        (if (= ::elided (::face item))
                                          [1 ""] [0 (order-key item)])) %))))
               node)]
   (if (and (contains? #{::vector ::list} (::face node))
           (zero? depth)
           (not (structural-cut? options depth)))
    (if-let [table (table-data node options)]
      (emit-table node table sink path)
      (emit node sink options depth path))
    (emit node sink options depth path)))
  sink)

(defn emit-text
  "Emit one admitted node as REPL-faithful text."
  {:malli/schema
   [:=> [:cat :seon.print/node :seon.print/options] :string]}
  [node options]
  (let [options (effective-options options)
        sink (text-sink options)]
    (emit-node node sink options 0 [])
    (sink-result sink)))

(defn emit-hiccup
  "Emit one admitted node as structural hiccup."
  {:malli/schema
   [:=> [:cat :seon.print/node :seon.print/options] :seon.render/hiccup]}
  [node options]
  (let [options (effective-options options)
        sink (hiccup-sink)]
    (emit-node node sink options 0 [])
    (sink-result sink)))

(defn emit-both
  "Emit text and hiccup from one traversal through a tee sink."
  {:malli/schema
   [:=> [:cat :seon.print/node :seon.print/options] :seon.print/result]}
  [node options]
  (let [options (effective-options options)
        text (text-sink options)
        hiccup (hiccup-sink)
        sink (tee-sink text hiccup)]
    (emit-node node sink options 0 [])
    {::text (sink-result text)
     ::hiccup (sink-result hiccup)}))

(def ^:private structural-faces
  #{::vector ::list ::set ::map ::record ::throwable})

(defn node-child?
  "True when `value` occupies one print node's child slot."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (map? value) (keyword? (::face value))))

(def ^:private node-face-validator*
  ;; THE FACE TABLE IS CORE-OWNED AND SHIPPED: no cluster, agent or delta
  ;; registers a `:seon.print/node-face`, so one validator compiled from the
  ;; packaged declaration population is the same answer every projection
  ;; would give — and compiling it per call (or reading the declaration
  ;; population per call, which is what asking the ambient registry costs)
  ;; is the measured performance trap this project already named.
  (delay
    (schema/projection-validator
     (schema/declaration-projection
      #?(:clj (schema.edn/packaged-forms) :cljs {}))
     ::node-face)))

(defn- node-face-validator
  []
  @node-face-validator*)

(defn- node-children
  [node]
  (case (::face node)
    (::vector ::list ::set) (::items node)
    (::map ::record) (into []
                           (mapcat (fn [entry]
                                     (if (vector? entry) entry [entry])))
                           (::entries node))
    ::throwable [(::value node)]
    nil))

(defn node?
  "True when `value` is a print node, one node at a time.

  ITERATIVE BY CONSTRUCTION. A recursive Malli `:ref` validates a node by
  recursing once per level: at depth 3,510 the contract on an admitted
  value answered `java.lang.StackOverflowError` — an `Error` escaping a
  total operation at exactly the boundary law 2.4 requires a flat value
  from, and the walk that produced the node is iterative. Each node is
  checked against the declared per-face shape (`:seon.print/node-face`)
  and its children are handed back to this loop, so the face table is
  declared once and the depth a contract admits is the depth admission
  admits."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (let [valid? (node-face-validator)]
    (loop [pending (list value)]
      (if-some [remaining (seq pending)]
        (let [node (first remaining)]
          (if (valid? node)
            (recur (into (rest remaining) (node-children node)))
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
  [profile path next-offset omitted total unit prefix]
  (when-not (and (vector? path) (int? next-offset))
    (throw
     (ex-info
      (str "An elision must carry its requery coordinates: "
           ":seon.render.data/path " (literal path)
           " and :seon.render.data/next-offset " (literal next-offset) ".")
      {:seon.error/kind ::elision-without-requery-coordinates
       ::elision-without-requery-coordinates true
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

(declare enrich-node)

(defn- enrich-entry
  [entry profile path]
  (if (vector? entry)
    (mapv (fn [index child]
            (enrich-node child profile (conj path index)))
          (range)
          entry)
    (elision-node profile path 0 1 nil :subtree nil)))

(defn- enrich-node
  [node profile path]
  (case (::face node)
    (::vector ::list ::set)
    (assoc node ::items
           (mapv (fn [index child]
                   (if (= ::elided (::face child))
                     (let [total (when (empty? path)
                                   (:seon.render.data/total profile))]
                       (elision-node profile path index
                                     (if total (- total index) 1)
                                     total :children nil))
                     (enrich-node child profile (conj path index))))
                 (range)
                 (::items node)))

    (::map ::record)
    (assoc node ::entries
           (mapv (fn [index entry]
                   (if (= ::elided (::face entry))
                     (let [total (when (empty? path)
                                   (:seon.render.data/total profile))]
                       (elision-node profile path index
                                     (if total (- total index) 1)
                                     total :children nil))
                     (enrich-entry entry profile (conj path index))))
                 (range)
                 (::entries node)))

    ::throwable
    (update node ::value enrich-node profile (conj path ::throwable))

    ::truncated-string
    ;; Admission already kept the characters that fit; handing them on as the
    ;; cut's prefix is the difference between "here is the start and what is
    ;; missing" and a bare count of a value the reader never saw.
    (let [kept (count (::value node))]
      (elision-node (assoc profile ::bound-by (::bound-by node))
                    path kept (max 1 (- (long (::length node)) kept))
                    (::length node) :characters
                    (when (pos? kept) (::value node))))

    ::elided
    (if (::omitted node)
      node
      (elision-node profile path 0 1 nil :subtree nil))

    node))

(defn enrich-elisions
  "Replace every admission marker with a declared structural elision value."
  {:malli/schema
   [:=> [:cat :seon.print/node :seon.render.profile/profile]
    :seon.print/node]}
  [node profile]
  (enrich-node node profile []))

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
               (::map ::record)
               (update node ::entries
                       #(vec (sort-by (fn [entry]
                                        (if (vector? entry)
                                          [0 (emit-text (first entry) generated-item-options)]
                                          [1 ""])) %)))
               ::set
               (if (::ordered? node) node
                 (update node ::items
                       #(vec (sort-by (fn [item]
                                        (if (= ::elided (::face item))
                                          [1 ""] [0 (emit-text item generated-item-options)])) %))))
               node)
        face (::face node)
        path (or (:seon.render.data/path node) path)]
    (cond
      (and (>= depth (:seon.render.profile/max-depth profile))
           (contains? structural-faces face)
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

        (::string ::truncated-string ::projected)
        (fit-text node profile path string-limit)

        node))))

(defn fit
  "Fit one admitted node to one declared presentation profile.

  THIS IS THE AI CONTEXT GENERATION BOUNDARY'S ONE ELISION, and the only
  place presentation size cuts a value at all (owner ruling, 2026-09-07).
  HTML is not bounded; the AI projection is bounded HERE, by the render profile the request
  carried. A caller that is not generating AI context does not call this —
  a request with no presentation decision must not make one.

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
   [:=> [:cat :seon.print/node :seon.render.profile/profile]
    :seon.print/node]}
  [node profile]
  (let [budget (:seon.render.profile/token-budget profile)
        initial-children (:seon.render.profile/max-children profile)
        initial-depth (:seon.render.profile/max-depth profile)
        options (assoc (default-options) ::length nil ::level nil)
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
          (<= (tokens/estimate (emit-text candidate options)) budget)
          candidate

          (> child-limit 1)
          (recur (dec child-limit) depth-limit string-limit)

          (> depth-limit 1)
          (recur child-limit (dec depth-limit) string-limit)

          (> string-limit 1)
          (recur child-limit depth-limit (max 1 (quot string-limit 2)))

          :else candidate)))))

(schema/register-core-predicate! 'seon.print/sink? sink?)
(schema/register-core-predicate! 'seon.print/node? node?)
(schema/register-core-predicate! 'seon.print/node-child? node-child?)
(schema/register-core-predicate! 'seon.print/print-number? print-number?)
(schema/register-core-predicate! 'seon.print/print-char? print-char?)

#?(:clj (schema.edn/load! {}))
