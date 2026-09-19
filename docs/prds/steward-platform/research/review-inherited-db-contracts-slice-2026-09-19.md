---
type: research
status: complete
created: 2026-09-19
tags: [review, db, schema, contracts]
---

# Review: the inherited, uncommitted `db contracts and read seams` slice

Read-only review of the working tree against HEAD `939a5d1f6` on
`steward-platform`, for the five files the slice owns. No JVM was started,
nothing was edited, staged, stashed or reverted. Every claim below is read
from the diff, the tree, or a named document; nothing is executed, so **no
test tally is claimed anywhere in this note**.

Measured extent (`git diff HEAD --stat`):

| File | + / − |
|---|---|
| `src/seon/db.clj` | 29 hunks, 571 changed lines |
| `src/seon/schema.clj` | 2 hunks, 34 added |
| `resources/seon/schemas/seon.db.edn` | 25 added |
| `test/seon/db_test.clj` | 200 changed (4 new `deftest`, 1 renamed) |
| `test/seon/schema_test.clj` | 26 added (1 new `deftest`) |
| the slice's own note | 30 changed |

The slice is split across the index and the working tree (`MM src/seon/db.clj`,
`MM test/seon/db_test.clj`, `M ` staged-only `src/seon/schema.clj` and
`test/seon/schema_test.clj`, unstaged-only `seon.db.edn`). A landing commit
must be `git commit --only -- <the five paths>`, which takes the worktree
state; nothing here needs the index split preserved.

## 1. What it set out to do, and what the diff does

From the slice's own note
([db-contracts-and-read-seams-2026-09-18.md](db-contracts-and-read-seams-2026-09-18.md)),
continuing `1695b43b2` and the launch recorded in
[unsettled.md](../plan/unsettled.md) "~08:30Z — the db lane launches":
ruling §1q error unions on the remaining `seon.db` owners; C1b #2, `pull`'s
output as the derived pulled form (study option B); B4's three read seams
#18/#19/#20; and ruling 1r's provenance-derived write bound.

### Group A — the declared error unions (§1q)

Fourteen output contracts widened from a bare `:seon.error/value` to the
declared `:seon.db/error-result` union: `read-evidence-changes`
(`src/seon/db.clj:1014`), `read-evidence-current?` (`:1058`), `pull` (`:2310`),
`pull-many` (`:2359`, `:2367`), `entity` (`:2421`), `datoms` (`:2515`),
`index-page` (`:2528`), `commit-id` (`:2595`), `committed-value-identity`
(`:2608`), `history` (`:2625`), `as-of` (`:2638`), `since` (`:2652`), `diff`
(`:3041`), `arity-mismatches` (`:3702`); plus a new contract on `replay-read`
(`:926`). `:seon.db/error-result` is the row landed by `1695b43b2`
(`resources/seon/schemas/seon.db.edn:6`).

**`db`, `supplied-database-value` and `supplied-connection` were NOT widened.**
The tree keeps `:seon.error/value` there and adds a comment at
`src/seon/db.clj:1717-1721` recording why: a supplier's declared return is read
by `seon.call-preparation`, which refuses a default whose declaration is wider
than the argument it fills, so widening those three made every prepared
`:seon.db/db` and `:seon.db/connection` inadmissible and `seon.db/diff` then
reported `:seon.db/database-input-absent` (measured 2026-09-18).
`src/seon/db.clj:1761` confirms the union is still `:seon.error/value`.
**The note's §1 table lists all three as widened. The table is wrong about the
tree** — the comment is the true record and the table is the stale one.

### Group B — the three read seams (#18/#19/#20)

- `read-declarations` (`src/seon/db.clj:1169`) now gates on
  `(db.utils/db? database)` and on a non-empty installed schema, and returns a
  `::unreadable-declarations` diagnostic instead of a table whose
  `::installed-schema` was `nil`.
