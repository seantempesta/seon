(ns env-probes.probe-b-interrupt-arm
  "Probe B — does work handed to another thread from inside an armed eval run
  ARMED?

  The sealed seon.env PRD lists this as the top unprobed hypothesis: \"The
  `:interrupt-fn` arm is a ThreadLocal on the process guard; work handed
  across a thread by agent code plausibly runs unarmed.\"

  The mechanism under test, from source:

  - `seon.sci.kernel/new-guard` builds ONE process guard whose arm state is a
    plain `(ThreadLocal.)` (src/seon/sci/kernel.clj:46-52) — not an
    InheritableThreadLocal, and virtual threads do not inherit it either.
  - the guard's `interrupt-fn` no-ops unless `(.get thread-arm)` is non-nil
    (src/seon/sci/kernel.clj:49-50); the fn-entry counter, the deadline check
    and `sci.interrupt/interrupt!` all sit INSIDE that `when-let`.
  - `seon.sci.kernel/arm` (src/seon/sci/kernel.clj:190-222) sets that
    ThreadLocal on the calling thread only, and `own-arm`
    (src/seon/sci/kernel.clj:218-249) schedules the one deadline task.
  - sci calls the `:interrupt-fn` on every interpreted fn-body and loop/recur
    entrance (reference-code/sci/doc/interrupt.md:6-9), lifting it off the ctx
    captured at fn creation (reference-code/sci/src/sci/impl/fns.cljc:40) — so
    the FUNCTION crosses the thread with the code; only the ARM does not.

  Four arms:

  B1 armed baseline on the arming thread — fn entries advance.
  B2 the same workload awaited on a virtual thread — do entries advance?
  B3 an unbounded interpreted loop handed to a virtual thread under a short
     time limit — does the limit terminate it?
  B4 control: the same unbounded loop on the arming thread — the limit fires.

  Load-only; no cluster, no database. See tmp/env-probes/RUN.md."
  (:require [clojure.pprint :as pprint]
            [sci.core :as sci]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as kernel])
  (:import [java.util.concurrent Callable ExecutorService Executors Future]))

(def ^:private probe-ns (sci/create-ns 'probe))

(def ^:private ticks (atom 0))
(def ^:private keep-going (atom true))
(def ^:private last-tick-thread (atom nil))

(defn- thread-facts
  []
  (let [t (Thread/currentThread)]
    {:probe/thread-id (.threadId t) :probe/virtual? (.isVirtual t)}))

(defn- tick
  "Called from inside the interpreted loop so the host can watch progress."
  []
  (reset! last-tick-thread (thread-facts))
  (swap! ticks inc))

(defn- keep-going?
  []
  @keep-going)

(defonce ^:private executor
  (Executors/newVirtualThreadPerTaskExecutor))

(defn- await-virtual
  "Run `f` on a virtual thread and WAIT for it."
  [f]
  (.get ^Future (.submit ^ExecutorService executor
                         ^Callable (fn [] {:probe/thread (thread-facts)
                                           :probe/value (f)}))))

(def ^:private detached (atom nil))

(defn- spawn-detached
  "Hand `f` to a virtual thread and return immediately."
  [f]
  (reset! detached
          (.submit ^ExecutorService executor
                   ^Callable (fn []
                               (try {:probe/thread (thread-facts)
                                     :probe/value (f)}
                                    (catch Throwable failure
                                      {:probe/thread (thread-facts)
                                       :probe/interrupted?
                                       (kernel/interrupted? failure)
                                       :probe/error
                                       (str (class failure) ": "
                                            (ex-message failure))})))))
  :spawned)

(defn probe-ctx
  "A base ctx with the process guard plus the host thread-crossing helpers."
  []
  (let [ctx (sci.eval/build-base-ctx)]
    (sci/add-namespace!
     ctx 'probe
     {'await-virtual (sci/new-var 'await-virtual await-virtual {:ns probe-ns})
      'spawn-detached (sci/new-var 'spawn-detached spawn-detached
                                   {:ns probe-ns})
      'tick (sci/new-var 'tick tick {:ns probe-ns})
      'keep-going? (sci/new-var 'keep-going? keep-going? {:ns probe-ns})
      'thread (sci/new-var 'thread thread-facts {:ns probe-ns})})
    ctx))

;; A bounded interpreted workload: 20k loop/recur entrances, each of which
;; sci reports to the ctx's :interrupt-fn.
(def bounded-workload
  '(fn [] (loop [i 0] (if (< i 20000) (recur (inc i)) i))))

;; An unbounded interpreted loop that only the host can stop, so a failed
;; time limit cannot wedge the probe.
(def unbounded-workload
  '(fn []
     (loop [i 0]
       (if (and (probe/keep-going?) (< i 200000000))
         (do (when (zero? (mod i 1000)) (probe/tick))
             (recur (inc i)))
         i))))

(defn- armed
  "Arm `ctx`, run `f` with the arm, disarm, and return [value record]."
  [ctx time-limit-ms f]
  (let [arm (kernel/arm ctx time-limit-ms)
        outcome (try {:probe/value (f)}
                     (catch Throwable failure
                       {:probe/interrupted? (kernel/interrupted? failure)
                        :probe/error (str (class failure) ": "
                                          (ex-message failure))}))
        record ((:seon.sci.kernel/record arm) :ok)]
    ((:seon.sci.kernel/stop! arm))
    [outcome record]))

