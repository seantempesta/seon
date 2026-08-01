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
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.ai :as ai]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(def ^:private base
  {:seon.ai/endpoint "http://127.0.0.1:1/chat/completions"
   :seon.ai/model "probe-model"
   :seon.ai/max-tokens 8192
   :seon.ai/api-key-variable "SEON_AI_TEST_KEY_ABSENT"
   :seon.ai/prompt "say hello"
   :seon.ai/timeout-ms 250})

(defn- error? [value]
  (and (map? value) (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))

;;; ---------------------------------------------------------------------------
;;; The descriptor rows: one derivation, two roles
;;; ---------------------------------------------------------------------------

(def ^:private dials
  "The shipped defaults — the real document, not a fixture of it. A
  suite that invents its own dial map cannot catch a default that
  stopped being derivable."
  (delay (config/defaults)))

(deftest the-shipped-cluster-has-one-target-and-no-backup
  (let [targets (ai/targets @dials)]
    (is (schema/valid-candidate-value? :seon.ai/targets targets))
    (is (= {:seon.ai/endpoint (:seon.config.ai/endpoint @dials)
            :seon.ai/model (:seon.config.ai/model @dials)
            :seon.ai/max-tokens (:seon.config.ai/max-tokens @dials)
            :seon.ai/api-key-variable (:seon.config.ai/api-key-variable @dials)
            :seon.ai/timeout-ms (:seon.config.ai/timeout-ms @dials)}
           (:seon.ai/primary targets))
        "the primary IS the four dials, and nothing reshapes them")
    (is (not (contains? targets :seon.ai/backup))
        "ABSENT, never nil — a nil backup would read as `configured, and
         broken` at every downstream site that asks whether one exists")
    (testing "and a target is a request minus what to say, which is why
    the call site is one assoc"
      (is (schema/valid-candidate-value?
           :seon.ai/request
           (assoc (:seon.ai/primary targets) :seon.ai/prompt "hello"))))
    (is (= 65536 (:seon.ai/max-tokens (:seon.ai/primary targets)))
        "thinking default-on has interim headroom while calibration runs")
    (is (not (contains? (:seon.ai/primary targets) :seon.ai/thinking))
        "absence reaches the wire as the provider's documented default")))

(deftest thinking-is-one-config-fact-with-three-wire-states
  (testing "absence leaves the provider default untouched"
    (let [body (ai/request-body base)]
      (is (not (contains? body "thinking")))
      (is (not (contains? body "reasoning_effort")))))
  (testing "explicit disable sends only the off switch"
    (let [body (ai/request-body (assoc base :seon.ai/thinking :disabled))]
      (is (= {"type" "disabled"} (get body "thinking")))
      (is (not (contains? body "reasoning_effort")))))
  (testing "each configured effort means explicit on plus that effort"
    (doseq [effort [:low :high :max]]
      (let [target (get-in (ai/targets
                            (assoc @dials :seon.config.ai/thinking effort))
                           [:seon.ai/primary])
            body (ai/request-body (assoc target :seon.ai/prompt "hello"))]
        (is (= effort (:seon.ai/thinking target)))
        (is (= {"type" "enabled"} (get body "thinking")))
        (is (= (name effort) (get body "reasoning_effort")))))))

(deftest no-auth-is-an-explicit-exclusive-target-shape
  (let [target {:seon.ai/endpoint "http://127.0.0.1:8090/v1/chat/completions"
                :seon.ai/model "local-model"
                :seon.ai/max-tokens 8192
                :seon.config.ai/no-auth true
                :seon.ai/timeout-ms 300000}]
    (is (schema/valid-candidate-value? :seon.ai/target target))
    (is (schema/valid-candidate-value?
         :seon.ai/request
         (assoc target :seon.ai/prompt "hello")))
    (is (not (schema/valid-candidate-value?
              :seon.ai/target
              (assoc target :seon.ai/api-key-variable "DUMMY")))
        "a descriptor cannot declare both authentication shapes")
    (is (not (schema/valid-candidate-value?
              :seon.ai/target
              (dissoc target :seon.config.ai/no-auth)))
        "omitting a credential does not silently mean no-auth")))

(deftest a-no-auth-config-row-assembles-and-sends-without-authorization
  (let [requests (atom [])
        targets (ai/targets
                 (-> @dials
                     (dissoc :seon.config.ai/api-key-variable)
                     (assoc :seon.config.ai/no-auth true)))
        target (:seon.ai/primary targets)
        outcome
        (with-redefs-fn
          {#'seon.ai/send-request
           (fn [request-data]
             (swap! requests conj request-data)
             {:seon.ai/text "local reply"})}
          #(ai/complete (assoc target :seon.ai/prompt "hello")))]
    (is (= true (:seon.config.ai/no-auth target))
        "assembly carries the descriptor's declared authentication state")
    (is (not (contains? target :seon.ai/api-key-variable))
        "the assembled target keeps exactly one authentication declaration")
    (is (schema/valid-candidate-value? :seon.ai/target target))
    (is (= {:seon.ai/text "local reply"} outcome))
    (is (= {"content-type" "application/json"}
           (:seon.ai.http/headers (first @requests)))
        "the F4 recorder seam observes no Authorization header")))

(deftest one-dial-configures-a-backup-and-the-rest-inherit
  ;; the shape that makes a PARTIAL backup unrepresentable: `model`
  ;; decides, everything else is an override
  (let [targets (ai/targets (assoc @dials :seon.config.ai.backup/model
                                   "deepseek-v4-pro"))
        {:seon.ai/keys [primary backup]} targets]
    (is (schema/valid-candidate-value? :seon.ai/targets targets))
    (is (.equals "deepseek-v4-pro" (:seon.ai/model backup)))
    (is (= (dissoc primary :seon.ai/model) (dissoc backup :seon.ai/model))
        "same provider, same credential, same deadline — one dial said
         everything that differs, so nothing was copied to drift")))

(deftest a-backup-at-another-provider-overrides-what-differs
  (let [{:seon.ai/keys [primary backup]}
        (ai/targets (assoc @dials
                           :seon.config.ai.backup/model "claude-probe"
                           :seon.config.ai.backup/endpoint
                           "https://example.invalid/v1/messages"
                           :seon.config.ai.backup/api-key-variable
                           "OTHER_PROVIDER_KEY"
                           :seon.config.ai.backup/timeout-ms 30000))]
    (is (= {:seon.ai/endpoint "https://example.invalid/v1/messages"
            :seon.ai/model "claude-probe"
            :seon.ai/max-tokens (:seon.config.ai/max-tokens @dials)
            :seon.ai/api-key-variable "OTHER_PROVIDER_KEY"
            :seon.ai/timeout-ms 30000}
           backup))
    (is (not= (:seon.ai/api-key-variable primary)
              (:seon.ai/api-key-variable backup))
        "and a second credential is a second VARIABLE NAME — never a key
         in the database, never in this repository")))

(deftest a-backup-dial-without-a-model-configures-nothing
  ;; the unrepresentable-partial rule, stated as a falsifier: setting
  ;; three of four backup dials cannot produce a half-built target
  (doseq [dial [:seon.config.ai.backup/endpoint
                :seon.config.ai.backup/api-key-variable]]
    (is (not (contains? (ai/targets (assoc @dials dial "set-but-alone"))
                        :seon.ai/backup))
        (str dial " alone is not a backup, and it is not half of one"))))

;;; ---------------------------------------------------------------------------
;;; The backoff schedule: a finite value, never a control structure
;;; ---------------------------------------------------------------------------

(def ^:private strategy
  {:seon.ai.retry/base-delay-ms 100
   :seon.ai.retry/multiplier 2.0
   :seon.ai.retry/jitter-fraction 0.0
   :seon.ai.retry/maximum-delay-ms 1000
   :seon.ai.retry/maximum-retries 5
   :seon.ai.retry/maximum-total-delay-ms 100000})

(deftest the-shipped-strategy-is-derived-from-the-shipped-dials
  (let [derived (ai/retry-strategy @dials)]
    (is (schema/valid-candidate-value? :seon.ai.retry/strategy derived))
    (testing "and the shipped budget is bounded by the RUN LEASE, not by
    patience: a backed-off turn that outlives its own claim is worse
    than a turn that gave up"
      (is (< (:seon.ai.retry/maximum-total-delay-ms derived) 60000)))))

(deftest with-no-jitter-the-schedule-is-exactly-the-doubling
  (is (= [100 200 400 800 1000]
         (ai/delays strategy (constantly 0.5)))
      "five retries, doubling, and the fifth CLAMPED at the maximum
       delay rather than continuing to 1600"))

(deftest each-bound-is-a-real-bound
  (testing "the retry count"
    (is (= 2 (count (ai/delays (assoc strategy :seon.ai.retry/maximum-retries 2)
                               (constantly 0.5))))))
  (testing "zero retries makes :backoff degenerate to :fail with no
  special case anywhere — the empty vector IS the behaviour"
    (is (= [] (ai/delays (assoc strategy :seon.ai.retry/maximum-retries 0)
                         (constantly 0.5)))))
  (testing "and the CUMULATIVE budget stops the schedule rather than
  trimming its last wait to fit: a shortened final wait is a wait
  nobody configured"
    (let [schedule (ai/delays
                    (assoc strategy :seon.ai.retry/maximum-total-delay-ms 250)
                    (constantly 0.5))]
      (is (= [100] schedule))
      (is (<= (reduce + 0 schedule) 250)))))

(defspec every-schedule-is-finite-bounded-and-inside-its-jitter-band 200
  (prop/for-all
   [base (gen/choose 1 500)
    retries (gen/choose 0 8)
    jitter (gen/elements [0.0 0.1 0.25 0.5 1.0])
    maximum (gen/choose 1 5000)
    budget (gen/choose 0 20000)
    randoms (gen/vector (gen/elements [0.0 0.25 0.5 0.75 0.999]) 1 12)]
   (let [strategy {:seon.ai.retry/base-delay-ms base
                   :seon.ai.retry/multiplier 2.0
                   :seon.ai.retry/jitter-fraction jitter
                   :seon.ai.retry/maximum-delay-ms maximum
                   :seon.ai.retry/maximum-retries retries
                   :seon.ai.retry/maximum-total-delay-ms budget}
         drawn (atom (cycle randoms))
         schedule (ai/delays strategy
                             (fn [] (let [v (first @drawn)]
                                      (swap! drawn rest)
                                      v)))]
     (and (schema/valid-candidate-value? :seon.ai.retry/delays schedule)
          ;; FINITE, and never longer than the configured count
          (<= (count schedule) retries)
          ;; every single wait is clamped
          (every? #(<= 0 % maximum) schedule)
          ;; the whole schedule fits the cumulative budget
          (<= (reduce + 0 schedule) budget)
          ;; and each wait is inside the jitter band around its own
          ;; undelayed geometric value, clamped — this is what proves
          ;; the randomness is SPREAD rather than merely added
          (every? true?
                  (map-indexed
                   (fn [index delay]
                     (let [raw (* (double base) (Math/pow 2.0 index))]
                       (<= (long (min (double maximum)
                                      (max 0.0 (* raw (- 1.0 jitter)))))
                           delay
                           (long (min (double maximum)
                                      (* raw (+ 1.0 jitter)))))))
                   schedule))))))

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
    (is (= 8192 (get body "max_tokens"))
        "the descriptor's positive output budget reaches the wire")
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

(def ^:private thinking-usage
  {"prompt_tokens" 91
   "completion_tokens" 42
   "total_tokens" 133
   "completion_tokens_details" {"reasoning_tokens" 40}})

(deftest a-thinking-response-retains-visible-text-finish-and-complete-usage
  (let [completion
        (ai/completion-text
         {"choices" [{"message" {"role" "assistant"
                                  "reasoning_content" "private reasoning"
                                  "content" "OK"}
                      "finish_reason" "stop"}]
          "usage" thinking-usage})]
    (is (= "OK" (:seon.ai/text completion)))
    (is (= "stop" (:seon.ai/finish-reason completion)))
    (is (= 42 (:seon.ai/tokens completion)))
    (is (= thinking-usage (:seon.ai/usage completion)))
    (is (not (contains? completion :seon.ai/reasoning-content))
        "reasoning is not reply text and no unsupported continuation is implied")))

(deftest a-reasoning-starved-response-is-a-named-evidenced-error
  (let [usage {"prompt_tokens" 104
               "completion_tokens" 8
               "total_tokens" 112
               "completion_tokens_details" {"reasoning_tokens" 8}}
        failure
        (ai/completion-text
         {"choices" [{"message" {"role" "assistant"
                                  "reasoning_content" "all reasoning"
                                  "content" ""}
                      "finish_reason" "length"}]
          "usage" usage})]
    (is (= :seon.ai/token-starvation (:seon.error/kind failure)))
    (is (= "length" (get-in failure
                             [:seon.error/data :seon.ai/finish-reason])))
    (is (= 8 (get-in failure
                      [:seon.error/data :seon.ai/usage
                       "completion_tokens_details" "reasoning_tokens"])))
    (is (schema/valid-candidate-value? :seon.error/value failure))))

(deftest streaming-reasoning-never-becomes-text-and-retains-terminal-evidence
  (let [lines [(str "data: {\"choices\":[{\"delta\":{"
                          "\"reasoning_content\":\"private\","
                          "\"content\":null},\"finish_reason\":null}]}")
               "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":null}]}"
               (str "data: {\"choices\":[{\"delta\":{\"content\":\"\"},"
                    "\"finish_reason\":\"stop\"}],\"usage\":"
                    (json/write-str thinking-usage) "}")
               "data: [DONE]"]
        seen (atom [])
        body (java.io.ByteArrayInputStream.
              (.getBytes (str/join "\n" lines) "UTF-8"))
        completion (#'seon.ai/streamed-completion
                    body #(swap! seen conj (:seon.ai/text %)))]
    (is (= ["OK"] @seen))
    (is (= "OK" (:seon.ai/text completion)))
    (is (= "stop" (:seon.ai/finish-reason completion)))
    (is (= thinking-usage (:seon.ai/usage completion)))))

(deftest a-reasoning-only-length-stream-is-the-same-named-error
  (let [usage {"completion_tokens" 1
               "completion_tokens_details" {"reasoning_tokens" 1}}
        lines [(str "data: {\"choices\":[{\"delta\":{"
                          "\"reasoning_content\":\"x\",\"content\":null},"
                          "\"finish_reason\":null}]}")
               (str "data: {\"choices\":[{\"delta\":{\"content\":\"\"},"
                    "\"finish_reason\":\"length\"}],\"usage\":"
                    (json/write-str usage) "}")
               "data: [DONE]"]
        body (java.io.ByteArrayInputStream.
              (.getBytes (str/join "\n" lines) "UTF-8"))
        failure (#'seon.ai/streamed-completion body nil)]
    (is (= :seon.ai/token-starvation (:seon.error/kind failure)))
    (is (= "length" (get-in failure
                             [:seon.error/data :seon.ai/finish-reason])))
    (is (= usage (get-in failure [:seon.error/data :seon.ai/usage])))))

;;; ---------------------------------------------------------------------------
;;; complete — one attempt, four failure shapes, never a throw
;;; ---------------------------------------------------------------------------

(deftest a-missing-credential-is-loud-in-the-value
  (let [requests (atom [])
        outcome
        (with-redefs-fn
          {#'seon.ai/send-request
           (fn [request]
             (swap! requests conj request)
             {:seon.ai/text "must not happen"})}
          #(ai/complete base))]
    (is (error? outcome))
    (is (= :seon.ai/no-credential (:seon.error/kind outcome)))
    (is (re-find #"SEON_AI_TEST_KEY_ABSENT" (:seon.error/message outcome))
        "the message names the variable that is unset")
    (is (empty? @requests)
        "a hosted target refuses before constructing or sending a request")))

(deftest explicit-no-auth-omits-the-authorization-header
  (let [requests (atom [])
        request (-> base
                    (dissoc :seon.ai/api-key-variable)
                    (assoc :seon.config.ai/no-auth true))
        outcome
        (with-redefs-fn
          {#'seon.ai/send-request
           (fn [request-data]
             (swap! requests conj request-data)
             {:seon.ai/text "local reply"})}
          #(ai/complete request))]
    (is (= {:seon.ai/text "local reply"} outcome))
    (is (= 1 (count @requests)))
    (is (= {"content-type" "application/json"}
           (:seon.ai.http/headers (first @requests)))
        "the captured request map contains no Authorization header")))

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

(def ^:private evidence-partitions
  [{::error-class :credential
    ::kind :seon.ai/no-credential
    ::transmitted? false}
   {::error-class :transport-before-send
    ::kind :seon.ai/transport-failure
    ::transmitted? false}
   {::error-class :transport-unknown
    ::kind :seon.ai/transport-failure
    ::transmitted? true}
   {::error-class :timeout
    ::kind :seon.ai/timeout
    ::transmitted? true}
   {::error-class :rate-limit
    ::kind :seon.ai/provider-error
    ::transmitted? true}
   {::error-class :server
    ::kind :seon.ai/provider-error
    ::transmitted? true}
   {::error-class :authentication
    ::kind :seon.ai/provider-error
    ::transmitted? true}
   {::error-class :authorization
    ::kind :seon.ai/provider-error
    ::transmitted? true}
   {::error-class :model
    ::kind :seon.ai/provider-error
    ::transmitted? true}
   {::error-class :request
    ::kind :seon.ai/provider-error
    ::transmitted? true}
   {::error-class :response
    ::kind :seon.ai/unparseable-body
    ::transmitted? true}
   {::error-class :response
    ::kind :seon.ai/unparseable-body
    ::transmitted? true
    ::output? true}])

(defn- expected-disposition
  [{::keys [error-class output?]} backup?]
  (cond
    output? :fail
    (contains? #{:rate-limit :server :transport-before-send} error-class)
    (if backup? :failover-now :backoff)
    (contains? #{:credential :authentication :authorization :model}
               error-class)
    (if backup? :failover-now :fail)
    :else :fail))

(defn- partition-value
  [{::keys [error-class kind transmitted? output?]}]
  (failure kind
           (cond-> {:seon.ai/error-class error-class
                    :seon.ai/request-transmitted? transmitted?
                    :seon.ai/response-started? (boolean
                                                (contains?
                                                 #{:rate-limit :server
                                                   :authentication
                                                   :authorization :model
                                                   :request :response}
                                                 error-class))
                    :seon.ai/output-observed? (boolean output?)}
             (contains? #{:rate-limit :server :authentication
                          :authorization :model :request :response}
                        error-class)
             (assoc :seon.ai/http-status
                    (if (= :response error-class) 200 503)))))

(deftest every-cost-evidence-partition-has-one-derived-disposition
  (test-support/assert-check!
   (tc/quick-check
    160
    (prop/for-all [partition (gen/elements evidence-partitions)
                   backup? gen/boolean]
      (let [value (partition-value partition)
            request {:seon.error/value value :seon.ai/backup? backup?}]
        (and (schema/valid-candidate-value? :seon.error/value value)
             (schema/valid-candidate-value? :seon.ai/disposition-request
                                            request)
             (= (expected-disposition partition backup?)
                (ai/disposition request)))))
    :seed 202607280401)
   "Every registered cost-evidence partition must derive one action."))

(deftest possibly-paid-work-never-repeats
  (let [value (partition-value
               {::error-class :response
                ::kind :seon.ai/unparseable-body
                ::transmitted? true
                ::output? true})]
    (is (= [:fail :fail]
           (mapv #(disposed value %) [false true])))))

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
