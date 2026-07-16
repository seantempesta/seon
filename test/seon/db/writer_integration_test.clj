(ns seon.db.writer-integration-test
  "End-to-end canonical writer request and publication tests."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.backend :as backend]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]
            [seon.embed :as embed]
            [seon.schema :as schema])
  (:import [java.io File]
           [java.nio.channels Channels SocketChannel]))

(defn- socket-path
  [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory
            (str "seon-writer-" label "-" (random-uuid) ".sock")))))

(defn- dependencies
  ([]
   (dependencies (fn [_connection _database-name] nil)))
  ([database-initializer]
   {::writer/database-initializer database-initializer
    ::writer/embedding-enabled? false
    ::writer/embedding-entity-ids (fn [_db-value] [])
    ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
    ::writer/embedding-assertions (fn [_inputs] [])
    ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
    ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
    ::writer/knn (fn [_db-value _vector _k _eids] [])}))

(defn- wait-for-subscriber!
  [publisher]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond
        (seq @(::uds/subscribers publisher)) true
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "publisher did not accept its subscriber" {}))
        :else (do (Thread/sleep 10) (recur))))))

(defn- call!
  [channel request]
  (uds/call! {::uds/channel channel ::uds/message request}))

(defn- acquire!
  [channel database-name label]
  (let [head
        (call! channel
               (protocol/resolve-head-request
                {::protocol/request-id (str label "/head")
                 ::protocol/database-name database-name}))]
    (call! channel
           (protocol/acquire-database-request
            {::protocol/request-id (str label "/acquire")
             ::protocol/database-name database-name
             ::protocol/attachment (::protocol/attachment head)}))))

(defn- await-route!
  [database-name present?]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [present-now?
            (boolean
             (::registry/conn
              (registry/resolve-connection
               {::registry/database-name (keyword database-name)})))]
        (cond
          (= present? present-now?) true
          (> (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 10) (recur)))))))

