---
type: issue
status: open
severity: friction
tags: [issue, operator, process, database]
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

## Acceptance

A named cause with numbers; complete publication back near its historical
cost or an owner-ruled accepted budget with the fix-loop default adjusted.
