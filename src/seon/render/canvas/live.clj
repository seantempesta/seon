(ns seon.render.canvas.live
  "Live in-flight acknowledgement, expandable user-presenting activity stream, and composable
   Root Canvas wrapper shared by `default-canvas` and custom `:seon.agent/render-fn` views."
  (:require [clojure.string :as str]
            [seon.render.canvas.blocks :as cblocks]
            [seon.render.data :as rdata :refer [agent-label]]
            [seon.render.markdown :as rmd :refer [render-activity-fold render-markdown-blocks]]))

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

(defn render-in-flight-panel
  "Renders immediate user acknowledgement, live pulsing thinking/delegation status, a 1-click
   live thinking drawer toggle, live expandable `Thinking & actions` eval steps, and active
   subagent delegations while `(:seon.canvas/pending-prompt ctx)` is in flight.
   Mounted above BOTH `default-canvas` and custom `:seon.agent/render-fn` views."
  {:malli/schema [:=> [:cat :map] :any]}
  [ctx]
  (when-let [pending-prompt (:seon.canvas/pending-prompt ctx)]
    (let [aid (:seon.agent/id ctx)
          label (agent-label ctx aid)
          active? (boolean (:seon.canvas/active? ctx))
          actions (:seon.canvas/latest-actions ctx)
          call-tree (:seon.canvas/outgoing-calls ctx)
          pend-n (count (filter #(= :pending (:seon.agent.call/status %)) call-tree))
          headline (prompt-headline pending-prompt)
          long-prompt? (> (count (str/trim (str pending-prompt))) (+ (count headline) 8))]
      [:section {:id "cv-pending" :class "ch-pending-card canvas-pending"}
       [:div {:class "ch-pending-head"}
        [:span {:class "root-kicker"} (str "In flight · " label)]
        [:div {:class "pill-row"}
         [:button {:type "button" :class "ch-pending-status" :data-on:click "$showThinking = !$showThinking"}
          [:span {:class "dot pulsing" :aria-hidden "true"}]
          [:span (cond
                   (pos? pend-n) (str "Coordinating · " pend-n " delegated")
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
          "Thinking — live eval steps will appear here as they run (click Thinking to watch live tokens)."])
       (when (seq call-tree)
         (cblocks/render-delegation-panel call-tree))])))

(defn compose-root-canvas
  "Composable wrapper for custom Root `:seon.agent/render-fn` functions: renders Root's
   evolving `custom-body` alongside live benchmark runs, active system progress/problems
   (`render-living-hero`), and Chronicle history so custom UI updates never throw away live
   system visibility."
  {:malli/schema [:=> [:cat :map :any] :any]}
  [ctx custom-body]
  (let [living (:seon.canvas/living ctx)
        as-of-tx (:seon.canvas/as-of-tx ctx)
        actions (:seon.canvas/latest-actions ctx)
        call-tree (:seon.canvas/outgoing-calls ctx)
        pending? (boolean (:seon.canvas/pending-prompt ctx))]
    [:div {:class "stack"}
     (when living (cblocks/render-chronicle-style))
     custom-body
     (when (seq (:bench-runs living))
       (cblocks/render-bench-runs (:bench-runs living) (:main-ms living)))
     (when living
       (cblocks/render-living-hero living {:primary-heading? false :hero-title (:seon.agent/hero-title ctx)}))
     (when (and (not pending?) (seq call-tree))
       (cblocks/render-delegation-panel call-tree))
     (when (and (not pending?) (seq actions))
       (render-activity-fold actions "cv"))
     (when living
       (cblocks/render-chronicle-stream (:entries living) {:as-of-tx as-of-tx}))]))
