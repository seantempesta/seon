(ns seon.db.transport-uds-test
  "Focused Bun session framing, correlation, and terminal-state tests."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]))

(def ^:private encode-frame @#'uds/encode-frame)
(def ^:private decode-payload @#'uds/decode-payload)
(def ^:private consume-chunk @#'uds/consume-chunk)
(def ^:private fresh-parser @#'uds/fresh-parser)
(def ^:private maximum-frame-bytes @#'uds/maximum-frame-bytes)
(def ^:private maximum-queued-bytes @#'uds/maximum-queued-bytes)
(def ^:private !connect-native @#'uds/!connect-native)

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
   ::protocol/result value})

(defn- fake-bun [accepted-counts]
  (let [!handler (atom nil)
        !socket (atom nil)
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
            (reset! !handler handler)
            (reset! !socket socket)
            ((aget handler "open") socket)
            (js/Promise.resolve socket)))]
    {::bun (js-obj "connect" connect)
     ::handler !handler
     ::socket !socket
     ::writes !writes
     ::close-count !close-count}))

(defn- with-fake-bun [accepted-counts body]
  (let [prior-connect @!connect-native
        fixture (fake-bun accepted-counts)]
    (reset! !connect-native (aget (::bun fixture) "connect"))
    (-> (uds/connect! {})
        (.then (fn [session] (body session fixture)))
        (.finally
         (fn []
           (reset! !connect-native prior-connect))))))

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
            !responded? (atom false)
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
                        (when-not @!responded?
                          (reset! !responded? true)
                          (js-invoke socket "write" response-frame))))))]
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

(deftest deadline-retains-request-identity-and-capacity-until-response
  (async done
    (-> (with-fake-bun
          []
          (fn [session fixture]
            (-> (uds/request!
                 {::uds/session session
                  ::uds/message (request-message "deadline")
                  ::uds/timeout-ms 1})
                (.then (fn [_]
                         (is false "an expired request must reject")))
                (.catch
                 (fn [error]
                   (is (= :seon.db.transport.uds.failure/timeout
                          (::uds/failure (ex-data error))))
                   (is (= 1 ((::uds/pending-count session)))
                       "physical work still owns its correlation slot")
                   (is (= 2 (count @(::writes fixture)))
                       "the deadline sends one cancellation on the same session")
                   (-> (uds/request!
                        {::uds/session session
                         ::uds/message (request-message "deadline")})
                       (.then (fn [_]
                                (is false "a timed-out id cannot be reused")))
                       (.catch
                        (fn [duplicate]
                          (is (= :seon.db.transport.uds.failure/duplicate
                                 (::uds/failure (ex-data duplicate))))
                          (inject! fixture
                                   (encode-frame
                                    (response-message "deadline" :late)))
                          (is (zero? ((::uds/pending-count session)))
                              "the late response only retires physical ownership")
                          (let [reused
                                (uds/request!
                                 {::uds/session session
                                  ::uds/message (request-message "deadline")})]
                            (inject! fixture
                                     (encode-frame
                                      (response-message "deadline" :reused)))
                            reused)))
                       (.then
                        (fn [response]
                          (is (= :reused (::protocol/result response)))
                          (uds/close! session)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "deadline handling failed: " error
                                 "\n" (.-stack error)))
                  (done))))))

(deftest repeated-deadlines-cannot-exceed-physical-in-flight-capacity
  (async done
    (-> (with-fake-bun
          []
          (fn [session _fixture]
            (let [requests
                  (mapv
                   (fn [index]
                     (-> (uds/request!
                          {::uds/session session
                           ::uds/message (request-message (str "timeout-" index))
                           ::uds/timeout-ms 1})
                         (.catch identity)))
                   (range 16))]
              (-> (js/Promise.all (into-array requests))
                  (.then
                   (fn [errors]
                     (is (every?
                          #(= :seon.db.transport.uds.failure/timeout
                              (::uds/failure (ex-data %)))
                          (array-seq errors)))
                     (is (= 16 ((::uds/pending-count session))))
                     (-> (uds/request!
                          {::uds/session session
                           ::uds/message (request-message "over-capacity")})
                         (.then (fn [_]
                                  (is false "timed-out work still uses capacity")))
                         (.catch
                          (fn [error]
                            (is (= :seon.db.transport.uds.failure/busy
                                   (::uds/failure (ex-data error))))
                            (uds/close! session))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "deadline capacity failed: " error))
                  (done))))))
