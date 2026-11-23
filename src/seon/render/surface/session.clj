(ns seon.render.surface.session
  "Focused Agent Session (`:session` / `:canvas`) renderer: honest live conversation +
   tight Active Context rail (capped to 8 high-signal entities by default via `project-entity-view`)."
  (:require [clojure.string :as str]
            [seon.render.data :as rdata]
            [seon.render.links :as rlinks :refer [badge kicker]]
            [seon.render.markdown :as rmd]
            [seon.store :as store]))

(def ^:private enter-submit-js
  "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.isComposing||evt.keyCode===229)return;if(evt.target.value.trim()){evt.target.form.requestSubmit();}}")

(defn- btn [cls oc lbl] [:button {:type "button" :class cls :on-click oc} lbl])
(defn- el [ref lbl cls] (rlinks/ent-link ref lbl cls))

(defn extract-conversation
  "Extracts interleaved user messages, REPL evals, honest pending/work-summary state, and latest prose."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :map :boolean [:maybe :int]] :map]
   :cost/ticks 15000000}
  [emdb ebdb aid ag active? as-of]
  (let [evals (rdata/agent-evals-in ebdb aid)
        msgs (rdata/agent-messages-in emdb aid)
        seen (store/agent-seen-message-ids ebdb aid)
        exs (rmd/split-exchanges (rmd/interleave-timeline msgs evals seen))
        ex-prose (fn [ex] (->> ex (filter #(= :eval (:kind %))) (map :data)
                               (filter #(str/blank? (str (:seon.agent.eval/code %))))
                               (keep :seon.agent.eval/thought) (remove #(str/blank? (str %))) vec))
        latest (peek exs)
        ex-evals (mapv :data (filter #(= :eval (:kind %)) latest))
        lum (last (filter rmd/prompt-msg? latest))
        latest-prompt (some-> lum :data :seon.agent.message/body str not-empty)
        done-ag? (boolean (or (:seon.agent/ready? ag) (:seon.agent/paused? ag)
                              (pos? (long (or (:seon.agent/merged-tx ag) 0)))))
        pending (when (and (nil? as-of)
                           (or active? (and latest-prompt (empty? ex-evals) (not done-ag?))))
                  (or latest-prompt "Working…"))
        latest-p (ex-prose latest)
        has-work? (and (nil? pending) (empty? latest-p) (seq ex-evals))
        last-intent (some #(not-empty (str/trim (str (or (:seon.agent.eval/intent %) "")))) (reverse ex-evals))
        work-sum (when has-work?
                   {:prompt latest-prompt :eval-count (count ex-evals)
                    :latest-intent (or last-intent (str (count ex-evals) " REPL step(s) completed"))
                    :status-label (cond (pos? (long (or (:seon.agent/merged-tx ag) 0))) "Merged"
                                        (:seon.agent/ready? ag) "Ready"
                                        (:seon.agent/paused? ag) "Paused" :else "Completed")})
        sidx (last (keep-indexed #(when (seq (ex-prose %2)) %1) exs))
        disp (cond pending latest has-work? latest (some? sidx) (nth exs sidx) :else latest)]
    {:evals evals :msgs msgs :exchanges exs :pending-prompt pending :work-summary work-sum
     :latest-prose (if (or pending has-work?) [] (ex-prose disp))
     :reply-prompt (some-> (last (filter rmd/prompt-msg? disp)) :data :seon.agent.message/body)
     :latest-actions (filterv #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                   (not (str/blank? (str (:seon.agent.eval/reasoning %)))))
                              (map :data (filter #(= :eval (:kind %)) disp)))}))

(defn canvas-live-progress
  "Classifies optional `live-stream` map into live phase badge, `tok/s` rate, prose, and reasoning preview."
  {:malli/schema [:=> [:cat [:maybe :map]]
                  [:map [:phase :keyword] [:label :string] [:css-class :string]
                   [:rate [:maybe :string]] [:reasoning [:maybe :string]] [:text [:maybe :string]]]]
   :cost/ticks 200000 :cost/bytes 32768}
  [live-stream]
  (let [sinfo (when (map? live-stream) live-stream)
        raw (when sinfo (or (:phase sinfo) (:status sinfo)))
        [phase label css-cls]
        (case raw
          (:thinking :reasoning :reasoning-token) [:thinking "Thinking…" "phase-thinking"]
          (:tools :eval :running-tools :done)     [:tools "Running tools…" "phase-tools"]
          (:writing :token)                       [:writing "Writing…" "phase-writing"]
          :testing                                [:testing "Testing…" "phase-testing"]
          :merging                                [:merging "Merging…" "phase-merging"]
          [:working "Working…" "phase-working"])
        rtxt (str (:reasoning sinfo "")) txt (str (:text sinfo ""))
        n-or (fn [k d] (let [v (get sinfo k)] (if (and (number? v) (not (Double/isNaN (double v))) (not (Double/isInfinite (double v)))) (max 0 (long v)) d)))
        chars (if sinfo (n-or :chars (+ (count rtxt) (count txt))) 0)
        step-ch (if sinfo (n-or :step-chars chars) 0)
        st (if sinfo (n-or :started-ms 0) 0) lm (if sinfo (n-or :last-ms 0) 0)
        tps (when (and (#{:thinking :writing} phase) (pos? step-ch) (pos? st) (>= lm st))
              (max 1 (Math/round (/ (/ (double step-ch) 4.0) (max 0.5 (/ (double (- lm st)) 1000.0))))))
        rate (cond tps (str tps " tok/s") (pos? chars) (str (max 1 (Math/round (/ (double chars) 4.0))) " tok") :else nil)]
    {:phase phase :label label :css-class css-cls :rate rate
     :reasoning (when-not (str/blank? rtxt) rtxt) :text (when-not (str/blank? txt) txt)}))

(defn- entity-vt-slug [ent]
  (let [s (-> (str ent) (str/replace #"[^A-Za-z0-9_-]+" "-") (str/replace #"^-+|-+$" ""))]
    (if (str/blank? s) "x" (subs s 0 (min 36 (count s))))))

(def ^:private group-tasks-edits #{:seon.task/entity :seon.code.var/entity :seon.code.ns/entity :seon.code.doc/entity})
(def ^:private group-alerts-proofs #{:seon.error/entity :seon.fix/entity :seon.test/verdict :seon.agent.eval/entity})

(defn- entity-group-key [it]
  (let [sk (or (first (:schemas it)) (:schema it))]
    (cond (group-tasks-edits sk) :tasks-edits (group-alerts-proofs sk) :alerts-proofs :else :pinned-rules)))

(defn- render-grouped-entities [entities avail-w]
  (when (seq entities)
    (let [by-g (group-by entity-group-key entities) counts (volatile! {})
          mk-item (fn [item]
                    (let [slug (entity-vt-slug (:entity item))
                          occ (long (get @counts slug 0)) _ (vswap! counts assoc slug (inc occ))
                          vt (str "ent-" occ "-" slug)
                          ref (or (rdata/entity-lookup-ref (:data item)) (when (integer? (:entity item)) [:db/id (:entity item)]))
                          cfn (:chosen-fn item) sem (:semantic item)]
                      [:div {:class "surface-entity-item" :style (str "view-transition-name:" vt)
                             :data-entity (str (:entity item)) :data-chosen-fn (str cfn) :data-min-width (str (:min-width item 0))}
                       [:div {:class "ent-prov-bar"}
                        [:div {:class "pill-row"}
                         (if (qualified-symbol? cfn) (el [:seon.code.var/name cfn] (name cfn) "mono tiny muted")
                             [:span {:class "mono tiny muted"} (str (some-> cfn name))])
                         [:span {:class "mono tiny muted"} (str "· " (:tokens item 0) " tok")]
                         (for [r (take 2 (:rules item))]
                           (if (rlinks/real-rule-id? r) (el [:seon.rule/id r] (name r) "pill mono tiny") [:span {:class "pill mono tiny"} (str r)]))
                         (when (number? sem) (badge "purple" (str "cos " (/ (Math/round (* (double sem) 100.0)) 100.0))))]
                        (when ref (el ref "Inspect variations →" "tiny mono"))]
                       (or (:hiccup item)
                           [:div {:class "ent-row mono tiny"}
                            (if ref (el ref (rdata/entity-short-label (or (:data item) ref)) "mono tiny strong") (str (:entity item)))])]))]
      [:div {:class (if (< (long avail-w) 600) "surface-entities stack-sm" "surface-entities stack")
             :data-entity-count (str (count entities))}
       (for [[gk title] [[:tasks-edits "Tasks & Edits"] [:alerts-proofs "Alerts & Proofs"] [:pinned-rules "Pinned & Rules"]]
             :let [xs (get by-g gk)] :when (seq xs)]
         [:section {:class "ctx-group stack-sm"}
          [:div {:class "ctx-group-head"} (kicker title) [:span {:class "mono tiny muted"} (str (count xs))]]
          (mapv mk-item xs)])])))

(defn- render-exchanges-list [prefix exchanges]
  (for [[idx ex] (map-indexed vector exchanges)
        :let [emsgs (filter #(= :msg (:kind %)) ex)
              eevals (map :data (filter #(= :eval (:kind %)) ex))
              prose (->> eevals (filter #(str/blank? (str (:seon.agent.eval/code %))))
                         (keep :seon.agent.eval/thought) (remove #(str/blank? (str %))) vec)
              acts (filterv #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                 (not (str/blank? (str (:seon.agent.eval/reasoning %))))) eevals)]]
    [:div {:id (str prefix "-" idx) :class "ws-exchange"}
     (for [[mi m] (map-indexed vector emsgs)]
       [:div {:id (str prefix "-" idx "-m-" mi) :class "ws-user-bubble"} (str (:seon.agent.message/body (:data m) ""))])
     (when (or (seq acts) (seq prose))
       [:div {:class (str "ws-agent-turn" (when (seq prose) " has-prose"))}
        (rmd/render-activity-fold acts (str prefix "-" idx))
        (for [[pi p] (map-indexed vector prose)]
          [:div {:id (str prefix "-" idx "-p-" pi) :class "chat-body"} (rmd/render-markdown-blocks p)])])]))

(defn- honest-status-badge [ag active?]
  (cond active? (badge "amber" "live") (pos? (long (or (:seon.agent/merged-tx ag) 0))) (badge "green" "merged")
        (:seon.agent/ready? ag) (badge "green" "ready") (:seon.agent/paused? ag) (badge "muted" "paused")
        (:seon.agent/waiting? ag) (badge "blue" "waiting") :else (badge "muted" "idle")))

(defn render-session-body
  "Renders the focused Agent Session view: ultra-clean work bar, honest conversation, and tight
   Active Context rail (capped to 8 high-signal entities with Explain selection link)."
  {:malli/schema [:=> [:cat :map :boolean] :any] :cost/ticks 20000000}
  [{:keys [agent viewing-id branch branch-db entities pool-count exchanges pending-prompt latest-prose
           reply-prompt latest-actions work-summary live-stream branch-feedback slot-budget active?]}
   include-composer?]
  (let [label (rdata/agent-label agent viewing-id)
        prose (when (seq latest-prose) (str/join "\n\n" latest-prose))
        earlier (when (> (count exchanges) 1) (pop exchanges))
        scope (:seon.agent/scope agent) task (:seon.agent/task agent) parent (:seon.agent/parent agent)]
    (list
     [:section {:id "ws-left-unit" :class "ws-left"}
      [:div {:class "ws-head work-bar"}
       [:div {:class "stack-xs"}
        [:div {:class "pill-row"}
         (el [:seon.agent/id viewing-id] label "mono strong")
         (badge "blue" (name (or (:seon.agent/role agent) :worker)))
         (honest-status-badge agent active?)]
        [:div {:class "pill-row tiny mono muted"}
         (el [:seon.branch/name branch] (str branch " @ tx " (:max-tx branch-db)) "mono tiny muted")
         (when parent (list "·" (el [:seon.agent/id parent] (str "↑" (name parent)) "mono tiny muted")))
         (when scope (list "·" (el [:seon.code.ns/name scope] (str scope) "mono tiny")))
         (when task (list "·" (el [:seon.task/id task] (str task) "mono tiny")))]]
       (when (not= branch :main)
         [:div {:class "pill-row"}
          (btn "btn sm" (list 'seon.web/run-tests! branch) "Test")
          (btn "btn sm success" (list 'seon.web/merge-branch! branch) "Merge → :main")])]
      (when branch-feedback
        [:div {:class (str "notice row-between " (if (:ok? branch-feedback) "green" "red"))}
         [:span (:text branch-feedback)] (btn "link-muted tiny" '(seon.web/dismiss-feedback!) "×")])
      [:div {:class "ws-timeline" :data-agent (str viewing-id)}
       (when (and (seq earlier) (not pending-prompt))
         [:details {:id "session-earlier" :class "activity-fold"}
          [:summary {:class "mono tiny"} (str "Earlier exchanges (" (count earlier) ")")]
          [:div {:class "stack-sm" :style {:margin-top "8px"}} (render-exchanges-list "hx" earlier)]])
       (cond
         pending-prompt
         (let [{:keys [css-class rate reasoning text] pl :label} (canvas-live-progress live-stream)]
           [:section {:id "cv-pending" :class (str "ch-pending-card canvas-pending " css-class)}
            [:div {:class "root-kicker"} (str "In flight · " pl)]
            [:h1 {:class "canvas-prompt-headline"} pending-prompt]
            [:div {:class "canvas-pending-status"}
             [:span {:class "dot pulsing"}] [:span {:class (str "stream-phase-badge " css-class)} pl]
             (when rate [:span {:class "stream-rate"} rate])]
            (when text [:div {:id "cv-live-prose" :class "live-prose"} text [:span {:class "cursor"} "▍"]])
            (when reasoning
              [:details {:id "cv-live-reasoning" :class "activity-fold"}
               [:summary {:class "mono tiny"} (str "Live reasoning (" (count reasoning) " chars)")]
               [:div {:class "live-reasoning"} reasoning]])
            (when (seq latest-actions) (rmd/render-activity-fold latest-actions "cv-live"))])
         prose
         [:section {:class "canvas-reply"}
          (when (not-empty reply-prompt) [:div {:class "canvas-re-prompt"} (str "Re: " reply-prompt)])
          (rmd/render-markdown-blocks prose)
          (when (seq latest-actions) (rmd/render-activity-fold latest-actions "cv"))]
         work-summary
         [:section {:id "cv-work-summary" :class "card stack-sm"}
          [:div {:class "row-between"}
           [:div {:class "pill-row"} (badge "green" (:status-label work-summary)) [:strong {:class "sm-copy"} (:latest-intent work-summary)]]
           [:span {:class "mono tiny muted"} (str (:eval-count work-summary) " eval(s)")]]
          (when-let [pr (:prompt work-summary)] [:div {:class "canvas-re-prompt"} (str "Re: " pr)])
          (when (seq latest-actions) (rmd/render-activity-fold latest-actions "cv-work"))]
         :else
         [:header {:class "root-hero"}
          [:div {:class "root-kicker pill-row"}
           (el [:seon.agent/id viewing-id] label "mono tiny") "·" (el [:seon.branch/name branch] (str branch) "mono tiny")]
          [:h1 {:class "root-title"} (or (not-empty (:seon.agent/hero-title agent)) label)]
          (when-let [g (not-empty (:seon.agent/goal agent))] [:p {:class "root-sub"} g])])]
      (when include-composer?
        [:form {:class "ws-composer" :on-submit (list 'seon.web/chat-agent-form! viewing-id)}
         [:div {:class "composer-shell"}
          [:textarea {:class "composer-inp" :name "text" :rows 1 :data-on:keydown enter-submit-js
                      :placeholder (str "Message " label " or (eval Clojure)…")}]
          [:button {:type "submit" :class "send-icon-btn" :aria-label "Send"} rmd/svg-arrow-up]]])]
     [:section {:class "ws-right"}
      [:div {:id "ws-right-head-unit"}
       [:div {:class "ws-head"}
        [:h2 (str "Active Context (" (count entities) ")")]
        (btn "nav-pill tiny" '(seon.web/set-explain-lens! true nil)
             (if (pos? (long (or pool-count 0)))
               (str "Explain selection (" pool-count " in pool) →")
               "Explain selection →"))]
       [:form {:class "row-form" :on-submit (list 'seon.web/pin-ref-form! viewing-id)}
        [:input {:class "inp sm mono flex-1" :name "ref" :placeholder "Pin ref, e.g. [:seon.code.ns/name 'seon.web]"}]
        [:button {:type "submit" :class "btn sm"} "+ Pin"]]]
      [:div {:class "tiles-scroll"}
       (or (render-grouped-entities entities (:avail-w slot-budget))
           [:div {:id "ws-tiles-empty" :class "card muted sm-copy"} "No active context entities yet."])]])))
