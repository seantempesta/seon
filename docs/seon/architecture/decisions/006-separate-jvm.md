---
type: decision
status: abandoned
date: 2026-02-14
tags: [decision, architecture, archive, agent]
---

# ADR-006: Separate JVM processes for agent isolation

This decision is superseded by [[009-claimant-process-topology]]. Seon does not
run one full application, database, nREPL, or mutable registry per agent.
Replaceable claimant JVMs instead compete for database-backed run claims and
execute one or more held runs with virtual threads.

Process count is capacity, not identity. Claim epochs, turn phases, and receipts
preserve authority and recovery across claimant replacement. A future container
or microVM claimant preserves that same data contract and cannot create another
database or capability surface. Historical details remain recoverable from Git.
