# Why the central round takes an hour

The largest apparent runner cost is missing evidence, and the largest repeat-round waste is the driver's forced
rerun. The runner already stores results by reach digest and reuses red results. Removing the forced rerun from
`seon.central-round/batch-form` uses that existing mechanism. No runner, fixture cache, timeout recovery, test
exemption, or result store is added.

Status: driver fix and two measured fixture tasks in this commit; publication and terminal-event waits remain expensive. The change is
identified by commit subject **“Reuse central-round results and account for the hour”** (resolve with
`git log -1 --format=%H -- script/seon/central_round.clj`). No claim that a complete corrected round has finished.

## Basis and evidence

All new probes used an isolated committed root at `61b9534d7ec0e879a20f9de1b941be48e5b6fb55`, pid 30596,
start instant 2026-10-12T11:24:30.132Z; initial readiness 44,653 ms. It was started under `setsid`.
Default was never stopped, reset, restarted, or used for these measurements. Initial default status was degraded:
a program-context cache warning (task f204e0c69368) and the recorded Keyword-to-Map.Entry error on turn
5b78e62435b4. Normal edit hooks evaluated this lane's tests on its own SCI branch; their arity refusals while the
platform script was not installed are not proof of the candidate. Candidate archives were checked separately.

The supplied driver files were read, never edited or resumed. Their result file was still growing; this report froze
2,750 results and 119 events, including 114 requests. The first 2,425 results and first 101 requests reproduce the
assignment's numbers. Recovery logs name commits 48bb5cfee, aea2a1052, 45bfab18e and 7ef4967db. Thus the accumulated
round is **not a single-HEAD baseline**; it contains two recorded input digests. Its timings describe what happened,
not what every test costs on current HEAD.

[Evidence](round-waste/evidence.edn) preserves the budgets, named 25-test selection, individual timings and
allocation, profile counters, slowest tests and fixture reach sets. [Probe](round-waste/probe.clj) is the reusable
PREPL form; replace its symbols with the same selection for both measurements. Allocation MB/GB below are decimal.
`seon.profile/measure` reports calling-thread allocation separately from approximate all-thread allocation. Neither
is retained heap. Inclusive function times overlap across callers and threads and must not be summed.

## The time budget: the 1,327 seconds are mostly unanswered requests

| Original prefix: 101 requests, 2,425 returned results | Seconds | Minutes |
|---|---:|---:|
| Returned test bodies | 2,287.986 | 38.133 |
| Other time in the 97 answered requests | 126.403 | 2.107 |
| Four requests with no terminal answer, each at the 300 s transport bound | 1,200.023 | 20.000 |
| Total request wall time | 3,614.412 | 60.240 |

The subtraction called “outside bodies” is 1,326.426 s. **90.5% is the four unknown requests**. Bodies may have run
inside them, but their results never reached results.edn; assigning their time to setup or recording is unsupported.
Actual answered-request overhead is 5.24% of answered wall time, averaging 1.303 s per 25 tests, not 13 s.

The frozen larger snapshot has 2,548.168 s bodies, 141.875 s other answered work and the same 1,200.023 s unknown:
3,890.066 s / 64.834 minutes. Initial boot, four recovery boots, shell startup and file output **outside** each
`run-batch!` timer are extra. The recovery log clips the operator response before timing, so those minutes cannot
be recovered honestly from this log.

The four unknown requests start at:

- `seon.cluster.agent-namespace-test/source-bearing-namespaces-without-a-steward-derive-one-problem-line`
- `seon.cluster.publication-inputs-test/file-entity-selection-follows-declaration-and-finding-refs`
- `seon.cluster.reload-per-declaration-test/ordinary-defn-reloads-only-its-namespace`
- `seon.cluster.wake-test/a-live-but-non-accepting-route-still-faults-loudly`

