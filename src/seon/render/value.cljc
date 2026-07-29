(ns seon.render.value
  "Structural presentation for every value the render router receives.

  This is the quarry's proven universal value renderer, ported to the fresh
  JVM system: it builds a depth- and breadth-bounded skeleton instead of
  clipping `pr-str` mid-token. Retained map keys and vector positions keep
  their real navigation identity; lazy sequences expose only a bounded head;
  opaque runtime handles become tagged data tokens; every omitted tail says
  that it was omitted and, when knowable, how much remains. `render-ai` and
  `render-html` are twins over one immutable result from `prepare`, while
  `render-html-data` exposes that same plain-data projection to richer human
  renderers.

  `seon.sci.admit` and this namespace are deliberately layered, not merged.
  Admission is the safety codec at the SCI eval boundary: it interrupts,
  bounds total nodes, and produces durable printable ordinary data. This
  renderer is the presentation skeleton over an admitted value—or any other
  value entering the router—with smaller presentation dials, preserved
  navigation identities, semantic map-field retention, and explicit elision
  markers. Both walk nested Clojure data today. That overlap is named in
  `docs/seon/issues/value-admission-render-walk-overlap.md`; merging their
  cores belongs to the owner-tabled renderer/walk design session, not this
  port. `seon.render.walk` remains untouched.

  Presentation defaults are registrations in `schema/render_value.edn`.
  Calls may narrow them through `:seon.render.value/options`; a database-backed
  unit derives admission's hard maxima from that same database value. Pure
  database-free calls may supply effective config or caps explicitly and
  otherwise use only the registered presentation defaults. Nothing here opens
  resources or writes facts."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.schema.form :as schema.form]
            #?(:clj [datahike.impl.entity :as datahike.entity])
            #?(:clj [seon.schema.edn :as schema.edn])))

;;; ---------------------------------------------------------------------------
;;; Schemas and options
;;; ---------------------------------------------------------------------------

#?(:clj (schema.edn/load! {}))

(defn- registered-option-defaults
  []
  (into {}
        (map
         (fn [[option]]
           (let [properties
                 (schema.form/attr-form-properties
                  (schema/schema-definition option))]
             (if (contains? properties :seon.render.value/default)
               [option (:seon.render.value/default properties)]
               (throw
                (ex-info
                 (str "Renderer option " option " has no default.")
                 {:seon.error/kind :core-bug
                  :seon.schema/key option}))))))
        (drop 2
              (schema/schema-definition :seon.render.value/options))))

(def ^:private default-options
  (delay (registered-option-defaults)))

(defn- positive-long
  [value fallback]
  (if (and (integer? value) (pos? value))
    (long value)
    (long fallback)))

(defn- presentation-options
  [effective caps overrides]
  (let [defaults @default-options
        requested
        (reduce-kv
         (fn [result key configured]
           (assoc result key
                  (positive-long (get overrides key) configured)))
         {}
         defaults)
        caps (merge (config/result-caps effective) caps)
        admission-maxima
        {:seon.render.value/max-depth
         (:seon.config.eval.result/max-depth caps)
         :seon.render.value/max-collection
         (:seon.config.eval.result/max-collection caps)
         :seon.render.value/max-string
         (:seon.config.eval.result/max-string caps)}]
    (reduce-kv
     (fn [result key maximum]
       (if maximum
         (update result key min (long maximum))
         result))
     requested
     admission-maxima)))

(defn- database-effective
  [db]
  (let [clusters
        (into
         (sorted-set)
         (d/q '[:find [?cluster ...]
                :where [_ :seon.config/cluster ?cluster]]
              db))]
    (when-not (= 1 (count clusters))
      (throw
       (ex-info
        "A database-backed render needs exactly one cluster config row."
        {:seon.error/kind :core-bug
         ::rule ::single-cluster-config
         :seon.config/clusters clusters})))
    (let [effective (config/effective db (first clusters))]
      (when-not
        (schema/valid-candidate-value? :seon.config/effective effective)
        (throw
         (ex-info
          "A database-backed render needs a complete effective config row."
          {:seon.error/kind :core-bug
           ::rule ::complete-effective-config
           :seon.schema/explanation
           (schema/explain-candidate-value
            :seon.config/effective effective)})))
      effective)))

