(ns ^:deferred seon.dev.test-select-test
  "Deferred per `seon/docs/prds/datahike-migration/deferred.md`
   §'Static-ingest path'. The pre-port test (`git show
   212ffc2:test/seon/dev/test_select_test.clj`) populates
   `:seon.runtime` via the analyzer + ingest path then exercises
   `affected-namespaces` / `run-affected-tests!` against the
   resulting graph. Restore body when the static-ingest path is
   revived.

   Note: `seon.dev.test/test-affected` uses `has-db? false` so the
   production fallback path (run-only-this-ns-test) works today —
   that's sufficient until the affected-tests UX matters again.")
