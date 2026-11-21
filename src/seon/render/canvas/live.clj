(ns seon.render.canvas.live
  "General data-driven live work progress, merge-gate readiness, in-flight acknowledgement,
   and composable Canvas wrapper shared identically by Browser UI and Agent Wire Context
   (zero `:agent/root` special-casing)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.canvas.blocks :as cblocks]
            [seon.render.data :as rdata :refer [agent-label first-doc-line]]
            [seon.render.markdown :as rmd :refer [render-activity-fold render-markdown-blocks]]
            [seon.store :as store]))

(defn prompt-headline
  "Derives a crisp single-line headline from a user prompt for the in-flight acknowledgement card."
  {:malli/schema [:=> [:cat [:maybe :string]] :string]
   :cost/ticks 50000}
  [prompt]
  (let [s (str/trim (str (or prompt "")))
        line1 (first (str/split-lines s))]
    (cond
      (str/blank? line1) "Working on your request"
      (> (count line1) 110) (str (subs line1 0 107) "…")
      :else line1)))

(defn- branch-test-summary
  "Reads the latest `:seon.test.run/*` verdict for `branch` from `main-db` via indexed `:avet` lookup."
  [main-db branch]
  (when (and main-db branch (contains? (:schema main-db) :seon.test.run/branch))
    (when-let [dt (first (d/datoms main-db :avet :seon.test.run/branch branch))]
      (let [r (d/pull main-db '[:seon.test.run/pass? :seon.test.run/ran :seon.test.run/cached
                                :seon.test.run/failed-digests] (:e dt))
            failed (count (:seon.test.run/failed-digests r))
            total (+ (long (or (:seon.test.run/ran r) 0))
                     (long (or (:seon.test.run/cached r) 0)))]
        {:status (cond
                   (:seon.test.run/pass? r) :pass
                   (pos? failed) :fail
                   :else :unrun)
         :total total
         :failed failed}))))

(defn- agent-branch-progress
  "Derives universal merge-readiness facts (evals, latest human-readable intent, test pass/fail,
   ready/merge state) for one delegated/child agent using indexed reads only."
  [main-db conn aid]
  (let [ag (when (and main-db aid (contains? (:schema main-db) :seon.agent/id))
             (d/pull main-db '[:seon.agent/id :seon.agent/branch :seon.agent/scope
                               :seon.agent/ready? :seon.agent/tested-tx :seon.agent/merged-tx]
                     [:seon.agent/id aid]))
        br (:seon.agent/branch ag)
        bdb (when (and conn br (not= br :main))
              (try (store/head conn br) (catch Throwable _ nil)))
        eval-db (or bdb main-db)
        eval-dts (when (and eval-db aid (contains? (:schema eval-db) :seon.agent.eval/agent))
                   (vec (d/datoms eval-db :avet :seon.agent.eval/agent aid)))
        eval-count (count eval-dts)
        last-dt (when (seq eval-dts) (reduce #(if (>= (:tx %2) (:tx %1)) %2 %1) eval-dts))
        last-ev (when last-dt
                  (d/pull eval-db '[:seon.agent.eval/intent :seon.agent.eval/thought
                                    :seon.agent.eval/error :seon.agent.eval/tx] (:e last-dt)))
        last-intent (or (not-empty (str/trim (str (or (:seon.agent.eval/intent last-ev) ""))))
                        (not-empty (str/trim (str (or (:seon.agent.eval/thought last-ev) "")))))
        last-err (not-empty (str/trim (str (or (:seon.agent.eval/error last-ev) ""))))
        test-sum (when br (branch-test-summary main-db br))
        b-max-tx (long (or (:max-tx bdb) (:seon.agent.eval/tx last-ev) 0))
        merged-tx (long (or (:seon.agent/merged-tx ag) 0))
        ready? (boolean (:seon.agent/ready? ag))
        merged? (and (pos? merged-tx) (or (zero? b-max-tx) (>= merged-tx b-max-tx)))
        stage (cond
                merged? :merged
                (= :fail (:status test-sum)) :tests-red
                (and ready? (= :pass (:status test-sum))) :ready-to-merge
                ready? :testing
                (pos? eval-count) :working
                :else :starting)]
    {:agent-id aid
     :branch br
     :scope (:seon.agent/scope ag)
     :evals eval-count
     :latest-intent (when last-intent (first-doc-line last-intent 84))
     :last-error (when last-err (first-doc-line last-err 84))
     :tests (or test-sum {:status :unrun :total 0 :failed 0})
     :ready? ready?
     :merged? merged?
     :stage stage}))

