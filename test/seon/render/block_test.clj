(ns seon.render.block-test
  "The surviving stable render address and bounded structural floors."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.render.block :as block]
            [seon.test-support :as support]))

(deftest stable-surface-addresses-are-injective
  (is (= "surface-transcript" (block/surface-id :transcript)))
  (support/assert-check!
   (tc/quick-check
    300
    (prop/for-all [[left right]
                   (gen/such-that
                    (fn [[left right]] (not= left right))
                    (gen/tuple gen/keyword-ns gen/keyword-ns)
                    100)]
      (not= (block/surface-id left) (block/surface-id right)))
    :seed 202607280201)
   "distinct names, distinct ids"))

(deftest ^{:seon/tasks #{'seon.render.block/implement-a-surface-id-passes-only-ascii}}
 every-surface-id-holds-only-ascii-letters-digits-dot-underscore-and-hyphen
 (support/assert-check!
  (tc/quick-check
   300
   (prop/for-all [s (gen/such-that seq gen/string)]
     (let [tail (subs (block/surface-id (keyword s)) 8)]
       (every? (fn [character]
                 (let [code (int character)]
                   (or (<= (int \a) code (int \z))
                       (<= (int \A) code (int \Z))
                       (<= (int \0) code (int \9))
                       (= \. character)
                       (= \_ character)
                       (= \- character))))
               tail)))
   :seed 20261013)
  "every surface id holds only ASCII letters, digits, dot, underscore and hyphen"))

(deftest ^{:seon/tasks #{'seon.render.block/implement-a-surface-id-passes-only-ascii}}
 surface-id-escapes-every-character-outside-ascii-letters-digits-dot-and-hyphen
 (doseq [[name expected]
         [[:transcript "surface-transcript"]
          [:a.b-c "surface-a.b-c"]
          [:a_b "surface-a__b"]
          [:my.turn/tree "surface-my.turn_2f_tree"]
          [(keyword "a b") "surface-a_20_b"]
          [(keyword "\u00e9") "surface-_e9_"]
          [(keyword "\u00aa") "surface-_aa_"]
          [(keyword "\u0663") "surface-_663_"]]]
   (is (= expected (block/surface-id name)))))
