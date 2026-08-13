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
