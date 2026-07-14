---
type: issue
status: open
severity: blocker
tags: [issue, component, agent, flow, cljs]
---

# Isolate ACME Shadow before the command starts

## Problem

The ACME watcher passes its alternate `:cache-root` through Shadow's
action-level `--config-merge`. Shadow has already loaded the project config and
chosen its server/cache identity before it parses that action option. Starting
ACME can therefore join or overwrite the default checkout's Shadow server
discovery and can publish the wrong CLJS endpoint/runtime selection even though
the ACME build id and output path differ.

## Evidence

The current ACME watcher command derived by `seon.dev.process/specs` places
`--config-merge {:cache-root ...}` after `watch acme-client test`. A live ACME
attempt on its separate web port was observed writing the checkout-level
`.shadow-cljs/nrepl.port`; default structured status then selected the changed
CLJS endpoint, while the ACME pod log showed selection of `:client` rather than
an independently owned Shadow server/runtime.

Shadow source separates command startup configuration from action/build
configuration: its CLI loads the project configuration and derives the server
cache/discovery paths before action `:config-merge` is applied. The cache root
therefore must be selected before the Shadow JVM command starts, not merged
into an individual watch action.

The exact source path is `shadow.cljs.devtools.server/from-cli`: it loads the
plain project config, calls zero-argument `start!`, and only later passes CLI
options to `watch-builds`. `start!` derives `:cache-root`, checks the existing
server, and publishes port files. Shadow's `SHADOW_CLJS` environment EDN is
deep-merged into the project config before that sequence, so it is the existing
pre-start configuration seam.

The failed live attempt left direct filesystem and log evidence. The ACME
watcher reported nREPL port `61208` written to `.shadow-cljs/nrepl.port`, and
its compiled runtime landed under `.shadow-cljs/builds/acme-client` rather than
`tmp/shadow/acme/builds/acme-client`. The ACME pod's static startup message
also claimed `.shadow-cljs` and `:client`. After both watchers had used the
shared cache, the changed-test gate failed before assertions because its
retained bundle referenced a missing `cljs-runtime/my.plan_test.js` file.

## Partial implementation — 2026-07-14

Artifact configuration now projects the ACME cache coordinate into
`SHADOW_CLJS` before any managed child starts. An existing environment EDN map
is preserved, except the selected flavor remains authoritative for
`:cache-root`. Default flavor environment and argv remain unchanged. The
one-shot artifact and watcher commands no longer put `:cache-root` in their
action-level `--config-merge`; that merge owns only downstream preload data.

The pod startup message now reads Shadow's compiled
`shadow.cljs.devtools.client.env/build-id` instead of claiming the default
cache/build for every flavor. The complete operator checkpoint passes 93 tests
and 577 assertions. The automatically selected 57-namespace CLJS gate could
not execute because the already-collided retained artifact was missing
`my.plan_test.js`; clean dual-watcher live proof remains required before this
issue can close.

## Owner

The one flavor-aware operator command derivation in `seon.dev.process` and
`seon.dev.artifact`. Artifact flavor data already owns the cache root; both the
watch and one-shot compile commands must present that coordinate through the
same pre-command Shadow configuration seam.

## Acceptance

- The default Shadow command remains byte-for-byte unchanged.
- Every ACME Shadow command selects `tmp/shadow/acme` before Shadow loads the
  project configuration or starts/discovers a server.
- ACME action-level build/preload merges cannot own or alter server discovery.
- Operator tests prove argument ordering and reject regression to an
  action-level cache-root merge.
- Live proof can run default and ACME concurrently with distinct nREPL port
  files, CLJS endpoints, active builds, and cluster-qualified runtimes.
