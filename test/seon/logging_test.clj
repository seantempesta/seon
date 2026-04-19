(ns seon.logging-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.logging :as logging])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture — isolated tmp directory per test
;;; ---------------------------------------------------------------------------

(def ^:private test-base-dir "tmp/logging-test")

(defn- fresh-test-dir
  "Create a unique test directory under tmp/logging-test/."
  []
  (let [dir (io/file test-base-dir (str (System/nanoTime)))]
    (.mkdirs dir)
    (.getPath dir)))

(defn- delete-tree!
  "Recursively delete a directory tree."
  [^File f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-tree! child)))
  (.delete f))

(use-fixtures :each
  (fn [test-fn]
    (let [dir (io/file test-base-dir)]
      (.mkdirs dir))
    (test-fn)
    ;; Clean up after each test
    (delete-tree! (io/file test-base-dir))))

;;; ---------------------------------------------------------------------------
;;; rotate-files! (tested via write-line! behavior)
;;; ---------------------------------------------------------------------------

(deftest rotation-shifts-files-test
  (testing "files shift correctly: .log -> .1 -> .2 -> .3"
    (let [dir   (fresh-test-dir)
          path  (str dir "/app.log")
          ;; Use tiny max-size so each write triggers rotation
          writer (logging/create-rotating-writer
                   {:path path :max-size 10 :max-backlog 3})]
      ;; Write enough to trigger multiple rotations
      (logging/write-line! writer "AAAAAAAAAAAAA")  ;; > 10 bytes, into .log
      ;; .log now has content. Next write triggers rotation.
      (logging/write-line! writer "BBBBBBBBBBBBB")  ;; rotates A to .1, writes B
      (logging/write-line! writer "CCCCCCCCCCCCC")  ;; rotates B to .1, A to .2, writes C
      (logging/write-line! writer "DDDDDDDDDDDDD")  ;; rotates C to .1, B to .2, A to .3, writes D

      (is (.exists (io/file path)) "Current log file exists")
      (is (.exists (io/file (str path ".1"))) "First rotated backup exists")
      (is (.exists (io/file (str path ".2"))) "Second rotated backup exists")
      (is (.exists (io/file (str path ".3"))) "Third rotated backup exists")

      ;; Current file has the latest content
      (is (str/includes? (slurp path) "DDDDD"))
      ;; .1 has previous content
      (is (str/includes? (slurp (str path ".1")) "CCCCC"))
      ;; .2 has older content
      (is (str/includes? (slurp (str path ".2")) "BBBBB"))
      ;; .3 has oldest content
      (is (str/includes? (slurp (str path ".3")) "AAAAA")))))

(deftest rotation-deletes-oldest-beyond-max-backlog-test
  (testing "oldest file beyond max-backlog gets deleted"
    (let [dir    (fresh-test-dir)
          path   (str dir "/app.log")
          writer (logging/create-rotating-writer
                   {:path path :max-size 10 :max-backlog 2})]
      ;; Write enough to push beyond backlog of 2
      (logging/write-line! writer "AAAAAAAAAAAAA")
      (logging/write-line! writer "BBBBBBBBBBBBB")
      (logging/write-line! writer "CCCCCCCCCCCCC")
      (logging/write-line! writer "DDDDDDDDDDDDD")

      (is (.exists (io/file path)))
      (is (.exists (io/file (str path ".1"))))
      (is (.exists (io/file (str path ".2"))))
      ;; .3 should NOT exist — max-backlog is 2
      (is (not (.exists (io/file (str path ".3"))))
          "File beyond max-backlog should be deleted"))))

(deftest rotation-handles-missing-intermediates-test
  (testing "rotation works when some rotated files don't exist yet"
    (let [dir    (fresh-test-dir)
          path   (str dir "/app.log")
          writer (logging/create-rotating-writer
                   {:path path :max-size 10 :max-backlog 5})]
      ;; First rotation: only .log exists, no .1-.5
      (logging/write-line! writer "AAAAAAAAAAAAA")
      (logging/write-line! writer "BBBBBBBBBBBBB")

      (is (.exists (io/file path)))
      (is (.exists (io/file (str path ".1"))))
      ;; .2 through .5 don't exist yet — that's fine
      (is (not (.exists (io/file (str path ".2"))))))))

;;; ---------------------------------------------------------------------------
;;; write-line!
;;; ---------------------------------------------------------------------------

(deftest write-line-basic-test
  (testing "writes lines to the file"
    (let [dir    (fresh-test-dir)
          path   (str dir "/test.log")
          writer (logging/create-rotating-writer
                   {:path path :max-size (* 1024 1024) :max-backlog 3})]
      (logging/write-line! writer "hello world")
      (logging/write-line! writer "second line")

      (let [content (slurp path)]
        (is (str/includes? content "hello world"))
        (is (str/includes? content "second line"))))))

