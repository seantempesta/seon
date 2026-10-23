(ns seon.main-test
  "A root holds no program it was not given (FENCES #50): a fresh store, as seon.root/start!
  opens it by default, holds the schema and nothing else; root's warm-up needs no seed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [datahike.api :as d]
            [seon.db :as pdb] [seon.root :as root] [test-runner :as runner]))

(defn- program-datoms [db]
  (vec (mapcat #(d/datoms db :aevt %) [:ns/name :form/id :var/name :task/name])))

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

(deftest root-boots-without-test-fixtures
  ;; the compiler's facts of the checkout (src and test imported as program facts): the
  ;; release binary compiles what src requires, so no src namespace requires one whose file is
  ;; in test/ (the check driver, the e2e runner, the scripted model's fixtures: task
  ;; seon.main/drivers-leave-src); a scripted root loads its fixture from the checkout by name
  (let [db (runner/program-db (System/getProperty "user.dir"))
        file (into {} (d/q '[:find ?n ?f :where [?e :ns/name ?n] [?e :ns/file ?f]] db))
        in? (fn [dir n] (some-> (file n) (clojure.string/starts-with? dir)))
        reqs (d/q '[:find ?n ?r :where [?e :ns/name ?n] [?e :ns/form ?f] [?f :form/requires ?r]] db)]
    (is (in? "src/" 'seon.root) "the facts hold src's namespaces")
    (is (in? "test/" 'seon.fixtures) "and test's")
    (is (= #{} (set (for [[n r] reqs :when (and (in? "src/" n) (in? "test/" r))] [n r])))
        "no src namespace requires a test/ one")))
