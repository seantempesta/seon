(ns seon.scoreboard-and-rules-test
  "Unit, end-to-end `d/as-of` A/B hyperparameter sweep, rule minimalism, and `seed = 42`
   Malli generative proofs for `seon.signals.scoreboard`, `seon.signals.touches`, and
   the trimmed minimal native Datahike `d/q` rule set."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [malli.generator :as mg]
            [seon.code :as code]
            [seon.error :as serr]
            [seon.rule.seed :as rseed]
            [seon.schema :as schema]
            [seon.signals :as sig]
            [seon.signals.scoreboard :as sb]
            [seon.signals.touches]
            [seon.test-fixture :refer [with-test-store]]))

(deftest ^{:cost/ticks 40000000} extract-eval-touches-and-manual-lookup-separation-test
  (testing "extract-eval-touches separates general usage from manual lookups and resolves DB refs cleanly"
    (with-test-store [{:keys [conn]}]
      (let [src (str "(ns demo.touch)\n"
                     "(defn compute-score [x] (inc x))\n"
                     "(defn helper-a [x] (* 2 x))\n"
                     "(defn helper-b [x] (+ 3 x))\n")]
        (d/transact conn {:tx-data (concat (code/file-import-tx @conn "src/demo/touch.clj" src false)
                                           [{:seon.task/id :task/touch-1 :seon.task/title "Touch Task" :seon.task/done? false}])})
        (let [db @conn
              task-eid (:e (first (d/datoms db :avet :seon.task/id :task/touch-1)))
              sch-eid  (:e (first (d/datoms db :avet :seon.schema/key :seon.task/entity)))
              ha-eid   (:e (first (d/datoms db :avet :seon.code.var/name 'demo.touch/helper-a)))
              hb-eid   (:e (first (d/datoms db :avet :seon.code.var/name 'demo.touch/helper-b)))
              evals [{:seon.agent.eval/turn 1 :seon.agent.eval/handle 'result/e1 :_tx 100
                      :seon.agent.eval/code "(demo.touch/compute-score 41)"
                      :seon.agent.eval/result-schemas #{sch-eid}}
                     {:seon.agent.eval/turn 2 :seon.agent.eval/handle 'result/e2 :_tx 200
                      :seon.agent.eval/code "(do (user/source 'demo.touch/helper-a) (user/grep-src \"helper-b\") (d/pull db '[*] [:seon.task/id :task/touch-1]))"
                      :seon.agent.eval/touched [task-eid [:seon.schema/key :seon.error/entity]]}]
              all-t (sb/extract-eval-touches db evals nil)
              after-t (sb/extract-eval-touches db evals {:after-tx 100})
              no-db-t (sb/extract-eval-touches evals)]
          (is (= 2 (:eval-count all-t)))
          (is (= 1 (:eval-count after-t)))
          (is (contains? (:touched-syms all-t) 'demo.touch/compute-score))
          (is (contains? (:touched-syms all-t) 'demo.touch/helper-a))
          (is (contains? (:touched-syms all-t) 'demo.touch/helper-b))
          (is (contains? (:touched-schemas all-t) :seon.task/entity))
          (is (contains? (:touched-schemas all-t) :seon.error/entity))
          (is (contains? (:touched-eids all-t) task-eid))
          ;; Manual lookups contain ONLY source/grep-src/pull targets, NOT normal call compute-score
          (is (contains? (:manual-lookup-syms all-t) 'demo.touch/helper-a))
          (is (contains? (:manual-lookup-syms all-t) 'demo.touch/helper-b))
          (is (not (contains? (:manual-lookup-syms all-t) 'demo.touch/compute-score)))
          (is (contains? (:manual-lookup-eids all-t) ha-eid))
          (is (contains? (:manual-lookup-eids all-t) hb-eid))
          (is (contains? (:manual-lookup-eids all-t) task-eid))
          (is (= #{:source :grep-src :pull} (into #{} (map :op) (:manual-lookup-calls all-t))))
          ;; Pure no-DB mode still extracts symbols & manual lookups cleanly
          (is (contains? (:manual-lookup-syms no-db-t) 'demo.touch/helper-a))
          (is (contains? (:touched-syms no-db-t) 'demo.touch/compute-score)))))))

(deftest ^{:cost/ticks 30000000} score-shown-blocks-precision-recall-gap-and-rollups-test
  (testing "score-shown-blocks computes exact Precision, Token Precision, Recall, Recall Gap, and :by-rule / :by-fn rollups"
    (let [b1 {:entity 101 :sym 'demo/a :fn 'seon.render.entities/render-var-full :tokens 300 :rules [:rule.ctx/edited-var-on-branch]}
          b2 {:entity 102 :sym 'demo/b :fn 'seon.render.entities/render-var-sig  :tokens 100 :rules [:rule.ctx/edited-var-callers]}
          b3 {:entity 103 :sym 'demo/c :fn 'seon.render.entities/render-var-sig  :tokens 200 :rules [:rule.ctx/edited-var-callers]}
          b4 {:entity 104 :sym 'demo/d :fn 'seon.render.entities/render-var-chip :tokens 50  :rules [:rule.scope/neighbor]}
          b5 {:entity 105 :sym 'demo/e :fn 'seon.render.entities/render-var-chip :tokens 50  :rules [:rule.scope/neighbor]}
          later {:touched-eids #{101 102 104}
                 :touched-syms #{'demo/a 'demo/b 'demo/d}
                 :manual-lookup-eids #{104}
                 :manual-lookup-syms #{'demo/d}}
          res (sig/score-shown-blocks [b1 b2 b3] [b1 b2 b3 b4 b5] later)
          empty-res (sb/score-shown-blocks [] [] nil)]
      (is (= :seon.signal/scoreboard (:seon/schema (meta res))))
      (is (= 0.6667 (:precision res)))
      (is (= 0.6667 (:token-precision res)))
      (is (= 0.6667 (:recall res)))
      (is (= 1 (:recall-gap-count res)))
      (is (= 104 (:entity (first (:recall-gap-items res)))))
      (is (= 'demo/d (:sym (first (:recall-gap-items res)))))
      (is (= :dropped-from-shown (:reason (first (:recall-gap-items res)))))
      ;; Per-rule & per-fn rollups
      (is (= 1.0 (get-in res [:by-rule :rule.ctx/edited-var-on-branch :precision])))
      (is (= 0.5 (get-in res [:by-rule :rule.ctx/edited-var-callers :precision])))
      (is (= 0.3333 (get-in res [:by-rule :rule.ctx/edited-var-callers :token-precision])))
      (is (= 1.0 (get-in res [:by-fn 'seon.render.entities/render-var-full :precision])))
      (is (= 0.5 (get-in res [:by-fn 'seon.render.entities/render-var-sig :precision])))
      ;; Empty inputs produce finite 0.0 metrics
      (is (= 0.0 (:precision empty-res)))
      (is (= 0.0 (:token-precision empty-res)))
      (is (= 0.0 (:recall empty-res)))
      (is (= 0 (:recall-gap-count empty-res))))))

(deftest ^{:cost/ticks 90000000} as-of-hyperparam-sweep-and-live-scoreboard-e2e-test
  (testing "d/as-of A/B hyperparameter lab compares breadth-first, depth-first, recency-heavy, and urgency-heavy deterministically"
    (with-test-store [{:keys [conn]}]
      (let [src (str "(ns demo.lab)\n"
                     "(defn urgent-var \"Urgent bug target\" {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
                     "(defn recent-a \"Recently inspected fn A\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"
                     "(defn recent-b \"Recently inspected fn B\" {:malli/schema [:=> [:cat :int] :int]} [x] (dec x))\n"
                     "(defn stale-c \"Untouched fn C\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))\n"
                     "(defn stale-d \"Untouched fn D\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 3 x))\n"
                     "(defn missed-e \"Missed manual lookup fn E\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 5))\n")]
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/lab.clj" src false)})
        (d/transact conn {:tx-data
                          [{:seon.task/id :task/lab-main :seon.task/title "Optimize demo.lab" :seon.task/scope 'demo.lab :seon.task/done? false}
                           (serr/error-tx {:kind :demo/stale-err :severity :error :source :eval :agent :ag/lab :message "Old warning"})
                           {:seon.agent/id :ag/lab :seon.agent/branch :main :seon.agent/role :worker
                            :seon.agent/scope 'demo.lab :seon.agent/task :task/lab-main
                            :seon.agent/pinned [[:seon.code.var/name 'demo.lab/stale-c]
                                                [:seon.code.var/name 'demo.lab/stale-d]]}]})
        ;; Turns 1-2 BEFORE mid-tx: agent works heavily on recent-a and recent-b
        (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/lab
                                     :seon.agent.eval/turn 1 :seon.agent.eval/handle 'result/e1
                                     :seon.agent.eval/code "(demo.lab/recent-a 1)"}]})
        (let [mid-tx (:max-tx (:db-after
                               (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/lab
                                                            :seon.agent.eval/turn 2 :seon.agent.eval/handle 'result/e2
                                                            :seon.agent.eval/code "(do (demo.lab/recent-a 2) (demo.lab/recent-b 3))"}]})))]
          ;; Turns 3-5 STRICTLY AFTER mid-tx: agent continues on recent-a/recent-b and manually looks up recent-b & missed-e
          (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/lab
                                       :seon.agent.eval/turn 3 :seon.agent.eval/handle 'result/e3
                                       :seon.agent.eval/code "(demo.lab/recent-a 10)"}]})
          (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/lab
                                       :seon.agent.eval/turn 4 :seon.agent.eval/handle 'result/e4
                                       :seon.agent.eval/code "(user/source 'demo.lab/recent-b)"}]})
          (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/lab
                                       :seon.agent.eval/turn 5 :seon.agent.eval/handle 'result/e5
                                       :seon.agent.eval/code "(user/grep-src \"missed-e\")"}]})
          (let [db @conn
                profiles [{:label :default       :weights {:urgency 0.50 :recency 0.30 :touches 0.20} :strategy :breadth-first :token-budget 280 :max-items 3}
                          {:label :recency-heavy :weights {:urgency 0.05 :recency 0.85 :touches 0.10} :strategy :breadth-first :token-budget 280 :max-items 3}
                          {:label :urgency-heavy :weights {:urgency 0.90 :recency 0.05 :touches 0.05} :strategy :breadth-first :token-budget 280 :max-items 3}
                          {:label :depth-first   :weights {:urgency 0.05 :recency 0.85 :touches 0.10} :strategy :depth-first   :token-budget 280 :max-items 3}]
                r1 (sb/compare-as-of-hyperparams db :ag/lab mid-tx profiles)
                r2 (sig/compare-as-of-hyperparams db :ag/lab mid-tx profiles)
                by-lbl (into {} (map (juxt :label identity)) (:profiles r1))
                rec-p (get by-lbl :recency-heavy)
                urg-p (get by-lbl :urgency-heavy)
                live-sb (sig/context-scoreboard db db :ag/lab {:tx mid-tx :token-budget 280 :max-items 3})]
            ;; Reproducible across runs
            (is (= (:winner r1) (:winner r2)))
            (is (= (mapv #(select-keys % [:label :precision :token-precision :recall :recall-gap-count :shown-tokens]) (:profiles r1))
                   (mapv #(select-keys % [:label :precision :token-precision :recall :recall-gap-count :shown-tokens]) (:profiles r2))))
            (is (= mid-tx (:tx r1)))
            (is (= :ag/lab (:agent r1)))
            (is (= 3 (:later-eval-count r1)))
            (is (>= (:manual-lookup-count r1) 2))
            (is (pos? (:actual-touched-count r1)))
            (is (contains? #{:default :recency-heavy :urgency-heavy :depth-first} (:winner r1)))
            ;; Recency-heavy surfaces recent-a & recent-b (strictly higher recall & precision than urgency-heavy!)
            (is (> (:recall rec-p) (:recall urg-p)) (str "Expected recency-heavy recall > urgency-heavy: " {:rec rec-p :urg urg-p}))
            (is (<= (:recall-gap-count rec-p) (:recall-gap-count urg-p)))
            ;; Live context-scoreboard conforms to :seon.signal/scoreboard schema
            (is (true? (:ok? (schema/validate-against db :seon.signal/scoreboard live-sb))))
            ;; REPL helper installation
            (let [tmp-ns (create-ns 'seon.test.tmp-sb-repl)]
              (try
                (sb/install-user-helpers! tmp-ns (fn [] {:conn conn :branch :main :agent-id :ag/lab}))
                (let [v-sb (ns-resolve tmp-ns 'context-scoreboard)
                      v-cmp (ns-resolve tmp-ns 'compare-hyperparams)
                      h-sb (@v-sb :ag/lab {:tx mid-tx :token-budget 280})
                      h-cmp (@v-cmp :ag/lab mid-tx profiles)]
                  (is (string? (:doc (meta v-sb))))
                  (is (string? (:doc (meta v-cmp))))
                  (is (= :ag/lab (:agent h-sb)))
                  (is (= (:winner r1) (:winner h-cmp))))
                (finally
                  (remove-ns 'seon.test.tmp-sb-repl))))))))))

(deftest ^{:cost/ticks 130000000} minimal-native-datalog-rules-and-generative-seed42-test
  (testing "Active canonical starter rules are minimal (<= 32), 0 :seon.rule/when, 100% native :seon.rule/query"
    (let [rs (rseed/starter-rules)
          rids (into #{} (map :seon.rule/id) rs)]
      (is (<= 20 (count rs) 32) (str "Expected 20..32 minimal rules, got " (count rs)))
      (is (= 0 (count (filter :seon.rule/when rs))) "Zero legacy :seon.rule/when rules allowed")
      (is (= (count rs) (count (filter :seon.rule/query rs))) "100% of rules must use native :seon.rule/query")
      (doseq [req-id [:rule.ctx/fault-with-recommended-fix :rule.ctx/active-task-and-lineage
                      :rule.ctx/scope-and-extra-namespaces :rule.ctx/scope-task-history-and-fixes
                      :rule.ctx/pinned-entities :rule.ctx/recent-eval-results
                      :rule.ctx/edited-var-on-branch :rule.ctx/failing-reach-tests
                      :rule.ctx/eval-touched-schemas :rule.ctx/eval-compatible-fns
                      :rule.ctx/edited-var-callers :rule.ctx/citing-docs
                      :rule.gate/task-needs-open :rule.gate/test-failing
                      :rule.gate/test-missing :rule.gate/agent-blocked
                      :rule.error/rate-limit-backpressure
                      :rule.preview/nested-sub-schema :rule.preview/errors-by-kind
                      :rule.preview/large-collection :rule.preview/multi-facet-entity]]
        (is (contains? rids req-id) (str "Missing required core rule " req-id)))))

  (testing "100-sample seed=42 Malli generative checks on scoreboard & touches pure functions + compare-as-of-hyperparams"
    (with-test-store [{:keys [conn]}]
      (let [db @conn]
        (doseq [sym '[seon.signals.touches/nonneg-long
                      seon.signals.touches/extract-eval-touches
                      seon.signals.scoreboard/extract-eval-touches
                      seon.signals.scoreboard/score-shown-blocks
                      seon.signals.scoreboard/compare-profiles
                      seon.signals/score-shown-blocks]]
          (let [res (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (true? (:ok? res)) (str sym " failed seed=42 generative check: " (pr-str res)))
            (is (= 100 (:ran res)) (str sym " expected 100 samples: " (pr-str res)))))
        ;; Deterministic seed=42 generative sweep over compare-as-of-hyperparams with real Datahike DB
        (d/transact conn {:tx-data [{:seon.task/id :task/gen-lab :seon.task/title "Gen Lab" :seon.task/done? false}
                                    {:seon.agent/id :ag/gen-lab :seon.agent/branch :main :seon.agent/role :worker
                                     :seon.agent/task :task/gen-lab}]})
        (let [tx0 (:max-tx @conn)
              _ (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/gen-lab
                                             :seon.agent.eval/turn 1 :seon.agent.eval/handle 'result/e1
                                             :seon.agent.eval/code "(d/pull db '[*] [:seon.task/id :task/gen-lab])"}]})
              db2 @conn
              prof-sch [:vector {:min 1 :max 4}
                        [:map
                         [:label :keyword]
                         [:strategy [:enum :breadth-first :depth-first]]
                         [:token-budget [:int {:min 0 :max 4000}]]
                         [:weights [:map [:urgency [:double {:min 0.0 :max 1.0 :gen/NaN? false :gen/infinite? false}]]
                                    [:recency [:double {:min 0.0 :max 1.0 :gen/NaN? false :gen/infinite? false}]]
                                    [:touches [:double {:min 0.0 :max 1.0 :gen/NaN? false :gen/infinite? false}]]]]]]
              batches (mg/generate [:vector {:min 10 :max 10} prof-sch] {:seed 42 :size 4})]
          (doseq [profs batches]
            (let [r (sb/compare-as-of-hyperparams db2 :ag/gen-lab tx0 profs)]
              (is (= (count profs) (count (:profiles r))))
              (is (contains? (set (map :label profs)) (:winner r)))
              (is (every? #(and (<= 0.0 (:precision %) 1.0)
                                (<= 0.0 (:token-precision %) 1.0)
                                (<= 0.0 (:recall %) 1.0))
                          (:profiles r))))))))))


(deftest ^{:cost/ticks 80000000} merged-branch-single-tx-turn-splitting-test
  (testing "compare-as-of-hyperparams auto-splits merged single-tx agents at median :turn and honors explicit :split-turn"
    (with-test-store [{:keys [conn]}]
      (let [src (str "(ns demo.merged)\n"
                     "(defn alpha \"Alpha fn\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"
                     "(defn beta \"Beta fn\" {:malli/schema [:=> [:cat :int] :int]} [x] (dec x))\n"
                     "(defn gamma \"Gamma fn\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))\n"
                     "(defn delta \"Delta fn\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 9))\n")
            ;; Commit agent + code + ALL 6 turns in ONE single merge tx (simulating merged branch onto :main!)
            tx-res (d/transact
                    conn
                    {:tx-data (concat
                               (code/file-import-tx @conn "src/demo/merged.clj" src false)
                               [{:seon.task/id :task/merged-demo :seon.task/title "Merged demo" :seon.task/scope 'demo.merged :seon.task/done? true}
                                {:seon.agent/id :ns/demo.merged :seon.agent/branch :main :seon.agent/role :worker
                                 :seon.agent/scope 'demo.merged :seon.agent/task :task/merged-demo
                                 :seon.agent/pinned [[:seon.code.var/name 'demo.merged/alpha]]}
                                {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ns/demo.merged :seon.agent.eval/turn 1
                                 :seon.agent.eval/handle 'result/e1 :seon.agent.eval/code "(demo.merged/alpha 1)"}
                                {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ns/demo.merged :seon.agent.eval/turn 2
                                 :seon.agent.eval/handle 'result/e2 :seon.agent.eval/code "(demo.merged/beta 2)"}
                                {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ns/demo.merged :seon.agent.eval/turn 3
                                 :seon.agent.eval/handle 'result/e3 :seon.agent.eval/code "(demo.merged/alpha 3)"}
                                {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ns/demo.merged :seon.agent.eval/turn 4
                                 :seon.agent.eval/handle 'result/e4 :seon.agent.eval/code "(demo.merged/alpha 4)"}
                                {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ns/demo.merged :seon.agent.eval/turn 5
                                 :seon.agent.eval/handle 'result/e5 :seon.agent.eval/code "(user/source 'demo.merged/gamma)"}
                                {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ns/demo.merged :seon.agent.eval/turn 6
                                 :seon.agent.eval/handle 'result/e6 :seon.agent.eval/code "(user/grep-src \"delta\")"}])})
            merge-tx (:max-tx (:db-after tx-res))
            db @conn
            auto-r (sb/compare-as-of-hyperparams db :ns/demo.merged merge-tx)
            exp-r  (sb/compare-as-of-hyperparams db :ns/demo.merged merge-tx {:split-turn 2})]
        ;; Auto-detected median turn on [1 2 3 4 5 6] -> turn 3, leaving turns 4, 5, 6 as later evals!
        (is (= :turn (:split-mode auto-r)))
        (is (= 3 (:split-turn auto-r)))
        (is (= 3 (:later-eval-count auto-r)))
        (is (>= (:manual-lookup-count auto-r) 2))
        (is (pos? (:actual-touched-count auto-r)))
        (is (some #(pos? (:precision %)) (:profiles auto-r)) "Merged agent must report real non-zero Precision")
        (is (some #(pos? (:recall %)) (:profiles auto-r)) "Merged agent must report real non-zero Recall")
        ;; Explicit :split-turn 2 leaves turns 3, 4, 5, 6 as later evals!
        (is (= :turn (:split-mode exp-r)))
        (is (= 2 (:split-turn exp-r)))
        (is (= 4 (:later-eval-count exp-r)))))))
