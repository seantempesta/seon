# The wiki: a map

Seon on Jolt's docs, one concept per page, addressed `topic#section`; find the page where your problem lives, read its leading sentences, and follow its links.

## How the wiki is kept

The wiki stays healthy by eight rules, each a page's or section's to keep, the last three read by a class of problem ([health](health.md#a-stale-section)):
- **Patterns live in the topic sections, linked, never in a catch-all file**. A pattern's home is the section where its problem lives: the idiom, its "Not this", its one implementation cited at code depth. Every section with the same problem links to that home with an ordinary link; its backlinks are its instances, so pulling one shows the others, and one fix can unify them.
- **One home per concept.** A var or mechanism is explained in one section, the one that cites it at code depth (` ```name``` `); others cite it at summary or doc depth and link there instead of re-explaining it.
- **Every section leads with one sentence that stands alone.** At summary depth an agent sees a section's heading and that sentence (as in v1), and decides by it whether to read on; then the body; then links to related sections. A section with none is a `:leadless-section` ([health](health.md#a-section-with-no-lead)).
- **Everything is linked.** Every section is reachable from the map, every page links its neighbours, and nothing is orphaned or duplicated.
- **Sections state the current truth by topic.** A change rewrites the section it concerns; nothing is a dated log.
- **A section that describes code is read again when that code changes.** The loader records the form of each var a section cites when its text is written; a changed form leaves the section a `:stale-section` until it is edited or reviewed (`my.docs/reviewed!`, which is also an empty git commit with a `Reviewed: docs/x.md#heading` trailer, so the review survives a rebuilt store).
- **Every page is reachable from the map or another page.** A page nothing links to is an `:orphan-page` ([health](health.md#an-orphan-page)).
- **Every problem class links the section that explains its fix.** One that does not is a `:problem-unlinked` ([health](health.md#a-problem-with-no-page)).

References and their depths: [references](references.md).

## What next replaces

The page that says where each capability of v1 Seon stands here.

- [parity](parity.md): each capability of v1 Seon (`~/src/seon`), and where it stands here: present, partial, missing, or left behind on purpose ([BAGGAGE](../BAGGAGE.md)).

## The running system

The pages on the processes that run: root, each agent's fork and the server that answers them.

- [root](root.md): the control process: the root graph's procs, boot, runs and judging, holders and refused spawns, root causes at quiescence.
- [processes](processes.md): the fork server and each agent's process: single-threaded, forked from main's db value, one socket to root.
- [server](server.md): root's request server: its ops, one table, and MCP's `seon_*` tools over it.
- [dashboard](dashboard.md): the live web page: the render proc, the web proc, callbacks as data.
- [errors](errors.md): the one error map, recorded once per cause; every problem read by one report; overrun charged to its producer.
- [flow](flow.md): what core.async.flow is on Jolt, the proc contract every component follows, and its hazards. Read [core-async](core-async.md) first: channels, buffers, `go` and fibers as Jolt implements them.
- [config](config.md): every limit, bound and default is one documented key in one aero file, read through one resolver.
- [vectors](vector.md): semantic search native to the store: each form, section and plan embedded once in root (llama.cpp), `seon.vector/near` a Datalog function over a two-level C search, fused with the call graph into the context's helpers.
- [storage](storage.md): one store of Datahike index trees on LMDB; what a commit writes, history, node compression, large values out of line, and collection.
- [failure points](failure-points.md): every place the outside world can fail, its one catching edge and its declared error; nothing else catches.
- [health](health.md): the open problems, recorded or derived from the program's facts, read when looked at and never polled; what a branch caused refuses its done.
- [clocks](clocks.md): where time decides (only the owner's exceptions) and where it is only measured; `bin/laws clocks` and `measures` name every site.
- [resolution](resolution.md): where a name is resolved or a Var defined at run time, each with its reason; `bin/laws resolution` names every site.

## The program and its loop

The pages on how agents change Seon's own program and how a text's references and renders show it.

- [source](source.md): agents change Seon's own functions at the REPL on their branches; a merge merges them on main, writes the files back and runs them next.
- [references](references.md): what a text cites, read by one parser (`seon.ref`), becomes its references, pulled into a context at the depth its delimiter asks.
- [ctx](ctx.md): the agent's record as one map, `@ctx`, kept by the change; the model reads it printed, the page draws it as a block, each key by its own schema's render.
- [render](render.md): every view, the model's context included, is the one value printer or a render declared on the value's schema; nothing clips.
- [prompt](prompt.md): the system prompt, what every agent reads first (the standard format's short prompt).
- [agent](agent.md): the help page, which `my.agent/help` answers section by section.
- [turn](turn.md): the turn loop an agent works its task in: observe, act, end, and who runs which tests.
- [model](model.md): the provider a profile names (none by default), the one tool, thinking off by default, usage as recorded.
- [cost](cost.md): what a cost proof measures (the calling thread's CPU time and allocation) and why.

## How work moves

The pages on how work is tasks, how it is checked and how it merges.

- [tasks](tasks.md): all work is tasks linked by `:needs`; a request becomes one task one agent designs, builds and merges (design-build then merge, or design → implement → merge, for experiments), in one transaction, before any agent forks.
- [checks](checks.md): the end-to-end proofs `bin/check` runs, and the task rows `seon.e2e` works.
- [merging](merging.md): how a branch merges into main: what it runs, how batches are proven, how conflicts are resolved.
- [machine](machine.md): the machine Seon is built on and for, and the rules that keep builds, disk and processes bounded.
- [plan](plan.md) (and [restart](restart.md): picking the work up on a fresh account): what is in flight, what the owner has not yet ruled, and the steps to the goal.
- [decisions](decisions.md): the owner's standing rulings, each with its words, date and where it is now stated.
- Who does what, delegation and briefs: [AGENTS.md §2](../AGENTS.md).
- The repository's front page, for a person arriving: [README.md](../README.md#start-here) (what Seon is, how to build it, where things live).

## Words and provenance

The pages on the words this repo uses and the libraries it customizes.

- [vocabulary](vocabulary.md): the words this repo uses, the library's first, and the namespace that owns each.
- [forks](forks.md): our customized libraries (Jolt, Chez, Datahike, persistent-sorted-set, konserve, crypto, Malli), their pins and branch scheme.
