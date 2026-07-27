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
    ;; STRING keys at the wire boundary, no keyword fallback: the body
    ;; is the one :any third-party document and we project out of it
    ;; immediately rather than pretending it is Clojure data
    (is (= "probe-model" (get body "model")))
    (is (vector? (get body "messages"))
        "one non-streaming chat completion, nothing else")
    (is (false? (get body "stream")))))

(deftest a-foreign-document-either-yields-text-or-says-why
  (testing "the shape a provider actually returns"
    (is (= {:seon.ai/text "hello there"}
           (ai/completion-text
            {"choices" [{"message" {"role" "assistant"
                                    "content" "hello there"}}]}))))
  (testing "and anything else is an error value, never nil"
    (doseq [body [{} {"choices" []} {"choices" [{"message" {}}]}
                  "a string" nil 42 {"error" {"message" "nope"}}
                  ;; keyword keys are NOT the wire shape and must not
                  ;; sneak through a fallback
                  {:choices [{:message {:content "hi"}}]}]]
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

;;; ---------------------------------------------------------------------------
;;; The evidence, and the disposition computed from it
;;; ---------------------------------------------------------------------------

;;; THE QUESTION EVERY ONE OF THESE ASKS is "did this call cost
;;; anything?", because that — not the error's kind — is what the
;;; no-retry ruling turns on. A kind list would be the hand-maintained
;;; classification the standing rule bans, and it could not answer this
;;; question at all: two `::transport-failure` values with different
;;; transmission phase have opposite dispositions.

(defn- disposed
  [value backup?]
  (ai/disposition {:seon.error/value value :seon.ai/backup? backup?}))

(defn- failure
  "One error value carrying the evidence the leaf would have recorded."
  [kind evidence]
  {:seon.error/kind kind
   :seon.error/message "probe"
   :seon.error/data evidence})

(deftest output-evidence-is-always-terminal
  ;; a 2xx body we could not parse is generated text somebody paid for
  (doseq [backup? [true false]]
    (is (= :fail
           (disposed (failure :seon.ai/unparseable-body
                              {:seon.ai/error-class :response
                               :seon.ai/http-status 200
                               :seon.ai/request-transmitted? true
                               :seon.ai/response-started? true
                               :seon.ai/output-observed? true})
                     backup?))
        "no backup and no backoff re-calls a call that produced output")))

(deftest a-transmitted-request-with-no-answer-is-ambiguously-paid
  ;; the strictest reading of the ruling rather than the convenient one:
  ;; "cannot prove it was free" is not "it was free"
  (doseq [backup? [true false]]
    (is (= :fail
           (disposed (failure :seon.ai/timeout
                              {:seon.ai/error-class :timeout
                               :seon.ai/request-transmitted? true
                               :seon.ai/response-started? false
                               :seon.ai/output-observed? false})
                     backup?))
        "a deadline that fired after transmission does NOT fail over")
    (is (= :fail
           (disposed (failure :seon.ai/transport-failure
                              {:seon.ai/error-class :transport-unknown
                               :seon.ai/request-transmitted? true
                               :seon.ai/response-started? false
                               :seon.ai/output-observed? false})
                     backup?))
        "and neither does a transport loss of unknown phase")))

(deftest a-call-that-never-left-the-machine-costs-nothing
  (doseq [error-class [:credential :transport-before-send]]
    (let [value (failure :seon.ai/transport-failure
                         {:seon.ai/error-class error-class
                          :seon.ai/request-transmitted? false
                          :seon.ai/response-started? false
                          :seon.ai/output-observed? false})]
      (is (= :failover-now (disposed value true))
          "a backup is called immediately — no retry, no sleep"))))

(deftest a-free-rejection-splits-by-what-the-provider-said
  (let [rejection (fn [error-class]
                    (failure :seon.ai/provider-error
                             {:seon.ai/error-class error-class
                              :seon.ai/request-transmitted? true
                              :seon.ai/response-started? true
                              :seon.ai/output-observed? false}))]
    (testing "not now — fail over, else wait"
      (doseq [error-class [:rate-limit :server]]
        (is (= :failover-now (disposed (rejection error-class) true)))
        (is (= :backoff (disposed (rejection error-class) false)))))
    (testing "not here — a different target may work, the same one never
    will, so this is the one free class that must not back off"
      (doseq [error-class [:authentication :authorization :model]]
        (is (= :failover-now (disposed (rejection error-class) true)))
        (is (= :fail (disposed (rejection error-class) false)))))
    (testing "not this — a backup would reject the same request"
      (is (= :fail (disposed (rejection :request) true)))
      (is (= :fail (disposed (rejection :request) false))))))

(deftest backoff-happens-only-where-repeating-can-help
  ;; the whole point of the split: `:backoff` appears for exactly the
  ;; classes where the same target, later, is a different answer
  (let [backoff-classes
        (into #{}
              (filter (fn [error-class]
                        (= :backoff
                           (disposed (failure :seon.ai/provider-error
                                              {:seon.ai/error-class error-class
                                               :seon.ai/request-transmitted?
                                               (not= :transport-before-send
                                                     error-class)
                                               :seon.ai/response-started? false
                                               :seon.ai/output-observed? false})
                                     false))))
              [:credential :transport-before-send :transport-unknown :timeout
               :rate-limit :server :authentication :authorization :model
               :request :response])]
    (is (= #{:rate-limit :server :transport-before-send} backoff-classes))))

(deftest the-leaf-records-phase-from-the-jdks-own-taxonomy
  ;; a real call to a port nothing listens on: the JDK raises
  ;; ConnectException, which PROVES nothing was transmitted
  (let [value (ai/complete {:seon.ai/endpoint "http://127.0.0.1:1/v1"
                            :seon.ai/model "probe"
                            :seon.ai/api-key-variable "SEON_AI_TEST_KEY"
                            :seon.ai/prompt "hello"
                            :seon.ai/timeout-ms 500})]
    (when (= :seon.ai/transport-failure (:seon.error/kind value))
      (let [evidence (:seon.error/data value)]
        (is (false? (:seon.ai/request-transmitted? evidence)))
        (is (= :transport-before-send (:seon.ai/error-class evidence)))
        (is (= :failover-now (disposed value true))
            "so a backup is eligible — this is the zero-cost case")))))

(deftest a-missing-credential-is-provably-free
  (let [value (ai/complete {:seon.ai/endpoint "http://127.0.0.1:1/v1"
                            :seon.ai/model "probe"
                            :seon.ai/api-key-variable "SEON_AI_ABSENT_KEY_PROBE"
                            :seon.ai/prompt "hello"
                            :seon.ai/timeout-ms 500})]
    (is (= :seon.ai/no-credential (:seon.error/kind value)))
    (is (false? (:seon.ai/request-transmitted? (:seon.error/data value)))
        "no network call happened at all")
    (is (= :failover-now (disposed value true)))
    (is (= :fail (disposed value false))
        "and with no backup there is nothing to wait for — a missing
         credential is not a condition that improves with time")))
