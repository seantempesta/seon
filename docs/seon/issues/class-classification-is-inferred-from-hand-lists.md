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

## Design boundary — 2026-09-15

N7 remains open. The bounded lane verified surviving prefix/route classifiers
and declaration call edges on default, and identified deleted historical
mechanisms without treating deletion as replacement coverage. The requested
repository-wide literal-roster checker needs an agreed classification grammar;
call/keyword edges alone do not distinguish declarations from copied rosters.
The assignment's explicit cross-owner design stop applies before production
edits. Three priced options, all nine member verdicts, exact live probes, and
verification boundaries are recorded in
[the N7 landing note](../../prds/context-generation/research/n7-query-classification-2026-09-15.md).
