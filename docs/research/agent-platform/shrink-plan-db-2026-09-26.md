---
type: plan
status: proposed; no source edits made; root selects slices
created: 2026-09-26
lane: shrink-db-opus
files: src/seon/db.clj (5,015 lines, 238 top-level definitions), its tests
tags: [shrink, performance, datahike, db]
---

# Refactor plan: `src/seon/db.clj`, slow paths first

The owner ruled on 2026-09-26: no breaking changes until there is a plan, and slow paths come first. So this lane has
made **no source edits**. This plan builds on the 2026-09-23 region audit
([shrink-db-2026-09-23.md](shrink-db-2026-09-23.md)), whose rows are cited below as "A23 row X". That audit is still
largely open: the file has grown from 4,654 to 5,015 lines since. The one exception is its row C′ (40 inline
error-shape copies), which has since been done: there are 0 copies left.

## Evidence

- **Profile source.** `seon.profile` observations on default pid 89341 (booted 21:35Z), read-only MCP JVM probes,
  2026-09-26 ~21:45Z. Times are inclusive, meaning a caller's time includes its callees' time. Contracts were armed.
- **Probe method.** Every probe below ran in the MCP session `shrink-db-opus`. The probes were read-only against
  default. A throwaway in-memory Datahike store was used for revision questions and was deleted afterwards.
- **Dead-code proof.** A clj-kondo analysis of `src script test` (`--cache false`, var-definitions and var-usages)
  was intersected with `rg` over `src script test resources`.

### `seon.db` by inclusive total since boot (top rows)

| Function | Calls | Total ms | Mean | Max ms |
|---|---:|---:|---:|---:|
| `call-with-custody` (scope; includes whole evaluations) | 389 | 796,817 | 2.0 s | 146,305 |
| `with-declarations` (read seam; includes q/pull bodies) | 157,425 | 169,378 | 1.1 ms | 2,906 |
| `q` | 20,969 | 115,318 | 5.5 ms | 2,744 |
| **`transact!`** | 310 | 101,413 | **327 ms** | **8,287** |
| `validate-write-report` → `write-report-error` | 370 | 47,445 → 42,926 | 128 → 116 ms | 9,602 |
| `pull-call` | 51,503 | 30,627 | 0.59 ms | 2,908 |
| **`with`** | 52 | 30,000 | **577 ms** | 10,311 |
| **`report-arity-comparison`** → `arity-base` | 46 | 27,744 → 23,132 | **603 → 503 ms** | 9,042 |
| `carried-projection` | 77,041 | 23,523 | 0.3 ms | 998 |
| **`content-projection`** (a projection derivation on a miss) | 444 | 20,706 | **47 ms** | 986 |
| `value-projection` | 8,528 | 14,720 | 1.7 ms | 986 |
| `write-owned-values-error` | 368 | 8,227 | 22 ms | 686 |
| `declaration-content-key` (walks ~40k datoms) | 444 | 5,217 | 11.7 ms | 464 |
| `write-digest-error` | 368 | 2,520 | 6.8 ms | 1,262 |
| `arity-content-key` (walks the arity datoms) | 91 | 1,434 | 15.8 ms | 456 |
| `identity-attributes` (sorts all ~1,219 installed attributes) | 2,448 | 1,416 | 0.6 ms | 52 |

### Direct probes (this session)

| Probe | Seon | Datahike | Note |
|---|---:|---:|---|
| `q`, full `:seon.fn/sym` find, Datahike cache warm | 77 µs | 3.5 µs | 22× |
| `q`, one bound input | 68 µs | 3.4 µs | 20× |
| `pull '[*]` by lookup ref | 128 µs | 87 µs | 1.5× |
| `with`, a one-datom error message | 4.24 ms | 0.10 ms | 40×. Per write: 763 `schema/projection-cache-value` calls, 6 `identity-attributes` calls |
| `call-edges-query` (an `or` over `:seon.fn/sym` / `:seon.test/sym`) on a **speculative** value, cold | **1,208 ms** | — | — |
| The same edges as two plain queries, unioned | **87 ms** | — | Identical: 102,063 edges, `(= or split)` true |
| `edges-fn-only`, one plain query, cold | 84 ms | — | — |

**Revision probe (throwaway store).** A write's report `:db-after` and the committed value that follows carry the
**same** attribute revision for the attribute they wrote: `6ab83e8a…`, content-derived. A `d/with` result gets a
random one. An attribute the write left untouched keeps its basis revision.

