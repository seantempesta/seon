# The agent debug view: research and design (2026-10-23)

Worktree session agent-view. Owner, 2026-10-23: "Lets get the web UI working better so I can get a debug view into any agent." Then: "just do exploratory research for now." This note is the research and the design. No `src` change is proposed for merging yet.

**Evidence.** The prototype's full test run (`bin/test`, 448 tests) was 446 green; the two reds were the digest-stability tests (`seon.program-test/the-same-tree-gives-the-same-digests-in-any-checkout-and-any-run`, `seon.agent-test/a-wide-reach-reuses-unchanged-results`), not investigated (files were committed during the run). Before the redirect, this session built a working prototype and ran it on its own root, with the scripted model working calc/add, square, twice and neg. A browser subagent then compared the pages against `seon_eval` facts. The prototype is kept on branch `jolt/agent-view-prototype` (commits `42a9d44`, `1bcf80f`, `f083e7e`, `56a9650`; +346 net `src` lines) as evidence only. Root decides whether any of it becomes tasks. Its numbers and the verifier's findings are below.

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

## 4. The design

**Keys.**
- A page per attempt is stable across boots: `/attempt/{attempt-id}`.
- `/agent/{id}` redirects to the agent's newest attempt, or shows a hand-spawned agent with no attempt as today.
- A whole turn is served at `/attempt/{aid}/turn/{n}` as text: the request the model read, plus its answer.

**One value, one layout.** The value is `:seon.render/agent-page`, the declared `:seon.render/html` `seon.render.ui/agent-page`. Its units, each reading exactly its input:

| Unit | Facts | Render |
|---|---|---|
| head | root's view entry (while live), the attempt, the task, totals over turns | `ui/zoom-head`. Why it is as it is, one line each (`ui/why`): status and running form; attempt outcome and reason; task held with its failure. Model, start and end (with the zone), turns, tokens, cost (`seon.model/cost`: off-peak configured prices; a turn whose provider gives no cache split is "not priced", never 0) |
| actions | live?, held task | `ui/zoom-actions`: eval box, message bar ("read at its next turn"), interrupt, release; each says when it acts |
| turns | the attempt's `:seon.turn/*` on its branch | `:seon.turn/turn-fact`'s declared html, `ui/turn-row`, rendered once per turn while its facts are equal (detailed below) |
| evaluations | `:seon.cluster.eval/record`s not in any turn (UI, outside, root) | `ui/history-row` as today, under its own heading |
| task | main | `:seon.task/task`'s declared render, showing **done** beside execution |
| proofs | the newest turn's `:results`; the judge's `:seon.attempt/results`; the last run if root holds it | `proofs-box`, each report "reused" or "run"; the merge-deferred set once it is on main |
| branch | the newest turn's `:will-merge`; the attempt's conflicts and unmerged keys | `branch-box`, with `:will-merge` printed by the printer as the model reads it (one line per key, not the nested panel noise) |
| problems | `seon.problems/report` rows naming this agent, attempt or task | the row's AI render text (`seon.render.ai/problem-row`, the words root's context uses), with its evidence |

**A turn row reads thought → form → result.** The row has two parts.

The header:
- turn N or read N, and pending while in flight;
- tokens, % cached, cost, finish, model;
- the split (tests, context, model, evals, record) with units.

The body, in order:
1. **New since the last turn:** the proofs that changed status, the refusals, the messages it took, and the run errors.
2. **Reasoning**, collapsed, until per-call thinking exists (§5).
3. **Per call, then per form:**
   - `;;` thought (the form's lead);
   - the form;
   - ⇒ its result or ✕ its error, through the declared renders (the error map's render; a value's schema render) and the record's ms and allocation;
   - the call's answer as the model read it, collapsed.
4. **The context it read**, collapsed:
   - the token table per part and key (`seon.context/sections`);
   - a diff against the previous turn's context;
   - the text under `:seon.config.render/turn-text-budget`, its cut linking the whole request.

**Live, by event, no polling.**
- An agent's `:commit` event for a watched agent re-reads its branch (`ask-branches` in the prototype).
- Proposed: a `:turn-start` event at `basis!` (pending row) and the `:commit` at its end.
- An ended agent's branch is read once.
- The writer's `:branch-db` reads a branch as a task names it, under its main (`seon.db/on-main`).

**Acting.** Each action is a UI call of root's request table (`seon.root/request`), which the server's ops also send. There is no second path.
- **eval**: `submit!`, origin `:ui`.
- **message**: `message!`, using root's existing delivery (`deliver-message`), shared with the server's new `:message` op.
- **interrupt**: the web proc's SIGUSR1, as today.
- **release**: `release!`, which is `my.root/run!`'s one transaction.

The agent takes a delivery into its inbox wherever it reads its socket, and its next turn takes the inbox into its basis (`:messages`). The prototype proved both through the server and through the page, live.

**Deferred:**
- fork-from-turn-N (lesson 7);
- `/ns/{ns}` and `/data` (filed as `seon.render/ns-and-data-pages`; not cheap, because next has no namespace-to-agent assignment).

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

The prototype measured +346 net `src` lines for parts 1-2 of §4, minus the turn diff, the digest, the attempt keying and §5. Its pieces:

| Piece | Lines |
|---|---|
| `ui` (head, why, actions, turn-row, turns-list, branch, problems) | +200 |
| `render.proc` (turn rows, problems, ask-branches, turn-text) | +60 |
| `context` (`remade`, `sections`, `request-text`) | +40 |
| `root`, `server`, `web` (message!, release!, the `:message` op, the turn route) | +45 |
| `agent` and `turn` (inbox on read, messages into the basis) | +25 |
| `model/cost` | +18 |
| `writer`'s on-main read | +5 |

**Estimate for the full design:**

| Part | Lines |
|---|---|
| Prototype, refined against the verifier's findings | ~350 |
| Attempt-keyed routes | +30 |
| Context diff against the previous turn (one `clojure.data/diff` or a line diff through the printer) | +40 |
| Context digest per turn, and its mismatch mark | +20 |
| Pending turn row and `:turn-start` event | +30 |
| §5 (lead per form, items per call, `thinking`) | +30 |
| **Total** | **~500** |

The total is about 5% of the 10k-line budget. I expect it within 2x.

**It replaces or deletes:**
- `zoom-context` (the one-read panel);
- `render.proc/context-of`;
- `seon.context/recorded` (its one reader goes);
- the page's attempts table;
- task `seon.render/a-turn-is-a-row-on-the-agent-page` (its proof included);
- parity rows "`/agent/{id}/debug`" and "`/agent/{id}/message`" (missing → present).

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

## 8. Proposed tasks (for root to write, if it accepts the design)

1. **`seon.render/a-debug-view-of-any-agent`**: §4 without §5, from the prototype branch, with the fix list in §7.
2. **`seon.repl/a-forms-lead-is-recorded-with-its-eval`**: §5 items 1-2, plus the help page text (item 4).
3. **`seon.model/the-eval-tool-takes-thinking`**: §5 item 3.
4. **`seon.turn/a-turn-records-what-it-sent`**: the context digest, and the pending row with its `:turn-start` event.
5. **A ruling on messages as facts on main.** v1 did this; the prototype records messages in the basis only.
6. **`seon.agent/an-agent-reports-its-threads`** and **`seon.render/ns-and-data-pages`**, already drafted on the prototype branch.
