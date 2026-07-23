(ns seon.agent.turn.llm
  "Portable durable LLM phase for every claim-native driver tier."
  #?(:clj (:refer-clojure :exclude [await]))
  (:require [clojure.string :as str]
            [my.blob :as blob]
            [seon.ai.core :as ai.core]
            [seon.agent.run.core :as run.core]
            [seon.agent.turn.core :as turn.core]
            [seon.content-hash :as content-hash]
            [seon.db :as db]
            [seon.db.id :as db.id]
            [seon.retry :as retry]
            [seon.schema :as schema])
  #?(:clj
     (:import [java.util.concurrent Executors ScheduledExecutorService
               ScheduledFuture ThreadFactory TimeUnit])))

#?(:clj (defmacro await [value] value))

(schema/register! :seon.ai.attempt/reply-evaluation
                  :seon.ai/reply-evaluation)
(schema/register!
 :seon.ai.attempt/partial-text
 [:string {:seon.db/no-history? true}])

#?(:clj
   (defonce ^:private partial-scheduler
     (Executors/newSingleThreadScheduledExecutor
      (reify ThreadFactory
        (newThread [_ runnable]
          (doto (Thread. runnable "seon-llm-partial-publisher")
            (.setDaemon true)))))))

(defn- cancel-scheduled! [scheduled]
  #?(:clj (when scheduled (.cancel ^ScheduledFuture scheduled false))
     :cljs (when scheduled (js/clearTimeout scheduled))))

(defn- schedule-after! [milliseconds callback]
  #?(:clj
     (.schedule ^ScheduledExecutorService partial-scheduler
                ^Runnable callback
                (long milliseconds)
                TimeUnit/MILLISECONDS)
     :cljs
     (js/setTimeout callback milliseconds)))

(defn presentation-sink
  "Create a non-blocking latest-prefix sink with isolated publication."
  [settle-ms publish!]
  (let [state (atom {::closed? false
                     ::pending nil
                     ::scheduled nil
                     ::publishing? false})
        schedule-flush! (atom nil)
        publication-finished!
        (fn []
          (let [schedule? (atom false)]
            (swap! state
                   (fn [current]
                     (let [current (assoc current ::publishing? false)]
                       (if (and (not (::closed? current))
                                (string? (::pending current))
                                (nil? (::scheduled current)))
                         (do
                           (reset! schedule? true)
                           (assoc current ::scheduled ::arming))
                         current))))
            (when @schedule? (@schedule-flush!))))
        flush!
        (fn []
          (let [text (atom nil)]
            (swap! state
                   (fn [current]
                     (if (or (::closed? current)
                             (::publishing? current))
                       (assoc current ::scheduled nil)
                       (do
                         (reset! text (::pending current))
                         (assoc current
                                ::pending nil
                                ::scheduled nil
                                ::publishing? (string? (::pending current)))))))
            (when (string? @text)
              #?(:clj
                 (try
                   (publish! @text)
                   (catch Throwable _ nil)
                   (finally (publication-finished!)))
                 :cljs
                 (-> (js/Promise.resolve nil)
                     (.then (fn [_] (publish! @text)))
                     (.catch (fn [_] nil))
                     (.finally publication-finished!))))))
        arm!
        (fn []
          (let [scheduled (schedule-after! settle-ms flush!)
                keep? (atom false)]
            (swap! state
                   (fn [current]
                     (if (and (not (::closed? current))
                              (= ::arming (::scheduled current)))
                       (do (reset! keep? true)
                           (assoc current ::scheduled scheduled))
                       current)))
            (when-not @keep? (cancel-scheduled! scheduled))))]
    (reset! schedule-flush! arm!)
    {:seon.ai.presentation/offer!
     (fn [complete-prefix]
       (when (string? complete-prefix)
         (let [schedule? (atom false)]
           (swap! state
                  (fn [current]
                    (if (::closed? current)
                      current
                      (let [current (assoc current ::pending complete-prefix)]
                        (if (and (nil? (::scheduled current))
                                 (not (::publishing? current)))
                          (do
                            (reset! schedule? true)
                            (assoc current ::scheduled ::arming))
                          current)))))
           (when @schedule? (arm!))))
       nil)
     :seon.ai.presentation/close!
     (fn []
       (let [scheduled (atom nil)]
         (swap! state
                (fn [current]
                  (reset! scheduled (::scheduled current))
                  (assoc current
                         ::closed? true
                         ::pending nil
                         ::scheduled nil)))
         (when-not (= ::arming @scheduled)
           (cancel-scheduled! @scheduled)))
       nil)}))

