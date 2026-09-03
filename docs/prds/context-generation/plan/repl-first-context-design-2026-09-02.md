---
type: prd
status: draft
tags: [prd, agent, context, render, teaching, architecture]
---

# REPL-first context — the converged design, for the owner's markup

*Draft 2026-09-02 from the live dialogue and the
[REPL-first probes](../research/repl-first-probes-2026-09-02.md). It
CONSOLIDATES rulings 47–55 under the owner's reframe of 2026-09-02 and
REPLACES the reader-centric spelling of 53–55 (reopened 2026-08-29).
LEGEND: [REAL] exists at HEAD, verified in the probes; [P] proposed;
[?] an open fork listed in §9. Nothing here is built until the owner
says go.*

## 0. The frame, in the owner's words

The agent is dropped into a Clojure REPL in its own namespace. Context
is three things and nothing else:

1. **the data** — discovered from the agent's own entity outward, never
   authored, never hand-assembled;
2. **the best render function for each value** — the priority chain:
   an inline render on the value → a function in the agent's own
   namespace whose input schema is the data's schema and whose output
   schema is `:seon.render/ai` → the family's declared face → the
   floor; agents are ENCOURAGED to write their own;
3. **the teaching needed to explain what was shown** — derived by
   walking back from the functions the queries and renders used:
   `doc` and `dir`, tailored to this system, never walls of prose.

No magic. Every line the agent reads is a form it could type and a
value that form returns. Queries are legit Datalog and pull with good
examples: an easy first query, then "what is new" through the
database's own `since`/`as-of` and transaction metadata. Every agent in
every namespace is tutorialized on ITS neighbourhood, and its render
functions become its HTML interfaces too.

## 1. The transcript IS the context — one REPL session, three kinds of entry

Every context entry has the same shape [REAL at HEAD:
`ns=> (form)` + printed value, `web.clj:562`]:

```clojure
my.agents.root=> (form)
<the value, rendered by the best render function>
```

Three kinds of entry, one grammar:

| kind | who typed it | how the value renders |
|---|---|---|
| **discovery** [P] | the system, on the agent's behalf, at generation | the best render function for the value (§3) |
| **teaching** [P] | the system, when a discovery entry demands it (§4) | `doc`/`dir` output — data, not prose |
| **history** [REAL] | the agent, in earlier turns (its evals) | its stored result, re-rendered through the same chain |

A fresh agent has zero history entries; turn N has N; compaction
retracts/supersedes the agent's context evals and regenerates (ruled).
Fresh, turn N, and compaction are ONE function of the database value
(52). The seven-sentence intro is the one authored instruction entity
(46, census 1.1) and the only string face.

## 2. Discovery — the data, found from the entity outward

The root is the agent's namespace row `[:seon.ns/name <ns>]` (39/40).
The installed schema says which attributes are refs and which are
identities [REAL: `walk/root-selector` enumerates them]. Discovery is
the set of **one query per ref edge**, both directions, each spelled as
an ordinary form the agent can re-run:

```clojure
;; who am I — the namespace row and what points at it
my.agents.root=> (doc *ns*)                                   ; [P] polymorphic doc, §4
;; what is addressed to me — a reverse edge, ONE query for the whole collection
my.agents.root=> (seon.db/q '[:find [(pull ?m [*]) ...]
                              :where [?m :seon.cluster.message/to
                                      [:seon.cluster.agent/id "root"]]])
<rendered by the best render function for a collection of messages>
;; my open run — a forward edge
my.agents.root=> (seon.db/pull '[*] [:seon.cluster.run/id "…"])
```

Rules, each grounded:

- **Collection-first** (55): a collection-shaped edge is one query
  explaining all N; instance reads are dig-ins the agent can type.
- **Identity on every ref leaf** [REAL in the walk's generated
  selector; NOT in a bare `[*]`, probes §1a]: the generated selector
  expands each ref to `[:db/id <identity-attr>]` so every handle in the
  transcript is a lookup ref the agent can pull. [?] whether
  `seon.db/pull` with `'[*]` should do the same for the agent's own
  typed pulls (a dependency-shaped convenience, not magic: Datomic's
  own `[*]` semantics are preserved, refs simply carry their identity).
- **Recency is free**: every datom carries `:db/txInstant`; a
  collection query orders newest-last with no stored salience (52a).
- **Bounded**: collection width and node caps come from the one
  `:seon.sci.admit/caps` dial [REAL]; omissions are elision values with
  requery identity [REAL].
- **Distance is spent on ref hops** [REAL], but the DISTANCE-SHRINK
  budget loop in `seon.cluster.prompt` DIES: budget is compaction over
  evals (ruled), never a thinner walk.

## 3. Render — the best function for a value, found by contract

