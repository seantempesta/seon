;;; R2 probe — structured test failure facts and "what made it red".
;;; Read-only. Each form was evaluated once through mcp__seon__eval_clj
;;; mode jvm, root /Users/sean/src/seon, cluster default, namespace user.
;;; Measured numbers live in test-failure-facts-2026-09-16.md.

;; 1. Live holders of the two string attributes, and the declared blob dial.
(let [conn (seon.operator/connection "default") db (seon.db/db conn)
      rows (seon.db/q '[:find ?s ?m :where [?e :seon.test/sym ?s] [?e :seon.test/failure-message ?m]] db)
      ids (seon.db/q '[:find ?s (count ?a) :where [?e :seon.test/sym ?s] [?e :seon.test/failing-assertions ?a]] db)
      lens (sort (map (comp count second) rows))]
  {:basis (seon.db/basis-t db)
   :message-holders (count rows)
   :identity-holders (count ids)
   :message-bytes {:min (first lens) :median (nth lens (quot (count lens) 2) nil)
                   :max (last lens) :total (reduce + lens)}
   :threshold (seon.db/q '[:find ?t . :where [_ :seon.config.eval.result/blob-threshold ?t]] db)})

;; 2. Failing assertions per red row, and how many messages exceed the dial.
(let [db (seon.db/db (seon.operator/connection "default"))
      ids (seon.db/q '[:find ?s (count ?a) :where [?e :seon.test/sym ?s] [?e :seon.test/failing-assertions ?a]] db)
      msgs (map second (seon.db/q '[:find ?s ?m :where [?e :seon.test/sym ?s] [?e :seon.test/failure-message ?m]] db))]
  {:identities-per-row (into (sorted-map) (frequencies (map second ids)))
   :total-identities (reduce + (map second ids))
   :messages-over-4096 (count (filter #(> (count %) 4096) msgs))
   :messages-over-16k (count (filter #(> (count %) 16384) msgs))})

;; 3. Is a previous (green) reach digest retained for a currently red test?
(let [db (seon.db/db (seon.operator/connection "default"))
      reds (map first (seon.db/q '[:find ?s :where [?e :seon.test/sym ?s] [?e :seon.test/failing-assertions _]] db))
      h (seon.db/history db)
      per (into {} (map (fn [s]
                          [s (->> (seon.db/q '[:find ?d ?t ?added :in $ ?s
                                               :where [?e :seon.test/sym ?s]
                                                      [?e :seon.test/reach-digest ?d ?tx ?added]
                                                      [?tx :db/txInstant _] [(identity ?tx) ?t]] h s)
                                  (sort-by second) vec)])) reds)]
  {:red-count (count reds)
   :distinct-digests (into (sorted-map) (frequencies (map (fn [[_ v]] (count (distinct (map first v)))) per)))
   :no-history (count (filter (fn [[_ v]] (empty? v)) per))})

;; 4. Cost of recomputing one closure at a historical basis (the diff's price today).
(let [db (seon.db/db (seon.operator/connection "default"))
      sym "seon.program-test/changed-runtime-redeclaration-builds-a-real-replacement"
      t0 (System/nanoTime) warm (seon.test.runner/reach-digests db [sym])
      t1 (System/nanoTime) cold (seon.test.runner/reach-digests (seon.db/as-of db 536871592) [sym])
      t2 (System/nanoTime) cold2 (seon.test.runner/reach-digests (seon.db/as-of db 536871592) [sym])
      t3 (System/nanoTime)]
  {:warm-current-ms (/ (- t1 t0) 1e6) :cold-as-of-ms (/ (- t2 t1) 1e6)
   :second-as-of-ms (/ (- t3 t2) 1e6)
   :digest-now (get warm sym) :digest-then (get cold sym) :repeat (get cold2 sym)})

;; 5. Which branch did the recorded run measure, and does the recorded digest
;;    reproduce at its own run-basis-t on default's branch?
(let [db (seon.db/db (seon.operator/connection "default"))
      sym "seon.program-test/changed-runtime-redeclaration-builds-a-real-replacement"
      row (seon.db/pull db [:seon.test/reach-digest :seon.test/run-basis-t :seon.test/run-at
                            {:seon.test/run [:seon.test.run/id :seon.test.run/branch
                                             :seon.test.run/basis-t :seon.test.run/program-digest]}]
                        [:seon.test/sym sym])
      d (get (seon.test.runner/reach-digests (seon.db/as-of db (:seon.test/run-basis-t row)) [sym]) sym)]
  {:row row :recomputed-at-run-basis d :matches? (= d (:seon.test/reach-digest row))
   :run-branches (frequencies (map first (seon.db/q '[:find ?b :where [?r :seon.test.run/branch ?b]] db)))})

;; 6. Do test rows already carry the assertion site's file and span?
(let [db (seon.db/db (seon.operator/connection "default"))]
  {:tests-total (count (seon.db/q '[:find ?e :where [?e :seon.test/sym _]] db))
   :tests-with-file (count (seon.db/q '[:find ?e :where [?e :seon.test/sym _] [?e :seon.fn/file _]] db))
   :tests-with-span (count (seon.db/q '[:find ?e :where [?e :seon.test/sym _] [?e :seon.fn/form-span _]] db))
   :red-with-file (count (seon.db/q '[:find ?e :where [?e :seon.test/failing-assertions _] [?e :seon.fn/file _]] db))})

;; 7. The proposed derivation's two halves, priced on existing facts:
;;    last green from the row's own history, and functions changed since it.
(let [db (seon.db/db (seon.operator/connection "default"))
      h (seon.db/history db)
      sym "seon.program-test/changed-runtime-redeclaration-builds-a-real-replacement"
      t0 (System/nanoTime)
      greens (seon.db/q '[:find ?tx ?f ?e2 :in $ ?s
                          :where [?e :seon.test/sym ?s]
                                 [?e :seon.test/fail-count ?f ?tx true]
                                 [?e :seon.test/error-count ?e2 ?tx true]] h sym)
      t1 (System/nanoTime)
      changed (seon.db/q '[:find ?sym :where [?e :seon.fn/source _] [?e :seon.fn/sym ?sym]]
                         (seon.db/since (seon.db/history db) 536871592))
      t2 (System/nanoTime)]
  {:green-history-ms (/ (- t1 t0) 1e6) :result-history (vec (sort-by first greens))
   :changed-fn-since-ms (/ (- t2 t1) 1e6) :changed-fn-count (count changed)})
