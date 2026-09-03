---
type: prd
status: draft
tags: [prd, agent, context, render, architecture, refactor, deletion]
---

# REPL-first context — ONE platform: what we add, refactor, and delete

*Draft 2026-09-03 for the owner's markup, written under ruling 59a: nothing
is deleted until this document explains, on one platform, what is
refactored, what is deleted, and what is added. Behavior authority:
[repl-first-behavior-2026-09-03.md](repl-first-behavior-2026-09-03.md).
Evidence: the [parallel-paths register](../research/parallel-paths-register-2026-09-03.md)
(every row cites file:line at HEAD) and the [probes](../research/repl-first-probes-2026-09-02.md).
This file REPLACES the implementation companion
[repl-first-context-design-2026-09-02.md](repl-first-context-design-2026-09-02.md),
which becomes history when this one is accepted. LEGEND: [REAL] exists at
HEAD and stays; [REFACTOR] exists and changes in place; [ADD] new; [DELETE]
goes, with its replacement and the proof that gates the deletion.*

## 0. The goal (owner's words, 2026-09-02/03)

The agent is dropped into a Clojure REPL in its own namespace. Its context
is generated from its data and its graph neighbourhood, structured as
FORMS we evaluate to surface the data and to explain how we found it; the
values print through the best render function, which agents write and
share; teaching is `doc`/`dir` walked back from what those forms used;
`(help)` bootstraps it all, generated. The same generation through
`:seon.render/html` IS the entire system UI. Every agent's context differs
because its neighbourhood differs. Every turn regenerates the whole thing
from facts; compaction is a fresh session that loses nothing. NOTHING is
hardcoded: "the agent's entire context should be nested russian dolls of
render functions being called, producing a coherent transcript of evals
and results; the same with the html — something at a high level to do
layout and sub render functions to render the individual values and
context blocks." ONE design, one platform, no parallel systems.

## 0a. What we have today (the register, in one screen)

Verified at HEAD by the [parallel-paths register](../research/parallel-paths-register-2026-09-03.md)
(file:line for every claim) and the [probes](../research/repl-first-probes-2026-09-02.md):

- **Three semantic context assemblers** (`seon.bootstrap` generated
  opening, `seon.render.walk/history`, `seon.render.transcript`) wrapped
  by **two glue paths** (`web/context-pass`, `cluster.prompt`) plus a
  debug reassembly; the prompt is a string join of per-entry bytes;
  `cluster.prompt` shrinks graph distance to fit a budget.
- **Six places decide visible omission** (`seon.print/fit`, profile knobs,
  `render.ns`'s ladder, transcript tail-of-6 + summaries, distance shrink,
  conditional MCP fitting); only one emits canonical elision values; the
  measured fit ladder halves strings to ZERO first.
- **Three namespace descriptions** (acquisition-time `doc` map,
  `render.ns` face, `my.run` face); `doc` answers only function symbols.
- **Faces are schema PROPERTIES** (364 `:seon.render/ai`, 285 of them one
  error face) with contracts on a generic render unit; ZERO functions are
  discoverable as renderers by contract query; agent-namespace candidates
  are found by a Malli scan per render.
- **Diff**: `seon.db/diff` (Var-only) + a count-only face; `membership-diff`
  and vendored `editscript` with no production caller; read-evidence
  stores every read's full result again (223 KB for six evals).
- **No result handles**; render provenance is known at selection and
  discarded before the context is assembled; 34 hardcoded opening/
  narration sites (census 2026-08-28).
- **What is right**: eval facts with result EDN/blob and read-evidence
  revisions; schema-derived root selection; the four-rung producer
  chain's shape; `seon.print` elision values; the exact prompt capture;
  the def-restore seam; the web delta packages; `seon.db/since`/`as-of`.

## 0b. Turtles all the way down — the composition, concretely

**One recursive function.** `render` takes a VALUE and a projection
(`/ai` or `/html`), selects the most specific render function for that
value by the query of §2.2, calls it, and the function calls `render` on
its parts. The base case is the floor (`seon.print/fit`), itself a
nameable render function. There is no other way text or hiccup is made.

