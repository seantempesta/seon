(ns seon.render.ns
  "The default namespace lens over the database program graph.

  Distance is inverse detail: zero names the namespace, one renders its
  authoritative stored source and referenced schemas, and deeper views render
  a compact public card. Both projections derive from the same database value."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.db :as db]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.ai.tokens :as tokens]
            [seon.cluster.agent :as agent]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]))

;;; ---------------------------------------------------------------------------
;;; Concrete program-graph reads
;;; ---------------------------------------------------------------------------

(def ^:private namespace-selector
  [:seon.ns/name
   :seon.ns/source
   {:seon.ns/requires [:seon.ns/name]}
   {:seon.ns/aliases
    [:seon.ns.alias/local :seon.ns.alias/target-ns]}])

(def ^:private function-selector
  [:seon.fn/sym
   :seon.fn/arglists
   :seon.fn/doc
   :seon.fn/private?
   :seon.fn/spec
   :seon.fn/source])

(def ^:private schema-selector
  [:seon.schema/key :seon.schema/form])

(defn- read-bounds
  [unit]
  (let [caps (:seon.sci.admit/caps unit)
        max-nodes (:seon.config.eval.result/max-nodes caps)
        max-collection (:seon.config.eval.result/max-collection caps)]
    (cond-> {}
      (pos-int? max-nodes)
      (assoc :max-work max-nodes :max-result-weight max-nodes)

      (pos-int? max-collection)
      (assoc :max-results max-collection))))

(defn- error-value?
  [value]
  (and (map? value) (keyword? (:seon.error/kind value))))

(defn- namespace-row
  [unit]
  (let [db (:seon.db/db unit)
        eid (:db/id unit)]
    (if (and db eid)
      (let [row (db/pull db (merge (read-bounds unit)
                                   {:selector namespace-selector
                                    :eid eid}))]
        (if (error-value? row) row (merge unit row)))
      unit)))

(defn- function-rows
  [db bounds namespace-eid]
  (if-not (and db namespace-eid)
    []
    (let [rows
          (db/q
           (merge bounds
                  {:query
                   '[:find [(pull ?function selector) ...]
                     :in $ ?namespace selector
                     :where [?function :seon.fn/ns ?namespace]]
                   :args [db namespace-eid function-selector]}))]
      (if (error-value? rows)
        rows
        (vec (sort-by :seon.fn/sym rows))))))

(defn- own-schema-rows
  [db bounds namespace-name distance]
  (if-not (and db namespace-name (pos? distance))
    []
    (let [rows
          (db/q
           (merge bounds
                  {:query
                   '[:find [(pull ?schema selector) ...]
                     :in $ ?namespace-name selector
                     :where
                     [?schema :seon.schema/key ?key]
                     [(namespace ?key) ?key-namespace]
                     [(= ?key-namespace ?namespace-name)]]
                   :args [db (str namespace-name) schema-selector]}))]
      (if (error-value? rows)
        rows
        (->> rows (sort-by (comp str :seon.schema/key)) vec)))))

(defn- schema-row
  [db bounds schema-key]
  (when (and db (qualified-keyword? schema-key))
    (db/pull db (merge bounds
                       {:selector schema-selector
                        :eid [:seon.schema/key schema-key]}))))

(defn- cached-schema-row
  [db bounds cache schema-key]
  (if (contains? @cache schema-key)
    (get @cache schema-key)
    (let [row (schema-row db bounds schema-key)]
      (swap! cache assoc schema-key row)
      row)))

;;; ---------------------------------------------------------------------------
;;; Malli-grounded referenced-schema closure
;;; ---------------------------------------------------------------------------

(def ^:private referenced-schema-cap 40)

(def ^:private schema-ref-registry
  (mr/composite-registry
   (mr/fast-registry (m/default-schemas))
   (reify mr/Registry
     (-schema [_ schema-type]
       (when (qualified-keyword? schema-type) :any))
     (-schemas [_] {}))))

(defn- read-edn
  [text]
  (when (and (string? text) (not (str/blank? text)))
    (try
      (edn/read-string text)
      (catch Throwable _
        nil))))

