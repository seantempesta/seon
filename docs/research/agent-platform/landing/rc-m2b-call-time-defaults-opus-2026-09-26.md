---
type: landing
lane: rc-m2b-call-time-defaults-opus
status: implemented and committed; two patches for root (cluster/boot arming db, schema resources); tests not run (root runs)
date: 2026-09-26
base: c236d2d97 (default pid 80197)
tasks: 0261d4d9bbe6, d6fa80e274ac
---

# rc-m2b-call-time-defaults-opus: landing

## Design (before the edit)

**What exists.** Supplied defaults were prepared by a SCI analyzer hook (fork commits 40fcaabd,
6ee57c9c). The hook read the environment of the context the calling closure captured
(`analyzer.cljc:1796` → `seon.call-preparation/hook`). A per-cluster plan atom
(`call-preparation/state`, `install`, `plan`) cached plans. `kernel/invoke` was a second
preparation entrance. A closure made in a connection-less base (row-loaded functions, test
members) or in the agent's context (called from a disposable copy) therefore ran with no world or
the wrong one (tasks 0261d4d9bbe6, d6fa80e274ac).

**The owner.** There is one world per evaluation, and the kernel arm carries it. Every entrance
already arms (`evaluate`, `kernel/invoke`, `sci.eval/run-tests`). The callee's armed contract
wrapper decides its plan once at arming and prepares each call from the arm's world.

**The one correction to the design doc.** The design says the arm already holds `::kernel/ctx`.
It does not. Live at c236d2d97, `(keys (seon.sci.kernel/current-arm))` has no ctx. Nested arming
returned the outer arm unchanged, so the arm could not name a nested evaluation's world either.
This lane adds both:
- the arm names its context;
- a nested `with-arm` scopes that context for its extent, sharing the same deadline and counter
  objects.

**Decision sites deleted:**
- the SCI hook option, Ctx field, call node and test (fork 743d1c43: −65 src, −127 test);
- the hook installation in `build-base-ctx` (−10);
- `kernel/invoke`'s second entrance (−13);
- the plan atom: `state`, `carrier`, `install`, `plan`, 2 install calls (≈ −60);
- `hook`, `refused-call`, `callee-identity`;
- the `prepared-symbols` hot-path gate: it existed only for the hook (−30);
- `fork-candidate-ctx` (−10).

## Broken things first / what root must do

- **Two patches must land with commit 1b77876af (before adoption). Without the first, host
  functions lose their defaults.**
  1. `tmp/rc-m2b-call-time-defaults-opus/cluster-arming-db.patch` (cluster.clj +8, boot.clj +2).
     It passes the cluster database into `instrument/apply!` at boot (`arm-host-program!`) and at
     development adoption, so host wrappers such as `my.plan/current` decide their plans from the
     supplied-default rows.
     - Without it, host contracts arm with no preparation step. `(my.plan/current {})` from SCI
       would then refuse on `:seon.db/db`.
     - `git apply --check` passes. The contract-compile check over the patched files gives `[]`.
  2. `tmp/rc-m2b-call-time-defaults-opus/schemas.patch` (3 resources):
     - deletes the `:prepared-symbols` declaration;
     - rewords `:state` as the atom type (still used by `:seon.render/cache`);
     - adds optional `:seon.db/db` to `:seon.instrument/request`;
     - deletes `:candidate-context-request`.

     It is safe in either order relative to the commit. The EDN reads, and the compile check over
     the patched resources plus every touched source gives `[]`.
- **RESET NEEDED.**
  - The SCI gitlink moved (7d28561e → 743d1c43, the hook removed; pushed to `fork/seon-env-hook`).
    The kernel arm shape changed.
  - A development reload of `seon.sci.kernel` alone is not enough: the running JVM's SCI Ctx record
    still carries the old field.
