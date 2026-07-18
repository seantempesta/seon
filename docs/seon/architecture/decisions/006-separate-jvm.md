---
type: decision
status: abandoned
date: 2026-02-14
tags: [decision, architecture, archive, agent]
---

# ADR-006: Separate JVM processes for agent isolation

This decision is superseded. Seon does not run one JVM, nREPL, Malli registry,
or database connection per agent. The local system is one JVM database server
plus one CLJS pod. Authored code runs in one disposable Bun execution child per
agent through the pod's data-only execution contract.

The parent execution host observes process exit and enforces each invocation's
deadline; replacement reconstructs the current database program in a fresh
child. A future container or microVM backend must preserve that same data-only
execution contract and cannot create another database or function surface.
Historical details remain recoverable from Git at
`runtime-reliability-pre-refactor-2026-07-13`.
