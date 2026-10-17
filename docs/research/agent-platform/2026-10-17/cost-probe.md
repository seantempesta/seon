---
title: "Cost probe (wave 2): generative growth profiles"
type: research
status: prototype committed; wave 3 priced (2026-10-17)
---

# Cost probe (wave 2): generative growth profiles

Lane cost-wave2, 2026-10-17. Prototype: `seon.cost.growth`, with tests in `seon.cost.growth-test` (check run
55d86425eada, 5 deftests green, 10.3 s). It feeds the owning plan, [cost evidence](cost-evidence.md), and its
default cost contract. The word is recorded in [vocabulary](../../../seon/architecture/vocabulary.md) as "growth
profile, growth class".

## What it does

`seon.cost.growth/database-growth` and `seon.cost.growth/input-growth` work in four steps.

1. **Count each call.** `seon.cost.growth/counted` wraps `seon.profile/measure` and records:
   - armed calls;
   - `seon.db` read calls;
   - thread-allocated bytes;
   - `seon.cost.growth/elements` of the value: the call's output N, where a database value counts as 1.
2. **Find what the call reads.** `seon.cost.growth/touched-attributes` binds `seon.db/*read-evidence-sink*` around one
   call and unions `datahike.api/dependency-plan-attributes` over the evidence. A read whose plan widens to `:all`
   (pull `*`, entity) is counted as widened.
3. **Pad the database in two dimensions.** Both are padded with `datahike.api/with`:
   - **Untouched:** `seon.cost.growth/pad-untouched` adds P datoms on a new attribute. The comparison is against a
     value padded by one datom.
   - **Touched:** `seon.cost.growth/pad-touched` clones every entity that holds a touched attribute, 1× and 3×, which
     gives 2T against 4T touched datoms. Unique values are renamed, refs inside the clone set stay inside the clone,
     and `seon.config.*` facts are not cloned.

   Every measured value is fresh, so a commit-keyed cache misses on all four. One warm-up call on another fresh value
   comes first, so the pad attribute's one-time schema projection is not charged to either measured value.
4. **Fit the growth per dimension.** The exponent is `ln(measure ratio) / ln(size ratio)`, with classes at 0.2, 0.7,
   1.4 and 1.8. The per-unit cost is `Δmeasure / Δsize`.

`input-growth` generates S argument lists from the `:=>` contract with `malli.generator/generate`, at size s and 4s.
The input's N is the summed elements of those arguments.

Measured on default, agent cost-wave2's branch: 723,227 datoms, untouched pad P = 361,613 (size ratio 1.5).

## Results

| Function | Expected | Probe answer | Match? |
|---|---|---|---|
| `seon.fn/derive-gate-edges` (16 attributes, T = 28,701) | linear in the program, flat in the rest of the store | **untouched:** calls 590→590, reads 22→22, alloc 99→72 MB: constant.<br>**touched:** calls 3,075→3,210 (k 0.06), reads 18→28 (k 0.64), alloc 115→200 MB (k 0.79, linear, 1,469 B per touched datom) | yes |
| `seon.test/reach-walk` (20 attributes, T = 174,398) | linear in the program (the walk from every destructive owner), flat in the rest | **untouched:** calls 2,991, reads 222, alloc 108→80 MB: constant.<br>**touched:** alloc 113→200 MB (k 0.82, linear); reads 20→30 | yes for the classes, with one anomaly (below) |
| `seon.context/current-state` (22 attributes, T = 1,869; 4 reads widened) | flat in the store: agent-scoped indexed reads | **untouched:** calls 3,528, reads 20, alloc 31→28 MB: constant.<br>**touched:** calls 3,493, reads 20, alloc 19.7→19.6 MB: constant.<br>N = 58 output elements, so 60 steps, 0.34 reads and 340 to 530 KB per element | classes match. The constant is the overrun `seon.cost-estimate-test` already carries (24 MB estimate) |
| a whole-index count, `(count (seon.db/datoms db :eavt))` (test fixture) | linear in every datom: the lazy rescan | untouched alloc: linear (`seon.cost.growth-test/a-rescan-grows-with-untouched-datoms-and-a-lookup-does-not`) | yes |
| an indexed `:db/ident` lookup (test fixture) | flat | untouched reads constant; alloc sublinear on a fresh store of a few thousand datoms (overhead noise) | reads yes; alloc is a near false positive |
| `seon.cost.growth/elements` (pure, generated `[:vector [:vector :int]]`) | linear in its input | alloc linear; calls constant (below) | alloc yes; calls is a false negative of the counter |
| `seon.test/destructive-reach` | after bebd51673: flat when the change leaves its closure untouched | not measured. A call on a held value is a `seon.cache` hit and records no reads (0 attributes), so the probe saw nothing to pad. Its derivation, `seon.test/reach-walk`, is the row above | does not fit as built |
| `seon.cluster.source/merge-base` | grows with commit-graph depth, cached by the commit-id pair | does not fit. Its size is the commit graph, which `datahike.api/with` never extends, and its commit-id arguments cannot be generated (random ids take the refusal path) | does not fit |
| `seon.db/pull-logical` | linear in the pulled entity's attributes | does not fit. Its arguments (projection, callable, declaration vector) have no generator, and it is private behind `seon.db/pull` | does not fit |

