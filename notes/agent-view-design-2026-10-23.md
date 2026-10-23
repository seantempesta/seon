# The agent debug view: research and design (2026-10-23)

Worktree session agent-view. Owner, 2026-10-23: "Lets get the web UI working better so I can get a debug view into any agent." Then: "just do exploratory research for now." This note is the research and the design. No `src` change is proposed for merging yet.

**Evidence.** The prototype's full test run (`bin/test`, 448 tests) was 446 green; the two reds were the digest-stability tests (`seon.program-test/the-same-tree-gives-the-same-digests-in-any-checkout-and-any-run`, `seon.agent-test/a-wide-reach-reuses-unchanged-results`), not investigated (files were committed during the run). Before the redirect, this session built a working prototype and ran it on its own root, with the scripted model working calc/add, square, twice and neg. A browser subagent then compared the pages against `seon_eval` facts. The prototype is kept on branch `jolt/agent-view-prototype` (commits `42a9d44`, `1bcf80f`, `f083e7e`, `56a9650`; +346 net `src` lines) as evidence only. Its pieces are reused in the build (§8). Its numbers and the verifier's findings are below.

## 1. What `/agent/{id}` shows today, and what v1 showed

**next (main `f7afe29`).** The page is `seon.render.ui/agent-page`, built by `seon.render.proc/zoom-value`. It has five units:
- `zoom-head`: the card's numbers (pid, fork ms, memory, evals, t) and the running form.
- `zoom-work`: an attempts table with one row, and the task box.
- `proofs-box`: the agent's last test run, and the judge's results on main.
- `zoom-context`: only the newest context read, remade from that turn's basis (`seon.context/recorded`).
- `history-list`: evaluation rows.

What it lacks:
- no turn timeline, so turns 0..N-1 are invisible;
- no reply or reasoning;
- no per-turn tokens, cache share or cost;
- no `:will-merge`;
- no problems for this agent;
- no way to message the agent or release its task.

An agent root no longer holds (one from another boot, or retired) gets an empty page. The zoom reads the branch only while root's view holds the agent.

**v1 (`~/src/seon/src/seon/render/web.clj`; routes in `render/route.clj:5-38`).**
- **`/agent/{id}`** (`page-response`, web.clj:3131):
  - header (`transcript.clj:1158`): state, including "stalled since";
  - units walked from the agent entity (`render.walk/neighborhood`);
  - a turns table (`transcript.clj:1041`): opened, duration, trigger message, eval count, first line of the reply;
  - a live strip of the in-flight partial reply;
  - an SSE feed of changed units.
- **`/agent/{id}/debug`** (`debug-response`, web.clj:3209):
  - "Model calls: what each was sent", one row per provider call with prompt, cached and completion tokens and finish;
  - each call opens lazily (`?call=`) into `sent/view-html` (sent.clj:92): call facts, the system and attempt sections token-badged, the exchanges (forms with what the REPL answered), and the turn sections;
  - "next call: what it would be sent now".

  The prompt is never stored. It is remade from the call's basis-t (sent.clj:2-6, 35).
- **`/agent/{id}/message`** (web.clj:299, 3410, 2960):
  - writes `:seon.message/to` and `:seon.message/content` (cluster/message.clj:151);
  - the datom wakes the agent;
  - `context/current-state` puts unhandled messages in `:seon.context/unread` (context.clj:929, 972);
  - the turn's close records `:seon.turn/handled` (turn.clj:408).
- **`/ns/{ns}`** (web.clj:3308): the namespace's assigned agent's page, or an inspection view.
- **`/data`** (web.clj:3475): a value browser over schema attributes, an entity, or a stored value.

## 2. The facts recorded per agent and turn, and what is missing

**On the attempt's branch** (kept in the store after the agent ends; real runs are never deleted):
- **`:seon.turn/*`**, one entity per model turn (`seon.turn/act!`), and one per outside read (`context-block`, id `<attempt>/read-N`):
  - `basis {:commit :task :attempt :turn :max-turns :origin :observed}`. `:observed` holds `:results :reds :refusals :run-errors :will-merge`, and `:last-evals` on the first turn;
  - `reply`, `reasoning` (one blob per turn), and `calls [{:id :code :evals}]`. Here `:evals` is the REPL's answer as the model read it, folded and budgeted, the same text the scrollback sends;
  - `tokens`, `cache-hit-tokens` and `cache-miss-tokens` (absent when the provider does not split them);
  - `forms`, `errors`, `red`, `ms`, `finish`, `model`, `truncated`;
  - `split {:tests-ms :context-ms :model-ms :eval-ms :record-ms}`, written in a second transaction.
