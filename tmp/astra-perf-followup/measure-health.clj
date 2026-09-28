(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      compile-fn (fn [path]
                   (binding [*ns* (the-ns 'seon.health)]
                     (eval (cons 'fn (drop-while #(not (vector? %)) (drop 2 (read-string (slurp path))))))))
      before (compile-fn "/Users/sean/src/seon/tmp/astra-perf-followup/health-parent.clj")
      after (compile-fn "/Users/sean/src/seon/tmp/astra-perf-followup/health-candidate.clj")
      attrs [:seon.profile.measurement/sym :seon.profile.measurement/digest
             :seon.profile.measurement/p50 :seon.profile.measurement/p99
             :seon.profile.measurement/errors :seon.profile.measurement/calls :seon.profile.measurement/at]
      measure (fn [f]
                (let [sink (atom []) t (System/nanoTime)
                      result (binding [seon.db/*read-evidence-sink* sink]
                               (f database :seon.profile.measurement/id attrs))]
                  {:ms (/ (- (System/nanoTime) t) 1e6) :reads (count @sink) :value result}))
      pairs (mapv (fn [_] [(measure before) (measure after)]) (range 3))]
  {:measurements (count (:value (ffirst pairs)))
   :pairs (mapv (fn [[b a]] {:before (dissoc b :value) :after (dissoc a :value)
                            :equal (= (set (:value b)) (set (:value a)))}) pairs)})
