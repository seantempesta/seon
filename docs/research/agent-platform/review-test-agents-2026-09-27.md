---
type: review
status: REVISE
created: 2026-09-27
reviewed: 378b1529f
scope: test agents on Flow; blocking findings only
---

# Review: test agents on Flow

**REVISE.** Keep option B's direction: several clusters' non-platform tests must run concurrently
in one JVM, each member through the one REPL path, with MCP and agent requests sharing that path.
The proposed deletions are blocked by the six findings below. The −535 source-line estimate is
not an implemented or measured diff.

The review used MCP `eval_clj`, root `/Users/sean/src/seon`, cluster `default`, owned session
`test-agents-review`, JVM namespace `tmp.test-agents-review`. Bodies and writes used the disposable
`:test-agents-review` branch or owned descendants. No test request, reload, adoption, reset,
restart or production definition change was made. These are diagnostic probes, not recorded green tests.

Exact forms: [proof.clj](../../../tmp/test-agents-review/proof.clj), numbered P1 onward;
complete returned envelopes: [initial](../../../tmp/test-agents-review/envelopes.json) and
[later](../../../tmp/test-agents-review/envelopes-later.json), and
[final checks](../../../tmp/test-agents-review/envelopes-final.json). Failed exploratory forms are retained:
P3 selected the Runnable submit overload and returned nil; P4 corrects it with an explicit Callable.
P12 encountered an unloaded test namespace; P13 corrects that observation. P20 used the wrong
store location; P21 verifies cleanup using the retained owned handle. Failed probes are not proof.

## 1. Graph termination does not prove compute-body exit

**Evidence, P10 (35 ms).** A one-proc graph observed its submitted compute Future for 20 ms.
After that wait timed out, stopping and joining the graph returned:

```clojure
{:observation :timeout :join nil :graph-exited? true
 :body-exited? false :future-done? false}
```

The body was held by a finite latch, then released and joined in cleanup. The installed
`seon.flow/join-graph!` joins only the graph's private executor
(`src/seon/flow.clj:138`); the body in §5.2 is on the separate process compute executor.
The statement “the graph does not join” while the body remains alive is therefore not an
existing guarantee. A proc waiting forever instead cannot itself record the timeout or process stop.
A member also cannot synchronously join its own executor from its stop transition.

**Smallest fix.** Keep actual body-exit ownership until a body-finally completion arrives.
Let the run agent observe the deadline and record unfinished; let the member retain its branch
until actual exit. The parent joins the member graph after that exit. Specify this ordering before
deleting `bounded-result` and its watcher; a cancelled/done Future is not an exit fact.

## 2. Moving the body to compute drops its world

**Evidence, P4 (4 ms).** With the branch connection and member bound on the submitting thread,
an explicit Callable on the installed compute executor returned:

```clojure
{:plain {:connection? false :member? false :virtual? false}
 :bound {:connection? true :member? true}}
```

P15 confirmed that connection custody, test member, kernel host world, schema projection and
effect request context are dynamic Vars. Thread-local means non-serializing; it does not mean
automatically conveyed. Today's `bounded-result` explicitly uses `bound-fn`, replaces custody,
and binds the member/host world (`src/seon/test.clj:250–292`).
`var-process` also documents that Flow conveys no bindings (`src/seon/flow.clj:293`).

**Smallest fix.** Carry the member's environment, projection, context, effect owner and
connection as stage inputs, and enter those owners on the worker thread before resolution,
seeding and evaluation. Preserve the existing binding behavior in the cut. Apply it at the
common body entrance so MCP and Seon agents obtain identical custody and evidence.

## 3. A fixed pool of blocking bodies can starve nested requests

**Evidence, P2/P7.** The actual executor has 18 platform workers, an effectively unbounded
`LinkedBlockingQueue` (remaining capacity 2,147,483,647), and initially zero completed tasks.
Eighteen finite held bodies produced `{:active 18 :queued 1 :last-done? false}`; releasing
them allowed the queued body to finish. This verifies the active-worker bound, not progress.

