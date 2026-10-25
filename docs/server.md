# The server

Root's server is one socket on 127.0.0.1 where outside agents (a Claude session through MCP, a person with `nc`) send requests, one per line, and read one answer per line: `seon.server`.

A line opening `{"` is MCP's JSON-RPC; every other line is an EDN request, a keyword-keyed map `{:op k ...}` (```seon.server/respond```, the protocol rule). Both reach the same table of ops, so MCP and EDN callers get the same answers. The server is an outside caller of root's API, as `bin/check` is: each op calls `seon.root` functions (`seon.root/submit!`, `seon.root/spawn!`, `seon.root/judge!`), which put requests on the api proc's `:ext` channel ([root](root.md#the-root-graph)).

```seon.server/serve!``` listens on `:seon.config.root/server-port` (`bin/ports` gives each checkout its own; 0 serves none): the accept loop and each connection run on edge threads, because accept and reads block ([failure points](failure-points.md)). ```seon.main/root-serve``` is what `bin/root run` starts: root booted, the server listening, until stdin closes or a signal arrives ([root](root.md#boot)).

## Ops

The ops are one table, ```seon.server/ops```, keyed by a request's `:op`; each entry holds its description, its arguments as JSON Schema and its answer function, and nothing else lists them: a new op is a new MCP tool `seon_<op>` (`seon_agents`, `seon_eval`, `seon_context`, `seon_message`, `seon_attach`, `seon_done`, `seon_prove`), and `bin/check mcp` compares the tools the server lists with the table, never a count.

`:prove` runs the tests a branch's change reaches, once per branch and tx however many ask ([merging](merging.md#the-test-service)): it names an agent (its branch at its head) or main, and optionally the test namespaces.

- `:agents`: live agents and every task's summary (`seon.server/task-summaries`: execution, done, blocked-by, its runs, its failure).
- `:eval`: Clojure evaluated in a running agent's process (recorded on its branch, origin `:outside`) or as `"root"` in root's process, with `sys` bound to root (`seon.server/*sys*`). Both go through the one REPL path, `seon.repl/eval-string` ([processes](processes.md#the-agent-process)).
- `:context`: the current-state block of the task an agent works, as the model would read it now (`seon.turn/context-block`; it names an agent or a task, attaching one if none works it), recorded with origin `:context`, a row of the agent's page like any evaluation ([render](render.md#the-page)).
- `:message`: the owner's words to a running agent (`seon.root/message!`, the page's message bar sends the same request): root delivers them, and the agent's next turn reads them in its block ([render](render.md)).
- `:attach`: root starts an agent on the task's branch with model `"outside"`, so no turn loop runs in it, and answers its id once the agent is ready (its hello), or the end it met first, which is the run's death ([root](root.md#holders-and-refused-spawns)).
- `:prove`: the tests a branch's change reaches, run once per branch and tx however many ask, through the one test service (`seon.prove`, [merging](merging.md#the-test-service)); it names an agent's branch, else main.
- `:done`: the agent ends its run as a turn loop does (```seon.turn/hand-back!```: its proofs run, its end `:green` or `:red` with the reasons, committed on its branch), then the run goes to the reconciler's `:judge` port and is judged ([root](root.md#runs-and-judging)).

```seon.server/answer``` dispatches one EDN request to its op; an unknown op answers the one error map. A line that does not read, or an op that throws, answers that request as the typed `:seon.server/defect`: the server is an entrant boundary ([failure points](failure-points.md)).

## MCP

MCP is the same table seen through JSON-RPC: ```seon.server/mcp-reply``` answers `initialize`, `ping`, `tools/list` (each op as the tool `seon_<op>`) and `tools/call`, and a method or tool it does not know is the JSON-RPC error that names the ones it has.

A tool's text is what a model should read: `seon_context`'s is the block itself, an evaluation's text is its response as the one printer prints it (`seon.print/response-text`), an error is its render, and anything else is the whole value by the one printer ([render](render.md)). `seon.server/protocol-versions` lists the protocol versions it answers.

`bin/mcp` is the stdio relay a client runs: stock perl, a few MB, one connection per message to the port in `runs/root.port`, so a root restarted between calls (`bin/root next`) costs the client nothing. A message root does not answer (none running, not listening, died mid-request) gets a JSON-RPC error saying why. `seon.check/mcp-proof` (`bin/check mcp`) proves it end to end from a separate process, with the relay's footprint under `:seon.config.machine/relay-mb`.

Working a task this way is the turn loop's path with the model outside: [turn](turn.md#an-outside-agent).

## Where it falls short today

An outside agent (a Claude session through the server) does not see what a model sees, so dogfooding through the server measures a different context.
- `:context` answers ```seon.turn/context-block```, the story and the turn sections, not the parts a model's request is made of: `seon.server/the-outside-context-is-what-a-model-is-sent`.
- `:done` answers `{:judging ...}` and ends the agent, so it never sees the verdict: `seon.server/done-answers-the-verdict`.
