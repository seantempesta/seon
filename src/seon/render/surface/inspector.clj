(ns seon.render.surface.inspector
  "Fractal Universal Hyperlinked Entity Surface (`seon.render.surface.inspector`):
   Clicking ANY entity (var, ns, task, rule, schema, agent, eval, error, fix, test, doc, branch, signal)
   runs the EXACT SAME generic 2-read graph discovery (`:eavt` + reverse `:avet`) + live EmbeddingGemma-2
   semantic neighborhood (`seon.vector/near` + `semantic-scores-for`) + 4-signal `fit/rank-candidates`
   + 2-pass `fit/fit-projections` Upgrade Ladder as an agent's own UI, paired with `fit/explain-entity-fit`
   showing ALL renderer variations side-by-side and real `← Back` / `→ Forward` + breadcrumb navigation."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks]
            [seon.render.wire :as rwire]
            [seon.signals :as sig]
            [seon.signals.touches :as touches]
            [seon.store :as store]
            [seon.vector :as vec]))

(defonce ^:private ^:seon.flow/edge ref-attrs-cache (store/lru-cache 16))
(defonce ^:private ^:seon.flow/edge entity-world-cache (store/lru-cache 32))

(defn- db-schema [db] (or (:schema db) (when db (d/schema db)) {}))
(defn- live-cid [db] (when (and db (instance? datahike.db.DB db)) (d/commit-id db)))

(defn- indexed-ref-attrs [db]
  (let [dh (db-schema db)
        ck (when (instance? datahike.db.DB db) [(get-in db [:config :store :id]) (count dh)])
        compute #(vec (sort (keep (fn [[a m]]
                                    (when (and (= :db.type/ref (:db/valueType m))
                                               (not= a :seon.vector/of))
                                      a))
                                  dh)))]
    (if ck (store/lru-get! ref-attrs-cache 16 ck compute) (compute))))

(def ^:private value-ref-targets
  {:seon.code.var/ns :seon.code.ns/name
   :seon.code.var/form :seon.code.form/key
   :seon.code.form/ns :seon.code.ns/name
   :seon.code.ns/requires :seon.code.ns/name
   :seon.task/scope :seon.code.ns/name
   :seon.task/owner :seon.agent/id
   :seon.task/created-by :seon.agent/id
   :seon.task/template :seon.task/id
   :seon.task/needs :seon.task/id
   :seon.task/tests :seon.code.var/name
   :seon.agent/task :seon.task/id
   :seon.agent/scope :seon.code.ns/name
   :seon.agent/parent :seon.agent/id
   :seon.agent.call/caller :seon.agent/id
   :seon.agent.call/callee :seon.agent/id
   :seon.agent.call/result-schema :seon.schema/key
   :seon.agent.call/parent-call :seon.agent.call/id
   :seon.error/agent :seon.agent/id
   :seon.error/var :seon.code.var/name
   :seon.error/eval :seon.agent.eval/id
   :seon.error/kind :seon.fix/error-class
   :seon.fix/regression-test :seon.code.var/name
   :seon.agent.eval/agent :seon.agent/id
   :seon.agent.eval/result-schema :seon.schema/key
   :seon.test/var :seon.code.var/name
   :seon.schema/render :seon.code.var/name
   :seon.schema/ref-targets :seon.schema/key})

(def ^:private reverse-value-attrs
  {:seon.code.ns/name    [:seon.code.var/ns :seon.task/scope :seon.agent/scope :seon.code.ns/requires]
   :seon.code.var/name   [:seon.code.var/refs :seon.test/var :seon.error/var :seon.task/tests]
   :seon.code.form/key   [:seon.code.var/form]
   :seon.task/id         [:seon.task/needs :seon.agent/task]
   :seon.agent/id        [:seon.agent/parent :seon.error/agent :seon.agent.eval/agent :seon.task/owner
                          :seon.agent.call/caller :seon.agent.call/callee]
   :seon.error/kind      [:seon.fix/error-class]
   :seon.fix/error-class [:seon.error/kind]
   :seon.code.doc/path   [:seon.code.doc/path]})

