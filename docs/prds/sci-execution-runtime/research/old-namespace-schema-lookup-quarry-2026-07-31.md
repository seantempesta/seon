---
type: research
status: active
tags: [research, render, context]
---

# Old namespace renderer and its schema-closure lookup — quarry

## What the owner remembered, located exactly

The recollection — "a lookup of all schemas used in all functions and then the
full function code for the agent's namespace, and required namespaces got a
more concise version" — is one real mechanism split across two surviving
quarry files:

- `src-old/seon/agent/ctx/namespaces.cljc` (1,292 lines) — selection, density
  rules, ordering, the compact-card renderer, and the *acquisition* of the
  schema closure.
- `src-old/seon/agent/ctx.cljc:1180-1490` — the pure renderers: the
  transitive referenced-schema closure, the full-namespace block, and the
  `;;; ┌─ namespace X ─` demarcation.

Both are alive in `src-old/` (not deleted), so no `git log -S` archaeology was
needed; history only dates the arc: `844ec4483` first derived required-namespace
views from real require edges, `4b46a2cb2` made stored source authoritative,
`2eeb3bd95` collapsed density to current-full + required-compact, and
`49b07a8a8`/`4af13d988` introduced and preserved `schema-ref-closure`.

## 1. The agent's OWN namespace — full

`format-namespaces-block` (`namespaces.cljc:884-1005`) states the density rule
in three words: **FULL, COMPACT CARD, DROPPED**. The full predicate is one
expression with no second control (`namespaces.cljc:695-709`):

```clojure
(defn- full?
  [nm cur-ns full-source current-full?]
  (boolean
    (or (contains? full-source nm)
        (and (= nm cur-ns) current-full?))))
```

Full rendering delegates to `render-one` (`namespaces.cljc:785-823`) →
`ctx/render-namespace-ai` (`ctx.cljc:1475-1490`) → `render-one-ns-ai`
(`ctx.cljc:1405-1459`). The framing and the ordering inside a full block
(`ctx.cljc:1449-1452`):

1. `;;; ┌─ namespace X ─` begin line (`ns-demarc`, `ctx.cljc:1381-1403`);
2. **the stored `:seon.ns/source` verbatim, trimmed, unclipped** — the real
   file text, which already contains every `defn`/`register!`;
3. a blank line, then **the referenced-schema block** (`ref-blk`);
4. a blank line, then **only the member facts not visible in the source**:
   one-line `; ⚠ <sym>: schema-error …` notes and `; ⚠ test <sym> failing`
   notes, each clipped to 120 chars;
5. `;;; └─ end namespace X ─`.

The load-bearing decision, called GI-1 in its own docstring
(`ctx.cljc:1415-1429`): when real source is present, per-member `[fn …]` /
`[schema …]` blocks are **not** re-emitted — that would be pure duplication.
Per-member blocks are the content *only* when there is no stored source, or
when the stored source is the indexer's bare `(ns x)` stub
(`ctx.cljc:1437-1438` tests exactly that string). In that member path the order
is fns → schemas → ref-blk → tests, each name-sorted
(`ctx.cljc:1434-1436,1453-1459`).

An empty current namespace never vanishes: `cur-ns-workspace-stub`
(`namespaces.cljc:760-783`) emits the real `(ns … (:require …))` form the
runtime actually installed, plus a prose hint that a `:seon.render/ai`-declaring
defn auto-runs each turn. `render-one` detects emptiness by *string* sniffing
the rendered text for `"(no recorded source/fns/schemas)"` / `"(not in db)"`
(`namespaces.cljc:815-817`) — the ugliest line in the file.

## 2. Required namespaces — the compact card

`render-one-ns-compact-row` (`namespaces.cljc:1250-1292`) is the concise tier.
What survived the cut, in card order:

| Part | Source | Line |
|---|---|---|
| `schema <key> = <form>` records, name-sorted | the ns's OWN `:seon.schema/_ns` rows filtered to `(= (namespace key) ns-str)` | `1256-1259`, `1075-1084` |
| the referenced-schema block | `ctx/referenced-schema-rows-block` | `1271-1282` |
| blank separator | — | `1286` |
| `fn <sym> — <contract> — "<doc line 1>"` | `compact-fn-head` over public schema-complete rows | `1230-1248` |