## 1. Slow paths first

### P1. Replace the arity gate's `or` query with two plain queries (the largest per-write stall)

- **What is slow.** `arity-base` (`db.clj:3838`) derives the whole-program call-edge set on a miss with
  `call-edges-query` (`:3797`), an `(or [?c :seon.fn/sym ?s] [?c :seon.test/sym ?s])` join over about 100k
  `:seon.fn/call-arities` datoms. On the writer's speculative values Datahike's query cache never hits, and that query
  costs **1.2 s**, rising to 2.4 s in the first probe of this session.
- **Evidence.** Five derivations since boot explain `arity-base`'s 23 s total and its 8.9 s maximum.
  `report-arity-comparison` (`:3877`) repeats the same `or` for the callers a report touched.
- **Proportional to.** The whole program, on every miss.
- **Fix.** Two plain queries, `[?x :seon.fn/call-arities ?c] [?x :seon.fn/sym ?s]` and the same with
  `:seon.test/sym`, unioned. That is **87 ms**, with identical results (probe above). Apply the same change to
  `after-callers` in `report-arity-comparison`.
- **Also.** `after-bounds` inlines a copy of `declared-arity-bounds`' reduce: call `declared-arity-bounds` with an
  `:in` of symbols instead.
- **Lines.** About +4, −14.
- **Risk.** Low: a pure query rewrite.
- **Regression.** The arity-gate tests in `db_test.clj`, plus a committed probe asserting that the split and `or`
  edge sets are equal on default.

### P2. Key the arity base by Datahike's attribute revisions, not by copies of the index

- **What is slow.** `arity-content-key` (`:3828`) copies every datom of six attributes into a vector: 15.8 ms per
  call. It is computed about twice per write that touches a definition: once for `:db-before`'s lookup and once to
  seed `:db-after`. The two LRUs hold these vectors as keys: 92–122 MB at idle (task `arity-caches-copy-the-indexes`,
  cache-central design rows 1–2).
- **Proportional to.** The whole program's arity datoms, per write.
- **Fix.** Use the key `projection-cache-key` already uses for projections: `select-keys` of
  `:datahike.cache/attribute-revisions` over `arity-attributes`, plus connection-id, generation and conservative
  revision. That lookup is O(6). The revision probe shows a report's `:db-after` revision equals the committed value's,
  so the seed that `report-arity-comparison` leaves is hit by the next write's `:db-before`.
- **Deletes.** `arity-content-key`, `arity-commit-cache` (revisions are content-derived on commit, so a fork at an
  equal commit shares them). The remaining cache goes through `seon.cache/lookup` (cache-central slice 2).
- **To verify first.** Is the writer's in-validator `:db-after` the same context as the report's? The arity-base
  comment at `:3854` says `:db-before` carries no committed identity. Test this with a d/transact on a throwaway store
  whose validator records `(:cache-context db-after)`.
- **Lines.** About −35.
- **Risk.** Medium: a cache-key change. A wrong key is a false hit.
- **Regression.** A test that writes two definitions in sequence on a branch and asserts the second write's
  `arity-base` hits (no `declared-arity-bounds` growth, read through `seon.profile/explain`), plus the existing
  arity-mismatch refusal tests.

### P3. One projection derivation per declaration write, not a derivation plus two 40k-datom walks

- **What is slow.** `seon.schema/projection-attributes` includes `:seon.fn/sym` and `:seon.fn/spec`, so **every
  defn write changes the projection's input**. Each such write pays:
  1. On the validator's speculative value: `value-projection` → `content-projection` → `declaration-content-key`
     (11.7 ms walk) → a derivation (47 ms mean, max 986 ms).
  2. On the committed value, the durable revision key and the commit key both miss. So it walks
     `declaration-content-key` again, only to hit the content entry.
- **Evidence.** 444 derivations and walks since boot, 26 s total.
- **Proportional to.** All declaration datoms, per write.
- **Fix (smallest).** The committed value's revision key equals the report's `:db-after` key (revision probe). So in
  `carried-projection`'s speculative branch, store the derived cell under the **revision key in the durable tier**
  when the value's context names a connection. The committed read then hits on its revision key with no walk. That
  removes the second walk and the value tier's reason to exist. It also deletes `value-projection-cache`,
  `value-projection`, and the value-tier half of `projection-cache-policy`.