- `with-declarations` (`:1209`) is the new seam: it calls the continuation with
  the declarations or returns their refusal, and its four-arity admits one
  explicit `:seon.db/relation-only` marker.
- `relation-only-declarations` (`:1200`) is the empty-but-readable table for a
  query with no database source.
- Seven consumers branch on it: `replay-read` (`:926`), `read-evidence-changes`
  (`:1014`), `q` (`:1942`), `pull-call` (`:2233`), `datoms-call` (`:2462`),
  `index-page` (`:2528`), `diff` (`:3071`).
- `replay-read` (`:926`) gains an input contract naming `:seon.db/read-request`
  and a typed default arm `::unknown-read-operation` naming
  `:seon.db/read-operation` and the offending value, replacing `case`'s bare
  `IllegalArgumentException` into the since-diff.
- `seon.schema`: `refuse-projection-source` (`src/seon/schema.clj:2598`) and a
  `(when-not (db-utils/db? db) (throw (ex-info …)))` guard at the head of
  `derive-projection-from-database` (`:2628-2631`), with the new
  `[datahike.db.utils :as db-utils]` require.

### Group C — the pull output contract and the derived pulled form

- `pulled-entity-schema-key` (`src/seon/db.clj:2062`): the entity's schema key
  from the `:seon.program/row-schema` the present attributes declare (exactly
  one = the key; more than one = `::disagreeing-pull-schema`), else a unique
  projection shape-index match whose required attributes are all present, else
  `nil` (undecided).
- `selector-names-attributes?` (`:2131`): a `[:db/id]`-only selector needs no
  entity schema.
- `validate-pulled-value` (`:2142`): derives the form through
  `schema/projection-with-pulled-form-in` (`src/seon/schema.clj:3016`) +
  `schema/pulled-schema-key` (`:2782`) and validates; a refused DERIVATION
  passes the value through, a failed VALIDATION refuses with
  `::invalid-pulled-result`.
- `validate-pulled-result` (`:2185`): the four-outcome walker — `[:db/id]`-only
  passes, `nil` passes, undecided key passes, named key validates.
- `pull-call` (`:2233`) extracts `:schema-key`, strips it before handing the
  argument map to Datahike, derives the literal selector and the entity ids,
  and returns `checked`.
- `seon.db.edn:268` declares `:seon.db/pulled-entity`, and `:schema-key` is
  added to `:seon.db/pull-options` and `:seon.db/pull-many-options`.

This is exactly study option B
([entity-schema-vs-pulled-shape-2026-09-16.md](entity-schema-vs-pulled-shape-2026-09-16.md)
§5 (B), the recommended option) consumed at the read, not a hand-written
pulled mirror.

### Group D — the provenance-derived write bound (§1r)

`agent-provenance?` (`src/seon/db.clj:4037`) reads `[:tx-meta :seon.db/user]`:
a `[:seon.agent/id …]` lookup ref is an agent write, otherwise the user entity
is resolved and checked for a `:seon.agent/id` datom; absent user = system.
In `transact-call` the prepared request is hoisted (`:4090`), `agent-write?`
computed (`:4099`), `write-time-limit-ms` becomes `nil` for system writes
(`:4100-4104`), the deref is bounded only when a bound exists (`:4114-4116`),
and the bound refusal carries `:seon.store/transaction transaction` (`:4136`)
so root can re-run it. `:seon.store/transaction` is a declared key
(`resources/seon/schemas/seon.store.edn:34`).

### Group E — tests

`test/seon/db_test.clj`: `a-refused-declarations-read-refuses-decoding`
(`:2142`), `an-unknown-read-operation-refuses-naming-the-attribute` (`:2170`),
`pull-validates-its-result-against-the-derived-pulled-form` (`:2187`),
`the-write-bound-derives-from-the-writes-own-provenance` (`:2245`),
`a-system-write-carries-no-per-write-bound` (`:1238`), and the pre-existing
bound test renamed to `an-agent-write-that-does-not-deliver-refuses-at-the-declared-bound`
and re-based on agent provenance (`:1167`).
`test/seon/schema_test.clj:1284`:
`a-refused-projection-source-never-yields-a-projection-with-no-forms`.

