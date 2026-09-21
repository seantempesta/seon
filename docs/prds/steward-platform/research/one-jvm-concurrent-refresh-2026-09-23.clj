(require '[clojure.java.io :as io] '[clojure.string :as str]
         '[seon.cluster :as cluster] '[seon.cluster.source :as source]
         '[seon.fs :as fs] '[seon.operator.runtime :as runtime]
         '[seon.schema :as schema] '[seon.test-support :as support])

; Run through the own scratch cluster's advertised prepl.
(fn [root cluster-name output]
  (let [instance (get @runtime/running-instances cluster-name)
        store (:seon.store/store instance)
        file (io/file (fs/source-directory) "src/my/note.clj")
        original (slurp file)
        changed (str/replace-first original "Read my current notes in identity order."
                                   "Read my current notes in identity order (concurrent publication).")
        start (java.util.concurrent.CountDownLatch. 1)
        entered (java.util.concurrent.CountDownLatch. 2)]
    (assert (not= original changed))
    (try
      (spit file changed)
      (let [requests
            (mapv (fn [_]
                    (future
                      (.countDown entered)
                      (support/await-event! start "concurrent refresh start")
                      (schema/call-with-projection-state
                       (get-in instance [:seon.sci.eval/ctx :seon.sci.eval/projection-state])
                       #(cluster/refresh-source! (str (io/file root "data" "clusters"))
                                                 ["src/my/note.clj"] cluster-name))))
                  (range 2))]
        (try
          (support/await-event! entered "both refresh requests entered")
          (.countDown start)
          (let [replies (mapv #(support/await-event! % "refresh completed") requests)
                head (:seon.source/commit-id (source/current store))
                result {:seon.source/commit-id head
                        :seon.source/replies
                        (mapv #(select-keys % [:seon.source/commit-id :seon.source/built?]) replies)}]
            (assert (= [head head] (mapv :seon.source/commit-id replies)))
            (assert (= #{true false} (set (map :seon.source/built? replies))))
            (spit output (pr-str result))
            result)
          (finally
            (.countDown start)
            (doseq [request requests]
              (when-not (realized? request) (future-cancel request))))))
      (finally (when (= changed (slurp file)) (spit file original))))))
