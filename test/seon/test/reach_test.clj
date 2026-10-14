(ns seon.test.reach-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.id :as id]
            [seon.program :as program]
            [seon.profile :as profile]
            [seon.render.value :as value]
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

(deftest a-work-branch-result-is-not-current-after-main-changes-what-it-reaches
  ;; Before, a work branch's reach digest read the branch's own rows, while the
  ;; branch runs main's row of a declaration main moved past since the merge
  ;; base (`seon.cluster.registry/seen-database`): chain :chain-f1f6dc8e6652's
  ;; proof kept a red recorded on the old platform as current (S5 run 6).
  (support/with-database
   (fn [connection]
     (let [reaching 'seon.id-test/identity-is-a-function-of-the-values-structure
           elsewhere 'seon.db-test/semantic-replay-declines-opaque-and-lazy-values-without-realizing
           symbols [reaching elsewhere]
           database (db/db connection)
           store (:seon.store/store (support/execution-handle connection))
           branch (keyword (str "reach-fork-" (id/id)))
           request {:seon.store/store store :seon.store/branch branch
                    :seon.cluster.registry/from (db/commit-id database) :seon.db/db database}]
       (is (:seon.cluster/created? (registry/fork! request)))
       (try
         (let [fork (store/open-branch! store branch)]
           (try
             (let [before (runner/reach-digests (db/db fork) symbols)]
               (support/transacted!
                connection
                [(support/revision (db/db connection) [:seon.fn/sym 'seon.id/id] {:seon.fn/doc "Main moved past the branch."})])
               (let [after (runner/reach-digests (db/db fork) symbols)]
                 (is (not= (get before reaching) (get after reaching))
                     "main's change to a reached definition the branch never changed changes the branch's digest")
                 (is (= (get before elsewhere) (get after elsewhere))
                     "a test that does not reach it keeps its digest")))
             (finally (store/release-branch! fork))))
         (finally (registry/retire-branch! (select-keys request [:seon.store/store :seon.store/branch]))))))))
