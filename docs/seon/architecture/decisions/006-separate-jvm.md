---
type: decision
status: abandoned
date: 2026-02-14
tags: [decision, architecture, archive, agent]
---

# ADR-006: Separate JVM processes for agent isolation

This decision is superseded by [[009-cluster-jvm-topology]]. Seon does not run
one full application, database, nREPL, or mutable registry per agent. One
cluster JVM per store executes all agents through database-backed run claims
and one virtual thread per held run.

Process count is capacity, not identity. Claim epochs, turn phases, and receipts
preserve authority and recovery across cluster JVM replacement. Scale comes
from adding isolated clusters, never another writer or agent process to one
store. Historical details remain recoverable from Git.
