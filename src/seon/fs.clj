(ns seon.fs
  "Filesystem operations whose safety depends on path ownership."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.nio.file Files LinkOption NoSuchFileException Path]
           [java.nio.file.attribute BasicFileAttributes]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- destructive-canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn- refuse-deletion! [rule message data]
  (throw (ex-info message
                  (assoc data :seon.error/kind :seon.cluster.store/refused
                         :seon.cluster.store/refused rule
                         :seon.cluster.store/rule rule))))

(defn- path-string
  [value]
  (cond
    (string? value) value
    (instance? java.io.File value) (.getPath ^java.io.File value)))

(defn- under-path?
  [ancestor descendant]
  (or (= ancestor descendant)
      (str/starts-with? descendant (str ancestor java.io.File/separator))))

(defn admit-destructive-path!
  "Admit one recursive deletion, or refuse BEFORE anything is deleted.

  Takes `{:seon.cluster.store/root, :seon.cluster.store/target, :seon.cluster.store/declared-root}` and returns the canonical
  target path. `:seon.cluster.store/root` is the caller's deletion authority, `:seon.cluster.store/target` the
  path it wants removed, and `:seon.cluster.store/declared-root` the operator root this JVM was
  launched to operate (`declared-operator-root`), absent when none was
  declared.

  THE RULE, refused as a typed store refusal naming the offending value:
  the root and the target must be absolute (nil, \"\", \".\" and any relative
  spelling resolve against the process working directory and are refused);
  the canonical target must lie under the canonical root; and a target
  inside the working directory's own `data/` is refused unless
  `:seon.cluster.store/declared-root` is that working directory. So `bin/seon reset --force`
  destroys the checkout's data because its JVM declared that root, while a
  test worker, a fixture, or a lane JVM — which declare an isolated root or
  none — cannot construct the deletion."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The admission judges caller-supplied path values of any shape, including nil and relative strings, and refuses them by typed value."}]]
    :string]}
  [request]
  (let [root (:seon.cluster.store/root request)
        target (:seon.cluster.store/target request)
        declared (:seon.cluster.store/declared-root request)
        root-string (path-string root)
        target-string (path-string target)]
    (when (str/blank? root-string)
      (refuse-deletion! :seon.cluster.store/undeclared-destructive-root
               (str "a recursive deletion was requested with no deletion "
                    "authority (" (pr-str root) "); the root is the disposable "
                    "root the caller holds, never the working directory")
               {:seon.cluster.store/root root :seon.cluster.store/target target}))
    (when (str/blank? target-string)
      (refuse-deletion! :seon.cluster.store/undeclared-destructive-target
               (str "a recursive deletion was requested with no target ("
                    (pr-str target) ")")
               {:seon.cluster.store/root root :seon.cluster.store/target target}))
    (when-not (.isAbsolute (io/file root-string))
      (refuse-deletion! :seon.cluster.store/relative-destructive-root
               (str "the deletion authority " (pr-str root-string)
                    " is relative, so it names the process working directory "
                    (pr-str (System/getProperty "user.dir"))
                    "; hand the absolute disposable root instead")
               {:seon.cluster.store/root root-string :seon.cluster.store/target target-string}))
    (when-not (.isAbsolute (io/file target-string))
      (refuse-deletion! :seon.cluster.store/relative-destructive-target
               (str "the deletion target " (pr-str target-string)
                    " is relative, so it names the process working directory "
                    (pr-str (System/getProperty "user.dir")))
               {:seon.cluster.store/root root-string :seon.cluster.store/target target-string}))
    (let [authority (destructive-canonical-path root-string)
          resolved (destructive-canonical-path target-string)
          working (destructive-canonical-path (System/getProperty "user.dir"))
          working-data (destructive-canonical-path (io/file working "data"))]
      (when-not (under-path? authority resolved)
        (refuse-deletion! :seon.cluster.store/destructive-path-outside-root
                 (str "refusing to delete " resolved
                      " because it lies outside the deletion authority "
                      authority)
                 {:seon.cluster.store/root authority :seon.cluster.store/target resolved}))
      (when (and (under-path? working-data resolved)
                 (not= declared working))
        (refuse-deletion! :seon.cluster.store/undeclared-checkout-deletion
                 (str "refusing to delete " resolved
                      " inside the working directory's own data directory: "
                      "this JVM declared operator root " (pr-str declared)
                      ", not " (pr-str working)
                      "; only a JVM launched to operate that root (bin/seon "
                      "[--root PATH]) may destroy it")
                 {:seon.cluster.store/root authority
                  :seon.cluster.store/target resolved
                  :seon.cluster.store/declared-root declared
                  :seon.cluster.store/working-directory working}))
      resolved)))