## 2. Verdicts

| Group | Verdict | Evidence |
|---|---|---|
| A — error unions | **SOUND**, with one stale record | The fourteen contracts are the declared union; arming is the check. The note's §1 table claims `db`/`supplied-database-value`/`supplied-connection` were widened; `src/seon/db.clj:1761` and the comment at `:1717` say they deliberately were not. Fix the note, not the code. |
| B — read seams | **SOUND** | Each of #18/#19/#20 closes an absence-read-as-health path, each has a regression, and the refusals are evidence-complete `seon.error/diagnostic` values (`src/seon/error.clj:338`), so `:seon.error/kind` at the top level and `:seon.error/diagnostic-*` under `:seon.error/data` — which is exactly how both tests read them. Two loose ends below. |
| C — pulled form | **SOUND** (after the repair the RESUME block demanded) | The three items named in [unsettled.md](../plan/unsettled.md) "RESUME HERE (2026-09-18 ~13:15Z)" are all present and asserted: `[:db/id]`-only (`:2131`), undeclared entity passes through (`:2185` `nil schema-key → nil`), `nil` for absent (`:2185`). The shelved patch `db-contracts-shelved-2026-09-18.patch` contains neither `selector-names-attributes?` nor the disagreement-only refusal, and `db-contracts-and-read-seams-partial-2026-09-18.patch:29,97` still carries the broken `pull-request-parts` / `::unknown-pull-schema` that wedged initialization. **The tree has advanced past both patches and the wedge is gone** — `unknown-pull-schema` survives in the tree only as a docstring in `src/seon/cluster.clj:1276`. |
| D — write bound | **SOUND** | Ruling 1r is owner-ruled, twice recorded: [unsettled.md:3413](../plan/unsettled.md) and the standing-rules line in the RESUME block ("root writes unbounded per write, agent writes bounded, derived from tx provenance (1r)"). The unbounded `(deref pending)` is that ruling, not a lane dropping §2.3's bound half: the operation's own lifecycle deadline is the declared bound. Three regressions (`:1167`, `:1238`, `:2245`). |
| E — `schema.clj` throw | **SOUND, with one open decision the note itself flags** | The refusal is delivered as `ex-info`, not a value, because `projection-from-database`'s output contract is `::projection` (`src/seon/schema.clj:2669-2672`) and ~20 first-party call sites read it as one (`seon.cluster` ×6, `seon.fn` ×4, `seon.error` ×3, `seon.turn`, `seon.test`, `seon.config`, `seon.schedule`, `seon.reconcile`, `seon.db:261`). Consistent with the sibling refusals inside `projection-from-rows`. §2.4's "errors are values" applies at agent/runtime boundaries; this is an internal derivation. The note asks a reviewer to consider overturning it: **do not** — widening the union is the cross-owner change §2.5's owner gate exists to avoid, and the throw is what the surrounding function already does. |
| FOREIGN | **none in these five files** | All 29 `src/seon/db.clj` hunks are error unions, `with-declarations`, pull validation, or the 1r bound. The live publication repair's footprint (`src/seon/cluster.clj`, `src/seon/instrument.clj`, `src/seon/sci/eval.clj`, `src/seon/fn.clj`, `resources/seon/schemas/seon.instrument.edn`) touches none of the slice's five paths. |

Loose ends that are real but small (all FINISH, none RESET):

1. **Two new private functions carry no Malli contract** — `with-declarations`
   (`src/seon/db.clj:1209`) and `validate-pulled-result` (`:2185`). §2.4
   requires one on every function, private included. (`read-declarations`
   already lacked one at HEAD; `pull-call` too.)
