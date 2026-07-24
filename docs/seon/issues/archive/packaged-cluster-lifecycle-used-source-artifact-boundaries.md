---
type: issue
status: complete
tags: [issue, operator, runtime]
---

# Packaged cluster lifecycle used source-artifact boundaries

## Failure

The first immutable-package `cluster apply` for R45 S4 failed before creating
the target database. `seon.dev.cluster` read `release.edn` through the
source-checkout artifact manifest validator, and autonomous cluster paths were
derived under the immutable package root. After selecting the release reader,
the package also exposed a digest-domain mismatch: release member digests
include the member type tag, while the client admission variables require the
SHA-256 of the exact sidecar bytes.

## Root cause

One cluster lifecycle crossed three already-distinct producer contracts
without translating them at the boundary:

- source checkouts publish a canonical artifact manifest;
- release packages publish and verify `release.edn`;
- runtime sidecar admission compares exact file bytes.

The package target also reused the source checkout root as mutable operator
state, contradicting the release inventory's closed immutable root.

## Resolution

`seon.dev.cluster` now selects the manifest reader from
`:seon.dev.config/source-checkout?`, derives autonomous database/process/log
paths from the configured operator state tree, and leaves the release root
immutable. `seon.dev.process` verifies the release once, then passes the exact
raw-byte digests for `base-projection.edn` and `page-plan.edn`.

Focused proof: `seon.dev.cluster-test` and `seon.dev.process-test`. Live proof:
release `ee5015ec…` applied and opened `s4startgate` under
`tmp/orchestrator/r45s4-b-state/`; package verification remained green, and
the release directory gained no mutable `data/`, `tmp/`, or `logs/` members.
