(ns seon.cst-metrics-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.code.cst :as cst]
            [seon.code.cst.ns :as cstns]
            [seon.health.metrics :as m]))

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

(deftest patch-form-source-batch-test
  (testing "applies multiple edits in one pass"
    (let [r (cst/patch-form-source-batch "(defn f [x] [(+ x 1) (* x 2)])"
                                         [["(+ x 1)" "(+ x 10)"] ["(* x 2)" "(* x 20)"]])]
      (is (:ok? r))
      (is (= 2 (:applied r)))
      (is (= "(defn f [x] [(+ x 10) (* x 20)])" (:src r)))))
  (testing "empty pair list is a trivial no-op"
    (let [r (cst/patch-form-source-batch "(defn f [x] 1)" [])]
      (is (:ok? r))
      (is (= 0 (:applied r)))
      (is (= "(defn f [x] 1)" (:src r)))))
  (testing "ambiguous duplicate must be reported, not applied"
    (let [r (cst/patch-form-source-batch "(do (+ 1 2) (+ 1 2))" [["(+ 1 2)" "(+ 3 4)"]])]
      (is (false? (:ok? r)))
      (is (= :ambiguous-match (:error r)))
      (is (= 2 (:count r)))
      (is (= 0 (:applied r))))))

(deftest slice-edit-test
  (testing "contiguous sibling slice edit preserves surrounding source"
    (let [src "(defn f [x] (let [a 1 b 2] (+ a b)))"
          r (cst/patch-form-source src "[a 1 b 2]" "[a 10 b 20]" {:occurrence 1})]
      (is (:ok? r))
      (is (= "(defn f [x] (let [a 10 b 20] (+ a b)))" (:src r)))))
  (testing "slice no-match is reported"
    (let [r (cst/patch-form-source "(defn f [] (let [a 1] a))" "[a 2 b 3]" "[z 9]")]
      (is (false? (:ok? r)))
      (is (= :no-match (:error r))))))

(deftest prune-unused-requires-src-test
  (testing "removes genuinely unused lib, keeps used one"
    (let [src "(ns foo (:require [clojure.string :as str]\n            [clojure.set :as set]))\n(defn f [x] (str/upper-case x))"
          r (cstns/prune-unused-requires-src src)]
      (is (:ok? r))
      (is (:changed? r))
      (is (= ["clojure.set"] (:removed r)))
      (is (= ["clojure.string"] (:kept r)))
      (is (not (clojure.string/includes? (:src r) "clojure.set")))))
  (testing "all requires used => unchanged no-op"
    (let [src "(ns foo (:require [clojure.string :as str]))\n(defn f [x] (str/trim x))"
          r (cstns/prune-unused-requires-src src)]
      (is (:ok? r))
      (is (false? (:changed? r)))
      (is (= src (:src r)))))
  (testing "no require clause => unchanged"
    (let [src "(ns foo)\n(defn g [] 1)"
          r (cstns/prune-unused-requires-src src)]
      (is (:ok? r))
      (is (false? (:changed? r)))
      (is (= [] (:removed r))))))

(deftest add-require-src-test
  (testing "adds a new require to an existing clause"
    (let [r (cstns/add-require-src "(ns foo (:require [clojure.string :as str]))" '[seon.store :as store])]
      (is (:ok? r))
      (is (:changed? r))
      (is (= ["seon.store"] (:added r)))
      (is (clojure.string/includes? (:src r) "[seon.store :as store]"))))
  (testing "idempotent: adding twice is a no-op"
    (let [once (:src (cstns/add-require-src "(ns foo (:require [clojure.string :as str]))" '[seon.store :as store]))
          twice (cstns/add-require-src once '[seon.store :as store])]
      (is (:ok? twice))
      (is (false? (:changed? twice)))
      (is (= [] (:added twice)))
      (is (= once (:src twice)))))
  (testing "adds require clause when absent"
    (let [r (cstns/add-require-src "(ns foo)\n(defn g [] 1)" '[seon.store :as store])]
      (is (:ok? r))
      (is (:changed? r))
      (is (clojure.string/includes? (:src r) "(:require [seon.store :as store])")))))
