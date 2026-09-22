---
type: reference
status: audit; read-only, no source/test/JVM touched
created: 2026-09-23
tags: [agent-platform, audit, datahike, malli, sci, deletion, stamps, caches]
---

# "Datahike already does it" — the stamp-and-mirror inventory

All citations are read at `f0c4564a6` (`git show f0c4564a6:<path>`), and
`reference-code/datahike` at its gitlink `41c79c1a`. Working-tree line numbers
differ: lanes were editing `src/seon/db.clj` during this audit, so every row
below is pinned to the HEAD blob, not to the tree. No file was edited, no test
or JVM was run, and no `eval_clj` probe was needed to establish a citation.
`DH/` = `reference-code/datahike/src/datahike/`.

## Summary (ten lines)

1. 26 rows: **7 DELETE, 9 REPLACE, 10 KEEP**.
2. Estimated removal of rows NOT already owned by a plan entry: **≈ 366 source
   lines** plus one schema family (`:seon.operator.lock/*`).
3. The largest single shape is today's instance repeated: a value that the
   dependency can derive is **stamped onto a database value's metadata** and
   then has to be re-stamped onto every derived value (`db.clj:251-310`,
   `:2519-2557`, and ten `vary-meta` sites in seven namespaces).
4. The second shape is **a hand cache with a hand stamp** where
   `clojure.core.cache.wrapped` is already on the classpath because Datahike
   requires it (`DH/schema_cache.cljc:2,8-29`).
5. The third is **a lock beside the writer**: `cluster.clj:1522-1584` serialises
   publication with a `ReentrantLock`, a holder atom and an acquisition timeout,
   while its own docstring says Datahike's expected-head guard is the fence.
6. The fourth is **identity Seon computes where Datahike already has one**:
   `basis-t`/`max-tx` compared as if it were an identity (`env.clj:116-132`,
   `turn.clj` `:seon.cluster.eval/read-basis-transaction`), and a SHA-256 taken
   of a git commit id (`test/cache.clj:242`).
7. Ten rows are KEEP, and three of them are the model to copy, not a defect:
   `schema.clj:351-420`, `instrument.clj:900-903`, the three `d/listen` sites.
8. Habit 1 (fetch-at-call) produced 11 rows, habit 2 (silence-as-health) 5,
   habit 3 (fix-at-site) 6, habit 4 (retire-without-convert) 4.
9. 9 rows are already scheduled (A1 win 1/2, A2 c1/c2/c9/c12, §6.3); §4 names
   them so the orchestrator adds rows without duplicating.
10. Nothing here contradicts the A2 mechanism table; every row extends it.

## 1. The inventory

Sizes are `defn`-span line counts at HEAD. "Seam guarantee" is what the cited
dependency block actually promises, read this session.

