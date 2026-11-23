(ns seon.link-audit-test
  "Automated QA & Link-Completeness Audit Suite:
   1. Walks rendered Hiccup trees across ALL 4 views (`:session`, `:context`, `:system`, `:inspect`
      across all 15 entity kinds), shell units, `seon.render.entities`, `seon.render.tails`,
      `seon.signals`, and `seon.render/render-schema-breadcrumbs` — verifying EVERY `:on-click` /
      `:on-submit` resolves to a bound `^:seon/callable` var (0 broken/stale links) and EVERY
      major ref type emits clickable `.ent-link` navigation while `hiccup->text` stays clean.
   2. 4-hop trail navigation (`agent -> task -> ns -> var`, Back/Forward/Breadcrumb jump) and
      `< 40 ms` warm frame budget across all 4 views.
   3. Regression tests for all 4 live Chrome crawl bugs (#1 `AsOfDB` `(:schema db)` nil,
      #2 stale-branch `:seon.code.var/calls` Malli contract on armed `render-var-chip`,
      #3 `toggle-lab-rule!` Datastar JSON body + live flip + candidate suppression,
      #4 stale `#stream-error-notice` clearing + `Return to Live` fault button + CSS polish).
   4. `seed=42` generative proofs on all new public render/link helpers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.graph :as graph]
            [seon.error :as serr]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.entities :as rent]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks]
            [seon.render.surface :as surface]
            [seon.render.surface.inspector :as sinsp]
            [seon.render.tails :as rtails]
            [seon.render.ui :as ui]
            [seon.rule :as rule]
            [seon.schema :as schema]
            [seon.signals :as sig]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor :as txr]
            [seon.vector :as vec]
            [seon.web :as web]
            [seon.web.actions :as actions]
            [seon.web.styles :as styles]))

(defn- unit-vec [dims idx]
  (vec (for [i (range dims)] (if (= i idx) 1.0 (if (= i (mod (inc idx) dims)) 0.1 0.0)))))

