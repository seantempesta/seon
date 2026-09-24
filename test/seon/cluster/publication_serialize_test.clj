(ns seon.cluster.publication-serialize-test
  "A publication publishes the paths it names from the bytes it loads, or refuses."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.fs]
            [seon.test-support :as support]))

(deftest adoption-refuses-a-namespace-whose-disk-bytes-were-never-published
  (let [database (db/db (:seon.db/connection (support/execution-handle nil)))
        directory (io/file "tmp" (str "publication-serialize-" (random-uuid)))
        file (io/file directory "src/seon/id.clj")]
    (try
      (io/make-parents file)
      (spit file "(ns seon.id)\n;; another lane's unpublished edit\n")
      (let [refusal (try (#'cluster/verify-development-sources!
                          database (.getPath directory) #{'seon.id})
                         (catch clojure.lang.ExceptionInfo refused refused))]
        (is (= "Source changed during development adoption." (ex-message refusal)))
        (is (= ["src/seon/id.clj"] (get-in (ex-data refusal) [:seon.boot/offense :seon.source/changed-paths]))))
      (finally (.delete file) (.delete (.getParentFile file))
               (.delete (.getParentFile (.getParentFile file))) (.delete directory)))))

(deftest a-changed-path-outside-the-source-directory-refuses-by-name
  (let [outside (.getCanonicalPath (io/file (System/getProperty "java.io.tmpdir") "seon-elsewhere.clj"))
        refusal (try (cluster/refresh-source! "tmp/publication-serialize-unused-root" [outside])
                     (catch clojure.lang.ExceptionInfo refused refused))]
    (is (= "Changed paths lie outside the JVM's source directory." (ex-message refusal)))
    (is (= {:seon.fn/root (seon.fs/source-directory) :seon.source/changed-paths [outside]}
           (select-keys (:seon.boot/offense (ex-data refusal))
                        [:seon.fn/root :seon.source/changed-paths])))))

(deftest a-linked-gitlink-publishes-by-its-pin-while-an-escaping-file-refuses
  ;; A committed archive links each gitlink to a checkout outside it.
  (let [base (.getCanonicalFile (io/file "tmp" (str "publication-gitlink-" (random-uuid))))
        directory (io/file base "archive")
        elsewhere (io/file base "elsewhere")
        link! (fn [path target]
                (io/make-parents (io/file directory path))
                (java.nio.file.Files/createSymbolicLink
                 (.toPath (io/file directory path)) (.toPath target)
                 (make-array java.nio.file.attribute.FileAttribute 0)))
        paths #(try (#'cluster/publication-paths (.getPath directory) %)
                    (catch clojure.lang.ExceptionInfo refused refused))]
    (try
      (.mkdirs (io/file elsewhere "dep"))
      (spit (io/file elsewhere "escape.clj") "(ns escape)\n")
      (io/make-parents (io/file directory "src/seon/id.clj"))
      (spit (io/file directory "src/seon/id.clj") "(ns seon.id)\n")
      (spit (io/file directory "dependency-pins.txt")
            "160000 0fb349c414e717800be775ba9cb77c95a9eb700d 0\treference-code/dep\n")
      (link! "reference-code/dep" (io/file elsewhere "dep"))
      (link! "src/escape.clj" (io/file elsewhere "escape.clj"))
      (is (= ["reference-code/dep" "src/seon/id.clj"]
             (paths ["reference-code/dep" "src/seon/id.clj"])))
      (let [refusal (paths ["reference-code/dep" "src/escape.clj" "../elsewhere/escape.clj"])]
        (is (= "Changed paths lie outside the JVM's source directory." (ex-message refusal)))
        (is (= ["src/escape.clj" "../elsewhere/escape.clj"]
               (get-in (ex-data refusal) [:seon.boot/offense :seon.source/changed-paths]))))
      (finally
        ;; Links first, so the recursive deletion never follows one.
        (doseq [path ["reference-code/dep" "src/escape.clj"]]
          (.delete (io/file directory path)))
        (doseq [file (reverse (file-seq base))] (.delete ^java.io.File file))))))
