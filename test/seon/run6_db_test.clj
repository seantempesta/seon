(ns seon.run6-db-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.test-support :as support]))

; Exact stored run-6 source, including whitespace, read from default on 2026-09-15.
(def run6-forms
  ["(seon.db/q\n  '[:find ?id ?customer ?amount\n    :where\n    [?e :example/order ?id]\n    [?e :example/customer ?customer]\n    [?e :example/amount ?amount]]\n  [])"
   "(seon.db/q\n  '[:find (pull ?e [*])\n    :where [?e :example/order _]]\n  [])"])

(deftest query-contract-refuses-run6-and-count-mismatches
  (support/with-database
   (fn [connection]
     (binding [db/*conn* connection]
       (doseq [source run6-forms
               :let [form (read-string source)
                     query (second (second form))]
               call [#(db/q query [])
                     #(db/q @connection query [])
                     #(db/q {:query query :args [[]]})
                     #(db/q @connection {:query query :args [[]]})]]
         (let [failure (support/refusal-data call)]
           (is (= :seon.instrument/contract-violated (:seon.error/kind failure)))
           (is (str/includes? (:seon.error/message failure) ":in"))
           (is (str/includes? (:seon.error/message failure) "(seon.db/q query input ...)"))
           (is (str/includes? (:seon.error/message failure) "(seon.db/q database query input ...)"))))
       (let [query '[:find ?id :in $ ?id :where [_ :seon.agent/id ?id]]]
         (doseq [call [#(db/q query) #(db/q query @connection)
                       #(db/q query @connection "a" "extra")
                       #(db/q {:query query :args [@connection "a"]} "ignored")
                       #(db/q '[:find ?id :in $other :where [$other _ :seon.agent/id ?id]] [])]]
           (is (= :seon.instrument/contract-violated
                  (:seon.error/kind (support/refusal-data call))))))
       (is (= #{["root"]}
              (db/q '[:find ?id :in [?id ...]] ["root"])))
       (is (= (db/q '[:find ?id :where [_ :seon.agent/id ?id]])
              (db/q @connection '[:find ?id :where [_ :seon.agent/id ?id]])))))))

(deftest database-read-arities-refuse-stray-inputs
  (support/with-database
   (fn [connection]
     (binding [db/*conn* connection]
       (doseq [[label call]
               [[:pull-map #(db/pull {:selector '[*] :eid 1} [])]
                [:pull-positional #(db/pull '[*] 1 [])]
                [:pull-explicit #(apply db/pull [@connection '[*] 1 []])]
                [:pull-db #(db/pull [] '[*] 1)]
                [:pull-map-db #(db/pull [] {:selector '[*] :eid 1})]
                [:pull-explicit-map #(db/pull @connection {:selector '[*] :eid 1} [])]
                [:pull-many-map #(db/pull-many {:selector '[*] :eids [1]} [])]
                [:pull-many-positional #(db/pull-many '[*] [1] [])]
                [:pull-many-explicit #(apply db/pull-many [@connection '[*] [1] []])]
                [:pull-many-db #(db/pull-many [] '[*] [1])]
                [:pull-many-map-db #(db/pull-many [] {:selector '[*] :eids [1]})]
                [:pull-many-explicit-map #(db/pull-many @connection {:selector '[*] :eids [1]} [])]
                [:entity #(db/entity 1 [])]
                [:entity-explicit #(apply db/entity [@connection 1 []])]
                [:entity-db #(db/entity [] 1)]
                [:datoms-map #(db/datoms {:index :eavt} [])]
                [:datoms-explicit-map #(db/datoms @connection {:index :eavt} [])]
                [:datoms-map-db #(db/datoms [] {:index :eavt})]
                [:datoms-components #(db/datoms :eavt 1 :seon.agent/id "root" 1 true)]
                [:datoms-explicit-components #(db/datoms @connection :eavt 1 :seon.agent/id "root" 1 true)]
                [:datoms-db #(db/datoms [] :eavt)]]]
         (testing (name label)
           (is (= :seon.instrument/contract-violated
                  (:seon.error/kind (support/refusal-data call))))))))))
