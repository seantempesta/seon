(ns seon.render.surface
  "ONE unified rule-projected surface pipeline for `:session`, `:context` (Explain),
   `:system` (`[:seon.system/id :root]`), and `:inspect` (Entity Full View) — powered by
   ONE `seon.ctx/gather-agent-candidates`, `project-entity-view`, and `seon.render.wire/build-live-wire-request`."
  (:require [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks :refer [db-schema kicker safe-as-of]]
            [seon.render.markdown :as rmd]
            [seon.render.surface.context-lab :as clab]
            [seon.render.surface.entity :as sentity]
            [seon.render.surface.inspector :as sinsp]
            [seon.render.surface.session :as ssess]
            [seon.render.surface.system :as ssys]
            [seon.render.wire :as rwire]
            [seon.signals :as sig]
            [seon.render.status :as rstatus]
            [seon.store :as store]))

(defn project-entity-view
  "ONE general entity-surface engine for BOTH humans (`:target :html`) and agents (`:target :wire`).
   Works identically for `[:seon.agent/id aid]`, `[:seon.system/id :root]`, and ANY entity lookup ref."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any [:maybe :map]] :map]]
   :cost/ticks 45000000}
  ([main-db branch-db focus-ref] (sentity/project-entity-view main-db branch-db focus-ref nil))
  ([main-db branch-db focus-ref opts] (sentity/project-entity-view main-db branch-db focus-ref opts)))

(defn canvas-live-progress
  "Delegates to `seon.render.surface.session/canvas-live-progress`."
  {:malli/schema [:=> [:cat [:maybe :map]]
                  [:map [:phase :keyword] [:label :string] [:css-class :string]
                   [:rate [:maybe :string]] [:reasoning [:maybe :string]] [:text [:maybe :string]]]]
   :cost/ticks 200000 :cost/bytes 32768}
  [live-stream]
  (ssess/canvas-live-progress live-stream))

(defn- build-mode-payload [canon explaining? inspecting? insp-ref emdb ebdb aid ag br fopts lp o]
  (cond
    (and explaining? insp-ref (not= :seon.agent/id (first insp-ref)))
    {:wire nil :fitted {:selected [] :tokens 0 :budget 16000} :ctx-trace nil :pool-count 0}

    explaining?
    (let [w (clab/build-context-lab-model emdb ebdb aid ag br (assoc o :lab-prefs lp))]
      {:wire w :ctx-trace (:rule-trace w) :pool-count (count (get-in w [:explained :items]))
       :fitted {:selected (or (get-in w [:fit-trace :items]) [])
                :tokens (get-in w [:fit-trace :tokens] 0)
                :budget (get-in w [:fit-trace :budget] 16000)}})

    (or inspecting? (= canon :system))
    {:wire nil :fitted {:selected [] :tokens 0 :budget 16000} :ctx-trace nil :pool-count 0}

    :else
    (let [t (case canon :context :debug :system :workspace :canvas)
          gather-fn #(ctx/gather-agent-candidates emdb ebdb aid {:agent ag :target t :semantic? true :enrich? true :rich-ns? false :situational? false})
          proj (sentity/project-entity-view
                emdb ebdb [:seon.agent/id aid]
                (assoc fopts :agent-gather-fn gather-fn :agent ag :max-items (long (or (:max-items o) 8))))]
      {:wire (when (:include-wire? o)
               (rwire/build-live-wire-request
                emdb ebdb aid
                (select-keys o [:conn :viewport-screenshot :vision? :already-shown :token-budget :strategy])))
       :fitted (:fitted proj)
       :pool-count (:pool-count proj 0)
       :ctx-trace (:ctx-trace proj)})))

(defn windowed-history-indices
  "Selects up to 14 bounded step indices for the interactive `d/as-of` timeline scrubber."
  {:malli/schema [:=> [:cat :int :int] [:vector :int]] :cost/ticks 100000}
  [n cur-idx]
  (let [n (max 0 (long n)) ci (max 0 (min (max 0 (dec n)) (long cur-idx)))]
    (cond (zero? n) [] (<= n 14) (vec (range n))
          :else (vec (distinct (concat [0] (range (max 1 (- ci 5)) (inc (min (- n 2) (+ ci 5)))) [(dec n)]))))))

