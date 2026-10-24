# Design: the agent's view and context as renders of its record (2026-10-24, revision 2, for the owner's approval)

THE PLAN steps it serves: 1 (the context tells the model what to change and how it will know), 3 (the owner can watch each turn), and E (turns to green). Worked out with the owner on 2026-10-24; checked by research agents and an independent review (root's scratchpad: challenge, reuse, apis, costs, printer-reuse, datastar, design-review). Revision 2 folds in the review: who renders, the budget rule, no duplicated conversation, and three overbuilt pieces dropped. Nothing is built yet.

## Names (the story the records and the render tree tell)
An **agent** has a **conversation** of **turns**; each turn holds the agent's **prose** and its **evals**; each eval has a **response**; the user's **screen** of the agent ends what the model is sent. Words are Clojure's, nREPL's, Reagent's or the chat API's where one exists (AGENTS §7a):

| Was | Now | Whose word |
|---|---|---|
| `:seon.cluster.eval/*` (eval record) | `:seon.eval/*` (an eval: its code, prose, response, author) | nREPL `eval` |
| `:seon.agent/item` (the response) | `:seon.eval/response`: `{:handle :value :out :err :ex :ms :ticks :bytes}` | nREPL's response keys (`:value :out :err :ex`) |
| `:origin` `:model`/`:ui`/`:outside`/`:context` | `:seon.eval/by`: the agent who evaluated (the agent itself, the user, a session) | plain English |
| `result/h3` (per-form counter) | `result/e43` (the eval's id), the response's `:handle` | the printer's word |
| conversation | conversation (the chat API's messages) | chat API |
| screen | the screen: what the user's screen shows of the agent | plain English |
| `:seon.view/{of by open pinned}` | `:seon.screen/{agent viewer open pinned}`; `seon.screen/open!`, `pin!` | plain English |
| unit | component (`[f props & children]`) | Reagent |
| `:seon.render/html`, `:seon.render/ai` (two faces) | `:seon.render/view`: a schema's one view function | Clerk's viewer, Clojure's "view" |
| `seon.render.ui/evaluation`, `task-box`, `error-box`, `verdict-box`, `config-panel` | `seon.view/eval`, `task`, `error`, `verdict`, `config`: views named by the thing they show | — |
| `:seon.render/as` `:inline :block :tile :row` | gone: size is CSS for the user and the budget for the model | — |
| `:seon.render/units`, `:keys`, `:parts` (the walk) | `:seon.render/pull` (the neighbours, a pull pattern), `:seon.render/shows`; `:parts` folds into pull | Datahike `pull` |
| `(info #'f)` | `(info #'f)`, the function card's data | nREPL/CIDER `info` op |

## Principles
1. **The REPL stays a plain REPL.** An eval answers its value, printed by the one printer. `(defn f ..)` answers `#'ns/f`. Nothing renders an eval's value: `wrap-print`'s render path (repl.clj:118, values printed through their declared render) is deleted.
2. **Views are renders of records.** A human's page and the model's screen are renders of recorded data: plain HTML hiccup with Tailwind classes, function calls and values.
3. **State is explicit.** What the user is looking at (expanded and pinned evals) is a recorded fact, so the model sees exactly that, a turn can be remade from its basis, and nothing depends on an open tab.
4. **Only what is visible is rendered.** In the browser an offscreen or closed component is a placeholder; for the model, everything is written under one budget, cut with the form that reads the rest.
5. **Reuse first; one mechanism per need.** No provider key, no `show` function, no attribute-level dependency index until a measurement asks for one.

## 1. What the model is sent
Standard function calling (`seon.model`, OpenAI-compatible; DeepSeek today):

| Part | Content | Cache |
|---|---|---|
| system | short prompt: you work in a live Clojure REPL; every action is an `eval` | stable |
| task story | user message: the task, its plan, the proofs to pass | stable per run |
| conversation | per model eval: assistant `{content: its prose, reasoning: replayed as returned, tool_calls: [eval {code}]}` then tool `{content: the printed response}`; per user message: a user message; per user form: a user message `{:from "user" :code ".." :result {..}}`; tx order, never a message between a call and its result | append-only |
| screen | the page without its chat column (section 4), rebuilt each request, never kept | changes |

- **Response** (`:seon.eval/response`, replacing `:seon.agent/item`): `{:handle result/e43 :value v :out ".." :err ".." :ex {..} :ms .. :ticks .. :bytes ..}`. `:ex` is a thrown exception as `seon.error` data; `:out`/`:err` are the streams; each key only when present. Printed by the printer with `:handle result/e43`, so a cut inside the value reads `(get-in result/e43 [..])`. Sent as a string.
- **One handle per tool call**, named by the eval record's id (`result/e43`; unique across restarts, unlike today's per-process counter, agent.clj:769). A call's forms run in order like `load-string`: the value is the last form's; it stops at the first exception, which is the `:ex`.
- **Handle lifetime.** In the live process a handle holds the value. After a restart, `result/e43` reads the record: the printed value and its type, saying it is not the live value. Values are not all kept forever: a process holds its handles' values (as today); measured in step 1, and bounded by the history budget if it matters.
- **Ids.** The provider's tool-call id is stored and replayed unchanged; our handle is in the response.
- **tool_choice auto** (DeepSeek rejects `required` with thinking on; Anthropic's current models too). A reply with prose and no call is a message to the user: the turn ends and the run waits for the user. Today such a reply wastes a turn ("Nothing ran: the reply called no eval", context.clj:345). Finishing stays an eval, `(my.task/done!)`.
- **Reasoning** is stored opaque and replayed exactly (DeepSeek requires all `reasoning_content` with tools; Anthropic thinking blocks with signatures). Shown to the user in the chat; never rendered for the model.
- **History bound**: the existing `:seon.config.render/history-budget`; past it, older tool results shrink to `{:handle result/e12 :value <one line>}` in large steps (one config key for the step) so the provider cache rarely breaks; the printer says what it left.
- **No `;;` reaches the model**: removed from the eval tool's description (model.clj:96), the help page (docs/agent.md:11, 12, 44) and render/ai.clj:200.

## 2. Explicit view state
- **The user is an agent**: an agent entity with no process (`:seon.agent/id "user"`); its messages and forms are recorded like any agent's (`:seon.eval/by` the user).
- **One view per (user, agent)**, a fact on main: `:seon.screen/{agent viewer open pinned}` (agent ref, user ref, sets of eval ids). All of a user's tabs on an agent share it. New schema attributes (owner's gate).
- **▸ and 📌** post `/call` (exists, web.clj:76) to `seon.screen/open!` / `pin!`, which transact the view fact on main: no branch write, no wake of the agent.
- **How the agent reads it.** The agent builds its request from its own branch db (turn.clj:191) and has no read of main's head. At each model request it asks root (its existing request channel) for `{:screen .. :peers ..}` at main's head; the answer and main's t go into the turn's basis, so the turn can be remade.
- **Scroll position is not state.** Browser lazy loading is performance (section 4); for the model, "what the user sees" is the page in layout order under the budget, plus everything open or pinned. No tab open: the last view stands; a new agent's view has its latest eval open.

## 3. The render system
**Hiccup rules:**
1. A keyword head is an HTML element (checked against the HTML element list); any other keyword-headed vector renders as its `m/explain`.
2. A vector headed by a function or qualified symbol is a call: `[f props? & children]`. Props are optional: the arguments are checked against f's contract with props first and then without; props schemas are closed maps, so an entity or other data map never passes as props.
3. Other values (maps, entities, scalars) render through their schema's render, else the printer. A data VECTOR is not hiccup: it is passed as a call argument (e.g. `[seon.view/value v]`), never as a bare child.
4. Hiccup holds no expressions. Data comes from function calls in the render's body. Defaults are plain Clojure: `(or (:turns props) (agent-turns props))`; the caller overrides by passing the key. No provider key.

**One render per schema**: `:seon.render/view` (replaces `:seon.render/html` and `:seon.render/ai`; the existing renders in `seon.render.ui` are reused, renamed by what they show (`seon.view/eval`, `task`, `error`), called `[f props value]`). Lookup by the value's schema key (`seon.render/declared`, one map lookup) with an optional registry in opts (the branch's, then main's).

**Who renders (one producer per view):**
- **Root's render proc renders every browser view** from records with main's renders: live or finished, watched or not, one place. For that, **an agent commits after each eval** (today it commits at idle or turn end), so root sees each eval as it lands; cost measured in step 3.
- **The agent's process renders its own screen** (it builds its model request), with its branch's registry: an override it registers applies at once to what its model sees, and to its page for the user when its branch merges. The browser view is never rendered by an agent, so an agent can't hide a red proof from root or the user.

**Components.** Each call `[f props & children]` is a component; its id is the digest of its path in the page plus the call's arguments (so two equal calls in different places have different ids; code identity is not in the id). The id is the element's `id` and the Datastar patch target. Re-render rule, using what already exists (render/proc.clj `step-units`): on a commit to the branch a view reads, its components re-run; a component whose inputs are equal is skipped, and same bytes send nothing. A parent holds its children's calls, not their output. Attribute-level dependency tracking (Datahike's `query-attribute-dependencies`, in our fork) is added only if a measurement on a busy page asks for it.

## 4. Budget (the model) and visibility (the browser)
- **The screen = the page without the chat column** (the conversation is already the chat), written for the model: the sidebar summaries, the tiles, the open and pinned evals, references. Any value that is an earlier eval's result is written as its handle `result/eN`. `class`, `style` and `data-*` attributes are dropped for the model (a printer option).
- **Written by the one printer, made general** (owner, 2026-10-24: "a general printer which shows all values well"): `seon.print/print-value` prints the screen's hiccup like any value. Two changes to the printer, for every value, not a view special case: (a) sequential members fill breadth before depth as maps already do (today a vector's members fill in order and the first that doesn't fit takes the rest, print/select.clj:557-587), so a long vector, a table or a page shows every member at its least before any grows; (b) a component `[f props & children]` is a value whose least form is its summary plus its call `(f props)`, the form that renders it, so it is shown whole only when its share holds it. Plain data inside keeps the printer's selection. Estimated ~40-60 lines in `seon.print.select`/`emit`; no second elision mechanism.
- **Priority is layout order** (title, status, red proofs, the latest eval first), and no component takes more than its share.
- **Sizes are not declared.** CSS for the user (Tailwind container queries, `line-clamp`); the budget for the model.
- **The browser renders only what is visible.** A closed `<details>` body or an offscreen tile is a placeholder with the component's id (same tag). `data-on-intersect__once` or `data-on:toggle` posts for it; the post answers 204 and the rendered component arrives on `/feed` (as `:more` does today, web.clj:142). `<details>` carries `data-preserve-attr="open"` (Datastar RC.7, our build).
- **References.** Strings shown (prose, thinking, user messages, the task plan) are parsed once at record time by the one reference parser and their references stored with them; the referenced things (a var's card, a task's plan and proofs, a wiki section) are rendered into the screen after the open and pinned items, within the budget.

## 5. The page
```
┌ agents ─────────────┬ chat ─────────────────────────────────┬ tiles, newest ↑ ┐
│ ● a3 working  task… │ You: why is the agent test red?        │ [fn card]       │
│ ○ a5 waiting        │ thinking (markdown, muted)             │ [proofs 2/3]    │
│ ● root              │ prose (markdown)                       │ [diff +12 -1]   │
│                     │ ▸ (info #'seon.turn/observe) · 12 ms  │ … placeholders  │
│                     │ ▾ (defn- index! …) · 8 ms              │                 │
│                     │   {:result result/e43 :value #'…}      │                 │
│                     │ [ message or (form) …           Send ] │                 │
└─────────────────────┴────────────────────────────────────────┴─────────────────┘
```
- Sidebar: each live agent's summary tile (its view under a small budget share), windowed. Root's page shows the agents at a larger share. Peers' ids appear in the agent's screen, so it can message them; replies land in its record.
- Chat: thinking and prose as markdown; each eval a collapsed line; expanded: form + response. The user's messages and forms in the same timeline; the input box takes prose, or a form when it reads as one.
- Tiles: one per eval whose value has a render, newest first.
- **Debug view (root)**: the same chat plus, per turn, the request's tokens, cache hits and its screen as sent (remade from the turn's basis, which includes the screen answer).

