# The server

Root's server is one socket on 127.0.0.1 where outside agents (a Claude session through MCP, a person with `nc`) send requests, one per line, and read one answer per line: `seon.server`.

A line opening `{"` is MCP's JSON-RPC; any other is an EDN request `{:op k ...}` (```seon.server/respond```). Both reach the same table of ops. The server is an outside caller of root's functions ([root](root.md#roots-functions)). ```seon.server/serve!``` listens on `:seon.config.root/server-port` (`bin/ports` gives each checkout its own; 0 serves none), its accept loop and each connection fibers parked on their sockets, at most `:seon.config.root/server-connections` open, and each op run on a `:server-op` effect worker (```seon.server/perform-op```), so an op that blocks holds its worker, never a carrier or another connection ([failure points](failure-points.md)). ```seon.main/root-serve``` is what `bin/system run` starts.

## Ops

The ops are one table, ```seon.server/ops```, keyed by `:op`, each entry holding its description and its arguments as JSON Schema; its answer is that op's method of the multimethod ```seon.server/op-answer```. `seon.server-test/each-op-has-its-answer` holds table and methods to one set of keys; each op is the MCP tool `seon_<op>`, and `bin/check mcp` compares the listed tools with the table.

- `:agents`: live agents (each its branch, status, task and process `:pid`) and the tasks running or judged on main, newest first (```seon.server/live-tasks```, `seon.server/task-summaries`), over a reconciler index the server advances by what main changed (```seon.server/index-now```).
- `:eval`: Clojure evaluated in a running agent's process (kept on its branch, origin `:outside`) or as `"root"` with `sys` bound, both through `seon.repl/eval-string` ([processes](processes.md#the-agent-process)). On an agent it is one reply, `{agent, message-md, evals: [{intent, code}, ...]}`, as a model's is ([one tool](model.md#one-tool-repl)): `seon.turn/outside-reply!` runs every eval in order through `seon.repl/run-calls` ([every eval runs](decisions.md#every-eval-of-a-reply-runs-owner-2026-11-08)), and for an attached agent the reply is its turn.  Root's eval is kept on main (```seon.repl/record-tx```), sent without waiting. Root has no branch, so `my.agent`, `my.db`, `my.task` and `my.branch` there answer `:seon.agent/no-branch`, naming `seon.agent/db` and `seon.agent/commit!`.
  An eval as `"root"` answers when its forms return and takes no bound of its own. Its `*in*` is the request's own `input` (empty when none is sent), so a read of stdin answers at once; a read that blocks in a foreign call (a socket) still holds that call, so a long probe is bounded in the form itself, `(deref (future ...) ms default)`.
- `:context`: what the agent's model is sent now, as text, or with `wire` the provider's JSON body (`seon.turn/context-block`); it names an agent or a task (attaching one if none works it), runs no proof, and is kept on the agent's page ([render](render.md#the-page)). A root with no `--profile` has no provider, so `model` and `max_tokens` are null.
- `:message`: a person's words to an agent, named by its id or by its task (`task=session/<area>` for a session, as `:inbox` takes it, ```seon.task/agent-key```), one message fact on main (`seon.system/message!`, `seon.system/message-write`), which wakes the agent and reaches its branch at its next act ([messages](users.md#messages)).
- **Holders.** `bin/mcp` stamps each message with its client id (`"seon/client"`), and `:attach` records it as `:seon.agent/holder`; a client attaching a live agent another holds takes the hold over. `:done` and `:eval` from a caller with a client act on the agent that client last attached (```seon.server/own-agent```, ```my.root/held-agent```), so another session's agent is not addressable by them; a client that attached none is answered that it holds none. A request with no client (`bin/ask`, a test) names its agent. `:message` is addressed to its recipient, any agent.
- `:create` declares a held task and answers `{:task id}`. `:write_back` is ```my.root/write-back!```. A request missing a `:required` argument is refused, naming it.
- `:attach`: root starts an agent on the task's branch with model `"outside"` (no turn loop) and answers its id at its hello, or the end it met first ([root](root.md#holders-and-refused-spawns)). A task whose agent is live answers that agent with `:shared true`; a branch another live agent holds is answered as a value naming it (```seon.server-test/an-attach-gives-the-caller-the-branch-or-names-who-holds-it```). `:fresh true` moves the task to a new branch from main, named by its last ending (```seon.task/restart-tx```), with an empty conversation. `:from` clones a live agent (`seon.system/clone!`) onto a sibling task with its own key; the task reaches the clone's branch at `seon.agent/begin-run!`.
- `:prove`: the tests a branch's change reaches, run once per branch and tx however many ask, through the test service ([merging](merging.md#the-test-service)); it names an agent's branch, else main, and optionally namespaces. It never ends the agent (```seon.server-test/an-outside-agents-prove-names-what-it-runs-and-done-answers-the-judge```).
- `:inbox`: waits for a message to an agent (by `agent`, or `task`, a session's `session/<area>`), answering at once with what its branch has not taken, else when one is written, else ``:seon.server/no-message`` at `:seon.config.root/inbox-wait-ms`; run it in the background.
- `:done`: a session's land, or any agent's: with `checkout`, its checkout's changed files are imported onto the agent's branch first (```my.branch/import!``` with no paths; a file that does not read refuses the land), then the agent ends its work as a turn loop does (```seon.turn/hand-back!```), the end is judged ([root](root.md#runs-and-judging)), and the verdict is answered once main holds it (```seon.server/verdict```): the outcome, its reasons, and a held task's failure. It waits on the judge's own end, never on the whole root's quiet (```seon.system/judge!```, answered by ```seon.reconcile/answer-judged```): until the task's own work is decided (```seon.reconcile/settled?```), through a gate with no process tests, and a judge that leaves its task `:running` answers the typed `:seon.reconcile/judge-left-running`, but at a `:gate` with tests it answers with `:next` saying how many run and where their verdict lands. Its wait can be minutes: `bin/ask` and `bin/mcp` set no read timeout.

```seon.server/answer``` dispatches one EDN request; an unknown op is the `:default` method's error. A line that does not read, or an op that throws, answers the typed `:seon.server/defect`: the server is an entrant boundary ([failure points](failure-points.md)).
- **Every call is answered on its own.** Each op runs on a `:server-op` worker under its own interrupt token with its declared work bound (```seon.intr/run```, `:seon.config.fx/bounds` `:server-op`): one past it answers its caller the typed `:seon.repl/ticks-spent` or `bytes-spent` inside its `:seon.server/defect`, and its worker goes on (```seon.server-test/an-op-past-its-bound-answers-its-caller-and-the-server-goes-on```). Root's own `:eval` has no bound (a whole-program load allocates tens of GB): an interrupt ends it.
- **A caller stops its own ops.** The `:server-op` owner makes each request's token at dispatch and keeps it until the outcome (```seon.fx/stop```). `:interrupt` (`seon_interrupt`) stops every op of its client, and MCP's `notifications/cancelled` the one request it names; both are answered on the connection's fiber, never queued behind the ops they stop. A stopped op answers `:seon.server/interrupted`, a queued one `:seon.fx/stopped` (```seon.server-test/a-caller-stops-its-own-op```). A caller that only closes its socket stops nothing: `bin/mcp` and `nc` half-close after sending, so the server cannot tell a gone caller from one waiting to read.
- `:eval` on an agent takes plain `code` (and `intent`) as a reply of that one eval, for every caller. `:import` imports a file onto the agent the caller's checkout holds (`bin/seon-import`).

## MCP

MCP is the same table seen through JSON-RPC: ```seon.server/mcp-reply``` answers `initialize`, `ping`, `tools/list` and `tools/call`; an unknown method or tool is an error naming the ones it has (`seon.server/protocol-versions`).

A tool's text is what a model should read: `seon_context`'s is the block, an evaluation's is its response printed by `seon.print/response-text` (with `:main` when main has moved past the branch's base, `seon.agent/main-difference`), an error its render, anything else the value by the one printer ([render](render.md)).

`bin/mcp` is the stdio relay a client runs: perl, one connection per message to the port in `runs/root.port` (read in `src` only by ```seon.fx/bound-port```), so a restarted root costs the client nothing; a root that does not answer gets a JSON-RPC error saying why. `bin/check mcp` (`seon.check/mcp-proof`) proves it end to end, the relay under `:seon.config.machine/relay-mb`. Working a task this way: [turn](turn.md#an-outside-agent).

## Working a task from a worktree

A Claude agent works a task as a Seon agent does, on its own root and branch, and plays the model: what it reads and what it evals are what a model reads and evals, and no model is called.

A session's `seon_*` tools reach the root of the checkout the session started in. A session's worktree reaches its own root with `bin/mcp call OP KEY=VALUE` (the tool `seon_<OP>`, its checkout's `runs/root.port`, the checkout as client); `--port PORT` reaches another root.

```
bin/worktree NAME; cd "$(bin/worktree --path NAME)"
nice -n 15 bin/build seon                             # only when this root must run this tree's code
bin/system start                                      # its own ports and store; its agents call DeepSeek (paid)
bin/mcp call attach task=ns/name                      # the agent's id
bin/mcp call context agent=ID                         # wire=true: the request body
bin/mcp call eval agent=ID message-md='Reading.' intent='how it stands' code=- <<'EOF'
(my.task/status)
EOF
bin/mcp call done agent=ID                            # judged, merged into this root's main when green
bin/system stop
```

A restart leaves an attached task `:lost` until someone attaches it again; its session and branch continue.

A file a session writes reaches its agent's branch by itself: the hook in `.claude/settings.json` runs `bin/seon-import` after each Edit and Write, which asks the standing root's `:import` op to import the file onto its session's agent. The checkout's branch names the session (```seon.fx/checkout-branch```: a worktree on `jolt/<area>` is the session that works `session/<area>`, AGENTS.md section 2), and ```my.root/branch-agent``` answers that task's live agent, whoever attached it. The import is an eval at that agent's REPL that is no turn of its (```my.branch/import!```), so an edit moves the branch, never the agent's turn or its messages (`seon.server-test/a-checkout-names-the-agent-it-holds`). A checkout whose session has no live agent imports nothing and says how to attach one.

A file edited on disk reaches this root's main only when imported: `bin/mcp call eval agent=root code='(my.root/import! sys #{"src/seon/x.clj"})'` (no set: the tree's changes) ([source](source.md#files-and-the-db-two-directions)). A fix to Seon itself is made in the worktree's source and proved with `bin/prove`. `bin/worktree --remove NAME` ends it.
