(ns probe-parallel-environment-declarations
  "Surface: two parallel test environments each establishing their own
   declaration population and then calling the AMBIENT schema entry points
   (`seon.schema/matching-shapes`, `seon.schema/candidate-shapes`) exactly as
   ordinary production and test code does.

   Hypothesis: the ambient entry points route through the process-global
   single-generation shape cache, so two environments running at once observe
   each other's declarations."
  (:require [seon.schema :as schema]))

(set! *warn-on-reflection* true)

(def forms-a {:probe/marker [:= "a"]
              :probe/thing [:map [:probe/marker :probe/marker]]})

(def forms-b {:probe/marker [:= "b"]
              :probe/thing [:map [:probe/marker :probe/marker]]})

(defn- environment [extra body]
  (schema/call-with-forms
   (merge ((requiring-resolve 'seon.schema.edn/packaged-forms)) extra)
   body))

(defn- matches? [value]
  (boolean
   (some #(= :probe/thing (:seon.schema/key %))
         (schema/matching-shapes value))))

(defn run
  "Run two divergent environments concurrently through the ambient entry point."
  [{:keys [iterations] :or {iterations 1500}}]
  (let [wrong (atom [])
        timed (fn [f] (let [start (System/nanoTime)
                            _ (f)
                            elapsed (/ (- (System/nanoTime) start) 1e6)]
                        (double elapsed)))
        side (fn [extra good bad label]
               (fn []
                 (environment
                  extra
                  (fn []
                    (dotimes [i iterations]
                      (when-not (matches? good)
                        (swap! wrong conj {:probe/side label :probe/iteration i
                                           :probe/expected :match}))
                      (when (matches? bad)
                        (swap! wrong conj
                               {:probe/side label :probe/iteration i
                                :probe/expected :no-match})))))))
        ;; One environment alone: the global generation cache holds and every
        ;; call is a cache hit.
        single-ms (timed (side forms-a {:probe/marker "a"}
                               {:probe/marker "b"} :serial-a))
        serial-wrong (count @wrong)
        _ (reset! wrong [])
        futures [(future ((side forms-a {:probe/marker "a"}
                                {:probe/marker "b"} :a)))
                 (future ((side forms-b {:probe/marker "b"}
                                {:probe/marker "a"} :b)))]
        parallel-ms (timed (fn [] (run! deref futures)))]
    {:probe/name 'probe-parallel-environment-declarations
     :probe/surface
     "ambient seon.schema entry points over the global shape generation"
     :probe/verdict (if (seq @wrong) :fail :pass)
     :probe/serial-control-violations serial-wrong
     :probe/parallel-violations (count @wrong)
     :probe/first-violations (vec (take 5 @wrong))
     ;; Wall-clock only. The first environment pays projection build and JIT
     ;; warmup, so these are NOT a like-for-like thrash measurement and no
     ;; ratio is claimed from them.
     :probe/first-environment-wall-ms single-ms
     :probe/two-environments-wall-ms parallel-ms}))
