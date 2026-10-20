(ns seon.cost
  "Idea 16, for every task: estimate first, and an overrun is a failing proof.

  A cost proof is an ordinary deftest that calls `proof` with an estimate (the
  :seon.cost/estimate schema), so it sits in a task's :seon.task/proofs beside the
  behaviour proofs and the done rule needs nothing new: every proof green. The estimate is
  written before the code, from what the work must do with the data:

    (deftest index-by-is-one-pass
      (cost/proof {:of 'app.rows/index-by :input 'app.rows-test/rows
                   :n \"rows in the input\"
                   :work \"one pass: per row, one key lookup and one transient assoc!\"
                   :growth :linear
                   :ops {:seq/reduce 1 :map/get 1 :map/assoc! 1}
                   :per-n {:calls 1.0} :fixed {:calls 1.0}
                   :sizes [1000 10000 100000]}))

  - :n says what N counts; :work is the floor: the passes over the data and what each
    element needs. It is the sentence an agent reads before writing code.
  - :growth is how the least work grows, W(n). Expected cost at n = fixed + per-n x W(n).
  - :ops derive per-n from `units`, the measured costs of unit operations on this runtime,
    so an estimate is derived, never copied from a run; :per-n states costs directly
    (:calls: contracted calls per unit of W(n), exact).
  - :input names (fn [n] args): the input is built outside the measurement.

  `run` measures the function at each size, ascending: allocated bytes and calls of
  contracted functions (seon.profile) are exact, so they are judged tightly; wall ns is the
  minimum over repeats, judged at 3x (the machine is shared). It stops at the first size
  over its estimate, so a quadratic body under a linear estimate costs one size too many,
  not hours. `judge` is pure: over (a size above slack x estimate), steeper (a dimension
  grew faster than :growth allows over the sizes: the extra pass, the scan inside a loop),
  under (a third of the estimate or less at the largest size: the estimate is loose, a
  finding, not red), unmeasured (a stated dimension with nothing measured)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.profile :as profile]
            [clojure.test :as t] [clojure.string :as str] [clojure.edn :as edn]))

;; ---- the judge (pure) -----------------------------------------------------------------

(def slack
  "Each dimension is red above this multiple of its estimate. Bytes and calls are exact on
  one thread (allocation is counted, not sampled); wall time moves with the machine's load."
  {:ns 3.0 :bytes 1.25 :calls 1.0})

(def steeper-by
  "A dimension is steeper when its fitted exponent exceeds its growth's own exponent over
  the same sizes by more than this: n log n under a linear estimate (+0.17 over 100x)
  passes, a second loop over the data (+1.0) does not."
  0.3)

(def loose
  "At or below this fraction of its estimate at the largest size, the estimate is loose."
  (/ 1.0 3.0))

(defn work
  "W(n), the least work's growth at n."
  {:malli/schema [:=> [:cat :seon.cost/growth pos-int?] [:double {:min 0.0}]]}
  [growth n]
  (let [n (double n) lg (/ (Math/log (max n 2.0)) (Math/log 2.0))]
    (case growth :constant 1.0 :log lg :linear n :n-log-n (* n lg) :quadratic (* n n) :cubic (* n n n))))

(defn exponent
  "The least-squares slope of log y over log x: how y grows with x (1.0 linear, 2.0
  quadratic). Points with x or y not positive are left out; 0.0 under two points."
  {:malli/schema [:=> [:cat [:sequential [:tuple number? number?]]] :double]}
  [points]
  (let [ps (vec (for [[x y] points :when (and (pos? x) (pos? y))] [(Math/log (double x)) (Math/log (double y))]))
        k (count ps)]
    (if (< k 2)
      0.0
      (let [mx (/ (reduce + (map first ps)) k) my (/ (reduce + (map second ps)) k)
            sxx (reduce + (for [[x _] ps] (* (- x mx) (- x mx))))
            sxy (reduce + (for [[x y] ps] (* (- x mx) (- y my))))]
        (if (zero? sxx) 0.0 (double (/ sxy sxx)))))))

