---
type: issue
status: resolved
severity: cleanup
tags: [issue, runtime, test]
---

# Keep the packaged-process fixture aligned with the release inventory

## Problem

The packaged-process test fixture omitted the client and execution inventory
members required by the maintained release contract.

## Evidence

The focused `seon.dev.process-test` gate failed while deriving packaged
process specifications because `package-process-manifest` lacked
`:seon.release.member/client-inventory` and
`:seon.release.member/execution-inventory`.

## Owner

`test/seon/dev/process_test.clj` owns the packaged-process fixture; the release
member contract remains the authority.

## Acceptance

The fixture names every required release inventory member and the complete
focused operator gate passes.

## Resolution

Resolved by `fe5e289b9`. The fixture now includes both inventory members, and
the focused operator gate passes 118 tests and 545 assertions.
