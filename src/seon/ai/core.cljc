(ns seon.ai.core
  "Pure configuration and failure transforms for LLM adapters."
  (:require
   [clojure.string :as str]
   #?(:clj [clojure.edn :as reader]
      :cljs [cljs.reader :as reader])
   [seon.content-hash :as content-hash]
   [seon.repl.parse :as repl.parse]
   [seon.schema :as schema])
  #?(:clj
     (:import
      [java.net URI]
      [java.time ZonedDateTime]
      [java.time.format DateTimeFormatter])))

(schema/register! :seon.ai/msg :string)
(schema/register! :seon.ai/status :int)
(schema/register! :seon.ai/timeout? :boolean)
(schema/register! :seon.ai/transport? :boolean)
(schema/register! :seon.ai/retry-after-ms :int)
(schema/register! :seon.ai/evidence-error [:string {:min 1}])
(schema/register! :seon.ai/raw-body :string)
(schema/register!
 :seon.ai/error
 [:map
  [:seon.ai/msg :seon.ai/msg]
  [:seon.ai/status {:optional true} :seon.ai/status]
  [:seon.ai/timeout? {:optional true} :seon.ai/timeout?]
  [:seon.ai/transport? {:optional true} :seon.ai/transport?]
  [:seon.ai/retry-after-ms {:optional true} :seon.ai/retry-after-ms]
  [:seon.ai/evidence-error {:optional true} :seon.ai/evidence-error]
  [:seon.ai/raw-body {:optional true} :seon.ai/raw-body]])

(def shipped-defaults
  "Per-provider shipped defaults for resolved model configuration."
  {:deepseek       {:seon.ai/model "deepseek-v4-pro"
                    :seon.ai/temperature 0.7
                    :seon.ai/max-tokens 4096
                    :seon.ai/thinking "false"
                    :seon.ai/completion-limit-field :max-tokens
                    :seon.ai/timeout-ms 60000
                    :seon.ai/base-url "https://api.deepseek.com/v1"}
   :openai-compat  {:seon.ai/model "deepseek-v4-pro"
                    :seon.ai/max-tokens 4096
                    :seon.ai/thinking "false"
                    :seon.ai/completion-limit-field :max-tokens
                    :seon.ai/timeout-ms 60000}
   :anthropic      {:seon.ai/model "claude-opus-4-8"
                    :seon.ai/max-tokens 16000
                    :seon.ai/thinking "false"
                    :seon.ai/timeout-ms 60000
                    :seon.ai/base-url "https://api.anthropic.com/v1"}
   :diffusiongemma {:seon.ai/dg-backend :control}})

(def system-boundary
  "\n\n;; ──────── ↑ system message  │  ↓ context (:seon.ai/ctx) ────────\n\n")

(defn resolved-adapter
  "Provider adapter keyword for one immutable resolved configuration."
  [config]
  (case (:seon.ai/provider config)
    :anthropic :anthropic
    :diffusiongemma (if (= :control (:seon.ai/dg-backend config))
                      :diffusiongemma
                      :openai-compat)
    :typeahead :typeahead
    :openai-compat))

(defn bounded-evidence-error
  "Bound an evidence error using one resolved positive cap."
  [message response-identity-cap]
  (subs message 0 (min response-identity-cap (count message))))

(declare openai-chat-endpoint)

(defn openai-request-endpoint
  "Return bounded, credential-free chat-completions endpoint evidence."
  [url endpoint-cap]
  (try
    (let [endpoint (openai-chat-endpoint url)]
      (if (<= (count endpoint) endpoint-cap)
        endpoint
        {:seon.ai/msg
         "The normalized OpenAI endpoint exceeds the evidence bound."}))
    (catch #?(:clj Throwable :cljs :default) _
      {:seon.ai/msg "The configured OpenAI endpoint is not a valid URL."})))

