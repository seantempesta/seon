---
type: issue
status: open
severity: defect
created: 2026-09-26
tags: [issue, test, cost, harness, datahike]
---

# Turn-test property members pay a file-store commit per step

`seon.turn-test` took 85,240 ms in root run abab00602fe6 (`tmp/orchestrator/verify-0415.out.d/008.txt`).
Three members spent 53.4 s of it, each far over its 5 s bound: `recovery-preserves-terminal-receipts-exactly`
17.8 s, `transitions-agree-with-the-model` 18.1 s (red; green it runs more trials), and
`virtual-turns-use-the-proc-and-compaction-is-agent-scoped` 17.5 s. Durations are the gaps between member
completion transactions in the stored run (the run stores no per-member elapsed time).

## Cost model (2026-09-26, default pid 84287, isolated branch of the live store)

| Operation | Time |
|---|---|
| isolated branch acquire / acquire + release | 29 ms / 72 ms |
| `seon.db/transact!` of one datom on a branch | 48-78 ms (5 samples) |
| raw `datahike.api/transact` of one datom on the same branch | 40 ms |
| same commit on an in-memory store | 5 ms |
| creating an in-memory store with the 1,219 installed attributes | 1,150 ms |

A trial is one fresh branch plus 5-6 commits (about 0.5 s): the recovery property runs 30 trials, the state
machine 60 trials of up to 15 commands each, so the green state machine is estimated at 35-45 s. The commit
itself (konserve on the file store) is most of it; the writer's own validation adds 10-40 ms.

## Direction

The ruled target is a member world that is a branch of a prepared commit in milliseconds. A memory-store
branch of a prepared commit would make each step about 5 ms; creating a memory store per trial is worse
(1.15 s). This belongs to the harness design (fast-harness lane), not to trial counts. Lowering trial counts
or declaring long bounds (over 10 s needs owner authorization) are the alternatives.
