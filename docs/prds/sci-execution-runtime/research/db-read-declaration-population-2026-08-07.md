---
type: research
status: complete
tags: [research, database, schema, performance]
---

# The read side: one declaration population per read operation — 2026-08-07

The third and last member of the family. The
[bare-suite hang](parallel-turns-hang-cause-2026-08-07.md) killed the
per-ATTRIBUTE instance at the Datahike WRITE seam;
[asking one question per item](declaration-population-per-item-2026-08-07.md)
killed the per-CONFIG-KEY, per-PRINT-OPTION and per-REGISTRY-KEY instances at
their callers. This one kills the per-PULLED-KEY, per-FIND-ELEMENT and
per-DATOM instances at `seon.db`'s five decode walkers — and finds that
threading alone is not enough, because one leaf predicate cannot take an
argument.

## What I read, end to end, before editing

Stated for the record, as the standing order requires:

- [db-read-decoding-resolves-declarations-per-attribute](../../../seon/issues/db-read-decoding-resolves-declarations-per-attribute.md)
  — the assignment;
- [declaration-population-per-item-2026-08-07.md](declaration-population-per-item-2026-08-07.md)
  — the write-side model (resolve once and pass; `call-with-forms` as the
  supply seam for a callee that cannot take an argument; the read-counting
  class regression), landed as `66679cf89`;
- [parallel-turns-hang-cause-2026-08-07.md](parallel-turns-hang-cause-2026-08-07.md)
  — the first family member;
- `src/seon/db.clj`, `src/seon/schema/datahike.clj`, the resolution chain in
  `src/seon/schema.clj`, `src/seon/reconcile.cljc`, `src/seon/config.clj`,
  and `test/seon/schema/declaration_population_test.clj`.

## The class, and the part threading cannot reach

`schema/declaration-population` falls through to
`seon.schema.edn/packaged-forms` when no overlay, packaged population,
projection state, or projection is supplied: 152 resource reads, ~14 ms, per
call. `seon.db`'s five recursive walkers asked
`schema.datahike/edn-encoded-attr?` — the ambient arity — once per decoded
map key (`:351`), per query find element (`:415`), per pulled key (`:524`,
`:528`), and per datom (`:785`).

Threading a projection through those walkers fixes the walkers. It does NOT
fix `edn-encoded-attr-in?` itself, and this is the finding that matters:

```
one edn-encoded-attr-in? over :seon.config.eval/time-limit-ms, live cluster
  projection merely PASSED     1,824 resource reads    76.6 ms
  projection also SUPPLIED             0 reads          0.17 ms
```

`edn-encoded-attr-in?` resolves the attribute's Datahike value type, which
reaches `schema/malli-form?` — a REGISTERED CORE PREDICATE that Malli calls
with the value and nothing else. It builds its own registry from its own
resolution and cannot be handed a population. This is exactly the fourth
instance the write-side report found only on a live cluster, met again at a
different seam. The remedy it established is the one used here: the operation
SUPPLIES, through `schema/call-with-forms`, the same value it already
resolved. Not a cache, not a second mechanism — one value made visible to a
callee that has no parameter for it.

## The repair

`src/seon/db.clj` — the read seam. Every decode walker takes the declarations
as its first argument; every public read operation creates them once.

- `read-declarations` returns a **`delay`** over `schema/declaration-projection`.
  A delay, not a plain value, because a `q` that decodes nothing is the
  commonest read in the system and must keep costing nothing: resolving
  eagerly at the entry made a no-decode `q` go from 0.08 ms to 17.6 ms, which
  would have been a worse defect than the one being fixed. The delay is
  created fresh per operation and dies with it.
- `ask-declarations` passes the projection AND supplies its forms for the one
  question, so the leaf predicates above are answered from the same value.
- `edn-encoded?` and `decode-attribute-value` are the two leaf questions; the
  five walkers call them and never touch the declarations directly.
- Entry points that create exactly one: `q-call`, `pull-call` (which `pull`,
  `pull-many` and `entity` all route through), `datoms-call`, `replay-read`'s
  three arms, and `agent-transaction-report`.