(deftest write-line-triggers-rotation-test
  (testing "triggers rotation when file exceeds max-size"
    (let [dir    (fresh-test-dir)
          path   (str dir "/test.log")
          writer (logging/create-rotating-writer
                   {:path path :max-size 50 :max-backlog 3})]
      ;; Write enough to exceed 50 bytes
      (dotimes [i 5]
        (logging/write-line! writer (str "line-" i "-padding-to-make-it-long-enough")))

      ;; After rotation, original file should be small (just the last write)
      (is (< (.length (io/file path)) 100)
          "After rotation, current file should be small")
      ;; At least one rotated file should exist
      (is (.exists (io/file (str path ".1")))
          "Rotated backup should exist"))))

(deftest write-line-creates-parent-dirs-test
  (testing "creates parent directories if they don't exist"
    (let [dir    (fresh-test-dir)
          path   (str dir "/deep/nested/dir/test.log")
          writer (logging/create-rotating-writer
                   {:path path :max-size (* 1024 1024) :max-backlog 3})]
      (logging/write-line! writer "hello")
      (is (.exists (io/file path))))))

(deftest write-line-truncates-on-failed-rotation-test
  (testing "file gets truncated rather than growing unbounded when rotation fails"
    (let [dir    (fresh-test-dir)
          path   (str dir "/test.log")
          writer (logging/create-rotating-writer
                   {:path path :max-size 50 :max-backlog 3})]
      ;; Fill the file past max-size
      (dotimes [_ 10]
        (logging/write-line! writer "padding-content-to-fill-file"))
      ;; The file should never grow unbounded — either rotation succeeded
      ;; and the file is small, or truncation kicked in
      (let [size (.length (io/file path))]
        (is (< size 200)
            (str "File should not grow unbounded, was " size " bytes"))))))

