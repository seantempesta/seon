(ns seon.explain-cache-test
  "Unit, integration, read-replay, and 100-sample `seed = 42` Malli generative proofs for:
   - `seon.render.fit` (commit/validator caches, 4-signal rank + boost/suppress + phase weights,
     `explain-entity-fit`, `explain-candidates`)
   - `seon.signals.cache` (`analyze-wire-cache`, `analyze-walk-cache`)
   - `seon.signals.touches` & `seon.signals.scoreboard` (reasoning mentions, rule toggles)
   - `seon.rule/verdict-latest-for-var?` & `seon.signals` Flow/Pressure calibration."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.code :as code]
            [seon.code.graph :as graph]
            [seon.error :as serr]
            [seon.render.fit :as fit]
            [seon.render.fit.measure]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.schema :as schema]
            [seon.signals :as sig]
            [seon.signals.cache :as scache]
            [seon.signals.scoreboard :as sb]
            [seon.signals.touches :as touches]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]))

(deftest ^{:cost/ticks 25000000} four-signal-rank-modifiers-and-phase-weights-test
  (testing "Optional 4th :semantic signal, :rank/boost / :rank/suppress modifiers, and phase-rank-weights"
    ;; 1. Without :semantic scores, 3-signal ranking ignores :semantic weight cleanly
    (let [c3 [{:entity :a :priority 50 :tx 100 :touches 0}
              {:entity :b :priority 50 :tx 200 :touches 0}]
          r3-def (fit/rank-candidates c3)
          r3-sem (fit/rank-candidates c3 {:weights {:urgency 1.0 :recency 0.75 :convergence 0.5 :semantic 10.0}})]
      (is (= (:rank/score (first r3-def)) (:rank/score (first r3-sem))))
      (is (not (contains? (:rank/signals (first r3-def)) :semantic))))

    ;; 2. With :semantic scores present, 4th signal activates and weight flips ranking
    (let [c4 [{:entity :sem-hit :priority 50 :tx 100 :touches 0 :semantic 0.98}
              {:entity :rec-hit :priority 50 :tx 500 :touches 0 :semantic 0.05}]
          by-sem (fit/rank-candidates c4 {:weights {:urgency 0.1 :recency 0.1 :convergence 0.1 :semantic 5.0}})
          by-rec (fit/rank-candidates c4 {:weights {:urgency 0.1 :recency 5.0 :convergence 0.1 :semantic 0.1}})]
      (is (= :sem-hit (:entity (first by-sem))))
      (is (= 0.98 (get-in (first by-sem) [:rank/signals :semantic])))
      (is (= :rec-hit (:entity (first by-rec)))))

    ;; 3. Rule-emitted :rank/boost (top-level & in :params) and :rank/suppress clamp cleanly
    (let [cm [{:entity :base :priority 50 :tx 100 :touches 1}
              {:entity :boosted :priority 50 :tx 100 :touches 1 :params {:rank/boost 0.35}}
              {:entity :suppressed :priority 50 :tx 100 :touches 1 :rank/suppress 0.5}]
          rm (fit/rank-candidates cm)]
      (is (= [:boosted :base :suppressed] (mapv :entity rm)))
      (is (= 0.35 (:rank/boost (first rm))))
      (is (= 0.5 (:rank/suppress (last rm))))
      (is (<= 0.0 (:rank/score (last rm)) (:rank/score (second rm)) (:rank/score (first rm)) 1.0)))

    ;; 4. phase-rank-weights shifts from semantic/urgency (turn 0) to recency/convergence (turn 3+)
    (let [w0 (fit/phase-rank-weights 0)
          w5 (fit/phase-rank-weights 5)
          pool [{:entity :sem-cold :priority 50 :tx 10 :touches 0 :semantic 0.95}
                {:entity :hot-recent :priority 50 :tx 99 :touches 5 :semantic 0.10}]
          r0 (fit/rank-candidates pool {:turn 0})
          r5 (fit/rank-candidates pool {:turn 5})]
      (is (> (:semantic w0) (:semantic w5)))
      (is (> (:recency w5) (:recency w0)))
      (is (= :sem-cold (:entity (first r0))))
      (is (= :hot-recent (:entity (first r5)))))))

