(ns seon.distributed-render-test
  "Proves general distributed rendering, uniform pure `ns-map -> ns-map` multi-level entity
   renderers (`seon.render.entities`), Datahike `:seon.code.var/input` + `:output` discovery,
   live viewport sync (`set-viewport!`), ambient CSS, and complete legacy purge."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.analyze :as analyze]
            [seon.model.wire :as mwire]
            [seon.render :as render]
            [seon.render.breadcrumbs]
            [seon.render.canvas :as rcanvas]
            [seon.render.data :as rdata]
            [seon.render.entities]
            [seon.rule :as rule]
            [seon.rule.context :as rctx]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]
            [seon.web :as web]
            [seon.web.actions :as actions]
            [seon.web.css :as web-css]
            [seon.web.styles :as styles]))

(defn sample-metric-render
  "Test-only distributed schema renderer attached dynamically at runtime to `:seon.demo.metric/entity`."
  {:malli/schema [:=> [:cat :map :map] :any]}
  [{:keys [mode]} m]
  [:div {:class "card sample-metric-card" :data-mode (name (or mode :human))}
   [:strong (str "METRIC-RENDER:" (:seon.demo.metric/id m))]
   [:span (str " val=" (:seon.demo.metric/score m))]])

(def ^:private entity-renderer-specs
  "Expected uniform `ns-map -> ns-map` renderers grouped by domain entity schema (ordered compact -> rich)."
  {:seon.code.var/entity    ['seon.render.entities/render-var-chip
                             'seon.render.entities/render-var-sig
                             'seon.render.entities/render-var-full]
   :seon.error/entity       ['seon.render.entities/render-error-badge
                             'seon.render.entities/render-error-card]
   :seon.task/entity        ['seon.render.entities/render-task-chip
                             'seon.render.entities/render-task-card
                             'seon.render.entities/render-task-full]
   :seon.schema/entity      ['seon.render.entities/render-schema-chip
                             'seon.render.entities/render-schema-detail]
   :seon.code.doc/entity    ['seon.render.entities/render-doc-snippet
                             'seon.render.entities/render-doc-full]
   :seon.test/verdict       ['seon.render.entities/render-test-badge
                             'seon.render.entities/render-test-card]
   :seon.agent.eval/entity  ['seon.render.entities/render-eval-row
                             'seon.render.entities/render-eval-card]
   :seon.code.ns/entity     ['seon.render.entities/render-ns-chip
                             'seon.render.entities/render-ns-card]
   :seon.agent/entity       ['seon.render.entities/render-agent-row
                             'seon.render.entities/render-agent-card]
   :seon.rule/entity        ['seon.render.entities/render-rule-chip
                             'seon.render.entities/render-rule-detail]})

(def ^:private all-entity-renderers
  (vec (sort (mapcat val entity-renderer-specs))))

(deftest ^{:cost/ticks 200000000} pure-breadcrumbs-query-board-generative-test
  (testing "Pure helpers in seon.render.breadcrumbs and ALL 22 renderers in seon.render.entities pass seed=42 generative checks"
    (is (= 22 (count all-entity-renderers)))
    (doseq [v (cons 'seon.render.breadcrumbs/entity-lookup-ref all-entity-renderers)]
      (let [res (schema/stress-var nil v {:seed 42 :samples 25})]
        (is (:ok? res) (str "Generative check failed for " v ": " (pr-str res)))))))

(deftest ^{:cost/ticks 120000000} cross-agent-distributed-schema-eval-and-breadcrumbs-test
  (testing "Brand-new schemas registered outside an agent's scope render automatically on eval & pin with live breadcrumbs + function/doc discovery"
    (with-test-store [{:keys [conn]}]
      (let [reg! (fn [k form doc]
                   (let [{:keys [dh-decls entity]} (schema/validate-schema-registration! @conn {:key k :form form :doc doc})]
                     (d/transact conn {:tx-data (vec (concat dh-decls [entity]))})))]
        (reg! :seon.demo.metric/id [:and {:seon.db/identity true} :keyword] "Metric ID")
        (reg! :seon.demo.metric/score :int "Metric score")
        (reg! :seon.demo.metric/entity
              [:map {:seon.db/attributes true :seon.render/hiccup 'seon.distributed-render-test/sample-metric-render}
               [:seon.demo.metric/id :seon.demo.metric/id]
               [:seon.demo.metric/score :seon.demo.metric/score]]
              "Custom metric entity with distributed renderer")
        (reg! :seon.demo.note/id [:and {:seon.db/identity true} :keyword] "Note ID")
        (reg! :seon.demo.note/body :string "Note body")
        (reg! :seon.demo.note/entity
              [:map {:seon.db/attributes true}
               [:seon.demo.note/id :seon.demo.note/id]
               [:seon.demo.note/body :seon.demo.note/body]]
              "Custom note entity without custom renderer"))

      (is (= 'seon.distributed-render-test/sample-metric-render
             (:seon.schema/render (d/pull @conn '[:seon.schema/render] [:seon.schema/key :seon.demo.metric/entity]))))

      (let [fn-src "(ns seon.demo.metrics)\n(defn double-metric\n  \"Doubles a metric score.\"\n  {:malli/schema [:=> [:cat :seon.demo.metric/entity] :int]}\n  [m]\n  (* 2 (:seon.demo.metric/score m)))\n"
            fn-tx (code/file-import-tx @conn "src/seon/demo/metrics.clj" fn-src false)
            _ (d/transact conn {:tx-data fn-tx})
            metric-schema-eid (:db/id (d/pull @conn '[:db/id] [:seon.schema/key :seon.demo.metric/entity]))
            _ (d/transact conn
                          {:tx-data
                           [{:seon.code.doc/id "docs/demo-metric.md#overview"
                             :seon.code.doc/path "docs/demo-metric.md"
                             :seon.code.doc/heading "Demo Metric Overview"
                             :seon.code.doc/body "Explains `:seon.demo.metric/entity` scoring."
                             :seon.code.doc/references [metric-schema-eid]}
                            {:seon.demo.metric/id :m/latency :seon.demo.metric/score 42}
                            {:seon.demo.note/id :n/alpha :seon.demo.note/body "Fallback rendered cleanly"}
                            {:seon.agent/id :ns/seon.health :seon.agent/branch :main :seon.agent/role :worker
                             :seon.agent/scope 'seon.health
                             :seon.agent/pinned [[:seon.demo.metric/id :m/latency]
                                                 [:seon.demo.note/id :n/alpha]]}]})
            db @conn
            health-ag (d/pull db '[*] [:seon.agent/id :ns/seon.health])]

        (let [ev-map (mwire/format-eval-output
                      {:handle 'result/e1 :ok? true :ms 1.2 :ticks 1000
                       :db db :main-db db
                       :value (pr-str {:seon.demo.metric/id :m/latency :seon.demo.metric/score 42})})
              ev-ref (mwire/format-eval-output
                      {:handle 'result/e2 :ok? true :ms 0.8 :ticks 800
                       :db db :main-db db
                       :value (pr-str [:seon.demo.metric/id :m/latency])})
              ev-note (mwire/format-eval-output
                       {:handle 'result/e3 :ok? true :ms 0.6 :ticks 500
                        :db db :main-db db
                        :value (pr-str {:seon.demo.note/id :n/alpha :seon.demo.note/body "Fallback rendered cleanly"})})]
          (is (str/includes? ev-map "METRIC-RENDER::m/latency"))
          (is (str/includes? ev-map "val=42"))
          (is (str/includes? ev-map "; Breadcrumbs · :seon.demo.metric/entity"))
          (is (str/includes? ev-map "seon.demo.metrics/double-metric"))
          (is (str/includes? ev-map "docs/demo-metric.md#overview"))
          (is (str/includes? ev-map "d/pull"))
          (is (str/includes? ev-ref "METRIC-RENDER::m/latency"))
          (is (str/includes? ev-ref "seon.demo.metrics/double-metric"))
          (is (str/includes? ev-note "Fallback rendered cleanly"))
          (is (str/includes? ev-note "; Breadcrumbs · :seon.demo.note/entity"))
          (is (str/includes? ev-note "self-printer")))

        (let [evals [{:seon.agent.eval/handle 'result/e9
                      :seon.agent.eval/result-schema :seon.demo.metric/entity
                      :seon.agent.eval/value (pr-str [:seon.demo.metric/id :m/latency])}]
              derived-html (rctx/derive-context db db health-ag evals nil {:target :html :include-evals? true})
              derived-wire (rctx/derive-context db db health-ag [] nil {:target :wire :include-evals? false})
              by-schema (fn [tiles sk] (first (filter #(= sk (:schema %)) tiles)))
              metric-tile (by-schema (:all-tiles derived-html) :seon.demo.metric/entity)
              note-tile (by-schema (:all-tiles derived-html) :seon.demo.note/entity)
              fn-tiles (filterv #(= :seon.code.var/entity (:schema %)) (:all-tiles derived-html))
              doc-tiles (filterv #(= :seon.code.doc/entity (:schema %)) (:all-tiles derived-html))]
          (is (some? metric-tile))
          (is (= 'seon.distributed-render-test/sample-metric-render (:view-fn metric-tile)))
          (is (true? (get-in metric-tile [:params :show-breadcrumbs?])))
          (is (some? note-tile))
          (is (= 'seon.render/render-entity-value (:view-fn note-tile)))
          (is (some #(= 'seon.demo.metrics/double-metric (get-in % [:val :seon.code.var/name])) fn-tiles))
          (is (some #(= "docs/demo-metric.md#overview" (get-in % [:val :seon.code.doc/id])) doc-tiles))

          (let [m-html (render/hiccup->html (render/render-tile-node db metric-tile {:mode :human :main-db db :agent-id :ns/seon.health}))
                n-html (render/hiccup->html (render/render-tile-node db note-tile {:mode :human :main-db db :agent-id :ns/seon.health}))
                m-wire (render/hiccup->text (render/render-tile-node db (by-schema (:all-tiles derived-wire) :seon.demo.metric/entity) {:mode :agent :main-db db}))]
            (is (str/includes? m-html "METRIC-RENDER::m/latency"))
            (is (str/includes? m-html "seon.demo.metrics/double-metric"))
            (is (str/includes? m-html "Demo Metric Overview"))
            (is (not (str/includes? m-html "set-board-state!")))
            (is (str/includes? n-html "Fallback rendered cleanly"))
            (is (str/includes? n-html "4-stage self-printer"))
            (is (str/includes? m-wire "METRIC-RENDER::m/latency"))
            (is (str/includes? m-wire "seon.demo.metrics/double-metric"))))))))

(def ^:private sample-entities
  {:seon.code.var/entity
   {:seon.code.var/entity
    {:seon.code.var/name 'seon.render.entities/render-var-chip
     :seon.code.var/ns 'seon.render.entities
     :seon.code.var/form "seon.render.entities/render-var-chip"
     :seon.code.var/doc "Compact chip renderer for a var entity with full contract metadata."
     :seon.code.var/arglists [['input]]
     :seon.code.var/private? false :seon.code.var/macro? false :seon.code.var/test? false
     :seon.code.var/platform-test? false :seon.code.var/flow-edge? false
     :seon.code.var/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]]]
                            [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
     :seon.code.var/ticks-budget 500000
     :seon.code.var/bytes-budget 262144
     :seon.code.var/big-o "O(1)"
     :seon.code.var/refs #{'clojure.core/str}}
    :seon.code.form/src "(defn render-var-chip [{v :seon.code.var/entity}] {:seon.ui/hiccup [:span (str (:seon.code.var/name v))]})"}

   :seon.error/entity
   {:seon.error/entity
    {:seon.error/id #uuid "11111111-1111-4111-8111-111111111111"
     :seon.error/kind :model/rate-limit :seon.error/severity :warning
     :seon.error/source :model :seon.error/status :open
     :seon.error/message "Upstream model returned HTTP 429 rate limit exceeded; retry after backoff."
     :seon.error/agent :agent/root :seon.error/branch :branch/root
     :seon.error/var 'seon.model/call-model! :seon.error/at 1700000000000
     :seon.error/data {:status 429 :retry-after-ms 5000}}
    :seon.fix/entity
    {:seon.fix/id #uuid "22222222-2222-4222-8222-222222222222"
     :seon.fix/error-class :model/rate-limit
     :seon.fix/symptom "Silent idle on 429"
     :seon.fix/root-cause "Model errors were not persisted as :seon.error/* entities."
     :seon.fix/change-summary "Persist typed :seon.error/* with retry-after-ms backpressure."
     :seon.fix/regression-test 'seon.error-test/model-proc-http-429-surfacing-and-agents-test
     :seon.fix/before-after "0% -> 100% surfaced"}}

   :seon.task/entity
   {:seon.task/entity
    {:seon.task/id :seon.render/entities-demo
     :seon.task/title "Unify multi-level pure entity renderers"
     :seon.task/scope 'seon.render.entities
     :seon.task/owner :agent/root
     :seon.task/created-by :agent/root
     :seon.task/done? false
     :seon.task/plan "Provide 2-3 complete-thought Hiccup levels per core entity with exact Malli IO contracts."
     :seon.task/summary "All 22 renderers implemented and proved with seed=42 generative checks."
     :seon.task/needs #{:seon.render/fit-ladder}
     :seon.task/tests #{'seon.distributed-render-test/live-query-and-board-canvas-projection-test}}}

   :seon.schema/entity
   {:seon.schema/entity
    {:seon.schema/key :seon.task/entity
     :seon.schema/type :map
     :seon.schema/doc "First-class Seon task entity driving agent planning, scope, and merge gates."
     :seon.schema/form [:map {:seon.db/attributes true} [:seon.task/id :seon.task/id] [:seon.task/title :seon.task/title]]
     :seon.schema/keys #{1 2} :seon.schema/optional-keys #{3 4 5}}}

   :seon.code.doc/entity
   {:seon.code.doc/entity
    {:seon.code.doc/id "docs/render.md#upgrade-ladder"
     :seon.code.doc/path "docs/render.md"
     :seon.code.doc/order 2
     :seon.code.doc/heading "Complete-Thought Upgrade Ladder"
     :seon.code.doc/body "The Upgrade Ladder pulls each ranked entity once, runs its pure ns-map -> ns-map Hiccup renderers, measures intrinsic min-width and token cost, and upgrades complete thoughts within budget."
     :seon.code.doc/references #{1 2}}}

   :seon.test/verdict
   {:seon.test/verdict
    {:seon.test/digest "abcdef0123456789"
     :seon.test/var 'seon.distributed-render-test/live-query-and-board-canvas-projection-test
     :seon.test/kind :deftest :seon.test/pass? true
     :seon.test/ms 14.2 :seon.test/ticks 850000 :seon.test/bytes 4194304
     :seon.test/report "PASS (22 assertions, 0 failures)"}}

   :seon.agent.eval/entity
   {:seon.agent.eval/entity
    {:seon.agent.eval/id #uuid "33333333-3333-4333-8333-333333333333"
     :seon.agent.eval/agent :agent/root :seon.agent.eval/turn 3
     :seon.agent.eval/handle 'result/e3 :seon.agent.eval/result-schema :seon.health/report
     :seon.agent.eval/intent "Inspect live system health report"
     :seon.agent.eval/code "(user/health)"
     :seon.agent.eval/value "{:ok? true :counts {} :problems []}"
     :seon.agent.eval/out "checked 72 namespaces"
     :seon.agent.eval/ms 18.4 :seon.agent.eval/ticks 2400000 :seon.agent.eval/bytes 8388608
     :seon.agent.eval/prompt-tokens 12000 :seon.agent.eval/cache-hit-tokens 11000}}

   :seon.code.ns/entity
   {:seon.code.ns/entity
    {:seon.code.ns/name 'seon.render.entities
     :seon.code.ns/path "src/seon/render/entities.clj"
     :seon.code.ns/doc "Uniform pure ns-map -> ns-map multi-level Hiccup renderers for Seon's core entities."
     :seon.code.ns/requires #{'clojure.string}}}

   :seon.agent/entity
   {:seon.agent/entity
    {:seon.agent/id :agent/guide :seon.agent/label "Architecture Guide"
     :seon.agent/role :worker :seon.agent/branch :main
     :seon.agent/scope 'seon.rule :seon.agent/waiting? true
     :seon.agent/goal "Explain Seon's unified rule-driven context & rendering pipeline using real Datahike entities."
     :seon.agent/pinned [[:seon.code.ns/name 'seon.rule]]
     :seon.agent/memory ["Uses uniform ns-map -> ns-map entity renderers."]}}

   :seon.rule/entity
   {:seon.rule/entity
    {:seon.rule/id :rule.ctx/scope-ns
     :seon.rule/doc "Pulls the agent's focus namespace into working memory."
     :seon.rule/category :discover :seon.rule/stratum 10
     :seon.rule/enabled? true :seon.rule/target :any
     :seon.rule/query '[:find ?v ?ns :in $main $wm :where [$wm ?v :ctx/viewer ?aid] [$main ?v :seon.agent/focus ?ns]]
     :seon.rule/assert '[[?v :rel/scope ?ns] [?ns :ctx/kind :seon.code.ns/entity]]}}})

(deftest ^{:cost/ticks 120000000} live-query-and-board-canvas-projection-test
  (testing "ALL 22 renderers in seon.render.entities populate Datahike :input/:output refs and produce strictly increasing complete-thought width/token levels"
    (with-test-store [{:keys [conn]}]
      ;; 1. Populate :seon.code.var/* entities via Stage 1 analyzer and prove :avet :input + :output (:seon.ui/hiccup) discovery!
      (let [db0 @conn
            var-ents (mapv (fn [vsym]
                             (let [m (meta (resolve vsym))]
                               (is (some? (:malli/schema m)) (str "Missing :malli/schema on " vsym))
                               (is (pos-int? (:cost/ticks m)) (str "Missing :cost/ticks on " vsym))
                               (analyze/enrich-var-with-account
                                db0
                                {:seon.code.var/name vsym
                                 :seon.code.var/ns 'seon.render.entities
                                 :seon.code.var/form (str vsym)
                                 :seon.code.var/private? false
                                 :seon.code.var/macro? false
                                 :seon.code.var/test? false
                                 :seon.code.var/platform-test? false
                                 :seon.code.var/flow-edge? false
                                 :seon.code.var/schema (:malli/schema m)}
                                nil #{})))
                           all-entity-renderers)
            _ (d/transact conn {:tx-data (into [{:seon.code.ns/name 'seon.render.entities}] var-ents)})
            db @conn
            hiccup-eid (:e (first (d/datoms db :avet :seon.schema/key :seon.ui/hiccup)))
            out-var-eids (into #{} (map :e) (d/datoms db :avet :seon.code.var/output hiccup-eid))]
        (is (some? hiccup-eid))
        (doseq [[sk expected-fns] entity-renderer-specs]
          (let [sk-eid (:e (first (d/datoms db :avet :seon.schema/key sk)))
                in-eids (into #{} (map :e) (d/datoms db :avet :seon.code.var/input sk-eid))
                found (into #{}
                            (map #(:seon.code.var/name (d/pull db '[:seon.code.var/name] %)))
                            (filter out-var-eids in-eids))]
            (is (some? sk-eid) (str "Missing schema " sk))
            (doseq [f expected-fns]
              (is (contains? found f) (str "Expected Datahike :input/:output discovery to find " f " for " sk)))))
        ;; Verify optional inputs on render-error-card (:seon.fix/entity) and render-var-full (:seon.code.form/src)
        (let [err-opt (set (map :seon.schema/key
                                (:seon.code.var/optional-input
                                 (d/pull db '[{:seon.code.var/optional-input [:seon.schema/key]}]
                                         [:seon.code.var/name 'seon.render.entities/render-error-card]))))
              var-opt (set (map :seon.schema/key
                                (:seon.code.var/optional-input
                                 (d/pull db '[{:seon.code.var/optional-input [:seon.schema/key]}]
                                         [:seon.code.var/name 'seon.render.entities/render-var-full]))))]
          (is (contains? err-opt :seon.fix/entity))
          (is (contains? var-opt :seon.code.form/src))))

      ;; 2. Verify strictly increasing :data-min-width AND rendered HTML/text sizes across every entity's levels!
      (doseq [[sk fns] entity-renderer-specs]
        (let [input (get sample-entities sk)
              levels (mapv (fn [fsym]
                             (let [res ((resolve fsym) input)
                                   h (:seon.ui/hiccup res)
                                   attrs (when (and (vector? h) (map? (second h))) (second h))
                                   min-w (:data-min-width attrs)
                                   html (render/hiccup->html h)
                                   txt (render/hiccup->text h)]
                               (is (map? res) (str fsym " must return ns-map"))
                               (is (vector? h) (str fsym " must return Hiccup vector under :seon.ui/hiccup"))
                               (is (pos-int? min-w) (str fsym " root element must carry integer :data-min-width"))
                               (is (not (str/blank? html)) (str fsym " must produce non-empty HTML"))
                               (is (not (str/blank? txt)) (str fsym " must produce non-empty Markdown text"))
                               {:fn fsym :min-w min-w :html-len (count html) :txt-len (count txt) :html html :txt txt}))
                           fns)]
          (is (apply < (map :min-w levels))
              (str "Expected strictly increasing :data-min-width for " sk ": " (mapv (juxt :fn :min-w) levels)))
          (is (apply < (map :html-len levels))
              (str "Expected strictly increasing HTML size for " sk ": " (mapv (juxt :fn :html-len) levels)))
          (is (apply < (map :txt-len levels))
              (str "Expected strictly increasing text size for " sk ": " (mapv (juxt :fn :txt-len) levels)))
          (when (= sk :seon.error/entity)
            (is (str/includes? (:html (last levels)) "ent-nested")
                "render-error-card must nest linked :seon.fix/entity cleanly")
            (is (str/includes? (:html (last levels)) "0% -&gt; 100% surfaced")))
          (when (= sk :seon.code.var/entity)
            (is (str/includes? (:html (last levels)) "defn render-var-chip")
                "render-var-full must render optional :seon.code.form/src cleanly")))))))

(deftest ^{:cost/ticks 100000000} live-engine-instruments-and-rule-bag-board-nesting-test
  (testing "set-viewport! clamps & transacts idempotently, ambient CSS replaces obsolete cockpit/board CSS, and zero legacy schemas/rules remain"
    (with-test-store [{:keys [conn]}]
      ;; 1. set-viewport! clamps to [200, 10000], transacts cleanly, and is idempotent on unchanged dimensions
      (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/view-mode :canvas}]})
      (let [st {:conn conn}
            r1 (actions/set-viewport! st 1440 900)
            tx1 (get-in r1 [:async :req :tx-data])]
        (is (seq tx1) "Initial set-viewport! must emit a transaction")
        (d/transact conn {:tx-data tx1})
        (let [u1 (d/pull @conn '[:seon.user/viewport-w :seon.user/viewport-h] [:seon.user/id :user/default])
              vs (rdata/user-view-state @conn)]
          (is (= 1440 (:seon.user/viewport-w u1)))
          (is (= 900 (:seon.user/viewport-h u1)))
          (is (= 1440 (:viewport-w vs)))
          (is (= 900 (:viewport-h vs))))

        ;; Idempotent no-op when dimensions match!
        (let [r2 (actions/set-viewport! st 1440 900)]
          (is (nil? (:async r2)) "Unchanged viewport dimensions must be an idempotent no-op"))

        ;; Clamping at lower and upper bounds via thin web/set-viewport! forwarder
        (let [r3 (web/set-viewport! st 42 99999)
              tx3 (get-in r3 [:async :req :tx-data])]
          (is (seq tx3))
          (d/transact conn {:tx-data tx3})
          (let [u3 (d/pull @conn '[:seon.user/viewport-w :seon.user/viewport-h] [:seon.user/id :user/default])]
            (is (= 200 (:seon.user/viewport-w u3)))
            (is (= 10000 (:seon.user/viewport-h u3))))))

      ;; 2. Debounced viewport-sync JS & Ambient CSS (zero obsolete gauge/dyno/lamp/board/query styles!)
      (let [css (styles/full-css)
            vp-js web-css/viewport-sync-js]
        (is (str/includes? vp-js "set-viewport!"))
        (is (str/includes? vp-js "180"))
        (is (not (str/includes? web-css/sticky-scroll-js "board-shell")))
        (doseq [cls [".sig-strip" ".sig-dot" ".sig-calm" ".sig-warm" ".sig-hot"
                     ".heat-bar" ".heat-fill" "@keyframes sig-pulse"
                     ".ent-chip" ".ent-card" ".ent-full"]]
          (is (str/includes? css cls) (str "Missing ambient CSS class: " cls)))
        (is (nil? (re-find #"\.(?:gauge-|dyno-|lamp-|board-|query-)" css))
            "All obsolete cockpit/instrument/board/query CSS must be deleted"))

      ;; 3. ZERO :seon.board/*, :seon.query/*, :seon.instrument/* schemas or rules remain!
      (let [db @conn
            legacy-ns? #(contains? #{"seon.board" "seon.query" "seon.instrument"} (namespace %))
            legacy-schemas (filterv legacy-ns? (map :v (d/datoms db :aevt :seon.schema/key)))
            legacy-dh-attrs (filterv #(and (qualified-keyword? %) (legacy-ns? %)) (keys (:schema db)))
            legacy-rules (filterv (fn [r]
                                    (let [s (pr-str r)]
                                      (or (str/includes? s "seon.board")
                                          (str/includes? s "seon.query")
                                          (str/includes? s "seon.instrument"))))
                                  (rule/load-rules db))]
        (is (empty? legacy-schemas) (str "Legacy schemas still present: " legacy-schemas))
        (is (empty? legacy-dh-attrs) (str "Legacy Datahike attrs still present: " legacy-dh-attrs))
        (is (empty? legacy-rules) (str "Legacy rules still present: " (mapv :seon.rule/id legacy-rules)))))))

(deftest ^{:cost/ticks 60000000} canvas-history-and-rule-board-preserved-during-chat-test
  (testing "Pinned entities and chat messages record clean Datahike d/as-of Canvas history steps without legacy boards"
    (with-test-store [{:keys [conn]}]
      (let [tx1 (:max-tx (:db-after (d/transact conn {:tx-data [{:seon.task/id :task/hist-1
                                                                 :seon.task/title "First Task"
                                                                 :seon.task/done? false}
                                                                {:seon.agent/id :agent/hist-test
                                                                 :seon.agent/branch :main
                                                                 :seon.agent/role :worker
                                                                 :seon.agent/label "Hist Agent"
                                                                 :seon.agent/pinned [[:seon.task/id :task/hist-1]]}]})))
            tx2 (:max-tx (:db-after (d/transact conn {:tx-data [{:seon.task/id :task/hist-2
                                                                 :seon.task/title "Second Task"
                                                                 :seon.task/done? false}
                                                                {:seon.agent/id :agent/hist-test
                                                                 :seon.agent/pinned [[:seon.task/id :task/hist-1]
                                                                                     [:seon.task/id :task/hist-2]]}]})))
            tx3 (:max-tx (:db-after (d/transact conn {:tx-data [{:seon.agent.message/id (random-uuid)
                                                                 :seon.agent.message/seq 1
                                                                 :seon.agent.message/from :user
                                                                 :seon.agent.message/to :agent/hist-test
                                                                 :seon.agent.message/type :chat
                                                                 :seon.agent.message/body "test"}]})))
            db @conn
            txs (rdata/canvas-history-txs db db :agent/hist-test)
            ctx-live (rcanvas/build-canvas-ctx db db db :agent/hist-test {})
            html-live (render/hiccup->html (rcanvas/default-canvas ctx-live))
            adb1 (d/as-of db tx1)
            ctx-past (rcanvas/build-canvas-ctx db adb1 db :agent/hist-test {:as-of-tx tx1})
            html-past (render/hiccup->html (rcanvas/default-canvas ctx-past))]
        (is (= [tx1 tx2 tx3] txs))
        (is (str/includes? html-live "canvas-timeline-bar"))
        (is (str/includes? html-live "canvas-pending"))
        (is (str/includes? html-past "Time-travel"))
        (is (not (str/includes? html-past "canvas-pending")))))))
