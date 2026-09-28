---
type: research
title: Store write fixes
date: 2026-09-28
status: in progress
---

# Store write fixes — 2026-09-28

## Batch result recording

`seon.test/run` now accumulates one admitted batch's captured outcomes before
calling `runner/commit-results!` once. Per-result termination is accepted by
`complete-members`, so an observed exit lands beside its outcome. The normal
synchronous path already returned termination with its result; only an
unfinished body's watcher called `record-exit!`. The callback now records its
observation in batch-local state until the batch commits. An atomic handoff
accounts for exits racing that commit; genuinely later exits retain their
own write. No callback waits for the recording thread.

If a request throws, `record-interrupted!` includes captured successful
outcomes and adds interruption errors only for still-unrecorded members.
Already committed outcomes remain immutable. Admission and stop writes are
separate; no test body was executed by this lane.

Live MCP JVM probe, pid 53829, loaded `17087c432`, on disposable branches
forked from the same admitted 100-member value, commit
`6aba9424-6cd4-5b21-9e57-7513b537f7f2`. Outcomes were copied from 100 real
recorded results; this measures recording, not newly earned test proof.
The candidate's per-member termination transaction data equals the existing
all-terminated transaction data. Every measured branch ended with exactly
N completed and terminated outcomes.

| Results | Before commits | After commits | Before index bytes | After index bytes | Before ms | After ms |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 1 | 1,044,756 | 1,044,756 | 96.462 | 91.206 |
| 10 | 10 | 1 | 10,630,532 | 1,349,146 | 523.890 | 82.033 |
| 100 | 100 | 1 | 108,183,483 | 1,583,135 | 4,761.626 | 271.629 |

Bytes are actual new external index-node files, marked after each commit
with Datahike's `index.interface/-mark` and one shared visited-address set.
Inlined roots have no separate file. Admission, branch metadata and the
commit records themselves are excluded, consistently on both sides. This
is a 68× reduction in this 100-result recording's node bytes, not a claimed
reduction of the whole store or test execution. Initial marking visited
2,165 nodes and about 479 MB of reachable files; it is outside measured
recording time. Heap allocation was not measured.

Deterministic regressions for root (not run by this lane):

- `seon.test.runner-test/a-batch-records-every-outcome-and-observed-exit-in-one-commit`
  checks one writer call at 1/10/100 results and common completion/exit tx.
- `seon.test.one-request-test/one-request-runs-each-member-as-an-isolated-agent`
  now also checks that the actual request's two members share a completion tx.
- `seon.test.interrupted-request-test/a-thrown-request-records-its-unfinished-members-as-failed-obligations`

Archive `483bc8291` plus the four changed source/test files: clj-kondo
`src script test --cache false`, 0 errors, 1,726 warnings, 6.712 s.
Contracts: 498 files, 4.626 s, the same ten pre-existing errors in
`script/seon/dev/docstring.clj` and `script/seon/dev/markdown.clj`; no new
source/test findings. Source/test delta is +99/−32 lines.

First drill failed before boot in Maven's parallel model validation:
`HashMap$Node` cannot cast to `HashMap$TreeNode`, under
`clojure.tools.deps/expand-deps`. Diagnostic:
`data/clusters/default/logs/operator-drill-20260928T102408.667.edn`.
The same drill passed on retry, candidate `1333066fc6b0`, base `17087c432`:
base from-scratch boot 241.203 s, move 20.589 s, candidate from-scratch boot
199.166 s, total 590.312 s. These are the explicitly requested disposable
drill operations, not ordinary request latency. Raw output is in
`tmp/astra-store-fixes-20260928/batch-drill-retry.log`.

The reproducible recording probe and paired data are committed at
`tmp/astra-store-fixes-20260928/batch-recording-probe.clj` and
`tmp/astra-store-fixes-20260928/batch-measurements.edn`.

Foreign boundary: one-path's turn/SCI/MCP work and the documentation-script
contracts are untouched. Default was already faulted (13 open faults, 184
failed tests, 54 unknown failed-test observations). Root replaced default
during probing; boot retired the first unclaimed probe branch. The probe was
recreated and the paired measurements above all use one captured head.
This lane never stopped or reset default.

## Member lifecycle investigation

Selection's latest-result lookup keeps the newest result per test, carrying
its reach digest; it does not search every historical digest for an older
green. A current-data scan found 16,277 admitted members, 1,870 latest
members across branches, and 683 wholly superseded runs containing 12,397
members. Keeping one result for every historical reach/input digest would
still keep 15,100 members. Historical run reports and live reservations are
separate consumers: pruning must preserve their historical query route and
must not retire a body whose exit remains unobserved.

