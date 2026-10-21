(ns seon.db-test
  "One store, many branches, as in production (owner, 2026-10-21): bin/test seeds one test
  store and each test takes a branch of it through one helper (seon.db/test-branch!)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as pdb]))

(defn- notes [db] (set (d/q '[:find [?t ...] :where [_ :note/text ?t]] db)))

(deftest a-test-takes-a-branch-of-the-test-store
  (let [[cfg-a a] (pdb/test-branch!)
        [cfg-b b] (pdb/test-branch!)
        seed (d/connect (dissoc cfg-a :branch))]
    (testing "both are branches of one store, each named apart"
      (is (= (get-in cfg-a [:store :id]) (get-in cfg-b [:store :id])))
      (is (not= (:branch cfg-a) (:branch cfg-b)))
      (is (pdb/branch? seed (:branch cfg-a)))
      (is (pdb/branch? seed (:branch cfg-b))))
    (testing "each starts at the seed: its merge base with :db is the seed's commit, and it holds the example program"
      (is (= (d/commit-id @seed) (d/merge-base a (:branch cfg-a) :db) (d/merge-base b (:branch cfg-b) :db)))
      (is (= (count (d/datoms @seed :eavt)) (count (d/datoms @a :eavt)) (count (d/datoms @b :eavt))))
      (is (= "f1 of module 0" (d/q '[:find ?d . :where [?e :var/name app.m0/f1] [?e :var/doc ?d]] @a))))
    (testing "what a test writes stays on its branch"
      (d/transact a [{:note/text "a's"}])
      (d/transact b [{:note/text "b's"}])
      (is (= #{"a's"} (notes @a)))
      (is (= #{"b's"} (notes @b)))
      (is (= #{} (notes @seed))))
    (testing "the five fresh-store copies went with the memory config"
      (is (nil? (resolve 'seon.db/create!)))
      (is (nil? (resolve 'seon.db/mem-cfg))))
    (d/release seed)))
