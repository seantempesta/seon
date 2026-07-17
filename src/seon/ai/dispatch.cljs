(ns seon.ai.dispatch
  "Effective-provider dispatch for the agent LLM boundary.

   Provider and DiffusionGemma backend selection come from the immutable
   `:seon.ai/config-resolution` captured at the prompt coordinate. Missing
   credentials select the deterministic stub; missing resolution is an
   explicit error value."
  (:require
    [seon.ai :as ai]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.diffusiongemma :as diffusiongemma]
    [seon.ai.openai-compat :as openai]
    [seon.ai.tokens :as tokens]
    [seon.ai.typeahead :as typeahead]
    [seon.schema :as schema]))

(schema/register! ::llm-fn 'fn?)

;; `:text` is the established turn-loop adapter result key. Provider adapters
;; may add raw/error fields; the deterministic stub returns only this minimum.
(schema/register! ::stub-response
  [:map [:text :string] [:seon.ai/adapter :seon.ai/adapter]])

(defn stub
  "Return the deterministic no-credentials LLM reply."
  {:malli/schema [:=> [:catn [::request :seon.ai/request]] ::stub-response]}
  [request]
  (let [ctx  (:seon.ai/ctx request)
        text (str
               ";; stub LLM here — the real one needs DEEPSEEK_API_KEY\n"
               ";; say hello to your human via the message/user function\n"
               "(message/user\n"
               "  "
               (pr-str (str "hello from the stub LLM — saw "
                            (tokens/estimate ctx) " tokens of ctx"))
               ")\n")]
    (.then (.resolve js/Promise nil)
           (fn [_] {:text text :seon.ai/adapter :stub}))))

(defn adapter
  "The agent adapter selected by one authority config resolution."
  {:malli/schema [:=> [:cat :seon.ai/config-resolution] ::llm-fn]}
  [resolution]
  (let [config (:seon.ai/resolved-config resolution)]
    (case (:seon.ai/provider config)
      :anthropic
      (if (anthropic/api-key-configured? resolution)
        (anthropic/agent-adapter)
        stub)

      :diffusiongemma
      (case (:seon.ai/dg-backend config)
        :control (if (diffusiongemma/api-configured? resolution)
                   (diffusiongemma/agent-adapter)
                   stub)
        (if (openai/api-key-configured? resolution)
          (openai/agent-adapter)
          stub))

      :typeahead
      (if (diffusiongemma/api-configured? resolution)
        (typeahead/agent-adapter)
        stub)

      (if (openai/api-key-configured? resolution)
        (openai/agent-adapter)
        stub))))

(defn- invalid-request
  []
  (.resolve js/Promise
            {:text ""
             :seon.ai/error
             {:seon.ai/msg
              "invalid LLM request — pass one closed :seon.ai/request map with :seon.ai/ctx and :seon.ai/config-resolution"}}))

(defn llm-fn
  "Build a per-call dispatching agent LLM function."
  {:malli/schema [:=> [:cat] ::llm-fn]}
  []
  (fn [request]
    (if (schema/valid-candidate-value? :seon.ai/request request)
      ((adapter (:seon.ai/config-resolution request)) request)
      (invalid-request))))
