(ns seon.sci.namespace-publics-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.sci.lookup :as lookup]
            [seon.test-support :as support]))

(deftest namespace-doc-lists-only-its-own-publics
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           database (db/db connection)]
       (doseq [namespace-name '[seon.ref seon.sci.reader my.fs]]
         (is (= (set (map :sym (:functions (lookup/directory-value database namespace-name false))))
                (set (:publics (lookup/documentation-value ctx database namespace-name namespace-name))))
             "doc answers the namespace's own public definitions, the set dir answers"))))))
