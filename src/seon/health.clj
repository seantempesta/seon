(ns seon.health
"System Health — pure, fast AEVT queries over Datahike facts (never polled, never duplicated):
 1. Derived structural rules (`:error` blocks merge; `:warning` advises):
    - `:blocking-in-flow-proc`: detects `<!!`, `>!!`, `alts!!`, `Thread/sleep` reachable from Flow procs
      (unless explicitly tagged `^:seon.flow/edge`).
    - `:state-outside-flow`: detects top-level `atom`/`volatile!`/`promise`/`agent` defs not tagged `^:seon.flow/edge`.
    - `:retyped-attribute` & `:schema-breaks-stored-data`: rejects schema edits conflicting with installed
      Datahike attribute types or existing stored datoms.
    - `:used-before-defined`: detects same-namespace forward references without `(declare ...)`.
    - `:unschemed-attribute`: detects `:seon.*/*` Datahike attributes lacking a registered Malli schema.
 2. Efficiency & hygiene advisories live in the child namespace `seon.health.efficiency` (thin delegating
    wrappers re-exported here for a stable public API):
    - `:nested-db-scan` (`:static`): detects `d/datoms :aevt` or `d/q` inside loop bodies (`O(N * M)`).
    - `:oversized-namespace` (`:static`): flags non-test namespaces exceeding `8k` estimated tokens.
    - `:slow-test` & `:cost-regression` (`:live`): flags latest green tests `> 1000 ms` or jumping `> 2.5x`
      in ticks/bytes vs their prior green run.
    - `:low-cache-hit` & `:heavy-eval` (`:live`): flags KV-cache hit drops (`< 60%`) and high-allocation
      or budget-exhausted evals.
 3. Global failing tests (`:failing-test`) & recorded Flow/runtime faults & perf outliers (`open-faults`).
 4. Scope-routed views (`static-problems-for-scope` for `<walk-context>` `ns-tile`, `live-problems-for-scope`
    for ephemeral `<neighborhood-activity>`) so every specialist sees its own alerts with zero KV-cache churn."
(:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.health.efficiency :as eff]
            [seon.store :as store]
            [seon.health.static :as static]
            [seon.health.contracts :as contracts]
          [datahike.index.interface :as di]
          [datahike.reads :as reads]
          [seon.error :as serr]
          [seon.health.branch :as hbranch]
          [seon.ctx.diff :as diff]
          [seon.health.liveness :as liveness]))

(defn ns-in-scope?
  "True when namespace symbol `nsym` belongs to `scope` (exact match or descendant `scope.*`,
   with `'seon` matching all `seon.*` namespaces). Delegates to `seon.health.efficiency/ns-in-scope?`."
  {:malli/schema [:=> [:cat [:maybe :symbol] [:maybe :symbol]] [:maybe :boolean]], :cost/ticks 50000}
  [scope nsym]
  (eff/ns-in-scope? scope nsym))

(defn- infer-ns-from-subject [subj]
  (when (string? subj)
    (let [s (if (str/starts-with? subj ":") (subs subj 1) subj)
          head (first (str/split s #"/" 2))]
      (when (and head (str/starts-with? head "seon."))
        (symbol head)))))

(defn ^:seon.flow/edge blocking-in-flow-procs [& args] (apply static/blocking-in-flow-procs args))

(defn ^:seon.flow/edge state-outside-flow [& args] (apply static/state-outside-flow args))

(defn schema-violations [& args] (apply static/schema-violations args))

(defn used-before-defined [& args] (apply static/used-before-defined args))

(defn static-efficiency-warnings
  "Delegates to `seon.health.efficiency/static-efficiency-warnings`."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] [:vector :seon.health/problem]] [:=> [:cat [:maybe :seon.host/db] :any] [:vector :seon.health/problem]]], :cost/ticks 25000000}
  ([db] (eff/static-efficiency-warnings db))
  ([db scope] (eff/static-efficiency-warnings db scope)))

(defn test-efficiency-warnings
  "Delegates to `seon.health.efficiency/test-efficiency-warnings`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :seon.health/problem]], :cost/ticks 15000000}
  [db]
  (eff/test-efficiency-warnings db))

