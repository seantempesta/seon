---
type: landing
status: S4a db7f1c919, design ruling ad9f579f4, S4b 15daddb0a, S4c 71f45f476; code only under the load rule, nothing run
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

## S4b and S4c (after the coordinator transferred `sci/eval.clj` and `seon.effect.edn`, 2026-09-25)

The owner ruled that only effect-reaching members get an attempt agent, turn and evaluation. That
ruling is recorded in the design (§2c and the §9c S4 row) in commit **ad9f579f4**.

### Broken or unproven first

- **Nothing here has run (load rule).** clj-kondo reports 0 errors. What I did check:
  - I compiled `context-handler` and `handler-refusal-validators` in a throwaway namespace
    against live default's ctx (since removed). `seon.edit.jvm/edit` resolved through the ctx.
    Its row-spec validators equal its Var-metadata validators (1 = 1). A nil spec gives `[]`.
  - `creation-tx` and `open-tx` accept the attempt's inputs. The first probe caught a missing
    `:seon.cluster/name`, which I then added.
- **Test-namespace handlers install lazily.** In live default's ctx, test-namespace handlers are
  in the program snapshot but not installed: 0 of 3,530 installed functions are `*-test`. The
  lazy installer used to *interpret* their source, which would break any handler that closes over
  JVM test state (such as `handler-calls`).
  - `install-function-from-database!` now binds the compiled Var when the row is neither
    interpreted nor overridden. That is the rule acquisition already applies to loaded namespaces.
  - This changes every lazy install of an unchanged loaded row. Root should watch SCI/program
    tests for a new red.
- **File-store fixtures borrow the member ctx.** `background_blob`, `web/jvm` and `shell/jvm`
  run on a separate file store, and `execution-handle` finds no handle for it. They therefore get
  the member's ctx, not a ctx acquired for that store. Their handlers are already installed there
  (or install lazily). S5 retires these fixtures.
- **`datahike_parity.edn` is stale.** Its baseline was last updated on 2026-09-22, and every
  schema change since then drifts it, including the two in `seon.effect.edn` here. That file is
  not mine to regenerate.
- **Effect-reach cost.** Computing it adds one `seon.fn/gate-set` of `seon.effect/request*` per
  `seon.test/run`. On live default that took 75 ms cold and 52 ms warm, and 430 tests reach it.
  An effect-reaching member then pays two extra writes (agent, then turn). No other member pays
  anything. If the 52 ms per request matters, the memo pattern `destructive-reach` already uses
  would remove it.
- **§2c coverage not written:** the timeout ("wait") attempt, the background request with the
  owned launcher (the attempt context carries no `:seon.flow/work-launcher`), and the
  base/candidate sibling. The regression covers the settled receipt, the refusal of a hidden call
  that reach missed, and the requesting branch staying clean.
- **The attempt writes no evaluation row.** The receipt names its evaluation by
  `:seon.effect/run` and `:seon.effect/form-ordinal` 0. No `:seon.cluster.eval/id` entity is
  written, so `:seon.effect/eval` stays absent, as for other host requests.
- **The attempt agent stewards the test namespace** on the member branch (`steward-call`) when
  that namespace has no steward yet.
- **Open item, recorded rather than fixed.** A seed that changes the test's own definition still
  runs the Var resolved before seeding. `resolve!` closes over the pre-seed database, so
  re-resolving would mean threading the prepared member into `resolve!`, which is larger.
  The smaller cut is a refusal:
  - compare the test row's `:seon.program/definition-digest` before and after the seeds in
    `apply-seeds!` (about 5 lines);
  - add a new `:seon.test.seed/preparation` alternative, such as `:changes-test`, to
    `resources/seon/schemas/seon.test.seed.edn`. That file is **not on my row**.

### S4b, commit 15daddb0a (+77 / −19)

- `sci/eval.clj`:
  - the request context carries `:seon.sci.eval/ctx`;
  - the lazy install change described above.
