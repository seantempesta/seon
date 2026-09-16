; Beat 1 research only. Evaluate forms through MCP jvm mode on default.
; No production Vars are replaced, tests run, or database facts transacted.
; Capture exactly once with (seon.db/db (seon.operator/connection "default")).
; The assigned dated research filename uses hyphens rather than classpath underscores.
#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns reach-digest-probe-2026-09-16
 (:require [clojure.edn] [clojure.walk] [seon.db] [seon.fn] [seon.id]))

(defn capture
 "Capture program rows once from an explicitly carried database value."
 [database]
 (let [ids (seon.db/q '[:find [?e ...] :where (or [?e :seon.fn/sym] [?e :seon.test/sym])] database)
       rows (seon.db/pull-many database '[:db/id :seon.fn/sym :seon.fn/source :seon.fn/spec :seon.fn/keywords {:seon.fn/calls [:db/id]} :seon.test/sym :seon.test/source {:seon.test/subject [:db/id]} :seon.test/pending-subject :seon.test/fixture-observation :seon.test/pass-count :seon.test/fail-count :seon.test/error-count :seon.test/run-basis-t {:seon.test/run [:seon.test.run/branch]}] ids)]
  {:reach/rows (into {} (map (juxt :db/id identity)) rows)
   :reach/forms (:seon.schema.projection/forms (seon.db/carried-projection database))}))

(defn named-keys
 "Qualified keyword references in a parsed form."
 [form]
 (into #{} (filter qualified-keyword?) (tree-seq coll? seq form)))
(defn schema-closure
 "Follow named schema forms, including aliases, once per visited key."
 [forms seeds]
 (loop [todo (seq seeds) seen #{}]
  (if-let [k (first todo)]
   (if (or (seen k) (not (find forms k)))
    (recur (next todo) seen)
    (recur (concat (next todo) (named-keys (get forms k))) (conj seen k)))
   seen)))
(defn ordered
 "Canonicalize unordered collections before the identity owner's printer."
 [form]
 (clojure.walk/postwalk
  (fn [x] (cond (map? x) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) x)
                (set? x) (into (sorted-set-by #(compare (pr-str %1) (pr-str %2))) x)
                :else x)) form))
(defn closures
 "Derive every test closure with one shared-rule query and one row capture."
 [database]
 (let [{rows :reach/rows forms :reach/forms} (capture database)
       pairs (seon.db/q '[:find ?test ?target :in $ % :where (test-reaches ?test ?target)]
                        database @#'seon.fn/test-reach-rules)
       reached (reduce (fn [m [t f]] (update m t (fnil conj #{}) f)) {} pairs)
       by-symbol (into {} (keep (fn [[e r]] (when-let [s (:seon.fn/sym r)] [s e]))) rows)
       adjacency (into {} (map (fn [[e r]] [e (mapv :db/id (:seon.fn/calls r))])) rows)
       follow (fn [start] (loop [todo (seq start) seen #{}]
                           (if-let [e (first todo)]
                            (if (seen e) (recur (next todo) seen)
                             (recur (concat (next todo) (get adjacency e)) (conj seen e))) seen)))
       row-keys (into {} (map (fn [[e r]] [e (schema-closure forms
                                (into (set (:seon.fn/keywords r))
                                 (when-let [s (:seon.fn/spec r)]
                                  (named-keys (clojure.edn/read-string s)))))])) rows)]
  (into (sorted-map)
   (for [[e r] rows :when (:seon.test/sym r)
         :let [pending (:seon.test/pending-subject r)
               targets (into (get reached e #{}) (when-let [p (get by-symbol pending)] (follow [p])))
               members (conj targets e)
               schema-keys (reduce into #{} (map row-keys members))
               parts [(mapv (fn [id] (let [v (get rows id)]
                                      [(or (:seon.test/sym v) (:seon.fn/sym v) [:reach/missing-row id])
                                       (or (:seon.test/source v) (:seon.fn/source v))
                                       (:seon.fn/spec v)]))
                            (sort-by #(pr-str (or (:seon.test/sym (get rows %)) (:seon.fn/sym (get rows %)) %)) members))
                      (mapv (fn [k] [k (ordered (get forms k))]) (sort schema-keys))
                      (when (and pending (not (get by-symbol pending))) [:reach/unresolved-subject pending])]]]
    [(:seon.test/sym r) {:reach/digest (seon.id/digest 64 parts)
                       :reach/function-count (count targets)
                       :reach/schema-count (count schema-keys)
                       :reach/targets targets
                       :reach/row r}]))))

; Historical comparison uses schema rows at the historical basis.
; as-of carries the current physical-schema projection, not historical forms.
; This in-memory traversal was compared against the SAME test-reach-rules
; above for all 1,753 tests (907,605 reached pairs): identical targets/digests.
; It avoids repeating the whole Datalog fixed point for 130 historical bases.
(defn historical-closures
 "Derive selected historical closures from that basis's rows and schema forms."
 [database test-symbols]
 (let [{rows :reach/rows} (capture database)
       forms (into {} (map (fn [[k s]] [k (clojure.edn/read-string s)]))
                   (seon.db/q '[:find ?k ?s :where [?e :seon.schema/key ?k] [?e :seon.schema/form ?s]] database))
       selected-ids (into #{} (keep (fn [[e r]] (when (test-symbols (:seon.test/sym r)) e))) rows)
       by-symbol (into {} (keep (fn [[e r]] (when-let [s (:seon.fn/sym r)] [s e]))) rows)
       adjacency (into {} (map (fn [[e r]] [e (mapv :db/id (:seon.fn/calls r))])) rows)
       follow (fn [start] (loop [todo (seq start) seen #{}]
                           (if-let [e (first todo)]
                            (if (seen e) (recur (next todo) seen)
                             (recur (concat (next todo) (get adjacency e)) (conj seen e))) seen)))
       pairs (for [t selected-ids
                         :let [r (get rows t)
                               roots (concat (map :db/id (:seon.fn/calls r))
                                             (when-let [s (:seon.test/subject r)] [(:db/id s)]))]
                         f (follow roots)] [t f])
       reached (reduce (fn [m [t f]] (update m t (fnil conj #{}) f)) {} pairs)
       needed (into (into selected-ids (map second pairs))
                    (mapcat (fn [t]
                              (when-let [p (get by-symbol (:seon.test/pending-subject (get rows t)))]
                                (follow [p])))
                            selected-ids))
       row-keys (into {} (map (fn [[e r]] [e (schema-closure forms
                                (into (set (:seon.fn/keywords r))
                                 (when-let [s (:seon.fn/spec r)]
                                  (named-keys (clojure.edn/read-string s)))))])) (select-keys rows needed))]
  (into (sorted-map)
   (for [[e r] rows :when (selected-ids e)
         :let [pending (:seon.test/pending-subject r)
               targets (into (get reached e #{}) (when-let [p (get by-symbol pending)] (follow [p])))
               members (conj targets e)
               schema-keys (reduce into #{} (map row-keys members))
               parts [(mapv (fn [id] (let [v (get rows id)]
                                      [(or (:seon.test/sym v) (:seon.fn/sym v) [:reach/missing-row id])
                                       (or (:seon.test/source v) (:seon.fn/source v))
                                       (:seon.fn/spec v)]))
                            (sort-by #(pr-str (or (:seon.test/sym (get rows %)) (:seon.fn/sym (get rows %)) %)) members))
                      (mapv (fn [k] [k (ordered (get forms k))]) (sort schema-keys))
                      (when (and pending (not (get by-symbol pending))) [:reach/unresolved-subject pending])]]]
    [(:seon.test/sym r) {:reach/digest (seon.id/digest 64 parts)
                       :reach/function-count (count targets)
                       :reach/schema-count (count schema-keys)
                       :reach/targets targets
                       :reach/row r}]))))

(defn reach-closure
 "The sorted-source/contract/schema digest and members of one test."
 [database test-symbol]
 (get (historical-closures database #{test-symbol}) test-symbol))

; Reproduction, one bounded MCP call per batch; do not launch a test JVM.
(comment
 (def reach-database (seon.db/db (seon.operator/connection "default")))
 (def started (System/nanoTime))
 (def reach-closures (closures reach-database))
 {:reach/elapsed-ms (/ (- (System/nanoTime) started) 1e6)
  :reach/tests (count reach-closures)}
 (def groups
  (vec (sort-by key
   (group-by (fn [[s c]] (:seon.test/run-basis-t (:reach/row c)))
    (filter (fn [[s c]]
     (let [r (:reach/row c)]
      (and (= (get-in reach-database [:config :branch])
              (get-in r [:seon.test/run :seon.test.run/branch]))
           (= 0 (:seon.test/fail-count r) (:seon.test/error-count r)))))
     reach-closures)))))
 ; Run a small subvec of groups per MCP call with timeout_ms 120000.
 (mapv (fn [[basis tests]]
   (let [old (historical-closures (seon.db/as-of reach-database basis)
                                 (set (map first tests)))]
    (mapv (fn [[s c]]
     {:reach/test s :reach/basis basis
      :reach/equal (= (:reach/digest c) (:reach/digest (get old s)))})
     tests)))
  (subvec groups 0 (min 3 (count groups)))))
