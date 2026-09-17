; Run with bb -cp src <this-file> <retained-manifest.edn>.
; Read-only reproduction through the production selection owner.
(require '[clojure.edn :as edn] '[seon.test.selection :as selection])
(let [m (edn/read-string (slurp (or (first *command-line-args*) (throw (ex-info "Pass the retained manifest.edn path." {})))))
      artifacts (:seon.fn.manifest/artifacts m)
      rows (vec (mapcat :seon.fn.file/rows artifacts))
      owners (set (filter :seon.fn/destroys rows))
      seed {:seon.fn.file/relative-path "fixture-selection" :seon.fn.file/rows (vec owners)}
      platforms (set (keep #(when (:seon.test/platform %) (:seon.test/sym %)) rows))
      old (set (selection/reaching-tests [seed {:seon.fn.file/relative-path "remaining-program" :seon.fn.file/rows (filterv (complement owners) rows)}] ["fixture-selection"]))
      fixed (set (selection/reaching-tests (into [seed] artifacts) ["fixture-selection"]))]
 (let [owner-symbols (set (map :seon.fn/sym owners))
       callees (into {} (keep (fn [row]
                               (when-let [s (or (:seon.fn/sym row) (:seon.test/sym row))]
                                 [s (map second (concat (:seon.fn/calls row)
                                                        (:seon.fn/references row)))]))) rows)]
   (prn :owners (mapv #(select-keys % [:seon.fn/sym :seon.fn/destroys]) owners))
   (doseq [start ["seon.cluster.registry-test/a-concurrent-create-wave-loses-nothing"
                 "seon.cluster/start!"
                 "seon.flow-configuration-test/every-built-graph-proc-declares-a-specific-workload"]]
     (prn :from start :path
          (loop [frontier [[start]] seen #{start}]
            (when (seq frontier)
              (or (first (filter #(owner-symbols (peek %)) frontier))
                  (let [next (vec (for [path frontier callee (callees (peek path))
                                        :when (not (seen callee))]
                                    (conj path callee)))]
                    (recur next (into seen (map peek next))))))))))
 (prn :platform-count (count platforms) :old-offenders (count (filter old platforms)) :fixed-offenders (vec (filter fixed platforms))))
