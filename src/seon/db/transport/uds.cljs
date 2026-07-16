(ns seon.db.transport.uds
  "Persistent Bun Unix-socket sessions for database protocol maps.

   This namespace owns Transit JSON, four-byte framing, request correlation,
   deadlines, and native socket backpressure. Bun values remain inside the
   session closures; callers exchange ordinary namespaced maps and Promises."
  (:require [cognitect.transit :as t]
            [seon.db.protocol :as protocol]
            [seon.platform :as platform]
            [seon.schema :as schema]))

(defonce ^:private transit-writer (t/writer :json))
(defonce ^:private transit-reader (t/reader :json))
(defonce ^:private text-encoder (js/TextEncoder.))
(defonce ^:private text-decoder (js/TextDecoder. "utf-8"))
(defonce ^:private !connect-native
  (atom (fn [options] (js-invoke js/Bun "connect" options))))

(def ^:private maximum-frame-bytes (* 1024 1024))
(def ^:private maximum-pending-requests 16)
(def ^:private maximum-queued-bytes (* 2 1024 1024))
(def ^:private deadline-tick-ms 250)

(def default-request-timeout-ms 5000)

(def default-socket-path
  (or (platform/env-val "SEON_DB_SOCK")
      "tmp/seon-cluster-default-db.sock"))

