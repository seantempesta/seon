;; Evaluate in the selected cluster's host JVM, with explicit custody.
(require 'seon.operator 'seon.db 'seon.schema 'seon.config
         'seon.instrument 'seon.schema.edn)
(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      projection (seon.schema/projection-from-database database)
      configuration (seon.config/effective database "default")]
  (seon.schema/call-with-projection
   projection
   (fn []
     (require 'seon.schema.edn :reload)
     (let [armed (seon.instrument/apply!
                  {:seon.config/on-core-error (:seon.config/on-core-error configuration)
                   :seon.schema/projection projection})
           timings (mapv
                    (fn [_]
                      (let [start (System/nanoTime)
                            forms (seon.schema.edn/packaged-forms)]
                        {:forms (count forms)
                         :elapsed-ms (/ (- (System/nanoTime) start) 1e6)}))
                    (range 4))]
       {:armed armed :timings timings}))))
