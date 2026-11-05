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

(deftest a-schema-transaction-rebuilds-the-reverse-schema-once-per-attribute-not-once-per-datom
  ;; task datahike.transaction/a-schema-transaction-costs-its-attributes: update-rschema rebuilt the whole reverse
  ;; schema (dbu/rschema: every attribute, every property, the tuple and secondary mappings) after EACH schema
  ;; datom, so transacting Seon's 459 attributes allocated 599 MB (50: 22 MB, 150: 98 MB: ~n^1.9) and a new
  ;; store cost a gigabyte, enough to kill an agent under its 1 GiB heap allowance. The reverse schema is updated for
  ;; the attribute that changed, and equals the whole rebuild
  (let [attrs (vec (remove #(#{:seon.tx/runtime :seon.tx/branch} (:db/ident %)) pdb/schema))
        fresh #(let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false :writer {:backend :sync}}]
                 (d/create-database cfg) (d/connect cfg))
        alloc (fn [k] (let [conn (fresh) b0 (profile/alloc-bytes)]
                        (d/transact conn {:tx-data (vec (take k attrs))})
                        (let [b (- (profile/alloc-bytes) b0) db @conn]
                          (d/release conn)
                          {:bytes b :rschema (:rschema db) :whole (dbu/rschema (:schema db))})))
        a150 (alloc 150) a450 (alloc (count attrs))]
    (is (= (:whole a150) (:rschema a150)) "the reverse schema is the whole rebuild's, at 150 attributes")
    (is (= (:whole a450) (:rschema a450)) "and at every attribute Seon declares")
    (is (< (/ (:bytes a450) (:bytes a150)) (* 1.3 (/ (count attrs) 150.0)))
        (str "linear in the attributes: " (quot (:bytes a150) 1048576) " MB for 150, " (quot (:bytes a450) 1048576) " MB for " (count attrs)))
    (is (< (:bytes a450) (* 100 1048576)) "a store's whole schema costs under 100 MB")))

