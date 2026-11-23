(ns seon.render.fit
  "Pure 4-signal self-balancing candidate ranking (urgency, recency, convergence, optional semantic)
   with rule boost/suppress modifiers, commit-cached Datahike `:avet` renderer discovery,
   unified 2-pass Upgrade Ladder, and candidate/variation explainer."
  (:require [datahike.api :as d]
            [malli.core :as m]
            [seon.render.fit.measure :as fmeas]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store :as store]))

(def default-rank-weights
  "Default tunable weights for the native Datahike ranking signals."
  {:urgency 1.0 :recency 0.75 :convergence 0.5})

(def ^:private zone-order
  {:static 0 :static-prefix 0 :canvas 1 :workspace 2 :debug 3 :ephemeral-tail 4})

(defonce ^:private ^:seon.flow/edge discover-renderers-cache (store/lru-cache 256))
(defonce ^:private ^:seon.flow/edge db-reg-cache (store/lru-cache 32))
(defonce ^:private ^:seon.flow/edge validator-cache (store/lru-cache 256))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (fmeas/finite-double x 0.0))))

(defn- round4 [x]
  (/ (double (Math/round (* (fmeas/finite-double x 0.0) 10000.0))) 10000.0))

(defn- db-schema [db]
  (or (:schema db) (when db (d/schema db)) {}))

(defn- db-cache-sig [db]
  (cond
    (instance? datahike.db.DB db)
    [(get-in db [:config :store :id]) (get-in db [:config :branch] :main)
     (d/commit-id db) (:max-tx db) nil]

    (instance? datahike.db.AsOfDB db)
    (when-let [orig (:origin-db db)]
      [(get-in orig [:config :store :id]) (get-in orig [:config :branch] :main)
       (when (instance? datahike.db.DB orig) (d/commit-id orig))
       (:max-tx orig) (:time-point db)])

    :else nil))

