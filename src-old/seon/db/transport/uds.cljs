(ns seon.db.transport.uds
  "Persistent Bun Unix-socket sessions for database protocol maps.

   This namespace owns Transit JSON, four-byte framing, request correlation,
   deadlines, and native socket backpressure. Bun values remain inside the
   session closures; callers exchange ordinary namespaced maps and Promises."
  (:require [cognitect.transit :as t]
            [seon.db.protocol :as protocol]
            [seon.error :as error]
            [seon.platform :as platform]
            [seon.schema :as schema]))

(defonce ^:private transit-writer (t/writer :json))
(defonce ^:private transit-reader (t/reader :json))
(defonce ^:private text-encoder (js/TextEncoder.))
(defonce ^:private text-decoder (js/TextDecoder. "utf-8"))
(defonce ^:private !connect-native
  (atom (fn [options] (js-invoke js/Bun "connect" options))))

(def ^:private maximum-frame-bytes protocol/maximum-frame-bytes)
(def ^:private maximum-pending-requests 256)
(def ^:private maximum-capacity-waiters 256)
(def ^:private maximum-queued-bytes (* 2 protocol/maximum-frame-bytes))
(def ^:private maximum-pending-events 64)
(def ^:private maximum-queued-event-bytes
  (* 2 protocol/maximum-frame-bytes))

(def default-socket-path
  (or (platform/env-val "SEON_DB_SOCK")
      "tmp/seon-cluster-default-db.sock"))

