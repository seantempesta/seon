---
type: review
status: REVISE
created: 2026-09-27
reviewed: 38e5a6d47
prototype: 33e878596
scope: test agents on Flow; execution blockers only
---

# Review: revised test agents on Flow

**REVISE.** Option B remains the right direction: concurrent non-platform tests from several clusters in one JVM,
each body on its own branch through the same REPL path. The revision supplies an executable batch/Flow composition,
but the six execution blockers below prevent replacing the current owners. The proposed deletions are not yet safe.

The prototype runs a body on a virtual thread, enters a carried world, and supplies recording to
`seon.batch/evaluate-batch` as a stage value. Run and member Flow procs observe completion. This fixes parts of the
original review; the new findings concern the remaining execution guarantees, not a demand for another runner.

## Evidence and scope

MCP `eval_clj`, root `/Users/sean/src/seon`, cluster `default`, owned session `test-agents-review-v2`.
Exact forms R0 onward extend [proof.clj](../../../tmp/test-agents-review/proof.clj);
[envelopes.json](../../../tmp/test-agents-review/v2/envelopes.json) preserves the returned envelopes, including failures.
P1–P22 retain the original audit evidence. No harness test request or gate was started.

R0 loads copies under **owned scratch namespaces**, with only namespace/require renaming: batch from
`38e5a6d47`, agent and thirteen proofs from `33e878596` (the commit that contains the prototype accompanying
the design revision). Literal `:seon.batch/*` keys are unchanged. The originals and SHA-256 manifest are in
[tmp/test-agents-review/v2](../../../tmp/test-agents-review/v2/source-manifest.json).
No foreign prototype namespace, source, spec or session was changed.

**R1/R2: 12/13 PASS, 8,302 ms.** All twelve revision-behavior proofs passed their declared schemas. The failing
`b3-counterexample` returned `[[:child :child] "java.util.concurrent.TimeoutException"]`: its parents have no
barrier before submitting children, so it does not deterministically saturate both workers. This does not refute
the revised virtual-thread design. The positive nested-progress proof passed in 267.81 ms.
The proof result schema checks `:pass?` is boolean; it is not a complete world, recording or delivery contract.
The rerun finished with zero running and zero waiting roster entries.

## 1. B1: a bounded close silently abandons the join

The revised `b1-exit-owned` passes: the branch remains held at the deadline, then release and member join follow
body exit. **R9, 186 ms**, tests the remaining close boundary with a real held member:

```clojure
{:unfinished [:held] :close-result nil :executor-terminated? false}
```

`close!` returns the same nil for its own wait timeout as for a successful join
(prototype `agent.clj:320`). `run-request!` starts an unrostered virtual closer with a 600,000 ms wait and
discards its outcome (`:327`). If the body exits after that wait, no caller remains to close the run graph.
The 20 ms probe verifies the failure at the common close seam; it does not claim to have waited ten minutes.
The probe released its body and joined both graphs afterward.

**Smallest fix.** Return the declared wait/join error, and keep the run's lifecycle owner until actual completion
and successful join. Observe late completion from that owner instead of dropping a bounded closer's result.
Keep current exit ownership until this includes close timeout and join failure.

## 2. B2: the carried world omits the effect/agent identity required by real bodies

`b2-world-carried` correctly restores connection, member, SCI host world and projection.
**R6, 218 ms**, additionally found agent id nil and effect request context absent inside `enter`.
**R7, 276 ms**, invokes the real effect entrance from a prototype member; it refuses before any capability effect:

```clojure
{:seon.error/operation seon.effect/request*
 :seon.effect/missing-context-member :seon.turn/id
 :seon.error/message "Invoke the capability from a current evaluation."}
```

The world constructor/entrance (`agent.clj:47,60`) supplies neither the attempt's agent/turn nor
`seon.effect/*request-context*`. **R22** also called the armed `my.test/run-owned` with that acquired world;
the contract refused its missing `:seon.agent/id` before any test selection ran.
Four restored bindings therefore do not justify deleting the current attempt-context owner.

