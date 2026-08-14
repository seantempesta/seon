---
type: research
status: complete
tags: [research, render, web, ui, wave/live-drive-render, wave/visual-qa]
---

# Web UI verification — 2026-08-14

Browser walk of both live targets, page by page, with screenshots taken at
1280x720 (screenshots cannot be committed, so each is described precisely
below). This is the BEFORE record and the acceptance baseline for the lane
currently improving the agent session surface.

## Targets

| Target | Origin | State |
|---|---|---|
| Drive-session cluster | `http://127.0.0.1:55156` | Drive 1 attempt 5; agents `drive-one-agent-attempt-5`, `root` |
| Shared default | `http://127.0.0.1:7994` | Rebooted onto HEAD; answered at 12:0x; agent `root` |

No JavaScript console messages were produced on ANY page of either target
(`read_console_messages` returned "No console logs." every time). Every defect
below is a server-side render defect, not a client script failure.

## Verdicts

| Target | Page | Verdict |
|---|---|---|
| Drive | `/` (root) | renders — ugly |
| Drive | `/agent/drive-one-agent-attempt-5` | renders — ugly, and the session's turns are NOT visible |
| Drive | `/agent/drive-one-agent-attempt-5/debug` | renders — the session IS visible here; one wrong-identity render |
| Drive | `/data` | **broken — HTTP 500** |
| Default | `/` (root) | renders — ugly |
| Default | `/agent/root` | renders — ugly (byte-identical to `/`) |
| Default | `/agent/root/debug` | renders — ugly |
| Default | `/data` | **broken — HTTP 500** |

## The owner's question: can a reader see the session's turns?

**On the agent page, no.** On the debug page, yes.

`/agent/drive-one-agent-attempt-5` contains 38 walk units and 16,266
characters of text. Of that, roughly 14,000 characters are the toolkit
namespace schema wall (`my.background`, `my.edit`, `my.fs`, `my.message`,
`my.note`, `my.plan`, `my.run`, `my.shell`, `my.web`, `seon.bootstrap`,
`seon.db`) — schema declarations and `register!` forms. The actual session
appears only at the very end, as exactly four blocks: two message blocks and
two run blocks. The two run blocks are, in their **entirety**:

```text
Run bootstrap:drive-one-agent-attempt-5, opened #inst "2026-08-14T11:25:09.152-00:00". It completed.
```

```text
Run a887d305-c8ae-4b6e-842f-43287f7f7496, opened #inst "2026-08-14T11:28:56.845-00:00". It did not run: The reply carried no Clojure forms — its whole text read as prose. Prose runs nothing and settles nothing; write the Clojure you want evaluated. Nothing was retried, and nothing it asked for ran.
```

That is the whole session on that page. The generated opening's forms do not
appear. No form source, no returned value, no printed output, no receipt, and
no model reply text exists anywhere in the page's 16 KB. A reader cannot see
what the agent was shown, what it ran, or what it said.

The same session IS legible on `/agent/drive-one-agent-attempt-5/debug`: the
`:seon.render/ai` pane holds 34,964 characters over 30 `my.agents.…=>`
prompts with their results — the generated opening's pulls and `dir` calls,
then the two run pulls, then the inbound message. So the facts exist and one
projection renders them; the agent page's HTML projection does not descend
into them.

## Per-page detail

### Drive `/` — renders, ugly

Screenshot: dark page. Top-left a small `agent` / `debug` link pair, then a
full-width message input reading `message agent root …` with an amber `send`
button, then a right-aligned `show everything` checkbox. Below, a two-column
grid occupying only 787 px of the 1280 px viewport (columns measured 525 px
and 262 px), leaving the right ~38% of the window empty. The left column
holds a `FLEET` table (columns AGENT / STATE / CURRENT RUN / EPISODE /
MAILBOX / TURN BUFFER) with rows `drive-one-agent-attempt-5 · parked · — · 1
· 0/1 · 0/1` and `root · parked · — · 9 · 0/1 · 0/1`, and a `PLUMBING PASSES`
footer. The right column is a vertical stack of small boxed panels, several
of which read only `renderer unavailable`, and several of which are walls of
escaped EDN beginning `"[:div {:id \"seon-value-94bcc65de2accc0f2b833871\",
:class \"seon-data-panel\"} nil nil [:details …` — Hiccup printed as text.

