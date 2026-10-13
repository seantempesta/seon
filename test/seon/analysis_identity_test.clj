(ns seon.analysis-identity-test
  "Analysis observations keep content identity across unrelated source edits."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.program :as program]
            [seon.test-support :as support]))

(deftest shifted-findings-update-only-positions-at-one-ten-and-one-hundred-definitions
  (let [root (io/file (support/temp-root "seon-lint-identity-"))
        file (io/file root "sample/lint.clj")
        artifact #(functions/build-artifact {:seon.fn/source-path (.getCanonicalPath file)
                                              :seon.fn/root (.getCanonicalPath root)
                                              :seon.fn.file/first-party-functions []})]
    (try
      (io/make-parents file)
      (support/with-database
       (fn [connection]
         (doseq [n [1 10 100]]
           (let [source (str "(ns sample.lint)\n"
                             (apply str (for [i (range n)]
                                          (str "(defn f" i " [a] (let [a 1] 0))\n"))))
                 _ (spit file source)
                 rows (:seon.fn.file/rows (artifact))
                 findings (filterv :seon.lint/id rows)
                 _ (support/transacted! connection (functions/reconcile-tx (db/db connection) rows []))
                 _ (spit file (str "\n" source))
                 shifted (filterv :seon.lint/id (:seon.fn.file/rows (artifact)))
                 report (support/transacted!
                         connection (functions/reconcile-tx (db/db connection) shifted
                                                             (mapv program/row-identity findings)))
                 facts (filter #(= "seon.lint" (namespace (:a %))) (:tx-data report))]
             (is (<= n (count findings)))
             (is (= (count findings) (count (set (map :seon.lint/id findings))))
                 "duplicate claims within a declaration remain distinct occurrences")
             (is (= (set (map #(dissoc % :seon.lint/row :seon.lint/col) findings))
                    (set (map #(dissoc % :seon.lint/row :seon.lint/col) shifted))))
             (is (= #{:seon.lint/row} (set (map :a facts))))
             (is (= (* 2 (count findings)) (count facts))
                 "each finding changes one position, regardless of population size")))))
      (finally (support/delete-root! (.getPath root))))))

(deftest a-new-file-stamp-does-not-rewrite-already-analyzed-declarations
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           names (take 100 (sort (db/q '[:find [?s ...] :where [_ :seon.fn/sym ?s]] database)))
           rows (vec (functions/published-index-entities database (mapv #(vector :seon.fn/sym %) names)))
           stamp (id/digest 64 [::new-file-analysis])]
       (is (= 100 (count rows)))
       (doseq [n [1 10 100]]
         (let [desired (mapv #(assoc % :seon.program/analyzed-source-digest stamp) (subvec rows 0 n))]
           (is (empty? (functions/reconcile-tx database desired []))
               "no declaration datoms are needed for a changed file stamp alone")))
       (let [row (first rows)]
         (is (false? (#'functions/same-indexed-entity?
                      (dissoc row :seon.program/analyzed-source-digest) row))
             "a first analysis observation is still a required write"))))))
