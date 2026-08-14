(ns seon.print
  "Emits admitted print nodes through text and hiccup sinks."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [seon.ai.tokens :as tokens]
            [seon.schema :as schema]
            #?(:clj [seon.schema.edn :as schema.edn])
            [seon.schema.form :as schema.form]))

(defprotocol Sink
  (-open [sink node] "Enter one structural node.")
  (-token [sink face text] "Emit one lexical token.")
  (-fragment [sink output value] "Emit one terminal producer projection.")
  (-close [sink node] "Leave one structural node."))

(defn sink?
  "True when a value consumes the admitted emitter event stream."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (satisfies? Sink value))

(defn print-number?
  "True for a number with a stock Clojure print face."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
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
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
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
                     (pr-str value))))
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
      :data-seon-path (pr-str (::path node))}
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
      :data-seon-path (pr-str (::path node))}
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
  [node path begin end separator summary]
  {::kind (::face node)
   ::path path
   ::begin begin
   ::end end
   ::separator separator
   ::summary summary})

(declare emit-node emit-text)

(defn render-elision-ai
  "Render an elision as a readable, requeryable structural face."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [omitted (or (:seon.print/omitted unit) 1)
        measure (name (or (:seon.print/elision-unit unit) :subtree))
        location (str "path " (pr-str (or (:seon.render.data/path unit) []))
                      " offset " (or (:seon.render.data/next-offset unit) 0))
        requery (if-some [identity (:seon.print/requery-id unit)]
                  (str "requery by " (pr-str identity))
                  (str "requery refused: "
                       (or (:seon.print/requery-refusal unit)
                           "no stable identity was supplied")))]
    (str (or (:seon.print/prefix unit) "") "…"
         " " omitted " more " measure
         (when-some [total (:seon.render.data/total unit)]
           (str " of " total))
         (when-some [bound-by (:seon.print/bound-by unit)]
           (str "; bounded by " bound-by))
         "; " requery " at " location
         " with " (pr-str (or (:seon.render.profile/id unit)
                               :seon.render.profile/unspecified)))))

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
        columns (when maps? (mapv first (::entries (first (::items node)))))
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
    (-token sink token-face (pr-str (::value node)))))

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

(defmethod emit ::object
  [node sink _ _ _]
  (-token sink ::object
          (str "#object[" (::class node)
               (when-some [rep (::rep node)] (str " " rep)) "]")))

(defmethod emit ::truncated-string
  [node sink _ _ _]
  (-token sink ::string (pr-str (str (::value node) "…"))))

(defmethod emit ::failed
  [node sink _ _ _]
  (-token sink ::object
          (str "#object[" (::class node) " "
               (pr-str (str "projection failed: " (::message node))) "]")))

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
  [node sink _ _ _]
  (-fragment sink (:seon.render/output node) (::value node)))

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
          (pr-str
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
  (if (and (contains? #{::vector ::list} (::face node))
           (zero? depth)
           (not (structural-cut? options depth)))
    (if-let [table (table-data node options)]
      (emit-table node table sink path)
      (emit node sink options depth path))
    (emit node sink options depth path))
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

(defn- requery-fields
  [profile]
  (if-some [identity (::requery-id profile)]
    {::requery-id identity}
    {::requery-refusal
     (or (::requery-refusal profile)
         "the source has no stable requery identity")}))

(defn- elision-node
  [profile path next-offset omitted total unit prefix]
  (merge
   {::face ::elided
    ::omitted (max 1 (long omitted))
    ::elision-unit unit
    :seon.render.data/path (vec path)
    :seon.render.data/next-offset (long next-offset)
    :seon.render.profile/id (:seon.render.profile/id profile)}
   (when (some? total) {:seon.render.data/total (long total)})
   (when (some? prefix) {::prefix prefix})
   (when-some [bound-by (::bound-by profile)]
     {::bound-by bound-by})
   (requery-fields profile)))

(defn- preserve-requery
  [elision carried]
  (cond
    (::requery-id carried)
    (-> elision
        (dissoc ::requery-refusal)
        (assoc ::requery-id (::requery-id carried)))

    (::requery-refusal carried)
    (-> elision
        (dissoc ::requery-id)
        (assoc ::requery-refusal (::requery-refusal carried)))

    :else elision))

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
    (elision-node profile path (count (::value node)) 1 (::length node)
                  :characters (pr-str (::value node)))

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
    (mapv (fn [index child]
            (fit-node child profile (inc depth) (conj path index)
                      child-limit string-limit))
          (range)
          entry)
    entry))

