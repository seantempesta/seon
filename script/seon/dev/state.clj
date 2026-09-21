(ns seon.dev.state
  "Atomic tooling records; process-root lifecycle is owned by boot and the store."
  (:require [babashka.fs :as fs] [clojure.edn :as edn]
            [seon.cluster.process :as process])
  (:import [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]))
(def process-start-instant process/process-start-instant)
(def process-identity-alive? process/process-identity-alive?)
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
  "Durably replace one EDN state record by fsync + atomic rename."
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


(defonce ^:private held-locks (atom #{}))

(defn with-lock
  "Fail fast when another tooling transition holds this resource."
  [config lock-name timeout-ms transition]
  (let [directory (:seon.dev.config/process-dir config)
        _ (when-not (and directory (fs/absolute? directory) (pos-int? timeout-ms))
            (throw (ex-info "Tooling lock needs absolute directory and declared bound." config)))
        path (fs/path directory "locks" (str (name lock-name) ".lock"))
        key (str path)
        busy! #(throw (ex-info "Tooling transition is busy; retry after it exits."
                              {:seon.dev.lock/path key}))]
    ;; A second descriptor's close can release this process's existing fcntl lock.
    ;; Reserve before opening, just as the store lock owner does.
    (locking held-locks
      (when (contains? @held-locks key) (busy!))
      (swap! held-locks conj key))
    (try
      (fs/create-dirs (fs/parent path))
      (with-open [channel (FileChannel/open path (into-array OpenOption
                                              [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
        (if-let [lock (.tryLock channel)]
          (with-open [lock lock] (transition))
          (busy!)))
      (finally (swap! held-locks disj key)))))
