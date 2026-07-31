(ns seon.render.ns
  "The default namespace lens over the database program graph.

  Distance is inverse detail: zero names the namespace, one renders its
  authoritative stored source and referenced schemas, and deeper views render
  a compact public card. Both projections derive from the same database value."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
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

(defn- namespace-row
  [unit]
  (let [db (:seon.db/db unit)
        eid (:db/id unit)]
    (if (and db eid)
      (merge unit (d/pull db namespace-selector eid))
      unit)))

(defn- function-rows
  [db namespace-eid]
  (if-not (and db namespace-eid)
    []
    (let [rows
          (d/q
           '[:find [(pull ?function selector) ...]
             :in $ ?namespace selector
             :where [?function :seon.fn/ns ?namespace]]
           db
           namespace-eid
           function-selector)]
      (vec (sort-by :seon.fn/sym rows)))))

(defn- own-schema-rows
  [db namespace-name distance]
  (if-not (and db namespace-name (pos? distance))
    []
    (->> (d/q
          '[:find [(pull ?schema selector) ...]
            :in $ ?namespace-name selector
            :where
            [?schema :seon.schema/key ?key]
            [(namespace ?key) ?key-namespace]
            [(= ?key-namespace ?namespace-name)]]
          db
          (str namespace-name)
          schema-selector)
         (sort-by (comp str :seon.schema/key))
         vec)))

(defn- schema-row
  [db schema-key]
  (when (and db (qualified-keyword? schema-key))
    (d/pull db schema-selector [:seon.schema/key schema-key])))

