(ns seon.cost-test
  "Cost proofs (idea 16): the judge's laws on stated measurements, and seon.cost/proof end
  to end on real functions, red when the body does more than the work needs."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [seon.contract :refer [defn defn-]] [seon.config]
            [seon.cost :as cost] [seon.profile :as profile] [datahike.api :as d] [seon.db :as pdb] [clojure.edn :as edn] [clojure.string :as str]
            [seon.agent :as agent] [seon.edn :as sedn]))

;; ---- the judge ------------------------------------------------------------------------

(def ^:private linear
  {:of 'seon.cost-test/one-pass :input 'seon.cost-test/ints :n "integers" :work "one pass" :growth :linear
   :per-n {:ns 100.0 :bytes 50.0 :calls 1.0} :fixed {:calls 1.0} :sizes [100 300 1000 10000]})

(defn- rows
  "Rows of estimate e at its sizes, measured as (m n) per dimension."
  {:malli/schema [:=> [:cat :seon.cost/estimate :seon.host/fn] [:vector :seon.cost/row]]}
  [e m] (vec (for [n (:sizes e)] {:n n :measured (m n) :expected (cost/expected e cost/units n)})))

(deftest a-proofs-sizes-stop-at-the-cap
  (is (= [100 300 1000] (cost/sizes [100 300 1000 10000] 1000)) "the sizes up to the cap")
  (is (= [100 1000 10000] (cost/sizes [10000 100 1000] 100000)) "under the cap: as written, ascending")
  (is (= [4 40 400] (cost/sizes [4 40 400] 1000))))

