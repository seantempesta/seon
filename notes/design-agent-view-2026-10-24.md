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
| `:seon.render/html`, `:seon.render/ai` (two faces) | kept, with clear jobs: `:seon.render/html` is the value's view (hiccup, for the page and the screen); `:seon.render/ai` is its part of the model's conversation (provider-neutral messages) | existing keys, reused (owner, 2026-10-24) |
| `seon.render.ui/evaluation`, `task-box`, `error-box`, `verdict-box`, `config-panel` | `seon.view/eval`, `task`, `error`, `verdict`, `config`: views named by the thing they show | — |
| `:seon.render/as` `:inline :block :tile :row` | gone: size is CSS for the user and the budget for the model | — |
| `:seon.render/units`, `:keys`, `:parts` (the walk) | `:seon.render/pull` (the neighbours, a pull pattern), `:seon.render/shows`; `:parts` folds into pull | Datahike `pull` |
| `(info #'f)` | `(info #'f)`, the function card's data | nREPL/CIDER `info` op |

## Principles
1. **The REPL stays a plain REPL.** An eval answers its value, printed by the one printer. `(defn f ..)` answers `#'ns/f`. Nothing renders an eval's value: `wrap-print`'s render path (repl.clj:118, values printed through their declared render) is deleted.
2. **Views are renders of records.** A human's page and the model's screen are renders of recorded data: plain HTML hiccup with Tailwind classes, function calls and values.
3. **State is explicit.** What the user is looking at (expanded and pinned evals) is a recorded fact, so the model sees exactly that, a turn can be remade from its basis, and nothing depends on an open tab.
4. **Only what is visible is rendered.** In the browser an offscreen or closed component is a placeholder; for the model, everything is written under one budget, cut with the form that reads the rest.
5. **Reuse first; one mechanism per need.** Everything the model sees is an eval and its response, the screen too. No special message, no provider key; views read through `pull` and `q`, whose recorded reads are the one way to know what must re-render.

## 1. What the model is sent
Standard function calling (`seon.model`, OpenAI-compatible; DeepSeek today):

| Part | Content | Cache |
|---|---|---|
| system | short prompt: you work in a live Clojure REPL; every action is an `eval` | stable |
| task story | user message: the task, its plan, the proofs to pass | stable per run |
| conversation | per model eval: assistant `{content: its prose, reasoning: replayed as returned, tool_calls: [eval {code}]}` then tool `{content: the printed response}`; per user message: a user message; per user form: a user message `{:from "user" :code ".." :result {..}}`; tx order, never a message between a call and its result | append-only |
| screen | the screen evals: forms the system evaluates in the agent's REPL just before each request, sent like a user form `{:by "screen" :code ".." :response {..}}`; each request replaces the previous screen evals, so they never repeat in the conversation | changes |

