# Check wait: measured 2026-10-14

Task: `seon.operator/a-check-waits-for-a-slot-longer-than-it-runs`.
Lane: check-wait. The operator builds a candidate archive, rehearses publication/adoption on default,
and concurrently queues, seeds, boots, adopts and tests a throwaway JVM. This note measures that existing path;
it proposes removing repeated publication before adding retained JVMs.

## Measurements

Seconds, rounded to three decimals. Historical inputs are the named files under
`data/clusters/default/logs/`; their complete phase maps are retained there. Fresh command:
`bin/seon check script/seon/operator.clj --test seon.operator-test/check-names-the-tests-its-throwaway-jvm-runs`.
The source path was unchanged: a doc-free HEAD candidate, not another lane's dirty tree.

| Check / candidate | Total | Queue | Seed | Prepare | JVM launch + boot | Ready (within boot) | Candidate adoption | Named tests / request | Parallel rehearsal |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| operator-check-20261014T160131.030.edn / 39f97869b7f0 | 155.273 | 75.553 | 3.197 | 4.429 | 31.086 | 23.488 | 5.640 | 30.066, unavailable | 23.485 |
| operator-check-20261014T160139.126.edn / 822b05d06a34 | 103.970 | 57.207 | 2.348 | 3.567 | 28.180 | 20.150 | 3.313 | 3.790, 1 executed | 29.500 |
| operator-check-20261014T160227.274.edn / d83e8f619214 | 150.110 | 90.522 | 2.649 | 3.543 | 31.426 | 21.646 | 3.650 | 12.303, 14 executed | 31.273 |
| Fresh / 923eff09d35e, run 831c5b5eb9f7 | 74.820 | 21.855 | 2.870 | 3.767 | 33.247 | 21.109 | 4.297 | 3.781, 1 executed | 32.939 |
| Same archive, `--at 923eff09d35e1b12df229acee30bc2f6f3950d46`, run 3e3a3ff3744b | 45.734 | 0.002 | 2.614 | 3.934 | 28.496 | 22.624 | 4.341 | 3.974, 1 executed | 0.001 |

The two fresh runs passed four assertions each. The historical one- and fourteen-test runs deliberately
reported the red measurement assertion `seon.schema.projection-writer-test/reconcile-probe-measurement`.
They are measurements of real checks, not green acceptance evidence.

Phase definitions come from `seon.operator/check!`, `seon.operator/candidate-tests!` and
`seon.operator/launched-throwaway!`. Rehearsal overlaps the throwaway path: do not add that column to total.
Ready is nested within boot; source/archive/classpath/share are nested within prepare. Total also includes
outer archive creation and teardown. Queue includes the preceding sweep. Fresh outer archive was 2.616 s;
the `--at` archive hit was 0.050 s. Remaining unassigned teardown/coordination was about 2.387 s and 2.323 s.

Fresh rehearsal explicitly reports 23.365 s publication and 9.347 s adoption, 1,792 adopted identities,
although the requested source file had no changes. Its template base was default's
5fddc2bfde4b3fee478b16f1b082a6ff5c0421f1. This is HEAD drift work paid by a one-test check.
Historical failed results retain rehearsal totals but discard that successful rehearsal's publication/adoption
split. They also discard boot layer facts. Those missing measurements cannot be reconstructed from ready-ms.

## Boot layers observed on the isolated fresh check

Read the check's own `seon.cluster.boot/readiness` while it was alive, after matching the advertisement's
process parent to this lane's launcher. `seon.cluster.boot/boot-timeline` records cumulative milliseconds.
The following differences are the actual layer intervals, not estimates from default's readiness.

| Layer | Cumulative ms | Interval ms |
|---|---:|---:|
| PREPL server | 124 | 124 |
| Store | 468 | 344 |
| Published source/current | 20,822 | 20,354 |
| Branch | 20,830 | 8 |
| Cluster connection | 20,832 | 2 |
| Commit id | 20,833 | 1 |
| Configuration | 21,235 | 402 |
| SCI context / work launcher | 22,005 | 770 |
| Flow graph | 22,605 | 600 |
| Web served / ready | 22,624 | 19 |

The interval through source/current is 90% of readiness. The source interval includes publication and its
prerequisites; it is not a profile proving which inner function dominates. JVM launch before boot's clock
and callback completion account for the remaining 5.872 s of the 28.496 s boot column.
Historical layer breakdown is unavailable because the operator discards it before removing the disposable root.

## Silent check and other observed failures

`operator-check-20261014T160131.030.edn` reports `check unavailable` after 30.066 s in the test-client phase.
`bin/test-check` first calls `seon.test/requested-bound-ms` with the 30 s transport bound, then calls the
runner with the derived request bound plus that transport allowance. The 30 s failure is consistent with
the first call, but the diagnostic does not retain its form: this is not yet proof of the producer's cause.
Two earlier diagnostics at 15:48:54 and 15:52:35 have the same 30 s signature.

