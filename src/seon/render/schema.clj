(ns seon.render.schema
  "The schema render pair: what a registration or a schema entity means for
  the program and its stored data.

  One selection, `usage`, reads one database value: the committed form a
  registration replaces and the schemas and contracts naming its key. The
  stored-data verdict is not computed here: `seon.schema/register!` records
  it on the registration when it is evaluated (`seon.db/registration-check`),
  and the render only reads it. `render-ai` returns the selection as data for
  the value printer; `render-html` lays the same keys out as hiccup. A read
  the database refuses is the render's value, never an empty result."
  (:require [clojure.edn :as edn]
            [seon.db :as db]
            [seon.id :as id]
            [seon.render.route :as render.route]
            [seon.render.value :as render.value]))

(def ^:private names-shown
  "Referencing schemas and contracts named before the count says the rest."
  8)

(defn usage
  "What registering `definition` under `schema-key` means for the program,
  or the first read the database refused.

  - `:seon.schema.usage/previous`: the committed form it replaces, when it
    differs; `:seon.schema.usage/new?` when the read found none committed.
  - `:seon.schema.usage/referenced-by` and `contracts`: the schemas and
    function contracts naming the key, eight named, the rest counted.

  ## Examples

  (usage database :seon.task/intent [:string {:min 400}])

  On default 2026-09-27 this named the previous form `[:string {:min 1}]`,
  the three task schemas referencing it and `seon.task/create!`'s contract.

  ## Costs

  Three indexed queries; no validation."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value]
                  [:or :seon.schema.usage/report :seon.db/error-result]]}
  [database schema-key definition]
  (let [previous (db/q '[:find ?form . :in $ ?k
                         :where [?s :seon.schema/key ?k] [?s :seon.schema/form ?form]]
                       database schema-key)
        referenced-by (db/q '[:find [?other ...] :in $ ?k
                              :where [?s :seon.schema/references ?k] [?s :seon.schema/key ?other]]
                            database schema-key)
        contracts (db/q '[:find [?sym ...] :in $ ?k [?attribute ...]
                          :where [?arity ?attribute ?k] [?f :seon.fn/arities ?arity]
                          [?f :seon.fn/sym ?sym]]
                        database schema-key
                        [:seon.fn.arity/input-refs :seon.fn.arity/output-refs
                         :seon.fn.arity/guard-refs])]
    (or (first (filter db/read-refusal? [previous referenced-by contracts]))
        (let [previous (some-> previous edn/read-string)
              referenced-by (sort referenced-by)
              contracts (sort contracts)]
          (cond-> {:seon.schema/key schema-key :seon.schema/definition definition}
            (nil? previous) (assoc :seon.schema.usage/new? true)
            (and (some? previous) (not= previous definition))
            (assoc :seon.schema.usage/previous previous)
            (seq referenced-by)
            (assoc :seon.schema.usage/referenced-by (vec (take names-shown referenced-by))
                   :seon.schema.usage/referenced-by-count (count referenced-by))
            (seq contracts)
            (assoc :seon.schema.usage/contracts (vec (take names-shown contracts))
                   :seon.schema.usage/contract-count (count contracts)))))))

(defn- subject
  "The rendered schema: a registration's key, form and recorded check, or a
  schema entity's key and stored form read as data."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :map]]}
  [unit]
  (let [value (or (:seon.render/value unit) unit)
        schema-key (:seon.schema/key value)]
    (when (qualified-keyword? schema-key)
      (cond
        (find value :seon.schema/definition)
        (select-keys value [:seon.schema/key :seon.schema/definition :seon.schema.usage/check])
        (string? (:seon.schema/form value))
        {:seon.schema/key schema-key
         :seon.schema/definition (edn/read-string (:seon.schema/form value))}))))

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

(defn- selection
  "The data both faces show: listed, the key and doc line; otherwise the
  `usage` report (or its read refusal) with the registration's recorded check."
  {:malli/schema [:=> [:cat :seon.render/unit :map]
                  [:or :seon.schema.usage/listing :seon.schema.usage/report :seon.db/error-result]]}
  [unit {schema-key :seon.schema/key definition :seon.schema/definition check :seon.schema.usage/check}]
  (let [database (:seon.db/db unit)]
    (if (or (listed? unit) (not (db/database-value? database)))
      (cond-> {:seon.schema/key schema-key}
        (description definition) (assoc :seon.schema.usage/doc (description definition)))
      (let [report (usage database schema-key definition)]
        (cond-> report
          (and (some? check) (not (db/read-refusal? report))) (assoc :seon.schema.usage/check check))))))

