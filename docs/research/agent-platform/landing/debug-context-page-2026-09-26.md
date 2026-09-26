---
type: landing
lane: debug-context-page-opus
status: "Committed 5ca17642f; proven at the REPL on default's real data through a scratch copy and a static preview; not live on :7994 until root restarts default (RESET NEEDED)"
created: 2026-09-26
---

# The owner's context page: `/agent/{id}/debug?n=N[&root=LOOKUP]`

**What already existed.** `seon.render.walk/graph` walks the agent's linked graph. `seon.cluster.prompt/prompt`
composes the prompt a turn sends. `render/render-call` renders each face and keeps retained calls in the ctx's
`render/shared-cache`. `seon-graph.js` draws a Cytoscape model.

**What this change does.** The page is now a display of those existing values. It adds no walk, renderer, store or
cache: the retained calls sit under one new key in the existing shared cache.

## Broken things first

1. **Default died of OOM while I loaded the old page at n=2 (10:10:34 local).** The earlier n=0 load took 3.6 s. The
   n=2 load ran 14.8 s and then returned an empty reply.
   - The JVM log ends in `java.lang.OutOfMemoryError: Java heap space`, and there is a heap dump at
     `data/heap/java_pid19859.hprof` (17.7 GB). The flow was already stopped ("flow not running", 10:10:11) before
     the request.
   - **The cause is not proven.** On the restarted JVM (pid 27723, `-Xmx` ≈ 11 GB), the same 83 nodes × 2 faces
     allocate 5–7 GB cold, with nothing retained. Dogfood lanes were also active at the time.
   - Root should look at the heap dump before deleting it.
   - Root restarted default. I did not start it.
2. **Default's flow is not running on the new JVM either** (`bin/seon status` exits 1 with "flow not running"). The
   web port still answers. This is root's to diagnose.
3. **Passing `:seon.render/captured-invocations` into `render-call` makes every source-producing AI face return
   nil.** `render.clj:1573` returns the invocation entry's `:seon.render.call/output`, which is nil for source
   output. The caller is expected to evaluate the forms, as the page proc does at `web.clj:1392`.
   - My first cut passed invocations, and the agent and plan AI faces showed `nil`.
   - **The fix:** the page retains calls only.
   - This is a sharp edge in `render-call`'s contract: the same arguments change the meaning of the output. It
     should be filed.
4. **The AI face on this page is the renderer's source forms, not their evaluated results.** For example, the agent
   node shows `(help)` and `(seon.db/pull …)`, while the "context as sent" shows those forms with their `#:seon.repl{:value …}`
   results.
   - The law says each AI-render form is evaluated read-only in the viewing agent's SCI world.
   - `render-call` alone does not do that evaluation, and the page does not do it either. It displays what exists.
   - **Owner decision:** should the page evaluate forms through the one REPL path? That is `web.clj`'s existing
     source-evaluation step, reused, and it makes cold loads slower.
5. **`seon.db/pull-many` costs 30× Datahike's own call.** For 83 nodes it takes 195 ms / 986 MB, against 6 ms /
   22 MB for `datahike.api/pull-many` on the same ids. That is 90% of the walk's 215 ms / 1 GB. The fix belongs in
   the `seon.db` owner (decode and evidence), not in the walk.
6. **Cold renders are dominated by `seon.db/read-evidence` and `canonical-value-string`.** A task-rooted n=2 page
   (48 nodes) made 2.3 M `canonical-value-string` calls and spent 1.1 s in `read-evidence`, for 115 calls. Each
   render call also recomputes `stored-call-reach` (about 4 ms). These are `render.clj`/`db.clj` costs, the same
   class as render-graph research §0.3.

## What the page shows

The page is one wide two-column layout. On the left is a sticky graph panel with the summary table. On the right is
the node list.

- **Header.**
  - N=0..3 links, which keep the root.
  - The root form: an EDN lookup, for example `[:seon.task/id "300aca1ad57c"]` or `[:seon.ns/name my.agents.root]`.
  - An "agent root" link back to the default root.
  - Totals: nodes, estimated tokens, skipped, cuts and back-references.
- **The context as sent.** A lazy `<details>` fetches `?sent=true` through Datastar and shows
  `seon.cluster.prompt/prompt`'s `:seon.cluster.prompt/text`. That is the provider's single user message, exactly
  as composed, with its chars, tokens and segment count.
  - Root today: **24,039 chars, ≈7,512 tokens, 35 segments.**
  - The label states plainly that the walk below is not yet on the prompt path; slice S3 of the task-context design
    puts it there.
- **Graph view.**
  - `seon-graph.js` is reused with an additive model: nodes carry `level`, and edges carry `reverse`.
  - The layout is one ring per level around the root, coloured by level (L0 signal, L1 info, L2 eval, L3 success).
  - Reverse edges (`:ns/_attr`) are dashed.
  - Hovering or selecting a node names it. Tapping a node opens its block and scrolls it into view.
  - The namespace debug page's model has no `level`, so it keeps its old behaviour.
- **Summary table.** Nodes and ≈tokens per (level, owning schema), then the skipped schemas with their counts.
- **Each node:**
  - position, schema, identity, eid and "walk from here" (for a scalar identity);
  - chars and ≈tokens;
  - "included by: root —:attr→ e… name —:attr→ …", the shortest walk path;
  - the AI face beside the HTML face.

