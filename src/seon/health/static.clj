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

(defn form-meta-snapshot
  "Scans inline `:seon.code.form/{ns,key,order,hash}` attributes ONCE using Java hash tables,
   producing `{:src-forms-by-ns :all-nss :has-hdr-nss :form-order-sig :form-hash-sig}`."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (let [schema-map (:schema db)]
    (if-not (and db (contains? schema-map :seon.code.form/key) (contains? schema-map :seon.code.form/ns))
      {:src-forms-by-ns {} :all-nss #{} :has-hdr-nss #{} :form-order-sig [0 0] :form-hash-sig [0 0]}
      (let [eid->ns (java.util.HashMap. 1400)
            eid->key (java.util.HashMap. 1400)
            eid->ord (java.util.HashMap. 1400)
            eid->hash (java.util.HashMap. 1400)
            ord-cnt (volatile! 0) ord-mtx (volatile! 0)
            hash-cnt (volatile! 0) hash-mtx (volatile! 0)]
        (doseq [dt (d/datoms db :aevt :seon.code.form/ns)]
          (.put eid->ns (:e dt) (:v dt)))
        (doseq [dt (d/datoms db :aevt :seon.code.form/key)]
          (.put eid->key (:e dt) (:v dt)))
        (when (contains? schema-map :seon.code.form/order)
          (doseq [dt (d/datoms db :aevt :seon.code.form/order)]
            (.put eid->ord (:e dt) (:v dt))
            (vswap! ord-cnt inc)
            (let [t (long (:tx dt))] (when (> t @ord-mtx) (vreset! ord-mtx t)))))
        (when (contains? schema-map :seon.code.form/hash)
          (doseq [dt (d/datoms db :aevt :seon.code.form/hash)]
            (.put eid->hash (:e dt) (:v dt))
            (vswap! hash-cnt inc)
            (let [t (long (:tx dt))] (when (> t @hash-mtx) (vreset! hash-mtx t)))))
        (let [all-nss (transient #{})
              has-hdr (transient #{})
              by-ns (java.util.HashMap. 160)
              it (.iterator (.entrySet eid->ns))]
          (loop []
            (when (.hasNext it)
              (let [entry ^java.util.Map$Entry (.next it)
                    e (.getKey entry)
                    nsym (.getValue entry)]
                (when nsym
                  (conj! all-nss nsym)
                  (let [k (.get eid->key e)]
                    (when (and (string? k) (str/ends-with? k "#ns"))
                      (conj! has-hdr nsym))
                    (when-not (str/ends-with? (name nsym) "-test")
                      (let [arr (or (.get by-ns nsym)
                                    (let [a (java.util.ArrayList. 16)] (.put by-ns nsym a) a))]
                        (.add ^java.util.ArrayList arr
                              {:e e :key k :order (or (.get eid->ord e) 0) :hash (.get eid->hash e)})))))
                (recur))))
          (let [out (transient {})
                it2 (.iterator (.entrySet by-ns))]
            (loop []
              (when (.hasNext it2)
                (let [entry ^java.util.Map$Entry (.next it2)]
                  (assoc! out (.getKey entry) (vec ^java.util.ArrayList (.getValue entry)))
                  (recur))))
            {:src-forms-by-ns (persistent! out)
             :all-nss (persistent! all-nss)
             :has-hdr-nss (persistent! has-hdr)
             :form-order-sig [@ord-cnt @ord-mtx]
             :form-hash-sig [@hash-cnt @hash-mtx]}))))))

(defn missing-ns-forms
  "Flags any namespace in `:seon.code.form/ns` (or `ns-syms`) that defines forms without a `<ns>#ns`
   `(ns ...)` header. Accepts optional precomputed `form-snap` to avoid duplicate scans."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe [:sequential :symbol]]] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe [:sequential :symbol]] [:maybe :map]] [:vector :map]]]}
  ([db] (missing-ns-forms db nil nil))
  ([db ns-syms] (missing-ns-forms db ns-syms nil))
  ([db ns-syms form-snap]
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
             (let [snap (or form-snap (form-meta-snapshot db))]
               (sort (set/difference (:all-nss snap) (:has-hdr-nss snap)))))]
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

(defonce ^:private ^:seon.flow/edge declare-cache (store/lru-cache 512))

(defn- parse-declare-syms [s]
  (let [tr (str/triml s)]
    (when (str/starts-with? tr "(declare ")
      (let [f (try (eda/parse-string tr {:read-cond :allow :features #{:clj} :readers (fn [_] identity)})
                   (catch clojure.lang.ExceptionInfo _ nil))]
        (when (seq? f)
          (vec (filter symbol? (rest f))))))))

(defn- cached-declare-syms [db e h]
  (if h
    (let [cached (get @declare-cache h ::miss)]
      (if (not= ::miss cached)
        cached
        (let [s (str (:v (first (d/datoms db :eavt e :seon.code.form/src))))]
          (store/lru-get! declare-cache 512 h #(parse-declare-syms s)))))
    (parse-declare-syms (str (:v (first (d/datoms db :eavt e :seon.code.form/src)))))))

(defn- ns-used-before-defined [db br nsym raw-forms ^java.util.HashMap deps-hm]
  (let [forms (sort-by :order raw-forms)
        def-orders (into {}
                         (keep (fn [{:keys [key order]}]
                                 (when (and key (not (str/includes? key "#")))
                                   [(symbol key) order])))
                         forms)
        candidates (keep (fn [{:keys [e key order deps]}]
                           (let [ds (or deps (when deps-hm (.get deps-hm e)))
                                 fwd (when (seq ds)
                                       (->> ds
                                            (filter (fn [dep]
                                                      (when-let [dord (get def-orders dep)]
                                                        (> dord order))))
                                            sort))]
                             (when (seq fwd)
                               {:key key :order order :fwd fwd})))
                         forms)
        expr-forms (filter #(and (:key %) (str/includes? (:key %) "#expr#")) forms)
        decls (reduce
               (fn [acc {:keys [e key order hash]}]
                 (if-let [syms (seq (cached-declare-syms db e hash))]
                   (reduce (fn [m sym]
                             (let [qsym (symbol (name nsym) (name sym))]
                               (-> m
                                   (update :orders assoc qsym order)
                                   (update :entries conj {:key key :sym qsym}))))
                           acc syms)
                   acc))
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
                               {:class :used-before-defined
                                :severity :error
                                :kind :static
                                :ns nsym
                                :subject key
                                :branch br
                                :fix {:fn 'seon.code.edit/reorder-ns-forms! :args [nsym]}
                                :message (str "Form " key " references " (str/join ", " bad)
                                              " before definition (breaks cold AOT builds). Run (user/autofix-health!) to topologically reorder, or add (declare ...).")})))
                         candidates))]
    (concat dangling fwd-errs)))

(defn used-before-defined
  "Flags forms in `src/` (or specific `ns-syms`) that reference a same-namespace var whose only
   definition appears later in the file without an earlier `(declare ...)`.
   Accepts optional precomputed `form-snap` to avoid duplicate AEVT scans."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe [:sequential :symbol]]] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe [:sequential :symbol]] [:maybe :map]] [:vector :map]]]}
  ([db] (used-before-defined db nil nil))
  ([db ns-syms] (used-before-defined db ns-syms nil))
  ([db ns-syms form-snap]
   (if-not (and db (contains? (:schema db) :seon.code.form/key))
     []
     (let [br (get-in db [:config :branch] :main)]
       (if ns-syms
         (let [forms-by-ns
               (into {}
                     (for [nsym ns-syms
                           :when (and nsym (not (str/ends-with? (name nsym) "-test")))
                           :let [fs (mapv (fn [dt]
                                            (let [e (:e dt)
                                                  p (d/pull db '[:seon.code.form/key :seon.code.form/order
                                                                 :seon.code.form/deps :seon.code.form/hash] e)]
                                              {:e e
                                               :key (:seon.code.form/key p)
                                               :order (or (:seon.code.form/order p) 0)
                                               :hash (:seon.code.form/hash p)
                                               :deps (set (:seon.code.form/deps p))}))
                                          (d/datoms db :avet :seon.code.form/ns nsym))]
                           :when (seq fs)]
                       [nsym fs]))]
           (vec (mapcat (fn [[nsym raw-forms]] (ns-used-before-defined db br nsym raw-forms nil))
                        (sort-by key forms-by-ns))))
         (let [snap (or form-snap (form-meta-snapshot db))
               deps-hm (java.util.HashMap. 1400)]
           (when (contains? (:schema db) :seon.code.form/deps)
             (doseq [dt (d/datoms db :aevt :seon.code.form/deps)]
               (let [e (:e dt)]
                 (.put deps-hm e (conj (or (.get deps-hm e) #{}) (:v dt))))))
           (vec (mapcat (fn [[nsym raw-forms]] (ns-used-before-defined db br nsym raw-forms deps-hm))
                        (sort-by key (:src-forms-by-ns snap))))))))))

(def ^:private broad-catch-classes #{"*" "Throwable" "java.lang.Throwable" "Exception" "java.lang.Exception"})

(defn silent-catch-warnings
  "Uses `seon.code.analyze`'s compiler-exact `:seon.code.var/catches` (when present) to flag
   non-`^:seon.flow/edge` `src/` vars with broad catch-all clauses answering `:constant`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe :symbol]] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe :symbol] [:maybe :map]] [:vector :map]]]}
  ([db] (silent-catch-warnings db nil nil))
  ([db scope] (silent-catch-warnings db scope nil))
  ([db scope var-snap]
   (let [catches-rows
         (cond
           var-snap (:var-catches var-snap)
           (and db (contains? (:schema db) :seon.code.var/catches)
                (first (d/datoms db :aevt :seon.code.var/catches)))
           (:var-catches (var-snapshot db {:schemas? false :catches? true}))
           :else nil)]
     (if (empty? catches-rows)
       []
       (let [br (get-in db [:config :branch] :main)
             scope-s (when scope (name scope))]
         (->> catches-rows
              (keep (fn [[_ vsym nsym test? edge? clauses]]
                      (when (and vsym nsym (not test?) (not edge?)
                                 (or (nil? scope-s)
                                     (= (name nsym) scope-s)
                                     (str/starts-with? (name nsym) (str scope-s "."))))
                        (when-let [hit (some (fn [c]
                                               (when (and (= :constant (:answers c))
                                                          (contains? broad-catch-classes (:class c)))
                                                 c))
                                             clauses)]
                          {:class :silent-catch-all
                           :severity :warning
                           :kind :static
                           :ns nsym
                           :var vsym
                           :subject (str vsym)
                           :branch br
                           :message (str "Silent catch-all in " vsym " (catch " (:class hit)
                                         " -> :constant). Surface typed error data or mark ^:seon.flow/edge.")}))))
              (sort-by :subject)
              vec))))))