(ns seon.profile-test
  "seon.profile: every contracted call counted exactly, one in 64 timed, read per epoch."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [seon.contract :as contract :refer [defn defn-]]
            [seon.profile :as profile] [seon.cost] [seon.repl :as repl] [seon.agent :as agent] [jolt.ffi :as ffi]))

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
        (is (every? #(>= % 0.0) (vals by)) "a call shorter than a clock read reads as 0, never negative")))
    (testing "the estimate (one call in 64 timed) is within 2x of the exact time in a loop (probe: 12-30% high)"
      (profile/begin!)
      (let [t0 (profile/now-ns) _ (dotimes [i 64000] (leaf i)) exact (/ (- (profile/now-ns) t0) 1e6)
            est (:ms (first (profile/touched)))]
        (is (<= (/ exact 2) est (* exact 2)) (str "exact " exact " ms, estimated " est " ms"))))))

(deftest top-orders-by-time-then-calls
  (is (= '[c a b] (mapv :var (profile/top [{:var 'a :calls 9 :ms 1.0} {:var 'b :calls 1 :ms 1.0} {:var 'c :calls 1 :ms 2.0}] 3))))
  (is (= 1 (count (profile/top [{:var 'a :calls 9 :ms 1.0} {:var 'b :calls 1 :ms 1.0}] 1)))))

(deftest an-evals-record-carries-its-costliest-functions
  (when contract/on?
    ;; the REPL's own handler in this process, as a scratch session (not agent mode: nothing is transacted)
    (let [answers (volatile! [])
          old @agent/state]
      (reset! agent/state {:n 0 :ns (the-ns 'user)})
      (try ((repl/handler) {"op" "eval" "code" "(seon.profile-test/branch 300)" :reply #(vswap! answers conj %)})
           (finally (reset! agent/state old)))
      (let [items (repl/fold @answers)
            fns (:fns (first items))]
        (is (= "8955050" (:printed (first items))) (pr-str items))
        (is (= {'seon.profile-test/branch 1 'seon.profile-test/leaf 300}
               (into {} (map (juxt :var :calls)) fns)))
        (is (= fns (:fns (:seon.cluster.eval/record (first (repl/record-tx :a "r" items nil))))))))))

;; the system's clock read directly (macOS clock_gettime_nsec_np, CLOCK_UPTIME_RAW = 8), the
;; one every process on the machine reads
(ffi/defcfn ^:private uptime-raw "clock_gettime_nsec_np" [:int] :long)

(deftest the-clock-is-the-one-every-process-reads
  (testing "profile/now-ns is the machine's CLOCK_UPTIME_RAW, not a count from this process's start"
    (let [a (uptime-raw 8) t (profile/now-ns) b (uptime-raw 8)]
      (is (<= a t b) (str "system " a " .. " b ", profile/now-ns " t))))
  (testing "it resolves below a microsecond (the least step between two different reads)"
    (let [step (apply min (for [_ (range 1000) :let [a (profile/now-ns) b (loop [b (profile/now-ns)] (if (= a b) (recur (profile/now-ns)) b))]] (- b a)))]
      (is (< step 1000) (str "least step " step " ns")))))

(deftest reading-the-clock-allocates-nothing
  (let [n 10000
        bytes (fn [f] (let [a0 (profile/alloc-bytes)] (f) (- (profile/alloc-bytes) a0)))
        empty-loop (bytes #(dotimes [_ n] nil))
        reads (bytes #(dotimes [_ n] (profile/now-ns)))]
    (is (<= (- reads empty-loop) 1024) (str n " reads allocated " reads " bytes, an empty loop " empty-loop))))

(defn- allocating-thread
  "A thread allocating until stop is set (a vector of 100 longs, again and again). Answers [thread stop]."
  []
  (let [stop (atom false)
        t (Thread. ^Runnable (fn [] (loop [] (when-not @stop (dotimes [_ 1000] (vec (range 100))) (recur)))))]
    (.start t)
    [t stop]))

(deftest a-threads-allocation-is-its-own
  ;; task seon.cost/a-proof-measures-only-its-own-thread: alloc-bytes counted the whole
  ;; process (1,712 B idle against 1.4 GB in 200 ms while another thread allocated), so a cost
  ;; proof read other threads' work as its own. Now it is the calling thread's count alone.
  (let [window #(let [a0 (profile/alloc-bytes) _ (Thread/sleep 200)] (- (profile/alloc-bytes) a0))]
    (is (< (window) 65536) "idle, the caller allocates next to nothing")
    (let [[t stop] (allocating-thread)]
      (try (Thread/sleep 50)
           (let [w (window)] (is (< w 65536) (str "another thread allocating moves the caller's count by " w " B")))
           (finally (reset! stop true) (.join ^Thread t))))
    (let [a0 (profile/alloc-bytes) b (byte-array 1000000) a1 (profile/alloc-bytes)]
      (is (<= 1000000 (- a1 a0) 1100000) (str "the caller's own " (count b) " bytes counted as " (- a1 a0))))))

(deftest a-measure-is-the-same-beside-another-thread
  ;; the measured call's bytes are the same alone and while another thread allocates
  (let [f (fn [] (reduce + (map leaf (range 1000))))
        alone (seon.cost/measure f [])
        [t stop] (allocating-thread)
        beside (try (Thread/sleep 50) (seon.cost/measure f []) (finally (reset! stop true) (.join ^Thread t)))]
    (is (<= (:bytes beside) (* 1.25 (max 1.0 (:bytes alone)))) (str "alone " alone ", beside " beside))))
