(ns probe-shape-generation-cache
  "Surface: seon.schema/!shape-generation — the process-global, SINGLE-generation
   compiled validator/explainer cache.

   Hypothesis: two isolated environments holding DIVERGENT projections share one
   compiled-shape cache, and `ensure-shape-generation-for!` checks identity and
   then RE-DEREFS the atom non-atomically, so one environment can read the other
   environment's validator for the same schema key."
  (:require [seon.schema :as schema]))

(set! *warn-on-reflection* true)

;; Two divergent declarations of ONE key. A value with :probe/marker "a" is
;; valid under A and invalid under B, and vice versa.
(def forms-a
  {:probe/marker [:= "a"]
   :probe/thing [:map [:probe/marker :probe/marker]]})

(def forms-b
  {:probe/marker [:= "b"]
   :probe/thing [:map [:probe/marker :probe/marker]]})

(defn- projection [extra]
  (schema/build-projection
   (merge ((requiring-resolve 'seon.schema.edn/packaged-forms)) extra)))

(defn run
  [{:keys [iterations] :or {iterations 4000}}]
  (let [pa (projection forms-a)
        pb (projection forms-b)
        value-a {:probe/marker "a"}
        value-b {:probe/marker "b"}
        matches? (fn [p v]
                   (boolean
                    (some #(= :probe/thing (:seon.schema/key %))
                          (schema/matching-shapes-in p v))))
        wrong (atom [])
        run-side (fn [p good bad label]
                   (fn []
                     (dotimes [i iterations]
                       (when-not (matches? p good)
                         (swap! wrong conj
                                {:probe/side label :probe/iteration i
                                 :probe/expected :match :probe/got :no-match}))
                       (when (matches? p bad)
                         (swap! wrong conj
                                {:probe/side label :probe/iteration i
                                 :probe/expected :no-match
                                 :probe/got :match})))))
        futures [(future ((run-side pa value-a value-b :a)))
                 (future ((run-side pb value-b value-a :b)))]]
    (run! deref futures)
    {:probe/name 'probe-shape-generation-cache
     :probe/surface "seon.schema/!shape-generation (process-global single generation)"
     :probe/verdict (if (seq @wrong) :fail :pass)
     :probe/violations (count @wrong)
     :probe/first-violations (vec (take 5 @wrong))}))
