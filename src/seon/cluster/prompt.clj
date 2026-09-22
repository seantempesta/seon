(ns seon.cluster.prompt
  "The prompt derives retained history on the calling turn's thread.

  It returns the exact text, ordered contribution measurements, and database
  value supplied to the provider boundary.

  THE HISTORY IS COMPOSED FROM WHOLE UNITS, NEVER CUT. Each stored evaluation
  is rendered once, by the evaluation schema's own AI pair, and its shown text
  was bounded once already — by the value renderer at evaluation time, the one
  clipping spot. [[select]] then chooses the NEWEST units the token budget can
  hold and names what it dropped in one elision value; [[compose]] joins them.
  Nothing here re-fits a string another renderer has already produced."
  (:require [clojure.string :as str]
            [seon.ai :as ai]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.print :as print]
            [seon.render :as render]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; One walk
;;; ---------------------------------------------------------------------------

(def ^:private default-depth 2)

(defn- config-cluster-name
  [database]
  (db/q '[:find ?cluster-name .
          :where
          [_ :seon.config/cluster ?cluster-name]]
        database))

(defn- effective-ai-settings
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :seon.config/effective :seon.config/error :seon.db/invalid-read-error
                   :seon.schema/validation-refusal
                   :seon.cluster.prompt/missing-cluster-error
                   :seon.cluster.prompt/missing-config-error]]}
  [database agent-id]
  (let [cluster-name (config-cluster-name database)]
    (cond
      (or (:seon.db/invalid-read cluster-name) (:seon.schema/expected-value cluster-name))
      cluster-name

      (nil? cluster-name)
      {:seon.cluster.prompt/missing-cluster agent-id
       :seon.error/at (java.util.Date.) :seon.error/layer :seon.cluster.prompt/prompt
       :seon.error/operation 'seon.cluster.prompt/effective-ai-settings
       :seon.error/message
       "The prompt's database has no effective cluster configuration."
       :seon.error/data {:seon.agent/id agent-id}}

      :else
      (let [effective (config/effective database cluster-name)]
        (cond
          (:seon.config/missing-effective effective)
          (assoc effective ::missing-config agent-id)
          (or (:seon.config/error-key effective) (:seon.db/invalid-read effective))
          effective
          :else
          (let [overlay (ai/agent-overlay database agent-id)]
            (if (or (:seon.db/invalid-read overlay) (:seon.schema/expected-value overlay))
              overlay
              (ai/settings effective overlay))))))))

(defn- calibration-for
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ai/model
                       :seon.ai.tokens/calibration [:or :nil :seon.agent/id]]
                  :seon.ai.tokens/calibration]}
  [database model fallback-calibration agent-id]
  (let [query (cond->
               '{:find [?attempt ?at ?characters ?provider-tokens]
                 :in [$ ?model]
                 :where [[?attempt :seon.ai/model ?model]
                         [?attempt :seon.ai.attempt/at ?at]
                         [?attempt :seon.ai.usage/prompt-tokens ?provider-tokens]
                         [?run :seon.turn/attempts ?attempt]
                         [?capture :seon.context.capture/run ?run]
                         [?capture :seon.ai.tokens/characters ?characters]]}
                agent-id (update :in conj '?agent-id)
                agent-id (update :where into
                                 '[[?run :seon.turn/agent ?agent]
                                   [?agent :seon.agent/id ?agent-id]]))
        rows (if agent-id (db/q query database model agent-id)
                 (db/q query database model))]
    (tokens/recent-calibration
     (keep (fn [[_attempt _at characters provider-tokens]]
             (when (and (int? provider-tokens) (pos? provider-tokens))
                 {:seon.ai.tokens/characters characters
                  :seon.ai.usage/prompt-tokens provider-tokens}))
           (sort-by (juxt second first)
                    (if (or (:seon.db/invalid-read rows) (:seon.schema/expected-value rows)) [] rows)))
     10 fallback-calibration)))

