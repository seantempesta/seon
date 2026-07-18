(ns seon.web.view-unit-test
  (:require [cljs.test :refer [deftest is]]
            [seon.web.view-unit :as unit]))

(deftest identity-token-is-stable-and-type-sensitive
  (is (= (unit/identity-token {:example/id "a" :example/name :b})
         (unit/identity-token {:example/name :b :example/id "a"})))
  (is (not= (unit/identity-token {:example/id "1"})
            (unit/identity-token {:example/id 1}))))
