(ns seon.cluster.publication-serialize-test
  "One publication of the store at a time, and no unpublished bytes reloaded."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.db :as db]
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