`src/seon/schema.clj` — `declaration-projection`, the one place that pairs a
population with its registry. See the defect below for why the registry had to
join it.

`src/seon/schema/datahike.clj` — `encode-transaction` and
`decode-attribute-value` now build that complete projection; the ambient
duplicates `edn-encoded-attr?` and `validate-logical-slot!` are DELETED (git
is the archive), so only the explicit-projection family remains.

Two callers whose own per-item shape the read seam then exposed:

- `seon.reconcile/plan` pulls once PER MANAGED ENTITY. One population per pull
  is correct per operation and still thousands of populations per plan, so
  `plan` supplies for its extent. This is what actually unwedged the two
  tests.
- `seon.config/effective` pulls the 65-key config row; same treatment.

## Measurement

### Load-only, deterministic

`tmp/repro/db_read_population_cost.clj`, `clojure -M:dev`, median of 5, reads
counted at the one read seam (`schema.edn/read-schema-resource`). One unbound
resolution is 152 reads / 14.3 ms, so 152 reads means exactly one population.

| Operation | Before | After |
|---|---|---|
| one unbound resolution (the floor) | 152 reads / 14.3 ms | unchanged |
| `q`, no decodable element | 0 / 0.08 ms | **0 / 0.11 ms** |
| `q` decoding one find element | 152 / 13.0 ms | 152 / 14.3 ms |
| `pull '[*]`, 2-attribute entity | (write was broken — see below) | **152 / 14.2 ms** |
| `entity` | — | 152 / 12.9 ms |
| `datoms :eavt`, 34 datoms | **5,168 / 372.5 ms** | **152 / 12.3 ms** |
| one `transact!` (write side, unchanged) | 152 / 13.2 ms | 152 / 13.2 ms |

`datoms` is the clean arithmetic statement of the class: 5,168 = 34 × 152,
one complete classpath population per datom.

### Live, on a booted cluster

Own isolated operator root `tmp/db-read-operator`, cluster `dbread`, never the
shared default. The read counter is process-wide and the cluster's own procs
read concurrently, so these numbers carry ±1 population of noise (one unbound
resolution measured 152–304 across windows). They are decisive anyway.

| Operation | Before | After |
|---|---|---|
| `db/pull '[*]` of the 65-key config row | **148,504 reads / 5,946 ms** | **430 / 19.8 ms** |
| `config/effective` | **84,664 reads** (issue), 1,216 / 95 ms after the walker fix alone | **311–376 / 19.0 ms** |

The middle column of the `effective` row is worth keeping: with the walkers
threaded but nothing supplied it was still 1,216 reads, because
`edn-encoded-attr-in?` kept reaching `malli-form?`. Supplying took it to one
population. Threading was necessary and not sufficient.

## The two wedged tests

Both are the 300 s liveness backstop, both attributed to this issue, both
unchanged by this repair.

| Test | Before | After (2 runs) |
|---|---|---|
| `seon.reconcile-test/reconciliation-uses-current-provenance-without-history` | never finished; backstop fired (reproduced this session at `exit=124`) | **5.3 s**, **6.3 s** |
| `seon.config-application-test/applied-values-shape-the-running-system` | never finished | **75.9 s**, **72.9 s** |

`bin/test seon.reconcile-test`: 8 tests / 20 assertions / 0 failures, twice.
`bin/test seon.config-application-test`: 4 tests / 18 assertions / 0 failures,
twice.

I reproduced the reconcile wedge on my own first attempt with only the
`seon.db` walkers fixed — it still ran out the backstop, with `main` in
`reconcile/plan` → `db/pull` → `decode-pull-entity` →
`seon.db/read-declarations` → `declaration-projection`. That dump is why the
`plan` supply seam exists; without it the issue's acceptance criteria would
have been met per operation and the test would still have hung.

## The class regression

