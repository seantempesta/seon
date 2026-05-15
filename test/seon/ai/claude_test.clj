(ns ^:deferred seon.ai.claude-test
  "Deferred per `seon/docs/prds/datahike-migration/deferred.md`
   §'seon.ai.claude + seon.ai requiring-resolve stubs'. The pre-port
   test (`git show 212ffc2:test/seon/ai/claude_test.clj`) exercised
   the Claude SDK message + session storage path through
   `seon.ai.datalevin`, all of which is now FIXME(M-3) stubbed.

   Restore body when chunk M-3 wires `:seon.ai` into the datahike
   flow + ports `seon.ai.claude`'s storage calls onto
   `seon.db/transact!`.")
