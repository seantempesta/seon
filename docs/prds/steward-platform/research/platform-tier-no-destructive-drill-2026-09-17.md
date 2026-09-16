---
type: research
status: active
tags: [testing, platform-tier, destructive, store, gate]
---

# The platform tier declares no destructive drill (2026-09-17)

Item 3 of
[a-platform-tier-test-wiped-the-checkouts-store](../../../seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md).
Items 1 and 2 landed in the peer's `ccccea806`: a recursive deletion is
admitted only with an absolute root and target under a DECLARED operator
root. This lane adds the second line of defence — the tier that runs FIRST
on every `bin/test` invocation carries no test that deletes a filesystem
path, enforced where the tier is selected.

## 1. Archaeology

`:seon.test/platform` is Var-or-namespace METADATA, never a program fact:
`seon.test.runner/marker-reason` (`src/seon/test/runner.clj:610`) reads the
Var's metadata first and falls back to its namespace's, and refuses a blank
reason. `test-selection` (`src/seon/test/runner.clj:648`) partitions the
loaded test Vars into `:seon.test/long` skips, the platform tier, the bulk
tier and the unreached. `run-coordinator!` runs the platform tasks first and
skips the bulk tier entirely when they are red
(`src/seon/test/runner.clj:3346-3375`).

Reach is derived from the program graph, not from names: the runner already
does exactly this for expensive fixtures — `expensive-fixture-tests`
(`src/seon/test/runner.clj:828`) resolves declared owner symbols against the
manifest rows, refuses when one fails to resolve, and walks `:seon.fn/calls`
backwards through `seon.test.selection/reaching-tests`.

## 2. Which owners are genuinely destructive (measured)

Derived live on the development JVM (pid 38993, `:current-src` 6aaa7aef →
6aaa7e6b), over `:seon.fn/calls` edges in the published program graph
(4,017 function rows with call edges, 1,771 test rows with call edges).
Platform-tier membership taken from the 13 namespace-level markers plus the
7 Var-level markers in `seon.test-runner-test`.

| candidate owner | platform tests reaching it |
|---|---|
| `seon.cluster.store/admit-destructive-path!` | 42 |
| `seon.cluster.store/create-store!` | 42 |
| `seon.cluster.registry/reset-cluster!` / `retire-branch!` / `collect!` | 23 (combined) |
| `seon.operator/cleanup-root-under-lock!` | 0 |
| `seon.test-support/populate-published-root!` | 2 |
| `seon.test-support/populate-published-operator-root!` | 1 |

**The assignment's proposed predicate — "reaches the delete admission seam"
— is refuted by its own numbers.** `create-store!` calls
`admit-destructive-path!` unconditionally, and every `open-store!` calls
`create-store!`, so the admission seam is reached by every file-store
fixture in the suite. The measured shortest path is uniform, e.g.

```
seon.cluster.registry-test/a-concurrent-create-wave-loses-nothing
  → seon.cluster.registry-test/with-source-store
  → seon.cluster.store/open-store!
  → seon.cluster.store/create-store!
```

Declaring that seam destructive would move 42 of roughly 80 platform tests
out of the tier — the whole of `store_test`, `source_test` and
`registry_test` — while naming nothing the incident was about. The admission
seam is the SAFETY the peer added, not the hazard; `create-store!` deletes
only its own incomplete genesis and now refuses a store whose `:branches`
roster is present.

Datahike branch retirement and collection are excluded for the same reason:
they act on a store handle the fixture already holds and cannot spell a
foreign path.

What remains is the class the incident actually travelled: **a function that
deletes a filesystem path it did not create.** Three of them exist:

* `seon.test-support/populate-published-root!`
* `seon.test-support/populate-published-operator-root!`
  (both `replace-directory!` = `delete-recursively!` + clone over a store
  directory — this is the exact call that emptied `data/store`)
* `seon.operator/cleanup-root-under-lock!` (removes an operator root's whole
  `data/`)

## 3. The checker

`src/seon/test/runner.clj`:

* `destructive-owners` — the three symbols, declared beside the fixture
  owners with the reasoning above, including why `create-store!` is not one.
* `destructive-owner-rows` — resolves every declared owner against the
  manifest rows and REFUSES `:seon.test.runner/missing-destructive-owners`
  when one does not resolve. Without this a rename would leave the checker
  walking to nothing and reporting the tier healthy: the project's recurring
  absence-of-signal class.
