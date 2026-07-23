(ns seon.dev.test-roots-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.set :as set]
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
      (write-source! root "test/my/portable_test.cljc"
                     (str "(ns sample.portable-test\n"
                          "  (:require #?(:clj [clojure.test]\n"
                          "               :cljs [cljs.test])))\n"))
      (write-source! root "test/my/node_test.cljc"
                     (str "(ns sample.node-test\n"
                          "  (:require [\"node:fs\" :as fs]\n"
                          "            [cljs.test]))\n"))
      (is (= ['sample.alpha-test 'sample.beta-test]
             (roots/operator-test-namespaces (str root))))
      (is (= ['sample.embed-writer-test 'sample.portable-test
              'sample.unretained-test 'sample.writer-test]
             (roots/writer-test-namespaces (str root))))
      (is (= #{"beta_test.cljc" "node_test.cljc" "portable_test.cljc"}
             (into #{} (map #(str (fs/file-name %)))
                   (roots/cljs-test-files (str root)))))
      (finally (fs/delete-tree root {:force true})))))

(defn- canonical-paths [files]
  (into #{} (map #(.getCanonicalPath (io/file %))) files))

(deftest every-retained-test-file-is-visible-to-a-runner
  (let [root "."
        candidates (canonical-paths (roots/test-files root))
        discovered (set/union
                    (canonical-paths (roots/operator-test-files root))
                    (canonical-paths (roots/writer-test-files root))
                    (canonical-paths (roots/cljs-test-files root)))
        orphans (sort (set/difference candidates discovered))]
    (is (empty? orphans)
        (str "Every test/**/*_test.{clj,cljc,cljs} file must be selected "
             "by the operator, writer, or CLJS runner; orphans: "
             (pr-str orphans)))))

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