- **`:seon.cluster.eval/record`**, one per evaluated form:
  - `{:tag :form :out :ms :alloc-bytes :fns :origin :shown :error}`;
  - its id is `<agent>-<pid>-<nanos>/<item id>`;
  - it has no link to the turn or call it belongs to.
- **`:seon.end/*`**: the attempt's end (outcome, turns, tokens, reason, error, waste).

**On main:**
- The attempt: status, outcome, reason, start and end ms, boot, agent, pid, `:env {:branch :model :max-turns}`, results, conflicts, unmerged.
- The task: execution, failure, evidence.
- Problems (`seon.problems/report`).

**In root's view only** (lost when root stops): pid, memory, fork ms, t, the newest 20 evals, the running form, and the last test run (`:tests`, with each report's `:reused`).

**"What it saw at turn N" can be rebuilt exactly.** `seon.context/render` of the basis on the commit it names gives the very messages the model was sent. The prototype measured this on the scripted run: turn 0 of calc/square was 2,505 tokens (help 2,172, attempt sections 167, turn sections 166). The rebuild was identical across reloads, and its "whole request" text began with the system message.

**What is missing:**
1. **The eval→turn link.** A record cannot be placed under its turn and call. Adding `:items [ids]` to each `:seon.turn/calls` entry is enough, because item ids are the process's eval counter.
2. **Per-form thought.** See §5.
3. **Reasoning per call.** Only one blob per turn exists.
4. **A digest of the context actually sent.** Without one, a remade context is trusted, not proven (research lesson 3). One digest per turn, `seon.edn/encode` of the messages hashed, would let the page mark any turn whose remake differs, for example after a renderer change.
5. **Cache-write tokens.** DeepSeek reports none, so this is not needed now.
6. **Threads and uptime.** The agent's `:stats` and hello carry pid, memory and t only (the prototype filed `seon.agent/an-agent-reports-its-threads`).
7. **The agent's last test run once root drops it.** The turn's basis `:results` covers the per-turn set. The judge's set is on main.
8. **A turn in flight.** Nothing is recorded until the model answers and the forms run. Research lesson 5 is a pending turn row from the basis, which is chosen before the model call. Here that means writing the basis at `basis!` time and the rest at the end.
9. **Messages as facts.** The prototype's message path is root's existing in-process delivery: the inbox, taken into the next turn's basis. It is recorded only through the basis, never on main. v1 wrote a `:seon.message` fact. Next needs a ruling on whether a message to an agent is a fact on main (with its handling recorded) or only the basis entry.
10. **Agent identity across boots.** Agent ids restart at a1 every boot. The prototype's verifier found calc/add's link opening today's a1, which works calc/abs. The newest attempt wins and the old one cannot be reached. An attempt id is the stable key.

## 3. How others do it (research subagent, 2026-10-23)

| Tool | Per step | Prompt per step | Long prompts | Live | Acting |
|---|---|---|---|---|---|
| LangSmith | run tree: LLM, tool and chain runs; inputs/outputs, tokens, cost, latency | stored | Compare: two traces side by side, runs paired, inputs diffed | runs visible while in progress (unconfirmed) | open a call in the Playground and re-run it |
| OpenHands | flat Action/Observation event stream | not in the stream | n/a | Socket.IO push, resume from `latest_event_id` | message while running; pause/resume; replay (warns: needs determinism) |
| SWE-agent | thought, action, observation, `query` | stored whole each step (`query`, quadratic growth) | pager toggles reduced/full | no | none |
| Inspect AI | ModelEvent: input, tools, output, raw API call, usage (input/cache read/cache write/output/reasoning), timing | stored, deduplicated by message pool (`input_refs`) | row shows only the new trailing messages; "Show all messages"; an API tab with the raw JSON | `pending` events; follow live | none (read-only) |
| Langfuse, Phoenix, OpenAI Agents SDK | spans with usage (cache read/write) | stored | tree view | batched | none |

