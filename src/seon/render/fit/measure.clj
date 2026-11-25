(ns seon.render.fit.measure
  "Pure structural Hiccup measurement, responsive slot geometry, Hiccup-to-text fallback,
   and candidate variation reason/explanation formatting for `seon.render.fit`."
  (:require [clojure.string :as str]
            [seon.render.text :as rtext]))

(def ^:private block-tags
  #{"div" "p" "li" "tr" "dt" "dd" "h1" "h2" "h3" "h4" "pre" "section" "article" "header" "table"})

(defn finite-double
  "Coerces finite numbers to `double`, falling back to `fallback` for NaN/Infinity/non-numbers."
  {:malli/schema [:=> [:cat :any number?] number?]
   :cost/ticks 5000}
  [x fallback]
  (rtext/finite-double x fallback))

(defn safe-long
  "Coerces finite numbers to `long`, falling back to `fallback`."
  {:malli/schema [:=> [:cat :any :int] :int]
   :cost/ticks 5000}
  [x fallback]
  (rtext/safe-long x fallback))

(defn parse-px-hint
  "Parses a pixel width/height hint from an integer, finite double, or leading-digit string."
  {:malli/schema [:=> [:cat :any] [:maybe :int]]
   :cost/ticks 20000}
  [v]
  (cond
    (integer? v) (max 0 (min 10000 (long v)))
    (number? v) (let [d (finite-double v -1.0)]
                  (when (>= d 0.0) (max 0 (min 10000 (long (Math/round d))))))
    (string? v) (when-let [ms (re-find #"^\d+" (str/trim v))]
                  (max 0 (min 10000 (Long/parseLong ms))))
    :else nil))

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
      (cond-> {:tokens (long tokens) :chars (long chars) :min-width min-w :area (* min-w est-h) :lines (long eff-lines)} (some? explicit-w) (assoc :explicit-min-width (long explicit-w))))))

