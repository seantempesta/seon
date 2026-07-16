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

(deftest addressed-interest-events-become-coordinate-only-render-evidence
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

(deftest subscriptions-render-only-for-declared-changed-attributes
  (let [affected? @#'datastar/subscription-affected?]
    (is (affected? {::datastar/dependencies #{:seon.agent/id}}
                   {:seon.db/changed-attrs #{:seon.agent/id}}))
    (is (not (affected? {::datastar/dependencies #{:seon.agent/id}}
                        {:seon.db/changed-attrs #{:seon.message/text}})))
    (is (affected? {::datastar/dependencies :all}
                   {:seon.db/changed-attrs #{:seon.message/text}}))
    ;; Missing transaction evidence or missing producer dependencies fail open.
    (is (affected? {::datastar/dependencies #{:seon.agent/id}}
                   {:seon.db/changed-attrs #{}}))
    (is (affected? {}
                   {:seon.db/changed-attrs #{:seon.message/text}}))))

(deftest one-listener-unions-only-live-subscription-dependencies
  (let [dependencies @#'datastar/live-listener-dependencies]
    (is (= #{:seon.agent/id :seon.message/text}
           (dependencies
            {::datastar/subscriptions
             {:agent {::datastar/live? true
                      ::datastar/dependencies #{:seon.agent/id}}
              :debug {::datastar/live? true
                      ::datastar/dependencies #{:seon.message/text}}
              :history {::datastar/live? false
                        ::datastar/dependencies :all}}})))
    (is (= :all
           (dependencies
            {::datastar/subscriptions
             {:agent {::datastar/live? true}}})))
    (is (nil? (dependencies
               {::datastar/subscriptions
                {:history {::datastar/live? false
                           ::datastar/dependencies :all}}})))))

(deftest listener-query-declares-the-exact-attribute-union
  (is (= '[:find (count ?e) . :where
           [?e :seon.agent/id _]
           [?e :seon.message/text _]]
         (@#'datastar/dependencies-query
          #{:seon.message/text :seon.agent/id}))))

(deftest complete-render-bytes-follow-the-coordinate-that-proved-them
  (let [next-point (assoc point ::coordinate/t 43)
        registry {::datastar/subscriptions
                  {:agent {::datastar/live? true
                           ::datastar/dependencies #{:seon.agent/id}
                           ::datastar/full-event "event: full\n\n"
                           ::datastar/full-event-coordinate point}
                   :historical {::datastar/live? false
                                ::datastar/full-event "event: frozen\n\n"
                                ::datastar/full-event-coordinate point}}}
        unchanged (@#'datastar/advance-full-events
                   registry
                   {:seon.db/coordinate next-point
                    :seon.db/changed-attrs #{:seon.message/text}})
        affected (@#'datastar/advance-full-events
                  registry
                  {:seon.db/coordinate next-point
                   :seon.db/changed-attrs #{:seon.agent/id}})]
    (is (= "event: full\n\n"
           (get-in unchanged [::datastar/subscriptions :agent
                              ::datastar/full-event])))
    (is (= next-point
           (get-in unchanged [::datastar/subscriptions :agent
                              ::datastar/full-event-coordinate])))
    (is (= "event: frozen\n\n"
           (get-in affected [::datastar/subscriptions :historical
                             ::datastar/full-event])))
    (is (= point
           (get-in affected [::datastar/subscriptions :historical
                             ::datastar/full-event-coordinate])))
    (is (not (contains? (get-in affected [::datastar/subscriptions :agent])
                        ::datastar/full-event)))
    (is (not (contains? (get-in affected [::datastar/subscriptions :agent])
                        ::datastar/full-event-coordinate)))))

(deftest completed-change-becomes-the-shared-reconnect-event
  (let [event "event: datastar-patch-elements\n\n"
        recorded (@#'datastar/record-complete-event
                  {::datastar/full-event "old"
                   ::datastar/full-event-committed? true}
                  {::datastar/render-full? true
                   ::datastar/change {:seon.db/coordinate point}}
                  {::datastar/event event})]
    (is (= event (::datastar/full-event recorded)))
    (is (= point (::datastar/full-event-coordinate recorded)))
    (is (true? (::datastar/full-event-committed? recorded)))))

(deftest partial-patch-never-replaces-the-complete-reconnect-event
  (let [recorded (@#'datastar/record-complete-event
                  {::datastar/full-event "complete"
                   ::datastar/full-event-coordinate point
                   ::datastar/full-event-committed? true}
                  {::datastar/change
                   {:seon.db/coordinate (assoc point ::coordinate/t 43)}}
                  {::datastar/event "partial"})]
    (is (= "complete" (::datastar/full-event recorded)))
    (is (= point (::datastar/full-event-coordinate recorded)))))
