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
            [seon.web.styles :as styles]))

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
