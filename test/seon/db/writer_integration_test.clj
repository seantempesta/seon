(ns seon.db.writer-integration-test
  "End-to-end canonical writer request and addressed-event tests."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.backend :as backend]
            [seon.db.branch :as branch]
            [seon.db.executor :as executor]
            [seon.db.id :as id]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]
            [seon.embed :as embed]
            [seon.schema :as schema])
  (:import [java.io File]
           [java.nio.channels SocketChannel]
           [java.util.concurrent CountDownLatch TimeUnit]))

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

(defn- call!
  [channel request]
  (uds/call! {::uds/channel channel ::uds/message request}))

(deftest writer-failures-preserve-the-existing-seon-error-kind
  (let [throwable (ex-info "unknown attribute" {:seon.error/kind :user-input})
        response (#'writer/request-failure-response throwable)
        member (#'writer/member-failure throwable)]
    (is (= :user-input (:seon.error/kind response)))
    (is (= :user-input (:seon.error/kind member)))
    (is (protocol/valid-response?
         (assoc member ::protocol/request-id "query/user-input")))))

(defn- acquire!
  [channel database-name label]
  (call! channel
         (protocol/acquire-database-request
          {::protocol/request-id (str label "/acquire")
           ::protocol/database-name database-name})))

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
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
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
                      ::protocol/version protocol/current-version
                      ::protocol/maximum-frame-bytes
                      protocol/maximum-frame-bytes)
               (::protocol/capabilities capabilities)))
        (is (= "control/head" (::protocol/request-id head)))
        (is (= database-name (:db-name (:seon.db/db head))))
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
        nil))))

