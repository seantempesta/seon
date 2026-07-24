---
type: issue
status: resolved
severity: blocker
tags: [issue, flow]
---

# Hash the selected package program-source member

## Problem

Package process derivation hashed a source-checkout coordinate beside the
client output instead of the package's manifest-selected program-source member.
The real release member existed, but `seon.dev.artifact/current-program-source-digest`
reported it absent before the operator could derive the package process graph.

## Evidence

`seon.dev.process-test/selected-runtime-owns-only-its-required-processes`
published `runtime/program-sources.edn` and selected that exact path in
`:seon.dev.config/program-source`. The digest helper instead looked for the
nonexistent `runtime/client/program-sources.edn`, producing one operator-suite
error before any ownership assertion changed.

## Resolution

`seon.dev.artifact/current-program-source-digest` now hashes the explicit
selected program-source path when configuration supplies one and retains the
flavor-owned build coordinate for source checkouts. The package fixture's
existing digest and process-ownership assertions exercise both the selected
bytes and the unchanged ownership contract.

## Evidence

The focused `seon.dev.process-test` gate passes 75 tests and 425 assertions
with zero failures and zero errors.

The complete operator gate passes 318 tests and 1,837 assertions with zero
failures and zero errors.
