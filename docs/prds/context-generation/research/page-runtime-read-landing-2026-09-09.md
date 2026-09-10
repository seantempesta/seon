---
type: research
status: complete
tags: [render, database, test]
---

# Page runtime read — 2026-09-09

Read end to end: AGENTS.md (its opening copies turn PRD §10; there is no
separate AGENTS.md §10), the named issue at its archived path
`docs/seon/issues/archive/debug-page-counts-zero-evaluations-after-turns-moved-under-runtime.md`,
and `data-lane-landing-2026-09-09.md`. Also read the roadmap and working edge,
turn PRD §10 and §§13–16, and the Clojure, REPL, Datahike, testing, and web UI skills.

## Dependency ledger and diagnosis

- Datahike query normalization and evidence: `reference-code/datahike/src/datahike/query.cljc:123–139`.
  First-party owner `src/seon/eval.clj:9` already follows
  agent/runtime/turns and sorts by turn transaction, turn id, and evaluation
  ordinal. `fd8646edd` installed that correction before this assignment.
- `src/seon/render/web.clj` then filtered those results to the newest turn.
  The header and Context now therefore hid the entire saved prefix whenever
  the latest turn was empty. Delete that extra query and filter.
- `seon.turn/latest-evaluations` still used the old turn/agent relationship.
  Its existing query now follows runtime ownership for the would-be system
  turn and actual changed-read append. `seon.turn/next-id` counts those same
  runtime turns, preventing identity reuse when the retired edge is absent.
- Canonical fixture: `seon.test-support/with-database`; web tests run a real
  http-kit server, render graph, and acquired SCI context. The regression
  stores a real system opening, removes legacy edges, closes an empty turn,
  adds an inbox wake, and stores the changed reads through the HTTP control.

## Initial live evidence

Inherited HEAD `a36208d5e`; default PID 83040, PREPL 60374, HTTP 7994.
Untracked `build/`, `config/virtual-turns.edn`, and `workers/` preserved.
No other lane or session operated.

Supported MCP JVM query: 11 evaluations across closed turns
`aa071259cfd8` (opened/closed 536871111), `9fc9bc9ef8ad`
(536871112), and `404bfad994bc` (opened 536871113, closed 536871115).
The page's current-turn filter returned 0. Curl on
`/ns/my.agents.juniper/debug?prompt=true` returned the exact state text
` · 0 evaluations · fresh` and an empty `seon-debug-evaluations` div.
The plain debug URL exposes the algorithm through its existing
`Inspect context algorithm` link; `?prompt=true` requests that projection.

MCP runtime status timed out while the supported evaluation and HTTP worked;
recorded in `docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md`.
The first exploratory pull used nonexistent `:seon.turn/ordinal`; it returned
a typed invalid-read and was corrected to the actual installed attributes.

## Verification

The first fast iteration exposed a
remaining reference to the deleted private filter in `page-review-test`.
That test asserted the obsolete newest-turn behavior; it now requires all
evaluations, including after an empty turn. This was a local test dependency,
not foreign in-flight breakage.

The HTTP regression also replaces the stale `Run system turn` button
expectation with the actual `System turn` label. No production label changed.
The suite's config seed wrote an invalid partial config and discarded its
refusal; the shared helper now calls `config/apply!` before cluster creation.
Other stale fixtures omitted the evaluation's required timestamp or wrote a
string into the integer `reply-size` attribute. Both now assert the admitted write. The
component-list expectation now includes the runtime component. These were
pre-existing fixture defects exposed by the requested full web namespace.

The corrected HTTP regression checks the existing successful control status,
204. Its behavioral assertions passed in the last fast iteration: saved
opening, unchanged comparisons without legacy refs, empty-turn preservation,
no-write preview, wake append, and exact prior-entry prefix.

The edit hook first made the page visible before publication converged.
An intermediate check found adopted `6aa20d09-e3e2-5e36-b03c-e851fe974eb0`
versus published `6aa21181-b2ca-50c8-b15a-e3ee3cebf250`; the hook reported
source changed during adoption. This is not claimed as completed adoption.
Final explicit adoption and matched commit ids follow below.

The first isolated expanded gate ran 87 tests / 925 assertions and confirmed
23 failures across three tests. Two inspection failures were the missing
timestamp. The turn fixture ran twice and contributed 14 assertions expecting
no changed reads after storing a turn. Seven loop-proof assertions omitted
the already-landed runtime read, assumed the data query was last, or expected
runtime shown text to survive compaction unchanged despite retaining turns.
The tests now explicitly expect runtime/settings refresh, retain stable-read
comparisons, and prove the peer inbox remains unchanged. No extra production
mechanism was introduced to satisfy those old expectations.

