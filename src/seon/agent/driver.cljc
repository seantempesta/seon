(ns seon.agent.driver
  "The one claim-native portable run driver.

   This namespace owns claim arbitration, held-epoch threading, eligibility,
   clean handoff, and the step loop. Platform leaves execute only the external
   phase they advertise; cursor and receipt transaction builders remain in the
   portable cores."
  #?(:clj (:refer-clojure :exclude [await]))
  (:require [seon.agent.loop.core :as loop.core]
            [seon.agent.run.core :as run.core]
            [seon.config.resolve :as config.resolve]
            [seon.db :as db]
            [seon.schema :as schema]))

#?(:clj (defmacro await [value] value))

(schema/register!
 :seon.agent.driver/capability
 [:enum :seon.agent.driver.capability/render
  :seon.agent.driver.capability/llm
  :seon.agent.driver.capability/eval
  :seon.agent.driver.capability/publish])
(schema/register!
 :seon.agent.driver/capabilities
 [:set :seon.agent.driver/capability])
(schema/register! :seon.agent.driver/claimant [:string {:min 1}])

(defonce ^:private process-start
  #?(:clj (java.time.Instant/now)
     :cljs (.toISOString (js/Date.))))

(defonce ^{:doc "This process instance's stable, self-derived custody identity."}
  claimant
  (str #?(:clj (.pid (java.lang.ProcessHandle/current))
          :cljs (.-pid js/process))
       "@"
       process-start))

(def ^:dynamic *leaf* nil)

(defn- leaf-fn [key]
  (or (get *leaf* key)
      (throw
       (ex-info "The claim driver platform leaf is incomplete."
                {:seon.error/kind :core-bug
                 :seon.agent.driver/missing-leaf key}))))

(def ^:private open-runs-query
  '[:find ?agent-id ?run-id
    :where
    [?agent :seon.agent/id ?agent-id]
    [?agent :seon.agent/run ?run]
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/status :open]
    (not [?run :seon.agent.run/paused-at _])])

(def ^:private pending-input-query
  {:find '[?message-id ?message-tx]
   :in '[$ ?agent-id ?run-id ?hop-cap]
   :where
   '[[?agent :seon.agent/id ?agent-id]
     [?run :seon.agent.run/id ?run-id]
     [?message :seon.agent.message/id ?message-id]
     [?message :seon.agent.message/to ?agent ?message-tx]
     [?message :seon.agent.message/from ?sender]
     [(not= ?sender ?agent)]
     [(get-else $ ?message :seon.agent.message/origin :agent) ?origin]
     [(not= ?origin :core)]
     [(get-else $ ?message :seon.agent.message/hops 0) ?hops]
     [(< ?hops ?hop-cap)]
     (not-join [?run ?message]
       [?run :seon.agent.run/consumed-input ?message])]
   :order-by '[?message-tx :asc ?message-id :asc]
   :limit 1})

(def ^:private run-selector
  [:db/id
   :seon.agent.run/id
   :seon.agent.run/status
   :seon.agent.run/started-at
   :seon.agent.run/turn-limit
   :seon.agent.run/deadline
   :seon.agent.run/last-beat-at
   :seon.agent.run/claimant
   :seon.agent.run/claim-epoch
   :seon.agent.run/paused-at
   {:seon.agent.run/cause [:db/id :seon.agent.message/id]}
   {:seon.agent.run/consumed-input [:db/id]}
   {:seon.agent.turn/_run
    [:db/id
     :seon.agent.turn/id
     :seon.agent.turn/at
     :seon.agent.turn/status
     :seon.agent.turn/scheduled?
     :seon.agent.turn/phase
     :seon.agent.turn/rendered-tx
     {:seon.agent.turn/prompt-blob [:my.blob/hash]}
     {:seon.agent.turn/reply-blob [:my.blob/hash]}
     {:seon.agent.turn/llm-attempts
      [:db/id :seon.ai.attempt/id :seon.ai.attempt/ordinal
       :seon.ai.attempt/outcome :seon.ai.attempt/deadline-at]}
     {:seon.agent.turn/evals
      [:seon.eval/id :seon.eval/source :seon.eval/status :seon.eval/ok?
       :seon.eval/progress? :seon.eval/result-edn :seon.eval/output
       :seon.eval/error :seon.eval/error-data :seon.eval/ns]}]}])

