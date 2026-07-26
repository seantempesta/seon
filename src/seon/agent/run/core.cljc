(ns seon.agent.run.core
  "Pure run acquisition, lease, and fencing shared by active drivers."
  (:require
    [seon.agent.core]
    [seon.db.id :as db.id]
    [seon.schema :as schema]))

;; The run contract is portable because the synchronous JVM driver is now the
;; execution owner. `seon.agent.run` previously registered these only when the
;; CLJS pod loaded; a cold JVM therefore could not validate or install its own
;; run transaction data.
(schema/register!
  :seon.agent.run/id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! :seon.agent.run/agent :seon.db/ref)
(schema/register! :seon.agent.run/started-at :inst)
(schema/register! :seon.agent.run/cause :seon.db/ref)
(schema/register! :seon.agent.run/process [:string {:min 1}])
(schema/register! :seon.agent.run/claim-epoch [:int {:min 1}])
(schema/register! :seon.agent.run/lease-until :inst)
(schema/register! :seon.agent.run/status [:enum :open :closed])
(schema/register! :seon.agent.run/closed-reason :keyword)
(schema/register! :seon.agent.run/closed-at :inst)
(schema/register! :seon.agent.run/result :string)
(schema/register! :seon.agent.run/plan-digest :string)
(schema/register! :seon.agent.run/forms
                  [:vector {:seon.db/component true} :seon.db/ref])
(schema/register! :seon.agent.run.form/id
                  [:string {:seon.db/identity true}])
(schema/register! :seon.agent.run.form/run :seon.db/ref)
(schema/register! :seon.agent.run.form/ordinal :seon.eval/ordinal)
(schema/register! :seon.agent.run.form/source :string)
(schema/register!
  :seon.agent.run.form
  [:map {:seon.db/entity true}
   [:seon.agent.run.form/id :seon.agent.run.form/id]
   [:seon.agent.run.form/run :seon.agent.run.form/run]
   [:seon.agent.run.form/ordinal :seon.agent.run.form/ordinal]
   [:seon.agent.run.form/source :seon.agent.run.form/source]])

(schema/register!
  :seon.agent.run
  [:map {:seon.db/entity true}
   [:seon.agent.run/id :seon.agent.run/id]
   [:seon.agent.run/agent :seon.agent.run/agent]
   [:seon.agent.run/started-at :seon.agent.run/started-at]
   [:seon.agent.run/status :seon.agent.run/status]
   [:seon.agent.run/cause {:optional true} :seon.agent.run/cause]
   [:seon.agent.run/process {:optional true} :seon.agent.run/process]
   [:seon.agent.run/claim-epoch
    {:optional true} :seon.agent.run/claim-epoch]
   [:seon.agent.run/lease-until
    {:optional true} :seon.agent.run/lease-until]
   [:seon.agent.run/closed-reason
    {:optional true} :seon.agent.run/closed-reason]
   [:seon.agent.run/closed-at
    {:optional true} :seon.agent.run/closed-at]
   [:seon.agent.run/result
    {:optional true} :seon.agent.run/result]
   [:seon.agent.run/plan-digest
    {:optional true} :seon.agent.run/plan-digest]
   [:seon.agent.run/forms
    {:optional true} :seon.agent.run/forms]])

(defn error-value?
  "Whether `value` is a direct Seon error envelope."
  [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn instant-ms
  "Portable epoch milliseconds for a database instant."
  [instant]
  #?(:clj (.getTime ^java.util.Date instant)
     :cljs (.getTime ^js instant)))

(defn expired-lease?
  "Whether a process-held run has reached its stored lease instant."
  [run now]
  (let [lease-until (:seon.agent.run/lease-until run)]
    (and (= :open (:seon.agent.run/status run))
         (string? (:seon.agent.run/process run))
         (some? (:seon.agent.run/claim-epoch run))
         (some? lease-until)
         (<= (instant-ms lease-until) (instant-ms now)))))

(defn live-process?
  "Whether `run` has a process whose lease is still live."
  [run now]
  (and (= :open (:seon.agent.run/status run))
       (string? (:seon.agent.run/process run))
       (some? (:seon.agent.run/claim-epoch run))
       (some? (:seon.agent.run/lease-until run))
       (not (expired-lease? run now))))

(defn lease-wake-at
  "The exact one-shot wake instant for an open process-held run."
  [run]
  (when (and (= :open (:seon.agent.run/status run))
             (string? (:seon.agent.run/process run)))
    (:seon.agent.run/lease-until run)))

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

