(ns seon.db-test
  "One store, many branches, as in production (owner, 2026-10-21): bin/test seeds one test
  store and each test takes a branch of it through one helper (seon.db/test-branch!)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [datahike.api :as d] [datahike.writer :as dw] [datahike.db.transaction :as dbt]
            [seon.db :as pdb] [seon.config :as cfg] [seon.contract :as contract]))

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

(deftest minting-past-the-lease-is-refused-not-shared
  ;; this process takes a lease on a test branch as a forked agent does (seon.db/after-fork!
  ;; -> lease!), mints all but two of its ids, then asks for five more
  (let [[c conn] (pdb/test-branch!)
        sid (get-in c [:store :id])
        lease (pdb/lease-base conn)
        ceiling (+ lease cfg/EID-LEASE)
        notes (fn [n tag] (vec (for [i (range n)] {:note/text (str tag i)})))]
    (try
      (pdb/lease! c lease)
      (let [r1 (dw/apply! conn {:tx-data (notes (- cfg/EID-LEASE 2) "a")})
            before (:db-after r1)
            r2 (dw/apply! conn {:tx-data (notes 5 "b")})
            e (pdb/lease-spent r2 lease)]
        (testing "the lease mints up to its end"
          (is (map? r1))
          (is (= (- ceiling 2) (:max-eid before)))
          (is (= {:from lease :used (- cfg/EID-LEASE 2) :of cfg/EID-LEASE} (pdb/lease-use before lease))))
        (testing "past it the transaction is refused with the typed error, nothing applied"
          (is (not (map? r2)))
          (is (= :seon.db/lease-spent (:seon.error/type e)))
          (is (contract/valid? :seon.db/lease-spent e))
          (is (= {:lease lease :ceiling ceiling} (select-keys e [:lease :ceiling])))
          (is (string? (:seon.error/fix e)))
          (is (nil? (pdb/lease-spent r2 nil)) "a process without a lease (root) is never refused this way"))
        (testing "the next lease's ids were never minted"
          (let [run-ahead (:db-after (dw/apply! conn {:tx-data []}))]
            (is (= (- ceiling 2) (:max-eid run-ahead)))
            (is (empty? (d/datoms run-ahead :eavt ceiling))))))
      (finally (dbt/set-eid-ceiling! sid nil)))))

(deftest the-store-reports-its-map-use
  ;; a store on disk of this test's own, the last resort: what is proved is how an LMDB
  ;; store reports its map; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-" (random-uuid))
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (let [{:keys [used-bytes map-bytes]} (pdb/map-use cfg)]
        (is (= (* 1048576 cfg/LMDB-MB) map-bytes))
        (is (< 0 used-bytes map-bytes))
        (d/transact conn [{:note/text (apply str (repeat 100000 "x"))}])
        (pdb/sync! cfg)
        (is (< used-bytes (:used-bytes (pdb/map-use cfg))) "it grows as the store does"))
      (is (nil? (pdb/map-use (first (pdb/test-branch!)))) "an in-memory store has no map")
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-lease-mints-in-its-own-block-after-a-merge
  ;; main merged into the branch brings entities minted in a later agent's block (ids past
  ;; this lease's ceiling): this process still mints inside its own block, never above them
  (let [[c conn] (pdb/test-branch!)
        sid (get-in c [:store :id])
        lease (pdb/lease-base conn)
        ceiling (+ lease cfg/EID-LEASE)
        theirs (+ ceiling 10)
        new-id (fn [r] (d/q '[:find ?e . :in $ ?t :where [?e :note/text ?t]] (:db-after r) "mine"))]
    (try
      (pdb/lease! c lease)
      (let [r0 (dw/apply! conn {:tx-data [[:db/add theirs :note/text "theirs"]]})
            r1 (dw/apply! conn {:tx-data [{:note/text "mine"}]})]
        (is (map? r0))
        (is (<= theirs (:max-eid (:db-after r0))) "the branch holds the later block's entity")
        (is (map? r1) "minting goes on")
        (is (< lease (new-id r1) ceiling) "inside its own block"))
      (finally (dbt/set-eid-ceiling! sid nil)))))
