(ns seon.operator.state
  "Protected, non-database lifecycle records for managed operator roots.

  Claims live in the installation control root, outside every managed
  `data/clusters` tree.  This namespace is deliberately available to both the
  Babashka launcher and the JVM operator; it is the one atomic-record
  mechanism for roots, processes, stores, and clusters."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn])
  (:import [java.io RandomAccessFile]
           [java.nio.channels FileChannel]
           [java.nio.file Files LinkOption OpenOption StandardOpenOption]
           [java.time Instant]
           [java.util Date UUID]))

(defn canonical-path
  [path]
  (.getCanonicalPath (java.io.File. (str path))))

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

(defn current-process-identity
  []
  (let [handle (java.lang.ProcessHandle/current)
        start (.startInstant (.info handle))]
    {:seon.dev.process/pid (.pid handle)
     :seon.dev.process/start-instant
     (when (.isPresent start) (str (.get start)))}))

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

(defn control-root
  "The one installation-owned authority outside `data/clusters`."
  [repository-root]
  (fs/path (canonical-path repository-root) "data" "operator"))

(defn root-claim-id
  [managed-root]
  (UUID/nameUUIDFromBytes
   (.getBytes (canonical-path managed-root)
              java.nio.charset.StandardCharsets/UTF_8)))

(defn root-claim-path
  [repository-root managed-root]
  (fs/path (control-root repository-root) "claims" "roots"
           (str (root-claim-id managed-root) ".edn")))

(defn process-claim-directory
  [repository-root]
  (fs/path (control-root repository-root) "claims" "processes"))

(defn process-claim-path
  [repository-root generation]
  (fs/path (process-claim-directory repository-root) (str generation ".edn")))

(defn- with-control-lock*
  [repository-root transition]
  (let [directory (control-root repository-root)
        path (fs/path directory "lifecycle.lock")]
    (fs/create-dirs directory)
    (with-open [file (RandomAccessFile. (str path) "rw")
                channel (.getChannel file)]
      (.lock channel)
      (transition))))

(defn claim-root-under-lock!
  "Publish a root claim while the caller holds the control lifecycle lock."
  [repository-root managed-root ephemeral? cluster-name]
  (let [root (canonical-path managed-root)
        path (root-claim-path repository-root root)
        previous (or (read-edn path) {})
        new-lifecycle? (contains? previous
                                  :seon.operator.claim/destroyed-at)
        previous (if new-lifecycle?
                   (dissoc previous
                           :seon.operator.claim/created-at
                           :seon.operator.claim/destroyed-at
                           :seon.operator.claim/cleanup
                           :seon.operator.claim/footprint
                           :seon.operator.claim/clusters)
                   previous)
        creator (if new-lifecycle?
                  (current-process-identity)
                  (or (:seon.operator.claim/creator previous)
                      (current-process-identity)))
        store-path (str (fs/path root "data" "clusters" "store"))
        clusters (cond-> (set (:seon.operator.claim/clusters previous))
                   cluster-name (conj cluster-name))
        claim (merge previous
                     {:seon.operator.claim/id (root-claim-id root)
                      :seon.operator.claim/root root
                      :seon.operator.claim/repository-root
                      (canonical-path repository-root)
                      :seon.operator.claim/store
                      {:seon.store/backend :file
                       :seon.store/path store-path
                       :seon.store/id
                       (UUID/nameUUIDFromBytes
                        (.getBytes store-path
                                   java.nio.charset.StandardCharsets/UTF_8))}
                      :seon.operator.claim/ephemeral? (boolean ephemeral?)
                      :seon.operator.claim/reap-on-owner-exit?
                      (boolean ephemeral?)
                      :seon.operator.claim/creator creator
                      :seon.operator.claim/clusters clusters
                      :seon.operator.claim/claimed-at (Date.)})]
    (write-edn! path claim)))

(defn claim-root!
  "Publish root/store/cluster intent before creating anything below the root."
  [repository-root managed-root ephemeral? cluster-name]
  (with-control-lock*
    repository-root
    #(claim-root-under-lock! repository-root managed-root
                             ephemeral? cluster-name)))

(defn mark-root-created-under-lock!
  [repository-root managed-root]
  (let [path (root-claim-path repository-root managed-root)
            claim (or (read-edn path)
                      (throw (ex-info "The managed root has no external claim."
                                      {:seon.operator.claim/root
                                       (canonical-path managed-root)})))]
    (write-edn! path (assoc claim :seon.operator.claim/created-at (Date.)))))

