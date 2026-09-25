---
type: landing
lane: impl-task-graph-s1-opus
status: "S1 landed: seon.render.walk/graph, its schemas, the /agent/{id}/debug?n=0..3 page, two tests (root runs them). Over the line budget: 206 source lines added against 100."
design: docs/research/agent-platform/task-context-install-design-2026-09-25.md revision 4 (7bdf4fc0c) §S1
---

# Task-context S1: the graph walk and the agent debug page

Open **http://localhost:7994/agent/root/debug?n=3** (or `n=0`, `1`, `2`). The page lists each node in graph order,
with its AI face beside its HTML face, then the skipped targets and any cut.

## Broken things first

1. **Over budget.** The change adds 206 source lines against the 100-line limit: `walk.clj` +171 and `web.clj` +35
   (the 35-line page cap was met). Declarations add +27 lines and tests +121. Nothing is deleted, because S1 as
   designed only adds. The walk is 7 contracted functions plus the interim table. The smaller alternative is to
   drop the component join-through (about 20 lines) and the per-attribute cap (about 8 lines). Without the
   join-through, functions lose their contract-schema edges. Without the cap, 42 turns flow uncapped.
2. **The max-nodes node cut is effectively unreachable.** Query work is bounded by the same cap, as the `ns.clj:40-50`
   precedent maps it (`:max-work`, `:max-results` and `:max-result-weight` = `max-nodes`). Level queries always
   touch more datoms than they admit nodes, so the query refusal fires first. That refusal is reported as a cut named
   `:seon.db/invalid-read`, carrying the refusal whole. Measured on task 300aca at N=3: `max-nodes` 10 refuses at
   level 0, and `max-nodes` 30–400 refuses at level 1. There is no separate query-work cap in the caps. Adding one is
   a config decision for the owner.
3. **The agent HTML face fails on a pulled agent value.** It returns "The selected renderer did not return an
   observation" after 417 ms. The page shows that failure through the error's own HTML face. This is an existing
   renderer defect (design §0.3; the S3 work), not something this slice changed.
4. **The page is slow on large graphs.** Root's page takes 0.49–0.70 s on the fresh store (12–15 nodes, 2 faces
   each). A task-rooted walk at N=3 reaches 435–2,182 nodes, which through `render-call` would take many seconds.
   The page walks from the agent only, and root has no task link yet.
5. **Reloads removed wrappers.** I ran `require :reload` on `seon.render.walk` and `seon.render.web` in default
   (pid 39484) to prove the page. The reload removed their instrumentation wrappers, so **root must re-arm or adopt**
   these namespaces. HTTP serves the new page, because the handler dispatches through Vars.
6. **Index seeks are not used.** The level step is Datalog through `seon.db/q`. The research's seek path needs a
   batch entrance in `seon.db`, which is outside this grant.
7. **Plans.** `datahike.query/explain` reports `SCAN eavt` with `?e`/`?a` bound, and the old-format heuristic
   estimate. Measured per-level times of 1–16 ms show bound lookups. The index choice for the reverse family is
   unverified.

## What landed

- **`seon.render.walk/graph`** takes `{:seon.db/db :seon.render.walk/lookup :seon.sci.admit/caps
  :seon.render/distance}` and returns `:seon.render.walk/graph`: nodes with level, owning schema and pulled value;
  every edge between nodes; the definitions-first order; back-references; skipped counts by owning schema; and cuts.
  A missing lookup returns `:seon.render.walk/no-such-entity-error`.
  - **Edges:** every ref attribute is followed forward and reverse (reverse spelled `:ns/_attr`). The interim
    value table (`interim-value-edges`, rows retired by the refs lane) is followed both ways. That table adds
    `:seon.test/subject` to the design's list.
  - **Presence:** a target is a node when its owning schema declares `:seon.render/ai`. The owning schema is the
    identity attribute's `:seon.program/row-schema`, else the reaching attribute's `:seon.db/component-schema`. A
    pair-less owned child (an arity) is joined through inside its owner's level.
  - **Order:** a DFS postorder over definition edges only. Siblings sort by `:seon.turn/opened-tx`, then entity id.
    Self-calls are ignored. An edge onto the current path is recorded as a back-reference, so a cycle renders once.
  - **Cost:** the structure is derived once per projection through `schema/projection-cache-value`.
- **Web.** `/agent/{id}/debug?n=` renders `graph-debug-html`. Every node face goes through `render/render-call`, the
  one render path, with the floor as the only clipper. A failed render shows through its own HTML face.
- **Schemas.** 23 declarations in `seon.render.walk.edn`: structure, edge(s), level result, node(s), order,
  back-references, skipped, cut(s), graph and graph request. All 7 function contracts compile against the live
  projection, and every measured graph validates against `:seon.render.walk/graph`.

## Evidence on the fresh store (pid 39484, max-tx 536870961, one sample each)

| root | N | nodes (per level) | edges | ms | MB |
|---|---:|---|---:|---:|---:|
| agent root | 0 | 1 | 0 | 4 | 2 |
| agent root | 1 | 7 (1 · 6) | 10 | 16 | 27 |
| agent root | 2 | 12 (1 · 6 · 5) | 40 | 13 | 31 |
| agent root | 3 | 12 | 45 | 8–15 | 32–39 |
| task 132fee0467c8 | 2 / 3 | 174 / 2,182 | 191 / 4,543 | 28 / 151 | 61 / 392 |
| task 300aca1ad57c | 2 / 3 | 40 / 435 | 60 / 648 | 26 / 65 | 44 / 133 |
| task cea47b015b23 | 2 / 3 | 323 / 1,593 | 335 / 3,763 | 39 / 127 | 87 / 354 |

- **Order check.** For root, 132fee and cea47 at N=3, no definition edge points forward except recorded
  back-references (0 violations). Turns fall in `opened-tx` order: 2 turns on root.
- **Page.** `debug-response` at n=0/1/2/3 answers 200 in 491 / 555 / 608 / 637 ms, with 1 / 10 / 15 / 15 node rows
  (the store grew between runs). Over HTTP, `?n=3` takes 703 ms, and the browser paints both columns and the N
  selector.
- **Pre-nuke store (1.0 M datoms).** Root at N=3 reached 134 nodes in 79–139 ms (225 MB); the per-level queries
  took about 67 ms and `pull-many` 31 ms.
- **Presence reconciliation.** Revision 4 §2.4 reported 0 rendered holders through the row-schema lookup; that is
  stale. `:seon.turn/id` and `:seon.message/id` declare row schemas, so turns and messages are present.

## Root runs (lanes run no tests)

```
bin/test-check default --ns seon.render.walk-graph-test
bin/test-check default --test seon.render.web-debug-test/the-graph-debug-page-shows-both-faces-in-graph-order-at-every-n
bin/test-check default --ns seon.render.web-debug-test
```

These need the reloaded namespaces adopted and armed first (item 5). The web case renders four pages, about 0.5 s
each, against the five-second bound. If it runs over, the cost is `render-call` per face, not the walk.

## Changed paths and lines

- `src/seon/render/walk.clj`: +171.
- `src/seon/render/web.clj`: +35.
- `resources/seon/schemas/seon.render.walk.edn`: +27.
- `test/seon/render/walk_graph_test.clj`: +98 (new).
- `test/seon/render/web_debug_test.clj`: +23.

Net source is +206 with no deletions.

**Out of scope, for root to file:**

- the agent HTML face failure (item 3);
- the query-work cap tied to `max-nodes` (item 2).

RESET NEEDED: no. **Re-arm needed** for `seon.render.walk` and `seon.render.web`.
