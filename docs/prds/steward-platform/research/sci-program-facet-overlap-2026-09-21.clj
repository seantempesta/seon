;; Run with clojure -M docs/prds/steward-platform/research/sci-program-facet-overlap-2026-09-21.clj
(require 'seon.sci.eval 'seon.sci.kernel 'seon.sci.admit 'seon.program
         '[seon.schema.edn :as schema.edn]
         '[seon.schema.form :as form])
(println :loads)
(let [forms (schema.edn/packaged-forms)
      facets [:seon.test/unknown-error :seon.test/expired]
      required
      (into (sorted-map)
            (map (fn [facet]
                   (let [definition (get forms facet)]
                     (assert (some? definition) (str "Absent declaration: " facet))
                     (assert (form/extends-schema? forms definition :seon.error/base))
                     [facet (into (sorted-set)
                                  (keep (fn [entry]
                                          (when-not (and (map? (second entry))
                                                         (:optional (second entry)))
                                            (first entry))))
                                  (form/map-entries forms definition))])))
            facets)
      unions
      (into (sorted-map)
            (map (fn [v]
                   (let [output (last (:malli/schema (meta v)))
                         declared (set (tree-seq coll? seq output))
                         members (filterv declared facets)]
                     (assert (= facets members))
                     [(symbol (str (:ns (meta v))) (str (:name (meta v)))) members])))
            [#'seon.sci.kernel/failure-value #'seon.sci.admit/semantic-value])]
  (assert (apply = (vals required)))
  (prn {:sci-program/required-members required
        :sci-program/output-unions unions
        :sci-program/shared-required-members true}))