(defn- schema-form-refs
  [form]
  (let [references (atom #{})]
    (try
      (m/walk
       (m/schema form {:registry schema-ref-registry})
       (fn [schema _ _ _]
         (when (m/-ref-schema? schema)
           (let [reference (m/-ref schema)]
             (when (qualified-keyword? reference)
               (swap! references conj reference))))
         schema))
      (catch Throwable _
        nil))
    @references))

(defn- normalize-schema-form
  [source]
  (let [form (read-edn source)]
    (if (and (seq? form)
             (#{'register! 'seon.schema/register!} (first form))
             (keyword? (second form))
             (<= 3 (count form)))
      (nth form 2)
      form)))

(defn- schema-definition
  [row]
  (when-let [source (:seon.schema/form row)]
    {::schema-source source
     ::schema-form (normalize-schema-form source)}))

(defn- referenced-schema-closure
  [db bounds cache seed-specs own-rows]
  (let [seed-keys
        (reduce
         (fn [schema-keys source]
           (if-let [form (read-edn source)]
             (into schema-keys (schema-form-refs form))
             schema-keys))
         #{}
         seed-specs)
        own-keys (into #{} (map :seon.schema/key) own-rows)
        initial-definitions
        (into {}
              (keep (fn [row]
                      (when-let [definition (schema-definition row)]
                        [(:seon.schema/key row) definition])))
              own-rows)]
    (loop [queue (vec (sort seed-keys))
           seen #{}
           emitted #{}
           definitions initial-definitions]
      (if (empty? queue)
        {::schema-keys (vec (sort emitted))
         ::schema-definitions definitions
         ::schemas-capped? false}
        (let [schema-key (first queue)
              remaining (subvec queue 1)]
          (if (contains? seen schema-key)
            (recur remaining seen emitted definitions)
            (let [row (when-not (get definitions schema-key)
                        (cached-schema-row db bounds cache schema-key))]
              (if (error-value? row)
                row
                (let [definition
                      (or (get definitions schema-key)
                          (schema-definition row))
                      child-keys
                      (or (some-> definition ::schema-form schema-form-refs) #{})
                      seen' (conj seen schema-key)
                      emittable? (and definition
                                      (not (contains? own-keys schema-key)))
                      definitions'
                      (cond-> definitions
                        definition (assoc schema-key definition))]
                  (if (and emittable?
                           (>= (count emitted) referenced-schema-cap))
                    {::schema-keys (vec (sort emitted))
                     ::schema-definitions definitions'
                     ::schemas-capped? true}
                    (recur (->> (concat remaining child-keys)
                                (remove seen')
                                distinct
                                sort
                                vec)
                           seen'
                           (cond-> emitted emittable? (conj schema-key))
                           definitions')))))))))))

(defn- schema-definition-text
  [{::keys [schema-source schema-form]}]
  (if (some? schema-form)
    (binding [*print-namespace-maps* false]
      (pr-str schema-form))
    schema-source))

(defn- schema-registration-line
  [schema-key definition]
  (str "(register! " (pr-str schema-key) " "
       (schema-definition-text definition) ")"))

(defn- referenced-schema-summary
  [db bounds cache functions own-rows]
  (let [closure
        (referenced-schema-closure
         db bounds cache
         (into [] (keep :seon.fn/spec) functions)
         own-rows)]
    (if (error-value? closure)
      closure
      {::schema-keys (::schema-keys closure)
       ::schemas-capped? (::schemas-capped? closure)})))

;;; ---------------------------------------------------------------------------
;;; One deterministic namespace representation
;;; ---------------------------------------------------------------------------

(defn- first-doc-line
  [doc]
  (some-> doc str/split-lines first str/trim not-empty))

(defn- stored-arglists
  [serialized]
  (let [arglists (read-edn serialized)]
    (when (and (seq arglists) (every? vector? arglists))
      arglists)))

(defn- signature-form
  [{:seon.fn/keys [sym arglists doc private? spec]}]
  (let [qualified (symbol sym)
        function-name (symbol (name qualified))
        arglists (stored-arglists arglists)
        schema (read-edn spec)
        prefix (cond-> [(if private? 'defn- 'defn) function-name]
                 (first-doc-line doc) (conj (first-doc-line doc))
                 (and (nil? arglists) schema)
                 (conj {:malli/schema schema}))]
    (if (= 1 (count arglists))
      (apply list (concat prefix [(first arglists) nil]))
      (apply list
             (concat prefix
                     (if (seq arglists)
                       (map #(list % nil) arglists)
                       [['& 'arguments] nil]))))))

(defn- require-specs
  [{:seon.ns/keys [requires aliases]}]
  (let [required-names (into #{} (keep :seon.ns/name) requires)
        aliases-by-target (group-by :seon.ns.alias/target-ns aliases)
        targets (sort-by str (into required-names (keys aliases-by-target)))]
    (mapcat
     (fn [target]
       (let [target-aliases
             (sort-by (comp str :seon.ns.alias/local)
                      (get aliases-by-target target))]
         (if (seq target-aliases)
           (map (fn [alias-row]
                  [target :as (:seon.ns.alias/local alias-row)])
                target-aliases)
           [target])))
     targets)))

(defn- ns-form
  [namespace-name requires]
  (apply list
         (cond-> ['ns namespace-name]
           (seq requires) (conj (apply list :require requires)))))

(defn- render-data
  [unit]
  (let [row (namespace-row unit)]
    (if (error-value? row)
      row
      (let [db (:seon.db/db unit)
            bounds (read-bounds unit)
            namespace-name (or (:seon.ns/name row) 'unknown.namespace)
            distance (max 0 (long (get unit :seon.render/distance 1)))
            all-functions (if (pos? distance)
                            (function-rows db bounds (:db/id unit))
                            [])]
        (if (error-value? all-functions)
          all-functions
          (let [functions (if (= 1 distance)
                            all-functions
                            (vec (remove :seon.fn/private? all-functions)))
                own-schemas (own-schema-rows db bounds namespace-name distance)]
            (if (error-value? own-schemas)
              own-schemas
              {::db db
               ::read-bounds bounds
               ::namespace-name namespace-name
               ::namespace-source (:seon.ns/source row)
               ::distance distance
               ::requires (vec (require-specs row))
               ::functions functions
               ::own-schemas own-schemas
               ::profile-id (get-in unit [:seon.render/profile
                                          :seon.render.profile/id]
                                    :seon.render.profile/unspecified)
               ::schema-row-cache
               (atom (into {} (map (juxt :seon.schema/key identity))
                           own-schemas))
               ::owner-agent-id (when (and db
                                          (empty? functions)
                                          (empty? own-schemas))
                                  (agent/owner-of db namespace-name))})))))))

;;; ---------------------------------------------------------------------------
;;; Bounded, whole-section assembly
;;; ---------------------------------------------------------------------------

(defn- token-budget
  [unit]
  (some-> (get-in unit [:seon.render/profile
                        :seon.render.profile/token-budget])
          long
          (max 1)))

(defn- within-budget?
  [text budget]
  (or (nil? budget)
      (<= (tokens/estimate text) budget)))

(defn- omission-value
  [namespace-name profile-id offset requires-count definitions-count]
  (let [omitted (+ requires-count definitions-count)]
    {:seon.print/face :seon.print/elided
     :seon.print/omitted omitted
     :seon.print/elision-unit :children
     :seon.render.data/total (+ offset omitted)
     :seon.render.data/path []
     :seon.render.data/next-offset offset
     :seon.render.profile/id profile-id
     :seon.print/requery-id [:seon.ns/name namespace-name]}))

(defn- empty-value
  [owner-agent-id]
  (cond->
   {:seon.error/message
    "No indexed members are recorded for this namespace."}
    owner-agent-id
    (assoc :seon.cluster.agent/id owner-agent-id)))

(defn- omission-text
  [requires-count definitions-count]
  (str (when (pos? requires-count)
         (str requires-count " require declaration"
              (when (not= 1 requires-count) "s")
              (when (pos? definitions-count) " and ")))
       (when (pos? definitions-count)
         (str definitions-count " definition"
              (when (not= 1 definitions-count) "s")))
       " omitted by the namespace render budget."))

(defn- empty-text
  [owner-agent-id]
  (str "No indexed members are recorded for this namespace"
       (when owner-agent-id (str "; owner agent " owner-agent-id))
       "."))

(defn- function-source
  [function]
  (or (:seon.fn/source function) (pr-str (signature-form function))))

(defn- schema-error-message
  [row]
  (let [form (::schema-form (schema-definition row))]
    (when (and (vector? form) (map? (second form)))
      (:error/message (second form)))))

(defn- compact-schema-line
  [row]
  (str "schema " (pr-str (:seon.schema/key row)) " — "
       (or (schema-error-message row)
           (schema-definition-text (schema-definition row)))))

(defn- compact-function-line
  [{:seon.fn/keys [sym doc spec]}]
  (str "fn " sym " — "
       (if (str/blank? spec) "<no contract>" spec)
       (when-let [summary (first-doc-line doc)]
         (str " — " (pr-str summary)))))

(defn- compact-schema-value
  [row]
  (if-let [message (schema-error-message row)]
    {:seon.schema/key (:seon.schema/key row)
     :seon.error/message message}
    {:seon.schema/key (:seon.schema/key row)
     :seon.schema/form
     (schema-definition-text (schema-definition row))}))

(defn- compact-function-value
  [{:seon.fn/keys [sym doc spec]}]
  (cond-> {:seon.fn/sym sym}
    (not (str/blank? spec)) (assoc :seon.fn/spec spec)
    (first-doc-line doc)
    (assoc :seon.fn/doc (first-doc-line doc))))

(defn- referenced-schema-ai-section
  [db bounds schema-row-cache functions own-schemas]
  (let [summary (referenced-schema-summary db bounds schema-row-cache
                                           functions own-schemas)]
    (if (error-value? summary)
      summary
      (let [{::keys [schema-keys schemas-capped?]} summary]
        (when (or (seq schema-keys) schemas-capped?)
          (str/join
           "\n"
           (cond-> []
             (seq schema-keys)
             (into (map (fn [schema-key]
                          (pr-str {:seon.schema/key schema-key})))
                   schema-keys)
             schemas-capped?
             (conj (pr-str
                    {:seon.error/message
                     (str referenced-schema-cap
                          "+ referenced schemas are reachable through the database.")})))))))))

(defn- full-ai-text
  [{::keys [db schema-row-cache namespace-name namespace-source requires
            functions own-schemas owner-agent-id] :as data}]
  (let [bounds (::read-bounds data)
        source (if (str/blank? namespace-source)
                 (pr-str (ns-form namespace-name requires))
                 namespace-source)
        member-parts
        (concat (map function-source functions)
                (map (fn [row]
                       (schema-registration-line
                        (:seon.schema/key row)
                        (schema-definition row)))
                     own-schemas))
        schema-section
        (referenced-schema-ai-section
         db bounds schema-row-cache functions own-schemas)]
    (if (error-value? schema-section)
      schema-section
      (str/join
       "\n\n"
       (cond-> [source]
         (seq member-parts) (into member-parts)
         schema-section (conj schema-section)
         (and (empty? functions) (empty? own-schemas))
         (conj (pr-str (empty-value owner-agent-id))))))))

(defn- compact-ai-items
  [{::keys [db schema-row-cache functions own-schemas] :as data}]
  (let [bounds (::read-bounds data)
        summary (referenced-schema-summary db bounds schema-row-cache
                                           functions own-schemas)]
    (if (error-value? summary)
      summary
      (let [{::keys [schema-keys schemas-capped?]} summary]
        (vec
         (concat
          (map compact-function-value functions)
          (map compact-schema-value own-schemas)
          (map (fn [schema-key] {:seon.schema/key schema-key}) schema-keys)
          (when schemas-capped?
            [{:seon.error/message
              (str referenced-schema-cap
                   "+ referenced schemas are reachable through the database.")}])))))))

(defn- compact-ai-text
  [{::keys [namespace-name requires functions own-schemas owner-agent-id
            profile-id]}
   items included-count]
  (let [included (subvec items 0 included-count)
        omitted (- (count items) included-count)]
    (pr-str
     (cond-> [(ns-form namespace-name requires)]
       (seq included) (into included)
       (and (empty? functions) (empty? own-schemas))
       (conj (empty-value owner-agent-id))
       (pos? omitted)
       (conj (omission-value namespace-name profile-id included-count
                             0 omitted))))))

(defn- ai-text
  [data]
  (case (::distance data)
    0 (str (::namespace-name data))
    1 (full-ai-text data)
    (let [items (compact-ai-items data)]
      (if (error-value? items)
        items
        (compact-ai-text data items (count items))))))

(defn- minimal-ai-text
  [{::keys [namespace-name requires functions own-schemas owner-agent-id
            profile-id]}]
  (pr-str
   (cond-> [namespace-name]
     (or (seq requires) (seq functions) (seq own-schemas))
     (conj (omission-value namespace-name profile-id 0
                           (count requires)
                           (+ (count functions) (count own-schemas))))
     (and (empty? requires) (empty? functions) (empty? own-schemas))
     (conj (empty-value owner-agent-id)))))

(defn- budgeted-ai
  [data budget]
  (if (or (nil? budget) (< (::distance data) 2))
    (ai-text data)
    (let [items (compact-ai-items data)
          item-count (when-not (error-value? items) (count items))]
      (if (error-value? items)
        items
        (let [render #(compact-ai-text data items %)
              initial (render 0)]
          (if-not (within-budget? initial budget)
            (minimal-ai-text data)
            (loop [included 0]
              (let [next-count (inc included)
                    candidate (when (<= next-count item-count)
                                (render next-count))]
                (if (and candidate (within-budget? candidate budget))
                  (recur next-count)
                  (render included))))))))))

;;; ---------------------------------------------------------------------------
;;; HTML twin
;;; ---------------------------------------------------------------------------

(defn- function-id
  [function]
  (block/surface-id (keyword (:seon.fn/sym function))))

(defn- full-function-html
  [function]
  [[:dt {:id (function-id function)
         :class "seon-namespace-function-name"}
    [:code (:seon.fn/sym function)]]
   [:dd {:class "seon-namespace-function-definition"}
    [:pre [:code (function-source function)]]]])

(defn- compact-function-html
  [function]
  [[:dt {:id (function-id function)
         :class "seon-namespace-function-name"}
    [:code (:seon.fn/sym function)]]
   [:dd {:class "seon-namespace-function-definition"}
    [:code (compact-function-line function)]]])

(defn- referenced-schema-html
  [db bounds schema-row-cache functions own-schemas]
  (let [summary (referenced-schema-summary db bounds schema-row-cache
                                           functions own-schemas)]
    (if (error-value? summary)
      summary
      (let [{::keys [schema-keys schemas-capped?]} summary
            items
            (cond-> (mapv (fn [schema-key]
                            [:li [:code (pr-str schema-key)]])
                          schema-keys)
              schemas-capped?
              (conj [:li (str referenced-schema-cap
                              "+ referenced schemas capped; more reachable via the db")]))]
        (when (or (seq schema-keys) schemas-capped?)
          [:section {:class "seon-namespace-referenced-schemas"}
           [:h3 "Referenced schemas"]
           (into [:ul] items)])))))

(defn- full-html-view
  [{::keys [db schema-row-cache namespace-name namespace-source requires
            functions own-schemas owner-agent-id] :as data}]
  (let [bounds (::read-bounds data)
        source (if (str/blank? namespace-source)
                 (pr-str (ns-form namespace-name requires))
                 namespace-source)
        schema-section
        (referenced-schema-html db bounds schema-row-cache
                                functions own-schemas)]
    (if (error-value? schema-section)
      schema-section
      (into
       [:section {:class "seon-family-entry seon-namespace-entry"}
        [:h2 [:code (str namespace-name)]]
        [:pre {:class "seon-namespace-source"} [:code source]]]
       (cond-> []
         (seq functions)
         (conj (into [:dl {:class "seon-namespace-definitions"}]
                     (mapcat full-function-html functions)))
         (seq own-schemas)
         (conj [:pre {:class "seon-namespace-own-schemas"}
                [:code (str/join "\n" (map compact-schema-line own-schemas))]])
         schema-section (conj schema-section)
         (and (empty? functions) (empty? own-schemas))
         (conj [:p {:class "seon-namespace-empty"}
                (empty-text owner-agent-id)]))))))

(defn- compact-html-view
  [{::keys [db schema-row-cache namespace-name requires functions own-schemas
            owner-agent-id] :as data}
   included-count]
  (let [bounds (::read-bounds data)
        included (subvec functions 0 included-count)
        omitted (- (count functions) included-count)
        schema-section
        (referenced-schema-html db bounds schema-row-cache
                                included own-schemas)]
    (if (error-value? schema-section)
      schema-section
      (into
       [:section {:class "seon-family-entry seon-namespace-entry"}
        [:h2 [:code (str namespace-name)]]]
       (cond-> []
         (seq requires)
         (conj [:p {:class "seon-namespace-requires"}
                "Requires " [:code (pr-str (vec requires))]])
         (seq own-schemas)
         (conj [:pre {:class "seon-namespace-own-schemas"}
                [:code (str/join "\n" (map compact-schema-line own-schemas))]])
         schema-section (conj schema-section)
         (seq included)
         (conj (into [:dl {:class "seon-namespace-definitions"}]
                     (mapcat compact-function-html included)))
         (and (empty? functions) (empty? own-schemas))
         (conj [:p {:class "seon-namespace-empty"}
                (empty-text owner-agent-id)])
         (pos? omitted)
         (conj [:p {:class "seon-namespace-elision"}
                (omission-text 0 omitted)]))))))

(defn- html-view
  [data included-count]
  (case (::distance data)
    0 [:section {:class "seon-family-entry seon-namespace-entry"}
       [:h2 [:code (str (::namespace-name data))]]]
    1 (full-html-view data)
    (compact-html-view data included-count)))

(defn- minimal-html-view
  [{::keys [namespace-name requires functions own-schemas owner-agent-id]}]
  [:section {:class "seon-family-entry seon-namespace-entry"}
   [:h2 [:code (str namespace-name)]]
    [:p {:class "seon-namespace-elision"}
    (if (or (seq requires) (seq functions) (seq own-schemas))
      (omission-text
       (count requires)
       (+ (count functions) (count own-schemas)))
      (empty-text owner-agent-id))]])

(defn- html-within-budget?
  [view budget]
  (within-budget? (hiccup/->string view) budget))

(defn- budgeted-html
  [data budget]
  (let [function-count (count (::functions data))]
    (if (or (nil? budget) (< (::distance data) 2))
      (html-view data function-count)
      (let [initial (html-view data 0)]
        (if-not (html-within-budget? initial budget)
          (minimal-html-view data)
          (loop [included 0]
            (let [next-count (inc included)
                  candidate (when (<= next-count function-count)
                              (html-view data next-count))]
              (if (and candidate (html-within-budget? candidate budget))
                (recur next-count)
                (html-view data included)))))))))

;;; ---------------------------------------------------------------------------
;;; Family defaults
;;; ---------------------------------------------------------------------------

(defn namespace-form
  "Return the ordinary `dir` form for a namespace entity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/form]}
  [unit]
  (list 'dir (list 'quote (:seon.ns/name unit))))

(defn function-form
  "Return the ordinary `doc` form for a function entity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/form]}
  [unit]
  (list 'doc (list 'quote (symbol (:seon.fn/sym unit)))))

(defn schema-form
  "Return the ordinary `doc` form for a schema entity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/form]}
  [unit]
  (list 'doc (:seon.schema/key unit)))

(defn render-ai
  "Render a namespace as valid, distance-sensitive Clojure."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or [:maybe :string] :seon.error/value]]}
  [unit]
  (let [data (render-data unit)]
    (if (error-value? data)
      data
      (budgeted-ai data (token-budget unit)))))

(defn render-html
  "Render the namespace's same definitions as stable HTML entries."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or [:maybe :seon.render/hiccup] :seon.error/value]]}
  [unit]
  (let [data (render-data unit)]
    (if (error-value? data)
      data
      (budgeted-html data (token-budget unit)))))
