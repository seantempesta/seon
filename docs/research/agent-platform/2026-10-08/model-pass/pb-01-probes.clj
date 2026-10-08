;; PB-01 budget probes (lane pb-01, HEAD 48191071f), run in a throwaway root through
;; seon.operator/prepl-value!, driver as in base-miss-probes.clj. Results: pb-01-notes.md.

;;; p3 (MCP sequence; base misses from seon.cache/status)
(do
  (require 'seon.pass-probe)
  (let [P (fn [s] @(ns-resolve 'seon.pass-probe s))
        misses #(get-in (seon.cache/status) [:seon.cache/kinds :seon.sci.eval/base-context :seon.cache/misses] 0)
        agent-id (str "pb01-mcp-" (seon.id/id))
        stages '#{seon.sci.eval/acquire! seon.sci.eval/base-ctx seon.sci.eval/fork-for-turn seon.cluster.agent/acquire-context!}
        step (fn [label code]
               (let [m0 (misses)
                     t0 (System/nanoTime)
                     b0 (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean))
                     mark (seon.profile/begin)
                     r (((P 'mcp-pass) {"agent" agent-id "cluster" "default" "code" code "namespace" "scratch.pu"}))
                     ex (seon.profile/explain mark 100000)
                     b1 (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean))]
                 {:step label :ms (Math/round (/ (- (System/nanoTime) t0) 1e6)) :mb (Math/round (/ (- b1 b0) 1e6))
                  :error? (:seon.pass-probe/error? r)
                  :base-misses (- (misses) m0)
                  :stages (into (sorted-map) (keep (fn [m] (when (stages (:seon.profile/sym m)) [(:seon.profile/sym m) [(:seon.profile/calls m) (:seon.profile/total-ms m)]]))) (:seon.profile/growths ex))}))]
    [(step :first "(+ 1 2)")
     (step :second "(+ 1 2)")
     (step :third "(+ 1 2)")
     (step :settings "(my.agent/settings! {:seon.config.run/max-episode-runs 3})")
     (step :after-settings "(+ 1 2)")
     (step :defn "(defn pu-f [x] (inc x))")
     (step :after-defn "(+ 1 2)")
     (step :after-defn-2 "(+ 1 2)")
     {:retained-bases (count (seon.cache/retained :seon.sci.eval/base-context))
      :base-kind (get-in (seon.cache/status) [:seon.cache/kinds :seon.sci.eval/base-context])}]))

;;; p4 (retained heap, eight agents)
(do
  (require 'seon.pass-probe)
  (let [P (fn [s] @(ns-resolve 'seon.pass-probe s))
        bases #(count (seon.cache/retained :seon.sci.eval/base-context))
        live (fn [] (dotimes [_ 3] (System/gc) (Thread/sleep 200))
               (Math/round (/ (.getUsed (.getHeapMemoryUsage (java.lang.management.ManagementFactory/getMemoryMXBean))) 1e6)))
        pass! (fn [agent-id code] (((P 'mcp-pass) {"agent" agent-id "cluster" "default" "code" code "namespace" "scratch.pu"})))
        ids (mapv (fn [i] (str "pb01-heap-" i "-" (seon.id/id))) (range 8))
        h0 (live) c0 (bases)
        rows (reduce (fn [acc n]
                       (doseq [id (subvec ids (count (:done (peek acc) [])) n)] (pass! id "(def pu-big (vec (range 10)))"))
                       (conj acc {:agents n :live-mb (live) :bases (bases) :done (subvec ids 0 n)}))
                     [] [1 2 4 8])]
    {:baseline-live-mb h0 :baseline-bases c0
     :rows (mapv #(dissoc % :done) rows)
     :base-kind (get-in (seon.cache/status) [:seon.cache/kinds :seon.sci.eval/base-context])}))

;;; p7 (a new agent's first MCP pass, top profile stages)
(do
  (require 'seon.pass-probe)
  (let [P (fn [s] @(ns-resolve 'seon.pass-probe s))
        agent-id (str "pb01-first-" (seon.id/id))
        mx (java.lang.management.ManagementFactory/getThreadMXBean)
        b0 (.getTotalThreadAllocatedBytes mx) t0 (System/nanoTime)
        mark (seon.profile/begin)
        r (((P 'mcp-pass) {"agent" agent-id "cluster" "default" "code" "(+ 1 2)" "namespace" "scratch.pu"}))
        ex (seon.profile/explain mark 100000)
        b1 (.getTotalThreadAllocatedBytes mx)]
    {:ms (Math/round (/ (- (System/nanoTime) t0) 1e6)) :mb (Math/round (/ (- b1 b0) 1e6))
     :top (->> (:seon.profile/growths ex)
               (sort-by :seon.profile/total-ms >)
               (take 25)
               (mapv (fn [m] [(:seon.profile/sym m) (:seon.profile/calls m) (:seon.profile/total-ms m)
                              (some-> (:seon.profile/allocated-bytes m) (/ 1e6) Math/round)])))
     :keys (keys (first (:seon.profile/growths ex)))}))

;;; p9 (model passes, fixture provider)
(do
  (require 'seon.pass-probe)
  (let [m0 (get-in (seon.cache/status) [:seon.cache/kinds :seon.sci.eval/base-context :seon.cache/misses] 0)
        rows (seon.pass-probe/run-model "default" 3 #{2})]
    {:base-misses (- (get-in (seon.cache/status) [:seon.cache/kinds :seon.sci.eval/base-context :seon.cache/misses] 0) m0)
     :rows (mapv (fn [r] {:kind (:seon.pass-probe/kind r) :seq (:seon.pass-probe/sequence r)
                          :ms (Math/round (double (:seon.pass-probe/wall-ms r)))
                          :mb (Math/round (/ (:seon.pass-probe/allocated-bytes r) 1e6))
                          :base-ctx? (boolean (some #(= 'seon.sci.eval/base-ctx (:seon.profile/sym %)) (:seon.pass-probe/top-ms r)))
                          :complete? (:seon.pass-probe/complete? r)})
                 rows)}))