Measured: 138 walk units is the default figure; here 67 occurrences of
`renderer unavailable`, 17 walk units whose entire visible content is a
printed Hiccup vector, `scrollHeight` 1050 px, no horizontal body scroll.

### Drive `/agent/drive-one-agent-attempt-5` — renders, ugly, session invisible

Screenshot: same header and input (`message agent drive-one-agent-attempt-5
…`). Below, the two-column grid. The left column is almost entirely EMPTY
except a single `renderer unavailable` chip at the top — a tall blank region
down the left 40% of the page. The right column carries the session content
in narrow ~262 px boxes: a run block whose text visibly stops mid-sentence at
"Nothing was retried, and nothing it asked for" with the remainder cut off
inside the box, then a message block ("From outside this cluster to
drive-one-agent-attempt-5: Author and follow one my.plan for this task…")
also cut off mid-sentence, then the bootstrap run block, then a second
message block. Visual order is newest-first (CSS `order` reverses DOM order).

Measured: 38 walk units, 17 `renderer unavailable`, 0 Hiccup-as-EDN units,
and **11 of 38 units clipped** — `max-height: 160px` with `overflow: hidden`,
so e.g. the `my.background` unit holds 800 px of content in a 160 px box with
no scrollbar and no elision marker. Text 16,266 chars.

### Drive `/agent/drive-one-agent-attempt-5/debug` — renders, session visible

Screenshot: two labelled panes side by side, `:seon.render/ai` on the left
(639 px) and `:seon.render/html` on the right. The left pane is a monospaced
REPL transcript starting with `captured`, then repeated
`my.agents.drive-one-agent-attempt-5=> (db/pull db (quote [*]) [:seon.cluster.run/id "bo`
— every line visibly cut at the pane's right edge mid-token (`"bo`, `"defa`,
`"de`, `instructio`), because the `<pre>` has `white-space: pre` and never
wraps. The pane scrolls horizontally (parent `overflow-x: auto`), but the
widest line measures **23,552 px** in a 615 px container. Interleaved are the
rendered results (`Cluster default.`, `Configuration default · manifest
637c5f03a6ad.`, the getting-started instruction with its fenced `(defn greet
…)` example, and the `dir` outputs for each toolkit namespace).

