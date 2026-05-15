(ns ^:deferred seon.ai-test
  "Deferred per `seon/docs/prds/datahike-migration/deferred.md`
   §'seon.ai.claude + seon.ai requiring-resolve stubs'. The pre-port
   test (`git show 212ffc2:test/seon/ai_test.clj`) exercised
   `seon.ai/datalevin-write!` + the requiring-resolve call sites,
   both of which are now FIXME(M-3) no-op stubs.

   Restore body — rewritten against the new `:seon.ai` datahike
   namespace + `seon.db/transact!` — when chunk M-3 lands.")
