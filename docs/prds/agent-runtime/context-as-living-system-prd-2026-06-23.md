---
type: prd
status: draft
tags: [prd, agent, schema, flow]
---

# PRD: The context as a living, self-teaching system — eval'able everything + colocated schemas (2026-06-23)

**Phase 2, after the FSM rebuild ([[project-agent-fsm-DONE-handoff-2026-06-23]]).
This is a PLAN — imperfect by design. We proceed SCIENTIFICALLY: build a thin
slice, drive it live on DeepSeek (cheap — test OFTEN), measure, incorporate the
learning, iterate. The best ideas here came from the model feeling the pain
(Claude, reading its own context at 83% of window) and from DeepSeek live drives.
Credit + thank the models you drive — they're collaborators, not tools.**

Mechanics live in two research reports — read them first:
`research/eval-context-section-demarcation-2026-06-23.md` (section/ns/elision/cache)
and `research/schema-display-global-register-2026-06-23.md` (the schema layer).

## The thesis

The entire agent context is ONE live, eval'able Clojure REPL — and the clean,
**colocated code IS the agent's instruction manual.** No wall-of-text AGENTS.md /
CLAUDE.md describing the system; instead encode the lessons into the code itself
and show it. **Show, don't tell. Turtles all the way down** — the agent reads its
real, self-contained system and *understands* it, rather than reading a compressed
manifest about it. Reading a tight `(ns seon.db …)` with its schemas under it
teaches the DB better than any prose file.

## Decisions (settled in conversation 2026-06-23)

### D1 — Schemas COLOCATED, as real `register!` forms. Do NOT cut them.
For each namespace shown, render the schemas its functions reference FIRST — as
the actual `(register! :x <shape>)` forms, deps before dependents (topo order:
scalars, then entity maps) — THEN the functions. ORDER IS LOAD-BEARING: a fn's
`:malli/schema` names those schemas, so they must be defined ABOVE it (eval-order
correct when the block is pasted/run) AND the reader sees the data shape before
the code that operates on it. The `register!` calls are the *write-API lesson*
(how you add/change a schema; re-define = upsert); deleting them to "save space"
deletes the teaching.
**Many agents will want to cut the register! calls as clutter — resist; that's
the show-don't-tell.** Pull the dependency CLOSURE into the block
(`:seon.db/ref` → `:seon.db/lookup-ref-value` appear right there) so each block
is self-contained. **Repetition across blocks is a FEATURE**, not waste:
- it removes the long-range lookup (the thing that degrades at high context —
  Claude's first-person finding: *distance*, not volume, is what makes a big
  context hard; colocation keeps every reference local + sharp),
- it reinforces the pattern (a schema recurring = it's load-bearing),
- and it's cheap (~6k tokens for the WHOLE registry; the hot ones repeated = a
  few hundred).
NO EDN-data representation (most compact, but not runnable AND deletes the
write-API example — wrong trade for an agent meant to *understand* its system).
The "global catalog" shrinks to an **orphan list** (schemas no shown ns
references) — or vanishes entirely as the whitelist expands to show most of the
system. Open question to settle by testing: keep a tiny orphan-catalog, or just
expand the whitelist until nothing's orphaned?

