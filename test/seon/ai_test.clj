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
            [seon.db :as db]
            [malli.core :as m]
            [malli.generator :as mg]
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

(def ^:private json-number-generator
  (gen/one-of
   [gen/small-integer
    (gen/fmap (fn [n] (/ (double n) 10.0)) gen/small-integer)]))

(def ^:private json-value-generator
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen/vector inner 0 4)
       (gen/map gen/string-alphanumeric inner {:max-elements 4})]))
   (gen/one-of
    [(gen/return nil)
     gen/boolean
     json-number-generator
     gen/string-alphanumeric])))

(defn- compiled-json-value-schema []
  (let [projection (schema/build-projection (schema/registered-schemas))]
    (m/schema
     :seon.ai/json-value
     {:registry (:seon.schema.projection/registry projection)})))

(defn- json-round-trip? [value]
  (= value (json/read-str (json/write-str value))))

;;; ---------------------------------------------------------------------------
;;; The descriptor rows: one derivation, two roles
;;; ---------------------------------------------------------------------------

(def ^:private dials
  "The shipped defaults — the real document, not a fixture of it. A
  suite that invents its own dial map cannot catch a default that
  stopped being derivable."
  (delay (config/defaults)))

(deftest the-shipped-cluster-has-a-primary-and-configured-backup
  (let [targets (ai/targets @dials)]
    (is (schema/valid-candidate-value? :seon.ai/targets targets))
    (is (= {:seon.ai/endpoint (:seon.config.ai/endpoint @dials)
            :seon.ai/model (:seon.config.ai/model @dials)
            :seon.ai/max-tokens (:seon.config.ai/max-tokens @dials)
            :seon.ai/prompt-token-budget
            (:seon.config.ai/prompt-token-budget @dials)
            :seon.ai/chars-per-token-prior
            (:seon.config.ai/chars-per-token-prior @dials)
            :seon.ai/api-key-variable (:seon.config.ai/api-key-variable @dials)
            :seon.ai/thinking :disabled
            :seon.ai/timeout-ms (:seon.config.ai/timeout-ms @dials)}
           (:seon.ai/primary targets))
        "the primary retains every effective AI dial without reshaping it")
    (is (= "deepseek/deepseek-v4-flash-20260731"
           (get-in targets [:seon.ai/backup :seon.ai/model]))
        "the pinned 0731 GA slug, never the bare 0423 preview slug")
    (testing "and a target is a request minus what to say, which is why
    the call site is one assoc"
      (is (schema/valid-candidate-value?
           :seon.ai/request
           (assoc (:seon.ai/primary targets) :seon.ai/prompt "hello"))))
    (is (= 65536 (:seon.ai/max-tokens (:seon.ai/primary targets)))
        "the interim output budget remains until flash calibration lands")
    (is (= :disabled (:seon.ai/thinking (:seon.ai/primary targets)))
        "the shipped fast-turn posture explicitly disables thinking")))

(deftest the-shipped-openrouter-backup-resolves-through-its-provider-row
  (test-support/with-database
    (fn [connection]
      (let [backup (:seon.ai/backup (ai/targets @connection @dials))]
        (is (= {:seon.ai/model "deepseek/deepseek-v4-flash-20260731"
                :seon.ai/endpoint
                "https://openrouter.ai/api/v1/chat/completions"
                :seon.ai/api-key-variable "OPENROUTER_API_KEY"
                :seon.ai.model/output-token-wire-key "max_tokens"}
               (select-keys
                backup
                [:seon.ai/model
                 :seon.ai/endpoint
                 :seon.ai/api-key-variable
                 :seon.ai.model/output-token-wire-key])))
        (is (= (:seon.config.ai/max-tokens @dials)
               (:seon.ai/max-tokens backup))
            "the backup inherits the configured output bound")
        (is (= "EXPLICIT_BACKUP_KEY"
               (get-in
                (ai/targets
                 @connection
                 (assoc @dials
                        :seon.config.ai.backup/api-key-variable
                        "EXPLICIT_BACKUP_KEY"))
                [:seon.ai/backup :seon.ai/api-key-variable]))
            "an explicit backup credential still outranks its descriptor")
        (is (= "PRIMARY_OVERRIDE_KEY"
               (get-in
                (ai/targets
                 @connection
                 (assoc @dials
                        :seon.config.ai/api-key-variable
                        "PRIMARY_OVERRIDE_KEY"
                        :seon.config.ai.backup/model "deepseek-v4-pro"))
                [:seon.ai/backup :seon.ai/api-key-variable]))
            "same-provider backups inherit an explicit primary credential")))))

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

