(ns seon.schema-description-test
  "The reply and the current-state block describe every entry an agent reads."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.ref :as ref]
            [seon.test-support :as support]))

(defn- members
  "The key of each entry of the map schema registered as `schema-key`."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword] [:vector :qualified-keyword]]}
  [database schema-key]
  (into [] (keep #(when (vector? %) (first %)))
        (rest (:form (ref/schema-documentation database schema-key schema-key)))))

(deftest reply-and-block-members-are-described
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           undescribed (fn [schema-key]
                         (into [] (remove #(:description (ref/schema-documentation database % %)))
                               (members database schema-key)))]
       (is (every? (set (members database :seon.repl/response))
                   [:seon.repl/findings :seon.repl/diagnostics])
           "the response names every entry response-entries emits")
       (is (= [] (undescribed :seon.repl/response)))
       (is (= [] (undescribed :seon.context/current-state)))
       (is (:description (ref/schema-documentation database :seon.repl/request :seon.repl/request)))))))