The right pane initially read `Loading the current HTML projection…`; it
populated within 5 s (not an infinite spinner) with the walk's HTML — `{} 4
keys`, `Cluster default`, the configuration table, then `renderer
unavailable` chips and the namespace blocks.

**Wrong-identity render (new defect).** In the left pane, verbatim:

```text
my.agents.drive-one-agent-attempt-5=> (db/pull db (quote [*]) [:seon.cluster.run/id "bootstrap:drive-one-agent-attempt-5"])
Run a887d305-c8ae-4b6e-842f-43287f7f7496, opened #inst "2026-08-14T11:25:09.152-00:00". It completed.
```

The form pulls `bootstrap:drive-one-agent-attempt-5`; the rendered value
names run `a887d305-…`. The timestamp and disposition belong to the bootstrap
run. The FIRST occurrence of the identical form, at the head of the same
transcript, renders correctly:

```text
Run bootstrap:drive-one-agent-attempt-5, opened #inst "2026-08-14T11:25:09.152-00:00". It is running now, held by 69568-1786706658408.
```

The database is not at fault — a live pull against the drive root returns:

```clojure
[{:seon.cluster.run/id "bootstrap:drive-one-agent-attempt-5"
  :seon.cluster.run/opened-at #inst "2026-08-14T11:25:09Z"
  :seon.cluster.run/closed-at #inst "2026-08-14T11:28:43Z"}
 {:seon.cluster.run/id "a887d305-c8ae-4b6e-842f-43287f7f7496"
  :seon.cluster.run/opened-at #inst "2026-08-14T11:28:56Z"
  :seon.cluster.run/closed-at #inst "2026-08-14T11:28:56Z"}]
```

### Drive `/data` — BROKEN

Screenshot: an otherwise entirely black page with one line of white
monospaced text at the top-left corner. No title, no header, no navigation,
no styling. The line reads:

```text
Effective config requires the projection handed to this operation.
```

HTTP status **500**, served in 0.36 s, reproduced twice.

### Default `/` and `/agent/root` — renders, ugly

Both URLs return byte-identical content (81,322 characters, 138 walk units) —
expected, since `root` is the cluster's root agent.

Screenshot: same layout as the drive root. `FLEET` table has one row `root ·
parked · — · 4 · 0/1 · 0/1`. The right column is a long stack alternating
`renderer unavailable` chips, `:seon.schedule.fire/id` maintenance-receipt
panels, and large escaped-EDN Hiccup blocks beginning `"[:div {:id
\"seon-value-c105392bd24b3269b7436365\", :class \"seon-data-panel\"} nil nil
[:details …`.

Measured: **69** occurrences of `renderer unavailable`, **29** walk units
rendering Hiccup as escaped EDN, and **55 of 138 units clipped** by the
160 px `overflow: hidden` box. Deep in the page the escaping compounds to
four levels (`\\\\\\\"`) inside one elision value.

The root agent's two most recent runs both refused honestly and are worth
recording as a live system observation (not a UI defect):

```text
Run 25c88c19-e483-40e9-b214-c6e650277bb3, opened #inst "2026-08-14T06:05:01.082-00:00". It did not run: At render distance 0 the prompt still needs 41577 estimated tokens against a 32768-token budget, calibrated at 3.06 characters per token from 1 recorded provider usage facts (worst observed miss 0.0%, so as much as 41577 tokens). It was not sent. Nothing was retried, and nothing it asked for ran.
```

```text
Run 8c22a7ff-a061-4b40-9792-20b66874056d, opened #inst "2026-08-14T06:05:04.290-00:00". It did not run: At render distance 0 the prompt still needs 63558 estimated tokens against a 32768-token budget, calibrated at 3.06 characters per token from 1 recorded provider usage facts (worst observed miss 0.0%, so as much as 63558 tokens). It was not sent. Nothing was retried, and nothing it asked for ran.
```

The bound fired and named exactly what happened — that is the wanted
behavior. It does mean the shared default's root agent currently cannot take
a turn at render distance 0. Whether the Hiccup-as-EDN inflation contributes
to that 41,577/63,558-token figure is a HYPOTHESIS this walk did not test:
the defect was observed in the `/html` projection, and no probe confirmed it
reaches `/ai`.

### Default `/agent/root/debug` — renders, ugly

Screenshot: same two-pane debug layout. Left `:seon.render/ai` pane 60,776
characters of `my.agents.root=>` transcript, again cut at the right edge
mid-token. Right `:seon.render/html` pane populated with 81,290 characters.
Widest `<pre>` line again **23,552 px**.

### Default `/data` — BROKEN

Identical to the drive cluster: HTTP **500** in 0.34 s, body exactly
`Effective config requires the projection handed to this operation.`

## Issues filed

New notes:

- [The /data route refuses because it never hands the projection](../../../seon/issues/the-data-route-refuses-because-it-never-hands-the-projection.md)
- [Walk units render their Hiccup as escaped EDN text](../../../seon/issues/walk-units-render-their-hiccup-as-escaped-edn-text.md)
- [Walk units hide their overflow instead of eliding it](../../../seon/issues/walk-units-hide-their-overflow-instead-of-eliding-it.md)
- [A run history entry can name a different run than its form pulled](../../../seon/issues/a-run-history-entry-can-name-a-different-run-than-its-form-pulled.md)
- [The agent page shows a run as one sentence and never its forms](../../../seon/issues/the-agent-page-shows-a-run-as-one-sentence-and-never-its-forms.md)
- [The debug AI pane never wraps](../../../seon/issues/the-debug-ai-pane-never-wraps.md)

Fresh evidence added to existing notes:

- [namespace-page-repeats-renderer-unavailable](../../../seon/issues/namespace-page-repeats-renderer-unavailable.md)
- [data-page-takes-five-and-a-half-seconds-for-three-kilobytes](../../../seon/issues/data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md)
- [agent-pages-overflow-a-phone-viewport](../../../seon/issues/agent-pages-overflow-a-phone-viewport.md)
