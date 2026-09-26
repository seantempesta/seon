(ns seon.measure-contracts
  "Offline retained-size measurement of compiled function contracts (contracts-out design §10; root
  rulings 2026-09-27: clj-memory-meter in a dev-only :measure alias, its own JVM, never on default; on its
  result, no shared compiled-contract retention). Kept as the evidence for that decision.

  Run: clojure -M:measure -m seon.measure-contracts bench/seon/contracts_corpus.edn bench/seon/contracts_measurement.edn

  Inputs: the checkout's packaged schema forms (the population) and a corpus of stored contracts copied
  from a store (`contracts_corpus.edn`: [symbol spec-string admission-source] rows and their basis).
  Boundary: the population root is the entry's held base plus its table holder; a slot is the inner key
  and the product (compiled contract + references). A slot's retained size is
  measure([base key product]) - measure([base]): the held base is the same object graph, subtracted once.
  Sizes are measured before and after forcing the products consumers build (validators and explainers
  of each arity's input and output)."
  (:require [clj-memory-meter.core :as mm]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [malli.core :as m]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(defn- private-field [object field-name]
  (let [field (.getDeclaredField (class object) field-name)]
    (.setAccessible field true)
    (.get field object)))

(def ^:private hidden-class-skipping-meter
  "jamm's default meter with one extra class filter: skip JVM hidden classes
  (lambda instances, e.g. inside compiled regex schemas). jamm 0.4 cannot take
  field offsets on them on this JDK (UnsupportedOperationException from Unsafe);
  their few words are excluded and reported as a known undercount."
  (delay
    (mm/measure 1)
    (let [default (.build (mm/meter-builder))
          meter-class (class default)
          loader (.getClassLoader meter-class)
          filter-class (Class/forName "org.github.jamm.FieldAndClassFilter" true loader)
          base-filter (private-field default "classFilter")
          ignore-class (.getMethod filter-class "ignore" (into-array Class [Class]))
          skipping (java.lang.reflect.Proxy/newProxyInstance
                  loader (into-array Class [filter-class])
                  (reify java.lang.reflect.InvocationHandler
                    (invoke [_ _ _method arguments]
                      (let [[^Class c field] arguments]
                        (boolean
                         (if field
                           ;; A hidden class's own fields cannot be read by offset.
                           (or (.isHidden c) (.isHidden (.getType ^java.lang.reflect.Field field)))
                           (or (.isHidden c) (.invoke ignore-class base-filter (object-array [c])))))))))
          constructor (first (filter #(= 5 (.getParameterCount ^java.lang.reflect.Constructor %))
                                     (.getConstructors meter-class)))]
      (.newInstance ^java.lang.reflect.Constructor constructor
                    (object-array [(let [strategies (Class/forName "org.github.jamm.strategies.MemoryMeterStrategies" true loader)
                                         guess (Class/forName "org.github.jamm.MemoryMeter$Guess" true loader)]
                                     ;; Instrumentation sizes hidden classes; Unsafe offsets cannot.
                                     (.getStrategy (.invoke (.getMethod strategies "getInstance" (into-array Class [])) nil (object-array 0))
                                                   (Enum/valueOf guess "ALWAYS_INSTRUMENTATION")))
                                   skipping
                                   (let [field-filter-class (Class/forName "org.github.jamm.FieldFilter" true loader)
                                         base-fields (private-field default "fieldFilters")
                                         ignore-field (.getMethod field-filter-class "ignore" (into-array Class [Class java.lang.reflect.Field]))]
                                     (java.lang.reflect.Proxy/newProxyInstance
                                      loader (into-array Class [field-filter-class])
                                      (reify java.lang.reflect.InvocationHandler
                                        (invoke [_ _ _ arguments]
                                          (let [[^Class c field] arguments]
                                            (boolean (or (.isHidden c)
                                                         (.invoke ignore-field base-fields (object-array [c field])))))))))
                                   (private-field default "omitSharedBufferOverhead")
                                   (private-field default "listenerFactory")])))))

(defn- bytes-of [value] (mm/measure value :bytes true :meter @hidden-class-skipping-meter))

(defn- load-predicate-namespaces!
  "Require every namespace a packaged predicate names, so the population binds real Vars."
  [forms]
  (doseq [owner (into (sorted-set)
                      (keep #(when (and (qualified-symbol? %) (not= "clojure.core" (namespace %)))
                               (symbol (namespace %))))
                      (tree-seq coll? seq (vals forms)))]
    (try (require owner) (catch Throwable _ nil))))

(defn- population
  [forms]
  (schema/build-projection
   forms {}
   {:seon.schema/schema-admissions
    (update-vals forms (constantly {:seon.schema.admission/source :core}))}))

(defn- force-products!
  "Build what consumers force on a compiled contract: validators and explainers per arity role."
  [compiled]
  (doseq [arity (m/-function-schema-arities compiled)
          :let [info (m/-function-info arity)]
          role [:input :output]]
    (when-let [s (get info role)] (m/validator s) (m/explainer s)))
  compiled)

(defn- timed [f] (let [t (System/nanoTime) v (f)] [v (/ (- (System/nanoTime) t) 1e6)]))

(defn- form-features [form]
  (let [nodes (tree-seq coll? seq form)]
    {:printed (count (pr-str form))
     :arities (max 1 (count (filter #(and (vector? %) (= :=> (first %))) nodes)))
     :local-entries (reduce + 0 (keep #(when (map? %) (count (:registry %))) nodes))}))

(defn -main
  "Measure, write the result EDN to `out-path` and exit."
  [corpus-path out-path & [individual-count]]
  (let [started (System/nanoTime)
        forms (schema.edn/packaged-forms)
        _ (load-predicate-namespaces! forms)
        [projection build-ms] (timed #(population forms))
        _ (when (seq (:seon.schema.projection/function-contracts projection))
            (throw (ex-info "The measured population must be shape-only." {})))
        ;; The closed base the (since deleted) population entry held.
        base (schema/with-predicate-functions
              (select-keys projection [:seon.schema.projection/registry
                                       :seon.schema.projection/forms
                                       :seon.schema.projection/canonical-keys
                                       :seon.schema.projection/schema-dependencies
                                       :seon.schema.projection/schema-admissions
                                       :seon.schema.projection/pure-predicate-symbols])
              (schema/predicate-functions-in projection))
        holder {::base base ::table (atom {})}
        base-bytes (bytes-of holder)
        corpus (:seon.measure/contracts (edn/read-string (slurp corpus-path)))
        compile-one (fn [mode [sym spec source]]
                      (let [form (edn/read-string spec)]
                        (try
                          (let [[product ms] (timed #(schema/function-contract
                                                      base {:seon.schema/form form :seon.schema/mode mode
                                                            :seon.schema/admission {:seon.schema.admission/source source}
                                                            :seon.schema/identity sym}))]
                            {:sym sym :form form :product product :ms ms})
                          (catch Throwable failure {:sym sym :form form :refused (ex-message failure)}))))
        shape (mapv (partial compile-one :shape) corpus)
        compiled (filterv :product shape)
        ;; Individual slots: the largest printed forms plus a spread sample.
        by-size (sort-by (comp - :printed :features) (map #(assoc % :features (form-features (:form %))) compiled))
        n (Long/parseLong (or individual-count "60"))
        chosen (distinct (concat (take (quot n 2) by-size) (take-nth (max 1 (quot (count by-size) (quot n 2))) by-size)))
        slot-size (fn [base-now {:keys [form product]}] (- (bytes-of [holder [form product]]) base-now))
        individual-before (mapv (fn [{:keys [sym features] :as c}]
                                  (assoc features :sym sym :slot-bytes (slot-size base-bytes c)))
                                chosen)
        aggregate-before (- (bytes-of [holder (mapv (juxt :form :product) compiled)]) base-bytes)
        _ (run! #(force-products! (:seon.schema/compiled (:product %))) compiled)
        base-after (bytes-of holder)
        aggregate-after (- (bytes-of [holder (mapv (juxt :form :product) compiled)]) base-after)
        individual (mapv (fn [{:keys [sym features] :as c}]
                           (assoc features :sym sym :slot-bytes (slot-size base-after c)))
                         chosen)
        ;; Conservative fit: bytes <= a*printed + b*arities + c*local-entries for every measured slot.
        b 2048 c 1024
        a (long (Math/ceil (apply max (map (fn [{:keys [slot-bytes printed arities local-entries]}]
                                             (/ (max 0 (- slot-bytes (* b arities) (* c local-entries)))
                                                (max 1 printed)))
                                           individual))))
        estimate (fn [{:keys [printed arities local-entries]}] (+ (* a printed) (* b arities) (* c local-entries)))
        all-features (map (comp form-features :form) compiled)
        over (count (filter #(> (estimate %) 16384) all-features))
        armed (mapv (partial compile-one :armed) (take 200 corpus))
        result {:seon.measure/tool "clj-memory-meter 0.3.0 (jamm), -Djdk.attach.allowAttachSelf"
                :seon.measure/jvm (System/getProperty "java.version")
                :seon.measure/corpus-basis (:seon.measure/basis (edn/read-string (slurp corpus-path)))
                :seon.measure/population-forms (count forms)
                :seon.measure/population-build-ms build-ms
                :seon.measure/population-root-bytes base-bytes
                :seon.measure/population-root-bytes-after-use base-after
                :seon.measure/contracts (count corpus)
                :seon.measure/compiled (count compiled)
                :seon.measure/refused (count (remove :product shape))
                :seon.measure/aggregate-slot-bytes-before-use aggregate-before
                :seon.measure/aggregate-slot-bytes-after-use aggregate-after
                :seon.measure/mean-slot-bytes-after-use (quot aggregate-after (max 1 (count compiled)))
                :seon.measure/individual-slots (count individual)
                :seon.measure/individual-before-use {:max (apply max (map :slot-bytes individual-before))
                                                      :median (nth (sort (map :slot-bytes individual-before)) (quot (count individual-before) 2))
                                                      :over-16k (count (filter #(> (:slot-bytes %) 16384) individual-before))}
                :seon.measure/individual-after-use {:max (apply max (map :slot-bytes individual))
                                                    :median (nth (sort (map :slot-bytes individual)) (quot (count individual) 2))
                                                    :over-16k (count (filter #(> (:slot-bytes %) 16384) individual))}
                :seon.measure/individual-max-bytes (apply max (map :slot-bytes individual))
                :seon.measure/individual-largest (vec (take 5 (sort-by (comp - :slot-bytes) individual)))
                :seon.measure/fitted {:a a :b b :c c :domain "EDN forms whose bindings are all Vars or Var roots (accounted)"}
                :seon.measure/margin-against-16k (- 16384 (apply max (map :slot-bytes individual)))
                :seon.measure/estimate-bypass-count over
                :seon.measure/estimate-bypass-rate (double (/ over (max 1 (count compiled))))
                :seon.measure/shape-compile-ms {:median (nth (sort (map :ms compiled)) (quot (count compiled) 2))
                                                :max (apply max (map :ms compiled))}
                :seon.measure/armed-compiled (count (filter :product armed))
                :seon.measure/armed-compile-ms-median (let [v (sort (keep :ms armed))] (nth v (quot (count v) 2)))
                :seon.measure/estimate-ms-per-form
                (let [[_ ms] (timed #(doall (map (comp estimate form-features :form) compiled)))]
                  (/ ms (max 1 (count compiled))))
                :seon.measure/total-ms (/ (- (System/nanoTime) started) 1e6)}]
    (spit out-path (with-out-str (pprint/pprint result)))
    (pprint/pprint (dissoc result :seon.measure/individual-largest))
    (shutdown-agents)
    (System/exit 0)))
