(ns seon.problems-cost-test
  "Cost proofs of the derived classes' reads (docs/health.md#old-words)."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]] [seon.cost :as cost]
            [seon.problems :as problems]))

(defn texts-of
  "The input of size n: n texts of about 1,200 characters in 30 lines of source, one in fifty using the old word
  \"lane\", and the words (of AGENTS.md section 7a's table) they are read for."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector [:tuple :any :string]] [:vector [:tuple :string [:vector :string]]]]]}
  [n]
  [(vec (for [i (range n)]
          [[:var (str "demo/f" i) "src/demo.clj"]
           (apply str (for [l (range 30)] (str "(defn f" i "-" l " [x] (let [y (inc x)] (* y 2)))" (when (and (zero? l) (zero? (mod i 50))) " ;; a lane") "\n")))]))
   (problems/old-word-spellings (slurp "AGENTS.md"))])

(deftest the-old-words-are-found-in-a-few-searches-over-the-texts
  (cost/proof
    {:of 'seon.problems/old-word-hits :input 'seon.problems-cost-test/texts-of
     :n "texts of about 1,200 characters"
     :work (str "each text lower-cased once and joined with its chunk's (about 100,000 characters), each of the table's "
                "spellings (~21) searched through the chunk once by indexOf (native, ~0.25 ticks a character), the few "
                "texts that use a word read a line at a time. The scan it replaced lower-cased, searched and split every "
                "text of 3,606 vars, 504 sections and 452 tasks (4.8 MB) at 194 MB per read of main")
     :growth :linear
     :per-n {:ticks 8000.0 :bytes 18000.0 :calls 0.0}
     :fixed {:ticks 100000.0 :bytes 200000.0 :calls 1.0}
     :sizes [30 100 1000]}))
