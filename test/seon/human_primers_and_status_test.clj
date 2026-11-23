(ns seon.human-primers-and-status-test
  "End-to-end proofs for:
   1. Blank `+ NEW AGENT` spawn fix (no synthetic task, `:auto-merge? false`, pinned `:root`) & slim system prompt.
   2. All 7 JIT Datalog primer rules + `:rule.ctx/live-status-card` firing ONLY on matching facts & stopping after reply.
   3. `:seon.ui.status/*` upsert, pure renderers, SVG sanitization, `seed=42` generative checks, and `<user-waiting>` tail nudge.
   4. Blank `@ tx ` fix under `d/as-of`, live status banner in Session column, and Everyday (default) vs Expert mode."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.graph :as graph]
            [seon.code.parse :as parse]
            [seon.error :as serr]
            [seon.fork.calls :as fcalls]
            [seon.model :as model]
            [seon.render :as render]
            [seon.render.fit :as fit]
            [seon.render.status :as rstatus]
            [seon.render.surface :as surface]
            [seon.render.tails :as rtails]
            [seon.render.wire :as rwire]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor :as txr]))

(defn- seed-primers-doc! [conn]
  (let [md (str "# Seon Agent Primers\n\nIntro.\n\n"
                "## Messaging\nReply directly to greetings with 0 tool calls.\n\n"
                "## Live Progress\nCall user/set-status! during multi-step work.\n\n"
                "## Data Modeling\nModel user data cleanly in Datahike.\n\n"
                "## Editing\nSearch first and use CST structural editors.\n\n"
                "## Contracts\nAttach Malli schemas and cost budgets.\n\n"
                "## Delegation\nUse user/ask! and user/await!.\n\n"
                "## Proofs\nRun user/run-tests! and user/health.\n")
        secs (parse/parse-md-sections "docs/primers.md" md)]
    (d/transact conn {:tx-data secs})
    secs))

