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
