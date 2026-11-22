(ns seon.fit-and-signals-test
  "Unit, integration, and seed=42 Malli generative proofs for `seon.render.fit`,
   `seon.signals`, and `seon.ctx.project/budget-and-order-projections`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.graph :as graph]
            [seon.ctx.project :as proj]
            [seon.error :as serr]
            [seon.render.fit :as fit]
            [seon.schema :as schema]
            [seon.signals :as sig]
            [seon.test-fixture :refer [with-test-store]]))

(deftest ^{:cost/ticks 20000000} three-signal-rank-and-weights-test
  (testing "Open error beats idle; higher native ?tx snaps older entity to top; convergence boosts; weights flip order"
    (let [cands [{:entity :idle :priority 50 :tx 100 :touches 0 :converging-paths 1}
                 {:entity :err  :data {:seon.error/status :open} :tx 100 :touches 0 :converging-paths 1}
                 {:entity :pin  :pinned? true :tx 100 :touches 0 :converging-paths 1}]
          r1 (fit/rank-candidates cands)]
      (is (= :err (:entity (first r1))))
      (is (= :pin (:entity (second r1))))
      (is (= :idle (:entity (nth r1 2))))
      (is (= 1.0 (get-in (first r1) [:rank/signals :urgency]))))

    (let [before [{:entity :older :priority 50 :tx 10 :touches 1}
                  {:entity :newer :priority 50 :tx 50 :touches 1}]
          after  [{:entity :older :priority 50 :tx 90 :touches 1}
                  {:entity :newer :priority 50 :tx 50 :touches 1}]
          rb (fit/rank-candidates before)
          ra (fit/rank-candidates after)]
      (is (= :newer (:entity (first rb))))
      (is (= :older (:entity (first ra))) "Re-touching older entity with higher native ?tx snaps it to top")
      (is (= 1.0 (get-in (first ra) [:rank/signals :recency]))))

    (let [pool [{:entity :recent-only :priority 50 :tx 200 :touches 0 :converging-paths 0}
                {:entity :converged   :priority 50 :tx 100 :touches 6 :converging-paths 4}]
          by-rec (fit/rank-candidates pool {:weights {:urgency 0.1 :recency 10.0 :convergence 0.1}})
          by-cv  (fit/rank-candidates pool {:weights {:urgency 0.1 :recency 0.1 :convergence 10.0}})]
      (is (= :recent-only (:entity (first by-rec))))
      (is (= :converged (:entity (first by-cv)))))))

