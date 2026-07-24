---
type: issue
status: open
severity: blocker
tags: [issue, operator, build]
---

# Operator watcher still requests the deleted execution build

## Problem

After U9 deleted the child execution build, `bin/seon up` still starts Shadow
with `watch client execution test`. Shadow reports `No config for build
"execution" found`, completes the surviving `client` and `test` builds, but
the operator remains `rebuild-pending` and eventually fails its first-flush
gate.

R52 observed this at the reset-boundary checkpoint. The supervised attempt was
closed with `bin/seon down`; no child was killed directly. R52 did not edit the
protected `script/seon/dev/**` owner.

## Evidence

- Watcher log:
  `logs/operator/watcher/1205314e-f18f-460c-b9e6-63004e1025bc.log`
- `bin/seon status` reported the watcher alive and all other processes absent
  while readiness remained `rebuild-pending`.
- The surviving client artifact compiles when invoked with the admitted
  resolved-manifest environment: 453 files, zero errors.

## Acceptance

- The operator derives its watch vector only from surviving flavor-owned
  builds and never names the deleted `execution` build.
- `bin/seon up` publishes a current artifact after the surviving build flush,
  then starts the writer, claimant host, pod leaf, and web-render processes.
- `bin/test-writer` accepts that current artifact without a manual fixture
  manifest.