**Smallest fix.** Construct the real agent/evaluation/effect ownership through the ordinary path and include it in
the one world value; enter the effect context alongside custody. Preserve the existing attempt context until its
replacement passes the real effect-reaching member contract. Do this in the common test entrant used by MCP and
`my.test`, not a wrapper-specific patch.

## 3. B3: roster identity collisions defeat admission, and nesting has no CPU/world bound

Replacing fixed-pool bodies fixes the demonstrated nested deadlock. It does not establish the claimed bound.

**R3, 175 ms:** two requests use the same member key, a shared process roster and limit 1. Their bodies are held
on a finite latch, so overlap is directly observed:

```clojure
{:limit 1 :first-started true :second-started true :roster-running 1}
```

`admit!` tests only whether the member key exists after the swap (`agent.clj:132`); an existing entry makes
a refused second admission look successful. Release is also keyed only by member and can remove the other run's
claim. The public request contract does not require globally unique member keys.

**R16, 306 ms:** one parent at limit 1 started six held children; all six entered and the roster contained seven
bodies. A nested request bypasses admission without a finite child bound. A waiting parent's slot does not bound
its independently runnable children or materialized worlds; the request's limit is not enforced on them.

**Smallest fix.** Key claims by the owning run/member identity and prove the swap acquired that exact claim;
release only that claim. Carry one process admission budget through nesting. A parent waiting for children must
yield runnable ownership so children can acquire it, without abandoning parent exit ownership. Preserve the bound
through the common nested-request path; simply exempting nested requests is not a bound.

## 4. B4: readiness does not cover overlapping loads, and classification still admits reloads

The revised invocation-graph proof catches the original fixture counterexample. Two independent failures remain.

**R5, 2 ms:** hold the loader for `[:program #{shared.ns}]`, then request
`[:program #{shared.ns another.ns}]` against the same readiness owner:

```clojure
{:both-loaders-entered? true :a :ready-a :b :ready-b}
```

The delay key is the entire namespace set (`agent.clj:169–180`), so overlapping requests can concurrently arm
the same Vars. This proves the ownership race without mutating production instrumentation.
Neither `ready!` nor `host-global` is called by `request!`/`start-member`; their standalone proofs do not
establish a pre-body execution gate.

**R11, 7 ms:** kondo analyzes, but never executes,

```clojure
(deftest reloads (require 'clojure.set :reload))
```

The test is found and its `require` call is present; `host-global` returns `{}`.
The mutator set (`agent.clj:343`) misses this JVM-global reload. The 1,973 remainder is not therefore a proven
concurrent-safe set. Loaded-program identity comparison detects some overlap; it cannot undo another body's
execution under changed Vars. Keeping a global snapshot/restore path (S17) is not safe concurrent isolation either.

**Smallest fix.** Own first load/arming by the actual overlapping namespace/declaration identities, and require
positive readiness before dispatch. Include reload syntax and known mutation helpers in classification and route
those tests to the platform JVM before execution. Do not restore global roots over another cluster's work.

Other shared subjects from P11/P13/P15 remain relevant: the central cache serializes same-key derivations;
SCI forks isolate definitions but can share mutable values inherited from the ancestor; process-wide cache,
profiling and instrumentation mutations are global; dynamic bindings need explicit conveyance. None is made
cluster-local by renaming a branch.

## 5. B5: another pressure reader can consume the resume transition without waking waiters

The revision's collection-notice proof passes when its listener clears pressure. **R4, 2 ms**, calls the real
`kernel/memory-pressure` on the supplied owned state first, then delivers the collection notification:

```clojure
{:pressure-cleared? true :reader-result nil :wake nil}
```

`resume-listener` (`agent.clj:153`) requires pressure still to be present before it checks for a clear.
Another admission reader can clear the shared volatile first. A waiting run with no active member then receives
neither this wake nor a member-exit wake, and may reach its deadline despite available memory.