(defn model-calibration
  "Fit this model's characters-per-token ratio to its own recorded usage.

  THE MEASUREMENT THE BUDGET TRUSTS, derived rather than assumed. Every
  settled attempt records what the provider counted
  (`:seon.ai.usage/prompt-tokens`) and the capture for
  the same run records the exact characters that produced it
  (`:seon.ai.tokens/characters`), so the ratio is a join over facts we
  already commit — no new writing, no tokenizer, and per model because
  tokenizers differ.

  The four-argument arity scopes the latest ten billed attempts to the agent.
  The shorter arities retain model-wide inspection for existing callers.
  A model with no recorded usage yet — a fresh cluster's first turns —
  uses the supplied measured prior without inventing an error band.
  Recorded drift stays queryable: this function IS the query."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.ai/model]
     :seon.ai.tokens/calibration]
    [:=> [:cat :seon.db/database-value :seon.ai/model
          :seon.ai.tokens/calibration]
     :seon.ai.tokens/calibration]
    [:=> [:cat :seon.db/database-value :seon.ai/model
          :seon.ai.tokens/calibration :seon.agent/id]
     :seon.ai.tokens/calibration]]}
  ([database model]
   (model-calibration database model tokens/shipped-calibration))
  ([database model fallback-calibration]
   (calibration-for database model fallback-calibration nil))
  ([database model fallback-calibration agent-id]
   (calibration-for database model fallback-calibration agent-id)))

(defn agent-calibration
  "Fit an agent's recent model attempts, falling back to its config prior."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.ai/model]
                  [:or :seon.ai.tokens/calibration :seon.error/value]]}
  [database agent-id model]
  (let [settings (effective-ai-settings database agent-id)]
    (if (or (:seon.cluster.prompt/missing-cluster settings)
            (:seon.cluster.prompt/missing-config settings)
            (:seon.config/error-key settings) (:seon.db/invalid-read settings)
            (:seon.schema/expected-value settings))
      settings
      (model-calibration database model
                         (tokens/prior-calibration (:seon.config.ai/chars-per-token-prior settings))
                         agent-id))))

(defn- refuse!
  {:malli/schema [:=> [:cat :keyword :string] :nil]}
  [rule message]
  (throw (ex-info message
                  {:seon.error/message message
                   ::rule rule
                   :seon.cluster.prompt/refused rule})))

(defn- missing-required-keys
  [explanation]
  (->> (:errors explanation)
       (keep (fn [problem]
               (when (= :malli.core/missing-key (:type problem))
                 (last (:in problem)))))
       distinct
       vec))

(defn- validate-request!
  [projection request]
  (when-let [explanation
             (schema/explain-candidate-value
              projection :seon.cluster.prompt/request request)]
    (let [missing (missing-required-keys explanation)]
      (refuse!
       ::missing-input
       (if (seq missing)
         (str "Prompt request is missing required input " (pr-str missing) ".")
         "Prompt request violates :seon.cluster.prompt/request.")))))

(defn- history-contributions
  "Price the exact retained entry segments whose concatenation is the prompt.

   Token estimates are differences between cumulative character estimates.
   That gives every separator to exactly one entry and guarantees the ordered
   decomposition sums to the same calibrated whole-prompt estimate."
  [segments calibration]
  (loop [remaining segments
         position 0
         characters 0
         estimated 0
         contributions []]
    (if-let [segment (first remaining)]
      (let [next-characters (+ characters (count segment))
            next-estimated
            (tokens/estimate-of-characters next-characters calibration)]
        (recur
         (next remaining)
         (inc position)
         next-characters
         next-estimated
         (conj contributions
               {:seon.render.block/name :walk
                :seon.context.contribution/position position
                :seon.context.contribution/text segment
                :seon.context.contribution/hash
                (context/contribution-hash segment)
                :seon.context.contribution/tokens
                (- next-estimated estimated)})))
      contributions)))

;;; ---------------------------------------------------------------------------
;;; Composable history — whole units under the prompt's own budget
;;; ---------------------------------------------------------------------------

(def ^:private unit-separator
  "The one separator between composed history units."
  "\n\n")

(defn- priced-unit
  [unit calibration]
  (assoc unit :seon.ai.tokens/estimate
         (tokens/estimate (or (:seon.render.history/bytes unit) "") calibration)))

(defn- retained-count
  "How many of the newest units fit, counting the separators they cost.

  A CUT NEVER OMITS ITS WHOLE SUBJECT (`seon.print/fit-text` states the same
  law for characters): the newest unit is retained even when it alone exceeds
  the budget, because a history composed of nothing tells the agent only that
  it has one."
  [units budget calibration]
  (loop [index (dec (count units)) characters 0 kept 0]
    (if (neg? index)
      kept
      (let [next-characters
            (+ characters
               (count (or (:seon.render.history/bytes (nth units index)) ""))
               (if (pos? kept) (count unit-separator) 0))]
        (if (and (pos? kept)
                 (> (tokens/estimate-of-characters next-characters calibration)
                    budget))
          kept
          (recur (dec index) next-characters (inc kept)))))))