(defn eval-efficiency-warnings
  "Delegates to `seon.health.efficiency/eval-efficiency-warnings`."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] [:vector :seon.health/problem]] [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:vector :seon.health/problem]]], :cost/ticks 15000000}
  ([branch-db] (eff/eval-efficiency-warnings branch-db))
  ([branch-db agent-id] (eff/eval-efficiency-warnings branch-db agent-id)))

(defn open-faults
  "Returns all unresolved recorded Flow/runtime faults and live performance outliers from Datahike."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :seon.health/problem]], :cost/ticks 5000000}
  [db]
  (->> (serr/open-errors db)
  (mapv (fn [e]
          (let [data (:seon.error/data e)
                v (:seon.error/var e)
                ag (:seon.error/agent e)
                subj (or (:subject data) (when v (str v)) (when ag (str ag)) (str (:seon.error/source e :system)))
                nsym (or (:ns data) (when v (symbol (namespace v))) (infer-ns-from-subject subj))]
            (cond-> {:class (or (:seon.error/kind e) :proc-fault)
                     :severity (or (:seon.error/severity e) :error)
                     :kind :live
                     :subject subj
                     :branch (or (:seon.error/branch e) :main)
                     :message (or (:seon.error/message e) "")}
              nsym (assoc :ns nsym)
              ag (assoc :agent ag)))))))

(defn unschemed-attributes [& args] (apply static/unschemed-attributes args))

(defn missing-ns-forms [& args] (apply static/missing-ns-forms args))

(defonce ^:private ^:seon.flow/edge report-cache
(store/lru-cache 12))

(defn- attr-sig [db a]
  (if (contains? (:schema db) a)
    (let [cnt (volatile! 0)
          mtx (volatile! 0)]
      (doseq [dt (d/datoms db :aevt a)]
        (vswap! cnt inc)
        (let [t (long (:tx dt))]
          (when (> t @mtx) (vreset! mtx t))))
      [@cnt @mtx])
    [0 0]))

(defn broken-symbol-refs [& args] (apply contracts/broken-symbol-refs args))

(defn broken-doc-refs [& args] (apply contracts/broken-doc-refs args))

(defn ^:seon.flow/edge impure-generatable-fns [& args] (apply contracts/impure-generatable-fns args))

(defn- db-snapshot-key [db]
  (when (and db
             (not (instance? datahike.db.AsOfDB db))
             (not (instance? datahike.db.HistoricalDB db)))
    [(get-in db [:config :store :id])
     (get-in db [:config :branch] :main)
     (try (d/commit-id db) (catch Throwable _ nil))
     (:max-tx db)]))

(defonce ^:private ^:seon.flow/edge report-commit-cache (store/lru-cache 32))

(defonce ^:private ^:seon.flow/edge static-scope-cache (store/lru-cache 32))

(defn missing-contract-violations [& args] (apply contracts/missing-contract-violations args))

(defn test-digest-supersession "Two flat AEVT scans (no nested loops): `:var->green-tx` maps a test var to the highest\n             tx at which one of its `:seon.test` rows passed; `:eid->pass-tx` maps each test entity\n             (digest) to the tx of its `:seon.test/pass?` datom. Used to detect stale failing digests\n             that a later green run has superseded."
  {:malli/schema [:=> [:cat :seon.host/db] [:map [:var->green-tx :map] [:eid->pass-tx :map]]], :cost/ticks 800000, :cost/bytes 20000, :malli/examples [[[{}] {:var->green-tx {}, :eid->pass-tx {}}]]}
  [db] (:supersession (eff/test-snapshot db)))

