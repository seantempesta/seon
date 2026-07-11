---
type: research
status: active
tags: [research, agent, web, ui]
---

# Live context audit — what root actually sees + what's broken in the UI

> Read-only audit of the LIVE default cluster (pod 7890, `:seon.agent/id "root"`),
> 2026-06-28. No `src/` edits, no store mutations. Evidence = `ctx-preview` evals +
> gunzipped SSE feed bytes. A build agent was hot-reloading `seon.render` during the
> run; numbers are a snapshot of that moment.

## TL;DR

- Root's prompt is **17,649 tokens** across **6 blocks**. The `:namespaces` block
  alone is **9,566 tok (54%)** — and **45% of the ENTIRE prompt is two full-dumped
  reference namespaces the agent has never called**: `seon.agent.todo` (4,133 tok)
  + `my.kb` (3,881 tok). Meanwhile `my.agent.root` — the workspace the system block
  calls "the most important thing here" — is **68 tok (empty)**.
- There is **no `inventory-block` / curated `store-inventory` section** in root's
  live context. The inventory only appears as a one-off `(db/store-inventory)` eval
  RESULT in the transcript, because the creation turn happened to call it. Not a
  standing curated summary.
- **Markdown lane bug CONFIRMED from page bytes:** the agent's reply renders RAW
  (`**Node process**`, `` `SEON_EMBED` ``, `- ` bullets) in the world tile — markdown
  never converted to HTML on `/agent/root`.
- **Canvas default is wrong content:** the expanded canvas shows a generic "Good
  morning / still finding my purpose" welcome that NEVER shows the agent's actual
  latest reply, even right after the agent answered the human.
