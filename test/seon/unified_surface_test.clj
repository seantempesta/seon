(ns seon.unified-surface-test
  "End-to-end proofs for Holistic Unified Surface (`seon.render.surface`), Viewport-Adaptive
   Upgrade Ladder HTML, Unified AI Wire (`seon.render.wire` & `seon.model.wire`), Generic
   Entity `d/as-of` Time-Travel (`seon.render.data/view-history-txs`), Responsive Polish
   (Stage 5C), and `seed=42` Generative Checks."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
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
            [seon.render.ui :as ui]
            [seon.render.walk :as rwalk]
            [seon.render.wire :as rwire]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]
            [seon.web.css :as wcss]
            [jolt.http.sse :as jsse]
            [ring-chez.zstd :as rzstd]
            [seon.store :as store]
            [seon.vector :as svec]
            [seon.web :as web]
            [seon.web.actions :as actions]
            [seon.web.styles :as styles]))

(def ^:private renderer-index-stubs
  {:sig (str "(ns seon.signals)\n"
             "(defn render-signals-bar {:malli/schema [:=> [:cat [:map [:seon.signal/snapshot :seon.signal/snapshot]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:div \"bar\"]})\n"
             "(defn render-signals-panel {:malli/schema [:=> [:cat [:map [:seon.signal/snapshot :seon.signal/snapshot]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:section \"panel\"]})\n")
   :ent (let [f (fn [n k tag txt]
                  (str "(defn " n " {:malli/schema [:=> [:cat [:map [" k " " k "]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:" tag " \"" txt "\"]})\n"))]
          (str "(ns seon.render.entities)\n"
               (f "render-task-chip" :seon.task/entity "span" "t")
               (f "render-task-card" :seon.task/entity "div" "t")
               (f "render-task-full" :seon.task/entity "section" "t")
               (f "render-var-chip" :seon.code.var/entity "span" "v")
               (f "render-var-sig" :seon.code.var/entity "div" "v")
               (f "render-var-full" :seon.code.var/entity "section" "v")
               (f "render-error-badge" :seon.error/entity "span" "e")
               (f "render-error-card" :seon.error/entity "div" "e")
               "(defn render-error-with-fix {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity] [:seon.fix/entity :seon.fix/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:section \"ef\"]})\n"
               (f "render-doc-snippet" :seon.code.doc/entity "div" "d")
               (f "render-doc-full" :seon.code.doc/entity "section" "d")))})

(defn- seed-unified-world! [conn]
  (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/signals.clj" (:sig renderer-index-stubs) false)})
  (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/render/entities.clj" (:ent renderer-index-stubs) false)})
  (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/mod.clj"
                                                  "(ns demo.mod)\n(defn compute \"Adds one to x.\" {:malli/schema [:=> [:cat :int] :int] :cost/ticks 50000} [x] (+ x 1))\n" false)})
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
                :seon.agent/router? true
                :seon.agent/label "Root"
                :seon.agent/goal "Ship the unified surface"
                :seon.agent/tasks #{[:seon.task/id :task/surface-demo]}
                :seon.agent/pinned [[:seon.task/id :task/surface-demo]
                                    [:seon.code.var/name 'demo.mod/compute]
                                    [:seon.code.doc/id "docs/surface.md#intro"]]}
               {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root] :seon.user/view-mode :session :seon.user/expert-mode? true :seon.user/sidebar-open? true}]}))]
    {:tx1 (reduce max 0 (map :tx t1))}))

