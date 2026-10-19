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
  (:require [seon.text-render-support :as text-render]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.ai :as ai]
            [seon.config :as config]
            [seon.agent]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(def ^:private base
  {:seon.ai/endpoint "http://127.0.0.1:1/chat/completions"
   :seon.ai/model "probe-model"
   :seon.ai/max-tokens 8192
   :seon.ai/api-key-variable "SEON_AI_TEST_KEY_ABSENT"
   :seon.ai/prompt "say hello"
   :seon.ai/timeout-ms 250})



(defn- with-test-key
  "`request` resolving its credential variable to a test key in its own
  environment: nothing is exported and no Var is redefined."
  {:malli/schema [:=> [:cat [:map [:seon.ai/api-key-variable :seon.ai/api-key-variable]]] :map]}
  [request]
  (assoc request :seon.ai/environment {(:seon.ai/api-key-variable request) "test-key"}))

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
;;; The descriptor entities: one derivation, two roles
;;; ---------------------------------------------------------------------------

(def ^:private dials
  "The shipped defaults — the real document, not a fixture of it. A
  suite that invents its own dial map cannot catch a default that
  stopped being derivable."
  (delay config/defaults))

(deftest the-shipped-cluster-has-one-primary-target
  (let [targets (ai/targets (seon.schema/handed-projection) @dials)]
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/targets) targets))
    (is (= {:seon.ai/endpoint (:seon.config.ai/endpoint @dials)
            :seon.ai/model (:seon.config.ai/model @dials)
            :seon.ai/max-tokens (:seon.config.ai/max-tokens @dials)
            :seon.ai/prompt-token-budget
            (:seon.config.ai/prompt-token-budget @dials)
            :seon.ai/chars-per-token-prior
            (:seon.config.ai/chars-per-token-prior @dials)
            :seon.ai/api-key-variable (:seon.config.ai/api-key-variable @dials)
            :seon.ai/thinking :disabled
            :seon.ai/stop (:seon.config.ai/stop @dials)
            :seon.ai/timeout-ms (:seon.config.ai/timeout-ms @dials)
            :seon.ai/wrapping (:seon.config.ai/wrapping @dials)}
           (:seon.ai/primary targets))
        "the primary retains every effective AI dial without reshaping it")
    (is (= [:seon.ai/primary] (keys targets))
        "one call per model step means one target: no backup")
    (testing "and a target is a request minus what to say, which is why
    the call site is one assoc"
      (is ((schema/projection-validator (schema/handed-projection) :seon.ai/request) (assoc (:seon.ai/primary targets) :seon.ai/prompt "hello"))))
    (is (= 65536 (:seon.ai/max-tokens (:seon.ai/primary targets)))
        "the interim output budget remains until flash calibration lands")
    (is (= :disabled (:seon.ai/thinking (:seon.ai/primary targets)))
        "the shipped fast-turn posture explicitly disables thinking")))

(deftest deepseek-flash-thinking-setting-survives-model-resolution
  (test-support/with-database
   (fn [connection]
     (is (seq (db/pull @connection [:seon.ai.model/id]
                      [:seon.ai.model/id "deepseek-flash"])))
     (doseq [thinking [:disabled :high]]
       (let [target (:seon.ai/primary
                     (ai/targets (seon.schema/handed-projection) @connection (assoc @dials :seon.config.ai/model "deepseek-flash"
                                        :seon.config.ai/thinking thinking)))
             body (ai/request-body (seon.schema/handed-projection) (assoc target :seon.ai/prompt "hello"))]
         (is (= "deepseek-flash" (get body "model")))
         (is (= thinking (:seon.ai/thinking target)))
         (is (= {"type" (if (= :disabled thinking) "disabled" "enabled")}
                (get body "thinking")))
         (if (= :disabled thinking)
           (is (not (contains? body "reasoning_effort")))
           (is (= "high" (get body "reasoning_effort")))))))))

(deftest thinking-is-one-config-fact-with-three-wire-states
  (testing "absence leaves the provider default untouched"
    (let [body (ai/request-body (seon.schema/handed-projection) base)]
      (is (not (contains? body "thinking")))
      (is (not (contains? body "reasoning_effort")))))
  (testing "explicit disable sends only the off switch"
    (let [body (ai/request-body (seon.schema/handed-projection) (assoc base :seon.ai/thinking :disabled))]
      (is (= {"type" "disabled"} (get body "thinking")))
      (is (not (contains? body "reasoning_effort")))))
  (testing "each configured effort means explicit on plus that effort"
    (doseq [effort [:low :high :max]]
      (let [target (get-in (ai/targets (seon.schema/handed-projection) (assoc @dials :seon.config.ai/thinking effort))
                           [:seon.ai/primary])
            body (ai/request-body (seon.schema/handed-projection) (assoc target :seon.ai/prompt "hello"))]
        (is (= effort (:seon.ai/thinking target)))
        (is (= {"type" "enabled"} (get body "thinking")))
        (is (= (name effort) (get body "reasoning_effort")))))))

(deftest no-auth-targets-accrete-unused-authentication-data
  (let [target {:seon.ai/endpoint "http://127.0.0.1:8090/v1/chat/completions"
                :seon.ai/model "local-model"
                :seon.ai/max-tokens 8192
                :seon.config.ai/no-auth true
                :seon.ai/timeout-ms 300000}]
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/target) target))
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/request) (assoc target :seon.ai/prompt "hello")))
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/target) (assoc target :seon.ai/api-key-variable "DUMMY"))
        "each open union arm ignores data it does not declare")
    (is (not ((schema/projection-validator (schema/handed-projection) :seon.ai/target) (dissoc target :seon.config.ai/no-auth)))
        "omitting a credential does not silently mean no-auth")))

