# Root's web: the live admin interface (session W2)

A real-time dashboard of the whole system, a zoomed page per agent, and root's own context, all
from schema-declared render functions. Ported from `proto/web` (session L2's dashboard, L4's polish)
and W1's render selection (`proto/render`); rebuilt on `seon.root`'s API as two step-fn procs.

## Run it

```
bin/build seon
cd target && SEON_UTC_OFFSET=$(date +%z) ./seon web 6 calc     # 6 agents by hand + the six-task calc graph
open http://127.0.0.1:7777/
```

- `./seon web N` spawns N agents by hand. Each defines `index-by`, a proof and a cost proof
  (every third body is quadratic, so its cost proof is red), runs its tests and evaluates a value.
- `./seon web N light` gives each agent one evaluation only, with no test forks (for the size sweep).
- `./seon web N calc` or `./seon web N swarm M` adds tasks the reconciler works, with real
  run agents and the scripted model.
- It serves until stdin closes. The port is `:seon.config.web/port` (7777; `SEON_WEB_PORT` overrides it through the config file), on 127.0.0.1 only.
  A port that cannot be bound shows as data in the `:web` proc's health (`:listening false :error`).
- Every root boot serves it. That includes checks and tests: the first root gets the port and
  later ones report `:listening false`.

| Route | What |
|---|---|
| `/` | Root's view: tiles, health, the reconciler and tasks, effects, root's defects, agent cards, root's context, the event stream. |
| `/agent/<id>` | One agent zoomed: its process, its run and task on main, proofs (red assertions, cost proofs), every evaluation on its branch (form, value, ms, alloc, costliest functions), the context its model reads, its REPL. Esc goes back. |
| `/feed?view=root\|<id>` | The view's Datastar SSE feed. |
| `POST /call?fn=&agent=&args=` | The one stateless callback route. Its payload is the call. |
| `/context.txt` | Root's context: the system's AI face, the text root reads. |
| `/render.edn`, `/web.edn` | The render proc's and the web proc's own numbers. |

## How it works

- **Two procs**, declared in `resources/seon/system.edn` and following the step-fn rules:
  - `:render` (`seon.render.proc/render-proc`, `:compute`) renders; it never waits.
  - `:web` (`seon.web/web-proc`, `:io`) owns the listener and the tabs' channels; it renders nothing.
  - proto/web's raw render thread and atom are gone.