Sources: docs.langchain.com/langsmith (observability-concepts, cost-tracking, compare-experiment-results); docs.openhands.dev (websocket-connection, convo-pause-and-resume, convo-send-message-while-running); swe-agent.com/latest/usage/trajectories and /inspector; inspect.aisi.org.uk (event reference, log-viewer).

Every one of them stores the prompt. Seon's basis-and-remake is unusual. It is cheap and exact while the renderer is deterministic, which is why lesson 3 (a digest of what was sent) matters.

**Lessons, ranked for Seon:**
1. A turn row defaults to what is new since turn N-1: the observations, messages, reply and calls. The full context goes behind a disclosure.
2. Diff turn N's context against turn N-1's. With remakes this is cheap and exact.
3. Prove each remake with a recorded digest.
4. Usage comes from the provider's answer. The cache-hit share per turn shows whether the prefix stayed stable.
5. Push turn-start (pending) and turn-end events.
6. Controls on the page say when they take effect ("read at the next turn").
7. Fork from turn N's basis rather than replay.
8. Show a tool result once, under its call.

## 4. The design (revised to the owner's shape, 2026-10-23)

The owner, condensed: "show useful data, not all the data"; everything is a render of the live system. Root's page renders all agents. Clicking one opens its window: the URL becomes the agent's (bookmarkable, back works) and its render tree runs. The agent window is chat-style: a large main pane with the input at the bottom, and a right column of live tiles for everything rendered, newest change at the top. Compact agent views are tiles on root's dashboard. Representations belong to the data.

### 4.1 Does a render nest other values' renders by reference, as reagent does? Today: no

From the code (main `b39cef5`):
- A render nests another value only by **calling** `seon.render/html` on it eagerly: `zoom-work` calls `(render/html task ...)`, `tile-health` calls it per proc, `cost-report` per verdict. The hiccup it answers holds the result, not a reference.
- The printer **never re-dispatches**. `seon.render`'s namespace doc says so: "A nested value renders by calling `render` on it; the printer never re-dispatches". A task map inside a printed vector prints as a map, not through `:seon.task/task`'s render.
- The serializer `seon.web.html/write!` (html.clj:82) prints any non-hiccup leaf with `print/text`, as text, never through its schema's render.
- Env (`:seon.render/env`) carries schema, budget and handle. It carries no context: one render per face, whether the value is a table cell, a panel or a tile.
- What is already reagent-like is the page's **units**: a layout names parts by id with their exact input, and the render proc re-renders a part only when its input changed. That is reagent's "re-render a component when its args change", one level deep.

### 4.2 The design: renders are functions and results (owner's shape, 2026-10-23, built)

The owner's corrections, in order: "should just be a normal render function and we can supply an optional argument. It's all just functions and results"; make it Clojure-like and explainable as React or Reagent. This replaced the per-size declaration map and its fallback chain, which I had proposed first.

**Where it lives.**
- `seon.render` holds the dispatch: `render`, `html`, `ai`, `render-of`, `schema-of`, and the printer as the default render (`print-html`, `print-ai`).
- `seon.web.html` expands calls: it is the one serializer, already the one place hiccup becomes text.
- The printer's auto-render goes in `seon.print`, once printer-eval's rewrite of `seon.print.emit` is on main.

**The signatures** (commit `2814928`):
- A render: `(fn [value opts] result)`. `opts` is `:seon.render/opts`, every key optional:
  - `:as`: `:inline`, `:block` (the default) or `:tile`;
  - `:budget`: tokens;
  - `:handle`: what cuts read through;
  - `:schema`: the producer's;
  - `:face`: `:html` (the default) or `:ai`.

  An `:html` render answers hiccup. An `:ai` render answers text or `{:text :truncated?}` (`:seon.render/text`).
