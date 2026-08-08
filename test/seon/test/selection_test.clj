(ns ^{:seon.test/platform
       "Moving part: the gate's own changed-test selector."}
    seon.test.selection-test
  "The class regression for the default tier's selector.

  THE CLASS: a gate that runs only some tests silently skips a test that
  could have observed the change. The selector must therefore be exact in
  both directions — every reaching test present, every non-reaching test
  absent — and it must decide from recorded facts (`:seon.fn/calls` edges
  and content digests), never from a modification time, a filename, or a
  maintained list."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.test.selection :as selection])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private artifacts
  "A program graph in the exact shape `seon.fn/build-manifest` produces.

  `leaf` is called by `middle`; `middle` is called by `unrelated-caller`
  and by `middle-test`; `leaf-test` calls `leaf` directly; `far-test`
  reaches `leaf` only transitively through `middle`; `stranger-test`
  reaches neither."
  [{:seon.fn.file/path "src/example/leaf.clj"
    :seon.fn.file/rows
    [{:seon.ns/name 'example.leaf}
     {:seon.fn/sym "example.leaf/leaf"}]}
   {:seon.fn.file/path "src/example/middle.clj"
    :seon.fn.file/rows
    [{:seon.ns/name 'example.middle}
     {:seon.fn/sym "example.middle/middle"
      :seon.fn/calls [[:seon.fn/sym "example.leaf/leaf"]]}]}
   {:seon.fn.file/path "src/example/stranger.clj"
    :seon.fn.file/rows
    [{:seon.ns/name 'example.stranger}
     {:seon.fn/sym "example.stranger/stranger"}]}
   {:seon.fn.file/path "test/example/leaf_test.clj"
    :seon.fn.file/rows
    [{:seon.ns/name 'example.leaf-test}
     {:seon.test/sym "example.leaf-test/leaf-test"
      :seon.fn/calls [[:seon.fn/sym "example.leaf/leaf"]]}]}
   {:seon.fn.file/path "test/example/far_test.clj"
    :seon.fn.file/rows
    [{:seon.ns/name 'example.far-test}
     {:seon.test/sym "example.far-test/far-test"
      :seon.fn/calls [[:seon.fn/sym "example.middle/middle"]]}]}
   {:seon.fn.file/path "test/example/subject_test.clj"
    :seon.fn.file/rows
    [{:seon.ns/name 'example.subject-test}
     {:seon.test/sym "example.subject-test/subject-test"
      :seon.test/subject [:seon.fn/sym "example.middle/middle"]}]}
   {:seon.fn.file/path "test/example/stranger_test.clj"
    :seon.fn.file/rows
    [{:seon.ns/name 'example.stranger-test}
     {:seon.test/sym "example.stranger-test/stranger-test"
      :seon.fn/calls [[:seon.fn/sym "example.stranger/stranger"]]}]}])

(deftest a-changed-file-selects-exactly-the-tests-that-reach-it
  (testing "a directly called leaf selects its caller and every transitive one"
    (is (= ["example.far-test/far-test"
            "example.leaf-test/leaf-test"
            "example.subject-test/subject-test"]
           (selection/reaching-tests artifacts ["src/example/leaf.clj"]))
        "far-test reaches leaf only through middle; subject-test only through
         its declared subject; both must be selected"))

  (testing "an unrelated change selects only its own dependents"
    (is (= ["example.stranger-test/stranger-test"]
           (selection/reaching-tests artifacts ["src/example/stranger.clj"]))
        "the leaf tests must be ABSENT — a selector that returns everything
         is vacuously safe and defeats the tier"))

  (testing "an intermediate change selects its callers, not the leaf's other
            dependents"
    (is (= ["example.far-test/far-test"
            "example.subject-test/subject-test"]
           (selection/reaching-tests artifacts ["src/example/middle.clj"]))))

  (testing "a changed test file selects its own tests"
    (is (= ["example.leaf-test/leaf-test"]
           (selection/reaching-tests artifacts ["test/example/leaf_test.clj"]))))

  (testing "an unchanged tree selects nothing"
    (is (= [] (selection/reaching-tests artifacts [])))))

(deftest gate-inputs-no-call-edge-can-reach-widen
  (is (selection/widening-path? "resources/seon/schemas/seon.db.edn"))
  (is (selection/widening-path? "deps.edn"))
  (is (selection/widening-path? "bin/test"))
  (is (selection/widening-path? "config/default.edn"))
  (is (not (selection/widening-path? "src/seon/db.clj")))
  (is (not (selection/widening-path? "test/seon/db_test.clj")))
  (is (not (selection/widening-path? "resources-of-mine.edn"))
      "prefix matching is path-segment exact, never a string prefix"))

(deftest changed-inputs-are-decided-by-content-not-modification-time
  (let [root (.toFile (Files/createTempDirectory
                       (.toPath (io/file "tmp")) "selection-test"
                       (into-array FileAttribute [])))
        source (io/file root "src" "example")
        _ (.mkdirs source)
        file (io/file source "leaf.clj")]
    (try
      (spit file "(ns example.leaf)\n")
      (let [first-pass (selection/input-digests (.getPath root))]
        (is (contains? first-pass "src/example/leaf.clj"))

        (testing "rewriting identical bytes with a newer timestamp is no change"
          (spit file "(ns example.leaf)\n")
          (.setLastModified file (+ (System/currentTimeMillis) 60000))
          (is (= {:seon.test.selection/changed []
                  :seon.test.selection/removed []}
                 (selection/changed-inputs
                  first-pass (selection/input-digests (.getPath root))))))

        (testing "different bytes are exactly one changed path"
          (spit file "(ns example.leaf)\n(defn leaf [] 1)\n")
          (is (= {:seon.test.selection/changed ["src/example/leaf.clj"]
                  :seon.test.selection/removed []}
                 (selection/changed-inputs
                  first-pass (selection/input-digests (.getPath root))))))

        (testing "a deleted input is reported as removed"
          (.delete file)
          (is (= ["src/example/leaf.clj"]
                 (:seon.test.selection/removed
                  (selection/changed-inputs
                   first-pass (selection/input-digests (.getPath root))))))))
      (finally
        ((requiring-resolve 'seon.fs/delete-recursively!)
         (.getCanonicalPath (io/file "tmp"))
         (.getCanonicalPath root))))))

(deftest a-recorded-green-basis-round-trips
  (let [root (.toFile (Files/createTempDirectory
                       (.toPath (io/file "tmp")) "selection-basis"
                       (into-array FileAttribute [])))]
    (try
      (is (nil? (selection/read-basis (.getPath root)))
          "no basis is an honest nil, never an empty map that reads as green")
      (selection/write-basis! (.getPath root)
                              {:seon.test.basis/at "2026-08-07T00:00:00Z"
                               :seon.test.basis/mode "all"
                               :seon.test.basis/digests {"src/a.clj" "abc"}})
      (is (= {:seon.test.basis/at "2026-08-07T00:00:00Z"
              :seon.test.basis/mode "all"
              :seon.test.basis/digests {"src/a.clj" "abc"}}
             (selection/read-basis (.getPath root))))
      (finally
        ((requiring-resolve 'seon.fs/delete-recursively!)
         (.getCanonicalPath (io/file "tmp"))
         (.getCanonicalPath root))))))
