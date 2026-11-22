(ns seon.schema
  "First-class Malli schema registry, automatic Datahike attribute derivation, `seed = 42`
   generator rail, agent-friendly instrumentation (`50 ns`/call), and 100-sample generative checks:
   1. All schema keys MUST use multi-segment namespaces co-located with their owning code area
      (e.g. `:seon.code.var/name`, `:seon.billing/amount` — never shallow `:var/name`!).
   2. Datahike attributes are derived 100% automatically via `seon.schema.datahike` — registering
      a new storable attribute schema at runtime installs its `:db/ident` into Datahike automatically!
   3. Every `defn` with `{:malli/schema [:=> ...]}` is armed via `m/-instrument` and stress-tested
      with 100 deterministic samples (`seed = 42`) when its reach digest moves."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [malli.registry :as mr]
            [seon.schema.datahike :as sdh]
            [clojure.core.cache :as cache]
            [clojure.core.cache.wrapped :as cw]
            [seon.schema.index :as six]))

(def checkout-root
  (or (System/getenv "SEON_CHECKOUT")
      (System/getProperty "user.dir")))

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

(defn generatable-function-schema?
  "True when `sch` is a single-arity `[:=> [:cat ...] ret]` whose argument schema contains pure
   generatable values rather than stateful `:seon.host/*` handles (`Connection`, `DB`, `Channel`, `Atom`).
   Stateful `:seon.host/*` functions are still 100% instrumented at runtime via `arm-var!`."
  [sch]
  (when (and (vector? sch) (= :=> (first sch)))
    (let [args-form (if (map? (second sch)) (nth sch 2 nil) (second sch))]
      (not (some #(and (qualified-keyword? %) (= "seon.host" (namespace %)))
                 (tree-seq coll? seq args-form))))))