- A schema declares one render per face, as before: `{:seon.render/html sym :seon.render/ai sym}`. There is no new declaration format.
- `(seon.render/render value opts)` returns the result. It looks up the function its override or schema names (`render-of`). The schema lookup is `schema-of`: the producer's `:schema`, the value's metadata, an error map's type, or an identity attribute through `by-identity`, one map lookup per key and never a validation. With no function found, it uses the printer.
- In hiccup, `[f value opts & children]` with `f` a function or var is a call. The serializer writes `(apply f value opts children)` in its place, expanded the same way. A keyword head is an element. Jolt answers `fn?` true for a keyword, so the serializer tests for one first.
- The printer, as the default render, takes the same props: `:inline` is one line (`print/line`), and `:block` or `:tile` is its structural print under the budget.

**Cost proof** (`seon.render-cost-test/render-calls-are-one-pass-over-their-nodes`, armed as at boot): a tree of render calls three wide, each node's budget a third of its parent's.

| Nodes | Per node | Bytes per node | Contracted calls per node |
|---|---|---|---|
| 100 | 3.4 µs | 8.5 KB | 11 |
| 1,000 | 4.0 µs | 8.2 KB | 11 |
| 10,000 | 4.3 µs | 7.9 KB | 11 |

The unit table prices a node at 7.9 KB, and the call count is exact. Wall time is not judged armed: the armed contracts check maps, as the printer's armed proof also found. The verdict is linear, nothing steeper.

**One value, both faces** (an attempt, `:seon.attempt/attempt`, through `seon.render/render`):

| Face | Output |
|---|---|
| `:ai` | `ended calc/square#1 green` |
| `:html` | `<tr class="at s-done o-green"><td><code>calc/square#1</code></td><td><a href="/agent/a4">a4</a></td><td><span class="pill s-done">done</span></td><td>2</td><td>5,465</td><td title="">green</td><td title="the attempt's wall time…">–</td></tr>` |
| printer `:html`, `:as :inline`, of `{:k [1 2 3]}` | `<code class="seon-inline">{:k [1 2 3]}</code>` |

**In React terms, for a newcomer:**
- A render is a component: a plain function of a value and its props (opts: the room it is shown in, its token budget).
- Children are its remaining arguments.
- A hiccup vector headed by a function is a component call, as `[task-row task {:as :inline}]` is in Reagent. A keyword head is an HTML tag.
- The one printer is the default component. Given any data, it lays it out, and wherever a nested value has its own component (its schema declares one), it calls that instead. So anything renderable renders, everywhere, by default.
- The render proc is React's reconciliation, one level up: a page's parts re-render only when their input changed, and only changed bytes are sent.

This paragraph opens docs/render.md.

**The printer's auto-render, both faces (built on land14's printer).** `seon.print/print-value` takes `:render`, and `seon.render`'s default renders pass `nested`. Where `seon.print.select/classify` meets a nested map whose schema declares a render, it makes a `:seon.print/rendered` leaf, sized by that render's one-line `:ai` text. `seon.print.emit` writes the text in the text output, and `[html-render v {:as :inline}]` in the hiccup, which the serializer expands. Both happen in the printer's one pass, with no second expander. For example, `{:newest <attempt> :count 3}` prints `{:count 3, :newest ended calc/square#1 green}`.

Cost proof, `seon.render-cost-test/the-printer-renders-nested-values-in-one-pass`:

| Attempts | Calls | Per attempt |
|---|---|---|
| 100 | 1,947 | 8.3 µs, 18.9 KB |
| 1,000 | 19,047 | 8.7 µs, 18.4 KB |
| 4,000 | 76,047 | 8.6 µs, 18.4 KB |

That is 19 contracted calls an attempt plus 47 fixed, linear.

### 4.3 The pages

**Root's dashboard (`/`).** As today, plus each agent's card becomes the `:tile` render of `:seon.render/agent`: a compact live view of status, why, its turn, its newest form and result. Clicking a card opens the agent window.

