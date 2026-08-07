(ns w4-fork-install-cost
  "W4 — per-fork installation cost for a committed agent-authored corpus.

  Phase 0 finding 2: an interpreted fn pins the ctx it was evaluated
  against, so a defn pre-evaluated once into the cluster's shared base ctx
  resolves the BASE environment forever. The two repairs are (a) install
  lazily into the turn fork on first call, or (b) re-create the whole
  corpus in the fork at fork time. This measures both, plus the fork
  baseline they are compared against.

  Everything here is measurement. No production namespace is loaded, no
  cluster is touched, nothing is written.

  Run: see docs/prds/sci-execution-runtime/research/env-phase1-w4-probes/RUN.md"
  (:require [clojure.string :as string]
            [sci.core :as sci]))

;;; ---------------------------------------------------------------------------
;;; Synthetic corpus, sized from w4_corpus_shape.clj measured on src/:
;;;   source chars  median 336  p90 1232
;;;   reader nodes  median  40  p90  128
;;; `body-blocks` tunes the node count; 3 blocks lands near the median.

(defn- defn-source
  "One realistic agent-authored defn: destructuring, let, threading,
  collection work, a conditional, and a call into another corpus fn."
  [index body-blocks previous-index]
  (let [nm (str "f" index)
        callee (when previous-index (str "f" previous-index))
        block
        (fn [b]
          (str "        s" b " (reduce + 0 (map (fn [v] (* v " (inc b) ")) xs))\n"
               "        m" b " (into {} (map (fn [v] [v (str \"k\" v)]) xs))\n"
               "        t" b " (->> xs (filter odd?) (take 3) (mapv inc))\n"))]
    (str "(defn " nm "\n"
         "  \"Synthetic corpus function " index ".\"\n"
         "  [{:keys [xs n] :or {xs [1 2 3 4 5] n 1}}]\n"
         "  (let [base (+ n " index ")\n"
         (apply str (map block (range body-blocks)))
         "        acc (cond (> base 100) :large (> base 10) :medium :else :small)]\n"
         "    {:fn/name \"" nm "\"\n"
         "     :fn/acc acc\n"
         "     :fn/sum (+ base "
         (string/join " " (map #(str "s" %) (range body-blocks))) ")\n"
         "     :fn/keys (into [] (comp (map key) (take 2)) m0)\n"
         "     :fn/tail t0\n"
         (if callee
           (str "     :fn/inner (when (pos? n) (" callee " {:xs xs :n (dec n)}))}))\n")
           "     :fn/inner nil}))\n"))))

(defn corpus
  "Vector of {:sym :source :form} for `n` synthetic defns.

  `:chained` — f_i calls f_(i-1), the realistic shape for an agent that
  builds on its own committed functions; installing f_k requires its whole
  transitive callee chain, because sci refuses a definition whose callee is
  unresolved (see `resolution`).
  `:flat` — every function is independent; the best case for lazy install."
  ([n body-blocks] (corpus n body-blocks :chained))
  ([n body-blocks shape]
   (mapv (fn [i]
           (let [src (defn-source i body-blocks
                       (when (and (= :chained shape) (pos? i)) (dec i)))]
             {:sym (symbol (str "f" i))
              :source src
              :form (read-string src)}))
         (range n))))

(defn- node-count [form]
  (if (coll? form)
    (reduce + 1 (map node-count (if (map? form) (apply concat form) (seq form))))
    1))

(defn corpus-shape
  "Measured size of the generated corpus, for comparison against src/."
  [c]
  (let [cs (map (comp count :source) c)
        ns- (map (comp node-count :form) c)]
    {:n (count c)
     :median-chars (nth (vec (sort cs)) (quot (count cs) 2))
     :median-nodes (nth (vec (sort ns-)) (quot (count ns-) 2))
     :total-chars (reduce + cs)}))

;;; ---------------------------------------------------------------------------
;;; Timing harness. Warm, then time, then take the median of several rounds.

(defn- bench
  "Median ns per iteration over `rounds` timed rounds of `iterations`."
  [{:keys [warm iterations rounds] :or {warm 3 iterations 5 rounds 5}} thunk]
  (dotimes [_ warm] (thunk))
  (let [samples (vec (sort (for [_ (range rounds)]
                             (let [t0 (System/nanoTime)]
                               (dotimes [_ iterations] (thunk))
                               (/ (double (- (System/nanoTime) t0)) iterations)))))]
    {:ns (Math/round ^double (nth samples (quot (count samples) 2)))
     :min-ns (Math/round ^double (first samples))
     :max-ns (Math/round ^double (peek samples))}))

(defn- ns->ms [n] (/ (Math/round (/ (double n) 1000.0)) 1000.0))

;;; ---------------------------------------------------------------------------
;;; The base context. Host leaves only, mirroring the design target where
;;; first-party functions are host Vars and only agent-authored program
;;; rows are interpreted.

(defn- new-base []
  (sci/init {:namespaces {'user {}}}))

(defn- install-corpus!
  "Evaluate every corpus form into `ctx`. This is exactly what acquire!
  does today against the base ctx, and what eager fork re-creation would
  do against every turn fork."
  [ctx c]
  (doseq [{:keys [form]} c]
    (sci/eval-form ctx form))
  ctx)

(defn- read-and-install-corpus!
  "Same, but reading each source string first — the honest cost when the
  installer starts from the durable :seon.fn/source fact rather than a
  cached form."
  [ctx c]
  (doseq [{:keys [source]} c]
    (sci/eval-form ctx (read-string source)))
  ctx)

;;; ---------------------------------------------------------------------------
;;; 1. Fork baseline — what a turn costs before any installation.

(defn- fork-baseline [base]
  (bench {:iterations 2000 :rounds 7} #(sci/fork base)))

;;; ---------------------------------------------------------------------------
;;; 2. Eager: re-create all N in the fork at fork time.

(defn- eager [base c]
  (let [forms-only (bench {:iterations 1 :rounds 5}
                          #(install-corpus! (sci/fork base) c))
        with-read (bench {:iterations 1 :rounds 5}
                         #(read-and-install-corpus! (sci/fork base) c))
        n (count c)]
    {:n n
     :install-preread {:total-ms (ns->ms (:ns forms-only))
                       :per-defn-ns (Math/round (/ (double (:ns forms-only)) n))}
     :install-with-read {:total-ms (ns->ms (:ns with-read))
                         :per-defn-ns (Math/round (/ (double (:ns with-read)) n))}
     :read-share-percent
     (/ (Math/round (* 1000.0 (/ (- (double (:ns with-read)) (:ns forms-only))
                                 (double (:ns with-read)))))
        10.0)}))

;;; ---------------------------------------------------------------------------
;;; 3. Lazy: install only the functions a turn actually calls.
;;;
;;; Two shapes: a flat corpus (each entry point installs alone) and the
;;; chained corpus (each entry point drags its transitive callee chain,
;;; because `resolution` below shows sci refuses an unresolved callee).

(defn- lazy-flat
  "Best case: the turn touches `k` independent functions out of N."
  [base c k]
  (let [n (count c)
        entries (mapv #(long (* (inc %) (/ n (inc k)))) (range k))
        install-touched!
        (fn [ctx]
          (doseq [e entries]
            (sci/eval-form ctx (:form (nth c (min e (dec n))))))
          ctx)
        b (bench {:iterations 1 :rounds 5} #(install-touched! (sci/fork base)))]
    (assoc b
           :corpus-shape :flat
           :corpus-n n
           :entry-points k
           :functions-installed k
           :total-ms (ns->ms (:ns b)))))

(defn- lazy-chained
  "Realistic case: each entry point drags its whole transitive callee chain
  in, because sci refuses a definition with an unresolved callee."
  [base c k]
  (let [n (count c)
        entries (mapv #(long (* (inc %) (/ n (inc k)))) (range k))
        deepest (min (dec n) (apply max entries))
        install-closure!
        (fn [ctx]
          (doseq [{:keys [form]} (subvec c 0 (inc deepest))]
            (sci/eval-form ctx form))
          ctx)
        b (bench {:iterations 1 :rounds 5} #(install-closure! (sci/fork base)))]
    (assoc b
           :corpus-shape :chained
           :corpus-n n
           :entry-points k
           :functions-installed (inc deepest)
           :total-ms (ns->ms (:ns b)))))

;;; ---------------------------------------------------------------------------
;;; 4. Does sci refuse a defn whose callee is not yet defined?
;;;    Decides whether lazy install is per-function or per-closure.

(defn- resolution []
  (let [ctx (new-base)
        eval-outcome
        (try (sci/eval-form ctx (read-string "(defn a [] (undefined-callee 1))"))
             :accepted
             (catch Exception e [:refused-at-definition (ex-message e)]))
        call-outcome
        (when (= :accepted eval-outcome)
          (try (sci/eval-form ctx (read-string "(a)"))
               :called
               (catch Exception e [:refused-at-call (ex-message e)])))]
    {:defining-with-unknown-callee eval-outcome
     :calling-it call-outcome
     :finding (if (= :accepted eval-outcome)
                "sci ACCEPTS the definition; lazy install can be per-function and pull callees on demand"
                "sci REFUSES the definition; lazy install must install the transitive closure up front")}))

;;; ---------------------------------------------------------------------------
;;; 5. Call cost after installation — is install amortized over a turn?

(defn- call-cost [base c]
  (let [ctx (install-corpus! (sci/fork base) c)
        last-sym (:sym (peek c))
        f (sci/eval-form ctx (read-string (str "(fn [] (" last-sym " {:xs [1 2 3] :n 1}))")))]
    (assoc (bench {:iterations 20000 :rounds 5} f)
           :called (str last-sym))))

;;; ---------------------------------------------------------------------------
;;; 6. Memory: retained bytes per installed defn, per fork.

(defn- used-heap []
  (dotimes [_ 4] (System/gc) (Thread/sleep 40))
  (let [rt (Runtime/getRuntime)]
    (- (.totalMemory rt) (.freeMemory rt))))

(defn- memory-per-fork [base c forks]
  (let [before (used-heap)
        held (mapv (fn [_] (install-corpus! (sci/fork base) c)) (range forks))
        after (used-heap)
        delta (- after before)]
    {:forks forks
     :corpus-n (count c)
     :retained-bytes delta
     :bytes-per-fork (long (/ delta (max 1 forks)))
     :bytes-per-defn-per-fork (long (/ delta (max 1 (* forks (count c)))))
     :live-forks (count held)
     :note "Runtime totalMemory-freeMemory after 4 gc rounds; indicative, not a heap dump"}))

(defn- empty-fork-memory [base forks]
  (let [before (used-heap)
        held (mapv (fn [_] (sci/fork base)) (range forks))
        after (used-heap)]
    {:forks forks
     :retained-bytes (- after before)
     :bytes-per-fork (long (/ (- after before) (max 1 forks)))
     :live-forks (count held)}))

;;; ---------------------------------------------------------------------------

(defn- warm-jit!
  "Install a corpus into throwaway forks so the measured rounds are not
  paying for sci's own JIT warmup. Small-N rounds are otherwise dominated
  by it (measured: N=10 reported 5x the per-defn cost of N=1000 without
  this step)."
  [base]
  (let [c (corpus 200 3)]
    (dotimes [_ 5] (install-corpus! (sci/fork base) c)))
  nil)

(defn run
  "Execute the full W4 measurement and return one data map."
  []
  (let [c10 (corpus 10 3)
        c100 (corpus 100 3)
        c1000 (corpus 1000 3)
        c100-big (corpus 100 10)
        flat100 (corpus 100 3 :flat)
        flat1000 (corpus 1000 3 :flat)
        small100 (corpus 100 1)
        small1000 (corpus 1000 1)
        base (new-base)
        _ (warm-jit! base)]
    {:probe/name "w4 — per-fork installation cost, lazy vs eager"
     :probe/corpus-shape
     {:src-reference {:median-chars 336 :median-nodes 40
                      :p75-chars 664 :p90-chars 1232 :p90-nodes 128
                      :source "w4_corpus_shape.clj over src/, 1428 defns"}
      :small-1-block (corpus-shape small100)
      :mid-3-block (corpus-shape c100)
      :large-10-block (corpus-shape c100-big)}
     :probe/fork-baseline (fork-baseline base)
     :probe/resolution (resolution)
     :probe/eager {:mid-n10 (eager base c10)
                   :mid-n100 (eager base c100)
                   :mid-n1000 (eager base c1000)
                   :small-n100 (eager base small100)
                   :small-n1000 (eager base small1000)
                   :large-n100 (eager base c100-big)}
     :probe/lazy {:flat-n1000-k1 (lazy-flat base flat1000 1)
                  :flat-n1000-k5 (lazy-flat base flat1000 5)
                  :flat-n1000-k20 (lazy-flat base flat1000 20)
                  :flat-n100-k5 (lazy-flat base flat100 5)
                  :chained-n1000-k1 (lazy-chained base c1000 1)
                  :chained-n1000-k5 (lazy-chained base c1000 5)
                  :chained-n1000-k20 (lazy-chained base c1000 20)}
     :probe/call-cost (call-cost base c100)
     :probe/memory {:empty-forks (empty-fork-memory base 50)
                    :small-n1000-x10-forks (memory-per-fork base small1000 10)
                    :mid-n100-x50-forks (memory-per-fork base c100 50)
                    :mid-n1000-x10-forks (memory-per-fork base c1000 10)}}))
