(ns seon.render.wire.blocks
  "Extracted child namespace of `seon.render.wire`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.ctx.diff :as diff]
            [seon.model :as model]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.entities]
            [seon.render.entities.infra]
            [seon.render.entities.work]
            [seon.render.fit :as fit]
            [seon.render.links :refer [db-schema live-cid]]
            [seon.render.status :as rstatus]
            [seon.rule :as rules]
            [seon.store :as store]))

(def fallback-entity-renderers
  "Re-exported canonical schema -> default pure Hiccup renderer fallback map."
  fit/fallback-entity-renderers)

(defn- fmt-wire-tile [{:keys [schema source handle layout chosen-fn text]}]
  (str "### [" (name (or source :rule)) (when handle (str " " handle))
       " · " (or schema chosen-fn :entity)
       (when (and layout (not= layout :expanded-card)) (str " · " (name layout))) "]\n" text))

(defn- lean-wire-item [item]
  (if (and (map? item) (map? (:data item)))
    (update item :data dissoc :seon.code.form/src :seon.code.form/form)
    item))

(defn- render-selected-text [walk-db main-db selected explain? ent-by-id]
  (mapv (fn [it]
          (if explain?
            (let [win (some #(when (:chosen? %) %) (:variations it))]
              (assoc it
                     :data (get ent-by-id (:entity it))
                     :hiccup (:hiccup win)
                     :text (if (= :unchanged (:status it)) "" (str (:text win)))))
            (let [txt (cond
                        (= :unchanged (:status it)) ""
                        (some? (:hiccup it)) (render/hiccup->text (:hiccup it))
                        (and (map? (:data it)) (seq (dissoc (:data it) :db/id)))
                        (let [d (:data it) sk (:schema it)
                              val (if sk (with-meta d {:seon/schema sk}) d)
                              vh (render/render-value walk-db val {:mode :agent :main-db main-db})
                              bh (when sk (render/render-schema-breadcrumbs walk-db sk val {:mode :agent :main-db main-db}))]
                          (render/hiccup->text (if bh [:div vh bh] vh)))
                        :else "")]
              (assoc it :text txt))))
        selected))

(defn build-viewport-block
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map :any [:maybe :boolean] [:maybe :boolean]] :any]}
  [main-db ag viewport-screenshot vision? force-vision?]
  (when viewport-screenshot
    (let [cfg? (and main-db ((db-schema main-db) :seon.config/viewport-screenshot?))
          en? (cond force-vision? true
                    cfg? (boolean (store/config-val main-db (:seon.agent/id ag) :seon.config/viewport-screenshot?))
                    :else (boolean vision?))
          dom (when-let [d (:dom viewport-screenshot)]
                (when (seq d) (str "\nDOM: " (pr-str d))))]
      (when en?
        (cond-> {:tag "live-viewport"
                 :attrs {:route (or (:route viewport-screenshot) "/")
                         :size (str (or (:width viewport-screenshot) 1280) "x" (or (:height viewport-screenshot) 820))}
                 :text (str "Current live WKWebView screenshot of what the user sees right now." dom)}
          (and vision? (:base64 viewport-screenshot))
          (assoc :image {:mime (or (:mime viewport-screenshot) "image/jpeg")
                         :base64 (:base64 viewport-screenshot)
                         :raw viewport-screenshot}))))))

(defn build-budget-block
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map :int :any] :any]}
  [main-db branch-db ag cur-turn evals]
  (when (and (seq evals) (not (:seon.agent/interactive? ag)))
    (let [max-tt (long (or (store/config-val main-db (:seon.agent/id ag) :seon.config/max-tool-turns) 32))]
      (when (pos? max-tt)
        (let [rem-t (max 0 (- max-tt (long cur-turn)))
              fc (:seon.agent/fork-commit ag)
              chgs (let [bc (live-cid branch-db)] (cond (and fc (= fc bc)) [] (and fc (diff/ancestor-within? branch-db fc bc 256)) (try (d/changes branch-db fc bc) (catch Throwable _ (diff/branch-changes main-db branch-db))) :else (diff/branch-changes main-db branch-db)))
              n-edits (count (into #{}
                                   (keep (fn [dt]
                                           (let [a (:a dt)
                                                 ns-s (when (keyword? a) (namespace a))]
                                             (when (and ns-s (or (str/starts-with? ns-s "seon.code.")
                                                                 (= ns-s "seon.schema")
                                                                 (= ns-s "seon.rule")))
                                               (:e dt)))))
                                   chgs))]
          {:tag "session-budget"
           :text (str "Turn " cur-turn " / " max-tt " (" rem-t " left) · branch code/schema/rule edits: " n-edits
                      (when (and (zero? n-edits) (>= (long cur-turn) (max 2 (quot max-tt 2))))
                        " · halfway through budget with 0 persisted edits — begin implementing and testing now"))})))))

