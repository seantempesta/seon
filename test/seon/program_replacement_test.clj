(ns seon.program-replacement-test
  "Publication writes only the members that changed in a declared set."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as support]))

(deftest ordered-many-attributes-do-not-use-set-differences
  (let [forms (schema.edn/packaged-forms)
        shape (fn [forms] (get (program/shapes-in (schema/build-projection forms)) :seon.fn/sym))]
    (is (contains? (:seon.program/set-attributes (shape forms)) :seon.fn/calls))
    (is (not (contains? (:seon.program/set-attributes
                        (shape (update forms :seon.fn/calls #(assoc % 0 :vector))))
                       :seon.fn/calls)))))

(deftest a-set-edit-writes-two-datoms-at-one-ten-and-one-hundred-members
  (support/with-database
   (fn [connection]
     (let [names (->> (ns-publics 'clojure.core)
                      (keep (fn [[s v]] (when (and (not (:macro (meta v))) (fn? @v)) s)))
                      (remove #{'identity}) sort (take 100)
                      (mapv #(symbol "clojure.core" (name %))))
           source #(id/printed (list 'defn 'set-delta [] (vec %)))
           sym 'seon.program/set-delta]
       (doseq [n [1 10 100]]
         (let [database (db/db connection)
               old (subvec names 0 n)
               edited (conj (subvec old 1) 'clojure.core/identity)
               current (support/program-fn-entity database sym (source old))
               _ (support/transacted! connection (functions/reconcile-tx database [current] []))
               database (db/db connection)
               desired (support/program-fn-entity database sym (source edited))
               shapes (program/shapes-in (db/carried-projection database))
               pulled (db/pull database '[*] [:seon.fn/sym sym])
               direct (db/with database (program/exact-replacement-tx-in shapes pulled desired))
               report (support/transacted! connection (functions/reconcile-tx database [desired] []))
               calls (filter #(= :seon.fn/calls (:a %)) (:tx-data report))
               selector [:seon.fn/source :seon.fn/calls :seon.fn/references :seon.program/definition-digest]
               expected (db/pull direct selector [:seon.fn/sym sym])
               actual (db/pull (db/db connection) selector [:seon.fn/sym sym])]
           (is (not (:seon.error/at direct)))
           (is (= expected actual) "direct replacement and publication leave the same declaration")
           (is (= 2 (count calls)) "unchanged set members produce no retraction or assertion")
           (is (= #{[(first old) false] ['clojure.core/identity true]}
                  (set (map (juxt :v :added) calls))))))))))
