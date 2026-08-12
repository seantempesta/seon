---
type: issue
status: open
severity: friction
tags: [issue, rendering, program-graph, operator, mcp]
---

# Include non-installed operator and MCP leaves in the sink proof

## Problem

The universal-output-floor graph diagnostic reads the database program graph,
whose deliberate source roots are `src/` and `test/`. The development MCP and
fresh operator live under `script/` because their process-control functions
must not become agent-callable program rows. Their declared sink metadata is
therefore analyzable but absent from the live database totals.

This is honest for the transitional baseline and incomplete for graduation. A
zero result over only installed program rows cannot prove every tool/operator
crossing uses the universal output floor.

## Evidence

- `src/seon/fn.clj:19-21` declares the installed source roots as `src` and
  `test`.
- `script/seon/dev/mcp.clj` declares the MCP response sink and its current
  `:none` boundary.
- `script/seon/fresh_operator.clj` declares status, startup, log relay, and log
  storage sinks.
- Direct static analysis of those two script files produced five sink rows and
  six projection-boundary rows.
- The freshly forked database graph reported nine sink facts and nine boundary
  facts; those totals contain only the installed `src/` leaves.

## N2 disposition — 2026-08-11

A fresh in-memory production-population probe reproduced the vacuity against
the current tree. `seon.fn/output-path-report` reported ten installed sinks;
the standing test's assertions (`totals` is a map and every value is an
integer) returned true. In the same JVM, `seon.fn/rows` over
`script/seon/dev/mcp.clj` and `script/seon/fresh_operator.clj` derived five
additional sink rows and six projection-boundary rows that were absent from
the installed report.

This is a production-subject-construction defect. The program-index/build-fact
boundary deliberately has no value that combines installed rows with the
analyzable non-installed leaves, so a test-only concatenation would be a
second roster. N2 leaves the member open for that production owner and does
not mask it in `test/seon/fn_test.clj`.

## Owner

The program-index/build-fact boundary and the universal-output-floor standing
diagnostic. Keep operator code outside the agent-callable database program
graph; do not solve this by adding `script/` to `seon.fn/source-roots`.

## Acceptance

- The standing diagnostic derives both installed program sinks and
  non-installed MCP/operator sinks from the same defn metadata and static
  analyzer, without a function roster or source-text scan.
- Adding another annotated script leaf changes the computed totals without
  editing a test.
- No operator process-control function becomes an installed `:seon.fn` row in
  a cluster.
- The final zero-bypass/zero-unresolved assertion covers both fact sets.
