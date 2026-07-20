---
type: issue
status: superseded
tags: [database, cljs, issue]
severity: friction
---

# Legacy replica load blocks CLJS tests

## Evidence

Focused `bin/test-cljs` runs compile successfully, then execute zero test
namespaces because loading `seon.db.replica` throws while constructing its
default launch descriptor. The generated descriptor contains undefined
`:seon.launch/request-socket-path` and `:seon.launch/publish-socket-path`
values. `seon.db.replica` still reads the removed
`seon.db.transport.uds/default-request-socket-path` and
`default-publish-socket-path` vars, while the canonical persistent Bun
transport now owns one `default-socket-path`.

The latest retained report is
`tmp/test-cljs-20260716-090413-15312.report.edn`; its full log reaches the
`seon.db.replica.js` import and fails before `cljs.test` starts.

## Owner and acceptance

This is migration fallout in the obsolete local-replica mechanism, not a
reason to restore the two-socket defaults. The database-authority mesh atomic
consumer cut owns the repair:

- the maintained test/runtime namespace graph no longer imports
  `seon.db.replica`;
- the replica and transaction publication/replay mechanism are deleted; and
- focused CLJS tests start without a compatibility launch descriptor or
  legacy request/publication socket variables.

Until that cut, Shadow compilation and direct Datahike resource probes remain
valid evidence, but a zero-namespace Node result is not a passing test gate.

## Closed 2026-07-20

Verified stale by the issues triage (docs/prds/source-cleanup/research/issues-triage-2026-07-20.md §STALE): the described code or behavior no longer exists at HEAD; rg evidence in the triage doc.
