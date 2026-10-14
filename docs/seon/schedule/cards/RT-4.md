---
title: "Card RT-4 A task names proofs in its data"
type: reference
status: superseded
created: 2026-10-08
---

# RT-4 A task names proofs in its data

Superseded by [TASK-CHAIN](TASK-CHAIN.md) §1 and §6: proofs are the field `:seon.task/proofs` (a set of qualified
deftest symbols), the `:seon/tasks` tags are deleted, and `seon.task/task-edges` goes with them (S2c). A named proof
that resolves to no deftest is reported where the task is written (TASK-CHAIN §3), not by a separate ingestion check.