(def claim-attempt-selector
  [:seon.agent.turn/id
   :seon.agent.turn/phase
   :seon.agent.turn/rendered-tx
   {:seon.agent.turn/prompt-blob [:my.blob/hash]}
    {:seon.agent.turn/llm-attempts
    [:seon.ai.attempt/id :seon.ai.attempt/ordinal
     :seon.ai.attempt/outcome
     :seon.ai.attempt/reply-evaluation]}])

(defn- instant-ms [instant]
  #?(:clj (.getTime ^java.util.Date instant)
     :cljs (.getTime ^js/Date instant)))

(defn- instant [milliseconds]
  #?(:clj (java.util.Date. (long milliseconds))
     :cljs (js/Date. milliseconds)))

(defn- ref-value [attribute value]
  (cond
    (vector? value) (second value)
    (map? value) (get value attribute)
    :else nil))

(defn split-persisted-prompt
  "Recover the exact provider blocks from one committed prompt artifact."
  [full-prompt]
  (when-let [boundary-index
             (str/index-of full-prompt ai.core/system-boundary)]
    {:seon.ai/system-prompt (subs full-prompt 0 boundary-index)
     :seon.ai/ctx
     (subs full-prompt
           (+ boundary-index (count ai.core/system-boundary)))}))

(defn attempt-outcome
  "Classify one transport result into the durable attempt vocabulary."
  [response]
  (cond
    (get-in response [:seon.ai/error :seon.ai/outer-timeout?])
    :outer-timeout

    (get-in response [:seon.ai/error :seon.ai/timeout?])
    :adapter-timeout

    (:seon.ai/error response)
    :provider-error

    :else :success))