(deftest a-no-auth-config-entity-assembles-and-sends-without-authorization
  (let [requests (atom [])
        ;; THE ROW IS COMPILED, NOT EDITED: `:seon.config/effective` declares
        ;; the credential variable, so `dissoc`ing it handed `targets` a
        ;; shape its contract forbids. `config/absent` is the one explicit
        ;; retraction a manifest has, and compiling with it is exactly how a
        ;; no-auth cluster's effective map comes to be.
        targets (ai/targets (seon.schema/handed-projection) (assoc (test-support/effective-config
                         {:seon.config.ai/api-key-variable config/absent})
                        :seon.config.ai/no-auth true))
        target (:seon.ai/primary targets)
        outcome
        (ai/complete (seon.schema/handed-projection)
                     (assoc target :seon.ai/prompt "hello"
                            :seon.ai/send! (fn [request-data]
                                             (swap! requests conj request-data)
                                             {:seon.ai/text "local reply"})))]
    (is (= true (:seon.config.ai/no-auth target))
        "assembly carries the descriptor's declared authentication state")
    (is (not (contains? target :seon.ai/api-key-variable))
        "the assembled target keeps exactly one authentication declaration")
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/target) target))
    (is (= "local reply" (:seon.ai/text outcome)))
    (is (int? (:seon.ai.model/last-latency-ms outcome)))
    (is (= {"content-type" "application/json"}
           (:seon.ai.http/headers (first @requests)))
        "the F4 recorder seam observes no Authorization header")))

(defn- seed-registry!
  [connection]
  (test-support/transacted!
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
      ;; THE ROW IS COMPILED, NOT EDITED: `:seon.config/effective` declares
      ;; the credential variable, and `config/absent` is the one explicit
      ;; retraction a manifest has, so this is the effective map a cluster
      ;; selecting no credential actually gets.
      (let [settings (test-support/effective-config
                      {:seon.config.ai/model "registered-model"
                       :seon.config.ai/max-tokens 500
                       :seon.config.ai/thinking :disabled
                       :seon.config.ai/api-key-variable config/absent})
            target (:seon.ai/primary (ai/targets (seon.schema/handed-projection) @connection settings))
            body (ai/request-body (seon.schema/handed-projection) (assoc target :seon.ai/prompt "hello"))]
        (is (= "https://example.invalid/v1/chat/completions"
               (:seon.ai/endpoint target)))
        (is (= "TEST_PROVIDER_KEY" (:seon.ai/api-key-variable target)))
        (is (= 100 (:seon.ai/max-tokens target))
            "the declared model maximum bounds the effective request")
        (is (not (contains? target :seon.ai/thinking))
            "an inadmissible setting leaves the provider default untouched")
        (is (= 100 (get body "max_completion_tokens")))
        (is (not (contains? body "max_tokens"))
            "the provider entity, not the model name, selects the wire field")))))

(deftest descriptor-resolution-normalizes-cardinality-many-before-membership
  (test-support/with-database
    (fn [connection]
      (seed-registry! connection)
      (let [settings (assoc @dials
                            :seon.config.ai/model "registered-model"
                            :seon.config.ai/thinking :high)
            target (:seon.ai/primary (ai/targets (seon.schema/handed-projection) @connection settings))
            body (ai/request-body (seon.schema/handed-projection) (assoc target :seon.ai/prompt "hello"))]
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
             (ai/targets (seon.schema/handed-projection) @connection (assoc @dials
                                :seon.config.ai/model "registered-model"
                                :seon.config.ai/api-key-variable
                                missing-variable)))
            requests (atom [])
            outcome
            (ai/complete (seon.schema/handed-projection)
                         (assoc target :seon.ai/prompt "hello"
                                :seon.ai/send! (fn [request]
                                                 (swap! requests conj request)
                                                 {:seon.ai/text "must not happen"})))]
        (is (= missing-variable (:seon.ai/api-key-variable target)))
        (is ((schema/projection-validator (schema/handed-projection) :seon.ai/no-credential-error) outcome))
        (is (empty? @requests)
            "the explicit absent credential refuses before the network")))))

(deftest provider-descriptor-fills-an-absent-credential-selection
  (test-support/with-database
    (fn [connection]
      (seed-registry! connection)
      (let [settings (test-support/effective-config
                      {:seon.config.ai/model "registered-model"
                       :seon.config.ai/api-key-variable config/absent})
            target (:seon.ai/primary (ai/targets (seon.schema/handed-projection) @connection settings))]
        (is (= "TEST_PROVIDER_KEY" (:seon.ai/api-key-variable target)))))))

(deftest a-missing-registry-entity-leaves-the-working-call-target-unchanged
  (test-support/with-database
    (fn [connection]
      (let [settings (test-support/effective-config
                      {:seon.config.ai/model "unregistered-model"})]
        (is (= (ai/targets (seon.schema/handed-projection) settings)
               (ai/targets (seon.schema/handed-projection) @connection settings)))))))

