(ns seon.fs-test
  "Recurring proof for the one recursive filesystem deletion owner."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.fs :as fs])
  (:import [java.nio.file Files LinkOption]))

(def ^:private no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(deftest recursive-deletion-never-crosses-a-symlink
  (let [base (str "tmp/fs-test/" (random-uuid))
        root (io/file base "owned")
        outside (io/file base "outside")
        sentinel (io/file outside "nested/must-survive.txt")
        link (io/file root "linked-elsewhere")]
    (try
      (.mkdirs root)
      (.mkdirs (.getParentFile sentinel))
      (spit sentinel "do not delete me")
      (Files/createSymbolicLink
       (.toPath link)
       (.toAbsolutePath (.toPath outside))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (fs/delete-recursively! (.getPath root) (.getPath root))
      (is (not (Files/exists (.toPath root) no-follow))
          "the owned root is deleted")
      (is (Files/exists (.toPath sentinel) no-follow)
          "a sentinel reachable only through the link survives")
      (is (Files/exists (.toPath outside) no-follow)
          "the target directory survives; only the link entry is deleted")
      (testing "an intermediate link cannot smuggle a narrower target out"
        (.mkdirs root)
        (Files/createSymbolicLink
         (.toPath link)
         (.toAbsolutePath (.toPath outside))
         (make-array java.nio.file.attribute.FileAttribute 0))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"crosses an intermediate symlink"
             (fs/delete-recursively!
              (.getPath root)
              (.getPath (io/file link "nested")))))
        (is (Files/exists (.toPath sentinel) no-follow)))
      (finally
        (fs/delete-recursively! base base)))))
