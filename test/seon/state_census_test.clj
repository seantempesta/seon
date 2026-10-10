(ns seon.state-census-test
  "Census of the law that every atom in src is a validated cell."
  (:require [clojure.test :refer [deftest is]]
            [seon.census :as census]))

(def ^:private owner-accepted
  "Top-level forms whose atom the owner accepted in plain words, each with its reason. A site not listed
  here must hold the form's `set-validator!` with a Malli validator."
  {})

(defn- validated?
  [form]
  (some (fn [[used _]] (= "set-validator!" (census/bare used))) (census/uses form)))

(deftest ^{:seon/tasks #{'seon.state-census-test/every-atom-in-src-is-a-declared-cell}}
  every-atom-in-src-is-a-declared-cell
  (let [scanned (census/files ["src"] [".clj" ".cljc"])
        {:keys [unreadable sites]} (census/scan scanned (fn [used head?] (and head? (contains? #{"atom" "volatile!"} (census/bare used)))))
        unlisted (remove #(or (contains? owner-accepted [(:file %) (:in %)]) (validated? (:form %))) sites)]
    (is (> (count scanned) 100) "the census read the source tree")
    (is (= [] unreadable) "every file was read")
    (is (seq sites) "the census saw atoms")
    (is (= [] (census/without-forms unlisted)) "each site is a top-level form building an atom with no validator")))
