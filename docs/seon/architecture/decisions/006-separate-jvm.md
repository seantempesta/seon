---
type: decision
status: abandoned
date: 2026-02-14
tags: [decision, architecture, archive, agent]
---

# ADR-006: Separate JVM processes for agent isolation

This decision is superseded. Seon does not run one JVM, nREPL, Malli registry,
or database connection per agent. The local system is one JVM database server
plus one CLJS pod. Agent code uses the pod's one SCI execution-service contract.

Stronger fault/resource isolation remains a separate execution-isolation PRD.
A future worker or microVM backend must preserve the same data-only execution
contract and cannot create another database or function surface. Historical
details remain recoverable from Git at
`runtime-reliability-pre-refactor-2026-07-13`.
