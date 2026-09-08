(require '[malli.core] '[seon.schema] '[seon.schema.edn] '[seon.instrument])

;; Evaluate in the default JVM after reloading the owned namespaces.
;; The temporary host Var is removed in finally; no durable facts are changed.
(let [probe-ns (create-ns 'seon.registry.probe)
      candidate (intern probe-ns 'value
                        (fn [request] (:seon.registry.probe/value request)))
      forms (seon.schema.edn/packaged-forms)
      contract (fn [key]
                 [:=> [:cat [:map [:seon.schema/projection :map]
                                  [:seon.registry.probe/value key]]] key])
      a (seon.schema/build-projection
         (assoc forms :seon.registry.probe/left :int)
         {'seon.registry.probe/value (contract :seon.registry.probe/left)})
      b (seon.schema/build-projection
         (assoc forms :seon.registry.probe/right :string)
         {'seon.registry.probe/value (contract :seon.registry.probe/right)})
      refuses? (fn [f] (try (f) false (catch Exception _ true)))]
  (try
    (alter-meta! candidate assoc :malli/schema
                 [:=> [:cat :map] :seon.schema/value])
    (seon.instrument/apply! {:seon.schema/projection a
                            :seon.config/on-core-error :panic})
    (let [roots (into {} (map (juxt identity deref))
                      (seon.instrument/instrumented))]
      (seon.instrument/apply! {:seon.schema/projection b
                              :seon.config/on-core-error :record})
      (seon.instrument/remove!)
      {:wrapped (count roots)
       :same-wrappers (every? (fn [[v root]] (identical? root @v)) roots)
       :left-schema-absent-in-right
       (nil? (get (:seon.schema.projection/forms b) :seon.registry.probe/left))
       :global-function-schemas (count (malli.core/function-schemas))
       :global-schema-lookup-refuses
       (refuses? #(malli.core/schema :seon.registry.probe/left))
       :left (candidate {:seon.schema/projection a :seon.registry.probe/value 7})
       :right (candidate {:seon.schema/projection b :seon.registry.probe/value "right"})
       :left-rejects-string
       (refuses? #(candidate {:seon.schema/projection a :seon.registry.probe/value "wrong"}))
       :right-rejects-int
       (refuses? #(candidate {:seon.schema/projection b :seon.registry.probe/value 7}))})
    (finally (remove-ns 'seon.registry.probe))))
