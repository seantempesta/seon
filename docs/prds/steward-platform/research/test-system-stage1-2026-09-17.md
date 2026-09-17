---
type: research
status: active
created: 2026-09-17
tags: [test-system, stage1, selection, program-graph]
---

# Stage 1 — resumed draft and publication dependency

Stage 1 is **not complete**. This note distinguishes the graph/regression
checkpoint from the restored selector draft; a passing fast iteration does
not establish the design's two-host acceptance.

## Authorities and inherited state

Read the requested authorities end to end:

- [AGENTS.md](../../../../AGENTS.md), sections 0–7, and the
  [clojure-testing skill](../../../../.agents/skills/clojure-testing/SKILL.md).
- [Test-system PRD](../plan/test-system-is-the-database-prd-2026-09-17.md),
  [Stage 1–3 design](../plan/test-system-stage1-3-design-2026-09-17.md),
  [Stage 2 research](test-system-stage2-2026-09-17.md), and
  [reset-batch plan](../plan/reset-batch-2026-09-17.md).

On resumption, re-read [program-facts §1n](../plan/program-facts-are-the-runtime-prd-2026-09-17.md),
the [plan README](../plan/README.md)'s 19:50Z schedule Track A, and the complete
Stage 1 design section. Applied the owner's preserved
616-line patch with `git apply --3way` and restored its schema resource.
The conflict was in `test/seon/test/selection_test.clj`; current fn.clj
landings were preserved. No default cluster was read, started or modified.

The first fast overlay at HEAD `707508b6f` refused admission because it
required the publication lane's dirty `test/seon/cluster/source_test.clj`.
A detached worktree at that HEAD, with reference-code and existing test-cache
links, isolates only this lane's paths. No foreign file was overlaid.

## Graph and regression checkpoint

The request-map arity of `seon.fn/gate-sets` accepts one immutable database
and a set of qualified-symbol seeds, returning their sorted union. It shares
one frontier and seen set across overlapping seeds; the positional arity
retains its per-seed result. The runtime request is declared with seon.fn.
The fixture checks an overlapping cyclic graph without repeated AVET reads.
The dependency seam is Datahike's AVET attribute/value lookup in
`reference-code/datahike/src/datahike/db/search.cljc:140–157`, through
`seon.db/datoms`. Database reads produce invalid-read or missing-projection
facets (`src/seon/db.clj:166–184`); the graph contracts name those facets.

The graph portion of triage #14 is covered by
`seon.fn-test/a-refused-reference-read-refuses-gate-set-derivation`:
only the declared-reference query is refused, while other reads remain real.
The refusal carries the declared database-read class marker without the old
kind key. Every gate adapter must return that exact value. The relation helper
and union helper have contracts enumerating database invalid-read and missing
schema-projection errors (`seon.db/dependency-error` and its read constructors);
declared read errors propagate through the
public adapters rather than becoming a smaller successful selection.

Runner regressions use qualified symbols for captured-result keys and lookup
refs. Recording after test retraction now asserts `test-definition-absent`,
unchanged database basis, and absence of resurrection. The 2,000-result EDN
transport probe retains its size/round-trip assertions and requires recording
to refuse unknown definitions rather than fabricate 2,000 program rows.
The admitted-member regression still proves completion can survive retraction
without recreating the program definition.

## Required dependency and unlanded work

The design's “Inputs outside the program graph” section requires publication
to record `:seon.source/test-input-digest`. It explicitly refuses missing
input evidence and prohibits a filesystem/file-basis fallback in select.
That attribute is absent from `resources/seon/schemas/seon.source.edn`, and
`src/seon/cluster/source.clj`'s source seal writer (around line 300) records
only digest, build time and activation closure. The user explicitly assigned
`src/seon/cluster/source.clj` to the publication lane. It was not edited.

Required handoff: the publication owner must carry the sorted non-graph
path/content inventory (including gitlink identity) into the source seal,
and preserve it through publication/adoption. Run admission can then compare
that observed value against its handed `:seon.test.run/input-digest`.
The existing `runner/admit-run` has members and claims, but currently checks
the program digest without checking this missing source-input fact.

The restored `seon.test/select` and runner draft remain **uncommitted WIP**:
they still accept changed paths, derive per-test green bases, and leave the
file green-basis and old selection namespace alive. They do not implement
the required cluster-scoped run basis/obligations or pre-execution selection
admission. They must not be described as the completed Stage 1 owner.
The draft's selection callers also still need the same declared-error
propagation at their result boundaries; the graph fix does not certify those
unlanded callers. `selection-is-one-function-on-both-hosts` and the removal of the superseded
selection paths remain owed with that integration. The graph checkpoint alone
does not satisfy “unchanged rerun executes zero.”

## Verification

First run: `timeout 2400 bin/test-fast --paths … -- seon.test.selection-test
seon.test-runner-test seon.fn-test seon.test.runner-test` at isolated HEAD
`707508b6f`, 1,170 contracts armed. The real fileless SCI regression passed.
Three exact-selection assertions exposed that quoted synthetic identities in
the regression itself were real indexed references; the test correctly selected
itself as a fourth reaching test. Runtime-constructed fixture symbols remove
that accidental graph edge without changing production selection.

