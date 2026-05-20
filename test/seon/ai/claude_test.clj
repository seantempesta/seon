(ns ^:deferred seon.ai.claude-test
  "Deferred per `docs/prds/datahike-migration/deferred.md`. The Claude SDK
   message + session storage path is currently stubbed. Restore body once
   `:seon.ai` is wired into the datahike flow and `seon.ai.claude`'s
   storage calls are ported onto `seon.db/transact!`.")
