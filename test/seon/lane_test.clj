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
    (is (= 2 (:exit (lane dir "claim" "one" "b.txt"))) "nobody's dirty file is refused")
    (is (zero? (:exit (lane dir "claim" "one" "a.txt"))))
    (spit (io/file dir "a.txt") "changed")
    (lane dir "release" "one" "a.txt")
    (is (= 2 (:exit (lane dir "claim" "two" "a.txt"))))
    (is (zero? (:exit (lane dir "claim" "one" "a.txt"))))))

(deftest claim-refuses-malformed-path-arguments
  (let [dir (scratch)]
    (is (= 2 (:exit (lane dir "claim" "one" "a.txt b.txt"))))
    (is (= 2 (:exit (lane dir "claim" "one" "a.txt\nd/"))))
    (is (= 2 (:exit (lane dir "claim" "one" "missing.txt"))))
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