(defn acquire-tx-data
  "First acquisition: absent process and epoch become `process`, 1."
  [agent-id run-id process lease-until]
  (let [run-ref [:seon.agent.run/id run-id]]
    [[:db.fn/cas [:seon.agent/id agent-id]
      :seon.agent/run run-ref run-ref]
     [:db.fn/cas run-ref :seon.agent.run/process nil process]
     [:db.fn/cas run-ref :seon.agent.run/claim-epoch nil 1]
     [:db/add run-ref :seon.agent.run/lease-until lease-until]]))

(defn reacquire-tx-data
  "Acquire a released run without displacing another process."
  [agent-id run-id observed-epoch process lease-until]
  (let [run-ref [:seon.agent.run/id run-id]]
    [[:db.fn/cas [:seon.agent/id agent-id]
      :seon.agent/run run-ref run-ref]
     [:db.fn/cas run-ref :seon.agent.run/process nil process]
     [:db.fn/cas run-ref :seon.agent.run/claim-epoch
      observed-epoch (inc observed-epoch)]
     [:db/add run-ref :seon.agent.run/lease-until lease-until]]))

(defn steal-tx-data
  "Take over one observed expired lease.

   The lease old→old CAS closes the read-to-takeover race. Adding the new
   process replaces the cardinality-one value only after both assertions
   succeed."
  [agent-id run-id observed-epoch observed-lease process lease-until]
  (let [run-ref [:seon.agent.run/id run-id]]
    [[:db.fn/cas [:seon.agent/id agent-id]
      :seon.agent/run run-ref run-ref]
     [:db.fn/cas run-ref :seon.agent.run/lease-until
      observed-lease observed-lease]
     [:db.fn/cas run-ref :seon.agent.run/claim-epoch
      observed-epoch (inc observed-epoch)]
     [:db/add run-ref :seon.agent.run/process process]
     [:db/add run-ref :seon.agent.run/lease-until lease-until]]))

(declare renew-tx-data)

(defn claim-plan
  "Return the exact claim transition observed at one immutable database value.

   A nil result means this process must not transact. The authority caller,
   not this builder, commits the returned transaction data."
  [{agent-id :seon.agent/id
    run-id :seon.agent.run/id
    process :seon.agent.run/process
    claim-epoch :seon.agent.run/claim-epoch
    observed-lease :seon.agent.run/lease-until
    :as run}
   process-id now lease-until]
  (cond
    (not= :open (:seon.agent.run/status run)) nil
    (nil? process)
    (if (nil? claim-epoch)
      {:seon.agent.run/claim-transition :acquire
       :seon.agent.run/claim-epoch 1
       :seon.db/tx-data
       (acquire-tx-data agent-id run-id process-id lease-until)}
      {:seon.agent.run/claim-transition :reacquire
       :seon.agent.run/claim-epoch (inc claim-epoch)
       :seon.db/tx-data
       (reacquire-tx-data agent-id run-id claim-epoch
                          process-id lease-until)})
    (= process process-id)
    {:seon.agent.run/claim-transition :held
     :seon.agent.run/claim-epoch claim-epoch
     :seon.db/tx-data
     (renew-tx-data agent-id run-id claim-epoch lease-until)}
    (expired-lease? run now)
    {:seon.agent.run/claim-transition :steal
     :seon.agent.run/claim-epoch (inc claim-epoch)
     :seon.db/tx-data
     (steal-tx-data agent-id run-id claim-epoch observed-lease
                    process-id lease-until)}
    :else nil))

(defn renew-tx-data
  "Renew the held lease under the pointer+epoch fence."
  [agent-id run-id claim-epoch lease-until]
  (conj
    (run-fence agent-id run-id claim-epoch)
    [:db/add [:seon.agent.run/id run-id]
     :seon.agent.run/lease-until lease-until]))

(defn release-tx-data
  "Release a held claim without retracting its monotonic epoch."
  [agent-id run-id claim-epoch]
  (conj (run-fence agent-id run-id claim-epoch)
        [:db/retract [:seon.agent.run/id run-id]
         :seon.agent.run/process]))

(defn finish-tx-data
  "Close one held run and detach it from the agent in the same transaction."
  [agent-id run-id claim-epoch reason closed-at]
  (into
   (run-fence agent-id run-id claim-epoch)
   [{:seon.agent.run/id run-id
     :seon.agent.run/status :closed
     :seon.agent.run/closed-reason reason
     :seon.agent.run/closed-at closed-at}
    [:db/retract [:seon.agent.run/id run-id]
     :seon.agent.run/process]
    [:db/retract [:seon.agent/id agent-id]
     :seon.agent/run]]))