(defn- b1-baseline
  [ctx]
  (let [[outcome record]
        (armed ctx 60000
               (fn [] (sci/eval-form ctx (list bounded-workload))))]
    {:probe/arm :b1-armed-same-thread
     :probe/fn-entries (:seon.eval/fn-entries record)
     :probe/outcome (:seon.eval/outcome record)
     :probe/value (:probe/value outcome)
     :probe/error (:probe/error outcome)}))

(defn- b2-awaited-virtual
  [ctx]
  (let [[outcome record]
        (armed ctx 60000
               (fn [] (sci/eval-form
                       ctx (list 'probe/await-virtual bounded-workload))))]
    {:probe/arm :b2-armed-work-on-virtual-thread
     :probe/fn-entries (:seon.eval/fn-entries record)
     :probe/outcome (:seon.eval/outcome record)
     :probe/ran-on (get-in outcome [:probe/value :probe/thread])
     :probe/value (get-in outcome [:probe/value :probe/value])
     :probe/error (:probe/error outcome)}))

(defn- b3-detached-under-limit
  [ctx time-limit-ms observe-ms]
  (reset! ticks 0)
  (reset! keep-going true)
  (reset! last-tick-thread nil)
  (let [[outcome record]
        (armed ctx time-limit-ms
               (fn [] (sci/eval-form
                       ctx (list 'probe/spawn-detached unbounded-workload))))
        _ (Thread/sleep (long (quot observe-ms 2)))
        ticks-at-half @ticks
        _ (Thread/sleep (long (quot observe-ms 2)))
        ticks-after @ticks
        still-running? (not (.isDone ^Future @detached))
        _ (reset! keep-going false)
        spawned (try (.get ^Future @detached)
                     (catch Throwable failure
                       {:probe/error (str (class failure) ": "
                                          (ex-message failure))}))]
    {:probe/arm :b3-detached-virtual-thread-under-time-limit
     :probe/time-limit-ms time-limit-ms
     :probe/observed-ms observe-ms
     :probe/parent-eval outcome
     :probe/parent-fn-entries (:seon.eval/fn-entries record)
     :probe/parent-outcome (:seon.eval/outcome record)
     :probe/ticks-at-half ticks-at-half
     :probe/ticks-after ticks-after
     :probe/still-running-after-deadline? still-running?
     :probe/spawned-thread (or (:probe/thread spawned) @last-tick-thread)
     :probe/spawned-interrupted? (:probe/interrupted? spawned)
     :probe/spawned-error (:probe/error spawned)}))

(defn- b4-control-same-thread
  [ctx time-limit-ms]
  (reset! ticks 0)
  (reset! keep-going true)
  (let [started (System/nanoTime)
        [outcome record]
        (armed ctx time-limit-ms
               (fn [] (sci/eval-form ctx (list unbounded-workload))))
        elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
    (reset! keep-going false)
    {:probe/arm :b4-control-unbounded-loop-on-arming-thread
     :probe/time-limit-ms time-limit-ms
     :probe/elapsed-ms elapsed-ms
     :probe/fn-entries (:seon.eval/fn-entries record)
     :probe/outcome (:seon.eval/outcome record)
     :probe/interrupted? (:probe/interrupted? outcome)
     :probe/error (:probe/error outcome)}))

(defn run
  "Run probe B. Returns a verdict value; no test framework."
  ([] (run {:probe/time-limit-ms 300 :probe/observe-ms 1500}))
  ([{time-limit-ms :probe/time-limit-ms observe-ms :probe/observe-ms}]
   (let [ctx (probe-ctx)
         b1 (b1-baseline ctx)
         b2 (b2-awaited-virtual ctx)
         b3 (b3-detached-under-limit ctx time-limit-ms observe-ms)
         b4 (b4-control-same-thread ctx time-limit-ms)
         entries-cross-thread (:probe/fn-entries b2)
         entries-same-thread (:probe/fn-entries b1)
         unarmed? (and (pos? entries-same-thread)
                       (< entries-cross-thread
                          (quot entries-same-thread 100))
                       (:probe/still-running-after-deadline? b3)
                       (not (:probe/spawned-interrupted? b3)))
         control-ok? (and (:probe/interrupted? b4)
                          (= :time (:probe/outcome b4)))]
     {:probe/name "B — interrupt arm across a thread hop"
      :probe/verdict (cond
                       (not control-ok?) :inconclusive
                       unarmed? :confirmed-unarmed
                       :else :armed)
      :probe/hypothesis
      "work handed across a thread from inside an armed eval runs UNARMED"
      :probe/arms [b1 b2 b3 b4]
      :probe/summary
      {:probe/fn-entries-same-thread entries-same-thread
       :probe/fn-entries-cross-thread entries-cross-thread
       :probe/detached-survived-deadline?
       (:probe/still-running-after-deadline? b3)
       :probe/detached-interrupted? (boolean (:probe/spawned-interrupted? b3))
       :probe/control-interrupted-on-arming-thread? control-ok?}})))

(defn -main [& _]
  (pprint/pprint (run)))