(schema/register! ::socket-path [:string {:min 1}])
(schema/register! ::message :map)
(schema/register! ::timeout-ms [:int {:min 1}])
(schema/register! ::connected? 'fn?)
(schema/register! ::request! 'fn?)
(schema/register! ::close! 'fn?)
(schema/register! ::pending-count 'fn?)
(schema/register! ::queued-bytes 'fn?)
(schema/register!
 ::session
 [:map {:closed true}
  [::connected? ::connected?]
  [::request! ::request!]
  [::close! ::close!]
  [::pending-count ::pending-count]
  [::queued-bytes ::queued-bytes]])
(schema/register! ::failure :keyword)
(schema/register! ::frame-bytes [:int {:min 0}])
(schema/register!
 ::connect-request
 [:map {:closed true}
  [::socket-path {:optional true} ::socket-path]])
(schema/register!
 ::request
 [:map {:closed true}
  [::session ::session]
  [::message ::message]
  [::timeout-ms {:optional true} ::timeout-ms]])

(defn- failure
  ([message kind]
   (failure message kind {}))
  ([message kind data]
   (ex-info message (assoc data ::failure kind))))

(defn- fresh-parser []
  {::header (js/Uint8Array. 4)
   ::header-offset 0})

(defn- uint32-be [^js header]
  (+ (* (aget header 0) 16777216)
     (* (aget header 1) 65536)
     (* (aget header 2) 256)
     (aget header 3)))

(defn- copy-bytes!
  [^js target target-offset ^js source source-offset byte-count]
  (.set target
        (.subarray source source-offset (+ source-offset byte-count))
        target-offset))

(defn- consume-chunk
  "Advance one parser through a native callback chunk without accumulation."
  [parser ^js chunk]
  (loop [parser parser
         chunk-offset 0
         payloads []]
    (if (= chunk-offset (.-byteLength chunk))
      {::parser parser ::payloads payloads}
      (if-let [^js payload (::payload parser)]
        (let [payload-offset (::payload-offset parser)
              copied (min (- (.-byteLength payload) payload-offset)
                          (- (.-byteLength chunk) chunk-offset))
              next-payload-offset (+ payload-offset copied)]
          (copy-bytes! payload payload-offset chunk chunk-offset copied)
          (if (= next-payload-offset (.-byteLength payload))
            (recur (fresh-parser)
                   (+ chunk-offset copied)
                   (conj payloads payload))
            (recur (assoc parser ::payload-offset next-payload-offset)
                   (+ chunk-offset copied)
                   payloads)))
        (let [^js header (::header parser)
              header-offset (::header-offset parser)
              copied (min (- 4 header-offset)
                          (- (.-byteLength chunk) chunk-offset))
              next-header-offset (+ header-offset copied)]
          (copy-bytes! header header-offset chunk chunk-offset copied)
          (if (= next-header-offset 4)
            (let [payload-bytes (uint32-be header)]
              (when (or (zero? payload-bytes)
                        (> payload-bytes maximum-frame-bytes))
                (throw
                 (failure
                  (str "Invalid database frame length: " payload-bytes
                       " bytes.")
                  :seon.db.transport.uds.failure/invalid-frame
                  {::frame-bytes payload-bytes})))
              (recur {::header header
                      ::header-offset 4
                      ::payload (js/Uint8Array. payload-bytes)
                      ::payload-offset 0}
                     (+ chunk-offset copied)
                     payloads))
            (recur (assoc parser ::header-offset next-header-offset)
                   (+ chunk-offset copied)
                   payloads)))))))

(defn- encode-frame [message]
  (let [^js payload (.encode text-encoder
                             ^String (t/write transit-writer message))
        payload-bytes (.-byteLength payload)]
    (when (or (zero? payload-bytes)
              (> payload-bytes maximum-frame-bytes))
      (throw
       (failure "Database protocol frame is too large."
                :seon.db.transport.uds.failure/too-large
                {::frame-bytes payload-bytes})))
    (let [frame (js/Uint8Array. (+ 4 payload-bytes))]
      (aset frame 0 (bit-and (unsigned-bit-shift-right payload-bytes 24) 255))
      (aset frame 1 (bit-and (unsigned-bit-shift-right payload-bytes 16) 255))
      (aset frame 2 (bit-and (unsigned-bit-shift-right payload-bytes 8) 255))
      (aset frame 3 (bit-and payload-bytes 255))
      (.set frame payload 4)
      frame)))

(defn- decode-payload [^js payload]
  (t/read transit-reader (.decode text-decoder payload)))

(defn- empty-output []
  {::frames []
   ::queued-bytes 0})

(defn- append-output [output ^js frame]
  (let [queued-bytes (+ (::queued-bytes output) (.-byteLength frame))]
    (when (> queued-bytes maximum-queued-bytes)
      (throw
       (failure "Database session output exceeded its byte limit."
                :seon.db.transport.uds.failure/output-overflow
                {::frame-bytes queued-bytes})))
    (-> output
        (update ::frames conj {::frame frame ::offset 0})
        (assoc ::queued-bytes queued-bytes))))

(defn- advance-output
  "Advance the first queued frame by exactly the native accepted count."
  [output accepted]
  (let [{::keys [frame offset]} (first (::frames output))
        remaining (- (.-byteLength ^js frame) offset)]
    (when (or (neg? accepted) (> accepted remaining))
      (throw
       (failure (str "Native socket returned invalid write count " accepted ".")
                :seon.db.transport.uds.failure/write
                {::frame-bytes accepted})))
    (cond
      (zero? accepted)
      output

      (= accepted remaining)
      {::frames (subvec (::frames output) 1)
       ::queued-bytes (- (::queued-bytes output) accepted)}

      :else
      {::frames (assoc (::frames output) 0
                       {::frame frame ::offset (+ offset accepted)})
       ::queued-bytes (- (::queued-bytes output) accepted)})))

(defn connect!
  "Open one persistent multiplexed Bun database session."
  {:malli/schema [:=> [:catn [::request ::connect-request]] :any]}
  [{::keys [socket-path]
    :or {socket-path default-socket-path}}]
  (js/Promise.
   (fn [resolve-connect reject-connect]
     (let [!socket (atom nil)
           !connected? (atom false)
           !terminal? (atom false)
           !connect-settled? (atom false)
           !pending (atom {})
           !output (atom (empty-output))
           !parser (atom (fresh-parser))
           !deadline-timer (atom nil)]
       (letfn [(as-error [reason]
                 (if (instance? js/Error reason)
                   reason
                   (failure (str reason)
                            :seon.db.transport.uds.failure/closed)))
               (settle-connect! [error session]
                 (when-not @!connect-settled?
                   (reset! !connect-settled? true)
                   (if error
                     (reject-connect error)
                     (resolve-connect session))))
               (terminate! [reason]
                 (if @!terminal?
                   false
                   (let [error (as-error reason)
                         socket @!socket
                         pending (vals @!pending)]
                     (reset! !terminal? true)
                     (reset! !connected? false)
                     (when-let [timer @!deadline-timer]
                       (js/clearInterval timer))
                     (reset! !deadline-timer nil)
                     (reset! !pending {})
                     (reset! !output (empty-output))
                     (settle-connect! error nil)
                     (doseq [{::keys [reject]} pending]
                       (reject error))
                     (when socket
                       (try
                         (js-invoke socket "close")
                         (catch :default _)))
                     true)))
               (flush-output! []
                 (when (and @!connected? (not @!terminal?))
                   (loop []
                     (when-let [{::keys [frame offset]}
                                (first (::frames @!output))]
                       (let [remaining (- (.-byteLength ^js frame) offset)
                             accepted
                             (try
                               (js-invoke @!socket "write"
                                          frame offset remaining)
                               (catch :default error
                                 (terminate! error)
                                 -1))]
                         (cond
                           (neg? accepted)
                           (terminate!
                            (failure "Native database socket write failed."
                                     :seon.db.transport.uds.failure/write))

                           (zero? accepted)
                           nil

                           :else
                           (do
                             (try
                               (swap! !output advance-output accepted)
                               (catch :default error
                                 (terminate! error)))
                             (when-not @!terminal?
                               (recur)))))))))
               (enqueue-frame! [frame]
                 (if @!terminal?
                   false
                   (try
                     (swap! !output append-output frame)
                     (flush-output!)
                     (not @!terminal?)
                     (catch :default error
                       (terminate! error)
                       false))))
               (enqueue-cancel! [target-request-id]
                 (when (and @!connected? (not @!terminal?))
                   (let [cancel-request-id
                         (str target-request-id ":cancel:" (random-uuid))]
                     (try
                       (enqueue-frame!
                        (encode-frame
                         {::protocol/operation protocol/cancel-operation
                          ::protocol/request-id cancel-request-id
                          ::protocol/target-request-id target-request-id}))
                       (catch :default error
                         (terminate! error))))))
               (expire-deadlines! []
                 (let [now (js/Date.now)
                       expired
                       (reduce-kv
                        (fn [entries request-id entry]
                          (if (<= (::deadline-at entry) now)
                            (conj entries [request-id entry])
                            entries))
                        []
                        @!pending)]
                   (doseq [[request-id {::keys [reject] :as expired-entry}]
                           expired]
                     (when-let [current-entry (get @!pending request-id)]
                       (when (identical? expired-entry current-entry)
                         (swap! !pending dissoc request-id)
                         (reject
                          (failure
                           "Database request timed out."
                           :seon.db.transport.uds.failure/timeout
                           {::protocol/request-id request-id}))
                         (enqueue-cancel! request-id))))))
               (deliver-response! [response]
                 (let [request-id (::protocol/request-id response)]
                   (when-not (string? request-id)
                     (throw
                      (failure "Database response has no request id."
                               :seon.db.transport.uds.failure/protocol)))
                   (when-let [{::keys [resolve]} (get @!pending request-id)]
                     (swap! !pending dissoc request-id)
                     (resolve response))))
               (receive! [chunk]
                 (when-not @!terminal?
                   (try
                     (let [{::keys [parser payloads]}
                           (consume-chunk @!parser chunk)]
                       (reset! !parser parser)
                       (doseq [payload payloads]
                         (deliver-response! (decode-payload payload))))
                     (catch :default error
                       (terminate! error)))))
               (request-map! [{::keys [message timeout-ms]
                               :or {timeout-ms default-request-timeout-ms}}]
                 (let [request-id (::protocol/request-id message)]
                   (cond
                     @!terminal?
                     (js/Promise.reject
                      (failure "Database session is closed."
                               :seon.db.transport.uds.failure/closed))

                     (not (string? request-id))
                     (js/Promise.reject
                      (failure "Database request has no request id."
                               :seon.db.transport.uds.failure/protocol))

                     (contains? @!pending request-id)
                     (js/Promise.reject
                      (failure "Database request id is already pending."
                               :seon.db.transport.uds.failure/duplicate
                               {::protocol/request-id request-id}))

                     (>= (count @!pending) maximum-pending-requests)
                     (js/Promise.reject
                      (failure "Database session has no request capacity."
                               :seon.db.transport.uds.failure/busy))

                     :else
                     (js/Promise.
                      (fn [resolve reject]
                        (try
                          (let [frame (encode-frame message)
                                entry {::resolve resolve
                                       ::reject reject
                                       ::deadline-at (+ (js/Date.now)
                                                        timeout-ms)}]
                            (swap! !pending assoc request-id entry)
                            (when-not (enqueue-frame! frame)
                              (when-let [owned (get @!pending request-id)]
                                (when (identical? owned entry)
                                  (swap! !pending dissoc request-id)
                                  (reject
                                   (failure "Database session is closed."
                                            :seon.db.transport.uds.failure/closed))))))
                          (catch :default error
                            (swap! !pending dissoc request-id)
                            (reject error))))))))
               (session-map []
                 {::connected? (fn [] @!connected?)
                  ::request! request-map!
                  ::close! #(terminate!
                             (failure "Database session was closed by its owner."
                                      :seon.db.transport.uds.failure/closed))
                  ::pending-count #(count @!pending)
                  ::queued-bytes #(::queued-bytes @!output)})
               (opened! [socket]
                 (when (and (not @!terminal?) (not @!connected?))
                   (reset! !socket socket)
                   (reset! !connected? true)
                   (when-not @!deadline-timer
                     (reset! !deadline-timer
                             (js/setInterval expire-deadlines!
                                             deadline-tick-ms)))
                   (let [session (session-map)]
                     (settle-connect! nil session)
                     (flush-output!))))]
         (let [handler
               (js-obj
                "binaryType" "uint8array"
                "open" (fn [socket] (opened! socket))
                "data" (fn [_socket chunk] (receive! chunk))
                "drain" (fn [_socket] (flush-output!))
                "error" (fn [_socket error] (terminate! error))
                "end" (fn [_socket]
                        (terminate!
                         (failure "Database authority ended the session."
                                  :seon.db.transport.uds.failure/closed)))
                "close" (fn [_socket error]
                          (terminate!
                           (or error
                               (failure "Database session closed."
                                        :seon.db.transport.uds.failure/closed))))
                "connectError" (fn [_socket error]
                                 (terminate! error)))
               options (js-obj "unix" socket-path "socket" handler)]
           (try
             (-> (@!connect-native options)
                 (.then opened!)
                 (.catch (fn [error]
                           ;; Bun invokes connectError first, then rejects this
                           ;; Promise. Both converge on the same transition.
                           (terminate! error))))
             (catch :default error
               (terminate! error)))))))))

(defn request!
  "Send one request through an existing multiplexed database session."
  {:malli/schema [:=> [:catn [::request ::request]] :any]}
  [{::keys [session] :as request}]
  ((::request! session) (dissoc request ::session)))

(defn close!
  "Close a database session and settle its pending requests once."
  {:malli/schema [:=> [:catn [::session ::session]] :boolean]}
  [session]
  ((::close! session)))

(defn connected?
  "True when a database session can accept native socket writes."
  {:malli/schema [:=> [:catn [::session ::session]] :boolean]}
  [session]
  ((::connected? session)))