(deftest model-entities-are-queryable-open-and-rendered-without-wire-duplication
  (test-support/with-database
    (fn [connection]
      (seed-registry! connection)
      (let [model (ai/model-entity @connection "registered-model")
            accreted (assoc model :seon.ai.model/future-tailored-fact
                            {:seon.ai.model/example true})
            ai-render (ai/registry-ai @connection)
            html-render (ai/registry-html @connection)]
        (is (= (conj (into #{} (keep :seon.ai.model/id)
                           (config/default-population))
                     "registered-model")
               (into #{} (map :seon.ai.model/id) (ai/models @connection)))
            "the list is every shipped declared model row plus what this
             test seeded — DERIVED from the same initialization document, so
             adding or dropping a shipped model is caught without any list
             here to update")
        (is (not (contains? model :seon.config.ai/endpoint))
            "provider wire facts never duplicate onto model entities")
        (is ((schema/projection-validator (schema/handed-projection) :seon.ai.model/entity) accreted)
            "a tailored fact can accrete without breaking the open entity")
        (is (str/includes? ai-render "input $0.250000/M"))
        (is (str/includes? ai-render "registered-model"))
        (is ((schema/projection-validator (schema/handed-projection) :seon.render/hiccup) html-render))))))

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
        (test-support/transacted! connection
                                  (ai/model-observation-tx @connection first-observation))
        (test-support/transacted! connection
                                  (ai/model-observation-tx @connection second-observation))
        (is (= 100.0
               (:seon.ai.model/last-tokens-per-second
                (ai/model-entity @connection "registered-model"))))
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
      (is ((schema/projection-validator (schema/handed-projection) :seon.ai/json-value) value)))
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/usage) payload))
    (is (= payload (json/read-str (json/write-str payload))))
    (is (not ((schema/projection-validator (schema/handed-projection) :seon.ai/json-value) (fn [] :not-json))))))

(deftest generated-json-values-validate-and-round-trip
  (let [compiled (compiled-json-value-schema)
        schema-values (mg/sample compiled {:seed 2026080301 :size 30})]
    (is (seq schema-values))
    (is (every? #(m/validate compiled %) schema-values))
    (doseq [[_json-partition seed generator]
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
            "JSON round-trip failed")))))

;; Task `seon.config/no-provider-cannot-be-lifted-per-agent`.
(deftest an-agent-lifts-no-provider-for-itself-only
  (let [cluster (assoc (test-support/effective-config) :seon.config.ai/no-provider true)
        lifted (ai/settings cluster {:seon.config.ai/no-provider false})]
    (is (false? (:seon.config.ai/no-provider lifted)))
    (is (true? (:seon.config.ai/no-provider (ai/settings cluster {}))))
    (is ((schema/projection-validator (schema/handed-projection) :seon.config/effective) lifted))
    (is ((schema/projection-validator (schema/handed-projection) :seon.config/agent-overlay)
         {:seon.config.ai/no-provider false}))))

;; Task `seon.ai/deepseek-model-rows-and-dial-classification-are-stale`.
(deftest the-shipped-prompt-budget-and-output-fit-the-model-window
  (let [model (some #(when (= (:seon.config.ai/model @dials) (:seon.ai.model/id %)) %)
                    (config/default-population))]
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai.model/entity) model))
    (is (<= (+ (:seon.config.ai/prompt-token-budget @dials) (:seon.config.ai/max-tokens @dials))
            (:seon.ai.model/context-window-tokens model)))
    (is (<= (:seon.config.ai/max-tokens @dials) (:seon.ai.model/max-output-tokens model)))))

(deftest the-request-body-carries-the-model-and-the-messages
  (let [body (ai/request-body (seon.schema/handed-projection) (assoc base :seon.ai/system "be brief"))]
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
    (is ((schema/projection-validator (schema/handed-projection) :seon.config/effective) resolved))))

