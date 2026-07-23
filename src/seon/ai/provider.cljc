(ns seon.ai.provider
  "Define provider identity and hosted wire descriptors as data.

   This leaf namespace breaks the planning/runtime dependency cycle: provider
   classification and hosted wire policy are ordinary immutable data that both
   `seon.ai` and `my.plan.internal` consume. Resolution, adapters, and
   generation orchestration remain above it."
  (:require
    [seon.schema :as schema]))

(def provider-locality
  "Declared wire locality of every `:seon.ai/provider` value."
  {:deepseek       :frontier
   :kimi           :frontier
   :zai            :frontier
   :openrouter     :frontier
   :gemini         :frontier
   :anthropic      :frontier
   :openai-compat  :frontier
   :diffusiongemma :local-worker
   :typeahead      :local-worker})

(schema/register! :seon.ai/provider
  (into [:enum] (keys provider-locality)))

(schema/register!
  :seon.ai.provider/id
  [:keyword {:seon.db/identity true}])
(schema/register!
  :seon.ai.provider/adapter-core
  [:enum :openai-compat :anthropic])
(schema/register!
  :seon.ai.provider/locality
  [:enum :frontier])
(schema/register! :seon.ai.provider/default-base-url :string)
(schema/register!
  :seon.ai.provider/endpoint-policy
  [:enum :openai-chat-completions :anthropic-messages])
(schema/register! :seon.ai.provider/credential-header :string)
(schema/register! :seon.ai.provider/credential-prefix :string)
(schema/register! :seon.ai.provider/default-api-key-env :string)
(schema/register! :seon.ai.provider/retry-after-header :string)
(schema/register!
  :seon.ai.provider/retry-after-format
  [:enum :delta-seconds-or-http-date])
(schema/register! :seon.ai.provider/default-model :string)
(schema/register! :seon.ai.provider/default-temperature :double)
(schema/register! :seon.ai.provider/default-max-tokens [:int {:min 1}])
(schema/register! :seon.ai.provider/default-thinking :string)
(schema/register!
  :seon.ai.provider/completion-limit-field
  [:enum :max-tokens :max-completion-tokens])
(schema/register!
  :seon.ai.provider/thinking-policy
  [:enum
   :omit
   :openai-reasoning-effort
   :deepseek-thinking-toggle
   :anthropic-adaptive])
(schema/register!
  :seon.ai.provider/stream-options-policy
  [:enum :none :openai-include-usage :anthropic-native-events])
(schema/register! :seon.ai.provider/streaming-advertised? :boolean)
(schema/register! :seon.ai.provider/streaming-actually-works? :boolean)
(schema/register! :seon.ai.provider/usage-in-stream? :boolean)
(schema/register! :seon.ai.provider/function-calling? :boolean)
(schema/register! :seon.ai.provider/response-format? :boolean)
(schema/register!
  :seon.ai.provider/allowed-tool-choices
  [:set [:enum :auto :none :required]])
(schema/register!
  :seon.ai.provider/quirks
  [:set :keyword])
(schema/register! :seon.ai.provider.usage/input-field :keyword)
(schema/register! :seon.ai.provider.usage/output-field :keyword)
(schema/register! :seon.ai.provider.usage/total-field :keyword)
(schema/register!
  :seon.ai.provider.usage/cached-direct-fields
  [:set :keyword])
(schema/register! :seon.ai.provider.usage/cached-parent-field :keyword)
(schema/register! :seon.ai.provider.usage/cached-nested-field :keyword)

