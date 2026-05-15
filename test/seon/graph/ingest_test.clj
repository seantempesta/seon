(ns ^:deferred seon.graph.ingest-test
  "Deferred per `seon/docs/prds/datahike-migration/deferred.md`
   §'Static-ingest path'. The pre-port test (`git show
   212ffc2:test/seon/graph/ingest_test.clj`) tests `ingest-analysis!`
   end-to-end against a temp datalevin conn; the same lookup-ref
   strictness issue against datahike means the test's subject is
   what needs the fix, not the test. Restore body from git when
   the static-ingest path is revived.")
