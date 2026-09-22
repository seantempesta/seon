(ns seon.publication-digest-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.test-support :as support]))

(deftest issue-notes-do-not-identify-the-program
  (let [directory (io/file "tmp" (str "publication-digest-" (random-uuid)))
        write! (fn [path text]
                 (let [file (io/file directory path)]
                   (io/make-parents file)
                   (spit file text)))
        snapshot #(source/path-digests (.getCanonicalPath directory)
                                      (source/discover-paths (.getCanonicalPath directory) cluster/source-roots))]
    (try
      (write! "src/fixture.clj" "(ns fixture)\n(def value 1)\n")
      (write! "test/fixture_test.clj" "(ns fixture-test)\n")
      (write! "config/default.edn" "{}\n")
      (write! "docs/seon/issues/fixture.md" "# Before\n")
      (write! "deps.edn" "{:paths [\"src\" \"resources\"]}\n")
      (is (zero? (:exit (shell/sh "git" "init" "-q" :dir (str directory)))))
      (is (zero? (:exit (shell/sh "git" "add" "deps.edn" "config/default.edn" :dir (str directory)))))
      (let [before (snapshot)]
        (is (seq before))
        (write! "docs/seon/issues/fixture.md" "# After\n")
        (write! "src/README.md" "# Source documentation\n")
        (is (= before (snapshot)))
        (is (= before
               (snapshot)))
        (write! "deps.edn" "{:paths [\"src\" \"resources\"] :deps {example/lib {:mvn/version \"2\"}}}\n")
        (is (not= before (snapshot))
            "Changed dependency bytes differ from the prior file observation.")
        (write! "src/fixture.clj" "(ns fixture)\n(def value 2)\n")
        (is (not= before (snapshot))))
      (finally (support/delete-recursively! directory)))))
