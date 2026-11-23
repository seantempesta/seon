(ns seon.render.surface
  "ONE unified rule-projected surface pipeline for `:session`, `:context` (Explain),
   `:system` (`[:seon.system/id :root]`), and `:inspect` (Entity Full View) — powered by
   `project-entity-view` for both human HTML (`:target :html`) and AI Wire (`:target :wire`)."
  (:require [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.ctx.project :as proj]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks :refer [db-schema kicker]]
            [seon.render.markdown :as rmd]
            [seon.render.surface.context-lab :as clab]
            [seon.render.surface.entity :as sentity]
            [seon.render.surface.inspector :as sinsp]
            [seon.render.surface.session :as ssess]
            [seon.render.surface.system :as ssys]
            [seon.render.wire :as rwire]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.signals :as sig]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge surface-ru-cache (store/lru-cache 16))
(defonce ^:private ^:seon.flow/edge surface-rules-cache (store/lru-cache 16))

(def ^:private ctx-rule-pull
  '[:seon.rule/id :seon.rule/category :seon.rule/stratum :seon.rule/query
    :seon.rule/assert :seon.rule/target :seon.rule/enabled? :seon.rule/doc])

(defn- live-cid [db] (when (and db (instance? datahike.db.DB db)) (d/commit-id db)))
(defn- safe-as-of {:seon.flow/edge true} [db tx]
  (if (and db (integer? tx)) (try (d/as-of db tx) (catch Throwable _ db)) db))

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

(defn- pull-ctx-rules [db]
  (let [dh (db-schema db)]
    (when (and db (dh :seon.rule/category) (dh :seon.rule/id))
      (vec (keep #(let [r (d/pull db ctx-rule-pull (:e %))]
                    (when (and (:seon.rule/id r) (not (false? (:seon.rule/enabled? r)))) r))
                 (d/datoms db :avet :seon.rule/category :context))))))

(defn- load-surface-ctx-rules [mdb bdb]
  (let [ms (rules/rules-db-sig mdb)
        bs (if (= mdb bdb) ms (rules/rules-db-sig bdb))
        load* (fn []
                (let [mr (pull-ctx-rules mdb)
                      br (if (= ms bs) mr (pull-ctx-rules bdb))
                      merged (if (seq br)
                               (vec (vals (merge (into {} (map (juxt :seon.rule/id identity)) mr)
                                                 (into {} (map (juxt :seon.rule/id identity)) br))))
                               (vec mr))]
                  (if (seq merged) merged
                      (filterv #(and (= :context (:seon.rule/category %)) (not (false? (:seon.rule/enabled? %))))
                               (rseed/starter-rules)))))]
    (if (and ms bs) (store/lru-get! surface-rules-cache 16 [ms bs] load*) (load*))))

(defn- branch-green? [db br same?]
  (let [dh (db-schema db)]
    (boolean (and db (dh :seon.test.run/branch) (dh :seon.test.run/pass?)
                  (when-let [e (or (when br (:e (first (d/datoms db :avet :seon.test.run/branch br))))
                                   (when same? (:e (first (d/datoms db :avet :seon.test.run/branch :main)))))]
                    (true? (:v (first (d/datoms db :eavt e :seon.test.run/pass?)))))))))

(defn- open-errors? [db] (boolean (and db ((db-schema db) :seon.error/status) (first (d/datoms db :avet :seon.error/status :open)))))
(defn- any-tests? [db] (boolean (and db ((db-schema db) :seon.test/digest) (first (d/datoms db :aevt :seon.test/digest)))))

