---
title: "Card COST-REG: A reaching test that got more expensive is red"
type: reference
status: active
created: 2026-10-09
---

# COST-REG A reaching test that got more expensive is red

Owner ruling (2026-10-09): automatic regressions create failing tests when a change made the work slower than it was;
the baseline is captured automatically at assignment, for every task. Trigger: fan-out 9 (77367741d), a deepseek
agent's `my.program/nearest` passed every reaching test on its branch at 6.3 s / 15.4 GB per call; root's review cut
it to 177 ms / 728 MB. Nothing red caught it. Lane: Opus (test runner and evidence schema are platform code).

## What exists

- `seon.test/run` runs each member on its own platform thread (`seon-test-body`, so HotSpot counts its allocation),
  measures `:seon.test.timing/run-ms` and builds `seon.profile/explain-slow` over one second, then **discards the
  cost**: the stored member (`:seon.test.member/member`) keeps counts, failures and `:seon.test.member/reach-digest`,
  no cost. Recorded green whose reach digest still holds is reused (`seon.test/verified?`, unchanged reuse).
- `seon.profile/measure` reads thread allocation, wall ms and armed calls, but its calls are the JVM-wide
  `seon.profile` cells (concurrent work counts). The per-world call sink (`seon.batch/sink`, `seon.batch/calls`,
  `seon.profile/sink-counts`) is scoped to one evaluation; a test member has none of its own today.
- `seon.health/regressions` = `seon.health/boot-comparisons` (process records) + `seon.health/definition-comparisons`
  (newest p50 bucket per `[sym digest]` from `:seon.profile.measurement/*`, written per agent branch by
  `seon.profile.measurement/significant-tx`; its only reader). A finding, never red; inputs are whatever traffic
  happened to call the function, so a digest's p50 moves with its callers, not its code.
- `seon.task/assign!` reads proof status from recorded evidence (`seon.test/verified?`); it runs nothing.

## Measurements (default, 2026-10-09, JVM mode as root; probe forms in the report of lane cost-regression-card)

Test body run directly under `seon.profile/measure` on the calling thread, one warm-up then 7 runs:

| test | ms min / med / max | alloc MB (thread) | armed calls |
|---|---|---|---|
| `my.program-read-test/source-of-a-misspelled-name-names-it-first-in-a-fixed-message` | 2.0 / 2.3 / 9.6 | 0.9 ×7 | 402 ×7 |
| `seon.ref-test/a-missing-heading-reply-names-the-nearest-heading` | 1.8 / 2.0 / 17.5 | 0.9 ×7 | 95 ×7 |
| `my.program-test/source-shows-the-docstring-once-inside-the-source` | 5.4 / 6.1 / 29.2 | 14.4 ×7 | 62 ×7 |

- Allocation and armed calls are exactly repeatable warm (max/min 1.00); wall ms spreads 4.7–9.8× at single-digit ms.
- Cold vs warm: the first body run of the misspelled-name test allocated 14.3 MB and took 19 ms (warm 0.9 MB,
  2 ms). Allocation is deterministic only given cache state; an absolute floor must absorb cold caches.
- Through the runner (`seon.test/run`, `:seon.test/rerun? true`, 5 runs of the misspelled-name test): run-ms 46–95,
  whole request 260–467 ms, JVM-wide armed calls 17,523–17,938 and JVM-wide allocation 303–486 MB (acquisition,
  release and concurrent work included). Request-level and JVM-wide numbers are not the test's cost.
- `my.program/nearest` reproduced on the JVM over the same 8,433 declared symbols: root's form (each distance once)
  177–346 ms, 713 MB; over a 1,000-symbol slice the agent's form (`sort-by` with the distance in the key, so two
  `malli.error/levenshtein` per comparison) 336–365 ms / 832 MB against 16–22 ms / 49 MB, 17× allocation, which
  scales to the reported 15.4 GB at 8,433. **Both forms make 0 armed calls**: `malli.error/levenshtein` is a
  dependency, unarmed. The baseline on default (the pre-fix filter) is 0.9 MB / 402 calls / 2 ms per body.

