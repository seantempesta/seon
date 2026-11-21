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
          [datahike.reads :as reads]))

(defn ns-in-scope?
  "True when namespace symbol `nsym` belongs to `scope` (exact match or descendant `scope.*`,
   with `'seon` matching all `seon.*` namespaces). Delegates to `seon.health.efficiency/ns-in-scope?`."
  [scope nsym]
  (eff/ns-in-scope? scope nsym))

(defn- infer-ns-from-subject [subj]
  (when (string? subj)
    (let [s (if (str/starts-with? subj ":") (subs subj 1) subj)
          head (first (str/split s #"/" 2))]
      (when (and head (str/starts-with? head "seon."))
        (symbol head)))))

(defn owner-specialist-id
  "Maps a namespace symbol (e.g. `'seon.render.ui`) to its owning specialist agent ID (`:ns/seon.render`)."
  [nsym]
  (when nsym
    (let [parts (str/split (name nsym) #"\.")
          top2 (if (>= (count parts) 2)
                 (str (first parts) "." (second parts))
                 (first parts))]
      (keyword "ns" top2))))

(defn- src-var-map [& args] (apply static/src-var-map args))

(defn ^:seon.flow/edge blocking-in-flow-procs [& args] (apply static/blocking-in-flow-procs args))

(defn ^:seon.flow/edge state-outside-flow [& args] (apply static/state-outside-flow args))

(defn schema-violations [& args] (apply static/schema-violations args))

(defn used-before-defined [& args] (apply static/used-before-defined args))

(defn static-efficiency-warnings
  "Delegates to `seon.health.efficiency/static-efficiency-warnings`."
  ([db] (eff/static-efficiency-warnings db))
  ([db scope] (eff/static-efficiency-warnings db scope)))

(defn test-efficiency-warnings
  "Delegates to `seon.health.efficiency/test-efficiency-warnings`."
  [db]
  (eff/test-efficiency-warnings db))

(defn eval-efficiency-warnings
  "Delegates to `seon.health.efficiency/eval-efficiency-warnings`."
  ([branch-db] (eff/eval-efficiency-warnings branch-db))
  ([branch-db agent-id] (eff/eval-efficiency-warnings branch-db agent-id)))

(defn open-faults
  "Returns all unresolved recorded Flow/runtime faults and live performance outliers from Datahike."
  [db]
  (if-not (contains? (:schema db) :seon.health.fault/id)
    []
    (->> (d/datoms db :aevt :seon.health.fault/id)
         (map #(d/pull db '[:seon.health.fault/id :seon.health.fault/class :seon.health.fault/severity
                            :seon.health.fault/proc :seon.health.fault/ns :seon.health.fault/subject
                            :seon.health.fault/message :seon.health.fault/branch
                            :seon.health.fault/resolved?] (:e %)))
         (remove :seon.health.fault/resolved?)
         (mapv (fn [f]
                 (let [subj (or (:seon.health.fault/subject f) (str (:seon.health.fault/proc f :system)))
                       nsym (or (:seon.health.fault/ns f) (infer-ns-from-subject subj))]
                   (cond-> {:class (or (:seon.health.fault/class f) :proc-fault)
                            :severity (or (:seon.health.fault/severity f) :error)
                            :kind :live
                            :subject subj
                            :branch (or (:seon.health.fault/branch f) :main)
                            :message (:seon.health.fault/message f "")}
                     nsym (assoc :ns nsym))))))))

(defn unschemed-attributes [& args] (apply static/unschemed-attributes args))

(defn missing-ns-forms [& args] (apply static/missing-ns-forms args))

(defonce ^:private ^:seon.flow/edge report-cache
(store/lru-cache 12))

(def ^:private report-tracked-attrs
  [:seon.code.var/name :seon.code.form/src :seon.test/digest :seon.health.fault/id :seon.schema/key])

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

(defn- report-cache-key [db]
  (when (and db
             (not (instance? datahike.db.AsOfDB db))
             (not (instance? datahike.db.HistoricalDB db)))
    [(get-in db [:config :store :id])
     (get-in db [:config :branch] :main)
     (count (:schema db))
     (attr-sig db :seon.code.form/hash)
     (attr-sig db :seon.code.form/order)
     (attr-sig db :seon.code.var/name)
     (attr-sig db :seon.schema/key)
     (attr-sig db :seon.test/digest)
     (attr-sig db :seon.test.run/pass?)
     (attr-sig db :seon.test.run/failed-digests)
     (attr-sig db :seon.agent/archived?)
     (attr-sig db :seon.health.fault/id)
     (attr-sig db :seon.health.fault/resolved?)]))

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

(def ^:private report-commit-cache (store/lru-cache 32))

(def ^:private static-scope-cache (store/lru-cache 32))

(defn static-problems-for-scope
  "Returns deterministic code-derived health problems owned by `scope` (exact ns or descendant `scope.*`).
   Runs ONLY scoped static checks (never scans global tests or live faults) and caches per DB snapshot + scope."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :symbol]] [:vector :map]]}
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
                (let [vmap (src-var-map db)
                      raw (concat (blocking-in-flow-procs db vmap)
                                  (state-outside-flow db vmap)
                                  (used-before-defined db scope-nss)
                                  (missing-ns-forms db scope-nss)
                                  (broken-symbol-refs db)
                                  (broken-doc-refs db scope)
                                  (impure-generatable-fns db scope)
                                  (static-efficiency-warnings db scope))]
                  (filterv (fn [p]
                             (and (= :static (:kind p))
                                  (ns-in-scope? scope (:ns p))))
                           raw)))))]
      (if sk
        (store/lru-get! static-scope-cache 32 sk compute)
        (compute)))))

