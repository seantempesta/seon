(ns seon.test.reach-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.program :as program]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

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
         (is (string? after) (pr-str after))
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
           tests ['seon.id-test/data-shape-and-explicit-length-determine-identity
                  'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol]
           reaches (runner/reach-memberships database tests)]
       (is (contains? (get reaches (first tests)) 'seon.id/id) "the subject is reached")
       (doseq [t tests]
         (is (= (oracle-closure database t) (get reaches t))
             (str t " reaches exactly the closure its rows name")))))))

(deftest a-digest-follows-its-closures-content-and-nothing-else
  (support/with-database
   (fn [connection]
     (let [reaching 'seon.id-test/data-shape-and-explicit-length-determine-identity
           elsewhere 'seon.db-test/semantic-replay-declines-opaque-and-lazy-values-without-realizing
           symbols [reaching elsewhere]
           before (runner/reach-digests (db/db connection) symbols)]
       (is (every? string? (map before symbols)) (pr-str before))
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