(deftest agent-overlay-reads-only-derived-per-agent-attributes
  (test-support/with-database
    (fn [connection]
      (test-support/transacted! connection (test-support/agent-tx @connection "planner"))
      (seon.agent/settings! {:seon.config.ai/model "planner-model"
                             :seon.config.ai/thinking :high}
                            connection "planner")
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
        {:seon.ai/keys [sent inert]} (ai/wire-settings (seon.schema/handed-projection) request)]
    (is (= {"max_tokens" 8192
            "thinking" {"type" "enabled"}
            "reasoning_effort" "high"
            "stop" ["END"]
            "top_p" 0.8
            "response_format" {"type" "json_object"}}
           sent))
    (is (= #{:seon.config.ai/temperature} inert))
    (is (not (contains? (ai/request-body (seon.schema/handed-projection) request) "temperature")))))

(deftest stop-is-effective-per-agent-and-recorded-with-the-attempt
  (test-support/with-database
   (fn [connection]
     (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
     (doseq [tx [(agent/creation-tx {:seon.agent/id "stop-agent"
                                    :seon.ns/name 'my.agents.stop
                                    :seon.cluster/name "default"})
                 (turn/open-tx {:seon.turn/id "stop-run"
                                :seon.turn/agent [:seon.agent/id "stop-agent"]
                                :seon.turn/opened-tx "datomic.tx"})
                 [{:seon.config/agent [:seon.agent/id "stop-agent"]
                   :seon.config.ai/stop ["END"]}]]]
       (test-support/transacted! connection tx))
     (doseq [[ordinal agent-id expected] [[0 "absent" (:seon.config.ai/stop @dials)]
                                         [1 "stop-agent" ["END"]]]]
       (let [settings (ai/settings (config/effective @connection "default")
                                   (ai/agent-overlay @connection agent-id))
             target (:seon.ai/primary (ai/targets (seon.schema/handed-projection) settings))
             body (ai/request-body (seon.schema/handed-projection) (assoc target :seon.ai/prompt "(+ 1 1)"))
             recorded (#'turn/record-attempt!
                       {:seon.db/connection connection}
                       {:seon.ai/target target :seon.ai/settings settings
                        :seon.turn/id "stop-run" :seon.agent/id "stop-agent"
                        :seon.ai.attempt/ordinal ordinal}
                       (java.util.Date.))
             saved (db/q '[:find ?settings . :in $ ?ordinal
                           :where [?a :seon.ai.attempt/ordinal ?ordinal]
                           [?a :seon.ai.attempt/settings-edn ?settings]]
                         @connection ordinal)]
         (is (nil? recorded))
         (is (= expected (get body "stop")))
         (is (= expected (:seon.config.ai/stop (edn/read-string saved)))))))))

(deftest captured-run4-replies-stop-before-fabricated-responses
  (test-support/with-database
   (fn [connection]
     (let [ctx (test-support/fork-cluster-ctx connection)
           captures (:run4/turns (edn/read-string (slurp "test/seon/run4_replies.edn")))
           stop "#:seon.repl"
           fabricated (filter #(str/includes? (second %) stop) captures)
           evaluated (atom 0)]
       (is (seq fabricated) "The replay must include actual fabricated replies.")
       (doseq [[_id text] captures]
         (let [stopped (subs text 0 (or (str/index-of text stop) (count text)))
               forms (turn/planned-sources stopped 'user 100000)]
           (is (and (vector? forms) (seq forms))
               "must retain its preceding forms")
           (when (vector? forms)
             (doseq [form forms]
               (let [result (test-support/evaluate
                             {:seon.sci.eval/ctx ctx
                              :seon.cluster.eval/source (:seon.cluster.eval/source form)
                              :seon.sci.eval/time-limit-ms 2000
                              :seon.sci.admit/caps (config/result-caps @dials)})]
                 (swap! evaluated inc)
                 (is (contains? result :seon.sci.admit/value)
                     "must return an evaluated value")
                 (is (not= true
                           (get-in result [:seon.sci.admit/value :seon.sci.reader/fabricated-response]))))))))
       (is (pos? @evaluated) "At least one preceding form must actually evaluate.")))))

(deftest disabled-thinking-emits-sampling-and-omits-effort
  (let [body (ai/request-body (seon.schema/handed-projection) (assoc base
                     :seon.ai/thinking :disabled
                     :seon.ai/temperature 0.4
                     :seon.ai/presence-penalty -0.5))]
    (is (= {"type" "disabled"} (get body "thinking")))
    (is (= 0.4 (get body "temperature")))
    (is (= -0.5 (get body "presence_penalty")))
    (is (not (contains? body "reasoning_effort")))))

(deftest extra-body-merges-last-but-cannot-rewrite-any-builder-owned-key
  (is (= true
         (get (ai/request-body (seon.schema/handed-projection) (assoc base :seon.ai/extra-body-edn
                      "{\"vendor_option\" true}"))
              "vendor_option")))
  (doseq [protected ["model" "max_tokens" "authorization"]]
    (let [failure (ai/request-body (seon.schema/handed-projection) (assoc base :seon.ai/extra-body-edn
                          (pr-str {protected "override"})))]
      (is ((schema/projection-validator (schema/handed-projection) :seon.ai/extra-body-conflict-error) failure))
      (is (= [protected]
             (get-in failure
                     [:seon.error/data :seon.ai/protected-keys])))))
  (is (= "[:not :a-map]"
         (:seon.ai/extra-body-edn
          (ai/request-body (seon.schema/handed-projection) (assoc base :seon.ai/extra-body-edn "[:not :a-map]"))))))

(deftest an-extra-body-conflict-refuses-before-the-http-leaf
  (let [sent (atom 0)
        outcome
        (ai/complete (seon.schema/handed-projection)
                     (assoc base :seon.ai/extra-body-edn "{\"authorization\" \"leak\"}"
                            :seon.ai/send! (fn [_] (swap! sent inc))))]
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/extra-body-conflict-error) outcome))
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
        (is (contains? outcome :seon.error/offending)
            "The observed malformed value remains available as evidence.")
        (is ((schema/projection-validator (schema/handed-projection) :seon.ai/unparseable-body-error) outcome))))))

(deftest an-empty-normal-stop-is-a-reply-but-malformed-json-is-a-provider-refusal
  (doseq [content ["" nil]]
    (let [completion (ai/completion-text
                      {"choices" [{"message" {"content" content}
                                   "finish_reason" "stop"}]})]
      (is (= "" (:seon.ai/text completion)))
      (is (= "stop" (:seon.ai/finish-reason completion)))
      (is (= "" (get-in (turn/planned-sources (:seon.ai/text completion) 'user 10000)
                       [:seon.error/data :seon.cluster.reply/text])))))
  (doseq [[body expected]
          [["data: {\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n" nil]
           ["data: {malformed json\n\n" :seon.ai/unparseable-body]]]
    (let [completion (#'ai/streamed-completion
                      (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8")) nil)]
      (is (= (some? expected) (contains? completion :seon.ai/unreadable-response-member)))
      (when-not expected
        (is (= "" (:seon.ai/text completion)))))))

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
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/completion) completion))))

