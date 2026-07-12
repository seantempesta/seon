(ns seon.agent.multiagent-test
  "Multi-agent-context unit (multiagent-context-spec) Pieces 1/2/2b/2c/2d on a
   FRESH :memory conn seeded like the pod boots. Time is INJECTED everywhere
   (explicit now, backdated :inst datoms) — zero timers, zero sleeps.
   Deliberate :core faults (the watchdog) ride the async-safe
   error/expecting-core-fault! bracket so the gate stays green."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.agent.lifecycle :as life]
    [seon.agent.message :as msg]
    [seon.agent.run :as run]
    [seon.client :as client]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.derive :as derive]
    [seon.error :as error]))

(def ^:private root-id "root")
(def ^:dynamic ^:private parent-id nil)
(def ^:dynamic ^:private child-id nil)
(def ^:dynamic ^:private gchild-id nil)

(defn- fresh-conn []
  (-> (client/open-agent-conn!)
      (.then
        (fn [conn]
          (-> (db.id/allocate!
                {::db.id/allocations
                 [{::db.id/key ::parent
                   ::db.id/identity-attr :seon.agent/id}
                  {::db.id/key ::child
                   ::db.id/identity-attr :seon.agent/id}]
                 ::db.id/transaction-builder
                 (fn [ids]
                   {:seon.db/tx-data
                    [{:db/id "fixture-parent"
                      :seon.agent/id (::parent ids)}
                     {:seon.agent/id (::child ids)
                      :seon.agent/parent "fixture-parent"}]})
                 :seon.db/conn conn})
              (.then
                (fn [env]
                  (when-not (:seon.db/ok? env)
                    (throw (ex-info "multiagent fixture allocation failed" env)))
                  (set! parent-id (get-in env [::db.id/ids ::parent]))
                  (set! child-id (get-in env [::db.id/ids ::child]))
                  conn)))))))

(defn- allocate-row!
  "Allocate one governed identity and commit its fixture row."
  [conn allocation-key identity-attr row]
  (db.id/allocate!
    {::db.id/allocations
     [{::db.id/key allocation-key
       ::db.id/identity-attr identity-attr}]
     ::db.id/transaction-builder
     (fn [ids]
       {:seon.db/tx-data
        [(assoc row identity-attr (get ids allocation-key))]})
     :seon.db/conn conn}))

(defn- with-conn [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- eid [id] (:db/id (db/entity {:seon.db/ref [:seon.agent/id id]})))

(defn- msg-count [from-id to-id]
  (or (db/query {:seon.db/query
                 '[:find (count ?m) . :in $ ?f ?t
                   :where
                   [?fe :seon.agent/id ?f]
                   [?te :seon.agent/id ?t]
                   [?m :seon.agent.message/from ?fe]
                   [?m :seon.agent.message/to ?te]]
                 :seon.db/args [from-id to-id]})
      0))

(defn- open-child! []
  (run/open-run! {:seon.agent/id child-id :seon.agent.run/trigger :message}))

