(ns seon.web.router-test
  "Behavioral tests for database-derived reitit cache invalidation."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [seon.agent.message]
    [seon.db :as db]
    [seon.db.coordinate :as coordinate]
    [seon.route]
    [seon.runtime.admission :as admission]
    [seon.test.async :refer [settle!]]
    [seon.web.router :as router]))

(defn temporary-handler!
  "Write the temporary route's observable response."
  [request]
  (let [^js response (:seon.http/node-res request)]
    (.writeHead response 200 #js {"Content-Type" "text/plain"})
    (.end response "temporary route")))


(def ^:private route-query
  '[:find [(pull ?route [:seon.route/pattern
                         :seon.route/method
                         :seon.route/handler
                         :seon.route/middleware]) ...]
    :where [?route :seon.route/pattern]])

(defn- database-coordinate
  "One complete immutable database coordinate for route tests."
  [transaction]
  {::coordinate/database-id (random-uuid)
   ::coordinate/branch :db
   ::coordinate/commit-id (random-uuid)
   ::coordinate/t transaction})

(defn- route-row
  "One ordinary database route projection row."
  [pattern]
  {:seon.route/pattern pattern
   :seon.route/method :get
   :seon.route/handler 'seon.web.router-test/temporary-handler!})

(defn- deferred
  "A Promise and the function that resolves it."
  []
  (let [!resolve (atom nil)
        promise (js/Promise. (fn [resolve _reject]
                               (reset! !resolve resolve)))]
    {::promise promise
     ::resolve! (fn [value] (@!resolve value))}))

(defn- next-turn
  "Yield until queued Promise continuations have run."
  []
  (js/Promise. (fn [resolve _reject]
                 (js/setTimeout resolve 0))))

(defn- with-route-db-fakes
  "Run one Promise body with direct database functions replaced and restored."
  [{::keys [query listen unlisten]} body]
  (let [original-query db/query
        original-listen db/listen!
        original-unlisten db/unlisten!
        state-atom @#'router/!router-state
        prior-state @state-atom]
    ;; Preserve the public function's CLJS fixed/variadic dispatch properties.
    ;; Replacing a multi-arity var with a plain one-arity function makes a
    ;; compiled `(db/query request)` call fail before the fake is invoked.
    (set! db/query
          (fn
            ([request] (query request))
            ([query-form & inputs] (apply query query-form inputs))))
    (set! db/listen!
          (fn
            ([request] (listen request))
            ([key handler] (listen {:seon.db/key key
                                    :seon.db/handler handler}))
            ([database key handler]
             (listen {:seon.db/db database
                      :seon.db/key key
                      :seon.db/handler handler}))))
    (set! db/unlisten! (fn [request] (unlisten request)))
    (reset! state-atom {})
    (-> (js/Promise.resolve nil)
        (.then (fn [] (body)))
        (.finally
          (fn []
            (set! db/query original-query)
            (set! db/listen! original-listen)
            (set! db/unlisten! original-unlisten)
            (reset! state-atom prior-state))))))

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

(deftest action-door-is-only-database-projected
  (let [projection [{:seon.route/pattern "/agent/{id}/call"
                     :seon.route/method :post
                     :seon.route/handler
                     'seon.web.router-test/temporary-handler!
                     :seon.route/middleware :seon.route/same-origin}]
        projected (router/db->routes projection)
        supplement (#'router/static-supplement {})]
    (is (= ["/agent/{id}/call"] (mapv first projected)))
    (is (not (contains? (set (map first supplement)) "/call"))
        "the static compatibility address is absent")))

(deftest operator-quiesce-route-is-unadmitted-and-loopback-only
  (let [!invocations (atom 0)
        handler (fn [_request ^js response]
                  (swap! !invocations inc)
                  (.writeHead response 200 #js {"Content-Type" "application/edn"})
                  (.end response "{:seon.runtime/quiesced? true}"))]
    (router/install!
      {:seon.web.router/operator-quiesce handler
       :seon.web.router/loopback-peer? (constantly false)})
    (let [response (request! "POST" "/_seon/operator/quiesce")]
      (is (= 403 (::response-status response)))
      (is (zero? @!invocations)))
    (router/install!
      {:seon.web.router/operator-quiesce handler
       :seon.web.router/loopback-peer? (constantly true)})
    (let [prior (admission/state)]
      (try
        (reset! @#'admission/!state
                {::admission/status :unavailable
                 ::admission/reason "ordinary work is closed"})
        (let [response (request! "POST" "/_seon/operator/quiesce")]
          (is (= 200 (::response-status response)))
          (is (= "{:seon.runtime/quiesced? true}" (::response-body response)))
          (is (= 1 @!invocations)
              "lifecycle work bypasses ordinary admission only for loopback"))
        (finally
          (reset! @#'admission/!state prior))))))

(deftest retained-blob-route-is-unadmitted-and-loopback-only
  (let [!invocations (atom 0)
        handler (fn [_request ^js response]
                  (swap! !invocations inc)
                  (.writeHead response 200 #js {"Content-Type" "application/edn"})
                  (.end response "{:my.blob/ok? true}"))]
    (router/install!
      {:seon.web.router/operator-blobs handler
       :seon.web.router/loopback-peer? (constantly false)})
    (is (= 403 (::response-status
                 (request! "POST" "/_seon/operator/blobs"))))
    (is (zero? @!invocations))
    (router/install!
      {:seon.web.router/operator-blobs handler
       :seon.web.router/loopback-peer? (constantly true)})
    (let [prior (admission/state)]
      (try
        (reset! @#'admission/!state
                {::admission/status :unavailable
                 ::admission/reason "restore admission is closed"})
        (let [response (request! "POST" "/_seon/operator/blobs")]
          (is (= 200 (::response-status response)))
          (is (= "{:my.blob/ok? true}" (::response-body response)))
          (is (= 1 @!invocations)))
        (finally
          (reset! @#'admission/!state prior))))))

(deftest closed-admission-refuses-post-before-the-domain-handler
  (let [!invocations (atom 0)
        prior (admission/state)]
    (try
      (router/install!
        {:seon.web.router/config-apply
         (fn [_request ^js response]
           (swap! !invocations inc)
           (.writeHead response 200 #js {"Content-Type" "application/edn"})
           (.end response "{:seon.state/ok? true}"))
         :seon.web.router/same-origin? (constantly true)})
      (reset! @#'admission/!state
              {::admission/status :unavailable
               ::admission/reason "injected publication failure"})
      (let [response (request! "POST" "/_seon/operator/config")]
        (is (= 503 (::response-status response)))
        (is (zero? @!invocations)
            "closed admission reaches neither parsing nor domain work"))
      (finally
        (reset! @#'admission/!state prior)))))

(deftest route-interest-queries-the-acknowledged-and-later-coordinates
  (async done
    (let [ack-coordinate (database-coordinate 10)
          event-coordinate (assoc ack-coordinate
                                  ::coordinate/commit-id (random-uuid)
                                  ::coordinate/t 11)
          interest-key "database-interest/routes-17"
          !queries (atom [])
          !listen-request (atom nil)
          !unlisten-request (atom nil)
          !handler (atom nil)]
      (->
        (with-route-db-fakes
          {::query
           (fn [request]
             (swap! !queries conj request)
             (js/Promise.resolve
               (if (= event-coordinate (:seon.db/coordinate request))
                 [(route-row "/temporary-route")]
                 [])))
           ::listen
           (fn [request]
             (reset! !listen-request request)
             (reset! !handler (:seon.db/handler request))
             (js/Promise.resolve {:seon.db/key interest-key
                                  :seon.db/coordinate ack-coordinate}))
           ::unlisten
           (fn [request]
             (reset! !unlisten-request request)
             (js/Promise.resolve {:seon.db/ok? true}))}
          (fn []
            (router/install! {})
            (-> (router/attach!)
                (.then
                  (fn [_]
                    (testing "the exact route query defines the interest"
                      (is (= route-query
                             (:seon.db/query @!listen-request))))
                    (testing "initial projection is read at the ack coordinate"
                      (is (= [{:seon.db/query route-query
                               :seon.db/coordinate ack-coordinate}]
                             @!queries)))
                    (@!handler
                      {:seon.db.protocol/coordinate event-coordinate})
                    (next-turn)))
                (.then
                  (fn [_]
                    (testing "a later event reads and publishes its exact point"
                      (is (= event-coordinate
                             (:seon.db/coordinate (peek @!queries))))
                      (is (= 200
                             (::response-status
                               (request! "/temporary-route")))))
                    (router/detach!)))
                (.then
                  (fn [_]
                    (testing "detach uses the authority-returned interest key"
                      (is (= {:seon.db/key interest-key}
                             @!unlisten-request))))))))
        (settle! done)))))

(deftest stale-route-query-completion-cannot-replace-a-newer-projection
  (async done
    (let [ack-coordinate (database-coordinate 20)
          stale-coordinate (assoc ack-coordinate
                                  ::coordinate/commit-id (random-uuid)
                                  ::coordinate/t 21)
          current-coordinate (assoc ack-coordinate
                                    ::coordinate/commit-id (random-uuid)
                                    ::coordinate/t 22)
          stale-query (deferred)
          current-query (deferred)
          !handler (atom nil)]
      (->
        (with-route-db-fakes
          {::query
           (fn [{actual-coordinate :seon.db/coordinate}]
             (cond
               (= stale-coordinate actual-coordinate) (::promise stale-query)
               (= current-coordinate actual-coordinate) (::promise current-query)
               :else (js/Promise.resolve [])))
           ::listen
           (fn [request]
             (reset! !handler (:seon.db/handler request))
             (js/Promise.resolve {:seon.db/key "database-interest/routes-18"
                                  :seon.db/coordinate ack-coordinate}))
           ::unlisten
           (fn [_request]
             (js/Promise.resolve {:seon.db/ok? true}))}
          (fn []
            (router/install! {})
            (-> (router/attach!)
                (.then
                  (fn [_]
                    (@!handler
                      {:seon.db.protocol/coordinate stale-coordinate})
                    (@!handler
                      {:seon.db.protocol/coordinate current-coordinate})
                    ((::resolve! current-query)
                     [(route-row "/current-route")])
                    (next-turn)))
                (.then
                  (fn [_]
                    (testing "the newer completed projection is active"
                      (is (= 200
                             (::response-status
                               (request! "/current-route")))))
                    ((::resolve! stale-query)
                     [(route-row "/stale-route")])
                    (next-turn)))
                (.then
                  (fn [_]
                    (testing "late older work cannot regress the route cache"
                      (is (= 200
                             (::response-status
                               (request! "/current-route"))))
                      (is (= 302
                             (::response-status
                               (request! "/stale-route"))))))))))
        (settle! done)))))