**Smallest fix.** Have the kernel's transition owner deliver the resume notice, or make qualifying collection
notices wake pending runs when pressure is already clear. Notification must not depend on this listener being the
first reader to clear the shared state. Keep the admission predicate and resume event in the same owner.

The design now correctly calls pressure reactive. R18 held four complete member worlds/graphs across an ordinary
young collection: used heap was 3,158,902,928 bytes versus 3,144,760,776 before acquisition; an empty control had
already shifted by 22,166,808 bytes, and release left 3,156,430,472. Last-collected old generation was unchanged.
This shared-heap observation cannot identify retained bytes per member. R13 found neither JOL nor MemoryMeter
installed. The rerun's roughly 28.3 MB/thread allocation per fork is not retained size or a heap guarantee.
Combined with finding 3's unbounded descendants, no finite live-world bound has been proved.

## 6. B6: the real recording refusal is returned as a successful settlement

Supplying `:seon.batch/settle` closes the original architectural question: recording **is** a stage value
(V2-8). The throwing-stage proof passes. The actual writer returns declared errors as values too.

**R8, 2,688 ms:** on a disposable branch, the real selection/admission writer admits one existing test identity;
`record-interrupted!` records an honest unstarted red member: zero passes, zero failures, one error,
`began? false`, `ended? false`, and completed/terminated transaction facts. No test body was run and no green
was fabricated. This proves the actual member writer is available, not the prototype's green/reach integration.

**R14, 3,636 ms:** pass deliberately mismatched branch provenance to that writer as the prototype settle stage.
The writer correctly refuses the transition; the common batch/prototype completion reports:

```clojure
{:admitted? true :value [42] :diagnostics []
 :commit-error {:seon.test/execution-refusal :seon.test.run/immutable}
 :unfinished [] :recorded-completion? false}
```

The batch's settle guard catches throws but accepts the error map as `:seon.batch/commit`
(pinned `batch.clj:296–305`). The run answers complete with no recorded completion and no settle diagnostic.
A synthetic marker transaction and an exception-only negative case did not exercise this interface.

**Smallest fix.** Define and consume the settle result's success/error alternatives at the common stage boundary.
Keep body values, attach and deliver the recording fault, and refuse recorded success when the writer refuses.
Supply actual member counts and reach digest from the member's observed execution to the requester's writer.
Prove both positive member evidence and a real refusal before removing runner-side recording.

Rev 3's test-agent landing is **S12**; **S13** now ports proofs as tests. The body thunk belongs in the shared
`seon.turn.batch/test-request` entrant. The prototype currently repeats its synthetic reader/evaluator in
`member-request` rather than calling that constructor. Converge there with the world, observer, settle and reply
stages; otherwise improving this prototype does not improve the MCP/`my.test` entrance.

## Coverage limits and landing evidence

These measurements delimit the execution findings; they are not additional blockers:

| Requested gap | REPL result |
|---|---|
| Option B versus serial | R12: eight identical `reduce` bodies, same request/recording, limits 1/4/4/1: **350.36/410.84/400.94/359.27 ms**, all eight correct each time. Parallel throughput was **0.874× serial** here. Includes branch/fork/graph lifecycle; uses quiet recording. This is a small-body counterexample to an unconditional speedup, not a real-suite throughput estimate. |
| Two clusters | R15, 132 ms: distinct requester branches, cluster names **["default" "default"]**, identical store UUID **224560ae-b0ba-3d5c-b6db-6d7415ad66e7**. The rerun proves concurrent branch isolation within one store, not two independently booted cluster configurations. |
| MCP / my.test delivery | R21: real MCP SCI on a disposable hosted branch evaluates `(my.test/run {:seon.ns/name 'review.test-agents-v2.empty})`, returning `[]` in 30 ms evaluated time (tool 1.6 s). R23's empty prototype returns its internal results/unfinished/pending map in 117 ms. Empty selection invokes no harness run; it cannot prove member counts, reach, refusal or reply-projection parity. The prototype is not wired into the installed entrance. |
| Retained heap | R18 measures live heap with four held worlds and controls; retained attribution remains unknown, as explained in finding 5. |
| Real member writer | R8 records genuine unstarted red evidence; R14 demonstrates lost refusal delivery. Positive executed-member/reach evidence belongs to root's central test round. |

