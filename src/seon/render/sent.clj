(ns seon.render.sent
  "An agent's model calls and, for one call, the context `seon.context/model-context` assembles at the call's
  recorded basis (`:seon.ai.attempt/basis-t`): what the model was sent, re-derived from the database the call read,
  since no prompt is stored. The HTML face shows its sections with their sizes in estimated tokens, each exchange's
  forms paired with what the REPL answered, and every cut: an older reply sent by reference
  (`:seon.config.context/reference-tokens`) and the value printer's cuts inside a reply."
  (:require [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.render.value :as value]))

(def ^:private call-selector
  "The attempt facts a call row shows."
  [:seon.ai.attempt/id :seon.ai.attempt/ordinal :seon.ai.attempt/basis-t :seon.ai.attempt/at :seon.ai/model
   :seon.ai.attempt/finish-reason :seon.ai.usage/prompt-tokens :seon.ai.usage/cached-tokens
   :seon.ai.usage/completion-tokens])

(defn calls
  "Agent `agent-id`'s model calls on `database`, its branch, oldest first: each provider attempt of its runs with the
  run it answered."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :seon.render.sent/calls :seon.db/invalid-read-error]]}
  [database agent-id]
  (let [rows (db/q [:find '?run (list 'pull '?attempt call-selector)
                    :in '$ '?id
                    :where ['?agent :seon.agent/id '?id] ['?r :seon.turn/agent '?agent] ['?r :seon.turn/id '?run]
                    ['?r :seon.turn/attempts '?attempt]]
                   database agent-id)]
    (if (:seon.error/at rows)
      rows
      (->> rows
           (map (fn [[run attempt]] (assoc (dissoc attempt :db/id) :seon.turn/id run)))
           (sort-by (juxt :seon.ai.attempt/basis-t :seon.ai.attempt/ordinal))
           vec))))

(defn- call-database
  "`database`'s value at `basis`, a call's recorded basis: the commit of its lineage that transaction made, so the
  value carries a commit as the turn's own read did; else `database` as of `basis`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ai.attempt/basis-t] :seon.db/database-value]}
  [database basis]
  (or (some->> (db/commit-at-t database basis) (db/commit-database database))
      (db/as-of database basis)))

(defn view
  "Agent `agent-id`'s context as `seon.context/model-context` assembles it over `database`, its branch, with
  `request`'s render inputs, beside its exchanges as :seon.context/exchange values (`seon.context/exchange-values`),
  whose AI face is what the context sends: at the head, what its next call is sent; for `call`, what that call was
  sent, at its recorded basis (`call-database`)."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :seon.sci.eval/shown-request :seon.agent/id] :seon.render.sent/view]
                  [:=> [:cat :seon.db/database-value :seon.sci.eval/shown-request :seon.agent/id :seon.render.sent/call]
                   :seon.render.sent/view]]}
  ([database request agent-id]
   (let [dials (config/effective database (db/q '[:find ?n . :where [_ :seon.config/cluster ?n]] database))
         exchanges (when-not (:seon.error/at dials) (context/exchange-values database agent-id dials))]
     (cond-> {:seon.render.sent/basis (db/basis-t database)
              :seon.render.sent/context (context/model-context database request agent-id)}
       (vector? exchanges) (assoc :seon.render.sent/exchanges exchanges))))
  ([database request agent-id call]
   (assoc (view (call-database database (:seon.ai.attempt/basis-t call)) request agent-id)
          :seon.render.sent/call call)))

(defn- text-section
  "One whole section of the context: its `title`, its size and its highlighted `text`."
  {:malli/schema [:=> [:cat :string :string :string] :seon.render/hiccup]}
  [kind title text]
  [:section {:class (str "seon-sent-section seon-sent-" kind)}
   [:header [:h3 title] (context/token-badge text)]
   [:pre {:class "seon-sent-text"} (context/code-html text)]])

(defn- exchange-html
  "One model reply's exchange: each evaluation through the exchange's HTML face (`seon.context/exchange-html`)."
  {:malli/schema [:=> [:cat [:vector :seon.context/exchange] :int] :seon.render/hiccup]}
  [exchanges index]
  [:li {:class "seon-sent-exchange" :data-exchange (str index)}
   [:header [:h4 (str "Reply " (inc index))]
    [:span {:class "seon-sent-count"} (str (count exchanges) (if (= 1 (count exchanges)) " form" " forms"))]]
   (into [:div {:class "seon-sent-pairs"}] (map context/exchange-html) exchanges)])

(defn- call-header
  "A call's facts: its run, basis, model, finish and the provider's token counts; for the next call, its basis."
  {:malli/schema [:=> [:cat :seon.render.sent/view] :seon.render/hiccup]}
  [{call :seon.render.sent/call basis :seon.render.sent/basis}]
  (into [:dl {:class "seon-sent-call"}]
        (mapcat (fn [[label v]] (when (some? v) [[:dt label] [:dd (str v)]])))
        (if-not call
          [["next call at basis t" basis]]
          (for [[label k] [["run" :seon.turn/id] ["basis t" :seon.ai.attempt/basis-t] ["model" :seon.ai/model]
         ["finish" :seon.ai.attempt/finish-reason] ["prompt tokens" :seon.ai.usage/prompt-tokens]
                            ["cached" :seon.ai.usage/cached-tokens] ["completion tokens" :seon.ai.usage/completion-tokens]]]
            [label (get call k)]))))

(defn view-html
  "`:seon.render/html` of :seon.render.sent/view: the call's facts, then what it sent in order, the system message
  ((help) and the attempt sections), the exchanges (each form paired with its reply, every cut named) and the turn
  sections, each sized in estimated tokens; the assembler's refusal through the value printer when it refused."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [{shown :seon.render/value database :seon.db/db}]
  (let [{call :seon.render.sent/call sent :seon.render.sent/context exchanges :seon.render.sent/exchanges} shown]
    [:article (cond-> {:class "seon-sent" :data-basis (str (:seon.render.sent/basis shown))}
                call (assoc :data-call (:seon.ai.attempt/id call)))
     (call-header shown)
     (if (:seon.error/at sent)
       [:section {:class "seon-sent-section seon-sent-refused"}
        [:header [:h3 "The context could not be derived at this basis"]]
        (value/html {:seon.render/value sent :seon.db/db database
                     :seon.render.value/root [::refused (:seon.render.sent/basis shown)]})]
       (list
        (text-section "system" "System: (help) and the attempt sections" (:seon.ai/system sent))
        [:section {:class "seon-sent-section seon-sent-exchanges"}
         [:header [:h3 "Exchanges"]
          [:span {:class "seon-sent-count"} (str (count exchanges) " replies, " (reduce + (map count exchanges)) " forms")]]
         (into [:ol {:class "seon-sent-exchange-list"}]
               (map-indexed (fn [index units] (exchange-html units index)))
               exchanges)]
        (text-section "prompt" "Turn sections" (:seon.ai/prompt sent))))]))
