(ns seon.render.fit.measure
  "Pure structural Hiccup measurement, responsive slot geometry, Hiccup-to-text fallback,
   and candidate variation reason/explanation formatting for `seon.render.fit`."
  (:require [clojure.string :as str]))

(def ^:private block-tags
  #{"div" "p" "li" "tr" "dt" "dd" "h1" "h2" "h3" "h4" "pre" "section" "article" "header" "table"})

(defn finite-double
  "Coerces finite numbers to `double`, falling back to `fallback` for NaN/Infinity/non-numbers."
  {:malli/schema [:=> [:cat :any number?] number?]
   :cost/ticks 5000}
  [x fallback]
  (if (number? x)
    (let [v (double x)]
      (if (and (== v v) (not (Double/isInfinite v))) v (double fallback)))
    (double fallback)))

(defn safe-long
  "Coerces finite numbers to `long`, falling back to `fallback`."
  {:malli/schema [:=> [:cat :any :int] :int]
   :cost/ticks 5000}
  [x fallback]
  (cond
    (integer? x) (long x)
    (number? x) (let [v (finite-double x fallback)]
                  (if (and (>= v -9.0e18) (<= v 9.0e18)) (long v) (long fallback)))
    :else (long fallback)))

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
      {:tokens (long tokens) :chars (long chars)
       :min-width min-w :area (* min-w est-h) :lines (long eff-lines)})))

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

(defn normalize-candidate
  "Ensures a candidate variation map carries integer `:tokens`, `:chars`, `:min-width`, `:area`, and `:lines`."
  {:malli/schema [:=> [:cat :map] :map]
   :cost/ticks 5000000}
  [c]
  (let [m (when (contains? c :hiccup) (measure-hiccup (:hiccup c)))
        chars (max 0 (safe-long (or (:chars c) (:chars m)) 0))
        tokens (max 0 (safe-long (or (:tokens c) (:tokens m)
                                     (when (pos? chars) (max 1 (quot (+ chars 3) 4)))) 0))
        min-w (max 0 (safe-long (or (:min-width c) (:seon.ui/min-width c) (:min-width m)) 0))
        lines (max 0 (safe-long (or (:lines c) (:lines m)) (if (pos? tokens) 1 0)))
        area (max 0 (safe-long (or (:area c) (:area m) (* (max 1 min-w) (max 1 (* (max 1 lines) 20)))) 0))]
    (assoc c :tokens tokens :chars chars :min-width min-w :area area :lines lines)))

(defn hiccup->text-fallback
  "Lightweight zero-dep Hiccup-to-text extractor used when `seon.render/hiccup->text` is not loaded."
  {:malli/schema [:=> [:cat :any] :string]
   :cost/ticks 5000000}
  [node]
  (let [sb (StringBuilder. 128)]
    ((fn w! [n]
       (cond
         (or (nil? n) (false? n)) nil
         (or (string? n) (number? n) (keyword? n) (symbol? n) (boolean? n)) (.append sb (str n))
         (seq? n) (doseq [x n] (w! x))
         (vector? n) (let [hd (first n)]
                       (if (or (keyword? hd) (symbol? hd) (string? hd))
                         (let [tag (name hd) ch (if (map? (second n)) (drop 2 n) (rest n))]
                           (when-not (contains? #{"script" "style" "svg"} tag)
                             (doseq [c ch] (w! c))
                             (.append sb (if (block-tags tag) "\n" " "))))
                         (doseq [item n] (w! item))))
         :else nil))
     node)
    (-> (.toString sb)
        (str/replace #"[ \t]+\n" "\n")
        (str/replace #" {2,}" " ")
        (str/replace #"\n{3,}" "\n\n")
        str/trim)))

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
  {:malli/schema [:=> [:cat :map :map :boolean :keyword :int :int] :string]
   :cost/ticks 200000}
  [c it dropped? target avail-w remaining]
  (let [mw (safe-long (:min-width c) 0)
        tok (safe-long (:tokens c) 0)
        area (safe-long (:area c) 0)
        cfn (:chosen-fn it)
        bfn (:base-fn it)
        status (:status it)
        chosen? (and (not dropped?) (= (:fn c) cfn))]
    (cond
      dropped?
      (cond (and (= target :html) (> mw avail-w)) (str "Skipped: min-width " mw "px > slot " avail-w "px")
            (= target :wire) (str "Dropped: exceeds budget (" tok " tok > " remaining " tok left)")
            :else (str "Dropped: exceeds budget (" area " px² > " remaining " px² left)"))

      chosen?
      (cond (and (= target :wire) (= :unchanged status)) "0 tok (already-shown)"
            (and (= target :wire) (or (= :upgraded status) (and bfn (not= bfn cfn))))
            (str "Chosen (Pass 2 upgraded +" (max 0 (- tok (safe-long (:base-tokens it) 0))) " tok)")
            (and (= target :html) bfn (not= bfn cfn))
            (str "Chosen (Pass 2 upgraded +" (max 0 (- area (safe-long (:base-area it) 0))) " px²)")
            (and (= target :html) (> mw avail-w))
            (str "Chosen (Fallback smallest; min-width " mw "px > slot " avail-w "px)")
            :else "Chosen (Pass 1 base fit)")

      (and (= target :html) (> mw avail-w))
      (str "Skipped: min-width " mw "px > slot " avail-w "px")

      (= target :wire)
      (let [ctok (safe-long (or (:measured-tokens it) (:tokens it)) 0)
            eff-c (if (= :unchanged status) 0 ctok)]
        (if (< tok ctok)
          "Superseded by richer variation"
          (str "Skipped: needs +" (max 0 (- tok eff-c)) " tok > " remaining " tok left")))

      :else
      (let [carea (safe-long (:area it) 0)]
        (if (< area carea)
          "Superseded by richer variation"
          (str "Skipped: needs +" (max 0 (- area carea)) " px² > " remaining " px² left"))))))

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
              :fits-width? (<= (safe-long (:min-width c) 0) avail-w)
              :chosen? (boolean (and (not dropped?) (= (:fn c) cfn)))
              :reason (variation-reason c it dropped? target avail-w remaining)})
           cands)}))
