; Static declaration comparison only. Run with bb from the repository root.
; No namespace loads, runtime mutation, publication, or fixture proof.
(require '[clojure.edn :as edn]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])

(let [prd "docs/prds/steward-platform/plan/error-entities-prd-2026-09-17.md"
      revision (str/trim (:out (shell/sh "git" "rev-parse" "HEAD")))
      rows (for [line (str/split-lines (slurp prd))
                 :when (str/starts-with? line "| `")
                 :let [resource-end (.indexOf line "`" 3)
                       resource (subs line 3 resource-end)
                       start (.indexOf line "`{" (+ resource-end 1))]
                 :when (and (str/ends-with? resource ".edn") (pos? start))
                 :let [fragment (edn/read-string
                                 (subs line (inc start) (.lastIndexOf line "`")))
                       baseline-result (shell/sh "git" "show"
                                                (str revision ":resources/seon/schemas/" resource))
                       baseline (if (zero? (:exit baseline-result))
                                  (edn/read-string (:out baseline-result)) {})]]
             {:resource resource :fragment fragment :baseline baseline})
      additions (for [{:keys [resource fragment baseline]} rows
                      [k v] fragment :when (not (contains? baseline k))]
                  [resource k])
      replacements (for [{:keys [resource fragment baseline]} rows
                         [k v] fragment
                         :when (and (contains? baseline k) (not= v (get baseline k)))]
                     [resource k])
      examples #{:seon.error/value :seon.error.occurrence/occurrence
                 :my.note/not-found-error :seon.config/error}
      summary {:probe/revision revision
               :probe/resources (count rows)
               :probe/declarations (reduce + (map (comp count :fragment) rows))
               :probe/new-keys (count additions)
               :probe/changed-existing-keys (count replacements)
               :probe/placement-errors
               (vec (for [{:keys [resource fragment]} rows
                          k (keys fragment)
                          :when (not= resource (str (namespace k) ".edn"))]
                      [resource k]))
               :probe/examples
               (into (sorted-map)
                     (for [{:keys [fragment baseline]} rows
                           [k v] fragment :when (contains? examples k)]
                       [k {:probe/before (get baseline k) :probe/manifest v}]))
               :probe/forbidden-generic-union-present
               (boolean (some #(contains? (:fragment %) :seon.error/result) rows))}]
  (assert (pos? (count rows)) "No literal manifest rows found")
  (prn summary))
