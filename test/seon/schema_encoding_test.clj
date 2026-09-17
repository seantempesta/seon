(ns seon.schema-encoding-test
  (:require [clojure.test :refer [deftest is]]
            [seon.schema :as schema]))

(deftest canonical-encoding-retains-published-bytes
  ;; Golden bytes observed on default before the recursion change, 2026-09-17.
  ;; Map and set members retain their existing framing, including string frames.
  (doseq [[value expected]
          [[[:map {:z false :a true} [:x :string]
             [:y {:optional true} [:set :keyword]]]
            "v103:k4::mapm20:s7:k2::ab1s7:k2::zb0v15:k2::xk7::stringv49:k2::ym18:s14:k9::optionalb1v18:k4::setk8::keyword"]
           [{:b #{:z :a} :a '(1 "x")}
            "m49:s16:k2::aq8:d1:1s1:xs25:k2::bt16:s5:k2::as5:k2::z"]
           [[nil false true 1 1.5 :x 'a/b \a
             #uuid "00000000-0000-0000-0000-000000000001" #inst "1970-01-01"]
            "v74:nb0b1d1:1d3:1.5k2::xy3:a/bc1:au36:00000000-0000-0000-0000-000000000001i1:0"]]]
    (is (= expected (schema/canonical-data-string value))))
  (is (= (schema/canonical-data-string (array-map :a 1 :b 2))
         (schema/canonical-data-string (array-map :b 2 :a 1))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-EDN"
                       (schema/canonical-data-string (Object.)))))
