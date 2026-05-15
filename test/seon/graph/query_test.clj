(ns ^:deferred seon.graph.query-test
  "Deferred per `seon/docs/prds/datahike-migration/deferred.md`
   §'Static-ingest path'. The pre-port test (`git show
   212ffc2:test/seon/graph/query_test.clj`) seeds the fixture with
   `analyzer/analyze-project!` + `ingest/ingest-analysis!` over
   `src/seon/graph/` then exercises call-graph,
   functions-with-output-key, transitive-dependents-of. Restore
   body from git when the static-ingest path is revived.")
