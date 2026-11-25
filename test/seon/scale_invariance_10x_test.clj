(ns seon.scale-invariance-10x-test
  "Monotonic 1x -> 10x scale-invariance & allocation regression suite for Path C:
   1. `seon.ctx.diff` O(1) incremental `fast-merge-base`, `branch-changes`, `code-db-sig`, `rules-db-sig`.
   2. Single-store monotonic 1x (10 agents, 50 evals, 5 merges, 200 verdicts) -> 10x (100 agents, 500 evals,
      50 merges, 2,000 verdicts) verifying hard ceilings AND 10x/1x ratio < 2.0x for:
      (a) `seon.render.wire/build-live-wire-request`
      (b) `seon.health/report` + incremental `seon.health.efficiency/test-snapshot`
      (c) `seon.health.coverage/coverage-report`
      (d) `seon.render.surface/render-surface` (`:context` & `:system`)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.ctx.diff :as diff]
            [seon.health :as health]
            [seon.health.coverage :as cov]
            [seon.health.efficiency :as heff]
            [seon.render.surface :as surface]
            [seon.render.wire :as wire]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]))

(def ^:private demo-src
  "(ns seon.scale.demo
  \"Demo namespace for 10x scale-invariance regression test.\")

(defn compute-score
  \"Computes a bounded score.\"
  {:malli/schema [:=> [:cat :int :int] :int] :cost/ticks 50000}
  [a b]
  (+ (long a) (long b)))

(defn render-demo-chip
  \"Pure renderer.\"
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]]
                      [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 100000}
  [{t :seon.task/entity}]
  {:seon.ui/hiccup [:span {:class \"ent-chip\" :data-min-width 140} (str (:seon.task/id t))]})")

(defn- add-scale-slice! [conn from-ag to-ag from-ev to-ev from-m to-m from-v to-v]
  (let [ags (mapv (fn [i]
                    {:seon.agent/id (keyword "scale" (str "ag-" i))
                     :seon.agent/branch :main
                     :seon.agent/scope 'seon.scale.demo
                     :seon.agent/goal (str "Background agent #" i)})
                  (range from-ag to-ag))
        evs (mapv (fn [i]
                    (let [ai (+ from-ag (mod i (max 1 (- to-ag from-ag))))]
                      {:seon.agent.eval/id (java.util.UUID/randomUUID)
                       :seon.agent.eval/agent (keyword "scale" (str "ag-" ai))
                       :seon.agent.eval/turn (inc (mod i 5))
                       :seon.agent.eval/handle (symbol "result" (str "se" i))
                       :seon.agent.eval/code "(+ 1 2)"
                       :seon.agent.eval/value "3"
                       :seon.agent.eval/ms 1.2
                       :seon.agent.eval/ticks 1000
                       :seon.agent.eval/bytes 65536}))
                  (range from-ev to-ev))
        vds (mapv (fn [i]
                    (let [vname (symbol "seon.scale-demo-test" (str "case-" (mod i 10) "-test"))]
                      {:seon.test/digest (str "scale-dg-" i)
                       :seon.test/var vname
                       :seon.test/pass? true
                       :seon.test/ms 4.5
                       :seon.test/ticks 50000
                       :seon.test/bytes 131072}))
                  (range from-v to-v))
        merges (mapv (fn [m]
                       {:seon.summary/id (java.util.UUID/randomUUID)
                        :seon.summary/kind :milestone
                        :seon.summary/from-tx (long (+ 100 m))
                        :seon.summary/to-tx (long (+ 101 m))
                        :seon.summary/title (str "Merge #" m)})
                     (range from-m to-m))]
    (d/transact conn {:tx-data (vec (concat ags evs vds merges))})))

(defn- ratio-with-floor [v10 v1 floor-v]
  (/ (double (max floor-v v10)) (double (max floor-v v1))))

(defn- measure-probe [conn mdb bdb aid]
  (let [m-wire (code/measure-call #(wire/build-live-wire-request mdb bdb aid {:conn conn}))
        m-hlth (code/measure-call #(health/report mdb))
        m-cov  (code/measure-call #(cov/coverage-report mdb))
        m-ctx  (code/measure-call #(surface/render-surface mdb bdb {:conn conn :view-mode :context :viewing-id aid}))
        m-sys  (code/measure-call #(surface/render-surface mdb mdb {:conn conn :view-mode :system :viewing-id aid}))]
    {:wire {:ms (:ms m-wire) :ticks (:ticks m-wire) :mb (/ (double (:bytes m-wire)) 1048576.0) :ok? (map? (:val m-wire))}
     :health {:ms (:ms m-hlth) :ticks (:ticks m-hlth) :mb (/ (double (:bytes m-hlth)) 1048576.0) :ok? (contains? (:val m-hlth) :ok?)}
     :cov {:ms (:ms m-cov) :ticks (:ticks m-cov) :mb (/ (double (:bytes m-cov)) 1048576.0) :ok? (= :seon.health/coverage (:seon/schema (meta (:val m-cov))))}
     :context {:ms (:ms m-ctx) :ticks (:ticks m-ctx) :mb (/ (double (:bytes m-ctx)) 1048576.0) :ok? (or (map? (:val m-ctx)) (vector? (:val m-ctx)))}
     :system {:ms (:ms m-sys) :ticks (:ticks m-sys) :mb (/ (double (:bytes m-sys)) 1048576.0) :ok? (or (map? (:val m-sys)) (vector? (:val m-sys)))}}))

(deftest diff-incremental-primitives-and-tail-rules-test
  (testing "seon.ctx.diff O(1) merge-base, incremental branch-changes, and code/rules sig invariance"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/scale/demo.clj" demo-src false)})
      (let [mdb0 @conn
            csig0 (diff/code-db-sig mdb0)
            rsig0 (diff/rules-db-sig mdb0)]
        (d/transact conn {:tx-data [{:seon.agent/id :scale/sig-check :seon.agent/branch :main :seon.agent/goal "check"}]})
        (let [mdb1 @conn]
          (is (= csig0 (diff/code-db-sig mdb1)) "Non-code write keeps code-db-sig identical")
          (is (= rsig0 (diff/rules-db-sig mdb1)) "Non-rule write keeps rules-db-sig identical"))
        (store/branch! conn :main :branch/diff-test)
        (let [bconn (store/connect cfg :branch/diff-test)]
          (try
            (let [base0 (diff/fast-merge-base @conn @bconn)]
              (is (some? base0))
              (d/transact bconn {:tx-data [{:seon.agent/id :scale/b1 :seon.agent/branch :branch/diff-test :seon.agent/goal "b1"}]})
              (d/transact conn {:tx-data [{:seon.agent/id :scale/m1 :seon.agent/branch :main :seon.agent/goal "m1"}]})
              (is (= base0 (diff/fast-merge-base @conn @bconn)) "Single-parent advance on main & branch preserves merge-base")
              (let [ch1 (diff/branch-changes @conn @bconn)]
                (is (some #(= :scale/b1 (:v %)) ch1))
                (d/transact bconn {:tx-data [{:seon.agent/id :scale/b2 :seon.agent/branch :branch/diff-test :seon.agent/goal "b2"}]})
                (let [ch2 (diff/branch-changes @conn @bconn)]
                  (is (some #(= :scale/b1 (:v %)) ch2))
                  (is (some #(= :scale/b2 (:v %)) ch2)))))
            (finally (d/release bconn)))))))
  (testing "project-entity handles both :target :wire and :target :html uniformly"
  (with-test-store [{:keys [conn]}]
    (d/transact conn {:tx-data [{:seon.agent/id :scale/proj :seon.agent/branch :main}]})
    (let [db @conn
          w (ctx/project-entity db db [:seon.agent/id :scale/proj] {:target :wire :semantic? false})
          h (ctx/project-entity db db [:seon.agent/id :scale/proj] {:target :html :semantic? false})]
      (is (= :agent (:focus-kind w)))
      (is (= :agent (:focus-kind h)))
      (is (map? (:fitted w)))
      (is (map? (:fitted h)))))))

(deftest scale-invariance-1x-to-10x-test
  (testing "Monotonic 1x -> 10x store growth keeps wire, health, coverage, :context, :system, and incremental test-snapshot < 2.0x and under hard ceilings"
    (with-test-store [{:keys [conn cfg]}]
      (let [starter (or rules/*base-rules-override* (rseed/starter-rules))
            focal :scale/focal]
        (d/transact conn {:tx-data starter})
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/scale/demo.clj" demo-src false)})
        (d/transact conn {:tx-data [{:seon.task/id :seon.scale/t1 :seon.task/title "Scale task" :seon.task/scope 'seon.scale.demo :seon.task/done? false}
                                    {:seon.agent/id focal :seon.agent/branch :branch/scale-focal :seon.agent/scope 'seon.scale.demo
                                     :seon.agent/goal "Verify scale invariance" :seon.agent/tasks [:seon.task/id :seon.scale/t1]}]})
        (store/branch! conn :main :branch/scale-focal)
        (let [bconn (store/connect cfg :branch/scale-focal)]
          (try
            ;; 1x: 10 agents, 50 evals, 5 merges, 200 verdicts; settle, then measure 1 real incremental step (branch eval + main commit)
            (add-scale-slice! conn 0 10 0 50 0 5 0 200)
            (measure-probe conn @conn @bconn focal)
            (d/transact bconn {:tx-data [{:seon.agent.eval/id (java.util.UUID/randomUUID)
                                          :seon.agent.eval/agent focal :seon.agent.eval/turn 1 :seon.agent.eval/handle 'result/e1
                                          :seon.agent.eval/code "(seon.scale.demo/compute-score 1 2)" :seon.agent.eval/value "3"}]})
            (d/transact conn {:tx-data [{:seon.summary/id (java.util.UUID/randomUUID) :seon.summary/kind :milestone
                                         :seon.summary/from-tx 201 :seon.summary/to-tx 202 :seon.summary/title "Step 1x"}]})
            (let [tp0 (System/nanoTime)
                  m1 (measure-probe conn @conn @bconn focal)
                  _ (d/transact conn {:tx-data [{:seon.test/digest "scale-dg-step-1" :seon.test/var 'seon.scale-demo-test/case-0-test
                                                 :seon.test/pass? true :seon.test/ms 4.5 :seon.test/ticks 50000 :seon.test/bytes 131072}]})
                  ts1 (code/measure-call #(let [s (heff/test-snapshot @conn)] {:snap s :eff (heff/test-efficiency-warnings @conn s)}))
                  p1-ms (/ (double (- (System/nanoTime) tp0)) 1e6)]
              ;; 10x: 100 agents, 500 evals, 50 merges, 2,000 verdicts; settle bulk setup, then measure identical incremental step
              (add-scale-slice! conn 10 100 50 500 5 50 200 2000)
              (measure-probe conn @conn @bconn focal)
              (heff/test-snapshot @conn)
              (d/transact bconn {:tx-data [{:seon.agent.eval/id (java.util.UUID/randomUUID)
                                            :seon.agent.eval/agent focal :seon.agent.eval/turn 2 :seon.agent.eval/handle 'result/e2
                                            :seon.agent.eval/code "(seon.scale.demo/compute-score 3 4)" :seon.agent.eval/value "7"}]})
              (d/transact conn {:tx-data [{:seon.summary/id (java.util.UUID/randomUUID) :seon.summary/kind :milestone
                                           :seon.summary/from-tx 301 :seon.summary/to-tx 302 :seon.summary/title "Step 10x"}]})
              (let [tp1 (System/nanoTime)
                    m10 (measure-probe conn @conn @bconn focal)
                    _ (d/transact conn {:tx-data [{:seon.test/digest "scale-dg-step-10" :seon.test/var 'seon.scale-demo-test/case-0-test
                                                   :seon.test/pass? true :seon.test/ms 4.5 :seon.test/ticks 50000 :seon.test/bytes 131072}]})
                    ts10 (code/measure-call #(let [s (heff/test-snapshot @conn)] {:snap s :eff (heff/test-efficiency-warnings @conn s)}))
                    p10-ms (/ (double (- (System/nanoTime) tp1)) 1e6)
                    probes-ms (+ p1-ms p10-ms)]
                (doseq [[k max-ms max-mb] [[:wire 120.0 40.0]
                                           [:health 80.0 30.0]
                                           [:cov 20.0 6.0]
                                           [:context 120.0 40.0]
                                           [:system 60.0 24.0]]]
                  (let [r1 (get m1 k) r10 (get m10 k)
                      ticks-ratio (ratio-with-floor (:ticks r10) (:ticks r1) 2000000)
                      mb-ratio (ratio-with-floor (:mb r10) (:mb r1) 3.0)]
                  (is (:ok? r1) (str k " 1x failed: " (pr-str r1)))
                  (is (:ok? r10) (str k " 10x failed: " (pr-str r10)))
                  (is (<= (:ms r10) max-ms) (str k " 10x ms=" (:ms r10) " exceeded ceiling " max-ms " (1x=" (:ms r1) ")"))
                  (is (<= (:mb r10) max-mb) (str k " 10x mb=" (:mb r10) " exceeded ceiling " max-mb " (1x=" (:mb r1) ")"))
                  (is (< ticks-ratio 2.0) (str k " 10x/1x ticks ratio " ticks-ratio " >= 2.0 (1x=" (:ticks r1) ", 10x=" (:ticks r10) ")"))
                  (is (< mb-ratio 2.0) (str k " 10x/1x mb ratio " mb-ratio " >= 2.0 (1x=" (:mb r1) "MB, 10x=" (:mb r10) "MB)"))))
                (let [ts1-mb (/ (double (:bytes ts1)) 1048576.0)
                      ts10-mb (/ (double (:bytes ts10)) 1048576.0)]
                  (is (< (ratio-with-floor (:ticks ts10) (:ticks ts1) 1000000) 2.0)
                  (str "Incremental test-snapshot ticks scaled >= 2.0x: 1x=" (:ticks ts1) ", 10x=" (:ticks ts10)))
                  (is (< (ratio-with-floor ts10-mb ts1-mb 2.0) 2.0)
                      (str "Incremental test-snapshot MB scaled >= 2.0x: 1x=" ts1-mb "MB, 10x=" ts10-mb "MB")))
                (is (< probes-ms 800.0) (str "Combined 1x+10x probe window took " probes-ms " ms (must be < 800 ms)"))))
            (finally (d/release bconn))))))))
