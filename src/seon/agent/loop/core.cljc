(ns seon.agent.loop.core
  "Pure claimant eligibility and next-step planning.")

(def pod-phases
  #{:unstarted :rendered :attempt-open})

(def host-phases
  #{:reply-ready :evaling :evaled})

(def no-progress-streak-limit 3)

(def hop-cap
  "Maximum live agent-message hop depth, shared by portable claim scans."
  4)

(def ^:private observation-keys
  [:seon.eval/source
   :seon.eval/status
   :seon.eval/ok?
   :seon.eval/result-edn
   :seon.eval/output
   :seon.eval/error
   :seon.eval/error-data
   :seon.eval/ns])

(defn- turn-observation [turn]
  (when (contains? turn :seon.agent.turn/evals)
    (mapv #(select-keys % observation-keys)
          (:seon.agent.turn/evals turn))))

(defn no-progress-streak
  "Derive the repeated no-progress streak from committed trailing turns."
  [run]
  (first
   (reduce
    (fn [[streak prior] turn]
      (let [evals (:seon.agent.turn/evals turn)
            observation (turn-observation turn)]
        (cond
          (some :seon.eval/progress? evals) [0 nil]
          observation
          [(if (= observation prior) (inc streak) 1) observation]
          :else [(inc streak) nil])))
    [0 nil]
    (->> (:seon.agent.turn/_run run)
         (filter #(and (= :done (:seon.agent.turn/status %))
                       (not (:seon.agent.turn/scheduled? %))))
         (sort-by :db/id)))))

(defn current-phase
  "Cursor phase, or `:unstarted` when the run has no current turn."
  [run]
  (if (and (:seon.agent.interaction/id run)
           (contains? #{:pending :running}
                      (:seon.agent.interaction/status run)))
    :interaction
    (or (get-in run [:seon.agent.run/current-turn :seon.agent.turn/phase])
        :unstarted)))

(defn eligible?
  "Whether declared claimant capabilities can execute the next phase."
  [capabilities run]
  (let [phase (current-phase run)]
    (or (and (contains? capabilities
                        :seon.agent.driver.capability/interaction)
             (= :interaction phase))
        (and (contains? capabilities :seon.agent.driver.capability/render)
             (= :unstarted phase))
        (and (contains? capabilities :seon.agent.driver.capability/llm)
             (contains? #{:rendered :attempt-open} phase))
        (and (contains? capabilities :seon.agent.driver.capability/eval)
             (contains? #{:reply-ready :evaling} phase))
        (and (contains? capabilities :seon.agent.driver.capability/publish)
             (= :evaled phase)))))

(defn next-step
  "Return the one step authorized by the persisted cursor."
  [run]
  (case (current-phase run)
    :interaction :interaction
    :unstarted :render
    :rendered :open-attempt
    :attempt-open :settle-attempt
    :reply-ready :eval
    :evaling :settle-eval
    :evaled :publish
    :published :close-turn
    nil))
