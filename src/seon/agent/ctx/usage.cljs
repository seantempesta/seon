(ns seon.agent.ctx.usage
  "Read-side extractor for the per-turn LLM token usage — the FIRST (and
   so far only) consumer of `:seon.agent.turn/llm-usage`, the verbatim
   provider `usage` map persisted as an EDN string by
   [[seon.agent.turn/ask-and-eval!]].

   There is NO normalization at capture time: the map carries
   provider-specific, differently-named keys (token-usage-pipeline
   research, §3). This ns DERIVES a normalized `{total cached output}`
   triple at read time (reactive-context — nothing normalized is stored),
   branching on KEY PRESENCE so it works for old turns whose provider may
   differ from the live one:

     - `:prompt_tokens` present  => OpenAI/DeepSeek shape. `prompt_tokens`
       is the FULL input (cached is a SUBSET); cached = `prompt_cache_hit_tokens`.
     - `:input_tokens` present   => Anthropic shape. `input_tokens` is the
       UNCACHED remainder; the cache fields are DISJOINT and ADD to it for
       the true total; cached = `cache_read_input_tokens`.

   Errors are values: a turn with no usage (stub-LLM turn) or an
   unparseable string yields nil — never a throw."
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
  "Given the persisted `:seon.agent.turn/llm-usage` EDN string, return the
   normalized `{::total ::cached ::output ::provider-shape}` triple, or
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
