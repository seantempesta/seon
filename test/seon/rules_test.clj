(ns seon.rules-test
  "Unit, incremental-reactivity, GTA-6 graph-to-tree, and end-to-end situational context tests for `seon.rules` & `seon.rules.context`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.datom :as datom]
            [seon.code :as code]
            [seon.code.files :as files]
            [seon.render :as render]
            [seon.rules :as rules]
            [seon.rules.context :as rctx]
            [seon.test-fixture :refer [with-test-store]]
            [seon.schema.datahike :as sdh]))

(deftest ^{:cost/ticks 20000000} rules-engine-clauses-hierarchy-and-diff-test
  (testing "Stratified rules support unification, wildcards, :not, :optional, :isa, :count, :guard, :bind, provenance, and A/B diff-sessions"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/alpha.clj"
                                                      "(ns seon.demo.alpha \"Alpha demo\")\n(defn target-fn \"Target doc\" [x] x)"
                                                      false)})
      (let [db @conn
            wm0 (rules/add-facts (rules/empty-wm)
                                 [[:ag/1 :rel/mentions-var :v1]
                                  [:ag/1 :rel/tree-discovered :v1]
                                  [:ag/2 :rel/tree-discovered :v1]
                                  [:v1 :ctx/conn-kind :rel/mentions-var]
                                  [:v1 :ctx/kind :seon.code.var/entity]]
                                 {:rule :seed :stratum 0})
            rs-a [{:seon.rule/id :r/isa-strong :seon.rule/stratum 10 :seon.rule/enabled? true :seon.rule/target :any
                   :seon.rule/when '[[?e :ctx/conn-kind ?ck]
                                     [:isa ?ck :rel/strong-signal]
                                     [:count ?n [?a :rel/tree-discovered ?e]]
                                     [:guard (>= ?n 2)]
                                     [:bind ?lbl (str "overlap-" ?n)]]
                   :seon.rule/assert '[[?e :view/badge ?lbl]
                                       [?e :view/layout :expanded-card]]}
                  {:seon.rule/id :r/neg-check :seon.rule/stratum 20 :seon.rule/enabled? true :seon.rule/target :any
                   :seon.rule/when '[[?e :ctx/kind :seon.code.var/entity]
                                     [:not [?e :view/suppressed? true]]
                                     [:db :branch [?dbe :seon.code.var/name seon.demo.alpha/target-fn ?tx]]]
                   :seon.rule/assert '[[?e :ctx/db-eid ?dbe]
                                       [?e :ctx/db-tx ?tx]]}]
            rs-b (assoc-in rs-a [0 :seon.rule/assert 1 2] :compact-row)
            res-a (rules/fire-rules wm0 {:main db :branch db} rs-a :wire)
            res-b (rules/fire-rules wm0 {:main db :branch db} rs-b :wire)
            diff (rules/diff-sessions res-a res-b)]
        (is (= "overlap-2" (rules/wm-scalar (:wm res-a) :v1 :view/badge)))
        (is (= :expanded-card (rules/wm-scalar (:wm res-a) :v1 :view/layout)))
        (is (integer? (rules/wm-scalar (:wm res-a) :v1 :ctx/db-eid)))
        (is (pos? (long (rules/wm-scalar (:wm res-a) :v1 :ctx/db-tx))))
        (is (= 1 (:added-count diff)))
        (is (= 1 (:removed-count diff)))
        (is (some #(= [:v1 :view/layout :compact-row] %) (:added diff)))))))

(deftest ^{:cost/ticks 20000000} incremental-step-rules-and-rule-edit-test
  (testing "step-rules skips 100% of rules on unrelated tx-datoms and re-fires ONLY the edited rule on rule update"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (concat
                                  (code/file-import-tx @conn "src/seon/demo/inc.clj"
                                                       "(ns seon.demo.inc \"Inc demo\")\n(defn f [x] x)"
                                                       false)
                                  [{:seon.agent/id :ag/inc :seon.agent/branch :main :seon.agent/role :worker
                                    :seon.agent/scope 'seon.demo.inc :seon.agent/goal "Test"}])})
      (let [db @conn
            rs [{:seon.rule/id :r/scope :seon.rule/stratum 10 :seon.rule/enabled? true :seon.rule/target :any
                 :seon.rule/when '[[?v :ctx/viewer ?aid]
                                   [:db :main [?ag :seon.agent/id ?aid]]
                                   [:db :main [?ag :seon.agent/scope ?sc]]
                                   [:db :branch [?ns :seon.code.ns/name ?sc]]]
                 :seon.rule/assert '[[?v :rel/scope ?ns]]}
                {:seon.rule/id :r/layout :seon.rule/stratum 20 :seon.rule/enabled? true :seon.rule/target :any
                 :seon.rule/when '[[?v :rel/scope ?ns]]
                 :seon.rule/assert '[[?ns :view/layout :expanded-card]]}]
            base-wm (rules/add-fact (rules/empty-wm) [:viewer :ctx/viewer :ag/inc] {:rule :seed :stratum 0})
            s1 (rules/step-rules nil base-wm {:main db :branch db} rs nil :wire)
            unrelated [(datom/->Datom 999999999 :seon.code.doc/body "noise" 536871999 true)]
            s2 (rules/step-rules s1 base-wm {:main db :branch db} rs unrelated :wire)
            rs-edited (assoc-in rs [1 :seon.rule/assert 0 2] :summary-strip)
            s3 (rules/step-rules s2 base-wm {:main db :branch db} rs-edited [] :wire)]
        (is (= 2 (:recomputed s1)))
        (is (= 0 (:skipped s1)))
        (is (= 0 (:recomputed s2)))
        (is (= 2 (:skipped s2)))
        (is (= 1 (:recomputed s3)))
        (is (= 1 (:skipped s3)))
        (is (= #{:summary-strip} (get-in (:wm s3) [:aev :view/layout (first (rules/wm-entities-with (:wm s3) :view/layout))])))))))

(defn- ensure-rule-schema! [conn]
  (when-not (contains? (:schema @conn) :seon.rule/id)
    (let [forms {:seon.rule/id [:and {:seon.db/identity true :seon.db/content-id true} :qualified-keyword]
                 :seon.rule/doc :string
                 :seon.rule/category [:and {:seon.db/index true} [:enum :seed :expand :diagnosis :rank :present :layout :health]]
                 :seon.rule/stratum [:and {:seon.db/index true} [:int {:min 0 :max 1000}]]
                 :seon.rule/enabled? [:and {:seon.db/index true} :boolean]
                 :seon.rule/target [:enum :any :html :wire]
                 :seon.rule/when [:vector :any]
                 :seon.rule/assert [:vector :any]
                 :seon.rule/entity [:map {:seon.db/attributes true}
                                    [:seon.rule/id :seon.rule/id]
                                    [:seon.rule/doc {:optional true} :seon.rule/doc]
                                    [:seon.rule/category {:optional true} :seon.rule/category]
                                    [:seon.rule/stratum :seon.rule/stratum]
                                    [:seon.rule/enabled? :seon.rule/enabled?]
                                    [:seon.rule/target {:optional true} :seon.rule/target]
                                    [:seon.rule/when :seon.rule/when]
                                    [:seon.rule/assert :seon.rule/assert]]
                 :seon.agent/curated [:vector :any]
                 :seon.task/curated [:vector :any]}
          have (:schema @conn)
          decls (filterv #(not (contains? have (:db/ident %)))
                         (:declarations (schema/compile-registry (merge (:forms @schema/base-registry) forms))))]
      (when (seq decls)
        (d/transact conn {:tx-data decls})))))

(deftest ^{:cost/ticks 30000000} derive-bag-graph-to-tree-and-rules-persistence-test
  (testing "Flat Datahike entity bag self-organizes into a 3-level ns -> var -> covering-test tree, and rules.edn round-trips losslessly"
    (with-test-store [{:keys [conn]}]
      (let [seed-rules (do (ensure-rule-schema! conn) (rctx/seed-rules-fallback))]
        (d/transact conn {:tx-data (concat
                                    seed-rules
                                    (code/file-import-tx @conn "src/seon/demo/tree.clj"
                                                         "(ns seon.demo.tree \"Tree demo\")\n(defn leaf-op [x] (inc x))" false)
                                    (code/file-import-tx @conn "test/seon/demo_tree_test.clj"
                                                         "(ns seon.demo-tree-test (:require [clojure.test :refer [deftest is]] [seon.demo.tree :as dt]))\n(deftest leaf-op-test (is (= 2 (dt/leaf-op 1))))" false))})
        (let [db @conn
              bag (rctx/derive-bag db
                                   [[:seon.code.ns/name 'seon.demo.tree]
                                    [:seon.code.var/name 'seon.demo.tree/leaf-op]
                                    [:seon.code.var/name 'seon.demo-tree-test/leaf-op-test]]
                                   {:target :html})
              root-ns (first (:tree bag))
              child-var (first (:children root-ns))
              grandchild-test (first (:children child-var))
              rendered-edn (files/render-rules-file db "resources/seon/rules.edn")
              reimported (files/import-rules-tx db "resources/seon/rules.edn" rendered-edn)]
          (is (= 1 (count (:tree bag))))
          (is (= "tile-seon.code.ns-seon-demo-tree" (:id root-ns)))
          (is (= "tile-seon.code.var-seon-demo-tree-leaf-op" (:id child-var)))
          (is (= "tile-seon.code.var-seon-demo-tree-test-leaf-op-test" (:id grandchild-test)))
          (is (contains? (:tags grandchild-test) :tag/covering-test))
          ;; Render the nested tree node cleanly into both HTML and Markdown text!
          (let [h (render/render-tile-node db root-ns {:mode :human :main-db db})
                html (render/hiccup->html h)
                txt (render/hiccup->text h)]
            (is (str/includes? html "leaf-op"))
            (is (str/includes? html "leaf-op-test"))
            (is (str/includes? txt "leaf-op")))
          ;; Declarative persistence round-trip
          (is (string? rendered-edn))
          (is (= (count seed-rules) (count reimported))))))))

(deftest ^{:cost/ticks 40000000} situational-context-grounding-search-recency-and-overlap-test
  (testing "Rules ground task sub-ns & schemas, demote broad parent ns, trigger search + recency tail on underspecified tasks, and boost tree overlap"
    (with-test-store [{:keys [conn]}]
      ;; Seed starter rules + demo namespaces/vars/tests/tasks/calls
      (let [seed-rules (do (ensure-rule-schema! conn) (rctx/seed-rules-fallback))
            base-tx (concat
                     seed-rules
                     (code/file-import-tx @conn "src/seon/demo.clj"
                                          "(ns seon.demo \"Parent demo\")\n(defn parent-fn [x] x)" false)
                     (code/file-import-tx @conn "src/seon/demo/target.clj"
                                          "(ns seon.demo.target \"Target sub-ns\")\n(defn alpha-widget \"Builds widget layout\" [x] x)" false)
                     (code/file-import-tx @conn "test/seon/demo_test.clj"
                                          "(ns seon.demo-test (:require [clojure.test :refer [deftest is]] [seon.demo.target :as t]))\n(deftest alpha-widget-test (is (= 1 (t/alpha-widget 1))))" false))]
        (d/transact conn {:tx-data base-tx})
        ;; Second transaction AFTER bootstrap import: add a recently modified var so recency rule triggers!
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/recent.clj"
                                                        "(ns seon.demo.recent \"Recent module\")\n(defn slow-debug-wire-profiler \"Profiles slow debug wire allocations\" [x] x)" false)})
        ;; Create 3 test scenarios: (1) targeted sub-ns + schema task, (2) underspecified vague task, (3) tree-overlap task
        (d/transact conn {:tx-data
                          [{:seon.task/id :demo/targeted
                            :seon.task/title "Audit seon.demo.target and inspect open tasks"
                            :seon.task/scope 'seon.demo
                            :seon.task/plan "Check seon.demo.target functions and open tasks."
                            :seon.task/done? false}
                           {:seon.agent/id :ag/targeted :seon.agent/branch :main :seon.agent/role :worker
                            :seon.agent/scope 'seon.demo :seon.agent/task :demo/targeted :seon.agent/goal "Work"}
                           {:seon.task/id :demo/vague
                            :seon.task/title "Fix slow debug wire profiler allocations"
                            :seon.task/scope 'seon.demo
                            :seon.task/done? false}
                           {:seon.agent/id :ag/vague :seon.agent/branch :main :seon.agent/role :worker
                            :seon.agent/scope 'seon.demo :seon.agent/task :demo/vague :seon.agent/goal "Investigate"}
                           {:seon.agent/id :ag/coord :seon.agent/branch :main :seon.agent/role :coordinator
                            :seon.agent/scope 'seon.demo :seon.agent/goal "Coord"
                            :seon.agent/pinned [[:seon.health/summary :main]]}
                           {:seon.agent.call/id (random-uuid) :seon.agent.call/caller :ag/coord :seon.agent.call/callee :ag/c1
                            :seon.agent.call/prompt "Audit" :seon.agent.call/status :resolved
                            :seon.agent.call/result {:found 'seon.demo.target/alpha-widget}}
                           {:seon.agent.call/id (random-uuid) :seon.agent.call/caller :ag/coord :seon.agent.call/callee :ag/c2
                            :seon.agent.call/prompt "Verify" :seon.agent.call/status :resolved
                            :seon.agent.call/result {:confirmed 'seon.demo.target/alpha-widget}}]})
        (let [db @conn
              ;; 1. Targeted task promotes seon.demo.target, demotes seon.demo to :summary-strip, surfaces :seon.task/entity schema, dedups lineage plan
              exp1 (rctx/explain-context db db :ag/targeted {:target :wire})
              by-id1 (into {} (map (juxt :id identity)) (:items exp1))
              ;; 2. Underspecified vague task triggers search -> recently modified slow-debug-wire-profiler promoted to :ephemeral-tail with :tag/recent & :show-diff? true!
              exp2 (rctx/explain-context db db :ag/vague {:target :wire})
              recent-item (first (filter #(str/includes? (:id %) "slow-debug-wire-profiler") (:items exp2)))
              ;; 3. Coordinator with 2 child calls discovering seon.demo.target/alpha-widget -> tree overlap promotes var with :tag/tree-overlap + :show-source? true!
              ;;    And in :target :html, pinned health panel uses :compact-summary layout!
              ag-coord (d/pull db '[*] [:seon.agent/id :ag/coord])
              der3 (rctx/derive-context db db ag-coord [] (fn [_] {:ok? true :problems [] :counts {}}) {:target :html})
              overlap-item (first (filter #(str/includes? (:id %) "alpha-widget") (:all-tiles der3)))
              health-item (first (filter #(= :seon.health/report (:schema %)) (:all-tiles der3)))]
          (is (= :expanded-card (:layout (get by-id1 "tile-seon.code.ns-seon-demo-target"))))
          (is (contains? (:tags (get by-id1 "tile-seon.code.ns-seon-demo-target")) :tag/mentioned))
          (is (= :summary-strip (:layout (get by-id1 "tile-seon.code.ns-seon-demo"))))
          (is (contains? by-id1 "tile-seon.schema-seon-task-entity"))
          (is (true? (get-in by-id1 ["tile-lineage-ag-targeted" :params :omit-current-plan?])))
          (is (some? recent-item))
          (is (= :ephemeral-tail (:zone recent-item)))
          (is (contains? (:tags recent-item) :tag/recent))
          (is (true? (get-in recent-item [:params :show-diff?])))
          (is (some? overlap-item))
          (is (contains? (:tags overlap-item) :tag/tree-overlap))
          (is (true? (get-in overlap-item [:params :show-source?])))
          (is (= :compact-summary (:layout health-item))))))))