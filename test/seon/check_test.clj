(ns seon.check-test
  "bin/check's store (owner, 2026-10-21: one store, many branches, as in production): the run
  seeds one store (seon.check/seed); every step's root opens it on a main of its own, a
  branch of the seed (seon.db/open!, as seon.root/start! does), names its agents' branches
  under that main, and a recovery step reopens the same store on the same main."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [datahike.api :as d]
            [seon.db :as pdb]))

(defn- notes [db] (set (d/q '[:find [?t ...] :where [_ :note/text ?t]] db)))

(deftest steps-are-branches-of-one-store
  ;; a store on disk of this test's own, the last resort: what is proved is how bin/check
  ;; opens its LMDB store from separate roots and reopens it after a kill; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/check-test-" (random-uuid))
        seed (pdb/open! :lmdb path 300 :db)
        a (pdb/open! :lmdb path 300 :smoke-1)
        b (pdb/open! :lmdb path 300 :drain-1)]
    (try
      (testing "the run seeds the store once; each step's main is a new branch of it"
        (is (:seeded? seed))
        (is (not (:seeded? a)))
        (is (not (:seeded? b)))
        (is (not (:recovered? a)))
        (is (= [:smoke-1 :drain-1] (mapv (comp pdb/main-branch :cfg) [a b])))
        (is (= (get-in seed [:cfg :store :id]) (get-in a [:cfg :store :id]) (get-in b [:cfg :store :id])))
        (is (= (d/commit-id @(:conn seed)) (d/merge-base (:conn a) :smoke-1 :db) (d/merge-base (:conn b) :drain-1 :db)))
        (is (= (count (d/datoms @(:conn seed) :eavt)) (count (d/datoms @(:conn a) :eavt)))))
      (testing "a step's writes stay on its main"
        (d/transact (:conn a) [{:note/text "smoke's"}])
        (is (= #{"smoke's"} (notes @(:conn a))))
        (is (= #{} (notes @(:conn b))))
        (is (= #{} (notes @(:conn seed)))))
      (testing "agents' branches are named under each root's main, so steps never share one; on :db (production) as given"
        (is (= :smoke-1/agent-1 (pdb/on-main :smoke-1 :agent-1)))
        (is (= :smoke-1/agent-1 (pdb/on-main :smoke-1 :smoke-1/agent-1)))
        (is (= :smoke-1 (pdb/on-main :smoke-1 :db)))
        (is (not= (pdb/on-main :smoke-1 :agent-1) (pdb/on-main :drain-1 :agent-1)))
        (is (= :agent-1 (pdb/on-main :db :agent-1)))
        (d/branch! (:conn a) :smoke-1 :smoke-1/agent-1)
        (d/branch! (:conn b) :drain-1 :drain-1/agent-1)
        (is (= [:agent-1] (pdb/of-main :smoke-1 (d/branches (:conn a)))))
        (is (= [:agent-1] (pdb/of-main :drain-1 (d/branches (:conn a))))))
      (testing "a recovery step reopens the same store on the same main"
        (pdb/sync! (:cfg a))
        (run! #(d/release (:conn %)) [a b seed])
        (let [r (pdb/open! :lmdb path 300 :smoke-1)]
          (is (:recovered? r))
          (is (not (:seeded? r)))
          (is (= #{"smoke's"} (notes @(:conn r))))
          (d/release (:conn r))))
      (finally
        (clojure.java.shell/sh "rm" "-rf" path)))))
