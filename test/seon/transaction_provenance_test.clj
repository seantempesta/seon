(ns seon.transaction-provenance-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test-support :as support]))

(deftest every-write-records-one-writer-at-one-ten-and-one-hundred-inputs
  (support/with-database
   (fn [connection]
     (let [findings (vec (take 100 (sort (db/q '[:find [?id ...] :where [_ :seon.lint/id ?id]] (db/db connection)))))
           agent (id/id [::writer])
           operation 'seon.transaction-provenance-test/every-write-records-one-writer-at-one-ten-and-one-hundred-inputs]
       (is (= 100 (count findings)))
       (doseq [n [1 10 100]]
         (let [before (db/db connection)
               tx (mapv (fn [finding]
                          [:db/add [:seon.lint/id finding] :seon.lint/row
                           (inc (:seon.lint/row (db/pull before [:seon.lint/row] [:seon.lint/id finding])))])
                        (subvec findings 0 n))
               report (support/transacted! connection {:tx-data tx
                                                       :seon.db.transaction/operation operation
                                                       :seon.db.transaction/agent agent})
               stamps (filter #(= "seon.db.transaction" (namespace (:a %))) (:tx-data report))]
           (is (= 1 (- (db/basis-t (:db-after report)) (db/basis-t before))))
           (is (= 2 (count stamps)) "one fixed-size attribution per commit")
           (is (= {:seon.db.transaction/operation operation :seon.db.transaction/agent agent}
                  (into {} (map (juxt :a :v)) stamps)))
           (is (every? #(= (:e %) (:tx %)) stamps) "provenance belongs to the transaction")))))))

(deftest unnamed-system-writes-do-not-invent-an-agent
  (support/with-database
   (fn [connection]
     (let [stamp (last (:tx-data (#'db/stamp-transaction (db/db connection) [] nil)))]
       (is (= 'seon.db/transact! (:seon.db.transaction/operation stamp)))
       (is (not (contains? stamp :seon.db.transaction/agent)))))))
