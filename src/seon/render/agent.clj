(ns seon.render.agent
  "The schema-declared AI and HTML renderers for one agent entity.

  Both functions consume the same flattened render-call unit. The transcript
  is an ordinary derived projection owned here; traversal, renderer selection,
  stable fragment identity, and package fan-out remain in their respective
  owners.

  The surviving agent renderers describe identity, current run state, and the
  bounded transcript. Other reached facts render through their own owning
  namespaces or schema defaults.

  Crash walk: pure renders over a database value. A kill loses a prompt
  that re-derives."
  (:require [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.render.transcript :as transcript]))

;;; ---------------------------------------------------------------------------
;;; The renders
;;; ---------------------------------------------------------------------------

(defn- transcript-unit
  [unit]
  (assoc unit :seon.render.transcript/token-budget
         (quot (long (get-in unit [:seon.sci.admit/caps
                                   :seon.config.eval.result/max-string]))
               tokens/chars-per-token)))

(defn agent-ai
  "`:seon.render/ai` — one agent, as its neighbours see it.

  The agent family's schema default, declared on `:seon.cluster.agent/agent`
  in `resources/seon/schema.edn`. Deliberately ONE sentence: an agent
  reached as a neighbour wants a name and whether it is busy, and
  everything else about it is its own neighbourhood's business, one hop
  further out.

  Presence is the state — an agent with no `/run` is idle, and there is
  no status attribute to read."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [id (get unit :seon.cluster.agent/id)]
    (let [status (str "Agent " id
                      (if (get unit :seon.cluster.agent/run)
                        " is running now."
                        " is idle."))
          history (when (and (:seon.db/db unit)
                             (:seon.sci.admit/caps unit))
                    (transcript/render-ai (transcript-unit unit)))]
      (str status (when (seq history) (str "\n" history))))))

(defn agent-html
  "`:seon.render/html` — one agent, with the same facts as its AI twin."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [text (agent-ai unit)]
    [:article {:class "seon-family-entry seon-agent-entry"}
     [:p (first (str/split-lines text))]
     (when (and (:seon.db/db unit) (:seon.sci.admit/caps unit))
       (transcript/render-html (transcript-unit unit)))]))
