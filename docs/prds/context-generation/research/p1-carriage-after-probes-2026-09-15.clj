(require '[seon.operator]
         '[seon.db]
         '[seon.config]
         '[seon.sci.eval]
         '[seon.schema])

; Execute each form separately through MCP JVM mode on default (20 s bound).
; The first form makes one empty domain transaction to inspect its report.
(let [connection (seon.operator/connection "default")
      report (seon.db/transact! connection [])
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.threadId (Thread/currentThread))
      measure
      (fn [database]
        (let [before (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
              start (System/nanoTime)
              value (seon.db/pull database [:seon.agent/id] [:seon.agent/id "root"])]
          {:probe/bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid) before)
           :probe/ms (/ (- (System/nanoTime) start) 1000000.0)
           :probe/value value}))]
  {:probe/report-error (:seon.error/kind report)
   :probe/before-carried? (boolean (seon.db/carried-projection (:db-before report)))
   :probe/after-carried? (boolean (seon.db/carried-projection (:db-after report)))
   :probe/after-state? (boolean (:seon.sci.eval/projection-state (meta (:db-after report))))
   :probe/reads (mapv measure [(:db-after report) (:db-after report)])
   :probe/missing (measure @connection)})

; Pure candidate construction; neither declaration is installed.
(let [database (seon.db/db (seon.operator/connection "default"))
      projection (seon.db/carried-projection database)
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.threadId (Thread/currentThread))]
  (mapv
   (fn [[key definition]]
     (let [before (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
           start (System/nanoTime)
           candidate (seon.schema/projection-with-schema
                      projection key definition {:seon.schema.admission/source :agent})]
       {:probe/key key
        :probe/bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid) before)
        :probe/ms (/ (- (System/nanoTime) start) 1000000.0)
        :probe/definition (get (:seon.schema.projection/forms candidate) key)}))
   [[:my.p1-probe/score [:int {:min 0 :max 100}]]
    [:my.p1-probe/label [:string {:min 1}]]]))

; Real guarded SCI declarations in a private context acquired from default.
; Custody supplies lazy program reads; evaluate returns candidate rows only.
; No listener, default-context mutation, or schema transaction is created.
(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      projection (seon.db/carried-projection database)
      ctx (assoc (seon.schema/call-with-projection
                  projection #(seon.sci.eval/cluster-ctx database))
                 :seon.sci.eval/custody {:seon.db/connection connection})
      configuration (seon.schema/call-with-projection
                     projection #(seon.config/effective database "default"))
      request {:seon.sci.eval/ctx ctx
               :seon.db/db database
               :seon.sci.admit/caps (seon.config/result-caps configuration)
               :seon.sci.eval/time-limit-ms 5000
               :seon.config/on-core-error :panic}
      _ (seon.sci.eval/evaluate (assoc request :seon.cluster.eval/source "(+ 1 1)"))]
  (mapv (fn [source]
          (let [evaluation (seon.sci.eval/evaluate
                            (assoc request :seon.cluster.eval/source source))]
            (select-keys evaluation [:seon.sci.admit/value :seon.cluster.eval/error
                                     :seon.sci.admit/record])))
        ["(seon.schema/register! :my.dogfood/score [:int {:min 0 :max 100}])"
         "(seon.schema/register! :my.dogfood/label [:string {:min 1}])"]))

; Final read-only shape/refusal probe, hot-loaded default JVM, 2026-09-15.
(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      projection (seon.db/carried-projection database)
      queries ['[:find [(pull ?e [:seon.error/id]) ...]
                 :where [?e :seon.error/id _]]
               '[:find ?id . :where [_ :seon.agent/id ?id]]
               '[:find [?id] :where [_ :seon.agent/id ?id]]]
      warnings (java.io.StringWriter.)]
  {:probe/shapes
   (mapv (fn [query]
           (= (seon.schema/call-with-projection
               projection #(seon.db/q query @connection))
              (seon.schema/call-with-projection-state
               (atom {}) #(seon.db/q query database)))) queries)
   :probe/missing
   (binding [*err* warnings]
     (seon.schema/call-with-projection-state
      (atom {}) #(select-keys (seon.problems/problems @connection {})
                             [:seon.error/kind])))
   :probe/warnings (str warnings)})

; Expected final observation: shapes [true true true]; missing-projection;
; one warning naming seon.problems/problems. No transaction or agent mutation.