(defn- cached-related-updates [mdb bdb aid ag target br]
  (let [mc (live-cid mdb) bc (live-cid bdb)
        all-rs (load-surface-ctx-rules mdb bdb)
        same? (and mc bc (= mc bc))
        errs? (or (open-errors? mdb) (and (not= mdb bdb) (open-errors? bdb)))
        evs? (boolean (and ((db-schema bdb) :seon.agent.eval/agent)
                           (first (d/datoms bdb :avet :seon.agent.eval/agent aid))))
        skip (cond-> #{:rule.ctx/system-overview}
               (or (branch-green? bdb br same?) (not (any-tests? bdb))) (conj :rule.ctx/failing-reach-tests)
               (not errs?) (conj :rule.ctx/fault-with-recommended-fix)
               same? (conj :rule.ctx/edited-var-on-branch :rule.ctx/edited-var-callers)
               (and (nil? (:seon.agent/task ag)) (empty? (:seon.agent/tasks ag))) (conj :rule.ctx/active-task-and-lineage)
               (not evs?) (conj :rule.ctx/recent-eval-results :rule.ctx/eval-touched-schemas :rule.ctx/eval-compatible-fns))
        rs (when (seq all-rs) (filterv #(not (skip (:seon.rule/id %))) all-rs))
        eff-t (if (and rs (every? #(or (nil? (:seon.rule/target %)) (= :any (:seon.rule/target %))) rs)) :any target)
        req (cond-> {:seon.db/main mdb :seon.db/branch bdb :seon.agent/id aid :seon.view/target target}
              (some? rs) (assoc :seon.ctx/rules rs))
        ck (when (and mc bc)
             [(get-in mdb [:config :store :id]) mc (:max-tx mdb) bc (:max-tx bdb) aid eff-t (count rs)])
        run #(let [res (ctx/related-updates req)]
               (if (seq all-rs) (assoc-in res [:seon.ctx/trace :rules-total] (count all-rs)) res))]
    (if ck (store/lru-get! surface-ru-cache 16 ck run) (run))))

(defn- gather-surface-candidates [mdb bdb aid ag vm br]
  (let [t (case (rdata/canonical-view-mode vm) :context :debug :system :workspace :canvas)
        ru (cached-related-updates mdb bdb aid ag t br)
        world (proj/agent-world-datoms mdb bdb aid)
        ru-ps (:seon.ctx/projections ru)
        seen (into #{} (keep :entity) ru-ps)
        pins (:pinned world) aeid (:agent-eid world)
        extra (into [] (comp (remove #(or (= (key %) aeid) (seen (key %))))
                             (take 8)
                             (map (fn [[e tx]]
                                    {:entity e :tx tx :priority (if (pins e) 88 60)
                                     :pinned? (boolean (pins e)) :zone :workspace :rules [:seed/world]})))
                    (sort-by (comp - val) (:connected world)))
        raw (into (mapv #(dissoc % :candidates :data) ru-ps) extra)]
    {:candidates
     (vec (keep (fn [item]
                  (when-let [eid (when (integer? (:entity item)) (:entity item))]
                    (let [b (d/pull bdb '[*] eid)
                          from-b? (boolean (seq (dissoc b :db/id)))
                          ent (if from-b? b (d/pull mdb '[*] eid))
                          ep (when (map? ent) (rlinks/enrich-entity-params (if from-b? bdb mdb) ent false))]
                      (when (and (map? ent) (seq (dissoc ent :db/id)))
                        (cond-> (assoc item :data ent) (seq ep) (update :params merge ep))))))
                raw))
     :ctx-trace (:seon.ctx/trace ru)}))

(defn- build-mode-payload [canon explaining? inspecting? insp-ref emdb ebdb aid ag br gather-fn fopts lp o]
  (cond
    (and explaining? insp-ref (not= :seon.agent/id (first insp-ref)))
    {:wire nil :fitted {:selected [] :tokens 0 :budget 16000} :ctx-trace nil :pool-count 0}

    explaining?
    (let [w (clab/build-context-lab-wire emdb ebdb aid ag br gather-fn (assoc o :lab-prefs lp))]
      {:wire w :ctx-trace (:rule-trace w) :pool-count (count (get-in w [:explained :items]))
       :fitted {:selected (or (get-in w [:fit-trace :items]) [])
                :tokens (get-in w [:fit-trace :tokens] 0)
                :budget (get-in w [:fit-trace :budget] 16000)}})

    (or inspecting? (= canon :system))
    {:wire nil :fitted {:selected [] :tokens 0 :budget 16000} :ctx-trace nil :pool-count 0}

    :else
    (let [proj (sentity/project-entity-view
                emdb ebdb [:seon.agent/id aid]
                (assoc fopts :agent-gather-fn gather-fn :agent ag :max-items (long (or (:max-items o) 8))))]
      {:wire (when (:include-wire? o)
               (rwire/build-live-wire-request
                emdb ebdb aid
                (select-keys o [:conn :viewport-screenshot :vision? :already-shown :token-budget :strategy])))
       :fitted (:fitted proj)
       :pool-count (:pool-count proj 0)
       :ctx-trace (:ctx-trace proj)})))

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
         br (or (:branch o) (when (and aid ((db-schema main-db) :seon.agent/id))
                              (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid]))) :main)
         hbdb (or branch-db (rdata/branch-db-for main-db (:conn o) br))
         emdb (if as-of (safe-as-of main-db as-of) main-db)
         ebdb (if as-of (safe-as-of hbdb as-of) hbdb)
         ag (rdata/pull-agent-entity emdb ebdb aid)
         sigs (when (or (not inspecting?) (= :seon.system/id (first insp-ref))) (sig/all-signals emdb))
         lp (or (:lab-prefs o) (:lab-prefs vs) {})
         fopts (merge {:target target :viewport-w vw :viewport-h vh :sidebar-open? sb? :slot slot
                       :avail-w (:avail-w slot-b) :area-budget (:area-budget slot-b) :main-db emdb
                       :fallback-renderers rwire/fallback-entity-renderers}
                      (select-keys o [:token-budget :already-shown :strategy]))
         sfit (when (and sigs (not= canon :system) (not inspecting?))
                (first (:selected (fit/fit-projections emdb [{:entity :signals/live :schema :seon.signal/snapshot :data sigs :priority 100}] fopts))))
         gather-fn #(gather-surface-candidates emdb ebdb aid ag vm br)
         {:keys [wire fitted ctx-trace pool-count]} (build-mode-payload canon explaining? inspecting? insp-ref emdb ebdb aid ag br gather-fn fopts lp o)
         peids (vec (keep #(when (integer? (:entity %)) (:entity %)) (:selected fitted)))
         htxs (if (or inspecting? (= canon :system) (and explaining? insp-ref)) [] (or (rdata/view-history-txs hbdb main-db aid peids) []))
         sidx (when (seq htxs)
                (if as-of (or (first (keep-indexed #(when (= %2 as-of) %1) htxs))
                              (last (keep-indexed #(when (<= %2 as-of) %1) htxs)) 0)
                    (dec (count htxs))))
         active? (or (= :active (get (:agent-states o) aid)) (map? (:live-stream o)))
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
             :agent-states (:agent-states o) :live-stream (:live-stream o) :branch-feedback (:branch-feedback o)
             :walk-summary (:walk-summary o) :vector-status (:vector-status o) :semantic-search (:semantic-search o) :conn (:conn o)
             :ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)}))))

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
     [:div (cond-> {:id "seon-view-unit" :class cls :data-agent (str viewing-id)
                    :data-view-mode (name view-mode) :data-viewport-w (str (:w viewport))
                    :data-slot-avail-w (str (:avail-w slot-budget))}
             inspect-ref (assoc :data-inspect-ref (pr-str inspect-ref)))
      tl body])))