(defn attempt-row
  "Build bounded terminal evidence from a frozen request and response."
  [ordinal fallback-variant resolution timeout-ms stream? reply-evaluation
   response]
  (let [config (:seon.ai/resolved-config resolution)
        raw (or (:seon.ai/raw response) response)
        credential (get-in raw [:seon.ai/config-evidence
                                :seon.ai/credential-source])
        endpoint-result
        (when (contains? #{:deepseek :openai-compat}
                         (:seon.ai/provider config))
          (when-let [endpoint-cap
                     (:seon.config.model-transport/endpoint-cap config)]
            (some-> (:seon.ai/base-url config)
                    (ai.core/openai-request-endpoint endpoint-cap))))
        response-identity-cap
        (:seon.config.model-transport/response-identity-cap config)
        evidence-error
        (when response-identity-cap
          (some-> (or (when (map? endpoint-result)
                        (:seon.ai/msg endpoint-result))
                      (:seon.ai/evidence-error raw)
                      (get-in response
                              [:seon.ai/error :seon.ai/evidence-error]))
                  (ai.core/bounded-evidence-error response-identity-cap)))
        adapter (or (:seon.ai/adapter response)
                    (ai.core/resolved-adapter config))
        status (get-in response [:seon.ai/error :seon.ai/status])
        finish-reason (:seon.ai.openai-compat/finish-reason raw)
        usage (:seon.ai/usage raw)]
    (cond->
     {:seon.ai.attempt/ordinal ordinal
      :seon.ai.attempt/provider (:seon.ai/provider config)
      :seon.ai.attempt/adapter adapter
      :seon.ai.attempt/outer-timeout-ms timeout-ms
      :seon.ai.attempt/stream? (boolean stream?)
      :seon.ai.attempt/reply-evaluation reply-evaluation
      :seon.ai.attempt/outcome (attempt-outcome response)}
      fallback-variant
      (assoc :seon.ai.attempt/fallback-variant fallback-variant)
      (:seon.ai/model config)
      (assoc :seon.ai.attempt/requested-model (:seon.ai/model config))
      (contains? config :seon.ai/temperature)
      (assoc :seon.ai.attempt/temperature (:seon.ai/temperature config))
      (:seon.ai/max-tokens config)
      (assoc :seon.ai.attempt/max-tokens (:seon.ai/max-tokens config))
      (:seon.ai/completion-limit-field config)
      (assoc :seon.ai.attempt/completion-limit-field
             (:seon.ai/completion-limit-field config))
      (contains? config :seon.ai/thinking)
      (assoc :seon.ai.attempt/thinking (:seon.ai/thinking config))
      (string? endpoint-result)
      (assoc :seon.ai.attempt/endpoint endpoint-result)
      evidence-error
      (assoc :seon.ai.attempt/evidence-error evidence-error)
      (:seon.ai/timeout-ms config)
      (assoc :seon.ai.attempt/adapter-timeout-ms (:seon.ai/timeout-ms config))
      (:seon.ai/extra-body-digest config)
      (assoc :seon.ai.attempt/extra-body-digest
             (:seon.ai/extra-body-digest config))
      (:seon.ai/dg-backend config)
      (assoc :seon.ai.attempt/dg-backend (:seon.ai/dg-backend config))
      (:seon.ai/api-key-env config)
      (assoc :seon.ai.attempt/api-key-env (:seon.ai/api-key-env config))
      (:seon.ai/credential-class credential)
      (assoc :seon.ai.attempt/credential-class
             (:seon.ai/credential-class credential))
      status
      (assoc :seon.ai.attempt/error-status status)
      (:seon.ai/response-model raw)
      (assoc :seon.ai.attempt/response-model (:seon.ai/response-model raw))
      (:seon.ai/system-fingerprint raw)
      (assoc :seon.ai.attempt/system-fingerprint
             (:seon.ai/system-fingerprint raw))
      (:seon.ai/request-id raw)
      (assoc :seon.ai.attempt/request-id (:seon.ai/request-id raw))
      finish-reason
      (assoc :seon.ai.attempt/finish-reason finish-reason)
      (:seon.ai/truncated? raw)
      (assoc :seon.ai.attempt/truncated? true)
      (seq usage)
      (assoc :seon.ai.attempt/usage (pr-str usage)))))

(defn attempt-deadline
  "Derive the absolute step deadline from run and frozen attempt bounds."
  [run now attempt-timeout-ms]
  (let [attempt-end (+ (instant-ms now) attempt-timeout-ms)
        run-end (some-> (:seon.agent.run/deadline run) instant-ms)]
    (instant (if run-end (min run-end attempt-end) attempt-end))))

(defn remaining-ms
  "Positive milliseconds remaining until an absolute deadline."
  [now deadline]
  (max 1 (- (instant-ms deadline) (instant-ms now))))

(defn- open-evidence
  [ordinal fallback-variant resolution deadline timeout-ms stream?
   reply-evaluation]
  (let [template
        (attempt-row ordinal fallback-variant resolution timeout-ms
                     stream? reply-evaluation {})]
    (assoc (dissoc template :seon.ai.attempt/outcome)
           :seon.ai.attempt/config-digest
           (content-hash/sha-256 (pr-str resolution))
           :seon.ai.attempt/deadline-at deadline)))

(defn- blob-ref [result]
  (when (:my.blob/ok? result)
    [:my.blob/hash (:my.blob/hash result)]))

