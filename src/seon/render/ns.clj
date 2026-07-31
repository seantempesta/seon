(ns seon.render.ns
  "The default namespace lens over the database program graph.

  Distance is representation: zero names the namespace, one renders its
  declared function signatures and first docstring lines, and deeper views
  render the exact stored function spans. Both projections derive from the
  same queried rows."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ai.tokens :as tokens]
            [seon.cluster.agent :as agent]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]))

;;; ---------------------------------------------------------------------------
;;; Concrete program-graph reads
;;; ---------------------------------------------------------------------------

(def ^:private namespace-selector
  [:seon.ns/name
   :seon.ns/requires
   {:seon.ns/aliases
    [:seon.ns.alias/local :seon.ns.alias/target-ns]}])

(defn- namespace-row
  [unit]
  (let [db (:seon.db/db unit)
        eid (:db/id unit)]
    (if (and db eid)
      (merge unit (d/pull db namespace-selector eid))
      unit)))

(defn- function-rows
  [db namespace-eid distance]
  (if-not (and db namespace-eid (pos? distance))
    []
    (let [rows
          (d/q
           (if (= 1 distance)
             '[:find [(pull ?function
                            [:seon.fn/sym
                             :seon.fn/arglists
                             :seon.fn/doc
                             :seon.fn/private?
                             :seon.fn/spec]) ...]
               :in $ ?namespace
               :where [?function :seon.fn/ns ?namespace]]
             '[:find [(pull ?function
                            [:seon.fn/sym :seon.fn/source]) ...]
               :in $ ?namespace
               :where [?function :seon.fn/ns ?namespace]])
           db
           namespace-eid)]
      (sort-by :seon.fn/sym
               (if (= 1 distance)
                 (remove :seon.fn/private? rows)
                 rows)))))

;;; ---------------------------------------------------------------------------
;;; One deterministic namespace representation
;;; ---------------------------------------------------------------------------

(defn- first-doc-line
  [doc]
  (some-> doc str/split-lines first str/trim not-empty))

(defn- read-edn
  [text]
  (when (and (string? text) (not (str/blank? text)))
    (try
      (edn/read-string text)
      (catch Throwable _
        nil))))

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
  (let [aliases-by-target
        (group-by :seon.ns.alias/target-ns aliases)
        targets (sort-by str
                         (into (set requires) (keys aliases-by-target)))]
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
  (let [row (namespace-row unit)
        db (:seon.db/db unit)
        namespace-name (or (:seon.ns/name row) 'unknown.namespace)
        distance (max 0 (long (get unit :seon.render/distance 1)))
        functions (function-rows db (:db/id unit) distance)]
    {::namespace-name namespace-name
     ::distance distance
     ::requires (vec (require-specs row))
     ::functions (vec functions)
     ::owner-agent-id (when (and db (empty? functions))
                        (agent/owner-of db namespace-name))}))

;;; ---------------------------------------------------------------------------
;;; Bounded, whole-form assembly
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
  [distance function]
  (if (= 1 distance)
    (pr-str (signature-form function))
    (:seon.fn/source function)))

(defn- ai-text
  [{::keys [namespace-name distance requires functions owner-agent-id]}
   included-count]
  (if (zero? distance)
    (str namespace-name)
    (let [included (subvec functions 0 included-count)
          omitted (- (count functions) included-count)]
      (str/join
       "\n\n"
       (cond-> [(pr-str (ns-form namespace-name requires))]
         (seq included) (into (map #(function-source distance %) included))
         (and (empty? functions) (zero? omitted))
         (conj (empty-comment owner-agent-id))
         (pos? omitted) (conj (omission-comment 0 omitted)))))))

(defn- minimal-ai-text
  [{::keys [namespace-name requires functions owner-agent-id]}]
  (str/join
   "\n\n"
   (cond-> [(str namespace-name)]
     (or (seq requires) (seq functions))
     (conj (omission-comment (count requires) (count functions)))
     (and (empty? requires) (empty? functions))
     (conj (empty-comment owner-agent-id)))))

(defn- budgeted-ai
  [data budget]
  (let [function-count (count (::functions data))]
    (if (or (nil? budget) (zero? (::distance data)))
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

(defn- function-html
  [distance function]
  [[:dt {:id (function-id function)
         :class "seon-namespace-function-name"}
    [:code (:seon.fn/sym function)]]
   [:dd {:class "seon-namespace-function-definition"}
    [:pre [:code (function-source distance function)]]]])

(defn- html-view
  [{::keys [namespace-name distance requires functions owner-agent-id]}
   included-count]
  (let [included (subvec functions 0 included-count)
        omitted (- (count functions) included-count)]
    (into
     [:section {:class "seon-family-entry seon-namespace-entry"}
      [:h2 [:code (str namespace-name)]]]
     (cond-> []
       (and (pos? distance) (seq requires))
       (conj [:p {:class "seon-namespace-requires"}
              "Requires "
              [:code (pr-str (vec requires))]])

       (seq included)
       (conj (into [:dl {:class "seon-namespace-definitions"}]
                   (mapcat #(function-html distance %) included)))

       (and (pos? distance) (empty? functions))
       (conj [:p {:class "seon-namespace-empty"}
              (str "No definitions yet"
                   (when owner-agent-id
                     (str "; owned by agent " owner-agent-id))
                   ".")])

       (pos? omitted)
       (conj [:p {:class "seon-namespace-elision"}
              (omission-comment 0 omitted)])))))

(defn- minimal-html-view
  [{::keys [namespace-name requires functions owner-agent-id]}]
  [:section {:class "seon-family-entry seon-namespace-entry"}
   [:h2 [:code (str namespace-name)]]
   [:p {:class "seon-namespace-elision"}
    (if (or (seq requires) (seq functions))
      (omission-comment (count requires) (count functions))
      (empty-comment owner-agent-id))]])

(defn- html-within-budget?
  [view budget]
  (within-budget? (hiccup/->string view) budget))

(defn- budgeted-html
  [data budget]
  (let [function-count (count (::functions data))]
    (if (or (nil? budget) (zero? (::distance data)))
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
