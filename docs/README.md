# The wiki: a map

Seon on Jolt's docs, one concept per page, addressed `topic#section`; find the page where your problem lives, read its leading sentences, and follow its links.

## How the wiki is kept

The wiki stays healthy by five rules, each a page's or section's to keep (owner, 2026-10-21: "a healthy wiki with good separation of topics and well linked so agents can get refs and know if they want to read more"):
- **Patterns live in the topic sections, linked, never in a catch-all file** (owner, 2026-10-21: "patterns embedded into the wiki in different sections and linked so that we pull together and understand disparate problems that can be unified in a fix"). A pattern's home is the section where its problem lives: the idiom, its "Not this", its one implementation cited at code depth. Every section with the same problem links to that home with an ordinary link; its backlinks are its instances, so pulling one shows the others, and one fix can unify them.
- **One home per concept.** A var or mechanism is explained in one section, the one that cites it at code depth (` ```name``` `); others cite it at summary or doc depth and link there instead of re-explaining it.
- **Every section leads with one sentence that stands alone.** At summary depth an agent sees a section's heading and that sentence (as in v1), and decides by it whether to read on; then the body; then links to related sections.
- **Everything is linked.** Every section is reachable from the map, every page links its neighbours, and nothing is orphaned or duplicated.
- **Sections state the current truth by topic.** A change rewrites the section it concerns; nothing is a dated log.

References and their depths: [references](references.md).

## What next replaces

- [parity](parity.md): each capability of v1 Seon (`~/src/seon`), and where it stands here: present, partial, missing, or left behind on purpose ([BAGGAGE](../BAGGAGE.md)).

## The running system

- [flow](flow.md): what core.async.flow is on Jolt, the proc contract every component follows, and its hazards. Read [core-async](core-async.md) first: channels, buffers, `go` and fibers as Jolt implements them.
- [config](config.md): every limit, bound and default is one documented key in one aero file, read through one resolver.
- [storage](storage.md): one store of Datahike index trees on LMDB; what a commit writes, history, node compression, large values out of line, and collection.
- [failure points](failure-points.md): every place the outside world can fail, its one catching edge and its declared error; nothing else catches.
- [clocks](clocks.md): where time decides (only the owner's exceptions) and where it is only measured; `bin/laws clocks` and `measures` name every site.
- [resolution](resolution.md): where a name is resolved or a Var defined at run time, each with its reason; `bin/laws resolution` names every site.

## The program and its loop

- [source](source.md): agents change Seon's own functions at the REPL on their branches; a merge merges them on main, writes the files back and runs them next.
- [references](references.md): what a text cites, read by one parser (`seon.ref`), becomes its references, pulled into a context at the depth its delimiter asks.
- [render](render.md): every view, the model's context included, is the one value printer or a render declared on the value's schema; nothing clips.
- [agent](agent.md): the help page, what every agent reads first and `my.agent/help` answers.
- [cost](cost.md): what a cost proof measures (the calling thread's CPU time and allocation) and why.

## How work moves

- [tasks](tasks.md): all work is tasks linked by `:needs`; a request becomes design → implement → merge on one branch, in one transaction, before any agent forks.
- [merging](merging.md): how a branch merges into main: what it runs, how batches are proven, how conflicts are resolved.
- [machine](machine.md): the machine Seon is built on and for, and the rules that keep builds, disk and processes bounded.
- Who does what, delegation and briefs: [AGENTS.md §2](../AGENTS.md).

## Words and provenance

- [vocabulary](vocabulary.md): the words this repo uses, the library's first, and the namespace that owns each.
- [forks](forks.md): our customized libraries (Jolt, Chez, Datahike, persistent-sorted-set, konserve, crypto, Malli), their pins and branch scheme.
