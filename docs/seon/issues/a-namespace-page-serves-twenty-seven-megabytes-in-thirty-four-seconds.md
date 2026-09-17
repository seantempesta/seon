---
type: issue
status: open
severity: blocker
tags: [issue, web, render, performance, wave/namespace-page-performance]
---

# A namespace page serves 27 MB in 34 seconds

## Problem

On the freshly reset default cluster (2026-09-17, HEAD `6ea932372`, pid
56353, Juniper reseeded), the ordinary namespace page for a small namespace
returned 27.5 MB of HTML and took 34 s. The root and agent pages take 6 s
warm at 3.7 MB. Under the standing rule any 3 s+ surface is a defect, and
27 MB is not a page a browser can hold as a live-updating surface.

## Evidence

Measured with curl from the same machine after the platform gate JVMs had
exited (load average 5.8):

```text
GET /               200  6.51 s   3,753,105 B
GET /agent/root     200  6.16 s   3,753,096 B
GET /ns/seon.id     200 34.15 s  27,547,015 B
GET /ns/seon.db     200 89.37 s  (measured earlier under gate load 17)
```

A thread dump during `/ns/seon.db` showed the request thread inside
`seon.render.value/value-node*` recursing eight levels deep under
`seon.render/project-node` → `invoke-selected` → `seon.sci.kernel/invoke`
(`src/seon/render/value.clj:311-429`, `src/seon/render.clj:990-1218`).

## What is not known

Whether the bytes are one entity rendered without a bound (HTML has no
presentation clipping by ruling, but query-work bounds still apply) or the
whole program population rendered per page. The size, not only the latency,
is the finding: the existing notes in `wave/namespace-page-performance`
record seconds, not megabytes.

## Owner

Orchestrator window; the fix belongs to the namespace page render owner
(`src/seon/render/ns.clj`) or the query-work bound at the walk.
