(ns seon.bootstrap-test
  "Help is a Markdown document: its sections and the references they make."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cache :as cache]
            [seon.bootstrap :as bootstrap]
            [seon.test-support :as support]))

(deftest every-reference-in-help-resolves
  (support/with-database
   (fn [connection]
     (let [groups (:seon.help/groups (bootstrap/help-value @connection))
           references (mapcat :seon.help/references groups)]
       (is (seq references) "help makes references")
       (is (empty? (filter :seon.ref/problem-kind references)) "every reference resolves")))))

(deftest help-answers-one-section-by-its-anchor
  (support/with-database
   (fn [connection]
     (let [whole (:seon.help/groups (bootstrap/help-value @connection))
           [group] (:seon.help/groups (bootstrap/help-section @connection "write-what-you-learn"))
           expected (first (filter #(= "Write what you learn" (:seon.help/title %)) whole))]
       (is (= expected group) "the section's text and references, as `(help)` carries them")
       (is (= "write-what-you-learn" (:seon.help/anchor group)))
       (is (seq (:seon.help/references group)) "the section makes references")
       (is (= :heading (:seon.ref/section-missing (bootstrap/help-section @connection "no-such-section")))
           "an unknown anchor answers the nearest headings")))))

(defn- cited-doc
  "The source help's reference to `function` shows at `database`, and the derivations that answer missed."
  [database function]
  (let [misses #(get-in (cache/status) [:seon.cache/kinds :seon.health/derived :seon.cache/misses] 0)
        before (misses)
        groups (:seon.help/groups (bootstrap/help-value database))]
    {:source (->> groups (mapcat :seon.help/references) (filter #(= function (:seon.ref/name %))) first :seon.fn/source)
     :misses (- (misses) before)}))

(deftest help-references-follow-the-program-they-cite
  (support/with-database
   (fn [connection]
     (cited-doc @connection 'seon.db/q)
     (is (zero? (:misses (cited-doc @connection 'seon.db/q))) "an unchanged program answers from the cache")
     (support/transacted!
      connection
      (support/replacement-tx @connection [:seon.fn/sym 'seon.db/q] "(defn q \"Changed by the test.\" [] 1)" {}))
     (is (str/includes? (str (:source (cited-doc @connection 'seon.db/q))) "Changed by the test.")
         "a cited function's new definition shows in help's reference"))))
