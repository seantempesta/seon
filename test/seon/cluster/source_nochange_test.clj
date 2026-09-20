(ns seon.cluster.source-nochange-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.source :as source]
            [seon.cluster.source-test :as fixture]
            [seon.db :as db]))

(deftest ^{:seon.test/long "Initial complete publication constructs the canonical program once; the unchanged call must perform no population, activation, or transaction."
           :seon.test/long-ms 600000}
  unchanged-digest-keeps-the-published-commit-without-a-transaction
  (#'fixture/with-store
   (fn [store]
     (let [published (#'fixture/publish store @#'fixture/digest-a)
           transactions (atom 0)
           populations (atom 0)
           activations (atom 0)
           transact! db/transact!
           populate! fixture/populate!
           activation fixture/activation
           repeated
           (with-redefs [db/transact! (fn [& arguments]
                                       (swap! transactions inc)
                                       (apply transact! arguments))
                         fixture/populate! (fn [request]
                                             (swap! populations inc)
                                             (populate! request))
                         fixture/activation (fn [request]
                                              (swap! activations inc)
                                              (activation request))]
             (#'fixture/publish store @#'fixture/digest-a))]
       (is (= (:seon.source/commit-id published) (:seon.source/commit-id repeated)))
       (is (= (:seon.source/commit-id published)
              (:seon.source/commit-id (source/current store))))
       (is (false? (:seon.source/built? repeated)))
       (is (= [0 0 0] [@transactions @populations @activations]))
       (is (empty? (#'fixture/scratch-branches store)))))))
