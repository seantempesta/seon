# Dependencies: what Seon uses each library for, our patches, and the seams

The deps area owns this page and the libraries under `reference-code/` (each a pin, a submodule at a commit of our fork `seantempesta/<lib>`, patched on a `jolt/<topic>` branch). An agent that needs something of a library reads this page first and asks deps to design the seam; it does not read the library's source to work around it. A cause in a library is fixed in the library, on its `jolt/` branch, proved, and pinned ([merging](merging.md#conflicts-and-pins)); never worked around in Seon.

## A pin moves only after a boot on the real store

A pin is a build input: a move rebuilds the binary and restarts the system. Before its READY, the new pins boot on a copy-on-write clone of the standing store (`cp -c runs/store`) and write and read there, beside the library's own tests; a fresh agent alone does not prove it. Root writes the move into the store with `seon.system/landed-tx`'s pin argument, since a catch-up leaves submodules out.

## The libraries

| Library | Pin | Seon uses it for | Seam in Seon |
|---|---|---|---|
| Jolt | 054395e7 | the Clojure compiler and runtime on Chez Scheme; fibers, Flow, the fork | the binary (`bin/build`), `jolt.host`, `jolt.scheme` |
| Chez Scheme | 9e2a4868 | Jolt's backend: the heap, collections, the static image | through Jolt only |
| Datahike | ea03e180 | the store: immutable db values, branches, history, the write path | `seon.db` (the one write path), `seon.writer` |
| persistent-sorted-set | eb8ed4df | Datahike's index trees, nodes stored by address | through Datahike only |
| konserve | 2bceb36a | Datahike's key-value layer | through Datahike only |
| LMDB | b4d2b489 | the store file `runs/store/data.mdb`, record locks | through konserve and `seon.db` |
| zstd | f8745da6 | node compression | through the store |
| malli | 4dc9e305 | every contract and schema; inputs generated from schemas | `seon.contract` |
| core.async and Flow (in Jolt's stdlib) | with Jolt | every process: procs, channels, the step-fn contract | Flow code is the flow area's alone ([flow](flow.md)) |
| llama.cpp | 620d2225 | embeddings, run by the embed worker | `seon.vector` (main only, [decisions](decisions.md#2026-11-16-rulings)) |
| git (the program, not a pin) | the host's | where everything kept lives: the write-back of main's files, and until sessions work through their agents, a boot's catch-up and a session's import reading the checkout | reached only through the `:fx.git` effect owner (```seon.fx/git-commit```, the write-back); the catch-up's and the import's reads route through it too (task ``seon.fx/git-is-reached-only-through-its-effect-owner``) |
| jolt-fressian | 8f3eb5d2 | the store's value encoding | through Datahike |

## What is measured and true now

- **A process's image.** A minimal Jolt program owns 52 MB private; Jolt alone is a 72 MB image; requiring `datahike.api` takes a process to 229 MB private, 183 MB of it static image, 3 MB of source compiling to 30 MB of Scheme. Tracing and inspector information stay on during development at about 22% of that (owner's ruling). Seon's contracts armed at load add 101 MB once per exec of the binary; a forked child inherits them shared and starts at 9.1 MB private.
- **Freed pages.** A forked child's collections write pages it inherited from an uncollected fork server, so they become its own; lowering Chez's reserve ratio returns nothing. The cause and its fix are open (the system process collected before it forks).
- **The node cache.** A cached leaf grew after it was booked when a value was decoded on read (1.39x its bound). A fix on `jolt/` branches (Datahike 26f4b75b, persistent-sorted-set cca1f22f) failed to boot on the standing store on 2026-11-16 and was reverted; its cause is open.
- **Stored children.** persistent-sorted-set kept every written child pinned under `:weak`; fixed at eb8ed4df (-110 MB in the system process).
- **One writer per branch.** Separate Datahike writers on one LMDB: 16 per-branch writers reach 12.4x one writer's throughput, the lock held ~0.1 ms of a 5.7 ms commit.

Each row of [cost](cost.md#the-test-systems-lower-bound) that a library sets cites its line here.