**The context is a value, and it is a query result.** The generator (§2.1)
produces DATA only: it evaluates its forms and settles them as evals.
The transcript value is then a PULL over the agent's evals — its own runs'
and the generation run's — ordered by the generator (help, self, edges,
teaching before use, history by basis, trigger last). So the whole
context is literally `(render (pull <transcript-selector> agent) :seon.render/ai)`
and the whole page is the same call with `:seon.render/html` — "it's all
queries rendered through the render function", at the top level too.

**The dolls, by family** (each level is a family in the schema registry
with a GENERAL render function on its schema — the rung-(c) default — that
any agent may out-specify in its own namespace by writing a function
with the same input and output):

| level | family (value) | `/ai` general render function does | `/html` twin does | an agent overrides it by |
|---|---|---|---|---|
| 0 layout | the transcript: `{agent, basis, entries [...]}` | joins `(render entry)` for each entry with blank lines; appends the REPL-state line (basis, time) | the page: header, the entry list as blocks, the right-column panels (agent render fns with `/html` twins) | a fn in its ns taking the transcript → its whole screen/page layout changes |
| 1 entry | one eval: `{ns, form, result, basis, handle, rendered-by}` | prints `ns=> form`, then `(render result)`, then `;; result/<id> rendered-by <fn>` | a block: form, `(render result :html)`, the handle chip | a fn taking an eval → its own entry style |
| 2 value | the result's family (message collection, fn row, ns doc data, help data, diff value, error value …) | the family's function, or the agent's, or another agent's (§2.2 order) | its `/html` twin | the normal case — `inbox-view` |
| 3 leaf | anything unclaimed | `seon.print/fit` with the profile: shape first, strings last, one requery form per elision | the same node as hiccup | never — the floor is total and the same for everyone |

Teaching entries are the same shape: `(doc x)` is an eval whose RESULT is
doc data (a value of the doc-data family) rendered at level 2 by the
doc-data render function; `(dir ns)` likewise; `(help)` is an eval whose
result is the coverage set, rendered by ITS render function. The intro
instruction is a value of the instruction family whose render function
returns its text. So the test for "turtles all the way down" is one
question — *is there any byte in the context that was not produced by a
render function selected by the query?* — and the answer is no. The
generator never concatenates text; the layout function does, and it is
replaceable.

**Why this is not fragile:** the levels are schema families (data), the
default render functions are ordinary contracted `defn`s (facts in the
graph), selection is one query over contracts, and the generator only
decides WHICH FORMS TO EVALUATE and IN WHAT ORDER — derived from the
schema's ref edges and the demand closure, never from a list. Adding a
family adds a doll; adding a render function changes one doll's face;
neither edits the generator. Hot reload of any render function changes
the next regeneration; a redefinition that breaks its contract is a typed
error value rendered where it happened (2.4), never a blank.

**What "render function" means (owner, 2026-09-03; ruling 60).** Any
function whose INPUTS are satisfiable from the value being rendered —
plus what call preparation can inject (the current database, the
environment) — and whose OUTPUT is `:seon.render/ai` (MUST be a string)
or `:seon.render/html` (MUST be hiccup). Separate functions for the two
projections are normal. A function that also requires something we do not
have is not a candidate. More than one candidate is a SORT (§2.2). A value
may also carry the render directly — `{:seon.render/ai "text"}` or
`{:seon.render/ai 'my.ns/fn}` — the inline rung.

## 0c. The platform in one paragraph

One walk from the agent's namespace row, spelled as forms, evaluated, and
SETTLED AS EVALS — the same eval family the agent's own forms already
settle into. One printer (`seon.print/fit`) decides every visible
omission. One render-selection query decides which function prints a
value, over program-graph contracts. One `doc`/`dir` pair explains
anything the graph knows. One diff owner (`seon.db/diff`) answers "what
changed since the basis I saw" — later. One after-value line names the
result symbol and, when the floor rendered it, the renderer. The web UI
is the `/html` projection of the same evals. Every turn regenerates the
whole context from facts (52, 58d); caching is a property, not a
mechanism. Nothing else assembles, selects, summarizes, or clips.

## 1. What SURVIVES as authority [REAL] — the platform we build on