(defn cached-registry-info
  "Returns commit/as-of cached `schema/registry-for-db` enriched with `:reg-sig`, replaying `datahike.reads`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :map]
   :cost/ticks 2000000}
  [db]
  (let [compute (fn []
                  (let [ri (schema/registry-for-db db)]
                    (assoc ri :reg-sig [(System/identityHashCode (:registry ri)) (count (:forms ri))])))
        ov-id (if schema/*base-forms-override* (System/identityHashCode schema/*base-forms-override*) 0)
        sig (when db (db-cache-sig db))]
    (if sig
      (store/lru-get! db-reg-cache 32 [sig ov-id] compute)
      (compute))))

(defn phase-rank-weights
  "Returns turn-phase adaptive rank weights: turn 0 emphasizes `:semantic` & `:urgency`;
   turns 1-2 balance all 4 signals; turn 3+ shifts toward `:recency` & `:convergence`."
  {:malli/schema [:=> [:cat [:maybe :int]]
                  [:map [:urgency number?] [:recency number?] [:convergence number?] [:semantic number?]]]
   :cost/ticks 50000}
  [turn]
  (let [t (max 0 (fmeas/safe-long turn 0))]
    (cond
      (zero? t) {:urgency 1.0  :recency 0.25 :convergence 0.25 :semantic 1.0}
      (< t 3)   {:urgency 0.85 :recency 0.65 :convergence 0.55 :semantic 0.65}
      :else     {:urgency 0.55 :recency 1.0  :convergence 0.85 :semantic 0.35})))

(defn- normalize-weights
  ([w] (normalize-weights w false))
  ([w has-sem?]
   (let [wm (if (map? w) w {})
         wu (max 0.0 (fmeas/finite-double (:urgency wm) (:urgency default-rank-weights)))
         wr (max 0.0 (fmeas/finite-double (:recency wm) (:recency default-rank-weights)))
         wc (max 0.0 (fmeas/finite-double (or (:convergence wm) (:touches wm)) (:convergence default-rank-weights)))
         has-wsem? (number? (:semantic wm))
         wsem (cond (not has-sem?) 0.0 has-wsem? (max 0.0 (fmeas/finite-double (:semantic wm) 0.0)) :else 0.5)
         base (if (pos? (+ wu wr wc wsem))
                {:urgency wu :recency wr :convergence wc}
                default-rank-weights)]
     (cond-> base
       (or has-sem? has-wsem?)
       (assoc :semantic (if has-sem? wsem (max 0.0 (fmeas/finite-double (:semantic wm) 0.0))))))))

(defn read-rank-weights
  "Reads `:seon.config/rank-weights` from `db` (falling back to `default-rank-weights`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :seon.config/rank-weights]
   :cost/ticks 500000}
  [db]
  (let [dh (db-schema db)]
    (if-not (and db (contains? dh :seon.config/rank-weights))
      default-rank-weights
      (let [root-w (when (contains? dh :seon.config/key)
                     (:seon.config/rank-weights
                      (d/pull db '[:seon.config/rank-weights] [:seon.config/key :seon.config/root])))]
        (normalize-weights (or root-w (:v (first (d/datoms db :aevt :seon.config/rank-weights)))) false)))))

(defn- to-set [x]
  (cond (set? x) x (coll? x) (set x) (some? x) #{x} :else #{}))

(defn- candidate-urgency [c]
  (if (number? (:urgency c))
    (clamp01 (:urgency c))
    (let [data (if (map? (:data c)) (:data c) {})
          tags (to-set (:tags c))
          rules (to-set (:rules c))
          anchor (cond
                   (or (= :open (:seon.error/status data)) (:open-error? c)
                       (tags :error) (tags :fault) (rules :rule.ctx/fault-with-recommended-fix)) 1.0
                   (or (false? (:seon.test/pass? data)) (:failing-test? c)
                       (tags :failing-test) (rules :rule.ctx/failing-reach-tests)) 0.95
                   (or (some? (:seon.fix/id data)) (:matching-fix? c) (tags :fix)) 0.90
                   (or (:edited? c) (:branch-edited? c)
                       (tags :edited) (rules :rule.ctx/edited-var-on-branch)) 0.85
                   (or (and (some? (:seon.task/id data)) (not (:seon.task/done? data)))
                       (:active-task? c) (tags :active-task) (rules :rule.ctx/active-task-and-lineage)) 0.75
                   (or (:pinned? c) (tags :pinned) (rules :rule.ctx/pinned-entities)) 0.70
                   :else 0.0)
          pri-u (if (number? (:priority c))
                  (* 0.45 (clamp01 (/ (fmeas/finite-double (:priority c) 0.0) 100.0)))
                  0.0)]
      (max anchor pri-u))))

(defn- candidate-tx [c]
  (max 0 (fmeas/safe-long (or (:tx c) (:max-tx c) (when (map? (:data c)) (:tx (:data c)))) 0)))

(defn- candidate-turn [c]
  (max 0 (fmeas/safe-long (or (:turn c) (when (map? (:data c)) (:seon.agent.eval/turn (:data c)))) 0)))

(defn- candidate-raw-convergence [c]
  (+ (max 0 (fmeas/safe-long (or (:touches c) (:eval-touches c)) 0))
     (max 0 (fmeas/safe-long (or (:converging-paths c) (:convergence c)
                                 (when (coll? (:rules c)) (count (:rules c)))) 0))))

(defn- candidate-semantic [c]
  (let [v (or (:semantic c) (:semantic/score c) (:ctx.match/score c))]
    (when (number? v) (clamp01 v))))

(defn- candidate-boost [c]
  (let [v (or (:rank/boost c) (get-in c [:params :rank/boost]))]
    (if (number? v) (max -1.0 (min 1.0 (fmeas/finite-double v 0.0))) 0.0)))

(defn- candidate-suppress [c]
  (let [v (or (:rank/suppress c) (get-in c [:params :rank/suppress]))]
    (if (number? v) (clamp01 v) 0.0)))

(defn- pool-norm [vs]
  (if (empty? vs)
    []
    (let [lo (reduce min vs) hi (reduce max vs) span (- hi lo)]
      (if (zero? span)
        (mapv #(if (pos? %) 1.0 0.0) vs)
        (mapv #(/ (double (- % lo)) (double span)) vs)))))

(defn rank-candidates
  "Pure 4-signal self-balancing rank over candidate entity maps: urgency/anchor, native `?tx`/turn
   recency, cumulative touches/convergence, and optional `:semantic` signal, plus rule-emitted
   `:rank/boost` and `:rank/suppress` modifiers and optional `:turn` phase preset."
  {:malli/schema [:=> [:cat [:vector :map] [:? [:maybe :map]]] [:vector :map]]
   :cost/ticks 10000000}
  ([candidates] (rank-candidates candidates nil))
  ([candidates opts]
   (if (empty? candidates)
     []
     (let [o (if (map? opts) opts {})
           sems (mapv candidate-semantic candidates)
           has-sem? (boolean (some some? sems))
           raw-w (or (:weights o) (when (integer? (:turn o)) (phase-rank-weights (:turn o))))
           ws (normalize-weights raw-w has-sem?)
           wu (double (:urgency ws))
           wr (double (:recency ws))
           wc (double (:convergence ws))
           wsem (if has-sem? (double (:semantic ws 0.0)) 0.0)
           w-sum (let [s (+ wu wr wc wsem)] (if (pos? s) s 1.0))
           txs (mapv candidate-tx candidates)
           turns (mapv candidate-turn candidates)
           tx-ns (pool-norm txs)
           turn-ns (pool-norm turns)
           has-tx? (boolean (some pos? txs))
           has-turn? (boolean (some pos? turns))
           convs (mapv candidate-raw-convergence candidates)
           max-conv (reduce max 0 convs)
           conv-denom (if (pos? max-conv) (Math/log1p (double max-conv)) 1.0)]
       (->> (map-indexed
             (fn [idx c]
               (let [u (candidate-urgency c)
                     r (cond (and has-tx? has-turn?) (max (nth tx-ns idx) (nth turn-ns idx))
                             has-tx? (nth tx-ns idx) has-turn? (nth turn-ns idx) :else 0.0)
                     cv (if (pos? max-conv) (/ (Math/log1p (double (nth convs idx))) conv-denom) 0.0)
                     sem (double (or (nth sems idx) 0.0))
                     boost (candidate-boost c)
                     supp (candidate-suppress c)
                     base (/ (+ (* wu u) (* wr r) (* wc cv) (* wsem sem)) w-sum)
                     score (round4 (clamp01 (+ base boost (- supp))))
                     sigs (cond-> {:urgency (round4 u) :recency (round4 r) :convergence (round4 cv)}
                            has-sem? (assoc :semantic (round4 sem)))]
                 (cond-> (assoc c :rank/score score :rank/signals sigs :rank/weights ws)
                   (not (zero? boost)) (assoc :rank/boost (round4 boost))
                   (pos? supp) (assoc :rank/suppress (round4 supp)))))
             candidates)
            (sort-by (fn [c]
                       [(- (double (:rank/score c 0.0))) (- (candidate-tx c))
                        (- (fmeas/safe-long (:priority c) 50)) (str (or (:entity c) (:id c) ""))]))
            vec)))))

(defn measure-hiccup
  "Pure Hiccup measurement returning `{:tokens :chars :min-width :area :lines}`."
  {:malli/schema [:=> [:cat :any]
                  [:map [:tokens :int] [:chars :int] [:min-width :int] [:area :int] [:lines :int]]]
   :cost/ticks 5000000}
  [node]
  (fmeas/measure-hiccup node))

(defn resolve-slot-budget
  "Computes `{:avail-w :avail-h :max-items :area-budget}` from `{:viewport-w :viewport-h :sidebar-open? :slot}`."
  {:malli/schema [:=> [:cat [:maybe :map]]
                  [:map [:avail-w :int] [:avail-h :int] [:max-items :int] [:area-budget :int]]]
   :cost/ticks 500000}
  [spec]
  (fmeas/resolve-slot-budget spec))

(defn- compute-discovered-renderers [db ks]
  (let [dh (db-schema db)]
    (if-not (and (seq ks) (dh :seon.schema/key) (dh :seon.code.var/input)
                 (dh :seon.code.var/output) (dh :seon.code.var/name))
      []
      (if-let [h-eid (:e (first (d/datoms db :avet :seon.schema/key :seon.ui/hiccup)))]
        (let [out-vs (into #{} (map :e) (d/datoms db :avet :seon.code.var/output h-eid))
              eids (reduce (fn [acc sk]
                             (if-let [se (:e (first (d/datoms db :avet :seon.schema/key sk)))]
                               (reduce #(if (out-vs (:e %2)) (conj %1 (:e %2)) %1)
                                       acc (d/datoms db :avet :seon.code.var/input se))
                               acc))
                           #{} ks)
              pure? (fn [ve]
                      (and (not (and (dh :seon.code.var/ops) (seq (d/datoms db :eavt ve :seon.code.var/ops))))
                           (not (and (dh :seon.code.var/private?) (:v (first (d/datoms db :eavt ve :seon.code.var/private?)))))
                           (not (and (dh :seon.code.var/test?) (:v (first (d/datoms db :eavt ve :seon.code.var/test?)))))
                           (not (and (dh :seon.code.var/flow-edge?) (:v (first (d/datoms db :eavt ve :seon.code.var/flow-edge?)))))))]
          (->> eids (keep #(when (pure? %) (:v (first (d/datoms db :eavt % :seon.code.var/name)))))
               distinct sort vec))
        []))))

(defn discover-renderers
  "Commit-cached Datahike `:avet` reverse-ref discovery of ALL pure `:seon.ui/hiccup` renderers
   accepting `schema-keys`, replaying `datahike.reads` transparently via `store/lru-get!`."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:vector :qualified-symbol]]
   :cost/ticks 5000000}
  [db schema-keys]
  (let [ks (cond (qualified-keyword? schema-keys) [schema-keys]
                 (coll? schema-keys) (vec (distinct (sort (filter qualified-keyword? schema-keys))))
                 :else [])]
    (if (empty? ks)
      []
      (if-let [sig (db-cache-sig db)]
        (store/lru-get! discover-renderers-cache 256 [sig ks] #(compute-discovered-renderers db ks))
        (compute-discovered-renderers db ks)))))

(defn- item-candidates [item]
  (let [raw (:candidates item)]
    (if (and (sequential? raw) (seq raw) (every? map? raw))
      (mapv fmeas/normalize-candidate raw)
      (let [tok (cond (= :unchanged (:status item)) 0
                      (number? (:tokens item)) (max 0 (fmeas/safe-long (:tokens item) 0))
                      :else (let [p (or (:delta-data item) (:data item))]
                              (if (and (map? p) (empty? p)) 0 (max 1 (quot (count (pr-str p)) 4)))))]
        [(fmeas/normalize-candidate
          {:fn (or (:fn item) :default/projection) :hiccup (:hiccup item)
           :tokens tok :chars (* 4 tok)
           :min-width (fmeas/safe-long (:min-width item) 240) :lines (max 1 (quot tok 16))})]))))

(defn- prepare-entry [item target avail-w already-shown]
  (let [cands0 (item-candidates item)
        eid (:entity item)
        cur-tx (candidate-tx item)
        prev (when (and (= target :wire) (map? already-shown) (some? eid)) (get already-shown eid))
        rewarmed? (boolean (and prev (or (> cur-tx (fmeas/safe-long (:tx prev) 0))
                                         (and (some? (:hash item)) (some? (:hash prev))
                                              (not= (:hash item) (:hash prev))))))
        unch-prev? (boolean (and prev (not rewarmed?)))]
    (if (= target :html)
      (let [sorted (vec (sort-by (juxt :area :min-width :tokens #(str (:fn %))) cands0))
            fitting (filterv #(<= (:min-width %) avail-w) sorted)
            usable (if (seq fitting) fitting [(first (sort-by (juxt :min-width :area :tokens) sorted))])]
        {:item item :all-cands sorted :cands usable :base-idx 0 :rewarmed? false
         :unch-prev? false :cost-fn #(long (:area %))})
      (let [sorted (vec (sort-by (juxt :tokens :chars #(str (:fn %))) cands0))
            base-idx (long (or (when unch-prev?
                                 (or (first (keep-indexed #(when (= (:fn %2) (:fn prev)) %1) sorted))
                                     (first (keep-indexed #(when (>= (:tokens %2) (fmeas/safe-long (:tokens prev) 0)) %1) sorted))))
                               0))
            unch-fn (when unch-prev? (:fn (nth sorted base-idx)))]
        {:item item :all-cands sorted :cands sorted :base-idx base-idx
         :rewarmed? rewarmed? :unch-prev? unch-prev?
         :cost-fn #(if (or (= :unchanged (:status item)) (and unch-prev? (= (:fn %) unch-fn)))
                     0 (long (:tokens %)))}))))

(defn- pass2-upgrade [kept used budget strategy]
  (let [n (count kept)
        step (fn [entries u i]
               (let [{:keys [cands idx cost cost-fn] :as e} (nth entries i)
                     ni (inc idx)]
                 (if (< ni (count cands))
                   (let [nc (long (cost-fn (nth cands ni))) delta (- nc cost)]
                     (if (<= (+ u delta) budget)
                       [(assoc entries i (assoc e :idx ni :cost nc)) (+ u delta) true]
                       [entries u false]))
                   [entries u false])))]
    (if (= strategy :depth-first)
      (loop [es kept u used i 0]
        (if (>= i n) [es u]
            (let [[es' u' mv?] (step es u i)]
              (if mv? (recur es' u' i) (recur es u (inc i))))))
      (loop [es kept u used]
        (let [[es' u' any?] (reduce (fn [[acc cu mv?] i]
                                      (let [[acc' cu' m?] (step acc cu i)] [acc' cu' (or mv? m?)]))
                                    [es u false] (range n))]
          (if any? (recur es' u') [es' u']))))))

(defn- finalize-entry [{:keys [item all-cands cands base-idx idx cost rewarmed? unch-prev?]} target avail-w]
  (let [chosen (nth cands idx)
        base-c (nth cands base-idx)
        status (if (= target :html)
                 :fitted
                 (cond (= :unchanged (:status item)) :unchanged
                       unch-prev? (if (zero? cost) :unchanged :upgraded)
                       rewarmed? :rewarmed
                       (some? (:status item)) (:status item)
                       :else :new))]
    (assoc item
           :chosen chosen :chosen-fn (:fn chosen) :hiccup (:hiccup chosen)
           :base-fn (:fn base-c) :base-tokens (long (:tokens base-c)) :base-area (long (:area base-c))
           :tokens (if (= target :wire) (long cost) (long (:tokens chosen)))
           :measured-tokens (long (:tokens chosen))
           :min-width (long (:min-width chosen)) :area (long (:area chosen))
           :chars (long (:chars chosen)) :lines (long (:lines chosen))
           :status status
           :candidates (mapv #(-> (select-keys % [:fn :tokens :min-width :area])
                                  (assoc :fits-width? (<= (:min-width %) avail-w)))
                             all-cands))))

(defn upgrade-ladder
  "Unified 2-pass Complete-Thought Upgrade Ladder for `:target :wire` and `:target :html`."
  {:malli/schema [:=> [:cat [:vector :map] [:? [:maybe :map]]]
                  [:map [:selected [:vector :map]] [:dropped [:vector :map]]
                   [:tokens :int] [:area :int] [:target :keyword] [:strategy :keyword] [:shown :map]]]
   :cost/ticks 15000000}
  ([ranked-items] (upgrade-ladder ranked-items nil))
  ([ranked-items opts]
   (let [o (if (map? opts) opts {})
         target (if (= :html (:target o)) :html :wire)
         strategy (if (= :depth-first (:strategy o)) :depth-first :breadth-first)
         slot-b (when (= target :html) (resolve-slot-budget o))
         avail-w (long (if (= target :html) (:avail-w slot-b) 10000))
         budget (long (if (= target :html) (:area-budget slot-b) (max 0 (fmeas/safe-long (:token-budget o) 16000))))
         max-items (long (if (= target :html) (:max-items slot-b) (max 0 (fmeas/safe-long (:max-items o) (count ranked-items)))))
         shown0 (if (map? (:already-shown o)) (:already-shown o) {})
         prepared (mapv #(prepare-entry % target avail-w shown0) (take max-items ranked-items))
         {:keys [kept dropped used]}
         (reduce (fn [acc e]
                   (let [idx (:base-idx e) c0 (nth (:cands e) idx) cost0 (long ((:cost-fn e) c0))]
                     (if (or (zero? cost0) (<= (+ (:used acc) cost0) budget))
                       (-> acc (update :kept conj (assoc e :idx idx :cost cost0)) (update :used + cost0))
                       (update acc :dropped conj
                               (assoc (:item e) :tokens (long (:tokens c0)) :area (long (:area c0))
                                      :all-cands (:all-cands e))))))
                 {:kept [] :dropped [] :used 0} prepared)
         [upgraded final-used] (pass2-upgrade kept used budget strategy)
         finalized (mapv #(finalize-entry % target avail-w) upgraded)
         ordered (if (= target :wire)
                   (vec (sort-by (fn [p] [(get zone-order (:zone p) 2) (- (double (or (:rank/score p) 0.0)))
                                          (- (fmeas/safe-long (:priority p) 50)) (str (or (:entity p) (:id p) ""))])
                                 finalized))
                   finalized)
         next-shown (if (= target :wire)
                      (reduce (fn [m p]
                                (if-let [eid (:entity p)]
                                  (let [prev (get m eid)]
                                    (if (= :unchanged (:status p))
                                      (assoc m eid prev)
                                      (assoc m eid {:fn (:chosen-fn p)
                                                    :tokens (long (:measured-tokens p (:tokens p 0)))
                                                    :hash (:hash p)
                                                    :tx (max (fmeas/safe-long (:tx prev) 0) (candidate-tx p))})))
                                  m))
                              shown0 ordered)
                      shown0)]
     {:selected ordered
      :dropped (into dropped (drop max-items ranked-items))
      :tokens (long (reduce + 0 (map #(long (:tokens % 0)) ordered)))
      :area (long (reduce + 0 (map #(long (:area % 0)) ordered)))
      :used (long final-used) :avail-w avail-w
      :budget budget :slot-budget slot-b :target target :strategy strategy :shown next-shown})))

(defn- resolve-renderer-var [vsym]
  (when (qualified-symbol? vsym)
    (when-let [v (or (when-let [ns-obj (find-ns (symbol (namespace vsym)))]
                       (ns-resolve ns-obj (symbol (name vsym))))
                     (requiring-resolve vsym))]
      (when (bound? v) v))))

(defn- renderer-input-schema [v]
  (let [ms (:malli/schema (meta v))
        ar1 (cond (and (vector? ms) (= :=> (first ms))) ms
                  (and (vector? ms) (= :function (first ms)))
                  (some #(when (and (vector? %) (= :=> (first %))
                                    (vector? (second %)) (= :cat (first (second %)))
                                    (= 2 (count (second %)))) %)
                        (rest ms))
                  :else nil)]
    (when (and (vector? ar1) (vector? (second ar1)) (= :cat (first (second ar1))) (= 2 (count (second ar1))))
      (second (second ar1)))))

(defn- cached-renderer-validator [reg-info rsym v]
  (let [in-s (renderer-input-schema v)
        reg (:registry reg-info)]
    (when (and reg in-s)
      (let [res (store/lru-get! validator-cache 256 [(:reg-sig reg-info) rsym in-s]
                                #(or (m/validator in-s {:registry reg}) ::no-validator))]
        (when-not (identical? ::no-validator res) res)))))

(defn- entity-schema-keys [ent-idx item ent-data]
  (let [explicit (:schema item)]
    (cond
      (qualified-keyword? explicit) [explicit]
      (and (coll? explicit) (seq explicit)) (vec (distinct (filter qualified-keyword? explicit)))
      :else
      (let [meta-sk (when (instance? clojure.lang.IObj ent-data) (:seon/schema (meta ent-data)))
            facets (when (and (map? ent-data) (seq ent-data) ent-idx)
                     (map #(if (map? %) (:schema %) %) (six/facets-of ent-idx ent-data)))]
        (->> (concat (when (qualified-keyword? meta-sk) [meta-sk]) facets)
             (filter qualified-keyword?) distinct vec)))))

(defn- normalize-entity-for-malli [db ent]
  (let [dh (db-schema db)]
    (if-not (and db (map? ent) (seq dh))
      ent
      (into {} (map (fn [[k v]]
                      [k (if (and (= :db.cardinality/many (get-in dh [k :db/cardinality])) (sequential? v))
                           (set v) v)]))
            ent))))

(defn- invoke-discovered-renderers [db reg-info item ent-data sks extra-ctx fallback-map]
  (let [disc (discover-renderers db sks)
        rsyms (if (seq disc) disc
                  (when (map? fallback-map) (vec (distinct (mapcat #(get fallback-map %) sks)))))
        norm-ent (normalize-entity-for-malli db ent-data)
        non-nil-ctx (into {} (filter (fn [[_ v]] (some? v))) (merge extra-ctx (:params item)))]
    (vec
     (keep (fn [rsym]
             (when-let [v (resolve-renderer-var rsym)]
               (let [in-map (reduce #(assoc %1 %2 norm-ent) non-nil-ctx sks)
                     vf (cached-renderer-validator reg-info rsym v)]
                 (when (or (nil? vf) (vf in-map))
                   (let [res (@v in-map)
                         hiccup (cond (and (map? res) (contains? res :seon.ui/hiccup)) (:seon.ui/hiccup res)
                                      (vector? res) res :else nil)]
                     (when (some? hiccup)
                       (let [m (measure-hiccup hiccup)
                             ow (when (map? res) (fmeas/parse-px-hint (:seon.ui/min-width res)))]
                         (cond-> (assoc m :fn rsym :hiccup hiccup)
                           (some? ow) (assoc :min-width ow :area (* ow (max 20 (* (max 1 (:lines m)) 20))))))))))))
           rsyms))))

(defn- populate-candidates [db items opts]
  (let [o (if (map? opts) opts {})
        slot-b (when (= :html (:target o)) (resolve-slot-budget o))
        extra-ctx (cond-> {}
                    (:slot o) (assoc :seon.ui/slot (:slot o))
                    (:avail-w slot-b) (assoc :seon.ui/avail-w (:avail-w slot-b)))
        fb-map (:fallback-renderers o)
        reg-info (cached-registry-info db)
        ent-idx (:entity-index reg-info)]
    (mapv (fn [item]
            (let [eid (:entity item)
                  ent-data (cond (and (map? (:data item)) (seq (:data item))) (:data item)
                                 (integer? eid) (d/pull db '[*] eid)
                                 :else {})
                  sks (entity-schema-keys ent-idx item ent-data)]
              (if (seq (:candidates item))
                (cond-> (assoc item :data ent-data) (seq sks) (assoc :schemas sks))
                (let [cands (when (seq sks)
                              (invoke-discovered-renderers db reg-info item ent-data sks extra-ctx fb-map))]
                  (cond-> (assoc item :data ent-data :schemas sks)
                    (seq cands) (assoc :candidates cands))))))
          items)))

(defn fit-projections
  "Ranks `projections`, pulls each entity once, discovers & invokes pure `:seon.ui/hiccup` renderers
   via commit-cached `discover-renderers` + `validator-cache`, and runs `upgrade-ladder`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:vector :map]] :map]
                  [:=> [:cat :seon.host/db [:vector :map] [:maybe :map]] :map]]
   :cost/ticks 30000000}
  ([db projections] (fit-projections db projections nil))
  ([db projections opts]
   (let [o (if (map? opts) opts {})
         r-opts (cond-> (select-keys o [:turn])
                  (or (:weights o) (nil? (:turn o))) (assoc :weights (or (:weights o) (read-rank-weights db))))
         ranked (rank-candidates projections r-opts)
         with-cands (populate-candidates db ranked o)]
     (upgrade-ladder with-cands o))))

(defn explain-candidates
  "Explains ranking signals, modifiers, provenance, and EVERY rendered variation + selection/skip
   reason for `candidates` under `opts` (`:target :wire` or `:html`)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:vector :map]] :map]
                  [:=> [:cat :seon.host/db [:vector :map] [:maybe :map]] :map]]
   :cost/ticks 40000000}
  ([db candidates] (explain-candidates db candidates nil))
  ([db candidates opts]
   (let [o (if (map? opts) opts {})
         text-fn (fmeas/resolve-text-fn o)
         r-opts (cond-> (select-keys o [:turn])
                  (or (:weights o) (nil? (:turn o))) (assoc :weights (or (:weights o) (read-rank-weights db))))
         ranked (rank-candidates candidates r-opts)
         with-cands (populate-candidates db ranked o)
         raw-by-ent (into {} (map (fn [it] [(:entity it) (item-candidates it)])) with-cands)
         ladder (upgrade-ladder with-cands o)
         target (:target ladder)
         avail-w (long (:avail-w ladder 10000))
         budget (long (:budget ladder 0))
         used (long (:used ladder 0))
         remaining (max 0 (- budget used))
         ex-sel (mapv #(fmeas/build-explained-item % (get raw-by-ent (:entity %) (item-candidates %))
                                                   false target avail-w remaining text-fn)
                      (:selected ladder))
         ex-drop (mapv #(fmeas/build-explained-item % (or (:all-cands %) (get raw-by-ent (:entity %) (item-candidates %)))
                                                    true target avail-w remaining text-fn)
                       (:dropped ladder))]
     {:target target :strategy (:strategy ladder)
      :budget budget :used used :remaining remaining
      :avail-w avail-w :slot-budget (:slot-budget ladder)
      :tokens (:tokens ladder) :area (:area ladder)
      :selected-count (count ex-sel) :dropped-count (count ex-drop)
      :items (vec (concat ex-sel ex-drop))
      :selected ex-sel :dropped ex-drop})))

(defn explain-entity-fit
  "Explains ALL discovered renderer variations, measurements, text previews, and fit reasons for
   a single entity (`eid`, lookup ref `[attr val]`, candidate map, or entity map)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :any] :map]
                  [:=> [:cat :seon.host/db :any [:maybe :map]] :map]]
   :cost/ticks 25000000}
  ([db item-or-eid] (explain-entity-fit db item-or-eid nil))
  ([db item-or-eid opts]
   (let [item (cond
                (integer? item-or-eid) {:entity item-or-eid}
                (and (vector? item-or-eid) (= 2 (count item-or-eid)) (qualified-keyword? (first item-or-eid)))
                (let [e (:e (first (d/datoms db :avet (first item-or-eid) (second item-or-eid))))]
                  {:entity (or e item-or-eid) :ref item-or-eid})
                (and (map? item-or-eid) (contains? item-or-eid :entity)) item-or-eid
                (map? item-or-eid) {:entity (or (:db/id item-or-eid) :entity/inline) :data item-or-eid}
                :else {:entity item-or-eid})
         res (explain-candidates db [item] opts)]
     (merge (select-keys res [:target :strategy :budget :used :remaining :avail-w :slot-budget])
            (or (first (:items res)) {:entity (:entity item) :variations [] :selected? false})))))
