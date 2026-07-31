---
type: research
status: active
tags: [research, datahike, render, caching]
---

# Render invalidation — adversarial falsification of the draft spec

Adversarial lane against `plan/context-render-data-model-spec.md` §3 (block
contract) and §2.4 (derived edges), under ruling 2026-07-31 #2(1). Every
verdict below is a probe result, not a reading. Four breaks are real; three of
them are in the spec's own words, one is a live defect in `seon.render.walk`
that has nothing to do with invalidation.

## Dependency ledger

- Datahike: `reference-code/datahike` submodule `9b3be9d5`
  (`0.8.1729-98-g9b3be9d5`), Seon's own fork. Read: `query.cljc:2568-2589`
  (`advance-query-cache-context`), `:2906-2944` (dependency-plan reduction),
  `:2963-2975` (`source-context-unchanged?`), `writer.cljc:225-236`,
  `writing.cljc:577-601` (`cache-revision-attributes`, `complete-db-update`),
  `pull_api.cljc:18-69`, `versioning.cljc:69-100`, `db/utils.cljc` (pull
  attribute admission).
- Seon: `src/seon/render.clj:135-222` (resolution chain),
  `src/seon/render/walk.clj:203-235,285,307,361`, `src/seon/render/block.clj:239,511`,
  `src/seon/render/agent.clj:220,340`, `src/seon/render/web.clj:818`,
  `src/seon/cluster/wake.cljc:78-93,203-228`, `resources/seon/schema/*.edn`,
  `src/seon/schema/datahike.cljc` (the bridge).
- Predecessors re-verified, not cited on faith:
  `render-invalidation-caching-2026-07-31.md`,
  `render-scheduling-design-2026-07-31.md` F4,
  `seondb-facade-quarry-2026-07-29.md`.
- Probes (committed, reproducible, `clojure -M:dev <path>`, Java 26.0.1,
  2026-07-31): `tmp/render-invalidation/falsify_probe.clj`,
  `family_pull_probe.clj`, `union_selector_probe.clj`, `reverse_cost_probe.clj`,
  `selector_sweep_probe.clj`. No cluster was needed — every claim is a
  property of a database value plus the schema population, so a load-only JVM
  is the honest surface and a scratch cluster would have added nothing but a
  port.

## B1 — The staleness formula in §3 is false-fresh on every schema change

**Spec text:** `∃ a ∈ deps : revisions[a] < current-revision[a]` — O(deps),
"conservative-revision fail-closed". The parenthetical is not in the formula.

**Probe** (`union_selector_probe.clj` §3): capture `{:probe/name rev}` at t1,
commit a pure schema transaction, evaluate the formula verbatim at t2.

```
spec formula says stale after SCHEMA tx?           false
conservative-revision actually moved?              true
=> FALSE FRESH unless conservative is in the check true
```

`advance-query-cache-context` (`query.cljc:2578-2585`) is explicit: a commit
touching any schema attribute bumps `:datahike.cache/conservative-revision`
and leaves `:datahike.cache/attribute-revisions` **untouched**
(`falsify_probe.clj` D: "schema tx left attribute-revisions untouched? true").
A merge commit and an empty-`tx-data` internal operation do the same. So the
formula as written misses exactly the class ruling #4(2) names as the point of
the system — "all agents are instantly up to date with new schemas, functions,
tools, capabilities."

**VERDICT: BREAK.** **Fix:** the check is two terms, not one, and both are
equality, not order:

```clojure
(or (not= captured-conservative (:datahike.cache/conservative-revision cc))
    (some (fn [a] (not= (get captured a) (get current a))) deps))
```

## B2 — Revisions are UUIDs; `<` is not defined on them

**Probe** (`falsify_probe.clj` D):

```
a revision value      #uuid "6a6cb2e2-242a-5468-bb5d-4a850caa267e"
revision value type   java.util.UUID
```

A revision is the commit ID, not a counter (`query.cljc:2587-2589`). `<` would
throw; even a lexicographic comparison would be wrong, because a branch fork
or a restart can produce a commit ID that does not sort after its predecessor.

**VERDICT: BREAK (spec text).** **Fix:** `not=`, and rename the field —
"revision counters" in ruling #2(1) and §3 is a lying name. They are **commit
IDs per attribute**; Datahike calls the map **attribute revisions** and never
calls them counters.

