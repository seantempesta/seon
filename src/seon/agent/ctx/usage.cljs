(ns seon.agent.ctx.usage
  "Derive normalized token usage from captured LLM responses.

   This namespace reads provider-specific usage maps stored on turns and
   projects comparable input, cached-input, and output counts without storing
   another normalized representation. Missing or malformed usage yields no
   projection rather than disrupting context rendering."
  (:require
    [cljs.reader :as reader]
    [seon.schema :as schema]))

(schema/register! ::usage-edn :string)
(schema/register! ::total :int)
(schema/register! ::cached :int)
(schema/register! ::output :int)
(schema/register! ::provider-shape
  [:enum :openai-compat :anthropic :unknown])

(schema/register! ::usage
  [:map
   [::total ::total]
   [::cached ::cached]
   [::output ::output]
   [::provider-shape ::provider-shape]])

(defn- parse-edn
  "Read the EDN usage string into a keywordized map, or nil on any
   failure (absent / garbage). Errors-as-values."
  [s]
  (when (and (string? s) (seq s))
    (try
      (let [m (reader/read-string s)]
        (when (map? m) m))
      (catch :default _ nil))))

(defn extract
  "Normalize the persisted `:seon.agent.turn/llm-usage` EDN into a triple.

   Returns the normalized `{::total ::cached ::output ::provider-shape}` triple, or
   nil when usage is absent/unparseable. `::total` = the TRUE total input
   tokens (DeepSeek: `prompt_tokens` as-is; Anthropic: `input_tokens` +
   cache read + cache creation). `::cached` = tokens served from cache
   this turn. `::output` = completion/output tokens."
  {:malli/schema [:=> [:catn [::usage-edn [:maybe ::usage-edn]]]
                  [:maybe ::usage]]}
  [edn-str]
  (when-let [m (parse-edn edn-str)]
    (cond
      ;; OpenAI / DeepSeek — prompt_tokens INCLUDES cached.
      (contains? m :prompt_tokens)
      {::total          (or (:prompt_tokens m) 0)
       ::cached         (or (:prompt_cache_hit_tokens m) 0)
       ::output         (or (:completion_tokens m) 0)
       ::provider-shape :openai-compat}

      ;; Anthropic — input_tokens EXCLUDES cached; cache fields ADD.
      (contains? m :input_tokens)
      (let [in     (or (:input_tokens m) 0)
            read*  (or (:cache_read_input_tokens m) 0)
            create (or (:cache_creation_input_tokens m) 0)]
        {::total          (+ in read* create)
         ::cached         read*
         ::output         (or (:output_tokens m) 0)
         ::provider-shape :anthropic})

      :else
      {::total          0
       ::cached         0
       ::output         0
       ::provider-shape :unknown})))
