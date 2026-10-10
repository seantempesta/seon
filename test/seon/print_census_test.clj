(ns seon.print-census-test
  "Census of the law that the value printer is the one printer."
  (:require [clojure.test :refer [deftest is]]
            [seon.census :as census]))

(def ^:private printer-files
  "The value printer's own sources, where printing is the work."
  #{"src/seon/print.cljc" "src/seon/render/value.clj"})

(def ^:private canonical-edn
  "Top-level forms allowed to print: each writes a value as an EDN file, the stored form of that data."
  {["src/seon/fs.clj" 'write-edn-atomically!] "spits a value as the EDN text of a file the caller reads back"})

(defn- printing?
  [used _]
  (let [bare (census/bare used)]
    (or (contains? #{"pr-str" "prn-str" "print-str" "println-str"} bare)
        (contains? #{"pprint" "clojure.pprint"} (namespace used)))))

(deftest ^{:seon/tasks #{'seon.print-census-test/no-value-is-printed-outside-the-value-printer}}
  no-value-is-printed-outside-the-value-printer
  (let [scanned (remove printer-files (census/files ["src"] [".clj" ".cljc"]))
        {:keys [unreadable sites]} (census/scan scanned printing?)
        unlisted (remove #(contains? canonical-edn [(:file %) (:in %)]) sites)]
    (is (> (count scanned) 100) "the census read the source tree")
    (is (= [] unreadable) "every file was read")
    (is (every? (fn [[[file form] _]] (some #(and (= file (:file %)) (= form (:in %))) sites)) canonical-edn)
        "every allowed site still prints, so the list stays honest")
    (is (= [] (census/without-forms unlisted)) "each site is a top-level form printing outside the value printer")))