(deftest physical-connections-own-exact-database-access-and-release
  (let [database-name (str "writer-acquisition-" (random-uuid))
        request-path (socket-path "acquisition-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        ^SocketChannel a (uds/connect! request-path)
        ^SocketChannel b (uds/connect! request-path)]
    (try
      (let [head
            (call! a
                   (protocol/resolve-head-request
                    {::protocol/request-id "acquisition/head"
                     ::protocol/database-name database-name}))
            database (:seon.db/db head)
            query-input
            {:seon.db/db database
             ::protocol/query-form
             '[:find ?ident :where [?entity :db/ident ?ident]]
             ::protocol/arguments []}
            initial-read
            (call! a
                   (protocol/query-request
                    (assoc query-input
                           ::protocol/request-id "acquisition/initial-read")))
            acquired-a (acquire! a database-name "acquisition/a")
            duplicate-a (acquire! a database-name "acquisition/a-duplicate")
            write
            (call! a
                   (protocol/transaction-request
                    {::protocol/request-id "acquisition/write"
                     :seon.db/db (:seon.db/db acquired-a)
                     ::protocol/transaction-data
                     [{:db/ident :acquisition/value
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}
                      {:acquisition/value "owned"}]}))
            current
            (assoc query-input :seon.db/db (:db-after write))
            initial-b
            (call! b
                   (protocol/query-request
                    (assoc current
                           ::protocol/request-id "acquisition/initial-b")))
            acquired-b (acquire! b database-name "acquisition/b")]
        (is (::protocol/success? initial-read))
        (is (false? (::protocol/acquired? acquired-a)))
        (is (false? (::protocol/acquired? duplicate-a)))
        (is (::protocol/success? initial-b))
        (is (false? (::protocol/acquired? acquired-b)))
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
        nil))))

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

(deftest execute-many-result-weight-is-position-deterministic
  (let [point {::branch/store-id (random-uuid)
               ::branch/name :main
               ::branch/commit-id (random-uuid)
               ::branch/basis-t 536870912}
        request
        (protocol/execute-many-request
         {::protocol/request-id "many/weight-order"
          ::protocol/database-name "default"
          ::protocol/branch-head point
          ::protocol/members
          [{::protocol/operation protocol/pull-operation
            ::protocol/selector [:db/id]
            ::protocol/entity-id 1}
           {::protocol/operation protocol/pull-operation
            ::protocol/selector [:db/id]
            ::protocol/entity-id 2}]})
        initial (#'writer/execute-many-result-state request)
        placeholder-weight (::writer/result-placeholder-weight initial)
        small (protocol/success {::protocol/result "small"})
        large (protocol/success {::protocol/result (apply str (repeat 1024 "x"))})
        small-weight (d/shallow-weight-within small protocol/maximum-frame-bytes)
        tight-limit
        (max (::writer/result-weight initial)
             (+ (- (::writer/result-weight initial) placeholder-weight)
                small-weight 8))
        tight-request
        (assoc request :datahike.resource/max-result-weight tight-limit)
        initial (#'writer/execute-many-result-state tight-request)
        ordered
        (#'writer/accept-contiguous-execute-many-results
         (assoc initial ::writer/request tight-request
                ::writer/results [small large]))
        waiting
        (#'writer/accept-contiguous-execute-many-results
         (assoc-in (assoc initial ::writer/request tight-request)
                   [::writer/results 1] large))
        inverted
        (#'writer/accept-contiguous-execute-many-results
         (assoc-in waiting [::writer/results 0] small))]
    (is (= 0 (::writer/next-result-position waiting)))
    (is (= (select-keys ordered
                        [::writer/results ::writer/next-result-position
                         ::writer/result-weight
                         ::writer/result-limit-position])
           (select-keys inverted
                        [::writer/results ::writer/next-result-position
                         ::writer/result-weight
                         ::writer/result-limit-position])))
    (is (= 1 (::writer/result-limit-position ordered)))
    (is (= small (first (::writer/results ordered))))
    (is (= (var-get #'writer/execute-many-result-limit)
           (second (::writer/results ordered))))))

(deftest callback-requests-reject-active-duplicates-and-reuse-cleanly
  (let [database-name (str "writer-callback-reuse-" (random-uuid))
        request-path (socket-path "callback-reuse-request")
        entered (java.util.concurrent.CountDownLatch. 1)
        finish (java.util.concurrent.CountDownLatch. 1)
        calls (atom 0)
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        runtime (::writer/runtime server)
        transport
        (#'writer/transport-connection
         {::uds/close! (fn [] nil) ::uds/send! (fn [_message] nil)})
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (let [acquired (promise)]
        (writer/handle-request!
         runtime transport
         (protocol/acquire-database-request
          {::protocol/request-id "callback-reuse/acquire"
           ::protocol/database-name database-name})
         #(deliver acquired %))
        (is (::protocol/success? (deref acquired 5000 ::not-delivered))))
      (let [head
            (call! request-channel
                   (protocol/resolve-head-request
                    {::protocol/request-id "callback-reuse/head"
                     ::protocol/database-name database-name}))
            request
            (protocol/pull-request
             {::protocol/request-id "callback-reuse/read"
              :seon.db/db (:seon.db/db head)
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
          (writer/handle-request! runtime transport request
                                  #(deliver first-response %))
          (is (.await entered 5 java.util.concurrent.TimeUnit/SECONDS))
          (writer/handle-request! runtime transport request
                                  #(deliver duplicate-response %))
          (is (= protocol/request-conflict-error
                 (::protocol/error-kind
                  (deref duplicate-response 1000 ::not-delivered))))
          (is (= 1 @calls) "the duplicate never reaches Datahike")
          (.countDown finish)
          (is (= {:callback/value 1}
                 (::protocol/result (deref first-response 5000 ::not-delivered))))
          (writer/handle-request! runtime transport request
                                  #(deliver reused-response %))
          (is (= {:callback/value 2}
                 (::protocol/result (deref reused-response 5000 ::not-delivered))))
          (is (= 2 @calls))
          (is (empty? @(::writer/active-requests runtime)))))
      (finally
        (.countDown finish)
        (#'writer/close-transport-connection! runtime transport)
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest writer-stop-surfaces-release-failure-and-retains-database-identity
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-name (str "writer-release-failure-" (random-uuid))
        database-keyword (keyword database-name)
        request-path (socket-path "release-failure-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        branch-head-before
        (::registry/branch-head
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
        (is (= (branch/connection-id branch-head-before)
               (::registry/connection-id failure)))
        (is (= branch-head-before (::registry/branch-head failure)))
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
        nil))))

(deftest writer-stop-retains-authority-until-request-workers-stop
  (let [executor-stops (atom 0)
        database-lists (atom 0)
        result
        (with-redefs [uds/close-request-server!
                      (fn [_]
                        {::uds/graceful? false
                         ::uds/forced-connections 1
                         ::uds/selector-stopped? true
                         ::uds/workers-stopped? false
                         ::uds/cleanup-stopped? true})
                      executor/stop! (fn [_] (swap! executor-stops inc))
                      registry/list-databases
                      (fn [_]
                        (swap! database-lists inc)
                        {::registry/databases []})]
          (writer/stop! {::writer/request-server ::request-server
                         ::writer/executor ::executor}))]
    (is (= {::writer/stopped? false ::writer/release-results []} result))
    (is (schema/valid-candidate-value? ::writer/stop-response result))
    (is (zero? @executor-stops))
    (is (zero? @database-lists))))

(deftest writer-stop-returns-the-pre-release-branch-head-and-outcome
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-name (str "writer-release-success-" (random-uuid))
        database-keyword (keyword database-name)
        request-path (socket-path "release-success-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
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
                ::registry/connection-id (::registry/connection-id before)
                ::registry/branch-head (::registry/branch-head before)
                ::registry/released? true}
               release))
        (is (nil? (::registry/conn
                   (registry/resolve-connection
                    {::registry/database-name database-keyword})))))
      (finally
        (registry/restore-registry! {::registry/snapshot snapshot})
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

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
          request-path (socket-path (str label "-request"))]
      (try
        (d/create-database config)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Initial database ensure failed"
             (writer/start!
              {::writer/dependencies (dependencies)
               ::writer/database-name database-name
               ::writer/backend :memory
               ::writer/request-socket-path request-path})))
        (is (empty? (::registry/databases (registry/list-databases {})))
            "a rejected schema never publishes a registry entry")
        (finally
          (when (d/database-exists? config)
            (d/delete-database config))
          (.delete (File. request-path))
          nil)))))

(deftest writer-opens-and-routes-an-explicit-datahike-connection-id
  (let [database-name (str "writer-branch-main-" (random-uuid))
        branch-name (str "writer-branch-route-" (random-uuid))
        request-path (socket-path "branch-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        main
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})
        connection-id
        (assoc (::registry/connection-id main) 1 :experiment/writer)]
    (try
      (d/branch! (::registry/conn main) :db :experiment/writer)
      (with-open [channel (uds/connect! request-path)]
        (let [ensure-response
              (call! channel
                     (protocol/ensure-database-request
                      {::protocol/request-id "branch/ensure-first"
                       ::protocol/database-name branch-name
                       ::protocol/backend :memory
                       ::branch/connection-id connection-id}))
              _
              (call! channel
                     (protocol/acquire-database-request
                      {::protocol/request-id "branch/acquire"
                       ::protocol/database-name branch-name}))
              transaction-response
              (call! channel
                     (protocol/transaction-request
                      {::protocol/request-id "branch/routed"
                       :seon.db/db (:seon.db/db ensure-response)
                       ::protocol/transaction-data []}))]
          (is (::protocol/success? ensure-response))
          (is (= branch-name (:db-name (:seon.db/db ensure-response))))
          (is (= connection-id
                 (::registry/connection-id
                  (registry/resolve-connection
                   {::registry/database-name (keyword branch-name)}))))
          (is (::protocol/success? transaction-response))
          (is (= branch-name (:db-name (:db-after transaction-response))))))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest native-branch-open-restores-proximum-without-running-main-initializer
  (let [database-name (str "writer-proximum-main-" (random-uuid))
        branch-name (str "writer-proximum-branch-" (random-uuid))
        database-root (File. "tmp" (str "writer-proximum-" (random-uuid)))
        database-path (.getPath (File. database-root "db"))
        request-path (socket-path "proximum-branch-request")
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
          ::writer/request-socket-path request-path})
        main
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})
        branch :experiment/proximum
        connection-id
        (assoc (::registry/connection-id main) 1 branch)]
    (try
      (is (embed/index-declared? (::registry/conn main)))
      (is (embed/index-live? (::registry/conn main)))
      (d/branch! (::registry/conn main)
                 (::branch/commit-id (::registry/branch-head main))
                 branch)
      (let [before (branch/head
                    (d/branch-as-db (::registry/conn main) branch))]
        (with-open [channel (uds/connect! request-path)]
          (let [response
                (call! channel
                       (protocol/ensure-database-request
                        {::protocol/request-id "branch/ensure-second"
                         ::protocol/database-name branch-name
                         ::protocol/backend :file
                         ::protocol/database-path database-path
                         ::branch/connection-id connection-id}))
                opened
                (registry/resolve-connection
                 {::registry/database-name (keyword branch-name)})]
            (is (::protocol/success? response))
            (is (= before (::registry/branch-head opened)))
            (is (embed/index-declared? (::registry/conn opened)))
            (is (embed/index-live? (::registry/conn opened)))
            (is (= [(keyword database-name)] @initializer-calls)
                "the writing database initializer runs only for main"))))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))
        nil
        (when (.exists database-root)
          (run! (fn [^File file] (.delete file))
                (reverse (file-seq database-root))))))))

