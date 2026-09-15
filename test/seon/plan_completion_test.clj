(ns seon.plan-completion-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.plan :as plan]
            [seon.test-support :as support]))

(def message-query
  '[:find ?message :in $ ?subject
    :where [?message :seon.message/about ?subject]
           [?message :seon.message/from ?sender]
           [?sender :seon.agent/id "worker"]])

(deftest query-completion-is-decided-by-the-writing-database
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "plan-completion")
      (is (:db-after
           (db/transact! connection
                         [{:seon.agent/id "worker"}
                          {:seon.agent/id "requester"}
                          {:seon.message/id "request"
                           :seon.message/to [:seon.agent/id "worker"]
                           :seon.message/content "Report the result."}])))
      (let [added (plan/add! {:my.plan.item/id "report"
                              :my.plan.item/title "Report"
                              :my.plan.item/done-when "The reply exists."
                              :my.plan.item/done-query message-query
                              :my.plan.item/subject [:seon.message/id "request"]}
                             connection "worker")
            failure (plan/complete! "report" connection "worker")]
        (is (= message-query (:my.plan.item/done-query added)))
        (is (= :my.plan/done-query-unsatisfied (:seon.error/kind failure)) (pr-str failure))
        (is (= message-query (get-in failure [:seon.error/data :my.plan.item/done-query])))
        (is (= #{} (get-in failure [:seon.error/data :seon.db/result])))
        (is (nil? (:my.plan.item/completed-tx
                   (db/pull @connection '[*] [:my.plan.item/id "report"])))))
      (plan/start! "report" connection "worker")
      (is (str/includes? (plan/format-plan-ai (plan/plan {:seon.db/db @connection :seon.agent/id "worker"}))
                         "done-query:"))
      (let [written (db/transact!
                     connection
                     [{:seon.message/id "reply"
                       :seon.message/from [:seon.agent/id "worker"]
                       :seon.message/to [:seon.agent/id "requester"]
                       :seon.message/content "Verified."
                       :seon.message/about [:seon.message/id "request"]}
                      [:db.fn/call #'plan/settle-call "worker"]])
            completed (db/pull (:db-after written)
                               '[{:my.plan.item/completed-tx [:db/id :db/txInstant]}]
                               [:my.plan.item/id "report"])
            completion (:my.plan.item/completed-tx completed)]
        (is (:db-after written) (pr-str written))
        (is (= (db/basis-t (:db-after written)) (:db/id completion)))
        (is (inst? (:db/txInstant completion)))
        (is (= {} (plan/current @connection "worker")))
        (db/transact! connection [[:db.fn/call #'plan/settle-call "worker"]])
        (is (= completed (db/pull @connection
                                  '[{:my.plan.item/completed-tx [:db/id :db/txInstant]}]
                                  [:my.plan.item/id "report"]))))
      (testing "authored steps without queries retain assertion completion"
        (plan/add! {:my.plan.item/id "authored" :my.plan.item/title "Review"} connection "worker")
        (is (= :completed (:my.plan/state (plan/complete! "authored" connection "worker")))))
      (testing "a query error never becomes successful completion"
        (plan/add! {:my.plan.item/id "invalid" :my.plan.item/title "Invalid query"
                    :my.plan.item/done-query '[:find ?unbound :where [?a :seon.agent/id "worker"]]}
                   connection "worker")
        (let [failure (plan/complete! "invalid" connection "worker")]
          (is (= :my.plan/done-query-failed (:seon.error/kind failure)) (pr-str failure))
          (is (nil? (:my.plan.item/completed-tx
                     (db/pull @connection '[*] [:my.plan.item/id "invalid"])))))))))