- **A pass.**
  1. An event (from a tap of root's events) or a sampler beat (500 ms) asks the api for its view.
  2. While that request is in flight, more events only queue. The round trip is the coalescing
     window, so no step sleeps.
  3. The answer is joined with every proc's health (Flow's ping), main's tasks and runs
     (re-read only when the writer's tx count moved), the series and the events. The result is
     the system value, `:seon.render/system`.
- **Renders.**
  - The system value's declared render (`seon.render.ui/system-page`) answers the layout and its
    units: each part's id, the exact value it reads, and its render.
  - Inputs before render: an unchanged input renders nothing.
  - Bytes before send: an unchanged fragment sends nothing.
  - A new agent's card is appended.
  - A new event's row is prepended. The list is not re-sent.
- **Fan-out.**
  - The web proc frames each patch once and offers it to every tab of that view.
  - A full tab turns stale and gets a keyframe: its view's page, composed from the retained
    fragments and morphed by id.
  - A tab still full at its keyframe is gone; Datastar reconnects a live one.
- **Selection** (`seon.render/render`), first match wins:
  1. the value's own override key;
  2. else the render its schema declares (`{:seon.render/html sym :seon.render/ai sym}` in
     `resources/seon/schemas/*.edn`, the schema then its parents);
  3. else the one value printer (`seon.print/print-value`).

  Declared renders:

  | Schema | Render |
  |---|---|
  | `:seon.render/system` | `system-page`; AI face `seon.render.ai/system` (root's context) |
  | `:seon.render/agent` | `agent-card` |
  | `:seon.render/agent-page` | `agent-page` |
  | `:seon.task/task` | `task-box` |
  | `:seon.run/run` | `run-row` |
  | `:seon.agent/item` and `:seon.cluster.eval/record` | `history-row` |
  | `:seon.root/health` | `health-cell` |
  | `:seon.render/proc-health` | `proc-health` |
  | `:seon.cost/verdict` | `verdict-box` |
  | `:seon.render/cost-report` | `cost-report` (HTML and AI) |
  | `:seon.render/event` | `event-row` |
- **Callbacks are data.** `{:seon/call 'ns/fn :seon/args {...} :seon/agent id}` on a form or
  button serializes to `@post('/call?...')`.
  - Root's functions (`seon.root/spawn! submit! run-tests! merge! merge-main! clone! interrupt! kill!`)
    become root's requests. Each is recorded on main as root's evaluation, origin `:ui`.
  - The REPL box is an eval in the agent with origin `:ui`.
  - Any other fn runs in the agent as `(seon.web/ui-call 'fn args)`. It is refused as data
    (`:seon.render/not-callable`) unless the var is `^:seon/callable`.
- **Root's context** is the system's AI face: problems first, then work, load, the costliest
  functions, and the newest events, cut by a token budget. The cut sections are named. It shows
  on the dashboard so the owner sees what root sees.

## Estimates (written before the timed runs)

What a pass must do when one agent evaluates, with N agents:

| Work | Proportional to |
|---|---|
| The api's view round trip | constant; the view is a persistent value |
| Grid order | N log N (a sort by id) |
| Unit inputs compared | N + 12; unchanged entries are identical, so O(1) each |
| The card that changed | one card |
| The swarm tile | N cells, about 110 characters each; Jolt's StringBuilder allocates about 308 B a character |
| Root's context | N (a scan for problems) |
| Fan-out | one string offered per tab |

| | 10 agents | 100 agents | 500 agents |
|---|---:|---:|---:|
| Render pass with a patch | 1.5 ms | 3 ms | 12 ms |
| Quiet pass (a beat: health and webstats only) | 0.5 ms | 1 ms | 4 ms |
| Patch p50, POST to marker in every tab | 6 ms | 9 ms | 20 ms |
| Bytes per eval per tab (card, swarm, row, context) | 7 KB | 15 KB | 53 KB |

proto/web measured a patch p50 of 22 ms at 100 live agents, with a 16 ms coalescing sleep.

## Measurements (release binary, `web/bench/sweep.sh` under the bench lock)

`target/seon web N light` with 3 tabs and 40 evals. Load was 4.4–7.5 (above method.md's 4;
a run where load went 141 → 57 was discarded). "Async" is proto/web's client, kept for
comparison; "sync" is a client with no thread handoff of its own.

| agents | patch p50, async (proto 22 ms at 100) | patch p50, sync | pass with patch, p50 | quiet pass, p50 | bytes per eval per tab | page GET p50 (bytes) |
|---:|---:|---:|---:|---:|---:|---:|
| 10 | 10.4 ms | 10.3 ms | 0.94 ms (est. 1.5) | 0.34 ms (est. 0.5) | 14.4 KB (est. 7) | 1.2 ms (60 KB) |
| 100 | 13.3 ms | 12.3 ms | 1.63 ms (est. 3) | 0.85 ms (est. 1) | 19.5 KB (est. 15) | 4.6 ms (435 KB) |
| 500 | 22.0 ms | 18.8 ms | 4.18 ms (est. 12) | 2.83 ms (est. 4) | 47.7 KB (est. 53) | 19.9 ms (2.1 MB) |

Where the time goes, at 10 agents:
- POST → the reply event: 2.0 ms. Root's own eval round trip is 1.4 ms (`bin/check rtt`).
- The reply event → its patch leaves the render proc: 1–2 ms.
- Leaving the web proc → received by the client: 0.8–1.0 ms. Every chunk carries `: at <ms>`, so this is measured.
- **An agent idle for about a second answers about 6.5 ms later.** Same agent, POST → patch
  p50: 3.0 ms after 10 ms idle, 3.3 after 0.1 s, 3.5 after 0.3 s, 9.9 after 1 s, 9.8 after 2 s.
  - The sweep picks a random agent, so most evals hit an idle one; that is the ~10 ms floor.
  - It is on the agent's side (its wake-up, or a backoff in the remote end's pump or the
    poller). It is not in the web path. Filed to root.
- At 500 agents, a pass's work is the O(N) inputs: the swarm tile's cells and root's context
  scan. The grid order is derived only when the agents map changes.
- An event waits 9 ms p50 for its pass behind the previous pass.

The bytes per eval are higher than estimated at 10 agents: an eval produces two events (reply,
commit), so two passes, and root's context panel (~2.5 KB) re-renders on each.

## Screenshots (`web/shots/`)

| File | What |
|---|---|
| `root-6-agents-calc-graph.jpg` | Root's view: 6 hand-spawned agents plus the calc graph's 12 runs. |
| `zoom-a1-red-cost-proof.jpg` | A zoomed agent whose cost proof is red: OVER, ns at n=100 is 24× its estimate. |
| `zoom-a8-attempt-calc-neg.jpg` | A run agent: run, task, proofs with red assertions, evaluations with their costliest functions. |
| `root-problems-tasks-health.jpg` | After root's feedback: problems first, observed task states with what blocks each, one-line health, ended agents. |
| `zoom-a9-merge-allowed-reds-big-alloc.jpg` | A merge run: "ended · merged", a flagged 88 MB evaluation, and each red proof labelled allowed with its task. |