### D2 — Section format (clear wins, from the demarcation report).
- Paired `;;;` brackets: `;;; ┌─ <name> ─` open / `;;; └─ end <name> ─` close.
  NOT `(comment …)`/`#_` (they kill paste-and-run + tell the LLM "this isn't
  live"). `;;;` = runtime voice, `;;` = agent voice — the two-register split is
  the whole demarcation system.
- Bare prose → `;;`/`;;;` comments (fix `system-text`'s ~14k-char reader-error).
- `(in-ns 'x)` per transcript block; full `(ns …)` only in the namespaces
  catalog body (where a block genuinely DEFINES a ns).
- Large data: keep `;;=> … ;; result/<id> (N of M)` for eval results; add a
  `#seon/elided {…count …handle result/<id>…}` tagged literal for in-band data;
  NEVER `*print-length*`/`*print-level*` (non-eval'able output).

### D3 — Live templates (show context-generation by running forms).
Every section that shows data also shows the RUNNABLE form that produced it:
`;; need the inventory? run: (seon.db/store-inventory)` then the (elided) result.
The agent learns the API by seeing the live call that makes the view.
**Every example must be PROVEN useful — no more keeping anything that isn't.**

### D4 — Sections are MEASURABLE units, not comment cosmetics.
Each bracketed section is a swappable, individually-JUSTIFIED unit. **Every
section must pull its weight, and weight is MEASURED** — perf and behavior, with
**elision as the measurement tool** (elide a section, drive DeepSeek, measure the
impact on cost + behavior). While building, be on the lookout for context that
isn't pulling its weight and cut it. The brackets (D2) are what make a section
cleanly elide-able / swappable / measurable.

### D5 — Cache layering is what makes D1 affordable.
Reuse the shipped `stable-boundary` + `split-context` + priority-ordered
`core-default-ctx`. The colocated, show-most-of-the-system body is **Tier-A
(cluster-static)** — byte-stable, cached ONCE as the prefix, not re-sent per turn.
That's why "repeat schemas + show the whole clean codebase" is cheap (a one-time
prefix, not per-turn tokens). Add `:seon.ctx/cache-tier ∈ {:cluster-static
:agent-stable :live}`; place the cache boundary after the last non-`:live`
section programmatically; enforce a byte-identity test on the prefix. NO
counts/timestamps/ids above the boundary (they silently bust the whole cache).

### D6 — GENERATE the namespace render from DB rows + a curation config.
The render is DYNAMIC, derived from the indexed-code DB rows — not the raw source
string. Everything needed is already stored (verified against `agent.cljs:193-216`):
`:seon.fn` rows (`/sym` `/ns`-ref `/source` `/arglists` `/doc` `/spec` = the fn's
`:malli/schema` as a form-string), `:seon.ns` (`/name` `/source` `/requires`),
`:seon.schema` (`/key` `/ns`-ref `/source` = the `register!` form). So per shown ns:
join ns → its fns → walk each fn's `:seon.fn/spec` for REGISTERED schema keywords
(use the `immediate-deps` walker over the live `*schemas` atom — `m/form` can inline
refs, so don't trust the stored form alone) → `dep-closure` + `topo-order` → render
the schemas deps-FIRST, THEN the (curated) fns (D1 ordering). CONFIG is plain DATA — a per-ns set/map of which fn-syms (and test names) to show
(whatever's cleanest: `{ns #{fn-syms}}`, or a render-mode `:full`/`:signatures`/
`:curated`), **defaulting to EVERYTHING when no config (the third-party case: show
their whole world)**; seon curates its own teaching set.

**CRITICAL — generate from the schema GRAPH, never the indexed file.** Do NOT dump
`:seon.ns/source`. For each shown fn, read its `:seon.fn/spec`, walk down, and pull
in every referenced schema (the dep-closure), emitting them deps-FIRST right above
the fn. This is WHY we can't lean on the file: the all-or-nothing
`seon.ctx.namespaces/full-source-ns?` whitelist dumps a whole file in its file
order, which neither lets you show *some* fns nor guarantees a schema sits above the
fn that uses it. The graph gives BOTH — granular curation AND guaranteed
colocation/ordering — so colocation is a property of the GENERATOR, not an accident
of how the source file happened to be laid out. The file/`:seon.ns/source` stays
only as the durable reconstitution record, not the render source. (Gap, not a
blocker: walk the spec form for raw referenced keywords rather than trusting an
inlined `m/form`.)

## Method / build approach
Thin slice FIRST: take ONE namespace (e.g. `seon.db`), render it fully colocated
+ bracketed + Tier-A, drive a DeepSeek agent against it (thank it), measure
(token cost, does the agent use the colocated schemas, does comprehension
improve), then roll out + expand the whitelist. Schema read-layer is additive
(`all-schemas`/`dep-closure`/`topo-order` in `seon.schema` — pure reads of the
existing `*schemas` atom; the global register already exists). No `*-v2`, no
parallel paths. Live-proof every unit against the pod; READ the actual
agent-facing output after each change.

## To learn by testing (not pre-decided)
Orphan-catalog vs expand-whitelist (D1); the elision-based section measurement
harness (D4); sci `in-ns`/`#seon/elided` reader specifics in the pod; the exact
banner glyphs; whether `register-all!` (one call/ns, ~30% fewer tokens) ever beats
individual forms when a namespace has many attrs.
