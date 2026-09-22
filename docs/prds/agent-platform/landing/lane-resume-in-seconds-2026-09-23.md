---
type: landing
status: partial — measured end to end; one commit landed in owned files; the remaining fixes are exact patches for their holders
created: 2026-09-23
tags: [agent-platform, boot, resume, dependency-cache, arming, projection, seconds-not-minutes]
---

# Lane resume-in-seconds (2026-09-23)

Owner rulings: "cold boot and index ... not acceptable for resume" and "by design most ops
should be sub second". Plan §7 has the resume row. Every file this lane's fixes touch
except `src/seon/cluster/agent.clj` is held by another lane
(`tmp/orchestrator/file-ownership.md`). Each fix was therefore applied and measured in
this lane's own disposable snapshot. It is handed over below as an exact patch; the
patches are under `tmp/ris-evidence/handoff/`.

## Commits

| commit | paths | what |
|---|---|---|
| `65bf137c4` | `src/seon/cluster/agent.clj`, `test/seon/cluster/release_context_test.clj` | `release-context!` attempts all three steps (connection release, branch unlink, context-state removal). It rethrows the first cause whole, with the later causes as suppressed (review P1-4). |
| `dc89f1d8a` | `test/seon/cluster/release_context_test.clj` | The test's contract uses `:seon.error/throwable` in place of an anonymous `[:fn …]`, which from-zero publication refused (P0 from the orchestrator). |

Proof for `65bf137c4`: the regression's scenarios were run live in the scratch JVM
(`tmp/ris-evidence/release-probe.clj`) against both versions of `release-context!`.

| scenario | HEAD `release-context!` | new `release-context!` |
|---|---|---|
| connection release fails | branch still rostered, context retained (leak) | unlinked, context removed, cause thrown with 0 suppressed |
| release and unlink both fail | context retained | context removed, `[:unlink]` suppressed |
| clean release | — | nothing thrown, unlinked |

HEAD plus the change loads in a fresh JVM:
`(require 'seon.cluster.agent 'seon.cluster.release-context-test)`, 10,117 ms wall.

`bin/test-fast` and `bin/_test-slot` are deleted in the shared working tree (a foreign,
uncommitted deletion). From a HEAD archive, `bin/test --fast` refused "Test recording
requires a published current-src" (`tmp/ris-evidence/tf-release-1.log`, 27.4 s). So the
regression has NOT run through the gate. **Unverified:** the gate run of
`seon.cluster.release-context-test`.

## Method

- Frozen source: `git archive bc8a1fa68` in `tmp/ris-source`. `reference-code` is a
  symlink to the checkout; `target/dev-dependency-classes` is a symlink to the main
  checkout's cache root. Both symlinks are documented here and are never followed by
  recursive deletion.
- Root `tmp/ris-root`, cluster `ris`.
- Phase clocks come from `RIS-PHASE` / `RIS-SUB` printlns added ONLY to the snapshot
  copies of `boot.clj`, `cluster.clj`, `config.clj`, `sci/eval.clj` and `operator.clj`.
  They go to the cluster log (`tmp/ris-evidence/{b3,a2,a3,a4,a5}-phases.txt`).
- Wall times come from `tmp/ris-evidence/timed.sh` (`timings.txt`). Load averages were
  10–15 on 18 cores throughout.
- The working tree could not boot from zero: another lane's hunk makes
  `:seon.fn.file/digest` require 64 characters, and the refusal text reads "expected a
  string, got a string". That attempt exited 1 after 53 s and printed a 30 MB refusal.
  The frozen archive booted.

## Resume TIMINGS, same root, before → after

Before is the `bc8a1fa` snapshot as committed. After is that snapshot with all four
changes below applied. `b3` and `a5` are the same root, `down` then `start`.

