---
type: research
status: complete
tags: [research, context, render, web, data-model]
---

# Data and renderer inspection interaction review — 2026-09-06

This review reads the
[design-lab PRD](../plan/design-lab-prd-2026-09-05.md) and the
`datastar-web-ui` skill end to end, then checks the current
`seon.render.data`, `seon.render`, and debug-page implementation. It is
independent of the choice of graph engine. No source, test, database, cluster,
process, or browser state was changed.

## Recommendation

Keep the inspection state as four explicit values:

| Value | Meaning | Change rule |
|---|---|---|
| starting entity | The experiment anchor and root of the accumulated graph. | Changes only through an explicit **Set as start** action or a new inspection URL. |
| selected entity | The entity whose assertions and connections are in focus. | Changes when an entity or actual ref endpoint is followed. It does not change the start or viewer. |
| selected value | An attribute value or nested path being sent through renderer selection. Its provenance remains the selected entity plus attribute/path. | Changes on a scalar/value click. A scalar is never presented as an entity. |
| viewer | The namespace perspective supplied to renderer selection as `:seon.render/namespace`. | Recomputes selection and output for the selected value; preserves start, selected entity, expanded graph, pan, zoom and browser history. |

The header should show all four where applicable, followed by the immutable
database-value identity, output projection, and program identity. “Start” and
“selected” must never be two labels for the same mutable query parameter.

Use `start`, `subject`, `path`, `viewer`, and `output` as independent URL
inputs: `start` is the graph anchor, `subject` is the selected entity, and
`path` identifies a selected nested value within that entity. A first request
defaults `start` and `subject` to the route's existing default subject. A ref
navigation changes `subject`; **Set as start** writes both `start` and
`subject`. Browser Back/Forward restores these inputs. Expanded nodes and graph
geometry remain browser-local keyed state; they are observation state, not
database facts.

The first useful layout is one coordinated surface:

1. graph and assertion table for the selected entity;
2. the actual ordered renderer decision and candidate detail for the selected
   value; and
3. the output returned by the same `render-call` that supplied that decision.

The graph and table are two views of the same bounded observation. Neither may
run a separate graph query or reconstruct renderer selection.

## What exists now

### Data observation

`seon.render.data/entity-observation` already accepts one explicit immutable
database value and entity subject. It returns the resolved eid and database
snapshot, separately bounded outgoing and incoming pages, identity assertions,
identity completeness, and the number of reference attributes probed
(`src/seon/render/data.clj:145-182`).

Outgoing rows are actual EAVT assertions for the selected eid, including
scalar attributes and refs. Incoming rows are AVET assertions assembled only
for attributes whose installed Datahike schema declares `:db.type/ref`; no
reverse edge is stored (`src/seon/render/data.clj:86-143`). Each page is
consumed through `seon.db/index-page` before collection and decoding.

Continuation values carry database snapshot, eid, direction, attribute offset,
and the underlying index cursor. A cursor for another snapshot, entity, or
direction returns `:seon.render.data/stale-continuation`; an absent subject
returns `:seon.render.data/missing-subject` rather than an empty observation
(`src/seon/render/data.clj:65-83,145-162`).

The debug page currently has `viewer` and `subject` query inputs but no
separate starting entity. It defaults a namespace route to that namespace row
and an agent route to that agent row. It also accepts output, independent
incoming/outgoing cursors, bounds, and a nested value path
(`src/seon/render/web.clj:159-220,2293-2316`).

The debug result currently performs two related reads:

- `entity-observation` supplies bounded datom/ref evidence;
- a bounded `seon.db/pull` supplies the complete render value, with explicit
  work, result-count, and result-weight bounds.

If the complete pull refuses, the page honestly withholds renderer selection
and actual output (`src/seon/render/web.clj:918-1081`). The structural entity
view uses the existing value floor and its path/requery links. The raw table is
currently text only: datom cells themselves do not navigate
(`src/seon/render/web.clj:743-778,995-1020`).

On a stale continuation the current web owner retries at the newest database
value with both cursors removed and displays “Data changed; pagination
restarted at the newest snapshot” (`src/seon/render/web.clj:948-975,794-799`).
This avoids mixed-snapshot pages, though it restarts both directions rather
than only the stale one.

### Renderer selection and output

`seon.render/selection` is the public contracted explanation consumed by
`render-call`. The current order is exactly:

