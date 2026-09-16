; Run through development MCP in JVM mode on default after adoption.
; This records two deliveries through the real cluster fault committer.
(do
  (require 'seon.operator 'seon.cluster 'seon.cluster.registry 'seon.config
           'seon.db 'seon.error 'seon.id)
  (let [connection (seon.operator/connection "default")
        instance (#'seon.cluster/mcp-instance "default")
        process (seon.cluster/process-identity (:seon.boot/advertisement instance))
        database (seon.db/db connection)
        dials (seon.config/effective database "default")
        failure (try (seon.id/valid? 0 "error-graph-live")
                     (catch Throwable failure failure))]
    (assert (instance? Throwable failure) "The real contract must be armed.")
    (let [outcome #(vector (first %) (second %))
          outcomes (mapv (fn [_]
                           (outcome (#'seon.cluster/commit-fault!
                                     connection "default" process
                                     (seon.config/result-caps dials) failure)))
                         (range 2))
          signature (:seon.error/signature (ffirst outcomes))
          database (seon.db/db connection)]
      {:outcomes (mapv second outcomes)
       :error (seon.db/pull
               database
               '[:db/id :seon.error/signature :seon.error/kind :seon.error/frame
                 :seon.error/exception-class
                 {:seon.error/fn [:db/id :seon.fn/sym
                                  {:seon.fn/ns [:seon.ns/name]}]}
                 {:seon.error/occurrences
                  [:seon.error.occurrence/id :seon.error.occurrence/count
                   :seon.error.occurrence/first-at :seon.error.occurrence/last-at
                   {:seon.error.occurrence/process [:seon.db.process/id]}]}]
               [:seon.error/signature signature])
       :adopted (:seon.source/commit-id (seon.db/pull database [:seon.source/commit-id]
                                                     [:seon.cluster/name "default"]))
       :published (seon.cluster.registry/connection-branch-commit-id connection :current-src)})))
