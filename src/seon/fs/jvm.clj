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
  [marker subject message data]
  {marker subject
   :seon.error/kind marker
   :seon.error/message message
   :seon.error/data data})

(defn- refuse!
  [marker subject message data]
  (throw
   (ex-info message
            (flat-error marker subject message data))))

(defn- error-value
  [error fallback-marker fallback-subject fallback-message data]
  (let [classified (ex-data error)]
    (if (and (keyword? (:seon.error/kind classified))
             (string? (:seon.error/message classified)))
      classified
      (flat-error fallback-marker fallback-subject fallback-message data))))

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
               path
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
               public-path
               "A declared filesystem root is not a no-follow directory."
               {:my.fs/path public-path}))
    (let [opened (Files/newDirectoryStream root)]
      (when-not (instance? SecureDirectoryStream opened)
        (.close opened)
        (refuse! :my.fs/path-refused
                 public-path
                 "This filesystem cannot provide race-free directory access."
                 {:my.fs/path public-path}))
      (let [secure ^SecureDirectoryStream opened
            after (handle-observation secure)]
        (when (or (nil? (:seon.fs.jvm/file-key before))
                  (not= (:seon.fs.jvm/file-key before)
                        (:seon.fs.jvm/file-key after)))
          (.close opened)
          (refuse! :my.fs/path-refused
                   public-path
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
                  public-path
                  "A filesystem path segment does not exist."
                  {:my.fs/path public-path}))
       (when (or (:seon.fs.jvm/symbolic-link? attrs)
                 (not (:seon.fs.jvm/directory? attrs)))
         (refuse! :my.fs/path-refused
                  public-path
                  "A filesystem path crosses a non-directory or symbolic link."
                  {:my.fs/path public-path}))
       (let [child (.newDirectoryStream
                    ^SecureDirectoryStream directory segment no-follow-links)]
         (when-not (instance? SecureDirectoryStream child)
           (.close child)
           (refuse! :my.fs/path-refused
                    public-path
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
               path
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
                   path
                   "The filesystem path does not exist."
                   {:my.fs/path path}))
        (when (or (:seon.fs.jvm/symbolic-link? attrs)
                  (not (:seon.fs.jvm/directory? attrs)))
          (close-streams! (:seon.fs.jvm/streams parent-state))
          (refuse! :my.fs/not-directory
                   path
                   "The glob root is not a no-follow directory."
                   {:my.fs/path path}))
        (let [child (.newDirectoryStream
                     ^SecureDirectoryStream parent entry-name no-follow-links)]
          (when-not (instance? SecureDirectoryStream child)
            (.close child)
            (close-streams! (:seon.fs.jvm/streams parent-state))
            (refuse! :my.fs/path-refused
                     path
                     "This filesystem cannot securely traverse the glob root."
                     {:my.fs/path path}))
          (-> parent-state
              (assoc :seon.fs.jvm/directory child)
              (update :seon.fs.jvm/streams conj child)))))))

(defn- read-limit-refusal!
  [path observed-bytes read-limit]
  (refuse! :my.fs/read-limit
           path
           (str "The whole file exceeds the configured read ceiling; read a "
                "bounded window with :my.fs/max-bytes instead.")
           {:my.fs/path path
            :my.fs/file-bytes observed-bytes
            :seon.config.fs/max-read-bytes read-limit}))

(defn- window-pass
  "Read exactly the requested window and digest exactly what is returned.

  The bound and the read are the SAME quantity here, which is the whole point:
  this pass takes no whole-file ceiling because it cannot read more than it
  returns. Seeking to `byte-offset` and stopping after `window-limit` bytes
  makes a windowed `my.fs/read` cost the window rather than the file, so a
  window of an arbitrarily large file is an ordinary read.

  The class this kills is a bound measured against something other than the
  thing it bounds. The pass this replaced streamed the WHOLE file so it could
  promise a whole-file digest, and refused as soon as that running total
  crossed the ceiling — so `:my.fs/byte-offset` and `:my.fs/max-bytes` only
  selected bytes out of a stream they never bounded, and the affordance that
  exists FOR large files was the one large files could not use. There is no
  ceiling parameter here to compare against the wrong quantity."
  [^SeekableByteChannel channel byte-offset window-limit]
  (let [octets (byte-array (int window-limit))
        ^ByteBuffer buffer (ByteBuffer/wrap octets)
        digest (MessageDigest/getInstance "SHA-256")]
    (when (pos? byte-offset)
      (.position channel (long byte-offset)))
    (loop []
      (when (.hasRemaining buffer)
        (let [read-count (.read channel buffer)]
          (cond
            (neg? read-count) nil

            (zero? read-count)
            (refuse! :my.fs/read-failed
                     true
                     "The file read made no progress."
                     {})

            :else (recur)))))
    (let [bytes-read (.position buffer)]
      (.update digest octets 0 bytes-read)
      {:seon.fs.jvm/digest (digest-string digest)
       :seon.fs.jvm/window (Arrays/copyOf octets (int bytes-read))})))

