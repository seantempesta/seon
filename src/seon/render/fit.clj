(ns seon.render.fit
  "Pure 3-signal self-balancing candidate ranking, structural Hiccup measurement,
   Datahike `:avet` renderer discovery, slot geometry, and the unified 2-pass
   Complete-Thought Upgrade Ladder for `:target :wire` and `:target :html`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.schema :as schema]
            [seon.schema.index :as six]))

(def default-rank-weights
  "Default tunable weights for the 3 native Datahike ranking signals."
  {:urgency 1.0 :recency 0.75 :convergence 0.5})

(def ^:private zone-order
  {:static 0 :static-prefix 0 :canvas 1 :workspace 2 :debug 3 :ephemeral-tail 4})

(defn- finite-double [x fallback]
  (if (number? x)
    (let [v (double x)]
      (if (and (== v v) (not (Double/isInfinite v))) v (double fallback)))
    (double fallback)))

(defn- clamp01 [x] (max 0.0 (min 1.0 (finite-double x 0.0))))

(defn- round4 [x] (/ (double (Math/round (* (finite-double x 0.0) 10000.0))) 10000.0))

(defn- safe-long [x fallback]
  (cond
    (integer? x) (long x)
    (number? x) (let [v (finite-double x fallback)]
                  (if (and (>= v -9.0e18) (<= v 9.0e18)) (long v) (long fallback)))
    :else (long fallback)))

