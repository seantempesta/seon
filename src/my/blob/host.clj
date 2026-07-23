(ns my.blob.host
  "Implement the JVM content-addressed blob archive leaf."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [my.blob.core :as core]
   [seon.ai.tokens :as tokens])
  (:import
   (java.nio ByteBuffer)
   (java.nio.channels FileChannel)
   (java.nio.charset StandardCharsets)
   (java.nio.file Files LinkOption Path Paths StandardCopyOption
                  StandardOpenOption)
   (java.nio.file.attribute FileAttribute)))

(def ^:private no-file-attributes (make-array FileAttribute 0))
(def ^:private no-links (make-array LinkOption 0))

(defn- default-storage-view
  []
  {:my.blob/writable-dir
   (str (or (System/getenv "SEON_CLUSTER_DIR")
            "data/clusters/default")
        "/blobs")
   :my.blob/read-only-dirs []})

(defonce ^:private !storage-view (atom (default-storage-view)))

(defn configure-storage-view!
  "Replace the JVM process-local blob storage view."
  [view]
  (let [prior @!storage-view]
    (reset! !storage-view view)
    prior))

(defn- absolute
  [path]
  (-> (Paths/get (str path) (make-array String 0))
      .toAbsolutePath
      .normalize))

(defn- normalize-storage-view
  [view]
  (let [writable (str (absolute (:my.blob/writable-dir view)))
        bases (mapv #(str (absolute %)) (:my.blob/read-only-dirs view))
        directories (into [writable] bases)]
    (if (and (not (str/blank? writable))
             (= (count directories) (count (distinct directories))))
      {:my.blob/ok? true
       :my.blob/storage-view
       {:my.blob/writable-dir writable
        :my.blob/read-only-dirs bases}}
      {:my.blob/ok? false
       :my.blob/error
       "invalid blob storage view — every directory must be distinct"})))

(defn- current-storage-view
  []
  (try
    (normalize-storage-view @!storage-view)
    (catch Throwable throwable
      {:my.blob/ok? false
       :my.blob/error (or (ex-message throwable) (str throwable))})))

(defn- blob-path
  [directory hash]
  (let [[shard filename] (core/blob-path-parts hash)]
    (.resolve (.resolve (absolute directory) shard) filename)))

(defn- force-file!
  [^Path path]
  (with-open [channel
              (FileChannel/open path
                                (into-array StandardOpenOption
                                            [StandardOpenOption/READ]))]
    (.force channel true)))

(defn- force-directory!
  [^Path directory]
  (try
    (force-file! directory)
    (catch java.nio.file.FileSystemException _ nil)))

(defn- ensure-directory!
  [^Path directory]
  (when-not (Files/isDirectory directory no-links)
    (let [parent (.getParent directory)]
      (when parent (ensure-directory! parent))
      (Files/createDirectory directory no-file-attributes)
      (when parent (force-directory! parent))))
  directory)

(defn- read-verified
  [hash ^Path path]
  (try
    (let [content (Files/readString path StandardCharsets/UTF_8)
          actual (core/sha256 content)]
      (if (= hash actual)
        {:my.blob/ok? true
         :my.blob/hash hash
         :my.blob/content content}
        {:my.blob/ok? false
         :my.blob/hash hash
         :my.blob/error
         (str "blob integrity failure under " hash
              " — stored bytes hash to " actual)}))
    (catch Throwable throwable
      {:my.blob/ok? false
       :my.blob/hash hash
       :my.blob/error (or (ex-message throwable) (str throwable))})))

(defn- resolve-blob
  [{:my.blob/keys [writable-dir read-only-dirs]} hash]
  (some
   (fn [directory]
     (let [path (blob-path directory hash)]
       (when (Files/exists path no-links)
         (read-verified hash path))))
   (into [writable-dir] read-only-dirs)))

(defn- publish!
  [directory hash content]
  (let [path (blob-path directory hash)
        shard (.getParent path)]
    (try
      (ensure-directory! (absolute directory))
      (ensure-directory! shard)
      (if (Files/exists path no-links)
        (force-file! path)
        (let [temporary
              (.resolve shard (str hash "." (random-uuid) ".new"))]
          (try
            (with-open [channel
                        (FileChannel/open
                         temporary
                         (into-array StandardOpenOption
                                     [StandardOpenOption/CREATE_NEW
                                      StandardOpenOption/WRITE]))]
              (let [buffer
                    (ByteBuffer/wrap
                     (.getBytes ^String content StandardCharsets/UTF_8))]
                (while (.hasRemaining buffer) (.write channel buffer)))
              (.force channel true))
            (Files/move
             temporary path
             (into-array StandardCopyOption
                         [StandardCopyOption/ATOMIC_MOVE]))
            (force-directory! shard)
            (finally
              (Files/deleteIfExists temporary)))))
      nil
      (catch Throwable throwable
        (or (ex-message throwable) (str throwable))))))

(defn put!
  "Publish content by SHA-256 and transact the identical database identity."
  [{:my.blob/keys [content media]} services]
  (let [hash (core/sha256 content)
        token-count (tokens/estimate content)
        view-result (current-storage-view)]
    (if-not (:my.blob/ok? view-result)
      {:my.blob/ok? false
       :my.blob/hash hash
       :my.blob/error (:my.blob/error view-result)}
      (let [view (:my.blob/storage-view view-result)
            existing (resolve-blob view hash)
            write-error
            (cond
              (and existing (false? (:my.blob/ok? existing)))
              (:my.blob/error existing)

              existing
              (let [writable-path
                    (blob-path (:my.blob/writable-dir view) hash)]
                (when (Files/exists writable-path no-links)
                  (publish! (:my.blob/writable-dir view) hash content)))

              :else
              (publish! (:my.blob/writable-dir view) hash content))]
        (if write-error
          {:my.blob/ok? false
           :my.blob/hash hash
           :my.blob/error write-error}
          (let [report
                ((::transact! services)
                 {:seon.db/tx-data
                  [(cond-> {:my.blob/hash hash
                            :my.blob/tokens token-count
                            :my.blob/at ((::now services))}
                     media (assoc :my.blob/media media))]})]
            (if (:seon.error/message report)
              {:my.blob/ok? false
               :my.blob/hash hash
               :my.blob/error
               (or (:seon.error/message report)
                   "blob file written but the projection tx was rejected")}
              {:my.blob/ok? true
               :my.blob/hash hash
               :my.blob/tokens token-count})))))))

(defn get-blob
  "Read and verify one content-addressed JVM blob."
  [{:my.blob/keys [hash]}]
  (cond
    (not (core/valid-hash? hash)) (core/bad-hash hash)
    :else
    (let [view-result (current-storage-view)]
      (if-not (:my.blob/ok? view-result)
        {:my.blob/ok? false
         :my.blob/hash hash
         :my.blob/error (:my.blob/error view-result)}
        (if-let [result
                 (resolve-blob (:my.blob/storage-view view-result) hash)]
          (if (:my.blob/ok? result)
            (assoc result :my.blob/tokens
                   (tokens/estimate (:my.blob/content result)))
            result)
          (core/not-found hash))))))

(defn services
  "Build one JVM blob leaf over the portable database callbacks."
  [database-services]
  (let [services
        (merge {::now #(java.util.Date.)}
               database-services)]
    {:my.blob/configure-storage-view! configure-storage-view!
     :my.blob/materialize-retained!
     (fn [_]
       {:my.blob/ok? false
        :my.blob/error
        "Retained-blob materialization is not an agent-facing JVM operation."})
     :my.blob/put! (fn [request] (put! request services))
     :my.blob/get get-blob
     :my.blob/current-db! (::current-db! services)
     :my.blob/query! (::query! services)}))
