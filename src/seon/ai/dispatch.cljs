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

;; The turn boundary has two intentional call shapes: buffered calls pass the
;; context string, while streaming calls add the stream flag to a request map.
(schema/register! ::request
  [:map
   [:seon.ai/ctx :seon.ai/ctx]
   [:seon.ai/system-prompt {:optional true} :seon.ai/system-prompt]
   [:seon.ai/stream? {:optional true} :seon.ai/stream?]
   [:seon.ai/abort-signal {:optional true} :seon.ai/abort-signal]
   [:seon.ai/config-resolution
    {:optional true}
    :seon.ai/config-resolution]])
(schema/register! ::arg [:or :string ::request])
(schema/register! ::llm-fn 'fn?)

;; `:text` is the established turn-loop adapter result key. Provider adapters
;; may add raw/error fields; the deterministic stub returns only this minimum.
(schema/register! ::stub-response
  [:map [:text :string] [:seon.ai/adapter :seon.ai/adapter]])

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

(defn- missing-resolution
  []
  (.resolve js/Promise
            {:text ""
             :seon.ai/error
             {:seon.ai/msg
              "missing :seon.ai/config-resolution — resolve ordinary authority data at the prompt coordinate before dispatch"}}))

(defn llm-fn
  "Build a per-call dispatching agent LLM function."
  {:malli/schema [:=> [:cat] ::llm-fn]}
  []
  (fn [arg]
    (if-let [resolution (when (map? arg)
                          (:seon.ai/config-resolution arg))]
      ((adapter resolution) arg)
      (missing-resolution))))
