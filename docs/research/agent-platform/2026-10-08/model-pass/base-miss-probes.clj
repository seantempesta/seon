;; Base-miss and retained-heap probes (lane pass-uncovered, HEAD 81020da6a), run in a throwaway root through
;; seon.operator/prepl-value! (driver below). Each top-level form is one driver call; results are in reconciled-plan.md.
;; Driver (bb --config /dev/null --classpath "script:src:resources" driver.clj FORM-FILE):
;; (require '[seon.operator :as op] '[clojure.pprint :as pp])
;; (let [root "/Users/sean/src/seon/tmp/pass-uncovered/root"
;;       ad (first (op/advertisements root))]
;;   (when-not ad (throw (ex-info "No owned JVM" {:root root})))
;;   (pp/pprint (op/prepl-value! ad (slurp (first *command-line-args*)) 300000 nil 300000)))

;;; p2
(do
  (require 'seon.pass-probe)
  (let [P (fn [s] @(ns-resolve 'seon.pass-probe s))
        cache-var @#'seon.sci.eval/base-context-cache
        keys0 (set (keys @cache-var))
        plan [[:ordinary 0] [:ordinary 1] [:ordinary 2] [:defn 2] [:after-defn 2] [:ordinary 3]]
        agent-id (str "pu-model-" (seon.id/id))
        ledger (atom []) timings (atom [])
        stages '#{seon.sci.eval/acquire! seon.sci.eval/base-ctx seon.sci.eval/fork-for-turn
                  seon.cluster.agent/acquire-context! seon.cluster.agent/acquire-once!
                  seon.turn/system-turn seon.turn/resume-turn seon.turn/call-turn seon.db/transact-call
                  seon.cluster.prompt/prompt seon.turn/declared-sources}
        order (atom [])
        out
        (seon.test-support/with-provider
         ledger (seon.test-support/reply-queue (mapv (fn [[k i]] ((P 'fenced) ((P 'texts) k i))) plan)) timings
         (fn [server]
           (((P 'mcp-pass) {"agent" agent-id "cluster" "default"
                            "code" (str "(my.agent/settings! " (seon.id/printed (merge (seon.test-support/provider-config server)
                                                                                     {:seon.config.ai/no-provider false
                                                                                      :seon.config.run/max-episode-runs 1})) ")")}))
           (let [world (assoc ((P 'agent-world) "default" agent-id) :seon.pass-probe/ledger ledger :seon.pass-probe/stages stages)]
             (try
               (mapv (fn [[kind _]]
                       (let [before (set (keys @cache-var))
                             r (seon.pass-probe/window world ((P 'model-pass) world))
                             after (keys @cache-var)]
                         (swap! order into (remove before after))
                         {:kind kind :ms (Math/round (double (:seon.pass-probe/wall-ms r)))
                          :mb (Math/round (/ (:seon.pass-probe/allocated-bytes r) 1e6))
                          :new-base-keys (count (remove before after))
                          :complete? (:seon.pass-probe/complete? r)
                          :stages (into (sorted-map) (map (fn [[s v]] [s [(:seon.profile/calls v) (:seon.profile/total-ms v)]])) (:seon.pass-probe/stages r))}))
                     plan)
               (finally (some-> (:seon.pass-probe/host ((P 'agent-world) "default" agent-id)) seon.cluster.host/retire!))))))
        revs (fn [k] (get-in k [0 :datahike.cache/attribute-revisions]))
        diff (fn [a b] (vec (sort (for [k (into (set (keys (revs a))) (keys (revs b)))
                                       :when (not= (get (revs a) k) (get (revs b) k))] k))))
        ks @order]
    {:passes out
     :new-keys (count ks)
     :key-meta (mapv (fn [k] [(get-in k [0 :datahike.cache/conservative-revision]) (get-in k [0 :datahike.cache/generation]) (str (get-in k [0 :datahike.cache/connection-id])) (str (nth k 1))]) ks)
     :diffs (mapv (fn [[a b]] (diff a b)) (partition 2 1 (concat (take 1 (filter keys0 (keys @cache-var))) ks)))}))

;;; p3
(do
  (require 'seon.pass-probe)
  (let [P (fn [s] @(ns-resolve 'seon.pass-probe s))
        cache-var @#'seon.sci.eval/base-context-cache
        agent-id (str "pu-mcp-" (seon.id/id))
        stages '#{seon.sci.eval/acquire! seon.sci.eval/base-ctx seon.sci.eval/fork-for-turn seon.cluster.agent/acquire-context!}
        step (fn [label code]
               (let [before (set (keys @cache-var))
                     world (when (seq before) nil)
                     t0 (System/nanoTime)
                     b0 (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean))
                     mark (seon.profile/begin)
                     r (((P 'mcp-pass) {"agent" agent-id "cluster" "default" "code" code "namespace" "scratch.pu"}))
                     ex (seon.profile/explain mark 100000)
                     b1 (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean))]
                 {:step label :ms (Math/round (/ (- (System/nanoTime) t0) 1e6)) :mb (Math/round (/ (- b1 b0) 1e6))
                  :error? (:seon.pass-probe/error? r)
                  :new-base-keys (count (remove before (keys @cache-var)))
                  :stages (into (sorted-map) (keep (fn [m] (when (stages (:seon.profile/sym m)) [(:seon.profile/sym m) [(:seon.profile/calls m) (:seon.profile/total-ms m)]]))) (:seon.profile/growths ex))}))]
    [(step :first "(+ 1 2)")
     (step :second "(+ 1 2)")
     (step :third "(+ 1 2)")
     (step :settings "(my.agent/settings! {:seon.config.run/max-episode-runs 3})")
     (step :after-settings "(+ 1 2)")
     (step :defn "(defn pu-f [x] (inc x))")
     (step :after-defn "(+ 1 2)")
     (step :after-defn-2 "(+ 1 2)")]))

;;; p4
(do
  (require 'seon.pass-probe)
  (let [P (fn [s] @(ns-resolve 'seon.pass-probe s))
        cache-var @#'seon.sci.eval/base-context-cache
        live (fn [] (dotimes [_ 3] (System/gc) (Thread/sleep 200))
               (Math/round (/ (.getUsed (.getHeapMemoryUsage (java.lang.management.ManagementFactory/getMemoryMXBean))) 1e6)))
        pass! (fn [agent-id code] (((P 'mcp-pass) {"agent" agent-id "cluster" "default" "code" code "namespace" "scratch.pu"})))
        ids (mapv (fn [i] (str "pu-heap-" i "-" (seon.id/id))) (range 8))
        h0 (live) c0 (count @cache-var)
        rows (reduce (fn [acc n]
                       (doseq [id (subvec ids (count (:done (peek acc) [])) n)] (pass! id "(def pu-big (vec (range 10)))"))
                       (conj acc {:agents n :live-mb (live) :bases (count @cache-var) :done (subvec ids 0 n)}))
                     [] [1 2 4 8])]
    {:baseline-live-mb h0 :baseline-bases c0
     :rows (mapv #(dissoc % :done) rows)
     :agents ids}))

;;; p5
(let [cache-var @#'seon.sci.eval/base-context-cache
      live (fn [] (dotimes [_ 3] (System/gc) (Thread/sleep 200))
             (Math/round (/ (.getUsed (.getHeapMemoryUsage (java.lang.management.ManagementFactory/getMemoryMXBean))) 1e6)))
      before (live)
      n (count @cache-var)
      ks (vec (keys @cache-var))
      agent-key-share (count (filter #(vector? (get-in % [0 :datahike.cache/connection-id])) ks))
      _ (reset! cache-var (clojure.core.cache/lru-cache-factory {} :threshold 4))
      after (live)]
  {:live-before-mb before :bases n :agent-branch-keyed agent-key-share :live-after-evicting-all-mb after
   :freed-mb (- before after)})

