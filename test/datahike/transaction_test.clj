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
