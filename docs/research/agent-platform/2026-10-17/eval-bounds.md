---
title: "Eval bounds: a declared memory bound per eval request"
type: research
status: "phase 1 committed (lane eval-bounds-1b, 2026-10-17); phase 2 option 1 committed (lane eval-bounds-2, 2026-10-17); options 2 and 3 not implemented"
---

# Eval bounds: a declared memory bound per eval request

Lane eval-bounds (Opus), 2026-10-17, after default's 17:23 OOM (commit 825d7638b). Phase 1 (await actual exit in
`seon.test/run`) is committed with root's option 1 (next section). Phase 2's option 1, the allocation bound on the
arm, is committed (lane eval-bounds-2, after the 18:14 OOM); options 2 and 3 are not implemented.

## Phase 1 status: committed (option 1, lane eval-bounds-1b)

**Cause.** `seon.test/run` opens the request view (`request-view!`) when an eval request's facts name the
execution's connection, as in an edit hook's `my.test/check`. The view is an overlay of the branch, and it is
used as the recording connection. The request's world (checkpoint, request branch, member branches) is built in
overlays over it. e71917c85 made `run-request` release its world only at its live members' actual exit. But
`run`'s own `finally` released the view at the answer, under any member still live past the bound.

**Fix.** `run`, the entrant, holds the request's custody (one volatile map `run-request` fills): the world, each
started member's exit, the view, and while a batch is in flight (until its results and stop are written) the
recording of its interruption.
- `run`'s catch records an in-flight batch as stopped (`seon.test.runner/record-interrupted!`, `:request-threw`)
  before it answers the throw; `run-request` holds no catch.
- `run` then calls `seon.test/release-at-exit!`, which releases the world and then the view: at once when no
  member is live, else on the last member's release, its failure reaching the core fault through the stage's
  `whenComplete`.
- The batch's stop write is inside the in-flight scope: before, a throw from it left the run with no stop cause.

**Proof.** `seon.test.one-request-test/a-member-outliving-its-bound-keeps-the-request-view-until-it-exits` (red on
2df7abac2 at "the request view outlives the answer"), and
`seon.test.interrupted-request-test/a-request-that-throws-mid-batch-still-stops-its-run` (red on 0468b27f5 at "its
run is stopped by the throw", run a9873ab48ee5). Both green in check run d920de39e1da (11 executed, 45 pass, 0
fail), with the world, bounded-result and request-view tests and `seon.guard-census-test/a-changed-function-holds-no-guard`.

**Limit.** In this fixture the member's own write after the view's release still committed on HEAD. The incident's
Datahike NPE came from a nested run inside a member body, and it is not reproduced. What is proven is the
ordering: nothing the request acquired is released under a live body.

## Phase 2 status: option 1 committed (lane eval-bounds-2)

**Why.** At 18:14 one SCI eval request generated example inputs from all 3,591 `seon.*` contracts, after earlier
requests that each churned 12–24 GB, and the shared JVM died of OOM.

**Mechanism.**
- The dial `:seon.config.eval/alloc-limit-bytes` (per agent) is shipped at 2 GB (2,147,483,648 bytes). That is half
  the 4 GB heap cap, so an evaluation that keeps every byte it allocates still leaves the JVM room. It is also five
  times the `:large-allocation` finding (400 MB), which is ten times an ordinary evaluation's 40–120 MB.
- It rides `:seon.sci.admit/caps`. `seon.config/result-caps` derives it with the other caps, so every eval request
  already carries it, and turn, MCP and test paths need no new member. `seon.sci.eval/evaluate` arms with it through
  `seon.sci.kernel/with-arm`'s new 4-arity. Other entrances (renders, named invocations, test bodies) arm without it.
- The interrupt function's existing 1,024-entrance sample sums three counts: the owner's sample, the finished
  adopted extents, and the calling adopter's extent in flight. `adopt-arm` notes that extent's start on the
  adopter's copy of the arm. Before this change an adopter's allocation counted only at its extent's end.
