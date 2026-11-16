(ns datahike.transaction-test
  "Datahike's transaction, as Seon's writers call it: what an entity map's values mean."
  (:require [clojure.test :refer [deftest is]] [datahike.api :as d] [seon.test-store :as ts] [seon.db :as pdb] [seon.profile :as profile] [datahike.db.utils :as dbu]))

(deftest a-keyword-attributes-two-values-are-two-values
  ;; task datahike.transaction/a-keyword-attributes-two-values-are-two-values: maybe-wrap-multival
  ;; read any two values whose first is a unique attribute as a lookup ref, whatever the value type,
  ;; so a form naming :ns/name and one other key failed its :form/keywords write
  (let [db (d/db-with (ts/test-db) [{:form/id "f" :form/keywords #{:ns/name :query/nested-call}}
                                    {:form/id "g" :form/keywords [:ns/name :ns/file]}])
        kws (fn [id] (set (d/q '[:find [?k ...] :in $ ?id :where [?f :form/id ?id] [?f :form/keywords ?k]] db id)))]
    (is (= #{:ns/name :query/nested-call} (kws "f")) "a set: two keywords")
    (is (= #{:ns/name :ns/file} (kws "g")) "a vector: two keywords")
    (let [db (d/db-with db [{:ns/name 'app.x} {:form/id "h" :form/ns [:ns/name 'app.x]}])]
      (is (= 'app.x (:ns/name (:form/ns (d/entity db [:form/id "h"])))) "a ref's lookup ref still resolves"))))

(deftest a-replaced-value-is-retracted-in-the-report
  ;; task datahike.transaction/a-replaced-value-is-retracted-in-the-report: a cardinality-one
  ;; assertion over a held value replaced it in the index, but the report's :tx-data held only the
  ;; assertion (upstream's too), so a reader of a transaction's datoms (seon.ctx/patch) never saw a
  ;; ref move an entity away from its old parent. The report says the change whole, as DataScript's
  (let [db (d/db-with (ts/test-db) [{:seon.agent/key "b/a1" :seon.agent/outcome :waiting} {:seon.agent/key "b/a2"}
                                    {:seon.model-call/id "b/a1/0" :seon.model-call/agent [:seon.agent/key "b/a1"] :seon.model-call/n 0}])
        e (fn [db a v] (:db/id (d/entity db [a v])))
        datoms (fn [tx] (set (map (juxt :a :v :added) (remove #(= :db/txInstant (:a %)) (:tx-data (d/with db tx))))))]
    (is (= #{[:seon.agent/outcome :waiting false] [:seon.agent/outcome :green true]}
           (datoms [[:db/add [:seon.agent/key "b/a1"] :seon.agent/outcome :green]]))
        "a value replaced: its retraction, then its assertion")
    (is (= #{[:seon.model-call/agent (e db :seon.agent/key "b/a1") false] [:seon.model-call/agent (e db :seon.agent/key "b/a2") true]}
           (datoms [[:db/add [:seon.model-call/id "b/a1/0"] :seon.model-call/agent [:seon.agent/key "b/a2"]]]))
        "a ref moved: the old link retracted")
    (is (= #{} (datoms [[:db/add [:seon.agent/key "b/a1"] :seon.agent/outcome :waiting]])) "a value asserted again changes nothing")
    (is (= #{[:seon.agent/outcome :green true]} (datoms [[:db/add [:seon.agent/key "b/a2"] :seon.agent/outcome :green]])) "a first value retracts nothing")))

(deftest ^{:seon.test/estimate {:ticks 4000000}} a-schema-transaction-rebuilds-the-reverse-schema-once-per-attribute-not-once-per-datom
  ;; task datahike.transaction/a-schema-transaction-costs-its-attributes: update-rschema rebuilt the whole reverse
  ;; schema (dbu/rschema: every attribute, every property, the tuple and secondary mappings) after EACH schema
  ;; datom, so transacting Seon's 459 attributes allocated 599 MB (50: 22 MB, 150: 98 MB: ~n^1.9) and a new
  ;; store cost a gigabyte, enough to kill an agent under its 1 GiB heap allowance. The reverse schema is updated for
  ;; the attribute that changed, and equals the whole rebuild
  ;; in-process on a db value (no store), at 40 and 120 of Seon's attributes: 3x the attributes is under 3.9x the bytes
  ;; where the per-datom rebuild is ~n^1.9 (~8x)
  (let [attrs (vec (remove #(#{:seon.tx/runtime :seon.tx/branch} (:db/ident %)) pdb/schema))
        alloc (fn [k] (let [db0 (datahike.core/empty-db {} {:schema-flexibility :write :keep-history? false})
                            b0 (profile/alloc-bytes)
                            db (datahike.core/db-with db0 (vec (take k attrs)))]
                        {:bytes (- (profile/alloc-bytes) b0) :rschema (:rschema db) :whole (dbu/rschema (:schema db))}))
        a40 (alloc 40) a120 (alloc 120)]
    (is (= (:whole a40) (:rschema a40)) "the reverse schema is the whole rebuild's, at 40 attributes")
    (is (= (:whole a120) (:rschema a120)) "and at 120")
    (is (< (/ (:bytes a120) (:bytes a40)) (* 1.3 (/ 120 40.0)))
        (str "linear in the attributes: " (quot (:bytes a40) 1024) " KB for 40, " (quot (:bytes a120) 1024) " KB for 120"))))

;; Estimate: a test branch (~1M), two loads of 3 source transactions (~0.1M each), two transacts (~0.3M), ~2M = 2000000
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-load-of-several-transactions-takes-an-id-for-each
  ;; deps, 2026-11-15 [M] (the merge-time question with data-2): load-entities gives each source transaction a tx id
  ;; of its own, but took only the first from the store's ids (datahike.ids) and counted the rest past the db's
  ;; max-tx, then left :max-tx at the first: the next transaction took an id the load had used. Every transaction
  ;; a load makes has an id issued once, and the next transaction's is past them all
  (let [ents (vec (for [g (range 3) a [:note/text :note/agent]]
                    [(+ 100000000 g) a (if (= a :note/text) (str "load " g) :load) (+ 536900000 g) true]))
        loaded (fn [db] (sort (set (map :tx (d/datoms db :avet :note/agent :load)))))]
    (let [db (:db-after (datahike.core/load-entities-with (ts/test-db) ents nil))
          next-tx (:tx (first (d/datoms (d/db-with db [{:note/text "after" :note/agent :after}]) :avet :note/agent :after)))]
      (is (= 3 (count (loaded db))) "a speculation: three source transactions, three ids")
      (is (every? #(< % next-tx) (loaded db)) (str "and the next transaction's id is past them: " next-tx " after " (pr-str (loaded db)))))
    (ts/test-branch!
      (fn [_ conn]
        @(d/load-entities conn ents)
        (let [db @conn
              _ (d/transact conn [{:note/text "after" :note/agent :after}])
              next-tx (:tx (first (d/datoms @conn :avet :note/agent :after)))]
          (is (= 3 (count (loaded db))) "through the writer: three ids")
          (is (every? #(< % next-tx) (loaded db)) (str "each issued once, the next past them: " next-tx " after " (pr-str (loaded db)))))))))
