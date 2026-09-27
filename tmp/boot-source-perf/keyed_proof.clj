;; Per-file derivation keys, proven in default's JVM before the cluster.clj change
;; is loaded there (lane boot-source-perf-opus, 2026-09-27). The changed
;; definitions are read from the working tree's src/seon/cluster.clj and
;; evaluated as local fns in seon.cluster's namespace: no Var is interned.
;;
;;   (eval (read-string (slurp "tmp/boot-source-perf/keyed_proof.clj")))
;;   (deref (.get (System/getProperties) "seon.boot-perf.keyed") 300000 :running)
;;
;; For each base publication B and the running archive's files: the keys B's
;; files had under B's code (as if B's publication had stored them), the keys
;; after analyzing the changed files with this code, and the files a keyed
;; publication would re-derive beyond the changed ones, by cause. Then the
;; `literal-value?` edit: its definition digest changed makes every file whose
;; derivation that code reaches stale.
#_:clj-kondo/ignore
(.put
 (System/getProperties) "seon.boot-perf.keyed"
 (future
   (binding [*ns* (the-ns 'seon.cluster)]
     (try
       (let [source (with-open [r (java.io.PushbackReader. (clojure.java.io/reader "/Users/sean/src/seon/src/seon/cluster.clj"))]
                      (let [eof (Object.)]
                        (loop [forms {}]
                          (let [form (read {:eof eof :read-cond :allow} r)]
                            (if (identical? form eof)
                              forms
                              (recur (if (and (seq? form) (#{'defn 'defn-} (first form)))
                                       (assoc forms (second form) form)
                                       forms)))))))
             ;; (defn name doc? attr? [args] body...) -> (fn name [args] body...)
             as-fn (fn [name]
                     (let [[_ _ & more] (get source name)
                           more (drop-while #(or (string? %) (map? %)) more)]
                       (list* 'fn name more)))
             names '[analyzer-reach analyzer-declarations selected-analyzer-digest
                     derivation-schema-keys stored-derivation-schema-keys derivation-keys]
             fns (eval `(let [~@(mapcat (fn [n] [n (as-fn n)]) names)]
                          ~(into {} (map (fn [n] [(keyword n) n])) names)))
             {:keys [selected-analyzer-digest derivation-schema-keys stored-derivation-schema-keys
                     derivation-keys analyzer-declarations]} fns
             inst (get @seon.operator.runtime/running-instances "default")
             store (:seon.store/store inst)
             head (:seon.source/commit-id (seon.cluster.source/current store))
             roots (#'seon.cluster/publication-roots)
             directory (:seon.fn/root roots)
             fn-roots (:seon.fn/roots roots)
             now #(System/nanoTime)
             ms (fn [t] (/ (Math/round (/ (- (System/nanoTime) t) 1e4)) 100.0))
             derivation-input? (fn [inputs p] (and (some #(clojure.string/starts-with? p (str % "/")) fn-roots)
                                                   (some #(clojure.string/ends-with? p %) [".clj" ".cljc"])
                                                   (contains? inputs p)))
             file-digests (fn [db] (into {} (datahike.api/q '[:find ?p ?d :where [?f :seon.fn.file/relative-path ?p]
                                                               [?f :seon.fn.file/digest ?d]] db)))
             inventory (seon.cluster.source/discover-paths directory (:seon.source/roots roots))
             files (seon.cluster.source/path-digests directory inventory)
             packaged (seon.schema/declaration-projection (seon.schema.edn/packaged-forms))
             prove (fn [base]
                     (let [db (seon.cluster.source/database store base)]
                       (try
                         (let [stored (file-digests db)
                               base-projection (seon.db/carried-projection db)
                               base-declared (set (keys (:seon.schema.projection/forms base-projection)))
                               base-inputs (into #{} (filter #(derivation-input? stored %)) (keys stored))
                               t0 (now)
                               base-code (selected-analyzer-digest db #{} [] stored stored)
                               base-keys (derivation-keys base-code base-projection stored
                                                          (stored-derivation-schema-keys db base-declared) base-inputs)
                               base-ms (ms t0)
                               changed (into #{} (filter #(not= (get stored %) (get files %)))
                                             (into (set (keys stored)) (keys files)))
                               inputs (merge stored files)
                               t1 (now)
                               rows (seon.schema/call-with-projection
                                     packaged
                                     #(let [[_ captured] (seon.cluster.source/capture-paths directory (vec changed))]
                                        (seon.fn/analyze-entities
                                         {:seon.fn/root directory :seon.fn/roots fn-roots :seon.fn/changed-paths changed
                                          :seon.fn.analyzer/sources captured :seon.source/relative-file-digests inputs
                                          :seon.source/previous-database db})))
                               analysis-ms (ms t1)
                               declared (set (keys (:seon.schema.projection/forms packaged)))
                               t2 (now)
                               code (selected-analyzer-digest db changed rows inputs files)
                               schema-keys (merge (stored-derivation-schema-keys db declared)
                                                  (derivation-schema-keys declared rows))
                               new-inputs (into #{} (filter #(derivation-input? inputs %)) (concat (keys inputs) changed))
                               keys-now (derivation-keys code packaged inputs schema-keys new-inputs)
                               stale (into #{} (filter #(and (not (changed %)) (not= (get base-keys %) (get keys-now %))))
                                           new-inputs)
                               key-ms (ms t2)
                               ;; causes: re-key each stale file with one component held at its base value
                               code-only (derivation-keys base-code packaged inputs schema-keys stale)
                               schema-only (derivation-keys code base-projection inputs schema-keys stale)
                               ;; the literal-value? edit: its definition digest changes
                               edited-rows (mapv #(if (= 'seon.fn.analyzer/literal-value? (:seon.fn/sym %))
                                                    (assoc % :seon.program/definition-digest (apply str (repeat 64 "e")))
                                                    %)
                                                 (concat rows
                                                         (when-not (some #(= 'seon.fn.analyzer/literal-value? (:seon.fn/sym %)) rows)
                                                           [(-> (seon.db/pull db '[:seon.fn/sym :seon.program/definition-digest
                                                                                   {:seon.fn/file [:seon.fn.file/relative-path]}
                                                                                   :seon.fn/calls]
                                                                              [:seon.fn/sym 'seon.fn.analyzer/literal-value?])
                                                                (update :seon.fn/calls vec))])))
                               edit-code (selected-analyzer-digest db (conj changed "src/seon/fn/analyzer.clj") edited-rows inputs files)
                               edit-keys (derivation-keys edit-code packaged inputs schema-keys new-inputs)]
                           {:base base :changed (count changed) :derivation-inputs (count new-inputs)
                            :code-changed? (not= base-code code)
                            :stale (count stale)
                            :stale-sample (vec (take 8 (sort stale)))
                            :stale-by-cause {:code (count (filter #(= (get code-only %) (get base-keys %)) stale))
                                             :schema (count (filter #(= (get schema-only %) (get base-keys %)) stale))}
                            :ms {:base-keys base-ms :analysis analysis-ms :keys key-ms}
                            :literal-value?-edit {:code-changed? (not= edit-code code)
                                                  :invalidated (count (filter #(not= (get edit-keys %) (get keys-now %)) new-inputs))
                                                  :covers-the-4? (every? #(not= (get edit-keys %) (get keys-now %))
                                                                         ["src/seon/db.clj" "src/seon/fn.clj" "test/seon/fn_test.clj"
                                                                          "test/seon/schema/vector_storage_test.clj"])}
                            :reach (count (analyzer-declarations db))})
                         (finally (datahike.api/release-materialized-db db)))))]
         {:head head
          :proofs (mapv prove [#uuid "6ab99276-59fb-5898-b59e-116bf5adada1"
                               #uuid "6ab9977e-f06d-5d02-a808-2ef6ccfe68ed"])})
       (catch Throwable e {:error (str e) :data (ex-data e)})))))