Existing non-platform harness bodies synchronously run nested requests; for example
`seon.test.one-request-test/a-member-that-never-exits-holds-only-its-own-test-and-the-next-request-runs`
(`test/seon/test/one_request_test.clj:175`). If every worker waits for a nested member,
all nested members queue behind their parents. A timeout can end the wait but is not a successful
test result. The same exhaustion arises from enough uninterruptible host bodies.
The estimate “body CPU divided by 18” does not account for these waits or other JVM work.

**Smallest fix.** Keep request orchestration and waits on the agents' `:io` procs; submit
only work whose completion does not require another queued member to the fixed compute pool.
Make nested test requests asynchronous through the same request/delivery path and keep
nonterminating host work in its declared platform isolation. Prove saturated nested requests,
not just N+1 independent bodies, before claiming progress for every cluster.

## 4. Direct-body classification misses fixtures; deleting S17 is premature

**Evidence, P14 (3 ms).** Static forms read through MCP from the *loaded archive* positively found
`seon.profile-test/an-sci-installation-owns-its-own-cell`. Its body contains no mutation from §5.4's
listed set, but the same namespace installs:

```clojure
(use-fixtures :each preserving-roots)
```

That local fixture contains two `alter-var-root` sites, iterating over instrumented Vars
(`test/seon/profile_test.clj:20–33`). It is neither the test body nor a directly called
`seon.test-support` helper. Local helper chains, once/each fixtures and top-level namespace load
effects must participate in classification.

There is also production arming outside test bodies: `arm-first-loads!`
(`src/seon/test.clj:2138–2200`) computes fresh namespaces, requires them, diffs `all-ns`,
then calls global `instrument/apply!`. Simultaneous first loads can enter that interval together.
Clojure serializes each `require` through `RT/REQUIRE_LOCK`
(`reference-code/clojure/src/clj/clojure/core.clj:6146`); that does not make the surrounding
discovery/arming interval atomic. The audit's classification does not remove this owner.

**Smallest fix.** Derive host-global effects over the actual test invocation graph, including
fixtures and local helper chains, stopping at explicitly understood production boundaries instead
of traversing indiscriminately into every production armer. Include first-load/arming as publication
ownership with a positive ready state before bodies start. Route the unconverted mutation cases
to platform. Remove S17 only after that coverage is demonstrated.

Additional shared-state checks, to delimit this finding:

- **Loaded identity:** P13 exercised the real detector with an owned identity atom; a changed
  identity returns `:seon.cluster/adoption`. It detects overlap, not a mutex. No live identity changed.
- **Central cache:** P11 (27 ms), using a disposable cache through `seon.cache/lookup`, proved a
  same-key caller waits and receives the first derivation; a different key completes independently.
  This serializer is absent from the audit. It is not a global test lock. Cache budget/configuration
  mutations and assertions on process-wide counters are global subjects.
- **SCI base:** P13 found distinct base maps and env atoms, and no leaked private definition.
  A shared atom placed in an owned SCI ancestor *was* shared across its forks (value 1).
  Forking isolates definitions, not arbitrary mutable objects inherited in their values.
  Use clean member worlds and classify shared-object mutation; do not claim base-map isolation
  proves deep isolation.
- **Dynamic Vars:** P4/P15 establish the handoff problem in finding 2, not serialization.
  Global profiling counters also include concurrent activity; `seon.profile/explain` documents
  this explicitly (`src/seon/profile.clj:230`).

## 5. Pressure admission has no specified resume wake or allocation bound

**Evidence, P7/P15/P16.** With 18 workers occupied and another queued, kernel pressure remained nil.
Heap usage later was 5,154,414,816 of 10,997,465,088 bytes; last collected old-generation usage was
4,894,367,968 bytes. These are snapshots, not retained per-agent sizes or a peak bound.