| phase | before (b3) ms | after (a5) ms | cause / change |
|---|---|---|---|
| bb + `clojure` CLI + JVM start → launch form | 655 | 805 | JVM start |
| `(require 'seon.cluster.boot)` | **19,186** | **8,001** | (a) dependency classes on the start classpath; the residue is 79 first-party namespaces compiled from source |
| start prelude (bootstrap, config compile) | 677 | 738 | — |
| store acquire, current-src check, open branch | 119 | 118 | — |
| `projection-from-database` (memo cold in a new JVM) | 530 | 516 | whole projection built once per JVM; a hit afterwards |
| `accrete-schema-population!` | **3,240** (`schema-row-changes` 2,819: 3,323 `seon.db/pull`s; `declaration-changes` 40) | **333** | (e) skip when the handed projection IS the branch's own |
| `recover-runs!` | 307 | 402 | data recovery |
| config apply | 534 (reconcile plan 511) | 656 (634) | `reconcile/plan` over the whole config row; converged, no transaction |
| `ensure-cluster-entity!` + `seed-root-agent!` | **1,308** (3 transactions) | 386 (1 transaction) | two unconditional empty transactions removed |
| SCI cluster ctx, environment, work launcher | 24 | 22 | — |
| `arm-agents!` | **8,342** (`load-core-namespaces!` 7,772, 417 ns) | **1,977** (1,193, 107 ns) | (b) arm only non-test namespaces |
| serve | 50 | 22 | — |
| **`:seon.boot/ready-ms`** | **15,091** (b1, uninstrumented) | **5,214** | |
| **wall** | **35,310** (b1 36,584) | **14,468** | |

Readiness names no indexing phase, before or after. `refresh-source!` is skipped
because `current-src` exists (`boot.clj` `(when-not (source/current store) …)`).

Transactions on resume (drill, `tmp/ris-evidence/tx-since-start.clj`):

- Before: 3. One `:seon.db.process/id` row, plus two EMPTY transactions that carry only
  provenance. They come from `seed-root-agent!`'s unconditional `ensure-entity!` and
  `root-maintenance-seed-call`.
- After: 1, the process row for this JVM.

A resume therefore transacts one provenance row, not zero.

Remaining phases over ~500 ms after the changes:

1. **First-party compile, ~7.2 s.** 79 `seon.*` namespaces reached by
   `seon.cluster.boot` are compiled from source on every start. The dependency cache
   excludes first-party code on purpose: `dev_cache.clj` `validate!` rejects first-party
   classes, because `require :reload` would prefer a class file that is newer than its
   dependents' edits.
2. **`load-core-namespaces!`, 1.2 s.** The remaining 26 `src` namespaces, the same
   cause as item 1.
3. **Config `reconcile/plan`, 634 ms.** It plans the whole config row on every start,
   even when converged.
4. **`projection-from-database`, 516 ms.** The first build in a new JVM; the memo is
   per process.
5. **Start prelude, 738 ms.**
6. **The process-row transaction, ~386 ms** cold, for the first write of the process.

## Findings and exact changes, per holder

### (a) Dependency class cache on every start classpath (nuke-is-total: `script/seon/operator.clj`, `script/seon/dev/dependency_digest.clj`)

- Patches: `tmp/ris-evidence/handoff/operator.patch` (against HEAD `00d50f31b`) and
  `dependency_digest.patch`.
- The operator (babashka) reads the child JVM's own runtime identity with
  `java -XshowSettings:properties -version` (28 ms). It computes
  `configuration-digest source properties`, a new two-arity in the one digest owner.
  That value equals `dev_cache.clj`'s key: the bb value reproduced manifest input digest
  `59fee5a7…` for `6bf3bde78`'s pins.
- It then selects a valid immutable directory under the MAIN checkout's
  `target/dev-dependency-classes`. A directory is valid when its version, name, input
  digest and every loader class match.
- On a hit it launches `clojure -Scp <classes>:<clojure -Spath -M:dev:test> -M:dev:test`.
  This is the same mechanism `bin/test` uses, and it avoids the "external :paths"
  deprecation warning that `-Sdeps :extra-paths` prints.
- It records a `target/dev-dependency-cache-processes/<pid>.edn` reference under the
  reference lock. Babashka exposes no FileLock methods, so closing the channel releases
  the lock.
- It adds `:seon.dev-cache/dependency-classes {:status :hit|:miss …}` to the start
  value and prints a `DEPENDENCY-CLASSES` line. A miss names its reason and the fill
  command; it never builds a cache on the start path.
- Because `repository-root` is always the checkout running `bin/seon`, nuke archives
  (`<root>/data/source/<sha>`) share the checkout's cache.
