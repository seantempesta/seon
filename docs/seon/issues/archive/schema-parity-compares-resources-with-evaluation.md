---
type: issue
status: resolved
severity: friction
created: 2026-09-17
tags: [issue, program-graph, indexing]
---

# Schema parity compares resources with evaluation

## Resolution — owner ruling, 2026-09-17

Literal register! in .clj source is not a declaration seam by ruling.
Schemas are declared once under `resources/seon/schemas/`; the agent's
evaluation seam accepts `seon.schema/register!` through the SCI reader.
The amended program-facts PRD I1 requires parity between those two seams.
The observed absence in source analysis is correct behavior, not an
indexer defect. The S1 regression uses a fixture schema resource on its
indexed side and an evaluated registration on its agent side.

## Evidence

The S1 read-only MCP JVM probe on default PID 53320 gave the existing
`seon.fn/analysis-rows-by-file` a namespace containing a literal
`(schema/register! :sample.s1/value :int)`, two functions and a deftest.
It returned four declaration identities and zero schema rows. The
source-string analysis ran through `seon.fn.analyzer/analyze`.

The constructor walks namespace and Var definitions only
(`src/seon/fn.clj:960`). `seon.fn/rows` flattens those file artifacts
(`src/seon/fn.clj:1877`); `desired-rows` separately adds packaged schema
resources (`src/seon/fn.clj:2078`). A literal registration in this source
does not become a schema entity through that path.

The [reproducible probe](../../../prds/steward-platform/research/program-facts-s1-analysis-probe-2026-09-17.clj)
and [S1 investigation](../../../prds/steward-platform/research/program-facts-s1-analysis-on-both-seams-2026-09-17.md)
record the boundary. This is source-row evidence, not a completed
publication-versus-turn acceptance test.

The proposed indexer change below the original observation was withdrawn.
