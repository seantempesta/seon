;; Read-only JVM forms, each evaluated separately through MCP on default.
;; Dated evidence: this pre-reset graph stores function names as strings
;; and call edges as refs. Do not treat those spellings as the target schema.

(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      rows (seon.db/q
            '[:find ?sym ?private :in $
              :where [?f :seon.fn/sym ?sym] [?f :seon.fn/spec _]
              [(get-else $ ?f :seon.fn/private? false) ?private]]
            database)
      armed (seon.instrument/instrumented)]
  (if (:seon.error/kind rows)
    rows
    {:basis (seon.db/basis-t database)
     :spec-counts (frequencies (map second rows))
     :private-specs
     (mapv (fn [[sym _]]
             (let [sym (if (symbol? sym) sym (symbol sym))
                   v (when (find-ns (symbol (namespace sym))) (find-var sym))]
               {:sym sym :loaded (some? v) :armed (contains? armed v)}))
           (sort-by first (filter second rows)))
     :armed-total (count armed)}))

(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      rows (seon.db/q
            '[:find ?sym ?spec ?callee
              :in $ [?callee ...]
              :where
              [?function :seon.fn/private? true]
              [?function :seon.fn/sym ?sym]
              [?function :seon.fn/calls ?target]
              [?target :seon.fn/sym ?callee]
              [(get-else $ ?function :seon.fn/spec "") ?spec]]
            database
            ["seon.db/pull" "seon.db/q" "seon.db/entity"
             "seon.db/pull-many" "seon.db/transact!"])]
  (if (:seon.error/kind rows)
    rows
    (let [functions (group-by first rows)
          read-rows (remove #(= "seon.db/transact!" (nth % 2)) rows)]
      {:basis (seon.db/basis-t database)
       :private-read-consumers (count (set (map first read-rows)))
       :uncontracted-private-read-consumers
       (count (set (map first (filter #(= "" (second %)) read-rows))))
       :private-read-or-write-consumers (count functions)
       :uncontracted-private-read-or-write-consumers
       (count (filter (fn [[_ calls]] (= "" (second (first calls)))) functions))
       :by-namespace
       (into (sorted-map)
             (frequencies (map #(namespace (symbol %)) (keys functions))))})))

(let [original {:seon.error/kind :private-contracts/failed-read
                :seon.error/message "The read was refused."}
      outcome (try
                {:transport :return :data (#'seon.fs.jvm/glob original {})}
                (catch clojure.lang.ExceptionInfo failure
                  {:transport :exception :data (ex-data failure)}))]
  (assoc outcome :preserved (= original (:data outcome))))
