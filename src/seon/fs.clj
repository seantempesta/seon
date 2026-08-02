(ns seon.fs
  "Filesystem operations whose safety depends on path ownership."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files LinkOption Path]))

(set! *warn-on-reflection* true)

(def ^:private no-follow
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

(defn delete-recursively!
  "Delete a path beneath its explicit root without following symlinks.

  `root` is the caller's deletion authority, not a path derived from the
  process working directory. Paths compare lexically after absolute
  normalization so the target itself may be a symlink: that link entry is
  deleted while its referent remains untouched. An intermediate symlink is
  refused because reaching the target through it would already have left the
  explicit root.

  The walk uses `NOFOLLOW_LINKS`, treats every symlink as a leaf, and checks
  every entry against the same explicit root before deleting it. This is the
  one recursive-deletion owner for fresh source and test cleanup."
  {:malli/schema
   [:=>
    [:catn [::root :string]
     [::target :string]]
    :nil]}
  [root target]
  (let [root-path (normalized-path root)
        target-path (normalized-path target)]
    (when-not (.startsWith target-path root-path)
      (throw
       (ex-info
        "Recursive deletion target is outside its explicit root."
        {::root (str root-path)
         ::target (str target-path)})))
    (when (some #(Files/isSymbolicLink ^Path %)
                (intermediate-paths root-path target-path))
      (throw
       (ex-info
        "Recursive deletion target crosses an intermediate symlink."
        {::root (str root-path)
         ::target (str target-path)})))
    (letfn [(under-root? [^Path candidate]
              (.startsWith (.normalize (.toAbsolutePath candidate))
                           root-path))
            (delete-one! [^Path entry]
              (when-not (under-root? entry)
                (throw
                 (ex-info
                  "Recursive deletion reached outside its explicit root."
                  {::root (str root-path)
                   ::target (str target-path)
                   ::entry (str entry)})))
              (Files/deleteIfExists entry))
            (walk! [^Path entry]
              (when-not (Files/isSymbolicLink entry)
                (when (Files/isDirectory entry no-follow)
                  (with-open [children (Files/newDirectoryStream entry)]
                    (run! walk! (vec children)))))
              (delete-one! entry))]
      (when (Files/exists target-path no-follow)
        (walk! target-path)))
    nil))
