(ns seon.web.router-test
  "Behavioral tests for database-derived reitit cache invalidation."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [datahike.api :as d]
    [seon.agent.message]
    [seon.db :as db]
    [seon.route]
    [seon.schema :as schema]
    [seon.test.async :refer [settle!]]
    [seon.web.router :as router]))

(schema/register! ::note :string)

(defn temporary-handler!
  "Write the temporary route's observable response."
  [request]
  (let [^js response (:seon.http/node-res request)]
    (.writeHead response 200 #js {"Content-Type" "text/plain"})
    (.end response "temporary route")))

(defn- fresh-conn
  "Promise of an isolated in-memory database connection."
  []
  (let [config {:store              {:backend :memory :id (random-uuid)}
                :schema-flexibility :write
                :keep-history?      true}]
    (-> (d/create-database config)
        (.then (fn [_] (d/connect config {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_] conn))))))))

(defn- response-probe
  "Node response double and its namespaced observation atom."
  []
  (let [observed (atom {})
        response #js {:writeHead
                      (fn [status headers]
                        (swap! observed assoc
                               ::response-status status
                               ::response-headers (js->clj headers)))
                      :end
                      (fn [body]
                        (swap! observed assoc
                               ::response-body (or body "")))}]
    [response observed]))

(defn- request!
  "Dispatch one synthetic GET and return its response observations."
  ([path] (request! "GET" path))
  ([method path]
   (let [[response observed] (response-probe)
         request #js {:url path :method method :headers #js {}}]
     (router/handle-request request response)
     @observed)))

(defn- cached-ring-handler
  "The current compiled handler identity, used to prove cache reuse."
  []
  (let [state-atom @#'router/!router-state]
    (:seon.web.router/ring-handler @state-atom)))

(deftest operator-config-route-reaches-the-injected-live-operation
  (router/install!
    {:seon.web.router/config-apply
     (fn [_request ^js response]
       (.writeHead response 200 #js {"Content-Type" "application/edn"})
       (.end response "{:seon.state/ok? true}"))
     :seon.web.router/same-origin? (constantly true)})
  (let [response (request! "POST" "/_seon/operator/config")]
    (is (= 200 (::response-status response)))
    (is (= "{:seon.state/ok? true}" (::response-body response)))))

(deftest route-facts-update-the-live-router-without-explicit-rebuild
  (async done
    (-> (fresh-conn)
        (.then
          (fn [conn]
            (let [prior-conn db/*conn*]
              (set! db/*conn* conn)
              (router/attach!)
              (-> (js/Promise.resolve nil)
                  (.then
                    (fn [_]
                      (testing "the temporary path starts at the default not-found handler"
                        (let [response (request! "/temporary-route")]
                          (is (= 302 (::response-status response)))
                          (is (= "/" (get (::response-headers response) "Location")))))
                      (db/transact!
                        {:seon.db/conn conn
                         :seon.db/tx-data
                         [{:seon.route/name    ::temporary-route
                           :seon.route/pattern "/temporary-route"
                           :seon.route/method  :get
                           :seon.route/handler
                           'seon.web.router-test/temporary-handler!}]})))
                  (.then
                    (fn [result]
                      (is (true? (:seon.db/ok? result)))
                      (testing "a committed route is requestable immediately"
                        (let [response (request! "/temporary-route")]
                          (is (= 200 (::response-status response)))
                          (is (= "temporary route" (::response-body response)))))
                      (let [handler-before (cached-ring-handler)]
                        (-> (db/transact!
                              {:seon.db/conn conn
                               :seon.db/tx-data [{::note "unrelated"}]})
                            (.then
                              (fn [unrelated-result]
                                (is (true? (:seon.db/ok? unrelated-result)))
                                (is (identical? handler-before
                                                (cached-ring-handler))
                                    "an unrelated commit reuses the compiled router")))))))
                  (.then
                    (fn [_]
                      (db/transact!
                        {:seon.db/conn conn
                         :seon.db/tx-data
                         [[:db.fn/retractEntity
                           [:seon.route/name ::temporary-route]]]})))
                  (.then
                    (fn [result]
                      (is (true? (:seon.db/ok? result)))
                      (testing "retraction immediately restores not-found"
                        (let [response (request! "/temporary-route")]
                          (is (= 302 (::response-status response)))
                          (is (= "/" (get (::response-headers response) "Location")))))))
                  (.finally
                    (fn []
                      (router/detach!)
                      (set! db/*conn* prior-conn)))))))
        (settle! done))))
