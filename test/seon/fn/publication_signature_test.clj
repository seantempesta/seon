(ns seon.fn.publication-signature-test
  "Declaration metadata follows Clojure's grammar and excludes executable bodies."
  (:require [clojure.test :refer [deftest is]]
            [seon.fn.signature :as signature]))

(deftest declaration-metadata-distinguishes-attribute-maps-from-bodies
  (doseq [source ["(def value {:body 1})"
                  "(deftest example {:body 1})"
                  "(defn example [] {:body 1})"]]
    (is (= {} (signature/declaration-metadata source 'pub.alpha {}))))
  (is (= {:deprecated "now"}
         (signature/declaration-metadata
          "(defn example ([] 0) ([x] x) {:deprecated \"now\"})" 'pub.alpha {}))))
