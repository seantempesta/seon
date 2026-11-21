(ns seon.rules.context
  "Rule-driven context & view derivation over Datahike (`seon.rules`):
   - Replaces hardcoded imperative tile checklists with editable `:seon.rule/*` entities.
   - Deterministic core + cached boundary effects (`:ground/task-text`, `:search/relevant`,
     `:collect/tree-discoveries`, `:discover/schema-neighborhood`) so rules stay 100% pure,
     explainable, and `datahike.reads`-reactive.
   - Drives BOTH `:target :wire` (KV-cache-safe `:static-prefix` + `:ephemeral-tail`) and
     `:target :html` (Canvas/Workspace/Debug layout + provenance trace) from one rule graph."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.lineage :as lineage]
            [seon.rules :as rules]
            [seon.store :as store]))

(def default-rules
  "Starter pure-EDN ruleset persisted to `resources/seon/rules.edn`. Any rule can be edited,
   toggled, or overridden live in Datahike via `(user/put-rule! ...)` or `(user/toggle-rule! ...)`."
  [{:seon.rule/id :rule.seed/ctx-snapshot
    :seon.rule/doc "Always surface the viewer agent's own @ctx snapshot tile."
    :seon.rule/category :seed :seon.rule/stratum 10 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?v :ctx/viewer ?aid]]
    :seon.rule/assert '[[:ctx/self :ctx/kind :seon.ctx/snapshot]
                        [:ctx/self :ctx/source :ctx]
                        [:ctx/self :ctx/score 1.0]
                        [:ctx/self :ctx/why "Agent @ctx runtime snapshot"]]}

   {:seon.rule/id :rule.seed/agent-task
    :seon.rule/doc "Connect viewer agent to its assigned :seon.task/* entity."
    :seon.rule/category :seed :seon.rule/stratum 10 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?v :ctx/viewer ?aid]
                      [?v :ctx/task-id ?tid]
                      [:db :task [?t :seon.task/id ?tid]]
                      [:optional [:db :task [?t :seon.task/title ?title]]]
                      [:optional [:db :task [?t :seon.task/plan ?plan]]]
                      [:optional [:db :task [?t :seon.task/template? ?tpl]]]]
    :seon.rule/assert '[[?v :rel/task ?t]
                        [?t :ctx/kind :seon.task/entity]
                        [?t :ctx/source :walk]
                        [?t :seon.task/id ?tid]
                        [?t :seon.task/title ?title]
                        [?t :seon.task/plan ?plan]
                        [?t :seon.task/template? ?tpl]
                        [?t :ctx/score 1.0]
                        [?t :ctx/why "Assigned agent task"]]}

   {:seon.rule/id :rule.seed/task-lineage
    :seon.rule/doc "Attach derived task DAG lineage (parents, subtasks, history, fixes) for the viewer."
    :seon.rule/category :seed :seon.rule/stratum 10 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?v :ctx/viewer ?aid]
                      [?v :ctx/has-lineage? true]]
    :seon.rule/assert '[[:ctx/lineage :ctx/kind :seon.task/lineage]
                        [:ctx/lineage :ctx/source :walk]
                        [:ctx/lineage :ctx/score 0.92]
                        [:ctx/lineage :ctx/why "Task DAG lineage & historical context"]]}

   {:seon.rule/id :rule.seed/agent-scope
    :seon.rule/doc "Connect viewer agent to its :seon.agent/scope namespace entity."
    :seon.rule/category :seed :seon.rule/stratum 10 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?v :ctx/viewer ?aid]
                      [?v :ctx/scope ?sc]
                      [:db :code [?ns :seon.code.ns/name ?sc]]]
    :seon.rule/assert '[[?v :rel/scope ?ns]
                        [?ns :ctx/kind :seon.code.ns/entity]
                        [?ns :ctx/source :walk]
                        [?ns :seon.code.ns/name ?sc]
                        [?ns :ctx/score 0.78]
                        [?ns :ctx/why "Agent namespace scope"]]}

   {:seon.rule/id :rule.seed/extra-namespaces
    :seon.rule/doc "Surface namespaces explicitly tuned via :seon.agent/context-prefs :extra-namespaces."
    :seon.rule/category :seed :seon.rule/stratum 10 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?v :ctx/extra-ns ?nsym]
                      [:db :code [?ns :seon.code.ns/name ?nsym]]]
    :seon.rule/assert '[[?v :rel/extra-ns ?ns]
                        [?ns :ctx/kind :seon.code.ns/entity]
                        [?ns :ctx/source :tuned]
                        [?ns :seon.code.ns/name ?nsym]
                        [?ns :ctx/score 0.88]
                        [?ns :ctx/why "Tuned extra namespace"]]}

   {:seon.rule/id :rule.seed/child-agents
    :seon.rule/doc "Surface non-archived direct child agents via reverse :seon.agent/parent lookup."
    :seon.rule/category :seed :seon.rule/stratum 10 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?v :ctx/viewer ?aid]
                      [?v :ctx/include-children? true]
                      [:db :main [?c :seon.agent/parent ?aid]]
                      [:db :main [?c :seon.agent/id ?cid]]
                      [:not [:db :main [?c :seon.agent/archived? true]]]]
    :seon.rule/assert '[[?v :rel/child-agent ?c]
                        [?c :ctx/kind :seon.agent/entity]
                        [?c :ctx/source :child]
                        [?c :seon.agent/id ?cid]
                        [?c :ctx/score 0.82]
                        [?c :ctx/why "Direct child sub-agent"]]}

   {:seon.rule/id :rule.diag/request-task-grounding
    :seon.rule/doc "Request boundary entity grounding over task title & custom plan (stripping template boilerplate)."
    :seon.rule/category :diagnosis :seon.rule/stratum 20 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?v :rel/task ?t]
                      [?t :seon.task/title ?title]
                      [:not [?t :seon.task/template? true]]
                      [:optional [?t :seon.task/plan ?plan]]
                      [:not [?t :ctx/grounded? true]]]
    :seon.rule/assert '[[?t :ctx/need-effect {:op :ground/task-text :title ?title :plan ?plan}]]}

   {:seon.rule/id :rule.diag/underspecified-task
    :seon.rule/doc "Detect an underspecified task lacking tests, concrete ns/var mentions, and a detailed plan."
    :seon.rule/category :diagnosis :seon.rule/stratum 21 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?v :rel/task ?t]
                      [?t :ctx/grounded? true]
                      [?t :seon.task/title ?title]
                      [:not [?t :seon.task/template? true]]
                      [:not [:db :task [?t :seon.task/tests _]]]
                      [:not [?t :rel/mentions-ns _]]
                      [:not [?t :rel/mentions-var _]]
                      [:optional [?t :ctx/custom-plan-len ?plen]]
                      [:guard (or (nil? ?plen) (< ?plen 80))]]
    :seon.rule/assert '[[?t :ctx/specificity :underspecified]
                        [?t :ctx/why "Underspecified task (missing tests & concrete symbols)"]]}

   {:seon.rule/id :rule.diag/search-on-underspecified
    :seon.rule/doc "Trigger boundary relevance search (lexical today, :vectors embedding ready) when task is underspecified."
    :seon.rule/category :diagnosis :seon.rule/stratum 22 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?t :ctx/specificity :underspecified]
                      [?t :seon.task/title ?title]
                      [:not [?t :ctx/searched? true]]]
    :seon.rule/assert '[[?t :ctx/need-effect {:op :search/relevant :query ?title :k 6}]]}

   {:seon.rule/id :rule.diag/collect-tree-discoveries
    :seon.rule/doc "Collect vars, namespaces, and schemas discovered/curated across child and subtask agents in the tree."
    :seon.rule/category :diagnosis :seon.rule/stratum 20 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?v :ctx/viewer ?aid]
                      [?v :ctx/has-tree-peers? true]
                      [:optional [?v :ctx/task-id ?tid]]
                      [:not [?v :ctx/tree-collected? true]]]
    :seon.rule/assert '[[?v :ctx/need-effect {:op :collect/tree-discoveries :agent ?aid :task ?tid}]]}

   {:seon.rule/id :rule.rank/mentioned-ns-boost
    :seon.rule/doc "Promote namespaces explicitly named in the task's title or custom plan."
    :seon.rule/category :rank :seon.rule/stratum 30 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?t :rel/mentions-ns ?ns]]
    :seon.rule/assert '[[?ns :ctx/score 0.95]
                        [?ns :ctx/source :rule]
                        [?ns :ctx/why "Explicitly named in task"]]}

   {:seon.rule/id :rule.rank/mentioned-var-boost
    :seon.rule/doc "Promote vars explicitly named in the task's title or custom plan."
    :seon.rule/category :rank :seon.rule/stratum 30 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?t :rel/mentions-var ?ve]]
    :seon.rule/assert '[[?ve :ctx/score 0.96]
                        [?ve :ctx/source :rule]
                        [?ve :ctx/why "Explicitly named in task"]]}

   {:seon.rule/id :rule.rank/mentioned-schema-boost
    :seon.rule/doc "Promote Malli entity schemas whose domain matches the task (prevents attribute hallucination)."
    :seon.rule/category :rank :seon.rule/stratum 30 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?t :rel/mentions-schema ?sc]]
    :seon.rule/assert '[[?sc :ctx/score 0.90]
                        [?sc :ctx/source :rule]
                        [?sc :ctx/why "Schema matched from task domain"]]}

   {:seon.rule/id :rule.rank/curated-boost
    :seon.rule/doc "Give top relevance to entities explicitly curated via (user/curate! ...) on agent or task."
    :seon.rule/category :rank :seon.rule/stratum 30 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :rel/curated true]]
    :seon.rule/assert '[[?e :ctx/score 0.98]
                        [?e :ctx/source :curated]
                        [?e :ctx/why "Curated for task context"]]}

   {:seon.rule/id :rule.rank/tree-overlap-boost
    :seon.rule/doc "Compute multi-agent discovery overlap across the delegation tree; >= 2 agents strongly promotes entity."
    :seon.rule/category :rank :seon.rule/stratum 30 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind _]
                      [:count ?n [?a :rel/tree-discovered ?e]]
                      [:guard (>= ?n 2)]]
    :seon.rule/assert '[[?e :ctx/tree-count ?n]
                        [?e :ctx/score 0.94]
                        [?e :ctx/source :tree]
                        [?e :ctx/why (str "Tree overlap across " ?n " agents")]]}

   {:seon.rule/id :rule.rank/tag-recent-entity
    :seon.rule/doc "Tag entities modified after initial store bootstrap and within the recent tx window."
    :seon.rule/category :rank :seon.rule/stratum 30 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[:env :env/boot-tx ?boot]
                      [:env :env/max-tx ?max-tx]
                      [?e :ctx/modified-tx ?tx]
                      [:guard (and (> ?tx ?boot) (>= ?tx (- ?max-tx 80)))]]
    :seon.rule/assert '[[?e :ctx/recency :recent]
                        [?e :ctx/why "Modified recently"]]}

   {:seon.rule/id :rule.expand/covering-tests-for-hot-var
    :seon.rule/doc "Automatically surface covering deftest vars referencing any high-relevance target var."
    :seon.rule/category :expand :seon.rule/stratum 32 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?ve :ctx/kind :seon.code.var/entity]
                      [?ve :seon.code.var/name ?sym]
                      [?ve :ctx/score ?s]
                      [:guard (>= ?s 0.94)]
                      [:not [:db :code [?ve :seon.code.var/test? true]]]
                      [:db :code [?te :seon.code.var/refs ?sym]]
                      [:db :code [?te :seon.code.var/test? true]]
                      [:db :code [?te :seon.code.var/name ?tsym]]]
    :seon.rule/assert '[[?te :ctx/kind :seon.code.var/entity]
                        [?te :seon.code.var/name ?tsym]
                        [?te :ctx/source :rule]
                        [?te :ctx/score 0.82]
                        [?te :ctx/why (str "Covering test for " ?sym)]]}

   ;; --- STRATUM 40: Default inherited presentation rules (apply to ANY agent!) ---
   {:seon.rule/id :rule.view/default-ctx
    :seon.rule/doc "Default presentation for the agent @ctx snapshot tile."
    :seon.rule/category :present :seon.rule/stratum 40 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.ctx/snapshot]]
    :seon.rule/assert '[[?e :view/fn seon.render.tiles/ctx-tile]
                        [?e :view/zone :static-prefix]
                        [?e :view/layout :expanded-card]
                        [?e :view/priority 100]]}

   {:seon.rule/id :rule.view/default-health
    :seon.rule/doc "Default presentation for a pinned or discovered :seon.health/report."
    :seon.rule/category :present :seon.rule/stratum 40 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.health/report]]
    :seon.rule/assert '[[?e :view/fn seon.render.tiles/health-panel]
                        [?e :view/zone :static-prefix]
                        [?e :view/layout :full-table]
                        [?e :view/priority 94]]}

   {:seon.rule/id :rule.view/default-task
    :seon.rule/doc "Default presentation for :seon.task/entity."
    :seon.rule/category :present :seon.rule/stratum 40 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.task/entity]
                      [?e :ctx/score _]]
    :seon.rule/assert '[[?e :view/fn seon.render.tiles/task-tile]
                        [?e :view/zone :static-prefix]
                        [?e :view/layout :expanded-card]
                        [?e :view/priority 92]]}

   {:seon.rule/id :rule.view/default-lineage
    :seon.rule/doc "Default presentation for :seon.task/lineage."
    :seon.rule/category :present :seon.rule/stratum 40 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.task/lineage]]
    :seon.rule/assert '[[?e :view/fn seon.render.lineage/task-lineage-tile]
                        [?e :view/zone :static-prefix]
                        [?e :view/layout :expanded-card]
                        [?e :view/priority 90]]}

   {:seon.rule/id :rule.view/default-ns
    :seon.rule/doc "Default presentation for any connected :seon.code.ns/entity."
    :seon.rule/category :present :seon.rule/stratum 40 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.code.ns/entity]
                      [?e :ctx/score _]]
    :seon.rule/assert '[[?e :view/fn seon.render.ns-tile/ns-tile]
                        [?e :view/zone :static-prefix]
                        [?e :view/layout :expanded-card]
                        [?e :view/priority 76]]}

   {:seon.rule/id :rule.view/default-schema
    :seon.rule/doc "Default presentation for any connected :seon.schema/entity."
    :seon.rule/category :present :seon.rule/stratum 40 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.schema/entity]
                      [?e :ctx/score _]]
    :seon.rule/assert '[[?e :view/fn seon.render.tiles/schema-tile]
                        [?e :view/zone :static-prefix]
                        [?e :view/layout :compact-card]
                        [?e :view/priority 84]
                        [?e :view.param/show-touching-vars? true]]}

   {:seon.rule/id :rule.view/default-var
    :seon.rule/doc "Default compact signature presentation for any connected :seon.code.var/entity."
    :seon.rule/category :present :seon.rule/stratum 40 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.code.var/entity]
                      [?e :ctx/score _]]
    :seon.rule/assert '[[?e :view/fn seon.render.tiles/var-tile]
                        [?e :view/zone :static-prefix]
                        [?e :view/layout :compact-row]
                        [?e :view/priority 72]
                        [?e :view.param/show-source? false]]}

   {:seon.rule/id :rule.view/default-doc
    :seon.rule/doc "Default presentation for :seon.code.doc/entity."
    :seon.rule/category :present :seon.rule/stratum 40 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.code.doc/entity]
                      [?e :ctx/score _]]
    :seon.rule/assert '[[?e :view/fn seon.render.tiles/doc-tile]
                        [?e :view/zone :static-prefix]
                        [?e :view/layout :compact-card]
                        [?e :view/priority 70]]}

   {:seon.rule/id :rule.view/default-agent
    :seon.rule/doc "Default presentation for connected :seon.agent/entity (children or pinned)."
    :seon.rule/category :present :seon.rule/stratum 40 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.agent/entity]
                      [?e :ctx/score _]]
    :seon.rule/assert '[[?e :view/fn seon.render.tiles/agent-tile]
                        [?e :view/zone :static-prefix]
                        [?e :view/layout :compact-card]
                        [?e :view/priority 60]]}

   {:seon.rule/id :rule.view/default-rule
    :seon.rule/doc "Default presentation for pinned or evaluated :seon.rule/entity."
    :seon.rule/category :present :seon.rule/stratum 40 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.rule/entity]
                      [?e :ctx/score _]]
    :seon.rule/assert '[[?e :view/fn seon.render.tiles/rule-tile]
                        [?e :view/zone :static-prefix]
                        [?e :view/layout :compact-card]
                        [?e :view/priority 65]]}

   ;; --- STRATUM 50-60: Situational layout & param overrides (same renderers, adaptive behavior!) ---
   {:seon.rule/id :rule.layout/dedup-lineage-plan
    :seon.rule/doc "When the current task tile is already rendered, omit its duplicate plan inside task-lineage-tile."
    :seon.rule/category :layout :seon.rule/stratum 50 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?t :ctx/kind :seon.task/entity]
                      [?t :view/fn _]
                      [:ctx/lineage :view/fn _]]
    :seon.rule/assert '[[:ctx/lineage :view.param/omit-current-plan? true]]}

   {:seon.rule/id :rule.layout/hide-templates-on-concrete-task
    :seon.rule/doc "Omit generic <task-templates> boilerplate on wire when the agent already has a concrete non-template task."
    :seon.rule/category :layout :seon.rule/stratum 50 :seon.rule/enabled? true :seon.rule/target :wire
    :seon.rule/when '[[?v :rel/task ?t]
                      [:not [?t :seon.task/template? true]]
                      [:ctx/lineage :view/fn _]]
    :seon.rule/assert '[[:ctx/lineage :view.param/omit-templates? true]]}

   {:seon.rule/id :rule.layout/demote-broad-scope-when-target-ns-mentioned
    :seon.rule/doc "Demote broad parent :scope ns to a 3-line summary strip when the task explicitly names specific target sub-namespaces."
    :seon.rule/category :layout :seon.rule/stratum 50 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?v :rel/scope ?sc-ns]
                      [?v :rel/task ?t]
                      [?t :rel/mentions-ns ?target-ns]
                      [:guard (not= ?sc-ns ?target-ns)]]
    :seon.rule/assert '[[?sc-ns :view/layout :summary-strip]
                        [?sc-ns :view/priority 45]
                        [?sc-ns :view.param/max-vars 0]
                        [?sc-ns :ctx/why "Demoted to summary strip (task names specific target ns)"]
                        [?target-ns :view/layout :expanded-card]
                        [?target-ns :view/priority 95]]}

   {:seon.rule/id :rule.layout/promote-strong-var
    :seon.rule/doc "Expand full source & callers for explicitly mentioned, curated, or multi-agent tree-overlap vars."
    :seon.rule/category :layout :seon.rule/stratum 55 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.code.var/entity]
                      [?e :ctx/score ?s]
                      [:guard (>= ?s 0.94)]]
    :seon.rule/assert '[[?e :view/layout :expanded-card]
                        [?e :view/priority 93]
                        [?e :view.param/show-source? true]
                        [?e :view.param/show-callers? true]]}

   {:seon.rule/id :rule.layout/promote-recent-search-var
    :seon.rule/doc "Move recently modified high-relevance search hits to :ephemeral-tail with source+diff so KV prefix stays cached."
    :seon.rule/category :layout :seon.rule/stratum 55 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind :seon.code.var/entity]
                      [?e :ctx.match/via :search]
                      [?e :ctx.match/score ?s]
                      [:guard (>= ?s 0.25)]
                      [?e :ctx/recency :recent]]
    :seon.rule/assert '[[?e :view/zone :ephemeral-tail]
                        [?e :view/layout :expanded-card]
                        [?e :view/priority 91]
                        [?e :view.param/show-source? true]
                        [?e :view.param/show-diff? true]
                        [?e :view.param/badge "recently modified"]]}

   {:seon.rule/id :rule.layout/compact-health-in-html-rail
    :seon.rule/doc "Use a clean compact summary layout for System Health inside narrow HTML right-column rails (prevents horizontal clipping)."
    :seon.rule/category :layout :seon.rule/stratum 55 :seon.rule/enabled? true :seon.rule/target :html
    :seon.rule/when '[[?e :ctx/kind :seon.health/report]]
    :seon.rule/assert '[[?e :view/layout :compact-summary]]}])

(defonce ^:private ^:seon.flow/edge effect-cache (store/lru-cache 64))
(defonce ^:private ^:seon.flow/edge boot-tx-cache (store/lru-cache 16))

(defn baseline-import-tx
  "Returns the bulk bootstrap transaction id of `db` (cached with `datahike.reads` replay) so recency rules distinguish post-boot edits."
  {:malli/schema [:=> [:cat :seon.host/db] :int]
   :cost/ticks 2000000}
  [db]
  (if-not (and db (contains? (:schema db) :seon.code.form/hash))
    0
    (let [ck [(get-in db [:config :store :id]) (get-in db [:config :branch] :main)]]
      (store/lru-get!
       boot-tx-cache 16 ck
       (fn []
         (let [tx-freqs (frequencies (map :tx (d/datoms db :aevt :seon.code.form/hash)))]
           (if (seq tx-freqs)
             (long (key (apply max-key val tx-freqs)))
             0)))))))

(defn strip-template-footer
  "Strips generic template instructions appended after `\n---\n` so task grounding matches only task-specific intent."
  {:malli/schema [:=> [:cat [:maybe :string]] :string]
   :cost/ticks 50000}
  [plan]
  (if (string? plan)
    (str/trim (first (str/split plan #"\n---\n" 2)))
    ""))

(def ^:private domain-stop-words
  #{"seon" "code" "user" "summary" "entity" "file" "form" "data" "test" "line" "name" "plan" "list" "view"})

(defn- ground-task-text-effect [db title plan]
  (let [custom-plan (strip-template-footer plan)
        txt (str (or title "") " " custom-plan)
        raw-toks (re-seq #"[a-zA-Z0-9_.:/-]+" txt)
        toks (into #{} (map #(str/replace % #"^[.:/]+|[.:/]+$" "")) raw-toks)
        ns-map (if (contains? (:schema db) :seon.code.ns/name)
                 (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.ns/name))
                 {})
        var-map (if (contains? (:schema db) :seon.code.var/name)
                  (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.var/name))
                  {})
        sch-map (if (contains? (:schema db) :seon.schema/key)
                  (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.schema/key))
                  {})
        ;; Only match multi-segment namespaces (avoids English false positives like 'build)
        hit-nss (vec (keep (fn [[nsym eid]]
                             (when (and (str/includes? (name nsym) ".")
                                        (contains? toks (name nsym)))
                               [nsym eid]))
                           ns-map))
        hit-vars (vec (take 8 (keep (fn [[vsym eid]]
                                      (when (contains? toks (str vsym))
                                        [vsym eid]))
                                    var-map)))
        words (into #{} (map (fn [w]
                               (let [lw (str/lower-case w)]
                                 (if (and (> (count lw) 4) (str/ends-with? lw "s"))
                                   (subs lw 0 (dec (count lw)))
                                   lw))))
                    (re-seq #"[a-zA-Z]{4,}" txt))
        hit-schemas (vec (take 4 (keep (fn [[sk eid]]
                                         (let [kw-str (str sk)
                                               ns-parts (str/split (namespace sk) #"\.")]
                                           (cond
                                             (contains? toks kw-str) [sk eid]
                                             (and (= "entity" (name sk))
                                                  (some #(and (not (contains? domain-stop-words %))
                                                              (contains? words %))
                                                        ns-parts))
                                             [sk eid])))
                                       sch-map)))]
    {:custom-plan-len (count custom-plan)
     :hit-nss hit-nss
     :hit-vars hit-vars
     :hit-schemas hit-schemas}))

(defn fast-search-vars
  "Fast flat-AEVT relevance scorer across non-test vars (0 per-var pulls, ~10 ms). Same `:ctx.match/*`
   boundary contract that `:vectors` embeddings plug into."
  {:malli/schema [:=> [:cat :seon.host/db :string :int] [:vector :map]]
   :cost/ticks 8000000}
  [db query k]
  (let [q (str/lower-case (str query))
        stops #{"the" "and" "for" "with" "from" "that" "this" "why" "how" "into" "are" "fix" "add" "use" "make" "can" "not" "all" "our" "when" "does"}
        q-terms (vec (distinct (remove stops (re-seq #"[a-z]{3,}" q))))]
    (if (or (empty? q-terms) (not (contains? (:schema db) :seon.code.var/name)))
      []
      (let [test-eids (if (contains? (:schema db) :seon.code.var/test?)
                        (reduce (fn [s dt] (if (:v dt) (conj s (:e dt)) s)) #{} (d/datoms db :aevt :seon.code.var/test?))
                        #{})
            doc-by-e  (if (contains? (:schema db) :seon.code.var/doc)
                        (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.var/doc))
                        {})
            form-tx-by-key (if (and (contains? (:schema db) :seon.code.form/hash)
                                    (contains? (:schema db) :seon.code.form/key))
                             (let [k-by-e (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))]
                               (reduce (fn [m dt] (if-let [fk (get k-by-e (:e dt))] (assoc m fk (:tx dt)) m))
                                       {} (d/datoms db :aevt :seon.code.form/hash)))
                             {})
            vform-by-e (if (contains? (:schema db) :seon.code.var/form)
                         (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.var/form))
                         {})
            n-terms (double (count q-terms))]
        (->> (d/datoms db :aevt :seon.code.var/name)
             (keep (fn [dt]
                     (let [e (:e dt)]
                       (when-not (contains? test-eids e)
                         (let [vsym (:v dt)
                               sym-s (str/lower-case (str vsym))
                               doc-s (str/lower-case (str (get doc-by-e e "")))
                               sym-hits (count (filter #(str/includes? sym-s %) q-terms))
                               doc-hits (count (filter #(and (not (str/includes? sym-s %))
                                                             (str/includes? doc-s %)) q-terms))
                               score (/ (+ (* 2.0 sym-hits) (* 1.0 doc-hits)) (* 2.0 n-terms))]
                           (when (> score 0.0)
                             {:e e :sym vsym
                              :tx (max (long (:tx dt)) (long (get form-tx-by-key (get vform-by-e e) 0)))
                              :score (/ (Math/round (* score 1000.0)) 1000.0)}))))))
             (sort-by (juxt (comp - :score) (comp - :tx) #(str (:sym %))))
             (take k)
             vec)))))

(defn- extract-tokens-from-text [s]
  (into #{} (map #(str/replace % #"^['`(\[\" ]+|[\])\"',.: ]+$" ""))
        (re-seq #"[a-zA-Z0-9_.'`:/-]+" (str s))))

(defn- collect-tree-discoveries-effect [main-db code-db agent-id task-id]
  (let [ns-map (if (contains? (:schema code-db) :seon.code.ns/name)
                 (into {} (map (juxt :v :e)) (d/datoms code-db :aevt :seon.code.ns/name))
                 {})
        var-map (if (contains? (:schema code-db) :seon.code.var/name)
                  (into {} (map (juxt :v :e)) (d/datoms code-db :aevt :seon.code.var/name))
                  {})
        sch-map (if (contains? (:schema code-db) :seon.schema/key)
                  (into {} (map (juxt :v :e)) (d/datoms code-db :aevt :seon.schema/key))
                  {})
        ;; Gather peer agents from delegation calls + task subtasks
        peer-calls (if (and agent-id (contains? (:schema main-db) :seon.agent.call/caller))
                     (->> (d/datoms main-db :avet :seon.agent.call/caller agent-id)
                          (map #(d/pull main-db '[:seon.agent.call/callee :seon.agent.call/result :seon.agent.call/status] (:e %)))
                          (filter #(= :resolved (:seon.agent.call/status %)))
                          vec)
                     [])
        subtask-ids (when (and task-id (contains? (:schema main-db) :seon.task/needs))
                      (:seon.task/needs (d/pull main-db '[:seon.task/needs] [:seon.task/id task-id])))
        subtask-peers (for [stid subtask-ids
                            :let [t (d/pull main-db '[:seon.task/owner :seon.task/summary :seon.task/curated] [:seon.task/id stid])]
                            :when (:seon.task/owner t)]
                        {:peer (:seon.task/owner t)
                         :text (str (:seon.task/summary t))
                         :curated (:seon.task/curated t)})
        peer-sources (concat
                      (map (fn [c] {:peer (:seon.agent.call/callee c)
                                    :text (pr-str (:seon.agent.call/result c))})
                           peer-calls)
                      subtask-peers)]
    (vec
     (mapcat
      (fn [{:keys [peer text curated]}]
        (let [toks (extract-tokens-from-text text)
              ns-hits (for [[nsym eid] ns-map
                            :when (and (str/includes? (name nsym) ".") (contains? toks (name nsym)))]
                        {:peer peer :e eid :kind :seon.code.ns/entity :attr :seon.code.ns/name :val nsym})
              var-hits (for [[vsym eid] var-map
                             :when (contains? toks (str vsym))]
                         {:peer peer :e eid :kind :seon.code.var/entity :attr :seon.code.var/name :val vsym})
              sch-hits (for [[sk eid] sch-map
                             :when (contains? toks (str sk))]
                         {:peer peer :e eid :kind :seon.schema/entity :attr :seon.schema/key :val sk})
              cur-hits (for [ref curated
                             :when (and (vector? ref) (= 2 (count ref)))
                             :let [[a v] ref
                                   e (:e (first (d/datoms code-db :avet a v)))]
                             :when e]
                         {:peer peer :e e
                          :kind (case a :seon.code.var/name :seon.code.var/entity :seon.code.ns/name :seon.code.ns/entity :seon.schema/key :seon.schema/entity :seon.code.doc/id :seon.code.doc/entity :seon.task/id :seon.task/entity nil)
                          :attr a :val v :curated? true})]
          (concat ns-hits var-hits sch-hits cur-hits)))
      peer-sources))))

(defn- execute-boundary-effects [wm dbs]
  (let [code-db (:code dbs)
        main-db (:main dbs)
        cid [(get-in code-db [:config :store :id]) (try (d/commit-id code-db) (catch Throwable _ (:max-tx code-db)))]
        reqs (for [[e a v] (:facts wm) :when (= a :ctx/need-effect)] [e v])]
    (reduce
     (fn [w [src-e eff]]
       (case (:op eff)
         :ground/task-text
         (if (contains? (:facts w) [src-e :ctx/grounded? true])
           w
           (let [{:keys [custom-plan-len hit-nss hit-vars hit-schemas]}
                 (store/lru-get! effect-cache 64 [:ground cid (:title eff) (:plan eff)]
                                 #(ground-task-text-effect code-db (:title eff) (:plan eff)))
                 w1 (-> w
                        (rules/add-fact [src-e :ctx/grounded? true] {:rule :effect/ground :stratum 20})
                        (rules/add-fact [src-e :ctx/custom-plan-len custom-plan-len] {:rule :effect/ground :stratum 20}))]
             (as-> w1 w'
               (reduce (fn [acc [nsym eid]]
                         (-> acc
                             (rules/add-fact [src-e :rel/mentions-ns eid] {:rule :effect/ground :stratum 20})
                             (rules/add-fact [eid :ctx/kind :seon.code.ns/entity] {:rule :effect/ground :stratum 20})
                             (rules/add-fact [eid :seon.code.ns/name nsym] {:rule :effect/ground :stratum 20})))
                       w' hit-nss)
               (reduce (fn [acc [vsym eid]]
                         (-> acc
                             (rules/add-fact [src-e :rel/mentions-var eid] {:rule :effect/ground :stratum 20})
                             (rules/add-fact [eid :ctx/kind :seon.code.var/entity] {:rule :effect/ground :stratum 20})
                             (rules/add-fact [eid :seon.code.var/name vsym] {:rule :effect/ground :stratum 20})))
                       w' hit-vars)
               (reduce (fn [acc [sk eid]]
                         (-> acc
                             (rules/add-fact [src-e :rel/mentions-schema eid] {:rule :effect/ground :stratum 20})
                             (rules/add-fact [eid :ctx/kind :seon.schema/entity] {:rule :effect/ground :stratum 20})
                             (rules/add-fact [eid :seon.schema/key sk] {:rule :effect/ground :stratum 20})))
                       w' hit-schemas))))

         :search/relevant
         (if (contains? (:facts w) [src-e :ctx/searched? true])
           w
           (let [hits (store/lru-get! effect-cache 64 [:search cid (:query eff) (:k eff 6)]
                                      #(fast-search-vars code-db (:query eff) (long (or (:k eff) 6))))
                 w1 (rules/add-fact w [src-e :ctx/searched? true] {:rule :effect/search :stratum 22})]
             (reduce (fn [acc {:keys [e sym tx score]}]
                       (-> acc
                           (rules/add-fact [e :ctx/kind :seon.code.var/entity] {:rule :effect/search :stratum 22})
                           (rules/add-fact [e :seon.code.var/name sym] {:rule :effect/search :stratum 22})
                           (rules/add-fact [e :ctx/source :search] {:rule :effect/search :stratum 22})
                           (rules/add-fact [e :ctx.match/via :search] {:rule :effect/search :stratum 22})
                           (rules/add-fact [e :ctx.match/score score] {:rule :effect/search :stratum 22})
                           (rules/add-fact [e :ctx/score (min 0.88 (+ 0.55 (* 0.35 score)))] {:rule :effect/search :stratum 22})
                           (rules/add-fact [e :ctx/modified-tx tx] {:rule :effect/search :stratum 22})
                           (rules/add-fact [e :ctx/why (str "Relevance search match (score " score ")")] {:rule :effect/search :stratum 22})))
                     w1 hits)))

         :collect/tree-discoveries
         (if (contains? (:facts w) [src-e :ctx/tree-collected? true])
           w
           (let [mcid (try (d/commit-id main-db) (catch Throwable _ (:max-tx main-db)))
                 hits (store/lru-get! effect-cache 64 [:tree mcid cid (:agent eff) (:task eff)]
                                      #(collect-tree-discoveries-effect main-db code-db (:agent eff) (:task eff)))
                 w1 (rules/add-fact w [src-e :ctx/tree-collected? true] {:rule :effect/tree :stratum 20})]
             (reduce (fn [acc {:keys [peer e kind attr val curated?]}]
                       (if (and e kind)
                         (cond-> (-> acc
                                     (rules/add-fact [peer :rel/tree-discovered e] {:rule :effect/tree :stratum 20})
                                     (rules/add-fact [e :ctx/kind kind] {:rule :effect/tree :stratum 20})
                                     (rules/add-fact [e attr val] {:rule :effect/tree :stratum 20}))
                           curated? (rules/add-fact [e :rel/curated true] {:rule :effect/tree :stratum 20}))
                         acc))
                     w1 hits)))

         w))
     wm
     reqs)))

(defn- sanitize-id [s]
  (-> (str s)
      (str/replace #"[^A-Za-z0-9_-]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- safe-read-edn [^String s]
  (when (and (string? s) (not (str/blank? s)) (< (count s) 12000))
    (try (edn/read-string s) (catch Throwable _ nil))))

(defn- resolve-lookup-ref [db item]
  (when (and (vector? item) (= 2 (count item)) (qualified-keyword? (first item)))
    (let [[attr val] item]
      (case attr
        :seon.health/summary {:eid [:health val] :kind :seon.health/report :ref item :health-val val}
        :seon.code.var/name  (when-let [e (:e (first (d/datoms db :avet attr val)))]
                               {:eid e :kind :seon.code.var/entity :attr attr :val val :ref item})
        :seon.code.ns/name   (when-let [e (:e (first (d/datoms db :avet attr val)))]
                               {:eid e :kind :seon.code.ns/entity :attr attr :val val :ref item})
        :seon.code.doc/id    (when-let [e (:e (first (d/datoms db :avet attr val)))]
                               {:eid e :kind :seon.code.doc/entity :attr attr :val val :ref item})
        :seon.schema/key     (when-let [e (:e (first (d/datoms db :avet attr val)))]
                               {:eid e :kind :seon.schema/entity :attr attr :val val :ref item})
        :seon.task/id        (when-let [e (:e (first (d/datoms db :avet attr val)))]
                               {:eid e :kind :seon.task/entity :attr attr :val val :ref item})
        :seon.agent/id       (when-let [e (:e (first (d/datoms db :avet attr val)))]
                               {:eid e :kind :seon.agent/entity :attr attr :val val :ref item})
        :seon.rule/id        (when-let [e (:e (first (d/datoms db :avet attr val)))]
                               {:eid e :kind :seon.rule/entity :attr attr :val val :ref item})
        nil))))

(defn- build-seed-wm [main-db branch-db code-db task-db agent-ent evals {:keys [include-evals? include-children?]}]
  (let [aid (:seon.agent/id agent-ent)
        tid (:seon.agent/task agent-ent)
        scope (:seon.agent/scope agent-ent)
        lin (lineage/agent-task-lineage task-db branch-db agent-ent)
        has-peers? (boolean
                    (or (and aid (contains? (:schema main-db) :seon.agent.call/caller)
                             (first (d/datoms main-db :avet :seon.agent.call/caller aid)))
                        (and tid (contains? (:schema task-db) :seon.task/needs)
                             (seq (:seon.task/needs (d/pull task-db '[:seon.task/needs] [:seon.task/id tid]))))))
        boot-tx (baseline-import-tx code-db)
        base-facts (cond-> [[:env :env/boot-tx boot-tx]
                            [:env :env/max-tx (long (or (:max-tx branch-db) 0))]]
                     aid (conj [:viewer :ctx/viewer aid])
                     tid (conj [:viewer :ctx/task-id tid])
                     scope (conj [:viewer :ctx/scope scope])
                     lin (conj [:viewer :ctx/has-lineage? true])
                     include-children? (conj [:viewer :ctx/include-children? true])
                     has-peers? (conj [:viewer :ctx/has-tree-peers? true]))
        wm0 (rules/add-facts (rules/empty-wm) base-facts {:rule :seed :stratum 0})
        ;; Seed extra-namespaces from :seon.agent/context-prefs
        wm1 (reduce (fn [w nsym] (rules/add-fact w [:viewer :ctx/extra-ns nsym] {:rule :seed :stratum 0}))
                    wm0
                    (get-in agent-ent [:seon.agent/context-prefs :extra-namespaces]))
        ;; Seed pinned refs
        wm2 (reduce (fn [w p]
                      (if-let [{:keys [eid kind attr val ref health-val]} (resolve-lookup-ref code-db p)]
                        (cond-> (-> w
                                    (rules/add-fact [eid :ctx/kind kind] {:rule :seed/pinned :stratum 10})
                                    (rules/add-fact [eid :ctx/source :pinned] {:rule :seed/pinned :stratum 10})
                                    (rules/add-fact [eid :ctx/score 0.96] {:rule :seed/pinned :stratum 10})
                                    (rules/add-fact [eid :ctx/ref ref] {:rule :seed/pinned :stratum 10})
                                    (rules/add-fact [eid :ctx/why "Pinned on agent"] {:rule :seed/pinned :stratum 10}))
                          attr (rules/add-fact [eid attr val] {:rule :seed/pinned :stratum 10})
                          health-val (rules/add-fact [eid :ctx/health-target health-val] {:rule :seed/pinned :stratum 10}))
                        w))
                    wm1
                    (:seon.agent/pinned agent-ent))
        ;; Seed curated refs from agent & task
        task-curated (when (and tid (contains? (:schema task-db) :seon.task/curated))
                       (:seon.task/curated (d/pull task-db '[:seon.task/curated] [:seon.task/id tid])))
        wm3 (reduce (fn [w c]
                      (if-let [{:keys [eid kind attr val ref]} (resolve-lookup-ref code-db c)]
                        (cond-> (-> w
                                    (rules/add-fact [eid :ctx/kind kind] {:rule :seed/curated :stratum 10})
                                    (rules/add-fact [eid :rel/curated true] {:rule :seed/curated :stratum 10})
                                    (rules/add-fact [eid :ctx/ref ref] {:rule :seed/curated :stratum 10}))
                          attr (rules/add-fact [eid attr val] {:rule :seed/curated :stratum 10}))
                        w))
                    wm2
                    (concat (:seon.agent/curated agent-ent) task-curated))
        ;; Seed recent renderable evals if requested
        eval-vals (volatile! {})
        wm4 (if-not include-evals?
              wm3
              (reduce (fn [w ev]
                        (let [rschema (:seon.agent.eval/result-schema ev)
                              handle (:seon.agent.eval/handle ev)]
                          (if-not rschema
                            w
                            (let [parsed (safe-read-edn (:seon.agent.eval/value ev))]
                              (cond
                                (and (vector? parsed) (= 2 (count parsed)) (qualified-keyword? (first parsed)))
                                (if-let [{:keys [eid kind attr val ref]} (resolve-lookup-ref branch-db parsed)]
                                  (cond-> (-> w
                                              (rules/add-fact [eid :ctx/kind kind] {:rule :seed/eval :stratum 10})
                                              (rules/add-fact [eid :ctx/source :eval] {:rule :seed/eval :stratum 10})
                                              (rules/add-fact [eid :ctx/score 0.74] {:rule :seed/eval :stratum 10})
                                              (rules/add-fact [eid :ctx/handle handle] {:rule :seed/eval :stratum 10})
                                              (rules/add-fact [eid :ctx/ref ref] {:rule :seed/eval :stratum 10})
                                              (rules/add-fact [eid :ctx/why (str "Returned by eval " handle)] {:rule :seed/eval :stratum 10}))
                                    attr (rules/add-fact [eid attr val] {:rule :seed/eval :stratum 10}))
                                  w)

                                (map? parsed)
                                (let [ek [:eval (or handle (:seon.agent.eval/id ev))]]
                                  (vswap! eval-vals assoc ek (with-meta parsed {:seon/schema rschema}))
                                  (-> w
                                      (rules/add-fact [ek :ctx/kind rschema] {:rule :seed/eval :stratum 10})
                                      (rules/add-fact [ek :ctx/source :eval] {:rule :seed/eval :stratum 10})
                                      (rules/add-fact [ek :ctx/score 0.74] {:rule :seed/eval :stratum 10})
                                      (rules/add-fact [ek :ctx/handle handle] {:rule :seed/eval :stratum 10})
                                      (rules/add-fact [ek :ctx/why (str "Returned by eval " handle)] {:rule :seed/eval :stratum 10})))
                                :else w)))))
                      wm3
                      (reverse (take-last 16 evals))))]
    {:wm wm4 :lineage lin :eval-vals @eval-vals}))

(defn- extract-view-params [wm e]
  (reduce-kv
   (fn [acc a _]
     (if (= "view.param" (namespace a))
       (let [k (keyword (name a))]
         (if (= k :badge)
           (assoc acc :badges (vec (sort (rules/wm-vals wm e a))))
           (assoc acc k (rules/wm-scalar wm e a))))
       acc))
   {}
   (get (:eav wm) e)))

(defn- entity-rules-trace [wm e]
  (let [prov (:provenance wm)
        e-map (get (:eav wm) e)]
    (->> (for [[a vs] e-map
               v vs
               p (get prov [e a v])]
           (:rule p))
         (remove nil?)
         distinct
         (sort-by str)
         vec)))

(defn- materialize-tile-val [main-db branch-db code-db task-db agent-ent health-fn lin eval-vals wm e kind]
  (cond
    (= e :ctx/self)
    {:id (str "tile-ctx-" (sanitize-id (:seon.agent/id agent-ent)))
     :val (with-meta (assoc (select-keys agent-ent
                                         [:seon.agent/id :seon.agent/branch :seon.agent/role
                                          :seon.agent/scope :seon.agent/goal :seon.agent/render-fn
                                          :seon.agent/pinned :seon.agent/stage :seon.agent/canvas
                                          :seon.agent/memory :seon.agent/context-prefs :seon.agent/curated])
                            :seon.ctx/tx (:max-tx branch-db))
                     {:seon/schema :seon.ctx/snapshot})}

    (= e :ctx/lineage)
    (when lin
      {:id (str "tile-lineage-" (sanitize-id (or (:seon.agent/id agent-ent) :agent)))
       :val lin})

    (= kind :seon.health/report)
    (let [ht (or (rules/wm-scalar wm e :ctx/health-target) :main)
          v (or (get eval-vals e) (when health-fn (health-fn (if (= :main ht) main-db branch-db))))]
      (when v
        {:id (str "tile-health-" (sanitize-id (if (vector? e) (second e) ht)))
         :val v}))

    (contains? eval-vals e)
    {:id (str "tile-eval-" (sanitize-id (if (vector? e) (second e) e)))
     :val (get eval-vals e)}

    (integer? e)
    (let [db (case kind
               :seon.task/entity task-db
               :seon.agent/entity main-db
               code-db)
          ent (d/pull db '[*] e)
          ident (or (:seon.code.ns/name ent) (:seon.code.var/name ent) (:seon.schema/key ent)
                    (:seon.task/id ent) (:seon.agent/id ent) (:seon.code.doc/id ent) (:seon.rule/id ent) e)]
      (when (seq (dissoc ent :db/id))
        {:id (str "tile-" (name (or (namespace kind) "ent")) "-" (sanitize-id ident))
         :ref (first (rules/wm-vals wm e :ctx/ref))
         :val (with-meta ent {:seon/schema kind})}))

    :else nil))

(defn derive-context
  "Runs the full deterministic rule engine + cached boundary effects for `agent-ent` and target (`:wire` or `:html`).
   Returns `{:tiles [...] :tail-tiles [...] :wm wm :runs [...] :rules-count n :ms d}`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map :any :any :map] :map]
   :cost/ticks 25000000}
  [main-db branch-db agent-ent evals health-fn
   {:keys [include-evals? include-children? task-db target rules-override]
    :or {include-evals? true include-children? true target :wire}}]
  (let [t0 (System/nanoTime)
        read-main? (or (= :main (:seon.agent/read-branch agent-ent))
                       (:seon.agent/router? agent-ent)
                       (= :coordinator (:seon.agent/role agent-ent)))
        code-db (if read-main? main-db branch-db)
        eff-task-db (or task-db main-db)
        dbs {:main main-db :branch branch-db :code code-db :task eff-task-db}
        active-rules (or rules-override (rules/load-rules code-db default-rules))
        {:keys [wm lineage eval-vals]} (build-seed-wm main-db branch-db code-db eff-task-db agent-ent evals
                                                      {:include-evals? include-evals?
                                                       :include-children? include-children?})
        ;; Deterministic 2-round boundary-effect loop (max 3 rounds)
        final-run
        (loop [cur-wm wm
               round 0]
          (let [res (rules/fire-rules cur-wm dbs active-rules target)
                wm-fx (execute-boundary-effects (:wm res) dbs)]
            (if (and (< round 2) (not= (:facts wm-fx) (:facts (:wm res))))
              (recur wm-fx (inc round))
              res)))
        fwm (:wm final-run)
        view-eids (rules/wm-entities-with fwm :view/fn)
        raw-tiles
        (keep (fn [e]
                (when-not (rules/wm-scalar fwm e :view/suppress?)
                  (let [kind (rules/wm-scalar fwm e :ctx/kind)
                        mat (materialize-tile-val main-db branch-db code-db eff-task-db agent-ent health-fn lineage eval-vals fwm e kind)]
                    (when mat
                      (let [params (extract-view-params fwm e)
                            why (vec (sort (rules/wm-vals fwm e :ctx/why)))]
                        (assoc mat
                               :eid e
                               :schema kind
                               :source (or (rules/wm-scalar fwm e :ctx/source) :rule)
                               :handle (rules/wm-scalar fwm e :ctx/handle)
                               :view-fn (rules/wm-scalar fwm e :view/fn)
                               :zone (or (rules/wm-scalar fwm e :view/zone) :static-prefix)
                               :layout (or (rules/wm-scalar fwm e :view/layout) :expanded-card)
                               :priority (long (or (rules/wm-scalar fwm e :view/priority) 50))
                               :params params
                               :badges (or (:badges params) [])
                               :why why
                               :fired-rules (entity-rules-trace fwm e)))))))
              view-eids)
        ;; Deduplicate by :id, keeping highest priority, and cap secondary hits so context stays sharp
        sorted (->> raw-tiles
                    (sort-by (fn [t] [(- (long (:priority t 50))) (str (:id t))]))
                    (reduce (fn [[seen acc] t]
                              (if (contains? seen (:id t))
                                [seen acc]
                                [(conj seen (:id t)) (conj acc t)]))
                            [#{} []])
                    second)
        prefix-tiles (filterv #(not= :ephemeral-tail (:zone %)) sorted)
        tail-tiles (filterv #(= :ephemeral-tail (:zone %)) sorted)
        run-summary (->> (vals (:runs final-run))
                         (sort-by (juxt :stratum #(str (:rule-id %))))
                         (mapv #(select-keys % [:rule-id :stratum :category :fired :asserted-count :reads-count :ms])))
        ms (/ (- (System/nanoTime) t0) 1e6)]
    {:tiles prefix-tiles
     :tail-tiles tail-tiles
     :all-tiles sorted
     :wm fwm
     :runs run-summary
     :rules-count (count active-rules)
     :facts-count (count (:facts fwm))
     :ms (/ (Math/round (* ms 100.0)) 100.0)}))

(defn discover-tiles
  "Single-source rule-driven tile discovery powering Workspace, Debug, and LLM Wire context."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :seon.host/db :map :any :any] [:vector :map]]
                  [:=> [:cat :seon.host/db :seon.host/db :map :any :any :map] [:vector :map]]]
   :cost/ticks 25000000}
  ([main-db branch-db agent-ent evals health-fn]
   (discover-tiles main-db branch-db agent-ent evals health-fn {}))
  ([main-db branch-db agent-ent evals health-fn opts]
   (:tiles (derive-context main-db branch-db agent-ent evals health-fn opts))))

(defn explain-context
  "Returns a compact human/REPL provenance summary explaining why every tile appeared, its layout,
   zone, params, and which rules fired for `agent-id`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :map]
   :cost/ticks 25000000}
  [main-db branch-db agent-id opts]
  (let [ag (or (d/pull branch-db '[*] [:seon.agent/id agent-id])
               (d/pull main-db '[*] [:seon.agent/id agent-id])
               {:seon.agent/id agent-id :seon.agent/role :worker})
        res (derive-context main-db branch-db ag [] nil (merge {:include-evals? false :include-children? true} opts))]
    {:agent agent-id
     :target (:target opts :wire)
     :ms (:ms res)
     :rules-count (:rules-count res)
     :facts-count (:facts-count res)
     :items (mapv #(select-keys % [:id :schema :source :zone :layout :priority :params :why :fired-rules])
                  (:all-tiles res))
     :fired-rules (filterv #(pos? (:fired %)) (:runs res))}))

(defn replay-agent-turns
  "Steps through `agent-id`'s historical eval turns via Datahike `d/as-of` under `rules-override`,
   showing how derived context sharpens turn by turn."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword [:maybe [:vector :map]]] [:vector :map]]
   :cost/ticks 50000000}
  [main-db branch-db agent-id rules-override]
  (let [eval-txs (->> (d/datoms branch-db :avet :seon.agent.eval/agent agent-id)
                      (map (fn [dt] {:tx (:tx dt)
                                     :turn (:seon.agent.eval/turn (d/pull branch-db '[:seon.agent.eval/turn] (:e dt)))}))
                      (group-by :turn)
                      (map (fn [[turn xs]] {:turn (or turn 0) :tx (apply max (map :tx xs))}))
                      (sort-by :turn)
                      (take 8)
                      vec)]
    (mapv (fn [{:keys [turn tx]}]
            (let [b-at (d/as-of branch-db tx)
                  m-at (d/as-of main-db tx)
                  ag (or (d/pull b-at '[*] [:seon.agent/id agent-id])
                         (d/pull m-at '[*] [:seon.agent/id agent-id])
                         {:seon.agent/id agent-id})
                  res (derive-context m-at b-at ag [] nil
                                      {:include-evals? false :include-children? false
                                       :rules-override rules-override :target :wire})]
              {:turn turn :tx tx :ms (:ms res)
               :items (mapv #(select-keys % [:id :schema :zone :layout :priority :why]) (:all-tiles res))}))
          eval-txs)))