- `effect.clj`:
  - a request context without a ctx refuses by name (`:seon.effect/missing-context-member
    :seon.sci.eval/ctx`) before any receipt;
  - `context-handler` resolves the declared handler with `kernel/ensure-function!` and
    `sci/resolve`, replacing `requiring-resolve`. An installation failure becomes the
    unavailable-handler refusal's `:seon.error/data`;
  - refusal validators compile from the handler row's `:seon.fn/spec`.
- `seon.effect.edn`:
  - `:seon.effect/request-context` requires `:seon.sci.eval/ctx`;
  - census class 16: `:seon.effect/result-validator` gets `:error/message`.
- Tests:
  - the five hand-built contexts (effect, edit, background_blob, web/jvm, shell/jvm) supply
    their execution handle's ctx;
  - new regression: `effect-test/a-request-context-without-its-evaluation-ctx-refuses-by-name`.

### S4c, commit 71f45f476 (+100 / −8)

- `seon.test/run` computes the effect reach once, and `member-result` passes membership to
  `bounded-result`.
- After seeds, `attempt-request-context` does two things:
  - opens `attempt-<id>` through `agent/creation-tx`, then its turn through `turn/open-tx`;
  - binds `effect/*request-context*` around the host or SCI body: the member's ctx, projection
    state, effective caps and on-core-error policy, a scoped environment, ordinal 0 and a fresh
    counter.
  A refused write becomes the member's failed result.
- New test: `seon.test.effect-attempt-test/an-agent-capability-stub-settles-a-receipt-only-in-an-effect-reaching-attempt`.
  - It admits the agent rows `my.catalog/canned-label` and `my.catalog/label`
    (`:seon.effect/capability`), plus two deftests, and runs both through `seon.test/run`.
  - Member 1's body asserts its own branch's receipt: owner `my.catalog/label`, capability
    `my.catalog/canned-label`, request `"item-1"`, result `"fixture label"`.
  - Member 2 calls through a runtime-built symbol that reach cannot see. It asserts the refusal
    `:seon.turn/id` and that its world has no receipts.
  - The requesting branch holds no receipt.

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

## Root runs (serially, after adopting every path below)

    bin/test-check default --ns seon.test.seed-preparation-test
    bin/test-check default --test seon.test.one-request-test/a-member-body-runs-on-its-own-listed-branch
    bin/test-check default --ns seon.test.one-request-test --include-long --time-limit-ms 120000
    bin/test-check default --ns seon.effect-test
    bin/test-check default --ns seon.edit-test --ns seon.background-blob-test --ns seon.web.jvm-test --ns seon.shell.jvm-test --include-long --time-limit-ms 300000
    bin/test-check default --test seon.test.effect-attempt-test/an-agent-capability-stub-settles-a-receipt-only-in-an-effect-reaching-attempt --include-long
    bin/test-check default --changed seon.test/bounded-result
    bin/test-check default --changed seon.cluster.agent/acquire-context!
    bin/test-check default --changed seon.effect/request*
    bin/test-check default --changed seon.sci.eval/install-function-from-database!

For the `--changed` runs, compare reds against the recorded baseline. A regression from this lane
would name one of: custody or a connection, `:seon.sci.eval/ctx`, "Load the declared capability
handler", or an attempt write.

RESET NEEDED: no. **Foreign edit:** `src/seon/cluster/agent.clj` holds an uncommitted
`namespace-seed-call` hunk that is not mine. I did not commit it or touch it.

Released paths: `src/seon/test.clj`, `src/seon/cluster/agent.clj`, `src/seon/effect.clj`,
`src/seon/sci/eval.clj`, `resources/seon/schemas/seon.effect.edn`,
`docs/research/agent-platform/one-test-harness/design-2026-09-24.md`, and these tests:
seed_preparation, one_request, effect_attempt (new), effect, edit, background_blob, web/jvm,
shell/jvm. This note is released too.
