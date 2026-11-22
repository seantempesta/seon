(ns seon.signals
  "Five ambient operational signals (Flow, Pressure, Context, Proof, Health) derived
   via fast indexed Datahike reads (< 5 ms), plus 2-level pure `ns-map -> ns-map`
   Hiccup renderers discovered automatically via `:seon.code.var/input` + `:seon.ui/hiccup`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.error :as serr]
            [seon.store :as store]
            [seon.store.query :as sq]))

(def ^:private level-rank {:calm 0 :warm 1 :hot 2})

(def ^:private commit-size-cache (store/lru-cache 32))
(def ^:private ns-size-cache (store/lru-cache 16))
(def ^:private form-size-cache (store/lru-cache 2048))

(defn- round1 [x]
  (if (number? x)
    (let [v (double x)]
      (if (and (== v v) (not (Double/isInfinite v)))
        (/ (double (Math/round (* v 10.0))) 10.0)
        0.0))
    0.0))

(defn- clamp-pct [x]
  (max 0.0 (min 100.0 (round1 x))))

(defn- worst-level [levels]
  (reduce (fn [a b] (if (> (get level-rank b 0) (get level-rank a 0)) b a)) :calm levels))

(defn- level-class [lvl]
  (case lvl :hot "sig-hot" :warm "sig-warm" "sig-calm"))

(defn- recent-evals [db limit]
  (if-not (and db (contains? (:schema db) :seon.agent.eval/id))
    []
    (let [ds (sq/slice-datoms db [nil :seon.agent.eval/id nil] {:limit limit :reverse? true})]
      (mapv (fn [dt]
              (d/pull db
                      '[:seon.agent.eval/agent :seon.agent.eval/turn :seon.agent.eval/ms
                        :seon.agent.eval/ticks :seon.agent.eval/bytes :seon.agent.eval/error
                        :seon.agent.eval/prompt-tokens :seon.agent.eval/cache-hit-tokens
                        :seon.agent.eval/cache-miss-tokens :seon.agent.eval/prefix-hash]
                      (:e dt)))
            ds))))

(defn- archived-branch-set [db]
  (let [dh (:schema db)]
    (if-not (and (dh :seon.agent/id) (dh :seon.agent/branch))
      #{}
      (let [arch-eids (if (dh :seon.agent/archived?)
                        (into #{} (map :e) (d/datoms db :avet :seon.agent/archived? true))
                        #{})
            by-br (reduce (fn [m dt]
                            (update m (:v dt) (fnil conj []) (contains? arch-eids (:e dt))))
                          {} (d/datoms db :aevt :seon.agent/branch))]
        (into #{} (keep (fn [[br flags]]
                          (when (and (not= br :main) (seq flags) (every? true? flags)) br)))
              by-br)))))

