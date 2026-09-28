(ns seon.source-reconciliation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.id :as id]
            [seon.test-support :as test-support]
            [seon.print.value]))

;; Every declaration is analyzed through the production source path
;; (`test-support/program-entity`), so it carries the digests the writer
;; requires; hand-built rows lacked them and the setup was refused (r5, r6).
;; The fixture namespaces are minted per run: a symbol quoted in this file is a
;; reference of this test's own program entity, and the writer refuses deleting
;; a declaration that a surviving entity references (5d933754f).
(deftest source-reconciliation-preserves-identities-and-unrelated-facts
  (test-support/with-database
    (fn [connection]
      (let [development (symbol (str "sample.reconciled" (id/id)))
            other (symbol (str "sample.agent" (id/id)))
            in-development #(symbol (str development) %)
            source-of (fn [namespace-name & forms]
                        (str/join "\n" (cons (pr-str (list 'ns namespace-name)) forms)))
            database (db/db connection)
            namespace-ref [:seon.ns/name development]
            kept [:seon.fn/sym (in-development "value")]
            removed [:seon.fn/sym (in-development "removed")]
            unrelated [:seon.fn/sym (symbol (str other) "value")]
            old-source (source-of development "(defn value \"old documentation\" [] 1)"
                                  "(defn removed [] nil)")
            setup (db/transact! connection
                                [(test-support/namespace-entity database development)
                                 (test-support/program-entity database kept old-source)
                                 (test-support/program-entity database removed old-source)
                                 (test-support/namespace-entity database other)
                                 (test-support/program-entity
                                  database unrelated (source-of other "(defn value [] :agent)"))])
            _ (is (some? (:db-after setup)) (seon.print.value/bounded-text setup))
            before @connection
            kept-id (:db/id (db/pull before [:db/id] kept))
            _ (is (= "old documentation" (:seon.fn/doc (db/pull before '[*] kept))))
            _ (is (some? (:db/id (db/pull before [:db/id] removed))))
            new-source (source-of development "(defn value [x] x)" "(defn new-value [] (value 2))"
                                  "(deftest new-test (is (= 2 (new-value))))")
            new-test [:seon.test/sym (in-development "new-test")]
            desired [(test-support/namespace-entity before development)
                     (test-support/program-entity before kept new-source)
                     (test-support/program-entity before [:seon.fn/sym (in-development "new-value")] new-source)
                     (test-support/program-entity before new-test new-source)]
            reconcile [:db.fn/call seon.fn/reconcile-tx desired [namespace-ref kept removed]]
            report (db/transact! connection {:tx-data [reconcile]})
            after @connection]
        (is (some? (:db-after report)) (seon.print.value/bounded-text report))
        (is (= kept-id (:db/id (db/pull after [:db/id] kept))) "a kept identity keeps its entity")
        (is (nil? (:db/id (db/pull after [:db/id] removed))))
        (is (contains? (set (source/deleted-identities after)) removed)
            "a retry derives deletion even after its transaction already committed")
        (is (= "([x])" (:seon.fn/arglists (db/pull after '[*] kept))))
        (is (nil? (:seon.fn/doc (db/pull after '[*] kept))))
        (is (= "(defn value [] :agent)" (:seon.fn/source (db/pull after '[*] unrelated))))
        ;; A call edge is the callee's symbol (0e8f7d323), never a ref.
        (is (contains? (set (:seon.fn/calls (db/pull after [:seon.fn/calls] new-test)))
                       (in-development "new-value")))
        (let [repeated (db/transact! connection {:tx-data [reconcile]})]
          (is (some? (:db-after repeated)) (seon.print.value/bounded-text repeated))
          (is (= [:db/txInstant] (mapv :a (:tx-data repeated)))
              "the same admitted source writes no definition datom"))
        (let [result (db/transact! connection
                                   {:tx-data [[:db/add kept :seon.fn/doc "concurrent"] reconcile]})]
          (is (some? (:db-after result)) (seon.print.value/bounded-text result))
          (is (nil? (:seon.fn/doc (db/pull @connection '[*] kept)))
              "the writer removes an earlier same-transaction stale attribute"))))))