| authority | why it is the owner | register row |
|---|---|---|
| `:seon.cluster.eval` family: form source + `result-edn`/`result-blob` + basis + read-evidence | the durable outcome of every evaluation; the agent's history AND the generated entries are this one family (53) | §6 row 1 |
| `seon.blob` content-addressed tier + `blob/get` | oversized results live here; the def-restore seam already recalls them (`sci/eval.clj:605-615`) | §6 |
| schema-derived root selection: `walk/root-selector` enumerating forward + reverse ref attributes and identities from the INSTALLED schema | the one place that knows the graph's edges without a hand list | §1 row 1 |
| `seon.render/producer` four-rung chain (explicit → viewer ns → schema face → floor) | already B3's chain; becomes a query, stays the one owner | §2 row 1 |
| `:seon.render/ai`/`/html` schema-property faces | ruled the GENERAL rung (56a, 58b) | §2 row 3 |
| `seon.print/fit` + elision values with requery identity | the one presentation authority; its ladder is refactored, its role is not | §5 row 1 |
| `seon.sci.admit` caps | boundary safety, distinct from presentation | §5 row 3 |
| `seon.db/diff` + `identity-diff` | the one semantic diff (56b/58d — later wave) | §4 row 1 |
| `:seon.context.capture` exact prompt capture | the receipt for an external crossing | §6 row 4 |
| web keyframe/delta packages + the render proc's losable retention/join | delivery compression and channel memo, not semantics | §4 row 6, §1 row 3 |
| `seon.def` restore seam (`def-value`, `install-root-row!`, unrestorable-reason) | the one seam that puts stored facts back into an agent's ctx | — |
| the injected `doc`/`dir` names in `clojure.core`/`clojure.repl` | Clojure's spelling; the implementation behind them changes | §3 row 1 |

## 2. What we ADD

### 2.1 The generator — the walk as generated evals [ADD]

One function of the database value, the agent's namespace, and the
turn's render context. It:

1. builds the root pull from the installed schema (`root-selector`
   [REAL]) at the ruled distance (2 [REAL]);
2. turns each hop into ONE FORM per ref edge (collection-first), spelled
   exactly as the agent could type it (pull by lookup ref; `q` with a
   reverse edge; nested/recursive selectors per Datahike's grammar,
   verified live);
3. evaluates each form through the ordinary eval seam and SETTLES it as
   an eval fact whose run is the system's generation run for that turn
   (so the entry has a basis, a result, and read-evidence like any
   other; 53) — never a rendered string as authority;
4. renders each result through the print floor (§2.4) whose selection is
   the query of §2.2;
5. computes the DEMANDS of every entry (the symbols and schema keys in
   its form and rendered result — post-bridge the settled form's usage
   children, until then the reader over the form), subtracts what the
   agent's own history already used correctly, and emits the teaching
   entries (`doc`/`dir`, §2.3) BEFORE first use; `(help)` is this closure
   at empty history (52a, 58a);
6. orders: help, who-am-I, edges, teaching-before-use, agent history by
   basis, trigger last (B1/B7/G3);
7. emits the transcript as the `/ai` string and the `/html` page from
   the SAME entries (57).

Turn N = the same call; fresh, turn N, and compaction are one function
(52, 58d). Incremental diff entries (B5) are a LATER accretion inside
step 2 (a previously emitted query at a recorded basis becomes a
`seon.db/diff` form).

**Where it lives:** `seon.render.walk` keeps root selection and the one
pull (its authority); the entry generation replaces `neighborhood` +
`history` there (register §1 rows 1–2). `seon.bootstrap`'s generated
opening episode is the ONLY current path already shaped as generated
evals (register §1 row 6): the generator grows FROM it — its
settle-generated-eval machinery survives, its candidate lists,
supervision strings, and second source reader die (§4).

**Proof gates:** P-STABLE-REGEN (twice at one basis → byte-equal; one
fact → prefix byte-equal); P-REPLAY-VERBATIM (every generated form pasted
back returns the value shown); P-TEACH-BEFORE-USE; P-OPENING-COST at the
ruled population; a fresh isolated cluster's B1 screen read at the bytes.

### 2.2 The render-selection query [ADD, replaces `candidates`' Malli scan and the hand rung order]

