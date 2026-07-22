(ns seon.db.writer-test-support
  "Shared admitted database-session fixtures for JVM writer tests."
  (:require [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]))

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