What was cut: the function **body**, all docstring lines after the first, and
Malli's own grammar. `callable-contract` (`namespaces.cljc:1178-1204`) rewrites
`:=>`/`:cat`/`:catn` into prose — `map-in {…} -> …` or
`positional [label Schema, …] -> …` — and `compact-fn-head`'s docstring says
plainly it "never exposes Malli's function grammar … never synthesizes a
`defn`, and never emits an ellipsis/fake body token". Docstring line 1 is
soft-clipped to 78 chars with an explicit ` [clipped]` marker rather than `…`,
because "models copied that glyph into executable forms"
(`namespaces.cljc:1026-1037`).

Two further gates:

- **Only callable rows appear.** `callable-fn-row?` (`namespaces.cljc:1219-1228`)
  requires `fn-var?` ∧ public ∧ nonblank `spec` ∧ blank `schema-error` ∧ a
  parseable arity list. An uncontracted public fn is invisible in a card.
- **`:refer` narrows the card.** `required-ns-selections`
  (`namespaces.cljc:169-201`) reads the persisted require edges: a real
  `:refer` selects exactly those symbols; `:as`/bare/`:refer :all` select the
  whole public surface; `:as-alias` contributes nothing. When refers narrow the
  card, the ns's own schema records are **suppressed entirely**
  (`namespaces.cljc:1268` — `schemas (if (nil? refers) all-schemas [])`).

The whole card body is reader-commented (`ctx/quote-lines`,
`namespaces.cljc:1289-1290`) so echoing it cannot enqueue evals. The stated
size claim is 3–5× smaller than full (`namespaces.cljc:1007-1024`).

## 3. THE SCHEMA LOOKUP — exact mechanism

This is the part with no fresh equivalent. It has three layers.

### 3a. Detection — Malli's own RefSchema walk, against an isolated registry

`ctx.cljc:1208-1243`. Refs are **not** regex-scraped and **not** resolved
through Malli's live process registry. A composite registry of Malli's
built-ins plus an inert fallback that maps every qualified keyword to `:any`
is handed to `m/schema`, then `m/walk` reports `m/-ref-schema?` nodes:

```clojure
(def ^:private schema-ref-registry
  (mr/composite-registry
    (mr/fast-registry (m/default-schemas))
    (reify mr/Registry
      (-schema [_ type] (when (qualified-keyword? type) :any))
      (-schemas [_] {}))))

(defn- schema-form-refs [form]
  (let [acc (atom #{})]
    (try
      (m/walk (m/schema form {:registry schema-ref-registry})
              (fn [sch _ _ _]
                (when (m/-ref-schema? sch)
                  (let [r (m/-ref sch)]
                    (when (qualified-keyword? r) (swap! acc conj r))))
                sch))
      (catch #?(:clj Throwable :cljs :default) _ nil))
    @acc))
```

The reasoning is recorded at `ctx.cljc:1192-1199`: Malli already knows which
*positions* in a form are schemas rather than `:catn` labels or `:enum` values,
so use it for direct detection only — but do the transitive expansion ourselves
over database strings, because Malli's native `::m/walk-refs` would resolve
through live registry state and break "pure function of the database value
(byte-identical train/serve)". Errors-as-values throughout: an unbuildable form
yields `#{}`.

### 3b. Seeds — from function `:malli/schema` contracts, not from call sites

The seed set is the **persisted `:seon.fn/spec` strings** of the namespace's
functions, plus (full path only, and compact only when unrefined) the ns's own
`:seon.schema/form` strings. `ctx.cljc:1482-1489`:

```clojure
seed-specs  (into [] (keep :seon.fn/spec) fns)
own-keys    (into #{} (map :seon.schema/key) schemas)
ref-blk     (referenced-schema-rows-block
              {::seed-specs seed-specs
               ::own-keys own-keys
               ::schema-rows (into schema-rows schemas)})
```

