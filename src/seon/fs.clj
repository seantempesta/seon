(ns seon.fs
  "Filesystem operations whose safety depends on path ownership."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.nio.file Files LinkOption NoSuchFileException Path CopyOption StandardCopyOption]
           [java.nio.file.attribute BasicFileAttributes]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn write-edn-atomically!
  "Replace an EDN map with one same-directory atomic rename. Readers see complete values."
  {:malli/schema [:=> [:cat :string :map] :nil]}
  [path value]
  (let [target (.toPath (io/file path))
        parent (.getParent (.toAbsolutePath target))
        temporary (Files/createTempFile parent ".seon-edn-" ".tmp"
                                         (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (.toFile temporary) (str (pr-str value) "\n"))
      (Files/move temporary target
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                          StandardCopyOption/REPLACE_EXISTING]))
      nil
      (finally (Files/deleteIfExists temporary)))))

(defn filesystem-space
  "Observe the requested volume without traversing its directory tree.

  The volume's own space accounting (`statfs`) answers in microseconds
  whatever the store's file count; it is the footprint a maintenance or
  status decision reads."
  {:malli/schema [:=> [:cat :string] :seon.operator/footprint]}
  [root]
  (let [file (.getAbsoluteFile (io/file root))
        ^java.io.File existing (loop [candidate file]
                   (if (.exists candidate) candidate
                       (recur (.getParentFile candidate))))
        total (.getTotalSpace existing)
        usable (.getUsableSpace existing)]
    {:seon.operator.footprint/root (.getPath file)
     :seon.operator.footprint/usable-bytes usable
     :seon.operator.footprint/total-bytes total
     :seon.operator.footprint/usable-ratio
     (if (pos? total) (/ (double usable) (double total)) 0.0)
     :seon.operator.footprint/observed-at (java.util.Date.)}))

(defn- destructive-canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn- refuse-deletion!
  {:malli/schema [:=> [:cat :keyword :string :map] :nil]}
  [rule message data]
  (throw (ex-info message
                  (assoc data :seon.cluster.store/refused rule
                         :seon.cluster.store/rule rule))))

(defn file?
  "True for a `java.io.File`. A named predicate for contracts naming files:
  a durable contract names its predicate; an anonymous `#(instance? ...)`
  refuses canonicalization (`seon.schema/canonical-definition`)."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                                    :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they are not files."
                                    :gen/elements [nil false 0 "" :k [] {}]}]]
                  :boolean]}
  [value]
  (instance? java.io.File value))

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

(defn canonical-relative-path
  "`path` relative to `directory` when both are already canonical: a pure
  path operation, no filesystem read. Anything else (a relative path, a path
  outside `directory`) answers [[relative-path]]'s filesystem result.

  Costs: none for a canonical path under `directory`; [[relative-path]]'s
  two canonicalizations otherwise."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [directory path]
  (if (and (.isAbsolute (io/file path))
           (str/starts-with? path (str directory java.io.File/separator)))
    (subs path (inc (count directory)))
    (relative-path directory path)))

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

(defn tree-bytes
  "The bytes of the regular files under `root`; symbolic links are leaves of
  zero size and are never followed, and an absent root is zero.

  Costs: unarmed, proportional to the tree's entries, one attribute read each
  (`Files/readAttributes` with NOFOLLOW_LINKS). Only a deletion's log and a
  whole-store collection pay it; decisions read `filesystem-space`. Loads in
  babashka, which runs this namespace for the operator."
  {:malli/schema [:=> [:cat :string] [:int {:min 0}]]}
  [root]
  (letfn [(size-of [^Path path]
            (let [attribute (attributes path)]
              (cond
                (nil? attribute) 0
                (.isDirectory attribute)
                (with-open [children (Files/newDirectoryStream path)]
                  (reduce (fn [total child] (+ total (size-of child))) 0 children))
                (.isRegularFile attribute) (.size attribute)
                :else 0)))]
    (size-of (.toPath (io/file root)))))

(defn- entry-kind
  "The count a deleted entry adds to: its link, directory or file."
  [^BasicFileAttributes attribute]
  (cond (.isSymbolicLink attribute) ::symlinks
        (.isDirectory attribute) ::directories
        :else ::files))

(defn- delete-recursively-impl!
  [root target progress! progress-backstop-ms]
  (let [root-path (normalized-path root)
        target-path (normalized-path target)
        started-nanos (System/nanoTime)
        interval-nanos (when progress!
                         (.toNanos TimeUnit/MILLISECONDS (long progress-backstop-ms)))]
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
    ;; The walk is one fold over the tree: its state is the deletion counts and
    ;; the next progress deadline, threaded through every entry it deletes.
    (letfn [(under-root? [^Path candidate]
              (.startsWith (.normalize (.toAbsolutePath candidate))
                           root-path))
            (reported [state ^Path entry ^BasicFileAttributes attribute]
              (let [now (System/nanoTime)]
                (if (and interval-nanos (<= ^long (::next-report state) now))
                  (do (progress!
                       (assoc (select-keys state [::deleted ::files ::directories ::symlinks])
                              ::directory (str (if (.isDirectory attribute) entry (.getParent entry)))
                              ::elapsed-ms (.toMillis TimeUnit/NANOSECONDS (- now started-nanos))))
                      (assoc state ::next-report (+ now ^long interval-nanos)))
                  state)))
            (delete-one [state ^Path entry attribute]
              (when-not (under-root? entry)
                (throw
                 (ex-info
                  "Recursive deletion reached outside its explicit root."
                  {::root (str root-path)
                   ::target (str target-path)
                   ::entry (str entry)})))
              (if (Files/deleteIfExists entry)
                (-> state
                    (update ::deleted inc)
                    (update (entry-kind attribute) inc)
                    (reported entry attribute))
                state))
            (walk [state ^Path entry ^BasicFileAttributes attribute]
              (try
                (delete-one
                 (if (.isDirectory attribute)
                   (with-open [children (Files/newDirectoryStream entry)]
                     (reduce (fn [state ^Path child]
                               (if-let [child-attribute (attributes child)]
                                 (walk state child child-attribute)
                                 state))
                             state children))
                   state)
                 entry attribute)
                (catch NoSuchFileException _
                  state)))]
      (when-let [target-attribute (attributes target-path)]
        (walk {::deleted 0 ::files 0 ::directories 0 ::symlinks 0
               ::next-report (+ started-nanos (or interval-nanos 0))}
              target-path target-attribute)))
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
