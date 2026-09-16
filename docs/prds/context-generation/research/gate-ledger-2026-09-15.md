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
| 21 | `fe9aeb336`+ | platform tier alone (issue-family's fix `fe9aeb336`: issue indexing out of the publication path), then issue-family's 3 namespaces | running | — |
| 20 | `cecfaf428` | steward session's error-graph (12 ns) | 230/1618: 236 failures, 53 errors; six namespaces GREEN (error, problems, cluster, wake, schedule, status); red: cluster.turn-test 48 (pre-existing class, overlaps turn-test-reds), render.transcript-test 15, turn-loop-test 11, turn-test 2, flow 1, faults 1; results not recorded (prepl unavailable) | steward session, with a baseline-first instruction |
| 19 | `e4080bd3f` | HEAD publishes again (`ff48a4110`). Six lanes in one batch: turn-test-reds (2) + steward session's reach-digest (2), program-provenance (3), entity-pairs (1), attempt-and-eval-facts (6), generated-read-identities (4) + platform; all 18 namespaces validated against test/ | named: 355/2452, 82 failures, 23 errors; entity-pairs GREEN; turn-test-reds 19 tests; reach-digest 4; program-provenance 11; attempt-and-eval-facts 4; generated-read-identities 3; PLATFORM RED 84/568: 13 failures all in seon.cluster.source-test (latest-test-evidence-survives-rebuilding-from-an-older-base ×12, incremental-first-party-publication ×1) — last touch of cluster/source.clj is the steward session's `a7d1e115e` (issue-family); results not recorded (prepl silent 30 s; result store held by another process) | per-lane files and the platform attribution sent to the steward session; turn-test-reds resumed |
| 18b | `806e6e8d8` | turn-test-reds (2 ns), relaunched once | ABORTED in published-base preparation: schema publication refuses `:seon.issue/issue` (`:seon.render/ai` names `seon.issue/render-ai` whose declared input nil does not accept the declaring shape) — introduced by the other session's commit `6a491f0b3` (01:26Z). EVERY cold gate at HEAD is blocked until that schema is fixed | owner / the steward session |
| 18 | `0eba2ae10` | turn-test-reds (2 ns; five slices: a production deletion fix `4b3322b04`, the test entity AI/HTML pair `ff351811b`, 27 repaired tests / 188 assertions, two obsolete tests replaced; 16 tests still unresolved) | running | — |
| 17 | `47dcf6d92` | n7-eval-call-edges re-gate (2 ns) after `171c0c193` (4 tests green on a fresh base) | 89/458: fn-test GREEN; seon.cluster.turn-test still 44 tests red cold (103 blocks) — the lane verified 4 of ~89 tests; BASELINE at pre-wave 4c8740cf0: 59 tests, 56 failures, 44 errors — 43 tests fail in both, 7 baseline-only (fixed since), 1 HEAD-only. seon.cluster.turn-test was deeply red before the wave; not an N7 regression | n7-eval-call-edges CLOSED for its slice (the 1 HEAD-only test handed back); seon.cluster.turn-test opens as its own class lane |
| 16 | `8bf2dceaf` | p1-ambient-state re-gate (5 ns) after `253206238`, `0edd57230` (supplied test bounds honored; one fresh-store fixture arity; render bounds caller-owned; SCI test state scoped) | 115/663 GREEN cold, no worker-global mutations; 114 s | p1-ambient-state CLOSED for its read/admission scope |
| 15 | `2a4e43d25` | n1-render-substitution (2 ns) | 54/361: render.value GREEN cold; 1 failure = distance-spends-only-real-ref-hops-and-caps-win, already in P1's batch 12b reds | n1-render-substitution CLOSED for its slice; the distance test stays with P1 |
| 14 | `6e4fe9511` | n7-eval-call-edges (2 ns) | 89/365: 59 failures, 44 errors — seon.cluster.turn-test 51 tests red cold (~30 contract refusals at instrument.clj:413, 8 schema refusals at edn.clj:46) after the settlement change; fn-test 1 | n7-eval-call-edges: reproduce cold, fix at the settlement seam |
| 13 | `bebdfb39e` | n1-mcp-bypass (4 ns) | 65/407: seon.mcp-test, render.value, mcp-bridge GREEN cold; 1 failure = the merged artifact test asserting one fresh store now sees 2 (same red as batch 12b, already with P1) | n1-mcp-bypass CLOSED for its slice; the fresh-store count stays with P1 |
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
| p1-ambient-state | `b80f78a7c`, `f5ca25ba9`, `28e955327`, `9c3c3d8d4` | batch 2c red → batch 4 worse (platform red) → in-process repair `72d7dc3a9` → batch 16 GREEN (closed) | adoption/lifecycle members open (recorded options in its note); stale fixture-contract-after-adoption class queued |
| n7-query-classification | `5deb40e4e`, `872fb25d4`; n7-eval-call-edges `f402c5d3d` (analysis returns core + my.* edges) | gated by lane before the rule (82/418, platform 86/542); seon.fn-test pending batch 12b | stored toolkit (cluster.clj, n1 lane holds it), turn.clj persistence landed `924fdbf3a` (batch 14 running); schema-fallback (P1); stored toolkit still open (cluster.clj) |
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
| n1-total-render-plan (`9167db1a8`, partial: stopped after three malformed probes) | option A approved (close the MCP sorted-map bypass at its owner; early root validation in value/prepare; one class regression); the 20 unverified members go to an Opus verification pass with the corrected probe recipe before any wider scope | n1-mcp-bypass (astra, in flight); N1 member verification (Opus, done: 13 resolved / 7 confirmed / 0 unverifiable — research/n1-member-verification-2026-09-16.md; the class is ALIVE on the agent path: a pulled :seon.fn row renders as a 100-char stale-Var instruction, a config row as English prose, a turn entity as the empty string) → n1-render-substitution landed `563034709` (batch 15 running) |
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

## 01:15Z — a second orchestrator session is live

A separate Claude Code session (the owner's steward-platform dialogue, pid
75117) launched lanes `cold-page-plan` and `hook-publication-race` from the
wave-2 spec templates. This session does not resume, stop, or gate them.
Gate coordination across sessions rests on `tmp/test-slots` (two slots
machine-wide, orchestrator-only mode); each session runs at most one gate
at a time, so the machine sees at most two.

## 01:50Z — default reforked by the other session; status refusal

`default` is now pid 7595 (start 01:36Z), reforked by the steward session.
`runtime_status` refuses: `seon.problems/problems refused return value at
[... :seon.problems/occurrences]: expected an integer, got an integer` —
a zero-occurrence signature against `[:int {:min 1}]`, and the grammar
dropped the constraint. Issue filed
(problems-refuses-its-own-zero-occurrence-signature.md); an Opus agent is
fixing both under the REPL rule (cost rule: Opus for mechanical fixes).

## 02:05Z — gates blocked by the steward session's schema commit

`6a491f0b3` (steward session) declared `:seon.issue/issue` with a render
pair whose contract the publication refuses; `bin/test`'s shared published
base cannot be built at HEAD, so no cold gate can run for anyone. That
session's five lanes also hold uncommitted edits in src/seon/problems.clj,
error.clj, cluster.clj, cluster/source.clj, test.clj, test/runner.clj and
several tests; this session's Opus fix for the problems zero-occurrence
refusal was stopped before writing to avoid clobbering them. Handoff to the
owner: the steward session must fix the seon.issue render-pair contract (or
revert `6a491f0b3`) before any gate here resumes; turn-test-reds' 18b re-gate
and the problems fix are queued behind it.

## 02:20Z — coordination with the steward session

The steward session (seon-61) owns the seon.issue refusal via its
issue-family lane and is repairing it; cold gates here HOLD until its
ledger line says HEAD publishes. Its in-flight lanes: error-graph
(error.clj, cluster.clj commit-fault!, seon.error*.edn, problems.clj
readers), reach-digest (test.clj, test/runner.clj, seon.test.edn),
program-provenance (fn.clj, program.cljc; also fixes the form-span tuple
retraction blocking `init --dev`). Landed from that side today:
`474234fb7` (system turns open again), `ac34ce5a3`/`ff351811b`/`54f9155f1`
(fn/test entity pairs), `3402913f3` (file/span + seon.lint), `17dd75e89`
(usage/renderer facts). Handed to it: the third-error-writer cause behind
the status refusal (fold into error-graph). It reforks default once more
after its schema edits land.


## 02:30Z — HEAD publishes again (steward session)

`ff48a4110` (issue-family) declares the `seon.issue` render pair contracts
the publication accepts; the hook's batch at 02:19:55Z converged on the
tree carrying that fix (commit `6aa9fc8a`, digest `f53a8de4…`), and
`bin/seon init --dev default` exited 0 after `f9a46b0bd` (tuple retraction
with values). Cold gates may resume. Still uncommitted from the steward
session: error-graph (error.clj, cluster.clj commit-fault! region,
problems.clj, seon.error.edn, their tests) and issue-family (issue.clj,
cluster/source.clj, bin/seon, bin/issues-index, script/seon/dev/issues.clj,
seon.agent.edn). Landed today: reach-digest `f2d537187`/`e5de6ebc7`
(warm check 2.65 ms; one changed function 56 ms, three digests recomputed),
program-provenance `3402913f3`/`f9a46b0bd`, entity-pairs, generated-read
fix `474234fb7`, attempt facts `17dd75e89`.

## 05:40Z — default's prepl saturates under in-process regressions

Eight lanes across both sessions ran their in-process regressions inside
default's JVM at once; the hook's publications exited 124 on their bound
and `runtime_status` timed out. The steward session paused four of its
lanes. Same shape as the test-JVM saturation: the development JVM's prepl
is one shared resource; in-process test runs need the same admission as
gates (a slot, or the reaching-tests check running them serially).

## 06:05Z — recording failures explained

The "result-cluster store held by another live process" and
"prepl-response-silent" recording failures in batches 19–20 came from
`f2d537187` (reach-digest): `completion-reach-digests` opened the gate's
shared published-base store under its lifetime flock on every
`commit-results!`. Replaced at HEAD by `1b5c09e15` (canonical private
fixture, no store open); recording should work again from the next gate at
or after that commit. reach-digest's only remaining cold red belongs to
agent-call-edges.

## 06:20Z — batch 20 attribution (steward session's Opus triage, `b45881f32`)

None of batch 20's reds attribute to error-graph. A PLATFORM CLASS since
`26ec13420`: `seon.db/write-map-error` validates a partial entity map
against every entity schema that lists its identity attribute, so fixture
seeds are silently refused and tests assert against an empty database
(issue raw-write-validation-refuses-reverse-refs-and-partial-entity-maps,
blocker; astra lane write-validation-class on db.clj). That explains
seon.cluster.turn-test growing 18 → 48 red between batches 19 and 20; the
turn-test-reds lane was told to classify seed-refused tests as blocked by
that lane and work only the rest. Other attributions: render.faults →
attempt-and-eval-facts; three transcript/turn-loop tests stale (Opus fix
later); turn-test settlement row → agent-call-edges (`76774d044`);
flow-test kill_child reads absence as readiness. Default's own fault
committer transactions are refused on default ("at [0 :seon.error/at]")
after error-graph's schema change: RESET NEEDED; the steward session
reforks after issue-family lands and messages first.

