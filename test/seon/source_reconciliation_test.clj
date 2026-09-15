(ns seon.source-reconciliation-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.test-support :as test-support]))

(deftest source-reconciliation-preserves-identities-and-unrelated-facts
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.development]
            kept [:seon.fn/sym "sample.development/value"]
            removed [:seon.fn/sym "sample.development/removed"]
            agent-function [:seon.fn/sym "sample.agent/value"]
            before
            [{:seon.ns/name 'sample.development
              :seon.ns/source "(ns sample.development)"}
             {:seon.fn/sym (second kept) :seon.fn/ns namespace-ref
              :seon.schema.admission/source :core
              :seon.fn/source "(defn value [] 1)"
              :seon.fn/arglists "([])" :seon.fn/private? false
              :seon.fn/doc "old documentation"
              :seon.fn/arities [{:seon.fn.arity/arity "0"
                                :seon.fn.arity/order 0}]}
             {:seon.fn/sym (second removed) :seon.fn/ns namespace-ref
              :seon.schema.admission/source :core
              :seon.fn/source "(defn removed [] nil)"}
             {:seon.fn/sym (second agent-function) :seon.fn/ns namespace-ref
              :seon.fn/source "(defn value [] :agent)"
              :seon.schema.admission/source :agent}]
            setup (db/transact! connection before)
            _ (is (not (:seon.error/kind setup)) (pr-str setup))
            kept-id (:db/id (db/pull @connection [:db/id] kept))
            removed-id (:db/id (db/pull @connection [:db/id] removed))
            old-arity (:db/id (first (:seon.fn/arities
                                     (db/pull @connection [:seon.fn/arities] kept))))
            desired
            [{:seon.ns/name 'sample.development
              :seon.ns/source "(ns sample.development)"}
             {:seon.fn/sym (second kept) :seon.fn/ns namespace-ref
              :seon.fn/source "(defn value [x] x)"
              :seon.fn/arglists "([x])" :seon.fn/private? false
              :seon.fn/arities [{:seon.fn.arity/arity "1"
                                :seon.fn.arity/order 0}]}
             {:seon.fn/sym "sample.development/new-value" :seon.fn/ns namespace-ref
              :seon.fn/source "(defn new-value [] (value 2))"
              :seon.fn/calls [kept]}
             {:seon.schema/key :sample.development/new-schema
              :seon.schema/form ":string"}
             {:seon.test/sym "sample.development/new-test"
              :seon.test/ns namespace-ref
              :seon.test/source "(deftest new-test (is (= 2 (new-value))))"
              :seon.fn/calls [[:seon.fn/sym "sample.development/new-value"]]}]
            report (db/transact! connection
                                 {:tx-data [[:db.fn/call seon.fn/reconcile-tx
                                             desired [namespace-ref kept removed]]]})
            after @connection]
        (is (not (:seon.error/kind report)) (pr-str report))
        (is (= kept-id (:db/id (db/pull after [:db/id] kept))))
        (is (= {:db/id removed-id :seon.fn/sym (second removed)}
               (db/pull after '[*] removed)))
        (is (contains? (set (source/deleted-identities after)) removed)
            "a retry derives deletion even after its transaction already committed")
        (is (nil? (db/pull after '[*] old-arity)))
        (is (= "([x])" (:seon.fn/arglists (db/pull after '[*] kept))))
        (is (nil? (:seon.fn/doc (db/pull after '[*] kept))))
        (is (= "(defn value [] :agent)" (:seon.fn/source (db/pull after '[*] agent-function))))
        (is (= ":string" (:seon.schema/form
                          (db/pull after '[*] [:seon.schema/key :sample.development/new-schema]))))
        (is (= "sample.development/new-value"
               (get-in (db/pull after '[{:seon.fn/calls [:seon.fn/sym]}]
                                [:seon.test/sym "sample.development/new-test"])
                       [:seon.fn/calls 0 :seon.fn/sym])))
        (is (empty? (seon.fn/reconcile-tx after desired [namespace-ref kept removed]))
            "same admitted source produces no definition transactions")
        (let [result (db/transact! connection
                                  {:tx-data [[:db/add kept :seon.fn/doc "concurrent"]
                                             [:db.fn/call seon.fn/reconcile-tx
                                              desired [namespace-ref kept removed]]]})]
          (is (not (:seon.error/kind result)))
          (is (nil? (:seon.fn/doc (db/pull @connection '[*] kept)))
              "the writer removes an earlier same-transaction stale attribute"))))))
