---
type: research
status: complete
tags: [operator, adoption, bounded-execution, performance]
---

# Adoption progress and phase cost — 2026-09-17

Bounded assignment: preserve default pid 94566; do not restart, stop, reset,
or refork it. Foreign edits and sessions remain untouched. Read AGENTS.md
sections 0–7, the flow architecture skill and the named failure evidence.
The sibling reset/refork issue was read end to end. The 170704 ms baseline
log failed during reload; it is not successful adoption evidence.

## Dependency ledger

- Clojure `reference-code/clojure/src/clj/clojure/core/server.clj:194`:
  prepl emits structured output events while evaluation runs and one terminal
  return. A socket client's exit is not evaluation cancellation.
- `resources/seon/operator/state.clj`: the shared Babashka/JVM lifecycle lock
  owns the actual file descriptor and transition completion. Progress extends
  silence observation; it never releases custody.
- `reference-code/datahike/src/datahike/db/transaction.cljc:1206`:
  final-report validation sees expanded and effective transaction results.
  Performance changes must preserve this writer authority.
- `reference-code/datahike/src/datahike/writer.cljc:136`: one serial processing
  loop owns a connection's transactions.
- `src/seon/cluster.clj:2272`: adoption owns schema reconciliation, program
  reconciliation, reload, instrumentation, SCI acquisition and its final stamp.

## Measurements so far

| Observation | Milliseconds | Outcome |
|---|---:|---|
| Historical lifecycle hold, pid 94902 | 180027 | Refused own holder |
| Historical lifecycle hold, pid 97908 | 180047 | Refused own holder |
| Historical init, pid 58066 | 170704 | Reload failure, not convergence |
| Live effective silence configuration | 30000 | Read from default's database |

First iteration: `bin/test-fast --paths resources/seon/operator/state.clj
script/seon/fresh_operator.clj test/seon/adoption_margin_test.clj --
seon.adoption-margin-test`: 1 test, 7 assertions, zero failures/errors.
This is an armed fast iteration, not the orchestrator's cold/platform proof.

A full JSON thread dump and a 60-second JFR recording observed the pre-existing
publication queue on default. No interpretation of thread silence as successful
adoption is made. Final phase and literal-duration measurements follow below.

## Literal progress and disconnect proof

The committed `adoption_margin_hold_probe_2026_09_17.clj` uses the real kernel
lifecycle lock under `tmp/adoption-margin-root`, following a root-scoped
`bin/seon --root tmp/adoption-margin-root status`. It ran under
`timeout 2400`, with the declared 30000 ms silence interval: 36 completed
work events across **540838 ms**, exceeding three times the actual old CLI
180000 ms limit. The stalled `SCI acquisition` phase refused after
**30075 ms**. Its worker retained the kernel lock until explicitly released;
a subsequent acquisition proved terminal release. No cluster JVM was needed
for this lock regression.

Default accumulated 19 submitted publication evaluations after their clients
disconnected. `PrintWriter` records socket errors instead of throwing them.
The existing progress owner now checks that error at a real phase boundary
and refuses the disconnected observer, rather than continuing expensive work.
Hot-reloading that private owner through MCP reduced the observed queue from
19 to one, without stopping or interrupting another session. The regression
uses a closed real `PrintWriter`. A stalled transaction still retains its
writer custody; no unsafe asynchronous cancellation was added.

## Fresh-store phase comparison

Both measurements use the armed canonical source-publication regression,
`seon.cluster.source-test/incremental-first-party-publication-retains-complete-scalar-rows`,
in a HEAD-plus-owned-paths snapshot. The prior fully populated default is not
used as the test fixture. Concurrent host work makes these observed timings,
not a statistical speed claim. Population grew from 40412 to 40417 entities
with the new declarations.

| Completed phase | Before ms | After ms |
|---|---:|---:|
| Publication start | 76 | 123 |
| Schema population | 808 | 642 |
| Instruction rows | 41 | 54 |
| Program preparation | 1921 | 1369 |
| Contract projection | 951 | 646 |
| Contract rows, first batch | 4850 | 3402 |
| Contract rows, second batch | 3323 | 2323 |
| Contract rows, third batch | 982 | 712 |
| Contract rows, fourth batch | 236 | 181 |
| Contract rows, fifth batch | 826 | 566 |
| Contract rows, sixth batch | 4674 | 3069 |
| Transaction compilation after final contract row | 5850 | 4151 |
| Complete population transaction | 14662 | 13036 |
| Initialization | 896 | 833 |
| Issue indexing | 3176 | 2317 |
| Test evidence (empty on this fresh store) | 1 | 0 |
| Activation seal | 550 | 531 |
| Branch publication | 61 | 37 |
| **Whole publication, exact timer** | **43896.634459** | **33999.886834** |

