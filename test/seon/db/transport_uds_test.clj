(ns seon.db.transport-uds-test
  "Protocol validation and Unix-socket transport tests."
  (:require [clojure.test :refer [deftest is]]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds])
  (:import [java.io File]
           [java.nio.channels Channels SocketChannel]
           [java.util Date UUID]))

(defn- socket-path
  [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory (str "seon-" label "-" (random-uuid) ".sock")))))

(defn- wait-for-subscriber!
  [publisher]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond
        (seq @(::uds/subscribers publisher)) true
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "publisher did not accept its subscriber" {}))
        :else (do (Thread/sleep 10) (recur))))))

(deftest canonical-request-validation-is-structural
  (let [ping (protocol/ping-request {::protocol/request-id "canonical/ping"})
        ensure (protocol/ensure-database-request
                {::protocol/request-id "canonical/ensure"
                 ::protocol/database-name "alpha"
                 ::protocol/backend :memory})
        transact (protocol/transaction-request
                  {::protocol/database-name "alpha"
                   ::protocol/request-id "request-1"
                   ::protocol/transaction-data [{:example/value :ready}]})]
    (is (every? protocol/valid-request? [ping ensure transact]))
    (is (false? (protocol/valid-request?
                 (dissoc transact ::protocol/database-name)))
        "every database-scoped operation requires explicit routing")
    (is (false? (protocol/valid-request?
                 (assoc transact ::protocol/operation :transact)))
        "bare operation vocabulary is not accepted")
    (is (= protocol/protocol-error
           (::protocol/error-kind
            (protocol/failure
             {::protocol/error-kind protocol/protocol-error
              ::protocol/error "invalid"}))))))

(deftest transit-roundtrip-preserves-native-protocol-values
  (let [request-id (str (UUID/randomUUID))
        instant (Date. 1720000000000)
        message
        (protocol/transaction-request
         {::protocol/database-name "alpha"
          ::protocol/request-id request-id
          ::protocol/transaction-data
          [{:db/id "entity"
            :example/status :example.status/ready
            :example/at instant}]
          ::protocol/transaction-meta
          {:seon.db/process :seon.db.process/repl}})
        decoded (uds/decode (uds/encode message))]
    (is (= message decoded))
    (is (keyword? (::protocol/operation decoded)))
    (is (= :example.status/ready
           (get-in decoded [::protocol/transaction-data 0
                            :example/status])))
    (is (instance? Date
                   (get-in decoded [::protocol/transaction-data 0
                                    :example/at])))))

(deftest transit-roundtrip-preserves-the-complete-coordinate
  (let [point
        {::coordinate/database-id
         #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
         ::coordinate/branch :db
         ::coordinate/commit-id
         #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
         ::coordinate/t 536870929}]
    (is (= point (uds/decode (uds/encode point))))))

(deftest execute-many-reuses-existing-read-shapes-with-one-public-identity
  (let [point {::coordinate/database-id (random-uuid)
               ::coordinate/branch :db
               ::coordinate/commit-id (random-uuid)
               ::coordinate/t 42}
        attachment (coordinate/attachment point)
        members [{::protocol/operation protocol/query-operation
                  ::protocol/query-form '[:find ?e :where [?e :db/ident]]
                  ::protocol/arguments []}
                 {::protocol/operation protocol/pull-operation
                  ::protocol/selector '[*]
                  ::protocol/entity-id 1}
                 {::protocol/operation protocol/pull-many-operation
                  ::protocol/selector '[:db/ident]
                  ::protocol/entity-ids [1 2]}]
        request (protocol/execute-many-request
                 {::protocol/request-id "many-1"
                  ::protocol/database-name "alpha"
                  ::protocol/attachment attachment
                  ::protocol/coordinate point
                  ::protocol/members members})]
    (is (= 5 protocol/current-version))
    (is (protocol/valid-request? request))
    (is (= request (uds/decode (uds/encode request))))
    (is (false? (protocol/valid-request?
                 (assoc-in request [::protocol/members 0
                                    ::protocol/request-id]
                           "member-id")))
        "members cannot invent another request identity")
    (is (every?
         false?
         [(protocol/valid-request? (assoc request ::protocol/members []))
          (protocol/valid-request?
           (assoc request ::protocol/members
                  [{::protocol/operation protocol/knn-search-operation
                    ::protocol/query "vector"
                    ::protocol/limit 1}]))])
        "an empty group and non-read members are rejected")
    (is (false? (protocol/valid-request?
                 (assoc request ::protocol/members (vec (repeat 65
                                                              (first members))))))
        "the semantic member bound is enforced before admission")))