1. `:explicit-value` — the selected map's value at the requested output key;
2. `:explicit-request` — the request's renderer symbol at that output key;
3. `:namespace` — public functions in the supplied
   `:seon.render/namespace` whose same arity accepts the actual prepared
   argument and returns the requested output;
4. `:schema` — the matching schema's declared renderer; and
5. `:floor` — `seon.render.value/render-ai`,
   `seon.render.value/render-html`, or the surviving form floor.

The implementation is `src/seon/render.clj:302-456`, and the five-stage shape
is declared in `resources/seon/schemas/seon.render.edn:40-80`. Once a stage
selects, all later stages are present as `:not-consulted`. This is useful
evidence: the page must not display those later stages as evaluated losers.

An explicit value may be literal rendered text/Hiccup rather than a function;
that literal is terminal. Explicit value and request choices are selected
without the namespace stage's compatibility census. The actual invocation
boundary still applies its function contract when the choice is a symbol, so
the page should label these stages **explicit** rather than infer
“contract-compatible” from their position.

Namespace functions are sorted by symbol, not ranked. Each candidate is
reported as `:compatible` or `:rejected` with
`:no-same-arity-match`. Exactly one compatible function wins; more than one is
an ambiguity error which itself becomes the selected result and stops fallback
(`src/seon/render.clj:178-219,366-390`). There is no discovery or relationship
distance for functions in other namespaces today.

For a pulled entity, schema selection validates both pulled and transaction
forms, excludes shapes whose required attributes are not database-storable,
and keeps shapes with the greatest number of required attributes. Multiple
remaining renderer symbols are an ambiguity; they are not settled by recency
or insertion order (`src/seon/render.clj:227-275,392-412`). Attribute form
selection has its existing declaration-specific branch.

The debug page passes its viewer namespace directly as
`:seon.render/namespace`, invokes `render/render-call`, and reads the selection
back from that call's captured static evidence. The displayed output is the
actual returned value of that same call (`src/seon/render/web.clj:976-1081`).
The retained evidence also carries selected producer, declaration row,
invocation argument and read dependencies (`src/seon/render.clj:461-528`).

The current selection UI shows the selected value and five ordered stages,
with namespace rejection reasons and ambiguity errors. It does not yet show
the selected definition source, arity contracts, supplied/defaulted argument
breakdown, or another namespace's candidates (`src/seon/render/web.clj:826-878`).

### Delivery and browser state

The debug page is an existing namespace/agent debug variant delivered through
the current revisioned package, delta/keyframe and Datastar feed path. Initial
HTML contains stable placeholder fragment ids; feed results replace the
observation, selection and output fragments (`src/seon/render/web.clj:2317-2369`).
The current page has browser signals for ordinary disclosure but no live graph
or graph lifecycle. The atlas mentioned by the PRD remains a prototype with
authored data, not a current debug capability.

## Exact interaction semantics

### Assertion and connection clicks

Every click acts on the value in the clicked datom, not on a label inferred
from its position:

| Click | Result |
|---|---|
| outgoing row `e` | Keep the selected entity; it is the row's source. |
| outgoing ref value `v` | Select the referenced target entity and add/show the directed edge `e -[a]-> v`. |
| incoming row `e` | Select the referring source entity and add/show the directed edge `e -[a]-> current`. |
| incoming row `v` | Keep the current selected entity; it is the row's target. |
| attribute `a` | Select the Datahike attribute entity identified by that `:db/ident`; show its stored schema assertions and the declaration/schema facts that can actually be joined. |
| scalar decoded value | Set selected value to that scalar with `[entity, attribute]` provenance; recompute renderer selection/output without changing selected entity. |
| nested structural value | Extend `path` and select that value; use the existing floor continuation/requery link. |
| stored value | Toggle physical versus decoded evidence for the same assertion; do not navigate. |

Only installed `:db.type/ref` attributes produce graph edges. Attribute nodes
are reached deliberately by clicking the attribute; scalar strings, numbers,
keywords and embedded maps do not become invented entity nodes.

Incoming and outgoing continuations are independent. “Continue outgoing” adds
only that page's new assertions. “Continue incoming” retains its attribute
offset and adds only incoming assertions. Page completeness means completion
of that bounded direction. `ref-attributes-probed` measures search work, not
incoming degree; `identities-complete? false` means the visible identity list
cannot be described as exhaustive.

### Candidate interaction

