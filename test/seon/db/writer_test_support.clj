(ns seon.db.writer-test-support
  "Shared admitted database-session fixtures for JVM writer tests."
  (:require [seon.db.transport.uds :as uds]))

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
