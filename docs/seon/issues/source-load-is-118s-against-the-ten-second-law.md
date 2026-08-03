---
type: issue
status: open
severity: friction
tags: [issue, tooling, performance]
---

# Source load is 11.8 s, and the ten-second law is measured against it

## Problem

`(require 'seon.artifact)` — the system closure — measures **11.8 s**
from `-M:dev` (matched by the jar's 12.1 s namespace-load phase,
measured three times). The July figure of 2.23 s that AGENTS.md and
the fresh-tree memory still imply is a **fossil**: the tree has grown
roughly tenfold since. Every dev boot, every fresh-JVM test run, and
every artifact start pays it, so the owner's ten-second start law is
breached by the load itself.

## Evidence

Per-namespace profile (2026-08-02 lane, means):

| Rank | Namespace | Owner | Mean |
|---:|---|---|---:|
| 1 | `konserve.tiered` | source dependency | 1,154 ms |
| 2 | `datahike.connector` | source dependency | 851 ms |
| 3 | `superv.async` | jar/platform | 588 ms |
| 4 | `konserve.impl.defaults` | source dependency | 379 ms |
| 5 | `konserve.filestore` | source dependency | 316 ms |
| 6 | `clojure.core.async` | jar/platform | 312 ms |
| 8 | `seon.cluster` | first-party | 259 ms |

**Vendored source dependencies dominate**; first-party namespaces are
a small minority of the cost. We compile the libraries awake on every
start.

## Direction and the hazard already hit

An AOT class cache for the VENDORED set only, with Clojure's
newer-wins source preference so first-party editing and hot reload are
untouched (the cache invalidates by file mtime). Work began at
`9bb559df9`. The lane then hit a real hazard worth keeping: compiling
an already-loaded `defprotocol` namespace emits `__init.class` but can
omit the interface class, and removing loaded namespaces to work
around it produced core.async classloader splits and SCI read-only Var
failures. The chosen shape is two clean JVM phases — discover actual
load-completion order from the real closure, then compile in that
order in a fresh compiler JVM — with no hand roster and no
live-namespace mutation. `core.async` needs its own decision: AOT
compiles go blocks to IOC on our pin (the `vthreads=target` property
is inert there), so caching it changes the runtime profile we tuned.

A second live hazard ruled out mutable admission entirely. Replacing
`target/dev-dependency-classes` while a recorded JVM used that path caused a
`NoClassDefFoundError` chain through `malli.generator`, test.check generators,
and `rose_tree`; restoring the JVM required dependency-ordered source reloads.
Each admitted closure is now an immutable content-addressed directory, and a
process record carries the exact directory used by that JVM. Refresh publishes
a new directory. Reaping derives live references from pid plus start instant
and never deletes a referenced path; no TTL or literal process roster decides.

The incident, implementation, measurements, and live-refresh falsifier are in
[[load-time-2026-08-03]].

## Acceptance

Refreshed `(require 'seon.artifact)` measured 3x with a 3.2-second median;
source-only was 10.3 seconds and the stale cache 5.4 seconds. A recurring test
must preserve an exact cache directory while its recorded JVM remains live and
prove a lazy class load after refresh. The remaining closure is artifact cold
and reopen measurement, the complete focused operator/boot gate, and hot reload
proof with no first-party loader class.
