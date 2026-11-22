(ns seon.distributed-render-test
  "Proves general distributed rendering, eval-triggered schema dispatch, live breadcrumbs,
   data-driven function/doc discovery, executable :seon.query/entity, and interactive
   :seon.board/entity projection onto default-canvas with ZERO fragile custom agent code."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.board :as board]
            [seon.code :as code]
            [seon.model.wire :as mwire]
            [seon.query :as squery]
            [seon.render :as render]
            [seon.render.breadcrumbs :as crumbs]
            [seon.render.canvas :as rcanvas]
            [seon.rule.context :as rctx]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]
            [seon.web.actions :as actions]
            [seon.instruments :as inst]
            [seon.render.data]))

(defn sample-metric-render
  "Test-only distributed schema renderer attached dynamically at runtime to `:seon.demo.metric/entity`."
  {:malli/schema [:=> [:cat :map :map] :any]}
  [{:keys [mode]} m]
  [:div {:class "card sample-metric-card" :data-mode (name (or mode :human))}
   [:strong (str "METRIC-RENDER:" (:seon.demo.metric/id m))]
   [:span (str " val=" (:seon.demo.metric/score m))]])

(deftest ^{:cost/ticks 40000000} pure-breadcrumbs-query-board-generative-test
  (testing "Pure helpers in seon.render.breadcrumbs, seon.query, and seon.board pass seed=42 generative checks"
    (doseq [v ['seon.render.breadcrumbs/entity-lookup-ref
              'seon.query/query-summary
              'seon.board/active-section
              'seon.board/board-summary]]
      (let [res (schema/stress-var nil v {:seed 42 :samples 30})]
        (is (:ok? res) (str "Generative check failed for " v ": " (pr-str res)))))))

(deftest ^{:cost/ticks 120000000} cross-agent-distributed-schema-eval-and-breadcrumbs-test
  (testing "Brand-new schemas registered outside an agent's scope render automatically on eval & pin with live breadcrumbs + function/doc discovery"
    (with-test-store [{:keys [conn]}]
      ;; 1. Register TWO brand-new runtime schemas outside seon.health's scope:
      ;;    (a) :seon.demo.metric/entity WITH custom :seon.render/hiccup renderer
      ;;    (b) :seon.demo.note/entity WITHOUT any custom renderer (proves 4-stage self-printer fallback!)
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

      ;; Verify :seon.schema/render was automatically extracted from :seon.render/hiccup!
      (is (= 'seon.distributed-render-test/sample-metric-render
             (:seon.schema/render (d/pull @conn '[:seon.schema/render] [:seon.schema/key :seon.demo.metric/entity]))))

      ;; 2. Import a public function whose :malli/schema consumes :seon.demo.metric/entity + a doc citing it + live instances
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

        ;; 3. Eval return rendering: BOTH map and lookup-ref trigger custom renderer + live breadcrumbs + discovered fn + citing doc!
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

        ;; 4. Unrelated agent (:ns/seon.health) rule projection renders BOTH entities with ZERO custom rules!
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
          (is (some? metric-tile) "Unrelated agent must project newly registered schema with custom renderer")
          (is (= 'seon.distributed-render-test/sample-metric-render (:view-fn metric-tile)))
          (is (true? (get-in metric-tile [:params :show-breadcrumbs?])))
          (is (some? note-tile) "Unrelated agent must project schema without custom renderer via fallback printer")
          (is (= 'seon.render/render-entity-value (:view-fn note-tile)))
          (is (some #(= 'seon.demo.metrics/double-metric (get-in % [:val :seon.code.var/name])) fn-tiles)
              "Eval return must trigger :rule.expand/eval-schema-fn to discover consuming function")
          (is (some #(= "docs/demo-metric.md#overview" (get-in % [:val :seon.code.doc/id])) doc-tiles)
              "Eval return must trigger :rule.expand/eval-schema-doc to surface citing doc")

          ;; Render BOTH :mode :human HTML and :mode :agent Wire text from the same tiles!
          (let [m-html (render/hiccup->html (render/render-tile-node db metric-tile {:mode :human :main-db db :agent-id :ns/seon.health}))
                n-html (render/hiccup->html (render/render-tile-node db note-tile {:mode :human :main-db db :agent-id :ns/seon.health}))
                m-wire (render/hiccup->text (render/render-tile-node db (by-schema (:all-tiles derived-wire) :seon.demo.metric/entity) {:mode :agent :main-db db}))]
            (is (str/includes? m-html "METRIC-RENDER::m/latency"))
            (is (str/includes? m-html "seon.demo.metrics/double-metric"))
            (is (str/includes? m-html "Demo Metric Overview"))
            (is (str/includes? n-html "Fallback rendered cleanly"))
            (is (str/includes? n-html "4-stage self-printer"))
            (is (str/includes? m-wire "METRIC-RENDER::m/latency"))
            (is (str/includes? m-wire "seon.demo.metrics/double-metric"))))))))

(deftest ^{:cost/ticks 120000000} live-query-and-board-canvas-projection-test
  (testing "All 7 :seon.query/entity kinds run live with datahike.reads, and :seon.board/entity projects to default-canvas with zero bespoke :seon.agent/render-fn"
    (with-test-store [{:keys [conn]}]
      (d/transact
       conn
       {:tx-data
        [{:seon.task/id :task/live-demo :seon.task/title "Demo Task" :seon.task/done? false}
         {:seon.code.var/name 'seon.query/run-query :seon.code.var/ns 'seon.query
          :seon.code.var/private? false :seon.code.var/test? false
          :seon.code.var/schema-refs [:seon.query/entity]}
         {:seon.query/id :q/datalog :seon.query/title "Live Datalog" :seon.query/kind :datalog
          :seon.query/spec {:q '[:find ?tid ?done :where [?t :seon.task/id ?tid] [?t :seon.task/done? ?done]]}}
         {:seon.query/id :q/slice :seon.query/title "Live Slice" :seon.query/kind :index-slice
          :seon.query/spec {:patterns [{:label "Tasks" :pattern [nil :seon.task/id nil]}]}}
         {:seon.query/id :q/trace :seon.query/title "Live Rule Trace" :seon.query/kind :rule-trace
          :seon.query/spec {:agent :agent/guide :target :html}}
         {:seon.query/id :q/fp :seon.query/title "Live Fingerprint" :seon.query/kind :schema-fingerprint
          :seon.query/spec {:entity [:seon.task/id :task/live-demo]}}
         {:seon.query/id :q/health :seon.query/title "Live Health" :seon.query/kind :health
          :seon.query/spec {}}
         {:seon.query/id :q/reads :seon.query/title "Live Reads" :seon.query/kind :walk-reads
          :seon.query/spec {:entity [:seon.task/id :task/live-demo]}}
         {:seon.query/id :q/pull :seon.query/title "Live Pull" :seon.query/kind :entity-pull
          :seon.query/spec {:entity [:seon.task/id :task/live-demo]}}
         {:seon.board/id :board/live :seon.board/title "Live Architecture Board"
          :seon.board/summary "Rendered purely via distributed schema + rule projection."
          :seon.board/active :sec/one :seon.board/view-as :human
          :seon.board/sections [{:id :sec/one :title "Section One" :summary "Queries live"
                                 :refs [[:seon.query/id :q/datalog] [:seon.query/id :q/slice]]}
                                {:id :sec/two :title "Section Two" :summary "Trace & Pull"
                                 :refs [[:seon.query/id :q/trace] [:seon.query/id :q/pull]]}]}
         {:seon.agent/id :agent/guide :seon.agent/branch :main :seon.agent/role :worker
          :seon.agent/pinned [[:seon.board/id :board/live]]}
         {:seon.agent/id :ns/seon.health :seon.agent/branch :main :seon.agent/role :worker}]})

      (let [db @conn]
        ;; 1. Verify all 7 query kinds execute cleanly and track datahike.reads
        (doseq [qid [:q/datalog :q/slice :q/trace :q/fp :q/health :q/reads :q/pull]]
          (let [q-ent (d/pull db '[*] [:seon.query/id qid])
                res (squery/run-query db db q-ent)]
            (is (:ok? res) (str "Query " qid " failed: " (pr-str res)))
            (is (number? (:ms res)))
            (is (pos? (long (:reads-count res))) (str "Expected datahike.reads > 0 for " qid))))

        ;; 2. Verify default-canvas projects :seon.board/entity with NO custom :seon.agent/render-fn!
        (let [html (render/hiccup->html (rcanvas/agent-canvas db {:conn conn :viewing-id :agent/guide}))]
          (is (str/includes? html "Live Architecture Board"))
          (is (str/includes? html "Live Datalog"))
          (is (str/includes? html ":task/live-demo"))
          (is (str/includes? html "seon.query/render"))
          (is (str/includes? html "seon.query/run-query")))

        ;; 3. Exercise general callable actions: set-board-state! (:split + :inspect + close), set-query-param!, pin-to-agent!
        (let [st {:conn conn}
              a1 (actions/set-board-state! st :board/live {:active :sec/two :view-as :split :inspect [:seon.task/id :task/live-demo]})
              _ (d/transact conn {:tx-data (get-in a1 [:async :req :tx-data])})
              html-split (render/hiccup->html (rcanvas/agent-canvas @conn {:conn conn :viewing-id :agent/guide}))]
          (is (str/includes? html-split "Live Entity Inspector"))
          (is (str/includes? html-split "Human HTML (same schema renderer)"))
          (is (str/includes? html-split "Model Wire Text (same Hiccup"))
          (is (str/includes? html-split "Live Rule Trace"))

          ;; Close inspector cleanly via retract
          (let [a2 (actions/set-board-state! st :board/live {:inspect nil})
                _ (d/transact conn {:tx-data (get-in a2 [:async :req :tx-data])})]
            (is (nil? (:seon.board/inspect (d/pull @conn '[:seon.board/inspect] [:seon.board/id :board/live])))))

          ;; Update query parameter via set-query-param!
          (let [;; Verify step-walk captures lazy-for & nested run-query reads so set-query-param! triggers immediate SSE patch!
              w0 (render/step-walk (render/empty-walk) @conn nil
                                   [{:id "seon-view-unit" :comp #'rcanvas/agent-canvas :args [{:conn conn :viewing-id :agent/guide}]}])
              a3 (actions/set-query-param! st :q/trace :target :wire)
              tx-res (d/transact conn {:tx-data (get-in a3 [:async :req :tx-data])})
              w1 (render/step-walk (:walk w0) @conn (:tx-data tx-res)
                                   [{:id "seon-view-unit" :comp #'rcanvas/agent-canvas :args [{:conn conn :viewing-id :agent/guide}]}])]
          (is (= :wire (get-in (d/pull @conn '[:seon.query/spec] [:seon.query/id :q/trace]) [:seon.query/spec :target])))
          (is (= 1 (:rerendered w1)) "set-query-param! must invalidate step-walk via lazy-for + forwarded datahike.reads")
          (is (seq (:patches w1)) "set-query-param! must immediately emit Datastar SSE patch")
          (is (str/includes? (first (:patches w1)) "target :wire")))

          ;; Cross-agent pin via pin-to-agent!
          (let [a4 (actions/pin-to-agent! st :ns/seon.health [:seon.query/id :q/slice] true)
                _ (d/transact conn {:tx-data (get-in a4 [:async :req :tx-data])})
                db2 @conn
                ws-html (render/hiccup->html (rcanvas/agent-workspace db2 {:conn conn :viewing-id :ns/seon.health}))]
            (is (str/includes? ws-html "Live Slice"))
            (is (str/includes? ws-html "seon.query/run-query"))))))))

(deftest ^{:cost/ticks 120000000} live-engine-instruments-and-rule-bag-board-nesting-test
  (testing "All 6 live engine instruments render real Datahike telemetry as SVG + Agent text + eval breadcrumbs, and seon.board uses derive-bag for rule-driven tree nesting"
    (with-test-store [{:keys [conn]}]
      (let [src "(ns seon.demo.nest \"Demo ns\")\n(defn leaf-op {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"
            tx-code (code/file-import-tx @conn "src/seon/demo/nest.clj" src false)]
        (d/transact conn
                    {:tx-data (vec (concat tx-code
                                           [{:seon.agent/id :agent/guide :seon.agent/branch :main :seon.agent/role :worker}
                                            {:seon.agent.eval/id #uuid "11111111-1111-4111-8111-111111111111"
                                             :seon.agent.eval/agent :agent/guide :seon.agent.eval/turn 1
                                             :seon.agent.eval/handle 'result/e1 :seon.agent.eval/code "(+ 1 1)"
                                             :seon.agent.eval/ms 14.5 :seon.agent.eval/ticks 1200000
                                             :seon.agent.eval/bytes 4194304 :seon.agent.eval/prompt-tokens 1000
                                             :seon.agent.eval/cache-hit-tokens 880}
                                            {:seon.board/id :board/nest-proof :seon.board/title "Rule-Organized Board"
                                             :seon.board/active :sec/nest :seon.board/view-as :human
                                             :seon.board/sections [{:id :sec/nest :title "Nested Bag"
                                                                    :refs [[:seon.code.ns/name 'seon.demo.nest]
                                                                           [:seon.code.var/name 'seon.demo.nest/leaf-op]]}]}]))})
        (let [db @conn]
          ;; 1. All 6 live instruments read real datoms & render SVG (:human) + compact text (:agent)
          (doseq [k [:tachometer :temperature :fuel-cache :oil-pressure :warning-lamps :eval-feed]]
            (let [v (inst/read-instrument db k)
                  h-html (render/hiccup->html (render/render-value db v {:mode :human :main-db db}))
                  a-txt (render/hiccup->text (render/render-value db v {:mode :agent :main-db db}))]
              (is (qualified-keyword? (:seon/schema (meta v))))
              (is (not (str/blank? h-html)))
              (is (not (str/blank? a-txt)))
              (when (contains? #{:tachometer :temperature :fuel-cache} k)
                (is (str/includes? h-html "<svg") (str "Expected SVG dial for " k)))))

          ;; 2. Eval output returning an instrument triggers its renderer + live breadcrumbs!
          (let [ev-out (mwire/format-eval-output
                        {:handle 'result/e7 :ok? true :ms 2.1 :ticks 9000
                         :db db :main-db db
                         :value (pr-str (inst/read-tachometer db))})]
            (is (str/includes? ev-out "Tachometer:"))
            (is (str/includes? ev-out "; Breadcrumbs · :seon.instrument/tachometer")))

          ;; 3. Check-Engine Warning Lamp turns :warn when a real :seon.error/entity opens and returns :ok when resolved
          (let [w0 (inst/read-warning-lamps db)
                lamp-status (fn [w id] (:status (first (filter #(= id (:id %)) (:lamps w)))))]
            (is (= :ok (lamp-status w0 :open-err)))
            (d/transact conn {:tx-data [{:seon.error/id #uuid "22222222-2222-4222-8222-222222222222"
                                         :seon.error/kind :instrument/thermal-check :seon.error/severity :warning
                                         :seon.error/status :open :seon.error/agent :agent/guide
                                         :seon.error/message "Live lamp check" :seon.error/at 1000}]})
            (is (= :warn (lamp-status (inst/read-warning-lamps @conn) :open-err)))
            (d/transact conn {:tx-data [{:seon.error/id #uuid "22222222-2222-4222-8222-222222222222"
                                         :seon.error/status :resolved}]})
            (is (= :ok (lamp-status (inst/read-warning-lamps @conn) :open-err))))

          ;; 4. seon.board/render uses derive-bag so a flat ns+var bag self-organizes into a nested tree!
          (let [b-ent (d/pull @conn '[*] [:seon.board/id :board/nest-proof])
                b-html (render/hiccup->html (board/render {:db @conn :main-db @conn :mode :human} b-ent))]
            (is (str/includes? b-html "tile-tree-children")
                "seon.board/render must route :refs through derive-bag so rules nest var under owning ns")
            (is (str/includes? b-html "leaf-op"))))))))

(deftest ^{:cost/ticks 60000000} canvas-history-and-rule-board-preserved-during-chat-test
  (testing "Pinned rule-projected boards stay visible during pending/prose chat AND record Datahike d/as-of Canvas history steps"
    (with-test-store [{:keys [conn]}]
      (let [tx1 (:max-tx (:db-after (d/transact conn {:tx-data [{:seon.board/id :test/hist-board
                                                                 :seon.board/title "Hist Board"
                                                                 :seon.board/active :step/s1
                                                                 :seon.board/view-as :human
                                                                 :seon.board/sections [{:id :step/s1 :title "Tab One" :summary "First tab"}
                                                                                       {:id :step/s2 :title "Tab Two" :summary "Second tab"}]}
                                                                {:seon.agent/id :agent/hist-test
                                                                 :seon.agent/branch :main
                                                                 :seon.agent/role :worker
                                                                 :seon.agent/label "Hist Agent"
                                                                 :seon.agent/pinned [[:seon.board/id :test/hist-board]]}]})))
            tx2 (:max-tx (:db-after (d/transact conn {:tx-data [{:seon.board/id :test/hist-board
                                                                 :seon.board/active :step/s2}]})))
            tx3 (:max-tx (:db-after (d/transact conn {:tx-data [{:seon.agent.message/id (random-uuid)
                                                                 :seon.agent.message/seq 1
                                                                 :seon.agent.message/from :user
                                                                 :seon.agent.message/to :agent/hist-test
                                                                 :seon.agent.message/type :chat
                                                                 :seon.agent.message/body "test"}]})))
            db @conn
            txs (seon.render.data/canvas-history-txs db db :agent/hist-test)
            ctx-live (seon.render.canvas/build-canvas-ctx db db db :agent/hist-test {})
            html-live (seon.render/hiccup->html (seon.render.canvas/default-canvas ctx-live))
            adb1 (d/as-of db tx1)
            ctx-past (seon.render.canvas/build-canvas-ctx db adb1 db :agent/hist-test {:as-of-tx tx1})
            html-past (seon.render/hiccup->html (seon.render.canvas/default-canvas ctx-past))]
        (is (= [tx1 tx2 tx3] txs))
        (is (str/includes? html-live "canvas-timeline-bar"))
        (is (str/includes? html-live "canvas-pending"))
        (is (str/includes? html-live "Hist Board"))
        (is (str/includes? html-live "Second tab"))
        (is (str/includes? html-past "Time-travel"))
        (is (str/includes? html-past "First tab"))
        (is (not (str/includes? html-past "canvas-pending")))))))