(deftest work-is-the-growth-at-n
  (is (= [1.0 1000.0 1000000.0] (mapv #(cost/work % 1000) [:constant :linear :quadratic])))
  (is (< 9.96 (cost/work :log 1000) 9.97))
  (is (< 9965.0 (cost/work :n-log-n 1000) 9966.0)))

(deftest a-measure-takes-a-count-of-samples-never-a-time
  ;; task seon.cost/ticks-are-exact: measure repeated a call until 20 ms of CPU time or 25 calls,
  ;; so a fast call took 25 samples and a slow or loaded one 3, and the minimum moved with the
  ;; machine; it takes calls until :seon.config.proofs/cost-settled in a row allocate no fewer bytes than the least,
  ;; whatever a call costs
  (let [n (atom 0)]
    (cost/measure (fn [] (swap! n inc) nil) [])
    (is (<= (inc (seon.config/value :seon.config.proofs/cost-settled)) @n (seon.config/value :seon.config.proofs/cost-max-samples)) "a fast call: a count of calls between the settled count and the cap"))
  (let [n (atom 0)]
    (cost/measure (fn [] (swap! n inc) (reduce + (range 200000))) [])
    (is (<= (inc (seon.config/value :seon.config.proofs/cost-settled)) @n (seon.config/value :seon.config.proofs/cost-max-samples)) "a slow call: the same bounds, whatever it costs")))

(deftest exponent-is-the-log-log-slope
  (is (< 0.99 (cost/exponent [[10 30] [100 300] [1000 3000]]) 1.01))
  (is (< 1.99 (cost/exponent [[10 1] [100 100] [1000 10000]]) 2.01))
  (is (= 0.0 (cost/exponent [[10 5]])) "one point has no slope")
  (is (< 0.99 (cost/exponent [[10 0] [100 100] [1000 1000]]) 1.01) "a zero measure is left out"))

(deftest expected-is-fixed-plus-per-unit-times-work
  (is (= {:ns 100000.0 :bytes 50000.0 :calls 1001.0} (cost/expected linear cost/units 1000)))
  (testing ":ops are priced by the unit table; :per-n wins over them"
    (let [u {:a/op {:ns 10.0 :bytes 4.0 :what "a"}}
          e (assoc linear :ops {:a/op 2.0} :per-n {:calls 1.0})]
      (is (= {:ns 20.0 :bytes 8.0 :calls 1.0} (cost/per-unit e u)))
      (is (= {:ns 200.0 :bytes 80.0 :calls 11.0} (cost/expected e u 10))))))

(deftest within-the-estimate-is-green
  (let [v (cost/judge linear (rows linear (fn [n] {:ns (* 60.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)})))]
    (is (:ok? v))
    (is (= [] (:over v) (:steeper v) (:under v) (:unmeasured v)))))

(deftest a-size-over-its-slack-is-red
  (let [v (cost/judge linear (rows linear (fn [n] {:ns (* 60.0 n) :bytes (* 50.0 n) :calls (+ 2.0 n)})))]
    (is (not (:ok? v)))
    (is (= [:calls] (distinct (map :dim (:over v)))) "calls are exact: one more than stated is over")
    (is (empty? (:steeper v))))
  (testing "wall time over its 3x slack is recorded and shown, never red: it moves with the machine's load"
    (let [v (cost/judge linear (rows linear (fn [n] {:ns (* 310.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)})))]
      (is (:ok? v))
      (is (= [:ns] (distinct (map :dim (:over v)))) "the time over is in the verdict")
      (is (re-find #"TIME OVER \(measured, not red\): ns" (cost/explain linear (rows linear (fn [n] {:ns (* 310.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)})) v))))))

(deftest more-work-with-the-same-bytes-and-calls-is-red-on-ticks
  ;; an extra loop that allocates nothing: bytes and calls cannot see it, CPU time can only
  ;; guess at it under load; ticks count it (pure-tests 2026-10-23: 2.56x ticks, 1.0x bytes)
  (let [e (assoc linear :per-n {:ticks 100.0 :bytes 50.0 :calls 1.0})
        v (cost/judge e (rows e (fn [n] {:ticks (* 400.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)})))]
    (is (not (:ok? v)))
    (is (= [:ticks] (distinct (map :dim (:over v)))))
    (is (re-find #"OVER: ticks" (cost/explain e (rows e (fn [n] {:ticks (* 400.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)})) v)))))

(deftest a-second-pass-is-steeper-even-inside-the-slack
  ;; quadratic bytes that stay under the linear estimate at every size: only the growth sees them
  (let [e (assoc linear :per-n {:bytes 100.0} :fixed {} :sizes [10 100 1000])
        v (cost/judge e (rows e (fn [n] {:bytes (* 0.09 n n)})))]
    (is (empty? (:over v)))
    (is (= [:bytes] (map :dim (:steeper v))))
    (is (< 1.9 (:exponent (first (:steeper v))) 2.1))
    (is (not (:ok? v)))))

;; docs/cost.md#how-growth-is-judged: a flat cost's own variance past its fixed part is no shape (1.91 MB +400 B, +1,424 B read n^1.83 and
;; held a finished agent's merge for 30 turns); a real growth on the same base is still steeper
(deftest a-flat-costs-variance-is-not-growth
  (let [row (fn [n b] {:n n :measured {:bytes b} :expected {:bytes 2000000.0}})
        e (assoc linear :fixed {:bytes 2000000.0} :per-n {:bytes 0.0} :sizes [10 40 80])]
    (is (empty? (:steeper (cost/judge e [(row 10 1912208.0) (row 40 1912608.0) (row 80 1913632.0)]))))
    (is (seq (:steeper (cost/judge e [(row 10 1000000.0) (row 40 1160000.0) (row 80 1640000.0)]))) "growth past the floor is still judged")))

(deftest a-speedup-never-reads-as-steeper
  ;; task seon.cost/a-speedup-never-reads-as-steeper: reach-update on Jolt 0d39edc3, cheaper at
  ;; both sizes than its estimate; with the stated :fixed (420k) taken off, 7k was left at n=100
  ;; and the fit read n^1.96 (two sizes, as a run capped at 1,000 measures)
  (let [e (assoc linear :per-n {:ticks 850.0} :fixed {:ticks 420000.0} :sizes [100 300 1000 10000])
        rs [{:n 100 :measured {:ticks 427200.0} :expected (cost/expected e cost/units 100)}
            {:n 1000 :measured {:ticks 1070000.0} :expected (cost/expected e cost/units 1000)}]]
    (is (empty? (:over (cost/judge e rs))))
    (is (empty? (:steeper (cost/judge e rs))) "under the estimate at both sizes, and linear"))
  (testing "a change that makes every size cheaper by one amount leaves the growth verdict as it was"
    (let [e (assoc linear :per-n {:bytes 100.0} :fixed {:bytes 50000.0} :sizes [10 100 1000])
          at (fn [f] (rows e (fn [n] {:bytes (f n)})))
          quad (fn [n] (+ 50000.0 (* 0.09 n n)))]
      (doseq [d [0.0 20000.0 49000.0]]
        (is (= [:bytes] (map :dim (:steeper (cost/judge e (at #(- (quad %) d))))))
            (str "a second pass, " d " cheaper at every size, is still steeper")))
      (doseq [d [0.0 20000.0 49000.0]]
        (is (empty? (:steeper (cost/judge e (at #(- (+ 50000.0 (* 90.0 %)) d)))))
            (str "one pass, " d " cheaper at every size, is not steeper"))))))

(deftest a-largest-size-within-the-fixed-part-did-not-grow
  ;; docs/cost.md#how-growth-is-judged: seon.vector/vectors-proc at 30, 100 and 300 cost 278k, 299k and
  ;; 251k ticks, the same on every run (where the index's nodes split); less its fixed part the largest
  ;; came out negative, was dropped, and the two sizes left read n^1.93
  (let [e (assoc linear :per-n {:ticks 100.0} :fixed {:ticks 309000.0} :sizes [30 100 300])
        at (fn [ticks] (rows e (fn [n] {:ticks (ticks n)})))]
    (is (empty? (:steeper (cost/judge e (at {30 278000.0 100 299000.0 300 251000.0}))))
        "the largest cost the least, and read on the cost itself")
    (is (= [:ticks] (map :dim (:steeper (cost/judge e (at {30 278000.0 100 299000.0 300 2000000.0})))))
        "the same two sizes and a dear largest is still steeper, so the rule reads the largest, not the bump")))

(deftest growth-takes-the-basis-the-rows-allow
  (let [e (assoc linear :per-n {:bytes 100.0} :fixed {:bytes 50000.0} :sizes [10 100 1000])
        g #(cost/growth %1 (rows %1 (fn [n] {:bytes (%2 n)})) :bytes)]
    (is (= :increments (:basis (g e #(+ 50000.0 (* 90.0 %))))) "three sizes that grow: increments")
    (is (< 0.95 (:exponent (g e #(+ 50000.0 (* 90.0 %)))) 1.05))
    (testing "a smallest size that costs more than the next leaves no two increments: the bounded fixed part judges"
      (let [r (g e #(if (= 10 %) 1000000.0 (+ 50000.0 (* 0.09 % %))))]
        (is (= :bounded-fixed (:basis r)))))
    (testing "growth a small share of the cost: increments would amplify the measure's variance, so the bounded fixed part judges"
      ;; seon.source-test's transact row on main 2026-10-23: 71k of growth on a 406k base read
      ;; n^0.605 against an allowed n^0.601 in one run, n^0.58 in another
      (let [t (assoc linear :growth :log :per-n {:ticks 11500.0} :fixed {:ticks 360000.0} :sizes [10 100 1000])
            r (cost/growth t (rows t (fn [n] {:ticks ({10 406785.0 100 424921.0 1000 479600.0} n)})) :ticks)]
        (is (= :bounded-fixed (:basis r)))
        (is (< (:exponent r) (:allowed r)))))
    (testing "a step in the cost (an index's depth) between two sizes: the flat side's few bytes are no shape"
      ;; seon.program/diff-plan's bytes on Jolt 546c3d1f at 10, 100, 1,000 forms: 738,336, 738,640 and 1,147,520
      ;; (the 304 B over the smallest, read as increments, fitted n^3.1 against the n^0.60 a log allows)
      (let [t (assoc linear :growth :log :per-n {:bytes 85000.0} :fixed {:bytes 480000.0} :sizes [10 100 1000])
            rows' (rows t (fn [n] {:bytes ({10 738336.0 100 738640.0 1000 1147520.0} n)}))]
        (is (empty? (:steeper (cost/judge t rows'))))
        (is (< (:exponent (cost/growth t rows' :bytes)) 0.3))
        ;; main's Jolt 546c3d1f, a step of 3% before the large one: 675.6k, 695.8k, 1.07M
        (let [r2 (rows t (fn [n] {:bytes ({10 675600.0 100 695800.0 1000 1070000.0} n)}))]
          (is (empty? (:steeper (cost/judge t r2)))))
        ;; a real extra pass over the data is still steeper: the same base, 1 KB a form beyond it
        (let [r3 (rows t (fn [n] {:bytes (+ 675600.0 (* 1000.0 n))}))]
          (is (not (:ok? (cost/judge t r3)))))))
    (testing "constant work: its cost itself, which a creep of a few percent barely moves"
      (let [c (assoc linear :growth :constant :per-n {} :fixed {:bytes 1290000.0} :sizes [10 100 1000])
            r (g c {10 1230000.0 100 1290000.0 1000 1430000.0})]
        (is (= :total (:basis r)))
        (is (< (:exponent r) (:allowed r)))))
    (is (nil? (cost/growth e [(first (rows e (fn [n] {:bytes (* 90.0 n)})))] :bytes)) "one size has no growth")))

(deftest one-timer-chunk-is-not-a-red-and-a-real-regression-is
  ;; task seon.cost/ticks-are-exact: one measure of a call's ticks may read one 1,000-tick timer
  ;; chunk more than another on the same input (:seon.config.proofs/tick-quantum)
  (let [q (double (seon.config/value :seon.config.proofs/tick-quantum))
        e (assoc linear :per-n {:ticks 100.0} :fixed {} :sizes [100 300 1000 10000])
        x (fn [n] (:ticks (cost/expected e cost/units n)))
        at (fn [f] (rows e (fn [n] {:ticks (f n)})))
        bound (fn [n] (* (cost/slack :ticks) (x n)))]
    (is (:ok? (cost/judge e (at #(if (= % 1000) (+ (bound %) q -1.0) (x %)))))
        "a size over its bound by less than one chunk is not over (red without the quantum)")
    (is (not (:ok? (cost/judge e (at #(if (= % 1000) (+ (bound %) q 1.0) (x %))))))
        "past the bound by more than one chunk it is over")
    (is (not (:ok? (cost/judge e (at #(* 2.0 (bound %)))))) "every size twice its bound is red")
    (is (seq (:steeper (cost/judge e (at #(* 0.5 (x %) (/ % 100.0)))))) "a second pass, quadratic in n, is still steeper")))

(deftest n-log-n-under-a-linear-estimate-is-not-steeper
  (let [e (assoc linear :per-n {:ns 1000.0} :fixed {})]
    (is (empty? (:steeper (cost/judge e (rows e (fn [n] {:ns (cost/work :n-log-n n)}))))))))

(deftest an-estimate-of-time-alone-is-red-it-could-not-fail
  ;; time is shown, never red: an estimate stating only :ns judged nothing (review 2026-10-23)
  (let [e (assoc linear :per-n {:ns 100.0} :fixed {})
        v (cost/judge e (rows e (fn [n] {:ns (* 60.0 n) :bytes (* 50.0 n)})))]
    (is (not (:ok? v)))
    (is (:uncounted v))
    (is (re-find #"NO COUNTED DIMENSION" (cost/explain e (rows e (fn [n] {:ns (* 60.0 n) :bytes (* 50.0 n)})) v))))
  (is (:ok? (cost/judge linear (rows linear (fn [n] {:ns (* 60.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)}))))
      "with bytes and calls stated and measured, it judges"))

(deftest a-loose-estimate-is-a-finding-not-red
  (let [v (cost/judge linear (rows linear (fn [n] {:ns (* 10.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)})))]
    (is (:ok? v))
    (is (= [{:dim :ns :n 10000 :ratio 0.1}] (mapv #(update % :ratio (fn [r] (/ (Math/round (* r 100)) 100.0))) (:under v))))))

(deftest a-stated-dimension-never-measured-is-named
  (let [v (cost/judge linear (rows linear (fn [n] {:ns (* 60.0 n) :bytes (* 50.0 n)})))]
    (is (= [:calls] (:unmeasured v)))))

;; ---- proofs end to end ----------------------------------------------------------------

(defn- seen
  "One contracted call per element: the unit the call counts below see."
  {:malli/schema [:=> [:cat :map :int] :map]}
  [m x] (assoc m x true))

(defn ints
  "The input of size n: n distinct integers."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :int]]]}
  [n] [(vec (range n))])

(defn one-pass
  "The distinct integers of xs as a set of keys: one pass, one call of seen each."
  {:malli/schema [:=> [:cat [:vector :int]] :map]}
  [xs] (reduce seen {} xs))

(defn rescanning
  "The same answer, but each element first scans the elements before it: a pass per element."
  {:malli/schema [:=> [:cat [:vector :int]] :map]}
  [xs] (reduce (fn [m i] (if (some #(= % (nth xs i)) (subvec xs 0 i)) m (seen m (nth xs i)))) {} (range (count xs))))

(def ^:private estimate
  {:of 'seon.cost-test/one-pass :input 'seon.cost-test/ints :n "integers in the input"
   :work "one pass: per integer one seen call (one persistent assoc)" :growth :linear
   :ops {:seq/reduce 1.0 :fn/armed-call 1.0 :map/assoc 1.0} :per-n {:calls 1.0} :fixed {:calls 1.0}
   :sizes [100 300 1000 10000]})

(deftest a-one-pass-function-meets-its-estimate
  (let [{:keys [rows verdict]} (cost/run estimate)]
    (is (= (cost/sizes (:sizes estimate) (seon.config/value :seon.config.proofs/cost-max-n)) (mapv :n rows)))
    (when seon.contract/on?
      (is (= (mapv #(double (inc %)) (mapv :n rows)) (mapv #(get-in % [:measured :calls]) rows)) "calls are exact: n + 1"))
    (is (:ok? verdict) (cost/explain estimate rows verdict))))

(deftest a-scan-inside-the-loop-is-red-and-says-why
  (let [e (assoc estimate :of 'seon.cost-test/rescanning)
        {:keys [rows verdict]} (cost/run e)
        text (cost/explain e rows verdict)]
    (is (not (:ok? verdict)))
    (is (< (count rows) 3) "it stops at the first size over its estimate")
    (is (re-find #"OVER: (bytes|calls)" text) "red on an exact dimension (time is shown, never red)")
    (is (re-find #"one pass" text) "the explanation restates the work the data needs")))

;; ---- a bound in the contract, checked on the contract's own generator -----------------------

(defn one-pass-bounded
  "one-pass, its contract carrying its bound: one call of itself and one of seen an element; the
  call's own bytes (an empty vector measured 272 B) and an assoc's an element (the unit table's
  :map/assoc, 825 B)."
  {:malli/schema [:=> [:cat [:vector :int]] :map]
   :seon.cost/bound (fn [xs] {:calls (inc (count xs)) :bytes (+ 400.0 (* 825.0 (count xs))) :out {:keys (count xs)}})}
  [xs] (reduce seen {} xs))

(defn rescanning-bounded
  "rescanning, under one-pass's bound: a pass per element."
  {:malli/schema [:=> [:cat [:vector :int]] :map]
   :seon.cost/bound (fn [xs] {:calls (inc (count xs)) :bytes (+ 400.0 (* 825.0 (count xs))) :out {:keys (count xs)}})}
  [xs] (rescanning xs))

(def ^:private generated
  {:of 'seon.cost-test/one-pass-bounded :n "a generated vector of integers (the contract's own generator)"
   :work "one pass: per integer one seen call (one persistent assoc)" :cases 30})

(deftest a-bound-holds-on-its-contracts-generated-inputs
  ;; task seon.cost/a-contract-carries-its-cost-bound: the bound is the contract's, evaluated on the
  ;; same arguments the function is measured on; no hand-picked sizes
  (let [{:keys [rows verdict]} (cost/run generated)]
    (is (= 30 (count rows)) "every generated case measured")
    (is (every? #(= (get-in % [:expected :calls]) (inc (count (first (:args %))))) rows) "each row's estimate is the bound on that row's arguments")
    (when seon.contract/on?
      (is (every? #(== (get-in % [:measured :calls]) (get-in % [:expected :calls])) rows) "calls are exact: the bound's count"))
    (is (:ok? verdict) (cost/explain generated rows verdict))))

(deftest a-cost-reports-line-reads-back-whole
  ;; task seon.agent/a-test-childs-line-always-reads: a test child streams each test's reports to
  ;; its agent as one printed line (seon.agent/run-tests-forked). A generated case's row kept its
  ;; arguments as data, and an argument that is not EDN (an exception prints as #error {\n ...)
  ;; split the line, so the agent's read met EOF and the agent died (0.7's seon_done, exit 70).
  ;; The report keeps what crosses as text: the case's arguments are in the proof's message
  (let [{:keys [rows verdict]} (cost/run generated)
        rows (mapv #(assoc % :args [(ex-info "an argument that is no EDN" {:a 1}) #'clojure.core/inc]) rows)
        rep (agent/test-report {:type :fail :message (cost/explain generated rows verdict) :actual {:rows rows :verdict verdict}}
                               "#'seon.cost-test/t")
        line (sedn/encode {:seon.test/ended "#'seon.cost-test/t" :reports [rep]})]
    (is (:cost rep) "the proof's rows and verdict are kept as data")
    (is (not (str/includes? line "\n")) "the line is one line")
    (is (= [rep] (:reports (sedn/decode line))) "and it reads back whole")))

(deftest a-scan-inside-the-loop-is-red-under-the-bound
  (let [e (assoc generated :of 'seon.cost-test/rescanning-bounded)
        {:keys [rows verdict]} (cost/run e)
        text (cost/explain e rows verdict)]
    (is (not (:ok? verdict)) text)
    (is (re-find #"OVER: bytes" text) "red on an exact dimension")
    (is (re-find #"\[" text) "the case that went over is named by its arguments")))

;; ---- the unit table's bench (seon.cost/units is these, divided by n) ---------------------

(defn- unit-inc "A contracted function of one int, for the :fn/armed-call unit."
  {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))

(defn- repl-defined
  "Evaluate forms in seon.cost.unit, as a REPL would, and answer the function named sym there."
  {:malli/schema [:=> [:cat :any :symbol] :seon.host/fn]}
  [forms sym]
  (binding [*ns* (create-ns 'seon.cost.unit)]
    (refer-clojure)
    (eval forms)
    @(ns-resolve 'seon.cost.unit sym)))

(defn- scrambled
  "0..n-1 in a scrambled order that is the same in every run (a multiplicative hash mod a
  prime, as sort keys): a cost measured on it is a function of n, never of a clock-seeded
  shuffle."
  [n] (vec (sort-by #(mod (* (inc %) 2654435761) 4294967291) (range n))))

(defn- notes-db
  "n notes, each with :note/text and :note/agent, on a branch of the seeded test store, and their
  entity ids in index order: the one input of both pull units (seon.test-store/test-branch!: the
  two index levels real dbs have)."
  [n]
  (ts/test-branch! (fn [_ conn]
                     (d/transact conn (vec (for [i (range n)] {:note/text (str "n" i) :note/agent :u})))
                     [(d/db conn) (mapv :e (d/datoms (d/db conn) :aevt :note/text))])))

(def unit-benches
  "How each unit operation is measured: (f n) does n units on (setup n). The table below
  is these, divided by n; the-unit-table-is-current measures them again."
  {:seq/reduce {:what "one step of reduce over a vector" :setup #(vector (vec (range %))) :f (fn [v] (reduce + 0 v))}
   ;; defined by eval, as an agent's REPL defines code (a release build direct-links its own
   ;; calls: 2.3 ns there, against 7.4 for code defined at the REPL)
   :fn/call {:what "a reduce step calling one plain function, both defined at the REPL"
             :setup (fn [n] [(vec (range n)) (repl-defined '(do (defn f [x] (inc x)) (defn g [v] (reduce (fn [a x] (f a)) 0 v))) 'g)])
             :f (fn [v g] (g v))}
   :fn/armed-call {:what "a reduce step calling one contracted function ([:=> [:cat :int] :int])" :setup #(vector (vec (range %)))
                   :f (fn [v] (reduce (fn [a x] (unit-inc a)) 0 v))}
   :vec/conj {:what "conj onto a persistent vector" :setup #(vector (vec (range %))) :f (fn [v] (reduce conj [] v))}
   :vec/conj! {:what "conj! onto a transient vector" :setup #(vector (vec (range %))) :f (fn [v] (persistent! (reduce conj! (transient []) v)))}
   :vec/nth {:what "nth of a vector" :setup #(vector (vec (range %))) :f (fn [v] (reduce (fn [a i] (+ a (nth v i))) 0 v))}
   :map/get {:what "get of an integer key in a hash map of n" :setup (fn [n] [(vec (range n)) (zipmap (range n) (range n))])
             :f (fn [v m] (reduce (fn [a k] (+ a (get m k))) 0 v))}
   :map/assoc {:what "assoc of a new integer key into a persistent hash map" :setup #(vector (vec (range %)))
               :f (fn [v] (reduce (fn [m k] (assoc m k k)) {} v))}
   :map/assoc! {:what "assoc! of a new integer key into a transient hash map" :setup #(vector (vec (range %)))
                :f (fn [v] (persistent! (reduce (fn [m k] (assoc! m k k)) (transient {}) v)))}
   :map/update {:what "update of a counter in a hash map (frequencies' step)" :setup #(vector (vec (map (fn [i] (mod i 64)) (range %))))
                :f (fn [v] (reduce (fn [m k] (update m k (fnil inc 0))) {} v))}
   :set/conj! {:what "conj! of a new integer into a transient hash set" :setup #(vector (vec (range %)))
               :f (fn [v] (persistent! (reduce conj! (transient #{}) v)))}
   :sorted/assoc {:what "assoc of a new integer key into a sorted map" :setup #(vector (scrambled %))
                  :f (fn [v] (reduce (fn [m k] (assoc m k k)) (sorted-map) v))}
   :sort/compare {:what "sort of shuffled integers, per n log2 n" :setup #(vector (scrambled %)) :f (fn [v] (sort v))
                  :per :n-log-n}
   :str/seq-char {:what "one step of a seq over a String's chars (doseq, reduce)" :setup #(vector (apply str (repeat % \a)))
                  :f (fn [s] (reduce (fn [n c] (inc n)) 0 s))}
   :str/escape {:what "clojure.string/escape of a text by char-escape-string, newlines kept, per char"
                :setup (fn [n] [(subs (str/join "\n" (repeat (inc (quot n 40)) "line 12 of a long log, as one prints it")) 0 n)])
                :f (fn [s] (str/escape s #(when-not (= \newline %) (char-escape-string %))))}
   :str/append {:what "StringBuilder append of one char" :setup #(vector (apply str (repeat % \a)))
                :f (fn [s] (let [sb (StringBuilder.) n (count s)] (loop [i 0] (when (< i n) (.append sb (.charAt ^String s i)) (recur (inc i)))) (.toString sb)))}
   :map/literal {:what "a small map literal of 5 keys built per element" :setup #(vector (vec (range %)))
                 :f (fn [v] (reduce (fn [a x] {:kind :leaf :n x :w a :f 1 :u 2}) nil v))}
   :map/seq-step {:what "one step of reduce over a hash map's entries (each a map entry)" :setup #(vector (zipmap (range %) (range %)))
                  :f (fn [m] (reduce (fn [a e] (+ a (val e))) 0 m))}
   :map/reduce-kv {:what "one step of reduce-kv over a hash map" :setup #(vector (zipmap (range %) (range %)))
                   :f (fn [m] (reduce-kv (fn [a k v] (+ a v)) 0 m))}
   :edn/read-char {:what "clojure.edn/read-string, per character of a vector of integers" :setup #(vector (pr-str (vec (range (quot % 6)))))
                   :f (fn [s] (edn/read-string s)) :per-char true}
   :pr-str/char {:what "pr-str, per character printed (a vector of integers)" :setup #(vector (vec (range (quot % 6))))
                 :f (fn [v] (pr-str v)) :per-char true}
   ;; a pull from a branch of the in-memory test store (session D2: every read of facts is pulls)
   :db/pull {:what "d/pull of one attribute of an entity, by entity id (per pull)" :setup notes-db
             :f (fn [db es] (reduce (fn [a e] (d/pull db [:note/text] e) a) 0 es))}
   ;; the same db and entities, one attribute more: the difference is the attribute alone
   :db/pull-attr {:what "each attribute of a d/pull beyond its first (per attribute)" :setup notes-db
                  :f (fn [db es] (reduce (fn [a e] (d/pull db [:note/text :note/agent] e) a) 0 es))
                  :minus :db/pull}})

(defn measure-unit
  "Unit k measured at n units: {:ticks :ns :bytes} per unit. A unit stated as the difference from
  another (:minus) measures both on the one input, the second right after the first, so the db,
  its entities and every index under them are the same for both and only the difference remains."
  {:malli/schema [:=> [:cat :keyword pos-int?] [:map [:ticks :double] [:ns :double] [:bytes :double]]]}
  [k n]
  (let [{:keys [setup f per per-char minus]} (unit-benches k)
        args (setup n)
        units (cond per-char (double (count (if (string? (first args)) (first args) (pr-str (first args)))))
                    per (cost/work per n) :else (double n))
        m (cost/measure f args)
        b (when minus (cost/measure (:f (unit-benches minus)) args))]
    {:ticks (/ (- (:ticks m) (:ticks b 0.0)) units) :ns (/ (- (:ns m) (:ns b 0.0)) units) :bytes (/ (- (:bytes m) (:bytes b 0.0)) units)}))

(deftest the-unit-table-is-current
  ;; every row of the table measured again, at the n where its row is steady: 2k units, but 10k for
  ;; the rows whose unit differs below it (:vec/conj!, :pr-str/char: 34 against 43 B, 56 against 43 ticks
  ;; per char at 2k; :map/assoc and :sorted/assoc: 2k), 1k for the rest, and 30 for the two pull rows, whose
  ;; ticks and bytes are the same at 30, 300 and 1k (5.4k ticks a pull): its bytes within 1.25x
  ;; (counted, the same at any load); its ticks too, the work done; a drifted row means the estimates
  ;; priced by it are stale. Its ns (CPU time) no verdict decides on (seon.cost/exact): measured,
  ;; not asserted (pure-tests 2026-10-23: within 3x of a table measured under another day's load
  ;; was no function of the digest). Measured at 10k (pulls 1k) the test cost 5.3 G ticks, at these n 0.15 G.
  (doseq [[k {:keys [ticks bytes]}] cost/units
          :let [m (measure-unit k (cond (#{:db/pull :db/pull-attr} k) 30 (#{:vec/conj! :pr-str/char} k) 10000 (#{:map/assoc :sorted/assoc} k) 2000 :else 1000)) row (assoc m :what (:what (unit-benches k)))]]
    (is (<= (/ ticks 1.25) (:ticks m) (* ticks 1.25)) (str k " ticks: table " ticks ", measured " (:ticks m) "; the row now: " row))
    (is (<= (- (/ bytes 1.25) 1.0) (:bytes m) (+ (* bytes 1.25) 1.0)) (str k " bytes: table " bytes ", measured " (:bytes m) "; the row now: " row))))

(deftest a-unit-measures-the-same-across-runs
  ;; :db/pull-attr read 11.6 KB on one run and 8.9 KB on the next two (a warm or cold Datahike
  ;; cache at its start); each measurement here builds its own db, and the counted dimensions
  ;; agree to 1%, so the row does not depend on the cache's state when it began
  (let [ms (vec (repeatedly 3 #(measure-unit :db/pull-attr 30)))
        near (fn [d] (let [vs (map d ms)] (<= (apply max vs) (* 1.01 (apply min vs)))))]
    (is (near :bytes) (str "bytes differ across runs: " (mapv :bytes ms)))
    (is (near :ticks) (str "ticks differ across runs: " (mapv :ticks ms)))))

(deftest an-open-overrun-is-accepted-until-it-regresses
  ;; 200 bytes per integer against a 50 byte estimate: 4x, over the 1.25x slack (2e6 bytes at n=10000)
  (let [m (fn [n] {:ns (* 60.0 n) :bytes (* 200.0 n) :calls (+ 1.0 n)})]
    (testing "within the ratchet (1.5x) of what was measured when its task was filed: green, still reported"
      (let [e (assoc linear :overrun {:task "seon.cost/demo-overrun" :measured {:bytes 1500000.0}})
            v (cost/judge e (rows e m))]
        (is (:ok? v))
        (is (= "seon.cost/demo-overrun" (get-in v [:accepted :task])))
        (is (seq (get-in v [:accepted :over])))
        (is (re-find #"OVERRUN accepted while task seon.cost/demo-overrun" (cost/explain e (rows e m) v)))))
    (testing "past the ratchet: a regression, red"
      (let [e (assoc linear :overrun {:task "seon.cost/demo-overrun" :measured {:bytes 1000000.0}})
            v (cost/judge e (rows e m))]
        (is (not (:ok? v)))
        (is (re-find #"REGRESSION" (cost/explain e (rows e m) v)))))))

(deftest a-proof-the-cap-leaves-too-few-sizes-is-refused
  ;; root, after [10 1000 2000] measured two points at the cap and said green as if three: a
  ;; normal run measures up to :seon.config.proofs/cost-max-n, so a proof it leaves fewer than
  ;; three sizes is refused, naming them and the key, and measures nothing; one with three
  ;; names the sizes it left out past the cap on its verdict
  (let [cap (seon.config/value :seon.config.proofs/cost-max-n)
        r (cost/run (assoc estimate :sizes [100 cap (* 10 cap)]))]
    (is (= :seon.cost/too-few-sizes (:seon.error/type r)) (pr-str r))
    (is (= [100 cap (* 10 cap)] (:sizes r)))
    (is (re-find #"cost-max-n" (:seon.error/message r)) "naming the key"))
  (let [cap (seon.config/value :seon.config.proofs/cost-max-n)
        r (cost/run (assoc estimate :sizes [10 100 cap (* 10 cap)]))]
    (is (= [(* 10 cap)] (:left-out r)) "the size past the cap, named")
    (is (= [10 100 cap] (mapv :n (:rows r))) "and three measured")))
