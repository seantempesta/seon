(ns seon.web.view-unit-test
  (:require [cljs.test :refer [deftest is]]
            [seon.web.view-unit :as unit]))

(deftest coordinate-token-is-stable-and-type-sensitive
  (is (= (unit/coordinate-token {:example/id "a" :example/name :b})
         (unit/coordinate-token {:example/name :b :example/id "a"})))
  (is (not= (unit/coordinate-token {:example/id "1"})
            (unit/coordinate-token {:example/id 1}))))