So "all schemas used in all functions" literally means: the qualified keywords
appearing in schema position inside every function's Malli contract. Nothing
walks bodies.

### 3c. Closure — transitive, cycle-safe, capped, own-keys traversed but not emitted

`schema-ref-closure` (`ctx.cljc:1283-1314`) is a `loop` over a sorted queue
with a `seen` set. Each key's definition is normalized
(`normalize-schema-form`, `ctx.cljc:1266-1276`, which unwraps a persisted
`(register! ::k <form>)` call to `<form>`), its own children are extracted with
the same `schema-form-refs`, and appended. The subtle rule
(`ctx.cljc:1284-1287`): `own-keys` are **traversed** so their cross-namespace
children still surface, but never **emitted**, because the namespace already
shows them in its own source/register block.

Output is one block of `(register! :the/key <form>)` lines, sorted by key
(`referenced-schema-block*`, `ctx.cljc:1326-1341`), with an explicit honesty
line when capped:

```clojure
(str "; … " referenced-schema-cap "+ referenced schemas — capped; more reachable via the db")
```

### 3d. Where the schemas render

**One section per namespace block, not inline per function.** In a full block
it sits between the source and the ⚠ notes (`ctx.cljc:1451`). In a compact card
it sits between the ns's own `schema … = …` records and the `fn …` lines
(`namespaces.cljc:1283-1287`). Each namespace computes its own closure; there
is no cluster-wide schema appendix.

### 3e. The database side of the lookup

`acquire-one-schema-closure!` (`namespaces.cljc:615-672`) is the frontier
fetcher. It is a **global key lookup**, not a namespace join:

```clojure
(def ^:private schema-frontier-query
  '[:find ?requested ?form
    :in $ [?requested ...]
    :where
    [?schema :seon.schema/key ?requested]
    [?schema :seon.schema/form ?form]])
```

Batched at `ctx/referenced-schema-cap` keys per round, memoized across
namespaces in one render via `::schema-rows-by-key` and `::missing-schema-keys`
(so a shared schema is fetched once for the whole block), and aborted with a
real error value when the aggregate exceeds the cap
(`namespaces.cljc:652-660`). Note the comment at `ctx.cljc:1186-1188`: the
closure reaches **freely across namespaces** because schemas are one global
registry and "which file a `register!` sits in is incidental" — the only
namespace-scoped read is the ns's *own* schema block, via
`:avet [:seon.schema/ns namespace-id]` (`namespaces.cljc:444-448`).

## 4. How depth and conciseness were decided

**Not a distance parameter.** Exactly two hardcoded tiers plus per-agent
presence-set overrides, stored as datoms on one `:namespaces` block entity
(`namespaces.cljc:47-68`):

| Dial | Type | Default | Effect |
|---|---|---|---|
| `::current-full?` | boolean | `true` | the current ns renders full |
| `::current-tests?` | boolean | `true` | its indexed test source rides along |
| `::full-source` | vector of ns symbols | `[]` | presence promotes that ns to full |
| `::compact` | vector of ns symbols | `[]` | presence keeps that ns's card |
| `::with-tests` | vector of ns symbols | `[]` | presence appends its test source |

Attribute presence *is* the config (decision 22/23, `namespaces.cljc:36-44`);
compact is the absence. Values are symbols rather than refs deliberately, so a
not-yet-indexed `my.agent.*` ns can be configured and simply no-ops.
`resolve-cfg` (`namespaces.cljc:711-719`) uses `some?` not truthiness so an
explicit `false` or empty set beats the default. Agents change these live via
`my.ns/full!` / `my.ns/compact!` (taught in the header,
`namespaces.cljc:739-758`).

Selection set = `requires(current) ∪ ::compact ∪ ::full-source ∪ {current}`,
filtered by `selected-ns?` (`namespaces.cljc:94-104`: `*-test` always out,
`*.internal` out unless its exact symbol is pinned in `::full-source`).

**The caps and budgets that actually existed** (all constants, no token
budget):

