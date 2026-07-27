(ns seon.db.transport-uds-test
  "Focused Bun session framing, correlation, and terminal-state tests."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.error :as error]))

(def ^:private encode-frame @#'uds/encode-frame)
(def ^:private decode-payload @#'uds/decode-payload)
(def ^:private consume-chunk @#'uds/consume-chunk)
(def ^:private fresh-parser @#'uds/fresh-parser)
(def ^:private maximum-frame-bytes @#'uds/maximum-frame-bytes)
(def ^:private maximum-queued-bytes @#'uds/maximum-queued-bytes)
(def ^:private maximum-pending-events @#'uds/maximum-pending-events)
(def ^:private maximum-queued-event-bytes
  @#'uds/maximum-queued-event-bytes)
(def ^:private append-event @#'uds/append-event)
(def ^:private empty-events @#'uds/empty-events)
(def ^:private take-event @#'uds/take-event)
(def ^:private !connect-native @#'uds/!connect-native)

(def ^:private database
  {:db-name "transport"
   :store-id [#uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" :db]
   :t 536870929
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id
   #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"})

(def ^:private datom
  [17 :person/name "Ada" 536870929 true])

(defn- datoms-event [request-id]
  {::protocol/event protocol/datoms-event
   ::protocol/request-id request-id
   :db-before (assoc database :t (dec (:t database)))
   :db-after database
   :tx-data [datom]
   :tempids {}
   :tx-meta {}})

(defn- resynchronization-event [request-id]
  {::protocol/event protocol/resynchronization-event
   ::protocol/request-id request-id
   :db-after database})

(defn- database-advanced-event [database]
  {::protocol/event protocol/database-advanced-event
   :db-after database})

(defn- concatenate [arrays]
  (let [result (js/Uint8Array.
                (reduce + (map #(.-byteLength ^js %) arrays)))]
    (reduce
     (fn [offset ^js bytes]
       (.set result bytes offset)
       (+ offset (.-byteLength bytes)))
     0
     arrays)
    result))

(defn- payloads-from-chunks [chunks]
  (reduce
   (fn [{::keys [parser payloads]} chunk]
     (let [result (consume-chunk parser chunk)]
       {::parser (::uds/parser result)
        ::payloads (into payloads (::uds/payloads result))}))
   {::parser (fresh-parser) ::payloads []}
   chunks))

(defn- one-byte-chunks [^js bytes]
  (mapv (fn [offset] (.slice bytes offset (inc offset)))
        (range (.-byteLength bytes))))

(defn- frame-header [payload-bytes]
  (doto (js/Uint8Array. 4)
    (aset 0 (bit-and (unsigned-bit-shift-right payload-bytes 24) 255))
    (aset 1 (bit-and (unsigned-bit-shift-right payload-bytes 16) 255))
    (aset 2 (bit-and (unsigned-bit-shift-right payload-bytes 8) 255))
    (aset 3 (bit-and payload-bytes 255))))

(defn- request-message [request-id]
  {::protocol/operation protocol/ping-operation
   ::protocol/request-id request-id})

(defn- response-message [request-id value]
  {::protocol/request-id request-id
   ::protocol/success? true
   :datahike.read/dependency-plan :all
   ::protocol/result value})

(defn- fake-bun [accepted-counts]
  (let [!handler (atom nil)
        !socket (atom nil)
        !options (atom nil)
        !accepted-counts (atom accepted-counts)
        !writes (atom [])
        !close-count (atom 0)
        socket
        (js-obj
         "write"
         (fn [frame offset byte-count]
           (let [accepted (if-let [scripted (first @!accepted-counts)]
                            (do (swap! !accepted-counts subvec 1) scripted)
                            byte-count)]
             (swap! !writes conj
                    {::offset offset
                     ::byte-count byte-count
                     ::accepted accepted
                     ::frame (.slice frame)})
             accepted))
         "close" (fn [] (swap! !close-count inc)))
        connect
        (fn [options]
          (let [handler (aget options "socket")]
            (reset! !options options)
            (reset! !handler handler)
            (reset! !socket socket)
            ((aget handler "open") socket)
            (js/Promise.resolve socket)))]
    {::bun (js-obj "connect" connect)
     ::handler !handler
     ::socket !socket
     ::options !options
     ::accepted-counts !accepted-counts
     ::writes !writes
     ::close-count !close-count}))

(declare inject!)

(defn- with-fake-bun
  ([accepted-counts body]
   (with-fake-bun accepted-counts {} body))
  ([accepted-counts options body]
   (let [prior-connect @!connect-native
         fixture (fake-bun [])]
     (reset! !connect-native (aget (::bun fixture) "connect"))
     (let [opening (uds/connect! options)]
       (js/setTimeout
        (fn []
          (inject!
           fixture
           (encode-frame
            (protocol/session-open-success
             {::protocol/configured-maximum-frame-bytes
              protocol/maximum-frame-bytes
              ::protocol/maximum-frame-bytes protocol/maximum-frame-bytes})
            protocol/session-open-maximum-frame-bytes)))
        0)
       (-> opening
         (.then (fn [session]
                  (reset! (::writes fixture) [])
                  (reset! (::accepted-counts fixture) accepted-counts)
                  (body session fixture)))
         (.finally
          (fn []
            (reset! !connect-native prior-connect))))))))

(defn- inject! [fixture bytes]
  ((aget @(::handler fixture) "data")
   @(::socket fixture)
   bytes))

(defn- event! [fixture event & [error]]
  (let [handler @(::handler fixture)
        callback (aget handler event)]
    (if error
      (callback @(::socket fixture) error)
      (callback @(::socket fixture)))))

(defn- after-event-turn []
  (js/Promise. (fn [resolve _] (js/setTimeout resolve 10))))

(deftest linear-parser-handles-fragmentation-and-coalescing
  (let [a (response-message "a" :first)
        b (response-message "b" :second)
        frame-a (encode-frame a)
        frame-b (encode-frame b)]
    (testing "every header and payload byte may arrive separately"
      (let [{::keys [payloads]}
            (payloads-from-chunks (one-byte-chunks frame-a))]
        (is (= [a] (mapv decode-payload payloads)))))
    (testing "several complete frames may share one callback chunk"
      (let [{::keys [payloads]}
            (payloads-from-chunks [(concatenate [frame-a frame-b])])]
        (is (= [a b] (mapv decode-payload payloads)))))))

(deftest parser-shares-the-protocol-frame-limit
  (is (= protocol/maximum-frame-bytes maximum-frame-bytes))
  (is (<= (+ 4 maximum-frame-bytes) maximum-queued-bytes)
      "one maximum legal payload and its header fit the output budget")
  (let [accepted (consume-chunk (fresh-parser)
                                (frame-header (inc (* 1024 1024))))]
    (is (= (inc (* 1024 1024))
           (.-byteLength ^js (::uds/payload (::uds/parser accepted))))
        "a legal protocol frame above the former Bun-only limit is admitted"))
  (is (thrown-with-msg?
       js/Error
       #"Invalid database frame length"
       (consume-chunk (fresh-parser)
                      (frame-header (inc maximum-frame-bytes))))))

(deftest parser-enforces-the-selected-session-ceiling
  (is (thrown-with-msg?
       js/Error
       #"Invalid database frame length"
       (consume-chunk
        (fresh-parser protocol/session-open-maximum-frame-bytes)
        (frame-header (inc protocol/session-open-maximum-frame-bytes))))))

(deftest admitted-session-retains-the-opening-agreement
  (async done
    (-> (with-fake-bun
          []
          (fn [session _]
            (is (= protocol/current-version (::uds/version session)))
            (is (= protocol/maximum-frame-bytes
                   (::uds/configured-maximum-frame-bytes session)))
            (is (= protocol/maximum-frame-bytes
                   (::uds/maximum-frame-bytes session)))
            (uds/close! session)))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "session agreement failed: " error))
                  (done))))))

(deftest opening-success-must-carry-the-exact-minimum-agreement
  (let [valid-opening-success? @#'uds/valid-opening-success?
        configured 65536
        response (protocol/session-open-success
                  {::protocol/configured-maximum-frame-bytes configured
                   ::protocol/maximum-frame-bytes configured})]
    (is (valid-opening-success? response protocol/maximum-frame-bytes))
    (is (false?
         (valid-opening-success?
          (assoc response ::protocol/maximum-frame-bytes (inc configured))
          protocol/maximum-frame-bytes)))))

(deftest opening-rejection-settles-connect-with-structured-data
  (async done
    (let [prior-connect @!connect-native
          fixture (fake-bun [])
          rejection
          (protocol/connection-capacity-failure
           {::protocol/maximum-connections 1})]
      (reset! !connect-native (aget (::bun fixture) "connect"))
      (let [opening (uds/connect! {})]
        (js/setTimeout
         #(inject! fixture
                   (encode-frame
                    rejection protocol/session-open-maximum-frame-bytes))
         0)
        (-> opening
            (.then (fn [_] (is false "rejected opening unexpectedly resolved")))
            (.catch
             (fn [error]
               (is (= rejection (ex-data error)))
               (is (= 1 @(::close-count fixture)))))
            (.finally #(reset! !connect-native prior-connect))
            (.then (fn [_] (done)))
            (.catch
             (fn [error]
               (reset! !connect-native prior-connect)
               (is false (str "opening rejection failed: " error))
               (done))))))))

(deftest local-oversize-is-a-structured-correlated-failure
  (async done
    (-> (with-fake-bun
          []
          (fn [session _]
            (-> (uds/request!
                 {::uds/session session
                  ::uds/message
                  {::protocol/operation protocol/ping-operation
                   ::protocol/request-id "oversize/local"
                   ::protocol/result
                   (apply str (repeat (inc protocol/maximum-frame-bytes) "x"))}})
                (.then (fn [_] (is false "oversize request unexpectedly sent")))
                (.catch
                 (fn [error]
                   (let [data (ex-data error)]
                     (is (= "oversize/local" (::protocol/request-id data)))
                     (is (= protocol/frame-too-large-error
                            (::protocol/error-kind data)))
                     (is (= :seon.config.database.transport/maximum-frame-bytes
                            (::protocol/configuration-key data)))
                     (is (= protocol/maximum-frame-bytes
                            (::protocol/maximum-frame-bytes data)))
                     (uds/close! session)))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "local oversize failure failed: " error))
                  (done))))))

(deftest session-control-failure-rejects-every-pending-request
  (async done
    (-> (with-fake-bun
          []
          (fn [session fixture]
            (let [a (uds/request! {::uds/session session
                                   ::uds/message (request-message "pending/a")})
                  b (uds/request! {::uds/session session
                                   ::uds/message (request-message "pending/b")})
                  control
                  (protocol/frame-too-large-failure
                   {::protocol/request-id protocol/session-control-request-id
                    ::protocol/maximum-frame-bytes
                    protocol/maximum-frame-bytes})]
              (inject! fixture (encode-frame control))
              (-> (js/Promise.all
                   #js [(.catch a ex-data) (.catch b ex-data)])
                  (.then
                   (fn [failures]
                     (is (= [control control] (vec (array-seq failures))))
                     (is (false? (uds/connected? session)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "control settlement failed: " error))
                  (done))))))

(deftest socket-eof-rejects-every-pending-request-before-a-deadline-turn
  (async done
    (-> (with-fake-bun
          []
          (fn [session fixture]
            (let [a (uds/request! {::uds/session session
                                   ::uds/message (request-message "eof/a")})
                  b (uds/request! {::uds/session session
                                   ::uds/message (request-message "eof/b")})]
              (event! fixture "end")
              (-> (js/Promise.all #js [(.catch a identity)
                                       (.catch b identity)])
                  (.then
                   (fn [errors]
                     (is (every?
                          #(= :seon.db.transport.uds.failure/closed
                              (::uds/failure (ex-data %)))
                          (array-seq errors)))
                     (is (zero? ((::uds/pending-count session))))
                     (is (= 1 @(::close-count fixture)))))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "EOF settlement failed: " error))
           (done))))))

(deftest multiplexed-session-correlates-out-of-order-responses
  (async done
    (-> (with-fake-bun
          []
          (fn [session fixture]
            (let [a (uds/request! {::uds/session session
                                   ::uds/message (request-message "request-a")})
                  b (uds/request! {::uds/session session
                                   ::uds/message (request-message "request-b")})]
              (inject! fixture
                       (concatenate
                        [(encode-frame (response-message "request-b" :b))
                         (encode-frame (response-message "request-a" :a))]))
              (-> (js/Promise.all #js [a b])
                  (.then
                   (fn [responses]
                     (is (= [:a :b]
                            (mapv ::protocol/result (array-seq responses))))
                     (is (zero? ((::uds/pending-count session))))
                     (uds/close! session)))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "out-of-order session failed: " error))
                  (done))))))

(deftest protocol-events-are-demultiplexed-without-consuming-correlation
  (async done
    (let [!events (atom [])]
      (-> (with-fake-bun
            []
            {::uds/on-event! #(swap! !events conj %)}
            (fn [session fixture]
              (let [response
                    (uds/request!
                     {::uds/session session
                      ::uds/message (request-message "listen/shared")})]
                (is (false? (aget @(::options fixture) "allowHalfOpen")))
                (inject!
                 fixture
                 (concatenate
                  [(encode-frame (datoms-event "listen/shared"))
                   (encode-frame (response-message "listen/shared" :ack))
                   (encode-frame (response-message "already-late" :ignored))]))
                (-> response
                    (.then
                     (fn [value]
                       (is (= :ack (::protocol/result value)))
                       (after-event-turn)))
                    (.then
                     (fn [_]
                       (inject!
                        fixture
                        (concatenate
                         [(encode-frame (datoms-event "listen/shared"))
                          (encode-frame (datoms-event "listen/shared"))]))
                       (after-event-turn)))
                    (.then
                     (fn [_]
                       (is (= [(datoms-event "listen/shared")
                               (resynchronization-event "listen/shared")]
                              @!events))
                       (is (zero? ((::uds/pending-event-count session))))
                       (is (zero? ((::uds/queued-event-bytes session))))
                       (uds/close! session)))))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "event demultiplexing failed: " error
                                   "\n" (.-stack error)))
                    (done)))))))

(deftest invalid-event-failure-preserves-the-response-and-explanation
  (async done
    (let [event (assoc (database-advanced-event database)
                       :seon.db.protocol/unexpected true)
          !terminal-errors (atom [])]
      (-> (with-fake-bun
            []
            {::uds/on-close! #(swap! !terminal-errors conj %)}
            (fn [session fixture]
              (inject! fixture (encode-frame event))
              (-> (after-event-turn)
                  (.then
                   (fn [_]
                     (let [error (first @!terminal-errors)]
                       (is (false? (uds/connected? session)))
                       (is (= 1 (count @!terminal-errors)))
                       (is (= event
                              (::protocol/response (ex-data error))))
                       (is (map?
                            (::protocol/explanation (ex-data error))))))))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "invalid-event diagnostics failed: " error
                                   "\n" (.-stack error)))
                    (done)))))))

(deftest event-queue-is-bounded-and-repetition-coalesces-to-resynchronization
  (let [first-event (datoms-event "listen/shared")
        events (-> (empty-events)
                   (append-event first-event 100)
                   (append-event first-event 120))
        taken (take-event events)]
    (is (= 1 (count (::uds/events events))))
    (is (= 120 (::uds/queued-event-bytes events)))
    (is (= (resynchronization-event "listen/shared")
           (::uds/event taken)))
    (is (empty? (::uds/events (::uds/events taken))))
    (is (zero? (::uds/queued-event-bytes (::uds/events taken))))
    (is (thrown-with-msg?
         js/Error
         #"event delivery exceeded"
         (reduce (fn [queued index]
                   (append-event queued (datoms-event (str "listen/" index)) 1))
                 (empty-events)
                 (range (inc maximum-pending-events)))))
    (is (thrown-with-msg?
         js/Error
         #"event delivery exceeded"
         (append-event (empty-events)
                       (datoms-event "listen/too-large")
                       (inc maximum-queued-event-bytes))))))

(deftest latest-database-events-coalesce-to-the-newest-value
  (let [newest (assoc database
                      :t (inc (:t database))
                      :datahike/commit-id
                      #uuid "6a56b426-c836-5817-9f6b-20584f2e81d6")
        events (-> (empty-events)
                   (append-event (database-advanced-event database) 100)
                   (append-event (database-advanced-event newest) 120)
                   (append-event (database-advanced-event database) 100))
        taken (take-event events)]
    (is (= 1 (count (::uds/events events))))
    (is (= 120 (::uds/queued-event-bytes events)))
    (is (= (database-advanced-event newest) (::uds/event taken)))
    (is (empty? (::uds/events (::uds/events taken))))))

(deftest event-overflow-terminates-once-and-discards-old-generation-callbacks
  (async done
    (let [!events (atom [])
          !terminal-errors (atom [])]
      (-> (with-fake-bun
            []
            {::uds/on-event! #(swap! !events conj %)
             ::uds/on-close! #(swap! !terminal-errors conj %)}
            (fn [session fixture]
              (inject!
               fixture
               (concatenate
                (mapv (comp encode-frame datoms-event #(str "listen/" %))
                      (range (inc maximum-pending-events)))))
              (event! fixture "error" (js/Error. "late error"))
              (event! fixture "end")
              (event! fixture "close")
              (inject! fixture (encode-frame (datoms-event "late/event")))
              (-> (after-event-turn)
                  (.then
                   (fn [_]
                     (is (false? (uds/connected? session)))
                     (is (= 1 @(::close-count fixture)))
                     (is (empty? @!events))
                     (is (= 1 (count @!terminal-errors)))
                     (is (= :seon.db.transport.uds.failure/event-overflow
                            (::uds/failure (ex-data (first @!terminal-errors)))))
                     (is (zero? ((::uds/pending-event-count session))))
                     (is (zero? ((::uds/queued-event-bytes session)))))))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "event overflow failed: " error
                                   "\n" (.-stack error)))
                    (done)))))))

(deftest consumer-callback-failure-closes-outside-the-native-data-callback
  (async done
    (let [callback-error (js/Error. "consumer failed")
          !terminal-errors (atom [])]
      (-> (with-fake-bun
            []
            {::uds/on-event! (fn [_] (throw callback-error))
             ::uds/on-close! #(swap! !terminal-errors conj %)}
            (fn [session fixture]
              (inject! fixture (encode-frame (datoms-event "listen/failure")))
              (is (uds/connected? session)
                  "the native data callback only enqueues consumer work")
              (-> (after-event-turn)
                  (.then
                   (fn [_]
                     (is (false? (uds/connected? session)))
                     (is (= 1 @(::close-count fixture)))
                     (is (= [callback-error] @!terminal-errors)))))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "callback failure handling failed: " error
                                   "\n" (.-stack error)))
                    (done)))))))

(deftest output-retains-the-exact-suffix-across-zero-and-drain
  (async done
    (-> (with-fake-bun
          [2 0]
          (fn [session fixture]
            (let [response (uds/request!
                            {::uds/session session
                             ::uds/message (request-message "partial")})]
              (is (= [0 2]
                     (mapv ::offset @(::writes fixture))))
              (is (pos? ((::uds/queued-bytes session))))
              (event! fixture "drain")
              (is (= [0 2 2]
                     (mapv ::offset @(::writes fixture))))
              (is (zero? ((::uds/queued-bytes session))))
              (inject! fixture
                       (encode-frame (response-message "partial" :done)))
              (-> response
                  (.then (fn [value]
                           (is (= :done (::protocol/result value)))
                           (uds/close! session)))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "partial output failed: " error))
                  (done))))))

(deftest negative-write-and-repeated-terminal-events-close-once
  (async done
    (-> (with-fake-bun
          [-1]
          (fn [session fixture]
            (-> (uds/request!
                 {::uds/session session
                  ::uds/message (request-message "fatal-write")})
                (.then (fn [_]
                         (is false "a fatal native write must reject")))
                (.catch
                 (fn [error]
                   (is (= :seon.db.transport.uds.failure/write
                          (::uds/failure (ex-data error))))
                   (event! fixture "error" (js/Error. "late error"))
                   (event! fixture "end")
                   (event! fixture "close")
                   (is (= 1 @(::close-count fixture)))
                   (is (false? (uds/connected? session))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "terminal transition failed: " error))
                  (done))))))

(deftest connect-error-and-rejected-promise-share-one-terminal-transition
  (async done
    (let [prior-connect @!connect-native
          connection-error (js/Error. "connection refused")
          !terminal-callbacks (atom 0)]
      (reset!
       !connect-native
       (fn [options]
         (let [handler (aget options "socket")
               socket (js-obj "close" #(swap! !terminal-callbacks inc))]
           ((aget handler "connectError") socket connection-error)
           ((aget handler "close") socket connection-error)
           (js/Promise.reject connection-error))))
      (-> (uds/connect! {})
          (.then (fn [_]
                   (is false "a failed native connection must reject")))
          (.catch (fn [error]
                    (is (identical? connection-error error))
                    (is (zero? @!terminal-callbacks)
                        "a socket that never opened is not closed again")))
          (.finally (fn [] (reset! !connect-native prior-connect)))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (reset! !connect-native prior-connect)
                    (is false (str "connect failure handling failed: " error))
                    (done)))))))

(deftest native-bun-connect-delivers-one-framed-response
  (async done
    (if-not (js* "typeof Bun !== 'undefined'")
      (do
        (is true "the native socket proof runs when the bundle uses Bun")
        (done))
      (let [socket-path (str "tmp/seon-bun-uds-test-" (random-uuid) ".sock")
            response (response-message "native-roundtrip" :native)
            response-frame (encode-frame response)
            !requests (atom 0)
            listener
            (js-invoke
             js/Bun
             "listen"
             (js-obj
              "unix" socket-path
              "socket"
              (js-obj
               "binaryType" "uint8array"
               "data" (fn [socket _chunk]
                        (case (swap! !requests inc)
                          1 (js-invoke
                             socket "write"
                             (encode-frame
                              (protocol/session-open-success
                               {::protocol/configured-maximum-frame-bytes
                                protocol/maximum-frame-bytes
                                ::protocol/maximum-frame-bytes
                                protocol/maximum-frame-bytes})
                              protocol/session-open-maximum-frame-bytes))
                          2 (js-invoke socket "write" response-frame)
                          nil)))))]
        (-> (uds/connect! {::uds/socket-path socket-path})
            (.then
             (fn [session]
               (-> (uds/request!
                    {::uds/session session
                     ::uds/message (request-message "native-roundtrip")})
                   (.then
                    (fn [value]
                      (is (= :native (::protocol/result value)))
                      (uds/close! session))))))
            (.finally (fn [] (js-invoke listener "stop" true)))
            (.then (fn [_] (done)))
            (.catch
             (fn [error]
               (is false (str "native Bun roundtrip failed: " error))
               (done))))))))

(deftest request-without-deadline-remains-owned-until-response
  (async done
    (-> (with-fake-bun
          []
          (fn [session fixture]
            (let [response (uds/request!
                            {::uds/session session
                             ::uds/message (request-message "no-deadline")})]
              (js/Promise.
               (fn [resolve _reject]
                 (js/setTimeout
                  (fn []
                    (is (= 1 ((::uds/pending-count session))))
                    (inject! fixture
                             (encode-frame
                              (response-message "no-deadline" :done)))
                    (resolve
                     (-> response
                         (.then (fn [value]
                                  (uds/close! session)
                                  value)))))
                  300))))))
        (.then (fn [response]
                 (is (= :done (::protocol/result response)))
                 (done)))
        (.catch (fn [error]
                  (is false (str "request without deadline failed: " error))
                  (done))))))

(deftest request-backstop-records-a-fault-and-settles-the-caller
  (async done
    (let [original-record! error/record!
          faults (atom [])]
      (set! error/record! #(swap! faults conj %))
      (-> (with-fake-bun
            []
            (fn [session fixture]
              (-> (uds/request!
                   {::uds/session session
                    ::uds/message (request-message "deadline")
                    ::uds/timeout-ms 1
                    ::uds/backstop-config-key
                    :seon.config.test/request-backstop-ms})
                  (.then (fn [_]
                           (is false "an expired request must reject")))
                  (.catch
                   (fn [exception]
                     (is (= :seon.db.transport.uds.failure/timeout
                            (::uds/failure (ex-data exception))))
                     (is (zero? ((::uds/pending-count session)))
                         "the request Promise is terminal at the backstop")
                     (is (= :seon.config.test/request-backstop-ms
                            (::uds/backstop-config-key
                             (ex-data (::error/raw (first @faults))))))
                     (is (= 2 (count @(::writes fixture)))
                         "the backstop sends one cancellation")
                     (-> (uds/request!
                          {::uds/session session
                           ::uds/message (request-message "deadline")})
                         (.then
                          (fn [_]
                            (is false "physical ownership prevents id reuse")))
                         (.catch
                          (fn [duplicate]
                            (is (= :seon.db.transport.uds.failure/duplicate
                                   (::uds/failure (ex-data duplicate))))
                            (inject! fixture
                                     (encode-frame
                                      (response-message "deadline" :late)))
                            (let [reused
                                  (uds/request!
                                   {::uds/session session
                                    ::uds/message
                                    (request-message "deadline")})]
                              (inject! fixture
                                       (encode-frame
                                        (response-message "deadline" :reused)))
                              reused)))
                         (.then
                          (fn [response]
                            (is (= :reused (::protocol/result response)))
                            (uds/close! session)))))))))
          (.finally #(set! error/record! original-record!))
          (.then (fn [_] (done)))
          (.catch
           (fn [exception]
             (set! error/record! original-record!)
             (is false (str "deadline handling failed: " exception
                            "\n" (.-stack exception)))
             (done)))))))

(deftest transaction-deadline-retains-the-authoritative-response
  (async done
    (let [original-record! error/record!]
      (set! error/record! (fn [_] nil))
      (-> (with-fake-bun
            []
            (fn [session fixture]
              (let [request (assoc (request-message "transaction-deadline")
                                   ::protocol/operation
                                   protocol/transact-operation)
                    settled? (atom false)
                    response
                    (-> (uds/request!
                         {::uds/session session
                          ::uds/message request
                          ::uds/timeout-ms 1
                          ::uds/backstop-config-key
                          :seon.config.test/transaction-backstop-ms})
                        (.finally #(reset! settled? true)))]
                (js/Promise.
                 (fn [resolve _reject]
                   (js/setTimeout
                    (fn []
                      (is (false? @settled?)
                          "a write deadline cannot claim physical completion")
                      (is (= 1 ((::uds/pending-count session))))
                      (is (= 2 (count @(::writes fixture)))
                          "the deadline sends one cancel beside the transaction")
                      (inject! fixture
                               (encode-frame
                                (response-message "transaction-deadline"
                                                  :authoritative)))
                      (resolve response))
                    300))))))
          (.finally #(set! error/record! original-record!))
          (.then
           (fn [response]
             (is (= :authoritative (::protocol/result response)))
             (done)))
          (.catch
           (fn [exception]
             (set! error/record! original-record!)
             (is false (str "transaction deadline failed: " exception
                            "\n" (.-stack exception)))
             (done)))))))
