(ns seon.health.static
  "Static code-structure checkers for System Health — structural integrity rules (blocking ops in Flow procs, top-level mutable state, schema violations, used-before-defined, unschemed attrs, missing ns forms) computed purely from `:seon.code.*` facts."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.error :as me]
            [seon.schema :as schema]
            [seon.store :as store]
            [edamame.core :as eda]))

(def blocking-syms
  #{'clojure.core.async/<!!
    'clojure.core.async/>!!
    'clojure.core.async/alts!!
    'Thread/sleep})

(def top-level-state-syms
  #{'clojure.core/atom
    'clojure.core/volatile!
    'clojure.core/promise
    'clojure.core/agent})

(defn src-var-map
  "Builds `{var-sym {:ns :refs :arglists :flow-edge?}}` for all non-test vars in `db` using
   linear AEVT scans (zero `d/pull` overhead)."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (:vmap (var-snapshot db nil)))

(defn blocking-in-flow-procs
  "Flags any Flow proc (`*-proc` var in `src/`) whose transitive call graph reaches blocking ops
   (`<!!`, `>!!`, `alts!!`, `Thread/sleep`) without an explicit `^:seon.flow/edge` annotation."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :map]] [:=> [:cat :seon.host/db :map] [:vector :map]]]}
  ([db] (blocking-in-flow-procs db (src-var-map db)))
  ([db vmap]
   (let [br (get-in db [:config :branch] :main)
         proc-vars (sort-by key
                            (filter (fn [[sym v]]
                                      (and (str/ends-with? (name sym) "-proc")
                                           (not (:seon.code.var/flow-edge? v))))
                                    vmap))
         hits (transient [])]
     (doseq [[proc-sym _] proc-vars]
       (let [queue (java.util.ArrayDeque.)
             visited (java.util.HashSet.)]
         (.add queue [proc-sym [proc-sym]])
         (.add visited proc-sym)
         (loop []
           (when-not (.isEmpty queue)
             (let [[cur path] (.removeFirst queue)
                   cur-v (get vmap cur)
                   refs (:seon.code.var/refs cur-v #{})]
               (when-not (:seon.code.var/flow-edge? cur-v)
                 (let [direct-blocks (filter blocking-syms refs)]
                   (when (seq direct-blocks)
                     (conj! hits
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
                                           ". Use Flow out-ports or mark an edge var ^:seon.flow/edge.")}))
                   (doseq [n (sort (filter #(and (contains? vmap %) (not (.contains visited %))) refs))]
                     (.add visited n)
                     (.addLast queue [n (conj path n)]))))
               (recur))))))
     (persistent! hits))))

(defn state-outside-flow
  "Flags top-level `def`/`defonce` vars in `src/` holding an `atom`, `volatile!`, `promise`, or `agent`
   without an explicit `^:seon.flow/edge` annotation."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :map]] [:=> [:cat :seon.host/db :map] [:vector :map]]]}
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
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :map]]}
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
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :map]] [:=> [:cat :seon.host/db [:maybe [:sequential :symbol]]] [:vector :map]]]}
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
             (let [expr-forms (filter #(and (:key %) (str/includes? (:key %) "#expr#")) forms)
                 decls (reduce
                        (fn [acc {:keys [e key order]}]
                          (let [s (str/triml (str (:v (first (d/datoms db :eavt e :seon.code.form/src)))))]
                            (if (str/starts-with? s "(declare ")
                              (let [syms (filter symbol? (rest (try (read-string s) (catch Throwable _ nil))))]
                                (reduce (fn [m sym]
                                          (let [qsym (symbol (name nsym) (name sym))]
                                            (-> m
                                                (update :orders assoc qsym order)
                                                (update :entries conj {:key key :sym qsym}))))
                                        acc syms))
                              acc)))
                        {:orders {} :entries []}
                        expr-forms)
                 decl-orders (:orders decls)
                 dangling (keep (fn [{:keys [key sym]}]
                                  (when-not (contains? def-orders sym)
                                    {:class :dangling-declare
                                     :severity :error
                                     :kind :static
                                     :ns nsym
                                     :subject key
                                     :branch br
                                     :message (str "Form " key " declares " sym " which is never defined in " nsym ". Remove dead declare or define it.")}))
                                (:entries decls))
                 fwd-errs (when (seq candidates)
                            (keep (fn [{:keys [key order fwd]}]
                                    (let [bad (filter (fn [dep]
                                                        (let [decl (get decl-orders dep)]
                                                          (or (nil? decl) (> decl order))))
                                                      fwd)]
                                      (when (seq bad)
                                        {:class :used-before-defined,
                                        :severity :error,
                                        :kind :static,
                                        :ns nsym,
                                        :subject key,
                                        :branch br,
                                        :fix {:fn 'seon.code.edit/reorder-ns-forms!, :args [nsym]},
                                        :message
                                        (str
                                          "Form "
                                          key
                                          " references "
                                          (str/join ", " bad)
                                          " before definition (breaks cold AOT builds). Run (user/autofix-health!) to topologically reorder, or add (declare ...).")})))                                    candidates))]
             (concat dangling fwd-errs))))
         (sort-by key forms-by-ns)))))))

