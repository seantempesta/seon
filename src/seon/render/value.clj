(ns seon.render.value
  "Unit adapter from admitted print data to the two floor projections."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [seon.print :as print]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
            [seon.schema.internal :as schema.internal]
            [seon.sci.admit :as admit]))

(schema.edn/load! {})

(defn transacted
  "Restore a pulled entity to its transaction shape.

  With a database value, installed Datahike value type and cardinality are the
  authority: refs become entity ids, cardinality-many values become sets, and
  scalar EDN vectors remain vectors. The one-argument arity preserves the
  established shape-only behavior for callers without database custody."
  {:malli/schema
   [:function
    [:=> [:catn [::entity :map]] :map]
    [:=> [:catn [::entity :map]
                 [::database :seon.db/db]]
     :map]]}
  ([entity]
   (into {}
         (map (fn [[attribute value]]
                [attribute
                 (cond
                   (and (map? value) (find value :db/id)) (:db/id value)
                   (and (sequential? value)
                        (seq value)
                        (every? #(and (map? %) (find % :db/id)) value))
                   (into #{} (map :db/id) value)
                   (sequential? value) (set value)
                   :else value)]))
         (dissoc entity :db/id)))
  ([entity database]
   (into {}
         (map (fn [[attribute value]]
                (let [{:db/keys [valueType cardinality]}
                      (get-in database [:schema attribute])
                      ref-id #(if (and (map? %) (find % :db/id))
                                (:db/id %)
                                %)]
                  [attribute
                   (cond
                     (= :db.type/ref valueType)
                     (if (= :db.cardinality/many cardinality)
                       (into #{} (map ref-id) value)
                       (ref-id value))

                     (= :db.cardinality/many cardinality)
                     (set value)

                     :else value)])))
         (dissoc entity :db/id))))

(defn render-database-identity-ai
  "Readable identity face for an admitted immutable database value."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (str "database " (pr-str (:db-name unit))
       " at basis transaction " (:t unit)
       " commit " (:datahike/commit-id unit)))

(def ^:private print-option-keys
  #{:seon.print/length
    :seon.print/level
    :seon.print/width
    :seon.print/namespace-maps?
    :seon.print/table?})

(defn node-id
  "Stable element id for one root selector and `get-in` path."
  {:malli/schema [:=> [:cat :seon.render/unit :seon.render.data/path]
                  [:or :string :seon.error/value]]}
  [unit path]
  (let [root-address
        (or (:seon.render.call/id unit)
            (:seon.render.value/root unit)
            (when-some [eid (:db/id unit)] [:db/id eid])
            (when-some [block-name (:seon.render.block/name unit)]
              [:seon.render.block/name block-name]))]
    (if-not root-address
      {:seon.error/kind ::missing-root-identity
       :seon.error/message
       "A rendered value root requires a caller-supplied block id."
       :seon.error/data
       {:seon.cluster.agent/id (:seon.cluster.agent/id unit)
        :seon.render.data/path path} :seon.render.value/missing-root-identity true}
      (let [address [(:seon.cluster.agent/id unit) root-address path]
            digest (schema/sha-256
                    [(.getBytes ^String (pr-str address) "UTF-8")])]
        (str "seon-value-" (subs digest 0 24))))))

(defn- encoded
  [value]
  (java.net.URLEncoder/encode (str value) "UTF-8"))

(defn- path-url
  [unit path offset]
  (when-let [base (:seon.render.value/route-base unit)]
    (str base (if (str/includes? base "?") "&" "?")
         "path=" (encoded (pr-str path)) "&offset=" offset)))

(defn- path-link
  [unit path offset label css-class]
  (if-some [url (path-url unit path offset)]
    [:a {:class css-class :href url} label]
    label))

(defn- print-options
  [unit]
  (merge (print/default-options)
         (select-keys (:seon.render.value/options unit) print-option-keys)
         (:seon.print/options unit)))

(defn- render-profile
  [unit]
  (let [profile (or (:seon.render/profile unit)
                    ((requiring-resolve 'seon.render/agent-render-profile)
                     ((requiring-resolve 'seon.config/defaults))))]
    (cond-> profile
      (:seon.cluster.eval/result-blob unit)
      (assoc :seon.print/requery-id
             [:seon.blob/digest (:seon.cluster.eval/result-blob unit)])

      (and (nil? (:seon.cluster.eval/result-blob unit))
           (:seon.render.value/root unit))
      (assoc :seon.print/requery-id (:seon.render.value/root unit))

      (and (nil? (:seon.cluster.eval/result-blob unit))
           (nil? (:seon.render.value/root unit)))
      (assoc :seon.print/requery-refusal
             "the value has no durable blob or entity identity"))))

(defn- stable-entries
  [value]
  (cond
    (map? value) (sort-by (comp pr-str first) (seq value))
    (set? value) (map (fn [entry] [entry entry]) (sort-by pr-str value))
    (vector? value) (map-indexed vector value)
    (sequential? value) (map-indexed vector value)
    :else nil))

(defn- counted-size
  [value]
  (when (counted? value)
    (try (count value) (catch Throwable _ nil))))

(defn window
  "Return one stable structural page from an ordinary bounded value."
  {:malli/schema [:=> [:cat :any :int :int] :map]}
  [value offset size]
  (try
    (if-let [entries (stable-entries value)]
      (let [available (max 0 size)
            total (counted-size value)
            head (into [] (comp (drop offset) (take (inc available))) entries)
            more? (> (count head) available)
            page (subvec head 0 (min available (count head)))
            page-value (cond
                         (map? value) (into {} page)
                         (set? value) (into #{} (map second) page)
                         :else (mapv second page))]
        {:seon.render.value/window page-value
         :seon.render.value/steps (mapv first page)
         :seon.render.value/offset offset
         :seon.render.value/shown (count page)
         :seon.render.value/total total
         :seon.render.value/beyond-end?
         (and (some? total) (> offset total))
         :seon.render.value/more? more?})
      {:seon.render.value/window value
       :seon.render.value/steps []
       :seon.render.value/offset 0
       :seon.render.value/shown 0
       :seon.render.value/total nil
       :seon.render.value/beyond-end? false
       :seon.render.value/more? false})
    (catch Throwable failure
      {:seon.render.value/window
       {:seon.error/kind :seon.render.value/window-failed
        :seon.render.value/window-realization-failed true
        :seon.error/message (or (ex-message failure) "realization failed")}
       :seon.render.value/steps []
       :seon.render.value/offset offset
       :seon.render.value/shown 0
       :seon.render.value/total nil
       :seon.render.value/beyond-end? false
       :seon.render.value/more? false})))

(defn- display-value
  [unit]
  (let [raw (:seon.render/value unit)
        total (counted-size raw)]
    {:seon.render.value/window raw
     :seon.render.value/steps []
     :seon.render.value/offset 0
     :seon.render.value/shown (or total 0)
     :seon.render.value/total total
     :seon.render.value/more? false}))

(defn- admitted-projection
  [value unit]
  (let [caps (:seon.sci.admit/caps unit)
        interrupt-fn (get-in unit [:seon.sci.eval/ctx
                                   :seon.sci.kernel/guard
                                   :seon.sci.kernel/interrupt-fn])
        admitted
        (admit/admit-value
         (cond->
          {:seon.sci.admit/value value
           :seon.sci.admit/caps caps
           :seon.sci.admit/interrupt-fn (or interrupt-fn (fn []))
           :seon.config/on-core-error :record}
           interrupt-fn
           (assoc :seon.sci.admit/unbounded? true)))]
    {:seon.render.value/tree
     (:seon.sci.admit/print-node admitted)
     :seon.render.value/semantic (:seon.sci.admit/value admitted)
     :seon.render.value/truncated? (:seon.sci.admit/capped? admitted)}))

(defn- distinct-in-order
  [values]
  (reduce (fn [result value]
            (if (some #{value} result) result (conj result value)))
          []
          values))

(defn- declared-attributes
  [forms schema-key]
  (letfn [(attributes [form visited required-only?]
            (cond
              (and (keyword? form)
                   (get forms form)
                   (not (contains? visited form)))
              (attributes (get forms form) (conj visited form) required-only?)

              (schema.form/map-shape? form)
              (into []
                    (keep (fn [entry]
                            (when (vector? entry)
                              (let [attribute (first entry)
                                    properties (when (map? (second entry))
                                                 (second entry))]
                                (when (and (qualified-keyword? attribute)
                                           (or (not required-only?)
                                               (not (:optional properties))))
                                  attribute)))))
                    (schema.form/map-entries form))

              (and (vector? form) (= :and (first form)))
              (into []
                    (mapcat #(attributes % visited required-only?))
                    (remove map? (rest form)))

              :else []))]
    (let [definition (get forms schema-key)
          declared (distinct-in-order (attributes definition #{} false))
          required (distinct-in-order (attributes definition #{} true))
          identity-attribute
          (some #(when (schema.internal/identity-attr? forms %) %) declared)]
      (distinct-in-order
       (concat (when identity-attribute [identity-attribute])
               (remove #{identity-attribute} required)
               (remove (set required) declared))))))

(defn- matching-shape-rows
  [projection value]
  (let [values (cond-> [value]
                 (:db/id value) (conj (transacted value)))]
    (->> values
         (mapcat #(schema/matching-shapes-in projection %))
         (reduce (fn [rows row]
                   (if (some #(= (:seon.schema/key row)
                                  (:seon.schema/key %))
                             rows)
                     rows
                     (conj rows row)))
                 []))))

(defn- common-shape
  [projection rows]
  (let [matches (mapv #(matching-shape-rows projection %) rows)]
    (some (fn [candidate]
            (when (every? (fn [row-matches]
                            (some #(= (:seon.schema/key candidate)
                                      (:seon.schema/key %))
                                  row-matches))
                          (next matches))
              candidate))
          (first matches))))

(defn- map-collection
  [value]
  (when (and (coll? value) (not (map? value)))
    (let [values (vec value)
          rows (filterv map? values)]
      (when (and (seq rows)
                 (every? #(or (map? %) (= :seon.sci.admit/elided %)) values))
        rows))))

(defn- registered-layout
  [unit value]
  (when-let [projection
             (some->> (:seon.sci.eval/ctx unit)
                      ((requiring-resolve
                        'seon.sci.kernel/context-projection)))]
    (let [rows (cond
                 (map? value) [value]
                 :else (map-collection value))]
      (when (seq rows)
        (when-let [shape (common-shape projection rows)]
          {:seon.render.value/layout
           (if (map? value) :map :map-collection)
           :seon.render.value/attributes
           (declared-attributes (:seon.schema.projection/forms projection)
                                (:seon.schema/key shape))})))))

(defn- child-nodes
  [node]
  (case (:seon.print/face node)
    (:seon.print/vector :seon.print/list :seon.print/set)
    (:seon.print/items node)

    (:seon.print/map :seon.print/record)
    (mapcat #(if (vector? %) % [%]) (:seon.print/entries node))

    :seon.print/throwable [(:seon.print/value node)]
    []))

(defn- projected-node?
  [node]
  (or (= :seon.print/projected (:seon.print/face node))
      (some projected-node? (child-nodes node))))

(defn- entry-attribute
  [entry]
  (when (and (vector? entry)
             (= :seon.print/keyword (:seon.print/face (first entry))))
    (:seon.print/value (first entry))))

(defn- ordered-map-node
  [node attributes]
  (let [rank (zipmap attributes (range))]
    (update node :seon.print/entries
            #(->> %
                  (sort-by (fn [entry]
                             (if-let [attribute (entry-attribute entry)]
                               [(get rank attribute Long/MAX_VALUE)
                                (if (get rank attribute) "" (pr-str attribute))]
                               [Long/MAX_VALUE "\uffff"])))
                  vec))))

(defn- layout-tree
  [tree {:seon.render.value/keys [layout attributes] :as registered}]
  (when-not (projected-node? tree)
    (case layout
      :map
      (when (= :seon.print/map (:seon.print/face tree))
        [(ordered-map-node tree attributes) registered])

      :map-collection
      (when (contains? #{:seon.print/vector :seon.print/list :seon.print/set}
                       (:seon.print/face tree))
        (let [items (:seon.print/items tree)]
          (when (every? #(contains? #{:seon.print/map :seon.print/elided}
                                    (:seon.print/face %))
                        items)
            [(update tree :seon.print/items
                     (fn [nodes]
                       (mapv #(if (= :seon.print/map (:seon.print/face %))
                                (ordered-map-node % attributes)
                                %)
                             nodes)))
             registered])))

      nil)))

(defn- attribute-label
  [attribute-node duplicated-names options]
  (let [attribute (:seon.print/value attribute-node)]
    (if (qualified-keyword? attribute)
      (if (contains? duplicated-names (name attribute))
        (str attribute)
        (name attribute))
      (print/emit-text attribute-node options))))

(defn- map-components
  [node options]
  (let [entries (:seon.print/entries node)
        duplicated-names
        (->> entries
             (keep entry-attribute)
             (group-by name)
             (keep (fn [[attribute-name attributes]]
                     (when (< 1 (count attributes)) attribute-name)))
             set)]
    (mapv (fn [entry]
            (if (vector? entry)
              {:seon.render.value/label
               (attribute-label (first entry) duplicated-names options)
               :seon.render.value/value
               (print/emit-text (second entry) options)}
              {:seon.render.value/elision (print/emit-text entry options)}))
          entries)))

(defn- components-text
  [components]
  (str/join
   ", "
   (map (fn [{:seon.render.value/keys [label value elision]}]
          (or elision (str label ": " value)))
        components)))

(defn- map-html
  [components]
  (into [:dl {:class "seon-data-map"}]
        (mapcat
         (fn [{:seon.render.value/keys [label value elision]}]
           (if elision
             [[:dt {:class "seon-data-key"} "\u2026"]
              [:dd {:class "seon-data-value"} elision]]
             [[:dt {:class "seon-data-key"} label]
              [:dd {:class "seon-data-value"} value]])))
        components))

(defn- layout-emission
  [tree layout options]
  (let [options (assoc options :seon.print/table? false :seon.print/width 0)]
    (case layout
      :map
      (when (= :seon.print/map (:seon.print/face tree))
        (let [components (map-components tree options)]
          {:seon.print/text (components-text components)
           :seon.print/hiccup (map-html components)}))

      :map-collection
      (when (contains? #{:seon.print/vector :seon.print/list :seon.print/set}
                       (:seon.print/face tree))
        (let [rows
              (mapv (fn [item]
                      (if (= :seon.print/map (:seon.print/face item))
                        (components-text (map-components item options))
                        (print/emit-text item options)))
                    (:seon.print/items tree))]
          {:seon.print/text (str/join "\n" rows)
           :seon.print/hiccup
           (into [:ol {:class "seon-data-list"}]
                 (map (fn [row] [:li row]))
                 rows)}))

      nil)))

(defn- breadcrumbs
  [unit path]
  (when (:seon.render.value/route-base unit)
    [:nav {:class "seon-data-crumbs"}
     (path-link unit [] 0 "root" "seon-data-crumb")
     (map (fn [index]
            (path-link unit (subvec path 0 (inc index)) 0
                       (pr-str (nth path index)) "seon-data-crumb"))
          (range (count path)))]))

(defn- pager
  [unit path {:seon.render.value/keys [offset shown total more?]}]
  (when (:seon.render.value/route-base unit)
    [:div {:class "seon-data-pager"}
       (when (pos? offset)
         (path-link unit path 0
                    "← previous" "seon-data-page"))
       [:span {:class "seon-data-range"}
        (str "showing " (if (zero? shown) 0
                            (str (inc offset) "–" (+ offset shown)))
             (when total (str " of " total)))]
       (when more?
         (path-link unit path (+ offset shown) "next →" "seon-data-page"))]))

(defn prepare
  "Admit once, recursively dispatch declared producers, fit, then emit."
  {:malli/schema
   [:function
    [:=> [:cat :seon.render/unit]
     [:or :nil :seon.render.value/projection :seon.error/value]]
    [:=> [:cat :seon.render/unit :seon.render/output]
     [:or :nil :seon.render.value/projection :seon.error/value]]]}
  ([unit]
   (prepare unit :seon.render/ai))
  ([unit output]
   (when (:seon.sci.admit/caps unit)
    (let [display (display-value unit)
          admitted (if-let [result-edn (:seon.cluster.eval/result-edn unit)]
                     (let [tree (edn/read-string result-edn)]
                       {:seon.render.value/tree tree
                        :seon.render.value/semantic
                        (admit/semantic-value tree)
                        :seon.render.value/truncated? false})
                     (admitted-projection
                      (:seon.render.value/window display) unit))
          registered (registered-layout unit
                                        (:seon.render.value/semantic admitted))
          profile (cond-> (render-profile unit)
                    (or (:seon.render.data/total unit)
                        (:seon.render.value/total display))
                    (assoc :seon.render.data/total
                           (or (:seon.render.data/total unit)
                               (:seon.render.value/total display))))
          projected-tree (if (get-in unit [:seon.render.value/options
                                           :seon.render.value/structural?])
                           (:seon.render.value/tree admitted)
                           ((requiring-resolve 'seon.render/project-node)
                            unit
                            (:seon.render.value/semantic admitted)
                            (:seon.render.value/tree admitted)
                            output))
          [projected-tree registered]
          (or (when registered (layout-tree projected-tree registered))
              [projected-tree nil])
          tree (-> projected-tree
                   (print/enrich-elisions profile)
                   (print/fit profile))
          options (cond-> (assoc (print-options unit)
                                 :seon.print/length nil
                                 :seon.print/level nil)
                    (= :single-line
                       (:seon.render.profile/composition profile))
                    (assoc :seon.print/width 0 :seon.print/table? false)

                    (= :tabular
                       (:seon.render.profile/composition profile))
                    (assoc :seon.print/table? true))
          emitted (or (when registered
                        (layout-emission
                         tree (:seon.render.value/layout registered) options))
                      (print/emit-both tree options))
          truncated? (boolean
                      (or (:seon.render.value/truncated? admitted)
                          (:seon.render.value/more? display)
                          (pos? (:seon.render.value/offset display))))
          path (vec (get-in unit [:seon.render.data/cursor
                                  :seon.render.data/path] []))
          id (node-id unit path)]
      (if (:seon.error/kind id)
        id
        {:seon.render.value/tree tree
         :seon.render.value/options options
         :seon.render.value/truncated? truncated?
         :seon.render.value/text (:seon.print/text emitted)
         :seon.render.value/html
         [:div {:id id :class "seon-data-panel"}
          (breadcrumbs unit path)
          (pager unit path display)
          (:seon.print/hiccup emitted)
          (when truncated?
            [:p {:class "seon-data-capped"}
             "elided — this value is larger than the configured window"]) ]})))))

(defn render-ai-data
  "Return the text sink result from one already prepared projection."
  {:malli/schema [:=> [:cat :seon.render.value/projection] :string]}
  [projection]
  (str (:seon.render.value/text projection)
       (when (:seon.render.value/truncated? projection)
         " ; elided — this value is larger than the configured window")))

(defn render-html-data
  "Return the hiccup sink result from one already prepared projection."
  {:malli/schema [:=> [:cat :seon.render.value/projection]
                  :seon.render/hiccup]}
  [projection]
  (:seon.render.value/html projection))

(defn artifact
  "Select the one durable value artifact from an admission result.

  The print node is the sole value source. Semantic data and printable EDN
  are derived when read and are never stored beside it."
  {:malli/schema [:=> [:cat :map] :seon.render.value/artifact]}
  [admitted]
  (select-keys admitted
               [:seon.sci.admit/print-node
                :seon.sci.admit/capped?
                :seon.sci.admit/record]))

(defn artifact-edn
  "Serialize one value artifact with canonical print bindings."
  {:malli/schema [:=> [:cat :seon.render.value/artifact] :string]}
  [stored]
  (admit/canonical-edn stored))

(defn read-artifact
  "Read one stored value artifact."
  {:malli/schema [:=> [:cat :string] :seon.render.value/artifact]}
  [content]
  (edn/read-string content))

(defn artifact-value
  "Derive semantic drill data from an artifact's sole print node."
  {:malli/schema [:=> [:cat :seon.render.value/artifact] :any]}
  [stored]
  (admit/semantic-value (:seon.sci.admit/print-node stored)))

(defn artifact-result-edn
  "Derive receipt EDN from an artifact's sole print node."
  {:malli/schema [:=> [:cat :seon.render.value/artifact]
                  :seon.cluster.eval/result-edn]}
  [stored]
  (admit/print-node-edn (:seon.sci.admit/print-node stored)))

(defn result-window-edn
  "Store a small tagged data window beside an oversized result blob."
  {:malli/schema
   [:=> [:cat :seon.render/unit :seon.cluster.eval/result-edn]
    :seon.cluster.eval/result-edn]}
  [unit result-edn]
  (let [parsed (edn/read-string result-edn)
        node (if (and (map? parsed) (contains? parsed :seon.print/face))
               parsed
               (:seon.render.value/tree
                (admitted-projection parsed unit)))]
    (admit/print-node-edn
     (print/fit node
                (assoc (render-profile unit)
                       :seon.render.profile/max-children
                       (max 0
                            (dec (long
                                  (get-in unit
                                          [:seon.sci.admit/caps
                                           :seon.config.eval.result/max-collection])))))))))

(defn- render-prepared
  [unit output]
  (if-let [projection (prepare unit output)]
    (if (:seon.error/kind projection)
      projection
      (if (= output :seon.render/html)
        (render-html-data projection)
        (render-ai-data projection)))
    (if (= output :seon.render/html)
      [:div {:class "seon-error-card"}
       [:span {:class "seon-error-card-message"}
        (str "This panel needs :seon.sci.admit/caps on the unit; without "
             "them nothing bounds what it would print.")]]
      (str "This projection needs :seon.sci.admit/caps on the unit; without "
           "them nothing bounds what it would say."))))

(defn render-ai
  "Render any floor unit through the admitted text sink."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :string :seon.error/value]]}
  [unit]
  (render-prepared unit :seon.render/ai))

(defn render-html
  "Render any floor unit through the admitted hiccup sink."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.error/value]]}
  [unit]
  (render-prepared unit :seon.render/html))
