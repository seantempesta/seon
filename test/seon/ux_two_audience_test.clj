(ns seon.ux-two-audience-test
  "Proves Two-Audience Design (Normal User vs Developer Mode), single unified `#seon-header`
   breadcrumbs (0 duplicate nav/breadcrumb bars), sanitized User Canvas fallback, zero-scroll 100dvh CSS,
   and `seed=42` generative checks on edited pure renderers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.graph :as graph]
            [seon.render :as render]
            [seon.render.surface :as surface]
            [seon.render.ui :as ui]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]
            [seon.web.styles :as styles]
            [clojure.core.async :as a]
            [seon.browser :as browser]
            [seon.render.fit :as fit]
            [seon.render.fit.measure :as fmeas]
            [seon.render.markdown :as rmd]))

(defn- seed-ux-world! [conn]
  (d/transact conn {:tx-data (code/file-import-tx
                              @conn "src/demo/ux.clj"
                              "(ns demo.ux)\n(defn twice {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] (* 2 x))\n"
                              false)})
  (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn {:analyze-compiler? true})})
  (d/transact
   conn
   {:tx-data
    [{:seon.task/id :task/ux-audit
      :seon.task/title "UX Audit Task"
      :seon.task/scope 'demo.ux
      :seon.task/owner :agent/root
      :seon.task/done? false}
     {:seon.agent/id :agent/root
      :seon.agent/entry? true
      :seon.agent/label "Root"
      :seon.agent/hero-title "Welcome to Seon"
      :seon.agent/branch :branch/root
      :seon.agent/scope 'demo.ux
      :seon.agent/tasks #{[:seon.task/id :task/ux-audit]}
      :seon.agent/goal "Act as the user's intermediary: farm subsystem questions down to specialist agents."
      :seon.agent/pinned [[:seon.task/id :task/ux-audit] [:seon.code.var/name 'demo.ux/twice]]}
     {:seon.agent/id :agent/helper
      :seon.agent/parent :agent/root
      :seon.agent/label "Planner"
      :seon.agent/branch :branch/helper
      :seon.agent/ready? true}
     {:seon.user/id :user/default
      :seon.user/viewing [:seon.agent/id :agent/root]
      :seon.user/view-mode :canvas
      :seon.user/sidebar-open? true
      :seon.user/expert-mode? false
      :seon.user/expanded-agents #{:agent/root}}]}))

(defn- walk-callable-forms [tree]
  (let [acc (volatile! [])]
    (letfn [(visit [n]
              (cond
                (and (vector? n) (keyword? (first n)))
                (let [[_ & more] n
                      attrs (when (map? (first more)) (first more))
                      kids (if attrs (rest more) more)]
                  (when attrs
                    (doseq [k [:on-click :on-submit :on-change]]
                      (when-let [hv (get attrs k)]
                        (when-not (string? hv) (vswap! acc conj hv)))))
                  (doseq [c kids] (visit c)))
                (sequential? n) (doseq [c n] (visit c))))]
      (visit tree)
      @acc)))

(deftest ^{:cost/ticks 90000000} normal-user-canvas-chat-shell-and-sanitization-test
  (testing "Normal User Mode (expert-mode? = false) delivers calm zero-jargon Canvas, single-column Chat, clean shell, and sanitized canvas-hiccup"
    (with-test-store [{:keys [conn]}]
      (seed-ux-world! conn)
      (let [db @conn
            hdr-h (ui/header-unit db {:boot-seq 1 :agent-states {} :active-count 0})
            hdr (render/hiccup->html hdr-h)
            hdr-act (render/hiccup->html (ui/header-unit db {:boot-seq 1 :agent-states {:agent/root :active} :active-count 1}))
            sb-h (ui/sidebar-unit db {:agent-states {:agent/root :active}})
            sb (render/hiccup->html sb-h)
            cp-h (ui/composer-unit db {})
            cp (render/hiccup->html cp-h)
            st-h (ui/stream-unit db {:live-streams {:agent/root {:status :token :phase :writing :started-ms 1000 :last-ms 2000 :step-chars 200 :chars 200 :context-tokens 4000 :max-context-tokens 8000 :text "Drafting"}}
                                     :errors [{:unit "seon-view-unit" :message "Connection interrupted"}]
                                     :viewing-id :agent/root :view-mode :canvas :expert-mode? false})
            st-empty (render/hiccup->html (ui/stream-unit db {:live-streams {} :errors [] :viewing-id :agent/root :view-mode :canvas :expert-mode? false}))
            st (render/hiccup->html st-h)
            cv-h (surface/render-surface db db {:conn conn :viewing-id :agent/root :view-mode :canvas :expert-mode? false :viewport-w 1440 :viewport-h 900})
            cv (render/hiccup->html cv-h)
            ch-h (surface/render-surface db db {:conn conn :viewing-id :agent/root :view-mode :session :expert-mode? false :viewport-w 1440 :viewport-h 900})
            ch (render/hiccup->html ch-h)]
        ;; 1. Header in Normal User Mode
        (is (str/includes? hdr "data-expert-mode=\"false\""))
        (is (str/includes? hdr "href=\"/\""))
        (is (str/includes? hdr ">Canvas</a>"))
        (is (str/includes? hdr ">Chat</a>"))
        (is (str/includes? hdr ">Dev</button>"))
        (is (str/includes? hdr ">Root</span>"))
        (doseq [forbidden ["←" "→" "Healthy" "sig-pill" "vision-toggle" "Explain" ">System</a>" "Activity" "interestfor=\"seon-hovercard\"" "/system"]]
          (is (not (str/includes? hdr forbidden)) (str "User Mode header must hide: " forbidden)))
        (is (str/includes? hdr-act "Working…"))
        (is (not (str/includes? hdr-act "$aggRate")))

        ;; History nav attrs + arrows appear in User Mode when nav history exists
        (let [db-nav (:db-after (d/with db {:tx-data [{:seon.user/id :user/default :seon.user/lab-prefs {:nav-back ["/canvas"] :nav-seq 7 :nav-op :push}}]}))
              hdr-nav (render/hiccup->html (ui/header-unit db-nav {:boot-seq 1 :agent-states {} :active-count 0}))]
          (is (str/includes? hdr-nav "data-nav-seq=\"7\""))
          (is (str/includes? hdr-nav "data-nav-op=\"push\""))
          (is (str/includes? hdr-nav "←"))
          (is (str/includes? hdr-nav "→")))

        ;; 2. Sidebar in Normal User Mode
        (is (str/includes? sb ">Conversations</span>"))
        (is (str/includes? sb "Working…"))
        (is (str/includes? sb "Ready"))
        (is (str/includes? sb "+ New conversation"))
        (is (str/includes? sb "New conversation name…"))
        (is (str/includes? sb "seon.web%2Fview-agent%21+%3Aagent%2Froot+%3Acanvas"))
        (doseq [forbidden ["/system" "Agents ·" "interestfor=\"seon-hovercard\"" "title=\":agent/root\"" ":ui/polish" "seon.web)"]]
          (is (not (str/includes? sb forbidden)) (str "User Mode sidebar must hide: " forbidden)))

        ;; 3. Composer & Stream in Normal User Mode
        (is (str/includes? cp "Ask anything…"))
        (is (not (str/includes? cp "eval Clojure")))
        (is (str/includes? st-empty "Live helper activity"))
        (is (not (str/includes? st-empty "tok/s")))
        (is (str/includes? st "Connection interrupted"))
        (is (not (str/includes? st "{:unit")))
        (is (not (str/includes? st "tok/s")))
        (is (not (str/includes? st "context tokens")))
        (is (not (str/includes? st "interestfor=\"seon-hovercard\"")))
        (is (str/includes? st "seon.web%2Fview-agent%21+%3Aagent%2Froot+%3Acanvas"))

        ;; 4. Empty Canvas in Normal User Mode: calm greeting + 3 starter chips, ZERO dev jargon
        (is (str/includes? cv "id=\"cv-user-welcome\""))
        (is (str/includes? cv "Summarize what&#39;s happening"))
        (is (str/includes? cv "Help me plan a project"))
        (is (str/includes? cv "Show me an interactive demo"))
        (doseq [forbidden ["0 proofs" "canvas.clj" "docs/render.md" "Explain" "vision-toggle" "tx 5368"
                           "Active Context" "Merge → :main" "Live System" "result/e1"
                           "Act as the user&#39;s intermediary" "cv-demo-deck" "cv-pinned-cards"]]
          (is (not (str/includes? cv forbidden)) (str "User Mode empty Canvas must hide: " forbidden)))

        ;; 5. Single-column Chat in Normal User Mode
        (is (str/includes? ch "class=\"user-chat-wrap\""))
        (is (str/includes? ch "class=\"user-chat-col\""))
        (doseq [forbidden ["workspace-grid" "ws-right" "Active Context" "Tasks &amp; Edits" "Merge → :main" "Details" "@ tx"]]
          (is (not (str/includes? ch forbidden)) (str "User Mode Chat must hide: " forbidden)))

        ;; 6. Active Canvas + sanitized :canvas-hiccup + human timeline labels (zero raw tx IDs) + zero raw eval folds
        (let [m1 (random-uuid)]
          (d/transact conn {:tx-data [{:seon.agent/id :agent/root
                                       :seon.agent/canvas-hiccup [:div {:class "card" :onclick "evil()"} [:script "alert(1)"] [:p "Safe live widget"]]}
                                      {:seon.agent.message/id m1 :seon.agent.message/seq 1 :seon.agent.message/from :user
                                       :seon.agent.message/to :agent/root :seon.agent.message/type :chat :seon.agent.message/body "Hello"}]})
          (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root :seon.agent.eval/turn 1
                                       :seon.agent.eval/call-index 0 :seon.agent.eval/handle 'result/e1 :seon.agent.eval/intent "Check"
                                       :seon.agent.eval/code "(+ 1 1)" :seon.agent.eval/value "2" :seon.agent.eval/reasoning "Secret chain"
                                       :seon.agent.eval/seen-messages #{m1}}
                                      {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root :seon.agent.eval/turn 1
                                       :seon.agent.eval/call-index 1 :seon.agent.eval/code "" :seon.agent.eval/thought "Here is your clear answer."}]})
          (let [db2 @conn
                cv-act (render/hiccup->html (surface/render-surface db2 db2 {:conn conn :viewing-id :agent/root :view-mode :canvas :expert-mode? false :viewport-w 1440 :viewport-h 900}))
                cv-run (render/hiccup->html (surface/render-surface db2 db2 {:conn conn :viewing-id :agent/root :view-mode :canvas :expert-mode? false :agent-states {:agent/root :active} :live-stream {:status :token :phase :writing :started-ms 1000 :last-ms 2000 :step-chars 160 :chars 160 :reasoning "Internal thought" :text "Streaming reply"}}))]
            (is (str/includes? cv-act "Safe live widget"))
            (is (not (str/includes? cv-act "<script")))
            (is (not (str/includes? cv-act "onclick")))
            (is (not (str/includes? cv-act "Live Agent Canvas")))
            (is (str/includes? cv-act "Here is your clear answer."))
            (is (not (str/includes? cv-act "result/e1")))
            (is (not (str/includes? cv-act "tx 5368")))
            (is (str/includes? cv-act "Step "))
            (is (str/includes? cv-run "Working…"))
            (is (not (str/includes? cv-run "Live reasoning")))
            (is (not (str/includes? cv-run "tok/s")))
            (is (not (str/includes? cv-run "result/e1")))))

        ;; 7. Every callable handler in Normal User Mode resolves to a bound ^:seon/callable var
        (doseq [[lbl tree] [[:hdr hdr-h] [:sb sb-h] [:cp cp-h] [:st st-h] [:cv cv-h] [:ch ch-h]]
                form (walk-callable-forms tree)]
          (let [sym (when (seq? form) (first form))
                v (when (qualified-symbol? sym) (resolve sym))]
            (is (and v (:seon/callable (meta v))) (str lbl " has non-callable form: " (pr-str form)))))))))

(deftest ^{:cost/ticks 90000000} dev-mode-single-breadcrumbs-and-generative-test
  (testing "Developer Mode retains full dev controls, all sub-views emit ZERO duplicate breadcrumb bars, :system header does not double-root, and seed=42 checks pass"
    (with-test-store [{:keys [conn]}]
      (seed-ux-world! conn)
      (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/expert-mode? true :seon.user/view-mode :system}]})
      (let [db @conn
            hdr-sys (render/hiccup->html (ui/header-unit db {:boot-seq 1 :agent-states {} :active-count 0}))
            cv-dev (render/hiccup->html (surface/render-surface db db {:conn conn :viewing-id :agent/root :view-mode :canvas :expert-mode? true :viewport-w 1440 :viewport-h 900}))
            ch-dev (render/hiccup->html (surface/render-surface db db {:conn conn :viewing-id :agent/root :view-mode :session :expert-mode? true :viewport-w 1440 :viewport-h 900}))
            insp-html (render/hiccup->html (surface/render-surface db db {:conn conn :viewing-id :agent/root :inspect-ref [:seon.code.var/name 'demo.ux/twice] :inspect-trail [[:seon.agent/id :agent/root]] :inspect-forward [[:seon.task/id :task/ux-audit]] :expert-mode? true :viewport-w 1440 :viewport-h 900}))
            sys-html (render/hiccup->html (surface/render-surface db db {:conn conn :viewing-id :agent/root :view-mode :system :inspect-trail [[:seon.agent/id :agent/root]] :expert-mode? true :viewport-w 1440 :viewport-h 900}))
            ctx-html (render/hiccup->html (surface/render-surface db db {:conn conn :viewing-id :agent/root :view-mode :context :expert-mode? true :viewport-w 1440 :viewport-h 900}))
            ent-exp-html (render/hiccup->html (surface/render-surface db db {:conn conn :viewing-id :agent/root :view-mode :context :inspect-ref [:seon.code.var/name 'demo.ux/twice] :expert-mode? true :viewport-w 1440 :viewport-h 900}))
            css (styles/full-css)]
        ;; 1. Dev Mode header retains all dev controls AND :system does NOT repeat Seon Runtime twice!
        (doseq [needle ["is-dev-mode" "Dev Mode · ON" "←" "→" "Flow" "Pressure" "Context" "Proof" "Health"
                        "vision-toggle" "Activity" ">Canvas</a>" ">Chat</a>" ">Explain</a>" ">System</a>"]]
          (is (str/includes? hdr-sys needle) (str "Dev header missing: " needle)))
        (is (= 1 (count (re-seq #"Seon Runtime" hdr-sys)))
            (str ":system header must render 'Seon Runtime' exactly once, got: " (count (re-seq #"Seon Runtime" hdr-sys))))

        ;; 2. Dev Mode Canvas & Chat retain all dev controls
        (doseq [needle ["is-dev-canvas" "DEV" "Active Context" "Test" "Merge → :main" "Live System"]]
          (is (str/includes? cv-dev needle) (str "Dev Canvas missing: " needle)))
        (doseq [needle ["workspace-grid" "Active Context" "Tasks &amp; Edits" "Test" "Merge → :main"]]
          (is (str/includes? ch-dev needle) (str "Dev Chat missing: " needle)))

        ;; 3. ZERO duplicate breadcrumb/nav bars across Inspector, System, Context Lab, and Entity Explain!
        (doseq [[lbl html] [[:inspector insp-html] [:system sys-html] [:context-lab ctx-html] [:entity-explain ent-exp-html]]]
          (is (not (str/includes? html "insp-nav-bar")) (str lbl " must NOT emit #insp-nav-bar"))
          (is (not (str/includes? html "class=\"insp-bar\"")) (str lbl " must NOT emit .insp-bar"))
          (is (not (str/includes? html "← Back")) (str lbl " must NOT emit duplicate Back button"))
          (is (not (str/includes? html "→ Forward")) (str lbl " must NOT emit duplicate Forward button")))
        (is (str/includes? insp-html "class=\"entity-actions\""))

        ;; 4. CSS includes 100dvh, user stage/chat rules, starter chips, and zero dead .insp-bar
        (doseq [sel ["100dvh" ".root-canvas-wrap" ".root-canvas.is-user-canvas{" ".user-welcome{" ".starter-chip" ".user-chat-wrap{" ".entity-actions{"]]
          (is (str/includes? css sel) (str "Missing CSS rule: " sel)))
        (is (not (str/includes? css ".insp-bar{")))

        ;; 5. All edited pure renderers pass 100-sample seed=42 Malli generative checks
        (doseq [sym '[seon.render.surface/render-surface-timeline
                      seon.render.surface.canvas/render-canvas-demo-deck
                      seon.render.ui/stream-phase-info
                      seon.render.ui/format-stream-rate
                      seon.render.ui/aggregate-stream-rate
                      seon.render.ui/stream-live-snippet
                      seon.web.styles/full-css]]
          (let [res (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (:ok? res) (str sym " failed seed=42 check: " (pr-str res)))))))))

(defn sample-reactive-canvas
  {:malli/schema [:=> [:cat :map] [:vector :any]] :cost/ticks 500000}
  [{:keys [viewport slot-budget expert-mode?]}]
  [:div {:class "card" :id "reactive-vp-probe"}
   (str "vp=" (:w viewport) "x" (:h viewport) " aw=" (:avail-w slot-budget) " exp=" (boolean expert-mode?))])

(deftest ^{:cost/ticks 120000000} mom-mode-adaptive-canvas-config-and-browser-box-sharing-test
  (with-test-store [{:keys [conn]}]
    (seed-ux-world! conn)
    (d/transact conn
      {:tx-data [{:seon.task/id :task/mom-1 :seon.task/title "Plan family dinner" :seon.task/plan "Menu, groceries, and seating chart." :seon.task/done? false}
                 {:seon.task/id :task/mom-2 :seon.task/title "Book spring flights" :seon.task/plan "Compare direct flights and luggage fees." :seon.task/done? false}
                 {:seon.task/id :task/mom-3 :seon.task/title "Garden weekend list" :seon.task/plan "Mulch, herbs, and drip timer check." :seon.task/done? false}
                 {:seon.task/id :task/mom-4 :seon.task/title "Photo album print" :seon.task/plan "Curate top 40 favorites from summer." :seon.task/done? false}]})
    (let [refs [[:seon.task/id :task/mom-1] [:seon.task/id :task/mom-2]
                [:seon.task/id :task/mom-3] [:seon.task/id :task/mom-4]]]
      (testing "Mom Mode Canvas [:seon/fit ...] adapts 1440px -> 390px with zero Dev chrome and XSS safety"
        (d/transact conn {:tx-data [{:seon.agent/id :agent/root
                                     :seon.agent/canvas-hiccup [:div {:onclick "evil()"}
                                                                [:script "bad()"]
                                                                [:seon/fit {:title "Your Projects" :refs refs}]]}]})
        (let [db @conn
              h-wide (render/hiccup->html (surface/render-surface db db {:view-mode :canvas :viewing-id :agent/root :expert-mode? false :viewport-w 1440 :viewport-h 820 :sidebar-open? false}))
              h-mob (render/hiccup->html (surface/render-surface db db {:view-mode :canvas :viewing-id :agent/root :expert-mode? false :viewport-w 390 :viewport-h 820 :sidebar-open? false}))]
          (is (str/includes? h-wide "has-custom-canvas"))
          (is (str/includes? h-wide "Your Projects"))
          (is (str/includes? h-wide "render-task-full"))
          (is (not (str/includes? h-wide "ent-prov-bar")) "Zero Dev provenance bars in Mom Mode")
          (is (not (str/includes? h-wide "cv-user-welcome")) "No redundant empty welcome below live Canvas")
          (is (not (str/includes? h-wide "<script")) "XSS script stripped")
          (is (not (str/includes? h-wide "onclick")) "XSS onclick stripped")
          (is (str/includes? h-mob "render-task-chip") "Phone viewport gracefully collapses to chips")
          (is (str/includes? h-mob "data-fit-layout=\"chips\""))
          (is (not (str/includes? h-mob "render-task-full")))))

      (testing "Mom Mode Markdown seon-view adapts to viewport with zero View Spec / dev clutter"
        (let [db @conn
              md (str "Here is your week:\n\n```seon-view\n" (pr-str {:title "Week Ahead" :refs refs}) "\n```")
              sb-w (fit/resolve-slot-budget {:viewport-w 1440 :viewport-h 820 :sidebar-open? false :slot :main})
              sb-m (fit/resolve-slot-budget {:viewport-w 390 :viewport-h 820 :sidebar-open? false :slot :main})
              mom-w (render/hiccup->html (rmd/render-markdown-blocks md {:db db :viewport {:w 1440 :h 820} :slot-budget sb-w :expert-mode? false}))
              mom-m (render/hiccup->html (rmd/render-markdown-blocks md {:db db :viewport {:w 390 :h 820} :slot-budget sb-m :expert-mode? false}))
              dev-w (render/hiccup->html (rmd/render-markdown-blocks md {:db db :viewport {:w 1440 :h 820} :slot-budget sb-w :expert-mode? true}))]
          (is (str/includes? mom-w "render-task-full"))
          (is (not (str/includes? mom-w "View Spec")) "Mom Mode hides View Spec EDN drawer")
          (is (str/includes? mom-m "render-task-chip"))
          (is (str/includes? dev-w "View Spec") "Dev Mode preserves View Spec drawer")))

      (testing "Reactive :seon.agent/canvas-fn receives live :viewport and :slot-budget"
        (let [aeid (:e (first (d/datoms @conn :avet :seon.agent/id :agent/root)))
              cur (:seon.agent/canvas-hiccup (d/pull @conn (quote [:seon.agent/canvas-hiccup]) aeid))]
          (d/transact conn {:tx-data (cond-> [{:seon.agent/id :agent/root :seon.agent/canvas-fn (quote seon.ux-two-audience-test/sample-reactive-canvas)}]
                                       cur (conj [:db/retract aeid :seon.agent/canvas-hiccup cur]))}))
        (let [db @conn
              h1 (render/hiccup->html (surface/render-surface db db {:view-mode :canvas :viewing-id :agent/root :expert-mode? false :viewport-w 1440 :viewport-h 820 :sidebar-open? false}))
              h2 (render/hiccup->html (surface/render-surface db db {:view-mode :canvas :viewing-id :agent/root :expert-mode? false :viewport-w 390 :viewport-h 820 :sidebar-open? false}))]
          (is (str/includes? h1 "vp=1440x820"))
          (is (str/includes? h2 "vp=390x820"))))

      (testing ":seon.config/* UI budgets flow into surface model and Dev /chat uses :rail column width"
        (let [dh (:schema @conn)
              decls (vec (remove #(contains? dh (:db/ident %))
                                 [{:db/ident :seon.config/ui-area-multiplier :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
                                  {:db/ident :seon.config/ui-max-items :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
                                  {:db/ident :seon.config/ui-per-item-area-ratio :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
                                  {:db/ident :seon.config/ui-sidebar-breakpoint :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
                                  {:db/ident :seon.config/preview-widths :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}]))]
          (when (seq decls)
            (d/transact conn {:tx-data (mapv #(if (= :seon.config/preview-widths (:db/ident %))
                                                (assoc % :db/valueType :db.type/tuple) %)
                                             decls)})))
        (let [db0 @conn
              dflt (surface/read-ui-config db0)]
          (is (= 1.6 (:area-multiplier dflt)))
          (is (= 8 (:max-items dflt)))
          (is (= 0.45 (:per-item-area-ratio dflt)))
          (is (= 760 (:sidebar-breakpoint dflt)))
          (is (= [360 640] (:preview-widths dflt))))
        (d/transact conn {:tx-data [{:seon.config/key :seon.config/root
                                     :seon.config/ui-area-multiplier 2.0
                                     :seon.config/ui-max-items 10
                                     :seon.config/ui-per-item-area-ratio 0.35
                                     :seon.config/ui-sidebar-breakpoint 820}]})
        (let [db @conn
              cfg (surface/read-ui-config db)
              m-mom (surface/build-surface-model db db {:view-mode :canvas :viewing-id :agent/root :expert-mode? false :viewport-w 1440 :viewport-h 820 :sidebar-open? false})
              m-dev-chat (surface/build-surface-model db db {:view-mode :session :viewing-id :agent/root :expert-mode? true :viewport-w 1440 :viewport-h 820 :sidebar-open? false})]
          (is (= 2.0 (:area-multiplier cfg)))
          (is (= 10 (:max-items cfg)))
          (is (= 0.35 (:per-item-area-ratio cfg)))
          (is (= 820 (:sidebar-breakpoint cfg)))
          (is (> (get-in m-mom [:slot-budget :avail-w]) 1000))
          (is (and (> (get-in m-dev-chat [:slot-budget :avail-w]) 860) (< (get-in m-dev-chat [:rail-budget :avail-w]) 700)) "Dev /chat keeps 2-col main width and fits right rail to narrow :rail width")
          (is (pos? (get-in m-dev-chat [:slot-budget :per-item-budget])))))

      (testing "Warm browser-proc :render-hiccup-frames shares browser box into fit cache and respects :explicit-min-width"
        (fit/clear-browser-box-cache!)
        (let [sent (atom [])
              rch (a/chan 1)
              st0 {:handle {:send! (fn [m] (swap! sent conj m) true)} :url "http://127.0.0.1:9998/"
                   :width 1280 :height 820 :cmd-seq 0 :pending {} :ready? true}
              rsym (quote demo.test/render-wide-card)
              fh "fh-mom-test-1"
              [st1 _] (browser/browser-proc st0 :req {:op :render-hiccup-frames :html "<div>x</div>" :widths [360] :var-sym rsym :form-hash fh :reply-ch rch})
              id1 (:id (last @sent))
              [st2 _] (browser/browser-proc st1 :ipc {:id id1 :ok true :value {:ok true :w 360 :h 420 :scrollW 360 :clientW 360 :overflowX false}})
              id2 (:id (last @sent))
              [st3 _] (browser/browser-proc st2 :ipc {:id id2 :ok true :base64 "AAA=" :width 360 :height 420 :mime "image/jpeg" :dom {:url "/"}})
              id3 (:id (last @sent))
              [_ _] (browser/browser-proc st3 :ipc {:id id3 :ok true :value {:cleared true}})
              ans (a/poll! rch)
              cached (fit/cached-browser-box rsym fh 360)
              m-explicit (fmeas/measure-hiccup [:div {:class "card ent-full" :data-min-width 560} "Big Card"])
              applied (fmeas/apply-browser-box m-explicit cached 390)]
          (is (:ok? ans))
          (is (map? cached) "Browser proc recorded measured box straight into shared fit cache")
          (is (= 420 (:h cached)))
          (is (= :browser (:measured-by applied)))
          (is (= 560 (:min-width applied)) "Explicit :data-min-width floor preserved over 360px wrap width"))
        (fit/clear-browser-box-cache!)))))