(defn- active-failing-test-count [db br]
  (let [dh (:schema db)]
    (if-not (dh :seon.test.run/branch)
      0
      (let [cur (d/pull db '[:seon.test.run/pass? :seon.test.run/failed-digests]
                        [:seon.test.run/branch br])]
        (cond
          (false? (:seon.test.run/pass? cur))
          (max 1 (count (:seon.test.run/failed-digests cur)))

          (dh :seon.test.run/pass?)
          (let [arch-brs (archived-branch-set db)]
            (reduce (fn [cnt dt]
                      (if (false? (:v dt))
                        (let [r (d/pull db '[:seon.test.run/branch :seon.test.run/failed-digests] (:e dt))
                              rbr (:seon.test.run/branch r)]
                          (if (and rbr (not (contains? arch-brs rbr)))
                            (+ cnt (max 1 (count (:seon.test.run/failed-digests r))))
                            cnt))
                        cnt))
                    0 (d/datoms db :aevt :seon.test.run/pass?)))

          :else 0)))))

(defn- flow-signal-from [db open-errs]
  (let [dh (:schema db)
        err-agents (into #{} (keep :seon.error/agent) open-errs)
        arch-eids (if (dh :seon.agent/archived?)
                    (into #{} (map :e) (d/datoms db :avet :seon.agent/archived? true))
                    #{})
        active-eids (if (dh :seon.agent/id)
                      (into [] (comp (map :e) (remove arch-eids)) (d/datoms db :aevt :seon.agent/id))
                      [])
        ags (mapv #(d/pull db '[:seon.agent/id :seon.agent/role :seon.agent/waiting?
                                :seon.agent/paused? :seon.agent/ready? :seon.agent/awaiting] %)
                  active-eids)
        counts
        (reduce (fn [acc a]
                  (let [aid (:seon.agent/id a)
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
                ags)
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
  (flow-signal-from db (serr/open-errors db)))

(defn- parse-ms-from-msg [s]
  (when (string? s)
    (when-let [[_ num-s] (re-find #"(\d+(?:\.\d+)?)\s*ms" s)]
      (Double/parseDouble num-s))))

(defn- pressure-signal-from [db evals open-errs]
  (let [cfg (when (contains? (:schema db) :seon.config/key)
              (d/pull db '[:seon.config/eval-ticks :seon.config/eval-bytes]
                      [:seon.config/key :seon.config/root]))
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
  (pressure-signal-from db (recent-evals db 16) (serr/open-errors db)))

(defn- bench-cache-fallback [db]
  (if-not (contains? (:schema db) :seon.bench.run/id)
    [0 0]
    (let [latest (->> (d/datoms db :aevt :seon.bench.run/id)
                      (map #(d/pull db '[:seon.bench.run/started-ms :seon.bench.run/proxy-stats] (:e %)))
                      (filter #(pos? (long (or (get-in % [:seon.bench.run/proxy-stats :prompt-tokens]) 0))))
                      (sort-by #(long (or (:seon.bench.run/started-ms %) 0)))
                      last)
          ps (:seon.bench.run/proxy-stats latest)]
      [(long (or (:prompt-tokens ps) 0)) (long (or (:cache-hit-tokens ps) 0))])))

(defn- count-prefix-busts [evals]
  (let [by-ag (group-by :seon.agent.eval/agent evals)]
    (reduce (fn [n [_ es]]
              (let [phs (->> es
                             (filter :seon.agent.eval/prefix-hash)
                             (sort-by #(long (or (:seon.agent.eval/turn %) 0)))
                             (map :seon.agent.eval/prefix-hash))]
                (+ n (count (filter (fn [[a b]] (not= a b)) (partition 2 1 phs))))))
            0 by-ag)))

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
  (context-signal-from db (recent-evals db 24)))

(defn read-proof-signal
  "Reads ambient cached green / failing / unproved test counts and branch merge readiness."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.signal/proof]
   :cost/ticks 5000000}
  [db]
  (let [dh (:schema db)
        br (get-in db [:config :branch] :main)
        total-vars (if (dh :seon.code.var/test?) (sq/count-range db [nil :seon.code.var/test? true]) 0)
        run (when (dh :seon.test.run/branch)
              (let [r (d/pull db '[:seon.test.run/pass? :seon.test.run/ran :seon.test.run/cached
                                   :seon.test.run/failed-digests]
                              [:seon.test.run/branch br])]
                (if (contains? r :seon.test.run/pass?)
                  r
                  (when (not= br :main)
                    (d/pull db '[:seon.test.run/pass? :seon.test.run/ran :seon.test.run/cached
                                 :seon.test.run/failed-digests]
                            [:seon.test.run/branch :main])))))
        failing (long (active-failing-test-count db br))
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

(defn- analyze-form-size [^String s]
  (let [ch (.length s)
        tr (str/triml s)
        defn? (or (str/starts-with? tr "(defn ") (str/starts-with? tr "(defn- "))
        lines (if defn?
                (loop [i 0 n 1]
                  (let [idx (.indexOf s "\n" i)]
                    (if (neg? idx) n (recur (inc idx) (inc n)))))
                0)]
    {:chars ch :large-fn? (boolean (and defn? (or (> lines 120) (> ch 7200))))}))

(defn- compute-ns-size-stats [db]
  (let [dh (:schema db)]
    (if-not (and (dh :seon.code.form/ns) (dh :seon.code.form/src))
      {:oversized-ns 0 :large-fns 0}
      (let [br (get-in db [:config :branch] :main)
            sid (get-in db [:config :store :id])
            c-key (when (instance? datahike.db.DB db) [sid br (d/commit-id db) (:max-tx db)])
            compute
            (fn []
              (let [cnt (volatile! 0)
                    mtx (volatile! 0)
                    e->hash (java.util.HashMap. 1400)]
                (when (dh :seon.code.form/hash)
                  (doseq [dt (d/datoms db :aevt :seon.code.form/hash)]
                    (vswap! cnt inc)
                    (let [tx (long (:tx dt))] (when (> tx @mtx) (vreset! mtx tx)))
                    (.put e->hash (:e dt) (:v dt))))
                (store/lru-get!
                 ns-size-cache 16 [sid br @cnt @mtx]
                 (fn []
                   (let [ns-chars (java.util.HashMap. 160)
                         large-n (volatile! 0)]
                     (doseq [dt (d/datoms db :aevt :seon.code.form/ns)
                             :let [nsym (:v dt) e (:e dt)]
                             :when (and nsym (not (str/ends-with? (name nsym) "-test")))]
                       (let [h (.get e->hash e)
                             {:keys [chars large-fn?]}
                             (if h
                               (store/lru-get!
                                form-size-cache 2048 h
                                #(analyze-form-size (str (:v (first (d/datoms db :eavt e :seon.code.form/src))))))
                               (analyze-form-size (str (:v (first (d/datoms db :eavt e :seon.code.form/src))))))]
                         (.put ns-chars nsym (+ (long (or (.get ns-chars nsym) 0)) (long chars)))
                         (when large-fn? (vswap! large-n inc))))
                     (let [over (count (filter #(> (quot (long (val %)) 4) 8000) ns-chars))]
                       {:oversized-ns (long over) :large-fns (long @large-n)}))))))]
        (if c-key
          (store/lru-get! commit-size-cache 32 c-key compute)
          (compute))))))

(defn- health-signal-from [db open-errs]
  (let [br (get-in db [:config :branch] :main)
        hard-errs (count (filter #(contains? #{:error :fatal} (:seon.error/severity %)) open-errs))
        warn-errs (count (filter #(= :warning (:seon.error/severity %)) open-errs))
        failing (long (active-failing-test-count db br))
        {:keys [oversized-ns large-fns]} (compute-ns-size-stats db)
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
  (health-signal-from db (serr/open-errors db)))

(defn all-signals
  "Computes all 5 ambient operational signals (flow, pressure, context, proof, health) cleanly in < 5 ms."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.signal/snapshot]
   :cost/ticks 15000000}
  [db]
  (let [t0 (System/nanoTime)
        open-errs (serr/open-errors db)
        evals (recent-evals db 24)
        flow (flow-signal-from db open-errs)
        pressure (pressure-signal-from db (vec (take 16 evals)) open-errs)
        context (context-signal-from db evals)
        proof (read-proof-signal db)
        health (health-signal-from db open-errs)
        lvl (worst-level [(:level flow) (:level pressure) (:level context) (:level proof) (:level health)])
        ms (round1 (/ (double (- (System/nanoTime) t0)) 1000000.0))]
    (with-meta
      {:level lvl
       :branch (get-in db [:config :branch] :main)
       :tx (long (or (:max-tx db) 0))
       :ms ms
       :flow flow
       :pressure pressure
       :context context
       :proof proof
       :health health}
      {:seon/schema :seon.signal/snapshot})))

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
  (let [w (clamp-pct pct)]
    [:div {:class "heat-bar"}
     [:div {:class (str "heat-fill " (level-class lvl))
            :style {:width (str w "%")}}]]))

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
