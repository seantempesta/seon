(ns seon.render.surface.debug
  "Unified `:debug` wire builder and Hiccup inspector shared by `seon.render.surface`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.model :as model]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.wire :as rwire]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge surface-dbg-wire-cache (store/lru-cache 8))

(defn- live-cid [db]
  (when (and db (instance? datahike.db.DB db)) (d/commit-id db)))

(defn- db-schema [db]
  (or (:schema db) (when db (d/schema db)) {}))

(defn- badge [cls txt] [:span {:class (str "badge " cls)} txt])
(defn- pill [txt] [:span {:class "pill mono tiny"} txt])
(defn- mut [txt] [:span {:class "mono tiny muted"} txt])
(defn- kicker [txt] [:span {:class "kicker"} txt])
(defn- code-pre [s] [:pre {:class "code-box tiny"} s])
(defn- mt [s] [:td {:class "mono tiny"} s])
(defn- mtm [s] [:td {:class "mono tiny muted"} s])
(defn- trunc [s n] (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn- card [id title pills & body]
  (into [:section (cond-> {:class "card"} id (assoc :id id))
         [:div {:class "card-head"}
          [:h2 title]
          (when (seq pills) (into [:div {:class "pill-row"}] pills))]]
        (remove nil? body)))

(defn- feedback-banner [fb]
  (when fb
    [:div {:class (str "notice row-between " (if (:ok? fb) "green" "red"))}
     [:span (:text fb)]
     [:button {:type "button" :class "link-muted tiny" :on-click '(seon.web/dismiss-feedback!)} "×"]]))

(defn build-debug-wire
  "Builds and commit-id caches the unified `:debug` wire snapshot from `gather-fn` without running legacy full-scope scans."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :map :keyword :any :map] :map]}
  [emdb ebdb aid ag br gather-fn o]
  (let [budget (long (or (:token-budget o) 16000))
        strat (or (:strategy o) :breadth-first)
        vis? (boolean (:vision? o))
        mc (live-cid emdb)
        bc (live-cid ebdb)
        ck (when (and mc bc (empty? (:already-shown o)))
             [(get-in emdb [:config :store :id]) aid mc (:max-tx emdb) bc (:max-tx ebdb) vis? budget strat])
        build*
        (fn []
          (let [{:keys [candidates ctx-trace]} (gather-fn)
                wf (fit/fit-projections ebdb candidates
                                        {:target :wire :token-budget budget :strategy strat
                                         :already-shown (:already-shown o)
                                         :fallback-renderers rwire/fallback-entity-renderers})
                tiles (mapv #(assoc % :text (if (= :unchanged (:status %)) "" (render/hiccup->text (:hiccup %))))
                            (:selected wf))
                wtxt (str/join "\n\n---\n\n"
                               (keep #(when-not (str/blank? (:text %))
                                        (str "### [" (or (:schema %) (:chosen-fn %) :entity) "]\n" (:text %)))
                                     tiles))
                evs (rdata/agent-evals-in ebdb aid)
                msgs (rdata/agent-messages-in emdb aid)
                seen (store/agent-seen-message-ids ebdb aid)
                wire (model/build-agent-wire-messages
                      {:thinking? true :replay-reasoning? true :vision? vis?}
                      {:agent-id aid :branch br :role (:seon.agent/role ag :worker)
                       :goal (:seon.agent/goal ag) :system-prompt (:seon.agent/system-prompt ag)
                       :examples [] :walk-text wtxt :tail-blocks []
                       :history (rdata/evals->turns evs (into {} (map (juxt :seon.agent.message/id identity)) msgs))
                       :unread (filterv #(not (seen (:seon.agent.message/id %))) msgs)
                       :eval-db ebdb :main-db emdb :fallback-db ebdb
                       :viewport-screenshot (:viewport-screenshot o)})]
            (assoc wire :tiles tiles :rule-trace ctx-trace
                   :fit-trace {:tokens (:tokens wf) :budget (:budget wf) :strategy (:strategy wf) :items tiles}
                   :surface-stats {:selected (count tiles) :dropped (count (:dropped wf))
                                   :unchanged (count (filter #(= :unchanged (:status %)) tiles))
                                   :tokens (:tokens wf) :budget (:budget wf)})))]
    (if ck (store/lru-get! surface-dbg-wire-cache 8 ck build*) (build*))))

(defn render-debug-ladder
  "Renders the Upgrade Ladder selection table wrapped inside `.table-scroll`."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]]] :any]
   :cost/ticks 500000}
  [items]
  (when (seq items)
    [:div {:class "table-scroll"}
     [:table {:class "data-table"}
      [:thead [:tr [:th "Entity"] [:th "Score"] [:th "Chosen Renderer"] [:th "Status"] [:th "Cost"] [:th "Levels"]]]
      [:tbody
       (for [it (take 14 items)]
         [:tr (mt (str (or (:schema it) (:entity it)))) (mt (str (:rank/score it "—")))
          (mt (str (:chosen-fn it))) [:td (badge "blue" (name (or (:status it) :new)))]
          (mtm (str (:tokens it 0) " tok · " (:min-width it 0) "px")) (mtm (str (count (:candidates it))))])]]]))

(defn render-debug-body
  "Renders the `:debug` surface body (wire request, ladder, signals, rule trace, dual versions, ctx, walk, branches, schemas)."
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [{:keys [agent viewing-id branch main-db branch-db signals signals-fit
           entities wire ctx-trace open-errors branch-feedback walk-summary conn slot-budget]}]
  (let [msgs (:messages wire)
        mtoks (:message-tokens wire)
        rt (or (:rule-trace wire) ctx-trace)
        runs (filterv #(pos? (long (or (:fired %) 0))) (or (:runs rt) (:fired-rules rt) []))
        ss (:surface-stats wire)
        tiles (take 8 (:tiles wire))
        brs (when conn (vec (take 16 (sort (d/branches conn)))))
        stack? (< (long (:avail-w slot-budget 1000)) 720)
        grid-c (if stack? "grid-2 is-stack" "grid-2")
        pair-c (if stack? "debug-tile-pair is-stack" "debug-tile-pair")]
    [:div {:class "stack"}
     [:div {:id "dbg-feedback-unit"} (feedback-banner branch-feedback)]
     (card "dbg-wire-unit" (str "Exact Wire Request for " viewing-id " (branch " branch ")")
           [(badge "blue mono" (str "~" (:total-tokens wire 0) " tok"))
            (when (:prefix-hash wire) (badge "mono" (str "prefix " (:prefix-hash wire))))
            (badge "green mono" (str (count msgs) " msgs"))
            (badge "purple mono" (str (:unchanged ss 0) "/" (:selected ss 0) " cached"))]
           [:div {:class "wire-list"}
            (for [[idx [m tok]] (map-indexed vector (take 10 (map vector msgs mtoks)))
                  :let [body (if (string? (:content m)) (:content m) "[multimodal]")
                        l1 (str (first (remove str/blank? (str/split-lines body))))]]
              [:details {:id (str "wm-" idx) :class "wire-row" :open (< idx 2)}
               [:summary {:class "wire-summary"}
                (mut (str "#" idx)) (badge "blue" (str/upper-case (str (:role m))))
                [:span {:class "muted tiny truncate flex-1"} (trunc l1 100)]
                (mut (str "~" tok " tok"))]
               (code-pre (trunc body 1400))])]
           (render-debug-ladder (or (get-in wire [:fit-trace :items]) entities)))
     [:section {:id "dbg-health-unit" :class "card"}
      (:hiccup signals-fit)
      (when (seq open-errors)
        [:div {:class "stack-xs"}
         (kicker (str "Open Errors (" (count open-errors) ")"))
         (for [e open-errors]
           [:div {:class "notice red tiny mono"} (str (:seon.error/kind e) " · " (:seon.error/message e))])])]
     [:div {:id "dbg-rules-unit"}
      (card "dbg-rule-trace" "Rule Firing Trace (seon.rule)"
            [(badge "purple mono" (str (or (:rules-count rt) (:rules-total rt) 0) " rules"))
             (badge "green mono" (str (count runs) " fired"))
             (badge "mono" (str (:ms rt 0.0) " ms"))]
            (if (empty? runs)
              [:p {:class "muted sm-copy"} "No rules fired."]
              [:div {:class "table-scroll"}
               [:table {:class "data-table"}
                [:thead [:tr [:th "Rule"] [:th "Stratum"] [:th "Fired"] [:th "Reads"] [:th "ms"]]]
                [:tbody (for [r (take 16 runs)]
                          [:tr (mt (str (:rule-id r))) (mt (str (:stratum r)))
                           (mt (str (:fired r))) (mtm (str (:reads-count r 0))) (mtm (str (:ms r 0.0) " ms"))])]]]))]
     (card "dbg-versions-unit" (str "Rendered Versions — Human HTML vs Model Text (" (count tiles) ")")
           [(badge "purple mono" "1 Hiccup → 2 targets")]
           [:div {:class "stack-sm"}
            (for [[idx t] (map-indexed vector tiles)]
              [:div {:id (str "dbg-tile-" idx) :class pair-c}
               [:div {:class "debug-tile-col"} (kicker "Human HTML (Browser)")
                (or (:hiccup t) (mut (str (:schema t))))]
               [:div {:class "debug-tile-col"} (kicker "Model Text (render/hiccup->text)")
                (code-pre (trunc (str (:text t)) 1000))]])])
     [:div {:id "dbg-ctx-unit" :class grid-c}
      (card nil (str "Live @seon.ctx/ctx Atom Value (" viewing-id ")") [(badge "mono" (str "tx " (:max-tx branch-db)))]
            (code-pre (pr-str (select-keys agent [:seon.agent/id :seon.agent/branch :seon.agent/role :seon.agent/scope :seon.agent/task :seon.agent/pinned]))))
      (card nil "datahike.reads Incremental Walk" [(mut (str (count walk-summary) " units"))]
            [:div {:class "pill-row"} (for [{:keys [id reads-count]} walk-summary] (pill (str "#" id ":" (or reads-count 0))))])]
     [:div {:class grid-c}
      (card "dbg-branches-unit" (str "Branches (" (count brs) ")") nil [:div {:class "pill-row"} (map pill (map str brs))])
      (card "dbg-tests-unit" "Proof Signal" nil [:p {:class "mono tiny"} (get-in signals [:proof :summary] "—")])]
     (card "dbg-schema-unit" "Schemas" nil (mut (str (count (db-schema main-db)) " Datahike attributes")))
     [:details {:id "dbg-drill-unit" :class "card fold"}
      [:summary {:class "muted tiny mono"} "Diagnostics & Crash-Recovery Drill"]
      [:button {:type "button" :class "btn sm danger" :on-click '(seon.web/power-loss-reboot!)} "Cold Reboot Drill"]]]))
