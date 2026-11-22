(ns seon.signals
  "Five ambient operational signals (Flow, Pressure, Context, Proof, Health) derived
   via fast indexed Datahike reads (< 5 ms), plus 2-level pure `ns-map -> ns-map`
   Hiccup renderers discovered automatically via `:seon.code.var/input` + `:seon.ui/hiccup`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store]
            [seon.signals.scoreboard :as scoreboard]
            [seon.store.query :as sq]))

(def ^:private level-rank {:calm 0 :warm 1 :hot 2})

(def ^:private signals-snap-cache (store/lru-cache 16))

(defn- round1 [x]
  (if (number? x)
    (let [v (double x)]
      (if (and (== v v) (not (Double/isInfinite v)))
        (/ (double (Math/round (* v 10.0))) 10.0)
        0.0))
    0.0))

(defn- clamp-pct [x] (max 0.0 (min 100.0 (round1 x))))

(defn- worst-level [levels]
  (reduce #(if (> (get level-rank %2 0) (get level-rank %1 0)) %2 %1) :calm levels))

(defn- level-class [lvl]
  (case lvl :hot "sig-hot" :warm "sig-warm" "sig-calm"))

(defn- db-schema [db]
  (or (:schema db) (when db (d/schema db)) {}))

(defn- db-branch [db]
  (or (get-in db [:config :branch])
      (get-in (:origin-db db) [:config :branch])
      :main))

(defn- db-tx [db]
  (long (or (:max-tx db) (:time-point db) (:max-tx (:origin-db db)) 0)))

(defn- eavt-map [db eid]
  (when (integer? eid)
    (let [dh (db-schema db)]
      (reduce (fn [m dt]
                (let [a (:a dt) v (:v dt)]
                  (if (= :db.cardinality/many (get-in dh [a :db/cardinality]))
                    (update m a (fnil conj #{}) v)
                    (assoc m a v))))
              {}
              (d/datoms db :eavt eid)))))

(defn- fast-open-errors [db]
  (if-not (and db (contains? (db-schema db) :seon.error/status))
    []
    (mapv #(eavt-map db (:e %)) (d/datoms db :avet :seon.error/status :open))))

(defn- recent-evals [db limit]
  (if-not (and db (contains? (db-schema db) :seon.agent.eval/id))
    []
    (mapv #(eavt-map db (:e %))
          (sq/slice-datoms db [nil :seon.agent.eval/id nil] {:limit limit :reverse? true}))))

(defn- branch-test-run [db br]
  (when (and db (contains? (db-schema db) :seon.test.run/branch))
    (or (when-let [e (:e (first (d/datoms db :avet :seon.test.run/branch br)))]
          (eavt-map db e))
        (when (not= br :main)
          (when-let [e (:e (first (d/datoms db :avet :seon.test.run/branch :main)))]
            (eavt-map db e))))))

(defn- branch-failing-count [db br]
  (let [run (branch-test-run db br)]
    (if (false? (:seon.test.run/pass? run))
      (long (max 1 (count (:seon.test.run/failed-digests run))))
      0)))

(defn- flow-signal-from [db open-errs]
  (let [dh (db-schema db)
        err-agents (into #{} (keep :seon.error/agent) open-errs)
        arch-eids (if (dh :seon.agent/archived?)
                    (into #{} (map :e) (d/datoms db :avet :seon.agent/archived? true))
                    #{})
        active-eids (if (dh :seon.agent/id)
                      (into [] (comp (map :e) (remove arch-eids)) (d/datoms db :aevt :seon.agent/id))
                      [])
        counts
        (reduce (fn [acc eid]
                  (let [a (eavt-map db eid)
                        aid (:seon.agent/id a)
                        role (:seon.agent/role a)
                        blocked? (or (contains? err-agents aid)
                                     (and (seq (:seon.agent/awaiting a))
                                          (or (:seon.agent/waiting? a) (:seon.agent/paused? a))))
                        stalled? (and (not blocked?) (boolean (:seon.agent/paused? a)) (not (:seon.agent/ready? a)))
                        ready? (and (not blocked?) (boolean (:seon.agent/ready? a)))
                        waiting? (and (not blocked?) (not stalled?) (not ready?) (boolean (:seon.agent/waiting? a)))
                        running? (and (not blocked?) (not stalled?) (not ready?) (not waiting?) (not= :repl role))]
                    (cond-> (update acc :active inc)
                      blocked? (update :blocked inc)
                      stalled? (update :stalled inc)
                      ready?   (update :ready inc)
                      waiting? (update :waiting inc)
                      running? (update :running inc))))
                {:active 0 :running 0 :waiting 0 :stalled 0 :blocked 0 :ready 0}
                active-eids)
        {:keys [running waiting stalled blocked ready]} counts
        level (cond (pos? blocked) :hot (pos? stalled) :warm :else :calm)
        pulse? (boolean (or (pos? running) (pos? blocked)))
        summary (str running " running · " waiting " waiting"
                     (when (pos? stalled) (str " · " stalled " stalled"))
                     (when (pos? blocked) (str " · " blocked " blocked"))
                     (when (pos? ready) (str " · " ready " ready")))]
    (with-meta (assoc counts :level level :pulse? pulse? :summary summary)
      {:seon/schema :seon.signal/flow})))

(defn read-flow-signal
  "Reads ambient agent flow & stall counts (running, waiting, stalled, blocked, ready + pulse)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.signal/flow]
   :cost/ticks 5000000}
  [db]
  (flow-signal-from db (fast-open-errors db)))

(defn- parse-ms-from-msg [s]
  (when (string? s)
    (when-let [[_ num-s] (re-find #"(\d+(?:\.\d+)?)\s*ms" s)]
      (Double/parseDouble num-s))))

(defn- pressure-signal-from [db evals open-errs]
  (let [cfg (when (contains? (db-schema db) :seon.config/key)
              (when-let [ce (:e (first (d/datoms db :avet :seon.config/key :seon.config/root)))]
                (eavt-map db ce)))
        tick-cap (max 1 (long (or (:seon.config/eval-ticks cfg) 100000000)))
        byte-cap (max 1 (long (or (:seon.config/eval-bytes cfg) 268435456)))
        cap-mb (round1 (/ (double byte-cap) 1048576.0))
        peak-ticks (reduce max 0 (map #(long (or (:seon.agent.eval/ticks %) 0)) evals))
        peak-bytes (reduce max 0 (map #(long (or (:seon.agent.eval/bytes %) 0)) evals))
        peak-mb (round1 (/ (double peak-bytes) 1048576.0))
        ticks-pct (clamp-pct (* 100.0 (/ (double peak-ticks) (double tick-cap))))
        bytes-pct (clamp-pct (* 100.0 (/ (double peak-bytes) (double byte-cap))))
        slow-errs (filterv #(= :slow-render (:seon.error/kind %)) open-errs)
        slow-n (count slow-errs)
        slow-ms (reduce max 0.0 (keep #(or (when (number? (get-in % [:seon.error/data :ms]))
                                             (double (get-in % [:seon.error/data :ms])))
                                           (parse-ms-from-msg (:seon.error/message %)))
                                      slow-errs))
        eval-ms (let [ms-v (keep #(when (number? (:seon.agent.eval/ms %)) (double (:seon.agent.eval/ms %))) evals)]
                  (if (seq ms-v) (round1 (/ (reduce + 0.0 ms-v) (count ms-v))) 0.0))
        frame-ms (round1 (if (pos? slow-ms) slow-ms (min 40.0 eval-ms)))
        frame-budget 40.0
        level (cond
                (or (>= ticks-pct 80.0) (>= bytes-pct 80.0) (>= slow-n 2) (> frame-ms 120.0)) :hot
                (or (>= ticks-pct 50.0) (>= bytes-pct 50.0) (pos? slow-n) (> frame-ms frame-budget)) :warm
                :else :calm)
        summary (str "ticks " ticks-pct "% · mem " bytes-pct "% (" peak-mb "MB)"
                     (if (pos? slow-n)
                       (str " · " slow-n " slow frame (" frame-ms "ms)")
                       (str " · frame " frame-ms "ms")))]
    (with-meta
      {:level level :ticks-pct ticks-pct :bytes-pct bytes-pct
       :frame-ms frame-ms :frame-budget-ms frame-budget :slow-renders slow-n
       :peak-mb peak-mb :cap-mb cap-mb :eval-sample-count (count evals) :summary summary}
      {:seon/schema :seon.signal/pressure})))

(defn read-pressure-signal
  "Reads ambient eval tick/byte allocation pressure and UI frame-ms pressure."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.signal/pressure]
   :cost/ticks 5000000}
  [db]
  (pressure-signal-from db (recent-evals db 12) (fast-open-errors db)))

(defn- bench-cache-fallback [db]
  (if-not (contains? (db-schema db) :seon.bench.run/id)
    [0 0]
    (let [latest (->> (sq/slice-datoms db [nil :seon.bench.run/id nil] {:limit 4 :reverse? true})
                      (map #(eavt-map db (:e %)))
                      (filter #(pos? (long (or (get-in % [:seon.bench.run/proxy-stats :prompt-tokens]) 0))))
                      (sort-by #(long (or (:seon.bench.run/started-ms %) 0)))
                      last)
          ps (:seon.bench.run/proxy-stats latest)]
      [(long (or (:prompt-tokens ps) 0)) (long (or (:cache-hit-tokens ps) 0))])))

(defn- count-prefix-busts [evals]
  (reduce (fn [n [_ es]]
            (let [phs (->> es
                           (filter :seon.agent.eval/prefix-hash)
                           (sort-by #(long (or (:seon.agent.eval/turn %) 0)))
                           (map :seon.agent.eval/prefix-hash))]
              (+ n (count (filter (fn [[a b]] (not= a b)) (partition 2 1 phs))))))
          0 (group-by :seon.agent.eval/agent evals)))

(defn- context-signal-from [db evals]
  (let [tot-p0 (reduce + 0 (map #(long (or (:seon.agent.eval/prompt-tokens %) 0)) evals))
        tot-h0 (reduce + 0 (map #(long (or (:seon.agent.eval/cache-hit-tokens %) 0)) evals))
        tot-m0 (reduce + 0 (map #(long (or (:seon.agent.eval/cache-miss-tokens %) 0)) evals))
        [tot-p tot-h] (if (pos? (+ tot-p0 tot-h0 tot-m0))
                        [(max tot-p0 (+ tot-h0 tot-m0)) tot-h0]
                        (bench-cache-fallback db))
        reuse-pct (if (pos? tot-p) (clamp-pct (* 100.0 (/ (double tot-h) (double tot-p)))) 100.0)
        p-evals (filter #(pos? (long (or (:seon.agent.eval/prompt-tokens %) 0))) evals)
        avg-p (if (seq p-evals)
                (/ (double (reduce + 0 (map #(long (:seon.agent.eval/prompt-tokens %)) p-evals)))
                   (count p-evals))
                0.0)
        budget-pct (if (pos? avg-p) (clamp-pct (* 100.0 (/ avg-p 32000.0))) 0.0)
        n-ev (count evals)
        ok-n (count (filter #(str/blank? (str (:seon.agent.eval/error %))) evals))
        ok-pct (if (pos? n-ev) (clamp-pct (* 100.0 (/ (double ok-n) (double n-ev)))) 100.0)
        busts (long (count-prefix-busts evals))
        level (cond
                (or (and (pos? tot-p) (< reuse-pct 60.0)) (< ok-pct 70.0) (>= busts 3)) :hot
                (or (and (pos? tot-p) (< reuse-pct 80.0)) (< ok-pct 90.0) (pos? busts) (>= budget-pct 75.0)) :warm
                :else :calm)
        summary (str "KV " reuse-pct "% · budget " budget-pct "% · ok " ok-pct "%"
                     (when (pos? busts) (str " · " busts " bust")))]
    (with-meta
      {:level level :prefix-reuse-pct reuse-pct :budget-util-pct budget-pct
       :eval-ok-pct ok-pct :prompt-tokens (long tot-p) :cached-tokens (long tot-h)
       :prefix-busts busts :summary summary}
      {:seon/schema :seon.signal/context})))

(defn read-context-signal
  "Reads ambient KV-cache prefix reuse %, token budget utilization, and eval efficiency."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.signal/context]
   :cost/ticks 5000000}
  [db]
  (context-signal-from db (recent-evals db 12)))

(defn- proof-signal-from [db]
  (let [dh (db-schema db)
        br (db-branch db)
        total-vars (if (dh :seon.code.var/test?) (sq/count-range db [nil :seon.code.var/test? true]) 0)
        run (branch-test-run db br)
        failing (branch-failing-count db br)
        ran-cached (+ (long (or (:seon.test.run/ran run) 0)) (long (or (:seon.test.run/cached run) 0)))
        green (long (cond
                      (and (true? (:seon.test.run/pass? run)) (pos? ran-cached)) ran-cached
                      (pos? ran-cached) (max 0 (- ran-cached failing))
                      :else 0))
        total (long (max total-vars (+ green failing)))
        unproved (long (if (and (true? (:seon.test.run/pass? run)) (zero? failing))
                         0
                         (max 0 (- total (+ green failing)))))
        merge-ready? (boolean (and (zero? failing) (or (true? (:seon.test.run/pass? run))
                                                       (and (= br :main) (zero? unproved)))))
        level (cond (pos? failing) :hot (or (pos? unproved) (not merge-ready?)) :warm :else :calm)
        summary (str green " green · " failing " fail"
                     (when (pos? unproved) (str " · " unproved " unproved"))
                     (if merge-ready? " · merge-ready" " · unmerged"))]
    (with-meta
      {:level level :green green :failing failing :unproved unproved
       :total-tests total :merge-ready? merge-ready? :branch br :summary summary}
      {:seon/schema :seon.signal/proof})))

(defn read-proof-signal
  "Reads ambient cached green / failing / unproved test counts and branch merge readiness."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.signal/proof]
   :cost/ticks 5000000}
  [db]
  (proof-signal-from db))

(defn- health-signal-from [db open-errs]
  (let [br (db-branch db)
        hard-errs (count (filter #(contains? #{:error :fatal} (:seon.error/severity %)) open-errs))
        warn-errs (count (filter #(= :warning (:seon.error/severity %)) open-errs))
        failing (branch-failing-count db br)
        oversized-ns (count (filter #(= :oversized-namespace (:seon.error/kind %)) open-errs))
        large-fns (count (filter #(= :large-function (:seon.error/kind %)) open-errs))
        errors (long (+ hard-errs failing))
        warnings (long (+ warn-errs oversized-ns))
        ok? (and (zero? errors) (zero? oversized-ns))
        level (cond
                (or (not ok?) (pos? errors) (pos? oversized-ns)) :hot
                (or (pos? (count open-errs)) (pos? warnings)) :warm
                :else :calm)
        summary (str (if ok? "healthy" (str errors " err"))
                     " · " (count open-errs) " open · " warnings " warn · " oversized-ns " oversized")]
    (with-meta
      {:level level :ok? ok? :errors errors :warnings warnings
       :open-errors (long (count open-errs)) :oversized-ns (long oversized-ns)
       :large-fns (long large-fns) :summary summary}
      {:seon/schema :seon.signal/health})))

(defn read-health-signal
  "Reads ambient structural & runtime health counts (errors, warnings, open errors, oversized namespaces)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.signal/health]
   :cost/ticks 10000000}
  [db]
  (health-signal-from db (fast-open-errors db)))

(defn all-signals
  "Computes all 5 ambient operational signals (flow, pressure, context, proof, health) cleanly in < 5 ms.
   Cached per Datahike commit-id via `store/lru-get!` with transparent `datahike.reads` replay."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.signal/snapshot]
   :cost/ticks 15000000}
  [db]
  (let [c-key (when (instance? datahike.db.DB db)
                [(get-in db [:config :store :id])
                 (get-in db [:config :branch] :main)
                 (d/commit-id db)
                 (:max-tx db)])
        compute
        (fn []
          (let [t0 (System/nanoTime)
                open-errs (fast-open-errors db)
                evals (recent-evals db 12)
                flow (flow-signal-from db open-errs)
                pressure (pressure-signal-from db evals open-errs)
                context (context-signal-from db evals)
                proof (proof-signal-from db)
                health (health-signal-from db open-errs)
                lvl (worst-level [(:level flow) (:level pressure) (:level context) (:level proof) (:level health)])
                ms (round1 (/ (double (- (System/nanoTime) t0)) 1000000.0))]
            (with-meta
              {:level lvl
               :branch (db-branch db)
               :tx (db-tx db)
               :ms ms
               :flow flow
               :pressure pressure
               :context context
               :proof proof
               :health health}
              {:seon/schema :seon.signal/snapshot})))]
    (if c-key
      (store/lru-get! signals-snap-cache 16 c-key compute)
      (compute))))

(defn- signal-pill [label lvl value pulse?]
  [:span {:class (str "sig-pill " (level-class lvl) (when pulse? " sig-pulse"))}
   [:span {:class (str "sig-dot " (level-class lvl))}]
   [:span {:class "sig-label"} label]
   [:span {:class "mono"} value]])

(defn render-signals-bar
  "Compact ambient operational signal strip (`:min-width 220`) for headers and narrow slots."
  {:malli/schema [:=> [:cat [:map [:seon.signal/snapshot :seon.signal/snapshot]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{:seon.signal/keys [snapshot]}]
  (let [{:keys [flow pressure context proof health]} snapshot]
    {:seon.ui/hiccup
     [:div {:class "sig-strip" :data-min-width 220}
      (signal-pill "Flow" (:level flow) (str (:running flow) "/" (:active flow)) (:pulse? flow))
      (signal-pill "Pressure" (:level pressure) (str (:bytes-pct pressure) "%") false)
      (signal-pill "Context" (:level context) (str (:prefix-reuse-pct context) "%") false)
      (signal-pill "Proof" (:level proof) (if (:merge-ready? proof) (str (:green proof) " ok") (str (:failing proof) " fail")) false)
      (signal-pill "Health" (:level health) (if (:ok? health) "ok" (str (:errors health) " err")) false)]}))

(defn- heat-bar [pct lvl]
  [:div {:class "heat-bar"}
   [:div {:class (str "heat-fill " (level-class lvl))
          :style {:width (str (clamp-pct pct) "%")}}]])

(defn- signal-cell [title lvl summary pct pulse?]
  [:div {:class (str "sig-cell " (level-class lvl))}
   [:div {:class "sig-cell-head"}
    [:span {:class (str "sig-dot " (level-class lvl) (when pulse? " sig-pulse"))}]
    [:strong title]
    [:span {:class "mono tiny muted"} (name lvl)]]
   (when (some? pct) (heat-bar pct lvl))
   [:div {:class "mono tiny muted"} summary]])

(defn render-signals-panel
  "Richer ambient operational signal panel (`:min-width 560`) with hairline heat bars for wide slots."
  {:malli/schema [:=> [:cat [:map [:seon.signal/snapshot :seon.signal/snapshot]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{:seon.signal/keys [snapshot]}]
  (let [{:keys [level branch flow pressure context proof health]} snapshot
        pressure-pct (max (double (:ticks-pct pressure)) (double (:bytes-pct pressure)))
        proof-pct (if (pos? (:total-tests proof))
                    (* 100.0 (/ (double (:green proof)) (double (:total-tests proof))))
                    100.0)
        health-pct (if (:ok? health) (min 100.0 (* 15.0 (double (:warnings health)))) 100.0)]
    {:seon.ui/hiccup
     [:section {:class (str "sig-panel " (level-class level)) :data-min-width 560}
      [:div {:class "sig-panel-head"}
       [:span {:class (str "sig-dot " (level-class level))}]
       [:strong "Operational Signals"]
       [:span {:class "mono tiny muted"} (str (name branch) " · " (name level))]]
      [:div {:class "sig-grid"}
       (signal-cell "Flow" (:level flow) (:summary flow) nil (:pulse? flow))
       (signal-cell "Pressure" (:level pressure) (:summary pressure) pressure-pct false)
       (signal-cell "Context" (:level context) (:summary context) (:prefix-reuse-pct context) false)
       (signal-cell "Proof" (:level proof) (:summary proof) proof-pct false)
       (signal-cell "Health" (:level health) (:summary health) health-pct false)]]}))

(defn extract-eval-touches
  "Thin alias for `seon.signals.scoreboard/extract-eval-touches`."
  {:malli/schema [:=> [:cat :any [:? :any] [:? :any]] :map]
   :cost/ticks 25000000}
  ([evals] (scoreboard/extract-eval-touches evals))
  ([db evals-or-agent] (scoreboard/extract-eval-touches db evals-or-agent))
  ([db evals-or-agent opts] (scoreboard/extract-eval-touches db evals-or-agent opts)))

(defn score-shown-blocks
  "Thin alias for `seon.signals.scoreboard/score-shown-blocks`."
  {:malli/schema [:=> [:cat [:vector :map] [:vector :map] [:maybe :map]] :seon.signal/scoreboard]
   :cost/ticks 15000000}
  [shown-blocks candidate-pool later-touches]
  (scoreboard/score-shown-blocks shown-blocks candidate-pool later-touches))

(defn context-scoreboard
  "Thin alias for `seon.signals.scoreboard/context-scoreboard`."
  {:malli/schema [:function [:=> [:cat :seon.host/db :qualified-keyword] :seon.signal/scoreboard]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :seon.signal/scoreboard]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword [:maybe :map]] :seon.signal/scoreboard]]
   :cost/ticks 60000000}
  ([db agent-id] (scoreboard/context-scoreboard db agent-id))
  ([main-db branch-db agent-id] (scoreboard/context-scoreboard main-db branch-db agent-id))
  ([main-db branch-db agent-id opts] (scoreboard/context-scoreboard main-db branch-db agent-id opts)))

(defn compare-as-of-hyperparams
  "Thin alias for `seon.signals.scoreboard/compare-as-of-hyperparams`."
  {:malli/schema [:function [:=> [:cat :seon.host/db :qualified-keyword :int] :map]
                  [:=> [:cat :seon.host/db :qualified-keyword :int [:maybe [:vector :map]]] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword :int [:maybe [:vector :map]]] :map]]
   :cost/ticks 80000000}
  ([db agent-id tx] (scoreboard/compare-as-of-hyperparams db agent-id tx))
  ([db agent-id tx profiles] (scoreboard/compare-as-of-hyperparams db agent-id tx profiles))
  ([main-db branch-db agent-id tx profiles] (scoreboard/compare-as-of-hyperparams main-db branch-db agent-id tx profiles)))
