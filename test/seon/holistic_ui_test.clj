(ns seon.holistic-ui-test
  "End-to-end proofs for Holistic Seon UI:
   - Unified `:session`, `:context`, `:system`, and fractal `:inspect` views (< 40 ms warm, 1440x900 & 430x900)
   - Hyperlinked `entities.clj` (`.ent-link` buttons + clean plain-text `hiccup->text` + rule-emitted params)
   - Fractal `inspector.clj` (2-read `:eavt`/`:avet` + live `seon.vector/near` + Upgrade Ladder + ALL variations + Back/Forward stack)
   - Glass-box `context_lab.clj` (KV-cache prefix/delta anatomy, bust reasons, walk cache, live `lab-prefs`, Recall-Gap links)
   - Holistic `system.clj` (5 clickable signal cards, swarm tree, tasks DAG, live vector search)
   - Honest Session conversation (ZERO fake \"In flight\" banner on completed/ready/idle-after-evals agents!)
   - End-to-end `actions/invoke-call!` + `transactor` `:show-user` navigation stack + `seed=42` generative checks."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.graph :as graph]
            [seon.error :as serr]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.entities :as rent]
            [seon.render.surface :as surface]
            [seon.render.surface.inspector :as sinsp]
            [seon.render.ui :as ui]
            [seon.render.walk :as rwalk]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor :as txr]
            [seon.vector :as vec]
            [seon.web :as web]
            [seon.web.actions :as actions]
            [seon.web.styles :as styles]
            [clojure.core.async :as a]
            [seon.fork :as fork]
            [seon.fork.calls :as fcalls]
            [seon.fork.helpers :as fhelpers]
            [seon.web.css :as wcss]))

(defn- unit-vec [dims idx]
  (vec (for [i (range dims)] (if (= i idx) 1.0 (if (= i (mod (inc idx) dims)) 0.1 0.0)))))

