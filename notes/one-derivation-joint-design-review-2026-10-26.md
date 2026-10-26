# Independent review: the joint design for one derivation (jolt/webui-root-chat:notes/one-derivation-joint-design.md)

Read-only review, 2026-10-26. Read against notes/one-derivation-2026-10-26.md (all of it, the last section above all), docs/decisions.md (2026-10-26 entries), AGENTS.md §4 and §9, and the code on main: seon.ctx, seon.screen, seon.render, seon.render.ui, seon.render.proc, seon.render.ai, seon.context, seon.repl, seon.agent. Nothing was run.

Timing matters: the design was committed at 07:30:03 (86049b9a4), and the owner's narrowing ("Narrowed to the implementation") at 07:31:16 (95e97bcfa). The design was written against the eight points, not the five functions. Most of what follows is the distance between the two.

## Verdict in one paragraph

The ctx side is sound and mostly built: pull once, patch per transaction, and order from facts. The render side does not reduce to the five-function chain. It keeps the formula the owner rejected: the model reads a print of the page's hiccup ("Two outputs of one hiccup", the screen mock). The narrowing says `(html part)` and `(ai part)` are two faces of one part, both defaulting to the printer and each overridable alone. The design instead builds a second machine around "print the hiccup for the model": a theme map, render markers in the text, wrapper elision, hiccup as a printer value kind, a chat-part call form, and a component diff beside the value diff. It also adds `:seon.eval/replay?` beside two facts that already say the same thing, and a second ctx in root. Each piece of that machinery exists only because the model reads markup. Drop that one premise and most of it goes.

## 1. Does it reduce to the five-function chain? Extra machinery, by section

The chain: `(ctx db agent)` → `(view ctx)` → parts → `(html part)` / `(ai part)` → `(diff prev next)`, cached by (renderer, part value).

| # | Extra beyond the chain | Section | Needed? |
|---|---|---|---|
| 1 | **The model reads the hiccup's print, not `(ai part)`.** `:seon.render/ai` is kept only as "the printer's text face, never a hand-written second view"; −120 lines of `:ai` faces are deleted. | "The split in two lines"; §3; deletion table | **No. This contradicts the narrowing,** which keeps `:seon.render/ai` as a per-face override: "a schema's `:seon.render/ai` overrides that face only". |
| 2 | Theme map (class → Tailwind utilities), shown once and again on change, expanded at HTML time | §3; "Open, for root" | No. The stylesheet already holds semantic component classes (`resources/public/css/input.css:88-126`: `@layer components`, `.kicker`, `.seon-print-*`). Renders that write semantic classes need no expansion step. The theme is a file, one form away. This would be a second styling mechanism. |
| 3 | Render markers `{:render sym :key path}` printed into the model's text | §3 | Only because of #1. Under `(ai part)`, a one-line part header (its key and its html render's name) carries the same information. |
| 4 | Layout-only wrapper elision in the printer | §3 | Only because of #1. It is also a heuristic about what counts as "layout-only". |
| 5 | Hiccup as a new printer value kind (+110) | §3; slice 4 | Only because of #1. If kept, hiccup must be marked explicitly (metadata from `html`). Detecting `[:kw …]` vectors would be inference, and data such as `[:db/add e a v]` looks the same. |
| 6 | "A component whose input the model already reads prints as its render call", `[:chat-part {:key "c-1665"} result/e42]` | §3 | **No.** It is a special case decided by inference ("already reads"). It sends about 10 tokens per eval that tell the model nothing it can use. It also contradicts §7: once the history cut folds e42, `result/e42` points at something no longer readable whole. A chat part's `(ai part)` is simply the record's existing ai face (`seon.context/eval-messages`, declared at `resources/seon/schemas/seon.facts.edn:154`). |
| 7 | "An @ctx eval in the conversation shows collapsed" | §2 | **No. It is a special case for @ctx,** against "Nothing is a special case, `@ctx` included" in the direction note. The cause is in §4 below. |
| 8 | `patch` answers `:changed #{path}` (+30), and the cache invalidates along those paths | §1, §5 | Redundant with `identical?`. A per-part memo `{key [f v out]}` checked with `identical?` costs one pointer compare per part, and the view has tens of parts. Pick one mechanism; `identical?` needs no change to `patch`. |
| 9 | Two diffs: the printer's "component diff" (slice 4) plus `seon.screen/diff` (kept and extended for point 7), plus `printed-run`'s `as-diff` / `:since`, `:same-as` and folds | §3, §7, §8; "What stays" | The chain has one `(diff prev next)` over keyed parts. The folds and `:same-as` are printer dedupe and can stay. The screen's value-path diff and a component diff are two answers to one question. |
| 10 | Root keeps its own ctx per viewed agent, held equal to the agent's by a property test | §1; slice 2 | Questionable. See §2 below. |
| 11 | `:seon.eval/replay?` | §4 | No. See §6a. |
| 12 | The request keeps its own path: system, story via `run-message`, history note, `printed-run` turns, then the screen | §8 | Partly. The conversation and the story do not pass through `(view ctx)` → `(ai part)`. The request still has its own notion of parts (`seon.context/parts-of`, `:sent` refs) beside the view's. Under the chain, the story is the task part's ai face, and each record is a conversation part's ai face. |
| 13 | A sorted conversation list held beside ctx (`:ix` already exists) | §1 | Acceptable. It is a derived index, not stored. Name it as one, or order by the eval handle's number (a fact) at view time. |

