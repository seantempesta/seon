---
type: issue
status: open
severity: cleanup
tags: [issue, agent, web, schema]
---

# Delete consumers of attributes removed with the run and turn stacks

## Problem

The run and turn cuts removed three schema families while production readers
survived. The post-cut source used 36 distinct unregistered attributes: 24
`:seon.ai.attempt/*`, seven old run-policy attributes, and five old turn-stack
attributes. Datahike reads therefore silently returned empty or nil results,
and the web evidence projection validated and sorted rows that no current
writer could produce.

## Evidence

Commit `f6f6673b6` deleted the turn and attempt registrations at parent
`src/seon/agent/turn.cljs:46-100,156-261`. Commit `bd12fdc7d` deleted the old
run registrations at parent `src/seon/agent/run.cljs:28-110`.

Measured on 2026-07-26 after commit `42a9faf2e`:

- `rg -o --no-filename --pcre2
  ':seon\.ai\.attempt/[\w?!*+.-]+' src | sort -u` returns 24 names. The
  previously reported count of 25 does not reproduce against current source;
  `src` plus `test` returns 27 because tests additionally mention
  `config-digest`, `deadline-at`, and `id`.
- `src/seon/web/serve.cljs:975-996,998-1050,1057-1092,1108-1185` consumes 23
  of those names in pull, validation, comparison, and rendering paths.
  `src/seon/agent/ctx/transcript.cljc:799-810` consumes `partial-text`.
- Seven removed run-policy attributes remain in database reads:
  `deadline`, `last-beat-at`, `paused-at`, `remaining-ms`, `result-ref`,
  `trigger`, and `turn-limit`. Representative owners are
  `src/seon/derive.cljs:68-75,129-141,449-463`,
  `src/seon/agent/ctx/subagents.cljc:107-141,328-337`, and
  `src/seon/agent/authorization.cljs:8-14`.
- Five removed turn attributes remain: `scheduled?`, `phase`, `llm-attempts`,
  `prompt-blob`, and `reply-blob`. Readers include
  `src/seon/agent/ctx/transcript.cljc:782-810`,
  `src/seon/agent/debug.cljs:102-123`, and
  `src/seon/web/serve.cljs:1247-1250`.
- A registration search over `src/` returns zero `schema/register!` or
  `register-schema!` calls for all 36 names. The surviving run schema at
  `src/seon/agent/run/core.cljc:11-65` deliberately owns none of the seven
  old run-policy attributes.

## Schema-hygiene disposition — 2026-07-26

The narrow schema-hygiene unit after this audit gives the surviving reader
vocabulary portable owners without restoring the deleted execution stack:

- `seon.ai.attempt` registers the 24 source-used names plus the historical
  identity, config-digest, and deadline fields still present in fixtures.
- `seon.agent.turn` registers the five surviving old-turn reader attributes
  plus the two process-local turn-coordinate keys. Attempt connections are a
  cardinality-many set; `:seon.ai.attempt/ordinal` owns their order.
- No current JVM writer produces attempt, phase, prompt/reply-blob, scheduled
  turn, or partial-text facts. Their registration prevents silent
  unregistered reads but does not make the old consumer paths live.

The seven removed run-policy readers remain unregistered and this issue stays
open. A surviving JVM observability owner must either write each retained fact
or delete its consumer; schema registration is not evidence that such a writer
exists.

## Owner

This is deletion residue, not authority to restore the removed runtime.

- O13's pod cut owns the attempt, phase, and scheduled-turn consumers.
- The owner-keyed run cleanup owns deletion of the old pause, heartbeat,
  deadline, and turn-budget projections.
- A later JVM observability owner must write new prompt/reply blob or
  partial-presentation facts in the same change that makes their readers live,
  or delete those readers.

## Acceptance

- Every remaining run, turn, and attempt attribute reference has one of two
  dispositions: deleted with its old-model consumer, or registered and written
  by a surviving JVM owner in the same implementation unit.
- No compatibility registration is added to make an empty read appear valid.
- The production dangling-attribute scan returns zero names.
