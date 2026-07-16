(ns seon.web.datastar-test
  (:require [cljs.test :refer [deftest is]]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.web.datastar :as datastar]))

(def point
  {::coordinate/database-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   ::coordinate/branch :db
   ::coordinate/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   ::coordinate/t 42})

(deftest transaction-events-become-coordinate-only-render-evidence
  (let [change (@#'datastar/event-change
                {::protocol/event protocol/datoms-event
                 ::protocol/request-id "views"
                 ::protocol/coordinate point
                 ::protocol/datoms
                 [{:seon.db/e 1 :seon.db/a :seon.agent/id
                   :seon.db/v "root" :seon.db/tx 42 :seon.db/added? true}]})]
    (is (= point (:seon.db/coordinate change)))
    (is (= #{:seon.agent/id} (:seon.db/changed-attrs change)))
    (is (not (contains? change :seon.db/db)))))

(deftest unit-catalog-does-not-invoke-producers
  (let [calls (atom 0)
        catalog (datastar/unit-catalog
                 [{::datastar/coordinate {:example/id "one"}
                   ::datastar/producer #(swap! calls inc)}])]
    (is (= 1 (count catalog)))
    (is (zero? @calls))))
