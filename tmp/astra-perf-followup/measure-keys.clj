(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      projection (seon.schema/projection-from-database database)
      mark (seon.profile/begin)
      files (into {} (seon.db/q '[:find ?path ?digest :where
                                 [?file :seon.fn.file/relative-path ?path]
                                 [?file :seon.fn.file/digest ?digest]] database))
      keys-start (System/nanoTime)
      schema-keys (#'seon.cluster/stored-derivation-schema-keys database (set (keys (:seon.schema.projection/forms projection))))
      keys-ms (/ (- (System/nanoTime) keys-start) 1e6)
      paths (set (keys schema-keys))
      code (apply str (repeat 64 "a"))
      compile-fn (fn [path]
                   (binding [*ns* (the-ns 'seon.cluster)]
                     (let [form (read-string (slurp path))
                           body (drop-while #(not (vector? %)) (drop 2 form))]
                       (eval (list 'let ['uncached (list 'fn ['_kind '_key '_weight 'derive] (list 'derive))]
                                   (clojure.walk/postwalk-replace {'cache/lookup 'uncached}
                                     (cons 'fn body)))))))
      before (compile-fn "/Users/sean/src/seon/tmp/astra-perf-followup/parent.clj")
      after (compile-fn "/Users/sean/src/seon/tmp/astra-perf-followup/candidate.clj")
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.threadId (Thread/currentThread))
      measure (fn [f]
                (let [bytes (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
                      started (System/nanoTime)
                      result (f code projection files schema-keys paths)]
                  {:ms (/ (- (System/nanoTime) started) 1e6)
                   :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid) bytes)
                   :value result}))
      pairs (mapv (fn [_] [(measure before) (measure after)]) (range 3))]
  {:source (seon.fs/source-directory)
   :files (count paths) :schemas (count (:seon.schema.projection/forms projection))
   :read-keys-ms keys-ms
   :pairs (mapv (fn [[b a]] {:before (dissoc b :value) :after (dissoc a :value) :equal (= (:value b) (:value a))}) pairs)
   :profile (seon.profile/explain mark 8)})
