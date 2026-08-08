;;; P17 S2 — the MANDATORY empty-plan hot-path benchmark.
;;;
;;; The call-preparation hook is armed on EVERY call in a cluster, so the
;;; path that decides whether the seam is affordable is the one taken by a
;;; callee that declares nothing suppliable. This measures that path against
;;; the same calls with no hook installed at all, in one session, on one
;;; fixture database, consuming every result so nothing is elided.
;;;
;;; Three sites, each a raw compiled host Var in a callee position (the
;;; ordinary production binding shape):
;;;
;;;   baseline   — no `:call-preparation-hook` on the context
;;;   empty-plan — hook installed, callee absent from the snapshot's
;;;                `prepared-symbols` gate
;;;   prepared   — hook installed, one positional slot supplied per call
;;;                (`seon.call-preparation-test/probe-received-database?`,
;;;                 a real program-graph identity in the fixture database)
;;;
;;; Run: clojure -M:dev:test -i \
;;;   docs/prds/sci-execution-runtime/research/p17-s2-probes/empty_plan_hot_path.clj
(require '[sci.core :as sci]
         '[seon.call-preparation :as cp]
         '[seon.db :as db]
         '[seon.env :as env]
         '[seon.schema :as schema]
         '[seon.test-support :as test-support])

(def iterations 2000000)

(defn plain
  "A callee declaring nothing suppliable — the empty-plan site."
  [x]
  x)


(def rows
  [{:seon.call-preparation/key :seon.db/db
    :seon.call-preparation/schema [:seon.schema/key :seon.db/database-value]
    :seon.call-preparation/supplier
    [:seon.fn/sym "seon.db/supplied-database-value"]}])

(defn- projection []
  (schema/declaration-projection (schema/declaration-population)))

(defn- timed
  [label thunk]
  ;; Warm, then measure; consume the result so nothing is elided.
  (dotimes [_ 200000] (thunk))
  (let [started (System/nanoTime)
        sink (loop [n iterations acc 0]
               (if (zero? n)
                 acc
                 (recur (dec n) (unchecked-add acc (hash (thunk))))))
        elapsed (- (System/nanoTime) started)]
    (println label
             (format "%.0f ns/call" (double (/ elapsed iterations)))
             (str "(sink " sink ")"))))

(test-support/with-database
 (fn [connection]
   (db/transact! connection rows)
   (let [namespaces
         {'my {'plain #'plain}
          'seon.call-preparation-test
          {'probe-received-database?
           (requiring-resolve
            'seon.call-preparation-test/probe-received-database?)}}
         environment
         (env/refuse-incomplete-environment!
          (env/environment {:seon.boot/cluster-name "p17s2-benchmark"
                            :seon.db/connection connection
                            :seon.schema/projection (projection)}))
         armed (-> (sci/init (assoc {:namespaces namespaces}
                                    :call-preparation-hook cp/hook))
                   (assoc :seon.schema/projection (projection))
                   (env/carry environment)
                   (cp/install))
         bare (sci/init {:namespaces namespaces})
         ;; Build the caller ONCE and invoke it, so the measurement is the
         ;; call path and not sci's analyzer. An interpreted fn carries its
         ;; creation context, which is the context whose hook it consults.
         run (fn [ctx source] (sci/eval-string* ctx (str "(fn [] " source ")")))
         gate (:seon.call-preparation/prepared-symbols
               (cp/current-snapshot (get armed cp/carrier)
                                    @connection (projection)))]
     (println :prepared-symbols (count gate))
     (println :gate-miss? (not (contains? gate (cp/callee-identity #'plain))))
     (println :prepared-site-in-gate?
              (contains? gate "seon.call-preparation-test/probe-untouched"))
     (timed "baseline  " (run bare "(my/plain 1)"))
     (timed "empty-plan" (run armed "(my/plain 1)"))
     (timed "prepared  "
            (run armed
                 "(seon.call-preparation-test/probe-received-database? \"a\")")))))
(System/exit 0)
