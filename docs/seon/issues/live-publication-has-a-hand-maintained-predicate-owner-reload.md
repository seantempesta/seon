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
