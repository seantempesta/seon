(ns seon.cluster.process
  "The one owner of JVM process identity and liveness.

  A pid alone is not an identity because operating systems recycle it.
  Seon therefore identifies a process as `(pid, start-instant)` and
  compares both halves at millisecond precision, matching the platform
  projection stored in advertisements and ancestor scratch names."
  (:require [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn current-identity
  "This JVM's `(pid, start-instant)` identity.

  Refuses when the platform cannot publish the start instant: a pid
  without its generation is not safe ownership evidence."
  {:malli/schema [:=> [:cat] :seon.cluster.process/identity]}
  []
  (let [handle (java.lang.ProcessHandle/current)
        start (.startInstant (.info handle))]
    (when-not (.isPresent start)
      (throw
       (ex-info
        "The process start instant is unavailable."
        {:seon.error/kind :seon.cluster.process/start-instant-unavailable
         :seon.boot/pid (.pid handle)})))
    {:seon.boot/pid (.pid handle)
     :seon.boot/start-instant (java.util.Date/from (.get start))}))

(defn live?
  "True exactly when `identity` names a currently live JVM generation.

  A recycled pid whose current start instant differs is dead from the
  recorded owner's perspective. Platform lookup failures are absence,
  not exceptions escaping a liveness derivation."
  {:malli/schema [:=> [:cat :seon.cluster.process/identity] :boolean]}
  [{:seon.boot/keys [pid start-instant]}]
  (try
    (let [optional (java.lang.ProcessHandle/of (long pid))]
      (boolean
       (when (.isPresent optional)
         (let [handle (.get optional)
               start (.startInstant (.info handle))]
           (and (.isAlive handle)
                (.isPresent start)
                (= (inst-ms start-instant)
                   (.toEpochMilli ^java.time.Instant (.get start))))))))
    (catch Throwable _
      false)))