(defn- whole-file-pass
  "Stream one COMPLETE file for its whole-file digest, bounded by the ceiling.

  The two operations whose result genuinely IS the whole file — a complete
  read and a write precondition's digest — come through here, and only here is
  the ceiling honest, because the whole file is what is being read. A windowed
  read never reaches this pass. The refusal names the path and the observed
  size next to the ceiling, and names the key that reads a window instead, so
  it says what to do rather than only which wall it hit."
  [^SeekableByteChannel channel path observed-bytes read-limit]
  (when (> observed-bytes read-limit)
    (read-limit-refusal! path observed-bytes read-limit))
  (let [buffer (ByteBuffer/allocate
                (int (min io-buffer-bytes (max 1 read-limit))))
        octets (byte-array (int (min observed-bytes read-limit)))
        digest (MessageDigest/getInstance "SHA-256")]
    (loop [total 0]
      (.clear buffer)
      (let [read-count (.read channel buffer)]
        (cond
          (neg? read-count)
          {:seon.fs.jvm/digest (digest-string digest)
           :seon.fs.jvm/file-bytes (long total)
           :seon.fs.jvm/window (Arrays/copyOf octets (int total))}

          (zero? read-count)
          (refuse! :my.fs/read-failed
                   true
                   "The file read made no progress."
                   {})

          (> (+ total read-count) read-limit)
          (read-limit-refusal! path (+ total read-count) read-limit)

          :else
          (let [buffer-octets (.array buffer)]
            (.update digest buffer-octets 0 read-count)
            (System/arraycopy buffer-octets 0 octets (int total) read-count)
            (recur (+ total read-count))))))))

(defn- open-read-channel
  [^SecureDirectoryStream directory ^Path entry-name]
  (.newByteChannel directory entry-name
                   #{StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS}
                   no-file-attributes))

(defn- same-observation?
  "Whether the file is the same file, unchanged, across the read."
  [before after]
  (and after
       (= (:seon.fs.jvm/file-key before) (:seon.fs.jvm/file-key after))
       (= (:seon.fs.jvm/size before) (:seon.fs.jvm/size after))
       (= (:seon.fs.jvm/modified-at before) (:seon.fs.jvm/modified-at after))
       (= (:seon.fs.jvm/created-at before) (:seon.fs.jvm/created-at after))))

(defn- readable-file
  "The observation of a regular, non-symlink file, or the refusal naming why."
  [^SecureDirectoryStream directory ^Path entry-name path]
  (let [before (relative-observation directory entry-name)]
    (when-not before
      (refuse! :my.fs/not-found path
               "The file does not exist."
               {:my.fs/path path}))
    (when (:seon.fs.jvm/symbolic-link? before)
      (refuse! :my.fs/path-refused
               path
               "The final filesystem path is a symbolic link."
               {:my.fs/path path}))
    (when-not (:seon.fs.jvm/regular-file? before)
      (refuse! :my.fs/not-regular-file
               path
               "The filesystem path is not a regular file."
               {:my.fs/path path}))
    before))