Facts it needs (all program-graph rows [REAL] except the contract shape):
a render function's arity with `:seon.fn.arity/input-refs` naming the
family schema and `:seon.fn.arity/output-refs` naming `:seon.render/ai`
or `/html` — TODAY zero rows match because faces are contracted as
`[:=> [:cat :seon.render/unit] [:maybe :string]]` (probes §4). The
contract shape changes to `[:=> [:cat <family>] :seon.render/ai]`
(56a's declaration) — a REFACTOR of every surviving face (§3.6), and the
one thing an agent must write to make its function eligible.

The order, as one query with rules (measured live 2026-09-03: the
distance rules over `:seon.ns/requires` return 54 namespaces within three
hops of `my.agents.root` in 16 ms cold / 0.07 ms warm):

```clojure
;; candidates for family F, viewed from namespace V, projection P (:seon.render/ai | /html)
[:find ?fn ?rank ?hop ?coverage ?settled-at
 :in $ % ?family ?viewer ?projection
 :where
 [?fam :seon.schema/key ?family]
 [?fn :seon.fn/arities ?a]
 [?a :seon.fn.arity/input-refs ?fam]
 (inputs-satisfiable ?a ?fam)           ; every other input ref of ?a is injectable (db, env) — a rule over :seon.fn.arity/input-refs
 [?a :seon.fn.arity/output-refs ?out] [?out :seon.schema/key ?projection]
 [?fn :seon.fn/ns ?ns]
 (rank ?viewer ?ns ?rank ?hop)            ; 1 = viewer's own ns, 2 = another agent's ns at hop N, 3 = the family's own ns
 [(get-else $ ?fam :seon.schema/required-attr-count 0) ?coverage]  ; derived at registration (55)
 [?fn :seon.fn/settled-at ?settled-at]]   ; or the row's tx instant — recency is free
;; rules: (rank ?v ?ns 1 0) when ?v = ?ns; (rank ?v ?ns 2 ?d) via (hop ?v ?ns ?d) for d in 1..3 —
;;        the hop rules verified live; (rank ?v ?ns 3 0) when ?ns is the family key's namespace row.
```

The INLINE rung (a value naming its own render function) is not a query
— it is a key on the value [REAL: `explicit-producer`]. Sort =
`[rank hop (- coverage) (- settled-at)]`; equal after all four = loud tie
(43). The floor is the total base case. This is the whole of B3 and 59d
in one place; `producer` becomes "explicit, else this query, else floor".

### 2.3 Polymorphic `doc` and `dir` [ADD behind the existing names]

`doc` dispatches on what the graph knows about its argument (a `:seon.fn`
row, a `:seon.ns` row / `*ns*`, a `:seon.test` row, a `:seon.schema/key`,
a map carrying an identity attribute → its family, a sequential of these)
and returns DATA printed through the floor (B6). It reads the handed
database/projection at call time (values carry their world) instead of the
acquisition-time documentation map (register §3 row 1). The contract lines
come from the ordered `:seon.fn.arity/arguments` rows, not the flat ref
set (the filed doc issue). `dir` keeps its behavior; `(dir *ns*)` works.
`seon.render.ns`'s data derivation MOVES here; its private budget loop and
HTML section policy die (§4).

### 2.4 The smart print floor [REFACTOR of `seon.print/fit`, listed here because its contract changes]

New ladder (B4): breadth first (keep the first N children per collection,
N shrinking per depth), then depth (subtrees → shape summaries `{…7 keys}`
/ `[…116 items]`), strings last and never below one line; ONE pasteable
requery form per elided subtree; the per-value token budget is the
profile's `:seon.render.profile/token-budget` (a config fact; default to
be ruled, ~5k). Every consumer — agent context, `/data` windows, the MCP
door, HTML blocks — calls THIS fit unconditionally with an explicit
profile; no consumer keeps a ladder of its own (register §5 count: six
places decide omission today; after: one).

### 2.5 Result handles [ADD, through the def-restore seam]

