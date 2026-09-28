(ns seon.render.schema
  "The schema render pair: what a registration or a schema entity means for
  the program and its stored data.

  One selection, `usage`, is one read of one database value: the committed
  form it replaces, the entities holding the attribute and how many of a
  bounded sample the new form refuses, and the schemas and contracts naming
  it. `render-ai` prints it through the floor; `render-html` lays the same
  keys out as hiccup. `seon.schema/register!` returns a
  `:seon.schema/registration`, whose declared pair is this one, as is the
  schema entity's."
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [seon.db :as db]
            [seon.id :as id]
            [seon.render.route :as render.route]
            [seon.render.value :as render.value]
            [seon.repl :as repl]))

(def ^:private sample-limit
  "Stored values validated against a new form: a bounded sample, counted."
  200)

(def ^:private count-limit
  "Stored entities counted before the count says at least this many."
  10000)

(def ^:private names-shown
  "Referencing schemas and contracts named before the count says the rest."
  8)

(defn- read-form
  "A stored schema form read as data, or nil when absent or unreadable."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :seon.schema/value]]}
  [text]
  (when (string? text)
    (try (edn/read-string text)
         ;; Declared case: a stored form that is not EDN reads as absent.
         (catch RuntimeException _ nil))))

(defn- found
  "A query's result collection, or empty for its declared read refusal."
  {:malli/schema [:=> [:cat [:or [:sequential :seon.schema/value] :seon.db/error-result :nil]]
                  [:sequential :seon.schema/value]]}
  [result]
  (if (sequential? result) result []))

(defn- stored-check
  "The attribute's stored entity count and the new form's verdict on a
  bounded sample of its values, or nil when `schema-key` is no attribute."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value]
                  [:maybe :map]]}
  [database schema-key definition]
  (when (some? (get-in (db/schema-database database) [:schema schema-key]))
    (let [datoms (db/datoms database :aevt schema-key)
          stored (if (sequential? datoms) (bounded-count count-limit datoms) 0)
          validator (try (m/validator definition
                                      {:registry (:seon.schema.projection/registry
                                                  (db/carried-projection database))})
                         ;; Declared case: a form naming a schema this
                         ;; database does not hold (registered later in the
                         ;; same batch) cannot be checked yet.
                         (catch clojure.lang.ExceptionInfo failure failure))]
      (if (instance? Throwable validator)
        {:seon.schema.usage/stored stored
         :seon.schema.usage/unchecked (ex-message validator)}
        (let [sample (take sample-limit (if (sequential? datoms) datoms []))
              failing (remove #(validator (:v %)) sample)]
          (cond-> {:seon.schema.usage/stored stored}
            (seq sample) (assoc :seon.schema.usage/checked (count sample)
                                :seon.schema.usage/failing (count failing))
            (seq failing)
            (assoc :seon.schema.usage/failing-example
                   [(:e (first failing)) (:v (first failing))])))))))

(defn usage
  "What registering `definition` under `schema-key` means for the program.

  One read of one database value.

  - `:seon.schema.usage/previous`: the committed form it replaces, when it
    differs; `:seon.schema.usage/new?` when none is committed.
  - `:seon.schema.usage/stored`: the entities holding the attribute (at most
    10,000 counted); `checked` and `failing`: how many of a sample of 200
    stored values the new form refuses, with one failing example as
    `[entity value]`; `unchecked` when the form cannot compile here yet.
  - `:seon.schema.usage/referenced-by` and `contracts`: the schemas and
    function contracts naming it, eight named, the rest counted.

  ## Examples

  (usage database :seon.task/intent [:string {:min 400}])

  On default 2026-09-27 this named the previous form `[:string {:min 1}]`,
  241 stored intents of which 90 of 200 checked fail, the three task
  schemas referencing it and `seon.task/create!`'s contract.

  ## Costs

  Unarmed, measured at default 2026-09-27 (tmp/feedback-renders/proof.clj):
  17-24 ms, the most for an attribute with 5,710 stored values."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value]
                  :seon.schema.usage/report]}
  [database schema-key definition]
  (let [previous (read-form (db/q '[:find ?form . :in $ ?k
                                    :where [?s :seon.schema/key ?k] [?s :seon.schema/form ?form]]
                                  database schema-key))
        referenced-by (sort (found (db/q '[:find [?other ...] :in $ ?k
                                           :where [?s :seon.schema/references ?k] [?s :seon.schema/key ?other]]
                                         database schema-key)))
        contracts (sort (found (db/q '[:find [?sym ...] :in $ ?k [?attribute ...]
                                       :where [?arity ?attribute ?k] [?f :seon.fn/arities ?arity]
                                       [?f :seon.fn/sym ?sym]]
                                     database schema-key
                                     [:seon.fn.arity/input-refs :seon.fn.arity/output-refs
                                      :seon.fn.arity/guard-refs])))]
    (merge
     (cond-> {:seon.schema/key schema-key :seon.schema/definition definition}
       (nil? previous) (assoc :seon.schema.usage/new? true)
       (and (some? previous) (not= previous definition)) (assoc :seon.schema.usage/previous previous))
     (stored-check database schema-key definition)
     (cond-> {}
       (seq referenced-by)
       (assoc :seon.schema.usage/referenced-by (vec (take names-shown referenced-by))
              :seon.schema.usage/referenced-by-count (count referenced-by))
       (seq contracts)
       (assoc :seon.schema.usage/contracts (vec (take names-shown contracts))
              :seon.schema.usage/contract-count (count contracts))))))

