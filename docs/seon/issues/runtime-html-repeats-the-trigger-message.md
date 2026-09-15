---
type: issue
status: resolved
severity: friction
tags: [issue, render, runtime, message, wave/ui-watchability]
---

# Runtime HTML repeats the trigger message

## Audit-1 source confirmation — 2026-09-15

At `0c70a1cb4`, `src/seon/render/transcript.clj:1133–1139`
(`runtime-message-link`) includes the first content line. At
`src/seon/render/transcript.clj:2139–2142`, the runtime block emits that
link after “Woke on” and then calls `message/render-html` with the same
trigger. The message pair at `src/seon/cluster/message.clj:382–431`
renders its complete content. A single-line fault notification is thus
duplicated in full. This confirms the source class without claiming a new
live browser observation. Estimated deletion: 5–15 lines; retain a short
trigger identity label and one complete message pair.

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

## Resolution — consolidate-debug

The resolving commit removes content from the trigger/history link and leaves
the complete notification to the message pair. A canonical runtime regression
requires the exact content once. Fast 11/188 and isolated 11/192 assertions pass.
Both Runtime screenshots show one short label plus one complete message, at
1440 and 700. Commit and screenshot log are recorded in the lane landing note.
