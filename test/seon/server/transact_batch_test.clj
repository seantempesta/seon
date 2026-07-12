(ns seon.server.transact-batch-test
  "Integration tests for `transact-batch`: ordered multi-tx commit
   with one pub event per individual tx. Matches d/listen semantics
   exactly.

   Each test spawns its own JVM writer subprocess (memory backend) and
   tears it down. Same fixture pattern as protocol_extensions_test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.server.test-util :as tu]))

(set! *warn-on-reflection* true)

(use-fixtures :each tu/with-fresh-writer)

(defn- req! [op extra] (tu/req! op extra))

(defn- transact! [wire-id tx-data]
  (req! "transact"
        {:seon.store.wire/id wire-id
         :seon.store.wire/tx-data tx-data}))

(defn- transact-batch! [wire-ids tx-data-list]
  (req! "transact-batch"
        {:seon.store.wire/ids wire-ids
         :seon.store.wire/tx-data-list tx-data-list}))

(defn- result-of [resp] (:seon.store.wire/result resp))
(defn- meta-of   [rep]  (:seon.store.wire/tx-meta rep))

(defn- install-schema! []
  (transact!
   "batch/item-schema"
   [{:db/ident :item/id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}
    {:db/ident :item/n
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}]))

(deftest test-batch-all-succeed
  (testing "transact-batch applies all entries in order and reports per-tx data"
    (install-schema!)
    (let [r (transact-batch!
             ["batch/all/a" "batch/all/b" "batch/all/c"]
             [[{:item/id "a" :item/n 1}]
              [{:item/id "b" :item/n 2}]
              [{:item/id "c" :item/n 3}]])]
      (is (= true (:seon.store.wire/ok r)))
      (is (= 3 (:seon.store.wire/applied r)))
      (is (= 3 (:seon.store.wire/total r)))
      (is (nil? (:seon.store.wire/failed-at r)))
      (let [reports (:seon.store.wire/reports r)]
        (is (= 3 (count reports)))
        (is (= [0 1 2] (mapv :seon.store.wire/index reports)))
        ;; basis-t advances monotonically across the batch
        (let [bts (mapv :seon.store.wire/basis-t reports)]
          (is (apply < bts) (str "basis-t should be strictly increasing: " bts)))
        ;; each report carries the wire-shape tx-data
        (doseq [rep reports]
          (is (vector? (:seon.store.wire/tx-data rep)))
          (is (pos? (:seon.store.wire/datoms-added rep))))))))

(deftest test-batch-preserves-order-in-db
  (testing "after the batch, all entries are queryable with expected values"
    (install-schema!)
    (transact-batch!
     ["batch/order/x" "batch/order/y" "batch/order/z"]
     [[{:item/id "x" :item/n 10}]
      [{:item/id "y" :item/n 20}]
      [{:item/id "z" :item/n 30}]])
    (let [r (req! "q" {:seon.store.wire/query '[:find ?id ?n :where [?e :item/id ?id] [?e :item/n ?n]]
                       :seon.store.wire/args  []})
          result (result-of r)
          by-id  (into {} (mapv (fn [[id n]] [id n]) result))]
      (is (= true (:seon.store.wire/ok r)))
      (is (= {"x" 10 "y" 20 "z" 30} by-id)))))