(defn- current-file
  [^SecureDirectoryStream directory ^Path entry-name path read-limit]
  (let [before (readable-file directory entry-name path)]
    (with-open [^SeekableByteChannel channel
                (open-read-channel directory entry-name)]
      (let [pass (whole-file-pass channel path (:seon.fs.jvm/size before)
                                  read-limit)
            after (relative-observation directory entry-name)]
        (when-not (and (same-observation? before after)
                       (= (:seon.fs.jvm/size before)
                          (:seon.fs.jvm/file-bytes pass)))
          (refuse! :my.fs/changed-during-read
                   path
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
  "One read result, from the pass its completeness demands.

  `complete?` is what separates the two passes, and it is the request's own
  semantics rather than a size test: a complete read promises the whole file,
  so it pays the whole file and may refuse at the ceiling; a windowed read
  promises the window, so it reads the window and can never refuse for the
  size of the surrounding file.

  `:my.fs/digest` keeps its one meaning — the digest of the WHOLE file — and
  is therefore present only when the returned bytes are the whole file.
  `:my.fs/window-digest` is always the digest of what was returned. Two names
  for two facts, so neither can quietly become the other."
  [request effective directory entry-name window-limit complete?]
  (let [path (:my.fs/path request)
        read-limit (:seon.config.fs/max-read-bytes effective)
        byte-offset (long (or (:my.fs/byte-offset request) 0))
        before (readable-file directory entry-name path)]
    (with-open [^SeekableByteChannel channel
                (open-read-channel directory entry-name)]
      (let [pass (if complete?
                   (whole-file-pass channel path (:seon.fs.jvm/size before)
                                    read-limit)
                   (window-pass channel byte-offset window-limit))
            after (relative-observation directory entry-name)]
        (when-not (same-observation? before after)
          (refuse! :my.fs/changed-during-read
                   path
                   "The file changed while it was read."
                   {:my.fs/path path}))
        (let [window (:seon.fs.jvm/window pass)
              bytes-read (long (alength ^bytes window))
              file-bytes (if complete?
                           (:seon.fs.jvm/file-bytes pass)
                           (long (:seon.fs.jvm/size before)))
              whole-file? (and (zero? byte-offset) (= bytes-read file-bytes))
              encoding (or (:my.fs/encoding request) :utf-8)
              base (cond-> {:my.fs/path path
                            :my.fs/window-digest (:seon.fs.jvm/digest pass)
                            :my.fs/file-bytes file-bytes
                            :my.fs/byte-offset byte-offset
                            :my.fs/bytes-read bytes-read
                            :my.fs/eof? (>= (+ byte-offset bytes-read)
                                            file-bytes)}
                     whole-file?
                     (assoc :my.fs/digest (:seon.fs.jvm/digest pass)))]
          (if (= :bytes encoding)
            (assoc base :my.fs/bytes (octet-values window))
            (try
              (assoc base :my.fs/text (strict-utf8 window))
              (catch java.nio.charset.CharacterCodingException _
                (refuse! :my.fs/invalid-utf8-window
                         path
                         (str "The requested byte window is not complete "
                              "UTF-8; read it as :bytes instead.")
                         {:my.fs/path path
                          :my.fs/byte-offset byte-offset
                          :my.fs/bytes-read bytes-read})))))))))

(defn- read-window
  [request effective window-limit complete?]
  (let [path (:my.fs/path request)]
    (try
      (let [{directory :seon.fs.jvm/directory
             entry-name :seon.fs.jvm/name
             streams :seon.fs.jvm/streams}
            (parent-access path effective)]
        (try
          (read-opened request effective directory entry-name window-limit
                       complete?)
          (finally
            (close-streams! streams))))
      (catch Throwable error
        (error-value error :my.fs/read-failed true "The file read failed."
                     {:my.fs/path path})))))

(defn- read
  "The agent-facing WINDOW read: bounded by the window, never by the file.

  The window this returns is `:my.fs/max-bytes` capped by the inline ceiling,
  and that is also the entire cost — no size of surrounding file can refuse
  it. That is the affordance the docstring promises for a file too large to
  take whole, and it now works on a file of any size."
  {:malli/schema
   [:=> [:cat :my.fs/read-request :seon.config/effective]
    [:or :my.fs/read-result :seon.error/value]]}
  [request effective]
  (read-window
   request effective
   (long (min (:seon.config.fs/max-inline-bytes effective)
              (or (:my.fs/max-bytes request) Long/MAX_VALUE)))
   false))

(defn- read-complete
  "Read one complete UTF-8 file for a higher-level conditional operation.

  This one genuinely wants the whole file, so it is the arm the read ceiling
  bounds, and it is the only read that can refuse for a file's size."
  {:malli/schema
   [:=> [:cat :my.fs/read-request :seon.config/effective]
    [:or :my.fs/read-result :seon.error/value]]}
  [request effective]
  (read-window request effective
               (long (:seon.config.fs/max-read-bytes effective))
               true))

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
                       write-limit
                       "The write content exceeds the configured ceiling."
                       {:seon.config.fs/max-write-bytes write-limit}))
            (byte-array (map unchecked-byte values)))

          :else nil)]
    (when (and octets (> (alength ^bytes octets) write-limit))
      (refuse! :my.fs/write-limit
               write-limit
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
                   true
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
                     digest
                     "The content-addressed blob is unavailable."
                     {:seon.blob/digest digest}))

          (> (+ offset (alength ^bytes octets)) write-limit)
          (refuse! :my.fs/write-limit
                   write-limit
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
                 expected
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
                     path
                     "The final filesystem path is a symbolic link."
                     {:my.fs/path path})
            (refuse! :my.fs/already-exists
                     path
                     "The file already exists."
                     {:my.fs/path path})))
        {:my.fs/created? true})
      (let [expected (:my.fs/expected-digest precondition)
            current (current-file directory entry-name path read-limit)
            actual (:seon.fs.jvm/digest current)]
        (when-not (= expected actual)
          (refuse! :my.fs/stale-digest
                   path
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
                     (:seon.fs.jvm/blob info)
                     "The blob changed while it was staged."
                     {:seon.blob/digest (:seon.fs.jvm/blob info)}))))
      (when-not (instance? FileChannel channel)
        (refuse! :my.fs/atomic-write-unsupported
                 true
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
                               true
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
        (error-value error :my.fs/write-failed true "The file write failed."
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
                     (str entry)
                     "A path changed while the directory was examined."
                     {:my.fs/path (str entry)}))
          (recur (conj entries {:seon.fs.jvm/name entry-name
                                :seon.fs.jvm/attrs attrs})
                 (dec remaining)))))))