A warm 100-identity probe through `seon.operator/prepl-value!` returned a declared bound of 1,173,000 ms.
`seon.profile/measure` of that call on default reported 0.6 ms, 501,592 allocated bytes, 102 instrumented calls,
and two reads. An 8,309-character form also round-tripped successfully. These refute a general 100-test
calculation cost or a basic 8 KiB transport limit; they do not reproduce the cold failed check.
No timeout increase, retry, or speculative transport fix is justified by this evidence.

`bin/seon check --help` still enters option validation and refuses Unknown option. The owning task is
`seon.operator/a-command-with-help-answers-an-error-map-instead-of-its-usage`.
The existing parser test `seon.operator-test/the-check-slot-count-is-a-dial` hardcodes two shipped slots;
it must not be treated as a check of the owner's new four-slot policy.

A preliminary fresh probe accidentally named nonexistent `seon.operator-test/operator-command-parsing`:
operator-check-20261014T160917.460.edn, 64.709 s, no eligible test; excluded from the success measurements.
A follow-up rehearsal lost default's PREPL during the scheduled root restart:
operator-check-20261014T161207.413.edn. This lane did not stop or restart default. The isolated `--at` probe
above completed independently. Missing-function reds cannot pass static analysis before tests run; that
is the existing admission order, not a queue defect. No stub or lint exemption was introduced here.

## Proposed next change, not implemented

First profile and narrow the reconciliation of the cloned template with the actual archive delta. Keep the
existing throwaway JVM and publisher/reloader as owners. The template already contains the base's facts,
and the existing publisher already compares file digests before publishing changed files. Merely adding
another changed-file filter would duplicate that mechanism. Measure the work before that comparison and
in the no-change publication/reload path, then move the unchanged-input decision to its owner.
The source/current interval is an upper bound of about 20 s on the earlier drifted candidate, not a
promise that all of it is removable. After the restart, the same-base candidate below reached readiness
in 6.191 s. Queue reduction follows shorter slot occupancy; four slots alone do not remove fixed work.
No performance implementation is justified until this profile identifies and measures the repeated work.

A retained JVM keyed by exact archive digest could remove roughly 28 s boot plus 2.6 s seed and 3.9 s
prepare on a repeat of that exact candidate. Different edits produce different digests, so measure the
repeat-hit frequency first. Reusing a JVM across different digests requires correct platform reload and
host-bound handling, a larger design than exact-archive reuse. It is not the first change proposed here.

The two fresh rows are NOT a parent/change performance proof: queue load changed and `--at` intentionally
skips the live rehearsal. No performance implementation or measured-win claim is committed. Acceptance
requires parent and candidate on the same fixed archive delta and named test set, alternating on a quiet
JVM, `seon.profile/measure` at the publication/request owner, readiness intervals, allocation, slot wait,
and identical test results. Keep only a measured end-to-end gain.

## Verification boundary

Initial default status: readiness complete, health faulted, two open faults and 47 errored receipts; zero
reported regressions. These predate this lane. `clj-kondo --lint src script test --cache false --parallel`
completed in 7.805 s with zero errors (1,689 warnings). Other lanes' modified source/test files were not
included in this lane's candidates. No default source was reloaded by this lane.

The note's first commit check was refused during live rehearsal by the foreign HEAD declaration
`seon.ref-test/a-reference-with-no-path-answers-an-error-that-holds-no-path`: dangling-id,
operator-check-20261014T161638.112.edn. The note changes none of that code. Its independent proof uses
`bin/lane commit --at-candidate`, the existing exact-candidate throwaway path, avoiding the foreign
live-store rehearsal while still booting and testing HEAD plus precisely this note.

That exact-candidate attempt also failed boot on the same foreign dangling task reference
(operator-check-20261014T161754.016.edn). HEAD e1db21f25 subsequently removes those stale test tags;
the note's next commit attempt uses that corrected HEAD. Neither failure is reported as a pass.

A fresh check of the original failing archive 39f97869b7f08fdde96a67da55800a64868f8e65 with 100 real
`seon.test*` identities completed transport and budget calculation, reaching test selection in 116 ms.
It then explicitly refused fixture identity `seon.test-runner-failure-fixture/distinct-error`
(operator-check-20261014T162004.871.edn, total 50.155 s). This is transport evidence, not a passing test run.
The original 30 s failure still lacks its exact argv/form; list length alone does not reproduce it.


## Note commit and CLI follow-up