(deftest test-batch-tx-meta-per-entry
  (testing "each report carries datahike-issued tx-meta (db/txInstant + db/commitId)"
    ;; Datahike's :schema-flexibility :write requires user-supplied tx-meta
    ;; attrs to be installed in schema too — out of scope for this batch
    ;; test. Just verify the batch path preserves the datahike-issued
    ;; tx-meta shape, the same way single-tx does in
    ;; protocol_integration_test.clj/test-tx-meta-shape.
    (install-schema!)
    (let [r (transact-batch!
             ["batch/meta/a" "batch/meta/b"]
             [[{:item/id "a" :item/n 1}]
              [{:item/id "b" :item/n 2}]])]
      (is (= true (:seon.store.wire/ok r)))
      (is (nil? (:seon.store.wire/failed-at r)))
      (let [reports (:seon.store.wire/reports r)
            metas   (mapv meta-of reports)]
        (is (= 2 (count metas)))
        (doseq [m metas]
          (is (contains? m :db/txInstant))
          (is (contains? m :db/commitId)))
        ;; All commitIds must be distinct (each tx is a separate commit)
        (is (= 2 (count (into #{} (map :db/commitId) metas))))))))

(deftest test-batch-partial-failure-stops-after-bad-entry
  (testing "entry 1 references an unknown attr — entries 0 applies, 1 fails, 2 NOT applied"
    (install-schema!)
    (let [r (transact-batch!
             ["batch/partial/a" "batch/partial/b" "batch/partial/c"]
             [[{:item/id "good-a" :item/n 1}]
              [{:item/id "bad-b" :unknown/attr 2}]  ; bad — unknown attr
              [{:item/id "good-c" :item/n 3}]])]
      (is (= true (:seon.store.wire/ok r)) "op succeeds even though one entry failed")
      (is (= 1 (:seon.store.wire/applied r)))
      (is (= 3 (:seon.store.wire/total r)))
      (is (= 1 (:seon.store.wire/failed-at r)))
      (is (some? (:seon.store.wire/error r)))
      (is (= 1 (count (:seon.store.wire/reports r))))
      ;; entry 2 must NOT be in the DB
      (let [q (req! "q" {:seon.store.wire/query '[:find ?id :where [?e :item/id ?id]] :seon.store.wire/args []})
            ids (into #{} (map first) (result-of q))]
        (is (contains? ids "good-a"))
        (is (not (contains? ids "good-c")) "entry after the failure must not be applied")
        (is (not (contains? ids "bad-b")))))))

(deftest test-batch-empty-is-a-noop
  (testing "empty batch returns applied=0 total=0 with no error"
    (install-schema!)
    (let [r (transact-batch! [] [])]
      (is (= true (:seon.store.wire/ok r)))
      (is (= 0 (:seon.store.wire/applied r)))
      (is (= 0 (:seon.store.wire/total r)))
      (is (nil? (:seon.store.wire/failed-at r)))
      (is (= [] (:seon.store.wire/reports r))))))

(deftest test-batch-wire-ids-roundtrip
  (testing "wire-ids echo per-entry on each report"
    (install-schema!)
    (let [r (transact-batch!
             ["req-aaa" "req-bbb"]
             [[{:item/id "r1" :item/n 1}]
              [{:item/id "r2" :item/n 2}]])
          reports (:seon.store.wire/reports r)]
      (is (= "req-aaa" (:seon.store.wire/id (first reports))))
      (is (= "req-bbb" (:seon.store.wire/id (second reports)))))))

(deftest test-batch-retry-recovers-without-new-commits
  (testing "resending one frozen batch recovers every committed entry"
    (install-schema!)
    (let [wire-ids ["batch/retry/a" "batch/retry/b"]
          tx-data  [[{:item/id "retry-a" :item/n 1}]
                    [{:item/id "retry-b" :item/n 2}]]
          first-r  (transact-batch! wire-ids tx-data)
          retry-r  (transact-batch! wire-ids tx-data)
          first-reports (:seon.store.wire/reports first-r)
          retry-reports (:seon.store.wire/reports retry-r)
          query-r (req! "q"
                        {:seon.store.wire/query
                         '[:find ?id :where [?entity :item/id ?id]]
                         :seon.store.wire/args []})]
      (is (= 2 (:seon.store.wire/applied retry-r)))
      (is (every? :seon.store.wire/recovered? retry-reports)
          "every repeated entry is reconstructed from its durable receipt")
      (is (= (mapv :seon.store.wire/basis-t first-reports)
             (mapv :seon.store.wire/basis-t retry-reports))
          "the retry identifies the original commits")
      (is (= #{"retry-a" "retry-b"}
             (into #{} (map first) (:seon.store.wire/result query-r)))
          "the repeated batch created no duplicate domain entities"))))

(deftest test-batch-requires-one-distinct-wire-id-per-entry
  (install-schema!)
  (doseq [wire-ids [["batch/invalid/only-one"]
                    ["batch/invalid/duplicate" "batch/invalid/duplicate"]
                    ["batch/invalid/blank" " "]]]
    (let [response
          (transact-batch!
           wire-ids
           [[{:item/id "invalid-a" :item/n 1}]
            [{:item/id "invalid-b" :item/n 2}]])]
      (is (false? (:seon.store.wire/ok response)))
      (is (= "protocol" (:seon.store.wire/error-kind response)))))
  (let [query-r (req! "q"
                      {:seon.store.wire/query
                       '[:find ?id :where [?entity :item/id ?id]]
                       :seon.store.wire/args []})]
    (is (empty? (:seon.store.wire/result query-r))
        "invalid batch identities reject before the first domain commit")))
