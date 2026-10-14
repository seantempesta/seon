---
title: "Card TASK-CHECK: Ingestion reports what makes a task unworkable"
type: reference
status: superseded
created: 2026-10-08
---

# TASK-CHECK

Superseded by [TASK-CHAIN](TASK-CHAIN.md). The card pulled `intent`, `must-read` and `evidence` and counted tagged
proofs; those members and the tags are deleted (TASK-CHAIN §1, §6). What survives is reported where it is written:
`my.task/update!` answers each reference in a text member that does not resolve as `:my.task/problems` (TASK-CHAIN
§3a), `seon.ref/references` is the one resolver (§3), and a task is done by `seon.cluster.source/done-refusals` (§2.4),
not by a check at ingestion.
