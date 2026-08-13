---
type: issue
status: open
severity: friction
tags: [issue, schema, runtime, wave/schema-lifecycle]
---

# The runtime schema guard refuses accretive loosenings when data exists

## Problem

`seon.cluster.run/program-row-tx` calls `assert-schema-data-unused!`
before computing whether a candidate global schema replacement changes
any physical Datahike declaration. An in-place logical LOOSENING — the
W1 case was making `:seon.ns/source` optional on the `:seon.ns/ns`
entity map — is therefore refused whenever namespace data exists, even
though the change is accretive (requires no more, provides no less) and
its Datahike declarations are identical.

The packaged build population accepts the relaxation because a fresh
`current-src` is built from the new complete forms; only the runtime
replacement path over-refuses. Evidence and context:
`docs/prds/sci-execution-runtime/research/w1-implementation-notes-2026-07-31.md`
("Contract pushback", final paragraph).

## Acceptance

The guard computes, before refusing, whether the replacement (a) leaves
every physical Datahike declaration identical and (b) only relaxes
logical requirements (required→optional and equivalent). Such a pure
loosening is permitted with existing data; every tightening or physical
change keeps today's refusal. One recurring regression per direction:
a loosening applies in place over data; a tightening still refuses.
The distinction is COMPUTED from the two projections, never a hand
list of allowed edits.
