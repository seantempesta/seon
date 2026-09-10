---
type: issue
status: resolved
severity: blocker
tags: [issue, test, config]
---

# Seed fixture clusters through config reconciliation

## Problem

`seon.test-support/seed-cluster!` transacted only `:seon.config/cluster`,
ignored the refused result, and then attempted to link that missing row.

## Evidence

Page runtime read fast gate on 2026-09-09: 39 errors at cluster population,
with `Nothing found for entity id [:seon.config/cluster "web-test"]`.
The helper's unchecked partial-row write preceded the failure.

## Owner

`test/seon/test_support.clj` now calls the existing `seon.config/apply!`
owner and refuses setup loudly if configuration fails. The web fixture's
second config application is removed.

## Acceptance

The canonical page fixture constructs its cluster and real server under
armed contracts. Gate results and the resolving commit are recorded in
`docs/prds/context-generation/research/page-runtime-read-landing-2026-09-09.md`.
