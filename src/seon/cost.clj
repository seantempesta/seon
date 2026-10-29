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
  not hours. `judge` is a function of its rows, its estimate and the config (the tick quantum): over (a size above slack x estimate), steeper (a dimension
  grew faster than :growth allows over the sizes: the extra pass, the scan inside a loop),
  under (a third of the estimate or less at the largest size: the estimate is loose, a
  finding, not red), unmeasured (a stated dimension with nothing measured)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.profile :as profile] [seon.config :as config] [seon.lib :as lib]
            [clojure.test :as t] [clojure.string :as str] [seon.print :as print] [seon.proofs :as proofs]
            [malli.core :as m] [malli.generator :as mg] [clojure.test.check.generators :as gen]))

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
  (the time dimension: jolt.host/ticks-of, measured by once; judged wherever the estimate states
  them, derived from the unit table's :ops or stated in :per-n), bytes and calls. An estimate with
  none of them both stated and measured is red (:uncounted). CPU time (:ns) moves with load
  (158 to 210 us for one function between load 10 and 17, check-events): it is
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

(def growth-share
  "Growth is fitted on increments over the smallest size only when the largest increment is
  at least this share of the smallest size's cost: below it, the measure's own variance moves
  the increments more than the growth does. Measured on seon.source-test's transact
  row (log growth, 406k ticks at 10, 71k more at 1,000): ticks varied 0.5% between runs, which
  moved the increments' exponent by 0.02, its whole margin under the bound."
  0.25)

(def increment-floor
  "An increment over the smallest size is fitted only when it is at least this share of the
  smallest size's cost (a twenty-fifth of growth-share, twice the 0.5% variance of a measure): a
  step in the cost (an index's depth, between two sizes) leaves the size before it a few bytes
  over the smallest, and the fit read 409k over 300 B as n^3.1
  (seon.program-diff-test/a-diff-costs-the-change-never-the-program, 2026-10-29)."
  0.01)

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
  {:malli/schema [:=> [:cat :seon.cost/sized [:map-of :keyword :seon.cost/unit]] :seon.cost/dims]}
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
  {:malli/schema [:=> [:cat :seon.cost/sized [:map-of :keyword :seon.cost/unit] pos-int?] :seon.cost/dims]}
  [e u n]
  (let [p (per-unit e u) f (:fixed e {}) w (work (:growth e) n)]
    (into {} (for [d dimensions :when (or (contains? p d) (contains? f d))]
               [d (+ (double (get f d 0.0)) (* w (double (get p d 0.0))))]))))

(defn growth
  "Dimension d's growth over rows under estimate e, {:exponent :allowed :basis}, or nil when
  fewer than two sizes measured it: the fitted exponent and the one the estimate's growth
  allows over the same sizes (its own plus steeper-by). A change making every size cheaper,
  or dearer, by one amount never moves it (task seon.cost/a-speedup-never-reads-as-steeper;
  docs/cost.md#how-growth-is-judged). The basis: :increments, each size's increment over the
  smallest against the work's own (three sizes or more, not log work, with two increments over increment-floor
  of the smallest size's cost, the largest at least growth-share of it, no fixed part assumed);
  :total, the cost itself, for constant work (it should not grow); log work takes :bounded-fixed,
  never increments: its cost is a staircase (an index gains a level every so many entries), and
  increments of a staircase have no power-law shape (675.6k, 695.8k and 1.07M bytes at 10, 100 and
  1,000 read n^1.29 on increments against a log's n^0.60, and n^0.19 on the cost less its fixed part);
  else :bounded-fixed, the estimate's :fixed taken off but never more than the smallest
  size leaves beyond its estimated per-unit part. On the stated :fixed alone, a faster fixed
  part left the smallest size's remainder near 0 and the fit read the speedup as growth."
  {:malli/schema [:=> [:cat :seon.cost/sized [:vector {:gen/max 4} :seon.cost/row] :seon.cost/dim]
                  [:maybe [:map [:exponent :double] [:allowed :double] [:basis [:enum :increments :total :bounded-fixed]]]]]}
  [e rows d]
  (let [rs (filterv #(some? (get-in % [:measured d])) rows)
        m #(double (get-in % [:measured d]))
        w #(work (:growth e) (:n %))
        fit (fn [basis pts wpts] {:exponent (exponent pts) :allowed (+ steeper-by (exponent wpts)) :basis basis})
        r0 (first rs)
        incs (when (and (>= (count rs) 3) (not (#{:constant :log} (:growth e))))
               (for [r (rest rs) :when (>= (- (m r) (m r0)) (* increment-floor (m r0)))] r))
        bounded (fn [] (let [f (double (get-in e [:fixed d] 0.0))
                             e0 (get-in r0 [:expected d])
                             f (if e0 (max 0.0 (min f (- (m r0) (- e0 f)))) f)
                             pts (for [r rs :when (pos? (- (m r) f))] r)]
                         (fit :bounded-fixed (for [r pts] [(:n r) (- (m r) f)]) (for [r pts] [(:n r) (w r)]))))]
    (cond
      (< (count rs) 2) nil
      (= :constant (:growth e)) (fit :total (for [r rs] [(:n r) (m r)]) (for [r rs] [(:n r) (w r)]))
      (and (>= (count incs) 2) (>= (- (m (last incs)) (m r0)) (* growth-share (m r0))))
      (fit :increments (for [r incs] [(:n r) (- (m r) (m r0))]) (for [r incs] [(:n r) (- (w r) (w r0))]))
      :else (bounded))))

(defn judge
  "The verdict on rows (ascending sizes, each measured and expected) under estimate e."
  {:malli/schema [:=> [:cat :seon.cost/estimate [:vector {:gen/max 4} :seon.cost/row]] :seon.cost/verdict]}
  [e rows]
  (let [dims (into #{} (mapcat (comp keys :expected)) rows)
        ;; one measure of ticks may be off by a timer chunk (:seon.config.proofs/tick-quantum, task
        ;; seon.cost/ticks-are-exact): taken off a size before it is judged over, and off every size
        ;; but the smallest before growth is fitted (one chunk, never more; a measure under it is
        ;; left as it is, so no point is dropped from the fit)
        quantum {:ticks (double (config/value :seon.config.proofs/tick-quantum))}
        ratio (fn [{:keys [measured expected]} d]
                (let [m (get measured d) x (get expected d)]
                  (when (and (some? m) (some? x)) (if (pos? x) (/ (double m) x) (if (pos? m) Double/POSITIVE_INFINITY 1.0)))))
        less-quantum (fn [m d] (let [q (get quantum d 0.0)] (if (> m q) (- m q) m)))
        allowed-ratio (fn [{:keys [measured] :as r} d]
                        (ratio (cond-> r (get measured d) (update-in [:measured d] #(less-quantum % d))) d))
        over (vec (for [r rows d dimensions :when (dims d)
                        :let [q (ratio r d)] :when (and q (> (allowed-ratio r d) (slack d)))]
                    {:dim d :n (:n r) :ratio q}))
        toward-flat (fn [d] (let [first-n (:n (first (filter #(get-in % [:measured d]) rows)))]
                              (mapv (fn [r] (cond-> r (and (get-in r [:measured d]) (not= first-n (:n r)))
                                              (update-in [:measured d] #(less-quantum % d))))
                                    rows)))
        steeper (vec (for [d dimensions :when (and (:growth e) (dims d))
                           :let [{:keys [exponent allowed]} (growth e (toward-flat d) d)]
                           :when (and exponent (> exponent allowed))]
                       {:dim d :exponent exponent :allowed allowed}))
        ;; the largest size, or in each dimension the generated case whose bound is the greatest there
        largest-in (fn [d] (if (:cases e) (last (sort-by #(get-in % [:expected d] 0.0) rows)) (peek rows)))
        largest (peek rows)
        under (vec (for [d dimensions :when (dims d)
                         :let [l (largest-in d) q (when l (ratio l d))] :when (and q (< q loose))]
                     {:dim d :n (:n l) :ratio q}))
        unmeasured (vec (for [d dimensions :when (and (dims d) (seq rows) (every? #(nil? (get-in % [:measured d])) rows))] d))
        held? (when-let [{:keys [measured]} (:overrun e)]
                (and largest (every? (fn [[d m]] (or (not (exact d)) (<= (get-in largest [:measured d] 0.0) (* ratchet m)))) measured)))
        red? (fn [fs] (some (comp exact :dim) fs))
        ;; a verdict needs a counted dimension both estimated and measured: one judged on time
        ;; alone could never be red (time is shown, never red)
        uncounted? (not-any? #(and (exact %) (dims %) (not (some #{%} unmeasured))) dimensions)]
    (cond-> {:ok? (and (not uncounted?) (or (not (or (red? over) (red? steeper))) (boolean held?)))
             :over over :steeper steeper :under under :unmeasured unmeasured}
      uncounted? (assoc :uncounted true)
      (and held? (or (red? over) (red? steeper)))
      (assoc :accepted {:task (:task (:overrun e)) :over over :steeper steeper}))))

;; ---- measuring ----------------------------------------------------------------------------

(def tick-allowance
  "Ticks a measure allows for each collection that ran during it: a collection or signal
  raised from another thread runs its handler on the measured thread, and those ticks count as
  the call's (+11 to +27 per forced event, measured by dry-run: docs/cost.md); a
  quiet run is exact. The measure takes this many off per collection, so a collection never
  turns a proof red."
  64)

(defn- once
  "One call of f on args, measured by the one measurement of a call (seon.profile/measure!): the
  engine ticks it ran on this thread, less tick-allowance for each collection during it; its ns
  of this thread's CPU; the bytes this thread allocated; and contracted calls (calls only when
  the contracts are on: seon.profile counts armed functions). The collection count is read
  outside the bytes' window, so reading it allocates nothing the call is charged."
  {:malli/schema [:=> [:cat :seon.host/fn [:sequential :any]] :seon.cost/dims]}
  [f args]
  (let [g0 (:count (lib/gc-stats))
        m (volatile! nil)
        _ (profile/measure! #(apply f args) #(vreset! m %))
        gcs (- (:count (lib/gc-stats)) g0)
        {:keys [ticks bytes cpu-ns calls]} @m]
    (cond-> {:ticks (double (max 0 (- ticks (* tick-allowance gcs)))) :ns (double cpu-ns) :bytes (double bytes)}
      contract/on? (assoc :calls (double calls)))))

(def ^:private threaded
  "Makes this process multi-threaded, once, before anything is measured: Jolt's lazy seq cells take a
  claim path (compare-and-swap, more ticks and bytes a cell: a seq over a String's chars 8 ticks and
  80 B, then 15 and 272) from the first real OS thread on, for the rest of the process (`jolt-mt?`,
  never reset). Every Seon root is threaded, so that is the regime a measure stands for; without
  this, a proof run alone (no thread yet) and in a shard after a test that made one (a `future`, a
  shell call) counted different work for one call. docs/cost.md#how-we-measure."
  (delay (doto (Thread. ^Runnable (fn [] nil)) (.start) (.join)) true))

(def ^:private baseline
  "What measuring a call that does nothing costs: the clock, tick and allocation reads themselves."
  (delay @threaded
         (let [f (fn [] nil)]
           (reduce (fn [acc m] (merge-with min acc m)) (repeatedly 5 #(once f []))))))

(defn measure
  "f on args, measured at its steady state: calls until `:seon.config.proofs/cost-settled` calls
  after the one that allocated the fewest bytes so far allocate no fewer; each dimension's minimum over
  all of them (bytes are the steady state's; ticks and calls their least, which noise only adds
  to), less what measuring itself costs. A call allocates more on its first calls (Jolt compiles
  what it finds hot, caches fill): one warm call left a proof measuring that, with the settled
  bytes 1,536 to 3,776 B over and runs of 8 equal calls on the way down, so a bound near the
  estimate went red or green by the draw (task seon.cost/a-call-allocates-the-same-bytes-each-time).
  A count of calls, never a time. A call that has not settled after
  `:seon.config.proofs/cost-max-samples` calls answers the least of them, marked in the answer's
  meta (`:unsettled {:calls n :lowered-at k}`): noise on a large allocation lowers the least now and
  then without end (a 32 MB call: the last new low at call 53 of 60), so \"no new low in a run of
  calls\" is a test that fails by chance, not a defect of the call; a call that never reaches a floor
  shows in that mark and in the spread of its bytes."
  {:malli/schema [:=> [:cat :seon.host/fn [:sequential :any]] :seon.cost/dims]}
  [f args]
  (let [need (config/value :seon.config.proofs/cost-settled)
        cap (config/value :seon.config.proofs/cost-max-samples)
        b @baseline
        [best n low-at] (loop [n 1 best (once f args) low-at 1]
                          (if (or (>= (- n low-at) need) (>= n cap))
                            [best n low-at]
                            (let [m (once f args) n (inc n)]
                              (recur n (merge-with min best m) (if (< (:bytes m) (:bytes best)) n low-at)))))
        dims (into {} (for [[d v] best] [d (max 0.0 (- v (if (= d :calls) 0.0 (get b d 0.0))))]))]
    (cond-> dims (< (- n low-at) need) (vary-meta assoc :unsettled {:calls n :lowered-at low-at}))))

(declare units)

(def min-points
  "The fewest sizes a proof measures: three, so its growth is judged on a curve, not on the
  line two points always make. A proof the cap leaves fewer under is refused (run)."
  3)

(defn sizes
  "The sizes a proof measures: its sizes up to cap (:seon.config.proofs/cost-max-n), ascending
  ([100 300 1000 10000] at cap 1000: [100 300 1000]). Building the input at 10k cost 10-19 s per
  proof in every run, so a normal run stops at the cap and the full run (cap 100,000) measures
  every size as written; the sizes past the cap are named on the proof's verdict (run's
  :left-out), never dropped in silence. Never a size smaller than the proof states: scaled
  down to n=10, fixed per-call costs swamp the per-element price its estimate states (print-cost
  and ref proofs went 1.8-5x over)."
  {:malli/schema [:=> [:cat [:sequential pos-int?] pos-int?] [:vector pos-int?]]}
  [ns cap]
  (filterv #(<= % cap) (sort ns)))

(defn bound
  "The cost bound var sym's contract carries (:seon.cost/bound beside :malli/schema): a function of
  the same arguments answering what one call may cost, {:ticks :bytes :calls}, and :out, the measure
  of its answer a caller's bound passes on. Its terms are the algorithm's steps, each step's cost
  measured once (docs/cost.md#a-bound-in-the-contract)."
  {:malli/schema [:=> [:cat :qualified-symbol] :seon.host/fn]}
  [sym]
  (or (:seon.cost/bound (meta (requiring-resolve sym)))
      (throw (ex-info (str sym " carries no :seon.cost/bound")
                      (contract/error :seon.cost/no-bound #'bound (str sym " carries no :seon.cost/bound beside its :malli/schema")
                                      {:seon.error/fix (str "Add :seon.cost/bound (fn [& args] {:ticks :bytes :out}) to " sym "'s attribute map.") :var sym})))))

(defn cases
  "The argument vectors a generated estimate measures its function on: :cases of them from :gen
  (the var of a test.check generator of argument vectors), else from the function's contract
  (malli's generator of its input), at sizes cycling through :seon.config.proofs/case-sizes and the running test's seed
  (seon.proofs/test-seed, a function of its reach digest: the same code draws the same cases;
  outside a test, the seed of no test, still the same each call)."
  {:malli/schema [:=> [:cat :seon.cost/generated] [:vector [:vector :any]]]}
  [{:keys [of cases] g :gen}]
  (let [g (if g @(requiring-resolve g) (mg/generator (first (m/children (contract/schema (:malli/schema (meta (requiring-resolve of))))))))
        seed (proofs/test-seed)]
    (mapv #(vec (gen/generate g (mod % (config/value :seon.config.proofs/case-sizes)) (+ seed %))) (range cases))))

(defn run
  "Estimate e measured: its function on its input at each size (sizes, under
  :seon.config.proofs/cost-max-n), ascending, or on each generated case (`cases`) against its
  contract's bound on that case's arguments (`bound`, kept on the row as :args), stopping after
  the first point over its estimate in an exact dimension. Answers {:rows :verdict :left-out},
  :left-out the stated sizes past the cap, or a proof refused: one the cap leaves fewer than
  min-points sizes is :seon.cost/too-few-sizes, naming them and the key, measured nothing."
  {:malli/schema [:=> [:cat :seon.cost/estimate] [:or [:map [:rows [:vector :seon.cost/row]] [:verdict :seon.cost/verdict] [:left-out [:vector pos-int?]]]
                                                   :seon.cost/too-few-sizes]]}
  [e]
  (let [cap (config/value :seon.config.proofs/cost-max-n)
        ns (when-not (:cases e) (sizes (:sizes e) cap))
        left-out (if (:cases e) [] (vec (remove (set ns) (sort (:sizes e)))))]
  (if (and (not (:cases e)) (< (count ns) min-points))
    (contract/error :seon.cost/too-few-sizes #'seon.cost/run
                    (str (:of e) "'s proof measures " (count ns) " sizes under :seon.config.proofs/cost-max-n " cap
                         " (" (print/text (vec (sort (:sizes e))) (:part print/budgets)) "), fewer than " min-points ": give it sizes under the cap where its work dominates")
                    {:of (:of e) :sizes (vec (sort (:sizes e))) :cap cap})
  (let [f @(requiring-resolve (:of e))
        ;; each point measured: a size and its input built outside the measure, or a generated
        ;; case and its arguments, the contract's bound on them its estimate
        points (if (:cases e)
                 (let [b (bound (:of e))] (map-indexed (fn [i args] [(inc i) args #(select-keys (apply b args) dimensions)]) (cases e)))
                 (let [input @(requiring-resolve (:input e))]
                   (map (fn [n] [n (delay (vec (input n))) #(expected e units n)]) ns)))]
    (loop [[[n args allowed] & more] points rows []]
      (if (nil? n)
        {:rows rows :verdict (judge e rows) :left-out left-out}
        (let [args (force args)
              row (cond-> {:n n :measured (measure f args) :expected (allowed)} (:cases e) (assoc :args args))
              rows (conj rows row)]
          (if (and (nil? (:overrun e)) (not (:ok? (judge e [row]))))
            {:rows rows :verdict (judge e rows) :left-out left-out}
            (recur more rows)))))))))

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
  [{:keys [ok? over steeper under unmeasured accepted] :as v} e]
  (concat [[(if ok? "ok" "bad") (if ok? "within estimate" "OVER ESTIMATE")]]
          (for [{:keys [dim n ratio]} over]
            [(if (exact dim) "bad" "muted") (str (if (exact dim) "OVER: " "TIME OVER (measured, not red): ") (name dim) " at n=" n " is " (format "%.2f" ratio) "x its estimate (allowed " (slack dim) "x).")])
          (for [{:keys [dim exponent allowed]} steeper]
            [(if (exact dim) "bad" "muted") (str (if (exact dim) "STEEPER: " "TIME STEEPER (measured, not red): ") (name dim) " grew as n^" (format "%.2f" exponent) "; " (if-let [g (:growth e)] (str (name g) " allows") "the estimate allows") " n^"
                        (format "%.2f" allowed) ". Something does more passes over the data than the work needs.")])
          (for [{:keys [dim n ratio]} under] ["muted" (str "LOOSE: " (name dim) " at n=" n " is " (format "%.2f" ratio) "x its estimate: tighten the estimate.")])
          (for [d unmeasured] ["muted" (str "NOT MEASURED: " (name d) ".")])
          (when (:uncounted v)
            [["bad" "NO COUNTED DIMENSION: the estimate states no bytes, calls or ticks that were measured; time alone is never red, so the proof could not fail."]])
          (when-let [{:keys [task]} accepted]
            [["muted" (str "OVERRUN accepted while task " task " is open (within " ratchet "x of "
                           (if-let [m (:measured (:overrun e))] (print/text m (:part print/budgets)) "the overrun its task recorded") "): it is that task's work.")]])
          (when (and (:overrun e) (not ok?))
            [["bad" (str "REGRESSION: past " ratchet "x of the overrun recorded for task " (:task (:overrun e)) ".")]])))

(defn explain
  "The estimate, the measurements against it and the verdict (`verdict-lines`), as text an
  agent reads."
  {:malli/schema [:=> [:cat :seon.cost/estimate [:vector {:gen/max 4} :seon.cost/row] :seon.cost/verdict] :string]}
  [e rows v]
  (let [dims (filter (set (mapcat (comp keys :expected) rows)) dimensions)
        line (fn [{:keys [n measured expected]}]
               (str n ": " (str/join "  " (for [d dims :let [m (get measured d) x (get expected d)]]
                                            (str (num-text m) "/" (num-text x)
                                                 (when m (if (pos? x) (format " (%.2fx)" (/ m x)) (when (pos? m) " (inf x)"))))))))
        ratios (fn [d] (sort (keep (fn [{:keys [measured expected]}] (let [m (get measured d) x (get expected d)] (when (and m x (pos? x)) (/ m x)))) rows)))
        over (set (map :n (:over v)))]
    (str/join "\n"
      (concat
        [(str (:of e) ", N = " (:n e) ". The work: " (:work e)
              (if (:cases e) (str " (its contract's bound, on " (count rows) " of " (:cases e) " generated cases).") (str " (" (name (:growth e)) ").")))
         (str "n: " (str/join "  " (for [d dims] (str (name d) " measured/estimate (x)"))))]
        (if (:cases e)
          ;; the cases' spread, then each case over its bound with the arguments that reproduce it
          (concat (for [d dims :let [r (vec (ratios d))] :when (seq r)]
                    (format "%s measured/bound: min %.2f, median %.2f, max %.2f" (name d) (first r) (nth r (quot (count r) 2)) (peek r)))
                  (for [r rows :when (over (:n r))] (str (line r) " on " (print/text (:args r) (:part print/budgets)))))
          (map line rows))
        (map second (verdict-lines v e))))))

(defn proof
  "Assert, inside a deftest, that estimate e holds: one clojure.test report (pass or fail)
  whose message is `explain` and whose :actual is the rows and verdict as data. Answers
  the verdict, or the error of a proof the cap leaves too few sizes (run)."
  {:malli/schema [:=> [:cat :seon.cost/estimate] [:or :seon.cost/verdict :seon.cost/too-few-sizes]]}
  [e]
  (let [r (run e)]
    (if (contract/error? r)
      (do (t/do-report {:type :fail :message (:seon.error/message r) :expected (list 'at-least min-points 'sizes-under-the-cap)
                        :actual r})
          r)
      (let [{:keys [rows verdict left-out]} r]
        (t/do-report {:type (if (:ok? verdict) :pass :fail)
                      :message (str (explain e rows verdict)
                                    (when (seq left-out)
                                      (str "\n" (str/join ", " left-out) " left out past :seon.config.proofs/cost-max-n "
                                           (config/value :seon.config.proofs/cost-max-n) "; the full run measures "
                                           (if (next left-out) "them" "it"))))
                      :expected (if (:cases e) (list 'within-bound (:of e)) (list 'within-estimate (:of e) (:growth e)))
                      :actual {:rows rows :verdict verdict :left-out left-out}})
        verdict))))

;; ---- the unit table -------------------------------------------------------------------------

(def units
  "Measured costs of unit operations on this runtime (release seon binary, M5 Max, load 5,
  at 10k units; source mode is within 10% except a plain call, 7 ns; the last
  five rows were measured in source mode after P2 found :str/append mislabeled: the 308 B
  per char it showed was the String's seq, not the StringBuilder; :fn/armed-call and the
  pull rows re-measured on Jolt 00a3b321, J3's try/catch: 990 -> 354 B per armed call), per unit: estimates price their :ops with these.
  seon.cost-test/the-unit-table-is-current measures them again (seon.cost-test/unit-benches)
  and is red when a row's bytes have drifted past 1.25x, naming the row it measured, so
  estimates stay derived from today's physics. :ns is CPU time, shown, never judged; each row
  carries :ticks, which estimates' :ops are priced in.
  :ticks per unit (jolt.host/ticks-of, less nothing: no collection ran at 10k) measured
  in the REPL at 10k units, twice, identical to the hundredth; the work done, the
  same at any load (docs/cost.md); seon.cost-test/the-unit-table-is-current holds them to 1.25x."
  {:seq/reduce {:ticks 1.16 :ns 1.4 :bytes 0.0 :what "one step of reduce over a vector"}
   :fn/call {:ticks 5.16 :ns 7.4 :bytes 0.0 :what "a reduce step calling one plain function, both defined at the REPL"}
   ;; 353.53 B before malli's total arms went unguarded (task seon.contract/an-armed-call-costs-its-checks:
   ;; two try's a call, 2026-10-23); a total arm is checked bare
   ;; 14.16 ticks since seon.profile's wrapper counts each call's ticks and bytes (task seon.proofs/tests-are-facts:
   ;; a test's record holds what its contracted functions cost): 10.2 when it counted calls alone, measured by the bisect
   ;; of tonight's merges (jolt/tests-are-facts moved it, nothing before it); the ns is the same wrapper's, 62-76 on a loaded machine
   :fn/armed-call {:ticks 14.16 :ns 62.4 :bytes 0.02 :what "a reduce step calling one contracted function ([:=> [:cat :int] :int])"}
   :vec/nth {:ticks 8.15 :ns 12.4 :bytes 0.0 :what "nth of a vector"}
   :vec/conj {:ticks 29.18 :ns 30.7 :bytes 245.99 :what "conj onto a persistent vector"}
   :vec/conj! {:ticks 7.49 :ns 12.3 :bytes 43.28 :what "conj! onto a transient vector"}
   :map/get {:ticks 10.44 :ns 35.6 :bytes 0.0 :what "get of an integer key in a hash map of n"}
   :map/assoc {:ticks 43.53 :ns 117.5 :bytes 825.01 :what "assoc of a new integer key into a persistent hash map"}
   :map/assoc! {:ticks 29.25 :ns 83.2 :bytes 123.72 :what "assoc! of a new integer key into a transient hash map"}
   ;; :map/update and the two pull rows re-measured on Jolt 328eaf2c (update's fixed arities; a
   ;; protocol call on the DB no longer reads its 21 fields): 117 -> 81 ticks and 1074 -> 802 B,
   ;; 7992 -> 5476 and 4670 -> 2685 ticks
   :map/update {:ticks 81.44 :ns 206.3 :bytes 802.44 :what "update of a counter in a hash map (frequencies' step)"}
   :set/conj! {:ticks 20.25 :ns 79.1 :bytes 91.72 :what "conj! of a new integer into a transient hash set"}
   :sorted/assoc {:ticks 837.9 :ns 1334.7 :bytes 3327.21 :what "assoc of a new integer key into a sorted map"}
   :sort/compare {:ticks 10.97 :ns 13.51 :bytes 39.04 :what "sort of shuffled integers, per n log2 n"}
   :str/append {:ticks 4.1 :ns 9.9 :bytes 36.0 :what "StringBuilder append of one char"}
   ;; :str/seq-char and :str/escape re-measured on Jolt 546c3d1f (jolt/lazy-cas: a pure lazy
   ;; producer is claim-free when threaded, and the claim path drops a box): 272 -> 80 B, 684 -> 436 B
   :str/seq-char {:ticks 11.0 :ns 18.2 :bytes 80.01 :what "one step of a seq over a String's chars (doseq, reduce)"}
   ;; source mode, at 10k: the string literal's escape
   ;; (seon.print.emit/string-literal), 2.5x a char seq's bytes
   :str/escape {:ticks 60.64 :ns 107.8 :bytes 436.1 :what "clojure.string/escape of a text by char-escape-string, newlines kept, per char"}
   :map/literal {:ticks 3.16 :ns 5.2 :bytes 144.0 :what "a small map literal of 5 keys built per element"}
   :map/seq-step {:ticks 15.79 :ns 28.3 :bytes 127.1 :what "one step of reduce over a hash map's entries (each a map entry)"}
   :map/reduce-kv {:ticks 1.24 :ns 4.7 :bytes 0.0 :what "one step of reduce-kv over a hash map"}
   :edn/read-char {:ticks 22.29 :ns 29.22 :bytes 38.02 :what "clojure.edn/read-string, per character of a vector of integers"}
   :pr-str/char {:ticks 43.33 :ns 61.9 :bytes 25.67 :what "pr-str, per character printed (a vector of integers)"}
   ;; source mode, load 3, at 2k: a pull is 21 us and 28 KB, each further
   ;; attribute 9.7 us and 13 KB (a finding: the pull's own allocation, like seon.db/eid's seek)
   ;; load 8, at 10k, three runs: measured on a branch of the seeded test
   ;; store (seon.test-store/test-branch!: ~2,000 datoms beside the setup's) instead of an empty store,
   ;; a pull 21 us and 24.6 KB, each further attribute 11.3-15.1 us and 11.6 KB: pull-attr's one
   ;; entity sat in a one-level index before, the seed makes it the two levels real dbs have.
   ;; 2026-10-25 (from jolt/unitflake): both pulls on one db and one set of entities, the subtrahend
   ;; measured on the same input right after (seon.cost-test/measure-unit), so the row is the extra
   ;; attribute alone: 10,377 B and 2,847 ticks at 1k and 10k in two processes, the same each run
   ;; (the two-db difference read 9,946 B and 2,577 ticks)
   :db/pull {:ticks 5475.52 :ns 15483.7 :bytes 23833.98 :what "d/pull of one attribute of an entity, by entity id (per pull)"}
   :db/pull-attr {:ticks 2847.0 :ns 7287.7 :bytes 10377.0 :what "each attribute of a d/pull beyond its first (per attribute)"}})