(deftest ^{:cost/ticks 25000000} wire-upgrade-ladder-strategies-and-shown-dedup-test
  (testing "2-pass Upgrade Ladder for :target :wire differentiates :breadth-first vs :depth-first and handles already-shown 0-token dedup + ?tx re-warm"
    (let [mk-cands (fn [sym]
                     [{:fn (symbol "demo" (str sym "-chip")) :hiccup [:span sym] :tokens 10 :min-width 120 :area 2400}
                      {:fn (symbol "demo" (str sym "-card")) :hiccup [:div sym] :tokens 40 :min-width 320 :area 12800}
                      {:fn (symbol "demo" (str sym "-full")) :hiccup [:section sym] :tokens 100 :min-width 560 :area 44800}])
          items [{:entity 1 :zone :ephemeral-tail :rank/score 0.95 :tx 20 :hash 111 :candidates (mk-cands "a")}
                 {:entity 2 :zone :static         :rank/score 0.80 :tx 10 :hash 222 :candidates (mk-cands "b")}]
          bf (fit/upgrade-ladder items {:target :wire :strategy :breadth-first :token-budget 110})
          df (fit/upgrade-ladder items {:target :wire :strategy :depth-first   :token-budget 110})
          bf-by (into {} (map (juxt :entity identity)) (:selected bf))
          df-by (into {} (map (juxt :entity identity)) (:selected df))]
      ;; Least-volatile -> most-volatile final ordering (:static before :ephemeral-tail)
      (is (= [2 1] (mapv :entity (:selected bf))))
      ;; Breadth-first upgrades both to 40 (total 80); depth-first upgrades rank #1 to 100 and leaves #2 at 10 (total 110)
      (is (= 'demo/a-card (:chosen-fn (get bf-by 1))))
      (is (= 'demo/b-card (:chosen-fn (get bf-by 2))))
      (is (= 80 (:tokens bf)))
      (is (= 'demo/a-full (:chosen-fn (get df-by 1))))
      (is (= 'demo/b-chip (:chosen-fn (get df-by 2))))
      (is (= 110 (:tokens df)))

      ;; Turn 2: unchanged entities with tight budget=10 cost 0 tokens via already-shown
      (let [t2 (fit/upgrade-ladder items {:target :wire :token-budget 10 :already-shown (:shown bf)})
            t2-by (into {} (map (juxt :entity identity)) (:selected t2))]
        (is (= 0 (:tokens t2)))
        (is (= :unchanged (:status (get t2-by 1))))
        (is (= :unchanged (:status (get t2-by 2)))))

      ;; Turn 3: entity 1 upgrades cleanly when budget expands; entity 2 re-warms when native ?tx bumps
      (let [items3 [(assoc (first items) :tx 20)
                    (assoc (second items) :tx 35)]
            t3 (fit/upgrade-ladder items3 {:target :wire :strategy :depth-first :token-budget 120 :already-shown (:shown bf)})
            t3-by (into {} (map (juxt :entity identity)) (:selected t3))]
        (is (= :upgraded (:status (get t3-by 1))))
        (is (= 'demo/a-full (:chosen-fn (get t3-by 1))))
        (is (= :rewarmed (:status (get t3-by 2))))
        (is (= 110 (:tokens t3)))))))

(deftest ^{:cost/ticks 25000000} html-upgrade-ladder-viewport-and-slot-adaptation-test
  (testing "measure-hiccup & resolve-slot-budget drive responsive HTML candidate choice with zero @media cutoffs"
    (let [pill-m (fit/measure-hiccup [:span {:class "badge"} "ok"])
          tbl-m  (fit/measure-hiccup [:table [:tr [:th "A"] [:th "B"] [:th "C"] [:th "D"]] [:tr [:td "1"] [:td "2"] [:td "3"] [:td "4"]]])
          hint-m (fit/measure-hiccup [:div {:data-min-width 420} "hello"])]
      (is (< (:min-width pill-m) (:min-width tbl-m)))
      (is (= 420 (:min-width hint-m)))
      (is (pos? (:tokens hint-m))))

    (let [cands [{:fn 'seon.signals/render-signals-bar   :hiccup [:div {:data-min-width 220} "compact"] :min-width 220 :area 8800 :tokens 12}
                 {:fn 'seon.signals/render-signals-panel :hiccup [:section {:data-min-width 560} "rich panel"] :min-width 560 :area 67200 :tokens 48}]
          items [{:entity :sig :rank/score 1.0 :candidates cands}]
          desk-main (fit/upgrade-ladder items {:target :html :viewport-w 1440 :viewport-h 900 :sidebar-open? true :slot :main})
          desk-side (fit/upgrade-ladder items {:target :html :viewport-w 1440 :viewport-h 900 :sidebar-open? true :slot :sidebar})
          mob-main  (fit/upgrade-ladder items {:target :html :viewport-w 430  :viewport-h 900 :sidebar-open? false :slot :main})
          tiny-slot (fit/upgrade-ladder items {:target :html :avail-w 150 :area-budget 50000})]
      (is (= 'seon.signals/render-signals-panel (get-in desk-main [:selected 0 :chosen-fn])))
      (is (= 'seon.signals/render-signals-bar   (get-in desk-side [:selected 0 :chosen-fn])))
      (is (= 'seon.signals/render-signals-bar   (get-in mob-main  [:selected 0 :chosen-fn])))
      (is (= 'seon.signals/render-signals-bar   (get-in tiny-slot [:selected 0 :chosen-fn]))
          "Falls back to smallest candidate when none fit avail-w"))))

(deftest ^{:cost/ticks 70000000} discover-renderers-and-ambient-signals-integration-test
  (testing "discover-renderers finds seon.signals/render-signals-{bar,panel} via :avet :input/:output, excludes impure :ops, fit-projections adapts live, and all 5 signals validate fast"
    (with-test-store [{:keys [conn]}]
      (let [sig-stub (str "(ns seon.signals)\n"
                          "(defn render-signals-bar {:malli/schema [:=> [:cat [:map [:seon.signal/snapshot :seon.signal/snapshot]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:div {:data-min-width 220} \"bar\"]})\n"
                          "(defn render-signals-panel {:malli/schema [:=> [:cat [:map [:seon.signal/snapshot :seon.signal/snapshot]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:section {:data-min-width 560} \"panel\"]})\n"
                          "(defn impure-signals-view {:malli/schema [:=> [:cat [:map [:seon.signal/snapshot :seon.signal/snapshot]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] (let [x {:op :mutate}] {:seon.ui/hiccup [:div (str x)]}))\n")
            _ (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/signals.clj" sig-stub false)})
            _ (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn {:analyze-compiler? true})})
            imp-eid (:e (first (d/datoms @conn :avet :seon.code.var/name 'seon.signals/impure-signals-view)))
            _ (when imp-eid (d/transact conn {:tx-data [[:db/add imp-eid :seon.code.var/ops :mutate]]}))
            _ (d/transact conn {:tx-data [{:seon.agent/id :ag/w1 :seon.agent/branch :main :seon.agent/role :worker :seon.agent/waiting? false}
                                          {:seon.agent/id :ag/w2 :seon.agent/branch :main :seon.agent/role :worker :seon.agent/paused? true}
                                          {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/w1 :seon.agent.eval/turn 1
                                           :seon.agent.eval/code "(+ 1 2)" :seon.agent.eval/ticks 2500000 :seon.agent.eval/bytes 10485760
                                           :seon.agent.eval/ms 12.0 :seon.agent.eval/prompt-tokens 10000 :seon.agent.eval/cache-hit-tokens 8800}
                                          {:seon.test.run/branch :main :seon.test.run/pass? true :seon.test.run/ran 10 :seon.test.run/cached 5}
                                          {:seon.config/key :seon.config/root :seon.config/rank-weights {:urgency 1.5 :recency 0.6 :convergence 0.4}}]})
            db @conn
            _ (sig/all-signals db)
            snap (sig/all-signals db)]
        ;; 1. All 5 signals + snapshot validate against registered Malli schemas and compute fast (< 5 ms)
        (is (:ok? (schema/validate-against db :seon.signal/flow (:flow snap))))
        (is (:ok? (schema/validate-against db :seon.signal/pressure (:pressure snap))))
        (is (:ok? (schema/validate-against db :seon.signal/context (:context snap))))
        (is (:ok? (schema/validate-against db :seon.signal/proof (:proof snap))))
        (is (:ok? (schema/validate-against db :seon.signal/health (:health snap))))
        (is (:ok? (schema/validate-against db :seon.signal/snapshot snap)))
        (is (< (:ms snap) 5.0) (str "Expected all-signals < 5 ms, got " (:ms snap)))
        (is (= 1 (get-in snap [:flow :running])))
        (is (= 1 (get-in snap [:flow :stalled])))
        (is (= :warm (get-in snap [:flow :level])))
        (is (= 88.0 (get-in snap [:context :prefix-reuse-pct])))
        (is (true? (get-in snap [:proof :merge-ready?])))
        (is (= {:urgency 1.5 :recency 0.6 :convergence 0.4} (fit/read-rank-weights db)))

        ;; 2. Adding an open error escalates health & flow levels to :hot
        (d/transact conn {:tx-data [(serr/error-tx {:kind :eval/boom :severity :error :source :eval :agent :ag/w1 :message "boom"})]})
        (let [snap2 (sig/all-signals @conn)]
          (is (= :hot (get-in snap2 [:health :level])))
          (is (false? (get-in snap2 [:health :ok?])))
          (is (= :hot (get-in snap2 [:flow :level])))
          (is (= 1 (get-in snap2 [:flow :blocked]))))

        ;; 3. discover-renderers finds BOTH pure signal renderers via :avet :input/:output and excludes impure :ops
        (is (= '[seon.signals/render-signals-bar seon.signals/render-signals-panel]
               (fit/discover-renderers db :seon.signal/snapshot)))

        ;; 4. fit-projections invokes discovered live renderers and adapts to desktop :main vs :sidebar vs mobile :main
        (let [projs [{:entity :live-sig :schema :seon.signal/snapshot :data snap}]
              f-desk (fit/fit-projections db projs {:target :html :viewport-w 1440 :viewport-h 900 :sidebar-open? true :slot :main})
              f-side (fit/fit-projections db projs {:target :html :viewport-w 1440 :viewport-h 900 :sidebar-open? true :slot :sidebar})
              f-mob  (fit/fit-projections db projs {:target :html :viewport-w 430  :viewport-h 900 :sidebar-open? false :slot :main})]
          (is (= 'seon.signals/render-signals-panel (get-in f-desk [:selected 0 :chosen-fn])))
          (is (= 'seon.signals/render-signals-bar   (get-in f-side [:selected 0 :chosen-fn])))
          (is (= 'seon.signals/render-signals-bar   (get-in f-mob  [:selected 0 :chosen-fn]))))

        ;; 5. Both seon.signals renderers produce strictly increasing size & width
        (let [bar-m (fit/measure-hiccup (:seon.ui/hiccup (sig/render-signals-bar {:seon.signal/snapshot snap})))
              pan-m (fit/measure-hiccup (:seon.ui/hiccup (sig/render-signals-panel {:seon.signal/snapshot snap})))]
          (is (= 220 (:min-width bar-m)))
          (is (= 560 (:min-width pan-m)))
          (is (< (:tokens bar-m) (:tokens pan-m))))))))

(deftest ^{:cost/ticks 40000000} ctx-project-fit-integration-and-seed42-generative-test
  (testing "budget-and-order-projections supports optional fit-opts while preserving 2-arg behavior"
    (let [p1 {:entity 1 :zone :workspace :priority 90 :tx 30 :status :new :delta-data {:a 1}
              :candidates [{:fn 'demo/p1-sm :tokens 10 :min-width 180 :area 4000}
                           {:fn 'demo/p1-lg :tokens 60 :min-width 520 :area 36000}]}
          p2 {:entity 2 :zone :static :priority 70 :tx 10 :status :unchanged :delta-data {}
              :candidates [{:fn 'demo/p2-sm :tokens 20 :min-width 180 :area 4000}]}
          r-wire (proj/budget-and-order-projections [p1 p2] 80 {:target :wire :strategy :depth-first})
          r-html (proj/budget-and-order-projections [p1 p2] 80 {:target :html :viewport-w 430 :viewport-h 900 :slot :main})
          r-2arg (proj/budget-and-order-projections [p1 p2] 80)]
      (is (= [2 1] (mapv :entity (:selected r-wire))))
      (is (= 'demo/p1-lg (:chosen-fn (second (:selected r-wire)))))
      (is (seq (:candidates (second (:selected r-wire)))))
      (is (= 'demo/p1-sm (:chosen-fn (first (:selected r-html)))))
      (is (= 60 (:tokens r-2arg)))))

  (testing "Pure functions in seon.render.fit, seon.signals, and seon.ctx.project pass 100-sample seed=42 generative checks"
    (doseq [sym '[seon.render.fit/rank-candidates
                  seon.render.fit/measure-hiccup
                  seon.render.fit/resolve-slot-budget
                  seon.render.fit/upgrade-ladder
                  seon.signals/render-signals-bar
                  seon.signals/render-signals-panel
                  seon.ctx.project/budget-and-order-projections]]
      (let [res (schema/stress-var nil sym {:samples 100 :seed 42})]
        (is (:ok? res) (str sym " failed seed=42 generative check: " (pr-str res)))))))
