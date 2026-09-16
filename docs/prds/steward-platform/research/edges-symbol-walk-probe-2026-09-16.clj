;; Evaluate this file to obtain a function; pass one immutable database value.
;; No Vars, transactions, threads, files, or retained caches are created.
;; Before reset records the actual ref-index walk; after reset records both
;; symbol walks. Pass the first report's targets to the second invocation.
;; This measures calls + references only, not declared dispatch/file uncertainty.
(fn [{database :seon.db/db
      supplied-targets :seon.measurement/targets
      repetitions :seon.measurement/repetitions
      remaining-ms :seon.measurement/remaining-ms
      :or {repetitions 5 remaining-ms 30000}}]
  (let [dependency-datoms (requiring-resolve 'datahike.api/datoms)
        dependency-pull (requiring-resolve 'datahike.api/pull)
        digest (requiring-resolve 'seon.id/digest)
        started (System/nanoTime)
        deadline (+ started (* 1000000 remaining-ms))
        check! (fn []
                 (when (> (System/nanoTime) deadline)
                   (throw (ex-info "Reverse-walk measurement exceeded its bound."
                                   {:seon.measurement/remaining-ms remaining-ms}))))
        datoms (fn [index & components]
                 (check!)
                 (apply dependency-datoms database index components))
        schemas (into {}
                      (map (fn [attribute]
                             [attribute (dependency-pull
                                         database [:db/valueType :db/index :db/cardinality]
                                         [:db/ident attribute])]))
                      [:seon.fn/calls :seon.fn/references])
        types (set (map :db/valueType (vals schemas)))
        _ (when-not (and (pos-int? repetitions)
                         (or (= types #{:db.type/ref})
                             (= types #{:db.type/symbol})))
            (throw (ex-info "Measure a complete ref or symbol publication, never a mixed schema."
                            {:seon.measurement/schemas schemas})))
        symbol-storage? (= types #{:db.type/symbol})
        name-of (fn [value] (if (symbol? value) value (symbol value)))
        identities (fn []
                     (into {} (map (fn [d] [(:e d) (name-of (:v d))]))
                           (concat (datoms :aevt :seon.fn/sym)
                                   (datoms :aevt :seon.test/sym))))
        population (identities)
        edges (vec (concat (datoms :aevt :seon.fn/calls)
                           (datoms :aevt :seon.fn/references)))
        targets (or supplied-targets
                    (->> edges
                         (map #(if symbol-storage? (:v %) (get population (:v %))))
                         (remove nil?) frequencies
                         (sort-by (fn [[s n]] [(- n) (str s)]))
                         (take 8) (mapv first)))
        _ (when (or (empty? population) (empty? edges) (empty? targets))
            (throw (ex-info "Measurement requires a positive population, edge set and targets."
                            {:seon.measurement/entities (count population)
                             :seon.measurement/edges (count edges)})))
        modes (if symbol-storage? [:symbol-naive :symbol-mapped] [:ref])
        run (fn [mode]
              (let [begin (System/nanoTime)
                    names (identities)
                    ids (into {} (map (fn [[e s]] [s e])) names)
                    caller-name (if (= mode :symbol-naive)
                                  (fn [e]
                                    (some-> (or (first (datoms :eavt e :seon.fn/sym))
                                                (first (datoms :eavt e :seon.test/sym)))
                                            :v name-of))
                                  names)
                    reached
                    (into {}
                          (map
                           (fn [target]
                             [target
                              (loop [pending (if symbol-storage? [target]
                                                 (if-let [e (get ids target)] [e] []))
                                     seen #{}]
                                (check!)
                                (if-let [node (peek pending)]
                                  (if (seen node)
                                    (recur (pop pending) seen)
                                    (let [incoming (concat (datoms :avet :seon.fn/calls node)
                                                           (datoms :avet :seon.fn/references node))
                                          callers (mapv (if symbol-storage?
                                                          #(or (caller-name (:e %))
                                                               (throw (ex-info "Caller has no identity."
                                                                               {:seon.measurement/entity (:e %)})))
                                                          :e) incoming)]
                                      (recur (into (pop pending) callers) (conj seen node))))
                                  (if symbol-storage? seen (into #{} (map names) seen))))]))
                          targets)]
                {:seon.measurement/ms (/ (- (System/nanoTime) begin) 1000000.0)
                 :seon.measurement/reached reached}))
        warmed (into {} (map (fn [mode] [mode (run mode)])) modes)
        expected (:seon.measurement/reached (get warmed (first modes)))
        _ (doseq [[mode result] warmed]
            (when-not (= expected (:seon.measurement/reached result))
              (throw (ex-info "Reverse walks disagree." {:seon.measurement/mode mode}))))
        samples (reduce (fn [acc round]
                          (reduce (fn [acc mode]
                                    (let [result (run mode)]
                                      (when-not (= expected (:seon.measurement/reached result))
                                        (throw (ex-info "Measured walk changed its answer."
                                                        {:seon.measurement/mode mode})))
                                      (update acc mode (fnil conj []) (:seon.measurement/ms result))))
                                  acc (if (even? round) modes (reverse modes))))
                        {} (range repetitions))]
    {:seon.measurement/basis (:max-tx database)
     :seon.measurement/schemas schemas
     :seon.measurement/entities (count population)
     :seon.measurement/edges (count edges)
     :seon.measurement/edge-value-bytes
     (reduce + (map #(alength (.getBytes (pr-str (:v %)) "UTF-8")) edges))
     :seon.measurement/targets targets
     :seon.measurement/reachable-counts (update-vals expected count)
     :seon.measurement/reachable-digest (digest 64 (vec (into (sorted-map) (update-vals expected #(vec (sort %))))))
     :seon.measurement/samples-ms samples
     :seon.measurement/statistics
     (update-vals samples
                  (fn [values]
                    (let [ordered (vec (sort values))
                          percentile #(nth ordered (dec (int (Math/ceil (* % (count ordered))))))]
                      {:seon.measurement/median-ms (percentile 0.5)
                       :seon.measurement/p95-ms (percentile 0.95)})))}))
