(ns seon.test.check-request-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test :as test]
            [seon.test-support :as support]))

(deftest check-request-returns-a-validated-observation-with-required-counts
  (support/with-database
   (fn [connection]
     (let [cluster "check-request-fixture"
           _ (support/seed-cluster! connection cluster)
           request {:seon.db/connection connection
                    :seon.boot/cluster-name cluster}
           projection (db/carried-projection (db/db connection))
           missing (test/check-request request)]
       (is (= "cluster adoption" (:seon.test/unknown missing)))
       (is (= `test/check-request (:seon.error/operation missing)))
       (is ((schema/projection-validator projection :seon.test.check/response) missing))
       (support/transacted!
        connection
        [{:db/id :db/current-tx
          :seon.test/adoption-cluster [:seon.cluster/name cluster]}])
       (let [result (test/check-request request)]
         (is (not (contains? result :seon.test/unknown)) (pr-str result))
         (is ((schema/projection-validator projection :seon.test.check/response) result))
         (is (= 0 (:seon.test/pass-count result)))
         (is (= 0 (:seon.test/fail-count result)))
         (is (= 0 (:seon.test/error-count result)))
         (is (false? ((schema/projection-validator projection :seon.test.check/result)
                      (dissoc result :seon.test/pass-count)))))))))
