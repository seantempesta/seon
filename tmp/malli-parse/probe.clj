(ns probe
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [malli.core :as m]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(def database-contracts
  ;; Read-only query snapshot from the live `default` branch on 2026-08-01:
  ;; [:find ?sym ?spec :in $ [?sym ...] ...]. The raw JVM deliberately does
  ;; not open the process-root store while the running Seon JVM owns its flock.
  [["seon.render.value/result-window-edn"
    "[:=> [:cat :seon.render/unit :seon.cluster.eval/result-edn] :seon.cluster.eval/result-edn]"]
   ["seon.render.walk/prose"
    "[:function [:=> [:cat :seon.db/database-value :seon.render.walk/node] [:maybe :string]] [:=> [:cat :seon.db/database-value :seon.render.walk/node [:map {:closed true} [:seon.render.walk/branch {:optional true} [:vector [:or :keyword :int]]]]] [:maybe :string]]]"]
   ["seon.schema.internal/assert-compilable-schema!"
    "[:function {:registry #:seon.schema.internal{:bound-definition [:or :nil :boolean [:fn clojure.core/number?] [:fn clojure.core/char?] :string :keyword :symbol :uuid [:fn clojure.core/inst?] [:fn clojure.core/map?] [:fn clojure.core/vector?] [:fn clojure.core/set?] [:fn clojure.core/sequential?]]}} [:=> [:cat :map :keyword :seon.schema.internal/bound-definition] :nil] [:=> [:cat :map :keyword :seon.schema.internal/bound-definition :map] :nil]]"]])

(defn data-function-info
  "Return Malli's function-info with schema objects restored to forms."
  [function-schema]
  (when-let [info (m/-function-info function-schema)]
    (cond-> (-> info
                (update :input m/form)
                (update :output m/form))
      (contains? info :guard) (update :guard m/form))))

(defn walk-data
  "Return Malli walk observations without replacing the schema tree."
  [compiled]
  (let [nodes (volatile! [])]
    (m/walk
     compiled
     (fn [node path _children _options]
       (vswap! nodes conj
               (cond-> {:path path
                        :type (m/type node)
                        :form (m/form node)}
                 (m/-ref-schema? node) (assoc :ref (m/-ref node))))
       node))
    @nodes))

(defn parse-contract
  "Decompose one contract with Malli's own public and protocol APIs."
  [form options]
  (let [compiled (m/function-schema form options)
        ast (m/ast compiled options)
        arities (m/-function-schema-arities compiled)]
    {:form (m/form compiled)
     :ast ast
     :from-ast-form (m/form (m/from-ast ast options))
     :round-trip? (= (m/form compiled)
                     (m/form (m/from-ast ast options)))
     :function-info (data-function-info compiled)
     :arities (mapv (fn [arity]
                      {:form (m/form arity)
                       :function-info (data-function-info arity)})
                    arities)
     :children (mapv m/form (m/children compiled))
     :walk (walk-data compiled)
     ;; `m/parse` parses these as VALUES against the function schema. A schema
     ;; form vector is itself IFn in Clojure and therefore passes the shallow
     ;; function-schema parser; a string does not. Neither call parses syntax.
     :m-parse-form-as-value (m/parse compiled form)
     :m-parse-string-as-value (m/parse compiled "not a function")}))

(defn nanos-per-call
  "Mean nanoseconds for `iterations` calls after a fixed warm-up."
  [f iterations]
  (dotimes [_ 1000] (f))
  (let [started (System/nanoTime)]
    (dotimes [_ iterations] (f))
    (/ (double (- (System/nanoTime) started)) iterations)))

(defn benchmark
  "Measure each Malli candidate and the complete decomposition."
  [form options]
  (let [compiled (m/function-schema form options)
        ast (m/ast compiled options)
        candidates
        {:form #(m/form compiled)
         :children #(m/children compiled)
         :walk #(walk-data compiled)
         :ast #(m/ast compiled options)
         :from-ast #(m/from-ast ast options)
         :function-info
         #(mapv data-function-info (m/-function-schema-arities compiled))
         :complete-decomposition #(parse-contract form options)}]
    (into (sorted-map)
          (map (fn [[candidate f]]
                 (let [nanos (nanos-per-call f 10000)]
                   [candidate
                    {:nanoseconds-per-contract nanos
                     :milliseconds-for-950 (/ (* nanos 950.0) 1000000.0)}])))
          candidates)))

(defn -main
  "Load Seon's packaged registry and print structures plus timings.

  Pass `benchmark` to print only the compact benchmark maps."
  [& [mode]]
  (schema.edn/load! {})
  (schema/activate! (schema.edn/packaged-forms))
  (let [options (:seon.schema.projection/compile-options
                 (schema/current-projection))]
    (doseq [[sym spec] database-contracts]
      (let [form (edn/read-string spec)]
        (println "CONTRACT" sym)
        (when-not (= "benchmark" mode)
          (pprint/pprint (parse-contract form options)))
        (println "BENCHMARK" sym)
        (pprint/pprint (benchmark form options))))))

(apply -main *command-line-args*)
