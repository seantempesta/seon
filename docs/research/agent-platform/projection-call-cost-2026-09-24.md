---
type: research
status: read-only root-cause study; no source edited
created: 2026-09-24
lane: research-projection-call-cost (Opus 5.5)
pins: seon HEAD bc9348422; live JVM pid 99265 (2026-09-24T18:13:37Z)
tags: [agent-platform, schema, projection, instrument, performance]
---

# Projection call cost: why every change pays whole-program work (2026-09-24)

Research lane `research-projection-call-cost`. This lane was read-only. The probes ran through MCP
`eval_clj` (JVM mode, root `/Users/sean/src/seon`, cluster `default`, session `projection-cost`,
`(seon.cluster.boot/connection "default")`, JVM pid 99265 started 18:13:37Z). It redefined no Vars.
The source is at HEAD `bc9348422`. The probes did cycle the one-slot wrapper holders they reached
(see F2): the live system does the same on every projection switch, and the next call recompiles
the holder once.

**What exists.** A projection is derived from a database value (`db/carried-projection`) or built
by hand. It is then *bound* dynamically (`schema/call-with-projection`). Every armed call
*re-discovers* it, through argument metadata or the binding, and picks a compiled Malli wrapper for
it. **Approach:** pass one value per database value. Arm once. Let identity (`identical?`) hold
because the value is memoized, not because a stamp was attached.

## Fresh profile (`runtime_status`, default, 2026-09-24 ~19:15Z; all times inclusive)

| armed fn | calls | total ms | max ms |
|---|---:|---:|---:|
| `seon.schema/call-with-projection` (cell 1) | 348,005 | 4,920,126 | 143,473 |
| `seon.schema/call-with-projection` (cell 2, earlier digest) | 131,603 | 2,417,752 | 138,103 |
| `seon.db/call-with-custody` | 3,865 (419 threw) | 3,998,149 | 77,496 |
| `seon.schema/call-with-projection-state` | 3,268 (386 threw) | 3,605,877 | 77,402 |
| `seon.db/with-declarations` | 15,026,970 | 2,569,149 | 4,296 |

There are 207 functions over one second. These totals nest: each is a *carrier*, and its total is
the body it wraps. The probes below separate each function's own cost from what it causes.

## Probes (warm, same db value, `[:find ?k :where [?e :seon.schema/key ?k]]` → 3,381 rows)

| form | ms per call |
|---|---:|
| raw `datahike.api/q` (query-cache hit) | 0.007 |
| armed `seon.db/q` (one `with-declarations`) | 0.113–0.135 |
| same, inside `call-with-projection p` (the same `p` each time) | 0.120 |
| same, inside `call-with-projection (assoc p ::probe (rand))` (a **fresh** projection each call) | **2.748** |
| q on plain db + q on db stamped with an equal-content `p2`, alternating, inside `p` | **1.665** per q |
| `db/carried-projection pc-db` (memo hit) | 0.015–0.027 |
| `db/carry-derived-projection pc-db` | **463.2, 459.3** (never `identical?` across calls; forms `=` carried) |
| `schema/canonical-data-string` over the 3,381-row result | 8.37 (20,287 armed recursive calls) |
| `pr-str` of the same value / `hash` | 1.32 / 0.16 |

## (1) What each does per call, what it is proportional to, and why it runs so often

- **`call-with-projection`** (`schema.clj:1272-1278`) is a `binding` of `*projection*` and nothing
  else. Its own cost is **≈7 µs** (armed contract plus binding: 0.120 − 0.113 ms). It is O(1). It is
  called per operation *and per entity*. For example, `render/walk.clj:552,723` re-binds for each
  walked entity. `render.clj:156,355,1043` and `db.clj:1584` (`ask-declarations`, once per
  declaration question) re-bind too, and so do about 40 more sites. It exists because callees
  locate the projection ambiently (`handed-projection` `schema.clj:3745`, `candidate-forms`
  `schema.clj:1227`) instead of taking it as an argument. The 4.9 Ms inclusive total is its bodies:
  turns, publications and walks.
- **`with-declarations`** (`db.clj:1544-1567`) runs once per `q`/`pull`/`datoms` read (`db.clj:998,
  1077, 2341, 2493, 2719, 2773`). `read-declarations` (`db.clj:1509`) builds a `delay` of
  `carried-projection`. The delay is forced only on a decode. The function's own cost is small. The
  per-read overhead of the whole armed read path is **~0.11 ms against 0.007 ms raw (≈17×)**. That
  overhead is spent in wrapper dispatch, `aligned-query-arguments` and `query-call-valid?`. Its call
  count is proportional to **reads**. The publication issue measured 866,175 calls for 143,224 pulls.
  `fn/published-index-rows` (`fn.clj:3312-3360`) pulls *every program identity* one by one
  (`db/pull database '[*] identity`, `fn.clj:3339`, plus a `datoms` and a second pull when
  `omitted`). So a publication's reads are proportional to the **whole program**, not to the change.
