; Read-only foreground probe for the C4 declaration proposal.
; Run: clojure -M docs/prds/steward-platform/research/config-plan-family-1d-property-probe-2026-09-20.clj
(require '[clojure.string :as str]
         '[seon.schema.edn :as schema.edn]
         '[seon.schema.form :as schema.form]
         '[seon.schema.datahike :as schema.datahike])
(let [forms (schema.edn/packaged-forms)
      proposed (assoc forms
                      :seon.config/default :seon.schema/value
                      :seon.shell/environment [:string {:min 1}]
                      :seon.render/derived :boolean)
      projection {:seon.schema.projection/forms proposed}]
  (prn {:seon.config/per-agent-dial-count
        (count (filter (fn [[_ definition]]
                         (let [p (schema.form/attr-form-properties definition)]
                           (and (:seon.config/dial p) (:seon.config/per-agent p))))
                       forms))
        :seon.config/millisecond-dial-count
        (count (filter (fn [[attribute definition]]
                         (and (:seon.config/dial (schema.form/attr-form-properties definition))
                              (str/ends-with? (name attribute) "-ms")))
                       forms))
        :seon.config/default-storable?
        (schema.datahike/storable-attribute-in? projection :seon.config/default)
        :seon.shell/environment-storable?
        (schema.datahike/storable-attribute-in? projection :seon.shell/environment)
        :seon.render/derived-storable?
        (schema.datahike/storable-attribute-in? projection :seon.render/derived)
        :seon.config/persisted-default-properties
        (schema.datahike/storable-properties-in
         projection [:int {:seon.config/default 250000}])
        :seon.schema/remaining-undeclared-properties
        (into (sorted-set)
              (comp (mapcat (comp keys schema.form/namespaced-properties val))
                    (filter #(str/starts-with? (namespace %) "seon."))
                    (remove #(contains? proposed %)))
              proposed)}))
