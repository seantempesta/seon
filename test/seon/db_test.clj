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
        ceiling (+ lease (cfg/value :seon.config.store/eid-lease))
        notes (fn [n tag] (vec (for [i (range n)] {:note/text (str tag i)})))]
    (try
      (pdb/lease! c lease)
      (let [r1 (dw/apply! conn {:tx-data (notes (- (cfg/value :seon.config.store/eid-lease) 2) "a")})
            before (:db-after r1)
            r2 (dw/apply! conn {:tx-data (notes 5 "b")})
            e (pdb/lease-spent r2 lease)]
        (testing "the lease mints up to its end"
          (is (map? r1))
          (is (= (- ceiling 2) (:max-eid before)))
          (is (= {:from lease :used (- (cfg/value :seon.config.store/eid-lease) 2) :of (cfg/value :seon.config.store/eid-lease)} (pdb/lease-use before lease))))
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
        (is (= (cfg/value :seon.config.store/lmdb-map-bytes) map-bytes))
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
        ceiling (+ lease (cfg/value :seon.config.store/eid-lease))
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

(deftest two-processes-branch-one-store
  ;; two processes at once each make 50 branches of one store (two roots on one store, as
  ;; parallel check steps or a standing root beside a run): every branch is listed. A store on
  ;; disk of this test's own, the last resort: the race is between processes; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-race-" (random-uuid))
        _ (d/release (:conn (pdb/open! :lmdb path 0 :db)))
        jolt (str (System/getProperty "user.dir") "/target/jolt/jolt")
        code (fn [tag] (pr-str `(do (require 'seon.db 'datahike.api)
                                    (let [c# (:conn (seon.db/open! :lmdb ~path 0 :db))]
                                      (dotimes [i# 50] (datahike.api/branch! c# :db (keyword (str ~tag "-" i#))))
                                      (System/exit 0)))))
        cmd (str "'" jolt "' -A:test -e " (pr-str (code "x")) " > /dev/null 2>&1 & "
                 "'" jolt "' -A:test -e " (pr-str (code "y")) " > /dev/null 2>&1 & wait")
        r (clojure.java.shell/sh "nice" "-n" "10" "zsh" "-c" cmd)
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)
        made (for [t ["x" "y"] i (range 50)] (keyword (str t "-" i)))]
    (try
      (is (zero? (:exit r)))
      (is (= 100 (count (filter #(pdb/branch? conn %) made))) "every branch's head was written")
      (is (= (set made) (disj (d/branches conn) :db)) "and every branch is listed")
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest opening-a-kept-store-installs-the-attributes-it-lacks
  ;; a store on disk of this test's own, the last resort: what is proved is what a root's
  ;; open does to a kept store seeded before an attribute existed; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-" (random-uuid))
        cfg (pdb/store-cfg :lmdb path)
        lacking (:db/ident (last pdb/schema))
        _ (d/create-database cfg)
        c0 (d/connect cfg)
        _ (d/transact c0 {:tx-data (vec (butlast pdb/schema))})
        _ (d/release c0)
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (is (= lacking (:db/ident (get (d/schema @conn) lacking))) "the attribute the store lacked is installed")
      (let [t (:max-tx @conn)
            _ (d/release conn)
            {c2 :conn} (pdb/open! :lmdb path 0 :db)]
        (is (= t (:max-tx @c2)) "an open that finds nothing missing writes nothing")
        (d/release c2))
      (finally (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-moved-store-opens
  ;; task seon.db/a-store-opens-where-it-lies: a store's Datahike id is (hasch/uuid path) of
  ;; the path it was created at (seon.db/store-cfg), so a store renamed or copied (kept runs:
  ;; next/runs/store-full-20261021, renamed from next/runs/store) is refused at its new path
  ;; with "Store identity mismatch". A store on disk of this test's own, the last resort:
  ;; what is proved is a move; removed at the end
  (let [base (str (System/getProperty "user.dir") "/target/db-test-move-" (random-uuid))
        moved (str base "-moved")
        _ (d/release (:conn (pdb/open! :lmdb base 0 :db)))
        _ (clojure.java.shell/sh "mv" base moved)]
    (try
      (let [r (try (pdb/open! :lmdb moved 0 :db) (catch Exception e {:refused (ex-message e)}))]
        (is (some? (:conn r)) (str "the moved store opens: " (:refused r)))
        (some-> (:conn r) d/release))
      (finally (clojure.java.shell/sh "rm" "-rf" base moved)))))
