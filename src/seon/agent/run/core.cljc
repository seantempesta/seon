(ns seon.agent.run.core
  "Pure run-claim, lease, and fencing policy shared by pod and JVM drivers.")

(defn error-value?
  "Whether `value` is a direct Seon error envelope."
  [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn instant-ms
  "Portable epoch milliseconds for a database instant."
  [instant]
  #?(:clj (.getTime ^java.util.Date instant)
     :cljs (.getTime ^js instant)))

(defn expired-claim?
  "Whether an observed claim lease is expired at `now`.

   An unclaimed run is not an expired claim. Paused and closed runs are never
   stealable."
  [run now stale-ms]
  (let [beat (:seon.agent.run/last-beat-at run)]
    (and (= :open (:seon.agent.run/status run))
         (nil? (:seon.agent.run/paused-at run))
         (string? (:seon.agent.run/claimant run))
         (some? (:seon.agent.run/claim-epoch run))
         (some? beat)
         (< (instant-ms beat) (- (instant-ms now) stale-ms)))))

(defn live-claim?
  "Whether `run` has a claimant whose derived lease is still live."
  [run now stale-ms]
  (and (string? (:seon.agent.run/claimant run))
       (not (expired-claim? run now stale-ms))))

(defn run-fence
  "The one run-work fence at the driver-held epoch.

   The pointer assertion prevents work after close/supersede. The epoch
   assertion prevents a displaced holder from publishing after takeover."
  [agent-id run-id claim-epoch]
  (let [run-ref [:seon.agent.run/id run-id]]
    [[:db.fn/cas [:seon.agent/id agent-id]
      :seon.agent/run run-ref run-ref]
     [:db.fn/cas run-ref :seon.agent.run/claim-epoch
      claim-epoch claim-epoch]]))

(defn consume-input-data
  "Explicit input-consumption edge for the claim/renew transaction."
  [run-id input-ref]
  (when input-ref
    [[:db/add [:seon.agent.run/id run-id]
      :seon.agent.run/consumed-input input-ref]]))

(defn acquire-tx-data
  "First claim of an open run: absent claimant and epoch become `claimant`, 1."
  [agent-id run-id claimant beat-at input-ref]
  (let [run-ref [:seon.agent.run/id run-id]]
    (into
     [[:db.fn/cas [:seon.agent/id agent-id]
       :seon.agent/run run-ref run-ref]
      [:db.fn/cas run-ref :seon.agent.run/claimant nil claimant]
      [:db.fn/cas run-ref :seon.agent.run/claim-epoch nil 1]
      [:db/add run-ref :seon.agent.run/last-beat-at beat-at]]
     (consume-input-data run-id input-ref))))

(defn reacquire-tx-data
  "Claim a released run without displacing another claimant."
  [agent-id run-id observed-epoch claimant beat-at input-ref]
  (let [run-ref [:seon.agent.run/id run-id]]
    (into
     [[:db.fn/cas [:seon.agent/id agent-id]
       :seon.agent/run run-ref run-ref]
      [:db.fn/cas run-ref :seon.agent.run/claimant nil claimant]
      [:db.fn/cas run-ref :seon.agent.run/claim-epoch
       observed-epoch (inc observed-epoch)]
      [:db/add run-ref :seon.agent.run/last-beat-at beat-at]]
     (consume-input-data run-id input-ref))))

(defn steal-tx-data
  "Take over one observed expired claim.

   The beat old→old CAS closes the read-to-steal race. Adding the new claimant
   replaces the cardinality-one value only after both assertions succeed."
  [agent-id run-id observed-epoch observed-beat claimant beat-at input-ref]
  (let [run-ref [:seon.agent.run/id run-id]]
    (into
     [[:db.fn/cas [:seon.agent/id agent-id]
       :seon.agent/run run-ref run-ref]
      [:db.fn/cas run-ref :seon.agent.run/last-beat-at
       observed-beat observed-beat]
      [:db.fn/cas run-ref :seon.agent.run/claim-epoch
       observed-epoch (inc observed-epoch)]
      [:db/add run-ref :seon.agent.run/claimant claimant]
      [:db/add run-ref :seon.agent.run/last-beat-at beat-at]]
     (consume-input-data run-id input-ref))))

(declare beat-tx-data)

(defn claim-plan
  "Return the exact claim transition observed at one immutable database value.

   A nil result means this claimant must not transact. The authority caller,
   not this builder, commits the returned transaction data."
  [{agent-id :seon.agent/id
    run-id :seon.agent.run/id
    claimant :seon.agent.run/claimant
    claim-epoch :seon.agent.run/claim-epoch
    last-beat-at :seon.agent.run/last-beat-at
    :as run}
   process-claimant now stale-ms input-ref]
  (cond
    (not= :open (:seon.agent.run/status run)) nil
    (some? (:seon.agent.run/paused-at run)) nil
    (nil? claimant)
    (if (nil? claim-epoch)
      {:seon.agent.run/claim-transition :acquire
       :seon.agent.run/claim-epoch 1
       :seon.db/tx-data
       (acquire-tx-data agent-id run-id process-claimant now input-ref)}
      {:seon.agent.run/claim-transition :reacquire
       :seon.agent.run/claim-epoch (inc claim-epoch)
       :seon.db/tx-data
       (reacquire-tx-data agent-id run-id claim-epoch
                          process-claimant now input-ref)})
    (= claimant process-claimant)
    {:seon.agent.run/claim-transition :held
     :seon.agent.run/claim-epoch claim-epoch
     :seon.db/tx-data
     (beat-tx-data agent-id run-id claim-epoch now input-ref)}
    (expired-claim? run now stale-ms)
    {:seon.agent.run/claim-transition :steal
     :seon.agent.run/claim-epoch (inc claim-epoch)
     :seon.db/tx-data
     (steal-tx-data agent-id run-id claim-epoch last-beat-at
                    process-claimant now input-ref)}
    :else nil))

(defn beat-tx-data
  "Renew the held lease under the pointer+epoch fence."
  [agent-id run-id claim-epoch beat-at input-ref]
  (into
   (run-fence agent-id run-id claim-epoch)
   (concat
    [[:db/add [:seon.agent.run/id run-id]
      :seon.agent.run/last-beat-at beat-at]]
    (consume-input-data run-id input-ref))))

(defn release-tx-data
  "Release a held claim without retracting its monotonic epoch."
  [agent-id run-id claim-epoch]
  (conj (run-fence agent-id run-id claim-epoch)
        [:db/retract [:seon.agent.run/id run-id]
         :seon.agent.run/claimant]))

(defn close-tx-data
  "Close and release one driver-held run atomically."
  [agent-id run-id claim-epoch reason closed-at]
  (into
   (run-fence agent-id run-id claim-epoch)
   [{:seon.agent.run/id run-id
     :seon.agent.run/status :closed
     :seon.agent.run/closed-reason reason
     :seon.agent.run/closed-at closed-at}
    [:db/retract [:seon.agent.run/id run-id]
     :seon.agent.run/claimant]
    [:db/retract [:seon.agent/id agent-id] :seon.agent/run]]))
