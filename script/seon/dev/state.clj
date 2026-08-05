(ns seon.dev.state
  "Atomic filesystem state and lifecycle locks for the Seon operator."
  (:require [babashka.fs :as fs]
            [clojure.string :as string]
            [seon.operator.state :as operator.state])
  (:import [java.io RandomAccessFile]
           [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]
           [java.util.concurrent TimeUnit]))

(def process-start-instant operator.state/process-start-instant)

(def process-identity-alive? operator.state/process-identity-alive?)

(def read-edn operator.state/read-edn)
(def write-edn! operator.state/write-edn!)
(def delete-edn! operator.state/delete-edn!)

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
