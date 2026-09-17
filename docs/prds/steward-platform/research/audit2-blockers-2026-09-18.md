---
type: research
status: complete
created: 2026-09-18
tags: [audit, contracts, database, turn, cluster]
---

# Audit 2 refused-read blockers

This bounded lane read AGENTS.md sections 0–7, the error-entities PRD §§1,
4.1, and 4.2, and `critical-findings-triage-2026-09-17.md` end to end. It
landed triage rows #2, #6, #12, #8, and #13. The implementation diff across
the four owned source/test paths is 29,526 bytes: 349 inserted and 125 deleted
lines.

## Landed repairs

- #2, commits `92d644cdc`, `47f0bc9c1`, and `b451313cb`:
  `seon.turn/current-run` and `require-open-run` preserve a pull refusal as
  exception transport whose `ex-data` is the original error. `open?` and the
  two private read contracts require the turn identity, so an error map cannot
  satisfy them. Their row contracts describe the identity-bearing pull shape,
  not the stored entity schema: a wildcard pull represents cardinality-many
  component refs as vectors. Owners are `src/seon/turn.clj:214`, `:313`, and
  `:324`; regression `test/seon/turn_test.clj:883` also proves the connection's
  basis transaction is unchanged.
- #6, commit `820d0ab60`: `max-episode-runs` and `opening-deferred?` check
  every intermediate value with `seon.error/error?`; `next-agent-work`
  declares and returns the same error value. No arithmetic sees the error.
  Owners are `src/seon/turn.clj:2672`, `:2703`, and `:2822`; regression
  `test/seon/turn_test.clj:900` poisons only the issue-budget query while every
  surrounding read uses the real database.
- #12, commit `da73fcd28`: `declaration-written-by-run?` distinguishes a
  refused historical view and refused query from a successful receipt lookup.
  Declaration divergence transports either refusal verbatim instead of
  coercing it to `true`. Owner `src/seon/turn.clj:1116`; regression
  `test/seon/turn_test.clj:922`.
- #8, commit `9d91b2422`: `recover-runs!` returns a refused open-turn query
  unchanged. `stand-cluster-runtime!` aborts boot with that same error as
  `ex-data`; under `:panic`, an unreadable recovery decision therefore fails
  cluster boot instead of publishing a degraded successful recovery. Owners
  `src/seon/cluster.clj:2620` and `:3432`; regression
  `test/seon/cluster_test.clj:16` proves no transaction committed.
- #13, commit `a3f4870a9`: `missing-process-rows` and
  `closure-fact-missing` check query results before set construction. Their
  population/activation callers abort with the original error before a
  process row or missing-fact vector can be fabricated. Owners
  `src/seon/cluster.clj:1088` and `:1481`; separate shape regressions are
  `test/seon/cluster_test.clj:32` and `:45`.

All new checks use only `seon.error/error?`. No error is relabelled. The
writer and boot exceptions are transport only; their `ex-data` is byte-for-byte
the read error returned by `seon.db`.

## Verification

The required `--paths` run in the shared tree refused its overlay because the
published graph named held caller files `src/seon/db.clj`, `src/seon/fn.clj`,
`test/seon/cluster/source_test.clj`, and `test/seon/fn_test.clj`. A plain fast
run then encountered those lanes' in-flight symbol/reset fixture failures.
Verification therefore moved to a clean HEAD worktree with linked
`reference-code`; `default` was never stopped, restarted, or armed.

The exact requested clean-snapshot command armed 1,172 contracts in `:panic`
mode and ran 69 tests / 574 assertions. All six owned regressions passed:
three turn reads, boot recovery, and the two set-building shapes. The total
was 11 failures / 10 errors, all outside the new tests. The observed foreign
boundary was reset-batch fixture drift: older rows omit required
`:seon.ns/name`, retain string values where qualified symbols are now
required, supply unresolved `:seon.config/agent` refs, and expect the old
schema-row population. The same run initially exposed the pulled-versus-stored
contract mistake in #2; the two small follow-up commits above corrected it.

After that correction, a focused clean-snapshot `seon.turn-loop-test` run
armed the same 1,172 contracts and ran 26 tests / 100 assertions. The
previous `current-run`/`require-open-run` contract failures disappeared and
`one-wake-cannot-open-a-second-turn-after-the-first-closes` passed. Its total
was 1 failure / 2 errors, the same foreign boundary: two unresolved
`:seon.config/agent` fixture refs and one stale string expectation for the now
symbolic `:seon.fn/sym` value.

The first clean snapshot predated `02cb1b2b7` and hit the then-declared
30-second transaction bound while building the canonical program fixture.
After that foreign commit raised the declared write bound to 600 seconds, the
same snapshot construction completed. No cold gate, `--all`, or `--full` was
run; cold/platform proof remains the orchestrator's responsibility.