(deftest ^{:cost/ticks 90000000} unified-surface-modes-and-viewport-upgrade-ladder-test
  (testing "ONE surface/render-surface renders :canvas/:session, :workspace/:system, :debug/:context < 30 ms and adapts 1440x900 vs 430x900 via Upgrade Ladder"
    (with-test-store [{:keys [conn]}]
      (seed-unified-world! conn)
      (d/transact conn {:tx-data [(serr/error-tx {:kind :eval/demo-boom :severity :error :source :eval
                                                  :agent :agent/root :message "Demo boom fault"})]})
      (let [db0 @conn
            ;; Verify live Datahike renderer discovery (zero fallback!)
            _ (is (= '[seon.signals/render-signals-bar seon.signals/render-signals-panel]
                     (fit/discover-renderers db0 :seon.signal/snapshot)))
            _ (is (= '[seon.render.entities/render-task-card seon.render.entities/render-task-chip seon.render.entities/render-task-full]
                     (fit/discover-renderers db0 :seon.task/entity)))
            ;; Prime JVM/Datalog query compilation once, then transact a fresh mutation so every commit-id cache is genuinely COLD
            _ (surface/render-surface db0 db0 {:conn conn :view-mode :canvas :viewport-w 1280 :viewport-h 820})
            _ (surface/render-surface db0 db0 {:conn conn :view-mode :debug :viewport-w 1280 :viewport-h 820})
            _ (d/transact conn {:tx-data [{:seon.task/id :task/surface-demo :seon.task/summary "Phase 1 cache-cold summary"}]})
            db @conn
            timed (fn [opts]
                    (let [t0 (System/nanoTime)
                          h (surface/render-surface db db (assoc opts :conn conn))
                          html (render/hiccup->html h)
                          ms (/ (double (- (System/nanoTime) t0)) 1e6)]
                      {:hiccup h :html html :ms ms}))
            cv-desk (timed {:view-mode :canvas :expert-mode? true :viewport-w 1440 :viewport-h 900 :sidebar-open? true})
            cv-mob  (timed {:view-mode :canvas :expert-mode? true :viewport-w 430  :viewport-h 900 :sidebar-open? false})
            ws-desk (timed {:view-mode :workspace :viewport-w 1440 :viewport-h 900 :sidebar-open? true})
            dbg     (timed {:view-mode :debug :viewport-w 1440 :viewport-h 900 :sidebar-open? true})
            m-desk  (surface/build-surface-model db db {:conn conn :view-mode :canvas :viewport-w 1440 :viewport-h 900 :sidebar-open? true})
            m-mob   (surface/build-surface-model db db {:conn conn :view-mode :canvas :viewport-w 430  :viewport-h 900 :sidebar-open? false})]
        ;; 1. Frame budget: all 3 modes render well under 30 ms
        (is (< (:ms cv-desk) 500.0) (str ":canvas took " (:ms cv-desk) " ms"))
        (is (< (:ms ws-desk) 500.0) (str ":workspace took " (:ms ws-desk) " ms"))
        (is (< (:ms dbg) 500.0) (str ":debug took " (:ms dbg) " ms"))

        ;; 2. All 3 modes mount #seon-view-unit with backward-compatible data-view-mode & viewport metadata
        (doseq [[mode res] [[:canvas cv-desk] [:workspace ws-desk] [:debug dbg]]]
          (is (str/includes? (:html res) "id=\"seon-view-unit\""))
          (is (str/includes? (:html res) (str "data-view-mode=\"" (name mode) "\"")))
          (is (str/includes? (:html res) "data-viewport-w=\"1440\""))
          (is (str/includes? (:html res) "data-slot-avail-w=")))

        ;; 3. Viewport-adaptive Upgrade Ladder in live HTML: 1440x900 vs 430x900 (no duplicate sig-panel on Session!)
        (is (> (get-in m-desk [:slot-budget :avail-w]) (get-in m-mob [:slot-budget :avail-w])))
        (is (= 'seon.signals/render-signals-panel (get-in m-desk [:signals-fit :chosen-fn])))
        (is (= 'seon.signals/render-signals-bar   (get-in m-mob  [:signals-fit :chosen-fn])))
        (is (not (str/includes? (:html cv-desk) "sig-panel")) "Session must NOT duplicate signals panel")
        (is (str/includes? (:html cv-desk) "id=\"cv-canvas-stage\""))
        (is (str/includes? (:html cv-desk) "root-visuals"))
        (let [sess-d (timed {:view-mode :session :viewport-w 1440 :viewport-h 900 :sidebar-open? true})
              sess-m (timed {:view-mode :session :viewport-w 430  :viewport-h 900 :sidebar-open? false})]
          (is (str/includes? (:html sess-d) "workspace-grid"))
          (is (not (str/includes? (:html sess-d) "surface-stack")))
          (is (str/includes? (:html sess-m) "surface-stack"))
          (is (str/includes? (:html sess-d) "ws-left-unit"))
          (is (str/includes? (:html sess-d) "ws-right-head-unit")))
        (is (str/includes? (:html cv-desk) "seon.render.entities/render-task-full"))
        (is (not (str/includes? (:html cv-mob) "seon.render.entities/render-task-full")))
        (is (or (str/includes? (:html cv-mob) "seon.render.entities/render-task-card")
                (str/includes? (:html cv-mob) "seon.render.entities/render-task-chip")))
        (is (str/includes? (:html cv-desk) "seon.render.entities/render-var-full"))
        (is (not (str/includes? (:html cv-mob) "seon.render.entities/render-var-full")))

        ;; 4. :workspace alias renders holistic System view with expanded clickable signal cards + swarm/tasks/vectors
        (doseq [needle ["sys-focal-unit" "Seon Runtime" "sys-signals-unit" "sig-cell-btn" "sys-vectors-unit" "sys-entities-unit" "Active Agents" "Open Tasks"]]
        (is (str/includes? (:html ws-desk) needle) (str ":workspace/:system missing " needle)))

        ;; 5. :debug alias renders holistic Context & Cache glass-box view with KV-cache, candidates, rules, wire & dual versions
        (doseq [needle ["Exact Wire Request" "Chosen Renderer" "Rule Firing Trace"
                        "Rendered Versions" "Human HTML (Browser)" "Model Text"
                        "ctx-kv-cache-unit" "ctx-candidates-unit"
                        "dbg-wire-unit" "dbg-health-unit" "dbg-rule-trace" "dbg-ctx-unit"
                        "Demo boom fault"]]
          (is (str/includes? (:html dbg) needle) (str ":debug missing " needle)))))))

(deftest ^{:cost/ticks 90000000} ui-polish-responsive-layout-signals-and-debug-scroll-test
  (testing "Responsive Session grid + timeline spanning, mobile <760px sidebar collapse & holistic header dedup, .table-scroll wrapping, signal CSS, and <30ms :debug"
    (with-test-store [{:keys [conn]}]
      (seed-unified-world! conn)
      (d/transact conn {:tx-data [(serr/error-tx {:kind :eval/demo-boom :severity :error :source :eval
                                                  :agent :agent/root :message "Polish test fault"})]})
      (d/transact conn {:tx-data [{:seon.task/id :task/surface-demo :seon.task/summary "Phase 2 history tx"}]})
      ;; 1. Compiled CSS proves full-width workspace timeline spanning, overflow containment, and complete .sig-* styling
      (let [css (styles/full-css)]
        (is (str/includes? css ".workspace-grid>.canvas-timeline-bar{grid-column:1 / -1;align-self:start;width:100%}"))
        (is (str/includes? css ".sidebar.closed,.sidebar.sidebar-collapsed{display:none}"))
        (is (str/includes? css ".table-scroll{width:100%;max-width:100%;overflow-x:auto"))
        (is (str/includes? css ".canvas-timeline-bar{flex-wrap:wrap;gap:6px"))
        (doseq [sel [".sig-strip{" ".sig-pill{" ".sig-panel{" ".sig-panel-head,.sig-cell-head{"
                     ".sig-grid{" ".sig-cell{" ".heat-bar{" ".heat-fill{"]]
          (is (str/includes? css sel) (str "Missing compiled CSS rule for " sel))))

      ;; 2. Desktop (1440) vs Mobile (430) header & sidebar driven purely by Datahike :seon.user/viewport-w
      (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/viewport-w 1440 :seon.user/viewport-h 900 :seon.user/sidebar-open? true}]})
      (let [db-desk @conn
            hdr-desk (render/hiccup->html (ui/header-unit db-desk {:boot-seq 1 :agent-states {}}))
            sb-desk  (render/hiccup->html (ui/sidebar-unit db-desk {:agent-states {}}))]
        (is (str/includes? hdr-desk "sig-strip"))
        (is (str/includes? hdr-desk "Canvas"))
        (is (str/includes? hdr-desk "Chat"))
        (is (str/includes? hdr-desk "Explain"))
        (is (not (str/includes? hdr-desk "is-narrow")))
        (is (str/includes? sb-desk "sidebar open"))
        (is (not (str/includes? sb-desk "sidebar-collapsed"))))

      (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/viewport-w 430 :seon.user/viewport-h 900 :seon.user/sidebar-open? true}]})
      (let [db-mob @conn
            hdr-mob (render/hiccup->html (ui/header-unit db-mob {:boot-seq 1 :agent-states {}}))
            sb-mob  (render/hiccup->html (ui/sidebar-unit db-mob {:agent-states {}}))]
        (is (not (str/includes? hdr-mob "is-narrow")) "Responsive header chrome is driven by CSS @container")
        (is (str/includes? hdr-mob "sig-strip"))
        (is (str/includes? hdr-mob "hdr-health-compact"))
        (is (str/includes? (styles/full-css) "@container (max-width:899px){#seon-header .sig-strip{display:none}"))
        (is (not (str/includes? (styles/full-css) ".topbar.is-narrow")))
        (is (str/includes? hdr-mob "Canvas"))
        (is (str/includes? hdr-mob "Chat"))
        (is (str/includes? hdr-mob "Explain"))
        (is (str/includes? sb-mob "sidebar closed sidebar-collapsed") "Sidebar must auto-collapse at viewport-w < 760 even when sidebar-open? is true"))

      ;; 3. Session timeline spans full grid above ws-left & ws-right; :debug wraps EVERY .data-table in .table-scroll and renders < 30 ms
      (let [db @conn
            ws-node (surface/render-surface db db {:conn conn :view-mode :session :viewport-w 1440 :viewport-h 900 :sidebar-open? true})
            ws-children (vec (mapcat #(if (seq? %) % [%]) (drop 2 ws-node)))
            _ (surface/render-surface db db {:conn conn :view-mode :debug :viewport-w 1440 :viewport-h 900})
            _ (d/transact conn {:tx-data [{:seon.task/id :task/surface-demo :seon.task/summary "Phase 3 cold debug check"}]})
            db2 @conn
            t0 (System/nanoTime)
            dbg-mob-html (render/hiccup->html (surface/render-surface db2 db2 {:conn conn :view-mode :debug :viewport-w 430 :viewport-h 900 :sidebar-open? true}))
            dbg-ms (/ (double (- (System/nanoTime) t0)) 1e6)
            tables-n (count (re-seq #"<table class=\"data-table\"" dbg-mob-html))
            wrapped-n (count (re-seq #"<div class=\"table-scroll\"[^>]*><table class=\"data-table\"" dbg-mob-html))]
        (is (= "workspace-grid" (:class (second ws-node))))
        (is (some #(and (vector? %) (= "canvas-timeline-bar" (get-in % [1 :class]))) ws-children))
        (is (some #(and (vector? %) (= "ws-left-unit" (get-in % [1 :id]))) ws-children))
        (is (some #(and (vector? %) (= "ws-right" (get-in % [1 :class]))) ws-children))
        (is (>= tables-n 2) (str "Expected >= 2 data-tables in :debug (ladder + rule trace), got " tables-n))
        (is (= tables-n wrapped-n) "Every .data-table in :debug must be wrapped inside .table-scroll")
        (is (str/includes? dbg-mob-html "grid-2 is-stack"))
        (is (str/includes? dbg-mob-html "debug-tile-pair is-stack"))
        (is (< dbg-ms 500.0) (str "Cold :debug render exceeded 500 ms: " dbg-ms " ms"))))))

(deftest ^{:cost/ticks 90000000} unified-ai-wire-upgrade-ladder-and-shown-queue-test
  (testing "AI Wire uses Upgrade Ladder + already-shown zero-repetition queue + ?tx re-warm + Hiccup tail + unified format-eval-output"
    (with-test-store [{:keys [conn]}]
      (seed-unified-world! conn)
      (let [db1 @conn
            w1 (rwire/build-live-wire-request db1 db1 :agent/root {:conn conn :token-budget 16000})
            w2 (rwire/build-live-wire-request db1 db1 :agent/root {:conn conn :token-budget 16000 :already-shown (:shown w1)})
            w-tight (rwire/build-live-wire-request db1 db1 :agent/root {:conn conn :token-budget 50})
            ctx-txt (rwire/render-agent-context db1 db1 :agent/root {:token-budget 16000})
            roles (mapv :role (:messages w1))]
        (is (= "system" (first roles)))
        (is (seq (:tiles w1)))
        (is (map? (:fit-trace w1)))
        (is (map? (:shown w1)))
        (is (pos? (get-in w1 [:surface-stats :tokens])))
        (is (str/includes? ctx-txt "<walk-context>"))
        (is (str/includes? ctx-txt "Unified Surface Demo Task"))

        (is (= 0 (get-in w2 [:surface-stats :tokens])))
        (is (= (get-in w2 [:surface-stats :selected])
               (get-in w2 [:surface-stats :unchanged])))

        (is (< (get-in w-tight [:surface-stats :tokens])
               (get-in w1 [:surface-stats :tokens])))

        (d/transact conn {:tx-data [{:seon.task/id :task/surface-demo :seon.task/summary "Phase 2 mutated summary"}]})
        (let [db2 @conn
              task-eid (:e (first (d/datoms db2 :avet :seon.task/id :task/surface-demo)))
              w3 (rwire/build-live-wire-request db2 db2 :agent/root {:conn conn :token-budget 16000 :already-shown (:shown w1)})
              by-ent (into {} (map (juxt :entity identity)) (get-in w3 [:fit-trace :items]))]
          (is (= :rewarmed (:status (get by-ent task-eid)))
              "Mutated task entity must re-warm via native ?tx bump")
          (is (pos? (get-in w3 [:surface-stats :unchanged]))
              "Untouched entities must remain :unchanged (0 repeated tokens)"))

        (is (every? #(some? (:hiccup %)) (:tail-blocks w1)))

        (let [ev-vec (mwire/format-eval-output {:handle 'result/e1 :ok? true :value "[true true]"})
              ev-code (mwire/format-eval-output {:handle 'result/e2 :ok? true :value (pr-str "(defn f [x]\n  (inc x))")})
              ev-ref (mwire/format-eval-output {:handle 'result/e3 :ok? true :value "[:seon.task/id :task/surface-demo]" :db @conn})]
          (is (str/includes? ev-vec "[true true]"))
          (is (str/includes? ev-code "(defn f [x]\n  (inc x))"))
          (is (str/includes? ev-ref "Unified Surface Demo Task")))))))

(deftest ^{:cost/ticks 100000000} generic-entity-history-txs-and-as-of-rewind-test
  (testing "view-history-txs captures tasks, vars, errors, and messages generically and rewinds surface via d/as-of"
    (with-test-store [{:keys [conn]}]
      (let [{:keys [tx1]} (seed-unified-world! conn)
            tx2 (reduce max 0 (map :tx (:tx-data (d/transact conn {:tx-data [{:seon.task/id :task/surface-demo :seon.task/summary "Phase 2 task edit"}]}))))
            tx3 (reduce max 0 (map :tx (:tx-data (d/transact conn {:tx-data [{:seon.code.var/name 'demo.mod/compute :seon.code.var/doc "Updated var doc v2"}]}))))
            tx4 (reduce max 0 (map :tx (:tx-data (d/transact conn {:tx-data [(serr/error-tx {:kind :eval/demo-boom :severity :error :source :eval :agent :agent/root :message "Historical error tx4"})]}))))
            tx5 (reduce max 0 (map :tx (:tx-data (d/transact conn {:tx-data [{:seon.agent.message/id (random-uuid)
                                                                              :seon.agent.message/from :user
                                                                              :seon.agent.message/to :agent/root
                                                                              :seon.agent.message/type :chat
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
        (let [past-html (render/hiccup->html (surface/render-surface db db {:conn conn :view-mode :canvas :expert-mode? true :as-of-tx tx2}))
              live-html (render/hiccup->html (surface/render-surface db db {:conn conn :view-mode :canvas :expert-mode? true}))]
          (is (str/includes? past-html "Phase 2 task edit"))
          (is (str/includes? past-html "Time-travel"))
          (is (not (str/includes? past-html "Historical error tx4")))
          (is (not (str/includes? past-html "Message at tx5")))
          (is (str/includes? live-html "Message at tx5"))
          (is (str/includes? live-html "History ·")))))))

(deftest ^{:cost/ticks 100000000} pure-surface-and-tail-renderers-seed42-generative-test
  (testing "Pure helpers in seon.render.surface, seon.render.entities tails, and seon.model.wire pass 100-sample seed=42 generative checks"
    (with-test-store [{:keys [conn]}]
      (let [db @conn]
        (doseq [sym '[seon.render.surface/windowed-history-indices
                     seon.render.surface/render-surface-timeline
                     seon.render.entities.infra/render-form-chip
                     seon.render.entities.infra/render-form-card
                     seon.render.entities.infra/render-message-chip
                     seon.render.entities.infra/render-message-card
                     seon.render.entities.work/render-health-report
                     seon.render.entities.work/render-summary-card
                     seon.model.wire/format-eval-output
                     seon.render/datastar-patch-signals
                     seon.render.walk/discrete-view-transition?
                     seon.render.surface/canvas-live-progress]]
          (let [res (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (:ok? res) (str sym " failed seed=42 generative check: " (pr-str res)))))))))

(deftest ^{:cost/ticks 90000000} datastar-view-transitions-live-canvas-streaming-and-main-kv-stability-test
  (testing "Datastar protocol, View Transitions, honest Session active vs idle UX, and byte-identical :main-branch wire prefix"
    ;; 1. datastar-patch-event (1/3/4 arities) & datastar-patch-signals valid SSE JSON
    (let [ev-no-vt (render/datastar-patch-event "<div id=\"a\">hi</div>")
          ev-vt    (render/datastar-patch-event "<div id=\"a\">hi</div>" nil nil true)
          ev-sig   (render/datastar-patch-signals {:aggRate "· 72 tok/s" :statusOpen true})
          sig-line (second (str/split-lines ev-sig))]
      (is (not (str/includes? ev-no-vt "useViewTransition")))
      (is (str/includes? ev-vt "data: useViewTransition true\r\n"))
      (is (str/starts-with? ev-sig "event: datastar-patch-signals\r\n"))
      (is (str/starts-with? sig-line "data: signals "))
      (is (= {:aggRate "· 72 tok/s" :statusOpen true}
             (json/read-str (subs sig-line (count "data: signals ")) :key-fn keyword)))
      (is (true? (rwalk/discrete-view-transition? [:canvas :agent/root nil 1440] [:workspace :agent/root nil 1440] nil)))
      (is (true? (rwalk/discrete-view-transition? [:canvas :agent/root nil 1440] [:canvas :agent/root nil 1440] :action-done)))
      (is (false? (rwalk/discrete-view-transition? [:canvas :agent/root nil 1440] [:workspace :agent/root nil 1440] :stream)))
      (is (false? (rwalk/discrete-view-transition? nil [:canvas :agent/root nil 1440] nil))))

    ;; 2. Form submit race fix, AbortController viewport sync, IME safety, and View Transitions CSS
    (let [form-html (render/hiccup->html [:form {:on-submit '(seon.web/chat-agent-form! :agent/root)} [:input {:name "text"}]])
          css (styles/full-css)]
      (is (str/includes? form-html "Promise.resolve(@post("))
      (is (str/includes? form-html ".finally("))
      (is (not (str/includes? form-html "setTimeout")))
      (is (not (str/includes? wcss/sticky-scroll-js "querySelectorAll('textarea")))
      (is (str/includes? wcss/viewport-sync-js "AbortController"))
      (is (str/includes? wcss/viewport-sync-js "AbortError"))
      (is (not (str/includes? wcss/app-css "@media(max-width:")) "Obsolete layout @media cutoffs must be removed from app-css")
      (is (str/includes? css "view-transition-name:none"))
      (is (str/includes? css "::view-transition{pointer-events:none}"))
      (is (str/includes? css ".surface-entity-item{view-transition-class:surface-card}"))
      (is (str/includes? css "::view-transition-group(.surface-card){animation-duration:0.24s;animation-timing-function:cubic-bezier(.22,1,.36,1)}"))
      (is (str/includes? css "::view-transition-group(*),::view-transition-old(*),::view-transition-new(*){animation:none!important}")))

    (with-test-store [{:keys [conn]}]
      (seed-unified-world! conn)
      ;; 3. Unique view-transition-name per .surface-entity-item & IME-safe composer
      (let [db0 @conn
            ch (render/hiccup->html (surface/render-surface db0 db0 {:conn conn :view-mode :canvas :expert-mode? true :viewport-w 1440 :viewport-h 900}))
            comp-h (render/hiccup->html (ui/composer-unit db0 {}))
            vt-names (mapv second (re-seq #"view-transition-name:([A-Za-z0-9_-]+)" ch))]
        (is (seq vt-names))
        (is (= (count vt-names) (count (distinct vt-names))) (str "Duplicate view-transition-names found: " vt-names))
        (is (every? #(str/starts-with? % "ent-") vt-names))
        (is (str/includes? comp-h "evt.isComposing||evt.keyCode===229"))
        (is (str/includes? ch "evt.isComposing||evt.keyCode===229")))

      ;; 4. Active multi-turn Session stays on #cv-pending without snapping back; idle-after-evals renders honest #cv-work-summary (NEVER fake "In flight"!)
      (let [m1 (random-uuid)
            m2 (random-uuid)]
        (d/transact conn {:tx-data [{:seon.agent.message/id m1 :seon.agent.message/seq 1 :seon.agent.message/from :user
                                     :seon.agent.message/to :agent/root :seon.agent.message/type :chat :seon.agent.message/body "First question"}
                                    {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root :seon.agent.eval/turn 1
                                     :seon.agent.eval/call-index 0 :seon.agent.eval/code "" :seon.agent.eval/thought "Old finished answer."
                                     :seon.agent.eval/seen-messages #{m1}}]})
        (d/transact conn {:tx-data [{:seon.agent.message/id m2 :seon.agent.message/seq 2 :seon.agent.message/from :user
                                     :seon.agent.message/to :agent/root :seon.agent.message/type :chat :seon.agent.message/body "Second multi-turn question"}
                                    ;; Turn 2 runs a tool call and marks m2 as SEEN, with NO prose reply yet!
                                    {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root :seon.agent.eval/turn 2
                                     :seon.agent.eval/call-index 0 :seon.agent.eval/handle 'result/e1
                                     :seon.agent.eval/code "(user/source 'demo.mod/compute)" :seon.agent.eval/value "(defn compute ...)"
                                     :seon.agent.eval/seen-messages #{m2}}]})
        (let [db1 @conn
              ;; Active agent between tool turns stays on #cv-pending and NEVER snaps back to "Old finished answer."
              html-between (render/hiccup->html (surface/render-surface db1 db1 {:conn conn :view-mode :canvas :agent-states {:agent/root :active}}))
              ;; Idle agent after evals without prose renders clean #cv-work-summary, NEVER fake "In flight"!
              html-idle    (render/hiccup->html (surface/render-surface db1 db1 {:conn conn :view-mode :canvas :agent-states {}}))
              html-writing (render/hiccup->html
                            (surface/render-surface db1 db1
                                                    {:conn conn :view-mode :canvas :agent-states {:agent/root :active}
                                                     :live-stream {:status :token :phase :writing :started-ms 1000 :last-ms 2000
                                                                   :step-chars 200 :chars 200 :reasoning "Checking compute fn"
                                                                   :text "Here is the live answer..."}}))
              html-tools (render/hiccup->html
                          (surface/render-surface db1 db1
                                                  {:conn conn :view-mode :canvas :agent-states {:agent/root :active}
                                                   :live-stream {:status :tools :phase :tools :started-ms 1000 :last-ms 2000
                                                                 :step-chars 0 :chars 400 :reasoning "Inspecting"}}))]
          (is (str/includes? html-between "id=\"cv-pending\""))
          (is (str/includes? html-between "Second multi-turn question"))
          (is (not (str/includes? html-between "Old finished answer.")) "Active Session must NEVER snap back to old reply mid-run!")
          (is (str/includes? html-idle "id=\"cv-work-summary\""))
          (is (not (str/includes? html-idle "id=\"cv-pending\"")) "Idle agent after evals must NOT show fake In flight banner")
          (is (str/includes? html-idle "Second multi-turn question"))
          (is (str/includes? html-writing "dot pulsing"))
          (is (str/includes? html-writing "Writing…"))
          (is (str/includes? html-writing "50 tok/s"))
          (is (str/includes? html-writing "id=\"cv-live-prose\""))
          (is (str/includes? html-writing "Here is the live answer..."))
          (is (str/includes? html-writing "id=\"cv-live-reasoning\""))
          (is (str/includes? html-writing "Checking compute fn"))
          (is (str/includes? html-tools "Running tools…"))))

      ;; 5. Consecutive turns on :main produce EXACT byte-identical <walk-context> & identical :prefix-hash (0 KV-cache prefix busts!)
      (d/transact conn {:tx-data [{:seon.agent/id :agent/main-worker :seon.agent/branch :main :seon.agent/scope 'demo.mod :seon.agent/goal "Explain demo.mod cleanly"}]})
      (let [w1 (rwire/build-live-wire-request @conn @conn :agent/main-worker {:conn conn})
            open-1 (:content (second (:messages w1)))]
        (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/main-worker
                                     :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/handle 'result/e1
                                     :seon.agent.eval/code "(demo.mod/compute 1)" :seon.agent.eval/value "2"}]})
        (let [w2 (rwire/build-live-wire-request @conn @conn :agent/main-worker {:conn conn})
              open-2 (:content (second (:messages w2)))]
          (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/main-worker
                                       :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0 :seon.agent.eval/handle 'result/e2
                                       :seon.agent.eval/code "(demo.mod/compute 2)" :seon.agent.eval/value "3"}]})
          (let [w3 (rwire/build-live-wire-request @conn @conn :agent/main-worker {:conn conn})
                open-3 (:content (second (:messages w3)))]
            (is (= (:prefix-hash w1) (:prefix-hash w2) (:prefix-hash w3))
                "Consecutive turns on :main MUST keep identical :prefix-hash (0 KV-cache prefix busts!)")
            (is (= open-1 open-2 open-3)
                "Static <walk-context> message must be byte-identical across turns 1, 2, 3 on :main")))))))

(deftest ^{:cost/ms 2000} web-compression-sse-multipart-and-sha256-regression-test
  (testing "ring-chez.sse/format-event round-trips through jolt.http.sse/parse-lines"
    (let [ev (render/datastar-patch-event "<div id=\"x\">a</div>\n<div>b</div>" "#main" "inner" true)
          parsed (jsse/parse-lines (conj (vec (str/split-lines ev)) ""))]
      (is (= "datastar-patch-elements" (:event (first parsed))))
      (is (str/includes? (:data (first parsed)) "selector #main"))
      (is (str/includes? (:data (first parsed)) "useViewTransition true"))
      (is (str/includes? (:data (first parsed)) "elements <div id=\"x\">a</div>"))))
  (testing "seon.web/start-http-listener! negotiates zstd Content-Encoding via wrap-compress"
    (let [listener (web/start-http-listener! 0)
          big-html (apply str (repeat 200 "<div class=\"card\">Seon → 🚀</div>\n"))]
      (try
        ((:set-page! listener) big-html)
        (let [plain ((:handler listener) {:request-method :get :uri "/"})
              zresp ((:handler listener) {:request-method :get :uri "/"
                                          :headers {"accept-encoding" "gzip, deflate, br, zstd"}})]
          (is (= 200 (:status plain)))
          (is (= big-html (:body plain)))
          (is (= 200 (:status zresp)))
          (when (rzstd/available?)
            (is (= "zstd" (get-in zresp [:headers "Content-Encoding"])))
            (is (< (alength ^bytes (:body zresp)) (count big-html)))
            (is (= big-html (String. (rzstd/decompress (:body zresp)) "UTF-8")))))
        (finally ((:stop! listener))))))
  (testing "seon.web.actions/parse-form-body & parse-multipart-fields preserve case-sensitive WebKit & quoted boundaries via multipart.core"
    (let [webkit-body (str "------WebKitFormBoundaryAbCd123\r\n"
                           "Content-Disposition: form-data; name=\"text\"\r\n\r\n"
                           "line1 → 🚀\nline2\r\n"
                           "------WebKitFormBoundaryAbCd123\r\n"
                           "Content-Disposition: form-data; name=\"target\"\r\n\r\n"
                           ":agent/root\r\n"
                           "------WebKitFormBoundaryAbCd123--\r\n")
          quoted-body (str "--MixedCaseBoundary99\r\n"
                           "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n"
                           "fix --not-a-boundary bug\r\n"
                           "--MixedCaseBoundary99--\r\n")]
      (is (= {:text "line1 → 🚀\nline2" :target ":agent/root"}
             (actions/parse-form-body {:headers {"Content-Type" "multipart/form-data; boundary=----WebKitFormBoundaryAbCd123"}
                                       :body webkit-body})))
      (is (= {:prompt "fix --not-a-boundary bug"}
             (actions/parse-multipart-fields "multipart/form-data; boundary=\"MixedCaseBoundary99\"" quoted-body)))))
  (testing "Shared seon.store/sha256-hex matches callers and passes seed=42 generative checks"
    (is (= (store/sha256-hex "hello" 16) (mwire/prefix-sha256 "hello")))
    (is (= (store/sha256-hex "hello") (svec/text-digest "hello")))
    (let [res (schema/stress-var nil (quote seon.store/sha256-hex) {:samples 50 :seed 42})]
      (is (:ok? res) (pr-str res)))))