These identify requests, **not the test that stalled**. The scratch driver's `restart!` deletes the store after an
unknown transport outcome and continues on current Git HEAD. It also keeps completed symbols in `done-syms`, so an
old result can suppress a changed test while its database evidence has been deleted. Use the maintained
`seon.central-round/run-round!`, which already preserves the complete answer and ends on unknown execution.
Diagnose the surviving execution before recovery; do not treat a client timeout as body termination.

## Later unfinished executions and revision identity

A second snapshot freezes 144 events / 134 requests / 2,807 results: 4,620.372 seconds request wall,
2,982.197 seconds returned bodies, five unknown requests totaling 1,350.027 seconds, and nine rebuilds.
The remaining 288.148 seconds includes unfinished-body work, so it is **not** pure runner overhead.
The ten unfinished answers below are in [later evidence](round-waste/unfinished.edn). Their request times include
other tests in the same request; they are not ten measured body durations.

| Unfinished test (namespace abbreviated below) | Request seconds | Observed or source-supported cause |
|---|---:|---|
| blob-publication / publication-and-collection-are-exclusive-in-both-orderings | 25.090 | Calls full publication before its collection/publication interleavings. The independently measured fixture exceeds this entire request bound. No thread evidence identifies its precise stop point. |
| agent / n-agent-parallel-turns-property | 40.136 | Generated parallel trials await terminal database facts; fixture agents have no assigned tasks. Failure shrinking repeats trial waits. |
| agent / park-wake-test | 5.164 | Reproduced: missing closed-turn event, zero provider calls, no answered message; details below. |
| agent / pause-during-in-flight-call-test | 35.086 | Awaits provider-entered before pause. Fixture has no task assignment; later waits also need turn facts. |
| agent / release-joins-every-owned-graph-before-it-closes-the-branch | 15.166 | Awaits stuck-prelude-entered before exercising release. Fixture creates agents but assigns no tasks. The timeout does not establish that release itself leaked. |
| agent / restamp-recovery-test | 5.092 | After recovery, expects unanswered messages to produce new model turns; fixture assigns no tasks. |
| agent / routing-conservation-waits-for-terminal-evidence | 5.095 | Awaits provider-entered from routing-trial; the trial creates agent entities and messages without task assignment. |
| agent / stop-flow-interrupts-an-in-flight-turn-and-records-it-interrupted | 39.153 | Awaits interruptible-prelude-entered before stop; fixture assigns no task. No evidence here establishes a stop implementation hang. |
| agent / wake-routing-conservation-property | 25.183 | Same routing trial; its source explicitly documents repeated 20-second failing trials during shrinking. |
| armed / a-message-committed-during-boot-start-is-conserved | 5.133 | Full publication precedes boot and its listener observation. This one does assign a task. Only 2,158 ms remained for the body after acquisition. |

Here **agent** means `seon.cluster.agent-test`, **armed** means `seon.cluster.armed-test`, and
**blob-publication** means `seon.blob-publication-test`. Except for park-wake, the cause classification is a source
trace paired with the measured unfinished answer, not a reproduced terminal diagnosis. Missing assignment is a
verified necessary precondition, not a claim that assignment alone repairs every fixture.

On the isolated root, park-wake returned unfinished at 5,140.37 ms and 5,099.11 ms. The first call spent most of
its bound loading the namespace (only 761 ms remained). After the second answer, its body thread was absent from
a dump taken eight seconds into that execution; the JVM stayed available. A diagnostic request allowed terminal
observation for 30 seconds while preserving the test's **5,000 ms** declared failure bound. It returned at
20,450.98 ms: body 20,184.28 ms / 145.50 MB, four failures and one error. At 12.98 seconds the body had used only
89.77 ms CPU and was in `seon.test-support/await-event!`, awaiting `:seon.cluster.agent-test/closed-turn`.
The provider count was zero and the answered-message map empty. This is waiting for work the fixture never
caused, not 20 seconds of useful computation.

