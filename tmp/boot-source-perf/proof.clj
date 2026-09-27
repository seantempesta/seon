;; Per-file derivation reuse: named proofs, armed, timed, in default's JVM.
;; Lane boot-source-perf-opus, 2026-09-27.
;;
;;   (eval (read-string (slurp "tmp/boot-source-perf/proof.clj")))
;;   (deref (.get (System/getProperties) "seon.boot-perf.proof") 300000 :running)
;;
;; No Var is interned: helpers are locals and the result lives in a System
;; property. Every write goes to a throwaway branch retired in `finally`.
;; Commits: P = 6ab98892 (published by the 441988b49 boot), H = 6ab99276 (the
;; cd4ca3d39 boot), the head = the e999fdd1b boot. The source directory is the
;; running archive's (e999fdd1b), so the change sets replayed are P->e999 and
;; H->e999.
;;
;; Proofs:
;;  :trace      which armed definitions one file's derivation exercises (analysis,
;;              then populate), over 20 files of different kinds
;;  :boundary   the exercised definitions in three classes, from the stored call
;;              graph: output (reached from the value producers analyze-entities
;;              and desired-entities without entering the database owner),
;;              orchestration (reached only from populate-source!), plumbing
;;              (reached only through a seon.db / seon.schema.datahike entry
;;              point: its values reach rows only as read results or refusals)
;;  :natural    for P->head and H->head: which exercised definitions changed
;;              digest, split by that boundary, beside today's seal reach
;;  :keyed=full the pass re-deriving only the changed files writes exactly what
;;              the pass re-deriving every file writes (tx reports set-equal)
#_:clj-kondo/ignore
(.put
 (System/getProperties) "seon.boot-perf.proof"
 (future
   (let [inst (get @seon.operator.runtime/running-instances "default")
         store (:seon.store/store inst)
         head (:seon.source/commit-id (seon.cluster.source/current store))
         P #uuid "6ab98892-c11d-5993-9ad6-d9f77c268a6c"
         H #uuid "6ab99276-59fb-5898-b59e-116bf5adada1"
         roots (#'seon.cluster/publication-roots)
         directory (:seon.fn/root roots)
         projection (seon.schema/declaration-projection (seon.schema.edn/packaged-forms))
         zero (apply str (repeat 64 "0"))
         now #(System/nanoTime)
         ms (fn [t] (/ (Math/round (/ (- (System/nanoTime) t) 1e4)) 100.0))
         ;; Armed calls per definition, JVM-wide: `seon.profile` cells.
         snap (fn [] (into {} (map (fn [c] [c (.sum ^java.util.concurrent.atomic.LongAdder
                                                 (:seon.profile/call-counter c))]))
                           (seon.profile/cells)))
         grown (fn [s0 s1] (into #{} (keep (fn [[c n]] (when (< (get s0 c 0) n) (:seon.profile/sym c)))) s1))
         traced (fn [f] (let [s0 (snap) v (f) s1 (snap)] [v (grown s0 s1)]))
         file-digests (fn [db] (into {} (datahike.api/q '[:find ?p ?d :where [?f :seon.fn.file/relative-path ?p]
                                                          [?f :seon.fn.file/digest ?d]] db)))
         definition-digests (fn [db] (into {} (datahike.api/q '[:find ?s ?d :where [?f :seon.fn/sym ?s]
                                                                [?f :seon.program/definition-digest ?d]] db)))
         calls (fn [db] (reduce (fn [m [a b]] (update m a (fnil conj #{}) b)) {}
                                (datahike.api/q '[:find ?a ?b :where [?f :seon.fn/sym ?a] [?f :seon.fn/calls ?b]] db)))
         with-db (fn [commit f] (let [db (seon.cluster.source/database store commit)]
                                  (try (f db) (finally (datahike.api/release-materialized-db db)))))
         analyze (fn [database stored paths]
                   (let [[observed captured] (seon.cluster.source/capture-paths directory (vec paths))]
                     (seon.fn/analyze-entities
                      {:seon.fn/root directory :seon.fn/roots (:seon.fn/roots roots)
                       :seon.fn/changed-paths (set paths) :seon.fn.analyzer/sources captured
                       :seon.source/relative-file-digests (merge stored observed)
                       :seon.source/previous-database database})))
         ;; One publication's populate on a throwaway branch of `base`; the
         ;; report's datoms named by identity, their values (entity ids aside).
         populate (fn [base database stored changed rows]
                    (let [branch (keyword (str "boot-perf-proof-" (System/nanoTime)))
                          _ (seon.cluster.registry/branch! {:seon.store/store store :seon.cluster.registry/from base
                                                            :seon.store/branch branch})
                          conn (seon.cluster.store/open-branch! store branch)]
                      (try
                        (let [result (seon.db/call-with-custody
                                      {:seon.db/connection conn}
                                      (fn [] (seon.cluster/populate-source!
                                              {:seon.db/connection conn :seon.source/digest zero
                                               :seon.program/rows rows :seon.fn/root directory
                                               :seon.fn/roots (:seon.fn/roots roots)
                                               :seon.source/relative-file-digests stored
                                               :seon.source/previous-database database
                                               :seon.fn/changed-paths (set changed)
                                               :seon.source/change-classes #{:program :schema-resource}})))
                              report (:seon.db/transaction-report result)
                              after (datahike.api/db conn)
                              ident (fn [e] (let [m (datahike.api/pull after [:seon.fn/sym :seon.test/sym :seon.ns/name
                                                                              :seon.fn.file/relative-path :seon.schema/key
                                                                              :seon.lint/id] e)]
                                              (or (first (vals (dissoc m :db/id))) :component)))]
                          {:error (:seon.error/message result)
                           :tx (into #{} (comp (remove #(= :db/txInstant (:a %)))
                                               (map (fn [d] [(ident (:e d)) (:a d)
                                                             (let [v (:v d)] (if (number? v) :n v)) (:added d)])))
                                     (:tx-data report))
                           :definitions (definition-digests after)})
                        (finally
                          (datahike.api/release conn)
                          (seon.cluster.registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))
         sample ["src/seon/id.clj" "src/seon/db.clj" "src/seon/fn.clj" "src/seon/program.cljc"
                 "src/seon/fn/signature.cljc" "src/seon/render/value.clj" "src/my/program.clj"
                 "src/seon/cluster/source.clj" "src/seon/schema/datahike.clj" "src/seon/flow.clj"
                 "test/seon/db_test.clj" "test/seon/fn_test.clj" "test/seon/test_support.clj"
                 "test/seon/id_test.clj" "test/seon/schema/vector_storage_test.clj"
                 "test/seon/render/root_pull_test.clj" "src/seon/sci/reader.cljc" "src/seon/cache.clj"
                 "src/seon/env.clj" "test/seon/cluster/source_test.clj"]]
     (try
       (seon.schema/call-with-projection
        projection
        (fn []
          (let [;; ---- :trace ------------------------------------------------
                t-trace (now)
                noise (second (traced #(Thread/sleep 1500)))
                per-file
                (with-db head
                  (fn [database]
                    (let [stored (file-digests database)]
                      (into {}
                            (map (fn [path]
                                   (let [[rows a1] (traced #(analyze database stored [path]))
                                         [_ a2] (traced #(analyze database stored [path]))
                                         [pop p1] (traced #(populate head database stored [path] rows))]
                                     [path {:rows (count rows)
                                            :analysis (clojure.set/difference (clojure.set/intersection a1 a2) noise)
                                            :populate (clojure.set/difference p1 noise)
                                            ;; re-deriving an unchanged file writes nothing but
                                            ;; what `index!` stamps on every publication
                                            :written (frequencies (map second (:tx pop)))}])))
                            sample))))
                trace-ms (ms t-trace)
                analysis-union (reduce clojure.set/union (map :analysis (vals per-file)))
                populate-union (reduce clojure.set/union (map :populate (vals per-file)))
                exercised (clojure.set/union analysis-union populate-union)
                ;; ---- :boundary -----------------------------------------------
                graph (with-db head calls)
                owner? (fn [s] (contains? #{"seon.db" "seon.schema.datahike"} (namespace s)))
                ;; reached from `roots` without entering the database owner
                reach (fn [roots]
                        (loop [seen #{} pending (vec roots)]
                          (if-let [s (peek pending)]
                            (let [pending (pop pending)]
                              (if (or (seen s) (owner? s))
                                (recur seen pending)
                                (recur (conj seen s) (into pending (get graph s)))))
                            seen)))
                ;; The value producers: rows come from `analyze-entities`, the
                ;; stored entity from `desired-entities` (index! writes the diff of
                ;; desired against stored, so stored = desired when nothing is
                ;; written). The rest of populate orchestrates: schema adoption,
                ;; transaction compilation, reconciliation.
                output (reach #{'seon.fn/analyze-entities 'seon.fn/desired-entities})
                orchestration (clojure.set/difference (reach #{'seon.cluster/populate-source!}) output)
                output-exercised (clojure.set/intersection exercised output)
                orchestration-exercised (clojure.set/intersection exercised orchestration)
                plumbing-exercised (clojure.set/difference exercised output orchestration)
                entry-points (into (sorted-set)
                                   (for [s output-exercised c (get graph s) :when (owner? c)] c))
                ;; today's seal: the static reach of seon.fn/analyze-entities
                seal-reach (with-db head (fn [db] (into #{} (map first) (#'seon.cluster/analyzer-declarations db))))
                by-ns (fn [syms] (into (sorted-map) (frequencies (map namespace syms))))
                ;; ---- :natural ------------------------------------------------
                head-defs (with-db head definition-digests)
                natural (into {}
                              (map (fn [[label base]]
                                     (let [base-defs (with-db base definition-digests)
                                           changed (into #{} (filter #(not= (get base-defs %) (get head-defs %)))
                                                         (into (set (keys base-defs)) (keys head-defs)))]
                                       [label {:changed-definitions (count changed)
                                               :in-seal-reach (count (clojure.set/intersection changed seal-reach))
                                               :exercised (by-ns (clojure.set/intersection changed exercised))
                                               :output (vec (sort (clojure.set/intersection changed output-exercised)))
                                               :output-static (vec (sort (clojure.set/intersection changed output)))
                                               :orchestration (vec (sort (clojure.set/intersection changed orchestration-exercised)))
                                               :plumbing (count (clojure.set/intersection changed plumbing-exercised))}])))
                              {:P->head P :H->head H})
                ;; ---- :keyed=full ---------------------------------------------
                replay (fn [base]
                         (with-db base
                           (fn [database]
                             (let [stored (file-digests database)
                                   inventory (seon.cluster.source/discover-paths directory (:seon.source/roots roots))
                                   files (seon.cluster.source/path-digests directory inventory)
                                   changed (into [] (comp (distinct) (remove #(= (get files %) (get stored %))))
                                                 (sort (concat (keys files) (keys stored))))
                                   inputs (merge stored files)
                                   t0 (now)
                                   keyed-rows (analyze database inputs changed)
                                   keyed-analysis (ms t0)
                                   t1 (now)
                                   keyed (populate base database inputs changed keyed-rows)
                                   keyed-populate (ms t1)
                                   all (into (set changed) inventory)
                                   t2 (now)
                                   full-rows (analyze database inputs all)
                                   full-analysis (ms t2)
                                   t3 (now)
                                   full (populate base database inputs all full-rows)
                                   full-populate (ms t3)]
                               {:changed-paths (count changed)
                                :rows {:keyed (count keyed-rows) :full (count full-rows)}
                                :ms {:keyed-analysis keyed-analysis :keyed-populate keyed-populate
                                     :full-analysis full-analysis :full-populate full-populate}
                                :errors [(:error keyed) (:error full)]
                                :tx-datoms {:keyed (count (:tx keyed)) :full (count (:tx full))}
                                :tx-equal? (= (:tx keyed) (:tx full))
                                :only-full (vec (take 10 (clojure.set/difference (:tx full) (:tx keyed))))
                                :only-keyed (vec (take 10 (clojure.set/difference (:tx keyed) (:tx full))))
                                :definitions-equal? (= (:definitions keyed) (:definitions full))}))))
                keyed=full (if (System/getProperty "seon.boot-perf.quick") :skipped {:P->head (replay P) :H->head (replay H)})]
            {:head head
             :trace {:ms trace-ms :noise-definitions (count noise)
                     :per-file (update-vals per-file #(-> % (update :analysis count) (update :populate count)))
                     :analysis-common (by-ns (reduce clojure.set/intersection (map :analysis (vals per-file))))
                     :analysis-union (by-ns analysis-union)
                     :populate-common (by-ns (reduce clojure.set/intersection (map :populate (vals per-file))))
                     :populate-union (by-ns populate-union)
                     :varying (into (sorted-map)
                                    (keep (fn [s] (let [n (count (filter #(or (contains? (:analysis %) s)
                                                                              (contains? (:populate %) s))
                                                                         (vals per-file)))]
                                                    (when (< n (count sample)) [s n]))))
                                    exercised)
                     :exercising-literal-value? (vec (sort (keep (fn [[p m]] (when (contains? (:analysis m) 'seon.fn.analyzer/literal-value?) p)) per-file)))}
             :boundary {:exercised (count exercised)
                        :output (by-ns output-exercised) :output-count (count output-exercised)
                        :orchestration (by-ns orchestration-exercised) :orchestration-count (count orchestration-exercised)
                        :orchestration-syms (vec (sort orchestration-exercised))
                        :plumbing (by-ns plumbing-exercised) :plumbing-count (count plumbing-exercised)
                        :entry-points entry-points
                        :output-syms (vec (sort output-exercised))
                        :seal-reach (count seal-reach)
                        :output-outside-seal (vec (sort (clojure.set/difference output-exercised seal-reach)))}
             :natural natural
             :keyed=full keyed=full})))
       (catch Throwable e {:error (str e) :data (ex-data e)})))))