| # | mechanism | Seon `file:line` (size) | what it does | dependency seam + its guarantee | verdict | smallest change | plan row |
|---|---|---|---|---|---|---|---|
| 1 | **projection stamped on the database value** | `src/seon/db.clj:251-272` `carry-projection-state` (22), `:274-295` `carry-derived-projection` (22), `:296-310` `resolve-database-value` (15), `:247` `alter-meta!` on the connection's `:wrapped-atom`; ten restamp sites: `db.clj:270,271,294,306`, `schema/datahike.clj:409`, `fn.clj:3374`, `cluster/source.clj:189`, `sci/eval.clj:854,1811,2868` — ≈ 90 | derives the compiled projection for a db value and hangs it in that value's metadata, then every function that mints a derived value must re-hang it | `DH/schema_cache.cljc:2,8-29`: Datahike keeps its own derived-from-the-value state in an LRU `clojure.core.cache.wrapped` cache keyed by a schema-meta key, never on the value; `DH/query.cljc:2568-2590` gives the per-commit key (`:cache-context`, changed attributes only). `clojure.core.cache` and `core.memoize` are on the classpath (verified: `clojure -Spath`) | **REPLACE** | the projection becomes `(memo cache-context → projection)`; the stamp and all ten restamps go | **ruled 2026-09-23** (commit `2b3f4f728`); A1 slice |
| 2 | temporal views re-merge the stamp | `db.clj:2519-2557` `database-view` (39), `vary-meta … merge (meta database)` at `:2533` | `history`/`as-of`/`since` copy the source value's whole metadata map so the stamp survives the view | `DH/versioning.cljc:499` `branch-as-db`, `:469` `commit-as-db` return values that already carry `:cache-context`; nothing else needs carrying | **DELETE** (with row 1) | delete the `vary-meta … merge`; keep `append-database-evidence!` | new, with row 1 |
| 3 | encoded-operation metadata stamp | `schema/datahike.clj:310-318` `encoded-operation-key`/`encoded-operation?`/`mark-encoded` (9) | marks transaction data "already encoded" in metadata so the codec does not run twice | exists only because the EDN codec exists; `DH/schema.cljc:87` `:db.type/any` + `DH/db/transaction.cljc:33` `validate-val` store the value | **DELETE** | leaves with the codec | **A2 c2** — extend its size table by these 9 lines |
| 4 | read currency: a revision map copied out of the cache context | `db.clj:878-903` `dependency-revision` (26) | rebuilds `{connection-id, generation, attribute-revisions, conservative-revision}` from `(:cache-context db)` into a Seon-shaped map for storage | `DH/query.cljc:2963` `source-context-unchanged?` is the fork's own predicate over two contexts; `:2906` `dependency-plan-attributes` supplies the plan | **KEEP** | the stored map must be Seon-shaped because it is a durable fact; the comparison should call the fork's predicate | **A2 §2(a)** — already scheduled |
| 5 | the other two currency arms | `db.clj:1083-1100` `index-evidence-current` (18) + the replay arm | walks history datoms / re-runs the read and digests the result | same seam as row 4 | **DELETE** | — | **A2 c1** — already scheduled |
| 6 | a second basis-t beside the revision | `turn.clj:124-126, 1381, 1647, 2026-2036, 2213, 4812` — `:seon.cluster.eval/read-basis-transaction`, ≈ 20 | stores the read's basis transaction on the evaluation and carries it forward | `DH/query.cljc:2568-2590`: currency is attribute revisions per commit; `max-tx` answers no currency question and is not unique across branches | **DELETE** (after A2 c1) | drop the attribute and its six sites with the c1 evidence narrowing | **NEW** — B2, pairs with A2 c1 |
| 7 | packaged declaration population cache | `schema/edn.clj:342-366` `declaration-stamp` (25), `:368` `defonce` atom, `:370-383` `packaged-population` (14) with `locking`, `:385-402` `forget-packaged-population!` (18) — ≈ 60 | hand LRU-of-one: hashes resource file name/length/mtime into a stamp, compares it under a monitor, plus an explicit "forget" hatch whose docstring says it exists only so a measurement can observe a first read | `clojure.core.cache.wrapped/lookup-or-miss` (used by `DH/schema_cache.cljc:8-29` for exactly this: derived state keyed by a stamp, with no `locking` and no forget-hatch) | **REPLACE** | one `cw/lookup-or-miss` keyed by the same stamp; the monitor and the hatch go (≈ −30) | **NEW** — A1 |
| 8 | process-global environment declaration delay | `env.clj:150-185` `declared-member-rows` (36), `:186` `(def declared-members (delay …))` | reads `:seon.env/environment` from packaged forms once per JVM because `packaged-forms` re-reads and re-merges every resource when no projection is bound (its own comment) | the recomputation it caches is row 7's; after row 7 there is nothing to cache. Law "values carry their world": the constructor is already handed a projection | **REPLACE** | take the projection as an argument; delete the delay and the "read once" comment | **NEW** — A1 (order after row 7) |
| 9 | projection staleness decided by `basis-t` | `env.clj:116-132` `advance-projection!` (17) | swaps the environment's projection only when the supplied `basis-t` is `>=` the retained one | `DH/api/impl.cljc:371` `commit-id`; `db.clj:2560` `database-identity` already returns `{:db-name :t :datahike/commit-id}`. Two branches of one store can share a `max-tx`, so `<=` on it is not an ordering of database values | **REPLACE** | compare the committed value identity, not `max-tx` | **NEW** — B2/D1 |
| 10 | owning-instance search by connection identity | `oversight.clj:48-53` `connection-identity` (6), `:55-61` `running-instances` (7) via `ns-resolve`, `:63-73` `owning-instance` (11) — 24 | re-derives `{connection-id, generation}` from a db value and then scans a late-resolved global registry to find which cluster instance owns it | `DH/connections.cljc:3` `*connections*` is already keyed by connection id and `:5-9` `active-connection` answers it; and the caller of an oversight check already holds the handle (law "values carry their world") | **DELETE** | pass the instance/environment into the oversight call | **NEW** — B2 |
| 11 | publication serialised by a second lock | `cluster.clj:1522-1527` monitor, `:1528-1529` holder atom, `:1531-1537` bound+view, `:1539-1584` `with-source-refresh-monitor!` (46) — ≈ 63, plus the `:seon.operator.lock/*` schema members it writes | a `ReentrantLock` with a holder record, a phase string, an acquisition timeout and a typed timeout refusal, serialising analysis+publication in one JVM | `DH/writing.cljc:875-890`: `:datahike/expected-basis-t` is a full-head precondition refused typed inside the writer; `DH/versioning.cljc:323-352` `force-branch!` takes `:expected-current-commit` and refuses a stale plan. The monitor's own docstring (`cluster.clj:1523-1526`) says the expected-head guard "remains the cross-plan correctness fence" | **REPLACE** | publication passes the expected head; the loser takes Datahike's typed refusal. Lock, holder atom, phase rows, timeout and the lock schema go | **NEW** — B1 (aligns with deep-review win 3, which removes the *other* lifecycle lock) |
| 12 | branch-open liveness pre-check | `store.clj:532` monitor, `:534-566` `open-branch!` (33) — the `contains? @connections/*connections*` arm | refuses `::branch-already-open` before calling `d/connect` | `DH/connections.cljc:11-…` `release-connection-reference!` removes the map entry only at the END of a drain, so `contains?` is true through it — the 2026-09-22 probe failed | **KEEP** | none; the seam lacks "one owner only", which Seon admits deliberately | **A2 §6.3 / c12** — already ruled KEEP |
| 13 | reopen re-reads `:keep-history?` from a second store | `store.clj:534-545` (the `get-in @main-connection [:config :keep-history?]` arm) | pre-reads stored config to build the reopen configuration | `DH/connector.cljc` store-fixed record keys are adopted when omitted and refused typed on conflict; `:keep-history?` is not yet in that set | **REPLACE** | fork f8 adds the key; the pre-read goes | **A2 c12 / f8** — already scheduled |
| 14 | branch-head pre-reads before GC | `maintenance.clj:607-621` `collection-evidence` (15), `:622-633` `branch-reopens?` (12) | reads each branch's commit id, then re-reads it to confirm it did not move | `DH/gc.cljc:125-169`: the sweep takes a reachability permit, independently marks current heads, and fences the sweep by cutoff; `:89` states the write-before-cutoff rule | **DELETE** the re-read | keep the evidence row; delete `branch-reopens?` where the permit already fences | **A2 c9** — extend (c9 names `commit-present?` only) |
| 15 | render walk's hand entity cache | `render/walk.clj:429-482` `acquire-entity` (54) | a shared atom keyed `[::entity-pull lookup]`, guarded by pull-plan identity, by `read-evidence-current?`, and by a monotonic `:seon.render.call/basis-transaction` compare-and-set | the same shape row 1 was ruled on: a pure function of the value, memoizable through `clojure.core.cache.wrapped` keyed by `:cache-context` (`DH/query.cljc:2568-2590`) plus the pull plan | **REPLACE** | one memo keyed by `[cache-context plan lookup]`; the basis-t guard and the CAS go (≈ −30) | **NEW** — A2/B2, after row 1 lands |
| 16 | fabricated transaction report across two commits | `cluster/source.clj:194-222` `changed-identities` (29) | builds `{:db-before :db-after :tx-data (d/datoms (d/since (d/history after) …)) :tempids {} :tx-meta {}}` and hands it to `fn/report-identities` as if it were a report | the real report comes from `transact`; for two arbitrary commits `DH/versioning.cljc:191` `branch-history` and `:469` `commit-as-db` give lineage, and `d/since`+`d/history` give the datoms — but not a report. `:tempids {}`/`:tx-meta {}` are fabricated | **REPLACE** | `report-identities` takes `db-before`, `db-after` and datoms; no caller may read the fabricated members | **NEW** — B1 |
| 17 | SHA-256 over a git commit id | `test/cache.clj:218-243` `gitlink-digests` (26), specifically `:242` `(sha-256 (.getBytes (second tokens)))` | re-hashes each submodule gitlink commit id into a SHA-256 string | git already computed a content identity for that submodule: the gitlink IS the identity, recorded by `git ls-files -s` | **REPLACE** | use the commit id as the digest (zero hashing) | **NEW** — B4 |
| 18 | file content digests for gate inputs | `test/cache.clj:39-44` `sha-256`, `:67-83` `file-input-digests` (17), `:154-166` `input-roots` (13), `:260-267` `input-digests` (8) | hashes every declared input file to decide gate input change | git's index holds a content id for tracked files, but not for a dirty working tree, and the gate must see uncommitted edits | **KEEP** | none; name the guarantee git lacks in the docstring | **NEW (doc only)** — B4 |
| 19 | schema-shape rows / authored-shape atom | `program.cljc:222` `(defonce !authored-shapes (atom nil))`, and the shape family | a stored structural mirror of compiled Malli schemas | the compiled registry answers structure (`m/children`, `m/entries`, `m/-function-info`) | **DELETE** | — | **deep-review win 2** — already scheduled (A1 + B1) |
| 20 | ambient projection dynamic vars | `schema.clj:892-895` `*candidate-forms-overlay*`, `*projection*`, `*projection-state*`, `*packaged-forms*` | thread-bound transport for the projection | the value carries it (after row 1, the memo derives it) | **DELETE** | — | **deep-review win 1** — already scheduled (A1-3/A1-4) |
| 21 | per-call `memoize` over `db/pull` | `fn.clj:2825`, `:2971`, `:3087` | dedupes pulls inside ONE call's `let` | `clojure.core/memoize`, used exactly as intended: call-scoped, discarded with the frame, no invalidation question | **KEEP** | none | — |
| 22 | projection-owned compiled cache | `schema.clj:351-356` `compiled-function-arities`, `:357-390` `with-compiled-cache` (34), `:397-420` `projection-cache-value` (24) | hangs derived compiled products on the immutable projection instance; absent holder still answers correctly | this IS Malli's pattern (`m/validator` is a pure function of the schema it was compiled from) and the model rows 1, 7 and 15 should copy | **KEEP** | none — cite it as the reference implementation | — |
| 23 | Malli's own function-schema registry | `instrument.clj:900-903` `(def function-schemas* @#'m/-function-schemas*)`, `:919`, `:947` `restore!`, `:979` | reaches Malli's one JVM-wide registry atom through its var and restores it | Malli's registry, used as the registry; no second registry is created | **KEEP** | none | — |
| 24 | structural registry for syntax preparation | `schema.clj:1279-1292` (14) | a `reify mr/Registry` over `mr/fast-registry` + `m/default-schemas` that keeps references opaque | Malli's own registry protocol and `fast-registry`; no parallel registry, and its docstring names what it cannot prove | **KEEP** | none | — |
| 25 | transaction notification | `eval/drive.clj:64`, `schedule.clj:790`, `cluster/wake.clj:505` — three `d/listen` sites | registers report callbacks on the connection | `DH/core.cljc:200-218` `listen!`/`unlisten!` — the seam, adopted. `flow.clj:679` `add-watch` is on an internal submissions atom, not a database mirror | **KEEP** | none | — |
| 26 | subprocess silence bound | `cluster/process.clj:217-252` (≈ 36: `add-watch`/`remove-watch` on a progress atom, a two-deadline `waitFor` loop) | bounds a child by "no progress for N ms" in addition to a deadline | `babashka.process` supplies `destroy-tree` and `.waitFor`, not a silence bound; the bounded-execution law requires a declared bound | **KEEP** | none; the second bound is accretion but it is the declared bound of an unbounded surface | — |

## 2. Top ten deletions, with the probe that proves the seam

Ordered by lines removed. Each probe names a form and the expected result; none
was run (this audit is read-only).

1. **Row 1, projection stamp — ≈ 90 lines.** Probe: `(let [c (seon.cluster.boot/connection "default") db (seon.db/db c)] [(keys (meta db)) (:cache-context db)])` — expected: the metadata carries `:seon.schema/projection`, and `:cache-context` is present and non-nil on the SAME value, i.e. the memo key already exists on the value the stamp is glued to. Seam: `DH/schema_cache.cljc:8-29`.
2. **Row 11, publication lock — ≈ 63 lines + a schema family.** Probe: two `transact!` calls with `:datahike/expected-basis-t` set to the same stale basis; expected: the second refuses typed with `:datahike/expected-basis-t` in its data (`DH/writing.cljc:882-890`), which is the refusal the lock's timeout currently substitutes for.
3. **Row 7, packaged-population cache — ≈ 30 net.** Probe: `(clojure.core.cache.wrapped/lookup-or-miss (clojure.core.cache.wrapped/lru-cache-factory {}) :k (constantly 1))` → `1`, and a second call with a throwing thunk → still `1`, proving miss-once without `locking`.
4. **Row 15, render walk cache — ≈ 30 net.** Probe: pull one entity twice across an unrelated commit; expected: `(:cache-context db)` differs only in `:datahike.cache/commit-id` while `:datahike.cache/attribute-revisions` for the pulled attributes is unchanged (`DH/query.cljc:2568-2590`), so a memo keyed on those revisions hits — the same fact the CAS guard approximates.
5. **Row 8, environment declaration delay — ≈ 36.** Probe: after row 7, time two consecutive `(seon.schema.edn/packaged-forms)` calls; expected: the second is cache-resident, so no process-global delay is needed to avoid the re-read the comment cites.
6. **Row 10, owning-instance search — 24.** Probe: `(keys @datahike.connections/*connections*)` beside `(seon.db/database-identity db)`; expected: the connection id in the identity is a key of that map, so the linear scan over `running-instances` answers a question the dependency's own map answers by lookup.
7. **Row 17+18, gate digests — ≈ 25.** Probe: `git ls-files -s reference-code/datahike` → mode `160000` and the gitlink commit id; expected: identical to what `gitlink-digests` hashes, showing the SHA-256 adds no identity.
8. **Row 6, read-basis-transaction — ≈ 20.** Probe: probe B of the A2 spec (`db.clj:878` revision equality over retained reads) run twice across an unrelated commit; expected: revision equality answers current while `basis-t` has moved — the stored basis-t cannot distinguish the two.
9. **Row 16, fabricated report — ≈ 12 net.** Probe: assert that no reader of `report-identities` reads `:tempids` or `:tx-meta`; expected: zero hits for those keys in `fn/report-identities` and its callees — then the report shape is unnecessary.
10. **Row 14, GC head re-read — 12.** Probe: `DH/gc.cljc:125-169` reading — the permit is acquired before reachability and released after the sweep; expected: no window in which a branch head can move between evidence and sweep, so `branch-reopens?` proves nothing the permit does not.

Rows 2, 3, 5, 13, 19, 20 also delete lines but land inside slices the plan
already owns (§4).

## 3. The pattern

Habit 1, fetching at call time instead of holding the value, produced rows 1, 2,
7, 8, 10, 15, 20, 21, 22 and the two currency rows 4 and 5: a function that was
not handed the projection, the declaration population, the pulled entity or the
owning instance recomputed it, someone cached the recomputation, and the cache
then needed a transport — metadata on the value, a `defonce` atom under a
monitor, a dynamic var, or a late `ns-resolve`. Habit 2, reading silence as
health and then hardening against it, produced rows 11, 12, 14, 26 and the
`forget-packaged-population!` hatch inside row 7: a wait hung so a timeout
appeared, a race happened so a lock appeared, a head might move so a re-read
appeared, and the cache became unobservable so an escape hatch appeared beside
it. Habit 3, fixing at the site instead of the owner, produced rows 2, 6, 9, 13,
15 and 16 — the temporal view re-merging metadata because its caller needed the
stamp, a basis-t stored on the evaluation because one comparison wanted an
ordering, a report shape fabricated because one callee's signature asked for a
report. Habit 4, retiring without converting, produced rows 3, 19, 20 and 6:
each is a mechanism whose reason has already been ruled away while its writers
and its stored attribute remain. Underneath all four, the dependency's source
was not the first read: `DH/schema_cache.cljc` is 42 lines and shows Datahike
solving row 1, row 7 and row 15 with one library it already puts on the
classpath.

## 4. Already scheduled vs NEW

**Already scheduled — do not open new rows.**

| audit row | owning plan entry |
|---|---|
| 3 (encoded-operation stamp) | `lane-a2-datahike-one-answer.md` §5 c2 and §2(b) — extend the c2 size table by 9 lines |
| 4 (dependency-revision) | A2 §2(a), row "the comparison" |
| 5 (index/replay arms) | A2 §5 c1 |
| 12 (branch-open pre-check) | A2 §6.3 — ruled KEEP 2026-09-22 |
| 13 (`:keep-history?` pre-read) | A2 §5 f8 + c12 |
| 14 (GC head re-read) | A2 §5 c9 — **extend**: c9 names `commit-present?` only, not `maintenance.clj:622-633` |
| 19 (schema-shape family) | `deep-review-wins-2026-09-21.md` §1 row 2 (A1 + B1) |
| 20 (ambient projection) | deep-review wins §1 row 1 (A1-3/A1-4) |
| 1 (projection stamp) | owner ruling 2026-09-23, commit `2b3f4f728`; A1 slice |

**NEW — no plan row exists.**

| audit row | proposed owner | net lines |
|---|---|---|
| 2 temporal-view metadata re-merge | with row 1 (A1) | −5 |
| 6 `:seon.cluster.eval/read-basis-transaction` | B2, same slice as A2 c1 (schema change ⇒ RESET) | −20 |
| 7 packaged-population cache → `core.cache.wrapped` | A1 | −30 |
| 8 environment declaration delay | A1, after row 7 | −36 |
| 9 projection staleness by `basis-t` → commit identity | B2/D1 | −8 |
| 10 oversight owning-instance search | B2 | −24 |
| 11 publication `ReentrantLock` → expected head | B1 (beside deep-review win 3) | −63 + schema |
| 15 render walk entity cache | A2/B2, after row 1 | −30 |
| 16 fabricated transaction report | B1 | −12 |
| 17 SHA-256 of a gitlink | B4 | −25 |
| 18 gate file digests | B4, docstring only | 0 |

**Limits of this audit.** Every line count is a `defn` span, not a measured
diff; call-site counts for row 1 are `rg` hits (`vary-meta|alter-meta!` over
`src/`), classified by reading each. No probe in §2 was executed, so each is a
proposal, not evidence. Rows 21-26 are KEEP decisions recorded so the same greps
are not re-run: they are the classified remainder of the `memoize|cache|registry`,
`listen|watch|add-watch`, `lock|locking`, `m/schema|registry` and
`sci/fork|copy-var` censuses. `retry|backoff|sleep` was run across 14 files and
produced no row: no hit sits beside a Datahike connection or writer retry.