- **The agent fabricated figures to the human** ("1,234 datoms across 12 entity
  kinds and 47 registered attributes") — real inventory is **4 kinds**; the real
  numbers were sitting in its own transcript. Anti-hallucination guidance in the
  system block did not prevent it.
- `architecture.md` UI prose is **stale (task #9 confirmed)** — still describes the
  old `!last-tree` slot-tree-diff + browser `since-t` replay SSE model; the real
  model is gzip whole-element morph with no UI-side replay.

---

## Part A — Root's live context, block by block (in order)

`(seon.agent.inspect/ctx-preview {:seon.agent/id "root"})` → 6 sections,
`:seon.render/token-estimate` = **17,649** (chars/4 convention).

| # | block | chars | ~tokens | % budget | verdict |
|---|-------|------:|--------:|---------:|---------|
| 1 | `:system`      | 12,458 | 3,114 | 18% | high-value manual; large but earns it |
| 2 | `:soul`        |  7,735 | 1,933 | 11% | SOUL.md — matches the "~10%" prior flag |
| 3 | `:namespaces`  | 38,265 | 9,566 | **54%** | **DOMINANT — the bloat lives here** |
| 4 | `:live-tile`   |  2,770 |   692 |  4% | welcome default + "wire your own" |
| 5 | `:warnings`    |    444 |   111 |  1% | derived, healthy (shows a real failed-eval) |
| 6 | `:transcript`  |  8,522 | 2,130 | 12% | the live REPL history |

### Inside `:namespaces` (9,566 tok) — full-dumped, in render order

| namespace | ~tokens | note |
|-----------|--------:|------|
| `my.kb.shared`     | 1,264 | KB provenance schema |
| `seon.agent.todo`  | **4,133** | the todo toolkit — dumped in full every turn |
| `my.agent.root`    | **68** | the agent's OWN workspace — effectively empty |
| `my.kb`            | **3,881** | the KB manual ns — dumped in full every turn |

### Bloat flags (concrete)

- **`seon.agent.todo` (4,133 tok, 23% of the whole prompt)** is dumped in full on
  every turn. The agent calls maybe two of its verbs (`add!`, `done!`); it does not
  need the whole implementation rendered. This is the single biggest reducible item.
- **`my.kb` (3,881 tok, 22%)** — the manual ns — is dumped in full whether or not the
  agent ever consults it. This is the "unused `my.kb`" the prior DeepSeek observer
  flagged. Together with `todo`, **8,014 tok / 45% of the prompt is reference code
  the agent has not touched.**
- **The system block's own promise is inverted.** It says: *"YOUR OWN namespace
  renders in FULL — your live workspace, the most important thing here."* In reality
  `my.agent.root` is **68 tokens (empty)** — the "most important" block is the
  smallest, while two reference dumps it never called dominate the budget. The
  recency ordering even buries the agent's own ns in the middle of the dump.
- **`:system` (3,114 tok)** is not bloat per se (it is the operating manual) but it
  is the second-largest block; every sentence should pull weight. Two spots read as
  filler: the duplicated store-inventory guidance ("run `(db/store-inventory)` …
  your creation turn already did" appears twice — once mid-block, once under STANDING
  TEACHINGS).

### Gap flags (what root SHOULD see and doesn't)

- **No curated `store-inventory` / `inventory-block` section.** The owner asked
  whether "the new curated store-inventory summary is showing up usefully in
  inventory-block." It is **not a section at all.** The only inventory in root's
  context is the `(db/store-inventory)` RESULT embedded in the transcript, present
  only because the creation turn called it — it will scroll away and is not a
  standing, curated view. If a curated inventory summary is intended, it is not
  wired into root's block set.
- **No "how to present to the human" guidance with a worked example.** The
  `:live-tile` block says *"point `:seon.render.live-canvas/content` at your own fn"*
  and *"define a tile fn … that returns hiccup"* but gives **zero copy-paste hiccup
  example.** The presentation research (`agent-presentation-canvas-2026-06-28.md`)
  explicitly recommends adding 1–2 copy-paste hiccup examples to root's `:live-tile`
  ai block + the `welcome` ai text. Currently the agent is told to present richly but
  shown nothing concrete — and (Part B) the default tile doesn't even surface its own
  reply, so the agent has no example to imitate.
- **acme-fixture bloat (~20%, prior flag) is NOT present on root/default.** That
  bloat is specific to the acme harness cluster; on the default root agent the
  dominant bloat is `todo` + `my.kb`, not fixtures. The "~40% prompt bloat" figure
  decomposes here as: SOUL ~11% + `todo`+`my.kb` dumps ~45% (the acme ~20% piece is a
  different cluster).

---

## Part B — What isn't working in the UI (read-only)

`/world` and `/world/feed` render fine (roster: "1 agent", root ● idle). The breakage
is all on the agent world page. Evidence is gunzipped feed bytes from
`/agent/root/feed`.

### B1. Markdown lane bug — agent reply renders RAW (CONFIRMED)

The agent's reply sits in the live tile's compact reply div as literal markdown,
unconverted:

```html
<div class="seon-tile-reply text-xs text-text-300 whitespace-pre-wrap">Here's what my environment looks like right now …
**Node process** — I'm running in a Node.js process…
- `SEON_EMBED` (embedding-based retrieval) — off…
**Shared database** — the Datahike store has 1,234 datoms…
```

`**bold**`, `` `inline code` `` and `- ` list markers are shown verbatim — never run
through `md->hiccup`. Root cause (per `agent-presentation-canvas-2026-06-28.md`): the
world shim loads ONLY `datastar.js` (no `marked.js`), so the client-side
`data-markdown` lane has nothing to render it, and here the reply isn't even in a
`data-markdown` attribute — it's raw text in a `whitespace-pre-wrap` div. Fix =
collapse onto the server-side `seon.ui.markdown/md->hiccup` lane everywhere the world
page renders. **[needs the presentation build — already scoped in that doc]**

### B2. Canvas default never shows the agent's actual reply (CONFIRMED)

The expanded canvas (`seon-tile-expanded`) shows only the boilerplate welcome:

```html
<div class="seon-tile-expanded …"><div class="text-lg text-text-50">Good morning.</div>
<div class="… text-signal">Sunday, June 28 · 11:04 AM</div>
<div class="text-sm text-text-200">I'm still finding my purpose — tell me what you need.</div>
<div class="text-xs text-text-400 italic">I'll update this tile as I work …</div></div>
```

The human asked "how's your env like?" and the agent answered — but the focal canvas
still shows "still finding my purpose," never the answer. The reply appears only in
the compact view (B1, raw markdown). The presentation doc's fix (extend `welcome` so
the canvas renders the latest `:origin :agent`→user message as a markdown card) is the
right one. **[needs the presentation build — already scoped]**

### B3. Agent surfaced FABRICATED figures to the human (CONFIRMED)

The rendered reply tells the human: *"the Datahike store has **1,234 datoms across 12
entity kinds and 47 registered attributes**."* Live check:

```clojure
(count (db/store-inventory {:seon.db/system? true})) ; => 4 kinds (not 12)
```

`1,234 / 12 / 47` are fabricated round numbers — and the agent had the REAL
`(db/store-inventory)` result in its own transcript the same turn. The system block's
"REPORT THE VALUE YOUR LAST EVAL RETURNED" guidance did not prevent the hallucination.
**[needs doc/plan update — agent-behavior, not a UI bug]**

### B4. Eval-mechanics guidance not landing (corroborating signal, from transcript)

Root's `:warnings` block shows a real failed eval `pdI-2606281017`: *"READ ERROR — this
form did not parse … Unmatched delimiter: } at line 1, col 1."* The agent pasted a bare
`}` / data literal — exactly the "bare data literals do NOT evaluate" case the system
block warns about. The transcript is also littered with
`; [unverified narration — not a real result]` markers (the runtime flagging prose like
"Now: what do I actually have?"). The "THINK IN COMMENTS / correct-shape" guidance is
present but not fully landing. **[needs doc/plan update — system-block efficacy]**

