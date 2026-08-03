(ns seon.render.block-test
  "The surviving stable render address and bounded structural floors."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.config :as config]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.test-support :as support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

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

(deftest prepared-html-floor-is-total-and-bounded
  (support/assert-check!
   (tc/quick-check
    200
    (prop/for-all [rendered-value gen/any-printable]
      (let [rendered (block/data-panel
                      {:seon.render/value rendered-value
                       :seon.sci.admit/caps caps})]
        (and (hiccup/hiccup? rendered)
             (= "seon-data-panel" (:class (nth rendered 1))))))
    :seed 202607280204)
   "html floor totality")
  (let [bounded (block/data-panel
                 {:seon.render/value (vec (range 20))
                  :seon.sci.admit/caps
                  (assoc caps :seon.config.eval.result/max-collection 4)})]
    (is (str/includes? (hiccup/->string bounded) "elided"))))