(defn- fired-rule-ids
([main-db branch-db aid] (fired-rule-ids main-db branch-db aid :wire))
([main-db branch-db aid target]
 (let [rs (filterv #(= :context (:seon.rule/category %)) (rseed/starter-rules))
       sr (rules/step-rules nil {:main main-db :branch branch-db :code branch-db :task branch-db}
                            {:agent-id aid :base-wm (rules/empty-wm)} rs nil target)]
   (into #{} (keep (fn [[rid run]] (when (pos? (long (:fired run 0))) rid))) (:runs sr)))))

(deftest ^{:cost/ticks 90000000} blank-agent-spawn-and-slim-system-prompt-test
  (testing "Slim default-system-prompt and blank + NEW AGENT spawn without synthetic task"
    (is (< (count model/default-system-prompt) 450))
    (is (< (count (str/split model/default-system-prompt #"\s+")) 75))
    (is (str/includes? model/default-system-prompt "NO tool calls"))
    (is (str/includes? model/default-system-prompt "<session kind=\"example\">"))
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:seon.system/id :root :seon.system/title "Seon Runtime"}
                                  {:seon.task/id :template/discover-and-plan
                                   :seon.task/title "Discover & Plan"
                                   :seon.task/template? true
                                   :seon.task/done? false}
                                  {:seon.task/id :task/open-demo
                                   :seon.task/title "Open Demo Task"
                                   :seon.task/scope 'demo.sc
                                   :seon.task/done? false}]})
      (let [st {:conn conn :msg-seq 0 :writes 0 :commit-git? false}
          ;; 1. Blank goal, no task, no scope -> NO synthetic task, auto-merge? unset (default), pinned :root
          _ (#'txr/handle-spawn-agent! st {:op :spawn-agent :id :agent/blank-chat :goal ""} (d/commit-id @conn))
          ag-blank (d/pull @conn '[*] [:seon.agent/id :agent/blank-chat])
          ;; 2a. Explicit :auto-merge? false is honored
          _ (#'txr/handle-spawn-agent! st {:op :spawn-agent :id :agent/blank-no-merge :goal "" :auto-merge? false} (d/commit-id @conn))
          ag-bf (d/pull @conn '[*] [:seon.agent/id :agent/blank-no-merge])
          ;; 2b. Blank goal with explicit :auto-merge? true preserves true
          _ (#'txr/handle-spawn-agent! st {:op :spawn-agent :id :agent/blank-merge :goal "   " :auto-merge? true} (d/commit-id @conn))
          ag-bm (d/pull @conn '[*] [:seon.agent/id :agent/blank-merge])
          ;; 3. Non-blank goal still creates :template/discover-and-plan subtask
          _ (#'txr/handle-spawn-agent! st {:op :spawn-agent :id :agent/with-goal :goal "Audit parser"} (d/commit-id @conn))
          ag-goal (d/pull @conn '[*] [:seon.agent/id :agent/with-goal])
          ;; 4. Explicit scope still matches open task in scope
          _ (#'txr/handle-spawn-agent! st {:op :spawn-agent :id :agent/with-scope :goal "" :scope 'demo.sc} (d/commit-id @conn))
          ag-scope (d/pull @conn '[*] [:seon.agent/id :agent/with-scope])]
      (is (nil? (:seon.agent/task ag-blank)))
      (is (nil? (:seon.agent/auto-merge? ag-blank)))
      (is (some #{[:seon.system/id :root]} (:seon.agent/pinned ag-blank)))
      (is (false? (:seon.agent/auto-merge? ag-bf)))
      (is (nil? (:seon.agent/task ag-bf)))
      (is (true? (:seon.agent/auto-merge? ag-bm)))
      (is (nil? (:seon.agent/task ag-bm)))
      (is (qualified-keyword? (:seon.agent/task ag-goal)) "Non-blank goal must preserve discover-and-plan subtask")
      (is (= :task/open-demo (:seon.agent/task ag-scope)) "Explicit scope must preserve best-task-for-scope matching")))))

(deftest ^{:cost/ticks 120000000} jit-primer-rules-firing-and-reply-suppression-test
  (testing "All 7 JIT Datalog primer rules + live-status-card fire ONLY on matching facts and stop after reply"
    (let [all-rs (rseed/starter-rules)
          by-id (into {} (map (juxt :seon.rule/id identity)) all-rs)]
      (is (= 0 (count (filter :seon.rule/when all-rs))))
      (doseq [rid [:rule.ctx/primer-messaging :rule.ctx/primer-live-progress
                   :rule.ctx/primer-data-modeling :rule.ctx/primer-editing
                   :rule.ctx/primer-contracts :rule.ctx/primer-delegation
                   :rule.ctx/primer-proofs :rule.ctx/live-status-card]]
        (is (some? (get-in by-id [rid :seon.rule/query])) (str "Missing native :seon.rule/query for " rid))))

    (with-test-store [{:keys [conn]}]
      (seed-primers-doc! conn)
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/p.clj"
                                                      "(ns demo.p)\n(defn f {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] (inc x))\n" false)})
      (d/transact conn {:tx-data [{:seon.agent/id :agent/idle :seon.agent/branch :main :seon.agent/role :worker}
                                  {:seon.agent/id :agent/coord :seon.agent/branch :main :seon.agent/role :coordinator}
                                  {:seon.agent/id :agent/editor :seon.agent/branch :branch/ed :seon.agent/role :worker}]})
      ;; 1. Idle worker fires ZERO primers
      (let [f0 (fired-rule-ids @conn @conn :agent/idle)]
        (doseq [rid [:rule.ctx/primer-messaging :rule.ctx/primer-live-progress :rule.ctx/primer-data-modeling
                     :rule.ctx/primer-editing :rule.ctx/primer-contracts :rule.ctx/primer-delegation
                     :rule.ctx/primer-proofs :rule.ctx/live-status-card]]
          (is (not (contains? f0 rid)) (str "Idle worker unexpectedly fired " rid))))

      ;; 2. Coordinator fires :rule.ctx/primer-delegation
      (is (contains? (fired-rule-ids @conn @conn :agent/coord) :rule.ctx/primer-delegation))

      ;; 3. Unanswered :from :user :type :chat -> messaging + data-modeling (taskless), NOT live-progress yet
      (let [m1 (random-uuid)]
        (d/transact conn {:tx-data [{:seon.agent.message/id m1 :seon.agent.message/seq 1
                                     :seon.agent.message/from :user :seon.agent.message/to :agent/idle
                                     :seon.agent.message/type :chat :seon.agent.message/body "Hello"}]})
        (let [f1 (fired-rule-ids @conn @conn :agent/idle)]
          (is (contains? f1 :rule.ctx/primer-messaging))
          (is (contains? f1 :rule.ctx/primer-data-modeling))
          (is (not (contains? f1 :rule.ctx/primer-live-progress))))

        ;; 4. Add 2 tool evals -> primer-live-progress fires
        (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/idle
                                     :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
                                     :seon.agent.eval/code "(+ 1 1)" :seon.agent.eval/seen-messages #{m1}}
                                    {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/idle
                                     :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0
                                     :seon.agent.eval/code "(+ 2 2)"}]})
        (let [f2 (fired-rule-ids @conn @conn :agent/idle)]
          (is (contains? f2 :rule.ctx/primer-messaging))
          (is (contains? f2 :rule.ctx/primer-live-progress)))

        ;; 5. Plain-prose reply stops messaging, live-progress, and data-modeling
        (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/idle
                                     :seon.agent.eval/turn 3 :seon.agent.eval/call-index 0
                                     :seon.agent.eval/code "" :seon.agent.eval/thought "Hi there!"}]})
        (let [f3 (fired-rule-ids @conn @conn :agent/idle)]
          (is (not (contains? f3 :rule.ctx/primer-messaging)))
          (is (not (contains? f3 :rule.ctx/primer-live-progress)))
          (is (not (contains? f3 :rule.ctx/primer-data-modeling)))))

      ;; 6. Second user msg answered via outbound message from agent -> stops firing
      (let [m2 (random-uuid)]
        (d/transact conn {:tx-data [{:seon.agent.message/id m2 :seon.agent.message/seq 2
                                     :seon.agent.message/from :user :seon.agent.message/to :agent/idle
                                     :seon.agent.message/type :chat :seon.agent.message/body "Ping"}]})
        (is (contains? (fired-rule-ids @conn @conn :agent/idle) :rule.ctx/primer-messaging))
        (d/transact conn {:tx-data [{:seon.agent.message/id (random-uuid) :seon.agent.message/seq 3
                                     :seon.agent.message/from :agent/idle :seon.agent.message/to :user
                                     :seon.agent.message/type :chat :seon.agent.message/body "Pong"}]})
        (is (not (contains? (fired-rule-ids @conn @conn :agent/idle) :rule.ctx/primer-messaging))))

      ;; 7. Open task -> primer-editing; open :missing-contract error -> primer-contracts; failing test -> primer-proofs
      (d/transact conn {:tx-data [{:seon.task/id :task/edit-me :seon.task/title "Edit" :seon.task/done? false}
                                  {:seon.agent/id :agent/idle :seon.agent/task :task/edit-me}
                                  (serr/error-tx {:kind :missing-contract :severity :error :source :health
                                                  :agent :agent/idle :message "Missing :malli/schema"})
                                  {:seon.agent.call/id (random-uuid) :seon.agent.call/caller :agent/coord
                                   :seon.agent.call/callee :agent/idle :seon.agent.call/status :pending
                                   :seon.agent.call/prompt "Check"}]})
      (let [f4 (fired-rule-ids @conn @conn :agent/idle)]
        (is (contains? f4 :rule.ctx/primer-editing))
        (is (contains? f4 :rule.ctx/primer-contracts))
        (is (contains? f4 :rule.ctx/primer-delegation))
        (is (not (contains? f4 :rule.ctx/primer-proofs))))
      ;; Failing test verdict triggers :rule.ctx/primer-proofs via :rule.ctx/failing-reach-tests
      (let [veid (:e (first (d/datoms @conn :avet :seon.code.var/name 'demo.p/f)))]
        (d/transact conn {:tx-data [{:seon.test/digest "failtest0001" :seon.test/var 'demo.p/f
                                     :seon.test/var-ref veid :seon.test/kind :deftest :seon.test/pass? false
                                     :seon.test/ms 1.0 :seon.test/report "boom"}]})
        (is (contains? (fired-rule-ids @conn @conn :agent/idle) :rule.ctx/primer-proofs)))

      ;; 8. Branch edit -> edited-var-on-branch -> primer-editing + primer-contracts + primer-proofs
      (store/branch! conn :main :branch/ed)
      (let [bconn (d/connect (assoc (:config @conn) :branch :branch/ed))]
        (try
          (d/transact bconn {:tx-data (code/file-import-tx @bconn "src/demo/p.clj"
                                                           "(ns demo.p)\n(defn f {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] (+ x 2))\n" false)})
          (let [fb (fired-rule-ids @conn @bconn :agent/editor :wire)
              fb-canvas (fired-rule-ids @conn @bconn :agent/editor :canvas)]
          (is (contains? fb :rule.ctx/primer-editing))
          (is (contains? fb :rule.ctx/primer-contracts))
          (is (contains? fb :rule.ctx/primer-proofs))
          ;; Primer rules are :target :wire so browser :canvas/:debug skips them
          (is (not-any? #{:rule.ctx/primer-messaging :rule.ctx/primer-live-progress
                          :rule.ctx/primer-data-modeling :rule.ctx/primer-editing
                          :rule.ctx/primer-contracts :rule.ctx/primer-delegation
                          :rule.ctx/primer-proofs}
                        fb-canvas)))
          (finally (d/release bconn)))))))

(deftest ^{:cost/ticks 120000000} live-status-renderers-svg-sanitization-and-wait-nudge-test
  (testing ":seon.ui.status/* upsert, renderer discovery, SVG sanitization, wait-nudge tail, and seed=42 checks"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/render/status.clj"
                                                      (str "(ns seon.render.status)\n"
                                                           "(defn render-status-chip {:malli/schema [:=> [:cat [:map [:seon.ui.status/entity :seon.ui.status/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:span \"c\"]})\n"
                                                           "(defn render-status-card {:malli/schema [:=> [:cat [:map [:seon.ui.status/entity :seon.ui.status/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:div \"k\"]})\n")
                                                      false)})
      (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn {:analyze-compiler? true})})
      (d/transact conn {:tx-data [{:seon.agent/id :agent/helper :seon.agent/branch :main :seon.agent/role :worker}]})
      (let [disc (fit/discover-renderers @conn :seon.ui.status/entity)]
        (is (= '[seon.render.status/render-status-card seon.render.status/render-status-chip] disc)))

      ;; 2. set-status! upserts cleanly & render-status-card sanitizes SVG + renders progress bar
      (binding [fcalls/*agent-ctx* {:conn conn :agent-id :agent/helper :branch :main}]
        (let [r1 (rstatus/set-status!
                  {:label "Inspecting schemas"
                   :detail "Checking Datahike edges"
                   :pct 45
                   :stage :researching
                   :svg "<svg viewBox=\"0 0 120 24\" width=\"120\" height=\"24\"><script>alert(1)</script><rect onclick=\"evil()\" x=\"2\" y=\"6\" width=\"54\" height=\"12\" rx=\"6\" fill=\"#f0b429\"/></svg>"})
              r2 (rstatus/set-status! {:label "Verifying proofs" :detail "Running tests" :pct 80 :stage :testing
                                       :svg "<svg viewBox=\"0 0 120 24\"><script>bad()</script><rect onmouseover=\"x()\" width=\"96\" height=\"12\"/></svg>"})
              st-ents (vec (d/datoms @conn :avet :seon.ui.status/agent :agent/helper))
              st-map (rstatus/latest-agent-status @conn @conn :agent/helper)
              card-h (:seon.ui/hiccup (rstatus/render-status-card {:seon.ui.status/entity st-map}))
              card-html (render/hiccup->html card-h)
              card-txt (render/hiccup->text card-h)]
          (is (:ok? r1))
          (is (:ok? r2))
          (is (= 1 (count st-ents)) "Repeated set-status! must upsert single entity via :seon.ui.status/id")
          (is (contains? (fired-rule-ids @conn @conn :agent/helper) :rule.ctx/live-status-card))
          (is (str/includes? card-html "Verifying proofs"))
          (is (str/includes? card-html "80%"))
          (is (str/includes? card-html "role=\"progressbar\""))
          (is (str/includes? card-html "aria-valuenow=\"80\""))
          (is (str/includes? card-html "<svg viewBox=\"0 0 120 24\">"))
          (is (not (str/includes? (str/lower-case card-html) "<script")))
          (is (not (str/includes? (str/lower-case card-html) "onmouseover")))
          (is (nil? (rstatus/sanitize-svg "<div>not svg</div>")))
          (is (str/includes? card-txt "Verifying proofs"))
          (is (not (str/includes? card-txt "<svg")))
          ;; Clear status
          (is (:ok? (rstatus/clear-status!)))
          (is (nil? (rstatus/latest-agent-status @conn @conn :agent/helper)))))

      ;; 3. <user-waiting> tail nudge: eval-count >= 2 OR elapsed >= 5s; suppressed after recent status or reply
      (let [mid (random-uuid)
            r-msg (d/transact conn {:tx-data [{:seon.agent.message/id mid :seon.agent.message/seq 1
                                               :seon.agent.message/from :user :seon.agent.message/to :agent/helper
                                               :seon.agent.message/type :chat :seon.agent.message/body "How is the system?"}]})
            msg-tx (:max-tx (:db-after r-msg))
            msg-ms (.getTime ^java.util.Date (:db/txInstant (d/pull @conn '[:db/txInstant] msg-tx)))
            ;; 0 evals + 1s -> no nudge
            w-early (rwire/build-live-wire-request @conn @conn :agent/helper {:conn conn :now-ms (+ msg-ms 1000)})
            ;; 0 evals + 8s -> <user-waiting> fires!
            w-slow (rwire/build-live-wire-request @conn @conn :agent/helper {:conn conn :now-ms (+ msg-ms 8000)})]
        (is (not (some #(= "user-waiting" (:tag %)) (:tail-blocks w-early))))
        (is (some #(= "user-waiting" (:tag %)) (:tail-blocks w-slow)))
        (is (str/includes? (str (:activity-text w-slow)) "8s"))
        (is (str/includes? (str (:activity-text w-slow)) "user/set-status!"))
        ;; Add 2 evals at +1s -> fires on eval-count >= 2
        (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/helper
                                     :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
                                     :seon.agent.eval/code "(+ 1 1)" :seon.agent.eval/seen-messages #{mid}}
                                    {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/helper
                                     :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0
                                     :seon.agent.eval/code "(+ 2 2)"}]})
        (let [w-evs (rwire/build-live-wire-request @conn @conn :agent/helper {:conn conn :now-ms (+ msg-ms 1000)})]
          (is (some #(= "user-waiting" (:tag %)) (:tail-blocks w-evs)))
          (is (str/includes? (str (:activity-text w-evs)) "2 eval(s)")))
        ;; Recent status update suppresses nudge
        (d/transact conn {:tx-data [(rstatus/build-status-tx {:label "Working" :pct 50 :stage :researching}
                                                             :agent/helper (+ msg-ms 2000))]})
        (let [w-st (rwire/build-live-wire-request @conn @conn :agent/helper {:conn conn :now-ms (+ msg-ms 9000)})]
          (is (not (some #(= "user-waiting" (:tag %)) (:tail-blocks w-st)))))
        ;; Plain-prose reply suppresses nudge even after status expires
        (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/helper
                                     :seon.agent.eval/turn 3 :seon.agent.eval/call-index 0
                                     :seon.agent.eval/code "" :seon.agent.eval/thought "All healthy!"}]})
        (let [w-done (rwire/build-live-wire-request @conn @conn :agent/helper {:conn conn :now-ms (+ msg-ms 60000)})]
          (is (not (some #(= "user-waiting" (:tag %)) (:tail-blocks w-done))))))

      ;; 4. 100-sample seed=42 generative checks
      (let [db @conn]
        (doseq [sym '[seon.render.status/sanitize-svg
                      seon.render.status/status-id-for-agent
                      seon.render.status/render-status-chip
                      seon.render.status/render-status-card
                      seon.render.status/build-status-tx
                      seon.render.tails/render-wait-nudge-tail]]
          (let [res (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (:ok? res) (str sym " failed seed=42 check: " (pr-str res)))))))))

(deftest ^{:cost/ticks 90000000} everyday-vs-expert-mode-live-status-banner-and-asof-tx-fix-test
  (testing "Blank @ tx fix under d/as-of, live status banner in Session column, and Everyday (default) vs Expert mode"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/ui.clj"
                                                      "(ns demo.ui)\n(defn calc {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] (* 2 x))\n" false)})
      (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn {:analyze-compiler? true})})
      (let [r1 (d/transact
                conn
                {:tx-data [{:seon.task/id :task/ux-demo :seon.task/title "UX Polish Task" :seon.task/done? false}
                           {:seon.agent/id :agent/ux :seon.agent/branch :branch/ux-demo :seon.agent/role :worker
                            :seon.agent/label "Assistant" :seon.agent/task :task/ux-demo
                            :seon.agent/pinned [[:seon.task/id :task/ux-demo]
                                                [:seon.code.var/name 'demo.ui/calc]]}
                           (rstatus/build-status-tx
                            {:label "Preparing chart"
                             :detail "Summarizing your notes"
                             :pct 65
                             :stage :researching
                             :svg "<svg viewBox=\"0 0 80 16\"><rect width=\"52\" height=\"10\" rx=\"4\" fill=\"#34d399\"/></svg>"}
                            :agent/ux 1700000000000)
                           {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/ux]
                            :seon.user/view-mode :session :seon.user/sidebar-open? true}]})
            tx1 (:max-tx (:db-after r1))
            _ (d/transact conn {:tx-data [{:seon.task/id :task/ux-demo :seon.task/summary "Updated later"}]})
            db @conn
            html-everyday (render/hiccup->html (surface/render-surface db db {:conn conn :viewing-id :agent/ux :view-mode :session :viewport-w 1440 :viewport-h 900}))
            html-expert   (render/hiccup->html (surface/render-surface db db {:conn conn :viewing-id :agent/ux :view-mode :session :viewport-w 1440 :viewport-h 900 :expert-mode? true}))
            html-asof     (render/hiccup->html (surface/render-surface db db {:conn conn :viewing-id :agent/ux :view-mode :session :viewport-w 1440 :viewport-h 900 :as-of-tx tx1}))]
        ;; 1. Live status card banner prominently in Session column
        (is (str/includes? html-everyday "id=\"cv-live-status\""))
        (is (str/includes? html-everyday "Preparing chart"))
        (is (str/includes? html-everyday "65%"))
        (is (str/includes? html-everyday "<svg viewBox=\"0 0 80 16\">"))

        ;; 2. Everyday mode (default = false) tucks noisy provenance & branch/test/merge inside collapsed expert-drawer
        (is (str/includes? html-everyday "data-expert-mode=\"false\""))
        (is (str/includes? html-everyday "class=\"expert-drawer\""))
        (is (str/includes? html-everyday "class=\"expert-drawer ent-prov-drawer\""))
        (is (str/includes? html-everyday "data-chosen-fn="))
        (is (str/includes? html-everyday "class=\"surface-entity-item\""))

        ;; 3. Expert mode (true) shows provenance bars & Test/Merge actions directly open without expert-drawer
        (is (str/includes? html-expert "data-expert-mode=\"true\""))
        (is (not (str/includes? html-expert "class=\"expert-drawer\"")))
        (is (not (str/includes? html-expert "ent-prov-drawer")))
        (is (str/includes? html-expert "class=\"ent-prov-bar\""))
        (is (str/includes? html-expert "Merge → :main"))

        ;; 4. Blank `@ tx ` fix under d/as-of (AsOfDB has nil :max-tx, must show as-of tx number!)
        (is (str/includes? html-asof (str ":branch/ux-demo @ tx " tx1)))
        (is (not (str/includes? html-asof "@ tx <")) "Must never render blank '@ tx ' under d/as-of")))))
