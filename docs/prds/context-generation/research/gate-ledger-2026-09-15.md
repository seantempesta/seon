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
| 2c | `48605a1de` | p1-ambient-state (19), capped at 3 workers, nice 15 | 392/2437: 50 failures, 20 errors in 6 namespaces (sci.eval, render-simplification, schema.datahike, effect, render.value, cluster.mcp); 13 namespaces green; published-base 44 s, tests 748 s; results NOT recorded (live prepl unavailable) | p1-ambient-state: class = fixtures mint bare database values and the fallback now refuses; Datom-where-map in mcp-test; two effect events |
| 13 | `bebdfb39e` | n1-mcp-bypass (4 ns; `c3a8d0f01`: MCP recognition bypass closed, roots validated first; issue archived) | running | — |
| 12b | `b1be50c2a` | P1's 13 real namespaces + seon.fn-test | 340/2448: 11 failures in 6 tests (was 153 blocks); published base reused (0 s); tests 285 s | p1-ambient-state 5 tests (sci.eval 3 incl. two that mutate worker-global state; render-simplification 1; the merged mcp artifact test 1); n7-eval-call-edges 1 (agent-source-reaches-the-evaluator-through-one-visible-path) — handed when the lane stops |
| 12 | `d57a69c3a` | p1-ambient-state re-gate (14 ns + platform) after `72d7dc3a9` (evaluation database context carried; batch-4 fixture contracts repaired; all 52 batch-4 regressions + the platform regression pass in-process) | named gate ABORTED at load 6/14: the request named seon.sci.kernel-test, which does not exist (orchestrator did not validate the request); PLATFORM GREEN again (84/566 at `f402c5d3d`, 125 s); 12b re-runs the 13 real namespaces | — |
| 11 | `0d057a799` | debug-page-cost (1: seon.render.web-context-test) | 4/53 GREEN cold; published base reused (0 s); 34 s | debug-page-cost CLOSED |
| 10 | `deb077923` | slow-tests-merge (1) | 2/59 GREEN cold; 33 s | slow-tests-merge CLOSED |
| 9 | `0cd0ad907` | debug-page-cost (3) + test-runner-waste (1) | 24/330: 1 failure; test-runner-waste GREEN (closed); retained + web-debug GREEN cold; web-context 1 (retained page count 9 vs 10 across an unrelated adoption); tests 53 s | debug-page-cost (`0d057a799`: the tenth render was cluster status, which legitimately depends on the adoption commit; regression now asserts every other renderer is reused → batch 11 pending: seon.render.web-context-test) |
| 8 | `af487bc76` | slow-tests-merge (1): cold-acquired fixture with explicit environment/projection/profile; the raw-EDN failure could not be reproduced in-process, so this gate is the cold-worker confirmation | 2/46: the merged grammar test GREEN cold (raw-EDN class gone, 36 s for the namespace); 1 error: the kept order-schema test now fails invalid-schema :example/order-row (fixture no longer registers it) | slow-tests-merge |
| 7 | `5f80c1871` | test-runner-waste (2) + reaching-tests-tier (2) + debug-page-cost (3) + slow-tests-merge (1) | 79/686: 31 failures, 1 error; reaching-tests-tier GREEN; zero confirmation JVMs; published-base 40 s, tests 99 s | debug-page-cost 22 (fixture render pair / grammar not selected in a fresh worker), slow-tests-merge 8 (same class), test-runner-waste 2 (nested probe runs in a fresh worker). Class: passes in the dev JVM, fails in a cold worker — fixtures relying on ambient dev-JVM state |
| 6 | `6df05c861` | test-runner-waste (2) + reaching-tests-tier (2) | ABORTED in SELECT: the new regrowth check refuses selected tests reaching expensive fixtures without a declared observation — existing tests were never declared; zero confirmation JVMs launched (row 1 works); published-base 39 s | test-runner-waste: declare observations on every legitimately reaching test first |
| 5 | `ec52657de` | slow-tests-merge (6 ns; rows 2–6) | 55/559: 8 failures all in one merged test (refusal-grammar-survives-real-evaluation renders raw EDN, not the grammar); 5 ns green; measured: problems 15 tests/221 s, agent 22/72 s, mcp 11/55 s, config-application 4/27 s, contracts-plan 2/11 s | slow-tests-merge: CLOSED green in batch 10 (`a1fbdb347`, `af487bc76`, `deb077923`) |
| 4 | `8e19dee42` | p1-ambient-state re-gate (9 + platform) after `28e955327` | WORSE: 210/1132: 134 failures, 19 errors (7 ns); PLATFORM RED (1 error: reads-require-their-carried-projection…, test_support.clj:479); published-base 40 s, tests 182 s | p1-ambient-state: platform first; iterate in-process with seon.test/run before any re-gate |
| 3 | `b5b5b0fb3` | startup-and-hook-waste (4) + debug-page-cost (3) + reaching-tests-tier (7, overlapping 3) + platform | 88/597: 13 failures, 2 errors; platform 84/567 green; published-base 44 s, tests 157 s | startup-and-hook-waste GREEN; debug-page-cost: retained_test 8 FAIL + web-debug 1 ERROR; reaching-tests-tier: source-reconciliation 5 FAIL + 1 ERROR |