## Change

Slice 1 (item 1) landed and stays. Slices 2-3 (items 2-6: baseline, assignment capture, regression red, acceptance, health fold) are superseded by FEEDBACK-LOOP, which compares a reply's live cost with main's stored cost entities instead; their dials are deleted.

Data flow: member body thread → its cost (three numbers + bounded top functions) on the stored member → the
branch member's cost compared with the target's member for the same test → a red result carrying both.

1. **Measure the member, store it on the member.** `seon.test` binds a fresh `seon.batch/sink` into the member's
   world for the body (calls conveyed to the futures it starts) and reads `getCurrentThreadAllocatedBytes` at the
   start and end of the body task on the body thread. The member gains `:seon.test.member/calls`,
   `:seon.test.member/alloc-bytes`, `:seon.test.member/run-ms` and `:seon.test.member/top` (at most
   `:seon.config.test.cost/top` rows `[sym calls self-ms]` from the sink, ranked by calls then self). One store: test
   evidence. No copy in `:seon.profile.measurement`. Cost of the measurement: two counter reads and one sink per member;
   the numbers are already produced by the body thread.
2. **Baseline = the target's evidence for this test at the merge base.** Key: test symbol + the reach digest the
   test has on the target at the agent branch's merge base (the unchanged closure). The baseline is the newest green
   target member with that reach digest. Nothing is copied: the comparison reads two members.
3. **Captured at assignment (owner ruling), reused first, lazy for the rest.** `seon.task/assign!` (after its
   proof check) computes the predicted set: the task's proofs plus the tests gating the symbols in
   `:seon.task/must-read` and its intent's typed symbols (`seon.fn/gating-tests`). Each test with a current green
   target member carrying cost is reused (zero runs). The rest, up to `:seon.config.test.cost/assign-runs` (default
   32: proofs first, then direct callers' tests), run once on the target's head through the ordinary `seon.test/run`
   on the target connection, recording ordinary target evidence. Every other reaching test (beyond the cap, or
   reaching a function changed outside the prediction) is baselined lazily: the first time it runs on the branch
   because of a change, the test step also runs it once on the merge-base world (target evidence, never the agent
   branch) unless a current target member exists. Proofs that are red on the target have no baseline (red is never
   a baseline); their first green is compared with nothing.
   - Runs per baseline: **one**. Calls and allocation repeat exactly warm; the floors absorb cold-cache allocation;
     wall ms is never red on a small number (below).
   - Cost of baselining at assignment: gating sets are large for platform symbols (measured
     `seon.fn/gating-tests`: `my.program/source` 326 tests in 269 ms; `seon.task/assign!` 1,854; `seon.db/q` 2,338),
     and one isolated member costs ~250–470 ms of request overhead, so running them all would take minutes. With reuse
     plus the 32-run cap, assignment costs the walk (≤ 0.4 s) plus at most 32 members, about 10 s worst case, usually
     far less where main's evidence is current. The lazy path adds at most one target run per test the branch actually
     reaches, the same order as the branch test step itself.
4. **Compare, and fail as an ordinary red.** After a member runs green on a branch with a baseline, it is red with
   `:seon.test/cost-regression` (a declared variant on `:seon.test/result`, `fail-count` counted) when any signal
   exceeds both its factor and its floor:

   | signal | factor | absolute floor (increase) | why |
   |---|---|---|---|
   | `alloc-bytes` (body thread) | 2× | 64 MB | deterministic warm; catches unarmed library work (nearest: 0 armed calls) |
   | armed `calls` (member sink) | 2× | 1,000 calls | deterministic; names repeated work in our code |
   | `run-ms` | 3× | 1,000 ms | noisy 5–10× at single-digit ms; only large wall regressions |

   The red carries data, never text: `:seon.test/sym`, the signal(s), baseline and observed values, the ratio, the
   baseline's run id and reach digest, and `:seon.test.cost/grown`: the member's `:top` rows beside the baseline's
   for the same symbols, ranked by call growth, so the agent sees which functions did more work. The comparison reads
   two stored members: ~zero cost.
5. **Acceptance of a legitimately slower change.** Only the assigner (root) accepts: it writes
   `:seon.task/cost-accepted` on the task, a set of `{test, signal, observed, reason}`; a member whose observed value
   is within the factor of the accepted value is green. The agent cannot accept its own regression. At merge the
   target's new green members become the baseline automatically (rebase on merge); nothing else is reset.
6. **Fold the duplicate.** `seon.health/definition-comparisons` and `:seon.health/definition-regression` are deleted
   with their tests: COST-REG compares controlled inputs (the same test) where health compared uncontrolled traffic.
   `seon.health/boot-comparisons` stays (no test covers a boot phase). Whether `significant-tx` then keeps writing
   measurements depends on `:seon.profile/over-bound-warning`'s reader (task
   `seon.profile.measurement/a-test-run-upserts-three-hundred-measurement-rows`).

