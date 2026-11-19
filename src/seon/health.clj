(ns seon.health
  "System Health — pure queries over Datahike facts (never polled, never duplicated):
   1. Derived structural rules (`O(|Δ|)` over `:seon.code.var/*`, `:seon.code.form/*`, `:seon.schema/*`):
      - `:blocking-in-flow-proc`: detects `<!!`, `>!!`, `alts!!`, `Thread/sleep` reachable from Flow procs
        (unless explicitly tagged `^:seon.flow/edge`). Prevents UI & Flow freezes before merge!
      - `:state-outside-flow`: detects top-level `atom`/`volatile!`/`promise`/`agent` defs not tagged `^:seon.flow/edge`.
      - `:retyped-attribute` & `:schema-breaks-stored-data`: rejects schema edits that conflict with
        installed Datahike attribute types or existing stored datoms, enforcing accretive migrations.
      - `:used-before-defined`: detects same-namespace forward references without `(declare ...)` that
        would break a cold AOT build.
   2. Global failing tests (`:failing-test`): reads `:seon.test.run/*` where `:pass? false` across `:main`
      and active branches — zero passing tests ever clutter context!
   3. Recorded Flow faults (`:proc-fault`): open `:seon.health.fault/*` entities emitted by `::flow/error`.
   Returns plain schema'd `:seon.health/report` data rendered identically on Web (Datastar) and in `@seon.ctx/ctx`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [malli.error :as me]
            [malli.core :as m]
            [seon.schema :as schema]
            [seon.schema.datahike :as sdh]))

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

(defn- src-var-map [db]
  (->> (d/datoms db :aevt :seon.code.var/name)
       (keep (fn [dt]
               (let [v (d/pull db '[:seon.code.var/name :seon.code.var/ns :seon.code.var/refs
                                    :seon.code.var/arglists :seon.code.var/test? :seon.code.var/flow-edge?]
                               (:e dt))
                     n (:seon.code.var/ns v)]
                 (when (and n (not (:seon.code.var/test? v))
                            (not (str/ends-with? (name n) "-test")))
                   [(:seon.code.var/name v) v]))))
       (into {})))

(defn blocking-in-flow-procs
  "Flags any Flow proc (`*-proc` var in `src/`) whose transitive call graph reaches blocking ops
   (`<!!`, `>!!`, `alts!!`, `Thread/sleep`) without an explicit `^:seon.flow/edge` annotation."
  [db]
  (let [vmap (src-var-map db)
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
                                        :subject (str proc-sym)
                                        :var cur
                                        :branch (get-in db [:config :branch] :main)
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
      (sort-by key proc-vars)))))

(defn state-outside-flow
  "Flags top-level `def`/`defonce` vars in `src/` holding an `atom`, `volatile!`, `promise`, or `agent`
   without an explicit `^:seon.flow/edge` annotation."
  [db]
  (let [vmap (src-var-map db)]
    (->> vmap
         (keep (fn [[sym v]]
                 (when (and (empty? (:seon.code.var/arglists v))
                            (not (:seon.code.var/flow-edge? v)))
                   (let [bad (filter top-level-state-syms (:seon.code.var/refs v #{}))]
                     (when (seq bad)
                       {:class :state-outside-flow
                        :severity :warning
                        :subject (str sym)
                        :var sym
                        :branch (get-in db [:config :branch] :main)
                        :message (str "Top-level mutable state (" (str/join ", " (sort bad))
                                      ") outside Flow in " sym ". Move into Flow proc state or mark ^:seon.flow/edge.")})))))
         (sort-by :subject)
         vec)))

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
          ;; Reverse reach: find all attributes whose schema form transitively references any changed key
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
                              :subject (str k)
                              :branch (get-in ours-db [:config :branch] :main)
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
                                      :subject (str attr)
                                      :branch (get-in ours-db [:config :branch] :main)
                                      :message (str "Schema change to " attr " rejects existing stored value "
                                                    (pr-str (:v dt)) " on entity " (:e dt) ": "
                                                    (pr-str (me/humanize (m/explain schema (:v dt) {:registry registry})))
                                                    ". Migrate existing datoms first or introduce a new schema key.")})))
                           (take 10))))))
               vec)]
      (into retyped refused))))

