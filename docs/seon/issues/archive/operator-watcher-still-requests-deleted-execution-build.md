---
type: issue
status: resolved
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

## Owner

The operator artifact/config/process/release boundary owns the flavor build
vector and its published launch identity.

## Resolution

Resolved by `41d911add`. The child build id, output, inventory, imported
runtime digest, release members, launch fields, and readiness expectation were
deleted together. The surviving `execution-digest` remains the exact client
entry-file identity required by startgate and host-session admission.
The original end-to-end writer/pod acceptance cannot complete in the current
dirty tree because page-plan publication now fails at a later boundary; that
independent blocker has its own issue and does not keep this deleted-build
root cause open.

## Proof

- `logs/operator/watcher/b84bc41c-a204-408e-b83e-9fb118a1b350.log`
  watches only `client` and `test`; both builds complete, and `bin/seon up`
  reports `watcher ready`.
- The same bring-up starts the writer and host before the pod independently
  refuses an old applied release identity. A fresh apply is now blocked later
  by [[page-plan-config-digest-drift-blocks-fresh-default-apply]], not watcher
  publication.
- Focused operator gate: 130 tests, 733 assertions, zero failures.
- Portable launch gate: 12 tests, 78 assertions, zero failures.
