(ns seon.schema
  "First-class Malli schema registry, derived Datahike attributes, `seed = 42` generative checks, and instrumentation."
  (:require [clojure.core.cache :as cache]
            [clojure.core.cache.wrapped :as cw]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.index.interface :as di]
            [datahike.reads :as reads]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [malli.registry :as mr]
            [seon.schema.datahike :as sdh]
            [seon.schema.index :as six]))

(def checkout-root six/checkout-root)

(defn- datahike-db? [x]
  (or (instance? datahike.db.DB x)
      (instance? datahike.db.AsOfDB x)
      (instance? datahike.db.HistoricalDB x)))

(def ^:private host-predicates
  {:seon.host/fn        (m/-simple-schema {:type :seon.host/fn :pred fn? :type-properties {:error/message "must be a function"}})
   :seon.host/var       (m/-simple-schema {:type :seon.host/var :pred var? :type-properties {:error/message "must be a Clojure Var"}})
   :seon.host/ns        (m/-simple-schema {:type :seon.host/ns :pred #(instance? clojure.lang.Namespace %) :type-properties {:error/message "must be a Clojure Namespace"}})
   :seon.host/atom      (m/-simple-schema {:type :seon.host/atom :pred #(instance? clojure.lang.Atom %) :type-properties {:error/message "must be an Atom"}})
   :seon.host/deref     (m/-simple-schema {:type :seon.host/deref :pred #(instance? clojure.lang.IDeref %) :type-properties {:error/message "must be derefable"}})
   :seon.host/chan      (m/-simple-schema {:type :seon.host/chan :pred #(instance? clojure.core.async.impl.channels.ManyToManyChannel %) :type-properties {:error/message "must be a core.async channel"}})
   :seon.host/db        (m/-simple-schema {:type :seon.host/db :pred datahike-db? :type-properties {:error/message "must be a Datahike DB value"}})
   :seon.host/conn      (m/-simple-schema {:type :seon.host/conn :pred #(instance? datahike.connector.Connection %) :type-properties {:error/message "must be a Datahike Connection"}})
   :seon.host/throwable (m/-simple-schema {:type :seon.host/throwable :pred #(instance? Throwable %) :type-properties {:error/message "must be a Throwable"}})
   :char                (m/-simple-schema {:type :char :pred char? :type-properties {:error/message "must be a Character"}})})

(defn generatable-function-schema? [& args] (apply six/generatable-function-schema? args))

(defn multi-segment-schema-key?
  "Enforces multi-segment schema namespaces."
  {:malli/schema [:=> [:cat :any] :boolean] :cost/ticks 20000}
  [k]
  (six/multi-segment-schema-key? k))

(defn schema-owner-path
  "Maps any global schema key to `resources/seon/schemas.edn`."
  {:malli/schema [:=> [:cat :qualified-keyword] :string] :cost/ticks 100000 :cost/bytes 131072}
  [_k]
  "resources/seon/schemas.edn")

(defn load-disk-schema-forms [& args] (apply six/load-disk-schema-forms args))

(def ^:seon.flow/edge disk-base-forms
  (let [st (atom {:mtime -1 :forms nil})]
    (reify clojure.lang.IDeref
      (deref [_]
        (let [f (io/file checkout-root "resources/seon/schemas.edn")
              mt (if (.isFile f) (.lastModified f) -2)
              cur @st]
          (if (and (= mt (:mtime cur)) (:forms cur))
            (:forms cur)
            (let [forms (load-disk-schema-forms)]
              (reset! st {:mtime mt :forms forms})
              forms)))))))

(defn db-schema-forms
  "Reads all `:seon.schema/key` entities from `db` into `{:seon.schema/key form}` via 2 linear AEVT scans."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :map] :cost/ticks 10000000}
  [db]
  (if (or (nil? db) (not (contains? (:schema db) :seon.schema/key)))
    {}
    (let [keys-by-e (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.schema/key))]
      (if (empty? keys-by-e)
        {}
        (reduce (fn [acc dt]
                  (if-let [k (get keys-by-e (:e dt))]
                    (let [v (:v dt)]
                      (assoc acc k (if (string? v) (edn/read-string v) v)))
                    acc))
                {}
                (d/datoms db :aevt :seon.schema/form))))))

(defn compile-registry
  "Compiles `{k form}` into a composite Malli `Registry` with every named schema compiled once."
  {:malli/schema [:function [:=> [:cat :map] :any]] :cost/ticks 50000000}
  [forms]
  (let [compiled (volatile! {})
        reg (volatile! nil)
        lazy (reify mr/Registry
               (-schema [_ k]
                 (or (get @compiled k)
                     (when-let [f (get forms k)]
                       (let [s (m/schema f {:registry @reg})]
                         (vswap! compiled assoc k s)
                         s))))
               (-schemas [_] @compiled))]
    (vreset! reg (mr/composite-registry (m/default-schemas) host-predicates lazy))
    (doseq [k (keys forms)] (mr/-schema lazy k))
    (mr/composite-registry (m/default-schemas) host-predicates (mr/simple-registry @compiled))))

(defonce ^:private ^:seon.flow/edge compiled-registry-cache
  (cw/lru-cache-factory {} :threshold 32))

(defn- cached-compiled-registry [forms]
  (when-not (satisfies? cache/CacheProtocol @compiled-registry-cache)
    (reset! compiled-registry-cache (cache/lru-cache-factory {} :threshold 32)))
  (cw/lookup-or-miss compiled-registry-cache forms
    (fn [_]
      (let [reg (compile-registry forms)
            decls (sdh/declarations reg)]
        {:forms forms
         :registry reg
         :reg-sig [(System/identityHashCode reg) (count forms)]
         :declarations decls
         :decl-by-id (into {} (map (juxt :db/ident identity)) decls)
         :merge-meta (sdh/merge-metadata reg)
         :entity-index (six/build-entity-index forms)
         :schema-ast-edges (into {} (map (fn [[k f]] [k (six/extract-schema-ast-edges f reg)])) forms)
         :attr-validators (java.util.HashMap.)}))))

(defn attr-validator-for
  "Returns `{:schema :valid?}` for a Datahike attribute `attr` from `reg-info` (O(1) cached)."
  {:malli/schema [:=> [:cat :map :any] [:maybe :map]]}
  [{:keys [registry merge-meta attr-validators]} attr]
  (let [compute (fn []
                  (when (and registry (satisfies? mr/Registry registry))
                    (when-let [s (mr/schema registry attr)]
                      (if (contains? (:many-attrs merge-meta) attr)
                        (let [elem-s (if (= :set (m/type s)) (first (m/children s)) s)
                              tx-s (m/schema [:or elem-s [:sequential elem-s] [:set elem-s]] {:registry registry})]
                          {:schema tx-s :valid? (m/validator tx-s {:registry registry})})
                        {:schema s :valid? (m/validator s {:registry registry})}))))]
    (if (instance? java.util.Map attr-validators)
      (let [hit (locking attr-validators (.get ^java.util.Map attr-validators attr))]
        (cond
          (identical? hit ::none) nil
          (some? hit) hit
          :else (let [res (compute)]
                  (locking attr-validators (.put ^java.util.Map attr-validators attr (if (some? res) res ::none)))
                  res)))
      (compute))))

(defn schema-refs-in-form [& args] (apply six/schema-refs-in-form args))

(defn- safe-example [& args] (apply six/safe-example args))

(def ^:dynamic *bypass-contracts* false)

(def ^:dynamic *base-forms-override* nil)

(def ^:seon.flow/edge base-registry
  (let [fast (atom nil)]
    (reify clojure.lang.IDeref
      (deref [_]
        (let [forms (if (seq *base-forms-override*) *base-forms-override* @disk-base-forms)
              cur @fast]
          (if (and cur (identical? forms (:forms cur)))
            (:base cur)
            (let [b (cached-compiled-registry forms)]
              (reset! fast {:forms forms :base b})
              b)))))))

(defonce ^:private ^:seon.flow/edge db-registry-cache
  (cw/lru-cache-factory {} :threshold 32))

(defn- strip-schema-doc
{:malli/schema [:=> [:cat :any] :any], :cost/ticks 500000}
[x]
(cond
  (map? x)
  (let [m (dissoc x :seon.schema/doc)]
    (reduce-kv (fn [acc k v] (assoc acc k (strip-schema-doc v))) {} m))
  (vector? x)
  (let [v (mapv strip-schema-doc x)]
    (if (and (>= (count v) 2) (map? (nth v 1)) (empty? (nth v 1)))
      (into [(nth v 0)] (subvec v 2))
      v))
  :else x))

(defonce ^:private ^:seon.flow/edge stripped-base-cache (atom {:forms nil :stripped nil}))

(defn schema-tempid [& args] (apply six/schema-tempid args))

(defn extract-schema-ast-edges [& args] (apply six/extract-schema-ast-edges args))

(defn enrich-schema-entity-tx [& args] (apply six/enrich-schema-entity-tx args))

(defn stale-schema-edge-retracts [& args] (apply six/stale-schema-edge-retracts args))

(defn enrich-schema-entities-tx [& args] (apply six/enrich-schema-entities-tx args))

(defn- stress-skip-reason [db fn-sym m]
  (cond
    (or (:seon.flow/edge m)
        (when (and db
                   (contains? (:schema db) :seon.code.var/name)
                   (contains? (:schema db) :seon.code.var/flow-edge?))
          (:seon.code.var/flow-edge?
           (d/pull db (quote [:seon.code.var/flow-edge?]) [:seon.code.var/name fn-sym]))))
    :flow-edge
    (:seon.gen/skip m)
    :gen-skip))

(defn sync-dh-decls [& args] (apply six/sync-dh-decls args))

(defonce ^:private ^:seon.flow/edge db-registry-anchor (atom {}))

(defn- db-schema-unchanged-since?
  {:seon.flow/edge true}
  [db prev-cid cur-cid]
  (let [anc-fn (some-> (find-ns (quote seon.ctx.diff)) (ns-resolve (quote ancestor-within?)))
        anc-ok? (if anc-fn
                  (boolean (anc-fn db prev-cid cur-cid 16))
                  (let [parents (get-in db [:meta :datahike/parents])]
                    (and (set? parents) (= 1 (count parents)) (= prev-cid (first parents)))))]
    (if-not anc-ok?
      false
      (try
        (let [kws [:seon.schema/key :seon.schema/form :seon.schema/doc :seon.schema/examples]
              attrs (into (set kws) (keep #(get-in db [:ident-ref-map %])) kws)]
          (not-any? #(contains? attrs (:a %)) (d/changes db prev-cid cur-cid)))
        (catch Throwable _ false)))))

(defn registry-for-db
  "Returns `{:registry :forms :reg-sig :merge-meta :declarations :decl-by-id :entity-index}` with commit-anchored promotion."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] :map] [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] :map]], :seon.flow/edge true}
  ([db] (registry-for-db db nil))
  ([db extra-forms]
   (let [compute (fn []
                   (let [db-forms (db-schema-forms db)
                         base @base-registry
                         bforms (:forms base)]
                     (if (and (empty? db-forms) (empty? extra-forms))
                       base
                       (let [cur @stripped-base-cache
                             stripped-base (if (identical? (:forms cur) bforms)
                                             (:stripped cur)
                                             (let [s (reduce-kv (fn [acc k v] (assoc acc k (strip-schema-doc v))) {} bforms)]
                                               (reset! stripped-base-cache {:forms bforms :stripped s})
                                               s))
                             matches-base? (and (empty? extra-forms)
                                                (reduce-kv
                                                 (fn [_ k v]
                                                   (let [bv (get stripped-base k ::missing)]
                                                     (if (or (identical? bv ::missing)
                                                             (not= (if (identical? v bv) v (strip-schema-doc v)) bv))
                                                       (reduced false)
                                                       true)))
                                                 true
                                                 db-forms))]
                         (if matches-base?
                           base
                           (let [merged (merge bforms db-forms extra-forms)]
                             (if (= merged bforms)
                               base
                               (cached-compiled-registry merged))))))))
         live-db? (instance? datahike.db.DB db)
         asof-db? (instance? datahike.db.AsOfDB db)
         uncommitted? (boolean (and live-db? (seq (:datahike/uncommitted (meta db)))))
         cur-cid (when (and (not uncommitted?) (or live-db? asof-db?))
                   (try (d/commit-id (if asof-db? (:origin-db db) db)) (catch Throwable _ nil)))
         ov (if *base-forms-override* (System/identityHashCode *base-forms-override*) 0)
         ck (when (and (empty? extra-forms) cur-cid (not uncommitted?))
              (if live-db?
                [::db (get-in db [:config :store :id]) (get-in db [:config :branch]) (:max-tx db) cur-cid (count (:schema db)) ov]
                [::as-of (get-in (:origin-db db) [:config :store :id]) (get-in (:origin-db db) [:config :branch]) (:time-point db) cur-cid ov]))]
     (if-not ck
       (compute)
       (let [outer di/*reads*
             _ (when-not (satisfies? cache/CacheProtocol @db-registry-cache)
                 (reset! db-registry-cache (cache/lru-cache-factory {} :threshold 32)))
             hit (get @db-registry-cache ck ::miss)]
         (if (and (not (identical? hit ::miss))
                  (or (nil? outer) (contains? hit :reads)))
           (do (when (and outer (seq (:reads hit))) (swap! outer into (:reads hit)))
               (:val hit))
           (let [ak (when live-db? [(nth ck 1) (nth ck 2) ov])
                 mtx (when live-db? (:max-tx db))
                 scnt (when live-db? (nth ck 5))
                 anc (when ak (get @db-registry-anchor ak))
                 promoted (when (and anc (:cid anc) (not= cur-cid (:cid anc)) (= scnt (:schema-cnt anc))
                                     (< 0 (- (long mtx) (long (:max-tx anc))) 65)
                                     (or (nil? outer) (contains? anc :reads))
                                     (db-schema-unchanged-since? db (:cid anc) cur-cid))
                            (let [e (select-keys anc [:val :reads])]
                              (swap! db-registry-anchor #(if (>= (long mtx) (long (:max-tx (get % ak) 0)))
                                                           (assoc % ak (assoc anc :cid cur-cid :max-tx mtx))
                                                           %))
                              (swap! db-registry-cache cache/miss ck e)
                              e))]
             (if promoted
               (do (when (and outer (seq (:reads promoted))) (swap! outer into (:reads promoted)))
                   (:val promoted))
               (let [[v rs] (if outer (reads/with-reads compute) [(compute) nil])
                     e (if outer {:val v :reads rs} {:val v})]
                 (swap! db-registry-cache cache/miss ck e)
                 (when ak
                   (swap! db-registry-anchor
                          #(let [base (if (> (count %) 32) {} %)]
                             (if (>= (long mtx) (long (:max-tx (get base ak) 0)))
                               (assoc base ak (assoc e :cid cur-cid :max-tx mtx :schema-cnt scnt))
                               base))))
                 (when (and outer (seq rs)) (swap! outer into rs))
                 v)))))))))

(defn validate-against
  "Validates `value` against registered Malli `schema-key` on `db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :keyword :any] :map] :cost/ticks 10000000}
  [db schema-key value]
  (let [{:keys [registry]} (registry-for-db db)
        s (mr/schema registry schema-key)]
    (if-not s
      {:ok? false :schema schema-key :explain (str "Unknown schema: " schema-key)}
      (if (m/validate s value {:registry registry})
        {:ok? true :schema schema-key}
        {:ok? false :schema schema-key
         :explain (me/humanize (m/explain s value {:registry registry}))}))))

(defonce ^:private ^:seon.flow/edge sample-schema-cache
  (cw/lru-cache-factory {} :threshold 256))

(defn sample-schema
  "Generates `n` deterministic (`seed = 42`) example values for `schema-key` from `db` (cached per schema form)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :keyword] :any] [:=> [:cat [:maybe :seon.host/db] :keyword :int] :any]] :cost/ticks 25000000}
  ([db schema-key] (sample-schema db schema-key 5))
  ([db schema-key n]
   (let [{:keys [registry forms reg-sig]} (registry-for-db db)
         sf (get forms schema-key)
         ck (when sf [reg-sig schema-key sf (long n)])
         _ (when-not (satisfies? cache/CacheProtocol @sample-schema-cache)
             (reset! sample-schema-cache (cache/lru-cache-factory {} :threshold 256)))
         hit (when ck (get @sample-schema-cache ck ::miss))]
     (if (and ck (not (identical? hit ::miss)))
       hit
       (try
         (let [v (vec (mg/generate [:vector {:min n :max n} schema-key] {:registry registry :seed 42 :size 6}))]
           (when ck (swap! sample-schema-cache cache/miss ck v))
           v)
         (catch Throwable _
           (let [exs (when db (:seon.schema/examples (d/pull db '[:seon.schema/examples] [:seon.schema/key schema-key])))]
             (vec (take n exs)))))))))

(defn arm-var!
  "Wraps `v` (a Clojure `Var` with `:malli/schema` `[:=> ...]` metadata) via `m/-instrument` with typed contract blame."
  {:malli/schema [:function [:=> [:cat :any] :any] [:=> [:cat [:maybe :seon.host/db] :any] :any]], :seon.flow/edge true}
  ([v] (arm-var! nil v))
  ([db v]
   (when (and (var? v) (bound? v) (fn? @v))
     (let [m0 (meta v)
          v (if (and (nil? (:malli/schema m0)) (symbol? (:seon.code/reexport m0)))
              (or (resolve (:seon.code/reexport m0)) v)
              v)
          m (meta v)
          sch-form (:malli/schema m)]
       (when (and (vector? sch-form) (contains? #{:=> :function} (first sch-form)))
         (let [raw-fn (or (:seon/raw-fn m) @v)
               var-sym (symbol v)
               {:keys [registry]} (registry-for-db db)
               opts {:registry registry}
               fn-sch (try (m/schema sch-form opts) (catch Throwable _ nil))]
           (when fn-sch
             (let [wrapped
                   (m/-instrument
                    {:schema fn-sch
                     :report
                     (fn [phase data]
                       (when-not *bypass-contracts*
                         (let [is-input? (= :malli.core/invalid-input phase)
                               blame (if is-input? :input-contract :output-contract)
                               target-sch (if is-input? (:input data) (:output data))
                               bad-val (if is-input? (:args data) (:value data))
                               vsch (first (sort-by str (schema-refs-in-form (when target-sch (m/form target-sch)))))
                               exp (when target-sch (m/explain target-sch bad-val opts))
                               human (if exp (me/humanize exp) (str phase))
                               example (when (and is-input? target-sch) (safe-example target-sch opts))]
                           (throw (ex-info
                                   (str "Schema contract violation in " var-sym
                                        " (" (if is-input? "invalid arguments" "invalid return value") "): "
                                        (pr-str human)
                                        " | Got: " (pr-str bad-val)
                                        " | Contract: " (pr-str sch-form)
                                        (when example (str " | Valid example args: " (pr-str example))))
                                   (cond-> {:error :seon.schema/contract-violation :blame blame :var var-sym :phase phase
                                            :contract sch-form :human human :got bad-val :example-args example}
                                     vsch (assoc :violated-schema vsch)))))))}
                    raw-fn)]
               (alter-meta! v assoc :seon/raw-fn raw-fn :seon/instrumented? true)
               (alter-var-root v (constantly wrapped))
               var-sym))))))))

(defn entity-index
  "Returns the cached `seon.schema.index` entity/facet/target index for `db`."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] :map] [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] :map]]}
  ([db] (:entity-index (if db (registry-for-db db) @base-registry)))
  ([db extra-forms] (:entity-index (if db (registry-for-db db extra-forms) (cached-compiled-registry (merge (:forms @base-registry) extra-forms))))))

(defn shrink-failing-args [& args] (apply six/shrink-failing-args args))

(defn validate-schema-registration!
  "Validates a candidate Malli schema (reusing compiled registry & cached `:declarations`) before storing in Datahike."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :map] :map] [:=> [:cat [:maybe :seon.host/db] :map [:maybe :map]] :map]]}
  ([db m] (validate-schema-registration! db m nil))
  ([db m sibling-forms]
   (let [key      (or (:key m) (:seon.schema/key m))
         raw-form (or (:form m) (:seon.schema/form m))
         doc      (or (:doc m) (:seon.schema/doc m))
         raw-ex   (or (:examples m) (:seon.schema/examples m))]
     (when-not (multi-segment-schema-key? key)
       (throw (ex-info (str "Schema key must use a multi-segment namespace co-located with code "
                            "(e.g. :seon.billing/amount, not :billing/amount), got: " (pr-str key))
                       {:error :seon.schema/invalid-key :key key})))
     (let [parsed-form (if (string? raw-form) (edn/read-string raw-form) raw-form)
           parsed-ex   (cond
                         (and (string? raw-ex) (not (str/blank? raw-ex))) (edn/read-string raw-ex)
                         (sequential? raw-ex) (vec raw-ex)
                         :else nil)
           base-ri (registry-for-db db)
           reg-info (if (and (empty? sibling-forms) (= parsed-form (get (:forms base-ri) key)))
                      base-ri
                      (registry-for-db db (assoc (or sibling-forms {}) key parsed-form)))
           {:keys [registry declarations decl-by-id]} reg-info
           opts {:registry registry}
           compiled (try
                      (m/schema key opts)
                      (catch Throwable t
                        (throw (ex-info (str "Invalid Malli schema for " key ": " (.getMessage t))
                                        {:error :seon.schema/compile-failed :key key :form parsed-form}))))
           valid? (m/validator compiled opts)
           sample (or (when (seq parsed-ex)
                        (doseq [ex parsed-ex]
                          (when-not (valid? ex)
                            (throw (ex-info (str "Schema " key " rejected its own example " (pr-str ex) ": "
                                                 (pr-str (me/humanize (m/explain compiled ex opts))))
                                            {:error :seon.schema/invalid-example :key key :example ex}))))
                        (first parsed-ex))
                      (try
                        (let [g (mg/generate compiled {:registry registry :seed 42 :size 6})]
                          (when-not (valid? g)
                            (throw (ex-info (str "Generated value for " key " failed its own validator: " (pr-str g))
                                            {:error :seon.schema/bad-generator :key key :generated g})))
                          g)
                        (catch Throwable t
                          (throw (ex-info (str "Schema " key " has no working generator and no `:examples`: "
                                               (.getMessage t)
                                               ". Provide `:examples [val ...]` or `:gen/fmap`.")
                                          {:error :seon.schema/missing-generator :key key :form parsed-form})))))
           dh-decls (if-let [d1 (get decl-by-id key)]
                      (sync-dh-decls (or (:schema db) {}) [d1])
                      (sync-dh-decls (or (:schema db) {}) declarations))
           batch-refs (into {key (six/schema-tempid key)}
                            (map (fn [k] [k (six/schema-tempid k)]))
                            (keys sibling-forms))
           ent (six/enrich-schema-entity-tx
                db
                {:seon.schema/key key :seon.schema/form parsed-form
                 :seon.schema/doc doc :seon.schema/examples parsed-ex}
                registry
                {:schema->ref batch-refs})
           retracts (six/stale-schema-edge-retracts db [ent])]
       {:ok? true :sample sample :dh-decls dh-decls :retract-ops retracts :entity ent}))))

(defn- live-branch-db []
  (some-> (requiring-resolve 'seon.fork.calls/*agent-ctx*) deref :conn deref))

(defn- edn-safe-ipc [& args] (apply six/edn-safe-ipc args))

(defn- stress-arity [& args] (apply six/stress-arity args))

(defn stress-var
  "Deterministically exercises `fn-sym` (single `:=>` or multi-arity `:function`) with `:examples`, guards, `seed = 42` inputs, shrinking, and `:cost/*` budgets. Usage: `(user/stress-var 'ns/fn)` or `(user/stress-var 'ns/fn {:samples 100 :seed 42})`."
  {:malli/schema [:function [:=> [:cat :any] [:maybe :map]] [:=> [:cat :any :any] [:maybe :map]] [:=> [:cat [:maybe :seon.host/db] :any :map] [:maybe :map]]], :seon.flow/edge true}
  ([fn-sym] (stress-var (live-branch-db) fn-sym {}))
  ([a b] (if (or (qualified-symbol? a) (var? a) (string? a))
           (if (map? b) (stress-var (live-branch-db) a b)
               {:ok? false :error :invalid-stress-var-args :hint "Usage: (user/stress-var 'ns/fn) or (user/stress-var 'ns/fn {:samples 50 :seed 42})"})
           (stress-var a b {})))
  ([db fn-sym {:keys [samples seed measure-fn] :or {samples 100 seed 42}}]
   (let [fn-sym (cond (qualified-symbol? fn-sym) fn-sym (var? fn-sym) (symbol fn-sym) (and (string? fn-sym) (str/includes? fn-sym "/")) (symbol fn-sym) :else nil)]
     (if-not fn-sym
       {:ok? false :error :invalid-stress-var-sym :hint "Pass a qualified var symbol like 'ns/fn."}
       (when-let [v (or (resolve fn-sym) (try (requiring-resolve fn-sym) (catch Throwable _ nil)))]
         (let [m0 (meta v)
               v (if (and (nil? (:malli/schema m0)) (symbol? (:seon.code/reexport m0)) (not= (:seon.code/reexport m0) fn-sym))
                   (or (resolve (:seon.code/reexport m0)) v) v)
               m (meta v) sch-form (:malli/schema m)
               skip-reason (stress-skip-reason db fn-sym m)
               fn-like? (and (vector? sch-form) (contains? #{:=> :function} (first sch-form)))]
           (cond
             skip-reason {:ok? true :skipped? true :var fn-sym :ran 0 :max-ticks 0 :max-bytes 0 :ms 0.0 :reason skip-reason}
             fn-like?
             (let [gen? (boolean (generatable-function-schema? sch-form))
                   {:keys [registry]} (registry-for-db db) opts {:registry registry}
                   fn-sch (m/schema sch-form opts) target-fn (or (:seon/raw-fn m) @v)
                   budgets {:ticks-budget (:cost/ticks m) :bytes-budget (:cost/bytes m) :ms-budget (:cost/ms m)}
                   t0 (System/nanoTime)
                   all-ars (if (= :function (m/type fn-sch)) (vec (m/children fn-sch)) [fn-sch])
                   arities (if gen? (filterv #(generatable-function-schema? (m/form %)) all-ars) all-ars)
                   top-ex (vec (or (:examples (m/properties fn-sch)) (:malli/examples m)))
                   per (if gen? (max 1 (quot (long samples) (max 1 (count arities)))) 0)
                   ex-for-ar (fn [i ar]
                               (let [ar-n (let [ch (first (m/children ar))]
                                            (when (and ch (= :cat (m/type ch)))
                                              (let [cs (m/children ch)]
                                                (when-not (some #(contains? #{:* :? :+} (m/type %)) cs) (count cs)))))
                                     top-match (if (and (= :function (m/type fn-sch)) ar-n)
                                                 (filterv #(and (vector? %) (vector? (first %)) (= ar-n (count (first %)))) top-ex)
                                                 (when (zero? i) top-ex))]
                                 (vec (concat top-match (when (not= ar fn-sch) (:examples (m/properties ar)))))))
                   out (reduce (fn [acc [i ar]]
                                 (let [r (stress-arity fn-sym target-fn opts registry ar (ex-for-ar i ar) per (+ (long seed) i) measure-fn budgets)]
                                   (if (:ok? r)
                                     (-> acc (update :ran + (:ran r 0)) (update :max-ticks max (:max-ticks r 0)) (update :max-bytes max (:max-bytes r 0)))
                                     (reduced r))))
                               {:ok? true :var fn-sym :ran 0 :max-ticks 0 :max-bytes 0}
                               (map-indexed vector arities))
                   ms (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0)]
               (cond-> (assoc out :ms (double ms))
                 (and (:ok? out) (not gen?))
                 (assoc :skipped-gen? true
                        :skipped? (zero? (long (:ran out 0)))
                        :reason (if (pos? (long (:ran out 0)))
                                  (str "Checked " (:ran out) " example(s); random seed=42 generation skipped (schema uses :seon.host/* or :any inside :function).")
                                  "No :examples and random seed=42 generation skipped (schema uses :seon.host/* or :any inside :function — use concrete input types or single :=> for generation).")))))))))))
