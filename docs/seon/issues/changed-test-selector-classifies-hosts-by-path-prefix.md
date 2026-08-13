---
type: issue
status: open
severity: friction
tags: [issue, test, class/n7, wave/changed-test-selector]
---

# Derive changed-test ownership instead of classifying paths

## Problem

The retained changed-test selector decides operator versus writer impact from
literal path prefixes and filenames. A moved or newly introduced source root
can therefore evade the intended widening rule and make the fast feedback gate
healthier than its evidence.

## Evidence

`script/seon/dev/changed_test.clj:167-177` defines `operator-path?` and
`writer-path?` from five prefixes and four exact files. `host-impact` at lines
179-220 uses those predicates for unknown paths, dependency-input forcing, and
the choice of which complete host suite to run.

The same function already has a clj-kondo namespace dependency graph at lines
182-191. The path taxonomy is a second classification mechanism used exactly
where the graph has no evidence.

## Owner

The changed-test host graph and the canonical inventories of executable source
roots and retained test runners.

## Acceptance

- Known paths derive impact solely from namespace dependencies and actual test
  roots.
- An unknown executable source path fails closed by widening to every relevant
  host suite; it is never categorized by a prefix roster.
- Runner/config files identify their affected test surface through one
  maintained manifest or graph edge, not exact-path conditionals.
- A regression adds and moves temporary source roots without changing the
  selector and proves the appropriate widening.