(defn superseded-digest?
  "True when a failing test digest (entity `eid`, var `vsym`) has been superseded by a later
   passing run of the same var. Stale digests must never pin a branch red."
  {:malli/schema [:=> [:cat :symbol [:maybe :int] :map] :boolean]
   :cost/ticks 200
   :cost/bytes 1024
   :malli/examples [[['clojure.string/upper-case 1 {:var->green-tx {'clojure.string/upper-case 5} :eid->pass-tx {1 2}}] true]
                    [['clojure.string/upper-case 1 {:var->green-tx {} :eid->pass-tx {1 2}}] false]]}
  [vsym eid {:keys [var->green-tx eid->pass-tx]}]
  (let [green-tx (get var->green-tx vsym)
        fail-tx (get eid->pass-tx eid)]
    (boolean (and green-tx fail-tx (> green-tx fail-tx)))))

(defn failing-tests
  "Global view of failing tests across `:main` and all active (non-archived) branches.
   Stale failing digests superseded by a later passing run of the same var are skipped."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db [:maybe :map]] [:vector :seon.health/problem]]]}
  ([db] (failing-tests db nil))
  ([db test-snap]
   (if-not (contains? (:schema db) :seon.test.run/branch)
     []
     (let [ag-ids (java.util.HashMap. 64)
           ag-brs (java.util.HashMap. 64)
           ag-arch (java.util.HashSet. 32)
           _ (when (contains? (:schema db) :seon.agent/id)
               (doseq [dt (d/datoms db :aevt :seon.agent/id)] (.put ag-ids (:e dt) (:v dt)))
               (when (contains? (:schema db) :seon.agent/branch)
                 (doseq [dt (d/datoms db :aevt :seon.agent/branch)] (.put ag-brs (:e dt) (:v dt))))
               (when (contains? (:schema db) :seon.agent/archived?)
                 (doseq [dt (d/datoms db :aevt :seon.agent/archived?)]
                   (when (:v dt) (.add ag-arch (:e dt))))))
           agents-by-branch (let [m (java.util.HashMap. 32)
                                  it (.iterator (.entrySet ag-ids))]
                              (loop []
                                (when (.hasNext it)
                                  (let [entry ^java.util.Map$Entry (.next it)
                                        e (.getKey entry)
                                        br (.get ag-brs e)]
                                    (when br
                                      (let [arr (or (.get m br) (let [a (java.util.ArrayList. 4)] (.put m br a) a))]
                                        (.add ^java.util.ArrayList arr
                                              {:id (.getValue entry) :archived? (.contains ag-arch e)}))))
                                  (recur)))
                              m)
           archived-branch? (fn [br]
                              (and (not= br :main)
                                   (let [ags (.get agents-by-branch br)]
                                     (and ags (pos? (.size ^java.util.ArrayList ags))
                                          (every? :archived? ags)))))
           passing-runs (java.util.HashSet. 32)
           _ (when (contains? (:schema db) :seon.test.run/pass?)
               (doseq [dt (d/datoms db :aevt :seon.test.run/pass?)]
                 (when (:v dt) (.add passing-runs (:e dt)))))
           fail-run-eids (vec (keep (fn [dt]
                                      (let [e (:e dt)
                                            br (:v dt)]
                                        (when (and (not (.contains passing-runs e))
                                                   (not (archived-branch? br)))
                                          e)))
                                    (d/datoms db :aevt :seon.test.run/branch)))]
       (if (empty? fail-run-eids)
         []
         (let [supersession (if test-snap (:supersession test-snap) (test-digest-supersession db))]
           (->> fail-run-eids
                (map #(d/pull db '[:seon.test.run/branch :seon.test.run/pass? :seon.test.run/failed-digests] %))
                (remove :seon.test.run/pass?)
                (mapcat
                 (fn [run]
                   (let [br (:seon.test.run/branch run)
                         ags (->> (or (.get agents-by-branch br) [])
                                  (remove :archived?)
                                  (mapv :id))]
                     (for [dg (sort (:seon.test.run/failed-digests run))
                           :let [v (d/pull db '[:db/id :seon.test/var :seon.test/kind :seon.test/ms :seon.test/report :seon.test/pass?]
                                   [:seon.test/digest dg])]
                           :when (:seon.test/var v)
                           :let [vsym (:seon.test/var v)]
                           :when (not (superseded-digest? vsym (:db/id v) supersession))]
                       (cond-> {:class :failing-test :severity :error :kind :live
                                :subject (str vsym) :var vsym :branch br
                                :message (str "[" (name (or (:seon.test/kind v) :deftest)) " on " br
                                              (when (seq ags) (str ", agents " (pr-str ags)))
                                              "] " (:seon.test/report v))}
                         (namespace vsym) (assoc :ns (symbol (namespace vsym))))))))
                vec)))))))

(defn silent-catch-warnings
  "Delegates to `seon.health.static/silent-catch-warnings`."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db [:maybe :symbol]] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db [:maybe :symbol] [:maybe :map]] [:vector :seon.health/problem]]]}
  ([db] (static/silent-catch-warnings db))
  ([db scope] (static/silent-catch-warnings db scope))
  ([db scope var-snap] (static/silent-catch-warnings db scope var-snap)))

