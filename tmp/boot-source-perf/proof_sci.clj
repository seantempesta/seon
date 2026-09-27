;; Output-changing analyzer edit on a throwaway branch (lane boot-source-perf-opus,
;; 2026-09-27). Evaluate each form through MCP `eval_clj` mode sci, branch
;; `boot-perf-edit` (made with the `branch` tool; retired after). The branch's
;; SCI world interprets the edited definition and its affected callers; the
;; JVM's compiled Vars are untouched.
;;
;; 1. `base`: derive the 20 sample files with the published analyzer.
;; 2. Install the edit (below): `seon.fn.analyzer/literal-value?` no longer
;;    counts a string as self-evaluating data.
;; 3. `edited`: derive the same files again; compare.
;;
;; Measured 2026-09-27 (head 6ab9977e, e999fdd1b archive): the edit changed the
;; rows of exactly 4 files (src/seon/db.clj 1 row, src/seon/fn.clj 2,
;; test/seon/fn_test.clj 1, test/seon/schema/vector_storage_test.clj 1). The
;; trace (proof.clj :trace :exercising-literal-value?) names the files a key over
;; exercised definitions would invalidate: a superset (sound, not exact).
;;
;; Gate finding: the same install of an edit to seon.program/test-markers (even
;; a behavior-identical one), seon.db/may-read-vectors? and
;; seon.schema.datahike/reads-cells? is refused by the install gate because
;; their reaching tests are red in the gate's candidate evaluation (unresolved
;; `with-vector-world`, `throwaway-test-var`; "the canonical fixture needs an
;; executing test handle"), whatever the edit.
#_:clj-kondo/ignore
(comment
  (def base
    (let [sample ["src/seon/id.clj" "src/seon/db.clj" "src/seon/fn.clj" "src/seon/program.cljc"
                  "src/seon/fn/signature.cljc" "src/seon/render/value.clj" "src/my/program.clj"
                  "src/seon/cluster/source.clj" "src/seon/schema/datahike.clj" "src/seon/flow.clj"
                  "test/seon/db_test.clj" "test/seon/fn_test.clj" "test/seon/test_support.clj"
                  "test/seon/id_test.clj" "test/seon/schema/vector_storage_test.clj"
                  "test/seon/render/root_pull_test.clj" "src/seon/sci/reader.cljc" "src/seon/cache.clj"
                  "src/seon/env.clj" "test/seon/cluster/source_test.clj"]
          store (:seon.store/store (#'seon.cluster.boot/selected-instance "default"))
          head (:seon.source/commit-id (seon.cluster.source/current store))
          roots (#'seon.cluster/publication-roots)
          directory (:seon.fn/root roots)
          database (seon.cluster.source/database store head)
          stored (into {} (seon.db/q '[:find ?p ?d :where [?f :seon.fn.file/relative-path ?p]
                                       [?f :seon.fn.file/digest ?d]] database))]
      {:rows (into {} (map (fn [path]
                             (let [[observed captured] (seon.cluster.source/capture-paths directory [path])]
                               [path (set (seon.schema/call-with-projection
                                           (seon.schema/declaration-projection (seon.schema.edn/packaged-forms))
                                           (fn [] (seon.fn/analyze-entities
                                                   {:seon.fn/root directory :seon.fn/roots (:seon.fn/roots roots)
                                                    :seon.fn/changed-paths #{path} :seon.fn.analyzer/sources captured
                                                    :seon.source/relative-file-digests (merge stored observed)
                                                    :seon.source/previous-database database}))))])))
                   sample)}))

  (in-ns 'seon.fn.analyzer)
  (defn- literal-value?
    "PROBE EDIT (throwaway branch boot-perf-edit): a string is no longer self-evaluating data."
    {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
    [value]
    (boolean
     (or (nil? value) (boolean? value) (number? value)
         (char? value) (keyword? value)
         (and (seq? value) (= 'quote (first value)) (= 2 (count value)))
         (and (or (vector? value) (set? value)) (every? literal-value? value))
         (and (map? value) (every? literal-value? (mapcat identity value))))))
  (in-ns 'user)

  ;; `edited` is `base`'s form evaluated again after the edit, then:
  (into (sorted-map)
        (keep (fn [[p rs]] (let [b (get-in base [:rows p])]
                             (when (not= b rs) [p (count (clojure.set/difference rs b))]))))
        (:rows edited)))
