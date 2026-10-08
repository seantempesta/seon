---
type: research
status: proposed
date: 2026-10-08
lane: design-cost
---

# Whole-program work per call: census and one mechanism (design-cost, 2026-10-08)

**What exists.** Every Seon derivation that reads the program (reach index, declaration projection, population
targets, task edges, test selection) is a function of a database value. Its cache key, where it has one, is either
the whole input (forms maps, file text) or Datahike's per-attribute content revisions (`seon.db/content-revisions`,
built on our Datahike fork's `:datahike.cache/attribute-revisions`, `datahike.db/advance-cache-context`), stored in
`seon.cache` (Caffeine). **Data flow:** eval request → recording transaction → test step (`seon.turn/test-step`:
`run-proofs`, `run-reaching`) → reply; `assign!` → `seon.test/verified?` → `seon.test.runner/reach-digests` →
`reach-graph` → `reach-attribute` (one whole-attribute map per attribute revision). **Principal approach:** a
derivation is keyed by the content revisions of what it reads (exists), and, when the key misses, is *advanced* by the
datoms of the commits between its last entry and the requested value (does not exist) instead of being recomputed
from the whole program.

Pins: Seon `59c3c85b4`, Datahike fork `86c3301a`, Malli fork `418df441`. Live measurements on default (agent
`design-cost`, JVM mode; a disposable agent `design-cost-probe` for the `:ping` passes), 2026-10-08. `seon.profile`
counters are JVM-wide, so call counts include concurrent work; each number below was repeated until stable.

## 1. Census

"Now ∝" is what the cost follows today; "should ∝" is what the work must do. Expected costs are derived from the
work, not targets.

| # | Operation (owner) | Measured | Now ∝ | Should ∝ / expected | Incremental key | Library seam |
|---|---|---|---|---|---|---|
| C1 | `assign!` of a proof with evidence: `seon.test/verified?` → `seon.test.runner/reach-digests` | 5.9 s, 4.2 GB, 3.3 M armed calls cold (root, 2026-10-07) | program: `reach-graph` rebuilds from `reach-attribute` maps of 10 whole attributes; any `defn` changes `:seon.program/definition-digest`'s revision, so every map and the graph re-derive | the test's closure on a miss, O(1) on a hit; a `defn` costs its reverse closure. Expected: tens of ms after a `defn`, < 1 ms unchanged | per-attribute content revision (exact hit); on a miss, advance the attribute maps by the commits' datoms of those attributes; per strongly connected component, a Merkle digest keyed by its members' leaves and its children's digests | Datahike attribute revisions + committed report datoms; Caffeine via `seon.cache` |
| C2 | `:ping` test step with nothing changed: `seon.turn/run-reaching` | 48 ms of a 124 ms `:ping` (32 of 94 ms quiet): `seon.test/changes` 25 ms, of it `digest-differences` 22 ms; `seon.test/run` → `selection-admission` 21 ms for an empty selection | program: two reads of every definition digest (12,381) decoded through `seon.db/q`, then a test request admitted with nothing in it | the branch's program revisions vs those its last test step answered: equal → nil. Expected < 0.1 ms (`content-revisions` is 76 µs armed today, O(attributes)) | the branch's program content revisions, recorded on the step's answer | Datahike attribute revisions |
| C3 | `:ping` reads: 128-138 reads (57 `pull`, 46-54 `q`) per one-form pass; `seon.db/q` 55 ms over 54 calls under load | one-clause `seon.db/q` 49 µs vs `d/q` 2.2 µs (22×), 49 armed calls; `seon.db/pull` 36 µs vs 8 µs; `carried-projection` 3 µs, 79 times in one pull | reads × per-read fixed machinery (declarations, projection key per attribute, read evidence, encode/decode) | per read: one projection resolution, one declarations table. Expected within 2-3× of Datahike's own call | the database value's commit id + revisions: resolve the projection once per read, not per attribute | Datahike query cache already answers the repeat |
| C4 | Decoding query results: a 5,816-row `seon.db/q` | 5 ms vs 3 µs for Datahike's cached answer | result size, per call, although Datahike cached it | only attributes whose stored form differs need decoding; a query over natively typed attributes decodes nothing. Expected near Datahike | the query's attribute set + declarations' revision → "needs decode?" | W-24 (`seon.db/datoms` decodes every datom) is the same class; lane datoms-decode in flight |
| C5 | Checked writer: `transaction-report-datom?` 560 calls for one `:ping` transaction; 21 k for 157 datoms per `defn` | 14 MB per `defn` (PB-10) | report × three validations × nesting depth | the report's datoms once | none needed: validate once, hand a checked value on (focused-instrumentation.md, I6) | — |
| C6 | `seon.db/declaration-content-key` per `defn` (PB-08, W-7) | ~30 k declaration datoms copied, 17.8 MB | program | the report's declaration datoms. Expected KB | basis revision key + the report's declaration datoms (the delta) | Datahike `speculative-cache-context` already gives the speculative value fresh revisions only for touched attributes |
| C7 | `my.program/breaks` on a namespace (PB-15, W-19) | 5.5-5.9 s; 1,812,010 `database-value?` calls in one old call | namespace's functions × gate-set walk | one walk seeded with all of them | — | fixed on a branch (one walk); lands as PB-15 |
| C8 | `my.program/remove-ns!` | 2 GB (root) | not diagnosed; likely two whole projections and a whole reach rebuild around one retraction | the namespace's declarations + their dependents' reach | delta (retractions are datoms too) | — |
| C9 | `seon.task/task-edges` | whole-population or-queries | program | fixed `1e4acaa9e` (plain indexed queries) | — | — |
| C10 | Reach cache keyed per branch (LR-BRANCHREACH) | branch values differ from main on all 10 reach attributes; `:seon.test.runner/reach-declarations` 0 hits in 9 misses, 86 MB, 2.3 s per load | branches × program | branches of one program share main's entries; a branch adds its own commits as a delta | content revision resolved through the branch's source commit | Datahike fork branch metadata (`datahike.versioning/branch!`, `parent-commit-ids`) |
| C11 | Whole-program cache entries per adoption (PB-14, W-17) | +129 MB in 4.5 min; `:seon.task/tasks-tx` ~10 MB per entry; its key holds the forms map and every task file's text | program per adoption, and hashing that key per lookup is O(program) for each new map instance | the changed declarations | content digests of the inputs (one digest per file/declaration, already stored) instead of the inputs themselves | `seon.cache` |
| C12 | Schema-only derivations re-derived per projection (PB-07b, W-9) | 25-37 MB, 49 k extra armed calls on the pass after a `defn` | projections | schema forms | `[forms admissions]` digest, as `seon.program/program-attributes` does | `seon.cache` |
| C13 | Program change walks every Var (PB-11, W-12) | 10 MB per `defn`, 30 MB on the pass after | Vars of the JVM | the agent's Vars + changed definitions | the changed definitions (delta) | SCI `sci/fork` (0.6 KB) |
| C14 | A check compares every definition digest twice; world build per check (W-25, W-27) | 146-212 ms world build; 2.74 GB for a two-test check | program per check | the check's members' reach | reach digests (C1) and the clean-world key (EN-7) | — |

