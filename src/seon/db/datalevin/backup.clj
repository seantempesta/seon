(ns seon.db.datalevin.backup
  "Backup coordination for Datalevin databases.

   Provides periodic backups using the writer flow's pause/resume for consistency:
   pause all writer flows → flush → copy LMDB data dir → resume.

   Usage:
     (backup/backup! {::backup/data-dir \"data/datalevin\"
                      ::backup/backup-dir \"data/backups\"})

     (backup/list-backups {::backup/backup-dir \"data/backups\"})

     (backup/prune! {::backup/backup-dir \"data/backups\"
                     ::backup/keep 5})"
  (:require [clojure.java.io :as io]
            [seon.db :as db]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.io File]
           [java.nio.file Files Path CopyOption StandardCopyOption LinkOption]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]
           [java.time.format DateTimeFormatter]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::data-dir
  [:string {:min 1 :description "Path to Datalevin data directory"}])

(schema/register! ::backup-dir
  [:string {:min 1 :description "Path to backup directory"}])

(schema/register! ::backup-path
  [:string {:min 1 :description "Full path to a specific backup"}])

(schema/register! ::keep
  [:int {:min 1 :description "Number of backups to keep"}])

(schema/register! ::status
  [:enum {:description "Operation status"} :ok :error])

(schema/register! ::elapsed-ms
  [:double {:min 0 :description "Operation duration in milliseconds"}])

(schema/register! ::created-at
  [:fn {:description "java.time.Instant"} #(instance? Instant %)])

(schema/register! ::size-bytes
  [:int {:min 0 :description "Size in bytes"}])

(schema/register! ::pruned
  [:int {:min 0 :description "Number of backups pruned"}])

(schema/register! ::kept
  [:int {:min 0 :description "Number of backups kept"}])

(schema/register! ::error-message
  [:string {:description "Error message"}])

;; Request schemas
(schema/register! ::backup-request
  [:map
   [::data-dir ::data-dir]
   [::backup-dir ::backup-dir]])

(schema/register! ::restore-request
  [:map
   [::backup-path ::backup-path]
   [::data-dir ::data-dir]])

(schema/register! ::list-backups-request
  [:map
   [::backup-dir ::backup-dir]])

(schema/register! ::prune-request
  [:map
   [::backup-dir ::backup-dir]
   [::keep ::keep]])

;; Response schemas
(schema/register! ::backup-result
  [:map
   [::status ::status]
   [::backup-path {:optional true} ::backup-path]
   [::elapsed-ms {:optional true} ::elapsed-ms]
   [::error-message {:optional true} ::error-message]])

(schema/register! ::restore-result
  [:map
   [::status ::status]
   [::error-message {:optional true} ::error-message]])

(schema/register! ::backup-info
  [:map
   [::backup-path ::backup-path]
   [::created-at ::created-at]
   [::size-bytes ::size-bytes]])

(schema/register! ::list-backups-result
  [:vector ::backup-info])

(schema/register! ::prune-result
  [:map
   [::pruned ::pruned]
   [::kept ::kept]])

;;; ---------------------------------------------------------------------------
;;; Internal Helpers
;;; ---------------------------------------------------------------------------

(def ^:private backup-timestamp-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss"))

(defn- timestamp-str
  "Generate a timestamp string for backup directory naming."
  []
  (.format backup-timestamp-formatter
           (.atZone (Instant/now) (java.time.ZoneId/systemDefault))))

(defn- parse-backup-timestamp
  "Parse a backup directory name back to an Instant."
  [dir-name]
  (try
    (let [local-dt (java.time.LocalDateTime/parse dir-name backup-timestamp-formatter)]
      (.toInstant (.atZone local-dt (java.time.ZoneId/systemDefault))))
    (catch Exception _
      nil)))

(defn- dir-size
  "Calculate total size of directory in bytes."
  [^File dir]
  (if (.isDirectory dir)
    (reduce + 0 (map dir-size (.listFiles dir)))
    (.length dir)))

(defn- copy-dir-recursive!
  "Recursively copy source directory to destination."
  [^File src ^File dest]
  (when (.isDirectory src)
    (.mkdirs dest)
    (doseq [child (.listFiles src)]
      (let [child-dest (io/file dest (.getName child))]
        (if (.isDirectory child)
          (copy-dir-recursive! child child-dest)
          (Files/copy (.toPath child)
                      (.toPath child-dest)
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))))))

(defn- delete-dir-recursive!
  "Recursively delete a directory and all its contents."
  [^File dir]
  (when (.exists dir)
    (when (.isDirectory dir)
      (doseq [child (.listFiles dir)]
        (delete-dir-recursive! child)))
    (.delete dir)))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn backup!
  "Create a backup of the Datalevin data directory.

   Pauses all writers, copies data directory, then resumes writers.
   Uses try/finally to guarantee resume even on copy failure.

   Request keys:
     ::data-dir   - Source data directory (e.g., \"data/datalevin\")
     ::backup-dir - Destination backup directory (e.g., \"data/backups\")

   Returns:
     {::status :ok ::backup-path \"...\" ::elapsed-ms N}
     or {::status :error ::error-message \"...\"}"
  {:malli/schema [:=> [:cat ::backup-request] ::backup-result]}
  [{::keys [data-dir backup-dir]}]
  (let [t0 (System/nanoTime)
        src-dir (io/file data-dir)
        ts (timestamp-str)
        dest-dir (io/file backup-dir ts)]
    (log/info "Starting backup" {:data-dir data-dir
                                  :backup-path (.getAbsolutePath dest-dir)})
    (try
      ;; Pause infrastructure writer to flush pending writes
      (db/pause-writer!)
      (log/debug "Infrastructure writer paused")

      ;; Verify source exists
      (if-not (.exists src-dir)
        {::status :error
         ::error-message (str "Data directory does not exist: " data-dir)}

        ;; Copy the data directory
        (do
          (copy-dir-recursive! src-dir dest-dir)
          (let [elapsed-ms (/ (- (System/nanoTime) t0) 1e6)]
            (log/info "Backup completed" {:backup-path (.getAbsolutePath dest-dir)
                                          :elapsed-ms elapsed-ms})
            {::status :ok
             ::backup-path (.getAbsolutePath dest-dir)
             ::elapsed-ms elapsed-ms})))

      (catch Exception e
        (log/error e "Backup failed")
        {::status :error
         ::error-message (.getMessage e)})

      (finally
        ;; Always resume writer
        (db/resume-writer!)
        (log/debug "Infrastructure writer resumed")))))

(defn restore!
  "Restore a backup to the data directory.

   NOTE: Server must be stopped before restore. This function does not
   enforce this - it's the caller's responsibility.

   Request keys:
     ::backup-path - Source backup directory
     ::data-dir    - Destination data directory

   Returns:
     {::status :ok}
     or {::status :error ::error-message \"...\"}"
  {:malli/schema [:=> [:cat ::restore-request] ::restore-result]}
  [{::keys [backup-path data-dir]}]
  (let [backup-dir (io/file backup-path)
        dest-dir (io/file data-dir)]
    (log/info "Starting restore" {:backup-path backup-path
                                   :data-dir data-dir})
    (try
      ;; Verify backup exists
      (when-not (.exists backup-dir)
        (throw (ex-info "Backup does not exist" {:backup-path backup-path})))

      ;; Delete current data directory contents
      (when (.exists dest-dir)
        (delete-dir-recursive! dest-dir)
        (log/debug "Deleted existing data directory"))

      ;; Copy backup to data directory
      (copy-dir-recursive! backup-dir dest-dir)
      (log/info "Restore completed" {:backup-path backup-path
                                      :data-dir data-dir})
      {::status :ok}

      (catch Exception e
        (log/error e "Restore failed")
        {::status :error
         ::error-message (.getMessage e)}))))

(defn list-backups
  "List all backups in the backup directory, sorted newest first.

   Request keys:
     ::backup-dir - Backup directory to list

   Returns:
     Vector of {::backup-path \"...\" ::created-at instant ::size-bytes N}"
  {:malli/schema [:=> [:cat ::list-backups-request] ::list-backups-result]}
  [{::keys [backup-dir]}]
  (let [dir (io/file backup-dir)]
    (if-not (.exists dir)
      []
      (->> (.listFiles dir)
           (filter #(.isDirectory ^File %))
           (keep (fn [^File d]
                   (when-let [ts (parse-backup-timestamp (.getName d))]
                     {::backup-path (.getAbsolutePath d)
                      ::created-at ts
                      ::size-bytes (dir-size d)})))
           (sort-by ::created-at #(compare %2 %1))  ; newest first
           vec))))

(defn prune!
  "Keep only the N most recent backups, delete the rest.

   Request keys:
     ::backup-dir - Backup directory
     ::keep       - Number of backups to keep

   Returns:
     {::pruned N ::kept N}"
  {:malli/schema [:=> [:cat ::prune-request] ::prune-result]}
  [{::keys [backup-dir keep]}]
  (let [backups (list-backups {::backup-dir backup-dir})
        to-keep (take keep backups)
        to-prune (drop keep backups)]
    (doseq [{::keys [backup-path]} to-prune]
      (log/info "Pruning backup" {:backup-path backup-path})
      (delete-dir-recursive! (io/file backup-path)))
    {::pruned (count to-prune)
     ::kept (count to-keep)}))