(defn static-problems-for-scope
  "Returns deterministic code-derived health problems owned by `scope` (exact ns or descendant `scope.*`).
   Runs ONLY scoped static checks (never scans global tests or live faults) and caches per DB snapshot + scope."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :symbol]] [:vector :seon.health/problem]]}
  [db scope]
  (if-not (and db scope (not= scope 'seon))
    []
    (let [sk (when-let [dkey (db-snapshot-key db)] (conj dkey scope))
          compute
          (fn []
            (let [scope-nss (if (contains? (:schema db) :seon.code.ns/name)
                              (->> (d/datoms db :aevt :seon.code.ns/name)
                                   (map :v)
                                   (filter #(and (ns-in-scope? scope %)
                                                 (not (str/ends-with? (name %) "-test"))))
                                   vec)
                              [])]
              (if (empty? scope-nss)
                []
                (let [vsnap (static/var-snapshot db {:schemas? true :catches? true})
                      vmap (:vmap vsnap)
                      raw (concat (blocking-in-flow-procs db vmap)
                                  (state-outside-flow db vmap)
                                  (used-before-defined db scope-nss)
                                  (missing-ns-forms db scope-nss)
                                  (silent-catch-warnings db scope vsnap)
                                  (contracts/broken-symbol-refs db vsnap)
                                  (contracts/broken-doc-refs db scope vsnap)
                                  (contracts/impure-generatable-fns db scope vsnap)
                                  (static-efficiency-warnings db scope))]
                  (filterv (fn [p]
                             (and (= :static (:kind p))
                                  (ns-in-scope? scope (:ns p))))
                           raw)))))]
      (if sk
        (store/lru-get! static-scope-cache 32 sk compute)
        (compute)))))

(defn- report-cache-key-with-sigs
  ([db] (report-cache-key-with-sigs db nil nil))
  ([db csig tsnap]
   (when (and db
              (not (instance? datahike.db.AsOfDB db))
              (not (instance? datahike.db.HistoricalDB db)))
     (when-let [code-sig (or csig (diff/code-db-sig db))]
       (let [ts (or tsnap (eff/test-snapshot db))]
         [(get-in db [:config :store :id])
          (get-in db [:config :branch] :main)
          (count (:schema db))
          code-sig
          (:var-sig ts)
          (:pass-sig ts)
          (attr-sig db :seon.test.run/pass?)
          (attr-sig db :seon.test.run/failed-digests)
          (attr-sig db :seon.agent/archived?)
          (attr-sig db :seon.error/id)
          (attr-sig db :seon.error/status)])))))

(defn- cached-test-state [db]
  (let [tsnap (eff/test-snapshot db)]
    {:supersession (:supersession tsnap)
     :var-sig (:var-sig tsnap)
     :pass-sig (:pass-sig tsnap)
     :test-eff (eff/test-efficiency-warnings db tsnap)}))

(defn- fast-scoped-test-alerts [db scope]
  (when (and db scope (get-in (:schema db) [:seon.test/var :db/index]))
    (let [sc (name scope) p1 (str sc ".") br (get-in db [:config :branch] :main)
          take-ns (fn [st pred]
                    (into [] (comp (take-while #(and (= :seon.test/var (:a %)) (symbol? (:v %)) (pred (namespace (:v %)))))
                                   (map (juxt :v :e :tx)))
                          (d/seek-datoms db :avet :seon.test/var st)))
          by-v (reduce (fn [m [v e tx]] (update m v (fnil conj []) [e (long (or tx 0))])) {}
                       (into (take-ns (symbol sc "") #(= % sc)) (take-ns (symbol p1 "") #(and % (str/starts-with? % p1)))))
          ea (fn [e a dflt] (if-let [dt (first (d/datoms db :eavt e a))] (:v dt) dflt))
          rd (fn [[e tx]]
               {:tx tx :pass? (ea e :seon.test/pass? false)
                :ms (double (or (ea e :seon.test/ms 0.0) 0.0))
                :ticks (long (or (ea e :seon.test/ticks 0) 0))
                :bytes (long (or (ea e :seon.test/bytes 0) 0))})]
      (vec (mapcat (fn [[v xs]]
                     (let [sxs (take-last 2 (sort-by second xs))
                           cur (rd (last sxs))
                           nsym (when (namespace v) (symbol (namespace v)))]
                       (when (and (:pass? cur) (or (> (:ms cur) 200.0) (> (:ticks cur) 5000000) (> (:bytes cur) 33554432)))
                         (concat
                          (when (> (:ms cur) 1000.0)
                            [{:class :slow-test :severity :warning :kind :live :ns nsym :var v :subject (str v) :branch br
                              :message (str "Slow test " v ": " (Math/round (:ms cur)) " ms (> 1000 ms).")}])
                          (when (= 2 (count sxs))
                            (let [p (rd (first sxs)) tr (when (pos? (:ticks p)) (/ (double (:ticks cur)) (:ticks p)))
                                  br' (when (pos? (:bytes p)) (/ (double (:bytes cur)) (:bytes p)))
                                  mr (when (> (:ms p) 20.0) (/ (double (:ms cur)) (:ms p)))]
                              (when (or (and tr (> tr 2.5) (> (- (:ticks cur) (:ticks p)) 5000000))
                                        (and br' (> br' 2.5) (> (- (:bytes cur) (:bytes p)) 33554432))
                                        (and mr (> mr 2.5) (> (- (:ms cur) (:ms p)) 200.0)))
                                [{:class :cost-regression :severity :warning :kind :live :ns nsym :var v :subject (str v) :branch br
                                  :message (str "Cost regression in " v " (" (Math/round (:ms p)) " -> " (Math/round (:ms cur)) " ms).")}])))))))
                   by-v)))))

(defn fast-scoped-alerts
  "Fast O(log N + K) scoped alerts for wire tails (zero full-codebase scans!)."
  {:malli/schema [:=> [:cat :any :any :any :any :any [:maybe :map]] [:vector :seon.health/problem]], :cost/ticks 15000000}
  [main-db branch-db scope aid evals {:keys [signals branch-edited]}]
  (if-not (and main-db scope)
    []
    (let [h (:health signals) p (:proof signals)
          sig-a (cond-> []
                  (or (false? (:ok? h)) (pos? (long (or (:errors h) 0)))) (conj {:class :system-health :severity :error :subject (str scope) :message (str (:summary h))})
                  (= :hot (:level p)) (conj {:class :failing-test :severity :error :subject (str scope) :message (str (:summary p))}))
          epull '[:seon.error/id :seon.error/kind :seon.error/severity :seon.error/status :seon.error/agent :seon.error/var :seon.error/message :seon.error/data]
          seen (volatile! #{})
          err-a (vec (for [db (distinct (remove nil? [branch-db main-db])) :let [dh (:schema db)] :when (contains? dh :seon.error/status)
                           eid (distinct (concat (when (and aid (contains? dh :seon.error/agent)) (map :e (d/datoms db :avet :seon.error/agent aid)))
                                                 (map :e (d/datoms db :avet :seon.error/status :open))))
                           :let [e (d/pull db epull eid) k (or (:seon.error/id e) eid) v (:seon.error/var e)
                                 ens (or (when (and (symbol? v) (namespace v)) (symbol (namespace v))) (get-in e [:seon.error/data :ns]))]
                           :when (and (= :open (:seon.error/status e)) (not (@seen k)) (or (= aid (:seon.error/agent e)) (and ens (ns-in-scope? scope ens))))]
                       (do (vswap! seen conj k)
                           {:class (or (:seon.error/kind e) :error) :severity (or (:seon.error/severity e) :error)
                            :subject (str (or (get-in e [:seon.error/data :subject]) v (:seon.error/agent e) scope))
                            :message (str (:seon.error/message e))})))
          edit-a (when (and branch-db (seq branch-edited))
                   (let [nss (if (= scope 'seon)
                               (distinct (keep #(let [p (first (str/split (str %) #"[#/]" 2))]
                                                  (when-not (str/blank? p) (symbol p)))
                                               branch-edited))
                               [scope])
                         p-key (juxt :class :subject)
                         base-keys (if (and main-db (not= main-db branch-db))
                                     (into #{} (map p-key) (mapcat #(eff/static-efficiency-warnings main-db %) nss))
                                     #{})]
                     (into [] (comp (mapcat #(eff/static-efficiency-warnings branch-db %))
                                    (filter (fn [x] (and (or (= :error (:severity x)) (:fix x))
                                                         (not (contains? base-keys (p-key x)))))))
                           nss)))]
      (vec (take 8 (distinct (concat err-a edit-a (fast-scoped-test-alerts main-db scope) (eff/eval-efficiency-warnings (or branch-db main-db) aid evals {:main-db main-db :branch-edited branch-edited}) sig-a)))))))

(defonce ^:private ^:seon.flow/edge branch-base-health-cache (store/lru-cache 8))

(defn caused-by-branch
  "Returns only the structural/schema health errors introduced by `theirs-db` relative to `ours-db`
   using O(|touched-nss| + |retracted| + |added-edges|) indexed reads."
  {:malli/schema [:function [:=> [:cat :any :any :map] [:vector :seon.health/problem]] [:=> [:cat :any :any :map [:maybe [:or [:sequential :symbol] [:set :symbol]]]] [:vector :seon.health/problem]]]}
  ([ours-db theirs-db changed-schema-forms]
   (caused-by-branch ours-db theirs-db changed-schema-forms nil))
  ([ours-db theirs-db changed-schema-forms touched-nss]
   (hbranch/caused-by-branch-impl ours-db theirs-db changed-schema-forms touched-nss
     {:cache branch-base-health-cache
      :schema-violations-fn schema-violations
      :blocking-fn blocking-in-flow-procs
      :ubd-fn used-before-defined
      :mns-fn missing-ns-forms})))

(defonce ^:private ^:seon.flow/edge liveness-cache (atom nil))

(defn- ^:seon.flow/edge cached-liveness-check []
  (let [pns (find-ns 'seon.reconciler.policy)
        sig (when pns
              (into [liveness/check-liveness]
                    (map #(when (bound? %) (var-get %)))
                    (vals (ns-interns pns))))
        cur @liveness-cache]
    (if (and sig (= sig (:sig cur)))
      (:res cur)
      (let [res (liveness/check-liveness)]
        (when sig (reset! liveness-cache {:sig sig :res res}))
        res))))

(defn liveness-violations
  "Runs finite-state liveness verification (`seon.health.liveness/check-liveness`, cached by `seon.reconciler.policy` var identities) on live DBs containing `seon.reconciler.policy/decide-agent-step`, surfacing any livelock, orphan-paused-parent, wedged inflight, crashed auto-merge, or stranded ready bug as `:error`."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :seon.health/problem]] :cost/ticks 60000000}
  [db]
  (if-not (and db
               (not (instance? datahike.db.AsOfDB db))
               (not (instance? datahike.db.HistoricalDB db))
               (contains? (:schema db) :seon.code.form/key)
               (first (d/datoms db :avet :seon.code.form/key "seon.reconciler.policy/decide-agent-step")))
    []
    (let [res (cached-liveness-check)]
      (if (:ok? res)
        []
        (let [br (get-in db [:config :branch] :main)
              mk (fn [cls subj msg]
                   {:class cls :severity :error :kind :static :ns 'seon.reconciler.policy
                    :subject subj :branch br :message msg})]
          (cond-> []
            (:truncated? res)
            (conj (mk :reconciler-liveness-truncated "seon.reconciler.policy/decide-agent-step"
                      (str "Liveness BFS truncated after " (:states-explored res) " states.")))
            (seq (:livelocks res))
            (conj (mk :reconciler-livelock "seon.reconciler.policy/decide-agent-step"
                      (str "Reconciler policy has " (count (:livelocks res)) " livelock SCC(s).")))
            (seq (:orphan-pauses res))
            (conj (mk :reconciler-orphan-pause "seon.reconciler.policy/decide-agent-step"
                      (str "Reconciler policy has " (count (:orphan-pauses res)) " orphan paused-parent terminal state(s).")))
            (seq (:wedged-inflight res))
            (conj (mk :reconciler-wedged-inflight "seon.reconciler.policy/decide-agent-step"
                      (str "Reconciler policy has " (count (:wedged-inflight res)) " wedged inflight terminal state(s).")))
            (seq (:crashed-auto-merges res))
            (conj (mk :reconciler-crashed-auto-merge "seon.reconciler.policy/decide-agent-step"
                      (str "Reconciler policy allows " (count (:crashed-auto-merges res)) " crashed auto-merge state(s).")))
            (seq (:stranded-ready res))
            (conj (mk :reconciler-stranded-ready "seon.reconciler.policy/decide-agent-step"
                      (str "Reconciler policy has " (count (:stranded-ready res)) " stranded ready terminal state(s).")))))))))

(defn- compute-report-data
  "Computes single-pass health report payload with shared snapshots."
  [db]
  (let [vsnap (static/var-snapshot db {:schemas? true :catches? true})
        vmap (:vmap vsnap)
        fsnap (static/form-meta-snapshot db)
        tstate (cached-test-state db)
        probs (vec (concat (blocking-in-flow-procs db vmap)
                           (state-outside-flow db vmap)
                           (used-before-defined db nil fsnap)
                           (missing-ns-forms db nil fsnap)
                           (unschemed-attributes db)
                           (silent-catch-warnings db nil vsnap)
                           (contracts/broken-symbol-refs db vsnap)
                           (contracts/broken-doc-refs db nil vsnap)
                           (contracts/impure-generatable-fns db nil vsnap)
                           (contracts/invalid-contract-violations db vsnap)
                           (eff/static-efficiency-warnings db nil fsnap)
                           (liveness-violations db)
                           (failing-tests db tstate)
                           (:test-eff tstate)
                           (open-faults db)))
        counts (frequencies (map :class probs))
        errors? (boolean (some #(= :error (:severity %)) probs))]
    {:report (with-meta
               {:ok? (not errors?)
                :branch (get-in db [:config :branch] :main)
                :counts counts
                :problems probs}
               {:seon/schema :seon.health/report})
     :code-sig (diff/code-db-sig db)
     :test-snap tstate}))

(defn report
  "Computes the complete `:seon.health/report` map from `db` (pure read, tagged with `:seon/schema`).
   `:ok?` is true whenever zero `:error`-severity problems exist. Shares single-pass snapshots across
   all sub-checks and caches per-form/per-ns static checks so cold & warm allocations stay low."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.health/report]}
  [db]
  (let [sk (db-snapshot-key db)
        compute-full
        (fn []
          (let [pre-ck (when (pos? (count @report-cache)) (report-cache-key-with-sigs db))
                raw-hit (when pre-ck (get @report-cache pre-ck ::miss))
                hit-entry (if (and (map? raw-hit) (contains? raw-hit :seon.store/lru-val))
                            (:seon.store/lru-val raw-hit)
                            raw-hit)
                [rep rs]
                (if (and pre-ck (not (identical? ::miss hit-entry)))
                  (store/lru-get! report-cache 12 pre-ck (constantly hit-entry))
                  (let [[data rset] (if di/*reads* (reads/with-reads #(compute-report-data db)) [(compute-report-data db) nil])
                        rep (:report data)
                        ck (or pre-ck
                               (report-cache-key-with-sigs db (:code-sig data) (:test-snap data)))
                        entry [rep rset]]
                    (if ck
                      (store/lru-get! report-cache 12 ck (constantly entry))
                      entry)))]
            {:report rep :reads rs}))
        entry (if sk
                (store/lru-get! report-commit-cache 32 sk compute-full)
                (compute-full))]
    (when-let [outer di/*reads*]
      (when (seq (:reads entry))
        (swap! outer into (:reads entry))))
    (:report entry)))