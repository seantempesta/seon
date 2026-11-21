(ns seon.render.walk
  "Fine-grained incremental `step-walk` unit builders for Workspace and Debug views, plus
   DOM-ID-preserving `#seon-view-unit` shell composition for initial SSR and structural transitions."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.render :as render]
            [seon.render.canvas :as rcanvas]
            [seon.render.data :as rdata]
            [seon.render.debug :as rdebug]
            [seon.render.markdown :as rmd]
            [seon.store :as store]))

(def shell-unit-ids
  "Mounted non-view shell unit IDs whose DOM roots live outside `#seon-view-unit`."
  #{"seon-css" "seon-header" "seon-sidebar" "seon-stream-unit" "seon-composer-unit"})

(defonce ^:private ^:seon.flow/edge ws-tiles-cache
  (store/lru-cache 16))

(defn- snap-key [db]
  (when (and db
             (not (instance? datahike.db.AsOfDB db))
             (not (instance? datahike.db.HistoricalDB db)))
    (when-let [cid (try (d/commit-id db) (catch Throwable _ nil))]
      [(get-in db [:config :store :id])
       (get-in db [:config :branch] :main)
       cid
       (:max-tx db)])))

(defn- branch-code-sig [db]
  (if (and db (contains? (:schema db) :seon.code.form/hash))
    (reduce (fn [[n mx] dt] [(inc n) (max mx (long (:tx dt 0)))]) [0 0] (d/datoms db :aevt :seon.code.form/hash))
    [0 0]))

(defn workspace-tiles
  "Returns rule-discovered `:target :html` Workspace tiles for `viewing-id`, cached per snapshot."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/conn] [:maybe :keyword]] [:vector :map]]
   :cost/ticks 15000000}
  [main-db conn viewing-id]
  (let [aid (or viewing-id (rdata/entry-agent-id main-db))
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (rdata/branch-db-for main-db conn branch)
        compute (fn []
                  (let [ag (rdata/pull-agent-entity main-db bdb aid)
                        evals (rdata/agent-evals-in bdb aid)]
                    (render/discover-renderable-tiles main-db bdb ag evals health/report {:target :html})))
        mk (snap-key main-db)
        bk (snap-key bdb)]
    (if (and mk bk)
      (store/lru-get! ws-tiles-cache 16 [mk bk aid] compute)
      (compute))))

(defn ws-left-unit
  "Independent `step-walk` unit for Workspace's chronological left pane (`#ws-left-unit`)."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :any]]}
  [main-db {:keys [conn branch-feedback viewing-id]}]
  (let [aid (or viewing-id (rdata/entry-agent-id main-db))
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (rdata/branch-db-for main-db conn branch)
        ag (rdata/pull-agent-entity main-db bdb aid)
        evals (rdata/agent-evals-in bdb aid)
        msgs (rdata/agent-messages-in main-db aid)
        seen-ids (store/agent-seen-message-ids bdb aid)
        exchanges (rmd/split-exchanges (rmd/interleave-timeline msgs evals seen-ids))
        retracted-evals (filterv :seon.agent.eval/retracted-tx (store/agent-historical-evals bdb aid))
        retracted-exchanges (when (seq retracted-evals)
                              (let [r-seen (into #{} (mapcat :seon.agent.eval/seen-messages) retracted-evals)
                                    r-msgs (filterv #(contains? r-seen (:seon.agent.message/id %)) msgs)]
                                (rmd/split-exchanges (rmd/interleave-timeline r-msgs retracted-evals))))
        derived (when (and conn (first (d/datoms main-db :avet :seon.agent/id aid)))
                  ((requiring-resolve 'seon.reconciler/derive-agent-status) main-db conn {} #{} #{} aid))]
    (rcanvas/ws-left-panel aid branch bdb ag derived branch-feedback retracted-exchanges exchanges)))

(defn ws-right-head-unit
  "Independent `step-walk` unit for Workspace's right pane header + pin form (`#ws-right-head-unit`)."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :any]]}
  [main-db {:keys [viewing-id tile-count]}]
  (rcanvas/ws-right-head (or viewing-id (rdata/entry-agent-id main-db)) (long (or tile-count 0))))

(defn ws-tile-unit
  "Independent `step-walk` unit for a single Workspace tile card (`#tile-...`)."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :any]]}
  [main-db {:keys [conn viewing-id tile]}]
  (let [aid (or viewing-id (rdata/entry-agent-id main-db))
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (rdata/branch-db-for main-db conn branch)]
    (rmd/render-tile-card bdb main-db tile aid)))

(defn ws-tiles-empty-unit
  "Placeholder `step-walk` unit when a Workspace walk has 0 tiles yet (`#ws-tiles-empty`)."
  [_main-db _opts]
  [:div {:id "ws-tiles-empty" :class "card muted sm-copy"}
   "No renderable tiles in this agent's walk yet. Evaluate " [:code "(user/health)"] " or "
   [:code "[:seon.code.ns/name 'seon.render]"] " on the left to watch it appear here live."])