(defn openai-chat-endpoint
  "Normalize an OpenAI-compatible base URL to its completion endpoint."
  [url]
  (let [parsed #?(:clj (URI. url) :cljs (js/URL. url))
        path #?(:clj (.getPath ^URI parsed) :cljs (.-pathname parsed))
        root-path
        (cond
          (str/ends-with? path "/chat/completions")
          (subs path 0 (- (count path) (count "/chat/completions")))

          (str/ends-with? path "/completions")
          (subs path 0 (- (count path) (count "/completions")))

          :else path)
        endpoint-path
        (str (str/replace root-path #"/+$" "") "/chat/completions")]
    #?(:clj
       (let [port (.getPort ^URI parsed)]
         (str (.getScheme ^URI parsed) "://" (.getHost ^URI parsed)
              (when (not= -1 port) (str ":" port))
              endpoint-path))
       :cljs
       (str (.-protocol parsed) "//" (.-host parsed) endpoint-path))))

(defn credential-candidates
  "Ordered environment-variable names and non-secret credential classes."
  [resolution]
  (let [config (:seon.ai/resolved-config resolution)
        provider (:seon.ai/provider config)
        configured (:seon.ai/api-key-env config)]
    (cond-> []
      configured
      (conj [configured :configured-env])

      (= :deepseek provider)
      (conj ["DEEPSEEK_API_KEY" :provider-default-env])

      (= :anthropic provider)
      (conj ["ANTHROPIC_API_KEY" :provider-default-env])

      true
      (conj ["SEON_AI_API_KEY" :conventional-env]))))

(defn config-evidence
  "Bounded non-secret evidence for one resolution and credential source."
  {:malli/schema
   [:function
    [:=> [:catn [::resolution :map]] :map]
    [:=> [:catn [::resolution :map] [::credential-source :map]] :map]]}
  ([resolution]
   (select-keys resolution
                [:seon.ai/resolved-config :seon.ai/provenance]))
  ([resolution credential-source]
   (assoc (config-evidence resolution)
          :seon.ai/credential-source credential-source)))

(defn first-form-complete?
  "Whether streamed text contains a reader-confirmed top-level form."
  {:malli/schema [:=> [:catn [::text :string]] :boolean]}
  [text]
  (boolean
   (and (repl.parse/first-top-level-close text)
        (some #(= :form (:seon.repl/kind %))
              (repl.parse/parse-forms text)))))

(def ^:private config-attrs
  [:seon.ai/provider
   :seon.ai/model
   :seon.ai/temperature
   :seon.ai/max-tokens
   :seon.ai/completion-limit-field
   :seon.ai/thinking
   :seon.ai/timeout-ms
   :seon.ai/base-url
   :seon.ai/api-key-env
   :seon.ai/dg-backend
   :seon.ai/extra-body-edn])

(def ^:private agent-override-attrs
  {:seon.ai/agent-provider :seon.ai/provider
   :seon.ai/agent-model :seon.ai/model
   :seon.ai/agent-temperature :seon.ai/temperature
   :seon.ai/agent-max-tokens :seon.ai/max-tokens
   :seon.ai/agent-completion-limit-field :seon.ai/completion-limit-field
   :seon.ai/agent-thinking :seon.ai/thinking
   :seon.ai/agent-timeout-ms :seon.ai/timeout-ms
   :seon.ai/agent-base-url :seon.ai/base-url
   :seon.ai/agent-api-key-env :seon.ai/api-key-env
   :seon.ai/agent-dg-backend :seon.ai/dg-backend
   :seon.ai/agent-extra-body-edn :seon.ai/extra-body-edn})

(def ^:private model-transport-cap-attrs
  [:seon.config.model-transport/response-identity-cap
   :seon.config.model-transport/endpoint-cap
   :seon.config.model-transport/connect-timeout-ms
   :seon.config.model-transport/maximum-response-bytes])

(defn agent-config-pull-pattern
  "Pull pattern for one agent's ordinary model overrides and phase policy."
  []
  (into [:seon.agent/id
         :seon.config/repl-mode
         :seon.ai/agent-max-retries
         :seon.ai/agent-attempt-timeout-ms
         :seon.ai/agent-fallback-variant]
        (keys agent-override-attrs)))

(defn config-pull-pattern
  "Pull pattern for the ordinary cluster values used by model resolution."
  []
  (into [:seon.config/id
         :seon.config.llm-retry/maximum-wait-ms
         :seon.config.llm-retry/maximum-total-wait-ms
         :seon.config.llm-retry/default-retries
         {:seon.config/model-variants ['*]}]
        (concat config-attrs model-transport-cap-attrs)))

(defn thinking-mode
  "Parse stored thinking configuration to the adapter value."
  {:malli/schema
   [:=> [:catn [::config :map]] [:or :boolean [:string {:min 1}]]]}
  [{:seon.ai/keys [thinking]}]
  (case thinking
    (nil "false") false
    "true" true
    thinking))

(defn parse-retry-after-ms
  "Parse `Retry-After` against `now-ms`, returning milliseconds or nil."
  {:malli/schema
   [:=> [:catn [::header [:maybe :string]] [::now-ms :int]] [:maybe :int]]}
  [header now-ms]
  (when (and (string? header) (not (str/blank? header)))
    (let [trimmed (str/trim header)
          seconds
          (try
            #?(:clj (let [value (Double/parseDouble trimmed)]
                      (when (Double/isFinite value) value))
               :cljs (let [value (js/Number trimmed)]
                       (when (js/isFinite value) value)))
            (catch #?(:clj Throwable :cljs :default) _ nil))]
      (if (some? seconds)
        (max 0 #?(:clj (Math/round (* seconds 1000.0))
                  :cljs (js/Math.round (* seconds 1000))))
        (try
          (let [date-ms
                #?(:clj
                   (.toEpochMilli
                    (.toInstant
                     (ZonedDateTime/parse
                      trimmed DateTimeFormatter/RFC_1123_DATE_TIME)))
                   :cljs
                   (let [value (js/Date.parse trimmed)]
                     (when-not (js/isNaN value) value)))]
            (when (some? date-ms)
              (max 0 (- date-ms now-ms))))
          (catch #?(:clj Throwable :cljs :default) _ nil))))))

(defn- agent-row-override-values
  [agent]
  (reduce-kv
   (fn [values agent-attr config-attr]
     (if (contains? agent agent-attr)
       (assoc values config-attr (get agent agent-attr))
       values))
   {}
   agent-override-attrs))

(defn- model-variants
  [config-row]
  (into {}
        (map (fn [variant]
               [(:seon.config/model-variant variant)
                (dissoc variant :db/id :seon.config/model-variant)]))
        (or (:seon.config/model-variants config-row) [])))

(defn extra-body-digest
  "SHA-256 of exact EDN bytes when they contain a map."
  {:malli/schema [:=> [:catn [::raw :string]] [:maybe :string]]}
  [raw]
  (let [parsed (try
                 (reader/read-string raw)
                 (catch #?(:clj Throwable :cljs :default) _ nil))]
    (when (map? parsed)
      (content-hash/sha-256 raw))))

(defn- resolve-config-values
  [shipped-defaults row-config overrides transport-caps]
  (let [pick (fn [key defaults]
               (cond
                 (contains? overrides key)
                 [(get overrides key) :agent-override]

                 (contains? row-config key)
                 [(get row-config key) :config-row]

                 (contains? defaults key)
                 [(get defaults key) :default]))
        [provider provider-source]
        (or (pick :seon.ai/provider {}) [:deepseek :default])
        defaults (get shipped-defaults provider {})
        resolved
        (reduce
         (fn [result key]
           (if-let [[value source] (pick key defaults)]
             (-> result
                 (assoc-in [:seon.ai/resolved-config key] value)
                 (assoc-in [:seon.ai/provenance key] source))
             result))
         {:seon.ai/resolved-config {:seon.ai/provider provider}
          :seon.ai/provenance {:seon.ai/provider provider-source}}
         [:seon.ai/model
          :seon.ai/temperature
          :seon.ai/max-tokens
          :seon.ai/completion-limit-field
          :seon.ai/thinking
          :seon.ai/timeout-ms
          :seon.ai/base-url
          :seon.ai/api-key-env
          :seon.ai/dg-backend])
        resolved
        (reduce-kv
         (fn [result attr [value source]]
           (-> result
               (assoc-in [:seon.ai/resolved-config attr] value)
               (assoc-in [:seon.ai/provenance attr] source)))
         resolved
         transport-caps)]
    (if-let [[raw source] (pick :seon.ai/extra-body-edn defaults)]
      (let [body (try
                   (reader/read-string raw)
                   (catch #?(:clj Throwable :cljs :default) _ nil))]
        (if-let [digest (and (map? body) (extra-body-digest raw))]
          (-> resolved
              (assoc :seon.ai/extra-body body)
              (assoc-in [:seon.ai/resolved-config
                         :seon.ai/extra-body-digest]
                        digest)
              (assoc-in [:seon.ai/provenance
                         :seon.ai/extra-body-digest]
                        source))
          resolved))
      resolved)))

(defn resolved-config-from-rows
  "Resolve LLM configuration from rows and one acquired attempt bound."
  {:malli/schema
   [:=> [:catn [::shipped-defaults :map]
                 [::config-row :map]
                 [::agent-row :map]
                 [::attempt-timeout-ms :int]]
    :map]}
  [shipped-defaults config-row agent-row attempt-timeout-ms]
  (let [transport-caps
        (into {}
              (keep (fn [attr]
                      (when (contains? config-row attr)
                        [attr [(get config-row attr) :config-row]])))
              model-transport-cap-attrs)
        provider-resolution
        (fn [row]
          (cond->
           (resolve-config-values
            shipped-defaults
            (select-keys config-row config-attrs)
            (agent-row-override-values row)
            transport-caps)
            (int? (:seon.ai/agent-max-retries row))
            (assoc :seon.ai/agent-max-retries
                   (:seon.ai/agent-max-retries row))

            true
            (assoc :seon.ai/agent-attempt-timeout-ms
                   (or (:seon.ai/agent-attempt-timeout-ms row)
                       attempt-timeout-ms))))
        primary (provider-resolution agent-row)
        fallback-variant (:seon.ai/agent-fallback-variant agent-row)
        fallback-row (get (model-variants config-row) fallback-variant)]
    (cond-> primary
      (and (keyword? fallback-variant) (map? fallback-row))
      (assoc :seon.ai/fallback-variant fallback-variant
             :seon.ai/fallback-config-resolution
             (provider-resolution fallback-row)))))
