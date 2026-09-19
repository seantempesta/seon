---
type: research
status: complete
created: 2026-09-19
tags: [testing, selection, runner, lane-a0]
---

# A0 — bare checkout selection refuses missing authority

Read AGENTS.md sections 0–5, the [assignment review](review-stage1-and-test-system-target-2026-09-19.md), the test-system PRD section 0b, and the stage-1 design section end to end. Read namespace-agents plan section 7 and the active roadmap entry. Applied data-oriented-clojure, clojure-testing and repl skills.

## Verified finding and boundary

The entry working tree confirmed review H11: `bulk-selection` called `seon.test/select` without `:seon.test.run/cluster`, threw the returned refusal, read nonexistent selector output `:seon.test/tests`, and acquired a canonical test fixture from production selection code. `bin/test:290` routes unnamed requests through `changed`. The selector's real output is `:seon.test.run/members`; its sound uncommitted implementation was not edited.

The assignment's fallback is necessary: the coordinator receives a cluster **name** and reads snapshot `provenance.edn`, but has no immutable database value for that named authority at selection. Its named-cluster connection is acquired later by `record!`, for completion recording, not selection. The existing `worker-request-admission` shim does not supply custody. Fabricating a fixture, treating the result destination as the tested database, or inventing a snapshot-to-live basis comparison would be incorrect. The stage-1 design's immutable publication/admission handoff is still required; the review assigns the launcher transport to A4. This is more precise than attributing the missing handoff solely to A1's selector internals.

Deleted `bulk-selection` and its now-unused path reader. The HEAD-only commit also removes its old `reaching-selection` helper, already absent in the inherited WIP. Explicit all/full/platform behavior stays at the existing caller. Bare `changed` requests now print an evidence-complete typed refusal and return exit 2 **before coordinator manifest loading, executor creation or worker startup**:

- No explicit cluster (`"-"`): `:seon.test/cluster-required`.
- Explicit cluster but no selection database handoff: `:seon.test/selection-authority-unavailable`.

Both name `seon.test/select`, the missing `:seon.db/db`, the explicit cluster observation, and the required published database/cluster-ref pair. No selection is guessed and no full run is substituted. Successful bare selection remains owed after the authority handoff; this note does not claim it works.

## Dependency ledger and verification

- Existing diagnostic constructor: `src/seon/error.clj:338`, returning `:seon.error/value`.
- Existing selector: `src/seon/test.clj:613`; no selection algorithm copied or changed.
- Coordinator provenance/selection and result connection: `src/seon/test/runner.clj`, `run-coordinator!` and `record!`.
- Canonical fixture: `test/seon/test_support.clj`, `with-database`, `seed-cluster!`; ordinary test execution/reporting is `reference-code/clojure/src/clj/clojure/test.clj:710–738` (`test-var`, `test-vars`). The regression uses the actual coordinator entry, not a mocked selector.

MCP runtime status observed default pid 41822 alive. One read-only JVM evaluation observed basis 536871546 and a loaded `seon.test/select`. No lifecycle command, live reload, or publication was performed; changed runtime behavior is not claimed live-proven.

The requested shared-tree fast overlay exited 64 before running tests: `Incomplete --paths overlay; add changed caller files: src/seon/test.clj`. Those dependencies belong to the inherited admission WIP, outside A0. An isolated HEAD worktree at `f28098ce59e4f009e85cc86fc038a1033bc1977b` therefore carried only A0's runner change and regression. Dependencies and the existing published-base cache were linked from the shared checkout; no baseline was published. The fast launcher reported graph `88a14feec76caf3901a9e08103e2cd8e14dc5aa43bdf46780dafd476876b8999`, 14 commits behind HEAD. The initially unlinked worktree correctly refused for no published graph before the existing cache was linked.

Fast tally: **24 tests / 194 assertions / 0 failures / 1 error**, exit 1. A0's new regression passed (11 assertions, both refusal branches). The unrelated error is `the-canonical-platform-tier-preserves-file-local-uncertainty`: `verify-platform-tier-carries-no-destructive-drill!` refused `seon.dev.fresh-operator-reset-test/managed-root-cleanup-loads-no-program-and-never-follows-symlinks` and `seon.dev.fresh-operator-reset-test/source-syntax-refuses-before-lock-or-destruction`. That check/declaration boundary is outside A0; it was observed on HEAD plus A0, not attributed to a foreign uncommitted change. The test is still red, and no green namespace tally is claimed. The JVM exited and its disposable snapshot was removed by the fast launcher. `git diff --check` passed. A later docstring-only clarification was not rerun.

## Files and integration owed

A0 changes only `src/seon/test/runner.clj` (selection region and caller), `test/seon/test/runner_test.clj` (one canonical-fixture regression), and this landing note. `bin/test` needs no selection-mode change: its existing `changed` mode reaches the corrected refusal boundary. All unrelated uncommitted bytes, including the selector, schema, admission work and launcher inventory additions, are preserved and excluded from A0's commit.

Orchestrator cold proof owed, after the held slice converges:

```sh
bin/test --paths src/seon/test/runner.clj bin/test test/seon/test/runner_test.clj -- seon.test.runner-test
bin/test
```

The bare command must currently exit nonzero with the named typed refusal, not throw or silently run everything. The orchestrator owns platform and later successful bare-selection proof.