(schema/register! ::socket-path [:string {:min 1}])
(schema/register! ::message :map)
(schema/register! ::encoded-message :string)
(schema/register! ::timeout-ms [:int {:min 1}])
(schema/register! ::backstop-config-key :keyword)
(schema/register! ::on-event! 'fn?)
(schema/register! ::on-close! 'fn?)
(schema/register! ::connected? 'fn?)
(schema/register! ::request! 'fn?)
(schema/register! ::close! 'fn?)
(schema/register! ::pending-count 'fn?)
(schema/register! ::queued-bytes 'fn?)
(schema/register! ::pending-event-count 'fn?)
(schema/register! ::queued-event-bytes 'fn?)
(schema/register! ::version :seon.db.protocol/version)
(schema/register! ::configured-maximum-frame-bytes
                  :seon.db.protocol/configured-maximum-frame-bytes)
(schema/register! ::maximum-frame-bytes
                  :seon.db.protocol/maximum-frame-bytes)
(schema/register!
 ::session
 [:map {:closed true}
  [::connected? ::connected?]
  [::request! ::request!]
  [::close! ::close!]
  [::pending-count ::pending-count]
  [::queued-bytes ::queued-bytes]
  [::pending-event-count ::pending-event-count]
  [::queued-event-bytes ::queued-event-bytes]
  [::version ::version]
  [::configured-maximum-frame-bytes ::configured-maximum-frame-bytes]
  [::maximum-frame-bytes ::maximum-frame-bytes]])
(schema/register! ::failure :keyword)
(schema/register! ::frame-bytes [:int {:min 0}])
(schema/register!
 ::connect-request
 [:map {:closed true}
  [::socket-path {:optional true} ::socket-path]
  [::on-event! {:optional true} ::on-event!]
  [::on-close! {:optional true} ::on-close!]])
(schema/register!
 ::request
 [:map {:closed true}
  [::session ::session]
  [::message ::message]
  [::timeout-ms {:optional true} ::timeout-ms]
  [::backstop-config-key {:optional true} ::backstop-config-key]])
(schema/register! ::on-text! 'fn?)
(schema/register! ::send-text! 'fn?)
(schema/register!
 ::stream
 [:map {:closed true}
  [::connected? ::connected?]
  [::send-text! ::send-text!]
  [::close! ::close!]])
(schema/register!
 ::stream-request
 [:map {:closed true}
  [::socket-path ::socket-path]
  [::on-text! ::on-text!]
  [::on-close! {:optional true} ::on-close!]])

(defn- failure
  ([message kind]
   (failure message kind {}))
  ([message kind data]
   (ex-info message (assoc data ::failure kind))))

(defn- record-backstop!
  [request-id config-key timeout-ms]
  (error/record!
   {::error/raw
    (ex-info
     "A database transport request reached its completion backstop."
     {:seon.error/kind :core-bug
      ::protocol/request-id request-id
      ::backstop-config-key config-key
      ::timeout-ms timeout-ms})
    ::error/fault :core}))

(defn- valid-opening-success?
  [response client-maximum-frame-bytes]
  (let [configured (::protocol/configured-maximum-frame-bytes response)
        agreed (::protocol/maximum-frame-bytes response)]
    (and (::protocol/success? response)
         (= protocol/session-open-request-id
            (::protocol/request-id response))
         (= protocol/current-version (::protocol/version response))
         (protocol/valid-response? response)
         (integer? configured)
         (<= protocol/session-open-maximum-frame-bytes
             configured
             protocol/maximum-frame-bytes)
         (= agreed (min client-maximum-frame-bytes configured)))))

(defn- fresh-parser
  ([] (fresh-parser maximum-frame-bytes))
  ([maximum-frame-bytes]
   {::header (js/Uint8Array. 4)
    ::header-offset 0
    ::maximum-frame-bytes maximum-frame-bytes}))

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
            (recur (fresh-parser (::maximum-frame-bytes parser))
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
                        (> payload-bytes (::maximum-frame-bytes parser)))
                (throw
                 (failure
                  (str "Invalid database frame length: " payload-bytes
                       " bytes.")
                  :seon.db.transport.uds.failure/invalid-frame
                  {::frame-bytes payload-bytes})))
              (recur {::header header
                      ::header-offset 4
                      ::maximum-frame-bytes (::maximum-frame-bytes parser)
                      ::payload (js/Uint8Array. payload-bytes)
                      ::payload-offset 0}
                     (+ chunk-offset copied)
                     payloads))
            (recur (assoc parser ::header-offset next-header-offset)
                   (+ chunk-offset copied)
                   payloads)))))))

(defn- text-frame
  ([text] (text-frame text maximum-frame-bytes))
  ([text maximum-frame-bytes]
  (let [^js payload (.encode text-encoder ^String text)
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
      frame))))

(defn- record-wire-degradation!
  [degraded-paths]
  (error/record!
   {::error/raw
    (ex-info
     "Database wire projection degraded unsupported values to text."
     {:seon.error/kind :core-bug
      ::protocol/degraded-paths degraded-paths})
    ::error/fault :core}))

(defn encode
  "Project one protocol map to ordinary data and encode it as Transit JSON."
  {:malli/schema [:=> [:catn [::message ::message]] ::encoded-message]}
  [message]
  (let [{::protocol/keys [projected-value degraded? degraded-paths]}
        (protocol/wire-envelope-projection message)]
    (when degraded?
      (record-wire-degradation! degraded-paths))
    (t/write transit-writer projected-value)))

(defn decode
  "Decode one Transit JSON protocol map."
  {:malli/schema [:=> [:catn [::encoded-message ::encoded-message]] ::message]}
  [encoded-message]
  (t/read transit-reader encoded-message))

(defn- encode-frame
  ([message] (encode-frame message maximum-frame-bytes))
  ([message maximum-frame-bytes]
   (text-frame (encode message) maximum-frame-bytes)))

(defn- payload-text [^js payload]
  (.decode text-decoder payload))

(defn- decode-payload [^js payload]
  (decode (payload-text payload)))

(defn- empty-output []
  {::frames []
   ::queued-bytes 0})

(defn- empty-events []
  {::request-ids []
   ::events {}
   ::queued-event-bytes 0})

(defn- resynchronization [event]
  {::protocol/event protocol/resynchronization-event
   ::protocol/request-id (::protocol/request-id event)
   :db-after (:db-after event)})

(defn- event-key [event]
  (if (= protocol/database-advanced-event (::protocol/event event))
    [:seon.db (:db-name (:db-after event))]
    (::protocol/request-id event)))

(defn- newer-database-event [left right]
  (if (> (:t (:db-after right)) (:t (:db-after left))) right left))

(defn- append-event
  "Retain one event per interest; repetition becomes explicit resync."
  [events event frame-bytes]
  (let [key (event-key event)
        prior (get-in events [::events key])
        next-event (cond
                     (nil? prior) event
                     (= protocol/database-advanced-event
                        (::protocol/event event))
                     (newer-database-event (::event prior) event)
                     :else (resynchronization event))
        next-frame-bytes (if (and prior (identical? next-event (::event prior)))
                           (::frame-bytes prior)
                           frame-bytes)
        queued-event-bytes (+ (- (::queued-event-bytes events)
                                 (or (::frame-bytes prior) 0))
                              next-frame-bytes)
        pending-event-count (+ (count (::events events)) (if prior 0 1))]
    (when (or (> pending-event-count maximum-pending-events)
              (> queued-event-bytes maximum-queued-event-bytes))
      (throw
       (failure "Database session event delivery exceeded its limit."
                :seon.db.transport.uds.failure/event-overflow
                {::frame-bytes queued-event-bytes})))
    (cond-> (-> events
                (assoc-in [::events key]
                          {::event next-event ::frame-bytes next-frame-bytes})
                (assoc ::queued-event-bytes queued-event-bytes))
      (nil? prior) (update ::request-ids conj key))))

(defn- take-event [events]
  (when-let [key (first (::request-ids events))]
    (let [{::keys [event frame-bytes]} (get-in events [::events key])]
      {::event event
       ::events (-> events
                    (update ::request-ids subvec 1)
                    (update ::events dissoc key)
                    (update ::queued-event-bytes - frame-bytes))})))

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
  [{::keys [socket-path on-event! on-close!]
    :or {socket-path default-socket-path
         on-event! (fn [_])
         on-close! (fn [_])}}]
  (js/Promise.
   (fn [resolve-connect reject-connect]
     (let [!socket (atom nil)
           !connected? (atom false)
           !terminal? (atom false)
           !connect-settled? (atom false)
           !pending (atom {})
           !retired-request-ids (atom #{})
           !capacity-waiters (atom [])
           !output (atom (empty-output))
           !events (atom (empty-events))
           !maximum-frame-bytes
           (atom protocol/session-open-maximum-frame-bytes)
           !opening? (atom true)
           !opening-response (atom nil)
           !parser (atom (fresh-parser protocol/session-open-maximum-frame-bytes))
           !deadline-timer (atom nil)
           !event-timer (atom nil)]
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
               (notify-close! [error connected?]
                 (when connected?
                   (js/setTimeout
                    (fn []
                      (try
                        (on-close! error)
                        (catch :default _)))
                    0)))
               (settle-capacity! []
                 (when (and (not @!terminal?)
                            (< (+ (count @!pending)
                                  (count @!retired-request-ids))
                               maximum-pending-requests))
                   (let [waiters @!capacity-waiters]
                     (reset! !capacity-waiters [])
                     (doseq [{::keys [resolve]} waiters]
                       (resolve true)))))
               (wait-for-capacity! []
                 (cond
                   @!terminal?
                   (js/Promise.reject
                    (failure "Database session is closed."
                             :seon.db.transport.uds.failure/closed))

                   (>= (count @!capacity-waiters)
                       maximum-capacity-waiters)
                   (js/Promise.reject
                    (failure "Database session has no request capacity."
                             :seon.db.transport.uds.failure/busy))

                   :else
                   (js/Promise.
                    (fn [resolve reject]
                      (swap! !capacity-waiters conj
                             {::resolve resolve ::reject reject})))))
               (terminate! [reason]
                 (if @!terminal?
                   false
                   (let [error (as-error reason)
                         socket @!socket
                         connected? @!connected?
                         pending (vals @!pending)
                         capacity-waiters @!capacity-waiters]
                     (reset! !terminal? true)
                     (reset! !connected? false)
                     (when-let [timer @!deadline-timer]
                       (js/clearTimeout timer))
                     (reset! !deadline-timer nil)
                     (when-let [timer @!event-timer]
                       (js/clearTimeout timer))
                     (reset! !event-timer nil)
                     (reset! !pending {})
                     (reset! !retired-request-ids #{})
                     (reset! !capacity-waiters [])
                     (reset! !output (empty-output))
                     (reset! !events (empty-events))
                     (settle-connect! error nil)
                     (doseq [{::keys [reject]} pending]
                       (when reject
                         (reject error)))
                     (doseq [{::keys [reject]} capacity-waiters]
                       (reject error))
                     (when socket
                       (try
                         (js-invoke socket "close")
                         (catch :default _)))
                     (notify-close! error connected?)
                     true)))
               (schedule-event-delivery! []
                 (when (and (not @!terminal?)
                            (nil? @!event-timer)
                            (seq (::request-ids @!events)))
                   (reset!
                    !event-timer
                    (js/setTimeout
                     (fn []
                       (reset! !event-timer nil)
                       (when-not @!terminal?
                         (when-let [{::keys [event events]}
                                    (take-event @!events)]
                           (reset! !events events)
                           (try
                             (let [result (on-event! event)]
                               (when (and (some? result)
                                          (fn? (.-then result)))
                                 (.catch (js/Promise.resolve result)
                                         terminate!)))
                             (catch :default error
                               (terminate! error)))
                           (schedule-event-delivery!))))
                     0))))
               (enqueue-event! [event frame-bytes]
                 (try
                   (swap! !events append-event event frame-bytes)
                   (schedule-event-delivery!)
                   (catch :default error
                     (terminate! error))))
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
                          ::protocol/target-request-id target-request-id}
                         @!maximum-frame-bytes))
                       (catch :default error
                         (terminate! error))))))
               (nearest-deadline-at []
                 (reduce-kv
                  (fn [nearest _request-id entry]
                    (let [deadline-at (::deadline-at entry)]
                      (cond
                        (nil? deadline-at) nearest
                        (nil? nearest) deadline-at
                        :else (min nearest deadline-at))))
                  nil
                  @!pending))
               (schedule-deadline! []
                 (when-let [timer @!deadline-timer]
                   (js/clearTimeout timer))
                 (reset! !deadline-timer nil)
                 (when-let [deadline-at
                            (when-not @!terminal? (nearest-deadline-at))]
                   (reset!
                    !deadline-timer
                    (js/setTimeout
                     (fn []
                       (reset! !deadline-timer nil)
                       (expire-deadlines!)
                       (schedule-deadline!))
                     (max 0 (- deadline-at (js/Date.now)))))))
               (expire-deadlines! []
                 (let [now (js/Date.now)
                       expired
                       (reduce-kv
                        (fn [entries request-id entry]
                          (if (and (::deadline-at entry)
                                   (<= (::deadline-at entry) now))
                            (conj entries [request-id entry])
                            entries))
                        []
                        @!pending)]
                   (doseq [[request-id
                            {::keys [reject timeout-ms backstop-config-key]
                             :as expired-entry}]
                           expired]
                     (when-let [current-entry (get @!pending request-id)]
                       (when (identical? expired-entry current-entry)
                         (record-backstop! request-id backstop-config-key
                                           timeout-ms)
                         ;; A transaction deadline requests cancellation but
                         ;; cannot assert that the write did not commit. Keep
                         ;; its one physical callback until the authority
                         ;; resolves it or the session closes. Reads retain the
                         ;; ordinary detach-and-reject deadline contract.
                         (if (= protocol/transact-operation
                                (::protocol/operation current-entry))
                           (swap! !pending update request-id
                                  #(-> %
                                       (dissoc ::deadline-at)
                                       (assoc ::timed-out? true)))
                           (do
                             (swap! !pending dissoc request-id)
                             (swap! !retired-request-ids conj request-id)
                             (reject
                              (failure
                               "Database request timed out."
                               :seon.db.transport.uds.failure/timeout
                               {::protocol/request-id request-id
                                ::backstop-config-key backstop-config-key}))
                             (settle-capacity!)))
                         (enqueue-cancel! request-id))))))
               (deliver-message! [message frame-bytes]
                 (let [request-id (::protocol/request-id message)
                       event (::protocol/event message)]
                   (cond
                     @!opening?
                     (if (and (= protocol/session-open-request-id request-id)
                              (protocol/valid-response? message))
                       (if (::protocol/success? message)
                         (if (valid-opening-success?
                              message protocol/maximum-frame-bytes)
                           (let [ceiling (::protocol/maximum-frame-bytes message)]
                             (reset! !maximum-frame-bytes ceiling)
                             (reset! !parser (fresh-parser ceiling))
                             (reset! !opening? false)
                             (reset! !opening-response message)
                             (settle-connect! nil (session-map)))
                           (throw
                            (failure
                             "Database session received an inconsistent opening agreement."
                             :seon.db.transport.uds.failure/protocol
                             {::protocol/response message})))
                         (terminate!
                          (ex-info (::protocol/error message) message)))
                       (throw
                        (failure
                         "Database session received an invalid opening response."
                         :seon.db.transport.uds.failure/protocol
                         {::protocol/response message
                          ::protocol/explanation
                          (protocol/explain-response message)})))

                     (and (= protocol/session-control-request-id request-id)
                          (= protocol/frame-too-large-error
                             (::protocol/error-kind message))
                          (protocol/valid-response? message))
                     (terminate! (ex-info (::protocol/error message) message))

                     event
                     (if (and (contains? #{protocol/datoms-event
                                          protocol/resynchronization-event
                                          protocol/database-advanced-event}
                                        event)
                              (protocol/valid-response? message))
                       (enqueue-event! message frame-bytes)
                       (throw
                        (failure "Database session received an invalid event."
                                 :seon.db.transport.uds.failure/protocol
                                 {::protocol/response message
                                  ::protocol/explanation
                                  (protocol/explain-response message)})))
                     :else
                     (do
                       (when-not (string? request-id)
                         (throw
                          (failure "Database message has no request id."
                                   :seon.db.transport.uds.failure/protocol)))
                       (if-let [{::keys [resolve]} (get @!pending request-id)]
                         (do
                           (swap! !pending dissoc request-id)
                           (schedule-deadline!)
                           (settle-capacity!)
                           (when resolve
                             (resolve message)))
                         (when (contains? @!retired-request-ids request-id)
                           (swap! !retired-request-ids disj request-id)
                           (settle-capacity!)))))))
               (receive! [chunk]
                 (when-not @!terminal?
                   (try
                     (let [{::keys [parser payloads]}
                           (consume-chunk @!parser chunk)]
                       (reset! !parser parser)
                       (doseq [payload payloads]
                         (when-not @!terminal?
                           (deliver-message! (decode-payload payload)
                                             (.-byteLength ^js payload)))))
                     (catch :default error
                       (terminate! error)))))
               (request-map!
                 [{::keys [message timeout-ms backstop-config-key] :as request}]
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

                     (or (contains? @!pending request-id)
                         (contains? @!retired-request-ids request-id))
                     (js/Promise.reject
                      (failure "Database request id is already pending."
                               :seon.db.transport.uds.failure/duplicate
                               {::protocol/request-id request-id}))

                     (and timeout-ms (not (keyword? backstop-config-key)))
                     (js/Promise.reject
                      (failure
                       "Database request deadline has no governing config fact."
                       :seon.db.transport.uds.failure/protocol
                       {::protocol/request-id request-id}))

                     (>= (+ (count @!pending)
                            (count @!retired-request-ids))
                         maximum-pending-requests)
                     (-> (wait-for-capacity!)
                         (.then (fn [_] (request-map! request))))

                     :else
                     (js/Promise.
                      (fn [resolve reject]
                        (try
                           (let [frame
                                 (try
                                   (encode-frame message @!maximum-frame-bytes)
                                   (catch :default error
                                     (if (= :seon.db.transport.uds.failure/too-large
                                            (::failure (ex-data error)))
                                       (throw
                                        (ex-info
                                         "The database protocol frame is too large."
                                         (protocol/frame-too-large-failure
                                          {::protocol/request-id request-id
                                           ::protocol/maximum-frame-bytes
                                           @!maximum-frame-bytes})))
                                       (throw error))))
                                entry (cond-> {::resolve resolve
                                               ::reject reject
                                               ::protocol/operation
                                               (::protocol/operation message)}
                                        timeout-ms
                                        (assoc
                                         ::deadline-at
                                         (+ (js/Date.now) timeout-ms)
                                         ::timeout-ms timeout-ms
                                         ::backstop-config-key
                                         backstop-config-key))]
                            (swap! !pending assoc request-id entry)
                            (schedule-deadline!)
                            (when-not (enqueue-frame! frame)
                              (when-let [owned (get @!pending request-id)]
                                (when (identical? owned entry)
                                  (swap! !pending dissoc request-id)
                                  (schedule-deadline!)
                                  (settle-capacity!)
                                  (reject
                                   (failure "Database session is closed."
                                            :seon.db.transport.uds.failure/closed))))))
                          (catch :default error
                            (swap! !pending dissoc request-id)
                            (schedule-deadline!)
                            (settle-capacity!)
                            (reject error))))))))
               (session-map []
                 {::connected? (fn [] @!connected?)
                  ::request! request-map!
                  ::close! #(terminate!
                             (failure
                              "Database session was closed by its owner."
                              :seon.db.transport.uds.failure/closed
                              {::closed-by-owner? true}))
                  ::pending-count #(count @!pending)
                  ::queued-bytes #(::queued-bytes @!output)
                  ::pending-event-count #(count (::events @!events))
                  ::queued-event-bytes #(::queued-event-bytes @!events)
                  ::version (or (::protocol/version @!opening-response)
                                protocol/current-version)
                  ::configured-maximum-frame-bytes
                  (::protocol/configured-maximum-frame-bytes @!opening-response)
                  ::maximum-frame-bytes @!maximum-frame-bytes})
               (opened! [socket]
                 (when (and (not @!terminal?) (not @!connected?))
                   (reset! !socket socket)
                   (reset! !connected? true)
                   (enqueue-frame!
                    (encode-frame
                     (protocol/session-open-request
                      {::protocol/maximum-frame-bytes
                       protocol/maximum-frame-bytes})
                     protocol/session-open-maximum-frame-bytes))))]
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
               options (js-obj "unix" socket-path
                               "allowHalfOpen" false
                               "socket" handler)]
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
  "Send one request with an optional caller-selected deadline."
  {:malli/schema [:=> [:catn [::request ::request]] :any]}
  [{::keys [session] :as request}]
  (-> ((::request! session) (dissoc request ::session))
      (.catch
       (fn [exception]
         ;; Capacity is ordinary flow control for the multiplexed session.
         ;; Keep it as data below public async instrumentation so the
         ;; transaction owner can wait and retry the exact immutable request
         ;; without recording an expected rejection as a core fault.
         (if (= :seon.db.transport.uds.failure/busy
                (::failure (ex-data exception)))
           (assoc (ex-data exception)
                  ::message
                  (or (.-message exception)
                      "Database session has no request capacity."))
           (throw exception))))))

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

