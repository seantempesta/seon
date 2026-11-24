(ns seon.render.viz
  "Pure, theme-aware, responsive Hiccup visualization primitives (`bar-chart`, `line-chart`,
   `sparkline`, `stat-grid`). Zero I/O, zero Clojure ratio literals, Phosphor CSS variables
   with WCAG-safe contrast across dark & light themes and 360px..960px viewports."
  (:require [clojure.string :as str]))

(def ^:private palette
  ["var(--signal,#f0b429)" "var(--info,#60a5fa)" "var(--success,#34d399)" "var(--eval,#c084fc)" "var(--warning,#fbbf24)"])

(defn- finite-num [x fb]
  (if (number? x)
    (let [d (double x)] (if (and (== d d) (not (Double/isInfinite d))) d (double fb)))
    (double fb)))

(defn- fmt-n [x]
  (let [d (finite-num x 0.0)]
    (if (> (Math/abs d) 1.0e12)
      (format java.util.Locale/US "%.0f" d)
      (let [r (/ (double (Math/round (* d 100.0))) 100.0)]
        (if (== r (Math/floor r)) (str (long r)) (str r))))))

(defn- fmt-val [x unit]
  (let [d (finite-num x 0.0) a (Math/abs d)
        core (cond (>= a 1.0e6) (str (fmt-n (/ d 1.0e6)) "M")
                   (>= a 1.0e4) (str (fmt-n (/ d 1.0e3)) "k")
                   :else (fmt-n d))]
    (if (and (string? unit) (not (str/blank? unit)))
      (str core (if (str/starts-with? unit "%") unit (str " " (str/trim unit))))
      core)))

