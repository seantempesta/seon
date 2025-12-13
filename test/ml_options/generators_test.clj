(ns ml-options.generators-test
  "Tests for custom generators.

  Note: Generators are primarily tested indirectly through schema-test.
  This file exists to satisfy the auto-test hook when generators.clj is edited."
  (:require [clojure.test :refer :all]
            [ml-options.generators :as gen]))

(deftest generators-load-test
  (testing "Generator namespace loads without errors"
    (is (some? gen/gen-valid-option-quote)
        "gen-valid-option-quote should be defined")
    (is (some? gen/gen-greeks)
        "gen-greeks should be defined")))

(deftest sample-generation-test
  (testing "Generators can produce samples"
    (let [quote (gen/generate gen/gen-valid-option-quote)]
      (is (map? quote) "Should generate a map")
      (is (contains? quote :xt/id) "Should have :xt/id")
      (is (contains? quote :quote/bid) "Should have :quote/bid")
      (is (contains? quote :quote/ask) "Should have :quote/ask")

      ;; Critical: verify no NaN values
      (is (not (Double/isNaN (:quote/bid quote)))
          "bid should not be NaN")
      (is (not (Double/isNaN (:quote/ask quote)))
          "ask should not be NaN"))))