`seon.turn/next-agent-work` explicitly needs a held task. Inserting the existing `seon.test-support/assign-task!`
into park-wake was tried in candidate 4a3b4c82f77eef93b525fb1b2d7bb984a06f0c31: body 61,602.87 ms,
request 66,588 ms, still zero provider calls, four failures and three errors. That change was **discarded**.
The fixture needs a fuller trace of assignment, execution branch and graph observations; adding setup blindly is
not a performance fix. The existing park-wake assertions now carry a whole-body `seon.profile/measure` assertion
and task `seon.cluster.agent-test/wake-fixtures-observe-real-terminal-events`; no assertion or bound was relaxed.
Candidate run 1aac362520e6 confirms that assertion red at 20,454.65 ms; the four central-round tests are green.

A rebuild is not implied by unfinished execution. `seon.test/bounded-result` starts an exit watcher, joins the
actual body, and only then releases that test's context. It ends the current request before another body starts.
Release before actual exit would violate this ownership rule. After these probes, another named request on the
**same process** completed green in 373 ms PREPL time; no rebuild was needed. That demonstrates availability,
not a proof that every timed-out fixture cleaned up every graph. The request parent's cleanup and late result
retention still deserve lifecycle verification; neither an unfinished answer nor a missing thread proves all
resources are gone. Preserve the complete answer and inspect the live execution before deciding recovery.

The maintained driver now captures the selected PREPL advertisement **once** in `seon.central-round/-main`,
uses it for every request, and includes it with every saved answer. It has no rebuild loop and never rediscovers a
replacement JVM between requests. The operator runs that isolated process from one committed archive; keep that
process for the round. A lost endpoint ends the round with its evidence instead of following current checkout
HEAD. The external script's nine rebuilds and symbol-only completion set cannot represent one revision and should
be retired. This change retains the process and store rather than adding a revision-checking mechanism.

The original isolated process was no longer live when the late probes began. Its retained root was started again
at the same 61b9534d7 revision: pid 38218, start instant 2026-10-12T11:48:35.090Z, readiness 5,166 ms. Those late
measurements are separate from the quiet before/after reuse measurements. Default was not restarted.

## Runner stages, measured on the isolated root

Same first 25 named tests as the stored round, three forced requests: first 6,301.64 ms; warm third 3,230.21 ms.
The first had 3,306.89 ms body time, 298.29 ms acquisition and 35.07 ms resolution. The warm third reconciles as:

| Exclusive portion of warm request | ms | Owner / interpretation |
|---|---:|---|
| Bodies | 2,490.49 | `seon.test/measured-body`; fixtures are inside this number |
| Acquire 25 isolated executions | 248.31 | `seon.test/member-result` acquisition; branch and SCI fork together |
| Resolve 25 tests | 23.19 | `seon.test/resolve-test` |
| Execution beyond bodies | 132.14 | `seon.test/bounded-result`; includes 11.67 ms release |
| Remaining per-test work | 39.74 | includes 38.30 ms `seon.test.runner/restore-live-cluster-schema!` |
| Request-level work | 296.33 | selection, clean execution, admission, completion and outer bookkeeping |
| Total | 3,230.21 | differences below 0.02 ms are rounding |

Within request-level work: `seon.test/selection-admission` 9.75 ms, `seon.test/clean-request-handle` 49.37 ms,
`seon.test.runner/reach-digests` two calls totaling 0.50 ms, `seon.test.runner/commit-results!` 186.49 ms.
The remaining 50.22 ms includes admission's checked transaction and bookkeeping. Its transaction function
`seon.test/admit-run` accounts for 2.68 ms of that, not the full transaction. Recording includes 23.35 ms in
`seon.test.runner/record-tx`; it is not an additional charge. No new timing instrumentation was added.

Across setup **and bodies**, 42 calls to `seon.cluster.registry/fork!` cost 148.32 ms and 41 calls to
`seon.sci.eval/fork-cluster-ctx` cost 175.03 ms. Bodies themselves acquire fixture executions, so subtracting these
inclusive totals from setup would double-count. The installed per-test timings do not split branch creation from
SCI acquisition. PREPL terminal time was 3,241 ms for the warm request, about 11 ms beyond the measured call;
local file append and BB startup were not in that window.

