---
type: landing
lane: fix-adoption-excludes-tests
created: 2026-09-24
status: adoption half installed on default (pid 70372); the member half is a ready patch to src/seon/test.clj, which this lane does not own; save-gate classification fix installed
---

# Lane fix-adoption-excludes-tests (2026-09-24)

What exists: `development-source-refresh!` (cluster.clj) runs rows → unmap →
`require :reload` → `instrument/apply!` → record. Test bodies run on a virtual
thread in `seon.test/bounded-result` (test.clj). No in-process owner knew which
bodies were running. The database reservation (`admit-run`) is per cluster, per
request, and cannot be waited on. The drift is JVM-wide. So the exclusion is one
JVM-wide fair `ReentrantReadWriteLock` (`seon.cluster/program-change`). A member
body holds the read side. Adoption holds the write side from unmap through
arming. No registry, no retry, no interrupt.

## Broken first

- **The member half is not installed.** Members only exclude adoption when
  `bounded-result` wraps its body in `seon.cluster/call-excluding-adoption`, and
  `src/seon/test.clj` is outside this lane. The patch is ready at
  `tmp/fix-adoption-excludes-tests/test.patch` (+11 / −3). It adds two
  load-cycle `delay`s and wraps the body. Until root applies it, a production
  adoption takes the uncontended write lock and excludes nothing. The drift
  reported in the fix-failure-message-bound landing can therefore still happen
  on default.
- **The wait bound is coarse.** On contention, adoption waits up to the longest
  `:seon.test/long-ms` declared in the adopting database. Today that is
  1,200,000 ms. Each member's own bound is not known without a registry. The
  actual wait is only until the running member exits. A body that outlives its
  request bound keeps its hold until it actually exits, so a publication behind
  a wedged member refuses only after 20 minutes.
- **Mid-request adoption.** Adoption can land *between* two members of one
  request. The later members then run the adopted program under the request's
  admitted program digest. No member sees a change during its body, but the
  run's evidence can name the older program. This needs an owner decision:
  hold for the whole request (up to its 120 s bound), or re-admit after an
  adoption.
- **Evaluations are not covered.** The law says "no reload may run under an
  evaluation". This lane covers test members only. SCI and MCP evaluations still
  do not exclude adoption.

## Changes

1. `src/seon/cluster.clj`:
   - `program-change` is a `defonce`, so it survives the reload of `seon.cluster`.
   - `program-change-lock?` is a registered predicate.
   - `*member-lock*` is conveyed by `bound-fn`, so adoption and nested members
     inside a member's own body run inside that member's exclusion and do not
     wait on themselves.
   - `call-excluding-adoption` is the member entrance.
   - `call-with-program-change` is the adoption side:
     - It first tries the lock at once.
     - On contention it waits up to the declared bound, then refuses by name
       with `:seon.cluster.source/phase :adoption` and
       `:seon.test/time-limit-ms`.
   - `development-source-refresh!` wraps unmap → reload → verify → arm. The
     drift check is unchanged.
2. `host-bound-declarations` also answers changed `:core` tests (the coordinator's
   follow-up, `docs/seon/issues/save-gate-runs-the-previous-compiled-test-body.md`):
   - Source proof: `seon.test/resolve-test` (test.clj:1305-1307) resolves a
     `:core` test with `requiring-resolve`, which returns the compiled Var. A
     candidate branch therefore ran the pre-edit body.
   - Such changes now take `adopt-then-test!`: reload first, then test.
   - Contracts on `host-bound-declarations`, `adopt-then-test!` and `save-gate!`
     now name `[:or :seon.fn/sym :seon.test/sym]`.
3. Tests:
   - New: `test/seon/cluster/adoption_exclusion_test.clj`.
   - Added: a `:core`-test case in
     `seon.cluster.save-gate-test/a-host-bound-change-adopts-first-then-reports-its-reaching-tests`.

Net, ignoring whitespace: cluster.clj +83 / −9 (most of the raw diff is
re-indentation), tests +65.

## Evidence (default pid 70372, checkout, hook publication on)

**Lock semantics probe** (scratch namespace, own lock). The member held the lock
0–305 ms. The adoption acquired it at 305 ms. The late member entered at 412 ms,
after the adoption.

**Regression**
`seon.cluster.adoption-exclusion-test/a-member-running-across-an-adoption-sees-no-change-and-the-adoption-completes-after-it`
uses real functions with an injected lock:
- The member sees `:before` at entry and at exit.
- The adoption queues and completes after the member.
- A later member queues behind the adoption and sees `:after`.
- A member's own adoption does not wait on itself.

Results: 11 pass on the save gate (run aa9bdcd6855a). Then 20 pass together
with the save-gate test (run 26d2094bfb69, 5,500 ms).

**Save-gate fix, live**
- `host-bound-declarations` on default answers
  `[seon.cluster/refresh-source! seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault]`.
  That test's admission is `:core`.
- The new-test save took the host-bound path and ran the *new* body green on
  the first save (gate aa9bdcd6855a, `adopt-ms` 512).
- Before this change, a changed test's first save ran it on the candidate.
- The added assertion fails on the parent, which does not list the test.

**Refusal path** (scratch lock, one reader held, database with no long-ms). The
adoption was refused after 5,005 ms: "Development adoption waited 5000 ms, the
longest declared member bound, and 1 running test member(s) had not exited."
The holder was not interrupted and exited after release.

**Added wait on an ordinary publication** (no member running): the production
lock's uncontended `tryLock` has a median of 708 ns over 200 samples, with a
first-call maximum of 1.9 ms. The bound query runs only on contention.
Adoption `adopt-ms` in the hook runs stayed at 390–582 ms.

**Hook reaching runs after each cluster.clj save**: 19–36 executed, with the same
foreign reds each time. Those reds are fixture rows missing
`:seon.program/definition-digest`, `my.message-test` routing, and bootstrap
budget. None involves the exclusion.

## Verification limits

- The member half is unproven in production until the test.clj patch is applied.
  After that, one `bin/test-check default --ns seon.test.runner-test` while a
  save publishes should show no `:drift-added`.
- The nested-member skip is not separately asserted.
- A probe in MCP session `default` is blocked in a 20-minute `tryLock` on a
  scratch lock only. It self-releases at about 17:48Z and holds no production
  state.
- Platform members that adopt their own roots were not run (`bin/test` is barred).

RESET NEEDED: none.