(defn render-ai
  "A registration's or schema entity's AI face: its selection as data, which
  the value printer prints. A refused read is the face's value."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or [:string {:min 1}] :seon.schema.usage/listing :seon.schema.usage/report
                   :seon.db/error-result]]}
  [unit]
  (if-let [chosen (subject unit)]
    (selection unit chosen)
    "Schema evidence is unavailable."))

(defn render-form
  "The form that finds this data again: the `usage` read for the registered
  key and form, over the viewer's database. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.render/read-form]]}
  [unit]
  (when-let [{schema-key :seon.schema/key definition :seon.schema/definition} (subject unit)]
    (list 'seon.render.schema/usage '(seon.db/db) schema-key (list 'quote definition))))

(defn- link
  {:malli/schema [:=> [:cat :qualified-keyword [:or :qualified-symbol :qualified-keyword]] :seon.render/hiccup]}
  [identity-attribute value]
  [:a {:href (render.route/path :seon.render.route/data {} {:entity (id/printed [identity-attribute value])})}
   [:code (str value)]])

(defn- named-list
  {:malli/schema [:=> [:cat :string [:vector :seon.render/hiccup] [:int {:min 0}]] :seon.render/hiccup]}
  [label links total]
  [:p {:class "seon-schema-usage-names"} (str label " (" total "): ")
   (into [:span] (interpose " " links))
   (when (> total (count links)) (str " and " (- total (count links)) " more"))])

(defn- shown-html
  "`value` through the value printer's HTML face under `root`; a printer
  refusal shows its message."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/value :seon.schema/value] :seon.render/hiccup]}
  [database root value]
  (let [shown (render.value/render-html {:seon.render/value value :seon.db/db database :seon.render.value/root root})]
    (if (map? shown) [:span (:seon.error/message shown)] shown)))

(defn- check-html
  "The registration's recorded stored-data verdict, or its refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema.usage/check] :seon.render/hiccup]}
  [database {:seon.schema.usage/keys [checked failing failing-example more?] :as check}]
  (if (:seon.error/message check)
    [:p {:class "seon-schema-usage-stored"} (str "Stored values not checked: " (:seon.error/message check))]
    [:div {:class "seon-schema-usage-stored"}
     [:p (str failing " of " checked (when more? " (the first checked)") " stored entities fail the form")]
     (when failing-example
       [:p "First failing: entity " (str (first failing-example)) " "
        (shown-html database [::failing-example (first failing-example)] (second failing-example))])]))

(defn render-html
  "Render a registration or schema entity from the same selection
  `render-ai` returns: its form beside the one it replaces, the recorded
  stored-data verdict, and the schemas and contracts naming it."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (if-let [{schema-key :seon.schema/key definition :seon.schema/definition :as chosen} (subject unit)]
    (let [report (selection unit chosen)
          {:seon.schema.usage/keys [previous new? check referenced-by referenced-by-count
                                    contracts contract-count]} report
          form-text (fn [form] (shown-html (:seon.db/db unit) [::form schema-key (identical? form previous)] form))]
      (into
       [:article {:class "seon-family-entry seon-schema-entry"}
        [:header [:p {:class "seon-kicker"} (if new? "New schema" "Schema")]
         [:h3 [:code (str schema-key)]]]
        (when-let [text (description definition)] [:p text])]
       (concat
        (cond
          (db/read-refusal? report)
          [[:p {:class "seon-schema-refused"} (str "Read refused: " (:seon.error/message report))]]
          previous
          [[:div {:class "seon-schema-diff"}
            [:div [:p "Before"] (form-text previous)] [:div [:p "Now"] (form-text definition)]]]
          :else [(form-text definition)])
        (when (some? check) [(check-html (:seon.db/db unit) check)])
        (when (seq referenced-by)
          [(named-list "Schemas naming it" (mapv #(link :seon.schema/key %) referenced-by) referenced-by-count)])
        (when (seq contracts)
          [(named-list "Contracts naming it" (mapv #(link :seon.fn/sym %) contracts) contract-count)]))))
    [:p {:class "seon-schema-entry"} "Schema evidence is unavailable."]))