(def ^:private claim-policy-selector
  [:seon.config.watchdog/stale-ms :seon.config/repl-mode])

(defn- work-count [run repl-mode]
  (let [turns (->> (:seon.agent.turn/_run run)
                   (remove :seon.agent.turn/scheduled?)
                   (filter #(= :done (:seon.agent.turn/status %))))]
    (if (= :stream repl-mode)
      (reduce + 0 (map #(count (:seon.agent.turn/evals %)) turns))
      (count turns))))

(defn- close-reason [run policy now]
  (cond
    (and (int? (:seon.agent.run/turn-limit run))
         (>= (work-count run (:seon.config/repl-mode policy))
             (:seon.agent.run/turn-limit run)))
    :turn-limit

    (and (:seon.agent.run/deadline run)
         (> (run.core/instant-ms now)
            (run.core/instant-ms (:seon.agent.run/deadline run))))
    :deadline-exceeded

    (>= (loop.core/no-progress-streak run)
        loop.core/no-progress-streak-limit)
    :no-forms

    :else nil))

(defn- latest-turn [run]
  (->> (:seon.agent.turn/_run run)
       (filter #(= :running (:seon.agent.turn/status %)))
       (sort-by :db/id)
       last))

(defn- cause-ref [run]
  (when-let [input-id
             (get-in run [:seon.agent.run/cause
                          :seon.agent.message/id])]
    [:seon.agent.message/id input-id]))

(defn- consumed-cause? [run]
  (let [cause-eid (get-in run [:seon.agent.run/cause :db/id])]
    (and cause-eid
         (some #(= cause-eid (:db/id %))
               (:seon.agent.run/consumed-input run)))))

(defn- acquired-run
  [run agent-id]
  (cond-> (assoc run :seon.agent/id agent-id)
    (latest-turn run)
    (assoc :seon.agent.run/current-turn (latest-turn run))))

(defn ^:async acquire-run-state!
  "Read one run and the one claim policy at an explicit database value."
  [database agent-id run-id]
  (let [run
        (await
         (db/pull
          {::db/db database
           ::db/pull-pattern run-selector
           ::db/ref [:seon.agent.run/id run-id]}))
        policy
        (await
         (db/pull
          {::db/db database
           ::db/pull-pattern claim-policy-selector
           ::db/ref [:seon.config/id config.resolve/cluster-config-id]}))
        pending-input
        (await
         (db/query
          {::db/db database
           ::db/query pending-input-query
           ::db/args [agent-id run-id loop.core/hop-cap]}))]
    (cond
      (run.core/error-value? run) run
      (run.core/error-value? policy) policy
      (run.core/error-value? pending-input) pending-input
      (nil? run)
      {:seon.error/message (str "No run " (pr-str run-id) " exists.")
       :seon.error/kind :core-bug}
      (not (int? (:seon.config.watchdog/stale-ms policy)))
      {:seon.error/message "The claim lease policy is missing."
       :seon.error/kind :configuration}
      :else
      {:seon.db/db database
       :seon.config.watchdog/stale-ms
       (:seon.config.watchdog/stale-ms policy)
       :seon.agent.driver/policy policy
       :seon.agent.driver/pending-input
       (when-let [[message-id] (first pending-input)]
         [:seon.agent.message/id message-id])
       :seon.agent.driver/run (acquired-run run agent-id)})))

(defn ^:async claim!
  "Attempt exactly one claim transition at one immutable database value."
  [{database :seon.db/db :as request}]
  (let [agent-id (:seon.agent/id request)
        run-id (:seon.agent.run/id request)
        database (or database (await (db/db)))]
    (if (run.core/error-value? database)
      database
      (let [state (await (acquire-run-state! database agent-id run-id))]
        (if (run.core/error-value? state)
          state
          (let [run (:seon.agent.driver/run state)
                input-ref
                (or (:seon.agent.run/input-ref request)
                    (:seon.agent.driver/pending-input state)
                    (when-not (consumed-cause? run) (cause-ref run)))
                plan
                (when
                 (loop.core/eligible?
                  (:seon.agent.driver/capabilities *leaf*) run)
                  (run.core/claim-plan
                   run claimant ((leaf-fn :seon.agent.driver/now))
                   (:seon.config.watchdog/stale-ms state) input-ref))]
            (cond
              (nil? plan) nil
              :else
              (let [report
                    (await
                     (db/transact!
                      {::db/db database
                       ::db/tx-data (:seon.db/tx-data plan)}))]
                (if (run.core/error-value? report)
                  report
                  (let [claimed
                        (await
                         (acquire-run-state!
                          (:db-after report) agent-id run-id))]
                    (assoc claimed
                           :seon.agent.driver/claimant claimant
                           :seon.agent.run/claim-epoch
                           (:seon.agent.run/claim-epoch plan))))))))))))

(defn ^:async release!
  "Release custody at the held epoch without changing the cursor."
  [{:seon.agent.driver/keys [run]
    claim-epoch :seon.agent.run/claim-epoch
    database :seon.db/db}]
  (await
   (db/transact!
    {::db/db database
     ::db/tx-data
     (run.core/release-tx-data
      (:seon.agent/id run)
      (:seon.agent.run/id run)
      claim-epoch)})))

(defn ^:async drive-claim!
  "Advance one held run until close, loss, or a clean tier handoff."
  [claim]
  (loop [held claim]
    (let [run (:seon.agent.driver/run held)
          capabilities (:seon.agent.driver/capabilities *leaf*)
          now ((leaf-fn :seon.agent.driver/now))]
      (if-let [reason (close-reason run
                                    (:seon.agent.driver/policy held)
                                    now)]
        (let [report
              (await
               (db/transact!
                {::db/db (:seon.db/db held)
                 ::db/tx-data
                 (run.core/close-tx-data
                  (:seon.agent/id run)
                  (:seon.agent.run/id run)
                  (:seon.agent.run/claim-epoch held)
                  reason now)}))]
          (if (run.core/error-value? report)
            report
            {:seon.db/db (:db-after report)
             :seon.agent.driver/closed? true
             :seon.agent.run/closed-reason reason}))
        (if-not (loop.core/eligible? capabilities run)
        (await (release! held))
        (let [step (loop.core/next-step run)
              result
              (await
               ((leaf-fn :seon.agent.driver/execute-step!)
                (assoc held :seon.agent.driver/step step)))]
          (cond
            (run.core/error-value? result) result
            (:seon.agent.driver/closed? result) result
            (:seon.agent.driver/released? result) result
            (:seon.db/db result)
            (let [next-state
                  (await
                   (acquire-run-state!
                    (:seon.db/db result)
                    (:seon.agent/id run)
                    (:seon.agent.run/id run)))]
              (if (run.core/error-value? next-state)
                next-state
                (recur
                 (merge held next-state
                        {:seon.agent.run/claim-epoch
                         (:seon.agent.run/claim-epoch held)}))))
            :else
            {:seon.error/message
             (str "The " (name step) " leaf returned no database value.")
             :seon.error/kind :core-bug})))))))

(defn ^:async drive-run!
  "Claim and drive one open run; a CAS loser returns its direct error value."
  [request]
  (let [claim (await (claim! request))]
    (cond
      (nil? claim) nil
      (run.core/error-value? claim) claim
      :else (await (drive-claim! claim)))))

(defn ^:async scan!
  "Drive every currently open run that this tier can claim."
  []
  (let [database (await (db/db))]
    (if (run.core/error-value? database)
      database
      (let [rows
            (await
             (db/query
              {::db/db database
               ::db/query open-runs-query}))]
        (if (run.core/error-value? rows)
          rows
          (loop [remaining (vec (sort rows))
                 results []]
            (if-let [[agent-id run-id] (first remaining)]
              (recur
               (subvec remaining 1)
               (conj results
                     (if-let [dispatch
                              (:seon.agent.driver/dispatch-run! *leaf*)]
                       (dispatch
                        {:seon.agent/id agent-id
                         :seon.agent.run/id run-id})
                       (await
                        (drive-run!
                         {:seon.agent/id agent-id
                          :seon.agent.run/id run-id})))))
              results)))))))

(defn ^:async call-with-leaf
  "Run a driver operation with one platform leaf and database leaf."
  [platform-leaf database-leaf operation]
  (binding [*leaf* platform-leaf
            db/*leaf* database-leaf]
    (await (operation))))