(deftest no-auth-targets-accrete-unused-authentication-data
  (let [target {:seon.ai/endpoint "http://127.0.0.1:8090/v1/chat/completions"
                :seon.ai/model "local-model"
                :seon.ai/max-tokens 8192
                :seon.config.ai/no-auth true
                :seon.ai/timeout-ms 300000}]
    (is (schema/valid-candidate-value? :seon.ai/target target))
    (is (schema/valid-candidate-value?
         :seon.ai/request
         (assoc target :seon.ai/prompt "hello")))
    (is (schema/valid-candidate-value?
         :seon.ai/target
         (assoc target :seon.ai/api-key-variable "DUMMY"))
        "each open union arm ignores data it does not declare")
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
    (is (= "local reply" (:seon.ai/text outcome)))
    (is (int? (:seon.ai.model/last-latency-ms outcome)))
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
            :seon.ai/prompt-token-budget
            (:seon.config.ai/prompt-token-budget @dials)
            :seon.ai/chars-per-token-prior
            (:seon.config.ai/chars-per-token-prior @dials)
            :seon.ai/api-key-variable "OTHER_PROVIDER_KEY"
            :seon.ai/thinking :disabled
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
    (is (not (contains? (ai/targets (-> @dials
                                        (dissoc :seon.config.ai.backup/model)
                                        (assoc dial "set-but-alone")))
                        :seon.ai/backup))
        (str dial " alone is not a backup, and it is not half of one"))))

