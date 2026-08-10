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

## Recurrence and regression, 2026-08-08 (whole-system-arc observer lane)

Cluster `default` (pid 31475), a fresh cluster. Not fixed, and slower:

| Sample | Status | Bytes | TTFB | Total |
|---|---|---:|---:|---:|
| 1 | 200 | 3,168 | 6.537 s | 6.537 s |
| 2 | 200 | 3,168 | 6.412 s | 6.412 s |

TTFB equals total on every sample and there is still no warm path, while `/`
serves 397 KB warm in 15 ms on the same server.

Priced independently by snapshotting `seon.schema/!fallback-counts` around one
6.412 s request:

```clojure
{:total-delta 927
 :top {"seon.schema.datahike (datahike.clj:71)"  264
       "seon.schema.datahike (datahike.clj:72)"  133
       "seon.schema.datahike (datahike.clj:112)" 109
       "seon.schema.datahike (datahike.clj:220)"  80
       "seon.schema.datahike (datahike.clj:203)"  80
       "seon.schema.datahike (datahike.clj:184)"  76}}
```

927 resolutions for one 3 KB page — 67% more than the 556 originally recorded,
and still essentially all from the Malli-to-Datahike bridge. Same cause, larger.

The per-resolution cost was re-measured directly on this JVM rather than reused
from the earlier note: 12.90 ms at n=10 and 11.51 ms at n=50, flat, confirming
it is still not memoized.

## Recurrence, 2026-08-10 (UI-truth route walk)

Cluster `default` (pid 31570), current HEAD. Still not fixed, and slower again:

| Sample | Status | Bytes | Total |
|---|---|---:|---:|
| cold | 200 | 3,168 | 7.864 s |
| warm | 200 | 3,168 | 7.452 s |

Still no warm path — the repeat request at an unchanged basis costs essentially
the same as the first. The trend across the three recordings is 5.5 s → 6.5 s
→ 7.9 s.

Two additions this walk found, both about usefulness rather than latency:

- The page renders only **8 of 525** schema keys before eliding, with
  `elided — this value is larger than the configured window`. The elision value
  itself is good — it names the count, the requery attribute, the path, the
  offset, and the producing profile — but a schema drill page that shows 1.5%
  of its subject for 7.9 s is not usable for its purpose.
- `/data` wears the root agent's identity: `<title>seon · root</title>`, the
  `/agent/root` nav, and a `message agent root …` form bar. It reads as the
  root page with a data block appended rather than as a data page.

The same latency signature appears on core namespace pages measured in the same
walk — `/ns/seon.db` 18.4 s cold and 12.0 s warm, `/ns/seon.fn` 17.2 s cold and
6.7 s warm, `/ns/seon.render` 14.0 s — while the agent-owned
`/ns/my.agents.root` serves a comparable 903 KB in 19 ms. Recorded here rather
than as a separate note because the fallback owner named above is the likely
shared cause; a fix should be verified against those routes too.

Full walk: [ui-truth-2026-08-10.md](../../prds/sci-execution-runtime/research/ui-truth-2026-08-10.md)

## Bridge fix and remaining route work, 2026-08-10

The measured 530-resolution bridge class is fixed by `f098bbdc7`.
`database-attributes-for-in` now supplies the same immutable forms it passes
as a projection for the full derivation, so registered predicates invoked by
Malli cannot resolve the classpath population per attribute. The recurring
regression runs the production 525-attribute operation on a raw Java task with
no inherited Clojure bindings and asserts its resource reads equal exactly one
population.

Live before/after in the same JVM, with `seon.schema/!fallback-counts`
snapshotted around each request:

| Cluster | Before | After | Bridge fallbacks after |
|---|---:|---:|---:|
| `db-decode-scratch` | 7.12-7.95 s / 554-556 total fallbacks | 418 ms cold, 130 ms warm | 0 |
| `default` (read-only route) | 8.17 s / 578 total fallbacks | 128 ms | 0 |

The first scratch request before the fix happened to finish in 109 ms with
eight fallbacks; three immediate repeats then reproduced the 7.8 s / 556
shape. That is why the class regression deliberately uses a worker with no
inherited bindings rather than relying on whichever HTTP worker serves one
sample.

The five-second stall is gone, but this note remains open for its other
acceptance edges: unchanged-basis requests still derive a new response rather
than serving retained bytes, the page still shows only 8 of 525 schema keys,
and it still wears root's page identity. Those are route/render concerns, not
reasons to retain the repaired bridge fallback.

The core-namespace attribution is refuted. After `f098bbdc7`,
`/ns/seon.db` still took 7.65 s for 908,444 bytes while causing one total
declaration fallback, from `seon.db`, and zero from `seon.schema.datahike`.
That independent cost is filed as
[core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks.md](core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks.md).

## Independent confirmation, 2026-08-10

Observer lane on cluster `default` (pid 91415), four consecutive samples over
the live HTTP server, measuring TTFB and total separately:

```text
/data 200 3147B ttfb=0.131s total=0.131s
/data 200 3147B ttfb=0.125s total=0.126s
/data 200 3147B ttfb=0.124s total=0.124s
/data 200 3147B ttfb=0.123s total=0.123s
```

The first request of the JVM took 0.32 s, then 0.123–0.131 s steady. Against
the 5.5 s originally filed and the 6.4–6.5 s the 2026-08-08 observer measured
for the same 3,147-byte payload, that is a ~50× improvement with a real warm
path. The stall is gone from an independent lane's measurement as well.

The note's other acceptance edges (retained bytes on an unchanged basis, only
8 of 525 schema keys shown, root's page identity) were not exercised here, so
this is corroboration of the latency fix only.