- **Fix (full).** Cache-central slice 2 (cache-central design §S2): the declaration digest as the value key, which
  also deletes `revision-agreement`, `nearest-base`, `as-of-key` and `declaration-content-key` (about −170 lines per
  that design). Recommend: take the smallest fix now; slice 2 follows as its own reviewed cut.
- **Lines.** Smallest fix about −30. Full fix about −120 net.
- **Risk.** Medium: speculative values that later abort leave a durable entry keyed by a revision no committed value
  will ever carry. It is bounded by the LRU, so the cost is an early eviction, not wrong reuse.
- **Regression.** `schema/projection_writer_test.clj` and `db/declaration_population_test.clj`, plus a probe
  asserting one `declaration-content-key` call per defn transaction.

### P4. The per-write report validator does whole-schema work for a one-datom write (40× `d/with`)

- **What is slow.** A trivial `with` costs 4.24 ms against 0.10 ms (task `the-checked-writer-is-slow-on-a-fresh-branch`
  records 211 ms of `write-report-error` on a fresh branch).
- **Proportional to.** The installed schema and projection per write, not the written datoms. Measured per write:
  1. **763 `projection-cache-value` calls.** `write-attribute-plans` (`:4256`) builds a plan for **every installed
     attribute**, keyed by the whole installed schema map, then `write-report-error` reads only the attributes of
     `attempted`. Fix: look up `write-attribute-plan` (already memoized per attribute) for the attributes actually
     present in the report's datoms, and delete `write-attribute-plans`. Its callers are `write-report-error`,
     `declaration-value` and `write-owned-values-error`, all in this file.
  2. **6 `identity-attributes` calls per write, each a sort of about 1,219 attributes.** `report-identity-attributes`,
     `write-digest-error`, `write-deletion-error` and `retention-snapshot` each recompute it. Fix: compute before and
     after once in `write-report-error` and pass them down. `identity-attributes` itself could be
     `schema/projection-cache-value`-memoized by the installed schema map. Datahike exposes `:rschema`
     (`db.utils/is-attr?` uses `(-> db :rschema :db.unique/identity)`), which already holds the identity attribute
     set, so `identity-attributes` becomes `(sort-by str (filter qualified-keyword? (get-in (schema-database db)
     [:rschema :db.unique/identity])))`. To verify: the `:rschema` key in `reference-code/datahike`.
- **Also.** `validate-write-report` does `(assoc projection :seon.config.db/validation-node-limit limit)`, which
  writes a config dial into the projection map to pass one number. Pass `limit` as an argument to
  `write-owned-values-error`, its only reader.
- **Lines.** About −25.
- **Risk.** Low to medium.
- **Regression.** `db_test.clj` writer refusals, `owned_value_test.clj`, and a timing probe of `with` on the trivial
  write (target under 1 ms).

### P5. `q` is 20× Datahike on a cache hit: parse once, skip the codec when nothing is encoded

- **What is slow.** 68–77 µs against 3.5 µs (task `the-query-wrapper-costs-36x-datahike`). Each call:
  1. Normalizes the input twice: in the body, then again in the `:fn` guard `query-call-valid?`.
  2. Runs `query-input-values` five times: 104,635 calls over 20,969 `q` calls.
  3. Walks `query-patterns`/`parsed-nodes` five times.
  4. Runs `walk/postwalk` over the query even when there are zero replacements.
  5. Computes `query-find-attributes` just to learn that nothing decodes.
- **Proportional to.** Query size × 5, per call. The per-form read path runs dozens of these.
- **Fix, one analysis per call.**
  1. Compute patterns and input values once in `q`, and pass them to `query-attribute-error`,
     `query-value-type-error`, `encode-query-request` and `decode-query-result`.
  2. When no pattern names an EDN-encoded attribute (A23 row I: 25 of about 1,219 attributes), skip encode, the
     postwalk and decode entirely.
  3. **Delete the guard pair** `query-call-valid?` / `query-guard-message` and the `[:fn …]` arm of `q`'s contract.
     The body already returns `query-input-shape-error` for exactly the cases the guard re-derives (both call
     `query-input-position`), so the guard is a duplicate path. The guard is referenced only from this file.
- **Lines.** About −70.
- **Risk.** Low. The refusal text stays the body's `query-argument-message`.
- **Regression.** The `q` shape-refusal tests in `db_test.clj` and `db_error_test.clj`, plus a probe: `q` within
  2× of `d/q` on a warm non-encoded query.

