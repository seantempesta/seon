(ns seon.fs
  "Filesystem operations whose safety depends on path ownership."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.error.refusal :as refusal]
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
  {:malli/schema [:=> [:cat :string :string] :boolean]}
  [ancestor descendant]
  (let [sep java.io.File/separator]
    (or (= ancestor descendant)
        (str/starts-with? descendant (if (str/ends-with? ancestor sep) ancestor (str ancestor sep))))))

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
  "The first-party frame that asked for a deletion, as `class.method(file:line)`."
  {:malli/schema [:=> [:cat] :string]}
  []
  ;; the deletion owners are not the caller, and neither is the plumbing
  ;; between them (contract wrappers, apply, the JDK): the first FIRST-PARTY
  ;; frame outside the owners is who asked for this deletion
  ;; walked from here outward, stopping at the answer; only that frame is named
  (let [owners ["seon.cluster.store" "seon.fs" "seon.instrument"]
        named (fn [prefixes]
                (refusal/first-frame-answer
                 (fn [[class-name method file line]]
                   (let [class-name (str class-name)]
                     (when (and (not (some #(str/starts-with? class-name %) owners))
                                (prefixes class-name))
                       (str class-name "." method "(" file ":" line ")"))))))]
    (or (named #(str/starts-with? % "seon."))
        (named (fn [class-name] (not (some #(str/starts-with? class-name %) ["clojure." "malli." "java." "jdk."]))))
        "unknown")))

(defn log-deletion!
  "Record one admitted recursive deletion BEFORE it runs.

  Takes the deletion root, targets, byte count and operation under
  `:seon.cluster.store`; logs the deletion root, target count and bytes, the
  deletion authority, the calling frame and this process's pid (the targets
  are the returned report's data), so a future wipe names itself
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
    ;; fixed text and references; the targets themselves are the returned
    ;; report's data, never printed here
    (log/warn (str "seon recursive deletion of " (count (:seon.cluster.store/targets report))
                   " targets (" (:seon.cluster.store/file-bytes report) " bytes) under "
                   (:seon.cluster.store/root report) " by " (:seon.cluster.store/operation report)
                   " from " (:seon.cluster.store/caller report) ", pid " (:seon.cluster.store/pid report)))
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

(defn checkout-root?
  "Whether a value is a string naming an absolute, existing directory that is not
  inside a `data/source` archive (the committed copy a Seon JVM runs from).

  Takes a value and returns a boolean. This predicate validates a root that
  source files are written under."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (boolean
   (and (string? value)
        (let [file (io/file value)]
          (and (.isAbsolute file) (.isDirectory file)
               (not-any? #(= ["data" "source"] %)
                         (partition 2 1 (map str (.toPath (io/file (.getCanonicalPath file)))))))))))

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
  {:malli/schema [:=> [:cat :any :any] [:sequential :any]]}
  [^Path root ^Path target]
  (let [rel (.relativize root target)]
    (if (zero? (count (str rel)))
      []
      (butlast
       (reductions
        (fn [^Path parent segment]
          (.resolve parent ^Path segment))
        root
        (iterator-seq (.iterator rel)))))))

(defn- attributes
  ^BasicFileAttributes [^Path path]
  (try
    (Files/readAttributes path BasicFileAttributes no-follow)
    (catch NoSuchFileException _ nil)))

(defn entry-count
  "The number of entries directly in `directory`, from one listing with no
  per-entry attribute read; an absent directory is zero. A konserve file store
  keeps one file per key, so this is the store's key count, the growth a
  status reader sees.

  Costs: unarmed, one directory listing proportional to its entries:
  1,053,786 entries in 1,329 ms on 2026-09-28 (default's `data/store` before
  its first successful collection)."
  {:malli/schema [:=> [:cat :string] [:int {:min 0}]]}
  [directory]
  (count (or (.list (io/file directory)) [])))

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

(defn path?
  "True for a `java.nio.file.Path`."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (instance? Path value))

(defn file-attributes?
  "True for one observation of a path's basic attributes."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (instance? BasicFileAttributes value))

(defn deletion-state
  "The starting state of one recursive deletion of `target` beneath `root`:
  both paths, zeroed counts and the first progress deadline. Refuses a target
  outside `root` or reached through an intermediate symlink."
  {:malli/schema
   [:=> [:cat :string :string
         [:map [::progress! {:optional true} [:fn clojure.core/ifn?]]
          [::progress-backstop-ms {:optional true} [:int {:min 1}]]]]
    [:map [::root-path [:fn seon.fs/path?]] [::target-path [:fn seon.fs/path?]] [::started-nanos :int] [::next-report :int] [::deleted :int] [::files :int] [::directories :int] [::symlinks :int] [::progress! {:optional true} [:fn clojure.core/ifn?]] [::interval-nanos {:optional true} :int]]]}
  [root target {progress! ::progress! progress-backstop-ms ::progress-backstop-ms}]
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
    (cond-> {::root-path root-path ::target-path target-path
             ::started-nanos started-nanos
             ::next-report (+ started-nanos (or interval-nanos 0))
             ::deleted 0 ::files 0 ::directories 0 ::symlinks 0}
      progress! (assoc ::progress! progress! ::interval-nanos interval-nanos))))

(defn delete-observed!
  "Delete `entry`, observed as `attribute`, and everything beneath it, folding
  the counts into `state`. Symlinks are leaves; every entry is checked against
  the state's root before it is deleted. An entry that vanished since it was
  observed is already deleted and leaves `state` unchanged."
  {:malli/schema
   [:=> [:cat [:map [::root-path [:fn seon.fs/path?]] [::target-path [:fn seon.fs/path?]] [::started-nanos :int] [::next-report :int] [::deleted :int] [::files :int] [::directories :int] [::symlinks :int] [::progress! {:optional true} [:fn clojure.core/ifn?]] [::interval-nanos {:optional true} :int]] [:fn seon.fs/path?] [:fn seon.fs/file-attributes?]]
    [:map [::root-path [:fn seon.fs/path?]] [::target-path [:fn seon.fs/path?]] [::started-nanos :int] [::next-report :int] [::deleted :int] [::files :int] [::directories :int] [::symlinks :int] [::progress! {:optional true} [:fn clojure.core/ifn?]] [::interval-nanos {:optional true} :int]]]}
  [{root-path ::root-path target-path ::target-path progress! ::progress!
    interval-nanos ::interval-nanos started-nanos ::started-nanos :as state}
   entry attribute]
  ;; The walk is one fold over the tree: its state is the deletion counts and
  ;; the next progress deadline, threaded through every entry it deletes.
  (letfn [(under-root? [^Path candidate]
            (.startsWith (.normalize (.toAbsolutePath candidate))
                         ^Path root-path))
          (reported [state ^Path entry ^BasicFileAttributes attribute]
            (let [now (System/nanoTime)]
              (if (and interval-nanos (<= ^long (::next-report state) now))
                (do (progress!
                     (assoc (select-keys state [::deleted ::files ::directories ::symlinks])
                            ::directory (str (if (.isDirectory attribute) entry (.getParent entry)))
                            ::elapsed-ms (.toMillis TimeUnit/NANOSECONDS (- now ^long started-nanos))))
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
    (walk state entry attribute)))

(defn- delete-recursively-impl!
  [root target options]
  (let [state (deletion-state root target options)]
    (when-let [target-attribute (attributes (::target-path state))]
      (delete-observed! state (::target-path state) target-attribute))
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
   (delete-recursively-impl! root target {}))
  ([root target options]
   (delete-recursively-impl! root target options)))

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