Health observations from the same window (not ranked above, recorded as findings): `seon.cache/cache?` 6.4 M calls
(1.0 s total) and `seon.schema/pull-selector?` 306,701 calls (1.47 s) are flagged untested-hot; both are predicates
called from schema validation (focused-instrumentation.md, F1). `seon.schema/call-with-projection-state` mean 4.07 s
over 863 calls and `seon.sci.eval/call-detecting-program-change` mean 2.07 s over 535 calls are over-bound wrappers
whose callees are the derivations above.

**Reading the census.** C2, C3 and C4 are paid on *every* eval request; C1, C6, C10, C12, C13 on every `defn` or
proof assignment; C11 on every adoption. In the quiet `:ping` (94 ms) the test step is about a third, the recording
transaction another third, and reads most of the rest; armed validation is a few ms of it (focused-instrumentation.md
§1). The large costs are algorithmic: derivations whose key or whose recomputation follows the program.

## 2. One mechanism: derivations keyed by what they read, advanced by what changed

Several rows share one shape: a pure function of a few attributes' datoms, recomputed in full when any datom of those
attributes changes (C1, C6, C10, C11, C12, and C2 as the trivial "nothing changed" case).

**Algorithm.**

1. A derivation declares the attributes it reads and `derive : database → value`; optionally
   `advance : value, delta → value`, where `delta` is the added and retracted datoms of exactly those attributes.
2. **Key** = `[name arguments (seon.db/content-revisions database reads)]`. Exact hit → done. Cost O(|reads|).
3. **Miss with a predecessor.** `seon.cache` also holds the derivation's latest entry per lineage,
   `[name arguments :latest lineage] → [revisions value t]`. If `advance` exists and the delta between that entry's
   `t` and the requested value is available, `value' = (advance value delta)`. Cost O(delta × advance).
4. **Delta source.** Within one connection: the committed reports Datahike already delivers
   (`datahike.committed-report`, which `d/listen` consumers read; Seon's `seon.cluster.wake` already listens), retained
   per connection as a bounded log of `[t attribute-datoms]` in `seon.cache` (weighed by datom count). For a
   speculative value: the transaction's own `:tx-data` (`speculative-cache-context` already names the touched
   attributes). Across branches: main's entry advanced by the branch's own commits since its fork point
   (`datahike.versioning/branch-history`). A delta that is evicted or spans a schema change (Datahike advances the
   conservative revision there) falls back to `derive`.
