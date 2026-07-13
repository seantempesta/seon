(ns seon.ai.dispatch
  "Effective-provider dispatch for the agent LLM boundary.

   Provider and DiffusionGemma backend selection are read for every call,
   after the agent turn has established its ambient database scope. This
   keeps per-agent provider overrides reactive and prevents a hosted agent
   from retaining an adapter chosen at boot. Missing credentials select the
   deterministic stub; no provider constructor is invoked in that case."
  (:require
    [seon.ai :as ai]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.diffusiongemma :as diffusiongemma]
    [seon.ai.openai-compat :as openai]
    [seon.ai.tokens :as tokens]
    [seon.ai.typeahead :as typeahead]
    [seon.config :as config]
    [seon.schema :as schema]))

;; The turn boundary has two intentional call shapes: buffered calls pass the
;; context string, while streaming calls add the stream flag to a request map.
(schema/register! ::request
  [:map
   [:seon.ai/ctx :seon.ai/ctx]
   [:seon.ai/stream? {:optional true} :seon.ai/stream?]])
(schema/register! ::arg [:or :string ::request])
(schema/register! ::llm-fn 'fn?)

;; `:text` is the established turn-loop adapter result key. Provider adapters
;; may add raw/error fields; the deterministic stub returns only this minimum.
(schema/register! ::stub-response [:map [:text :string]])

(defn stub
  "Return the deterministic no-credentials LLM reply."
  {:malli/schema [:=> [:catn [::arg ::arg]] ::stub-response]}
  [arg]
  (let [ctx  (ai/llm-arg->ctx arg)
        text (str
               ";; stub LLM here — the real one needs DEEPSEEK_API_KEY\n"
               ";; say hello to your human via the message/user function\n"
               "(message/user\n"
               "  "
               (pr-str (str "hello from the stub LLM — saw "
                            (tokens/estimate ctx) " tokens of ctx"))
               ")\n")]
    (.then (.resolve js/Promise nil) (fn [_] {:text text}))))

(defn adapter
  "The agent adapter for the currently effective provider."
  {:malli/schema [:=> [:cat] ::llm-fn]}
  []
  (case (ai/provider)
    :anthropic
    (if (config/anthropic-api-key)
      (anthropic/agent-adapter)
      stub)

    :diffusiongemma
    (case (ai/dg-backend)
      :control (if (diffusiongemma/api-configured?)
                 (diffusiongemma/agent-adapter)
                 stub)
      (if (openai/api-key-configured?)
        (openai/agent-adapter)
        stub))

    :typeahead
    (if (diffusiongemma/api-configured?)
      (typeahead/agent-adapter)
      stub)

    ;; DeepSeek and every OpenAI-compatible gateway share the same adapter.
    (if (openai/api-key-configured?)
      (openai/agent-adapter)
      stub)))

(defn llm-fn
  "Build a per-call dispatching agent LLM function."
  {:malli/schema [:=> [:cat] ::llm-fn]}
  []
  (fn [arg]
    ((adapter) arg)))
