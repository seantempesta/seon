(ns seon.rules-test
  "Unit, incremental-reactivity, unified candidate-pipeline, and end-to-end situational context tests for `seon.rule` & `seon.ctx`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.datom :as datom]
            [seon.code :as code]
            [seon.code.files :as files]
            [seon.ctx :as ctx]
            [seon.render :as render]
            [seon.render.entities.work :as rwork]
            [seon.render.wire :as rwire]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.test-fixture :refer [with-test-store]]))

(deftest ^{:cost/ticks 30000000} rules-engine-clauses-hierarchy-and-diff-test
  (testing "Stratified native Datahike d/q rules support unification, :not, isa?, count, str, provenance, and A/B diff-sessions"
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
                   :seon.rule/query '[:find ?e (count ?a) :keys e n
                                      :in $wm ?hierarchy
                                      :where [$wm ?e :ctx/conn-kind ?ck]
                                             [(clojure.core/isa? ?hierarchy ?ck :rel/strong-signal)]
                                             [$wm ?a1 :rel/tree-discovered ?e]
                                             [$wm ?a2 :rel/tree-discovered ?e]
                                             [(not= ?a1 ?a2)]
                                             [$wm ?a :rel/tree-discovered ?e]]
                   :seon.rule/assert '[[?e :ctx/overlap-n ?n]
                                       [?e :view/layout :expanded-card]]}
                  {:seon.rule/id :r/badge-lbl :seon.rule/stratum 15 :seon.rule/enabled? true :seon.rule/target :any
                   :seon.rule/query '[:find ?e ?lbl
                                      :in $wm
                                      :where [$wm ?e :ctx/overlap-n ?n]
                                             [(str "overlap-" ?n) ?lbl]]
                   :seon.rule/assert '[[?e :view/badge ?lbl]]}
                  {:seon.rule/id :r/neg-check :seon.rule/stratum 20 :seon.rule/enabled? true :seon.rule/target :any
                   :seon.rule/query '[:find ?e ?dbe ?tx
                                      :in $branch $wm
                                      :where [$wm ?e :ctx/kind :seon.code.var/entity]
                                             (not [$wm ?e :view/suppressed? true])
                                             [$branch ?dbe :seon.code.var/name seon.demo.alpha/target-fn ?tx]]
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

