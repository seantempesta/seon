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
(:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.error :as me]
            [seon.schema :as schema]
            [seon.health.efficiency :as eff]
            [seon.store :as store]
            [seon.health.static :as static]))

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

(defn- full-db-scan-form? [x]
  (and (seq? x) (symbol? (first x))
       (let [nm (name (first x))]
         (or (and (= "datoms" nm) (= :aevt (nth x 2 nil)))
             (= "q" nm)))))

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

(defn failing-tests
  "Global view of failing tests across `:main` and all active branches (0 passing tests returned!)."
  [db]
  (if-not (contains? (:schema db) :seon.test.run/branch)
    []
    (let [agents-by-branch
          (->> (d/datoms db :aevt :seon.agent/id)
               (map #(d/pull db '[:seon.agent/id :seon.agent/branch] (:e %)))
               (group-by :seon.agent/branch))]
      (->> (d/datoms db :aevt :seon.test.run/branch)
           (map #(d/pull db '[:seon.test.run/branch :seon.test.run/commit :seon.test.run/agent
                              :seon.test.run/pass? :seon.test.run/failed-digests] (:e %)))
           (remove :seon.test.run/pass?)
           (mapcat
            (fn [run]
              (let [br (:seon.test.run/branch run)
                    ags (mapv :seon.agent/id (get agents-by-branch br))]
                (for [dg (sort (:seon.test.run/failed-digests run))
                      :let [v (d/pull db '[:seon.test/var :seon.test/kind :seon.test/ms :seon.test/report]
                                      [:seon.test/digest dg])]
                      :when (:seon.test/var v)
                      :let [vsym (:seon.test/var v)]]
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

(defn broken-symbol-refs
  "Referential integrity gate: flags any qualified symbol referenced by `:seon.schema/render`,
   `:seon.render/hiccup` inside `:seon.schema/form`, `:seon.agent/render-fn`, or `:seon.agent/tail-renders`
   that does not exist in `:seon.code.var/name` (catching renderer typos or deleted fns at merge time)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (if-not (and db (contains? (:schema db) :seon.code.var/name))
    []
    (let [br (get-in db [:config :branch] :main)
          live-vars (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name))]
      (if (empty? live-vars)
        []
        (let [errs (volatile! [])
              check! (fn [src-kind owner sym]
                       (when (and (qualified-symbol? sym)
                                  (not (contains? live-vars sym))
                                  (nil? (try (resolve sym) (catch Throwable _ nil))))
                         (vswap! errs conj
                                 {:class :broken-symbol-ref
                                  :severity :error
                                  :kind :static
                                  :ns (symbol (namespace sym))
                                  :var sym
                                  :subject (str src-kind " " owner " -> " sym)
                                  :branch br
                                  :message (str "Broken symbol reference in " src-kind " on " owner
                                                ": var " sym " does not exist. Define it or update the reference.")})))]
          (when (contains? (:schema db) :seon.schema/render)
            (doseq [dt (d/datoms db :aevt :seon.schema/render)]
              (let [k (:seon.schema/key (d/pull db '[:seon.schema/key] (:e dt)))]
                (check! :seon.schema/render k (:v dt)))))
          (when (contains? (:schema db) :seon.schema/form)
            (doseq [dt (d/datoms db :aevt :seon.schema/form)]
              (let [f (:v dt)
                    r (when (and (vector? f) (map? (second f))) (:seon.render/hiccup (second f)))]
                (when r
                  (let [k (:seon.schema/key (d/pull db '[:seon.schema/key] (:e dt)))]
                    (check! :seon.render/hiccup k r))))))
          (when (contains? (:schema db) :seon.agent/render-fn)
            (doseq [dt (d/datoms db :aevt :seon.agent/render-fn)]
              (let [a (:seon.agent/id (d/pull db '[:seon.agent/id] (:e dt)))]
                (check! :seon.agent/render-fn a (:v dt)))))
          (when (contains? (:schema db) :seon.agent/tail-renders)
            (doseq [dt (d/datoms db :aevt :seon.agent/tail-renders)]
              (let [a (:seon.agent/id (d/pull db '[:seon.agent/id] (:e dt)))]
                (doseq [sym (:v dt)]
                  (check! :seon.agent/tail-renders a sym)))))
          (vec (sort-by :subject (distinct @errs))))))))

(defn- report-cache-key [db]
  (when (and db
             (not (instance? datahike.db.AsOfDB db))
             (not (instance? datahike.db.HistoricalDB db)))
    [(get-in db [:config :branch] :main)
     (count (:schema db))
     (attr-sig db :seon.code.form/hash)
     (attr-sig db :seon.code.form/order)
     (attr-sig db :seon.code.var/name)
     (attr-sig db :seon.schema/key)
     (attr-sig db :seon.test/digest)
     (attr-sig db :seon.health.fault/id)
     (attr-sig db :seon.health.fault/resolved?)]))

(defn broken-doc-refs
  "Detects backticked qualified symbol references (e.g. `` `seon.health/report` ``) inside var or namespace
  docstrings that do not exist in `:seon.code.var/name` on `db`, preventing docstring rot."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (if-not (and db
               (contains? (:schema db) :seon.code.var/name)
               (contains? (:schema db) :seon.code.var/doc))
    []
    (let [br (get-in db [:config :branch] :main)
          all-vars (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name))
          pat #"`(seon\.[a-z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)`"
          var-probs
          (for [dt (d/datoms db :aevt :seon.code.var/doc)
                :let [v (d/pull db '[:seon.code.var/name :seon.code.var/ns :seon.code.var/doc] (:e dt))
                      vsym (:seon.code.var/name v)
                      nsym (:seon.code.var/ns v)
                      doc (str (:seon.code.var/doc v))]
                :when (and vsym nsym (not (str/ends-with? (name nsym) "-test")))
                [_ sym-s] (re-seq pat doc)
                :let [sym (try (symbol sym-s) (catch Throwable _ nil))]
                :when (and sym (not (contains? all-vars sym)))]
            {:class :broken-doc-ref
             :severity :error
             :kind :static
             :ns nsym
             :var vsym
             :subject (str vsym "->" sym)
             :branch br
             :message (str "Docstring of " vsym " references missing var `" sym "`. Update the docstring or restore the var.")})
          ns-probs
          (if-not (contains? (:schema db) :seon.code.ns/doc)
            []
            (for [dt (d/datoms db :aevt :seon.code.ns/doc)
                  :let [n (d/pull db '[:seon.code.ns/name :seon.code.ns/doc] (:e dt))
                        nsym (:seon.code.ns/name n)
                        doc (str (:seon.code.ns/doc n))]
                  :when (and nsym (not (str/ends-with? (name nsym) "-test")))
                  [_ sym-s] (re-seq pat doc)
                  :let [sym (try (symbol sym-s) (catch Throwable _ nil))]
                  :when (and sym (not (contains? all-vars sym)))]
              {:class :broken-doc-ref
               :severity :error
               :kind :static
               :ns nsym
               :subject (str nsym "#ns->" sym)
               :branch br
               :message (str "Namespace docstring of " nsym " references missing var `" sym "`. Update the docstring.")}))]
      (vec (sort-by :subject (concat var-probs ns-probs))))))

(defn impure-generatable-fns
  "Detects non-edge functions carrying a generatable `[:=> ...]` `:malli/schema` whose static/IR
   references call a `^:seon.flow/edge` side-effecting var (which would fire 100x during `stress-var`)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (if-not (and db
               (contains? (:schema db) :seon.code.var/schema)
               (contains? (:schema db) :seon.code.var/flow-edge?)
               (contains? (:schema db) :seon.code.var/refs))
    []
    (let [br (get-in db [:config :branch] :main)
          edge-vars (into #{}
                          (keep (fn [dt]
                                  (when (:v dt)
                                    (:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e dt))))))
                          (d/datoms db :aevt :seon.code.var/flow-edge?))]
      (if (empty? edge-vars)
        []
        (->> (d/datoms db :aevt :seon.code.var/schema)
             (keep (fn [dt]
                     (let [v (d/pull db '[:seon.code.var/name :seon.code.var/ns :seon.code.var/schema
                                          :seon.code.var/refs :seon.code.var/flow-edge?] (:e dt))
                           vsym (:seon.code.var/name v)
                           nsym (:seon.code.var/ns v)
                           sch (:seon.code.var/schema v)]
                       (when (and vsym nsym
                                  (not (str/ends-with? (name nsym) "-test"))
                                  (not (:seon.code.var/flow-edge? v))
                                  (schema/generatable-function-schema? sch))
                         (let [bad-refs (vec (sort (filter #(contains? edge-vars %) (:seon.code.var/refs v))))]
                           (when (seq bad-refs)
                             {:class :impure-generatable-fn
                              :severity :error
                              :kind :static
                              :ns nsym
                              :var vsym
                              :subject (str vsym)
                              :branch br
                              :message (str "Generatable pure schema on " vsym " calls ^:seon.flow/edge var(s) "
                                            (str/join ", " bad-refs)
                                            ". Mark ^:seon.flow/edge, use :seon.host/* args, or keep pure logic separate from IO.")}))))))
             (sort-by :subject)
             vec)))))

(defn report
"Computes the complete `:seon.health/report` map from `db` (pure read, tagged with `:seon/schema`).
 `:ok?` is true whenever zero `:error`-severity problems exist (`:warning` advisories surface
 without blocking unrelated merges). Content-keyed on code/schema/test/fault signatures so UI
 interactions and chat commits hit in < 1 ms while keeping `datahike.reads` 100% sound."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
[db]
(let [ck (report-cache-key db)
      compute (fn []
                (let [vmap (src-var-map db)
                      probs (vec (concat (blocking-in-flow-procs db vmap)
                                         (state-outside-flow db vmap)
                                         (used-before-defined db)
                                         (missing-ns-forms db)
                                         (unschemed-attributes db)
                                         (broken-symbol-refs db)
                                         (broken-doc-refs db)
                                         (impure-generatable-fns db)
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
                    {:seon/schema :seon.health/report})))]
  (if ck
    (store/lru-get! report-cache 12 ck compute)
    (compute))))

(defn static-problems-for-scope
  "Returns deterministic code-derived health problems owned by `scope` (exact ns or descendant `scope.*`).
   Safe to include inside static `<walk-context>` (`ns-tile`) with zero KV-cache churn across turns."
  [db scope]
  (if-not (and db scope (not= scope 'seon))
    []
    (filterv (fn [p]
               (and (= :static (:kind p))
                    (ns-in-scope? scope (:ns p))))
             (:problems (report db)))))

(defn live-problems-for-scope
  "Returns dynamic runtime, test, perf-outlier, and eval-efficiency alerts for `scope` + `agent-id`:
   - For root coordinator (`scope = 'seon`): returns the global roll-up of live + static warnings/errors
     annotated with their owning `:ns/<subsystem>` specialist so root knows who to delegate to.
   - For a specialist (`scope = 'seon.render`, etc.): returns only live alerts owned by `scope` subtree
     plus `agent-id`'s own eval/cache warnings. Lives in ephemeral `<neighborhood-activity>`."
  [main-db branch-db scope agent-id]
  (let [live-main (concat (failing-tests main-db)
                          (test-efficiency-warnings main-db)
                          (open-faults main-db))
        eval-warns (eval-efficiency-warnings branch-db agent-id)]
    (if (= scope 'seon)
      (vec (concat live-main eval-warns))
      (let [owned? (fn [p]
                     (or (= agent-id (:agent p))
                         (ns-in-scope? scope (:ns p))
                         (when-let [v (:var p)]
                           (and (namespace v) (ns-in-scope? scope (symbol (namespace v)))))))]
        (vec (concat (filter owned? live-main) eval-warns))))))

(defn missing-contract-violations
  "Hard merge gate: flags any newly added or edited non-test public `defn` on `theirs-db`
   (relative to `ours-db`) that lacks a `:malli/schema` contract (skipping thin `[& args] (apply ...)`
   forwarding stubs). Returns actionable `:error` violations with copy-pasteable `user/set-meta!` hints."
  {:malli/schema [:function
                  [:=> [:cat :any :any] [:vector :map]]
                  [:=> [:cat :any :any [:maybe [:sequential :symbol]]] [:vector :map]]]}
  ([ours-db theirs-db] (missing-contract-violations ours-db theirs-db nil))
  ([ours-db theirs-db touched-nss]
   (if-not (and theirs-db
                (contains? (:schema theirs-db) :seon.code.var/name)
                (contains? (:schema theirs-db) :seon.code.form/key))
     []
     (let [br (get-in theirs-db [:config :branch] :main)
         ;; Fast O(|changed-forms|) filter: find only form keys whose hash differs from ours-db!
         changed-fkeys
         (if (seq touched-nss)
           (into #{}
                 (for [n (distinct touched-nss)
                       :when (and n (not (str/ends-with? (name n) "-test")))
                       dt (d/datoms theirs-db :avet :seon.code.form/ns n)
                       :let [e (:e dt)
                             fk (:v (first (d/datoms theirs-db :eavt e :seon.code.form/key)))
                             th (:v (first (d/datoms theirs-db :eavt e :seon.code.form/hash)))
                             oh (when (and ours-db fk (contains? (:schema ours-db) :seon.code.form/key))
                                  (when-let [oe (:e (first (d/datoms ours-db :avet :seon.code.form/key fk)))]
                                    (:v (first (d/datoms ours-db :eavt oe :seon.code.form/hash)))))]
                       :when (and fk th (not= th oh))]
                   fk))
           (let [fmap (fn [db]
                        (if-not (and db (contains? (:schema db) :seon.code.form/key))
                          {}
                          (let [ks (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))]
                            (reduce (fn [a dt]
                                      (if-let [k (get ks (:e dt))] (assoc a k (:v dt)) a))
                                    {}
                                    (d/datoms db :aevt :seon.code.form/hash)))))
                 ohs (fmap ours-db)
                 ths (fmap theirs-db)]
             (into #{}
                   (keep (fn [[fk th]] (when (not= th (get ohs fk)) fk)))
                   ths)))]
     (if (empty? changed-fkeys)
       []
       (let [proj '[:seon.code.var/name :seon.code.var/ns :seon.code.var/form
                    :seon.code.var/private? :seon.code.var/macro? :seon.code.var/test?
                    :seon.code.var/arglists :seon.code.var/schema]]
         (->> changed-fkeys
              (mapcat #(map :e (d/datoms theirs-db :avet :seon.code.var/form %)))
              (keep (fn [e]
                      (let [v (d/pull theirs-db proj e)
                            vsym (:seon.code.var/name v)
                            nsym (:seon.code.var/ns v)
                            fk (:seon.code.var/form v)]
                        (when (and vsym nsym fk
                                   (not (str/ends-with? (name nsym) "-test"))
                                   (not (:seon.code.var/test? v))
                                   (not (:seon.code.var/private? v))
                                   (not (:seon.code.var/macro? v))
                                   (seq (:seon.code.var/arglists v))
                                   (nil? (:seon.code.var/schema v)))
                          (let [fe (:e (first (d/datoms theirs-db :avet :seon.code.form/key fk)))
                                src (str (when fe (:v (first (d/datoms theirs-db :eavt fe :seon.code.form/src)))))
                                delegate-stub? (boolean (re-find #"\[&\s+args\]\s*\(apply\s+" src))]
                            (when-not delegate-stub?
                              {:class :missing-contract
                               :severity :error
                               :kind :static
                               :ns nsym
                               :var vsym
                               :subject (str vsym)
                               :branch br
                               :message (str "Public defn " vsym " was added/edited without a :malli/schema contract. "
                                             "Add one via (user/set-meta! '" vsym " {:malli/schema [:=> [:cat ...] :any]}) — "
                                             "for host values use :seon.host/db, :seon.host/conn, :seon.host/chan, or :any; "
                                             "for pure fns include :cost/ticks & :cost/bytes for seed=42 generative proofs.")}))))))
              (sort-by :subject)
              vec)))))))

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