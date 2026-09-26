(ns seon.measure-cache-kinds
  "Offline retained-size measurement of seon.cache's schema-family kinds, to fit
  each kind's weigh factor (web research 2026-09-26 §5: fit count x factor
  offline; never measure on default).

  One kind per fresh JVM, from a checkout snapshot:

    clojure -Sdeps '{:aliases {:m {:extra-paths [\"bench\"]}}}' -J-Xmx6g -M:m -m seon.measure-cache-kinds
       KIND bench/seon/cache_kind_measurement.edn [contracts-corpus.edn]

  A kind's retention is the used heap its build leaves while its value is held
  (after collections settle), less the heap before. A deep walk (jamm) cannot
  size the hidden-class lambdas inside compiled schemas on JDK 26, and it
  counts structure the value shares; this counts exactly what holding the
  entry keeps alive. Projection kinds are measured with the declaration
  projection already built, as they are in the process cache, so each row is
  the kind's marginal retention (authored shapes with their projection built).
  `:seon.measure/git-head` is the repository HEAD at the run; the snapshot's
  commit is recorded beside it by hand."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(defn- used-heap
  "Heap in use after collections have settled."
  []
  (dotimes [_ 4] (System/gc) (Thread/sleep 200))
  (let [runtime (Runtime/getRuntime)] (- (.totalMemory runtime) (.freeMemory runtime))))

(defn- retained-by
  "Heap `build` leaves retained while its value is held: used heap after
  holding it, less used heap before. Unlike a deep walk this counts the
  hidden-class lambdas inside compiled schemas (which jamm cannot size by
  field offset on this JDK) and anything the build caches beside the value;
  it is the retention a cache entry causes."
  [build]
  (let [before (used-heap)
        held (volatile! (build))
        after (used-heap)]
    [@held (- after before)]))

(defn- require-predicate-owners!
  "Require every namespace a packaged predicate names, so projections bind real Vars."
  [forms]
  (doseq [owner (into (sorted-set)
                      (keep #(when (and (qualified-symbol? %) (not= "clojure.core" (namespace %)))
                               (symbol (namespace %))))
                      (tree-seq coll? seq (vals forms)))]
    (try (require owner) (catch Throwable _ nil))))

(defn -main
  "Measure one kind (its name as the first argument) in this fresh JVM and
  merge its row into the measurement file."
  [kind out-path & [corpus-path]]
  (let [forms (schema.edn/packaged-forms)
        _ (require-predicate-owners! forms)
        _ (when (#{"seon.schema/projection" "seon.schema/projection-with-contracts"
                    "seon.program/authored-shapes"} kind)
            (schema/declaration-projection forms))
        _ (when (= kind "seon.program/authored-shapes")
            (schema/build-projection forms))
        contracts (fn []
                    (into {}
                          (keep (fn [[sym spec]]
                                  (let [form (edn/read-string spec)]
                                    (when (and (vector? form) (#{:=> :function} (first form)))
                                      [sym form]))))
                          (:seon.measure/contracts (edn/read-string (slurp corpus-path)))))
        [value retained]
        (case kind
          "seon.schema.edn/packaged-population"
          (retained-by #(#'schema.edn/resource-population schema.edn/default-resource))
          "seon.schema/declaration-projection"
          (retained-by #(schema/declaration-projection (into {} forms)))
          "seon.schema/projection"
          (retained-by #(schema/build-projection (into {} forms)))
          "seon.schema/projection-with-contracts"
          (let [c (contracts)] (retained-by #(schema/build-projection (into {} forms) c)))
          "seon.program/authored-shapes"
          (let [built (schema/build-projection forms)] (retained-by #(program/shapes-in built))))
        counted (case kind
                  "seon.schema.edn/packaged-population" (count (:seon.schema.edn/declared-forms value))
                  "seon.program/authored-shapes" (count value)
                  (+ (count (:seon.schema.projection/forms value))
                     (count (:seon.schema.projection/function-contracts value))))
        prior (try (edn/read-string (slurp out-path)) (catch Throwable _ {}))
        row (cond-> {:count counted :retained-bytes retained
                     :bytes-per-unit (quot retained (max 1 counted))}
              (= kind "seon.schema/projection-with-contracts")
              (assoc :contracts (count (:seon.schema.projection/function-contracts value))))
        result (-> prior
                   (assoc :seon.measure/tool "used heap after System/gc, holding the value, less before; one kind per fresh JVM"
                          :seon.measure/jvm (System/getProperty "java.version")
                          :seon.measure/git-head (-> (Runtime/getRuntime)
                                                     (.exec ^"[Ljava.lang.String;" (into-array String ["git" "rev-parse" "HEAD"]))
                                                     .getInputStream slurp str/trim)
                          :seon.measure/forms (count forms))
                   (assoc-in [:seon.measure/kinds (keyword kind)] row))]
    (spit out-path (with-out-str (pprint/pprint result)))
    (pprint/pprint {(keyword kind) row})
    (shutdown-agents)
    (System/exit 0)))
