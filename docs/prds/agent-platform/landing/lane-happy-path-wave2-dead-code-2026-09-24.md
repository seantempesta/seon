---
type: landing
status: landed (bff4fac4b, d2604ede9); verification partial, load-paused
lane: happy-path-wave2-dead-code
created: 2026-09-24
---

# Lane happy-path-wave2-dead-code (2026-09-24)

Wave 2 of `docs/research/agent-platform/one-happy-path/README.md`: pure deletions of dead code.

## Broken or unproven first

- **flow-test is red, cause not attributed to this lane.** After the cut, `bin/test-check default --ns seon.flow-test`
  gave pass 132 / fail 10 / error 11 (104.6 s). Seven errors are `Cannot interpret seon.sci.eval-test/deadlined-in: Unable
  to resolve symbol: clojure.core/future-call` (SCI interpretation of another namespace on the test branch). One is a
  stderr assertion that read a live publication message. The rest are a missing test-channel event and one bound overrun.
  No before run exists: both before runs refused `Node not found in storage` (at seon.test/run → acquire-context →
  carried-projection). So no red can be attributed to either side of the cut. Root should rerun at HEAD and at HEAD~ in a
  quiet window.
- **Default adopted an intermediate flow.clj.** The publication hook adopted the cut at about 21:22Z. That version still
  deleted `bounded-platform-executor`, which `resources/seon/operator/runtime.clj:20` calls. I restored it before
  committing. Default's root executor delay was already realized, and `require :reload` does not unmap Vars, so nothing
  is broken live. Hook publication is paused, so default has not adopted the committed HEAD version. Root needs to adopt it.
- `test/seon/schema/datahike_parity.edn` is a historical baseline and still names the deleted flow schemas. The
  canonical-population-native-parity test flags drift from any schema change, so its baseline update belongs with that
  test's owner.

## Commits

| Item | Files | Commit | Lines |
|---|---|---|---|
| Dead launcher callers | src/seon/turn.clj (`submission-time-limit-evaluation`, `submit-evaluation!!`, were :3299-3343) | bff4fac4b | −45 |
| Launcher compute half | src/seon/flow.clj (`capacity-facts`, `capacity-observer-step/-proc`, `with-current-arm`, `refuse-compute-submission!`, `execute-work!`, `submit!!`, compute in-port, `::launcher-step`, compute config read); resources/seon/schemas/seon.flow.edn (10 schemas); tests flow_test (8 tests + helpers), env_test (compute halves; test renamed `a-detached-submission-carries-no-arm`), flow_configuration_test, instrument_test (`submit!!`→`submit!`), cluster/turn_test (false comment) | d2604ede9 | +48 −888 (src −280, schema −42) |

Total: −885 net (src −325, schema −42, test −518).

## Evidence that it was dead

- `rg` over src, test, script, bin and resources found `submit-evaluation!!` only at its definition.
  `submit!!` appeared only there and in tests. `requiring-resolve` finds neither.
- Default's stored reverse edges (`:seon.fn/calls` avet, JVM MCP eval, read-only):
  - `submit-evaluation!!` has no callers.
  - `submit!!` is called only by `seon.turn/submit-evaluation!!` and `seon.flow-test/submit-test!!`.
  - `capacity-observer-proc` is called only by the launcher graph and `flow-test/source-testbed`. The testbed now uses
    `join-probe-step`.
- After the cut no production proc declares `:compute`. The `#{:io :compute}` set-equality in
  flow_configuration_test is dropped, and the per-proc "declares :io or :compute" check stays.

## Verification

- clj-kondo: 0 errors on every touched file. The warnings match their HEAD counts. `seon.error` unused in flow.clj was
  already there at HEAD.
- `bin/test-check default --ns seon.env-test`: 51/51 pass (6.8 s).
- `bin/test-check default --ns seon.flow-configuration-test`: 5/5 pass (3.6 s).
- `bin/test-check default --test seon.instrument-test/the-work-launcher-api-is-collected-without-an-allowlist`: 3/3 pass (3.2 s).
- These ran on the adopted intermediate flow.clj, which is identical to HEAD except that it lacked
  `bounded-platform-executor`.
- Owed, serially and after adopting HEAD:
  - `bin/test-check default --ns seon.flow-test`
  - `bin/test-check default --ns seon.env-test`
  - `bin/test-check default --ns seon.flow-configuration-test`
  - `bin/test-check default --test seon.instrument-test/the-work-launcher-api-is-collected-without-an-allowlist`
  - cluster.turn-test changed only in a comment. It was already badly red: 30 executed, "Isolation requires a fresh
    owned branch".

## Not made: wave-2 items in held files (exact edits)

- **analyze-forms family**: already deleted (delete-dead-code-1, 0cb5fec13). **seon.test.arm**: already deleted.
- **log-line / log-report** (133). Both are dead: `log-report` has no stored callers, and `log-line`'s only callers are
  `log-report` and `error-test/rendered`.
  - Delete `seon.problems/log-report` (problems.clj, held by fix-unstarted-members-unknown) and its problems_test uses.
  - Then delete `seon.error/log-line` (error.clj) and `error_test` `rendered` plus its tests.
  - log-line cannot go first, because problems.clj would stop loading.
- **Dead boot span** (156): src/seon/cluster.clj is held.
  - Delete `boot-phase`, `reserve-cluster!`, `release-reservation!`, `create-directories!`, `require-cluster-target!`
    and `read-advertisement`. None of them has a stored caller.
  - Delete `publication-base!` together with test/seon/cluster/publication_export_test.clj:28. The store holds no
    caller rows for `publication-base!`.
- **Dead snapshot fields** (≈53): src/seon/test.clj, src/seon/test/runner.clj and seon.test.run.edn are held.
  - Delete `:seon.test.run/published-base-digest` and `overlay-input-digest` from seon.test.run.edn (lines 6-11, 85-86,
    101-102).
  - Delete their readers and the `snapshot?` branches: test.clj:82,998,1032-1101,1138-1146, runner.clj:1546,1725,
    cluster/source.clj:446-447.
  - Do this in one commit once those files free up.
- **Follow-ups this cut exposed:**
  - The `:seon.config.flow.compute/{queue-depth,concurrency}` dials no longer have a reader in the launcher. Delete them
    from the config schema, from config.clj:60,81,398,440 and from about 15 test fixtures (cluster/agent_test is held).
  - Check the root `:compute` executor (`resources/seon/operator/runtime.clj:18-21`, `:seon.boot/executors`) for any
    remaining reader.
  - The comment at src/seon/cluster/agent.clj:39-40 names `seon.flow/submit!!` (held file).
  - Stale doc and skill claims name `submit!!` and `submit-evaluation!!`:
    - docs/seon/reference/flow-foundation.md:43
    - .agents/skills/seon-flow-architecture/references/{decisions.md:151, workloads-and-scheduling.md:64,115-123}
    - docs/seon/issues/turn-evaluations-bypass-work-submission.md (the issue is resolved by deletion)
    - docs/seon/issues/map-unions-have-no-explicit-discriminants.md:22
    - docs/seon/issues/the-turn-proc-cannot-answer-ping-or-stop-during-a-model-call.md:23

Released: src/seon/turn.clj (after bff4fac4b, per root), src/seon/flow.clj, seon.flow.edn and the six test files above.