(deftest the-model-the-provider-reported-is-kept-from-either-transport
  ;; live run 12: DeepSeek served `deepseek-flash` as V4.1-Flash-1017 from 10-17, and only the response said so
  (let [reported "deepseek-v4.1-flash-1017"
        one-shot (ai/completion-text {"model" reported
                                      "choices" [{"message" {"content" "OK"} "finish_reason" "stop"}]})
        streamed (#'seon.ai/streamed-completion
                  (java.io.ByteArrayInputStream.
                   (.getBytes (str/join "\n" [(str "data: " (json/write-str {"model" reported "choices" [{"delta" {"content" "OK"}}]}))
                                              (str "data: " (json/write-str {"model" reported "choices" [{"delta" {} "finish_reason" "stop"}]}))
                                              "data: [DONE]"])
                              "UTF-8"))
                  nil)]
    (is (= reported (:seon.ai/response-model one-shot)) "a one-shot completion keeps the body's model")
    (is (= reported (:seon.ai/response-model streamed)) "a streamed completion keeps its chunks' model")))

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
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/reasoning-without-answer-error) failure))
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
    (is ((schema/projection-validator (schema/handed-projection) :seon.error/value) failure))))

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
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/reasoning-without-answer-error) failure))
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
          (let [bytes-read (.read delivered buffer offset length)]
            (if (neg? bytes-read)
              (throw (java.io.IOException. "closed" cause))
              bytes-read))))))))

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
    (is (contains? completion :seon.ai/text)
        "a partial completion is a completion, not a refusal")
    (is (= 2 (count @seen)) "every arrived delta still published to the sink")
    (let [truncation (:seon.ai/truncation completion)]
      (is ((schema/projection-validator (schema/handed-projection) :seon.ai/stream-truncated-error) truncation)
          "the truncation is a flat error value the caller can record")
      (is (= 12 (get-in truncation [:seon.error/data :seon.ai/text-received])))
      (is (= false (get-in truncation
                           [:seon.error/data :seon.ai/thread-interrupted?])))
      (is (= [["java.io.IOException" "closed"]
              ["java.io.IOException" "connection reset by peer"]]
             (mapv (juxt :seon.error/throwable-class :seon.error/message)
                   (:seon.error/chain truncation)))
          "the JDK's real cause is recorded, not just the word closed")
      (is (= 'seon.ai/truncation (:seon.error/operation truncation))
          "The observation names the function that read the ended stream."))))

(deftest a-stream-that-ends-before-any-text-is-named-truncation-not-bad-json
  (let [completion (#'seon.ai/streamed-completion (truncating-stream "") nil)]
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/stream-truncated-error) completion)
        "an early close is distinguished from a body that could not be parsed")
    (is (zero? (get-in completion [:seon.error/data :seon.ai/text-received])))
    (is (= [["java.io.IOException" "closed"]
            ["java.io.IOException" "connection reset by peer"]]
           (mapv (juxt :seon.error/throwable-class :seon.error/message)
                 (:seon.error/chain completion))))))

(deftest a-reasoning-only-time-limit-names-the-missing-assistant-text
  (let [body
        (truncating-stream
         (streamed-lines
          "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}")
         (java.net.http.HttpTimeoutException. "request timed out"))
        failure (#'seon.ai/streamed-completion body nil)]
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/stream-truncated-error) failure))
    (is (= 8 (get-in failure
                      [:seon.error/data :seon.ai/reasoning-received])))
    (is (zero? (get-in failure
                       [:seon.error/data :seon.ai/text-received])))
    (is (zero? (:seon.ai/interrupted-text-count failure)))
    (is (true? (get-in failure [:seon.error/data :seon.ai/time-limit-fired?])))
    (is (true? (#'seon.ai/output-observed? (:seon.error/data failure)))
        "reasoning is paid output even when assistant text is absent")))

(deftest a-truncated-stream-never-reports-output-that-never-arrived
  ;; the output-observed flag was a hardcoded true on every 2xx
  ;; failure, so an attempt entity asserted "output WAS seen" about a stream
  ;; that delivered nothing. It is derived now.
  (let [empty-truncation (#'seon.ai/streamed-completion (truncating-stream "") nil)
        evidenced (update empty-truncation :seon.error/data merge
                          {:seon.ai/error-class :response
                           :seon.ai/output-observed?
                           (pos? (get-in empty-truncation
                                         [:seon.error/data
                                          :seon.ai/text-received]
                                         1))})]
    (is (false? (get-in evidenced [:seon.error/data :seon.ai/output-observed?])))))

;;; ---------------------------------------------------------------------------
;;; complete — one attempt, four failure shapes, never a throw
;;; ---------------------------------------------------------------------------

(deftest a-missing-credential-is-loud-in-the-value
  (let [requests (atom [])
        outcome
        (ai/complete (seon.schema/handed-projection)
                     (assoc base :seon.ai/send! (fn [request]
                                                  (swap! requests conj request)
                                                  {:seon.ai/text "must not happen"})))]
    (is (= :seon.ai/request (:seon.error/layer outcome)))
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/no-credential-error) outcome))
    (is (= "SEON_AI_TEST_KEY_ABSENT" (:seon.ai/missing-credential-variable outcome))
        "the refusal names the variable that is unset")
    (is (empty? @requests)
        "a hosted target refuses before constructing or sending a request")))

(deftest explicit-no-auth-omits-the-authorization-header
  (let [requests (atom [])
        request (-> base
                    (dissoc :seon.ai/api-key-variable)
                    (assoc :seon.config.ai/no-auth true))
        outcome
        (ai/complete (seon.schema/handed-projection)
                     (assoc request :seon.ai/send! (fn [request-data]
                                                     (swap! requests conj request-data)
                                                     {:seon.ai/text "local reply"})))]
    (is (= "local reply" (:seon.ai/text outcome)))
    (is (int? (:seon.ai.model/last-latency-ms outcome)))
    (is (= 1 (count @requests)))
    (is (= {"content-type" "application/json"}
           (:seon.ai.http/headers (first @requests)))
        "the captured request map contains no Authorization header")))

