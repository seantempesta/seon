(ns spike.core
  "DECISIVE empirical spike: can SCI + a wall-clock :interrupt-fn abort a
   TRULY synchronous infinite loop IN-PROCESS on Node/CLJS?

   All eval-string calls below are synchronous host calls — the SCI interpreter
   drives loop/recur in its OWN host loop and calls :interrupt-fn synchronously
   at the top of every interpreted fn/loop entry. So a wall-clock interrupt-fn
   throws SYNCHRONOUSLY in the same call stack as (sci/eval-string ...). The
   event loop is NEVER yielded inside test 1's loop — there is no await, no
   setTimeout, no core.async."
  (:require [sci.core :as sci]
            [sci.interrupt :as interrupt]
            [clojure.string :as str]))

(defn now [] (js/Date.now))

(defn deadline-interrupt-fn
  "Zero-arg :interrupt-fn that throws an UN-CATCHABLE sci interrupt once the
   wall clock passes `(+ start ms)`. Polls js/Date.now — no event-loop yield."
  [ms]
  (let [deadline (+ (now) ms)]
    (fn []
      (when (> (now) deadline)
        (interrupt/interrupt!)))))

(defn line [] (println (apply str (repeat 72 "="))))

(defn classify-thrown [e]
  (cond
    (and (instance? cljs.core/ExceptionInfo e)
         (contains? (ex-data e) :sci.impl/interrupt))
    "SCI-INTERRUPT (ex-info w/ :sci.impl/interrupt marker)"
    (instance? js/RangeError e) "js/RangeError (StackOverflow)"
    (instance? js/Error e) (str "js/Error: " (.-message e))
    :else (str "other: " (pr-str e))))

;; ---------------------------------------------------------------------------
;; TEST 1 — TRUE-SYNC-LOOP ABORT (the decisive one)
;; ---------------------------------------------------------------------------
(defn run-one
  "Eval `code` under sci with a wall-clock interrupt at `budget-ms`. Returns a
   map describing what happened. The (now) bracketing this call measures REAL
   wall-clock around a fully synchronous host call."
  [label code budget-ms]
  (let [t0 (now)
        outcome (try
                  (let [v (sci/eval-string code {:interrupt-fn (deadline-interrupt-fn budget-ms)})]
                    {:returned v})
                  (catch :default e
                    {:threw e}))
        t1 (now)
        elapsed (- t1 t0)]
    (println)
    (println (str "  [" label "]  code = " code))
    (println (str "    wall-clock elapsed: " elapsed " ms  (budget " budget-ms " ms)"))
    (if (contains? outcome :threw)
      (println (str "    THREW: " (classify-thrown (:threw outcome))))
      (println (str "    RETURNED (no throw): " (pr-str (:returned outcome)))))
    (assoc outcome :label label :elapsed elapsed :budget budget-ms)))

