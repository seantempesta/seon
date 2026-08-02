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

## Acceptance

Cold `(require 'seon.artifact)` under 5 s measured 3x; `bin/test`
green and unaffected; hot reload proven intact (edit a first-party
file, re-require, new behavior); `bin/seon start` on an existing root
inside the ten-second law end to end; and the stale 2.23 s claim
corrected wherever it is written.