(deftest write-line-thread-safety-test
  (testing "multiple threads writing concurrently don't corrupt"
    (let [dir    (fresh-test-dir)
          path   (str dir "/concurrent.log")
          writer (logging/create-rotating-writer
                   {:path path :max-size (* 10 1024) :max-backlog 3})
          n-threads 8
          n-lines   50
          latch     (java.util.concurrent.CountDownLatch. n-threads)]
      ;; Launch threads that all write simultaneously
      (dotimes [t n-threads]
        (.start
          (Thread.
            (fn []
              (try
                (dotimes [i n-lines]
                  (logging/write-line! writer (str "thread-" t "-line-" i)))
                (finally
                  (.countDown latch)))))))
      (.await latch 10 java.util.concurrent.TimeUnit/SECONDS)

      ;; Count total lines across all files (current + rotated)
      (let [all-files (cons (io/file path)
                            (for [i (range 1 4)
                                  :let [f (io/file (str path "." i))]
                                  :when (.exists f)]
                              f))
            total-lines (reduce (fn [acc ^File f]
                                  (+ acc (count (str/split-lines (slurp f)))))
                                0
                                (filter #(.exists ^File %) all-files))]
        (is (= (* n-threads n-lines) total-lines)
            (str "Expected " (* n-threads n-lines) " lines, got " total-lines))))))

;;; ---------------------------------------------------------------------------
;;; rotating-appender
;;; ---------------------------------------------------------------------------

(deftest rotating-appender-returns-valid-map-test
  (testing "returns a valid Timbre appender map"
    (let [appender (logging/rotating-appender {:fname "tmp/test-appender.log"})]
      (is (map? appender))
      (is (true? (:enabled? appender)))
      (is (fn? (:fn appender))))))

(deftest rotating-appender-rotates-test
  (testing "actually rotates when file exceeds max-size"
    (let [dir  (fresh-test-dir)
          path (str dir "/appender.log")
          appender (logging/rotating-appender
                     {:fname path :max-size 100 :max-backlog 3})
          appender-fn (:fn appender)]
      ;; Simulate Timbre calls — appender :fn receives a map with :output_
      (dotimes [i 20]
        (appender-fn {:output_ (delay (str "log-message-" i "-with-padding-to-exceed-limit"))}))

      ;; After enough writes, rotation should have happened
      (is (.exists (io/file path)))
      (is (.exists (io/file (str path ".1")))
          "Rotated file should exist after exceeding max-size"))))

(deftest rotating-appender-truncates-on-failed-rotation-test
  (testing "truncates to prevent unbounded growth"
    (let [dir  (fresh-test-dir)
          path (str dir "/appender-trunc.log")
          appender (logging/rotating-appender
                     {:fname path :max-size 100 :max-backlog 2})
          appender-fn (:fn appender)]
      ;; Write many messages to force multiple rotations
      (dotimes [i 50]
        (appender-fn {:output_ (delay (str "message-" i "-padding-content-here"))}))

      ;; Current file should always be bounded
      (is (< (.length (io/file path)) 500)
          "Current log file should not grow unbounded"))))

;;; ---------------------------------------------------------------------------
;;; cleanup-old-logs!
;;; ---------------------------------------------------------------------------

(deftest cleanup-protocol-captures-test
  (testing "deletes protocol-capture-*.jsonl files older than 7 days"
    (let [dir (fresh-test-dir)
          old-file (io/file dir "protocol-capture-abc.jsonl")
          new-file (io/file dir "protocol-capture-def.jsonl")]
      (spit old-file "old data")
      (spit new-file "new data")
      ;; Make old-file appear 10 days old
      (.setLastModified old-file (- (System/currentTimeMillis) (* 10 86400000)))
      ;; Make new-file appear 1 day old
      (.setLastModified new-file (- (System/currentTimeMillis) (* 1 86400000)))

      (let [result (logging/cleanup-old-logs! dir)]
        (is (not (.exists old-file)) "Old protocol capture should be deleted")
        (is (.exists new-file) "Recent protocol capture should be kept")
        (is (= 1 (::logging/files-deleted result)))
        (is (pos? (::logging/bytes-freed result)))))))

(deftest cleanup-agent-logs-test
  (testing "deletes agent logs older than 7 days"
    (let [dir        (fresh-test-dir)
          agents-dir (io/file dir "agents")]
      (.mkdirs agents-dir)
      (let [old-log (io/file agents-dir "agent-abc.log")
            new-log (io/file agents-dir "agent-def.log")]
        (spit old-log "old agent output")
        (spit new-log "new agent output")
        (.setLastModified old-log (- (System/currentTimeMillis) (* 10 86400000)))
        (.setLastModified new-log (- (System/currentTimeMillis) (* 1 86400000)))

        (let [result (logging/cleanup-old-logs! dir)]
          (is (not (.exists old-log)) "Old agent log should be deleted")
          (is (.exists new-log) "Recent agent log should be kept")
          (is (= 1 (::logging/files-deleted result))))))))

(deftest cleanup-xtdb-files-test
  (testing "deletes all xtdb.* files regardless of age"
    (let [dir (fresh-test-dir)]
      (spit (io/file dir "xtdb.log") "stale xtdb log")
      (spit (io/file dir "xtdb.data") "stale xtdb data")
      ;; Make them recent — should still be deleted
      (.setLastModified (io/file dir "xtdb.log")
                        (System/currentTimeMillis))
      (.setLastModified (io/file dir "xtdb.data")
                        (System/currentTimeMillis))

      (let [result (logging/cleanup-old-logs! dir)]
        (is (not (.exists (io/file dir "xtdb.log"))))
        (is (not (.exists (io/file dir "xtdb.data"))))
        (is (= 2 (::logging/files-deleted result)))))))

(deftest cleanup-hook-debug-truncates-large-test
  (testing "truncates hook-debug.log when > 10MB"
    (let [dir  (fresh-test-dir)
          hook (io/file dir "hook-debug.log")]
      ;; Create a file just over 10MB
      (let [chunk (apply str (repeat 1024 "x"))  ;; 1KB
            sb (StringBuilder.)]
        (dotimes [_ (* 11 1024)]  ;; 11MB worth
          (.append sb chunk))
        (spit hook (str sb)))

      (is (> (.length hook) (* 10 1024 1024))
          "File should be > 10MB before cleanup")

      (let [result (logging/cleanup-old-logs! dir)]
        ;; File should still exist but be empty
        (is (.exists hook))
        (is (< (.length hook) 100)
            "hook-debug.log should be truncated")
        (is (= 1 (::logging/files-deleted result)))
        (is (pos? (::logging/bytes-freed result)))))))

(deftest cleanup-hook-debug-leaves-small-test
  (testing "leaves hook-debug.log alone when < 10MB"
    (let [dir  (fresh-test-dir)
          hook (io/file dir "hook-debug.log")]
      (spit hook "small debug content")

      (let [result (logging/cleanup-old-logs! dir)]
        (is (.exists hook))
        (is (= "small debug content" (slurp hook))
            "Small hook-debug.log should not be modified")
        (is (= 0 (::logging/files-deleted result)))))))

(deftest cleanup-handles-missing-directories-test
  (testing "handles missing directories gracefully"
    (let [result (logging/cleanup-old-logs! "tmp/logging-test/nonexistent-dir-12345")]
      (is (= 0 (::logging/files-deleted result)))
      (is (= 0 (::logging/bytes-freed result))))))

(deftest cleanup-returns-correct-counts-test
  (testing "returns correct accumulated counts"
    (let [dir        (fresh-test-dir)
          agents-dir (io/file dir "agents")]
      (.mkdirs agents-dir)
      ;; Create multiple deletable files
      (let [files [(io/file dir "protocol-capture-1.jsonl")
                   (io/file dir "protocol-capture-2.jsonl")
                   (io/file dir "xtdb.old")
                   (io/file agents-dir "old-agent.log")]]
        (doseq [^File f files]
          (spit f (apply str (repeat 100 "x"))))
        ;; Make protocol captures and agent log old
        (.setLastModified (first files) (- (System/currentTimeMillis) (* 10 86400000)))
        (.setLastModified (second files) (- (System/currentTimeMillis) (* 10 86400000)))
        (.setLastModified (last files) (- (System/currentTimeMillis) (* 10 86400000)))

        (let [result (logging/cleanup-old-logs! dir)]
          (is (= 4 (::logging/files-deleted result))
              "Should delete 2 protocol captures + 1 xtdb + 1 agent log")
          (is (pos? (::logging/bytes-freed result))))))))
