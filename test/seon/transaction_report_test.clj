(ns seon.transaction-report-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest changed-entities-and-tempids-share-two-reads-at-one-ten-and-one-hundred
  (support/with-database
   (fn [connection]
     (let [names (vec (take 100 (sort (db/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] (db/db connection)))))]
       (is (= 100 (count names)))
       (doseq [n [1 10 100]]
         (let [rows (db/pull-many (db/db connection) [:seon.test/sym :seon.test/pass-count]
                                 (mapv #(vector :seon.test/sym %) (subvec names 0 n)))
               tx (mapv (fn [i row] (assoc row :db/id (str "test-" i)
                                           :seon.test/pass-count (inc (get row :seon.test/pass-count 0))))
                        (range n) rows)
               report (support/transacted! connection tx)
               [view reads] (support/read-operations #(db/render-transaction-ai report))
               identities (set (map #(vector :seon.test/sym %) (subvec names 0 n)))]
           (is (= identities (set (map :seon.db.report/identity (:seon.db.report/entities view)))))
           (is (= identities (set (vals (:seon.db.report/tempids view)))))
           (is (= (reduce + (map #(if (contains? % :seon.test/pass-count) 2 1) rows))
                  (:seon.db.report/facts view)))
           (is (every? #(= [:seon.test/pass-count] (:seon.db.report/changed %)) (:seon.db.report/entities view)))
           (is (= (if (= 1 n) [:pull :pull] [:pull-many :pull-many]) reads)
               "changed entities and temporary names reuse the same identity reads")))))))