(defn- walk-hiccup-handlers
  "Walks any Hiccup tree and returns `{:handlers [{:attr :form :class :text}] :inspect-attrs #{...}}`."
  [tree]
  (let [acc (volatile! {:handlers [] :inspect-attrs #{} :inspect-refs []})]
    (letfn [(node-text [n]
              (cond (string? n) n
                    (number? n) (str n)
                    (vector? n) (let [more (rest n)
                                      ch (if (map? (first more)) (rest more) more)]
                                  (str/trim (apply str (map node-text ch))))
                    (seq? n) (str/trim (apply str (map node-text n)))
                    :else ""))
            (visit [n]
              (cond
                (and (vector? n) (keyword? (first n)))
                (let [[_ & more] n
                      attrs (when (map? (first more)) (first more))
                      children (if attrs (rest more) more)]
                  (when attrs
                    (doseq [ek [:on-click :on-submit :on-change :seon/call]]
                      (when-let [hv (get attrs ek)]
                        (vswap! acc update :handlers conj
                                {:attr ek :form hv :class (:class attrs) :text (node-text n)})
                        (when (and (seq? hv) (= 'seon.web/inspect-entity! (first hv)))
                          (let [r (second hv)]
                            (when (and (vector? r) (qualified-keyword? (first r)))
                              (vswap! acc (fn [m] (-> m (update :inspect-attrs conj (first r))
                                                      (update :inspect-refs conj r))))))))))
                  (doseq [c children] (visit c)))
                (sequential? n)
                (doseq [c n] (visit c))))]
      (visit tree)
      @acc)))

(defn- callable-head-sym [hv]
  (cond
    (and (seq? hv) (qualified-symbol? (first hv))) (first hv)
    (and (vector? hv) (seq hv)) (render/fn->qualified-sym (first hv))
    :else (render/fn->qualified-sym hv)))

(defn- verify-all-handlers-callable! [label tree]
  (let [{:keys [handlers] :as info} (walk-hiccup-handlers tree)
        broken (keep (fn [{:keys [attr form text]}]
                       (when-not (string? form)
                         (let [sym (callable-head-sym form)
                               v (when sym (resolve sym))]
                           (when-not (and v (:seon/callable (meta v)))
                             {:label label :attr attr :form form :sym sym :text text}))))
                     handlers)]
    (is (empty? broken) (str label " had non-callable/broken handlers: " (pr-str (take 8 broken))))
    info))

(defn- seed-audit-world! [conn]
  (d/transact conn {:tx-data (code/file-import-tx
                              @conn "src/demo/audit.clj"
                              (str "(ns demo.audit \"Audit demo namespace.\")\n"
                                   "(defn helper \"Helper incrementer.\" {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] (inc x))\n"
                                   "(defn compute \"Computes next value via helper.\" {:malli/schema [:=> [:cat :int] :int] :cost/ticks 20000} [x] (helper x))\n")
                              false)})
  (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn {:analyze-compiler? true})})
  (let [fix-id (random-uuid)
        err-id (random-uuid)
        ev1-id (random-uuid)
        ev2-id (random-uuid)
        ev3-id (random-uuid)
        call-id (random-uuid)
        m1-id (random-uuid)
        m2-id (random-uuid)
        tx1-r (d/transact
               conn
               {:tx-data
                [{:seon.task/id :task/audit-dep
                  :seon.task/title "Dependency Task"
                  :seon.task/scope 'demo.audit
                  :seon.task/owner :agent/root
                  :seon.task/plan "Provide helper"
                  :seon.task/done? true}
                 {:seon.task/id :task/audit-main
                  :seon.task/title "Audit Main Task"
                  :seon.task/scope 'demo.audit
                  :seon.task/owner :agent/worker
                  :seon.task/created-by :agent/root
                  :seon.task/template :task/audit-dep
                  :seon.task/needs #{:task/audit-dep}
                  :seon.task/tests #{'demo.audit/compute}
                  :seon.task/plan "1. Audit links\n2. Prove callables"
                  :seon.task/summary "Active audit summary"
                  :seon.task/done? false}
                 {:seon.code.doc/id "docs/audit.md#overview"
                  :seon.code.doc/path "docs/audit.md"
                  :seon.code.doc/order 1
                  :seon.code.doc/heading "Audit Overview"
                  :seon.code.doc/body "Every reference is a clickable link."}
                 {:seon.fix/id fix-id
                  :seon.fix/error-class :eval/audit-boom
                  :seon.fix/symptom "Boom on stale branch"
                  :seon.fix/root-cause "Missing schema fallback"
                  :seon.fix/change-summary "Unwrap refs via main-db schema"
                  :seon.fix/regression-test 'demo.audit/compute}
                 (serr/error-tx {:id err-id :kind :eval/audit-boom :severity :error :source :eval
                                 :agent :agent/worker :branch :branch/audit :var 'demo.audit/compute
                                 :message "Audit fault on compute"})
                 {:seon.test/digest "auditfail1234567"
                  :seon.test/var 'demo.audit/compute
                  :seon.test/kind :deftest
                  :seon.test/pass? false
                  :seon.test/ms 2.1
                  :seon.test/report "expected 2 got 1"}
                 {:seon.rule/id :rule.ctx/audit-demo
                  :seon.rule/category :context
                  :seon.rule/stratum 20
                  :seon.rule/target :any
                  :seon.rule/enabled? true
                  :seon.rule/doc "Audit context rule"
                  :seon.rule/query '[:find ?e ?tx :in $main $branch ?agent :where [$main ?e :seon.task/id :task/audit-main ?tx]]
                  :seon.rule/assert '[{:entity ?e :zone :static-prefix :priority 95 :rank/boost 0.2 :tx ?tx}]}
                 {:seon.agent/id :agent/root
                  :seon.agent/entry? true
                  :seon.agent/branch :main
                  :seon.agent/role :coordinator
                  :seon.agent/label "Root"
                  :seon.agent/scope 'demo.audit
                  :seon.agent/task :task/audit-dep
                  :seon.agent/goal "Coordinate link audit"}
                 {:seon.agent/id :agent/worker
                  :seon.agent/parent :agent/root
                  :seon.agent/branch :branch/audit
                  :seon.agent/role :worker
                  :seon.agent/label "Worker"
                  :seon.agent/scope 'demo.audit
                  :seon.agent/task :task/audit-main
                  :seon.agent/goal "Audit every UI link"
                  :seon.agent/pinned [[:seon.task/id :task/audit-main]
                                      [:seon.code.var/name 'demo.audit/compute]
                                      [:seon.code.doc/id "docs/audit.md#overview"]]}
                 {:seon.agent.call/id call-id
                  :seon.agent.call/caller :agent/root
                  :seon.agent.call/callee :agent/worker
                  :seon.agent.call/status :pending
                  :seon.agent.call/result-schema :seon.task/entity
                  :seon.agent.call/prompt "Verify every link in UI"}
                 {:seon.agent.message/id m1-id :seon.agent.message/seq 1 :seon.agent.message/from :user
                  :seon.agent.message/to :agent/worker :seon.agent.message/type :chat
                  :seon.agent.message/body "Audit demo.audit/compute links"}
                 {:seon.agent.eval/id ev1-id :seon.agent.eval/agent :agent/worker :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
                  :seon.agent.eval/handle 'result/e1 :seon.agent.eval/intent "Inspect compute"
                  :seon.agent.eval/code "(demo.audit/compute 1)" :seon.agent.eval/value "2"
                  :seon.agent.eval/result-schema :seon.task/entity
                  :seon.agent.eval/reasoning "Inspecting demo.audit/helper and demo.audit/compute"
                  :seon.agent.eval/prefix-hash "1111aaaa" :seon.agent.eval/prompt-tokens 1000
                  :seon.agent.eval/cache-hit-tokens 800 :seon.agent.eval/cache-miss-tokens 200
                  :seon.agent.eval/seen-messages #{m1-id}}
                 {:seon.agent.eval/id ev2-id :seon.agent.eval/agent :agent/worker :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0
                  :seon.agent.eval/handle 'result/e2 :seon.agent.eval/intent "Inspect helper"
                  :seon.agent.eval/code "(user/source 'demo.audit/helper)" :seon.agent.eval/value "(defn helper [x] (inc x))"
                  :seon.agent.eval/result-schema :seon.code.var/entity
                  :seon.agent.eval/reasoning "Checking demo.audit/helper directly"
                  :seon.agent.eval/prefix-hash "2222bbbb" :seon.agent.eval/prompt-tokens 1300
                  :seon.agent.eval/cache-hit-tokens 300 :seon.agent.eval/cache-miss-tokens 1000}
                 {:seon.agent.message/id m2-id :seon.agent.message/seq 2 :seon.agent.message/from :agent/worker
                  :seon.agent.message/to :user :seon.agent.message/type :chat
                  :seon.agent.message/body "Audit pass 1 done."}
                 {:seon.user/id :user/default
                  :seon.user/viewing [:seon.agent/id :agent/worker]
                  :seon.user/view-mode :session
                  :seon.user/sidebar-open? true}]})
        tx1 (reduce max 0 (map :tx (:tx-data tx1-r)))]
    ;; Second tx so d/as-of history has >1 distinct tx and vectors exist
    (d/transact conn {:tx-data [{:seon.agent.eval/id ev3-id :seon.agent.eval/agent :agent/worker :seon.agent.eval/turn 3
                                 :seon.agent.eval/call-index 0 :seon.agent.eval/handle 'result/e3
                                 :seon.agent.eval/intent "Confirm pass" :seon.agent.eval/code "(+ 1 2)" :seon.agent.eval/value "3"
                                 :seon.agent.eval/prefix-hash "2222bbbb" :seon.agent.eval/prompt-tokens 1400
                                 :seon.agent.eval/cache-hit-tokens 1200 :seon.agent.eval/cache-miss-tokens 200}]})
    (let [db @conn
          c-eid (:e (first (d/datoms db :avet :seon.code.var/name 'demo.audit/compute)))
          h-eid (:e (first (d/datoms db :avet :seon.code.var/name 'demo.audit/helper)))
          t-eid (:e (first (d/datoms db :avet :seon.task/id :task/audit-main)))
          d-eid (:e (first (d/datoms db :avet :seon.code.doc/id "docs/audit.md#overview")))
          f-key (:seon.code.form/key (d/pull db '[:seon.code.form/key]
                                             (:e (first (d/datoms db :avet :seon.code.form/ns 'demo.audit)))))]
      (d/transact conn {:tx-data (vec/tx-data-for-batch
                                  [{:eid t-eid :digest "at"} {:eid c-eid :digest "ac"}
                                   {:eid h-eid :digest "ah"} {:eid d-eid :digest "ad"}]
                                  [(unit-vec 16 0) (unit-vec 16 0) (unit-vec 16 1) (unit-vec 16 2)]
                                  [12 24 18 15] "test-embed+v1")})
      {:fix-id fix-id :err-id err-id :ev1-id ev1-id :ev2-id ev2-id :ev3-id ev3-id
       :call-id call-id :form-key f-key :tx1 tx1 :c-eid c-eid :t-eid t-eid})))

(deftest ^{:cost/ticks 120000000} exhaustive-callable-and-link-completeness-audit-test
  (testing "ALL 4 views, shell units, 15 inspector kinds, entities, tails, signals, and breadcrumbs have 0 broken callables and complete clickable links"
    (with-test-store [{:keys [conn]}]
      (let [{:keys [fix-id err-id ev1-id call-id form-key t-eid c-eid]} (seed-audit-world! conn)
            db @conn
            ;; 1. Shell units (header, sidebar, live stream, composer)
            hdr-h (ui/header-unit db {:boot-seq 1 :agent-states {:agent/worker :active} :active-count 1})
            sb-h  (ui/sidebar-unit db {:agent-states {:agent/worker :active}})
            st-h  (ui/stream-unit db {:live-streams {:agent/worker {:status :token :phase :writing
                                                                    :started-ms 1000 :last-ms 2000
                                                                    :chars 120 :step-chars 120 :text "Working"}}
                                      :errors [] :viewing-id :agent/worker :view-mode :session})
            cp-h  (ui/composer-unit db {})
            ;; 2. All 4 holistic views
            sess-h (surface/render-surface db db {:conn conn :viewing-id :agent/worker :view-mode :session
                                                  :viewport-w 1440 :viewport-h 900})
            ctx-h  (surface/render-surface db db {:conn conn :viewing-id :agent/worker :view-mode :context
                                                  :viewport-w 1440 :viewport-h 900
                                                  :already-shown {t-eid {:tx (:max-tx db) :chosen-fn 'seon.render.entities/render-task-card}}
                                                  :walk-summary [{:id "seon-header" :reads-count 4 :hit? true :ms 0.0}
                                                                 {:id "seon-view-unit" :reads-count 14 :hit? false :ms 4.1}]})
            sys-h  (surface/render-surface db db {:conn conn :viewing-id :agent/worker :view-mode :system
                                                  :viewport-w 1440 :viewport-h 900
                                                  :semantic-search {:ok? true :query "compute"
                                                                    :results [{:eid c-eid :score 0.92 :kind :var
                                                                               :ref [:seon.code.var/name 'demo.audit/compute]
                                                                               :label "demo.audit/compute"}]}})
            ;; Verify 0 broken callables across shell + 3 top-level views
            hdr-i  (verify-all-handlers-callable! :header hdr-h)
            sb-i   (verify-all-handlers-callable! :sidebar sb-h)
            st-i   (verify-all-handlers-callable! :stream st-h)
            _      (verify-all-handlers-callable! :composer cp-h)
            sess-i (verify-all-handlers-callable! :session sess-h)
            ctx-i  (verify-all-handlers-callable! :context ctx-h)
            sys-i  (verify-all-handlers-callable! :system sys-h)]
        ;; Shell link checks (header signals + agent breadcrumb, sidebar header -> :system, stream agent link)
        (is (contains? (:inspect-attrs hdr-i) :seon.signal/kind))
        (is (contains? (:inspect-attrs hdr-i) :seon.agent/id))
        (is (some #(= '(seon.web/set-view-mode! :system) (:form %)) (:handlers sb-i)))
        (is (contains? (:inspect-attrs st-i) :seon.agent/id))

        ;; Session view link completeness: agent/parent, branch, scope ns, task, chosen-fn var, rule, result/e<n> eval, result-schema
        (doseq [a [:seon.agent/id :seon.branch/name :seon.code.ns/name :seon.task/id
                   :seon.code.var/name :seon.rule/id :seon.agent.eval/id :seon.schema/key]]
          (is (contains? (:inspect-attrs sess-i) a) (str ":session missing clickable link for " a)))

        ;; Context & Cache link completeness: eval turns/busts, renderer vars, walk unit var, rules, errors, agent, branch, task, ns, signal
        (doseq [a [:seon.agent.eval/id :seon.code.var/name :seon.rule/id :seon.error/id
                   :seon.agent/id :seon.branch/name :seon.task/id :seon.code.ns/name :seon.signal/kind]]
          (is (contains? (:inspect-attrs ctx-i) a) (str ":context missing clickable link for " a)))
        (is (some #(= [:seon.code.var/name 'seon.render.ui/view-unit] %) (:inspect-refs ctx-i))
            ":context UI Walk Cache should link unit IDs to seon.render.ui/* vars")
        (is (some #(and (seq? (:form %)) (= 'seon.web/toggle-lab-rule! (first (:form %)))) (:handlers ctx-i))
            ":context Rules table must expose callable toggle-lab-rule! buttons")

        ;; System view link completeness: signals, branch, agent/parent/caller/callee, call, task/needs, ns, var, error, fix, schema, test
        (doseq [a [:seon.signal/kind :seon.branch/name :seon.agent/id :seon.agent.call/id
                   :seon.task/id :seon.code.ns/name :seon.code.var/name :seon.error/id
                   :seon.fix/id :seon.schema/key :seon.test/digest]]
          (is (contains? (:inspect-attrs sys-i) a) (str ":system missing clickable link for " a)))

        ;; 3. Inspector across ALL 15 entity types: 0 broken callables + renderer fn var links + neighborhood links
        (let [all-15 [[:seon.code.var/name 'demo.audit/compute]
                      [:seon.code.ns/name 'demo.audit]
                      [:seon.task/id :task/audit-main]
                      [:seon.rule/id :rule.ctx/audit-demo]
                      [:seon.schema/key :seon.task/entity]
                      [:seon.agent/id :agent/worker]
                      [:seon.agent.eval/id ev1-id]
                      [:seon.error/id err-id]
                      [:seon.fix/id fix-id]
                      [:seon.test/digest "auditfail1234567"]
                      [:seon.code.doc/id "docs/audit.md#overview"]
                      [:seon.branch/name :branch/audit]
                      [:seon.signal/kind :health]
                      [:seon.agent.call/id call-id]
                      [:seon.code.form/key form-key]]
              total-insp (atom 0)]
          (doseq [ref all-15]
            (let [ih (surface/render-surface db db {:conn conn :viewing-id :agent/worker :inspect-ref ref
                                                    :inspect-trail [[:seon.agent/id :agent/root]]
                                                    :inspect-forward [[:seon.branch/name :main]]
                                                    :viewport-w 1440 :viewport-h 900})
                  info (verify-all-handlers-callable! [:inspect ref] ih)
                  html (render/hiccup->html ih)]
              (swap! total-insp + (count (:handlers info)))
              (is (str/includes? html "id=\"seon-inspector-unit\"") (str "Inspector missing root for " ref))
              (is (not (str/includes? html "Entity not found")) (str "Inspector entity not found for " ref))
              (is (pos? (count (:inspect-refs info))) (str "Inspector emitted 0 entity links for " ref))))
          (is (> @total-insp 80) (str "Expected rich callable surface across 15 inspector types, got " @total-insp))
          ;; Inspecting a var must link its renderer variation fn (`seon.render.entities/render-var-*`)
          (let [vi (verify-all-handlers-callable!
                    :inspect-var
                    (surface/render-surface db db {:conn conn :viewing-id :agent/worker
                                                   :inspect-ref [:seon.code.var/name 'demo.audit/compute]}))]
            (is (some #(and (= :seon.code.var/name (first %))
                            (str/starts-with? (str (second %)) "seon.render.entities/render-var-"))
                      (:inspect-refs vi))
                "Inspector variations must link renderer fn vars!")))

        ;; 4. Direct renderers (`entities`, `tails`, `signals`, `render-schema-breadcrumbs`) + `hiccup->text` preservation
        (let [fix-ent (d/pull db '[*] [:seon.fix/id fix-id])
              call-ent (d/pull db '[*] [:seon.agent.call/id call-id])
              snap (sig/all-signals db)
              tail-act (:seon.ui/hiccup (rtails/render-activity-tail
                                         {:seon.tail/activity
                                          {:scope 'demo.audit
                                           :incoming [call-ent]
                                           :outgoing [call-ent]
                                           :open-errors [(d/pull db '[*] [:seon.error/id err-id])]}}))
              tail-br  (:seon.ui/hiccup (rtails/render-branch-status-tail
                                         {:seon.tail/branch-status
                                          {:branch :branch/audit :max-tx 100 :branch-edited [form-key]
                                           :branch-errors [{:class :test-fail :var 'demo.audit/compute :subject "demo.audit/compute" :message "boom"}]}}))
              sig-bar  (:seon.ui/hiccup (sig/render-signals-bar {:seon.signal/snapshot snap}))
              sig-pan  (:seon.ui/hiccup (sig/render-signals-panel {:seon.signal/snapshot snap}))
              fix-card (:seon.ui/hiccup (rent/render-fix-card {:seon.fix/entity fix-ent}))
              call-crd (:seon.ui/hiccup (rent/render-call-card {:seon.agent.call/entity call-ent}))
              crumbs   (render/render-schema-breadcrumbs db :seon.task/entity (d/pull db '[*] [:seon.task/id :task/audit-main])
                                                         {:mode :human :main-db db :agent-id :agent/worker})]
          (doseq [[k h] [[:tail-act tail-act] [:tail-br tail-br] [:sig-bar sig-bar]
                         [:sig-pan sig-pan] [:fix-card fix-card] [:call-card call-crd] [:crumbs crumbs]]]
            (let [i (verify-all-handlers-callable! k h)]
              (is (pos? (count (:inspect-refs i))) (str k " emitted 0 clickable entity links"))))
          ;; hiccup->text keeps .ent-link text cleanly readable while stripping chrome buttons
          (is (str/includes? (render/hiccup->text fix-card) "demo.audit/compute"))
          (is (str/includes? (render/hiccup->text call-crd) ":agent/worker"))
          (is (str/includes? (render/hiccup->text sig-bar) "Flow"))
          (is (str/includes? (render/hiccup->text tail-act) ":agent/root"))
          (is (= "demo.audit/compute"
                 (render/hiccup->text [:div
                                       (rlinks/ent-link [:seon.code.var/name 'demo.audit/compute] "demo.audit/compute" "mono")
                                       [:button {:type "button" :class "btn sm"} "Hidden Chrome"]]))))))))

(deftest ^{:cost/ticks 90000000} four-hop-trail-navigation-and-warm-frame-budget-test
  (testing "4-hop link chain (agent -> task -> ns -> var) pushes trail, Back/Forward/Jump work, and all 4 views render < 40 ms warm"
    (with-test-store [{:keys [conn]}]
      (seed-audit-world! conn)
      (let [st {:conn conn}
            apply-call! (fn [call-form]
                          (let [res (actions/invoke-call! st {:call-form call-form})]
                            (is (:ok? res) (str "invoke-call! failed: " (pr-str res)))
                            (when-let [req (get-in res [:async :req])]
                              (when (= :show-user (:op req))
                                (let [{:keys [tx-data]} (#'txr/show-user-tx @conn req)]
                                  (d/transact conn {:tx-data tx-data}))))
                            res))
            r-ag   [:seon.agent/id :agent/worker]
            r-task [:seon.task/id :task/audit-main]
            r-ns   [:seon.code.ns/name 'demo.audit]
            r-var  [:seon.code.var/name 'demo.audit/compute]]
        ;; 4-hop forward chain
        (doseq [r [r-ag r-task r-ns r-var]] (apply-call! (list 'seon.web/inspect-entity! r)))
        (let [vs (rdata/user-view-state @conn)]
          (is (= r-var (:inspect-ref vs)))
          (is (= [r-ag r-task r-ns] (:inspect-trail vs)))
          (is (= [] (:inspect-forward vs))))
        ;; Step Back 2 hops -> r-task
        (apply-call! '(seon.web/inspect-back!))
        (is (= r-ns (:inspect-ref (rdata/user-view-state @conn))))
        (apply-call! '(seon.web/inspect-back!))
        (let [vs (rdata/user-view-state @conn)]
          (is (= r-task (:inspect-ref vs)))
          (is (= [r-ag] (:inspect-trail vs)))
          (is (= [r-var r-ns] (:inspect-forward vs))))
        ;; Step Forward 1 hop -> r-ns
        (apply-call! '(seon.web/inspect-forward!))
        (let [vs (rdata/user-view-state @conn)]
          (is (= r-ns (:inspect-ref vs)))
          (is (= [r-ag r-task] (:inspect-trail vs)))
          (is (= [r-var] (:inspect-forward vs))))
        ;; Jump directly to trail index 0 -> r-ag
        (apply-call! '(seon.web/jump-inspect-trail! 0))
        (let [vs (rdata/user-view-state @conn)]
          (is (= r-ag (:inspect-ref vs)))
          (is (= [] (:inspect-trail vs)))
          (is (= [r-var r-ns r-task] (:inspect-forward vs))))
        ;; Warm render time < 40 ms across all 4 views
        (let [db @conn
              timed (fn [opts]
                      (surface/render-surface db db (assoc opts :conn conn))
                      (let [t0 (System/nanoTime)
                            _ (render/hiccup->html (surface/render-surface db db (assoc opts :conn conn)))]
                        (/ (double (- (System/nanoTime) t0)) 1e6)))]
          (doseq [[lbl opts] [[:session {:viewing-id :agent/worker :view-mode :session :inspect-ref nil :viewport-w 1440 :viewport-h 900}]
                              [:context {:viewing-id :agent/worker :view-mode :context :inspect-ref nil :viewport-w 1440 :viewport-h 900}]
                              [:system  {:viewing-id :agent/worker :view-mode :system  :inspect-ref nil :viewport-w 1440 :viewport-h 900}]
                              [:inspect {:viewing-id :agent/worker :inspect-ref r-var :viewport-w 1440 :viewport-h 900}]]]
            (let [ms (timed opts)]
              (is (< ms 40.0) (str lbl " warm render took " ms " ms (> 40 ms)")))))))))

(deftest ^{:cost/ticks 100000000} live-browser-crawl-four-bug-regressions-test
  (testing "All 4 live Chrome crawl bugs stay fixed: #1 AsOfDB nil :schema, #2 stale branch ref contract, #3 toggle-lab-rule! JSON+flip, #4 error clear + Return to Live + CSS"
    (with-test-store [{:keys [conn]}]
      (let [{:keys [tx1 c-eid]} (seed-audit-world! conn)
            db @conn
            asof (d/as-of db tx1)]
        ;; BUG #1: AsOfDB has (:schema asof) == nil; verdict-latest-for-var? + all 4 views + breadcrumbs must NOT throw!
        (is (nil? (:schema asof)))
        (is (contains? (rlinks/db-schema asof) :seon.code.var/name))
        (is (boolean? (rule/verdict-latest-for-var? asof c-eid tx1)))
        (doseq [opts [{:viewing-id :agent/worker :view-mode :session :as-of-tx tx1}
                      {:viewing-id :agent/worker :view-mode :context :as-of-tx tx1}
                      {:viewing-id :agent/worker :view-mode :system  :as-of-tx tx1}
                      {:viewing-id :agent/worker :inspect-ref [:seon.code.var/name 'demo.audit/compute] :as-of-tx tx1}]]
          (let [html (render/hiccup->html (surface/render-surface db db (assoc opts :conn conn)))]
            (is (str/includes? html "id=\"seon-view-unit\""))
            (is (not (str/includes? html "Render fault")))))

        ;; BUG #2: Stale branch DB missing :seon.code.var/calls in its schema + armed render-var-chip -> 0 Malli violations!
        (let [stale-bdb (assoc db :schema (dissoc (or (:schema db) (d/schema db)) :seon.code.var/calls :seon.code.var/refs))
              raw-var (assoc (d/pull db '[*] c-eid)
                             :seon.code.var/calls [{:db/id c-eid}]
                             :seon.code.var/refs  ['demo.audit/helper])
              normed (#'fit/normalize-entity-for-malli stale-bdb raw-var nil (or (:schema db) (d/schema db)))]
          (is (set? (:seon.code.var/calls normed)))
          (is (every? integer? (:seon.code.var/calls normed)))
          (is (set? (:seon.code.var/refs normed)))
          (is (every? qualified-symbol? (:seon.code.var/refs normed)))
          ;; Armed render-var-chip via Malli contract must succeed on normalized entity AND inside Inspector!
          (schema/arm-var! db #'rent/render-var-chip)
          (let [res (rent/render-var-chip {:seon.code.var/entity normed})
                insp-html (render/hiccup->html
                           (sinsp/render-inspector-body
                            {:main-db db :branch-db stale-bdb :viewing-id :agent/worker
                             :agent {:seon.agent/id :agent/worker}
                             :inspect-ref [:seon.task/id :task/audit-main]
                             :inspect-trail [] :inspect-forward [] :lab-prefs {}
                             :slot-budget {:avail-w 1100 :area-budget 1400000}}))]
            (is (vector? (:seon.ui/hiccup res)))
            (is (str/includes? insp-html "id=\"seon-inspector-unit\""))
            (is (not (str/includes? insp-html "Schema contract violation")))))

        ;; BUG #3: parse-form-body ignores Datastar JSON payloads regardless of header casing; toggle-lab-rule! flips & suppresses!
        (is (= {} (actions/parse-form-body {:headers {"Content-Type" "application/json; charset=utf-8"} :body "{\"aggRate\":\"\"}"})))
        (is (= {} (actions/parse-form-body {:headers {} :body "{\"aggRate\":\"10 tok/s\"}"})))
        (is (= {:query "kv"} (actions/parse-form-body {:headers {"Content-Type" "application/x-www-form-urlencoded"} :body "query=kv"})))
        (let [st {:conn conn}
              do-toggle! (fn []
                           (let [res (actions/invoke-call! st {:call-form '(seon.web/toggle-lab-rule! :rule.ctx/audit-demo)
                                                               :form-fields (actions/parse-form-body {:headers {"Content-Type" "application/json"}
                                                                                                      :body "{\"aggRate\":\"\"}"})})]
                             (is (:ok? res))
                             (let [{:keys [tx-data]} (#'txr/show-user-tx @conn (get-in res [:async :req]))]
                               (d/transact conn {:tx-data tx-data}))))
              html-before (render/hiccup->html (surface/render-surface @conn @conn {:conn conn :viewing-id :agent/worker :view-mode :context}))]
          (is (str/includes? html-before "id=\"lab-rule-btn-audit-demo\""))
          (do-toggle!)
          (let [html-off (render/hiccup->html (surface/render-surface @conn @conn {:conn conn :viewing-id :agent/worker :view-mode :context}))]
            (is (contains? (:disabled-rules (rdata/normalize-lab-prefs (:lab-prefs (rdata/user-view-state @conn)))) :rule.ctx/audit-demo))
            (is (str/includes? html-off ">Disabled</button>"))
            (is (str/includes? html-off "1 lab-disabled")))
          (do-toggle!)
          (let [html-on (render/hiccup->html (surface/render-surface @conn @conn {:conn conn :viewing-id :agent/worker :view-mode :context}))]
            (is (not (contains? (:disabled-rules (rdata/normalize-lab-prefs (:lab-prefs (rdata/user-view-state @conn)))) :rule.ctx/audit-demo)))
            (is (not (str/includes? html-on "1 lab-disabled")))))

        ;; BUG #4: Fault fallback includes Return to Live when :as-of-tx is set; stale errors clear on clean walk; CSS polish verified!
        (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/as-of-tx tx1}]})
        (let [boom-comp (fn [_db _args] (throw (ex-info "Simulated time-travel fault" {})))
              res (render/step-walk (render/empty-walk) @conn nil [{:id "seon-view-unit" :comp boom-comp :args [{}]}])
              fault-html (get-in res [:walk :units "seon-view-unit" :html])]
          (is (= 1 (count (:render-errors res))))
          (is (str/includes? fault-html "Return to Live"))
          (is (str/includes? fault-html "jump-canvas-live")))
        (d/transact conn {:tx-data [[:db/retract [:seon.user/id :user/default] :seon.user/as-of-tx tx1]]})
        ;; Verify web-proc clears stale :errors AND removes #stream-error-notice in the SAME clean :db render!
        (let [st-dirty {:conn conn :walk (render/empty-walk) :live-streams {} :agent-states {}
                        :last-stream-emit-ns 0 :last-view-sig nil :last-agg-rate ""
                        :reports [] :errors [{:unit "seon-view-unit" :error "Stale old error"}] :frames 1}
              [st-clean _] (web/web-proc st-dirty :db {:reason :show-user :tx-data nil})
              stream-html (get-in st-clean [:walk :units "seon-stream-unit" :html])]
          (is (= [] (:errors st-clean)) "Clean walk render MUST clear stale :errors")
          (is (not (str/includes? (str stream-html) "stream-error-notice"))
              "#stream-error-notice must disappear on the SAME clean walk broadcast"))
        ;; CSS rules: .ent-link before .sig-pill / .sig-cell, plus rail overflow & var-col polish
        (let [css (styles/full-css)
              i-ent (.indexOf css ".ent-link{")
              i-sp  (.indexOf css ".sig-pill{")
              i-sc  (.indexOf css ".sig-cell{")]
          (is (and (>= i-ent 0) (< i-ent i-sp) (< i-ent i-sc))
              ".ent-link CSS rule must appear BEFORE .sig-pill and .sig-cell so signal buttons keep chip styling")
          (doseq [needle [".surface-entity-item," "min-width:0" ".ent-code{" "overflow-x:auto"
                          ".ent-meta{" "overflow-wrap:anywhere" ".var-col{"]]
            (is (str/includes? css needle) (str "Missing CSS polish rule: " needle))))))))

(deftest ^{:cost/ticks 90000000} new-render-helpers-seed42-generative-test
  (testing "All new public render/link/tail/kv-cache/action helpers pass 100-sample seed=42 generative checks"
    (with-test-store [{:keys [conn]}]
      (let [db @conn]
        ;; Multi-arity link helpers
        (is (vector? (rlinks/ent-link [:seon.task/id :t/a] "t")))
        (is (vector? (rlinks/ent-link [:seon.task/id :t/a] "t" "mono")))
        (is (vector? (rlinks/ent-link [:seon.task/id :t/a] "t" "mono" "title")))
        (is (vector? (rlinks/ent-link nil "plain" "mono")))
        (is (vector? (rlinks/meta-link :seon.code.ns/name 'seon.web)))
        (is (vector? (rlinks/link-pill :seon.branch/name :main ":main")))
        (doseq [sym '[seon.render.links/real-rule-id?
                      seon.render.links/kv-dl
                      seon.render.links/pill-list
                      seon.render.links/badge
                      seon.render.links/pill
                      seon.render.links/mut
                      seon.render.links/kicker
                      seon.render.entities/render-fix-chip
                      seon.render.entities/render-fix-card
                      seon.render.entities/render-call-row
                      seon.render.entities/render-call-card
                      seon.render.tails/render-branch-status-tail
                      seon.render.tails/render-scoped-health-tail
                      seon.render.tails/render-activity-tail
                      seon.render.tails/render-inbox-tail
                      seon.render.tails/render-system-health-tail
                      seon.render.tails/render-viewport-tail
                      seon.render.surface.kv-cache/render-kv-cache-section
                      seon.web.actions/parse-form-body]]
          (let [res (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (:ok? res) (str sym " failed seed=42 generative check: " (pr-str res)))))))))
