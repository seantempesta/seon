(ns seon.render.canvas
  "Data-driven Agent Canvas (timeline scrubber + recursive delegation panel) and Chronological Workspace views."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.reconciler :as reconciler]
            [seon.render :as render]
            [seon.store :as store]
            [seon.render.data :as rdata :refer [agent-evals-in agent-label agent-messages-in attr-tx-in branch-db-for canvas-history-txs entry-agent-id first-doc-line pull-agent-entity]]
            [seon.render.markdown :as rmd :refer [interleave-timeline prompt-msg? render-activity-fold render-markdown-blocks render-tile-card split-exchanges svg-arrow-up svg-chevron-left svg-chevron-right]]
            [seon.render.canvas.blocks :as cblocks]))

(defn windowed-history-indices
  "Selects up to 14 well-spaced step indices (always keeping first, current neighborhood, and latest)
   so the interactive timeline scrubber never overflows even at 50+ history steps or half-screen."
  [n cur-idx]
  (if (<= n 14)
    (vec (range n))
    (let [lo (max 1 (- cur-idx 5))
          hi (min (- n 2) (+ cur-idx 5))]
      (vec (distinct (concat [0] (range lo (inc hi)) [(dec n)]))))))

(defn render-canvas-timeline
  "Sleek interactive Datahike history bar: real SVG Left/Right buttons + clickable timeline pips +
   step counter & Return to Live pill (only rendered when > 1 historical Canvas snapshot exists)."
  [hist-txs cur-idx as-of-tx]
  (let [n (count hist-txs)]
    (when (> n 1)
      (let [idx (or cur-idx (dec n))
            at-past? (boolean (and as-of-tx (< idx (dec n))))
            shown-idxs (windowed-history-indices n idx)]
        [:div {:class (str "canvas-timeline-bar" (when at-past? " historical"))
               :title "Time-travel through Canvas history via Datahike d/as-of"}
         [:div {:class "tl-nav"}
          [:button {:type "button" :class "tl-btn"
                    :aria-label "Previous step"
                    :title "Previous Canvas snapshot"
                    :disabled (zero? idx)
                    :on-click '(seon.web/step-canvas-history! -1)}
           svg-chevron-left]
          [:button {:type "button" :class "tl-btn"
                    :aria-label "Next step"
                    :title "Next Canvas snapshot"
                    :disabled (>= idx (dec n))
                    :on-click '(seon.web/step-canvas-history! 1)}
           svg-chevron-right]]
         [:div {:class "tl-track" :role "tablist" :aria-label "Canvas history timeline"}
          (for [i shown-idxs
                :let [tx (nth hist-txs i)
                      active? (= i idx)
                      past? (< i idx)]]
            [:button {:type "button"
                      :class (str "tl-pip" (cond active? " active" past? " past" :else ""))
                      :title (str "Step " (inc i) " of " n (when (= i (dec n)) " (Live)") " · tx " tx)
                      :aria-label (str "Jump to step " (inc i) " of " n)
                      :on-click (list 'seon.web/jump-canvas-tx! tx)}])]
         [:div {:class "tl-meta"}
          [:span (str (if at-past? "Time-travel · " "") (inc idx) " / " n)]
          (when at-past?
            [:button {:type "button" :class "step-live" :on-click '(seon.web/jump-canvas-live!)}
             "Live"])]]))))

(defn default-canvas
  "Data-driven default Canvas render function for ANY agent (takes `ctx` map, returns Hiccup):
   - Renders reactive Router Pulse strip when `:seon.agent/router?` is true (live merges, active workers, health).
   - Renders message-driven exchanges (latest prompt/reply prominently + recent messages fold) so Canvas
     updates on EVERY message, not just manual `(user/canvas! ...)` calls.
   - Renders live recursive delegation tree when `:seon.canvas/outgoing-calls` is present.
   - Folds older `:seon.agent/canvas` visuals into `Previous visual` automatically once newer messages arrive."
  {:malli/schema [:=> [:cat :map] :any]}
  [ctx]
  (let [aid (:seon.agent/id ctx)
        label (agent-label ctx aid)
        branch (:seon.agent/branch ctx :main)
        canvas (or (:seon.agent/canvas ctx) {})
        latest-stage (last (:seon.agent/stage ctx))
        title (or (not-empty (:title canvas)) (:title latest-stage))
        body (or (:body canvas) (:hiccup latest-stage))
        prose-list (:seon.canvas/latest-prose ctx)
        prose-text (when (seq prose-list) (str/join "\n\n" prose-list))
        newer-prose? (boolean (:seon.canvas/newer-prose? ctx))
        pending-prompt (:seon.canvas/pending-prompt ctx)
        reply-prompt (:seon.canvas/reply-prompt ctx)
        active? (boolean (:seon.canvas/active? ctx))
        summary (if newer-prose?
                  prose-text
                  (or (not-empty (:summary canvas)) prose-text))
        actions (:seon.canvas/latest-actions ctx)
        call-tree (:seon.canvas/outgoing-calls ctx)
        router-pulse (:seon.canvas/router-pulse ctx)
        recent-exchanges (:seon.canvas/recent-exchanges ctx)
        as-of-tx (:seon.canvas/as-of-tx ctx)
        hist-txs (:seon.canvas/history-txs ctx)
        step-idx (:seon.canvas/step-idx ctx)
        branch-feedback (:seon.canvas/feedback ctx)
        fresh? (and (nil? pending-prompt) (nil? title) (nil? body) (str/blank? (str summary)))]
    [:div {:id "seon-view-unit" :class "root-canvas" :data-agent (str aid) :data-view-mode "canvas"}
     (render-canvas-timeline hist-txs step-idx as-of-tx)

     (when (and branch-feedback (not (:ok? branch-feedback)))
       [:div {:class "notice red row-between"}
        [:span (:text branch-feedback)]
        [:button {:type "button" :class "link-muted tiny" :on-click '(seon.web/dismiss-feedback!)} "×"]])

     (when router-pulse
       (cblocks/render-router-pulse router-pulse))

     (when (seq recent-exchanges)
       (cblocks/render-recent-messages-fold recent-exchanges))

     (when (and (nil? pending-prompt) (seq actions))
       (render-activity-fold actions "cv"))

     (when (seq call-tree)
       (cblocks/render-delegation-panel call-tree))

     (cond
       pending-prompt
       [:div {:class "canvas-pending"}
        [:div {:class "root-kicker"} (str "You asked · " label)]
        [:h1 {:class "canvas-prompt-headline"} pending-prompt]
        [:div {:class "canvas-pending-status"}
         [:span {:class "dot pulsing"}]
         [:span (if active? "Thinking & coordinating response…" "Sent — waking agent…")]]
        (when (seq actions)
          (render-activity-fold actions "cv"))
        (when body
          [:details {:id "cv-prev-peek" :class "prev-peek"}
           [:summary (str "Previous visual" (when title (str " · " title)))]
           [:div {:class "canvas-surface" :style "margin-top:10px"} body]])]

       fresh?
       (if (and (seq actions) (not (str/blank? (str reply-prompt))))
         [:div {:class "canvas-pending"}
          [:div {:class "root-kicker"} (str "You asked · " label)]
          [:h1 {:class "canvas-prompt-headline"} reply-prompt]
          [:p {:class "root-sub"}
           (str "Paused after " (count actions) " tool step" (when (not= 1 (count actions)) "s")
                " without a final response. Expand Thinking & actions above to inspect what ran, or reply below to continue.")]]
         [:div {:class "root-hero"}
          [:div {:class "root-kicker"} (str label " · " branch)]
          [:h1 {:class "root-title"}
           (or (not-empty (:seon.agent/hero-title ctx))
               (str label " is ready"))]
          [:p {:class "root-sub"}
           (or (not-empty (:seon.agent/goal ctx))
               "Ask anything below — Canvas updates live with every answer and visual, and Workspace keeps the full conversation.")]])

       newer-prose?
       [:div {:class "stack"}
        (when (not (str/blank? (str prose-text)))
          [:section {:class "canvas-reply"}
           (when (not (str/blank? (str reply-prompt)))
             [:div {:class "canvas-re-prompt"}
              (str "Re: " (if (> (count reply-prompt) 90) (str (subs reply-prompt 0 90) "…") reply-prompt))])
           (render-markdown-blocks prose-text)])
        (when body
          [:details {:id "cv-prev-visual" :class "activity-fold"}
           [:summary (str "Previous visual" (when title (str " · " title)))]
           [:div {:class "canvas-surface" :style "margin-top:10px"} body]])]

       :else
       [:div {:class "stack"}
        (when (or title (not (str/blank? (str summary))))
          [:header {:class "canvas-header"}
           (when title
             [:h1 {:class "canvas-title"} title])
           (when (not (str/blank? (str summary)))
             [:div {:class "canvas-summary"} (render-markdown-blocks summary)])])
        (when body
          [:section {:class "canvas-surface"}
           body])])]))

(defn exchange-prose [ex]
  (->> ex
       (filter #(= :eval (:kind %)))
       (map :data)
       (filter #(str/blank? (str (:seon.agent.eval/code %))))
       (map :seon.agent.eval/thought)
       (remove #(str/blank? (str %)))
       vec))

(defn exchange-max-eval-tx [ex]
  (->> ex
       (filter #(= :eval (:kind %)))
       (keep #(get-in % [:data :seon.agent.eval/tx]))
       (reduce max 0)))

(defn exchange-user-msg [ex]
  (last (filter prompt-msg? ex)))

(defn pull-outgoing-call-tree
  "Builds a recursive tree of recent/active outgoing `:seon.agent.call/*` entries rooted at `agent-id`,
   including nested child/grandchild calls linked via `:seon.agent.call/parent-call`."
  [main-db agent-id]
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
          expand (fn expand [c depth]
                   (let [kids (when (and has-parent-idx? (< depth 4))
                                (->> (d/datoms main-db :avet :seon.agent.call/parent-call (:seon.agent.call/id c))
                                     (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                                             :seon.agent.call/callee :seon.agent.call/parent-call
                                                             :seon.agent.call/status :seon.agent.call/prompt
                                                             :seon.agent.call/created-tx] (:e %)))
                                     (sort-by #(or (:seon.agent.call/created-tx %) 0))
                                     (mapv #(expand % (inc depth)))))]
                     (cond-> c (seq kids) (assoc :children kids))))]
      (when (seq roots)
        (mapv #(expand % 0) (take-last 8 roots))))))

(defonce ^:private ^:seon.flow/edge canvas-ctx-cache
  (store/lru-cache 16))

(defn build-canvas-ctx
  "Builds the Canvas render context map for `aid`, content-keyed in an LRU cache by DB tx & state."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :keyword :map] :map]}
  [main-db bdb head-bdb aid {:keys [as-of-tx branch-feedback agent-states]}]
  (let [ck (when (and (not (instance? datahike.db.AsOfDB main-db))
                      (not (instance? datahike.db.AsOfDB bdb))
                      (not (instance? datahike.db.HistoricalDB bdb)))
             [(:max-tx main-db) (get-in bdb [:config :branch] :main) (:max-tx bdb) (:max-tx head-bdb)
              aid as-of-tx (get agent-states aid) (count (filter #(= :active (val %)) agent-states)) branch-feedback])
        compute
        (fn []
          (let [eff-main-db (if as-of-tx (try (d/as-of main-db as-of-tx) (catch Throwable _ main-db)) main-db)
                ag (pull-agent-entity eff-main-db bdb aid)
                evals (agent-evals-in bdb aid)
                msgs (agent-messages-in eff-main-db aid)
                seen-ids (store/agent-seen-message-ids bdb aid)
                timeline (interleave-timeline msgs evals seen-ids)
                exchanges (split-exchanges timeline)
                canvas-tx (max (or (attr-tx-in bdb aid :seon.agent/canvas) 0)
                               (or (attr-tx-in eff-main-db aid :seon.agent/canvas) 0)
                               (or (attr-tx-in bdb aid :seon.agent/stage) 0)
                               (or (attr-tx-in eff-main-db aid :seon.agent/stage) 0))
                latest-ex (peek exchanges)
                latest-user-msg (exchange-user-msg latest-ex)
                latest-msg-id (get-in latest-user-msg [:data :seon.agent.message/id])
                msg-unread? (boolean (and latest-msg-id (not (contains? seen-ids latest-msg-id))))
                active? (= :active (get agent-states aid))
                call-tree (when (nil? as-of-tx) (pull-outgoing-call-tree eff-main-db aid))
                awaiting-calls? (boolean (some #(= :pending (:seon.agent.call/status %)) call-tree))
                latest-prev-max-tx (if (> (count exchanges) 1)
                                     (reduce max 0 (map exchange-max-eval-tx (pop exchanges)))
                                     0)
                latest-updated-canvas? (and (pos? canvas-tx) (> canvas-tx latest-prev-max-tx))
                pending-prompt (when (and (nil? as-of-tx)
                                          latest-user-msg
                                          (empty? (exchange-prose latest-ex))
                                          (not latest-updated-canvas?)
                                          (or msg-unread? active? awaiting-calls?))
                                 (str (:seon.agent.message/body (:data latest-user-msg))))
                settled-idx (last (keep-indexed (fn [i ex] (when (seq (exchange-prose ex)) i)) exchanges))
                display-ex (cond
                             pending-prompt latest-ex
                             (some? settled-idx) (nth exchanges settled-idx)
                             :else latest-ex)
                cur-evals (map :data (filter #(= :eval (:kind %)) display-ex))
                prose-parts (if pending-prompt [] (exchange-prose display-ex))
                reply-prompt (some-> (exchange-user-msg display-ex) :data :seon.agent.message/body)
                action-evals (filterv #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                           (not (str/blank? (str (:seon.agent.eval/reasoning %)))))
                                      cur-evals)
                reply-tx (->> cur-evals
                              (filter #(and (str/blank? (str (:seon.agent.eval/code %)))
                                            (not (str/blank? (str (:seon.agent.eval/thought %))))))
                              (keep :seon.agent.eval/tx)
                              (reduce max 0))
                prev-ex-max-tx (if (and (some? settled-idx) (pos? settled-idx))
                                 (reduce max 0 (map exchange-max-eval-tx (subvec exchanges 0 settled-idx)))
                                 0)
                newer-prose? (boolean (and (nil? pending-prompt)
                                           (pos? reply-tx)
                                           (> reply-tx canvas-tx)
                                           (or (zero? canvas-tx) (<= canvas-tx prev-ex-max-tx))))
                prior-exs (let [end-idx (cond
                                          pending-prompt (max 0 (dec (count exchanges)))
                                          (some? settled-idx) settled-idx
                                          :else (max 0 (dec (count exchanges))))]
                            (->> (subvec exchanges 0 end-idx)
                                 (take-last 3)
                                 (keep (fn [ex]
                                         (let [p (some-> (exchange-user-msg ex) :data :seon.agent.message/body)
                                               pr (str/join "\n\n" (exchange-prose ex))]
                                           (when (or (not (str/blank? (str p))) (not (str/blank? pr)))
                                             {:prompt p :prose pr}))))
                                 vec))
                rpulse (when (and (nil? as-of-tx) (:seon.agent/router? ag))
                         (cblocks/router-pulse-data eff-main-db aid agent-states))
                hist-txs (canvas-history-txs head-bdb aid)
                step-idx (when (seq hist-txs)
                           (if as-of-tx
                             (or (first (keep-indexed (fn [i tx] (when (= tx as-of-tx) i)) hist-txs)) (dec (count hist-txs)))
                             (dec (count hist-txs))))]
            (assoc ag
                   :seon.ctx/db bdb
                   :seon.ctx/main-db main-db
                   :seon.ctx/tx (:max-tx bdb)
                   :seon.canvas/latest-prose prose-parts
                   :seon.canvas/latest-actions action-evals
                   :seon.canvas/recent-exchanges prior-exs
                   :seon.canvas/router-pulse rpulse
                   :seon.canvas/outgoing-calls call-tree
                   :seon.canvas/canvas-tx canvas-tx
                   :seon.canvas/reply-tx reply-tx
                   :seon.canvas/newer-prose? newer-prose?
                   :seon.canvas/pending-prompt pending-prompt
                   :seon.canvas/reply-prompt reply-prompt
                   :seon.canvas/active? (or active? awaiting-calls?)
                   :seon.canvas/as-of-tx as-of-tx
                   :seon.canvas/history-txs hist-txs
                   :seon.canvas/step-idx step-idx
                   :seon.canvas/step-label (when (some? step-idx) (str "Step " (inc step-idx) " / " (count hist-txs)))
                   :seon.canvas/feedback branch-feedback)))]
    (if ck
      (store/lru-get! canvas-ctx-cache 16 ck compute)
      (compute))))

(defn agent-canvas
  "Standard data-driven Canvas renderer for any agent entity."
  [main-db {:keys [conn viewing-id as-of-tx branch] :as opts}]
  (let [aid (or viewing-id (entry-agent-id main-db))
        branch (or branch (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        head-bdb (branch-db-for main-db conn branch)
        eff-bdb (if as-of-tx (try (d/as-of head-bdb as-of-tx) (catch Throwable _ head-bdb)) head-bdb)
        ctx-map (build-canvas-ctx main-db eff-bdb head-bdb aid opts)]
    (default-canvas ctx-map)))

(defn root-canvas
  "Alias to `agent-canvas` kept for backwards compatibility with existing callers/tests."
  [main-db opts]
  (agent-canvas main-db opts))

(defn render-ws-exchanges [prefix exchanges]
  (for [[ex-idx ex] (map-indexed vector exchanges)
        :let [ex-msgs (filter #(= :msg (:kind %)) ex)
              ex-evals (map :data (filter #(= :eval (:kind %)) ex))
              prose-parts (->> ex-evals
                               (filter #(str/blank? (str (:seon.agent.eval/code %))))
                               (map :seon.agent.eval/thought)
                               (remove #(str/blank? (str %)))
                               vec)
              action-evals (filterv #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                         (not (str/blank? (str (:seon.agent.eval/reasoning %)))))
                                    ex-evals)]]
    [:div {:id (str prefix "-" ex-idx) :class "ws-exchange"}
     (for [[m-idx m-item] (map-indexed vector ex-msgs)
           :let [m (:data m-item)
                 body (str (:seon.agent.message/body m ""))
                 mtype (:seon.agent.message/type m)
                 foldable? (or (#{:call-result :call-request :system} mtype)
                               (str/starts-with? body "Resolved call results")
                               (> (count body) 600))]]
       (if foldable?
         [:details {:id (str prefix "-" ex-idx "-m-" m-idx) :class "activity-fold" :style "margin-bottom:6px"}
          [:summary {:class "activity-summary"}
           [:span {:class "activity-label"}
            (let [headline (first (str/split-lines body))]
              (str (if (> (count headline) 84) (str (subs headline 0 84) "…") headline)
                   " · " (count body) " chars"))]]
          [:pre {:class "code-box tiny" :style "white-space:pre-wrap;max-height:360px;overflow:auto;margin-top:6px"} body]]
         [:div {:id (str prefix "-" ex-idx "-m-" m-idx) :class "ws-user-bubble"}
          body]))
     (when (or (seq action-evals) (seq prose-parts))
       [:div {:class (str "ws-agent-turn" (when (seq prose-parts) " has-prose"))}
        (render-activity-fold action-evals (str prefix "-" ex-idx))
        (for [[p-idx p] (map-indexed vector prose-parts)]
          [:div {:id (str prefix "-" ex-idx "-p-" p-idx) :class "chat-body"}
           (render-markdown-blocks p)])])]))

(defn agent-workspace
  {:malli/schema [:=> [:cat :seon.host/db :map] :any]}
  [main-db {:keys [conn branch-feedback viewing-id]}]
  (let [aid (or viewing-id (entry-agent-id main-db))
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (branch-db-for main-db conn branch)
        ag (pull-agent-entity main-db bdb aid)
        evals (agent-evals-in bdb aid)
        msgs (agent-messages-in main-db aid)
        seen-ids (store/agent-seen-message-ids bdb aid)
        timeline (interleave-timeline msgs evals seen-ids)
        exchanges (split-exchanges timeline)
        retracted-evals (filterv :seon.agent.eval/retracted-tx (store/agent-historical-evals bdb aid))
        retracted-exchanges (when (seq retracted-evals)
                              (let [r-seen (into #{} (mapcat :seon.agent.eval/seen-messages) retracted-evals)
                                    r-msgs (filterv #(contains? r-seen (:seon.agent.message/id %)) msgs)]
                                (split-exchanges (interleave-timeline r-msgs retracted-evals))))
        tiles (render/discover-renderable-tiles main-db bdb ag evals health/report)
        derived (when (and conn (first (d/datoms main-db :avet :seon.agent/id aid)))
                  (reconciler/derive-agent-status main-db conn {} #{} #{} aid))]
    [:div {:id "seon-view-unit" :class "workspace-grid" :data-agent (str aid) :data-view-mode "workspace"}
     [:section {:class "ws-left"}
      [:div {:class "ws-head"}
       [:div
        [:div {:class "pill-row"}
         [:h2 {:class "mono"} (str aid)]
         [:span {:class "badge amber"} (name (or (:seon.agent/role ag) :worker))]
         (when derived
           [:span {:class (str "badge " (case (:status derived) :merged "green" :running "amber" "blue"))}
            (name (:status derived))])]
        [:div {:class "muted tiny mono" :style "margin-top:3px"}
         (str "branch " branch " @ tx " (:max-tx bdb)
              (when (:seon.agent/scope ag) (str " · scope " (:seon.agent/scope ag))))]]
       [:div {:class "pill-row"}
        [:button {:type "button" :class "btn sm" :on-click (list 'seon.web/run-tests! branch)} "Test Branch"]
        (when (not= branch :main)
          [:button {:type "button" :class "btn sm success" :on-click (list 'seon.web/merge-branch! branch)} "Merge → :main"])]]

      (when branch-feedback
        [:div {:class (str "notice row-between " (if (:ok? branch-feedback) "green" "red"))}
         [:span (:text branch-feedback)]
         [:button {:type "button" :class "link-muted tiny" :on-click '(seon.web/dismiss-feedback!)} "×"]])

      [:div {:class "ws-timeline" :data-agent (str aid)}
       (when (not (str/blank? (str (:seon.agent/goal ag))))
         [:div {:class "goal-banner"}
          [:span {:class "kicker"} "Goal"]
          [:p {:class "sm-copy" :style "margin:3px 0 0"} (:seon.agent/goal ag)]])

       (when (seq retracted-exchanges)
         [:details {:id "ws-retracted-history" :class "activity-fold" :style "margin-bottom:10px"}
          [:summary {:class "activity-summary"}
           [:span {:class "activity-label"}
            (str "Earlier history (d/history) · " (count retracted-evals) " compacted eval(s)")]]
          [:div {:class "stack-sm" :style "padding:8px 0 4px 10px;border-left:1px solid var(--border-subtle)"}
           (render-ws-exchanges "whx" retracted-exchanges)]])

       (if (empty? exchanges)
         [:p {:class "muted sm-copy" :style "padding:12px 0"}
          (if (seq retracted-exchanges)
            "Working history compacted (expand Earlier history above or time-travel in Canvas). Send a message to continue."
            "No conversation on this branch yet. Send a message or evaluate a Clojure form below.")]
         (render-ws-exchanges "wx" exchanges))]

      [:form {:class "ws-composer" :on-submit (list 'seon.web/eval-agent-form! aid branch)}
       [:div {:class "composer-shell"}
        [:textarea {:class "composer-inp" :name "code" :rows 1
                    :data-on:keydown "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.target.value.trim()){evt.target.form.requestSubmit();}}"
                    :placeholder (str "Message " (agent-label ag aid) " or (eval Clojure)…")}]
        [:button {:type "submit" :class "send-icon-btn" :aria-label "Send" :title "Send (Enter)"}
         svg-arrow-up]]]]

     [:section {:class "ws-right"}
      [:div {:class "ws-head"}
       [:div
        [:h2 (str "Live Context Tiles (" (count tiles) ")")]
        [:span {:class "muted tiny"} "Everything pulled in @seon.ctx/ctx or returned by evals with a :seon.render/hiccup renderer."]]
       [:button {:type "button" :class "nav-pill tiny"
                 :on-click '(seon.web/set-view-mode! :debug)} "Wire Debug →"]]

      [:form {:class "row-form" :style "margin-bottom:10px" :on-submit (list 'seon.web/pin-ref-form! aid)}
       [:input {:class "inp sm mono flex-1" :name "ref"
                :placeholder "Pin ref, e.g. [:seon.code.ns/name 'seon.web]"}]
       [:button {:type "submit" :class "btn sm"} "+ Pin to Walk"]]

      [:div {:class "tiles-scroll"}
       (if (empty? tiles)
         [:div {:class "card muted sm-copy"}
          "No renderable tiles in this agent's walk yet. Evaluate " [:code "(user/health)"] " or "
          [:code "[:seon.code.ns/name 'seon.render]"] " on the left to watch it appear here live."]
         (for [tile tiles]
           (render-tile-card bdb main-db tile aid)))]]]))