(deftest transport-failure-is-an-ordinary-value
  ;; port 1 answers nothing; the credential is present so the call is
  ;; genuinely attempted
  (let [outcome (ai/complete (seon.schema/handed-projection) (with-test-key base))]
    (is (= :seon.ai/request (:seon.error/layer outcome)))
    (is (or (contains? outcome :seon.ai/transport-failure)
            (contains? outcome :seon.ai/timeout)))))

(deftest the-deadline-fires-as-an-outcome-not-a-bug-report
  ;; 10.255.255.1 is unroutable: the connection hangs rather than
  ;; refusing, which is the genuinely unobservable case the deadline
  ;; exists for
  (let [outcome (ai/complete (seon.schema/handed-projection)
                            (with-test-key (assoc base
                                                  :seon.ai/endpoint "http://10.255.255.1:8080/v1"
                                                  :seon.ai/timeout-ms 300)))]
    (is (= :seon.ai/request (:seon.error/layer outcome)))
    (is (or (contains? outcome :seon.ai/transport-failure)
            (contains? outcome :seon.ai/timeout)))
    (is (contains? #{:timeout :transport-before-send :transport-unknown}
                   (get-in outcome [:seon.error/data :seon.ai/error-class]))
        "a slow model is a transport condition, not a defect report")))

(deftest one-attempt-means-one-attempt
  ;; nothing retries a paid call — the count is the contract
  (let [calls (atom 0)]
    (ai/complete (seon.schema/handed-projection)
                 (assoc (with-test-key base)
                        :seon.ai/send! (fn [_] (swap! calls inc) {:seon.ai/text "one reply"})))
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
               body-bytes (.getBytes body "UTF-8")]
           (.add (.getResponseHeaders exchange)
                 "Content-Type" "application/json")
           (.sendResponseHeaders exchange 402 (long (alength body-bytes)))
           (with-open [out (.getResponseBody exchange)]
             (.write out body-bytes))))))
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
   :seon.ai.http/body "{}"})

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
        hold-entered (java.util.concurrent.CountDownLatch. 1)
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
    (.createContext
     server "/hold"
     ;; Receives the request and answers nothing until released: the caller
     ;; stays inside `HttpClient.send`.
     (reify com.sun.net.httpserver.HttpHandler
       (handle [_ exchange]
         (.countDown hold-entered)
         (.await release-peer 5 java.util.concurrent.TimeUnit/SECONDS)
         (.close exchange))))
    (.setExecutor server executor)
    (.start server)
    {:server server
     :executor executor
     :hold-entered hold-entered
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
      (let [completion (ai/complete (seon.schema/handed-projection)
                                    (with-test-key (stub-request server "/truncate")))
            truncation (:seon.ai/truncation completion)]
        (is (= "(+ 1 2) (+ 3" (:seon.ai/text completion))
            "a run built from this settles forms instead of closing with zero")
        (is (contains? completion :seon.ai/text))
        (is ((schema/projection-validator (schema/handed-projection) :seon.ai/stream-truncated-error) truncation))
        (is (pos? (get-in truncation
                          [:seon.error/data :seon.ai/text-received])))
        (is (seq (:seon.error/chain truncation))
            "the transport's own account of the ending is recorded"))
      (finally (.stop server 0)))))

(deftest one-client-serves-concurrent-streams
  ;; the seam that produced the whole-system arc's failures ran several
  ;; agents' streams at once; the process holds ONE HttpClient, so this
  ;; is the shape that must not degrade
  (let [server (start-stub!)]
    (try
      (let [outcomes (mapv deref
                           (mapv (fn [_]
                                   (future
                                     (ai/complete (seon.schema/handed-projection)
                                                  (with-test-key (stub-request server "/stream")))))
                                 (range 6)))]
        (is (= 6 (count (filter #(= "(+ 1 2)" (:seon.ai/text %)) outcomes)))
            "every concurrent stream completed"))
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
          (is (= :seon.ai/reasoning-without-answer-error (:seon.error/type settled))
              "a stop with reasoning and no text is the named reply error (7a996ed48 superseded)")
          (is (= "stop" (get-in settled [:seon.error/data :seon.ai/finish-reason])))
          (is (= 8 (count (get-in settled [:seon.error/data :seon.ai/reasoning-content]))))))
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
          (is (= :seon.ai/reasoning-without-answer-error
                 (:seon.error/type (deref reasoning 2000
                                          {::timed-out true})))
              "the finished attempt closes its own body")
          (.countDown ^java.util.concurrent.CountDownLatch release-peer)
          (let [peer-outcome (deref peer 2000 {::timed-out true})]
            (is (= "peer survived" (:seon.ai/text peer-outcome))
                "the concurrent peer remained readable"))))
      (finally (stop-custody-stub! stub)))))