---

## Part C — Doc-currency sweep

### C1. `docs/prds/agent-fsm/ui.md` — mostly CURRENT; stale by OMISSION

Good news: ui.md's page model (`#world-canvas` IS the live tile; transcript as a
priority-ordered supporting tile below; gzip whole-element morph; no UI-side `since-t`)
**agrees with** `agent-presentation-canvas-2026-06-28.md`. It is NOT describing a
superseded canvas/transcript model. What it LACKS (the presentation redesign adds these;
ui.md should gain a short subsection or xref):

- **No mention of the markdown LANE collapse.** ui.md never states that message/eval
  bodies must render server-side via `md->hiccup`, nor that the world shim loads no
  `marked.js`. The B1 bug is invisible in ui.md. *Fix:* add to the "world" page section
  a sentence that all transcript/tile bodies render server-side through
  `seon.ui.markdown/md->hiccup` (lane a), and that the client loads no markdown JS.
- **No typed `value → hiccup` renderer.** The presentation doc makes a one
  `message | data | source | error | hiccup` renderer first-class; ui.md's render-engine
  section doesn't mention it. *Fix:* add a line under "The render engine" pointing at the
  typed block renderer as the layer above `seon.ui.html`.
- **Canvas default behavior is under-specified.** ui.md line ~108–114 says the canvas is
  the live tile resolving `:seon.render.live-canvas/content`, but does not say what the
  default shows when an agent has none. The real default (`welcome`) doesn't surface the
  latest reply (B2). *Fix:* state the no-custom-tile default renders the latest agent→user
  message as a markdown card.

Net: ui.md is **current on structure, stale-by-omission on presentation** — needs three
small additions, not a rewrite.

### C2. `docs/prds/agent-fsm/architecture.md` — STALE (task #9 CONFIRMED)

The UI-domain prose still describes the OLD per-connection slot-tree-diff + browser
`since-t` SSE model. The real model (ui.md "The live channel"): gzip whole-element morph,
idiomorph diffs client-side, **no UI-side `since-t` replay** ("the first paint fires" on
reconnect). Stale lines:

