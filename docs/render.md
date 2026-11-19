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
- **`:canvas` (Pure Self-Replacing Live Presentation + Datahike Time-Travel — For ALL Agents):** Full `100vh` flex shell with a non-overlapping bottom dock. Rendered by `((resolve render-fn) ctx)` — defaulting to `seon.render.ui/default-canvas [ctx]` reading `:seon.agent/canvas` (`{:title :summary :body}` set via `(user/canvas! ...)`) and falling back cleanly to the latest prose reply in consistent Phosphor typography, with a quiet collapsed `▸ Thinking & actions` pill above the response. Every update creates an immutable Datahike transaction; the top bar's **`◀ N / M ▶ · Live`** stepper moves `:seon.user/as-of-tx` to replay past Canvas states (and historical custom render-fn code) via `d/as-of` with zero separate undo stack. Live streaming arrives in `#seon-stream-unit` with full untruncated reasoning toggled by Datastar's client `$showThinking` signal and sticky auto-scroll.
- **`:workspace` (Traditional Chronological Chat + Live Context Tiles):** Left panel renders a calm chronological chat (user bubble -> collapsed `▸ Thinking & actions` -> clean editorial prose) with sticky auto-scroll on `.ws-timeline` and fixed branch REPL input; right panel renders live auto-discovered tiles from `@seon.ctx/ctx` and evals.
- **`:debug` (Zero-Cost Wire & System X-Ray):** Renders the exact wire request produced by `seon.render.ui/build-live-wire-request` + `seon.model/build-agent-wire-messages`, live `@seon.ctx/ctx` pull snapshot (`pattern` + EDN value), side-by-side **Human HTML vs Model Text** (`render/hiccup->text`) tile projections (showing colocated `{:mode :agent}` affordances), live `datahike.reads` read-sets per mounted unit, current-digest health/test verdicts, and 3-way branch diffs.

## 3. Single-Source Wire Format, XML Session Framing & Ephemeral Viewport (`seon.model`)

`seon.model/build-agent-wire-messages` powers both live LLM turns and Level 3 Debug with prefix-cache-friendly ordering:

1. **`system` message:** Byte-identical across turns (KV prefix cache anchor).
2. **Example / previous sessions (`:seon.agent/examples`):** Each finished session is framed in explicit XML user messages `<session id="example/<id>" kind="example" from="seon">` ... `</session>`, with its turns rendered in the exact same wire roles (`user`, `assistant` + `tool_calls` + `reasoning_content`, `tool` with `result/e<n>`) so the model learns patterns without confusing examples with live work.
3. **Live session opening:** `<agent id="<agent-id>" branch="<branch>" role="<role>" from="seon">` + `<walk-context>` containing the initial `render/hiccup->text` projection of discovered tiles.
4. **Append-only conversation turns:** Exact `user` (`<message ...>`), `assistant` (`content` + `reasoning_content` + `tool_calls`), and `tool` (`tool_call_id` + `result/e<n>` handle) messages.
5. **Current turn delta + ephemeral tail viewport screenshot:** Unread messages, walk diffs, and — only when a human tab is actively viewing that route — a single latest viewport screenshot attached at the very end of the final `user` message (`image_url` for vision-capable models), replaced every turn and never persisted in history.
