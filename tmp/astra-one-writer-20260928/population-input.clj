;; Evaluate in scratch.astra.one-writer via MCP JVM mode, agent astra-one-writer.
;; Captures current canonical rows once; it changes no default program Vars.
(do
  (require '[seon.cluster :as cluster] '[seon.cluster.source :as source]
           '[seon.fn :as index] '[seon.fs :as fs] '[seon.schema :as schema]
           '[seon.schema.edn :as schema.edn] '[seon.id :as id])
  (def population-input
    (future
      (let [started (System/nanoTime)
            forms (schema.edn/packaged-forms)
            projection (schema/declaration-projection forms)
            directory (fs/source-directory)
            inputs (source/path-digests directory (source/discover-paths directory cluster/source-roots))
            rows (schema/call-with-forms
                  forms
                  #(schema/call-with-projection
                    projection
                    (fn []
                      (#'index/desired-entities
                       {:seon.fn/root directory :seon.fn/roots index/source-roots
                        :seon.source/relative-file-digests inputs}
                       (constantly nil)))))]
        (spit "/Users/sean/src/seon/tmp/astra-one-writer-20260928/live-population.edn"
              (id/printed {:forms forms :rows rows}))
        {:rows (count rows) :ms (/ (- (System/nanoTime) started) 1e6)})))
  {:started true})