(defn- dropped-elision
  "One elision value for the evaluations the budget could not hold.

  `:seon.render.data/next-offset` IS the oldest surviving position: the
  omitted units are the oldest ones, so the retained history resumes at that
  index of the requeried evaluation sequence, and the requery form retrieves
  the whole sequence to read from."
  [agent-id dropped total profile]
  (print/elision
   {:seon.print/omitted dropped
    :seon.render.profile/id (:seon.render.profile/id profile)
    :seon.print/elision-unit :evaluations
    :seon.print/bound-by :seon.config.ai/prompt-token-budget
    :seon.render.data/path []
    :seon.render.data/next-offset dropped
    :seon.render.data/total total
    :seon.print/requery-id
    (list 'seon.eval/of-agent (list 'seon.db/db) agent-id)}))

(defn- selection-segments
  "The selection's ordered segments, whose concatenation IS the history.

  The separator belongs to the composition, so it is given to exactly one
  segment and the ordered decomposition the contributions price sums to the
  composed whole. The elision, when there is one, is named first."
  [selection]
  (let [parts (into (if-let [elision (:seon.print/elision selection)]
                      [(print/render-elision-ai elision)]
                      [])
                    (map #(or (:seon.render.history/bytes %) ""))
                    (:seon.render.history/units selection))]
    (into []
          (map-indexed (fn [position part]
                         (str (when (pos? position) unit-separator) part)))
          parts)))

(defn compose
  "Join the selected units, newest last, with the elision named first."
  {:malli/schema [:=> [:cat :seon.render.history/selection] :string]}
  [selection]
  (str/join (selection-segments selection)))

(defn- selection-of
  [priced total dropped agent-id profile]
  (cond-> {:seon.render.history/units (subvec priced dropped)}
    (pos? dropped)
    (assoc :seon.print/elision (dropped-elision agent-id dropped total profile))))

(defn select
  "Choose the newest whole history units `budget` admits, oldest dropped first.

  PURE: the units, budget, calibration, agent identity and render profile are all
  arguments (2.1); nothing is fetched at call time. Each returned unit carries
  `:seon.ai.tokens/estimate`, derived here from its own stored shown text —
  never stored, because the calibration this prices against drifts as attempts
  accumulate.

  This is SELECTION, not elision: every retained unit is whole, and its result
  was already bounded once by the value renderer at evaluation time. When
  anything is dropped the selection carries ONE elision value naming the
  dropped count, the offset the retained history resumes at, and the form that
  retrieves the omitted evaluations.

  THE BUDGET BOUNDS WHAT IS COMPOSED, INCLUDING THE ELISION. Naming the
  omission costs bytes too, so the first estimate is re-judged against the
  composed result and one more unit is released until the composition fits or
  only the newest unit is left."
  {:malli/schema [:=> [:cat [:vector :seon.render.history/unit]
                       :seon.config.ai/prompt-token-budget
                       :seon.ai.tokens/calibration :seon.agent/id
                       :seon.render.profile/profile]
                  :seon.render.history/selection]}
  [units budget calibration agent-id profile]
  (let [priced (mapv #(priced-unit % calibration) units)
        total (count priced)]
    (loop [kept (retained-count priced budget calibration)]
      (let [selection (selection-of priced total (- total kept) agent-id profile)]
        (if (or (<= kept 1)
                (<= (tokens/estimate (compose selection) calibration) budget))
          selection
          (recur (dec kept)))))))

(defn- capture-mismatch
  "A replay whose recomposed prompt is not the bytes the provider was sent.

  DERIVED AT THE AUTHORITY (owner law, 2026-08-29). A capture holds
  `compose(select(units))` plus the turn frame — history under THIS
  agent's token budget — so only the function that composes it can say
  whether saved evaluations still reconstruct it. The check used to live
  at `seon.render/acquire-context!`, which holds the acquired units and
  not the composition, and compared the selected capture against the
  whole unselected join: identical at a budget large enough to keep every
  unit, and a false `capture-mismatch` at every smaller one. A live turn
  has no capture yet, so this answers only for a replay."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.turn/id :string]
                  [:or :nil :seon.schema/validation-refusal]]}
  [database turn-id text]
  (let [capture (db/q '[:find ?text . :in $ ?id
                        :where [?t :seon.turn/id ?id]
                               [?c :seon.context.capture/run ?t]
                               [?c :seon.context.capture/prompt ?text]]
                      database turn-id)]
    (when (and (string? capture) (not= capture text))
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.cluster.prompt/capture
       :seon.error/operation 'seon.cluster.prompt/capture-mismatch
       :seon.schema/expected-value capture
       :seon.schema/refused-value text
       :seon.error/message
       "Saved evaluations do not reconstruct the captured provider prompt."
       :seon.turn/id turn-id})))