(deftest canonical-writes-route-commit-and-recover-exactly-once
  (let [database-name (str "writer-integration-" (random-uuid))
        request-path (socket-path "request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (let [acquired (acquire! request-channel database-name "writer/session")
            initial-db (:seon.db/db acquired)
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
            ensured-db (:seon.db/db ensure-response)
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
                    {::protocol/request-id "unknown-route"
                     :seon.db/db (assoc initial-db :db-name "not-open")
                     ::protocol/transaction-data []}))
            schema-response
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/request-id "writer/schema"
                     :seon.db/db ensured-db
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
                       :db/cardinality :db.cardinality/one}]}))
            entity-request
            (protocol/transaction-request
             {::protocol/request-id "writer/entity"
              :seon.db/db (:db-after schema-response)
              ::protocol/transaction-data
              [{:db/id "person-temp"
                :writer.person/id "alice"
                :writer.person/status :writer.status/ready
                :writer.person/score 1}]})
            entity-response (call! request-channel entity-request)
            recovered-response (call! request-channel entity-request)
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            stored
            (d/pull (d/db connection) '[*] [:writer.person/id "alice"])]
        (is (= {::protocol/success? true
                ::protocol/request-id "invalid/ping"
                ::protocol/pong? true}
               ping-response))
        (is (every? protocol/valid-response?
                    [ensure-response invalid-response ensure-after-invalid
                     unknown-response schema-response entity-response
                     recovered-response]))
        (is (= database-name (:db-name (:seon.db/db ensure-response))))
        (is (= ensured-db (:seon.db/db ensure-after-invalid))
            "invalid input and idempotent ensure do not advance the database")
        (is (= protocol/protocol-error
               (::protocol/error-kind invalid-response)))
        (is (= protocol/not-found-error
               (::protocol/error-kind unknown-response))
            "a named unknown database cannot fall back to the open database")
        (is (true? (::protocol/success? schema-response)))
        (is (= protocol/reserved-attributes
               (set (filter #(contains? (:schema (d/db writer-connection)) %)
                            protocol/reserved-attributes)))
            "the canonical Malli-derived receipt schema exists before writes")
        (is (true? (::protocol/success? entity-response)))
        (is (pos-int?
             (get (:tempids entity-response) "person-temp")))
        (is (every?
             empty?
             (for [message [schema-response entity-response]]
               (filter protocol/reserved-attributes
                       (map second (:tx-data message)))))
            "public transaction responses omit receipt implementation datoms")
        (is (every?
             empty?
             (for [message [schema-response entity-response]]
               (filter protocol/reserved-attributes
                       (keys (or (:tx-meta message) {})))))
            "public transaction responses omit receipt implementation metadata")
        (is (= (:db-after entity-response) (:db-after recovered-response))
            "an idempotent retry returns the exact committed database value")
        (is (= (set (:tx-data entity-response))
               (set (:tx-data recovered-response))))
        (is (= :writer.status/ready (:writer.person/status stored)))
        (is (instance? Double (:writer.person/score stored)))
        (is (= 1.0 (:writer.person/score stored))))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))))))

