(ns seon.profile-test
  "seon.profile: every contracted call counted exactly, one in 64 timed, read per epoch."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [seon.contract :as contract :refer [defn defn-]]
            [seon.profile :as profile] [seon.cost] [seon.lib] [seon.repl :as repl] [seon.agent :as agent] [jolt.ffi :as ffi]))

(defn- leaf {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))

(defn branch {:malli/schema [:=> [:cat :int] :int]} [n] (reduce + (map leaf (range n))))

(defn- cost-of
  "Each touched function's calls, by symbol, after running thunk in a fresh epoch."
  [thunk] (profile/begin!) (thunk) (into {} (map (juxt :var :calls)) (profile/touched)))

(deftest every-call-of-a-contracted-function-is-counted
  (when contract/on?
    (is (= {'seon.profile-test/branch 1 'seon.profile-test/leaf 1000} (cost-of #(branch 1000))))
    (testing "a new epoch starts from zero"
      (is (= {'seon.profile-test/leaf 3} (cost-of #(dotimes [i 3] (leaf i))))))
    (testing "an epoch that calls nothing touched nothing"
      (is (= {} (cost-of (fn [] nil)))))))

(deftest calls-is-the-epochs-total
  (when contract/on?
    (profile/begin!)
    (branch 200)
    (is (= 201 (profile/calls)))))

(deftest every-touched-function-has-an-estimated-time
  (when contract/on?
    (testing "the first call of an epoch is always timed, so a function called once has a time"
      (profile/begin!)
      (branch 50)
      (let [by (into {} (map (juxt :var :ms)) (profile/touched))]
        (is (pos? (by 'seon.profile-test/branch)))
        (is (every? #(>= % 0.0) (vals by)) "a call shorter than a clock read reads as 0, never negative")))))
;; (the sampled estimate held within 2x of the wall clock over a loop went: it was red under load,
;; an 11.7 ms estimate against 2.5 ms measured, and a time is shown, never a verdict:
;; seon.cost/exact, pure-tests 2026-10-23)

(deftest top-orders-by-time-then-calls
  (is (= '[c a b] (mapv :var (profile/top [{:var 'a :calls 9 :ms 1.0} {:var 'b :calls 1 :ms 1.0} {:var 'c :calls 1 :ms 2.0}] 3))))
  (is (= 1 (count (profile/top [{:var 'a :calls 9 :ms 1.0} {:var 'b :calls 1 :ms 1.0}] 1)))))

(deftest an-evals-record-carries-its-costliest-functions
  (when contract/on?
    ;; the REPL's own handler in this process, as a scratch session (not agent mode: nothing is transacted)
    (let [old @agent/state
          r (try (reset! agent/state {:n 0 :ns (the-ns 'user)})
                 (repl/eval-string "(seon.profile-test/branch 300)" {})
                 (finally (reset! agent/state old)))
          fns (:fns r)]
      (is (= 8955050 (:value r)) (pr-str (dissoc r :fns)))
      (is (= {'seon.profile-test/branch 1 'seon.profile-test/leaf 300}
             (into {} (map (juxt :var :calls)) fns))
          "the response carries them, and seon.repl/record! stores them on the eval's record"))))

;; the system's clock read directly (macOS clock_gettime_nsec_np, CLOCK_UPTIME_RAW = 8), the
;; one every process on the machine reads
(ffi/defcfn ^:private uptime-raw "clock_gettime_nsec_np" [:int] :long)

(deftest the-clock-is-the-one-every-process-reads
  (testing "profile/now-ns is the machine's CLOCK_UPTIME_RAW, not a count from this process's start"
    (let [a (uptime-raw 8) t (profile/now-ns) b (uptime-raw 8)]
      (is (<= a t b) (str "system " a " .. " b ", profile/now-ns " t)))))

(deftest reading-the-clock-allocates-nothing
  (let [n 10000
        bytes (fn [f] (let [a0 (profile/alloc-bytes)] (f) (- (profile/alloc-bytes) a0)))
        empty-loop (bytes #(dotimes [_ n] nil))
        reads (bytes #(dotimes [_ n] (profile/now-ns)))]
    (is (<= (- reads empty-loop) 1024) (str n " reads allocated " reads " bytes, an empty loop " empty-loop))))

(defn- busy-threads
  "n threads running (work) until stop is set, each delivering its promise once it is
  running. Answers [threads stop], after every one has started."
  [n work]
  (let [stop (atom false)
        started (vec (repeatedly n promise))
        ts (mapv (fn [p] (Thread. ^Runnable (fn [] (deliver p true) (loop [] (when-not @stop (work) (recur)))))) started)]
    (doseq [^Thread t ts] (.start t))
    (doseq [p started] @p)
    [ts stop]))

(defn- end-threads! [[ts stop]] (reset! stop true) (doseq [^Thread t ts] (.join t)))

(defn- allocating-thread
  "One thread allocating until stopped (a vector of 100 longs, again and again), running when answered."
  [] (busy-threads 1 #(dotimes [_ 1000] (vec (range 100)))))

(deftest a-threads-allocation-is-its-own
  ;; task seon.cost/a-proof-measures-only-its-own-thread: alloc-bytes counted the whole
  ;; process (1,712 B idle against 1.4 GB in 200 ms while another thread allocated), so a cost
  ;; proof read other threads' work as its own. Now it is the calling thread's count alone.
  (let [window #(let [a0 (profile/alloc-bytes) _ (Thread/sleep 200)] (- (profile/alloc-bytes) a0))]
    (is (< (window) 65536) "idle, the caller allocates next to nothing")
    (let [busy (allocating-thread)]
      (try (let [w (window)] (is (< w 65536) (str "another thread allocating moves the caller's count by " w " B")))
           (finally (end-threads! busy))))
    (let [a0 (profile/alloc-bytes) b (byte-array 1000000) a1 (profile/alloc-bytes)]
      (is (<= 1000000 (- a1 a0) 1100000) (str "the caller's own " (count b) " bytes counted as " (- a1 a0))))))

(deftest a-collection-in-a-measured-call-is-not-its-bytes
  ;; task seon.cost/a-cost-proof-is-the-same-under-load: a collection tripped inside a measured
  ;; call charged the call the collector's bookkeeping (17,344 B for an explicit collect, 67,264 B
  ;; a call of seon.vector/near beside an allocating thread), so a proof's sizes read different
  ;; offsets under load and its growth fit went red. A call of ~5 MB beside a thread allocating
  ;; takes a collection in every sample; its bytes are the same as alone. (into, not vec: Jolt's
  ;; vec of a seq applies jolt-vector to n arguments, and the stack that grows is kept until a
  ;; collection, so vec's own bytes depend on whether one ran since the last call: task
  ;; jolt.core/vec-builds-by-reduction.)
  (let [f (fn [] (count (into [] (range 600000))))
        alone (seon.cost/measure f [])
        busy (allocating-thread)
        g0 (:count (seon.lib/gc-stats))
        beside (try (seon.cost/measure f []) (finally (end-threads! busy)))
        gcs (- (:count (seon.lib/gc-stats)) g0)]
    (is (pos? gcs) "the measure beside the allocating thread ran collections")
    (is (<= (Math/abs (- (:bytes beside) (:bytes alone))) 4096.0)
        (str "alone " (long (:bytes alone)) " B, beside " (long (:bytes beside)) " B, over " gcs " collections"))))

(deftest a-new-stack-is-not-the-calls-bytes
  ;; task seon.cost/a-cost-proof-is-the-same-under-load: a call deep enough to overflow its stack
  ;; takes a cached stack segment, or allocates one when a collection has emptied the cache, so
  ;; the same call read 5,794,784 B or ~65 KB more (seon.vector/near at 500, alone), and beside an
  ;; allocating thread the more every time. In plain Chez a 50,000-deep recursion read 384 B
  ;; warm and 393,504 B after a collection. A stack is the runtime's; the call's depth is its ticks.
  (let [deep (fn deep [n] (if (zero? n) 0 (inc (deep (dec n)))))
        window #(let [a0 (profile/alloc-bytes) _ (deep 20000) a1 (profile/alloc-bytes)] (- a1 a0))
        warm (do (window) (window))
        cold (do (System/gc) (window))]
    (is (<= (Math/abs (- cold warm)) 4096) (str "warm " warm " B, after a collection " cold " B")))
  ;; a try is a call/1cc, which takes a new stack when the cache is empty: 144 B warm and
  ;; 65,664 B after a collection, so seon.vector/near read 65.5 KB more for each collection
  ;; in its window (datahike.datom/compare-value's try, on every f16 datom it compares)
  (let [window #(let [a0 (profile/alloc-bytes) _ (try 1 (catch Exception _ 0)) a1 (profile/alloc-bytes)] (- a1 a0))
        warm (do (window) (window))
        cold (do (System/gc) (window))]
    (is (<= (Math/abs (- cold warm)) 4096) (str "a try: warm " warm " B, after a collection " cold " B"))))

(deftest a-measure-is-the-same-beside-another-thread
  ;; the measured call's bytes are the same alone and while another thread allocates
  (let [f (fn [] (reduce + (map leaf (range 1000))))
        alone (seon.cost/measure f [])
        busy (allocating-thread)
        beside (try (seon.cost/measure f []) (finally (end-threads! busy)))]
    (is (<= (:bytes beside) (* 1.25 (max 1.0 (:bytes alone)))) (str "alone " alone ", beside " beside))))

(deftest a-measure-is-the-call-at-its-steady-state
  ;; task seon.cost/a-call-allocates-the-same-bytes-each-time: a call allocates more on its first calls
  ;; (seon.merge/delta-three-way: 3,776 2,752 1,536 3,584 B over its settled bytes, down to 320 by the
  ;; 14th); the measure is the settled call's, never one the warm-up is still in
  (let [calls (atom 0)
        f (fn [calls] (fn [] (swap! calls inc) (count (vec (range (if (< @calls 10) 3000 100))))))
        warming (seon.cost/measure (f calls) [])
        steady (seon.cost/measure (f (atom 100)) [])]
    (is (< 10 @calls) "the measure went on past the warm-up")
    (is (= (:bytes steady) (:bytes warming)) (str "the warm-up is not the cost: " warming " against " steady))))

(deftest a-measure-that-never-settles-is-an-error-not-a-number
  ;; a call allocating fewer bytes every time never reaches a steady state: the measure stops at its cap
  ;; and says so, rather than answering the last call's bytes as the cost
  (let [calls (atom 0)
        cap (seon.config/value :seon.config.proofs/cost-max-samples)
        e (try (seon.cost/measure (fn [] (swap! calls inc) (count (vec (range (- 100000 (* 100 @calls)))))) []) nil
               (catch Throwable t (ex-data t)))]
    (is (= :seon.cost/unsettled (:seon.error/type e)))
    (is (= cap @calls (:calls e)) "it stopped at its cap")))

(deftest a-measure-is-one-number-beside-another-thread
  ;; the same call measured ten times while another thread allocates reads one byte count
  (let [f (fn [] (reduce + (map leaf (range 1000))))
        busy (allocating-thread)
        counts (try (vec (repeatedly 10 #(:bytes (seon.cost/measure f [])))) (finally (end-threads! busy)))]
    (is (= 1 (count (distinct counts))) (str "bytes " (sort counts)))))

(deftest a-call-is-measured-one-way
  ;; task seon.profile/a-call-is-measured-one-way: one measurement of a call, which the REPL's
  ;; per-form cost and the cost proof both read
  (let [m (volatile! nil)]
    (testing "the call's value is answered; what it measured goes to the callback"
      (is (= 328350 (profile/measure! #(branch 100) #(vreset! m %))))
      (is (= #{:ticks :bytes :wall-ns :cpu-ns :calls} (set (keys @m))))
      (is (pos? (:ticks @m)) "its work, in engine ticks")
      (is (= 101 (:calls @m)) "branch and its 100 leaf calls, the epoch's contracted calls"))
    (testing "a call that throws is measured too, and its exception goes on"
      (vreset! m nil)
      (is (thrown-with-msg? Exception #"boom" (profile/measure! #(do (branch 10) (throw (ex-info "boom" {}))) #(vreset! m %))))
      (is (= 11 (:calls @m))))))
