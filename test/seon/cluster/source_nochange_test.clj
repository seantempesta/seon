(ns seon.cluster.source-nochange-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.source :as source]
            [seon.cluster.source-test :as fixture]
            [seon.db :as db]))

(deftest ^{:seon.test/long "Initial complete publication constructs the canonical program once; the unchanged call must perform no population or transaction."
           :seon.test/long-ms 600000}
  unchanged-digest-keeps-the-published-commit-without-a-transaction
  (#'fixture/with-store
   (fn [store]
     (let [published (#'fixture/publish store @#'fixture/digest-a)
           transactions (atom 0)
           ;; Publication's writer counts its writes, and the population names a
           ;; Var that does not exist, so populating at all would refuse by
           ;; name: no Var is redefined.
           repeated
           (#'fixture/publish store @#'fixture/digest-a
                              'seon.cluster.source-nochange-test/no-population
                              {:seon.db/writer (fn [connection transaction]
                                                 (swap! transactions inc)
                                                 (db/transact! connection transaction))})]
       (is (= (:seon.source/commit-id published) (:seon.source/commit-id repeated)))
       (is (= (:seon.source/commit-id published)
              (:seon.source/commit-id (source/current store))))
       (is (false? (:seon.source/built? repeated)))
       (is (zero? @transactions))
       (is (empty? (#'fixture/scratch-branches store)))))))
