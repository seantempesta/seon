---
type: research
status: active
tags: [research, test, orchestrator]
---

# Gate ledger — 2026-09-15 (orchestrator-maintained)

Rule (owner, 21:05Z–21:50Z): astra lanes do focused fixes and never launch
test JVMs (`bin/_test-slot` orchestrator-only mode, `fd98bc5a5`); the
orchestrator batches gates in dedicated Opus threads on committed HEAD only
(`bin/test --paths README.md -- <namespaces>`), saves results under
`tmp/orchestrator/gate-results/<batch>/<lane>.md`, records them here, and
resumes each lane pointing at exactly its reds. One gate per batch; never
the same namespace twice for the same HEAD.

## Batches

| Batch | HEAD region | Namespaces | Result | Reds handed to |
|---|---|---|---|---|
| 1 | `4c8740cf0`–`1d17650a9` | refusal-grammar (10) + fixtures-events (4) + platform | 207/6191 green; platform 84/567 green; results recorded | none |
| 2 | `f5ca25ba9`+ | p1-ambient-state (19) | ABORTED at 22:12Z by the orchestrator: the gate alone ran 9 pool workers + serial + 6 concurrent confirmation JVMs (load avg 75 on 18 cores, 26 GB compressed); runner capped, re-run as batch 2b. The killed batch-2 gate thread RELAUNCHED its run (uncapped) beside 2b; killed again at 22:45Z. Lesson: a gate thread's instruction must say "if the command is killed, do not rerun; report" | — |
| 2b | `f5ca25ba9`+ | p1-ambient-state (19) | KILLED at 21:44Z by the superseded batch-2 thread (the two threads had swapped runs); partial log through seon.sci.eval-test, no tally | — |
| 2c | `48605a1de` | p1-ambient-state (19), capped at 3 workers, nice 15, strict no-relaunch thread | running | — |
| 3 | pending | debug-page-cost, reaching-tests-tier, startup-and-hook-waste | queued behind their gate requests | — |

## Lane → slice → status

| Lane | Landed | Gate | Open residuals |
|---|---|---|---|
| refusal-grammar-2 | `1fd81b2be`, `4c8740cf0` | batch 1 green | class residuals in its note; argument-count refusal omits the count (issue) |
| fixtures-events | `e4f8bbe07`, `1d17650a9` | batch 1 green | P2/P3/N2 members open with residuals |
| p1-ambient-state | `b80f78a7c`, `f5ca25ba9` | batch 2 running | adoption/lifecycle members open |
| n7-query-classification | `5deb40e4e`, `872fb25d4` | gated by lane before the rule (82/418, platform 86/542) | stored toolkit (cluster.clj), eval call edges (fn.clj), schema-fallback (P1) |
| bisect-today-reds | `6dc70f30a`, `ee8d54dca` | gated by lane before the rule (103/248, platform 86/542) | none |
| debug-page-cost | `cfb35a22b`, `671108b60`, `c20b83d20` (three kills: retained reuse across carried values; passive directory audit off; shared derivations once) | batch 3 pending: seon.render.retained-test seon.render.web-debug-test seon.render.web-context-test | cold page after adoption 19.5 s (issue; slice 4 in flight) |
| reaching-tests-tier | in flight | — | automatic on-edit check |
| startup-and-hook-waste | `c395610db`, `db7e653ca`, `f75c85402` (both initializers build one projection, 998/998 armed; hook drains immediately, exactly one successor batch; edit-feedback test updated) | batch 3 pending: seon.test.runner-test seon.test-runner-test seon.dev.hook-test seon.dev.edit-feedback-test | hook issue narrowed to AGENTS.md wording |

## Live checks (default)

| When | Debug page warm | Fallback warnings / 100 KB log | Note |
|---|---|---|---|
| 21:00Z | 1.81 s | 7 | after restart |
| 21:10Z | 0.70 s | 7 | P1 edits adopted |
| 21:45Z | 0.75 s | 7 | P1 committed; plan attributes the floor to retained-read replay and directory reconstruction |
| 22:05Z | 0.18 s (cold after adoption 18.4 s) | 7 | debug-page-cost kills 1–2 adopted; cold path filed as an issue |
| 22:35Z | 0.14 s (cold after adoption 19.5 s) | 7 | kill 3 adopted; lane in-process 64–76 ms |