(schema/register!
  :seon.ai.provider/descriptor
  [:map {:seon.db/entity true}
   [:seon.ai.provider/id :seon.ai.provider/id]
   [:seon.ai.provider/adapter-core :seon.ai.provider/adapter-core]
   [:seon.ai.provider/locality :seon.ai.provider/locality]
   [:seon.ai.provider/default-base-url
    :seon.ai.provider/default-base-url]
   [:seon.ai.provider/endpoint-policy :seon.ai.provider/endpoint-policy]
   [:seon.ai.provider/credential-header :seon.ai.provider/credential-header]
   [:seon.ai.provider/credential-prefix :seon.ai.provider/credential-prefix]
   [:seon.ai.provider/default-api-key-env
    :seon.ai.provider/default-api-key-env]
   [:seon.ai.provider/retry-after-header
    :seon.ai.provider/retry-after-header]
   [:seon.ai.provider/retry-after-format
    :seon.ai.provider/retry-after-format]
   [:seon.ai.provider/default-model
    {:optional true}
    :seon.ai.provider/default-model]
   [:seon.ai.provider/default-temperature
    {:optional true}
    :seon.ai.provider/default-temperature]
   [:seon.ai.provider/default-max-tokens
    {:optional true}
    :seon.ai.provider/default-max-tokens]
   [:seon.ai.provider/default-thinking
    {:optional true}
    :seon.ai.provider/default-thinking]
   [:seon.ai.provider/completion-limit-field
    {:optional true}
    :seon.ai.provider/completion-limit-field]
   [:seon.ai.provider/thinking-policy :seon.ai.provider/thinking-policy]
   [:seon.ai.provider/stream-options-policy
    :seon.ai.provider/stream-options-policy]
   [:seon.ai.provider/streaming-advertised?
    :seon.ai.provider/streaming-advertised?]
   [:seon.ai.provider/streaming-actually-works?
    :seon.ai.provider/streaming-actually-works?]
   [:seon.ai.provider/usage-in-stream?
    :seon.ai.provider/usage-in-stream?]
   [:seon.ai.provider/function-calling?
    :seon.ai.provider/function-calling?]
   [:seon.ai.provider/response-format?
    :seon.ai.provider/response-format?]
   [:seon.ai.provider/allowed-tool-choices
    :seon.ai.provider/allowed-tool-choices]
   [:seon.ai.provider/quirks
    {:optional true}
    :seon.ai.provider/quirks]
   [:seon.ai.provider.usage/input-field
    :seon.ai.provider.usage/input-field]
   [:seon.ai.provider.usage/output-field
    :seon.ai.provider.usage/output-field]
   [:seon.ai.provider.usage/total-field
    {:optional true}
    :seon.ai.provider.usage/total-field]
   [:seon.ai.provider.usage/cached-direct-fields
    {:optional true}
    :seon.ai.provider.usage/cached-direct-fields]
   [:seon.ai.provider.usage/cached-parent-field
    {:optional true}
    :seon.ai.provider.usage/cached-parent-field]
   [:seon.ai.provider.usage/cached-nested-field
    {:optional true}
    :seon.ai.provider.usage/cached-nested-field]])

(def ^:private openai-usage
  {:seon.ai.provider.usage/input-field :prompt_tokens
   :seon.ai.provider.usage/output-field :completion_tokens
   :seon.ai.provider.usage/total-field :total_tokens})

