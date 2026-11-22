(ns seon.instruments
  "Live engine cockpit instruments for Seon — zero fake data, zero giant layout functions.
   Each instrument is a small, composable Malli schema + live Datahike telemetry reader +
   dual-mode SVG/Markdown render function (`:human` Phosphor SVG dials/lamps, `:agent`/`:model`
   compact telemetry lines). Usable anywhere: Boards, Canvas, Workspace, or direct REPL evals."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.index.interface :as di]
            [datahike.reads :as reads]
            [seon.store :as store]
            [seon.store.query :as sq]))

(defn- finite-num? [x]
  (if (number? x)
    (let [d (double x)]
      (and (== d d) (< d 1.0e308) (> d -1.0e308)))
    false))

(defn- clamp01 [x]
  (if (finite-num? x)
    (max 0.0 (min 1.0 (double x)))
    0.0))

(defn- round1 [x]
  (if (finite-num? x)
    (/ (Math/round (* (double x) 10.0)) 10.0)
    0.0))

(defn- tone-color [tone]
  (case tone
    :ok   "var(--success)"
    :warn "var(--signal)"
    :err  "var(--error)"
    :info "var(--info)"
    "var(--signal)"))

(defn- polar-xy [cx cy r deg]
  (let [rad (* (double deg) (/ Math/PI 180.0))]
    [(round1 (+ cx (* r (Math/cos rad))))
     (round1 (+ cy (* r (Math/sin rad))))]))

(defn- arc-path [cx cy r start-deg end-deg]
  (let [[x1 y1] (polar-xy cx cy r start-deg)
        [x2 y2] (polar-xy cx cy r end-deg)
        large? (if (> (Math/abs (- end-deg start-deg)) 180.0) 1 0)]
    (str "M " x1 " " y1 " A " r " " r " 0 " large? " 1 " x2 " " y2)))

(defn arc-gauge-svg
  "Pure 220-degree Phosphor SVG radial dial (Tachometer / Temp / Fuel) with moving needle."
  {:malli/schema [:=> [:cat number? number? :map] [:vector :any]]
   :cost/ticks 500000
   :cost/bytes 131072}
  [val max-val {:keys [display unit sublabel tone]}]
  (let [ratio (clamp01 (if (and (finite-num? val) (finite-num? max-val) (pos? (double max-val)))
                         (/ (double val) (double max-val))
                         0.0))
        start-deg 160.0
        sweep 220.0
        end-deg (+ start-deg sweep)
        val-deg (+ start-deg (* sweep (max 0.015 ratio)))
        cx 90.0 cy 76.0 r 56.0
        [nx ny] (polar-xy cx cy 44.0 (+ start-deg (* sweep ratio)))
        col (tone-color tone)
        ticks (for [i (range 0 11)
                    :let [a (+ start-deg (* sweep (/ (double i) 10.0)))
                          [tx1 ty1] (polar-xy cx cy 62.0 a)
                          [tx2 ty2] (polar-xy cx cy (if (zero? (mod i 2)) 52.0 56.0) a)]]
                [:line {:x1 tx1 :y1 ty1 :x2 tx2 :y2 ty2
                        :stroke (if (>= i 8) "var(--error)" "var(--base-700)")
                        :stroke-width (if (zero? (mod i 2)) "1.5" "1")}])]
    [:svg {:class "gauge-svg" :viewBox "0 0 180 116" :width "180" :height "116" :role "img"
           :aria-label (str (or display val) " " (or unit ""))}
     [:path {:d (arc-path cx cy r start-deg end-deg)
             :fill "none" :stroke "var(--base-800)" :stroke-width "8" :stroke-linecap "round"}]
     [:path {:d (arc-path cx cy r start-deg val-deg)
             :fill "none" :stroke col :stroke-width "8" :stroke-linecap "round"}]
     ticks
     [:line {:x1 cx :y1 cy :x2 nx :y2 ny :stroke col :stroke-width "2.2" :stroke-linecap "round"}]
     [:circle {:cx cx :cy cy :r "4" :fill col}]
     [:text {:x cx :y "95" :text-anchor "middle" :fill "var(--text-50)"
             :font-family "var(--font-mono)" :font-size "17" :font-weight "700"}
      (str (or display (round1 val)) (when unit (str " " unit)))]
     (when sublabel
       [:text {:x cx :y "110" :text-anchor "middle" :fill "var(--text-400)"
               :font-family "var(--font-mono)" :font-size "9.5"}
        (str sublabel)])]))