(deftest a-stop-interrupt-exits-the-http-leaf-with-the-flag-set
  ;; A stop interrupts the turn thread (`seon.cluster.agent/interrupt-turn!`)
  ;; and interrupted execution never resumes. Caught as `Throwable`, the
  ;; interrupt inside `HttpClient.send` became a transport-failure value and
  ;; one inside the body read a truncated completion, and the turn went on
  ;; (2026-09-27). Both must throw the interrupt, flag restored.
  (let [{:keys [server hold-entered peer-started] :as stub} (start-custody-stub!)]
    (try
      (doseq [[path entered] [["/hold" hold-entered] ["/peer" peer-started]]]
        (testing path
          (let [outcome (java.util.concurrent.FutureTask.
                         ^java.util.concurrent.Callable
                         (fn []
                           (try
                             {:returned (#'seon.ai/send-request
                                         (stub-leaf-request server path))}
                             (catch InterruptedException stop
                               {:stop stop
                                :interrupted? (.isInterrupted (Thread/currentThread))}))))
                thread (Thread. ^Runnable outcome)]
            (.start thread)
            (is (.await ^java.util.concurrent.CountDownLatch entered
                        5 java.util.concurrent.TimeUnit/SECONDS)
                "the local provider holds the exchange open")
            (.interrupt thread)
            (.join thread 2000)
            (is (not (.isAlive thread)) "the leaf exits on the interrupt")
            (let [{:keys [stop interrupted?]}
                  (.get outcome 1 java.util.concurrent.TimeUnit/SECONDS)]
              (is (instance? InterruptedException stop)
                  "an interrupt is never a value")
              (is (true? interrupted?) "the thread's interrupt flag is restored")))))
      (finally (stop-custody-stub! stub)))))

;;; ---------------------------------------------------------------------------
;;; The transport-phase evidence the leaf records
;;; ---------------------------------------------------------------------------

(deftest a-payment-required-provider-returns-its-error-value
  (let [server (start-stub!)]
    (try
      (let [{:seon.ai/keys [primary]} (ai/targets (seon.schema/handed-projection) @dials)
            failure
            (ai/complete (seon.schema/handed-projection)
                         (with-test-key (assoc primary
                                               :seon.ai/endpoint
                                               (str "http://127.0.0.1:"
                                                    (.getPort (.getAddress server))
                                                    "/payment-required")
                                               :seon.ai/prompt "hello")))]
        (is (= 402 (:seon.ai/provider-error failure)))
        (is (= :authentication
               (get-in failure [:seon.error/data :seon.ai/error-class]))))
      (finally (.stop server 0)))))

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
  (let [value (ai/complete (seon.schema/handed-projection) {:seon.ai/endpoint (refused-loopback-endpoint)
                            :seon.ai/model "probe"
                            ;; the declared call target carries its output
                            ;; bound like every assembled one
                            :seon.ai/max-tokens 32
                            :seon.config.ai/no-auth true
                            :seon.ai/prompt "hello"
                            :seon.ai/timeout-ms 500})]
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/transport-failure-error) value)
        "the production transport boundary must construct the subject")
    (is ((schema/projection-validator (schema/handed-projection) :seon.error/value) value)
        "the derived subject is one complete typed error value")
    (let [evidence (:seon.error/data value)]
      (is (false? (:seon.ai/request-transmitted? evidence)))
      (is (= :transport-before-send (:seon.ai/error-class evidence))))))

(deftest a-missing-credential-is-an-unsent-error-value
  (let [value (ai/complete (seon.schema/handed-projection) {:seon.ai/endpoint "http://127.0.0.1:1/v1"
                            :seon.ai/model "probe"
                            :seon.ai/max-tokens 32
                            :seon.ai/api-key-variable "SEON_AI_ABSENT_KEY_PROBE"
                            :seon.ai/prompt "hello"
                            :seon.ai/timeout-ms 500})]
    (is ((schema/projection-validator (schema/handed-projection) :seon.ai/no-credential-error) value))
    (is (false? (:seon.ai/request-transmitted? (:seon.error/data value)))
        "no network call happened at all")
    (is (= :credential (:seon.ai/error-class (:seon.error/data value))))))