## 6. The function card
`(info #'f)` returns data, so the agent can evaluate it: `{:var :args :doc :source :callers :tests {:reaching .. :red ..} :cost ..}`. Source from `:form/*` facts; callers from `my.agent/callers` (exists); tests reaching the var by a reverse walk of the reach graph (new, ~30 lines); their status from `:seon.proof/*` facts; cost: the producing eval's ticks, bytes and its `:fns` (a history of a var's cost would need `:fns` as facts: deferred). Its render is the card.

## 7. Record changes (the standing store is erased on the owner's go)
- Eval (`:seon.eval`, was `:seon.cluster.eval`): one per tool call; its prose; the response's keys; `:seon.eval/by` (who evaluated); the provider's call id; the references in its strings. `:lead` goes.
- Turn: reply, reasoning (opaque), basis (now with the screen answer and main's t), usage. `:calls`/`:evals` text blobs go.
- User prose: `:seon.message` facts, placed in the conversation in tx order (context_test.clj:539 changes).
- New: the user agent entity; `:seon.screen/*`.

## 8. Work per operation
| Operation | Work | Today |
|---|---|---|
| an eval | print the value within its budget; one branch commit | no commit until turn end |
| browser update after a commit | a view's components re-run; equal inputs skipped; same bytes not sent | root re-walks the page per event |
| model request | conversation kept as serialized messages per eval id: O(new evals); screen: O(visible components + references) under the budget; one root request for view + peers | O(turns) pulls + whole context rebuild |
| references | parsed once per string at record time | per render (1.66M ticks a string; being fixed) |
| render lookup | one map lookup | same |
| sidebar, N agents | one tile per status change; visible rows only | O(N) per commit |
| view toggle | one transaction on main; one component | — |
Per commit a view's components re-run: O(components on the view), each cheap, sends only what changed. If a busy page measures badly, attribute dependencies narrow it.

## 9. What it replaces and deletes (estimates)
- New or rewritten: ~430 src lines (chat page ~150, function card ~70, result/handle/message reshape ~90 mostly rewrites, printer: breadth-first members and component leaves ~50, view facts + toggle ~25, user agent + root request for view ~30, render lookup ~15).
- Deleted: ~320: context.clj's conversation/scrollback helpers and the run/turn message split (~150), ui.clj's turn/evaluation/row renders (~110), turn.clj plumbing (~35), `:lead` (~10), wrap-print's render path (~15); plus the two render faces, `shown`/`data`/`rendered`. ~120 more (the `:references`/`:found`/`:map`/`:sections` sections) only if a measured run shows the screen replaces them.

## 10. Build order: riskiest first, each measured
0. **Provider check** (~$0.01): 3 DeepSeek turns with thinking ON: tool_choice auto, reasoning replayed, a trailing screen message replaced each turn; assert 200 and cache hits by turn 3.
1. **Conversation reshape** (seon.context, seon.turn, seon.repl, seon.model): response, handles by record id, prose on the eval, user messages in the conversation, auto tool choice, no `;;`, no render in wrap-print; the screen is today's current state for now. Measure on 5 real tasks with deepseek-flash against today's context: turns to green, cache hit rate, tokens per turn (~$1). Rethink if hits fall under ~60% after turn 3 or turns to green get worse.
2. **Screen as the page under budget** (printer component share, handles for earlier results, attributes dropped, root request for view + peers): same 5 tasks, compared.
3. **Commit per eval; the page in root's render proc** (three columns, placeholders, toggle/pin, user agent), checked in the browser by a subagent; commit cost measured.
4. **Function card; references.**
5. **Deletions** as each replacement is proven.
One Opus worktree session does 0-2 and reports numbers; the owner sees a real request's JSON before step 3.

## 11. For the owner
1. Root renders every browser view; the agent renders only its own screen (its override shows on its page once merged). Agents commit after each eval.
2. View state as a fact on main (`:seon.screen/*`) and a user agent entity: new attributes.
3. A prose-only reply ends the turn and waits for the user.
4. Paid runs for steps 0-2: about $1-2 of the $50 budget; erasing the standing store when the record changes land.
