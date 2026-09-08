---
type: research
status: active
tags: [research, test, runtime]
---

# Support restoration — 2026-09-08

Read AGENTS.md's copied PRD §10 lane rules, the actual PRD §10, the
cluster-scoped-registry landing (including its fixture handoff), adoption-rows
landing, plan README and working edge end to end. The handoff is currently
headed “Protected test fixture migration”, not “Remaining handoffs”. Loaded
the data-oriented Clojure, testing and REPL skills.

## Change and dependency ledger

The inherited fixture directly unwraps current host roots, then restores
the exact entering callable objects and Malli function-schema registry in
`finally`. The regression deliberately unwraps one entering root, verifies
its original callable identity and absence from the instrumented set, throws,
then verifies the exact entering set, every callable identity, and schemas.
It requires a nonempty entering set. Production cluster removal is not a
test teardown mechanism.

The cohost test uses this fixture around each test, including cluster cleanup;
its obsolete production `remove!` call is deleted. The inherited `closeable`
helper and its setup/cleanup-failure regression are preserved in this slice.
No production seam was needed.

- Malli owns unwrapping: `reference-code/malli/src/malli/instrument.clj:8`
  returns `::original` or the supplied function.
- `src/seon/instrument.clj:580` stores that original on its host wrapper;
  `:33` derives the current instrumented Vars from their metadata.
- `test/seon/test_support.clj:631` owns the existing restoration bracket;
  `test/seon/cluster/cohost_boot_test.clj:66` applies it to the real cohost
  boot regression. The canonical database and real SCI execution are retained.

## Verification

Both commands use `SEON_TEST_WORKERS=3` and
`JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=6`, and the explicit paths
`test/seon/test_support.clj test/seon/test_support_test.clj
test/seon/cluster/cohost_boot_test.clj`.

- `bin/test-fast --paths <paths> -- seon.test-support-test
  seon.cluster.cohost-boot-test`: 9 tests, 81 assertions, zero failures/errors.
  This snapshot predates the extra exact-original assertion and deletion of
  the obsolete cohost removal call; the isolated gate below owns final bytes.
- `bin/test --paths <paths> -- seon.test-support-test
  seon.cluster.cohost-boot-test`: 9 tests, 82 assertions, zero failures/errors,
  no worker-global drift. One worker; restoration test 186 ms, cohost test
  71,703 ms; coordinator/tests 143 seconds, published-base preparation
  71,665 ms. Exit 0; successful root `run.hDTlkY` automatically removed.
- `bin/test --paths <paths> --platform`: 82 tests, 486 assertions, zero
  failures/errors, no worker-global drift or re-arming report. Three workers;
  cohost test 72,309 ms, coordinator/tests 121 seconds, published-base
  preparation 51,304 ms. Exit 0; successful root `run.OKPx7s` automatically
  removed. Platform ran on HEAD `1806ee59616222555565b7bb4f01ba1986639aa3`
  plus the same owned bytes; the earlier scoped gate used HEAD
  `e00b56248577720ee8ffcd6c4da9be937909a8bd`.

Final test bytes (SHA-256):

| Path | SHA-256 |
|---|---|
| `test/seon/test_support.clj` | `a5b5f6e8bfcc8bba9aea102a96d834389fda423c10bbf4328f7c525d9354e9a7` |
| `test/seon/test_support_test.clj` | `e20a8559516b6daca2d4291723142faf396b153db77896f3fbaadf9677f4a07f` |
| `test/seon/cluster/cohost_boot_test.clj` | `f72fcaa7c306f8bf94d28ba69fcc83f1f32761e79ebb9b5d203a88877ac85823` |

The existing default JVM (PID 91455) answered a read-only MCP JVM probe in
54 ms: 908 wrappers, and Malli unwrapping returned a distinct original which
unwrapping again preserved by identity. No live Var was changed by this probe.
MCP runtime status answered with render ping unknown; CLI status waited behind
another publication, then reported default alive and no orphan JVMs. These
are connectivity/dependency observations, not browser or clean-runtime proof.
No default stop, refork or restart was performed.
Later read-only MCP comparison observed adopted and current commit IDs both
`6aa08e33-076c-5001-adcf-05a9e24fb707`, convergence true, in 1,757 ms.
This observes in-place development adoption, not browser paint.

The shared tree had unrelated runner, rendering, hook and test edits. Scoped
gates use HEAD plus only the three owned test files. No foreign session or
file was operated to repair a gate. The existing runtime observation is
tracked by `docs/seon/issues/default-web-request-times-out-during-partial-adoption.md`.

The cohost case in
`docs/seon/issues/a-platform-test-leaves-its-worker-stripped-of-every-contract.md`
is resolved by these scoped and platform observations. This does not claim
verification of that issue's other non-platform cases. No foreign gate
breakage entered either snapshot. No scratch worktree or cluster was created;
the fast snapshot was also removed, and all owned command sessions completed.
No full-suite option was used. `git diff --check` passed, and the three
test-file hashes still matched after platform completion.