(defn sparkline-svg
  "Pure Phosphor SVG sparkline for live eval latencies, cache hits, or benchmark trends."
  {:malli/schema [:=> [:cat [:vector number?] :map] [:vector :any]]
   :cost/ticks 500000
   :cost/bytes 131072}
  [values {:keys [tone label]}]
  (let [vs (vec (take-last 24 (filter finite-num? values)))
        n (count vs)
        col (tone-color tone)]
    (if (< n 2)
      [:div {:class "muted tiny mono"} (str (or label "Trend") ": awaiting samples")]
      (let [lo (double (apply min vs))
            hi (double (apply max vs))
            span (max 1e-6 (- hi lo))
            w 200.0 h 34.0 pad 4.0
            pts (map-indexed
                 (fn [i v]
                   (let [x (round1 (+ pad (* (- w (* 2 pad)) (/ (double i) (double (dec n))))))
                         y (round1 (- (- h pad) (* (- h (* 2 pad)) (/ (- (double v) lo) span))))]
                     [x y]))
                 vs)
            poly (str/join " " (map (fn [[x y]] (str x "," y)) pts))
            [lx ly] (last pts)]
        [:div {:class "spark-wrap"}
         (when label [:div {:class "muted tiny mono"} label])
         [:svg {:class "spark-svg" :viewBox "0 0 200 34" :width "200" :height "34"}
          [:polyline {:points poly :fill "none" :stroke col :stroke-width "1.8"
                      :stroke-linecap "round" :stroke-linejoin "round"}]
          [:circle {:cx lx :cy ly :r "3" :fill col}]]]))))

(defn- meter-bar [label display ratio tone]
  (let [pct (round1 (* 100.0 (clamp01 ratio)))]
    [:div {:class "meter-row"}
     [:div {:class "row-between tiny mono"}
      [:span {:class "muted"} label]
      [:span {:class "strong"} display]]
     [:div {:class "meter-track"}
      [:div {:class "meter-fill"
             :style (str "width:" pct "%;background:" (tone-color tone))}]]]))

;; ── Live Datahike Telemetry Readers (100% Real Datoms, Zero Fake Data) ─────────────────────────

(defonce ^:private ^:seon.flow/edge eval-tail-cache (atom {}))
(defonce ^:private ^:seon.flow/edge ns-heat-cache (atom {}))

