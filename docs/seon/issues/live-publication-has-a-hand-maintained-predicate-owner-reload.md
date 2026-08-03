---
type: issue
status: open
severity: friction
tags: [issue, architecture, schema, operator]
---

# Derive predicate-owner readiness before live source publication

## Problem

Live source publication reloads selected namespaces in
`seon.fresh-operator/init-form`. Moving a load-time
`register-core-predicate!` call to another namespace can therefore leave an
already-running JVM with the new schema declaration but without its predicate
registration. The immediate `seon.db` ordering repair remains an explicit
reload and will not automatically follow the next owner move.

## Evidence

Commit `7661c0214` moved `seon.db/connection?` and
`seon.db/database-value?` plus their registrations into `seon.db`. A JVM that
had loaded the previous namespace retained no registrations for those symbols;
`requiring-resolve` returned the already-loaded namespace without replaying its
top-level forms. Reloading `seon.schema.edn` then refused
`:seon.db/connection` as an unregistered predicate.

`test/seon/dev/fresh_operator_test.clj` now reproduces that state in a private
long-lived operator JVM and proves the specific ordering repair. The general
reload set is not safely derivable from schema symbols alone: the population
also references `seon.cluster/socket-server?`, while loading `seon.cluster`
activates the schema population. The current program graph is built after this
gate and does not describe top-level registration or activation effects.

The same hand-maintained reload boundary caused a second incident on
2026-08-03. `seon.program` gained ownership of `:seon.fn/calls`, but live
publication reloaded `seon.fn` without reloading `seon.program`. Its stale
`canonical-row` Var removed the newly analyzed call edges, so the capability
index refused `my.edit/exact` as `:capability-without-request` even though the
fresh analyzer row contained a direct `seon.effect/request!` call. Commit
`be232fa32` reloads `seon.program` before `seon.fn` and extends the existing
live-operator regression by deliberately installing a call-edge-dropping
`canonical-row` before `bin/seon init`. Publication then succeeded as
`:current-src` commit `6a70d410-a771-585a-bee5-d2c88e2f909c` with digest
`804b096f7733a36015a5562c483be70870375345607904fb740e6e66e5c505e9`.

## Owner

The source-publication reload boundary in `seon.fresh-operator`, together with
core-predicate registration provenance in `seon.schema`.

## Acceptance

Derive cycle-safe predicate-owner readiness from first-class registration and
load-effect provenance, or eliminate load-time registration as a publication
prerequisite. A recurring test moves a predicate registration between two
already-loaded namespaces without editing an operator reload list and live
publication still succeeds. Missing readiness must name both the predicate and
the namespace that must load or reload.
