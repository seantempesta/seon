(ns seon.db.writer-test-support
  "Shared admitted database-session fixtures for JVM writer tests."
  (:require [seon.config.resolve :as config.resolve]
            [seon.db.host :as db.host]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]
            [seon.schema :as schema]))

(def ^:private fixture-hardware
  {:seon.hardware/cores 8
   :seon.hardware/system-memory-bytes (* 32 1024 1024 1024)
   :seon.hardware/fd-soft-limit 2048})

(def guard-policy
  "The production-resolved default SCI guard facts for JVM writer fixtures."
  (select-keys
   (config.resolve/resolve-config-singleton
    {:seon.config/guard
     {:seon.config.guard/output-cap 16384}}
    {}
    fixture-hardware)
   (keys config.resolve/guard-budget-schemas)))

(defn canonical-schema-rows
  "Derive fixture schema rows from the loaded canonical schema authority."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (schema/canonical-schema-rows (java.util.Date.)))

(defn seed-canonical-schema!
  "Commit the computed canonical schema population with boot provenance."
  [session database-name initial-data]
  (let [database (db.host/resolve-db! session nil false)]
    (db.host/call!
     session
     (protocol/transaction-request
      {::protocol/request-id (str (random-uuid))
       :seon.db/db database
       ::protocol/transaction-data
       (into (canonical-schema-rows) initial-data)
       ::protocol/transaction-meta
       {:seon.db/user [:seon.agent/id "root"]
        :seon.db/process
        [:seon.db.process/id :seon.db.process/boot]}}))))

(def read-defaults
  "Generous finite read limits for writer tests not exercising read policy."
  {:datahike.resource/max-work 2000000000
   :datahike.resource/max-results 10000000
   :datahike.resource/max-result-weight 100000000
   ::writer/read-deadline-ms 600000})

(defn start!
  "Start a test writer with the shared finite read limits."
  [request]
  (writer/start! (assoc request ::writer/read-defaults read-defaults)))

(def ^:private sessions-by-channel
  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))

(defn open-session!
  "Open and admit one database session at `path`."
  [path]
  (uds/open-session! path))

(defn channel
  "Return the raw channel retained by an admitted database session."
  [session]
  (::uds/channel session))

(defn open-channel!
  "Open one admitted session and return its retained raw channel."
  [path]
  (let [session (open-session! path)
        channel (channel session)]
    (.put sessions-by-channel channel (dissoc session ::uds/channel))
    channel))

(defn call!
  "Round-trip one request through an admitted database session."
  [session request]
  (uds/call! {::uds/session session ::uds/message request}))

(defn call-channel!
  "Round-trip through the admitted session retaining `channel`."
  [channel request]
  (call! (assoc (.get sessions-by-channel channel) ::uds/channel channel)
         request))

(defn close-session!
  "Close an admitted database session."
  [session]
  (uds/close-session! session))
