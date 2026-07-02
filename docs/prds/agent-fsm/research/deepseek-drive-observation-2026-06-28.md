---
type: research
status: active
tags: [research, agent, ui]
---

# DeepSeek drive observation — "build a todos tile" (agent zeG-2606272150)

> Independent experience observation (Lane-U). The observer did NOT drive the
> agent. Source bytes: the verbatim turn-0 prompt (`deepseek-drive-prompt.txt`,
> 19.5k tok), all 64 evals (`deepseek-drive-evals.txt`), the final-turn prompt,
> and the gunzipped world page (`deepseek-drive-ui.html`). Every claim below
> cites a prompt line, an eval id, or an HTML node.

## TL;DR

The agent SUCCEEDED at the buildable half and the new UI carried it cleanly:
it discovered the tile contract by reading source, wrote a correctly-specced
`my-todos-tile` fn (eval `msi`), wired it with one raw transact to
`:seon.render.live-tile/content` (eval `WgQ`, first try, no error), and that fn
is the focal `#world-canvas` on `/agent/zeG-2606272150` — rendering "todos /
0 open / 2 done". **The live-tile-as-canvas mechanism is well-taught and worked.**

But the agent **could not fulfil the human's literal ask — "lets me add a new
one"** — because the live-tile contract is read-only hiccup with NO taught
interactivity story, and it burned roughly 25 evals discovering that dead end
before settling for a tile that says `message me: "add todo: <title>" to add`.
The 20 error rows are 65% one class (bare `}` / prose-pasted-as-code, 13/20) the
context already warns about and the agent eventually self-corrected; the rest are
a guessed `:seon.fn` identity attr (2) and `\|` regex-escape (5) — both real
context gaps. **#19 verdict: KEEP** — the agent never once confused live-tile vs
ctx-block (0 mentions of canvas/slot/block/install across 64 evals); it reached
for the live tile exclusively. The only canvas-vs-block artifact is a HUMAN-facing
phantom "Acme canvas" tile from stale acme code, not agent confusion.

**Biggest single lever: build `my.tile` (esp. an interactive `show!`/action
primitive). It does not exist in the live system; its absence is what turned a
~5-eval task into a 64-eval one.**

## 1. Prompt clarity — legible, but 40% of it is dead weight for this task

The assembled prompt is **19,540 tok**. It is well-organized and the
load-bearing mechanics are genuinely good. Token budget by section:

| Section | tok | Pulled weight for THIS task? |
|---|---:|---|
| system message (L1-215) | 3116 | YES — eval mechanics, errors-are-values, render twins |
| soul (L219-275) | 1965 | NO — identity/values, zero task relevance |
| acme.* fixtures (L279-475) | 2220 | NO — bug-reproduction cruft (see below) |
| seon.agent.todo (L476-656) | 1929 | YES — the exemplar store/retrieve ns |
| my.agent.vKt broken (L657-667) | 117 | NO — another agent's schema-error stub |
| my.kb DB manual (L668-967) | 3956 | PARTLY — heavy; agent never used it |
| my.kb.shared (L968-1081) | 1292 | NO |
| acme.pod + acme.world (L1082-1225) | 1713 | NO — fixture wiring |
| live-tile + todos + relevant-source (L1229-1390) | 2018 | YES — the 10% that actually drove the build |
| stubs + inventory + transcript | 344 | mixed |

**(a) Define a fn with `:malli/schema` — well-taught.** The agent copied the tile
contract verbatim from source it read: `value-explorer-view`/`progress-view`/
`status-view` all carry `{:malli/schema [:=> [:cat :seon.render/system-input]
:seon.render/html-response]}` (eval `Qey`, the full `seon.web.tile` render), and
the agent reproduced it exactly (eval `msi`, verified at eval `kix` →
`[:=> [:cat :seon.render/system-input] :seon.render/html-response]`). Discovery
worked: it learned the schema by reading, not from the prompt.

**(b) Wire a live tile — well-taught, first-try success.** The `live-tile`
section is unambiguous (prompt L1237-1244): *"define a tile fn in YOUR OWN
namespace that returns hiccup and transact its qualified symbol ... onto that
attr."* The agent did exactly that (eval `WgQ`) and got
`{:seon.db/ok? true ... :seon.db/added 8}` with no error. This is the strongest
positive signal in the run.