**The proposal that changes the schema seam** [P]: a render function
declares the data's schema as its INPUT and `:seon.render/ai` (text) or
`:seon.render/html` (hiccup) as its OUTPUT:

```clojure
(defn inbox-view
  "Newest last, one line each: who, when, the first sentence."
  {:malli/schema [:=> [:cat [:sequential :seon.cluster.message/message]]
                  :seon.render/ai]}
  [messages]
  …)
```

Selection is then a QUERY over program-graph contracts, in priority
order, ties loud (43):

1. an inline `:seon.render/ai` on the value itself [REAL: explicit producer];
2. a public function in the AGENT'S OWN namespace whose arity accepts
   the value's family and returns `:seon.render/ai`
   [REAL mechanism: `render/candidates` does this by Malli validation
   over the namespace's publics, 0.3 ms; [P] the same answer as one
   Datalog clause over `:seon.fn.arity/input-refs`/`output-refs` once
   faces carry this contract shape — TODAY zero rows match
   `output-refs :seon.render/ai`, probes §4];
3. the family's own namespace (the family's schema key's namespace)
   — the "declared face" becomes the function that lives beside the
   data, found the same way [P: dissolves the `:seon.render/ai <sym>`
   schema PROPERTY — 364 declarations, 285 of them the one
   `seon.error/render-ai` — into ordinary contracted functions; one
   mechanism (§2.5)]. [?] keep the property as an explicit override.
4. the floor: `seon.print` over the admitted value [REAL], total.

The value's FAMILY is derived, never stamped: a pulled entity's
identity attribute → the entity schema that references it
(`[?s :seon.db/attributes true] [?s :seon.schema/references ?r] [?r
:seon.schema/key <identity-attr>]`, 0.1 ms raw; or Malli's shape index,
0.3 ms — both [REAL], probes §2). Shape (collection-of vs single-of)
derives from the declared Malli output form at registration (55).

**Rendering is the REPL's printer** [P, naming per the vocabulary law]:
in a Clojure REPL every result is printed; here every result — a
discovery, a teaching entry, the agent's own eval — is printed through
this chain. The agent customizes printing the way Clojure does, by
defining the function (Clojure's `print-method` is the precedent; ours
dispatches on the declared schema instead of the JVM type). [?] whether
an explicit agent-callable exists at all (`seon.render/ai`?) or the
printer is the only door.

## 4. Teaching — `doc` and `dir`, tailored, polymorphic, demand-driven

**`doc` takes anything, or a list of anythings** (owner, 2026-09-02),
and shows the relevant parts, derived from program-graph rows:

| argument | shows |
|---|---|
| a function symbol [REAL, ugly — issue filed] | docstring, arglists, per-arity arguments IN ORDER with each argument's schema KEY, the output alternatives |
| a namespace symbol / `*ns*` [P] | ns docstring, requires, the owner agent, publics as one line each (name + first docstring line + concise in/out), schemas it declares |
| a schema key [P] | the Malli form compactly, its faces, the functions that accept/return it (from contracts), a real example row if one exists |
| a test symbol [P] | its docstring, its subject, the functions it reaches |
| a value (map with an identity attribute) [P] | its family's doc + the render function that would print it |
| a collection of the above [P] | each, in order — the "bulk docs" [TARGET] row lands here |

`dir` stays what it is [REAL, 31 names for `seon.db` in 6 ms].

**Which teaching entries appear, and where** (52b, unchanged): every
discovery/history entry's DEMANDS are the functions and schema keys its
form and rendered value mention (post-bridge, the settled form's usage
children — ruling 50/51; until then, the reader over the form); each
unmet demand generates its `doc` entry BEFORE the entry that uses it;
demands already satisfied by the agent's own correct prior use generate
nothing; a prior error generates the doc plus a real test as
demonstration (`tests-reaching`). Order = topological sort of the
demand DAG, goal last. `(help)` is not special: it is the demand
closure at empty history (52a).

## 5. Delta — what is new, in the database's own vocabulary

Three candidates were named; the probes ground the first as the
simplest viable constraint [?]:

- **(a) since-shaped re-run** [REAL primitive, probes §1]: at turn N,
  each discovery query re-runs against `(seon.db/since <last basis>)`;
  a non-empty result IS the new entry, spelled exactly as the agent
  would type it (`(seon.db/q <same query> (seon.db/since 536870931))`),
  rendered by the same render function. Datahike's `since` returns only
  the datoms asserted after the basis, so a changed entity shows only
  its changed attributes — a diff in Datomic's own vocabulary, no
  editscript, no M13 face to design. Retractions are invisible to
  `since` (Datomic semantics); [?] whether that matters for any family
  at hand (messages, runs, errors, plan items all accrete).
- **(b) staleness re-run + identity diff**: the eval's stored
  read-evidence carries per-attribute revisions [REAL, db.clj:340];
  stale ⇒ re-run and render the identity diff (`seon.db/diff`,
  `identity-diff` [REAL]). More machinery, same information as (a).
