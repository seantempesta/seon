(ns seon.unified-surface-test
  "End-to-end proofs for Wave 2A Unified Surface (`seon.render.surface`), Viewport-Adaptive
   Upgrade Ladder HTML, Unified AI Wire (`seon.render.wire` & `seon.model.wire`), Generic
   Entity `d/as-of` Time-Travel (`seon.render.data/view-history-txs`), and `seed=42` Generative Checks."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.graph :as graph]
            [seon.error :as serr]
            [seon.model.wire :as mwire]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.surface :as surface]
            [seon.render.wire :as rwire]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]))

(def ^:private renderer-index-stubs
  {:sig (str "(ns seon.signals)\n"
             "(defn render-signals-bar {:malli/schema [:=> [:cat [:map [:seon.signal/snapshot :seon.signal/snapshot]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:div \"bar\"]})\n"
             "(defn render-signals-panel {:malli/schema [:=> [:cat [:map [:seon.signal/snapshot :seon.signal/snapshot]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:section \"panel\"]})\n")
   :ent (str "(ns seon.render.entities)\n"
             "(defn render-task-chip {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:span \"t\"]})\n"
             "(defn render-task-card {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:div \"t\"]})\n"
             "(defn render-task-full {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:section \"t\"]})\n"
             "(defn render-var-chip {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:span \"v\"]})\n"
             "(defn render-var-sig {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:div \"v\"]})\n"
             "(defn render-var-full {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:section \"v\"]})\n"
             "(defn render-error-badge {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:span \"e\"]})\n"
             "(defn render-error-card {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:div \"e\"]})\n"
             "(defn render-error-with-fix {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity] [:seon.fix/entity :seon.fix/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:section \"ef\"]})\n"
             "(defn render-doc-snippet {:malli/schema [:=> [:cat [:map [:seon.code.doc/entity :seon.code.doc/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:div \"d\"]})\n"
             "(defn render-doc-full {:malli/schema [:=> [:cat [:map [:seon.code.doc/entity :seon.code.doc/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:section \"d\"]})\n")})

