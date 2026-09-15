---
type: issue
status: superseded
severity: blocker
tags: [issue, operator, runtime, wave/per-cluster-live-graph]
---

# Development adoption refuses cohosted clusters

## Problem

The owner requires default to adopt edits while another cluster in the same
JVM retains its program. `seon.cluster/refresh-source!` explicitly requires
exactly one running instance. Starting beta therefore disables the default
edit hook.

## Evidence

Observed 2026-09-08 on PID 14049: `bin/seon status` reported default and beta
alive in that JVM. `bin/seon init --dev default --changed
src/seon/operator.clj` exited 1 with `:seon.boot/refused`, message
`Development updates require their own running JVM.` The edit hook reproduced
the same refusal when adding `test/seon/concurrency_test.clj`.

The deciding condition is `src/seon/cluster.clj:2024`. Adoption also reloads
host JVM Vars and instruments them, so simply deleting the count condition
does not establish that beta continues executing its previous program.

## Owner

The development refresh path in `src/seon/cluster.clj`, held by another lane
during this observation. No production hunk was applied. A safe replacement
needs a stated guarantee for shared JVM Vars, beyond separate SCI env atoms.

## Acceptance

Two live clusters in one JVM; adopt changed behavior onto default; default's
program and page advance; beta's program facts, executed behavior, and served
page retain the prior program. Observe both, not merely distinct commit IDs.

See [the partial landing evidence](../../../prds/context-generation/research/multi-cluster-concurrency-landing-2026-09-08.md).

## Resolution (2026-09-15 triage)

Basis: `7e35df2131c71f476a85c6a38bfc8eb292cb36f5` (committed source).

Commit `4bd2116a2` removes the exactly-one-running-instance refusal. HEAD `src/seon/cluster.clj:2031` requires only that the named development cluster be running. The broader acceptance claim that beta's executed host behavior remains old is not established: shared host Vars remain. That residual belongs to [development-adoption-can-mix-host-and-sci-generations](../development-adoption-can-mix-host-and-sci-generations.md), whose triage records the current reload/acquisition seam and required concurrency proof. Verified by `git log -S 'Development updates require their own running JVM.' -- src/seon/cluster.clj` and the removing diff.

surface: adoption-publication
