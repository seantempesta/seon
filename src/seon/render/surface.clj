(ns seon.render.surface
  "ONE unified rule-projected surface pipeline for Holistic `:session` (`:canvas`),
   `:context` (`:debug`), `:system` (`:workspace`), and fractal `:inspect` views.
   Reads viewport geometry, `seon.signals/all-signals`, `seon.ctx/related-updates`,
   and live `seon.vector` 4th `:semantic` scores, then runs the 2-pass Upgrade Ladder."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.ctx.project :as proj]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.markdown :as rmd]
            [seon.render.surface.context-lab :as clab]
            [seon.render.surface.inspector :as sinsp]
            [seon.render.surface.system :as ssys]
            [seon.render.wire :as rwire]
            [seon.rule :as rules]
            [seon.signals :as sig]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge surface-ru-cache (store/lru-cache 16))
(defonce ^:private ^:seon.flow/edge surface-rules-cache (store/lru-cache 16))

(def ^:private enter-submit-js
  "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.isComposing||evt.keyCode===229)return;if(evt.target.value.trim()){evt.target.form.requestSubmit();}}")

(def ^:private ctx-rule-pull
  '[:seon.rule/id :seon.rule/category :seon.rule/stratum :seon.rule/query
    :seon.rule/assert :seon.rule/target :seon.rule/enabled? :seon.rule/doc])

(defn- live-cid [db] (when (and db (instance? datahike.db.DB db)) (d/commit-id db)))
(defn- safe-as-of {:seon.flow/edge true} [db tx]
  (if (and db (integer? tx)) (try (d/as-of db tx) (catch Throwable _ db)) db))
(defn- db-schema [db] (or (:schema db) (when db (d/schema db)) {}))

(defn- pull-ctx-rules [db]
  (let [dh (db-schema db)]
    (when (and db (dh :seon.rule/category) (dh :seon.rule/id))
      (vec (keep #(let [r (d/pull db ctx-rule-pull (:e %))]
                    (when (and (:seon.rule/id r) (not (false? (:seon.rule/enabled? r)))) r))
                 (d/datoms db :avet :seon.rule/category :context))))))

(defn- load-surface-ctx-rules [mdb bdb]
  (let [ms (rules/rules-db-sig mdb)
        bs (if (= mdb bdb) ms (rules/rules-db-sig bdb))
        load* #(let [mr (pull-ctx-rules mdb)
                     br (if (= ms bs) mr (pull-ctx-rules bdb))]
                 (if (seq br)
                   (vec (vals (merge (into {} (map (juxt :seon.rule/id identity)) mr)
                                     (into {} (map (juxt :seon.rule/id identity)) br))))
                   (vec mr)))]
    (if (and ms bs) (store/lru-get! surface-rules-cache 16 [ms bs] load*) (load*))))

(defn- branch-green? [db br same?]
  (let [dh (db-schema db)]
    (boolean (and db (dh :seon.test.run/branch) (dh :seon.test.run/pass?)
                  (when-let [e (or (when br (:e (first (d/datoms db :avet :seon.test.run/branch br))))
                                   (when same? (:e (first (d/datoms db :avet :seon.test.run/branch :main)))))]
                    (true? (:v (first (d/datoms db :eavt e :seon.test.run/pass?)))))))))

(defn- open-errors? [db]
  (boolean (and db ((db-schema db) :seon.error/status)
                (first (d/datoms db :avet :seon.error/status :open)))))

