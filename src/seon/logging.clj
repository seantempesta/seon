(ns seon.logging
  "Centralized Timbre logging configuration for Seon.

  Call `(configure! {})` early in startup to set up file appenders.
  Timbre handles all application logging (seon.* namespaces).
  Logback handles library logging (Datalevin, nREPL, etc.) via SLF4J.

  Log files:
    logs/app.log      - Current session, all levels (rotating, 50MB max)
    logs/startup.log  - Wiped each startup, captures boot sequence
    logs/error.log    - Errors only (via logback, library errors)

  ## Rotation

  File appenders use `rotating-appender` which checks file size before each
  write and rotates to `.1`, `.2`, etc. when the limit is hit. Oldest files
  beyond `max-backlog` are deleted.

  ## Cleanup

  `configure!` also runs `cleanup-old-logs!` on startup to remove orphan
  files: stale protocol captures, old agent logs, and leftover xtdb files."
  (:require
   [clojure.java.io :as io]
   [taoensso.timbre :as timbre]
   [seon.schema :as schema])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(schema/register! ::log-dir
                  [:string {:default "logs"
                            :description "Directory for log files"}])

(schema/register! ::status
                  [:enum :ok :error])

(schema/register! ::configure-request
                  [:map {:closed true}
                   [::log-dir {:optional true} ::log-dir]])

(schema/register! ::configure-response
                  [:map
                   [::status ::status]
                   [::log-dir ::log-dir]])

(schema/register! ::max-size
                  [:int {:min 1024
                         :description "Max file size in bytes before rotation"}])

(schema/register! ::max-backlog
                  [:int {:min 0
                         :description "Number of rotated files to keep"}])

(schema/register! ::cleanup-result
                  [:map
                   [::files-deleted :int]
                   [::bytes-freed :int]])

;;; ---------------------------------------------------------------------------
;;; Rotating File Writer
;;; ---------------------------------------------------------------------------

(defn- rotate-files!
  "Rotate log files: app.log -> app.log.1, .1 -> .2, etc.
   Deletes files beyond max-backlog. Returns true if rotation succeeded."
  [^String path max-backlog]
  (let [f (io/file path)]
    ;; Delete the oldest if it exists
    (let [oldest (io/file (str path "." max-backlog))]
      (when (.exists oldest)
        (.delete oldest)))
    ;; Shift existing rotated files up by one
    (doseq [i (range (dec max-backlog) 0 -1)]
      (let [src (io/file (str path "." i))
            dst (io/file (str path "." (inc i)))]
        (when (.exists src)
          (.renameTo src dst))))
    ;; Move current to .1
    (if (.exists f)
      (.renameTo f (io/file (str path ".1")))
      true)))

(defn rotating-appender
  "Returns a Timbre appender that writes to a rotating log file.

   Options:
     :fname       - Log file path (required)
     :max-size    - Max bytes before rotation (default 50MB)
     :max-backlog - Number of rotated files to keep (default 3)

   On each write, checks file size. When exceeded, rotates:
     app.log -> app.log.1 -> app.log.2 -> app.log.3 (deleted)"
  [{:keys [fname max-size max-backlog]
    :or {max-size    (* 50 1024 1024)  ; 50MB
         max-backlog 3}}]
  {:enabled? true
   :fn
   (let [lock (Object.)]
     (fn [{:keys [output_]}]
       (let [output-str (str (force output_) "\n")]
         (try
           (locking lock
             (let [log-file (io/file fname)]
               (when-not (.exists log-file)
                 (io/make-parents log-file))
               (when (> (.length log-file) (long max-size))
                 (rotate-files! fname max-backlog)
                 ;; If rotation failed, truncate to prevent unbounded growth
                 (when (and (.exists log-file) (> (.length log-file) (long max-size)))
                   (spit fname ""))))
             (spit fname output-str :append true))
           (catch java.io.IOException _)))))})

;;; ---------------------------------------------------------------------------
;;; Rotating Line Writer (for non-Timbre use, e.g. process stdout piping)
;;; ---------------------------------------------------------------------------

(defn create-rotating-writer
  "Create a rotating writer state map for piping process output to a log file.
   Returns a map with {:path, :max-size, :max-backlog, :lock}.

   Use with `write-line!` to write lines with automatic rotation."
  [{:keys [path max-size max-backlog]
    :or {max-size    (* 50 1024 1024)
         max-backlog 3}}]
  {:path path
   :max-size (long max-size)
   :max-backlog max-backlog
   :lock (Object.)})

(defn write-line!
  "Write a line to a rotating log file. Thread-safe.
   Checks file size before each write and rotates if needed.
   If rotation fails (e.g. renameTo returns false), truncates the file
   to prevent unbounded growth."
  [{:keys [path max-size max-backlog lock]} line]
  (locking lock
    (let [f (io/file path)]
      (when-not (.exists f)
        (io/make-parents f))
      (when (> (.length f) (long max-size))
        (rotate-files! path max-backlog)
        ;; If the file still exists and is still too large, rotation failed.
        ;; Truncate to prevent unbounded growth.
        (when (and (.exists f) (> (.length f) (long max-size)))
          (spit path ""))))
    (spit path (str line "\n") :append true)))

;;; ---------------------------------------------------------------------------
;;; Old Log Cleanup
;;; ---------------------------------------------------------------------------

(defn- file-age-days
  "Age of a file in days (since last modification)."
  [^File f]
  (let [age-ms (- (System/currentTimeMillis) (.lastModified f))]
    (/ age-ms (* 1000 60 60 24.0))))

(defn- delete-matching-files!
  "Delete files matching a predicate. Returns [count bytes-deleted]."
  [^File dir predicate]
  (if (and dir (.isDirectory dir))
    (let [files (filter predicate (or (.listFiles dir) []))]
      (reduce (fn [[n bytes] ^File f]
                (let [size (.length f)]
                  (if (.delete f)
                    [(inc n) (+ bytes size)]
                    [n bytes])))
              [0 0]
              files))
    [0 0]))

(defn cleanup-old-logs!
  "Remove orphan and stale log files. Run during configure!.

   Cleans:
   - logs/protocol-capture-*.jsonl older than 7 days
   - logs/agents/*.log older than 7 days
   - logs/xtdb.* files (stale, from old system)
   - logs/hook-debug.log if > 10MB (truncates to empty)

   Returns ::cleanup-result map with files-deleted and bytes-freed."
  [log-dir]
  (let [dir (io/file log-dir)
        agents-dir (io/file log-dir "agents")
        max-age-days 7
        ;; Protocol captures older than 7 days
        [n1 b1] (delete-matching-files!
                  dir
                  (fn [^File f]
                    (and (.isFile f)
                         (.startsWith (.getName f) "protocol-capture-")
                         (.endsWith (.getName f) ".jsonl")
                         (> (file-age-days f) max-age-days))))
        ;; Agent logs older than 7 days
        [n2 b2] (delete-matching-files!
                  agents-dir
                  (fn [^File f]
                    (and (.isFile f)
                         (.endsWith (.getName f) ".log")
                         (> (file-age-days f) max-age-days))))
        ;; Stale xtdb files (any logs/xtdb.*)
        [n3 b3] (delete-matching-files!
                  dir
                  (fn [^File f]
                    (and (.isFile f)
                         (.startsWith (.getName f) "xtdb."))))
        ;; hook-debug.log — truncate if > 10MB
        hook-debug (io/file log-dir "hook-debug.log")
        [n4 b4] (if (and (.exists hook-debug)
                          (> (.length hook-debug) (* 10 1024 1024)))
                  (let [size (.length hook-debug)]
                    (spit hook-debug "")
                    [1 size])
                  [0 0])
        total-files (+ n1 n2 n3 n4)
        total-bytes (+ b1 b2 b3 b4)]
    (when (pos? total-files)
      (timbre/info "Cleaned up old logs"
                   {:files-deleted total-files
                    :bytes-freed total-bytes
                    :protocol-captures n1
                    :agent-logs n2
                    :xtdb-files n3
                    :hook-debug-truncated (pos? n4)}))
    {::files-deleted total-files
     ::bytes-freed total-bytes}))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(defn configure!
  "Configure Timbre with rotating file appenders. Call once at startup.

  Sets up:
  - :println  - stdout (already default)
  - :app-file - <log-dir>/app.log (rotating, 50MB, 3 backlog)
  - :startup  - <log-dir>/startup.log (rotating, 5MB, 1 backlog)

  Also runs cleanup of stale log files.

  Usage:
    (configure! {})
    (configure! {::log-dir \"logs\"})"
  {:malli/schema [:=> [:cat ::configure-request] ::configure-response]}
  [{::keys [log-dir] :or {log-dir "logs"}}]
  (.mkdirs (File. ^String log-dir))

  ;; Clean up stale files before configuring
  (cleanup-old-logs! log-dir)

  ;; Wipe startup.log on each startup
  (let [startup-path (str log-dir "/startup.log")
        app-path     (str log-dir "/app.log")]
    (spit startup-path "")

    (timbre/merge-config!
     {:min-level :info

      :appenders
      {:println  {:enabled? true}

       :app-file (rotating-appender
                  {:fname app-path
                   :max-size (* 50 1024 1024)     ; 50MB
                   :max-backlog 3})

       :startup  (rotating-appender
                  {:fname startup-path
                   :max-size (* 5 1024 1024)      ; 5MB
                   :max-backlog 1})}})

    {::status  :ok
     ::log-dir log-dir}))