(defn connect-stream!
  "Open one framed text-payload session on the shared four-byte codec.

   The execution boundary owns its own Transit message codec, so this
   session carries its already-encoded payload text verbatim: outbound
   text is length-prefixed onto the socket and every inbound frame's
   utf-8 text reaches `on-text!` in arrival order without request
   correlation. `on-close!` receives the terminal error exactly once
   after a successful open."
  {:malli/schema [:=> [:catn [::request ::stream-request]] :any]}
  [{::keys [socket-path on-text! on-close!]
    :or {on-close! (fn [_])}}]
  (js/Promise.
   (fn [resolve-connect reject-connect]
     (let [!socket (atom nil)
           !connected? (atom false)
           !terminal? (atom false)
           !connect-settled? (atom false)
           !output (atom (empty-output))
           !parser (atom (fresh-parser))]
       (letfn [(as-error [reason]
                 (if (instance? js/Error reason)
                   reason
                   (failure (str reason)
                            :seon.db.transport.uds.failure/closed)))
               (settle-connect! [error stream]
                 (when-not @!connect-settled?
                   (reset! !connect-settled? true)
                   (if error
                     (reject-connect error)
                     (resolve-connect stream))))
               (terminate! [reason]
                 (if @!terminal?
                   false
                   (let [error (as-error reason)
                         socket @!socket
                         connected? @!connected?]
                     (reset! !terminal? true)
                     (reset! !connected? false)
                     (reset! !output (empty-output))
                     (settle-connect! error nil)
                     (when socket
                       (try
                         (js-invoke socket "close")
                         (catch :default _)))
                     (when connected?
                       (js/setTimeout
                        (fn []
                          (try
                            (on-close! error)
                            (catch :default _)))
                        0))
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
               (enqueue-text! [text]
                 (if @!terminal?
                   (failure "The stream session is closed."
                            :seon.db.transport.uds.failure/closed)
                   (try
                     (swap! !output append-output (text-frame text))
                     (flush-output!)
                     (when @!terminal?
                       (failure "The stream session is closed."
                                :seon.db.transport.uds.failure/closed))
                     (catch :default error
                       (terminate! error)
                       error))))
               (receive! [chunk]
                 (when-not @!terminal?
                   (try
                     (let [{::keys [parser payloads]}
                           (consume-chunk @!parser chunk)]
                       (reset! !parser parser)
                       (doseq [payload payloads]
                         (when-not @!terminal?
                           (on-text! (payload-text payload)))))
                     (catch :default error
                       (terminate! error)))))
               (stream-map []
                 {::connected? (fn [] @!connected?)
                  ::send-text!
                  (fn [text]
                    (when-let [error (enqueue-text! text)]
                      {:seon.error/message (str (.-message ^js error))}))
                  ::close! #(terminate!
                             (failure
                              "The stream session was closed by its owner."
                              :seon.db.transport.uds.failure/closed
                              {::closed-by-owner? true}))})
               (opened! [socket]
                 (when (and (not @!terminal?) (not @!connected?))
                   (reset! !socket socket)
                   (reset! !connected? true)
                   (settle-connect! nil (stream-map))
                   (flush-output!)))]
         (let [handler
               (js-obj
                "binaryType" "uint8array"
                "open" (fn [socket] (opened! socket))
                "data" (fn [_socket chunk] (receive! chunk))
                "drain" (fn [_socket] (flush-output!))
                "error" (fn [_socket error] (terminate! error))
                "end" (fn [_socket]
                        (terminate!
                         (failure "The stream peer ended the session."
                                  :seon.db.transport.uds.failure/closed)))
                "close" (fn [_socket error]
                          (terminate!
                           (or error
                               (failure "The stream session closed."
                                        :seon.db.transport.uds.failure/closed))))
                "connectError" (fn [_socket error]
                                 (terminate! error)))
               options (js-obj "unix" socket-path
                               "allowHalfOpen" false
                               "socket" handler)]
           (try
             (-> (@!connect-native options)
                 (.then opened!)
                 (.catch (fn [error]
                           (terminate! error))))
             (catch :default error
               (terminate! error)))))))))

(defn close-stream!
  "Close a stream session and release its socket once."
  {:malli/schema [:=> [:catn [::stream ::stream]] :boolean]}
  [stream]
  ((::close! stream)))
