;; The bounded probes behind docs/research/agent-platform/eval-feedback-design-2026-09-26.md §1-§2, verbatim.
;; JVM mode (read_only), session eval-feedback-design-opus, ns scratch.eval-feedback-design-opus, default pid 59671 then 70054.
;; SCI forms ran on MCP branch eval-feedback-design-opus (retired).

;; §2 collection costs (pid 59671). Output:
;; {:nano-ns 21.1 :cpu-ns 482.4 :tl-chm-merge-ns 35.8 :tl-get-ns 5.6 :alloc-ns 68.8 :cells 4207
;;  :begin-us 133.0 :explain-us 4118.8 :estimate-10k-chars-us 1.48}
(let [ns-per (fn [n f] (let [t0 (System/nanoTime)] (dotimes [_ n] (f)) (/ (double (- (System/nanoTime) t0)) n)))
      tl (ThreadLocal.) _ (.set tl (java.util.concurrent.ConcurrentHashMap.))
      k (Object.)
      mx (java.lang.management.ManagementFactory/getThreadMXBean)
      s (apply str (repeat 1000 "abcdefghij"))]
  {:begin-us (/ (ns-per 20 seon.profile/begin) 1000)
   :explain-us (let [m (seon.profile/begin)] (/ (ns-per 5 #(seon.profile/explain m 10)) 1000))
   :tl-chm-merge-ns (ns-per 200000 #(let [^java.util.concurrent.ConcurrentHashMap m (.get tl)] (.merge m k 1 (reify java.util.function.BiFunction (apply [_ a b] (+ a b))))))
   :tl-get-ns (ns-per 200000 #(.get tl))
   :nano-ns (ns-per 200000 #(System/nanoTime))
   :alloc-ns (ns-per 20000 #(.getCurrentThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx))
   :cpu-ns (ns-per 20000 #(.getCurrentThreadCpuTime mx))
   :estimate-10k-chars-us (/ (ns-per 200 #(seon.ai.tokens/estimate s)) 1000)
   :cells (count (seon.profile/cells))})

;; §1.8 per-run armed-call census (pid 70054): snapshot, then ONE SCI `(inc 1)` on the branch (:ms 229), then growth.
(defn snap [] (let [m (java.util.IdentityHashMap.)] (doseq [c (seon.profile/cells)] (.put m c [(.sum ^java.util.concurrent.atomic.LongAdder (:seon.profile/call-counter c)) (.sum ^java.util.concurrent.atomic.LongAdder (:seon.profile/total-counter c))])) m))
(defn growth [s0 t0] (let [s1 (snap) g (for [[c [calls nanos]] s1 :let [[c0 n0] (or (.get ^java.util.IdentityHashMap s0 c) [0 0]) dc (- calls c0) dn (- nanos n0)] :when (pos? dc)] [(:seon.profile/sym c) dc (quot dn 1000000)]) by (fn [f] (->> g (sort-by f >) (take 15) vec))] {:window-ms (quot (- (System/nanoTime) t0) 1000000) :total-calls (reduce + (map second g)) :distinct (count g) :by-calls (by second) :by-ms (by #(nth % 2))}))
;; Output: {:window-ms 3200 :total-calls 39950 :distinct 288
;;  :by-calls [[seon.db/database-value? 7456 2] [seon.profile/adder? 6360 1] [seon.sci.eval/ctx? 3872 0]
;;             [seon.sci.eval/copy-host-var 3852 2] [seon.sci.kernel/mark-installed! 3810 3] [seon.profile/accumulator? 2120 0]
;;             [seon.db/schema-database 1939 1] [seon.schema/projection-cache-value 1441 1] [seon.db/with-declarations 1101 285] ...]
;;  :by-ms [[seon.schema/call-with-projection-state 6 1035] [seon.sci.eval/call-detecting-program-change 2 465]
;;          [seon.cluster.agent/turn-step 2 417] [seon.cluster.agent/evaluate-source! 1 408] ... [seon.sci.eval/evaluate 1 233]]}

;; §1.9 the stored receipt (SCI, on the branch): read evidence of `(inc 1)` = one read of
;; #{:seon.agent/id :seon.ns/name :seon.agent/namespace}, pattern value "mcp/eval-feedback-design-opus".
(let [d (seon.db/db) e (seon.db/q '[:find (pull ?e [*]) . :where [?t :seon.turn/id "583953bd0964"] [?e :seon.cluster.eval/run ?t]] d)]
  (into (sorted-map) (map (fn [[k v]] [k (if (string? v) (count v) v)])) e))

;; SCI replies recorded: (inc 1) first on the branch -> :ms 1307 + OVER ONE SECOND text in :out;
;; warm -> :ms 150, 135, 539 (pid 59671), 332, 229 (pid 70054); (vec (range 3000)) -> :ms 711, elided reply of §1.5.