(defn- enrich-entity-params [db ent]
  (let [dh (db-schema db)
        src (when-let [fk (and (dh :seon.code.form/key) (:seon.code.var/form ent))]
              (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key fk])))
        fix (when-let [ek (and (dh :seon.fix/error-class) (:seon.error/kind ent))]
              (when-let [fe (:e (first (d/datoms db :avet :seon.fix/error-class ek)))]
                (d/pull db '[*] fe)))]
    (cond-> {}
      (and (string? src) (not (str/blank? src))) (assoc :seon.code.form/src src)
      (and (map? fix) (seq (dissoc fix :db/id))) (assoc :seon.fix/entity fix))))

(defn- cached-related-updates [mdb bdb aid ag target br]
  (let [mc (live-cid mdb) bc (live-cid bdb)
        all-rs (load-surface-ctx-rules mdb bdb)
        same? (and mc bc (= mc bc))
        errs? (or (open-errors? mdb) (and (not= mdb bdb) (open-errors? bdb)))
        evs? (boolean (and ((db-schema bdb) :seon.agent.eval/agent)
                           (first (d/datoms bdb :avet :seon.agent.eval/agent aid))))
        skip (cond-> #{}
               (branch-green? bdb br same?) (conj :rule.ctx/failing-reach-tests)
               (not errs?) (conj :rule.ctx/fault-with-recommended-fix)
               same? (conj :rule.ctx/edited-var-on-branch :rule.ctx/edited-var-callers)
               (and (nil? (:seon.agent/task ag)) (empty? (:seon.agent/tasks ag))) (conj :rule.ctx/active-task-and-lineage)
               (empty? (:seon.agent/pinned ag)) (conj :rule.ctx/pinned-entities)
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
        pins (:pinned world)
        aeid (:agent-eid world)
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
                          ent (if (seq (dissoc b :db/id)) b (d/pull mdb '[*] eid))
                          ep (when (map? ent) (enrich-entity-params bdb ent))]
                      (when (and (map? ent) (seq (dissoc ent :db/id)))
                        (cond-> (assoc item :data ent) (seq ep) (update :params merge ep))))))
                raw))
     :ctx-trace (:seon.ctx/trace ru)}))