(deftest database-acquisition-is-closed-correlated-and-transit-stable
  (let [point {::coordinate/database-id (random-uuid)
               ::coordinate/branch :db
               ::coordinate/commit-id (random-uuid)
               ::coordinate/t 42}
        attachment (coordinate/attachment point)
        request (protocol/acquire-database-request
                 {::protocol/request-id "acquire/alpha"
                  ::protocol/database-name "alpha"})
        response (protocol/success
                  {::protocol/request-id "acquire/alpha"
                   ::protocol/database-name "alpha"
                   ::protocol/attachment attachment
                   ::protocol/coordinate point
                   ::protocol/acquired? true})]
    (is (protocol/valid-request? request))
    (is (protocol/valid-response? response))
    (is (= request (uds/decode (uds/encode request))))
    (is (= response (uds/decode (uds/encode response))))
    (is (false? (protocol/valid-request?
                 (assoc request :unexpected/field true)))
        "acquisition requests reject transport-private or unknown fields")
    (is (false? (protocol/valid-response?
                 (assoc response :unexpected/field true)))
        "acquisition responses reject transport-private or unknown fields")
    (is (false? (protocol/valid-response?
                 (assoc response ::protocol/acquired? :yes))))))

(deftest transaction-coordinate-request-and-response-are-transit-stable
  (let [head
        {::coordinate/database-id
         #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
         ::coordinate/branch :db
         ::coordinate/commit-id
         #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
         ::coordinate/t 536870929}
        original
        (assoc head
               ::coordinate/commit-id
               #uuid "6a56b425-30e5-53b3-86c1-e31381023716"
               ::coordinate/t 536870920)
        request
        (protocol/resolve-transaction-coordinate-request
         {::protocol/request-id "coordinate/resolve"
          ::protocol/database-name "default"
          ::protocol/head-coordinate head
          ::protocol/transaction-id (::coordinate/t original)})
        response (protocol/success
                  {::protocol/request-id "coordinate/resolve"
                   ::protocol/coordinate original})]
    (is (protocol/valid-request? request))
    (is (protocol/valid-response? response))
    (is (= request (uds/decode (uds/encode request))))
    (is (= response (uds/decode (uds/encode response))))))

(deftest ensure-request-roundtrip-preserves-explicit-attachment
  (let [attachment
        {::coordinate/database-id
         #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
         ::coordinate/branch :experiment/cold}
        request
        (protocol/ensure-database-request
         {::protocol/request-id "ensure/attachment"
          ::protocol/database-name "experiment-cold"
          ::protocol/backend :file
          ::protocol/database-path "data/clusters/default/db"
          ::coordinate/attachment attachment})
        decoded (uds/decode (uds/encode request))]
    (is (protocol/valid-request? decoded))
    (is (= attachment (::coordinate/attachment decoded)))
    (is (= request decoded))))

