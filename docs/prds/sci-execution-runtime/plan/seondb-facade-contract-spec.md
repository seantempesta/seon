---
type: prd
status: active
tags: [prd, database, render]
---

# seon.db read facade — contract spec (for owner review)

Authored by the orchestrator from the full-surface quarry
(`../research/seondb-facade-quarry-2026-07-29.md`, rulings 22a/24).
Port basis: **Generation G** (`f6d843ee7`), adapted to the fresh tree and
Datahike internals (our fork; internal calls sanctioned). Nothing here is
invented — every mechanism names its mined source in the quarry report.

## The surface (one namespace: `seon.db`)

| fn | shape | notes |
|---|---|---|
| `q` | `(q query & args)` / `(q db query & args)` | dual arity: db omitted → the latest database value auto-inserts (parsed-position insertion, Generation G's rule) |
| `pull` | `(pull pattern eid)` / `(pull db pattern eid)` | plan-derived evidence |
| `pull-many` | `(pull-many pattern eids)` / `(pull-many db pattern eids)` | one shared plan; input-aligned results including nil |
| `entity` | `(entity eid)` / `(entity db eid)` | **eager bounded `pull '[*]`** projection — never a lazy Entity object (prototype: any seq over a lazy wrapper widened evidence to `:all`) |
| `datoms` | `(datoms index components page)` / `(datoms db …)` | bounded eager pages only; evidence = the honest index RANGE (measured: 20 wakes / 0 false vs 60/40 for `:all`) |

- All reads are pure functions of an immutable database value; the
  auto-inserted "latest" is resolved once per call at the boundary, never
  re-read mid-call.
- Errors are flat `:seon.error` values; every fn carries a complete
  `:malli/schema`; no `[:maybe]` on stored shapes, in-memory returns per
  the omission ruling.

## The capture seam (dual use, ruling 24)

One dynamic capture context, bound ONLY by a registering pass (the render
proc's derivation pass). Present → every read appends evidence
(query/pull: plan-derived attribute sets, memoized by query form;
datoms: the index range; entity: the pull plan). Absent → plain call,
zero registration, zero overhead beyond a var read. Agent code never
chooses; the pass does.

Evidence consumer: the per-agent interest index (the falsifier's adapted
reverse candidate index — `::all` + `::by-attribute` + range entries).
Dedupe: computation by Datahike's query/result cache (verified
`miss-owner → hit`, 22µs class); interest by shared attribute buckets
with per-agent reference rows; plan→attribute derivation memoized by
query form.

## Return shape (the one owner decision embedded)

Reads return the BARE result; evidence flows only through the bound
capture context. (The closed read-result wrapper the first report floated
is REJECTED in this spec: it would force every call site to unwrap, and
the pass — not the caller — owns evidence. Owner may override.)

## Custody (ruled 2026-08-01)

`seon.db` OWNS the current-connection custody: one dynamic var in
`seon.db`, bound by the passes that already hold the cluster connection
(the run loop around each evaluation, the render pass — replacing
`seon.render`'s private `ambient-database-value`). The zero-db arity
resolves `(d/db bound-connection)` once per call at the boundary.
Unbound custody is a flat `:seon.error` value, never an NPE. Rationale:
one JVM hosts many clusters, so "the" connection is contextual and the
binder is the pass that knows its cluster; one custody owner, and it is
the boundary namespace itself.

## Migration

The 27 `datahike.api` call-site files route through the facade in owner
lane groups per the quarry inventory; render family first (the interest
machinery's consumers), `seon.db` internals exempt (they ARE the
boundary). No fresh `entity`/`pull-many`/index call sites exist today —
those fns land with tests but no migration burden.

## Sealed falsifiers (test-forward; the implementing lane makes green)

1. Dual arity: `(q query)` ≡ `(q (latest) query)` byte-identical; the
   latest resolves exactly once per call.
2. Capture absent: a plain call registers nothing and allocates no
   evidence.
3. Capture present: query/pull evidence = the plan's attribute set;
   a query whose plan cannot resolve widens to `:all` (fail-open, never
   silently narrow).
4. Entity: `(entity eid)` = `(pull db '[*] eid)` exactly; no lazy object
   escapes; evidence = the pull plan.
5. Datoms: page bounded; evidence is the range; a commit INSIDE the
   range wakes, a commit outside does not (the 20/0 table reproduced as
   a regression).
6. Dedupe: two agents registering the identical query yield one plan
   derivation, one attribute bucket, two references; second read is a
   cache hit.
7. Absence-dependence: a query returning empty registers its plan
   attributes and wakes when a matching fact APPEARS (the class that
   killed read-tracing, pinned forever).
8. Errors: a malformed query returns a flat `:seon.error` value; nothing
   throws through the facade.

## Out of scope

Writes (transact stays on the store owner), the narrow-wake rollout
(unconditional wake + suppression remains until measurements demand),
and the render-proc implementation (its own contract, after the owner's
§7 review).