Reach is not today's dominant unchanged-round overhead. R11 work is already partly installed, including
6433c7593's reuse of components. Do not reimplement the older survey's whole-program algorithm as a new fix.

## Allocation and fixture publication

Original prefix: 1,333.155 GB body-thread allocation; 176 tests over 1 GB. Frozen snapshot: 1,544.774 GB;
210 tests over 1 GB account for 1,279.496 GB (82.8%). Just 28 tests over 20 GB account for 891.661 GB (57.7%)
and 20.23 minutes of body time. Allocation is concentrated, not an hour of uniformly expensive assertions.

Measured example, `seon.shell.jvm-test/binary-output-is-byte-exact-on-both-sides-of-the-inline-ceiling`:

| Measurement | Value |
|---|---:|
| Whole runner request | 44,494.98 ms |
| Body | 44,063.15 ms / 30.150 GB |
| JVM window | 76.055 GB |
| `seon.cluster/refresh-source!` | 42,429.10 ms |
| `seon.cluster.source/publish!` (inside refresh) | 28,469.15 ms |
| `seon.fn/index!` (inside publication) | 25,584.14 ms |
| `seon.fn/analyze-entities` (inside indexing) | 13,532.22 ms |
| All checked writes, inclusive | 23,786.03 ms |

The test is green. Publication is 96.3% of body time. The independent fixture entrance, reading only one existing
function, costs 44,073.39 ms / 29.405 GB on its calling thread, 74.396 GB JVM-wide. That accounts for the magnitude
of the shell test's allocation without attributing concurrent writer allocation to its body thread.

Static reach on the measured HEAD maps the stored snapshot to 19 tests reaching
`seon.test-support/populate-published-root!` (760.37 s / 528.08 GB), eight reaching
`seon.test-support/populate-published-operator-root!` (346.73 s / 219.08 GB), and nine reaching
`seon.test-support/with-published-file-database` (361.97 s / 278.17 GB). These sets overlap; indirect or computed
calls can escape static reach. Their sums are not a publication-only time estimate.

History: 8bc917872 removed the published-base launcher and made both population helpers call full refresh for each
fresh root. Restoring its deleted cache/property path is not the fix. `datahike.versioning/branch!` shares immutable
indexes; `datahike.versioning/fork-database` copies every store key. The existing test execution uses
`seon.cluster.store/open-overlay!`, a memory frontend; `seon.blob/stage-file!` requires a file-backed process root.
The remaining fixture task must compose those ownership requirements while preserving the executing program and
private filesystem semantics. A branch of the physical source store alone would lose an interpreted member's changes.

The snapshot's median is 32.69 MB, at
`seon.render.web-debug-test/every-declared-attribute-has-an-ordered-pair-even-when-absent`. Its current warm request
measured 408.15 ms / 311.26 MB JVM-wide; the body was 109.44 ms / 85.52 MB. It writes three synthetic schemas,
derives the projection and renders attribute blocks. Seven checked writes across runner and fixture cost 192.58 ms;
clean execution 66.88 ms, result recording 58.59 ms. Its first namespace load alone cost 594.99 ms, so cold request
cost is distinct from body cost. It does not republish the checkout.

A separate JFR sample of that request recorded collection construction and Fressian serialization on the async
workers: 12.20 MB sampled Object arrays, 6.45 MB MapEntry, 4.64 MB byte arrays; 6.27 MB of sampled stacks build
vectors from persistent sorted-set sequences and 5.99 MB include Fressian handler lookup. See
[allocation sample](round-waste/median-allocations.json). These are sampled weights, not exact per-function bytes;
five-frame stacks do not prove a deeper producer. The median is ordinary fixture/schema/query/recording work,
whereas the largest allocations are full publication. There is no evidence here for a new runner cache.

## The ten slowest returned tests

