(deftest patch-form-source-test
  (testing "single + round-trip preserves unrelated source"
    (let [src "(defn f [x]   (+ x 1)) ; keep"
          r (seon.code.cst/patch-form-source src "(+ x 1)" "(* x 2)")]
      (is (:ok? r))
      (is (= "(defn f [x]   (* x 2)) ; keep" (:src r)))
      (is (= 1 (:applied r)))))
  (testing "no match reports :no-match, not silent"
    (let [r (seon.code.cst/patch-form-source "(defn f [x] (+ x 1))" "(missing)" "z")]
      (is (false? (:ok? r)))
      (is (= :no-match (:error r)))
      (is (= 0 (:applied r)))))
  (testing "empty src reports :no-match"
    (let [r (seon.code.cst/patch-form-source "" "a" "b")]
      (is (false? (:ok? r)))
      (is (= :no-match (:error r)))))
  (testing "invalid syntax reported at parse stage"
    (let [r (seon.code.cst/patch-form-source "(defn f [x] (+ x 1)" "a" "b")]
      (is (false? (:ok? r)))
      (is (= :invalid-clojure-syntax (:error r))))))
