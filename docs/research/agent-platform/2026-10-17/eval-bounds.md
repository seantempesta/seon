---
title: "Eval bounds: a declared memory bound per eval request"
type: research
status: "design with priced options, lane eval-bounds 2026-10-17; nothing implemented"
---

# Eval bounds: a declared memory bound per eval request

Lane eval-bounds (Opus), 2026-10-17, after default's 17:23 OOM (commit 825d7638b). Phase 1 (await actual exit in
`seon.test/run`) is fixed and proven as a patch, blocked from commit by the guard census (next section). Phase 2 is a
design with priced options; nothing of it is implemented.

## Phase 1 status: fixed and proven, not committed (blocked by the guard census)

**Cause.** `seon.test/run` opens the request view (`request-view!`) when an eval request's facts name the
execution's connection, as in an edit hook's `my.test/check`. The view is an overlay of the branch, and it is
used as the recording connection. The request's world (checkpoint, request branch, member branches) is built in
overlays over it. e71917c85 made `run-request` release its world only at its live members' actual exit. But
`run`'s own `finally` released the view at the answer, under any member still live past the bound.

**Fix** (`eval-bounds-phase1.patch`, against 2df7abac2):
- `run-request` takes the view, and one function, `release-at-exit!`, releases the world and then the view. It
  runs at once when no member is live, else on the last member's release; a failure reaches the core fault through
  the stage's `whenComplete`, so there is no catch. A finally around the whole request covers early returns.
- `run` no longer releases the view.
- New proofs: `seon.test.one-request-test/a-member-outliving-its-bound-keeps-the-request-view-until-it-exits`
  and its gated member `a-gated-member-writes-on-its-branch`.

**Proof.** The proof is red on HEAD in a check JVM at the right assertion: the view is gone at the answer. It is
green with the patch: run 641f16a34a42, 6 executed, 18 pass, together with the world, bounded-result and
request-view tests. The whole `seon.test.one-request-test` namespace with include-long has 8 red with the patch
(run f7801d13efca) and the same 8 red members on HEAD (run fb8c75ef0715), so none of them is this change's.

**Limit.** In this fixture the member's own write after the view's release still committed on HEAD. The incident's
Datahike NPE came from a nested run inside a member body, and it is not reproduced. What is proven is the
ordering: nothing the request acquired is released under a live body.

**Blocker.** `bin/lane commit` refused the patch: `seon.guard-census-test/a-changed-function-holds-no-guard`
judges every changed function. Touching `run-request` exposes its pre-existing batch catch. That catch is
`(catch Throwable failure ...)` around each batch: it calls `seon.test.runner/record-interrupted!` (the run stops
with `:request-threw`, keeping its captured results), then rethrows. Every other route to the view also touches a
function with a legacy general catch (`seon.test/clean-request-handle`, `seon.cluster.agent/release-context!`).

**Options for root:**
1. **Move the batch's interrupted recording to the entrant (recommended).** About 30 lines in `seon.test` only.
   - `run` owns the request's custody: the held world, the member exits and the in-flight batch (its provenance and
     observed results).
   - `run`'s entrant catch calls `record-interrupted!` from that custody before `record-thrown!`. Its finally calls
     `release-at-exit!`, so recording precedes release on the view's connection.
   - Proof: the patch's tests, plus a new row in `seon.test.interrupted-request-test` that throws mid-batch through
     `run` and reads `:request-threw` on the run.
2. **Delete the batch recording.** A throw then leaves the run admitted with unfinished members, which admission
   already reads as interrupted work. `record-interrupted!` and `:request-threw` lose their only production caller,
   and `seon.context`'s display of them goes with it. This is a cross-file cut, with the context lane's consent.
3. **Commit the patch as is with root's census override.** This is not offered by the law ("no exemptions"),
   so it is listed only to be refused.

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