(defn ^{:async #?(:cljs true :clj false)} durable-attempt!
  "Open, dispatch, and terminalize one durable provider attempt."
  [{:seon.agent.driver/keys [run]
    claim-epoch :seon.agent.run/claim-epoch
    database :seon.db/db
    resolution :seon.ai/config-resolution
    prompt :seon.ai/ctx
    system-prompt :seon.ai/system-prompt
    stream? :seon.ai/stream?
    reply-evaluation :seon.ai/reply-evaluation
    settle-ms :seon.config.model-stream/partial-publish-settle-ms
    transport! :seon.agent.turn/transport!
    now! :seon.agent.turn/now!}]
  (let [agent-id (:seon.agent/id run)
        run-id (:seon.agent.run/id run)
        turn-id (get-in run [:seon.agent.run/current-turn
                             :seon.agent.turn/id])
        turn (await
              (db/pull {::db/db database
                        ::db/pull-pattern claim-attempt-selector
                        ::db/ref [:seon.agent.turn/id turn-id]}))
        attempts (vec (:seon.agent.turn/llm-attempts turn))
        open-attempt (last (sort-by :seon.ai.attempt/ordinal
                                    (filter #(= :open
                                                (:seon.ai.attempt/outcome %))
                                            attempts)))
        fence (run.core/run-fence agent-id run-id claim-epoch)
        crash-report
        (when open-attempt
          (await
           (db/transact!
            {::db/db database
             ::db/tx-data
             (turn.core/crash-open-attempt-tx-data
              fence turn-id (:seon.ai.attempt/id open-attempt))})))
        database (if (:db-after crash-report) (:db-after crash-report) database)
        attempts (cond-> attempts
                   open-attempt
                   (conj (assoc open-attempt
                                :seon.ai.attempt/outcome :crashed)))
        ordinal (turn.core/next-attempt-ordinal attempts)
        attempt-timeout-ms (:seon.ai/agent-attempt-timeout-ms resolution)
        now (now!)
        deadline (attempt-deadline run now attempt-timeout-ms)
        timeout-ms (remaining-ms now deadline)
        evidence (open-evidence ordinal nil resolution deadline timeout-ms
                                stream? reply-evaluation)
        allocation
        (if (:seon.error/message crash-report)
          crash-report
          (await
           (db.id/allocate!
            {::db/db database
             ::db.id/allocations
             [{::db.id/key ::claim-attempt
               ::db.id/identity-attr :seon.ai.attempt/id}]
             ::db.id/transaction-builder
             (fn [{attempt-id ::claim-attempt}]
               {::db/tx-data
                ((if (= :rendered (:seon.agent.turn/phase turn))
                   turn.core/open-attempt-tx-data
                   turn.core/next-attempt-tx-data)
                 fence turn-id attempt-id evidence)})})))
        attempt-id (get-in allocation [::db.id/ids ::claim-attempt])]
    (if (:seon.error/message allocation)
      allocation
      (let [sink
            (presentation-sink
             settle-ms
             (fn [partial-text]
               (db/transact!
                {::db/db (:db-after allocation)
                 ::db/tx-data
                 (turn.core/partial-attempt-tx-data
                  fence turn-id attempt-id partial-text)})))]
        (try
          (let [response
                (await
                 (transport!
                  (cond-> {:seon.ai/ctx prompt
                           :seon.ai/config-resolution resolution
                           :seon.ai/request-timeout-ms timeout-ms
                           :seon.ai/deadline-at deadline
                           :seon.ai/reply-evaluation reply-evaluation
                           :seon.ai/progress!
                           (:seon.ai.presentation/offer! sink)}
                    (string? system-prompt)
                    (assoc :seon.ai/system-prompt system-prompt)
                    stream? (assoc :seon.ai/stream? true))))
                terminal
                (attempt-row ordinal nil resolution timeout-ms stream?
                             reply-evaluation response)
                reply-result
                (when (= :success (:seon.ai.attempt/outcome terminal))
                  (await
                   (blob/put! {:my.blob/content (or (:text response) "")
                               :my.blob/media :reply})))
                reply-blob (blob-ref reply-result)
                retry-after
                (get-in response [:seon.ai/error :seon.ai/retry-after-ms])
                terminal (cond-> terminal
                           (int? retry-after)
                           (assoc :seon.ai.attempt/retry-after-ms retry-after))
                publication-failed?
                (and (= :success (:seon.ai.attempt/outcome terminal))
                     (nil? reply-blob))]
            (if publication-failed?
              {:seon.error/message
               (or (:my.blob/error reply-result)
                   "The successful LLM reply was not published to blob storage.")
               :seon.error/kind :core-bug
               :seon.db/db (:db-after allocation)}
              (let [report
                    (await
                     (db/transact!
                      {::db/db (:db-after allocation)
                       ::db/tx-data
                       (turn.core/terminal-attempt-tx-data
                        fence turn-id attempt-id terminal reply-blob)}))]
                (if (:seon.error/message report)
                  report
                  (assoc response :seon.db/db (:db-after report))))))
          (finally
            ((:seon.ai.presentation/close! sink))))))))

