---
type: issue
status: resolved
severity: blocker
created: 2026-09-17
tags: [testing, platform-tier, store, root-resolution, destructive]
---

# A platform-tier test wiped the checkout's store

## Problem

At 10:17Z on 2026-09-17 the development root's `data/store` (3.6 GB, live
default cluster on pid 17352) became empty: publications refused with "no
branch or commit `:db` to branch from", `bin/seon status --verbose` reported
"the current-src branch is absent", and after a stop/start the store
directory was recreated from nothing (28 KB, files dated at boot). No
orchestrator or lane command reset, destroyed or collected the main root in
that window. The gate ledger shows batch 61 A (the PLATFORM tier, a cold
`bin/test` in its own run root) running
`seon.cluster.registry-test/non-temporal-collection-marks-current-blob-references`
at 10:18:11Z and `seon.cluster.registry-test/reset-returns-a-cluster-to-source-state`
at 10:18:12Z (`tmp/orchestrator/gate-results/batch-61/platform.log:468-472`).
Hypothesis under investigation (read-only lane): one of those fixtures
resolves its store/root to the checkout (`"."`, `user.dir`, or a nil
property falling to cwd) instead of its isolated operator root, so a
platform gate can reset the developer's store — the same class as the
`workers/` exhaust fixed in `86b4c8ff4`.

Evidence copy of the emptied store: `tmp/orchestrator/refork/store-wiped-2026-09-17T1100Z/`.
Recovery: `bin/seon reset --force` + start + reseed (data disposable by
ruling); the day's recorded test results were lost.

## Cause (established 2026-09-16, fix lane)

Not a garbage collection and not the platform tier's own drill: the store was
DELETED AND RE-CREATED FROM GENESIS by a path that resolved its root from the
PROCESS WORKING DIRECTORY (`docs/prds/context-generation/research/store-wipe-2026-09-17.md`
§1-§2 established the genesis shape; this lane established the seam).

`seon.cluster/operator-root` answered `-Dseon.operator.root` BEFORE the root
its caller genuinely held, and fell back to the working directory when neither
was declared. Both halves point at the developer's store:

* undeclared JVM (`bin/test-fast` sets no operator root): the default cluster
  root `"data/clusters"` is relative, so `(resolve-bootstrap {})` derived the
  checkout and `:seon.boot/store-dir` = `<checkout>/data/store`;
* declared JVM (the `default` development JVM declares the checkout): a
  fixture's explicit `tmp/<name>/<uuid>` root was OUTRANKED by the property, so
  `(resolve-bootstrap {:seon.boot/root "tmp/blob-publication-test/<uuid>"})`
  also returned `<checkout>/data/store` — and
  `seon.test-support/populate-published-root!` then ran
  `replace-directory!` (delete-recursively! + clone) over it. That is an
  in-process fixture run wiping the development store, verified live at the
  REPL on 2026-09-16: the same call now returns
  `<checkout>/tmp/blob-publication-test/<uuid>/data/store`.

Nothing recorded the deletion, so a 3.6 GB removal left no line anywhere — the
project's recurring absence-of-signal class.

## Fix (2026-09-16)

THE RULE, stated in the docstrings of both destructive owners: a recursive
deletion is admitted only when its authority root and target are ABSOLUTE
spellings, the canonical target lies under the canonical root, and the target
is outside the working directory's own `data/` unless the caller DECLARES that
directory as the operator root this JVM was launched to operate. The
declaration is an argument, not a property read at the seam. `bin/seon
[--root PATH]` declares it on every child JVM, so `bin/seon reset --force`
still destroys the checkout's data deliberately; `bin/test` workers declare
their own isolated run root and `bin/test-fast` declares none, so no worker,
fixture, or lane JVM can spell the developer's `data/store` at all.

* `src/seon/cluster/store.clj` — new `admit-destructive-path!` (the one rule),
  `declared-operator-root`, and `log-deletion!` (root, canonical targets, file
  bytes, first-party caller frame, pid, logged BEFORE the delete);
  `create-store!` admits and records its deletion and now RE-DECIDES at the
  seam: a store whose `:branches` roster is present is never deleted.
