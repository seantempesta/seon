(ns seon.db.writer-query-admission-test
  "Integrated Datahike query-call admission tests."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.executor :as executor]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer])
  (:import [java.io File]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- socket-path [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory (str "seon-query-admission-" label "-"
                          (random-uuid) ".sock")))))

(defn- dependencies []
  {::writer/database-initializer (fn [_ _] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_] [])
   ::writer/embedding-inputs-for-eids (fn [_ _] [])
   ::writer/embedding-assertions (fn [_] [])
   ::writer/revalidate-embedding-assertions (fn [_ _] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_ _ _ _] [])})

(defn- request!
  [runtime transport request]
  (let [response (promise)]
    (writer/handle-request! runtime transport request #(deliver response %))
    response))

(defn- wait-until!
  [pred message]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (cond
        (pred) true
        (< deadline (System/nanoTime)) (throw (ex-info message {}))
        :else (do (Thread/yield) (recur))))))

(defn- ensure-database-value!
  [runtime _transport database-name]
  (let [ensure-response
        (writer/handle-request
         runtime
         (protocol/ensure-database-request
          {::protocol/request-id (str database-name "/ensure")
           ::protocol/database-name database-name
           ::protocol/backend :memory}))]
    (is (::protocol/success? ensure-response))
    (:seon.db/db ensure-response)))

(defn- query-request
  [_database-name database request-id]
  (protocol/query-request
   {::protocol/request-id request-id
    :seon.db/db database
    ::protocol/query-form '[:find ?ident :where [?entity :db/ident ?ident]]
    ::protocol/arguments []}))

