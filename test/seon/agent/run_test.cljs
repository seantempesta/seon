(ns seon.agent.run-test
  "Lifecycle tests for seon.agent.run on a FRESH :memory conn seeded like the
   pod boots (never the live agent conn). Covers open-run! (bounds + the
   fencing pointer + derived :running), the in-tx WORK FENCE (a superseded
   run's beat/renew is rejected AT COMMIT — the leading CAS aborts the tx,
   lands no datom; the current run's write commits), close-run!
   (retract-pointer-when-owned → derived :idle), renew! (bump both bounds,
   fenced), beat!, and seeding turn-limit from the agent default."
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
                                           {:seon.agent/id a-id}]})))
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

(defn ^:async supersede!
  "Open a fresh CURRENT run for agent A, leaving the prior run OPEN but no
   longer pointed-at (a 'superseded' run for the fencing tests). open-run! is
   now CAS-guarded on an ABSENT pointer — a plain second open while a run is
   pointed-at FAILS — so supersede = retract the pointer, then open. Returns
   the new run's snapshot."
  [trigger]
  (await (d/transact! db/*conn* {:tx-data [[:db/retract a-ref :seon.agent/run]]}))
  (await (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger trigger})))

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
                          snap2 (agent/derive-status {:seon.agent/id a-id})]
                      (is (= (:seon.agent.run/id snap) (:seon.agent.run/id cur))
                          "the agent's pointer resolves to this open run")
                      (is (= :running (:seon.agent/state snap2)) "derived state")
                      (is (= 0 (:seon.agent.run/turn snap2)) "no turns stamped yet")
                      (is (= 20 (:seon.agent.run/turns-remaining snap2)))
                      (is (contains? snap2 :seon.agent.run/ms-remaining))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest work-fence-rejects-a-superseded-runs-write-at-commit
  ;; The in-tx CAS IS the fence (no owns-run? predicate): a write from a
  ;; superseded run aborts the WHOLE tx at the writer and lands NO datom; the
  ;; current run's write commits.
  (async done
    (-> (with-conn
          (fn ^:async fence-test [_]
            (let [r1    (:seon.agent.run/id
                          (await (run/open-run! {:seon.agent/id a-id
                                                 :seon.agent.run/trigger :message})))
                  beat1 (await (run/beat! {:seon.agent/id a-id :seon.agent.run/id r1}))]
              (is (:seon.db/ok? beat1) "the owning run's beat commits")
              (let [hb1   (:seon.agent.run/last-beat-at
                            (db/entity {:seon.db/ref [:seon.agent.run/id r1]}))
                    r2    (:seon.agent.run/id (await (supersede! :schedule)))
                    ;; r1 no longer owns the agent — its beat's leading CAS
                    ;; fails, aborting the whole tx.
                    res-old (await (run/beat! {:seon.agent/id a-id :seon.agent.run/id r1}))]
                (is (inst? hb1) "the owning beat landed a heartbeat")
                (is (false? (:seon.db/ok? res-old))
                    "a superseded run's beat is REJECTED at commit (CAS abort)")
                (is (= hb1 (:seon.agent.run/last-beat-at
                             (db/entity {:seon.db/ref [:seon.agent.run/id r1]})))
                    "the rejected tx landed NO datom — r1's heartbeat is unchanged")
                (let [res-cur (await (run/beat! {:seon.agent/id a-id :seon.agent.run/id r2}))]
                  (is (:seon.db/ok? res-cur)
                      "the CURRENT run's beat commits — fencing rejects only the loser"))))))
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
                    (is (= :idle (:seon.agent/state (agent/derive-status {:seon.agent/id a-id})))
                        "derived state falls to idle")
                    (is (= :completed
                           (:seon.agent.run/closed-reason
                             (agent/derive-status {:seon.agent/id a-id})))
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
                              (supersede! :schedule)))
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
                              (supersede! :schedule)))
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

;; ── FIX 1: crash recovery — close runs orphaned by a prior pod crash ───────

(deftest recover-crashed-runs!-closes-orphaned-runs-and-is-idempotent
  (async done
    (let [!rid (atom nil)]
      (-> (with-conn
            (fn [_]
              (-> (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :message})
                  (.then (fn [snap]
                           (reset! !rid (:seon.agent.run/id snap))
                           ;; an open run with no loop driving it IS the
                           ;; post-crash state: derived :running, unwakeable.
                           (is (= :running (:seon.agent/state
                                             (agent/derive-status {:seon.agent/id a-id})))
                               "open run, no driver ⇒ derived :running (crash state)")
                           (run/recover-crashed-runs!)))
                  (.then (fn [res]
                           (is (= [@!rid] (:seon.agent.run/closed res))
                               "the orphaned run was recovered")
                           (let [r (db/entity {:seon.db/ref [:seon.agent.run/id @!rid]})]
                             (is (= :closed (:seon.agent.run/status r)))
                             (is (= :crashed (:seon.agent.run/closed-reason r))
                                 "closed with the boot-recovery reason"))
                           (is (nil? (run/current-run {:seon.agent/id a-id})) "pointer cleared")
                           (is (= :idle (:seon.agent/state
                                          (agent/derive-status {:seon.agent/id a-id})))
                               "the recovered agent is wakeable (:idle)")
                           (run/recover-crashed-runs!)))
                  (.then (fn [res2]
                           (is (= [] (:seon.agent.run/closed res2))
                               "idempotent — a clean second pass closes nothing"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ── FIX 2: atomic wake — two concurrent opens yield exactly ONE open run ───

(deftest concurrent-opens-yield-exactly-one-open-run
  (async done
    (-> (with-conn
          (fn [_]
            ;; Fire two opens WITHOUT awaiting the first: both read the agent
            ;; idle (no pointer), then the writer serializes their txs — the
            ;; second's CAS sees the first's pointer and its whole tx fails.
            (let [p1 (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :message})
                  p2 (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :schedule})]
              (-> (js/Promise.all #js [p1 p2])
                  (.then (fn [results]
                           (let [rs         [(aget results 0) (aget results 1)]
                                 ok-count   (count (remove #(false? (:seon.db/ok? %)) rs))
                                 fail-count (count (filter #(false? (:seon.db/ok? %)) rs))
                                 open-runs  (db/query {:seon.db/query
                                                       '[:find [?rid ...]
                                                         :where
                                                         [?r :seon.agent.run/status :open]
                                                         [?r :seon.agent.run/id ?rid]]})]
                             (is (= 1 ok-count) "exactly one open succeeded")
                             (is (= 1 fail-count) "the other LOST the CAS (error envelope)")
                             (is (= 1 (count open-runs))
                                 "exactly ONE :open run in the db — no duplicate")
                             (is (= :running (:seon.agent/state (agent/derive-status {:seon.agent/id a-id})))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ── close-run! TOCTOU — the owned retract is in-tx FENCED ───────────────────

(deftest close-run!-fence-protects-the-new-owners-pointer-in-the-toctou-window
  ;; close-run! reads owns? then, in ONE tx, conditionally retracts the agent's
  ;; :seon.agent/run pointer. A supersede landing in that read→commit window must
  ;; NOT retract the NEW owner's pointer. The owned retract-tx now LEADS with a
  ;; CAS asserting the pointer STILL names this run (run-fence); if it moved, the
  ;; whole tx aborts. This reproduces the window deterministically: build the
  ;; EXACT owned-branch tx close-run! commits for r1, supersede to r2 IN THE
  ;; WINDOW, THEN commit the stale tx — and assert r2's pointer survives. (Sans
  ;; the leading CAS, the bare [:db/retract] would yank r2's live pointer →
  ;; orphan it → wrongly idle the agent; this test discriminates the fix.)
  (async done
    (-> (with-conn
          (fn ^:async toctou [_]
            (let [r1 (:seon.agent.run/id
                       (await (run/open-run! {:seon.agent/id a-id
                                              :seon.agent.run/trigger :message})))
                  ;; the exact owned-branch tx close-run! builds for r1:
                  stale-close [(db/cas-assert a-ref :seon.agent/run [:seon.agent.run/id r1])
                               {:seon.agent.run/id            r1
                                :seon.agent.run/status        :closed
                                :seon.agent.run/closed-reason :completed}
                               [:db/retract a-ref :seon.agent/run]]
                  ;; ── the supersede lands IN THE WINDOW (r2 now owns the agent) ──
                  r2  (:seon.agent.run/id (await (supersede! :schedule)))
                  ;; now commit r1's stale, pre-built close-tx:
                  res (await (db/transact! {:seon.db/tx-data stale-close}))]
              (is (false? (:seon.db/ok? res))
                  "the fenced close aborts — the pointer no longer names r1")
              (is (= r2 (:seon.agent.run/id (run/current-run {:seon.agent/id a-id})))
                  "the NEW owner r2 still owns the agent — its pointer was NOT retracted")
              (is (= :running (:seon.agent/state (agent/derive-status {:seon.agent/id a-id})))
                  "the agent stays :running on r2 (never wrongly idled by the stale close)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