(defn- unit-effective
  [unit]
  (if-some [db (:seon.db/db unit)]
    (database-effective db)
    (or (:seon.config/effective unit) {})))

(defn- unit-value
  [unit]
  (get unit :seon.render/value unit))

(defn- unit-options
  [unit effective]
  (presentation-options
   effective
   (when-not (:seon.db/db unit)
     (:seon.sci.admit/caps unit))
   (:seon.render.value/options unit)))

(defn- bounded-pr-str
  [value maximum-tokens]
  (let [text (pr-str value)
        maximum-characters (* (long maximum-tokens)
                              tokens/chars-per-token)]
    (if (<= (count text) maximum-characters)
      text
      (str (subs text 0 (max 0 (dec maximum-characters))) "…"))))

(defn- clip-text
  [text maximum]
  (if (<= (count text) maximum)
    text
    (str (subs text 0 (max 0 (dec maximum))) "…")))

;;; ---------------------------------------------------------------------------
;;; Opaque values — tagged tokens, never arbitrary printing
;;; ---------------------------------------------------------------------------

(defn- datahike-database?
  [value]
  (and (record? value)
       (some? (:max-tx value))))

(defn- datahike-entity?
  [value]
  #?(:clj (datahike.entity/entity? value)
     :cljs false))

(defn- datom?
  [value]
  (and (some? value)
       (not (coll? value))
       (not (record? value))
       (try
         (and (number? (:e value))
              (keyword? (:a value)))
         (catch #?(:clj Throwable :cljs :default) _ false))))

(defn- ordinary-scalar?
  [value]
  (or (nil? value)
      (string? value)
      (number? value)
      (keyword? value)
      (symbol? value)
      (boolean? value)
      (char? value)
      (uuid? value)
      (inst? value)))

(defn opaque?
  "True when `value` is a runtime handle rather than ordinary data."
  {:malli/schema [:=> [:cat :seon.render/value] :boolean]}
  [value]
  (boolean
   (or (datahike-database? value)
       (datahike-entity? value)
       (datom? value)
       (record? value)
       (fn? value)
       (and (some? value)
            (not (coll? value))
            (not (ordinary-scalar? value))))))

(defn- class-label
  [value]
  (let [label #?(:clj (.getName (class value))
                 :cljs (str (type value)))]
    (subs label 0 (min 80 (count label)))))

(declare sample-node)

(defn- opaque-marker
  [value options depth]
  (try
    (cond
      (datom? value)
      {:seon.eval/datom
       [(:e value)
        (:a value)
        (sample-node (:v value) options (inc depth))]}

      (datahike-database? value)
      (cond-> {:seon.eval/opaque "datahike/DB"}
        (number? (:max-tx value))
        (assoc :seon.eval/summary
               (str "max-tx=" (:max-tx value)
                    (when (number? (:max-eid value))
                      (str " max-eid=" (:max-eid value))))))

      (datahike-entity? value)
      (let [eid (:db/id value)]
        (cond-> {:seon.eval/opaque "datahike/Entity"}
          (or (number? eid) (keyword? eid) (string? eid))
          (assoc :seon.eval/summary
                 (str ":db/id=" (bounded-pr-str eid 20)))))

      (record? value)
      {:seon.eval/opaque (class-label value)}

      (fn? value)
      {:seon.eval/opaque "fn"}

      :else
      {:seon.eval/opaque "jvm/Object"})
    (catch #?(:clj Throwable :cljs :default) _
      {:seon.eval/opaque "unknown"
       :seon.eval/summary "<unprintable>"})))

;;; ---------------------------------------------------------------------------
;;; Bounded structural sampling
;;; ---------------------------------------------------------------------------

(defn- counted-count
  [value]
  (when (counted? value)
    (try
      (count value)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- shared-keys
  [items limit]
  (let [head (take limit items)]
    (when (and (seq head) (every? map? head))
      (let [shared (reduce set/intersection (map (comp set keys) head))]
        (when (seq shared)
          (vec (sort-by str shared)))))))

(defn- field-tier
  [preferred key]
  (let [field (when (or (keyword? key) (symbol? key) (string? key))
                (name key))]
    (cond
      (contains? preferred key) 0
      (or (= key :db/id)
          (= field "id")
          (str/ends-with? (or field "") "-id")) 1
      (contains? #{"status" "state" "phase" "outcome"} field) 2
      (contains? #{"title" "summary" "name" "label" "purpose"} field) 3
      (or (str/includes? (or field "") "error")
          (str/includes? (or field "") "fault")) 4
      (contains? #{:seon.db/user :seon.db/process :db/txInstant} key) 5
      :else 6)))

(defn- named-length
  [value]
  (+ (count (name value))
     (if-some [namespace (namespace value)]
       (inc (count namespace))
       0)))

(defn- clipped-string
  [value maximum]
  (if (> (count value) maximum)
    {:seon.render.value/string-len (count value)
     :seon.render.value/head (subs value 0 (max 0 (dec maximum)))}
    value))

(defn- bounded-key-description
  [value]
  (bounded-pr-str value 20))

(defn- exceptional-number?
  [value]
  #?(:clj
     (or (Double/isNaN (double value))
         (Double/isInfinite (double value))
         (= (Double/doubleToRawLongBits (double value))
            (Double/doubleToRawLongBits -0.0)))
     :cljs
     (or (js/isNaN value)
         (not (js/isFinite value))
         (js/Object.is value -0))))

(defn- map-key
  [key maximum]
  (cond
    (opaque? key)
    [(opaque-marker key
                    {:seon.render.value/max-string maximum}
                    0)
     true]

    (and (string? key) (> (count key) maximum))
    [{:seon.eval/opaque "map-key/string"
      :seon.eval/summary (bounded-key-description key)}
     true]

    (and (or (keyword? key) (symbol? key))
         (> (named-length key) maximum))
    [{:seon.eval/opaque
      (str "map-key/" (if (keyword? key) "keyword" "symbol"))
      :seon.eval/summary (bounded-key-description key)}
     true]

    (coll? key)
    [{:seon.eval/opaque "map-key/collection"} true]

    (and (number? key) (exceptional-number? key))
    [{:seon.eval/opaque "map-key/number"
      :seon.eval/summary (bounded-key-description key)}
     true]

    (or (nil? key)
        (boolean? key)
        (number? key)
        (and (string? key) (<= (count key) maximum))
        (and (or (keyword? key) (symbol? key))
             (<= (named-length key) maximum)))
    [key false]

    :else
    [{:seon.eval/opaque "map-key/unsupported"} true]))

(defn- realization-message
  [kind failure]
  {:seon.eval/opaque (str (name kind) " realization threw")
   :seon.eval/summary
   (clip-text
    (or (ex-message failure)
        #?(:clj (.getName (class failure))
           :cljs (str (type failure))))
    60)})

(defn- sample-sequential
  [value options depth kind]
  (let [maximum (:seon.render.value/max-collection options)
        forced
        (try
          {:head (vec (take (inc maximum) value))}
          (catch #?(:clj Throwable :cljs :default) failure
            {:failure failure}))]
    (if-some [failure (:failure forced)]
      (realization-message kind failure)
      (let [head (:head forced)
            overflow? (> (count head) maximum)
            shown (mapv #(sample-node % options (inc depth))
                        (take maximum head))
            total (counted-count value)
            elided (cond
                     (not overflow?) 0
                     total (- total maximum)
                     :else :more)
            shape (when overflow?
                    (try
                      (shared-keys head
                                   (:seon.render.value/shape-sample options))
                      (catch #?(:clj Throwable :cljs :default) _ nil)))]
        (cond-> {:seon.render.value/kind kind
                 :seon.render.value/shown shown}
          (not= 0 elided)
          (assoc :seon.render.value/elided elided)

          shape
          (assoc :seon.render.value/shape shape))))))

(defn- sample-map
  [value options depth]
  (let [maximum (:seon.render.value/max-collection options)
        visits (max maximum (:seon.render.value/max-map-visits options))
        candidates+sentinel
        (try
          (into [] (take (inc visits)) value)
          (catch #?(:clj Throwable :cljs :default) failure
            ::failed))]
    (if (= ::failed candidates+sentinel)
      {:seon.eval/opaque "map realization threw"}
      (let [candidates (take visits candidates+sentinel)
            sampled
            (mapv
             (fn [[key child]]
               (let [[display-key non-drillable?]
                     (map-key key (:seon.render.value/max-string options))]
                 [display-key
                  (sample-node child options (inc depth))
                  non-drillable?]))
             candidates)
            preferred
            (or (:seon.render.value/preferred-keys options) #{})
            ranked
            (sort-by
             (fn [[index [key child _]]]
                [(field-tier preferred key)
                (count (bounded-pr-str child 20))
                index])
             (map-indexed vector sampled))
            kept-indexes (into #{} (map first) (take maximum ranked))
            kept (into []
                       (keep-indexed
                        (fn [index entry]
                          (when (contains? kept-indexes index)
                            entry)))
                       sampled)
            [entries non-drillable]
            (reduce-kv
             (fn [[result indexes] index [key child projected?]]
               [(conj result [key child])
                (cond-> indexes projected? (conj index))])
             [[] []]
             kept)
            total (counted-count value)
            beyond-window? (> (count candidates+sentinel) visits)
            elided (cond
                     total (max 0 (- total (count entries)))
                     beyond-window? :more
                     :else (max 0 (- (count candidates)
                                     (count entries))))]
        (cond-> {:seon.render.value/map-entries entries}
          (not= 0 elided)
          (assoc :seon.render.value/elided-keys elided)

          (seq non-drillable)
          (assoc :seon.render.value/non-drillable-key-indexes
                 non-drillable))))))

(defn- collection-kind
  [value]
  (cond
    (map? value) :map
    (set? value) :set
    (vector? value) :vector
    :else :seq))

(defn- depth-marker
  [value]
  (try
    (when (seq value)
      {:seon.render.value/pruned (collection-kind value)
       :seon.render.value/count (counted-count value)})
    (catch #?(:clj Throwable :cljs :default) failure
      (realization-message (collection-kind value) failure))))

(defn- sample-node
  [value options depth]
  (let [depth-marker
        (when (and (>= depth (:seon.render.value/max-depth options))
                   (coll? value))
          (depth-marker value))]
    (cond
      depth-marker
      depth-marker

      (opaque? value)
      (opaque-marker value options depth)

      (string? value)
      (clipped-string value (:seon.render.value/max-string options))

      (or (keyword? value) (symbol? value))
      (if (> (named-length value) (:seon.render.value/max-string options))
        {:seon.eval/opaque (if (keyword? value) "keyword" "symbol")
         :seon.eval/summary (bounded-key-description value)}
        value)

      (map? value)
      (sample-map value options depth)

      (vector? value)
      (sample-sequential value options depth :vector)

      (set? value)
      (sample-sequential value options depth :set)

      (coll? value)
      (sample-sequential value options depth :seq)

      :else value)))

(defn sample
  "Build a bounded navigable skeleton of `value`.

  `effective` supplies admission's hard config maxima. `overrides` may only
  narrow or reshape this call's presentation and uses
  `:seon.render.value/*` keys."
  {:malli/schema
   [:=> [:cat :seon.config/effective :seon.render/value
         :seon.render.value/options]
    :seon.render.value/tree]}
  [effective value overrides]
  (sample-node value
               (presentation-options effective
                                     (config/result-caps effective)
                                     overrides)
               0))

;;; ---------------------------------------------------------------------------
;;; Marker detection and text emission
;;; ---------------------------------------------------------------------------

(defn- leaf-marker?
  [value]
  (and (map? value)
       (or (:seon.eval/opaque value)
           (:seon.eval/datom value)
           (:seon.render.value/string-len value)
           (:seon.render.value/pruned value))))

(defn- truncated?
  [skeleton]
  (boolean
   (some
    (fn [value]
      (and
       (map? value)
       (or (contains? value :seon.render.value/elided)
           (and (contains? value :seon.render.value/map-entries)
                (not= 0 (get value :seon.render.value/elided-keys 0)))
           (seq (:seon.render.value/non-drillable-key-indexes value))
           (contains? value :seon.render.value/pruned)
           (contains? value :seon.eval/opaque)
           (contains? value :seon.eval/datom)
           (contains? value :seon.render.value/string-len))))
    (tree-seq coll?
              #(if (map? %) (vals %) (seq %))
              skeleton))))

(defn- top-summary
  [value skeleton]
  (cond
    (:seon.eval/opaque skeleton)
    (:seon.eval/opaque skeleton)

    (:seon.eval/datom skeleton)
    "datom"

    (map? value)
    (if-some [size (counted-count value)]
      (str "map " size " keys")
      "map")

    (vector? value)
    (str "vector " (count value) " items")

    (set? value)
    (str "set " (count value) " items")

    (coll? value)
    (if-some [size (counted-count value)]
      (str "seq " size " items")
      "seq")

    :else "scalar"))

(declare emit-inline)

(defn- datom-token
  [value]
  (let [[entity attribute child] (:seon.eval/datom value)]
    (str "#datom["
         (bounded-pr-str entity 20) " "
         (bounded-pr-str attribute 20) " "
         (emit-inline child)
         "]")))

(defn- opaque-token
  [value]
  (str "#‹" (:seon.eval/opaque value)
       (when-some [summary (:seon.eval/summary value)]
         (str " " summary))
       "›"))

(defn- pruned-token
  [value]
  (let [kind (:seon.render.value/pruned value)
        size (:seon.render.value/count value)
        [open close] (case kind
                       :map ["{" "}"]
                       :set ["#{" "}"]
                       :vector ["[" "]"]
                       ["(" ")"])
        unit (if (= kind :map) "keys" "items")]
    (str open "…"
         (when size (str size " " unit))
         close)))

(defn- clipped-string-token
  [value]
  (str (pr-str (str (:seon.render.value/head value) "…"))
       "⟨"
       (quot (:seon.render.value/string-len value)
             tokens/chars-per-token)
       " tokens⟩"))

(defn- emit-leaf
  [value]
  (cond
    (:seon.eval/datom value) (datom-token value)
    (:seon.eval/opaque value) (opaque-token value)
    (:seon.render.value/pruned value) (pruned-token value)
    (:seon.render.value/string-len value) (clipped-string-token value)))

(defn- seq-parts
  [value]
  (let [{:seon.render.value/keys [shown elided shape]} value]
    [shown
     (str/join
      " "
      (remove
       nil?
       [(cond
          (nil? elided) nil
          (= :more elided) "… +more"
          :else (str "… +" elided " more"))
        (when shape
          (str "sampled columns {"
               (str/join " " (map pr-str shape))
               "}"))]))]))

(defn- map-parts
  [value]
  (let [wrapped? (contains? value :seon.render.value/map-entries)
        entries (if wrapped?
                  (:seon.render.value/map-entries value)
                  value)
        elided (when wrapped?
                 (:seon.render.value/elided-keys value))
        non-drillable
        (when wrapped?
          (:seon.render.value/non-drillable-key-indexes value))]
    [(map (fn [[key child]]
            [(bounded-pr-str key 20) child])
          entries)
     (str/join
      " · "
      (remove
       nil?
       [(when elided
          (if (= :more elided)
            "… +more keys"
            (str "… +" elided " more keys")))
        (when (seq non-drillable)
          (str (count non-drillable)
               " non-drillable key"
               (when (not= 1 (count non-drillable)) "s")
               " shown safely"))]))]))

(defn- emit-inline
  [value]
  (cond
    (leaf-marker? value)
    (emit-leaf value)

    (:seon.render.value/kind value)
    (let [[open close]
          (case (:seon.render.value/kind value)
            :vector ["[" "]"]
            :set ["#{" "}"]
            ["(" ")"])
          [children tail] (seq-parts value)]
      (str open
           (str/join " "
                     (concat (map emit-inline children)
                             (when (seq tail) [tail])))
           close))

    (map? value)
    (let [[entries tail] (map-parts value)]
      (str "{"
           (str/join
            ", "
            (concat
             (map (fn [[key child]]
                    (str key " " (emit-inline child)))
                  entries)
             (when (seq tail) [tail])))
           "}"))

    :else
    (pr-str value)))

(defn- indentation
  [depth]
  (apply str (repeat depth "  ")))

(declare emit)

(defn- fits?
  [value depth width]
  (let [text (emit-inline value)]
    (and (not (str/includes? text "\n"))
         (<= (+ (count text) (* 2 depth)) width))))

(defn- emit
  [value depth width]
  (cond
    (leaf-marker? value)
    (emit-leaf value)

    (fits? value depth width)
    (emit-inline value)

    (:seon.render.value/kind value)
    (let [[open close]
          (case (:seon.render.value/kind value)
            :vector ["[" "]"]
            :set ["#{" "}"]
            ["(" ")"])
          [children tail] (seq-parts value)
          separator (str "\n" (indentation (inc depth)))]
      (str open
           (str/join separator
                     (map #(emit % (inc depth) width) children))
           (when (seq tail) (str separator tail))
           close))

    (map? value)
    (let [[entries tail] (map-parts value)
          separator (str "\n" (indentation (inc depth)))]
      (str "{"
           (str/join
            separator
            (map (fn [[key child]]
                   (str key " " (emit child (inc depth) width)))
                 entries))
           (when (seq tail) (str separator tail))
           "}"))

    :else
    (pr-str value)))

;;; ---------------------------------------------------------------------------
;;; One prepared projection, two twins
;;; ---------------------------------------------------------------------------

(defn- schema-statuses
  [value incomplete?]
  (when (map? value)
    (try
      (if incomplete?
        (mapv
         (fn [row]
           {:seon.schema/key (:seon.schema/key row)
            :seon.schema/entity? (:seon.schema/entity? row)
            :seon.render.value/status :shape-only})
         (schema/candidate-shapes value))
        (mapv
         (fn [row]
           {:seon.schema/key (:seon.schema/key row)
            :seon.schema/entity? (:seon.schema/entity? row)
            :seon.render.value/status :valid})
         (schema/matching-shapes value)))
      (catch #?(:clj Throwable :cljs :default) _ []))))

(defn prepare
  "Sample a unit once into the immutable projection both twins consume."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  :seon.render.value/projection]}
  [unit]
  (let [raw (unit-value unit)
        effective (unit-effective unit)
        options (unit-options unit effective)
        skeleton (sample effective raw options)
        incomplete? (truncated? skeleton)
        statuses (schema-statuses raw incomplete?)]
    (cond-> {:seon.render.value/tree skeleton
             :seon.render.value/summary (top-summary raw skeleton)
             :seon.render.value/truncated? incomplete?}
      (seq statuses)
      (assoc :seon.render.value/schemas statuses)

      true
      (assoc ::width (:seon.render.value/width options)))))

(defn render-ai-data
  "Render AI text from one already prepared structural projection."
  {:malli/schema [:=> [:cat :map] :string]}
  [projection]
  (str
   (emit (:seon.render.value/tree projection)
         0
         (get projection ::width 72))
   (when (:seon.render.value/truncated? projection)
     (str "\n; ‹partial view of "
          (:seon.render.value/summary projection)
          " — elided past the configured presentation caps›"))))

(defn render-html-data
  "Return the plain-data contract for one prepared human projection."
  {:malli/schema [:=> [:cat :map] :map]}
  [projection]
  (dissoc projection ::width))

(defn- marker-hiccup
  [value]
  [:span {:class "seon-value-marker"} (emit-leaf value)])

(declare skeleton-hiccup)

(defn- skeleton-hiccup
  [value]
  (cond
    (leaf-marker? value)
    (marker-hiccup value)

    (:seon.render.value/kind value)
    (let [[children tail] (seq-parts value)
          tag (if (= :set (:seon.render.value/kind value)) :ul :ol)]
      [tag {:class (str "seon-value-"
                        (name (:seon.render.value/kind value)))}
       (mapv (fn [child] [:li (skeleton-hiccup child)]) children)
       (when (seq tail)
         [:li {:class "seon-value-elision"} tail])])

    (map? value)
    (let [[entries tail] (map-parts value)]
      [:dl {:class "seon-value-map"}
       (mapv
        (fn [[key child]]
          [:div {:class "seon-value-entry"}
           [:dt key]
           [:dd (skeleton-hiccup child)]])
        entries)
       (when (seq tail)
         [:div {:class "seon-value-elision"} tail])])

    (string? value)
    [:span {:class "seon-value-string"} value]

    (nil? value)
    [:span {:class "seon-value-nil"} "nil"]

    :else
    [:span {:class "seon-value-scalar"} (str value)]))

(defn render-ai
  "Render any unit as bounded structural text for the AI boundary."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (render-ai-data (prepare unit)))

(defn render-html
  "Render any unit as bounded structural hiccup for the HTML boundary."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [projection (prepare unit)]
    [:div {:class "seon-value"}
     [:div {:class "seon-value-summary"}
      (:seon.render.value/summary projection)]
     (skeleton-hiccup (:seon.render.value/tree projection))
     (when (:seon.render.value/truncated? projection)
       [:p {:class "seon-value-elision"}
        "elided past the configured presentation caps"])]))
