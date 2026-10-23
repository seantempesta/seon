(ns seon.cost-test
  "Cost proofs (idea 16): the judge's laws on stated measurements, and seon.cost/proof end
  to end on real functions, red when the body does more than the work needs."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [seon.contract :refer [defn defn-]] [seon.config]
            [seon.cost :as cost] [seon.profile :as profile] [datahike.api :as d] [seon.db :as pdb] [clojure.edn :as edn] [clojure.string :as str]))

;; ---- the judge ------------------------------------------------------------------------

(def ^:private linear
  {:of 'seon.cost-test/one-pass :input 'seon.cost-test/ints :n "integers" :work "one pass" :growth :linear
   :per-n {:ns 100.0 :bytes 50.0 :calls 1.0} :fixed {:calls 1.0} :sizes [100 1000 10000]})

(defn- rows
  "Rows of estimate e at its sizes, measured as (m n) per dimension."
  {:malli/schema [:=> [:cat :seon.cost/estimate :seon.host/fn] [:vector :seon.cost/row]]}
  [e m] (vec (for [n (:sizes e)] {:n n :measured (m n) :expected (cost/expected e cost/units n)})))

(deftest a-proofs-sizes-stop-at-the-cap-keeping-two
  (is (= [100 1000] (cost/sizes [100 1000 10000] 1000)) "the sizes up to the cap")
  (is (= [1000 10000] (cost/sizes [10000 1000 100000] 1000)) "ascending, and at least the two smallest: a growth fit needs two")
  (is (= [100 1000 10000] (cost/sizes [10000 100 1000] 100000)) "under the cap: as written, ascending")
  (is (= [4 40 400] (cost/sizes [4 40 400] 1000))))

(deftest work-is-the-growth-at-n
  (is (= [1.0 1000.0 1000000.0] (mapv #(cost/work % 1000) [:constant :linear :quadratic])))
  (is (< 9.96 (cost/work :log 1000) 9.97))
  (is (< 9965.0 (cost/work :n-log-n 1000) 9966.0)))

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
  (testing "wall time has 3x slack"
    (is (:ok? (cost/judge linear (rows linear (fn [n] {:ns (* 290.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)})))))
    (is (not (:ok? (cost/judge linear (rows linear (fn [n] {:ns (* 310.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)}))))))))

(deftest a-second-pass-is-steeper-even-inside-the-slack
  ;; quadratic bytes that stay under the linear estimate at every size: only the growth sees them
  (let [e (assoc linear :per-n {:bytes 100.0} :fixed {} :sizes [10 100 1000])
        v (cost/judge e (rows e (fn [n] {:bytes (* 0.09 n n)})))]
    (is (empty? (:over v)))
    (is (= [:bytes] (map :dim (:steeper v))))
    (is (< 1.9 (:exponent (first (:steeper v))) 2.1))
    (is (not (:ok? v)))))

(deftest n-log-n-under-a-linear-estimate-is-not-steeper
  (let [e (assoc linear :per-n {:ns 1000.0} :fixed {})]
    (is (empty? (:steeper (cost/judge e (rows e (fn [n] {:ns (cost/work :n-log-n n)}))))))))

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
   :sizes [100 1000 10000]})