Zero-duration boundary announcements are omitted from the table. The
publication timer improved by 9896.747625 ms (22.55%). The historical
41644 ms measurement described whole publication, not all writer validation;
this measurement separates the writer transaction from preparation.

A 128-frame JFR recording found `canonical-data-string` in 842 sampled
stacks and as the first Seon frame in 599. Its recursive calls crossed the
public instrumentation wrapper at each nested value. A private recursive
worker now performs exactly the same encoding; the public boundary stays
contracted. Five golden-byte assertions cover the prior map/set framing,
collection order, EDN literals and rejection of runtime objects.

The first optimized snapshot passed **20 tests / 163 assertions, zero
failures/errors**, including the real lock, closed observer, golden encoding
and complete source-publication namespace. A follow-up snapshot covers the
evidence-preservation optimization below.

## Published evidence cost

Default's prior published head held 784 test results, eight run rows and
1146092 reach references (3793 distinct reached functions). A per-row pull
then repeated portable-reference work hid in the unlabelled seal phase.
Batched `seon.db/pull-many` shares the dependency's compiled pull plan
(`reference-code/datahike/src/datahike/pull_api.cljc:541,579`). Missing refs
remain aligned nil values; typed read failures refuse before transformation.

Inside the existing writer transaction, each distinct identity is resolved
once and existing targets use their numeric entity IDs from that exact
transaction database. Missing targets retain the current owner's admission
behavior. No schema validation was removed or moved before the writer.
The full evidence read measured **5733 ms** live. The subsequent adoption
measured **4735 ms read + 25570 ms transaction**, each below the declared
silence window. The prior live attempt stopped after 30000 ms of silence
in the combined evidence phase; it supplies no successful before-duration.

## Verification boundaries

All test commands were `timeout 2400 bin/test-fast --paths ... -- ...`, one
at a time, with no `SEON_TEST_*` overrides. An isolated worktree was required
when the shared tree had an in-flight unmatched delimiter in
`test/seon/gen/loop_test.clj`. The first broad operator iteration separately
found the stale one-argument `canonical-row` fixture described in
[the existing reload issue](../../../seon/issues/live-publication-has-a-hand-maintained-predicate-owner-reload.md).
It was ended after that failure; its files and sessions were not changed.
The orchestrator still owns the cold gate and platform proof.

The final evidence-preservation snapshot again passed **20 tests / 163
assertions, zero failures/errors**. The issue adoption/deletion iteration
ran **12 tests / 163 assertions, one failure / zero errors**; the failure is
the unchanged render expectation at `test/seon/issue_test.clj:65`, already
tracked in [the issue render note](../../../seon/issues/the-issue-ai-render-no-longer-teaches-its-requery-form.md).
An earlier attempt exposed one additional test caller of the removed private
per-issue reader; that caller was updated to the batch reader and the complete
issue/deletion iteration rerun.

At HEAD `be9c90e2f`, the shared `bin/test-fast --paths` launcher refused before
launching a test JVM: `No published program graph matches HEAD ...;
orchestrator must run: bin/test --prepare-head-base`. The assignment forbids
that command. Reconciliation iteration therefore continued in the existing
isolated worktree at `c772db2d3`, with only owned paths overlaid. Its log is
`tmp/adoption-margin/reconcile-final-fast.log`; no foreign launcher file was
edited and no other lane was operated.

Program reconciliation had a second per-row recomputation: its memoized
reference pull was allocated inside `normalized-index-row`, so every next
row reread the same targets. Reference reads now belong to the exact immutable
database value for the operation: published rows, the writer's reconciliation,
and each side of the definition comparison carry separate readers. These
readers never escape the operation or cross database values. The existing
index callback reports row acquisition, transaction, and comparison boundaries.

## Successful current-default adoption

**Default remained PID 94566 throughout. It was never stopped, started, reset
or reforked.** The normal edit-hook adoption completed at 05:56Z with
`6aab80ff-f1c1-5732-a247-23e483188a35`, including the existing one-time
source-change retry. Its progress timers sum to 146193 ms. The explicit
`timeout 2400 bin/seon init --dev default` then exited zero: **37354 ms init**,
**190874 ms lifecycle including lock acquisition**. It reused the complete
publication and still performed development reconciliation and acquisition.
This is distinguished from the fresh-store publication measurement above.

| Completed phase | Successful adoption, final retry ms | Explicit following adoption ms |
|---|---:|---:|
| Schema declarations | 322 | 219 |
| Published program rows | 2554 | 2226 |
| Reconciliation preparation | 396 | 33 |
| Program reconciliation transaction | 5373 | 4639 |
| Changed definition comparison | 4982 | 4533 |
| Issue reconciliation and next-phase preparation | 5355 | 4645 |
| Loaded definition preparation | 349 | 359 |
| Reload seon.issue | 35 | — |
| Reload seon.schema | 62 | — |
| Reload seon.program | 30 | — |
| Reload seon.fn | 76 | — |
| Reload seon.cluster.source | 40 | — |
| Reload seon.sci.eval | 77 | — |
| Reload seon.test.selection | 10 | — |
| Reload seon.test.cache | 11 | — |
| Reload seon.cluster | 69 | — |
| JVM instrumentation | 957 | 40 |
| SCI acquisition | 621 | 542 |
| Source verification | 103 | 91 |
| Adoption record | 278 | 224 |

