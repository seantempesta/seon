---
type: plan
status: active (owner-approved 2026-09-22; supersedes publication-dissolution-spec-2026-09-20.md)
created: 2026-09-22
tags: [plan, publication, adoption, operator, one-jvm, measurement]
---

# One JVM, index once, then incremental — the publication redesign

Owner, 2026-09-22: "It was always supposed to be a single JVM and we pay
the cost of startup once. We pay the cost of indexing once and then it's
incremental. Stop fighting the tools they are already optimized." And:
"start making tests fail if they exceed reasonable time limits. None of
this shit should require minutes of computation."

## The invariant (the only thing a slice is judged by)

A cold start pays JVM boot and one complete analysis. After that, an edit
costs work proportional to the changed declarations and their callers, and
a request with nothing changed is a comparison of two commit ids. The
measurement is
[measure-publication-path-2026-09-22.sh](../research/measure-publication-path-2026-09-22.sh);
the orchestrator runs it before and after every slice and records the row.

Measured at HEAD `7924f4dae` before this plan (isolated root, hook command):

| Case | Now | Target |
|---|---:|---:|
| From zero, complete publication | 175 s | ≤ 60 s (one analysis ≈ 10 s + one population) |
| Fork a cluster | 29 s (two JVM boots) | < 1 s (a Datahike branch) |
| Boot to ready | 43 s | measured, paid once |
| No change at all | 149 s | < 1 s (two commit ids compared) |
| Docstring edit, non-core file | 483 s | ≤ 5 s |
| Docstring edit, core file | 419 s | ≤ 5 s (no "toolchain" class) |

## The seams we build ON (read before editing; cite when landing)

- **The running JVM's prepl.** Every cluster advertises an io-prepl at
  boot (`src/seon/cluster.clj:5`, port in the advertisement `:3869`); the
  operator already has the client (`script/seon/fresh_operator.clj:1819`
  `prepl-eval!`, replies `:1172`, silence bound `:1835`).
- **clj-kondo's own namespace cache.** `seon.fn.analyzer` already calls
  `clj-kondo.core/run!` with `:cache-dir` (`src/seon/fn/analyzer.clj:264–272`);
  clj-kondo reuses unchanged namespaces itself
  (`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj`). A complete
  analysis of 381 files measured 10.2 s.
- **Digests already stored on program rows** (`:seon.fn/digest`, per-file
  digests in the manifest artifact and `:seon.source/relative-file-digests`):
  the changed set is a set difference, not a second cache.
- **Datahike's transaction report** (`:tx-data` of the publication
  transaction) names every changed identity; the cluster row stores
  `:seon.source/commit-id`.
- **Clojure's `require :reload`** in the existing `reload-order`
  (`src/seon/cluster.clj:2428` `load-development-definitions!`), measured
  10–140 ms per namespace.
- **The runner's declared bound** (`:seon.test/long` / `:seon.test/long-ms`,
  `src/seon/test/runner.clj:386`, `:754`; `seon.test-support/event-backstop-seconds`).

## Status — 2026-09-23 ~20:30 (written to disk before an orchestrator compaction)

| Slice | State | Evidence |
|---|---|---|
| 0 (loaded-producer guard) | landed | `cd701afc2` |
| 1 (one JVM, prepl requests) | landed; fork 27 s → 0.34 s | `0c6be06f3`, `5cf44da20`; script row |
| 2 (clj-kondo cache only) | landed; platform tier green | `c402d3c1d` |
| 3 (transact the difference) | landed; no-change 149 s → 1.46 s; first adoption 1.5 s | `8de7a8868`, `e71e0e4b6` |
| 4 (adoption from the report) | OPEN | `245f693f6`, `3c0b47dfe` (roster gone), `064b07fde`, `b17ad6ef1`, `3b73038f2` (note paths), `430fc91a1` (SCI on first use), `3a27e58f2` (publication serialized), `f6a463de6` (lazy agent graphs, boot 24.0 → 16.2 s), `d726468e0` (validator reads touched rows, 849/518 → 77/8 ms). One-file docstring edit on the lane's root: 12.7 s → 7.14 s → 5.42 s; target under 1 s. |
| 5 (bounded everything) | landed in the test system | `6ee74f025` (bound as failure), `cec1b6782` (bare gate), `8aca66774` (fixture fork) |

