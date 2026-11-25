(ns seon.render.surface.canvas
  "Spatial presentation-first Canvas (`:canvas`) surface: live presence, status card,
   self-replacing Markdown presentation stage, interactive capability demos, and spatial
   Active Context card grid. Traditional chronological chat lives behind the `:session` toggle."
  (:require [clojure.string :as str]
            [seon.render.data :as rdata]
            [seon.render.links :as rlinks :refer [badge btn ent-link feedback-banner kicker]]
            [seon.render.markdown :as rmd]
            [seon.render.status :as rstatus]
            [seon.render.surface.session :as ssess]
            [seon.render.markdown.parse :as mdp]))

(def ^:private enter-submit-js
  "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.isComposing||evt.keyCode===229)return;if(evt.target.value.trim()){evt.target.form.requestSubmit();}}")

(defn- resolve-presence-fn []
  (when-let [ns-obj (find-ns 'seon.render.surface.presence)]
  (when-let [v (ns-resolve ns-obj 'render-live-canvas-presence)]
    (when (and (var? v) (bound? v)) @v))))

(defn- render-presence-slot [model]
  (if-let [pf (resolve-presence-fn)]
    (pf model)
    (when-let [ch (get-in model [:agent :seon.agent/canvas-hiccup])]
      (when (vector? ch)
        (when-let [safe (mdp/sanitize-hiccup ch)]
          [:section {:id "cv-custom-canvas" :class "canvas-surface stack-sm"}
           (when (:expert-mode? model)
             [:div {:class "row-between"}
              (kicker "Live Agent Canvas")
              (badge "purple" "hiccup")])
           safe])))))

(defn render-canvas-demo-deck
  "Renders a compact, viewport-fitting interactive capabilities strip on the Canvas
   (clickable live signals, entity deep-links with hovercards, and mode toggles — zero vertical scroll bloat)."
  {:malli/schema [:=> [:cat :any [:maybe :keyword] [:maybe :map]] [:vector :any]]
   :cost/ticks 2000000 :cost/bytes 1048576}
  [_main-db viewing-id signals]
  (let [h (:health signals)
        p (:proof signals)
        f (:flow signals)]
    [:section {:id "cv-demo-deck" :class "card canvas-compact-deck stack-sm"}
     [:div {:class "row-between"}
      [:div {:class "pill-row"}
       (kicker "Live System")
       (ent-link [:seon.signal/kind :health]
                 (if (:ok? h true) "Healthy" (str (:errors h 0) " err"))
                 (str "pill mono tiny" (when-not (:ok? h true) " red-text")))
       (ent-link [:seon.signal/kind :proof]
                 (str (long (or (:green p) 0)) " proofs")
                 "pill mono tiny")
       (ent-link [:seon.signal/kind :flow]
                 (str (long (or (:running f) 0)) "/" (long (or (:active f) 0)) " active")
                 "pill mono tiny")]
      [:div {:class "pill-row"}
       (ent-link [:seon.code.ns/name 'seon.render.surface.canvas] "canvas.clj" "pill mono tiny")
       (ent-link [:seon.code.doc/id "docs/render.md#overview"] "docs/render.md" "pill mono tiny")
       (when viewing-id (ent-link [:seon.agent/id viewing-id] "Inspect" "pill mono tiny"))]]]))

(defn- render-canvas-context-grid [entities pool-count viewing-id expert-mode?]
  (let [pin-form [:form {:class "row-form" :on-submit (list 'seon.web/pin-ref-form! viewing-id)}
                  [:input {:class "inp sm mono flex-1" :name "ref" :placeholder "Pin ref, e.g. [:seon.code.ns/name 'seon.web]"}]
                  [:button {:type "submit" :class "btn sm"} "+ Pin"]]
        vt-slug (fn [ent]
                  (let [s (-> (str ent) (str/replace #"[^A-Za-z0-9_-]+" "-") (str/replace #"^-+|-+$" ""))]
                    (if (str/blank? s) "x" (subs s 0 (min 36 (count s))))))]
    [:section {:id "cv-context-grid" :class "stack-sm"}
     [:div {:class "row-between"}
      [:div {:class "pill-row"}
       (kicker (str "Active Context (" (count entities) ")"))
       (when (pos? (long (or pool-count 0)))
         [:span {:class "mono tiny muted"} (str pool-count " in pool")])]
      (btn "nav-pill tiny" '(seon.web/set-explain-lens! true nil) "Explain selection →")]
     (when expert-mode? pin-form)
     (if (seq entities)
       (let [counts (volatile! {})]
         [:div {:class "root-visuals" :data-entity-count (str (count entities))}
          (for [item entities
                :let [slug (vt-slug (:entity item))
                      occ (long (get @counts slug 0))
                      _ (vswap! counts assoc slug (inc occ))
                      vt (str "ent-" occ "-" slug)
                      ref (or (rdata/entity-lookup-ref (:data item))
                              (when (integer? (:entity item)) [:db/id (:entity item)]))
                      cfn (:chosen-fn item)
                      sem (:semantic item)
                      card-node (or (:hiccup item)
                                    [:div {:class "ent-row mono tiny"}
                                     (if ref (ent-link ref (rdata/entity-short-label (or (:data item) ref)) "mono tiny strong") (str (:entity item)))])
                      prov-bar [:div {:class "ent-prov-bar"}
                                [:div {:class "pill-row"}
                                (when-let [r0 (first (filter qualified-keyword? (:rules item)))]
                                  (ent-link [:seon.rule/id r0] (name r0) "badge blue tiny mono"))
                                (if (qualified-symbol? cfn)
                                  (ent-link [:seon.code.var/name cfn] (name cfn) "mono tiny strong")
                                  [:span {:class "mono tiny muted"} (str (some-> cfn name))])
                                (badge (if (and (qualified-symbol? (:fn item)) (= (:fn item) cfn)) "amber" "muted")
                                       (if (and (qualified-symbol? (:fn item)) (= (:fn item) cfn)) "rule pinned :fn" "auto-fit"))
                                [:span {:class "mono tiny muted"} (str (:tokens item 0) " tok")]
                                (when (number? sem)
                                  (badge "purple" (str "cos " (/ (Math/round (* (double sem) 100.0)) 100.0))))]
                                (when ref (ent-link ref "Inspect →" "tiny mono"))]]]
            [:div {:class "surface-entity-item"
                   :style (str "view-transition-name:" vt)
                   :data-entity (str (:entity item))
                   :data-chosen-fn (str cfn)
                   :data-min-width (str (:min-width item 0))}
             (when expert-mode? prov-bar)
             card-node
             (when-not expert-mode?
               [:details {:class "expert-drawer ent-prov-drawer"}
                [:summary {:class "mono tiny muted" :style {:cursor "pointer" :opacity "0.55" :font-size "10px" :margin-top "2px"}} "···"]
                prov-bar])])])
       [:p {:class "muted tiny mono"} "No active context cards yet."])]))

(def ^:private starter-prompts
  ["Summarize what's happening"
   "Help me plan a project"
   "Show me an interactive demo"])

(defn- render-canvas-dev-bar [viewing-id label status-b branch tx-num ex-n]
  [:div {:class "ws-head work-bar dev-work-bar"}
   [:div {:class "pill-row"}
    (badge "amber" "DEV")
    (ent-link [:seon.agent/id viewing-id] label "mono strong")
    status-b
    (ent-link [:seon.branch/name branch] (str branch (when tx-num (str " @ tx " tx-num))) "mono tiny muted")]
   [:div {:class "pill-row"}
    (when (pos? ex-n)
      (btn "btn sm" '(seon.web/set-view-mode! :session) (str "Chat (" ex-n ") →")))
    (when (not= branch :main)
      (list (btn "btn sm" (list 'seon.web/run-tests! branch) "Test")
            (btn "btn sm success" (list 'seon.web/merge-branch! branch) "Merge → :main")))]])

(defn- render-canvas-stage-body
  [{:keys [agent viewing-id branch main-db pending-prompt reply-prompt latest-actions work-summary live-stream signals]}
   label prose exp? custom-canvas? md-opts live-acts]
  (cond
    pending-prompt
    (let [{:keys [css-class rate reasoning text] pl :label} (ssess/canvas-live-progress live-stream)]
      [:section {:id "cv-pending" :class (str "canvas-surface ch-pending-card canvas-pending " css-class)}
       (when exp? [:div {:class "root-kicker"} (str "In flight · " pl)])
       [:h1 {:class "canvas-prompt-headline"} pending-prompt]
       [:div {:class "canvas-pending-status"}
        [:span {:class "dot pulsing"}]
        (if exp?
          (list [:span {:class (str "stream-phase-badge " css-class)} pl]
                (when rate [:span {:class "stream-rate"} rate]))
          [:span {:class "muted sm-copy"} "Working…"])]
       (when text [:div {:id "cv-live-prose" :class "live-prose"} (rmd/render-markdown-blocks text md-opts) [:span {:class "cursor"} "▍"]])
       (when (and exp? reasoning)
         [:details {:id "cv-live-reasoning" :class "activity-fold"}
          [:summary {:class "mono tiny"} (str "Live reasoning (" (count reasoning) " chars)")]
          [:div {:class "live-reasoning"} reasoning]])
       (when (and exp? (seq live-acts)) (rmd/render-activity-fold live-acts "cv-live"))])

    prose
    [:section {:id "cv-featured-reply" :class "canvas-surface canvas-reply stack-sm"}
     (when (not-empty reply-prompt) [:div {:class "canvas-re-prompt"} (if exp? (str "Re: " reply-prompt) reply-prompt)])
     (rmd/render-markdown-blocks prose md-opts)
     (when (and exp? (seq latest-actions)) (rmd/render-activity-fold latest-actions "cv"))]

    work-summary
    [:section {:id "cv-work-summary" :class "canvas-surface card stack-sm"}
     [:div {:class "row-between"}
      [:div {:class "pill-row"}
       (badge "green" (:status-label work-summary))
       [:strong {:class "sm-copy"} (:latest-intent work-summary)]]
      (when exp? [:span {:class "mono tiny muted"} (str (:eval-count work-summary) " eval(s)")])]
     (when-let [pr (:prompt work-summary)] [:div {:class "canvas-re-prompt"} (if exp? (str "Re: " pr) pr)])
     (when (and exp? (seq latest-actions)) (rmd/render-activity-fold latest-actions "cv-work"))]

    exp?
    [:div {:class "stack"}
     [:header {:class "root-hero"}
      [:div {:class "root-kicker pill-row"}
       (ent-link [:seon.agent/id viewing-id] label "mono tiny") "·"
       (ent-link [:seon.branch/name branch] (str branch) "mono tiny")]
      [:h1 {:class "root-title"} (or (not-empty (:seon.agent/hero-title agent)) label)]
      (when-let [g (not-empty (:seon.agent/goal agent))] [:p {:class "root-sub"} g])]
     (render-canvas-demo-deck main-db viewing-id signals)]

    :else
    (when-not custom-canvas?
      [:div {:id "cv-user-welcome" :class "user-welcome"}
       [:header {:class "root-hero"}
        [:h1 {:class "root-title"} (or (not-empty (:seon.agent/hero-title agent)) "What would you like to work on?")]
        [:p {:class "root-sub"} "Ask a question, brainstorm an idea, or pick a prompt below to get started."]]
       [:div {:class "starter-chips"}
        (for [prompt starter-prompts]
          [:button {:type "button" :class "starter-chip"
                    :on-click (list 'seon.web/chat-agent-form! viewing-id {:text prompt})}
           prompt])]])))

(defn render-canvas-body
  "Renders the presentation-first Canvas (`:canvas`):
   - Normal User Mode (`expert-mode? false`): calm, viewport-fitting living stage with warm greeting,
     starter chips, live presence/status, and clean Markdown replies (zero dev jargon or eval folds).
   - Developer Mode (`expert-mode? true`): DEV work bar, live telemetry/reasoning/eval folds, demo deck,
     and full Active Context spatial tile grid."
  {:malli/schema [:=> [:cat :map :boolean] :any] :cost/ticks 20000000}
  [{:keys [agent viewing-id branch main-db branch-db as-of-tx entities pool-count exchanges evals
           pending-prompt latest-prose reply-prompt latest-actions work-summary live-stream
           branch-feedback signals active? expert-mode? agent-status] :as model}
   include-composer?]
  (let [label (rdata/agent-label agent viewing-id)
        prose (when (seq latest-prose) (str/join "\n\n" latest-prose))
        tx-num (or as-of-tx (:max-tx branch-db) (:max-tx main-db))
        exp? (boolean expert-mode?)
        ex-n (count exchanges)
        custom-canvas? (boolean (or (qualified-symbol? (:seon.agent/canvas-fn agent))
                                    (vector? (:seon.agent/canvas-hiccup agent))))
        md-opts {:db main-db :branch-db branch-db :viewport (:viewport model)
                 :slot-budget (:slot-budget model) :ui-config (:ui-config model)
                 :expert-mode? exp? :canon-mode :canvas}
        
        live-acts (when exp?
                    (if (seq latest-actions)
                      latest-actions
                      (when (seq evals)
                        (vec (take-last 3 (filter #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                                       (not (str/blank? (str (:seon.agent.eval/intent %)))))
                                                  evals))))))
        status-b (cond active? (badge "amber" "live")
                       (pos? (long (or (:seon.agent/merged-tx agent) 0))) (badge "green" "merged")
                       (:seon.agent/ready? agent) (badge "green" "ready")
                       (:seon.agent/paused? agent) (badge "muted" "paused")
                       (:seon.agent/waiting? agent) (badge "blue" "waiting")
                       :else (badge "muted" "idle"))]
    [:div {:id "cv-canvas-stage" :class (str "root-canvas" (if exp? " is-dev-canvas" " is-user-canvas") (when custom-canvas? " has-custom-canvas"))}
     (when exp? (render-canvas-dev-bar viewing-id label status-b branch tx-num ex-n))
     (feedback-banner branch-feedback)
     (render-presence-slot model)
     (when (and (map? agent-status) (:seon.ui.status/label agent-status) (or (not= :done (:seon.ui.status/stage agent-status)) (and (not pending-prompt) (or exp? (not prose)))))
       [:div {:id "cv-live-status" :class "session-live-status"}
        (:seon.ui/hiccup (rstatus/render-status-card {:seon.ui.status/entity agent-status}))])
     (render-canvas-stage-body model label prose exp? custom-canvas? md-opts live-acts)
     (when exp?
       (list
        (render-canvas-context-grid entities pool-count viewing-id true)
        (when (or prose work-summary)
          [:details {:id "cv-demos-fold" :class "activity-fold"}
           [:summary {:class "mono tiny"} "Interactive Canvas Showcase & Demos"]
           [:div {:style {:margin-top "10px"}}
            (render-canvas-demo-deck main-db viewing-id signals)]])))
     (when include-composer?
       [:form {:class "ws-composer" :on-submit (list 'seon.web/chat-agent-form! viewing-id)}
        [:div {:class "composer-shell"}
         [:textarea {:class "composer-inp" :name "text" :rows 1 :data-on:keydown enter-submit-js
                     :placeholder (if exp? (str "Message " label " or (eval Clojure)…") "Ask anything…")}]
         [:button {:type "submit" :class "send-icon-btn" :aria-label "Send"} rmd/svg-arrow-up]]])]))