(deftest a-one-pass-function-meets-its-estimate
  (let [{:keys [rows verdict]} (cost/run estimate)]
    (is (= (cost/sizes [100 1000 10000] (seon.config/value :seon.config.proofs/cost-max-n)) (mapv :n rows)))
    (when seon.contract/on?
      (is (= (mapv #(double (inc %)) (mapv :n rows)) (mapv #(get-in % [:measured :calls]) rows)) "calls are exact: n + 1"))
    (is (:ok? verdict) (cost/explain estimate rows verdict))))

(deftest a-scan-inside-the-loop-is-red-and-says-why
  (let [e (assoc estimate :of 'seon.cost-test/rescanning)
        {:keys [rows verdict]} (cost/run e)
        text (cost/explain e rows verdict)]
    (is (not (:ok? verdict)))
    (is (< (count rows) 3) "it stops at the first size over its estimate")
    (is (re-find #"OVER: ns" text))
    (is (re-find #"one pass" text) "the explanation restates the work the data needs")))

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
   :sorted/assoc {:what "assoc of a new integer key into a sorted map" :setup #(vector (vec (shuffle (range %))))
                  :f (fn [v] (reduce (fn [m k] (assoc m k k)) (sorted-map) v))}
   :sort/compare {:what "sort of shuffled integers, per n log2 n" :setup #(vector (vec (shuffle (range %)))) :f (fn [v] (sort v))
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
   :db/pull {:what "d/pull of one attribute of an entity, by entity id (per pull)"
             :setup (fn [n] (ts/test-branch! (fn [_ conn]
                              (d/transact conn (vec (for [i (range n)] {:note/text (str "n" i) :note/agent :u})))
                              [(d/db conn) (mapv :e (d/datoms (d/db conn) :aevt :note/text))])))
             :f (fn [db es] (reduce (fn [a e] (d/pull db [:note/text] e) a) 0 es))}
   :db/pull-attr {:what "each attribute of a d/pull beyond its first (per attribute)"
                  :setup (fn [n] (ts/test-branch! (fn [_ conn]
                                   (d/transact conn [{:note/text "x" :note/agent :u}])
                                   [(d/db conn) (first (map :e (d/datoms (d/db conn) :aevt :note/text))) n])))
                  :f (fn [db e n] (dotimes [_ n] (d/pull db [:note/text :note/agent] e)))
                  :minus :db/pull}})

(defn measure-unit
  "Unit k measured at n units: {:ns :bytes} per unit."
  {:malli/schema [:=> [:cat :keyword pos-int?] [:map [:ns :double] [:bytes :double]]]}
  [k n]
  (let [{:keys [setup f per per-char minus]} (unit-benches k)
        args (setup n)
        units (cond per-char (double (count (if (string? (first args)) (first args) (pr-str (first args)))))
                    per (cost/work per n) :else (double n))
        m (cost/measure f args)
        ;; a unit measured as the difference from another (an attribute beyond a pull's first)
        b (when minus (measure-unit minus n))]
    {:ns (- (/ (:ns m) units) (:ns b 0.0)) :bytes (- (/ (:bytes m) units) (:bytes b 0.0))}))

(deftest the-unit-table-is-current
  ;; every row of the table measured again at 10k units (the table's own n: a unit's bytes at 1k
  ;; differ, 630 against 825 B for :map/assoc): within 3x of its ns, its bytes within
  ;; 1.25x (allocation is counted); a drifted row means the estimates priced by it are stale
  (doseq [[k {:keys [ns bytes]}] cost/units
          :let [m (measure-unit k 10000)]]
    (is (<= (/ ns 3.0) (:ns m) (* ns 3.0)) (str k " ns: table " ns ", measured " (:ns m) "; the row now: " (assoc m :what (:what (unit-benches k)))))
    (is (<= (- (/ bytes 1.25) 1.0) (:bytes m) (+ (* bytes 1.25) 1.0)) (str k " bytes: table " bytes ", measured " (:bytes m)))))

(deftest an-open-overrun-is-accepted-until-it-regresses
  ;; 400 ns per integer against a 100 ns estimate: 4x, over the 3x slack (4e6 ns at n=10000)
  (let [m (fn [n] {:ns (* 400.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)})]
    (testing "within the ratchet (1.5x) of what was measured when its task was filed: green, still reported"
      (let [e (assoc linear :overrun {:task "seon.cost/demo-overrun" :measured {:ns 3000000.0}})
            v (cost/judge e (rows e m))]
        (is (:ok? v))
        (is (= "seon.cost/demo-overrun" (get-in v [:accepted :task])))
        (is (seq (get-in v [:accepted :over])))
        (is (re-find #"OVERRUN accepted while task seon.cost/demo-overrun" (cost/explain e (rows e m) v)))))
    (testing "past the ratchet: a regression, red"
      (let [e (assoc linear :overrun {:task "seon.cost/demo-overrun" :measured {:ns 2000000.0}})
            v (cost/judge e (rows e m))]
        (is (not (:ok? v)))
        (is (re-find #"REGRESSION" (cost/explain e (rows e m) v)))))))
