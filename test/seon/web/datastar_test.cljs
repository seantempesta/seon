(ns seon.web.datastar-test
  (:require [cljs.test :refer [async deftest is]]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.execution :as execution]
            [seon.reactive :as reactive]
            [seon.ui.agent-view :as agent-view]
            [seon.ui.html :as html]
            [seon.web.datastar :as datastar]))

(def database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(deftest root-page-has-one-namespace-aware-agent-form-outside-the-morph
  (let [root-markup (@#'datastar/root-page-html)
        agent-markup (@#'datastar/agent-page-html "worker")
        app-end (str/index-of root-markup "</main>")
        form-start (str/index-of root-markup "id=\"app-agent-create\"")]
    (is (= 1 (count (re-seq #"id=\"app-agent-create\"" root-markup))))
    (is (< app-end form-start)
        "human controls remain outside the whole-element morph target")
    (is (str/includes?
         root-markup
         (str "data-on:submit=\"@post(&#39;/agents&#39;, "
              "{contentType:&#39;form&#39;})\"")))
    (doseq [[field signal label]
            [["namespace" "agentNamespace" "agent namespace"]
             ["purpose" "agentPurpose" "agent purpose"]
             ["message" "agentMessage" "initial agent message"]]]
      (let [input (some #(when (str/includes? % (str "name=\"" field "\"")) %)
                        (re-seq #"<input[^>]+>" root-markup))]
        (is (str/includes? input (str "data-bind=\"" signal "\""))
            (str "binds the optional " field " form field"))
        (is (str/includes? input (str "aria-label=\"" label "\""))
            (str "labels the optional " field " form field"))))
    (is (str/includes? root-markup "id=\"app-chat\""))
    (is (str/includes? root-markup "id=\"app-agent-feed\""))
    (is (not (str/includes? agent-markup "id=\"app-agent-create\""))
        "ordinary agent pages do not gain a second cluster control")))

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

(def read-evidence
  [{::db/db database
    ::db/source-argument-position 0
    :datahike.read/dependency-plan :all}])

(deftest complete-render-returns-event-value-and-child-read-evidence
  (async done
    (let [seen (atom nil)]
      (-> (js/Promise.resolve
           (@#'datastar/render-read
            {::datastar/render
             (fn [value]
               (reset! seen value)
               {::datastar/element [:main {:id "app-view"} "ok"]
                ::db/read-evidence read-evidence})}
            database))
          (.then
           (fn [result]
             (is (= database @seen))
             (is (string? (::db/value result)))
             (is (= read-evidence (::db/read-evidence result)))))
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))

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
                   ::db/read-evidence read-evidence}))]
    (is (string? (::datastar/event result)))
    (is (= read-evidence (::db/read-evidence result)))
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
    (is (= :all (::db/read-evidence result)))
    (is (= "render error: datahike query-results budget exceeded"
           (last (::datastar/element result))))))

(deftest child-message-read-evidence-is-authoritative
  (with-redefs [agent-view/render-agent-view
                (fn [_] [:main {:id "app-view"} "projection"])]
    (let [result (@#'datastar/agent-view-result
                  {::execution/message execution/result-message
                   ::execution/result {:unrelated/declaration :wrong}
                   ::db/read-evidence read-evidence})]
      (is (= read-evidence (::db/read-evidence result))))))

(deftest structural-settle-selects-the-database-configured-delay
  (let [select @#'datastar/structural-settle-ms
        policy {:seon.config/reactive-settle-ms 7
                :seon.config/reactive-structural-settle-ms 70
                :seon.config/reactive-max-latency-ms 100}]
    (is (= 7 (select {:tx-data [[1 :seon.message/text "x" 1 true]]}
                     policy)))
    (is (= 70 (select {:tx-data [[1 :seon.render/html "x" 1 true]]}
                      policy)))))

(deftest live-root-feed-observer-omits-the-absent-database-value
  (async done
    (let [feeds @#'datastar/!feeds
          original @feeds
          original-observe reactive/observe!
          request (atom nil)
          subscription-key [:seon.web.feed/agent "root"]
          conn {::datastar/view-id "root-view"
                ::datastar/subscription-key subscription-key
                :seon.web.feed/id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}]
      (reset! feeds
              {::datastar/views {"root-view" conn}
               ::datastar/subscriptions
               {subscription-key
                {::datastar/live? true
                 ::datastar/render (fn [_] [:main {:id "app-view"}])}}
               ::datastar/measurements {}})
      (set! reactive/observe!
            (fn [value]
              (reset! request value)
              (js/Promise.resolve (:seon.reactive/consumer-key value))))
      (-> (js/Promise.resolve (@#'datastar/observe-connection! conn))
          (.then
           (fn [_]
             (is (map? @request))
             (is (not (contains? @request ::db/db))
                 "a live root feed reaches reactive observe without invalid nil")))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! reactive/observe! original-observe)
             (reset! feeds original)
             (done)))))))

(deftest prepared-socket-retains-the-reactive-registration-key-for-close
  (let [subscription-key [:seon.web.feed/agent "root"]
        conn (@#'datastar/prepare-feed
              {:seon.web.feed/key subscription-key
               :seon.web.feed/live? true
               :seon.web.feed/render (fn [_])}
              "root-view"
              #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
              #js {}
              (atom false)
              (fn [_]))]
    (is (= subscription-key (::datastar/subscription-key conn))
        "the close callback can always release its exact reactive consumer")))

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
