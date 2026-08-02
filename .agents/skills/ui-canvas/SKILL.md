---
name: ui-canvas
description: "Assess or design an agent-authored canvas, dashboard, table, chart, button, input, toggle, form, my.canvas call, or generalized action surface. Load to determine whether a request belongs to the canvas/control boundary or to datastar-web-ui."
---

# Agent canvas — target boundary

Do not present the deleted `src-old/my/canvas.cljc` functions as current APIs;
the great-deletion boundary is explicit at `AGENTS.md:20-31`.
The fresh route owner and renderer implement namespace pages, debug pages, a
fixed message form, `/data`, SSE feeds, and static resources
(`src/seon/render/route.clj:5-27`, `src/seon/render/web.clj:1011-1220`). That
live surface is useful, but it is not a generalized agent-authored canvas or
control API.

Do not write or recommend executable calls such as:

- `my.canvas/show!` or `my.canvas/clear!`;
- `my.canvas/pinned`, `state`, or `save!`;
- `my.canvas/button`, `input`, `select`, `toggle`, or `form`; or
- a `/call` request.

The one live route table has no `/call` route
(`src/seon/render/route.clj:5-27`). Current interaction is the fixed
human-to-agent message form plus the page-local `showEverything` checkbox
(`src/seon/render/web.clj:132-169,1027-1037`), not a constructor API available
to agent code.

## What is built

The current JVM renderer provides:

- canonical namespace pages with root and agent aliases
  (`src/seon/render/route.clj:5-16`, `src/seon/render/web.clj:1074-1102`);
- namespace and agent debug pages showing the AI and HTML projections of the
  same visible walk (`src/seon/render/web.clj:428-441,1041-1072`);
- identified HTML walk units with stable morph wrappers
  (`src/seon/render/web.clj:241-269,300-350`);
- streamed partial presentation and per-tab changed-block Datastar morphs
  (`src/seon/render/web.clj:497-804`); and
- one admitted inbound message through `POST /agent/{id}/message`
  (`src/seon/render/route.clj:17-21`, `src/seon/render/web.clj:883-913`).

Context mining is no longer a prerequisite to use those surfaces: both AI and
HTML are assembled from the same walk membership and ordering seam
(`src/seon/render/walk.clj:693-876`), and the debug response renders both at
one database value (`src/seon/render/web.clj:1041-1072`). Load
`datastar-web-ui` for work inside that built boundary.

## What remains target-only

Mark each of these **[TARGET]** until current source contains it:

- generalized canvas/control constructors and their schema: current source
  exposes only the fixed message form and page-local checkbox
  (`src/seon/render/web.clj:132-169,1027-1037`);
- a guarded action value and `/call` handler: neither appears in the complete
  route table (`src/seon/render/route.clj:5-27`);
- agent-owned `::renders`: the current agent graph has only mailbox and turn
  procs (`src/seon/cluster/agent.clj:240-264`); and
- revisioned packages/keyframes: current delivery uses complete snapshots and
  per-tab comparison (`src/seon/render/web.clj:497-804`), while the intended
  package/keyframe protocol is explicitly **[TARGET]**
  (`.agents/skills/seon-flow-architecture/references/render-delivery.md:55-94`).

The `::renders` feasibility probe did not settle interest narrowness or a
bounded retained representation; read the method and unresolved contracts in
`.agents/skills/seon-flow-architecture/references/agent-graphs.md:130-179`
before designing that proc.

Do not revive an effectful eval helper that mutates runtime state or dispatches
HTTP from inside an agent evaluation. A future design must remain consistent
with the three surviving effect shapes recorded in `AGENTS.md:29-52`: pure
values interpreted by the run loop, genuine capability requests, and durable
facts committed by the system.

## Respond to a canvas request

1. State that no current generalized canvas/control API answers the request;
   cite the fixed live routes and controls at
   `src/seon/render/route.clj:5-27` and
   `src/seon/render/web.clj:132-169,1027-1037`.
2. Determine whether current derived namespace-page HTML can represent the
   requested output without new interaction
   (`src/seon/render/web.clj:300-350,1011-1039`).
3. If interaction is essential, record the requirement against the
   **[TARGET]** action/control boundary instead of inventing an endpoint.
4. If the owner resumes that boundary, re-read the current route, agent graph,
   render walk, and delivery owner before implementation.

Do not provide executable pseudo-examples using nonexistent functions. A data
sketch is acceptable only when labeled **[TARGET]** and tied to a settled
contract.
