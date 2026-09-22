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

(deftest a-multimethod-contract-uses-its-dispatch-function-arities
  (doseq [source ["(defmulti emit (fn [node sink options depth path] (:face node)))"
                  "(clojure.core/defmulti emit (clojure.core/fn dispatch [node sink options depth path] (:face node)))"]]
    (is (= [{:seon.fn.signature/order 0
             :seon.fn.signature/variadic? false
             :seon.fn.signature/min 5
             :seon.fn.signature/max 5
             :seon.fn.signature/bindings '[node sink options depth path]}]
           (:seon.fn.signature/signatures
            (signature/function-signatures {:seon.fn/source source}))))))
