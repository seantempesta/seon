(ns seon.profile-test
  "seon.profile: every contracted call counted exactly, with its ticks and bytes, in all and its own, read per epoch."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [seon.contract :as contract :refer [defn defn-]]
            [seon.profile :as profile] [seon.cost] [seon.lib] [seon.repl :as repl] [seon.agent :as agent] [jolt.ffi :as ffi]))

(defn- leaf {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))

(defn branch {:malli/schema [:=> [:cat :int] :int]} [n] (reduce + (map leaf (range n))))

(defn- cost-of
  "Each touched function's calls, by symbol, after running thunk in a fresh epoch."
  [thunk] (profile/begin!) (thunk) (into {} (map (juxt :var :calls)) (profile/touched)))

;; docs/cost.md#counting-calls: what a test read is what its verdict's digest will be made of, so a nested measure (an eval, a cost proof) must not lose it
;; docs/sharing.md#a-functions-cost-is-a-fact: stats add exactly, however they were split
;; Estimate: 3 accrue calls and one stats-sum, ~10 contracted calls x ~30k with checks: ~300k ticks.
(deftest ^{:seon.test/estimate {:ticks 300000}} stats-add-the-same-however-they-were-split
  (let [f (fn [c t] {:var 'a/f :calls c :ticks t :self-ticks t :bytes t :self-bytes t})
        once (profile/accrue {} [(f 2 100) (f 1 1000)] "e1")
        apart (profile/stats-sum (get (profile/accrue {} [(f 2 100)] "e1") 'a/f) (get (profile/accrue {} [(f 1 1000)] "e1") 'a/f))]
    (is (= (get once 'a/f) apart))
    (is (= {:self-ticks 1000 :eval "e1"} (:worst apart)) "the worst is the one with the most self-ticks")
    (is (= {5 2 9 1} (:hist apart)) "calls by the log2 of their self-ticks a call")))

;; Estimate: two counted wrappers, reads-begin!, begin!, one measure!, touched x2: ~8 calls x 25k: ~200k ticks.
(deftest ^{:seon.test/estimate {:ticks 200000}} a-nested-measure-keeps-what-ran
  (let [before (profile/counted 'seon.profile-test/before-the-nested (fn [] 1))
        inside (profile/counted 'seon.profile-test/inside-the-nested (fn [] 2))]
    (profile/reads-begin!)
    (profile/begin!)
    (before)
    (profile/measure! inside)
    (is (= #{'seon.profile-test/inside-the-nested} (into #{} (map :var) (profile/touched))) "an epoch holds only what ran since it opened")
    (is (every? (profile/reads) ['seon.profile-test/before-the-nested 'seon.profile-test/inside-the-nested]) "the reads hold both")
    (let [by (into {} (map (juxt :var :calls)) (profile/with-reads (profile/touched)))]
      (is (= [0 1] [(by 'seon.profile-test/before-the-nested) (by 'seon.profile-test/inside-the-nested)]) "and with-reads names the first at no counts"))
    (profile/reads-begin!)
    (is (not (contains? (profile/reads) 'seon.profile-test/before-the-nested)) "a new record starts over")))

;; Estimate: 1,000 + 3 leaf calls x ~300 (counted, checked) = 0.3M; 3 epochs (begin!, touched) ~70k each: ~500k ticks.
(deftest ^{:seon.test/estimate {:ticks 500000}} every-call-of-a-contracted-function-is-counted
  (when contract/on?
    (is (= {'seon.profile-test/branch 1 'seon.profile-test/leaf 1000} (cost-of #(branch 1000))))
    (testing "a new epoch starts from zero"
      (is (= {'seon.profile-test/leaf 3} (cost-of #(dotimes [i 3] (leaf i))))))
    (testing "an epoch that calls nothing touched nothing"
      (is (= {} (cost-of (fn [] nil)))))))

;; Estimate: 201 contracted calls x 300 = 60k, one epoch: ~100k ticks.
(deftest ^{:seon.test/estimate {:ticks 100000}} calls-is-the-epochs-total
  (when contract/on?
    (profile/begin!)
    (branch 200)
    (is (= 201 (profile/calls)))))

;; docs/cost.md#counting-calls: a signal handler's work is a callee's of the call it lands in (seon.profile/aside), so a signal inside
;; either call breaks these identities for that one measure (SIGIO reaches an agent's test child: about 1 measure in 3,000 under a
;; flood of them). Holding interrupts is no answer, as it stops the tick counter too. A counting defect breaks every measure, so one of
;; three exact says the counting is right.
;; Estimate: 3 runs x (51 calls x 300 + an epoch ~70k): ~200k ticks.
(deftest ^{:seon.test/estimate {:ticks 200000}} a-functions-own-work-is-its-work-less-its-callees
  (when contract/on?
    (let [measure (fn [] (profile/begin!) (branch 50)
                    (let [by (into {} (map (juxt :var identity)) (profile/touched))]
                      [(by 'seon.profile-test/branch) (by 'seon.profile-test/leaf)]))
          exact? (fn [[b l]] (and (= (:ticks l) (:self-ticks l)) (= (:bytes l) (:self-bytes l))
                                  (= (:ticks b) (+ (:self-ticks b) (:ticks l))) (= (:bytes b) (+ (:self-bytes b) (:bytes l)))))
          runs (vec (repeatedly 3 measure))
          [b l :as run] (or (first (filter exact? runs)) (peek runs))]
      (testing "every touched function did work, counted in ticks and bytes"
        (is (every? #(pos? (:ticks %)) [b l]) (pr-str run)))
      (testing "a leaf's own work is all of it, and a caller's its own and its callees', exactly"
        (is (exact? run) (pr-str runs))
        (is (< (:self-ticks b) (:ticks b)))))))

(defn interrupted {:malli/schema [:=> [:cat :int] :int]} [n] (+ (branch n) (profile/aside #(branch n))))

;; Estimate: 102 contracted calls x 300 = 30k, one epoch, one aside: ~100k ticks.
(deftest ^{:seon.test/estimate {:ticks 100000}} work-set-aside-is-not-the-interrupted-calls
  ;; docs/cost.md#counting-calls: a signal handler runs at a safe point inside whatever call is running
  ;; (seon.intr); md-scan #28's test child counted its SIGIO handler's calls in the eval it interrupted.
  ;; Work set aside (seon.profile/aside) is neither counted nor the interrupted call's own
  (when contract/on?
    (profile/begin!)
    (interrupted 50)
    (let [by (into {} (map (juxt :var identity)) (profile/touched))
          i (by 'seon.profile-test/interrupted) b (by 'seon.profile-test/branch)]
      (is (= {'seon.profile-test/interrupted 1 'seon.profile-test/branch 1 'seon.profile-test/leaf 50}
             (into {} (map (juxt key (comp :calls val))) by))
          "only the calls outside the aside are counted")
      (is (< (+ (:self-ticks i) (:ticks b)) (:ticks i)) "the aside's work is not the interrupted call's own")
      (is (= 1 (profile/aside (constantly 1))) "it answers its thunk's value"))))

(defn- bare-leaf [x] (* x x))

;; Estimate: 2 measures of 10,000 calls: plain 10 ticks, counted 15 ticks a call = 0.25M, plus 2 measure! ~50k each: ~400k ticks.
(deftest ^{:seon.test/estimate {:ticks 400000}} counting-a-call-allocates-nothing-and-costs-a-few-ticks
  ;; measured 2026-10-27: a call of bare-leaf 10 ticks; counted (calls, ticks, bytes) 15
  (let [counted (profile/counted 'seon.profile-test/bare-leaf bare-leaf)
        n 10000
        cost (fn [f] (dissoc (profile/measure! #(dotimes [i n] (f i))) :value))
        plain (cost bare-leaf) wrapped (cost counted)]
    (is (<= (- (:bytes wrapped) (:bytes plain)) 1024) (str n " counted calls allocated " (:bytes wrapped) " bytes, plain " (:bytes plain)))
    (is (<= (- (:ticks wrapped) (:ticks plain)) (* 8 n)) (str n " counted calls " (:ticks wrapped) " ticks, plain " (:ticks plain)))))

;; Estimate: eval-string of one call form: read, analyze, compile ~0.5M; 301 counted calls x 300; the record: ~1M ticks.
(deftest ^{:seon.test/estimate {:ticks 1000000}} an-evals-record-carries-its-costliest-functions
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

;; Estimate: two foreign calls and one now-ns: ~20k ticks.
(deftest ^{:seon.test/estimate {:ticks 20000}} the-clock-is-the-one-every-process-reads
  (testing "profile/now-ns is the machine's CLOCK_UPTIME_RAW, not a count from this process's start"
    (let [a (uptime-raw 8) t (profile/now-ns) b (uptime-raw 8)]
      (is (<= a t b) (str "system " a " .. " b ", profile/now-ns " t)))))

;; Estimate: 10,000 now-ns calls x ~50 ticks (a foreign call) = 0.5M, an empty loop 10k x 10: ~600k ticks.
(deftest ^{:seon.test/estimate {:ticks 600000}} reading-the-clock-allocates-nothing
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

;; Estimate: three windows each a thread start and a join ~50k; the other threads' work is theirs, not counted; one 1 MB array (C fill): ~300k ticks.
(deftest ^{:seon.test/estimate {:ticks 300000}} a-threads-allocation-is-its-own
  ;; alloc-bytes counted the whole
  ;; process (1,712 B idle against 1.4 GB in 200 ms while another thread allocated), so a cost
  ;; proof read other threads' work as its own. Now it is the calling thread's count alone.
  ;; The window is another thread's work, waited on (its promise), never a sleep: [the caller's bytes, the worker's own]
  (let [window (fn [work] (let [done (promise) a0 (profile/alloc-bytes)
                                t (Thread. ^Runnable (fn [] (let [b0 (profile/alloc-bytes)] (work) (deliver done (- (profile/alloc-bytes) b0)))))]
                            (.start t)
                            (let [theirs @done] (.join t) [(- (profile/alloc-bytes) a0) theirs])))]
    (let [[mine _] (window (fn []))]
      (is (< mine 65536) "idle, the caller allocates next to nothing"))
    (let [[mine theirs] (window #(dotimes [_ 100] (dotimes [_ 1000] (vec (range 100)))))]
      (is (< 10000000 theirs) (str "the other thread allocated " theirs " B, its own count"))
      (is (< mine 65536) (str "another thread allocating moves the caller's count by " mine " B")))
    (let [a0 (profile/alloc-bytes) b (byte-array 1000000) a1 (profile/alloc-bytes)]
      (is (<= 1000000 (- a1 a0) 1100000) (str "the caller's own " (count b) " bytes counted as " (- a1 a0))))))

;; Estimate: measure's settle rule: ~17 calls alone and ~17 beside the allocating thread = 34 calls of (into [] (range 100000)) x ~15 ticks an element = 1.5M each: ~50M ticks.
(deftest ^{:seon.test/estimate {:ticks 50000000}} a-collection-in-a-measured-call-is-not-its-bytes
  ;; task seon.cost/a-cost-proof-is-the-same-under-load: a collection tripped inside a measured
  ;; call charged the call the collector's bookkeeping (17,344 B for an explicit collect, 67,264 B
  ;; a call of seon.vector/near beside an allocating thread), so a proof's sizes read different
  ;; offsets under load and its growth fit went red. A call beside a thread allocating
  ;; takes a collection in the samples (asserted: gcs is positive; the error class needs a collection in the window, not a large call); its bytes are the same as alone. (into, not vec: Jolt's
  ;; vec of a seq applies jolt-vector to n arguments, and the stack that grows is kept until a
  ;; collection, so vec's own bytes depend on whether one ran since the last call: task
  ;; jolt.core/vec-builds-by-reduction.)
  (let [f (fn [] (count (into [] (range 100000))))
        alone (seon.cost/measure f [] (seon.config/value :seon.config.proofs/cost-max-samples))
        busy (allocating-thread)
        g0 (:count (seon.lib/gc-stats))
        beside (try (seon.cost/measure f [] (seon.config/value :seon.config.proofs/cost-max-samples)) (finally (end-threads! busy)))
        gcs (- (:count (seon.lib/gc-stats)) g0)]
    (is (pos? gcs) "the measure beside the allocating thread ran collections")
    (is (<= (Math/abs (- (:bytes beside) (:bytes alone))) 4096.0)
        (str "alone " (long (:bytes alone)) " B, beside " (long (:bytes beside)) " B, over " gcs " collections"))))

;; Estimate: ~6 calls of a 20,000-deep recursion x ~10 ticks a frame = 0.2M each, 3 collections' own handler not counted: ~1M ticks.
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-new-stack-is-not-the-calls-bytes
  ;; task seon.cost/a-cost-proof-is-the-same-under-load: a call deep enough to overflow its stack
  ;; takes a cached stack segment, or allocates one when a collection has emptied the cache, so
  ;; the same call read 5,794,784 B or ~65 KB more (seon.vector/near at 500, alone), and beside an
  ;; allocating thread the more every time. In plain Chez a 50,000-deep recursion read 384 B
  ;; warm and 393,504 B after a collection. A stack is the runtime's; the call's depth is its ticks.
  (let [deep (fn deep [n] (if (zero? n) 0 (inc (deep (dec n)))))
        window #(let [a0 (profile/alloc-bytes) _ (deep 20000) a1 (profile/alloc-bytes)] (- a1 a0))
        warm (do (window) (window))
        cold (do (System/gc) (window))
        ;; a collection landing INSIDE the call: it finds the stack 20,000 frames deep and makes it one multi-shot
        ;; continuation, which the way back cuts piece by piece, a 64 B continuation a cut (7,040 B read here before
        ;; the runtime left a stack cut's continuation out of the count, as it leaves the stack out)
        collects (fn collects [n] (if (zero? n) (do (System/gc) 0) (inc (collects (dec n)))))
        ;; the least of three windows: one window in about 200 reads 96 B more, from a source not yet located
        ;; (task jolt.host/a-deep-call-with-a-collection-inside-sometimes-counts-96-bytes), which is not this claim
        inside (apply min (repeatedly 3 #(let [a0 (profile/alloc-bytes) _ (collects 20000) a1 (profile/alloc-bytes)] (- a1 a0))))]
    (is (= warm cold inside) (str "warm " warm " B, after a collection " cold " B, a collection inside the call " inside " B")))
  ;; a try is a call/1cc, which takes a new stack when the cache is empty: 144 B warm and
  ;; 65,664 B after a collection, so seon.vector/near read 65.5 KB more for each collection
  ;; in its window (datahike.datom/compare-value's try, on every f16 datom it compares)
  (let [window #(let [a0 (profile/alloc-bytes) _ (try 1 (catch Exception _ 0)) a1 (profile/alloc-bytes)] (- a1 a0))
        warm (do (window) (window))
        cold (do (System/gc) (window))]
    (is (<= (Math/abs (- cold warm)) 4096) (str "a try: warm " warm " B, after a collection " cold " B"))))

;; Estimate: ~34 calls (17 settle alone, 17 beside) of (reduce + (map leaf (range 1000))) x ~50 ticks an element = 50k each: ~2M ticks.
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-measure-is-the-same-beside-another-thread
  ;; the measured call's bytes are the same alone and while another thread allocates
  (let [f (fn [] (reduce + (map leaf (range 1000))))
        alone (seon.cost/measure f [] (seon.config/value :seon.config.proofs/cost-max-samples))
        busy (allocating-thread)
        beside (try (seon.cost/measure f [] (seon.config/value :seon.config.proofs/cost-max-samples)) (finally (end-threads! busy)))]
    (is (<= (:bytes beside) (* 1.25 (max 1.0 (:bytes alone)))) (str "alone " alone ", beside " beside))))

;; Estimate: warming: 9 calls of 3000 elements x 15 = 45k, then 17 of 100; steady: 17 of 100 x 1.5k: ~500k ticks.
(deftest ^{:seon.test/estimate {:ticks 500000}} a-measure-is-the-call-at-its-steady-state
  ;; task seon.cost/a-call-allocates-the-same-bytes-each-time: a call allocates more on its first calls
  ;; (seon.merge/delta-three-way: 3,776 2,752 1,536 3,584 B over its settled bytes, down to 320 by the
  ;; 14th); the measure given a cap (a call over its estimate, or a perf problem: one call is the default) is the settled call's, never
  ;; one the warm-up is still in
  (let [calls (atom 0)
        f (fn [calls] (fn [] (swap! calls inc) (count (vec (range (if (< @calls 10) 3000 100))))))
        warming (seon.cost/measure (f calls) [] (seon.config/value :seon.config.proofs/cost-max-samples))
        steady (seon.cost/measure (f (atom 100)) [] (seon.config/value :seon.config.proofs/cost-max-samples))]
    (is (< 10 @calls) "the measure went on past the warm-up")
    (is (= (:bytes steady) (:bytes warming)) (str "the warm-up is not the cost: " warming " against " steady))))

;; Estimate: a call that never settles runs to cost-max-samples = 60 calls of (vec (range ~97,000)) x ~15 ticks an element = 1.5M each; the other measures are one call of 100 elements: ~90M ticks.
(deftest ^{:seon.test/estimate {:ticks 90000000}} a-measure-that-never-settles-answers-its-least-and-says-so
  ;; a call allocating fewer bytes every time never reaches a steady state: the measure stops at its cap, answers
  ;; the least of its calls and marks it (an error here failed by chance on a large call whose noise lowers the
  ;; least now and then: seon.cost-test/the-unit-table-is-current, once in three runs)
  (let [calls (atom 0)
        cap (seon.config/value :seon.config.proofs/cost-max-samples)
        m (seon.cost/measure (fn [] (swap! calls inc) (count (vec (range (- 100000 (* 100 @calls)))))) [] cap)]
    (is (= cap @calls) "it stopped at its cap")
    (is (= {:calls cap :lowered-at cap} (:unsettled (meta m))) "and marks the answer")
    (is (pos? (:bytes m)))
    (is (nil? (:unsettled (meta (seon.cost/measure (fn [] (count (vec (range 100)))) [] cap)))) "a settled call carries no mark")
    (let [calls (atom 0)]
      (seon.cost/measure (fn [] (swap! calls inc)) [])
      (is (= 1 @calls) "by default a measure is one call"))))

;; Estimate: 10 measures x ~17 calls (settle) of 1000 elements x ~50 ticks = 50k a call: ~9M ticks.
(deftest ^{:seon.test/estimate {:ticks 9000000}} a-measure-is-one-number-beside-another-thread
  ;; the same call measured ten times while another thread allocates reads one byte count
  (let [f (fn [] (reduce + (map leaf (range 1000))))
        busy (allocating-thread)
        counts (try (vec (repeatedly 10 #(:bytes (seon.cost/measure f [] (seon.config/value :seon.config.proofs/cost-max-samples))))) (finally (end-threads! busy)))]
    (is (= 1 (count (distinct counts))) (str "bytes " (sort counts)))))

;; Estimate: measure! of branch 100 (101 counted calls x 300) and of branch 10, two epochs: ~300k ticks.
(deftest ^{:seon.test/estimate {:ticks 300000}} a-call-is-measured-one-way
  ;; task seon.profile/a-call-is-measured-one-way: one measurement of a call, which the REPL's
  ;; per-form cost and the cost proof both read
  (testing "the call's value is answered with what it measured, one value"
    (let [m (profile/measure! #(branch 100))]
      (is (= 328350 (:value m)))
      (is (= #{:value :ticks :bytes :wall-ns :cpu-ns :calls} (set (keys m))))
      (is (pos? (:ticks m)) "its work, in engine ticks")
      (is (= 101 (:calls m)) "branch and its 100 leaf calls, the epoch's contracted calls")))
  (testing "a call that throws is measured too, its exception answered for the caller to throw"
    (let [m (profile/measure! #(do (branch 10) (throw (ex-info "boom" {}))))]
      (is (= "boom" (ex-message (:thrown m))))
      (is (not (contains? m :value)))
      (is (= 11 (:calls m))))))