(defn- seed-registry!
  [connection]
  (db/transact!
   connection
   [{:seon.ai.model/provider-id "test-provider"
     :seon.config.ai/endpoint "https://example.invalid/v1/chat/completions"
     :seon.config.ai/api-key-variable "TEST_PROVIDER_KEY"
     :seon.ai.model/openai-chat-completions true
     :seon.ai.model/output-token-wire-key "max_completion_tokens"}
    {:seon.ai.model/id "registered-model"
     :seon.ai.model/provider
     [:seon.ai.model/provider-id "test-provider"]
     :seon.ai.model/context-window-tokens 1000000
     :seon.ai.model/max-output-tokens 100
     :seon.ai.model/input-usd-per-mtok 0.25
     :seon.ai.model/output-usd-per-mtok 1.0
     :seon.ai.model/input-modalities #{:text}
     :seon.ai.model/thinking-dials #{:high}}]))

(deftest registry-resolution-accretes-provider-facts-onto-a-working-target
  (test-support/with-database
    (fn [connection]
      (seed-registry! connection)
      (let [settings (-> @dials
                         (assoc :seon.config.ai/model "registered-model"
                                :seon.config.ai/max-tokens 500
                                :seon.config.ai/thinking :disabled)
                         (dissoc :seon.config.ai/api-key-variable))
            target (:seon.ai/primary (ai/targets @connection settings))
            body (ai/request-body (assoc target :seon.ai/prompt "hello"))]
        (is (= "https://example.invalid/v1/chat/completions"
               (:seon.ai/endpoint target)))
        (is (= "TEST_PROVIDER_KEY" (:seon.ai/api-key-variable target)))
        (is (= 100 (:seon.ai/max-tokens target))
            "the declared model maximum bounds the effective request")
        (is (not (contains? target :seon.ai/thinking))
            "an inadmissible setting leaves the provider default untouched")
        (is (= 100 (get body "max_completion_tokens")))
        (is (not (contains? body "max_tokens"))
            "the provider row, not the model name, selects the wire field")))))

(deftest descriptor-resolution-normalizes-cardinality-many-before-membership
  (test-support/with-database
    (fn [connection]
      (seed-registry! connection)
      (let [settings (assoc @dials
                            :seon.config.ai/model "registered-model"
                            :seon.config.ai/thinking :high)
            target (:seon.ai/primary (ai/targets @connection settings))
            body (ai/request-body (assoc target :seon.ai/prompt "hello"))]
        (is (= :high (:seon.ai/thinking target))
            "a supported keyword is membership data, never a vector index")
        (is (= {"type" "enabled"} (get body "thinking")))
        (is (= "high" (get body "reasoning_effort")))))))

(deftest explicit-credential-selection-survives-provider-resolution
  (test-support/with-database
    (fn [connection]
      (seed-registry! connection)
      (let [missing-variable "SEON_AI_AGENT_OVERRIDE_VERIFIED_ABSENT"
            target
            (:seon.ai/primary
             (ai/targets @connection
                         (assoc @dials
                                :seon.config.ai/model "registered-model"
                                :seon.config.ai/api-key-variable
                                missing-variable)))
            requests (atom [])
            outcome
            (with-redefs-fn
              {#'seon.ai/send-request
               (fn [request]
                 (swap! requests conj request)
                 {:seon.ai/text "must not happen"})}
              #(ai/complete (assoc target :seon.ai/prompt "hello")))]
        (is (= missing-variable (:seon.ai/api-key-variable target)))
        (is (= :seon.ai/no-credential (:seon.error/kind outcome)))
        (is (empty? @requests)
            "the explicit absent credential refuses before the network")))))

(deftest provider-descriptor-fills-an-absent-credential-selection
  (test-support/with-database
    (fn [connection]
      (seed-registry! connection)
      (let [settings (-> @dials
                         (assoc :seon.config.ai/model "registered-model")
                         (dissoc :seon.config.ai/api-key-variable))
            target (:seon.ai/primary (ai/targets @connection settings))]
        (is (= "TEST_PROVIDER_KEY" (:seon.ai/api-key-variable target)))))))

(deftest a-missing-registry-row-leaves-the-working-call-target-unchanged
  (test-support/with-database
    (fn [connection]
      (let [settings (-> @dials
                         (assoc :seon.config.ai/model "unregistered-model")
                         (dissoc :seon.config.ai.backup/model))]
        (is (= (ai/targets settings)
               (ai/targets @connection settings)))))))

(deftest model-rows-are-queryable-open-and-rendered-without-wire-duplication
  (test-support/with-database
    (fn [connection]
      (seed-registry! connection)
      (let [model (ai/model-row @connection "registered-model")
            accreted (assoc model :seon.ai.model/future-tailored-fact
                            {:seon.ai.model/example true})
            ai-render (ai/registry-ai @connection)
            html-render (ai/registry-html @connection)]
        (is (= (conj (into #{} (keep :seon.ai.model/id)
                           (config/default-population))
                     "registered-model")
               (into #{} (map :seon.ai.model/id) (ai/models @connection)))
            "the roster is every shipped declared model row plus what this
             test seeded — DERIVED from the same initialization document, so
             adding or dropping a shipped model is caught without any list
             here to update")
        (is (not (contains? model :seon.config.ai/endpoint))
            "provider wire facts never duplicate onto model rows")
        (is (schema/valid-candidate-value? :seon.ai.model/entity accreted)
            "a tailored fact can accrete without breaking the open row")
        (is (str/includes? ai-render "input $0.250000/M"))
        (is (str/includes? ai-render "registered-model"))
        (is (schema/valid-candidate-value? :seon.render/hiccup html-render))))))

(deftest no-history-gauges-retain-current-and-drop-superseded-values
  (test-support/with-database
    (fn [connection]
      (seed-registry! connection)
      (let [first-observation
            {:seon.ai.model/id "registered-model"
             :seon.ai.model/last-used-at (java.util.Date. 1000)
             :seon.ai.model/last-latency-ms 1000
             :seon.ai/usage {"completion_tokens" 10}}
            second-observation
            {:seon.ai.model/id "registered-model"
             :seon.ai.model/last-used-at (java.util.Date. 2000)
             :seon.ai.model/last-latency-ms 200
             :seon.ai/usage {"completion_tokens" 20}}]
        (db/transact! connection
                      (ai/model-observation-tx @connection first-observation))
        (db/transact! connection
                      (ai/model-observation-tx @connection second-observation))
        (is (= 100.0
               (:seon.ai.model/last-tokens-per-second
                (ai/model-row @connection "registered-model"))))
        (is (= #{200}
               (set
                (db/q
                 '[:find [?latency ...]
                   :in $ ?model-id
                   :where
                   [?model :seon.ai.model/id ?model-id]
                   [?model :seon.ai.model/last-latency-ms ?latency]]
                 (db/history @connection)
                 "registered-model")))
            "the current noHistory value remains visible in history")
        (is (not
             (contains?
              (set
               (db/q
                '[:find [?latency ...]
                  :in $ ?model-id
                  :where
                  [?model :seon.ai.model/id ?model-id]
                  [?model :seon.ai.model/last-latency-ms ?latency]]
                (db/history @connection)
                "registered-model"))
              1000))
            "the superseded noHistory value is gone")))))

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
  (let [shipped-dials @dials
        derived (ai/retry-strategy shipped-dials)
        midpoint-schedule (ai/delays derived (constantly 0.5))]
    (is (schema/valid-candidate-value? :seon.ai.retry/strategy derived))
    (is (= {:seon.ai.retry/base-delay-ms 500
            :seon.ai.retry/multiplier 2.0
            :seon.ai.retry/jitter-fraction 0.25
            :seon.ai.retry/maximum-delay-ms 4000
            :seon.ai.retry/maximum-retries 2
            :seon.ai.retry/maximum-total-delay-ms 3000}
           derived)
        "every shipped retry dial reaches the one derived strategy")
    (is (= [500 1000] midpoint-schedule)
        "midpoint jitter preserves the exact base-plus-doubled schedule")
    (is (<= (reduce + 0 midpoint-schedule)
            (:seon.ai.retry/maximum-total-delay-ms derived))
        "the complete shipped schedule fits its cumulative wait budget")
    (is (< (:seon.ai.retry/maximum-total-delay-ms derived)
           (:seon.config.ai/timeout-ms shipped-dials))
        "the cumulative retry bound stays below the current call deadline")))

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

(deftest provider-documents-use-one-named-recursive-json-schema
  (let [payload
        {"prompt_tokens" 91
         "completion_tokens" 42
         "details" {"cached" true
                    "classes" [nil "hit" 1.5]}}]
    (doseq [value [nil false 7 1.25 "text" [] {} [nil {"ok" true}]]]
      (is (schema/valid-candidate-value? :seon.ai/json-value value)
          (pr-str value)))
    (is (schema/valid-candidate-value? :seon.ai/usage payload))
    (is (= payload (json/read-str (json/write-str payload))))
    (is (not (schema/valid-candidate-value?
              :seon.ai/json-value
              (fn [] :not-json))))))

(deftest generated-json-values-validate-and-round-trip
  (let [compiled (compiled-json-value-schema)
        schema-values (mg/sample compiled {:seed 2026080301 :size 30})]
    (is (seq schema-values))
    (is (every? #(m/validate compiled %) schema-values))
    (doseq [[json-partition seed generator]
            [[:null 2026080310 (gen/return nil)]
             [:boolean 2026080311 gen/boolean]
             [:number 2026080312 json-number-generator]
             [:string 2026080313 gen/string-alphanumeric]
             [:array 2026080314 (gen/vector json-value-generator 0 4)]
             [:object 2026080315 (gen/map gen/string-alphanumeric
                                          json-value-generator
                                          {:max-elements 4})]]]
      (let [check
            (tc/quick-check
             40
             (prop/for-all
              [value generator]
              (and (m/validate compiled value)
                   (json-round-trip? value)))
             :seed seed)]
        (is (true? (:result check))
            (str (name json-partition) " JSON round-trip failed: "
                 (pr-str check)))))))

(deftest the-request-body-carries-the-model-and-the-messages
  (let [body (ai/request-body (assoc base :seon.ai/system "be brief"))]
    (is (map? body))
    ;; STRING keys at the wire boundary, no keyword fallback: the body is one
    ;; named JSON provider document, not arbitrary Clojure data.
    (is (= "probe-model" (get body "model")))
    (is (= 8192 (get body "max_tokens"))
        "the descriptor's positive output budget reaches the wire")
    (is (vector? (get body "messages"))
        "one non-streaming chat completion, nothing else")
    (is (false? (get body "stream")))))

(deftest settings-resolve-by-one-agent-over-cluster-merge
  (let [cluster (test-support/effective-config)
        override {:seon.config.ai/model "planner-model"
                  :seon.config.ai/thinking :high}
        resolved (ai/settings cluster override)]
    (is (= "planner-model" (:seon.config.ai/model resolved)))
    (is (= :high (:seon.config.ai/thinking resolved)))
    (is (= (:seon.config.ai/endpoint cluster)
           (:seon.config.ai/endpoint resolved)))
    (is (schema/valid-candidate-value? :seon.config/effective resolved))))

(deftest agent-overlay-reads-only-derived-per-agent-attributes
  (test-support/with-database
    (fn [connection]
      (db/transact! connection
                  [{:seon.cluster.agent/id "planner"
                    :seon.config.ai/model "planner-model"
                    :seon.config.ai/thinking :high}])
      (is (= {:seon.config.ai/model "planner-model"
              :seon.config.ai/thinking :high}
             (ai/agent-overlay @connection "planner")))
      (is (= {} (ai/agent-overlay @connection "absent"))))))

(deftest registered-wire-triples-own-coercion-and-thinking-inertness
  (let [request (assoc base
                       :seon.ai/thinking :high
                       :seon.ai/temperature 0.4
                       :seon.ai/top-p 0.8
                       :seon.ai/stop ["END"]
                       :seon.ai/response-format :json-object)
        {:seon.ai/keys [sent inert]} (ai/wire-settings request)]
    (is (= {"max_tokens" 8192
            "thinking" {"type" "enabled"}
            "reasoning_effort" "high"
            "stop" ["END"]
            "response_format" {"type" "json_object"}}
           sent))
    (is (= #{:seon.config.ai/temperature :seon.config.ai/top-p} inert))
    (is (not (contains? (ai/request-body request) "temperature")))))

(deftest disabled-thinking-emits-sampling-and-omits-effort
  (let [body (ai/request-body
              (assoc base
                     :seon.ai/thinking :disabled
                     :seon.ai/temperature 0.4
                     :seon.ai/presence-penalty -0.5))]
    (is (= {"type" "disabled"} (get body "thinking")))
    (is (= 0.4 (get body "temperature")))
    (is (= -0.5 (get body "presence_penalty")))
    (is (not (contains? body "reasoning_effort")))))

(deftest extra-body-merges-last-but-cannot-rewrite-any-builder-owned-key
  (is (= true
         (get (ai/request-body
               (assoc base :seon.ai/extra-body-edn
                      "{\"vendor_option\" true}"))
              "vendor_option")))
  (doseq [protected ["model" "max_tokens" "authorization"]]
    (let [failure (ai/request-body
                   (assoc base :seon.ai/extra-body-edn
                          (pr-str {protected "override"})))]
      (is (= :seon.ai/extra-body-conflict (:seon.error/kind failure)))
      (is (= [protected]
             (get-in failure
                     [:seon.error/data :seon.ai/protected-keys])))))
  (is (= :seon.ai/invalid-extra-body
         (:seon.error/kind
          (ai/request-body (assoc base :seon.ai/extra-body-edn "[:not :a-map]"))))))

(deftest an-extra-body-conflict-refuses-before-the-http-leaf
  (let [sent (atom 0)
        outcome
        (with-redefs-fn
          {#'seon.ai/send-request (fn [_] (swap! sent inc))}
          #(ai/complete
            (assoc base :seon.ai/extra-body-edn
                   "{\"authorization\" \"leak\"}")))]
    (is (= :seon.ai/extra-body-conflict (:seon.error/kind outcome)))
    (is (zero? @sent))))

(deftest usage-cache-detail-is-normalized-only-at-read
  (is (= {:seon.ai.usage/prompt-tokens 91
          :seon.ai.usage/completion-tokens 42
          :seon.ai.usage/total-tokens 133
          :seon.ai.usage/cached-tokens 73}
         (ai/normalize-usage
          {"prompt_tokens" 91
           "completion_tokens" 42
           "total_tokens" 133
           "prompt_tokens_details" {"cached_tokens" 73}})))
  (is (= {:seon.ai.usage/prompt-tokens 91
          :seon.ai.usage/completion-tokens 42
          :seon.ai.usage/total-tokens 133
          :seon.ai.usage/cached-tokens 73}
         (ai/normalize-usage
          {"prompt_tokens" 91
           "completion_tokens" 42
           "total_tokens" 133
           "prompt_cache_hit_tokens" 73
           "prompt_cache_miss_tokens" 18}))))

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

(deftest a-thinking-response-retains-reasoning-visible-text-and-terminal-evidence
  (let [completion
        (ai/completion-text
         {"choices" [{"message" {"role" "assistant"
                                  "reasoning_content" "private reasoning"
                                  "content" "OK"}
                      "finish_reason" "stop"}]
          "usage" thinking-usage})]
    (is (= "OK" (:seon.ai/text completion)))
    (is (= "private reasoning" (:seon.ai/reasoning-content completion)))
    (is (= "stop" (:seon.ai/finish-reason completion)))
    (is (= 42 (:seon.ai/tokens completion)))
    (is (= thinking-usage (:seon.ai/usage completion)))
    (is (schema/valid-candidate-value? :seon.ai/completion completion))))

(deftest a-reasoning-only-response-is-a-named-evidenced-error
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
    (is (= :seon.ai/reasoning-without-answer
           (:seon.error/kind failure)))
    (is (= "length" (get-in failure
                             [:seon.error/data :seon.ai/finish-reason])))
    (is (= 8 (get-in failure
                      [:seon.error/data :seon.ai/usage
                       "completion_tokens_details" "reasoning_tokens"])))
    (is (= "all reasoning"
           (get-in failure
                   [:seon.error/data :seon.ai/reasoning-content])))
    (is (= 13 (get-in failure
                       [:seon.error/data :seon.ai/reasoning-received])))
    (is (zero? (get-in failure
                        [:seon.error/data :seon.ai/text-received])))
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
        completion (#'seon.ai/streamed-completion body #(swap! seen conj %))]
    (is (= [{:seon.ai/text ""
             :seon.ai/reasoning-partial "private"
             :seon.ai/tokens 1}
            {:seon.ai/text "OK"
             :seon.ai/reasoning-partial "private"
             :seon.ai/tokens 2}]
           @seen)
        "reasoning and visible text publish separately without mixing")
    (is (= "OK" (:seon.ai/text completion)))
    (is (= "private" (:seon.ai/reasoning-content completion)))
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
    (is (= :seon.ai/reasoning-without-answer
           (:seon.error/kind failure)))
    (is (= "length" (get-in failure
                             [:seon.error/data :seon.ai/finish-reason])))
    (is (= usage (get-in failure [:seon.error/data :seon.ai/usage])))
    (is (= "x" (get-in failure
                        [:seon.error/data :seon.ai/reasoning-content])))
    (is (= 1 (get-in failure
                      [:seon.error/data :seon.ai/reasoning-received])))
    (is (zero? (get-in failure
                        [:seon.error/data :seon.ai/text-received])))))

;;; ---------------------------------------------------------------------------
;;; A stream that ends before its terminal event
;;; ---------------------------------------------------------------------------

;;; THE CLASS: a transport failure part-way through a 2xx body used to
;;; unwind past the accumulated snapshot, so everything the provider had
;;; already generated (and charged for) was discarded and reported as an
;;; unreadable body. The construction that kills the class is that the
;;; read failure ENDS the line sequence instead of throwing through the
;;; fold — after which there is no code path on which a partial can be
;;; dropped, because the snapshot is simply in the caller's hands.

(defn- truncating-stream
  "A body that delivers `text` and then fails the way the JDK fails a
  stream whose connection went away."
  ([text]
   (truncating-stream text
                      (java.io.IOException. "connection reset by peer")))
  ([text cause]
   (let [delivered
         (java.io.ByteArrayInputStream. (.getBytes ^String text "UTF-8"))]
     (proxy [java.io.InputStream] []
       (read
         ([]
          (let [byte-read (.read delivered)]
            (if (neg? byte-read)
              (throw (java.io.IOException. "closed" cause))
              byte-read)))
         ([buffer offset length]
          (let [read (.read delivered buffer offset length)]
            (if (neg? read)
              (throw (java.io.IOException. "closed" cause))
              read))))))))

(defn- streamed-lines [& lines] (str (str/join "\n" lines) "\n"))

(deftest a-stream-that-ends-mid-body-keeps-every-character-that-arrived
  (let [seen (atom [])
        body (truncating-stream
              (streamed-lines
               "data: {\"choices\":[{\"delta\":{\"content\":\"(+ 1 2)\"}}]}"
               "data: {\"choices\":[{\"delta\":{\"content\":\" (+ 3\"}}]}"))
        completion (#'seon.ai/streamed-completion body #(swap! seen conj %))]
    (is (= "(+ 1 2) (+ 3" (:seon.ai/text completion))
        "the turn keeps what arrived rather than discarding a paid completion")
    (is (nil? (:seon.error/kind completion))
        "a partial completion is a completion, not a refusal")
    (is (= 2 (count @seen)) "every arrived delta still published to the sink")
    (let [truncation (:seon.ai/truncation completion)]
      (is (= :seon.ai/stream-truncated (:seon.error/kind truncation))
          "the truncation is a flat error value the caller can record")
      (is (= 12 (get-in truncation [:seon.error/data :seon.ai/text-received])))
      (is (= false (get-in truncation
                           [:seon.error/data :seon.ai/thread-interrupted?])))
      (is (= ["java.io.IOException: closed"
              "java.io.IOException: connection reset by peer"]
             (get-in truncation [:seon.error/data :seon.ai/cause-chain]))
          "the JDK's real cause is recorded, not just the word closed")
      (is (str/includes? (:seon.error/message truncation)
                         "connection reset by peer")
          "the rendered message names the cause the database holds"))))

(deftest a-stream-that-ends-before-any-text-is-named-truncation-not-bad-json
  (let [completion (#'seon.ai/streamed-completion (truncating-stream "") nil)]
    (is (= :seon.ai/stream-truncated (:seon.error/kind completion))
        "an early close is distinguished from a body that could not be parsed")
    (is (zero? (get-in completion [:seon.error/data :seon.ai/text-received])))
    (is (= ["java.io.IOException: closed"
            "java.io.IOException: connection reset by peer"]
           (get-in completion [:seon.error/data :seon.ai/cause-chain])))
    (is (not (str/includes? (:seon.error/message completion) "readable JSON"))
        "nothing blames the body for a transport that ended")))

(deftest a-reasoning-only-time-limit-names-the-missing-assistant-text
  (let [body
        (truncating-stream
         (streamed-lines
          "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}")
         (java.net.http.HttpTimeoutException. "request timed out"))
        failure (#'seon.ai/streamed-completion body nil)]
    (is (= :seon.ai/stream-truncated (:seon.error/kind failure)))
    (is (= 8 (get-in failure
                      [:seon.error/data :seon.ai/reasoning-received])))
    (is (zero? (get-in failure
                       [:seon.error/data :seon.ai/text-received])))
    (is (str/includes? (:seon.error/message failure)
                       "8 characters of reasoning but no assistant text"))
    (is (str/includes? (:seon.error/message failure)
                       "configured time limit fired"))
    (is (true? (#'seon.ai/output-observed? (:seon.error/data failure)))
        "reasoning is paid output even when assistant text is absent")))

(deftest a-truncated-stream-never-reports-output-that-never-arrived
  ;; the flag `disposition` reads was a hardcoded true on every 2xx
  ;; failure, so an attempt row asserted "output WAS seen" about a stream
  ;; that delivered nothing. It is derived now, and a truncation that
  ;; delivered nothing is still terminal — no re-request either way.
  (let [empty-truncation (#'seon.ai/streamed-completion (truncating-stream "") nil)
        evidenced (update empty-truncation :seon.error/data merge
                          {:seon.ai/error-class :response
                           :seon.ai/output-observed?
                           (pos? (get-in empty-truncation
                                         [:seon.error/data
                                          :seon.ai/text-received]
                                         1))})]
    (is (false? (get-in evidenced [:seon.error/data :seon.ai/output-observed?])))
    (is (= :fail (ai/disposition {:seon.error/value evidenced
                                  :seon.ai/backup? false}))
        "nothing re-requests a 2xx whose stream died, paid or not")))

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
    (is (= "local reply" (:seon.ai/text outcome)))
    (is (int? (:seon.ai.model/last-latency-ms outcome)))
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
;;; The whole leaf, against a server that really truncates
;;; ---------------------------------------------------------------------------

;;; The unit tests above pin the fold; these pin the HTTP leaf itself,
;;; because the class showed up as an HTTP fact and a stub that only
;;; replaces the InputStream cannot see the client, the connection, or
;;; concurrency. The server answers 200, promises a Content-Length it
;;; does not deliver, and closes — which is what a provider disconnect
;;; looks like from inside the JDK.

(defn- sse-chunk [text]
  (str "data: {\"choices\":[{\"delta\":{\"content\":" (json/write-str text)
       "}}]}\n\n"))

(defn- start-stub!
  "A local server for complete, truncated, and rejected responses."
  []
  (let [server (com.sun.net.httpserver.HttpServer/create
                (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
        write (fn [exchange promised body]
                (.add (.getResponseHeaders exchange)
                      "Content-Type" "text/event-stream")
                (.sendResponseHeaders exchange 200 (long promised))
                (let [out (.getResponseBody exchange)]
                  (.write out (.getBytes ^String body "UTF-8"))
                  (.flush out)
                  (.close exchange)))]
    (.createContext
     server "/truncate"
     (reify com.sun.net.httpserver.HttpHandler
       (handle [_ exchange]
         (let [body (str (sse-chunk "(+ 1 2)") (sse-chunk " (+ 3"))]
           ;; promise more than we send, then hang up
           (write exchange (+ 4096 (count body)) body)))))
    (.createContext
     server "/stream"
     (reify com.sun.net.httpserver.HttpHandler
       (handle [_ exchange]
         (let [body (str (sse-chunk "(+ 1 2)")
                         "data: {\"choices\":[{\"delta\":{\"content\":\"\"},"
                         "\"finish_reason\":\"stop\"}]}\n\n"
                         "data: [DONE]\n\n")]
           (write exchange (count body) body)))))
    (.createContext
     server "/payment-required"
     (reify com.sun.net.httpserver.HttpHandler
       (handle [_ exchange]
         (let [body "{\"error\":{\"message\":\"Insufficient Balance\"}}"
               bytes (.getBytes body "UTF-8")]
           (.add (.getResponseHeaders exchange)
                 "Content-Type" "application/json")
           (.sendResponseHeaders exchange 402 (long (alength bytes)))
           (with-open [out (.getResponseBody exchange)]
             (.write out bytes))))))
    (.setExecutor
     server (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor))
    (.start server)
    server))

(defn- stub-request [server path]
  (assoc base
         :seon.ai/endpoint (str "http://127.0.0.1:"
                                (.getPort (.getAddress server)) path)
         :seon.ai/stream? true
         :seon.ai/timeout-ms 10000))

(defn- stub-leaf-request [server path]
  {:seon.ai/endpoint (str "http://127.0.0.1:"
                          (.getPort (.getAddress server)) path)
   :seon.ai/timeout-ms 10000
   :seon.ai/stream? true
   :seon.ai.http/headers {"content-type" "application/json"}
   :seon.ai.attempt/sent-body "{}"})

(defn- start-custody-stub!
  "A local server whose two response bodies are independently gated."
  []
  (let [server (com.sun.net.httpserver.HttpServer/create
                (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
        executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
        reasoning-finished (java.util.concurrent.CountDownLatch. 1)
        release-reasoning (java.util.concurrent.CountDownLatch. 1)
        peer-started (java.util.concurrent.CountDownLatch. 1)
        release-peer (java.util.concurrent.CountDownLatch. 1)
        begin (fn [exchange]
                (.add (.getResponseHeaders exchange)
                      "Content-Type" "text/event-stream")
                ;; Zero selects chunked transfer, so the handler can keep the
                ;; response open after it sends the provider finish signal.
                (.sendResponseHeaders exchange 200 0)
                (.getResponseBody exchange))
        write! (fn [out value]
                 (.write out (.getBytes ^String value "UTF-8"))
                 (.flush out))]
    (.createContext
     server "/reasoning"
     (reify com.sun.net.httpserver.HttpHandler
       (handle [_ exchange]
         (with-open [out (begin exchange)]
           (write! out
                   (streamed-lines
                    (str "data: {\"choices\":[{\"delta\":{"
                         "\"reasoning_content\":\"thinking\"},"
                         "\"finish_reason\":\"stop\"}]}")))
           (.countDown reasoning-finished)
           (.await release-reasoning 5 java.util.concurrent.TimeUnit/SECONDS)))))
    (.createContext
     server "/peer"
     (reify com.sun.net.httpserver.HttpHandler
       (handle [_ exchange]
         (with-open [out (begin exchange)]
           (write! out (sse-chunk "peer "))
           (.countDown peer-started)
           (.await release-peer 5 java.util.concurrent.TimeUnit/SECONDS)
           (write! out
                   (str (sse-chunk "survived")
                        "data: {\"choices\":[{\"delta\":{\"content\":\"\"},"
                        "\"finish_reason\":\"stop\"}]}\n\n"
                        "data: [DONE]\n\n"))))))
    (.setExecutor server executor)
    (.start server)
    {:server server
     :executor executor
     :reasoning-finished reasoning-finished
     :release-reasoning release-reasoning
     :peer-started peer-started
     :release-peer release-peer}))

(defn- stop-custody-stub!
  [{:keys [server executor release-reasoning release-peer]}]
  (.countDown ^java.util.concurrent.CountDownLatch release-reasoning)
  (.countDown ^java.util.concurrent.CountDownLatch release-peer)
  (.stop ^com.sun.net.httpserver.HttpServer server 0)
  (.shutdownNow ^java.util.concurrent.ExecutorService executor))

(deftest a-provider-that-hangs-up-mid-body-settles-what-it-already-sent
  (let [server (start-stub!)]
    (try
      (let [completion (with-redefs [ai/credential (constantly "test-key")]
                         (ai/complete (stub-request server "/truncate")))
            truncation (:seon.ai/truncation completion)]
        (is (= "(+ 1 2) (+ 3" (:seon.ai/text completion))
            "a run built from this settles forms instead of closing with zero")
        (is (nil? (:seon.error/kind completion)))
        (is (= :seon.ai/stream-truncated (:seon.error/kind truncation)))
        (is (pos? (get-in truncation
                          [:seon.error/data :seon.ai/text-received])))
        (is (seq (get-in truncation [:seon.error/data :seon.ai/cause-chain]))
            "the transport's own account of the ending is recorded"))
      (finally (.stop server 0)))))

(deftest one-client-serves-concurrent-streams
  ;; the seam that produced the whole-system arc's failures ran several
  ;; agents' streams at once; the process holds ONE HttpClient, so this
  ;; is the shape that must not degrade
  (let [server (start-stub!)]
    (try
      (let [outcomes (with-redefs [ai/credential (constantly "test-key")]
                       (mapv deref
                             (mapv (fn [_]
                                     (future
                                       (ai/complete
                                        (stub-request server "/stream"))))
                                   (range 6))))]
        (is (= 6 (count (filter #(= "(+ 1 2)" (:seon.ai/text %)) outcomes)))
            (str "every concurrent stream completed: "
                 (pr-str (mapv #(or (:seon.error/message %) :ok) outcomes)))))
      (finally (.stop server 0)))))

(deftest payment-required-primary-selects-the-configured-backup
  (let [server (start-stub!)]
    (try
      (let [{:seon.ai/keys [primary backup]}
            (ai/targets
             (assoc @dials
                    :seon.config.ai.backup/model "backup-probe"
                    :seon.config.ai.backup/endpoint
                    "https://backup.example.invalid/v1/chat/completions"))
            failure
            (with-redefs [ai/credential (constantly "test-key")]
              (ai/complete
               (assoc primary
                      :seon.ai/endpoint
                      (str "http://127.0.0.1:"
                           (.getPort (.getAddress server))
                           "/payment-required")
                      :seon.ai/prompt "hello")))
            action
            (ai/disposition
             {:seon.error/value failure :seon.ai/backup? true})
            selected-target (when (= :failover-now action) backup)]
        (is (= 402 (:seon.ai/provider-error failure)))
        (is (= :authentication
               (get-in failure
                       [:seon.error/data :seon.ai/error-class]))
            "payment exhaustion is a free 'not here' refusal")
        (is (= :failover-now action))
        (is (= "backup-probe" (:seon.ai/model selected-target)))
        (is (= "https://backup.example.invalid/v1/chat/completions"
               (:seon.ai/endpoint selected-target))
            "the primary rejection selects the configured backup target")
        (is (= :fail
               (ai/disposition
                {:seon.error/value failure :seon.ai/backup? false}))
            "without a backup, repeating an empty-balance target cannot help"))
      (finally (.stop server 0)))))

(deftest a-reasoning-finish-settles-before-the-response-body-ends
  (let [{:keys [server reasoning-finished] :as stub}
        (start-custody-stub!)]
    (try
      (let [outcome (future
                      (#'seon.ai/send-request
                       (stub-leaf-request server "/reasoning")))]
        (is (.await ^java.util.concurrent.CountDownLatch reasoning-finished
                    15 java.util.concurrent.TimeUnit/SECONDS)
            "the local provider published its finish evidence")
        (let [settled (deref outcome 2000 ::did-not-settle)]
          (is (not= ::did-not-settle settled)
              "finish evidence settles without waiting for EOF or timeout")
          (is (= :seon.ai/reasoning-without-answer
                 (:seon.error/kind settled)))
          (is (= 8 (get-in settled
                           [:seon.error/data
                            :seon.ai/reasoning-received])))
          (is (zero? (get-in settled
                             [:seon.error/data :seon.ai/text-received])))))
      (finally (stop-custody-stub! stub)))))

(deftest closing-one-attempts-body-cannot-close-its-concurrent-peer
  (let [{:keys [server peer-started release-peer reasoning-finished]
         :as stub}
        (start-custody-stub!)]
    (try
      (let [complete! #(#'seon.ai/send-request
                        (stub-leaf-request server %))
            peer (future (complete! "/peer"))]
        (is (.await ^java.util.concurrent.CountDownLatch peer-started
                    15 java.util.concurrent.TimeUnit/SECONDS)
            "the peer owns an open response body")
        (let [reasoning (future (complete! "/reasoning"))]
          (is (.await ^java.util.concurrent.CountDownLatch reasoning-finished
                      15 java.util.concurrent.TimeUnit/SECONDS))
          (is (= :seon.ai/reasoning-without-answer
                 (:seon.error/kind (deref reasoning 2000
                                          {::timed-out true})))
              "the finished attempt closes its own body")
          (.countDown ^java.util.concurrent.CountDownLatch release-peer)
          (let [peer-outcome (deref peer 2000 {::timed-out true})]
            (is (= "peer survived" (:seon.ai/text peer-outcome))
                (str "the concurrent peer remained readable: "
                     (pr-str peer-outcome))))))
      (finally (stop-custody-stub! stub)))))

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

(defn- refused-loopback-endpoint
  "An endpoint whose ephemeral loopback listener has already closed."
  []
  (with-open [listener
              (java.net.ServerSocket.
               0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (str "http://127.0.0.1:" (.getLocalPort listener) "/v1")))

(deftest the-leaf-records-phase-from-the-jdks-own-taxonomy
  ;; The fixture asks the OS for a free loopback port, closes its listener,
  ;; then calls the production boundary. The JDK raises ConnectException,
  ;; which PROVES nothing was transmitted.
  (let [value (ai/complete {:seon.ai/endpoint (refused-loopback-endpoint)
                            :seon.ai/model "probe"
                            :seon.config.ai/no-auth true
                            :seon.ai/prompt "hello"
                            :seon.ai/timeout-ms 500})]
    (is (= :seon.ai/transport-failure (:seon.error/kind value))
        (str "the production transport boundary must construct the subject: "
             (pr-str value)))
    (is (schema/valid-candidate-value? :seon.error/value value)
        "the derived subject is one complete typed error value")
    (let [evidence (:seon.error/data value)]
      (is (false? (:seon.ai/request-transmitted? evidence)))
      (is (= :transport-before-send (:seon.ai/error-class evidence)))
      (is (= :failover-now (disposed value true))
          "so a backup is eligible — this is the zero-cost case")
      (is (= :fail
             (disposed
              (assoc-in value [:seon.error/data :seon.ai/error-class]
                        :transport-unknown)
              true))
          "counterexample: without before-send evidence the proof is false"))))

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