- **L118–120** (the topology diagram): `listen! → derive world → slot-tree diff → push`
  and `wire (RPC + tx-feed, since-t replay)`. The browser path is no longer a slot-tree
  diff; it's a whole-element morph. (The *wire/replication* `since-t` at the pod↔writer
  layer, L39, is legitimate and should stay — distinguish the two.)
- **L162–168:** "re-derives the affected FRAGMENT, fast-hashes it, and pushes via datastar
  only if … the **tx feed** is a separate broadcast made lossless across reconnect by
  per-subscriber `since-t` replay." The browser stream is NOT fragment+hash+since-t; it's
  one whole-element morph per tx, idiomorph-diffed client-side.
- **L261–262:** "streams a per-connection `!last-tree` slot-tree diff, reconnect-lossless
  via `since-t`." `!last-tree` and browser `since-t` are gone.
- **L289:** "the SSE `!last-tree` live channel." Same — rename to the gzip-morph channel.

*Fix:* rewrite these four spots to match ui.md's "The live channel — gzip morph SSE"
(whole-element morph, idiomorph, no browser-side `since-t`), while preserving the
wire-replication `since-t` at L39 (that layer genuinely replays).

### C3. Other UI-lane doc hit

- `data-model.md` / `agent-runtime.md` were not deep-audited here, but `architecture.md`
  L261–263 advertises itself as owning "the SSE `!last-tree` live channel" — any xref to
  that phrase from other docs inherits the stale model. Grep `!last-tree` and
  `since-t` repo-wide when fixing C2 to catch sibling references.

---

## FLAGGED ISSUES (triage)

1. **`seon.agent.todo` full-dump = 4,133 tok / 23% of root's prompt.** Render a summary
   (verbs + signatures) instead of the whole ns; keep it queryable. **[well-scoped fix]**
2. **`my.kb` full-dump = 3,881 tok / 22%.** The manual ns is dumped every turn though the
   agent may never call it. Decide: summary, lazy/on-demand, or drop from the standing set.
   **[needs doc/plan update]** (validates task #22)
3. **System-block promise inverted:** "your own ns is the most important thing" but
   `my.agent.root` renders at 68 tok while two reference dumps dominate. Either stop
   full-dumping `todo`/`my.kb`, or soften the claim. **[needs doc/plan update]**
4. **No curated `store-inventory` / `inventory-block` section in root's context** — it
   exists only as a transient transcript eval result. If a standing curated summary is
   intended, it isn't wired. **[needs doc/plan update]**
5. **No copy-paste presentation example** in the `:live-tile` ai block / `welcome` ai text;
   agent is told to present richly but shown nothing. **[well-scoped fix]** (presentation build)
6. **Markdown lane bug (B1)** — agent reply renders raw on `/agent/{id}`; collapse onto
   server-side `md->hiccup`. **[well-scoped fix]** (presentation build, already scoped)
7. **Canvas default never shows the latest reply (B2)** — extend `welcome` in place to
   render the latest agent→user message. **[well-scoped fix]** (presentation build)
8. **Agent fabricated store figures to the human (B3)** — anti-hallucination guidance
   ineffective; consider auto-injecting a real inventory count instead of relying on the
   agent to quote it. **[needs doc/plan update]**
9. **Duplicated store-inventory guidance** in the `:system` block (twice). Trim one.
   **[well-scoped fix]**
10. **`architecture.md` stale SSE prose (C2)** — L118–120, 162–168, 261–262, 289 still
    describe `!last-tree`/slot-tree-diff/browser `since-t`. Rewrite to gzip-morph; keep
    wire `since-t` at L39. **[well-scoped fix]** (task #9)
11. **`ui.md` stale-by-omission (C1)** — add markdown-lane, typed-renderer, and
    canvas-default notes. **[well-scoped fix]**