Dial (config fact `:seon.config.test.cost/top`): 10. The factor, floor and assign-runs dials of slices 2-3 were deleted unread.

## Would it have caught nearest

Yes, on allocation and wall time, not on calls. Baseline (main's member, pre-fix filter): 0.9 MB, 402 calls, ~2 ms per
body. Agent's form: two misses per body (`seon.ref/sectoin`, `nope.ns/foo`), ~15.4 GB each: allocation ≫ 2× and ≫
+64 MB, run-ms ≫ 3× and ≫ +1 s. Calls unchanged (levenshtein unarmed): calls alone would have missed it. Root's
accepted form (713–728 MB per miss) is also red against the filter baseline (≈1,600×): the gate fires on a deliberate
algorithm change, and root accepts it with a reason (step 5). That is the intended trade: ranking every declared
name by edit distance is proportional to the program, and the acceptance records that decision.

## Proof

- `seon.test-cost-test/a-member-records-its-calls-allocation-and-time` (one member stores the three numbers and its
  top rows; repeat run equal calls and allocation).
- `seon.test-cost-test/a-branch-redefinition-allocating-twice-its-baseline-is-red` (an agent branch redefines a
  fixture function to allocate a large array per call; its reaching test is red with `:seon.test/cost-regression`
  naming the function under `:seon.test.cost/grown`; the same redefinition under the floor stays green).
- `seon.test-cost-test/an-accepted-regression-is-green` (root's `:seon.task/cost-accepted` turns it green; the agent's
  own write is refused).
- `seon.task-test/assignment-baselines-the-proofs-on-the-target` (assignment reuses a current target member and runs
  only a missing one).

## Cost

Per member: one sink, two allocation counter reads, four attributes and ≤ 10 rows. Per comparison: one indexed read
of the target member by test symbol. Per assignment: one gating walk plus ≤ 32 member runs when main has no current
evidence. Expected to be invisible against the member's own acquisition (~250 ms).

## Open questions for root

1. Acceptance home: `:seon.task/cost-accepted` on the task (visible on the branch, assigner-written), or a merge-time
   acceptance only (branch stays red, so the task cannot complete by green)? Recommended: on the task.
2. Should calls and allocation also count work the body hands to virtual threads (thread allocation misses it; the
   sink catches calls only through the conveyed world)? Recommended: not in the first cut; record it as a known gap.
3. How did the agent's 15.4 GB form pass its proof inside the 5 s per-test bound (two misses per body; evidence run
   350e63af93de)? If the branch ran the compiled `my.program/source` (the `seon.fn/call-reach` gap filed in 995b01c2d),
   the branch never measured its own code and no cost rule can see it; COST-REG depends on that fix.
4. Delete `seon.health/definition-comparisons` in the COST-REG commit, or after a release with both?
5. The cap of 32 assignment runs: dial value, and whether the predicted set should be proofs + direct callers only
   (cheaper, more lazy runs) rather than the full transitive gating set.

## Dependencies

The `seon.fn/call-reach` transitive-reach task (lane reach-transitive); default must run HEAD for the measurement
proofs (on 2026-10-09 both fan-out-9b proofs ran red on default when run directly, and `seon.health` lists the misspelled-name proof as a failed test).