(defn unschemed-attributes
  "Flags any `:seon.*/*` attribute installed in Datahike (`(:schema db)`) that lacks a registered
   Malli `:seon.schema/key` entity in `db` — guaranteeing 100% Malli-derived schema discipline."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
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
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :map]] [:=> [:cat :seon.host/db [:maybe [:sequential :symbol]]] [:vector :map]]]}
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

(defn var-snapshot
  "Builds `{:vmap :all-var-syms :eid->name :eid->ns :eid->refs :edge-vars :var-schemas :var-catches :var-name-sig}`
   in a single pass over `:seon.code.var/*` AEVT indexes using Java hash tables (zero `d/pull`s)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] :map]
                  [:=> [:cat :seon.host/db [:maybe :map]] :map]]}
  ([db] (var-snapshot db nil))
  ([db {:keys [schemas? catches?]}]
   (let [schema-map (:schema db)]
     (if-not (and db (contains? schema-map :seon.code.var/name))
       {:vmap {} :all-var-syms #{} :eid->name (java.util.HashMap.) :eid->ns (java.util.HashMap.)
        :eid->refs (java.util.HashMap.) :edge-vars #{} :var-schemas [] :var-catches [] :var-name-sig [0 0]}
       (let [eid->name (java.util.HashMap. 1200)
             eid->ns (java.util.HashMap. 1200)
             eid->args (java.util.HashMap. 1200)
             eid->refs (java.util.HashMap. 1200)
             test-eids (java.util.HashSet. 256)
             edge-eids (java.util.HashSet. 64)
             all-syms (transient #{})
             cnt (volatile! 0)
             mtx (volatile! 0)]
         (doseq [dt (d/datoms db :aevt :seon.code.var/name)]
           (let [e (:e dt)
                 v (:v dt)
                 tx (long (:tx dt))]
             (.put eid->name e v)
             (conj! all-syms v)
             (vswap! cnt inc)
             (when (> tx @mtx) (vreset! mtx tx))))
         (when (contains? schema-map :seon.code.var/ns)
           (doseq [dt (d/datoms db :aevt :seon.code.var/ns)]
             (.put eid->ns (:e dt) (:v dt))))
         (when (contains? schema-map :seon.code.var/test?)
           (doseq [dt (d/datoms db :aevt :seon.code.var/test?)]
             (when (:v dt) (.add test-eids (:e dt)))))
         (when (contains? schema-map :seon.code.var/flow-edge?)
           (doseq [dt (d/datoms db :aevt :seon.code.var/flow-edge?)]
             (when (:v dt) (.add edge-eids (:e dt)))))
         (when (contains? schema-map :seon.code.var/arglists)
           (doseq [dt (d/datoms db :aevt :seon.code.var/arglists)]
             (.put eid->args (:e dt) (:v dt))))
         (when (contains? schema-map :seon.code.var/refs)
           (doseq [dt (d/datoms db :aevt :seon.code.var/refs)]
             (let [e (:e dt)]
               (.put eid->refs e (conj (or (.get eid->refs e) #{}) (:v dt))))))
         (let [vmap (transient {})
               edge-vars (transient #{})
               it (.iterator (.entrySet eid->name))]
           (loop []
             (when (.hasNext it)
               (let [entry ^java.util.Map$Entry (.next it)
                     e (.getKey entry)
                     sym (.getValue entry)
                     n (.get eid->ns e)
                     edge? (.contains edge-eids e)]
                 (when edge? (conj! edge-vars sym))
                 (when (and n (not (.contains test-eids e)) (not (str/ends-with? (name n) "-test")))
                   (assoc! vmap sym
                           {:seon.code.var/name sym
                            :seon.code.var/ns n
                            :seon.code.var/refs (or (.get eid->refs e) #{})
                            :seon.code.var/arglists (.get eid->args e)
                            :seon.code.var/flow-edge? edge?}))
                 (recur))))
           (let [var-schemas
                 (if (and schemas? (contains? schema-map :seon.code.var/schema))
                   (let [out (transient [])]
                     (doseq [dt (d/datoms db :aevt :seon.code.var/schema)]
                       (let [e (:e dt)]
                         (conj! out [e (.get eid->name e) (.get eid->ns e) (.contains edge-eids e) (:v dt)])))
                     (persistent! out))
                   [])
                 var-catches
                 (if (and catches?
                          (contains? schema-map :seon.code.var/catches)
                          (first (d/datoms db :aevt :seon.code.var/catches)))
                   (let [out (transient [])]
                     (doseq [dt (d/datoms db :aevt :seon.code.var/catches)]
                       (let [e (:e dt)
                             n (.get eid->ns e)
                             test? (or (.contains test-eids e)
                                       (boolean (when n (str/ends-with? (name n) "-test"))))]
                         (conj! out [e (.get eid->name e) n test? (.contains edge-eids e) (:v dt)])))
                     (persistent! out))
                   [])]
             {:vmap (persistent! vmap)
              :all-var-syms (persistent! all-syms)
              :eid->name eid->name
              :eid->ns eid->ns
              :eid->refs eid->refs
              :edge-vars (persistent! edge-vars)
              :var-schemas var-schemas
              :var-catches var-catches
              :var-name-sig [@cnt @mtx]})))))))