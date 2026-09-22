---
type: issue
status: open
severity: friction
created: 2026-09-22
tags: [boot, publication, performance, seconds-not-minutes]
---

# A from-zero boot takes minutes; the law is seconds

## Problem

`bin/seon --root tmp/<fresh-empty-root> start <cluster>` on committed HEAD reaches
readiness in 90–180 s; a warm restart of the same root takes about 5.5 s. AGENTS.md
("Seconds, not minutes") requires owner authorization for any work over ten seconds,
boot and initial indexing included, and the owner's framing of the refactor
(2026-09-23) is that by design most operations are sub-second: every phase that is
not is a question of what to delete, not what to tune.

`:seon.boot/ready-ms` is measured from `seon.cluster.boot/start!` entry
(`src/seon/cluster/boot.clj:223,255`); it excludes JVM start and
`(require 'seon.cluster.boot)` (`script/seon/operator.clj:290`), which only the
launcher's wall time sees.

The phase breakdown and the ranked options are in
[from-zero-boot-cost-2026-09-23](../../research/agent-platform/from-zero-boot-cost-2026-09-23.md)
(in progress).

## Timing rows

Append one dated row per observed boot. `ready-ms` is the boot's own value; wall is
the launching shell's; load is `uptime`'s one-minute average at start.

| date | subject (commit) | kind | ready-ms | wall s | load | source |
|---|---|---|---|---|---|---|
| 2026-09-22 | `a614fb898` frozen archive | from-zero | 145,701 | 160.44 | — | [lane-entrance-supplier](../../prds/agent-platform/landing/lane-entrance-supplier-2026-09-23.md) |
| 2026-09-22 | earlier lane boots (recorded by the orchestrator) | from-zero | 112,209 · 152,951 · 180,675 · 96,964 · 89,423 | — | — | orchestrator report |
| 2026-09-22 | typical | warm restart | ~5,500 | — | — | orchestrator report |
| 2026-09-22 | `b51a24055` frozen archive (`git archive HEAD`) | from-zero | 179,252 | 199.19 | — | [lane-three-way-comparison](../../prds/agent-platform/landing/lane-three-way-comparison-2026-09-22.md) |
| 2026-09-22 | `bfe3445f8` archive + projection-writer-producer patch | from-zero | 131,170 | 148.98 | — | [lane-projection-writer-producer](../../prds/agent-platform/landing/lane-projection-writer-producer-2026-09-23.md) |
| 2026-09-22 | working tree (pre-fix memo; refused at scratch schema transaction) | from-zero, exit 1 | — | 38.69 | — | same |
| 2026-09-22 | working tree (foreign `:pos-int` contract in `src/seon/test.clj`; refused) | from-zero, exit 1 | — | 41.29 | — | same |