- Past the bound it records outcome `:alloc` and closes the arm's one latch (`::reached`), so every thread serving the
  arm is cut at its next entrance. It then calls `interrupt!`.
- `seon.sci.kernel/failure-value` answers `:seon.sci.kernel/alloc-limit-error` with the bound, the measured
  `:seon.eval/allocated-bytes`, a guard observation and the form (`:seon.error/offending`). The kernel is the producer,
  so the variant is named there rather than `seon.sci.eval/...`.
- `:seon.eval/outcome` gains `:alloc`. `:seon.cluster.eval/interrupted-at` is present for both bounds.
- **Terminal event.** The bound's missing terminal event is the evaluation's return to interpreted code past the
  bound. The arm is released by `with-arm`'s `finally` only when the interrupted body actually unwinds, so resources
  are held until the actual exit, exactly as for `:time`.

**Proof.** `seon.sci.eval-test/an-evaluation-past-its-allocation-bound-is-cut-and-the-next-one-runs` (64 MB bound;
the next evaluation in the same context answers 3) and
`seon.sci.eval-test/generating-from-many-schemas-in-one-request-stops-at-the-allocation-bound` (the 18:14 shape at a
small scale: `mg/generate` over 100,000 schemas in one request under a 32 MB bound). Both were red on 056b43883 with
outcome `:time` and no variant. Both are green on the change.

**Limits that remain.**
- A host call that allocates without re-entering interpreted code (a `pr` of a huge value, as at 16:38 and 17:23)
  finishes or OOMs first.
- The sample runs every 1,024 entrances. Host-heavy loops overshoot by that many iterations' allocation: about 200 MB
  for the generate shape.
- Test bodies (`seon.sci.eval/run-tests`) and renders arm without the bound.

## What exists

- **One arm per evaluation.** `seon.sci.kernel/arm` installs an arm value per evaluation; SCI calls the guard's
  interrupt function at every interpreted function-body and loop entrance. Its time limit is a latch
  (`seon.sci.kernel/deadline-arm`) that the interrupt function turns into `sci.interrupt/interrupt!` with outcome
  `:time`. Nested work inherits the arm, and work crossing threads carries it (`seon.sci.kernel/current-arm`, `adopt-arm`).
- **Allocation is already sampled.** The same interrupt function samples the owning thread's
  `getCurrentThreadAllocatedBytes` every 1,024 entrances into the arm's `::sampled`. `adopt-arm` adds the adopting
  thread's allocation into `::adopted-allocated-bytes`. `seon.sci.kernel/record` reports the total as
  `:seon.eval/allocated-bytes`, and every reply shows it as `:seon.repl/alloc-mb`. A new arm on a virtual thread runs on a
  platform worker (`on-platform-worker`) so that the count exists.
- **A JVM-wide admission bound exists.** `seon.sci.kernel/watch-memory!` and `enter-memory-pressure!` close new arms
  when tenured live bytes cross 70% of max, and admit again below 55%. `seon.test/run-request` asks the same question
  before building a world.

## What the proposal would and would not have caught

The proposal: a per-request allocation bound read at stage boundaries and by the interrupt poll, enforced by
`interrupt!`, returning an error that names the stage, its bound and the measured allocation.

**It would not have stopped today's OOM.** The 1.34 GB string came from a `seon-test-body` platform thread, outside
the edit hook's arm. The member body's arm comes from `seon.sci.eval/run-test` on that thread, or is absent for a
host body. The allocation happened in host code (`CompletableFuture.get` → `ExceptionInfo.toString` → `pr` of
ex-data) after the outer evaluation had already been interrupted. A host `pr` makes no interpreted entrance, so no
poll runs during it. That cause is fixed at its producers instead: the Datahike fork never prints a delivered
failure (553c9d5d5), and phase 1 keeps resources until actual exit.

**It would catch** interpreted work that allocates cumulatively on the evaluation's own threads, for example the
1.17 GB `defn seon.db/as-of-key` (task `seon.sci.eval/one-defn-on-a-branch-allocates-a-gigabyte`), whose cost is
in interpreted callers. Allocated bytes are a rate, not retention: a 1 GB allocation that keeps nothing live is
harmless to the heap, and the arm's counter cannot tell the two apart. As a heap guard, the bound is a proxy.
As a cost bound it is honest: "this evaluation did 10× the work it should" is exactly what CLAUDE.md asks to
surface ("10× over what the form should cost").