### P6. The per-defn double writer (cross-owner finding, no db.clj edit)

`seon.turn/settled-value` (`turn.clj:1398`) runs `db/with` (the full writer: 577 ms mean, 52 calls) and settlement
then runs `transact!` (327 ms mean) over the same row. Every definition is validated twice. After P1–P4 both are
cheaper, but the duplication belongs to the turn owner: can settlement reuse the `with` answer, or skip it when the
transact follows immediately? This is reported to root; no db.clj change.

## 2. Deletions (behaviour preserved)

"Ext" counts callers outside `seon.db` from kondo plus `rg` (src, script, test, resources).

| # | What | Proof | Replacement / kept path | Lines | Risk | Regression |
|---|---|---|---|---:|---|---|
| D1 | `deletion-error` (`:4186`) | kondo: 0 usages anywhere; `rg`: only its own task EDN (`deletion-error-has-no-caller`) | none; `removed-definition-error` stays (used by `write-deletion-error`). Close the task | −19 | none | load + kondo |
| D2 | `pull-plan-with-evidence`, `pull-many-plan-with-evidence` (`:985`, `:990`) | `(apply pull-api/… arguments)` wrappers that only rename; private; 8 internal uses | `pull-api/pull-plan-with-evidence` / `pull-many-plan-with-evidence` directly | −10 | none | pull tests |
| D3 | `q` guard pair (P5) | same checks as `aligned-query-arguments` / `query-input-position`; ext 0 | the body's `query-input-shape-error` | −55 | low | q refusal tests |
| D4 | `pull-call-valid?` and its `[:fn …]` arms on `pull` / `pull-many` (6 copies of the message) | `pull-call` builds the identical `valid?` inline and refuses with the same guidance; ext 0 | `pull-call`'s own check | −25 | low | pull refusal tests |
| D5 | `pull` / `pull-many` / `entity-call` repeat six constant arguments across seven call sites | the same `pull-call` with `(operation, operation-key, result-key, public-op)` fixed per public function | `pull-call` takes `many?` and derives the other three | −35 | low | pull/pull-many tests |
| D6 | arity duplicates: `after-bounds` copy of `declared-arity-bounds`; `arity-mismatches-with`'s inline copy of `arity-comparison` | read side by side (`:3769`, `:3912`, `:4057`) | `declared-arity-bounds` with an `:in` list; `arity-comparison` | −20 | low | arity tests |
| D7 | 15 copies of `(vary-meta (diagnostic {… :seon.db/invalid-read true :seon.error/layer :database-read …}) assoc :seon.error/declared-schema :seon.db/invalid-read-error)` | `grep -c` = 15 | one private `read-refusal [operation member expected offending message data]` over `diagnostic` | −60 | low: the key sets stay identical; test by diffing the key sets of each refusal before and after | db_error_test, db_test |
| D8 | the arity and projection hand-rolled LRUs (`arity-base-cache`, `arity-commit-cache`, `projection-cache`, `value-projection-cache`, `projection-cache-policy` constants) | CLAUDE.md "Caches are a library, bounded and configured": sizes are code constants today | `seon.cache/lookup` (cache-central slice 2), with dials in config | −30 (after P2/P3) | medium | projection_writer_test |
| D9 | `diff` / `apply-diff` / `value-changes` (editscript over shown values, 45 lines) | not a database concern; only callers are `turn.clj:2008,2300` and the `seon.db.diff` schema | moves to the turn owner (a relocation, not a deletion); **needs the turn lane** | −45 here | low | turn diff tests |
| D10 | write pre-flight walk (`write-error`, `write-map-error`, `write-ref-error`, `write-many-values`, `write-value`, `write-key-candidates`) | A23 row Q: the final-report validator (`write-report-error`) re-validates every attempted datom with the same per-attribute validators, so every value is validated **twice** per write | the report validator alone; add the "missing declared key" hint to its entity refusal | −170 | **medium-high**: refusal paths change from `[index attr]` to `[eid attr]` | transact_feedback_test (its path assertions are retired assumptions to update), db_test |
| D11 | read-evidence patterns and replay (`query-index-patterns`, `pull-index-patterns`, `index-pattern-change`, `index-evidence-current`, `replay-read`, `stable-value`, `read-result-digest`, `bounded-read-request?`) | A23 row F: Datahike's per-attribute revision is the dependency's granularity; the pattern matcher reinvents Posh | `read-evidence-current?` becomes revision equality; a moved revision means reread | −450 | medium; callers in `turn.clj`, `render.clj`, `sci/kernel.clj` (other lanes) | read_evidence_test, render retained tests |
| D12 | projection stamp and state carrier (`connection-projection-state`, `carry-connection-projection-state!`, `carry-projection-state`, the meta stamping in `resolve-database-value` / `database-view`) | A23 row D, **ruled 2026-09-23**: `carried-projection` answers from the value itself | `carried-projection`; genesis gets its projection as an argument | −110 | medium; 13+ external callers in other lanes' files | acquisition, host and turn tests |
| D13 | codec minimal form (the `with-declarations` / `ask-declarations` / `decode-*` family) | A23 row I: only 25 attributes are encoded | an encoded-attribute set per projection, and decode by membership | −250 | medium | q/pull/datoms decode tests |

