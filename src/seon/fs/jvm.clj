(ns seon.fs.jvm
  "Protected JVM implementation of the `my.fs` capability family."
  (:refer-clojure :exclude [read])
  (:require [babashka.fs :as fs]
            [seon.blob :as blob]
            [seon.db :as db])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel SeekableByteChannel]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException Files LinkOption
            NoSuchFileException Path SecureDirectoryStream
            StandardOpenOption]
           [java.nio.file.attribute BasicFileAttributeView
            BasicFileAttributes FileAttribute]
           [java.security MessageDigest]
           [java.util Arrays Date HexFormat UUID]))

(set! *warn-on-reflection* true)

(def ^:private no-follow-links
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(def ^:private no-file-attributes
  (make-array FileAttribute 0))

(def ^:private io-buffer-bytes
  65536)

(def ^:private empty-digest
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(def ^:private write-serialization
  (Object.))

(defn- flat-error
  [kind message data]
  {:seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

(defn- refuse!
  [kind message data]
  (throw
   (ex-info message
            {:seon.error/kind kind
             :seon.error/message message
             :seon.error/data data})))

(defn- error-value
  [error fallback-kind fallback-message data]
  (let [classified (ex-data error)]
    (if (and (keyword? (:seon.error/kind classified))
             (string? (:seon.error/message classified)))
      (flat-error (:seon.error/kind classified)
                  (:seon.error/message classified)
                  (:seon.error/data classified))
      (flat-error fallback-kind fallback-message data))))

(defn- digest-string
  [^MessageDigest digest]
  (.formatHex (HexFormat/of) (.digest digest)))

(defn- absolute-normalized
  ^Path [base path]
  (let [candidate (fs/path path)]
    (fs/normalize
     (if (.isAbsolute candidate)
       candidate
       (fs/path base candidate)))))

(defn- path-plan
  [path effective]
  (let [working-root (fs/normalize
                      (fs/absolutize
                       (:seon.config.fs/working-root effective)))
        roots (->> (:seon.config.fs/roots effective)
                   (map #(absolute-normalized working-root %))
                   distinct
                   (sort-by (juxt (comp - #(.getNameCount ^Path %)) str)))
        target (absolute-normalized working-root path)
        root (first (filter #(.startsWith target ^Path %) roots))]
    (when-not root
      (refuse! :my.fs/path-refused
               "The path is outside every declared filesystem root."
               {:my.fs/path path}))
    {:seon.fs.jvm/root root
     :seon.fs.jvm/target target
     :seon.fs.jvm/relative (.relativize ^Path root target)}))

(defn- observation
  [^BasicFileAttributes attrs]
  {:seon.fs.jvm/file-key (.fileKey attrs)
   :seon.fs.jvm/size (.size attrs)
   :seon.fs.jvm/modified-at (.lastModifiedTime attrs)
   :seon.fs.jvm/created-at (.creationTime attrs)
   :seon.fs.jvm/regular-file? (.isRegularFile attrs)
   :seon.fs.jvm/directory? (.isDirectory attrs)
   :seon.fs.jvm/symbolic-link? (.isSymbolicLink attrs)})

(defn- path-observation
  [^Path path]
  (let [attrs (fs/read-attributes path "basic:*" {:nofollow-links true})]
    {:seon.fs.jvm/file-key (:fileKey attrs)
     :seon.fs.jvm/size (:size attrs)
     :seon.fs.jvm/modified-at (:lastModifiedTime attrs)
     :seon.fs.jvm/created-at (:creationTime attrs)
     :seon.fs.jvm/regular-file? (:isRegularFile attrs)
     :seon.fs.jvm/directory? (:isDirectory attrs)
     :seon.fs.jvm/symbolic-link? (:isSymbolicLink attrs)}))

(defn- handle-observation
  [^SecureDirectoryStream directory]
  (observation
   (.readAttributes
    ^BasicFileAttributeView
    (.getFileAttributeView directory BasicFileAttributeView))))

(defn- relative-observation
  [^SecureDirectoryStream directory ^Path path]
  (try
    (observation
     (.readAttributes
      ^BasicFileAttributeView
      (.getFileAttributeView directory path BasicFileAttributeView
                             no-follow-links)))
    (catch NoSuchFileException _ nil)))

(defn- close-streams!
  [streams]
  (doseq [stream (reverse streams)]
    (.close ^java.io.Closeable stream)))

(defn- open-root
  [^Path root public-path]
  (let [before (path-observation root)]
    (when (or (:seon.fs.jvm/symbolic-link? before)
              (not (:seon.fs.jvm/directory? before)))
      (refuse! :my.fs/path-refused
               "A declared filesystem root is not a no-follow directory."
               {:my.fs/path public-path}))
    (let [opened (Files/newDirectoryStream root)]
      (when-not (instance? SecureDirectoryStream opened)
        (.close opened)
        (refuse! :my.fs/path-refused
                 "This filesystem cannot provide race-free directory access."
                 {:my.fs/path public-path}))
      (let [secure ^SecureDirectoryStream opened
            after (handle-observation secure)]
        (when (or (nil? (:seon.fs.jvm/file-key before))
                  (not= (:seon.fs.jvm/file-key before)
                        (:seon.fs.jvm/file-key after)))
          (.close opened)
          (refuse! :my.fs/path-refused
                   "The declared filesystem root changed while it was opened."
                   {:my.fs/path public-path}))
        {:seon.fs.jvm/directory secure
         :seon.fs.jvm/streams [secure]}))))

(defn- relative-segments
  [^Path relative]
  (into []
        (remove #(empty? (str %)))
        (iterator-seq (.iterator relative))))

(defn- descend
  [opened segments public-path]
  (reduce
   (fn [{directory :seon.fs.jvm/directory :as state} segment]
     (let [attrs (relative-observation directory segment)]
       (when-not attrs
         (refuse! :my.fs/not-found
                  "A filesystem path segment does not exist."
                  {:my.fs/path public-path}))
       (when (or (:seon.fs.jvm/symbolic-link? attrs)
                 (not (:seon.fs.jvm/directory? attrs)))
         (refuse! :my.fs/path-refused
                  "A filesystem path crosses a non-directory or symbolic link."
                  {:my.fs/path public-path}))
       (let [child (.newDirectoryStream
                    ^SecureDirectoryStream directory segment no-follow-links)]
         (when-not (instance? SecureDirectoryStream child)
           (.close child)
           (refuse! :my.fs/path-refused
                    "This filesystem cannot securely traverse the path."
                    {:my.fs/path public-path}))
         (-> state
             (assoc :seon.fs.jvm/directory child)
             (update :seon.fs.jvm/streams conj child)))))
   opened
   segments))

(defn- parent-access
  [path effective]
  (let [{root :seon.fs.jvm/root relative :seon.fs.jvm/relative}
        (path-plan path effective)
        segments (relative-segments relative)]
    (when (empty? segments)
      (refuse! :my.fs/path-refused
               "This operation requires a path beneath a declared root."
               {:my.fs/path path}))
    (assoc (descend (open-root root path) (butlast segments) path)
           :seon.fs.jvm/name (last segments))))

(defn- directory-access
  [path effective]
  (let [{root :seon.fs.jvm/root relative :seon.fs.jvm/relative}
        (path-plan path effective)
        segments (relative-segments relative)
        opened (open-root root path)]
    (if (empty? segments)
      opened
      (let [parent-state (descend opened (butlast segments) path)
            parent (:seon.fs.jvm/directory parent-state)
            entry-name (last segments)
            attrs (relative-observation parent entry-name)]
        (when-not attrs
          (close-streams! (:seon.fs.jvm/streams parent-state))
          (refuse! :my.fs/not-found
                   "The filesystem path does not exist."
                   {:my.fs/path path}))
        (when (or (:seon.fs.jvm/symbolic-link? attrs)
                  (not (:seon.fs.jvm/directory? attrs)))
          (close-streams! (:seon.fs.jvm/streams parent-state))
          (refuse! :my.fs/not-directory
                   "The glob root is not a no-follow directory."
                   {:my.fs/path path}))
        (let [child (.newDirectoryStream
                     ^SecureDirectoryStream parent entry-name no-follow-links)]
          (when-not (instance? SecureDirectoryStream child)
            (.close child)
            (close-streams! (:seon.fs.jvm/streams parent-state))
            (refuse! :my.fs/path-refused
                     "This filesystem cannot securely traverse the glob root."
                     {:my.fs/path path}))
          (-> parent-state
              (assoc :seon.fs.jvm/directory child)
              (update :seon.fs.jvm/streams conj child)))))))

(defn- read-pass
  [^SeekableByteChannel channel byte-offset window-limit read-limit]
  (let [buffer (ByteBuffer/allocate
                (int (min io-buffer-bytes (max 1 read-limit))))
        octets (byte-array (int window-limit))
        window-end (+ byte-offset window-limit)
        digest (MessageDigest/getInstance "SHA-256")]
    (loop [total 0]
      (.clear buffer)
      (let [read-count (.read channel buffer)]
        (cond
          (neg? read-count)
          (let [bytes-read (long (max 0 (min window-limit
                                                (- total byte-offset))))]
            {:seon.fs.jvm/digest (digest-string digest)
             :seon.fs.jvm/file-bytes (long total)
             :seon.fs.jvm/window (Arrays/copyOf octets (int bytes-read))})

          (zero? read-count)
          (refuse! :my.fs/read-failed
                   "The file read made no progress."
                   {})

          (> (+ total read-count) read-limit)
          (refuse! :my.fs/read-limit
                   "The file exceeds the configured read ceiling."
                   {:seon.config.fs/max-read-bytes read-limit})

          :else
          (let [buffer-octets (.array buffer)
                chunk-start total
                chunk-end (+ total read-count)
                copy-start (max byte-offset chunk-start)
                copy-end (min window-end chunk-end)]
            (.update digest buffer-octets 0 read-count)
            (when (< copy-start copy-end)
              (System/arraycopy buffer-octets
                                (int (- copy-start chunk-start))
                                octets
                                (int (- copy-start byte-offset))
                                (int (- copy-end copy-start))))
            (recur chunk-end)))))))

(defn- open-read-channel
  [^SecureDirectoryStream directory ^Path entry-name]
  (.newByteChannel directory entry-name
                   #{StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS}
                   no-file-attributes))

(defn- same-observation?
  [before after pass]
  (and after
       (= (:seon.fs.jvm/file-key before) (:seon.fs.jvm/file-key after))
       (= (:seon.fs.jvm/size before) (:seon.fs.jvm/size after))
       (= (:seon.fs.jvm/modified-at before) (:seon.fs.jvm/modified-at after))
       (= (:seon.fs.jvm/created-at before) (:seon.fs.jvm/created-at after))
       (= (:seon.fs.jvm/size before) (:seon.fs.jvm/file-bytes pass))))

(defn- current-file
  [^SecureDirectoryStream directory ^Path entry-name path read-limit]
  (let [before (relative-observation directory entry-name)]
    (when-not before
      (refuse! :my.fs/not-found "The file does not exist."
               {:my.fs/path path}))
    (when (:seon.fs.jvm/symbolic-link? before)
      (refuse! :my.fs/path-refused
               "The final filesystem path is a symbolic link."
               {:my.fs/path path}))
    (when-not (:seon.fs.jvm/regular-file? before)
      (refuse! :my.fs/not-regular-file
               "The filesystem path is not a regular file."
               {:my.fs/path path}))
    (with-open [^SeekableByteChannel channel
                (open-read-channel directory entry-name)]
      (let [pass (read-pass channel 0 0 read-limit)
            after (relative-observation directory entry-name)]
        (when-not (same-observation? before after pass)
          (refuse! :my.fs/changed-during-read
                   "The file changed while its digest was read."
                   {:my.fs/path path}))
        pass))))

(defn- strict-utf8
  [octets]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (str (.decode decoder (ByteBuffer/wrap ^bytes octets)))))

(defn- octet-values
  [octets]
  (mapv #(bit-and 0xff %) ^bytes octets))

(defn- read-opened
  [request effective directory entry-name window-limit]
  (let [path (:my.fs/path request)
        before (relative-observation directory entry-name)
        read-limit (:seon.config.fs/max-read-bytes effective)
        byte-offset (long (or (:my.fs/byte-offset request) 0))]
    (when-not before
      (refuse! :my.fs/not-found "The file does not exist."
               {:my.fs/path path}))
    (when (:seon.fs.jvm/symbolic-link? before)
      (refuse! :my.fs/path-refused
               "The final filesystem path is a symbolic link."
               {:my.fs/path path}))
    (when-not (:seon.fs.jvm/regular-file? before)
      (refuse! :my.fs/not-regular-file
               "The filesystem path is not a regular file."
               {:my.fs/path path}))
    (with-open [^SeekableByteChannel channel
                (open-read-channel directory entry-name)]
      (let [pass (read-pass channel byte-offset window-limit read-limit)
            after (relative-observation directory entry-name)]
        (when-not (same-observation? before after pass)
          (refuse! :my.fs/changed-during-read
                   "The file changed while it was read."
                   {:my.fs/path path}))
        (let [window (:seon.fs.jvm/window pass)
              encoding (or (:my.fs/encoding request) :utf-8)
              base {:my.fs/path path
                    :my.fs/digest (:seon.fs.jvm/digest pass)
                    :my.fs/file-bytes (:seon.fs.jvm/file-bytes pass)
                    :my.fs/byte-offset byte-offset
                    :my.fs/bytes-read (long (alength ^bytes window))
                    :my.fs/eof?
                    (>= (+ byte-offset (alength ^bytes window))
                        (:seon.fs.jvm/file-bytes pass))}]
          (if (= :bytes encoding)
            (assoc base :my.fs/bytes (octet-values window))
            (try
              (assoc base :my.fs/text (strict-utf8 window))
              (catch java.nio.charset.CharacterCodingException _
                (refuse! :my.fs/invalid-utf8-window
                         (str "The requested byte window is not complete "
                              "UTF-8; read it as :bytes instead.")
                         {:my.fs/path path
                          :my.fs/byte-offset byte-offset
                          :my.fs/bytes-read
                          (long (alength ^bytes window))})))))))))

(defn- read-window
  [request effective window-limit]
  (let [path (:my.fs/path request)]
    (try
      (let [{directory :seon.fs.jvm/directory
             entry-name :seon.fs.jvm/name
             streams :seon.fs.jvm/streams}
            (parent-access path effective)]
        (try
          (read-opened request effective directory entry-name window-limit)
          (finally
            (close-streams! streams))))
      (catch Throwable error
        (error-value error :my.fs/read-failed "The file read failed."
                     {:my.fs/path path})))))

(defn- read
  {:malli/schema
   [:=> [:cat :my.fs/read-request :seon.config/effective]
    [:or :my.fs/read-result :seon.error/value]]}
  [request effective]
  (read-window
   request effective
   (long (min (:seon.config.fs/max-inline-bytes effective)
              (or (:my.fs/max-bytes request) Long/MAX_VALUE)))))

(defn- read-complete
  "Read one complete UTF-8 file for a higher-level conditional operation."
  {:malli/schema
   [:=> [:cat :my.fs/read-request :seon.config/effective]
    [:or :my.fs/read-result :seon.error/value]]}
  [request effective]
  (read-window request effective
               (long (:seon.config.fs/max-read-bytes effective))))

(defn- array-content
  [content write-limit]
  (let [octets
        (cond
          (contains? content :my.fs/text)
          (.getBytes ^String (:my.fs/text content) StandardCharsets/UTF_8)

          (contains? content :my.fs/bytes)
          (let [values (:my.fs/bytes content)]
            (when (> (count values) write-limit)
              (refuse! :my.fs/write-limit
                       "The write content exceeds the configured ceiling."
                       {:seon.config.fs/max-write-bytes write-limit}))
            (byte-array (map unchecked-byte values)))

          :else nil)]
    (when (and octets (> (alength ^bytes octets) write-limit))
      (refuse! :my.fs/write-limit
               "The write content exceeds the configured ceiling."
               {:seon.config.fs/max-write-bytes write-limit}))
    octets))

(defn- write-buffer!
  [^SeekableByteChannel channel octets]
  (let [buffer (ByteBuffer/wrap ^bytes octets)]
    (loop []
      (when (.hasRemaining buffer)
        (when (zero? (.write channel buffer))
          (refuse! :my.fs/write-failed
                   "The staged file write made no progress."
                   {}))
        (recur)))))

(defn- blob-pass
  [digest write-limit channel]
  (let [digester (MessageDigest/getInstance "SHA-256")]
    (loop [offset 0
           observed? false]
      (let [octets (blob/read-chunk db/*conn* digest offset io-buffer-bytes)]
        (cond
          (nil? octets)
          (if (or observed? (= digest empty-digest))
            {:seon.fs.jvm/digest (digest-string digester)
             :seon.fs.jvm/file-bytes (long offset)}
            (refuse! :my.fs/blob-unavailable
                     "The content-addressed blob is unavailable."
                     {:seon.blob/digest digest}))

          (> (+ offset (alength ^bytes octets)) write-limit)
          (refuse! :my.fs/write-limit
                   "The blob exceeds the configured write ceiling."
                   {:seon.blob/digest digest
                    :seon.config.fs/max-write-bytes write-limit})

          :else
          (let [length (alength ^bytes octets)
                next-offset (+ offset length)]
            (.update digester ^bytes octets)
            (when channel (write-buffer! channel octets))
            (if (< length io-buffer-bytes)
              {:seon.fs.jvm/digest (digest-string digester)
               :seon.fs.jvm/file-bytes (long next-offset)}
              (recur next-offset true))))))))

(defn- content-info
  [content write-limit]
  (if-let [octets (array-content content write-limit)]
    (let [digest (MessageDigest/getInstance "SHA-256")]
      (.update digest ^bytes octets)
      {:seon.fs.jvm/digest (digest-string digest)
       :seon.fs.jvm/file-bytes (long (alength ^bytes octets))
       :seon.fs.jvm/octet-array octets})
    (let [expected (:seon.blob/digest content)
          actual (blob-pass expected write-limit nil)]
      (when-not (= expected (:seon.fs.jvm/digest actual))
        (refuse! :my.fs/blob-unavailable
                 "The blob bytes do not match their content digest."
                 {:seon.blob/digest expected
                  :my.fs/digest (:seon.fs.jvm/digest actual)}))
      (assoc actual :seon.fs.jvm/blob expected))))

(defn- precondition-state
  [^SecureDirectoryStream directory ^Path entry-name path precondition read-limit]
  (let [attrs (relative-observation directory entry-name)]
    (if (:my.fs/expected-absence? precondition)
      (do
        (when attrs
          (if (:seon.fs.jvm/symbolic-link? attrs)
            (refuse! :my.fs/path-refused
                     "The final filesystem path is a symbolic link."
                     {:my.fs/path path})
            (refuse! :my.fs/already-exists
                     "The file already exists."
                     {:my.fs/path path})))
        {:my.fs/created? true})
      (let [expected (:my.fs/expected-digest precondition)
            current (current-file directory entry-name path read-limit)
            actual (:seon.fs.jvm/digest current)]
        (when-not (= expected actual)
          (refuse! :my.fs/stale-digest
                   "The file no longer has the expected digest."
                   {:my.fs/path path
                    :my.fs/expected-digest expected
                    :my.fs/digest actual}))
        {:my.fs/created? false
         :my.fs/before-digest actual}))))

(defn- stage-content!
  [^SecureDirectoryStream directory content write-limit]
  (let [stage-name (fs/path (str ".seon-write-" (UUID/randomUUID) ".stage"))
        info (content-info content write-limit)]
    (with-open [channel
                (.newByteChannel
                 directory stage-name
                 #{StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE
                   LinkOption/NOFOLLOW_LINKS}
                 no-file-attributes)]
      (if-let [octets (:seon.fs.jvm/octet-array info)]
        (write-buffer! channel octets)
        (let [written (blob-pass (:seon.fs.jvm/blob info) write-limit channel)]
          (when-not (= (select-keys info [:seon.fs.jvm/digest
                                         :seon.fs.jvm/file-bytes])
                       written)
            (refuse! :my.fs/blob-unavailable
                     "The blob changed while it was staged."
                     {:seon.blob/digest (:seon.fs.jvm/blob info)}))))
      (when-not (instance? FileChannel channel)
        (refuse! :my.fs/atomic-write-unsupported
                 "This filesystem cannot force the staged file."
                 {}))
      (.force ^FileChannel channel true))
    {:seon.fs.jvm/stage-name stage-name
     :seon.fs.jvm/content info}))

(defn- write
  {:malli/schema
   [:=> [:cat :my.fs/write-request :seon.config/effective]
    [:or :my.fs/write-result :seon.error/value]]}
  [request effective]
  (let [path (:my.fs/path request)]
    (try
      (locking write-serialization
        (let [{directory :seon.fs.jvm/directory
               entry-name :seon.fs.jvm/name
               streams :seon.fs.jvm/streams}
              (parent-access path effective)]
          (try
            (let [write-limit (:seon.config.fs/max-write-bytes effective)
                  read-limit (:seon.config.fs/max-read-bytes effective)
                  precondition (:my.fs/precondition request)
                  before (precondition-state directory entry-name path
                                             precondition read-limit)
                  content (content-info (:my.fs/content request) write-limit)
                  proposed (:seon.fs.jvm/digest content)]
              (if (= proposed (:my.fs/before-digest before))
                (merge {:my.fs/path path
                        :my.fs/changed? false
                        :my.fs/after-digest proposed
                        :my.fs/bytes-written
                        (:seon.fs.jvm/file-bytes content)}
                       before)
                (let [{stage-name :seon.fs.jvm/stage-name
                       staged-content :seon.fs.jvm/content}
                      (stage-content! directory (:my.fs/content request)
                                      write-limit)
                      moved? (volatile! false)]
                  (try
                    (precondition-state directory entry-name path
                                        precondition read-limit)
                    (.move ^SecureDirectoryStream directory stage-name
                           ^SecureDirectoryStream directory entry-name)
                    (vreset! moved? true)
                    (merge {:my.fs/path path
                            :my.fs/changed? true
                            :my.fs/after-digest
                            (:seon.fs.jvm/digest staged-content)
                            :my.fs/bytes-written
                            (:seon.fs.jvm/file-bytes staged-content)}
                           before)
                    (catch AtomicMoveNotSupportedException _
                      (refuse! :my.fs/atomic-write-unsupported
                               "The filesystem refused an atomic file move."
                               {:my.fs/path path}))
                    (finally
                      (when-not @moved?
                        (try
                          (.deleteFile ^SecureDirectoryStream directory
                                       stage-name)
                          (catch NoSuchFileException _))))))))
            (finally
              (close-streams! streams)))))
      (catch Throwable error
        (error-value error :my.fs/write-failed "The file write failed."
                     {:my.fs/path path})))))

(defn- directory-entries
  [^SecureDirectoryStream directory remaining]
  (let [iterator (.iterator directory)]
    (loop [entries []
           remaining remaining]
      (if (or (zero? remaining) (not (.hasNext iterator)))
        {:seon.fs.jvm/entries entries
         :seon.fs.jvm/exhausted? (not (.hasNext iterator))}
        (let [entry ^Path (.next iterator)
              entry-name (.getFileName entry)
              attrs (relative-observation directory entry-name)]
          (when-not attrs
            (refuse! :my.fs/glob-failed
                     "A path changed while the directory was examined."
                     {:my.fs/path (str entry)}))
          (recur (conj entries {:seon.fs.jvm/name entry-name
                                :seon.fs.jvm/attrs attrs})
                 (dec remaining)))))))

(defn- glob-walk!
  [^SecureDirectoryStream directory relative depth state matcher limits]
  (when (:my.fs/complete? @state)
    (let [remaining (- (:seon.fs.jvm/max-entries limits)
                       (:my.fs/examined @state))
          {:seon.fs.jvm/keys [entries exhausted?]}
          (directory-entries directory remaining)
          ordered (sort-by (comp str :seon.fs.jvm/name) entries)]
      (swap! state update :my.fs/examined + (count entries))
      (when-not exhausted?
        (swap! state assoc :my.fs/complete? false))
      (doseq [{entry-name :seon.fs.jvm/name attrs :seon.fs.jvm/attrs} ordered
              :while (:my.fs/complete? @state)]
        (let [child-relative (if (empty? (str relative))
                               entry-name
                               (.resolve ^Path relative ^Path entry-name))]
          (when (.matches ^java.nio.file.PathMatcher matcher child-relative)
            (if (< (count (:my.fs/paths @state))
                   (:seon.fs.jvm/max-results limits))
              (swap! state update :my.fs/paths conj (str child-relative))
              (swap! state assoc :my.fs/complete? false)))
          (when (and (:my.fs/complete? @state)
                     (:seon.fs.jvm/directory? attrs))
            (cond
              (< depth (:seon.fs.jvm/effective-depth limits))
              (with-open [child (.newDirectoryStream
                                 directory entry-name no-follow-links)]
                (when-not (instance? SecureDirectoryStream child)
                  (refuse! :my.fs/glob-failed
                           "This filesystem cannot securely traverse the tree."
                           {:my.fs/path (str child-relative)}))
                (glob-walk! child child-relative (inc depth)
                            state matcher limits))

              (:seon.fs.jvm/depth-policy-limited? limits)
              (swap! state assoc :my.fs/complete? false))))))))

(defn- glob
  {:malli/schema
   [:=> [:cat :my.fs/glob-request :seon.config/effective]
    [:or :my.fs/glob-result :seon.error/value]]}
  [request effective]
  (let [root (:my.fs/root request)]
    (try
      (let [matcher (.getPathMatcher
                     (java.nio.file.FileSystems/getDefault)
                     (str "glob:" (:my.fs/pattern request)))
            requested-depth (:my.fs/max-depth request)
            policy-depth (:seon.config.fs/max-depth effective)
            effective-depth (min (or requested-depth policy-depth)
                                 policy-depth)
            limits {:seon.fs.jvm/max-results
                    (min (or (:my.fs/max-results request)
                             (:seon.config.fs/max-glob-results effective))
                         (:seon.config.fs/max-glob-results effective))
                    :seon.fs.jvm/max-entries
                    (:seon.config.fs/max-traversal-entries effective)
                    :seon.fs.jvm/effective-depth effective-depth
                    :seon.fs.jvm/depth-policy-limited?
                    (or (nil? requested-depth)
                        (> requested-depth policy-depth))}
            {directory :seon.fs.jvm/directory
             streams :seon.fs.jvm/streams}
            (directory-access root effective)
            state (atom {:my.fs/paths []
                         :my.fs/examined 0
                         :my.fs/complete? true})]
        (try
          (glob-walk! directory (fs/path "") 0 state matcher limits)
          (let [result @state
                paths (vec (sort (:my.fs/paths result)))]
            {:my.fs/root root
             :my.fs/paths paths
             :my.fs/returned (long (count paths))
             :my.fs/examined (long (:my.fs/examined result))
             :my.fs/complete? (:my.fs/complete? result)})
          (finally
            (close-streams! streams))))
      (catch java.util.regex.PatternSyntaxException error
        (error-value error :my.fs/invalid-glob
                     "The JDK glob pattern is invalid."
                     {:my.fs/root root
                      :my.fs/pattern (:my.fs/pattern request)}))
      (catch Throwable error
        (error-value error :my.fs/glob-failed "The filesystem glob failed."
                     {:my.fs/root root})))))

(defn- stat
  {:malli/schema
   [:=> [:cat :my.fs/stat-request :seon.config/effective]
    [:or :my.fs/stat-result :seon.error/value]]}
  [request effective]
  (let [path (:my.fs/path request)]
    (try
      (let [{root :seon.fs.jvm/root relative :seon.fs.jvm/relative}
            (path-plan path effective)
            segments (relative-segments relative)
            opened (if (empty? segments)
                     (open-root root path)
                     (descend (open-root root path)
                              (butlast segments) path))
            directory (:seon.fs.jvm/directory opened)
            attrs (if (empty? segments)
                    (handle-observation directory)
                    (relative-observation directory (last segments)))]
        (try
          (when-not attrs
            (refuse! :my.fs/not-found "The filesystem path does not exist."
                     {:my.fs/path path}))
          (cond-> {:my.fs/path path
                   :my.fs/regular-file?
                   (:seon.fs.jvm/regular-file? attrs)
                   :my.fs/directory? (:seon.fs.jvm/directory? attrs)
                   :my.fs/symbolic-link?
                   (:seon.fs.jvm/symbolic-link? attrs)
                   :my.fs/modified-at
                   (Date. (.toMillis
                           ^java.nio.file.attribute.FileTime
                           (:seon.fs.jvm/modified-at attrs)))}
            (:seon.fs.jvm/regular-file? attrs)
            (assoc :my.fs/byte-size (long (:seon.fs.jvm/size attrs))))
          (finally
            (close-streams! (:seon.fs.jvm/streams opened)))))
      (catch Throwable error
        (error-value error :my.fs/read-failed
                     "The filesystem attributes could not be read."
                     {:my.fs/path path})))))
