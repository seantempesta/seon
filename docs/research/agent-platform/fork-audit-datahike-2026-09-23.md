---
type: reference
status: audited; read-only lane, textual evidence only (no Datahike tests run against upstream)
created: 2026-09-23
tags: [agent-platform, datahike, fork, audit, upstream]
lane: fork-audit-datahike (Opus 5.5), read-only audit
subject: reference-code/datahike (fork seantempesta/datahike) against upstream replikativ/datahike main
fork-head: 84a20308
upstream-head: 7f39cccc (fetched read-only 2026-09-23, 0.50 s)
merge-base: 85c40aee (2026-07-14, "docs: state the writer model", #880)
---

# Datahike fork audit — every fork commit, keep or remove

Owner (2026-09-23): "look for more custom shit we introduced and analyze if it's stupid and if
it is rip it out and fix the seam on our end", and "Konserve has had some legit bugs we've solved,
but I think agents have been lazy and just hacking their own easy solutions rather than doing
things properly."

The test for every row: **KEEP** means a real Datahike bug, fixed where it starts, with a
reproduction. Each KEEP says whether to offer it upstream as a PR (the owner decides).
**RIP** means a quick workaround for one Seon caller, a change that bends Datahike's contract,
or a copy of something Datahike already does. The row names the proper way: Datahike's intended
API or the Seon-side change. **UPSTREAMED** means upstream has since fixed the same problem, so we
take upstream's version.

## Counts

| | Fork commits since the merge base (merges excluded) |
|---|---|
| Total | **115** (4 upstream merges excluded). All authored by Sean Tempesta's agents. **None were sent upstream.** |
| KEEP | **19**: 11 KEEP+PR, and 8 KEEP-verify planner fixes whose symptom upstream may have fixed separately |
| RIP | **74** |
| UPSTREAMED | **22** |
| HIGH (durability, retention, error handling) | **31** rows, marked **H** |
| Fork src lines | +6,038 / −1,055 over 43 files (`git diff --stat 85c40aee HEAD -- src`) |
| Upstream since the merge base | 165 commits; src +32,470 / −4,215 over 112 files |

**Proof boundary.** This audit is textual: `git log`/`show`/`grep` over both heads, a dry-run
`git merge-tree` (0.55 s), and `rg` over Seon's `src/ test/ script/ bin/`. It ran no Datahike
tests against upstream. The rules forbid a fork-test JVM without the owner's authorization, so
"upstream fixed it" means a cited upstream commit on the same symptom, or the same code still
present at upstream's HEAD (the file:line is given). Every probe was under one second: the fetch
took 0.50 s and merge-tree 0.55 s. The per-commit `git grep` loops were not timed one by one.

## Findings that change what we believe

1. **`:cache-context`, attribute revisions and `committed-value-identity` are not Datahike's.**
   They appear 0 times in upstream src and 0 times at the merge base. The fork has 36, 3 and 5
   occurrences (`git grep -c`). AGENTS.md "No stamps" says identity comes "from Datahike (commit
   id, `:cache-context`, attribute revisions)". Two of those three are fork inventions. Seon keys
   derived caches on them in `src/seon/db.clj` (54 references), `src/seon/test/runner.clj` (14),
   `src/seon/sci/eval.clj` (14), `src/seon/render.clj` (9) and `src/seon/call_preparation.clj` (8).
   Upstream's mechanism for the same job is `datahike.dependency-tracking`: snapshot dependency
   tokens for projection caches, from upstream `1ea8972a` (#1083) and `doc/dependency-tracking.md`.
   AGENTS.md makes a false current claim here. The owner or orchestrator should correct it. This
   lane does not hold AGENTS.md.
2. **Upstream's query-result cache already does what the fork's cache family was built for.** It
   is keyed by `[(:hash db) (:max-tx db) (:max-eid db)]` (upstream `query.cljc:3170-3173`). A
   content hash means every branch at one commit shares one bucket, which is what `684d3290` built
   by hand. Upstream also stores per-entry attribute dependencies and carries entries forward on
   commit when their attributes are untouched (`5e3dd26a`, #931). It keeps a running O(1) bucket
   weight, and its key is `(vec (rest args))`, which retains no database (`84a20308` restored this).
   The fork replaced that design with about 1,400 lines: generation-scoped identity, single-flight
   (`query/single_flight.cljc`, 560 lines), host admission and a committed-report source
   (`committed_report.cljc`, 350 lines). Seon calls none of single-flight, admission, committed
   report or capabilities (`rg` over `src/`). The retention study found 0 flights and 0 report
   sources live (datahike-memory-retention-2026-09-23.md, "Ruled out").
3. **Seon calls `d/release-materialized-db` 19 times, and every call does nothing.** Seon declares
   no secondary index: `:db.secondary/only` appears only in the bridge
   `src/seon/schema/datahike.clj:156-175`, and no schema resource declares it. So each call
   releases nothing (`versioning.cljc` `release-materialized-db`, from `04213e17`). The Proximum
   dependency in Seon's `deps.edn:40-43` is never loaded by Seon code.
4. **Upstream already has the right mechanism for four fork inventions:**

   | Fork invention | Upstream mechanism | Upstream source |
   |---|---|---|
   | Blob reachability permits (`gc_guard.cljc`) | `:db.type/store-ref` ("the database is the root set"), durable GC roots and the sweep floor | `11426b97`; `ae659349`, `6934bde1`, `244988d0`, `ffa9f98d` |
   | Per-transaction `:datahike/validate-report` | Store-level transaction predicates on the resolved report | `datahike.tx-preds`, `register-tx-pred!`; `bbbf4569`, `12d9d992` |
   | Branch roster lock | Fenced branch heads | `5acabc13` |
   | Connection opening reservation | `reserve-connection!` / `abandon-reservation!` | `connections.cljc:108-134` |

5. **Datahike's writer names Seon attributes.** `7e1af7dd`, `006e634a` and `6dd49e5e` make
   `writer.cljc:85-110` select `:seon.cluster/name`, `:seon.test/sym`, `:seon.test.run/id` and
   `:seon.error/id` out of exceptions and tx-meta. They also drop `:data` from every logged cause.
   A dependency must not know its consumer. The log also drops the ex-data that the Seon error
   policy requires to be kept.

## Rows by theme

Columns: commit · what it changes (fork file:line) · the problem and Seon's caller · upstream
since the merge base · verdict and the proper way. **H** = touches durability, retention or error
handling.

### A. Commit and write path, writer error handling

| Commit | Change | Problem · Seon caller | Upstream | Verdict |
|---|---|---|---|---|
| `cae24611` **H** | `writer.cljc:20-48,156-230`: fail pending transactions and commits on a fatal exit; always rethrow | Accepted writes never resolved after a writer death. No particular Seon caller: every `transact!` | Fixed: upstream `writer.cljc:561-624, 895-914` closes the queues and fails every staged callback | **UPSTREAMED** |
| `67934f65` | `db/transaction.cljc:372`: `:tx-data` holds only effective datoms | A no-op re-assertion appeared in `:tx-data`. Seon reads receipts | `08c2ad81` (#946) "A no-op re-assertion changes nothing" | **UPSTREAMED** |
| `ca4383dc` **H** | `writing.cljc:786`: `:datahike/expected-basis-t` precondition, `:transaction/stale-basis` | Optimistic head check. Callers: `src/seon/plan.clj:1323`, `src/seon/cluster/source.clj:749-838`, `src/seon/db.clj:4498` | None identical. Datahike's own means is a transaction function, which runs serialized in the writer | **RIP**: prepend `[:db.fn/call seon.db/basis-guard t]` in Seon's transact seam (`db.clj:4498`); the fn throws when `(:max-tx db)` differs |
| `ea6e9881` **H** | `writer.cljc:114`: stale-basis refusals not logged | Log noise from `ca4383dc` | n/a | **RIP** with `ca4383dc` |
| `9c356e32` **H** | `writer.cljc:105-114`: never rewind the threaded uncommitted `:max-tx` to the connection's lagging value | A pipelined transaction's basis moved backwards while its predecessors awaited batch commit | Still present upstream: `writer.cljc:445-451` rewinds whenever `(not= …)` in non-shared mode | **KEEP+PR** (reproduction: the fork's pipelined expected-basis test in `9c356e32`; offer upstream without the expected-basis dependency) |
| `c1527273` **H** | `writer.cljc:85`: bounded one-line refusal log | Huge tx-data in error logs | Upstream still logs `:args` (`writer.cljc:533`) | **RIP** (already replaced in the fork by `7e1af7dd`) |
| `7e1af7dd` **H** | `writer.cljc:85-110`: log selects `:seon.*` identity keys and drops `:data` | Seon wanted run identity in Datahike's log. Seon side: the fault recorder | n/a | **RIP**. Datahike logs as upstream does. Seon records its own fault with identity at `seon.db`'s transact catch (error policy). A generic "do not log tx args" change can be offered upstream separately |
| `006e634a` **H** | `writer.cljc:90`: reads `:seon.error/data` | Same as above | n/a | **RIP** |
| `6dd49e5e` **H** | `writer.cljc:90-160`: deliver the refusal before formatting the diagnostic | Formatting the Seon-keyed log could throw | n/a (upstream's log is a plain map) | **RIP** with `7e1af7dd` |
| `73afe782` **H** | `db/transaction.cljc:1224-1295`: `:datahike/validate-report` callback in tx-meta | Seon's write-report validation: `src/seon/db.clj:4251` (`write-report-validator projection`) | Upstream `datahike.tx-preds` (`register-tx-pred!`), a store-level predicate on the fully resolved report (`bbbf4569`, `12d9d992`) | **RIP**. After the re-fork, Seon registers one tx-pred per store that derives the projection from `:db-after` (a function of the value, no stamp in tx-meta) |
| `e11845ba` **H** | `writer.cljc:393-430`: deliver the report, then notify listeners one by one with failures isolated | A throwing listener stranded the committed write's caller | Still present upstream: `writer.cljc:1377-1379` calls listeners before `deliver`, uncaught | **KEEP+PR**. Reproduction: a listener that throws means `@(transact …)` never returns. Seon regression `test/seon/cluster/listener_failure_test.clj` |
| `2cc313a6` **H** | `writer.cljc:382-420`: `:listener-failure` handler in connection meta, retire by identity | Seon wants failures of its own listeners stored and delivered. Caller: `src/seon/cluster/store.clj:621` | Upstream offers durable `:commit-listeners` with isolated failures (`1070bb75`) | **RIP**. Seon wraps its own callback at registration (catch, record the fault, `d/unlisten` by key). Sites: `eval/drive.clj:64`, `sci/eval.clj:2419`, `schedule.clj:799` |
| `131ca636` | `writing.cljc:863`, `writer.cljc:198`, `versioning.cljc:745`: merge shares the basis fence; parents stop leaking to the next operation | Seon merge in `cluster/source.clj:788,838` | Partial: upstream fenced heads `5acabc13` | **RIP** of the basis part with `ca4383dc`. The lineage part (merge parents threaded into the next operation) goes upstream as a PR only after it reproduces on upstream HEAD |
| `49ea5933` | `core.cljc:133`: detach speculative cache identity before tx fns run | Fork cache family | n/a | **RIP** (theme E) |

### B. Store, Konserve pins, history indexes

| Commit | Change | Problem · Seon caller | Upstream | Verdict |
|---|---|---|---|---|
| `9ada7550` **H** | `deps.edn:5`: pin fork Konserve (idempotent deletion) | Delete-store races | Konserve 0.9.388 and 0.9.389 delete-store fixes (`f443e551`, `86ff4f4d`) | **UPSTREAMED** |
| `256b714d` **H** | `deps.edn:5`: pin fork Konserve 737697d9 (ordered filestore multi-key batches) | Batch write ordering | Upstream Konserve f1d1be9: multi-key means atomic, and filestores must not implement it. Our Konserve already reverted it (`reference-code/konserve` 5b39fdd) | **RIP**. Pin Datahike's `deps.edn` to the reverted Konserve (held by store-damage) |
| `0e8601d7` **H** | `store.cljc:7-30`: remove the per-connection Konserve LRU wrapper | An empty LRU retained for every connection. Nothing reads through it (retention study, table row "Konserve read cache") | Upstream still wraps (`store.cljc`); its node-cache sharing `d2b9e525` changes the same file | **KEEP+PR** (a deletion; re-check against `d2b9e525` at the re-fork) |
| `cc2b2bc7` **H** | `connector.cljc:193-310,461`: adopt the stored `:keep-history?` at connect; check a shared connection's config | Connect without `:keep-history?` defaulted to true against a store created without history | Upstream `adopt-create-time-fixed` omits `:keep-history?` | **RIP**. Seon's single config owner (`src/seon/cluster/store.clj`) passes the config the store was created with. A lone `:keep-history?` adoption may still be offered upstream |
| `9fbb66fc` **H** | `index/persistent_set.cljc:154-170`: `temporal-upsert` tests `(some? old-datom)` rather than `old-val` | A false-valued card-one upsert lost its history retraction: `(if old-val …)` is false for `false` | Still present upstream: `persistent_set.cljc:225-228` `(if old-val` | **KEEP+PR**. Reproduction: keep-history db, `:flag false`, then `:flag true`; `(d/history db)` lacks the retraction of `false` (test in `9fbb66fc` `index_test.cljc`) |
| `96e465c3` | `writing.cljc:3`: adopt upstream's awaited delete lifecycle | Merge alignment | Upstream's | **UPSTREAMED** |
| `6c54b88a` | `writing.cljc:3`: revert unsafe active-connection delete | Merge alignment | Upstream's | **UPSTREAMED** |
| `92300d4e` | `api/types.cljc:101`, `readers.cljc:27`: align with the current API | Merge alignment | Upstream's | **UPSTREAMED** |

### C. Garbage collection

| Commit | Change | Problem · Seon caller | Upstream | Verdict |
|---|---|---|---|---|
| `56f1c621` **H** | `gc_guard.cljc:42-270`, `gc.cljc:120-190`, `versioning.cljc:174-380`: reachability permits so publication and collection exclude each other, plus `:datahike.gc/reachable-extension` | GC could sweep a just-published head or blob. Callers: `src/seon/blob.clj:315-321`, `src/seon/cluster/boot.clj:199-221`, `src/seon/cluster.clj:34` | Durable GC roots `ae659349`; settable sweep floor `6934bde1`; 15-minute default floor `244988d0`; long-running operations protect themselves `ffa9f98d`; blobs as `:db.type/store-ref` kept alive by the datom naming them `11426b97` | **RIP**. Seon blobs become `:db.type/store-ref` values; boot and publication rely on the sweep floor or a durable root, not a permit |
| `10540578` | `api/impl.cljc:336`: permit option arities | Same | n/a | **RIP** with `56f1c621` |
| `4605c12a` | tests: guarded force proof ordering | Same | n/a | **RIP** with `56f1c621` |

### D. Branching, versioning, connections

| Commit | Change | Problem · Seon caller | Upstream | Verdict |
|---|---|---|---|---|
| `d6a99f53` **H** | `connections.cljc:5-125`, `connector.cljc:2-250`: reference-counted connections, one opening reservation per key, release awaited | Concurrent connects and releases on one key. Caller: `src/seon/cluster/registry.clj:180` (`connections/active-connection`) | `reserve-connection!` / `abandon-reservation!` / `invalidate-store-connections!` (upstream `connections.cljc:108-151`); `48a461da`, `cecb85af` | **UPSTREAMED**. Convert `registry.clj:180` to upstream's lookup |
| `357ffc87` **H** | `versioning.cljc:171-205`: per-store-id lock around `k/update :branches` | Konserve's per-key lock lives on the store object (`konserve/core.cljc:126-154,194`), and each connection had its own store object | Fenced heads `5acabc13`; one store per physical key once `d2b9e525` is ported | **RIP**. Share one store per physical store, so Konserve's own lock serializes the roster |
| `fbd1ad2d` | `versioning.cljc:329`, spec `:1026`: `force-branch!` returns the commit id it installed | Callers identify their own update: `cluster/registry.clj:325`, `cluster/source.clj:494,661` | Returns nil upstream | **KEEP+PR** (accretive return value; no contract bent) |
| `5566ab13` | `versioning.cljc:5-215`, `api/impl.cljc:72`: `fork-database` | No Seon caller (`rg d/fork-database`: 0 hits) | n/a | **RIP** |
| `092f5b05` | `versioning.cljc:67`: keep cache identity for attached commits | Cache family | n/a | **RIP** (theme E) |
| `6f256908` | `versioning.cljc:70`: fence materialized cache revisions | Cache family | n/a | **RIP** (theme E) |
| `d7ac886f` | `versioning.cljc:10`: CLJ-only refer for release | Secondary lifetime | n/a | **RIP** (theme K) |

### E. Query-result cache and value identity (the largest family)

The proper way for the whole family is upstream's hash-keyed result cache with attribute-dependency
propagation (`query.cljc:3056-3300` at upstream HEAD, `5e3dd26a`, `091b9b34`). Seon's derived
caches move to upstream's dependency tokens (`1ea8972a`), and committed identity is read from the
value itself (`[store-id (get-in db [:meta :datahike/commit-id])]`).

| Commit | Change | Problem · Seon caller | Upstream | Verdict |
|---|---|---|---|---|
| `0b652215` **H** | `db.cljc:307`, `connector.cljc:9`, `lru.cljc:160`, `query.cljc:10`, `writer.cljc:6`: exact committed cache identity per connection generation | Cache keys by generation. Seon: `render.clj:788`, `call_preparation.clj:626`, `db.clj:868` | Hash-keyed snapshot cache | **RIP** |
| `acbc6f20` | tests: batched invalidation | Same | n/a | **RIP** |
| `a795dc37` **H** | `connector.cljc:483`, `writer.cljc:225`, `writing.cljc:545`: fence ambiguous cache propagation | Same | Upstream bounded commit propagation `5e3dd26a` | **RIP** |
| `999e26a2` | tests: scope isolation | Same | n/a | **RIP** |
| `f8192962` **H** | `query/single_flight.cljc:1-270`: query single-flight | Duplicate in-flight queries. No Seon caller | n/a (upstream clients collapse duplicates in their own LRU, `a18ce206`) | **RIP** |
| `7eb1b849` | `query.cljc:4184`, `single_flight.cljc:102`, `resource.cljc:170`: share bounded results | Same | n/a | **RIP** |
| `f3776b72` **H** | `query.cljc:2580`: share exact earlier as-of results | Same key path that retained args (retention study, section 3) | Upstream as-of is covered by the hash key | **RIP** |
| `0070d507` **H** | `query.cljc:2524-2600`, spec `:143`: cache by every database source; key kept the caller's arg array | Multi-source reads. This is the retention defect (datahike-memory-retention-2026-09-23.md §3) | Upstream key `(vec (rest args))` | **RIP** |
| `caf52685` | `lru.cljc:170`, `query.cljc:3005`: multi-source keys ordinary | Follow-up to `0070d507` | n/a | **RIP** |
| `0cf39e57` **H** | `query.cljc:2417`, `writer.cljc:221`, `writing.cljc:577`: lazy cache inheritance | Promoted entries copy the old key (retention study table) | Upstream propagation | **RIP** |
| `0c01b5fe` | `db.cljc:413`, `db/transaction.cljc:8`, `writing.cljc:580`: speculative values carry revision context | Revision caches missed on `with` values. Seon: `db.clj:1111,1357,1478` | Upstream tokens are per DB value and inherited by `d/with` (`doc/dependency-tracking.md`) | **RIP** |
| `684d3290` **H** | `lru.cljc:190`, `query.cljc:2432-2600`: share results by `[store-id commit-id]` across branches | Every branch stored its own copy | The upstream hash key shares by content already | **RIP** |
| `84a20308` **H** | `query.cljc:3171`: `(vec (rest args))` | Undoes `0070d507`'s retention | This is upstream's code | **RIP** (it disappears with the family; until then it is the needed fix) |
| `107bc8f6` | `db/transaction.cljc:748`: report purge invalidations | The fork cache missed purges | `18b7d248` purge fix; hash key changes on purge | **RIP** |
| `f9fc0940` | tests: CLJS cache lifecycle | CLJS | n/a | **RIP** |

### F. Read evidence, dependency plans and pull plans

The problem: Seon records which attributes each read touched ("read evidence"), so at turn start
it can refresh only the changed reads. Callers: `src/seon/db.clj:504-1141`
(`append-*-evidence!`, `read-evidence-changes`, `read-evidence-current?`),
`src/seon/call_preparation.clj:535-574`, `src/seon/render/web.clj:1614`, `src/seon/turn.clj:1367`
and `src/seon/cluster/wake.clj`.

The proper way: re-run each retained read against the new value. Upstream's cache carries an
unchanged read's entry forward on commit when none of its attributes changed, so the re-run is a
hit. Compare results, or use one dependency token per read group. This deletes the plan/evidence
API and Seon's evidence ledger. It needs a design note: its cost is O(retained reads) per turn,
each read O(1) on a hit.

| Commit | Change | Upstream | Verdict |
|---|---|---|---|
| `41764938` | `query.cljc:74`, spec `:316`: `query-attribute-dependencies` | upstream private `extract-query-attr-deps` (`query.cljc:3175`) | **RIP** |
| `0f40370e` | `query.cljc:2839`: `query-input-count` (Seon `db.clj:2150`) | n/a; Seon can count `:in` itself from `datahike.query/memoized-parse-query` | **RIP** |
| `b9a487f6` | `query.cljc:24-135`: parsed dependency projection | n/a | **RIP** |
| `6a0386d2` | `query.cljc:20`, spec `:523`: execution-aware dependency plans (`dependency-plan-attributes`, Seon `db.clj:880`) | n/a | **RIP** |
| `e37ae715` | `pull_api.cljc:18`, `query.cljc:2808`: pull dependency evidence | n/a | **RIP** |
| `2e6c7bcf` | `query.cljc:2890`: plan interpretation | n/a | **RIP** |
| `407e9328` | `pull_api.cljc:21`: component expansion in evidence | n/a | **RIP** |
| `b200902d` | `pull_api.cljc:18-160`: compile pull selectors once (`compile-pull-plan`, Seon `db.clj:706`, `render/walk.clj:396`, `schema.clj:1475,3534`) | upstream prepared execution `ee3937c3`, memoized analysis `091b9b34` | **RIP**. Seon memoizes `datahike.pull-api` parsing by selector value if a measurement shows the need |
| `15d98da6` | `pull_api.cljc:18`: keep dependency attributes in plans | n/a | **RIP** |
| `cdcb5792` | `pull_api.cljc:22-108`: shared selector DAGs | n/a | **RIP** |
| `e1d28437` | `query.cljc:76-270`, `committed_report.cljc:7`: schema dependencies and report readiness | n/a | **RIP** |

### G. Bounds, admission and host surfaces

| Commit | Change | Problem · Seon caller | Upstream | Verdict |
|---|---|---|---|---|
| `1e78cb9c` | `resource.cljc:1-157`, `pull_api.cljc:5-90`, `query.cljc:27-140`: `:max-work`/`:max-results`/`:max-result-weight` budgets | Bounded render reads: `src/seon/render/ns.clj:49-52`, `render/web.clj:192-222`, `program.cljc:488`, `db.clj:527,2387-2444` | Query deadlines `bb2ce2cc` (`:timeout`, `*query-timeout-ms*`); bounded sort `56457093`; OFFSET/LIMIT pushdown `803f1593` | **RIP**. Render expresses its bound in the selector (Datahike's `(limit :attr n)` and recursion depth) plus the upstream `:timeout`. Needs a design note: AGENTS separates work limits from deadlines |
| `a7c9aeb7` | tests for the above | Same | n/a | **RIP** |
| `8cb44a29` | `query.cljc:124-500`, `single_flight.cljc:17-450`, `api.cljc:36`: two-phase host query admission (`q-call-state`, `run-q!`, `on-q-complete!`) | Built for the deleted CLJS pod host. No Seon caller | n/a | **RIP** |
| `a5315858` | `single_flight.cljc:426`: queued owner on cancel (`cancel-query!`) | Same | n/a | **RIP** |
| `5e82166f` **H** | `committed_report.cljc:1-127`, `writer.cljc:7`: bounded committed-report source | No Seon caller; 0 live sources (retention study) | Durable commit listeners `1070bb75` | **RIP** |
| `f8d0b34b` | `committed_report.cljc`: block on readiness | Same | n/a | **RIP** |
| `d9765276` | `committed_report.cljc:175`: fair batches | Same | n/a | **RIP** |
| `45f58511` | spec `:76-200`, `db.cljc:385`: capability catalog | No Seon caller (`d/capabilities`: 0 hits) | n/a | **RIP** |
| `940810f5` | seven files: complete the capability catalog | Same | n/a | **RIP** |
| `d21abadb` | `api.cljc:49`, spec `:114`: `shallow-weight-within` for JVM hosts | No Seon caller | n/a | **RIP** |
| `34365046` | `index_page.cljc:1-161`, `db/utils.cljc:205`: bounded index pages with cursor | Seon `db.clj:1022,2687-2720`, `program.cljc:477-508` | Datahike already has `d/seek-datoms` (resume from a datom) plus `take` | **RIP**. `seon.db/index-page` = `(take (inc n) (d/seek-datoms db index cursor-components))`, with the cursor being the last datom's components |
| `f0ee54c2` | `index_page.cljc:45`: temporal page order | Same | `seek-datoms` on `(d/history db)` keeps index order | **RIP** |

### H. Query planner correctness

These are real wrong-answer bugs, each with a regression test in the fork. Since the merge base,
upstream has fixed about 30 planner defects in the same code (#887–#1092; see
`git log 85c40aee..FETCH_HEAD`). Our changed lines mostly do not appear verbatim upstream
(`git grep -F` of each added line of 25 or more characters). Upstream may have fixed the same
symptom a different way. **KEEP-verify** means: at the re-fork, run the fork's regression test on
upstream HEAD first, drop the commit if it passes, and offer it as a PR if it fails.

| Commit | Change | Symptom (fork regression test) | Related upstream | Verdict |
|---|---|---|---|---|
| `fc1bba01` | `query/execute.cljc:1739`: the direct multi-group path allows one probe edge only | A join through two identity clauses ignored the `:in` binding (`cljs_pattern_scan_test.cljc`) | `b3747605` (#925), `f6889ccf` (#1087) | **KEEP-verify** |
| `f77ea5f1` | `execute.cljc:2049`: standalone get-else scan keeps left-outer semantics | Rows lacking the attribute were dropped (`query_getelse_test.cljc`) | `2bafda19` (#888), `437d6401` (#923) | **KEEP-verify** |
| `22153e6f` | `execute.cljc:506`: collect the probe from the merge datom's own field | A `?tx`-slot probe used `.-v`, giving `#{}` (`query_planner_test.clj`) | 1 of 2 lines present upstream | **KEEP-verify** |
| `c9a2704c` | `query.cljc:3430`: cross-component split on disjoint components | An empty sub-`:find` collapsed the result to `#{}` | `8028d1f4` (#956) | **KEEP-verify** |
| `fa03b0fa` | `query.cljc:3687`: run ignored components so binding errors still raise | "Insufficient bindings" was swallowed | 2 of 10 lines present | **KEEP-verify** |
| `1598a824` | `execute.cljc:2793`, `lower.cljc:295`, `relation.cljc:103`: recursive-rule shortcuts direction-aware; bounded intermediates | The transitive closure stopped at depth 5 on CLJS; wrong direction on the JVM | `e15505fa` (#889), `5f859c00` (#915), `c1daf5b4` (#917), `6d5f602d` (#919); 11 of 258 lines present | **UPSTREAMED** (upstream rewrote demand restriction; confirm with the fork test) |
| `6f90b339` | `execute.cljc:2887`: preserve const-bound find-pull inputs | Pull in `:find` over a constant `:in` (`query_input_pull_test.cljc`) | `a5045907` (#908), `c3d20faf` (#1032) | **KEEP-verify** |
| `a464cd88` | `query.cljc:4162`: projected inputs survive across components | Wrong projection (`query_planner_test.clj`) | 4 of 12 lines present | **KEEP-verify** |
| `4c55791b` | `execute.cljc:3030`: no nil attribute probes | A nil probe matched everything | `225d6d23` (#976) "an undeclared attribute matches nothing"; 4 of 5 lines present | **UPSTREAMED** |
| `6611de27` | `query.cljc:372`, `analyze.cljc:15`, `estimate.cljc:45`, `execute.cljc:358`, `logical.cljc:38`, `plan.cljc:36`: symbol values stay ground | A symbol-typed `:in` value was treated as a variable. Seon stores symbols (AGENTS: "a symbol stays a symbol") (`query_cache_test.cljc` `ordinary-symbol-inputs-remain-ground-…`) | 0 of 43 lines present | **KEEP+PR** |
| `9a7a9ef1` | `plan.cljc:619`: a card-many scan leaves the sorted-merge path | A forward cursor dropped repeat entities; only the first value was answered | 0 of 1 line present | **KEEP+PR** |
| `19f5cdd9` | `execute.cljc:1037`, `plan.cljc:1544`: stable tied plans, card-many merges | Nondeterministic plan choice | 7 of 18 lines present | **KEEP-verify** |
| `574c5f0f` | `query.cljc:4187`: ORDER BY before return-map | Upstream `ebbd623a` (#795) ordered after the map conversion (`query_test.cljc`) | 1 of 3 lines present | **KEEP+PR** (its own message says so) |
| `da257d38` | CHANGELOG only | n/a | n/a | **RIP** (changelog follows its code) |
| `eedde719` | CHANGELOG only | n/a | n/a | **RIP** |

### I. Pull

| Commit | Change | Problem | Upstream | Verdict |
|---|---|---|---|---|
| `1296cfc4` | `pull_api.cljc:20-80`, spec `:540`: `pull-many` keeps input positions, with nil for missing | `pull-many` returned only the found entities, so positions were lost (`pull_api_test.cljc` `ordered-pull-many-preserves-input-positions`) | Still present upstream: `pull_api.cljc:311-325` `pull-spec … true` | **KEEP+PR**. Seon fallback if refused: `(mapv #(d/pull db sel %) eids)` in `seon.db` |
| `41c79c1a` | `pull_api.cljc:16`: `+default-limit+` 1000 → nil | Datahike silently truncates card-many and wildcard pulls at 1000 (upstream `pull_api.cljc:15,137`). AGENTS: "A pull must not silently truncate" | Still present upstream (a DataScript-inherited default) | **KEEP+PR** as an opt-in (a pull option or config key rather than a changed default). The honest note: this does bend the documented default. If upstream refuses, **RIP** and have Seon refuse a result of exactly 1000 elements as a possible truncation |

### J. Schema

| Commit | Change | Problem | Upstream | Verdict |
|---|---|---|---|---|
| `670cd1ad` **H** | `schema.cljc:261-293`: the `reduce-kv` arms return `m`, not nil | A later unchanged or allowed key (for example `:db/doc`) reset the accumulator, so an earlier invalid update (card-one → many on a unique attribute) was silently accepted | Still present upstream: `schema.cljc:358-391` (`(when (not= …))`, `:db/doc nil`) | **KEEP+PR**. Reproduction: one schema-update entity carrying an invalid `:db/cardinality` change and a `:db/doc` change |
| `58764d90` | `db/transaction.cljc:12`, `schema.cljc:277`: AVET backfill for additive `:db/index` | Enabling an index on used attributes | `e8239e72` (#934) index-backfill migration; `cbcf21be` (#1081) | **UPSTREAMED** |
| `c1c4c293` | `db/transaction.cljc:99-280`: reject non-monotonic `:db/index` | Disabling an index left AVET stale | Upstream handles both directions (`schema.cljc:383-388` comment; `cbcf21be`) | **UPSTREAMED** |
| `5cdbc88a` | `db/transaction.cljc:136-300`: allow removal of empty indexed schemas | Drop-attribute pattern | `5e3dd26a` (#931) in-use check on the resulting state; `a5a67e2b` (#1089) | **UPSTREAMED** |
| `c0a74e12` | `db/transaction.cljc:292`: `ident-name?` | Same | Same | **UPSTREAMED** |
| `b73550bf` **H** | `db/transaction.cljc:136`: refuse removing any schema that still has data | Same | `5e3dd26a` | **UPSTREAMED** |

### K. Proximum and secondary indexes (Seon declares none)

| Commit | Change | Verdict |
|---|---|---|
| `6cf05300` | `src-secondary/…/proximum.clj`: coerce vectors to `float[]` | **RIP**: no Seon secondary index; drop `org.replikativ/proximum` from Seon `deps.edn:40-43` and the `:db.secondary/only` arm in `schema/datahike.clj:156-175` |
| `5f62d57f` | proximum: restore on reopen | **RIP** |
| `7ef2b5de` | proximum: keep the post-sync index | **RIP** (upstream secondary generations `d7560db7`, `2d9d286c` rewrote this seam) |
| `069a807e` | `index/secondary.cljc:295`, `versioning.cljc:121`: guarded Proximum force | **RIP** |
| `c1faf70d` | proximum: external IDs for filtered search | **RIP** |
| `04213e17` **H** | `writing.cljc:227-320`, `versioning.cljc:422-445`, `api/impl.cljc:368`: `commit-as-db {:secondary-indices? false}` and `release-materialized-db` | **RIP**. Delete Seon's 19 no-op calls: `cluster.clj` 8, `cluster/source.clj` 6, `cluster/registry.clj` 2, `maintenance.clj` 2, `cluster/boot.clj` 1, plus 8 test sites; and the `{:secondary-indices? false}` argument at `sci/eval.clj:895` |

### L. ClojureScript (Seon is CLJ-only; the pod is deleted)

| Commit | Change | Verdict |
|---|---|---|
| `1c630664` | `api.cljc:2`, `api/async.cljs`: Promise contract for CLJS | **RIP** (upstream has its own CLJS async seam, `16c1ab9a`) |
| `a7690d0b` | CLJS analyzer warnings in five files | **RIP** |
| `f2f2809d` | tests: exclude channel-contract tests | **RIP** |
| `e6d196d5` | `db.cljc:391`: CLJS wrapper-db `-lookup` | **RIP** |
| `f9fc0940` | tests: CLJS cache lifecycle | counted in theme E |
| `d7ac886f` | `versioning.cljc:10`: CLJ-only refer | counted in theme D |

### M. Build, codegen, HTTP, test hygiene

| Commit | Change | Verdict |
|---|---|---|
| `6e0b891e` | `build.clj:10`, generated Java: cold `compile-java` for `:git/url` consumers | **UPSTREAMED** (`12d9d992` "prepare clean git dependencies"). Seon uses `:local/root` |
| `30a15483` | `codegen/java.clj:220`: whitespace-clean Java docs | **RIP** (regenerate at the re-fork) |
| `e2f71ad0` | `api.cljc:36`, spec `:23`: generated API for clj-kondo | **RIP** (regenerate) |
| `9b3be9d5` | regenerated kondo export | **RIP** |
| `2241df17` | `codegen/pod.clj:288`: test resource ownership | **RIP** |
| `eedea005` | `http/writer.clj:4`: HTTP writer decoding | **UPSTREAMED** (HTTP API rewritten in `aeb36c84` #1028; Seon has no HTTP writer) |
| `7f2c77d0`, `3af6e46e`, `d45ee18b`, `d664dce7`, `c2379bcf`, `d59f76bb` | test-only connection-release hygiene | **UPSTREAMED** (upstream's own test hygiene `92815f0b`, `7cebcdbf`); each goes with the code it tested |

(The last row covers six commits. `f9fc0940` and `d7ac886f` are listed in L but counted in E and
D. Every one of the 115 commits is counted exactly once: checked by script against
`git log --no-merges 85c40aee..HEAD`.)

## The HIGH rows (31)

| Area | Commits |
|---|---|
| Write path and error handling | `cae24611` U, `ca4383dc` R, `ea6e9881` R, `9c356e32` K+PR, `c1527273` R, `7e1af7dd` R, `006e634a` R, `6dd49e5e` R, `73afe782` R, `e11845ba` K+PR, `2cc313a6` R |
| Store and durability | `9ada7550` U, `256b714d` R, `0e8601d7` K+PR, `cc2b2bc7` R, `9fbb66fc` K+PR, `670cd1ad` K+PR, `b73550bf` U |
| GC | `56f1c621` R |
| Connections and branches | `d6a99f53` U, `357ffc87` R, `04213e17` R |
| Query-cache retention | `0b652215`, `a795dc37`, `f8192962`, `f3776b72`, `0070d507`, `0cf39e57`, `684d3290`, `84a20308`, `5e82166f`: all R |

## The upstream merge, priced

The dry run (`git merge-tree --write-tree HEAD FETCH_HEAD`, 0.55 s) conflicts in 44 files: 28 src
files, **141 conflict hunks, about 7,900 conflicted src lines**. The largest:

| File | Hunks | Lines |
|---|---|---|
| `query/execute.cljc` | 30 | 1,038 |
| `query.cljc` | 15 | 709 |
| `db/transaction.cljc` | 14 | 802 |
| `writer.cljc` | 11 | 1,184 |
| `versioning.cljc` | 9 | 655 |
| `writing.cljc` | 8 | 687 |
| `connector.cljc` | 5 | 447 |
| `gc.cljc` | 1 | 406 |
| `connections.cljc` | 1 | 288 |

Upstream rewrote the commit path (fenced heads, `:self` writers, secondary generations, AVET
generations, durable roots), and those are exactly the files where our RIP families live.
Merging means re-deriving upstream's commit path around about 6,000 fork lines, from the 74
commits this audit removes anyway.

**Option 1: merge upstream into the fork.** It keeps everything and resolves 141 hunks by hand,
most of them in code we are deleting. Each writer and cache conflict needs a proof. It is days
of work and carries the most risk, and we would keep the parallel cache.

**Option 2: keep the fork as it is.** It costs nothing now. We give up upstream's 165 commits,
including about 30 planner correctness fixes, the store-ref blob GC, fenced heads, the durable GC
roots, the node-cache sharing (already queued as a port of `d2b9e525`) and `(vec (rest args))`
correctness by design. Every new Seon need adds another fork commit, which is the pattern this
audit found.

**Option 3 (recommended): shrink first, then re-fork.** Make the Seon-side conversions that need no
upstream code (slices R1–R7 below), so Seon stops calling the RIP'd APIs. Then create a new fork
branch at upstream main and cherry-pick only the KEEP set: 19 commits, about 300 src lines, with
conflicts only in the 8 KEEP-verify planner commits, each settled by running its own regression
first. Then adopt upstream's mechanisms (R8–R11). The fork drops from 115 commits to at most 19,
and to fewer as PRs land. Most of this cost is Seon conversions that also shrink Seon, which the
owner's 10k target needs anyway. The price: the re-fork's proof runs the Datahike suite in its own
JVM, which needs the owner's authorization (rules: no fork-test JVM without it).

## Removal plan — ordered slices

Each slice is one revert or small fork change, plus the Seon seam fix (at most about 100 added
Seon src lines), plus the regression that proves Seon's behaviour survives. Holders are from
`tmp/orchestrator/file-ownership.md` at audit time. `reference-code/datahike` is held by
store-damage, so every fork-side step waits for its release or goes to it as a follow-up.

| # | Slice | Fork change | Seon change (files) | Held by | Regression |
|---|---|---|---|---|---|
| R1 | Konserve pin | Pin Datahike `deps.edn` to Konserve 5b39fdd (reverts the effect of `256b714d`) | none | store-damage (`deps.edn`, `reference-code/konserve`, `reference-code/datahike`) | `test/seon/cluster/store_test.clj` (store-damage's) |
| R2 | Writer log names no Seon keys | revert `7e1af7dd`, `006e634a`, `6dd49e5e`, `c1527273` | none: Seon's transact catch already records the fault with identity (`src/seon/db.clj`) | store-damage | `test/seon/cluster/fault_storage_test.clj` plus the fork's `writer_error_test.clj` |
| R3 | Listener failure handled by Seon | revert `2cc313a6` (keep `e11845ba`) | one `seon.db` listen wrapper (catch → fault → `d/unlisten` by key), about 25 lines; convert `eval/drive.clj:64`, `sci/eval.clj:2419`, `schedule.clj:799`; delete `cluster/store.clj:621-640` | `sci/eval.clj` and `cluster/store.clj`: leak-fix-2; `schedule.clj`: store-damage; `db.clj` and `eval/drive.clj`: free | `test/seon/cluster/listener_failure_test.clj` |
| R4 | Secondary-index lifetime | revert `04213e17` (and the Proximum commits at the re-fork) | scripted deletion of 19 `d/release-materialized-db` calls and 8 test calls; drop `{:secondary-indices? false}` at `sci/eval.clj:895`; drop Proximum from Seon `deps.edn:40-43`; drop the `:db.secondary/only` arm in `schema/datahike.clj:156-175`. Net negative | `cluster.clj` (m4-n1, b1-adoption), `cluster/source.clj` (test-overhead), `registry.clj` and `maintenance.clj` (store-damage), `cluster/boot.clj` (error-floor), `sci/eval.clj` (leak-fix-2), `deps.edn` (store-damage) | `test/seon/cluster/source_test.clj`, `publication_reuse_test.clj`, `publication_export_test.clj` |
| R5 | Expected basis as a Datahike transaction function | revert `ca4383dc`, `ea6e9881`; keep `9c356e32` | `seon.db/basis-guard` fn plus prepending `[:db.fn/call …]` at `db.clj:4498`, about 15 lines; convert `plan.clj:1323`, `cluster/source.clj:749-838` | `source.clj`: test-overhead; `db.clj`, `plan.clj`: free | the stale-basis refusal tests in `test/seon/db_test.clj`, `test/seon/cluster/source_lineage_test.clj` |
| R6 | Index pages from `seek-datoms` | revert `34365046`, `f0ee54c2` | rewrite `seon.db/index-page` (`db.clj:2687-2720`) over `d/seek-datoms` + `take`, about 25 lines; `decode-index-page` keeps its envelope | `db.clj`: free | `test/seon/db_test.clj` index-page tests, `test/seon/program_test.clj` |
| R7 | Unused host surfaces | revert `45f58511`, `940810f5`, `8cb44a29`, `a5315858`, `5e82166f`, `f8d0b34b`, `d9765276`, `d21abadb`, `5566ab13` (about 1,500 fork lines) | none (no Seon caller) | store-damage | Seon HEAD loads; `test/seon/db_test.clj` |
| R8 | Re-fork at upstream main | new branch at `7f39cccc` plus cherry-pick the 19 KEEP commits | `deps.edn` pin | owner authorization for the Datahike suite JVM | the fork's KEEP regressions on the new base; Seon `bin/test-check default --gate --changed-path reference-code/datahike` |
| R9 | Write validation as upstream tx-preds | (upstream code) | `register-tx-pred!` per store in `seon.db`, the projection derived from `:db-after`; delete the tx-meta stamp at `db.clj:4251` | `db.clj`: free | `test/seon/db_test.clj` write-refusal tests |
| R10 | Cache identity and read evidence | (upstream code; deletes theme E and F fork commits) | **Design note first**: committed identity from `[store-id commit-id]`; Seon's attribute-revision caches → `datahike.dependency-tracking` tokens (bound: 16 groups per DB); read refresh → re-run reads against upstream's propagated cache. Split per consumer: `render.clj` + `call_preparation.clj`; `test/runner.clj`; `sci/eval.clj`; `db.clj` | `sci/eval.clj`: leak-fix-2; others free | `test/seon/render_cache_test.clj`, the call-preparation tests, and the test-runner reuse tests |
| R11 | Bounds and GC | (upstream code; deletes `1e78cb9c`, `56f1c621`) | **Design notes first**: render bounds as selector limits plus `:timeout`; blobs as `:db.type/store-ref`; boot and publication on the sweep floor or a durable root (`blob.clj`, `cluster/boot.clj`, `cluster.clj`, `render/ns.clj`, `render/web.clj`) | `cluster.clj`, `boot.clj`: held | `test/seon/blob_publication_test.clj`, `test/seon/background_blob_test.clj`, the render web tests |

Upstream PR candidates for the owner: `9fbb66fc`, `670cd1ad`, `e11845ba`, `9c356e32`,
`1296cfc4`, `6611de27`, `9a7a9ef1`, `574c5f0f`, `fbd1ad2d`, `0e8601d7`, `41c79c1a` (as an opt-in),
plus any KEEP-verify planner commit whose regression fails on upstream HEAD.
