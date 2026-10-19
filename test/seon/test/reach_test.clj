(ns seon.test.reach-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cache :as cache]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.id :as id]
            [seon.program :as program]
            [seon.profile :as profile]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test :as test]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{'seon.test.runner/the-first-reach-digest-after-a-change-costs-what-changed
                        'seon.test.runner/verified-recomputes-reach-digests-for-the-whole-program}}
  a-changed-programs-first-reach-digest-is-subsecond
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           names (vec (sort (db/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] database)))
           selected ['seon.request-cost-test/a-request-counts-its-own-calls-reads-and-transactions]
           cold (profile/measure #(runner/reach-digests database names))
           subset (profile/measure #(runner/reach-digests database selected))]
       (support/transacted!
        connection
        [(support/revision database [:seon.fn/sym 'seon.id/id]
                           {:seon.fn/doc "Reach cost fixture documentation."})])
       (let [changed (db/db connection)
             one (profile/measure #(runner/reach-digests changed selected))
             all (profile/measure #(runner/reach-digests changed names))
             summary (into {}
                           (map (fn [[stage measured]]
                                  [stage (select-keys measured [:seon.profile/ms :seon.profile/alloc-bytes
                                                               :seon.profile/reads :seon.profile/calls
                                                               :seon.profile/ranked-by-self])]))
                           {::cold cold ::subset subset ::one one ::all all})]
         (when-let [path (System/getenv "SEON_REACH_COST_FILE")]
           (spit path (value/core-text summary)))
         (is (every? string? (map (:seon.profile/value one) selected)))
         (is (= (:seon.profile/value one) (select-keys (:seon.profile/value all) selected)))
         (is (zero? (profile/calls-of subset 'seon.test.runner/reached-graph)))
         (is (< (:seon.profile/ms one) 100.0))
         (is (< (:seon.profile/alloc-bytes one) 128000000)))))))

(deftest program-digest-uses-the-databases-program-shapes
  (support/with-database
   (fn [connection]
     (let [before (runner/program-digest (db/db connection))]
       (support/transacted!
        connection
        [(support/revision (db/db connection) [:seon.fn/sym 'seon.id/id] {:seon.fn/doc "Changed fixture documentation."})
         (support/revision (db/db connection) [:seon.fn/sym 'seon.id/digest] {:seon.fn/doc "Another changed declaration."})])
       (let [after (with-redefs [program/shapes
                                #(throw (ex-info "Program digest read authored resources." {}))]
                     (runner/program-digest (db/db connection)))]
         (is (string? before))
         (is (string? after))
         (is (not= before after)))))))

(deftest bookkeeping-keeps-the-program-content-digest
  (support/with-database
   (fn [connection]
     (support/declare-source! connection 'tq.content
                             "(ns tq.content) (defn f {:malli/schema [:=> [:cat] :int]} [] 1)")
     (let [before (runner/program-content-digest (db/db connection))]
       (support/transacted!
        connection
        [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
          :seon.source/test-input-digest (id/digest 64 [::bookkeeping])}])
       (let [measured (profile/measure #(runner/program-content-digest (db/db connection)))]
         (is (string? before))
         (is (= before (:seon.profile/value measured)))
         (is (zero? (profile/calls-of measured 'seon.program/digest-map))))
       (support/transacted! connection [(support/revision (db/db connection) [:seon.fn/sym 'tq.content/f]
                                                        {:seon.fn/doc "Changed content."})])
       (let [database (db/db connection)
             measured (profile/measure #(runner/program-content-digest database))]
         (is (not= before (:seon.profile/value measured)))
         (is (zero? (profile/calls-of measured 'seon.db/index-page))
             "one declaration change does not read the full declaration indexes")
         (is (= (:seon.profile/value measured)
                (id/digest 64 [(into (sorted-map) (program/digest-map database runner/program-content-bound))]))))))))

(deftest the-program-content-fold-equals-full-content-through-reverts-and-deletion
  (support/with-database
   (fn [connection]
     (let [source #(str "(ns tq.digestfold) (defn f {:malli/schema [:=> [:cat] :int]} [] " % ")")
           full #(id/digest 64 [(into (sorted-map) (program/digest-map % runner/program-content-bound))])]
       (support/declare-source! connection 'tq.digestfold (source 1))
       (let [base (db/db connection)
             original (runner/program-content-digest base)]
         (is (= original (full base)))
         (doseq [n [2 3 1]]
           (support/declare-source! connection 'tq.digestfold (source n))
           (let [database (db/db connection)
                 measured (profile/measure #(runner/program-content-digest database))]
             (is (= (full database) (:seon.profile/value measured)))
             (is (zero? (profile/calls-of measured 'seon.db/index-page)))))
         (is (= original (runner/program-content-digest (db/db connection))))
         (support/transacted! connection [[:db/retractEntity [:seon.fn/sym (symbol "tq.digestfold" "f")]]])
         (let [database (db/db connection)]
           (is (= (full database) (runner/program-content-digest database)))
           (is (= original (runner/program-content-digest (db/as-of database (db/basis-t base)))))))))))

(deftest content-revision-contracts-are-admitted-for-agents
  (support/with-database
   (fn [connection]
     (doseq [sym ['seon.test/selection-revisions 'seon.test.runner/result-revisions 'seon.program/content-digest 'seon.id/fold-map]]
       (is (map? (schema/projection-with-function-contract
                  (db/carried-projection (db/db connection)) sym
                  (:malli/schema (meta (requiring-resolve sym)))
                  {:seon.schema.admission/source :agent})))))))

(defn- oracle-closure
  "A test's reach recomputed from each row's own pulled facts: calls,
  references and subject, walked to their fixed point."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym] [:set :symbol]]}
  [database test-symbol]
  (loop [pending [test-symbol] seen #{}]
    (if-let [target (peek pending)]
      (if (seen target)
        (recur (pop pending) seen)
        (let [row (or (db/pull database '[:seon.fn/calls :seon.fn/references] [:seon.fn/sym target])
                      (db/pull database '[:seon.fn/calls :seon.fn/references :seon.test/subject]
                               [:seon.test/sym target]))]
          (recur (into (pop pending) (concat (:seon.fn/calls row) (:seon.fn/references row)
                                             (some-> (:seon.test/subject row) vector)))
                 (conj seen target))))
      (disj seen test-symbol))))

(deftest reach-is-the-closure-of-each-rows-own-facts
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           tests ['seon.id-test/identity-is-a-function-of-the-values-structure
                  'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol]
           reaches (runner/reach-memberships database tests)]
       (is (contains? (get reaches (first tests)) 'seon.id/id) "the subject is reached")
       (doseq [t tests]
         (is (= (oracle-closure database t) (get reaches t))
             "reaches exactly the closure its rows name"))))))

(deftest a-digest-follows-its-closures-content-and-nothing-else
  (support/with-database
   (fn [connection]
     (let [reaching 'seon.id-test/identity-is-a-function-of-the-values-structure
           elsewhere 'seon.db-test/semantic-replay-declines-opaque-and-lazy-values-without-realizing
           symbols [reaching elsewhere]
           before (runner/reach-digests (db/db connection) symbols)]
       (is (every? string? (map before symbols)))
       (is (not (contains? (get (runner/reach-memberships (db/db connection) [elsewhere]) elsewhere)
                           'seon.id/id))
           "the other test does not reach the changed definition")
       (support/transacted! connection
         [[:db/add [:seon.test/sym reaching] :seon.test/pass-count 1]])
       (is (= before (runner/reach-digests (db/db connection) symbols))
           "a result-only write changes no reach")
       (support/transacted!
        connection
        [(support/revision (db/db connection) [:seon.fn/sym 'seon.id/id] {:seon.fn/doc "A changed reached definition."})])
       (let [after (runner/reach-digests (db/db connection) symbols)]
         (is (not= (get before reaching) (get after reaching))
             "a changed definition in the closure changes the digest")
         (is (= (get before elsewhere) (get after elsewhere))
             "a test that does not reach it keeps its digest"))))))

(deftest tests-reaching-answers-the-tests-whose-closure-holds-the-target
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           reaching 'seon.id-test/identity-is-a-function-of-the-values-structure
           elsewhere 'seon.db-test/semantic-replay-declines-opaque-and-lazy-values-without-realizing
           symbols [reaching elsewhere]
           reaches (runner/reach-memberships database symbols)]
       (is (= (into #{} (filter #(contains? (get reaches %) 'seon.id/id)) symbols)
              (runner/tests-reaching database symbols 'seon.id/id))
           "the same tests the memberships name")
       (is (= #{reaching} (runner/tests-reaching database symbols 'seon.id/id)))
       (is (= #{} (runner/tests-reaching database symbols reaching))
           "a test never counts as reaching itself")))))

(deftest tests-reaching-reads-the-held-index-as-it-grows
  ;; A reach request for tests the held index lacks extends it under the same
  ;; revisions; a walk kept from before that growth answered an empty set for
  ;; the added test (seon.step.prove-test/handoff, row "a proof never run").
  (support/with-database
   (fn [connection]
     (support/declare-source! connection 'tq.rch
                              (str "(ns tq.rch (:require [clojure.test :refer [deftest is]]))\n"
                                   "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
                                   "(deftest a (is (= 1 (f 1))))\n"
                                   "(deftest b (is (= 2 (f 2))))\n"))
     (let [database (db/db connection)]
       (runner/reach-digests database '[tq.rch/a])
       (is (= '#{tq.rch/a} (runner/tests-reaching database '[tq.rch/a] 'tq.rch/f)))
       (runner/reach-digests database '[tq.rch/b])
       (is (= '#{tq.rch/b} (runner/tests-reaching database '[tq.rch/b] 'tq.rch/f))
           "a test the held index gained reaches what it calls")))))

(deftest a-branch-result-identity-is-a-function-of-its-own-head
  ;; A branch runs its own HEAD (branch-model ruling 1): its reach digests read
  ;; its own rows. Before, a work branch's digest of a declaration main changed
  ;; after the fork was main's (`moved-reach`), so the branch's identity moved
  ;; with every main commit and no recorded result stayed current.
  (support/with-database
   (fn [connection]
     (let [reaching 'seon.id-test/identity-is-a-function-of-the-values-structure
           elsewhere 'seon.db-test/semantic-replay-declines-opaque-and-lazy-values-without-realizing
           symbols [reaching elsewhere]
           cited [:seon.fn/sym 'seon.id/id]
           database (db/db connection)
           store (:seon.store/store (support/execution-handle connection))
           branch (keyword (str "reach-fork-" (id/id)))
           request {:seon.store/store store :seon.store/branch branch
                    :seon.cluster.registry/from (db/commit-id database) :seon.db/db database}]
       (is (:seon.cluster/created? (registry/fork! request)))
       (try
         (let [fork (store/open-branch! store branch)]
           (try
             (let [own [(support/revision (db/db fork) [:seon.fn/sym 'seon.id/digest] {:seon.fn/doc "The branch's own row."})]
                   _ (support/transacted! fork own)
                   before (runner/reach-digests (db/db fork) symbols)
                   independent (runner/reach-digests (db/with database own) symbols)]
               (is (every? string? (vals before)))
               (is (= before independent)
                   "the branch's digests equal those of the same rows built apart from it")
               ;; main changes the behavior of a definition the test reaches and the branch never changed
               (support/transacted!
                connection
                [(let [source (str/trim (:seon.fn/source (db/pull (db/db connection) [:seon.fn/source] cited)))]
                   (support/revision (db/db connection) cited
                                     {:seon.fn/source (str (subs source 0 (dec (count source))) "\n  ;; main's change\n  )")}))])
               (is (= before (runner/reach-digests (db/db fork) symbols))
                   "main's later change is not the branch's until it is merged")
               (is (not= (get before reaching) (get (runner/reach-digests (db/db connection) symbols) reaching))
                   "main's own digest of the test follows main's row"))
             (finally (store/release-branch! fork))))
         (finally (registry/retire-branch! (select-keys request [:seon.store/store :seon.store/branch]))))))))

(deftest a-new-branchs-first-reach-digest-costs-what-it-changed
  ;; exp-c (2026-10-15): the prior reach derivation was keyed by connection generation and branch, so a new branch
  ;; (a chain's first test step, `prepare-merge!`'s `:merge-<id>`) rebuilt the whole closure (~470 MB for 29 tests).
  ;; The prior is any held derivation of the store, its declarations compared with the branch's by content.
  ;; The edited definition is one the test reaches and almost nothing else calls, so the work it changes is small.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           reaching 'seon.id-test/identity-is-a-function-of-the-values-structure
           names (into [reaching] (take 28 (sort (db/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] database))))
           store (:seon.store/store (support/execution-handle connection))
           branch (keyword (str "reach-new-" (id/id)))
           request {:seon.store/store store :seon.store/branch branch
                    :seon.cluster.registry/from (db/commit-id database) :seon.db/db database}
           before (runner/reach-digests database names)]
       (is (:seon.cluster/created? (registry/fork! request)))
       (try
         (let [fork (store/open-branch! store branch)]
           (try
             (support/transacted! fork [(support/revision (db/db fork) [:seon.fn/sym 'seon.id/valid?]
                                                          {:seon.fn/doc "The new branch's own row."})])
             (let [changed (db/db fork)
                   first-reach (profile/measure #(runner/reach-digests changed names))
                   cold (profile/measure
                         #(:seon.test.runner/digests
                           (@#'runner/reach-index (@#'runner/reached-graph changed names) names)))
                   ;; every reach digest also keys the loaded commit that runs the test
                   loaded (:seon.source/commit-id @sci.eval/loaded-identity)]
               (is (= (update-vals (:seon.profile/value cold) #(id/id [% loaded] 64)) (:seon.profile/value first-reach))
                   "the branch's first digests equal a rebuild of its whole closure")
               (is (not= (get before reaching) (get (:seon.profile/value first-reach) reaching))
                   "the branch's change reaches the test")
               (is (<= (:seon.profile/alloc-bytes first-reach) (quot (:seon.profile/alloc-bytes cold) 4))
                   "the first reach on a new branch allocates at most a quarter of a rebuild"))
             (finally (store/release-branch! fork))))
         (finally (registry/retire-branch! (select-keys request [:seon.store/store :seon.store/branch]))))))))

;; reach-maps (2026-10-15): a many-valued attribute's map was updated from `seon.db/entities-changed-after` a basis t,
;; valid only on the branch that held it, so a new branch read the whole attribute again for each one it changed.
(deftest a-new-branchs-first-attribute-map-costs-what-it-changed
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           reach-attribute @#'runner/reach-attribute
           revisions #(db/content-revisions % [:seon.fn/calls])
           store (:seon.store/store (support/execution-handle connection))
           branch (keyword (str "reach-map-" (id/id)))
           request {:seon.store/store store :seon.store/branch branch
                    :seon.cluster.registry/from (db/commit-id database) :seon.db/db database}]
       (reach-attribute database (revisions database) :seon.fn/calls :many)
       (is (:seon.cluster/created? (registry/fork! request)))
       (try
         (let [fork (store/open-branch! store branch)]
           (try
             (support/transacted! fork [(support/revision (db/db fork) [:seon.fn/sym 'seon.id/valid?]
                                                          {:seon.fn/calls #{'seon.id/digest}})])
             (let [changed (db/db fork)
                   first-map (profile/measure #(reach-attribute changed (revisions changed) :seon.fn/calls :many))
                   cold (profile/measure #(reach-attribute changed nil :seon.fn/calls :many))]
               (is (= (:seon.profile/value cold) (:seon.profile/value first-map))
                   "the branch's first map equals a full read of the attribute")
               (is (contains? (get (:seon.profile/value first-map)
                                   (db/q '[:find ?e . :where [?e :seon.fn/sym seon.id/valid?]] changed))
                              'seon.id/digest)
                   "the branch's own change is in its map")
               (is (<= (:seon.profile/alloc-bytes first-map) (quot (:seon.profile/alloc-bytes cold) 4))
                   "the first map on a new branch allocates at most a quarter of a full read"))
             (finally (store/release-branch! fork))))
         (finally (registry/retire-branch! (select-keys request [:seon.store/store :seon.store/branch]))))))))

(deftest a-branch-result-is-recorded-at-its-own-heads-identity
  ;; S5 run 7: the recorder judged what the body executed against another row than its reach digest read, called
  ;; every result stale and keyed none: `seon.test.runner/current-results` found no green, and the attempt's prove
  ;; pass ran the proof again ~130 times in one agent context. The branch's copy differs from the loaded commit's
  ;; only in documentation, so it runs the compiled function; its reach digest reads the branch's own row.
  (support/with-database
   (fn [connection]
     (let [reaching 'seon.id-test/a-host-object-has-no-identity
           cited [:seon.fn/sym 'seon.id/id]
           platform-doc (:seon.fn/doc (db/pull (db/db connection) [:seon.fn/doc] cited))
           revise! #(support/transacted! connection [(support/revision (db/db connection) cited {:seon.fn/doc %})])
           handle (support/execution-handle connection)
           store (:seon.store/store handle)
           branch (keyword (str "moved-run-" (id/id)))]
       ;; the branch's copy differs from the loaded commit's row in documentation only
       (revise! "Main's row before the fork.")
       (let [request {:seon.store/store store :seon.store/branch branch
                      :seon.cluster.registry/from (db/commit-id (db/db connection)) :seon.db/db (db/db connection)}]
         (is (:seon.cluster/created? (registry/fork! request)))
         (try
           ;; main returns to the loaded commit's row after the fork; the branch keeps its own
           (revise! platform-doc)
           (let [attempt (agent/acquire-context! handle nil {:seon.agent/branch branch})
                 conn (:seon.db/connection attempt)
                 run-once #(db/with-branch {:seon.db/connection conn}
                             (fn [] (test/run {:seon.test/execution (assoc attempt :seon.agent/id "moved-run")
                                               :seon.test/recording-connection conn :seon.test/policy :named
                                               :seon.test/identities #{reaching}})))]
             (try
               (let [first-run (run-once)]
                 (is (= [1 true] ((juxt :seon.test/executed-count :seon.test/passed?) first-run))
                     "the test runs once on the branch, green")
                 (is (= [reaching] (keys (runner/current-results (db/db conn) [reaching] nil)))
                     "its result is current at the identity its readers compute")
                 (is (= [0 true] ((juxt :seon.test/executed-count :seon.test/passed?) (run-once)))
                     "so the same input is answered from it, not run again")
                 (revise! "Main's row after the result.")
                 (is (= [reaching] (keys (runner/current-results (db/db conn) [reaching] nil)))
                     "main's later change leaves the branch's result current")
                 (is (= [0 true] ((juxt :seon.test/executed-count :seon.test/passed?) (run-once)))
                     "and runs nothing again"))
               (finally (agent/release-context! attempt))))
           (finally (registry/retire-branch! (select-keys request [:seon.store/store :seon.store/branch])))))))))

(deftest ^{:seon.test/long "One nested seon.test/run of one compiled member, then two reach reads."
           :seon.test/long-ms 15000}
  a-result-recorded-under-one-loaded-commit-is-not-reused-under-another
  ;; S5's merge step, 2026-10-15: a red the lazy-root NPE in the compiled platform caused (seon.sci.eval) was
  ;; reused by reach digest after the JVM restarted onto the fix, so the step stalled on a red no body re-earned.
  (let [execution (assoc (support/execution-handle nil) :seon.agent/id "loaded-commit-identity")
        conn (:seon.db/connection execution)
        test-symbol 'seon.id-test/a-host-object-has-no-identity
        ran (test/run {:seon.test/execution execution :seon.test/recording-connection conn
                       :seon.test/policy :named :seon.test/rerun? true :seon.test/identities #{test-symbol}})
        database (db/db conn)
        loaded (runner/reach-digests database [test-symbol])
        other (runner/reach-digests database [test-symbol] {} nil (random-uuid))]
    (is (true? (:seon.test/passed? ran)))
    (is (not= (get loaded test-symbol) (get other test-symbol)) "another loaded commit is another identity")
    (is (contains? (runner/result-at-reach database loaded) test-symbol)
        "the result answers under the loaded commit that ran it")
    (is (not (contains? (runner/result-at-reach database other) test-symbol))
        "a platform that never ran it has no result to reuse")))

(deftest a-held-reach-index-counts-its-reuse-and-weighs-what-it-adds
  ;; Live run 10: `::runner/reach-index-by-revision` read 159 MB, 35% of `seon.cache`, at 0 hits over 50 misses.
  ;; A request on an unchanged program read the held index through `seon.cache/present`, which counts nothing;
  ;; and each of the 31 held indexes was weighed as a whole program while they shared 36 times over the node
  ;; values their predecessors held. A reuse is a hit, and an index derived from a held one is charged for what
  ;; it adds. Each program here is this test's own (a doc no other test writes), so no other test's request
  ;; replaces its index.
  (support/with-database
   (fn [connection]
     (let [edit! (fn [doc] (support/transacted!
                            connection
                            [(support/revision (db/db connection) [:seon.fn/sym 'seon.id/valid?] {:seon.fn/doc doc})])
                   (db/db connection))
           names (vec (take 29 (sort (db/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] (db/db connection)))))
           kind-stat (fn [k] (get-in (cache/status) [:seon.cache/kinds ::runner/reach-index-by-revision k] 0))
           held-index (fn [database] (cache/present ::runner/reach-index-by-revision (@#'runner/reach-revision-key database)))
           database (edit! (str "This test's own program " (id/id)))
           _ (runner/reach-digests database names)
           hits (kind-stat :seon.cache/hits)
           again (runner/reach-digests database names)]
       (is (map? again))
       (is (<= (inc hits) (kind-stat :seon.cache/hits)) "a second request on one program reads the held index as a hit")
       (let [prior (held-index database)
             changed (edit! (str "This test's changed program " (id/id)))
             _ (runner/reach-digests changed names)
             held (held-index changed)
             whole (@#'runner/reach-entries-weight held nil)]
         (is (some? held) "the changed program's index is held")
         (is (< (@#'runner/reach-entries-weight held prior) (quot whole 4))
             "an index derived from a held one is charged for what it adds, not the whole program it shares"))))))
