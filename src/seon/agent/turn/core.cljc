(ns seon.agent.turn.core
  "Pure turn cursor, durable attempt, parsing, and retry policy."
  (:require [seon.repl.parse :as repl.parse]
            [seon.retry :as retry]))

(def llm-retry-base-ms 500)
(def llm-retry-factor 2)
(def llm-retry-jitter 0.5)
(def llm-retry-max-delay-ms 20000)
(def llm-retry-total-cap-ms 60000)
(def llm-retry-default-count 4)

(def phases
  [:rendered :attempt-open :reply-ready :evaling :evaled :published])

(def phase-successor
  (zipmap phases (concat (rest phases) [nil])))

(defn phase-fence
  "CAS one turn cursor at the observed phase."
  [turn-id phase]
  [:db.fn/cas [:seon.agent.turn/id turn-id]
   :seon.agent.turn/phase phase phase])

(defn advance-phase-tx-data
  "Advance one cursor under the driver-held run fence with phase facts."
  [run-fence turn-id from-phase to-phase facts]
  (into
   (vec run-fence)
   (concat
    [[:db.fn/cas [:seon.agent.turn/id turn-id]
      :seon.agent.turn/phase from-phase to-phase]]
    facts)))

(defn open-attempt-row
  "Build the durable pre-dispatch attempt row."
  [{:seon.ai.attempt/keys
    [ordinal config-digest deadline-at fallback-variant provider adapter
     requested-model temperature max-tokens completion-limit-field thinking
     endpoint adapter-timeout-ms outer-timeout-ms stream? extra-body-digest
     dg-backend api-key-env credential-class]}]
  (cond->
   {:seon.ai.attempt/ordinal ordinal
    :seon.ai.attempt/config-digest config-digest
    :seon.ai.attempt/deadline-at deadline-at
    :seon.ai.attempt/provider provider
    :seon.ai.attempt/adapter adapter
    :seon.ai.attempt/outer-timeout-ms outer-timeout-ms
    :seon.ai.attempt/stream? (boolean stream?)
    :seon.ai.attempt/outcome :open}
    fallback-variant
    (assoc :seon.ai.attempt/fallback-variant fallback-variant)
    requested-model
    (assoc :seon.ai.attempt/requested-model requested-model)
    (some? temperature)
    (assoc :seon.ai.attempt/temperature temperature)
    max-tokens
    (assoc :seon.ai.attempt/max-tokens max-tokens)
    completion-limit-field
    (assoc :seon.ai.attempt/completion-limit-field completion-limit-field)
    (some? thinking)
    (assoc :seon.ai.attempt/thinking thinking)
    endpoint
    (assoc :seon.ai.attempt/endpoint endpoint)
    adapter-timeout-ms
    (assoc :seon.ai.attempt/adapter-timeout-ms adapter-timeout-ms)
    extra-body-digest
    (assoc :seon.ai.attempt/extra-body-digest extra-body-digest)
    dg-backend
    (assoc :seon.ai.attempt/dg-backend dg-backend)
    api-key-env
    (assoc :seon.ai.attempt/api-key-env api-key-env)
    credential-class
    (assoc :seon.ai.attempt/credential-class credential-class)))

(defn open-attempt-tx-data
  "Attach one `:open` attempt and advance `:rendered → :attempt-open`."
  [run-fence turn-id attempt-id attempt]
  (advance-phase-tx-data
   run-fence turn-id :rendered :attempt-open
   [(assoc (open-attempt-row attempt)
           :seon.ai.attempt/id attempt-id)
    [:db/add [:seon.agent.turn/id turn-id]
     :seon.agent.turn/llm-attempts
     [:seon.ai.attempt/id attempt-id]]]))

(defn next-attempt-tx-data
  "Attach a new open attempt while retaining the `:attempt-open` cursor."
  [run-fence turn-id attempt-id attempt]
  (into
   (vec run-fence)
   [(phase-fence turn-id :attempt-open)
    (assoc (open-attempt-row attempt)
           :seon.ai.attempt/id attempt-id)
    [:db/add [:seon.agent.turn/id turn-id]
     :seon.agent.turn/llm-attempts
     [:seon.ai.attempt/id attempt-id]]]))