- **(c) output-level diff**: rejected — render output changes with
  code generation and carries no identity.

Effects fire once (54c): a history entry whose closure touches an
external sink replays its stored result and never re-runs; `since`
re-runs apply only to pure discovery queries — the graph's
`:seon.fn/external-sink` facts decide, as they already do for
`seon.db/diff` [REAL].

## 6. What this dissolves (do not build; delete when the replacement is live)

| dies | replaced by |
|---|---|
| `seon.render.walk` neighbourhood orchestration + distance policy (829 lines) | one query per ref edge (§2) + compaction |
| `seon.cluster.prompt` distance-shrink loop | compaction over evals |
| `seon.render.transcript` projection policies (`recent-entry-count` 6, `best-summary`, fit ladder; 1,009 lines) | history entries render through the one chain; budget = compaction |
| `:seon.render/ai <sym>` schema properties (364) [?] | contracted render functions found by query |
| narration faces (census cat. 3: 14 sites) | results as data through the floor or a data face |
| the reader-selection ladder remnants (M1–M15 minus M6/M13) | §3's four-rung priority, one query |
| `walk/prose`, `effect/context-suffix` (dead) | already deleted |

## 7. What this leverages (already built, verified)

`seon.db/since`/`as-of`/`history` with elided db; `:db/txInstant` on
every datom; Datahike's query cache (LRU 100, keyed by query/args/db
snapshot) and per-attribute revisions (the memo key for rendered
bytes); Malli's shape index for family-of-value; `:seon.fn` contracts
with per-arity input/output refs; `seon.print/fit` and elision values;
the schema reference graph (3,655 edges); the bridge's usage children
(50/51) for demand tracing once landed; every eval already stored as
(form, result, basis).

## 8. Trials the design must pass before it is a wave

1. **P-STABLE-REGEN** (52): regenerate twice at one basis → byte-equal;
   one new fact → old prefix byte-equal, new bytes appended.
2. **P-REPLAY-VERBATIM**: every generated entry's form, pasted back by
   the agent, returns the value shown (modulo `since` basis).
3. **P-OPENING-COST**: the steward scenario's opening (54f) generates
   in under 1 s and under N tokens at the ruled population (322 ns,
   4,033 fn rows) — measured, with `seon.db` reads fixed first (the
   per-call projection rebuild issue is a precondition).
4. **P-TEACH-BEFORE-USE**: no entry mentions a name whose `doc` has not
   appeared earlier, unless the agent's own history already used it
   correctly.
5. **P-OWN-RENDER-WINS**: an agent's contracted render function in its
   namespace is selected over the family's; a second fitting function
   is a loud tie.

## 9. RULED (owner, 2026-09-02 evening; ledger ruling 56)

The four forks below were answered by the owner in conversation (his first
answers were lost with a harness process exit; re-asked and re-answered
the same evening — this section is the durable record):

1. **Render functions are just functions; declaring one makes it
   eligible.** Selection order, most specific first: (a) ANY data may
   expressly specify its render function (inline on the value); (b) a
   render function declared in the AGENT'S OWN namespace whose contract
   accepts the data; (c) the schema's metadata naming a GENERAL render
   function — the fallback when nothing more specific exists (the
   `:seon.render/ai` property STAYS as this rung; the "dissolve the
   property" recommendation is rejected); (d) the floor. Ties loud.
2. **Delta = the `since` spelling with a DIFF under the hood, always
   showing both additions and deletions.** The agent's REPL history
   shows the initial value (pull + render) and later entries are diffs
   named against the basis. Because Datahike's `since` view alone hides
   retractions, "under the hood" means: the same pure read evaluated
   as-of the shown basis and at the current basis, identity-diffed
   (`seon.db/diff` [REAL] is this mechanism for Vars; it extends to a
   query/pull form as the pure read), and the render function for the
   family renders the diff value with `+`/`-` entries. The spelling the
   agent sees names the basis it saw last.
3. **Rendering is invoked by calling the render function on the data.**
   Plus the PRINT FLOOR: any value returned to the REPL or to the UI
   looks up the best (most specific) renderer by the order in (1). No
   separate generic "render" callable is introduced.
4. **Pulls are nested and recursive — teach them right.** No magic on
   `[*]`: the generated queries and the teaching examples use Datahike's
   own nested selectors and recursive pull specs (`{:attr [...]}`,
   `{:attr ...}`/`{:attr N}`) so every ref carries the attributes the
   agent needs; where a first query must gather the refs before naming
   them in explicit recursive pulls, the generated examples do exactly
   that. Verify each idiom against `reference-code/datahike/src/datahike/pull_api.cljc`
   before it appears in a teaching example.