(defn ^{:async #?(:cljs true :clj false)} llm-phase!
  "Resume the durable attempt cursor and advance a successful reply."
  [{:seon.agent.driver/keys [run]
    claim-epoch :seon.agent.run/claim-epoch
    database :seon.db/db
    resolve-context! :seon.agent.turn/resolve-context!
    :as request}]
  (let [agent-id (:seon.agent/id run)
        run-id (:seon.agent.run/id run)
        turn (:seon.agent.run/current-turn run)
        turn-id (:seon.agent.turn/id turn)
        rendered-db
        (db/as-of database
                  (ref-value :db/id (:seon.agent.turn/rendered-tx turn)))
        context (await (resolve-context! agent-id rendered-db run-id))
        prompt-hash
        (ref-value :my.blob/hash (:seon.agent.turn/prompt-blob turn))
        prompt-artifact (blob/get {:my.blob/hash prompt-hash})
        persisted
        (when (:my.blob/ok? prompt-artifact)
          (split-persisted-prompt (:my.blob/content prompt-artifact)))]
    (cond
      (:seon.error/message context) context
      (nil? persisted)
      {:seon.error/message
       (or (:my.blob/error prompt-artifact)
           "The persisted prompt artifact has no system/context boundary.")
       :seon.error/kind :core-bug
       :seon.error/data
       {:seon.agent.turn/id turn-id
        :seon.agent.turn/prompt-blob prompt-hash}}
      :else
      (let [resolution (:seon.ai/config-resolution context)
            retry-configuration
            (select-keys
             context
             [:seon.config.llm-retry/maximum-wait-ms
              :seon.config.llm-retry/maximum-total-wait-ms
              :seon.config.llm-retry/default-retries])
            stream? (:seon.ai/wire-stream? context)
            reply-evaluation (:seon.ai/reply-evaluation context)
            settle-ms
            (:seon.config.model-stream/partial-publish-settle-ms context)
            attempt-count (count (:seon.agent.turn/llm-attempts turn))
            maximum-attempts
            (inc (or (:seon.ai/agent-max-retries resolution)
                     (:seon.config.llm-retry/default-retries
                      retry-configuration)))]
        (if (>= attempt-count maximum-attempts)
          (let [report
                (await
                 (db/transact!
                  {::db/db database
                   ::db/tx-data
                   (turn.core/error-close-tx-data
                    (run.core/run-fence agent-id run-id claim-epoch)
                    agent-id run-id turn-id
                    ((:seon.agent.turn/now! request))
                    "The durable LLM attempt budget was exhausted.")}))]
            (if (:seon.error/message report)
              report
              {:seon.db/db (:db-after report)
               :seon.agent.driver/closed? true}))
          (await
           (retry/with-retry!
            {:seon.retry/thunk
             #(durable-attempt!
               (assoc request
                      :seon.ai/config-resolution resolution
                      :seon.ai/ctx (:seon.ai/ctx persisted)
                      :seon.ai/system-prompt
                      (:seon.ai/system-prompt persisted)
                      :seon.ai/stream? stream?
                      :seon.ai/reply-evaluation reply-evaluation
                      :seon.config.model-stream/partial-publish-settle-ms
                      settle-ms))
             :seon.retry/strategy
             (turn.core/llm-retry-strategy
              resolution retry-configuration attempt-count)
             :seon.retry/retry? turn.core/llm-retryable?
             :seon.retry/override
             (fn [response]
               (some-> (get-in response
                               [:seon.ai/error
                                :seon.ai/retry-after-ms])
                       (min
                        (:seon.config.llm-retry/maximum-wait-ms
                         retry-configuration))))})))))))