The retention unit is the admitted run, not an individual component member:
`admitted-members` verifies its historical membership refs. Keep latest
per-test owners, live reservations, the last green selection baseline and
subsequent outstanding obligations, and the transitive owners reached by
`covered-by`. Historical run reports must read an as-of value from before
retirement. Do not keep one copy for every historical digest.

A disposable prototype retired 543 runs and 9,947 members in one transaction
(122,022 effective datoms, 7.355 s, 108,273,059 new external index bytes).
An old run's as-of results were exactly equal and the second pass selected
no further removals. This proves the cascade/history mechanism, not the
final retention policy: the prototype did not yet preserve selection's last
green baseline and subsequent obligations. It is not installed. History is
still retained; retracting current rows does not itself shrink disk history.

## Publication set differences

`program/derived-shape` now carries the bridge's declared many attributes
without component ownership or ordered-vector storage. It derives them once per projection, using
`malli->datahike-schema-in`; no reader guesses cardinality from a set-shaped
EDN value. `replacement-tx` computes removed and added members in O(old + new)
and emits O(difference) writes. Components retain their existing cascade.
`fn/reconcile-tx-in` passes the returned set additions through the index
compiler instead of rebuilding whole-set assertions from the source row.
The existing new-entity path remains a complete canonical declaration.

MCP JVM pid 66988, loaded `c4628c22b`, scratch functions only. Each pair starts
from the same committed value on an owned branch. The real
`seon.render/invoke-selected` row supplied the source, resolver context and
157 observed calls. Each probe retains N calls and removes one; both paths
use `db/definition-digest-of` and the normal writer checks. Fully published
declarations compare equal after each pair. This is a storage probe, not a
claim that the deliberately reduced graph is executable analysis evidence.

| Set size | Before/after commits | Before/after call datoms | Before index bytes | After index bytes | Before ms | After ms |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 / 1 | 1 / 1 | 561,874 | 561,874 | 138.639 | 30.486 |
| 10 | 1 / 1 | 19 / 1 | 1,070,256 | 561,681 | 164.410 | 40.501 |
| 100 | 1 / 1 | 199 / 1 | 2,314,307 | 568,453 | 99.378 | 30.645 |

Same node-file measurement as above; seed commits are excluded. The
single-member paths write identical bytes, so their timing difference is
warm-up/noise, not an algorithmic claim. At 100 members the change removes
198 unnecessary call datoms and 75% of node bytes for this edit. Heap
allocation was not measured. Probe source and data are
`tmp/astra-store-fixes-20260928/publication-{probe.clj,measurements.edn}`.

Focused tests for root, not run here:

- `seon.program-replacement-test/a-set-edit-writes-two-datoms-at-one-ten-and-one-hundred-members`
- `seon.program-replacement-test/ordered-many-attributes-do-not-use-set-differences`
- `seon.program-test/function-contract-redefinition-replaces-component-facts-exactly`
- `seon.fn-test/invocation-metadata-survives-admission-and-exact-replacement`
- `seon.fn-test/publication-writes-one-definition-digest-per-declaration-family`

Archive `c4628c22b` plus the four owned files: final lint 0 errors, 1,673 warnings,
7.259 s. Contracts: 494 files, 16.586 s under concurrent boot load, the same
ten foreign documentation-script errors and no new findings. Source, test
and schema delta: +94/−20 lines. The first publication drill passed; final
review added the ordered-vector guard and its regression, then the final
drill passed too (`cf491c78b39d`, base `c4628c22b`): base from-scratch
158.965 s, move 79.115 s, candidate from-scratch 145.674 s, total 464.595 s.
Foreign `test/seon/fn_test.clj` edits were not included;
the new work-count test lives in its own file.

## Analysis digest investigation

The audit's 8,332 figure is **datoms**, not 8,332 changed function entities.
Transaction 536880175 at 2026-09-27T18:35:28.075Z has 4,166 assertions and
4,166 retractions, with 3,951 entity ids on both sides (deletions/additions
account for the remainder). All 4,166 asserted digests exactly equal their
owning file's digest, over 234 files. There is no shared global hash here.

231 of those files were touched by `799cd62a7`, the entity-vocabulary sweep.
232 file hashes match `4dea3b95a`; the other two (runner and failure-facts
tests) match that commit's parent, before its report-identity change. Thus
the wide event is an adoption of a broad source sweep. The remaining waste
is the file-level stamp changing on unchanged declarations and lint ids
including absolute row/column. Analysis provenance stays a file/content
observation; an already analyzed, otherwise identical row need not be
rewritten solely to carry the newer file stamp.

The history count fold took 151 ms; the as-of file join took 4.083 s and
is an audit of the historical publication, not an ordinary request path.
The file-hash comparison took 5 ms after obtaining the held results.

## Stable lint identity and analysis observations

