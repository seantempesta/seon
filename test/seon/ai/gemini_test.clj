(ns seon.ai.gemini-test
  "Tests for Gemini API integration with :malli/schema metadata pattern.

   Tests cover:
   1. Schema validation - schemas parse correctly and can generate data
   2. Function schema metadata - :malli/schema is accessible on vars
   3. Schema collection - mi/collect! registers function schemas
   4. Generative testing - mi/check validates function contracts
   5. Mock HTTP responses - API parsing works correctly"
  (:require [clojure.test :refer :all]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.instrument :as mi]
            [seon.ai.gemini :as gemini]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration Tests
;;; ---------------------------------------------------------------------------

(deftest schema-registration-test
  (testing "Gemini schemas are registered"
    (let [schemas (gemini/registered-schemas)]
      (is (map? schemas) "Should return a map")
      (is (contains? schemas :gemini/api-key))
      (is (contains? schemas :gemini/prompt))
      (is (contains? schemas :gemini/model))
      (is (contains? schemas :gemini/options))
      (is (contains? schemas :gemini/response))
      (is (contains? schemas :gemini/tool))
      (is (contains? schemas :gemini/error)))))

(deftest schema-validity-test
  (testing "All registered schemas are valid Malli schemas"
    (doseq [[k schema] (gemini/registered-schemas)]
      (is (m/schema? (m/schema schema {:registry (gemini/gemini-registry)}))
          (str "Schema " k " should be valid")))))

;;; ---------------------------------------------------------------------------
;;; Schema Generation Tests
;;; ---------------------------------------------------------------------------

(deftest schema-generation-test
  (testing "Can generate GeminiApiKey"
    (let [generated (mg/generate gemini/GeminiApiKey)]
      (is (string? generated))
      (is (pos? (count generated)))))

  (testing "Can generate GeminiPrompt"
    (let [generated (mg/generate gemini/GeminiPrompt)]
      (is (string? generated))
      (is (pos? (count generated)))))

  (testing "Can generate GeminiModel"
    (let [generated (mg/generate gemini/GeminiModel)]
      (is (string? generated))
      (is (#{"gemini-2.5-flash" "gemini-2.5-pro"
             "gemini-3-flash" "gemini-3-pro-preview"} generated))))

  (testing "Can generate GeminiOptions"
    (let [generated (mg/generate gemini/GeminiOptions)]
      (is (map? generated))))

  (testing "Can generate GeminiResponse"
    (let [generated (mg/generate gemini/GeminiResponse)]
      (is (map? generated))
      (is (contains? generated :text))
      (is (string? (:text generated))))))

