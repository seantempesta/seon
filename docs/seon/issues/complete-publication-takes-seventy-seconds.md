---
type: issue
status: open
severity: friction
tags: [issue, operator, database, class/n9, wave/publication-velocity]
---

# Complete source publication takes ~70 s against the ten-second law

## Problem

`seon.cluster/refresh-source!` (the complete `current-src` publication) took
70.2 s over the live prepl on 2026-08-06 (orchestrator probe, RUNNABLE in
malli var registration throughout) and drove the fresh-operator multi-init
lifecycle test into its 300 s liveness backstop (gate-fix-operator lane, same
day). The 2026-08-05 working edge recorded reset→republish→refork→READY at
2.7 s — either that measured a different operation (incremental? warm path?)
or publication genuinely regressed ~25×. Every init, reset, and fresh-boot
cycle pays this, taxing every fix loop (velocity outranks the queue).

## Needed

Attribution first: profile one complete publication (analysis, schema
population, malli registration, transaction batches) and name where the time
goes; compare against the 08-05 measurement's actual scope. Then either fix
the regression at cause or record the honest cost and revisit the
incremental path (`bin/seon init --changed`) as the default fix-loop surface.
Publication progress events (landed with the init-silence fix `b465b4613`)
give per-phase timestamps for free — use them.

The 2026-08-10 attribution is now recorded in
[the suite fat-tail report](../../prds/sci-execution-runtime/research/suite-fat-tail-2026-08-10.md).
On the current tree, namespace load took 11.334 s and the following complete
refresh took 45.079 s. Program rows consumed 34.781 s: declarations alone
took 22.786 s, keywords 3.770 s, and calls 4.351 s.

The progress callback is not observational today. Its presence divides every
ordered program phase into up to six writer transactions. A direct falsifier
using the same population with one transaction per phase reduced program rows
to 27.143 s and complete refresh to 36.487 s. Separately, the listed suite tail
contains at least 22 complete populations because tests and operator commands
rebuild private roots instead of forking one immutable source base. Fix both
classes: progress must not change transaction mechanics, and the owner-ruled
`source-base!`/`start-fork!` path must make repeated population structurally
unavailable to ordinary tests.

## Acceptance

- Progress and no-progress publication issue identical writer transaction
  shapes.
- One suite source digest produces exactly one base-build event; ordinary
test forks cannot invoke complete population.

## Re-observed in the 2026-08-13 boot gate

`bin/test seon.cluster.boot-test` measured
`incremental-source-refresh-publishes-without-touching-existing-clusters` at
156.980 seconds before advancing normally to the next test. This is the same
known complete-publication class, not the co-hosted-second-boot stall: the test
is declared long because complete incremental publication dominates, and it
then proves an existing cluster remains sovereign while a later cluster forks
the published commit (`test/seon/cluster/boot_test.clj:932-985`). The 2026-08-11
fat-tail measurement was 186.733 seconds for that same boundary, so the new
sample is faster but still violates the fast-by-default law by more than an
order of magnitude.
- A named cause with numbers; complete publication is back near its historical
  cost or an owner-ruled accepted budget has the fix-loop default adjusted.

## Re-profiled 2026-09-05

A fresh private operator root took 68.36 s real before the indexing repair.
Phase instrumentation found 56.323 s inside publication: clj-kondo plus source
row construction took 7.103 s, schema population took 6.023 s, and the five
program-graph writer phases took 39.096 s (namespaces 3.157 s, declarations
23.237 s, keywords 4.323 s, calls 8.379 s). Contract projection and per-row
contract derivation together took 1.235 s. The analyzed input is exactly the
236 `.clj`/`.cljc` files under `src/` and `test/` (5,077,708 bytes on this
checkout); `reference-code/` is not analyzed, and clj-kondo used the existing
19 MiB `.clj-kondo/.cache`.

The 2026-09-05 indexing repair reads and line-indexes each source file once,
parses each namespace form once per population, and compiles all program refs
to transaction-local tempids. That replaced five dependent program commits
with one. The matched instrumented publication fell from 56.323 s to 38.415 s;
the program boundary became 4.030 s of transaction compilation plus one
26.025 s Datahike commit for 207,915 datoms. The full cold command improved
only from 68.36 s to 63.29 s real, so this issue remains open: the irreducible
observed floor is now the real 208k-datom database population plus roughly
25 s of cold operator/source-JVM loading, not repeated per-row source reads or
per-phase commits. Reaching the target requires reducing the admitted fact
population or accelerating the Datahike bulk-load/store boundary without
weakening durability or program-graph queryability.