(defn- fit-children
  [children profile depth path child-limit string-limit child-fit]
  (let [children (vec children)
        carried-elision (when (= ::elided (::face (peek children)))
                          (peek children))
        children (if carried-elision (pop children) children)
        admitted-total (count children)
        total (or (when (empty? path)
                    (:seon.render.data/total profile))
                  (:seon.render.data/total carried-elision)
                  (when-some [omitted (::omitted carried-elision)]
                    (+ admitted-total omitted))
                  admitted-total)
        retained (min child-limit admitted-total)
        fitted-elision
        (when (< retained total)
          (preserve-requery
           (elision-node profile path retained (- total retained) total
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

(defn- fit-string
  [node profile path string-limit]
  (let [value (::value node)
        original (long (or (::length node) (count value)))
        retained (min string-limit (count value))]
    (if (and (= retained (count value)) (= original retained))
      node
      (elision-node profile path retained (- original retained) original
                    :characters (pr-str (subs value 0 retained))))))

(defn- projected-text
  [node]
  (let [value (::value node)]
    (if (string? value) value (pr-str value))))

(defn- fit-projected
  [node profile path string-limit]
  (let [value (projected-text node)
        original (count value)
        retained (min string-limit original)]
    (if (= retained original)
      node
      (elision-node profile path retained (- original retained) original
                    :characters (pr-str (subs value 0 retained))))))

(defn- structural-elision
  [node profile path]
  (let [children (vec (or (::items node) (::entries node) []))
        carried (when (= ::elided (::face (peek children))) (peek children))
        admitted (if carried (dec (count children)) (count children))
        total (or (:seon.render.data/total carried)
                  (when-some [omitted (::omitted carried)]
                    (+ admitted omitted))
                  admitted)]
    (preserve-requery
     (elision-node profile path 0 (max 1 total) total :subtree nil)
     carried)))

(defn- fit-node
  [node profile depth path child-limit string-limit]
  (let [face (::face node)]
    (cond
      (and (>= depth (:seon.render.profile/max-depth profile))
           (contains? structural-faces face))
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

        (::string ::truncated-string)
        (fit-string node profile path string-limit)

        ::projected
        (fit-projected node profile path string-limit)

        node))))

(defn fit
  "Fit one admitted node to one declared presentation profile.

  Token size is measured only through `seon.ai.tokens/estimate`. Structural
  cuts remain ordinary elision nodes carrying their continuation facts."
  {:malli/schema
   [:=> [:cat :seon.print/node :seon.render.profile/profile]
    :seon.print/node]}
  [node profile]
  (let [budget (:seon.render.profile/token-budget profile)
        initial-children (:seon.render.profile/max-children profile)
        initial-depth (:seon.render.profile/max-depth profile)
        initial-strings (tokens/estimate-chars budget)
        options (assoc (default-options) ::length nil ::level nil)]
    (loop [child-limit initial-children
           depth-limit initial-depth
           string-limit initial-strings]
      (let [candidate (fit-node node
                                (assoc profile
                                       :seon.render.profile/max-depth
                                       depth-limit)
                                0 [] child-limit string-limit)]
        (cond
          (<= (tokens/estimate (emit-text candidate options)) budget)
          candidate

          (pos? string-limit)
          (recur child-limit depth-limit (quot string-limit 2))

          (pos? child-limit)
          (recur (quot child-limit 2) depth-limit 0)

          (pos? depth-limit)
          (recur 0 (dec depth-limit) 0)

          :else candidate)))))

(defn bounded-text
  "Return text unchanged or one honest, requeryable elision value."
  {:malli/schema
   [:=> [:cat :seon.print/bounded-text-request]
    :seon.print/bounded-text]}
  [{text ::text
    character-limit ::character-limit
    bound-by ::bound-by
    supplied-profile :seon.render/profile
    profile-id :seon.render.profile/id
    requery-id ::requery-id
    path :seon.render.data/path}]
  (let [path (vec (or path []))
        profile
        (merge
         (or supplied-profile
             {:seon.render.profile/id profile-id})
         {::requery-id requery-id}
         (when bound-by {::bound-by bound-by}))
        node {::face ::string ::value text}
        fitted (if (some? character-limit)
                 (fit-string node profile path character-limit)
                 (fit node profile))]
    (if (= ::string (::face fitted))
      (::value fitted)
      fitted)))

(schema/register-core-predicate! 'seon.print/sink? sink?)
(schema/register-core-predicate! 'seon.print/print-number? print-number?)
(schema/register-core-predicate! 'seon.print/print-char? print-char?)

#?(:clj (schema.edn/load! {}))