- **Owner law (total REPL path).** Two failure paths still propagate out of `evaluate-candidate` as
  exceptions:
  - The disposable copy's acquisition. It throws when the agent's environment has no roster, or
    when branch creation fails.
  - The copy's release.

  Whether a thrown gate is a diagnostic beside the value is `turn/gate-function-install`'s
  decision (turn.clj, not this lane's). It must not block the run. Named for the turn/M4 owner.
- **Not measured live (no load of new code into default allowed):**
  - Disposable-copy cost. The earlier lane measured `acquire-context!` isolate at 40–66 ms and
    release at 8 ms. Add `fork-cluster-ctx` of the agent context.
  - The acceptance bar is "well under 1 s". Root measures it in the acceptance run below.
- **Behaviour widened, and must be verified by root's runs.**
  - A host→host call inside an evaluation that omits a declared default is now supplied from the
    world. Before, only SCI→host calls were.
  - A call outside any evaluation that omits a required supplied value now refuses by name: "No
    evaluation is running on this thread … pass it explicitly". Before, it failed the callee's
    arity or contract.
- **Left for M1 (root's 2026-09-26 note, eval.clj):**
  - `shown-result` still runs inside the evaluation's success `try`;
  - renderer `:seon.render.value/diagnostics` are not yet recorded as faults.

  Neither was touched here.
- **Not taken:**
  - accretion's `supplied-registry` still keeps the generator for connections. Now that the check
    world is disposable it could supply them; that is a follow-up.
  - `state-generator` stays because the `:state` declaration names it.

## What changed (commit 1b77876af, plus fork commit 743d1c43)

| file | change |
|---|---|
| `src/seon/sci/kernel.clj` | The arm carries `::ctx`. Nested `with-arm` installs `(assoc armed ::ctx ctx)` and restores on stop. New `world-ctx`, which reads without marking the arm travelled. New `with-world-ctx`. `detached-arm` keeps the submitter's world. The `invoke` preparation entrance is deleted, along with the `call-preparation` require. |
| `src/seon/call_preparation.clj` | Deleted: the plan atom, `plan`, `hook`, `refused-call`, `callee-identity`, `prepared-symbols`. New `arm-plan`, one contract's plan, nil when nothing is suppliable. New `preparing`, the step composed outside Malli's wrapper. `prepare` accepts a nil world and returns a named unavailable refusal. `plan-for` is split into reads plus `compiled-plan`. |
| `src/seon/instrument.clj` | `compiled-wrapper` composes `preparing` when the policy carries a snapshot and `arm-plan` is non-nil. `apply!` takes an optional `:seon.db/db`. `calling-environment` is the arm's world, scoped with `:my.program/executing-ctx`. |
| `src/seon/sci/eval.clj` | Deleted: hook installation and `fork-candidate-ctx`. Native mutation (`intern alter-var-root remove-ns ns-unalias`) is now an SCI `clojure.core` override that asks `my.program/native-call-refusal` over the arm's world. `evaluate-native!` marks its world instead of disabling the hook. Interpreted arming passes the snapshot. `evaluate-candidate` proves in `disposable-copy`: an isolated branch of the agent's commit (the agent entrance), with the evaluating context forked onto it by `fork-cluster-ctx`. The copy keeps the agent id, is its own base, and is released in `finally`. |
| `src/seon/test.clj` | The member world keeps the executing agent's id. Test arming passes its request database. |
| `reference-code/sci` | The hook is removed. `core_test` uses another known option. |
| tests | `call_preparation_test`: plan tests moved to `plan-for`/`arm-plan`. Hook tests are ported to armed Vars (`instrument/apply!` with the fixture db) evaluated under `kernel/with-arm`. New class regression `a-function-made-in-one-context-uses-the-calling-evaluations-world` (d6fa80e274ac, 0261d4d9bbe6). `eval_test`'s `with-redefs` of the hook is replaced by reading `world-ctx`. The custody census names `disposable-copy`. |

Lines, measured:
- Seon src **+341/−323 (net +18)**. `call_preparation.clj` +109/−215. `eval.clj` +140/−74, of
  which the disposable copy is about +75. That copy is deliverable 2, a new capability, so without
  it src is net about −57.
- Seon tests +175/−160.
- SCI fork: src +4/−65, tests −127.
- All code together: **net −159**.
- **Short of the design's −210.** The design counted neither the disposable copy nor the
  kernel's ctx scoping (+25), which the design wrongly believed already existed.

## Evidence (REPL, real data, default c236d2d97, read-only JVM probes in a scratch namespace)

- **Arity plans agree.** Over all 2,287 contracts in default's carried projection, the arm-time
  per-contract arity plans (`arm-plan`'s input) equal the old per-database `planned-arities` for
  every one (0 differ).
- **Arm-time planning:**
  - It takes **22 ms** for all 2,287 contracts, unarmed internals.
  - **893** contracts get a preparation step. `my.plan/current` is one.
  - **1,394** get none, so they pay nothing per call.
- **Per call:**
  - A prepared exact-arity call costs **0.16 µs** (`prepare`, 5,000 calls).
  - `(my.plan/current {})` supplied with `:seon.db/db` and `:seon.agent/id` costs **20 µs**. That
    is supplier cost with armed suppliers. The old path was about 30 µs, plus 8.5 µs of hook on
    every SCI Var call.
  - `seon.db/basis-t` has an empty plan, so it gets no step at all.
- **Static checks.** `seon.contracts-compile-test/check` gave `[]` in three runs:
  - over the 8 touched files (1.38 s);
  - over those plus `my/program.clj` and `cluster.clj` against the patched schema resources;
  - over the patched cluster.clj/boot.clj sources.

  clj-kondo errors: 0. The three patched EDN resources read.
- **Refuted (the design doc's claim):** the live arm has no `::kernel/ctx`. Evidence: `(keys
  (seon.sci.kernel/current-arm))` on branch `rc-m2b`.
- **Checked live:** the cluster handle's context environment carries `:seon.agent/routing`,
  `:seon.store/store` and the cluster name, which `disposable-copy` hands the agent entrance.

## Root runs (after the two patches, the commit, and RESET)

1. `bin/test-check default --ns seon.call-preparation-test`
2. `bin/test-check default --ns seon.test.accretion-test`
3. `bin/test-check default --ns seon.sci.restoration-test`
4. `bin/test-check default --ns seon.contracts-install-test`
5. `bin/test-check default --test seon.sci.eval-test/the-evaluation-world-is-the-form-scoped-environment`
6. `bin/test-check default --ns seon.custody-stability-test`
7. `bin/test-check default --ns seon.cluster.entrance-supplier-test` and `--ns my.program-test`
   (native refusal, supplied context)
8. Acceptance on a fresh MCP branch:
   - `(defn plus1 "…" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))` installs.
   - Count the branch datoms before and after the definition. The auto-check writes nothing to the
     branch: the generated calls ran on the copy.
   - Time the defn. It should be well under 1 s.
   - `(defn plan-now "…" {:malli/schema [:=> [:cat] :int]} [] (count (my.plan/current {})))`,
     then `(clojure.test/deftest plan-now-answers (clojure.test/is (int? (plan-now))))`, then
     `(my.test/run)`. The result should be green, not `refused request at [:seon.db/db]`.
   - `(alter-var-root #'my.program/…)` / `(remove-ns 'x)` still refuse by name.
