(ns seon.main-test
  "A root holds no program it was not given (FENCES #50): a fresh store, as seon.root/start!
  opens it by default, holds the schema and nothing else; root's warm-up needs no seed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [datahike.api :as d]
            [seon.db :as pdb] [seon.root :as root]))

(defn- program-datoms [db]
  (vec (mapcat #(d/datoms db :aevt %) [:ns/name :var/name :task/name])))

(defn- fresh-store
  "A fresh store on disk of this test's own, the last resort: what is proved is what a
  fresh store holds when a root creates it. Answers [path opened]."
  []
  (let [path (str (System/getProperty "user.dir") "/target/main-test-" (random-uuid))]
    [path (pdb/open! :lmdb path 0 :db)]))

(deftest a-fresh-root-holds-no-program-it-was-not-given
  (let [[path {:keys [conn seeded?]}] (fresh-store)]
    (try
      (is seeded?)
      (is (= [] (pdb/seed-tx 0)) "no example program unless one is asked for")
      (is (= [] (program-datoms @conn)))
      (is (contains? (d/schema @conn) :var/name) "the schema is there")
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest warm-needs-no-seed
  (let [[path {:keys [conn]}] (fresh-store)
        t (:max-tx @conn)]
    (try
      (is (= {:doc "warm" :task {:task/name :seon.warm/tidy :task/refs [{:var/name 'seon.warm/f}]}} (#'root/warm! conn))
          "its reads found its own definition and task")
      (is (= t (:max-tx @conn)) "warm-up commits nothing")
      (is (= [] (program-datoms @conn)))
      (is (nil? (find-ns 'seon.warm)) "its scratch namespace is gone")
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))