The runner namespace then hit the existing concurrent-launcher liveness issue:
320 seconds without reporter progress, zero declared body allowance, exit 124.
There was no suite tally. The test now declares its 1,800,000 ms bound; no
SEON_TEST override was supplied. The issue note has the exact evidence.
The second run armed 1,171 contracts but hit the old 30,000 ms write bound
in three canonical-fixture attempts. This is the existing
[publication write-bound issue](../../../seon/issues/the-thirty-second-write-bound-fails-program-publication-under-load.md).
Commit `02cb1b2b7` landed its declared-config interim while the detached worktree
still held `707508b6f`. The obsolete run was ended with TERM (143), then the
worktree advanced by fast-forward to `02cb1b2b7`, preserving only owned edits.
One preparation was cancelled after a failed local sync; no result is claimed
from it. The current-HEAD run puts the graph namespace first and uses the same
four requested namespaces. The canonical fixture now builds.

Measured union walk on that canonical fixture: one seed = 15 indexed reads /
39.457 ms; two overlapping seeds = 15 reads / 39.019 ms; three seeds = 21 reads /
38.966 ms. No indexed lookup repeats. These are fixture measurements, not a
latency guarantee. The four targeted refusal-adapter checks passed. Two test
expectations needed correction: another shorthand-quoted synthetic identity
made the test itself a referrer, and quoted `%` inside `#(...)` was the anonymous
function parameter rather than the literal Datalog rules symbol. The latter
check now uses an explicit `fn`. The current-HEAD four-namespace run completed **141 tests / 1,026 assertions /
9 failures / 0 errors**. The other seven failures were the concurrent-launcher
fixture's obsolete **240-second** child waits: both completion flags were false,
both children were killed with exit 137 during publication, and neither could
report a tally. Those waits now use remaining time from the one declared test
deadline, starting before store preparation. The final serial rerun includes
that correction and the graph-test fixes. All recorder, symbol-identity,
retraction and admitted-member checks completed without unexpected failures
in the full run.

Cold gate and platform proof remain the orchestrator's responsibility; this
lane did not invoke either as a gate. The explicitly requested runner namespace
contains launcher regressions which execute child gates in private fixture roots.

### Graph checkpoint verification

In the final serial four-namespace iteration, all **62 seon.fn-test tests**,
**6 seon.test.selection-test tests**, and **22 seon.test.runner-test tests**
completed without unexpected failures. This includes the corrected reference
read refusal and shared-frontier assertions. The broader launcher namespace
subsequently completed green as recorded below.

The final graph measurement retained 15 / 15 / 21 indexed reads for one / two /
three seeds, at 104.555 / 102.638 / 99.658 ms. Both observed timing sets are
reported; the regression asserts exact membership and no repeated reads, not
the design's explicitly unproven 50 ms estimate. Static lint reports zero
errors for the changed graph and regression files.

### Final fast result and review boundary

The final invocation exited **0**, with **141 tests / 1,026 assertions /
0 failures / 0 errors**. Exact invocation, from the detached worktree at
`02cb1b2b7`:

```sh
timeout 2400 bin/test-fast --paths src/seon/fn.clj src/seon/test.clj src/seon/test/runner.clj resources/seon/schemas/seon.fn.edn resources/seon/schemas/seon.test.selection.edn test/seon/test/selection_test.clj test/seon/fn_test.clj test/seon/test_runner_test.clj test/seon/test/runner_test.clj -- seon.fn-test seon.test.selection-test seon.test.runner-test seon.test-runner-test
```

Raw evidence: `tmp/test-system-stage1-resumed-fast-5.log`. The concurrent
launcher test completed in 575.406 seconds; both child tallies were observed.
The graph checkpoint is commit `fe2f1e816`. The runner regression checkpoint
contains only the symbol/retraction expectations, declared launcher deadline,
and their issue evidence. No production selector draft is certified by these
commits or by this tally.

Publication changes landed while verification ran (`ac13b8b4d`, followed by
`54e3a45ce`), leaving `src/seon/cluster/source.clj` clean. The required input
digest still does not exist. An ownership clarification was sent because the
assignment explicitly held that owner for the publication lane; no answer
had arrived at this checkpoint. This lane has not changed that owner or
substituted filesystem evidence. Full Stage 1 remains at the named publication
input-digest dependency, with shared admission and the exact two-host
acceptance still owed. The restored draft is preserved in the shared tree.

The completed lane JVM and its worktree JVM holders were absent before cleanup.
Removed only `tmp/test-system-stage1-resumed-wt`, unlinking its shared cache,
reference-code and slot symlinks first. The verified overlay and new schema
were saved under `tmp/orchestrator/worktree-patches/test-system-stage1-resumed-*`;
raw logs remain in `tmp/`. The documentation hook reported repository-wide
stale citation errors in older audit documents; `git diff --check` is clean.