A subsequent single read through sanctioned MCP returned PID 94566,
`:converged? true`, adopted = published =
`6aab814a-c7b5-5d2d-950b-1ea7970b16d6`, and effective silence = **30000 ms**.
The later commit reflects the continuing normal edit-hook publication. No
claim of globally clean runtime errors or a platform gate is made.

Before convergence, selected current source definitions were hot-reloaded via
MCP: the progress observer, canonical encoder, source evidence functions,
issue reader/adopter, development refresh and index/reconciliation functions.
Public replacements were re-armed with the declared projection. Those probes
were loaded-Var proofs; the successful adoption and equal source stamps above
are the separate in-place development-adoption proof. The final reload set was
derived by the existing owner; no hand-maintained reload list was introduced.

Historical logs contain no per-adoption-phase elapsed values, and both supplied
180-second logs failed. Missing historical measurements are **unknown**, not
inferred timings. The new progress output makes subsequent comparisons direct.

## Final checks and commits

- Lock/closed observer/encoding/source publication: **20 tests, 163 assertions,
  zero failures/errors**, twice after the optimization (the second includes
  batched evidence and writer-local reference resolution).
- Issue adoption/deletion: **12 tests, 163 assertions, one known render failure,
  zero errors**. All adoption/deletion assertions passed.
- Index/program reconciliation: **86 tests, 662 assertions, two failures, zero
  errors**. The SCI ownership census is the existing
  [second-owner issue](../../../seon/issues/my-program-native-evaluation-adds-a-second-sci-owner.md).
  The schema namespace parity mismatch also reproduced with the unchanged
  indexer: **27 tests, 227 assertions, one failure, zero errors**, now recorded
  in [the schema row issue](../../../seon/issues/schema-declaration-regression-disagrees-with-current-row-shape.md).
- No `bin/test` cold gate, platform gate or preparation command was run.
  No `SEON_TEST_*` override was used. The orchestrator owes integration proof.

Coherent commits: `c772db2d3` (phase liveness), `02fb846e3` (canonical
encoding), `be9c90e2f` (phase reporting and evidence), `627a24047` (issue
adoption), and `ad75bab51` (reconciliation reads).

Machine-readable measured phases are in
[adoption-margin-phases-2026-09-17.edn](adoption-margin-phases-2026-09-17.edn),
extracted with [the phase script](adoption_margin_phases_2026_09_17.clj).
The JFR summary is [adoption-margin-profile-2026-09-17.json](adoption-margin-profile-2026-09-17.json),
reproduced by [the profile script](adoption_margin_profile_2026_09_17.py) from
`jfr print --stack-depth 128 --events jdk.ExecutionSample --json`.
The literal lock proof is [the committed probe](adoption_margin_hold_probe_2026_09_17.clj).
All test/probe work used project-local scratch paths.

## Owned files and cleanup

Production and schema paths changed in this dated slice:
`resources/seon/operator/state.clj`, `resources/seon/schemas/seon.source.edn`,
`script/seon/fresh_operator.clj`, `src/seon/cluster.clj`,
`src/seon/cluster/source.clj`, `src/seon/schema.clj`, `src/seon/issue.clj`,
`src/seon/fn.clj`. Regressions changed:
`test/seon/adoption_margin_test.clj`, `test/seon/schema_encoding_test.clj`,
`test/seon/cluster/source_test.clj`, `test/seon/issue_test.clj`,
`test/seon/issue_deletion_test.clj`, `test/seon/program_test.clj`.

Research paths are this landing note, the linked phase/profile data and their
extractors, and the literal hold probe. Issue notes changed are
`development-adoption-hold-bound-measures-duration-instead-of-progress.md`,
`issue-indexing-at-publication-costs-13-seconds.md`,
`live-publication-has-a-hand-maintained-predicate-owner-reload.md`,
`schema-declaration-regression-disagrees-with-current-row-shape.md`, and
`the-indexer-resolves-its-declaration-world-per-file-and-per-row.md`, all under
`docs/seon/issues/`. No shared launcher, evaluator, runner or foreign session
was changed.

After all owned test/probe sessions ended, the process table showed no JVM or
Babashka process holding the scratch paths. The `reference-code` symlink was
unlinked before removing the worktree. `tmp/adoption-margin-wt`,
`tmp/adoption-margin-root`, and the 1.6 GB profiling scratch under
`tmp/adoption-margin` were deleted. Measured phase/profile data were already
extracted into the linked durable research files. The raw temporary paths
above are historical provenance, not retained files.