(deftest complete-writes-result-and-closes-completed
  (async done
    (-> (with-conn
          (fn ^:async af [_]
            (let [snap (await (open-child!))
                  rid  (:seon.agent.run/id snap)]
              (await (db/with-agent child-id
                       (fn ^:async af [] (await (life/complete "the answer is 42")))))
              (let [r (db/entity {:seon.db/ref [:seon.agent.run/id rid]})]
                (is (= "the answer is 42" (:seon.agent.run/result r)) "result datom written")
                (is (= :completed (:seon.agent.run/closed-reason r)))
                (is (inst? (:seon.agent.run/closed-at r)) "closed-at stamped")
                (is (nil? (run/current-run {:seon.agent/id child-id})) "pointer retracted")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest complete-result-datom-unconditional-past-message-skip-guard
  (async done
    (-> (with-conn
          (fn ^:async af [_]
            (let [snap (await (open-child!))
                  rid  (:seon.agent.run/id snap)]
              (await (db/with-agent child-id
                       (fn ^:async af []
                         (await (msg/agent parent-id "interim update"))
                         (await (life/complete "final answer")))))
              (is (= 1 (msg-count child-id parent-id))
                  "exactly one child->parent message")
              (let [r (db/entity {:seon.db/ref [:seon.agent.run/id rid]})]
                (is (= "final answer" (:seon.agent.run/result r))
                    "result datom written UNCONDITIONALLY past the skip guard")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest complete-result-ref-round-trips
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (await (d/transact! conn {:tx-data [{:my.kb.shared/id "kb-note-x"}]}))
            (let [note-eid (:db/id (db/entity {:seon.db/ref [:my.kb.shared/id "kb-note-x"]}))
                  snap     (await (open-child!))
                  rid      (:seon.agent.run/id snap)]
              (await (db/with-agent child-id
                       (fn ^:async af [] (await (life/complete "see note" note-eid)))))
              (let [r (db/entity {:seon.db/ref [:seon.agent.run/id rid]})]
                (is (= note-eid (:db/id (:seon.agent.run/result-ref r)))
                    "result-ref resolves to the seeded entity")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest complete-blank-result-writes-no-datom-and-sends-nothing
  (async done
    (-> (with-conn
          (fn ^:async af [_]
            (let [snap (await (open-child!))
                  rid  (:seon.agent.run/id snap)]
              (await (db/with-agent child-id (fn ^:async af [] (await (life/complete "")))))
              (let [r (db/entity {:seon.db/ref [:seon.agent.run/id rid]})]
                (is (not (contains? r :seon.agent.run/result)) "no result key")
                (is (= :completed (:seon.agent.run/closed-reason r)))
                (is (zero? (msg-count child-id parent-id)) "blank result delivers nothing")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest spawn-depth-counts-parent-chain
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (let [env (await
                        (allocate-row!
                          conn ::gchild :seon.agent/id
                          {:seon.agent/parent [:seon.agent/id child-id]}))]
              (set! gchild-id (get-in env [::db.id/ids ::gchild])))
            (let [db @db/*conn*]
              (is (= 0 (agent/spawn-depth db root-id)) "root = 0")
              (is (= 0 (agent/spawn-depth db parent-id)) "parentless = 0")
              (is (= 1 (agent/spawn-depth db child-id)) "child = 1")
              (is (= 2 (agent/spawn-depth db gchild-id)) "grandchild = 2"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest spawn-depth-cycle-guard-returns-a-value
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (await (d/transact! conn {:tx-data [{:seon.agent/id parent-id
                                                 :seon.agent/parent [:seon.agent/id child-id]}]}))
            (await
              (error/expecting-core-fault!
                (fn ^:async af []
                  (let [d (agent/spawn-depth @db/*conn* parent-id)]
                    (is (int? d) "cycle-guarded: returns a value"))
                  (js/Promise.resolve nil))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest start!-refuses-a-depth-1-caller-datom-free
  (async done
    (-> (with-conn
          (fn ^:async af [_]
            (let [before (count (db/query {:seon.db/query '[:find [?id ...] :where [?a :seon.agent/id ?id]]}))
                  res    (await (db/with-agent child-id
                                  (fn ^:async af [] (await (agent/start! {:seon.agent/purpose "nope"})))))
                  after  (count (db/query {:seon.db/query '[:find [?id ...] :where [?a :seon.agent/id ?id]]}))]
              (is (false? (:seon.db/ok? res)) "refused as an error envelope")
              (is (re-find #"depth" (:seon.error/message (:seon.db/error res))) "message names depth")
              (is (= before after) "no agent entity created"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest start!-depth-0-caller-still-spawns
  (async done
    (-> (with-conn
          (fn ^:async af [_]
            (let [res (await (db/with-agent parent-id
                               (fn ^:async af []
                                 (await (agent/start!
                                          {:seon.agent/purpose "ok"})))))
                  spawned-id (:seon.agent/id res)
                  spawned (db/entity
                            {:seon.db/ref [:seon.agent/id spawned-id]})]
              (is (re-matches #"^[a-z0-9]+-[a-z0-9]+-[a-z0-9]+$" spawned-id)
                  "a depth-0 caller gets a readable generated id")
              (is (= parent-id
                     (:seon.agent/id (:seon.agent/parent spawned)))
                  "the allocating transaction also records the parent ref"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(defn- close-child-as! [reason]
  (fn ^:async af [_]
    (let [snap (await (open-child!))
          rid  (:seon.agent.run/id snap)]
      (await (run/close-run! {:seon.agent.run/id rid :seon.agent.run/closed-reason reason}))
      rid)))

(deftest outcome-turn-limit-messages-parent-once-with-affordance
  (async done
    (-> (with-conn
          (fn ^:async af [_]
            (await ((close-child-as! :turn-limit) nil))
            (is (= 1 (msg-count child-id parent-id)) "exactly one parent message")
            (let [content (db/query {:seon.db/query
                                     '[:find ?c . :in $ ?f
                                       :where [?fe :seon.agent/id ?f]
                                       [?m :seon.agent.message/from ?fe]
                                       [?m :seon.agent.message/content ?c]]
                                     :seon.db/args [child-id]})]
              (is (re-find #"turn-limit" content) "content carries the reason")
              (is (re-find #"fresh run" content) "content carries the continue affordance")
              (is (re-find (re-pattern child-id) content) "content names the child"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest outcome-notice-origin-is-agent-and-wakes-the-parent
  (async done
    (-> (with-conn
          (fn ^:async af [_]
            (await ((close-child-as! :error) nil))
            (let [meid (db/query {:seon.db/query
                                  '[:find ?m . :in $ ?f
                                    :where [?fe :seon.agent/id ?f]
                                    [?m :seon.agent.message/from ?fe]]
                                  :seon.db/args [child-id]})
                  notice (db/entity meid)]
              (is (= :agent (:seon.agent.message/origin notice)) "origin :agent not :core")
              (is (msg/waking-inbound? notice (eid parent-id)) "passes the real wake gate"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest outcome-nonoutcome-closes-send-nothing
  (async done
    (-> (with-conn
          (fn ^:async af [_]
            (await ((close-child-as! :waited) nil))
            (await ((close-child-as! :terminated) nil))
            (await ((close-child-as! :superseded) nil))
            (is (zero? (msg-count child-id parent-id)) "non-outcome closes send zero messages")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest outcome-crashed-nonroot-parent-messages-parent-and-root
  (async done
    (-> (with-conn
          (fn ^:async af [_]
            (await ((close-child-as! :crashed) nil))
            (is (= 1 (msg-count child-id parent-id)) "one to the parent")
            (is (= 1 (msg-count child-id root-id)) "one escalation to root")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest outcome-crashed-root-parent-dedups-to-one
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (await (d/transact! conn {:tx-data [{:seon.agent/id child-id
                                                 :seon.agent/parent [:seon.agent/id root-id]}]}))
            (await ((close-child-as! :crashed) nil))
            (is (= 1 (msg-count child-id root-id)) "parent IS root -> exactly one message")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest outcome-deadline-rides-real-close-overdue-path
  (async done
    (-> (with-conn
          (fn ^:async af [_]
            (let [snap (await (open-child!))]
              (await (run/close-overdue-runs! {:seon.agent/now (js/Date. (+ (.getTime (js/Date.)) 3600000))}))
              (is (= :deadline-exceeded
                     (:seon.agent.run/closed-reason
                       (db/entity {:seon.db/ref [:seon.agent.run/id (:seon.agent.run/id snap)]}))))
              (is (= 1 (msg-count child-id parent-id)) "the deadline close routes ONE notice"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(defn- backdate-beat! [conn rid beat-inst]
  (d/transact! conn {:tx-data [{:seon.agent.run/id rid
                                :seon.agent.run/last-beat-at beat-inst}]}))

(deftest watchdog-stale-run-boundary-pair
  (async done
    (let [stale-ms 60000
          now      (js/Date. 5000000)]
      (-> (with-conn
            (fn ^:async af [conn]
              (let [snap (await (open-child!))
                    rid  (:seon.agent.run/id snap)]
                (await (backdate-beat! conn rid (js/Date. (- (.getTime now) stale-ms -1000))))
                (is (= [] (run/stale-run-ids @db/*conn* now stale-ms)) "fresh beat untouched")
                (await (backdate-beat! conn rid (js/Date. (- (.getTime now) stale-ms 1000))))
                (is (= [rid] (run/stale-run-ids @db/*conn* now stale-ms)) "stale beat flagged"))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw " e)) (done)))))))

(deftest watchdog-no-beat-falls-back-to-started-at
  (async done
    (let [stale-ms 60000
          ;; now well AFTER the run's real started-at (which open-run! stamps
          ;; ~real-now) so the never-beaten run is stale by its started-at.
          now      (js/Date. (+ (.getTime (js/Date.)) (* 2 stale-ms)))]
      (-> (with-conn
            (fn ^:async af [_]
              (let [snap (await (open-child!))
                    rid  (:seon.agent.run/id snap)]
                (is (= [rid] (run/stale-run-ids @db/*conn* now stale-ms))
                    "no-beat run is stale by started-at"))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw " e)) (done)))))))

(deftest watchdog-skips-paused-run
  (async done
    (let [stale-ms 60000
          now      (js/Date. (+ (.getTime (js/Date.)) 3600000))]
      (-> (with-conn
            (fn ^:async af [_]
              (let [snap (await (open-child!))
                    rid  (:seon.agent.run/id snap)]
                (await (run/pause! {:seon.agent/id child-id :seon.agent.run/id rid}))
                (is (= [] (run/stale-run-ids @db/*conn* now stale-ms)) "paused run skipped"))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw " e)) (done)))))))

(deftest watchdog-close-stale-crashes-notifies-and-records-fault
  (async done
    (let [stale-ms 60000
          now      (js/Date. (+ (.getTime (js/Date.)) 3600000))]
      (-> (with-conn
            (fn ^:async af [_]
              (let [snap (await (open-child!))
                    rid  (:seon.agent.run/id snap)]
                (await
                  (error/expecting-core-fault!
                    (fn ^:async af []
                      (await (run/close-stale-runs! {:seon.agent/now now
                                                     :seon.agent.run/stale-ms stale-ms})))))
                (let [r (db/entity {:seon.db/ref [:seon.agent.run/id rid]})]
                  (is (= :crashed (:seon.agent.run/closed-reason r)) "closed :crashed")
                  (is (nil? (run/current-run {:seon.agent/id child-id})) "pointer retracted"))
                (is (= 1 (msg-count child-id parent-id)) "parent got the wedge notice")
                (is (= 1 (msg-count child-id root-id)) "root got the escalation")
                (let [again (await (run/close-stale-runs! {:seon.agent/now now
                                                           :seon.agent.run/stale-ms stale-ms}))]
                  (is (= [] (:seon.agent.run/closed again)) "second scan closes nothing")))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw " e)) (done)))))))

(deftest watchdog-late-beat-is-fenced-noop
  (async done
    (let [stale-ms 60000
          now      (js/Date. (+ (.getTime (js/Date.)) 3600000))]
      (-> (with-conn
            (fn ^:async af [_]
              (let [snap (await (open-child!))
                    rid  (:seon.agent.run/id snap)]
                (await
                  (error/expecting-core-fault!
                    (fn ^:async af []
                      (await (run/close-stale-runs! {:seon.agent/now now
                                                     :seon.agent.run/stale-ms stale-ms})))))
                (let [beat (await (run/beat! {:seon.agent/id child-id :seon.agent.run/id rid}))]
                  (is (false? (:seon.db/ok? beat)) "late beat on a closed run is fenced")))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw " e)) (done)))))))

(defn- seed-crash! [conn agent-id rid-suffix closed-at]
  (allocate-row!
    conn (keyword "seon.agent.multiagent-test" (str "crash-" rid-suffix))
    :seon.agent.run/id
    {:seon.agent.run/agent         [:seon.agent/id agent-id]
     :seon.agent.run/started-at    closed-at
     :seon.agent.run/trigger       :schedule
     :seon.agent.run/turn-limit    20
     :seon.agent.run/deadline      closed-at
     :seon.agent.run/status        :closed
     :seon.agent.run/closed-reason :crashed
     :seon.agent.run/closed-at     closed-at}))

(deftest breaker-trips-at-N-in-window
  (async done
    (let [now    (js/Date. 5000000000)
          n      3
          window 1800000]
      (-> (with-conn
            (fn ^:async af [conn]
              (dotimes [i n]
                (await (seed-crash! conn child-id (str "in" i)
                                    (js/Date. (- (.getTime now) (* (inc i) 60000))))))
              (is (= n (derive/recent-crash-count @db/*conn* child-id
                                                  (js/Date. (- (.getTime now) window)))))
              (is (true? (derive/schedule-breaker-tripped? @db/*conn* child-id now n window))
                  "N in-window crashes trips the breaker")
              (is (false? (derive/schedule-breaker-tripped? @db/*conn* child-id now (inc n) window))
                  "dial is a real parameter")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw " e)) (done)))))))

(deftest breaker-does-not-trip-below-N-or-outside-window
  (async done
    (let [now    (js/Date. 5000000000)
          n      3
          window 1800000]
      (-> (with-conn
            (fn ^:async af [conn]
              (dotimes [i (dec n)]
                (await (seed-crash! conn child-id (str "in" i)
                                    (js/Date. (- (.getTime now) (* (inc i) 60000))))))
              (await (seed-crash! conn child-id "old" (js/Date. (- (.getTime now) window 60000))))
              (is (false? (derive/schedule-breaker-tripped? @db/*conn* child-id now n window))
                  "N-1 in-window + 1 out-of-window does NOT trip")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw " e)) (done)))))))

(deftest breaker-only-crashed-closes-count
  (async done
    (let [now    (js/Date. 5000000000)
          window 1800000]
      (-> (with-conn
            (fn ^:async af [conn]
              (await
                (allocate-row!
                  conn ::completed-run :seon.agent.run/id
                  {:seon.agent.run/agent [:seon.agent/id child-id]
                   :seon.agent.run/started-at now :seon.agent.run/trigger :message
                   :seon.agent.run/turn-limit 20 :seon.agent.run/deadline now
                   :seon.agent.run/status :closed :seon.agent.run/closed-reason :completed
                   :seon.agent.run/closed-at (js/Date. (- (.getTime now) 60000))}))
              (is (= 0 (derive/recent-crash-count @db/*conn* child-id
                                                  (js/Date. (- (.getTime now) window))))
                  "only :crashed closes count")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw " e)) (done)))))))