(defn- caller-frame
  []
  ;; the deletion owners are not the caller, and neither is the plumbing
  ;; between them (contract wrappers, apply, the JDK): the first FIRST-PARTY
  ;; frame outside the owners is who asked for this deletion
  (let [owners ["seon.cluster.store" "seon.fs" "seon.instrument"]
        frames (map str (.getStackTrace (Thread/currentThread)))
        outside (remove (fn [frame]
                          (some #(str/starts-with? frame %) owners))
                        frames)]
    (or (first (filter #(str/starts-with? % "seon.") outside))
        (first (remove (fn [frame]
                         (some #(str/starts-with? frame %)
                               ["clojure." "malli." "java." "jdk."]))
                       outside))
        "unknown")))

(defn log-deletion!
  "Record one admitted recursive deletion BEFORE it runs.

  Takes the deletion root, targets, byte count and operation under
  `:seon.cluster.store`; logs every canonical target and deletion authority,
  the calling frame, and this process's pid, so a future wipe names itself
  instead of leaving the recurring absence-of-signal. Interpreted callers
  supply their caller explicitly because JVM stack frames name SCI internals.
  Returns the recorded report."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.cluster.store/root :string]
               [:seon.cluster.store/targets [:vector :string]]
               [:seon.cluster.store/file-bytes :int]
               [:seon.cluster.store/operation :string]
               [:seon.cluster.store/caller {:optional true} :string]]]
    [:map
     [:seon.cluster.store/root :string]
     [:seon.cluster.store/targets [:vector :string]]
     [:seon.cluster.store/file-bytes :int]
     [:seon.cluster.store/operation :string]
     [:seon.cluster.store/caller :string]
     [:seon.cluster.store/pid :int]]]}
  [request]
  (let [report (assoc request
                      :seon.cluster.store/caller (or (:seon.cluster.store/caller request)
                                                    (caller-frame))
                      :seon.cluster.store/pid (.pid (java.lang.ProcessHandle/current)))]
    (log/warn (str "seon recursive deletion: " (pr-str report)))
    report))

(defn source-directory
  "The checkout directory supplying the loaded filesystem owner."
  {:malli/schema [:=> [:cat] :string]}
  []
  (let [resource (io/resource "seon/fs.clj")]
    (when-not (= "file" (.getProtocol resource))
      (throw (ex-info "Source indexing requires a source checkout."
                      {:seon.fs/resource (str resource)})))
    (-> resource .toURI io/file .getParentFile .getParentFile
        .getParentFile .getCanonicalPath)))

(defn absolute-path
  "Resolve a filesystem path against its explicit directory."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [directory path]
  (let [file (io/file path)]
    (.getCanonicalPath (if (.isAbsolute file) file (io/file directory path)))))

(defn relative-path
  "Canonical file identity relative to its explicit directory."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [directory path]
  (str (.relativize (.toPath (.getCanonicalFile (io/file directory)))
                    (.toPath (io/file (absolute-path directory path))))))

(defn- normalized-path
  ^Path [path]
  (.normalize (.toAbsolutePath (.toPath (io/file path)))))

(defn- intermediate-paths
  [^Path root ^Path target]
  (butlast
   (reductions
    (fn [^Path parent segment]
      (.resolve parent ^Path segment))
    root
    (iterator-seq (.iterator (.relativize root target))))))

(defn- attributes
  ^BasicFileAttributes [^Path path]
  (try
    (Files/readAttributes path BasicFileAttributes no-follow)
    (catch NoSuchFileException _ nil)))

(defn- progress-reporter
  [progress! progress-backstop-ms started-nanos]
  (when progress!
    (let [interval-nanos
          (.toNanos TimeUnit/MILLISECONDS (long progress-backstop-ms))
          next-report (volatile! (+ started-nanos interval-nanos))]
      (fn [directory counts]
        (let [now (System/nanoTime)]
          (when (<= ^long @next-report now)
            (vreset! next-report (+ now interval-nanos))
            (progress!
             (assoc counts
                    ::directory (str directory)
                    ::elapsed-ms
                    (.toMillis TimeUnit/NANOSECONDS
                               (- now started-nanos))))))))))