**(c) Toolkit verbs — the catalog the agent was promised does NOT exist.**
`toolkit.md` describes `my.todo`, `my.tile`, `my.search`, etc. The live system
renders **`seon.agent.todo`** (not `my.todo`), the agent searched with floor
**`seon.agent.search/grep`** (not `my.search`), and **`my.tile` is entirely
absent** from the loaded namespaces. The agent used `seon.agent.todo/complete!`
correctly (it was in context) but had to hand-roll a tile because no `my.tile/show!`
exists. The toolkit catalog is an aspirational TARGET, and the gap between it and
the live floor is the run's central cost (see §5).

**Noise that actively confused the surface:** the acme fixture namespaces (≈3933
tok, 20% of the prompt) carry docstrings narrating their own bug numbers INTO the
agent's context — e.g. acme.notes (prompt L324-331): *"This is the BUG-C
reproduction case"*, acme.helpers (L302-308): *"that gap is why a tile ... falls
off the SCI-bounded path onto the unbounded compiled path (BUG A)"*. An agent
reading "BUG-C reproduction case" in its OWN workspace context is being shown
test-harness internals as if they were its tools. Acme-specific, but it exposes
that the "render `my.*` and downstream FULL" rule has no lever to exclude harness
fixtures.

## 2. The 20 errors — 13 agent-slip, 7 context-gap

Exact breakdown (`grep "!! ERROR"` = 20):

| Class | Count | Verdict |
|---|---:|---|
| Unmatched delimiter `}` (prose/map pasted as code) | 13 | mostly AGENT SLIP — context warns, agent self-corrected |
| Unsupported escape `\|` (regex in grep pattern) | 5 | CONTEXT GAP — floor verb's pattern contract invisible |
| Lookup ref not `:db/unique` (`:seon.fn/name`) | 2 | CONTEXT GAP — fn identity attr undiscoverable |

