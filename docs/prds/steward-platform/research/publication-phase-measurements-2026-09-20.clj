;; bb docs/prds/steward-platform/research/publication-phase-measurements-2026-09-20.clj LOG...
(require '[clojure.edn :as edn] '[clojure.string :as str])
(defn phase-group [phase]
  (cond
    (str/starts-with? phase "WARNING ") "warning output"
    (str/starts-with? phase "contract rows:") "contract rows batches"
    (str/starts-with? phase "program population compiled:") "population transaction"
    (str/starts-with? phase "population:") "population completion"
    :else phase))
(println "log\tcompleted-phase\telapsed-ms")
(doseq [path *command-line-args*]
  (let [events (keep (fn [line]
                      (when (str/starts-with? line "● current-src: ")
                        (let [event (try (edn/read-string (subs line (count "● current-src: ")))
                                         (catch Exception _ nil))]
                          (when (and (map? event) (:seon.source/elapsed-ms event)) event))))
                    (str/split-lines (slurp path)))
        totals (reduce (fn [m event]
                         (update m (phase-group (:seon.source/completed-phase event))
                                 (fnil + 0) (:seon.source/elapsed-ms event))) {} events)]
    (doseq [phase (distinct (map #(phase-group (:seon.source/completed-phase %)) events))]
      (println (str path "\t" phase "\t" (get totals phase))))))