**Checked and kept:**

- `transaction-report-datom-generator` (kondo shows 0 usages, but it is referenced as `:gen/gen` in
  `resources/seon/schemas/seon.db.edn:288`).
- `render-transaction-*`, `render-rejection-*` (render pairs in schema resources).
- `supplied-database-value`, `supplied-connection` (call-preparation supplier rows in the database).
- `call-without-custody` (test-only, but it is the scope proof `seed_preparation_test` needs).

No other public or private definition is dead. Every private definition has at least one internal caller.

## 3. Ordered loadable slices (slow first)

Each slice is one commit that keeps HEAD loading and is self-contained in `db.clj` plus its tests, except where
marked. Before each commit: `clj-kondo --lint src script test --cache false` shows 0 errors, and
`contracts-compile-test/check` on `db.clj` shows 0 findings.

| Slice | Content | Net lines | Expected effect | Root runs |
|---|---|---:|---|---|
| 1 | **P1** + D6 | −30 | arity derivation 1.2–2.4 s → ~0.1 s; `transact!`/`with` maxima (8–10 s) drop | `--ns seon.db-test` |
| 2 | **P4** (plans for written attributes only, identity attributes once per report, limit as argument) | −25 | trivial write 4.2 ms → <1 ms | `--ns seon.db-test`, `seon.owned-value-test` |
| 3 | **P2** (revision-keyed arity base) after its verification probe | −35 | −15.8 ms × 2 per definition write; about −100 MB idle heap | `--ns seon.db-test`; a two-defn branch probe |
| 4 | **P3** smallest fix (durable revision entry for speculative values) | −30 | one 12 ms walk fewer per defn; value tier gone | `seon.schema.projection-writer-test`, `seon.db.declaration-population-test` |
| 5 | **P5** + D3 (q: one analysis, skip the codec, delete the guard) | −70 | q 70 µs → under 10 µs on non-encoded queries | `--ns seon.db-test`, `seon.db-error-test` |
| 6 | D1 + D2 + D4 + D5 (dead code and pull duplication) | −90 | pull ~40 µs less | `--ns seon.db-test` |
| 7 | D7 (one read-refusal builder) | −60 | — | `seon.db-error-test` |
| 8 | D8 = cache-central slice 2 for the db caches (with the cache-central lane) | −120 | config dials; one cache | cache-central's own checks |
| 9+ | D10, D11, D12, D13: each is its own reviewed design (Astra review), cross-owner callers | −980 | — | platform tier |

**Totals.** Slices 1–7 are about −340 lines, all inside db.clj and its tests. Slices 8+ are about −1,100 more, which
matches A23's target of about 3,500 lines and then about 2,500.

**Before/after.** db.clj is 5,015 lines today. It is about 4,675 after slices 1–7, and about 3,600 after 8–9.

## Defects found (no source edit made)

- **Arity gate `or` query: 1.2–2.4 s cold per derivation** (P1). This is the direct cause of the 8–10 s `transact!` /
  `with` maxima.
- **Every defn is validated by the writer twice** (P6, turn owner).
- **Every value is validated twice per write** (D10): pre-flight, then the final report.
- **`validate-write-report` puts a config dial into the projection map** (P4).

Existing tasks cover P2 (`arity-caches-copy-the-indexes`), P4 (`the-checked-writer-is-slow-on-a-fresh-branch`), P5
(`the-query-wrapper-costs-36x-datahike`) and D1 (`deletion-error-has-no-caller`). P1 and P6 have no task yet. Root
decides whether to file them; no task EDN is written in plan-only mode.