## Lane → slice → status

| Lane | Landed | Gate | Open residuals |
|---|---|---|---|
| refusal-grammar-2 | `1fd81b2be`, `4c8740cf0` | batch 1 green | class residuals in its note; argument-count refusal omits the count (issue) |
| fixtures-events | `e4f8bbe07`, `1d17650a9` | batch 1 green | P2/P3/N2 members open with residuals |
| p1-ambient-state | `b80f78a7c`, `f5ca25ba9`, `28e955327`, `9c3c3d8d4` | batch 2c red → batch 4 worse (platform red) → in-process repair `72d7dc3a9` → batch 12 running | adoption/lifecycle members open |
| n7-query-classification | `5deb40e4e`, `872fb25d4`; n7-eval-call-edges `f402c5d3d` (analysis returns core + my.* edges) | gated by lane before the rule (82/418, platform 86/542); seon.fn-test pending batch 12b | stored toolkit (cluster.clj, n1 lane holds it), turn.clj persistence (lane resumed now that it is free), schema-fallback (P1) |
| bisect-today-reds | `6dc70f30a`, `ee8d54dca` | gated by lane before the rule (103/248, platform 86/542) | none |
| debug-page-cost | `cfb35a22b`, `671108b60`, `c20b83d20` (three kills: retained reuse across carried values; passive directory audit off; shared derivations once) | batch 3 red (retained_test 8, web-debug 1) → fixed `8920d1dfe` (25 + 95 assertions green in-process) → batch 7 red (22) → root cause `27b9f7165`: the cold fixture carried the BOOTSTRAP projection (0 function contracts) while its database held 1,045; the constructor now carries the populated database's projection → batch 9 pending (3 ns) | cold page after adoption 18 s → 1.7–3.4 s (residual acquisition cost in the issue); the 0-contract bootstrap projection is probably the same cause behind slow-tests-merge's raw-EDN refusals (batch 8 will tell) |
| reaching-tests-tier | `e9af61d87`, `b5b5b0fb3` (seon.test/check in the development JVM; hook runs the reaching tests after adoption and withholds escalation on red; bin/test-check) | batch 3: 3 ns green, source-reconciliation red → repaired `558d5614a`, `dec12ea41` (empty check 6.6 s → 1.86 ms; both namespaces green in-process) → batch 7 GREEN | cold development JVMs lack some test dependencies (issue) |
| startup-and-hook-waste | `c395610db`, `db7e653ca`, `f75c85402` (both initializers build one projection, 998/998 armed; hook drains immediately, exactly one successor batch; edit-feedback test updated) | batch 3 GREEN | hook issue narrowed to AGENTS.md wording |