(defn- resolve-eid [bdb mdb ref]
  (let [[a v] ref]
    (cond
      (= a :db/id) (when (integer? v) v)
      (and bdb ((db-schema bdb) a)) (or (:e (first (d/datoms bdb :avet a v)))
                                        (when (and mdb (not= mdb bdb) ((db-schema mdb) a))
                                          (:e (first (d/datoms mdb :avet a v)))))
      (and mdb ((db-schema mdb) a)) (:e (first (d/datoms mdb :avet a v)))
      :else nil)))

(defn- pull-entity-best [bdb mdb eid]
  (when (integer? eid)
    (let [b (when bdb (d/pull bdb '[*] eid))]
      (if (and (map? b) (seq (dissoc b :db/id)))
        b
        (when (and mdb (not= mdb bdb))
          (let [m (d/pull mdb '[*] eid)]
            (when (and (map? m) (seq (dissoc m :db/id))) m)))))))

(defn- enrich-params
  ([bdb mdb ent] (enrich-params bdb mdb ent false))
  ([bdb mdb ent focal?]
   (let [p1 (rlinks/enrich-entity-params (or bdb mdb) ent focal?)]
     (if (and mdb (not= mdb bdb) (empty? p1))
       (rlinks/enrich-entity-params mdb ent focal?)
       p1))))

(defn- add-neighbor [acc eid polarity rel pri tx sem]
  (if-not (integer? eid)
    acc
    (let [cur (get acc eid)]
      (if cur
        (-> acc
            (update-in [eid :relations] conj rel)
            (update-in [eid :priority] max pri)
            (update-in [eid :tx] max (long (or tx 0)))
            (cond-> (number? sem) (assoc-in [eid :semantic] (double sem))))
        (assoc acc eid {:entity eid :polarity polarity :relation rel :relations #{rel}
                        :priority pri :tx (long (or tx 0)) :rules [(keyword "rel" (name rel))]
                        :semantic sem})))))

(defn- discover-real-neighbors [mdb bdb focus-eid ent]
  (let [db (or bdb mdb)
        dh (db-schema db)
        ;; 1. Forward :eavt read on focus-eid
        acc1 (reduce
              (fn [acc dt]
                (let [a (:a dt) v (:v dt) tx (:tx dt) vt (get-in dh [a :db/valueType])]
                  (cond
                    (and (= :db.type/ref vt) (integer? v) (not= v focus-eid))
                    (add-neighbor acc v :outbound a 86 tx nil)

                    (= a :seon.agent/pinned)
                    (reduce (fn [a2 r]
                              (if-let [te (when-let [nr (rdata/normalize-inspect-ref r)] (resolve-eid bdb mdb nr))]
                                (if (not= te focus-eid) (add-neighbor a2 te :outbound :seon.agent/pinned 88 tx nil) a2)
                                a2))
                            acc (if (coll? v) v []))

                    :else
                    (if-let [target-a (get value-ref-targets a)]
                      (let [te (resolve-eid bdb mdb [target-a v])]
                        (if (and te (not= te focus-eid))
                          (add-neighbor acc te :outbound a 84 tx nil)
                          acc))
                      acc))))
              {} (take 96 (d/datoms db :eavt focus-eid)))
        ;; 1b. Eval touched entities (for :seon.agent.eval/entity)
        acc1b (if (:seon.agent.eval/id ent)
                (let [et (touches/extract-eval-touches db [ent])
                      teids (concat (take 10 (:touched-eids et))
                                    (keep #(resolve-eid bdb mdb [:seon.code.var/name %]) (take 10 (:touched-syms et)))
                                    (keep #(resolve-eid bdb mdb [:seon.schema/key %]) (take 8 (:touched-schemas et))))]
                  (reduce #(if (not= %2 focus-eid) (add-neighbor %1 %2 :outbound :eval/touched 85 (:max-tx db 0) nil) %1)
                          acc1 (distinct teids)))
                acc1)
        ;; 2. Generic Reverse :avet reads over all :db.type/ref attrs
        acc2 (reduce
              (fn [acc ra]
                (reduce (fn [a2 dt]
                          (let [e (:e dt)]
                            (if (and (integer? e) (not= e focus-eid))
                              (add-neighbor a2 e :inbound ra 78 (:tx dt) nil)
                              a2)))
                        acc (take 10 (d/datoms db :avet ra focus-eid))))
              acc1b (indexed-ref-attrs db))
        ;; 2b. Reverse value-indexed attrs matching entity's identity values
        fix-datoms (when (and (dh :seon.fix/error-class) (:seon.error/kind ent))
                     (vec (d/datoms db :aevt :seon.fix/error-class)))
        acc2b (reduce
               (fn [acc [id-a rev-as]]
                 (if-let [iv (get ent id-a)]
                   (reduce (fn [a2 ra]
                             (if (dh ra)
                               (reduce (fn [a3 dt]
                                         (let [e (:e dt)]
                                           (if (and (integer? e) (not= e focus-eid))
                                             (add-neighbor a3 e :inbound ra 76 (:tx dt) nil)
                                             a3)))
                                       a2 (if (= ra :seon.fix/error-class)
                                            (take 12 (filter #(= iv (:v %)) fix-datoms))
                                            (take 12 (d/datoms db :avet ra iv))))
                               a2))
                           acc rev-as)
                   acc))
               acc2 reverse-value-attrs)
        ;; 3. Live EmbeddingGemma-2 semantic nearest neighbors + batched cosine scoring
        fvec (when mdb (vec/vector-of mdb focus-eid))
        acc3 (if (seq fvec)
               (let [near-hits (vec/near mdb fvec 8)
                     with-near (reduce (fn [a [ne s]]
                                         (if (not= ne focus-eid)
                                           (add-neighbor a ne :semantic :semantic/near 70 (:max-tx mdb 0) s)
                                           a))
                                       acc2b near-hits)
                     all-eids (vec (keys with-near))
                     smap (vec/semantic-scores-for mdb fvec all-eids)]
                 (reduce-kv (fn [m e item]
                              (let [s (or (get smap e) (:semantic item))]
                                (assoc m e (cond-> item (number? s) (assoc :semantic (double s))))))
                            {} with-near))
               acc2b)]
    {:focus-vec? (boolean (seq fvec))
     :neighbors (vec (take 28 (sort-by (fn [c] [(- (long (:priority c 0))) (- (double (or (:semantic c) 0.0))) (:entity c)])
                                       (vals acc3))))}))

(defn- virtual-signal-world [mdb bdb sig-kind]
  (let [snap (sig/all-signals mdb)
        dh (db-schema mdb)
        eids (case sig-kind
               :flow (when (dh :seon.agent/id) (map :e (take 16 (d/datoms mdb :aevt :seon.agent/id))))
               :pressure (concat (when (dh :seon.error/kind) (map :e (take 8 (d/datoms mdb :avet :seon.error/kind :slow-render))))
                                 (when (dh :seon.agent.eval/id) (map :e (take-last 10 (d/datoms (or bdb mdb) :aevt :seon.agent.eval/id)))))
               :context (when (dh :seon.agent.eval/id) (map :e (take-last 12 (d/datoms (or bdb mdb) :aevt :seon.agent.eval/id))))
               :proof (when (dh :seon.test/digest)
                        (->> (take-last 24 (d/datoms mdb :aevt :seon.test/digest))
                             (map #(let [e (:e %)] [e (false? (:v (first (d/datoms mdb :eavt e :seon.test/pass?))))]))
                             (sort-by #(if (second %) 0 1))
                             (take 16) (map first)))
               :health (concat (when (dh :seon.error/status) (map :e (take 12 (d/datoms mdb :avet :seon.error/status :open))))
                               (when (dh :seon.fix/id) (map :e (take 8 (d/datoms mdb :aevt :seon.fix/id)))))
               [])]
    {:ref [:seon.signal/kind sig-kind]
     :virtual? true :kind :signal :signal-kind sig-kind
     :focal-item {:entity [:seon.signal/kind sig-kind] :schema :seon.signal/snapshot :data snap :priority 100}
     :neighbors (mapv (fn [e] {:entity e :polarity :outbound :relation (keyword "signal" (name sig-kind))
                               :priority 82 :tx (:max-tx mdb 0) :rules [:signal/members]})
                      (distinct (filter integer? eids)))}))

(defn- virtual-branch-world [mdb bdb br]
  (let [dh (db-schema mdb)
        ags (when (dh :seon.agent/branch) (map :e (take 12 (d/datoms mdb :avet :seon.agent/branch br))))
        errs (when (dh :seon.error/branch) (map :e (take 8 (d/datoms mdb :avet :seon.error/branch br))))
        trun (when (dh :seon.test.run/branch)
               (when-let [e (:e (first (d/datoms mdb :avet :seon.test.run/branch br)))]
                 (d/pull mdb '[*] e)))]
    {:ref [:seon.branch/name br]
     :virtual? true :kind :branch :branch-name br :test-run trun
     :focal-item {:entity [:seon.branch/name br] :data {:seon.branch/name br :test-run trun} :priority 100}
     :neighbors (mapv (fn [e] {:entity e :polarity :outbound :relation :branch/member
                               :priority 82 :tx (:max-tx mdb 0) :rules [:branch/members]})
                      (distinct (filter integer? (concat ags errs))))}))

(defn- compute-entity-world [main-db bdb ref]
  (let [[ra rv] ref]
    (cond
      (= ra :seon.signal/kind)
      (let [w (virtual-signal-world main-db bdb rv)]
        (assoc w :candidates
               (vec (keep (fn [c]
                            (when-let [ent (pull-entity-best bdb main-db (:entity c))]
                              (let [ep (enrich-params bdb main-db ent false)]
                                (cond-> (assoc c :data ent) (seq ep) (update :params merge ep)))))
                          (:neighbors w)))))

      (= ra :seon.branch/name)
      (let [w (virtual-branch-world main-db bdb rv)]
        (assoc w :candidates
               (vec (keep (fn [c]
                            (when-let [ent (pull-entity-best bdb main-db (:entity c))]
                              (let [ep (enrich-params bdb main-db ent false)]
                                (cond-> (assoc c :data ent) (seq ep) (update :params merge ep)))))
                          (:neighbors w)))))

      :else
      (let [eid (when ref (resolve-eid bdb main-db ref))
            ent (when eid (pull-entity-best bdb main-db eid))
            ep (when ent (enrich-params bdb main-db ent true))
            {:keys [focus-vec? neighbors]} (if (and eid ent)
                                             (discover-real-neighbors main-db bdb eid ent)
                                             {:focus-vec? false :neighbors []})
            cands (vec (keep (fn [c]
                               (when-let [ne (pull-entity-best bdb main-db (:entity c))]
                                 (let [nep (enrich-params bdb main-db ne false)]
                                   (cond-> (assoc c :data ne) (seq nep) (update :params merge nep)))))
                             neighbors))]
        {:ref (or (when ent (rdata/entity-lookup-ref ent)) ref)
         :eid eid :entity ent :virtual? false :focus-vec? focus-vec?
         :focal-item (when (and eid ent)
                       (cond-> {:entity eid :data ent :priority 100}
                         (seq ep) (assoc :params ep)))
         :candidates cands}))))

(defn entity-world-candidates
  "Commit-cached generic 2-read entity-rooted world discovery (`:eavt` outbound + reverse `:avet` inbound +
   live `seon.vector/near` semantic neighbors) for ANY entity lookup ref or EID, replaying `datahike.reads`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any] :map]
   :cost/ticks 30000000}
  [main-db branch-db ref-or-eid]
  (let [bdb (or branch-db main-db)
        ref (if (integer? ref-or-eid) [:db/id ref-or-eid] (rdata/normalize-inspect-ref ref-or-eid))
        mc (live-cid main-db) bc (live-cid bdb)
        ck (when (and mc bc ref)
             [(get-in main-db [:config :store :id]) mc (:max-tx main-db) bc (:max-tx bdb) ref])]
    (if ck
      (store/lru-get! entity-world-cache 32 ck #(compute-entity-world main-db bdb ref))
      (compute-entity-world main-db bdb ref))))

(defn- btn [cls oc lbl & [disabled?]]
  [:button (cond-> {:type "button" :class cls :on-click oc} disabled? (assoc :disabled true)) lbl])

(defn- badge [cls txt] [:span {:class (str "badge " cls)} txt])
(defn- mut [txt] [:span {:class "mono tiny muted"} txt])
(defn- trunc [s n] (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn- render-nav-bar [viewing-id agent-lbl ref trail fwd lab-prefs focal]
  (let [[ra rv] ref
        dis-rules (:disabled-rules lab-prefs)
        rule-dis? (and (= ra :seon.rule/id) (contains? dis-rules rv))]
    [:div {:id "insp-nav-bar" :class "insp-bar"}
     [:div {:class "insp-nav-left"}
      (btn "btn sm" '(seon.web/inspect-back!) "← Back" (empty? trail))
      (btn "btn sm" '(seon.web/inspect-forward!) "→ Forward" (empty? fwd))
      [:div {:class "insp-trail"}
       (for [[idx tr] (map-indexed vector trail)]
         (list [:button {:type "button" :class "crumb-btn mono tiny"
                         :on-click (list 'seon.web/jump-inspect-trail! idx)}
                (rdata/entity-short-label tr)]
               [:span {:class "crumb-sep"} "›"]))
       [:span {:class "badge amber mono"} (rdata/entity-short-label (or (:entity focal) ref))]]]
     [:div {:class "pill-row"}
      (when (= ra :seon.agent/id)
        (list (btn "btn sm primary" (list 'seon.web/view-agent! rv :session) "Open Session")
              (btn "btn sm" (list 'seon.web/view-agent! rv :context) "Context & Cache")))
      (when (= ra :seon.rule/id)
        (btn (if rule-dis? "btn sm success" "btn sm danger")
             (list 'seon.web/toggle-lab-rule! rv)
             (if rule-dis? "Enable in Lab" "Disable in Lab")))
      (when (= ra :seon.branch/name)
        (list (btn "btn sm" (list 'seon.web/run-tests! rv) "Test Branch")
              (when (not= rv :main) (btn "btn sm success" (list 'seon.web/merge-branch! rv) "Merge → :main"))))
      (when (= ref [:seon.signal/kind :context])
        (btn "btn sm primary" '(seon.web/set-view-mode! :context) "Open Context & Cache →"))
      (when (and ref (not= ra :seon.signal/kind) (not= ra :seon.branch/name))
        (btn "btn sm" (list 'seon.web/pin-to-agent! viewing-id ref) (str "+ Pin to " agent-lbl)))
      (btn "btn sm" '(seon.web/inspect-close!) "Close ×")]]))

(defn- render-variations-panel [explained lab-prefs stack?]
  (let [vars (:variations explained)
        itarget (:inspect-target lab-prefs)
        ibud (:inspect-budget lab-prefs)
        iw (:inspect-width lab-prefs)
        chip-btn (fn [active? patch lbl]
                   [:button {:type "button" :class (str "lab-chip" (when active? " active"))
                             :on-click (list 'seon.web/update-lab-prefs! patch)} lbl])]
    [:section {:id "insp-variations-unit" :class "card stack-sm"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:h2 (str "All Renderer Variations (" (count vars) ")")]
       (badge "purple mono" (if (= itarget :wire) "Model Wire" "Browser UI"))
       (mut (str "budget " ibud " tok · width " iw "px"))]
      [:div {:class "lab-controls"}
       (chip-btn (= itarget :html) {:inspect-target :html} "Browser UI")
       (chip-btn (= itarget :wire) {:inspect-target :wire} "Model Wire")
       (for [b [200 600 2400]] (chip-btn (= ibud b) {:inspect-budget b} (str b " tok")))
       (for [w [360 640 1100]] (chip-btn (= iw w) {:inspect-width w} (str w "px")))]]
     (if (empty? vars)
       [:p {:class "muted sm-copy"} "No `:seon.ui/hiccup` variations discovered for this entity."]
       [:div {:class "stack-sm"}
        (for [v vars
              :let [win? (:chosen? v) fnsym (:fn v)]]
          [:div {:class (str "var-card" (when win? " is-winner"))}
           [:div {:class "card-head"}
            [:div {:class "pill-row"}
             (when win? (badge "green" "WINNER"))
             (if (qualified-symbol? fnsym)
               [:button {:type "button" :class "ent-link mono strong"
                         :on-click (list 'seon.web/inspect-entity! [:seon.code.var/name fnsym])} (str fnsym)]
               [:span {:class "mono strong"} (str fnsym)])
             (badge (if win? "green" "muted") (:reason v))]
            (mut (str (:tokens v 0) " tok · min " (:min-width v 0) "px · " (:area v 0) " px² · " (:lines v 0) "L"))]
           [:div {:class (if stack? "debug-tile-pair is-stack" "debug-tile-pair")}
            [:div {:class "debug-tile-col"}
             [:span {:class "kicker"} "Browser HTML"]
             (or (:hiccup v) (mut "(empty)"))]
            [:div {:class "debug-tile-col"}
             [:span {:class "kicker"} "Model Text (hiccup->text)"]
             [:pre {:class "code-box tiny"} (trunc (str (:text v)) 900)]]]])])]))

(defn- render-focal-extras [world main-db]
  (let [ent (:entity world)]
    (cond
      (= :signal (:kind world))
      (let [sk (:signal-kind world)
            snap (get-in world [:focal-item :data])
            sig-m (get snap sk)]
        [:section {:class "card stack-sm"}
         [:div {:class "card-head"}
          [:h2 (str (str/capitalize (name sk)) " Signal Breakdown")]
          [:div {:class "pill-row"}
           (when-let [br (:branch snap)] (rlinks/ent-link [:seon.branch/name br] (str br) "pill mono tiny"))
           (badge (case (:level sig-m) :hot "red" :warm "amber" "green") (name (or (:level sig-m) :calm)))]]
         [:div {:class "pill-row"}
          (for [k [:flow :pressure :context :proof :health]]
            (rlinks/ent-link [:seon.signal/kind k] (str/capitalize (name k))
                             (str "pill mono tiny" (when (= k sk) " strong"))))]
         [:p {:class "mono sm-copy"} (str (:summary sig-m))]
         [:pre {:class "code-box tiny"} (pr-str (dissoc sig-m :summary))]])

      (= :branch (:kind world))
      [:section {:class "card stack-sm"}
       [:div {:class "card-head"}
        [:h2 (str "Branch " (:branch-name world))]
        [:div {:class "pill-row"}
         (when-let [ag (get-in world [:test-run :seon.test.run/agent])]
           (rlinks/ent-link [:seon.agent/id ag] (str ag) "pill mono tiny"))
         (when-let [tr (:test-run world)]
           (badge (if (:seon.test.run/pass? tr) "green" "red")
                  (if (:seon.test.run/pass? tr) "Tests PASS" "Tests FAIL")))]]
       (when-let [fds (seq (get-in world [:test-run :seon.test.run/failed-digests]))]
         (rlinks/pill-list "failed verdicts:" fds :seon.test/digest 8))
       (when-let [tr (:test-run world)]
         [:pre {:class "code-box tiny"} (pr-str (dissoc tr :db/id))])]

      (:seon.agent.eval/id ent)
      (let [pt (long (or (:seon.agent.eval/prompt-tokens ent) 0))
            ht (long (or (:seon.agent.eval/cache-hit-tokens ent) 0))
            mt (long (or (:seon.agent.eval/cache-miss-tokens ent) 0))
            pct (if (pos? pt) (/ (Math/round (* 1000.0 (/ (double ht) (double pt)))) 10.0) 0.0)]
        [:section {:class "card stack-sm"}
         [:div {:class "card-head"}
          [:h2 "Eval Turn Telemetry & Reasoning"]
          [:div {:class "pill-row"}
           (when-let [ag (:seon.agent.eval/agent ent)] (rlinks/ent-link [:seon.agent/id ag] (str ag) "pill mono tiny"))
           (when-let [rs (:seon.agent.eval/result-schema ent)] (rlinks/ent-link [:seon.schema/key rs] (str rs) "pill mono tiny"))
           (when (:seon.agent.eval/prefix-hash ent) (badge "mono" (str "prefix " (:seon.agent.eval/prefix-hash ent))))
           (badge (if (>= pct 80.0) "green" "amber") (str "KV hit " pct "% (" ht "/" pt " tok, miss " mt ")"))]]
         (when-let [r (not-empty (str (:seon.agent.eval/reasoning ent "")))]
           [:details {:class "activity-fold" :open true}
            [:summary {:class "mono tiny"} (str "Reasoning (" (count r) " chars)")]
            [:pre {:class "code-box tiny"} (trunc r 2400)]])])

      (:seon.schema/key ent)
      (when-let [dh-attr (get (db-schema main-db) (:seon.schema/key ent))]
        [:section {:class "card stack-xs"}
         [:span {:class "kicker"} "Derived Datahike Attribute"]
         [:pre {:class "code-box tiny"} (pr-str dh-attr)]])

      :else nil)))

(defn- render-generic-focal-card [ent ref dh]
  (when (map? ent)
    (let [rows (for [[k v] (sort-by (comp str key) (dissoc ent :db/id))
                     :when (some? v)
                     :let [ta (get value-ref-targets k)
                           k-node (if (contains? dh k) (rlinks/ent-link [:seon.schema/key k] (str k) "mono tiny") (str k))
                           v-node (cond
                                    (and ta (not (coll? v))) (rlinks/ent-link [ta v] (str v) "mono tiny")
                                    (and (vector? v) (rdata/normalize-inspect-ref v))
                                    (rlinks/ent-link v (rdata/entity-short-label v) "mono tiny")
                                    (and (map? v) (integer? (:db/id v)))
                                    (rlinks/ent-link [:db/id (:db/id v)] (str "#" (:db/id v)) "mono tiny")
                                    :else (trunc (pr-str v) 240))]]
                 [[:dt k-node] [:dd v-node]])]
      [:div {:class "card ent-card stack-sm"}
       [:div {:class "card-head"}
        (rlinks/ent-link ref (rdata/entity-short-label (or ent ref)) "mono strong")]
       (when (seq rows) (into [:dl {:class "kv-grid"}] (mapcat identity rows)))])))

(defn- render-neighbor-group [title items dh]
  (when (seq items)
    [:section {:class "ctx-group stack-sm"}
     [:div {:class "ctx-group-head"}
      [:span {:class "kicker"} title]
      (mut (str (count items) " fitted"))]
     [:div {:class "stack-sm"}
      (for [it items
            :let [r (or (rdata/entity-lookup-ref (:data it)) (when (integer? (:entity it)) [:db/id (:entity it)]))
                  rel (:relation it)
                  cfn (:chosen-fn it)
                  sem (:semantic it)]]
        [:div {:class "surface-entity-item" :data-entity (str (:entity it)) :data-chosen-fn (str cfn)}
         [:div {:class "ent-prov-bar"}
          [:div {:class "pill-row"}
           (if (and (qualified-keyword? rel) (contains? dh rel))
             (rlinks/ent-link [:seon.schema/key rel] (name rel) "badge muted tiny mono")
             (badge "muted" (name (or rel :ref))))
           (if (qualified-symbol? cfn)
             (rlinks/ent-link [:seon.code.var/name cfn] (name cfn) "mono tiny muted")
             (mut (str (some-> cfn name))))
           (mut (str "· " (:tokens it 0) " tok"))
           (when (number? sem) (badge "purple" (str "cos " (/ (Math/round (* (double sem) 100.0)) 100.0))))]
          (when r
            [:button {:type "button" :class "ent-link tiny mono"
                      :on-click (list 'seon.web/inspect-entity! r)} "Re-root →"])]
         (or (:hiccup it)
             [:div {:class "ent-row mono tiny"}
              (rlinks/ent-link r (rdata/entity-short-label (or (:data it) r)) "mono tiny strong")])])]]))

(defn render-inspector-body
  "Renders the fractal entity-rooted surface for `:inspect-ref` (navigation bar, focal entity +
   ALL renderer variations via `fit/explain-entity-fit`, and Upgrade-Ladder-fitted graph & semantic neighborhood)."
  {:malli/schema [:=> [:cat :map] [:vector :any]]
   :cost/ticks 40000000}
  [{:keys [main-db branch-db viewing-id agent inspect-ref inspect-trail inspect-forward
           lab-prefs slot-budget branch-feedback]}]
  (if-not (or main-db branch-db)
    [:div {:id "seon-inspector-unit" :class "card muted"} "No database connected."]
    (let [main-db (or main-db branch-db)
          lp (rdata/normalize-lab-prefs lab-prefs)
          ref (or inspect-ref [:seon.agent/id viewing-id])
          world (entity-world-candidates main-db branch-db ref)
          avail-w (long (:avail-w slot-budget 1000))
          stack? (< avail-w 720)
          dh (db-schema main-db)
          ex-opts {:target (:inspect-target lp)
                   :token-budget (:inspect-budget lp)
                   :avail-w (:inspect-width lp) :main-db main-db
                   :area-budget (* (long (:inspect-width lp)) 1200)
                   :fallback-renderers rwire/fallback-entity-renderers}
          explained (when-let [fi (:focal-item world)]
                      (fit/explain-entity-fit (or branch-db main-db) fi ex-opts))
          fitted-nb (when (seq (:candidates world))
                      (fit/fit-projections (or branch-db main-db) (:candidates world)
                                           {:target :html :avail-w avail-w :main-db main-db
                                            :area-budget (max 800000 (long (:area-budget slot-budget 1200000)))
                                            :max-items 24
                                            :fallback-renderers rwire/fallback-entity-renderers}))
          sel-nb (:selected fitted-nb)
          out-nb (filterv #(= :outbound (:polarity %)) sel-nb)
          in-nb (filterv #(= :inbound (:polarity %)) sel-nb)
          sem-nb (filterv #(= :semantic (:polarity %)) sel-nb)
          winner-h (some #(when (:chosen? %) (:hiccup %)) (:variations explained))]
      [:div {:id "seon-inspector-unit" :class "stack"}
       (when branch-feedback
         [:div {:class (str "notice row-between " (if (:ok? branch-feedback) "green" "red"))}
          [:span (:text branch-feedback)]
          (btn "link-muted tiny" '(seon.web/dismiss-feedback!) "×")])
       (render-nav-bar viewing-id (rdata/agent-label agent viewing-id) (:ref world) inspect-trail inspect-forward lp world)
       (if (and (not (:virtual? world)) (nil? (:entity world)))
         [:section {:class "card"}
          [:h2 "Entity not found"]
          [:p {:class "muted sm-copy mono"} (str "No entity matched " (pr-str ref) " on current DB.")]]
         (list
          (when-let [focal-card (or winner-h (render-generic-focal-card (:entity world) (:ref world) dh))]
            [:section {:id "insp-focal-unit" :class "stack-xs"}
             [:div {:class "row-between"}
              [:div {:class "pill-row"}
               [:span {:class "kicker"} "Focal Entity Surface"]
               (rlinks/ent-link (:ref world) (pr-str (:ref world)) "mono tiny muted")
               (when (:focus-vec? world) (badge "purple" "embedded"))]
              (mut (str (count sel-nb) " neighborhood entities fitted via Upgrade Ladder"))]
             focal-card])
          (render-focal-extras world main-db)
          (when explained (render-variations-panel explained lp stack?))
          [:section {:id "insp-neighborhood-unit" :class "stack"}
           [:div {:class "row-between"}
            [:h2 (str "Graph & Semantic Neighborhood (" (count sel-nb) ")")]
            (mut "Generated via 2-read :eavt/:avet + seon.vector/near + Upgrade Ladder")]
           (if (empty? sel-nb)
             [:div {:class "card muted sm-copy"} "No linked graph or semantic neighbors found for this entity."]
             [:div {:class (if stack? "stack" "insp-neighbors")}
              (render-neighbor-group "Outbound & Direct Refs" out-nb dh)
              (render-neighbor-group "Inbound & Referencers" in-nb dh)
              (render-neighbor-group "Semantically Nearest (EmbeddingGemma-2)" sem-nb dh)])]))])))
