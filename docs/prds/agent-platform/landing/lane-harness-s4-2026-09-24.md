---
type: landing
status: S4a committed (db7f1c919), code only under the load rule; S4b stopped before writing (needs a held file)
lane: harness-s4-opus
created: 2026-09-24
design: docs/research/agent-platform/one-test-harness/design-2026-09-24.md §1 "host custody", §2 step 2, §2c, §9c S4
prior: lane-harness-s2b-b-2026-09-24.md (the two open items closed here)
---

# Lane harness-s4 (2026-09-24)

**What exists and the approach.** `bounded-result` ran a host body with `(runner/run-var! v {})`.
`db/call-with-custody` clears any custody it is not handed, so a bare `(db/db)` in a host body had
no connection. `apply-seeds!` then moved only its local member map to the seeded commit. The handle
held in the member's context state still showed the pre-seed commit, and `test-support/execution-handle`
finds that held handle first. Both are now fixed at their owners:

- the host body is handed its member's connection as custody;
- seeding ends with an ordinary `agent/acquire-context!` of the member's own branch. This is the
  same "reacquire from the branch head" that a turn performs. It re-settles the held entry and runs
  `sci.eval/acquire!` at the prepared database.

Nothing new was added: no second handle, cache or stamp.

## Broken or unproven first

- **Not run (load rule).** I made no test request, no adoption and no reload. clj-kondo reports 0
  errors. The new `apply-seeds!` form compiled in a throwaway JVM namespace (since removed). Its
  no-seed path returns the member unchanged; that is the only branch I executed.
  The reacquisition path has **not** run. The regression below is its first proof.
- **Reacquiring through the member itself is "live" to `acquire-context!`.** The branch the
  member's connection holds is the requested branch, so that call computes `isolated? false`. The
  consequences:
  - The connection, base ctx, routing and ownership flags all come from `previous`, the same handle.
  - I changed one line in `agent.clj` so that `:seon.agent/mode` also comes from `previous`.
    Without it the member would report `:live`.
  - `member-result` still releases the pre-seed `child`. Its connection, branch, routing and
    context state are identical to the prepared handle's, and so are its ownership flags.
- **Program-changing seeds.** `acquire!` now reacquires the program when a seed changes program
  rows, because `acquired-database?` compares program identity. The test Var, however, was
  resolved *before* seeding. An SCI test whose own definition is changed by a seed would still run
  the old Var. Refusing that by name remains open (§2 step 2).
- **Behaviour change for every host member.** Host bodies now have `db/*conn*` bound to their
  member branch, where before they had none.
  - Elided writes from host bodies now land on the member branch; before, they refused.
  - `one-request-test/a-member-body-runs-on-its-own-listed-branch` asserted `(nil? db/*conn*)`.
    It now asserts that the body's custody is the member's own connection.
  - Other host tests that assert an elided arity refuses already bind `db/*conn*` nil explicitly,
    as `db-test` does. I found no other reader of the old absence (`rg` over `test/`).
- **Cost.** A member with no seeds pays nothing new. A seeded member pays one `acquire-context!`.
  For data-only seeds that takes the `acquired-database?` fast path: it compares the program basis
  and advances the snapshot. I did not measure it.

## S4b: stopped before writing (needs a held file)

§2c needs `effect/request*` to resolve the declared handler symbol in the requesting evaluation's
SCI ctx instead of `requiring-resolve` (`effect.clj:740`). The request context has no ctx to
resolve in. That context is built in **`src/seon/sci/eval.clj` ≈3586**, which another lane holds.

- **Smallest cut:** add `:seon.sci.eval/ctx evaluation-ctx` in that request-context map (one line
  in `sci/eval.clj`). Then declare it on `:seon.effect/request-context` in
  `resources/seon/schemas/seon.effect.edn`.
- **In `effect.clj`:** resolve through the ctx. Build the handler's refusal validators from its
  admitted row's `:seon.fn/spec` (stored as a string; probe on `seon.edit.jvm/edit`). They are
  currently built from the JVM Var's metadata.
- **Hand-built request contexts:** the tests that bind `effect/*request-context*` by hand
  (effect, instrument, env, edit, shell/web jvm, background-blob, supplied-documentation) must
  supply the ctx too. Otherwise they need a stated refusal, not a fallback to `requiring-resolve`.
- **Size:** about +60–90, which fits S4b once `sci/eval.clj` is released.

**Design question for root (S4c prerequisite):** where does a test attempt's evaluation identity
come from? `request*` refuses without `*request-context*`, and a receipt's `:seon.effect/run` is a
lookup ref, so a turn row must exist. There are three options:

- (a) Each member creates an attempt agent and turn through `agent/creation-tx` and `turn/open-tx`,
  then binds the context. This costs one write per member for about 2,000 tests. It also changes
  every member world: an extra agent, and stewardship of the test namespace through
  `steward-call`.
- (b) Do (a) only for members whose reach includes `effect/request*`.
- (c) Run SCI bodies through `sci.eval/evaluate` under an ordinary turn. This is also in
  `sci/eval.clj`.

I recommend (b), because cost follows reach. This needs the owner's ruling before S4c.

## Changes (commit db7f1c919)

| file | + / − | what |
|---|---:|---|
| `src/seon/test.clj` | +21 / −22 | the `acquire-context!` delay moves above `apply-seeds!`; each seed entry reads the member head; success reacquires the member; the host body gets `(select-keys member [:seon.db/connection])` |
| `src/seon/cluster/agent.clj` | +2 / −1 | reacquisition keeps `previous`'s mode |
| `test/seon/test/seed_preparation_test.clj` | +16 / −3 | `run-seeded` calls under `db/call-without-custody`, as `member-result` does; new class regression `a-host-body-and-its-held-handle-read-the-prepared-member` |
| `test/seon/test/one_request_test.clj` | +2 / −1 | custody assertion updated |

**Net:** +42 / −26.

The regression should fail without the fix in two ways:

- Without the custody change, a bare `(db/db)` returns a missing-connection refusal, so `reasons`
  fails.
- Without the reacquisition, the held handle's commit is the pre-seed commit, not the head.

## Root runs (serially, after adopting the four source/test paths)

    bin/test-check default --ns seon.test.seed-preparation-test
    bin/test-check default --test seon.test.one-request-test/a-member-body-runs-on-its-own-listed-branch
    bin/test-check default --ns seon.test.one-request-test --include-long --time-limit-ms 120000
    bin/test-check default --changed seon.test/bounded-result
    bin/test-check default --changed seon.cluster.agent/acquire-context!

The last two select the host-body population. Compare their reds against the recorded baseline for
any new red whose message names a connection or custody: that would be a host test that relied on
having no custody.

RESET NEEDED: no. Released paths: `src/seon/test.clj`, `src/seon/cluster/agent.clj`,
`test/seon/test/seed_preparation_test.clj`, `test/seon/test/one_request_test.clj`, this note.