## Live checks (default)

| When | Debug page warm | Fallback warnings / 100 KB log | Note |
|---|---|---|---|
| 21:00Z | 1.81 s | 7 | after restart |
| 21:10Z | 0.70 s | 7 | P1 edits adopted |
| 21:45Z | 0.75 s | 7 | P1 committed; plan attributes the floor to retained-read replay and directory reconstruction |
| 22:05Z | 0.18 s (cold after adoption 18.4 s) | 7 | debug-page-cost kills 1–2 adopted; cold path filed as an issue |
| 22:35Z | 0.14 s (cold after adoption 19.5 s) | 7 | kill 3 adopted; lane in-process 64–76 ms |
| 23:20Z | 0.11–0.16 s (cold after adoption 3.4 s) | — | slice 4 adopted; unrelated adoption re-renders zero evaluations |

## Plans reviewed

| Plan | Decision | Implementation lanes |
|---|---|---|
| slow-surfaces-plan (`65642226b`) | rows 1–3 approved; hook option 1 (no idle delay); rows 4–7 deferred until measured | startup-and-hook-waste (rows 2–3, landed); row 1 (adoption rebuilds the projection) queued behind P1 |
| debug-page-cost-plan (`5755bcd60`) | kills 1–3 approved; option A for the directory audit | debug-page-cost (landed; cold-page slice in flight) |
| n1-total-render-plan (`9167db1a8`, partial: stopped after three malformed probes) | option A approved (close the MCP sorted-map bypass at its owner; early root validation in value/prepare; one class regression); the 20 unverified members go to an Opus verification pass with the corrected probe recipe before any wider scope | n1-mcp-bypass (astra, in flight); N1 member verification (Opus, done: 13 resolved / 7 confirmed / 0 unverifiable — research/n1-member-verification-2026-09-16.md; the class is ALIVE on the agent path: a pulled :seon.fn row renders as a 100-char stale-Var instruction, a config row as English prose, a turn entity as the empty string) → n1-render-substitution launched (value.clj free after `c3a8d0f01`) |
| test-suite-cost-plan (`78f0d15c0`) | row 1 option 1 (no automatic confirmation; explicit `--confirm`); row 7 publication deferred, lazy checkouts approved; row 8 approved; rows 2–6 approved; regrowth check approved | test-runner-waste (GREEN in batch 9, closed; rows 1, 7-lazy, 8, regrowth landed `ea5861329`…`e0dded0c6`; `e5b206987` carries the connection projection through in-process runs; `5f80c1871` declares 52 existing fixture observations — full selection 1,652 tests, 54 expensive, zero refusals; batch 7); slow-tests-merge (rows 2–6 landed, final `ec52657de`; flow-health test still blocked by a missing projection at seon.program/base-context-injected-symbols — P1 territory) |

## 23:55Z — Codex usage limit

The Codex account hit its usage limit ("try again at Sep 19th, 2026 3:03 AM").
reaching-tests-tier died after landing `558d5614a` (reconciliation fixture
repair; request narrowed to seon.source-reconciliation-test +
seon.test-reaching-test). debug-page-cost died mid-slice with uncommitted
edits in src/seon/render.clj and test/seon/render/retained_test.clj —
preserved, untouched. p1-ambient-state, slow-tests-merge (row 3 landed
`fc90bb972`; row 4 `e3af34340`), test-runner-waste (`ea5861329` landed) will
fail on their next turn. Owner decision pending: credits, or Opus
implementation threads under the same rules.

## Open class noticed by two lanes (00:40Z)

Both n7-eval-call-edges and n1-mcp-bypass hit "the canonical fixture retains
the old function contract after adoption" in the development JVM
(docs/seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md):
the in-memory fixture base caches a projection whose contracts predate the
hot-adopted definitions, so an in-process regression can be blocked by a
stale contract the cold worker never sees. Same family as the projection
carriage work (P1); assign after P1's current slice.