(defn render-surface-timeline
  "Generic Datahike `d/as-of` time-travel bar shared across views."
  {:malli/schema [:=> [:cat [:maybe [:sequential :int]] [:maybe :int] [:maybe :int]] :any] :cost/ticks 500000}
  [hist-txs cur-idx as-of-tx]
  (let [txs (vec (filter integer? (or hist-txs []))) n (count txs)
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
           (kicker (str "Snapshot · tx " as-of-tx)))
         [:div {:class "tl-meta"}
          [:span (cond (and past? (not in-h?)) (str "Time-travel · tx " as-of-tx)
                       past? (str "Time-travel · " (inc idx) " / " n) :else (str "History · " (inc idx) " / " n))]
          (when past? [:button {:type "button" :class "step-live" :on-click '(seon.web/jump-canvas-live!)} "Live"])]]))))

(defonce ^:private ^:seon.flow/edge signals-fit-cache (store/lru-cache 32))

(defn build-surface-model
  "Pure unified surface model builder over `main-db`, `branch-db`, and `opts`."
  {:malli/schema [:function [:=> [:cat :seon.host/db [:maybe :map]] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] [:maybe :map]] :map]]
   :cost/ticks 45000000}
  ([main-db opts] (build-surface-model main-db nil opts))
  ([main-db branch-db opts]
   (let [t0 (System/nanoTime)
         o (if (map? opts) opts {})
         vs (rdata/user-view-state main-db)
         aid (or (:viewing-id o) (:viewing-id vs) (:entry-id vs) :agent/root)
         vm (or (:view-mode o) (:view-mode vs) :session)
         canon (rdata/canonical-view-mode vm)
         insp-ref (if (contains? o :inspect-ref) (rdata/normalize-inspect-ref (:inspect-ref o)) (:inspect-ref vs))
         explaining? (and (= canon :context)
                          (or (nil? insp-ref)
                              (= :context (rdata/canonical-view-mode (:view-mode o)))
                              (not (contains? o :inspect-ref))))
         inspecting? (boolean (and (not explaining?) (or insp-ref (= vm :inspect))))
         vw (long (or (:viewport-w o) (:viewport-w vs) 1280))
         vh (long (or (:viewport-h o) (:viewport-h vs) 820))
         sb? (and (if (boolean? (:sidebar-open? o)) (:sidebar-open? o) (boolean (:sidebar-open? vs))) (>= vw 760))
         as-of (if (contains? o :as-of-tx) (:as-of-tx o) (:as-of-tx vs))
         slot (or (:slot o) (if (= :wire (:target o)) :wire :main))
         target (if (= :wire (:target o)) :wire :html)
         slot-b (fit/resolve-slot-budget {:viewport-w vw :viewport-h vh :sidebar-open? sb? :slot slot})
         br0 (or (:branch o)
                 (when (and (nil? branch-db) aid ((db-schema main-db) :seon.agent/id))
                   (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])))
                 :main)
         hbdb (or branch-db (rdata/branch-db-for main-db (:conn o) br0))
         emdb (if as-of (safe-as-of main-db as-of) main-db)
         ebdb (if as-of (if (identical? hbdb main-db) emdb (safe-as-of hbdb as-of)) hbdb)
         ag (rdata/pull-agent-entity emdb ebdb aid)
         br (or (:branch o) (:seon.agent/branch ag) br0)
         sigs (when (or (not inspecting?) (= :seon.system/id (first insp-ref))) (sig/all-signals emdb))
         lp (or (:lab-prefs o) (:lab-prefs vs) {})
         fopts (merge {:target target :viewport-w vw :viewport-h vh :sidebar-open? sb? :slot slot
                       :avail-w (:avail-w slot-b) :area-budget (:area-budget slot-b) :main-db emdb
                       :fallback-renderers rwire/fallback-entity-renderers}
                      (select-keys o [:token-budget :already-shown :strategy]))
         sfit (when (and sigs (not= canon :system) (not inspecting?))
              (store/lru-get! signals-fit-cache 32
                              [(get-in emdb [:config :store :id]) (dissoc sigs :ms :tx)
                               (:avail-w slot-b) (:area-budget slot-b) target
                               (:strategy fopts) (:token-budget fopts)]
                              #(first (:selected (fit/fit-projections emdb [{:entity :signals/live :schema :seon.signal/snapshot :data sigs :priority 100}] fopts)))))
         {:keys [wire fitted ctx-trace pool-count]} (build-mode-payload canon explaining? inspecting? insp-ref emdb ebdb aid ag br fopts lp o)
         peids (vec (keep #(when (integer? (:entity %)) (:entity %)) (:selected fitted)))
         htxs (if (or inspecting? (= canon :system) (and explaining? insp-ref)) [] (or (rdata/view-history-txs hbdb main-db aid peids) []))
         sidx (when (seq htxs)
                (if as-of (or (first (keep-indexed #(when (= %2 as-of) %1) htxs))
                              (last (keep-indexed #(when (<= %2 as-of) %1) htxs)) 0)
                    (dec (count htxs))))
         active? (or (= :active (get (:agent-states o) aid)) (map? (:live-stream o)))
         expert-mode? (if (contains? o :expert-mode?)
         (boolean (:expert-mode? o))
         (boolean (when (and ((db-schema main-db) :seon.user/expert-mode?)
                             (first (d/datoms main-db :aevt :seon.user/expert-mode?))
                             (first (d/datoms main-db :avet :seon.user/id :user/default)))
                    (:seon.user/expert-mode?
                     (d/pull main-db '[:seon.user/expert-mode?] [:seon.user/id :user/default])))))
         agent-status (when (and (= canon :session) (not inspecting?) (not explaining?))
                        (rstatus/latest-agent-status emdb ebdb aid))
         conv (if (and (= canon :session) (not inspecting?) (not explaining?)) (ssess/extract-conversation emdb ebdb aid ag active? as-of) {})
         errs (when (and explaining? (or (nil? insp-ref) (= :seon.agent/id (first insp-ref))) ((db-schema emdb) :seon.error/status))
                (mapv #(d/pull emdb '[:seon.error/id :seon.error/kind :seon.error/severity :seon.error/message
                                      :seon.error/agent :seon.error/var :seon.error/branch] (:e %))
                      (take 8 (d/datoms emdb :avet :seon.error/status :open))))]
     (merge conv
            {:agent ag :viewing-id aid :branch br :view-mode vm :canon-mode canon :explaining? explaining?
             :inspect-ref insp-ref :inspecting? inspecting? :pool-count (long (or pool-count 0))
             :inspect-trail (or (:inspect-trail o) (:inspect-trail vs) [])
             :inspect-forward (or (:inspect-forward o) (:inspect-forward vs) []) :lab-prefs lp
             :viewport {:w vw :h vh :sidebar-open? sb?} :slot-budget slot-b :signals sigs :signals-fit sfit
             :fitted fitted :entities (:selected fitted) :history-txs htxs :step-idx sidx :as-of-tx as-of
             :ctx-trace ctx-trace :wire wire :open-errors errs :main-db emdb :branch-db ebdb :active? active?
             :expert-mode? expert-mode? :agent-status agent-status
             :agent-states (:agent-states o) :live-stream (:live-stream o) :branch-feedback (:branch-feedback o)
             :walk-summary (:walk-summary o) :vector-status (:vector-status o) :semantic-search (:semantic-search o) :conn (:conn o)
             :ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)}))))

(defn render-surface
  "ONE unified Hiccup renderer for `:session`, `:context` (Explain), `:system`, and `:inspect`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :map]] [:vector :any]]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] [:maybe :map]] [:vector :any]]]
   :cost/ticks 45000000}
  ([main-db opts] (render-surface main-db nil opts))
  ([main-db branch-db opts]
   (let [o (if (map? opts) opts {})
         m (build-surface-model main-db branch-db o)
         {:keys [viewing-id view-mode canon-mode explaining? inspecting? inspect-ref viewport slot-budget
                 history-txs step-idx as-of-tx]} m
         comp? (if (contains? o :include-composer?) (boolean (:include-composer? o)) true)
         tl (when (and (not inspecting?) (not= canon-mode :system) (not (and explaining? inspect-ref)))
              (render-surface-timeline history-txs step-idx as-of-tx))
         cls (cond (or explaining? inspecting? (= canon-mode :system)) "debug-wrap"
                   :else (if (>= (long (:avail-w slot-budget 1000)) 860) "workspace-grid" "workspace-grid surface-stack"))
         body (cond explaining? (clab/render-context-lab-body m)
                    (and inspecting? (= :seon.system/id (first inspect-ref))) (sentity/render-system-entity-body m)
                    inspecting? (sinsp/render-inspector-body m)
                    (= canon-mode :system) (ssys/render-system-body m)
                    :else (ssess/render-session-body m comp?))]
     [:div (cond-> {:id "seon-view-unit" :class cls :data-agent (str viewing-id) :data-view-mode (name view-mode) :data-viewport-w (str (:w viewport)) :data-slot-avail-w (str (:avail-w slot-budget)) :data-expert-mode (str (boolean (:expert-mode? m)))}
             inspect-ref (assoc :data-inspect-ref (pr-str inspect-ref)))
      tl body])))