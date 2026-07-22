(ns seon.agent.ctx.usage
  "Derive normalized token usage from captured LLM responses.

   This namespace reads provider-specific named attributes stored on turns and
   projects comparable input, cached-input, and output counts without storing
   another normalized representation. Missing or incomplete usage yields no
   projection rather than disrupting context rendering."
  (:require
    [seon.schema :as schema]))

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
   [:seon.agent.turn.usage/prompt-tokens {:optional true} :int]
   [:seon.agent.turn.usage/completion-tokens {:optional true} :int]
   [:seon.agent.turn.usage/cached-tokens {:optional true} :int]
   [:seon.agent.turn.usage/input-tokens {:optional true} :int]
   [:seon.agent.turn.usage/output-tokens {:optional true} :int]
   [:seon.agent.turn.usage/cache-read-input-tokens {:optional true} :int]
   [:seon.agent.turn.usage/cache-creation-input-tokens {:optional true} :int]
   [:seon.agent.turn/usage-estimated? {:optional true} :boolean]])

(schema/register! ::turn-projection
  [:map {:closed true}
   [::usage {:optional true} ::usage]
   [::diagnostic {:optional true} ::diagnostic]
   [::line {:optional true} ::line]])

(def turn-attributes
  [:seon.agent.turn.usage/prompt-tokens
   :seon.agent.turn.usage/completion-tokens
   :seon.agent.turn.usage/cached-tokens
   :seon.agent.turn.usage/input-tokens
   :seon.agent.turn.usage/output-tokens
   :seon.agent.turn.usage/cache-read-input-tokens
   :seon.agent.turn.usage/cache-creation-input-tokens])

(defn captured?
  "True when a turn carries at least one named usage attribute."
  {:malli/schema [:=> [:catn [::turn ::turn]] :boolean]}
  [turn]
  (boolean (some #(contains? turn %) turn-attributes)))

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
  (let [prompt (:seon.agent.turn.usage/prompt-tokens m)
        output (:seon.agent.turn.usage/completion-tokens m)
        cached? (contains? m :seon.agent.turn.usage/cached-tokens)
        cached (:seon.agent.turn.usage/cached-tokens m)
        invalid (cond-> []
                  (not (token-count? prompt))
                  (conj :seon.agent.turn.usage/prompt-tokens)
                  (not (token-count? output))
                  (conj :seon.agent.turn.usage/completion-tokens)
                  (and cached? (not (token-count? cached)))
                  (conj :seon.agent.turn.usage/cached-tokens))]
    (cond
      (seq invalid)
      (invalid-counts "OpenAI-compatible" invalid)

      :else
      {::usage
       (cond-> {::total prompt
                ::output output
                ::provider-shape :openai-compat}
         cached? (assoc ::cached cached))})))

(defn- anthropic-analysis
  [m]
  (let [input (:seon.agent.turn.usage/input-tokens m)
        output (:seon.agent.turn.usage/output-tokens m)
        read? (contains? m :seon.agent.turn.usage/cache-read-input-tokens)
        create? (contains? m :seon.agent.turn.usage/cache-creation-input-tokens)
        read* (:seon.agent.turn.usage/cache-read-input-tokens m)
        create (:seon.agent.turn.usage/cache-creation-input-tokens m)
        invalid (cond-> []
                  (not (token-count? input))
                  (conj :seon.agent.turn.usage/input-tokens)
                  (not (token-count? output))
                  (conj :seon.agent.turn.usage/output-tokens)
                  (and read? (not (token-count? read*)))
                  (conj :seon.agent.turn.usage/cache-read-input-tokens)
                  (and create? (not (token-count? create)))
                  (conj :seon.agent.turn.usage/cache-creation-input-tokens))]
    (if (seq invalid)
      (invalid-counts "Anthropic" invalid)
      {::usage
       (cond-> {::total (+ input (if read? read* 0) (if create? create 0))
                ::output output
                ::provider-shape :anthropic}
         read? (assoc ::cached read*))})))

(defn analyze
  "Normalize one turn's named usage attributes or explain rejection."
  {:malli/schema [:=> [:catn [::turn ::turn]] ::analysis]}
  [turn]
  (cond
    (contains? turn :seon.agent.turn.usage/prompt-tokens)
    (openai-analysis turn)
    (contains? turn :seon.agent.turn.usage/input-tokens)
    (anthropic-analysis turn)
    :else {::diagnostic
           "Unknown usage shape: expected prompt-tokens or input-tokens."}))

(defn extract
  "Normalize one turn's named usage attributes, or return nil."
  {:malli/schema [:=> [:catn [::turn ::turn]] [:or :nil ::usage]]}
  [turn]
  (::usage (analyze turn)))

(defn turn-projection
  "Derive normalized usage, diagnostic, and compact line from one turn."
  {:malli/schema [:=> [:catn [::turn ::turn]] ::turn-projection]}
  [{estimated? :seon.agent.turn/usage-estimated? :as turn}]
  (if-not (captured? turn)
    {}
    (let [{normalized ::usage :as analysis} (analyze turn)]
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
