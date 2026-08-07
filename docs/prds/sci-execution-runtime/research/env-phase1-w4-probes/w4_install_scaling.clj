(ns w4-install-scaling
  "W4 supplement — per-defn install cost as a function of defn SIZE.

  The main probe's smallest synthetic defn is already ~130 reader nodes,
  which sits at the p90 of first-party src/ (median 40). This walks the
  size axis down to the measured median so the per-defn figure quoted in
  the report is not silently a p90 figure.

  Run: see docs/prds/sci-execution-runtime/research/env-phase1-w4-probes/RUN.md"
  (:require [sci.core :as sci]))

(defn- node-count [form]
  (if (coll? form)
    (reduce + 1 (map node-count (if (map? form) (apply concat form) (seq form))))
    1))

(defn- sized-defn
  "A defn of roughly `let-bindings` bindings, independent of all others."
  [index let-bindings]
  (str "(defn g" index "\n"
       "  \"Sized corpus function " index ".\"\n"
       "  [xs n]\n"
       "  (let ["
       (apply str (for [b (range let-bindings)]
                    (str "a" b " (+ n " (* index (inc b)) " (count xs))\n        ")))
       "acc (if (> n 5) :big :small)]\n"
       "    {:g/acc acc :g/n n"
       (apply str (for [b (range let-bindings)] (str " :g/a" b " a" b)))
       "}))"))

(defn- corpus [n let-bindings]
  (mapv (fn [i] (read-string (sized-defn i let-bindings))) (range n)))

(defn- bench [rounds thunk]
  (dotimes [_ 3] (thunk))
  (let [samples (vec (sort (for [_ (range rounds)]
                             (let [t0 (System/nanoTime)]
                               (thunk)
                               (- (System/nanoTime) t0)))))]
    (nth samples (quot (count samples) 2))))

(defn- measure [base n let-bindings]
  (let [forms (corpus n let-bindings)
        nodes (node-count (first forms))
        total (bench 7 (fn []
                         (let [ctx (sci/fork base)]
                           (doseq [f forms] (sci/eval-form ctx f)))))]
    {:reader-nodes nodes
     :chars (count (sized-defn 0 let-bindings))
     :n n
     :total-ms (/ (Math/round (/ (double total) 1000.0)) 1000.0)
     :per-defn-us (/ (Math/round (/ (double total) n 100.0)) 10.0)}))

(defn run
  "Per-defn install cost across the measured src/ size distribution."
  []
  (let [base (sci/init {:namespaces {'user {}}})]
    ;; warm sci's own JIT before the measured rounds
    (dotimes [_ 5] (let [ctx (sci/fork base)]
                     (doseq [f (corpus 200 4)] (sci/eval-form ctx f))))
    {:probe/name "w4 — install cost vs defn size"
     :probe/src-reference {:median-nodes 40 :p75-nodes 74 :p90-nodes 128
                           :p99-nodes 259}
     :probe/by-size (mapv #(measure base 500 %) [1 2 4 8 16 32])}))