Lint identity now hashes the declaration/file owner, finding type, message and
occurrence among equal claims in source order. Row and column remain facts.
Sorting costs O(F log F), then one fold assigns identities; duplicate claims
remain distinct. Reconciliation compares already-analyzed rows without their
file observation stamp, while a missing first observation still requires a write.
The contract is retained and the lint producer now declares its finding schema.

MCP JVM pid 75624, loaded `52e163bee`, paired disposable branches from the same
held commit; actual stored findings and function rows, normal writer validation.
Both paths commit once and return equal logical answers (excluding the deliberately
changed identity scheme or retained historical analysis stamp). Bytes are newly
reachable external index-node files across six indexes; seed writes are excluded.

| Inputs | Lint bytes before / after | Lint datoms before / after | Lint ms before / after | Stamp bytes before / after | Stamp ms before / after |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 2,208,614 / 527,736 | 19 / 3 | 265.298 / 33.309 | 1,086,130 / 543,366 | 157.687 / 32.841 |
| 10 | 4,769,327 / 533,462 | 181 / 21 | 173.160 / 40.082 | 1,951,615 / 543,366 | 74.971 / 33.111 |
| 100 | 9,038,988 / 496,935 | 1,777 / 201 | 299.855 / 56.528 | 3,999,945 / 543,366 | 185.931 / 32.682 |

Stamp-only declaration datoms fall from 2N to zero; the file change still writes
three datoms including txInstant. At 100 inputs, lint node bytes fall 94.5% and
stamp node bytes 86.4%. These measurements do not estimate heap allocation or
claim physical reclamation of history. The real lint producer was also evaluated
in a scratch namespace on `src/seon/error.clj`: all 77 findings retained distinct,
stable identities after a line shift (176 ms).

Evidence: `tmp/astra-store-fixes-20260928/lint-probe.clj`, `stamp-probe.clj` and
`analysis-measurements.json`. Root's focused tests (not run by this lane):

- `seon.analysis-identity-test/shifted-findings-update-only-positions-at-one-ten-and-one-hundred-definitions`
- `seon.analysis-identity-test/a-new-file-stamp-does-not-rewrite-already-analyzed-declarations`

Archive `f80a524b2` plus these two source/test files: lint with cache disabled,
0 errors and 1,673 existing warnings, 8.111 s. Contracts retain the same ten
foreign errors in `script/seon/dev/{docstring,markdown}.clj`; no new finding.
Drill candidate `29070f2a1d7b` passed: base 112.477 s, move 54.166 s,
from-scratch candidate 161.499 s, total 563.376 s. This proves the indexed
candidate loads and arms; central focused tests remain root's responsibility.
Foreign boundaries are the turn/SCI lane, schema lane and cluster MCP removal;
none of those pending changes is included in this slice.

## Adoption records store only their consumed inputs

The declaration list is still derived for reload/unmapping, now explicitly named
`reload-identities`. It is no longer copied onto the adoption transaction:
`:seon.test/adoption-identities` had no source consumer. Its schema and the two
measurement selectors are removed; the former test now checks the reload filter
and constant recording work at 1/10/100 declarations. Recording stores the cluster
and distinct changed file paths, O(P) in those paths rather than O(D) declarations.
Retiring the attribute during adoption retracts current values; history remains.

Fresh paired branch measurements after restart, pid 85484, on 100 real function
identities, one changed path. Both use the same held commit and normal writer;
the pulled cluster/input answer agrees. The entire six-case probe took 816 ms.

| Declarations | Commits before / after | Datoms before / after | Node bytes before / after | ms before / after |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 1 / 1 | 4 / 3 | 785,672 / 712,389 | 60.949 / 38.450 |
| 10 | 1 / 1 | 13 / 3 | 862,837 / 712,389 | 41.767 / 45.470 |
| 100 | 1 / 1 | 103 / 3 | 868,322 / 712,389 | 42.659 / 39.426 |

At 100 declarations, 100 unused datoms and 155,933 node bytes disappear. Latency
is essentially unchanged. Evidence is `adoption-probe.clj` and
`adoption-measurements.json` under `tmp/astra-store-fixes-20260928/`.

Root's focused test (not run here):
`seon.adoption-entities-test/adoption-record-size-does-not-grow-with-reload-identities`.
Archive `ddd3cf9a2` plus the five owned files: lint 0 errors, 1,673 warnings,
7.551 s; contracts 495 files, 5.122 s, only the same ten foreign documentation-script
findings. Drill `46a71e5a9972` passed: base 228.735 s, move 29.954 s,
candidate from scratch 173.276 s, total 510.522 s. The unchanged cluster MCP
region remains the entrance lane/root's boundary. Source/resource/test delta is
+41/−53 lines; this slice contains no turn, SCI, schema-owner or MCP edits.