;;; The traversal state — collected paths, examined count, and whether
;;; every cap still holds — is the walk's RETURN VALUE. A recursive
;;; child hands its state back to its parent, so no reference outlives
;;; the call that owns it and an early stop is one `reduced`.
(declare glob-walk)

(defn- glob-entry
  [^SecureDirectoryStream directory relative depth matcher limits state
   {entry-name :seon.fs.jvm/name attrs :seon.fs.jvm/attrs}]
  (if-not (:my.fs/complete? state)
    (reduced state)
    (let [child-relative (if (empty? (str relative))
                           entry-name
                           (.resolve ^Path relative ^Path entry-name))
          state
          (if (.matches ^java.nio.file.PathMatcher matcher child-relative)
            (if (< (count (:my.fs/paths state))
                   (:seon.fs.jvm/max-results limits))
              (update state :my.fs/paths conj (str child-relative))
              (assoc state :my.fs/complete? false))
            state)]
      (if (and (:my.fs/complete? state)
               (:seon.fs.jvm/directory? attrs))
        (cond
          (< depth (:seon.fs.jvm/effective-depth limits))
          (with-open [child (.newDirectoryStream
                             directory entry-name no-follow-links)]
            (when-not (instance? SecureDirectoryStream child)
              (refuse! :my.fs/glob-failed
                       (str child-relative)
                       "This filesystem cannot securely traverse the tree."
                       {:my.fs/path (str child-relative)}))
            (glob-walk child child-relative (inc depth)
                       state matcher limits))

          (:seon.fs.jvm/depth-policy-limited? limits)
          (assoc state :my.fs/complete? false)

          :else state)
        state))))

(defn- glob-walk
  [^SecureDirectoryStream directory relative depth state matcher limits]
  (if-not (:my.fs/complete? state)
    state
    (let [remaining (- (:seon.fs.jvm/max-entries limits)
                       (:my.fs/examined state))
          {:seon.fs.jvm/keys [entries exhausted?]}
          (directory-entries directory remaining)
          ordered (sort-by (comp str :seon.fs.jvm/name) entries)]
      (reduce
       (fn [state entry]
         (glob-entry directory relative depth matcher limits state entry))
       (cond-> (update state :my.fs/examined + (count entries))
         (not exhausted?) (assoc :my.fs/complete? false))
       ordered))))

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
            (directory-access root effective)]
        (try
          (let [result (glob-walk directory (fs/path "") 0
                                  {:my.fs/paths []
                                   :my.fs/examined 0
                                   :my.fs/complete? true}
                                  matcher limits)
                paths (vec (sort (:my.fs/paths result)))]
            {:my.fs/root root
             :my.fs/paths paths
             :my.fs/returned (long (count paths))
             :my.fs/examined (long (:my.fs/examined result))
             :my.fs/complete? (:my.fs/complete? result)})
          (finally
            (close-streams! streams))))
      (catch java.util.regex.PatternSyntaxException error
        (error-value error :my.fs/invalid-glob (:my.fs/pattern request)
                     "The JDK glob pattern is invalid."
                     {:my.fs/root root
                      :my.fs/pattern (:my.fs/pattern request)}))
      (catch Throwable error
        (error-value error :my.fs/glob-failed root "The filesystem glob failed."
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
            (refuse! :my.fs/not-found path "The filesystem path does not exist."
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
        (error-value error :my.fs/read-failed true
                     "The filesystem attributes could not be read."
                     {:my.fs/path path})))))
