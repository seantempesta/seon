---
type: issue
status: open
severity: friction
tags: [issue, test, schema, wave/test-fixture]
---

# Refresh canonical fixture contracts after development adoption

## Problem

In-process regressions execute the newly loaded function against the old
function contract carried by the canonical fixture. A valid widened input
therefore refuses before the changed implementation runs.

## Evidence — 2026-09-15

Default PID 69622, MCP JVM mode. After the N7 analyzer edit, the live default
database's `seon.fn/analyze-forms` spec made `:seon.program/row` optional.
The same query inside `seon.test-support/with-database` returned the previous
required-row spec. The direct default-database analysis accepted an ordinary
form and returned both `seon.db/q` and `my.turn/wait` call refs in 18 ms.

The recorded in-process regression
`seon.fn-test/ordinary-form-analysis-keeps-call-edges-without-a-declaration`
returned 1 pass, 0 failures, 1 error, run entity 65281, basis 536872171,
2026-09-15T23:27:07.367Z. It refused at `[2 :seon.program/row]`, before the
ordinary-form assertions. The existing defining-form regression passed all
five assertions in the same JVM because its requests satisfy both contracts.

`test/seon/test_support.clj:165` retains `source-manifest` in a delay;
`:281` retains `database-base` in another delay. `with-branched-database`
at `:628` takes the projection from that acquired base. The host wrapper
correctly uses the supplied database's contract in
`src/seon/instrument.clj:552`; weakening instrumentation would hide the defect.
No fixture global was reset, redefined, or replaced during this observation.

Exact forms and full results are in
[the N7 residual landing](../../prds/context-generation/research/n7-eval-call-edges-2026-09-15.md).

## Owner

The canonical in-process fixture's source identity and acquisition lifecycle,
`test/seon/test_support.clj`, coordinated with development test execution.
This is distinct from initial fixture population refusing a schema transaction:
the fixture here is present, writable, and carries an older contract.

## Acceptance

After a valid function-contract accretion is adopted, an in-process canonical
regression sees that same contract without restarting default, globally
reloading a shared fixture, disabling instrumentation, or substituting a
hand-built schema population. Retained older database values remain immutable.

## Fresh-base probe — 2026-09-15, 23:42Z

The resumed N7 lane initialized a fresh canonical base in default's existing
JVM by loading test-support and realizing the base with default's carried
projection. Its stored `analyze-forms` contract has the optional declaration
row. The previously blocked regression passed 12 assertions (run 67062), then
12 again after adoption (67086). Therefore this issue concerns reuse of an
already retained base across adoption, not a stale contract in newly populated
fixtures. No fixture source or contract was changed. Explicit fresh namespace
initialization does not satisfy the automatic-refresh acceptance above.