(defn per-unit
  "What one unit of W(n) costs under estimate e: its :ops priced by the unit table u,
  overridden by what :per-n states."
  {:malli/schema [:=> [:cat :seon.cost/estimate [:map-of :keyword :seon.cost/unit]] :seon.cost/dims]}
  [e u]
  (let [derived (reduce (fn [acc [op k]]
                          (if-let [{:keys [ns bytes]} (get u op)]
                            (-> acc (update :ns (fnil + 0.0) (* k ns)) (update :bytes (fnil + 0.0) (* k bytes)))
                            acc))
                        {} (:ops e))]
    (merge derived (:per-n e))))

(defn expected
  "What estimate e allows at n, per dimension it states: fixed + per-unit x W(n)."
  {:malli/schema [:=> [:cat :seon.cost/estimate [:map-of :keyword :seon.cost/unit] pos-int?] :seon.cost/dims]}
  [e u n]
  (let [p (per-unit e u) f (:fixed e {}) w (work (:growth e) n)]
    (into {} (for [d [:ns :bytes :calls] :when (or (contains? p d) (contains? f d))]
               [d (+ (double (get f d 0.0)) (* w (double (get p d 0.0))))]))))

(defn judge
  "The verdict on rows (ascending sizes, each measured and expected) under estimate e."
  {:malli/schema [:=> [:cat :seon.cost/estimate [:vector :seon.cost/row]] :seon.cost/verdict]}
  [e rows]
  (let [dims (into #{} (mapcat (comp keys :expected)) rows)
        ratio (fn [{:keys [measured expected]} d]
                (let [m (get measured d) x (get expected d)]
                  (when (and (some? m) (some? x)) (if (pos? x) (/ (double m) x) (if (pos? m) Double/POSITIVE_INFINITY 1.0)))))
        over (vec (for [r rows d [:ns :bytes :calls] :when (dims d)
                        :let [q (ratio r d)] :when (and q (> q (slack d)))]
                    {:dim d :n (:n r) :ratio q}))
        fixed (:fixed e {})
        allowed (+ steeper-by (exponent (for [r rows] [(:n r) (work (:growth e) (:n r))])))
        steeper (vec (for [d [:ns :bytes :calls] :when (dims d)
                           :let [pts (for [r rows :let [m (get-in r [:measured d])] :when m] [(:n r) (- m (get fixed d 0.0))])]
                           :when (>= (count pts) 2)
                           :let [x (exponent pts)] :when (> x allowed)]
                       {:dim d :exponent x :allowed allowed}))
        largest (peek rows)
        under (vec (for [d [:ns :bytes :calls] :when (dims d)
                         :let [q (when largest (ratio largest d))] :when (and q (< q loose))]
                     {:dim d :n (:n largest) :ratio q}))
        unmeasured (vec (for [d [:ns :bytes :calls] :when (and (dims d) (seq rows) (every? #(nil? (get-in % [:measured d])) rows))] d))]
    {:ok? (and (empty? over) (empty? steeper)) :over over :steeper steeper :under under :unmeasured unmeasured}))

;; ---- measuring ----------------------------------------------------------------------------

(defn- once
  "One call of f on args: its wall ns, allocated bytes and contracted calls (calls only
  when the contracts are on: seon.profile counts armed functions)."
  {:malli/schema [:=> [:cat :seon.host/fn [:sequential :any]] :seon.cost/dims]}
  [f args]
  (let [a0 (profile/alloc-bytes) _ (profile/begin!) t0 (profile/now-ns)
        _ (apply f args)
        t1 (profile/now-ns) c (profile/calls) a1 (profile/alloc-bytes)]
    (cond-> {:ns (double (- t1 t0)) :bytes (double (- a1 a0))}
      contract/on? (assoc :calls (double c)))))

(def ^:private baseline
  "What measuring a call that does nothing costs: the clock and allocation reads themselves."
  (delay (let [f (fn [] nil)]
           (reduce (fn [acc m] (merge-with min acc m)) (repeatedly 5 #(once f []))))))

(defn measure
  "f on args, measured: one warm call, then repeats until 20 ms or 25 calls (3 at least);
  each dimension's minimum, less what measuring itself costs."
  {:malli/schema [:=> [:cat :seon.host/fn [:sequential :any]] :seon.cost/dims]}
  [f args]
  (once f args)
  (let [b @baseline]
    (loop [k 0 spent 0.0 best nil]
      (if (and (>= k 3) (or (>= spent 2e7) (>= k 25)))
        (into {} (for [[d v] best] [d (max 0.0 (- v (if (= d :calls) 0.0 (get b d 0.0))))]))
        (let [m (once f args)]
          (recur (inc k) (+ spent (:ns m)) (if best (merge-with min best m) m)))))))

(declare units)

(defn run
  "Estimate e measured: its function on its input at each size, ascending, stopping after
  the first size over its estimate. Answers {:rows :verdict}."
  {:malli/schema [:=> [:cat :seon.cost/estimate] [:map [:rows [:vector :seon.cost/row]] [:verdict :seon.cost/verdict]]]}
  [e]
  (let [f @(requiring-resolve (:of e)) input @(requiring-resolve (:input e))]
    (loop [[n & more] (sort (:sizes e)) rows []]
      (if (nil? n)
        {:rows rows :verdict (judge e rows)}
        (let [row {:n n :measured (measure f (vec (input n))) :expected (expected e units n)}
              rows (conj rows row)]
          (if (seq (:over (judge e [row])))
            {:rows rows :verdict (judge e rows)}
            (recur more rows)))))))

;; ---- the proof ----------------------------------------------------------------------------

(defn- num-text {:malli/schema [:=> [:cat [:maybe number?]] :string]}
  [x]
  (cond (nil? x) "-"
        (>= x 1e9) (format "%.2fG" (/ x 1e9)) (>= x 1e6) (format "%.2fM" (/ x 1e6))
        (>= x 1e4) (format "%.1fk" (/ x 1e3)) :else (format "%.0f" (double x))))

(defn explain
  "The estimate, the measurements against it and the verdict, as text an agent reads."
  {:malli/schema [:=> [:cat :seon.cost/estimate [:vector :seon.cost/row] :seon.cost/verdict] :string]}
  [e rows v]
  (let [dims (filter (set (mapcat (comp keys :expected) rows)) [:ns :bytes :calls])]
    (str/join "\n"
      (concat
        [(str (:of e) ", N = " (:n e) ". The work: " (:work e) " (" (name (:growth e)) ").")
         (str "n: " (str/join "  " (for [d dims] (str (name d) " measured/estimate (x)"))))]
        (for [{:keys [n measured expected]} rows]
          (str n ": " (str/join "  " (for [d dims :let [m (get measured d) x (get expected d)]]
                                       (str (num-text m) "/" (num-text x)
                                            (when (and m (pos? x)) (format " (%.2fx)" (/ m x))))))))
        (for [{:keys [dim n ratio]} (:over v)]
          (str "OVER: " (name dim) " at n=" n " is " (format "%.2f" ratio) "x its estimate (allowed " (slack dim) "x)."))
        (for [{:keys [dim exponent allowed]} (:steeper v)]
          (str "STEEPER: " (name dim) " grew as n^" (format "%.2f" exponent) "; " (name (:growth e))
               " allows n^" (format "%.2f" allowed) ". Something does more passes over the data than the work needs."))
        (for [{:keys [dim n ratio]} (:under v)]
          (str "LOOSE: " (name dim) " at n=" n " is " (format "%.2f" ratio) "x its estimate: tighten the estimate."))
        (for [d (:unmeasured v)] (str "NOT MEASURED: " (name d) "."))))))

(defn proof
  "Assert, inside a deftest, that estimate e holds: one clojure.test report (pass or fail)
  whose message is `explain` and whose :actual is the rows and verdict as data. Answers
  the verdict."
  {:malli/schema [:=> [:cat :seon.cost/estimate] :seon.cost/verdict]}
  [e]
  (let [{:keys [rows verdict]} (run e)]
    (t/do-report {:type (if (:ok? verdict) :pass :fail)
                  :message (explain e rows verdict)
                  :expected (list 'within-estimate (:of e) (:growth e))
                  :actual {:rows rows :verdict verdict}})
    verdict))

;; ---- the unit table -------------------------------------------------------------------------

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

(def unit-benches
  "How each unit operation is measured: (f n) does n units on (setup n). The table below
  is these, divided by n, on the release binary (bin/check units)."
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
   :sorted/assoc {:what "assoc of a new integer key into a sorted map" :setup #(vector (vec (shuffle (range %))))
                  :f (fn [v] (reduce (fn [m k] (assoc m k k)) (sorted-map) v))}
   :sort/compare {:what "sort of shuffled integers, per n log2 n" :setup #(vector (vec (shuffle (range %)))) :f (fn [v] (sort v))
                  :per :n-log-n}
   :str/append {:what "StringBuilder append of one char" :setup #(vector (apply str (repeat % \a)))
                :f (fn [s] (let [sb (StringBuilder.)] (doseq [c s] (.append sb c)) (.toString sb)))}
   :edn/read-char {:what "clojure.edn/read-string, per character of a vector of integers" :setup #(vector (pr-str (vec (range (quot % 6)))))
                   :f (fn [s] (edn/read-string s)) :per-char true}
   :pr-str/char {:what "pr-str, per character printed (a vector of integers)" :setup #(vector (vec (range (quot % 6))))
                 :f (fn [v] (pr-str v)) :per-char true}})

(defn measure-unit
  "Unit k measured at n units: {:ns :bytes} per unit."
  {:malli/schema [:=> [:cat :keyword pos-int?] [:map [:ns :double] [:bytes :double]]]}
  [k n]
  (let [{:keys [setup f per per-char]} (unit-benches k)
        args (setup n)
        units (cond per-char (double (count (if (string? (first args)) (first args) (pr-str (first args)))))
                    per (work per n) :else (double n))
        m (measure f args)]
    {:ns (/ (:ns m) units) :bytes (/ (:bytes m) units)}))

(def units
  "Measured costs of unit operations on this runtime (release seon binary, M5 Max, load 5,
  2026-10-20, at 10k units; source mode is within 10% except a plain call, 7 ns), per unit: estimates price their :ops with these. `bin/check
  units` measures them again, and seon.cost-test/the-unit-table-is-current is red when a
  row has drifted past 3x, so estimates stay derived from today's physics."
  {:seq/reduce {:ns 1.4 :bytes 0.0 :what "one step of reduce over a vector"}
   :fn/call {:ns 7.4 :bytes 0.0 :what "a reduce step calling one plain function, both defined at the REPL"}
   :fn/armed-call {:ns 60.5 :bytes 989.66 :what "a reduce step calling one contracted function ([:=> [:cat :int] :int])"}
   :vec/nth {:ns 12.4 :bytes 0.0 :what "nth of a vector"}
   :vec/conj {:ns 30.7 :bytes 245.99 :what "conj onto a persistent vector"}
   :vec/conj! {:ns 12.3 :bytes 43.28 :what "conj! onto a transient vector"}
   :map/get {:ns 35.6 :bytes 0.0 :what "get of an integer key in a hash map of n"}
   :map/assoc {:ns 117.5 :bytes 825.01 :what "assoc of a new integer key into a persistent hash map"}
   :map/assoc! {:ns 83.2 :bytes 123.72 :what "assoc! of a new integer key into a transient hash map"}
   :map/update {:ns 238.2 :bytes 1074.43 :what "update of a counter in a hash map (frequencies' step)"}
   :set/conj! {:ns 79.1 :bytes 91.72 :what "conj! of a new integer into a transient hash set"}
   :sorted/assoc {:ns 1334.7 :bytes 3327.21 :what "assoc of a new integer key into a sorted map"}
   :sort/compare {:ns 13.51 :bytes 39.04 :what "sort of shuffled integers, per n log2 n"}
   :str/append {:ns 39.7 :bytes 308.02 :what "StringBuilder append of one char"}
   :edn/read-char {:ns 29.22 :bytes 38.02 :what "clojure.edn/read-string, per character of a vector of integers"}
   :pr-str/char {:ns 61.9 :bytes 25.67 :what "pr-str, per character printed (a vector of integers)"}})
