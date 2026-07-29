---
type: issue
status: open
severity: cleanup
tags: [issue, docs]
---

# Replace contract-scaffold prose with current namespace contracts

## Problem

Six implemented production namespaces still tell readers that an
implementation lane must fill stub bodies until a sealed test suite turns
green. Historical construction sequencing is presented as current source
authority.

## Evidence

The stale instruction appears at:

- `src/seon/cluster.clj:4-8`;
- `src/seon/cluster/store.clj:5-10`;
- `src/seon/cluster/ancestor.clj:4-9`;
- `src/seon/schema/edn.clj:5-9`;
- `src/seon/sci/admit.clj:4-10`; and
- `src/seon/config.cljc:4-8`.

All six namespaces contain implemented functions and recurring tests.

The archived
`five-namespaces-claim-they-await-implementation.md` note removed the same
failure class from a different set of namespaces. This is a recurrence in
owners that were not covered by that issue's acceptance grep.

## Owner

The namespace docstrings in the six named production files.

## Acceptance

Each namespace docstring describes the current behavioral contract and its
dependency owner without lane status, stub-filling instructions, or a
tests-green implementation recipe. A source-wide grep has no implementation
scaffold prose outside an explicitly active contract package.

## Triage 2026-07-29, chunk 2

Configuration settled in `ce398cd59`, so its identical stale instruction is
now part of the finding. The prior in-flight exclusion is superseded.
