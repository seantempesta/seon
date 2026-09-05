---
type: prd
status: draft
tags: [prd, agent, context, render, tooling, visualization, data-model]
---

# The Design Lab — a live, clickable model of the agent's data and its projections

*PRD, 2026-09-05. The owner asked for the best programmatically driven
visualization of the REAL data — actual entities, every attribute fully
namespaced, actual values — where clicking an entity shows the render
functions that could apply, the output of each on that data, and how the
data assembles into the three scenarios (the SCI environment, the
completion prompt, the user's page). Built in Clojure against the actual
database so schemas and designs can be tried before any production code.
Temporary by intent; reusable where it earns it.*

**Vocabulary.** "Entity schema" = the registered `:map` schema with
`:seon.db/attributes true` that a stored entity satisfies, found from its
identity attribute. (Earlier drafts said "family"; that word is retired.)

## 1. Goal

One page, served by the running system, that lets the owner:

1. pick an entity (an agent, a namespace, a message, anything with an
   identity attribute) in a live scratch cluster;
2. see its ACTUAL attributes and values (namespaced, unabridged, with
   elision values where large) and its connections in both directions —
   every ref attribute out, every ref attribute in, with counts — as an
   interactive graph;
3. click any node and see: the candidate render functions for that value
   (from the real program graph and schema registry), the output of each
   candidate on that real value for `/ai` and for `/html`, and where the
   floor would fall;
4. assemble the three scenarios from the same data and read them: the SCI
   bindings that would be installed, the completion prompt as bytes, the
   page as rendered HTML — with the ordering visible and editable;
5. try a design: fork a scratch BRANCH of the database (Datahike branches
   are head pointers, near-free), transact a proposed schema and rows into
   it, and re-run 2–4 on the branch — compare two branches side by side;
6. write down what was learned: a decision log the lab appends to, so the
   design document is fed by evidence, not recollection.

## 2. Non-goals

Not a production feature, not a replacement for the agent page, not a
second renderer. No production namespace changes to make it work: it
reads the database and calls existing functions. Nothing it does touches
a shared cluster; experiments live on branches it creates under its own
scratch cluster.

## 3. The tool choice

**Graph:** Cytoscape.js (MIT; JSON in; programmatic layouts, styling,
events; already proven in the atlas) rendered inside the existing Seon
web stack — a hiccup page served by the running JVM with Datastar for
signals/patches. The graph's data is a JSON document DERIVED FROM DATOMS
by Clojure on every request; the browser never invents anything.
Alternatives weighed: Portal (superb for a datafy/nav tree of one value,
no graph); Clerk (notebook, weak interactivity for graphs); Graphviz
(static). Cytoscape inside our own page wins because the same request
path can call our render functions and stream their outputs.

**Everything else is Clojure in one dev namespace** (`seon.dev.lab`,
routed at `/lab/...` only when the cluster runs in development), reusing:
`seon.db` (q/pull/history/as-of/branches), `seon.render/producer` and
`candidates` (today's real selection), `seon.print/fit` (today's real
floor), `seon.schema/projection-from-database`, `seon.cluster.registry/branch!`
+ `store/open-branch!` (experiment branches), the Datastar page shell and
SSE that `seon.render.web` already runs.

## 4. The page

```
┌ header: cluster · branch (main | experiment-N) · basis t · entity picker (identity attr + value) ┐
├────────────────────────────┬──────────────────────────────────────────────────────────────────┤
│ GRAPH (Cytoscape)          │ INSPECTOR for the clicked node                                    │
│ nodes = entities           │ • all attributes, namespaced, actual values (large → elision +    │
│   label = identity value   │   requery form), tx instant per attribute (from :db/txInstant)    │
│ edges = ref attributes     │ • entity schema(s) it satisfies — 0, 1, or several (shown honestly)│
│   solid out, dashed in,    │ • RENDER CANDIDATES: every fn whose contract accepts it, in the    │
│   labelled :attr ×N        │   selection order; the schema-declared face; the floor            │
│ depth slider 1–3           │   [run] each → the /ai text and the /html block, real bytes       │
│ filter by attribute ns     │ • PROCESSING CANDIDATES: fns whose contracts accept this entity   │
│                            │   schema (not render) — what could act on it                      │
├────────────────────────────┴──────────────────────────────────────────────────────────────────┤
│ SCENARIOS for the picked agent (tabs)                                                          │
│  SCI env: the bindings that would be installed (requires, defs newest-per-name, result handles │
│           with readability, contract wrappers) in dependency order                            │
│  Prompt:  the entries in the chosen ORDER (draggable), each: intent · form · rendered result   │
│           · handle; total tokens; the pre-requisite doc/dir entries inserted; diff vs today's  │
│           actual prompt for the same agent (the capture)                                       │
│  Page:    header · transcript blocks · panels — rendered HTML in an iframe from the same data  │
├───────────────────────────────────────────────────────────────────────────────────────────────┤
│ ENV: [reset from load definition] [fork branch] [compare with main]   DECISION LOG: [append note] │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 5. What the lab computes (all from datoms; each a named function with a contract)

1. **Neighbourhood** — for entity E at depth d: E's attributes and values;
   for every installed ref attribute A: out-edges `[E A ?v]` and in-edges
   `[?x A E]`, grouped with counts, expanding a bounded sample per edge;
   repeat to depth d. Uses the installed schema for the ref set (the same
   derivation as `walk/root-selector`), datoms for the counts.
2. **Entity schemas of a value** — the registered attribute-bearing maps
   whose required attributes are present; ZERO, ONE, or SEVERAL, shown as
   found (the reviewer measured several and none for real identities).
3. **Render candidates** — (a) today's real selection: `seon.render/producer`
   over the value in the agent's namespace (explicit → viewer publics by
   Malli fit → schema face → floor); (b) the proposed contract query (an
   arity whose input refs are covered by the value's entity schema(s) plus
   injectables, output `:seon.render/ai`/`/html`), ranked by rung, namespace
   distance, coverage, recency. Both shown, so the design's claim can be
   compared with today's behavior on the same value.
4. **Run a candidate** — call it on the real value under the real render
   profile and print floor; show `/ai` bytes and `/html` rendered;
   time it; show the elision values it produced.
5. **Processing candidates** — arities whose input refs include the
   entity schema and whose output is not a render projection.
6. **Scenario: SCI** — from the agent's namespace row and evals: the
   requires, the newest def per name (with the eval that defined it), the
   result handles and whether each value is readable, the contract
   wrappers; dependency order; nothing executed.
7. **Scenario: prompt** — the entries the design would generate
   (discovery per connection, wrapped in the chosen render function,
   intent comment derived from the attribute), the pre-requisite `doc`/`dir`
   entries from the demand DAG, the agent's own evals; the ORDER produced by
   the live ordering function (a contracted `defn`; edit it, hot reload,
   refresh); the assembled bytes and token estimate per entry; beside it,
   what the CURRENT system would send for the same agent (the debug
   page's prospective prompt) — so the two can be read together.
8. **Scenario: page** — the same entries through `/html`: header block,
   transcript blocks, panels (any `/html` candidate the owner clicks
   "add as panel"); rendered live.
9. **Environment and experiments** — the lab cluster is built from the
   LOAD DEFINITION (a real namespace: schemas, rows, domain functions);
   `reset` reforks it from current source and runs the load; `branch!`
   forks the head for a side experiment; the whole page re-computes
   against the selected branch; a two-column compare with main for the
   same entity (attributes, candidates, scenario bytes). The load
   definition is edited by the designing agent between resets.
10. **Decision log** — one EDN row per note {at, branch, entity, note},
    stored on the lab's own branch, rendered as a list, exportable to the
    design document's §9.

## 6. Acceptance, per wave (each read at the bytes on a scratch cluster)

- **W1 — the graph of real data.** `/lab/entity/[:seon.cluster.agent/id "root"]`
  shows root's actual attributes and every ref edge in both directions
  with counts matching direct Datalog; every attribute namespaced; a large
  value shows an elision with a working requery; depth 2 in under 500 ms
  at the ruled population.
- **W2 — candidates and outputs.** Clicking a message shows today's
  selection AND the proposed query's ranking; "run" prints the `/ai` and
  `/html` outputs of each candidate on that message; the floor's output
  is shown and labelled; a candidate that throws shows the flat error.
- **W3 — scenarios.** For the lab agent: the SCI bindings list; the
  prompt assembled by the live ordering function with pre-requisites
  inserted and tokens per entry, beside the current system's prospective
  prompt for the same agent; the page rendered. Editing the ordering
  function and refreshing changes the order; nothing else does.
- **W4 — environments.** Edit the load definition (a new attribute on the
  agent schema and three rows), `reset`, refresh: the graph, candidates,
  and scenarios show the new world in under 15 s end to end; fork a branch
  for a side experiment and compare it with main; the decision log accepts
  and lists a note.

## 7. What it must reveal (the questions it exists to answer)

Which entity schemas real values actually satisfy (0/1/many); whether the
proposed selection query picks what today's selection picks, and where it
differs; what the floor really prints for each real value; how many tokens
a generated prompt costs at the real population and where they go; whether
turn plumbing as agent attributes reads back correctly after a simulated
crash (a branch with the process retracted); how the transcript reads when
render functions change; which of the design's names are wrong when typed
against real attributes.

## 8. Waves, lanes, and what is reused

The debug page's owner (`seon.render.web`, its debug section, and
`seon.render.route`'s existing `/agent/{id}/debug`) grows the lab's views;
the pure derivations (neighbourhood, candidates, scenario assembly) are
ordinary functions in the render owners they belong to, tested against
the canonical database fixture; the seed world is a config manifest under
`config/` naming the steward agent and its namespace plus seed rows; the
Cytoscape script is inlined in the page as the atlas does; no changes to
production namespaces; tests under `test/seon/dev/lab_test.clj` for the
pure derivations (neighbourhood, candidates, scenario assembly) against
the canonical database fixture. Four lanes, one per wave, sequential (each
wave's page is the next wave's substrate). Cytoscape.js pinned from
cdnjs as the atlas does. The atlas's hand-typed `MODEL` is retired when W1
lands: the lab IS the visualization, over real data.

## 9. Ruled by the owner (2026-09-05) and open

**Ruled:**
1. A dev-only route in the EXISTING web server — one process, one
   database, the real render functions in the real ctx.
2. **Fresh environments from a LOAD DEFINITION, reset cheaply.** The lab
   cluster is disposable; what persists is the definition of what gets
   loaded into it — schemas, rows, the domain's namespaces and functions —
   kept as code in the tree so the designing agent edits it, resets the
   env (refork from current source + load), and the dev page just
   refreshes. The data model is designed interactively and the definition
   is what "sticks around". The more real the loaded world, the better.
3. **Everything real.** Functions the lab shows are program rows: the
   domain's render and processing functions live in real namespaces in the
   tree (indexed by `bin/seon init`, hot-reloaded on edit); an agent's own
   functions arrive through real settled turns. No door-mode shortcuts.
4. **Visualize the context that WILL be sent, not runs.** The prompt
   scenario shows the generated context as bytes with per-entry tokens and
   provenance; it borrows the existing debug machinery
   (`seon.render.web` `debug-prompt`/`prospective-prompt`, the render
   proc's retained entries, `:seon.context.capture` reads) rather than
   building a second assembler. Paid runs are out of scope for the lab.
5. **Ordering is algorithmic and live.** The prompt's order comes from a
   real ordering FUNCTION (a `defn` in the tree with a contract) that the
   designing agent edits; hot reload changes it and the dev page shows the
   new order on refresh. No manual drag, no saved manual orders.

6. **No new infrastructure, no new names.** The lab is a CLUSTER —
   started, reset, and described by the existing operator (`bin/seon
   --root <root> reset --force`, `bin/seon --root <root> start <name>`;
   cluster details live where they already live, `data/clusters/<name>/`).
   No `bin/seon lab`, no `/lab` route, no `seon.dev.lab` namespace: **the
   lab IS the debug page** — `/agent/{id}/debug` in `seon.render.web`
   grows the graph, inspector, candidates, and scenarios, and its
   "prospective prompt" becomes the prompt scenario.
7. **The load definition is real code, and for the dogfood world it is
   almost nothing new.** The seed world is THE STEWARD OF SEON'S OWN CODE:
   an agent whose namespace is a real first-party namespace. Its data is
   already there (that namespace's functions, tests, usages, errors), so
   the load definition reduces to the config manifest that declares the
   agent and its namespace plus a few seed messages and plan items —
   applied with the existing `bin/seon config apply`. The agent's own
   render, processing, and ORDERING functions live in ITS namespace, as
   real files hot-reloaded on edit or as functions it defines in real
   turns. No `src/seon/dev/lab/world/` directory.
8. **Trying a new storage format is a code branch**, as the owner said:
   add attributes (or retype existing ones — retype + reset is ruled),
   change the code that writes and reads them, reset the lab cluster, look.
   Nothing needs deleting first; the lab shows old and new attributes side
   by side because it reads datoms.

**Still open:** which first-party namespace the first steward works;
where the default ordering function lives; the graph's default depth.
