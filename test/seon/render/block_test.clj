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