(def ^:private openai-capabilities
  {:seon.ai.provider/locality :frontier
   :seon.ai.provider/adapter-core :openai-compat
   :seon.ai.provider/endpoint-policy :openai-chat-completions
   :seon.ai.provider/credential-header "Authorization"
   :seon.ai.provider/credential-prefix "Bearer "
   :seon.ai.provider/retry-after-header "retry-after"
   :seon.ai.provider/retry-after-format :delta-seconds-or-http-date
   :seon.ai.provider/streaming-advertised? true
   :seon.ai.provider/streaming-actually-works? false
   :seon.ai.provider/usage-in-stream? true
   :seon.ai.provider/function-calling? true
   :seon.ai.provider/response-format? true
   :seon.ai.provider/allowed-tool-choices #{:auto :none :required}})

(defn- openai-row
  [provider-id fields]
  (merge openai-capabilities
         openai-usage
         {:seon.ai.provider/id provider-id}
         fields))

(def hosted-provider-descriptors
  "Hosted provider rows interpreted by the two portable wire cores.

   DeepSeek, Kimi, and Z.AI request policy is grounded in the maintained
   LiteLLM checkout:
   `deepseek.clj:8-121`, `kimi.clj:9-143`, `zai.clj:9-142`, and
   `openai_compatible.clj:164-175`. OpenRouter's current terminal usage and
   cache detail come from its primary API reference because the vendored
   `openrouter.clj:236-264` stream transform drops usage. Gemini was qualified
   live through Google's documented OpenAI-compatible Chat Completions surface
   on 2026-07-23; its native GenerateContent protocol is not this row."
  {:deepseek
   (openai-row
    :deepseek
    {:seon.ai.provider/default-base-url "https://api.deepseek.com"
     :seon.ai.provider/default-api-key-env "DEEPSEEK_API_KEY"
     :seon.ai.provider/default-model "deepseek-v4-pro"
     :seon.ai.provider/default-temperature 0.7
     :seon.ai.provider/default-max-tokens 4096
     :seon.ai.provider/default-thinking "false"
     :seon.ai.provider/completion-limit-field :max-tokens
     :seon.ai.provider/thinking-policy :deepseek-thinking-toggle
     :seon.ai.provider/stream-options-policy :openai-include-usage
     :seon.ai.provider/streaming-actually-works? true
     :seon.ai.provider.usage/cached-direct-fields
     #{:prompt_cache_hit_tokens :cached_tokens}
     :seon.ai.provider.usage/cached-parent-field :prompt_tokens_details
     :seon.ai.provider.usage/cached-nested-field :cached_tokens})

   :kimi
   (openai-row
    :kimi
    {:seon.ai.provider/default-base-url "https://api.moonshot.ai/v1"
     :seon.ai.provider/default-api-key-env "MOONSHOT_API_KEY"
     :seon.ai.provider/default-model "kimi-k3"
     :seon.ai.provider/default-max-tokens 131072
     :seon.ai.provider/completion-limit-field :max-completion-tokens
     :seon.ai.provider/thinking-policy :omit
     :seon.ai.provider/stream-options-policy :openai-include-usage
     :seon.ai.provider.usage/cached-direct-fields #{:cached_tokens}
     :seon.ai.provider.usage/cached-parent-field :prompt_tokens_details
     :seon.ai.provider.usage/cached-nested-field :cached_tokens
     :seon.ai.provider/quirks #{:thinking-cannot-be-disabled}})

   :zai
   (openai-row
    :zai
    {:seon.ai.provider/default-base-url "https://api.z.ai/api/paas/v4"
     :seon.ai.provider/default-api-key-env "ZAI_API_KEY"
     :seon.ai.provider/default-model "glm-5.2"
     :seon.ai.provider/default-max-tokens 4096
     :seon.ai.provider/completion-limit-field :max-tokens
     :seon.ai.provider/thinking-policy :openai-reasoning-effort
     :seon.ai.provider/stream-options-policy :openai-include-usage
     :seon.ai.provider/allowed-tool-choices #{:auto}
     :seon.ai.provider.usage/cached-direct-fields #{:cached_tokens}
     :seon.ai.provider.usage/cached-parent-field :prompt_tokens_details
     :seon.ai.provider.usage/cached-nested-field :cached_tokens
     :seon.ai.provider/quirks
     #{:do-sample-extra-body
       :tool-stream-extra-body
       :clear-thinking-extra-body}})

   :openrouter
   (openai-row
    :openrouter
    {:seon.ai.provider/default-base-url "https://openrouter.ai/api/v1"
     :seon.ai.provider/default-api-key-env "OPENROUTER_API_KEY"
     :seon.ai.provider/completion-limit-field :max-tokens
     :seon.ai.provider/thinking-policy :openai-reasoning-effort
     :seon.ai.provider/stream-options-policy :none
     :seon.ai.provider.usage/cached-parent-field :prompt_tokens_details
     :seon.ai.provider.usage/cached-nested-field :cached_tokens
     :seon.ai.provider/quirks
     #{:usage-always-in-final-stream-chunk
       :public-attribution-headers-optional}})

   :anthropic
   {:seon.ai.provider/id :anthropic
    :seon.ai.provider/locality :frontier
    :seon.ai.provider/adapter-core :anthropic
    :seon.ai.provider/default-base-url "https://api.anthropic.com/v1"
    :seon.ai.provider/endpoint-policy :anthropic-messages
    :seon.ai.provider/credential-header "x-api-key"
    :seon.ai.provider/credential-prefix ""
    :seon.ai.provider/default-api-key-env "ANTHROPIC_API_KEY"
    :seon.ai.provider/retry-after-header "retry-after"
    :seon.ai.provider/retry-after-format :delta-seconds-or-http-date
    :seon.ai.provider/default-model "claude-opus-4-8"
    :seon.ai.provider/default-max-tokens 16000
    :seon.ai.provider/default-thinking "false"
    :seon.ai.provider/thinking-policy :anthropic-adaptive
    :seon.ai.provider/stream-options-policy :anthropic-native-events
    :seon.ai.provider/streaming-advertised? true
    :seon.ai.provider/streaming-actually-works? true
    :seon.ai.provider/usage-in-stream? true
    :seon.ai.provider/function-calling? true
    :seon.ai.provider/response-format? false
    :seon.ai.provider/allowed-tool-choices #{:auto :none :required}
    :seon.ai.provider.usage/input-field :input_tokens
    :seon.ai.provider.usage/output-field :output_tokens
    :seon.ai.provider.usage/cached-direct-fields
    #{:cache_read_input_tokens}}

   :gemini
   (openai-row
    :gemini
    {:seon.ai.provider/default-base-url
     "https://generativelanguage.googleapis.com/v1beta/openai"
     :seon.ai.provider/default-api-key-env "GEMINI_API_KEY"
     :seon.ai.provider/default-model "gemini-2.5-flash-lite"
     :seon.ai.provider/default-max-tokens 4096
     :seon.ai.provider/completion-limit-field :max-tokens
     :seon.ai.provider/thinking-policy :openai-reasoning-effort
     :seon.ai.provider/stream-options-policy :openai-include-usage
     :seon.ai.provider/streaming-actually-works? true
     :seon.ai.provider.usage/cached-parent-field :prompt_tokens_details
     :seon.ai.provider.usage/cached-nested-field :cached_tokens
     :seon.ai.provider/quirks
     #{:usage-on-content-chunks
       :beta-openai-compatibility
     :native-generate-content-is-a-different-core}})})

(defn frontier-provider?
  "True when `provider` is a hosted frontier LLM, not a local worker."
  {:malli/schema [:=> [:cat :seon.ai/provider] :boolean]}
  [provider]
  (= :frontier (get provider-locality provider)))