Every eval's result is bound in the agent's SCI ctx as `result/<id>`
(id = the eval's identity, shortened deterministically) at ctx
construction, by the SAME seam that restores the agent's defs
(`sci/eval.clj:598-700`: `def-value` reads `value-edn` or `blob/get`;
an unrestorable value interns a typed unrestorable marker). A result
whose value cannot be recalled or read binds NOTHING and the transcript
shows no handle for it (59c). The after-value line `;; result/<id>` is
emitted by the one entry renderer; the reader needs no special case
because `result/<id>` is an ordinary bound symbol (the first
implementation special-cased the reader — `src-old/seon/repl/parse.cljc:437-452`
at `9e44815f5` — because it stashed results outside the ctx; we do not).
Cost: one intern per eval per ctx build; the value is read lazily from
the fact (a `delay` behind the Var) so a 100-eval history costs 100
interns, not 100 blob reads.

### 2.6 Floor provenance [ADD, minimal]

When the floor renders a value (agent context or HTML block), the entry
carries which function rendered it, taken from the selection query's
answer at that moment; it is displayed on the result-handle line
(`;; result/k7f2  rendered-by seon.cluster.message/render-ai`) and as
`data-rendered-by` on the HTML block. It is stored, if at all, as one
attribute beside the eval result (`:seon.cluster.eval/rendered-by`,
optional); never a fact per function call (59b). `render-cost-fact`
[REAL] accretes the same symbol where it already transacts.

## 3. What we REFACTOR in place

| owner | change | why |
|---|---|---|
| 3.1 `seon.render.walk` | keep `root-selector` + `root-acquisition`; replace `neighborhood`/`history` with §2.1 entry generation; delete `membership-diff` (no production caller) | register §1 rows 1–2, §4 row 3 |
| 3.2 `seon.render/producer` | explicit → §2.2 query → floor; `candidates`' Malli scan and `schema-producer`'s shape matching collapse into the query once faces carry the new contract shape; ties stay loud | register §2 rows 1–2 |
| 3.3 `seon.print/fit` | the §2.4 ladder; requery forms; token-budget-first | register §5 row 1 |
| 3.4 `seon.sci.eval` `doc`/`dir` | live polymorphic dispatch (§2.3); contract lines from ordered arguments | register §3 row 1 |
| 3.5 `seon.db/diff` | accrete query/pull forms as the pure read (Var-only today); its `/ai` face renders through the family's function (M13 dies) — later wave | register §4 rows 1–2 |
| 3.6 every surviving face | contract becomes `[:=> [:cat <family>] :seon.render/ai]` (and `/html`); the 285 identical `seon.error/render-ai` declarations become ONE general error face derived at the error-value registration owner; narration faces convert to data or die | register §2 rows 3–4 |
| 3.7 `seon.cluster.prompt` | keep budget report + capture boundary; delete distance shrink (§4) | register §1 row 4 |
| 3.8 `seon.bootstrap` | keep generated-eval settlement and trigger-last; delete candidates/supervision/second reader (§4) as §2.1 absorbs them | register §1 rows 6–7 |
| 3.9 `seon.render.value` | keep explicit window/requery; delete the authored suffix; fit through §2.4 only | register §5 row 4 |
| 3.10 `seon.cluster/mcp-project` | fit unconditionally with an explicit MCP profile; blob spill stays storage policy (kills the zero-character strings) | register §5 row 7; the filed get_value issue |
| 3.11 `seon.render/walk`'s ambient `*walk-context*` | inputs declared and supplied by call preparation; delete the dynamic var | register §8 row 1 |

## 4. What we DELETE — each with its replacement and the proof that gates it

Order follows the register's "delete only three" (accepted as input, 59a),
expanded to every parallel path it found. Nothing here is deleted before
its replacement is proven live on a fresh isolated cluster and the
behavior authority's ❓ for that area is gone.

| # | delete | lines | replacement | proof gate |
|---|---|---|---|---|
| D1 | `seon.render.transcript` as history/context projection (fixed tail 6, best-summary, second byte budget, its own result reader) | 1,009 | §2.1 entries rendered per-entry through §2.4; episode grading (`seon.eval.drive`) and the agent `/html` page consume the same entries | grading + agent page green on the eval stream; B7 ordering asserted |
| D2 | `seon.cluster.prompt` distance-shrink loop | ~50 | fixed ruled distance + §2.4 elisions + compaction (58d) | P-OPENING-COST; no prompt ever thinner than the walk |
| D3 | `seon.render.ns` private fit ladder + HTML section policy; `my.run/render-namespace-ai`; the 14 narration faces | 675 + 154 + sites | `(doc ns)` data through §2.3 + §2.4; family render functions or the floor | `(doc my.run)` ≡ the old face's information, read at the bytes |
| D4 | `seon.render.walk/neighborhood` + `history` (member narration, `renderer unavailable` text, the second `/form` pass) and `membership-diff` | ~400 | §2.1 | P-REPLAY-VERBATIM |
| D5 | `seon.bootstrap` candidate lists, supervision strings, `calls-symbol?` second reader; `seon.bootstrap/situation` + `render-situation-ai` (the authored `(help)`) | ~450 | §2.1 + generated `(help)` (58a) | B1 on a fresh isolated cluster: trigger last, teach before use, restart continuation |
| D6 | prospective-debug reassembly in `seon.render.web` | ~40 | the debug page shows the captured prompt or calls the ONE generator | debug page ≡ captured bytes when a capture exists |
| D7 | `seon.db/render-diff-ai` (M13) | ~40 | family render of the diff value (later wave) | with B5 |
| D8 | durable `:seon.db/read-result` inside read-evidence (223 KB / 6 evals) | schema + writer | revisions decide currentness; unprovable = stale, re-render | P-NO-SILENT-FRESH: a read without revision evidence never reports current |
| D9 | vendored `editscript` | dep | `seon.db/diff` | no runtime caller (register §4 row 4) |
| D10 | tuned constants standing for events: transcript tail 6, ns closure cap 40 | — | profile/config facts with elision values | with D1/D3 |
| D11 | `*walk-context*` dynamic var | ~40 | call preparation | with 3.11 |

## 5. What we do NOT touch (named so nobody "cleans" it)

Admission caps; blob spill thresholds; the exact prompt capture; web
keyframe/delta; the render proc's retention channel (its INPUT changes to
§2.1 entries); `seon.db`'s read-evidence dependency plans and revisions;
the eval family's result authority (one full artifact + one derived
bounded window — the schema must make that split unambiguous, register §6
row 1).