The full rerun costs 8.3 s because it includes a deliberate 3 s pending deadline, 2.18 s population-wide
kondo analysis (2,378 tests), and bounded negative cases. R8/R14 exceed one second in the existing real
selection/admission/recording path; their 2.688/3.636 s are end-to-end probes, not isolated batch costs.
No additional production profiler mutation was used to assign exclusive function costs.

Foreign boundary: the in-flight one-path sources and test/member writer/schema edits were neither changed nor
loaded. The main probes ran on PID **84717**, archive **e999fdd1bf4876e0bceced3de14817eda8a8d61f**.
An external replacement at 22:45:47Z changed default to PID **96991**, archive
**31afcffa921b0ff858bfbb4e04b0579dac458b4c**; the first delivery probe saw a missing advertisement and the next an
absent disposable branch. After readiness, the branch was recreated, the empty-route probe completed and the
branch was retired. No stop/reset/restart was issued here. Default reported 13 existing open fault signatures.

Failed exploratory forms are retained, not counted as proof: R10 used kondo's unsupported stdin argument and
timed out; R11 supplies `with-in-str`. R17 had a reader delimiter error, corrected in R18. R19/R20 were interrupted
by the external replacement. R22's missing-agent contract refusal is evidence for finding 2, not delivery parity.

Static validation used a `git archive` of HEAD **b57f01ba4a0ba74b345dcb91074d6ed4451365e4** plus only this
document's diff, refreshed after foreign code commits advanced HEAD. `clj-kondo --cache false --lint src script test`:
**0 errors, 948 warnings**, 6,243 ms, exit 2 for warnings;
[lint output](../../../tmp/test-agents-review/v2/lint-landing.txt).
R30 ran `seon.contracts-compile-test/check` over **486 archived files** against their packaged projection:
**1,984.34 ms**, ten pre-existing compile errors, zero source/test findings. The additional foreign boundary is
`script/seon/dev/docstring.clj:285,318,334,360` and
`script/seon/dev/markdown.clj:1020,1050,1090,1124,1170,1215`. This is not a green whole-tree contracts check.
These are static source checks, not test requests; no archive JVM or worktree was created.

R27 confirms zero running/waiting prototype members, the delivery branch absent and all three owned scratch
namespaces removed. R26's unqualified `ProcessHandle` failed compilation before cleanup; R27 corrects it.
All owned body latches were released and the observed graphs joined. Disposable writer branches released in
`finally`; the archive is only retained static evidence, not an operator root. Source/test net change: **0**.

No runtime tests are required for this documentation-only commit. Root's focused implementation list is:

- `seon.test.one-request-test/a-member-body-runs-on-its-own-listed-branch`
- `seon.test.one-request-test/a-body-outliving-its-bound-is-released-only-after-it-exits`
- `seon.test.one-request-test/a-member-that-never-exits-holds-only-its-own-test-and-the-next-request-runs`
- `seon.test.clean-world-test/a-member-world-is-the-loaded-program-with-boot-facts-only`
- `seon.test.effect-attempt-test/an-agent-capability-stub-settles-a-receipt-only-in-an-effect-reaching-attempt`
- `seon.test.first-load-arming-test/a-namespace-first-loaded-by-a-request-has-its-contracts-armed`
- `seon.flow-test/stop-joins-the-graph-only-after-its-procs-exit`
- `seon.custody-stability-test/cross-cluster-write-isolation`

Add the exact R3/R4/R5/R7/R9/R11/R14/R16 behavior regressions to that central round, plus a positive real
member/reach writer and nonempty MCP/`my.test` delivery comparison. Those new regression tests are not installed.
Release only this review document and `tmp/test-agents-review/`; no source/test net change or runtime publication.
