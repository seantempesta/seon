(ns my.program-read-test
  (:require [clojure.string]
            [clojure.test :refer [are deftest is]]
            [my.program :as program]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest source-reads-a-function-and-a-test-from-their-own-families
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           function (program/source database 'seon.task/task-handle)
           test-sym (first (db/q database '[:find [?s ...] :where [?e :seon.test/sym ?s]]))
           test-read (program/source database test-sym)]
       (is (= [:function "src/seon/task.clj"] ((juxt :kind :file) function)))
       (is (= :=> (first (:contract function))))
       (is (integer? (:line function)))
       (is (= [:test test-sym] ((juxt :kind :sym) test-read)))
       (is (string? (:source test-read)))))))

(deftest source-of-an-unknown-symbol-names-the-nearest
  (support/with-database
   (fn [connection]
     (let [refusal (program/source (db/db connection) 'seon.task/task-handl)]
       (is (= 'seon.task/task-handl (:seon.program/not-found refusal)))
       (is (some #{'seon.task/task-handle} (:seon.error/expected refusal)))))))

(deftest search-ranks-bounds-and-offers-more
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           found (program/search database {:text :seon.render/form :limit 3})]
       (is (= 3 (count (:matches found))))
       (is (= 3 (:count found)) "the count is the matches returned")
       (is (= '(my.program/search {:text :seon.render/form :limit 9}) (:more found)))
       (is (every? #(and (integer? (:line %)) (string? (:snippet %))) (filter :sym (:matches found))))
       (is (not (contains? (program/search database "no such text anywhere zzq") :more)))))))

(deftest sizes-list-lines-largest-first
  (support/with-database
   (fn [connection]
     (let [sized (program/sizes (db/db connection) 'seon.task)
           lines (map second (:largest sized))]
       (is (= 'seon.task (:ns sized)))
       (is (= lines (reverse (sort lines))))
       (is (seq (:largest sized)))))))

(deftest source-of-a-misspelled-name-names-it-first-in-a-fixed-message
  (let [database (seon.db/db)
        typo (my.program/source database 'seon.ref/sectoin)
        elsewhere (my.program/source database 'nope.ns/foo)]
    (is (= 'seon.ref/section (first (:seon.error/expected typo))))
    (is (= (:seon.error/message typo) (:seon.error/message elsewhere)) "the message is fixed text; the names are data")))

(deftest a-search-snippet-keeps-the-whole-line
  ;; match-row cut a trimmed line over 100 characters to 97 and "..."; the value printer alone bounds what is shown.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           longest-line (fn [sym]
                          (->> (db/q database '[:find ?src . :in $ ?sym :where [?e :seon.fn/sym ?sym] [?e :seon.fn/source ?src]] sym)
                               clojure.string/split-lines
                               (map clojure.string/trim)
                               (sort-by count)
                               last))
           snippet-of (fn [sym line]
                        (->> (:matches (program/search database {:text line :limit 50}))
                             (filter #(= sym (:sym %)))
                             first
                             :snippet))]
       (is (< 100 (count (longest-line 'my.program/caller-data))) "the fixture line is long enough to be cut")
       (are [sym] (= (longest-line sym) (snippet-of sym (longest-line sym)))
         'my.program/caller-data
         'my.program/reads-key
         'my.program/breaks)))))

(deftest a-search-limit-is-a-positive-integer
  ;; :limit 0 answered no matches and a :more form that asks again with limit 0.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           refusal-type (fn [limit]
                          (let [answer (try (program/search database {:text "inbox" :limit limit})
                                            (catch clojure.lang.ExceptionInfo e (ex-data e)))]
                            (:seon.error/type answer)))
           one (program/search database {:text "inbox" :limit 1})]
       (is (= 1 (count (:matches one))) "a limit of 1 answers one match")
       (is (< 1 (:limit (second (:more one)))) "its :more form asks for a larger limit")
       (are [limit] (= :seon.instrument/contract-error (refusal-type limit))
         0
         -1)))))
