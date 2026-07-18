(ns seon.web.router-test
  "Behavioral tests for database-derived reitit cache invalidation."
  (:require
    [cljs.test :refer [async deftest is testing use-fixtures]]
    [seon.agent.message]
    [seon.db :as db]
    [seon.route]
    [seon.runtime.admission :as admission]
    [seon.test.async :refer [settle!]]
    [seon.web.router :as router]))

(def ^:private prior-router-state (atom nil))

(use-fixtures
  :each
  {:before (fn []
             (reset! prior-router-state (deref @#'router/!router-state)))
   :after (fn []
            (reset! @#'router/!router-state @prior-router-state))})

(defn temporary-handler!
  "Return the temporary route's observable response."
  [_request]
  (js/Response. "temporary route" #js {:status 200}))


(def ^:private route-query
  '[:find [(pull ?route [:seon.route/pattern
                         :seon.route/method
                         :seon.route/handler
                         :seon.route/middleware]) ...]
    :where [?route :seon.route/pattern]])

(defn- database-value
  "One complete immutable database value for route tests."
  [transaction]
  {:db-name "default"
   :t transaction
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id (random-uuid)})

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
  [{::keys [query listen unlisten current-db]} body]
  (let [original-query db/query
        original-db db/db
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
    (set! db/db
          (fn
            ([] (current-db))
            ([_request] (current-db))))
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
            (set! db/db original-db)
            (set! db/listen! original-listen)
            (set! db/unlisten! original-unlisten)
            (reset! state-atom prior-state))))))

(defn- request!
  "Dispatch one synthetic WHATWG Request and return its Response."
  ([path] (request! "GET" path))
  ([method path]
   (router/handle-request
    (js/Request. (str "http://127.0.0.1" path) #js {:method method}) nil)))

(deftest operator-config-route-reaches-the-injected-live-operation
  (router/install!
    {:seon.web.router/config-apply
     (fn [_request _response]
       (js/Response. "{:seon.state/ok? true}" #js {:status 200}))
     :seon.web.router/same-origin? (constantly true)})
  (let [response (request! "POST" "/_seon/operator/config")]
    (is (= 200 (.-status response)))))

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
        handler (fn [_request _response]
                  (swap! !invocations inc)
                  (js/Response. "{:seon.runtime/quiesced? true}" #js {:status 200}))]
    (router/install!
      {:seon.web.router/operator-quiesce handler
       :seon.web.router/loopback-peer? (constantly false)})
    (let [response (request! "POST" "/_seon/operator/quiesce")]
      (is (= 403 (.-status response)))
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
          (is (= 200 (.-status response)))
          (is (= 1 @!invocations)
              "lifecycle work bypasses ordinary admission only for loopback"))
        (finally
          (reset! @#'admission/!state prior))))))

(deftest retained-blob-route-is-unadmitted-and-loopback-only
  (let [!invocations (atom 0)
        handler (fn [_request _response]
                  (swap! !invocations inc)
                  (js/Response. "{:my.blob/ok? true}" #js {:status 200}))]
    (router/install!
      {:seon.web.router/operator-blobs handler
       :seon.web.router/loopback-peer? (constantly false)})
    (is (= 403 (.-status (request! "POST" "/_seon/operator/blobs"))))
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
          (is (= 200 (.-status response)))
          (is (= 1 @!invocations)))
        (finally
          (reset! @#'admission/!state prior))))))

(deftest closed-admission-refuses-post-before-the-domain-handler
  (let [!invocations (atom 0)
        prior (admission/state)]
    (try
      (router/install!
        {:seon.web.router/config-apply
         (fn [_request _response]
           (swap! !invocations inc)
           (js/Response. "{:seon.state/ok? true}" #js {:status 200}))
         :seon.web.router/same-origin? (constantly true)})
      (reset! @#'admission/!state
              {::admission/status :unavailable
               ::admission/reason "injected publication failure"})
      (let [response (request! "POST" "/_seon/operator/config")]
        (is (= 503 (.-status response)))
        (is (zero? @!invocations)
            "closed admission reaches neither parsing nor domain work"))
      (finally
        (reset! @#'admission/!state prior)))))

(deftest route-interest-queries-the-acknowledged-and-later-database-values
  (async done
    (let [ack-db (database-value 10)
          event-db (database-value 11)
          interest-key ::routes
          !current-db (atom ack-db)
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
            (if (= event-db (::db/db request))
              [(route-row "/temporary-route")]
              [])))
         ::current-db #(js/Promise.resolve @!current-db)
         ::listen
         (fn [request]
           (reset! !listen-request request)
           (reset! !handler (:seon.db/handler request))
           (js/Promise.resolve interest-key))
         ::unlisten
         (fn [key]
           (reset! !unlisten-request key)
           (js/Promise.resolve {:seon.db/ok? true}))}
        (fn []
          (router/install! {})
          (-> (router/attach!)
              (.then
               (fn [key]
                 (is (= interest-key key))
                 (is (= route-query (:seon.db/query @!listen-request)))
                 (is (= [{:seon.db/query route-query ::db/db ack-db}]
                        @!queries))
                 (reset! !current-db event-db)
                 (@!handler {:db-after event-db :tx-data []})
                 (next-turn)))
              (.then
               (fn [_]
                 (is (= event-db (::db/db (peek @!queries))))
                 (is (= 200 (.-status (request! "/temporary-route"))))
                 (router/detach!)))
              (.then
               (fn [_]
                 (is (= interest-key @!unlisten-request)))))))
       (settle! done)))))

(deftest stale-route-query-completion-cannot-replace-a-newer-projection
  (async done
    (let [ack-db (database-value 20)
          stale-db (database-value 21)
          current-db (database-value 22)
          stale-query (deferred)
          current-query (deferred)
          !handler (atom nil)]
      (->
       (with-route-db-fakes
        {::query
         (fn [{database ::db/db}]
           (cond
             (= stale-db database) (::promise stale-query)
             (= current-db database) (::promise current-query)
             :else (js/Promise.resolve [])))
         ::current-db #(js/Promise.resolve ack-db)
         ::listen
         (fn [request]
           (reset! !handler (:seon.db/handler request))
           (js/Promise.resolve ::routes))
         ::unlisten
         (fn [_key] (js/Promise.resolve {:seon.db/ok? true}))}
        (fn []
          (router/install! {})
          (-> (router/attach!)
              (.then
               (fn [_]
                 (@!handler {:db-after stale-db :tx-data []})
                 (@!handler {:db-after current-db :tx-data []})
                 ((::resolve! current-query) [(route-row "/current-route")])
                 (next-turn)))
              (.then
               (fn [_]
                 (is (= 200 (.-status (request! "/current-route"))))
                 ((::resolve! stale-query) [(route-row "/stale-route")])
                 (next-turn)))
              (.then
               (fn [_]
                 (is (= 200 (.-status (request! "/current-route"))))
                 (is (= 302 (.-status (request! "/stale-route")))))))))
       (settle! done)))))

(deftest detached-route-query-completion-cannot-publish
  (async done
    (let [ack-db (database-value 30)
          later-db (database-value 31)
          later-query (deferred)
          !handler (atom nil)]
      (->
       (with-route-db-fakes
        {::query (fn [{database ::db/db}]
                   (if (= later-db database)
                     (::promise later-query)
                     (js/Promise.resolve [])))
         ::current-db #(js/Promise.resolve ack-db)
         ::listen (fn [request]
                    (reset! !handler (:seon.db/handler request))
                    (js/Promise.resolve ::routes))
         ::unlisten (fn [_key]
                      (js/Promise.resolve {:seon.db/ok? true}))}
        (fn []
          (router/install! {})
          (-> (router/attach!)
              (.then
               (fn [_]
                 (@!handler {:db-after later-db :tx-data []})
                 (router/detach!)))
              (.then
               (fn [_]
                 ((::resolve! later-query) [(route-row "/detached-route")])
                 (next-turn)))
              (.then
               (fn [_]
                 (is (= 302 (.-status (request! "/detached-route")))))))))
       (settle! done)))))