Numbers are measured in the supplied mixed-revision round. Classification comes from their current source and the
fixture measurements above; these ten were not each repeated on HEAD. Parent-thread allocation omits child JVMs.

| Test | Body s | Body GB | Why it is slow |
|---|---:|---:|---|
| `seon.pass-probe-test/a-pass-of-each-entrant-is-measured` | 120.79 | 0.039 | Four assertion failures; `seon.pass-probe/outside` and `model-pass` await a 120,000 ms bound. This is incomplete execution evidence, not necessary CPU work. The returned scalar export does not identify which awaited event failed. |
| `seon.dev.fresh-operator-export-test/export-verb-produces-an-openable-queryable-store` | 94.45 | 31.80 | Fresh publication, real child start, CLI export/reopen; five failures and an error. Publication is repeated setup; independent export/reopen is the behavior under test. |
| `seon.schema-redeclare-test/seeded-schema-families-survive-development-adoption` | 66.34 | 0.009 | Child JVM publishes, boots, seeds and reloads; two failures. Low parent allocation hides the child's allocation. Reload survival needs the real child; publication before it is setup. |
| `seon.cluster.fault-storage-test/oversized-fault-evidence-is-bounded-and-retrievable` | 59.27 | 39.36 | `source-store` publishes first, then a repeated error storm and physical-store measurements; three failures. Fresh physical storage is relevant; reanalysis of unchanged source is not. |
| `seon.cluster.publication-host-test/live-init-keeps-the-host-process-and-launches-no-jvm` | 49.28 | 34.79 | Real initial boot/publication before live init; eight failures and an error. Process identity is the subject; initial publication dominates the setup class. |
| `seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation` | 48.96 | 34.87 | Full publication, then two cluster boots. Green. The two boots are required by its assertion; repeated source analysis is setup. |
| `seon.cluster.fault-storage-test/lossy-subthreshold-fault-evidence-says-it-is-capped` | 45.31 | 31.45 | Same full-publication fixture; one failure and one error. The small evidence case does not need whole-checkout analysis. |
| `seon.concurrency-independence-test/n-agents-fold-independently-on-one-live-cluster` | 44.94 | 34.86 | `with-cluster` publishes and boots before concurrent folds; two failures and an error. Actual concurrency remains necessary. |
| `seon.config-application-test/applied-values-shape-the-running-system` | 44.83 | 33.67 | `fresh-root` publishes before a real configured boot; two failures. Real runtime configuration is the subject. |
| `seon.cluster.publication-inputs-test/an-unchanged-explicit-path-is-captured-once-and-keeps-the-head` | 44.78 | 29.86 | Full fixture publication precedes the unchanged-path request; one failure. Historical metadata says a 6.10 s fixture, but this observation is 44.78 s. |

No test was deleted, weakened, or given a larger duration. None of these ten is explained by an intentional sleep
in its test body; the first is consistent with its explicit await bound. Do not assign its timeout to a particular
owner without the missing terminal-event evidence.

## Fix, proof, and reuse phases

`seon.central-round/batch-form` no longer sends `:seon.test/rerun? true`. Its added arity accepts the execution form
so the exact generated PREPL request can be tested on the test's own execution. The request still uses the runner's
bound, recording connection, selection and body lifecycle. The driver also holds its initial process advertisement for the entire round, as described above.

Same quiet JVM, same 25 symbols, same retained store: parent forced request 3,230.21 ms / 5,214.47 MB JVM-wide;
the identical generated request with only the forced-rerun key removed 26.09 ms / 18.73 MB, executed 0, reused 25.
That is 123.8× faster and 99.64% less allocation. Stored red results remain red and are reused. Calling-thread
allocation fell from the multi-GB request to 17.92 MB. This is an end-to-end request measurement, not a call-count
proxy. The first request remains necessary when there is no result at the key.

