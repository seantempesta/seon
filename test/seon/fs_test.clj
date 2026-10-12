(ns ^{:seon.test/platform
       "Moving part: the one no-follow recursive deletion owner."}
    seon.fs-test
  "Recurring proof for the one recursive filesystem deletion owner."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.test-support :as test-support]
            [seon.fs :as fs])
  (:import [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute BasicFileAttributes]))

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

(deftest recursive-deletion-publishes-rate-bounded-progress
  (let [base (str "tmp/fs-progress-test/" (random-uuid))
        root (io/file base "owned")
        nested (io/file root "nested")
        progress (atom [])]
    (try
      (.mkdirs nested)
      (dotimes [ordinal 1000]
        (spit (io/file nested (str ordinal)) ""))
      (fs/delete-recursively!
       base (.getPath root)
       {:seon.fs/progress! #(swap! progress conj %)
        :seon.fs/progress-backstop-ms 1})
      (is (seq @progress) "a deletion exceeding its backstop reports progress")
      (is (every? #(= (.getAbsolutePath nested)
                      (:seon.fs/directory %))
                  (butlast @progress))
          "each file batch names the directory it is deleting")
      (let [last-progress (peek @progress)]
        (is (pos? (:seon.fs/deleted last-progress)))
        (is (pos? (:seon.fs/files last-progress)))
        (is (<= (+ (:seon.fs/files last-progress)
                   (:seon.fs/directories last-progress)
                   (:seon.fs/symlinks last-progress))
                (:seon.fs/deleted last-progress)))
        (is (pos? (:seon.fs/elapsed-ms last-progress))))
      (finally
        (fs/delete-recursively! base base)))))

(deftest concurrent-deletions-treat-a-vanished-path-as-success
  ;; The first deletion observes the root and its child, the second deletes
  ;; both, then the first continues from what it observed.
  (let [base (str "tmp/fs-concurrent-test/" (random-uuid))
        root (io/file base "owned")]
    (try
      (.mkdirs (io/file root "child"))
      (let [first-deletion (fs/deletion-state base (.getPath root) {})
            root-path (:seon.fs/target-path first-deletion)
            child-path (.resolve ^Path root-path "child")
            observe #(Files/readAttributes ^Path % BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" no-follow)
            root-observed (observe root-path)
            child-observed (observe child-path)]
        (fs/delete-recursively! base (.getPath root))
        (is (= first-deletion
               (fs/delete-observed! first-deletion child-path child-observed))
            "a child that vanished after it was observed is already deleted")
        (is (= first-deletion
               (fs/delete-observed! first-deletion root-path root-observed))
            "a root that vanished after it was observed is already deleted")
        (is (not (Files/exists root-path no-follow))))
      (finally
        (fs/delete-recursively! base base)))))

(deftest ^{:seon/tasks #{'seon.fs/implement-a-path-under-a-root-answers-true}}
    under-path-tells-what-lies-under-a-root
  (doseq [[ancestor descendant expected] [["/" "/x" true]
                                          ["/a/b/" "/a/b/c" true]
                                          ["/a/b" "/a/b/c" true]
                                          ["/a/b" "/a/b" true]
                                          ["/a/b" "/a/bc" false]
                                          ["/a/b" "/a" false]]]
    (testing (str (pr-str descendant) " under " (pr-str ancestor))
      (is (= expected (#'seon.fs/under-path? ancestor descendant))))))

(def ^:private segment-generator
  (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcxyz019")) 1 4)))

(deftest ^{:seon/tasks #{'seon.fs/implement-a-path-under-a-root-answers-true}}
    a-path-lies-under-exactly-the-roots-it-extends-by-a-component
  (test-support/assert-check!
   (tc/quick-check
    200
    (prop/for-all [root-components (gen/vector segment-generator 1 4)
                   segment segment-generator]
      (let [root (apply str (map #(str "/" %) root-components))]
        (and (true? (#'seon.fs/under-path? root (str root "/" segment)))
             (false? (#'seon.fs/under-path? root (str root segment "x"))))))
    :seed 2026101301)))

(deftest ^{:seon/tasks #{'seon.fs/implement-an-equal-root-and-target-have-no-intermediate-paths}}
    intermediate-paths-lists-the-roots-from-root-to-target-exclusive
  (doseq [[root target expected] [["/r" "/r" []]
                                  ["/r" "/r/a" ["/r"]]
                                  ["/r" "/r/a/b" ["/r" "/r/a"]]
                                  ["/r/" "/r/a/b" ["/r" "/r/a"]]]]
    (testing (str (pr-str target) " under " (pr-str root))
      (is (= expected
             (mapv #(.toString ^java.nio.file.Path %)
                   (#'seon.fs/intermediate-paths (java.nio.file.Paths/get root (into-array String []))
                                                 (java.nio.file.Paths/get target (into-array String [])))))))))
