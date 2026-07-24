---
type: issue
status: open
severity: blocker
tags: [issue, operator, build]
---

# Page-plan config digest drift blocks fresh default apply

## Problem

After resetting the default database, `bin/seon cluster apply default` refuses
the current published page plan because the page-plan sidecar names a different
config-manifest digest than the operator-selected launch manifest.

This is downstream of watcher readiness: the watcher successfully builds only
`client` and `test`, publishes artifact manifest v14, and the writer becomes
ready before apply starts.

## Evidence

- Failed apply result:
  `tmp/seon-operator/cluster-apply/b908aec1-24f6-402f-a72f-9737d5cbabe3.edn`
- Selected config-manifest digest:
  `607f6793ef3e3123d626e7b69b21cc231a5a209571725b09d62f86b873e29ca0`
- Page-plan config-manifest digest:
  `adc1e407f04100f1412700f33c482d21a2f017eb340dcecfa37ec5bc64908630`
- The refusal reproduced after the old default database was unable to converge
  non-destructively and the pre-authorized default reset completed.

## Owner

The preprocessing/page-plan publication owner must make the flush hook consume
the exact operator-selected resolved manifest used by the launch descriptor.

## Acceptance

- A source-frozen default watcher flush publishes a page plan whose
  config-manifest digest equals the selected launch manifest digest.
- `bin/seon cluster apply default` succeeds against a fresh default database.
- A subsequent `bin/seon up` passes the applied-identity start gate and reaches
  pod and web-render readiness without regenerating the page plan.
