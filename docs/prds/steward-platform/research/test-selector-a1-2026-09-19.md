---
type: research
status: active
created: 2026-09-19
tags: [testing, selection, lane-a1]
---

# A1 — database test selection

## Boundary and grounding

Read AGENTS.md sections 0–5, the stage-1 review (including H1–H15 and
L0–L6), both test-system authorities, selection-efficiency research, and
the A0 and stage-1 landing notes end to end; read namespace-agents plan
sections 7–8. D7 and D9 supersede the older execution-policy wording.
Applied data-oriented-clojure, clojure-testing, repl, datahike and
data-modeling skills. No delegation or lifecycle operation.

Entry branch: `steward-platform`, HEAD `d4686f495`.
Inherited selector/test/runner/schema WIP is owned by this assignment;
the dirty `bin/test` is preserved and excluded from commits.
One read-only MCP JVM probe observed default alive, basis `536871554`,
loaded `seon.test/select`, and publication input digest
`a58de6d9c5fdc48f5179895f06df972cebb4948d9338ab83530735ecf3cb00bc`.
This is an inherited-system observation, not proof of later source adoption.

Dependency ledger:

- Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:1153`
  passes the writer's mid-transaction database to `:db.fn/call`.
- `reference-code/datahike/src/datahike/db.cljc:142` includes the as-of
  basis; `:149` excludes it for since.
- `reference-code/datahike/src/datahike/db/search.cljc:140` chooses EAVT
  for bound entities and AVET for indexed attribute/value pairs.
- `src/seon/fn.clj`, `gate-sets` request arity, owns the shared frontier.
- `src/seon/test/cache.clj`, `input-roots` and root-arity
  `test-input-digest`, own external input evidence (commit `40ddbfd87`).

## First convergence slice

The cold log `tmp/orchestrator/gates/a0-stage1-cold-4-2026-09-19.log`
reports two admission fixture refusals and one missing-count assertion.
The fixtures supplied an invented digest instead of the publication's
observed input digest; admission correctly compares those values at the
writer. They now query the actual input fact.

The SCI result's runtime-only `:seon.test.run/terminated?` entered
`record-latest-tx`'s test-row map. The cold log records the exact schema
refusal before the missing counts. The writer now excludes this request
observation from the stored test row; admitted member termination remains
owned by `complete-members`.

Verification in progress. No green or completion claim yet.

## Integration owed

The orchestrator owns the cold selected gate, platform proof, a successful
bare request, and a second unchanged bare request executing zero tests.
A0 still refuses bare requests lacking the real selection-authority
handoff; A1 alone cannot certify that A4 launcher integration.