- Measured (a4): selection 58 ms, hit on `42d5d09b…`; `require` 19,186 → 8,001 ms.
- Before this lane rebuilt the cache, every existing cache was stale: the datahike pin
  advanced after `6bf3bde78`, so the correct answer was a miss. The rebuild ran
  `clojure -T:dev-cache ensure-cache` from a HEAD archive: **47,944 ms, a defect over
  10 s**, paid once per dependency change.
- Rebuilding in the main tree failed. Discovery loads first-party `seon.artifact`, and
  the working tree held a deliberately broken `boot.clj` drill hunk
  (`tmp/ris-evidence/ensure-cache-head.log`). The dependency-cache build should not
  depend on first-party working-tree health.
- `bin/test-fast` at HEAD runs `clojure -M:test` with no class cache. The same
  `-Scp <classes>:<-Spath>` prefix applies there. The file is currently deleted in the
  working tree, so it was not patched.

### (b) Arm only the running program's namespaces (realities-commit-4: `src/seon/sci/eval.clj`)

- Patch: `tmp/ris-evidence/handoff/eval.patch`.
- `load-core-namespaces!` excludes namespaces whose file has
  `:seon.fn.file/relative-root "test"`. That fact is already used by
  `program.cljc:111` and `test.clj:686`.
- Test namespaces load on the test request through `seon.test/resolve-test`'s
  `requiring-resolve` (`test.clj:1182`).
- The patch also stops treating a refused `db/q` as data. HEAD `doseq`s over the error
  map and throws `ClassCastException: MapEntry cannot be cast to Symbol`, which was
  observed in run a1.
- Clause order matters. A `not-join` refuses with "Insufficient bindings". The
  `?file`-first form takes 49 ms, against 31 ms for HEAD's query.
- Measured: 417 → 107 namespaces; 7,772 → 1,193 ms.
- Risk: an SCI evaluation inside a test namespace that the runner has not yet loaded
  binds nothing for it. The runner loads the namespace under test before acquisition.

### (c) Unchanged-program resume (nuke-is-total: `src/seon/cluster/boot.clj`)

Resume does no indexing, but only because it never compares at all.
`(when-not (source/current store) (cluster/refresh-source! …))` skips even when files
changed while the JVM was down. The cluster then runs stale program rows until a hook
publication.

The measured check is `tmp/ris-evidence/program-equals-files.clj`:
`source/discover-paths` (48 ms) + `path-digests` (74 ms) + `stored-path-digests`
(330 ms, one `seon.db/pull` per path; one query would do). It found exactly this
lane's five instrumented files. Exact change: at resume, compute the changed paths and
call `refresh-source!` with only those; call nothing when there are none.

### (d) Projection at resume

After the first build, the projection is read through the memo from `9b8c5b405`
(`carried-projection` of a committed value, 0 ms). `boot.clj` builds it once per JVM
(516 ms) and does not rebuild it: the second build is skipped when `basis-t` is
unchanged. The writer-side re-derivation for speculative values is item (f).

### (e) Schema diff on a converged reopen (schema-changes-in-place: `src/seon/cluster.clj`) — measured, not edited

- On resume, the projection handed to `accrete-schema-population!` is the branch's own
  `carried-projection`: `identical?` was true, observed. Both diffs therefore compare
  the branch with itself.
- `declaration-changes` costs 25–47 ms. `schema-row-changes` costs **2,819–4,169 ms**:
  3,323 `seon.db/pull`s at about 0.9 ms each. `d/pull-many '[*]` of the same entities
  takes 50 ms.
- Cheap rule: when `(identical? projection (db/carried-projection (db/db connection)))`,
  return without diffing (a5: 3,240 → 333 ms).
- Generally, replace the per-row pulls with one batched read.
- The packaged-forms projection's fingerprint differs from the branch's, so a
  fingerprint comparison is not a usable rule.

### Empty transactions on every resume (schema-changes-in-place: `cluster.clj` `seed-root-agent!`)

- Both transaction functions return no data on an existing root, yet each commits. One
  seon empty transaction costs 33–70 ms warm and ~330–600 ms cold on the first writes
  of a boot.
- Fix option 1: a boot-time pre-read. It is legitimate here because root is never
  retracted and no other writer runs before arm.
- Fix option 2: the datahike fork declines to commit an empty expansion.

### (f) The P0 warm-restart hang (#17a)