| Constant | Value | File:line |
|---|---:|---|
| `referenced-schema-cap` | 40 (comment: measured live max closure ≈26) | `ctx.cljc:1202-1206` |
| `schema-row-aggregate-cap` | 2048 keys per block render | `namespaces.cljc:604` |
| docstring line-1 `soft-clip` | 78 chars, ` [clipped]` marker | `namespaces.cljc:1026-1037,1244` |
| `schema-error` / test-failure note clip | 120 chars | `ctx.cljc:1442,1448` |
| `source-chunk-chars` | 12,000 (paged source read) | `namespaces.cljc:285` |
| acquisition page sizes | 32 index / 16 pull | `namespaces.cljc:282-283` |
| per-member result weight | 60,000 units | `namespaces.cljc:284` |
| card size claim | "3–5× smaller than full", "budget of ~11 full nses" | `namespaces.cljc:1007-1024` |

**Full source was never truncated.** There was no token budget and no
progressive inclusion; the only bounds were the schema closure cap and
per-string clips. Ordering was a prompt-cache decision, not a budget one
(`namespaces.cljc:964-967, 993-999`): stable `seon.*` required namespaces
render first as a name-sorted **cache prefix**, then the churning body
(`my.*` + current ns) ordered by the tx of the `:seon.ns/name` datom so the
current ns sits nearest the tail.

## 5. Requires → rendered namespaces: how the old system connected them

Require edges were **component entities holding a plain symbol, not refs**.
`src-old/seon/ns/source.cljc:19-32,51` declares
`:seon.ns.require/target :symbol` inside a `{:seon.db/entity true}` map, and
`:seon.ns/require-edges` as a set of those component entities;
`require-edges-from-source` (`:61-113`) parses them out of the `(ns …)` form
at index time. At render time the current namespace's edges were pulled as
components (`namespaces.cljc:540-547`), reduced to a target→selection map by
`required-ns-selections` (`:169-201`), and then the *symbols* were resolved to
namespace entities by a **separate name lookup**: `acquire-namespace-identities!`
(`:381-420`) pages the `:aevt` index of `:seon.ns/name` and keeps the datoms
whose value is in the selected symbol set, and unmatched names degrade to
`{:seon.ns/name nm}` placeholders that render as `; (not in db — not indexed)`
(`:478`, `:1255`). So: symbols in the database, name-lookup at render time,
graceful degradation for external/unindexed targets. The fresh system's planned
conversion of `:seon.ns/requires` from symbols to refs with name-only rows for
external namespaces is exactly this same graceful-degradation contract, moved
from a render-time scan into the graph — a strict improvement, since the old
`:aevt` full scan per render is what the paging/frame machinery existed to
survive.

## 6. What worked, what didn't

**Worked** (evidenced by the code's own settled comments and the adjacent
quarry note `namespace-renderer-quarry-2026-07-29.md:17-21`):

- Stored source as the authoritative full body, with no duplicated member
  listing (GI-1). Simple, honest, no synthesis.
- Never presenting partial data as executable: no synthetic `defn`, no
  ellipsis-as-body, reader-commented cards, `#object[…]` scrubbed to
  `<runtime value omitted>` (`namespaces.cljc:1039-1059`).
- The referenced-schema closure itself. It answers "what IS this argument"
  without the agent making a second call, is a pure function of the database
  value, is cycle-safe, and states its own cap out loud.
- Deriving conciseness from real `:refer` edges rather than a heuristic.
- Cache-prefix ordering — a genuinely load-bearing prompt-economics decision
  the fresh renderer has not yet reproduced.
- The never-omit empty-workspace stub.

**Didn't work:**

- **Unbounded response weight.** `live-namespaces-render-defect-2026-07-22.md`
  documents the block asking for up to 3,735,552 weight units against a
  negotiated 64 KiB frame, then swallowing the resulting error into
  `{:seon.error/data nil}`. Root cause: one deep selector pulling source +
  every reverse fn (with source) + every reverse schema + every reverse test.
- **String-sniffing for emptiness** (`namespaces.cljc:815-817`) — the render
  decides structure by searching its own output text.