`seon.central-round-test/an-unchanged-round-reuses-its-results` was red before removal: executed 1/reused 0 on its
second request (run bb36f0a55227). After removal the four central-round tests are green in run 00f67df3b8bf.
The fixture task proof is intentionally red in that same run: readable declaration passes; setup 44,379.28 ms,
34,560,079,696 bytes, and whole test 45,682.45 ms versus the unchanged 5,000 ms bound. Commit validation names
central-round tests; it does not claim the filed fixture proof is green.

Reuse needs the round's store to survive. The maintained script already leaves it intact; invoke it against the
same isolated root. The external recovery script should be retired, not edited by this lane. Keeping OUT alone is
insufficient because it is neither the runner's keyed evidence nor a complete result history.

Existing hybrid phases, not a new result mechanism:

- R1 already reuses red results at equal reach; removing the driver's override makes this effective.
- R4 adds configuration to the result key. It remains needed; this fix does not claim reach alone covers every input.
- R5 preserves a base run on the asking agent's branch; R6 provides the common multi-database `results-at` read.
- R8 retains root's merge-gate observations on main and reads live branches, so accepted results survive branch deletion.
- R9 and R10 consume that same evidence for branch verification and platform checks. Main's compiled results must
  not stand in for interpreted results.
- R11 owns changed-program reach cost; R12 owns conflicting outcomes at one key. Neither justifies rerunning all tests.

The fresh isolated store cannot read main's evidence through today's central driver. Preserve its own results now;
use R6/R8/R10 for shared evidence when installed, rather than importing scalar OUT files or inventing another cache.
Fixture-observation tests are currently excluded from reuse by selection, so zero work for every unchanged test is
not an achieved guarantee.

## Remaining work and expected cost

Filed `seon.cluster.agent-test/wake-fixtures-observe-real-terminal-events` with the existing park-wake behavior
and a measured red assertion. Updated the existing task `seon.test-support/file-backed-branch-without-republication` with measured evidence and
the tagged proof `seon.test-support-test/a-file-backed-fixture-costs-a-branch`. It requires a subsecond, under-128-MB
fixture entrance that still reads the executing program. This is a filed red, not a speculative performance fix.
The task covers the observed fixture waste; broader population helpers used for boot/store subjects still need
per-subject ownership review before changing their semantics.

Known remaining runner work is already tracked by `seon.test/one-test-run-at-the-repl-allocates-over-a-gigabyte`,
`seon.test.runner/the-first-reach-digest-after-a-change-costs-what-changed`, and `seon.test/a-full-tier-on-one-jvm-retains-what-its-members-built`. The unknown requests need preserved execution/result evidence; their
first symbols are not enough to author a meaningful new red proof. No guessed timeout fix is filed as proven.

With this fix, the measured unchanged 25-test selection costs 26 ms instead of 3.23 s. Extrapolating that selection
rate to 110 requests gives about 2.9 s of selection work **only**; fixture-observation tests, missing keys, changed
inputs, cold namespace loads and unreadable results still execute. This is not a prediction of a three-second full
round. An all-new round still has roughly the observed 38–42 minutes of returned bodies plus 2–2.4 minutes answered
runner overhead until fixture setup changes. Removing publication from the nine file-fixture tests could remove
roughly 5–6 minutes from this snapshot; extending a correct composition to the other publication fixtures could
remove much of the 20-minute high-allocation group. These are opportunities, not measured post-fix round totals.

The 20 minutes of unknown execution cannot be called saved by ending the driver: those tests remain unproven.
The next central round should use one pinned revision, retain its store and complete answers, and reconcile only
matched request/body intervals. Then its remaining time is directly attributable.

Validation: `clj-kondo --lint src script test --cache false`: zero errors (1,615 existing warnings). Candidate archive
publication, reload, boot and four central-round tests passed. The file-fixture proof failed on measured costs and
duration; park-wake retained its pre-existing behavior failures and its added measured assertion is red. Foreign uncommitted files and the http-kit submodule's untracked content were left
untouched; they were excluded from the candidate slice. Final commit validation and cleanup are reported with the
commit result.
