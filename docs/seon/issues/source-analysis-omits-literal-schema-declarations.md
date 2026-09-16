---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, program-graph, indexing]
---

# Source analysis omits literal schema declarations

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

The [reproducible probe](../../prds/steward-platform/research/program-facts-s1-analysis-probe-2026-09-17.clj)
and [S1 investigation](../../prds/steward-platform/research/program-facts-s1-analysis-on-both-seams-2026-09-17.md)
record the boundary. This is source-row evidence, not a completed
publication-versus-turn acceptance test.

## Owner and acceptance

The existing source-analysis and program-row owners must supply the
schema declaration required by the S1 parity acceptance case. Verify it
through publication and the turn writer on canonical fixture branches;
do not populate the missing indexed row manually in the test.