**The agent window (`/agent/{id}`, and `/attempt/{aid}` for an attempt of an earlier boot).**
- Clicking a card does `history.pushState` to `/agent/{id}`. The page's feed switches to that view (the web proc's `:views` message, as a tab's view is today), so back and forward work.
- The window is its own view, `:seon.render/agent-window`, rendered by units:
  - **Main pane (most of the screen): the chat.** The attempt's turns as a conversation, oldest first, and evaluations from the page or outside interleaved by time. Each turn is the `:block` render of `:seon.turn/turn-fact`: per form, its `;;` thought (the form's `:lead`, §5), the form, then ⇒ its result through the value's own render (`[:seon/render value {:as :block}]`, or the error map's), with ms and allocation inline. A turn opens with what was new since the last one (proofs that changed, messages it took), and ends with its usage line (tokens, % cached, cost). The context it read is behind one disclosure: the token table per part, a diff against the previous turn's context, the text, and the whole request.
  - **Input at the bottom.** One box: a line that reads as Clojure evaluates in the agent's REPL (`submit!`, origin `:ui`); a line starting `>` is a message to the agent (`message!`, "read at its next turn"). Interrupt and release are buttons beside it. All of them go through root's request table, which the server's ops also use.
  - **Right column: live tiles**, the `:tile` render of each value the window shows, newest change at the top: why, the attempt and cost, the task, proofs, branch (`:will-merge`), problems, the process. Each tile is a unit, ordered by its input's last change: the render proc stamps a unit's change seq, and the column's order is a unit too.

**Live, by event, no polling.** As in the prototype: an agent's `:commit` event re-reads its branch. Added: a turn-start event at `basis!` gives a pending row.

## 5. Thought next to the forms (owner direction, 2026-10-23)

The owner wants the context to explain the work as Clojure code being evaluated, and the agent to think in `;;` comments beside the forms it evaluates. Each comment is recorded with its form's evaluation, as `:form/lead` keeps a definition's leading comments. The eval tool may also get an optional `thinking` parameter, recorded with its call.

**What is recorded today:**
- The **turn** keeps each call's whole `:code`, comments included (`:seon.turn/calls`). So the thought is on record, but only as part of one string per call. Prose outside fences is turned into `;;` comments by `seon.reader/forgive`, so the scripted turns show `;; Squaring doubles?` above `(in-ns ...)`.
- The **evaluation record** keeps only the form's own text. `seon.repl/form-src` (repl.clj:39) cuts from the form's reader start to its end, so the `;;` lines before it are dropped. An evaluation from the page, from outside or from root has no record of any comment.
- **Source forms** do keep it. `seon.source/read-forms` (source.clj:32) answers `:lead`, the text between the previous form and this one, using `lead-end` (source.clj:20). `seon.program` writes it as `:form/lead` (program.clj:400) and carries it to a redefinition (program.clj:386).
- The **eval tool** takes `code` only (`seon.model/eval-tool`, model.clj:79-86: "Clojure forms; prose in ;; comments."). Reasoning is one blob per turn (`:seon.turn/reasoning`).

**What is missing, and the smallest change:**
1. **The REPL reads each form's lead as the source reader does.** `seon.repl`'s reading answers `:lead` per item, so one implementation is shared: `seon.source/lead-end` moves to `seon.reader`, and both readers call it. `seon.repl/record-tx` keeps `:lead` in the record, and the reply item carries it. The text after the last form (a trailing thought) is the call's `:tail`. This costs about 15 lines and no new attribute, because it lives inside the record's EDN.
2. **The call keeps the ids of its items** (`:seon.turn/calls [{:id :code :evals :items [n ...]}]`), so the page places each record, with its lead, under its call: thought → form → result. About 5 lines.
3. **Optional `thinking` on the eval tool.** Add a `thinking` string property to `eval-tool`, read in `seon.model/call-code`, and record it as the call's `:thinking` in `:seon.turn/calls`. The turn row shows it above the call's forms. Wire cost: DeepSeek's `reasoning_content` already arrives per reply. A per-call parameter is the model's choice to explain each call, which is cheap and lands in the cached scrollback. About 10 lines.
4. **The context says so.** The help page (docs/agent.md, the REPL section) states that work is Clojure evaluated at a REPL, that you think in `;;` comments before each form, and that they are recorded with it and shown back in your scrollback. The scrollback's `:evals` already echo each form. The lead comes back with it once (1) carries it, as `(transcript ...)` prints the item.

The debug view then needs no parsing of `:code`. It reads `:lead`, `:form` and the result per record, and `:thinking` per call.