(deftest capability-discovery-and-head-resolution-return-only-portable-data
  (let [database-name (str "writer-control-" (random-uuid))
        request-path (socket-path "control-request")
        publish-path (socket-path "control-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (let [capability-request
            (protocol/capabilities-request
             {::protocol/request-id "control/capabilities"})
            head-request
            (protocol/resolve-head-request
             {::protocol/request-id "control/head"
              ::protocol/database-name database-name})
            capabilities (call! request-channel capability-request)
            head (call! request-channel head-request)
            cancellation
            (call! request-channel
                   (protocol/cancel-request
                    {::protocol/request-id "control/cancel"
                     ::protocol/target-request-id "control/not-running"}))
            unknown
            (call! request-channel
                   (protocol/resolve-head-request
                    {::protocol/request-id "control/unknown"
                     ::protocol/database-name "not-open"}))]
        (is (every? protocol/valid-request?
                    [capability-request head-request]))
        (is (every? protocol/valid-response?
                    [capabilities head cancellation unknown]))
        (is (= "control/capabilities" (::protocol/request-id capabilities)))
        (is (= (assoc (d/capabilities)
                      ::protocol/version protocol/current-version)
               (::protocol/capabilities capabilities)))
        (is (= "control/head" (::protocol/request-id head)))
        (is (= database-name (::protocol/database-name head)))
        (is (= (coordinate/attachment (::protocol/coordinate head))
               (::protocol/attachment head)))
        (is (= {::protocol/success? true
                ::protocol/request-id "control/cancel"
                ::protocol/target-request-id "control/not-running"
                ::protocol/canceled? false
                ::protocol/running? false}
               cancellation))
        (is (= "control/unknown" (::protocol/request-id unknown)))
        (is (= protocol/not-found-error (::protocol/error-kind unknown)))
        (is (not-any? #(or (instance? clojure.lang.IDeref %)
                           (instance? Thread %)
                           (instance? Throwable %))
                      (tree-seq coll? seq [capabilities head unknown]))))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest physical-connections-own-exact-database-access-and-release
  (let [database-name (str "writer-acquisition-" (random-uuid))
        request-path (socket-path "acquisition-request")
        publish-path (socket-path "acquisition-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        ^SocketChannel a (uds/connect! request-path)
        ^SocketChannel b (uds/connect! request-path)]
    (try
      (let [head
            (call! a
                   (protocol/resolve-head-request
                    {::protocol/request-id "acquisition/head"
                     ::protocol/database-name database-name}))
            query-input
            {::protocol/database-name database-name
             ::protocol/attachment (::protocol/attachment head)
             ::protocol/coordinate (::protocol/coordinate head)
             ::protocol/query-form
             '[:find ?ident :where [?entity :db/ident ?ident]]
             ::protocol/arguments []}
            denied-read
            (call! a
                   (protocol/query-request
                    (assoc query-input
                           ::protocol/request-id "acquisition/denied-read")))
            denied-write
            (call! a
                   (protocol/transaction-request
                    {::protocol/request-id "acquisition/denied-write"
                     ::protocol/database-name database-name
                     ::protocol/transaction-data
                     [{:db/ident :acquisition/value
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}]}))
            acquired-a (acquire! a database-name "acquisition/a")
            duplicate-a (acquire! a database-name "acquisition/a-duplicate")
            write
            (call! a
                   (protocol/transaction-request
                    {::protocol/request-id "acquisition/write"
                     ::protocol/database-name database-name
                     ::protocol/transaction-data
                     [{:db/ident :acquisition/value
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}
                      {:acquisition/value "owned"}]}))
            current
            (assoc query-input ::protocol/coordinate (::protocol/coordinate write))
            denied-b
            (call! b
                   (protocol/query-request
                    (assoc current
                           ::protocol/request-id "acquisition/denied-b")))
            acquired-b (acquire! b database-name "acquisition/b")]
        (is (= protocol/not-found-error (::protocol/error-kind denied-read)))
        (is (= protocol/not-found-error (::protocol/error-kind denied-write)))
        (is (true? (::protocol/acquired? acquired-a)))
        (is (false? (::protocol/acquired? duplicate-a)))
        (is (= protocol/not-found-error (::protocol/error-kind denied-b)))
        (is (true? (::protocol/acquired? acquired-b)))
        (.close a)
        (is (await-route! database-name true)
            "a sibling physical connection retains the shared Datahike owner")
        (let [read-b
              (call! b
                     (protocol/query-request
                      (assoc current ::protocol/request-id "acquisition/read-b")))]
          (is (::protocol/success? read-b))
          (is (some #{[:acquisition/value]}
                    (:datahike.query/result read-b))))
        (.close b)
        (is (await-route! database-name false)
            "the last physical connection releases the shared indexes"))
      (finally
        (try (.close a) (catch Throwable _))
        (try (.close b) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest coordinate-pinned-reads-share-old-commit-and-preserve-datahike-shapes
  (let [database-name (str "writer-read-" (random-uuid))
        request-path (socket-path "read-request")
        publish-path (socket-path "read-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/selected-processors 2
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (acquire! request-channel database-name "read/session")
      (call! request-channel
             (protocol/transaction-request
              {::protocol/database-name database-name
               ::protocol/request-id "read/schema-and-data"
               ::protocol/transaction-data
               [{:db/ident :reader/id
                 :db/valueType :db.type/string
                 :db/cardinality :db.cardinality/one
                 :db/unique :db.unique/identity}
                {:db/ident :reader/score
                 :db/valueType :db.type/long
                 :db/cardinality :db.cardinality/one}
                {:reader/id "alice" :reader/score 1}
                {:reader/id "bob" :reader/score 2}]}))
      (let [head
            (call! request-channel
                   (protocol/resolve-head-request
                    {::protocol/request-id "read/head"
                     ::protocol/database-name database-name}))
            attachment (::protocol/attachment head)
            frozen (::protocol/coordinate head)
            _
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "read/advance"
                     ::protocol/transaction-data
                     [[:db/add [:reader/id "alice"] :reader/score 9]]}))
            query-input
            {::protocol/database-name database-name
             ::protocol/attachment attachment
             ::protocol/coordinate frozen
             ::protocol/query-form
             [:find '?id '?score
              :keys 'id 'score
              :where ['?e :reader/id '?id]
              ['?e :reader/score '?score]]
             ::protocol/arguments []}
            owner
            (call! request-channel
                   (protocol/query-request
                    (assoc query-input ::protocol/request-id "read/query-1")))
            hit
            (call! request-channel
                   (protocol/query-request
                    (assoc query-input ::protocol/request-id "read/query-2")))
            pull
            (call! request-channel
                   (protocol/pull-request
                    {::protocol/request-id "read/pull"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/selector [:reader/id :reader/score]
                     ::protocol/entity-id [:reader/id "alice"]}))
            pulls
            (call! request-channel
                   (protocol/pull-many-request
                    {::protocol/request-id "read/pull-many"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/selector [:reader/id :reader/score]
                     ::protocol/entity-ids [[:reader/id "alice"]
                                            [:reader/id "bob"]]}))
            many
            (call! request-channel
                   (protocol/execute-many-request
                    {::protocol/request-id "read/many"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/members
                     [{::protocol/operation protocol/pull-operation
                       ::protocol/selector [:reader/id :reader/score]
                       ::protocol/entity-id [:reader/id "bob"]}
                      {::protocol/operation protocol/query-operation
                       ::protocol/query-form (::protocol/query-form query-input)
                       ::protocol/arguments []}
                      {::protocol/operation protocol/pull-many-operation
                       ::protocol/selector [:reader/id]
                       ::protocol/entity-ids [[:reader/id "alice"]
                                              [:reader/id "bob"]]}]}))
            large-many
            (call! request-channel
                   (protocol/execute-many-request
                    {::protocol/request-id "read/many-64"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/members
                     (vec
                      (repeat 64
                              {::protocol/operation protocol/pull-operation
                               ::protocol/selector [:reader/id]
                               ::protocol/entity-id [:reader/id "alice"]}))}))]
        (is (every? protocol/valid-response?
                    [owner hit pull pulls many large-many]))
        (is (= #{{:id "alice" :score 1} {:id "bob" :score 2}}
               (set (:datahike.query/result owner))
               (set (:datahike.query/result hit))))
        (is (= :datahike.cache.outcome/miss-owner
               (get-in owner [:datahike.query/cache-evidence
                              :datahike.cache/outcome])))
        (is (= :datahike.cache.outcome/hit
               (get-in hit [:datahike.query/cache-evidence
                            :datahike.cache/outcome])))
        (is (= {:reader/id "alice" :reader/score 1}
               (::protocol/result pull)))
        (is (= [{:reader/id "alice" :reader/score 1}
                {:reader/id "bob" :reader/score 2}]
               (::protocol/result pulls)))
        (is (= {:reader/id "bob" :reader/score 2}
               (get-in many [::protocol/results 0 ::protocol/result])))
        (is (= #{"alice" "bob"}
               (set (map :id
                         (get-in many [::protocol/results 1
                                       :datahike.query/result])))))
        (is (= [{:reader/id "alice"} {:reader/id "bob"}]
               (get-in many [::protocol/results 2 ::protocol/result])))
        (is (= 64 (count (::protocol/results large-many))))
        (is (every? ::protocol/success? (::protocol/results large-many)))
        (is (= frozen (::protocol/coordinate owner)
               (::protocol/coordinate hit)
               (::protocol/coordinate pull)
               (::protocol/coordinate pulls)
               (::protocol/coordinate many))))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest database-result-validation-rejects-host-owners-and-lazy-values
  (is (thrown? clojure.lang.ExceptionInfo
               (#'writer/materialize-result (future :host))))
  (is (thrown? clojure.lang.ExceptionInfo
               (#'writer/materialize-result (map identity [1 2]))))
  (let [value [{:bare-key "preserved"}]]
    (is (identical? value (#'writer/materialize-result value))))
  (is (thrown?
       clojure.lang.ExceptionInfo
       (#'writer/validate-read-input!
        {::protocol/operation protocol/query-operation
         ::protocol/query-form [:find '?value :in '$ '?input
                                :where ['?entity :value '?value]]
         ::protocol/arguments [(future :host)]})))
  (is (= [:find '?value :where ['?entity :value '?value]]
         (::protocol/query-form
          (#'writer/validate-read-input!
           {::protocol/operation protocol/query-operation
            ::protocol/query-form
            [:find '?value :where ['?entity :value '?value]]
           ::protocol/arguments []})))))

(deftest semantic-search-transitions-from-provider-to-coordinate-pinned-knn
  (let [database-name (str "writer-knn-" (random-uuid))
        request-path (socket-path "knn-request")
        publish-path (socket-path "knn-publish")
        observed (atom {})
        deps (assoc (dependencies)
                    ::writer/query-vec
                    (fn [request]
                      (swap! observed assoc
                             :provider-thread (.getName (Thread/currentThread))
                             :query request)
                      {:seon.embed/vector [1.0 0.0]})
                    ::writer/knn
                    (fn [db-value vector k eids]
                      (swap! observed assoc
                             :knn-thread (.getName (Thread/currentThread))
                             :coordinate (coordinate/resolved db-value)
                             :vector vector :k k :eids eids)
                      [{:entity-id 42 :distance 0.25}]))
        server (writer/start!
                {::writer/dependencies deps
                 ::writer/database-name database-name
                 ::writer/backend :memory
                 ::writer/selected-processors 2
                 ::writer/request-socket-path request-path
                 ::writer/publish-socket-path publish-path})
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (acquire! request-channel database-name "knn/session")
      (let [frozen (::protocol/coordinate
                    (call! request-channel
                           (protocol/resolve-head-request
                            {::protocol/request-id "knn/head"
                             ::protocol/database-name database-name})))
            attachment (coordinate/attachment frozen)
            _ (call! request-channel
                     (protocol/transaction-request
                       {::protocol/database-name database-name
                       ::protocol/request-id "knn/advance"
                       ::protocol/transaction-data
                       [{:db/ident :knn/advanced :db/doc "advanced"}]}))
            response
            (call! request-channel
                   (protocol/knn-search-request
                    {::protocol/request-id "knn/search"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/query "nearest"
                     ::protocol/limit 3
                     ::protocol/entity-ids [42 99]}))]
        (is (protocol/valid-response? response))
        (is (= frozen (::protocol/coordinate response)
               (:coordinate @observed)))
        (is (= [{:seon.embed/eid 42 :seon.embed/distance 0.25}]
               (::protocol/hits response)))
        (is (= {:seon.embed/text "nearest"} (:query @observed)))
        (is (= [[1.0 0.0] 3 '(42 99)]
               [(:vector @observed) (:k @observed) (:eids @observed)]))
        (is (.startsWith ^String (:knn-thread @observed) "seon-database-cpu-"))
        (is (not= (:provider-thread @observed) (:knn-thread @observed))))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest disconnect-waits-until-a-running-read-relinquishes-the-generation
  (let [database-name (str "writer-read-release-" (random-uuid))
        request-path (socket-path "read-release-request")
        publish-path (socket-path "read-release-publish")
        entered (java.util.concurrent.CountDownLatch. 1)
        finish (java.util.concurrent.CountDownLatch. 1)
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        ^SocketChannel read-channel (uds/connect! request-path)
        ^SocketChannel control-channel (uds/connect! request-path)]
    (try
      (acquire! read-channel database-name "read-release/session")
      (let [head
            (call! control-channel
                   (protocol/resolve-head-request
                    {::protocol/request-id "read-release/head"
                     ::protocol/database-name database-name}))
            attachment (::protocol/attachment head)
            coordinate (::protocol/coordinate head)]
        (with-redefs [d/pull
                      (fn [_db-value _request]
                        (.countDown entered)
                        (.await finish)
                        {:read/value :finished})]
          (let [read-result
                (future
                  (try
                    (call! read-channel
                           (protocol/pull-request
                            {::protocol/request-id "read-release/pull"
                             ::protocol/database-name database-name
                             ::protocol/attachment attachment
                             ::protocol/coordinate coordinate
                             ::protocol/selector [:read/value]
                             ::protocol/entity-id 1}))
                    (catch Throwable _ :connection-closed)))]
            (is (.await entered 5 java.util.concurrent.TimeUnit/SECONDS))
            (let [foreign-cancel
                  (call! control-channel
                         (protocol/cancel-request
                          {::protocol/request-id "read-release/foreign-cancel"
                           ::protocol/target-request-id "read-release/pull"}))]
              (is (false? (::protocol/canceled? foreign-cancel)))
              (is (false? (::protocol/running? foreign-cancel)))
              (is (not (realized? read-result))
                  "one socket cannot observe or cancel another socket's work"))
            (.close read-channel)
            (Thread/sleep 100)
            (is (await-route! database-name true)
                "Datahike resources remain open while a read owns them")
            (.countDown finish)
            (is (await-route! database-name false)
                "the final socket release removes the route after the read")
            @read-result)))
      (finally
        (.countDown finish)
        (try (.close read-channel) (catch Throwable _))
        (try (.close control-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest callback-requests-reject-active-duplicates-and-reuse-cleanly
  (let [database-name (str "writer-callback-reuse-" (random-uuid))
        request-path (socket-path "callback-reuse-request")
        publish-path (socket-path "callback-reuse-publish")
        entered (java.util.concurrent.CountDownLatch. 1)
        finish (java.util.concurrent.CountDownLatch. 1)
        calls (atom 0)
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        runtime (::writer/runtime server)
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (let [head
            (call! request-channel
                   (protocol/resolve-head-request
                    {::protocol/request-id "callback-reuse/head"
                     ::protocol/database-name database-name}))
            request
            (protocol/pull-request
             {::protocol/request-id "callback-reuse/read"
              ::protocol/database-name database-name
              ::protocol/attachment (::protocol/attachment head)
              ::protocol/coordinate (::protocol/coordinate head)
              ::protocol/selector [:callback/value]
              ::protocol/entity-id 1})
            first-response (promise)
            duplicate-response (promise)
            reused-response (promise)]
        (with-redefs [d/pull
                      (fn [_db-value _request]
                        (let [call (swap! calls inc)]
                          (when (= 1 call)
                            (.countDown entered)
                            (.await finish))
                          {:callback/value call}))]
          (writer/handle-request! runtime request #(deliver first-response %))
          (is (.await entered 5 java.util.concurrent.TimeUnit/SECONDS))
          (writer/handle-request! runtime request #(deliver duplicate-response %))
          (is (= protocol/request-conflict-error
                 (::protocol/error-kind
                  (deref duplicate-response 1000 ::not-delivered))))
          (is (= 1 @calls) "the duplicate never reaches Datahike")
          (.countDown finish)
          (is (= {:callback/value 1}
                 (::protocol/result (deref first-response 5000 ::not-delivered))))
          (writer/handle-request! runtime request #(deliver reused-response %))
          (is (= {:callback/value 2}
                 (::protocol/result (deref reused-response 5000 ::not-delivered))))
          (is (= 2 @calls))
          (is (empty? @(::writer/active-requests runtime)))))
      (finally
        (.countDown finish)
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest writer-stop-surfaces-release-failure-and-retains-database-identity
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-name (str "writer-release-failure-" (random-uuid))
        database-keyword (keyword database-name)
        request-path (socket-path "release-failure-request")
        publish-path (socket-path "release-failure-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        coordinate-before
        (::registry/coordinate
         (registry/resolve-connection
          {::registry/database-name database-keyword}))]
    (try
      (let [result
            (with-redefs [d/release
                          (fn [_]
                            (throw (ex-info "writer release failed" {})))]
              (writer/stop! server))
            failure (first (::writer/release-results result))
            retained
            (first
             (filter #(= database-keyword (::registry/database-name %))
                     (::registry/databases (registry/list-databases {}))))]
        (is (false? (::writer/stopped? result)))
        (is (schema/valid-candidate-value? ::writer/stop-response result))
        (is (= database-keyword (::registry/database-name failure)))
        (is (= (coordinate/attachment coordinate-before)
               (::registry/attachment failure)))
        (is (= coordinate-before (::registry/coordinate failure)))
        (is (false? (::registry/released? failure)))
        (is (re-find #"writer release failed"
                     (::registry/release-error failure)))
        (is (= (::registry/release-error failure)
               (::registry/release-error retained))
            "the failed database identity remains inspectable"))
      (finally
        (registry/restore-registry! {::registry/snapshot snapshot})
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest writer-stop-returns-the-pre-release-coordinate-and-outcome
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-name (str "writer-release-success-" (random-uuid))
        database-keyword (keyword database-name)
        request-path (socket-path "release-success-request")
        publish-path (socket-path "release-success-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        before
        (registry/resolve-connection
         {::registry/database-name database-keyword})]
    (try
      (let [result (writer/stop! server)
            release (first (::writer/release-results result))]
        (is (true? (::writer/stopped? result)))
        (is (schema/valid-candidate-value? ::writer/stop-response result))
        (is (= 1 (count (::writer/release-results result))))
        (is (= {::registry/database-name database-keyword
                ::registry/attachment (::registry/attachment before)
                ::registry/coordinate (::registry/coordinate before)
                ::registry/released? true}
               release))
        (is (nil? (::registry/conn
                   (registry/resolve-connection
                    {::registry/database-name database-keyword})))))
      (finally
        (registry/restore-registry! {::registry/snapshot snapshot})
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest existing-database-must-already-match-the-protocol-candidate
  (doseq [[label initial-tx]
          [["missing" nil]
           ["incompatible"
            [{:db/ident ::protocol/request-id
              :db/valueType :db.type/keyword
              :db/cardinality :db.cardinality/one
              :db/unique :db.unique/identity}]]]]
    (let [database-name (str "writer-protocol-candidate-" label "-"
                             (random-uuid))
          database-keyword (keyword database-name)
          config
          (backend/datahike-config
           (cond-> {::backend/database-name database-keyword
                    ::backend/backend :memory}
             (seq initial-tx) (assoc ::backend/initial-tx initial-tx)))
          request-path (socket-path (str label "-request"))
          publish-path (socket-path (str label "-publish"))]
      (try
        (d/create-database config)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Initial database ensure failed"
             (writer/start!
              {::writer/dependencies (dependencies)
               ::writer/database-name database-name
               ::writer/backend :memory
               ::writer/request-socket-path request-path
               ::writer/publish-socket-path publish-path})))
        (is (empty? (::registry/databases (registry/list-databases {})))
            "a rejected schema never publishes a registry entry")
        (finally
          (when (d/database-exists? config)
            (d/delete-database config))
          (.delete (File. request-path))
          (.delete (File. publish-path)))))))

(deftest writer-opens-and-routes-an-explicit-native-branch-attachment
  (let [database-name (str "writer-branch-main-" (random-uuid))
        branch-name (str "writer-branch-route-" (random-uuid))
        request-path (socket-path "branch-request")
        publish-path (socket-path "branch-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        main
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})
        attachment
        (assoc (::registry/attachment main)
               ::coordinate/branch :experiment/writer)]
    (try
      (d/branch! (::registry/conn main) :db :experiment/writer)
      (with-open [channel (uds/connect! request-path)]
        (let [ensure-response
              (call! channel
                     (protocol/ensure-database-request
                      {::protocol/request-id "branch/ensure-first"
                       ::protocol/database-name branch-name
                       ::protocol/backend :memory
                       ::coordinate/attachment attachment}))
              _
              (call! channel
                     (protocol/acquire-database-request
                      {::protocol/request-id "branch/acquire"
                       ::protocol/database-name branch-name
                       ::protocol/attachment attachment}))
              transaction-response
              (call! channel
                     (protocol/transaction-request
                      {::protocol/database-name branch-name
                       ::protocol/request-id "branch/routed"
                       ::protocol/transaction-data []}))]
          (is (::protocol/success? ensure-response))
          (is (= attachment
                 (coordinate/attachment
                  (::coordinate/coordinate ensure-response))))
          (is (::protocol/success? transaction-response))
          (is (= attachment
                 (coordinate/attachment
                  (::protocol/coordinate transaction-response))))))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest native-branch-open-restores-proximum-without-running-main-initializer
  (let [database-name (str "writer-proximum-main-" (random-uuid))
        branch-name (str "writer-proximum-branch-" (random-uuid))
        database-root (File. "tmp" (str "writer-proximum-" (random-uuid)))
        database-path (.getPath (File. database-root "db"))
        request-path (socket-path "proximum-branch-request")
        publish-path (socket-path "proximum-branch-publish")
        initializer-calls (atom [])
        server
        (writer/start!
         {::writer/dependencies
          (dependencies
           (fn [connection initialized-database-name]
             (swap! initializer-calls conj initialized-database-name)
             (embed/install! connection)))
          ::writer/database-name database-name
          ::writer/backend :file
          ::writer/database-path database-path
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        main
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})
        branch :experiment/proximum
        attachment
        (assoc (::registry/attachment main) ::coordinate/branch branch)]
    (try
      (is (embed/index-declared? (::registry/conn main)))
      (is (embed/index-live? (::registry/conn main)))
      (d/branch! (::registry/conn main)
                 (::coordinate/commit-id (::registry/coordinate main))
                 branch)
      (let [before (coordinate/resolved
                    (d/branch-as-db (::registry/conn main) branch))]
        (with-open [channel (uds/connect! request-path)]
          (let [response
                (call! channel
                       (protocol/ensure-database-request
                        {::protocol/request-id "branch/ensure-second"
                         ::protocol/database-name branch-name
                         ::protocol/backend :file
                         ::protocol/database-path database-path
                         ::coordinate/attachment attachment}))
                opened
                (registry/resolve-connection
                 {::registry/database-name (keyword branch-name)})]
            (is (::protocol/success? response))
            (is (= before (::registry/coordinate opened)))
            (is (embed/index-declared? (::registry/conn opened)))
            (is (embed/index-live? (::registry/conn opened)))
            (is (= [(keyword database-name)] @initializer-calls)
                "the writing database initializer runs only for main"))))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))
        (when (.exists database-root)
          (run! (fn [^File file] (.delete file))
                (reverse (file-seq database-root))))))))

(deftest typed-native-branch-lifecycle-routes-through-one-writer
  (let [source-name (str "writer-lifecycle-source-" (random-uuid))
        target-name (str "writer-lifecycle-target-" (random-uuid))
        request-path (socket-path "life-req")
        publish-path (socket-path "life-pub")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name source-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})]
    (try
      (with-open [channel (uds/connect! request-path)]
        (acquire! channel source-name "lifecycle/session")
        (let [initial-head
              (::registry/coordinate
               (registry/resolve-connection
                {::registry/database-name (keyword source-name)}))
              source-write
              (call! channel
                     (protocol/transaction-request
                      {::protocol/database-name source-name
                       ::protocol/request-id "lifecycle/source-schema"
                       ::protocol/transaction-data
                       [{:db/ident :writer.lifecycle/value
                         :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one}
                        {:writer.lifecycle/value "shared"}]}))
              source-head (::protocol/coordinate source-write)
              branch :experiment/typed-lifecycle
              stale
              (call! channel
                     (protocol/create-branch-request
                      {::protocol/request-id "branch/create-first"
                       ::protocol/source-database-name source-name
                       ::protocol/target-database-name target-name
                       ::protocol/source-coordinate source-head
                       ::protocol/expected-source-head initial-head
                       ::protocol/target-branch branch}))
              created
              (call! channel
                     (protocol/create-branch-request
                      {::protocol/request-id "branch/create-second"
                       ::protocol/source-database-name source-name
                       ::protocol/target-database-name target-name
                       ::protocol/source-coordinate source-head
                       ::protocol/expected-source-head source-head
                       ::protocol/target-branch branch}))
              target-attachment (::protocol/target-attachment created)
              target-write
              (with-open [target-channel (uds/connect! request-path)]
                (call! target-channel
                       (protocol/acquire-database-request
                        {::protocol/request-id "lifecycle/target-acquire"
                         ::protocol/database-name target-name
                         ::protocol/attachment target-attachment}))
                (call! target-channel
                       (protocol/transaction-request
                        {::protocol/database-name target-name
                         ::protocol/request-id "lifecycle/target-write"
                         ::protocol/transaction-data
                         [{:writer.lifecycle/value "target-only"}]})))
              target-head (::protocol/coordinate target-write)
              source-connection
              (::registry/conn
               (registry/resolve-connection
                {::registry/database-name (keyword source-name)}))
              target-db
              (d/branch-as-db source-connection branch)]
          (is (await-route! target-name false)
              "a target with no live child retains no authority connection")
          (let [unopened-branch :experiment/unopened
                _ (d/branch! source-connection
                             (::coordinate/commit-id source-head)
                             unopened-branch)
                observation
                (call! channel
                       (protocol/observe-database-lifecycle-request
                        {::protocol/request-id "branch/observe"
                         ::protocol/database-name source-name}))]
            (is (::protocol/success? observation))
            (is (= source-head (::protocol/main-coordinate observation)))
            (is (= (set (or (d/parent-commit-ids (d/db source-connection)) []))
                   (::protocol/main-parent-commit-ids observation)))
            (is (= [] (::protocol/restore-completions observation)))
            (is (= #{} (::protocol/completed-restore-ids observation)))
            (is (= {}
                   (::protocol/restore-completion-coordinates observation)))
            (is (= #{:db branch unopened-branch}
                   (::protocol/branch-roster observation)))
            (is (= (::protocol/branch-roster observation)
                   (set (keys (::protocol/branch-coordinates observation)))))
            (is (= (assoc source-head ::coordinate/branch unopened-branch)
                   (get (::protocol/branch-coordinates observation)
                        unopened-branch))))
          (is (= protocol/stale-source-head-error
                 (::protocol/error-kind stale)))
          (is (::protocol/success? created))
          (is (true? (::protocol/created? created)))
          (is (false? (::protocol/adopted? created)))
          (is (= (assoc source-head ::coordinate/branch branch)
                 (::protocol/coordinate created)))
          (is (::protocol/success? target-write))
          (is (some? (d/q '[:find ?entity .
                             :where [?entity :writer.lifecycle/value "target-only"]]
                           target-db)))
          (is (nil? (d/q '[:find ?entity .
                            :where [?entity :writer.lifecycle/value "target-only"]]
                          (d/db source-connection))))
          (let [deleted
                (call! channel
                       (protocol/delete-branch-request
                        {::protocol/request-id "branch/delete"
                         ::protocol/source-database-name source-name
                         ::protocol/target-database-name target-name
                         ::protocol/target-attachment target-attachment
                         ::protocol/expected-target-head target-head}))]
            (is (::protocol/success? deleted))
            (is (false? (::protocol/released? deleted)))
            (is (true? (::protocol/deleted? deleted)))
            (is (not (contains? (d/branches source-connection) branch))))))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest canonical-writes-route-commit-and-publish-exactly-once
  (let [database-name (str "writer-integration-" (random-uuid))
        request-path (socket-path "request")
        publish-path (socket-path "publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        ^SocketChannel request-channel (uds/connect! request-path)
        ^SocketChannel publish-channel (uds/connect! publish-path)]
    (try
      (acquire! request-channel database-name "writer/session")
      (wait-for-subscriber! (::writer/publisher server))
      (let [publish-input (Channels/newInputStream publish-channel)
            ping-response
            (call! request-channel
                   (protocol/ping-request
                    {::protocol/request-id "invalid/ping"}))
            ensure-response
            (call! request-channel
                   (protocol/ensure-database-request
                    {::protocol/request-id "invalid/ensure"
                     ::protocol/database-name database-name
                     ::protocol/backend :memory}))
            initial-coordinate (::coordinate/coordinate ensure-response)
            writer-connection
            (::registry/conn
             (registry/resolve-connection
              {::registry/database-name (keyword database-name)}))
            invalid-response
            (call! request-channel
                   {::protocol/operation protocol/transact-operation
                    ::protocol/request-id "missing-route"
                    ::protocol/transaction-data []})
            ensure-after-invalid
            (call! request-channel
                   (protocol/ensure-database-request
                    {::protocol/request-id "invalid/reensure"
                     ::protocol/database-name database-name
                     ::protocol/backend :memory}))
            unknown-response
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name "not-open"
                     ::protocol/request-id "unknown-route"
                     ::protocol/transaction-data []}))
            schema-request
            (protocol/transaction-request
             {::protocol/database-name database-name
              ::protocol/request-id "writer/schema"
              ::protocol/transaction-data
              [{:db/ident :writer.person/id
                :db/valueType :db.type/string
                :db/cardinality :db.cardinality/one
                :db/unique :db.unique/identity}
               {:db/ident :writer.person/status
                :db/valueType :db.type/keyword
                :db/cardinality :db.cardinality/one}
               {:db/ident :writer.person/score
                :db/valueType :db.type/double
                :db/cardinality :db.cardinality/one}]})
            schema-response (call! request-channel schema-request)
            entity-request
            (protocol/transaction-request
             {::protocol/database-name database-name
              ::protocol/request-id "writer/entity"
              ::protocol/transaction-data
              [{:db/id "person-temp"
                :writer.person/id "alice"
                :writer.person/status :writer.status/ready
                :writer.person/score 1}]})
            entity-response (call! request-channel entity-request)
            schema-event (uds/read-frame publish-input)
            entity-event (uds/read-frame publish-input)
            recovered-response (call! request-channel entity-request)
            unexpected-event (future (uds/read-frame publish-input))
            no-event-sentinel ::no-event
            observed-after-retry (deref unexpected-event 250 no-event-sentinel)
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            stored
            (d/pull (d/db connection) '[*]
                    [:writer.person/id "alice"])]
        (is (= {::protocol/success? true
                ::protocol/request-id "invalid/ping"
                ::protocol/pong? true}
               ping-response))
        (is (every? protocol/valid-response?
                    [ensure-response invalid-response ensure-after-invalid
                     unknown-response schema-response entity-response
                     recovered-response]))
        (is (coordinate/same-attachment?
             (coordinate/resolved (d/db connection))
             (::coordinate/coordinate ensure-response)))
        (is (= initial-coordinate
               (::coordinate/coordinate ensure-after-invalid))
            "an invalid request and an idempotent ensure write nothing")
        (is (= protocol/protocol-error
               (::protocol/error-kind invalid-response)))
        (is (= protocol/not-found-error
               (::protocol/error-kind unknown-response))
            "a named unknown database cannot fall back to the open database")
        (is (true? (::protocol/success? schema-response)))
        (is (= protocol/reserved-attributes
               (set (filter #(contains? (:schema (d/db writer-connection)) %)
                            protocol/reserved-attributes)))
            "the canonical Malli-derived receipt schema exists at publication")
        (is (true? (::protocol/success? entity-response)))
        (is (pos-int?
             (get (::protocol/temporary-ids entity-response) "person-temp")))
        (is (= [(::protocol/coordinate schema-response)
                (::protocol/coordinate entity-response)]
               (mapv ::protocol/coordinate
                     [schema-event entity-event])))
        (is (= ["writer/schema" "writer/entity"]
               (mapv ::protocol/request-id [schema-event entity-event])))
        (is (every? #(= protocol/transaction-event (::protocol/event %))
                    [schema-event entity-event]))
        (is (every?
             empty?
             (for [message [schema-response entity-response
                            schema-event entity-event]]
               (filter protocol/reserved-attributes
                       (map second (::protocol/transaction-data message)))))
            "public response and event datoms omit receipt implementation")
        (is (every?
             empty?
             (for [message [schema-response entity-response
                            schema-event entity-event]]
               (filter protocol/reserved-attributes
                       (keys (or (::protocol/transaction-meta message) {})))))
            "public response and event metadata omit receipt implementation")
        (is (= (count (::protocol/transaction-data entity-response))
               (+ (::protocol/datoms-added entity-response)
                  (::protocol/datoms-retracted entity-response))))
        (is (true? (::protocol/recovered? recovered-response)))
        (is (= no-event-sentinel observed-after-retry)
            "a recovered delivery emits no duplicate transaction event")
        (is (= :writer.status/ready (:writer.person/status stored)))
        (is (instance? Double (:writer.person/score stored)))
        (is (= 1.0 (:writer.person/score stored))))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (try (.close publish-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest expected-basis-is-enforced-inside-the-serialized-writer
  (let [database-name (str "writer-fence-" (random-uuid))
        request-path (socket-path "fence-request")
        publish-path (socket-path "fence-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (acquire! request-channel database-name "fence/session")
      (let [opened
            (call! request-channel
                   (protocol/ensure-database-request
                    {::protocol/request-id "invalid/ensure-after"
                     ::protocol/database-name database-name
                     ::protocol/backend :memory}))
            schema
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/schema"
                     ::protocol/transaction-data
                     [{:db/ident :writer.fence/id
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/unique :db.unique/identity}
                      {:db/ident :writer.fence/value
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}]}))
            frozen (::protocol/coordinate schema)
            accepted
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/accepted"
                     ::protocol/expected-coordinate frozen
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "accepted"}]}))
            committed (::protocol/coordinate accepted)
            wrong-branch
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/wrong-branch"
                     ::protocol/expected-coordinate
                     (assoc committed ::coordinate/branch :experiment)
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "wrong-branch-must-not-land"}]}))
            rejected
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/rejected"
                     ::protocol/expected-coordinate frozen
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "must-not-land"}]}))
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            stored (d/pull (d/db connection) '[*]
                           [:writer.fence/id "one"])]
        (is (true? (::protocol/success? opened)))
        (is (true? (::protocol/success? accepted)))
        (is (= protocol/stale-coordinate-error
               (::protocol/error-kind wrong-branch)))
        (is (= committed (::protocol/current-coordinate wrong-branch))
            "equal t and commit cannot cross a different branch attachment")
        (is (protocol/valid-response? rejected))
        (is (= protocol/stale-coordinate-error
               (::protocol/error-kind rejected)))
        (is (= frozen (::protocol/expected-coordinate rejected)))
        (is (= committed (::protocol/current-coordinate rejected)))
        (is (= (::coordinate/t committed) (:max-tx (d/db connection)))
            "the rejected request creates no receipt or transaction")
        (is (= "accepted" (:writer.fence/value stored))
            "none of the stale request lands"))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))
