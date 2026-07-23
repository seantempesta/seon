(ns seon.agent.lifecycle.core
  "Pure lifecycle authorization, transaction builders, and policy.")

(def managed-agent-selector
  '[:db/id :seon.agent/id :seon.agent/terminated-at
    {:seon.agent/parent ...}
    {:seon.agent/run [:seon.agent.run/id :seon.agent.run/status
                      :seon.agent.run/started-at :seon.agent.run/paused-at]}])

(defn error [message] {:seon.error/message message :seon.error/kind :user-input})

(defn no-agent-error [operation]
  (error (str operation ": no agent in scope — call inside (seon.db/with-agent …).")))

(defn manages? [caller-id target]
  (cond
    (nil? caller-id) false
    (nil? (:seon.agent/id target)) false
    (= "root" caller-id) true
    :else (loop [agent target seen #{}]
            (let [id (:seon.agent/id agent)]
              (cond
                (nil? id) false
                (= caller-id id) true
                (contains? seen id) false
                :else (recur (:seon.agent/parent agent) (conj seen id)))))))

(defn unauthorized [operation caller-id target-id]
  (error (str operation ": agent " (pr-str caller-id) " cannot manage "
              (pr-str target-id) "; root manages the cluster and ordinary agents "
              "manage only themselves and their descendants.")))

(defn run-fence [agent-id run-id]
  [:db.fn/cas [:seon.agent/id agent-id] :seon.agent/run
   [:seon.agent.run/id run-id] [:seon.agent.run/id run-id]])

(defn close-tx-data [agent-id run-id reason closed-at]
  [(run-fence agent-id run-id)
   {:seon.agent.run/id run-id
    :seon.agent.run/status :closed
    :seon.agent.run/closed-reason reason
    :seon.agent.run/closed-at closed-at}
   [:db/retract [:seon.agent/id agent-id] :seon.agent/run]])

(defn pause-tx-data [agent-id run-id deadline now]
  (let [remaining-ms (max 0 (- (inst-ms deadline) (inst-ms now)))]
    [(run-fence agent-id run-id)
     [:db.fn/cas [:seon.agent.run/id run-id] :seon.agent.run/deadline deadline deadline]
     [:db.fn/cas [:seon.agent.run/id run-id] :seon.agent.run/paused-at nil now]
     [:db/add [:seon.agent.run/id run-id] :seon.agent.run/remaining-ms remaining-ms]]))

(defn resume-tx-data [agent-id run-id paused-at remaining-ms now]
  (let [deadline #?(:clj (java.util.Date. (+ (inst-ms now) remaining-ms))
                    :cljs (js/Date. (+ (inst-ms now) remaining-ms)))]
    [(run-fence agent-id run-id)
     [:db.fn/cas [:seon.agent.run/id run-id] :seon.agent.run/paused-at paused-at paused-at]
     [:db.fn/cas [:seon.agent.run/id run-id] :seon.agent.run/remaining-ms remaining-ms remaining-ms]
     [:db/add [:seon.agent.run/id run-id] :seon.agent.run/deadline deadline]
     [:db/retract [:seon.agent.run/id run-id] :seon.agent.run/paused-at]
     [:db/retract [:seon.agent.run/id run-id] :seon.agent.run/remaining-ms]]))