**Anomaly (reach-walk).** reach-walk made 222 reads on the untouched values but only 20 to 30 on the touched clones.
The clones probably send it down a different path, such as an early refusal on a cloned `:seon.fn/destroys` owner.
Treat its touched judgement as unverified until the clone keeps the walk's path.

## Judged against the default cost contract

The default contract is `k × N + c` on steps, reads and allocation, where N is inputs plus output and a database
counts as 1.

- **Correctly refused:**
  - The rescan fixture: N = 1, alloc ∝ datoms.
  - `seon.context/current-state`: flat, but 60 steps and about 0.4 MB per output element. That is a constant c far
    above any small default, the same overrun the cost-estimate table carries.
- **Wrongly refused, so needs an override:** `seon.test/reach-walk`. It is a truly open-ended batch over the program:
  its output (tests reaching destructive owners) is small while its reads and allocation follow the program. Its
  override would read "grows with program identities". bebd51673 moved it off the per-change path, and that, not a
  bigger k, is the right answer.
- **Wrongly passed:**
  - `seon.fn/derive-gate-edges` is linear in its output (every identity), so the default passes it. Its defect is
    how often it runs: whole-program work per change. A per-call contract cannot see call frequency; the request-level
    estimate table (`seon.cost-estimate-test`) can.
  - Any function that over-returns: N includes the output, so returning the whole index buys its own allowance.
  - Any cached function: the probe measures the hit (destructive-reach).
- **Correctly passed:** the lookup and `elements`.

## Coverage

All `seon.*` contracted Vars on default 056b43883, 3,705 in all, classified by `seon.cost.growth/generatable`'s rule.
The classification constructs generators only and never samples. It ran as root JVM read-only queries in batches of 600
contracts, 32 ms per batch.

| Kind | Count | Meaning |
|---|---|---|
| generated | 2,427 (65%) | every argument has a generator |
| database | 869 (23%) | a database value plus generated arguments |
| connection | 101 | needs a connection: a write, or reads through the bound connection |
| none | 162 | an argument has no generator (`:fn`, opaque host objects, callables) |
| no-arguments | 104 | nothing to vary |
| invalid | 42 | the contract does not compile in the registry, 31 of them in `seon.schema*` |

Areas with the most database-taking functions are `seon.cluster` (129), `seon.test` (96), `seon.db` (94),
`seon.sci` (81), `seon.turn` (81), `seon.render` (62), `seon.task` (61) and `seon.context` (42).

Some classes do not fit:

- **Write functions.** 442 names end in `!`, and 310 of them are "generated". Generated inputs must never be driven
  into them on a live connection.
- **Generated values.** For the 869 database functions, a generated value is an arbitrary id, so the call mostly
  measures the refusal path. Ids must be sampled from the database instead.
- **Test namespaces:** 339 Vars.

## False positives and negatives seen

1. **Cold one-time work.** The first untouched value paid the new attribute's projection: 99 MB against 72 MB. The
   warm-up call fixes this.
2. **Small-store noise.** On a fresh store, per-call overhead moves allocation enough to classify an indexed lookup as
   sublinear. Thresholds on allocation need N large enough that k × N dominates c.