## 6. Waves (each ends with the behavior authority's ❓ for its area gone and a live proof)

1. **Platform floor** — 3.3 smart fit + 3.10 MCP unconditional fit (kills
   zero-char strings) + the doc contract lines (3.4, first half).
2. **Facts the query needs** — 3.6 face contract shape on every surviving
   face; `:seon.render/ai` becomes a plain output schema; the general
   error face derived once; result identity → `result/<id>` binding
   (2.5) through the def-restore seam.
3. **The generator** (2.1) grown from `seon.bootstrap`'s generated-eval
   path, with 2.2 selection, 2.3 `doc`/`dir`, 2.6 provenance; every turn
   a full regeneration (58d); P-STABLE-REGEN, P-REPLAY-VERBATIM,
   P-TEACH-BEFORE-USE, P-OPENING-COST.
4. **Consumers move** — web retention, agent `/html` page, episode
   grading, debug read the eval stream; then D1, D4, D6.
5. **Deletions** D2, D3, D5, D10, D11 with their gates; D8/D9 when
   revision currentness is total.
6. **Later** — incremental diff entries (B5) as an accretion inside 2.1
   step 2; D7 with them.

Nothing creates a `-v2` namespace or a second registry; each wave lands
in the owner named above and deletes what it replaces in the same
refactor (2.5 of AGENTS.md).

## 7. Settled and open

Settled (owner, 2026-09-03): the render-function definition above (60);
result-handle ids DERIVED from the eval identity; the per-value budget is a
CONFIG FACT in TOKENS, 5k to start; NO CODING — not even wave 1 — until
this document convinces the owner; platform bug fixes continue only where
the code is staying (never polish what §4 deletes).

Open:
1. The transcript family's identity and selector (§0b level 0): a pull
   over `:seon.cluster.eval` rows of the agent's runs plus the generation
   run, ordered by the generator — confirm that the generation run is an
   ordinary run whose evals are the system's entries (53), so the
   transcript IS a query.
2. Whether the intro instruction survives as a level-2 value (an
   instruction-family entry rendered by its face) or dissolves into
   `(help)` entirely (58a).
3. The remaining ❓ of the behavior spec (its foot lists them, each with a
   recommendation).
