; Writer census — the forms run on default (jvm mode, explicit custody, read only).
; R1, 2026-09-16. Every form below was evaluated through mcp__seon__eval_clj
; (mode jvm, session writer-census) against cluster `default` at basis 536871970.
; Nothing here transacts; the only writes are EDN files under tmp/.
(comment

  ;; 1. Custody and projection. Without the projection every :seon.fn query
  ;;    answers 3 rows (projection-fallback warning), so bind it first.
  (let [connection (seon.operator/connection "default")
        database @connection
        projection (seon.schema/projection-from-database database)]
    (seon.schema/call-with-projection
     projection
     (fn []
       [(count (seon.db/q '[:find [?s ...] :where [?f :seon.fn/sym ?s]] database))
        (count (seon.db/q '[:find [?n ...] :where [?e :seon.ns/name ?n]] database))])))
  ;; => [4764 437]

  ;; 2. THE QUERY — the writer census. Every function that reaches
  ;;    seon.db/transact! directly or transitively through :seon.fn/calls,
  ;;    joined to the installed attributes its source names literally
  ;;    (:seon.fn/keywords ∩ installed attributes). The reach rules are the
  ;;    two `function-reaches` clauses of src/seon/fn.clj:863.
  (let [connection (seon.operator/connection "default")
        database @connection
        projection (seon.schema/projection-from-database database)]
    (seon.schema/call-with-projection
     projection
     (fn []
       (let [rules '[[(function-reaches ?f ?t) [?f :seon.fn/calls ?t]]
                     [(function-reaches ?f ?t)
                      [?f :seon.fn/calls ?c]
                      (function-reaches ?c ?t)]]
             transitive
             (set (seon.db/q '[:find [?sym ...]
                               :in $ % ?target-sym
                               :where
                               [?target :seon.fn/sym ?target-sym]
                               (function-reaches ?f ?target)
                               [?f :seon.fn/sym ?sym]]
                             database rules "seon.db/transact!"))
             direct
             (set (seon.db/q '[:find [?sym ...]
                               :in $ ?t
                               :where
                               [?target :seon.fn/sym ?t]
                               [?f :seon.fn/calls ?target]
                               [?f :seon.fn/sym ?sym]]
                             database "seon.db/transact!"))
             installed (set (filter keyword? (keys (:schema database))))
             rows (vec (for [sym (sort transitive)
                             :let [entity (seon.db/pull
                                           database
                                           [:seon.fn/sym :seon.fn/private?
                                            :seon.fn/keywords
                                            {:seon.fn/ns [:seon.ns/name]}]
                                           [:seon.fn/sym sym])
                                   ns-name (get-in entity [:seon.fn/ns :seon.ns/name])]]
                         {:sym sym
                          :ns ns-name
                          :direct? (contains? direct sym)
                          ;; probe-local classification only; production code
                          ;; never classifies by name (AGENTS.md §2.2).
                          :test-ns? (boolean (re-find #"-test$|^seon.test-support"
                                                      (str ns-name)))
                          :attrs (vec (sort (filter installed
                                                    (:seon.fn/keywords entity))))}))]
         (spit "tmp/orchestrator/wave3/research/writer-census-raw.edn" (pr-str rows))
         [(count rows)
          (count (remove :test-ns? rows))
          (count (filter #(and (:direct? %) (not (:test-ns? %))) rows))]))))
  ;; => [311 138 55]  reachers / production-namespace reachers / direct production writers
  ;;    the transitive closure alone: 311 rows in 3 066 ms.

  ;; 3. The stored form of every attribute a production writer names:
  ;;    installed :db/valueType, cardinality, component, unique, holder count,
  ;;    and one live sample value (the median datom in :aevt order).
  (let [connection (seon.operator/connection "default")
        database @connection
        projection (seon.schema/projection-from-database database)]
    (seon.schema/call-with-projection
     projection
     (fn []
       (let [census (read-string
                     (slurp "tmp/orchestrator/wave3/research/writer-census-raw.edn"))
             attrs (sort (set (mapcat :attrs (remove :test-ns? census))))
             schema (:schema database)
             rows (for [a attrs
                        :let [s (get schema a)
                              ds (vec (seon.db/datoms database :aevt a))
                              n (count ds)
                              raw (when (pos? n) (pr-str (:v (nth ds (quot n 2)))))]]
                    {:attr a
                     :value-type (:db/valueType s)
                     :card (:db/cardinality s)
                     :component? (boolean (:db/isComponent s))
                     :unique (:db/unique s)
                     :holders n
                     :sample (when raw (subs raw 0 (min 200 (count raw))))})]
         (spit "tmp/orchestrator/wave3/research/written-attributes-raw.edn"
               (pr-str (vec rows)))
         (count rows)))))
  ;; => 178 written attributes: 68 string, 46 ref, 31 long, 12 instant,
  ;;    10 keyword, 6 symbol, 4 boolean, 1 uuid.

  ;; 4. Reconciliation against the hand list in namespace-data-model §7.3 —
  ;;    which proposed attributes are installed now, and how many holders.
  (let [connection (seon.operator/connection "default")
        database @connection
        projection (seon.schema/projection-from-database database)]
    (seon.schema/call-with-projection
     projection
     (fn []
       (let [schema (:schema database)]
         (vec (for [k [:seon.error/fn :seon.error/steward :seon.error/process
                       :seon.error/throwable-class :seon.error.occurrence/id
                       :seon.error.occurrence/count
                       :seon.ai.usage/prompt-tokens :seon.ai.usage/total-tokens
                       :seon.ai.attempt/usage-edn :seon.ai.attempt/settings-edn
                       :seon.ai.attempt/model :seon.cluster.eval/triage-edn
                       :seon.effect/capability :seon.eval/renderer
                       :seon.eval/renderer-fn :seon.test/subject :seon.fn/file
                       :seon.ns/steward :seon.issue/id :seon.issue/functions
                       :seon.issue/tests :seon.lint/id :seon.render/ai
                       :seon.render/html :seon.fn/arglists :seon.fn.arity/arguments
                       :seon.test/failing-assertions :seon.fn/sym :seon.test/sym
                       :seon.ns/name]]
                [k
                 (boolean (get schema k))
                 (:db/valueType (get schema k))
                 (when (get schema k)
                   (count (seon.db/datoms database :aevt k)))]))))))
  ;; Landed since §7.3 was written: :seon.error/fn (ref, 3), :seon.error.occurrence/*
  ;; (4), :seon.ai.usage/* (24 each), :seon.ai.attempt/model (ref, 24),
  ;; :seon.eval/renderer-fn (ref, 7 of 7), :seon.fn/file (ref, 5667),
  ;; :seon.issue/* (1630 ids, 1423 function refs, 225 test refs), :seon.lint/id (1014),
  ;; :seon.maintenance.*/handler (refs, 119).
  ;; Still open: :seon.fn/sym (string, 4764), :seon.test/sym (string, 1779),
  ;; :seon.render/ai (string, 388), :seon.render/html (string, 383),
  ;; :seon.fn/arglists (string, 3984), :seon.ns.alias/target-ns (symbol, 2829),
  ;; :seon.sci.eval/ending-ns (symbol, 110), :seon.effect/capability (symbol, 10),
  ;; :seon.error/process (string, 4), :seon.error/throwable-class (string, 3),
  ;; :seon.ai.attempt/usage-edn (string, 24), :seon.cluster.eval/triage-edn (16),
  ;; :seon.test/failing-assertions (262), :seon.test/subject (0 holders).

  ;; 5. One sample schema row proving :seon.render/ai stores a function NAME as text.
  (let [connection (seon.operator/connection "default")
        database @connection
        projection (seon.schema/projection-from-database database)]
    (seon.schema/call-with-projection
     projection
     (fn []
       (let [d (first (seon.db/datoms database :aevt :seon.render/ai))]
         [d (seon.db/pull database '[*] (:e d))]))))
  ;; => {:a :seon.render/ai :e 729 :v "seon.agent/render-settings-ai"}
  ;;    the same row carries :seon.render/html "seon.agent/render-settings-html";
  ;;    both name functions that have :seon.fn entities.
  )
