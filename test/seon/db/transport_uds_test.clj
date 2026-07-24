(ns seon.db.transport-uds-test
  "Protocol validation and Unix-socket transport tests."
  (:require [clojure.test :refer [deftest is]]
            [seon.db.branch :as branch]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.error :as error])
  (:import [com.sun.management UnixOperatingSystemMXBean]
           [java.io File InputStream]
           [java.lang.management ManagementFactory]
           [java.nio ByteBuffer]
           [java.nio.channels Channels SocketChannel]
           [java.util ArrayDeque Date UUID]
           [java.util.concurrent CountDownLatch LinkedBlockingQueue TimeUnit]
           [java.util.concurrent.atomic AtomicReference]))

(def ^:private database
  {:db-name "alpha"
   :store-id [#uuid "ca2dd867-e51c-4165-b3b7-430bfe199f2e" :db]
   :t 536870929
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id
   #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"})

(defn- socket-path
  [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory (str "seon-" label "-" (random-uuid) ".sock")))))

(defn- wait-until!
  [description predicate]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond
        (predicate) true
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info (str "timed out waiting for " description) {}))
        :else (do (Thread/sleep 5) (recur))))))

(defn- open-file-descriptor-count
  []
  (.getOpenFileDescriptorCount
   ^UnixOperatingSystemMXBean (ManagementFactory/getOperatingSystemMXBean)))

(defn- request-server!
  [path handler close-connection!]
  (uds/start-request-server!
   {::uds/socket-path path
    ::uds/open-connection!
    (fn [{close! ::uds/close!}]
      {::connection-id (random-uuid) ::close! close!})
    ::uds/close-connection! close-connection!
    ::uds/handler handler}))

(defn- frame-bytes
  [message]
  (let [payload (uds/encode message)]
    (.array
     (doto (ByteBuffer/allocate (+ Integer/BYTES (alength payload)))
       (.putInt (alength payload))
       (.put payload)))))

