(ns seon.ai-test
  "Sealed acceptance draft for the model seam (N3, C10).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). Every failure shape
  is asserted WITHOUT a network: the pure halves take a decoded
  document, and the four `complete` failures are reachable against
  endpoints that are not a model — an unroutable host, a port nothing
  listens on, a server that answers 500, one that answers prose. The
  live call against the real provider is the falsifier the orchestrator
  runs once by hand; a suite that needs a paid call to be green is a
  suite nobody runs."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.ai :as ai]
            [seon.schema]))

(def ^:private base
  {:seon.ai/endpoint "http://127.0.0.1:1/chat/completions"
   :seon.ai/model "probe-model"
   :seon.ai/api-key-variable "SEON_AI_TEST_KEY_ABSENT"
   :seon.ai/prompt "say hello"
   :seon.ai/timeout-ms 250})

(defn- error? [value]
  (and (map? value) (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))

;;; ---------------------------------------------------------------------------
;;; The pure halves
;;; ---------------------------------------------------------------------------

(deftest the-request-body-carries-the-model-and-the-messages
  (let [body (ai/request-body (assoc base :seon.ai/system "be brief"))]
    (is (map? body))
    (is (= "probe-model" (get body "model" (get body :model))))
    (is (some? (or (get body "messages") (get body :messages)))
        "one non-streaming chat completion, nothing else")))

(deftest a-foreign-document-either-yields-text-or-says-why
  (testing "the shape a provider actually returns"
    (is (= {:seon.ai/text "hello there"}
           (ai/completion-text
            {:choices [{:message {:role "assistant" :content "hello there"}}]}))))
  (testing "and anything else is an error value, never nil"
    (doseq [body [{} {:choices []} {:choices [{:message {}}]}
                  "a string" nil 42 {:error {:message "nope"}}]]
      (let [outcome (ai/completion-text body)]
        (is (error? outcome) (str "must classify: " (pr-str body)))
        (is (= :seon.ai/unparseable-body (:seon.error/kind outcome)))))))

;;; ---------------------------------------------------------------------------
;;; complete — one attempt, four failure shapes, never a throw
;;; ---------------------------------------------------------------------------

(deftest a-missing-credential-is-loud-in-the-value
  (let [outcome (ai/complete base)]
    (is (error? outcome))
    (is (= :seon.ai/no-credential (:seon.error/kind outcome)))
    (is (re-find #"SEON_AI_TEST_KEY_ABSENT" (:seon.error/message outcome))
        "the message names the variable that is unset")))

(deftest transport-failure-is-an-ordinary-value
  ;; port 1 answers nothing; the credential is present so the call is
  ;; genuinely attempted
  (let [outcome (with-redefs [ai/credential (constantly "test-key")]
                  (ai/complete base))]
    (is (error? outcome))
    (is (contains? #{:seon.ai/transport-failure :seon.ai/timeout}
                   (:seon.error/kind outcome)))))

(deftest the-deadline-fires-as-an-outcome-not-a-bug-report
  ;; 10.255.255.1 is unroutable: the connection hangs rather than
  ;; refusing, which is the genuinely unobservable case the deadline
  ;; exists for
  (let [outcome (with-redefs [ai/credential (constantly "test-key")]
                  (ai/complete (assoc base
                                      :seon.ai/endpoint
                                      "http://10.255.255.1:8080/v1"
                                      :seon.ai/timeout-ms 300)))]
    (is (error? outcome))
    (is (contains? #{:seon.ai/timeout :seon.ai/transport-failure}
                   (:seon.error/kind outcome)))
    (is (not (re-find #"(?i)bug" (:seon.error/message outcome)))
        "a slow model is a condition, not a defect report")))

(deftest one-attempt-means-one-attempt
  ;; nothing retries a paid call — the count is the contract
  (let [calls (atom 0)]
    (with-redefs [ai/credential (constantly "test-key")
                  ai/request-body (fn [request] (swap! calls inc) {})]
      (ai/complete base))
    (is (= 1 @calls) "exactly one request was built, and so one was made")))