* `destructive-call-path` — the shortest `:seon.fn/calls` path from a test
  down to an owner, so the refusal hands its reader evidence rather than a
  verdict.
* `verify-platform-tier-carries-no-destructive-drill!` — called from
  `run-coordinator!` immediately before `verify-fixture-observations!` and
  before the first platform task is dispatched. Because it derives the tier
  from the Vars the selection actually produced and the reach from the
  program graph, metadata drift cannot bypass it: re-marking a demoted test
  `:seon.test/platform` fails `bin/test` with a typed refusal naming the
  test and its path.

## 4. The three platform tests that reached a destructive owner, and where they went

| test | owner reached | move |
|---|---|---|
| `seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation` | `populate-published-root!` | namespace-level `:seon.test/platform` marker removed; the reason and the rule are now in the namespace docstring. Bulk tier. |
| `seon.test-runner-test/consecutive-cache-invocations-reuse-the-published-base` | `populate-published-root!` | Var-level `:seon.test/platform` marker removed (its `:seon.test/fixture-observation` is unchanged); comment names the rule. Bulk tier. |
| `seon.test-support-test/simultaneous-fixture-bases-never-open-the-published-store` | `populate-published-operator-root!` | the namespace-level marker was moved onto each of the other 12 deftests, so this one Var — and only this one — leaves the tier. Bulk tier. |

No coverage is lost: all three run in the ordinary bulk selection whenever a
change reaches them, and under `--all` / `--full` at the orchestrator's
integration checkpoints. None was demoted to `:seon.test/long`.

Measured after the moves, in the development JVM through `seon.test`'s own
test loader:

```
(#'seon.test.runner/test-selection
 '[seon.test-support-test seon.cluster.cohost-boot-test] …)
=> platform 12, bulk selected
   ["seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation"
    "seon.test-support-test/simultaneous-fixture-bases-never-open-the-published-store"]
```

## 5. In-process evidence (pid 38993, adopted `:current-src` 6aaa7e6b)

Run on a daemon thread with
`{:seon.test.run/provenance (seon.test.runner/provenance (seon.db/db c))
  :seon.test/remaining-ms 100000}`:

* `seon.test.runner-test/the-platform-tier-declares-no-destructive-drill`
  — **8 passes, 0 failures, 0 errors.**

Live derivation against the real manifest (336 artifacts, built on a daemon
thread per the BASE CONSTRUCTION RULE):

* `destructive-owner-rows` resolves all three owners;
* 55 tests in the whole suite reach a destructive owner, of which the three
  above were platform;
* `destructive-call-path` for the `test_support_test` case returns
  `["seon.test-support-test/simultaneous-fixture-bases-never-open-the-published-store"
    "seon.test-support/populate-published-operator-root!"]`;
* removing `cleanup-root-under-lock!` from the rows refuses with
  `{:seon.error/kind :seon.test.runner/missing-destructive-owners
    :seon.test.runner/missing-destructive-owners
    ["seon.operator/cleanup-root-under-lock!"]}`;
* an empty platform set and a clean Var both return `nil`.

**Cold-only, deliberately not run in process:** every test that reaches a
destructive owner, including
`seon.test.runner-test/expensive-fixtures-require-a-declared-observation`
and the three demoted tests. An in-process run of exactly that class is what
wiped the store; the new regression is pure over a manifest and acquires no
fixture. `seon.test-runner-test` could not be loaded through the in-process
test loader at all (`Syntax error macroexpanding at (dev_cache.clj:1:1)`),
so its one demotion is metadata-only and is proven by the cold gate.

## 6. Named hunks in protected files (not made here)

* `test/seon/test_support.clj` — the two population fixtures are declared
  destructive in the runner rather than at their own defns. If the owner
  prefers the declaration at the seam, the hunk is a
  `^{:seon.fn/destructive true}` marker on `populate-published-root!`
  (`test/seon/test_support.clj:101`) and
  `populate-published-operator-root!` (`test/seon/test_support.clj:124`),
  plus a `:seon.fn/destructive` facet in `resources/seon/schemas/seon.fn.edn`
  and its `assoc` in `seon.fn/var-row`. It was cut here as speculative
  addition: the three owners resolve against the program graph today and the
  facet would have no other user.
* `src/seon/operator.clj:310` — same marker for `cleanup-root-under-lock!`.

No protected file was edited.
