;; Declaration-only evidence for the ops-effects-2 PRD section 6 boundary.
;; Run: clojure -M docs/prds/steward-platform/research/kind-sweep-ops-effects-gate-facet-probe-2026-09-21.clj
(require '[seon.fn]
         '[seon.schema.edn :as schema.edn]
         '[seon.schema.form :as schema.form])

(let [forms (schema.edn/packaged-forms)
      contract (:malli/schema (meta #'seon.fn/gate-sets))
      output (last (last contract))
      facets (filter qualified-keyword? (rest output))
      observations
      (mapv (fn [facet]
              (let [required
                    (into #{}
                          (keep (fn [[member options]]
                                  (when-not (and (map? options) (:optional options))
                                    member)))
                          (schema.form/map-entries forms (get forms facet)))
                    markers (into #{} (filter #(= [:= true] (get forms %))) required)]
                {:facet facet :required required :markers markers
                 :required-after-marker-retirement
                 (into #{} (remove markers) required)}))
            facets)]
  (prn {:function 'seon.fn/gate-sets :output output :facets observations})
  (assert (= 2 (count observations)) "The declared subject changed; reread it.")
  (assert (every? (comp seq :markers) observations)
          "A facet no longer relies on a marker; reread it.")
  (assert (apply = (map :required-after-marker-retirement observations))
          "The owner has made the substantive members distinguishable."))
