(ns n3-crash-verify
  "Phase 2 reboot: prove interrupted+adapt over the murdered JVM's facts.

  CORRECTED against the real contracts (2026-07-27), after probing the
  choreography end to end in
  `tmp/n3-crash-choreography-probe.clj`. The original guesses were
  wrong in two places and the truth is worth stating plainly:

  - `work/interruption` requires `:seon.cluster.run/process` to be
    ABSENT. The crashed run is claimed by the dead pid with a 60 s
    lease, so nothing surfaces it until custody is released;
  - what releases it is `seon.cluster.run/recover-tx`, and it does so
    BY FACT — the dead pid is not in the live-process set — NOT by
    waiting out the lease. The drill therefore needs no lease budget:
    recovery is event-driven and immediate. (Probed: recover-tx emits
    exactly two retractions, process and lease-until.)
  - settling the orphan is CLAIM-THEN-CLOSE. A direct close refuses
    `::not-the-holder`, because after recovery the run has no holder
    and `close-call`'s fence is exact. Claiming an unheld open run
    succeeds (epoch 1 → 2) and the new holder may then close it. No
    change to run.cljc is needed for this, so the drill does not
    depend on the lease-expiry hardening in flight;
  - `prompt/prompt` takes `[db {agent-id + message-id}]`.

  REWORKED AGAIN (2026-07-27, after the gaps landed). The drill now
  proves PRODUCTION WIRING rather than hand choreography:

  - boot recovery runs inside `cluster/start!`, so the wreckage is
    already settled-of-custody by the time this script can look. Phase
    A reads the INSTANCE's own recovery evidence;
  - the manual `recover-tx` phase is GONE — there is nothing left for
    the drill to do that production does not;
  - the LOOP settles the orphan, in its own pass, before deriving work.
    The drill arms the loop and watches it happen;
  - one direct-transition assertion survives, and only one: a survivor
    cannot close a run it does not hold. That fence is what makes the
    loop's claim-then-close the only way in, so it is worth pinning
    where a reader can see it."
  (:require [seon.cluster :as cluster]
            [seon.cluster.wake :as wake]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.run :as run]
            [seon.cluster.store :as store]
            [seon.cluster.work :as work]
            [seon.cluster.prompt :as prompt]
            [seon.config :as config]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [datahike.api :as d]))

(defn- stamp [& parts]
  (println (str "[verify " (.toString (java.time.LocalTime/now)) "] "
                (apply str parts)))
  (flush))

(defn- check! [label ok? evidence]
  (if ok?
    (stamp "OK   " label " → " (pr-str evidence))
    (do (stamp "FAIL " label " → " (pr-str evidence))
        (throw (ex-info label {::evidence evidence})))))

(def root "tmp/n3-crash/clusters")
(def started-at (System/nanoTime))
(def instance (cluster/start! {:seon.boot/cluster-name "live"
                               :seon.boot/root root}))
(def connection (:seon.boot/cluster-connection instance))
;; the SAME derivation boot recovery uses, so the run's holder and the
;; live set it is judged against are one value
(def process (cluster/process-identity (:seon.boot/advertisement instance)))
(stamp "rebooted as process " process)

