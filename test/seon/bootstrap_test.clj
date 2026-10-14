(ns seon.bootstrap-test
  "Help is a Markdown document: its sections and the references they make."
  (:require [clojure.test :refer [deftest is]]
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