- **`canonical-value-string`** (`schema.clj:846`) is a private function with a contract, and it
  recurses through its own armed Var. Every element pays one wrapper: 20,287 calls for a 3,381-row
  result, about 6× `pr-str`. It is reached per read by `db/read-result-digest` (`db.clj:596-607`),
  which digests the whole result. It is reached per row by `program/definition-digest`
  (`program.cljc:394`) and by sort keys (`program.cljc:748`, `fn/schema_shape.clj:49`). The open
  item's 723k calls / 9 s (≈12 µs per call) is this pattern.

## (2) Which caches exist, what keys them, and why they miss

| cache | key | holder | why it misses |
|---|---|---|---|
| `projection-cache` LRU 24 (`db.clj:1279`) | declaration-attribute revisions (`projection-cache-key` `db.clj:1290`), commit id, declaration content (`db.clj:1315`) | `carried-projection` `db.clj:1433` | This cache is **correct and content-keyed**. It misses only when declarations change, and then it pays `schema/load-projection` (483 ms, P2). |
| `value-projection-cache` LRU 4 (`db.clj:1283`) | the revision key of a speculative or as-of value | same | It misses per written declaration (bounded). |
| `projection-cache-value` (`schema.clj:421`) | a key inside **one projection object's** `:seon.schema.projection/compiled` atom | per projection | It is keyed by projection *identity*. A fresh projection (from `carry-derived-projection`, an `assoc`, or a delta) starts empty and recomputes every validator, every `edn-encoded?` answer and `write-report-validator`. |
| instrument one-slot `supplied-wrapper` (`instrument.clj:825-852`) | `identical?` to the **last** projection seen by this Var | per armed Var | **This cache thrashes.** `supplied-projection` (`instrument.clj:551-568`) sniffs every argument's meta `:seon.schema/projection`, every argument's `:seon.schema/projection`, and `:seon.env/environment`, and then falls back to `handed-projection`. A projection that is not identical, or two projections alternating, runs `compiled-wrapper` (`instrument.clj:648`) again: `m/schema` + `m/-instrument` per switch. Measured: **2.75 ms per q with a fresh projection and 1.67 ms per q when two alternate, against 0.12 ms** (14–23×). |
| `carry-derived-projection` meta stamp (`db.clj:278-298`) | none: it reads `(meta database)` | the db value's metadata | **There is no memo.** It rebuilds from `packaged-forms` + `projection-from-database` + delta + `materialize-projection` on every unstamped value: **460 ms**, never identical, with forms equal to `carried-projection`. The stamp then feeds the thrash above, because meta is checked *before* the binding. Caller: `cluster/source.clj:612`, `previous-database` of each publication. |

Commit id against content: the durable memo is already content-valid (`declaration-content-key`
reads every datom of `schema/projection-attributes`, ~5 ms). The misses do not come from commit ids.
They come from **objects built outside that memo**, which break `identical?` for every
identity-keyed layer downstream. That breaks the law "Derive without stamps".

## (3) Do the one-happy-path replacements remove each cost?

| replacement (README row) | removes | expected number |
|---|---|---|
| **carried-projection instead of `carry-derived-projection`** (row 57) | the 460 ms rebuild per publication, plus the downstream wrapper thrash caused by its stamp | 460 ms → 0.015 ms. Identity is preserved, so no recompile. |
| **projection passed explicitly** (row 3; contracts table row 3) | `call-with-projection`/`-state`, `handed-projection`, the classpath fallback `packaged-forms`, and the per-entity re-binds | Only about 7 µs per wrap is directly its own cost. The real saving is removing the ambient second source that alternates with arguments' projections. Together with row 11, this removes the 1.5–2.6 ms per armed read under a switch. |
| **arm once per adoption** (contracts table row 11: delete `supplied-projection`, the `supplied-wrapper` holder, `validates-loaded-contract?`) | per-call sniffing and recompile-on-switch | every call becomes `boot-wrapper`: q 1.67 ms → ~0.11 ms under alternation. It deletes ~100 lines. |
| **Malli lazy registry over rows** (row 1) | the 483 ms `load-projection` on each declaration-touching commit, and `projection-fingerprint` (the whole-population `canonical-value-string` walk) | 483 ms → 12.4 ms (P2, 5,364 declarations). It does **not** touch per-read or per-row costs. |
| none of the above | per-row reads in `published-index-rows`, per-read `read-result-digest`, and armed recursion in `canonical-value-string` | These need F4 and F5 below. |