## 2. Is the data dependency chain clear and one-directional?

Mostly, with three problems.

- **A second derivation stays alive: root's ctx per viewed agent.** The agent's @ctx (in its process, used for the request) and root's copy (for the page) are the same functions in two processes. A property test then says they agree. That is the owner's "are we deriving the same data twice?" in a new form. It also breaks the override story. A render an agent registers on its branch is a function in the agent's REPL. Root cannot call it today, so it asks the agent for the drawing (`seon.render.ui/*drawn*` / `*wanted*`, `seon.render.proc/ask-draws`, proc.clj:386). The design never mentions this. For a parked or gone agent, root's ctx can draw only the defaults, so "a parked or gone agent's page still renders" holds only without overrides. Render in one place:
  - either where ctx and the render fns live (the agent, sending parts to root, which keeps the last ones; while parked nothing changes, so there is nothing to redo, per §4);
  - or in root only, if renders are data root can run.
  - Root must rule on this. The docstring of `seon.agent/screen` (agent.clj:516) says "an agent cannot draw its own view", and the reason should be checked.
- **The walk stays alive for every page that is not an agent's.** `/` (root's), `/walk/<eid>`, task pages and the debug page all go through `seon.render/walk` and `ui/page` (web.clj:152, proc.clj:269-285). Slice 3 replaces the walk "for agent pages" only, and slice 6 adds root and the user. Task pages and `/walk/<eid>` are in no slice. So the walk and the read-set index remain a second producer, unless every page becomes `(view x)` of a pulled value.
- **The screen's whole has no stated home.** §8 puts "the screen last", and a diff then replaces changed components "by their key". If the screen is a tail replaced each request (as `screen-message` is today), a later diff has no base above it. If it accretes (as today's screen evals do, via `printed-run`'s `:since`), the component diff duplicates `seon.screen/diff`. The design must say which; point 7's rule depends on it.
- There is no cycle. The obvious candidate, an @ctx eval recorded inside ctx, is already broken at its producer: `seon.repl` records a value carrying `:seon.render/basis` as `{:schema :basis}`, never a copy (repl.clj:391-399).

## 3. Do the deletion claims hold?

| Claim | Today | Holds? |
|---|---|---|
| walk, `one`, `relevant`, helpers, render.clj 216–311 (−95) | Exists: `forward` 216, `relevant` 221, `one` 230, `walk` 272-311. `entity-schemas` / `entity-schema` (≈199-214) stay; seon.ctx uses them (ctx.clj:36, 48, 72). | Only if every page moves (see §2). Not for agent pages alone. |
| read-set index (−160), proc.clj | Exists: `comp-entry` 364 (`d/with-reads` 377, `:comp-index` 382), `comps-touched` 397, `comps-of` / `drop-comps` / `invalidate` / `advance-main` 405-443, `index` / `unindex` / `touched` 323-355, `location` 211, `page-value` 247. | The size is plausible, but it is contingent on the same condition: root's and task pages still use it. |
| walk-driven layouts (−300), ui.clj | `page` 1118, `rows` 795, `component` 887, `chat` 952, `agent` 1001, `root` 1078: about 250 lines. | Partly, and +150 comes back for the layout render. `root` and `page` stay while non-agent pages use the walk. |
| `:ai` faces (−120), render/ai.clj (222 lines) | Faces `error`, `task`, `run`, `problem-row`, `report`, `problems`, `system`. `render/ai` of errors answers MCP clients (server.clj:233, 258). | **No.** The narrowing keeps the ai face as an override, and these carry what the model and MCP need. |
| `seon.screen/show` (−40) | `show` 58-68 and `last-screen` 48-56: about 20 lines. `diff` stays. | About −20. **But under-claimed overall.** The screen's whole round trip goes too: `seon.agent/screen` (agent.clj:516-528), proc `screen-view?` / `drop-view` / `screen-hiccup` / `screen-of` / `index-of` / `screen-due?` (proc.clj 535-600), and the handlers at root.clj:397 and agent_proc.clj:221. That is roughly −100, and the design does not list it. |
| `seon.context/records` (−50) | 557-576, 20 lines. It already reads only since `(:tx printed)`; the query still scans the agent's evals. `printed-run`, `kept` and `as-sent` (≈450-555) stay. | About −20. The cost section's "the request re-reads the run's records" overstates today: it reprints nothing, and only scans. |

