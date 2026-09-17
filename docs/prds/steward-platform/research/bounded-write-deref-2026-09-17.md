---
type: research
status: landed
created: 2026-09-17
tags: [seon.db, datahike, bounded-execution]
---

# Bounded Datahike writer deref

## Result

`seon.db/transact!` no longer parks indefinitely on Datahike's writer promise.
I read AGENTS.md §§0–5, `.agents/skills/datahike/SKILL.md`, and
`boot-and-load-sequence-2026-09-17.md` R4 and ranked fix 2 end to end before
the change.

The declared fact is `:seon.config.db/write-time-limit-ms`, an integer of at
least 1 ms with a shipped default of **30,000 ms**
(`resources/seon/schemas/seon.config.db.edn:2`, `config/default.edn:6-12`).
Like `:seon.config.db/validation-node-limit`, every asserted branch policy is
read from the database and the smallest value governs; before config facts
exist, the default comes from the declaration carried by the supplied schema
projection (`src/seon/db.clj:3737-3749`). The current database value governs a
submitted write. A transaction that changes this dial affects later writes,
not the wait already in progress.

## Dependency seam and refusal

Datahike's synchronous `transact` was an unbounded `@` of the promise returned
by `dw/transact!` (`reference-code/datahike/src/datahike/api/impl.cljc:44-46`).
Its JVM `throwable-promise` implements bounded deref through
`CompletableFuture.get(timeout, MILLISECONDS)`
(`reference-code/datahike/src/datahike/tools.cljc:93-109`). On expiry that
implementation wraps the JDK `TimeoutException` in `ExceptionInfo`; the live
JVM probe returned `{:class clojure.lang.ExceptionInfo, :cause-class
java.util.concurrent.TimeoutException}`.

`transact-call` now invokes `d/transact!`, derefs that exact promise with the
declared bound, recognizes only the dependency's timeout cause, and preserves
all delivered writer errors (`src/seon/db.clj:3750-3772`). A firing bound
returns one `seon.error/diagnostic` with:

- kind and cause `:seon.db/write-bound-exceeded`;
- layer `:database-write` and operation `seon.db/transact!`;
- connection identity and Datahike branch;
- `:seon.config.db/write-time-limit-ms` and measured
  `:seon.db/write-wait-elapsed-ms`;
- `:seon.db/transaction-outcome-unknown true`.

The caller may assume only that Seon stopped waiting. Datahike has already
queued the invocation and bounded deref does not cancel it: the writer may
still commit and deliver later (`reference-code/datahike/src/datahike/writer.cljc:147-218`,
`:395-417`). The caller must not infer rollback or retry blindly. This is also
stated on the public `transact!` docstring (`src/seon/db.clj:3998-4002`).

## Regression and verification

The canonical fixture regression replaces its branch connection's writer with
a real Datahike `LocalWriter` constructed through the dependency's
`:write-fn-map` seam. Its real `transact!` operation announces entry, waits on
a bounded latch, and therefore cannot deliver within the test's 25 ms config
fact (`test/seon/db_test.clj:1132-1199`). There are no sleeps. Every event wait
uses `seon.test-support/await-event!`. The assertions prove the typed refusal,
bound, elapsed wait, connection/branch evidence, unchanged basis at firing,
and the later commit that makes the outcome genuinely unknown.

Fast overlay evidence, isolated at HEAD plus this lane's four implementation
paths because concurrent edits made the shared-tree overlay inadmissible:

- `seon.db-test`: **56 tests, 424 assertions, 0 failures, 0 errors**.
- requested combined selection: **77 tests, 531 assertions, 4 failures,
  0 errors**. All four are one foreign symbol-migration boundary in
  `seon.config-test`: expectations compare string function names with the
  qualified symbols now returned for `seon.config/compile-manifest`,
  `seon.config/apply!`, and `seon.config/effective`. The database-config
  completeness assertion is green. This lane did not edit that foreign test
  or the protected symbol-owner paths.

The orchestrator still owes the cold `bin/test --paths ...` and platform
proofs. **RESET NEEDED:** this slice adds a stored schema/config dial; batch a
destructive refork of `default` with the other pending schema changes. This
lane did not stop, refork, or restart `default`.

The source/config/test patch is **14,790 bytes** by `git diff --binary`, with
175 inserted and 24 removed lines across the four implementation paths.