## B3 — Renderer resolution is not a database read at all: the killer false-fresh

`seon.render/render` resolves a projection through
`value-declaration → namespace-declaration → schema-declaration → floor`
(`src/seon/render.clj:166-171`). `namespace-declaration` calls
`requiring-resolve` on a **Var** (`:143`); `schema-declaration` calls
`schema/matching-shapes`, which reads the **process-local activated Malli
projection** (`src/seon/schema.cljc:2457-2465`). Neither touches the database.
The spec's block contract derives `deps` entirely from the facade's
dependency plans over data reads, so the resolution inputs are invisible to it.

**Probe** (`family_pull_probe.clj` §6): define `tmp.render-probe/render-ai`,
render, redefine it, render again — with **zero transactions**:

```
render before redefinition                 "v1:x"
render after redefinition                  "v2:x"
BYTES CHANGED with zero datoms committed?  true
resolution reads the db?                   false
```

Three live paths hit this, in ascending nastiness:

1. **Hot reload of a first-party renderer.** Every REPL redefinition silently
   serves stale bytes from the production cache (§3: cluster-global, keyed by
   block identity + basis) until an unrelated commit happens to move a
   dependency. `render.clj:202-203` deliberately invokes the Var so
   "re-evaluating a projection's defn must change the next render" — the
   invalidation design cancels that guarantee.
2. **An agent publishing a renderer.** Publication commits `:seon.fn/*` facts,
   which are *user* attributes: they move `:seon.fn/sym`'s revision but bump
   **no** conservative revision. A block whose deps are `#{:seon.cluster.message/*}`
   never notices that its renderer changed.
3. **An agent registering a schema whose Datahike attributes already exist.**
   Only `:seon.schema/*` datoms commit, no Datahike schema attribute moves, so
   again no conservative bump — yet `matching-shapes` now returns a different
   winner and §2 resolution picks a different renderer.

**VERDICT: BREAK, and it is the one that matters.** The spec's block identity
`(root-entity, renderer-var, distance, projection)` names the renderer Var but
the *cache key* is that identity plus `basis`, so a redefinition of the same
Var is indistinguishable.

**Implied fix (recommended, cheapest sound option):** the block's freshness
check gains a **third scalar** beside the two revision terms — a process-local
**code revision**, a value bumped whenever anything resolution can consult
changes: a Var interned/redefined in a governing namespace, or the activated
schema projection replaced. `seon.schema` already replaces its projection
atomically, and `seon.instrument/apply!` already runs on the reload path, so
there is one obvious owner for the bump and no new mechanism. Cost: one
`not=` per block. It is the same shape as `conservative-revision` — a
fail-closed scalar for "something I cannot enumerate moved" — which is the
argument for it: the design already accepts exactly this construct.

The alternative — folding `:seon.fn/sym` and `:seon.schema/key` into every
block's `deps` — is unsound for case 1 (a REPL redefinition commits nothing at
all) and over-wakes badly for cases 2–3 (every corpus commit invalidates every
block). Do not take it.

## B4 — The walk's reverse-ref read is `:all`, and it is also incorrect

Two independent findings from the same line, `src/seon/render/walk.clj:221`:
`(d/q '[:find ?source ?attribute :in $ ?target :where [?source ?attribute ?target]] db eid)`.

### B4a — dependency plan is `:all` (confirms scheduling F4 at the walk root)

```
walk.clj:221 reverse-refs query deps        :all
walk.clj:307/361 d/pull '[*] deps           :all
```

An unbound attribute position widens to `:all` (`query.cljc:2549-2566`), and
`:all` is the design's off switch: `source-context-unchanged?` refuses to
inherit a `:all` dependency set (`query.cljc:2963-2975`), and the spec's own
check has no `a ∈ :all`. Since `refs` (`walk.clj:289-315`) is called at
**every node of every walk**, and both its forward (`d/pull '[*]`) and reverse
reads are `:all`, **every walk-derived block is stale on every commit** and
the selectivity claim is zero at the root — not merely degraded at the four
F4 sites.