(defn resolve-slot-budget
  "Computes `{:avail-w :avail-h :max-items :area-budget :per-item-budget}` from viewport, slot, and UI config."
  {:malli/schema [:=> [:cat [:maybe :map]]
                  [:map [:avail-w :int] [:avail-h :int] [:max-items :int] [:area-budget :int]
                   [:per-item-budget {:optional true} :int]]]
   :cost/ticks 500000}
  [spec]
  (let [sp (if (map? spec) spec {})
        vw (max 240 (min 10000 (safe-long (:viewport-w sp) 1440)))
        vh (max 240 (min 10000 (safe-long (:viewport-h sp) 900)))
        sb-bp (max 320 (min 1600 (safe-long (:sidebar-breakpoint sp) 760)))
        sb-open? (if (boolean? (:sidebar-open? sp)) (:sidebar-open? sp) (>= vw sb-bp))
        sb-w (if sb-open? (min 280 (max 200 (quot vw 5))) 0)
        am (max 0.5 (min 8.0 (finite-double (:area-multiplier sp) 1.6)))
        ratio (max 0.1 (min 1.0 (finite-double (:per-item-area-ratio sp) 0.45)))
        slot (if (#{:header :sidebar :main :rail :inspector :modal :wire} (:slot sp)) (:slot sp) :main)
        main-w (max 240 (- vw (if (and sb-open? (>= vw sb-bp)) (+ sb-w 48) 32)))
        main-h (max 240 (- vh 80))
        [aw ah mi area]
        (case slot
          :header (let [w (max 160 (- vw 32))] [w 56 (max 2 (min 8 (quot w 160))) (* w 56)])
          :sidebar (let [w (min 260 (max 180 (if sb-open? (- sb-w 24) 220))) h (max 240 (- vh 72))]
                     [w h (max 4 (min 24 (quot h 44))) (* w h)])
          :main [main-w main-h (max 4 (min 30 (quot (* main-w main-h) 28000))) (long (* am (double (* main-w main-h))))]
          :rail (let [rw (if (>= main-w 860) (max 260 (quot (* (- main-w 18) 44) 100)) main-w)]
                  [rw main-h (max 4 (min 24 (quot (* rw main-h) 22000))) (long (* am (double (* rw main-h))))])
          :inspector (let [w (min 480 (max 240 (quot vw 3))) h (max 240 (- vh 96))]
                       [w h (max 3 (min 16 (quot h 64))) (* w h)])
          :modal (let [w (min 760 (max 260 (- vw 48))) h (min 720 (max 240 (- vh 96)))]
                   [w h (max 3 (min 16 (quot h 56))) (* w h)])
          :wire [10000 10000 40 100000000])
        final-area (max 1000 (safe-long (:area-budget sp) area))
        per-item (max 1000 (safe-long (:per-item-budget sp) (long (* (double final-area) ratio))))]
    {:avail-w (max 80 (min 10000 (safe-long (:avail-w sp) aw)))
     :avail-h (max 40 (min 10000 (safe-long (:avail-h sp) ah)))
     :max-items (max 1 (min 500 (safe-long (:max-items sp) mi)))
     :area-budget final-area
     :per-item-budget per-item}))

(defn normalize-candidate
  "Ensures a candidate variation map carries integer `:tokens`, `:chars`, `:min-width`, `:area`, and `:lines`."
  {:malli/schema [:=> [:cat :map] :map]
   :cost/ticks 5000000}
  [c]
  (let [m (when (and (contains? c :hiccup) (not (and (integer? (:tokens c)) (integer? (:chars c)) (integer? (:min-width c)) (integer? (:area c)) (integer? (:lines c)))))
          (measure-hiccup (:hiccup c)))
        chars (max 0 (safe-long (or (:chars c) (:chars m)) 0))
        tokens (max 0 (safe-long (or (:tokens c) (:tokens m)
                                     (when (pos? chars) (max 1 (quot (+ chars 3) 4)))) 0))
        min-w (max 0 (safe-long (or (:min-width c) (:seon.ui/min-width c) (:min-width m)) 0))
        lines (max 0 (safe-long (or (:lines c) (:lines m)) (if (pos? tokens) 1 0)))
        area (max 0 (safe-long (or (:area c) (:area m) (* (max 1 min-w) (max 1 (* (max 1 lines) 20)))) 0))]
    (assoc c :tokens tokens :chars chars :min-width min-w :area area :lines lines)))

(defn hiccup->text-fallback
  "Hiccup-to-text used by fit measurement when `seon.render/hiccup->text` is not loaded.
   Delegates to the single implementation `seon.render.text/hiccup->text` (zero-dep leaf ns),
   so fit scoring measures EXACTLY the text an agent's wire context will see."
  {:malli/schema [:=> [:cat :any] :string]
   :cost/ticks 5000000}
  [node]
  (rtext/hiccup->text node))

(defn resolve-text-fn
  "Resolves Hiccup-to-text function from `(:text-fn opts)`, `seon.render/hiccup->text`, or fallback."
  {:malli/schema [:=> [:cat [:maybe :map]] :seon.host/fn]
   :cost/ticks 50000}
  [opts]
  (or (when (and (map? opts) (fn? (:text-fn opts))) (:text-fn opts))
      (when-let [v (find-var 'seon.render/hiccup->text)] (when (bound? v) @v))
      hiccup->text-fallback))

(defn variation-reason
  "Returns an explicit human-readable selection/skip/drop reason for variation `c` of item `it`."
  {:malli/schema [:=> [:cat :map :map :boolean :keyword :int :int [:? [:maybe :int]]] :string]
   :cost/ticks 200000}
  ([c it dropped? target avail-w remaining]
   (variation-reason c it dropped? target avail-w remaining
                     (when (integer? (:per-item-budget it)) (long (:per-item-budget it)))))
  ([c it dropped? target avail-w remaining per-item-budget]
   (let [mw (safe-long (:min-width c) 0)
         tok (safe-long (:tokens c) 0)
         area (safe-long (:area c) 0)
         ov? (boolean (:overflow-x? c))
         pib (when (and (integer? per-item-budget) (pos? (long per-item-budget)))
               (long per-item-budget))
         cfn (:chosen-fn it)
         bfn (:base-fn it)
         status (:status it)
         chosen? (and (not dropped?) (= (:fn c) cfn))]
     (cond
       dropped?
       (cond (and (= target :html) ov?) (str "Skipped: browser overflow at " avail-w "px")
             (and (= target :html) (> mw avail-w)) (str "Skipped: min-width " mw "px > slot " avail-w "px")
             (= target :wire) (str "Dropped: exceeds budget (" tok " tok > " remaining " tok left)")
             :else (str "Dropped: exceeds budget (" area " px² > " remaining " px² left)"))

       chosen?
       (cond (and (= target :wire) (= :unchanged status)) "0 tok (already-shown)"
             (and (= target :wire) (or (= :upgraded status) (and bfn (not= bfn cfn))))
             (str "Chosen (Pass 2 upgraded +" (max 0 (- tok (safe-long (:base-tokens it) 0))) " tok)")
             (and (= target :html) bfn (not= bfn cfn))
             (str "Chosen (Pass 2 upgraded +" (max 0 (- area (safe-long (:base-area it) 0))) " px²)")
             (and (= target :html) ov?)
             (str "Chosen (Fallback smallest; browser overflow at " avail-w "px)")
             (and (= target :html) (> mw avail-w))
             (str "Chosen (Fallback smallest; min-width " mw "px > slot " avail-w "px)")
             :else "Chosen (Pass 1 base fit)")

       (and (= target :html) ov?)
       (str "Skipped: browser overflow at " avail-w "px")

       (and (= target :html) (> mw avail-w))
       (str "Skipped: min-width " mw "px > slot " avail-w "px")

       (= target :wire)
       (let [ctok (safe-long (or (:measured-tokens it) (:tokens it)) 0)
             eff-c (if (= :unchanged status) 0 ctok)]
         (cond (< tok ctok) "Superseded by richer variation"
               (and pib (> tok pib)) (str "Skipped: exceeds per-item budget (" tok " tok > " pib " tok cap)")
               :else (str "Skipped: needs +" (max 0 (- tok eff-c)) " tok > " remaining " tok left")))

       :else
       (let [carea (safe-long (:area it) 0)]
         (cond (< area carea) "Superseded by richer variation"
               (and pib (> area pib)) (str "Skipped: exceeds per-item budget (" area " px² > " pib " px² cap)")
               :else (str "Skipped: needs +" (max 0 (- area carea)) " px² > " remaining " px² left")))))))

(defn build-explained-item
  "Assembles the complete explanation record for one ranked candidate item and all its variations."
  {:malli/schema [:=> [:cat :map [:vector :map] :boolean :keyword :int :int :seon.host/fn] :map]
   :cost/ticks 5000000}
  [it raw-cands dropped? target avail-w remaining text-fn]
  (let [cands (if (= target :html)
                (vec (sort-by (juxt :area :min-width :tokens #(str (:fn %))) (mapv normalize-candidate raw-cands)))
                (vec (sort-by (juxt :tokens :chars #(str (:fn %))) (mapv normalize-candidate raw-cands))))
        cfn (when-not dropped? (:chosen-fn it))
        sks (vec (or (:schemas it) (when (qualified-keyword? (:schema it)) [(:schema it)]) []))]
    {:entity (:entity it)
     :schema (or (first sks) (:schema it))
     :schemas sks
     :status (if dropped? :dropped (:status it))
     :selected? (not dropped?)
     :zone (:zone it)
     :chosen-fn cfn
     :measured-by (or (:measured-by it) :static)
     :overflow-x? (boolean (:overflow-x? it))
     :tokens (long (if dropped? 0 (safe-long (:tokens it) 0)))
     :measured-tokens (long (safe-long (or (:measured-tokens it) (:tokens it)) 0))
     :min-width (long (safe-long (:min-width it) 0))
     :area (long (safe-long (:area it) 0))
     :rank/score (:rank/score it)
     :rank/signals (:rank/signals it)
     :rank/weights (:rank/weights it)
     :rank/boost (or (:rank/boost it) (get-in it [:params :rank/boost]) 0.0)
     :rank/suppress (or (:rank/suppress it) (get-in it [:params :rank/suppress]) 0.0)
     :rules (vec (or (:rules it) []))
     :params (or (:params it) {})
     :variations
     (mapv (fn [c]
             {:fn (:fn c)
              :hiccup (:hiccup c)
              :text (if (:hiccup c) (text-fn (:hiccup c)) "")
              :tokens (long (safe-long (:tokens c) 0))
              :chars (long (safe-long (:chars c) 0))
              :min-width (long (safe-long (:min-width c) 0))
              :area (long (safe-long (:area c) 0))
              :lines (long (safe-long (:lines c) 0))
              :measured-by (or (:measured-by c) :static)
              :overflow-x? (boolean (:overflow-x? c))
              :fits-width? (and (<= (safe-long (:min-width c) 0) avail-w)
                                (not (:overflow-x? c)))
              :chosen? (boolean (and (not dropped?) (= (:fn c) cfn)))
              :reason (variation-reason c it dropped? target avail-w remaining)})
           cands)}))

(defn- set-schema-form? [f]
  (and (vector? f) (or (= :set (first f)) (and (= :and (first f)) (some set-schema-form? (rest f))))))

(defn- unwrap-ref [x]
  (if (and (map? x) (= 1 (count x)) (integer? (:db/id x))) (:db/id x) x))

(defn normalize-entity-map
  "Normalizes entity map `ent` against schema maps `dh`/`main-dh` and Malli `forms`: converts
   `:db.cardinality/many` or `:set` values to sets and unwraps single-key `{:db/id n}` refs."
  {:malli/schema [:=> [:cat [:maybe :map] :any [:maybe :map] [:maybe :map]] :any]
   :cost/ticks 2000000}
  [dh ent forms main-dh]
  (if-not (map? ent)
    ent
    (let [d1 (when (map? dh) dh)
          d2 (when (map? main-dh) main-dh)
          fs (when (map? forms) forms)]
      (into {} (map (fn [[k v]]
                      (let [raw-sp (or (get d1 k) (get d2 k))
                            sp (when (map? raw-sp) raw-sp)
                            many? (or (= :db.cardinality/many (:db/cardinality sp))
                                      (set-schema-form? (get fs k)))
                            ref? (= :db.type/ref (:db/valueType sp))
                            v1 (cond
                                 (and many? (sequential? v)) (into #{} (if ref? (map unwrap-ref) identity) v)
                                 (and many? (set? v) ref?) (into #{} (map unwrap-ref) v)
                                 (and many? (some? v) (not (set? v))) #{(if ref? (unwrap-ref v) v)}
                                 ref? (unwrap-ref v)
                                 :else v)]
                        [k v1])))
            ent))))

(defn normalize-browser-box
  "Normalizes a browser measurement box map into `{:w :h :area :overflow-x? :slot-w}`."
  {:malli/schema [:=> [:cat :int :map]
                  [:map [:w :int] [:h :int] [:area :int] [:overflow-x? :boolean] [:slot-w :int]]]
   :cost/ticks 50000}
  [slot-w box]
  (let [sw (max 1 (safe-long slot-w 1))
        bw (max 0 (safe-long (or (:w box) (:width box)) sw))
        bh (max 0 (safe-long (or (:h box) (:height box)) 0))
        ba (max 1 (safe-long (:area box) (* (max 1 bw) (max 1 bh))))
        ov? (boolean (or (:overflow-x? box) (:overflowX box)))]
    {:w bw :h bh :area ba :overflow-x? ov? :slot-w sw}))

(defn select-browser-box
  "Selects the best recorded browser box from `{slot-w box}` for `avail-w`:
   exact `slot-w == avail-w` if present, else largest `slot-w <= avail-w`, else smallest `slot-w`."
  {:malli/schema [:=> [:cat :map :int] [:maybe :map]]
   :cost/ticks 50000}
  [by-w avail-w]
  (when (seq by-w)
    (let [aw (long avail-w)
          exact (get by-w aw)]
      (if (map? exact)
        exact
        (let [ws (filterv #(and (integer? %) (map? (get by-w %))) (keys by-w))]
          (when (seq ws)
            (let [le (filterv #(<= (long %) aw) ws)
                  best-w (if (seq le) (reduce max le) (reduce min ws))]
              (get by-w best-w))))))))

(defn apply-browser-box
  "Applies a real browser box `{:w :h :area :overflow-x?}` onto static Hiccup metrics `m`,
   preserving exact `:tokens`/`:chars`/`:lines` and tagging `:measured-by` (`:browser` vs `:static`)."
  {:malli/schema [:=> [:cat :map [:maybe :map] :int] :map]
   :cost/ticks 50000}
  [m box avail-w]
  (let [b (when (map? box)
            (if (and (seq box) (every? integer? (keys box)))
              (select-browser-box box avail-w)
              box))]
    (if-not (map? b)
      (assoc m :measured-by :static :overflow-x? (boolean (:overflow-x? m)))
      (let [aw (max 1 (long avail-w))
          exp-mw (max 0 (safe-long (:explicit-min-width m) 0))
          bw (max 0 (safe-long (or (:w b) (:width b)) (safe-long (:min-width m) 0)))
          bh (max 0 (safe-long (or (:h b) (:height b)) 0))
          ov? (boolean (or (:overflow-x? b) (:overflowX b)))
          ba (max 1 (safe-long (:area b) (* (max 1 bw) (max 1 bh))))
          mw (if ov? (max exp-mw bw (inc aw)) (max exp-mw bw))]
      (assoc m :w bw :h bh :min-width mw :area ba
             :overflow-x? ov? :measured-by :browser)))))

(defn item-candidates
  "Normalizes an item map's `:candidates` vector (or synthesizes a default candidate from `:tokens`/`:data`)."
  {:malli/schema [:=> [:cat :map] [:vector :map]]
   :cost/ticks 5000000}
  [item]
  (let [raw (:candidates item)]
    (if (and (sequential? raw) (seq raw) (every? map? raw))
      (mapv normalize-candidate raw)
      (let [tok (cond (= :unchanged (:status item)) 0
                      (number? (:tokens item)) (max 0 (safe-long (:tokens item) 0))
                      :else (let [p (or (:delta-data item) (:data item))]
                              (if (and (map? p) (empty? p)) 0 (max 1 (quot (count (pr-str p)) 4)))))]
        [(normalize-candidate
          {:fn (or (:fn item) :default/projection) :hiccup (:hiccup item)
           :tokens tok :chars (* 4 tok)
           :min-width (safe-long (:min-width item) 240) :lines (max 1 (quot tok 16))})]))))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (finite-double x 0.0))))

(defn- to-set [x]
  (cond (set? x) x (coll? x) (set x) (some? x) #{x} :else #{}))

(defn candidate-urgency
  "Computes the normalized `[0.0, 1.0]` urgency signal for a candidate entity map."
  {:malli/schema [:=> [:cat :map] number?]
   :cost/ticks 100000}
  [c]
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
                   (or (:edited? c) (:branch-edited? c) (:written? c)
                       (tags :edited) (rules :rule.ctx/edited-var-on-branch)) 0.85
                   (or (and (some? (:seon.task/id data)) (not (:seon.task/done? data)))
                       (:active-task? c) (tags :active-task) (rules :rule.ctx/active-task-and-lineage)) 0.75
                   (or (:pinned? c) (tags :pinned) (rules :rule.ctx/pinned-entities)) 0.70
                   :else 0.0)
          pri-u (if (number? (:priority c))
                  (* 0.45 (clamp01 (/ (finite-double (:priority c) 0.0) 100.0)))
                  0.0)]
      (max anchor pri-u))))

(defn candidate-raw-convergence
  "Computes raw convergence energy from `:touch-energy`, `:touches`, and converging `:rules`."
  {:malli/schema [:=> [:cat :map] number?]
   :cost/ticks 50000}
  [c]
  (let [te (when (number? (:touch-energy c)) (max 0.0 (finite-double (:touch-energy c) 0.0)))
        raw-t (if (some? te) (* 2.5 te) (double (max 0 (safe-long (or (:touches c) (:eval-touches c)) 0))))
        paths (double (max 0 (safe-long (or (:converging-paths c) (:convergence c)
                                            (when (coll? (:rules c)) (count (:rules c)))) 0)))]
    (+ raw-t paths)))

(defn pool-norm
  "Min-max normalizes a vector of numbers into `[0.0, 1.0]` across the pool."
  {:malli/schema [:=> [:cat [:vector number?]] [:vector number?]]
   :cost/ticks 200000}
  [vs]
  (if (empty? vs)
    []
    (let [ds (mapv #(finite-double % 0.0) vs)
          lo (reduce min ds) hi (reduce max ds) span (- hi lo)]
      (if (zero? span)
        (mapv #(if (pos? %) 1.0 0.0) ds)
        (mapv #(/ (- % lo) span) ds)))))