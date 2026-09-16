;; Read-only MCP JVM probe; run on the selected development cluster.
(require 'seon.operator 'seon.db 'seon.schema 'seon.fn 'seon.test)

(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)]
  (seon.schema/call-with-projection
   (seon.db/carried-projection database)
   (fn []
     {:seon.probe/basis-t (seon.db/basis-t database)
      :seon.probe/observation-count
      (count (seon.db/q '[:find [?test ...]
                         :where [?test :seon.test/fixture-observation]] database))
      :seon.probe/reached
      (vec
       (sort
        (seon.db/q
         '[:find ?namespace ?symbol
           :in $ % ?test-name [?namespace ...]
           :where [?test :seon.test/sym ?test-name]
           [?ns :seon.ns/name ?namespace]
           [?function :seon.fn/ns ?ns]
           [?function :seon.fn/sym ?symbol]
           (test-reaches ?test ?function)]
         database @#'seon.fn/test-reach-rules
         "my.examples-test/public-docstring-examples-run-in-the-canonical-agent-context"
         ['seon.render.value 'seon.cluster])))})))

;; Exact recurring regression invocation used before and after file edits.
;; Evaluate deliberately: this records one test result in default.
(comment
  (seon.test/run #'seon.dev.hook-test/idle-edit-starts-without-quiet-delay
                 (seon.operator/connection "default")))

;; Option 1: recurring canonical probes and a mixed real check after adoption.
(comment
  (seon.test/run #'seon.fn-test/fixture-observations-survive-static-and-runtime-admission
                 (seon.operator/connection "default"))
  (seon.test/run #'seon.test-reaching-test/declared-observations-defer-before-cheap-reaching-tests
                 (seon.operator/connection "default"))
  (let [connection (seon.operator/connection "default")
        result (seon.test/check
                {:seon.db/connection connection
                 :seon.test/changed
                 ["my.examples-test/public-docstring-examples-run-in-the-canonical-agent-context"
                  "seon.id-test/data-shape-and-explicit-length-determine-identity"]})]
    {:seon.probe/check result :seon.probe/feedback (seon.test/feedback result)}))