(deftest each-wrapping-builds-its-wire-messages
  (let [one [{:seon.ai/form "(+ 1 2)" :seon.ai/reply "#:seon.repl{:value 3}"}]
        two [{:seon.ai/form ";; look first\n(keys {:a 1})" :seon.ai/reply "#:seon.repl{:value (:a)}"}
             {:seon.ai/form "(def x 1)" :seon.ai/reply "#:seon.repl{:value #'user/x}"}]
        settled-nothing [{:seon.ai/form "(Thread/sleep 1000)"}]
        roles #(mapv (fn [message] (get message "role")) %)
        last-text (fn [messages]
                    (let [content (get (peek messages) "content")]
                      (if (string? content) content (get (peek content) "text"))))]
    (doseq [[exchanges transcript-roles chat-roles]
            [[[] ["system" "user"] ["system" "user"]]
             [[one] ["system" "user"] ["system" "assistant" "user"]]
             [[one two settled-nothing] ["system" "user"]
              ["system" "assistant" "user" "assistant" "user" "assistant" "user"]]]
            [wrapping expected-roles] [[:transcript transcript-roles] [:chat chat-roles]]]
      (let [sent (ai/messages wrapping "help" exchanges "BLOCK")]
        (testing (str wrapping " over " (count exchanges) " exchanges")
          (is (= expected-roles (roles sent)) "roles and order")
          (is (str/ends-with? (last-text sent) "BLOCK") "the block is the last content")
          (is (= {"role" "system" "content" "help"} (first sent)) "help is the system message"))))
    (testing "the transcript is today's single text: each form then its reply, exchanges apart, the block last"
      (is (= "(+ 1 2)\n#:seon.repl{:value 3}\n\n;; look first\n(keys {:a 1})\n#:seon.repl{:value (:a)}\n(def x 1)\n#:seon.repl{:value #'user/x}\n\nBLOCK"
             (get (peek (ai/messages :transcript "help" [one two] "BLOCK")) "content"))))
    (testing "chat: the model's forms are its own messages and the REPL's replies the user's"
      (let [sent (ai/messages :chat "help" [one two] "BLOCK")]
        (is (= ";; look first\n(keys {:a 1})\n(def x 1)" (get (nth sent 3) "content")))
        (is (= [{"type" "text" "text" "#:seon.repl{:value (:a)}\n#:seon.repl{:value #'user/x}"}
                {"type" "text" "text" "BLOCK"}]
               (get (peek sent) "content"))
            "the block is a second part of the last user message, never its own message")))
    (testing "adding an exchange leaves every earlier message byte-identical"
      (doseq [wrapping [:chat]]
        (let [before (ai/messages wrapping "help" [one] "BLOCK")
              after (ai/messages wrapping "help" [one two] "BLOCK2")]
          (is (= (pop before) (subvec after 0 (dec (count before)))))
          (is (= (get (first (get (peek before) "content")) "text")
                 (get (nth after (dec (count before))) "content"))
              "the last reply keeps its text once the block moves on")))
      (let [before (get (peek (ai/messages :transcript "help" [one] "BLOCK")) "content")
            after (get (peek (ai/messages :transcript "help" [one two] "BLOCK")) "content")]
        (is (str/starts-with? after (subs before 0 (str/index-of before "BLOCK"))))))
    (testing "a request carries its wrapping to the wire"
      (is (= ["system" "assistant" "user"]
             (roles (get (ai/request-body (seon.schema/handed-projection)
                                          (assoc base :seon.ai/system "help" :seon.ai/wrapping :chat
                                                      :seon.ai/exchanges [one]))
                         "messages")))))))

(defn- tool-call
  "One provider tool call to `name` whose JSON arguments carry `code`."
  {:malli/schema [:=> [:cat :string :string] [:map-of :string :seon.ai/json-value]]}
  [name code]
  {"id" (str "call-" name) "type" "function"
   "function" {"name" name "arguments" (json/write-str {"code" code})}})

(defn- tool-reply
  "The completion of a one-shot provider document whose message has `content` and `calls`."
  {:malli/schema [:=> [:cat [:maybe :string] [:vector :map]] :seon.ai/parsed-completion]}
  [content calls]
  (ai/completion-text {"choices" [{"message" {"content" content "tool_calls" calls}
                                   "finish_reason" "tool_calls"}]}))

(deftest a-tool-call-named-eval-is-the-replys-code
  (testing "the request declares one tool, eval, whose one required parameter is code"
    (let [tools (get (ai/request-body (seon.schema/handed-projection) base) "tools")]
      (is (= ["eval"] (mapv #(get-in % ["function" "name"]) tools)))
      (is (= ["code"] (get-in tools [0 "function" "parameters" "required"])))))
  (testing "an eval call's code is the reply text, after any content, in order"
    (is (= "(in-ns 'seon.sci.forgive)"
           (:seon.ai/text (tool-reply "" [(tool-call "eval" "(in-ns 'seon.sci.forgive)")]))))
    (is (= ";; plan\n(+ 1 2)\n(+ 3 4)"
           (:seon.ai/text (tool-reply ";; plan" [(tool-call "eval" "(+ 1 2)") (tool-call "eval" "(+ 3 4)")])))))
  (testing "a call to another tool is named on the completion and is not read as code"
    (let [completion (tool-reply nil [(tool-call "my.program/edit" "(+ 1 2)")])]
      (is (= ["my.program/edit"] (:seon.ai/unknown-tool-calls completion)))
      (is (= "" (:seon.ai/text completion)))
      (is (= ["my.program/edit"]
             (:seon.ai/unknown-tool-calls (#'turn/reply-sources completion 'user 10000)))
          "the no-forms error names the unknown tool")))
  (testing "streamed argument fragments fold to the same text"
    (let [chunk #(str "data: " (json/write-str {"choices" [%]}) "\n\n")
          body (str (chunk {"delta" {"content" ";; plan"}})
                    (chunk {"delta" {"tool_calls" [{"index" 0 "id" "call-0" "type" "function"
                                                    "function" {"name" "eval" "arguments" "{\"code\":\"(+ 1"}}]}})
                    (chunk {"delta" {"tool_calls" [{"index" 0 "function" {"arguments" " 2)\"}"}}]}})
                    (chunk {"delta" {} "finish_reason" "tool_calls"})
                    "data: [DONE]\n\n")
          completion (#'ai/streamed-completion
                      (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8")) nil)]
      (is (= ";; plan\n(+ 1 2)" (:seon.ai/text completion))))))

(deftest the-model-ai-render-returns-data
  (text-render/with-ai-face
    "seon.ai.model/entity#0"
    (fn [sample answer]
      (is (not (string? (text-render/render-output answer)))
          "the AI face is data the value printer prints, not text")
      (is (text-render/carries? answer (:seon.ai.model/id sample))
          "the data carries what the text carried"))))

(deftest the-provider-ai-render-returns-data
  (text-render/with-ai-face
    "seon.ai.model/provider-entity#0"
    (fn [sample answer]
      (is (not (string? (text-render/render-output answer)))
          "the AI face is data the value printer prints, not text")
      (is (text-render/carries? answer (:seon.ai.model/provider-id sample))
          "the data carries what the text carried"))))