(defn- clip-lbl [s n]
  (let [t (str/trim (str/replace (str (or s "")) #"\s+" " "))]
    (if (> (count t) n) (str (subs t 0 (max 1 (dec n))) "…") t)))

(defn- safe-color [c idx]
  (if (and (string? c) (not (str/blank? c))) (str/trim c) (nth palette (mod idx (count palette)))))

(defn- nonblank [s]
  (when (and (string? s) (not (str/blank? s))) (str/trim s)))

(def ^:private c-txt3 "var(--text-300,#b0aa9e)")

(defn- card-shell [title subtitle body]
  (let [t (nonblank title) sub (nonblank subtitle)]
    (cond-> [:section {:class "card stack-sm viz-card"}]
      (or t sub)
      (conj (cond-> [:div {:class "row-between"}]
              t (conj [:h2 t])
              sub (conj [:span {:class "tiny mono" :style {:color c-txt3}} sub])))
      true (conj body))))

(defn- empty-box [msg]
  [:div {:class "tiny mono" :style {:padding "10px" :color c-txt3}} msg])

(def ^:private svg-style {:width "100%" :height "auto" :display "block"})

(defn- svg-t [x y anc sz fill txt]
  [:text {:x (fmt-n x) :y (fmt-n y) :text-anchor anc :font-size (str sz) :fill fill} txt])

(def ^:private c-txt2 "var(--text-200,#d4d0c8)")

(def ^:private c-txt1 "var(--text-100,#ece9e3)")

(defn bar-chart
  "Pure responsive SVG bar chart (`:title :subtitle :items [{:label :value :color?}] :unit? :width? :height? :orientation?`)."
  {:malli/schema [:=> {:examples [[[{:items []}] [:section {:class "card stack-sm viz-card"} [:div {:class "tiny mono", :style {:padding "10px", :color "var(--text-300,#b0aa9e)"}} "No bar chart data."]]]]} [:cat [:map [:title {:optional true} [:maybe :string]] [:subtitle {:optional true} [:maybe :string]] [:unit {:optional true} [:maybe :string]] [:width {:optional true} [:maybe pos-int?]] [:height {:optional true} [:maybe pos-int?]] [:orientation {:optional true} [:maybe [:enum :horizontal :vertical]]] [:items {:optional true} [:maybe [:sequential [:map [:label :any] [:value number?] [:color {:optional true} [:maybe :string]]]]]]]] [:vector :any]], :cost/ticks 4000000, :cost/bytes 1048576}
  [{:keys [title subtitle items unit width height orientation]}]
  (let [rows (vec (keep #(when (and (map? %) (number? (:value %)))
                           {:label (clip-lbl (or (:label %) "-") 16) :value (finite-num (:value %) 0.0) :color (:color %)}) items))]
    (card-shell
     title subtitle
     (if (empty? rows)
       (empty-box "No bar chart data.")
       (let [max-v (max 1.0e-6 (reduce max 0.0 (map #(Math/abs ^double (:value %)) rows)))
             vw (max 320.0 (min 1200.0 (finite-num width 520.0)))]
         (if (= :vertical orientation)
           (let [vh (max 160.0 (min 640.0 (finite-num height 220.0)))
                 pl 48.0 pr 16.0 pt 22.0 pb 34.0 pw (- vw pl pr) ph (- vh pt pb)
                 n (count rows) step (/ pw (double n)) bw (max 6.0 (min 48.0 (* step 0.62)))
                 stride (max 1 (long (Math/ceil (/ (double n) 6.0))))]
             (into [:svg {:viewBox (str "0 0 " (fmt-n vw) " " (fmt-n vh)) :role "img" :aria-label (or title "Bar chart") :style svg-style}
                    [:line {:x1 (fmt-n pl) :y1 (fmt-n (+ pt ph)) :x2 (fmt-n (- vw pr)) :y2 (fmt-n (+ pt ph)) :stroke "var(--base-700,#3d3a36)" :stroke-width "1"}]]
                   (mapcat (fn [i {:keys [label value color]}]
                             (let [bh (max 2.0 (* (min 1.0 (/ (max 0.0 value) max-v)) ph))
                                   bx (+ pl (* i step) (/ (- step bw) 2.0)) by (- (+ pt ph) bh) cx (+ bx (/ bw 2.0))]
                               (cond-> [[:rect {:x (fmt-n bx) :y (fmt-n by) :width (fmt-n bw) :height (fmt-n bh) :rx "3" :fill (safe-color color i)}]]
                                 (<= n 8) (conj (svg-t cx (max 12.0 (- by 5.0)) "middle" 11 c-txt2 (clip-lbl (fmt-val value unit) 9)))
                                 (or (zero? (mod i stride)) (= i (dec n)))
                                 (conj (svg-t cx (- vh 10.0) (cond (zero? i) "start" (= i (dec n)) "end" :else "middle") 11 c-txt3 (clip-lbl label 9))))))
                           (range n) rows)))
           (let [pl 128.0 pr 76.0 pt 10.0 rh 30.0 bh 14.0 pw (max 80.0 (- vw pl pr))
                 vh (max (+ (* 2.0 pt) (* rh (count rows))) (finite-num height 0.0))]
             (into [:svg {:viewBox (str "0 0 " (fmt-n vw) " " (fmt-n vh)) :role "img" :aria-label (or title "Bar chart") :style svg-style}]
                   (mapcat (fn [i {:keys [label value color]}]
                             (let [ry (+ pt (* i rh)) by (+ ry (/ (- rh bh) 2.0)) cy (+ ry (/ rh 2.0) 4.0)
                                   bw (max 4.0 (* (min 1.0 (/ (max 0.0 value) max-v)) pw))]
                               [(svg-t (- pl 10.0) cy "end" 11.5 c-txt2 label)
                                [:rect {:x (fmt-n pl) :y (fmt-n by) :width (fmt-n pw) :height (fmt-n bh) :rx "4" :fill "var(--base-850,#252422)"}]
                                [:rect {:x (fmt-n pl) :y (fmt-n by) :width (fmt-n bw) :height (fmt-n bh) :rx "4" :fill (safe-color color i)}]
                                (svg-t (+ pl pw 8.0) cy "start" 11.5 c-txt1 (clip-lbl (fmt-val value unit) 9))]))
                           (range (count rows)) rows)))))))))

(defn line-chart
  "Pure responsive SVG line/area chart (`:title :subtitle :series [{:name :color? :points [{:x :y :label?}]}] :unit? :width? :height?`)."
  {:malli/schema [:=> {:examples [[[{:series []}] [:section {:class "card stack-sm viz-card"} [:div {:class "tiny mono", :style {:padding "10px", :color "var(--text-300,#b0aa9e)"}} "No line chart series."]]]]} [:cat [:map [:title {:optional true} [:maybe :string]] [:subtitle {:optional true} [:maybe :string]] [:unit {:optional true} [:maybe :string]] [:width {:optional true} [:maybe pos-int?]] [:height {:optional true} [:maybe pos-int?]] [:series {:optional true} [:maybe [:sequential [:map [:name {:optional true} [:maybe :string]] [:color {:optional true} [:maybe :string]] [:points [:sequential [:map [:x {:optional true} :any] [:y number?] [:label {:optional true} [:maybe :string]]]]]]]]]]] [:vector :any]], :cost/ticks 6000000, :cost/bytes 1048576}
  [{:keys [title subtitle series unit width height]}]
  (let [valid (vec (keep-indexed
                    (fn [i s]
                      (when (map? s)
                        (let [pts (vec (keep-indexed #(when (and (map? %2) (number? (:y %2)))
                                                        {:idx %1 :x (when (number? (:x %2)) (finite-num (:x %2) %1)) :y (finite-num (:y %2) 0.0)
                                                         :label (or (:label %2) (when (some? (:x %2)) (str (:x %2))) (str (inc %1)))}) (:points s)))]
                          (when (seq pts) {:name (clip-lbl (or (:name s) (str "Series " (inc i))) 20) :color (safe-color (:color s) i) :points pts}))))
                    series))]
    (card-shell
     title subtitle
     (if (empty? valid)
       (empty-box "No line chart series.")
       (let [vw (max 320.0 (min 1200.0 (finite-num width 560.0))) vh (max 160.0 (min 640.0 (finite-num height 220.0)))
             pl 54.0 pr 22.0 pt 16.0 pb 32.0 pw (- vw pl pr) ph (- vh pt pb)
             all (mapcat :points valid) ys (map :y all) y0 (reduce min ys) y1 (reduce max ys)
             [ymin ymax] (if (== y0 y1) [(- y0 1.0) (+ y1 1.0)] [y0 y1]) yr (- ymax ymin)
             num-x? (and (every? #(some? (:x %)) all) (not= (reduce min (map :x all)) (reduce max (map :x all))))
             xmin (if num-x? (reduce min (map :x all)) 0.0)
             xmax (if num-x? (reduce max (map :x all)) (double (max 1 (dec (reduce max 1 (map (comp count :points) valid))))))
             xr (max 1.0e-6 (- xmax xmin))
             sx #(if (and (not num-x?) (= 1 %2)) (+ pl (/ pw 2.0)) (+ pl (* (/ (- (if num-x? (:x %1) (double (:idx %1))) xmin) xr) pw)))
             sy #(+ pt (* (- 1.0 (/ (- % ymin) yr)) ph))
             bpts (:points (first valid)) bn (count bpts) mt (min 5 (max 2 (long (quot pw 95.0))))
             t-idxs (if (<= bn mt) (vec (range bn))
                      (let [st (max 1 (long (Math/ceil (/ (double (dec bn)) (double (dec mt))))))]
                        (vec (distinct (conj (vec (range 0 (max 1 (- bn (max 1 (quot st 2)))) st)) (dec bn))))))
             single? (= 1 (count valid))]
         (cond-> [:div {:class "stack-xs"}]
           (> (count valid) 1)
           (conj (into [:div {:class "pill-row"}]
                       (for [{:keys [name color]} valid]
                         [:span {:class "tiny mono" :style {:display "inline-flex" :align-items "center" :gap "5px" :color c-txt2}}
                          [:span {:style {:width "8px" :height "8px" :border-radius "999px" :background color :display "inline-block"}}] name])))
           true
           (conj (into [:svg {:viewBox (str "0 0 " (fmt-n vw) " " (fmt-n vh)) :role "img" :aria-label (or title "Line chart") :style svg-style}]
                       (concat
                        (for [k (range 4) :let [fr (/ (double k) 3.0) gy (+ pt (* fr ph))]]
                          [:g [:line (cond-> {:x1 (fmt-n pl) :y1 (fmt-n gy) :x2 (fmt-n (- vw pr)) :y2 (fmt-n gy) :stroke "var(--base-800,#302e2b)" :stroke-width "1"} (< k 3) (assoc :stroke-dasharray "3 3"))]
                           (svg-t (- pl 8.0) (+ gy 4.0) "end" 11 c-txt3 (clip-lbl (fmt-val (+ ymin (* (- 1.0 fr) yr)) unit) 8))])
                        (for [ti t-idxs :let [p (nth bpts ti)]]
                          (svg-t (sx p bn) (- vh 8.0) (cond (= ti (first t-idxs)) "start" (= ti (last t-idxs)) "end" :else "middle") 11 c-txt3 (clip-lbl (:label p) 10)))
                        (mapcat (fn [{:keys [color points]}]
                                  (let [n (count points) cs (mapv #(vector (sx % n) (sy (:y %))) points)
                                        dl (str/join " " (map-indexed (fn [i [x y]] (str (if (zero? i) "M" "L") (fmt-n x) " " (fmt-n y))) cs))
                                        [x0 _] (first cs) [xn _] (peek cs) yb (+ pt ph)]
                                    (vec (concat (when (and single? (>= n 2)) [[:path {:d (str dl " L" (fmt-n xn) " " (fmt-n yb) " L" (fmt-n x0) " " (fmt-n yb) " Z") :fill color :fill-opacity "0.14"}]])
                                                 (when (>= n 2) [[:path {:d dl :fill "none" :stroke color :stroke-width "2.2" :stroke-linecap "round" :stroke-linejoin "round"}]])
                                                 (when (<= n 24) (mapv (fn [[x y]] [:circle {:cx (fmt-n x) :cy (fmt-n y) :r (if (= 1 n) "4" "3") :fill color}]) cs))))))
                                valid))))))))))

(defn sparkline
  "Pure compact inline trend SVG (`:values [...] :color? :width? :height? :label?`)."
  {:malli/schema [:=> {:examples [[[{:values []}] [:span {:class "tiny mono", :style {:color "var(--text-300,#b0aa9e)"}} "—"]]]} [:cat [:map [:values {:optional true} [:maybe [:sequential number?]]] [:color {:optional true} [:maybe :string]] [:width {:optional true} [:maybe pos-int?]] [:height {:optional true} [:maybe pos-int?]] [:label {:optional true} [:maybe :string]]]] [:vector :any]], :cost/ticks 1000000, :cost/bytes 262144}
  [{:keys [values color width height label]}]
  (let [vs (vec (keep #(when (number? %) (finite-num % 0.0)) values))]
    (if (empty? vs)
      [:span {:class "tiny mono" :style {:color c-txt3}} "—"]
      (let [vw (max 48.0 (min 400.0 (finite-num width 120.0))) vh (max 16.0 (min 120.0 (finite-num height 28.0)))
            pw (- vw 8.0) ph (- vh 8.0) col (safe-color color 0)
            y0 (reduce min vs) y1 (reduce max vs) [lo hi] (if (== y0 y1) [(- y0 1.0) (+ y1 1.0)] [y0 y1]) yr (- hi lo)
            n (count vs)
            cs (mapv (fn [i y] [(if (= 1 n) (+ 4.0 (/ pw 2.0)) (+ 4.0 (* (/ (double i) (double (dec n))) pw)))
                                (+ 4.0 (* (- 1.0 (/ (- y lo) yr)) ph))]) (range n) vs)
            [lx ly] (peek cs)]
        (cond-> [:svg {:viewBox (str "0 0 " (fmt-n vw) " " (fmt-n vh)) :width (fmt-n vw) :height (fmt-n vh)
                       :role "img" :aria-label (or label "Trend sparkline")
                       :style {:max-width "100%" :height "auto" :display "inline-block" :vertical-align "middle"}}]
          (>= n 2) (conj [:polyline {:points (str/join " " (map (fn [[x y]] (str (fmt-n x) "," (fmt-n y))) cs))
                                     :fill "none" :stroke col :stroke-width "1.8" :stroke-linecap "round" :stroke-linejoin "round"}])
          true (conj [:circle {:cx (fmt-n lx) :cy (fmt-n ly) :r "2.5" :fill col}]))))))

(defn stat-grid
  "Pure responsive KPI metric grid (`:title? :items [{:label :value :delta? :tone? :sparkline?}]`)."
  {:malli/schema [:=> {:examples [[[{:items []}] [:section {:class "stack-sm viz-stat-grid"} [:div {:class "tiny mono", :style {:padding "10px", :color "var(--text-300,#b0aa9e)"}} "No KPI metrics."]]]]} [:cat [:map [:title {:optional true} [:maybe :string]] [:items {:optional true} [:maybe [:sequential [:map [:label :any] [:value :any] [:delta {:optional true} [:maybe :any]] [:tone {:optional true} [:maybe :keyword]] [:sparkline {:optional true} [:maybe [:sequential number?]]]]]]]]] [:vector :any]], :cost/ticks 2000000, :cost/bytes 1048576}
  [{:keys [title items]}]
  (let [rows (vec (filter map? items)) t (nonblank title)
        tone-col #(case % (:good :positive :success :green) "var(--success,#34d399)" (:bad :negative :error :red) "var(--error,#f87171)"
                        (:warn :warning :amber) "var(--signal,#f0b429)" (:info :blue) "var(--info,#60a5fa)" c-txt3)]
    (cond-> [:section {:class "stack-sm viz-stat-grid"}]
      t (conj [:h2 t])
      true
      (conj (if (empty? rows) (empty-box "No KPI metrics.")
              (into [:div {:style {:display "grid" :grid-template-columns "repeat(auto-fit,minmax(150px,1fr))" :gap "10px"}}]
                    (for [{:keys [label value delta tone] sp :sparkline} rows
                          :let [tc (tone-col tone) vs (if (number? value) (fmt-n value) (str (or value "—")))]]
                      (cond-> [:div {:class "card stack-xs"}
                               [:div {:class "tiny mono" :style {:color c-txt3 :text-transform "uppercase" :letter-spacing ".06em"}} (clip-lbl (or label "Metric") 28)]
                               (cond-> [:div {:class "row-between" :style {:align-items "baseline" :gap "8px"}}
                                        [:span {:class "mono strong" :style {:font-size "20px" :line-height "1.2" :color "var(--text-50,#faf9f7)"}} vs]]
                                 (some? delta) (conj [:span {:class "tiny mono strong" :style {:color tc}} (if (number? delta) (fmt-n delta) (str delta))]))]
                        (seq sp) (conj [:div {:style {:margin-top "4px"}} (sparkline {:values sp :color tc :width 120 :height 24})])))))))))
