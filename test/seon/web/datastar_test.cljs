(ns seon.web.datastar-test
  (:require [cljs.test :refer [async deftest is]]
            [seon.db :as db]
            [seon.execution :as execution]
            [seon.ui.html :as html]
            [seon.web.datastar :as datastar]))

(def database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(deftest unknown-agent-page-redirects-before-serving-a-loading-shell
  (async done
    (let [original-db db/db
          original-query db/query
          request (js/Request. "http://127.0.0.1/agent/no-such-agent")]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/query
            (fn [request]
              (is (identical? database (::db/db request)))
              (js/Promise.resolve #{})))
      (-> (datastar/serve-agent-page!
           {:seon.http/request request
            :path-params {:id "no-such-agent"}})
          (.then
           (fn [response]
             (is (instance? js/Response response))
             (is (= 302 (.-status response)))
             (is (= "/" (.get (.-headers response) "Location")))
             (.text response)))
          (.then #(is (= "" %)))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/query original-query)
             (done)))))))

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
        result (with-redefs [html/->string
                             (fn [_]
                               (js-obj "then"
                                       (fn [resolve _reject]
                                         (resolve
                                          "<main id=\"app-view\">ready</main>"))))]
                 (@#'datastar/rendered-view-patch
                  {::datastar/element outer
                   ::datastar/dependencies #{:seon.agent/id}}))]
    (is (string? (::datastar/event result)))
    (is (= #{:seon.agent/id} (::datastar/dependencies result)))
    (is (re-find #"<main id=\"app-view\">ready</main>"
                 (::datastar/event result)))
    (is (not (re-find #"Promise" (::datastar/event result))))))

(deftest native-javascript-promises-are-recognized
  (is (@#'datastar/promise-like? (js/Promise.resolve :ready))))

(deftest feed-compression-is-explicit-and-negotiated
  (let [select @#'datastar/selected-feed-encoding]
    (is (= :identity (select nil "gzip, deflate")))
    (is (= :identity (select "identity" "gzip")))
    (is (= :gzip (select "gzip" "br, gzip")))
    (is (= :gzip (select "gzip" "br, *;q=0.5")))
    (is (= :identity (select "gzip" "br")))
    (is (= :identity (select "gzip" "gzip;q=0, br")))
    (is (thrown? js/Error (select "invented" "gzip")))))

(deftest child-database-errors-remain-the-visible-render-error
  (let [result (@#'datastar/agent-view-result
                {::execution/message execution/result-message
                 ::execution/result
                 {:seon.error/message
                  "datahike query-results budget exceeded"}})]
    (is (= :main (first (::datastar/element result))))
    (is (= :all (::datastar/dependencies result)))
    (is (= "render error: datahike query-results budget exceeded"
           (last (::datastar/element result))))))

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

(deftest listener-refresh-does-not-duplicate-render-work-for-the-same-database
  (let [covered? @#'datastar/subscription-has-database?
        next-database (assoc database :t 43)]
    (is (covered? {::datastar/rendered-db database} database))
    (is (covered? {::datastar/active-render {::datastar/db database}}
                  database))
    (is (covered? {::datastar/pending-render {::datastar/db database}}
                  database))
    (is (not (covered? {::datastar/rendered-db database}
                       next-database)))))

(deftest completed-render-becomes-the-shared-reconnect-event
  (let [event "event: datastar-patch-elements\n\n"
        recorded (@#'datastar/record-complete-event
                  {::datastar/full-event "old"}
                  {::datastar/db database}
                  {::datastar/event event})]
    (is (= event (::datastar/full-event recorded)))
    (is (= database (::datastar/rendered-db recorded)))))

(deftest direct-stream-backpressure-keeps-only-the-newest-event
  (async done
    (let [writes (atom [])
          finish-flush (atom nil)
          flush (js/Promise. (fn [resolve _reject]
                               (reset! finish-flush resolve)))
          first-write? (atom true)
          controller
          #js {:write (fn [event]
                        (swap! writes conj event)
                        (if (compare-and-set! first-write? true false) -1 1))
               :flush (fn [wait?]
                        (is (true? wait?))
                        flush)}
          conn {:seon.web.feed/controller controller
                :seon.web.feed/closed? (atom false)
                :seon.web.feed/pending-event (atom nil)
                :seon.web.feed/draining? (atom false)
                :seon.web.feed/backpressured-at (atom nil)
                :seon.web.feed/close! (fn [_] nil)}]
      (@#'datastar/push-event! conn "first")
      (@#'datastar/push-event! conn "obsolete")
      (@#'datastar/push-event! conn "latest")
      (is (= ["first"] @writes))
      (@finish-flush nil)
      (-> flush
          (.then (fn [] (js/Promise.resolve nil)))
          (.then
           (fn []
             (is (= ["first" "latest"] @writes))
             (is (nil? @(:seon.web.feed/pending-event conn)))
             (is (false? @(:seon.web.feed/draining? conn)))))
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))

(deftest performance-measurements-are-bounded-and-reset-without-closing-feeds
  (let [feeds @#'datastar/!feeds
        original @feeds]
    (try
      (reset! feeds {::datastar/views {"view" {::datastar/view-id "view"}}
                     ::datastar/subscriptions {:agent {}}
                     ::datastar/measurements {}})
      (@#'datastar/record-count! ::datastar/render-requested 2)
      (@#'datastar/record-sample! ::datastar/render-duration-ms 4)
      (@#'datastar/record-sample! ::datastar/render-duration-ms 10)
      (let [snapshot (datastar/performance-snapshot)]
        (is (= 1 (::datastar/view-count snapshot)))
        (is (= 1 (::datastar/subscription-count snapshot)))
        (is (= 2 (get-in snapshot [::datastar/measurements
                                   ::datastar/render-requested])))
        (is (= {::datastar/sample-count 2
                ::datastar/sample-total 14
                ::datastar/sample-maximum 10
                ::datastar/sample-latest 10}
               (get-in snapshot [::datastar/measurements
                                 ::datastar/render-duration-ms]))))
      (let [reset-snapshot (datastar/reset-performance!)]
        (is (= {} (::datastar/measurements reset-snapshot)))
        (is (= 1 (::datastar/view-count reset-snapshot)))
        (is (= 1 (::datastar/subscription-count reset-snapshot))))
      (finally
        (reset! feeds original)))))