(defn- acquire-context-report
  "`settings` is the ONE resolution `prompt` already made (2.1): the turn
  frame reads the same resolved dial through it rather than deriving the
  agent's overlay a second time for the same number."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster.prompt/request
                       :seon.config.ai/prompt-token-budget :seon.ai.tokens/calibration
                       :seon.config/effective]
                  [:or :seon.cluster.prompt/rendered-context :seon.cluster.prompt/error]]}
  [database request budget calibration settings]
  (let [profile (render/request-profile (assoc request :seon.db/db database))
        distance (long (get request :seon.render/distance default-depth))
        acquired (if (:seon.render/refused-member profile) profile
                   (render/acquire-context!
                  (assoc request
                         :seon.render/profile profile
                         :seon.db/db database
                         :seon.render/distance distance)))]
    (cond
      (or (:seon.render/refused-member acquired)
          (:seon.render.web/refused-member acquired))
      (assoc acquired :seon.cluster.prompt/error-agent-id (:seon.agent/id request)
                       :seon.cluster.prompt/derivation-observation
                       {:seon.error.evidence/attribute :seon.error/message
                        :seon.error.evidence/value (:seon.error/message acquired)})

      ;; ABSENCE IS NOT AN EMPTY HISTORY. Acquisition publishes one unit per
      ;; stored evaluation; a text with no units behind it would compose to
      ;; nothing and read as a fresh agent, so it is refused by name.
      (and (nil? (:seon.render.history/entries acquired))
           (seq (:seon.cluster.prompt/text acquired)))
      (refuse! ::missing-history-units
               (str "Acquired context for agent "
                    (pr-str (:seon.agent/id request))
                    " carries prompt text with no history units to compose."))

      :else
      (let [selection (select (vec (:seon.render.history/entries acquired))
                              budget calibration (:seon.agent/id request) profile)
            history (compose selection)
            frame (str (when (seq history) "\n\n")
                       (repl/frame (:seon.db/db acquired) (:seon.agent/id request)
                                   settings))
            text (str history frame)
            segments (conj (selection-segments selection) frame)
            contributions (history-contributions segments calibration)
            report (tokens/budget-report text budget calibration)]
        (or (when-let [turn-id (:seon.turn/id request)]
              (when-let [failure (capture-mismatch database turn-id text)]
                (assoc failure :seon.cluster.prompt/error-agent-id (:seon.agent/id request)
                       :seon.cluster.prompt/derivation-observation
                       {:seon.error.evidence/attribute :seon.error/message
                        :seon.error.evidence/value (:seon.error/message failure)})))
            {:seon.cluster.prompt/text text
             :seon.context/contributions
             (update contributions (dec (count contributions))
                     assoc :seon.render.block/name :frame)
             :seon.ai.tokens/budget-report report
             :seon.db/db (:seon.db/db acquired)})))))

(defn prompt
  "Acquire one retained walk for the agent holding the request's run.

  A turn may wake on changed facts without a message trigger. Acquisition
  selects history at its opening basis and appends the derived turn-budget
  frame. Contributions measure the exact resulting prompt.

  The returned value carries its `:seon.ai.tokens/budget-report`, so the
  size that was checked, the basis that produced it, and the margin it
  carried are all readable rather than implied."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.prompt/request]
                  [:or :seon.cluster.prompt/rendered-context :seon.cluster.prompt/error]]}
  [database request]
  (validate-request! (db/carried-projection database) request)
  (let [agent-id (:seon.agent/id request)
        settings (effective-ai-settings database agent-id)]
    (if (or (:seon.cluster.prompt/missing-cluster settings)
            (:seon.cluster.prompt/missing-config settings)
            (:seon.config/error-key settings) (:seon.db/invalid-read settings)
            (:seon.schema/expected-value settings))
      (assoc settings :seon.cluster.prompt/error-agent-id agent-id
                       :seon.cluster.prompt/derivation-observation
                       {:seon.error.evidence/attribute :seon.error/message
                        :seon.error.evidence/value (:seon.error/message settings)})
      (acquire-context-report
       database request
       (:seon.config.ai/prompt-token-budget settings)
       (model-calibration
        database
        (:seon.config.ai/model settings)
        (tokens/prior-calibration
         (:seon.config.ai/chars-per-token-prior settings))
        agent-id)
       settings))))