(deftest joined-query-calls-consume-one-physical-job-and-do-not-gate-another-db
  (let [database-a (str "query-admission-a-" (random-uuid))
        database-b (str "query-admission-b-" (random-uuid))
        request-path (socket-path "request")
        server (writer/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-a
                               ::writer/backend :memory
                               ::writer/selected-processors 4
                               ::writer/request-socket-path request-path})
        runtime (::writer/runtime server)
        transport (#'writer/transport-connection
                   {::uds/close! (fn [] nil) ::uds/send! (fn [_] nil)})
        head-a (ensure-database-value! runtime transport database-a)
        head-b (ensure-database-value! runtime transport database-b)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        run-count (atom 0)
        original-run d/run-q!]
    (try
      (with-redefs [d/run-q!
                    (fn [call]
                      (when (= 1 (swap! run-count inc))
                        (.countDown entered)
                        (when-not (.await release 5 TimeUnit/SECONDS)
                          (throw (ex-info "blocked owner was not released" {}))))
                      (original-run call))]
        (let [owner (request! runtime transport
                              (query-request database-a head-a "a/owner"))]
          (is (.await entered 2 TimeUnit/SECONDS))
          (let [waiters
                (mapv
                 (fn [index]
                   (let [response
                         (request! runtime transport
                                   (query-request database-a head-a
                                                  (str "a/waiter-" index)))]
                     (wait-until!
                      #(= (+ 2 index)
                          (:datahike.single-flight/active-callers
                           (d/query-cache-evidence)))
                      "joined query caller did not acquire")
                     response))
                 (range 32))
                other (request! runtime transport
                                (query-request database-b head-b "b/query"))
                other-response (deref other 2000 ::timeout)]
            (wait-until!
             #(let [evidence (executor/evidence (::writer/executor server))]
                (and (= 1 (get-in evidence
                                  [::executor/running-by-class :read]))
                     (zero? (::executor/queued evidence))))
             "joined acquisition jobs did not yield their workers")
            (is (not= ::timeout other-response)
                "database B finishes while database A's owner is blocked")
            (is (::protocol/success? other-response))
            (.countDown release)
            (let [responses (mapv #(deref % 5000 ::timeout)
                                  (into [owner] waiters))
                  outcomes (map #(get-in % [:datahike.query/cache-evidence
                                             :datahike.cache/outcome])
                                responses)]
              (is (every? ::protocol/success? responses))
              (is (= 1 (count (filter #{:datahike.cache.outcome/miss-owner}
                                      outcomes))))
              (is (= 32 (count (filter #{:datahike.cache.outcome/miss-joined}
                                       outcomes))))
              (is (= 2 @run-count))
              (wait-until!
               #(and (empty? @(::writer/active-requests runtime))
                     (zero? (::executor/retained-identities
                             (executor/evidence (::writer/executor server))))
                     (zero? (:datahike.single-flight/active-callers
                             (d/query-cache-evidence))))
               "query owners or callers remained after completion")))))
      (finally
        (.countDown release)
        (#'writer/close-transport-connection! runtime transport)
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest execute-many-query-members-share-one-physical-owner
  (let [database-a (str "query-many-a-" (random-uuid))
        database-b (str "query-many-b-" (random-uuid))
        request-path (socket-path "many-request")
        server (writer/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-a
                               ::writer/backend :memory
                               ::writer/selected-processors 4
                               ::writer/request-socket-path request-path})
        runtime (::writer/runtime server)
        read-window
        (get-in (executor/evidence (::writer/executor server))
                [::executor/capacity ::executor/classes :read
                 ::executor/maximum-queued-by-database])
        transport (#'writer/transport-connection
                   {::uds/close! (fn [] nil) ::uds/send! (fn [_] nil)})
        head-a (ensure-database-value! runtime transport database-a)
        head-b (ensure-database-value! runtime transport database-b)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        run-count (atom 0)
        original-run d/run-q!
        query-member {::protocol/operation protocol/query-operation
                      :seon.db/db head-a
                      ::protocol/query-form
                      '[:find ?ident :where [?entity :db/ident ?ident]]
                      ::protocol/arguments []}]
    (try
      (with-redefs [d/run-q!
                    (fn [call]
                      (when (= 1 (swap! run-count inc))
                        (.countDown entered)
                        (when-not (.await release 5 TimeUnit/SECONDS)
                          (throw (ex-info "execute-many owner was not released" {}))))
                      (original-run call))]
        (let [many
              (request!
               runtime transport
               (protocol/execute-many-request
                {::protocol/request-id "many/query-members"
                 ::protocol/members (vec (repeat 33 query-member))}))]
          (is (.await entered 2 TimeUnit/SECONDS))
          (wait-until!
           #(= read-window (:datahike.single-flight/active-callers
                             (d/query-cache-evidence)))
           "execute-many did not fill its bounded admitted-member window")
          (wait-until!
           #(let [evidence (executor/evidence (::writer/executor server))]
              (and (= 1 (get-in evidence [::executor/running-by-class :read]))
                   (zero? (::executor/queued evidence))))
           "execute-many acquisition jobs did not yield their workers")
          (let [evidence (executor/evidence (::writer/executor server))
                other (request! runtime transport
                                (query-request database-b head-b "many/b-query"))]
            (is (= 1 (get-in evidence [::executor/running-by-class :read])))
            (is (zero? (::executor/queued evidence)))
            (is (::protocol/success? (deref other 2000 {})))
            (.countDown release)
            (let [response (deref many 5000 ::timeout)
                  results (::protocol/results response)
                  outcomes (map #(get-in % [:datahike.query/cache-evidence
                                             :datahike.cache/outcome]) results)]
              (is (not= ::timeout response))
              (is (::protocol/success? response))
              (is (= 33 (count results)))
              (is (every? ::protocol/success? results))
              (is (= 1 (count (filter #{:datahike.cache.outcome/miss-owner}
                                      outcomes))))
              (is (<= (dec read-window)
                      (count (filter #{:datahike.cache.outcome/miss-joined}
                                     outcomes))))
              (is (= 32
                     (count
                      (filter #{:datahike.cache.outcome/miss-joined
                                :datahike.cache.outcome/hit}
                              outcomes))))
              (is (= 2 @run-count)
                  "database A shares one physical query while database B runs independently")
              (wait-until!
               #(and (empty? @(::writer/active-requests runtime))
                     (zero? (::executor/retained-identities
                             (executor/evidence (::writer/executor server))))
                     (zero? (:datahike.single-flight/active-callers
                             (d/query-cache-evidence))))
               "execute-many retained logical or physical query ownership")))))
      (finally
        (.countDown release)
        (#'writer/close-transport-connection! runtime transport)
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest execute-many-result-limit-bounds-a-slow-position-gap
  (let [database-name (str "many-result-limit-" (random-uuid))
        request-path (socket-path "mlr")
        server (writer/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-name
                               ::writer/backend :memory
                               ::writer/selected-processors 3
                               ::writer/request-socket-path request-path})
        runtime (::writer/runtime server)
        read-window
        (get-in (executor/evidence (::writer/executor server))
                [::executor/capacity ::executor/classes :read
                 ::executor/maximum-queued-by-database])
        transport (#'writer/transport-connection
                   {::uds/close! (fn [] nil) ::uds/send! (fn [_] nil)})
        head (ensure-database-value! runtime transport database-name)
        slow-entered (CountDownLatch. 1)
        large-completed (CountDownLatch. 1)
        release-slow (CountDownLatch. 1)
        calls (atom [])
        members
        (mapv (fn [position]
                (cond->
                 {::protocol/operation protocol/pull-operation
                  :seon.db/db head
                  ::protocol/selector [:db/id]
                  ::protocol/entity-id position}
                  (= position 1)
                  (assoc :datahike.resource/max-result-weight
                         protocol/maximum-frame-bytes)))
              (range 16))
        request
        (protocol/execute-many-request
         {::protocol/request-id "many/result-limit"
          ::protocol/members members})
        initial (#'writer/execute-many-result-state request)
        placeholder-weight (::writer/result-placeholder-weight initial)
        small-value {:position 0 :payload "small"}
        small-result
        (protocol/success
         {::protocol/result small-value
          :datahike.read/dependency-plan :all})
        small-weight
        (d/shallow-weight-within small-result protocol/maximum-frame-bytes)
        max-result-weight
        (max (::writer/result-weight initial)
             (+ (- (::writer/result-weight initial) placeholder-weight)
                small-weight 8))
        request
        (assoc request :datahike.resource/max-result-weight max-result-weight)
        original-pull d/pull-with-evidence]
    (try
      (with-redefs [d/pull-with-evidence
                    (fn [_db-value options]
                      (let [position (:eid options)]
                        (swap! calls conj position)
                        (case position
                          0 (do
                              (.countDown slow-entered)
                              (when-not (.await release-slow 5 TimeUnit/SECONDS)
                                (throw (ex-info "slow member was not released" {})))
                              {:datahike.pull/result small-value
                               :datahike.read/dependency-plan :all})
                          1 (let [value
                                  {:position position
                                   :payload (apply str (repeat 2048 "x"))}]
                              (.countDown large-completed)
                              {:datahike.pull/result value
                               :datahike.read/dependency-plan :all})
                          (original-pull _db-value options))))]
        (let [response (request! runtime transport request)]
          (is (.await slow-entered 2 TimeUnit/SECONDS))
          (is (.await large-completed 2 TimeUnit/SECONDS))
          (wait-until!
           #(let [entry (get @(::writer/active-requests runtime)
                             "many/result-limit")]
              (and (= read-window (::writer/next-position entry))
                   (some? (get-in entry [::writer/results 1]))))
           "the bounded admitted suffix did not settle behind position zero")
          (is (every? #(< % read-window) @calls))
          (.countDown release-slow)
          (let [response (deref response 5000 ::timeout)
                results (::protocol/results response)]
            (is (not= ::timeout response))
            (is (::protocol/success? response))
            (is (= small-result (first results)))
            (is (every? #{(var-get #'writer/execute-many-result-limit)}
                        (subvec results 1)))
            (is (every? #(< % read-window) @calls))
            (wait-until!
             #(and (empty? @(::writer/active-requests runtime))
                   (zero? (::executor/retained-identities
                           (executor/evidence (::writer/executor server)))))
             "result-limited grouped read retained authority work"))))
      (finally
        (.countDown release-slow)
        (#'writer/close-transport-connection! runtime transport)
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest execute-many-members-inherit-and-enforce-the-outer-result-limit
  (let [request-id "many/member-bound"
        owner (Object.)
        member-limit 32
        request
        {::protocol/request-id request-id
         ::protocol/operation protocol/pull-operation
         ::protocol/selector [:db/id]
         ::protocol/entity-id 1
         :datahike.resource/max-result-weight member-limit}
        oversized-success
        (protocol/success
         {::protocol/result {:payload (apply str (repeat 256 "x"))}})
        oversized-failure
        (#'writer/member-failure
         (ex-info (apply str (repeat 256 "x")) {}))]
    (is (= (var-get #'writer/execute-many-result-limit)
           (#'writer/bounded-execute-many-member-result
            request oversized-success)))
    (is (= (var-get #'writer/execute-many-result-limit)
           (#'writer/bounded-execute-many-member-result
            request oversized-failure)))
    (let [active (atom {request-id {::writer/owner owner
                                    ::writer/jobs #{[request-id 0]}
                                    ::writer/result-limit-position 0}})
          submitted (atom [])
          runtime {::writer/active-requests active
                   ::writer/executor ::executor}]
      (with-redefs [executor/try-submit! #(swap! submitted conj %)]
        (#'writer/submit-execute-many-members!
         runtime request-id owner
         [{::executor/job-id [request-id 0]}]))
      (is (empty? @submitted))
      (is (empty? (get-in @active [request-id ::writer/jobs]))))
    (let [active (atom {request-id {::writer/owner owner
                                    ::writer/jobs #{[request-id 0]}}})
          submitted (atom [])
          canceled (atom [])
          runtime {::writer/active-requests active
                   ::writer/executor ::executor}]
      (with-redefs [executor/try-submit!
                    (fn [submission]
                      (swap! submitted conj submission)
                      (swap! active assoc-in
                             [request-id ::writer/result-limit-position] 0))
                    executor/cancel! #(swap! canceled conj %)]
        (#'writer/submit-execute-many-members!
         runtime request-id owner
         [{::executor/job-id [request-id 0]}]))
      (is (= 1 (count @submitted)))
      (is (= [[request-id 0]] (mapv ::executor/job-id @canceled))))))

(deftest final-unstarted-cancel-removes-the-exact-owner-job
  (let [database-name (str "query-cancel-" (random-uuid))
        request-path (socket-path "cancel-request")
        server (writer/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-name
                               ::writer/backend :memory
                               ::writer/selected-processors 4
                               ::writer/request-socket-path request-path})
        runtime (::writer/runtime server)
        transport (#'writer/transport-connection
                   {::uds/close! (fn [] nil) ::uds/send! (fn [_] nil)})
        head (ensure-database-value! runtime transport database-name)
        guard-entered (CountDownLatch. 1)
        release-guard (CountDownLatch. 1)
        original-guard @#'writer/continue-query-job?]
    (try
      (with-redefs-fn
        {#'writer/continue-query-job?
         (fn [runtime owner-request-id job-id]
           (.countDown guard-entered)
           (.await release-guard 5 TimeUnit/SECONDS)
           (original-guard runtime owner-request-id job-id))}
        (fn []
          (let [owner (request! runtime transport
                                (query-request database-name head "cancel/owner"))]
            (is (.await guard-entered 2 TimeUnit/SECONDS))
            (let [cancel
                  (future
                    @(request!
                      runtime transport
                      (protocol/cancel-request
                       {::protocol/request-id "cancel/request"
                        ::protocol/target-request-id "cancel/owner"})))]
              (wait-until!
               #(zero? (:datahike.single-flight/active-callers
                         (d/query-cache-evidence)))
               "final cancellation did not remove the unstarted Datahike call")
              (.countDown release-guard)
              (let [cancel-response (deref cancel 2000 ::timeout)
                    owner-response (deref owner 2000 ::timeout)]
                (is (not= ::timeout cancel-response))
                (is (::protocol/canceled? cancel-response))
                (is (false? (::protocol/running? cancel-response)))
                (is (not (::protocol/success? owner-response)))
                (wait-until!
                 #(and (empty? @(::writer/active-requests runtime))
                       (empty? (get @(::writer/query-jobs runtime) ::writer/by-owner))
                       (zero? (::executor/retained-identities
                               (executor/evidence (::writer/executor server)))))
                 "unstarted cancellation retained an owner or executor job"))))))
      (finally
        (.countDown release-guard)
        (#'writer/close-transport-connection! runtime transport)
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest detached-owner-retains-its-database-until-joined-computation-finishes
  (let [database-name (str "query-owner-release-" (random-uuid))
        request-path (socket-path "rel-req")
        server (writer/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-name
                               ::writer/backend :memory
                               ::writer/selected-processors 4
                               ::writer/request-socket-path request-path})
        runtime (::writer/runtime server)
        transport (#'writer/transport-connection
                   {::uds/close! (fn [] nil) ::uds/send! (fn [_] nil)})
        head (ensure-database-value! runtime transport database-name)
        connection
        (::registry/conn
         (registry/resolve-connection
          {::registry/database-name (keyword database-name)}))
        _ (d/transact connection
                      [{:db/ident :query-owner/advanced
                        :db/valueType :db.type/boolean
                        :db/cardinality :db.cardinality/one}])
        entered (CountDownLatch. 1)
        release-run (CountDownLatch. 1)
        releases (atom [])
        original-run d/run-q!
        original-release d/release-materialized-db]
    (try
      (with-redefs [d/run-q!
                    (fn [call]
                      (.countDown entered)
                      (when-not (.await release-run 5 TimeUnit/SECONDS)
                        (throw (ex-info "query owner was not released" {})))
                      (original-run call))
                    d/release-materialized-db
                    (fn [db-value]
                      (swap! releases conj db-value)
                      (original-release db-value))]
        (let [owner (request! runtime transport
                              (query-request database-name head "release/owner"))]
          (is (.await entered 2 TimeUnit/SECONDS))
          (let [waiter (request! runtime transport
                                 (query-request database-name head
                                                "release/waiter"))]
            (wait-until!
             #(= 2 (:datahike.single-flight/active-callers
                     (d/query-cache-evidence)))
             "waiter did not join the blocked owner")
            (let [cancel
                  @(request!
                    runtime transport
                    (protocol/cancel-request
                     {::protocol/request-id "release/cancel-owner"
                      ::protocol/target-request-id "release/owner"}))
                  owner-response (deref owner 2000 ::timeout)]
              (is (::protocol/canceled? cancel))
              (is (not= ::timeout owner-response))
              (is (not (::protocol/success? owner-response)))
              (is (empty? @releases)
                  "logical owner cancellation cannot release physical query data")
              (.countDown release-run)
              (is (::protocol/success? (deref waiter 5000 {})))
              (wait-until!
               #(= 2 (count @releases))
               "owner and waiter databases were not released exactly once")
              (wait-until!
               #(and (empty? @(::writer/active-requests runtime))
                     (empty? (get @(::writer/query-jobs runtime)
                                  ::writer/by-owner))
                     (zero? (::executor/retained-identities
                             (executor/evidence (::writer/executor server))))
                     (zero? (:datahike.single-flight/active-callers
                             (d/query-cache-evidence))))
               "detached owner retained physical or logical state")
              (with-redefs [d/release-materialized-db
                            (fn [_]
                              (throw (ex-info "injected release failure" {})))]
                (is (::protocol/success?
                     (deref
                      (request! runtime transport
                                (query-request database-name head
                                               "release/failure-does-not-wedge"))
                      2000 {}))))))))
      (finally
        (.countDown release-run)
        (#'writer/close-transport-connection! runtime transport)
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))
