;; lab: cost bounds on contracts (root's experiment, the owner's idea). Root's REPL (root.repl): full names, no ns.
(require 'malli.generator 'clojure.test.check.generators 'seon.cost 'seon.ctx 'seon.profile)

;; ---- the registry: a bound per var, as the contract's :seon.cost/bound would carry it -------------
(def lab-bounds (atom {}))
(defn lab-bound! [sym f] (swap! lab-bounds assoc sym f) sym)
(defn lab-price
  "Ops priced by seon.cost/units: {op count} -> {:ticks :bytes}."
  [ops] (reduce-kv (fn [m op n] (let [u (get seon.cost/units op)] (-> m (update :ticks + (* n (:ticks u))) (update :bytes + (* n (:bytes u))))))
                   {:ticks 0.0 :bytes 0.0} ops))
(defn lab-plus [& ms] (apply merge-with + ms))

;; ---- seon.ctx/changes: the bound is the walk's own sketch ---------------------------------------
;; the walk: identical? skips a pair; an entity's two nodes, or two collections, are walked by their
;; keys (distinct of both); any other pair is compared by = (its size); each change conj'd; then
;; sorted by its path joined as a string.
(defn lab-size "Elements of x, recursively (what = walks at worst)." [x]
  (cond (map? x) (reduce-kv (fn [n k v] (+ n 1 (lab-size k) (lab-size v))) 0 x)
        (coll? x) (reduce (fn [n v] (+ n 1 (lab-size v))) 0 x)
        :else 1))
(defn lab-walk-of
  "What changes' walk reaches from a to b: :keys visited, :eq elements compared by =, :k changes,
  :path elements of the changes' paths."
  [a b]
  (let [entity? #(and (map? %) (contains? % :db/id))
        members? #(and (map? %) (not (entity? %)) (every? entity? (vals %)))
        go (fn go [acc depth a b]
             (cond (identical? a b) acc
                   (or (nil? a) (nil? b)) (-> acc (update :k inc) (update :path + depth))
                   (or (and (entity? a) (entity? b) (= (:db/id a) (:db/id b))) (and (members? a) (members? b)))
                   (let [ks (distinct (concat (keys a) (keys b)))]
                     (reduce (fn [acc k] (go acc (inc depth) (get a k) (get b k))) (update acc :keys + (count a) (count b)) ks))
                   :else (let [acc (update acc :eq + (min (lab-size a) (lab-size b)))]
                           (if (= a b) acc (-> acc (update :k inc) (update :path + depth))))))]
    (go {:keys 0 :eq 0 :k 0 :path 0} 0 a b)))
(defn lab-log2 [x] (/ (Math/log (max 2.0 x)) (Math/log 2.0)))
(defn lab-changes-bound
  "Worst-case work of (seon.ctx/changes c0 c1), priced by unit operations: per key visited, a get of
  each side, an identical? and a call, and its place in distinct (a set conj!) and concat (a seq
  step); per element =, a compare; per change, a conj and its tuple, its path joined (a str step
  an element) and its share of the sort; the contract's checks of the answer, a call a change."
  [c0 c1]
  (let [{:keys [keys eq k path]} (lab-walk-of (if (contains? c0 :tree) (:tree c0) c0) (if (contains? c1 :tree) (:tree c1) c1))]
    (lab-price {:map/get (* 2 keys) :fn/call (* 3 keys) :set/conj! keys :seq/reduce (+ keys eq)
                :vec/conj (* 2 k) :str/append (* 24 path) :sort/compare (* k (lab-log2 k)) :fn/armed-call (+ 2 (* 4 k))})))
(lab-bound! 'seon.ctx/changes lab-changes-bound)

;; ---- generated inputs: ctx-shaped records and a transaction's edits, structure shared as patch does ----
(defn lab-text [seed n] (apply str (map #(char (+ 97 (mod (+ seed (* 7 %)) 26))) (range n))))
(defn lab-coll [es] (into (sorted-map-by @#'seon.ctx/newest-first) (map (juxt :db/id identity)) es))
(defn lab-eval [i len] {:db/id (+ 1000 i) :seon.eval/id (str "e" i) :seon.eval/by "a1"
                        :seon.eval/record {:code (str "(f " i ")") :response {:handle (symbol "result" (str "e" i)) :value (vec (range (mod i 9)))}
                                           :line (lab-text i (min len 80)) :prose (lab-text (inc i) len)}})
(defn lab-var [i len] {:db/id (+ 5000 i) :var/name (symbol "demo" (str "f" i)) :var/doc (lab-text i 40)
                       :var/form {:db/id (+ 6000 i) :form/src (lab-text (* 3 i) len)}})
(defn lab-tree
  "An agent's record: evals, runs each holding its task with its cited vars and their forms."
  [{:keys [evals runs cites len]}]
  {:db/id 1 :seon.agent/key "b/a1" :seon.agent/branch :b
   :evals (lab-coll (map #(lab-eval % len) (range evals)))
   :runs (lab-coll (for [r (range runs)]
                     {:db/id (+ 100 r) :seon.run/id (str "demo/f#" r) :seon.run/status :running
                      :task {:db/id 50 :seon.task/id "demo/f" :seon.task/plan (lab-text r (* 4 len))
                             :cited (lab-coll (map #(lab-var % len) (range cites)))}}))})
(defn lab-edit
  "Tree t after one edit [kind i]: a new eval, an eval's record replaced, the plan edited under
  every run, a cited var's form redefined, an eval gone."
  [t [kind i]]
  (let [ev (keys (:evals t)) rs (keys (:runs t))]
    (case kind
      :new-eval (let [n (+ 900 i)] (assoc-in t [:evals (+ 1000 n)] (lab-eval n 60)))
      :eval-edit (if (seq ev) (assoc-in t [:evals (nth ev (mod i (count ev))) :seon.eval/record :line] (lab-text i 30)) t)
      :plan (reduce (fn [t r] (assoc-in t [:runs r :task :seon.task/plan] (lab-text (+ 7 i) 200))) t rs)
      :cited (reduce (fn [t r] (let [cs (keys (get-in t [:runs r :task :cited]))]
                                 (if (seq cs) (assoc-in t [:runs r :task :cited (nth cs (mod i (count cs))) :var/form :form/src] (lab-text i 300)) t)))
                     t rs)
      :gone (if (seq ev) (update t :evals dissoc (nth ev (mod i (count ev)))) t))))
(def lab-gen-case
  "A record and the same record after 0-5 edits."
  (clojure.test.check.generators/let
    [shape (clojure.test.check.generators/hash-map :evals (clojure.test.check.generators/choose 0 80) :runs (clojure.test.check.generators/choose 0 4)
                                                   :cites (clojure.test.check.generators/choose 0 15) :len (clojure.test.check.generators/choose 10 400))
     edits (clojure.test.check.generators/vector (clojure.test.check.generators/tuple (clojure.test.check.generators/elements [:new-eval :eval-edit :plan :cited :gone])
                                                                                      clojure.test.check.generators/nat) 0 5)]
    (let [t (lab-tree shape)] {:shape shape :edits edits :args [t (reduce lab-edit t edits)]})))

;; ---- (1) the generative cost test: measured against the bound on the same inputs ----------------
(defn lab-check
  "f's bound against what it measured, on n generated cases: per case the ratio measured/bound in
  ticks and bytes; the worst, the median, and the cases over (ratio > 1)."
  [sym gen n seed]
  (let [f @(resolve sym) b (get @lab-bounds sym)
        rows (for [c (clojure.test.check.generators/sample (clojure.test.check.generators/resize 100 gen) n)]
               (let [m (seon.cost/measure f (:args c)) e (apply b (:args c))]
                 (assoc (select-keys c [:shape :edits]) :measured (select-keys m [:ticks :bytes :calls]) :bound e
                        :r-ticks (/ (:ticks m) (max 1.0 (:ticks e))) :r-bytes (/ (:bytes m) (max 1.0 (:bytes e))))))
        rows (vec rows)
        stat (fn [k] (let [v (vec (sort (map k rows)))] {:max (peek v) :median (nth v (quot (count v) 2)) :min (first v)}))]
    {:n (count rows) :ticks (stat :r-ticks) :bytes (stat :r-bytes)
     :over (vec (take 5 (filter #(or (> (:r-ticks %) 1.0) (> (:r-bytes %) 1.0)) rows)))}))
