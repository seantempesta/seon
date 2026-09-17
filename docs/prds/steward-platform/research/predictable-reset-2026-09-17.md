---
type: research
status: landed
created: 2026-09-17
tags: [operator, reset, preflight]
---

# Predictable reset

## Owner order and incident

Owner order, 2026-09-17 20:30Z: “We should have a predictable reset that
always works.” A reset must refuse before destruction with a complete repair
diagnostic, or complete. Once destruction has happened, every refusal must
leave recorded phase evidence and print the commands that continue the reset.

The raw evidence was `tmp/orchestrator/reset-2026-09-17-second.log` and
`data/operator/operations/reset-start-72356.log`. The reset completed down,
destroy, republish, and refork, then refused at start because
`test/seon/test_support_test.clj` used the unresolved alias `d/listen!`. The
original preflight checked syntax only and ran minutes before start, so it
admitted the defect and printed no recovery path after the store was destroyed.

## Dependency and owner seams

- `bin/seon:1-40` executes the operator with Babashka.
- `script/seon/dev/clj_kondo.clj:64` now owns the output/analysis configuration
  used by both the edit hook and operator preflight. Its existing
  `ensure-dependency-cache!` at line 79 remains the single owner that derives
  the project classpath, fingerprints dependency inputs, and populates
  `.clj-kondo/.cache/v1` with `--copy-configs --skip-lint`.
- `bin/seon-hook:248` and `script/seon/fresh_operator.clj:310` use that shared
  configuration with `--cache false` for partial first-party lint runs, so a
  partial source analysis cannot poison the persistent dependency cache.
- `script/seon/fresh_operator.clj:3441` owns reset phase order and
  `resources/seon/operator/state.clj` continues to own bounded subprocesses and
  lifecycle locking. No new state flag was added.

## Landed behavior

`source-preflight!` retains the 5,000 ms admission bound and now treats syntax,
unresolved namespace, unresolved var, and unresolved symbol findings as errors.
It records a sorted path-to-content-digest snapshot of dirty first-party source.
The first check still occurs before lifecycle-lock acquisition, down, or
destruction. A second check after lock acquisition catches changes made while
waiting for the lock.

Reset carries the first snapshot through its phases. Immediately before start
and before development adoption, it lints only paths whose content differs from
that snapshot. Thus a shell write during republish or refork cannot reach JVM
boot unexamined. A refusal at reset start prints exactly:

```text
bin/seon start default
bin/seon init --dev default
```

Earlier post-destroy failures print the additional remaining publication or
refork commands; an adoption failure prints only the remaining adoption command.
The continuation is derived from the failed phase.

`bin/seon status` derives `reset incomplete at <phase>` from existing reset
phase logs after a completed destroy. It does not store a boolean mirror. A
later reset that refuses before destroy cannot hide the earlier incomplete
reset, and the notice clears when either that reset's start phase or a later
standalone start phase completes.

The platform regression namespace now covers syntax and unresolved-alias
refusal before lock/destruction, content changes between initial preflight and
reset start, the exact continuation text, status derivation, and clearing the
notice after a successful recorded start. The repaired file passes the same
pre-start admission seam before continuation.

## Verification

Fast snapshot command:

```text
bin/test-fast --paths bin/seon-hook script/seon/dev/clj_kondo.clj script/seon/fresh_operator.clj test/seon/dev/fresh_operator_reset_test.clj -- seon.dev.fresh-operator-reset-test
```

Measured tally: 8 tests, 109 assertions, 0 failures, 0 errors. The orchestrator
still owes the cold path-limited gate and platform proof. The shared Seon MCP
tools were unavailable to this lane, so no live default-cluster probe was
claimed; the lane did not operate `default`.

Foreign protected paths remained untouched:
`src/seon/cluster/source.clj`, `src/seon/db.clj`,
`test/seon/cluster/source_test.clj`, and `test/seon/test_support_test.clj`.

## Deeper test-namespace boot policy — owner decision

1. **Recommended — keep whole-cluster refusal (simplest).** A cluster starts
   only when every first-party namespace, tests included, loads into the
   evaluation context. The guarantee is one complete program graph with no
   silently missing namespace. The implementation cost is zero beyond this
   predictable preflight and continuation repair. We give up availability when
   a test-only namespace has a boot-refusing defect.

2. **Exclude the failed test namespace and commit a boot fault fact.** The
   cluster becomes available while the durable fault names every excluded
   namespace. This requires a truthful partial-admission model, fault commit
   path, rendering, recovery, and tests proving absence is never read as
   health. We give up the guarantee that a running cluster contains the entire
   published first-party program.

