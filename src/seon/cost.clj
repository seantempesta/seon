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
            [clojure.test :as t] [clojure.string :as str] [seon.print :as print]))

;; ---- the judge (pure) -----------------------------------------------------------------

(def slack
  "Each dimension is over above this multiple of its estimate. Ticks, bytes and calls are
  counted exactly on one thread, so their slack is the estimate's own imprecision (an
  estimate priced from unit operations leaves out a function's overheads: ticks get time's
  old 3x); CPU time moves with the machine's load."
  {:ticks 3.0 :ns 3.0 :bytes 1.25 :calls 1.0})

(def exact
  "The dimensions a verdict is decided on: counted, so the same code on the same input
  measures the same on any machine at any load, and a green is a function of its reach
  digest (AGENTS.md section 5, results reused by input digest). Cost is work done: ticks
  (the time dimension: jolt.host/ticks-of, measured once Jolt's jolt/thread-ticks pin lands;
  judged wherever an estimate and a measure both have them), bytes and calls. CPU time (:ns) moves with load
  (158 to 210 us for one function between load 10 and 17, check-events 2026-10-23): it is
  measured, recorded and shown, never red."
  #{:ticks :bytes :calls})

(def dimensions
  "The measured dimensions, in the order a verdict shows them."
  [:ticks :ns :bytes :calls])

(def steeper-by
  "A dimension is steeper when its fitted exponent exceeds its growth's own exponent over
  the same sizes by more than this: n log n under a linear estimate (+0.17 over 100x)
  passes, a second loop over the data (+1.0) does not."
  0.3)

(def loose
  "At or below this fraction of its estimate at the largest size, the estimate is loose."
  (/ 1.0 3.0))

(def ratchet
  "An accepted overrun stays green while every dimension at the largest size is within this
  multiple of what was measured when its task was filed (Seon's ratchet: the spread of one
  operation measured apart), so a regression is red even inside an open overrun."
  1.5)

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
                          (if-let [{:keys [ticks ns bytes]} (get u op)]
                            (cond-> (-> acc (update :ns (fnil + 0.0) (* k ns)) (update :bytes (fnil + 0.0) (* k bytes)))
                              ticks (update :ticks (fnil + 0.0) (* k ticks)))
                            acc))
                        {} (:ops e))]
    (merge derived (:per-n e))))

(defn expected
  "What estimate e allows at n, per dimension it states: fixed + per-unit x W(n)."
  {:malli/schema [:=> [:cat :seon.cost/estimate [:map-of :keyword :seon.cost/unit] pos-int?] :seon.cost/dims]}
  [e u n]
  (let [p (per-unit e u) f (:fixed e {}) w (work (:growth e) n)]
    (into {} (for [d dimensions :when (or (contains? p d) (contains? f d))]
               [d (+ (double (get f d 0.0)) (* w (double (get p d 0.0))))]))))