(defn- subject
  "The rendered schema's key and form: a registration's own, or a schema
  entity's stored form read as data."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe [:tuple :qualified-keyword :seon.schema/value]]]}
  [unit]
  (let [value (or (:seon.render/value unit) unit)
        schema-key (:seon.schema/key value)
        definition (if (find value :seon.schema/definition)
                     (:seon.schema/definition value)
                     (read-form (:seon.schema/form value)))]
    (when (and (qualified-keyword? schema-key) (some? definition))
      [schema-key definition])))

(defn- listed?
  "True when the unit lists the schema among others (a walk at distance 0):
  only its key and doc line are shown then, and nothing is read."
  {:malli/schema [:=> [:cat :seon.render/unit] :boolean]}
  [unit]
  (zero? (long (get unit :seon.render/distance 1))))

(defn- description
  {:malli/schema [:=> [:cat :seon.schema/value] [:maybe :string]]}
  [definition]
  (when (and (vector? definition) (map? (second definition)))
    (let [text (:description (second definition))]
      (when (string? text) text))))

(defn- floor-shown
  "`value` through the floor printer in `output`'s face under the unit's
  profile: text for AI, hiccup for HTML."
  {:malli/schema [:=> [:cat :seon.render/unit :seon.schema/value :seon.render/output]
                  [:maybe :seon.render/hiccup]]}
  [unit value output]
  (let [projection (render.value/prepare
                    (-> unit
                        (dissoc :seon.repl/handle :seon.render.value/root)
                        (assoc :seon.render/value value
                               :seon.render.value/options {:seon.render.value/structural? true})
                        (update :seon.render.call/id #(or % [:seon.schema.usage/report])))
                    output)]
    (get projection (if (= :seon.render/html output) :seon.render.value/html :seon.render.value/text))))

(defn render-ai
  "Render a registration or schema entity as its `usage` map.

  The floor prints it; listed among others, only `(doc key)` and its doc
  line are shown."
  {:malli/schema [:=> [:cat :seon.render/unit] [:string {:min 1}]]}
  [unit]
  (if-let [[schema-key definition] (subject unit)]
    (let [database (:seon.db/db unit)]
      (if (or (listed? unit) (not (db/database-value? database)))
        (str (repl/source-text (list 'doc schema-key))
             (some->> (description definition) (str "\n;; ")))
        (let [shown (floor-shown unit (usage database schema-key definition) :seon.render/ai)]
          (if (and (string? shown) (seq shown)) shown (str schema-key)))))
    "Schema evidence is unavailable."))

(defn- form-text
  "A schema form as the source that writes it, through the value printer's
  bounded text."
  {:malli/schema [:=> [:cat :seon.schema/value] :string]}
  [form]
  (render.value/bounded-text form))

(defn render-form
  "The form that finds this data again: the `usage` read for the registered
  key and form, over the viewer's database. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.render/read-form]]}
  [unit]
  (when-let [[schema-key definition] (subject unit)]
    (list 'seon.render.schema/usage '(seon.db/db) schema-key (list 'quote definition))))

(defn- function-link
  {:malli/schema [:=> [:cat :qualified-symbol] :seon.render/hiccup]}
  [sym]
  [:a {:href (render.route/path :seon.render.route/data {} {:entity (id/printed [:seon.fn/sym sym])})}
   [:code (str sym)]])

(defn- schema-link
  {:malli/schema [:=> [:cat :qualified-keyword] :seon.render/hiccup]}
  [schema-key]
  [:a {:href (render.route/path :seon.render.route/data {} {:entity (id/printed [:seon.schema/key schema-key])})}
   [:code (str schema-key)]])

(defn- named-list
  {:malli/schema [:=> [:cat :string [:vector :seon.render/hiccup] [:int {:min 0}]] :seon.render/hiccup]}
  [label links total]
  [:p {:class "seon-schema-usage-names"} (str label " (" total "): ")
   (into [:span] (interpose " " links))
   (when (> total (count links)) (str " and " (- total (count links)) " more"))])

(defn render-html
  "Render a registration or schema entity from the `usage` selection.

  Its form beside the one it replaces, its stored data's verdict, and the
  schemas and contracts naming it: the keys `render-ai` prints."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (if-let [[schema-key definition] (subject unit)]
    (let [database (:seon.db/db unit)
          report (when (and (not (listed? unit)) (db/database-value? database))
                   (usage database schema-key definition))
          {:seon.schema.usage/keys [previous new? stored checked failing failing-example unchecked
                                    referenced-by referenced-by-count contracts contract-count]} report]
      (into
       [:article {:class "seon-family-entry seon-schema-entry"}
        [:header [:p {:class "seon-kicker"} (if new? "New schema" "Schema")]
         [:h3 [:code (str schema-key)]]]
        (when-let [text (description definition)] [:p text])]
       (concat
        (if previous
          [[:div {:class "seon-schema-diff"}
            [:div [:p "Before"] [:pre [:code (form-text previous)]]]
            [:div [:p "Now"] [:pre [:code (form-text definition)]]]]]
          [[:pre [:code (form-text definition)]]])
        (when stored
          [[:p {:class "seon-schema-usage-stored"}
            (str stored " stored"
                 (when checked (str "; " failing " of " checked " checked fail the form"))
                 (when unchecked (str "; not checked: " unchecked)))]])
        (when failing-example
          [[:div {:class "seon-schema-usage-failing"}
            [:p "First failing: entity " (str (first failing-example))]
            (floor-shown unit (second failing-example) :seon.render/html)]])
        (when (seq referenced-by)
          [(named-list "Schemas naming it" (mapv schema-link referenced-by) referenced-by-count)])
        (when (seq contracts)
          [(named-list "Contracts naming it" (mapv function-link contracts) contract-count)]))))
    [:p {:class "seon-schema-entry"} "Schema evidence is unavailable."]))