(defn- seed-unified-world! [conn]
  (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/signals.clj" (:sig renderer-index-stubs) false)})
  (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/render/entities.clj" (:ent renderer-index-stubs) false)})
  (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn {:analyze-compiler? true})})
  (let [t1 (:tx-data
            (d/transact
             conn
             {:tx-data
              [{:seon.task/id :task/surface-demo
                :seon.task/title "Unified Surface Demo Task"
                :seon.task/summary "Phase 1 summary"
                :seon.task/plan "1. Rank entities\n2. Fit via Upgrade Ladder"
                :seon.task/done? false}
               {:seon.code.form/key "demo.mod/compute"
                :seon.code.form/ns 'demo.mod
                :seon.code.form/path "src/demo/mod.clj"
                :seon.code.form/order 1
                :seon.code.form/kind :defn
                :seon.code.form/name 'compute
                :seon.code.form/src "(defn compute [x] (+ x 1))"
                :seon.code.form/hash "h-demo-1"}
               {:seon.code.var/name 'demo.mod/compute
                :seon.code.var/ns 'demo.mod
                :seon.code.var/kind :defn
                :seon.code.var/doc "Adds one to x."
                :seon.code.var/arglists '[[x]]
                :seon.code.var/form "demo.mod/compute"
                :seon.code.var/hash "h-demo-1"}
               {:seon.code.doc/id "docs/surface.md#intro"
                :seon.code.doc/path "docs/surface.md"
                :seon.code.doc/order 1
                :seon.code.doc/heading "Surface Intro"
                :seon.code.doc/body "All modes share one pipeline."}
               {:seon.fix/id (random-uuid)
                :seon.fix/error-class :eval/demo-boom
                :seon.fix/symptom "Partial entity threw contract violation"
                :seon.fix/root-cause "Missing contract guard"
                :seon.fix/change-summary "Validate before dispatch"}
               {:seon.agent/id :agent/root
                :seon.agent/entry? true
                :seon.agent/branch :main
                :seon.agent/role :coordinator
                :seon.agent/label "Root"
                :seon.agent/goal "Ship the unified surface"
                :seon.agent/task :task/surface-demo
                :seon.agent/pinned [[:seon.task/id :task/surface-demo]
                                    [:seon.code.var/name 'demo.mod/compute]
                                    [:seon.code.doc/id "docs/surface.md#intro"]]}
               {:seon.user/id :user/default
                :seon.user/viewing [:seon.agent/id :agent/root]
                :seon.user/view-mode :canvas
                :seon.user/sidebar-open? true}]}))]
    {:tx1 (reduce max 0 (map :tx t1))}))

(deftest ^{:cost/ticks 90000000} unified-surface-modes-and-viewport-upgrade-ladder-test
  (testing "ONE surface/render-surface renders :canvas, :workspace, :debug < 30 ms and adapts 1440x900 vs 430x900 via Upgrade Ladder"
    (with-test-store [{:keys [conn]}]
      (seed-unified-world! conn)
      (d/transact conn {:tx-data [(serr/error-tx {:kind :eval/demo-boom :severity :error :source :eval
                                                  :agent :agent/root :message "Demo boom fault"})]})
      (let [db @conn
            ;; Verify live Datahike renderer discovery (zero fallback!)
            _ (is (= '[seon.signals/render-signals-bar seon.signals/render-signals-panel]
                     (fit/discover-renderers db :seon.signal/snapshot)))
            _ (is (= '[seon.render.entities/render-task-card seon.render.entities/render-task-chip seon.render.entities/render-task-full]
                     (fit/discover-renderers db :seon.task/entity)))
            ;; Prime rule engine once so per-mode cold budget measures surface pipeline cleanly
            _ (surface/build-surface-model db db {:conn conn :view-mode :canvas :viewport-w 1280 :viewport-h 820})
            timed (fn [opts]
                    (let [t0 (System/nanoTime)
                          h (surface/render-surface db db (assoc opts :conn conn))
                          html (render/hiccup->html h)
                          ms (/ (double (- (System/nanoTime) t0)) 1e6)]
                      {:hiccup h :html html :ms ms}))
            cv-desk (timed {:view-mode :canvas :viewport-w 1440 :viewport-h 900 :sidebar-open? true})
            cv-mob  (timed {:view-mode :canvas :viewport-w 430  :viewport-h 900 :sidebar-open? false})
            ws-desk (timed {:view-mode :workspace :viewport-w 1440 :viewport-h 900 :sidebar-open? true})
            ws-mob  (timed {:view-mode :workspace :viewport-w 430  :viewport-h 900 :sidebar-open? false})
            dbg     (timed {:view-mode :debug :viewport-w 1440 :viewport-h 900 :sidebar-open? true})
            m-desk  (surface/build-surface-model db db {:conn conn :view-mode :canvas :viewport-w 1440 :viewport-h 900 :sidebar-open? true})
            m-mob   (surface/build-surface-model db db {:conn conn :view-mode :canvas :viewport-w 430  :viewport-h 900 :sidebar-open? false})]
        ;; 1. Frame budget: all 3 modes render well under 30 ms
        (is (< (:ms cv-desk) 30.0) (str ":canvas took " (:ms cv-desk) " ms"))
        (is (< (:ms ws-desk) 30.0) (str ":workspace took " (:ms ws-desk) " ms"))
        (is (< (:ms dbg) 30.0) (str ":debug took " (:ms dbg) " ms"))

        ;; 2. All 3 modes mount #seon-view-unit with viewport & slot metadata
        (doseq [[mode res] [[:canvas cv-desk] [:workspace ws-desk] [:debug dbg]]]
          (is (str/includes? (:html res) "id=\"seon-view-unit\""))
          (is (str/includes? (:html res) (str "data-view-mode=\"" (name mode) "\"")))
          (is (str/includes? (:html res) "data-viewport-w=\"1440\""))
          (is (str/includes? (:html res) "data-slot-avail-w=")))

        ;; 3. Viewport-adaptive Upgrade Ladder in live HTML: 1440x900 vs 430x900
        (is (> (get-in m-desk [:slot-budget :avail-w]) (get-in m-mob [:slot-budget :avail-w])))
        (is (= 'seon.signals/render-signals-panel (get-in m-desk [:signals-fit :chosen-fn])))
        (is (= 'seon.signals/render-signals-bar   (get-in m-mob  [:signals-fit :chosen-fn])))
        (is (str/includes? (:html cv-desk) "sig-panel"))
        (is (str/includes? (:html cv-mob)  "sig-strip"))
        (is (not (str/includes? (:html cv-mob) "sig-panel")))
        (is (str/includes? (:html cv-desk) "seon.render.entities/render-task-full"))
        (is (not (str/includes? (:html cv-mob) "seon.render.entities/render-task-full")))
        (is (or (str/includes? (:html cv-mob) "seon.render.entities/render-task-card")
                (str/includes? (:html cv-mob) "seon.render.entities/render-task-chip")))
        (is (str/includes? (:html cv-desk) "seon.render.entities/render-var-full"))
        (is (not (str/includes? (:html cv-mob) "seon.render.entities/render-var-full")))

        ;; 4. :workspace adapts layout cleanly between desktop 2-column and narrow single-column stack
        (is (str/includes? (:html ws-desk) "workspace-grid"))
        (is (not (str/includes? (:html ws-desk) "surface-stack")))
        (is (str/includes? (:html ws-mob) "surface-stack"))
        (is (str/includes? (:html ws-desk) "ws-left-unit"))
        (is (str/includes? (:html ws-desk) "ws-right-head-unit"))

        ;; 5. :debug renders exact wire request, ladder table, signals, rule trace, and dual versions
        (doseq [needle ["Exact Wire Request" "Chosen Renderer" "Rule Firing Trace"
                        "Rendered Versions" "Human HTML (Browser)" "Model Text"
                        "dbg-wire-unit" "dbg-health-unit" "dbg-rule-trace" "dbg-ctx-unit"
                        "Demo boom fault"]]
          (is (str/includes? (:html dbg) needle) (str ":debug missing " needle)))))))

(deftest ^{:cost/ticks 90000000} unified-ai-wire-upgrade-ladder-and-shown-queue-test
  (testing "AI Wire uses Upgrade Ladder + already-shown zero-repetition queue + ?tx re-warm + Hiccup tail + unified format-eval-output"
    (with-test-store [{:keys [conn]}]
      (seed-unified-world! conn)
      (let [db1 @conn
            w1 (rwire/build-live-wire-request db1 db1 :agent/root {:conn conn :token-budget 16000})
            w2 (rwire/build-live-wire-request db1 db1 :agent/root {:conn conn :token-budget 16000 :already-shown (:shown w1)})
            w-tight (rwire/build-live-wire-request db1 db1 :agent/root {:conn conn :token-budget 220})
            ctx-txt (rwire/render-agent-context db1 db1 :agent/root {:token-budget 16000})
            roles (mapv :role (:messages w1))]
        ;; 1. Least-volatile -> most-volatile message framing & expected keys preserved
        (is (= "system" (first roles)))
        (is (seq (:tiles w1)))
        (is (map? (:fit-trace w1)))
        (is (map? (:shown w1)))
        (is (pos? (get-in w1 [:surface-stats :tokens])))
        (is (str/includes? ctx-txt "<walk-context>"))
        (is (str/includes? ctx-txt "Unified Surface Demo Task"))

        ;; 2. Turn 2 with :already-shown repeats ZERO tokens for unchanged entities
        (is (= 0 (get-in w2 [:surface-stats :tokens])))
        (is (= (get-in w2 [:surface-stats :selected])
               (get-in w2 [:surface-stats :unchanged])))

        ;; 3. Tight token budget shrinks total fitted tokens relative to generous budget
        (is (< (get-in w-tight [:surface-stats :tokens])
               (get-in w1 [:surface-stats :tokens])))

        ;; 4. Mutating ONE entity bumps its native ?tx and re-warms ONLY that entity
        (d/transact conn {:tx-data [{:seon.task/id :task/surface-demo :seon.task/summary "Phase 2 mutated summary"}]})
        (let [db2 @conn
              task-eid (:e (first (d/datoms db2 :avet :seon.task/id :task/surface-demo)))
              w3 (rwire/build-live-wire-request db2 db2 :agent/root {:conn conn :token-budget 16000 :already-shown (:shown w1)})
              by-ent (into {} (map (juxt :entity identity)) (get-in w3 [:fit-trace :items]))]
          (is (= :rewarmed (:status (get by-ent task-eid)))
              "Mutated task entity must re-warm via native ?tx bump")
          (is (pos? (get-in w3 [:surface-stats :unchanged]))
              "Untouched entities must remain :unchanged (0 repeated tokens)"))

        ;; 5. Every ephemeral tail block carries Hiccup from pure ns-map -> ns-map tail renderers
        (is (every? #(some? (:hiccup %)) (:tail-blocks w1)))

        ;; 6. format-eval-output routes through discover-renderers + render-value -> hiccup->text
        (let [ev-vec (mwire/format-eval-output {:handle 'result/e1 :ok? true :value "[true true]"})
              ev-code (mwire/format-eval-output {:handle 'result/e2 :ok? true :value (pr-str "(defn f [x]\n  (inc x))")})
              ev-ref (mwire/format-eval-output {:handle 'result/e3 :ok? true :value "[:seon.task/id :task/surface-demo]" :db @conn})]
          (is (str/includes? ev-vec "[true true]"))
          (is (str/includes? ev-code "(defn f [x]\n  (inc x))"))
          (is (str/includes? ev-ref "Unified Surface Demo Task")))))))

(deftest ^{:cost/ticks 80000000} generic-entity-history-txs-and-as-of-rewind-test
  (testing "view-history-txs captures tasks, vars, errors, and messages generically and rewinds surface via d/as-of"
    (with-test-store [{:keys [conn]}]
      (let [{:keys [tx1]} (seed-unified-world! conn)
            tx2 (reduce max 0 (map :tx (:tx-data (d/transact conn {:tx-data [{:seon.task/id :task/surface-demo :seon.task/summary "Phase 2 task edit"}]}))))
            tx3 (reduce max 0 (map :tx (:tx-data (d/transact conn {:tx-data [{:seon.code.var/name 'demo.mod/compute :seon.code.var/doc "Updated var doc v2"}]}))))
            tx4 (reduce max 0 (map :tx (:tx-data (d/transact conn {:tx-data [(serr/error-tx {:kind :eval/demo-boom :severity :error :source :eval :agent :agent/root :message "Historical error tx4"})]}))))
            tx5 (reduce max 0 (map :tx (:tx-data (d/transact conn {:tx-data [{:seon.agent.message/id (random-uuid)
                                                                              :seon.agent.message/from :user/default
                                                                              :seon.agent.message/to :agent/root
                                                                              :seon.agent.message/body "Message at tx5"}]}))))
            db @conn
            task-eid (:e (first (d/datoms db :avet :seon.task/id :task/surface-demo)))
            var-eid  (:e (first (d/datoms db :avet :seon.code.var/name 'demo.mod/compute)))
            htxs (rdata/view-history-txs db db :agent/root [task-eid var-eid])
            delegated (rdata/canvas-history-txs db :agent/root)]
        (is (= htxs (vec (sort (distinct htxs)))))
        (doseq [tx [tx1 tx2 tx3 tx4 tx5]]
          (is (some #{tx} htxs) (str "Expected tx " tx " in view-history-txs " htxs)))
        (is (seq delegated))
        (let [past-html (render/hiccup->html (surface/render-surface db db {:conn conn :view-mode :canvas :as-of-tx tx2}))
              live-html (render/hiccup->html (surface/render-surface db db {:conn conn :view-mode :canvas}))]
          (is (str/includes? past-html "Phase 2 task edit"))
          (is (str/includes? past-html "Time-travel"))
          (is (not (str/includes? past-html "Historical error tx4")))
          (is (not (str/includes? past-html "Message at tx5")))
          (is (str/includes? live-html "Message at tx5"))
          (is (str/includes? live-html "History ·")))))))

(deftest ^{:cost/ticks 60000000} pure-surface-and-tail-renderers-seed42-generative-test
  (testing "Pure helpers in seon.render.surface, seon.render.entities tails, and seon.model.wire pass 100-sample seed=42 generative checks"
    (with-test-store [{:keys [conn]}]
      (let [db @conn]
        (doseq [sym '[seon.render.surface/windowed-history-indices
                      seon.render.surface/render-surface-timeline
                      seon.render.entities/render-branch-status-tail
                      seon.render.entities/render-scoped-health-tail
                      seon.render.entities/render-activity-tail
                      seon.render.entities/render-inbox-tail
                      seon.render.entities/render-system-health-tail
                      seon.render.entities/render-viewport-tail
                      seon.model.wire/format-eval-output]]
          (let [res (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (:ok? res) (str sym " failed seed=42 generative check: " (pr-str res)))))))))
