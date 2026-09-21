---
type: issue
status: resolved
severity: friction
tags: [issue, test, datahike]
---

# Physical fixture copy selected an empty default branch

The ordinary test fixture branches the published database. The separate
physical-store fixture copied its private exported store with Datahike
`fork-database`, supplying a configuration whose branch was `:current-src`.
Datahike reads the source store's `:db` key explicitly
(`reference-code/datahike/src/datahike/versioning.cljc:620`); it does not select
that head from the configuration's branch. The result lacked the published
agent schema while the fixture supplied the published projection.

The fixture now points its private store's unused `:db` branch at the acquired
published value before copying. It never changes the exported store or the
ordinary branch fixture. A real physical-copy probe then completed three
state derivations with all semantic assertions passing. Its total
**13097.799917 ms** exceeded the default **5000 ms** bound: physical copying
was therefore rejected for the generated turn-work property, which now uses
immutable database values. This is a correctness repair, not a claim that
physical copies meet the ordinary fixture's latency target.

Evidence: [test-system landing](../../prds/steward-platform/research/test-system-fork-2026-09-23.md).
