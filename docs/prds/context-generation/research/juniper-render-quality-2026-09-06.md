---
type: research
status: complete
tags: [research, context, render, web]
---

# Juniper render quality audit — 2026-09-06

This is a read-only audit of
`http://127.0.0.1:7773/ns/my.agents.juniper/debug?subject=32120` in the
`lab-run-inspection` cluster. It covers the actual found plan and message
values, not only the selected agent identity. No database or cluster state was
changed.

The existing browser probe completed in 1.1 seconds with 12 paired found-value
rows and no page errors. Fresh screenshots were captured at 1440 × 1100 and
390 × 844 viewports:

- `tmp/juniper-render-quality-desktop.png` — selected identity and graph;
- `tmp/juniper-render-quality-found-values.png` — the desktop found values;
- `tmp/juniper-render-quality-found-values-mobile.png` — the same rows at the
  mobile viewport;
- `tmp/juniper-plan.png`, `tmp/juniper-message-root-one.png`,
  `tmp/juniper-message-root-two.png`, and `tmp/juniper-failed-run.png` — exact
  element screenshots used below.

These are disposable measurement artifacts; the exact visible text is retained
here as the durable evidence.

## What is already truthful

The plan item carries distinct facts for title, id, description, and expected
result. The live HTML column contains:

> Make my plan and messages useful context  
> "juniper/understand-context"  
> Inspect the facts connected to my agent entity. Compare the AI and HTML
> renderings, then improve the functions with Sean.  
> Expected: Two clear blocks: the work I am doing and the new messages I should
> respond to.

That matches the semantic Hiccup already emitted by `render-item-html`: `h3`,
`.my-plan-id`, a description paragraph, and `.my-plan-expected`
([`src/my/plan.clj:901-914`](../../../../src/my/plan.clj)). The aggregate plan
renderer also distinguishes derived obligations, ready work, blocked work, and
recent completions in separate sections
([`src/my/plan.clj:973-1009`](../../../../src/my/plan.clj)).

The two root messages are also accurate:

> Agent root said to juniper: Please make your current plan and the messages you
> receive easy to understand together. Start by inspecting the data connected
> to your agent entity.

> Agent root said to juniper: Show Sean which function renders each block and
> an executable example of updating your plan. We will compare the assembled
> context before trying a live model turn.

The sender and recipient are resolved from the database rather than inferred
([`src/seon/cluster/message.clj:456-491`](../../../../src/seon/cluster/message.clj)).
The page also honestly shows three historical failed runs, including the
bootstrap `entry-source` refusal and two old prompt-budget refusals at 111138
and 112532 estimated tokens. Those rows are historical status evidence; they
do not prove that rendering is currently capped.

The column named **AI** is the exact agent-visible rendering produced from
stored facts. It is not an authored AI reply. For these rows, the authored
facts are the plan description, expected result, and message content. The
failed run rows contain status/refusal facts and no executed form or computed
result. A later successful run transcript may show stored form sources and
their actual results; those must remain distinct from message content and from
the renderer's AI projection.

## Concrete usability failures

### 1. Message HTML collapses attribution, content, and time

The live message HTML is visually one undifferentiated paragraph with a yellow
left rule. At 768 pixels the attribution consumes the beginning of every line,
so the instruction itself has no stable reading edge. The HTML is intentionally
implemented as the AI sentence inside one `p`
([`src/seon/cluster/message.clj:493-500`](../../../../src/seon/cluster/message.clj)).
Although `:seon.cluster.message/at` is required by the message schema, the HTML
does not expose it (`resources/seon/schemas/seon.cluster.message.edn:1-69`).

Recommended small change: keep `render-ai` byte-for-byte as the agent-facing
sentence. In `render-html`, derive a compact metadata line containing sender,
recipient, and time, followed by the stored `:seon.cluster.message/content` as
its own body paragraph. Use the current `.seon-message-entry` border, muted
`text-2xs` metadata, and cream `text-xs` body. “Outside this cluster” and
unresolved refs must retain their current honest labels. This changes only
presentation; it does not turn authored content into a result.

Owned paths: `src/seon/cluster/message.clj`, its focused renderer test, and
`resources/public/css/input.css`.

### 2. Plan HTML has semantics but no visual hierarchy

The plan screenshot shows the title, id, description, and expected outcome at
nearly the same weight and spacing. The expected outcome wraps as ordinary
body prose even though it is the completion criterion. There are no
`.my-plan-*` rules in the maintained stylesheet; only the generic family text
rules apply. The reverse `:my.plan.item/agent` row duplicates the anchor item,
but it is a genuine second database connection and is outside this renderer
layout recommendation.

Recommended small change: preserve the current Hiccup data shape and add scoped
Phosphor styling. Render the title in compact cream text, the quoted plan id as
muted monospace metadata, the description as the primary body, and the expected
result as a separated criterion line with an explicit “Expected” label. For
aggregate plans, apply the same rhythm inside the already-separated ready,
blocked, and completed sections. Do not add a status badge to an individual
item because the item value handed to this renderer does not itself declare
ready or blocked status.

Owned paths: `resources/public/css/input.css`; `src/my/plan.clj` only if a small
semantic wrapper or label is necessary; focused plan renderer tests.

### 3. Debug labels do not explain content provenance

Side-by-side **AI** and **HTML** is valuable for renderer comparison, but in
the found-value list the bare “AI” label can read as model-authored text. The
effect is strongest beside historical run rows, where the same refusal prose
appears in both columns and can be mistaken for a generated result. The actual
run screenshot says “It did not run” and contains no form/result, which is
truthful but visually indistinguishable from a message row.

Recommended small change: in the debug-only column heading, label the first
projection “agent-visible rendering” while retaining the underlying
`:seon.render/ai` identity in renderer details. Give message, plan, and run
families compact content labels from their existing semantic classes. For
successful transcripts, keep stored source and actual result as separate
entries; for failed runs, show a status/refusal label and do not invent an
“actual result.”

Owned paths: debug helpers in `src/seon/render/web.clj`, transcript presentation
only if its existing source/result classes prove insufficient, focused web
tests, and `resources/public/css/input.css`.

## Design constraints

The Phosphor rules call for the maintained warm palette, `text-xs` body,
`text-2xs` metadata, compact headings, tight padding, one-pixel borders, and
dot-plus-text status rather than pills
([`.agents/skills/datastar-web-ui/references/design-principles.md:35-80`](../../../../.agents/skills/datastar-web-ui/references/design-principles.md)).
The existing family and transcript CSS already provides the right base border
and direction semantics
(`resources/public/css/input.css:950-1079`). The proposed work should accrete
information hierarchy there instead of introducing another card or renderer
path.