- **The async acquisition ladder.** `acquire-one-namespace-row!`
  (`:422-465`) is seven nested `if-let [error (database-error …)]` levels; the
  file is 1,292 lines for one block.
- **Stored density dials.** Five presence-set attributes per agent, plus an
  assignment overlay (`effective-selections`, `:487-501`) that mutates them —
  config where a distance parameter belongs.
- **Two renderers for one thing.** `render-one-ns-ai` and
  `render-one-ns-compact-row` diverge structurally; only the schema closure was
  genuinely shared.
- **The compact/full split was itself the wrong axis.** It is a hand-built
  two-tier hierarchy with a forward declaration at `:28` because the halves
  could not be ordered.

## 7. Adopt / drop for `src/seon/render/ns.clj`

| Old mechanism | Verdict | Why |
|---|---|---|
| Schema closure seeded from `:seon.fn/spec` | **ADOPT** | The single highest-value idea here and entirely absent from the fresh renderer. |
| Malli `m/walk` + isolated placeholder registry for ref detection | **ADOPT verbatim** | Correctly distinguishes schema positions from `:catn` labels; keeps the render a pure function of the database value. |
| `own-keys` traversed but not emitted | **ADOPT** | Prevents the duplication that would otherwise double a d2 render. |
| Explicit `; … N+ capped` line | **ADOPT** | Honest omission, matching the fresh `omission-comment` idiom. |
| One schema section per namespace, not per function | **ADOPT** | Dedupes shared schemas; the alternative is quadratic. |
| Per-render memo of fetched schema keys | **ADOPT** | Necessary when the walk renders several namespaces in one pass. |
| Cache-prefix ordering (stable first, churn last) | **ADOPT** (walk-level, not renderer-level) | Already ruled in README:700-706; still not built. |
| Never synthesize `defn`; never emit `…` as a body | **ADOPT as an invariant** | Fresh `signature-form` already emits `nil` bodies — keep it that way. |
| `soft-clip` with explicit ` [clipped]` marker | **ADOPT if clipping is needed** | The ellipsis-copied-into-code failure is real. |
| Two hardcoded tiers (`full?` / compact) | **DROP** | Replaced by d0/d1/d2 distance, which is strictly more general. |
| Five stored density presence-sets + `resolve-cfg` | **DROP** | Distance is a request parameter, not stored agent config. |
| `callable-fn-row?` gate (contract-complete only) | **DROP** | Hides uncontracted public functions; fresh d1 correctly shows all public rows. |
| `:refer`-narrowed cards suppressing schemas | **DROP** | An agent that refers one symbol still needs that symbol's argument shapes. |
| `callable-contract` prose rewriting of Malli grammar | **DROP** | Fresh `signature-form` emits real `{:malli/schema …}` — valid Clojure beats invented prose. |
| String-sniffing rendered text for emptiness | **DROP** | Fresh code decides from `(empty? functions)`. |
| Async acquisition ladder, paged source chunks, frame budgets | **DROP** | JVM `d/pull`/`d/q` over an immutable value; the whole class is gone. |
| Namespace catalog overlay | **DROP** | A different block's concern. |
| Boot-storage `full-source-ns?` / `my.*` name rules | **DROP** | Hand list; the fresh indexer stores every first-party source. |
| Workspace stub reconstructing the `(ns …)` form | **SUPERSEDED** | Fresh `ns-form` + `empty-comment` + `agent/owner-of` already covers it, more honestly. |

## 8. Concrete deltas against what W2a landed

Verified against `src/seon/render/ns.clj` (308 lines) and
`w2a-namespace-renderer-notes-2026-07-31.md`.

1. **The schemas-used-by-functions lookup does not exist in the fresh renderer
   at any depth. Confirmed.** `src/seon/render/ns.clj` reads only
   `:seon.ns/name`, `:seon.ns/requires`, `:seon.ns/aliases`, and
   `:seon.fn/{sym,arglists,doc,private?,spec,source}` (`:20-58`). Grepping
   `seon.schema` across `src/seon/render/` returns hits in `walk.clj`,
   `block.clj`, `web.clj`, `value.cljc`, `data.clj`, `hiccup.clj` — and **none
   in `ns.clj`**. `:seon.fn/spec` is read at `:46` and `:85`, but only to
   splice the raw form into `{:malli/schema …}` when arglists are missing
   (`:89-91`); its referenced keys are never resolved.

