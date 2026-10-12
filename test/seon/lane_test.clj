(ns seon.lane-test
  "bin/lane against a scratch repository holding a copy of the script."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.operator :as operator]))

(defn- sh [dir & args]
  (apply shell/sh (concat args [:dir (str dir)])))

(defn- scratch
  "A git repository with bin/lane, a committed file a.txt and dirty b.txt."
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "lane-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (io/make-parents (io/file dir "bin/lane"))
    (spit (io/file dir "bin/lane") (slurp "bin/lane"))
    (.setExecutable (io/file dir "bin/lane") true)
    (spit (io/file dir "a.txt") "a")
    (.mkdirs (io/file dir "d"))
    (sh dir "git" "init" "-q")
    (sh dir "git" "add" "a.txt")
    (sh dir "git" "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "init")
    (spit (io/file dir "b.txt") "b")
    dir))

(defn- lane [dir & args] (apply sh dir "bin/lane" args))

(deftest claim-prints-only-its-own-paths-and-status-counts
  (let [dir (scratch)]
    (lane dir "claim" "one" "a.txt")
    (lane dir "claim" "one" "d/")
    (is (= "lane one claims: d/\n" (:out (lane dir "claim" "one" "d/"))))
    (is (= "one (since " (subs (:out (lane dir "status")) 0 11)))
    (is (str/ends-with? (str/trim (:out (lane dir "status"))) "2 paths"))
    (is (= "a.txt\nd/\n" (:out (lane dir "status" "one"))))))

(deftest release-drops-only-the-named-paths
  (let [dir (scratch)]
    (lane dir "claim" "one" "a.txt" "d/")
    (is (zero? (:exit (lane dir "release" "one" "a.txt"))))
    (is (= "d/\n" (:out (lane dir "status" "one"))))
    (is (= 2 (:exit (lane dir "release" "one" "zzz"))))
    (lane dir "release" "one")
    (is (str/includes? (:out (lane dir "status" "one")) "holds no claims"))))

(deftest the-last-holder-may-reclaim-a-dirty-file-and-others-may-not
  (let [dir (scratch)]
    (is (zero? (:exit (lane dir "claim" "one" "b.txt"))) "an untracked file nobody holds is claimable")
    (spit (io/file dir "a.txt") "changed")
    (is (= 2 (:exit (lane dir "claim" "one" "a.txt"))) "nobody's dirty tracked file is refused")
    (is (zero? (:exit (let [r (sh dir "env" "LANE_ADOPT=1" "bin/lane" "claim" "one" "a.txt")] r))))
    (lane dir "release" "one" "a.txt")
    (is (= 2 (:exit (lane dir "claim" "two" "a.txt"))))
    (is (zero? (:exit (lane dir "claim" "one" "a.txt"))))))

(deftest claim-refuses-malformed-path-arguments
  (let [dir (scratch)]
    (is (= 2 (:exit (lane dir "claim" "one" "a.txt b.txt"))))
    (is (= 2 (:exit (lane dir "claim" "one" "a.txt\nd/"))))
    (is (= 2 (:exit (lane dir "claim" "one" "nodir/missing.txt"))))
    (is (zero? (:exit (lane dir "claim" "one" "d/" "d/new.txt"))) "a new file under a claimed directory")))

(deftest a-test-list-after-one-test-flag-is-refused
  (let [dir (scratch)
        parse #(try (operator/parse-argv ["--seon-root" (str dir) "check" "--test" "ns.a" "ns.b"])
                    (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    (lane dir "claim" "one" "a.txt")
    (let [refused (lane dir "commit" "one" "-m" "x" "--test" "ns.a" "ns.b" "ns.c" "a.txt")]
      (is (= 2 (:exit refused)))
      (is (str/includes? (:err refused) "each test needs its own --test")))
    (is (str/includes? (str (parse)) "each test needs its own --test"))))

(defn- kondo-scratch
  "A repository whose HEAD holds namespace a.core and its caller b.core."
  []
  (let [dir (scratch)
        put (fn [path text] (io/make-parents (io/file dir path)) (spit (io/file dir path) text))]
    (put ".clj-kondo/config.edn" "{}")
    (put "script/s.clj" "(ns s)\n")
    (put "test/t.clj" "(ns t)\n")
    (put "src/a/core.clj" "(ns a.core)\n(defn f [x] x)\n")
    (put "src/b/core.clj" "(ns b.core (:require [a.core :as a]))\n(defn g [] (a/f 1))\n")
    (sh dir "git" "add" ".clj-kondo" "src" "script" "test")
    (sh dir "git" "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "code")
    dir))

(defn- candidate-lint
  "The narrow lint's output for a.core rewritten to TEXT."
  [dir text]
  (spit (io/file dir "src/a/core.clj") text)
  (sh dir "git" "add" "src/a/core.clj")
  (let [tree (str/trim (:out (sh dir "git" "write-tree")))]
    (:out (sh dir "bash" "-c" (str "bin/lane _lint-candidate " tree " " dir "/lint src/a/core.clj 2>&1")))))

(deftest a-body-change-lints-only-its-file-and-an-arity-change-lints-its-callers
  (let [dir (kondo-scratch)
        body (candidate-lint dir "(ns a.core)\n(defn f [x] (do 1 x))\n")
        arity (candidate-lint dir "(ns a.core)\n(defn f [x y] x)\n")]
    (is (str/includes? body "callers not linted"))
    (is (str/includes? body "errors: 0"))
    (is (str/includes? arity "src/b/core.clj:2"))
    (is (str/includes? arity "is called with 1 arg but expects 2"))))

(deftest a-cljc-file-is-branch-evidence-eligible
  (let [dir (scratch)
        eligible #(:exit (lane dir "_evidence-paths" %))]
    (is (zero? (eligible "src/seon/print.cljc")))
    (is (zero? (eligible "test/seon/print_test.cljc")))
    (is (zero? (eligible "src/my/program.clj")))
    (is (= 1 (eligible "resources/seon/x.edn")) "a non-program path still takes the throwaway check")))

(deftest a-fallback-check-names-its-tests
  (let [dir (scratch)
        put (fn [path text] (io/make-parents (io/file dir path)) (spit (io/file dir path) text))]
    (put ".clj-kondo/config.edn" "{}")
    (put "src/x/core.cljc" "(ns x.core)\n")
    (sh dir "git" "add" ".clj-kondo")
    (sh dir "git" "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "k")
    (sh dir "env" "LANE_ADOPT=1" "bin/lane" "claim" "one" "src/x/core.cljc")
    (let [refused (lane dir "commit" "one" "-m" "x" "--branch-evidence" "nobody" "src/x/core.cljc")]
      (is (= 2 (:exit refused)))
      (is (str/includes? (:err refused) "names no test: pass each reaching test as --test NS/VAR")))))

(deftest a-reference-code-gitlink-commits-only-inside-a-restart-batch
  (let [dir (scratch)
        sha "1111111111111111111111111111111111111111"]
    (sh dir "git" "update-index" "--add" "--cacheinfo" (str "160000," sha ",reference-code/dep"))
    (sh dir "git" "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "gitlink")
    (.mkdirs (io/file dir "reference-code/dep"))
    (is (zero? (:exit (lane dir "claim" "one" "reference-code/dep"))))
    (let [refused (lane dir "commit" "one" "-m" "bump" "reference-code/dep")
          batch (lane dir "commit" "one" "-m" "bump" "--restart-batch" "reference-code/dep")]
      (is (= 2 (:exit refused)))
      (is (str/includes? (:err refused) "restart batch"))
      (is (str/includes? (:err batch) "names no test") "with the flag it reaches the normal check"))))