(defn- forward-reads! [rs]
  (when-let [outer di/*reads*]
    (when (seq rs)
      (swap! outer into rs))))

(defn- newest-eval-entities [db n]
  (if-not (and db (contains? (:schema db) :seon.agent.eval/id))
    []
    (let [ds (vec (d/datoms db :aevt :seon.agent.eval/id))
          cnt (count ds)
          k [(get-in db [:config :branch] :main) (:max-tx db) cnt]
          {:keys [rows reads]}
          (store/lru-get!
           eval-tail-cache 16 k
           (fn []
             (let [[rows rs]
                   (reads/with-reads
                    #(let [start (max 0 (- cnt 32))
                           tail (subvec ds start cnt)]
                       (->> tail
                            (sort-by :tx >)
                            (mapv (fn [dt]
                                    (assoc (d/pull db '[:seon.agent.eval/agent :seon.agent.eval/handle :seon.agent.eval/turn
                                                        :seon.agent.eval/ms :seon.agent.eval/ticks :seon.agent.eval/bytes
                                                        :seon.agent.eval/result-schema :seon.agent.eval/error
                                                        :seon.agent.eval/intent :seon.agent.eval/prompt-tokens
                                                        :seon.agent.eval/cache-hit-tokens]
                                                   (:e dt))
                                           :tx (:tx dt)))))))]
               {:rows rows :reads rs})))]
      (forward-reads! reads)
      (vec (take n rows)))))

(defn- hottest-ns-summary [db]
  (if-not (and db (contains? (:schema db) :seon.code.form/src) (contains? (:schema db) :seon.code.form/hash))
    ['seon 0]
    (let [hds (d/datoms db :aevt :seon.code.form/hash)
          cnt (count hds)
          max-tx (reduce (fn [^long mx dt]
                           (let [^long tx (:tx dt)]
                             (if (> tx mx) tx mx)))
                         0 hds)
          k [(get-in db [:config :branch] :main) cnt max-tx]
          {:keys [val reads]}
          (store/lru-get!
           ns-heat-cache 16 k
           (fn []
             (let [[res rs]
                   (reads/with-reads
                    #(let [ns-by-e (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/ns))
                           ns-chars (reduce (fn [m dt]
                                              (if-let [nsym (get ns-by-e (:e dt))]
                                                (update m nsym (fnil + 0) (count (str (:v dt))))
                                                m))
                                            {}
                                            (d/datoms db :aevt :seon.code.form/src))
                           [hot-ns hot-ch] (if (seq ns-chars) (apply max-key val ns-chars) ['seon 0])]
                       [hot-ns (quot (long hot-ch) 4)]))]
               {:val res :reads rs})))]
      (forward-reads! reads)
      val)))

(defn read-tachometer
  "Reads live eval throughput, latency, tick load, and active agent count directly from Datahike."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.instrument/tachometer]
   :cost/ticks 15000000
   :cost/bytes 8388608}
  [db]
  (let [total-evals (sq/count-range db [nil :seon.agent.eval/id nil])
        recent (newest-eval-entities db 20)
        ms-vals (vec (keep #(when (number? (:seon.agent.eval/ms %)) (double (:seon.agent.eval/ms %))) (reverse recent)))
        avg-ms (if (seq ms-vals) (round1 (/ (reduce + 0.0 ms-vals) (count ms-vals))) 0.0)
        max-ms (if (seq ms-vals) (round1 (apply max ms-vals)) 0.0)
        tick-cap (long (or (when (contains? (:schema db) :seon.config/eval-ticks)
                             (:v (first (d/datoms db :aevt :seon.config/eval-ticks))))
                           100000000))
        peak-ticks (long (reduce max 0 (keep :seon.agent.eval/ticks recent)))
        peak-ticks-pct (round1 (* 100.0 (/ (double peak-ticks) (double (max 1 tick-cap)))))
        active-agents (max 0 (- (sq/count-range db [nil :seon.agent/id nil])
                                (sq/count-range db [nil :seon.agent/archived? true])))]
    (with-meta
      {:total-evals total-evals
       :active-agents active-agents
       :avg-ms avg-ms
       :max-ms max-ms
       :peak-ticks peak-ticks
       :tick-cap tick-cap
       :peak-ticks-pct peak-ticks-pct
       :latencies ms-vals}
      {:seon/schema :seon.instrument/tachometer})))

(defn read-temperature
  "Reads live allocation heat (MB vs 256 MB eval cap) and hottest namespace token density (vs 8k cap)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.instrument/temperature]
   :cost/ticks 15000000
   :cost/bytes 8388608}
  [db]
  (let [recent (newest-eval-entities db 24)
        cap-bytes (long (or (when (contains? (:schema db) :seon.config/eval-bytes)
                              (:v (first (d/datoms db :aevt :seon.config/eval-bytes))))
                            268435456))
        cap-mb (round1 (/ (double cap-bytes) 1048576.0))
        mb-vals (vec (keep #(when (number? (:seon.agent.eval/bytes %))
                              (round1 (/ (double (:seon.agent.eval/bytes %)) 1048576.0)))
                           (reverse recent)))
        sorted-mb (sort mb-vals)
        peak-mb (if (seq sorted-mb) (last sorted-mb) 0.0)
        p95-mb (if (seq sorted-mb) (nth sorted-mb (min (dec (count sorted-mb)) (int (* 0.95 (count sorted-mb))))) 0.0)
        heat-pct (round1 (* 100.0 (/ (double p95-mb) (max 1.0 cap-mb))))
        [hot-ns hot-tok] (hottest-ns-summary db)]
    (with-meta
      {:p95-mb p95-mb
       :peak-mb peak-mb
       :cap-mb cap-mb
       :heat-pct heat-pct
       :hottest-ns hot-ns
       :hottest-ns-tok hot-tok
       :ns-cap-tok 8000
       :mb-series mb-vals}
      {:seon/schema :seon.instrument/temperature})))

(defn read-fuel-cache
  "Reads live LLM KV-cache efficiency (`cache-hit-tokens / prompt-tokens`) from real evals & benchmark runs."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.instrument/fuel-cache]
   :cost/ticks 15000000
   :cost/bytes 8388608}
  [db]
  (let [prompt-by-e (if (contains? (:schema db) :seon.agent.eval/prompt-tokens)
                      (reduce (fn [m dt] (assoc m (:e dt) (long (:v dt)))) {} (d/datoms db :aevt :seon.agent.eval/prompt-tokens))
                      {})
        hit-datoms (if (contains? (:schema db) :seon.agent.eval/cache-hit-tokens)
                     (sort-by :tx (d/datoms db :aevt :seon.agent.eval/cache-hit-tokens))
                     [])
        tot-prompt (reduce + 0 (vals prompt-by-e))
        tot-hit (reduce + 0 (map #(long (:v %)) hit-datoms))
        eval-hit-pct (if (pos? tot-prompt) (round1 (* 100.0 (/ (double tot-hit) (double tot-prompt)))) 0.0)
        turn-series (vec (keep (fn [dt]
                                 (let [pt (get prompt-by-e (:e dt) 0)]
                                   (when (pos? pt)
                                     (round1 (* 100.0 (/ (double (:v dt)) (double pt)))))))
                               (take-last 20 hit-datoms)))
        ;; Also read latest benchmark run proxy cache efficiency
        runs (if (contains? (:schema db) :seon.bench.run/id)
               (->> (d/datoms db :aevt :seon.bench.run/id)
                    (map #(d/pull db '[:seon.bench.run/title :seon.bench.run/started-ms :seon.bench.run/proxy-stats] (:e %)))
                    (filter #(pos? (long (get-in % [:seon.bench.run/proxy-stats :prompt-tokens] 0))))
                    (sort-by #(long (or (:seon.bench.run/started-ms %) 0))))
               [])
        latest-run (last runs)
        ps (:seon.bench.run/proxy-stats latest-run)
        b-prompt (long (or (:prompt-tokens ps) 0))
        b-hit (long (or (:cache-hit-tokens ps) 0))
        bench-hit-pct (if (pos? b-prompt) (round1 (* 100.0 (/ (double b-hit) (double b-prompt)))) eval-hit-pct)]
    (with-meta
      {:hit-pct (if (pos? bench-hit-pct) bench-hit-pct eval-hit-pct)
       :eval-hit-pct eval-hit-pct
       :bench-hit-pct bench-hit-pct
       :cached-tokens (+ tot-hit b-hit)
       :prompt-tokens (+ tot-prompt b-prompt)
       :bench-title (or (:seon.bench.run/title latest-run) "Live eval window")
       :turn-series turn-series}
      {:seon/schema :seon.instrument/fuel-cache})))

(defn read-oil-pressure
  "Reads merge-rate pressure, cached green test count, eval error rate, and real benchmark run outcomes."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.instrument/oil-pressure]
   :cost/ticks 15000000
   :cost/bytes 8388608}
  [db]
  (let [green-tests (sq/count-range db [nil :seon.test/pass? true])
        recent (newest-eval-entities db 30)
        err-n (count (filter #(and (:seon.agent.eval/error %) (not (str/blank? (str (:seon.agent.eval/error %))))) recent))
        err-pct (if (seq recent) (round1 (* 100.0 (/ (double err-n) (count recent)))) 0.0)
        runs (if (contains? (:schema db) :seon.bench.run/id)
               (->> (d/datoms db :aevt :seon.bench.run/id)
                    (map #(d/pull db '[:seon.bench.run/title :seon.bench.run/started-ms :seon.bench.run/passed
                                       :seon.bench.run/total :seon.bench.run/trials :seon.bench.run/status] (:e %)))
                    (sort-by #(long (or (:seon.bench.run/started-ms %) 0)))
                    vec)
               [])
        latest-passing (or (last (filter #(pos? (long (or (:seon.bench.run/passed %) 0))) runs)) (last runs))
        passed (long (or (:seon.bench.run/passed latest-passing) 0))
        total (long (or (:seon.bench.run/total latest-passing) 0))
        pass-pct (if (pos? total) (round1 (* 100.0 (/ (double passed) (double total)))) 100.0)
        trials (:seon.bench.run/trials latest-passing)
        mean-evals (if (seq trials)
                     (round1 (/ (reduce + 0.0 (map #(double (or (:evals %) 0)) trials)) (count trials)))
                     0.0)
        fixes-count (sq/count-range db [nil :seon.fix/id nil])]
    (with-meta
      {:green-tests green-tests
       :eval-error-pct err-pct
       :pass-pct pass-pct
       :passed passed
       :total total
       :mean-evals mean-evals
       :bench-runs (count runs)
       :fixes-count fixes-count
       :latest-title (or (:seon.bench.run/title latest-passing) "No benchmark runs yet")}
      {:seon/schema :seon.instrument/oil-pressure})))

(defn read-warning-lamps
  "Reads live Check-Engine warning lamps directly from `seon.health/report` and open `:seon.error/*` datoms."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.instrument/warning-lamps]
   :cost/ticks 25000000
   :cost/bytes 16777216}
  [db]
  (let [health-fn (requiring-resolve 'seon.health/report)
        rep (health-fn db)
        counts (or (:counts rep) {})
        open-errs (if (contains? (:schema db) :seon.error/status)
                    (mapv #(d/pull db '[:seon.error/id :seon.error/kind :seon.error/severity :seon.error/agent :seon.error/message] (:e %))
                          (d/datoms db :avet :seon.error/status :open))
                    [])
        n-open (count open-errs)
        n-hard-err (count (filter #(= :error (:severity %)) (:problems rep)))
        oversized (long (or (:oversized-namespace counts) 0))
        silent-c (long (or (:silent-catch-all counts) 0))
        slow-r (long (or (:slow-render counts) 0))
        large-fn (long (or (:large-function counts) 0))
        lamps [{:id :health :label "Health Gate" :status (if (:ok? rep) :ok :err)
                :value (if (:ok? rep) "GREEN" (str n-hard-err " ERR"))}
               {:id :open-err :label "Open Faults" :status (cond (some #(= :error (:seon.error/severity %)) open-errs) :err (pos? n-open) :warn :else :ok)
                :value (if (zero? n-open) "0 OPEN" (str n-open " OPEN"))}
               {:id :oversized :label "8k NS Budget" :status (if (pos? oversized) :warn :ok)
                :value (str oversized " OVER")}
               {:id :silent :label "Silent Catch" :status (if (pos? silent-c) :warn :ok)
                :value (str silent-c " FOUND")}
               {:id :frame :label "40ms Frame" :status (if (pos? slow-r) :warn :ok)
                :value (if (zero? slow-r) "NOMINAL" (str slow-r " SLOW"))}
               {:id :large-fn :label "Large Fns" :status (if (> large-fn 20) :warn :ok)
                :value (str large-fn " ADV")}]]
    (with-meta
      {:health-ok? (boolean (:ok? rep))
       :open-errors open-errs
       :lamps lamps}
      {:seon/schema :seon.instrument/warning-lamps})))

(defn read-eval-feed
  "Reads the newest 8 real `:seon.agent.eval/*` executions directly from Datahike."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.instrument/eval-feed]
   :cost/ticks 15000000
   :cost/bytes 8388608}
  [db]
  (let [total (sq/count-range db [nil :seon.agent.eval/id nil])
        recent (newest-eval-entities db 8)
        entries (mapv (fn [e]
                        {:tx (:tx e)
                         :agent (:seon.agent.eval/agent e)
                         :handle (:seon.agent.eval/handle e)
                         :ms (round1 (or (:seon.agent.eval/ms e) 0.0))
                         :ticks (long (or (:seon.agent.eval/ticks e) 0))
                         :mb (round1 (/ (double (or (:seon.agent.eval/bytes e) 0)) 1048576.0))
                         :schema (:seon.agent.eval/result-schema e)
                         :intent (:seon.agent.eval/intent e)
                         :ok? (str/blank? (str (:seon.agent.eval/error e)))})
                      recent)]
    (with-meta
      {:total-evals total
       :entries entries}
      {:seon/schema :seon.instrument/eval-feed})))

(defn read-instrument
  "General dispatcher reading any live engine instrument (`:tachometer`, `:temperature`, `:fuel-cache`, `:oil-pressure`, `:warning-lamps`, `:eval-feed`)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword] :map]
   :cost/ticks 25000000
   :cost/bytes 16777216}
  [db kind]
  (case kind
    :tachometer    (read-tachometer db)
    :temperature   (read-temperature db)
    :fuel-cache    (read-fuel-cache db)
    :oil-pressure  (read-oil-pressure db)
    :warning-lamps (read-warning-lamps db)
    :eval-feed     (read-eval-feed db)
    {:unknown-instrument kind}))

;; ── 6 Small, Focused, Dual-Mode Schema Render Functions (:human SVG + :agent/:model Text) ─────

(defn render-tachometer
  "Schema-attached renderer (`:seon.render/hiccup`) for `:seon.instrument/tachometer`."
  {:malli/schema [:=> [:cat :map :seon.instrument/tachometer] :any]
   :cost/ticks 3000000
   :cost/bytes 1048576}
  [{:keys [mode]} {:keys [total-evals active-agents avg-ms max-ms peak-ticks-pct latencies]}]
  (let [tone (cond (> peak-ticks-pct 75.0) :err (> peak-ticks-pct 40.0) :warn :else :ok)]
    (if (not= :human (or mode :human))
      [:div [:p (str "Tachometer: " total-evals " total evals · " active-agents " active agents · avg " avg-ms " ms (max " max-ms " ms) · peak tick load " peak-ticks-pct "% of 100M cap")]]
      [:div {:class "inst-body"}
       [:div {:class "inst-dial-row"}
        (arc-gauge-svg peak-ticks-pct 100.0 {:display (str peak-ticks-pct "%") :sublabel "Peak Tick Cap" :tone tone})
        [:div {:class "inst-stats stack-xs"}
         [:div {:class "pill-row"}
          [:span {:class "badge blue mono"} (str total-evals " evals")]
          [:span {:class "badge green mono"} (str active-agents " agents")]]
         (meter-bar "Mean Eval Latency" (str avg-ms " ms") (/ (double avg-ms) 500.0) (if (> avg-ms 250.0) :warn :ok))
         (meter-bar "Peak Eval Latency" (str max-ms " ms") (/ (double max-ms) 1000.0) (if (> max-ms 600.0) :warn :info))
         (sparkline-svg latencies {:tone tone :label "Recent 20 evals (ms)"})]]])))

(defn render-temperature
  "Schema-attached renderer (`:seon.render/hiccup`) for `:seon.instrument/temperature`."
  {:malli/schema [:=> [:cat :map :seon.instrument/temperature] :any]
   :cost/ticks 3000000
   :cost/bytes 1048576}
  [{:keys [mode]} {:keys [p95-mb peak-mb cap-mb heat-pct hottest-ns hottest-ns-tok ns-cap-tok mb-series]}]
  (let [tone (cond (> heat-pct 80.0) :err (> heat-pct 55.0) :warn :else :ok)
        ns-ratio (/ (double hottest-ns-tok) (double (max 1 ns-cap-tok)))]
    (if (not= :human (or mode :human))
      [:div [:p (str "Memory/Temp: p95 " p95-mb " MB / " cap-mb " MB cap (" heat-pct "% · peak " peak-mb " MB) · hottest ns " hottest-ns " (" hottest-ns-tok "/" ns-cap-tok " tok)")]]
      [:div {:class "inst-body"}
       [:div {:class "inst-dial-row"}
        (arc-gauge-svg p95-mb cap-mb {:display (str p95-mb) :unit "MB" :sublabel (str "p95 of " cap-mb " MB") :tone tone})
        [:div {:class "inst-stats stack-xs"}
         (meter-bar "Peak Eval Allocation" (str peak-mb " MB") (/ (double peak-mb) (max 1.0 cap-mb)) (if (> peak-mb 200.0) :warn :ok))
         (meter-bar (str "Hottest NS (" hottest-ns ")") (str hottest-ns-tok " / " ns-cap-tok " tok") ns-ratio (if (> ns-ratio 0.9) :warn :info))
         (sparkline-svg mb-series {:tone tone :label "Recent allocs (MB)"})]]])))

(defn render-fuel-cache
  "Schema-attached renderer (`:seon.render/hiccup`) for `:seon.instrument/fuel-cache`."
  {:malli/schema [:=> [:cat :map :seon.instrument/fuel-cache] :any]
   :cost/ticks 3000000
   :cost/bytes 1048576}
  [{:keys [mode]} {:keys [hit-pct eval-hit-pct bench-hit-pct cached-tokens prompt-tokens bench-title turn-series]}]
  (let [tone (cond (>= hit-pct 85.0) :ok (>= hit-pct 65.0) :warn :else :err)
        fmt-k (fn [n] (if (>= n 1000) (str (round1 (/ (double n) 1000.0)) "k") (str n)))]
    (if (not= :human (or mode :human))
      [:div [:p (str "KV-Cache Fuel: " hit-pct "% hit rate (bench " bench-hit-pct "% · live evals " eval-hit-pct "% · " (fmt-k cached-tokens) "/" (fmt-k prompt-tokens) " tok cached)")]]
      [:div {:class "inst-body"}
       [:div {:class "inst-dial-row"}
        (arc-gauge-svg hit-pct 100.0 {:display (str hit-pct "%") :sublabel "KV-Cache Efficiency" :tone tone})
        [:div {:class "inst-stats stack-xs"}
         [:div {:class "pill-row"}
          [:span {:class "badge green mono"} (str (fmt-k cached-tokens) " cached")]
          [:span {:class "pill mono"} (str (fmt-k prompt-tokens) " prompt")]]
         (meter-bar "Latest Benchmark Cache" (str bench-hit-pct "%") (/ (double bench-hit-pct) 100.0) tone)
         (meter-bar "Live Eval Cache Window" (str eval-hit-pct "%") (/ (double eval-hit-pct) 100.0) (if (>= eval-hit-pct 80.0) :ok :info))
         (if (>= (count turn-series) 2)
           (sparkline-svg turn-series {:tone tone :label "Per-turn cache hit %"})
           [:div {:class "muted tiny"} bench-title])]]])))

(defn render-oil-pressure
  "Schema-attached renderer (`:seon.render/hiccup`) for `:seon.instrument/oil-pressure`."
  {:malli/schema [:=> [:cat :map :seon.instrument/oil-pressure] :any]
   :cost/ticks 3000000
   :cost/bytes 1048576}
  [{:keys [mode]} {:keys [green-tests eval-error-pct pass-pct passed total mean-evals bench-runs fixes-count latest-title]}]
  (let [tone (cond (and (>= pass-pct 100.0) (< eval-error-pct 15.0)) :ok (>= pass-pct 66.0) :warn :else :err)]
    (if (not= :human (or mode :human))
      [:div [:p (str "Oil Pressure: " passed "/" total " latest bench merged (" pass-pct "%, " mean-evals " mean evals) · " green-tests " cached green tests · " eval-error-pct "% recent eval error · " fixes-count " class fixes")]]
      [:div {:class "inst-body stack-xs"}
       [:div {:class "pill-row"}
        [:span {:class "badge green mono"} (str green-tests " green tests")]
        [:span {:class "badge amber mono"} (str mean-evals " mean evals/merge")]
        [:span {:class "pill mono"} (str bench-runs " bench runs")]
        [:span {:class "pill mono"} (str fixes-count " class fixes")]]
       (meter-bar (str "Latest Benchmark Pass (" passed "/" total ")") (str pass-pct "%") (/ (double pass-pct) 100.0) tone)
       (meter-bar "Recent Eval Clean Rate" (str (round1 (- 100.0 (double eval-error-pct))) "%") (/ (- 100.0 (double eval-error-pct)) 100.0) (if (< eval-error-pct 15.0) :ok :warn))
       [:div {:class "muted tiny truncate"} (str "Latest passing round: " latest-title)]])))

(defn render-warning-lamps
  "Schema-attached renderer (`:seon.render/hiccup`) for `:seon.instrument/warning-lamps`."
  {:malli/schema [:=> [:cat :map :seon.instrument/warning-lamps] :any]
   :cost/ticks 3000000
   :cost/bytes 1048576}
  [{:keys [mode]} {:keys [health-ok? open-errors lamps]}]
  (if (not= :human (or mode :human))
    [:div [:p (str "Warning Lamps (" (if health-ok? "OK" "ALERT") "): "
                   (str/join " | " (map #(str (:label %) "=" (:value %)) lamps)))]]
    [:div {:class "inst-body stack-xs"}
     [:div {:class "lamp-grid"}
      (for [{:keys [label status value]} lamps]
        [:div {:class (str "lamp-pill is-" (name status))}
         [:span {:class (str "lamp-led is-" (name status))}]
         [:div {:class "lamp-text"}
          [:span {:class "lamp-label"} label]
          [:span {:class "lamp-val mono"} value]]])]
     (when (seq open-errors)
       [:div {:class "notice red tiny mono"}
        (str "Active fault(s): "
             (str/join " · " (map #(str (:seon.error/kind %) " (" (:seon.error/agent %) "): " (:seon.error/message %))
                                  (take 3 open-errors))))])]))

(defn render-eval-feed
  "Schema-attached renderer (`:seon.render/hiccup`) for `:seon.instrument/eval-feed`."
  {:malli/schema [:=> [:cat :map :seon.instrument/eval-feed] :any]
   :cost/ticks 3000000
   :cost/bytes 1048576}
  [{:keys [mode]} {:keys [total-evals entries]}]
  (if (not= :human (or mode :human))
    [:div
     [:p (str "Live Eval Tape (" total-evals " total; newest " (count entries) "):")]
     (for [{:keys [agent handle ms mb schema ok?]} entries]
       [:p (str "- " agent " " handle " · " (if ok? "ok" "err") " · " ms " ms · " mb " MB" (when schema (str " · " schema)))])]
    [:div {:class "table-scroll"}
     [:table {:class "data-table"}
      [:thead [:tr [:th "Agent"] [:th "Handle"] [:th "Status"] [:th "ms"] [:th "MB"] [:th "Schema / Intent"]]]
      [:tbody
       (for [{:keys [agent handle ms mb schema intent ok?]} entries]
         [:tr
          [:td [:code {:class "mono tiny"} (str agent)]]
          [:td [:code {:class "mono tiny"} (str (or handle "—"))]]
          [:td [:span {:class (str "badge " (if ok? "green" "red"))} (if ok? "ok" "err")]]
          [:td {:class "mono"} (str ms)]
          [:td {:class "mono"} (str mb)]
          [:td {:class "mono tiny"} (or (when schema (str schema)) (not-empty (str intent)) "—")]])]]]))
