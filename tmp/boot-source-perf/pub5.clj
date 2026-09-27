;; Publish 5 edited source files (comment lines appended to the running archive) with phase times.
(let [dir (seon.fs/source-directory)
      files ["src/seon/eval.clj" "src/seon/render.clj" "src/seon/render/walk.clj" "src/seon/schema/datahike.clj" "src/seon/turn.clj"]
      _ (doseq [f files] (spit (str dir "/" f) (str ";; probe " (System/nanoTime) "\n") :append true))
      progress (atom [])
      t0 (System/nanoTime)
      r (binding [seon.cluster/*source-progress!* (fn [l] (swap! progress conj [(quot (- (System/nanoTime) t0) 1000000) (subs l 0 (min 70 (count l)))]))]
          (seon.cluster/refresh-source! "/Users/sean/src/seon/tmp/boot-source-perf/work-root" files nil))]
  {:ms (quot (- (System/nanoTime) t0) 1000000) :built? (:seon.source/built? r) :err (:seon.error/message r)
   :phases (filterv #(re-find #"source build|findings|program entities|reconciliation|complete|source identity" (second %)) @progress)})
