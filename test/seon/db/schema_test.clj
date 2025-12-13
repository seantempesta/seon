(ns seon.db.schema-test
  "Tests for Malli schema definitions and generators."
  (:require [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [malli.generator :as mg]
            [seon.db.schema :as schema]
            [seon.generators :as gen]))

;;; ---------------------------------------------------------------------------
;;; Schema Validation Tests
;;; ---------------------------------------------------------------------------

(deftest option-quote-schema-test
  (testing "Valid option quote passes validation"
    (let [quote (schema/generate schema/OptionQuote)]
      (is (schema/validate schema/OptionQuote quote)
          "Generated quote should be valid")))

  (testing "Missing required fields fail validation"
    (let [invalid {:asset/ticker "AAPL"}]
      (is (not (schema/validate schema/OptionQuote invalid))
          "Quote missing required fields should fail")))

  (testing "Invalid option type fails validation"
    (let [quote (assoc (schema/generate schema/OptionQuote)
                       :option/type :invalid)]
      (is (not (schema/validate schema/OptionQuote quote))
          "Invalid option type should fail"))))

(deftest greeks-schema-test
  (testing "Valid Greeks map passes validation"
    (let [greeks (schema/generate schema/Greeks)]
      (is (schema/validate schema/Greeks greeks))))

  (testing "Delta bounds are enforced"
    (let [invalid {:delta 1.5 :gamma 0.1 :vega 10 :theta -0.5}]
      (is (not (schema/validate schema/Greeks invalid))
          "Delta > 1 should fail"))))

(deftest iv-surface-schema-test
  (testing "Valid IV surface passes validation"
    (let [surface (schema/generate schema/IVSurface)]
      (is (schema/validate schema/IVSurface surface)))))

(deftest trading-signal-schema-test
  (testing "Valid trading signal passes validation"
    (let [signal (schema/generate schema/TradingSignal)]
      (is (schema/validate schema/TradingSignal signal))))

  (testing "Invalid strategy fails validation"
    (let [signal (assoc (schema/generate schema/TradingSignal)
                        :signal/strategy :invalid-strategy)]
      (is (not (schema/validate schema/TradingSignal signal))))))

;;; ---------------------------------------------------------------------------
;;; Property-Based Tests
;;; ---------------------------------------------------------------------------

(defspec generated-quotes-are-valid 100
  (prop/for-all [quote (mg/generator schema/OptionQuote
                                     {:registry @schema/registry})]
    (schema/validate schema/OptionQuote quote)))

(defspec generated-greeks-are-valid 100
  (prop/for-all [greeks (mg/generator schema/Greeks
                                      {:registry @schema/registry})]
    (schema/validate schema/Greeks greeks)))

(defspec generated-surfaces-are-valid 50
  (prop/for-all [surface (mg/generator schema/IVSurface
                                       {:registry @schema/registry
                                        :size 5})]
    (schema/validate schema/IVSurface surface)))

(defspec generated-signals-are-valid 100
  (prop/for-all [signal (mg/generator schema/TradingSignal
                                      {:registry @schema/registry})]
    (schema/validate schema/TradingSignal signal)))

;;; ---------------------------------------------------------------------------
;;; Custom Generator Tests
;;; ---------------------------------------------------------------------------

(deftest custom-generators-produce-valid-data
  (testing "gen-valid-option-quote produces valid quotes"
    (let [quotes (gen/sample gen/gen-valid-option-quote 20)]
      (doseq [q quotes]
        (is (schema/validate schema/OptionQuote q)
            (str "Quote should be valid: " (schema/explain schema/OptionQuote q))))))

  (testing "gen-greeks produces valid Greeks"
    (let [greeks-samples (gen/sample gen/gen-greeks 20)]
      (doseq [g greeks-samples]
        (is (schema/validate schema/Greeks g))))))

;;; ---------------------------------------------------------------------------
;;; Regression Tests
;;; ---------------------------------------------------------------------------

(deftest schema-explain-provides-useful-errors
  (testing "Explain identifies specific field errors"
    (let [invalid {:xt/id (java.util.UUID/randomUUID)
                   :asset/ticker "AAPL"
                   :option/id "TEST"
                   :option/strike "not-a-number"  ; Invalid
                   :option/type :call
                   :option/expiry (java.time.Instant/now)
                   :quote/bid 1.0
                   :quote/ask 1.5}
          explanation (schema/explain schema/OptionQuote invalid)]
      (is (some? explanation)
          "Should provide explanation for invalid data")
      (is (some #(= :option/strike (last (:in %)))
                (:errors explanation))
          "Should identify the invalid field"))))
