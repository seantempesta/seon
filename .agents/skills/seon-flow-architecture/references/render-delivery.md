# Render delivery

Read this when changing render blocks, SSE delivery, buffering, socket
backpressure, or the proposed package/keyframe protocol.

## Contents

- [Current implementation](#current-implementation)
- [Blocks and stable identity](#blocks-and-stable-identity)
- [Current snapshots and per-tab deltas](#current-snapshots-and-per-tab-deltas)
- [TARGET: revisioned packages and keyframes](#target-revisioned-packages-and-keyframes)
- [Per-edge buffer law](#per-edge-buffer-law)
- [The http-kit write-state interface](#the-http-kit-write-state-interface)
- [Measured frame budgets](#measured-frame-budgets)
- [Change checklist](#change-checklist)

## Current implementation

The live UI is JVM code in `src/seon/render/web.clj`, not the deleted CLJS pod.
One cluster render proc consumes:

- database transaction reports;
- streamed partial values; and
- page-demand signals.

It emits a complete page snapshot map
`{agent-id {surface-id serialized-html}}`, suppresses unchanged surfaces, and
mults snapshots to connected tabs
(`src/seon/render/web.clj:497-671,692-804`).

The built surface also includes canonical namespace pages, root/agent aliases,
and namespace/agent debug variants in the one route table
(`src/seon/render/route.clj:5-34`). HTML pages and AI context share the same
walk membership and ordering, and debug renders both projections from one
database value (`src/seon/render/web.clj:300-350,1041-1102`;
`src/seon/render/walk.clj:693-876`). The package/keyframe protocol below and a
generalized canvas/control surface remain **[TARGET]**
(`src/seon/render/route.clj:5-27`;
`src/seon/render/web.clj:132-169,1027-1037`).

## Blocks and stable identity

A block is one render function's identified output:

- the render function;
- its stable element ID; and
- its current serialized bytes.

The current renderer's `page-of` serializes surfaces by stable ID at
`src/seon/render/web.clj:300-350`. `changed` compares current and prior
surface bytes at `src/seon/render/web.clj:443-467`.

Keep identity stable across updates. Datastar morphs the targeted element; a
new ID turns an update into removal plus insertion and loses browser-local
state.

Use “block” for the render unit, “surface” for a context render, and “card”
only for CSS grouping. Do not revive widget/component/panel as competing
runtime nouns.

## Current snapshots and per-tab deltas

The live delivery sequence is:

1. Render one complete page snapshot when database or stream state changes.
2. Publish that snapshot through a `mult`.
3. Give each tab a `(sliding-buffer 1)` tap.
4. On connection, render and send a full initial paint.
5. For subsequent snapshots, compare against that tab's prior snapshot and
   send changed surfaces.

Read `src/seon/render/web.clj:300-350,443-467,497-804`.

The per-tab prior snapshot means delta selection is currently repeated for
each connection. Serialization of each surface happens in `page-of` before the
mult, so the expensive render/serialize work is shared.

Do not claim current revisions, gap detection, or reconnect keyframes. Those
are target protocol properties.

## TARGET: revisioned packages and keyframes

The ruled target uses three delivery nouns:

- **package** — one revisioned delivery value per render change;
- **delta** — only changed block fragments; and
- **keyframe** — every current block, serialized once.

The intended producer serializes shared bytes once, then mults the immutable
package to every tab. A revision gap selects the latest keyframe. A new page
load can use that keyframe without re-rendering.

This design and its falsification are in
`docs/prds/sci-execution-runtime/research/render-pipeline-design-2026-07-29.md`.
It is **[TARGET]**. Before implementation, settle revision identity, bounded
keyframe retention, the gap rule, and how the current initial paint path
acquires the latest package.

Do not bolt packages beside snapshots. Convert the existing render owner in
place and delete the superseded per-tab delta mechanism in the same slice.

## Per-edge buffer law

Choose each buffer from its loss semantics:

| edge | buffer | why loss is safe or forbidden |
|---|---|---|
| database render wake | sliding-1 | newest database value supersedes older wakes |
| streamed partial | sliding-1 | newest complete partial supersedes an older partial |
| page demand | sliding-1 | demand means derive current page |
| package to each tab | sliding-1 | a slow tab needs the newest complete package/keyframe |
| socket write completion | no lossy handoff | writer must observe drained or closed |
| fault observation | counted-dropping | producer must never block on diagnostics |

The research table and measured conditions are at
`docs/prds/sci-execution-runtime/research/render-pipeline-design-2026-07-29.md`.
Current concrete taps and inputs are visible at
`src/seon/cluster.clj:1119-1148` and `src/seon/render/web.clj:551-671,692-804`.

Never use a channel for state recovery. If dropping the value makes reconnect
or restart incorrect, commit the required identity/receipt/final value as a
database fact.

## The http-kit write-state interface

Stock http-kit `send!` reports whether the channel remains open; it does not
mean the socket has drained. The maintained fork exposes write state with
pending bytes and a completion that settles drained or closed
(`reference-code/http-kit/src/org/httpkit/server.clj:321-326`).

Fresh Seon reads this state after each patch and joins the drain completion on
its `:io` virtual thread (`src/seon/render/web.clj:701-725`). Parking here
applies real socket backpressure without blocking a compute executor.

If the fork interface changes, verify both sides: the Clojure wrapper and the
Java channel/write-state implementation under `reference-code/http-kit/`.

## Measured frame budgets

The July 29 render falsifier ran the JVM and browser extensions twice on
OpenJDK 26.0.1 with 18 available processors and headless Chrome
150.0.7871.187 using Datastar 1.0.0-RC.7. Browser morph timing excluded
transport and ended at the `MutationObserver` microtask. It measured:

| probe | p95 |
|---|---:|
| 250-event block, Chrome morph | 1.2–1.5 ms |
| 50-tab serialize-once + mult | 0.872–1.171 ms |
| 50 active-row server delta | 1.172 ms |
| 50 transcript-block server delta | 2.387 ms |
| 250-event server render + serialize | 0.847–0.965 ms |
| 2,500-block Chrome morph | 12.4–13.1 ms |
| 2,500-block server render + serialize | 9.506–10.436 ms |

At 5,000 blocks the measured path exceeded a 16 ms frame budget. At 50 tabs
and 60 updates/second, the active-row delta case measured about 1.25 MiB/s
versus 244.71 MiB/s for full-transcript delivery.

All values, sample counts, warm-ups, payload definitions, and raw tables live
in `docs/prds/sci-execution-runtime/research/render-pipeline-design-2026-07-29.md`.
The once+mult comparison's per-tab serialization baseline was
31.783–42.479 ms p50; do not misquote it as 53 ms.

These results support block-local morphing and shared serialization under the
tested conditions. They do not prove every page stays below 16 ms. Measure
changed-block count, serialized bytes, tab count, browser morph time, and
socket backlog for the actual page.

## Change checklist

1. Identify the stable block IDs and bytes.
2. Mark current snapshot behavior separately from target package behavior.
3. Serialize common data before the `mult`.
4. Give each tab a latest-wins buffer.
5. Park `:io` writers on actual drain state.
6. Keep durable settled output in the database; keep partials on channels.
7. Re-measure server serialization, browser morph, bandwidth, and stalled-tab
   memory after changing the protocol.