Additions not counted:
- Rewriting every render's Tailwind class strings to semantic names, across all of ui.clj (1246 lines), if the theme map is kept.
- Root's datom feed of agent branches for its per-view patch.
- The tile rule for replayed forms.

**Net:** the −350 estimate assumes every page migrates and the ai faces are deleted. The first is not in the slices and the second is against the narrowing. If only agent and root pages migrate, net src is about −50 to −150. That is still a reduction if the hiccup-print machinery (+110 and the theme) is dropped. The estimate is "from the current files' sizes, not measured", and it reads that way.

## 4. Are the token measurements credible? Is "whole once, then diffs" affordable?

- **Method.** `notes/measure/hiccup_measure.py` parses today's HTML with Python's HTMLParser and counts characters ÷ 3.2. That is not a tokenizer, and keyword- and class-heavy EDN tokenizes worse than prose. The "semantic classes" are synthetic (`div0`, `span3`, …), because today's hiccup has Tailwind strings, not semantic classes. The "compact print" was never produced by the printer. These are estimates, as stated. They are not evidence for slice 4's design.
- **≈5.2k "with @ctx collapsed" is 25.0k − 19.8k, a subtraction, not a measurement.**
- **The headline comparison is wrong.** "About 5k, against today's 6.4k request, which already includes the story":
  - the 6.4k is system 0.5k, story 4.5k and screen 1.4k;
  - the page print replaces none of the system or the story;
  - the like-for-like figure is today's screen at 1.4k against the screen side at 2.1k + 0.15k = **2.25k: 60% more tokens**;
  - and the screen side carries less. The proofs tile (`ui/proofs`, ui.clj:713-724) shows only status and `:assert` / `:got`. `my.task/status` (my/task.clj:47) gives failures with source and calls, the done rule's reasons, the turn of how many, the last reply's fate, unmerged entries, stale sections and the tests run. This is the owner's "html(ctx) will remove details that the agent needs", measured.