(defn multi-segment-schema-key?
  "Enforces co-located hierarchical naming: every schema keyword must have a multi-segment namespace
   (e.g. `:seon.code/hash`, `:seon.code.var/name`, `:seon.app.orders/total`), never `one/thing`!"
  {:malli/schema [:=> [:cat :any] :boolean]
   :cost/ticks 50000
   :cost/bytes 65536}
  [k]
  (boolean (and (qualified-keyword? k)
  (let [ns-s (str (namespace k))]
    (or (str/includes? ns-s ".")
        (contains? #{"db" "datahike" "malli" "flow" "git" "http"} ns-s))))))

(defn schema-owner-path
  "Maps any global schema key to the single canonical global schema file `resources/seon/schemas.edn`."
  {:malli/schema [:=> [:cat :qualified-keyword] :string]
   :cost/ticks 100000
   :cost/bytes 131072}
  [_k]
  "resources/seon/schemas.edn")

(defn load-disk-schema-forms
  "Reads global Malli schemas from `resources/seon/schemas.edn` (falling back to legacy
   `resources/seon/schemas/*.edn` directories if `schemas.edn` does not yet exist)."
  ([] (load-disk-schema-forms checkout-root))
  ([root-dir]
   (let [single (io/file root-dir "resources/seon/schemas.edn")
         validate-map! (fn [fname m]
                         (doseq [k (keys m)]
                           (when-not (multi-segment-schema-key? k)
                             (throw (ex-info (str "Schema key in " fname " must have a multi-segment namespace (e.g. :seon.area/name), got: " k)
                                             {:file fname :key k}))))
                         m)]
     (if (.isFile single)
       (validate-map! "schemas.edn" (edn/read-string (slurp single)))
       (let [dir (io/file root-dir "resources/seon/schemas")]
         (if-not (.isDirectory dir)
           {}
           (->> (.listFiles dir)
                (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
                (sort-by #(.getName ^java.io.File %))
                (reduce (fn [acc ^java.io.File f]
                          (merge acc (validate-map! (.getName f) (edn/read-string (slurp f)))))
                        {}))))))))

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
  "Compiles a `{k form}` map into a composite Malli `Registry` with every named schema compiled once."
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
    (let [reg (compile-registry forms)]
      {:forms forms
      :registry reg
      :declarations (sdh/declarations reg)
      :merge-meta (sdh/merge-metadata reg)
      :entity-index (six/build-entity-index forms)}))))

(defn attr-validator-for
  "Returns `{:schema :valid?}` for a Datahike attribute `attr` from `reg-info`."
  [{:keys [registry merge-meta]} attr]
  (when-let [s (mr/schema registry attr)]
    (if (contains? (:many-attrs merge-meta) attr)
      ;; In Datahike tx-data, cardinality/many accepts a single element, sequential, or set!
      (let [elem-s (if (= :set (m/type s)) (first (m/children s)) s)
            tx-s (m/schema [:or elem-s [:sequential elem-s] [:set elem-s]] {:registry registry})]
        {:schema tx-s :valid? (m/validator tx-s {:registry registry})})
      {:schema s :valid? (m/validator s {:registry registry})})))

(defn schema-refs-in-form
  "Extracts all multi-segment qualified keywords referenced inside a `:malli/schema` form."
  [schema-form]
  (if (nil? schema-form)
    #{}
    (into #{}
          (filter (fn [x]
                    (and (multi-segment-schema-key? x)
                         (not (str/starts-with? (namespace x) "seon.db"))
                         (not (str/starts-with? (namespace x) "seon.merge")))))
          (tree-seq coll? seq schema-form))))

(defn- safe-example [input-sch opts]
  (try
    (mg/generate input-sch (assoc opts :seed 42 :size 4))
    (catch Throwable _ nil)))

(def ^:dynamic *bypass-contracts*
  "When true (e.g. inside `seon.code/eval-forms`'s own `read-clj`), Malli contract violations
   do not abort execution so self-modification cannot cut the floor out from the REPL parser."
  false)

(def ^:dynamic *base-forms-override*
  "Optional `{:schema/key form}` map bound during branch test execution so ephemeral `with-test-store`
   fixtures inherit the branch's live `:seon.schema/*` definitions before merge writes `schemas.edn` to disk."
  nil)

(def ^:seon.flow/edge base-registry
(let [st (atom {:disk nil :override nil})
      build (fn [forms]
              (let [reg (compile-registry forms)]
                {:forms forms
                 :registry reg
                 :declarations (sdh/declarations reg)
                 :merge-meta (sdh/merge-metadata reg)
                 :entity-index (six/build-entity-index forms)}))]
  (reify clojure.lang.IDeref
    (deref [_]
      (let [ov *base-forms-override*
            slot (if (seq ov) :override :disk)
            forms (if (seq ov) ov @disk-base-forms)
            cur (get @st slot)]
        (if (and (= forms (:forms cur)) (:base cur))
          (:base cur)
          (let [b (build forms)]
            (swap! st assoc slot {:forms forms :base b})
            b)))))))

(defn registry-for-db
  "Returns `{:registry :forms :merge-meta :declarations}` combining `base-registry` + any branch-specific
   `:seon.schema/key` entities on `db` + optional `extra-forms`, with bounded cache over exact form maps."
  ([db] (registry-for-db db nil))
  ([db extra-forms]
   (let [db-forms (db-schema-forms db)
         base @base-registry
         merged (if (and (empty? db-forms) (empty? extra-forms))
                  (:forms base)
                  (merge (:forms base) db-forms extra-forms))]
     (if (= merged (:forms base))
       base
       (cached-compiled-registry merged)))))

(defn validate-against
  "Validates `value` against registered Malli `schema-key` on `db`.
   Returns `{:ok? true}` or `{:ok? false :schema schema-key :explain ...}`."
  [db schema-key value]
  (let [{:keys [registry]} (registry-for-db db)
        s (mr/schema registry schema-key)]
    (if-not s
      {:ok? false :schema schema-key :explain (str "Unknown schema: " schema-key)}
      (if (m/validate s value {:registry registry})
        {:ok? true :schema schema-key}
        {:ok? false
         :schema schema-key
         :explain (me/humanize (m/explain s value {:registry registry}))}))))

(defn validate-schema-registration!
  "Validates a candidate Malli schema before storing it in Datahike:
   1. Key MUST be multi-segment qualified (`:seon.<area>/*` — co-located with code!).
   2. Form must compile cleanly against the branch's Malli registry.
   3. Must either generate a valid sample with `seed = 42` OR provide valid `:examples`.
   4. Automatically derives its Datahike `:db/ident` declaration(s) if it carries `:seon.db/*`
      storage properties or `{:seon.db/attributes true}`!
   Returns `{:ok? true :sample val :entity map :dh-decls [...]}`."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :map] :map] [:=> [:cat [:maybe :seon.host/db] :map [:maybe :map]] :map]]}
  ([db m] (validate-schema-registration! db m nil))
  ([db m sibling-forms]
   (let [key      (or (:key m) (:seon.schema/key m))
         raw-form (or (:form m) (:seon.schema/form m))
         doc      (or (:doc m) (:seon.schema/doc m))
         raw-ex   (or (:examples m) (:seon.schema/examples m))
         render   (or (:render m) (:seon.schema/render m))]
     (when-not (multi-segment-schema-key? key)
       (throw (ex-info (str "Schema key must use a multi-segment namespace co-located with code "
                            "(e.g. :seon.billing/amount, not :billing/amount), got: " (pr-str key))
                       {:error :seon.schema/invalid-key :key key})))
     (let [parsed-form (if (string? raw-form) (edn/read-string raw-form) raw-form)
           parsed-ex   (cond
                         (and (string? raw-ex) (not (str/blank? raw-ex))) (edn/read-string raw-ex)
                         (sequential? raw-ex) (vec raw-ex)
                         :else nil)
           {:keys [registry]} (registry-for-db db (assoc (or sibling-forms {}) key parsed-form))
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
           ;; Automatically derive Datahike attribute declarations if this schema declares storage!
           all-attr-ks (set (sdh/attribute-keys registry))
           existing-dh (if db (set (keys (:schema db))) #{})
           dh-decls (cond
                      (contains? all-attr-ks key)
                      (if (contains? existing-dh key) [] [(sdh/declaration registry key)])

                      ;; Also check if `key` is an entity map `{:seon.db/attributes true}` adding member attrs
                      :else
                      (->> all-attr-ks
                           (remove existing-dh)
                           (mapv #(sdh/declaration registry %))))]
       {:ok? true
        :sample sample
        :dh-decls dh-decls
        :entity (let [eff-render (or render (when (and (vector? parsed-form) (map? (second parsed-form)))
                                            (:seon.render/hiccup (second parsed-form))))]
                (cond-> {:seon.schema/key key
                         :seon.schema/form parsed-form}
                  doc            (assoc :seon.schema/doc (str doc))
                  (seq parsed-ex) (assoc :seon.schema/examples (vec parsed-ex))
                  eff-render     (assoc :seon.schema/render eff-render)))}))))

(defn sample-schema
  "Generates `n` deterministic (`seed = 42`) example values for `schema-key` from `db`."
  ([db schema-key] (sample-schema db schema-key 5))
  ([db schema-key n]
   (let [{:keys [registry]} (registry-for-db db)
         ent (when db (d/pull db '[:seon.schema/examples] [:seon.schema/key schema-key]))
         exs (:seon.schema/examples ent)]
     (try
       (vec (mg/generate [:vector {:min n :max n} schema-key] {:registry registry :seed 42 :size 6}))
       (catch Throwable _
         (vec (take n exs)))))))

(defn arm-var!
  "Wraps `v` (a Clojure `Var` with `:malli/schema` `[:=> ...]` metadata) using `m/-instrument` (~50 ns/call).
   Idempotent via `:seon/raw-fn`. Throws structured humanized errors + concrete `seed = 42` example args."
  {:malli/schema [:function [:=> [:cat :any] :any] [:=> [:cat [:maybe :seon.host/db] :any] :any]]}
  ([v] (arm-var! nil v))
  ([db v]
   (when (and (var? v) (bound? v) (fn? @v))
     (let [m (meta v)
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
                               target-sch (if is-input? (:input data) (:output data))
                               bad-val (if is-input? (:args data) (:value data))
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
                                   {:error :seon.schema/contract-violation
                                    :var var-sym
                                    :phase phase
                                    :contract sch-form
                                    :human human
                                    :got bad-val
                                    :example-args example})))))}
                    raw-fn)]
               (alter-meta! v assoc :seon/raw-fn raw-fn :seon/instrumented? true)
               (alter-var-root v (constantly wrapped))
               var-sym))))))))

