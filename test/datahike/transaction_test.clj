(ns datahike.transaction-test
  "Datahike's transaction, as Seon's writers call it: what an entity map's values mean."
  (:require [clojure.test :refer [deftest is]] [datahike.api :as d] [seon.test-store :as ts]))

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
  (let [db (d/db-with (ts/test-db) [{:seon.run/id "r#1" :seon.run/status :running} {:seon.run/id "r#2"}
                                    {:seon.turn/id "r#1/0" :seon.turn/run [:seon.run/id "r#1"] :seon.turn/n 0}])
        e (fn [db a v] (:db/id (d/entity db [a v])))
        datoms (fn [tx] (set (map (juxt :a :v :added) (remove #(= :db/txInstant (:a %)) (:tx-data (d/with db tx))))))]
    (is (= #{[:seon.run/status :running false] [:seon.run/status :done true]}
           (datoms [[:db/add [:seon.run/id "r#1"] :seon.run/status :done]]))
        "a value replaced: its retraction, then its assertion")
    (is (= #{[:seon.turn/run (e db :seon.run/id "r#1") false] [:seon.turn/run (e db :seon.run/id "r#2") true]}
           (datoms [[:db/add [:seon.turn/id "r#1/0"] :seon.turn/run [:seon.run/id "r#2"]]]))
        "a ref moved: the old link retracted")
    (is (= #{} (datoms [[:db/add [:seon.run/id "r#1"] :seon.run/status :running]])) "a value asserted again changes nothing")
    (is (= #{[:seon.run/status :done true]} (datoms [[:db/add [:seon.run/id "r#2"] :seon.run/status :done]])) "a first value retracts nothing")))
