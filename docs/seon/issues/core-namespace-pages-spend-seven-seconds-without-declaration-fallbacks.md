---
type: issue
status: open
severity: friction
tags: [issue, render, web, performance]
---

# Attribute the seven-second core namespace-page derivation

## Problem

Core namespace pages take several seconds even after the declaration bridge
cost is absent. The 2026-08-10 route walk tentatively attributed their
12-18-second latency to the same declaration-population fallback that stalled
`/data`; a direct before/after measurement refutes that attribution.

## Evidence

Cluster `db-decode-scratch`, current loaded source after `f098bbdc7`, one
read-only request:

```text
GET /ns/seon.db  200  908444 bytes  7.647 s
declaration fallbacks: total=1, seon.db=1, seon.schema.datahike=0
```

Before the bridge change, the same lane measured the page at 9.499 s on the
scratch cluster and 7.383 s on `default`, with only 8 and 1 declaration
fallbacks respectively. The fix moved `/data` from 7-8 seconds to about 130 ms
but did not materially move this page. Declaration resolution therefore
cannot explain the namespace-page floor.

The earlier full route walk remains the broader symptom evidence:
[ui-truth-2026-08-10.md](../../prds/sci-execution-runtime/research/ui-truth-2026-08-10.md).

## Owner

The namespace-page derivation under `seon.render`. Start with a measured cost
profile of the one route operation; do not reopen `seon.db` decoding or the
declaration bridge without a new fallback-count falsifier.

## Acceptance

- Attribute the wall time to named operations with measurements that account
  for most of the observed total.
- Make a repeat request at an unchanged basis serve in the same order of
  magnitude as the 19 ms agent namespace page, or record the ruled reason it
  cannot.
- Add one recurring route-level cost proof for the identified class.