(defn terminal-attempt-tx-data
  "Terminalize an open attempt and atomically link reply evidence.

   `terminal` carries only bounded terminal evidence. The reply link is absent
   for provider/error terminals that produced no response body."
  [run-fence turn-id attempt-id terminal reply-blob]
  (into
   (vec run-fence)
   (concat
    [(phase-fence turn-id :attempt-open)
     [:db.fn/cas [:seon.ai.attempt/id attempt-id] :seon.ai.attempt/outcome
      :open (:seon.ai.attempt/outcome terminal)]
     (merge {:seon.ai.attempt/id attempt-id}
            (dissoc terminal :seon.ai.attempt/outcome))]
    (when reply-blob
      [[:db/add [:seon.agent.turn/id turn-id]
        :seon.agent.turn/reply-blob reply-blob]
       [:db.fn/cas [:seon.agent.turn/id turn-id]
        :seon.agent.turn/phase :attempt-open :reply-ready]]))))

(defn crash-open-attempt-tx-data
  "Mark an abandoned external LLM attempt honestly crashed."
  [run-fence turn-id attempt-id]
  (into
   (vec run-fence)
   [(phase-fence turn-id :attempt-open)
    [:db.fn/cas [:seon.ai.attempt/id attempt-id]
     :seon.ai.attempt/outcome :open :crashed]]))

(defn error-close-tx-data
  "Close an exhausted LLM turn and its claimed run atomically."
  [run-fence agent-id run-id turn-id closed-at error-message]
  (into
   (vec run-fence)
   [(phase-fence turn-id :attempt-open)
    {:seon.agent.turn/id turn-id
     :seon.agent.turn/phase :published
     :seon.agent.turn/status :error
     :seon.agent.turn/error error-message}
    {:seon.agent.run/id run-id
     :seon.agent.run/status :closed
     :seon.agent.run/closed-reason :error
     :seon.agent.run/closed-at closed-at}
    [:db/retract [:seon.agent.run/id run-id]
     :seon.agent.run/claimant]
    [:db/retract [:seon.agent/id agent-id] :seon.agent/run]]))

(defn next-attempt-ordinal
  "Derive the next ordinal exclusively from durable attempt rows."
  [attempts]
  (if (seq attempts)
    (inc (apply max (map :seon.ai.attempt/ordinal attempts)))
    0))

(defn reply-program
  "Parse one provider reply for the frozen REPL mode."
  [raw-reply stream? starting-ns]
  (if-not stream?
    (repl.parse/parse-program
     raw-reply {:seon.repl/current-ns starting-ns})
    (let [parsed (repl.parse/parse-forms raw-reply)
          first-form-index
          (reduce (fn [index entry]
                    (if (= :form (:seon.repl/kind entry))
                      (reduced index)
                      (inc index)))
                  0 parsed)
          retained
          (if (< first-form-index (count parsed))
            (vec (take (inc first-form-index) parsed))
            parsed)
          remaining-form-count
          (->> (drop (inc first-form-index) parsed)
               (filter #(= :form (:seon.repl/kind %)))
               count)
          retained
          (if (pos? remaining-form-count)
            (update-in
             retained [first-form-index :seon.repl/narration]
             (fn [narration]
               (str (when (seq narration) (str narration "\n"))
                    "; stream mode executed the first complete form; "
                    remaining-form-count " further "
                    (if (= 1 remaining-form-count) "form was" "forms were")
                    " not executed — resend the next form.")))
            retained)]
      {:seon.repl/eval-entries retained
       :seon.repl/errors []})))

(defn llm-retryable?
  "Whether one provider response is transient and retryable."
  [response]
  (let [failure (:seon.ai/error response)
        status (:seon.ai/status failure)]
    (boolean
     (and failure
          (or (true? (:seon.ai/transport? failure))
              (= 429 status)
              (and (int? status) (<= 500 status 599)))))))

(defn llm-fallback-eligible?
  "Whether exhausted primary work may use its configured fallback."
  [response]
  (boolean
   (or (llm-retryable? response)
       (true? (get-in response [:seon.ai/error :seon.ai/timeout?])))))

(defn llm-retry-strategy
  "Portable bounded retry strategy from one frozen resolution."
  ([resolution]
   (llm-retry-strategy resolution 0))
  ([resolution retry-reduction]
   (-> (retry/multiplicative-strategy llm-retry-base-ms llm-retry-factor)
       (retry/randomize-strategy llm-retry-jitter)
       (retry/clamp-delay llm-retry-max-delay-ms)
       (retry/max-retries
        (max 0
             (- (or (:seon.ai/agent-max-retries resolution)
                    llm-retry-default-count)
                retry-reduction)))
       (retry/max-duration llm-retry-total-cap-ms))))
