(ns seon.cluster.save-gate-test
  "The save gate: a change reaches its target only when the tests reaching it
  pass on the target's candidate branch (`seon.cluster/candidate-gate!`)."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test-support :as support]))

(defn- deftest-row
  "The canonical declaration row of one synthetic deftest in this namespace."
  [database test-name assertion]
  (support/program-row database [:seon.test/sym (symbol "seon.cluster.save-gate-test" test-name)]
                       (str "(clojure.test/deftest " test-name " (clojure.test/is " assertion "))")))

(defn- writing
  "A gate `write!`: transact the deftest onto a handle's branch, answer its identity."
  [test-name assertion]
  (fn [handle]
    (let [connection (:seon.db/connection handle)]
      (support/transacted! connection [(deftest-row (db/db connection) test-name assertion)])
      #{[:seon.test/sym (symbol "seon.cluster.save-gate-test" test-name)]})))

(defn- gate!
  "Run one change through the gate onto the member's own branch as target."
  [target branch write!]
  (cluster/candidate-gate! target (:seon.store/store target) branch write!
                           #(write! target)))

(deftest ^{:seon.test/long "Each case runs one nested seon.test/run on a new candidate commit; seon.test.runner/program-digest re-derives the whole program per new commit (10.7 s measured 2026-09-23 on default; the whole test measured 86,950 ms under concurrent lane load, run 4a2f7723dbb9; docs/research/agent-platform/cache-invalidation-audit-2026-09-23.md item 6)."
           :seon.test/long-ms 150000}
  a-change-reaches-its-target-only-through-green-reaching-tests
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        branch (keyword (str "save-gate-test-" (id/id)))
        head #(db/commit-id (db/db (:seon.db/connection target)))
        present? #(some? (:db/id (db/pull (db/db (:seon.db/connection target)) [:db/id]
                                          [:seon.test/sym (symbol "seon.cluster.save-gate-test" %)])))]
    (try
      (testing "an unchanged change selects and executes nothing"
        (let [before (head)
              gate (gate! target branch (constantly #{}))]
          (is (true? (:seon.test/passed? gate)) (:seon.source/tally gate))
          (is (nil? (:seon.source/gate-run gate)) "no test request was made")
          (is (= #{} (:seon.source/advanced gate)))
          (is (= before (head)))))
      (testing "a change whose reaching test fails leaves the target's commit unchanged"
        (let [before (head)
              gate (gate! target branch (writing "gate-red" "(= 1 2)"))]
          (is (false? (:seon.test/passed? gate)) (:seon.source/tally gate))
          (is (pos? (:seon.test/fail-count (first (:seon.test/results (:seon.source/gate-run gate))) 0))
              "the failure comes back to the editor")
          (is (not (contains? gate :seon.source/advanced)))
          (is (= before (head)) "the target's commit is unchanged")
          (is (not (present? "gate-red")))
          (is (contains? (registry/roster store) branch) "the candidate persists with the red change")))
      (testing "a change whose reaching test passes advances the target"
        (let [before (head)
              gate (gate! target branch (writing "gate-green" "(= 1 1)"))]
          (is (true? (:seon.test/passed? gate)) (:seon.source/tally gate))
          (is (= 1 (:seon.test/executed-count (:seon.source/gate-run gate))))
          (is (pos? (:seon.test/pass-count (first (:seon.test/results (:seon.source/gate-run gate))) 0)))
          (is (not= before (head)) "the target advanced")
          (is (present? "gate-green"))
          (is (not (present? "gate-red")) "the reset candidate carried no earlier red change")))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))