(defn- delete-recursively-impl!
  [root target progress! progress-backstop-ms]
  (let [root-path (normalized-path root)
        target-path (normalized-path target)
        started-nanos (System/nanoTime)
        report-progress!
        (progress-reporter progress! progress-backstop-ms started-nanos)
        ^longs counts (long-array 4)]
    (when-not (.startsWith target-path root-path)
      (throw
       (ex-info
        "Recursive deletion target is outside its explicit root."
        {::root (str root-path)
         ::target (str target-path)})))
    (when (some #(some-> (attributes ^Path %) .isSymbolicLink)
                (intermediate-paths root-path target-path))
      (throw
       (ex-info
        "Recursive deletion target crosses an intermediate symlink."
        {::root (str root-path)
         ::target (str target-path)})))
    (letfn [(under-root? [^Path candidate]
              (.startsWith (.normalize (.toAbsolutePath candidate))
                           root-path))
            (delete-one! [^Path entry attribute]
              (when-not (under-root? entry)
                (throw
                 (ex-info
                  "Recursive deletion reached outside its explicit root."
                  {::root (str root-path)
                   ::target (str target-path)
                   ::entry (str entry)})))
              (when (Files/deleteIfExists entry)
                (aset-long counts 0 (inc (aget counts 0)))
                (aset-long
                 counts
                 (cond
                   (.isSymbolicLink ^BasicFileAttributes attribute) 3
                   (.isDirectory ^BasicFileAttributes attribute) 2
                   :else 1)
                 (inc
                  (aget counts
                        (cond
                          (.isSymbolicLink
                           ^BasicFileAttributes attribute) 3
                          (.isDirectory
                           ^BasicFileAttributes attribute) 2
                          :else 1))))
                (when report-progress!
                  (report-progress!
                   (if (.isDirectory ^BasicFileAttributes attribute)
                     entry
                     (.getParent entry))
                   {::deleted (aget counts 0)
                    ::files (aget counts 1)
                    ::directories (aget counts 2)
                    ::symlinks (aget counts 3)}))))
            (walk! [^Path entry ^BasicFileAttributes attribute]
              (try
                (when (.isDirectory attribute)
                  (with-open [children (Files/newDirectoryStream entry)]
                    (run!
                     (fn [^Path child]
                       (when-let [child-attribute (attributes child)]
                         (walk! child child-attribute)))
                     (vec children))))
                (delete-one! entry attribute)
                (catch NoSuchFileException _
                  nil)))]
      (when-let [target-attribute (attributes target-path)]
        (walk! target-path target-attribute)))
    nil))

(defn delete-recursively!
  "Delete a path beneath its explicit root without following symlinks.

  `root` is the caller's deletion authority, not a path derived from the
  process working directory. Paths compare lexically after absolute
  normalization so the target itself may be a symlink: that link entry is
  deleted while its referent remains untouched. An intermediate symlink is
  refused because reaching the target through it would already have left the
  explicit root.

  The walk reads each entry's basic attributes once with `NOFOLLOW_LINKS`,
  treats every symlink as a leaf, and checks every entry against the same
  explicit root before deleting it. A path removed by a concurrent deletion is
  already complete for this walk. The optional callback receives rate-bounded
  progress after each declared backstop interval. This is the one recursive-
  deletion owner for fresh source and test cleanup."
  {:malli/schema
   [:function
    [:=>
     [:catn [::root :string]
      [::target :string]]
     :nil]
    [:=>
     [:catn
      [::root :string]
      [::target :string]
      [::options
       [:map
        [::progress! [:fn clojure.core/ifn?]]
        [::progress-backstop-ms [:int {:min 1}]]]]]
     :nil]]}
  ([root target]
   (delete-recursively-impl! root target nil nil))
  ([root target {progress! ::progress!
                 progress-backstop-ms ::progress-backstop-ms}]
   (delete-recursively-impl!
    root target progress! progress-backstop-ms)))

(defn content?
  "Whether a value names exactly one file-content source.

  Takes a value and returns a boolean. This predicate validates text, bytes,
  or blob-digest content for `write`."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (map? value)
       (= 1
          (count
           (filter #(contains? value %)
                   [:my.fs/text :my.fs/bytes :seon.blob/digest])))))


(defn write-precondition?
  "Whether a value names exactly one write precondition.

  Takes a value and returns a boolean. This predicate validates an expected
  absence or expected digest for `write`."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (map? value)
       (= 1
          (count
           (filter #(contains? value %)
                   [:my.fs/expected-absence? :my.fs/expected-digest])))))
