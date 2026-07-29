---
name: ui-canvas
description: "Assess or design Seon's agent-controlled canvas after confirming its current status. Load this whenever a task asks for my.canvas, dashboards, tables, charts, buttons, inputs, toggles, or forms: the old effectful API is deleted and the fresh canvas/action surface is tabled, so this skill prevents inventing a usable API and points to the required target design."
---

# Agent canvas — tabled target

There is no usable fresh `my.canvas` API. The old
`src-old/my/canvas.cljc` surface belonged to the deleted CLJS pod, and fresh
`src/` contains no replacement.

Do not write or recommend calls such as:

- `my.canvas/show!`;
- `my.canvas/clear!`;
- `my.canvas/pinned`;
- `my.canvas/state` or `my.canvas/save!`;
- `my.canvas/button`, `input`, `select`, `toggle`, or `form`; or
- a `/call` request.

They are not current capabilities. The old functions also mixed agent eval,
database mutation, rendering, and HTTP dispatch—the exact effectful shape the
fresh execution model is deleting.

## What exists now

The current JVM web renderer can:

- derive identified HTML surfaces from database facts;
- render a stable agent page;
- stream partial output over channels;
- morph changed blocks through Datastar; and
- accept a human message through `POST /agent/{id}/message`.

Read `src/seon/render/web.clj:1-45,119-180,229-285,687-840`. This narrow
surface is not an agent-controlled canvas and does not provide generalized
controls.

## Why restoration is paused

Ruling 12 tables proper UI restoration until context rendering is understood:
`docs/prds/sci-execution-runtime/plan/README.md:1087-1097`.

The required dependency order is:

1. mine the code graph end to end;
2. mine how the old context system assembled blocks, namespace source, and
   transcript;
3. settle context rendering with the owner; and
4. build UI on that settled derivation.

Do not bypass that order by reviving the old canvas because a task asks for a
dashboard.

## Target design, not current API

`docs/seon/architecture/ui.md` describes the aspirational render contract.
`docs/prds/sci-execution-runtime/plan/ui-conversion-plan-2026-07-29.md`
contains the filed, falsified conversion plan. Read both before designing
here, and label every proposed mechanism **[TARGET]**.

The intended direction is simpler than the deleted API:

- pure renderer code returns ordinary AI/HTML render values;
- durable domain facts remain in the database;
- ephemeral partial presentation remains on channels;
- a genuine user action eventually crosses one guarded capability boundary;
- control constructors return data rather than performing runtime mutation;
- the agent-owned render proc derives both AI and HTML views; and
- cluster delivery owns sockets and tabs.

None of the agent-owned `::renders` proc, generalized controls, guarded action
boundary, or `/call` route is built. The `::renders` feasibility probe also
left interest-narrowness and retained-memory contracts unresolved; read
`.agents/skills/seon-flow-architecture/references/agent-graphs.md`.

## Respond to a canvas request

When asked to build a canvas today:

1. State that the public canvas/control API does not exist.
2. Determine whether the request can be represented by the current derived
   HTML surfaces without new interaction.
3. If interaction is essential, record the requirement against the tabled UI
   plan rather than inventing an endpoint or effectful eval helper.
4. If the owner explicitly resumes the UI rung, begin from the architecture
   and conversion plan, then re-verify current source before implementation.

Do not provide executable pseudo-examples with nonexistent functions. A
target-shaped data sketch is acceptable only when clearly labeled
aspirational and tied to a settled design decision.
