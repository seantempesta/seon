(ns seon.web.datastar-test
  (:require [cljs.test :refer [deftest is]]
            [seon.web.datastar :as datastar]))

(def database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(deftest native-interest-events-become-render-evidence
  (let [change (@#'datastar/event-change
                {:db-after database
                 :tx-data [[1 :seon.agent/id "root" 42 true]
                           [2 :seon.message/text "hello" 42 true]]})]
    (is (= database (::datastar/db change)))
    (is (= #{:seon.agent/id :seon.message/text}
           (:seon.db/changed-attrs change)))
    (is (= 2 (count (:seon.db/tx-data change))))))

(deftest one-render-function-receives-the-exact-database-value
  (let [seen (atom nil)
        result (@#'datastar/render-request-result
                {::datastar/render (fn [value]
                                     (reset! seen value)
                                     [:main {:id "app-view"} "ok"])}
                {::datastar/db database})]
    (is (= database @seen))
    (is (string? (::datastar/event result)))))

(deftest nested-async-render-values-are-settled-before-serialization
  (let [inner (js-obj "then"
                      (fn [resolve _reject]
                        (resolve [:main {:id "app-view"} "ready"])))
        outer (js-obj "then" (fn [resolve _reject] (resolve inner)))
        result (@#'datastar/rendered-view-patch outer)]
    (is (string? (::datastar/event result)))
    (is (re-find #"<main id=\"app-view\">ready</main>"
                 (::datastar/event result)))
    (is (not (re-find #"Promise" (::datastar/event result))))))

(deftest subscriptions-render-only-for-declared-changed-attributes
  (let [affected? @#'datastar/subscription-affected?]
    (is (affected? {::datastar/dependencies #{:seon.agent/id}}
                   {:seon.db/changed-attrs #{:seon.agent/id}}))
    (is (not (affected? {::datastar/dependencies #{:seon.agent/id}}
                        {:seon.db/changed-attrs #{:seon.message/text}})))
    (is (affected? {::datastar/dependencies :all}
                   {:seon.db/changed-attrs #{:seon.message/text}}))
    ;; Missing transaction evidence or missing render dependencies fail open.
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

(deftest complete-render-bytes-follow-the-database-that-proved-them
  (let [next-database (assoc database :t 43)
        registry {::datastar/subscriptions
                  {:agent {::datastar/live? true
                           ::datastar/dependencies #{:seon.agent/id}
                           ::datastar/full-event "event: full\n\n"
                           ::datastar/rendered-db database}
                   :historical {::datastar/live? false
                                ::datastar/full-event "event: frozen\n\n"
                                ::datastar/rendered-db database}}}
        unchanged (@#'datastar/advance-full-events
                   registry
                   {::datastar/db next-database
                    :seon.db/changed-attrs #{:seon.message/text}})
        affected (@#'datastar/advance-full-events
                  registry
                  {::datastar/db next-database
                   :seon.db/changed-attrs #{:seon.agent/id}})]
    (is (= "event: full\n\n"
           (get-in unchanged [::datastar/subscriptions :agent
                              ::datastar/full-event])))
    (is (= next-database
           (get-in unchanged [::datastar/subscriptions :agent
                              ::datastar/rendered-db])))
    (is (= "event: frozen\n\n"
           (get-in affected [::datastar/subscriptions :historical
                             ::datastar/full-event])))
    (is (= database
           (get-in affected [::datastar/subscriptions :historical
                             ::datastar/rendered-db])))
    (is (not (contains? (get-in affected [::datastar/subscriptions :agent])
                        ::datastar/full-event)))
    (is (not (contains? (get-in affected [::datastar/subscriptions :agent])
                        ::datastar/rendered-db)))))

(deftest completed-render-becomes-the-shared-reconnect-event
  (let [event "event: datastar-patch-elements\n\n"
        recorded (@#'datastar/record-complete-event
                  {::datastar/full-event "old"}
                  {::datastar/db database}
                  {::datastar/event event})]
    (is (= event (::datastar/full-event recorded)))
    (is (= database (::datastar/rendered-db recorded)))))