* `src/seon/operator.clj` — `declared-managed-root` refuses a nil, blank or
  relative managed root before any path is derived; `managed-data-paths` uses
  it; `cleanup-root-under-lock!` admits EVERY path before the first deletion
  and records the aggregate report.
* `src/seon/cluster.clj` — `operator-root` derives from the root the caller
  genuinely holds FIRST, uses the declared property only when the cluster root
  resolves to the working directory, and refuses when neither is available.
* `test/seon/test_support.clj` — `populate-published-root!`'s deletion
  authority is the run root it holds, never the JVM-wide operator root.

## Regressions

* `seon.operator-test/a-destructive-root-is-declared-never-inferred-from-the-working-directory`
  — nil/""/"."/relative roots each refuse with a typed value and delete
  nothing; the checkout's store is refused for an undeclared root and admitted
  for the declared one; a legitimate scratch cleanup completes, never follows
  the symlinked sentinel out of its root, and records root/targets/bytes/caller;
  the checkout's `data/store` byte count is asserted unchanged throughout.
* `seon.cluster.store-test/creation-never-deletes-a-complete-store-or-an-undeclared-root`
  — the inferred-root refusals at the store seam, and a complete store
  surviving a `create-store!` call with its durable marker intact.

## Original fix shape

1. The fixture derives its root from the isolated operator root it was
   handed, never from the process's cwd; a nil root refuses, never defaults.
2. A regression plants a sentinel store under a fake checkout `data/` and
   asserts a reset/collect drill never touches it (recursive deletion never
   follows symlinks; plant a symlinked sentinel).
3. The platform tier declares no destructive drill: destructive tests are
   `:seon.test/long` or run only under an explicit isolated root, and the
   tier's selection checker fails when one is declared `:seon.test/platform`.

(1) and (2) landed in `ccccea806`.

**(3) landed 2026-09-17** (`docs/prds/steward-platform/research/platform-tier-no-destructive-drill-2026-09-17.md`).
The three functions that delete a filesystem path they did not create —
`seon.test-support/populate-published-root!`,
`seon.test-support/populate-published-operator-root!` and
`seon.operator/cleanup-root-under-lock!` — and
`destructive-owner-rows` RESOLVES each against the program graph, refusing
when one does not, so a rename cannot leave the checker walking to nothing.
`verify-platform-tier-carries-no-destructive-drill!` runs inside
`run-coordinator!` before the first platform task is dispatched: it derives
the reach from `:seon.fn/calls` and refuses the run, naming each offending
test and its call path to the owner. Metadata drift cannot bypass it.

Three platform tests reached a destructive owner and are now bulk-tier:
`seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation`,
`seon.test-runner-test/consecutive-cache-invocations-reuse-the-published-base`,
`seon.test-support-test/simultaneous-fixture-bases-never-open-the-published-store`.
No coverage was dropped and none was demoted to `:seon.test/long`.

