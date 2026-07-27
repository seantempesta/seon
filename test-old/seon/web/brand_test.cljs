(ns seon.web.brand-test
  (:require
   [cljs.test :refer [deftest is]]
   [seon.web.brand :as brand]))

(deftest effective-brand-is-pure-over-an-ordinary-row
  (is (= brand/defaults (brand/info nil)))
  (is (= (assoc brand/defaults ::brand/name "acme")
         (brand/info {::brand/name "acme"}))))

(deftest brand-sync-transaction-is-derived-data
  (is (= [{::brand/id "brand" ::brand/name "acme"}]
         (brand/sync-tx-data
          {::brand/row nil
           ::brand/env {::brand/name "acme"}})))
  (is (= [[:db/retract [::brand/id "brand"] ::brand/name "old"]]
         (brand/sync-tx-data
          {::brand/row {::brand/name "old"}
           ::brand/env {}}))))