The cause is written into
`docs/seon/issues/warm-restart-hangs-in-agent-arm-waiting-on-an-atom-monitor.md`.

- `main` waits on `routing`, which the armer proc's `arm!` holds (a virtual thread).
- Under that monitor, the SCI `acquire!` records each acquisition refusal as its own
  `commit-fault!` transaction.
- Each `seon.error/commit-call` then re-derives the whole projection on the Datahike
  writer, because a `:db.fn/call` value has no committed identity. Measured: 164 ms per
  call on this store.
- Owners: `db.clj` (projection-writer-producer) and `sci/eval.clj` (realities-commit-4,
  which should record the batch as one transaction). Nothing in `agent.clj` changes the
  latency, because any second arming caller needs the same armed entry.

### Linked caches (owner, same day: "link the caches")

- **Dependency classes:** item (a).
- **clj-kondo cache:** handed to lane publication-work. Suggested design: in
  `discard-obsolete-cache-entries!`, drop a checkout-owned entry whose source file is
  newer than the entry. Snapshots link `.clj-kondo/.cache` to the main checkout, as
  `bin/test` links `target/`. A publication JVM already shares it: its working
  directory is the checkout.
- **Per-file analysis reuse:** needs an owner decision. Three options follow; not built.
  1. **Simplest, RECOMMENDED: seed a fresh root's store from an existing published
     store**, using the existing published-base mechanism
     (`target/test-published-bases/<digest>`, `seon.cluster.export`). A new root then
     publishes only the files whose digests differ, through the incremental path.
     - Guarantee: the same rows as the files, because the incremental publication is
       the canonical path.
     - Cost: a store copy (166 MB) and one publication of the difference.
     - Gives up: store-level history from the seed.
  2. **A content-addressed per-file analysis cache** of clj-kondo output keyed by file
     digest.
     - Saves the ~18 s of analysis.
     - Cost: population (8.5 s) and whole-program validation remain. It is a new cache
       beside the store.
  3. **Keep from-zero only for nuke**, and make reset a fresh branch (plan §7).
     - Cost: none.
     - Gives up: fast fresh roots.

## TIMINGS (every operation over 1 s)

| operation | wall ms | note |
|---|---|---|
| from-zero `start` of the working tree (setup attempt) | ~53,000 | exit 1: foreign schema hunk; **defect: 30 MB refusal, "expected a string, got a string"** |
| from-zero `start`, archive `bc8a1fa68` (setup, once) | **118,457** (ready 93,723) | **>10 s defect**; the from-zero-boot issue class |
| resume b1 (clean snapshot) | 36,584 (ready 15,091) | before |
| resume b2 / b3 (instrumented) | 46,355 / 35,310 | b2 config apply took 15,697 ms once and did not recur (unexplained, load 11) |
| resume a1 | 30,935, exit 1 | `not-join` refusal + unchecked `db/q` error map |
| resume a2 (arm filter) | 34,632 | |
| resume a3 (static cache + arm) | 18,307 | |
| resume a4 (operator cache selection + arm) | 19,432 | |
| resume a5 (a + b + e + empty-transaction skip) | **14,468 (ready 5,214)** | after |
| `down` | 774–1,160 | |
| `clojure -T:dev-cache ensure-cache`, main tree | 20,300–20,580 | failed: discovery loads the working tree |
| `clojure -T:dev-cache ensure-cache`, HEAD archive | **47,944** | **>10 s defect**; rebuild per dependency change |
| `require seon.cluster.boot`, source / cached | 20,400 / 9,200 | fresh JVM probe |
| `bin/test --fast` from a HEAD archive | 27,383, exit 1 | published current-src refusal (test infra, foreign) |
| HEAD + `agent.clj` load check | 10,117 | |
| release probes (3 isolated acquisitions) | 4,816 / 3,482 | **isolated `acquire-context!` ≈ 1.6 s each** |
| `schema-row-changes` alone | 3,063 | (e) |
| program-equals-files check | 459 | (c) |

## Verification limits

- The after-numbers are for patched snapshot copies of held files. HEAD does not yet
  have (a), (b), (c) or (e).
- The regression has not run through `bin/test`.
- Instrumentation in the snapshot added one `stale-vars` problem entry
  (`seon.cluster.boot/tick!`).
- No `default` was touched. RESET NEEDED: no.
