# The wiki: a map

Seon on Jolt's docs, one concept per page, addressed `topic#section`; find the page where your problem lives, read its leading sentences, and follow its links. How the wiki is kept: [AGENTS.md §6](../AGENTS.md).

## What next replaces

- [parity](parity.md): each capability of v1 Seon (`~/src/seon`), and where it stands here: present, partial, missing, or left behind on purpose ([BAGGAGE](../BAGGAGE.md)).

## The running system

- [flow](flow.md): what core.async.flow is on Jolt, the proc contract every component follows, and its hazards. Read [core-async](core-async.md) first: channels, buffers, `go` and fibers as Jolt implements them.
- [config](config.md): every limit, bound and default is one documented key in one aero file, read through one resolver.
- [storage](storage.md): one store of Datahike index trees on LMDB; what a commit writes, history, node compression, large values out of line, and collection.
- [DRAGONS](DRAGONS.md): every place the outside world can fail, its one catching edge and its declared error; nothing else catches.

## The program and its loop

- [source](source.md): agents change Seon's own functions at the REPL on their branches; a merge lands them on main, writes the files back and runs them next.
- [references](references.md): what a text cites, read by one parser (`seon.ref`), becomes its references, pulled into a context at the depth its delimiter asks.
- [render](render.md): every view, the model's context included, is the one value printer or a render declared on the value's schema; nothing clips.

## Words and provenance

- [vocabulary](vocabulary.md): the words this repo uses, the library's first, and the namespace that owns each.
- [forks](forks.md): our customized libraries (Jolt, Chez, Datahike, persistent-sorted-set, konserve, crypto, Malli), their pins and branch scheme.
