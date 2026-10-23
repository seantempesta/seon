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
        path (str co "/target/runs/test-store-" pdb/schema-digest)]
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
               (ts/drop-main! store main)))))

(deftest a-kept-store-is-keyed-by-its-schema
  ;; task seon.test-store/a-kept-store-is-keyed-by-its-schema: the page merge changed three
  ;; attributes to refs, and every checkout's kept program store and process store refused to open
  ;; (:seon.db/types-differ): bin/test failed at its plan, process tests at root start. A kept store
  ;; of output is named by the schema's digest; one made with another schema is removed, and the
  ;; new path is made fresh, nothing migrated or refused
  (let [dir (str (java.io.File. (System/getProperty "java.io.tmpdir") (str "seon-kept-" (random-uuid))))
        base (str dir "/test-store")
        old (str base "-00000000")
        runs (str base "-" pdb/schema-digest "-some-main-runs")]
    (try
      (.mkdirs (java.io.File. dir))
      ;; a store made under another schema's digest (what it holds is not read: its name says it)
      (let [cfg (pdb/store-cfg :lmdb old)]
        (d/create-database cfg)
        (let [c (d/connect cfg)]
          (d/transact c {:tx-data [{:db/ident :task/name :db/valueType :db.type/long :db/cardinality :db.cardinality/one}]})
          (d/release c)))
      (.mkdirs (java.io.File. base))
      (.mkdirs (java.io.File. runs))
      (let [path (ts/kept-store! base)]
        (is (= (str base "-" pdb/schema-digest) path) "named by this schema's digest")
        (is (not (.exists (java.io.File. old))) "the store of another schema is removed")
        (is (not (.exists (java.io.File. base))) "and the unkeyed one")
        (is (.exists (java.io.File. runs)) "a runs directory beside them is no store, and stays")
        (let [{:keys [conn seeded?]} (pdb/open! :lmdb path 0 :db)]
          (is seeded? "the new path is made fresh")
          (d/release conn)))
      (finally (sh/sh "rm" "-rf" dir)))))
