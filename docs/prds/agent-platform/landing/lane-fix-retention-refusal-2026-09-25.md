---
type: landing
status: complete-unverified
lane: fix-retention-refusal-opus
created: 2026-09-25
authority: docs/prds/agent-platform/landing/lane-fix-test-shapes-2026-09-25.md (item 1)
---

# Lane fix-retention-refusal: the retention refusal returns instead of throwing

## Broken or not verified first

- **Not verified by execution.** No live root JVM answered: `bin/seon status` said
  "No live exact-root JVM; start the selected root first.", and MCP `runtime_status`
  listed no clusters. The REPL premise probe did not run, and no contract was
  compiled (the edit hook's advisory said so). Under the one-JVM and LOAD rules I
  booted nothing. The premise rests on source citations and the prior lane's
  recorded blob `1aa08310…`.
- **Lint.** clj-kondo shows 0 errors in the test. db.clj has one error,
  `parser.type/->Variable` at :673. It is stale dependency analysis and also appears
  at HEAD.
- **Before/after timing:** not measured, because no JVM was up. The change swaps
  `doseq`+throw for `some`+return over the same keys, so the cost is the same or
  lower (it stops at the first refusal).
- **RESET NEEDED:** none. Root must adopt src/seon/db.clj.

## Cause and fix

See docs/seon/issues/issue-test-retention-refusal-escapes-as-outcome-unknown.md.
In short, the validator threw a map with no `:seon.error/at`, so `transact-call`
treated it as an outcome-unknown panic. It now returns a db `diagnostic` with
`:seon.db/transaction-refused`, which Datahike carries as
`:datahike/validation-refusal`. Class audit: the only other throw in the
report-validator path is the owned-value refusal, and it is already caught and
returned (db.clj `write-owned-values-error`).

## Root runs

1. `bin/test-check default --test seon.error-write-timing-test/retention-validates-expanded-writes-and-retained-target-identities`
2. `bin/test-check default --ns seon.error-write-timing-test`
3. `bin/test-check default --changed seon.db/retention-check`

## Size and commits

- src/seon/db.clj: +27/−18. The test gains 3 lines. The issue and this note are
  docs.
- Commits: 2b1027b5d (fix + regression), plus the docs commit that follows it.

## Out of scope

- .agents/skills/datahike/SKILL.md:64-65 cites `src/seon/db.clj:4348-4351` for
  `write-report-validator` under `:tx-meta`. That code is now around db.clj:4417-4419,
  so the citation is stale. The skill belongs to another lane; I did not edit it.
- `:seon.error/expected` and `:seon.error/offending` are used across src, but
  resources/seon/schemas declares neither attribute. I left them out of this refusal.
  Their sibling users (for example, the arity refusal) should be audited.
- The retention refusal has no named error schema over `:seon.error/base`. Adding one
  (for example `:seon.db/retention-refused-error`) requires resources/seon/schemas/seon.db.edn,
  which this lane does not own.
