---
type: issue
status: open
severity: friction
tags: [issue, schema, database, sci, class/p1, wave/explicit-environment-proof]
---

# Remove remaining explicitly supplied thread projection compatibility

## Narrowed at 2026-09-15 — b80f78a7c

Database reads, config reads and transaction construction still accept the
existing `schema/handed-projection` when a carried value is absent. They never
rebuild declarations. The common `seon.db/projection-fallback` returns a flat,
counted refusal when both inputs are absent. This residual is about removing
the thread input itself, not projection reconstruction.

SCI admission now uses value/request/database/ctx environment data; kernel,
render, effect and turn producers carry those inputs. SCI acquisition's read
fallback and per-document contract projection builds are gone. Turn and web
database births use `seon.db/db`. Cold cluster construction remains an explicit
projection builder; the db constructor still consults the existing operator
connection state lookup, rather than introducing a second registry.

Default live probe: three query find shapes agree between supplied and carried
branches; missing input refuses once; debug HTTP200 in 1.526276 seconds with
zero new fallback warnings. The complete corrected regression gate and full
source adoption remain pending orchestrator verification.

Exact evidence, member boundaries and the already approved option 1 are in the
[P1 landing note](../../prds/context-generation/research/p1-ambient-state-2026-09-15.md).
Adoption/lifecycle, foreign-write fencing, generators, operator initialization,
source offsets and provenance are separate open members. The whole P1 class
is not closed by this slice.
