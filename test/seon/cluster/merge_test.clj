(ns seon.cluster.merge-test
  "Prepare, gate and named accept of a candidate branch's replacements
  (`seon.cluster.source/prepare-merge!`, `accept-merge!`)."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test-support :as support]))

(defn- sym [n] (symbol "seon.cluster.merge-test" n))

(defn- row
  "One analyzed fixture declaration of this namespace: `fn` or `test`."
  [database kind n source]
  (support/program-row database [(if (= kind :fn) :seon.fn/sym :seon.test/sym) (sym n)] source))

(defn- write! [connection rows] (support/transacted! connection (vec rows)) (db/commit-id (db/db connection)))

(deftest ^{:seon.test/long "Three prepares, each one nested seon.test/run on a new candidate commit (the save gate's cost, measured in lane-nsa-slice4-2026-09-23.md)."
           :seon.test/long-ms 90000}
  a-candidate-merges-only-its-tested-green-program
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        fn-row #(row (db/db %1) :fn %2 %3)
        issue (str "merge-test-" (id/id))
        c (keyword (str "merge-test-c-" (id/id)))
        _ (write! h-conn [(fn-row h-conn "merge-callee" "(defn merge-callee [] 1)")
                          (fn-row h-conn "merge-caller" "(defn merge-caller [] (seon.cluster.merge-test/merge-callee))")])
        _ (write! h-conn [(row (db/db h-conn) :test "merge-check" "(clojure.test/deftest merge-check (clojure.test/is (= 1 (seon.cluster.merge-test/merge-caller))))")
                          {:seon.issue/id issue :seon.issue/title "Merge" :seon.issue/status :open
                           :seon.issue/severity :cleanup :seon.issue/problem "Replace the callee."
                           :seon.issue/tests #{[:seon.test/sym (sym "merge-check")]}}])
        _ (registry/branch! {:seon.store/store store :seon.store/branch c
                             :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
        candidate (cluster.agent/acquire-context! target nil {:seon.agent/branch c})
        c-conn (:seon.db/connection candidate)
        digest #(:seon.program/definition-digest (db/pull (db/db %1) [:seon.program/definition-digest] [:seon.fn/sym (sym %2)]))
        made (volatile! [])
        prepare #(let [p (source/prepare-merge! target store c issue)]
                   (some->> (:seon.source/candidate p) (vswap! made conj)) p)]
    (try
      (testing "an equal revert is unchanged: nothing to merge"
        (write! c-conn [(fn-row c-conn "merge-callee" "(defn merge-callee [] (+ 0 1))")])
        (write! c-conn [(fn-row c-conn "merge-callee" "(defn merge-callee [] 1)")])
        (is (= :seon.program/scope (:seon.program/missing-evidence (prepare)))))
      (testing "a red replacement is tested but its evidence refuses acceptance"
        (let [_ (write! h-conn [(fn-row h-conn "merge-extra" "(defn merge-extra [] 0)")])
              _ (write! c-conn [(fn-row c-conn "merge-callee" "(defn merge-callee [] 2)")])
              red (prepare)
              before (db/commit-id (db/db h-conn))]
          (is (false? (:seon.test/passed? red)) (pr-str red))
          (is (= :seon.source/merge (:seon.error/layer (source/accept-merge! target store red))))
          (is (= before (db/commit-id (db/db h-conn))))))
      (testing "green: accept merges exactly the tested rows with lineage H C E"
        (let [_ (write! c-conn [(fn-row c-conn "merge-callee" "(defn merge-callee [] (* 1 1))")])
              stale (prepare)
              moved (write! h-conn [{:seon.issue/id issue :seon.issue/title "Merge again"}])
              refused (source/accept-merge! target store stale)
              _ (is (= :transaction/stale-basis (get-in refused [:seon.error/data :error])) (pr-str refused))
              _ (is (= moved (db/commit-id (db/db h-conn))) "a stale head takes zero datoms")
              green (prepare)
              tested (digest c-conn "merge-callee")
              h (db/commit-id (db/db h-conn))
              _ (write! c-conn [(fn-row c-conn "merge-callee" "(defn merge-callee [] 3)")])
              e (registry/branch-commit-id {:seon.store/store store :seon.store/branch (:seon.source/candidate green)})
              report (source/accept-merge! target store green)]
          (is (true? (:seon.test/passed? green)) (:seon.source/tally green))
          (is (:db-after report) (pr-str report))
          (is (= tested (digest h-conn "merge-callee")) "the tested replacement, not C's later edit")
          (is (= #{h (first (:parents green)) e} (set (:datahike/parents (:meta (db/db h-conn))))))
          (is (= :seon.source/merge (:seon.error/layer (source/accept-merge! target store green)))
              "the merged proposal has nothing left to merge")))
      (testing "a head replacement of the same function is a conflict"
        (write! h-conn [(fn-row h-conn "merge-callee" "(defn merge-callee [] (- 2 1))")])
        (is (seq (:seon.program/conflict (prepare)))))
      (finally
        (cluster.agent/release-context! candidate)
        (doseq [b (conj @made c)] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))))

(deftest ^{:seon.test/long "One prepare: one nested seon.test/run on a new candidate commit."
           :seon.test/long-ms 60000}
  a-replaced-function-no-test-reaches-and-a-later-program-write-refuse
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        issue (str "merge-test-" (id/id))
        c (keyword (str "merge-test-c-" (id/id)))
        _ (write! h-conn [(row (db/db h-conn) :fn "merge-alone" "(defn merge-alone [] 1)")
                          (row (db/db h-conn) :test "merge-other" "(clojure.test/deftest merge-other (clojure.test/is true))")])
        _ (write! h-conn [{:seon.issue/id issue :seon.issue/title "Lone" :seon.issue/status :open
                           :seon.issue/severity :cleanup :seon.issue/problem "Replace the lone function."
                           :seon.issue/tests #{[:seon.test/sym (sym "merge-other")]}}])
        _ (registry/branch! {:seon.store/store store :seon.store/branch c
                             :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
        candidate (cluster.agent/acquire-context! target nil {:seon.agent/branch c})
        c-conn (:seon.db/connection candidate)]
    (try
      (write! c-conn [(row (db/db c-conn) :fn "merge-alone" "(defn merge-alone [] (inc 0))")])
      (let [proposal (source/prepare-merge! target store c issue)
            before (db/commit-id (db/db h-conn))
            refusal (source/accept-merge! target store proposal)]
        (is (true? (:seon.test/passed? proposal)) "the test owner selects no test and refuses nothing")
        (is (= [(sym "merge-alone")] (:seon.error/offending refusal)) (pr-str refusal))
        (is (= before (db/commit-id (db/db h-conn))))
        (testing "a program write on S after the run refuses the named run"
          (let [s (cluster.agent/acquire-context! target nil {:seon.agent/branch (:seon.source/candidate proposal)})]
            (try (write! (:seon.db/connection s) [(row (db/db (:seon.db/connection s)) :fn "merge-alone" "(defn merge-alone [] 1)")])
                 (finally (cluster.agent/release-context! s))))
          (is (= "The tested program changed after the named run."
                 (:seon.error/message (source/accept-merge! target store proposal)))))
        (registry/retire-branch! {:seon.store/store store :seon.store/branch (:seon.source/candidate proposal)}))
      (finally
        (cluster.agent/release-context! candidate)
        (registry/retire-branch! {:seon.store/store store :seon.store/branch c})))))
