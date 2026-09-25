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
            [seon.cluster.agent :as agent]
            [seon.issue :as issue]
            [seon.repl :as repl]
            [seon.render.block :as block]
            [seon.render.route :as route]))

;;; ---------------------------------------------------------------------------
;;; Concrete program-graph reads
;;; ---------------------------------------------------------------------------

(def ^:private namespace-selector
  [:seon.ns/name
   :seon.ns/source
   :seon.ns/doc
   :seon.ns/requires
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

(defn- read-refusal?
  "A bounded read's refusal: `seon.db`'s declared `:seon.db/invalid-read-error`,
  known by its distinguishing member. Sections are also strings and hiccup
  vectors, which are never refusals."
  [value]
  (and (map? value) (contains? value :seon.db/invalid-read)))

(defn- namespace-row
  [unit]
  (let [db (:seon.db/db unit)
        eid (:db/id unit)]
    (if (and db eid)
      (let [row (db/pull db (merge (read-bounds unit)
                                   {:selector namespace-selector
                                    :eid eid}))]
        (if (read-refusal? row) row (merge unit row)))
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
      (if (read-refusal? rows)
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
      (if (read-refusal? rows)
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
              (if (read-refusal? row)
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
    (if (read-refusal? closure)
      closure
      {::schema-keys (::schema-keys closure)
       ::schemas-capped? (::schemas-capped? closure)})))

;;; ---------------------------------------------------------------------------
;;; One deterministic namespace representation
;;; ---------------------------------------------------------------------------

(defn- first-doc-line
  [doc]
  (some-> doc str/split-lines first str/trim not-empty))

(defn- require-specs
  [{:seon.ns/keys [requires aliases]}]
  (let [required-names (set requires)
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

(defn render-alias-ai
  "Render source that says what one alias resolves to and how to look there.

  Clojure's own words for this binding are the `:as` portion of a require
  libspec, so that is what the comment states; the form is the ordinary way to
  see what the aliased namespace publishes."
  {:malli/schema [:=> [:cat :seon.ns.alias/binding] :seon.render/source]}
  [{local :seon.ns.alias/local target :seon.ns.alias/target-ns}]
  (str ";; Here `" local "` is an alias for " target ", so `" local
       "/name` reads a Var\n"
       ";; in that namespace — the `:as` half of [" target " :as " local "].\n"
       (pr-str (list 'dir (list 'quote target)))))

(defn render-alias-html
  "Render one namespace alias binding with its local and target roles explicit."
  {:malli/schema [:=> [:cat :seon.ns.alias/binding] :seon.render/hiccup]}
  [{local :seon.ns.alias/local target :seon.ns.alias/target-ns}]
  [:article {:class "seon-family-entry seon-namespace-alias-entry"}
   [:p {:class "seon-kicker"} "Namespace alias"]
   [:p [:code (str local)] " → " [:code (str target)]]])

(defn render-refer-ai
  "Render source that says which Var one refer binding names.

  A refer points the other way from an alias: the local symbol is the target
  Var's name brought into this namespace, so the arrow reads backwards."
  {:malli/schema [:=> [:cat :seon.ns.refer/binding] :seon.render/source]}
  [{local :seon.ns.refer/local
    target-ns :seon.ns.refer/target-ns
    target-name :seon.ns.refer/target-name}]
  (str ";; Here the bare symbol `" local "` is " target-ns "/" target-name
       " —\n"
       ";; the `:refer` half of [" target-ns " :refer [" target-name "]].\n"
       (pr-str (list 'doc (symbol (str target-ns) (str target-name))))))

(defn render-refer-html
  "Render one refer binding with its local and target roles explicit."
  {:malli/schema [:=> [:cat :seon.ns.refer/binding] :seon.render/hiccup]}
  [{local :seon.ns.refer/local
    target-ns :seon.ns.refer/target-ns
    target-name :seon.ns.refer/target-name}]
  [:article {:class "seon-family-entry seon-namespace-refer-entry"}
   [:p {:class "seon-kicker"} "Namespace refer"]
   [:p [:code (str local)] " ← " [:code (str target-ns "/" target-name)]]])

(defn render-import-ai
  "Render source naming the class one import binding makes available."
  {:malli/schema [:=> [:cat :seon.ns.import/binding] :seon.render/source]}
  [{local :seon.ns.import/local target :seon.ns.import/target-class}]
  (str ";; Here the bare symbol `" local "` is the JVM class "
       (or target local) " —\n"
       ";; the `:import` half of the ns form.\n"
       (pr-str (list 'class (list 'quote (or target local))))))

(defn render-import-html
  "Render one import binding with its local and target roles explicit."
  {:malli/schema [:=> [:cat :seon.ns.import/binding] :seon.render/hiccup]}
  [{local :seon.ns.import/local target :seon.ns.import/target-class}]
  [:article {:class "seon-family-entry seon-namespace-import-entry"}
   [:p {:class "seon-kicker"} "Namespace import"]
   [:p [:code (str local)] " → " [:code (str (or target local))]]])

(defn- ns-form
  [namespace-name requires]
  (apply list
         (cond-> ['ns namespace-name]
           (seq requires) (conj (apply list :require requires)))))

(defn- render-data
  [unit]
  (let [row (namespace-row unit)]
    (if (read-refusal? row)
      row
      (let [db (:seon.db/db unit)
            bounds (read-bounds unit)
            namespace-name (or (:seon.ns/name row) 'unknown.namespace)
            distance (max 0 (long (get unit :seon.render/distance 1)))
            all-functions (if (pos? distance)
                            (function-rows db bounds (:db/id unit))
                            [])]
        (if (read-refusal? all-functions)
          all-functions
          (let [functions (if (= 1 distance)
                            all-functions
                            (vec (remove :seon.fn/private? all-functions)))
                own-schemas (own-schema-rows db bounds namespace-name distance)]
            (if (read-refusal? own-schemas)
              own-schemas
              {::db db
               ::read-bounds bounds
               ::namespace-name namespace-name
               ::namespace-source (:seon.ns/source row)
               ::namespace-doc (:seon.ns/doc row)
               ::distance distance
               ::requires (vec (require-specs row))
               ::functions functions
               ::own-schemas own-schemas
               ::schema-row-cache
               (atom (into {} (map (juxt :seon.schema/key identity))
                           own-schemas))
               ::owner-agent-id (when (and db
                                          (empty? functions)
                                          (empty? own-schemas))
                                  (agent/steward-of db namespace-name))})))))))

;;; ---------------------------------------------------------------------------
;;; Bounded, whole-section assembly
;;; ---------------------------------------------------------------------------

(defn- empty-comment
  "The ordinary state of a fresh agent namespace, said as source.

  AN EMPTY NAMESPACE IS NOT A FAILURE. This used to render
  `{:seon.error/message \"No indexed members …\"}` into the agent's own
  context — an error map describing the normal first moment of every agent.
  A Clojure comment is what a REPL would show, and it is not an error value."
  [owner-agent-id]
  (str ";; No definitions are indexed in this namespace yet"
       (when owner-agent-id (str "; it belongs to agent " owner-agent-id))
       ".\n"
       ";; Every `defn` evaluated here with a :malli/schema becomes one."))

(defn- empty-text
  [owner-agent-id]
  (str "No definitions are indexed in this namespace yet"
       (when owner-agent-id (str "; it belongs to agent " owner-agent-id))
       ". Every contracted `defn` evaluated here becomes one."))

(defn- schema-error-message
  "The `:error/message` a schema declares on its own properties or, for a named
  error `[:and :seon.error/base [:map {...} ...]]`, on a conjunct's."
  [row]
  (let [form (::schema-form (schema-definition row))
        message (fn [child]
                  (when (and (vector? child) (map? (second child)))
                    (:error/message (second child))))]
    (when (vector? form)
      (or (message form)
          (when (and (= :and (first form)) (some #{:seon.error/base} form))
            (some message (rest form)))))))

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
    (if (read-refusal? summary)
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
        (concat (map :seon.fn/source functions)
                (map (fn [row]
                       (schema-registration-line
                        (:seon.schema/key row)
                        (schema-definition row)))
                     own-schemas))
        schema-section
        (referenced-schema-ai-section
         db bounds schema-row-cache functions own-schemas)]
    (if (read-refusal? schema-section)
      schema-section
      (str/join
       "\n\n"
       (cond-> [source]
         (seq member-parts) (into member-parts)
         schema-section (conj schema-section)
         (and (empty? functions) (empty? own-schemas))
         (conj (empty-comment owner-agent-id)))))))

(defn- compact-ai-items
  [{::keys [db schema-row-cache functions own-schemas] :as data}]
  (let [bounds (::read-bounds data)
        summary (referenced-schema-summary db bounds schema-row-cache
                                           functions own-schemas)]
    (if (read-refusal? summary)
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
  [{::keys [namespace-name requires functions own-schemas owner-agent-id]}
   items]
  (let [no-members? (and (empty? functions) (empty? own-schemas))]
    (str (when no-members? (str (empty-comment owner-agent-id) "\n"))
         (pr-str (into [(ns-form namespace-name requires)] items)))))

(defn- ai-text
  [data]
  (case (::distance data)
    0 (str (::namespace-name data))
    1 (full-ai-text data)
    (let [items (compact-ai-items data)]
      (if (read-refusal? items)
        items
        (compact-ai-text data items)))))

;;; ---------------------------------------------------------------------------
;;; HTML twin
;;; ---------------------------------------------------------------------------

(defn- function-id
  [function]
  (block/surface-id (keyword (:seon.fn/sym function))))

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
    (if (read-refusal? summary)
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

(defn- requires-html
  "The namespaces this one requires, one entry each, never one raw vector."
  [requires]
  [:div {:class "seon-namespace-requires"}
   [:p "Requires"]
   (into [:ul]
         (map (fn [required]
                (let [namespace-name (if (sequential? required)
                                       (first required)
                                       required)]
                  [:li
                   [:a {:href (route/path ::route/namespace
                                          {:namespace (str namespace-name)})}
                    [:code (pr-str required)]]])))
         requires)])

(defn- full-html-view
  [{::keys [db schema-row-cache namespace-name namespace-source namespace-doc
            requires functions own-schemas owner-agent-id] :as data}
   include-detail?]
  (let [bounds (::read-bounds data)
        source (if (str/blank? namespace-source)
                 (pr-str (ns-form namespace-name requires))
                 namespace-source)
        schema-section (when include-detail?
                         (referenced-schema-html db bounds schema-row-cache
                                                 functions own-schemas))]
    (if (read-refusal? schema-section)
      schema-section
      (into
       [:section {:class "seon-family-entry seon-namespace-entry"}
        [:h2 [:code (str namespace-name)]]]
       (cond-> []
         (first-doc-line namespace-doc)
         (conj [:p {:class "seon-namespace-description"}
                (first-doc-line namespace-doc)])
         (seq functions)
         (conj (into [:dl {:class "seon-namespace-definitions"}]
                     (mapcat compact-function-html functions)))
         (and include-detail? (seq requires))
         (conj (requires-html requires))
         (and include-detail? (seq own-schemas))
         (conj [:pre {:class "seon-namespace-own-schemas"}
                [:code (str/join "\n" (map compact-schema-line own-schemas))]])
         (and include-detail? schema-section) (conj schema-section)
         (and (empty? functions) (empty? own-schemas))
         (conj [:p {:class "seon-namespace-empty"}
                (empty-text owner-agent-id)])
         include-detail?
         (conj
          [:details {:class "seon-namespace-source"}
           [:summary "namespace source"]
           [:pre [:code source]]])
         (and include-detail? (seq functions))
         (conj
          (into
           [:details {:class "seon-namespace-member-sources"}
            [:summary "member definition sources"]]
           (map (fn [function]
                  [:details
                   [:summary [:code (:seon.fn/sym function)]]
                   [:pre [:code (:seon.fn/source function)]]])
                functions)))
         )))))

(defn- compact-html-view
  [{::keys [db schema-row-cache namespace-name requires functions own-schemas
            owner-agent-id] :as data}]
  (let [bounds (::read-bounds data)
        schema-section
        (referenced-schema-html db bounds schema-row-cache
                                functions own-schemas)]
    (if (read-refusal? schema-section)
      schema-section
      (into
       [:section {:class "seon-family-entry seon-namespace-entry"}
        [:h2 [:code (str namespace-name)]]]
       (cond-> []
         (seq requires)
         (conj (requires-html requires))
         (seq own-schemas)
         (conj [:pre {:class "seon-namespace-own-schemas"}
                [:code (str/join "\n" (map compact-schema-line own-schemas))]])
         schema-section (conj schema-section)
         (seq functions)
         (conj (into [:dl {:class "seon-namespace-definitions"}]
                     (mapcat compact-function-html functions)))
         (and (empty? functions) (empty? own-schemas))
         (conj [:p {:class "seon-namespace-empty"}
                (empty-text owner-agent-id)]))))))

(defn- html-view
  [data]
  (case (::distance data)
    0 [:section {:class "seon-family-entry seon-namespace-entry"}
       [:h2
        [:a {:href (route/path ::route/namespace
                               {:namespace (str (::namespace-name data))})}
         [:code (str (::namespace-name data))]]]]
    1 (full-html-view data true)
    (compact-html-view data)))

;;; ---------------------------------------------------------------------------
;;; Family defaults
;;; ---------------------------------------------------------------------------

(defn namespace-form
  "Return the ordinary `dir` form for a namespace entity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/form]}
  [unit]
  (list 'dir (list 'quote (:seon.ns/name unit))))

(defn function-ai
  "Emit one documentation read and exact, unevaluated queries for linked facts."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [unit]
  (let [entity (or (:seon.render/value unit) unit)
        function-name (:seon.fn/sym entity)
        lookup [:seon.fn/sym function-name]
        installed (when-let [database (:seon.db/db unit)]
                    (:schema (db/schema-database database)))
        links (cond-> []
                (get installed :seon.lint/fn) (conj [:seon.lint/_fn :seon.lint/id]))]
    (str ";; Function " function-name ". Read its contract and docstring.\n"
         (str/join "\n"
           (for [[attribute identity-attribute] links]
             (str ";; " attribute " (run to inspect): "
                  (pr-str (list 'seon.db/pull
                                (list 'quote [{attribute [identity-attribute]}]) (list 'quote lookup))))))
         "\n;; Historical faults (run to inspect): "
         (pr-str (list 'seon.db/q
                       (list 'quote '[:find ?signature :in $ ?target
                                      :where [?fault :seon.instrument/fn ?target]
                                             [?fault :seon.error/signature ?signature]])
                       (list 'seon.db/db) (list 'quote function-name)))
         "\n;; Callers (run to inspect): "
         (pr-str (list 'seon.db/q
                       (list 'quote '[:find ?caller :in $ ?target
                                      :where [?caller :seon.fn/calls ?target]])
                       (list 'seon.db/db) (list 'quote function-name)))
         "\n;; Reaching tests (run to inspect): "
         (pr-str (list 'seon.fn/tests-reaching (list 'seon.db/db) (list 'quote function-name)))
         "\n" (repl/source-text (list 'doc (symbol function-name))))))

(defn function-html
  "Render a function block with its namespace link and unevaluated queries."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [entity (or (:seon.render/value unit) unit)
        function-name (:seon.fn/sym entity)]
    [:section {:class "seon-family-entry seon-function"}
     [:h3 [:a {:href (route/path :seon.render.route/namespace
                       {:namespace (namespace (symbol function-name))})} function-name]]
     (when-let [doc (:seon.fn/doc entity)] [:p doc])
     (when-let [spec (:seon.fn/spec entity)] [:pre spec])
     [:pre (function-ai unit)]]))

(defn schema-form
  "Return the ordinary `doc` form for a schema entity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/form]}
  [unit]
  (list 'doc (:seon.schema/key unit)))

;;; ---------------------------------------------------------------------------
;;; Graph nodes: one entity each. Distance 0 names it; distance 1 and beyond
;;; show its content, with its neighbours as names (the walk renders those).
;;; ---------------------------------------------------------------------------

(defn- node-distance
  {:malli/schema [:=> [:cat :seon.render/unit] [:int {:min 0}]]}
  [unit]
  (max 0 (long (get unit :seon.render/distance 1))))

(defn- names
  {:malli/schema [:=> [:cat [:maybe [:or [:set :symbol] [:sequential :symbol]]]] :string]}
  [symbols]
  (if (seq symbols) (str/join " " (sort-by str symbols)) "none"))

(defn- comment-lines
  {:malli/schema [:=> [:cat :string :string] :string]}
  [label text]
  (let [indent (apply str (repeat (count label) \space))]
    (str/join "\n" (map-indexed #(str ";; " (if (zero? %1) label indent) %2) (str/split-lines text)))))

(defn task-ai
  "Render one task: intent, done-when proofs, subjects and must-reads.

  Neighbours are names; the last form is the call that expands the edges."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/source :seon.db/invalid-read-error]]}
  [unit]
  (let [{:seon.task/keys [id kind intent must-read] task-name :seon.task/name task-ns :seon.task/ns}
        (or (:seon.render/value unit) unit)
        header (str ";; Task " id " \"" task-name "\": a " (name kind) " task in " task-ns)
        edges (when (pos? (node-distance unit)) (issue/task-edges (:seon.db/db unit) id))]
    (cond
      (nil? edges) header
      (read-refusal? edges) edges
      :else (str header "\n" (comment-lines "Intent: " intent)
                 "\n;; Done when these proofs pass: " (names (:seon.task/proofs edges))
                 "\n;; Subjects: " (names (:seon.task/subjects edges))
                 "\n;; Must read: " (names must-read)
                 "\n" (repl/source-text (list 'seon.issue/task-edges (list 'seon.db/db) id))))))

(defn task-html
  "Render one task as a card: intent, proofs, subjects, must-reads, evidence."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/hiccup :seon.db/invalid-read-error]]}
  [unit]
  (let [{:seon.task/keys [id kind intent must-read evidence] task-name :seon.task/name task-ns :seon.task/ns}
        (or (:seon.render/value unit) unit)
        edges (when (pos? (node-distance unit)) (issue/task-edges (:seon.db/db unit) id))
        row (fn [label items]
              [:div [:dt label] [:dd (if (seq items) (into [:ul] (map #(vector :li [:code (str %)])) (sort-by str items)) "None")]])]
    (if (read-refusal? edges)
      edges
      [:article {:class "seon-family-entry seon-task-entry"}
       [:header [:p {:class "seon-kicker"} (str "Task · " (name kind) " · " task-ns)] [:h3 task-name " " [:code id]]]
       [:p {:class "seon-task-intent"} intent]
       (when edges
         [:dl (row "Done when these proofs pass" (:seon.task/proofs edges)) (row "Subjects" (:seon.task/subjects edges))
          (row "Must read" must-read) (row "Evidence" evidence)])])))

(defn- schema-parts
  "A schema row's key, its stored form read as data, and the form's description."
  {:malli/schema [:=> [:cat :seon.render/unit] [:tuple :qualified-keyword [:or [:vector :any] :keyword :symbol] [:maybe :string]]]}
  [unit]
  (let [{schema-key :seon.schema/key text :seon.schema/form} (or (:seon.render/value unit) unit)
        form (edn/read-string text)]
    [schema-key form (when (and (vector? form) (map? (second form))) (:description (second form)))]))

(defn schema-ai
  "Render one schema as its `register!` form, headed by its doc line."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [unit]
  (let [[schema-key form doc] (schema-parts unit)
        header (str ";; Schema " schema-key (some->> (first-doc-line doc) (str " — ")))]
    (if (zero? (node-distance unit))
      header
      (str header "\n" (repl/source-text (list 'seon.schema/register! schema-key form))))))

(defn schema-html
  "Render one schema: its key, its doc and its declaring form."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [[schema-key form doc] (schema-parts unit)]
    [:article {:class "seon-family-entry seon-schema-entry"}
     [:header [:p {:class "seon-kicker"} "Schema"] [:h3 [:code (str schema-key)]]]
     (when doc [:p doc])
     (when (pos? (node-distance unit))
       [:pre [:code (repl/source-text (list 'seon.schema/register! schema-key form))]])]))

(defn render-ai
  "Render a namespace as valid, distance-sensitive Clojure."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or [:maybe :seon.render/source] :seon.db/invalid-read-error]]}
  [unit]
  (let [database (:seon.db/db unit)
        namespace-name (:seon.ns/name unit)
        agent-id (when database
                   (db/q '[:find ?id . :in $ ?name
                           :where [?namespace :seon.ns/name ?name]
                                  [?agent :seon.agent/namespace ?namespace]
                                  [?agent :seon.agent/id ?id]
                                  (not [?agent :seon.agent/archived-tx])] database namespace-name))]
    (if agent-id
      (let [installed (:schema (db/schema-database database))
            attributes (sort (filter #(contains? installed %)
                                     (db/q '[:find [?key ...] :in $ ?name
                                             :where [?namespace :seon.ns/name ?name]
                                                    [?schema :seon.schema/ns ?namespace]
                                                    [?schema :seon.schema/key ?key]]
                                           database namespace-name)))]
        (str ";; I should inspect what I have defined and which schemas I declared.\n"
             (repl/source-text (list 'dir namespace-name))
             (when (seq attributes)
               (str "\n\n;; I should count the facts under my declared attributes.\n"
               (repl/source-text (list 'seon.db/q
                             (list 'quote '[:find ?attribute (count ?entity)
                                            :in $ [?attribute ...]
                                            :where [?entity ?attribute _]])
                             (vec attributes)))))))
      (let [data (render-data unit)]
        (if (read-refusal? data) data (ai-text data))))))

(defn render-html
  "Render the namespace's same definitions as stable HTML entries."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or [:maybe :seon.render/hiccup] :seon.db/invalid-read-error]]}
  [unit]
  (let [data (render-data unit)]
    (if (read-refusal? data)
      data
      (html-view data))))
