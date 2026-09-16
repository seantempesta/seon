;; Read-only evidence over the full and incremental completed publications.
;; bb <this-file> <full-base> <incremental-base> <changed-path>...
;; Fails loudly rather than reporting absence of signal: an empty change set,
;; an unchanged docstring, or a manifest disagreeing with its own completed
;; artifact all raise.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(let [[before after & expected-changes] *command-line-args*
      read-at (fn [root path] (edn/read-string (slurp (io/file root path))))
      artifacts (mapv #(read-at % "build/current-src.edn") [before after])
      manifests (mapv #(read-at % "manifest.edn") [before after])
      docs (mapv (fn [manifest]
                   (some (fn [row]
                           (when (= 'seon.test.runner/-main (:seon.fn/sym row))
                             (:seon.fn/doc row)))
                         (mapcat :seon.fn.file/rows
                                 (:seon.fn.manifest/artifacts manifest))))
                 manifests)
      inputs (mapv #(-> (read-at (io/file % "..") "ready.edn")
                        :seon.test.cache/inputs first) [before after])
      changed (->> (concat (keys (first inputs)) (keys (second inputs)))
                   distinct
                   (filter #(not= (get (first inputs) %) (get (second inputs) %)))
                   sort vec)]
  (assert (every? some? inputs) "Both completed bases must retain their inputs.")
  (assert (= manifests (mapv :seon.fn/manifest artifacts))
          "Each published manifest.edn must equal its own completed artifact.")
  (assert (= (vec (sort expected-changes)) changed)
          (str "Changed inputs were " (pr-str changed)))
  (assert (every? some? docs) "Both manifests must carry the probed function.")
  (assert (apply not= docs)
          "The incremental manifest did not observe the changed docstring.")
  (prn {:seon.test.cache/changed changed
        :seon.test.cache/manifest-counts (mapv #(count (:seon.fn.manifest/artifacts %)) manifests)
        :seon.test.cache/completed-manifests-match true
        :seon.test.cache/runner-docs docs}))