(def outcome
  (try
    ;;; -----------------------------------------------------------------
    ;;; A. WHAT BOOT ALREADY DID — recovery is production, not drill
    ;;; -----------------------------------------------------------------
    (let [db @connection
          runs (d/q '[:find ?id ?holder :where
                      [?r :seon.cluster.run/id ?id]
                      [(get-else $ ?r :seon.cluster.run/process "-") ?holder]]
                    db)
          [crashed-id holder] (first runs)
          plans (d/q '[:find ?d :where [_ :seon.cluster.run/plan-digest ?d]] db)
          receipts (d/q '[:find ?o :where [?e :seon.cluster.eval/ordinal ?o]] db)]
      (check! "start! reported recovering the crashed run"
              (and (= 1 (:seon.boot/recovered-runs instance))
                   (pos? (:seon.boot/recovery-operations instance)))
              (select-keys instance [:seon.boot/recovered-runs
                                     :seon.boot/recovery-operations]))
      (check! "one run, and boot ALREADY released the dead holder"
              (and (= 1 (count runs)) (= "-" holder))
              {:runs runs :this-process process})
      (check! "it died mid-model-call: no plan, no receipts"
              (and (empty? plans) (empty? receipts))
              {:plans plans :receipts receipts})
      (check! "and it is still OPEN — recovery settles custody, nothing else"
              (nil? (d/q '[:find ?c . :where
                           [_ :seon.cluster.run/closed-at ?c]] db))
              :still-open)
      (check! "so the agent is BUSY and nothing is work — this is the
               wedge the loop must clear"
              (and (nil? (work/next-work db {:seon.cluster.run/process process
                                             :seon.cluster.work/now (java.util.Date.)}))
                   (= [crashed-id] (mapv :seon.cluster.run/id
                                         (work/interruptions db))))
              {:interruptions (work/interruptions db)})
      (check! "the crashed trigger reads ANSWERED — the run IS the answer"
              (empty? (work/unanswered-triggers db "alice"))
              :answered)

      ;;; ---------------------------------------------------------------
      ;;; B. THE ONE FENCE WORTH PINNING BY HAND
      ;;; ---------------------------------------------------------------
      (let [refused (store/transact!
                     connection
                     (run/close-tx
                      {:seon.cluster.run/id crashed-id
                       :seon.cluster.run/process process
                       :seon.cluster.run/claim-epoch 1
                       :seon.cluster.run/closed-at (java.util.Date.)
                       :seon.cluster.run/now (java.util.Date.)}))]
        (check! "a survivor cannot simply close a run it does not hold —
                 which is why the loop claims first"
                (= :seon.cluster.run/not-the-holder
                   (:seon.cluster.run/rule refused))
                refused))

      ;;; ---------------------------------------------------------------
      ;;; C. THE WARNING, before the loop touches anything
      ;;; ---------------------------------------------------------------
      (d/transact connection
                  [{:seon.cluster.message/id "m-after-crash"
                    :seon.cluster.message/to [:seon.cluster.agent/id "alice"]
                    :seon.cluster.message/content
                    "What were you asked before the interruption? Reply briefly and complete."
                    :seon.cluster.message/at (java.util.Date.)}])
      (check! "the interrupted warning is derivable from the crashed run"
              (str/includes?
               (str/lower-case
                (prompt/prompt @connection
                               {:seon.cluster.agent/id "alice"
                                :seon.cluster.message/id "m-after-crash"}))
               "interrupt")
              :derivable))

    ;;; -----------------------------------------------------------------
    ;;; E. THE SURVIVOR WORKS — a fresh trigger drives a NEW run to close
    ;;; -----------------------------------------------------------------
    (let [dials (config/effective @connection "live")
          wake-channel (async/chan (async/sliding-buffer 1))
          faults (async/chan (async/sliding-buffer 8))
          handle {:seon.store/branch-connection connection
                  :seon.cluster.run/process process
                  :seon.cluster.wake/channel wake-channel
                  :seon.cluster.loop/provider
                  {:seon.ai/endpoint "https://api.deepseek.com/chat/completions"
                   :seon.ai/model "deepseek-chat"
                   :seon.ai/api-key-variable "DEEPSEEK_API_KEY"
                   :seon.ai/timeout-ms 60000}
                  :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
                  :seon.sci.admit/caps
                  (select-keys dials [:seon.config.eval.result/max-depth
                                      :seon.config.eval.result/max-collection
                                      :seon.config.eval.result/max-string
                                      :seon.config.eval.result/max-nodes])
                  :seon.config.eval/time-limit-ms
                  (:seon.config.eval/time-limit-ms dials)
                  :seon.config/on-core-error
                  (:seon.config/on-core-error dials)}
          _ (wake/listen! {:seon.cluster.wake/connection connection
                           :seon.cluster.wake/attributes (wake/wake-attributes)
                           :seon.cluster.wake/channel wake-channel
                           :seon.cluster.wake/fault-channel faults
                           :seon.cluster.wake/key ::verify})
          graph (flow/create-flow
                 {:procs {::loop {:proc (flow/process #'cluster.loop/step
                                                      {:workload :io})
                                  :args handle}}
                  :conns []})]
      (try
        (flow/start graph)
        (flow/resume graph)
        (async/offer! wake-channel ::boot)
        (loop [n 0]
          (let [new-run (d/q '[:find ?id . :where
                               [?r :seon.cluster.run/id ?id]
                               [?r :seon.cluster.run/plan-digest _]
                               [?r :seon.cluster.run/closed-at _]]
                             @connection)]
            (cond
              new-run (check! "the LOOP settled the orphan and drove a NEW
                               run to completion — production wiring, not
                               hand choreography"
                              true {:run new-run})
              (>= n 240) (check! "a NEW run closed after reboot" false
                                 :timed-out)
              :else (do (Thread/sleep 500) (recur (inc n))))))
        (check! "the orphan is buried: no interruptions left, and the
                 crashed run is closed"
                (empty? (work/interruptions @connection))
                {:closed (d/q '[:find (count ?c) . :where
                                [_ :seon.cluster.run/closed-at ?c]]
                              @connection)})

        ;;; the crash-model claim, read straight off the facts
        (let [by-run (d/q '[:find ?run-id (count ?e) :where
                            [?e :seon.cluster.eval/run ?r]
                            [?r :seon.cluster.run/id ?run-id]]
                          @connection)
              crashed-id (d/q '[:find ?id . :where
                                [?r :seon.cluster.run/id ?id]
                                (not [?r :seon.cluster.run/plan-digest _])]
                              @connection)]
          (check! "receipts exist ONLY for the new run — the crashed run
                   has none, so nothing re-executed"
                  (and (= 1 (count by-run))
                       (not= crashed-id (ffirst by-run)))
                  {:receipts-by-run by-run :crashed-run crashed-id})
          (check! "and the crashed run stayed closed with no plan — no
                   model call was repeated for it"
                  (nil? (d/q '[:find ?d . :in $ ?id :where
                               [?r :seon.cluster.run/id ?id]
                               [?r :seon.cluster.run/plan-digest ?d]]
                             @connection crashed-id))
                  :never-planned))

        ;;; the warning the live agent actually read
        (check! "the warning was still in the prompt after the new run
                 opened — the shadowing is gone"
                (str/includes?
                 (str/lower-case
                  (prompt/prompt @connection
                                 {:seon.cluster.agent/id "alice"
                                  :seon.cluster.message/id "m-after-crash"}))
                 "interrupt")
                :present)
        :complete
        (finally
          (flow/stop graph)
          (wake/unlisten! {:seon.cluster.wake/connection connection
                           :seon.cluster.wake/key ::verify}))))
    (catch Throwable failure
      (stamp "VERIFY FAILED: " (ex-message failure))
      failure)
    (finally
      (cluster/stop! instance))))

(if (= :complete outcome)
  (do (stamp "PHASE 2 COMPLETE — interrupted+adapt proven over real facts")
      (System/exit 0))
  (System/exit 1))