2. **`:seon.db/pulled-entity` is a name, not a check.**
   `seon.db.edn:268` is `[:map {:description …}]` with no keys, so the static
   contract is exactly `:map` — the guarantee is entirely the runtime
   validation in `validate-pulled-value`. That is honest and correct under
   option B (the derived key is per-selector and cannot be named statically),
   but the contract is not the proof; the regression is.
3. **The `:seon.db/relation-only` marker is a latent hole.** `q` passes
   `(some #(when (db.utils/db? %) %) aligned)` (`:1942`), so *any* nil there
   takes the empty-schema path and decodes nothing. Today `query-call-valid?`
   refuses a source input that is not a database before this point, so it is
   unreachable with contracts armed — but unarmed, it is the same
   "nothing to decode" / "cannot decode" confusion the seam was built to end.
   A one-line change (require that the query's parsed `:in` names no source)
   would make it structural.
4. **Two unmeasured per-operation costs.** `pulled-entity-schema-key` does an
   `entid` + a full `:eavt` datoms scan + a form resolution *per pulled entity*,
   so a `pull-many` of N entities without a caller `:schema-key` does N extra
   scans; `agent-provenance?` may do an `entid` + `datoms` on every single
   `transact!`. Neither is measured in the note. Standing order
   "fast by default — slow is a bug" makes this worth one measurement, not a
   redesign.
5. **`docs/seon/issues/pull-validation-refuses-a-db-id-selector.md` is still
   `status: open`** and its subject is repaired in this tree. It is resolved by
   this landing.

## 3. Would the tests run on the canonical fixture, and what do they assert?

**All five new tests and both amended ones use `test-support/with-database`** —
`test/seon/db_test.clj:2143, 2171, 2188, 2246, 1240` and
`test/seon/schema_test.clj:1285`. The amended bound test writes its agent row
through `test-support/transacted!` (`:1177`) and its config through
`test-support/apply-config!`, so no hand-written fixture map appears. They will
therefore be armed like any other canonical-fixture test.

**The blocker the note names is gone.** §6 says no canonical-fixture regression
in the tree could run because `test/seon/test_runner_test.clj:48` called the
removed `dev-cache/digest-file!`. At HEAD that file calls
`#'dependency-digest/digest-file!` (`test/seon/test_runner_test.clj:48`),
landed by `b9c4cfa58` ("Follow the dependency-digest helper to its new owner").
The fixture base is loadable; the gate is runnable.

What they assert, and the honesty check:

- `a-refused-declarations-read-refuses-decoding` asserts the refusal kind and
  member, that the continuation did **not** run (`(is (false? @decoded?))`),
  **and** the positive control that a real database still reaches the decoder
  (`::decoded`). The absence assertion is paired with its positive; sound.
- `an-unknown-read-operation-refuses-naming-the-attribute` calls
  `(mi/-f->original @#'db/replay-read)` to strip the instrumented wrapper,
  because the new input contract's `:seon.db/read-operation` enum would refuse
  the bogus member before the default arm. That is explicit and honest, but it
  means the regression proves the default arm **with the contract removed**; an
  armed caller gets the contract refusal instead. Both are typed, so the class
  (a `case` with no default throwing into the since-diff) is dead either way.
- `pull-validates-its-result-against-the-derived-pulled-form` asserts a real
  fixture pull validates, an absent entity is `nil`, a `[:db/id]`-only pull on
  the measured trigger `[:seon.ai.model/provider-id "openrouter"]` returns an
  int id, an undecided schema key is not an error, and a deliberately
  wrong-typed value (`{:seon.ns/name "my.message"}` — a string where the
  attribute is a symbol) refuses with `::invalid-pulled-result`.
  **Two assertions read absence as behaviour** and must be fixed before
  landing: `(is (every? :seon.fn/sym (db/pull-many …)))` at `:2225` and the
  `db/q` feeding it — an empty query result makes `every?` vacuously true, so
  the "a wildcard pull whose form the derivation declines still reads" claim
  would pass if the fixture stopped containing `my.message/send`. Assert the
  count is positive first. Everything else in this test is a positive value
  assertion.
- `the-write-bound-derives-from-the-writes-own-provenance` asserts
  `agent-provenance?` directly for a lookup-ref user (true), a
  `:seon.db/process` write (false) and an empty map (false). Pure, positive.
- `a-system-write-carries-no-per-write-bound` blocks the real writer, asserts
  the future is still waiting after 40× the dial, releases it, and asserts the
  target row is readable. Its middle assertion `(is (not (:seon.error/kind report)))`
  reads absence of a key as success — it is bounded by the following positive
  pull, so it is not load-bearing, but `(is (contains? report :db-after))`
  would be the honest form.
- `a-refused-projection-source-never-yields-a-projection-with-no-forms` asserts
  the throw happens, names the kind, member and offending value, **and** that a
  real database still derives a populated forms table. The poisoned value is a
  map, so `projection-from-database`'s `[:catn [:seon.schema/database-value :map]]`
  contract (`src/seon/schema.clj:2669`) admits it and the new guard is genuinely
  reached with contracts armed. Sound.

## 4. Recommendation per group

Everything here is **FINISH**. Nothing is RESET: no group contradicts a ruling,
every group has a regression, and the one item that wedged the tree
(`::unknown-pull-schema` on a `[:db/id]` pull) is already repaired in the tree
and only survives in the two shelved patches, which can be deleted after the
landing.

| Group | Action | Remaining work | Lane-hours |
|---|---|---|---|
| A unions | FINISH | Correct §1 of the slice note: three suppliers keep `:seon.error/value`, with the `call-preparation` reason. Documentation only. | 0.25 |
| B read seams | FINISH | Add `:malli/schema` to `with-declarations`; make the `:seon.db/relation-only` decision structural in `q` (derive "no source input" from the parsed query rather than from a nil `some`). | 0.75 |
| C pulled form | FINISH | Add `:malli/schema` to `validate-pulled-result`; fix the two vacuous `every?` assertions at `test/seon/db_test.clj:2225`; measure one `pull-many` of ≥100 entities with and without `:schema-key` and record the number in the note. | 1.0 |
| D write bound | FINISH | Measure `agent-provenance?`'s cost on a hot write path (one number in the note); tighten `(is (not (:seon.error/kind report)))` to a positive report assertion. | 0.5 |
| E `schema.clj` | FINISH as written | Keep the `ex-info` delivery; delete §3's "a reviewer may want to overturn this" invitation from the note and record the ~20 call sites as the reason. | 0.25 |
| Verification | FINISH | The cold gate below, which nobody has run for any of it. | 0.5 |

**Total to finish the sound parts: ~3.25 lane-hours**, of which 2.5 is code and
tests and 0.75 is the gate plus the note corrections. The work is coherent
enough for one lane; splitting it would re-pay the grounding cost five times.

## 5. The gate the orchestrator would run

```
bin/test --paths src/seon/db.clj src/seon/schema.clj \
  resources/seon/schemas/seon.db.edn test/seon/db_test.clj \
  test/seon/schema_test.clj \
  -- seon.db-test seon.schema-test seon.cluster-test
bin/test --platform
```

`seon.cluster-test` is in the selection because `transact-initialization!`
(`src/seon/cluster.clj:1268-1300`) is the reader whose nil-versus-refusal
distinction this slice broke and then repaired, and `src/seon/cluster.clj:1276`
still names the refusal in its docstring.

Two admission notes from the slice's own §6, still true: the cold `--paths`
overlay previously refused naming dirty caller files held by other lanes
(`src/seon/cluster/source.clj`, `src/seon/test.clj`, `src/seon/test/runner.clj`,
`test/seon/cluster/source_test.clj`, `test/seon/test/runner_test.clj`) — those
paths are still dirty in this tree, so the same refusal should be expected and
the named paths added to `--paths`, or the gate run after those lanes land.
And `bin/test` itself is modified in the working tree, which the gate run
should be aware of.
