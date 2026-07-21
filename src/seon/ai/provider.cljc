(ns seon.ai.provider
  "Define provider identity and locality without depending on orchestration.

   This leaf namespace breaks the planning/runtime dependency cycle: provider
   classification is ordinary immutable data that both `seon.ai` and
   `my.plan.internal` consume. Adapters and generation orchestration remain
   above it."
  (:require
    [seon.schema :as schema]))

(def provider-locality
  "Declared wire locality of every `:seon.ai/provider` value."
  {:deepseek       :frontier
   :anthropic      :frontier
   :openai-compat  :frontier
   :diffusiongemma :local-worker
   :typeahead      :local-worker})

(schema/register! :seon.ai/provider
  (into [:enum] (keys provider-locality)))

(defn frontier-provider?
  "True when `provider` is a hosted frontier LLM, not a local worker."
  {:malli/schema [:=> [:cat :seon.ai/provider] :boolean]}
  [provider]
  (= :frontier (get provider-locality provider)))
