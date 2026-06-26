(ns seon.agent.run-test
  "Lifecycle tests for seon.agent.run on a FRESH :memory conn seeded like the
   pod boots (never the live agent conn). Covers open-run! (bounds + the
   fencing pointer + derived :running), the fencing (owns-run?), close-run!
   (retract-pointer-when-owned → derived :idle), renew! (bump both bounds,
   fencing-guarded), beat!, and seeding turn-limit from the agent default."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.agent.run :as run]
    [seon.client :as client]
    [seon.db :as db]))

(def ^:private a-id "runtest-260625")   ; exactly 14 chars (:seon.db/id)
(def ^:private a-ref [:seon.agent/id a-id])

(def ^:private run-model-attrs
  "The run-model attrs the pod installs lazily — added to the test conn's
   boot schema alongside client/agent-bootstrap-attrs."
  [:seon.agent/run :seon.agent/terminated-at :seon.agent/default-turn-limit
   :seon.agent/default-deadline-ms :seon.agent/schedules
   :seon.agent.run/id :seon.agent.run/agent :seon.agent.run/started-at
   :seon.agent.run/trigger :seon.agent.run/cause :seon.agent.run/turn-limit
   :seon.agent.run/deadline :seon.agent.run/last-beat-at :seon.agent.run/paused-at
   :seon.agent.run/status :seon.agent.run/closed-reason :seon.agent.turn/run])

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + run-model
   schema + the user entity + an :idle agent A."
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         (into client/agent-bootstrap-attrs run-model-attrs))
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data [{:seon.user/id "user"}
                                           {:seon.agent/id a-id :seon.agent/state :idle}]})))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Fresh seeded conn `set!` as the ROOT db/*conn* for `body` (conn → Promise),
   prior root restored after. Root set!, not binding (CLJS dynamic bindings
   pop at the first await — see seon.agent.todo-test)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(deftest open-run!-opens-a-bounded-run-and-derives-running
  (async done
    (-> (with-conn
          (fn [_]
            (-> (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :message})
                (.then
                  (fn [snap]
                    (is (= :open (:seon.agent.run/status snap)))
                    (is (= :message (:seon.agent.run/trigger snap)))
                    (is (= 20 (:seon.agent.run/turn-limit snap)) "default turn-limit")
                    (is (inst? (:seon.agent.run/deadline snap)) "wall-clock bound set")
                    (let [cur  (run/current-run {:seon.agent/id a-id})
                          snap2 (agent/state-snapshot {:seon.agent/id a-id})]
                      (is (= (:seon.agent.run/id snap) (:seon.agent.run/id cur))
                          "the agent's pointer resolves to this open run")
                      (is (= :running (:seon.agent/state snap2)) "derived state")
                      (is (= 0 (:seon.agent.run/turn snap2)) "no turns stamped yet")
                      (is (= 20 (:seon.agent.run/turns-remaining snap2)))
                      (is (contains? snap2 :seon.agent.run/ms-remaining))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest owns-run?-fences-a-superseded-run
  (async done
    (-> (with-conn
          (fn [_]
            (-> (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :message})
                (.then
                  (fn [snap1]
                    (let [r1 (:seon.agent.run/id snap1)]
                      (is (true? (run/owns-run? {:seon.agent/id a-id :seon.agent.run/id r1})))
                      (-> (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :schedule})
                          (.then
                            (fn [snap2]
                              (let [r2 (:seon.agent.run/id snap2)]
                                (is (false? (run/owns-run? {:seon.agent/id a-id :seon.agent.run/id r1}))
                                    "the older run no longer owns the agent")
                                (is (true? (run/owns-run? {:seon.agent/id a-id :seon.agent.run/id r2}))
                                    "the newer run does")))))))))) )
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest close-run!-retracts-pointer-when-owned-deriving-idle
  (async done
    (-> (with-conn
          (fn [_]
            (-> (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :message})
                (.then
                  (fn [snap]
                    (run/close-run! {:seon.agent.run/id (:seon.agent.run/id snap)
                                     :seon.agent.run/closed-reason :completed})))
                (.then
                  (fn [res]
                    (is (:seon.db/ok? res))
                    (is (nil? (run/current-run {:seon.agent/id a-id})) "pointer retracted")
                    (is (= :idle (:seon.agent/state (agent/state-snapshot {:seon.agent/id a-id})))
                        "derived state falls to idle")
                    (is (= :completed
                           (:seon.agent.run/closed-reason
                             (agent/state-snapshot {:seon.agent/id a-id})))
                        "last closed-reason surfaces in the snapshot"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest renew!-bumps-both-bounds-and-fences
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :message})
                (.then
                  (fn [snap]
                    (let [rid    (:seon.agent.run/id snap)
                          dl-bef (.getTime (:seon.agent.run/deadline snap))]
                      (-> (run/renew! {:seon.agent/id a-id :seon.agent.run/id rid
                                       :seon.agent.run/deadline-extension-ms 1200000})
                          (.then
                            (fn [res]
                              (is (:seon.db/ok? res))
                              (let [r (db/entity @conn [:seon.agent.run/id rid])]
                                (is (= 21 (:seon.agent.run/turn-limit r)) "turn-limit +1")
                                (is (> (.getTime (:seon.agent.run/deadline r)) dl-bef)
                                    "deadline pushed out"))
                              ;; supersede, then renew the OLD run → fenced
                              (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :schedule})))
                          (.then
                            (fn [_]
                              (run/renew! {:seon.agent/id a-id :seon.agent.run/id rid})))
                          (.then
                            (fn [res]
                              (is (false? (:seon.db/ok? res))
                                  "a superseded run cannot renew (fencing)"))))))))) )
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest beat!-writes-a-heartbeat-and-fences
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :message})
                (.then
                  (fn [snap]
                    (let [rid (:seon.agent.run/id snap)]
                      (-> (run/beat! {:seon.agent/id a-id :seon.agent.run/id rid})
                          (.then
                            (fn [res]
                              (is (:seon.db/ok? res))
                              (is (inst? (:seon.agent.run/last-beat-at
                                           (db/entity @conn [:seon.agent.run/id rid])))
                                  "heartbeat stamped")
                              (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :schedule})))
                          (.then (fn [_] (run/beat! {:seon.agent/id a-id :seon.agent.run/id rid})))
                          (.then
                            (fn [res]
                              (is (false? (:seon.db/ok? res)) "superseded run cannot beat"))))))))) )
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest open-run!-seeds-turn-limit-from-agent-default
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (d/transact! conn {:tx-data [{:seon.agent/id a-id :seon.agent/default-turn-limit 3}]})
                (.then (fn [_] (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :message})))
                (.then (fn [snap]
                         (is (= 3 (:seon.agent.run/turn-limit snap))
                             "turn-limit seeded from :seon.agent/default-turn-limit"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
