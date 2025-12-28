(ns seon.ai.gemini-test
  "Tests for Gemini API integration.

   Tests cover:
   1. Schema registration - schemas are in global registry and valid
   2. Schema validation - data validates against schemas correctly
   3. Schema generation - can generate valid sample data
   4. Function metadata - :malli/schema is accessible on vars
   5. Schema collection - mi/collect! registers function schemas
   6. API key handling - proper error handling for missing keys"
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [malli.generator :as mg]
   [malli.instrument :as mi]
   [seon.ai.gemini :as gemini]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration Tests
;;; ---------------------------------------------------------------------------

(deftest schema-registration-test
  (testing "Gemini schemas are registered in global registry"
    (doseq [k gemini/gemini-schema-keys]
      (is (some? (m/schema k))
          (str "Schema " k " should be registered"))))

  (testing "Schema introspection function works"
    (is (some? (gemini/schema :gemini/api-key)))
    (is (some? (gemini/schema :gemini/prompt)))
    (is (some? (gemini/schema :gemini/model)))
    (is (some? (gemini/schema :gemini/options)))
    (is (some? (gemini/schema :gemini/response)))
    (is (nil? (gemini/schema :gemini/nonexistent)))))

(deftest schema-validity-test
  (testing "All registered schemas are valid Malli schemas"
    (doseq [k gemini/gemini-schema-keys]
      (is (m/schema? (m/schema k))
          (str "Schema " k " should be a valid Malli schema")))))

;;; ---------------------------------------------------------------------------
;;; Schema Generation Tests
;;; ---------------------------------------------------------------------------

(deftest schema-generation-test
  (testing "Can generate :gemini/api-key"
    (let [generated (mg/generate :gemini/api-key)]
      (is (string? generated))
      (is (pos? (count generated)))))

  (testing "Can generate :gemini/prompt"
    (let [generated (mg/generate :gemini/prompt)]
      (is (string? generated))
      (is (pos? (count generated)))))

  (testing "Can generate :gemini/model"
    (let [generated (mg/generate :gemini/model)]
      (is (string? generated))
      (is (#{"gemini-2.5-flash" "gemini-2.5-pro"
             "gemini-3-flash" "gemini-3-pro-preview"} generated))))

  (testing "Can generate :gemini/options"
    (let [generated (mg/generate :gemini/options)]
      (is (map? generated))))

  (testing "Can generate :gemini/response"
    (let [generated (mg/generate :gemini/response)]
      (is (map? generated))
      (is (contains? generated :text))
      (is (string? (:text generated))))))

(deftest complex-schema-generation-test
  (testing "Can generate multiple valid options"
    (let [samples (mg/sample :gemini/options {:size 10})]
      (is (= 10 (count samples)))
      (doseq [opt samples]
        (is (m/validate :gemini/options opt)))))

  (testing "Can generate multiple valid responses"
    (let [samples (mg/sample :gemini/response {:size 10})]
      (is (= 10 (count samples)))
      (doseq [resp samples]
        (is (m/validate :gemini/response resp))))))

;;; ---------------------------------------------------------------------------
;;; Function Schema Metadata Tests
;;; ---------------------------------------------------------------------------

