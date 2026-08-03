(ns seon.render.agent
  "The schema-declared AI and HTML renderers for one agent entity.

  Both functions consume the same flattened render-call unit and describe
  identity plus current run state. The schema-declared session producers in
  `seon.render.transcript` compose this unchanged status with session history
  until bootstrap forms replace the status section in slice 2.

  Crash walk: pure renders over a database value. A kill loses a prompt
  that re-derives."
  (:require [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; The renders
;;; ---------------------------------------------------------------------------

(defn agent-ai
  "The unchanged status section for one agent session.

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
    (str "Agent " id
         (if (get unit :seon.cluster.agent/run)
           " is running now."
           " is idle."))))

(defn agent-html
  "The unchanged HTML status section for one agent session."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [text (agent-ai unit)]
    [:article {:class "seon-family-entry seon-agent-entry"}
     [:p (first (str/split-lines text))]]))