(defn- parse-px-hint [v]
  (cond
    (integer? v) (max 0 (min 10000 (long v)))
    (number? v) (let [d (finite-double v -1.0)]
                  (when (>= d 0.0) (max 0 (min 10000 (long (Math/round d))))))
    (string? v) (when-let [m (re-find #"^\d+" (str/trim v))]
                  (max 0 (min 10000 (Long/parseLong m))))
    :else nil))

(defn- normalize-weights [w]
  (let [wm (if (map? w) w {})
        wu (max 0.0 (finite-double (:urgency wm) (:urgency default-rank-weights)))
        wr (max 0.0 (finite-double (:recency wm) (:recency default-rank-weights)))
        wc (max 0.0 (finite-double (:convergence wm) (:convergence default-rank-weights)))]
    (if (pos? (+ wu wr wc))
      {:urgency wu :recency wr :convergence wc}
      default-rank-weights)))

(defn read-rank-weights
  "Reads `:seon.config/rank-weights` from `db` (falling back to `default-rank-weights`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :seon.config/rank-weights]
   :cost/ticks 500000}
  [db]
  (if-not (and db (contains? (:schema db) :seon.config/rank-weights))
    default-rank-weights
    (let [root-w (when (contains? (:schema db) :seon.config/key)
                   (:seon.config/rank-weights
                    (d/pull db '[:seon.config/rank-weights] [:seon.config/key :seon.config/root])))]
      (normalize-weights (or root-w (:v (first (d/datoms db :aevt :seon.config/rank-weights))))))))

(defn- to-set [x]
  (cond (set? x) x (coll? x) (set x) (some? x) #{x} :else #{}))

(defn- candidate-urgency [c]
  (if (number? (:urgency c))
    (clamp01 (:urgency c))
    (let [data (if (map? (:data c)) (:data c) {})
          tags (to-set (:tags c))
          rules (to-set (:rules c))
          anchor
          (cond
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
                  (* 0.45 (clamp01 (/ (finite-double (:priority c) 0.0) 100.0)))
                  0.0)]
      (max anchor pri-u))))

(defn- candidate-tx [c]
  (max 0 (safe-long (or (:tx c) (:max-tx c) (when (map? (:data c)) (:tx (:data c)))) 0)))

(defn- candidate-turn [c]
  (max 0 (safe-long (or (:turn c) (when (map? (:data c)) (:seon.agent.eval/turn (:data c)))) 0)))

(defn- candidate-raw-convergence [c]
  (+ (max 0 (safe-long (or (:touches c) (:eval-touches c)) 0))
     (max 0 (safe-long (or (:converging-paths c)
                           (:convergence c)
                           (when (coll? (:rules c)) (count (:rules c))))
                       0))))

(defn- pool-norm [vs]
  (if (empty? vs)
    []
    (let [lo (reduce min vs)
          hi (reduce max vs)
          span (- hi lo)]
      (if (zero? span)
        (mapv #(if (pos? %) 1.0 0.0) vs)
        (mapv #(/ (double (- % lo)) (double span)) vs)))))

(defn rank-candidates
  "Pure 3-signal self-balancing rank over candidate entity maps using native Datahike signals:
   urgency/anchor, native `?tx`/turn recency normalized across the pool, and cumulative touches/convergence."
  {:malli/schema [:=> [:cat [:vector :map] [:? [:maybe :map]]] [:vector :map]]
   :cost/ticks 10000000}
  ([candidates] (rank-candidates candidates nil))
  ([candidates opts]
   (if (empty? candidates)
     []
     (let [ws (normalize-weights (when (map? opts) (:weights opts)))
           {:keys [urgency recency convergence]} ws
           w-sum (+ urgency recency convergence)
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
                     r (cond
                         (and has-tx? has-turn?) (max (nth tx-ns idx) (nth turn-ns idx))
                         has-tx? (nth tx-ns idx)
                         has-turn? (nth turn-ns idx)
                         :else 0.0)
                     cv (if (pos? max-conv)
                          (/ (Math/log1p (double (nth convs idx))) conv-denom)
                          0.0)
                     score (round4 (/ (+ (* urgency u) (* recency r) (* convergence cv)) w-sum))]
                 (assoc c
                        :rank/score score
                        :rank/signals {:urgency (round4 u) :recency (round4 r) :convergence (round4 cv)}
                        :rank/weights ws)))
             candidates)
            (sort-by (fn [c]
                       [(- (double (:rank/score c 0.0)))
                        (- (candidate-tx c))
                        (- (safe-long (:priority c) 50))
                        (str (or (:entity c) (:id c) ""))]))
            vec)))))

(def ^:private block-tags
  #{"div" "p" "li" "tr" "dt" "dd" "h1" "h2" "h3" "h4" "pre" "section" "article" "header" "table"})

(defn- class-width-floor [^String cls]
  (cond
    (or (.contains cls "grid") (.contains cls "split") (.contains cls "ent-full")
        (.contains cls "sig-panel") (.contains cls "panel")) 480
    (or (.contains cls "ent-card") (.contains cls "card") (.contains cls "kv-grid")) 300
    (or (.contains cls "sig-strip") (.contains cls "ent-chip") (.contains cls "badge")
        (.contains cls "pill") (.contains cls "chip")) 120
    :else 0))

(defn- walk-hiccup! [st n depth]
  (when (and (< (:nodes @st) 1500) (< depth 48))
    (vswap! st update :nodes inc)
    (cond
      (or (nil? n) (false? n)) nil
      (or (string? n) (number? n) (keyword? n) (symbol? n) (boolean? n))
      (let [s (str n) len (count s)]
        (when (pos? len)
          (let [parts (str/split s #"\n" -1)
                ml (reduce max 0 (map count parts))]
            (vswap! st (fn [m]
                         (-> m (update :chars + len)
                             (update :lines + (max 0 (dec (count parts))))
                             (update :max-line max ml)))))))
      (seq? n) (doseq [x n] (walk-hiccup! st x (inc depth)))
      (vector? n)
      (let [hd (first n)]
        (if (or (keyword? hd) (symbol? hd) (string? hd))
          (let [tag (name hd)
                has-a? (map? (second n))
                attrs (when has-a? (second n))
                ch (if has-a? (drop 2 n) (rest n))]
            (when-not (contains? #{"script" "style"} tag)
              (when (block-tags tag) (vswap! st update :lines inc))
              (when (string? (:class attrs))
                (let [fw (class-width-floor (:class attrs))]
                  (when (pos? fw) (vswap! st update :floor-w max fw))))
              (when-let [aw (or (parse-px-hint (:data-min-width attrs))
                                (parse-px-hint (:seon.ui/min-width attrs)))]
                (vswap! st update :floor-w max aw))
              (case tag
                "table" (vswap! st assoc :has-table? true)
                "tr" (let [cols (count (filter #(and (vector? %) (keyword? (first %))
                                                     (contains? #{"td" "th"} (name (first %)))) ch))]
                       (vswap! st update :max-cols max cols))
                "pre" (vswap! st assoc :has-pre? true)
                "dl" (vswap! st update :floor-w max 280)
                "svg" (let [sw (or (parse-px-hint (:width attrs)) 120)
                            sh (or (parse-px-hint (:height attrs)) 40)]
                        (vswap! st #(-> % (update :floor-w max sw) (update :svg-h max sh))))
                nil)
              (when-not (= tag "svg")
                (doseq [c ch] (walk-hiccup! st c (inc depth))))))
          (doseq [item n] (walk-hiccup! st item (inc depth)))))
      :else nil)))

(defn measure-hiccup
  "Pure Hiccup measurement returning `{:tokens :chars :min-width :area :lines}`.
   Reads explicit `:data-min-width` / `:seon.ui/min-width` hints if present; else estimates from structure."
  {:malli/schema [:=> [:cat :any]
                  [:map [:tokens :int] [:chars :int] [:min-width :int] [:area :int] [:lines :int]]]
   :cost/ticks 5000000}
  [node]
  (let [root-a (when (and (vector? node) (keyword? (first node)) (map? (second node))) (second node))
        explicit-w (when root-a (or (parse-px-hint (:data-min-width root-a))
                                    (parse-px-hint (:seon.ui/min-width root-a))))
        st (volatile! {:chars 0 :lines 0 :max-line 0 :max-cols 0
                       :nodes 0 :floor-w 0 :has-pre? false :has-table? false :svg-h 0})]
    (walk-hiccup! st node 0)
    (let [{:keys [chars lines max-line max-cols floor-w has-pre? has-table? svg-h]} @st
          eff-lines (if (and (zero? chars) (zero? lines) (zero? svg-h)) 0 (max 1 lines))
          tokens (if (zero? chars) 0 (max 1 (quot (+ chars 3) 4)))
          table-w (if has-table? (max 360 (min 960 (* (max 2 max-cols) 115))) 0)
          pre-w (if has-pre? (max 340 (min 720 (+ 36 (* (min 84 (max 20 max-line)) 8)))) 0)
          text-w (if (pos? chars) (max 96 (min 520 (+ 32 (* (min 64 max-line) 7)))) 0)
          min-w (long (cond
                        (some? explicit-w) explicit-w
                        (zero? eff-lines) 0
                        :else (max 60 (min 1200 (max floor-w table-w pre-w text-w)))))
          est-h (long (if (zero? eff-lines) 0
                          (max svg-h (+ (if (or has-table? has-pre?) 24 8) (* eff-lines 20)))))]
      {:tokens (long tokens) :chars (long chars)
       :min-width min-w :area (* min-w est-h) :lines (long eff-lines)})))

(defn discover-renderers
  "Pure Datahike `:avet` reverse-ref discovery of ALL pure `:seon.ui/hiccup` renderers accepting
   `schema-keys` via `:seon.code.var/input` intersected with `:seon.code.var/output` `:seon.ui/hiccup`
   (filtering out impure `:seon.code.var/ops`, private, test, and flow-edge vars)."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:vector :qualified-symbol]]
   :cost/ticks 5000000}
  [db schema-keys]
  (let [dh (:schema db)
        ks (cond (qualified-keyword? schema-keys) [schema-keys]
                 (coll? schema-keys) (filterv qualified-keyword? schema-keys)
                 :else [])]
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
          (->> eids
               (keep #(when (pure? %) (:v (first (d/datoms db :eavt % :seon.code.var/name)))))
               distinct sort vec))
        []))))

(defn resolve-slot-budget
  "Computes `{:avail-w :avail-h :max-items :area-budget}` from `{:viewport-w :viewport-h :sidebar-open? :slot}`."
  {:malli/schema [:=> [:cat [:maybe :map]]
                  [:map [:avail-w :int] [:avail-h :int] [:max-items :int] [:area-budget :int]]]
   :cost/ticks 500000}
  [spec]
  (let [sp (if (map? spec) spec {})
        vw (max 240 (min 10000 (safe-long (:viewport-w sp) 1440)))
        vh (max 240 (min 10000 (safe-long (:viewport-h sp) 900)))
        sb-open? (if (boolean? (:sidebar-open? sp)) (:sidebar-open? sp) (>= vw 840))
        sb-w (if sb-open? (min 280 (max 200 (quot vw 5))) 0)
        slot (if (#{:header :sidebar :main :inspector :modal :wire} (:slot sp)) (:slot sp) :main)
        [aw ah mi area]
        (case slot
          :header (let [w (max 160 (- vw 32))] [w 56 (max 2 (min 8 (quot w 160))) (* w 56)])
          :sidebar (let [w (min 260 (max 180 (if sb-open? (- sb-w 24) 220))) h (max 240 (- vh 72))]
                     [w h (max 4 (min 24 (quot h 44))) (* w h)])
          :main (let [w (max 240 (- vw (if (and sb-open? (>= vw 768)) (+ sb-w 48) 32)))
                      h (max 240 (- vh 80))]
                  [w h (max 4 (min 30 (quot (* w h) 28000))) (long (* 1.6 (double (* w h))))])
          :inspector (let [w (min 480 (max 240 (quot vw 3))) h (max 240 (- vh 96))]
                       [w h (max 3 (min 16 (quot h 64))) (* w h)])
          :modal (let [w (min 760 (max 260 (- vw 48))) h (min 720 (max 240 (- vh 96)))]
                   [w h (max 3 (min 16 (quot h 56))) (* w h)])
          :wire [10000 10000 40 100000000])]
    {:avail-w (max 80 (min 10000 (safe-long (:avail-w sp) aw)))
     :avail-h (max 40 (min 10000 (safe-long (:avail-h sp) ah)))
     :max-items (max 1 (min 500 (safe-long (:max-items sp) mi)))
     :area-budget (max 1000 (safe-long (:area-budget sp) area))}))

(defn- normalize-candidate [c]
  (let [m (when (contains? c :hiccup) (measure-hiccup (:hiccup c)))
        chars (max 0 (safe-long (or (:chars c) (:chars m)) 0))
        tokens (max 0 (safe-long (or (:tokens c) (:tokens m)
                                     (when (pos? chars) (max 1 (quot (+ chars 3) 4)))) 0))
        min-w (max 0 (safe-long (or (:min-width c) (:seon.ui/min-width c) (:min-width m)) 0))
        lines (max 0 (safe-long (or (:lines c) (:lines m)) (if (pos? tokens) 1 0)))
        area (max 0 (safe-long (or (:area c) (:area m) (* (max 1 min-w) (max 1 (* (max 1 lines) 20)))) 0))]
    (assoc c :tokens tokens :chars chars :min-width min-w :area area :lines lines)))

(defn- item-candidates [item]
  (let [raw (:candidates item)]
    (if (and (sequential? raw) (seq raw) (every? map? raw))
      (mapv normalize-candidate raw)
      (let [tok (cond
                  (= :unchanged (:status item)) 0
                  (number? (:tokens item)) (max 0 (safe-long (:tokens item) 0))
                  :else (let [p (or (:delta-data item) (:data item))]
                          (if (and (map? p) (empty? p)) 0 (max 1 (quot (count (pr-str p)) 4)))))]
        [(normalize-candidate
          {:fn (or (:fn item) :default/projection) :hiccup (:hiccup item)
           :tokens tok :chars (* 4 tok)
           :min-width (safe-long (:min-width item) 240) :lines (max 1 (quot tok 16))})]))))

(defn- prepare-entry [item target avail-w already-shown]
  (let [cands0 (item-candidates item)
        eid (:entity item)
        cur-tx (candidate-tx item)
        prev (when (and (= target :wire) (map? already-shown) (some? eid)) (get already-shown eid))
        rewarmed? (boolean (and prev (or (> cur-tx (safe-long (:tx prev) 0))
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
                                     (first (keep-indexed #(when (>= (:tokens %2) (safe-long (:tokens prev) 0)) %1) sorted))))
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
                   (let [nc (long (cost-fn (nth cands ni)))
                         delta (- nc cost)]
                     (if (<= (+ u delta) budget)
                       [(assoc entries i (assoc e :idx ni :cost nc)) (+ u delta) true]
                       [entries u false]))
                   [entries u false])))]
    (if (= strategy :depth-first)
      (loop [es kept u used i 0]
        (if (>= i n)
          [es u]
          (let [[es' u' mv?] (step es u i)]
            (if mv? (recur es' u' i) (recur es u (inc i))))))
      (loop [es kept u used]
        (let [[es' u' any?] (reduce (fn [[acc cu mv?] i]
                                      (let [[acc' cu' m?] (step acc cu i)] [acc' cu' (or mv? m?)]))
                                    [es u false] (range n))]
          (if any? (recur es' u') [es' u']))))))

(defn- finalize-entry [{:keys [item all-cands cands idx cost rewarmed? unch-prev?]} target avail-w]
  (let [chosen (nth cands idx)
        status (if (= target :html)
                 :fitted
                 (cond
                   (= :unchanged (:status item)) :unchanged
                   unch-prev? (if (zero? cost) :unchanged :upgraded)
                   rewarmed? :rewarmed
                   (some? (:status item)) (:status item)
                   :else :new))]
    (assoc item
           :chosen chosen :chosen-fn (:fn chosen) :hiccup (:hiccup chosen)
           :tokens (if (= target :wire) (long cost) (long (:tokens chosen)))
           :measured-tokens (long (:tokens chosen))
           :min-width (long (:min-width chosen)) :area (long (:area chosen))
           :chars (long (:chars chosen)) :lines (long (:lines chosen))
           :status status
           :candidates (mapv #(-> (select-keys % [:fn :tokens :min-width :area])
                                  (assoc :fits-width? (<= (:min-width %) avail-w)))
                             all-cands))))

(defn upgrade-ladder
  "Unified 2-pass Complete-Thought Upgrade Ladder for `:target :wire` (token budget + `already-shown`
   0-token dedup + least-volatile ordering) and `:target :html` (`resolve-slot-budget` width/area fit)."
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
         budget (long (if (= target :html) (:area-budget slot-b) (max 0 (safe-long (:token-budget o) 16000))))
         max-items (long (if (= target :html) (:max-items slot-b) (max 0 (safe-long (:max-items o) (count ranked-items)))))
         shown0 (if (map? (:already-shown o)) (:already-shown o) {})
         prepared (mapv #(prepare-entry % target avail-w shown0) (take max-items ranked-items))
         {:keys [kept dropped used]}
         (reduce (fn [acc e]
                   (let [idx (:base-idx e)
                         c0 (nth (:cands e) idx)
                         cost0 (long ((:cost-fn e) c0))]
                     (if (or (zero? cost0) (<= (+ (:used acc) cost0) budget))
                       (-> acc (update :kept conj (assoc e :idx idx :cost cost0)) (update :used + cost0))
                       (update acc :dropped conj (assoc (:item e) :tokens (long (:tokens c0)) :area (long (:area c0)))))))
                 {:kept [] :dropped [] :used 0} prepared)
         [upgraded _] (pass2-upgrade kept used budget strategy)
         finalized (mapv #(finalize-entry % target avail-w) upgraded)
         ordered (if (= target :wire)
                   (vec (sort-by (fn [p] [(get zone-order (:zone p) 2)
                                          (- (double (or (:rank/score p) 0.0)))
                                          (- (safe-long (:priority p) 50))
                                          (str (or (:entity p) (:id p) ""))])
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
                                                    :tx (max (safe-long (:tx prev) 0) (candidate-tx p))})))
                                  m))
                              shown0 ordered)
                      shown0)]
     {:selected ordered
      :dropped (into dropped (drop max-items ranked-items))
      :tokens (long (reduce + 0 (map #(long (:tokens % 0)) ordered)))
      :area (long (reduce + 0 (map #(long (:area % 0)) ordered)))
      :budget budget :slot-budget slot-b :target target :strategy strategy :shown next-shown})))

(defn- resolve-renderer-fn [vsym]
  (when (qualified-symbol? vsym)
    (when-let [v (or (when-let [ns-obj (find-ns (symbol (namespace vsym)))]
                       (ns-resolve ns-obj (symbol (name vsym))))
                     (requiring-resolve vsym))]
      (when (bound? v) @v))))

(defn- entity-schema-keys [db item ent-data]
  (let [explicit (:schema item)
        meta-sk (when (instance? clojure.lang.IObj ent-data) (:seon/schema (meta ent-data)))
        facets (when (and (map? ent-data) (seq ent-data)) (six/facets-of (schema/entity-index db) ent-data))]
    (->> (concat (cond (qualified-keyword? explicit) [explicit] (coll? explicit) explicit :else [])
                 (when (qualified-keyword? meta-sk) [meta-sk])
                 facets)
         (filter qualified-keyword?) distinct vec)))

(defn- invoke-discovered-renderers [db item ent-data sks extra-ctx]
  (let [rsyms (discover-renderers db sks)
        non-nil-ctx (into {} (filter (fn [[_ v]] (some? v))) (merge extra-ctx (:params item)))]
    (vec
     (keep (fn [rsym]
             (when-let [f (resolve-renderer-fn rsym)]
               (let [in-map (reduce #(assoc %1 %2 ent-data) non-nil-ctx sks)
                     res (f in-map)
                     hiccup (cond (and (map? res) (contains? res :seon.ui/hiccup)) (:seon.ui/hiccup res)
                                  (vector? res) res :else nil)]
                 (when (some? hiccup)
                   (let [m (measure-hiccup hiccup)
                         ow (when (map? res) (parse-px-hint (:seon.ui/min-width res)))]
                     (cond-> (assoc m :fn rsym :hiccup hiccup)
                       (some? ow) (assoc :min-width ow :area (* ow (max 20 (* (max 1 (:lines m)) 20))))))))))
           rsyms))))

(defn fit-projections
  "Ranks `projections`, pulls each entity once, discovers & invokes pure `:seon.ui/hiccup` renderers
   via `discover-renderers`, measures candidates, and runs `upgrade-ladder`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:vector :map]] :map]
                  [:=> [:cat :seon.host/db [:vector :map] [:maybe :map]] :map]]
   :cost/ticks 30000000}
  ([db projections] (fit-projections db projections nil))
  ([db projections opts]
   (let [o (if (map? opts) opts {})
         ranked (rank-candidates projections {:weights (or (:weights o) (read-rank-weights db))})
         slot-b (when (= :html (:target o)) (resolve-slot-budget o))
         extra-ctx (cond-> {}
                     (:slot o) (assoc :seon.ui/slot (:slot o))
                     (:avail-w slot-b) (assoc :seon.ui/avail-w (:avail-w slot-b)))
         with-cands
         (mapv (fn [item]
                 (if (seq (:candidates item))
                   item
                   (let [eid (:entity item)
                         ent-data (cond (and (map? (:data item)) (seq (:data item))) (:data item)
                                        (integer? eid) (d/pull db '[*] eid)
                                        :else {})
                         sks (entity-schema-keys db item ent-data)
                         cands (when (seq sks) (invoke-discovered-renderers db item ent-data sks extra-ctx))]
                     (cond-> (assoc item :data ent-data) (seq cands) (assoc :candidates cands)))))
               ranked)]
     (upgrade-ladder with-cands o))))
