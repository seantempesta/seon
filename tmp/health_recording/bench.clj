;; Workbench (lane health-recording-opus, 2026-09-28; disposable, JVM mode):
;; the histogram bracket against HEAD's seon.profile/timed in the same JVM.
;; Sent to eval_clj in namespace tmp.health-recording.bench; never load-file'd.
;; The MCP reader qualifies `.method` inside syntax-quote, so the variants are
;; written out inline rather than as probe macros.
(ns tmp.health-recording.bench
  (:require [seon.batch]
            [seon.profile])
  (:import [java.util.concurrent.atomic AtomicLongArray LongAdder LongAccumulator]
           [java.util.concurrent ConcurrentHashMap]))

(def longest
  (reify java.util.function.LongBinaryOperator (applyAsLong [_ a b] (Math/max a b))))

(defn head-fn
  "HEAD's bracket on a fresh cell."
  []
  (let [c (seon.profile/cell {:seon.profile/sym 'bench/head :seon.profile/scope :seon.profile/host})]
    (seon.profile/with-cell c (fn [x] (seon.profile/timed (inc x))))))

(defn a-fn
  "Variant A: AtomicLongArray histogram in place of the call counter; one world
  read; the world's count reuses the exit clock read. `gauge?` adds an entry
  LongAdder (in flight = entries - calls)."
  [gauge?]
  (let [^ThreadLocal e (ThreadLocal.) ^AtomicLongArray h (AtomicLongArray. 40)
        ^LongAdder t (LongAdder.) ^LongAccumulator m (LongAccumulator. longest 0)
        ^LongAdder entries (LongAdder.) k ['bench/a nil] gauge? (boolean gauge?)]
    (fn [x]
      (let [prior (.get e)
            sink (:seon.batch/sink seon.batch/*world*)
            here (or sink seon.profile/host-entry)]
        (if (identical? prior here)
          (inc x)
          (let [started (System/nanoTime)
                ^AtomicLongArray counted (when sink (.computeIfAbsent ^ConcurrentHashMap sink k seon.profile/new-adder))]
            (when gauge? (.increment entries))
            (.set e here)
            (try
              (let [value (inc x)
                    elapsed (- (System/nanoTime) started)
                    b (- 64 (Long/numberOfLeadingZeros elapsed))]
                (.incrementAndGet h (if (< b 39) b 39))
                (.add t elapsed)
                (.accumulate m elapsed)
                (when counted (.incrementAndGet counted 0) (.addAndGet counted 1 elapsed))
                value)
              (finally (.set e prior)))))))))

(defn b-fn
  "Variant B: a LongAdder per bucket."
  []
  (let [^ThreadLocal e (ThreadLocal.) ^objects h (into-array LongAdder (repeatedly 40 #(LongAdder.)))
        ^LongAdder t (LongAdder.) ^LongAccumulator m (LongAccumulator. longest 0) k ['bench/b nil]]
    (fn [x]
      (let [prior (.get e)
            sink (:seon.batch/sink seon.batch/*world*)
            here (or sink seon.profile/host-entry)]
        (if (identical? prior here)
          (inc x)
          (let [started (System/nanoTime)
                ^AtomicLongArray counted (when sink (.computeIfAbsent ^ConcurrentHashMap sink k seon.profile/new-adder))]
            (.set e here)
            (try
              (let [value (inc x)
                    elapsed (- (System/nanoTime) started)
                    b (- 64 (Long/numberOfLeadingZeros elapsed))]
                (.increment ^LongAdder (aget h (if (< b 39) b 39)))
                (.add t elapsed)
                (.accumulate m elapsed)
                (when counted (.incrementAndGet counted 0) (.addAndGet counted 1 elapsed))
                value)
              (finally (.set e prior)))))))))

(defn per-call-ns
  "Mean ns per call of `f` over `n` calls on each of `threads` threads."
  [f threads n]
  (let [run (fn [] (let [t0 (System/nanoTime)]
                     (loop [i 0 acc 0] (if (< i n) (recur (inc i) (long (f acc))) acc))
                     (/ (double (- (System/nanoTime) t0)) n)))]
    (if (= 1 threads)
      (run)
      (let [fs (doall (repeatedly threads #(future (run))))]
        (/ (reduce + (map deref fs)) threads)))))

(defn bench
  "ns/call, three runs, for the original, HEAD's bracket and the variants."
  [threads n]
  (let [fs [[:orig inc] [:head (head-fn)] [:a (a-fn false)] [:a+gauge (a-fn true)] [:b (b-fn)]]]
    (dotimes [_ 3] (doseq [[_ f] fs] (per-call-ns f threads n)))
    (into {} (for [[nm f] fs]
               [nm (vec (repeatedly 3 #(/ (Math/round (* 10 (per-call-ns f threads n))) 10.0)))]))))