## Evidence (default pid 27723, JVM REPL, scratch namespaces, since removed)

| probe | before | after |
|---|---|---|
| root, n=2 page (85 nodes, 170 faces) | 2.25 s / 6.8 GB on every load (no retention) | cold 2.25 s / 6.8 GB; **warm 0.56–0.58 s / 1.33 GB** |
| same faces only, 166 calls | 1.87 s / 5.2 GB | warm 0.34 s / 0.29 GB (retained calls) |
| task `300aca1ad57c`, n=2 (48 nodes) | — | cold 2.41 s / 7.4 GB; warm 0.66 s / 0.43 GB |
| context as sent (`prompt`) | — | cold 466 ms / 1.76 GB; warm 5 ms |
| walk alone, root n=2 | 215–225 ms / 1.0 GB | unchanged (item 5) |
| per-face cold cost, one per schema | turn 68 ms, error 85 ms, others 7–18 ms (AI); turn 52 ms, runtime 18 ms (HTML) | — |

**Why renders did not hit.** The page never passed `:seon.render/retained-calls` or `:seon.render/captured-calls`,
so every load rendered everything.

- It now keeps the captured calls in `render/shared-cache` under `[::graph-calls [agent lookup n]]`.
- The next load takes `render-call`'s fast path when the argument, producer and program evidence are unchanged, and
  the read evidence is current.

**Contracts.** I validated every new function's real inputs and outputs with `m/validate` against the carried
projection:

- the graph, edges, node and inclusion paths (83 of 83 nodes have a path);
- the model, label and page hiccup;
- the malformed root, which is refused by name;
- the missing root (`no entity in this database`);
- the sent hiccup.

`seon.contracts-compile-test/check` over `web.clj` found 0 findings. clj-kondo reports 0 errors.

**Layout.** At 1600×1000 with every node open, `document.scrollWidth` is 1585 (no page scroll), and no `.seon-graph-face` is wider
than its box (agent n=2 and task n=2).

**Screenshots** are in `tmp/debug-context-page-opus/`, captured by headless Chrome at 1600×1000 from a static copy
of the scratch render with the new CSS and JS:

- `after-agent-n2-w1600.png`
- `after-agent-n1-w1600.png`
- `after-task-n2-w1600.png`
- `after-ns-n1-w1600.png`
- `after-sent-open-w1600.png`

I also checked the interaction in the in-app browser: tapping a node opened `#node-34165` and named it in the status
line.

## Owner-facing observations (for tuning)

- **Root at n=2 is about 17.3k tokens over 85 nodes.**
  - 34 `seon.cluster.eval/receipt` nodes: ≈7.5k tokens.
  - 19 turns: ≈8.2k tokens. The turn AI face repeats the receipts it contains, so the receipts are counted twice.
  - Reverse refs at level 2 reach 4 other agents: the dogfood/mcp agents that messaged root.
- **Task `300aca1ad57c` at n=2 is 48 nodes, ≈5.4k tokens:** the task, then 4 function must-reads, then 14
  functions, 16 tests, 6 issues, 6 schemas and 1 namespace.
  - Task `cea47b015b23` reaches 350 nodes at n=2. I did not render that page on default because of item 1.

## Lines (5ca17642f)

| file | added | removed | net |
|---|---:|---:|---:|
| `src/seon/render/web.clj` | 144 | 52 | +92 |
| `test/seon/render/web_debug_test.clj` | 10 | 2 | +8 |
| `resources/public/js/seon-graph.js` | 27 | 4 | +23 |
| `resources/public/css/input.css` | 30 | 7 | +23 |

`output.css` is generated.

**Deleted:** the page's separate Skipped and Cuts sections (folded into the summary table and panel), the old
level-heading kind list, and `graph-node-label`'s three-way naming (now `graph-node-name`, shared with the graph
model).

Clojure is +92 net: under the ~100 guideline, but it carries real new surface (the prompt panel, graph model,
inclusion paths and root selector). A smaller alternative is to drop the summary table (≈8 lines) or the "walk from
here" links (≈3 lines).

## Root runs (lanes run no tests)

- RESET NEEDED: restart default so it loads 5ca17642f, since default runs a committed archive.
- `bin/test-check default --test seon.render.web-debug-test/the-graph-debug-page-shows-both-faces-in-graph-order-at-every-n`
- `bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection`

**URLs after restart:**

- `http://127.0.0.1:7994/agent/root/debug?n=2`
- `http://127.0.0.1:7994/agent/root/debug?n=2&root=%5B%3Aseon.task%2Fid%20%22300aca1ad57c%22%5D`
- `http://127.0.0.1:7994/agent/root/debug?n=1&root=%5B%3Aseon.ns%2Fname%20my.agents.root%5D`

## Limits

- The live page on :7994 was not observed after the change. The proof is the scratch copy (unarmed; contracts
  validated explicitly) plus a static preview.
- Warm timings assume the same committed database value, or current read evidence. A write between loads re-checks
  read evidence per call.
- Default was under concurrent lane load, and each timing is 2–3 samples.