5. **Otherwise** `derive` (today's behavior).

**Guarantee.** The answer equals `derive` on the requested value: the revision key names exactly the datoms read
(the fork's contract for `advance-cache-context`), and `advance` is checked against `derive` by a property test per
derivation (generated deltas; bounded so every seed passes). **Cost.** Unchanged: O(|reads|). After a `defn`:
O(changed datoms × local work). Cold: unchanged. **What disappears.** Per-`defn` whole-attribute re-reads
(`reach-attribute`), whole-program keys (C11), per-branch duplicates (C10), and the `:ping` test step's selection.

**Library check.** Datahike supplies the key (attribute revisions), the delta (committed reports, `:tx-data`,
branch history) and nothing else: it has no incremental view maintenance. posh/re-posh-style tx-report filtering is
invalidation only, which the revisions already give. Differential dataflow engines exist outside Clojure; adopting
one would be a second evaluation model and is rejected. Caffeine stores both the exact and `:latest` entries, weighed
in bytes; no atom.

**Where it lives.** One function in `seon.cache` (or `seon.db`, since it calls `content-revisions`): a lookup taking
`{:reads :derive :advance}`. Each owner converts its own derivation in its slice; no registry of derivations.

### Options (simplest viable first)

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| A. Exact reuse only: key every derivation by `content-revisions` of its reads; answer "nothing changed" steps from their record (C2) | unchanged inputs cost O(attributes) | one slice per owner, small | every `defn` still recomputes whole-attribute derivations (C1's 2.3 s reach load, C6's 17.8 MB) |
| **B. A + delta advance for the derivations every `defn` invalidates (recommended)** | same answers; a `defn` costs its delta | A plus `advance` for 4 derivations (reach attribute maps, reach component digests, declaration projection, population targets) and the bounded delta log | an `advance` per derivation is code that must equal `derive` (property-tested); delta log memory, bounded by a dial |
| C. Entity-granular revisions in the Datahike fork | keys follow entities, not attributes | fork change in the index write path; per-entity revision storage | write-path cost and storage on every transaction; still needs recomputation of the derived value |

## 3. Slices (ordered by measured waste; each about 15 minutes; proofs count work, not time)

1. **S1 — An unchanged branch's test step selects nothing** (`seon.turn/run-reaching`, `seon.test/changes`). Record
   the branch's program content revisions on the step's answer; equal revisions answer nil before `changes`.
   Proof: `seon.turn-test/an-unchanged-pass-selects-no-tests` — a second `:ping` on one branch calls
   `seon.test/select` zero times (`seon.profile/measure` `:called`). Measured parent 32-48 ms per pass.
2. **S2 — Branches share main's reach entries** (LR-BRANCHREACH; fork + `seon.db/content-revision`). Proof: the
   row's hit-rate check (two branches of one program: second load is a hit). Measured parent 2.3 s, 86 MB per miss.
3. **S3 — Reach attribute maps advance by delta** (`seon.test.runner/reach-attribute`, `reach-graph`). Proof:
   `seon.test.runner-test/a-defn-advances-reach-by-its-datoms` — after one `defn` on a branch, `reach-attribute`
   derives zero whole attributes; equality with `derive` as a property. Measured parent: C1 (5.9 s cold).
4. **S4 — Component digests reuse by content** (`reach-component-digests`): a component's digest cached by its
   members' leaves + children's digests, so a `defn` re-hashes only its reverse closure. First measure Tarjan +
   hashing on default's 9,165 names / 104,761 edges; skip the slice if under 20 ms. Proof: hashed components after one
   `defn` = components reaching it.
5. **S5 — PB-08 by this mechanism** (`seon.db/declaration-content-key`): key = basis revisions + report's declaration
   datoms. Proof: the row's. Measured parent 17.8 MB per `defn`.
6. **S6 — PB-14 per kind**: replace whole-input keys by stored digests. Proof: the row's diagnosis table, bytes per
   adoption per kind. Measured parent +129 MB / 4.5 min.
7. **S7 — One projection resolution per read** (`seon.db/carried-projection`, `projection-cache-key`). Proof: one
   `seon.db/pull` calls `carried-projection` once (79 today). Measured parent 36 µs per pull vs 8 µs.
8. **S8 — Decode only what needs decoding** (`seon.db/decode-query-result`). With lane datoms-decode (W-24): a query
   over natively typed attributes returns Datahike's answer. Proof: `digest-differences` over 12,381 digests ≤ 2× raw
   (0.4 ms raw, 22 ms today).
9. **S9 — `remove-ns!` diagnosis** (C8): allocation profile, owner table, then rows. Measured parent 2 GB.

PB-10 (C5), PB-07b (C12), PB-11 (C13) and EN-7/W-25 (C14) keep their own cards; B is the algorithm their keys should
use.

## 4. Open owner questions

1. Option B adds an `advance` function beside each converted derivation (four at first). Accept that code, guarded by
   an equality property against `derive`, or stay at option A and accept a whole-attribute recompute per `defn`?
2. The delta log: retain recent committed reports' datoms per connection in `seon.cache` (bounded by a new dial,
   weighed by datoms), or read deltas from Datahike's history index on demand (no retention, slower per miss)?
3. S2 needs our Datahike fork to expose a branch's source commit for revision resolution. Approve the fork change in
   the FK-DH batch?
4. S4 is conditional on a measurement; may the lane skip it when the whole-graph hash is under 20 ms?
