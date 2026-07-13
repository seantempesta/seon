(ns seon.db.transport.uds
  "Node Unix-domain-socket delivery for database protocol maps.

   This namespace owns Transit JSON, length framing, socket deadlines, and a
   persistent publish connection. It never interprets an operation or creates
   a database response; those semantics live once in `seon.db.protocol`."
  (:require [cognitect.transit :as t]
            [seon.platform :as platform]
            [seon.schema :as schema]))

(def ^js net (js/require "node:net"))
(def ^js Buffer (.-Buffer (js/require "node:buffer")))

(defonce ^:private transit-writer (t/writer :json))
(defonce ^:private transit-reader (t/reader :json))

(def ^:private maximum-frame-bytes (* 16 1024 1024))
(def ^:private rpc-tick-ms 250)

(def default-rpc-timeout-ms 5000)

(def default-request-socket-path
  (or (platform/env-val "SEON_REQ_SOCK")
      "tmp/seon-cluster-default-req.sock"))

(def default-publish-socket-path
  (or (platform/env-val "SEON_PUB_SOCK")
      "tmp/seon-cluster-default-pub.sock"))

(schema/register! ::socket-path [:string {:min 1}])
(schema/register! ::message :map)
(schema/register! ::timeout-ms [:int {:min 1}])
(schema/register! ::on-message [:fn fn?])
(schema/register! ::on-close [:fn fn?])
(schema/register! ::failure :keyword)
(schema/register! ::frame-bytes [:int {:min 0}])
(schema/register!
 ::rpc-request
 [:map
  [::socket-path {:optional true} ::socket-path]
  [::message ::message]
  [::timeout-ms {:optional true} ::timeout-ms]])
(schema/register!
 ::publisher-request
 [:map
  [::socket-path {:optional true} ::socket-path]
  [::on-message ::on-message]
  [::on-close ::on-close]])

(defn encode-frame
  "Encode one map as a length-prefixed Transit JSON buffer."
  {:malli/schema [:=> [:catn [::message ::message]] :any]}
  ^js [message]
  (let [payload (.from Buffer ^String (t/write transit-writer message) "utf-8")
        size (.-length payload)
        header (.alloc Buffer 4)]
    (when (> size maximum-frame-bytes)
      (throw (ex-info "Database protocol frame is too large."
                      {::failure :seon.db.transport.uds.failure/too-large
                       ::frame-bytes size})))
    (.writeUInt32BE header size 0)
    (.concat Buffer #js [header payload])))

(defn decode-payload
  "Decode one Transit JSON payload buffer."
  {:malli/schema [:=> [:catn [:seon.db.transport.uds/payload :any]] ::message]}
  [^js payload]
  (t/read transit-reader (.toString payload "utf-8")))

(defn rpc
  "Send one protocol map and resolve with one reply map.

   The timeout counts event-loop-alive time. A long synchronous pod operation
   therefore cannot expire a response already waiting in the socket buffer."
  {:malli/schema [:=> [:catn [::request ::rpc-request]] :any]}
  [{::keys [socket-path message timeout-ms]
    :or {socket-path default-request-socket-path
         timeout-ms default-rpc-timeout-ms}}]
  (js/Promise.
   (fn [resolve reject]
     (let [socket (.createConnection net socket-path)
           !needed (atom nil)
           !buffer (atom (.alloc Buffer 0))
           !settled? (atom false)
           !alive-ms (atom 0)
           started-at (js/Date.now)
           !timer (atom nil)
           finish! (fn [error value]
                     (when-not @!settled?
                       (reset! !settled? true)
                       (js/clearInterval @!timer)
                       (if error
                         (do (.destroy socket) (reject error))
                         (do (.end socket) (resolve value)))))
           _ (reset! !timer
                     (js/setInterval
                      (fn []
                        (when (and (not @!settled?)
                                   (>= (swap! !alive-ms + rpc-tick-ms)
                                       timeout-ms))
                          (finish!
                           (ex-info
                            (str "Database request timed out (alive " @!alive-ms
                                 "ms, wall " (- (js/Date.now) started-at) "ms).")
                            {::failure :seon.db.transport.uds.failure/timeout})
                           nil)))
                      rpc-tick-ms))]
       (.on socket "error" (fn [error] (finish! error nil)))
       (.on socket "connect"
            (fn []
              (try
                (.write socket (encode-frame message))
                (catch :default error
                  (finish! error nil)))))
       (.on socket "data"
            (fn [chunk]
              (swap! !buffer
                     (fn [^js current]
                       (.concat Buffer #js [current chunk])))
              (when (and (nil? @!needed) (>= (.-length ^js @!buffer) 4))
                (let [needed (.readUInt32BE ^js @!buffer 0)]
                  (reset! !needed needed)
                  (swap! !buffer #(.subarray ^js % 4))
                  (when (> needed maximum-frame-bytes)
                    (finish!
                     (ex-info "Database reply frame is too large."
                              {::failure
                               :seon.db.transport.uds.failure/too-large
                               ::frame-bytes needed})
                     nil))))
              (when (and (some? @!needed)
                         (>= (.-length ^js @!buffer) @!needed)
                         (not @!settled?))
                (try
                  (finish! nil
                           (decode-payload
                            (.subarray ^js @!buffer 0 @!needed)))
                  (catch :default error
                    (finish! error nil))))))
       (.on socket "end"
            (fn []
              (when-not @!settled?
                (finish!
                 (ex-info "Database connection closed before its reply."
                          {::failure :seon.db.transport.uds.failure/closed})
                 nil))))))))

(defn connect-publisher!
  "Connect to the transaction publisher and deliver complete maps in order.

   Resolves with the socket after connecting. After that, any socket, decode,
   or callback failure closes the connection and invokes `on-close` once. The
   caller owns replay and reconnect policy."
  {:malli/schema [:=> [:catn [::request ::publisher-request]] :any]}
  [{::keys [socket-path on-message on-close]
    :or {socket-path default-publish-socket-path}}]
  (js/Promise.
   (fn [resolve reject]
     (let [socket (.createConnection net socket-path)
           !connected? (atom false)
           !closed? (atom false)
           !buffer (atom (.alloc Buffer 0))
           close! (fn [reason]
                    (when-not @!closed?
                      (reset! !closed? true)
                      (.destroy socket)
                      (if @!connected?
                        (on-close reason)
                        (reject (js/Error.
                                 (str "Database publisher connection failed: "
                                      reason))))))]
       (.on socket "connect"
            (fn []
              (reset! !connected? true)
              (resolve socket)))
       (.on socket "error"
            (fn [error]
              (close! (or (.-message error) (str error)))))
       (.on socket "close" (fn [] (close! "socket closed")))
       (.on socket "data"
            (fn [chunk]
              (swap! !buffer
                     (fn [^js current]
                       (.concat Buffer #js [current chunk])))
              (loop []
                (let [^js buffer @!buffer]
                  (when (and (not @!closed?) (>= (.-length buffer) 4))
                    (let [needed (.readUInt32BE buffer 0)]
                      (cond
                        (> needed maximum-frame-bytes)
                        (close! (str "Publish frame is too large: " needed
                                     " bytes."))

                        (>= (.-length buffer) (+ 4 needed))
                        (do
                          (reset! !buffer (.subarray buffer (+ 4 needed)))
                          (let [error
                                (try
                                  (on-message
                                   (decode-payload
                                    (.subarray buffer 4 (+ 4 needed))))
                                  nil
                                  (catch :default cause
                                    (str "Publish frame handling failed: "
                                         (or (.-message cause) (str cause)))))]
                            (if error
                              (close! error)
                              (recur)))))))))))))))
