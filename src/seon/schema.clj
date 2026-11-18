(ns seon.schema
  "First-class Malli schema registry, generator rail, agent-friendly instrumentation,
   and deterministic `seed = 42` generative stress testing:
   1. Every `:schema/key` registered in Datahike is immediately validated to have a working
      Malli generator (`seed = 42`) or valid `:schema/examples` (0 dead/untestable schemas!).
   2. Every `defn` with `{:malli/schema [:=> ...]}` is instrumented via `m/-instrument` (~50 ns/call),
      producing human-readable errors + concrete valid example inputs on any violation.
   3. Changed functions with schemas are stress-tested with 100 deterministic samples (`seed = 42`)
      in `:test` and cached by reach digest so unchanged functions never re-run."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [malli.registry :as mr]
            [seon.store :as store]))

(def ^:private host-predicates
  {:seon.host/fn        (m/-simple-schema {:type :seon.host/fn :pred fn?})
   :seon.host/var       (m/-simple-schema {:type :seon.host/var :pred var?})
   :seon.host/atom      (m/-simple-schema {:type :seon.host/atom :pred #(instance? clojure.lang.Atom %)})
   :seon.host/deref     (m/-simple-schema {:type :seon.host/deref :pred #(instance? clojure.lang.IDeref %)})
   :seon.host/chan      (m/-simple-schema {:type :seon.host/chan :pred some?})
   :seon.host/db        (m/-simple-schema {:type :seon.host/db :pred some?})
   :seon.host/conn      (m/-simple-schema {:type :seon.host/conn :pred #(instance? clojure.lang.IDeref %)})
   :seon.host/throwable (m/-simple-schema {:type :seon.host/throwable :pred #(instance? Throwable %)})
   :char                (m/-simple-schema {:type :char :pred char?})})

(def ^:private attr-malli-map
  (into {}
        (map (fn [{:keys [ident malli]}] [ident malli]))
        store/attributes))

(defn db-schema-forms
  "Reads all `:schema/key` entities from `db` into `{:schema/key parsed-form}`."
  [db]
  (if (nil? db)
    {}
    (into {}
          (keep (fn [dt]
                  (let [{:keys [:schema/key :schema/form]} (d/pull db '[:schema/key :schema/form] (:e dt))]
                    (when (and key form)
                      [key (edn/read-string form)]))))
          (d/datoms db :aevt :schema/key))))

(defn registry-for-db
  "Builds a composite Malli registry combining Malli defaults, host predicates, store attributes,
   and all `:schema/key` entities on `db` (plus optional `extra-forms` map)."
  ([db] (registry-for-db db nil))
  ([db extra-forms]
   (let [forms (merge attr-malli-map (db-schema-forms db) extra-forms)
         compiled (volatile! {})
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
     {:registry @reg :forms forms})))

(defn validate-schema-registration!
  "Validates a candidate `:schema/key` + `form` (+ optional `examples` vector) before storing it:
   1. Must compile cleanly as a Malli schema.
   2. Must either generate a valid sample with `seed = 42` OR provide non-empty `examples` that all
      pass `(m/validate s ex)`. Returns `{:ok? true :sample val :entity tx-map}` or throws clear `ex-info`."
  ([db m] (validate-schema-registration! db m nil))
  ([db m sibling-forms]
   (let [key      (or (:key m) (:schema/key m))
         form     (or (:form m) (:schema/form m))
         doc      (or (:doc m) (:schema/doc m))
         examples (or (:examples m) (:schema/examples m))
         render   (or (:render m) (:schema/render m))]
     (when-not (qualified-keyword? key)
       (throw (ex-info (str "Schema key must be a qualified keyword, got: " (pr-str key))
                       {:error :schema/invalid-key :key key})))
     (let [parsed-form (if (string? form) (edn/read-string form) form)
           parsed-ex (cond
                       (string? examples) (edn/read-string examples)
                       (some? examples) examples
                       :else nil)
           {:keys [registry]} (registry-for-db db (assoc (or sibling-forms {}) key parsed-form))
           opts {:registry registry}
           compiled (try
                      (m/schema key opts)
                      (catch Throwable t
                        (throw (ex-info (str "Invalid Malli schema for " key ": " (.getMessage t))
                                        {:error :schema/compile-failed :key key :form parsed-form}))))
           valid? (m/validator compiled opts)
           sample (or (when (seq parsed-ex)
                        (doseq [ex parsed-ex]
                          (when-not (valid? ex)
                            (throw (ex-info (str "Schema " key " rejected its own example " (pr-str ex) ": "
                                                 (pr-str (me/humanize (m/explain compiled ex opts))))
                                            {:error :schema/invalid-example :key key :example ex}))))
                        (first parsed-ex))
                      (try
                        (let [g (mg/generate compiled {:registry registry :seed 42 :size 6})]
                          (when-not (valid? g)
                            (throw (ex-info (str "Generated value for " key " failed its own validator: " (pr-str g))
                                            {:error :schema/bad-generator :key key :generated g})))
                          g)
                        (catch Throwable t
                          (throw (ex-info (str "Schema " key " has no working generator and no `:examples`: "
                                               (.getMessage t)
                                               ". Provide `:examples [val ...]` or `:gen/fmap` properties.")
                                          {:error :schema/missing-generator :key key :form parsed-form})))))]
       {:ok? true
        :sample sample
        :entity (cond-> {:schema/key key
                         :schema/form (pr-str parsed-form)}
                  doc (assoc :schema/doc (str doc))
                  (seq parsed-ex) (assoc :schema/examples (pr-str (vec parsed-ex)))
                  render (assoc :schema/render render))}))))

(defn validate-schema-batch!
  "Validates a batch of schema maps that may cross-reference each other (e.g. `schemas.edn`)."
  [db entries]
  (let [siblings (into {}
                       (keep (fn [m]
                               (let [k (or (:key m) (:schema/key m))
                                     f (or (:form m) (:schema/form m))]
                                 (when (and k f)
                                   [k (if (string? f) (edn/read-string f) f)]))))
                       entries)]
    (mapv #(:entity (validate-schema-registration! db % siblings)) entries)))

(defn sample-schema
  "Generates `n` deterministic (`seed = 42`) example values for `schema-key` from `db`."
  ([db schema-key] (sample-schema db schema-key 5))
  ([db schema-key n]
   (let [{:keys [registry]} (registry-for-db db)
         ent (when db (d/pull db '[:schema/examples] [:schema/key schema-key]))
         exs (when-let [s (:schema/examples ent)] (edn/read-string s))]
     (try
       (vec (mg/generate [:vector {:min n :max n} schema-key] {:registry registry :seed 42 :size 6}))
       (catch Throwable _
         (vec (take n exs)))))))

(defn schema-refs-in-form
  "Extracts all qualified keywords referenced inside a `:malli/schema` form (excluding `:malli/*`)."
  [schema-form]
  (if (nil? schema-form)
    #{}
    (into #{}
          (filter (fn [x]
                    (and (qualified-keyword? x)
                         (not= "malli" (namespace x)))))
          (tree-seq coll? seq schema-form))))

(defn- safe-example [input-sch opts]
  (try
    (mg/generate input-sch (assoc opts :seed 42 :size 4))
    (catch Throwable _ nil)))

(defn arm-var!
  "Wraps `v` (a Clojure `Var` with `:malli/schema` `[:=> ...]` metadata) using `m/-instrument` (~50 ns/call).
   Idempotent: keeps `:seon/raw-fn` in metadata so re-arming never double-wraps!
   On violation, throws a structured agent-friendly error with `malli.error/humanize` + valid example args."
  ([v] (arm-var! nil v))
  ([db v]
   (when (and (var? v) (bound? v) (fn? @v))
     (let [m (meta v)
           sch-form (:malli/schema m)]
       (when (and (vector? sch-form) (= :=> (first sch-form)))
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
                                 {:error :schema/contract-violation
                                  :var var-sym
                                  :phase phase
                                  :contract sch-form
                                  :human human
                                  :got bad-val
                                  :example-args example}))))}
                    raw-fn)]
               (alter-meta! v assoc :seon/raw-fn raw-fn :seon/instrumented? true)
               (alter-var-root v (constantly wrapped))
               var-sym))))))))

(defn stress-var
  "Deterministically exercises `fn-sym` with `n` generated call samples (`seed = 42` by default).
   Uses `:seon/raw-fn` if armed so we test pure logic + explicit input/output contract match,
   and also checks `:cost/ticks` / `:cost/bytes` per-call metadata budgets if declared on the var!"
  ([db fn-sym] (stress-var db fn-sym {}))
  ([db fn-sym {:keys [samples seed measure-fn]
               :or {samples 100 seed 42}}]
   (when-let [v (resolve fn-sym)]
     (let [m (meta v)
           sch-form (:malli/schema m)]
       (when (and (vector? sch-form) (= :=> (first sch-form)))
         (let [{:keys [registry]} (registry-for-db db)
               opts {:registry registry}
               fn-sch (m/schema sch-form opts)
               [cat-s ret-s] (m/children fn-sch)
               valid-ret? (m/validator ret-s opts)
               target-fn (or (:seon/raw-fn m) @v)
               ticks-budget (:cost/ticks m)
               bytes-budget (:cost/bytes m)
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
             (let [outcome
                   (reduce
                    (fn [acc sample-args]
                      (try
                        (let [{:keys [val ticks bytes]}
                              (if measure-fn
                                (measure-fn #(apply target-fn sample-args))
                                {:val (apply target-fn sample-args) :ticks 0 :bytes 0})]
                          (cond
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
                                    :reason (str "Threw exception on seed=" seed " args " (pr-str sample-args) ": " (.getMessage ex))}))))
                    {:ok? true :var fn-sym :ran 0 :max-ticks 0 :max-bytes 0}
                    arg-batches)
                   ms (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0)]
               (assoc outcome :ms (double ms))))))))))