**VERDICT: BREAK (already known as F4, now measured at the walk's own root).**
Narrowing works and is derivable — see §N below.

### B4b — the generic reverse query matches non-ref attributes

`[?source ?attribute ?target]` with `?target` bound to an entity id also
matches any `:db.type/long` datom whose **value** equals that number.

**Probe** (`selector_sweep_probe.clj` §1): one `:probe/coincidence` long datom
whose value is the agent's eid, alongside 200 real `:seon.cluster.message/to`
refs.

```
agent eid                              157
generic reverse hit count              201
attributes returned                    #{:probe/coincidence :seon.cluster.message/to}
matched the NON-ref long attribute?    true
```

The walk then treats it as a connection and recurses into an unrelated entity.
Entity ids are small dense longs and Seon stores plenty of longs
(`:seon.render.block/*`, token counts, sizes, `:seon.cluster.agent/eid` — which
literally *is* an eid stored as a long), so this is not exotic. Filed as
`docs/seon/issues/render-walk-reverse-refs-matches-non-ref-longs.md`.

**VERDICT: BREAK, live defect, independent of invalidation.** The narrowing
fix below repairs it as a side effect, because a reverse pull over named ref
attributes cannot match a long.

## N — The narrowing fix: derive selectors from the schema EDN, mechanically

Everything here is computed from the population, never a hand list.

**Derivation rule (implementable as written).** Let `forms` be
`(seon.schema/snapshot)`.

1. **Entity family** — a registered form
   `[:map {:seon.db/entity true …} [attr schema] …]`. There are **21** in the
   current population (3–32 attributes each; `family_pull_probe.clj` §1).
2. **Family selector** — `(into [:db/id] (child attribute keys))`. Concrete,
   so its dependency plan is exactly those attributes plus `:db/id`
   (`:seon.cluster.message/message` → 8 deps, verified).
3. **Identity probe selector** — the FIRST child attribute of each family (its
   identity attribute by construction), 21 attributes + `:db/id`. This is how
   the walk learns which family an entity belongs to without a wildcard.
4. **Ref universe** — every attribute whose bridge declaration is
   `:db/valueType :db.type/ref`. **31** in the population; **26** appear inside
   entity families.
5. **Reverse selector** — `{ns/_attr [:db/id]}` for each ref attribute. One
   pull, deps = 27 concrete attributes, never `:all`.
6. **Install intersection is mandatory.** A pull naming an uninstalled
   attribute **throws** (`union_selector_probe.clj` §2:
   `Bad entity attribute :seon.cluster.run/id … not defined in current schema`).
   Every derived selector must be intersected with `(:schema db)` at the
   render's own basis. That intersection is itself schema-derived, and schema
   commits bump the conservative revision, so the intersection can never go
   stale silently.

**Coverage.** 323 attributes bridge to Datahike declarations; **155** are
inside some entity family, **168** are not (`union_selector_probe.clj` §1:
`:my.message/*`, `:my.run/*`, `:seon.ai/*` dials, …). A family-lens walk
therefore renders 155 of them and *cannot* render the other 168 — which is
consistent (unpulled ⇒ unrendered ⇒ not a dependency) but is a **P1 membership
gap, not an invalidation gap**: today's `d/pull '[*]` shows those attributes
and the narrowed walk will stop showing them. That change is invisible unless
P1 asserts it. Either the family maps grow to cover every stored attribute of
their entities, or P1 must accept family-scoped membership explicitly.

## C — Cost, measured

**Staleness sweep** (`falsify_probe.clj` §F) — 100 blocks, 10–30 deps each,
1702 deps total, both terms of the corrected B1 check, per commit:

```
staleness sweep: 100 blocks   66.31 µs/commit
```

Free. At 1000 blocks it is still sub-millisecond. The O(deps) check is not the
problem and needs no optimization.

**Dependency capture** (same probe, result cache disabled for fairness):

```
d/q plain                              210.57 µs
q-with-evidence                        320.85 µs   (+52%, +110 µs/query)
query-attribute-dependencies (pure)      3.75 µs
```

**VERDICT on the §3 "facade's evidence-capture pass":** it costs +52% per
query for information the **pure** function already gives at 3.75 µs.
`query-attribute-dependencies` takes the query form with no db and no inputs
(`query.cljc:2935-2944`), and `pull-dependency-plan` likewise. **Fix: capture
the plan from the READ FORM, not from an execution evidence pass.** This is
also the sounder direction — the report already argues that only a
form-derived set covers absence — and it removes a reason for the facade to
wrap every read. Keep `q-with-evidence` for diagnosis, not for the render path.

**Narrowing is not free** (`selector_sweep_probe.clj` §2, 200-message
neighbourhood, per pull):

| selector | µs |
|---|---:|
| `'[*]` (any size) | 6.95 |
| 1 named attr | 13.90 |
| 3 | 13.42 |
| 8 | 19.37 |
| 21 | 37.22 |
| 50 | 82.26 |
| 156 (family union) | 228.90 |

Pull pays ≈1.45 µs per **named** attribute whether present or absent, while
`[*]` walks only the datoms that exist. The naive "replace `[*]` with the
union selector" costs **33×**. The two-step is the answer:

```
two-step probe(21) + family(4)        47.86 µs      deps = 24 concrete attrs
d/pull '[*]                            6.95 µs      deps = :all
```

**7× per node for real selectivity.** Reverse the same way: the generic query
is 11.33 µs/`:all`, the derived 26-entry reverse pull is 270.66 µs/27 deps
(`reverse_cost_probe.clj`) — 24×, and the reverse case is worse because each
named reverse attribute is its own index probe.

**Recommendation, with evidence:** adopt the **two-step forward** read
(identity probe → family selector) — 7× on a microsecond-scale operation,
bounded, and it is the only shape that gives the walk a family to resolve a
lens against. For the **reverse** read, do **not** adopt the 26-entry reverse
pull as the default; instead keep one narrow reverse pull per **ref attribute
the walk actually intends to follow at this node** (an agent lens wants
`:seon.cluster.message/_to`, `:seon.error/_agent`, `:seon.cluster.run/_agent` —
three, ~35 µs), derived from the ref universe filtered by the family being
rendered. The full 26-entry pull is the fallback for the structural floor only.
The `datoms`-range alternative from `seondb-facade-quarry-2026-07-29.md` buys
100% precision but obliges a `db-before` read per candidate on the writer
thread; at 66 µs for a whole 100-block sweep there is no measured reason to pay
that, and `wake.cljc:22-28` forbids the work anyway.

## R — Revision semantics, exactly (attack 3)

All from `falsify_probe.clj` §§B, D, E.

| event | attribute revisions | conservative revision |
|---|---|---|
| ordinary assert | advance for each asserted user attribute | unchanged |
| **retraction only** | **advance** (`RETRACTION advanced :probe/name revision? true`) | unchanged |
| `:db.fn/retractEntity` | advance for every retracted attribute | unchanged |
| **tx-meta datoms** | **advance** — `:probe/trigger` entered the map even with `:tx-data []` | unchanged |
| `:db/txInstant` | **never** — explicitly `disj`'d (`query.cljc:2575`) | — |
| schema transaction | unchanged | **advances** |
| merge commit | unchanged | advances (`writer.cljc:235`) |
| empty `tx-data` internal op | unchanged | advances (`writing.cljc:584`, nil ⇒ unknowable) |
| no-op re-assert of an identical value | **unchanged** — but `:max-tx` still moves | unchanged |
| `d/with` (speculative) | `:cache-context` is **nil** | nil |
| `d/as-of` / `d/history` | **absent** | absent |

Three consequences the spec does not state:

1. **`:db/txInstant` is a legal dependency-plan attribute that can never go
   stale.** `walk.clj:285` already pulls it
   (`apparatus?` deps = `#{:db/txInstant :seon.render.block/name}`). Any
   renderer that displays a transaction time through `:db/txInstant` is
   permanently fresh. Cheap fix: treat `:db/txInstant ∈ deps` as "always
   stale", or forbid it in a render selector.
2. **A derived database value has no revisions at all.** On an `as-of`/`history`
   value the corrected check reports *stale* (captured non-nil vs current nil),
   which is fail-closed and fine — but two `as-of` values compare *equal*
   (both nil), so a block rendered from one as-of point and checked against a
   different one is **fresh forever**. State the rule: **the freshness check is
   defined only on a committed database value**; anything else is
   unconditionally stale. `d/with` (`:cache-context nil`) is the same case.
3. **Retraction-only transactions are covered**, so the "can a retraction
   produce false-fresh" attack fails — with one exception, the no-op re-assert:
   it moves `:max-tx` without moving any revision. That is *correct* for
   staleness (bytes did not change) but it breaks §3's other claim: `basis` as
   the ordering key. A block ordered by "max tx over its read set" must derive
   that tx from the datoms it read (`d/datoms`/history), not from the database
   value's `:max-tx`, or every no-op transaction reorders the prompt. §3 does
   not say which, and a pull returns no tx — **the `basis` field is not
   derivable from the reads §3 prescribes**. Flagged for the owner; it is an
   ordering question, not an invalidation one.

## D — Derived edges (§2.4), verdicts

| edge | probe | verdict |
|---|---|---|
| ns → required ns rows | `[:find ?e :in $ ?n :where [?e :probe/nsname ?n]]` → deps `#{:probe/nsname}`; resolving a name **before** its row exists returns nil, and committing the row **advances** that attribute's revision (`falsify_probe.clj` §C) | **SOUND.** The absence→presence transition is covered exactly as the predecessor report argued. |
| agent → trigger message (tx-meta) | a transaction carrying `:tx-meta {:probe/trigger msg}` put `:probe/trigger` into attribute revisions and moved it; a query over transaction entities reduces to `#{:probe/id :probe/trigger}` | **SOUND.** tx-meta datoms are ordinary datoms in `tx-data` (`writing.cljc:584` → `modified-attributes`), so they are first-class dependencies. This was the attack most likely to break; it holds. |
| agent → asked-for runs (tx-meta) | same mechanism | **SOUND**, same evidence. |

The one caveat: a query over transaction entities that also reads
`:db/txInstant` (ordering the triggers by time is the obvious way to write it)
inherits consequence R1 above and is false-fresh on that term.

## Aggregation and count reads (attack 1c)

`[:find (count ?e) . :where [?e :probe/name _]]` → deps `#{:probe/name}`;
count went 2 → 1 across a retraction and the revision advanced
(`falsify_probe.clj` §D). **SOUND.** Aggregates are not a false-fresh channel;
the aggregation happens above the clause whose attribute is registered.

## Summary of required spec edits

1. §3 staleness: two terms, `not=` not `<`, conservative revision named in the
   formula (B1, B2).
2. §3: add the **code revision** scalar; block identity's `renderer-var` is not
   enough (B3).
3. §3: define the check only on a **committed** database value; derived values
   are unconditionally stale (R2).
4. §3: `deps` come from the **read form** (`query-attribute-dependencies` /
   `pull-dependency-plan`, 3.75 µs), not from an evidence-capture pass
   (+110 µs/query). The facade's dual-use evidence pass is diagnosis.
5. §3: `:db/txInstant ∈ deps` ⇒ always stale (R1).
6. §3: state the selector derivation rule (§N 1–6), including the **mandatory
   intersection with `(:schema db)`** — a pull naming an uninstalled attribute
   throws.
7. §3 `basis`: name how the last-change transaction is read; a pull cannot
   produce it, and `:max-tx` moves on no-op transactions (R3). Owner call.
8. §2.4: the three derived edges are sound as specified; say that tx-meta
   datoms are ordinary dependencies so nobody re-litigates it.
9. §6/P1: family-scoped membership is narrower than today's `[*]` walk —
   168 of 323 installable attributes fall outside every entity family. Either
   widen the family maps or state the narrowing in P1.

## Skill drift found (reported, not edited)

1. `.claude/skills/datahike/SKILL.md`, "Common errors and gotchas": says "an
   uninstalled attr also throws on read — a `d/datoms` scan or explicit pull of
   an unknown attribute throws rather than returning empty. Gate a raw scan on
   `(contains? (:schema @connection) attr)`." That is **correct and verified**
   here (`union_selector_probe.clj` §2) — recording it as calibration, since it
   is the line that saves the whole selector-derivation design.
2. Same skill, "Temporal, listeners, triggers": still says `d/listen!` where
   the public API is `d/listen`. Already reported by
   `render-invalidation-caching-2026-07-31.md` §9(1) and still unfixed.
3. Neither `datahike` nor `data-oriented-clojure` mentions that a **speculative
   `d/with` database value carries `:cache-context nil`** and that `as-of` /
   `history` values carry no attribute revisions. Any lane implementing the
   invalidation contract will get this wrong; it belongs beside the existing
   "`as-of` reports its ORIGIN db's basis-t" gotcha, which is the same family
   of trap.
4. `reference-code/datahike` docstring drift previously reported at
   `query-attribute-dependencies` (`query.cljc:2938-2942`) is confirmed again:
   a concrete pull pattern narrows correctly; only wildcard/dynamic widens.