- **A1 is an outside agent with no conversation.** For the case that matters, a model agent (screen 11,848 whole / 4,458 as diffs per turn), nothing was measured: no diffed run, no cache share, no turns to green.
- **Affordability.** Yes, a whole screen side of about 2–3k sent once, then about 100–300 per changed part, is affordable. That holds whether the parts are hiccup or ai text. It is not evidence for printing hiccup.
- **The 19.8k @ctx tile.** It is a page problem. The model's print of that eval is the record's budgeted `:printed` text. The page rebuilds ctx at the recorded basis (`based-value`, ui.clj:471-481, a full `seon.ctx/build` on `as-of`) and draws every key with no budget (`ui/ctx`, ui.clj:738-772). The design hides it with a special case (#7) instead of fixing it at its cause. The fix is the direction's own rule, "the same selection and the same cuts": the html face uses the printer's budgeted selection, as the text face does. Then the @ctx tile is the size of its text print, with cuts that say what they left. Nothing collapses specially, and the same fix covers any other large value.

## 5. The agents' help text

It is short: five sentences. Two of them are false to the mechanism, and one omission matters.
- "Everything in `@ctx` is shown to you and the user the same way: your page, printed." This is false under the design: the chat side reaches the model as the conversation, not the page. It is false under the narrowing too, which says the faces "differ in detail, never in which parts". It also contradicts the help text's own next lines ("what the page shows is chosen by its layout, and the rest is one form away").
- It names only `:seon.render/html`. The narrowing gives a second override, `:seon.render/ai`, for what the model reads. An agent that wants its own value to read better to itself has no way, per this text.
- "A form you want re-run every turn goes on your screen (`:seon.agent/screen`); every other eval shows what it answered." This is true, and it never mentions `:seon.eval/replay?`. That itself shows the attribute adds nothing an agent uses.
- It does not say that the agent receives the whole once, then diffs, or how to read a part again (`result/eN`).

A true version in five lines:

> Your page is `(view @ctx)`: keyed parts. Each part has two faces: html for the user, ai for you; both default to the one printer, so any value reads well with no render. To change a key's look, register its schema with `{:seon.render/html f}` (f: value → hiccup) or what you read with `{:seon.render/ai f}` (value → text); the layout is `:seon.ctx/ctx`'s. Forms in `:seon.agent/screen` re-run every turn; every other eval shows what it answered. You get each part whole once, then only the parts that changed; a part folded away is sent whole again.

## 6. The design's calls

**(a) `:seon.eval/replay?` set by screen forms.** Unsound as a new attribute. The explicit fact already exists twice:
- `:seon.agent/screen`, the forms list in ctx, which the agent edits with `swap!` (screen.clj:19-23; turn.clj:208), is the declaration at the form;
- `:seon.eval/by "screen"` (an indexed attribute, seon.facts.edn:123) is written on every eval those forms make (screen.clj:53, context.clj:478).

That is explicit, not inferred, which is what the owner asked for. A third attribute is a second representation of one fact, and the two can disagree. The tile rule reads `:seon.eval/by`. If the owner wants the word "replay", rename `"screen"` → `:replay` in `:seon.eval/by`; do not add a key. The design's open question on where the attribute lives answers itself: on the form list, where it already is.

**(b) Cache key = ctx path + render fn, with `identical?` rather than a content hash.** Sound for in-process use, and better than a content hash there. Three corrections:
- Key on the render fn **value**, not its symbol. An agent that redefines `my/render` keeps the symbol, so a symbol key serves a stale render. Compare `[f v]` with `identical?` per part.
- Then `:changed` from `patch` is unneeded (#8).
- `identical?` holds only along patches. A rebuild (resume, merge, a branch switch: ctx.clj:352, 356) re-renders everything. That is right, but say so in the cost proof.

**(c) A chat part printed as its render call beside the record.** Unsound (#6):
- it is inferred ("whose input the model already reads");
- it carries no information;
- it can dangle after the history cut, which is exactly point 7's failure ("the mixed-collection run repeated a probe 8 times on `(:out result/e89)`").

A chat part's ai face is the record's ai face. Send nothing extra.

**Does any call hide a detail the agent needs?** Yes, the premise behind all three: the model's screen as the print of the page's tiles. The proofs tile drops failures with source and calls, the done rule's reasons, the turn count and the reply's fate (§4). The theme, markers and elision then spend tokens on presentation the agent rarely edits, and the details it needs every turn are cut.

## 7. Top five changes that would make it simpler, ranked

1. **Implement the narrowed chain literally: the model reads `(ai part)`, never the hiccup's print.** Each part's ai face is the printer's text of the part's value, or its `:seon.render/ai` override. The existing ai faces and `eval-messages` stay. A one-line part header names its key and its html render, which answers "compress, don't remove": the hiccup is one form away, named.
   - Deletes from the design: hiccup as a printer value kind, the theme map in the text, render markers, wrapper elision, the chat-part call, and the −120 ai-face deletion.
   - Keeps the details `my.task/status` gives.
2. **One `(diff prev next)` over the view's keyed parts, and one cache rule.** The memo is per part `{key [f v out]}`, hit when both are `identical?`. The diff sends a changed part's new ai text whole and an unchanged part nothing, only while its last whole send is still readable above (point 7). Screen parts accrete as conversation entries, as today's screen evals do, so the base always has a home.
   - Retires `seon.screen/diff`'s value-path diff and `printed-run`'s `as-diff` screen branch once the screen forms are parts.
   - Drops `:changed` from `patch`.
3. **Drop `:seon.eval/replay?`, and delete the screen's round trip instead.** `:seon.agent/screen` plus `:seon.eval/by "screen"` are the explicit facts. The real deletion the design misses is `seon.screen/show` and `last-screen`, `seon.agent/screen`, proc.clj 535-600, and the root and agent_proc handlers: about −100 lines, plus one cross-process round trip and one commit per turn.
4. **One render site, not two ctxs.** Either the agent renders its parts (it holds ctx and its render fns) and root keeps and forwards the last ones (`ask-draws`, `*drawn*` and `*wanted*` go), or root renders alone from renders it can run. Remove root's per-viewed ctx and its equality property test, or say which overrides a gone agent's page loses. Root rules on which.
5. **No special cases for size or styling.**
   - The html face uses the printer's budgeted selection and cuts, as the text face does. That fixes the 19.8k @ctx tile and every large value without "@ctx shows collapsed".
   - Styling uses the stylesheet's existing semantic component classes (`input.css` `@layer components`), with no theme map expanded at render time.

Then state the scope honestly: the walk and the read-set index are deleted only when every page (root's, task pages, `/walk/<eid>`, debug) is `(view x)` of a pulled value. Either add that slice, or drop −255 from the estimate.