(defn- extract-conversation [emdb ebdb aid ag active? as-of]
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
        ;; NEVER show fake "IN FLIGHT" when agent already ran evals and is idle/ready/merged!
        pending (when (and (nil? as-of)
                           (or active?
                               (and latest-prompt (empty? ex-evals) (not done-ag?))))
                  (or latest-prompt "Working…"))
        latest-p (ex-prose latest)
        has-work? (and (nil? pending) (empty? latest-p) (seq ex-evals))
        last-intent (some #(not-empty (str/trim (str (or (:seon.agent.eval/intent %) "")))) (reverse ex-evals))
        work-sum (when has-work?
                   {:prompt latest-prompt
                    :eval-count (count ex-evals)
                    :latest-intent (or last-intent (str (count ex-evals) " REPL step(s) completed"))
                    :status-label (cond (pos? (long (or (:seon.agent/merged-tx ag) 0))) "Merged"
                                        (:seon.agent/ready? ag) "Ready"
                                        (:seon.agent/paused? ag) "Paused"
                                        :else "Completed")})
        sidx (last (keep-indexed #(when (seq (ex-prose %2)) %1) exs))
        disp (cond pending latest has-work? latest (some? sidx) (nth exs sidx) :else latest)]
    {:evals evals :msgs msgs :exchanges exs :pending-prompt pending :work-summary work-sum
     :latest-prose (if (or pending has-work?) [] (ex-prose disp))
     :reply-prompt (some-> (last (filter rmd/prompt-msg? disp)) :data :seon.agent.message/body)
     :latest-actions (filterv #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                   (not (str/blank? (str (:seon.agent.eval/reasoning %)))))
                              (map :data (filter #(= :eval (:kind %)) disp)))}))

(defn- build-mode-payload [canon inspecting? emdb ebdb aid ag vm br gather-fn fopts lp o]
  (cond
    (or inspecting? (= canon :system))
    {:wire nil :fitted {:selected [] :tokens 0 :budget 16000} :ctx-trace nil}

    (= canon :context)
    (let [w (clab/build-context-lab-wire emdb ebdb aid ag br gather-fn (assoc o :lab-prefs lp))]
      {:wire w :ctx-trace (:rule-trace w)
       :fitted {:selected (or (get-in w [:fit-trace :items]) [])
                :tokens (get-in w [:fit-trace :tokens] 0)
                :budget (get-in w [:fit-trace :budget] 16000)}})

    :else
    (let [{:keys [candidates ctx-trace]} (gather-fn)
          with-sem (clab/attach-semantic-scores emdb ebdb ag candidates)]
      {:wire (when (:include-wire? o)
               (rwire/build-live-wire-request
                emdb ebdb aid
                (select-keys o [:conn :viewport-screenshot :vision? :already-shown :token-budget :strategy])))
       :fitted (fit/fit-projections ebdb with-sem fopts)
       :ctx-trace ctx-trace})))

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
         inspecting? (boolean (or insp-ref (= vm :inspect)))
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
         sigs (sig/all-signals emdb)
         lp (or (:lab-prefs o) (:lab-prefs vs) {})
         fopts (merge {:target target :viewport-w vw :viewport-h vh :sidebar-open? sb? :slot slot
                       :avail-w (:avail-w slot-b) :area-budget (:area-budget slot-b)
                       :fallback-renderers rwire/fallback-entity-renderers}
                      (select-keys o [:token-budget :already-shown :strategy]))
         sfit (first (:selected (fit/fit-projections emdb [{:entity :signals/live :schema :seon.signal/snapshot :data sigs :priority 100}] fopts)))
         gather-fn #(gather-surface-candidates emdb ebdb aid ag vm br)
         {:keys [wire fitted ctx-trace]} (build-mode-payload canon inspecting? emdb ebdb aid ag vm br gather-fn fopts lp o)
         peids (vec (keep #(when (integer? (:entity %)) (:entity %)) (:selected fitted)))
         htxs (if (or inspecting? (= canon :system)) [] (or (rdata/view-history-txs hbdb main-db aid peids) []))
         sidx (when (seq htxs)
                (if as-of (or (first (keep-indexed #(when (= %2 as-of) %1) htxs))
                              (last (keep-indexed #(when (<= %2 as-of) %1) htxs)) 0)
                    (dec (count htxs))))
         active? (or (= :active (get (:agent-states o) aid)) (map? (:live-stream o)))
         conv (if (and (= canon :session) (not inspecting?)) (extract-conversation emdb ebdb aid ag active? as-of) {})
         errs (when (and (= canon :context) ((db-schema emdb) :seon.error/status))
                (mapv #(d/pull emdb '[:seon.error/id :seon.error/kind :seon.error/severity :seon.error/message] (:e %))
                      (take 8 (d/datoms emdb :avet :seon.error/status :open))))]
     (merge conv
            {:agent ag :viewing-id aid :branch br :view-mode vm :canon-mode canon :inspect-ref insp-ref :inspecting? inspecting?
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

(defn- btn [cls oc lbl] [:button {:type "button" :class cls :on-click oc} lbl])
(defn- badge [cls txt] [:span {:class (str "badge " cls)} txt])
(defn- kicker [txt] [:span {:class "kicker"} txt])
(defn- el [ref lbl cls]
  (if (and (vector? ref) (= 2 (count ref)) (qualified-keyword? (first ref)) (some? (second ref)))
    [:button {:type "button" :class (str "ent-link" (when cls (str " " cls)))
              :on-click (list 'seon.web/inspect-entity! ref)} (str lbl)]
    [:span {:class (or cls "")} (str lbl)]))

(defn render-surface-timeline
  "Generic Datahike `d/as-of` time-travel bar shared across views."
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
           (kicker (str "Snapshot · tx " as-of-tx)))
         [:div {:class "tl-meta"}
          [:span (cond (and past? (not in-h?)) (str "Time-travel · tx " as-of-tx)
                       past? (str "Time-travel · " (inc idx) " / " n)
                       :else (str "History · " (inc idx) " / " n))]
          (when past? (btn "step-live" '(seon.web/jump-canvas-live!) "Live"))]]))))

(defn- feedback-banner [fb]
  (when fb
    [:div {:class (str "notice row-between " (if (:ok? fb) "green" "red"))}
     [:span (:text fb)]
     (btn "link-muted tiny" '(seon.web/dismiss-feedback!) "×")]))

(defn- composer-form [cls action field-name placeholder]
  [:form {:class cls :on-submit action}
   [:div {:class "composer-shell"}
    [:textarea {:class "composer-inp" :name field-name :rows 1 :data-on:keydown enter-submit-js :placeholder placeholder}]
    [:button {:type "submit" :class "send-icon-btn" :aria-label "Send"} rmd/svg-arrow-up]]])

(defn- entity-vt-slug [ent]
  (let [s (-> (str ent) (str/replace #"[^A-Za-z0-9_-]+" "-") (str/replace #"^-+|-+$" ""))]
    (if (str/blank? s) "x" (subs s 0 (min 36 (count s))))))

(def ^:private group-tasks-edits #{:seon.task/entity :seon.code.var/entity :seon.code.ns/entity :seon.code.doc/entity})
(def ^:private group-alerts-proofs #{:seon.error/entity :seon.fix/entity :seon.test/verdict :seon.agent.eval/entity})

(defn- entity-group-key [it]
  (let [sk (or (first (:schemas it)) (:schema it))]
    (cond (group-tasks-edits sk) :tasks-edits
          (group-alerts-proofs sk) :alerts-proofs
          :else :pinned-rules)))

(defn- render-grouped-entities [entities avail-w]
  (when (seq entities)
    (let [by-g (group-by entity-group-key entities)
          counts (volatile! {})
          mk-item (fn [item]
                    (let [slug (entity-vt-slug (:entity item))
                          occ (long (get @counts slug 0))
                          _ (vswap! counts assoc slug (inc occ))
                          vt (str "ent-" occ "-" slug)
                          ref (or (rdata/entity-lookup-ref (:data item))
                                  (when (integer? (:entity item)) [:db/id (:entity item)]))
                          sem (:semantic item)]
                      [:div {:class "surface-entity-item" :style (str "view-transition-name:" vt)
                             :data-entity (str (:entity item)) :data-chosen-fn (str (:chosen-fn item))
                             :data-min-width (str (:min-width item 0))}
                       [:div {:class "ent-prov-bar"}
                        [:div {:class "pill-row"}
                         [:span {:class "mono tiny muted"}
                          (str (some-> (:chosen-fn item) name) " · " (:tokens item 0) " tok")]
                         (when (number? sem)
                           (badge "purple" (str "cos " (/ (Math/round (* (double sem) 100.0)) 100.0))))]
                        (when ref
                          [:button {:type "button" :class "ent-link tiny mono"
                                    :on-click (list 'seon.web/inspect-entity! ref)} "Inspect variations →"])]
                       (or (:hiccup item) [:div {:class "ent-row mono tiny"} (str (:entity item))])]))]
      [:div {:class (if (< (long avail-w) 600) "surface-entities stack-sm" "surface-entities stack")
             :data-entity-count (str (count entities))}
       (for [[gk title] [[:tasks-edits "Tasks & Edits"] [:alerts-proofs "Alerts & Proofs"] [:pinned-rules "Pinned & Rules"]]
             :let [xs (get by-g gk)]
             :when (seq xs)]
         [:section {:class "ctx-group stack-sm"}
          [:div {:class "ctx-group-head"}
           (kicker title)
           [:span {:class "mono tiny muted"} (str (count xs))]]
          (mapv mk-item xs)])])))

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
  (cond active? (badge "amber" "live")
        (pos? (long (or (:seon.agent/merged-tx ag) 0))) (badge "green" "merged")
        (:seon.agent/ready? ag) (badge "green" "ready")
        (:seon.agent/paused? ag) (badge "muted" "paused")
        (:seon.agent/waiting? ag) (badge "blue" "waiting")
        :else (badge "muted" "idle")))

(defn- render-session-body [{:keys [agent viewing-id branch branch-db entities exchanges
                                    pending-prompt latest-prose reply-prompt latest-actions work-summary
                                    live-stream branch-feedback slot-budget active?]} include-composer?]
  (let [label (rdata/agent-label agent viewing-id)
        prose (when (seq latest-prose) (str/join "\n\n" latest-prose))
        earlier (when (> (count exchanges) 1) (pop exchanges))
        scope (:seon.agent/scope agent)
        task (:seon.agent/task agent)]
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
         (when scope (list "·" (el [:seon.code.ns/name scope] (str scope) "mono tiny")))
         (when task (list "·" (el [:seon.task/id task] (str task) "mono tiny")))]]
       [:div {:class "pill-row"}
        (btn "btn sm" (list 'seon.web/run-tests! branch) "Test")
        (when (not= branch :main)
          (btn "btn sm success" (list 'seon.web/merge-branch! branch) "Merge → :main"))
        (btn "nav-pill tiny" '(seon.web/set-view-mode! :context) "Context & Cache →")]]
      (feedback-banner branch-feedback)
      [:div {:class "ws-timeline" :data-agent (str viewing-id)}
       (when (seq earlier)
         [:details {:id "session-earlier" :class "activity-fold"}
          [:summary {:class "mono tiny"} (str "Earlier exchanges (" (count earlier) ")")]
          [:div {:class "stack-sm" :style {:margin-top "8px"}}
           (render-exchanges-list "hx" earlier)]])
       (cond
         pending-prompt
         (let [{:keys [css-class rate reasoning text] phase-label :label} (canvas-live-progress live-stream)]
           [:section {:id "cv-pending" :class (str "ch-pending-card canvas-pending " css-class)}
            [:div {:class "root-kicker"} (str "In flight · " label)]
            [:h1 {:class "canvas-prompt-headline"} pending-prompt]
            [:div {:class "canvas-pending-status"}
             [:span {:class "dot pulsing"}]
             [:span {:class (str "stream-phase-badge " css-class)} phase-label]
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
           [:div {:class "pill-row"}
            (badge "green" (:status-label work-summary))
            [:strong {:class "sm-copy"} (:latest-intent work-summary)]]
           [:span {:class "mono tiny muted"} (str (:eval-count work-summary) " eval(s)")]]
          (when-let [pr (:prompt work-summary)] [:div {:class "canvas-re-prompt"} (str "Re: " pr)])
          (when (seq latest-actions) (rmd/render-activity-fold latest-actions "cv-work"))]

         :else
         [:header {:class "root-hero"}
          [:div {:class "root-kicker"} (str label " · " branch)]
          [:h1 {:class "root-title"} (or (not-empty (:seon.agent/hero-title agent)) label)]
          (when-let [g (not-empty (:seon.agent/goal agent))] [:p {:class "root-sub"} g])])]
      (when include-composer?
        (composer-form "ws-composer" (list 'seon.web/chat-agent-form! viewing-id) "text"
                       (str "Message " label " or (eval Clojure)…")))]
     [:section {:class "ws-right"}
      [:div {:id "ws-right-head-unit"}
       [:div {:class "ws-head"}
        [:h2 (str "Active Context (" (count entities) ")")]
        (btn "nav-pill tiny" '(seon.web/set-view-mode! :context) "Context & Cache →")]
       [:form {:class "row-form" :on-submit (list 'seon.web/pin-ref-form! viewing-id)}
        [:input {:class "inp sm mono flex-1" :name "ref" :placeholder "Pin ref, e.g. [:seon.code.ns/name 'seon.web]"}]
        [:button {:type "submit" :class "btn sm"} "+ Pin"]]]
      [:div {:class "tiles-scroll"}
       (or (render-grouped-entities entities (:avail-w slot-budget))
           [:div {:id "ws-tiles-empty" :class "card muted sm-copy"} "No active context entities yet."])]])))

(defn render-surface
  "ONE unified Hiccup surface renderer for `:session` (`:canvas`), `:context` (`:debug`),
   `:system` (`:workspace`), and fractal `:inspect` views."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :map]] [:vector :any]]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] [:maybe :map]] [:vector :any]]]
   :cost/ticks 45000000}
  ([main-db opts] (render-surface main-db nil opts))
  ([main-db branch-db opts]
   (let [o (if (map? opts) opts {})
         model (build-surface-model main-db branch-db o)
         {:keys [viewing-id view-mode canon-mode inspecting? inspect-ref viewport slot-budget
                 history-txs step-idx as-of-tx]} model
         include-composer? (if (contains? o :include-composer?) (boolean (:include-composer? o)) true)
         tl (when-not inspecting? (render-surface-timeline history-txs step-idx as-of-tx))
         cls (cond
               inspecting? "debug-wrap"
               (= canon-mode :session) (if (>= (long (:avail-w slot-budget 1000)) 860) "workspace-grid" "workspace-grid surface-stack")
               :else "debug-wrap")
         body (cond
                inspecting? (sinsp/render-inspector-body model)
                (= canon-mode :context) (clab/render-context-lab-body model)
                (= canon-mode :system) (ssys/render-system-body model)
                :else (render-session-body model include-composer?))]
     [:div (cond-> {:id "seon-view-unit" :class cls
                    :data-agent (str viewing-id) :data-view-mode (name view-mode)
                    :data-viewport-w (str (:w viewport)) :data-slot-avail-w (str (:avail-w slot-budget))}
             inspect-ref (assoc :data-inspect-ref (pr-str inspect-ref)))
      tl body])))