(defn- joined-bytes
  [& frames]
  (let [buffer (ByteBuffer/allocate (reduce + (map alength frames)))]
    (run! #(.put buffer ^bytes %) frames)
    (.array buffer)))

(defn- write-bytes!
  [^SocketChannel channel ^bytes bytes chunk-size]
  (loop [offset 0]
    (when (< offset (alength bytes))
      (let [length (min chunk-size (- (alength bytes) offset))
            buffer (ByteBuffer/wrap bytes offset length)]
        (loop []
          (when (.hasRemaining buffer)
            (.write channel buffer)
            (recur)))
        (recur (+ offset length))))))

(defn- one-byte-input
  [^SocketChannel channel]
  (let [delegate (Channels/newInputStream channel)]
    (proxy [InputStream] []
      (read
        ([] (.read delegate))
        ([bytes offset length]
         (.read delegate bytes offset (min 1 length))))
      (close [] (.close delegate)))))

(defn- open-admitted-channel!
  [path]
  (let [session (uds/open-session! path)]
    (::uds/channel session)))

(deftest at-capacity-session-open-delivery-is-bounded-and-restores-admission
  (let [path (socket-path "session-open-capacity")
        maximum-connections 1
        opened (atom 0)
        closed (atom 0)
        baseline-fds (open-file-descriptor-count)
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/maximum-connections maximum-connections
          ::uds/open-connection!
          (fn [_control]
            (swap! opened inc)
            (Object.))
          ::uds/close-connection! (fn [_owner] (swap! closed inc))
          ;; Before admission belongs to the transport, this response lets the
          ;; held channel reach the exact future test state. Cap+1 never reaches
          ;; the handler in either implementation.
          ::uds/handler
          (fn [_owner request _frame-bytes complete!]
            (when (= protocol/session-open-operation
                     (::protocol/operation request))
              (complete!
               (protocol/session-open-success
                {::protocol/configured-maximum-frame-bytes
                 protocol/maximum-frame-bytes
                 ::protocol/maximum-frame-bytes
                 protocol/maximum-frame-bytes}))))})
        opening
        (protocol/session-open-request
         {::protocol/maximum-frame-bytes protocol/maximum-frame-bytes})
        capacity-failure
        (protocol/connection-capacity-failure
         {::protocol/maximum-connections maximum-connections})
        held (uds/connect! path)
        rejected (atom [])]
    (try
      (write-bytes! held (frame-bytes opening) 1)
      (is (= (protocol/session-open-success
              {::protocol/configured-maximum-frame-bytes
               protocol/maximum-frame-bytes
               ::protocol/maximum-frame-bytes
               protocol/maximum-frame-bytes})
             (uds/read-frame (one-byte-input held))))
      (is (= 1 @opened))
      (is (= 1 (count @(::uds/connections server))))

      (dotimes [_ 4]
        (swap! rejected conj (uds/connect! path)))
      (Thread/sleep 50)
      (is (<= (open-file-descriptor-count)
              (+ baseline-fds (count @rejected) 7))
          "cap+1 clients retain only their FDs plus one rejection allowance")
      (doseq [channel @rejected]
        (is (= capacity-failure
               (uds/read-frame (one-byte-input channel)))
            "each cap+1 client receives the exact fragmented rejection")
        (.close ^SocketChannel channel))
      (is (= 1 @opened) "rejection never calls open-connection!")
      (is (= 1 (count @(::uds/connections server)))
          "rejection sessions are not admitted")
      (is (.isAlive ^Thread (::uds/worker server))
          "the selector remains responsive after repeated rejection writes")
      (wait-until! "capacity rejection FD release"
                   #(<= (open-file-descriptor-count) (+ baseline-fds 7)))

      (.close held)
      (wait-until! "held admitted session cleanup" #(= 1 @closed))
      (wait-until! "held admission release"
                   #(empty? @(::uds/connections server)))
      (with-open [next (uds/connect! path)]
        (write-bytes! next (frame-bytes opening) Integer/MAX_VALUE)
        (is (= true (::protocol/success?
                     (uds/read-frame (one-byte-input next))))
            "the next session admits immediately after capacity releases"))
      (is (= 2 @opened))
      (finally
        (try (.close held) (catch Throwable _))
        (run! #(try (.close ^SocketChannel %) (catch Throwable _)) @rejected)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest session-open-fragmentation-and-coalescing-switches-before-semantics
  (let [path (socket-path "session-open-phase-switch")
        opened (atom 0)
        handled (atom [])
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection! (fn [_control] (swap! opened inc) (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner request _frame-bytes complete!]
            (swap! handled conj request)
            (complete! (protocol/success
                        {::protocol/request-id (::protocol/request-id request)
                         ::protocol/pong? true})))})
        opening
        (protocol/session-open-request
         {::protocol/maximum-frame-bytes protocol/maximum-frame-bytes})
        ping (protocol/ping-request {::protocol/request-id "coalesced/ping"})]
    (try
      (with-open [channel (uds/connect! path)]
        (write-bytes! channel
                      (joined-bytes (frame-bytes opening) (frame-bytes ping)) 1)
        (let [input (one-byte-input channel)]
          (is (= protocol/session-open-request-id
                 (::protocol/request-id (uds/read-frame input))))
          (is (= {::protocol/success? true
                  ::protocol/request-id "coalesced/ping"
                  ::protocol/pong? true}
                 (uds/read-frame input)))))
      (is (= 1 @opened))
      (is (= [ping] @handled)
          "only the post-open semantic request reaches the handler")
      (finally
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest session-open-rejects-version-and-ordinary-first-without-an-owner
  (let [path (socket-path "session-open-rejections")
        opened (atom 0)
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection! (fn [_control] (swap! opened inc) (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler (fn [_owner _request _frame-bytes _complete!] nil)})]
    (try
      (with-open [channel (uds/connect! path)]
        (write-bytes!
         channel
         (frame-bytes
          (assoc (protocol/session-open-request
                  {::protocol/maximum-frame-bytes protocol/maximum-frame-bytes})
                 ::protocol/version 12))
         Integer/MAX_VALUE)
        (is (= (protocol/incompatible-version-failure
                {::protocol/peer-version 12})
               (uds/read-frame (one-byte-input channel)))))
      (with-open [channel (uds/connect! path)]
        (let [request (protocol/ping-request
                       {::protocol/request-id "ordinary/first"})]
          (write-bytes! channel (frame-bytes request) Integer/MAX_VALUE)
          (is (= (protocol/session-open-required-failure
                  {::protocol/request-id "ordinary/first"})
                 (uds/read-frame (one-byte-input channel))))))
      (doseq [request [(assoc (protocol/session-open-request
                               {::protocol/maximum-frame-bytes
                                protocol/maximum-frame-bytes})
                              ::protocol/request-id "session/wrong")
                       (assoc (protocol/session-open-request
                               {::protocol/maximum-frame-bytes
                                protocol/maximum-frame-bytes})
                              ::unexpected true)]]
        (with-open [channel (uds/connect! path)]
          (write-bytes! channel (frame-bytes request) Integer/MAX_VALUE)
          (let [response (uds/read-frame (one-byte-input channel))]
            (is (false? (::protocol/success? response)))
            (is (= protocol/protocol-error
                   (::protocol/error-kind response))))))
      (is (zero? @opened))
      (finally
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest opening-success-must-carry-the-exact-minimum-agreement
  (let [valid-opening-success? (var-get #'uds/valid-opening-success?)
        configured 65536
        response (protocol/session-open-success
                  {::protocol/configured-maximum-frame-bytes configured
                   ::protocol/maximum-frame-bytes configured})
        decoded (uds/decode (uds/encode response))]
    (is (instance? Long (::protocol/configured-maximum-frame-bytes decoded))
        "Transit decodes a JVM frame ceiling as Long, not Integer")
    (is (valid-opening-success? decoded protocol/maximum-frame-bytes))
    (is (false?
         (valid-opening-success?
          (assoc response ::protocol/maximum-frame-bytes (inc configured))
          protocol/maximum-frame-bytes)))))

(deftest negotiated-session-ceiling-governs-both-directions
  (let [path (socket-path "session-frame-ceiling")
        ceiling 65536
        legal (apply str (repeat 60000 "x"))
        oversized (apply str (repeat 70000 "x"))
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/maximum-frame-bytes ceiling
          ::uds/open-connection! (fn [_control] (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner request _frame-bytes complete!]
            (complete!
             {::protocol/request-id (::protocol/request-id request)
              ::response (if (= :oversized (::response request))
                           oversized
                           (::response request))}))})
        session (uds/open-session! path)]
    (try
      (is (= ceiling (::protocol/configured-maximum-frame-bytes session)))
      (is (= ceiling (::protocol/maximum-frame-bytes session)))
      (is (= legal
             (::response
              (uds/call! {::uds/session session
                          ::uds/message
                          {::protocol/request-id "frame/legal"
                           ::response legal}}))))
      (let [failure
            (try
              (uds/call! {::uds/session session
                          ::uds/message
                          {::protocol/request-id "frame/outbound"
                           ::response oversized}})
              nil
              (catch clojure.lang.ExceptionInfo exception
                (ex-data exception)))]
        (is (= :seon.config.database.transport/maximum-frame-bytes
               (::protocol/configuration-key failure))))
      (is (= (protocol/frame-too-large-failure
              {::protocol/request-id "frame/response"
               ::protocol/maximum-frame-bytes ceiling})
             (uds/call! {::uds/session session
                         ::uds/message
                         {::protocol/request-id "frame/response"
                          ::response :oversized}})))
      (finally
        (uds/close-session! session)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest oversized-inbound-length-returns-session-control-and-closes
  (let [path (socket-path "session-inbound-ceiling")
        ceiling 65536
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/maximum-frame-bytes ceiling
          ::uds/open-connection! (fn [_control] (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler (fn [_owner _request _frame-bytes _complete!] nil)})
        session (uds/open-session! path)
        channel (::uds/channel session)]
    (try
      (write-bytes! channel
                    (.array (doto (ByteBuffer/allocate Integer/BYTES)
                              (.putInt (inc ceiling))))
                    Integer/MAX_VALUE)
      (is (= (protocol/frame-too-large-failure
              {::protocol/request-id protocol/session-control-request-id
               ::protocol/maximum-frame-bytes ceiling})
             (uds/read-frame (one-byte-input channel)
                             protocol/session-open-maximum-frame-bytes)))
      (is (nil? (uds/read-frame (Channels/newInputStream channel))))
      (finally
        (try (uds/close-session! session) (catch Throwable _))
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest transit-roundtrip-preserves-native-protocol-values
  (let [request-id (str (UUID/randomUUID))
        instant (Date. 1720000000000)
        message
        (protocol/transaction-request
         {::protocol/request-id request-id
          :seon.db/db database
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

(deftest transit-decodes-aggregate-query-lists-as-eager-protocol-data
  (let [query-form
        '[:find (count ?entity) . :where [?entity :person/name]]
        direct
        (protocol/query-request
         {::protocol/request-id "aggregate/direct"
          :seon.db/db database
          ::protocol/query-form query-form
          ::protocol/arguments []})
        many
        (protocol/execute-many-request
         {::protocol/request-id "aggregate/many"
          ::protocol/members
          [{::protocol/operation protocol/query-operation
            :seon.db/db database
            ::protocol/query-form query-form
            ::protocol/arguments []}]})
        decoded-direct (uds/decode (uds/encode direct))
        decoded-many (uds/decode (uds/encode many))
        direct-aggregate (get-in decoded-direct [::protocol/query-form 1])
        many-aggregate
        (get-in decoded-many [::protocol/members 0 ::protocol/query-form 1])]
    (is (list? direct-aggregate))
    (is (list? many-aggregate))
    (is (= '(count ?entity) direct-aggregate many-aggregate))
    (is (= direct decoded-direct))
    (is (= many decoded-many))
    (is (protocol/valid-request? decoded-direct))
    (is (protocol/valid-request? decoded-many))))

(deftest execute-many-reuses-existing-read-shapes-with-one-public-identity
  (let [members [{::protocol/operation protocol/query-operation
                  :seon.db/db database
                  ::protocol/query-form '[:find ?e :where [?e :db/ident]]
                  ::protocol/arguments []}
                 {::protocol/operation protocol/pull-operation
                  :seon.db/db database
                  ::protocol/selector '[*]
                  ::protocol/entity-id 1}
                 {::protocol/operation protocol/pull-many-operation
                  :seon.db/db database
                  ::protocol/selector '[:db/ident]
                  ::protocol/entity-ids [1 2]}]
        request (protocol/execute-many-request
                 {::protocol/request-id "many-1"
                  ::protocol/members members})]
    (is (= 14 protocol/current-version))
    (is (protocol/valid-request? request))
    (is (= request (uds/decode (uds/encode request))))
    (is (= [database database database]
           (mapv :seon.db/db (::protocol/members request))))))

(deftest database-acquisition-is-closed-correlated-and-transit-stable
  (let [request (protocol/acquire-database-request
                 {::protocol/request-id "acquire/alpha"
                  ::protocol/database-name "alpha"
                  ::protocol/database-advanced? false})
        response (protocol/success
                  {::protocol/request-id "acquire/alpha"
                   ::protocol/database-name "alpha"
                   :seon.db/db database
                   ::protocol/acquired? true})]
    (is (protocol/valid-request? request))
    (is (protocol/valid-response? response))
    (is (= request (uds/decode (uds/encode request))))
    (is (false? (::protocol/database-advanced? request)))
    (is (= response (uds/decode (uds/encode response))))
    (is (false? (protocol/valid-request?
                 (assoc request :unexpected/field true)))
        "acquisition requests reject transport-private or unknown fields")
    (is (false? (protocol/valid-response?
                 (assoc response :unexpected/field true)))
        "acquisition responses reject transport-private or unknown fields")
    (is (false? (protocol/valid-response?
                 (assoc response ::protocol/acquired? :yes))))))

(deftest transaction-branch-head-request-and-response-are-transit-stable
  (let [head
        {::branch/store-id
         #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
         ::branch/name :db
         ::branch/commit-id
         #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
         ::branch/basis-t 536870929}
        original
        (assoc head
               ::branch/commit-id
               #uuid "6a56b425-30e5-53b3-86c1-e31381023716"
               ::branch/basis-t 536870920)
        request
        (protocol/resolve-transaction-branch-head-request
         {::protocol/request-id "branch-head/resolve"
          ::protocol/database-name "default"
          ::protocol/containing-branch-head head
          ::protocol/transaction-id (::branch/basis-t original)})
        response (protocol/success
                  {::protocol/request-id "branch-head/resolve"
                   ::protocol/branch-head original})]
    (is (protocol/valid-request? request))
    (is (protocol/valid-response? response))
    (is (= request (uds/decode (uds/encode request))))
    (is (= response (uds/decode (uds/encode response))))))

(deftest ensure-request-roundtrip-preserves-explicit-connection-id
  (let [connection-id
        [#uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
         :experiment/cold]
        request
        (protocol/ensure-database-request
         {::protocol/request-id "ensure/connection-id"
          ::protocol/database-name "experiment-cold"
          ::protocol/backend :file
          ::protocol/database-path "data/clusters/default/db"
          ::branch/connection-id connection-id})
        decoded (uds/decode (uds/encode request))]
    (is (protocol/valid-request? decoded))
    (is (= connection-id (::branch/connection-id decoded)))
    (is (= request decoded))))

(deftest lifecycle-requests-are-closed-and-transit-stable
  (let [source
        {::branch/store-id
         #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
         ::branch/name :db
         ::branch/commit-id
         #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
         ::branch/basis-t 536870929}
        target-connection-id
        (assoc (branch/connection-id source) 1 :experiment/lifecycle)
        target-head (assoc source ::branch/name :experiment/lifecycle)
        requests
        [(protocol/create-branch-request
          {::protocol/request-id "lifecycle/create"
           ::protocol/source-database-name "default"
           ::protocol/target-database-name "default-lifecycle"
           ::protocol/source-branch-head source
           ::protocol/expected-source-head source
           ::protocol/target-branch :experiment/lifecycle})
        (protocol/release-database-request
          {::protocol/request-id "lifecycle/release"
           :seon.db/db (assoc database
                              :db-name "default-lifecycle"
                              :datahike/commit-id (::branch/commit-id
                                                   target-head))})
         (protocol/delete-branch-request
          {::protocol/request-id "lifecycle/delete"
           ::protocol/source-database-name "default"
           ::protocol/target-database-name "default-lifecycle"
           ::protocol/target-connection-id target-connection-id
           ::protocol/expected-target-head target-head})]]
    (is (every? protocol/valid-request? requests))
    (is (= requests (mapv #(uds/decode (uds/encode %)) requests)))
    (is (every? false?
                (map #(protocol/valid-request?
                       (assoc % :unexpected/field true))
                     requests))
        "lifecycle requests reject unknown fields")
    (is (false? (protocol/valid-request?
                 (dissoc (first requests) ::protocol/source-branch-head))))))

(deftest synchronous-call-preserves-complete-lifecycle-values
  (let [path (socket-path "transport-lifecycle-call")
        source
        {::branch/store-id
         #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
         ::branch/name :db
         ::branch/commit-id
         #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
         ::branch/basis-t 536870929}
        target-connection-id
        (assoc (branch/connection-id source) 1 :experiment/portable-call)
        request
        (protocol/create-branch-request
         {::protocol/request-id "lifecycle/call"
          ::protocol/source-database-name "default"
          ::protocol/target-database-name "default-portable-call"
          ::protocol/source-branch-head source
          ::protocol/expected-source-head source
          ::protocol/target-branch :experiment/portable-call})
        response
        (protocol/success
         {::protocol/request-id "lifecycle/call"
          ::protocol/target-database-name "default-portable-call"
          ::protocol/target-connection-id target-connection-id
          ::protocol/branch-head
          (assoc source ::branch/name :experiment/portable-call)
          ::protocol/backend :file
          ::protocol/database-path "data/clusters/default/db"
          ::protocol/created? true
          ::protocol/adopted? false})
        seen (atom [])
        server
        (request-server!
         path
         (fn [_owner message _frame-bytes complete!]
           (swap! seen conj message)
           (complete! response))
         (constantly nil))]
    (try
      (let [session (uds/open-session! path)]
        (try
          (let [actual
                (uds/call! {::uds/session session ::uds/message request})]
          (is (= [request] @seen))
          (is (= response actual))
            (is (protocol/valid-response? actual)))
          (finally (uds/close-session! session))))
      (finally
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest request-server-delivers-maps-without-interpreting-them
  (let [path (socket-path "transport-request")
        seen (atom [])
        server
        (request-server!
         path
         (fn [_owner request _frame-bytes complete!]
           (swap! seen conj request)
           (complete! (protocol/success
                       {::protocol/request-id (::protocol/request-id request)
                        ::protocol/pong? true})))
         (constantly nil))]
    (try
      (let [session (uds/open-session! path)]
        (try
          (let [request (protocol/ping-request
                         {::protocol/request-id "transport/ping"})
                response (uds/call! {::uds/session session
                                     ::uds/message request})]
          (is (= [request] @seen))
          (is (= {::protocol/success? true
                  ::protocol/request-id "transport/ping"
                  ::protocol/pong? true}
                   response)))
          (finally (uds/close-session! session))))
      (finally
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest failed-request-server-start-closes-native-resources
  (let [missing-parent
        (File. "tmp" (str "missing-socket-parent-" (random-uuid)))
        path (.getAbsolutePath (File. missing-parent "request.sock"))
        before (open-file-descriptor-count)]
    (dotimes [_ 16]
      (is (thrown?
           Throwable
           (uds/start-request-server!
            {::uds/socket-path path
             ::uds/open-connection! (fn [_control] (Object.))
             ::uds/close-connection! (constantly nil)
             ::uds/handler
             (fn [_owner _request _frame-bytes _complete!] nil)}))))
    (is (<= (open-file-descriptor-count) (+ before 2))
        "failed bind closes its server channel and selector")))

(deftest request-server-reserves-exact-input-before-payload-allocation
  (let [path (socket-path "transport-input-reservation")
        request {::request :reserved}
        payload (uds/encode request)
        frame-size (+ Integer/BYTES (alength payload))
        seen-frame-bytes (promise)
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/maximum-input-bytes frame-size
          ::uds/open-connection! (fn [_control] (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner _request frame-bytes complete!]
            (deliver seen-frame-bytes frame-bytes)
            (complete! {::response :ok}))})]
    (try
      (with-open [first (open-admitted-channel! path)
                  second (open-admitted-channel! path)]
        (write-bytes! first
                      (.array (doto (ByteBuffer/allocate Integer/BYTES)
                                (.putInt (alength payload))))
                      Integer/MAX_VALUE)
        (wait-until! "exact input reservation"
                     #(= frame-size @(::uds/authority-input-bytes server)))
        (write-bytes! second
                      (.array (doto (ByteBuffer/allocate Integer/BYTES)
                                (.putInt (alength payload))))
                      Integer/MAX_VALUE)
        (Thread/sleep 25)
        (is (= frame-size @(::uds/authority-input-bytes server))
            "another session cannot allocate beyond the authority byte bound")
        (write-bytes! first payload Integer/MAX_VALUE)
        (is (= frame-size (deref seen-frame-bytes 2000 ::missing-frame-size)))
        (is (= {::response :ok}
               (uds/read-frame (Channels/newInputStream first))))
        (.close second)
        (wait-until! "input and response release"
                     #(and (zero? @(::uds/authority-input-bytes server))
                           (zero? @(::uds/authority-response-slot-count
                                     server)))))
      (finally
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest request-server-close-drains-admitted-work-and-rejects-later-admission
  (let [path (socket-path "transport-drain")
        request {:transport-drain/request "accepted"}
        entered (promise)
        release-handler (promise)
        server
        (request-server!
         path
         (fn [_owner message _frame-bytes complete!]
           (deliver entered message)
           (future
             @release-handler
             (complete! {:transport-drain/response "committed"})))
         (constantly nil))
        client
        (future
          (let [session (uds/open-session! path)]
            (try
              (uds/call! {::uds/session session ::uds/message request})
              (finally (uds/close-session! session)))))]
    (try
      (is (= request (deref entered 2000 ::handler-not-entered)))
      (let [close (future (uds/close-request-server! server))]
        (is (= ::still-draining (deref close 100 ::still-draining))
            "close waits for the admitted handler")
        (deliver release-handler true)
        (is (= {:transport-drain/response "committed"}
               (deref client 2000 ::client-timeout))
            "the admitted response is delivered before its connection closes")
        (is (= {::uds/graceful? true
                ::uds/forced-connections 0
                ::uds/selector-stopped? true
                ::uds/workers-stopped? true
                ::uds/cleanup-stopped? true}
               (deref close 2000 ::close-timeout)))
        (is (thrown? Throwable (open-admitted-channel! path))
            "a closed request server accepts no later connection"))
      (finally
        (deliver release-handler true)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest request-server-close-has-a-bounded-backstop
  (let [path (socket-path "transport-bounded-close")
        entered (promise)
        closed (atom 0)
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/shutdown-timeout-ms 50
          ::uds/open-connection! (fn [_control] (Object.))
          ::uds/close-connection! (fn [_owner] (swap! closed inc))
          ::uds/handler
          (fn [_owner request _frame-bytes _complete!]
            (deliver entered request))})
        channel (open-admitted-channel! path)]
    (try
      (write-bytes! channel (frame-bytes {::request :never-completes})
                    Integer/MAX_VALUE)
      (is (= {::request :never-completes}
             (deref entered 2000 ::handler-not-entered)))
      (let [started (System/nanoTime)
            result (uds/close-request-server! server)]
        (is (< (/ (- (System/nanoTime) started) 1000000.0) 1000.0)
            "one broken handler cannot wedge authority shutdown")
        (is (false? (::uds/graceful? result)))
        (is (= 1 (::uds/forced-connections result)))
        (is (true? (::uds/selector-stopped? result))))
      (wait-until! "forced connection cleanup" #(= 1 @closed))
      (finally
        (.close channel)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest request-server-admits-fragmented-and-coalesced-frames-in-order
  (let [path (socket-path "transport-linear-input")
        seen (atom [])
        owners (atom [])
        server
        (request-server!
         path
         (fn [owner request _frame-bytes complete!]
           (swap! owners conj owner)
           (swap! seen conj (::request request))
           (complete! {::response (::request request)}))
         (constantly nil))]
    (try
      (with-open [channel (open-admitted-channel! path)]
        (write-bytes! channel (frame-bytes {::request :fragmented}) 1)
        (write-bytes!
         channel
         (joined-bytes (frame-bytes {::request :coalesced-a})
                       (frame-bytes {::request :coalesced-b}))
         Integer/MAX_VALUE)
        (let [input (Channels/newInputStream channel)
              responses (repeatedly 3 #(uds/read-frame input))]
          (is (= #{:fragmented :coalesced-a :coalesced-b}
                 (set (map ::response responses))))
          (is (= [:fragmented :coalesced-a :coalesced-b] @seen)
              "decode and admission preserve one connection's byte order")
          (is (every? #(identical? (first @owners) %) (rest @owners))
              "every request receives the exact owner returned at open")))
      (finally
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest request-session-pauses-reading-until-response-capacity-is-released
  (let [path (socket-path "read-pressure")
        capacity 2
        entered (atom [])
        completions (atom {})
        closed (atom 0)
        requests (mapv (fn [request-number]
                         {::request request-number})
                       (range (inc capacity)))
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/maximum-response-slots capacity
          ::uds/maximum-session-response-slots capacity
          ::uds/open-connection! (fn [_control] (Object.))
          ::uds/close-connection! (fn [_owner] (swap! closed inc))
          ::uds/handler
          (fn [_owner request _frame-bytes complete!]
            (swap! entered conj (::request request))
            (swap! completions assoc (::request request) complete!))})]
    (try
      (with-open [channel (open-admitted-channel! path)]
        (write-bytes! channel
                      (apply joined-bytes (map frame-bytes requests))
                      Integer/MAX_VALUE)
        (wait-until! "requests admitted to the session capacity"
                     #(= capacity (count @entered)))
        (Thread/sleep 25)
        (is (= [0 1] @entered))
        (is (= 1 (count @(::uds/connections server)))
            "temporary response pressure retains the physical session")
        (is (zero? @closed))

        ((get @completions 0) {::response 0})
        (let [input (Channels/newInputStream channel)]
          (is (= {::response 0} (uds/read-frame input)))
          (wait-until! "paused request admitted after one response"
                       #(= (inc capacity) (count @entered)))
          (is (= [0 1 2] @entered)
              "the retained frame header resumes in request order")
          ((get @completions 1) {::response 1})
          ((get @completions 2) {::response 2})
          (is (= [{::response 1} {::response 2}]
                 [(uds/read-frame input) (uds/read-frame input)])))
        (wait-until! "request pressure reservations released"
                     #(and (zero? @(::uds/authority-response-slot-count server))
                           (zero? @(::uds/authority-input-bytes server))))
        (is (zero? @closed)))
      (finally
        (run! (fn [[request-number complete!]]
                (complete! {::response request-number}))
              @completions)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest request-server-writes-responses-when-they-complete
  (let [path (socket-path "transport-out-of-order")
        completions (atom {})
        server
        (request-server!
         path
         (fn [_owner request _frame-bytes complete!]
           (swap! completions assoc (::request request) complete!))
         (constantly nil))]
    (try
      (with-open [channel (open-admitted-channel! path)]
        (write-bytes!
         channel
         (joined-bytes (frame-bytes {::request :first})
                       (frame-bytes {::request :second}))
         Integer/MAX_VALUE)
        (wait-until! "both request admissions" #(= 2 (count @completions)))
        ((get @completions :second) {::response :second})
        (is (= {::response :second}
               (uds/read-frame (Channels/newInputStream channel))))
        ((get @completions :first) {::response :first})
        (is (= {::response :first}
               (uds/read-frame (Channels/newInputStream channel)))))
      (finally
        (run! #(% {::response :cleanup}) (vals @completions))
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest physical-session-send-owns-one-event-until-full-write
  (let [path (socket-path "transport-addressed-send")
        control (promise)
        release-encode (CountDownLatch. 1)
        original-frame @#'seon.db.transport.uds/message-frame
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection!
          (fn [connection-control]
            (deliver control connection-control)
            (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner _request _frame-bytes _complete!] nil)})
        channel (open-admitted-channel! path)
        {close! ::uds/close! send! ::uds/send!}
        (deref control 2000 ::not-opened)]
    (try
      (with-redefs-fn
        {#'seon.db.transport.uds/message-frame
         (fn [message & [ceiling]]
           (.await release-encode)
           (if ceiling
             (original-frame message ceiling)
             (original-frame message)))}
        (fn []
          (let [first-result (send! {::event 1})]
            (is (= uds/send-accepted (::uds/send-status first-result)))
            (is (= uds/send-session-full
                   (::uds/send-status (send! {::event 2})))
                "the slot is owned while the first event is still encoding")
            (.countDown release-encode)
            (let [input (Channels/newInputStream channel)]
              (is (= {::event 1} (uds/read-frame input)))
              (is (= uds/send-accepted
                     (deref (::uds/send-completion first-result)
                            2000 ::not-complete)))
              (let [second-result (send! {::event 2})]
                (is (= uds/send-accepted (::uds/send-status second-result)))
                (is (= {::event 2} (uds/read-frame input)))
                (is (= uds/send-accepted
                       (deref (::uds/send-completion second-result)
                              2000 ::not-complete))))))))
      (wait-until! "addressed response slots"
                   #(zero? @(::uds/authority-response-slot-count server)))
      (close!)
      (is (= uds/send-closed
             (::uds/send-status
              (send! {::event :after-close-request}))))
      (wait-until! "addressed session close"
                   #(empty? @(::uds/connections server)))
      (is (= uds/send-closed
             (::uds/send-status (send! {::event :after-close}))))
      (finally
        (.countDown release-encode)
        (.close channel)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest addressed-send-does-not-replace-a-partial-requests-response-slot
  (let [path (socket-path "transport-send-during-request")
        control (promise)
        request {::request :partial}
        payload (uds/encode request)
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection!
          (fn [connection-control]
            (deliver control connection-control)
            (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner message _frame-bytes complete!]
            (complete! {::response (::request message)}))})
        channel (open-admitted-channel! path)
        {send! ::uds/send!} (deref control 2000 ::not-opened)
        input (Channels/newInputStream channel)]
    (try
      (write-bytes! channel
                    (.array (doto (ByteBuffer/allocate Integer/BYTES)
                              (.putInt (alength payload))))
                    Integer/MAX_VALUE)
      (wait-until! "partial request slot"
                   #(= 1 @(::uds/authority-response-slot-count server)))
      (is (= uds/send-accepted
             (::uds/send-status (send! {::event :while-partial}))))
      (is (= {::event :while-partial} (uds/read-frame input)))
      (write-bytes! channel payload Integer/MAX_VALUE)
      (is (= {::response :partial} (uds/read-frame input)))
      (wait-until! "event and request slots released"
                   #(and (zero? @(::uds/authority-response-slot-count server))
                         (zero? @(::uds/authority-output-bytes server))))
      (finally
        (.close channel)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest addressed-send-bounds-one-physical-event-and-preserves-order
  (let [path (socket-path "transport-send-admission-order")
        control (promise)
        first-entered (promise)
        second-entered (promise)
        release-first (CountDownLatch. 1)
        caller (Thread/currentThread)
        original-frame @#'seon.db.transport.uds/message-frame
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection!
          (fn [connection-control]
            (deliver control connection-control)
            (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner _request _frame-bytes _complete!] nil)})
        channel (open-admitted-channel! path)
        {send! ::uds/send!} (deref control 2000 ::not-opened)]
    (try
      (with-redefs-fn
        {#'seon.db.transport.uds/message-frame
         (fn [message & [ceiling]]
           (case (::event message)
             :first
             (do
               (deliver first-entered (Thread/currentThread))
               (.await release-first)
               (if ceiling
                 (original-frame message ceiling)
                 (original-frame message)))

             :second
             (do
               (deliver second-entered (Thread/currentThread))
               (if ceiling
                 (original-frame message ceiling)
                 (original-frame message)))

             (if ceiling
               (original-frame message ceiling)
               (original-frame message))))}
        (fn []
          (let [first-result (send! {::event :first})]
            (is (= uds/send-accepted (::uds/send-status first-result)))
            (is (not (identical? caller
                                 (deref first-entered 2000 ::not-encoding)))
                "the caller admits while a codec worker performs Transit")
            (let [second-result (send! {::event :second})]
              (is (= uds/send-session-full
                     (::uds/send-status second-result)))
              (is (= ::not-yet
                     (deref second-entered 50 ::not-yet))
                  "a refused second event is never encoded")
              (.countDown release-first)
              (let [input (Channels/newInputStream channel)]
                (is (= {::event :first} (uds/read-frame input)))
                (is (= uds/send-accepted
                       (deref (::uds/send-completion first-result)
                              2000 ::not-complete)))
                (let [retried (send! {::event :second})]
                  (is (= uds/send-accepted (::uds/send-status retried)))
                  (is (not= ::not-encoding
                            (deref second-entered 2000 ::not-encoding)))
                  (is (= {::event :second} (uds/read-frame input)))
                  (is (= uds/send-accepted
                         (deref (::uds/send-completion retried)
                                2000 ::not-complete)))))))))
      (finally
        (.countDown release-first)
        (.close channel)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest idle-encoding-handoff-cannot-strand-concurrent-admission
  (let [poll-entered (promise)
        release-poll (CountDownLatch. 1)
        queue
        (proxy [ArrayDeque] []
          (pollFirst []
            (deliver poll-entered true)
            (.await release-poll)
            (proxy-super pollFirst)))
        send-lock (Object.)
        encoding-active? (AtomicReference. true)
        session {::uds/send-lock send-lock
                 ::uds/pending-encodes queue
                 ::uds/encoding-active? encoding-active?}
        drain-handoff
        (future
          (#'seon.db.transport.uds/take-pending-encode! session))]
    (try
      (is (= true (deref poll-entered 2000 ::poll-not-entered)))
      (let [admission
            (future
              (locking send-lock
                (.addLast ^ArrayDeque queue {::event :arrived-at-handoff})
                (.get encoding-active?)))]
        (is (= ::blocked (deref admission 50 ::blocked))
            "admission cannot enter after the empty poll but before idle")
        (.countDown release-poll)
        (is (nil? (deref drain-handoff 2000 ::handoff-blocked)))
        (is (false? (deref admission 2000 ::admission-blocked))
            "admission observes idle and therefore schedules a new drain")
        (is (= {::event :arrived-at-handoff} (.peekFirst queue))))
      (finally
        (.countDown release-poll)))))

(deftest separate-sessions-encode-in-parallel
  (let [path (socket-path "transport-send-parallel")
        controls (atom [])
        both-entered (CountDownLatch. 2)
        release-encodes (CountDownLatch. 1)
        original-frame @#'seon.db.transport.uds/message-frame
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection!
          (fn [connection-control]
            (swap! controls conj connection-control)
            (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner _request _frame-bytes _complete!] nil)})
        channels [(open-admitted-channel! path)
                  (open-admitted-channel! path)]]
    (try
      (wait-until! "two physical session controls" #(= 2 (count @controls)))
      (with-redefs-fn
        {#'seon.db.transport.uds/message-frame
         (fn [message & [ceiling]]
           (.countDown both-entered)
           (.await release-encodes)
           (if ceiling
             (original-frame message ceiling)
             (original-frame message)))}
        (fn []
          (let [results
                (mapv (fn [control n]
                        ((::uds/send! control) {::event n}))
                      @controls [1 2])]
            (is (every? #(= uds/send-accepted (::uds/send-status %))
                        results))
            (is (.await both-entered 2 TimeUnit/SECONDS)
                "different sessions occupy different bounded codec workers")
            (.countDown release-encodes)
            (is (= #{1 2}
                   (into #{}
                         (map (fn [channel]
                                (::event
                                 (uds/read-frame
                                  (Channels/newInputStream channel)))))
                         channels)))
            (is (every? #(= uds/send-accepted
                            (deref (::uds/send-completion %)
                                   2000 ::not-complete))
                        results)))))
      (finally
        (.countDown release-encodes)
        (run! #(.close ^SocketChannel %) channels)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest small-fanout-does-not-reserve-maximum-frame-bytes
  (let [path (socket-path "transport-small-fanout")
        controls (atom [])
        release-encodes (CountDownLatch. 1)
        original-frame @#'seon.db.transport.uds/message-frame
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection!
          (fn [connection-control]
            (swap! controls conj connection-control)
            (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner _request _frame-bytes _complete!] nil)})
        channels
        (reduce
         (fn [channels index]
           (let [channel (open-admitted-channel! path)]
             (wait-until! (str "physical session control " index)
                          #(= (inc index) (count @controls)))
             (conj channels channel)))
         [] (range 64))]
    (try
      (wait-until! "64 physical session controls" #(= 64 (count @controls)))
      (with-redefs-fn
        {#'seon.db.transport.uds/message-frame
         (fn [message & [ceiling]]
           (.await release-encodes)
           (if ceiling
             (original-frame message ceiling)
             (original-frame message)))}
        (fn []
          (let [results
                (mapv (fn [control index]
                        ((::uds/send! control) {::event index}))
                      @controls (range 64))]
            (is (every? #(= uds/send-accepted (::uds/send-status %)) results)
                "all small events admit while encoding is blocked")
            (is (zero? @(::uds/authority-output-bytes server))
                "unencoded messages retain slots, not maximum-size bytes")
            (.countDown release-encodes)
            (is (= (set (range 64))
                   (into #{}
                         (map (fn [channel]
                                (::event
                                 (uds/read-frame
                                  (Channels/newInputStream channel)))))
                         channels)))
            (is (every? #(= uds/send-accepted
                            (deref (::uds/send-completion %)
                                   5000 ::not-complete))
                        results)))))
      (finally
        (.countDown release-encodes)
        (run! #(try (.close ^SocketChannel %) (catch Throwable _)) channels)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest exact-encoded-byte-pressure-is-session-local
  (let [path (socket-path "transport-exact-pressure")
        controls (atom [])
        closed (atom [])
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/maximum-output-bytes (* 1024 1024)
          ::uds/maximum-session-output-bytes 128
          ::uds/open-connection!
          (fn [connection-control]
            (let [owner (assoc connection-control ::connection-id
                               (random-uuid))]
              (swap! controls conj owner)
              owner))
          ::uds/close-connection! #(swap! closed conj (::connection-id %))
          ::uds/handler
          (fn [_owner _request _frame-bytes _complete!] nil)})
        channels [(open-admitted-channel! path)
                  (open-admitted-channel! path)]]
    (try
      (wait-until! "two exact-pressure sessions" #(= 2 (count @controls)))
      (let [[oversized healthy] @controls
            [oversized-channel healthy-channel] channels
            oversized-result
            ((::uds/send! oversized) {::event (apply str (repeat 1024 "x"))})]
        (is (= uds/send-accepted (::uds/send-status oversized-result)))
        (is (= ::not-complete
               (deref (::uds/send-completion oversized-result)
                      50 ::not-complete))
            "encoded event waits instead of closing under output pressure")
        (is (empty? @closed))
        (let [healthy-result ((::uds/send! healthy) {::event :healthy})]
          (is (= uds/send-accepted (::uds/send-status healthy-result)))
          (is (= {::event :healthy}
                 (uds/read-frame (Channels/newInputStream healthy-channel))))
          (is (= uds/send-accepted
                 (deref (::uds/send-completion healthy-result)
                        2000 ::not-complete))))
        (.close ^SocketChannel oversized-channel)
        (is (= uds/send-closed
               (deref (::uds/send-completion oversized-result)
                      2000 ::not-complete)))
        (wait-until! "only explicitly closed session cleanup"
                     #(= #{(::connection-id oversized)} (set @closed)))
        (wait-until! "exact output byte release"
                     #(zero? @(::uds/authority-output-bytes server))))
      (finally
        (run! #(try (.close ^SocketChannel %) (catch Throwable _)) channels)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest unsolicited-event-pressure-is-bounded-without-response-slots
  (let [path (socket-path "transport-session-pressure")
        controls (atom [])
        closed (atom [])
        slow-entered (promise)
        release-slow (CountDownLatch. 1)
        original-frame @#'seon.db.transport.uds/message-frame
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/maximum-response-slots 4
          ::uds/maximum-session-response-slots 1
          ::uds/open-connection!
          (fn [connection-control]
            (let [owner (assoc connection-control ::connection-id (random-uuid))]
              (swap! controls conj owner)
              owner))
          ::uds/close-connection! #(swap! closed conj (::connection-id %))
          ::uds/handler
          (fn [_owner _request _frame-bytes _complete!] nil)})
        channels [(open-admitted-channel! path)
                  (open-admitted-channel! path)]]
    (try
      (wait-until! "two session owners" #(= 2 (count @controls)))
      (let [[slow healthy] @controls
            [slow-channel healthy-channel] channels]
        (with-redefs-fn
          {#'seon.db.transport.uds/message-frame
           (fn [message & [ceiling]]
             (when (= :slow (::event message))
               (deliver slow-entered true)
               (.await release-slow))
             (if ceiling
               (original-frame message ceiling)
               (original-frame message)))}
          (fn []
            (is (= uds/send-accepted
                   (::uds/send-status ((::uds/send! slow)
                                       {::event :slow}))))
            (is (= true (deref slow-entered 2000 ::not-entered)))
            (is (every?
                 #(= uds/send-session-full (::uds/send-status %))
                 (repeatedly
                  64
                  #((::uds/send! slow) {::event :too-many}))))
            (is (zero? @(::uds/authority-response-slot-count server))
                "one-way events never occupy request-response slots")
            (is (= uds/send-accepted
                   (::uds/send-status ((::uds/send! healthy)
                                       {::event :healthy}))))
            (is (= {::event :healthy}
                   (uds/read-frame (Channels/newInputStream healthy-channel))))
            (Thread/sleep 25)
            (is (empty? @closed))
            (is (= uds/send-accepted
                   (::uds/send-status ((::uds/send! healthy)
                                       {::event :still-healthy}))))
            (is (= {::event :still-healthy}
                   (uds/read-frame (Channels/newInputStream healthy-channel))))
            (.countDown release-slow)
            (is (= {::event :slow}
                   (uds/read-frame (Channels/newInputStream slow-channel))))
            (wait-until! "slow event capacity release"
                         #(= uds/send-accepted
                             (::uds/send-status
                              ((::uds/send! slow) {::event :after-pressure}))))
            (is (= {::event :after-pressure}
                   (uds/read-frame (Channels/newInputStream slow-channel)))))))
      (finally
        (.countDown release-slow)
        (run! #(try (.close ^SocketChannel %) (catch Throwable _)) channels)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest unsolicited-events-do-not-share-response-slot-authority
  (let [path (socket-path "transport-authority-pressure")
        controls (atom [])
        closed (atom 0)
        first-entered (promise)
        release-first (CountDownLatch. 1)
        original-frame @#'seon.db.transport.uds/message-frame
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/maximum-response-slots 1
          ::uds/maximum-session-response-slots 2
          ::uds/open-connection!
          (fn [connection-control]
            (swap! controls conj connection-control)
            (Object.))
          ::uds/close-connection! (fn [_owner] (swap! closed inc))
          ::uds/handler
          (fn [_owner _request _frame-bytes _complete!] nil)})
        channels [(open-admitted-channel! path)
                  (open-admitted-channel! path)]]
    (try
      (wait-until! "two authority-pressure sessions"
                   #(= 2 (count @controls)))
      (let [[first-control current-control] @controls
            [first-channel current-channel] channels]
        (with-redefs-fn
          {#'seon.db.transport.uds/message-frame
           (fn [message & [ceiling]]
             (when (= :occupies-authority (::event message))
               (deliver first-entered true)
               (.await release-first))
             (if ceiling
               (original-frame message ceiling)
               (original-frame message)))}
          (fn []
            (is (= uds/send-accepted
                   (::uds/send-status
                    ((::uds/send! first-control)
                     {::event :occupies-authority}))))
            (is (= true (deref first-entered 2000 ::not-entered)))
            (let [deferred
                  ((::uds/send! current-control) {::event :deferred})]
              (is (= uds/send-accepted (::uds/send-status deferred)))
              (is (zero? @(::uds/authority-response-slot-count server)))
              (is (= {::event :deferred}
                     (uds/read-frame
                      (Channels/newInputStream current-channel))))
              (is (= uds/send-accepted
                     (deref (::uds/send-completion deferred)
                            2000 ::not-complete))))
            (Thread/sleep 25)
            (is (zero? @closed))
            (is (= 2 (count @(::uds/connections server))))
            (.countDown release-first)
            (is (= {::event :occupies-authority}
                   (uds/read-frame (Channels/newInputStream first-channel))))
            (is (= uds/send-accepted
                   (::uds/send-status
                    ((::uds/send! current-control) {::event :retried}))))
            (is (= {::event :retried}
                   (uds/read-frame
                    (Channels/newInputStream current-channel)))))))
      (finally
        (.countDown release-first)
        (run! #(try (.close ^SocketChannel %) (catch Throwable _)) channels)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest asynchronous-encode-failure-is-reported-and-closes-that-session
  (let [path (socket-path "transport-encode-failure")
        control (promise)
        closed (atom 0)
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection!
          (fn [connection-control]
            (deliver control connection-control)
            (Object.))
          ::uds/close-connection! (fn [_owner] (swap! closed inc))
          ::uds/handler
          (fn [_owner _request _frame-bytes _complete!] nil)})
        channel (open-admitted-channel! path)
        {send! ::uds/send!} (deref control 2000 ::not-opened)]
    (try
      (with-redefs-fn
        {#'seon.db.transport.uds/message-frame
         (fn [_message & _]
           (throw (ex-info "deliberate encode failure" {})))}
        (fn []
          (let [result (send! {::event :broken})]
            (is (= uds/send-accepted (::uds/send-status result))
                "admission remains independent from worker encoding")
            (is (= uds/send-encode-failed
                   (deref (::uds/send-completion result)
                          2000 ::not-complete))))))
      (wait-until! "encode-failed session cleanup" #(= 1 @closed))
      (wait-until! "encode-failed resource release"
                   #(and (empty? @(::uds/connections server))
                         (zero? @(::uds/authority-response-slot-count server))
                         (zero? @(::uds/authority-output-bytes server))))
      (finally
        (.close channel)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest degraded-value-records-one-core-fault-and-session-survives
  (let [path (socket-path "transport-codec-degrade")
        control (promise)
        recorded (promise)
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/on-core-error :log
          ::uds/open-connection!
          (fn [connection-control]
            (deliver control connection-control)
            (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner _request _frame-bytes _complete!] nil)})
        channel (open-admitted-channel! path)
        input (Channels/newInputStream channel)
        {send! ::uds/send!} (deref control 2000 ::not-opened)]
    (try
      (with-redefs-fn
        {#'seon.error/record!
         (fn [fault]
           (deliver recorded fault)
           {:seon.error/message "recorded"})}
        (fn []
          (let [first-send (send! {::event :degraded ::value *})
                first-frame (uds/read-frame input)
                second-send (send! {::event :ordinary ::value 42})]
            (is (= uds/send-accepted (::uds/send-status first-send)))
            (is (= uds/send-accepted
                   (deref (::uds/send-completion first-send)
                          2000 ::not-complete)))
            (is (= :degraded (::event first-frame)))
            (is (string? (::value first-frame)))
            (is (= :core (::error/fault (deref recorded 2000 ::not-recorded))))
            (is (= uds/send-accepted (::uds/send-status second-send)))
            (is (= {::event :ordinary ::value 42}
                   (uds/read-frame input)))
            (is (= uds/send-accepted
                   (deref (::uds/send-completion second-send)
                          2000 ::not-complete))))))
      (finally
        (.close channel)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest rejected-event-codec-admission-encodes-inline-once
  (let [path (socket-path "event-inline")
        control (promise)
        callbacks (atom 0)
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection!
          (fn [connection-control]
            (deliver control connection-control)
            (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner _request _frame-bytes _complete!] nil)})
        channel (open-admitted-channel! path)
        {send! ::uds/send!} (deref control 2000 ::not-opened)]
    (try
      (.shutdownNow ^java.util.concurrent.ThreadPoolExecutor (::uds/workers server))
      (let [result
            (send! {::event :inline}
                   (fn [status]
                     (when (= uds/send-accepted status)
                       (swap! callbacks inc))))]
        (is (= uds/send-accepted (::uds/send-status result)))
        (is (= {::event :inline}
               (uds/read-frame (Channels/newInputStream channel))))
        (is (= uds/send-accepted
               (deref (::uds/send-completion result) 2000 ::not-complete)))
        (is (= 1 @callbacks))
        (is (zero? @(::uds/authority-response-slot-count server)))
        (is (zero? @(::uds/authority-output-bytes server))))
      (finally
        (.close channel)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest codec-workers-currently-bound-control-entry
  (let [path (socket-path "transport-control-progress")
        release-heavy (java.util.concurrent.CountDownLatch. 1)
        heavy-entered-holder (atom nil)
        control-entered (promise)
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection!
          (fn [_control] (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner request _frame-bytes complete!]
            (if (= :control (::request request))
              (do
                (deliver control-entered true)
                (complete! {::response :control}))
              (do
                (.countDown ^java.util.concurrent.CountDownLatch
                            @heavy-entered-holder)
                (.await release-heavy)
                (complete! {::response (::request request)}))))})
        worker-count (.getCorePoolSize
                      ^java.util.concurrent.ThreadPoolExecutor
                      (::uds/workers server))
        heavy-entered (java.util.concurrent.CountDownLatch. worker-count)
        _ (reset! heavy-entered-holder heavy-entered)
        channels (mapv (fn [_] (open-admitted-channel! path))
                       (range (inc worker-count)))]
    (try
      (doseq [[channel n] (map vector (take worker-count channels)
                               (range worker-count))]
        (write-bytes! channel (frame-bytes {::request n})
                      Integer/MAX_VALUE))
      (is (.await heavy-entered 2 java.util.concurrent.TimeUnit/SECONDS))
      (write-bytes! (peek channels) (frame-bytes {::request :control})
                    Integer/MAX_VALUE)
      (is (= ::blocked (deref control-entered 100 ::blocked))
          "control cannot enter while heavy handlers own every codec worker")
      (.countDown release-heavy)
      (is (= true (deref control-entered 2000 ::control-did-not-enter)))
      (finally
        (.countDown release-heavy)
        (run! #(try (.close ^SocketChannel %) (catch Throwable _)) channels)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest request-server-constructs-the-configured-codec-worker-pool
  (let [path (socket-path "configured-codec-workers")
        worker-count 3
        queue-capacity 7
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection! (fn [_control] (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler (fn [_owner _request _frame-bytes _complete!] nil)
          ::uds/codec-workers worker-count
          ::uds/codec-worker-queue-capacity queue-capacity})
        workers ^java.util.concurrent.ThreadPoolExecutor (::uds/workers server)]
    (try
      (is (= worker-count (.getCorePoolSize workers)))
      (is (= worker-count (.getMaximumPoolSize workers)))
      (is (= queue-capacity (.remainingCapacity (.getQueue workers))))
      (finally
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest request-server-retains-large-response-suffix-until-writable
  (let [path (socket-path "transport-partial-write")
        response {::large-payload (apply str (repeat (* 3 1024 1024) "x"))}
        server
        (request-server!
         path
         (fn [_owner _request _frame-bytes complete!] (complete! response))
         (constantly nil))]
    (try
      (with-open [channel (open-admitted-channel! path)]
        (write-bytes! channel (frame-bytes {::request :large})
                      Integer/MAX_VALUE)
        (is (= response (uds/read-frame (Channels/newInputStream channel)))
            "a response larger than the socket buffer is resumed exactly")
        (wait-until! "large response byte release"
                     #(zero? @(::uds/authority-output-bytes server))))
      (finally
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest partial-event-frame-cannot-be-preempted-by-a-response
  (let [path (socket-path "transport-partial-event")
        control (promise)
        event {::event-payload (apply str (repeat (* 3 1024 1024) "e"))}
        request {::request :after-partial-event}
        response {::response :after-partial-event}
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection!
          (fn [connection-control]
            (deliver control connection-control)
            (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner _request _frame-bytes complete!]
            (complete! response))})
        channel (open-admitted-channel! path)
        {send! ::uds/send!} (deref control 2000 ::not-opened)]
    (try
      (let [send-result (send! event)]
        (is (= uds/send-accepted (::uds/send-status send-result)))
        (wait-until!
         "partially written event frame"
         #(when-let [session (first (vals @(::uds/connections server)))]
            (when-let [^ByteBuffer frame
                       (::uds/frame @(::uds/event-state session))]
              (and (pos? (.position frame)) (.hasRemaining frame)))))
        (write-bytes! channel (frame-bytes request) Integer/MAX_VALUE)
        (wait-until!
         "response queued behind the partial event frame"
         #(when-let [session (first (vals @(::uds/connections server)))]
            (some ::uds/response-slot
                  (seq ^ArrayDeque (::uds/outputs session)))))
        (let [input (Channels/newInputStream channel)]
          (is (= event (uds/read-frame input))
              "the first frame drains completely before another frame starts")
          (is (= response (uds/read-frame input))
              "the following response remains independently framed"))
        (is (= uds/send-accepted
               (deref (::uds/send-completion send-result)
                      2000 ::not-complete)))
        (wait-until!
         "event and response output reservations released"
         #(and (zero? @(::uds/authority-response-slot-count server))
               (zero? @(::uds/authority-output-bytes server)))))
      (finally
        (.close channel)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest request-server-closes-one-owner-exactly-once
  (let [path (socket-path "transport-owner-close")
        opened (promise)
        closed (atom [])
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/open-connection!
          (fn [{close! ::uds/close!}]
            (let [owner {::connection-id (random-uuid) ::close! close!}]
              (deliver opened owner)
              owner))
          ::uds/close-connection! #(swap! closed conj %)
          ::uds/handler (fn [_owner _request _frame-bytes _complete!] nil)})
        channel (open-admitted-channel! path)
        owner (deref opened 2000 ::not-opened)]
    (try
      ((::close! owner))
      ((::close! owner))
      (.close channel)
      (wait-until! "connection close callback" #(= 1 (count @closed)))
      (is (identical? owner (first @closed)))
      (is (= 1 (count @closed)))
      (finally
        (.close channel)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest connection-cleanup-has-fixed-capacity-under-disconnect-bursts
  (let [path (socket-path "transport-cleanup-capacity")
        release-cleanup (java.util.concurrent.CountDownLatch. 1)
        active (atom 0)
        maximum-active (atom 0)
        closed (atom 0)
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/maximum-connections 8
          ::uds/open-connection! (fn [_control] (Object.))
          ::uds/close-connection!
          (fn [_owner]
            (let [current (swap! active inc)]
              (swap! maximum-active max current)
              (try
                (.await release-cleanup)
                (finally
                  (swap! active dec)
                  (swap! closed inc)))))
          ::uds/handler
          (fn [_owner _request _frame-bytes _complete!] nil)})
        channels (mapv (fn [_] (open-admitted-channel! path)) (range 8))]
    (try
      (wait-until! "all bounded connections"
                   #(= 8 (count @(::uds/connections server))))
      (run! #(.close ^SocketChannel %) channels)
      (wait-until! "fixed cleanup workers" #(= 2 @active))
      (is (= 2 @maximum-active))
      (is (= 8 (count @(::uds/connections server)))
          "closing sessions retain admission until their database cleanup ends")
      (let [names (map #(.getName ^Thread %)
                       (.keySet (Thread/getAllStackTraces)))]
        (is (= 2 (count (filter #(.startsWith ^String %
                                             "database-request-cleanup-")
                                names))))
        (is (not-any? #(= "database-request-rejected-close" %) names)))
      (.countDown release-cleanup)
      (wait-until! "all exact connection cleanup" #(= 8 @closed))
      (wait-until! "all closing sessions released"
                   #(empty? @(::uds/connections server)))
      (finally
        (.countDown release-cleanup)
        (run! #(try (.close ^SocketChannel %) (catch Throwable _)) channels)
        (uds/close-request-server! server)
        (.delete (File. path))))))

(deftest forced-close-retains-encoding-until-the-worker-releases-it
  (let [path (socket-path "transport-encoding-close")
        control (promise)
        encode-entered (promise)
        release-encode (atom false)
        original-frame @#'seon.db.transport.uds/message-frame
        server
        (uds/start-request-server!
         {::uds/socket-path path
          ::uds/shutdown-timeout-ms 25
          ::uds/open-connection!
          (fn [connection-control]
            (deliver control connection-control)
            (Object.))
          ::uds/close-connection! (constantly nil)
          ::uds/handler
          (fn [_owner _request _frame-bytes complete!]
            (complete! {::response :blocked-encode}))})
        channel (open-admitted-channel! path)
        {send! ::uds/send!} (deref control 2000 ::not-opened)]
    (try
      (with-redefs-fn
        {#'seon.db.transport.uds/message-frame
         (fn [message & [ceiling]]
           (deliver encode-entered true)
           (loop []
             (when-not @release-encode
               (try
                 (Thread/sleep 5)
                 (catch InterruptedException _))
               (recur)))
           (if ceiling
             (original-frame message ceiling)
             (original-frame message)))}
        (fn []
          (write-bytes! channel (frame-bytes {::request :encode})
                        Integer/MAX_VALUE)
          (is (= true (deref encode-entered 2000 ::encode-not-entered)))
          (let [pending (send! {::event :pending-behind-encode})
                first-close (uds/close-request-server! server)]
            (is (= uds/send-accepted (::uds/send-status pending)))
            (is (true? (::uds/selector-stopped? first-close)))
            (is (false? (::uds/workers-stopped? first-close)))
            (is (false? (::uds/cleanup-stopped? first-close)))
            (is (= 1 @(::uds/authority-response-slot-count server)))
            (is (= uds/send-closed
                   (deref (::uds/send-completion pending)
                          2000 ::pending-not-abandoned)))
            (is (= 1 (count @(::uds/connections server))))
            (reset! release-encode true)
            (let [final-close (uds/close-request-server! server)]
              (is (true? (::uds/workers-stopped? final-close)))
              (is (true? (::uds/cleanup-stopped? final-close)))
              (is (zero? @(::uds/authority-response-slot-count server)))
              (is (zero? @(::uds/authority-output-bytes server)))
              (is (empty? @(::uds/connections server)))
              (is (.isEmpty ^LinkedBlockingQueue (::uds/commands server)))))))
      (finally
        (reset! release-encode true)
        (.close channel)
        (uds/close-request-server! server)
        (.delete (File. path))))))
