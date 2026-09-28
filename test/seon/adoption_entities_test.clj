(ns seon.adoption-entities-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test-support :as support]))

(deftest adoption-record-size-does-not-grow-with-reload-identities
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           names (vec (take 100 (sort (db/q '[:find [?s ...] :where [_ :seon.fn/sym ?s]] database))))
           cluster-ref [:seon.cluster/name (:seon.cluster/name (support/execution-handle connection))]]
       (is (= 100 (count names)))
       (doseq [n [1 10 100]]
         (let [declarations (mapv #(vector :seon.fn/sym %) (subvec names 0 n))
               identities (into declarations [[:seon.fn.file/relative-path "src/seon/cluster.clj"]
                                               [:seon.lint/id (id/id [:adoption-test :finding])]])
               reload (#'cluster/reload-identities identities)
               record (#'cluster/adoption-record cluster-ref ["src/seon/cluster.clj"])
               report (support/transacted! connection [record])
               facts (filter #(= "seon.test" (namespace (:a %))) (:tx-data report))]
           (is (= declarations reload) "reload retains declaration identities")
           (is (= #{:seon.test/adoption-cluster :seon.test/adoption-inputs}
                  (set (map :a facts))))
           (is (= 2 (count facts)) "recording work follows changed files, not declarations")))))))