The note committed as 19a8e2c1b after run 549d9720e102 passed four assertions. Total 125.783 s:
24.554 s queue, 69.878 s seed (including a 63.418 s template build), 5.101 s prepare,
13.507 s JVM launch/boot (6.191 s readiness), 0.313 s adoption, 5.839 s tests. In parallel,
rehearsal spent 29.651 s publishing and 1.176 s adopting **zero identities**.
Root had restarted onto e1db21f25; this lane did not restart it.

`seon.operator/seeded-store!` deliberately keys a template by base commit AND slot: the Datahike
store identity is derived from its canonical path. A shared template cannot simply delete the slot
from that key. Investigate that dependency seam before proposing cross-slot template reuse; the
63.418 s measured cold build is not evidence that changing the key preserves a valid store.

The CLI follow-up changes `seon.operator/parse-argv` to answer usage before reading operational options,
using one usage table for general and command help. `seon.operator/-main` prints the returned usage.
The two parser regressions give four failures and six errors on the parent, then ten passing assertions
on the change. The actual Babashka entry point answers check --help with the command's usage and exit 0.
No timeout or queue policy changes. The silent-check failure remains unproven and unfixed; the two cold
and warm 100-identity probes above are the reproducible counterevidence to list length being sufficient.

Exact read-only JVM forms used for the profile and boot timeline (the latter on this lane's owned
throwaway endpoint, not on default):

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      tests (into #{} (take 100)
                  (seon.db/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] database))]
  (seon.profile/measure #(seon.test/requested-bound-ms database tests #{})))

(select-keys
 (seon.cluster.boot/readiness (get @seon.operator.runtime/running-instances "default"))
 [:seon.boot/layers :seon.boot/ready-ms])
```

The CLI candidate's run ccdc0955f6ec executed eight tests: the seven CLI/parser tests passed;
`seon.cluster.publication-host-test/live-init-keeps-the-host-process-and-launches-no-jvm` alone failed
(eight assertions, one error). Its operator calls reported Request root does not match this process;
its later lookup reported an absent fork branch. Diagnostic: operator-check-20261014T163136.103.edn.
The exact parent 840d175a854e88fd88e4ceafe2767553adf44add reproduced the same eight failures and one
error in run 39aa091d2489 (66.215 s; operator-check-20261014T163419.614.edn). The candidate had the same
14 passing integration assertions plus 24 passing assertions in the seven CLI/parser tests. This
pre-existing integration red is excluded by var from the final commit check; no test was deleted or
relaxed. The foreign root/branch boundary is outside this lane's files.

## Final proposal and acceptance boundary

CLI fix committed as 3b48555eb. Run 19adf9604939: seven tests, 24 assertions, zero failures/errors,
5.127 s test request; whole check 70.289 s, queue 0.004 s, seed 3.114 s, prepare 4.475 s,
boot 48.671 s (readiness 39.520 s), adoption 1.709 s, test client 5.227 s. Candidate lint:
8.848 s, zero errors, 1,689 warnings. A concurrent committed clj-kondo pin change made the operator
use its existing dependency-change path: the candidate booted/tested its own archive, without
attempting to reload a different dependency into default. This is not a parent/change performance
comparison. Operator source grew by 32 lines, regressions by 30; neither bin/lane nor bin/seon changed.
The installed bin/seon selects the running JVM's operator archive: RESTART NEEDED to expose the fix
there. The changed Babashka entry point and exact candidate were tested; this lane never restarted default.

Recommend a bounded profile/prototype at `seon.cluster/full-source-refresh!` during the boot
source/current interval, using the same pinned dependencies, template basis, changed files and tests
for parent and candidate. Preserve the publisher's existing digest comparison; remove measured
repeated preparation at its producer. The measured 20.354 s interval is the **maximum opportunity**
on the drifted fresh check, not a promised gain. The same-base check's readiness was only 6.191 s,
so this cannot be quoted as a universal per-check saving. The expected gain is the portion of that
interval the profile proves independent of changed inputs; it remains unmeasured.

Do not start by deleting the parallel default rehearsal for a claimed wall-time win: in the fresh
74.820 s check it completed after 32.939 s, before the queued throwaway path. Its 23.365 s publication
cost is real CPU work, but removing it alone has no demonstrated critical-path saving. Exact-archive
JVM reuse has a larger conditional ceiling (about 35 s for seed, prepare and boot in the isolated
45.734 s check), but only on repeat hits; measure hit frequency and retain the existing test-isolation
semantics before choosing it. A changed dependency needs its own JVM regardless.

No performance implementation was built or committed. Parent/change end-to-end measurement,
including allocation and positive test evidence on the identical probe, remains the acceptance
condition. The silent 30 s check failure also remains open: its original exact argv/form is missing,
and neither the warm budget probe nor the cold 100-identity request reproduced it. No retry,
timeout bump, extra slot, or speculative parsing/transport check was added by this lane.
