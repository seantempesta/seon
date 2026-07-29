---
name: datastar-web-ui
description: "Work on Seon's current JVM Datastar web renderer and its narrow live routes. Load this when editing seon.render.web or seon.render.*, diagnosing /, /agent/{id}, /data, /feed/{id}, message submission, block morphs, SSE backpressure, or render cost. Also load it before proposing broader UI work so you do not mistake the deleted CLJS pod or the tabled restoration design for a usable surface."
---

# Datastar web UI

The active web UI is the cluster JVM renderer in `src/seon/render/web.clj`.
It is not the deleted CLJS pod, and it does not use a fixed port.

Discover the URL from the cluster advertisement:

```text
bin/seon status
bin/seon open NAME
```

Boot writes the actual bound URL and port into the advertisement at
`src/seon/cluster.clj:900-922`. The server derives a preferred port from the
cluster name, falls back to an ephemeral port on collision, and reports the
bound result (`src/seon/render/web.clj:850-953`). Never hard-code 7890.

## What is built now

The one Ring dispatcher currently serves:

| method/path | behavior |
|---|---|
| `GET /` | the selected cluster agent page |
| `GET /agent/{id}` | one agent page |
| `GET /feed/{id}` | that agent's Datastar SSE feed |
| `POST /agent/{id}/message` | commit one inbound message |
| `GET /data` | database/schema drill page |
| `GET /css/*`, `GET /js/*` | packaged resources |

Verify these exact branches at `src/seon/render/web.clj:734-840`. Reitit,
database-backed route facts, debug pages/feeds, `/call`, agent creation,
stop/resume controls, and a generalized action system are not current routes.

## The one live render path

The cluster graph owns one `:io` render proc. It consumes database transaction
reports and streamed partials, derives complete page snapshots, suppresses
unchanged bytes, and publishes through a `mult`
(`src/seon/render/web.clj:1-45,360-480`).

Each tab:

1. receives a full initial paint;
2. owns a `(sliding-buffer 1)` tap;
3. compares the newest complete snapshot with what that tab last delivered;
4. sends one Datastar patch per changed block; and
5. parks on http-kit's real write-drain state.

Read `src/seon/render/web.clj:229-285,502-608`. Complete snapshots make
sliding-1 loss safe: a displaced snapshot is superseded by a newer complete
answer.

The cluster's one Datahike listener offers render wakes
(`src/seon/cluster/wake.cljc:156-217`). The current renderer receives every
transaction report; equality suppression, not query-interest derivation,
filters unchanged pages.

## Keep human input stable

The message bar and hidden feed opener are stable surfaces in the page shell
(`src/seon/render/web.clj:119-180`). The POST returns no paint; committing the
message wakes the ordinary render path (`src/seon/render/web.clj:687-717`).

Use Datastar's colon-form event attributes such as `data-on:submit`. Keep
transient input in signals and durable messages in the database. Do not add an
action-specific refresh channel.

## Verify the stream and socket

A browser-control bridge may return 503 or fail to hold a long-lived SSE
connection. Use the browser for layout, stable IDs, form behavior, and console
errors. If liveness is ambiguous, verify `/feed/{id}` with a server-side HTTP
client and inspect the selected cluster log with:

```text
bin/seon logs NAME
```

The maintained http-kit fork exposes pending bytes plus drained/closed
completion at
`reference-code/http-kit/src/org/httpkit/server.clj:321-326`. The writer reads
that state at `src/seon/render/web.clj:502-528`; do not infer drain from
`send!` returning an open channel.

## Mark target UI explicitly

The broader UI restoration is **TABLED** by ruling 12 until context rendering
is understood (`docs/prds/sci-execution-runtime/plan/README.md:1087-1097`).

Treat these as design inputs, not current APIs:

- agent-owned `::renders`;
- generalized surfaces/canvas controls;
- pure handler values and a `/call` boundary;
- database-derived route trees;
- revisioned packages, deltas, and keyframes; and
- historical/as-of feeds and debug pages.

Read `docs/seon/architecture/ui.md` for the target and
`docs/prds/sci-execution-runtime/plan/ui-conversion-plan-2026-07-29.md` for the
falsified conversion plan before proposing work. The architecture document is
target-written; confirm current source before using any named mechanism.

## Design and measurement

Keep stable block IDs, semantic hiccup, server-rendered content, and the
Phosphor theme. The maintained tokens are in
`resources/public/css/input.css`; deeper visual rules remain in
`references/design-principles.md`.

For protocol and performance work, load
`seon-flow-architecture/references/render-delivery.md`. It separates the live
snapshot/per-tab-delta implementation from the target package/keyframe design
and records the 60 fps probe conditions.
