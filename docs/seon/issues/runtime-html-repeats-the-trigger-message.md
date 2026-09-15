---
type: issue
status: open
severity: friction
tags: [wave/ui-watchability, render, runtime]
---

# Runtime HTML repeats the trigger message

Observed on default, 2026-09-14, at `/ns/my.agents.juniper`.
The runtime block prints the complete fault notification after “Woke on”,
then prints the same notification again through the message render pair.
At 700 px this consumes two paragraphs with the same error identity and
signature before the reader reaches runtime state.

Evidence: `tmp/debug-product/panel-2-agent-700.png` and
`panel-2-agent-1440.png`, inspected during the debug-product lane.
The composing owner is `seon.render.transcript/render-runtime-html`:
its trigger summary and message pair both include message content.

Render one short trigger label and one complete message pair. Preserve the
message's exact content in the pair; do not truncate or rewrite the stored
notification. The debug-product slice leaves runtime block internals to the
concurrently assigned HTML-views work.
