(ns seon.publication-digest-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.test-support :as support]))

(deftest issue-notes-do-not-identify-the-program
  (let [directory (io/file "tmp" (str "publication-digest-" (random-uuid)))
        write! (fn [path text]
                 (let [file (io/file directory path)]
                   (io/make-parents file)
                   (spit file text)))
        snapshot #(cluster/source-snapshot cluster/source-roots
                                            (.getCanonicalPath directory))]
    (try
      (write! "src/fixture.clj" "(ns fixture)\n(def value 1)\n")
      (write! "test/fixture_test.clj" "(ns fixture-test)\n")
      (write! "config/default.edn" "{}\n")
      (write! "docs/seon/issues/fixture.md" "# Before\n")
      (let [before (snapshot)]
        (is (seq (:seon.source/relative-file-digests before)))
        (write! "docs/seon/issues/fixture.md" "# After\n")
        (write! "src/README.md" "# Source documentation\n")
        (is (= (:seon.source/digest before) (:seon.source/digest (snapshot))))
        (is (= (:seon.source/relative-file-digests before)
               (:seon.source/relative-file-digests (snapshot))))
        (write! "src/fixture.clj" "(ns fixture)\n(def value 2)\n")
        (is (not= (:seon.source/digest before) (:seon.source/digest (snapshot)))))
      (finally (support/delete-recursively! directory)))))
