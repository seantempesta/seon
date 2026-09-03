(ns seon.fs
  "Filesystem operations whose safety depends on path ownership."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files LinkOption NoSuchFileException Path]
           [java.nio.file.attribute BasicFileAttributes]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

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
   (delete-recursively! root target nil))
  ([root target {progress! ::progress!
                 progress-backstop-ms ::progress-backstop-ms}]
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
     nil)))