## 6. Size and what it replaces

**Measured:** the prototype is +346 net `src` lines (on `jolt/agent-view-prototype`).

**Estimate** (net `src`, built on health's commit 1 and printer-eval's units):

| Part | Lines |
|---|---|
| One dispatch by context: `render` with `:as`, `declared` per context, the printer's three contexts, `expand` and `[:seon/render]` references, the printer emitting references for named values | +90 |
| Tile renders for the agent window's values (why, attempt, task, proofs, branch, problems, process) | +80 |
| Agent card as a tile | +20 |
| The agent window (layout, chat, input, tile column, its order) | +150 |
| The turn's `:block` render with the per-form chat, context table, diff and whole request | +120 |
| pushState navigation (one script line) | +10 |
| Turn-start pending row | +30 |
| Message and release (the prototype's) | +70 |
| §5 thinking beside forms | +30 |
| **Gross** | **≈600** |

**Deletes (≈ −200):**
- `zoom-head`, `zoom-work` (its attempts table), `zoom-context`, `history-list`'s separate pane, `render.proc/context-of` and `seon.context/recorded`;
- the hand-written inline cells that become `[:seon/render ... {:as :inline}]`: `attempt-row`'s link cell, `error-box`'s nested printing, `tile-tasks`' per-row attempt;
- the agent card's own eval rows (`eval-row`, `eval-meta`'s second copy) once the card is a tile;
- task `seon.render/a-turn-is-a-row-on-the-agent-page`.

**Net ≈ +400**, which I expect within 2x. That is about 4% of the 10k budget.

## 7. What the prototype's verifier found (fix list for whoever builds it)

**Mismatches between the page and the facts:**
1. **The model shown was the config default** ("deepseek-flash") for a scripted attempt whose `:env :model` is nil. The page should say "root's default (scripted provider)", or record the provider and model actually used on the attempt. The turn's `:seon.turn/model` is absent for scripted turns.
2. **The head's `evals 0` disagreed with the five evaluations listed.** It counts root's view's evals, which an ended agent no longer has. It should count the records.
3. **Agent ids are reused across boots**: calc/add's link opens today's a1. Key the pages by attempt (§4).
4. **The head said "3 turns" for three outside reads**, while the attempt records no turns. Count model turns and reads separately.
5. **The task box shows `running` for a done task.** Show `done` beside the execution.

**Observed, not a page bug:** calc/square turn 1's basis says square-test still got 0 after turn 0 defined `square` as `(twice x)`. `twice` is a stub on that branch until calc/twice merges, so `(twice 3)` is 0. The facts are right; the scripted reply's "The proof shows 6" is the script's.

**Usability findings:**
- times carry no zone;
- "1 ✓" sits beside a red proof name;
- the `:will-merge` panel is noisy ("map 1 items, depth 0");
- "scrollback, 1 earlier turns 53" lacks a unit and a singular;
- the evaluations list needs a heading;
- the message bar gives no "delivered" answer on the page;
- "its branch is being read" shows for 2-5 s;
- the whole-request text should say it is the model's input, so the turn's own call is not in it.

**What matched:**
- the a4 head;
- each turn's tokens, split, forms and red counts;
- the code and answers;
- the remade context, and the whole-request link;
- the judge's proofs and `:will-merge`;
- on the live a1: the eval box (`(* 6 7)` showed 42 live, and `*1` agreed), and the message bar (the message showed in `seon_context` and live in a new read row).

## 8. Build order (owner: build it; root's go 2026-10-23)

1. **Now:** §5's recording (the eval's `:lead`, items per call, the eval tool's `thinking`) and the reusable browser-tester brief (`notes/browser-tester.md`).
2. After health's commit 1 is merged in: the one dispatch by context and references (§4.2), with tests on production-shaped values.
3. The agent window (§4.3), built from the prototype's pieces with §7's fix list.
4. Browser testers (Haiku and Sonnet subagents on the brief), iterated until no mismatch; then one full run.

Merge printer-eval once it is on main.

**Still for root:** a ruling on messages as facts on main (today the basis records them); `/ns/{ns}` and `/data` (task `seon.render/ns-and-data-pages`); and threads in the agent's stats.
