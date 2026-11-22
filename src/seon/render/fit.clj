(ns seon.render.fit
  "Pure 3-signal self-balancing candidate ranking, structural Hiccup measurement,
   Datahike `:avet` renderer discovery, real-estate slot geometry, and the unified
   2-pass Complete-Thought Upgrade Ladder for BOTH AI Wire (`:target :wire`) and
   responsive browser HTML (`:target :html`)."
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

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (finite-double x 0.0))))

(defn- round4 [x]
  (/ (double (Math/round (* (finite-double x 0.0) 10000.0))) 10000.0))

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
                    (d/pull db '[:seon.config/rank-weights] [:seon.config/key :seon.config/root])))
          any-w (or root-w (:v (first (d/datoms db :aevt :seon.config/rank-weights))))]
      (normalize-weights any-w))))

(defn- candidate-urgency [c]
  (if (number? (:urgency c))
    (clamp01 (:urgency c))
    (let [data (if (map? (:data c)) (:data c) {})
          tags (let [t (:tags c)] (cond (set? t) t (coll? t) (set t) (some? t) #{t} :else #{}))
          rules (let [r (:rules c)] (cond (set? r) r (coll? r) (set r) (some? r) #{r} :else #{}))
          has-open-err? (or (= :open (:seon.error/status data))
                            (boolean (:open-error? c))
                            (contains? tags :error)
                            (contains? tags :fault)
                            (contains? rules :rule.ctx/fault-with-recommended-fix))
          has-fail-test? (or (false? (:seon.test/pass? data))
                             (boolean (:failing-test? c))
                             (contains? tags :failing-test)
                             (contains? rules :rule.ctx/failing-reach-tests))
          has-fix? (or (some? (:seon.fix/id data))
                       (boolean (:matching-fix? c))
                       (contains? tags :fix))
          has-edited? (or (boolean (:edited? c))
                          (boolean (:branch-edited? c))
                          (contains? tags :edited)
                          (contains? rules :rule.ctx/edited-var-on-branch))
          has-task? (or (and (some? (:seon.task/id data)) (not (:seon.task/done? data)))
                        (boolean (:active-task? c))
                        (contains? tags :active-task)
                        (contains? rules :rule.ctx/active-task-and-lineage))
          has-pin? (or (boolean (:pinned? c))
                       (contains? tags :pinned)
                       (contains? rules :rule.ctx/pinned-entities))
          anchor (cond
                   has-open-err?  1.0
                   has-fail-test? 0.95
                   has-fix?       0.90
                   has-edited?    0.85
                   has-task?      0.75
                   has-pin?       0.70
                   :else          0.0)
          pri-u (if (number? (:priority c))
                  (* 0.45 (clamp01 (/ (finite-double (:priority c) 0.0) 100.0)))
                  0.0)]
      (max anchor pri-u))))

(defn- candidate-tx [c]
  (max 0 (safe-long (or (:tx c) (:max-tx c) (when (map? (:data c)) (:tx (:data c)))) 0)))

(defn- candidate-turn [c]
  (max 0 (safe-long (or (:turn c) (when (map? (:data c)) (:seon.agent.eval/turn (:data c)))) 0)))

(defn- candidate-raw-convergence [c]
  (let [touches (max 0 (safe-long (or (:touches c) (:eval-touches c)) 0))
        paths (max 0 (safe-long (or (:converging-paths c)
                                    (:convergence c)
                                    (when (coll? (:rules c)) (count (:rules c))))
                                0))]
    (+ touches paths)))

(defn- pool-norm [vals-vec]
  (if (empty? vals-vec)
    []
    (let [lo (reduce min vals-vec)
          hi (reduce max vals-vec)
          span (- hi lo)]
      (if (zero? span)
        (mapv (fn [v] (if (pos? v) 1.0 0.0)) vals-vec)
        (mapv (fn [v] (/ (double (- v lo)) (double span))) vals-vec)))))

(defn rank-candidates
  "Pure 3-signal self-balancing rank over candidate entity maps using native Datahike signals:
   (a) Urgency / Anchor (open error, failing test, matching fix, edited var, active task, pinned ref),
   (b) Recency (native Datahike `max ?tx` / `:seon.agent.eval/turn` normalized across the pool),
   (c) Cumulative Touches / Convergence (distinct eval touches + converging ref paths).
   Weights are tunable via `{:weights {:urgency ... :recency ... :convergence ...}}`."
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
           raw-convs (mapv candidate-raw-convergence candidates)
           max-conv (reduce max 0 raw-convs)
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
                          (/ (Math/log1p (double (nth raw-convs idx))) conv-denom)
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

(def ^:private skip-text-tags
  #{"script" "style"})

(defn- class-width-floor [^String cls]
  (cond
    (or (.contains cls "grid") (.contains cls "split") (.contains cls "ent-full")
        (.contains cls "sig-panel") (.contains cls "panel")) 480
    (or (.contains cls "ent-card") (.contains cls "card") (.contains cls "kv-grid")) 300
    (or (.contains cls "sig-strip") (.contains cls "ent-chip") (.contains cls "badge")
        (.contains cls "pill") (.contains cls "chip")) 120
    :else 0))

(defn measure-hiccup
  "Pure structural Hiccup measurement returning `{:tokens :chars :min-width :area :lines}`.
   Respects explicit `:data-min-width` / `:seon.ui/min-width` on the root attrs map if present;
   otherwise estimates intrinsic width and area from tables, code blocks, cards, SVGs, and text."
  {:malli/schema [:=> [:cat :any]
                  [:map [:tokens :int] [:chars :int] [:min-width :int] [:area :int] [:lines :int]]]
   :cost/ticks 5000000}
  [node]
  (let [root-attrs (when (and (vector? node) (keyword? (first node)) (map? (second node)))
                     (second node))
        explicit-w (when root-attrs
                     (or (parse-px-hint (:data-min-width root-attrs))
                         (parse-px-hint (:seon.ui/min-width root-attrs))))
        st (volatile! {:chars 0 :lines 0 :max-line 0 :max-cols 0 :cur-cols 0
                       :nodes 0 :floor-w 0 :has-pre? false :has-table? false :svg-h 0})]
    ((fn walk [n depth]
       (when (and (< (:nodes @st) 1500) (< depth 48))
         (vswap! st update :nodes inc)
         (cond
           (or (nil? n) (false? n)) nil
           (or (string? n) (number? n) (keyword? n) (symbol? n) (boolean? n))
           (let [s (str n)
                 len (count s)]
             (when (pos? len)
               (let [parts (str/split s #"\n" -1)
                     ml (reduce max 0 (map count parts))]
                 (vswap! st (fn [m]
                              (-> m
                                  (update :chars + len)
                                  (update :lines + (max 0 (dec (count parts))))
                                  (update :max-line max ml)))))))
           (seq? n) (doseq [x n] (walk x (inc depth)))
           (vector? n)
           (let [hd (first n)]
             (if (or (keyword? hd) (symbol? hd) (string? hd))
               (let [tag (name hd)
                     has-a? (map? (second n))
                     attrs (when has-a? (second n))
                     ch (if has-a? (drop 2 n) (rest n))
                     cls (when (string? (:class attrs)) (:class attrs))]
                 (when-not (contains? skip-text-tags tag)
                   (when (contains? block-tags tag)
                     (vswap! st update :lines inc))
                   (when cls
                     (let [fw (class-width-floor cls)]
                       (when (pos? fw) (vswap! st update :floor-w max fw))))
                   (when-let [aw (or (parse-px-hint (:data-min-width attrs))
                                     (parse-px-hint (:seon.ui/min-width attrs)))]
                     (vswap! st update :floor-w max aw))
                   (case tag
                     "table" (vswap! st assoc :has-table? true)
                     "tr" (let [cols (count (filter #(and (vector? %)
                                                          (let [t (first %)]
                                                            (and (keyword? t)
                                                                 (contains? #{"td" "th"} (name t)))))
                                                    ch))]
                            (vswap! st update :max-cols max cols))
                     "pre" (vswap! st assoc :has-pre? true)
                     "dl" (vswap! st update :floor-w max 280)
                     "svg" (let [sw (or (parse-px-hint (:width attrs)) 120)
                                 sh (or (parse-px-hint (:height attrs)) 40)]
                             (vswap! st (fn [m] (-> m (update :floor-w max sw) (update :svg-h max sh)))))
                     nil)
                   (when-not (= tag "svg")
                     (doseq [c ch] (walk c (inc depth))))))
               (doseq [item n] (walk item (inc depth)))))
           :else nil)))
     node 0)
    (let [{:keys [chars lines max-line max-cols floor-w has-pre? has-table? svg-h]} @st
          eff-lines (if (and (zero? chars) (zero? lines) (zero? svg-h)) 0 (max 1 lines))
          tokens (if (zero? chars) 0 (max 1 (quot (+ chars 3) 4)))
          table-w (if has-table? (max 360 (min 960 (* (max 2 max-cols) 115))) 0)
          pre-w (if has-pre? (max 340 (min 720 (+ 36 (* (min 84 (max 20 max-line)) 8)))) 0)
          text-w (if (pos? chars) (max 96 (min 520 (+ 32 (* (min 64 max-line) 7)))) 0)
          struct-w (max floor-w table-w pre-w text-w)
          min-w (long (cond
                        (some? explicit-w) explicit-w
                        (zero? eff-lines) 0
                        :else (max 60 (min 1200 struct-w))))
          est-h (long (if (zero? eff-lines)
                        0
                        (max svg-h (+ (if (or has-table? has-pre?) 24 8) (* eff-lines 20)))))
          area (* min-w est-h)]
      {:tokens (long tokens)
       :chars (long chars)
       :min-width min-w
       :area (long area)
       :lines (long eff-lines)})))

(defn discover-renderers
  "Pure Datahike `:avet` reverse-ref discovery of ALL pure Hiccup renderer var symbols
   accepting `schema-keys` (single keyword or collection of keywords) via `:seon.code.var/input`
   intersected with `:seon.code.var/output` targeting `:seon.ui/hiccup` (filtering out impure
   vars with non-empty `:seon.code.var/ops`, private vars, test vars, and flow-edge vars).
   Zero reliance on `:seon.schema/render` or `:seon.code.var/schema-refs`."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:vector :qualified-symbol]]
   :cost/ticks 5000000}
  [db schema-keys]
  (let [dh-sch (:schema db)
        ks (cond
             (qualified-keyword? schema-keys) [schema-keys]
             (coll? schema-keys) (filterv qualified-keyword? schema-keys)
             :else [])]
    (if-not (and (seq ks)
                 (contains? dh-sch :seon.schema/key)
                 (contains? dh-sch :seon.code.var/input)
                 (contains? dh-sch :seon.code.var/output)
                 (contains? dh-sch :seon.code.var/name))
      []
      (if-let [hiccup-eid (:e (first (d/datoms db :avet :seon.schema/key :seon.ui/hiccup)))]
        (let [out-vars (into #{} (map :e) (d/datoms db :avet :seon.code.var/output hiccup-eid))
              has-ops? (contains? dh-sch :seon.code.var/ops)
              has-priv? (contains? dh-sch :seon.code.var/private?)
              has-test? (contains? dh-sch :seon.code.var/test?)
              has-edge? (contains? dh-sch :seon.code.var/flow-edge?)
              matched-eids
              (reduce
               (fn [acc sk]
                 (if-let [sk-eid (:e (first (d/datoms db :avet :seon.schema/key sk)))]
                   (reduce (fn [s dt]
                             (let [ve (:e dt)]
                               (if (contains? out-vars ve) (conj s ve) s)))
                           acc
                           (d/datoms db :avet :seon.code.var/input sk-eid))
                   acc))
               #{}
               ks)]
          (->> matched-eids
               (keep (fn [ve]
                       (when (and (not (and has-ops? (seq (d/datoms db :eavt ve :seon.code.var/ops))))
                                  (not (and has-priv? (:v (first (d/datoms db :eavt ve :seon.code.var/private?)))))
                                  (not (and has-test? (:v (first (d/datoms db :eavt ve :seon.code.var/test?)))))
                                  (not (and has-edge? (:v (first (d/datoms db :eavt ve :seon.code.var/flow-edge?))))))
                         (:v (first (d/datoms db :eavt ve :seon.code.var/name))))))
               distinct
               sort
               vec))
        []))))

(defn resolve-slot-budget
  "Pure real-estate geometry resolver replacing arbitrary `@media` cutoffs.
   Computes `{:avail-w :avail-h :max-items :area-budget}` from `{:viewport-w :viewport-h :sidebar-open? :slot}`."
  {:malli/schema [:=> [:cat [:maybe :map]]
                  [:map [:avail-w :int] [:avail-h :int] [:max-items :int] [:area-budget :int]]]
   :cost/ticks 500000}
  [spec]
  (let [sp (if (map? spec) spec {})
        vw (max 240 (min 10000 (safe-long (:viewport-w sp) 1440)))
        vh (max 240 (min 10000 (safe-long (:viewport-h sp) 900)))
        sb-open? (if (boolean? (:sidebar-open? sp)) (:sidebar-open? sp) (>= vw 840))
        sb-w (if sb-open? (min 280 (max 200 (quot vw 5))) 0)
        slot (if (contains? #{:header :sidebar :main :inspector :modal :wire} (:slot sp))
               (:slot sp)
               :main)
        base
        (case slot
          :header
          (let [aw (max 160 (- vw 32))
                ah 56]
            {:avail-w aw :avail-h ah
             :max-items (max 2 (min 8 (quot aw 160)))
             :area-budget (* aw ah)})

          :sidebar
          (let [aw (min 260 (max 180 (if sb-open? (- sb-w 24) 220)))
                ah (max 240 (- vh 72))]
            {:avail-w aw :avail-h ah
             :max-items (max 4 (min 24 (quot ah 44)))
             :area-budget (* aw ah)})

          :main
          (let [aw (max 240 (- vw (if (and sb-open? (>= vw 768)) (+ sb-w 48) 32)))
                ah (max 240 (- vh 80))]
            {:avail-w aw :avail-h ah
             :max-items (max 4 (min 30 (quot (* aw ah) 28000)))
             :area-budget (long (* 1.6 (double (* aw ah))))})

          :inspector
          (let [aw (min 480 (max 240 (quot vw 3)))
                ah (max 240 (- vh 96))]
            {:avail-w aw :avail-h ah
             :max-items (max 3 (min 16 (quot ah 64)))
             :area-budget (* aw ah)})

          :modal
          (let [aw (min 760 (max 260 (- vw 48)))
                ah (min 720 (max 240 (- vh 96)))]
            {:avail-w aw :avail-h ah
             :max-items (max 3 (min 16 (quot ah 56)))
             :area-budget (* aw ah)})

          :wire
          {:avail-w 10000 :avail-h 10000
           :max-items 40 :area-budget 100000000})]
    {:avail-w (max 80 (min 10000 (safe-long (:avail-w sp) (:avail-w base))))
     :avail-h (max 40 (min 10000 (safe-long (:avail-h sp) (:avail-h base))))
     :max-items (max 1 (min 500 (safe-long (:max-items sp) (:max-items base))))
     :area-budget (max 1000 (safe-long (:area-budget sp) (:area-budget base)))}))

(defn- normalize-candidate [c]
  (let [m (when (contains? c :hiccup) (measure-hiccup (:hiccup c)))
        chars (max 0 (safe-long (or (:chars c) (:chars m)) 0))
        tokens (max 0 (safe-long (or (:tokens c) (:tokens m)
                                     (when (pos? chars) (max 1 (quot (+ chars 3) 4))))
                                 0))
        min-w (max 0 (safe-long (or (:min-width c) (:seon.ui/min-width c) (:min-width m)) 0))
        lines (max 0 (safe-long (or (:lines c) (:lines m)) (if (pos? tokens) 1 0)))
        area (max 0 (safe-long (or (:area c) (:area m) (* (max 1 min-w) (max 1 (* (max 1 lines) 20)))) 0))]
    (assoc c :tokens tokens :chars chars :min-width min-w :area area :lines lines)))

(defn- item-candidates [item]
  (let [raw (:candidates item)]
    (if (and (sequential? raw) (seq raw) (every? map? raw))
      (mapv normalize-candidate raw)
      (let [unch? (= :unchanged (:status item))
            tok (if unch?
                  0
                  (if (number? (:tokens item))
                    (max 0 (safe-long (:tokens item) 0))
                    (let [payload (or (:delta-data item) (:data item))]
                      (if (and (map? payload) (empty? payload))
                        0
                        (max 1 (quot (count (pr-str payload)) 4))))))]
        [(normalize-candidate
          {:fn (or (:fn item) :default/projection)
           :hiccup (:hiccup item)
           :tokens tok
           :chars (* 4 tok)
           :min-width (safe-long (:min-width item) 240)
           :lines (max 1 (quot tok 16))})]))))

(defn- prepare-entry [item target avail-w already-shown]
  (let [cands0 (item-candidates item)
        eid (:entity item)
        cur-tx (candidate-tx item)
        cur-hash (:hash item)
        prev (when (and (= target :wire) (map? already-shown) (some? eid))
               (get already-shown eid))
        prev-tx (safe-long (:tx prev) 0)
        rewarmed? (boolean (and prev
                                (or (> cur-tx prev-tx)
                                    (and (some? cur-hash) (some? (:hash prev))
                                         (not= cur-hash (:hash prev))))))
        unchanged-prev? (boolean (and prev (not rewarmed?)))]
    (if (= target :html)
      (let [sorted (vec (sort-by (fn [c] [(:area c) (:min-width c) (:tokens c) (str (:fn c))]) cands0))
            fitting (filterv #(<= (:min-width %) avail-w) sorted)
            usable (if (seq fitting)
                     fitting
                     [(first (sort-by (fn [c] [(:min-width c) (:area c) (:tokens c)]) sorted))])]
        {:item item
         :all-cands sorted
         :cands usable
         :base-idx 0
         :prev nil
         :rewarmed? false
         :unchanged-prev? false
         :cost-fn (fn [c] (long (:area c)))})
      (let [sorted (vec (sort-by (fn [c] [(:tokens c) (:chars c) (str (:fn c))]) cands0))
            prev-idx (when unchanged-prev?
                       (or (first (keep-indexed (fn [i c] (when (= (:fn c) (:fn prev)) i)) sorted))
                           (first (keep-indexed (fn [i c] (when (>= (:tokens c) (safe-long (:tokens prev) 0)) i)) sorted))
                           0))
            base-idx (long (or prev-idx 0))
            unch-fn (when unchanged-prev? (:fn (nth sorted base-idx)))]
        {:item item
         :all-cands sorted
         :cands sorted
         :base-idx base-idx
         :prev prev
         :rewarmed? rewarmed?
         :unchanged-prev? unchanged-prev?
         :cost-fn (fn [c]
                    (if (or (= :unchanged (:status item))
                            (and unchanged-prev? (= (:fn c) unch-fn)))
                      0
                      (long (:tokens c))))}))))

(defn- pass2-upgrade [kept used budget strategy]
  (let [n (count kept)
        try-step
        (fn [entries cur-used i]
          (let [{:keys [cands idx cost cost-fn] :as e} (nth entries i)
                next-i (inc idx)]
            (if (< next-i (count cands))
              (let [next-cost (long (cost-fn (nth cands next-i)))
                    delta (- next-cost cost)]
                (if (<= (+ cur-used delta) budget)
                  [(assoc entries i (assoc e :idx next-i :cost next-cost))
                   (+ cur-used delta)
                   true]
                  [entries cur-used false]))
              [entries cur-used false])))]
    (if (= strategy :depth-first)
      (loop [entries kept u used i 0]
        (if (>= i n)
          [entries u]
          (let [[entries' u' moved?] (try-step entries u i)]
            (if moved?
              (recur entries' u' i)
              (recur entries u (inc i))))))
      (loop [entries kept u used]
        (let [[entries' u' any-moved?]
              (reduce (fn [[es cu mv?] i]
                        (let [[es' cu' m?] (try-step es cu i)]
                          [es' cu' (or mv? m?)]))
                      [entries u false]
                      (range n))]
          (if any-moved?
            (recur entries' u')
            [entries' u']))))))

(defn- finalize-kept-entry [{:keys [item all-cands cands idx cost prev rewarmed? unchanged-prev?]} target avail-w]
  (let [chosen (nth cands idx)
        status (if (= target :html)
                 :fitted
                 (cond
                   (= :unchanged (:status item)) :unchanged
                   unchanged-prev? (if (zero? cost) :unchanged :upgraded)
                   rewarmed? :rewarmed
                   (some? (:status item)) (:status item)
                   :else :new))
        cand-summary (mapv (fn [c]
                             {:fn (:fn c)
                              :tokens (:tokens c)
                              :min-width (:min-width c)
                              :area (:area c)
                              :fits-width? (<= (:min-width c) avail-w)})
                           all-cands)]
    (assoc item
           :chosen chosen
           :chosen-fn (:fn chosen)
           :hiccup (:hiccup chosen)
           :tokens (if (= target :wire) (long cost) (long (:tokens chosen)))
           :measured-tokens (long (:tokens chosen))
           :min-width (long (:min-width chosen))
           :area (long (:area chosen))
           :chars (long (:chars chosen))
           :lines (long (:lines chosen))
           :status status
           :candidates cand-summary)))

(defn upgrade-ladder
  "Unified 2-pass Complete-Thought Upgrade Ladder working identically for BOTH:
   - `:target :wire` (budget = `:token-budget`, respects `already-shown` `{eid {:fn :tokens :hash :tx}}`
     so unchanged entities cost 0 tokens and upgrades/re-warmed entities append cleanly;
     least-volatile -> most-volatile final ordering)
   - `:target :html` (budget = `:avail-w` + `:area-budget` from `resolve-slot-budget`; filters
     candidates by `:min-width <= avail-w` with smallest-width fallback).
   Pass 1 selects the smallest complete thought for top K; Pass 2 upgrades in rank order
   using `:breadth-first` (round-robin) or `:depth-first` while within budget."
  {:malli/schema [:=> [:cat [:vector :map] [:? [:maybe :map]]]
                  [:map
                   [:selected [:vector :map]]
                   [:dropped [:vector :map]]
                   [:tokens :int]
                   [:area :int]
                   [:target :keyword]
                   [:strategy :keyword]
                   [:shown :map]]]
   :cost/ticks 15000000}
  ([ranked-items] (upgrade-ladder ranked-items nil))
  ([ranked-items opts]
   (let [o (if (map? opts) opts {})
         target (if (= :html (:target o)) :html :wire)
         strategy (if (= :depth-first (:strategy o)) :depth-first :breadth-first)
         slot-b (when (= target :html) (resolve-slot-budget o))
         avail-w (long (if (= target :html) (:avail-w slot-b) 10000))
         budget (long (if (= target :html)
                        (:area-budget slot-b)
                        (max 0 (safe-long (:token-budget o) 16000))))
         max-items (long (if (= target :html)
                           (:max-items slot-b)
                           (max 0 (safe-long (:max-items o) (count ranked-items)))))
         shown0 (if (map? (:already-shown o)) (:already-shown o) {})
         top-k (vec (take max-items ranked-items))
         overflow (vec (drop max-items ranked-items))
         prepared (mapv #(prepare-entry % target avail-w shown0) top-k)
         ;; Pass 1: smallest complete thought for top K
         {:keys [kept dropped used]}
         (reduce (fn [{:keys [kept dropped used] :as acc} e]
                   (let [idx (:base-idx e)
                         c0 (nth (:cands e) idx)
                         cost0 (long ((:cost-fn e) c0))]
                     (if (or (zero? cost0) (<= (+ used cost0) budget))
                       (-> acc
                           (update :kept conj (assoc e :idx idx :cost cost0))
                           (update :used + cost0))
                       (update acc :dropped conj
                               (assoc (:item e) :tokens (long (:tokens c0)) :area (long (:area c0)))))))
                 {:kept [] :dropped [] :used 0}
                 prepared)
         ;; Pass 2: upgrade in rank order (:breadth-first or :depth-first)
         [upgraded _final-used] (pass2-upgrade kept used budget strategy)
         finalized (mapv #(finalize-kept-entry % target avail-w) upgraded)
         ordered (if (= target :wire)
                   (vec (sort-by (fn [p]
                                   [(get zone-order (:zone p) 2)
                                    (- (double (or (:rank/score p) 0.0)))
                                    (- (safe-long (:priority p) 50))
                                    (str (or (:entity p) (:id p) ""))])
                                 finalized))
                   finalized)
         all-dropped (into dropped overflow)
         total-tokens (reduce + 0 (map #(long (:tokens % 0)) ordered))
         total-area (reduce + 0 (map #(long (:area % 0)) ordered))
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
                              shown0
                              ordered)
                      shown0)]
     {:selected ordered
      :dropped all-dropped
      :tokens (long total-tokens)
      :area (long total-area)
      :budget (long budget)
      :slot-budget slot-b
      :target target
      :strategy strategy
      :shown next-shown})))

(defn- resolve-renderer-fn [vsym]
  (when (qualified-symbol? vsym)
    (let [ns-s (symbol (namespace vsym))
          nm-s (symbol (name vsym))]
      (when-let [v (or (when-let [ns-obj (find-ns ns-s)] (ns-resolve ns-obj nm-s))
                       (requiring-resolve vsym))]
        (when (bound? v) @v)))))

(defn- entity-schema-keys [db item ent-data]
  (let [explicit (:schema item)
        meta-sk (when (instance? clojure.lang.IObj ent-data) (:seon/schema (meta ent-data)))
        facets (when (and (map? ent-data) (seq ent-data))
                 (six/facets-of (schema/entity-index db) ent-data))]
    (->> (concat (cond (qualified-keyword? explicit) [explicit]
                       (coll? explicit) explicit
                       :else [])
                 (when (qualified-keyword? meta-sk) [meta-sk])
                 facets)
         (filter qualified-keyword?)
         distinct
         vec)))

(defn- invoke-discovered-renderers [db item ent-data sks extra-ctx]
  (let [rsyms (discover-renderers db sks)
        non-nil-ctx (into {} (filter (fn [[_ v]] (some? v))) (merge extra-ctx (:params item)))]
    (vec
     (keep (fn [rsym]
             (when-let [f (resolve-renderer-fn rsym)]
               (let [in-map (reduce (fn [m sk] (assoc m sk ent-data)) non-nil-ctx sks)
                     res (f in-map)
                     hiccup (cond
                              (and (map? res) (contains? res :seon.ui/hiccup)) (:seon.ui/hiccup res)
                              (vector? res) res
                              :else nil)]
                 (when (some? hiccup)
                   (let [m (measure-hiccup hiccup)
                         override-w (when (map? res) (parse-px-hint (:seon.ui/min-width res)))]
                     (cond-> (assoc m :fn rsym :hiccup hiccup)
                       (some? override-w) (assoc :min-width override-w
                                                 :area (* override-w (max 20 (* (max 1 (:lines m)) 20))))))))))
           rsyms))))

(defn fit-projections
  "End-to-end DB driver: ranks `projections` via `rank-candidates` (reading `:seon.config/rank-weights`
   unless `:weights` is supplied), pulls each entity once, discovers & invokes its pure `:seon.ui/hiccup`
   renderers via `discover-renderers`, measures each complete thought, and runs `upgrade-ladder`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:vector :map]] :map]
                  [:=> [:cat :seon.host/db [:vector :map] [:maybe :map]] :map]]
   :cost/ticks 30000000}
  ([db projections] (fit-projections db projections nil))
  ([db projections opts]
   (let [o (if (map? opts) opts {})
         ws (or (:weights o) (read-rank-weights db))
         ranked (rank-candidates projections {:weights ws})
         slot-b (when (= :html (:target o)) (resolve-slot-budget o))
         extra-ctx (cond-> {}
                     (:slot o) (assoc :seon.ui/slot (:slot o))
                     (:avail-w slot-b) (assoc :seon.ui/avail-w (:avail-w slot-b)))
         with-cands
         (mapv (fn [item]
                 (if (seq (:candidates item))
                   item
                   (let [eid (:entity item)
                         ent-data (cond
                                    (and (map? (:data item)) (seq (:data item))) (:data item)
                                    (integer? eid) (d/pull db '[*] eid)
                                    :else {})
                         sks (entity-schema-keys db item ent-data)
                         cands (when (seq sks)
                                 (invoke-discovered-renderers db item ent-data sks extra-ctx))]
                     (cond-> (assoc item :data ent-data)
                       (seq cands) (assoc :candidates cands)))))
               ranked)]
     (upgrade-ladder with-cands o))))
