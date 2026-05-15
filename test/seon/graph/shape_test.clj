(ns ^:deferred seon.graph.shape-test
  "Deferred per `seon/docs/prds/datahike-migration/deferred.md`
   §'Static-ingest path' + §'seon.test.bootstrap'. The pre-port
   test (`git show 212ffc2:test/seon/graph/shape_test.clj`) uses
   the deleted `seon.test.bootstrap/with-test-bootstrap` macro
   plus `seon.graph.extract/extract-graph-from-file` + ingest.
   Restore body — and port the `with-test-bootstrap` fixture to
   `tu/transact-full-graph!` — when the static-ingest path is
   revived.")
