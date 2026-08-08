(ns ^{:seon.test/platform
       "Moving part: the interrupt arm travelling with the work."}
    seon.sci.kernel-arm-carriage-test
  "The arm travels with the work — one class regression.

  THE CLASS: work handed from an armed evaluation to another thread escapes
  the ONE limit. Probed and confirmed 2026-08-07 (Phase 0 probe B, graduated
  here): a detached interpreted loop under a 300 ms limit was still running
  at 1500 ms and could not be interrupted, and the governing arm recorded
  `:seon.eval/fn-entries 0` for twenty thousand interpreted entrances that
  happened on another thread — a diagnostic that reads `blocked in a host
  call` while the work in fact burns CPU somewhere else.

  What makes the class unrepresentable is that the arm is a VALUE rather than
  a property of a thread: `kernel/current-arm` hands it to whatever crosses
  and `kernel/adopt-arm` installs it where the work actually runs. These
  tests assert BEHAVIOUR at both halves — the deadline reaches the other
  thread, and the entrances that happened there are counted. They cannot go
  green vacuously: the detached loop is genuinely unbounded, so only the time
  limit can end it, and the test asserts the loop reported SCI's own
  interrupt rather than any flag the test could set."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel])
  (:import [java.util.concurrent Callable ExecutorService Executors Future
            TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; The crossing — exactly what a host function that hands work away must do
;;; ---------------------------------------------------------------------------

(defonce ^:private crossings
  (delay (Executors/newVirtualThreadPerTaskExecutor)))

(defn- cross
  "Hand `work` to a virtual thread CARRYING this thread's arm.

  This is the shape every real crossing takes: capture the arm as a value on
  the submitting thread, adopt it on the thread that runs the work.
  `seon.flow`'s submissions carry the same value under
  `:seon.sci.kernel/arm` in the submission's environment and the launcher
  adopts it identically."
  ^Future [work]
  (let [carried (kernel/current-arm)]
    (.submit ^ExecutorService @crossings
             ^Callable
             (fn []
               (kernel/adopt-arm
                carried
                (fn []
                  (try {::value (work)}
                       (catch Throwable failure
                         {::interrupted? (kernel/interrupted? failure)
                          ::message (ex-message failure)}))))))))

(def ^:private ticks (atom 0))
(def ^:private detached-work (atom nil))

(def ^:private probe-ns (sci/create-ns 'crossing))

(defn- crossing-ctx
  "A base ctx whose host surface can hand interpreted work to another thread."
  []
  (let [ctx (eval/build-base-ctx)]
    (sci/add-namespace!
     ctx 'crossing
     {'await (sci/new-var 'await
                          (fn [work] (.get (cross work)))
                          {:ns probe-ns})
      'detach (sci/new-var 'detach
                           (fn [work] (reset! detached-work (cross work))
                             :detached)
                           {:ns probe-ns})
      'tick (sci/new-var 'tick (fn [] (swap! ticks inc)) {:ns probe-ns})})
    ctx))

;; 20k interpreted loop/recur entrances, each of which sci reports to the
;; ctx's :interrupt-fn.
(def ^:private bounded
  '(fn [] (loop [i 0] (if (< i 20000) (recur (inc i)) i))))

;; Genuinely unbounded: nothing but the time limit ends this.
(def ^:private unbounded
  '(fn []
     (loop [i 0]
       (when (zero? (mod i 1000)) (crossing/tick))
       (recur (inc i)))))

(defn- armed
  "Arm `ctx`, run `body`, and return `[outcome diagnostic-record]`."
  [ctx time-limit-ms body]
  (let [arm (kernel/arm ctx time-limit-ms)
        outcome (try {::value (body)}
                     (catch Throwable failure
                       {::interrupted? (kernel/interrupted? failure)}))
        record ((:seon.sci.kernel/record arm) :ok)]
    ((:seon.sci.kernel/stop! arm))
    [outcome record]))

;;; ---------------------------------------------------------------------------
;;; The regression
;;; ---------------------------------------------------------------------------

(deftest entrances-on-another-thread-reach-the-governing-arm
  (let [ctx (crossing-ctx)]
    (testing "the arming thread's own entrances (baseline)"
      (let [[outcome record]
            (armed ctx 60000 #(sci/eval-form ctx (list bounded)))]
        (is (= 20000 (::value outcome)))
        (is (= :ok (:seon.eval/outcome record)))
        (is (<= 20000 (:seon.eval/fn-entries record)))))

    (testing "the identical workload run on a virtual thread"
      (let [[outcome record]
            (armed ctx 60000
                   #(sci/eval-form ctx (list 'crossing/await bounded)))]
        (is (= 20000 (get-in outcome [::value ::value]))
            "the work still returns its value across the crossing")
        (is (<= 20000 (:seon.eval/fn-entries record))
            (str "20k entrances on a virtual thread must reach the governing "
                 "arm; a 0 here is the lying diagnostic this regression "
                 "exists to kill"))))))

(deftest ^:long detached-work-is-cut-by-the-limit-that-admitted-it
  (let [ctx (crossing-ctx)
        time-limit-ms 300]
    (reset! ticks 0)
    (reset! detached-work nil)
    (let [started (System/nanoTime)
          [outcome _]
          (armed ctx time-limit-ms
                 #(sci/eval-form ctx (list 'crossing/detach unbounded)))
          ;; The arming evaluation has already disarmed here. Nothing but the
          ;; carried arm's own deadline latch can end the detached loop.
          settled (.get ^Future @detached-work 5 TimeUnit/SECONDS)
          elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
      (is (= :detached (::value outcome))
          "the parent evaluation returns immediately")
      (is (pos? @ticks) "the detached loop really ran")
      (is (true? (::interrupted? settled))
          (str "the detached loop must end by SCI's own interrupt; got "
               (pr-str settled)))
      (is (< elapsed-ms 2000)
          (str "detached work must end at its ~" time-limit-ms
               "ms limit, not run unbounded; elapsed " elapsed-ms "ms"))
      (let [before @ticks]
        (Thread/sleep 200)
        (is (= before @ticks) "and it is no longer running")))))

(deftest an-unarmed-crossing-runs-unchanged
  (is (= :plain (::value (.get ^Future (cross (fn [] :plain)))))
      "work that never came from an armed evaluation carries no arm"))

(deftest adoption-is-strictly-nested
  (let [outer-ctx (crossing-ctx)
        inner-ctx (crossing-ctx)]
    (armed
     outer-ctx 60000
     (fn []
       (let [outer (kernel/current-arm)
             inner (.get ^Future
                         (.submit ^ExecutorService @crossings
                                  ^Callable
                                  (fn []
                                    (armed inner-ctx 60000
                                           #(kernel/current-arm)))))
             displaced (::value (first inner))]
         (is (some? outer))
         (is (some? displaced))
         (is (not (identical? outer displaced))
             "a second evaluation on another thread owns its own arm")
         (kernel/adopt-arm
          displaced
          (fn []
            (is (identical? displaced (kernel/current-arm))
                "inside the extent this thread serves the carried arm")))
         (is (identical? outer (kernel/current-arm))
             "and the displaced arm is restored on the way out"))))))
