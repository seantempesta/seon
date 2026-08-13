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

Eight open issues span 2026-08-01 through 2026-08-11:
[[agent-form-calls-to-core-namespaces-are-not-indexed]],
[[changed-test-selector-classifies-hosts-by-path-prefix]],
[[cluster-toolkit-stores-a-prefix-derived-projection]],
[[config-ai-request-idents-are-derived-by-string-surgery]],
[[config-dial-discovery-has-three-authorities]],
[[initial-paint-census-is-a-hand-maintained-count]],
[[operator-classifies-processes-by-command-substrings]], and
[[render-walk-maintains-a-derived-edge-hand-list]].

The archive repeats the class on 2026-08-11 in
[[archive/render-walk-spells-declared-identities-as-raw-eids]]; the same-day
walk repair demonstrated that querying `:seon.entity/id-attr` removes the
roster.

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