`kernel/memory-pressure` checks a volatile state, and clears it only when called and collection
usage is below the resume threshold (`src/seon/sci/kernel.clj:161–173`). P16 used its supplied-state
arity: pressure cleared to nil, with no branch transaction. `watch-memory!` subscribes to the
*enter* collection-threshold notification (`:175–194`), not to a resume fact for waiting run agents.
A run whose members all refuse can have no member exit to wake it. “Next wake” in §5.3 is unspecified.

The queue holds no allocation reservation: arbitrarily many member graphs/contexts may be
created while the last collected usage remains below enter. Pressure is a reactive admission
guard, not a hard guarantee that concurrent allocations remain within heap.

**Smallest fix.** Keep pending members as facts until resource admission permits materialization;
specify a kernel-owned resume notification delivered to waiting run graphs, including the zero-running
case and deadline delivery. Describe pressure honestly as reactive and measure retained/peak member
cost under concurrent admission before claiming a heap bound. Do not add a second pressure monitor.

## 6. Recording must be a supplied stage, not an unresolved post-loop call

**Evidence, P1/P15 and the named spec.** `evaluate-batch` is not installed on this JVM.
The installed body owner is `runner/run-var!` at line 438, and recording is
`runner/commit-results!` at line 1813. Thus no installed stage-valued test recording was demonstrated.
The checked-in one-path spec remains revision 2 (`eb590e3ec`); revision 3 is in progress and was not edited.
Its test-body entrant says no/no/no, its request declares only `:seon.eval/settle?`, and S13 leaves
recording outside the batch. These are precisely [V2-8](review-one-repl-path-spec-2026-09-27.md#v2-8--p1-every-entrant-calls-the-loop-but-recording-is-still-split).
§5.5 of the concurrency proposal leaves that ruled requirement as a question.

**Smallest fix.** Make the existing test evidence writer a supplied recording/settlement stage
in the common orchestrator, retaining the evaluated body value when recording fails. Separate
“no program derivation” from “no recording.” Settle each owning event once; a later actual-exit
event may need its own durable transaction. Align S13 and this cut before implementing their
deletions. Prove invocation count, transaction ownership, and identical value/fault delivery from
MCP and `my.test`; a new MCP-only test tool is not the fix.

## Measurement audit and landing evidence

These observations support the findings; they are not additional blockers.

| Requested claim | REPL result and limit |
|---|---|
| 3.5× | P9 verifies the arithmetic: 6 × 12.1 / 20.7 = 3.50725. No request-level benchmark was repeated because this lane is prohibited from starting test runs. The original multi-request timing remains historical, not independently reproduced or a proof of option B. Root must repeat serial and N=6 requests on disposable worlds, retaining member outcomes as well as wall/CPU/GC measurements. |
| Per-branch writer not a bottleneck | P8 held one branch's writer inside a finite transaction function. A second write on that branch waited; a write on another branch committed in 110.43 ms. All three committed. This proves independent writers, not that shared recording or store I/O can never bottleneck. |
| Roster permit | P5 held the real store's roster permit; second roster admission refused with `:reachability-permit-unavailable`, while a blob permit succeeded. Both released. Store-wide exclusivity verified in 2 ms; 23/66 ms historical latency not remeasured. |
| 2.8 s fixed work | Historical parts sum to 2,779 ms. P9 measured selection 1,697.20 ms for 14 members and `program-written-since?` 1,273.26 ms even at the current basis (false). Both exceed one second and reproduce the costly owners, but this is not the old 13-member population. The 900 ms clean-world and 701 ms admission totals were not reexecuted; no test reservation or checkpoint sweep was made. |
| Compute pool | P2/P7 verify 18 active workers and queueing. Findings 2–3 limit the claimed guarantee. |
| Per-test fork/graph | P6 built five real one-proc graphs, each with an isolated branch and SCI fork, ran `(+ 1 1)` on compute, then stopped/joined and released. Branch/context acquisition: 89.76 ms first, then 30.28–31.52 ms. Additional SCI fork: 0.003–0.018 ms. Graph construction: 0.138–0.266 ms. Body dispatch/evaluation: 0.269–0.440 ms. All returned 2 and joined. Whole probe 265 ms. This measures primitives, not host-agent entity creation, seeding, arming, one-path recording or retained memory. The proposed one-path API is absent. |

The slow selection and program-history checks belong to the existing test-cost findings, not a new
performance fix in this review. Instrumentation contamination is already tracked by task
`b9933ca067dc` (`resources/seon/tasks/seon.test.edn`). Root owns task ingestion for the blocking
cases above; this lane did not edit task files or the issue index.

Live baseline: PID 71396, start 2026-09-27T22:01:39.330Z, archive `cd4ca3d39`.
Both operator status and MCP reported **faulted**, 13 open fault signatures, including
`seon.sci.kernel/acquire-arm`; readiness was 47,189 ms. These pre-existing faults are not green
or attributed to this review. Shared activity makes timings observations, not isolated throughput.

Dependency pins at reviewed HEAD: Datahike `591c825d`, SCI `743d1c43`, core.async `dc35f3e0`,
Malli `43c6dca8`. No dependency was changed. The temporary probe file is roughly 5,000 estimated
tokens. Envelopes preserve the exact returned tool responses; P19 retrieves a compact, complete
finding-location list after P18's contract messages hit the printer budget.

Cleanup: P17 released the parent execution, found none of the five graph-sample branches in the
roster, and observed compute active/queued = 0/0. MCP retirement confirmed the named branch retired.
Writer-probe descendants release in finally. All probe latches/futures and graph executors completed.
P21 positively verified the named branch and scratch host namespace absent. Later static checks
used `user` and defined no Vars. Temporary archive directories retain static-check evidence only;
they are not running operator roots.

Static validation used an owned `git archive` of HEAD `71af4f638` plus this document, after another
lane advanced HEAD during review. `clj-kondo --cache false --lint src script test`: **0 errors,
948 warnings**, 8,519 ms, exit 2 for warnings. [Lint output](../../../tmp/test-agents-review/lint-final.txt).
The static `seon.contracts-compile-test/check` examined **486 files**, with the archive's packaged
projection, in **2,725.65 ms** (MCP 2,734 ms). Its population-wide work is proportional to those
486 files; it starts no test run. It reproduced **ten pre-existing contract compile errors**:
`script/seon/dev/docstring.clj:285,318,334,360` and
`script/seon/dev/markdown.clj:1020,1050,1090,1124,1170,1215`. There were **zero source/test findings**.
These developer-script contracts are the foreign boundary, already recorded by the one-path
review; this is not a green whole-tree contracts check. Shared uncommitted files were excluded.
Production/source/test net change: **0**. Only this review document is committed; probe evidence
stays under `tmp/test-agents-review/`. No runtime publication clock applies to a document change.

No runtime tests are needed for this documentation-only commit. Root's focused implementation
round should include these existing tests (not run by this lane):

- `seon.test.one-request-test/a-member-body-runs-on-its-own-listed-branch`
- `seon.test.one-request-test/a-body-outliving-its-bound-is-released-only-after-it-exits`
- `seon.test.one-request-test/a-member-that-never-exits-holds-only-its-own-test-and-the-next-request-runs`
- `seon.flow-test/stop-joins-the-graph-only-after-its-procs-exit`
- `seon.custody-stability-test/cross-cluster-write-isolation`

New regression targets, explicitly not installed: concurrent two-cluster requests with member/effect
custody; saturated nested requests; graph join before external-body exit; fixture/helper mutation
classification; pressure resume with no running members; one body value and one owned recording
through agent and MCP entrants, including recording failure.
