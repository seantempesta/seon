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
          [seon.schema.datahike :as sdh]
          [seon.health.efficiency :as eff]
          [seon.store :as store]))

(def ^:private blocking-syms
  #{'clojure.core.async/<!!
    'clojure.core.async/>!!
    'clojure.core.async/alts!!
    'Thread/sleep})

(def ^:private top-level-state-syms
  #{'clojure.core/atom
    'clojure.core/volatile!
    'clojure.core/promise
    'clojure.core/agent})

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

(defn- src-var-map
  "Builds `{var-sym {:ns :refs :arglists :flow-edge?}}` for all non-test vars in `db` using
   6 linear AEVT scans (zero `d/pull` overhead — ~8x faster than per-entity pulls)."
  [db]
  (if-not (and db (contains? (:schema db) :seon.code.var/name))
    {}
    (let [names (reduce (fn [acc dt] (assoc acc (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.var/name))
          nss   (reduce (fn [acc dt] (assoc acc (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.var/ns))
          tests (if (contains? (:schema db) :seon.code.var/test?)
                  (reduce (fn [acc dt] (if (:v dt) (conj acc (:e dt)) acc)) #{} (d/datoms db :aevt :seon.code.var/test?))
                  #{})
          edges (if (contains? (:schema db) :seon.code.var/flow-edge?)
                  (reduce (fn [acc dt] (if (:v dt) (conj acc (:e dt)) acc)) #{} (d/datoms db :aevt :seon.code.var/flow-edge?))
                  #{})
          args  (if (contains? (:schema db) :seon.code.var/arglists)
                  (reduce (fn [acc dt] (assoc acc (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.var/arglists))
                  {})
          refs  (if (contains? (:schema db) :seon.code.var/refs)
                  (reduce (fn [acc dt] (update acc (:e dt) (fnil conj #{}) (:v dt))) {} (d/datoms db :aevt :seon.code.var/refs))
                  {})]
      (reduce-kv (fn [acc e sym]
                   (let [n (get nss e)]
                     (if (and n (not (contains? tests e)) (not (str/ends-with? (name n) "-test")))
                       (assoc acc sym {:seon.code.var/name sym
                                       :seon.code.var/ns n
                                       :seon.code.var/refs (get refs e #{})
                                       :seon.code.var/arglists (get args e)
                                       :seon.code.var/flow-edge? (contains? edges e)})
                       acc)))
                 {}
                 names))))

(defn blocking-in-flow-procs
  "Flags any Flow proc (`*-proc` var in `src/`) whose transitive call graph reaches blocking ops
   (`<!!`, `>!!`, `alts!!`, `Thread/sleep`) without an explicit `^:seon.flow/edge` annotation."
  ([db] (blocking-in-flow-procs db (src-var-map db)))
  ([db vmap]
   (let [br (get-in db [:config :branch] :main)
         proc-vars (filter (fn [[sym v]]
                             (and (str/ends-with? (name sym) "-proc")
                                  (not (:seon.code.var/flow-edge? v))))
                           vmap)]
     (vec
      (mapcat
       (fn [[proc-sym _]]
         (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [proc-sym [proc-sym]])
                visited #{proc-sym}
                hits []]
           (if (empty? queue)
             hits
             (let [[cur path] (peek queue)
                   q' (pop queue)
                   cur-v (get vmap cur)
                   refs (:seon.code.var/refs cur-v #{})]
               (if (:seon.code.var/flow-edge? cur-v)
                 (recur q' visited hits)
                 (let [direct-blocks (filter blocking-syms refs)
                       new-hits (if (seq direct-blocks)
                                  (conj hits
                                        {:class :blocking-in-flow-proc
                                         :severity :error
                                         :kind :static
                                         :ns (symbol (namespace proc-sym))
                                         :subject (str proc-sym)
                                         :var cur
                                         :branch br
                                         :message (str "Flow proc " proc-sym " reaches blocking op "
                                                       (str/join ", " (sort direct-blocks))
                                                       " via " (str/join " -> " path)
                                                       ". Use Flow out-ports or mark an edge var ^:seon.flow/edge.")})
                                  hits)
                       nexts (->> refs
                                  (filter #(and (contains? vmap %) (not (contains? visited %))))
                                  sort)]
                   (recur (into q' (map (fn [n] [n (conj path n)]) nexts))
                          (into visited nexts)
                          new-hits)))))))
       (sort-by key proc-vars))))))

(defn state-outside-flow
  "Flags top-level `def`/`defonce` vars in `src/` holding an `atom`, `volatile!`, `promise`, or `agent`
   without an explicit `^:seon.flow/edge` annotation."
  ([db] (state-outside-flow db (src-var-map db)))
  ([db vmap]
   (let [br (get-in db [:config :branch] :main)]
     (->> vmap
          (keep (fn [[sym v]]
                  (when (and (empty? (:seon.code.var/arglists v))
                             (not (:seon.code.var/flow-edge? v)))
                    (let [bad (filter top-level-state-syms (:seon.code.var/refs v #{}))]
                      (when (seq bad)
                        {:class :state-outside-flow
                         :severity :warning
                         :kind :static
                         :ns (:seon.code.var/ns v)
                         :subject (str sym)
                         :var sym
                         :branch br
                         :message (str "Top-level mutable state (" (str/join ", " (sort bad))
                                       ") outside Flow in " sym ". Move into Flow proc state or mark ^:seon.flow/edge.")})))))
          (sort-by :subject)
          vec))))

(defn schema-violations
  "Checks candidate schemas (`changed-forms`: `{schema-key form}`) against existing `ours-db`:
   1. `:retyped-attribute` — any installed Datahike attribute whose derived `:db/valueType` or
      `:db/cardinality` differs from `(:schema ours-db)`.
   2. `:schema-breaks-stored-data` — any existing stored datom in `ours-db` for a changed (or
      transitively reached) attribute whose value fails the new Malli schema.
   Forces safe accretive schema migrations instead of silently corrupting stored data!"
  [ours-db changed-forms]
  (if (empty? changed-forms)
    []
    (let [reg-info (schema/registry-for-db ours-db changed-forms)
          {:keys [forms registry declarations]} reg-info
          changed-keys (set (keys changed-forms))
          br (get-in ours-db [:config :branch] :main)
          reached-keys
          (loop [frontier changed-keys
                 seen changed-keys]
            (let [parents (->> forms
                               (keep (fn [[k f]]
                                       (when (and (not (contains? seen k))
                                                  (some #(contains? frontier %) (schema/schema-refs-in-form f)))
                                         k)))
                               set)]
              (if (empty? parents)
                seen
                (recur parents (into seen parents)))))
          decl-by-id (into {} (map (juxt :db/ident identity)) declarations)
          installed (:schema ours-db)
          retyped
          (->> reached-keys
               (keep (fn [k]
                       (when-let [old-spec (get installed k)]
                         (when-let [new-spec (get decl-by-id k)]
                           (when (or (not= (:db/valueType old-spec) (:db/valueType new-spec))
                                     (not= (:db/cardinality old-spec) (:db/cardinality new-spec)))
                             {:class :retyped-attribute
                              :severity :error
                              :kind :static
                              :ns (symbol (namespace k))
                              :subject (str k)
                              :branch br
                              :message (str "Cannot retype installed Datahike attribute " k
                                            " from " [(:db/valueType old-spec) (:db/cardinality old-spec)]
                                            " to " [(:db/valueType new-spec) (:db/cardinality new-spec)]
                                            ". Create a new attribute and migrate data accretively.")})))))
               vec)
          refused
          (->> reached-keys
               (mapcat
                (fn [attr]
                  (when (contains? installed attr)
                    (when-let [{:keys [schema valid?]} (schema/attr-validator-for reg-info attr)]
                      (->> (d/datoms ours-db :aevt attr)
                           (keep (fn [dt]
                                   (when-not (valid? (:v dt))
                                     {:class :schema-breaks-stored-data
                                      :severity :error
                                      :kind :static
                                      :ns (symbol (namespace attr))
                                      :subject (str attr)
                                      :branch br
                                      :message (str "Schema change to " attr " rejects existing stored value "
                                                    (pr-str (:v dt)) " on entity " (:e dt) ": "
                                                    (pr-str (me/humanize (m/explain schema (:v dt) {:registry registry})))
                                                    ". Migrate existing datoms first or introduce a new schema key.")})))
                           (take 10))))))
               vec)]
      (into retyped refused))))

(defn used-before-defined
  "Flags forms in `src/` (or specific `ns-syms`) that reference a same-namespace var whose only
   definition appears later in the file without an earlier `(declare ...)`.
   Uses indexed `:avet :seon.code.form/ns` when scoped to specific `ns-syms` and linear AEVT scans
   for full-codebase checks; only pulls `:seon.code.form/src` when a forward reference exists."
  ([db] (used-before-defined db nil))
  ([db ns-syms]
   (if-not (and db (contains? (:schema db) :seon.code.form/key))
     []
     (let [br (get-in db [:config :branch] :main)
           forms-by-ns
           (if ns-syms
             (into {}
                   (for [nsym ns-syms
                         :when (and nsym (not (str/ends-with? (name nsym) "-test")))
                         :let [fs (mapv (fn [dt]
                                          (let [e (:e dt)
                                                p (d/pull db '[:seon.code.form/key :seon.code.form/order :seon.code.form/deps] e)]
                                            {:e e
                                             :key (:seon.code.form/key p)
                                             :order (or (:seon.code.form/order p) 0)
                                             :deps (set (:seon.code.form/deps p))}))
                                        (d/datoms db :avet :seon.code.form/ns nsym))]
                         :when (seq fs)]
                     [nsym fs]))
             (let [nss-by-e (reduce (fn [acc dt] (assoc acc (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/ns))
                   keys-by-e (reduce (fn [acc dt] (assoc acc (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))
                   ords-by-e (reduce (fn [acc dt] (assoc acc (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/order))
                   deps-by-e (if (contains? (:schema db) :seon.code.form/deps)
                               (reduce (fn [acc dt] (update acc (:e dt) (fnil conj #{}) (:v dt))) {} (d/datoms db :aevt :seon.code.form/deps))
                               {})]
               (reduce-kv (fn [acc e nsym]
                            (if (and nsym (not (str/ends-with? (name nsym) "-test")))
                              (update acc nsym (fnil conj [])
                                      {:e e
                                       :key (get keys-by-e e)
                                       :order (or (get ords-by-e e) 0)
                                       :deps (get deps-by-e e #{})})
                              acc))
                          {}
                          nss-by-e)))]
       (vec
        (mapcat
         (fn [[nsym raw-forms]]
           (let [forms (sort-by :order raw-forms)
                 def-orders (into {}
                                  (keep (fn [{:keys [key order]}]
                                          (when (and key (not (str/includes? key "#")))
                                            [(symbol key) order])))
                                  forms)
                 candidates (keep (fn [{:keys [key order deps]}]
                                    (let [fwd (->> deps
                                                   (filter (fn [dep]
                                                             (when-let [dord (get def-orders dep)]
                                                               (> dord order))))
                                                   sort)]
                                      (when (seq fwd)
                                        {:key key :order order :fwd fwd})))
                                  forms)]
             (when (seq candidates)
               ;; Only pull `:seon.code.form/src` on `#expr#` forms in this namespace to check `(declare ...)`!
               (let [decl-orders (reduce
                                  (fn [acc {:keys [e key order]}]
                                    (if (and key (str/includes? key "#expr#"))
                                      (let [s (str/triml (str (:v (first (d/datoms db :eavt e :seon.code.form/src)))))]
                                        (if (str/starts-with? s "(declare ")
                                          (let [syms (rest (try (read-string s) (catch Throwable _ nil)))]
                                            (reduce #(assoc %1 (symbol (name nsym) (name %2)) order)
                                                    acc (filter symbol? syms)))
                                          acc))
                                      acc))
                                  {}
                                  forms)]
                 (keep (fn [{:keys [key order fwd]}]
                         (let [bad (filter (fn [dep]
                                             (let [decl (get decl-orders dep)]
                                               (or (nil? decl) (> decl order))))
                                           fwd)]
                           (when (seq bad)
                             {:class :used-before-defined
                              :severity :error
                              :kind :static
                              :ns nsym
                              :subject key
                              :branch br
                              :message (str "Form " key " references " (str/join ", " bad)
                                            " before definition (breaks cold AOT builds). Reorder or add (declare ...).")})))
                       candidates)))))
         (sort-by key forms-by-ns)))))))

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

(defn unschemed-attributes
  "Flags any `:seon.*/*` attribute installed in Datahike (`(:schema db)`) that lacks a registered
   Malli `:seon.schema/key` entity in `db` — guaranteeing 100% Malli-derived schema discipline."
  [db]
  (let [br (get-in db [:config :branch] :main)
        registered (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))]
    (->> (keys (:schema db))
         (filter #(and (qualified-keyword? %)
                       (str/starts-with? (namespace %) "seon.")
                       (not (contains? registered %))))
         sort
         (mapv (fn [attr]
                 {:class :unschemed-attribute
                  :severity :error
                  :kind :static
                  :ns (symbol (namespace attr))
                  :subject (str attr)
                  :branch br
                  :message (str "Datahike attribute " attr " has no registered Malli :seon.schema/key. "
                                "Register it via Malli schemas instead of installing raw Datahike attributes.")})))))

(defn missing-ns-forms
  "Flags any namespace in `:seon.code.form/ns` (or `ns-syms`) that defines forms without a `<ns>#ns`
   `(ns ...)` header (e.g. created via bare `in-ns` instead of `(ns ...)`), which breaks cold AOT.
   When `ns-syms` is given, runs in O(|ns-syms|) via indexed `:avet` lookups."
  ([db] (missing-ns-forms db nil))
  ([db ns-syms]
   (if-not (and db (contains? (:schema db) :seon.code.form/key))
     []
     (let [br (get-in db [:config :branch] :main)
           missing
           (if ns-syms
             (->> ns-syms
                  (remove nil?)
                  distinct
                  (filter (fn [nsym]
                            (and (first (d/datoms db :avet :seon.code.form/ns nsym))
                                 (not (first (d/datoms db :avet :seon.code.form/key (str nsym "#ns")))))))
                  sort)
             (let [ns-by-e (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/ns))
                   key-by-e (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))
                   all-nss (into #{} (remove nil?) (vals ns-by-e))
                   has-hdr (into #{} (keep (fn [[e k]]
                                             (when (and (string? k) (str/ends-with? k "#ns"))
                                               (get ns-by-e e))))
                                 key-by-e)]
               (sort (set/difference all-nss has-hdr))))]
       (mapv (fn [nsym]
               {:class :missing-ns-form
                :severity :error
                :kind :static
                :ns nsym
                :subject (str nsym)
                :branch br
                :message (str "Namespace " nsym " has persisted forms but no (ns " nsym " ...) header form. "
                              "Evaluate (ns " nsym " (:require ...)) so the file compiles cleanly on cold AOT builds.")})
             missing)))))

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

(defn- report-cache-key [db]
  (when (and db
             (not (instance? datahike.db.AsOfDB db))
             (not (instance? datahike.db.HistoricalDB db)))
    [(get-in db [:config :branch] :main)
     (count (:schema db))
     (attr-sig db :seon.code.form/hash)
     (attr-sig db :seon.code.var/name)
     (attr-sig db :seon.schema/key)
     (attr-sig db :seon.test/digest)
     (attr-sig db :seon.health.fault/id)
     (attr-sig db :seon.health.fault/resolved?)]))

(defn report
"Computes the complete `:seon.health/report` map from `db` (pure read, tagged with `:seon/schema`).
 `:ok?` is true whenever zero `:error`-severity problems exist (`:warning` advisories surface
 without blocking unrelated merges). Content-keyed on code/schema/test/fault signatures so UI
 interactions and chat commits hit in < 1 ms while keeping `datahike.reads` 100% sound."
[db]
(let [ck (report-cache-key db)
      compute (fn []
                (let [vmap (src-var-map db)
                      probs (vec (concat (blocking-in-flow-procs db vmap)
                                         (state-outside-flow db vmap)
                                         (used-before-defined db)
                                         (missing-ns-forms db)
                                         (unschemed-attributes db)
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
     (vec (concat schema-errs new-blocks new-ubd new-mns (missing-contract-violations ours-db theirs-db touched-nss))))))

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