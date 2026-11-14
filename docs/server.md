# The server

Root's server is one socket on 127.0.0.1 where outside agents (a Claude session through MCP, a person with `nc`) send requests, one per line, and read one answer per line: `seon.server`.

A line opening `{"` is MCP's JSON-RPC; any other is an EDN request `{:op k ...}` (```seon.server/respond```). Both reach the same table of ops. The server is an outside caller of root's functions ([root](root.md#roots-functions)). ```seon.server/serve!``` listens on `:seon.config.root/server-port` (`bin/ports` gives each checkout its own; 0 serves none), its accept loop and connections on edge threads ([failure points](failure-points.md)). ```seon.main/root-serve``` is what `bin/system run` starts.

## Ops

The ops are one table, ```seon.server/ops```, keyed by `:op`, each entry holding its description and its arguments as JSON Schema; its answer is that op's method of the multimethod ```seon.server/op-answer```. `seon.server-test/each-op-has-its-answer` holds table and methods to one set of keys; each op is the MCP tool `seon_<op>`, and `bin/check mcp` compares the listed tools with the table.

- `:agents`: live agents and the tasks running or judged on main, newest first (```seon.server/live-tasks```, `seon.server/task-summaries`), over a reconciler index the server advances by what main changed (```seon.server/index-now```).
- `:eval`: Clojure evaluated in a running agent's process (kept on its branch, origin `:outside`) or as `"root"` with `sys` bound, both through `seon.repl/eval-string` ([processes](processes.md#the-agent-process)). On an agent it is one reply, `{agent, message-md, evals: [{intent, code}, ...]}`, as a model's is ([one tool](model.md#one-tool-repl)): `seon.turn/outside-reply!` runs every eval in order through `seon.repl/run-calls` ([every eval runs](decisions.md#every-eval-of-a-reply-runs-owner-2026-11-08)), and for an attached agent the reply is its turn. Plain `code` is refused on an agent and taken only by root. Root's eval is kept on main (```seon.repl/record-tx```), sent without waiting. Root has no branch, so `my.agent`, `my.db`, `my.task` and `my.branch` there answer `:seon.agent/no-branch`, naming `seon.agent/db` and `seon.agent/commit!`.
- `:context`: what the agent's model is sent now, as text, or with `wire` the provider's JSON body (`seon.turn/context-block`); it names an agent or a task (attaching one if none works it), runs no proof, and is kept on the agent's page ([render](render.md#the-page)). A root with no `--profile` has no provider, so `model` and `max_tokens` are null.
- `:message`: a person's words to an agent, one message fact on main (`seon.system/message!`, `seon.system/message-write`), which wakes the agent and reaches its branch at its next act ([messages](users.md#messages)).
- **Holders.** `bin/mcp` stamps each message with its client id (`"seon/client"`), and `:attach` records it as `:seon.agent/holder`. `:done`, `:eval` and `:message` on a held agent answer only that client or a request naming the agent's task; any other is refused `:seon.server/held` (```seon.server/held```). A request with no client (`bin/ask`, a test) attaches with no holder.
- `:create` declares a held task and answers `{:task id}`. `:write_back` is ```my.root/write-back!```. A request missing a `:required` argument is refused, naming it.
- `:attach`: root starts an agent on the task's branch with model `"outside"` (no turn loop) and answers its id at its hello, or the end it met first ([root](root.md#holders-and-refused-spawns)). A task whose agent is live answers that agent with `:shared true`; a branch another live agent holds is answered as a value naming it (```seon.server-test/an-attach-gives-the-caller-the-branch-or-names-who-holds-it```). `:fresh true` moves the task to a new branch from main, named by its last ending (```seon.task/restart-tx```), with an empty conversation. `:from` clones a live agent (`seon.system/clone!`) onto a sibling task with its own key; the task reaches the clone's branch at `seon.agent/begin-run!`.
- `:prove`: the tests a branch's change reaches, run once per branch and tx however many ask, through the test service ([merging](merging.md#the-test-service)); it names an agent's branch, else main, and optionally namespaces. It never ends the agent (```seon.server-test/a-prove-never-ends-the-agent-it-proves```).
- `:done`: the agent ends its work as a turn loop does (```seon.turn/hand-back!```), the end is judged ([root](root.md#runs-and-judging)), and the verdict is answered once main holds it (```seon.server/verdict```, through `seon.system/await-db`): the outcome, its reasons, and a held task's failure. It waits until the task's own work is decided (```seon.server/done-settled?```), through a gate with no process tests, but at a `:gate` with tests it answers with `:next` saying how many run and where their verdict lands. Its wait can be minutes: `bin/ask` and `bin/mcp` set no read timeout.

```seon.server/answer``` dispatches one EDN request; an unknown op is the `:default` method's error. A line that does not read, or an op that throws, answers the typed `:seon.server/defect`: the server is an entrant boundary ([failure points](failure-points.md)).

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
bin/system start                                      # its own ports and store, no provider
bin/mcp call attach task=ns/name                      # the agent's id
bin/mcp call context agent=ID                         # wire=true: the request body
bin/mcp call eval agent=ID message-md='Reading.' intent='how it stands' code=- <<'EOF'
(my.task/status)
EOF
bin/mcp call done agent=ID                            # judged, merged into this root's main when green
bin/system stop
```

A restart leaves an attached task `:lost` until someone attaches it again; its session and branch continue.

A file edited on disk reaches this root's main only when imported: `bin/mcp call eval agent=root code='(my.root/import! sys #{"src/seon/x.clj"})'` (no set: the tree's changes) ([source](source.md#files-and-the-db-two-directions)). A fix to Seon itself is made in the worktree's source and proved with `bin/prove`. `bin/worktree --remove NAME` ends it.
