---
type: issue
status: resolved
severity: blocker
tags: [issue, cljs, component, flow]
---

# Isolate the ACME watcher from the canonical test artifact

## Problem

Default and ACME now use separate Shadow server/cache roots, but the generic
process graph makes both watchers watch the single `:test` build. That build
writes and publishes through checkout-global `out/test` paths, so two
independently owned watchers can concurrently replace the intermediate runner,
the canonical changed-test manifest, immutable bundle objects, and retention
state.

The cache-root isolation fix therefore closes compiler/server ownership but not
the managed test artifact's output ownership.

## Evidence

- Before the fix, `seon.dev.process/extra-cljs-watch-args` always emitted
  `watch <selected-client-build> test` for every artifact flavor.
- Before the fix, `seon.dev.process/watcher-ready?` always required both the selected client
  build and `:test` to report completion, so ACME cannot simply omit the test
  build without changing the flavor-aware process contract.
- `shadow-cljs.edn` gives `:test` the checkout-global output
  `out/test/test.js` and enables `seon.dev.test-artifact/publish!`.
- The build hook publishes and prunes checkout-global
  `out/test/artifacts/current.edn`, `bundles/`, and `objects/`; no cache root,
  cluster, or artifact flavor participates in those coordinates.
- Current live proof has a default watcher and an ACME watcher concurrently
  ready under distinct Shadow cache roots. Both command specifications still
  include `test`, making the shared publisher race reachable rather than
  hypothetical.

## Implementation evidence

- The process graph now derives one flavor-owned watcher build vector: default
  owns `[:client :test]`, while ACME owns only `[:acme-client]`.
- Command construction, readiness, and early build-failure detection consume
  that same vector. ACME therefore cannot compile, publish, prune, or await the
  canonical `:test` artifact through its managed watcher.
- The affected operator gate passes 16 tests/55 assertions, including exact
  default/ACME command-build selection and ACME readiness without `:test`.
- The complete operator checkpoint passes 94 tests/581 assertions.
- Concurrent live default/ACME proof confirms the default manifest and bundle
  remain valid across an ACME restart.

## Owner

The flavor-aware process graph and the one canonical changed-test artifact.
The default managed Shadow watcher should remain the sole publisher unless a
separate flavor-specific artifact is explicitly designed and consumed.

## Acceptance

- Exactly one owned process can write or prune the canonical
  `out/test/artifacts` tree and intermediate `out/test/test.js`.
- The default watcher continues to publish the complete changed-test graph and
  passes current warm/focused test behavior unchanged.
- ACME readiness does not depend on compiling or publishing the default test
  artifact; its watcher owns only the builds required by its selected runtime
  flavor.
- Concurrent default/ACME start, hot reload, changed-test selection, and
  shutdown preserve a valid current manifest and bundle with no cross-flavor
  replacement.
- Operator tests assert build lists and artifact-publisher ownership by flavor.

## Resolution proof — 2026-07-14

Before and after restarting ACME under its flavor-owned build vector, the
complete `out/test` tree hashed to
`8d822f866007a3a8f4a1a728c31831d8fdaad8e67ea9427ac3d888dbc042c5fc`.
The `current.edn` and `test.js` modification times also remained unchanged.
Live MCP status showed default owning `:client` plus `:test`, while ACME owned
only `:acme-client`; both clusters remained ready.