The new socket regression itself passed in that gate, including opening,
empty-turn preservation, runtime-only comparison, and wake append.

Corrected turn/loop fast iteration: **27 tests / 500 assertions, zero
failures/errors**. Tests entered with 946 instrumented functions and the
canonical database/SCI fixture.

Final owned-path isolated gate: **87 tests / 926 assertions, zero
failures/errors**, exit 0. Namespaces: `seon.render.web-test`,
`seon.eval-test`, `seon.render.page-review-test`, `seon.turn-test`,
`seon.loop-proof-test`. `SEON_TEST_WORKERS=1 bin/test --paths` supplied the
owned paths below; the coordinator/test phase took 236 seconds. Successful
root `tmp/test-runs/run.ZFxOt7` was removed by the runner. No all/full gate ran.

Explicit platform gate, same owned paths and one worker: **84 tests / 505
assertions, zero failures/errors**, exit 0; coordinator/test phase 91 seconds.
The runner removed successful root `tmp/test-runs/run.3djp9P`. The earlier
failed root `run.dZVrhV` was removed after its failures were fixed and the
process table showed no JVM holding it.

## Owned paths

- `src/seon/render/web.clj`
- `src/seon/turn.clj`
- `test/seon/render/web_test.clj`
- `test/seon/render/page_review_test.clj`
- `test/seon/turn_test.clj`
- `test/seon/loop_proof_test.clj`
- `test/seon/test_support.clj`
- `docs/seon/issues/archive/debug-page-counts-zero-evaluations-after-turns-moved-under-runtime.md`
- `docs/seon/issues/archive/test-cluster-seed-writes-an-invalid-partial-config.md`
- `docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md`
- `docs/prds/context-generation/research/page_runtime_read_probe_2026_09_09.py`
- This landing note.

## Live reproduction command

```sh
curl --max-time 30 -fsS 'http://127.0.0.1:7994/ns/my.agents.juniper/debug?prompt=true' -o tmp/page-runtime-read-after.html
python3 docs/prds/context-generation/research/page_runtime_read_probe_2026_09_09.py tmp/page-runtime-read-after.html
```

No default stop, refork, or restart is authorized or performed.

## Final adoption and HTTP proof

Implementation commit: `49869fd90`. Explicit command, exit 0:

```sh
bin/seon init --dev default --changed src/seon/render/web.clj src/seon/turn.clj test/seon/render/web_test.clj test/seon/render/page_review_test.clj test/seon/test_support.clj test/seon/turn_test.clj test/seon/loop_proof_test.clj
```

Development adoption completed schema/program reconciliation, loaded definitions,
SCI acquisition, and JVM instrumentation. Supported JVM evaluation independently
reported adopted and published commit ids both
`6aa21434-2adf-57e7-96ae-20b164ab5de3`, `:converged true`, 11 evaluations,
and three closed runtime turns. This is in-place development adoption.

Curl after adoption: **HTTP 200, 63,691 UTF-8 bytes, 0.546574 seconds**.
SHA-256: `59b801c048eae7282ad7b6dce1908326efc0b9c72e25e314636818127fdf2874`.
The committed HTML parser observed exact text
`Agent juniper · 11 evaluations · continuing`, 11 prompt entries and 11
responses inside Context now. Would-be statuses in order:
`[:unchanged :unchanged :unchanged :changed :changed :unchanged :unchanged :unchanged :changed]`.
The would-be projection therefore compares with retained reads; no form is
misclassified as a missing opening. The HTTP observation does not claim browser
paint or layout verification.

Default remained PID 83040, generation `1f682616-d27b-47e0-9d61-0ac02919a83d`,
PREPL 60374 and HTTP 7994 throughout. No live message or provider request was
sent for this proof; the wake append ran on the canonical fixture.
The MCP health timeout remains the recorded out-of-scope observation.

No foreign gate boundary occurred. All owned shells completed; successful
gate roots were removed by the runner, the holderless failed root was removed,
and page/probe/gate scratch files were deleted. The inherited three untracked
paths remain untouched. Source lint reported existing unused/shadowed bindings
and docstring warnings; no unresolved-name, syntax, privacy, or arity finding
remains in the final changed files.
