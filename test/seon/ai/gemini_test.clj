(ns seon.ai.gemini-test
  "Tests for Gemini API integration.

   Tests cover:
   1. Schema registration - schemas are in global registry and valid
   2. Schema validation - request/response validate correctly
   3. Schema generation - can generate valid sample data
   4. Function metadata - :malli/schema is accessible on vars
   5. Schema collection - mi/collect! registers function schemas
   6. API key handling - proper error handling for missing keys
   7. Provider pattern - Gemini follows seon.ai base schema pattern"
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [malli.generator :as mg]
   [malli.instrument :as mi]
   [seon.ai :as ai]
   [seon.ai.gemini :as gemini]
   [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration Tests
;;; ---------------------------------------------------------------------------

(deftest schema-registration-test
  (testing "Gemini schemas are registered in global registry"
    (let [gemini-schemas (schema/schemas-in-namespace "seon.ai.gemini")]
      (is (pos? (count gemini-schemas)) "Should have registered schemas")
      (is (contains? gemini-schemas ::gemini/prompt))
      (is (contains? gemini-schemas ::gemini/api-key))
      (is (contains? gemini-schemas ::gemini/model))
      (is (contains? gemini-schemas ::gemini/response))
      (is (contains? gemini-schemas ::gemini/ask-request))
      (is (contains? gemini-schemas ::gemini/generate-request)))))

(deftest schema-validity-test
  (testing "All registered Gemini schemas are valid Malli schemas"
    (doseq [[k _] (schema/schemas-in-namespace "seon.ai.gemini")]
      (is (m/schema? (m/schema k))
          (str "Schema " k " should be a valid Malli schema")))))

;;; ---------------------------------------------------------------------------
;;; Schema Generation Tests
;;; ---------------------------------------------------------------------------

(deftest schema-generation-test
  (testing "Can generate ::gemini/api-key"
    (let [generated (mg/generate ::gemini/api-key)]
      (is (string? generated))
      (is (pos? (count generated)))))

  (testing "Can generate ::gemini/prompt"
    (let [generated (mg/generate ::gemini/prompt)]
      (is (string? generated))
      (is (pos? (count generated)))))

  (testing "Can generate ::gemini/model"
    (let [generated (mg/generate ::gemini/model)]
      (is (string? generated))
      (is (#{"gemini-3-flash-preview" "gemini-3-pro-preview"} generated))))

  (testing "Can generate ::gemini/ask-request"
    (let [generated (mg/generate ::gemini/ask-request)]
      (is (map? generated))
      (is (contains? generated ::gemini/prompt))))

  (testing "Can generate ::gemini/response"
    (let [generated (mg/generate ::gemini/response)]
      (is (map? generated))
      (is (contains? generated ::gemini/text))
      (is (string? (::gemini/text generated))))))

(deftest complex-schema-generation-test
  (testing "Can generate multiple valid ask-requests"
    (let [samples (mg/sample ::gemini/ask-request {:size 10})]
      (is (= 10 (count samples)))
      (doseq [req samples]
        (is (m/validate ::gemini/ask-request req)))))

  (testing "Can generate multiple valid responses"
    (let [samples (mg/sample ::gemini/response {:size 10})]
      (is (= 10 (count samples)))
      (doseq [resp samples]
        (is (m/validate ::gemini/response resp))))))

;;; ---------------------------------------------------------------------------
;;; Function Schema Metadata Tests
;;; ---------------------------------------------------------------------------

(deftest function-schema-metadata-test
  (testing "generate has :malli/schema metadata"
    (let [schema (:malli/schema (meta #'gemini/generate))]
      (is (some? schema) "Should have :malli/schema metadata")
      (is (vector? schema) "Schema should be a vector")
      (is (= :=> (first schema)) "Should be a function schema")))

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
;;; Request/Response Validation Tests
;;; ---------------------------------------------------------------------------

(deftest request-validation-test
  (testing "Minimal ask-request is valid"
    (is (m/validate ::gemini/ask-request {::gemini/prompt "Hello"})))

  (testing "ask-request with options is valid"
    (is (m/validate ::gemini/ask-request
                    {::gemini/prompt "Hello"
                     ::gemini/model "gemini-3-pro-preview"
                     ::gemini/thinking-level "high"})))

  (testing "generate-request requires api-key"
    (is (not (m/validate ::gemini/generate-request {::gemini/prompt "Hello"})))
    (is (m/validate ::gemini/generate-request
                    {::gemini/api-key "test-key"
                     ::gemini/prompt "Hello"}))))

(deftest response-validation-test
  (testing "Valid success response passes validation"
    (let [response {::gemini/text "Hello, world!"
                    ::gemini/usage {:promptTokenCount 10
                                    :candidatesTokenCount 5
                                    :totalTokenCount 15}}]
      (is (m/validate ::gemini/response response))))

  (testing "Minimal success response passes validation"
    (let [response {::gemini/text "Hello, world!"}]
      (is (m/validate ::gemini/response response))))

  (testing "Valid error response passes validation"
    (let [response {::gemini/text ""
                    ::gemini/error {::gemini/status 401
                                    ::gemini/message "Invalid API key"}}]
      (is (m/validate ::gemini/response response))))

  (testing "Response with grounding metadata passes validation"
    (let [response {::gemini/text "According to recent sources..."
                    ::gemini/grounding-metadata
                    {:webSearchQueries ["test query"]
                     :groundingChunks [{:web {:uri "https://example.com"
                                              :title "Example"}}]}}]
      (is (m/validate ::gemini/response response))))

  (testing "Response with code results passes validation"
    (let [response {::gemini/text "The result is 42"
                    ::gemini/code-results [{:outcome "OUTCOME_OK"
                                            :output "42"}]}]
      (is (m/validate ::gemini/response response)))))

;;; ---------------------------------------------------------------------------
;;; API Key Handling Tests
;;; ---------------------------------------------------------------------------

(deftest api-key-handling-test
  (testing "ask throws when no API key available"
    (binding [gemini/*api-key* nil]
      (with-redefs [seon.ai.gemini/resolve-api-key (fn [_] nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Gemini API key available"
                              (gemini/ask {::gemini/prompt "test"}))))))

  (testing "search throws when no API key available"
    (binding [gemini/*api-key* nil]
      (with-redefs [seon.ai.gemini/resolve-api-key (fn [_] nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Gemini API key available"
                              (gemini/search {::gemini/prompt "test"}))))))

  (testing "calculate throws when no API key available"
    (binding [gemini/*api-key* nil]
      (with-redefs [seon.ai.gemini/resolve-api-key (fn [_] nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Gemini API key available"
                              (gemini/calculate {::gemini/prompt "test"})))))))

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
    (is (m/validate ::gemini/model gemini/default-model))))

;;; ---------------------------------------------------------------------------
;;; Provider Pattern Tests (seon.ai base schema relationship)
;;; ---------------------------------------------------------------------------

(deftest provider-pattern-test
  (testing "Gemini follows provider pattern - has corresponding base schemas"
    ;; Gemini has its own ::prompt that mirrors ::ai/prompt
    ;; Both are non-empty strings, keeping API consistency
    (is (m/validate ::gemini/prompt "test prompt"))
    (is (m/validate ::ai/prompt "test prompt"))
    ;; Same string validates against both
    (let [prompt "What is the meaning of life?"]
      (is (m/validate ::gemini/prompt prompt))
      (is (m/validate ::ai/prompt prompt))))

  (testing "Base schemas exist for common AI concepts"
    ;; These base schemas can be used when persisting Gemini data to XTDB
    (is (schema/registered? ::ai/input-tokens))
    (is (schema/registered? ::ai/output-tokens))
    (is (schema/registered? ::ai/cost-usd))
    (is (schema/registered? ::ai/status)))

  (testing "Gemini-specific schemas are distinct from base"
    ;; These are Gemini-only features not in base
    (is (schema/registered? ::gemini/thinking-level))
    (is (schema/registered? ::gemini/grounding-metadata))
    (is (schema/registered? ::gemini/code-result))
    (is (schema/registered? ::gemini/model)))

  (testing "Gemini namespace requires seon.ai"
    ;; Verify the require relationship is established
    ;; This is a documentation test - ensures the provider pattern is followed
    (is (find-ns 'seon.ai) "seon.ai namespace should be loaded")))