(defn delegation-progress-tree
  "Builds a unified recursive work-progress tree for ANY agent `agent-id` from its outgoing
   `:seon.agent.call/*` entries (and nested child calls), enriching each node with live eval
   activity and universal merge-gate readiness (tests, conflicts/errors, ready, merged).
   Single source of truth for BOTH Browser UI and Agent Wire Context (`neighborhood-activity`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/conn] [:maybe :keyword]] [:maybe [:vector :map]]]}
  [main-db conn agent-id]
  (when (and main-db agent-id (contains? (:schema main-db) :seon.agent.call/caller))
    (let [direct (->> (d/datoms main-db :avet :seon.agent.call/caller agent-id)
                      (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                              :seon.agent.call/callee :seon.agent.call/parent-call
                                              :seon.agent.call/status :seon.agent.call/prompt
                                              :seon.agent.call/created-tx] (:e %)))
                      (sort-by #(or (:seon.agent.call/created-tx %) 0))
                      vec)
          pending (filterv #(= :pending (:seon.agent.call/status %)) direct)
          roots (if (seq pending)
                  pending
                  (let [latest-tx (or (:seon.agent.call/created-tx (peek direct)) 0)]
                    (filterv #(>= (or (:seon.agent.call/created-tx %) 0) (- latest-tx 8)) direct)))
          has-parent-idx? (contains? (:schema main-db) :seon.agent.call/parent-call)
          enrich (fn enrich [c depth]
                   (let [callee (:seon.agent.call/callee c)
                         prog (agent-branch-progress main-db conn callee)
                         kids (when (and has-parent-idx? (< depth 4))
                                (->> (d/datoms main-db :avet :seon.agent.call/parent-call (:seon.agent.call/id c))
                                     (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                                             :seon.agent.call/callee :seon.agent.call/parent-call
                                                             :seon.agent.call/status :seon.agent.call/prompt
                                                             :seon.agent.call/created-tx] (:e %)))
                                     (sort-by #(or (:seon.agent.call/created-tx %) 0))
                                     (mapv #(enrich % (inc depth)))))]
                     (cond-> (assoc c :progress prog)
                       (seq kids) (assoc :children kids))))]
      (when (seq roots)
        (mapv #(enrich % 0) (take-last 8 roots))))))

(defn- render-work-node
  [node depth]
  (let [st (:seon.agent.call/status node :pending)
        callee (:seon.agent.call/callee node)
        prompt (:seon.agent.call/prompt node "")
        prog (:progress node)
        stage (:stage prog :starting)
        tinfo (:tests prog)
        evals (long (or (:evals prog) 0))
        intent (:latest-intent prog)
        err (:last-error prog)
        badge-cls (case st
                    :resolved "green"
                    :failed "red"
                    :cancelled "muted"
                    (if (= :tests-red stage) "red" "amber"))
        test-badge (case (:status tinfo)
                     :pass (str "tests ✓ " (:total tinfo))
                     :fail (str "tests ✗ " (:failed tinfo) " red")
                     "tests · pending")
        merge-badge (cond
                      (:merged? prog) "merged ✓"
                      (= :ready-to-merge stage) "ready to merge"
                      (:ready? prog) "merge-queued"
                      :else (if (pos? evals) (str evals " eval" (when (not= 1 evals) "s")) "starting"))]
    [:div {:style (str "margin-left:" (* depth 16) "px;padding:5px 0;border-left:"
                       (if (pos? depth) "1px solid var(--border-subtle);padding-left:10px" "none"))}
     [:div {:class "pill-row"}
      [:span {:class (str "dot " (if (= :pending st) "pulsing" "live"))}]
      [:button {:type "button" :class "link-muted mono tiny strong"
                :on-click (list 'seon.web/view-agent! callee :canvas)}
       (str callee)]
      [:span {:class (str "badge " badge-cls)} (name st)]
      [:span {:class (str "pill mono tiny" (when (= :pass (:status tinfo)) " green-text") (when (= :fail (:status tinfo)) " red-text"))}
       test-badge]
      [:span {:class (str "pill mono tiny" (when (:merged? prog) " green-text"))}
       merge-badge]
      (when (and intent (= :pending st))
        [:span {:class "tiny"} (str "— " intent)])
      (when (and (not intent) (not (str/blank? prompt)))
        [:span {:class "muted tiny truncate"} (first-doc-line prompt 84)])]
     (when err
       [:div {:class "tiny red-text mono" :style "margin:2px 0 0 18px"}
        (str "err: " err)])
     (for [ch (:children node)]
       (render-work-node ch (inc depth)))]))

(defn render-work-progress
  "Single-source Hiccup renderer for distributed subagent work & merge-gate readiness.
   Rendered as interactive HTML in Canvas AND projected via `seon.render/hiccup->text`
   directly into coordinating agents' `<neighborhood-activity>` context."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]]] :any]}
  [work-tree]
  (when (seq work-tree)
    (let [flatten-nodes (fn fnodes [xs] (mapcat #(cons % (fnodes (:children %))) xs))
          all (vec (flatten-nodes work-tree))
          total (count all)
          pend (count (filter #(= :pending (:seon.agent.call/status %)) all))
          merged-n (count (filter #(get-in % [:progress :merged?]) all))
          tests-pass-n (count (filter #(= :pass (get-in % [:progress :tests :status])) all))
          tests-red-n (count (filter #(= :fail (get-in % [:progress :tests :status])) all))]
      [:details {:id "cv-delegations" :class "activity-fold" :open (pos? pend)}
       [:summary {:class "activity-summary"}
        [:span {:class "activity-label"}
         (str "Sub-agents · "
              (if (pos? pend)
                (str pend " running / " total " call" (when (not= 1 total) "s"))
                (str total " settled"))
              (when (pos? tests-pass-n) (str " · " tests-pass-n " tests green"))
              (when (pos? tests-red-n) (str " · " tests-red-n " tests red"))
              (when (pos? merged-n) (str " · " merged-n " merged")))]]
       [:div {:class "stack-sm" :style "padding:6px 0 2px 4px"}
        (for [n work-tree]
          (render-work-node n 0))]])))

(defn render-in-flight-panel
  "Renders immediate user acknowledgement, live pulsing status + 1-click Live Thinking toggle,
   live expandable `Thinking & actions` eval steps (showing clean latest message collapsed,
   full Thinking/Intent/Code/Output/Result expanded), and live distributed subagent merge-gate
   progress while `(:seon.canvas/pending-prompt ctx)` is active. Shared by `default-canvas`
   AND custom `:seon.agent/render-fn` views for ANY agent."
  {:malli/schema [:=> [:cat :map] :any]}
  [ctx]
  (when-let [pending-prompt (:seon.canvas/pending-prompt ctx)]
    (let [aid (:seon.agent/id ctx)
          label (agent-label ctx aid)
          active? (boolean (:seon.canvas/active? ctx))
          actions (:seon.canvas/latest-actions ctx)
          work-tree (or (:seon.canvas/work-tree ctx) (:seon.canvas/outgoing-calls ctx))
          pend-n (count (filter #(= :pending (:seon.agent.call/status %)) work-tree))
          headline (prompt-headline pending-prompt)
          long-prompt? (> (count (str/trim (str pending-prompt))) (+ (count headline) 8))]
      [:section {:id "cv-pending" :class "ch-pending-card canvas-pending"}
       [:div {:class "ch-pending-head"}
        [:span {:class "root-kicker"} (str "In flight · " label)]
        [:div {:class "pill-row"}
         [:button {:type "button" :class "ch-pending-status" :data-on:click "$showThinking = !$showThinking"}
          [:span {:class "dot pulsing" :aria-hidden "true"}]
          [:span (cond
                   (pos? pend-n) (str "Coordinating · " pend-n " sub-agent" (when (not= 1 pend-n) "s"))
                   (seq actions) (str "Working · " (count actions) " step" (when (not= 1 (count actions)) "s"))
                   active? "Thinking…"
                   :else "Acknowledged · waking agent…")]]
         [:button {:type "button" :class "link-muted tiny" :data-on:click "$statusOpen = !$statusOpen"}
          "Activity"]]]
       [:h1 {:class "ch-pending-title canvas-prompt-headline"} headline]
       (when long-prompt?
         [:details {:class "ch-prompt-more"}
          [:summary "Full request"]
          [:div {:class "ch-prompt-more-body ch-hero-body"} (render-markdown-blocks pending-prompt)]])
       (if (seq actions)
         (render-activity-fold actions "cv-live")
         [:p {:class "muted tiny" :style "margin:8px 0 0"}
          "Thinking — live eval steps appear here as they run (click Thinking to watch live tokens)."])
       (when (seq work-tree)
         (render-work-progress work-tree))])))

(defn compose-live-canvas
  "General composable Canvas wrapper for any agent overriding `:seon.agent/render-fn`:
   keeps `custom-body` alongside live subagent work/merge progress, active benchmark runs,
   system status/problems (`render-living-hero` when coordinator), and Chronicle history so
   evolving custom UIs never throw away live system visibility."
  {:malli/schema [:=> [:cat :map :any] :any]}
  [ctx custom-body]
  (let [living (:seon.canvas/living ctx)
        as-of-tx (:seon.canvas/as-of-tx ctx)
        actions (:seon.canvas/latest-actions ctx)
        work-tree (or (:seon.canvas/work-tree ctx) (:seon.canvas/outgoing-calls ctx))
        pending? (boolean (:seon.canvas/pending-prompt ctx))]
    [:div {:class "stack"}
     (when living (cblocks/render-chronicle-style))
     custom-body
     (when (and (not pending?) (seq work-tree))
       (render-work-progress work-tree))
     (when (seq (:bench-runs living))
       (cblocks/render-bench-runs (:bench-runs living) (:main-ms living)))
     (when living
       (cblocks/render-living-hero living {:primary-heading? false :hero-title (:seon.agent/hero-title ctx)}))
     (when (and (not pending?) (seq actions))
       (render-activity-fold actions "cv"))
     (when living
       (cblocks/render-chronicle-stream (:entries living) {:as-of-tx as-of-tx}))]))
