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
| Docstring edit, non-core file | 483 s | ≤ 15 s |
| Docstring edit, core file | 419 s | ≤ 15 s (no "toolchain" class) |

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

## The slices, in order (one lane, astra high; each measured before the next)

1. **One JVM.** `bin/seon` and the edit hook send every request to the
   running cluster's prepl and print its reply; a child JVM boots only
   when no cluster is running (cold start, reset). DELETE: the relay child
   JVM (`init`, `init --dev`, `init --changed`), the publish-before-fork
   JVM. Fork = `init NAME` over the prepl = a Datahike branch.
2. **Analysis is complete, always.** DELETE: the `reusable?` branch of
   `seon.fn/build-manifest` (`src/seon/fn.clj:2369–2420`), the per-file
   cache under `build/analysis`, `seon.fn/toolchain-digest` and
   `producer-paths` (the 36-namespace closure). The analyzer's own
   namespaces changing is an ordinary changed namespace; a changed analysis
   output SHAPE is a reset (database data is disposable by ruling).
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
