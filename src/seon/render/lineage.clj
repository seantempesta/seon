(ns seon.render.lineage
  "Pure, entity-pull driven task DAG (`:seon.task/needs` forward -> children, reverse -> parents),
   historical reference tasks & past agent sessions (`:seon.task/scope` + `:seon.agent/task` reverse
   index + `:seon.fix/*`), and starter task templates (`:seon.task/template?`). Single source of truth
   for Workspace, Debug, Canvas, and XML-framed Agent Wire Context (`<task-chain>`, `<historical-tasks>`,
   `<task-templates>`)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.data :refer [first-doc-line]]
            [seon.store :as store]))

(def ^:private task-pull-pattern
  [:seon.task/id :seon.task/title :seon.task/plan :seon.task/scope
   :seon.task/done? :seon.task/needs :seon.task/tests
   :seon.task/template? :seon.task/template
   :seon.task/owner :seon.task/created-by :seon.task/summary])

(defn- pull-task [db tid]
  (when (and db tid (contains? (:schema db) :seon.task/id))
    (when-let [e (:e (first (d/datoms db :avet :seon.task/id tid)))]
      (let [t (d/pull db task-pull-pattern e)]
        (when (:seon.task/id t) t)))))

(defn task-parents
  "Returns direct parent tasks whose `:seon.task/needs` set contains `task-id`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :qualified-keyword]] [:vector :map]]}
  [db task-id]
  (if (or (nil? db) (nil? task-id) (not (contains? (:schema db) :seon.task/needs)))
    []
    (let [indexed? (get-in db [:schema :seon.task/needs :db/index])
          eids (if indexed?
                 (map :e (d/datoms db :avet :seon.task/needs task-id))
                 (keep #(when (= task-id (:v %)) (:e %)) (d/datoms db :aevt :seon.task/needs)))]
      (->> eids
           distinct
           (map #(d/pull db task-pull-pattern %))
           (filter :seon.task/id)
           (sort-by #(str (:seon.task/id %)))
           (take 8)
           vec))))

(defn task-ancestors
  "Walks reverse `:seon.task/needs` upward from `task-id` (cycle-safe, max depth 8), returning root-first ancestors."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :qualified-keyword]] [:vector :map]]}
  [db task-id]
  (if (or (nil? db) (nil? task-id) (not (contains? (:schema db) :seon.task/needs)))
    []
    (loop [cur-id task-id
           seen #{task-id}
           acc []]
      (let [p (first (remove #(contains? seen (:seon.task/id %)) (task-parents db cur-id)))]
        (if (or (nil? p) (>= (count acc) 8))
          (vec (reverse acc))
          (recur (:seon.task/id p) (conj seen (:seon.task/id p)) (conj acc p)))))))

(defn task-children
  "Returns direct child/needed subtasks from `task-id`'s `:seon.task/needs` set."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :qualified-keyword]] [:vector :map]]}
  [db task-id]
  (if (or (nil? db) (nil? task-id))
    []
    (let [cur (pull-task db task-id)
          need-ids (:seon.task/needs cur)]
      (if (empty? need-ids)
        []
        (->> need-ids
             (keep #(pull-task db %))
             (sort-by #(str (:seon.task/id %)))
             (take 12)
             vec)))))

(defn- scope-related? [target-scope cand-scope]
  (cond
    (nil? target-scope) true
    (= target-scope 'seon) true
    (nil? cand-scope) false
    (= target-scope cand-scope) true
    :else
    (let [ts (name target-scope)
          cs (name cand-scope)]
      (or (str/starts-with? cs (str ts "."))
          (str/starts-with? ts (str cs "."))))))

(defn- task-workers [main-db tid]
  (when (and main-db tid (contains? (:schema main-db) :seon.agent/task))
    (->> (d/datoms main-db :avet :seon.agent/task tid)
         (map #(d/pull main-db '[:seon.agent/id :seon.agent/branch :seon.agent/role :seon.agent/merged-tx] (:e %)))
         (filter :seon.agent/id)
         (take 4)
         vec)))

(defn historical-tasks-for
  "Pulls prior/related non-template tasks touching `scope`, enriched with past agent sessions
   that worked them via reverse index `:seon.agent/task`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :symbol] [:set :qualified-keyword]] [:vector :map]]}
  [main-db scope exclude-ids]
  (if (or (nil? main-db) (not (contains? (:schema main-db) :seon.task/id)))
    []
    (let [cands (->> (d/datoms main-db :aevt :seon.task/id)
                     (map #(d/pull main-db task-pull-pattern (:e %)))
                     (filter (fn [t]
                               (and (:seon.task/id t)
                                    (not (:seon.task/template? t))
                                    (not (contains? exclude-ids (:seon.task/id t)))
                                    (scope-related? scope (:seon.task/scope t)))))
                     (sort-by (fn [t]
                                [(if (:seon.task/done? t) 0 1)
                                 (if (not (str/blank? (str (:seon.task/summary t)))) 0 1)
                                 (if (= scope (:seon.task/scope t)) 0 1)
                                 (str (:seon.task/id t))]))
                     (take 6)
                     vec)]
      (mapv (fn [t]
              (let [ws (task-workers main-db (:seon.task/id t))]
                (cond-> t
                  (seq ws) (assoc :past-agents ws))))
            cands))))

(defn- format-task-line [prefix t]
  (let [status (cond (:seon.task/template? t) "template"
                     (:seon.task/done? t) "done"
                     :else "open")
        owner (when-let [o (:seon.task/owner t)] (str " · owner " o))
        scope (when-let [sc (:seon.task/scope t)] (str " · scope " sc))
        agents (when (seq (:past-agents t))
                 (str " · agents " (str/join ", " (map :seon.agent/id (:past-agents t)))))
        summary (when-not (str/blank? (str (:seon.task/summary t)))
                  (str "\n    summary: " (first-doc-line (:seon.task/summary t) 180)))]
    (str prefix "[" status "] " (:seon.task/id t) " — " (:seon.task/title t)
         scope owner agents summary)))

(defn task-lineage-tile
  "Malli-attached render for `:seon.task/lineage`. Emits XML-framed `<task-chain>`,
   `<historical-tasks>`, and `<task-templates>` in `:mode :agent`, and structured Hiccup cards
   in Human Browser UI (Workspace & Debug)."
  {:malli/schema [:=> [:cat :map [:maybe :map]] :any]}
  [opts lineage]
  (when lineage
    (let [{:keys [current ancestors children historical fixes templates]} lineage
          agent-mode? (= :agent (:mode opts))]
      (if agent-mode?
        (let [chain-lines
              (when (or current (seq ancestors) (seq children))
                (str "<task-chain>\n"
                     (str/join "\n"
                               (concat
                                (map-indexed (fn [i a]
                                               (format-task-line (str (apply str (repeat i "  ")) "↑ needed-by: ") a))
                                             ancestors)
                                (when current
                                  [(str "● current: " (:seon.task/id current) " — " (:seon.task/title current)
                                        (when (:seon.task/template current) (str " (from " (:seon.task/template current) ")"))
                                        (when (:seon.task/created-by current) (str " · created-by " (:seon.task/created-by current)))
                                        (when (:seon.task/scope current) (str " · scope " (:seon.task/scope current)))
                                        (when (and (not (get-in opts [:params :omit-current-plan?]))
                                                   (not (str/blank? (str (:seon.task/plan current)))))
                                        (str "\n  plan: " (:seon.task/plan current)))
                                        (when (seq (:seon.task/tests current))
                                          (str "\n  tests: " (str/join ", " (map str (:seon.task/tests current))))))])
                                (map #(format-task-line "  ↓ needs: " %) children)))
                     "\n</task-chain>"))
              hist-lines
              (when (or (seq historical) (seq fixes))
                (str "<historical-tasks>\n"
                     (str/join "\n"
                               (concat
                                (map #(format-task-line "- " %) historical)
                                (map (fn [fx]
                                       (str "- [fix] " (:seon.fix/id fx) " (" (:seon.fix/error-class fx) "): "
                                            (first-doc-line (or (:seon.fix/change-summary fx) (:seon.fix/root-cause fx)) 160)))
                                     fixes)))
                     "\n</historical-tasks>"))
              tpl-lines
              (when (and (not (get-in opts [:params :omit-templates?])) (seq templates))
              (str "<task-templates>\n"
                   (str/join "\n"
                             (map (fn [t]
                                    (str "- " (:seon.task/id t) " — " (:seon.task/title t)
                                         " · use `(user/create-task! {:id :ns/topic :from-template " (:seon.task/id t) " :title \"...\" :scope 'ns :plan \"...\"})`"))
                                  templates))
                   "\n</task-templates>"))]
          [:div
           (when chain-lines [:div chain-lines])
           (when hist-lines [:div hist-lines])
           (when tpl-lines [:div tpl-lines])])
        [:div {:class "card tile-card"}
         [:div {:class "card-head"}
          [:div {:class "pill-row"}
           [:span {:class "badge purple"} "task chain"]
           [:strong (or (:seon.task/title current) "Task Lineage & History")]]
          (when (:seon.task/id current)
            [:span {:class "muted tiny mono"} (str (:seon.task/id current))])]
         (when (seq ancestors)
           [:div {:class "stack-xs"}
            [:div {:class "muted tiny"} "Needed by (parent tasks)"]
            (for [a ancestors]
              [:div {:class "pill-row"}
               [:span {:class "pill mono"} (str "↑ " (:seon.task/id a))]
               [:span {:class "muted tiny"} (first-doc-line (:seon.task/title a) 72)]])])
         (when (seq children)
           [:div {:class "stack-xs"}
            [:div {:class "muted tiny"} (str "Needs / child subtasks (" (count children) ")")]
            (for [c children]
              [:div {:class "pill-row"}
               [:span {:class (str "badge " (if (:seon.task/done? c) "green" "blue"))}
                (if (:seon.task/done? c) "done" "open")]
               [:span {:class "mono tiny"} (str (:seon.task/id c))]
               (when (:seon.task/owner c) [:span {:class "pill mono"} (str (:seon.task/owner c))])
               [:span {:class "muted tiny"} (first-doc-line (:seon.task/title c) 64)]])])
         (when (or (seq historical) (seq fixes))
           [:details {:class "activity-fold"}
            [:summary (str "Historical tasks & fixes · " (+ (count historical) (count fixes)))]
            [:div {:class "stack-xs"}
             (for [h historical]
               [:div {:class "sm-copy"}
                [:span {:class (str "badge " (if (:seon.task/done? h) "green" "muted"))}
                 (if (:seon.task/done? h) "done" "prior")]
                " " [:strong {:class "mono"} (str (:seon.task/id h))] " — " (:seon.task/title h)
                (when-not (str/blank? (str (:seon.task/summary h)))
                  [:div {:class "muted tiny"} (first-doc-line (:seon.task/summary h) 140)])])
             (for [fx fixes]
               [:div {:class "sm-copy"}
                [:span {:class "badge amber"} "fix"] " "
                [:strong {:class "mono"} (str (:seon.fix/id fx))] " — "
                (first-doc-line (or (:seon.fix/change-summary fx) (:seon.fix/root-cause fx)) 120)])]])
         (when (seq templates)
           [:details {:class "activity-fold"}
            [:summary (str "Starter task templates · " (count templates))]
            [:div {:class "pill-row"}
             (for [t templates]
               [:span {:class "pill mono"} (str (:seon.task/id t))])]])]))))

(defonce ^:private ^:seon.flow/edge lineage-aux-cache
  (store/lru-cache 32))

(defn scope-fixes-for
"Pulls recorded `:seon.fix/*` class fixes relevant to `scope`."
{:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :symbol]] [:vector :map]]}
[main-db scope]
(if (or (nil? main-db) (not (contains? (:schema main-db) :seon.fix/id)))
  []
  (let [compute (fn []
                  (let [sn (when scope (name scope))
                        all (->> (d/datoms main-db :aevt :seon.fix/id)
                                 (map #(d/pull main-db '[:seon.fix/id :seon.fix/error-class :seon.fix/root-cause
                                                         :seon.fix/change-summary :seon.fix/regression-test
                                                         :seon.fix/created-ms] (:e %)))
                                 (filter :seon.fix/id)
                                 (sort-by #(- (long (or (:seon.fix/created-ms %) 0))))
                                 vec)
                        matched (if (or (nil? sn) (= sn "seon"))
                                  all
                                  (filterv (fn [fx]
                                             (let [hay (str (:seon.fix/error-class fx) " "
                                                            (:seon.fix/change-summary fx) " "
                                                            (:seon.fix/regression-test fx))]
                                               (str/includes? hay sn)))
                                           all))]
                    (vec (take 4 (if (seq matched) matched (take 2 all))))))
        cid (when (and (not (instance? datahike.db.AsOfDB main-db)) (not (instance? datahike.db.HistoricalDB main-db)))
              (try (d/commit-id main-db) (catch Throwable _ nil)))
        ck (when cid [:fixes (get-in main-db [:config :store :id]) cid (:max-tx main-db) scope])]
    (if ck
      (store/lru-get! lineage-aux-cache 32 ck compute)
      (compute)))))

(defn available-templates
"Lists starter `:seon.task/template? true` task templates available to customize."
{:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :map]]}
[main-db]
(if (or (nil? main-db) (not (contains? (:schema main-db) :seon.task/template?)))
  []
  (let [compute (fn []
                  (->> (d/datoms main-db :avet :seon.task/template? true)
                       (map #(d/pull main-db '[:seon.task/id :seon.task/title :seon.task/scope] (:e %)))
                       (filter :seon.task/id)
                       (sort-by #(str (:seon.task/id %)))
                       (take 8)
                       vec))
        cid (when (and (not (instance? datahike.db.AsOfDB main-db)) (not (instance? datahike.db.HistoricalDB main-db)))
              (try (d/commit-id main-db) (catch Throwable _ nil)))
        ck (when cid [:templates (get-in main-db [:config :store :id]) cid (:max-tx main-db)])]
    (if ck
      (store/lru-get! lineage-aux-cache 32 ck compute)
      (compute)))))

(defn agent-task-lineage
  "Derives the full task chain (`:seon.task/needs` ancestors + child subtasks), historical
   reference tasks + past agents, relevant `:seon.fix/*` records, and starter templates strictly
   from `agent-ent`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :map]] [:maybe :map]]}
  [main-db branch-db agent-ent]
  (when (and main-db agent-ent)
    (let [tid (:seon.agent/task agent-ent)
          cur (or (pull-task branch-db tid) (pull-task main-db tid))
          scope (or (:seon.task/scope cur) (:seon.agent/scope agent-ent))
          ancestors (if tid (task-ancestors main-db tid) [])
          children (if tid (task-children main-db tid) [])
          exclude (into (if tid #{tid} #{}) (concat (map :seon.task/id ancestors)
                                                    (map :seon.task/id children)))
          historical (historical-tasks-for main-db scope exclude)
          fixes (scope-fixes-for main-db scope)
          templates (available-templates main-db)]
      (when (or cur (seq ancestors) (seq children) (seq historical) (seq fixes) (seq templates))
        (with-meta
          {:agent-id (:seon.agent/id agent-ent)
           :scope scope
           :current cur
           :ancestors ancestors
           :children children
           :historical historical
           :fixes fixes
           :templates templates}
          {:seon/schema :seon.task/lineage})))))