(defn used-before-defined
  "Flags forms in `src/` (or specific `ns-syms`) that reference a same-namespace var whose only
   definition appears later in the file without an earlier `(declare ...)`."
  ([db] (used-before-defined db nil))
  ([db ns-syms]
   (let [nss (or ns-syms
                 (->> (d/datoms db :aevt :seon.code.ns/name)
                      (map :v)
                      (remove #(str/ends-with? (name %) "-test"))))]
     (vec
      (mapcat
       (fn [nsym]
         (let [forms (->> (d/datoms db :avet :seon.code.form/ns nsym)
                          (map #(d/pull db '[:seon.code.form/key :seon.code.form/order
                                             :seon.code.form/src :seon.code.form/deps] (:e %)))
                          (sort-by :seon.code.form/order))
               def-orders (into {}
                                (keep (fn [f]
                                        (let [k (:seon.code.form/key f)]
                                          (when-not (str/includes? k "#")
                                            [(symbol k) (:seon.code.form/order f)]))))
                                forms)
               decl-orders (reduce
                            (fn [acc f]
                              (let [s (str/triml (:seon.code.form/src f ""))]
                                (if (str/starts-with? s "(declare ")
                                  (let [syms (rest (try (read-string s) (catch Throwable _ nil)))]
                                    (reduce #(assoc %1 (symbol (name nsym) (name %2)) (:seon.code.form/order f))
                                            acc (filter symbol? syms)))
                                  acc)))
                            {}
                            forms)]
           (keep (fn [f]
                   (let [ord (:seon.code.form/order f)
                         bad (->> (:seon.code.form/deps f)
                                  (filter (fn [dep]
                                            (when-let [dord (get def-orders dep)]
                                              (and (> dord ord)
                                                   (let [decl (get decl-orders dep)]
                                                     (or (nil? decl) (> decl ord)))))))
                                  sort)]
                     (when (seq bad)
                       {:class :used-before-defined
                        :severity :error
                        :subject (:seon.code.form/key f)
                        :branch (get-in db [:config :branch] :main)
                        :message (str "Form " (:seon.code.form/key f) " references " (str/join ", " bad)
                                      " before definition (breaks cold AOT builds). Reorder or add (declare ...).")})))
                 forms)))
       nss)))))

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
                      :when (:seon.test/var v)]
                  {:class :failing-test
                   :severity :error
                   :subject (str (:seon.test/var v))
                   :var (:seon.test/var v)
                   :branch br
                   :message (str "[" (name (or (:seon.test/kind v) :deftest)) " on " br
                                 (when (seq ags) (str ", agents " (pr-str ags)))
                                 "] " (:seon.test/report v))}))))
           vec))))

(defn open-faults
  "Returns all unresolved recorded Flow/runtime faults from Datahike."
  [db]
  (if-not (contains? (:schema db) :seon.health.fault/id)
    []
    (->> (d/datoms db :aevt :seon.health.fault/id)
         (map #(d/pull db '[:seon.health.fault/id :seon.health.fault/class :seon.health.fault/proc
                            :seon.health.fault/subject :seon.health.fault/message
                            :seon.health.fault/branch :seon.health.fault/resolved?] (:e %)))
         (remove :seon.health.fault/resolved?)
         (mapv (fn [f]
                 {:class (or (:seon.health.fault/class f) :proc-fault)
                  :severity :error
                  :subject (or (:seon.health.fault/subject f) (str (:seon.health.fault/proc f :system)))
                  :branch (or (:seon.health.fault/branch f) :main)
                  :message (:seon.health.fault/message f "")})))))

(defn unschemed-attributes
  "Flags any `:seon.*/*` attribute installed in Datahike (`(:schema db)`) that lacks a registered
   Malli `:seon.schema/key` entity in `db` — guaranteeing 100% Malli-derived schema discipline."
  [db]
  (let [registered (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))]
    (->> (keys (:schema db))
         (filter #(and (qualified-keyword? %)
                       (str/starts-with? (namespace %) "seon.")
                       (not (contains? registered %))))
         sort
         (mapv (fn [attr]
                 {:class :unschemed-attribute
                  :severity :error
                  :subject (str attr)
                  :branch (get-in db [:config :branch] :main)
                  :message (str "Datahike attribute " attr " has no registered Malli :seon.schema/key. "
                                "Register it via Malli schemas instead of installing raw Datahike attributes.")})))))

(defn report
  "Computes the complete `:seon.health/report` map from `db` (pure read, tagged with `:seon/schema`)."
  [db]
  (let [probs (vec (concat (blocking-in-flow-procs db)
                           (state-outside-flow db)
                           (used-before-defined db)
                           (unschemed-attributes db)
                           (failing-tests db)
                           (open-faults db)))
        counts (frequencies (map :class probs))
        errors? (boolean (some #(= :error (:severity %)) probs))]
    (with-meta
      {:ok? (not errors?)
       :branch (get-in db [:config :branch] :main)
       :counts counts
       :problems probs}
      {:seon/schema :seon.health/report})))

(defn caused-by-branch
  "Returns only the structural/schema health errors *introduced* by `theirs-db` relative to `ours-db`
   (including `:retyped-attribute`, `:schema-breaks-stored-data`, new `:blocking-in-flow-proc`,
   and new `:used-before-defined`). Used to gate merges cleanly without blaming a branch for pre-existing warnings."
  [ours-db theirs-db changed-schema-forms]
  (let [schema-errs (schema-violations ours-db changed-schema-forms)
        base-blocks (into #{} (map (juxt :class :subject :var)) (blocking-in-flow-procs ours-db))
        new-blocks (->> (blocking-in-flow-procs theirs-db)
                        (remove #(contains? base-blocks ((juxt :class :subject :var) %))))
        base-ubd (into #{} (map :subject) (used-before-defined ours-db))
        new-ubd (->> (used-before-defined theirs-db)
                     (remove #(contains? base-ubd (:subject %))))]
    (vec (concat schema-errs new-blocks new-ubd))))
