(ns seon.print
  "Emits admitted print nodes through text and hiccup sinks."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [seon.schema :as schema]
            #?(:clj [seon.schema.edn :as schema.edn])
            [seon.schema.form :as schema.form]))

(defprotocol Sink
  (-open [sink node] "Enter one structural node.")
  (-token [sink face text] "Emit one lexical token.")
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
  [:details
   {:class (str "seon-print-node " (face-class (::kind node)))
    :data-seon-path (pr-str (::path node))}
   [:summary {:class "seon-print-summary"
              :data-seon-summary (::summary node)}]
   (into [:span {:class "seon-print-content"}] children)])

(deftype ^:private HiccupSink [state]
  Sink
  (-open [_ node]
    (vswap! state update ::stack conj
            {::node node
             ::children [(hiccup-token ::delimiter (::begin node))]}))
  (-token [_ face text]
    (append-hiccup! state (hiccup-token face text)))
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

(defn- option-defaults
  []
  (into {}
        (keep
         (fn [entry]
           (when (vector? entry)
             (let [attribute (first entry)
                   properties
                   (schema.form/attr-form-properties
                    (schema/schema-definition attribute))]
               (when (contains? properties ::default)
                 [attribute (::default properties)])))))
        (schema/schema-definition ::options)))

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

(declare emit-node)

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
    (-token sink ::elision "...")
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
  (emit-sequential node sink options depth path "#{" "}" " "))

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
          (str "#object[" (::class node) " " (::address node)
               (when-some [rep (::rep node)] (str " " rep)) "]")))

(defmethod emit ::truncated-string
  [node sink _ _ _]
  (-token sink ::string (pr-str (::value node))))

(defmethod emit ::failed
  [node sink _ _ _]
  (-token sink ::object
          (str "#object[" (::class node) " 0x0 "
               (pr-str (str "projection failed: " (::message node))) "]")))

(defmethod emit ::throwable
  [node sink options depth path]
  (if (structural-cut? options depth)
    (-token sink ::prune "#")
    (do
      (-token sink ::tag "#error ")
      (emit-node (::value node) sink options depth (conj path ::throwable)))))

(defmethod emit ::elided
  [_ sink _ _ _]
  (-token sink ::elision "..."))

(defmethod emit ::pruned
  [_ sink _ _ _]
  (-token sink ::prune "#"))

(defmethod emit :default
  [node _ _ _ _]
  (throw (ex-info "Unknown admitted print face."
                  {:seon.error/kind ::unknown-face
                   ::face (::face node)})))

(defn- emit-node
  [node sink options depth path]
  (emit node sink options depth path)
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

(schema/register-core-predicate! 'seon.print/sink? sink?)
(schema/register-core-predicate! 'seon.print/print-number? print-number?)
(schema/register-core-predicate! 'seon.print/print-char? print-char?)

#?(:clj (schema.edn/load! {}))