2. **A d1 render is therefore self-referentially opaque.** It emits
   `[:=> [:cat :seon.render/unit] [:maybe :string]]` with no way to learn what
   `:seon.render/unit` is. This is precisely the gap `ctx.cljc:1180-1190`
   was written to close.

3. **The lookup is implementable today with no schema change.**
   `:seon.schema/key` is a global identity attribute
   (`src/seon/schema.cljc:733`: `[:keyword {:seon.db/identity true}]`) and
   `:seon.schema/form` is stored alongside it
   (`resources/seon/schema/program.edn:28-33`). The old frontier query is a
   direct port. Malli is on the classpath.

4. **But `:seon.schema/ns` does not exist in fresh.** Grep across
   `resources/` and `src/` returns nothing; the old system had it
   (`src-old/seon/db/program.clj:331`, `src-old/seon/db/writer.clj:1637`).
   Consequence: the old *own-schemas* block cannot be reproduced, and
   `own-keys` cannot be derived from a namespace join. Two honest options —
   derive `own-keys` from the keyword namespace (`(= (namespace k) ns-str)`,
   which is what the compact card already did at
   `src-old/…/namespaces.cljc:1257-1258`), or simply pass `own-keys #{}` and
   accept that a namespace's own schemas appear in its closure block. The
   first is a computed rule, not a hand list, and is preferable.

5. **Distance placement.** The closure belongs at d1 and deeper, appended as
   one section after the function forms in `ai-text` (`:175-188`) and as one
   `<dl>`/`<pre>` sibling in `html-view` (`:232-259`). At d0 (name only) it
   must not appear.

6. **Budget interaction is a genuine new design question the old system never
   faced.** `budgeted-ai` (`:200-214`) grows the included-function count one at
   a time against `tokens/estimate`. The schema block's size depends on which
   functions are included, so it must be recomputed inside that loop (correct,
   O(n²) on estimate calls) or computed once for the full set and counted as
   fixed overhead (cheaper, but can push the render over budget). Recommend:
   fold the closure into the same incremental loop and let `omission-comment`
   report both omitted definitions and capped schemas.

7. **`:seon.fn/calls` is unused by the renderer.** The fresh graph has a real
   `[:set :seon.db/ref]` call edge (`program.edn:11`) the old system lacked.
   Worth noting as a *separate* future lens (call-graph distance), not as part
   of the schema closure — the schema closure is contract-derived, and mixing
   the two would recreate the old two-axis confusion.

8. **Cache-prefix ordering remains unbuilt** in both the renderer and the walk,
   despite being ruled (README:700-706) and demonstrably valuable in the
   quarry. Not `ns.clj`'s job; flag for the walk.

9. **W2a's own pending edge is unrelated and still stands:** the name-only
   family route is gated on W1 making `:seon.ns/source` optional on
   `:seon.ns/ns`.

## Files read

- `src-old/seon/agent/ctx/namespaces.cljc` (whole)
- `src-old/seon/agent/ctx.cljc:1180-1500`
- `src-old/seon/ns/source.cljc:1-120`
- `src/seon/render/ns.clj` (whole)
- `src/seon/schema.cljc:733`; `src/seon/schema/edn.clj:350-406`
- `resources/seon/schema/program.edn`
- `docs/prds/sci-execution-runtime/research/w2a-namespace-renderer-notes-2026-07-31.md`
- `docs/prds/sci-execution-runtime/research/namespace-renderer-quarry-2026-07-29.md`
- `docs/prds/sci-execution-runtime/research/live-namespaces-render-defect-2026-07-22.md`
- `docs/prds/sci-execution-runtime/research/context-walk-synthesis-2026-07-31.md`
