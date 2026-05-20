(ns ^:deferred seon.ai-test
  "Deferred per `docs/prds/datahike-migration/deferred.md`. The persistence
   call sites in `seon.ai` are currently no-op stubs. Restore tests —
   rewritten against the `:seon.ai` datahike namespace + `seon.db/transact!`
   — once persistence is wired in.")
