(ns ^:deferred seon.graph.context-test
  "Deferred per `seon/docs/prds/datahike-migration/deferred.md`
   §'Static-ingest path'. The pre-port test (`git show
   212ffc2:test/seon/graph/context_test.clj`) populated the fixture
   via `analyzer/analyze-project!` + `ingest/ingest-analysis!` over
   `src/seon/graph/`; that path trips datahike's lookup-ref
   strictness against forward references. Restore body from git
   when the static-ingest path is revived.")