3. **Make exclusion depend on `:seon.config/on-core-error`.** Under `:record`,
   exclude the test namespace and commit the boot fault; under `:panic`, refuse
   boot. This aligns the behavior with the existing dial but has the highest
   implementation and reasoning cost because identical program facts acquire
   environment-dependent boot semantics. We give up one predictable boot
   guarantee across development and production modes.

No boot-policy option beyond the reset repair was implemented.

## 2026-09-17 follow-up — boot carries no test namespaces

The owner selected the former option A in
`docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md`
§1n: cluster boot is the platform from `src/`; tests are indexed program facts
loaded after boot by the test system.

`launch!` no longer hands the resolved test classpath to the detached cluster
JVM. The publication JVM still receives that classpath, and `seon.test/run`
continues to resolve test Vars under `seon.test/with-test-loader`; these are
different processes and different phases. The launch-command regression now
asserts that neither `-Scp` nor test-only JVM options enter cluster boot.

The platform/long regression performs a complete publication, forks an
isolated `default` branch, and starts it with a `test/` namespace whose
top-level form throws if required. Boot reaches `ready`. Afterward the test
constructs the existing resolved test loader, resolves a separately indexed
test by identity, and runs it through `seon.test/run` with the isolated
cluster's connection and carried database projection. Result: one pass, zero
failures, zero errors.

Fast snapshot proof:

```text
bin/test-fast --paths script/seon/fresh_operator.clj test/seon/dev/fresh_operator_reset_test.clj -- seon.dev.fresh-operator-reset-test
```

Measured tally: 9 tests, 115 assertions, 0 failures, 0 errors. Publication
indexed 371 source inputs and 6,408 declarations; the isolated cluster reached
every boot phase through web/ready. The test stopped the one isolated JVM and
the runner removed its snapshot root.

The `classpath-locatable?` docstring was clean when inspected and this lane
edited only that docstring. While the real-boot regression was running, the
concurrent `boot-load-bounds` lane landed the same resulting bytes in
`940f4b426`; no implementation hunk in its protected region was touched here.
The shared tree's dirty `src/seon/db.clj`, `src/seon/fn.clj`, and their tests
remained outside every fast snapshot.

## 2026-09-17 follow-up — bare cold gates are HEAD-exact

The retained `tmp/test-runs/run.OZGw7Q` evidence showed a bare platform gate
loading an uncommitted test while reporting no difference from HEAD. The
launcher had two conflicting meanings for a missing `--paths`: it documented
HEAD-exact behavior but populated `overlay_paths` from every dirty tracked and
untracked file.

That implicit overlay is removed. `git archive HEAD` is now the complete bare
snapshot constructor; only explicit `--paths` add working-tree bytes. The
difference report separately builds a private index from
`git ls-tree -r -z HEAD` and refreshes it against the run root. It refuses
before dependency preparation or a JVM if any non-named tracked path has a
different Git mode or blob. The private index avoids the original false-clean
class: the invoking checkout's index may carry stat information for a
different work tree.

The platform regression proves the ordinary and failure paths. A dirty tracked
test file omitted from `--paths` remains at its committed bytes in the bare run
root and worker. A fixture archive extractor then injects those dirty bytes
after extraction; snapshot admission exits 64 and names the exact test path
before the fake JVM runs.

The focused regression passed in a HEAD worktree with only `bin/test` and
`test/seon/test_runner_test.clj` overlaid. The worktree was necessary because a
concurrent lane left a transient reader error in the shared working tree; no
foreign path entered the diagnostic snapshot. The required combined fast tally
used HEAD `b56410f55` plus only the four requested paths:

```text
bin/test-fast --paths script/seon/fresh_operator.clj bin/test test/seon/dev/fresh_operator_reset_test.clj test/seon/test_runner_test.clj -- seon.dev.fresh-operator-reset-test seon.test-runner-test
```

The new isolated boot and snapshot regressions both passed. The total was 60
tests and 490 assertions, with 7 failures and 5 errors in pre-existing
`seon.test-runner-test` result-recording expectations on that HEAD. The named
foreign boundary is the concurrent program-facts symbol transition: examples
were empty captured per-test maps, the writer now refusing a retracted test as
`:seon.test.runner/test-definition-absent`, and
`gate-completions-travel-as-a-file-not-as-code` still supplying a string where
the installed contract requires a namespaced symbol. None is in the launcher
snapshot hunk. The focused snapshot regression also passed alone in the clean
HEAD worktree. The orchestrator still owns the cold gate and platform proof.