- **The screen is evals, nothing else** (owner, 2026-10-24: "the dynamic section is just forms that we inject into the evals and we just replace them … the hiccup is a normal eval response. No magic, one system"; and the 2026-10-23 ruling "the turn's live state is REPL forms, run fresh each turn"). The default screen forms are `(seon.screen/show)` (the agent's page without its chat column, as hiccup data) plus the live state forms (`(my.task/status)`, `(my.branch/changes)`). Each is a real eval: recorded (`:seon.eval/by` the screen), a response with a handle, printed by the one printer. The forms are data on the agent's screen fact (`:seon.screen/forms`), so an agent changes what it is shown by changing them: rewriting its own interface is an eval.
- **Handles in printing.** The printer is given the conversation's handles: a value already in an earlier response prints as its handle (`result/e43`) instead of again. A general printer feature: it serves every response, the screen's included.

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
- **How the agent reads it.** `(seon.screen/show)` is evaluated in the agent's process, which reads its own branch db (turn.clj:191) and has no read of main's head; so it asks root (its existing request channel) for `{:screen .. :peers ..}` at main's head. The screen evals are recorded with main's t, so a turn can be remade.
- **Scroll position is not state.** Browser lazy loading is performance (section 4); for the model, "what the user sees" is the page in layout order under the budget, plus everything open or pinned. No tab open: the last view stands; a new agent's view has its latest eval open.

## 3. The render system
**Hiccup rules:**
1. A keyword head is an HTML element (checked against the HTML element list); any other keyword-headed vector renders as its `m/explain`.
2. A vector headed by a function or qualified symbol is a call: `[f props? & children]`. Props are optional: the arguments are checked against f's contract with props first and then without; props schemas are closed maps, so an entity or other data map never passes as props.
3. Other values (maps, entities, scalars) render through their schema's render, else the printer. A data VECTOR is not hiccup: it is passed as a call argument (e.g. `[seon.view/value v]`), never as a bare child.
4. Hiccup holds no expressions. Data comes from function calls in the render's body. Defaults are plain Clojure: `(or (:turns props) (agent-turns props))`; the caller overrides by passing the key. No provider key.

**Two existing render keys, each with one job** (owner, 2026-10-24: "don't invent new schemas if we already have ai and html keys that we can reuse"): `:seon.render/html` is a value's view (hiccup for the page and the screen); `:seon.render/ai` is its part of the model's conversation (provider-neutral messages; see "The request is a render" below). (Was: one `:seon.render/html` replacing both; the existing renders in `seon.render.ui` are reused, renamed by what they show (`seon.view/eval`, `task`, `error`), called `[f props value]`). Lookup by the value's schema key (`seon.render/declared`, one map lookup) with an optional registry in opts (the branch's, then main's).

**No special treatment: the page is the agent's refs, rendered** (owner, 2026-10-24: "I don't want special treatment anywhere … The goal is to only render the same thing once and then cache it"). An agent's page is its entity's view. Its view renders the entities it refers to (its task, its run, its turns, its root) through their own views; root's view renders root's agents (`:seon.agent/_root`). So the sidebar is not a column anyone builds: it is the agent's ref to root, rendered, and root's view shows its agents' views. Root's own page is the same thing with root as the entity. Nothing about agents, root or the user is special-cased in code.
**Render each thing once, and share it.**
- **A view is rendered once per set of inputs, and re-rendered only when a datom it read changes** (owner, 2026-10-24: "I don't want everything to re-render every time a new datom is added … immutable data is immutable so it's never re-rendered"). Views read the db only through `pull` and `q` (the render context's), which record what they read; the cached hiccup is kept until a transaction touches one of those reads. This is the content-keyed reuse the owner ruled on 2026-10-23, keyed by what was read, never by time.
- **Views are budget-free.** A view renders its entity whole, summary first; size is applied after rendering: by CSS in the browser and by the printer's breadth-first selection for the model. So "the abbreviated agent in a sidebar" and "the agent in root's detail page" are the same cached hiccup, shown at different sizes; nothing renders twice for size.
- **One producer: root's render proc** renders every view from records with main's views. An agent commits after each eval (today: at idle or turn end), so its entity's datoms change, its view re-renders once, and every page that includes it, every sidebar of every agent, root's page, and the screens of agents that show it, picks up the same new hiccup. Cost of the commit measured in step 3.
- **The model's screen is a read of the same cache.** `(seon.screen/show)` in the agent's REPL asks root for its page's hiccup (cached views, presentation attributes removed) and answers it as an ordinary value. An agent trying a new view calls it in its REPL like any function; it becomes the page's view when its branch merges. The browser view is never rendered by an agent, so an agent can't hide a red proof from root or the user.

**The request is a render** (owner, 2026-10-24: "turtles all the way down"). Each record's `:seon.render/ai` view renders its part of the conversation as provider-neutral messages (an eval: its assistant message with prose, reasoning and the eval call, then its tool message with the printed response; a user message; a screen eval). The agent's request is the agent entity's `:seon.render/ai` view: the system prompt, the task story, its evals' messages in tx order, then its screen evals'. Those messages are cached by their reads like page components, so a request costs O(new evals). Last, the provider's format function (one pure function per provider, named in its config; today's `seon.model/wire-message` becomes the OpenAI/DeepSeek one) turns them into the wire shape: where reasoning goes and whether (`replay-reasoning`), where the screen and the cache breakpoint go. The debug view draws the request with the request's own `:seon.render/html` view: a render of a render.

**Components, reads and patches.**
- **A component** is a view's call of another entity's view; its id is that entity's (`e<eid>`), so the same entity is the same component on every page, rendered once for all of them. A view of the same entity twice on one page shows the second as a link.
- **Reads are recorded by Datahike itself, at its index** (owner, 2026-10-24: "reuse [Datahike's analysis] and we own it so we can expose whatever we want; I don't want a hacky regex thing"; findings in root's scratchpad, datahike-reads.md). Every `q`, `pull`, `datoms` and entity lookup bottoms out in one index interface (`IIndex`, `index/persistent_set.cljc:204-225`: `-slice`, `-rslice`, `-lookup`). Our fork records each range it scans, `[index from to]` with nil wildcards, into the render's collector, so what a view read is exactly the ranges Datahike scanned: no parsing of forms, per entity and per value, for queries and pulls alike. Measured on the live root: a pull with one nested ref records 3 ranges, a unique point query 1, an attribute scan 1; recording costs nothing measurable.
- **Fork change, ~25 lines on a `jolt/` branch**, named in Datahike's words: `(d/with-reads f)` answers `[result reads]`; `(d/reads-index reads)` keys each range by its bound prefix (`e`, `[e a]`, `a`, `[a v]`, or any); `(d/reads-touched? index tx-data)` answers whether a transaction's datoms (added or retracted) fall in any read, one hash lookup per datom. While recording, the query result cache and the search memo are bypassed (both are off on root today), and queries run on the relational engine (`*disable-planner*`), because the default planner reads the index through ~25 direct cursor calls that skip the interface. Views are small: the relational engine is as fast or faster for point and 2-hop queries, 2.6x slower on a 392-row join; routing the planner's cursor calls through the interface instead is a later fork change if a view needs it.
- **Rules for views that follow from it:** views read through `q` and `pull` (realized inside the render), not lazy `d/entity` reads that outlive the render; a transaction that changes the schema touches every read; one db per view (ranges carry no db identity).
- **Matching a transaction** is one hash lookup per datom: O(datoms in the transaction), never O(components on the page). The datoms come from the writer's transaction report on main, and from `datoms-diff` of an agent branch's previous and new head (O(changed); the writer sees only the branch's new t). Clause-level analysis (`query-attribute-dependencies`, attributes only, `:all` for a pull in `:find`) can pre-filter if ever needed.
- **Immutable things never re-render.** A finished eval, turn or message is written once; no later datom has its entity id, so no transaction ever matches its reads. Its hiccup is computed once and kept.
- **Appends are appends.** A new eval adds `[e-new :seon.eval/agent a]`, which matches only the agent view's reverse read `(a, :seon.eval/agent)`. That view re-runs its query, renders the one new eval (the others are cache hits), and sends one Datastar `append` patch into its list (`seon.web/chunk-of` already sends appends and prepends); the rest of the page is untouched.
- **One patch per change**: serialized once and sent to every tab of every view showing that component (an index from component id to views).

## 4. Budget (the model) and visibility (the browser)
- **`(seon.screen/show)` answers the page without the chat column** (the conversation is already the chat) as hiccup data: the sidebar summaries, the tiles, the open and pinned evals, references. It is an ordinary value: its response prints it like any other, earlier results as their handles. It answers the page's hiccup without its presentation attributes (`class`, `style`, `data-*`, removed by one small function over hiccup), which the same page keeps when written as HTML.
- **Written by the one printer, made general** (owner, 2026-10-24: "a general printer which shows all values well"): `seon.print/print-value` prints the screen's hiccup like any value. Two changes to the printer, for every value, not a view special case: (a) sequential members fill breadth before depth as maps already do (today a vector's members fill in order and the first that doesn't fit takes the rest, print/select.clj:557-587), so a long vector, a table or a page shows every member at its least before any grows; (b) a component `[f props & children]` is a value whose least form is its summary plus its call `(f props)`, the form that renders it, so it is shown whole only when its share holds it. Plain data inside keeps the printer's selection. Estimated ~40-60 lines in `seon.print.select`/`emit`; no second elision mechanism.
- **Priority is layout order** (title, status, red proofs, the latest eval first): the printer's breadth-first selection shows every member at its least first, so a small share shows the summary.
- **Sizes are not declared and views take no budget.** CSS for the user (Tailwind container queries, `line-clamp`); the printer under the request's budget for the model.
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
- Sidebar: the agent's ref to root, rendered: root's view lists its agents' views, which CSS shows small here and root's own page shows large. Peers' ids are in the agent's screen, so it can message them; replies land in its record.
- Chat: thinking and prose as markdown; each eval a collapsed line; expanded: form + response. The user's messages and forms in the same timeline; the input box takes prose, or a form when it reads as one.
- Tiles: one per eval whose value has a render, newest first.
- **Two pages, two audiences** (owner, 2026-10-24: "The debug page should show what the agent is seeing and the normal agent view should be rendering the nice view for the user"). `/agent/{id}` is the user's page above (markdown prose, collapsed evals, tiles). `/agent/{id}/debug` shows exactly what the agent sees: each model request as sent, message by message in order (system, task story, the conversation, the screen evals), with tokens and cache hits per call; it is the request's own `:seon.render/html` view.

## 6. The function card
`(info #'f)` returns data, so the agent can evaluate it: `{:var :args :doc :source :callers :tests {:reaching .. :red ..} :cost ..}`. Source from `:form/*` facts; callers from `my.agent/callers` (exists); tests reaching the var by a reverse walk of the reach graph (new, ~30 lines); their status from `:seon.proof/*` facts; cost: the producing eval's ticks, bytes and its `:fns` (a history of a var's cost would need `:fns` as facts: deferred). Its render is the card.

## 7. Record changes (the standing store is erased on the owner's go)
- Eval (`:seon.eval`, was `:seon.cluster.eval`): one per tool call; its prose; the response's keys; `:seon.eval/by` (who evaluated); the provider's call id; the references in its strings. `:lead` goes.
- Turn: reply, reasoning (opaque), basis, usage. `:calls`/`:evals` text blobs go.
- User prose: `:seon.message` facts, placed in the conversation in tx order (context_test.clj:539 changes).
- New: the user agent entity; `:seon.screen/*`.

## 8. Work per operation
| Operation | Work | Today |
|---|---|---|
| an eval | print the value within its budget; one branch commit | no commit until turn end |
| a commit | one hash lookup per datom finds the components whose reads it touches; each re-renders once; immutable ones never; one patch each (an append for a new list member), fanned out to every view showing it | root re-walks every watched page per event |
| model request | conversation kept as serialized messages per eval id: O(new evals); screen: O(visible components + references) under the budget; one root request for view + peers | O(turns) pulls + whole context rebuild |
| references | parsed once per string at record time | per render (1.66M ticks a string; being fixed) |
| render lookup | one map lookup | same |
| sidebar, N agents | one cached view per agent, re-rendered when that agent changes; visible rows only | O(N) per commit |
| view toggle | one transaction on main; one component | — |
Per commit: O(datoms in the commit) to match, plus the touched components, each rendered once for everyone.

## 9. What it replaces and deletes (estimates)
- New or rewritten: ~530 src lines (read recording and transaction matching ~100 (fork ~25), chat page ~150, function card ~70, result/handle/message reshape ~90 mostly rewrites, printer: breadth-first members and component leaves ~50, view facts + toggle ~25, user agent + root request for view ~30, render lookup ~15).
- Deleted: ~320: context.clj's conversation/scrollback helpers and the run/turn message split (~150), ui.clj's turn/evaluation/row renders (~110), turn.clj plumbing (~35), `:lead` (~10), wrap-print's render path (~15); plus the two render faces, `shown`/`data`/`rendered`. ~120 more (the `:references`/`:found`/`:map`/`:sections` sections) only if a measured run shows the screen replaces them.

## 10. Build order: riskiest first, each measured
0. **Provider check** (~$0.01): 3 DeepSeek turns with thinking ON: tool_choice auto, reasoning replayed, a trailing screen message replaced each turn; assert 200 and cache hits by turn 3.
1. **Conversation reshape** (seon.context, seon.turn, seon.repl, seon.model): response, handles by record id, prose on the eval, user messages in the conversation, auto tool choice, no `;;`, no render in wrap-print; the screen is today's current state for now. Measure on 5 real tasks with deepseek-flash against today's context: turns to green, cache hit rate, tokens per turn (~$1). Rethink if hits fall under ~60% after turn 3 or turns to green get worse.
2. **The screen evals** (`seon.screen/show` and the live-state forms; the printer's breadth-first members, component leaves and handles for earlier results; a root request for screen + peers): same 5 tasks, compared.
3. **Commit per eval; the page in root's render proc** (three columns, placeholders, toggle/pin, user agent), checked in the browser by a subagent; commit cost measured.
4. **Function card; references.**
5. **Deletions** as each replacement is proven.
One Opus worktree session does 0-2 and reports numbers; the owner sees a real request's JSON before step 3.

## 11. For the owner
1. Root renders every browser view; the agent renders only its own screen (its override shows on its page once merged). Agents commit after each eval.
2. View state as a fact on main (`:seon.screen/*`) and a user agent entity: new attributes.
3. A prose-only reply ends the turn and waits for the user.
4. Paid runs for steps 0-2: about $1-2 of the $50 budget; erasing the standing store when the record changes land.