## (4) The smallest ordered fixes (each ≤100 added lines)

1. **F1: `carry-derived-projection` → `carried-projection`. Delete, don't tune.** Owners:
   `cluster/source.clj:612` (one caller) and `db.clj:278-298`. Pass `(d/db connection)` to the
   ordinary `carried-projection` path. Delete the function and `sci/eval.clj:2019`'s stamp once no
   reader of `(meta db) :seon.schema/projection` remains. This adds 0 lines and deletes ~25. It saves
   ~460 ms per publication with a prior commit, and the stamp-induced recompiles. Verify: run
   `refresh-source!` on one file and compare the profile of `call-with-projection` and
   `with-declarations` before and after.
2. **F2: arm once. Delete per-call projection selection. Delete, don't tune.** Owner:
   `instrument.clj:544-568, 738-765, 825-852`. The wrapper calls `boot-wrapper` directly. It adds
   0 lines and deletes ~100. It saves 1.5–2.6 ms per armed call whenever two projections interleave:
   every wrapper reached by reads on previous, commit, as-of or test-branch values inside another
   binding. Risk (UNVERIFIED, as recorded in the contracts table): some caller may rely on the
   supplied wrapper for a branch whose contracts differ. The law already rules that such a branch
   interprets in SCI. First count the resets: add a disposable counter on the probe branch, never on
   default.
3. **F3: fix the `canonical-value-string` recursion cost.** Owner: `schema.clj:835-896`. Two options:
   - Replace the digest with `hasch` (README step 3). Delete, don't tune.
   - As an interim step, if hasch is not ready: make the recursion a local `letfn` behind the one
     contracted `canonical-data-string`. This adds ~5 lines and removes one armed call per element
     (20,287 → 1 per digest). It saves ~6 ms per 3k-row read digest and ~9 s of the 723k-call idle
     profile.
4. **F4: stop reading the whole program per publication.** Owner: `fn.clj:3312-3360`. First, pull
   all identities in one `db/pull-many` (or one `q` with a pull expression) instead of one pull per
   identity. This adds ≤30 lines. Expected: the 143k pulls, and most of the 866k
   `with-declarations`, collapse to about 1 per attribute. The ~0.11 ms per-read overhead × 866k is
   ~24.6 s (matching the issue). Then restrict it to the changed files' identities: this is the
   "Split analysis from the prior-row reads" lead in
   `docs/seon/issues/a-full-reanalysis-publication-takes-twenty-five-seconds.md`.
5. **F5: explicit transport sweep.** Owner: `schema.clj:1227-1290, 3745` and about 75 sites (the
   `rg` list in this lane: `render/web.clj` ×7, `cluster.clj` ×11, `render/walk.clj` ×2, and
   others). Do it as one scripted sweep after F2. **Delete, don't tune**
   `call-with-projection`, `call-with-projection-state`, `handed-projection` and the
   `packaged-forms` fallback. It is net-deleting (~650). Its direct saving is small (~7 µs × 480k
   wraps ≈ 3.4 s in this profile). It removes the ambient source F2 would otherwise still need.
6. **F6: lazy registry** (row 1, task `:seon.schema.task/projection-is-a-lazy-registry`). Owner:
   `schema.clj`. One `(projection forms contracts)` constructor of ~40 lines. It deletes about
   1,260 lines. It saves ~470 ms per declaration-touching commit. It is last because it only
   affects the memo-miss path that F1–F4 leave.

## Unresolved and limits

- The profile is cumulative over the JVM's life, and concurrent lanes add to it. This lane has no
  per-publication run of its own; "a small publication" (×12,143 / ×22) is quoted from the
  assignment.
- This lane did not verify how often live paths actually alternate projections. The thrash
  mechanism and its cost are measured. Its share of the 4.9 Ms total needs the F2 reset counter.
- `call-with-projection-state` (`schema.clj:1280`) is UNVERIFIED as a source of a fresh projection
  per evaluation: the evaluation-local delta may build one. If it does, it hits the same one-slot
  thrash, and F2 covers it.
- The two `call-with-projection` profile cells mean the Var was re-armed with a new digest. The old
  cell's counters are history, not current callers.