(defn- cached-schema-row
  [db cache schema-key]
  (if (contains? @cache schema-key)
    (get @cache schema-key)
    (let [row (schema-row db schema-key)]
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
  [db cache seed-specs own-rows]
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
            (let [definition
                  (or (get definitions schema-key)
                      (some-> (cached-schema-row db cache schema-key)
                              schema-definition))
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
                       definitions')))))))))

(defn- schema-definition-text
  [{::keys [schema-source schema-form]}]
  (if (some? schema-form) (pr-str schema-form) schema-source))

(defn- schema-registration-line
  [schema-key definition]
  (str "(register! " (pr-str schema-key) " "
       (schema-definition-text definition) ")"))

(defn- referenced-schema-lines
  [db cache functions own-rows]
  (let [closure
        (referenced-schema-closure
         db cache
         (into [] (keep :seon.fn/spec) functions)
         own-rows)
        definitions (::schema-definitions closure)]
    {::schema-lines
     (mapv (fn [schema-key]
             (schema-registration-line schema-key (get definitions schema-key)))
           (::schema-keys closure))
     ::schemas-capped? (::schemas-capped? closure)}))

;;; ---------------------------------------------------------------------------
;;; One deterministic namespace representation
;;; ---------------------------------------------------------------------------

(defn- first-doc-line
  [doc]
  (some-> doc str/split-lines first str/trim not-empty))

(defn- soft-clip
  [text limit]
  (let [marker " [clipped]"
        text (str/replace text "…" "...")]
    (if (> (count text) limit)
      (str (subs text 0 (max 0 (- limit (count marker)))) marker)
      text)))

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

(defn- bare-source?
  [namespace-name source]
  (or (str/blank? source)
      (= (str/trim source) (str "(ns " namespace-name ")"))))

(defn- render-data
  [unit]
  (let [row (namespace-row unit)
        db (:seon.db/db unit)
        namespace-name (or (:seon.ns/name row) 'unknown.namespace)
        distance (max 0 (long (get unit :seon.render/distance 1)))
        all-functions (if (pos? distance)
                        (function-rows db (:db/id unit))
                        [])
        functions (if (= 1 distance)
                    all-functions
                    (vec (remove :seon.fn/private? all-functions)))
        own-schemas (own-schema-rows db namespace-name distance)]
    {::db db
     ::namespace-name namespace-name
     ::namespace-source (:seon.ns/source row)
     ::distance distance
     ::requires (vec (require-specs row))
     ::functions functions
     ::own-schemas own-schemas
     ::schema-row-cache
     (atom (into {} (map (juxt :seon.schema/key identity)) own-schemas))
     ::owner-agent-id (when (and db
                                (empty? functions)
                                (empty? own-schemas))
                        (agent/owner-of db namespace-name))}))

;;; ---------------------------------------------------------------------------
;;; Bounded, whole-section assembly
;;; ---------------------------------------------------------------------------

(defn- token-budget
  [unit]
  (some-> (::token-budget unit) long (max 1)))

(defn- within-budget?
  [text budget]
  (or (nil? budget)
      (<= (tokens/estimate text) budget)))

(defn- omission-comment
  [requires-count definitions-count]
  (str ";; "
       (when (pos? requires-count)
         (str requires-count " require declaration"
              (when (not= 1 requires-count) "s")
              (when (pos? definitions-count) " and ")))
       (when (pos? definitions-count)
         (str definitions-count " definition"
              (when (not= 1 definitions-count) "s")))
       " omitted by the namespace render budget."))

(defn- empty-comment
  [owner-agent-id]
  (str ";; no definitions yet"
       (when owner-agent-id (str "; owned by agent " owner-agent-id))
       "."))

(defn- function-source
  [function]
  (or (:seon.fn/source function) (pr-str (signature-form function))))

(defn- compact-schema-line
  [row]
  (str "schema " (pr-str (:seon.schema/key row)) " = "
       (schema-definition-text (schema-definition row))))

(defn- compact-function-line
  [{:seon.fn/keys [sym doc spec]}]
  (str "fn " sym " — "
       (if (str/blank? spec) "<no contract>" spec)
       (when-let [summary (first-doc-line doc)]
         (str " — " (pr-str (soft-clip summary 78))))))

(defn- referenced-schema-ai-section
  [db schema-row-cache functions own-schemas compact?]
  (let [{::keys [schema-lines schemas-capped?]}
        (referenced-schema-lines db schema-row-cache functions own-schemas)]
    (when (or (seq schema-lines) schemas-capped?)
      (str/join
       "\n"
       (cond-> [(if compact?
                  "; referenced schemas"
                  ";; referenced schemas")]
         (seq schema-lines)
         (into (if compact?
                 (mapv #(str "; " %) schema-lines)
                 schema-lines))
         schemas-capped?
         (conj (str "; " referenced-schema-cap
                    "+ referenced schemas capped; more reachable via the db")))))))

(defn- full-ai-text
  [{::keys [db schema-row-cache namespace-name namespace-source requires
            functions own-schemas owner-agent-id]}]
  (let [stub? (bare-source? namespace-name namespace-source)
        source (if (str/blank? namespace-source)
                 (pr-str (ns-form namespace-name requires))
                 namespace-source)
        member-parts
        (when stub?
          (concat (map function-source functions)
                  (map (fn [row]
                         (schema-registration-line
                          (:seon.schema/key row)
                          (schema-definition row)))
                       own-schemas)))
        schema-section
        (referenced-schema-ai-section
         db schema-row-cache functions own-schemas false)]
    (str/join
     "\n\n"
     (cond-> [source]
       (seq member-parts) (into member-parts)
       schema-section (conj schema-section)
       (and stub? (empty? functions) (empty? own-schemas))
       (conj (empty-comment owner-agent-id))))))

(defn- compact-ai-text
  [{::keys [db schema-row-cache namespace-name requires functions own-schemas
            owner-agent-id]}
   included-count]
  (let [included (subvec functions 0 included-count)
        omitted (- (count functions) included-count)
        schema-section
        (referenced-schema-ai-section
         db schema-row-cache included own-schemas true)]
    (str/join
     "\n\n"
     (cond-> [(pr-str (ns-form namespace-name requires))]
       (seq own-schemas)
       (conj (str/join "\n" (map #(str "; " (compact-schema-line %))
                                   own-schemas)))
       schema-section (conj schema-section)
       (seq included)
       (conj (str/join "\n" (map #(str "; " (compact-function-line %))
                                   included)))
       (and (empty? functions) (empty? own-schemas))
       (conj (empty-comment owner-agent-id))
       (pos? omitted) (conj (omission-comment 0 omitted))))))

(defn- ai-text
  [data included-count]
  (case (::distance data)
    0 (str (::namespace-name data))
    1 (full-ai-text data)
    (compact-ai-text data included-count)))

(defn- minimal-ai-text
  [{::keys [namespace-name requires functions own-schemas owner-agent-id]}]
  (str/join
   "\n\n"
   (cond-> [(str namespace-name)]
     (or (seq requires) (seq functions) (seq own-schemas))
     (conj (omission-comment
            (count requires)
            (+ (count functions) (count own-schemas))))
     (and (empty? requires) (empty? functions) (empty? own-schemas))
     (conj (empty-comment owner-agent-id)))))

(defn- budgeted-ai
  [data budget]
  (let [function-count (count (::functions data))]
    (if (or (nil? budget) (< (::distance data) 2))
      (ai-text data function-count)
      (let [initial (ai-text data 0)]
        (if-not (within-budget? initial budget)
          (minimal-ai-text data)
          (loop [included 0]
            (let [next-count (inc included)
                  candidate (when (<= next-count function-count)
                              (ai-text data next-count))]
              (if (and candidate (within-budget? candidate budget))
                (recur next-count)
                (ai-text data included)))))))))

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
  [db schema-row-cache functions own-schemas]
  (let [{::keys [schema-lines schemas-capped?]}
        (referenced-schema-lines db schema-row-cache functions own-schemas)]
    (when (or (seq schema-lines) schemas-capped?)
      [:section {:class "seon-namespace-referenced-schemas"}
       [:h3 "Referenced schemas"]
       [:pre
        [:code
         (str/join
          "\n"
          (cond-> (vec schema-lines)
            schemas-capped?
            (conj (str referenced-schema-cap
                       "+ referenced schemas capped; more reachable via the db"))))]]])))

(defn- full-html-view
  [{::keys [db schema-row-cache namespace-name namespace-source requires
            functions own-schemas owner-agent-id]}]
  (let [stub? (bare-source? namespace-name namespace-source)
        source (if (str/blank? namespace-source)
                 (pr-str (ns-form namespace-name requires))
                 namespace-source)
        schema-section
        (referenced-schema-html db schema-row-cache functions own-schemas)]
    (into
     [:section {:class "seon-family-entry seon-namespace-entry"}
      [:h2 [:code (str namespace-name)]]
      [:pre {:class "seon-namespace-source"} [:code source]]]
     (cond-> []
       (and stub? (seq functions))
       (conj (into [:dl {:class "seon-namespace-definitions"}]
                   (mapcat full-function-html functions)))
       (and stub? (seq own-schemas))
       (conj [:pre {:class "seon-namespace-own-schemas"}
              [:code (str/join "\n" (map compact-schema-line own-schemas))]])
       schema-section (conj schema-section)
       (and stub? (empty? functions) (empty? own-schemas))
       (conj [:p {:class "seon-namespace-empty"}
              (str "No definitions yet"
                   (when owner-agent-id
                     (str "; owned by agent " owner-agent-id))
                   ".")])))))

(defn- compact-html-view
  [{::keys [db schema-row-cache namespace-name requires functions own-schemas
            owner-agent-id]}
   included-count]
  (let [included (subvec functions 0 included-count)
        omitted (- (count functions) included-count)
        schema-section
        (referenced-schema-html db schema-row-cache included own-schemas)]
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
              (str "No definitions yet"
                   (when owner-agent-id
                     (str "; owned by agent " owner-agent-id))
                   ".")])
       (pos? omitted)
       (conj [:p {:class "seon-namespace-elision"}
              (omission-comment 0 omitted)])))))

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
      (omission-comment
       (count requires)
       (+ (count functions) (count own-schemas)))
      (empty-comment owner-agent-id))]])

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

(defn render-ai
  "Render a namespace as valid, distance-sensitive Clojure."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (let [data (render-data unit)]
    (budgeted-ai data (token-budget unit))))

(defn render-html
  "Render the namespace's same definitions as stable HTML entries."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (let [data (render-data unit)]
    (budgeted-html data (token-budget unit))))