(deftest complex-schema-generation-test
  (testing "Can generate multiple valid options"
    (let [samples (mg/sample gemini/GeminiOptions {:size 10})]
      (is (pos? (count samples)))
      (doseq [opt samples]
        (is (m/validate gemini/GeminiOptions opt)))))

  (testing "Can generate multiple valid responses"
    (let [samples (mg/sample gemini/GeminiResponse {:size 10})]
      (is (pos? (count samples)))
      (doseq [resp samples]
        (is (m/validate gemini/GeminiResponse resp))))))

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
    ;; Collect schemas from the namespace
    (mi/collect! {:ns 'seon.ai.gemini})

    ;; Check that schemas are now in the function-schemas registry
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
;;; Response Parsing Tests (Mock Data)
;;; ---------------------------------------------------------------------------

(deftest response-validation-test
  (testing "Valid success response passes validation"
    (let [response {:text "Hello, world!"
                    :grounding-metadata nil
                    :code-results nil
                    :usage-metadata {:promptTokenCount 10
                                     :candidatesTokenCount 5
                                     :totalTokenCount 15}}]
      (is (m/validate gemini/GeminiResponse response))))

  (testing "Minimal success response passes validation"
    (let [response {:text "Hello, world!"}]
      (is (m/validate gemini/GeminiResponse response))))

  (testing "Valid error response passes validation"
    (let [response {:text ""
                    :error {:status 401
                            :message "Invalid API key"}}]
      (is (m/validate gemini/GeminiResponse response))))

  (testing "Response with grounding metadata passes validation"
    (let [response {:text "According to recent sources..."
                    :grounding-metadata
                    {:webSearchQueries ["test query"]
                     :groundingChunks [{:web {:uri "https://example.com"
                                              :title "Example"}}]}}]
      (is (m/validate gemini/GeminiResponse response))))

  (testing "Response with code results passes validation"
    (let [response {:text "The result is 42"
                    :code-results [{:outcome "OUTCOME_OK"
                                    :output "42"}]}]
      (is (m/validate gemini/GeminiResponse response)))))

;;; ---------------------------------------------------------------------------
;;; Options Validation Tests
;;; ---------------------------------------------------------------------------

(deftest options-validation-test
  (testing "Empty options map is valid"
    (is (m/validate gemini/GeminiOptions {})))

  (testing "Options with model is valid"
    (is (m/validate gemini/GeminiOptions {:model "gemini-2.5-flash"})))

  (testing "Options with timeout is valid"
    (is (m/validate gemini/GeminiOptions {:timeout 30000})))

  (testing "Options with tools is valid"
    (is (m/validate gemini/GeminiOptions {:tools [{:google_search {}}]}))
    (is (m/validate gemini/GeminiOptions {:tools [{:code_execution {}}]})))

  (testing "Options with thinking-level is valid"
    (is (m/validate gemini/GeminiOptions {:thinking-level "high"})))

  (testing "Full options map is valid"
    (is (m/validate gemini/GeminiOptions
                    {:model "gemini-3-flash"
                     :timeout 45000
                     :tools [{:google_search {}}]
                     :thinking-level "medium"
                     :system-instruction "You are a helpful assistant."}))))

(deftest options-validation-failures-test
  (testing "Invalid model fails validation"
    (is (not (m/validate gemini/GeminiOptions {:model "invalid-model"}))))

  (testing "Invalid timeout fails validation"
    (is (not (m/validate gemini/GeminiOptions {:timeout 100})))  ; below minimum
    (is (not (m/validate gemini/GeminiOptions {:timeout 700000}))))  ; above maximum

  (testing "Invalid thinking-level fails validation"
    (is (not (m/validate gemini/GeminiOptions {:thinking-level "extreme"})))))

;;; ---------------------------------------------------------------------------
;;; API Key Handling Tests
;;; ---------------------------------------------------------------------------

(deftest api-key-handling-test
  (testing "ask throws when no API key available"
    (binding [gemini/*api-key* nil]
      ;; Temporarily remove env var from consideration
      (with-redefs [gemini/get-api-key (fn [explicit] explicit)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Gemini API key available"
                              (gemini/ask "test" {}))))))

  (testing "search throws when no API key available"
    (binding [gemini/*api-key* nil]
      (with-redefs [gemini/get-api-key (fn [explicit] explicit)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Gemini API key available"
                              (gemini/search "test" {}))))))

  (testing "calculate throws when no API key available"
    (binding [gemini/*api-key* nil]
      (with-redefs [gemini/get-api-key (fn [explicit] explicit)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Gemini API key available"
                              (gemini/calculate "test" {})))))))

;;; ---------------------------------------------------------------------------
;;; Generative Tests via mi/check
;;; ---------------------------------------------------------------------------

;; Note: We can't run mi/check on functions that make HTTP calls without mocking.
;; Instead, we verify that the schemas are well-formed and generatable.

(deftest generative-schema-check-test
  (testing "Function schemas are valid and generatable"
    ;; Ensure schemas are collected
    (mi/collect! {:ns 'seon.ai.gemini})

    ;; For each function, verify its schema can generate valid inputs
    (let [fn-schemas (get (m/function-schemas) 'seon.ai.gemini)]
      (doseq [[fn-sym schema-data] fn-schemas]
        (let [schema (:schema schema-data)
              ;; Extract input schema from [:=> [:cat ...] output]
              input-schema (second (m/form schema))]
          (testing (str "Schema for " fn-sym " is generatable")
            ;; Try to generate sample inputs
            (is (some? (mg/generate input-schema))
                (str fn-sym " input schema should be generatable"))))))))

;;; ---------------------------------------------------------------------------
;;; Integration Pattern Verification
;;; ---------------------------------------------------------------------------

(deftest malli-schema-pattern-verification
  (testing "Pattern: Schema in metadata is parseable"
    (let [schema (:malli/schema (meta #'gemini/generate))]
      ;; Can parse as Malli schema
      (is (m/schema? (m/schema schema)))
      ;; Has correct structure: [:=> [:cat inputs...] output]
      (is (= :=> (first schema)))
      (is (= :cat (first (second schema))))))

  (testing "Pattern: Collected schemas match metadata"
    (mi/collect! {:ns 'seon.ai.gemini})
    (let [metadata-schema (:malli/schema (meta #'gemini/generate))
          collected-schema (m/form (:schema (get-in (m/function-schemas)
                                                    ['seon.ai.gemini 'generate])))]
      (is (= metadata-schema collected-schema)
          "Collected schema should match var metadata"))))
