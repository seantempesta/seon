(ns seon.print-census-test
  "Census of the law that the value printer is the one printer."
  (:require [clojure.test :refer [deftest is]]
            [seon.census :as census]))

(def ^:private canonical-edn
  "Top-level forms allowed to print: each writes a value as an EDN file, the stored form of that data."
  {["src/seon/fs.clj" 'write-edn-atomically!] "spits a value as the EDN text of a file the caller reads back"})

(defn- printing?
  "True of `pr-str` and its family, `clojure.pprint`, `with-out-str` and `print-method`."
  [used _]
  (let [bare (census/bare used)]
    (or (contains? #{"pr-str" "prn-str" "print-str" "println-str" "with-out-str" "print-method"} bare)
        (contains? #{"pprint" "clojure.pprint"} (namespace used)))))

(defn- unlisted
  "`sites` that neither the allowed list nor an `^{:seon.print/emitter true}` form names."
  [sites]
  (remove #(or (contains? canonical-edn [(:file %) (:in %)]) (census/emitter? (:form %))) sites))

(defn- unlisted-sites-of-text
  "The unlisted printing sites of source `text`."
  [text]
  (let [forms (census/read-forms text)]
    {:sites (unlisted (for [form forms
                            :let [used (filter (fn [[s head?]] (printing? s head?)) (census/uses form))]
                            :when (seq used)]
                        {:file "fixture.clj" :in (second form) :form form}))}))

(deftest ^{:seon/tasks #{'seon.print-census-test/no-value-is-printed-outside-the-value-printer}}
  no-value-is-printed-outside-the-value-printer
  (let [scanned (remove census/printer-files (census/files ["src" "script"] [".clj" ".cljc"]))
        {:keys [unreadable sites]} (census/scan scanned printing?)
        outside (unlisted sites)]
    (is (> (count scanned) 100) "the census read the source and script trees")
    (is (= [] unreadable) "every file was read")
    (is (every? (fn [[[file form] _]] (some #(and (= file (:file %)) (= form (:in %))) sites)) canonical-edn)
        "every allowed site still prints, so the list stays honest")
    (is (= [] (census/without-forms outside)) "each site is a top-level form printing outside the value printer")))

(deftest the-printing-rule-catches-a-planted-print-and-spares-an-emitter
  (let [sites (fn [text] (:sites (unlisted-sites-of-text text)))]
    (is (seq (sites "(defn show [x] (pr-str x))")) "pr-str is caught")
    (is (seq (sites "(defn show [x] (with-out-str (print x)))")) "with-out-str is caught")
    (is (seq (sites "(defmethod print-method :a [x w] nil)")) "print-method is caught")
    (is (= [] (sites "(defn ^{:seon.print/emitter true} encode [x] (pr-str x))")) "a marked emitter is spared")))
