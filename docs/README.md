# The wiki: a map

Seon on Jolt's docs, one concept per page, addressed `topic#section`: find the page where your problem lives, read its leading sentences, and follow its links. The wiki is the one home for how the system works ([AGENTS](../AGENTS.md)). A person arriving starts at the repository's [README](../README.md).

## How the wiki is kept

The wiki stays healthy by these rules, the last four read by a class of problem ([health](health.md#a-stale-section)):
- **One home per concept.** A var, mechanism or pattern is explained in the one section that cites it at code depth (` ```name``` `); other sections link there and cite it at summary or doc depth ([references](references.md)).
- **Sections state the current truth by topic.** A change rewrites the section it concerns; nothing is a dated log.
- **Every section leads with one sentence that stands alone**, which an agent sees at summary depth. A section with none is a `:leadless-section` ([health](health.md#a-section-with-no-lead)).
- **A section that describes code is read again when that code changes.** The loader records the form of each var a section cites; a changed form leaves the section a `:stale-section` until it is edited or reviewed (`my.doc/reviewed!`, also an empty git commit with a `Reviewed: docs/x.md#heading` trailer).
- **Every page is reachable from this map.** A page nothing links to is an `:orphan-page` ([health](health.md#an-orphan-page)).
- **Every problem class links the section that explains its fix**, or it is a `:problem-unlinked` ([health](health.md#a-problem-with-no-page)).

## The pages

Each page of the wiki, in one line.

- [parity](parity.md): each v1 capability and where it stands here.
- [root](root.md): the control process: its graph of procs, boot, holders, root causes at quiescence.
- [reconcile](reconcile.md): how a task holds how its work stood and ended, and the reconciler's index.
- [processes](processes.md): the fork server and each agent's process.
- [server](server.md): root's request server, its ops and MCP's `seon_*` tools.
- [dashboard](dashboard.md): the live web page: the render proc, the web proc, callbacks as data.
- [errors](errors.md): the one error map, recorded once per cause, and every problem read by one report.
- [core-async](core-async.md): channels, buffers, `go` and fibers as Jolt implements them.
- [flow](flow.md): core.async.flow on Jolt and the proc contract every component follows.
- [config](config.md): every limit, bound and default as one documented key, read through one resolver.
- [vectors](vector.md): semantic search in the store, fused with the call graph.
- [data modeling](data-modeling.md): entities, refs and index seeks: how to design data and read it cheaply.
- [storage](storage.md): Datahike index trees on LMDB: commits, history, compression, values out of line, collection.
- [sharing](sharing.md): where a fact lives, how a merge works, and what each operation should cost.
- [failure points](failure-points.md): every place the outside world can fail and its one catching edge.
- [health](health.md): the open problems, recorded or derived from the program's facts.
- [clocks](clocks.md): where time decides and where it is only measured.
- [resolution](resolution.md): where a name is resolved or a Var defined at run time, and why.
- [source](source.md): agents change Seon's own functions at the REPL; a merge writes them back.
- [references](references.md): what a text cites, read by one parser, pulled at its delimiter's depth.
- [ctx](ctx.md): the agent's entity as one map, `@ctx`, and the one render the model and the person read.
- [render](render.md): every view is the one printer or a render declared on the value's schema.
- [prompt](prompt.md): the system prompt every agent reads first.
- [agent](agent.md): the help page, which `my.agent/doc` reads by section.
- [turn](turn.md): the turn loop: observe, act, end, and who runs which tests.
- [model](model.md): the provider a profile names, the one tool, thinking, usage.
- [cost](cost.md): what a cost proof measures and why.
- [tasks](tasks.md): all work is tasks linked by `:needs`; a request is one transaction.
- [users](users.md): a user is an agent and a session is a branch of its branch.
- [testing](testing.md): how a test is written: on a small world, cheaply, one default way.
- [checks](checks.md): the end-to-end proofs `bin/check` runs.
- [merging](merging.md): how a branch merges into main.
- [machine](machine.md): the machine Seon is built on, and the rules that keep it bounded.
- [plan](plan.md): what is in flight, open owner questions, and the steps to the goal.
- [decisions](decisions.md): the owner's rulings, each with its words and date.
- [sessions](sessions.md): how the Claude sessions share the work.
- [vocabulary](vocabulary.md): the words this repo uses.
- [forks](forks.md): the libraries we carry changes in, what each carries and why.