(defn judge
  "The verdict on rows (ascending sizes, each measured and expected) under estimate e."
  {:malli/schema [:=> [:cat :seon.cost/estimate [:vector :seon.cost/row]] :seon.cost/verdict]}
  [e rows]
  (let [dims (into #{} (mapcat (comp keys :expected)) rows)
        ratio (fn [{:keys [measured expected]} d]
                (let [m (get measured d) x (get expected d)]
                  (when (and (some? m) (some? x)) (if (pos? x) (/ (double m) x) (if (pos? m) Double/POSITIVE_INFINITY 1.0)))))
        over (vec (for [r rows d dimensions :when (dims d)
                        :let [q (ratio r d)] :when (and q (> q (slack d)))]
                    {:dim d :n (:n r) :ratio q}))
        fixed (:fixed e {})
        allowed (+ steeper-by (exponent (for [r rows] [(:n r) (work (:growth e) (:n r))])))
        steeper (vec (for [d dimensions :when (dims d)
                           :let [pts (for [r rows :let [m (get-in r [:measured d])] :when m] [(:n r) (- m (get fixed d 0.0))])]
                           :when (>= (count pts) 2)
                           :let [x (exponent pts)] :when (> x allowed)]
                       {:dim d :exponent x :allowed allowed}))
        largest (peek rows)
        under (vec (for [d dimensions :when (dims d)
                         :let [q (when largest (ratio largest d))] :when (and q (< q loose))]
                     {:dim d :n (:n largest) :ratio q}))
        unmeasured (vec (for [d dimensions :when (and (dims d) (seq rows) (every? #(nil? (get-in % [:measured d])) rows))] d))
        held? (when-let [{:keys [measured]} (:overrun e)]
                (and largest (every? (fn [[d m]] (or (not (exact d)) (<= (get-in largest [:measured d] 0.0) (* ratchet m)))) measured)))
        red? (fn [fs] (some (comp exact :dim) fs))]
    (cond-> {:ok? (or (not (or (red? over) (red? steeper))) (boolean held?))
             :over over :steeper steeper :under under :unmeasured unmeasured}
      (and held? (or (red? over) (red? steeper)))
      (assoc :accepted {:task (:task (:overrun e)) :over over :steeper steeper}))))

;; ---- measuring ----------------------------------------------------------------------------

(defn- once
  "One call of f on args: its ns of this thread's CPU, the bytes this thread allocated, and contracted calls (calls only
  when the contracts are on: seon.profile counts armed functions)."
  {:malli/schema [:=> [:cat :seon.host/fn [:sequential :any]] :seon.cost/dims]}
  [f args]
  (let [a0 (profile/alloc-bytes) _ (profile/begin!) t0 (profile/cpu-ns)
        _ (apply f args)
        t1 (profile/cpu-ns) c (profile/calls) a1 (profile/alloc-bytes)]
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
  the first size over its estimate in an exact dimension. Answers {:rows :verdict}."
  {:malli/schema [:=> [:cat :seon.cost/estimate] [:map [:rows [:vector :seon.cost/row]] [:verdict :seon.cost/verdict]]]}
  [e]
  (let [f @(requiring-resolve (:of e)) input @(requiring-resolve (:input e))]
    (loop [[n & more] (sort (:sizes e)) rows []]
      (if (nil? n)
        {:rows rows :verdict (judge e rows)}
        (let [row {:n n :measured (measure f (vec (input n))) :expected (expected e units n)}
              rows (conj rows row)]
          (if (and (nil? (:overrun e)) (not (:ok? (judge e [row]))))
            {:rows rows :verdict (judge e rows)}
            (recur more rows)))))))

;; ---- the proof ----------------------------------------------------------------------------

(defn- num-text {:malli/schema [:=> [:cat [:maybe number?]] :string]}
  [x]
  (cond (nil? x) "-"
        (>= x 1e9) (format "%.2fG" (/ x 1e9)) (>= x 1e6) (format "%.2fM" (/ x 1e6))
        (>= x 1e4) (format "%.1fk" (/ x 1e3)) :else (format "%.0f" (double x))))

(defn verdict-lines
  "A cost verdict as [class words] lines, red first: within or over its estimate, each size
  over it (and the slack allowed), each dimension grown steeper than the work needs (time's
  shown as a finding: it is never red, `exact`), loose
  estimates and unmeasured dimensions as findings, an accepted overrun, and, with its
  estimate e, a regression past the recorded overrun. The one wording of a verdict: the
  agent's text (`explain`) and the page's verdict box (seon.render.ui/verdict-box)."
  {:malli/schema [:=> [:cat :seon.cost/verdict [:maybe :seon.cost/estimate]] [:sequential [:tuple :string :string]]]}
  [{:keys [ok? over steeper under unmeasured accepted]} e]
  (concat [[(if ok? "ok" "bad") (if ok? "within estimate" "OVER ESTIMATE")]]
          (for [{:keys [dim n ratio]} over]
            [(if (exact dim) "bad" "muted") (str (if (exact dim) "OVER: " "TIME OVER (measured, not red): ") (name dim) " at n=" n " is " (format "%.2f" ratio) "x its estimate (allowed " (slack dim) "x).")])
          (for [{:keys [dim exponent allowed]} steeper]
            [(if (exact dim) "bad" "muted") (str (if (exact dim) "STEEPER: " "TIME STEEPER (measured, not red): ") (name dim) " grew as n^" (format "%.2f" exponent) "; " (if-let [g (:growth e)] (str (name g) " allows") "the estimate allows") " n^"
                        (format "%.2f" allowed) ". Something does more passes over the data than the work needs.")])
          (for [{:keys [dim n ratio]} under] ["muted" (str "LOOSE: " (name dim) " at n=" n " is " (format "%.2f" ratio) "x its estimate: tighten the estimate.")])
          (for [d unmeasured] ["muted" (str "NOT MEASURED: " (name d) ".")])
          (when-let [{:keys [task]} accepted]
            [["muted" (str "OVERRUN accepted while task " task " is open (within " ratchet "x of "
                           (if-let [m (:measured (:overrun e))] (print/text m (:part print/budgets)) "the overrun its task recorded") "): it is that task's work.")]])
          (when (and (:overrun e) (not ok?))
            [["bad" (str "REGRESSION: past " ratchet "x of the overrun recorded for task " (:task (:overrun e)) ".")]])))

(defn explain
  "The estimate, the measurements against it and the verdict (`verdict-lines`), as text an
  agent reads."
  {:malli/schema [:=> [:cat :seon.cost/estimate [:vector :seon.cost/row] :seon.cost/verdict] :string]}
  [e rows v]
  (let [dims (filter (set (mapcat (comp keys :expected) rows)) dimensions)]
    (str/join "\n"
      (concat
        [(str (:of e) ", N = " (:n e) ". The work: " (:work e) " (" (name (:growth e)) ").")
         (str "n: " (str/join "  " (for [d dims] (str (name d) " measured/estimate (x)"))))]
        (for [{:keys [n measured expected]} rows]
          (str n ": " (str/join "  " (for [d dims :let [m (get measured d) x (get expected d)]]
                                       (str (num-text m) "/" (num-text x)
                                            (when (and m (pos? x)) (format " (%.2fx)" (/ m x))))))))
        (map second (verdict-lines v e))))))

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

(def units
  "Measured costs of unit operations on this runtime (release seon binary, M5 Max, load 5,
  2026-10-20, at 10k units; source mode is within 10% except a plain call, 7 ns; the last
  five rows were measured in source mode after P2 found :str/append mislabeled: the 308 B
  per char it showed was the String's seq, not the StringBuilder; :fn/armed-call and the
  pull rows re-measured on Jolt 00a3b321, J3's try/catch: 990 -> 354 B per armed call), per unit: estimates price their :ops with these.
  seon.cost-test/the-unit-table-is-current measures them again (seon.cost-test/unit-benches)
  and is red when a row's bytes have drifted past 1.25x, naming the row it measured, so
  estimates stay derived from today's physics. :ns is CPU time, shown, never judged; each row
  gains :ticks (jolt.host/ticks-of, Jolt jolt/thread-ticks) when that pin lands."
  {:seq/reduce {:ns 1.4 :bytes 0.0 :what "one step of reduce over a vector"}
   :fn/call {:ns 7.4 :bytes 0.0 :what "a reduce step calling one plain function, both defined at the REPL"}
   :fn/armed-call {:ns 32.8 :bytes 353.53 :what "a reduce step calling one contracted function ([:=> [:cat :int] :int])"}
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
   :str/append {:ns 9.9 :bytes 36.0 :what "StringBuilder append of one char"}
   :str/seq-char {:ns 29.9 :bytes 272.0 :what "one step of a seq over a String's chars (doseq, reduce)"}
   :map/literal {:ns 5.2 :bytes 144.0 :what "a small map literal of 5 keys built per element"}
   :map/seq-step {:ns 28.3 :bytes 127.1 :what "one step of reduce over a hash map's entries (each a map entry)"}
   :map/reduce-kv {:ns 4.7 :bytes 0.0 :what "one step of reduce-kv over a hash map"}
   :edn/read-char {:ns 29.22 :bytes 38.02 :what "clojure.edn/read-string, per character of a vector of integers"}
   :pr-str/char {:ns 61.9 :bytes 25.67 :what "pr-str, per character printed (a vector of integers)"}
   ;; session D2, source mode, load 3, 2026-10-20, at 2k: a pull is 21 us and 28 KB, each further
   ;; attribute 9.7 us and 13 KB (a finding: the pull's own allocation, like seon.db/eid's seek)
   ;; session STORES, 2026-10-21, load 8, at 10k, three runs: measured on a branch of the seeded test
   ;; store (seon.test-store/test-branch!: ~2,000 datoms beside the setup's) instead of an empty store,
   ;; a pull 21 us and 24.6 KB, each further attribute 11.3-15.1 us and 11.6 KB: pull-attr's one
   ;; entity sat in a one-level index before, the seed makes it the two levels real dbs have
   :db/pull {:ns 21200.3 :bytes 24612.49 :what "d/pull of one attribute of an entity, by entity id (per pull)"}
   :db/pull-attr {:ns 11801.8 :bytes 11576.64 :what "each attribute of a d/pull beyond its first (per attribute)"}})