(defn workspace-unit-specs
  "Builds fine-grained `step-walk` unit specs for `:workspace` view (left pane, right header, per-tile units)."
  {:malli/schema [:=> [:cat :seon.host/db :map [:maybe :keyword]] [:vector :map]]}
  [main-db st viewing-id]
  (let [conn (:conn st)
        aid (or viewing-id (rdata/entry-agent-id main-db))
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (rdata/branch-db-for main-db conn branch)
        btx (:max-tx bdb)
        csig (when (not= bdb main-db) (branch-code-sig bdb))
        tiles (workspace-tiles main-db conn aid)
        tile-units (if (empty? tiles)
                     [{:id "ws-tiles-empty" :comp #'ws-tiles-empty-unit :args [{}]}]
                     (mapv (fn [t]
                             (let [sk (:schema t)
                                   arg (cond-> {:conn conn :viewing-id aid :tile t}
                                         (= sk :seon.ctx/snapshot) (assoc :branch-tx btx)
                                         (and csig (contains? #{:seon.code.ns/entity :seon.code.var/entity :seon.schema/entity} sk))
                                         (assoc :branch-code-sig csig))]
                               {:id (:id t) :comp #'ws-tile-unit :args [arg]}))
                           tiles))]
    (into [{:id "ws-left-unit" :comp #'ws-left-unit
            :args [{:conn conn :viewing-id aid :branch-tx btx :branch-feedback (:branch-feedback st)}]}
           {:id "ws-right-head-unit" :comp #'ws-right-head-unit
            :args [{:viewing-id aid :tile-count (count tiles)}]}]
          tile-units)))

(defn compose-view-html
  "Assembles `#seon-view-unit` HTML from mounted `walk` units for initial SSR and structural transitions."
  {:malli/schema [:function
                  [:=> [:cat :map] :string]
                  [:=> [:cat :map :any] :string]
                  [:=> [:cat :map [:maybe :keyword] [:maybe :keyword]] :string]]}
  ([walk] (compose-view-html walk nil nil))
  ([walk db]
   (let [vs (when db (rdata/user-view-state db))]
     (compose-view-html walk (:viewing-id vs) (:view-mode vs))))
  ([walk viewing-id view-mode]
   (let [units (:units walk)
         uhtml (fn [id] (get-in units [id :html] ""))
         eff-mode (or view-mode
                      (cond
                        (contains? units "seon-view-unit") :canvas
                        (contains? units "dbg-wire-unit") :debug
                        (contains? units "ws-left-unit") :workspace
                        :else :canvas))
         attr-s (str (when viewing-id (str " data-agent=\"" (render/esc (str viewing-id)) "\""))
                     " data-view-mode=\"" (name eff-mode) "\"")]
     (cond
       (contains? units "seon-view-unit")
       (uhtml "seon-view-unit")

       (= eff-mode :debug)
       (str "<div id=\"seon-view-unit\" class=\"debug-wrap\"" attr-s ">"
            (uhtml "dbg-feedback-unit")
            (uhtml "dbg-wire-unit")
            (uhtml "dbg-ctx-unit")
            (uhtml "dbg-rules-unit")
            (uhtml "dbg-versions-unit")
            "<div class=\"grid-2\">" (uhtml "dbg-health-unit") (uhtml "dbg-branches-unit") "</div>"
            (uhtml "dbg-schema-unit")
            (uhtml "dbg-tests-unit")
            (uhtml "dbg-drill-unit")
            "</div>")

       (= eff-mode :workspace)
       (let [tile-ids (remove #(or (contains? shell-unit-ids %)
                                   (= "ws-left-unit" %)
                                   (= "ws-right-head-unit" %))
                              (:order walk))]
         (str "<div id=\"seon-view-unit\" class=\"workspace-grid\"" attr-s ">"
              (uhtml "ws-left-unit")
              "<section class=\"ws-right\">"
              (uhtml "ws-right-head-unit")
              "<div class=\"tiles-scroll\">"
              (apply str (map uhtml tile-ids))
              "</div></section></div>"))

       :else ""))))

(defn effective-patches
  "Returns SSE patches for `step-walk`, morphing `#seon-view-unit` cleanly whenever mounted unit order changes."
  {:malli/schema [:=> [:cat :map :map [:vector :string] :any] [:vector :string]]}
  [prev-walk walk patches db]
  (let [prev-ord (:order prev-walk)
        new-ord (:order walk)]
    (if (and (seq prev-ord) (not= prev-ord new-ord))
      (let [shell-patches (filterv (fn [p]
                                     (let [head (subs p 0 (min (count p) 220))]
                                       (some #(str/includes? head (str "id=\"" % "\"")) shell-unit-ids)))
                                   patches)
            view-html (compose-view-html walk db)]
        (if (str/blank? view-html)
          shell-patches
          (conj shell-patches (render/datastar-patch-event view-html))))
      patches)))
