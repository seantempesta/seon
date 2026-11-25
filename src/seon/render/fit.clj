(ns seon.render.fit
  "Pure 4-signal self-balancing candidate ranking (urgency, recency, convergence, optional semantic)
   with rule boost/suppress modifiers, batched `code-db-sig` Datahike `:avet` renderer discovery,
   unified 2-pass Upgrade Ladder, and candidate/variation explainer."
  (:require [datahike.api :as d]
            [malli.core :as m]
            [seon.ctx.diff :as diff]
            [seon.render.fit.measure :as fmeas]
            [seon.render.text :as rtext]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store :as store :refer [db-schema]]
            [clojure.core.cache :as cache]))

(def default-rank-weights
  "Default tunable weights for the native Datahike ranking signals."
  {:urgency 1.0 :recency 0.75 :convergence 0.5})

(def ^:private zone-order
  {:static 0 :static-prefix 0 :canvas 1 :workspace 2 :debug 3 :ephemeral-tail 4})

(defonce ^:private ^:seon.flow/edge discover-all-renderers-cache (store/lru-cache 32))

(defonce ^:private ^:seon.flow/edge validator-cache (store/lru-cache 256))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (fmeas/finite-double x 0.0))))

(defn cached-registry-info
  "Returns commit-anchored `schema/registry-for-db` enriched with `:reg-sig`, replaying `datahike.reads`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :map] :cost/ticks 2000000}
  [db]
  (let [ri (schema/registry-for-db db)]
    (if (:reg-sig ri)
      ri
      (assoc ri :reg-sig [(System/identityHashCode (:registry ri)) (count (:forms ri))]))))

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
         base (if (pos? (+ wu wr wc wsem)) {:urgency wu :recency wr :convergence wc} default-rank-weights)]
     (cond-> base
       (or has-sem? has-wsem?)
       (assoc :semantic (if has-sem? wsem (max 0.0 (fmeas/finite-double (:semantic wm) 0.0))))))))

(defn read-rank-weights
  "Reads `:seon.config/rank-weights` from `db` (falling back to `default-rank-weights`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :seon.config/rank-weights] :cost/ticks 500000}
  [db]
  (store/without-agent-eval-reads
   (fn []
     (let [dh (db-schema db)]
       (if-not (and db (contains? dh :seon.config/rank-weights))
         default-rank-weights
         (let [root-w (when (contains? dh :seon.config/key)
                        (:seon.config/rank-weights
                         (d/pull db '[:seon.config/rank-weights] [:seon.config/key :seon.config/root])))]
           (normalize-weights (or root-w (:v (first (d/datoms db :aevt :seon.config/rank-weights)))) false)))))))

(defn- candidate-tx [c]
  (max 0 (fmeas/safe-long (or (:tx c) (:max-tx c) (when (map? (:data c)) (:tx (:data c)))) 0)))

(defn- candidate-turn [c]
  (max 0 (fmeas/safe-long (or (:turn c) (when (map? (:data c)) (:seon.agent.eval/turn (:data c)))) 0)))

(defn- candidate-semantic [c]
  (let [v (or (:semantic c) (:semantic/score c) (:ctx.match/score c))]
    (when (number? v) (clamp01 v))))

(defn- candidate-boost [c]
  (let [v (or (:rank/boost c) (get-in c [:params :rank/boost]))]
    (if (number? v) (max -1.0 (min 1.0 (fmeas/finite-double v 0.0))) 0.0)))

(defn- candidate-suppress [c]
  (let [v (or (:rank/suppress c) (get-in c [:params :rank/suppress]))]
    (if (number? v) (clamp01 v) 0.0)))

(defn rank-candidates
  "Pure 4-signal self-balancing rank over candidate entity maps: urgency/anchor, native `?tx`/turn
   recency, cumulative touches/convergence, and optional `:semantic` signal."
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
           wu (double (:urgency ws)) wr (double (:recency ws)) wc (double (:convergence ws))
           wsem (if has-sem? (double (:semantic ws 0.0)) 0.0)
           w-sum (let [s (+ wu wr wc wsem)] (if (pos? s) s 1.0))
           txs (mapv candidate-tx candidates) turns (mapv candidate-turn candidates)
           tx-ns (fmeas/pool-norm txs) turn-ns (fmeas/pool-norm turns)
           has-tx? (boolean (some pos? txs)) has-turn? (boolean (some pos? turns))
           convs (mapv fmeas/candidate-raw-convergence candidates)
           max-conv (reduce max 0 convs)
           conv-denom (if (pos? max-conv) (Math/log1p (double max-conv)) 1.0)]
       (->> (map-indexed
             (fn [idx c]
               (let [u (fmeas/candidate-urgency c)
                     r (cond (and has-tx? has-turn?) (max (nth tx-ns idx) (nth turn-ns idx))
                             has-tx? (nth tx-ns idx) has-turn? (nth turn-ns idx) :else 0.0)
                     cv (if (pos? max-conv) (/ (Math/log1p (double (nth convs idx))) conv-denom) 0.0)
                     sem (double (or (nth sems idx) 0.0))
                     boost (candidate-boost c) supp (candidate-suppress c)
                     base (/ (+ (* wu u) (* wr r) (* wc cv) (* wsem sem)) w-sum)
                     score (rtext/round4 (clamp01 (+ base boost (- supp))))
                     sigs (cond-> {:urgency (rtext/round4 u) :recency (rtext/round4 r) :convergence (rtext/round4 cv)}
                            has-sem? (assoc :semantic (rtext/round4 sem)))]
                 (cond-> (assoc c :rank/score score :rank/signals sigs :rank/weights ws)
                   (not (zero? boost)) (assoc :rank/boost (rtext/round4 boost))
                   (pos? supp) (assoc :rank/suppress (rtext/round4 supp)))))
             candidates)
            (sort-by (fn [c]
                       [(- (double (:rank/score c 0.0))) (- (candidate-tx c))
                        (- (fmeas/safe-long (:priority c) 50)) (str (or (:entity c) (:id c) ""))]))
            vec)))))