(defn test-1-true-sync-abort []
  (line)
  (println "TEST 1 — TRUE-SYNC-LOOP ABORT (decisive)")
  (println "  Goal: a genuinely synchronous infinite loop must be aborted by the")
  (println "  wall-clock interrupt within ~the budget, and the script must CONTINUE.")
  ;; FALSIFICATION PROBE: if the loop ever yielded the event loop, a queued
  ;; macrotask (setTimeout 0) or microtask would have run mid-loop. We arm both
  ;; BEFORE the synchronous eval and check AFTER. If either flipped before the
  ;; throw, the "loop" secretly yielded and test 1 is invalid.
  (let [macrotask-ran (atom false)
        microtask-ran (atom false)]
    (js/setTimeout #(reset! macrotask-ran true) 0)
    (.then (js/Promise.resolve) (fn [_] (reset! microtask-ran true)))
    (try (sci/eval-string "(loop [] (recur))"
                          {:interrupt-fn (deadline-interrupt-fn 250)})
         (catch :default _ nil))
    (println)
    (println (str "  FALSIFICATION PROBE (did the loop yield the event loop?):"))
    (println (str "    setTimeout(0) macrotask ran DURING the loop? " @macrotask-ran
                  "   (must be FALSE — proves no event-loop yield)"))
    (println (str "    Promise microtask ran DURING the loop?       " @microtask-ran
                  "   (must be FALSE — proves no microtask yield)")))
  (let [budget 250
        r-loop    (run-one "loop-recur infinite" "(loop [] (recur))" budget)
        r-dotimes (run-one "dotimes 1e9"         "(dotimes [_ 1e9] nil)" budget)
        ;; deep NON-tail recursion: a real recursive call per level, NOT recur.
        ;; This may blow the JS stack (RangeError) before the deadline fires.
        r-deep    (run-one "deep non-tail recursion"
                           "(defn f [n] (if (zero? n) 0 (inc (f (dec n))))) (f 1e9)"
                           budget)]
    (println)
    (println "  >>> Script CONTINUED past all three evals (process did NOT hang). <<<")
    {:loop r-loop :dotimes r-dotimes :deep r-deep}))

;; ---------------------------------------------------------------------------
;; TEST 2 — UN-CATCHABILITY
;; ---------------------------------------------------------------------------
(defn test-2-uncatchable []
  (line)
  (println "TEST 2 — UN-CATCHABILITY")
  (println "  Sandboxed try/catch (and a throwing finally) must NOT swallow the interrupt.")
  (let [budget 250
        ;; (a) plain catch around the infinite loop
        code-a "(try (loop [] (recur)) (catch :default _ :swallowed))"
        ;; (b) catch + a finally that itself throws — finally must not mask the interrupt
        code-b "(try (loop [] (recur)) (catch :default _ :swallowed) (finally (throw (ex-info \"finally-boom\" {}))))"]
    (let [r-a (run-one "catch :default around infinite loop" code-a budget)]
      (println (str "    -> returned :swallowed? "
                    (= :swallowed (:returned r-a))
                    "  (must be FALSE for un-catchability)")))
    (let [r-b (run-one "catch + throwing finally" code-b budget)]
      (println (str "    -> returned :swallowed? "
                    (= :swallowed (:returned r-b))
                    "  (must be FALSE; interrupt must win over finally-boom)")))))

;; ---------------------------------------------------------------------------
;; TEST 3 — OVERHEAD (sci eval vs native, + ctx creation cost)
;; ---------------------------------------------------------------------------

(def tile-src
  "A small representative 'tile render' body: a few map/filter/str ops over a
   modest seq, returning a string — roughly the shape of a real tile.
   Uses clojure.string/join, provided via a sci namespace under alias `str`."
  "(require '[clojure.string :as str])
   (defn render-tile [items]
     (->> items
          (filter (fn [m] (:visible m)))
          (map (fn [m] (str (:label m) \"=\" (:value m))))
          (sort)
          (str/join \", \")))
   (render-tile data)")

;; native equivalent of the same logic (compiled CLJS), for the baseline
(defn render-tile-native [items]
  (->> items
       (filter (fn [m] (:visible m)))
       (map (fn [m] (str (:label m) "=" (:value m))))
       (sort)
       (str/join ", ")))

(def tile-data
  (vec (for [i (range 24)]
         {:label (str "k" i) :value (* i 3) :visible (even? i)})))

(defn percentile [sorted-v p]
  (let [n (count sorted-v)
        idx (min (dec n) (int (* p n)))]
    (nth sorted-v idx)))

(defn time-many [thunk n]
  ;; warmup
  (dotimes [_ (quot n 4)] (thunk))
  (let [samples (vec (for [_ (range n)]
                       (let [t0 (js/performance.now)]
                         (thunk)
                         (- (js/performance.now) t0))))
        sorted (vec (sort samples))]
    {:median (percentile sorted 0.5)
     :p99    (percentile sorted 0.99)
     :n n}))

(defn ms->us [x] (* x 1000.0))

(defn test-3-overhead []
  (line)
  (println "TEST 3 — OVERHEAD (per-render sci eval vs native; ctx creation cost)")
  (let [n 2000
        ;; (a) ctx CREATION cost (one-time): build a sci ctx with str/data bound
        ns-opts {:namespaces {'clojure.string {'join str/join}
                              'user {'data tile-data}}}
        mk-ctx (fn [] (sci/init ns-opts))
        ctx-samples (do (dotimes [_ 20] (mk-ctx))
                        (vec (for [_ (range 100)]
                               (let [t0 (js/performance.now)] (mk-ctx)
                                 (- (js/performance.now) t0)))))
        ctx-sorted (vec (sort ctx-samples))
        ;; (b) per-call sci eval reusing ONE ctx (realistic per-render path)
        ctx (mk-ctx)
        sci-eval (fn [] (sci/eval-string* ctx tile-src))
        ;; (c) per-call sci eval building a FRESH ctx each time (cold path)
        sci-eval-cold (fn [] (sci/eval-string tile-src ns-opts))
        ;; (d) native baseline
        native (fn [] (render-tile-native tile-data))
        sci-warm-t  (time-many sci-eval n)
        sci-cold-t  (time-many sci-eval-cold (quot n 2))
        native-t    (time-many native n)]
    (println)
    (println (str "  sci CONTEXT CREATION (one-time): median "
                  (.toFixed (percentile ctx-sorted 0.5) 4) " ms,  p99 "
                  (.toFixed (percentile ctx-sorted 0.99) 4) " ms"))
    (println)
    (println (str "  per-call (reusing ctx)   : median "
                  (.toFixed (ms->us (:median sci-warm-t)) 2) " µs,  p99 "
                  (.toFixed (ms->us (:p99 sci-warm-t)) 2) " µs   (n=" n ")"))
    (println (str "  per-call (fresh ctx/call): median "
                  (.toFixed (:median sci-cold-t) 4) " ms,   p99 "
                  (.toFixed (:p99 sci-cold-t) 4) " ms    (n=" (quot n 2) ")"))
    (println (str "  per-call NATIVE compiled : median "
                  (.toFixed (ms->us (:median native-t)) 3) " µs,  p99 "
                  (.toFixed (ms->us (:p99 native-t)) 3) " µs   (n=" n ")"))
    (println)
    (let [warm-ms (:median sci-warm-t)]
      (println (str "  Under a ~250ms tile budget? warm per-call median = "
                    (.toFixed warm-ms 4) " ms  -> "
                    (if (< warm-ms 250) "YES, comfortably" "NO"))))))

;; ---------------------------------------------------------------------------
;; TEST 4 — RESIDUAL CLASS (what SCI canNOT bound)
;; ---------------------------------------------------------------------------
;; A native host fn that loops forever, and a native ReDoS regex. The
;; interrupt-fn fires only on INTERPRETED fn/loop entry — never inside host
;; code. To keep the SPIKE itself from hanging forever while PROVING the hang,
;; the host fn self-aborts on its own wall clock and reports whether the sci
;; interrupt-fn was ever called during the host loop. (interrupt-fn-called?
;; staying FALSE across a multi-second host loop is the proof.)

(def ^:dynamic *interrupt-fired* (atom false))

(defn host-busy-loop
  "Native JS-style busy loop. Spins synchronously. self-abort-ms bounds the
   spike (a REAL pod host fn would have no such bound and would hang forever).
   Returns the number of host iterations performed."
  [self-abort-ms]
  (let [deadline (+ (now) self-abort-ms)]
    (loop [i 0]
      (if (> (now) deadline)
        i
        (recur (inc i))))))

(defn test-4-residual []
  (line)
  (println "TEST 4 — RESIDUAL CLASS (SCI cannot bound native/host CPU loops)")
  (println "  interrupt-fn fires only on INTERPRETED entry. Native host loops &")
  (println "  native regex run to completion; the interrupt-fn NEVER fires inside.")
  ;; (a) native host fn that loops "forever" (self-bounded for the spike)
  (reset! *interrupt-fired* false)
  (let [budget 250
        host-self-abort 1500
        ifn (let [deadline (+ (now) budget)]
              (fn []
                ;; this fn would fire the interrupt IF sci ever called it during
                ;; the host loop. It records that it was called.
                (reset! *interrupt-fired* true)
                (when (> (now) deadline) (interrupt/interrupt!))))
        t0 (now)
        result (try
                 (sci/eval-string
                  "(host-busy-loop 1500)"
                  {:interrupt-fn ifn
                   :namespaces {'user {'host-busy-loop host-busy-loop}}})
                 (catch :default e {:threw (classify-thrown e)}))
        elapsed (- (now) t0)]
    (println)
    (println (str "  (a) native host busy-loop (budget " budget "ms, host self-abort " host-self-abort "ms):"))
    (println (str "      wall-clock elapsed: " elapsed " ms"))
    (println (str "      interrupt-fn EVER called during host loop? " @*interrupt-fired*))
    (println (str "      outcome: " (pr-str result)))
    (println (str "      -> elapsed >> budget (" (> elapsed (* 2 budget)) ") AND interrupt never fired ("
                  (not @*interrupt-fired*) ") => SCI did NOT bound it.")))
  ;; (b) catastrophic-backtracking regex (ReDoS) via NATIVE re-find.
  ;; CLJS sci.interrupt does NOT override re-find (JVM-only per source). So
  ;; this is the host JS regex engine: a running match blocks the event loop.
  ;; We use a moderately-evil input so the spike returns in a few seconds while
  ;; still demonstrating the cost is unbounded by the interrupt-fn.
  (reset! *interrupt-fired* false)
  (let [budget 250
        ;; classic exponential-backtracking pattern + non-matching tail
        redos-code "(re-find #\"^(a+)+$\" \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaa!\")"
        ifn (let [deadline (+ (now) budget)]
              (fn [] (reset! *interrupt-fired* true)
                (when (> (now) deadline) (interrupt/interrupt!))))
        t0 (now)
        result (try (sci/eval-string redos-code {:interrupt-fn ifn})
                    (catch :default e {:threw (classify-thrown e)}))
        elapsed (- (now) t0)]
    (println)
    (println (str "  (b) ReDoS via native re-find #\"^(a+)+$\" (budget " budget "ms):"))
    (println (str "      wall-clock elapsed: " elapsed " ms"))
    (println (str "      interrupt-fn EVER called during the regex match? " @*interrupt-fired*))
    (println (str "      outcome: " (pr-str result)))
    (println (str "      -> elapsed >> budget? " (> elapsed budget)
                  "   interrupt fired mid-match? " @*interrupt-fired*
                  " => host regex is unbounded by interrupt-fn."))))

(defn main [& _]
  (println)
  (line)
  (println "SCI 0.13.53  +  wall-clock :interrupt-fn  —  Node/CLJS in-process spike")
  (println (str "Node " js/process.version "  |  " (js/Date.)))
  (test-1-true-sync-abort)
  (test-2-uncatchable)
  (test-3-overhead)
  (test-4-residual)
  (line)
  (println "SPIKE COMPLETE — process reached the end (it never hung).")
  (line))
