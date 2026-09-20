(require 'clojure.edn 'clojure.java.io 'clojure.string 'datahike.api
         'seon.cluster 'seon.cluster.source 'seon.db 'seon.fn 'seon.operator.runtime)

; Call the returned function through the scratch host's prepl with its
; cluster-root (ROOT/data/clusters), cluster name, and evidence output path.
(fn [root cluster output]
  (let [instance (get @seon.operator.runtime/running-instances cluster)
        store (:seon.store/store instance)
        published (seon.cluster.source/current store)
        artifact (clojure.edn/read-string (slurp (seon.cluster/source-artifact-file root)))
        previous (:seon.fn/manifest artifact)
        directory (:seon.fn.manifest/root previous)
        file (clojure.java.io/file directory "src/my/note.clj")
        original (slurp file)
        changed (clojure.string/replace-first original "Read my current notes in identity order."
                                              "Read my current notes in identity order (measurement).")
        phases (atom [])
        clock (atom [(System/nanoTime) "probe preparation"])
        progress (fn [phase]
                   (let [now (System/nanoTime)
                         [before completed] @clock
                         row {:seon.source/completed-phase completed
                              :seon.source/elapsed-ms (/ (- now before) 1000000.0)}]
                     (reset! clock [now phase])
                     (swap! phases conj row)
                     (prn row)
                     (flush)))
        database (seon.db/carry-projection-state
                  (seon.cluster.source/database store (:seon.source/commit-id published))
                  (get-in instance [:seon.sci.eval/ctx :seon.sci.eval/projection-state]))]
    (try
      (assert (= (:seon.source/digest artifact)
                 (seon.db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] database)))
      (assert (not= original changed))
      (progress "file digest walk")
      (let [snapshot (seon.cluster.source/snapshot
                      {:seon.fn/root directory
                       :seon.source/roots (:seon.fn.manifest/relative-roots previous)})
            before (into {} (map (juxt :seon.fn.file/relative-path :seon.fn.file/digest))
                         (:seon.fn.manifest/artifacts previous))]
        (progress "publication snapshot")
        (assert (= before (select-keys (:seon.source/relative-file-digests snapshot) (keys before)))
                "Publish the current tree before the one-file measurement.")
        (seon.cluster/source-snapshot seon.cluster/source-roots directory)
        (progress "docstring edit")
        (spit file changed)
        (progress "manifest entry")
        (let [manifest
              (with-bindings {#'seon.cluster/*source-progress!* progress}
                (seon.fn/build-manifest
                 {:seon.fn/root directory
                  :seon.fn/roots (:seon.fn.manifest/relative-roots previous)
                  :seon.fn/previous-manifest previous
                  :seon.source/previous-database database
                  :seon.fn.analyzer/cache-root (str (clojure.java.io/file root "build" "analysis"))
                  :seon.source/progress! @#'seon.cluster/report-source-progress!}))]
          (progress "probe complete")
          (let [result {:seon.source/commit-id (:seon.source/commit-id published)
                        :seon.fn.manifest/digest (:seon.fn.manifest/digest manifest)
                        :seon.source/progress @phases}]
            (spit output (pr-str result))
            result)))
      (finally
        (when (= changed (slurp file)) (spit file original))
        (datahike.api/release-materialized-db database)))))
