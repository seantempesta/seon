---
type: orchestrator
status: active
tags: [orchestrator, prd, component, cljs, flow]
---

# Independent downstream distribution — working context

This PRD owns the released producer/consumer boundary that lets ACME or another
downstream product build, customize, and operate Seon without access to a Seon
source checkout. Read [[roadmap]] first, then the completed distribution audit.

Architecture remains target truth. This folder owns current implementation
state, the dependency ledger, detailed release/package research, acceptance
evidence, and migration order. Do not move those details back into architecture
or the high-level runtime-reliability roadmap.

Before implementation, identify and read the exact pinned sources for Clojure,
ClojureScript, Shadow, tools.build, Babashka, Node/npm packaging, Datahike,
Konserve, superv.async, partial-cps, and the license/SBOM tooling selected by the
release. Record versions, SHAs, `reference-code/` paths, and idiomatic source
call sites in this PRD. If an exact source is absent, mirror it before planning.

ACME is the acceptance fixture, not a hard-coded production flavor. The source
repository produces immutable writer, runtime, SDK, operator, compatibility,
and license artifacts. A downstream repository owns only its source,
dependencies, config delta, routes/renderers, branding, and deployment policy.

Preserve one operator and one process graph. Development projects watcher +
writer + pod; a package projects writer + pod. Do not create a package-only
operator, second config authority, compatibility namespace, or runtime that
silently recompiles or selects latest dependencies.

The active ACME agent worktree is separately owned. Do not edit, stop, reset,
clean, stage, or merge it until its owner hands back commits and evidence.