(deftest ^{:cost/ticks 70000000} explain-entity-fit-candidates-and-read-replay-test
  (testing "explain-entity-fit & explain-candidates report every variation, text, fits-width?, and explicit reasons; caches replay datahike.reads"
    (with-test-store [{:keys [conn]}]
      (let [src (str "(ns demo.exvar)\n"
                     "(defn demo-fn \"Explained var\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n")
            _ (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/exvar.clj" src false)})
            _ (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn)})
            db @conn
            veid (:e (first (d/datoms db :avet :seon.code.var/name 'demo.exvar/demo-fn)))
            fb {:seon.code.var/entity ['seon.render.entities/render-var-chip
                                       'seon.render.entities/render-var-sig
                                       'seon.render.entities/render-var-full]}
            ;; 1. HTML mobile (430px -> avail-w 398px): chip superseded, sig chosen, full skipped for width
            ex-mob (fit/explain-entity-fit db [:seon.code.var/name 'demo.exvar/demo-fn]
                                           {:target :html :viewport-w 430 :viewport-h 900 :sidebar-open? false :slot :main
                                            :fallback-renderers fb})
            mob-by (into {} (map (juxt :fn identity)) (:variations ex-mob))
            ;; 2. Wire tight budget: Pass 1 base fit vs skipped richer variation + custom :text-fn
            ex-tight (fit/explain-entity-fit db {:entity veid :rules [:rule.ctx/edited-var-on-branch]
                                                 :rank/boost 0.15 :params {:show-source? true}}
                                             {:target :wire :token-budget 15 :fallback-renderers fb
                                              :text-fn (fn [_] "CUSTOM-TEXT")})
            tight-by (into {} (map (juxt :fn identity)) (:variations ex-tight))
            ;; 3. Wire already-shown (0 tok) & dropped item exceeding budget
            cands [{:entity 10 :priority 95 :tx 20 :hash 1 :rules [:rule.ctx/pinned-entities]
                    :candidates [{:fn 'demo/a-sm :hiccup [:span "sm"] :tokens 20 :min-width 120 :area 2400}
                                 {:fn 'demo/a-lg :hiccup [:section "lg detail text"] :tokens 80 :min-width 480 :area 24000}]}
                   {:entity 20 :priority 10 :tx 10 :hash 2 :rules [:rule.scope/neighbor]
                    :candidates [{:fn 'demo/b-sm :hiccup [:span "b"] :tokens 50 :min-width 120 :area 2400}]}]
            ex-multi (fit/explain-candidates db cands {:target :wire :token-budget 30
                                                       :already-shown {10 {:fn 'demo/a-sm :tokens 20 :hash 1 :tx 20}}})
            by-e (into {} (map (juxt :entity identity)) (:items ex-multi))]
        (is (= veid (:entity ex-mob)))
        (is (= 'seon.render.entities/render-var-sig (:chosen-fn ex-mob)))
        (is (= "Superseded by richer variation" (:reason (get mob-by 'seon.render.entities/render-var-chip))))
        (is (true? (:chosen? (get mob-by 'seon.render.entities/render-var-sig))))
        (is (str/starts-with? (:reason (get mob-by 'seon.render.entities/render-var-sig)) "Chosen (Pass 2 upgraded"))
        (is (false? (:fits-width? (get mob-by 'seon.render.entities/render-var-full))))
        (is (str/includes? (:reason (get mob-by 'seon.render.entities/render-var-full)) "Skipped: min-width 540px > slot 398px"))

        (is (= "CUSTOM-TEXT" (:text (get tight-by 'seon.render.entities/render-var-chip))))
        (is (= "Chosen (Pass 1 base fit)" (:reason (get tight-by 'seon.render.entities/render-var-chip))))
        (is (str/includes? (:reason (get tight-by 'seon.render.entities/render-var-full)) "Skipped: needs +"))
        (is (= [:rule.ctx/edited-var-on-branch] (:rules ex-tight)))
        (is (= 0.15 (:rank/boost ex-tight)))
        (is (= {:show-source? true} (:params ex-tight)))

        (is (= "0 tok (already-shown)" (:reason (first (:variations (get by-e 10))))))
        (is (false? (:selected? (get by-e 20))))
        (is (str/starts-with? (:reason (first (:variations (get by-e 20)))) "Dropped: exceeds budget"))

        ;; 4. Cache hits on discover-renderers & cached-registry-info MUST replay datahike.reads
        (let [[_ rs1] (reads/with-reads #(do (fit/cached-registry-info db) (fit/discover-renderers db :seon.code.var/entity)))
              [_ rs2] (reads/with-reads #(do (fit/cached-registry-info db) (fit/discover-renderers db :seon.code.var/entity)))]
          (is (pos? (count rs1)))
          (is (= rs1 rs2) "Cached fit lookups must replay exact datahike.reads"))))))

(deftest ^{:cost/ticks 50000000} wire-and-walk-cache-anatomy-test
  (testing "analyze-wire-cache classifies prefix, delta zones, already-shown, and diagnoses prefix busts; analyze-walk-cache classifies UI units"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:seon.task/id :task/wc :seon.task/title "Wire cache test" :seon.task/done? false}
                                  {:seon.agent/id :ag/wc :seon.agent/branch :main :seon.agent/role :worker
                                   :seon.agent/task :task/wc}]})
      ;; Turn 1 & Turn 2 share prefix hash "aaaa1111"
      (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/wc
                                   :seon.agent.eval/turn 1 :seon.agent.eval/code "(+ 1 1)"
                                   :seon.agent.eval/prefix-hash "aaaa1111"
                                   :seon.agent.eval/prompt-tokens 5000 :seon.agent.eval/cache-hit-tokens 0
                                   :seon.agent.eval/cache-miss-tokens 5000}]})
      (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/wc
                                   :seon.agent.eval/turn 2 :seon.agent.eval/code "(+ 2 2)"
                                   :seon.agent.eval/prefix-hash "aaaa1111"
                                   :seon.agent.eval/prompt-tokens 5200 :seon.agent.eval/cache-hit-tokens 4800
                                   :seon.agent.eval/cache-miss-tokens 400}]})
      ;; Mutate :seon.agent/pinned between Turn 2 and Turn 3 -> causes prefix bust!
      (d/transact conn {:tx-data [{:seon.agent/id :ag/wc :seon.agent/pinned [[:seon.task/id :task/wc]]}]})
      (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/wc
                                   :seon.agent.eval/turn 3 :seon.agent.eval/code "(+ 3 3)"
                                   :seon.agent.eval/prefix-hash "bbbb2222"
                                   :seon.agent.eval/prompt-tokens 6000 :seon.agent.eval/cache-hit-tokens 400
                                   :seon.agent.eval/cache-miss-tokens 5600}]})
      (let [db @conn
            wire-req {:prefix-hash "bbbb2222"
                      :total-tokens 1600
                      :messages [{:role "system" :content "You are Seon."}
                                 {:role "user" :content "<agent id=\":ag/wc\">\n<walk-context>\nctx\n</walk-context>"}
                                 {:role "assistant" :content "Checking code."}
                                 {:role "tool" :content "result/e1 => 2"}
                                 {:role "user" :content [{:type "text" :text "<message from=\"user\" type=\"chat\">\nhi\n</message>\n\n<scoped-health ephemeral=\"true\">\nok\n</scoped-health>"}
                                                         {:type "image_url" :image_url {:url "data:image/jpeg;base64,AAAA"}}]}]
                      :message-tokens [200 800 100 150 350]
                      :tail-blocks [{:tag "scoped-health" :text "ok"}]
                      :unread [{:seon.agent.message/body "hi"}]
                      :ephemeral-screenshot {:width 1280 :height 820 :bytes 4096 :base64 "AAAA"}
                      :shown {1 {:fn 'demo/a} 2 {:fn 'demo/b}}
                      :fit-trace {:items [{:entity 1 :status :unchanged :tokens 0 :measured-tokens 120 :chosen-fn 'demo/a}
                                          {:entity 2 :status :rewarmed :tokens 90 :measured-tokens 90 :chosen-fn 'demo/b}]}}
            wc (scache/analyze-wire-cache db db :ag/wc wire-req)
            wk (scache/analyze-walk-cache [{:id "seon-header" :reads-count 3 :hit? true :ms 0.0}
                                           {:id "seon-sidebar" :reads-count 5 :hit? true :ms 0.0}
                                           {:id "seon-view-unit" :reads-count 14 :hit? false :ms 3.4}])]
        (is (= [0 1] (get-in wc [:cached-prefix :message-indices])))
        (is (= 1000 (get-in wc [:cached-prefix :tokens])))
        (is (= "bbbb2222" (get-in wc [:cached-prefix :prefix-hash])))
        (is (= 600 (get-in wc [:uncached-delta :tokens])))
        (is (= 250 (get-in wc [:uncached-delta :zones :history :tokens])))
        (is (= 1 (get-in wc [:uncached-delta :zones :unread :count])))
        (is (true? (get-in wc [:uncached-delta :zones :vision :present?])))
        (is (= 3 (count (:turns wc))))
        (is (= [false false true] (mapv :busted? (:turns wc))))
        (is (= 1 (:bust-count wc)))
        (is (str/includes? (str/lower-case (:reason (first (:busts wc)))) "pins"))
        (is (= 1 (get-in wc [:already-shown :zero-token-count])))
        (is (= 120 (get-in wc [:already-shown :saved-tokens])))
        (is (= 1 (get-in wc [:already-shown :rewarmed-count])))
        (is (string? (:summary wc)))

        (is (= 2 (:hits wk)))
        (is (= 1 (:misses wk)))
        (is (= 22 (:total-reads wk)))
        (is (= 0.6667 (:hit-rate wk)))
        (is (= 3 (count (:units wk))))))))

(deftest ^{:cost/ticks 60000000} reasoning-mentions-rule-toggles-and-verdict-fix-test
  (testing "Reasoning mentions fold into :touched-* when enabled while keeping :manual-lookup-* code-only, rule toggles work, and verdict-latest-for-var? suppresses stale fails"
    (with-test-store [{:keys [conn cfg]}]
      (let [src (str "(ns demo.reas)\n"
                     "(defn compose-live-widget \"Widget fn\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"
                     "(defn unused-helper \"Helper fn\" {:malli/schema [:=> [:cat :int] :int]} [x] (dec x))\n")
            _ (d/transact conn {:tx-data (concat (code/file-import-tx @conn "src/demo/reas.clj" src false)
                                                 [{:seon.task/id :task/reas :seon.task/title "Reas test" :seon.task/scope 'demo.reas :seon.task/done? false}
                                                  {:seon.agent/id :ag/reas :seon.agent/branch :main :seon.agent/role :worker
                                                   :seon.agent/scope 'demo.reas :seon.agent/task :task/reas}])})
            _ (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/reas
                                           :seon.agent.eval/turn 1 :seon.agent.eval/handle 'result/e1
                                           :seon.agent.eval/code "(+ 1 1)"
                                           :seon.agent.eval/reasoning "Need to inspect compose-live-widget and :seon.task/entity next."}]})
            db @conn
            w-eid (:e (first (d/datoms db :avet :seon.code.var/name 'demo.reas/compose-live-widget)))
            t-on (touches/extract-eval-touches db :ag/reas {:include-reasoning? true})
            t-off (touches/extract-eval-touches db :ag/reas {:include-reasoning? false})
            sb-all (sb/context-scoreboard db db :ag/reas {:token-budget 4000})
            sb-no-nb (sb/context-scoreboard db db :ag/reas {:token-budget 4000 :disabled-rules #{:rule.scope/neighbor}})]
        (is (contains? (:reasoning-syms t-on) 'demo.reas/compose-live-widget))
        (is (contains? (:reasoning-eids t-on) w-eid))
        (is (contains? (:touched-syms t-on) 'demo.reas/compose-live-widget))
        (is (not (contains? (:touched-syms t-off) 'demo.reas/compose-live-widget)))
        (is (empty? (:manual-lookup-syms t-on)) "Manual lookups stay strictly code-only")
        (is (< (:pool-size sb-no-nb) (:pool-size sb-all)) "Disabling :rule.scope/neighbor shrinks candidate pool")

        ;; verdict-latest-for-var?: passing verdict >= tx OR passing branch/main run clears stale FAIL
        (let [fail-tx (:max-tx (:db-after
                                (d/transact conn {:tx-data [{:seon.test/digest "dg-fail-stale"
                                                             :seon.test/var 'demo.reas/compose-live-widget
                                                             :seon.test/var-ref w-eid
                                                             :seon.test/kind :generative
                                                             :seon.test/pass? false
                                                             :seon.test/ms 1.0
                                                             :seon.test/report "old fail"}]})))]
          (is (true? (rules/verdict-latest-for-var? @conn w-eid fail-tx)))
          ;; Same-branch passing test run clears it unless digest in :failed-digests
          (d/transact conn {:tx-data [{:seon.test.run/branch :main :seon.test.run/pass? true :seon.test.run/ran 5 :seon.test.run/cached 0}]})
          (is (false? (rules/verdict-latest-for-var? @conn w-eid fail-tx)))
          ;; Forked branch inheriting green :main clears stale fail from before :main run
          (store/branch! conn :main :branch/guide-sim)
          (let [bconn (store/connect cfg :branch/guide-sim)]
            (try
              (is (false? (rules/verdict-latest-for-var? @bconn w-eid fail-tx)))
              ;; Brand-new failing verdict AFTER :main passing run DOES surface on the branch
              (let [new-fail-tx (:max-tx (:db-after
                                          (d/transact bconn {:tx-data [{:seon.test/digest "dg-fail-fresh"
                                                                        :seon.test/var 'demo.reas/compose-live-widget
                                                                        :seon.test/var-ref w-eid
                                                                        :seon.test/kind :generative
                                                                        :seon.test/pass? false
                                                                        :seon.test/ms 1.0
                                                                        :seon.test/report "fresh fail"}]})))]
                (is (true? (rules/verdict-latest-for-var? @bconn w-eid new-fail-tx))))
              (finally (d/release bconn)))))

        ;; rules.edn rank boosts & renderer params present on canonical rules
        (let [by-id (into {} (map (juxt :seon.rule/id identity)) (rseed/starter-rules))
              ed-p (first (filter map? (:seon.rule/assert (get by-id :rule.ctx/edited-var-on-branch))))
              err-p (first (filter map? (:seon.rule/assert (get by-id :rule.ctx/fault-with-recommended-fix))))]
          (is (= 0.15 (:rank/boost ed-p)))
          (is (true? (get-in ed-p [:params :seon.ui/show-callers?])))
          (is (= 0.25 (:rank/boost err-p)))
          (is (= :fix (get-in err-p [:params :seon.ui/expand-section]))))))))

(deftest ^{:cost/ticks 60000000} calibrated-flow-pressure-and-seed42-generative-test
  (testing "Flow separates :paused (:calm) from :stalled (:warm); Pressure uses median+peak and avoids single-spike false :hot"
    (with-test-store [{:keys [conn]}]
      ;; 3 idle paused agents + 11 light evals + 1 huge historical 260 MB spike -> Flow :calm, Pressure NOT :hot!
      (d/transact conn {:tx-data (concat
                                  [{:seon.agent/id :ag/p1 :seon.agent/branch :main :seon.agent/role :worker :seon.agent/paused? true}
                                   {:seon.agent/id :ag/p2 :seon.agent/branch :main :seon.agent/role :worker :seon.agent/paused? true}
                                   {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/p1 :seon.agent.eval/turn 1
                                    :seon.agent.eval/code "(big)" :seon.agent.eval/bytes 272629760 :seon.agent.eval/ticks 1000000 :seon.agent.eval/ms 10.0}]
                                  (for [i (range 2 10)]
                                    {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/p1 :seon.agent.eval/turn i
                                     :seon.agent.eval/code "(+ 1 1)" :seon.agent.eval/bytes 8388608 :seon.agent.eval/ticks 500000 :seon.agent.eval/ms 4.0}))})
      (let [snap1 (sig/all-signals @conn)]
        (is (= 2 (get-in snap1 [:flow :paused])))
        (is (= 0 (get-in snap1 [:flow :stalled])))
        (is (= :calm (get-in snap1 [:flow :level])))
        (is (str/includes? (get-in snap1 [:flow :summary]) "2 paused"))
        (is (not= :hot (get-in snap1 [:pressure :level])))
        (is (< (get-in snap1 [:pressure :median-mb]) 20.0))
        (is (> (get-in snap1 [:pressure :peak-mb]) 250.0)))

      ;; Agent stuck awaiting -> :stalled 1, Flow :warm; 2 slow renders > 100ms -> Pressure :hot
      (d/transact conn {:tx-data [{:seon.agent/id :ag/stuck :seon.agent/branch :main :seon.agent/role :worker
                                   :seon.agent/waiting? true :seon.agent/awaiting #{(random-uuid)}}
                                  (serr/error-tx {:kind :slow-render :severity :warning :source :web :message "Slow frame 140ms" :data {:ms 140.0}})
                                  (serr/error-tx {:kind :slow-render :severity :warning :source :web :message "Slow frame 165ms" :data {:ms 165.0}})]})
      (let [snap2 (sig/all-signals @conn)]
        (is (= 1 (get-in snap2 [:flow :stalled])))
        (is (= :warm (get-in snap2 [:flow :level])))
        (is (= :hot (get-in snap2 [:pressure :level]))))))

  (testing "100-sample seed=42 Malli generative checks on all new pure functions in fit, fit.measure, and signals.cache"
    (doseq [sym '[seon.render.fit/phase-rank-weights
                  seon.render.fit.measure/finite-double
                  seon.render.fit.measure/safe-long
                  seon.render.fit.measure/parse-px-hint
                  seon.render.fit.measure/measure-hiccup
                  seon.render.fit.measure/resolve-slot-budget
                  seon.render.fit.measure/normalize-candidate
                  seon.render.fit.measure/hiccup->text-fallback
                  seon.render.fit.measure/variation-reason
                  seon.signals.cache/classify-wire-zones
                  seon.signals.cache/analyze-already-shown
                  seon.signals.cache/analyze-prefix-turns
                  seon.signals.cache/analyze-walk-cache]]
      (let [res (schema/stress-var nil sym {:samples 100 :seed 42})]
        (is (:ok? res) (str sym " failed seed=42 generative check: " (pr-str res)))))))

(deftest live-eval-touches-and-minimal-context-prefs-test
  (let [db (seon.db/mem-db)
        var-eid (:db/id (d/pull db (quote [:db/id]) [:seon.code.var/name (quote seon.render.fit/rank-candidates)]))
        _ (is (integer? var-eid))
        task-id :test.minimal/ab-task
        aid-full :agent/test-full-ctx
        aid-min :agent/test-min-ctx
        r1 (d/with db {:tx-data [{:seon.task/id task-id :seon.task/title "Minimal vs full context test" :seon.task/scope (quote seon.render.fit) :seon.task/plan "Verify context prefs" :seon.task/done? false}
                                 {:seon.agent/id aid-full :seon.agent/branch :main :seon.agent/role :worker :seon.agent/scope (quote seon.render.fit) :seon.agent/task task-id}
                                 {:seon.agent/id aid-min :seon.agent/branch :main :seon.agent/role :worker :seon.agent/scope (quote seon.render.fit) :seon.agent/task task-id
                                  :seon.agent/context-prefs {:minimal? true :disabled-rules #{:rule.ctx/scope-and-extra-namespaces :rule.ctx/scope-task-history-and-fixes :rule.ctx/citing-docs :rule.ctx/eval-touched-schemas :rule.ctx/eval-compatible-fns}}}
                                 {:seon.agent.eval/id (java.util.UUID/randomUUID) :seon.agent.eval/agent aid-full :seon.agent.eval/turn 1 :seon.agent.eval/code "(user/show (quote seon.render.fit/rank-candidates))" :seon.agent.eval/touched [var-eid var-eid]}]}) 
        db2 (:db-after r1)
        cands-full (:candidates (seon.ctx/gather-agent-candidates db2 db2 aid-full {:target :wire :use-cache? false :semantic? false}))
        touched-c (some #(when (= var-eid (:entity %)) %) cands-full)
        w-full (seon.render.wire/build-live-wire-request db2 db2 aid-full {:vision? false})
        w-min (seon.render.wire/build-live-wire-request db2 db2 aid-min {:vision? false})]
    (is (some? touched-c) "Live :seon.agent.eval/touched EID enters unified candidate pool")
    (is (= 2 (:touches touched-c)) "Cumulative live touch count populates :touches for rank-candidates")
    (is (< (count (:tiles w-min)) (count (:tiles w-full))) "Minimal context-prefs trims non-essential tiles cleanly")))
