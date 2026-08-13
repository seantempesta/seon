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
- A named cause with numbers; complete publication is back near its historical
  cost or an owner-ruled accepted budget has the fix-loop default adjusted.