(deftest lifecycle-requests-are-closed-and-transit-stable
  (let [source
        {::coordinate/database-id
         #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
         ::coordinate/branch :db
         ::coordinate/commit-id
         #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
         ::coordinate/t 536870929}
        target-attachment
        (assoc (coordinate/attachment source)
               ::coordinate/branch :experiment/lifecycle)
        target-head (merge source target-attachment)
        requests
        [(protocol/create-branch-request
          {::protocol/request-id "lifecycle/create"
           ::protocol/source-database-name "default"
           ::protocol/target-database-name "default-lifecycle"
           ::protocol/source-coordinate source
           ::protocol/expected-source-head source
           ::protocol/target-branch :experiment/lifecycle})
         (protocol/release-database-request
          {::protocol/request-id "lifecycle/release"
           ::protocol/target-database-name "default-lifecycle"
           ::protocol/target-attachment target-attachment
           ::protocol/expected-target-head target-head})
         (protocol/delete-branch-request
          {::protocol/request-id "lifecycle/delete"
           ::protocol/source-database-name "default"
           ::protocol/target-database-name "default-lifecycle"
           ::protocol/target-attachment target-attachment
           ::protocol/expected-target-head target-head})]]
    (is (every? protocol/valid-request? requests))
    (is (= requests (mapv #(uds/decode (uds/encode %)) requests)))
    (is (every? false?
                (map #(protocol/valid-request?
                       (assoc % :unexpected/field true))
                     requests))
        "lifecycle requests reject unknown fields")
    (is (false? (protocol/valid-request?
                 (dissoc (first requests) ::protocol/source-coordinate))))
    (is (false? (protocol/valid-request?
                 (assoc (second requests)
                        ::protocol/expected-target-head
                        (coordinate/attachment target-head)))))))

(deftest synchronous-call-preserves-complete-lifecycle-values
  (let [path (socket-path "transport-lifecycle-call")
        source
        {::coordinate/database-id
         #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
         ::coordinate/branch :db
         ::coordinate/commit-id
         #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
         ::coordinate/t 536870929}
        target-attachment
        (assoc (coordinate/attachment source)
               ::coordinate/branch :experiment/portable-call)
        request
        (protocol/create-branch-request
         {::protocol/request-id "lifecycle/call"
          ::protocol/source-database-name "default"
          ::protocol/target-database-name "default-portable-call"
          ::protocol/source-coordinate source
          ::protocol/expected-source-head source
          ::protocol/target-branch :experiment/portable-call})
        response
        (protocol/success
         {::protocol/request-id "lifecycle/call"
          ::protocol/target-database-name "default-portable-call"
          ::protocol/target-attachment target-attachment
          ::protocol/coordinate (merge source target-attachment)
          ::protocol/backend :file
          ::protocol/database-path "data/clusters/default/db"
          ::protocol/created? true
          ::protocol/adopted? false})
        seen (atom [])
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/handler (fn [message]
                          (swap! seen conj message)
                          response)})]
    (try
      (with-open [channel (uds/connect! path)]
        (let [actual
              (uds/call! {::uds/channel channel ::uds/message request})]
          (is (= [request] @seen))
          (is (= response actual))
          (is (protocol/valid-response? actual))))
      (finally
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest request-server-delivers-maps-without-interpreting-them
  (let [path (socket-path "transport-request")
        seen (atom [])
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/handler
          (fn [request]
            (swap! seen conj request)
            (protocol/success {::protocol/pong? true}))})]
    (try
      (with-open [channel (uds/connect! path)]
        (let [request (protocol/ping-request
                       {::protocol/request-id "transport/ping"})
              response (uds/call! {::uds/channel channel
                                   ::uds/message request})]
          (is (= [request] @seen))
          (is (= {::protocol/success? true ::protocol/pong? true}
                 response))))
      (finally
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest request-server-close-drains-admitted-work-and-rejects-later-admission
  (let [path (socket-path "transport-drain")
        request {:transport-drain/request "accepted"}
        entered (promise)
        release-handler (promise)
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/handler
          (fn [message]
            (deliver entered message)
            @release-handler
            {:transport-drain/response "committed"})})
        client
        (future
          (with-open [channel (uds/connect! path)]
            (uds/call! {::uds/channel channel ::uds/message request})))]
    (try
      (is (= request (deref entered 2000 ::handler-not-entered)))
      (let [close (future (uds/close-request-server! server))]
        (is (= ::still-draining (deref close 100 ::still-draining))
            "close waits for the admitted handler")
        (deliver release-handler true)
        (is (= {:transport-drain/response "committed"}
               (deref client 2000 ::client-timeout))
            "the admitted response is delivered before its connection closes")
        (is (nil? (deref close 2000 ::close-timeout)))
        (is (thrown? Throwable (uds/connect! path))
            "a closed request server accepts no later connection"))
      (finally
        (deliver release-handler true)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest publisher-delivers-a-complete-large-frame
  (let [path (socket-path "transport-publish")
        publisher (uds/start-publisher! path)
        ^SocketChannel channel (uds/connect! path)
        message {::large-payload (apply str (repeat (* 4 1024 1024) "x"))}]
    (try
      (wait-for-subscriber! publisher)
      (let [received
            (future
              (uds/read-frame (Channels/newInputStream channel)))]
        (uds/publish! {::uds/publisher publisher ::uds/message message})
        (is (= message (deref received 5000 ::timed-out))))
      (finally
        (.close channel)
        (uds/close-publisher! publisher)
        (.delete (File. path))))))
