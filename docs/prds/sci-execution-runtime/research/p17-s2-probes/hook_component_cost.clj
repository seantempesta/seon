;;; P17 S2 — where the empty-plan hook's own work goes.
;;;
;;; The paired benchmark beside this file measures the hook THROUGH sci, so
;;; its numbers include sci's hook-consultation node. This one calls the hook
;;; directly, with no node around it, and times each component separately, so
;;; the two together say how much of the tax is ours and how much is the cost
;;; of consulting any hook at all.
;;;
;;; Run: clojure -M:dev:test -i \
;;;   docs/prds/sci-execution-runtime/research/p17-s2-probes/hook_component_cost.clj
(require '[datahike.core :as dh]
         '[seon.call-preparation :as cp]
         '[seon.db :as db]
         '[seon.env :as env]
         '[seon.schema :as schema]
         '[seon.test-support :as test-support])

(def iterations 2000000)

(defn plain
  "A callee declaring nothing suppliable."
  [x]
  x)

(defn- timed
  [label thunk]
  (dotimes [_ 200000] (thunk))
  (let [started (System/nanoTime)
        sink (loop [n iterations acc 0]
               (if (zero? n)
                 acc
                 (recur (dec n) (unchecked-add acc (hash (thunk))))))]
    (println label
             (format "%.0f ns" (double (/ (- (System/nanoTime) started)
                                          iterations)))
             (str "(sink " sink ")"))))

(test-support/with-database
 (fn [connection]
   (let [projection (schema/declaration-projection
                     (schema/declaration-population))
         environment
         (env/refuse-incomplete-environment!
          (env/environment {:seon.boot/cluster-name "p17s2-components"
                            :seon.db/connection connection
                            :seon.schema/projection projection}))
         ctx (-> {:env (atom {}) :seon.schema/projection projection}
                 (env/carry environment)
                 (cp/install))
         state (get ctx cp/carrier)
         database (dh/db connection)]
     (timed "env/of         " #(env/of ctx))
     (timed "seon.db/db     " #(db/db connection))
     (timed "datahike/db    " #(dh/db connection))
     (timed "basis-t        " #(db/basis-t database))
     (timed "callee-identity" #(cp/callee-identity #'plain))
     (timed "snapshot-check " #(cp/current-snapshot state database projection))
     (timed "whole hook     " #(cp/hook ctx #'plain [1])))))
(System/exit 0)
