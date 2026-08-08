---
type: issue
status: open
severity: friction
tags: [issue, render, web, live-drive]
---

# Return `/data` without a five-second stall

## Problem

`/data` responds 200 with a 3,168-byte page and takes about 5.5 seconds to
produce the first byte, every time. The page is small and the work behind it
is a single database inspection, so the latency is not the payload.

The 2026-08-06 drive recorded `/data` as a deterministic 500 in 41 ms. That
break is fixed. What replaced it is a deterministic 5.5-second stall, so the
route still is not usable.

## Evidence

Cluster `default` (pid 79576), observer lane, 2026-08-08, three consecutive
samples over the live HTTP server:

```text
/data  200 3168B total=5.524532s ttfb=5.524480s
/data  200 3168B total=5.426576s ttfb=5.426533s
/data  200 3168B total=5.489447s ttfb=5.489400s
```

Time-to-first-byte equals total time in every sample, so the whole page is
computed before anything is written. For comparison, in the same session and
against the same server:

```text
/                        200 737602B ttfb=3.116973s   (cold)
/                        200 737602B ttfb=0.013039s   (warm, keyframe)
/agent/root              200 313618B ttfb=0.015239s
/ns/my.agents.root       200 313618B ttfb=0.015578s
/ns/my.agents.root/debug 200   2087B ttfb=0.024762s
```

So a 738 KB page serves warm in 13 ms while a 3 KB page takes 5.5 s. `/data`
is also the only one of these routes with no warm path — the third sample is
as slow as the first.

Two neighbouring 2026-08-06 findings are worth recording as fixed in the same
breath: the namespace debug page returned no first byte within five seconds
then, and now returns a shell in 25 ms with its content following over the
feed; and the root page's "Renderer unavailable" unit is gone.

## Cause — measured, not inferred

The stall is the declaration-population fallback, almost in its entirety.
Snapshotting `seon.schema/!fallback-counts` around exactly one `/data` request
that took 5.441 s:

```clojure
{:total-delta   556
 :estimated-ms  5893.6   ; 556 × 10.6 ms measured per resolution
 :by-caller
 {"seon.schema.datahike (datahike.clj:71)"  160
  "seon.schema.datahike (datahike.clj:72)"   80
  "seon.schema.datahike (datahike.clj:112)"  64
  "seon.schema.datahike (datahike.clj:203)"  50
  "seon.schema.datahike (datahike.clj:220)"  50
  "seon.schema.datahike (datahike.clj:184)"  46
  "seon.schema.datahike (datahike.clj:113)"  32
  "seon.schema.datahike (datahike.clj:223)"  25
  ;; … 13 more callers, 49 resolutions
  "seon.print (print.cljc:232)"               4
  "seon.render.web (web.clj:1469)"            1}}
```

5,894 ms estimated against 5,441 ms observed — within 8%. About 530 of the
556 resolutions come from `seon.schema.datahike`, the Malli-to-Datahike
bridge, which re-resolves the whole population per attribute rather than
once per request.

The 10.6 ms per resolution is itself measured on this cluster and is NOT
memoized — 10 calls and 100 calls both average 10.6 ms/call:

```clojure
{:warm-ms-per-call-10 10.8665916, :warm-ms-per-call-100 10.588492}
```

So this is one instance of
[Resolve the declaration population once per admission, not once per node](value-admission-resolves-the-declaration-population-per-node.md),
surfacing on a route rather than in admission. Fixing that owner should fix
this route; this note exists because `/data` is the user-visible symptom and
because the bridge is a caller that note does not currently name.

## Owner

`src/seon/schema/datahike.clj` (the resolutions), with the `/data` route in
`src/seon/render/route.clj` as the surface that exposes them.

## Acceptance

- `/data` serves in the same order of magnitude as the other routes.
- One `/data` request causes a single-digit number of declaration-population
  resolutions, not 556.
- Repeat requests at an unchanged basis do not recompute the page.
- The measurement is a recurring one, so a later change cannot quietly
  reintroduce a multi-second floor.
