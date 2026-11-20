(ns seon.health.static
  "Static code-structure checkers for System Health — structural integrity rules (blocking ops in Flow procs, top-level mutable state, schema violations, used-before-defined, unschemed attrs, missing ns forms) computed purely from `:seon.code.*` facts."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.error :as me]
            [seon.schema :as schema]
            [seon.health.efficiency :as eff]
            [seon.store :as store]))

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