**Slice 4 remaining items, in order (each a deletion or an O(edit) rewrite), with the spans they answer** (`research/one-jvm-lazy-boot-after-2026-09-23.edn`): (2) every publication input is a file row carrying its digest, request hashes only `:seon.source/changed-paths` — "source build" 909–1006 ms (ruling: option 1 of `docs/seon/issues/publication-input-digests-are-not-all-database-facts.md`; analysis membership derived from program-graph file references, merged schema declaration derived not stored, aggregate `:seon.source/test-input-digest` deleted when unread); (4) post-adoption "development source verification" 523–546 ms → a commit-id compare; (5) "published manifest read" 142 ms + "validation" 278 ms deleted (the database is the manifest); (6) "analysis caller files" 278 ms → callers re-linted only when a changed declaration's contract digest changed; (8) only changed namespaces reloaded, wrappers re-armed only where the contract digest differs (`src/seon/instrument.clj:593`) — the 36 s multi-file convergence; config: reconciliation transacts the difference between the manifest's facts and the current database value, empty difference = no transaction, initialization compared the same way, the stored applied-manifest digest deleted when unread (`docs/seon/issues/config-dial-digest-cannot-prove-initialization-is-unchanged.md`, option 1).

**Landed means** (unchanged): the script row moves (`research/measure-publication-path-2026-09-22.sh`, orchestrator, worktree at the commit) and the platform tier is green.

**Adjacent, same path:** `:seon.schema.shape/form` rows store the fully expanded Malli form (65 MB, largest 1.4 MB) and Datahike's count-bounded node cache multiplied them to 4 GB live in `default`; lane `schema-shape-authored` stores the authored form with references as keywords (`docs/seon/issues/the-index-cache-retains-4gb-of-expanded-schema-shape-forms.md`). Reset required when it lands.

## The slices, in order (one lane, astra high; each measured before the next)

1. **One JVM.** `bin/seon` and the edit hook send every request to the
   running cluster's prepl and print its reply; a child JVM boots only
   when no cluster is running (cold start, reset). DELETE: the relay child
   JVM (`init`, `init --dev`, `init --changed`), the publish-before-fork
   JVM. Fork = `init NAME` over the prepl = a Datahike branch.
2. **No double caching: clj-kondo's cache is the ONLY analysis cache**
   (owner, 2026-09-22: "No double caching. Use all the existing tool
   caches."). An edit lints the changed files, and the files of their
   callers for findings (callers come from the program graph's
   `:seon.fn/calls` reverse edges), with `:cache true` so clj-kondo
   supplies every other namespace from its own cache; the complete
   analysis (10.2 s) is the cold case only. Target for one file: ≤ 2 s.
   DELETE: our layer on top of it — the `reusable?` branch of
   `seon.fn/build-manifest` (`src/seon/fn.clj:2369–2420`: forget-namespaces,
   known-symbol sets, `publication-inputs`, `cached-analysis` reads of the
   44 MB `build/analysis` cache — that layer, not clj-kondo, is the 277 s),
   `seon.fn/toolchain-digest` and `producer-paths` (the 36-namespace
   closure). The analyzer's own namespaces changing is an ordinary changed
   namespace; a changed analysis output SHAPE is a reset (database data is
   disposable by ruling).
3. **Publication transacts the difference.** Changed files = digest
   difference between the new manifest and the stored rows; changed rows =
   their declarations; transact only those (the existing population path
   for N inputs). Unchanged digest → return the stored commit, no transaction.
4. **Adoption derives from the report.** Stored commit = published commit
   → "converged", nothing runs. Else: the report's changed identities →
   reload those namespaces + dependents, re-arm only wrappers whose contract
   digest changed (`src/seon/instrument.clj:593` already compares), regenerate
   the SCI base from the database value. DELETE: "published rows read",
   "changed definition comparison" and "issue reconciliation" over the whole
   program, the same-call-only `scalar?` path, the loaded-producer guard's
   aggregate digest (a namespace reload IS the transition).
5. **Bounded everything.** Every test has a bound and FAILS when it
   exceeds it: a default per-test bound declared once in
   `resources/seon/schemas/seon.test.edn` (proposed 5 s), enforced by the
   runner as a failure with the measured time; `:seon.test/long-ms` is the
   only way to declare more and must carry its reason. The publication
   phases carry the same kind of declared bound, and the from-zero boot +
   one-file edit is a platform test asserting the target table above.

## What this deletes and what it never adds

Deleted: relay JVMs, the incremental analyzer, the toolchain digest and
producer closure, the whole-program adoption diff, the loaded-producer
generation guard. Never added: a second cache, a second analysis path, a
tuned timeout without a declared bound, a new noun.

## Owned by

The orchestrator measures; one lane implements slices 1–5 in order;
bridge step 2 lands its identity slice first so the shared tree boots;
every other lane stays paused until slice 4 is measured.
