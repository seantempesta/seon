---
type: decision
status: abandoned
date: 2026-03-05
tags: [decision, architecture, archive, database]
---

# ADR-001: Nippy for inter-JVM serialization

This decision is superseded. It governed the removed inter-JVM flow harness,
not the active database protocol. Nippy may remain a private Konserve encoding
detail, but it is not a Seon wire contract.

The active decision is [[architecture/decisions/008-database-protocol]]. The
historical implementation remains recoverable from Git at
`runtime-reliability-pre-refactor-2026-07-13`.