(defn mark-root-created!
  [repository-root managed-root]
  (with-control-lock*
    repository-root
    #(mark-root-created-under-lock! repository-root managed-root)))

(defn write-process-claim!
  [repository-root record]
  (write-edn! (process-claim-path repository-root
                                  (:seon.dev.process/generation record))
              record))

(defn delete-process-claim!
  [repository-root generation]
  (delete-edn! (process-claim-path repository-root generation)))

(defn- read-directory-records
  [directory]
  (if-not (fs/directory? directory)
    {:records [] :errors []}
    (reduce
     (fn [result path]
       (try
         (update result :records conj (read-edn path))
         (catch Throwable error
           (update result :errors conj
                   {:seon.operator.claim/path (str path)
                    :seon.error/message (ex-message error)}))))
     {:records [] :errors []}
     (sort-by str (fs/list-dir directory)))))

(defn process-claims
  [repository-root]
  (read-directory-records (process-claim-directory repository-root)))

(defn root-claims
  [repository-root]
  (read-directory-records
   (fs/path (control-root repository-root) "claims" "roots")))

(defn existence
  "Read claim files only. Never opens Datahike or a managed root database."
  [repository-root]
  (let [{roots :records root-errors :errors} (root-claims repository-root)
        {processes :records process-errors :errors}
        (process-claims repository-root)
        processes-by-root (group-by :seon.dev.process/root processes)]
    {:seon.operator/roots
     (mapv
      (fn [claim]
        (let [owned (get processes-by-root
                         (:seon.operator.claim/root claim) [])
              processes
              (mapv #(assoc % :seon.operator.claim/live?
                            (process-identity-alive? %))
                    owned)]
          (assoc claim
                 :seon.operator.claim/live?
                 (boolean (some :seon.operator.claim/live? processes))
                 :seon.operator.claim/processes processes)))
      (sort-by :seon.operator.claim/root roots))
     :seon.operator/claim-errors (into root-errors process-errors)}))

(defn filesystem-space
  "Observe usable and total filesystem space for the root's volume.
  Two statfs calls, never a directory walk — safe on any path, hot
  paths included. The boot low-space gate reads exactly this."
  [managed-root]
  (let [root-path (.normalize (.toAbsolutePath (fs/path managed-root)))
        no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        filesystem-file
        (.toFile (if (Files/exists root-path no-follow)
                   root-path
                   (.getParent root-path)))
        total (.getTotalSpace filesystem-file)
        usable (.getUsableSpace filesystem-file)]
    {:seon.operator.footprint/root (str root-path)
     :seon.operator.footprint/usable-bytes usable
     :seon.operator.footprint/total-bytes total
     :seon.operator.footprint/usable-ratio
     (if (pos? total) (/ (double usable) (double total)) 0.0)
     :seon.operator.footprint/observed-at (Date.)}))

(defn footprint
  "Observe allocated bytes and usable filesystem space without following links.
  The allocated-bytes half recursively stats every file under the root, so
  its cost is proportional to the tree — call it only on managed data roots
  (status, cleanup accounting, scheduled observation), never on a boot path
  and never on a repository checkout."
  [managed-root]
  (let [root-path (.normalize (.toAbsolutePath (fs/path managed-root)))
        no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        bytes
        (if-not (Files/exists root-path no-follow)
          0
          (letfn [(size-of [path]
                    (cond
                      (Files/isSymbolicLink path) 0
                      (Files/isDirectory path no-follow)
                      (with-open [children (Files/newDirectoryStream path)]
                        (reduce + 0 (map size-of (vec children))))
                      :else (Files/size path)))]
            (size-of root-path)))]
    (assoc (filesystem-space managed-root)
           :seon.operator.footprint/bytes bytes)))

(defn record-footprint-under-lock!
  "Record one observation while the caller holds the lifecycle lock."
  [repository-root managed-root]
  (let [path (root-claim-path repository-root managed-root)
        claim (or (read-edn path)
                  (throw (ex-info "The managed root has no external claim."
                                  {:seon.operator.claim/root
                                   (canonical-path managed-root)})))
        cluster-data (fs/path managed-root "data" "clusters")
        observation (footprint (if (fs/exists? cluster-data)
                                 cluster-data
                                 managed-root))]
    (write-edn! path
                (assoc claim :seon.operator.claim/footprint observation))
    observation))

(defn record-footprint!
  [repository-root managed-root]
  (with-control-lock*
    repository-root
    #(record-footprint-under-lock! repository-root managed-root)))

(defn mark-root-destroyed-under-lock!
  [repository-root managed-root result]
  (let [path (root-claim-path repository-root managed-root)
            claim (or (read-edn path)
                      {:seon.operator.claim/id (root-claim-id managed-root)
                       :seon.operator.claim/root (canonical-path managed-root)})]
    (write-edn! path
                (assoc claim
                       :seon.operator.claim/destroyed-at (Date.)
                       :seon.operator.claim/cleanup result))))

(defn mark-root-destroyed!
  [repository-root managed-root result]
  (with-control-lock*
    repository-root
    #(mark-root-destroyed-under-lock! repository-root managed-root result)))
