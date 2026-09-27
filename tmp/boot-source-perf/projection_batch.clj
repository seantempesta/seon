(let [tag (str "r" (System/currentTimeMillis))
      conn (seon.cluster.boot/connection "default")
      ms (fn [t] (/ (Math/round (/ (- (System/nanoTime) t) 1e4)) 100.0))
      cache-count #(count @@(ns-resolve 'seon.db 'projection-cache))
      row (fn [db k] (seon.program/declaration-row (seon.db/carried-projection db)
                                                  {:seon.schema/key k :seon.schema/form "[:string]"} :all :agent))
      ;; a committed registration, timed before the batch
      commit! (fn [k] (let [t0 (System/nanoTime)
                            r (seon.db/transact! conn [(row (seon.db/db conn) k)])
                            write-ms (ms t0)
                            t1 (System/nanoTime)
                            _ (seon.db/carried-projection (seon.db/db conn))]
                        {:write-ms write-ms :projection-ms (ms t1) :error (:seon.error/message r)}))
      warm (commit! (keyword (str "probe.batch." tag) "warm"))
      before-cache (cache-count)
      t0 (System/nanoTime)
      batch (loop [db (seon.db/db conn) i 0 times []]
              (if (= i 22)
                times
                (let [t (System/nanoTime)
                      next (seon.db/with db [(row db (keyword (str "probe.batch." tag) (str "k" i)))])]
                  (if (:seon.error/at next)
                    (conj times {:error (:seon.error/message next)})
                    (recur next (inc i) (conj times (ms t)))))))
      batch-ms (ms t0)
      after-cache (cache-count)
      after (commit! (keyword (str "probe.batch." tag) "after"))]
  {:warm-commit warm :batch-ms batch-ms :batch-step-ms batch
   :projection-cache {:before before-cache :after after-cache}
   :commit-after-batch after})