(defn measure-hiccup
  "Pure Hiccup measurement returning `{:tokens :chars :min-width :area :lines}`."
  {:malli/schema [:=> [:cat :any] [:map [:tokens :int] [:chars :int] [:min-width :int] [:area :int] [:lines :int]]]
   :cost/ticks 5000000}
  [node] (fmeas/measure-hiccup node))

(defn resolve-slot-budget
  "Computes `{:avail-w :avail-h :max-items :area-budget}` from `{:viewport-w :viewport-h :sidebar-open? :slot}`."
  {:malli/schema [:=> [:cat [:maybe :map]] [:map [:avail-w :int] [:avail-h :int] [:max-items :int] [:area-budget :int]]]
   :cost/ticks 500000}
  [spec] (fmeas/resolve-slot-budget spec))

(defn- compute-all-renderers-map [db]
  (let [dh (db-schema db)]
    (if-not (and db (dh :seon.schema/key) (dh :seon.code.var/input)
                 (dh :seon.code.var/output) (dh :seon.code.var/name))
      {}
      (if-let [h-eid (:e (first (d/datoms db :avet :seon.schema/key :seon.ui/hiccup)))]
        (let [has-ops? (boolean (dh :seon.code.var/ops))
              has-priv? (boolean (dh :seon.code.var/private?))
              has-test? (boolean (dh :seon.code.var/test?))
              has-edge? (boolean (dh :seon.code.var/flow-edge?))
              pure? (fn [ve]
                      (and (not (and has-ops? (seq (d/datoms db :eavt ve :seon.code.var/ops))))
                           (not (and has-priv? (:v (first (d/datoms db :eavt ve :seon.code.var/private?)))))
                           (not (and has-test? (:v (first (d/datoms db :eavt ve :seon.code.var/test?)))))
                           (not (and has-edge? (:v (first (d/datoms db :eavt ve :seon.code.var/flow-edge?)))))))
              se->sk (java.util.HashMap. 64)
              get-sk (fn [se]
                       (if (.containsKey se->sk se)
                         (.get se->sk se)
                         (let [k (:v (first (d/datoms db :eavt se :seon.schema/key)))]
                           (.put se->sk se k) k)))
              by-sk (reduce (fn [acc dt]
                              (let [ve (:e dt)]
                                (if-not (pure? ve)
                                  acc
                                  (if-let [vname (:v (first (d/datoms db :eavt ve :seon.code.var/name)))]
                                    (reduce (fn [m idt]
                                              (if-let [sk (get-sk (:v idt))]
                                                (update m sk (fnil conj #{}) vname)
                                                m))
                                            acc (d/datoms db :eavt ve :seon.code.var/input))
                                    acc))))
                            {} (d/datoms db :avet :seon.code.var/output h-eid))]
          (reduce-kv (fn [m sk syms] (assoc m sk (vec (sort syms)))) {} by-sk))
        {}))))

(defn discover-all-renderers
  "Single-scan `code-db-sig`-cached map `{schema-key [sorted-pure-renderer-syms ...]}` for ALL `:seon.ui/hiccup` renderers."
  {:malli/schema [:=> [:cat :seon.host/db] :map] :cost/ticks 5000000}
  [db]
  (store/without-agent-eval-reads
   (fn []
     (if-let [sig (diff/code-db-sig db)]
       (store/lru-get! discover-all-renderers-cache 32 sig #(compute-all-renderers-map db))
       (compute-all-renderers-map db)))))

(defn discover-renderers
  "O(1) `code-db-sig`-cached lookup of ALL pure `:seon.ui/hiccup` renderers accepting `schema-keys`."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:vector :qualified-symbol]] :cost/ticks 5000000}
  [db schema-keys]
  (let [ks (cond (qualified-keyword? schema-keys) [schema-keys]
                 (coll? schema-keys) (vec (distinct (filter qualified-keyword? schema-keys)))
                 :else [])]
    (if (empty? ks)
      []
      (let [all-m (discover-all-renderers db)]
        (if (= 1 (count ks))
          (get all-m (first ks) [])
          (->> ks (mapcat #(get all-m %)) distinct sort vec))))))

(defn- prepare-entry [item target avail-w already-shown]
  (let [cands0 (fmeas/item-candidates item)
        eid (:entity item) cur-tx (candidate-tx item)
        prev (when (and (= target :wire) (map? already-shown) (some? eid)) (get already-shown eid))
        rewarmed? (boolean (and prev (or (> cur-tx (fmeas/safe-long (:tx prev) 0))
                                         (and (some? (:hash item)) (some? (:hash prev)) (not= (:hash item) (:hash prev))))))
        unch-prev? (boolean (and prev (not rewarmed?)))]
    (if (= target :html)
      (let [sorted (vec (sort-by (juxt :area :min-width :tokens #(str (:fn %))) cands0))
            fitting (filterv #(and (<= (:min-width %) avail-w) (not (:overflow-x? %))) sorted)
            usable (if (seq fitting) fitting [(first (sort-by (juxt :min-width :area :tokens) sorted))])]
        {:item item :all-cands sorted :cands usable :base-idx 0 :rewarmed? false :unch-prev? false
         :locked-unchanged? false :explicit-new-view? false :cost-fn #(long (:area %))})
      (let [sorted (vec (sort-by (juxt :tokens :chars #(str (:fn %))) cands0))
            prev-fns (when unch-prev? (into (if (:fn prev) #{(:fn prev)} #{}) (:fns prev)))
            req-fn (when (qualified-symbol? (:fn item)) (:fn item))
            req-idx (when (and unch-prev? req-fn (not (contains? prev-fns req-fn)))
                      (first (keep-indexed #(when (= (:fn %2) req-fn) %1) sorted)))
            new-view? (boolean (some? req-idx))
            locked? (boolean (or (= :unchanged (:status item)) (and unch-prev? (not new-view?))))
            base-idx (long (or req-idx
                               (when unch-prev?
                                 (or (first (keep-indexed #(when (= (:fn %2) (:fn prev)) %1) sorted))
                                     (first (keep-indexed #(when (>= (:tokens %2) (fmeas/safe-long (:tokens prev) 0)) %1) sorted))))
                               0))]
        {:item item :all-cands sorted :cands sorted :base-idx base-idx :rewarmed? rewarmed? :unch-prev? unch-prev?
         :locked-unchanged? locked? :explicit-new-view? new-view?
         :cost-fn (if locked? (constantly 0) #(long (:tokens %)))}))))

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
         per-item (let [p (:per-item-budget o)]
                    (when (number? p)
                      (let [pl (fmeas/safe-long p 0)] (when (pos? pl) pl))))
         max-items (long (if (= target :html) (:max-items slot-b) (max 0 (fmeas/safe-long (:max-items o) (count ranked-items)))))
         shown0 (if (map? (:already-shown o)) (:already-shown o) {})
         prepared (mapv #(prepare-entry % target avail-w shown0) (take max-items ranked-items))
         {:keys [kept dropped used]}
         (reduce (fn [acc e]
                   (let [idx (:base-idx e) c0 (nth (:cands e) idx) cost0 (long ((:cost-fn e) c0))]
                     (if (or (zero? cost0) (<= (+ (:used acc) cost0) budget))
                       (-> acc (update :kept conj (assoc e :idx idx :cost cost0)) (update :used + cost0))
                       (update acc :dropped conj (assoc (:item e) :tokens (long (:tokens c0)) :area (long (:area c0)) :all-cands (:all-cands e))))))
                 {:kept [] :dropped [] :used 0} prepared)
         [upgraded final-used] (fmeas/pass2-upgrade kept used budget strategy per-item target)
         finalized (mapv #(fmeas/finalize-entry % target avail-w) upgraded)
         ordered (if (= target :wire)
                   (vec (sort-by (fn [p] [(get zone-order (:zone p) 2) (- (double (or (:rank/score p) 0.0)))
                                          (- (fmeas/safe-long (:priority p) 50)) (str (or (:entity p) (:id p) ""))])
                                 finalized))
                   finalized)
         next-shown (if (= target :wire)
                      (reduce (fn [m p]
                                (if-let [eid (:entity p)]
                                  (let [prev (get m eid) cfn (:chosen-fn p)
                                        pfns (into (if (:fn prev) #{(:fn prev)} #{}) (:fns prev))]
                                    (if (= :unchanged (:status p))
                                      (assoc m eid prev)
                                      (assoc m eid {:fn cfn :fns (if (= :rewarmed (:status p)) #{cfn} (conj pfns cfn))
                                      :tokens (long (:measured-tokens p (:tokens p 0)))
                                      :turn (long (or (:turn o) (:turn prev) 0))
                                      :hash (:hash p) :tx (max (fmeas/safe-long (:tx prev) 0) (candidate-tx p))})))
                                  m))
                              shown0 ordered)
                      shown0)]
     (cond-> {:selected ordered :dropped (into dropped (drop max-items ranked-items))
              :tokens (long (reduce + 0 (map #(long (:tokens % 0)) ordered)))
              :area (long (reduce + 0 (map #(long (:area % 0)) ordered)))
              :used (long final-used) :avail-w avail-w :budget budget :slot-budget slot-b
              :target target :strategy strategy :shown next-shown}
       per-item (assoc :per-item-budget per-item)))))

(defn ^:seon.flow/edge resolve-renderer-var
  "Resolves a qualified renderer symbol from loaded namespaces or classpath, returning nil cleanly if unloaded."
  {:malli/schema [:=> [:cat :any] :any] :cost/ticks 200000}
  [vsym]
  (when (qualified-symbol? vsym)
    (when-let [v (or (when-let [ns-obj (find-ns (symbol (namespace vsym)))]
                       (ns-resolve ns-obj (symbol (name vsym))))
                     (try (requiring-resolve vsym) (catch Throwable _ nil)))]
      (when (bound? v) v))))

(defn- renderer-input-schema [v]
  (let [ms (:malli/schema (meta v))
        ar1 (cond (and (vector? ms) (= :=> (first ms))) ms
                  (and (vector? ms) (= :function (first ms)))
                  (some #(when (and (vector? %) (= :=> (first %)) (vector? (second %))
                                    (= :cat (first (second %))) (= 2 (count (second %)))) %) (rest ms))
                  :else nil)]
    (when (and (vector? ar1) (vector? (second ar1)) (= :cat (first (second ar1))) (= 2 (count (second ar1))))
      (second (second ar1)))))

(defn- cached-renderer-validator [reg-info rsym v]
  (let [in-s (renderer-input-schema v) reg (:registry reg-info)]
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

(defn normalize-entity-for-malli
  "Normalizes a Datahike entity map for Malli validation: converts `:db.cardinality/many` or `:set`
   values to sets and unwraps single-key `{:db/id n}` ref maps to integer eids."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any [:? [:maybe :map]] [:? [:maybe :map]]] :any]
   :cost/ticks 2000000}
  ([db ent] (normalize-entity-for-malli db ent nil nil))
  ([db ent forms] (normalize-entity-for-malli db ent forms nil))
  ([db ent forms main-dh]
   (fmeas/normalize-entity-map (when db (db-schema db)) ent forms main-dh)))

(defn- lookup-form-hash [db main-db rsym]
  (store/without-agent-eval-reads
   (fn []
     (let [from-db (fn [d*]
                     (when d*
                       (let [dh (db-schema d*)]
                         (when (and (contains? dh :seon.code.form/hash)
                                    (contains? dh :seon.code.form/key))
                           (when-let [fe (:e (first (d/datoms d* :avet :seon.code.form/key (str rsym))))]
                             (:v (first (d/datoms d* :eavt fe :seon.code.form/hash))))))))]
       (or (from-db db)
           (when (and main-db (not= main-db db)) (from-db main-db)))))))

(defonce ^:private ^:seon.flow/edge browser-box-cache (store/lru-cache 512))

(defn ^:seon.flow/edge cached-browser-box
  "O(1) lookup of the best recorded browser box for `[var-sym form-hash]` at `avail-w`
   (exact `slot-w`, else largest `slot-w <= avail-w`, else smallest `slot-w`)."
  {:malli/schema [:=> [:cat :qualified-symbol :any [:maybe :int]] [:maybe :map]]
   :cost/ticks 100000}
  [var-sym form-hash avail-w]
  (when-not (satisfies? cache/CacheProtocol @browser-box-cache)
    (reset! browser-box-cache (cache/lru-cache-factory {} :threshold 512)))
  (let [k [var-sym form-hash]
        by-w (get @browser-box-cache k)]
    (when (and (map? by-w) (seq by-w))
      (swap! browser-box-cache cache/hit k)
      (fmeas/select-browser-box by-w (fmeas/safe-long avail-w 10000)))))

(defn- invoke-discovered-renderers [db reg-info item ent-data sks extra-ctx fallback-map main-db avail-w browser-boxes]
  (let [req-fn (when (qualified-symbol? (:fn item)) (:fn item))
        disc (seq (distinct (concat (discover-renderers db sks) (when (and main-db (not= main-db db)) (discover-renderers main-db sks)))))
        fb-syms (when (map? fallback-map) (vec (distinct (mapcat #(get fallback-map %) sks))))
        main-dh (when main-db (db-schema main-db))
        norm-ent (normalize-entity-for-malli db ent-data (:forms reg-info) main-dh)
        non-nil-ctx (into {} (filter (fn [[_ v]] (some? v))) (merge extra-ctx (:params item)))
        has-cache? (boolean (seq @browser-box-cache))
        run-syms (fn [rsyms]
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
                                            ow (when (map? res) (fmeas/parse-px-hint (:seon.ui/min-width res)))
                                            m1 (cond-> m (some? ow) (assoc :min-width ow :area (* ow (max 20 (* (max 1 (:lines m)) 20)))))
                                            bbox (or (when (map? browser-boxes) (get browser-boxes rsym))
                                                     (when has-cache? (cached-browser-box rsym (lookup-form-hash db main-db rsym) avail-w)))]
                                        (assoc (fmeas/apply-browser-box m1 bbox avail-w) :fn rsym :hiccup hiccup))))))))
                          rsyms)))
        explicit (when req-fn (run-syms [req-fn]))]
    (if (seq explicit) explicit (run-syms (if (seq disc) (vec disc) fb-syms)))))

(defn- populate-candidates [db items opts]
  (let [o (if (map? opts) opts {})
        wire? (not= :html (:target o))
        shown-map (when (and wire? (not (:explain? o)) (map? (:already-shown o))) (:already-shown o))
        slot-b (when (= :html (:target o)) (resolve-slot-budget o))
        avail-w (long (or (:avail-w slot-b) (fmeas/safe-long (:avail-w o) 10000)))
        browser-boxes (when (map? (:browser-boxes o)) (:browser-boxes o))
        extra-ctx {}
        fb-map (:fallback-renderers o) main-db (:main-db o)
        reg-info (let [r-db (cached-registry-info db)
                       r-m (when (and main-db (not= main-db db)) (cached-registry-info main-db))]
                   (if (and r-m (> (count (:forms r-m)) (count (:forms r-db)))) r-m r-db))
        ent-idx (:entity-index reg-info)]
    (mapv (fn [item]
            (let [eid (:entity item)
                  prev (when (and shown-map (some? eid)) (get shown-map eid))
                  req-fn (when (qualified-symbol? (:fn item)) (:fn item))
                  prev-fns (when prev (into (if (:fn prev) #{(:fn prev)} #{}) (:fns prev)))
                  skip-unch? (boolean (and prev
                                           (<= (candidate-tx item) (fmeas/safe-long (:tx prev) 0))
                                           (or (nil? (:hash item)) (nil? (:hash prev)) (= (:hash item) (:hash prev)))
                                           (or (nil? req-fn) (contains? prev-fns req-fn))))]
              (if skip-unch?
                (assoc item :status :unchanged
                       :candidates [{:fn (or (:fn prev) :default/projection) :hiccup nil
                                     :tokens (fmeas/safe-long (:tokens prev) 0) :min-width 0 :area 0 :chars 0 :lines 0}])
                (let [ent-data (cond (and (map? (:data item)) (seq (:data item))) (:data item)
                                     (integer? eid) (d/pull db (quote [*]) eid) :else {})
                      sks (entity-schema-keys ent-idx item ent-data)]
                  (if (seq (:candidates item))
                    (cond-> (assoc item :data ent-data) (seq sks) (assoc :schemas sks))
                    (let [cands (when (seq sks)
                                  (invoke-discovered-renderers db reg-info item ent-data sks extra-ctx fb-map main-db avail-w browser-boxes))]
                      (cond-> (assoc item :data ent-data :schemas sks) (seq cands) (assoc :candidates cands))))))))
          items)))

(defn fit-projections
  "Ranks `projections`, pulls each entity once, discovers & invokes pure `:seon.ui/hiccup` renderers, and runs `upgrade-ladder`."
  {:malli/schema [:function [:=> [:cat :seon.host/db [:vector :map]] :map]
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
  "Explains ranking signals, modifiers, provenance, and EVERY rendered variation + selection/skip reason."
  {:malli/schema [:function [:=> [:cat :seon.host/db [:vector :map]] :map]
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
         raw-by-ent (into {} (map (fn [it] [(:entity it) (fmeas/item-candidates it)])) with-cands)
         ladder (upgrade-ladder with-cands o)
         target (:target ladder) avail-w (long (:avail-w ladder 10000))
         budget (long (:budget ladder 0)) used (long (:used ladder 0)) remaining (max 0 (- budget used))
         pib (when (> (count (:selected ladder)) 1) (:per-item-budget ladder))
         ex-sel (mapv #(fmeas/build-explained-item
                        (cond-> % pib (assoc :per-item-budget pib))
                        (get raw-by-ent (:entity %) (fmeas/item-candidates %)) false target avail-w remaining text-fn)
                      (:selected ladder))
         ex-drop (mapv #(fmeas/build-explained-item % (or (:all-cands %) (fmeas/item-candidates %)) true target avail-w remaining text-fn) (:dropped ladder))]
     {:target target :strategy (:strategy ladder) :budget budget :used used :remaining remaining
      :avail-w avail-w :slot-budget (:slot-budget ladder) :tokens (:tokens ladder) :area (:area ladder)
      :selected-count (count ex-sel) :dropped-count (count ex-drop)
      :items (vec (concat ex-sel ex-drop)) :selected ex-sel :dropped ex-drop})))

(defn explain-entity-fit
  "Explains ALL discovered renderer variations, measurements, text previews, and fit reasons for a single entity."
  {:malli/schema [:function [:=> [:cat :seon.host/db :any] :map]
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

(defn ^:seon.flow/edge clear-browser-box-cache!
  "Clears the bounded in-memory browser box cache (0 LMDB writes)."
  {:malli/schema [:=> [:cat] :boolean]
   :cost/ticks 50000}
  []
  (reset! browser-box-cache (cache/lru-cache-factory {} :threshold 512))
  true)

(defn ^:seon.flow/edge record-browser-box!
  "Records a browser-measured `{:w :h :area :overflow-x?}` box for `[var-sym form-hash]` at `slot-w`
   into the bounded in-memory `browser-box-cache` (0 LMDB writes)."
  {:malli/schema [:=> [:cat :qualified-symbol :any :int :map]
                  [:map [:w :int] [:h :int] [:area :int] [:overflow-x? :boolean] [:slot-w :int]]]
   :cost/ticks 200000}
  [var-sym form-hash slot-w box]
  (let [k [var-sym form-hash]
        nb (fmeas/normalize-browser-box (long slot-w) box)
        sw (:slot-w nb)]
    (when-not (satisfies? cache/CacheProtocol @browser-box-cache)
      (reset! browser-box-cache (cache/lru-cache-factory {} :threshold 512)))
    (swap! browser-box-cache
           (fn [c]
             (let [cur (get c k)
                   m (assoc (if (map? cur) cur {}) sw nb)]
               (cache/miss c k m))))
    nb))

(def fallback-entity-renderers fmeas/fallback-entity-renderers)