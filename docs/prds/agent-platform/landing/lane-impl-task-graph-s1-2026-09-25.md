---
type: landing
lane: impl-task-graph-s1-opus
status: "IN PROGRESS: paused for the default store nuke (root, 2026-09-25 22:05Z). No source edited yet; the REPL-proven graph walk is held in the lane's scratch file and lands in walk.clj after the fresh default is up."
design: docs/research/agent-platform/task-context-install-design-2026-09-25.md revision 4 (7bdf4fc0c) §S1
---

# Task-context S1: the graph walk and the agent debug page

## Broken things first

- Nothing is installed yet. `src/seon/render/walk.clj`, `seon.render.walk.edn` and `web.clj` are unchanged.
  The walk below runs only as a scratch namespace (`scratch.task-graph-final`, loaded into default pid 79957
  from an ignored `tmp/` file). It is not committed, contracted or armed.
- The level step uses Datalog through `seon.db/q`, not index seeks. The research's 20–40× faster seeks need a
  batch `seon.db` entrance, and `db.clj` is outside this grant. The measured cost is below.
- A task root at N=3 reaches 1,593–2,177 nodes. Through `render-call`, at the research's ~5 ms per node and face,
  such a debug page would take 15–20 s. The agent debug page walks only from the agent. Root's agent has no task
  link, so its N=3 graph is 134 nodes.

## REPL evidence (default pid 79957, max-tx 536886778, JVM mode, read-only, one sample each)

**The presence reconciliation (§2.4 against the research §1b).** Every identity attribute that has an entity row
declares `:seon.program/row-schema`, including `:seon.turn/id` → `:seon.turn/turn` and `:seon.message/id` →
`:seon.message/message`. Presence through the identity's row schema therefore finds turns and messages: 42 turns and
17 messages are nodes at N=1. Revision 4's "0 rendered holders" did not reproduce. Identity-less owned children take
the reaching attribute's `:seon.db/component-schema`. Of 55 identity attributes, 9 lack a row schema, and none of
those 9 are entity identities with a pair. The structure found 260 ref attributes, 137 components, and 23 owning
schemas with an AI pair.

**Structure memo:** 152 ms on first derivation (it runs `schema.datahike/malli->datahike-schema-in` over every
attribute), then 0.07 ms per hit via `schema/projection-cache-value`.

**`graph` from `[:seon.agent/id "root"]`:**

| N | nodes | levels (nodes per level) | edges | ms | MB |
|---|---:|---|---:|---:|---:|
| 0 | 1 | 1 | 0 | 5–54 | 4–105 |
| 1 | 65 | 1 · 64 | 84 | 17–28 | 26–48 |
| 2 | 134 | 1 · 64 · 69 | 468 | 43–70 | 103–106 |
| 3 | 134 | 1 · 64 · 69 (level 3 adds no node with a pair) | 537 | 79–139 | 225–227 |

- Node kinds at N=2: 42 turns, 33 context captures, 30 attempts, 17 messages, 5 refer bindings, 2 notes, and one each
  of agent, runtime, plan, namespace and function.
- Skipped at N=3: 108 `:seon.cluster.eval/receipt`, 78 unowned (transaction entities), 4 error occurrences, 3
  schedule tasks, 2 schedule fires, 2 maintenance requests, 2 shape rows, 1 argument row, 1 config agent.

**Task roots:**

| task | N=2 nodes / edges / ms / MB | N=3 nodes / edges / ms / MB | back-references at N=3 |
|---|---|---|---:|
| 132fee0467c8 | 171 / 188 / 52 / 88 | 2,177 / 4,536 / 158 / 416 | 0 |
| 300aca1ad57c | 40 / 60 / 50 / 71 | 435 / 648 / 74 / 150 | 0 |
| cea47b015b23 | 322 / 334 / 58 / 114 | 1,593 / 3,760 / 148 / 384 | 4 (call cycles) |

**Cost split, root at N=3:**

- per-level queries: forward refs 1.3 / 11.8 / 15.8 ms, reverse refs 1.2 / 11.7 / 10.9 ms, value edges 0.7–3.0 ms
  each, for levels 0 / 1 / 2;
- `pull-many '[*]` of the 134 nodes: 31.5 ms, 57 MB.

**Plans** (`datahike.query/explain`, level-1 frontier). Every family reports `SCAN eavt` with the pattern's
`?e`/`?a` bound. The row estimate is the old-format heuristic (1,002,676), and the log confirms
`estimate-heuristic-fallback`. Measured times of 1–16 ms per level show bound lookups, not store scans. The
reverse family reports `SCAN eavt [?t ?a ?e]`, so its index choice is unverified.

**Queries per level:**

- four families (forward refs, reverse refs, value forward, value reverse), plus one identity query for presence;
- plus two families and one identity query when the level has pair-less owned children (arities), which are joined
  through inside their owner's level;
- one `pull-many` at the end.

## Remaining (resume after the fresh default)

1. Paste the scratch walk into `walk.clj`, and add the structure, edges, step, graph, graph-request and cut schemas
   to `seon.render.walk.edn`.
2. Build the `?n=0..3` agent debug page with both faces per node in graph order, plus the skipped and cut panels.
3. Add the tests.
4. Re-measure on the fresh store.

The owner's correction (root, 22:03Z): renderers MAY read the stable database value they are handed. The walk still
supplies each node's pulled value and the order.

RESET NEEDED: no.