(deftest function-schema-metadata-test
  (testing "generate has :malli/schema metadata"
    (let [schema (:malli/schema (meta #'gemini/generate))]
      (is (some? schema) "Should have :malli/schema metadata")
      (is (vector? schema) "Schema should be a vector")
      (is (= :=> (first schema)) "Should be a function schema")))

  (testing "generate-with-search has :malli/schema metadata"
    (let [schema (:malli/schema (meta #'gemini/generate-with-search))]
      (is (some? schema))
      (is (= :=> (first schema)))))

  (testing "generate-with-code has :malli/schema metadata"
    (let [schema (:malli/schema (meta #'gemini/generate-with-code))]
      (is (some? schema))
      (is (= :=> (first schema)))))

  (testing "ask has :malli/schema metadata"
    (let [schema (:malli/schema (meta #'gemini/ask))]
      (is (some? schema))
      (is (= :=> (first schema)))))

  (testing "search has :malli/schema metadata"
    (let [schema (:malli/schema (meta #'gemini/search))]
      (is (some? schema))
      (is (= :=> (first schema)))))

  (testing "calculate has :malli/schema metadata"
    (let [schema (:malli/schema (meta #'gemini/calculate))]
      (is (some? schema))
      (is (= :=> (first schema))))))

;;; ---------------------------------------------------------------------------
;;; Schema Collection Tests
;;; ---------------------------------------------------------------------------

(deftest schema-collection-test
  (testing "mi/collect! registers function schemas"
    (mi/collect! {:ns 'seon.ai.gemini})
    (let [fn-schemas (m/function-schemas)
          gemini-schemas (get fn-schemas 'seon.ai.gemini)]
      (is (some? gemini-schemas)
          "Should have schemas for seon.ai.gemini namespace")
      (is (contains? gemini-schemas 'generate))
      (is (contains? gemini-schemas 'generate-with-search))
      (is (contains? gemini-schemas 'generate-with-code))
      (is (contains? gemini-schemas 'ask))
      (is (contains? gemini-schemas 'search))
      (is (contains? gemini-schemas 'calculate))))

  (testing "Collected schemas have correct structure"
    (let [generate-schema (get-in (m/function-schemas)
                                  ['seon.ai.gemini 'generate])]
      (is (some? generate-schema))
      (is (contains? generate-schema :schema))
      (is (m/schema? (:schema generate-schema))))))

;;; ---------------------------------------------------------------------------
;;; Response Validation Tests
;;; ---------------------------------------------------------------------------

(deftest response-validation-test
  (testing "Valid success response passes validation"
    (let [response {:text "Hello, world!"
                    :grounding-metadata nil
                    :code-results nil
                    :usage-metadata {:promptTokenCount 10
                                     :candidatesTokenCount 5
                                     :totalTokenCount 15}}]
      (is (m/validate :gemini/response response))))

  (testing "Minimal success response passes validation"
    (let [response {:text "Hello, world!"}]
      (is (m/validate :gemini/response response))))

  (testing "Valid error response passes validation"
    (let [response {:text ""
                    :error {:status 401
                            :message "Invalid API key"}}]
      (is (m/validate :gemini/response response))))

  (testing "Response with grounding metadata passes validation"
    (let [response {:text "According to recent sources..."
                    :grounding-metadata
                    {:webSearchQueries ["test query"]
                     :groundingChunks [{:web {:uri "https://example.com"
                                              :title "Example"}}]}}]
      (is (m/validate :gemini/response response))))

  (testing "Response with code results passes validation"
    (let [response {:text "The result is 42"
                    :code-results [{:outcome "OUTCOME_OK"
                                    :output "42"}]}]
      (is (m/validate :gemini/response response)))))

;;; ---------------------------------------------------------------------------
;;; Options Validation Tests
;;; ---------------------------------------------------------------------------

(deftest options-validation-test
  (testing "Empty options map is valid"
    (is (m/validate :gemini/options {})))

  (testing "Options with model is valid"
    (is (m/validate :gemini/options {:model "gemini-2.5-flash"})))

  (testing "Options with timeout is valid"
    (is (m/validate :gemini/options {:timeout 30000})))

  (testing "Options with tools is valid"
    (is (m/validate :gemini/options {:tools [{:google_search {}}]}))
    (is (m/validate :gemini/options {:tools [{:code_execution {}}]})))

  (testing "Options with thinking-level is valid"
    (is (m/validate :gemini/options {:thinking-level "high"})))

  (testing "Full options map is valid"
    (is (m/validate :gemini/options
                    {:model "gemini-3-flash"
                     :timeout 45000
                     :tools [{:google_search {}}]
                     :thinking-level "medium"
                     :system-instruction "You are a helpful assistant."}))))

(deftest options-validation-failures-test
  (testing "Invalid model fails validation"
    (is (not (m/validate :gemini/options {:model "invalid-model"}))))

  (testing "Invalid timeout fails validation"
    (is (not (m/validate :gemini/options {:timeout 100})))    ; below minimum
    (is (not (m/validate :gemini/options {:timeout 700000})))) ; above maximum

  (testing "Invalid thinking-level fails validation"
    (is (not (m/validate :gemini/options {:thinking-level "extreme"})))))

;;; ---------------------------------------------------------------------------
;;; API Key Handling Tests
;;; ---------------------------------------------------------------------------

(deftest api-key-handling-test
  (testing "ask throws when no API key available"
    (binding [gemini/*api-key* nil]
      (with-redefs [seon.ai.gemini/resolve-api-key (fn [_] nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Gemini API key available"
                              (gemini/ask "test" {}))))))

  (testing "search throws when no API key available"
    (binding [gemini/*api-key* nil]
      (with-redefs [seon.ai.gemini/resolve-api-key (fn [_] nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Gemini API key available"
                              (gemini/search "test" {}))))))

  (testing "calculate throws when no API key available"
    (binding [gemini/*api-key* nil]
      (with-redefs [seon.ai.gemini/resolve-api-key (fn [_] nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Gemini API key available"
                              (gemini/calculate "test" {})))))))

;;; ---------------------------------------------------------------------------
;;; Generative Schema Check
;;; ---------------------------------------------------------------------------

(deftest generative-schema-check-test
  (testing "Function schemas are valid and generatable"
    (mi/collect! {:ns 'seon.ai.gemini})
    (let [fn-schemas (get (m/function-schemas) 'seon.ai.gemini)]
      (doseq [[fn-sym schema-data] fn-schemas]
        (let [schema (:schema schema-data)
              input-schema (second (m/form schema))]
          (testing (str "Schema for " fn-sym " is generatable")
            (is (some? (mg/generate input-schema))
                (str fn-sym " input schema should be generatable"))))))))

;;; ---------------------------------------------------------------------------
;;; Malli Schema Pattern Verification
;;; ---------------------------------------------------------------------------

(deftest malli-schema-pattern-verification
  (testing "Pattern: Schema in metadata is parseable"
    (let [schema (:malli/schema (meta #'gemini/generate))]
      (is (m/schema? (m/schema schema)))
      (is (= :=> (first schema)))
      (is (= :cat (first (second schema))))))

  (testing "Pattern: Collected schemas match metadata"
    (mi/collect! {:ns 'seon.ai.gemini})
    (let [metadata-schema (:malli/schema (meta #'gemini/generate))
          collected-schema (m/form (:schema (get-in (m/function-schemas)
                                                    ['seon.ai.gemini 'generate])))]
      (is (= metadata-schema collected-schema)
          "Collected schema should match var metadata"))))

;;; ---------------------------------------------------------------------------
;;; Constants and Configuration Tests
;;; ---------------------------------------------------------------------------

(deftest constants-test
  (testing "Base URL is correct"
    (is (= "https://generativelanguage.googleapis.com/v1beta" gemini/base-url)))

  (testing "Default timeout is reasonable"
    (is (= 60000 gemini/default-timeout-ms)))

  (testing "Default model is valid"
    (is (m/validate :gemini/model gemini/default-model))))
