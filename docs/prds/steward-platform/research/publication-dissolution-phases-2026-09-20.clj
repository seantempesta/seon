; Run with bb against retained operator logs; this reads evidence only.
; Example: bb docs/prds/steward-platform/research/publication-dissolution-phases-2026-09-20.clj tmp/publication-dissolution/scratch-live-init.log
(require '[clojure.edn :as edn] '[clojure.string :as str])
(doseq [path *command-line-args*]
  (let [events (into []
                    (keep (fn [line]
                            (when-let [offset (str/index-of line "#:seon.source{")]
                              (let [event (edn/read-string (subs line offset))]
                                (when (:seon.source/completed-phase event) event)))))
                    (str/split-lines (slurp path)))
        warning? (fn [event]
                   (str/starts-with? (:seon.source/completed-phase event) "WARNING "))]
    (prn {:seon.publication.measurement/log path
          :seon.publication.measurement/phases
          (mapv #(select-keys % [:seon.source/completed-phase :seon.source/elapsed-ms])
                (remove warning? events))
          :seon.publication.measurement/warning-events (count (filter warning? events))
          :seon.publication.measurement/warning-ms
          (reduce + 0 (map :seon.source/elapsed-ms (filter warning? events)))})))