`test/seon/db/declaration_population_test.clj` counts reads at the one read
seam and asserts each read operation performs at most ONE population,
whatever its attribute, key, or datom count — `pull '[*]`, `pull-many`,
`entity`, a decoding `q`, and `datoms :eavt` over a nine-attribute row and
nine entities. It is non-vacuous by construction: the first assertion fails if
the unbound resolution ever stops reading resources, and every operation's
assertion fails on the old code by the arithmetic above (34 × 152 ≠ 152). A
second test asserts a read that decodes nothing resolves nothing — the
regression that guards the delay. A third asserts the EDN-backed round trip,
for the defect below.

`bin/test seon.db.declaration-population-test`: 3 tests, 12 assertions, 0
failures.

## Defect found and fixed: EDN-backed writes were broken at HEAD

Found while building the measurement harness, before any of my own edits.

`bbb8c673f` (the write-side hang fix) had `encode-transaction` hand
`encode-transaction-in` a **forms-only** map as its projection.
`validate-logical-slot-in!` compiles through
`schema/projection-validator`, which reads
`:seon.schema.projection/registry` — absent from that map. So every write of
an EDN-backed attribute failed:

```clojure
(db/transact! conn [{:seon.cluster.agent/id "agent-a"
                     :seon.cluster.registry/from :core}])
;; => #:seon.error{:kind :seon.db/unknown-failure
;;                 :message ":malli.core/invalid-schema"}
```

The previous ambient path validated through `valid-candidate-value?`, which
builds the candidate registry from the forms, so it worked; the conversion
dropped the registry silently and no test covered an EDN-backed write. Fixed
at cause by `schema/declaration-projection`, which is the ONE place a
population is paired with its registry, and used by both the encode and decode
seams. `edn-backed-attributes-still-round-trip` in the new regression
namespace covers the write and all four read shapes.

## Ugly output (standing order)

1. **The fallback warning that landed today is exactly right, and it works.**
   `seon.schema: DECLARATION POPULATION FALLBACK — seon.db (db.clj:361) …
   (occurrence 10 for this caller)` named my own remaining seam while I was
   measuring it. This is the loud face the previous report asked for; it
   turned a silent 6-second disk storm into a labelled one. No change wanted.
2. **A stale `clj-kondo` cache entry blocked the whole suite, again.**
   `.clj-kondo/.cache/v1/clj/seon.reconcile.transit.json` recorded
   `identity-attributes` as one-arity-only, so the `:clj` branch of
   `seon/reconcile.cljc` reported `invalid-arity` for a correct two-arity
   call, `seon.fn/build-manifest` refused the index, and every test in
   `seon.reconcile-test` errored with "Static program analysis found blocking
   errors". `seon.reconcile` is a `.cljc`; a `clj/` cache entry for it is
   stale by construction. This is the SECOND instance in two days (the write
   side hit the mirror-image `cljc/seon.schema.transit.json`). The failure
   names the callee and not the cache, and the wall of findings buries the
   four `:level :error` entries among hundreds of `:warning` ones — the
   refusal should print only what it refused on.
3. **`(db/db)` with no connection now says what to do about it.** Per the
   orchestrator's addendum, and `seon.instrument` no longer buries a flat
   error value under `… violated its contract (invalid-input)`: an error value
   arriving at a contract seam IS the answer and is returned as-is.

## What this does NOT do

- It does not make one unbound resolution cheaper. A read with nothing
  supplied still pays 152 reads / 14 ms once. That floor disappears when
  Phase 1 supplies `:seon.schema/projection` from the environment, at which
  point `read-declarations` becomes an environment read and
  `ask-declarations`' supply half becomes unnecessary.
- It does not make `situation-totality-property` fast. Measured after:
  **54.9 s**, against ~60 s before (`bin/test seon.cluster.work-test`: 10
  tests / 63 assertions / 0 failures). Its cost is the WRITE seam, not the
  read seam: its hand-built fixture supplies nothing and each of its ~600
  transactions pays one unbound resolution, 14 ms, which is correct
  per-operation behaviour. Supplying a population in the fixture would mask
  the class; the floor is Phase 1's to erase.
- It does not audit every remaining caller that loops over `seon.db` reads.
  Two were found because they wedged tests. The new fallback warning names
  any others as they occur.