(defn- seed-holistic-world! [conn]
  (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/alpha.clj"
                                                  "(ns demo.alpha \"Alpha core namespace.\")\n(defn helper \"Helper fn.\" {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] (inc x))\n(defn compute \"Computes next value using helper.\" {:malli/schema [:=> [:cat :int] :int] :cost/ticks 20000} [x] (helper x))\n" false)})
  (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn {:analyze-compiler? true})})
  (let [fix-id (random-uuid)
        err-id (random-uuid)
        ev1-id (random-uuid)
        ev2-id (random-uuid)
        m1-id  (random-uuid)
        m2-id  (random-uuid)]
    (d/transact
     conn
     {:tx-data
      [{:seon.task/id :task/holistic-demo
        :seon.task/title "Holistic Demo Task"
        :seon.task/scope 'demo.alpha
        :seon.task/owner :agent/root
        :seon.task/plan "1. Inspect graph\n2. Verify KV cache"
        :seon.task/summary "Active task summary"
        :seon.task/tests #{'demo.alpha/compute}
        :seon.task/done? false}
       {:seon.code.doc/id "docs/holistic.md#overview"
        :seon.code.doc/path "docs/holistic.md"
        :seon.code.doc/order 1
        :seon.code.doc/heading "Holistic Overview"
        :seon.code.doc/body "Everything derives from one fractal pipeline."}
       {:seon.fix/id fix-id
        :seon.fix/error-class :eval/demo-fault
        :seon.fix/symptom "Boom"
        :seon.fix/root-cause "Unchecked nil"
        :seon.fix/change-summary "Normalize input"
        :seon.fix/regression-test 'demo.alpha/compute}
       (serr/error-tx {:id err-id :kind :eval/demo-fault :severity :error :source :eval
                       :agent :agent/root :branch :main :var 'demo.alpha/compute
                       :message "Demo fault occurred on compute"})
       {:seon.test/digest "deadbeef12345678"
        :seon.test/var 'demo.alpha/compute
        :seon.test/kind :deftest
        :seon.test/pass? true
        :seon.test/ms 1.5
        :seon.test/report "1 assertion passed"}
       {:seon.rule/id :rule.ctx/holistic-demo
        :seon.rule/category :context
        :seon.rule/stratum 20
        :seon.rule/target :any
        :seon.rule/enabled? true
        :seon.rule/doc "Demo context rule"
        :seon.rule/query '[:find ?e ?tx :in $main $branch ?agent :where [$main ?e :seon.task/id :task/holistic-demo ?tx]]
        :seon.rule/assert '[{:entity ?e :zone :static-prefix :priority 95 :rank/boost 0.15 :tx ?tx}]}
       {:seon.agent/id :agent/root
        :seon.agent/entry? true
        :seon.agent/branch :main
        :seon.agent/role :coordinator
        :seon.agent/label "Root"
        :seon.agent/scope 'demo.alpha
        :seon.agent/task :task/holistic-demo
        :seon.agent/goal "Drive holistic UI"
        :seon.agent/pinned [[:seon.task/id :task/holistic-demo]
                            [:seon.code.var/name 'demo.alpha/compute]
                            [:seon.code.doc/id "docs/holistic.md#overview"]]}
       {:seon.agent/id :ns/demo.alpha
        :seon.agent/parent :agent/root
        :seon.agent/branch :branch/demo-alpha
        :seon.agent/role :worker
        :seon.agent/scope 'demo.alpha
        :seon.agent/task :task/holistic-demo
        :seon.agent/ready? true
        :seon.agent/goal "Finished specialist"}
       {:seon.agent.message/id m1-id :seon.agent.message/seq 1 :seon.agent.message/from :user
        :seon.agent.message/to :agent/root :seon.agent.message/type :chat :seon.agent.message/body "Inspect compute"}
       {:seon.agent.eval/id ev1-id :seon.agent.eval/agent :agent/root :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
        :seon.agent.eval/handle 'result/e1 :seon.agent.eval/intent "Inspect demo.alpha/compute"
        :seon.agent.eval/code "(demo.alpha/compute 1)" :seon.agent.eval/value "2"
        :seon.agent.eval/reasoning "Checking demo.alpha/helper and demo.alpha/compute"
        :seon.agent.eval/prefix-hash "aaaa1111" :seon.agent.eval/prompt-tokens 1200
        :seon.agent.eval/cache-hit-tokens 900 :seon.agent.eval/cache-miss-tokens 300
        :seon.agent.eval/seen-messages #{m1-id}}
       {:seon.agent.eval/id ev2-id :seon.agent.eval/agent :agent/root :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0
        :seon.agent.eval/handle 'result/e2 :seon.agent.eval/intent "Verify helper"
        :seon.agent.eval/code "(user/source 'demo.alpha/helper)" :seon.agent.eval/value "(defn helper [x] (inc x))"
        :seon.agent.eval/prefix-hash "bbbb2222" :seon.agent.eval/prompt-tokens 1400
        :seon.agent.eval/cache-hit-tokens 400 :seon.agent.eval/cache-miss-tokens 1000}
       ;; Completed specialist with a prompt + evals and NO prose -> MUST NOT show "In flight"!
       {:seon.agent.message/id m2-id :seon.agent.message/seq 2 :seon.agent.message/from :agent/root
        :seon.agent.message/to :ns/demo.alpha :seon.agent.message/type :chat :seon.agent.message/body "Audit demo.alpha"}
       {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ns/demo.alpha :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
        :seon.agent.eval/handle 'result/e1 :seon.agent.eval/intent "Mark branch ready"
        :seon.agent.eval/code "(user/ready!)" :seon.agent.eval/value "{:ok? true}"
        :seon.agent.eval/seen-messages #{m2-id}}
       {:seon.user/id :user/default
        :seon.user/viewing [:seon.agent/id :agent/root]
        :seon.user/view-mode :session
        :seon.user/sidebar-open? true}]})
    ;; Store real packed Matryoshka vectors so live 4th :semantic signal & seon.vector/near run in tests!
    (let [db @conn
          c-eid (:e (first (d/datoms db :avet :seon.code.var/name 'demo.alpha/compute)))
          h-eid (:e (first (d/datoms db :avet :seon.code.var/name 'demo.alpha/helper)))
          t-eid (:e (first (d/datoms db :avet :seon.task/id :task/holistic-demo)))
          d-eid (:e (first (d/datoms db :avet :seon.code.doc/id "docs/holistic.md#overview")))
          vtx (vec/tx-data-for-batch
               [{:eid t-eid :digest "dt"} {:eid c-eid :digest "dc"} {:eid h-eid :digest "dh"} {:eid d-eid :digest "dd"}]
               [(unit-vec 16 0) (unit-vec 16 0) (unit-vec 16 1) (unit-vec 16 2)]
               [12 24 18 15]
               "test-embed+v1")]
      (d/transact conn {:tx-data vtx}))
    {:fix-id fix-id :err-id err-id :ev1-id ev1-id :ev2-id ev2-id}))

(deftest ^{:cost/ticks 90000000} holistic-views-header-honest-session-and-frame-budget-test
  (testing "Holistic :session, :context, :system, :inspect render < 40 ms warm at 1440x900 & 430x900, header has clickable signals/breadcrumb, and completed agents NEVER show fake In flight"
    (with-test-store [{:keys [conn]}]
      (seed-holistic-world! conn)
      (let [db @conn
            hdr-desk (render/hiccup->html (ui/header-unit db {:boot-seq 1 :agent-states {}}))
            _ (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/viewport-w 430 :seon.user/viewport-h 900}]})
            hdr-mob  (render/hiccup->html (ui/header-unit @conn {:boot-seq 1 :agent-states {}}))
            _ (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/viewport-w 1440 :seon.user/viewport-h 900}]})
            db2 @conn
            timed (fn [opts]
                    ;; warm once, measure second
                    (surface/render-surface db2 db2 (assoc opts :conn conn))
                    (let [t0 (System/nanoTime)
                          html (render/hiccup->html (surface/render-surface db2 db2 (assoc opts :conn conn)))
                          ms (/ (double (- (System/nanoTime) t0)) 1e6)]
                      {:html html :ms ms}))
            sess-d (timed {:viewing-id :agent/root :view-mode :session :viewport-w 1440 :viewport-h 900})
            sess-m (timed {:viewing-id :agent/root :view-mode :session :viewport-w 430  :viewport-h 900})
            ctx-d  (timed {:viewing-id :agent/root :view-mode :context :viewport-w 1440 :viewport-h 900
                           :walk-summary [{:id "seon-header" :reads-count 4 :hit? true :ms 0.0}
                                          {:id "seon-view-unit" :reads-count 18 :hit? false :ms 6.2}]})
            sys-d  (timed {:viewing-id :agent/root :view-mode :system  :viewport-w 1440 :viewport-h 900
                           :semantic-search {:ok? true :query "compute"
                                             :results [{:eid 1 :score 0.91 :kind :var
                                                        :ref [:seon.code.var/name 'demo.alpha/compute]
                                                        :label "demo.alpha/compute"}]}})
            insp-d (timed {:viewing-id :agent/root :inspect-ref [:seon.code.var/name 'demo.alpha/compute]
                           :inspect-trail [[:seon.agent/id :agent/root]]
                           :inspect-forward [[:seon.task/id :task/holistic-demo]]
                           :viewport-w 1440 :viewport-h 900})
            done-ag (timed {:viewing-id :ns/demo.alpha :view-mode :session :viewport-w 1440 :viewport-h 900})
            live-ag (timed {:viewing-id :ns/demo.alpha :view-mode :session :viewport-w 1440 :viewport-h 900
                            :agent-states {:ns/demo.alpha :active}
                            :live-stream {:status :token :phase :writing :started-ms 1000 :last-ms 2000 :step-chars 160 :chars 160 :text "Streaming"}})]
        ;; 1. Header navigation + clickable signal pills + clickable agent breadcrumb
        (do
        (doseq [needle ["Explore" "Explain" "Expert" "←" "→" "sig-pill-btn" "data-preview-ref"
                        "data-canonical-url=" "data-expert-mode=\"false\"" "is-everyday"
                        "interestfor=\"seon-hovercard\"" "seon.web%2Finspect-entity%21"
                        "seon.web%2Fset-explain-lens%21" "seon.web%2Ftoggle-expert-mode%21"]]
          (is (str/includes? hdr-desk needle) (str "Everyday desktop header missing " needle)))
        (is (not (str/includes? hdr-desk "Pressure")) "Everyday mode should tuck 5-signal strip behind Expert toggle")
        (let [db-exp (:db-after (d/with db2 {:tx-data [{:seon.user/id :user/default :seon.user/expert-mode? true}]}))
              hdr-exp (render/hiccup->html (ui/header-unit db-exp {:boot-seq 1 :agent-states {}}))]
          (is (str/includes? hdr-exp "data-expert-mode=\"true\""))
          (doseq [needle ["Flow" "Pressure" "Context" "Proof" "Health"]]
            (is (str/includes? hdr-exp needle) (str "Expert desktop header missing " needle)))))
        (is (str/includes? hdr-mob "is-narrow"))
        (is (not (str/includes? hdr-mob "sig-strip")))
        (is (str/includes? hdr-mob "Explore"))
        (is (str/includes? hdr-mob "Explain"))

        ;; 2. Strict < 40 ms warm frame budget across ALL 4 holistic views
        (doseq [[lbl r] [[:session sess-d] [:session-mobile sess-m] [:context ctx-d] [:system sys-d] [:inspect insp-d]]]
          (is (< (:ms r) 40.0) (str lbl " exceeded 40 ms warm budget: " (:ms r) " ms")))

        ;; 3. Session: clean 2-col desktop -> stacked mobile, NO duplicate signals box, grouped Active Context + provenance
        (is (str/includes? (:html sess-d) "workspace-grid"))
        (is (not (str/includes? (:html sess-d) "surface-stack")))
        (is (str/includes? (:html sess-m) "surface-stack"))
        (is (not (str/includes? (:html sess-d) "sig-panel")) "Session must NOT duplicate signals panel")
        (is (str/includes? (:html sess-d) "Active Context"))
        (is (str/includes? (:html sess-d) "Tasks &amp; Edits"))
        (is (str/includes? (:html sess-d) "Inspect variations"))
        (is (str/includes? (:html sess-d) "cos "))

        ;; 4. Completed / :ready? agent NEVER shows fake "In flight" banner; active agent DOES!
        (is (not (str/includes? (:html done-ag) "id=\"cv-pending\"")))
        (is (not (str/includes? (:html done-ag) "In flight")))
        (is (str/includes? (:html done-ag) "id=\"cv-work-summary\""))
        (is (str/includes? (:html done-ag) "Mark branch ready"))
        (is (str/includes? (:html live-ag) "id=\"cv-pending\""))
        (is (str/includes? (:html live-ag) "Streaming"))

        ;; 5. Context & Cache: KV-cache anatomy, prefix busts, walk cache, lab controls, rules + scoreboard
        (doseq [needle ["ctx-kv-cache-unit" "kv-bar" "Prefix-Bust Explainer" "bbbb2222"
                        "UI Incremental Walk Cache" "ctx-candidates-unit" "Model Wire" "Browser UI"
                        "Breadth-First" "Depth-First" "Semantic-Heavy" "toggle-lab-rule"
                        "dbg-rule-trace" "Rule Firing Trace" "Live @seon.ctx/ctx Atom Value"
                        "boost=0.15" "Variations (" "demo.alpha/compute"]]
          (is (str/includes? (:html ctx-d) needle) (str ":context missing " needle)))

        ;; 6. System: 5 clickable signal cards, vector status + search results, swarm tree, tasks DAG, branches
        (doseq [needle ["sys-focal-unit" "Seon Runtime" "sys-signals-unit" "sig-cell-btn"
                        "sys-vectors-unit" "4 indexed" "semantic-search-form" "cos-bar"
                        "sys-entities-unit" "Active Agents" "Open Tasks" "Holistic Demo Task"]]
          (is (str/includes? (:html sys-d) needle) (str ":system missing " needle)))
        (doseq [deleted ["dbg-branches-unit" "dbg-schema-unit" "dbg-drill-unit"]]
          (is (not (str/includes? (:html sys-d) deleted)) (str "Hand-coded dashboard should be deleted: " deleted)))

        ;; 7. Fractal Inspector: Back/Forward + Breadcrumbs + Variations WINNER + Neighborhood
        (doseq [needle ["insp-nav-bar" "← Back" "→ Forward" "insp-variations-unit" "WINNER"
                        "Browser HTML" "Model Text" "insp-neighborhood-unit"
                        "Outbound &amp; Direct Refs" "Inbound &amp; Referencers" "Open →" "Explain / Variations"]]
          (is (str/includes? (:html insp-d) needle) (str ":inspect missing " needle)))))))

(deftest ^{:cost/ticks 90000000} entities-hyperlinks-plain-text-params-and-all-types-inspector-test
  (testing "Every entity type emits clickable inspect-entity! links, clean plain-text hiccup->text, honors rule params, and renders in fractal Inspector"
    (with-test-store [{:keys [conn]}]
      (let [{:keys [fix-id err-id ev1-id]} (seed-holistic-world! conn)
            db @conn]
        ;; 1. Verify hiccup->text keeps .ent-link text while stripping chrome buttons
        (is (= "demo.alpha/compute"
               (render/hiccup->text [:div [:button {:type "button" :class "ent-link mono"} "demo.alpha/compute"]
                                     [:button {:type "button" :class "btn sm"} "Test Branch"]])))

        ;; 2. Verify rule-emitted params (:highlight-terms, :expand-section, :show-callers?, :token-budget)
        (let [raw-v (d/pull db '[*] [:seon.code.var/name 'demo.alpha/compute])
              var-ent (cond-> raw-v
                        (sequential? (:seon.code.var/refs raw-v)) (update :seon.code.var/refs set)
                        (sequential? (:seon.code.var/calls raw-v)) (update :seon.code.var/calls set))
              v-h (:seon.ui/hiccup (rent/render-var-full {:seon.code.var/entity var-ent
                                                          :seon.code.form/src "(defn compute [x] (helper x))"
                                                          :seon.ui/highlight-terms ["helper"]
                                                          :seon.ui/expand-section :source
                                                          :seon.ui/show-callers? true
                                                          :seon.ui/callers ['demo.alpha/helper]}))
              v-html (render/hiccup->html v-h)
              v-txt  (render/hiccup->text v-h)
              tight-txt (render/hiccup->text
                         (:seon.ui/hiccup (rent/render-doc-full
                                           {:seon.code.doc/entity {:seon.code.doc/id "d#1" :seon.code.doc/path "d.md"
                                                                   :seon.code.doc/order 1 :seon.code.doc/heading "Long"
                                                                   :seon.code.doc/body (apply str (repeat 200 "abcdefghij "))}
                                            :seon.view/token-budget 200})))
              wide-txt (render/hiccup->text
                        (:seon.ui/hiccup (rent/render-doc-full
                                          {:seon.code.doc/entity {:seon.code.doc/id "d#1" :seon.code.doc/path "d.md"
                                                                  :seon.code.doc/order 1 :seon.code.doc/heading "Long"
                                                                  :seon.code.doc/body (apply str (repeat 200 "abcdefghij "))}
                                           :seon.view/token-budget 4800})))]
          (is (str/includes? v-html "hl-term"))
          (is (str/includes? v-html "is-expanded"))
          (is (str/includes? v-html "callers:"))
          (is (str/includes? v-html "seon.web%2Finspect-entity%21"))
          (is (str/includes? v-txt "demo.alpha/compute"))
          (is (str/includes? v-txt "callers:"))
          (is (< (count tight-txt) (count wide-txt)) "Tight :seon.view/token-budget must clip body shorter"))

        ;; 3. Every entity kind renders cleanly in fractal Inspector with variations + clickable neighbors
        (doseq [ref [[:seon.code.var/name 'demo.alpha/compute]
                     [:seon.code.ns/name 'demo.alpha]
                     [:seon.task/id :task/holistic-demo]
                     [:seon.rule/id :rule.ctx/holistic-demo]
                     [:seon.schema/key :seon.task/entity]
                     [:seon.agent/id :agent/root]
                     [:seon.agent.eval/id ev1-id]
                     [:seon.error/id err-id]
                     [:seon.fix/id fix-id]
                     [:seon.test/digest "deadbeef12345678"]
                     [:seon.code.doc/id "docs/holistic.md#overview"]
                     [:seon.branch/name :main]
                     [:seon.signal/kind :flow]]]
          (let [w (sinsp/entity-world-candidates db db ref)
                html (render/hiccup->html
                      (sinsp/render-inspector-body {:main-db db :branch-db db :viewing-id :agent/root
                                                    :agent {:seon.agent/id :agent/root}
                                                    :inspect-ref ref :inspect-trail [] :inspect-forward []
                                                    :lab-prefs {} :slot-budget {:avail-w 1100 :area-budget 1400000}}))]
            (is (some? (:focal-item w)) (str "Missing focal item for " ref))
            (is (str/includes? html "id=\"seon-inspector-unit\"") (str "Inspector failed for " ref))
            (is (not (str/includes? html "Entity not found")) (str "Entity not found for " ref))))))))

(deftest ^{:cost/ticks 90000000} callable-actions-navigation-stack-and-lab-prefs-e2e-test
  (testing "All new callable actions work through actions/invoke-call! and transactor :show-user Back/Forward/Trail/Lab-Prefs stack"
    (with-test-store [{:keys [conn]}]
      (seed-holistic-world! conn)
      (let [st {:conn conn}
            apply-call! (fn [call-form & [fields]]
                          (let [res (actions/invoke-call! st (cond-> {:call-form call-form} fields (assoc :form-fields fields)))]
                            (is (:ok? res) (str "invoke-call! failed for " call-form ": " (pr-str res)))
                            (when-let [req (get-in res [:async :req])]
                              (when (= :show-user (:op req))
                                (let [{:keys [tx-data]} (#'txr/show-user-tx @conn req)]
                                  (d/transact conn {:tx-data tx-data}))))
                            res))
            r1 [:seon.code.var/name 'demo.alpha/compute]
            r2 [:seon.task/id :task/holistic-demo]
            r3 [:seon.code.ns/name 'demo.alpha]]
        ;; Push r1 -> r2 -> r3
        (apply-call! (list 'seon.web/inspect-entity! r1))
        (is (= r1 (:inspect-ref (rdata/user-view-state @conn))))
        (is (= [] (:inspect-trail (rdata/user-view-state @conn))))
        (apply-call! (list 'seon.web/inspect-entity! r2))
        (apply-call! (list 'seon.web/inspect-entity! r3))
        (let [vs (rdata/user-view-state @conn)]
          (is (= r3 (:inspect-ref vs)))
          (is (= [r1 r2] (:inspect-trail vs)))
          (is (= [] (:inspect-forward vs))))

        ;; Back -> r2 (forward gets [r3]), Back -> r1 (forward gets [r3 r2])
        (apply-call! '(seon.web/inspect-back!))
        (let [vs (rdata/user-view-state @conn)]
          (is (= r2 (:inspect-ref vs)))
          (is (= [r1] (:inspect-trail vs)))
          (is (= [r3] (:inspect-forward vs))))
        (apply-call! '(seon.web/inspect-back!))
        (let [vs (rdata/user-view-state @conn)]
          (is (= r1 (:inspect-ref vs)))
          (is (= [] (:inspect-trail vs)))
          (is (= [r3 r2] (:inspect-forward vs))))

        ;; Forward -> r2, Forward -> r3
        (apply-call! '(seon.web/inspect-forward!))
        (is (= r2 (:inspect-ref (rdata/user-view-state @conn))))
        (apply-call! '(seon.web/inspect-forward!))
        (is (= r3 (:inspect-ref (rdata/user-view-state @conn))))
        (is (= [r1 r2] (:inspect-trail (rdata/user-view-state @conn))))
        (is (= [] (:inspect-forward (rdata/user-view-state @conn))))

        ;; Breadcrumb jump to index 0 (r1) preserves [r3 r2] on forward stack!
        (apply-call! '(seon.web/jump-inspect-trail! 0))
        (let [vs (rdata/user-view-state @conn)]
          (is (= r1 (:inspect-ref vs)))
          (is (= [] (:inspect-trail vs)))
          (is (= [r3 r2] (:inspect-forward vs))))

        ;; Close clears ref, trail, and forward stack
        (apply-call! '(seon.web/inspect-close!))
        (let [vs (rdata/user-view-state @conn)]
          (is (nil? (:inspect-ref vs)))
          (is (= [] (:inspect-trail vs)))
          (is (= [] (:inspect-forward vs))))

        ;; Lab prefs: update, weights form, toggle rule on/off, reset
        (apply-call! '(seon.web/update-lab-prefs! {:token-budget 1000 :strategy :depth-first :avail-w 390}))
        (let [lp (rdata/normalize-lab-prefs (:lab-prefs (rdata/user-view-state @conn)))]
          (is (= 1000 (:token-budget lp)))
          (is (= :depth-first (:strategy lp)))
          (is (= 390 (:avail-w lp))))
        (apply-call! '(seon.web/update-lab-weights-form!) {:urgency "0.2" :recency "1.4" :convergence "0.3" :semantic "1.8"})
        (is (= 1.8 (get-in (rdata/normalize-lab-prefs (:lab-prefs (rdata/user-view-state @conn))) [:weights :semantic])))
        (apply-call! '(seon.web/toggle-lab-rule! :rule.ctx/holistic-demo))
        (is (contains? (:disabled-rules (rdata/normalize-lab-prefs (:lab-prefs (rdata/user-view-state @conn)))) :rule.ctx/holistic-demo))
        ;; Verify toggling rule off actually removes it from Context Lab candidates!
        (let [ctx-dis (render/hiccup->html (surface/render-surface @conn @conn {:conn conn :viewing-id :agent/root :view-mode :context}))]
          (is (str/includes? ctx-dis "Disabled")))
        (apply-call! '(seon.web/toggle-lab-rule! :rule.ctx/holistic-demo))
        (is (not (contains? (:disabled-rules (rdata/normalize-lab-prefs (:lab-prefs (rdata/user-view-state @conn)))) :rule.ctx/holistic-demo)))
        (apply-call! '(seon.web/reset-lab-prefs!))
        (is (= 2400 (:token-budget (rdata/normalize-lab-prefs (:lab-prefs (rdata/user-view-state @conn))))))

        ;; Semantic search & vector status callable actions
        (let [sr (apply-call! '(seon.web/semantic-search-form!) {:query "compute" :of ":seon.code.var/name"})
              vr (apply-call! '(seon.web/refresh-vector-status!))]
          (is (= :embed-search (get-in sr [:async :req :op])))
          (is (= :seon.code.var/name (get-in sr [:async :req :opts :of])))
          (is (= :vector-status (get-in vr [:async :req :op]))))

        ;; View transition sig triggers on :inspect-ref & :lab-prefs changes, NEVER on :stream
        (is (true? (rwalk/discrete-view-transition? [:session :agent/root nil 1440 nil {}]
                                                    [:session :agent/root nil 1440 r1 {}] nil)))
        (is (true? (rwalk/discrete-view-transition? [:context :agent/root nil 1440 nil {}]
                                                    [:context :agent/root nil 1440 nil {:token-budget 1000}] nil)))
        (is (false? (rwalk/discrete-view-transition? [:session :agent/root nil 1440 nil {}]
                                                     [:session :agent/root nil 1440 r1 {}] :stream)))

        ;; CSS contains holistic rules & zero layout @media(max-width:1080px) hack
        (let [css (styles/full-css)]
          (is (not (str/includes? css "@media(max-width:1080px)")))
          (doseq [sel [".ent-link{" ".kv-bar{" ".lab-chip.active{" ".var-card.is-winner{" ".insp-bar{" ".cos-bar{"]]
            (is (str/includes? css sel) (str "Missing CSS rule " sel))))

        ;; Seed=42 generative checks on new pure helpers
        (doseq [sym '[seon.render.data/canonical-view-mode
                      seon.render.data/normalize-inspect-ref
                      seon.render.data/entity-lookup-ref
                      seon.render.data/entity-short-label
                      seon.render.data/normalize-lab-prefs
                      seon.render/hiccup->text]]
          (let [res (schema/stress-var @conn sym {:samples 100 :seed 42})]
            (is (:ok? res) (str sym " failed seed=42 generative check: " (pr-str res)))))))))

(deftest ^{:cost/ticks 120000000} one-render-system-preview-explain-explore-and-fork-crash-proof-test
  (testing "System entity, GET /preview hovercards, tight <=8 Session rail, Agent+Entity Explain, user/explore!, and ForkParent :advance-image survival"
    (with-test-store [{:keys [conn]}]
      (seed-holistic-world! conn)
      (d/transact conn {:tx-data [{:seon.system/id :root :seon.system/title "Seon Runtime"
                                   :seon.system/description "Unified Seon OS."}]})
      (let [db @conn
            sys-ent (d/pull db '[*] [:seon.system/id :root])
            sys-proj (surface/project-entity-view db db [:seon.system/id :root] {:target :html :avail-w 1100})
            req-ctx {:ui-ch (a/chan 4) :server-in-ch (a/chan 4) :conn conn}
            lnk (rent/render-system-chip {:seon.system/entity sys-ent})
            lnk-html (render/hiccup->html (:seon.ui/hiccup lnk))
            lnk-txt (render/hiccup->text (:seon.ui/hiccup lnk))
            shell (#'web/page-shell (render/empty-walk) db)
            css (styles/full-css)]
        (is (= :root (:seon.system/id sys-ent)))
        (is (contains? '#{seon.render.entities/render-system-card seon.render.entities/render-system-chip}
                       (get-in sys-proj [:focal-fit :chosen-fn])))
        (is (pos? (count (:selected sys-proj))))
        (doseq [[ref want-fn] [[[:seon.system/id :root] "render-system-chip"]
                               [[:seon.code.var/name 'demo.alpha/compute] "render-var-"]
                               [[:seon.task/id :task/holistic-demo] "render-task-"]
                               [[:seon.rule/id :rule.ctx/holistic-demo] "render-rule-"]
                               [[:seon.agent/id :agent/root] "render-agent-"]]]
          (let [qs (str "ref=" (web/url-enc (pr-str ref)))
                _ (web/handle-http-request! req-ctx {:request-method :get :uri "/preview" :query-string qs})
                t0 (System/nanoTime)
                resp (web/handle-http-request! req-ctx {:request-method :get :uri "/preview" :query-string qs})
                ms (/ (double (- (System/nanoTime) t0)) 1e6)]
            (is (= 200 (:status resp)) (str "/preview failed for " ref))
            (is (= "no-store" (get-in resp [:headers "Cache-Control"])))
            (is (str/includes? (:body resp) want-fn) (str "/preview missing " want-fn " for " ref))
            (is (str/includes? (:body resp) "Click for full view"))
            (is (< ms 20.0) (str "/preview took " ms " ms"))))
        (is (= 400 (:status (web/handle-http-request! req-ctx {:request-method :get :uri "/preview" :query-string ""}))))
        (is (str/includes? lnk-html "data-preview-ref=\"[:seon.system/id :root]\""))
        (is (str/includes? lnk-html "interestfor=\"seon-hovercard\""))
        (is (not (str/includes? lnk-txt "interestfor")))
        (is (str/includes? lnk-txt "Seon Runtime"))
        (is (str/includes? shell "id=\"seon-hovercard\" popover=\"hint\" role=\"tooltip\""))
        (is (str/includes? wcss/hovercard-js "/preview?ref="))
        (is (str/includes? css "@supports (position-area: block-end){.hovercard{inset:auto;"))
        (is (str/includes? css "@starting-style{.hovercard:popover-open"))
        (is (str/includes? css "transition-behavior:allow-discrete"))
        (let [extras (mapv (fn [i] {:seon.task/id (keyword "task" (str "noise-" i))
                                    :seon.task/title (str "Noise " i) :seon.task/done? false}) (range 14))
              _ (d/transact conn {:tx-data (conj extras {:seon.agent/id :agent/root
                                                         :seon.agent/pinned (mapv #(vector :seon.task/id (:seon.task/id %)) extras)})})
              sm (surface/build-surface-model @conn @conn {:conn conn :viewing-id :agent/root :view-mode :session :viewport-w 1440 :viewport-h 900})
              sh (render/hiccup->html (surface/render-surface @conn @conn {:conn conn :viewing-id :agent/root :view-mode :session :viewport-w 1440 :viewport-h 900}))]
          (is (<= (count (:entities sm)) 8) (str "Session rail exceeded 8 items: " (count (:entities sm))))
          (is (str/includes? sh "Explain selection ("))
          (is (not (str/includes? sh "sig-panel"))))
        (let [r1 (actions/invoke-call! {:conn conn} {:call-form '(seon.web/set-explain-lens! true [:seon.code.var/name demo.alpha/compute])})
              _ (is (:ok? r1))
              _ (d/transact conn {:tx-data (:tx-data (#'txr/show-user-tx @conn (get-in r1 [:async :req])))})
              ev-h (render/hiccup->html (surface/render-surface @conn @conn {:conn conn :viewing-id :agent/root :view-mode :context :inspect-ref [:seon.code.var/name 'demo.alpha/compute] :walk-summary [{:id "seon-view-unit" :reads-count 9 :hit? true :ms 0.0}]}))
              es-h (render/hiccup->html (surface/render-surface @conn @conn {:conn conn :viewing-id :agent/root :view-mode :context :inspect-ref [:seon.system/id :root]}))
              ea-h (render/hiccup->html (surface/render-surface @conn @conn {:conn conn :viewing-id :agent/root :view-mode :context :inspect-ref nil}))]
          (is (str/includes? ev-h "id=\"seon-entity-explain-unit\""))
          (is (str/includes? ev-h "All Renderer Variations"))
          (is (str/includes? ev-h "Neighborhood Selection"))
          (is (str/includes? ev-h "UI Incremental Walk Cache"))
          (is (str/includes? es-h "Seon Runtime"))
          (is (str/includes? ea-h "ctx-kv-cache-unit")))
        (binding [fcalls/*agent-ctx* {:conn conn :agent-id :agent/root :branch :main}]
          (let [er (fhelpers/explore! :root)
                ev (fhelpers/explore! 'demo.alpha/compute)
                et (fhelpers/explore! [:seon.task/id :task/holistic-demo])
                erl (fhelpers/explore! [:seon.rule/id :rule.ctx/holistic-demo])
                etight (fhelpers/explore! 'demo.alpha/compute {:token-budget 350})]
            (is (str/includes? er "Seon Runtime"))
            (is (str/includes? ev "demo.alpha/compute"))
            (is (str/includes? et "Holistic Demo Task"))
            (is (str/includes? erl ":rule.ctx/holistic-demo"))
            (is (<= (count etight) (count ev)))))
        (let [ev-ch (a/chan 4)
              c1 (fork/advance-image-step! ev-ch {:op :advance-image :commit "bad-commit" :upserts [{:seon.code.form/key "broken.ns/bad" :seon.code.form/ns 'broken.ns :seon.code.form/src "(ns broken.ns) (defn boom [] definitely-not-defined-xyz)"}]} "prev-good")
              e-bad (a/<!! ev-ch)
              c2 (fork/advance-image-step! ev-ch {:op :advance-image :commit "next-good" :upserts []} c1)
              e-ok (a/<!! ev-ch)]
          (is (= "prev-good" c1))
          (is (= :image-advance-error (:ev e-bad)))
          (is (= "bad-commit" (:commit e-bad)))
          (is (string? (:error e-bad)))
          (is (= "next-good" c2))
          (is (= :image-advanced (:ev e-ok))))))))