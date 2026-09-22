(ns seon.test.fixture-timing-test
  "The canonical fixture is a branch through the agent entrance, never a copy."
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.test-support :as support]))

(defn- elapsed-ms
  [f]
  (let [started (System/nanoTime)]
    (f)
    (/ (- (System/nanoTime) started) 1e6)))

(deftest fixture-acquisition-is-a-pointer-branch-off-the-executing-commit
  (let [parent (support/execution-handle nil)
        store (:seon.store/store parent)
        projection (db/carried-projection (db/db (:seon.db/connection parent)))
        observed (atom [])
        sample (fn []
                 (elapsed-ms
                  #(support/with-database
                    (fn [connection]
                      (let [handle (support/execution-handle connection)]
                        (swap! observed conj
                               {:branch (:seon.agent/branch handle)
                                :listed? (contains? (registry/roster store) (:seon.agent/branch handle))
                                :commit (:seon.source/commit-id handle)
                                :projection? (identical? projection
                                                         (db/carried-projection (db/db connection)))}))))))
        first-ms (sample)
        later-ms (mapv (fn [_] (sample)) (range 10))
        later-p50-ms (nth (sort later-ms) 4)]
    (println "FIXTURE ACQUISITION" {:first-ms first-ms :later-p50-ms later-p50-ms
                                   :later-samples-ms later-ms})
    (is (= 11 (count (set (map :branch @observed)))) "every fixture owns a fresh branch")
    (is (every? :listed? @observed) "each fixture branch is on the roster during its body")
    (is (not-any? (set (registry/roster store)) (map :branch @observed))
        "each fixture branch is unlinked after its body")
    (is (every? #(= (:seon.source/commit-id parent) (:commit %)) @observed)
        "every fixture branches off the executing handle's commit")
    (is (every? :projection? @observed) "the branch carries the executing projection")
    (is (<= first-ms 500.0) "the first pointer branch stays inside 500 ms")
    (is (<= later-p50-ms 100.0) "later pointer branches stay inside 100 ms")))
