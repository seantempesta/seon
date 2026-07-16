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
    [seon.db :as db]
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
  "The agent adapter for the currently effective provider."
  {:malli/schema
   [:function
    [:=> [:cat] ::llm-fn]
    [:=> [:cat :seon.ai/config-resolution] ::llm-fn]]}
  ([]
   ;; Retained for direct operator/debug selection. Provider attempts use the
   ;; explicit arity below through [[llm-fn]].
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
  ([resolution]
   (let [config (:seon.ai/resolved-config resolution)]
     (case (:seon.ai/provider config)
       :anthropic
       (if (config/anthropic-api-key)
         (anthropic/agent-adapter)
         stub)

       :diffusiongemma
       (case (:seon.ai/dg-backend config)
         :control (if (diffusiongemma/api-configured?)
                    (diffusiongemma/agent-adapter)
                    stub)
         (if (openai/api-key-configured? resolution)
           (openai/agent-adapter)
           stub))

       :typeahead
       (if (diffusiongemma/api-configured?)
         (typeahead/agent-adapter)
         stub)

       (if (openai/api-key-configured? resolution)
         (openai/agent-adapter)
         stub)))))

(defn llm-fn
  "Build a per-call dispatching agent LLM function."
  {:malli/schema [:=> [:cat] ::llm-fn]}
  []
  (fn [arg]
    (let [supplied-resolution
          (when (map? arg) (:seon.ai/config-resolution arg))
          database (when-not supplied-resolution @db/*conn*)
          agent-id (when-not supplied-resolution (db/current-agent-id))
          resolution (or supplied-resolution
                         (ai/resolved-config
                           (cond-> {:seon.db/db database}
                             agent-id (assoc :seon.agent/id agent-id))))
          request (if (map? arg)
                    (assoc arg :seon.ai/config-resolution resolution)
                    {:seon.ai/ctx arg
                     :seon.ai/config-resolution resolution})]
      ((adapter resolution) request))))
