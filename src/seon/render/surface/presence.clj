(ns seon.render.surface.presence
  "Auto-updating present-tense Live Canvas Presence and optional direct/reactive agent Canvas
   (`:seon.agent/canvas-hiccup` and `:seon.agent/canvas-fn`), 100% `d/as-of` time-travelable
   and XSS-sanitized."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx.touches :as ctouches]
            [seon.render.data :as rdata]
            [seon.render.links :as rlinks :refer [badge ent-link kicker]]
            [seon.store :as store :refer [db-schema]]
            [seon.render.sanitize :as sanitize]
            [seon.render.fit :as fit]
            [seon.render.wire :as rwire]
            [seon.render.text :refer [clip]]))

(defn sanitize-canvas-hiccup
  "Pure recursive allowlist XSS sanitizer for agent-supplied Canvas Hiccup via `seon.render.sanitize/sanitize-hiccup`
   (`:allow-seon-actions? true` permits `:button` and strictly allowlisted `(seon.web/...)` `:on-click` actions)."
  {:malli/schema [:=> [:cat :any] :any]
   :cost/ticks 5000000}
  [node]
  (sanitize/sanitize-hiccup node {:allow-seon-actions? true}))

(defn- pull-child-agents [main-db aid]
  (if-not (and main-db aid)
    []
    (let [dh (db-schema main-db)
          aeid (when (dh :seon.agent/id) (:e (first (d/datoms main-db :avet :seon.agent/id aid))))
          by-ref (when (and aeid (dh :seon.agent/parent-ref))
                   (map :e (d/datoms main-db :avet :seon.agent/parent-ref aeid)))
          by-kw (when (dh :seon.agent/parent)
                  (map :e (d/datoms main-db :avet :seon.agent/parent aid)))
          eids (distinct (concat by-ref by-kw))
          pat '[:seon.agent/id :seon.agent/label :seon.agent/branch :seon.agent/archived?
                :seon.agent/ready? :seon.agent/waiting? :seon.agent/paused? :seon.agent/merged-tx :seon.agent/summary]]
      (->> eids
           (map #(d/pull main-db pat %))
           (remove #(or (nil? (:seon.agent/id %)) (:seon.agent/archived? %)))
           (sort-by #(str (:seon.agent/id %)))
           vec))))

(defn- pull-agent-calls [main-db aid]
  (if-not (and main-db aid)
    []
    (let [dh (db-schema main-db)
          aeid (when (dh :seon.agent/id) (:e (first (d/datoms main-db :avet :seon.agent/id aid))))
          by-ref (when (and aeid (dh :seon.agent.call/caller-ref))
                   (map :e (d/datoms main-db :avet :seon.agent.call/caller-ref aeid)))
          by-kw (when (dh :seon.agent.call/caller)
                  (map :e (d/datoms main-db :avet :seon.agent.call/caller aid)))
          eids (distinct (concat by-ref by-kw))
          pat '[:seon.agent.call/id :seon.agent.call/callee :seon.agent.call/status
                :seon.agent.call/prompt :seon.agent.call/result :seon.agent.call/error :seon.agent.call/created-tx]]
      (->> eids
           (map #(d/pull main-db pat %))
           (filter :seon.agent.call/id)
           (sort-by #(long (or (:seon.agent.call/created-tx %) 0)))
           vec))))

(defn derive-presence-state
  "Derives present-tense live presence state for `agent-id` from `main-db` and `branch-db`
   (100% `d/as-of` time-travelable: children, delegated calls, and in Dev Mode branch writes & proofs)."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword] [:maybe :map]] :map]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword] [:maybe :map] [:maybe :map]] :map]]
   :cost/ticks 15000000}
  ([main-db branch-db agent-id agent]
   (derive-presence-state main-db branch-db agent-id agent {:expert-mode? true}))
  ([main-db branch-db agent-id agent opts]
   (let [bdb (or branch-db main-db)
         dh (when bdb (db-schema bdb))
         dev? (not (false? (:expert-mode? opts)))
         children (pull-child-agents main-db agent-id)
         calls (pull-agent-calls main-db agent-id)
         br (or (:seon.agent/branch agent) (when bdb (get-in bdb [:config :branch])) :main)
         writes (when (and dev? main-db bdb (not= br :main))
                  (ctouches/branch-write-eids main-db bdb nil))
         written-vars (if (seq writes)
                        (vec (keep (fn [e]
                                     (when (and dh (dh :seon.code.var/name))
                                       (:v (first (d/datoms bdb :eavt e :seon.code.var/name)))))
                                   (keys writes)))
                        [])
         proof-run (when (and dev? bdb dh (dh :seon.test.run/branch))
                     (when-let [e (:e (first (d/datoms bdb :avet :seon.test.run/branch br)))]
                       (d/pull bdb '[:seon.test.run/pass? :seon.test.run/ran :seon.test.run/cached :seon.test.run/failed-digests] e)))]
     {:children children
      :calls calls
      :written-vars (vec (sort written-vars))
      :proof-run proof-run})))

(defn- child-status-badge [c]
  (cond
    (pos? (long (or (:seon.agent/merged-tx c) 0))) (badge "green" "merged")
    (:seon.agent/ready? c) (badge "green" "ready")
    (:seon.agent/paused? c) (badge "muted" "paused")
    (:seon.agent/waiting? c) (badge "blue" "waiting")
    :else (badge "amber" "running")))

(defn- call-status-badge [st]
  (case st
    :resolved (badge "green" "resolved")
    :failed (badge "red" "failed")
    :cancelled (badge "muted" "cancelled")
    (badge "amber" "pending")))

(defn- pull-fit-ref [main-db branch-db r]
  (let [nr (rdata/normalize-inspect-ref r)
        bdb (or branch-db main-db)
        try-pull (fn [db]
                   (when (and db nr (instance? datahike.db.interface.ISearch db))
                     (let [[k v] nr
                           dh (db-schema db)
                           eid (cond
                                 (and (= :db/id k) (integer? v)) v
                                 (and (keyword? k) (:db/unique (get dh k)))
                                 (:e (first (d/datoms db :avet k v)))
                                 :else nil)]
                       (when (integer? eid)
                         (let [m (d/pull db (quote [*]) eid)]
                           (when (and (map? m) (> (count m) 1)) m))))))]
    (when nr
      (when-let [ent (or (try-pull bdb) (when (and main-db (not= main-db bdb)) (try-pull main-db)))]
        {:ref nr :ent ent}))))

(defn render-fitted-spec
  "Fits Datahike `:ref`/`:refs` and/or inline `:items` through `seon.render.fit/fit-projections`
   for the live viewport, rendering clean distraction-free cards in Mom Mode (`expert-mode? false`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :map [:maybe :map]] :any]
   :cost/ticks 25000000}
  [main-db branch-db spec opts]
  (let [o (if (map? opts) opts {})
        bdb (or branch-db main-db)
        exp? (boolean (:expert-mode? o))
        refs (cond-> []
               (:ref spec) (conj (:ref spec))
               (sequential? (:refs spec)) (into (:refs spec)))
        ref-projs (vec (keep-indexed
                        (fn [idx r]
                          (when-let [{:keys [ref ent]} (pull-fit-ref main-db bdb r)]
                            (cond-> {:entity (or (:db/id ent) ref) :ref ref :data ent :priority (- 100 idx)}
                              (:schema spec) (assoc :schema (:schema spec)))))
                        refs))
        item-projs (vec (keep-indexed
                         (fn [idx it]
                           (when (and (map? it) (map? (:data it)))
                             (cond-> {:entity (or (:entity it) (:id it) (keyword "fit-item" (str idx)))
                                      :data (:data it)
                                      :priority (- 100 (+ (count ref-projs) idx))}
                               (or (:schema it) (:schema spec)) (assoc :schema (or (:schema it) (:schema spec))))))
                         (when (sequential? (:items spec)) (:items spec))))
        projs (into ref-projs item-projs)]
    (if (empty? projs)
      (when exp? [:div {:class "muted tiny mono"} "No matching entities to fit."])
      (let [vw (long (or (get-in o [:viewport :w]) (:viewport-w o) 1280))
            vh (long (or (get-in o [:viewport :h]) (:viewport-h o) 820))
            sb? (boolean (get-in o [:viewport :sidebar-open?]))
            sb-w (long (or (get-in o [:slot-budget :avail-w]) (max 240 (- vw 32))))
            mom-canvas? (and (not exp?) (not= :session (:canon-mode o)))
            aw (long (or (:avail-w spec) (if mom-canvas? (min sb-w 996) sb-w)))
            ah (long (or (get-in o [:slot-budget :avail-h]) (max 240 (- vh 80))))
            am (double (or (get-in o [:ui-config :area-multiplier]) 1.6))
            ratio (double (or (get-in o [:ui-config :per-item-area-ratio]) 0.45))
            ab (long (or (:area-budget spec)
                         (min (long (or (get-in o [:slot-budget :area-budget]) Long/MAX_VALUE))
                              (long (* am (double (* aw ah)))))))
            pib (long (or (:per-item-budget spec) (max 1000 (long (* (double ab) ratio)))))
            max-n (long (or (:max-items spec) (max (count projs) (long (or (get-in o [:ui-config :max-items]) 8)))))
            fitted (fit/fit-projections bdb projs
                     {:target :html :viewport-w vw :viewport-h vh :sidebar-open? sb?
                      :avail-w aw :area-budget ab :per-item-budget pib :max-items max-n
                      :strategy (or (:strategy spec) :breadth-first)
                      :main-db main-db :fallback-renderers rwire/fallback-entity-renderers})
            selected (vec (:selected fitted))
            all-chips? (and (seq selected) (every? #(or (<= (long (or (:min-width %) 999)) 240) (and (pos? (long (or (:h %) 0))) (<= (long (:h %)) 64))) selected))
            has-wide? (boolean (some #(>= (long (or (:min-width %) 0)) 520) selected))
            layout (or (:layout spec)
                       (cond all-chips? :chips
                             (or has-wide? (< aw 700) (= 1 (count selected))) :stack
                             :else :grid))
            grid-cls (case layout
                       :chips "mom-fit-chips pill-row"
                       :stack "mom-fit-stack stack-sm"
                       "mom-fit-grid")
            meas-by (if (some #(= :browser (:measured-by %)) selected) "browser" "static")
            title (when (string? (:title spec)) (not-empty (str/trim (:title spec))))
            sub (when (string? (:subtitle spec)) (not-empty (str/trim (:subtitle spec))))]
        [:div {:class "mom-fit-wrap stack-sm"
               :data-fit-avail-w (str aw)
               :data-fit-count (str (count selected))
               :data-fit-layout (name layout)
               :data-measured-by meas-by}
         (when (or title sub exp?)
           [:div {:class "row-between"}
            [:div {:class "stack-xs"}
             (when title [:h2 {:class "mom-fit-title"} title])
             (when sub [:p {:class "muted sm-copy"} sub])]
            (when exp?
              [:div {:class "pill-row"}
               (badge "blue" (str aw "px"))
               (badge (if (= "browser" meas-by) "green" "muted") meas-by)
               [:span {:class "mono tiny muted"} (str (count selected) " fitted")]])])
         (into [:div {:class grid-cls}]
               (map-indexed
                (fn [idx item]
                  (let [cfn (:chosen-fn item)
                        mb (name (or (:measured-by item) :static))
                        card (or (when (:hiccup item) (sanitize-canvas-hiccup (:hiccup item)))
                                 [:div {:class "ent-row mono tiny"} (str (:entity item))])]
                    [:div {:class "mom-fit-item"
                           :style (str "view-transition-name:seon-fit-" idx)
                           :data-chosen-fn (str cfn)
                           :data-measured-by mb
                           :data-tokens (str (:tokens item 0))
                           :data-area (str (:area item 0))}
                     (when exp?
                       [:div {:class "ent-prov-bar"}
                        [:div {:class "pill-row"}
                         (when (qualified-symbol? cfn)
                           (ent-link [:seon.code.var/name cfn] (name cfn) "mono tiny muted"))
                         [:span {:class "mono tiny muted"} (str "· " (:tokens item 0) " tok · " mb)]]])
                     card]))
                selected))]))))

(defn- has-fit-node? [node depth]
  (cond
    (> depth 24) false
    (and (vector? node) (= :seon/fit (first node)) (map? (second node))) true
    (vector? node) (boolean (some #(has-fit-node? % (inc depth)) (if (map? (second node)) (drop 2 node) (rest node))))
    (seq? node) (boolean (some #(has-fit-node? % (inc depth)) node))
    :else false))

(defn- expand-canvas-node [main-db bdb node model depth]
  (cond
    (> depth 24) nil
    (and (vector? node) (= :seon/fit (first node)) (map? (second node)))
    (render-fitted-spec main-db bdb (second node) model)
    (not (has-fit-node? node depth))
    (sanitize-canvas-hiccup node)
    (and (vector? node) (keyword? (first node)))
    (let [has-a? (map? (second node))
          shell (if has-a? [(first node) (second node)] [(first node)])
          clean-shell (sanitize-canvas-hiccup shell)
          kids (if has-a? (drop 2 node) (rest node))]
      (when (vector? clean-shell)
        (into clean-shell (keep #(expand-canvas-node main-db bdb % model (inc depth)) kids))))
    (seq? node)
    (vec (keep #(expand-canvas-node main-db bdb % model (inc depth)) node))
    :else (sanitize-canvas-hiccup node)))

(defn- eval-canvas-fn [model bdb agent fn-sym]
  (when (qualified-symbol? fn-sym)
    (try
      (when-let [ns-obj (find-ns (symbol (namespace fn-sym)))]
        (when-let [v (ns-resolve ns-obj (symbol (name fn-sym)))]
          (when (and (var? v) (bound? v))
            (let [als (:arglists (meta v))
                  one-arg-only? (and (seq als) (every? #(= 1 (count %)) als))
                  ctx {:seon.agent/entity agent :db bdb :main-db (:main-db model)
                       :viewport (:viewport model) :slot-budget (:slot-budget model)
                       :ui-config (:ui-config model) :expert-mode? (boolean (:expert-mode? model))}
                  ag-ctx (assoc agent
                                :seon.ui/viewport (:viewport model)
                                :seon.ui/slot-budget (:slot-budget model)
                                :seon.ui/main-db (:main-db model)
                                :seon.ui/expert-mode? (boolean (:expert-mode? model)))
                  raw (if one-arg-only? (@v ctx) (@v bdb ag-ctx))
                  node (if (and (map? raw) (contains? raw :seon.ui/hiccup)) (:seon.ui/hiccup raw) raw)]
              (expand-canvas-node (:main-db model) bdb node model 0)))))
      (catch Throwable ex
        [:div {:class "card mono tiny muted"}
         (str "Canvas renderer " fn-sym " error: " (or (.getMessage ex) (str ex)))]))))

(defn render-live-canvas-presence
  "Renders custom agent Canvas (`:seon.agent/canvas-fn` or `:seon.agent/canvas-hiccup`, sanitized)
   and auto-derived present-tense Live Presence (streaming progress, active child agents, delegated
   calls returning results, and in Dev Mode branch writes & proofs)."
  {:malli/schema [:=> [:cat :map] :any]
   :cost/ticks 15000000}
  [{:keys [main-db branch-db viewing-id agent live-stream expert-mode? as-of-tx] :as model}]
  (let [bdb (or branch-db main-db)
        cfn (:seon.agent/canvas-fn agent)
        chic (:seon.agent/canvas-hiccup agent)
        custom-node (cond
                      (qualified-symbol? cfn)
                      (when-let [rendered (eval-canvas-fn model bdb agent cfn)]
                        [:section {:id "cv-custom-canvas" :class "canvas-surface stack-sm" :data-canvas-mode "reactive"}
                         (when expert-mode?
                         [:div {:class "row-between"}
                          (kicker "Live Agent Canvas")
                          [:div {:class "pill-row"}
                           (badge "purple" "reactive")
                           (ent-link [:seon.code.var/name cfn] (str cfn) "mono tiny muted")]])
                         rendered])

                      (vector? chic)
                      (when-let [clean (expand-canvas-node main-db bdb chic model 0)]
                        [:section {:id "cv-custom-canvas" :class "canvas-surface stack-sm" :data-canvas-mode "hiccup"}
                         (when expert-mode?
                         [:div {:class "row-between"}
                          (kicker "Live Agent Canvas")
                          (badge "purple" "hiccup")])
                         clean]))
        pres (derive-presence-state main-db bdb viewing-id agent {:expert-mode? (boolean expert-mode?)})
        {:keys [children calls written-vars proof-run]} pres
        streaming? (and (map? live-stream) (some? (:phase live-stream)) (not= :idle (:phase live-stream)))
        show-presence? (or streaming? (seq children) (seq calls) (and expert-mode? (or (seq written-vars) (some? proof-run))))
        presence-node
        (when show-presence?
          [:section {:id "cv-live-presence" :class "card stack-sm" :data-as-of (str (or as-of-tx :live))}
           [:div {:class "row-between"}
            (kicker "Live Presence")
            [:div {:class "pill-row"}
             (when streaming? (badge "amber" (name (or (:phase live-stream) :streaming))))
             (when (seq children) (badge "blue" (str (count children) " agent" (when (> (count children) 1) "s"))))
             (when (and expert-mode? (seq calls))
             (let [done-n (count (filter #(= :resolved (:seon.agent.call/status %)) calls))]
               (badge (if (= done-n (count calls)) "green" "amber")
                      (str done-n "/" (count calls) " calls"))))
             (when (and expert-mode? proof-run)
               (badge (if (:seon.test.run/pass? proof-run) "green" "red")
                      (if (:seon.test.run/pass? proof-run)
                        (str (+ (long (or (:seon.test.run/ran proof-run) 0))
                                (long (or (:seon.test.run/cached proof-run) 0))) " green")
                        (str (count (:seon.test.run/failed-digests proof-run)) " failing"))))]]
           (when (seq children)
             [:div {:class "pill-row"}
              (for [c children]
                [:span {:class "pill mono tiny"}
                 (ent-link [:seon.agent/id (:seon.agent/id c)] (rdata/agent-label c (:seon.agent/id c)) "mono tiny strong")
                 " "
                 (child-status-badge c)])])
           (when (seq calls)
             [:div {:class "stack-xs"}
              (for [c calls
                    :let [st (:seon.agent.call/status c)
                          res-s (when (= :resolved st) (clip (:seon.agent.call/result c) 140))
                          err-s (when (= :failed st) (clip (:seon.agent.call/error c) 140))]]
                [:div {:class "ent-row mono tiny"}
                 [:div {:class "row-between"}
                  (ent-link [:seon.agent/id (:seon.agent.call/callee c)] (if expert-mode? (str (:seon.agent.call/callee c)) (rdata/friendly-agent-name (:seon.agent.call/callee c))) "mono tiny strong")
                  (call-status-badge st)]
                 [:div {:class "muted"} (clip (:seon.agent.call/prompt c) 120)]
                 (when (seq res-s) [:div {:class "green-text"} (str "-> " res-s)])
                 (when (seq err-s) [:div {:class "red-text"} (str "! " err-s)])])])
           (when (and expert-mode? (seq written-vars))
           (let [n (count written-vars) shown (take 10 written-vars) more (drop 10 written-vars)]
             [:div {:class "stack-xs"}
              [:div {:class "pill-row"}
               [:span {:class "mono tiny muted"} (str "Branch edits (" n "):")]
               (for [vsym shown]
                 (ent-link [:seon.code.var/name vsym] (name vsym) "pill mono tiny"))]
              (when (seq more)
                [:details {:class "activity-fold"}
                 [:summary {:class "mono tiny muted"} (str "+" (count more) " more edited var(s)")]
                 [:div {:class "pill-row" :style {:margin-top "6px"}}
                  (for [vsym more]
                    (ent-link [:seon.code.var/name vsym] (name vsym) "pill mono tiny"))]])]))])]
    (cond
      (and custom-node presence-node) [:div {:class "stack-sm"} custom-node presence-node]
      custom-node custom-node
      presence-node presence-node
      :else nil)))