(def ^:private live-main-cache (store/lru-cache 16))

(defn missing-contract-violations [& args] (apply contracts/missing-contract-violations args))

(defn caused-by-branch
  "Returns only the structural/schema health errors *introduced* by `theirs-db` relative to `ours-db`
   (including `:retyped-attribute`, `:schema-breaks-stored-data`, new `:blocking-in-flow-proc`,
   new `:used-before-defined`, and new `:missing-ns-form`). When `touched-nss` is supplied,
   runs `used-before-defined` and `missing-ns-forms` in O(|Delta|) instead of scanning all N forms."
  {:malli/schema [:function [:=> [:cat :any :any :map] [:vector :map]] [:=> [:cat :any :any :map [:maybe [:sequential :symbol]]] [:vector :map]]]}
  ([ours-db theirs-db changed-schema-forms]
   (caused-by-branch ours-db theirs-db changed-schema-forms nil))
  ([ours-db theirs-db changed-schema-forms touched-nss]
   (let [schema-errs (schema-violations ours-db changed-schema-forms)
         base-blocks (into #{} (map (juxt :class :subject :var)) (blocking-in-flow-procs ours-db))
         new-blocks (->> (blocking-in-flow-procs theirs-db)
                         (remove #(contains? base-blocks ((juxt :class :subject :var) %))))
         base-ubd (into #{} (map :subject) (used-before-defined ours-db touched-nss))
         new-ubd (->> (used-before-defined theirs-db touched-nss)
                      (remove #(contains? base-ubd (:subject %))))
         base-mns (into #{} (map :subject) (missing-ns-forms ours-db touched-nss))
         new-mns (->> (missing-ns-forms theirs-db touched-nss)
                      (remove #(contains? base-mns (:subject %))))]
     (let [base-bref (into #{} (map :subject) (broken-symbol-refs ours-db))
         new-bref (remove #(contains? base-bref (:subject %)) (broken-symbol-refs theirs-db))
         base-dref (into #{} (map :subject) (broken-doc-refs ours-db))
         new-dref (remove #(contains? base-dref (:subject %)) (broken-doc-refs theirs-db))
         base-imp (into #{} (map :subject) (impure-generatable-fns ours-db))
         new-imp (remove #(contains? base-imp (:subject %)) (impure-generatable-fns theirs-db))]
     (vec (concat schema-errs new-blocks new-ubd new-mns new-bref new-dref new-imp
                  (missing-contract-violations ours-db theirs-db touched-nss)))))))

(defn test-digest-supersession "Two flat AEVT scans (no nested loops): `:var->green-tx` maps a test var to the highest\n             tx at which one of its `:seon.test` rows passed; `:eid->pass-tx` maps each test entity\n             (digest) to the tx of its `:seon.test/pass?` datom. Used to detect stale failing digests\n             that a later green run has superseded."
  {:malli/schema [:=> [:cat :seon.host/db] [:map [:var->green-tx :map] [:eid->pass-tx :map]]], :cost/ticks 800000, :cost/bytes 20000, :malli/examples [[[{}] {:var->green-tx {}, :eid->pass-tx {}}]]}
  [db] (let [var-by-eid (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.test/var))] (reduce (fn [m [e pass? tx]] (let [m (assoc-in m [:eid->pass-tx e] tx)] (if-let [vsym (and pass? (get var-by-eid e))] (update-in m [:var->green-tx vsym] (fnil max -1) tx) m))) {:var->green-tx {}, :eid->pass-tx {}} (map (juxt :e :v :tx) (d/datoms db :aevt :seon.test/pass?)))))

(defn superseded-digest? "True when a failing test digest (entity `eid`, var `vsym`) has been superseded by a later\n             passing run of the same var. Stale digests must never pin a branch red."
  {:malli/schema [:=> [:cat :symbol [:maybe :int] :map] :boolean], :cost/ticks 200, :cost/bytes 200, :malli/examples [[[seon.health/failing-tests 1 {:var->green-tx {seon.health/failing-tests 5}, :eid->pass-tx {1 2}}] true] [[seon.health/failing-tests 1 {:var->green-tx {}, :eid->pass-tx {1 2}}] false]]}
  [vsym eid {:keys [var->green-tx eid->pass-tx]}] (let [green-tx (get var->green-tx vsym) fail-tx (get eid->pass-tx eid)] (boolean (and green-tx fail-tx (> green-tx fail-tx)))))

(defn failing-tests
  "Global view of failing tests across `:main` and all active (non-archived) branches.
   Stale failing digests superseded by a later passing run of the same var are skipped
   (0 passing tests returned!)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (if-not (contains? (:schema db) :seon.test.run/branch)
    []
    (let [agents-by-branch
          (->> (d/datoms db :aevt :seon.agent/id)
               (map #(d/pull db '[:seon.agent/id :seon.agent/branch :seon.agent/archived?] (:e %)))
               (group-by :seon.agent/branch))
          archived-branch?
          (fn [br]
            (and (not= br :main)
                 (let [ags (get agents-by-branch br)]
                   (and (seq ags) (every? :seon.agent/archived? ags)))))
          supersession (test-digest-supersession db)]
      (->> (d/datoms db :aevt :seon.test.run/branch)
           (map #(d/pull db '[:seon.test.run/branch :seon.test.run/commit :seon.test.run/agent
                              :seon.test.run/pass? :seon.test.run/failed-digests] (:e %)))
           (remove :seon.test.run/pass?)
           (remove #(archived-branch? (:seon.test.run/branch %)))
           (mapcat
            (fn [run]
              (let [br (:seon.test.run/branch run)
                    ags (->> (get agents-by-branch br)
                             (remove :seon.agent/archived?)
                             (mapv :seon.agent/id))]
                (for [dg (sort (:seon.test.run/failed-digests run))
                      :let [v (d/pull db '[:db/id :seon.test/var :seon.test/kind :seon.test/ms :seon.test/report]
                                      [:seon.test/digest dg])]
                      :when (:seon.test/var v)
                      :let [vsym (:seon.test/var v)]
                      :when (not (superseded-digest? vsym (:db/id v) supersession))]
                  (cond-> {:class :failing-test
                           :severity :error
                           :kind :live
                           :subject (str vsym)
                           :var vsym
                           :branch br
                           :message (str "[" (name (or (:seon.test/kind v) :deftest)) " on " br
                                         (when (seq ags) (str ", agents " (pr-str ags)))
                                         "] " (:seon.test/report v))}
                    (namespace vsym) (assoc :ns (symbol (namespace vsym))))))))
           vec))))

(defn report
  "Computes the complete `:seon.health/report` map from `db` (pure read, tagged with `:seon/schema`).
   `:ok?` is true whenever zero `:error`-severity problems exist (`:warning` advisories surface
   without blocking unrelated merges). Fast-pathed on `[store-id branch commit-id max-tx]` (< 0.05 ms)
   with content-keyed fallback and exact `datahike.reads` replay so reactive UI walks stay 100% sound."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (let [sk (db-snapshot-key db)
        compute-full
        (fn []
          (let [ck (report-cache-key db)
                run-inner
                (fn []
                  (reads/with-reads
                   (fn []
                     (let [vmap (src-var-map db)
                           probs (vec (concat (blocking-in-flow-procs db vmap)
                                              (state-outside-flow db vmap)
                                              (used-before-defined db)
                                              (missing-ns-forms db)
                                              (unschemed-attributes db)
                                              (broken-symbol-refs db)
                                              (broken-doc-refs db)
                                              (concat (impure-generatable-fns db) (contracts/invalid-contract-violations db))
                                              (static-efficiency-warnings db)
                                              (failing-tests db)
                                              (test-efficiency-warnings db)
                                              (open-faults db)))
                           counts (frequencies (map :class probs))
                           errors? (boolean (some #(= :error (:severity %)) probs))]
                       (with-meta
                         {:ok? (not errors?)
                          :branch (get-in db [:config :branch] :main)
                          :counts counts
                          :problems probs}
                         {:seon/schema :seon.health/report})))))]
            (let [[rep rs] (if ck
                             (store/lru-get! report-cache 12 ck run-inner)
                             (run-inner))]
              {:report rep :reads rs})))
        entry (if sk
                (store/lru-get! report-commit-cache 32 sk compute-full)
                (compute-full))]
    (when-let [outer di/*reads*]
      (when (seq (:reads entry))
        (swap! outer into (:reads entry))))
    (:report entry)))

(defn- cached-live-main-problems [main-db]
  (let [sk (db-snapshot-key main-db)
        compute (fn []
                  (vec (concat (failing-tests main-db)
                               (test-efficiency-warnings main-db)
                               (open-faults main-db))))]
    (if sk
      (store/lru-get! live-main-cache 16 sk compute)
      (compute))))

(defn live-problems-for-scope
  "Returns dynamic runtime, test, perf-outlier, and eval-efficiency alerts for `scope` + `agent-id`.
   Caches global `:main` live problems per DB commit-id and accepts optional `prefetched-evals`."
  {:malli/schema [:function
                  [:=> [:cat :any :any :any :any] [:vector :map]]
                  [:=> [:cat :any :any :any :any :any] [:vector :map]]]}
  ([main-db branch-db scope agent-id]
   (live-problems-for-scope main-db branch-db scope agent-id nil))
  ([main-db branch-db scope agent-id prefetched-evals]
   (let [live-main (cached-live-main-problems main-db)
         eval-warns (eff/eval-efficiency-warnings branch-db agent-id prefetched-evals)]
     (if (= scope 'seon)
       (vec (concat live-main eval-warns))
       (let [owned? (fn [p]
                      (or (= agent-id (:agent p))
                          (ns-in-scope? scope (:ns p))
                          (when-let [v (:var p)]
                            (and (namespace v) (ns-in-scope? scope (symbol (namespace v)))))))]
         (vec (concat (filter owned? live-main) eval-warns)))))))