**The in-process half landed 2026-09-17**
(`docs/prds/steward-platform/research/in-process-run-refuses-destructive-tests-2026-09-17.md`).
The wipe was an in-process `seon.test/run`, so the prose rule ("those are
cold-only", `tmp/orchestrator/wave2/repl-rule.txt`) is now a check at the one
seam every in-process execution passes through. `seon.test/run` refuses a test
whose `:seon.fn/calls` reach includes a member of the same
declared destructive owner set — no second list — when the operator
root this JVM DECLARES is the development checkout it runs in; it executes
nothing and commits nothing, and the typed
`:seon.test/destructive-in-process` refusal names the test, the owner, the
shortest call path, and the cold invocation that may run it. `seon.test/check`
excludes the same tests from its in-process selection and reports them in
`:seon.test/destructive-excluded` (also in its feedback line), and hands the
declaration it holds to every run it starts. A `bin/test` worker, a lane's
`--root` scratch JVM and `bin/test-fast` declare no development root and are
untouched. An owner symbol with no program row refuses instead of admitting.
Regressions: five in `seon.test-reaching-test`, 5/10/5/10/4 passes in process
on pid 38993, 0 failures, 0 errors.

Refuted while doing it: "destructive = reaches the delete admission seam"
over-approximates to 42 of roughly 80 platform tests, because
`seon.cluster.store/create-store!` admits unconditionally and every
`open-store!` reaches it. The admission seam is the safety `ccccea806`
added, not the hazard. Regression:
`seon.test.runner-test/the-platform-tier-declares-no-destructive-drill`.

## Verdict 2026-09-17 11:40Z (peer research `fbd9c0cd9`)

The store was DELETED AND RE-CREATED FROM GENESIS, not collected: the
evidence copy's `:branches` is exactly `#{:db}` (only `d/create-database`
writes that). GC and `registry_test`'s own store fixture are cleared. Window
10:17:35Z (healthy, 91,443-row population logged) → ~10:50Z; the platform
tier began 10:18:11Z inside it. The two paths with exactly this shape are
`(io/file <root> "data" "store")` with a nil/relative root —
`src/seon/operator.clj:277/:294` (cleanup deletes clusters/store/lock/
blob-staging) and `src/seon/cluster/store.clj:281` (`create-store!` deletes
then creates) — with root fallbacks at `cluster.clj:792` and
`test_support.clj:112` (`bin/test-fast` sets no operator root): the
`86b4c8ff4` class. Exact caller not established (nothing logs a deletion;
the evidence copy lost mtimes — copy with `cp -Rp` next time). Fix lane
(peer, Opus): a nil/relative/"."/checkout root is unconstructable at both
owners (typed refusal before any delete; the owner's deliberate `reset
--force` keeps its explicit path); a log line naming root/paths/bytes/caller
before every delete; the two fallbacks removed; the symlinked-sentinel class
regression. Gates stay held until it lands. Full page:
`docs/prds/context-generation/research/store-wipe-2026-09-17.md`.

## Actual cause 2026-09-17 12:00Z (peer `ccccea806`)

`seon.cluster/operator-root` answered the JVM property `-Dseon.operator.root`
(default's own root = the checkout) BEFORE the root the caller held — a
fetch-at-call-time defect (§2.1). So an IN-PROCESS fixture run inside
default's JVM (`test-support/populate-published-root!` with a
`tmp/<test>/<uuid>` root; verified live: resolve-bootstrap with that root
returned `/Users/sean/src/seon/data/store`) ran delete + clone over the
developer's store. A lane's repl-rule run did it, not a gate; every lane on
default could have. Fixed: recursive deletion admitted only with absolute
root+target under a root the JVM was DECLARED to operate; operator-root
takes the caller's root first; create-store! refuses to delete a store whose
`:branches` roster is present; every delete logs root/targets/bytes/caller/pid.
Regressions 22/0/0, 6/0/0. Gates resumed (batch 65). Item 3 (platform tier
carries no destructive drill; tier checker) is a separate lane.

**The roster became a declaration, 2026-09-17** (owner ruling F1;
`docs/prds/steward-platform/research/destructive-tests-derived-2026-09-17.md`).
`seon.test.runner/destructive-owners` was a hand-maintained set of three
symbol strings in code. It is deleted. Each owner now carries
`:seon.fn/destroys` in its own metadata at its definition, saying what it
deletes; the indexer admits it as a program fact; and both the cold tier
checker and the in-process refusal derive the owner set from that one
attribute. A test's host is derived per question by `seon.test/host` over
`:seon.fn/calls`, never stored, and the test entity's AI and HTML renders
say where the test runs and why.


## Batch 115: file uncertainty escaped its file (2026-09-17)

The cold checker falsely refused the registry fixtures because its
`tests-reaching-rows` helper collapsed every non-owner row into one synthetic
file. The selector's conservative unresolved-file reach then selected every
test in that artificial file. No fs admission declaration caused this:
`seon.fs/admit-destructive-path!` and `create-store!` remain undeclared.
The fix preserves actual artifacts through both destructive and expensive
fixture selection. The Flow graph census genuinely references scheduled root
cleanup and moves to the ordinary tier; its construction-refusal sibling
remains platform. The checker and all destroyer declarations remain intact.
Evidence and verification:
[platform destroyer file boundaries](../../prds/steward-platform/research/platform-destroyer-file-boundaries-2026-09-17.md).

The scoped verification also exposed the registry fixture's incomplete
production schema roster: its two blob schema rows lacked admission
provenance. That fixture now derives production attributes canonically and
stores the canonical dependency closure of its synthetic blob declarations,
using checked writes. It keeps its real file-store/branch observations and
introduces no published-root destruction path.
