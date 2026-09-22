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
(2026-09-23). Measured on frozen HEAD `bfe3445f8`: about 125 s of the 170 s is Seon's
report validator on the one 441,788-datom program transaction. `write-owned-values-error`
rescans the whole `:tx-data` once per owning root (`src/seon/db.clj:3163-3172`), which
is quadratic. The JVM compiles every dependency from source on every start (17–19 s,
not counted in ready-ms), while the class cache `bin/test` already uses would cut that
to 7.9 s. Reset deletes the program rows it then rebuilds, whereas a fresh cluster
forked from `current-src` took 4.3 s. A schema-resource add or writerless retire
adopts incrementally in about 7 s (retire keeps the Datahike ident; plan 1.3e).

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
| 2026-09-22 | `a102a8403` frozen archive (`git archive`), before the 2026-09-23 no-from-zero ruling reached the lane | from-zero | 176,798 | 201.64 | — | [lane-publication-lock-deletion](../../prds/agent-platform/landing/lane-publication-lock-deletion-2026-09-23.md) |
| 2026-09-22 | `87c4228f7` frozen archive: `cluster/publication-base!` over an empty root (full index + export, no boot) | publication from empty | — | 166.04 | — | same |
| 2026-09-22 | `a102a8403` archive: `cluster/publication-base!` over the booted root (unchanged source, export only) | publication export | — | 39.10 (JVM start + require included) | — | same |
| 2026-09-22 | `bfe3445f8` frozen archive, JFR attached | from-zero | 170,338 | 191.21 | 11.30 | [from-zero-boot-cost](../../research/agent-platform/from-zero-boot-cost-2026-09-23.md) |
| 2026-09-22 | same root, `down` then `start` | warm restart | 11,092 | 28.73 | 9.98 | same |
| 2026-09-22 | same live JVM, second cluster `fzb2` forked from `current-src` | new cluster | 4,256 | 4.42 | 8.54 | same |
| 2026-09-22 | `fe624bf22` archive + oversight follow-up (booted before the lane read the no-from-zero ruling) | from-zero | 128,569 | 142.38 | — | [lane-oversight-owning-instance](../../prds/agent-platform/landing/lane-oversight-owning-instance-2026-09-23.md) |
| 2026-09-22 | same root, `start` | warm restart | 14,774 | 31.53 | — | same |
| 2026-09-22 | `6bf3bde78` frozen archive: one-pass arity-gate grouping in `write-owned-values-error` (measurement, not a gate) | from-zero | **74,027** (was 170,338) | 89.17 | 7.44 | [lane-validator-single-pass](../../prds/agent-platform/landing/lane-validator-single-pass-2026-09-23.md) |
| 2026-09-22 | same: program-rows transaction, `:db/txInstant` of that transaction to the next | program write | **29,865 ms** (was ~125,500) | — | — | same |
| 2026-09-22 | same root, `down` then `start` | warm restart | 14,469 | 30.12 | ~11 | same |
| 2026-09-22 | `9d029820d` + lane hunks archive: `cluster/publication-base!` over an empty root | publication from empty | — | 93.31 | — | [lane-publication-lock-deletion](../../prds/agent-platform/landing/lane-publication-lock-deletion-2026-09-23.md) |
| 2026-09-22 | `2bd568c08` worktree (committed publication-clock script cold start, before the no-from-zero ruling reached the lane) | from-zero | 165,327 | 185.13 | ~11 | [lane-reload-per-declaration](../../prds/agent-platform/landing/lane-reload-per-declaration-2026-09-23.md) |
| 2026-09-22 | same root at `2bd568c08`, `start` | warm restart | 10,298 | — | ~11 | same |
| 2026-09-22 | same root, `c1d2e6d7f` `git archive`, `start` | warm restart, **hung** | none: main BLOCKED at `seon.cluster.agent/arm!` (`agent.clj:876`) after 300 s; stopped | 301.96 | — | same |
