(ns seon.dev.issues-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.issues :as issues]))

(defn- note! [root relative metadata title]
  (let [path (fs/path root "docs/seon/issues" relative)]
    (fs/create-dirs (fs/parent path))
    (spit (str path)
          (str "---\n"
               "type: issue\n"
               "status: " (:status metadata) "\n"
               "severity: " (:severity metadata) "\n"
               "tags: [issue, agent]\n"
               "---\n\n"
               "# " title "\n"))
    path))

(deftest validates-location-lifecycle-and-required-metadata
  (let [root (fs/create-temp-dir {:prefix "seon-issues-test-"})]
    (try
      (note! root "open.md" {:status "open" :severity "friction"} "Open")
      (note! root "archive/done.md"
             {:status "resolved" :severity "cleanup"}
             "Done")
      (is (= [] (issues/validation-errors (issues/notes root))))

      (note! root "archive/wrong.md"
             {:status "open" :severity "urgent"}
             "Wrong")
      (is (= #{:invalid-status
               :invalid-severity}
             (set (map :seon.dev.issues/problem
                       (issues/validation-errors (issues/notes root))))))
      (finally (fs/delete-tree root {:force true})))))

(deftest check-detects-drift-and-write-replaces-the-derived-index
  (let [root (fs/create-temp-dir {:prefix "seon-issues-index-test-"})
        index (fs/path root "docs/seon/issues/index.md")]
    (try
      (note! root "one.md" {:status "open" :severity "blocker"} "One")
      (fs/create-dirs (fs/parent index))
      (spit (str index) "stale\n")
      (testing "check mode never repairs drift"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Issue index is stale"
                              (issues/check! root)))
        (is (= "stale\n" (slurp (str index)))))
      (issues/write! root)
      (is (= true (:seon.dev.issues/clean? (issues/check! root))))
      (is (str/includes? (slurp (str index)) "[One](one.md)"))
      (finally (fs/delete-tree root {:force true})))))