(defn- resolve-merge-target-id [main-db branch-db ag]
  (when-let [mt (:seon.agent/merge-target ag)]
    (let [aid (cond
                (qualified-keyword? mt) mt
                (and (vector? mt) (= :seon.agent/id (first mt))) (second mt)
                (map? mt) (or (:seon.agent/id mt)
                              (when (integer? (:db/id mt))
                                (or (:seon.agent/id (d/pull branch-db '[:seon.agent/id] (:db/id mt)))
                                    (:seon.agent/id (d/pull main-db '[:seon.agent/id] (:db/id mt))))))
                (integer? mt) (or (:seon.agent/id (d/pull branch-db '[:seon.agent/id] mt))
                                  (:seon.agent/id (d/pull main-db '[:seon.agent/id] mt)))
                :else nil)
          exists? (and (qualified-keyword? aid) (not= aid (:seon.agent/id ag))
                       (or (and ((db-schema branch-db) :seon.agent/id)
                                (first (d/datoms branch-db :avet :seon.agent/id aid)))
                           (and ((db-schema main-db) :seon.agent/id)
                                (first (d/datoms main-db :avet :seon.agent/id aid)))))]
      (when exists? aid))))

(defonce ^:private ^:seon.flow/edge project-wire-cache (store/lru-cache 32))

(defn project-wire
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map :map] :any]}
  [main-db walk-db ag opts]
  (let [prefs (if (map? (:seon.agent/context-prefs ag)) (:seon.agent/context-prefs ag) {})
        budget (long (or (:token-budget opts) (:token-budget prefs) 16000))
        per-item (long (or (:per-item-budget opts) 4000))
        strategy (or (:strategy opts) (:strategy prefs) :depth-first)
        explain? (boolean (:explain? opts))
        aid (:seon.agent/id ag)
        mc (live-cid main-db) wc (live-cid walk-db)
        ashown (:already-shown opts)
        ck (when (and (not explain?) mc wc aid (not (false? (:use-cache? opts)))
                      (not (instance? datahike.db.AsOfDB main-db))
                      (not (instance? datahike.db.AsOfDB walk-db)))
             [(get-in main-db [:config :store :id]) aid mc (:max-tx main-db)
              (if (= mc wc) :same-db [wc (:max-tx walk-db)])
              budget per-item strategy (boolean (:use-live-touches? opts))
              (when (seq ashown) (hash ashown))
              (when (seq (:disabled-rules opts)) (vec (sort (:disabled-rules opts))))
              (:weights opts) (long (or (:turn opts) 0))
              (vec (:seon.agent/pinned ag)) (:seon.agent/scope ag)
              (when-let [tdb (:task-db opts)] (live-cid tdb))])
        compute
        (fn []
          (let [res (ctx/project-entity
                     main-db walk-db [:seon.agent/id aid]
                     (merge {:agent ag :target :wire :token-budget budget :per-item-budget per-item
                             :strategy strategy :include-unmatched? false :include-focal-fit? false :include-rule-trace? false
                             :include-evals? false :fallback-renderers fallback-entity-renderers}
                            (select-keys opts [:task-db :turn :already-shown :disabled-rules :weights
                                               :explain? :semantic? :enrich? :use-live-touches? :use-cache?])))
                all-cands (:candidates res)
                fitted (:fitted res)
                ent-by-id (when explain? (into {} (map (juxt :entity :data)) all-cands))
                selected (render-selected-text walk-db main-db (:selected fitted) explain? ent-by-id)
                dropped (vec (:dropped fitted))
                active (filterv #(not= :unchanged (:status %)) selected)
                ctx-trace (:ctx-trace res)
                rtrace {:rules-count (long (or (:rules-total ctx-trace) 0))
                        :facts-count (count all-cands)
                        :ms (reduce + 0.0 (map #(double (or (:ms %) 0.0)) (:fired-rules ctx-trace)))
                        :runs (vec (:fired-rules ctx-trace))
                        :recomputed (long (or (:recomputed ctx-trace) 0))
                        :skipped (long (or (:skipped ctx-trace) 0))}
                tot-tok (long (:tokens fitted 0))
                explained (when explain?
                            {:target :wire :strategy strategy :budget budget :tokens tot-tok
                             :selected-count (count selected) :dropped-count (count dropped)
                             :items (vec (:items fitted)) :selected selected :dropped dropped})]
            (cond-> {:tile-projections selected :active-tiles active :candidates (mapv lean-wire-item all-cands)
                     :prefix-items (filterv #(= :agent-prefix (:zone %)) all-cands)
                     :walk-text (str/join "\n\n---\n\n" (map fmt-wire-tile active))
                     :walk-db walk-db :rule-trace rtrace
                     :fit-trace {:tokens tot-tok :budget budget :strategy strategy
                                 :selected-count (count selected) :dropped-count (count dropped)
                                 :items (mapv #(select-keys % [:entity :id :schema :zone :status :chosen-fn :tokens
                                                               :measured-tokens :rank/score :rank/signals :candidates :tx]) selected)}
                     :shown (or (:shown fitted) {})
                     :surface-stats {:selected (count selected) :dropped (count dropped)
                                     :unchanged (count (filter #(= :unchanged (:status %)) selected))
                                     :tokens tot-tok :budget budget}}
              explained (assoc :explained explained))))]
    (if ck (store/lru-get! project-wire-cache 32 ck compute) (compute))))

(defn build-live-delta-blocks
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map :int :any :map] :any]}
  [main-db branch-db ag cur-turn prev-shown opts]
  (let [delta-res (project-wire main-db branch-db ag
                                (merge (select-keys opts [:disabled-rules :weights])
                                       {:token-budget (long (or (:delta-token-budget opts) (:tail-token-budget opts) 4000))
                                        :per-item-budget (long (or (:delta-per-item-budget opts) 1500))
                                        :strategy (or (:delta-strategy opts) :breadth-first)
                                        :turn (long (max 1 cur-turn))
                                        :already-shown prev-shown
                                        :use-live-touches? true}))
        active (:active-tiles delta-res)
        msg-eids (into #{} (comp (filter #(= :seon.agent.message/entity (:schema %))) (keep :entity)) active)]
    {:blocks (when (seq active)
               [{:tag "context-delta"
                 :contains-unread? (boolean (seq msg-eids))
                 :hiccup (into [:section {:class "tail-block context-delta"}] (keep :hiccup active))
                 :text (str/join "\n\n---\n\n" (map fmt-wire-tile active))}])
     :delta-tiles (:tile-projections delta-res)
     :delta-active active
     :delta-shown (:shown delta-res)
     :delta-tokens (long (get-in delta-res [:fit-trace :tokens] 0))
     :rendered-msg-eids msg-eids}))

(defn build-target-preview-block
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map :any :int] :any]}
  [main-db branch-db ag _evals depth]
  (when (zero? (long depth))
    (let [tids (set (concat (rdata/agent-task-ids branch-db ag) (rdata/agent-task-ids main-db ag)))
          want-preview? (or (contains? tids :template/tune-context)
                            (contains? tids :template/refine-task-proofs)
                            (and (some? (:seon.agent/forked-from ag)) (= 'seon.ctx (:seon.agent/scope ag))))]
      (when want-preview?
        (when-let [taid (resolve-merge-target-id main-db branch-db ag)]
      (let [tag (or (rdata/pull-agent-entity main-db branch-db taid) {:seon.agent/id taid})
            tevs (rdata/agent-evals-in branch-db taid)
            pw (project-wire main-db branch-db tag {:use-live-touches? false})
            same-db? (or (identical? main-db branch-db)
                         (let [mc (live-cid main-db) bc (live-cid branch-db)] (and mc bc (= mc bc))))
            m-tag (when-not same-db? (rdata/pull-agent-entity main-db main-db taid))
            mpw (when m-tag (project-wire main-db main-db m-tag {:use-live-touches? false}))
            tiles (:active-tiles pw)
            tkey (fn [t] (or (:handle t) (:id t) (:entity t)))
            cur-ks (into #{} (map tkey) tiles)
            base-ks (if mpw (into #{} (map tkey) (:active-tiles mpw)) #{})
            added (when mpw (vec (sort-by str (remove base-ks cur-ks))))
            removed (when mpw (vec (sort-by str (remove cur-ks base-ks))))
            dropped (get-in pw [:surface-stats :dropped] 0)
            tot (long (get-in pw [:surface-stats :tokens] 0))
            bud (long (get-in pw [:surface-stats :budget] 16000))
            rows (mapv (fn [t]
                         (str "- " (tkey t) " (" (or (:schema t) :entity) " · " (or (:chosen-fn t) :default)
                              " · " (long (or (:tokens t) 0)) " tok)"))
                       tiles)
            fired-rs (vec (keep :rule-id (get-in pw [:rule-trace :runs])))
            err-evs (->> tevs
                         (filter #(not (str/blank? (str (:seon.agent.eval/error %)))))
                         (take-last 3)
                         (mapv (fn [e]
                                 (let [em (first (str/split-lines (str (:seon.agent.eval/error e))))
                                       cd (first (str/split-lines (str (:seon.agent.eval/code e))))]
                                   (str "- turn " (:seon.agent.eval/turn e) " (" (:seon.agent.eval/handle e) "): "
                                        (subs cd 0 (min 90 (count cd))) " => " (subs em 0 (min 140 (count em))))))))
            wtxt (str (:walk-text pw))
            excerpt (when-not (str/blank? wtxt)
                      (if (> (count wtxt) 1800)
                        (let [cut (or (str/last-index-of wtxt "\n" 1800) 1800)]
                          (str (subs wtxt 0 cut) "\n… [call (user/simulate-context " taid " {:render-text? true}) for full text]"))
                        wtxt))]
        {:tag "target-context-preview"
         :attrs {:for (str taid) :mode (if (seq tevs) "mid-session" "turn-0")
                 :tokens (str tot) :tiles (str (count tiles))}
         :text (str "Live projected context for " taid " (" (count tiles) " tiles, " tot " / " bud " tok; " dropped " dropped)"
                    (when mpw (str " · diff vs :main: +" (count added) " " (pr-str added) " / -" (count removed) " " (pr-str removed)))
                    (when (seq fired-rs) (str "\nFired rules: " (pr-str fired-rs)))
                    (when (seq err-evs) (str "\nTarget recent failing evals:\n" (str/join "\n" err-evs)))
                    ":\n"
                    (if (seq rows) (str/join "\n" rows) "(none)")
                    (when excerpt (str "\n\nRendered preview:\n" excerpt))
                    "\n\nFull text: (user/simulate-context " taid " {:render-text? true}) | 12-scenario check: (user/check-corpus)")}))))))

(defn build-last-proofs-block
  {:malli/schema [:=> [:cat :map] :any], :cost/ticks 300000}
  [ag]
  (when-let [lp (:seon.agent/last-proofs ag)]
    (when (and (map? lp) (pos? (long (or (:ran lp) 0))))
      (let [ok? (boolean (:ok? lp))
            ran (long (:ran lp 0))
            passed (long (:passed lp 0))
            failed (long (:failed lp 0))
            ms (:ms lp 0.0)
            tx (:tx lp)
            fails (vec (take 5 (:failures lp)))]
        {:tag "auto-proofs"
         :attrs {:ok (str ok?) :ran (str ran) :failed (str failed) :tx (str tx)}
         :text (if ok?
                 (str "Auto-proofs PASS (" passed "/" ran " green in " ms " ms, tx " tx ")")
                 (str "Auto-proofs FAIL (" failed "/" ran " failed in " ms " ms, tx " tx "):\n"
                      (str/join "\n" (map #(str "- " %) fails))))}))))