(defn stress-var
  "Deterministically exercises `fn-sym` with `samples` generated call inputs (`seed = 42` by default)
   and checks `:cost/ticks` / `:cost/bytes` metadata budgets."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :qualified-symbol] [:maybe :map]] [:=> [:cat [:maybe :seon.host/db] :qualified-symbol :map] [:maybe :map]]]}
  ([db fn-sym] (stress-var db fn-sym {}))
  ([db fn-sym {:keys [samples seed measure-fn]
               :or {samples 100 seed 42}}]
   (when-let [v (resolve fn-sym)]
     (let [m (meta v)
           sch-form (:malli/schema m)]
       (when (generatable-function-schema? sch-form)
         (let [{:keys [registry]} (registry-for-db db)
               opts {:registry registry}
               fn-sch (m/schema sch-form opts)
               [cat-s ret-s] (m/children fn-sch)
               valid-ret? (m/validator ret-s opts)
               target-fn (or (:seon/raw-fn m) @v)
               ticks-budget (:cost/ticks m)
               bytes-budget (:cost/bytes m)
               ms-budget (:cost/ms m)
               examples (:examples (m/properties fn-sch))
               t0 (System/nanoTime)
               arg-batches (try
                             (mg/generate [:vector {:min samples :max samples} cat-s]
                                          {:registry registry :seed seed :size 8})
                             (catch Throwable t
                               {:gen-error (.getMessage t)}))]
           (if (map? arg-batches)
             {:ok? false
              :var fn-sym
              :reason (str "Generator failed for " fn-sym ": " (:gen-error arg-batches))}
             (let [check-one
                 (fn [acc [tag sample-args expected]]
                   (try
                     (let [{:keys [val ticks bytes ms]}
                           (if measure-fn
                             (measure-fn #(apply target-fn sample-args))
                             {:val (apply target-fn sample-args) :ticks 0 :bytes 0 :ms 0.0})]
                       (cond
                         (and (= tag :example) (not= expected val))
                         (reduced {:ok? false
                                   :var fn-sym
                                   :example? true
                                   :args sample-args
                                   :expected expected
                                   :return val
                                   :reason (str "Example failed on " fn-sym " args " (pr-str sample-args)
                                                ": expected " (pr-str expected) ", got " (pr-str val))})

                         (not (valid-ret? val))
                         (reduced {:ok? false
                                   :var fn-sym
                                   :args sample-args
                                   :return val
                                   :human (me/humanize (m/explain ret-s val opts))
                                   :reason (str "Return value failed schema " (pr-str (m/form ret-s))
                                                " on args " (pr-str sample-args) ": got " (pr-str val))})

                         (and ticks-budget (> ticks ticks-budget))
                         (reduced {:ok? false
                                   :var fn-sym
                                   :args sample-args
                                   :ticks ticks
                                   :ticks-budget ticks-budget
                                   :reason (str "Cost overrun on " fn-sym ": " ticks " ticks > :cost/ticks " ticks-budget
                                                " on args " (pr-str sample-args))})

                         (and bytes-budget (> bytes bytes-budget))
                         (reduced {:ok? false
                                   :var fn-sym
                                   :args sample-args
                                   :bytes bytes
                                   :bytes-budget bytes-budget
                                   :reason (str "Allocation overrun on " fn-sym ": " bytes " bytes > :cost/bytes " bytes-budget
                                                " on args " (pr-str sample-args))})

                         (and ms-budget ms (> ms ms-budget))
                         (reduced {:ok? false
                                   :var fn-sym
                                   :args sample-args
                                   :call-ms ms
                                   :ms-budget ms-budget
                                   :reason (str "Time overrun on " fn-sym ": " ms " ms > :cost/ms " ms-budget
                                                " on args " (pr-str sample-args))})

                         :else
                         (-> acc
                             (update :ran inc)
                             (update :max-ticks max (long (or ticks 0)))
                             (update :max-bytes max (long (or bytes 0))))))
                     (catch Throwable ex
                       (reduced {:ok? false
                                 :var fn-sym
                                 :args sample-args
                                 :exception (.getMessage ex)
                                 :reason (str "Threw exception on " (if (= tag :example) "example" (str "seed=" seed))
                                              " args " (pr-str sample-args) ": " (.getMessage ex))}))))
                 items (concat (map (fn [[a exp]] [:example a exp]) examples)
                               (map (fn [a] [:sample a nil]) arg-batches))
                 outcome (reduce check-one {:ok? true :var fn-sym :ran 0 :max-ticks 0 :max-bytes 0} items)
                 ms (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0)]
             (assoc outcome :ms (double ms))))))))))

(defn entity-index
  "Returns the cached `seon.schema.index` entity/facet/target index for `db` (or base registry when `db` is nil)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] :map] [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] :map]]}
  ([db] (:entity-index (if db (registry-for-db db) @base-registry)))
  ([db extra-forms] (:entity-index (if db (registry-for-db db extra-forms) (cached-compiled-registry (merge (:forms @base-registry) extra-forms))))))