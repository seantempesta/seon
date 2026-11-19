# Rendering, 3-Level Progressive UI, and Single-Source Model Context

Seon renders HTML for humans and structured context for LLMs from ONE reactive walk (`seon.render`, `seon.render.ui`, `seon.model`), guaranteeing that what a human sees in the browser matches what the agent receives on the wire.

## 1. Incremental `datahike.reads` Walk & Reagent Event Wiring (`seon.render`)

`seon.render/step-walk` tracks per-component `datahike.reads/reads-index` read-sets so untouched components do zero work on commits or high-frequency streams (`0.02 ms/frame` measured `[M]`, >400x headroom inside an `8.33 ms` 120 fps frame budget).

- **Auto-injected stable IDs:** `render/ensure-root-id` injects a deterministic `:id` onto any mounted component's root element when omitted, so Datastar Idiomorph morphs cleanly without manual `:id` plumbing.
- **Fault isolation:** Every component in `step-walk` and every action in `seon.web/invoke-call!` runs inside a `try/catch` boundary; a broken render turns only its own tile into an inline red fault card without affecting sibling tiles or `:web`.
- **Standard Reagent events:** `:on-click`, `:on-submit`, and `:on-change` accept all four Clojure idioms (`#(...)` closure, unquoted `[fn args]`, bare `fn`/`var`, quoted `'(ns/fn args)`). Edge `POST /call` validates `^:seon/callable`, dispatches asynchronously onto `ui-ch`, and returns empty `200 text/event-stream` immediately while targeted DOM morphs coalesce at `8 ms` (`120 Hz`) over `/events` SSE.
- **Schema-attached tiles:** Schemas declare `{:seon.render/hiccup ns/fn}` directly in Malli (`:seon.health/report`, `:seon.code.ns/entity`, `:seon.code.var/entity`, `:seon.code.doc/entity`, `:seon.schema/entity`, `:seon.task/entity`, `:seon.agent/entity`). `render/discover-renderable-tiles` auto-discovers every renderable entity across `:seon.agent/pinned`, the agent's walk refs, and recent `result/e<n>` evals.

## 2. Inside-Out User Viewport (`:seon.user/*`), Recursive Agent Sidebar, & 3 Views (`seon.render.ui`, `seon.web`)

Where the user is (`:seon.user/viewing`, `:seon.user/view-mode`, `:seon.user/sidebar-open?`, `:seon.user/expanded-agents`, `:seon.user/selected-branch`) lives exclusively on `:user/default` in Datahike — never in ephemeral web memory. Restoring LMDB restores the user's exact screen, and any agent can programmatically move the user's view via `(user/show-user! {:viewing :app/billing :mode :workspace :sidebar? true})`.

- **Collapsible Recursive Left Sidebar (`#seon-sidebar`):** Walks `:seon.agent/parent` refs recursively (`root -> sub-agent -> sub-sub-agent`) with live pulsing status indicators (driven by `:exits` + `:stream` state transitions with zero DB seeks per streaming token), 1-click expand/collapse, and agent selection.
- **`:canvas` (Present-Moment Presentation View — For ALL Agents):** Calm warm Phosphor editorial presentation stage, not an endless chat scroll. Displays presented live Hiccup/SVG stage visuals (`:seon.agent/stage` via `(user/present! ...)`), live branch-isolated custom `:seon.agent/render-fn` previews (evaluated inside the branch's warm COW worker with 1-click `Merge → :main`), explicitly pinned tiles, and ONLY the present exchange with a quiet collapsible `▸ Thinking & actions` pill. Live streaming arrives in-place right above the dedicated sticky `#seon-composer-unit` with smart sticky auto-scroll that yields immediately when the user scrolls up manually.
- **`:workspace` (Two-Panel Agent Workbench):** Full chronological interleaved conversation + foldable `result/e<n>` eval timeline + live branch COW REPL on the left; scrollable auto-discovered live tiles from that agent's **branch DB** on the right.
- **`:debug` (Zero-Cost Wire & System X-Ray):** Renders the exact wire request produced by `seon.render.ui/build-live-wire-request` + `seon.model/build-agent-wire-messages`, live `@seon.ctx/ctx` pull snapshot (`pattern` + EDN value), side-by-side **Human HTML vs Model Text** (`render/hiccup->text`) tile projections (showing colocated `{:mode :agent}` affordances), live `datahike.reads` read-sets per mounted unit, current-digest health/test verdicts, and 3-way branch diffs.

## 3. Single-Source Wire Format, XML Session Framing & Ephemeral Viewport (`seon.model`)

`seon.model/build-agent-wire-messages` powers both live LLM turns and Level 3 Debug with prefix-cache-friendly ordering:

1. **`system` message:** Byte-identical across turns (KV prefix cache anchor).
2. **Example / previous sessions (`:seon.agent/examples`):** Each finished session is framed in explicit XML user messages `<session id="example/<id>" kind="example" from="seon">` ... `</session>`, with its turns rendered in the exact same wire roles (`user`, `assistant` + `tool_calls` + `reasoning_content`, `tool` with `result/e<n>`) so the model learns patterns without confusing examples with live work.
3. **Live session opening:** `<agent id="<agent-id>" branch="<branch>" role="<role>" from="seon">` + `<walk-context>` containing the initial `render/hiccup->text` projection of discovered tiles.
4. **Append-only conversation turns:** Exact `user` (`<message ...>`), `assistant` (`content` + `reasoning_content` + `tool_calls`), and `tool` (`tool_call_id` + `result/e<n>` handle) messages.
5. **Current turn delta + ephemeral tail viewport screenshot:** Unread messages, walk diffs, and — only when a human tab is actively viewing that route — a single latest viewport screenshot attached at the very end of the final `user` message (`image_url` for vision-capable models), replaced every turn and never persisted in history.
