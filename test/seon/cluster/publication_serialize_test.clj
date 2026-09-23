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