(deftest ^{:cost/ticks 35000000} incremental-step-rules-and-rule-edit-test
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
                 :seon.rule/query '[:find ?v ?ns
                                    :in $main $branch $wm
                                    :where [$wm ?v :ctx/viewer ?aid]
                                           [$main ?ag :seon.agent/id ?aid]
                                           [$main ?ag :seon.agent/scope ?sc]
                                           [$branch ?ns :seon.code.ns/name ?sc]]
                 :seon.rule/assert '[[?v :rel/scope ?ns]]}
                {:seon.rule/id :r/layout :seon.rule/stratum 20 :seon.rule/enabled? true :seon.rule/target :any
                 :seon.rule/query '[:find ?ns :in $wm :where [$wm _ :rel/scope ?ns]]
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
    (d/transact conn
                {:tx-data
                 [{:db/ident :seon.rule/id :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/unique :db.unique/identity :db/contentId true}
                  {:db/ident :seon.rule/doc :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                  {:db/ident :seon.rule/category :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/index true}
                  {:db/ident :seon.rule/stratum :db/valueType :db.type/long :db/cardinality :db.cardinality/one :db/index true}
                  {:db/ident :seon.rule/enabled? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one :db/index true}
                  {:db/ident :seon.rule/target :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
                  {:db/ident :seon.rule/when :db/valueType :db.type/data :db/cardinality :db.cardinality/one}
                  {:db/ident :seon.rule/assert :db/valueType :db.type/data :db/cardinality :db.cardinality/one}
                  {:db/ident :seon.agent/curated :db/valueType :db.type/data :db/cardinality :db.cardinality/one}
                  {:db/ident :seon.task/curated :db/valueType :db.type/data :db/cardinality :db.cardinality/one}]})))

(deftest ^{:cost/ticks 55000000} derive-bag-graph-to-tree-and-rules-persistence-test
  (testing "Unified Turn-0 render-ns-full projects ns -> public API -> covering tests into HTML & Markdown, and rules.edn round-trips losslessly"
    (with-test-store [{:keys [conn]}]
      (let [seed-rules (do (ensure-rule-schema! conn) (rseed/starter-rules))]
        (d/transact conn {:tx-data (concat
                                    seed-rules
                                    (code/file-import-tx @conn "src/seon/demo/tree.clj"
                                                         "(ns seon.demo.tree \"Tree demo\")\n(defn leaf-op [x] (inc x))" false)
                                    (code/file-import-tx @conn "test/seon/demo_tree_test.clj"
                                                         "(ns seon.demo-tree-test (:require [clojure.test :refer [deftest is]] [seon.demo.tree :as dt]))\n(deftest leaf-op-test (is (= 2 (dt/leaf-op 1))))" false)
                                    [{:seon.agent/id :ag/tree-demo :seon.agent/branch :main :seon.agent/role :worker
                                      :seon.agent/scope 'seon.demo.tree :seon.agent/goal "Inspect tree"}])})
        (let [db @conn
              {:keys [candidates]} (ctx/gather-agent-candidates db db :ag/tree-demo {:enrich? true :semantic? false})
              ns-cand (first (filter #(= 'seon.demo.tree (get-in % [:data :seon.code.ns/name])) candidates))
              h (:seon.ui/hiccup (rwork/render-ns-full (assoc (:params ns-cand) :seon.code.ns/entity (:data ns-cand))))
              html (render/hiccup->html h)
              txt (render/hiccup->text h)
              wire-ctx (rwire/render-agent-context db db :ag/tree-demo)
              rendered-edn (files/render-rules-file db "resources/seon/rules.edn")
              reimported (files/import-rules-tx db "resources/seon/rules.edn" rendered-edn)]
          (is (some? ns-cand))
          (is (str/includes? html "leaf-op"))
          (is (str/includes? html "leaf-op-test"))
          (is (str/includes? txt "leaf-op"))
          (is (str/includes? wire-ctx "leaf-op-test"))
          ;; Declarative persistence round-trip
          (is (string? rendered-edn))
          (is (= (count seed-rules) (count reimported))))))))

(deftest ^{:cost/ticks 55000000} situational-context-grounding-search-recency-and-overlap-test
  (testing "Unified pipeline grounds task sub-ns & schemas, demotes broad parent ns, triggers search + recency tail on underspecified tasks, and boosts tree overlap"
    (with-test-store [{:keys [conn]}]
      ;; Seed starter rules + demo namespaces/vars/tests/tasks/calls
      (let [seed-rules (do (ensure-rule-schema! conn) (rseed/starter-rules))
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
              ;; 1. Targeted task promotes seon.demo.target, demotes seon.demo to summary strip, surfaces :seon.task/entity schema
              c1 (:candidates (ctx/gather-agent-candidates db db :ag/targeted {:target :wire :enrich? true}))
              t-ns (first (filter #(= 'seon.demo.target (get-in % [:data :seon.code.ns/name])) c1))
              p-ns (first (filter #(= 'seon.demo (get-in % [:data :seon.code.ns/name])) c1))
              sch-it (first (filter #(= :seon.task/entity (get-in % [:data :seon.schema/key])) c1))
              ;; 2. Underspecified vague task triggers search -> recently modified slow-debug-wire-profiler promoted to :ephemeral-tail with :tag/recent & :show-diff? true!
              c2 (:candidates (ctx/gather-agent-candidates db db :ag/vague {:target :wire :enrich? true}))
              recent-item (first (filter #(= 'seon.demo.recent/slow-debug-wire-profiler (get-in % [:data :seon.code.var/name])) c2))
              ;; 3. Coordinator with 2 child calls discovering seon.demo.target/alpha-widget -> tree overlap promotes var with :tag/tree-overlap + :show-source? true!
              ;;    And pinned [:seon.health/summary :main] surfaces :seon.health/report!
              c3 (:candidates (ctx/gather-agent-candidates db db :ag/coord
                                                           {:target :html :enrich? true
                                                            :health-summary-fn (fn [_] {:ok? true :branch :main :problems [] :counts {}})}))
              overlap-item (first (filter #(= 'seon.demo.target/alpha-widget (get-in % [:data :seon.code.var/name])) c3))
              health-item (first (filter #(= :seon.health/report (:schema %)) c3))]
          (is (some? t-ns))
          (is (contains? (:tags t-ns) :tag/mentioned))
          (is (some? p-ns))
          (is (true? (get-in p-ns [:params :seon.ui/summary-strip?])))
          (is (some? sch-it))
          (is (some? recent-item))
          (is (= :ephemeral-tail (:zone recent-item)))
          (is (contains? (:tags recent-item) :tag/recent))
          (is (true? (get-in recent-item [:params :show-diff?])))
          (is (some? overlap-item))
          (is (contains? (:tags overlap-item) :tag/tree-overlap))
          (is (true? (get-in overlap-item [:params :show-source?])))
          (is (some? health-item)))))))