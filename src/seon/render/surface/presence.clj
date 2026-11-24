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
            [seon.render.sanitize :as sanitize]))

(defn sanitize-canvas-hiccup
  "Pure recursive allowlist XSS sanitizer for agent-supplied Canvas Hiccup via `seon.render.sanitize/sanitize-hiccup`
   (`:allow-seon-actions? true` permits `:button` and strictly allowlisted `(seon.web/...)` `:on-click` actions)."
  {:malli/schema [:=> [:cat :any] :any]
   :cost/ticks 5000000}
  [node]
  (sanitize/sanitize-hiccup node {:allow-seon-actions? true}))

(defn- eval-canvas-fn [branch-db agent fn-sym]
  (when (qualified-symbol? fn-sym)
    (try
      (when-let [ns-obj (find-ns (symbol (namespace fn-sym)))]
        (when-let [v (ns-resolve ns-obj (symbol (name fn-sym)))]
          (when (and (var? v) (bound? v))
            (let [als (:arglists (meta v))
                  one-arg-only? (and (seq als) (every? #(= 1 (count %)) als))
                  raw (if one-arg-only?
                        (@v {:seon.agent/entity agent :db branch-db})
                        (@v branch-db agent))
                  node (if (and (map? raw) (contains? raw :seon.ui/hiccup))
                         (:seon.ui/hiccup raw)
                         raw)]
              (sanitize-canvas-hiccup node)))))
      (catch Throwable ex
        [:div {:class "card mono tiny muted"}
         (str "Canvas renderer " fn-sym " error: " (or (.getMessage ex) (str ex)))]))))

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

(defn- clip-str [s n]
  (let [t (str/trim (str (or s "")))]
    (if (> (count t) n) (str (subs t 0 (max 0 (- n 3))) "...") t)))

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

(defn render-live-canvas-presence
  "Renders custom agent Canvas (`:seon.agent/canvas-fn` or `:seon.agent/canvas-hiccup`, sanitized)
   and auto-derived present-tense Live Presence (streaming progress, active child agents, delegated
   calls returning results, and in Dev Mode branch writes & proofs)."
  {:malli/schema [:=> [:cat :map] :any]
   :cost/ticks 15000000}
  [{:keys [main-db branch-db viewing-id agent live-stream expert-mode? as-of-tx]}]
  (let [bdb (or branch-db main-db)
        cfn (:seon.agent/canvas-fn agent)
        chic (:seon.agent/canvas-hiccup agent)
        custom-node (cond
                      (qualified-symbol? cfn)
                      (when-let [rendered (eval-canvas-fn bdb agent cfn)]
                        [:section {:id "cv-custom-canvas" :class "canvas-surface stack-sm" :data-canvas-mode "reactive"}
                         (when expert-mode?
                         [:div {:class "row-between"}
                          (kicker "Live Agent Canvas")
                          [:div {:class "pill-row"}
                           (badge "purple" "reactive")
                           (ent-link [:seon.code.var/name cfn] (str cfn) "mono tiny muted")]])
                         rendered])

                      (vector? chic)
                      (when-let [clean (sanitize-canvas-hiccup chic)]
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
                          res-s (when (= :resolved st) (clip-str (:seon.agent.call/result c) 140))
                          err-s (when (= :failed st) (clip-str (:seon.agent.call/error c) 140))]]
                [:div {:class "ent-row mono tiny"}
                 [:div {:class "row-between"}
                  (ent-link [:seon.agent/id (:seon.agent.call/callee c)] (if expert-mode? (str (:seon.agent.call/callee c)) (rdata/friendly-agent-name (:seon.agent.call/callee c))) "mono tiny strong")
                  (call-status-badge st)]
                 [:div {:class "muted"} (clip-str (:seon.agent.call/prompt c) 120)]
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