3. **Singleton facts cloned.** Cloning configuration made `seon.context/current-state` refuse
   (`seon.batch/cost-diff` got a nil `min-calls`). `seon.config.*` is now excluded. Any other one-per-cluster entity
   would behave the same.
4. **Cache hits.** `seon.test/destructive-reach` recorded no reads, so it had no touched attributes.
5. **Outermost-entry counting.** `seon.profile/measure` counts outermost entries, so recursion (`elements`) shows
   constant calls. Steps need per-entry counts or SCI step counts.
6. **No datom count.** Reads are `seon.db` entrance calls, not datoms, and allocation stands in for passes until wave
   1 adds datoms read.

## Wave 3 design, priced

**Measured unit costs:**

- **`datahike.api/with`:** about 9 µs and 31 KB allocated per added datom (37.5 / 31.5 / 31.0 KB at 1k / 10k / 40k).
  Filed as `seon.db/a-datahike-with-allocates-thirty-kilobytes-per-datom`.
- **Generator construction:** about 0.05 ms per contract.
- **A database probe on default:** 3.3 to 9 s and 12 to 24 GB allocated per probe at P = 361k. One unbounded sweep
  (3,591 contracts sampled at size 2 in one request) ended default with an OutOfMemoryError (pid 56939, 18:14). So no
  probe may run on default, and every probe states its bounds.

**Bounds per `defn`.** Only the defined function is probed, never a sweep:

| Bound | Value |
|---|---|
| Contracts | 1 |
| Generated samples | S ≤ 8 per size, 2 sizes |
| Database calls | 5 (1 warm-up, 4 measured) |
| Padded datoms | ≤ 60,000 |
| Untouched base | a small value: fresh store + the touched entities, ≤ 10,000 datoms, padded 2×, never the live store at 1.5× |
| Touched clones | ≤ 4 × min(T, 10,000) datoms |
| Write functions (`!`) and connection functions | never probed generatively; live evidence only |

**Estimated cost of one probe:**

- **Pure function:** about 5 ms plus 16 calls of f; allocation is f's own.
- **Database function:** ≤ 60k datoms × 9 µs ≈ 0.55 s and ≈ 1.9 GB, plus 5 calls of f.

Padded values are shared. A seon.cache entry keyed by (base commit id, P) or (base commit id, attribute set) serves
every function and agent on the same commit. That makes a repeat probe about 5 calls of f and keeps the retained
padding near 100 B per datom. The probe's own cost is a row for `seon.cost-estimate-test`: 0.6 s, 2 GB cold,
5 calls warm.

**Options:**

1. **Synchronous for generated pure functions only (recommended).** These are the 2,427 functions (65%) with every
   argument generated and no database. `defn`'s reply carries the growth class per measure against the default
   `k × N + c`: a refusal when an exponent breaks it, otherwise a finding with the measured k. Database functions are
   probed asynchronously by a Flow `:compute` proc after the reply, on a bounded small base; the next reply carries
   the finding.
   - Guarantee: immediate rejection for most definitions, and the lazy rescan caught within one turn.
   - Cost: about 5 ms per pure `defn`, ≤ 0.6 s off the reply path per database `defn`.
   - Sacrifice: database rescans are reported a turn late.
2. **Everything asynchronous.**
   - Guarantee: no added latency.
   - Sacrifice: no rejection at `defn`, only a finding.
3. **Everything synchronous on the small base.**
   - Guarantee: immediate rejection for every class.
   - Cost: up to 0.6 s and 2 GB per database `defn` on the reply path, against a 1 s ordinary bound.

**Prerequisites before any option:**

- Sample ids from the database for id arguments.
- Measure the derivation instead of the cache for functions read through `seon.cache/lookup`.
- Add per-entry step counts and wave 1's datoms read.
- Read override metadata ("grows with program identities") before judging.
- Add a request-level frequency check, which the per-call default cannot replace.

## Findings filed

- `seon.db/a-datahike-with-allocates-thirty-kilobytes-per-datom`.
- `seon.sci.eval/a-def-of-a-database-value-is-reinterpreted-with-its-render`. On this branch, each eval request
  re-ran top-level defs and resolved a database def to its render map.
- The default OutOfMemoryError above was reported to root as RESET NEEDED. Default was restarted on 056b43883.
