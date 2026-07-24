(ns seon.host.session.leaf
  "Own the JVM execution wire projection and one host session's state."
  (:require [seon.ai.tokens :as tokens]
            [seon.host.session :as protocol]
            [seon.db.protocol :as db.protocol]
            [seon.db.transport.uds :as uds]
            [seon.schema :as schema])
  (:import [java.io OutputStream]
           [java.nio.channels Channels SocketChannel]))

(set! *warn-on-reflection* true)

(schema/register! ::session
                  [:map
                   [::channel 'some?]
                   [::input 'some?]
                   [::output 'some?]
                   [::write-lock 'some?]
                   [::startup 'some?]
                   [::active 'some?]
                   [::active-run 'some?]
                   [::cancel-requested? 'some?]
                   [::interrupt-lock 'some?]
                   [::interrupt-fired? 'some?]
                   [::worker-phase 'some?]
                   [::live-values 'some?]
                   [::contexts 'some?]
                   [::writer 'some?]
                   [::projection-state 'some?]
                   [::eval-pool 'some?]
                   [::watchdog 'some?]
                   [::ctx {:optional true} 'some?]])

(def ^:private error-frame-token-cap
  "Wire error-message budget; W1 moves it to a config fact."
  120)
(defn now-ms
  "Current wall-clock time in milliseconds."
  {:malli/schema [:=> [:cat] :int]}
  []
  (System/currentTimeMillis))

(defn error-value
  "Build one execution error value."
  {:malli/schema [:function
                  [:=> [:cat :string :keyword] :map]
                  [:=> [:cat :string :keyword [:or :nil :map]] :map]]}
  ([message kind] (error-value message kind nil))
  ([message kind data]
   (cond-> {:seon.error/message message :seon.error/kind kind}
     (seq data) (assoc :seon.error/data data))))

(defn- bounded-error-value [error]
  (update error :seon.error/message
          #(tokens/clip-str % error-frame-token-cap)))

(defn error-frame
  "Build one bounded invocation error frame."
  {:malli/schema [:function
                  [:=> [:cat :string :map] :map]
                  [:=> [:cat :string :map [:or :nil :seon.db/db]] :map]]}
  ([invocation-id error] (error-frame invocation-id error nil))
  ([invocation-id error database]
   (cond-> {:seon.execution/message protocol/error-message
            :seon.execution/protocol-version protocol/protocol-version
            :seon.execution/invocation-id invocation-id
            :seon.execution/error (bounded-error-value error)}
     database (assoc :seon.db/db database))))

(defn result-frame
  "Build one correlated invocation result frame."
  {:malli/schema [:=> [:cat :string :seon.db/db :any :int] :map]}
  [invocation-id database value result-bytes]
  {:seon.execution/message protocol/result-message
   :seon.execution/protocol-version protocol/protocol-version
   :seon.execution/invocation-id invocation-id
   :seon.db/db database
   :seon.execution/result value
   :seon.execution/result-bytes result-bytes})

(defn sample-error-frame
  "Build one correlated value-sample error frame."
  {:malli/schema [:function
                  [:=> [:cat :map :string] :map]
                  [:=> [:cat :map :string :keyword] :map]]}
  ([sample message] (sample-error-frame sample message :core-bug))
  ([sample message kind]
  {:seon.execution/message protocol/value-sample-error-message
   :seon.execution/protocol-version protocol/protocol-version
   :seon.execution/agent-id (:seon.execution/agent-id sample)
   :seon.execution/request-id (:seon.execution/request-id sample)
   :seon.execution/error (error-value message kind)}))
(defn- fallback-error-frame [message]
  (let [execution-error (:seon.execution/error message)
        invocation-id (or (:seon.execution/invocation-id message) "invalid")]
    (if (contains? message :seon.execution/request-id)
      (sample-error-frame
       {:seon.execution/agent-id
        (or (:seon.execution/agent-id message) "invalid")
        :seon.execution/request-id
        (or (:seon.execution/request-id message) "invalid")}
       (or (:seon.error/message execution-error)
           "The execution response could not cross its frame boundary."))
      (error-frame
       invocation-id
       (error-value
        (or (:seon.error/message execution-error)
            "The execution response could not cross its frame boundary.")
        (or (:seon.error/kind execution-error) :core-bug))
       (:seon.db/db message)))))

(defn- encodable-frame? [message]
  (let [^bytes payload (uds/encode message)]
    (<= (alength payload) db.protocol/maximum-frame-bytes)))

(defn- guaranteed-fallback-frame [message]
  (let [fallback (fallback-error-frame message)]
    (if (try (encodable-frame? fallback) (catch Throwable _ false))
      fallback
      (error-frame (or (:seon.execution/invocation-id message) "invalid")
                   (error-value "The execution response was unavailable."
                                :core-bug)))))

(defn prepare-frame
  "Prepare one frame that fits the transport boundary."
  {:malli/schema [:=> [:cat :map] :map]}
  [message]
  (let [message (cond-> message
                  (:seon.execution/error message)
                  (update :seon.execution/error bounded-error-value))]
    (try
      (if (encodable-frame? message)
        message
        (guaranteed-fallback-frame message))
      (catch Throwable _
        (guaranteed-fallback-frame message)))))

(defn write-prepared-frame!
  "Write one prepared frame under the session write lock."
  {:malli/schema [:=> [:cat ::session :map] :nil]}
  [session message]
  (locking (::write-lock session)
    (uds/write-frame! ^OutputStream (::output session) message))
  nil)

(defn send-frame!
  "Prepare and write one bounded frame under the session write lock."
  {:malli/schema [:=> [:cat ::session :map] :nil]}
  [session message]
  (write-prepared-frame! session (prepare-frame message)))

(defn bounded-result
  "Return `{::ok? true ::value ::result-bytes}` or a bounded error value.

   Mirrors `seon.execution/bounded-result`: the value must encode as
   Transit and fit the invocation's byte limit; failures are `:agent`
   error values, never throws."
  {:malli/schema [:=> [:cat :any [:int {:min 1}]] :map]}
  [value result-limit]
  (let [encoded (try {::bytes (uds/encode {:seon.execution/value value})}
                     (catch Throwable throwable
                       {::encode-error (.getMessage throwable)}))]
    (if-let [^bytes payload (::bytes encoded)]
      (let [byte-count (alength payload)]
        (if (<= byte-count result-limit)
          {::ok? true ::value value ::result-bytes byte-count}
          {::ok? false
           ::error (error-value
                    "The function result exceeded its byte limit."
                    :agent
                    {:seon.execution/result-bytes byte-count
                     :seon.execution/result-limit-bytes result-limit})}))
      {::ok? false
       ::error (error-value
                "The function returned a value that cannot cross IPC."
                :agent
                {::encode-error (::encode-error encoded)})})))
(defn invalid-message-frame
  "Build the error frame for an invalid parent message."
  {:malli/schema [:=> [:cat :map] :map]}
  [message]
  (error-frame (or (:seon.execution/invocation-id message) "invalid")
               (error-value "The parent sent an invalid execution message."
                            :core-bug)))

(defn startup-error
  "Send one startup error frame."
  {:malli/schema [:=> [:cat ::session :string] :nil]}
  [session message]
  (send-frame! session (error-frame "startup"
                                    (error-value message :core-bug)))
  nil)

(defn session-map
  "Construct one process-local host session map."
  {:malli/schema [:=> [:cat :map ::channel] ::session]}
  [host ^SocketChannel channel]
  {::channel channel
   ::input (Channels/newInputStream channel)
   ::output (Channels/newOutputStream channel)
   ::write-lock (Object.)
   ::startup (atom nil)
   ::active (atom nil)
   ::active-run (atom nil)
   ::cancel-requested? (atom false)
   ::interrupt-lock (Object.)
   ::interrupt-fired? (atom false)
   ::worker-phase (atom :idle)
   ::live-values (atom {::order [] ::values {}})
   ::contexts (:seon.host/contexts host)
   ::writer (:seon.host/writer host)
   ::projection-state (:seon.host/projection-state host)
   ::eval-pool (:seon.host/eval-pool host)
   ::watchdog (:seon.host/watchdog host)})
