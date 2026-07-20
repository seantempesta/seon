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
(schema/register! ::token-count [:int {:min 0}])
(schema/register! ::total ::token-count)
(schema/register! ::cached ::token-count)
(schema/register! ::output ::token-count)
(schema/register! ::provider-shape
  [:enum :openai-compat :anthropic])
(schema/register! ::diagnostic :string)
(schema/register! ::line :string)

(schema/register! ::usage
  [:map {:closed true}
   [::total ::total]
   [::cached {:optional true} ::cached]
   [::output ::output]
   [::provider-shape ::provider-shape]])

(schema/register! ::analysis
  [:map {:closed true}
   [::usage {:optional true} ::usage]
   [::diagnostic {:optional true} ::diagnostic]])

(schema/register! ::turn
  [:map
   [:seon.agent.turn/llm-usage {:optional true} :string]
   [:seon.agent.turn/usage-estimated? {:optional true} :boolean]])

(schema/register! ::turn-projection
  [:map {:closed true}
   [::usage {:optional true} ::usage]
   [::diagnostic {:optional true} ::diagnostic]
   [::line {:optional true} ::line]])

(defn- parse-edn
  "Read the EDN usage string into a keywordized map, or nil on any
   failure (absent / garbage). Errors-as-values."
  [s]
  (when (and (string? s) (seq s))
    (try
      (let [m (reader/read-string s)]
        (when (map? m) m))
      (catch :default _ nil))))

(defn- token-count?
  [x]
  (and (int? x) (not (neg? x))))

(defn- invalid-counts
  [provider fields]
  {::diagnostic
   (str provider " usage requires non-negative integer "
        (if (= 1 (count fields)) "field " "fields ")
        (pr-str (vec fields)))})

(defn- openai-analysis
  [m]
  (let [prompt (:prompt_tokens m)
        output (:completion_tokens m)
        direct? (contains? m :prompt_cache_hit_tokens)
        nested-map (:prompt_tokens_details m)
        nested? (and (map? nested-map) (contains? nested-map :cached_tokens))
        direct (:prompt_cache_hit_tokens m)
        nested (:cached_tokens nested-map)
        invalid (cond-> []
                  (not (token-count? prompt)) (conj :prompt_tokens)
                  (not (token-count? output)) (conj :completion_tokens)
                  (and direct? (not (token-count? direct)))
                  (conj :prompt_cache_hit_tokens)
                  (and nested? (not (token-count? nested)))
                  (conj :prompt_tokens_details.cached_tokens))]
    (cond
      (seq invalid)
      (invalid-counts "OpenAI-compatible" invalid)

      (and direct? nested? (not= direct nested))
      {::diagnostic
       (str "OpenAI-compatible cache fields disagree: "
            ":prompt_cache_hit_tokens=" direct " and "
            ":prompt_tokens_details/:cached_tokens=" nested)}

      :else
      {::usage
       (cond-> {::total prompt
                ::output output
                ::provider-shape :openai-compat}
         (or direct? nested?) (assoc ::cached (if direct? direct nested)))})))

(defn- anthropic-analysis
  [m]
  (let [input (:input_tokens m)
        output (:output_tokens m)
        read? (contains? m :cache_read_input_tokens)
        create? (contains? m :cache_creation_input_tokens)
        read* (:cache_read_input_tokens m)
        create (:cache_creation_input_tokens m)
        invalid (cond-> []
                  (not (token-count? input)) (conj :input_tokens)
                  (not (token-count? output)) (conj :output_tokens)
                  (and read? (not (token-count? read*)))
                  (conj :cache_read_input_tokens)
                  (and create? (not (token-count? create)))
                  (conj :cache_creation_input_tokens))]
    (if (seq invalid)
      (invalid-counts "Anthropic" invalid)
      {::usage
       (cond-> {::total (+ input (if read? read* 0) (if create? create 0))
                ::output output
                ::provider-shape :anthropic}
         read? (assoc ::cached read*))})))

(defn analyze
  "Normalize one persisted provider usage string or explain its rejection."
  {:malli/schema [:=> [:catn [::usage-edn ::usage-edn]] ::analysis]}
  [edn-str]
  (if-let [m (parse-edn edn-str)]
    (cond
      (contains? m :prompt_tokens) (openai-analysis m)
      (contains? m :input_tokens) (anthropic-analysis m)
      :else {::diagnostic
             "Unknown usage shape: expected :prompt_tokens or :input_tokens."})
    {::diagnostic "Malformed usage EDN: expected a non-empty map."}))

(defn extract
  "Normalize one persisted provider usage string, or return nil."
  {:malli/schema [:=> [:catn [::usage-edn ::usage-edn]] [:or :nil ::usage]]}
  [edn-str]
  (::usage (analyze edn-str)))

(defn turn-projection
  "Derive normalized usage, diagnostic, and compact line from one turn."
  {:malli/schema [:=> [:catn [::turn ::turn]] ::turn-projection]}
  [{usage-edn :seon.agent.turn/llm-usage
    estimated? :seon.agent.turn/usage-estimated?}]
  (if-not (string? usage-edn)
    {}
    (let [{normalized ::usage :as analysis} (analyze usage-edn)]
      (if-not normalized
        analysis
        (let [{::keys [total cached output]} normalized]
          (assoc analysis ::line
                 (str "usage · "
                      (when estimated? "est. (stream abort) · ")
                      "total " total " · "
                      (if (some? cached)
                        (str "cached " cached)
                        "no cache data")
                      " · output " output)))))))

(defn render-html
  "Render one turn's compact usage or diagnostic line for the agent page."
  {:malli/schema [:=> [:catn [::turn ::turn]] [:or :nil :seon.render.canvas/hiccup]]}
  [turn]
  (let [{::keys [line diagnostic]} (turn-projection turn)]
    (cond
      line
      [:div {:class "px-2 pb-1 text-2xs font-mono text-text-600"} line]

      diagnostic
      [:div {:class "px-2 pb-1 text-2xs font-mono text-warning"}
       (str "usage unavailable · " diagnostic)]

      :else nil)))
