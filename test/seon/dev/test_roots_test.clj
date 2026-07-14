(ns seon.dev.test-roots-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.test-roots :as roots]))

(defn- write-source! [root relative source]
  (let [path (fs/path root relative)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) source)
    path))

(deftest runner-roots-discover-new-tests-without-a-namespace-list
  (let [root (fs/create-temp-dir {:prefix "seon-test-roots-"})]
    (try
      (write-source! root "test/seon/dev/alpha_test.clj"
                     "(ns sample.alpha-test)\n")
      (write-source! root "test/seon/dev/nested/beta_test.cljc"
                     "(ns sample.beta-test)\n")
      (write-source! root "test/seon/dev/helper.clj"
                     "(ns sample.helper)\n")
      (write-source! root "test/seon/db/writer_test.clj"
                     "(ns sample.writer-test)\n")
      (write-source! root "test/seon/embed_writer_test.clj"
                     "(ns sample.embed-writer-test)\n")
      (write-source! root "test/seon/unretained_test.clj"
                     "(ns sample.unretained-test)\n")
      (is (= ['sample.alpha-test 'sample.beta-test]
             (roots/operator-test-namespaces (str root))))
      (is (= ['sample.embed-writer-test 'sample.writer-test]
             (roots/writer-test-namespaces (str root))))
      (finally (fs/delete-tree root {:force true})))))

(deftest duplicate-test-namespace-is-an-explicit-error
  (let [root (fs/create-temp-dir {:prefix "seon-test-roots-"})]
    (try
      (write-source! root "test/seon/dev/one_test.clj"
                     "(ns sample.duplicate-test)\n")
      (write-source! root "test/seon/dev/two_test.cljc"
                     "(ns sample.duplicate-test)\n")
      (testing "ambiguous ownership never silently picks one file"
        (is (thrown? Exception
                     (roots/operator-test-namespaces (str root)))))
      (finally (fs/delete-tree root {:force true})))))
