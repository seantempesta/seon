(let [target (ns-resolve 'seon.render.web 'reusable-source-run-id)
      original @target
      source-run (ns-resolve 'seon.render.web 'source-run)
      assigned-namespace (ns-resolve 'seon.render.web 'assigned-agent-namespace)
      evaluation-evidence (ns-resolve 'seon.render.web 'evaluation-read-evidence)
      current-reads? (ns-resolve 'seon.db 'read-evidence-current?)
      observations (atom [])
      observed (promise)
      record-call
      (fn [request current source]
        (let [result (original request current source)]
          (when (and (< (count @observations) 20)
                     (or (.contains ^String source "32367")
                         (.contains ^String source "whoami")))
            (let [previous (get (:seon.render/retained-calls request)
                                (:seon.render.call/id request))
                  previous-id (:seon.render.call/source-run-id previous)
                  database (:seon.db/db request)
                  run (when previous-id (source-run database previous-id))
                  current-ns (assigned-namespace database (:seon.cluster.agent/id request))
                  starting-ns (get-in run [:seon.cluster.run/starting-ns :seon.ns/name])
                  evidence (when previous-id (evaluation-evidence database previous-id))
                  checks
                  {:seon.dev.preview-reuse/same-source
                   (= source (:seon.render.call/source previous))
                   :seon.dev.preview-reuse/same-producer
                   (= (get-in current [:seon.render.call/static-evidence :seon.render.call/producer])
                      (get-in previous [:seon.render.call/static-evidence :seon.render.call/producer]))
                   :seon.dev.preview-reuse/program-present (some? (:seon.render/program-snapshot current))
                   :seon.dev.preview-reuse/projection-present (some? (:seon.render/projection current))
                   :seon.dev.preview-reuse/same-program
                   (identical? (:seon.render/program-snapshot current) (:seon.render/program-snapshot previous))
                   :seon.dev.preview-reuse/same-projection
                   (identical? (:seon.render/projection current) (:seon.render/projection previous))
                   :seon.dev.preview-reuse/same-agent
                   (= (:seon.cluster.agent/id request) (get-in run [:seon.cluster.run/agent :seon.cluster.agent/id]))
                   :seon.dev.preview-reuse/namespace-present (some? current-ns)
                   :seon.dev.preview-reuse/same-namespace (= current-ns starting-ns)
                   :seon.dev.preview-reuse/evidence-vector (vector? evidence)
                   :seon.dev.preview-reuse/current-reads
                   (and (vector? evidence) (current-reads? database evidence))}]
              (swap! observations conj
                     (merge checks
                            {:seon.dev.preview-reuse/source source
                             :seon.dev.preview-reuse/call-id (:seon.render.call/id request)
                             :seon.dev.preview-reuse/previous-run previous-id
                             :seon.dev.preview-reuse/result result
                             :seon.dev.preview-reuse/current-namespace current-ns
                             :seon.dev.preview-reuse/starting-namespace starting-ns
                             :seon.dev.preview-reuse/read-count (when (vector? evidence) (count evidence))}))
              (when previous-id (deliver observed true))))
          result))]
  (with-redefs-fn {target record-call}
    #(do
       (deref observed 10000 :seon.dev.preview-reuse/timed-out)
       @observations)))
