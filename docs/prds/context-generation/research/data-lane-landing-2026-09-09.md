---
type: research
status: working
tags: [schema, database, agent]
---

# Data lane — chart roadmap steps 3–7, 2026-09-09

Read end to end: the data chart PRD and raw-data-forms probe, active roadmap
and working edge; turn PRD §10 and §13–§15. AGENTS.md has no separate §10:
its opening copies the turn PRD lane rules. The owner's assignment overrides
the chart's older map-arity id example, singular runtime turn, and `to` wake.

## Dependency ledger

- Java MessageDigest SHA-256 through `src/seon/id.clj`; existing evaluation
  and digest callers retain their ordered-vector identities. The new entry
  hashes exactly `(pr-str data)`, default length 12.
- Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:640`
  resolves unique identity upserts; `:64` recognizes `"datomic.tx"`;
  `:738` expands nested maps; `:997` retracts entities and incoming refs.
  Existing first-party seams: `seon.plan/add-step-call`, message delivery,
  and `seon.turn/open-call`.
- Canonical fixture: `seon.test-support/with-database`; armed fast loop and
  isolated path-only gate. `SEON_TEST_WORKERS` capped at 3; final gates use 1 after the
  parallel-base loss was observed.

## Initial live evidence

Default PID 92059, PREPL 53086. MCP status returned health/Flow unknown,
`Read timed out`; JVM arithmetic returned 2 in 1 ms, immutable schema/plan
probe in 3 ms. Plan eid 36225. Completion was `db.type/instant`; item id
was `db.type/string` and `db.unique/identity`. Existing issue updated.

## Shared-tree boundary

Cookbook research probes, help trial, trial reports, trial test, and its
issues were already edited/untracked. They are excluded from this lane's
changes and gate snapshots. No foreign session is operated.

## Verification

First slice: the identity entry and its regression. Scoped gate and fast
iteration: 3 tests, 33 assertions, zero failures/errors (identity and plan API
snapshot). Explicit platform-only snapshot of the identity files also green.
Exact check: `(seon.id/id 'abc)` = `"ba7816bf8f01"`; length 8 = `"ba7816bf"`.
The independently progressing plan changes are not part of that identity
commit or its platform snapshot.

Identity platform: 83 tests / 490 assertions, zero failures/errors.
Plan fast iteration: 21 tests / 125 assertions, zero failures/errors.
The first isolated plan gate hit the recorded parallel-base filestore-key
loss; its isolated title-update confirmation passed. The separate shown-text
test used a base SCI context without agent call preparation. It now acquires
the agent context through the same `fork-for-turn` owner as production.
Final scoped and platform results follow below.

RESET NEEDED once when the complete schema batch lands; the owner
reforks default once. This lane uses `tmp/data-lane-root` for live proof.

Plan/component slice: scoped gate 22 tests / 134 assertions; platform 83
tests / 490 assertions, all green. The shown-text fixture now refuses
failed setup loudly and seeds its cluster through the canonical owner.
