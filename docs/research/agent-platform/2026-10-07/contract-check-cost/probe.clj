;; Run with clojure.main in a disposable committed archive, on its platform main thread.
;; Arguments: whole|phases|retained, followed by source roots (default src script test).
;; Use the same pinned dependency classpath/cache on both sides; no store or test runner.
(def allocation-bean (java.lang.management.ManagementFactory/getThreadMXBean))
(def measurement-thread (.threadId (Thread/currentThread)))
(defn measure [f]
  (let [bytes (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean allocation-bean measurement-thread)
        began (System/nanoTime)
        result (f)]
    {:value result :ms (/ (- (System/nanoTime) began) 1e6)
     :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean allocation-bean measurement-thread) bytes)}))
(def loading (measure #(require 'seon.contracts-compile-test)))
(require '[seon.contracts-compile-test :as c] '[seon.schema :as schema])
(def paths (c/source-files (or (seq (rest *command-line-args*)) ["src" "script" "test"])))
(def observation
  (case (first *command-line-args*)
    "whole"
    (let [cold (measure #(c/check {::c/paths paths}))
          warm (mapv (fn [_] (measure #(c/check {::c/paths paths}))) (range 3))
          summary #(assoc (dissoc % :value) :findings (count (:value %)))]
      {:cold (summary cold) :warm (mapv summary warm)
       :finding-files (frequencies (map :filename (:value cold)))})

    "retained"
    (let [texts (into {} (map (fn [p] [p (#'c/read-file p)])) paths)
          memory (java.lang.management.ManagementFactory/getMemoryMXBean)
          _ (System/gc)
          _ (Thread/sleep 200)
          before (.getUsed (.getHeapMemoryUsage memory))
          reading (measure #(#'c/read-texts texts))
          _ (System/gc)
          _ (Thread/sleep 200)
          after (.getUsed (.getHeapMemoryUsage memory))]
      ;; Keeping reading reachable through this count prevents the held forms being collected.
      {:chars (reduce + (map count (vals texts))) :held-bytes (- after before)
       :held-files (count (:value reading)) :reading (dissoc reading :value)})

    (let [resources (measure #(#'c/schema-resources "."))
          forms (::c/population (:value resources))
          predicates (measure #(#'c/predicate-bindings "." (vals forms)))
          projection (measure #(schema/build-projection forms {} {:seon.schema/predicate-functions (::c/bound (:value predicates))}))
          reading (measure #(#'c/read-texts (into {} (map (fn [p] [p (#'c/read-file p)])) paths)))
          contexts (measure #(#'c/namespace-contexts (:value reading)))
          contracts (measure #(into [] (mapcat (fn [[path text]] (c/declared-contracts path (::c/forms text) (get (:value contexts) path @#'c/no-namespace-context)))) (:value reading)))
          compilation (measure #(if-let [compile-all (ns-resolve 'seon.contracts-compile-test 'contract-refusals)]
                                  (compile-all "." (:value projection) (:value contracts))
                                  (into [] (keep (partial c/contract-refusal "." (:value projection))) (:value contracts))))
          warm (measure #(c/check {::c/paths paths}))]
      {:schemas (count forms) :contracts (count (:value contracts))
       :distinct-contracts (count (set (map ::c/stored (:value contracts))))
       :findings (count (:value compilation)) :warm-findings (count (:value warm))
       :phases (mapv (fn [[phase result]] (assoc (dissoc result :value) :phase phase))
                     [[:resources resources] [:predicates predicates] [:projection projection]
                      [:reading reading] [:kondo contexts] [:contracts contracts]
                      [:compilation compilation] [:warm warm]])})))
;; The printer is loaded after measurement so it cannot warm predicate namespaces.
(require '[seon.render.value :as value])
(println (value/core-text (assoc observation :platform? (not (.isVirtual (Thread/currentThread)))
                               :files (count paths) :loading (dissoc loading :value))))
(shutdown-agents)
