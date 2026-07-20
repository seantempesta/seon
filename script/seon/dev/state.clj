(ns seon.dev.state
  "Atomic filesystem state and lifecycle locks for the Seon operator."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as string])
  (:import [java.io RandomAccessFile]
           [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]
           [java.util.concurrent TimeUnit]))

(defn process-start-instant
  "Return the OS start instant for a live PID."
  [pid]
  (try
    (let [optional (java.lang.ProcessHandle/of (long pid))]
      (when (.isPresent optional)
        (let [instant (.startInstant (.info (.get optional)))]
          (when (.isPresent instant) (str (.get instant))))))
    (catch Throwable _ nil)))

(defn process-identity-alive?
  "True when a PID still has the recorded OS start instant."
  [{:seon.dev.process/keys [pid start-instant]}]
  (and (integer? pid)
       (string? start-instant)
       (= start-instant (process-start-instant pid))))

(defn read-edn
  "Read one EDN state record when it exists."
  [path]
  (when (fs/regular-file? path)
    (edn/read-string (slurp (str path)))))

(defn- sync-path! [path options]
  (with-open [channel
              (FileChannel/open (fs/path path)
                                (into-array OpenOption options))]
    (.force channel true)))

(defn write-edn!
  "Durably replace one EDN state record."
  [path value]
  (let [path (fs/path path)
        parent (fs/parent path)
        temp (fs/path (str path "." (random-uuid) ".tmp"))]
    (fs/create-dirs parent)
    (try
      (spit (str temp) (str (pr-str value) "\n"))
      (sync-path! temp [StandardOpenOption/WRITE])
      (fs/move temp path {:replace-existing true :atomic-move true})
      (sync-path! parent [])
      value
      (finally
        (fs/delete-if-exists temp)))))

(defn delete-edn!
  "Durably delete one EDN state record when present."
  [path]
  (let [path (fs/path path)
        deleted? (fs/delete-if-exists path)]
    (when deleted?
      (sync-path! (fs/parent path) []))
    (boolean deleted?)))

(defn- try-lock [channel]
  (try
    (.tryLock channel)
    (catch Throwable error
      (if (= "java.nio.channels.OverlappingFileLockException"
             (.getName (class error)))
        nil
        (throw error)))))

(defn with-lock
  "Run a lifecycle transition under one kernel-owned file lock."
  [config lock-name timeout-ms transition]
  (let [process-dir (:seon.dev.config/process-dir config)]
    (when-not (and (string? process-dir)
                   (not (string/blank? process-dir))
                   (fs/absolute? process-dir))
      (throw (ex-info "with-lock requires an absolute :seon.dev.config/process-dir"
                      {:seon.dev.lock/name lock-name
                       :seon.dev.config/process-dir process-dir}))))
  (let [directory (fs/path (:seon.dev.config/process-dir config) "locks")
        path (fs/path directory (str (name lock-name) ".lock"))
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (fs/create-dirs directory)
    (loop []
      (let [file (RandomAccessFile. (str path) "rw")
            channel (.getChannel file)
            lock (try-lock channel)]
        (if lock
          (try
            (transition)
            (finally
              ;; Closing the channel releases its FileLock. Babashka permits
              ;; FileChannel/close but intentionally does not expose
              ;; FileLock/release through SCI.
              (.close channel)
              (.close file)))
          (do
            (.close channel)
            (.close file)
            (if (< (System/currentTimeMillis) deadline)
              (do (.sleep TimeUnit/MILLISECONDS 50) (recur))
              (throw (ex-info "Timed out waiting for the Seon lifecycle lock."
                              {:seon.dev.lock/name lock-name})))))))))
