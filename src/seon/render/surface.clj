(ns seon.render.surface
  "ONE unified rule-projected surface pipeline for `:canvas`, `:workspace`, `:debug`, and `:wire`.
   Reads viewport geometry, `seon.signals/all-signals`, and `seon.ctx/related-updates`, ranks
   candidates with 3 native Datahike signals, and runs the 2-pass Upgrade Ladder."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.ctx.project :as proj]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.markdown :as rmd]
            [seon.render.wire :as rwire]
            [seon.signals :as sig]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge surface-ru-cache (store/lru-cache 16))

(def ^:private enter-submit-js
  "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.target.value.trim()){evt.target.form.requestSubmit();}}")

(defn- live-cid [db]
  (when (and db (instance? datahike.db.DB db)) (d/commit-id db)))

(defn- safe-as-of
  {:seon.flow/edge true}
  [db tx]
  (if (and db (integer? tx)) (try (d/as-of db tx) (catch Throwable _ db)) db))

(defn- db-schema [db]
  (or (:schema db) (when db (d/schema db)) {}))

(defn- enrich-entity-params [db ent]
  (let [dh (db-schema db)
        src (when-let [fk (and (contains? dh :seon.code.form/key) (:seon.code.var/form ent))]
              (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key fk])))
        fix (when-let [ek (and (contains? dh :seon.fix/error-class) (:seon.error/kind ent))]
              (when-let [fe (:e (first (d/datoms db :avet :seon.fix/error-class ek)))]
                (d/pull db '[*] fe)))]
    (cond-> {}
      (and (string? src) (not (str/blank? src))) (assoc :seon.code.form/src src)
      (and (map? fix) (seq (dissoc fix :db/id))) (assoc :seon.fix/entity fix))))

(defn- cached-related-updates [main-db branch-db aid target]
  (let [mc (live-cid main-db)
        bc (live-cid branch-db)
        req {:seon.db/main main-db :seon.db/branch branch-db :seon.agent/id aid :seon.view/target target}
        ck (when (and mc bc) [(get-in main-db [:config :store :id]) mc (:max-tx main-db) bc (:max-tx branch-db) aid target])]
    (if ck (store/lru-get! surface-ru-cache 16 ck #(ctx/related-updates req)) (ctx/related-updates req))))

(defn- gather-surface-candidates [main-db bdb aid view-mode]
  (let [ru (cached-related-updates main-db bdb aid (if (#{:canvas :workspace :debug :wire} view-mode) view-mode :canvas))
        world (proj/agent-world-datoms main-db bdb aid)
        ru-ps (:seon.ctx/projections ru)
        seen (into #{} (keep :entity) ru-ps)
        pins (:pinned world)
        aeid (:agent-eid world)
        extra (into [] (comp (remove #(or (= (key %) aeid) (contains? seen (key %))))
                             (take 10)
                             (map (fn [[e tx]]
                                    {:entity e :tx tx :priority (if (contains? pins e) 88 60)
                                     :pinned? (contains? pins e) :zone :workspace :rules [:seed/world]})))
                    (sort-by (comp - val) (:connected world)))
        raw (into (mapv #(dissoc % :candidates :data) ru-ps) extra)]
    {:candidates
     (vec (keep (fn [item]
                  (when-let [eid (when (integer? (:entity item)) (:entity item))]
                    (let [b (d/pull bdb '[*] eid)
                          ent (if (seq (dissoc b :db/id)) b (d/pull main-db '[*] eid))
                          ep (when (map? ent) (enrich-entity-params bdb ent))]
                      (when (and (map? ent) (seq (dissoc ent :db/id)))
                        (cond-> (assoc item :data ent) (seq ep) (update :params merge ep))))))
                raw))
     :ctx-trace (:seon.ctx/trace ru)}))

(defn- extract-conversation [emdb ebdb aid active? as-of]
  (let [evals (rdata/agent-evals-in ebdb aid)
        msgs (rdata/agent-messages-in emdb aid)
        seen (store/agent-seen-message-ids ebdb aid)
        exs (rmd/split-exchanges (rmd/interleave-timeline msgs evals seen))
        ex-prose (fn [ex] (->> ex (filter #(= :eval (:kind %))) (map :data)
                               (filter #(str/blank? (str (:seon.agent.eval/code %))))
                               (keep :seon.agent.eval/thought) (remove #(str/blank? (str %))) vec))
        latest (peek exs)
        lum (last (filter rmd/prompt-msg? latest))
        unread? (when-let [mid (get-in lum [:data :seon.agent.message/id])] (not (contains? seen mid)))
        pending (when (and (nil? as-of) lum (empty? (ex-prose latest)) (or unread? active?))
                  (str (:seon.agent.message/body (:data lum))))
        sidx (last (keep-indexed #(when (seq (ex-prose %2)) %1) exs))
        disp (cond pending latest (some? sidx) (nth exs sidx) :else latest)
        devals (map :data (filter #(= :eval (:kind %)) disp))]
    {:evals evals :msgs msgs :exchanges exs :pending-prompt pending
     :latest-prose (if pending [] (ex-prose disp))
     :reply-prompt (some-> (last (filter rmd/prompt-msg? disp)) :data :seon.agent.message/body)
     :latest-actions (filterv #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                   (not (str/blank? (str (:seon.agent.eval/reasoning %))))) devals)}))

(defn build-surface-model
  "Pure unified surface model builder over `main-db`, `branch-db`, and `opts`.
   Computes viewport slot budget, live ambient signals, 3-signal ranked + Upgrade-Ladder-fitted
   entities, generic `view-history-txs`, and conversation/debug state in one shared pipeline."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :map]] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] [:maybe :map]] :map]]
   :cost/ticks 40000000}
  ([main-db opts] (build-surface-model main-db nil opts))
  ([main-db branch-db opts]
   (let [t0 (System/nanoTime)
         o (if (map? opts) opts {})
         vs (rdata/user-view-state main-db)
         aid (or (:viewing-id o) (:viewing-id vs) (:entry-id vs) :agent/root)
         vm (or (:view-mode o) (:view-mode vs) :canvas)
         vw (long (or (:viewport-w o) (:viewport-w vs) 1280))
         vh (long (or (:viewport-h o) (:viewport-h vs) 820))
         sb? (if (boolean? (:sidebar-open? o)) (:sidebar-open? o) (boolean (:sidebar-open? vs)))
         as-of (if (contains? o :as-of-tx) (:as-of-tx o) (:as-of-tx vs))
         slot (or (:slot o) (if (= :wire (:target o)) :wire :main))
         target (if (= :wire (:target o)) :wire :html)
         slot-b (fit/resolve-slot-budget {:viewport-w vw :viewport-h vh :sidebar-open? sb? :slot slot})
         br (or (:branch o)
                (when (and aid (contains? (db-schema main-db) :seon.agent/id))
                  (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])))
                :main)
         hbdb (or branch-db (rdata/branch-db-for main-db (:conn o) br))
         emdb (if as-of (safe-as-of main-db as-of) main-db)
         ebdb (if as-of (safe-as-of hbdb as-of) hbdb)
         ag (rdata/pull-agent-entity emdb ebdb aid)
         sigs (sig/all-signals emdb)
         fopts (merge {:target target :viewport-w vw :viewport-h vh :sidebar-open? sb?
                       :slot slot :avail-w (:avail-w slot-b) :area-budget (:area-budget slot-b)
                       :fallback-renderers rwire/fallback-entity-renderers}
                      (select-keys o [:token-budget :already-shown :strategy]))
         sfit (first (:selected (fit/fit-projections emdb [{:entity :signals/live :schema :seon.signal/snapshot
                                                            :data sigs :priority 100}] fopts)))
         wire (when (or (= vm :debug) (:include-wire? o))
                (rwire/build-live-wire-request emdb ebdb aid
                                               (select-keys o [:conn :viewport-screenshot :vision?
                                                               :already-shown :token-budget :strategy])))
         {:keys [fitted ctx-trace]}
         (if (and (= vm :debug) wire)
           {:fitted {:selected (or (get-in wire [:fit-trace :items]) [])
                     :tokens (get-in wire [:fit-trace :tokens] 0)
                     :budget (get-in wire [:fit-trace :budget] 16000)}
            :ctx-trace (:rule-trace wire)}
           (let [{:keys [candidates ctx-trace]} (gather-surface-candidates emdb ebdb aid vm)]
             {:fitted (fit/fit-projections ebdb candidates fopts)
              :ctx-trace ctx-trace}))
         peids (vec (keep #(when (integer? (:entity %)) (:entity %)) (:selected fitted)))
         htxs (or (rdata/view-history-txs hbdb main-db aid peids) [])
         sidx (when (seq htxs)
                (if as-of
                  (or (first (keep-indexed #(when (= %2 as-of) %1) htxs))
                      (last (keep-indexed #(when (<= %2 as-of) %1) htxs)) 0)
                  (dec (count htxs))))
         conv (if (= vm :debug) {} (extract-conversation emdb ebdb aid (= :active (get (:agent-states o) aid)) as-of))
         errs (when (and (= vm :debug) (contains? (db-schema emdb) :seon.error/status))
                (mapv #(d/pull emdb '[:seon.error/kind :seon.error/severity :seon.error/message] (:e %))
                      (take 8 (d/datoms emdb :avet :seon.error/status :open))))]
     (merge conv
            {:agent ag :viewing-id aid :branch br :view-mode vm
             :viewport {:w vw :h vh :sidebar-open? sb?} :slot-budget slot-b
             :signals sigs :signals-fit sfit :fitted fitted :entities (:selected fitted)
             :history-txs htxs :step-idx sidx :as-of-tx as-of :ctx-trace ctx-trace
             :wire wire :open-errors errs :main-db emdb :branch-db ebdb
             :branch-feedback (:branch-feedback o) :walk-summary (:walk-summary o) :conn (:conn o)
             :ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)}))))

;; Shared pure Hiccup surface sections

(defn windowed-history-indices
  "Selects up to 14 bounded step indices for the interactive `d/as-of` timeline scrubber."
  {:malli/schema [:=> [:cat :int :int] [:vector :int]] :cost/ticks 100000}
  [n cur-idx]
  (let [n (max 0 (long n)) ci (max 0 (min (max 0 (dec n)) (long cur-idx)))]
    (cond (zero? n) [] (<= n 14) (vec (range n))
          :else (vec (distinct (concat [0] (range (max 1 (- ci 5)) (inc (min (- n 2) (+ ci 5)))) [(dec n)]))))))

(defn render-surface-timeline
  "Generic Datahike `d/as-of` time-travel bar shared across `:canvas`, `:workspace`, and `:debug`."
  {:malli/schema [:=> [:cat [:maybe [:sequential :int]] [:maybe :int] [:maybe :int]] :any]
   :cost/ticks 500000}
  [hist-txs cur-idx as-of-tx]
  (let [txs (vec (filter integer? (or hist-txs [])))
        n (count txs)
        in-h? (boolean (and as-of-tx (some #(= (long %) (long as-of-tx)) txs)))
        past? (boolean (and as-of-tx (or (<= n 1) (not in-h?) (< (long (or cur-idx (dec n))) (dec n)))))]
    (when (or (> n 1) past?)
      (let [idx (if (pos? n) (max 0 (min (dec n) (long (or cur-idx (dec n))))) 0)]
        [:div {:class (str "canvas-timeline-bar" (when past? " historical"))}
         (when (> n 1)
           [:div {:class "tl-nav"}
            [:button {:type "button" :class "tl-btn" :aria-label "Previous step" :disabled (zero? idx)
                      :on-click '(seon.web/step-canvas-history! -1)} rmd/svg-chevron-left]
            [:button {:type "button" :class "tl-btn" :aria-label "Next step" :disabled (>= idx (dec n))
                      :on-click '(seon.web/step-canvas-history! 1)} rmd/svg-chevron-right]])
         (if (> n 1)
           [:div {:class "tl-track" :role "tablist"}
            (for [i (windowed-history-indices n idx)
                  :let [tx (nth txs i) c (cond (and in-h? (= i idx)) " active" (< i idx) " past" :else "")]]
              [:button {:type "button" :class (str "tl-pip" c) :title (str "Step " (inc i) "/" n " · tx " tx)
                        :on-click (list 'seon.web/jump-canvas-tx! tx)}])]
           [:span {:class "kicker"} (str "Snapshot · tx " as-of-tx)])
         [:div {:class "tl-meta"}
          [:span (cond (and past? (not in-h?)) (str "Time-travel · tx " as-of-tx)
                       past? (str "Time-travel · " (inc idx) " / " n)
                       :else (str "History · " (inc idx) " / " n))]
          (when past? [:button {:type "button" :class "step-live" :on-click '(seon.web/jump-canvas-live!)} "Live"])]]))))

(defn- card [id title pills & body]
  (into [:section (cond-> {:class "card"} id (assoc :id id))
         [:div {:class "card-head"} [:h2 title] (when (seq pills) (into [:div {:class "pill-row"}] pills))]]
        (remove nil? body)))

(defn- feedback-banner [fb]
  (when fb
    [:div {:class (str "notice row-between " (if (:ok? fb) "green" "red"))}
     [:span (:text fb)]
     [:button {:type "button" :class "link-muted tiny" :on-click '(seon.web/dismiss-feedback!)} "×"]]))

(defn- composer-form [cls action field-name placeholder]
  [:form {:class cls :on-submit action}
   [:div {:class "composer-shell"}
    [:textarea {:class "composer-inp" :name field-name :rows 1 :data-on:keydown enter-submit-js :placeholder placeholder}]
    [:button {:type "submit" :class "send-icon-btn" :aria-label "Send"} rmd/svg-arrow-up]]])

(defn- render-fitted-entities [entities avail-w]
  (when (seq entities)
    [:section {:class (if (< (long avail-w) 600) "surface-entities stack-sm" "surface-entities stack")
               :data-entity-count (str (count entities))}
     (for [item entities]
       [:div {:class "surface-entity-item" :data-entity (str (:entity item))
              :data-chosen-fn (str (:chosen-fn item)) :data-min-width (str (:min-width item 0))}
        (or (:hiccup item) [:div {:class "ent-row"} [:span {:class "mono tiny"} (str (:entity item))]])])]))

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

(defn- render-canvas-body [{:keys [agent viewing-id branch signals-fit entities slot-budget
                                   pending-prompt latest-prose reply-prompt latest-actions
                                   branch-feedback]} include-composer?]
  (let [label (rdata/agent-label agent viewing-id)
        prose (when (seq latest-prose) (str/join "\n\n" latest-prose))]
    [:div {:class "stack"}
     (when (:hiccup signals-fit)
       [:div {:class "surface-signals" :data-chosen-fn (str (:chosen-fn signals-fit))} (:hiccup signals-fit)])
     (when (and branch-feedback (not (:ok? branch-feedback))) (feedback-banner branch-feedback))
     (cond
       pending-prompt
       [:section {:id "cv-pending" :class "ch-pending-card canvas-pending"}
        [:div {:class "root-kicker"} (str "In flight · " label)]
        [:h1 {:class "canvas-prompt-headline"} pending-prompt]
        (when (seq latest-actions) (rmd/render-activity-fold latest-actions "cv-live"))]
       prose
       [:section {:class "canvas-reply"}
        [:div {:class "root-kicker"} (str label " · " branch)]
        (when (not-empty reply-prompt) [:div {:class "canvas-re-prompt"} (str "Re: " reply-prompt)])
        (rmd/render-markdown-blocks prose)
        (when (seq latest-actions) (rmd/render-activity-fold latest-actions "cv"))]
       :else
       [:header {:class "root-hero"}
        [:div {:class "root-kicker"} (str label " · " branch)]
        [:h1 {:class "root-title"} (or (not-empty (:seon.agent/hero-title agent)) label)]
        (when-let [g (not-empty (:seon.agent/goal agent))] [:p {:class "root-sub"} g])])
     (render-fitted-entities entities (:avail-w slot-budget))
     (when include-composer?
       (composer-form "surface-composer" (list 'seon.web/chat-agent-form! viewing-id) "text" (str "Message " label "…")))]))

(defn- render-workspace-body [{:keys [agent viewing-id branch branch-db signals-fit entities
                                      exchanges branch-feedback slot-budget]}]
  (let [wide? (>= (long (:avail-w slot-budget 1000)) 860)
        label (rdata/agent-label agent viewing-id)
        left [:section {:id "ws-left-unit" :class "ws-left"}
              [:div {:class "ws-head"}
               [:div [:div {:class "pill-row"} [:h2 {:class "mono"} (str viewing-id)]
                      [:span {:class "badge amber"} (name (or (:seon.agent/role agent) :worker))]]
                [:div {:class "muted tiny mono"}
                 (str "branch " branch " @ tx " (:max-tx branch-db)
                      (when (:seon.agent/scope agent) (str " · scope " (:seon.agent/scope agent))))]]
               [:div {:class "pill-row"}
                [:button {:type "button" :class "btn sm" :on-click (list 'seon.web/run-tests! branch)} "Test Branch"]
                (when (not= branch :main)
                  [:button {:type "button" :class "btn sm success" :on-click (list 'seon.web/merge-branch! branch)} "Merge → :main"])]]
              (feedback-banner branch-feedback)
              [:div {:class "ws-timeline" :data-agent (str viewing-id)}
               (when-let [g (not-empty (:seon.agent/goal agent))]
                 [:div {:class "goal-banner"} [:span {:class "kicker"} "Goal"] [:p {:class "sm-copy"} g]])
               (if (empty? exchanges)
                 [:p {:class "muted sm-copy"} "No conversation yet. Send a message or evaluate Clojure below."]
                 (render-exchanges-list "wx" exchanges))]
              (composer-form "ws-composer" (list 'seon.web/eval-agent-form! viewing-id branch)
                             "code" (str "Message " label " or (eval Clojure)…"))]
        right [:section {:class "ws-right"}
               [:div {:id "ws-right-head-unit"}
                [:div {:class "ws-head"} [:h2 (str "Live Surface Entities (" (count entities) ")")]
                 [:button {:type "button" :class "nav-pill tiny" :on-click '(seon.web/set-view-mode! :debug)} "Wire Debug →"]]
                [:form {:class "row-form" :style "margin-bottom:8px" :on-submit (list 'seon.web/pin-ref-form! viewing-id)}
                 [:input {:class "inp sm mono flex-1" :name "ref" :placeholder "Pin ref, e.g. [:seon.code.ns/name 'seon.web]"}]
                 [:button {:type "submit" :class "btn sm"} "+ Pin"]]]
               (when (:hiccup signals-fit)
                 [:div {:class "surface-signals" :style "margin-bottom:8px" :data-chosen-fn (str (:chosen-fn signals-fit))}
                  (:hiccup signals-fit)])
               [:div {:class "tiles-scroll"}
                (or (render-fitted-entities entities (:avail-w slot-budget))
                    [:div {:id "ws-tiles-empty" :class "card muted sm-copy"} "No projected entities yet."])]]]
    (if wide? (list left right)
        [:div {:class "surface-stack" :style "display:flex;flex-direction:column;gap:12px"} left right])))

(defn- render-debug-ladder [items]
  (when (seq items)
    [:div {:class "table-scroll" :style "margin-top:8px"}
     [:table {:class "data-table"}
      [:thead [:tr [:th "Entity"] [:th "Score"] [:th "Chosen Renderer"] [:th "Status"] [:th "Cost"] [:th "Levels"]]]
      [:tbody
       (for [it (take 14 items)]
         [:tr [:td {:class "mono tiny"} (str (or (:schema it) (:entity it)))]
          [:td {:class "mono tiny"} (str (:rank/score it "—"))]
          [:td {:class "mono tiny"} (str (:chosen-fn it))]
          [:td [:span {:class "badge blue"} (name (or (:status it) :new))]]
          [:td {:class "mono tiny muted"} (str (:tokens it 0) " tok · " (:min-width it 0) "px")]
          [:td {:class "mono tiny muted"} (str (count (:candidates it)))]])]]]))

(defn- render-debug-body [{:keys [agent viewing-id branch main-db branch-db signals signals-fit
                                  entities wire ctx-trace open-errors branch-feedback walk-summary conn]}]
  (let [msgs (:messages wire) mtoks (:message-tokens wire)
        rt (or (:rule-trace wire) ctx-trace)
        runs (filterv #(pos? (long (or (:fired %) 0))) (or (:runs rt) (:fired-rules rt) []))
        ss (:surface-stats wire) tiles (take 8 (:tiles wire))
        brs (when conn (vec (take 16 (sort (d/branches conn)))))]
    [:div {:class "stack"}
     [:div {:id "dbg-feedback-unit"} (feedback-banner branch-feedback)]
     (card "dbg-wire-unit" (str "Exact Wire Request for " viewing-id " (branch " branch ")")
           [[:span {:class "badge blue mono"} (str "~" (:total-tokens wire 0) " tok")]
            (when (:prefix-hash wire) [:span {:class "badge mono"} (str "prefix " (:prefix-hash wire))])
            [:span {:class "badge green mono"} (str (count msgs) " msgs")]
            [:span {:class "badge purple mono"} (str (:unchanged ss 0) "/" (:selected ss 0) " cached")]]
           [:div {:class "wire-list"}
            (for [[idx [m tok]] (map-indexed vector (take 10 (map vector msgs mtoks)))
                  :let [body (if (string? (:content m)) (:content m) "[multimodal]")
                        l1 (str (first (remove str/blank? (str/split-lines body))))]]
              [:details {:id (str "wm-" idx) :class "wire-row" :open (< idx 2)}
               [:summary {:class "wire-summary"}
                [:span {:class "muted tiny mono"} (str "#" idx)]
                [:span {:class "badge blue"} (str/upper-case (str (:role m)))]
                [:span {:class "muted tiny truncate flex-1"} (if (> (count l1) 100) (str (subs l1 0 100) "…") l1)]
                [:span {:class "mono tiny muted"} (str "~" tok " tok")]]
               [:pre {:class "code-box tiny" :style "white-space:pre-wrap"}
                (if (> (count body) 1400) (str (subs body 0 1400) "\n…") body)]])]
           (render-debug-ladder (or (get-in wire [:fit-trace :items]) entities)))
     [:section {:id "dbg-health-unit" :class "card"}
      (:hiccup signals-fit)
      (when (seq open-errors)
        [:div {:class "stack-xs" :style "margin-top:8px"}
         [:span {:class "kicker"} (str "Open Errors (" (count open-errors) ")")]
         (for [e open-errors] [:div {:class "notice red tiny mono"} (str (:seon.error/kind e) " · " (:seon.error/message e))])])]
     [:div {:id "dbg-rules-unit"}
      (card "dbg-rule-trace" "Rule Firing Trace (seon.rule)"
            [[:span {:class "badge purple mono"} (str (or (:rules-count rt) (:rules-total rt) 0) " rules")]
             [:span {:class "badge green mono"} (str (count runs) " fired")]
             [:span {:class "badge mono"} (str (:ms rt 0.0) " ms")]]
            (if (empty? runs)
              [:p {:class "muted sm-copy"} "No rules fired."]
              [:table {:class "data-table"}
               [:thead [:tr [:th "Rule"] [:th "Stratum"] [:th "Fired"] [:th "Reads"] [:th "ms"]]]
               [:tbody (for [r (take 16 runs)]
                         [:tr [:td {:class "mono tiny"} (str (:rule-id r))] [:td {:class "mono tiny"} (str (:stratum r))]
                          [:td {:class "mono tiny"} (str (:fired r))] [:td {:class "mono tiny muted"} (str (:reads-count r 0))]
                          [:td {:class "mono tiny muted"} (str (:ms r 0.0) " ms")]])]]))]
     (card "dbg-versions-unit" (str "Rendered Versions — Human HTML vs Model Text (" (count tiles) ")")
           [[:span {:class "badge purple mono"} "1 Hiccup → 2 targets"]]
           [:div {:class "stack-sm"}
            (for [[idx t] (map-indexed vector tiles)]
              [:div {:id (str "dbg-tile-" idx) :class "debug-tile-pair"}
               [:div {:class "debug-tile-col"} [:span {:class "kicker"} "Human HTML (Browser)"]
                (or (:hiccup t) [:span {:class "muted tiny"} (str (:schema t))])]
               [:div {:class "debug-tile-col"} [:span {:class "kicker"} "Model Text (render/hiccup->text)"]
                [:pre {:class "code-box tiny"} (let [s (str (:text t))] (if (> (count s) 1000) (str (subs s 0 1000) "…") s))]]])])
     [:div {:id "dbg-ctx-unit" :class "grid-2"}
      (card nil (str "Live @seon.ctx/ctx Atom Value (" viewing-id ")")
            [[:span {:class "badge mono"} (str "tx " (:max-tx branch-db))]]
            [:pre {:class "code-box tiny"}
             (pr-str (select-keys agent [:seon.agent/id :seon.agent/branch :seon.agent/role :seon.agent/scope :seon.agent/task :seon.agent/pinned]))])
      (card nil "datahike.reads Incremental Walk" [[:span {:class "muted tiny"} (str (count walk-summary) " units")]]
            [:div {:class "pill-row"} (for [{:keys [id reads-count]} walk-summary] [:span {:class "pill mono tiny"} (str "#" id ":" (or reads-count 0))])])]
     [:div {:class "grid-2"}
      (card "dbg-branches-unit" (str "Branches (" (count brs) ")") nil
            [:div {:class "pill-row"} (for [b brs] [:span {:class "pill mono tiny"} (str b)])])
      (card "dbg-tests-unit" "Proof Signal" nil [:p {:class "mono tiny"} (get-in signals [:proof :summary] "—")])]
     (card "dbg-schema-unit" "Schemas" nil [:span {:class "muted tiny mono"} (str (count (db-schema main-db)) " Datahike attributes")])
     [:details {:id "dbg-drill-unit" :class "card fold"}
      [:summary {:class "muted tiny mono"} "Diagnostics & Crash-Recovery Drill"]
      [:button {:type "button" :class "btn sm danger" :on-click '(seon.web/power-loss-reboot!)} "Cold Reboot Drill"]]]))

(defn render-surface
  "ONE unified Hiccup surface renderer for `:canvas`, `:workspace`, and `:debug`.
   Mounts `#seon-view-unit` with viewport/slot data attributes, generic `d/as-of` timeline scrubber,
   ambient operational signals, and Upgrade-Ladder-adapted entity cards."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :map]] [:vector :any]]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] [:maybe :map]] [:vector :any]]]
   :cost/ticks 45000000}
  ([main-db opts] (render-surface main-db nil opts))
  ([main-db branch-db opts]
   (let [o (if (map? opts) opts {})
         model (build-surface-model main-db branch-db o)
         {:keys [viewing-id view-mode viewport slot-budget history-txs step-idx as-of-tx]} model
         include-composer? (if (contains? o :include-composer?) (boolean (:include-composer? o)) true)
         tl (render-surface-timeline history-txs step-idx as-of-tx)
         cls (case view-mode
               :workspace (if (>= (long (:avail-w slot-budget 1000)) 860) "workspace-grid" "workspace-grid surface-stack")
               :debug "debug-wrap"
               "root-canvas")
         body (case view-mode
                :workspace (render-workspace-body model)
                :debug (render-debug-body model)
                (render-canvas-body model include-composer?))]
     [:div {:id "seon-view-unit" :class cls
            :data-agent (str viewing-id) :data-view-mode (name view-mode)
            :data-viewport-w (str (:w viewport)) :data-slot-avail-w (str (:avail-w slot-budget))}
      tl body])))