## What SCI's interrupt reaches

- Interpreted function bodies and loops on any thread that holds the arm, owner or adopter: these are cut.
- Host calls (`pr`, a Datahike transaction, `CompletableFuture.get`, a regex over a huge string): these are not cut
  until they return to interpreted code. A single host call that allocates gigabytes, such as printing a huge
  value, finishes or OOMs first. The per-thread sample also misses an adopting thread's allocation until its
  `adopt-arm` extent ends.
- Threads the evaluation starts outside the arm (a `seon-test-body` thread, the Flow mixed pool without
  `current-arm`, Datahike's writer thread): these are not counted and not cut. Test bodies get their own arm from
  `run-test`.

## Options (simplest first)

1. **Allocation bound on the existing arm (recommended).** About 60 lines, one lane, platform-class (schema and
   config resources plus the kernel).
   - A dial `:seon.config.eval/alloc-limit-bytes`, shipped at 2 GB (half the 4 GB heap cap). It flows wherever
     `:seon.config.eval/time-limit-ms` flows into `kernel/with-arm`.
   - On the arm: `::alloc-limit`. In the interrupt function's existing 1,024-entrance sample: when
     `sampled + adopted >= limit`, set outcome `:alloc` and call `interrupt!`.
   - `:seon.eval/outcome` gains `:alloc` (`resources/seon/schemas/seon.eval.edn`), and
     `seon.sci.eval`'s failure path maps it the way it maps `:time`, with a new variant
     `:seon.sci.eval/alloc-limit-error` carrying `:seon.eval/allocated-bytes` and the bound.
   - Red proof: a `seon.sci.eval-test` row evaluating `(loop [i 0 acc nil] (if (< i 1e9) (recur (inc i) (object-array 1024)) i))`
     under a 64 MB limit returns the variant with `allocated-bytes >= 64 MB`, and the next evaluation in the same
     context succeeds.
   - Cost: a compare per 1,024 entrances, on a counter already read there.
   - Sacrifice: blind to host-only allocation, as above.
   - Stage naming: the arm knows only the evaluation. The eval request's other stages (record, render, print)
     run outside the arm on the same thread, so naming the stage takes option 2.
2. **Plus a per-stage reading in the one-path fold.** About 40 more lines in the fold owner (`seon.turn.loop` /
   `seon.repl`, held by the eval-trace lane, so as a patch):
   - Read `getCurrentThreadAllocatedBytes` at each stage boundary (read, eval, record, render, print).
   - Attach `:seon.repl/stage-alloc` to the reply as data.
   - Past the bound after a stage, return that stage's error beside the value.
   - This cannot cut a stage mid-flight (the printer and recorder are host code). It names the stage that went
     over, which is the diagnostic the brief asks for.
3. **A heap-retention guard.** Have `watch-memory!`'s crossing interrupt the arms in flight with the largest
   allocation instead of only refusing new ones.
   - This is the only option that answers "a request that allocates gigabytes takes down the JVM", because it acts
     on live bytes.
   - It is a cross-arm effect: a registry of in-flight arms (`::in-flight` already exists in the pressure value)
     and a policy for which arm to cut. That makes it a design round, not a lane. It still cannot cut host code.

**Recommendation:** option 1 now, as a platform slice with the red proof above. Option 2 as a patch to the fold
owner once eval-trace releases it. Option 3 only if a measured OOM survives the producer fixes and option 1.
Today's did not need it.

## Open questions for root

- The dial default: 2 GB against a measured ordinary cost. The sentinel's eval requests are 40–120 MB each (this
  lane's replies). The as-of-key defn is 1.17 GB, which is the defect to fix, not a size to admit.
- Whether `:alloc` should be its own outcome or `:time`'s sibling under one `:bound` outcome carrying the kind.
