;; Run through eval_clj, mode jvm, cluster default, agent astra-measure.
;; No Vars are installed. Use a fresh case agent for another definition run.
(require 'seon.mcp 'clojure.edn 'seon.db 'seon.config 'seon.operator.runtime)
(let [mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
      os ^com.sun.management.OperatingSystemMXBean (java.lang.management.ManagementFactory/getOperatingSystemMXBean)
      call (fn [code] (#'seon.mcp/eval-clj {:seon.mcp/cluster "default"
                                           :seon.mcp/agent "astra-measure-cases"
                                           :seon.mcp/code code}))
      measure (fn [[label code]]
                (let [a (.getTotalThreadAllocatedBytes mx)
                      c (.getProcessCpuTime os)
                      t (System/nanoTime)
                      [texts failed?] (call code)
                      elapsed (/ (- (System/nanoTime) t) 1e6)
                      allocated (- (.getTotalThreadAllocatedBytes mx) a)
                      cpu (- (.getProcessCpuTime os) c)]
                  {:case label :window {:ms elapsed :alloc-mb (/ allocated 1e6) :cpu-ms (/ cpu 1e6)}
                   :failed? failed? :reply (clojure.edn/read-string (last texts))
                   :texts (vec (butlast texts))}))
      first-three (mapv measure
        [[:trivial "(+ 1 1)"]
         [:define "(in-ns 'my.agents.astra-measure-cases) (defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)) (clojure.test/deftest tiny-test (clojure.test/is (= 2 (tiny 1))))"]
         [:redefine "(defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (+ 1 x))"]])
      custody (#'seon.mcp/execution (#'seon.mcp/instance @seon.operator.runtime/running-instances "default") "astra-measure-cases" nil)
      connection (get-in custody [:seon.turn.loop/cluster :seon.db/connection])
      configure (fn [step?]
                  (seon.db/call-with-root-bindings
                   #(seon.config/apply! {:seon.db/connection connection :seon.boot/cluster-name "default"
                                         :seon.config/manifest {:seon.config.test/step? step?}})))]
  ;; Only this disposable branch changes. The new test must be recorded before check,
  ;; and the automatic test step must not already have run it.
  (try
    (configure false)
    (call "(clojure.test/deftest fresh-cost-test (clojure.test/is (= 3 (tiny 2))))")
    (let [checked (measure [:check "(select-keys (my.test/check {:seon.test/changed ['my.agents.astra-measure-cases/fresh-cost-test]}) [:seon.test/executed-count :seon.test/reused-count :seon.test/passed?])"])]
      (configure true)
      (into (conj first-three checked)
            (mapv measure
                  [[:write "(my.program/write-branch! {:seon.fn/root \"/Users/sean/src/seon/tmp/astra-measure\"})"]
                   [:source "(my.program/source 'seon.id/id)"]])))
    (finally (configure true))))