(deftest expected-basis-is-enforced-inside-the-serialized-writer
  (let [database-name (str "writer-fence-" (random-uuid))
        request-path (socket-path "fence-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        ^SocketChannel a (uds/connect! request-path)
        ^SocketChannel b (uds/connect! request-path)]
    (try
      (let [acquired-a (acquire! a database-name "fence/a")
            _acquired-b (acquire! b database-name "fence/b")
            opened
            (call! a
                   (protocol/ensure-database-request
                    {::protocol/request-id "invalid/ensure-after"
                     ::protocol/database-name database-name
                     ::protocol/backend :memory}))
            schema
            (call! a
                   (protocol/transaction-request
                    {::protocol/request-id "fence/schema"
                     :seon.db/db (:seon.db/db acquired-a)
                     ::protocol/transaction-data
                     [{:db/ident :writer.fence/id
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/unique :db.unique/identity}
                      {:db/ident :writer.fence/value
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}]}))
            frozen (:db-after schema)
            ready (CountDownLatch. 2)
            start (CountDownLatch. 1)
            submit
            (fn [channel request-id entity-id]
              (future
                (.countDown ready)
                (.await start)
                (call! channel
                       (protocol/transaction-request
                        {::protocol/request-id request-id
                         :seon.db/db frozen
                         :seon.db/expected-db frozen
                         ::protocol/transaction-data
                         [{:writer.fence/id entity-id
                           :writer.fence/value request-id}]}))))
            left (submit a "fence/left" "left")
            right (submit b "fence/right" "right")
            _ready? (is (.await ready 5 TimeUnit/SECONDS)
                        "both requests are ready before concurrent release")
            _started? (.countDown start)
            responses [(deref left 5000 ::timed-out)
                       (deref right 5000 ::timed-out)]
            accepted (first (filter ::protocol/success? responses))
            rejected (first (remove ::protocol/success? responses))
            committed (:db-after accepted)
            wrong-commit
            (call! a
                   (protocol/transaction-request
                    {::protocol/request-id "fence/wrong-commit"
                     :seon.db/db committed
                     :seon.db/expected-db
                     (assoc committed :datahike/commit-id (random-uuid))
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "wrong-commit-must-not-land"}]}))
            wrong-branch
            (call! a
                   (protocol/transaction-request
                    {::protocol/request-id "fence/wrong-database"
                     :seon.db/db committed
                     :seon.db/expected-db
                     (assoc committed :db-name "not-this-database")
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "wrong-branch-must-not-land"}]}))
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            stored
            (d/q '[:find ?id ?value
                   :where
                   [?entity :writer.fence/id ?id]
                   [?entity :writer.fence/value ?value]]
                 (d/db connection))]
        (is (true? (::protocol/success? opened)))
        (is (not-any? #{::timed-out} responses))
        (is (= 1 (count (filter ::protocol/success? responses)))
            "exactly one same-head request commits")
        (is (= 1 (count (remove ::protocol/success? responses)))
            "the serialized writer rejects the losing request")
        (is (true? (::protocol/success? accepted)))
        (is (= protocol/stale-database-value-error
               (::protocol/error-kind wrong-commit)))
        (is (= committed (:seon.db/current-db wrong-commit))
            "equal t cannot cross a different commit identity")
        (is (= protocol/stale-database-value-error
               (::protocol/error-kind wrong-branch)))
        (is (= committed (:seon.db/current-db wrong-branch))
            "an expected value cannot name a different database")
        (is (protocol/valid-response? rejected))
        (is (= protocol/stale-database-value-error
               (::protocol/error-kind rejected)))
        (is (= frozen (:seon.db/expected-db rejected)))
        (is (= committed (:seon.db/current-db rejected)))
        (is (= (:t committed) (:max-tx (d/db connection)))
            "the rejected request creates no receipt or transaction")
        (is (= 1 (count stored))
            "only the winning request's domain facts land"))
      (finally
        (try (.close a) (catch Throwable _))
        (try (.close b) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))
