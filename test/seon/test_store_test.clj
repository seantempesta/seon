(ns seon.test-store-test
  "The test store's own rules: the process tests' store is made once, before any shard."
  (:require [clojure.test :refer [deftest is]] [clojure.java.shell :as sh] [datahike.api :as d]
            [seon.db :as pdb] [seon.test-store :as ts])
  (:import [clojure.lang ExceptionInfo]))

(deftest the-process-store-is-made-before-any-shard
  ;; task seon.test-store/the-process-store-is-made-before-any-shard: shards' roots that each made
  ;; target/runs/test-store when they found it missing raced (one opened the store another was
  ;; still creating: "Database does not exist", the first test of 2 of 4 shards red on a fresh
  ;; tree). bin/test makes it before any shard starts; a test asking for it where it is not
  ;; made is refused, naming who makes it, and never creates it
  (let [co (str (System/getProperty "java.io.tmpdir") "/seon-process-store-" (random-uuid))
        path (str co "/target/runs/test-store")]
    (try
      (let [e (is (thrown? ExceptionInfo (ts/process-store co)))]
        (is (= :seon.test-store/no-process-store (:seon.error/type (ex-data e))))
        (is (re-find #"bin/test makes it" (ex-message e))))
      (is (not (pdb/store? path)) "refused, it made nothing")
      (d/release (:conn (pdb/open! :lmdb path 0 :db)))
      (is (= path (ts/process-store co)) "made, it is the store")
      (finally (sh/sh "rm" "-rf" co)))))

(deftest a-test-root-is-its-own
  ;; task seon.test-store/a-test-root-is-its-own: root_test's root-answers gave every root on the
  ;; one test store the runs directory <store>-runs; bin/root start in a runs directory whose root
  ;; still runs only reports that root, so under parallel load one test asked another test's
  ;; root (main-follows-its-source-tree-across-restarts read the whole Seon program, :removed-forms 71).
  ;; A second root asked for in a runs directory a live root holds is refused, never answered
  (let [store (ts/process-store)
        runs (str (.getParent (java.io.File. ^String store)) "/own-root-" (random-uuid) "-runs")
        main (str "own-a-" (random-uuid))
        a (ts/start-root! {:store store :main main :runs runs})]
    (try
      (is (some? (:port a)) "the first root listens")
      (let [e (is (thrown? ExceptionInfo (ts/start-root! {:store store :main (str "own-b-" (random-uuid)) :runs runs})))]
        (is (= :seon.test-store/runs-in-use (:seon.error/type (ex-data e)))))
      (finally (ts/stop-root! a) (sh/sh "rm" "-rf" runs)
               ;; its main and its branches leave the one store (as root_test's drop-main!)
               (ts/binary-sh "./seon" "drop" main "--set" (str ":seon.config.root/store-path=" (pr-str store)) :dir "target")))))