The **Actual** row is always the selected value returned by the captured
`render-call` decision. Clicking any candidate changes only candidate detail:
show its stored function row, arity rows, input/output refs, source and the
argument evidence already acquired by the call. It must not alter selection,
persist preference, or relabel a `:not-consulted` stage as evaluated.

A later **Run preview** action needs an explicit execution contract. Simply
putting a renderer symbol in the current request does not always preview it:
an explicit value has higher precedence and still wins. Calling the function
directly would bypass the decision being inspected. Arbitrary execution also
cannot be called read-only merely because it runs in an SCI fork. Until the
effect boundary proves candidate preview isolation, show source/contracts for
unselected candidates and execute only the page's actual `render-call`.

When preview execution is admitted, it should return a second complete
selection-and-output record labelled **preview**, with its changed selection
input and program/database identities. It must never replace the **Actual**
record or become a stored preference merely because it was clicked.

### Viewer changes

Changing viewer supplies the new namespace to the existing selector and
reruns selection/output for the same selected value. Data observation should
be reused because viewer does not change the immutable database value or
subject. If the new viewer has zero, one, or multiple compatible public
functions, show the current `:no-match`, selected, or ambiguity result exactly.

Do not show other-namespace renderers as a current ranked stage. The proposed
distance stage remains an experiment. When implemented, it belongs between
the current namespace and schema stages and must name the traversed declared
relationships and tie inputs; namespace-string distance is not evidence.

## Updates, omission and stale evidence

Graph state must survive ordinary Datastar fragment morphs. Keep one browser
graph instance; apply node/edge data changes incrementally using stable entity
lookups. Preserve expanded-node set, selected entity, candidate detail, pan,
zoom and positions. A package revision gap uses the existing complete keyframe
for server fragments, then reconciles graph data without resetting browser
geometry.

Display database and program identities separately. The database snapshot on
an observation does not prove which program snapshot supplied renderer source,
and a hot-reloaded host Var does not prove an existing cluster adopted a
published program. Candidate details should use the declaration row already
captured by the call. An unavailable identity is an explicit diagnostic.

Never merge continuation pages from different database snapshots. On stale
input, preserve start, viewer, selected entity and graph geometry, discard the
affected accumulated direction's pages, and label the move to the new snapshot.
The current server restarts both directions; narrowing that reset is a later
improvement, not a reason to hide the present behavior.

Large data has three independent limits today:

- outgoing/incoming index pages have assertion count and decoded-result weight
  bounds;
- incoming traversal has a bounded reference-attribute probe count;
- the complete render pull has work, result-count and result-weight bounds,
  after which value rendering applies the one print-fit/elision owner.

Show these bounds and each continuation beside the affected region. A partial
page establishes only the visible assertions and a lower bound. Do not show an
exact entity degree, identity census, or schema validation from partial data.
If the complete pull refuses, retain the bounded datom evidence and state that
selection/output is unavailable. Exact omitted content remains reachable only
through its real continuation or requery identity; disclosure controls must
not expand an unbounded value already embedded in the page.

## Current capability versus proposed mockup

| Concern | Current | Proposed mockup |
|---|---|---|
| anchor/focus | One `subject`; no separate graph start. | Persistent `start`, independently selected `subject`, and optional scalar `path`. |
| viewer | Independent viewer query; passed to selector. | Keep it independent and preserve all navigation state on change. |
| refs | Bounded outgoing/incoming tables with continuations. | Same observation drives linked table cells and graph edges. |
| renderer order | Explicit value, explicit request, viewer namespace, schema, floor. | Display this exact order; label other-namespace distance as unimplemented experiment. |
| output | Actual output from the same captured `render-call`. | Keep as immutable **Actual** comparison; add preview only after its execution contract exists. |
| candidate detail | Symbol, compatibility/rejection, ambiguity. | Add stored source, one-arity contract evidence and supplied/defaulted arguments from existing facts. |
| graph | None in the debug page. | One persistent browser graph over incrementally acquired actual ref datoms. |
| stale continuation | Typed refusal, then labelled restart of both cursors at newest snapshot. | Preserve navigation/geometry and reset only stale accumulated evidence; never mix snapshots. |
| large values | Bounded datoms, bounded pull, structural print fit and requery links. | Put completion/continuation/omission beside each region and never infer unseen totals. |

This screen is useful before choosing Cytoscape or another graph engine. Its
semantics come from Datahike assertions, the public renderer decision, and the
existing retained-call evidence; the engine only draws the already established
nodes and edges.
