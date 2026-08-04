---
type: research
status: blocked
tags: [bootstrap, agents, experiment, rendering]
---

# Bootstrap baseline rerun — shared scratch deletion blocker

## Verdict

The renderer and bootstrap-fold blockers are fixed, but the Arm A/Arm B
experiment still did not complete. During the Arm A run, the contents of
`tmp/bootstrap-drives/` disappeared while three isolated roots were live. The
deletion removed completed raw reports and the database files beneath running
writers. Continuing or launching Arm B would have produced an unauditable
matrix, so the lane stopped under the original foreign-breakage rule.

The deleting process or owner is not identified. This report records only
observed facts and does not attribute the cause.

## Grounding

I read this report end to end before replacing it. I had already read
[`docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md`](../plan/bootstrap-vector-design-2026-08-01.md)
and `src/seon/bootstrap_drive.clj` end to end before implementing and running
the original experiment.

The rerun verified both named fixes before spending provider calls:

- `5e5f28fb1` passes `:seon.sci.eval/time-limit-ms` to transcript rendering;
  the completed rerun transcripts no longer contain the prior
  `render-receipt-ai failed` value.
- `8763b4b17` passes the database to declared-content comparison; the shipped
  bootstrap now folds all 13 database plan forms.
- `02dd76e8a` supplies a validated candidate vector only during the drive's
  fresh source-population call.

The shipped resource contains 13 database plan forms. The design's 14-entry
description includes the separate banner plus those 13 forms. Arm A used the
shipped resource. Arm B was prepared as the first `(help)` form map, including
its `:seon.bootstrap.plan.form/context`, but was not launched after the shared
path disappeared.

## Exact failure boundary

Immediately before the disappearance, the observed Arm A raw-report counts
were:

| Objective | Reports observed |
|---|---:|
| O1 | 9 |
| O2 | 10 |
| O3 | 4 |
| O4 | 10 |
| O5 | 2 |

The next read found counts of 0/1/1/0/0. The only surviving new files were:

- [`tmp/bootstrap-drives/o2-10-a900e98a.edn`](../../../../tmp/bootstrap-drives/o2-10-a900e98a.edn)
- [`tmp/bootstrap-drives/o3-5-b2c8be70.edn`](../../../../tmp/bootstrap-drives/o3-5-b2c8be70.edn)

At the same instant, the live roots
`tmp/bootstrap-drives/9295c147/clusters/store` and
`tmp/bootstrap-drives/c07548a4/clusters/store` were absent. Both JVMs then
reported `java.nio.file.NoSuchFileException` for their own Konserve `.new`
paths and emitted `:datahike/writer-shutdown` core faults. The remaining live
drives were interrupted immediately.

The durable issue is
[`docs/seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md`](../../../seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md).

## Incomplete predicate evidence

This is not the requested objective-by-arm result table; the deleted raw
reports make that table unrecoverable. Two complete batch summaries reached
the lane before their report files disappeared:

| Objective | Arm | Attempts | Predicate results |
|---|---:|---:|---|
| O2 | A — shipped | 10 | P2a 10/10; P2b 10/10 |
| O4 | A — shipped | 10 | P4a 0/10; P4b 0/10; P4c 0/10; P4d 0/10 |

O4's agents sent their peer requests and returned `my.run/wait`, but no
peer-authored function or reply appeared before each episode stopped. That is
valid behavioral evidence, not a renderer failure, but it is insufficient for
the complete matrix.

No Arm B winner exists, so there are no self-written winner forms to mine.

## Token cost and peak window

The rerun began at approximately 23:01 UTC, outside DeepSeek's stated 2× peak
windows of 01:00–04:00 and 06:00–10:00 UTC. All provider calls therefore ran
off-peak. No HTTP 402 appeared before the shared path disappeared.

Exact token totals and cost are not recoverable: usage was recorded only in
the per-attempt EDN reports, and those reports were deleted. The two surviving
files retain only their own attempts' usage; presenting that subset as matrix
cost would be misleading.

## Agent-visible output

The renderer fix held in every transcript inspected before deletion. Agents
saw ordinary REPL forms and values, including `(help)`, `dir`, `doc`, query
results, declaration Vars, the deliberate lint rejection for `(largest)`, and
message/disposition values. No prior renderer invocation failure recurred.

The new failure was operator-visible rather than agent-visible: Datahike's
writer emitted a long nested exception and then a core-fault line after its
database directory disappeared. No surviving transcript shows that fault
rendered into an agent's context.
