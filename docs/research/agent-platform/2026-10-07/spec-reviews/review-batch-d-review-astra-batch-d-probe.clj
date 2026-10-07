; Read-only review probes, run from the archived candidate with its src/resources/test/script classpath.
; No deftest or test runner is invoked. Output is measurement evidence, not Seon product output.
(require '[seon.render.value :as v] '[seon.error.refusal :as r])
(defn observe [label f]
 (let [start (System/nanoTime)]
  (try (let [value (f)] (prn {:probe label :ms (/ (- (System/nanoTime) start) 1e6) :value value}))
   (catch Throwable t (prn {:probe label :throws (.getName (class t)) :message (ex-message t)})))))
(observe :core-no-world #(let [t (v/core-text {:probe/value (vec (range 10000))})] {:tokens-estimate (/ (count t) 4.0) :config-realized? (realized? @#'v/config-defaults)}))
(observe :full-bypasses-fixed-core-budget #(let [t (v/core-text (with-meta (vec (range 2000)) {:seon.print/full true}))] {:tokens-estimate (/ (count t) 4.0)}))
(let [hostile (proxy [RuntimeException] [] (getCause [] (throw (IllegalStateException. "hostile cause"))))]
 (doseq [[label f] [[:core-text #(v/core-text hostile)] [:core-html #(v/core-html hostile)]
                    [:total-text #(v/text {:seon.render/value hostile})] [:total-html #(v/html {:seon.render/value hostile})]]]
  (observe label f)))
(doseq [n [1000 10000 100000]]
 (let [visits (volatile! 0) value {:probe/items (vec (range n))} start (System/nanoTime)
       result (#'r/referenced (fn [_] (vswap! visits inc) nil) value)]
  (prn {:probe :reference-traversal :n n :visits @visits :identical? (identical? result value) :ms (/ (- (System/nanoTime) start) 1e6)})))
(doseq [n ['seon.sci.branch-execution-test 'seon.cluster.agent-test 'seon.render.floor-test
           'seon.test.runner 'seon.turn.batch 'seon.render.walk 'seon.render.web
           'seon.cluster.boot 'seon.fault 'seon.test.accretion 'my.background]]
 (observe [:require n] #(do (require n) :loaded)))
(require '[seon.contracts-compile-test :as contracts])
(observe :contracts #(let [paths ["src/my/background.clj" "src/seon/batch.clj" "src/seon/cluster.clj" "src/seon/cluster/boot.clj" "src/seon/cluster/status.clj" "src/seon/cluster/store.clj" "src/seon/config.clj" "src/seon/db.clj" "src/seon/error.clj" "src/seon/error/refusal.clj" "src/seon/fault.clj" "src/seon/flow.clj" "src/seon/fs.clj" "src/seon/health.clj" "src/seon/mcp.clj" "src/seon/problems.clj" "src/seon/render.clj" "src/seon/render/schema.clj" "src/seon/render/value.clj" "src/seon/render/var.clj" "src/seon/render/walk.clj" "src/seon/render/web.clj" "src/seon/repl.clj" "src/seon/schema/admission.clj" "src/seon/sci/eval.clj" "src/seon/test.clj" "src/seon/test/accretion.clj" "src/seon/test/runner.clj" "src/seon/turn.clj" "src/seon/turn/batch.clj" "test/seon/error/refusal_test.clj" "test/seon/flow_test.clj" "test/seon/render/floor_test.clj" "test/seon/render/web_test.clj" "test/seon/sci/branch_execution_test.clj" "test/seon/sci/eval_test.clj" "test/seon/sci/kernel_test.clj" "test/seon/test_support_test.clj"]
 findings (contracts/check {::contracts/root "." ::contracts/paths paths})]
 {:findings findings :count (count findings)}))
(doseq [[label entry] [[:text v/text] [:html v/html]]]
 (let [unit {:seon.render/value 42 :seon.render.data/cursor {:seon.render.data/path (lazy-seq (throw (ex-info (.repeat "x" 100000) {})))}}
       start (System/nanoTime)
       shown (entry unit)
       text (if (string? shown) shown (get-in shown [2 2]))]
  (prn {:probe label :diagnostic-tokens-estimate (/ (count text) 4.0) :ms (/ (- (System/nanoTime) start) 1e6)})))

(shutdown-agents)
