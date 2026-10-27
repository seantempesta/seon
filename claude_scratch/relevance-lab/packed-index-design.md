# The relevance index: relevance in a few ms, flat in the store (design, 2026-10-27)

Root's target (2026-10-27): `seon.context/relevance` under about 10 ms and a few MB a call, flat in corpus size, with a cost proof that says so. Today, on Seon's 5,095 texts at load ~20: 115 ms, 54M ticks, 132 MB a call (three kinds); `related` with its view ~260 ms.

## Where a call's cost goes today

| part | ms | MB | why it costs |
|---|---|---|---|
| `near`, once per kind (3) | 10-18 each | 11-15 each | every coarse datom read and copied per call (~5 KB a vector); linear in the store |
| tokens of the pool's texts | 26 | 16 | each pool item's source tokenized per call |
| the push (PageRank) | 18 | 36 | each visited node's degree counted, each pushing node's neighbours read: datom seeks at ~1.4 KB a datom |
| rarity degrees | (reused from the push) | | |
| `section-names` | 2 | 3 | every `:seon.section/names` datom per call |
| `scores` (pool vectors) | 1.5 | 1.5 | one f16 seek a pool item |

Every part is a re-read of facts that did not change since the last call. The work a call needs is: one scan of n vectors in C, a push over a few dozen in-memory adjacency lists, and BM25 over ~300 precomputed token rows.

## The design

**One value, the relevance index of a db**, `seon.vector/index`, built once from a db and advanced to a later db by the datoms that differ (`d/datoms-diff`, as `seon.vector/written` already reads them):

- `:rows` the entities with a vector (forms, sections, schema entries), each a row number; `:kind` a byte a row;
- `:v` the rows' first 256 dims, renormalized, as one contiguous f16 buffer (256 dims ranked within 0.03 of 768 on the held-out plans; 5k rows: 2.6 MB, 40k: 20 MB);
- `:tokens` each row's identifier tokens as sorted vocabulary ids with counts (an int array a row) and the vocabulary (`:vocab` string to id);
- `:adj` each row's neighbours in the reference graph as row numbers, capped at `:hub` (a hub's row is marked, not listed), and its degree to `:rarity-cap`;
- per kind, the mean and spread of nothing: the z-scores stay over the pool (measured as good as the kind's moments for functions and sections).

**Built** once per process the first time a caller asks with a db (a full read: every vector, every text tokenized once, every reference resolved once: an estimated 1-2 s at 5k texts, linear). **Advanced** when asked with a later db of the same lineage: the datoms-diff from the index's db to the asked db, restricted to the attributes the index reads (`:seon.vector/f16`, `:form/src`, `:form/refs`, `:form/quoted`, `:form/keywords`, `:var/form`, `:seon.section/*` it reads, `:schema/key`); each changed entity's row rebuilt, and the rows of the vars it now names or no longer names updated (a reference is an edge both ways). Cost: the diff, not the store. A db not descended from the index's (another branch) builds its own or advances from the nearest held.

**Held** where the process's other per-db caches are: an agent's `seon.agent/state` (beside `:branch-changes`, keyed the same way: the db it is current for), root's for root's own callers. No new atom, no proc, no clock: a call with the held db reuses it, a call with another db advances it. Content-keyed reuse of an expensive unit (§5): its key is the db value.

**Scored without datom reads.** `relevance` takes the index and the db: the pool per kind is the C scan of that kind's rows of `:v` (`seon_vec_fine` over a contiguous kind slice, 256 dims; the top `relevance-pool` by a heap, as `seon_vec_coarse` keeps its shortlist), the seeds, and the push over `:adj`; BM25 from `:tokens`; z over the pool. Only the answer's names are read from the db.

**What it replaces:** `near`'s per-call datom scan for relevance's pools; `section-names`, `degree` and `neighbours` as per-call reads (they become the index's builders); `tokens` per call. `near` itself stays for its other callers (the Datalog function, `similar`).

## Expected cost and its proof

Per call, at 5k texts: the C scan 3 × ~1.7k rows × 256 MACs (~0.3 ms), the push over in-memory lists (~37 pushes), BM25 over ~300 token rows, z and logits: an estimated 2-5 ms and under 2 MB, the scan's time linear but its bytes and calls flat. Cost proof: `relevance` over an index of n made rows (n 1k / 4k / 16k), bytes and contracted calls flat in n; a second proof that advancing the index by one changed form costs that form, not n.

## Open questions for the review

1. Is the agent's `state` the right holder, or should the index ride on ctx (which already holds the agent's per-db views)?
2. Memory: each agent process holding its own index (~5-10 MB at 5k texts in Jolt values) against `:seon.config.machine/memory-mb`. Alternative: root builds it and the fork server inherits it copy-on-write at fork.
3. The 768-dim vectors stay in the store for `near` and for re-ranking; is ranking at 256 for relevance enough, or should the pool be re-scored at 768 (100 seeks a kind)?
4. One-search (`my.agent/search`) calls `relevance` per query: the index makes that a few ms; does anything else need the index (the Datalog `near`)?

## Review (independent, Opus, 2026-10-27): approve with changes. On hold: the owner put accuracy first

1. Hold main's index in the fork server beside its reach graph and reconciler index (`seon.fork-server` builds them at start, brings them to main at each fork, `lib/static!` before forking so agents share them copy-on-write); the agent advances its copy as it does `:base-graph` (`seon.agent/branch-graph`, `seon.turn/index!`). Root's dashboard call (`seon.render.ui/related` in root) needs a named holder: no bare atom.
2. Persistent advancing: an untouched main base plus a branch overlay (re-based at merge-main!), never a buffer mutated under another db value.
3. No new 256-dim buffer or scan: lay the stored coarse rows (128 int8 + scale, 132 B a row) out per kind and scan them with `seon_vec_coarse` unchanged, then rescore the pool at 768 with `scores` (the fitted calibration stays valid).
4. Form and schema adjacency from the reach graph's `:adj`/`:rev` (seon.program, reach-update), only section edges added; re-measure the 57 plans if the edge set moves the features.
5. Edges keyed by var name (a var moving forms is one update); a node crossing `:hub` is recounted.
6. No per-call copy of the buffer into an arena, or bytes stay linear; a foreign buffer needs a named owner and free.
7. A proof that after N random changes (redefinition, retraction, re-parse, late vector, merge of main) the advanced index equals a fresh build.
8. It deletes `section-names`, `degree` and `neighbours` as per-call reads, and per-call tokens (a form is content-addressed: its tokens never change).