**Class 1 — bare `}` / prose-as-code (13/20, 65%).** The agent repeatedly quoted
prior result maps in its narration without `;` prefixes, e.g. eval `HeW` narration
`=> {:seon.agent.search/ok? true` → the runtime correctly flags the opening map
as a note (*"⚠ Read as a note, not code: vector. Only forms beginning with ( are
evaluated"*) but the dangling closing `}` becomes the next input and hard-fails:
eval `EER`, `VPl`, `Aoc`, … → *"READ ERROR — this form did not parse, so it
DEFINED NOTHING. Unmatched delimiter: } at line 1, col 1"*. The context DOES warn
(system prompt L49-58 "THINK IN COMMENTS … put `;` in front of every line",
L30-41 the Correct/Wrong shape example, L34 "a bare data literal you paste
({...}) … do NOT evaluate"). The agent eventually internalized it on its own
(eval `oJd`: *"I'll keep ALL prose as `;` comments only"*; eval `XNH`: same). So
this is **primarily a DeepSeek discipline slip, not a missing teaching** — but 13
wasted evals is a lot of friction. → Core (eval ergonomics): when a chunk is a
bare unmatched closing delimiter immediately following a note-classified literal,
absorb it into the same note instead of emitting a scary "DEFINED NOTHING" READ
ERROR. The teaching is fine; the failure mode is too punishing.

**Class 2 — `\|` regex escape (5/20).** The agent reached for alternation in grep
patterns five times: eval `(none-id)` L162 `"defn.*add.*todo\|defn.*todo.*add\|defn.*new.*todo"`
→ *"Unsupported escape character: \|"*; again at L244, L340, L532, L581. Root
cause: `seon.agent.search/grep` is a FLOOR verb (not rendered in context), so its
pattern-is-a-string-becoming-a-regex contract is invisible — the agent guessed
that a Clojure string regex needs `\|`. It learned by trial (eval `Mbc`: *"The
pipe character in grep patterns also breaks. Let me search without pipes."*).
**Pure context gap.** → Core: teach grep's pattern contract ("alternation is a
plain `|`, no backslash; the pattern is a ripgrep string") in the one discovery
example that already appears at system-prompt L115, OR surface a one-line grep
signature.

**Class 3 — `:seon.fn/name` lookup-ref (2/20).** Eval `CSC`:
`(seon.db/pull '[*] [:seon.fn/name "todos-view"])` → *"Lookup ref attribute should
be marked as :db/unique: [:seon.fn/name \"todos-view\"]"*; repeated eval `zBW`
with the fully-qualified name. The agent correctly knew the "pull by lookup-ref
[identity-attr value]" pattern (it's taught in `my.kb/source-detail`, prompt
L913-921) but **did not know the fn entity's identity attr is `:seon.fn/sym`, not
`:seon.fn/name`** — that's nowhere in context, and the error message names the
defect without naming the fix. The agent routed around it via
`(seon.agent.ctx/render-namespace {:seon.ns/name :seon.web.tile})` (eval `FHo`,
worked) — so the whole-ns read path saved it. **Context gap.** → Core: either the
lookup-ref error should suggest the correct identity attr, or the relevant-source
header (which already says "pull the full row with `(seon.db/pull '[*] <id>)`",
prompt L1261) should show the actual fn identity attr.

## 3. Discoverability of the new UI + the #19 duality — clean for the agent

**The agent operated entirely through the live tile and never touched
ctx-blocks/slots/world-layout.** Across 64 evals there are **zero** mentions of
`canvas`, `world-layout`, `slot`, `:seon.agent.ctx/name`, or `ctx/install!`
(`grep -ic` = 0). Its mental model was clean and correct: it read
`seon.agent.ctx.live-tile/live-tile-block` (eval `RYT`/`Drn`), summarized it
accurately — *"A tile fn takes a system-input map ... and returns
`{:seon.render/hiccup ... :seon.render/ai ...}`"* (eval `Lyo` narration) — defined
its fn, and wired the ONE attr. It never tried to install a block, never confused
a block with the tile, never looked for a "canvas" to write to.

So the duality from decision #19 (**canvas = live tile, NOT a ctx-block**) **did
not trip this agent at all.** The agent saw the acme stub blocks in its context
(prompt L219-222 `:canvas`, L1391-1404 `:acme-tile`/`:acme-widget`/`:acme-broken`)
but treated them as opaque and irrelevant, which is the correct read.

One real cost the duality DID produce, but it is HUMAN-facing, not agent-facing
(routed to U §below): the rendered page has TWO things called "canvas" —
`#world-canvas` (the live tile, correct) AND `#world-tile-canvas`
(`data-slot="canvas"`, the acme `:canvas` block rendered as an ordinary tile in
the scroll). acme.world's docstring still claims a block named `:canvas` becomes
the focal canvas (`(some (set names) [:canvas :transcript])`, prompt L1144-1147),
but `seon.ui.world/world-layout` no longer honors that — #19 made the canvas
ALWAYS the live tile, so the acme `:canvas` block now renders as a confusing
phantom "Acme canvas" tile. That is stale acme code colliding with the post-#19
world-layout, not the agent being confused.

## 4. The UI result — correct render, but the human's ask is half-met

Verified against `deepseek-drive-ui.html`. Page structure (by id):

- `#world-canvas` → the agent's `my-todos-tile`: header "todos", "0 open",
  "2 done", then the two completed task-messages struck through with ages
  (13m / 6m). Renders correctly, themed, container-query compact+expanded blocks
  present. **This is exactly what should be there.**
- `#world-tiles` scroll: `world-tile-canvas` (phantom "Acme canvas", see §3),
  `world-tile-soul`, `world-tile-acme-tile` ("Acme world tile"),
  `world-tile-acme-widget` ("Acme dashboard"), `world-tile-acme-broken` ("Acme is
  preparing this view…" — the broken-tile→error-response override, working),
  `world-tile-transcript`.

**The usability gap:** the human asked for a tile that "lets me add a new one,"
and the rendered tile has **no add affordance** — no input, no button, just the
expanded-block text `message me: "add todo: <title>" to add` (the agent's own
fallback, eval `msi` hiccup; confirmed in the HTML). The agent was honest about
this in its close-out message (eval `gPB`): *"I can also wire an inline add button
once I find the right action-registration API."* The render is clean; the
deliverable is incomplete, and the cause is the Core interactivity gap (§5).

Render/layout issues to route to U:
- **Phantom second canvas** (§3): world-layout should skip a block literally named
  `:canvas` (canvas is the live tile now) or acme.world should be updated; the
  page should not show two "canvas" surfaces.
- **html-only blocks render as meaningless agent-context stubs.** The acme blocks
  carry `:seon.render/html` but no `:seon.render/ai`, so in the agent's TEXT
  prompt they appear as bare `{:db/id 2381, :seon.agent.ctx/name :canvas}` stubs
  (prompt L219-222, L1391-1404) — 4 near-empty noise sections. world.cljs already
  states "Blocks with only an ai render (prompt-only) contribute no tile"; the
  inverse should hold — an html-only block should contribute NO prompt section,
  not a stub.

## 5. What's MISSING — one addition that would have collapsed the run

**Build `my.tile` with an interactivity primitive, and teach the live-tile
interactivity boundary in the contract.** This is the single highest-value change.

The agent's task was 90% spent discovering that live tiles can't be interactive.
It found the OLD system's `action-form-view` (eval `oOD`) and `handle-action!` /
`!action-handlers` (eval `Pdw`), correctly diagnosed that they POST to
`/api/agent/:id/actions` but are **private to `seon.web.tile` with no public
registration API** (eval `qyN`: *"The action-handlers atom is private ... There's
no public registration API exposed. So for the live-tile contract, interactivity
isn't directly supported yet"*), and gave up on the "add" affordance. That entire
arc (~25 evals, evals `FPI` through `qyN`) is the cost of an unbuilt capability
plus a silent contract boundary.

Two concrete fixes, either of which pays for itself:

1. **(preferred) Build `my.tile/show!` + an action/form primitive** so a tile can
   emit a button/input that calls back into the agent (message or fn). The
   `toolkit.md` catalog already designs `my.tile/show!`; shipping it — with one
   prebuilt interactive view — turns this whole task into ~5 evals.
2. **(minimum) State the boundary in the live-tile contract**: "live tiles are
   read-only rendered queries; for human input, render an instruction to message
   you." One sentence in `seon.render.live-tile`'s ns doc would have saved the
   agent 25 evals of dead-end spelunking.

## Routed findings

### → Core (prompt / context / toolkit / schema-teaching / eval-ergonomics)

1. **`my.tile` (esp. interactive `show!`/action) is unbuilt** — the run's central
   cost; the human's "add" ask was unmeetable. Build it, or at minimum document
   the live-tile read-only boundary in `src/seon/render/live_tile.cljs`'s ns doc.
   (§5)
2. **Toolkit catalog ≠ live floor.** `toolkit.md` promises `my.todo`/`my.search`/
   `my.tile`; the live system renders `seon.agent.todo` + `seon.agent.search` +
   raw `:seon.render.live-tile/content`. Either ship the wrappers or stop
   describing them as present. (§1c)
3. **grep pattern contract invisible** → 5 `\|` errors. Teach "alternation is a
   plain `|`, no backslash" at the existing grep discovery example (system prompt
   L115). (§2 class 2)
4. **`:seon.fn` identity attr undiscoverable** → 2 lookup-ref errors. Make the
   "should be marked :db/unique" error suggest `:seon.fn/sym`, or show the fn
   identity attr in the relevant-source header. (§2 class 3)
5. **Eval ergonomics: dangling `}` after a note is too punishing** (13/20 errors).
   Absorb a bare unmatched closer that follows a note-classified literal into the
   same note rather than emitting "READ ERROR — DEFINED NOTHING". (§2 class 1)
6. **Context bloat / harness leakage.** acme fixture namespaces = ~3933 tok (20%)
   of the prompt, with docstrings narrating "BUG-C reproduction case" into the
   agent's own workspace. The render-curation rule needs a lever to exclude
   harness/fixture namespaces. (§1)
7. **SOUL renders full every turn (1965 tok, 10%)** with zero relevance to a build
   task. Standing cost — flag, decide deliberately; not necessarily a fix.

### → U (UI / render / routing / world-layout)

1. **Phantom second "canvas".** Rendered page shows both `#world-canvas` (live
   tile) and `#world-tile-canvas` (acme `:canvas` block). Post-#19, world-layout
   should ignore a block literally named `:canvas`, or acme.world's stale
   `(some (set names) [:canvas :transcript])` doc/code must be updated. Human sees
   two canvases. (§3, §4)
2. **html-only ctx-blocks emit meaningless agent-context stubs** (`{:db/id …
   :seon.agent.ctx/name :canvas}` ×4). An html-only block should contribute a
   tile but NO prompt section — the inverse of the existing "ai-only = prompt-only,
   no tile" rule. (§4)
3. **No interactive affordance reaches the human** — downstream of Core #1; the
   tile shows a "message me" instruction instead of an add control. Once Core #1
   lands, verify the affordance renders + round-trips on `/agent/{id}`. (§4)

## #19 verdict — KEEP the decision

**The live-tile-vs-block duality did NOT confuse the agent — keep "canvas = live
tile (not a block)."** Evidence: zero references to canvas/slot/block/install
across 64 evals; the agent reached for the live tile exclusively, understood the
contract from source, and wired it first-try. The duality cost is entirely
human-facing and entirely attributable to STALE ACME CODE (a `:canvas` block that
predates #19), not to agents misreading the model. Revisit only if a future drive
shows an agent trying to write to a "canvas" block or confusing the two surfaces;
this drive shows the opposite. The follow-up is the U-side cleanup (route U#1),
not a reversal of #19.
