(ns ^{:seon.test/platform
       "Moving part: the one no-follow recursive deletion owner."}
    seon.fs-test
  "Recurring proof for the one recursive filesystem deletion owner."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.fs :as fs])
  (:import [java.nio.file Files LinkOption Path]
           [java.util.concurrent CountDownLatch TimeUnit]))

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
  (let [base (str "tmp/fs-concurrent-test/" (random-uuid))
        root (io/file base "owned")
        child (io/file root "child")
        child-path (.normalize (.toAbsolutePath (.toPath child)))
        first-thread (atom nil)
        child-observed (CountDownLatch. 1)
        release-first (CountDownLatch. 1)
        original-attributes @#'seon.fs/attributes]
    (try
      (.mkdirs child)
      (with-redefs-fn
        {#'seon.fs/attributes
         (fn [^Path path]
           (let [attribute (original-attributes path)]
             (when (and attribute
                        (identical? (Thread/currentThread) @first-thread)
                        (= child-path path))
               (.countDown child-observed)
               (when-not (.await release-first 10 TimeUnit/SECONDS)
                 (throw (ex-info "The competing deletion did not finish."
                                 {:seon.fs/path (str path)}))))
             attribute))}
        (fn []
          (let [first-deletion
                (future
                  (reset! first-thread (Thread/currentThread))
                  (try
                    (fs/delete-recursively! base (.getPath root))
                    :deleted
                    (catch Throwable failure failure)))]
            (is (.await child-observed 10 TimeUnit/SECONDS)
                "the first walk must observe the child before it vanishes")
            (let [second-deletion
                  (future
                    (try
                      (fs/delete-recursively! base (.getPath root))
                      :deleted
                      (catch Throwable failure failure)))
                  second-result
                  (deref second-deletion 10000 ::deletion-backstop)]
              (.countDown release-first)
              (is (= :deleted second-result)
                  (pr-str second-result))
              (let [first-result
                    (deref first-deletion 10000 ::deletion-backstop)]
                (is (= :deleted first-result)
                    (pr-str first-result)))))))
      (finally
        (.countDown release-first)
        (fs/delete-recursively! base base)))))
