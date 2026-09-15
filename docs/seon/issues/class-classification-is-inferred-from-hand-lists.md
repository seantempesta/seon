---
type: issue
status: open
severity: friction
tags: [issue, database, class/n7, class-kill, wave/class-kill-queue]
---

# Make classification query facts instead of text and hand lists

## Problem

Process identity, config routes, namespace relevance, render edges, test
ownership, and expected counts are inferred from strings or copied rosters.
Each list is a second authority that silently misses the next valid member.

## Evidence

Current open members carry `class/n7` and are derived with
`bin/issues-index --class class/n7`.

## Owner

The constructors/indexers that currently omit the required identity, edge, or
ownership fact, followed by the queries that consume it.

## Acceptance

- Every classified relationship is an explicit recorded fact at its owning
  constructor or index pass.
- Consumers accept no roster, count, prefix, substring, or name-derived route;
  they query the facts.
- Adding a valid member changes the query result without changing classifier
  code or a test expectation.

## Initial design boundary — 2026-09-15 (superseded below)

N7 remains open. The bounded lane verified surviving prefix/route classifiers
and declaration call edges on default, and identified deleted historical
mechanisms without treating deletion as replacement coverage. The requested
repository-wide literal-roster checker needs an agreed classification grammar;
call/keyword edges alone do not distinguish declarations from copied rosters.
The assignment's explicit cross-owner design stop applies before production
edits. Three priced options, all nine member verdicts, exact live probes, and
verification boundaries are recorded in
[the N7 landing note](../../prds/context-generation/research/n7-query-classification-2026-09-15.md).

## Owner decision and implementation — 2026-09-15

The owner explicitly superseded the design stop: owner-specific schema facts,
queries, and tests; no generic publication enforcement or provenance analyzer.
AI routes, explicit config membership, explained interpreter bindings, namespace
relevance, and cross-checkout process claim ownership are implemented in the
existing owners. Three dissolved members moved to archive with their residual
proof named.

N7 remains open for protected schema-composite classification and protected
cluster projection removal (exact diffs in the landing note), and the unproven
persisted edges of ordinary non-defining evaluation forms. Scoped gate and live
verification results are recorded in the landing note.

## Landed verdict — 2026-09-15

Commit `5deb40e4e` installs the owner-specific facts and queries. The final
owner gate passed 82 tests / 418 assertions; the platform gate passed 86 tests /
542 assertions. Six members are closed (four resolved, two superseded with
their residual proof named). The three open boundaries are exactly those
listed above. No generic publication enforcement or provenance analyzer was
introduced. The dated per-member table, live probes, broader baseline reds,
and all touched paths are in the landing note.
