---
type: research
status: active
tags: [performance, publication, test-system, class/p1, schema, program-graph]
---

# Recompute-from-scratch: the full inventory, ranked

Dated 2026-09-16. Read-only. The owner's question: *"updates should be
incremental. Why does any file in the snapshot break this? How many stupid
decisions are you finding like this? Identify all of them and let's slate them
to be fixed."*

**The disease.** A derivation recomputed from scratch, re-read from disk, or
rebuilt per worker / per call, where the answer was already derived once and
could have been reused, updated by the changed inputs, or read from a fact.
It is law 2.1's fetch-at-call-time seen from the cost side, and
[AGENTS.md](../../../../AGENTS.md) §0 names its cure: *"If your design
recomputes something a dependency already maintains, the design is wrong."*

**Headline, measured today.** The edit hook ran **113 publication cycles**
between 09:16 and 22:00 (`logs/hook-debug.log`). **46 of them took ≥ 30 s,
summing 6,087 s — 1 h 41 min of one working day spent republishing a program
graph that changed by one file.** p50 0.4 s (coalesced no-ops), p90 **167.1 s**,
max **180.0 s** — the operator's own bound, hit twice. Of the 47 decisions the
log records, **40 were "complete publication" and 7 were incremental**: the
incremental seam exists and fires **15%** of the time.

---

## 0. Defects, first

| # | Defect | Evidence |
|---|---|---|
| **R1** | **The incremental publication path is the exception, not the rule.** 40 complete vs 7 incremental decisions today; the largest single reason is `complete publication: missing or stale artifact` (21). | `logs/hook-debug.log`; branch at `src/seon/cluster.clj:2014-2015` |
| **R2** | **Any non-Clojure file in the snapshot forces a complete rebuild, by construction.** `incremental-source-refresh!` classifies a file that is not `.clj`/`.cljc` as `:schema-resource` and leaves `desired` nil (`src/seon/cluster.clj:2032-2050`); `plan-file-change` turns *both* into reasons (`src/seon/fn.clj:2064-2069`), and every reason except `:component-or-cardinality-many-change`/`:attribute-retraction` is `structural`, which calls `full-source-refresh!` (`src/seon/cluster.clj:2086-2087`). **This is the literal answer to "why does any file in the snapshot break this".** | log reasons `(:missing-desired-artifact :schema-resource)` ×2, and 8 more compound reasons containing them |
| **R3** | **1,743 of the 2,089 files that identify `:current-src` are issue markdown.** `seon.cluster/source-roots` (`src/seon/cluster.clj:1674-1677`) adds `docs/seon/issues`, and `source-file?` admits `.md` (`src/seon/cluster/source.clj:94-99`). Every issue note write changes `:seon.source/digest`, so `current-publication store source-digest` misses and `full-source-refresh!` republishes (`src/seon/cluster.clj:1980-1986`). 83% of the publication's identity surface produces **zero program facts**. | counted at HEAD: `find src test docs/seon/issues -type f \( -name '*.clj' -o -name '*.cljc' -o -name '*.edn' -o -name '*.md' \)` = **2,089**, of which `docs/seon/issues` = **1,743** |
| **R4** | **`packaged-forms` has no memo, and the observable key it needs is already written.** Three consecutive live calls: **38.8 / 36.2 / 36.9 ms** — flat. `declaration-stamp` (`src/seon/schema/edn.clj:342`), whose docstring says in as many words that it is "the key a derivation over the AUTHORED population caches under", costs **1.72 ms** — 21× cheaper. `resource-population` (`:329`) is a plain `defn-`. | live eval, basis `:t` **536871857**, 182 resource files, 2,781 forms |
| **R5** | **The gate's "18 s program graph build" is mislabelled; it is worker JVM startup.** `announce! "SELECT building the program graph"` (`src/seon/test/runner.clj:3691`) is followed by `manifest` = one `slurp` of the published base's `manifest.edn` (`:3701`, `seon.test.cache/manifest`, `src/seon/test/cache.clj:147-153`) and then `(mapv #(.get %) worker-futures)` (`:3704`), which **blocks on `start-worker!` + `initialize-worker!`** (`:2948`). 18.1 s matches the 11.2–16.2 s worker JVM start exactly. **Option C's fourth target does not exist as stated** — the manifest is already read from facts. | [test-execution-model](test-execution-model-2026-09-16.md) §1.1, §2.5; `runner.clj:3691-3704` |
| **R6** | **`dev-cache` holds an exclusive file lock across its own cache-hit check.** `ensure-cache` (`dev_cache.clj:493`) wraps its whole body in `with-cache-lock` (`:404`, a `FileChannel.lock`). A gate whose cache is already valid still waits out a peer's full rebuild: **87,431 ms** in batch-106, for a check that costs milliseconds. | `bin/test:591`; `lock wait-ms= 87431`, [test-execution-model](test-execution-model-2026-09-16.md) §2.5 |
| **R7** | **An identical `config/apply!` costs 2,101 ms while writing zero datoms.** `apply-compiled!` already short-circuits at zero operations (`src/seon/config.clj:486-490`) — so the cost is entirely `compile-manifest` and `reconcile/plan`, not the write. The 2-arity also re-reads the shipped manifest and `packaged-forms` on every call (`:529-533`). Option C's framing ("should cost what `record-tx`'s delta writer costs") mis-locates it: the writer is already a delta. | `src/seon/config.clj:454-533`; 3,009 / 2,101 ms from [turn-bookkeeping-cost](../../context-generation/research/turn-bookkeeping-cost-2026-09-16.md):94 |
| **R8** | **Three worker JVMs each clone and connect their own copy of the same immutable published base.** `create-base` (`test/seon/test_support.clj:379`) `clone-directory!`s the runner's base and re-identifies it, once per JVM (`database-base`, `:609`); `seon.test/check` primes it before the clock starts (`src/seon/test.clj:741-747`), the worker does not, so the cost lands inside the first test's own bound (D5). | 15.6–16.8 s × 3 per gate, [test-execution-model](test-execution-model-2026-09-16.md) §6, §0 D5 |

---

## 1. The inventory

Each row: what is recomputed, from what, how often, cost with source, what
already holds the answer, and the shape of the fix.

### R1/R2/R3 — the publication decides "rebuild everything" for a one-file edit

| | |
|---|---|
| **Recomputed** | the entire `:current-src` program graph: analyze 339 Clojure files, reconcile schema, write ~660 store segments |
| **From** | all 2,089 files under `src`, `test`, `resources/…`, `config/default.edn`, `docs/seon/issues` (`src/seon/cluster.clj:1674`, `src/seon/cluster/source.clj:94`) |
| **How often** | per edit-hook publication cycle; **40 of 47 decisions today** |
| **Cost** | **113 cycles / 6,216 s total; 46 cycles ≥ 30 s summing 6,087 s; p90 167.1 s; 2 cycles at the 180 s operator bound** (`logs/hook-debug.log`, `SOURCE_EDIT` → `SOURCE_ADMITTED`/`SOURCE_BATCH`) |
| **Already holds the answer** | `incremental-source-refresh!` (`src/seon/cluster.clj:2000`) and its `:seon.fn.change/rows` delta; the cached artifact's `:seon.source/relative-file-digests` |
| **Fix** | (a) a non-Clojure file gets its own change class instead of `:schema-resource` + `:missing-desired-artifact` — an `.md` under `docs/seon/issues` produces no program row and must not be structural; (b) decide staleness from `:seon.fn.manifest/roots` vs the publication's roots rather than reading a relocated path as 2,048 deletions ([incremental-refresh-exchange-bound](../../context-generation/research/incremental-refresh-exchange-bound-2026-09-16.md) fix 1–2) |

Reasons recorded today, verbatim:

| count | reason |
|---:|---|
| 21 | `missing or stale artifact` |
| 5 | `(:analysis-refused)` |
| 3 | `(:added-identity :component-or-cardinality-many-addition :component-or-cardinality-many-change :removed-identity)` |
| 3 | `(:added-identity … :deleted :missing-desired-artifact :removed-identity :schema-resource)` |
| 2 | `(:missing-desired-artifact :schema-resource)` |
| 2 | `(:component-or-cardinality-many-change :missing-desired-artifact :schema-resource)` |
| 2 | `(:added-identity … :missing-desired-artifact :removed-identity :schema-resource)` |
| 1 + 1 | two more compound sets |

### R4 — `packaged-forms` re-lists and re-parses 182 resources per call

| | |
|---|---|
| **Recomputed** | list the resource directory, read and EDN-parse 182 files, merge 2,781 forms |
| **From** | `resources/seon/schemas/` |
| **How often** | **per call**, at 50 call sites in `src/` |
| **Cost** | **38.8 / 36.2 / 36.9 ms** across three consecutive live calls (basis `:t` 536871857); 14,942 µs/call uninstrumented on a bare `clojure -M:dev` ([incremental-refresh-exchange-bound](../../context-generation/research/incremental-refresh-exchange-bound-2026-09-16.md)); **5.0 s** across 337 `build-artifact` calls in one publication; 82,992 resource reads / 6,495 ms in one config admission before `call-with-forms` fixed that caller (`src/seon/config.clj:275-281`) |
| **Already holds the answer** | `declaration-stamp` (`src/seon/schema/edn.clj:342`), **1.72 ms**, one `stat` pass, explicitly documented as the cache key |
| **Fix** | memoize `resource-population` on `declaration-stamp` — one line, the key already exists |

The instance fixes keep recurring because the function is unguarded: the
2026-08-07 repair, then `seon.cluster.source/identity-ref` on 2026-09-16
(three 180 s operator-bound overruns), then `analysis-rows-by-file`
(`src/seon/fn.clj:1205-1215`, "18 ms a call"), then
`incremental-source-refresh!` (`src/seon/cluster.clj:2024-2025`, "18 ms a
call"). Four callers, four separate fixes, one unfixed function.
([issue](../../../seon/issues/packaged-forms-per-call-reads-recur-at-every-new-caller.md))

`seon.fn/declaration-forms` (`src/seon/fn.clj:1203-1215`) falls back to
`packaged-forms` when a request carries no projection, and `build-artifact` is
a per-file public entry point — so the fallback is the default at every
per-file caller that does not know to hand the population in.

### R5 — the coordinator's 18 s is not a program-graph build

Already stated in §0. The correction matters for slating: **do not launch a
lane against "the 18 s program graph build".** The manifest is one file read
(`src/seon/test/cache.clj:147`); the 18 s is the coordinator waiting on three
worker JVMs it launched in virtual threads (`runner.clj:3704`). The real
target is R8, and the mislabelled announce is itself a defect — a progress
line that names the wrong subject is how a cost stays misattributed across
two research notes.

### R6 — `dev-cache` serializes hits behind misses

| | |
|---|---|
| **Recomputed** | nothing — the *wait* is the cost |
| **From** | one exclusive `FileChannel.lock` on `target/dev-dependency-cache.lock` |
| **How often** | per `bin/test` invocation (`bin/test:591`) |
| **Cost** | **87,431 ms** lock wait in batch-106 (90 s of a 289 s gate, 31%) |
| **Already holds the answer** | `current-cache` / `valid-cache` (`dev_cache.clj:354-366`, `:303`) — a pure digest comparison needing no exclusive lock |
| **Fix** | check for a valid cache **before** taking the lock; take it only on a miss |

`test-digest` (`dev_cache.clj:458`) additionally SHA-256s every gate input's
bytes inside that lock, per invocation — 567 files / 9.5 MB at HEAD, so tens
of milliseconds, not the bottleneck. `seon.test.selection/input-digests`
(`src/seon/test/selection.clj:69`) is the same walk from the gate side. **Named
and dismissed:** this one is cheap; leave it.

### R7 — `config/apply!` recompiles the manifest to discover it changed nothing

| | |
|---|---|
| **Recomputed** | `compile-manifest` (read + validate + admit the manifest) and `reconcile/plan` over the whole desired population |
| **From** | `config/default.edn` via `io/resource`, plus `packaged-forms` (`src/seon/config.clj:529-533`) |
| **How often** | **twice per seeded cluster fixture**, and on every `bin/seon config apply` |
| **Cost** | 3,009 ms first, **2,101 ms identical again** ([turn-bookkeeping-cost](../../context-generation/research/turn-bookkeeping-cost-2026-09-16.md):94); `with-cluster` pays ~5 s, `seed-cluster!` ~2.3 s ([test-execution-model](test-execution-model-2026-09-16.md) §2.3) |
| **Already holds the answer** | `apply-compiled!` already converges at zero operations (`:486-490`); the compiled value is a pure function of (manifest bytes, environment, cluster name) |
| **Fix** | memoize `compile-manifest` on that triple, and hand the compiled value to the second apply instead of recompiling it; the 2-arity resolves `packaged-forms` once per operation |

### R8 — three worker JVMs build the same canonical fixture base

| | |
|---|---|
| **Recomputed** | clone the published base store, `reidentify!`, connect (which can run connect-time migration) |
| **From** | the one immutable `seon.test.published-base` directory |
| **How often** | **once per worker JVM per gate** (3), plus the coordinator's |
| **Cost** | **15.6–16.8 s each**, ~47 s per gate, charged to the first `with-database` test's own bound (D5) |
| **Already holds the answer** | the published base itself; `create-base`'s own comment says each JVM owns its copy **because connect-time migration can mutate a frontend-only backend** (`test/seon/test_support.clj:389-391`) — so the clone is load-bearing, but the *timing* is not |
| **Fix** | move the priming into worker readiness, exactly as `seon.test/check` already does (`src/seon/test.clj:741-747`); the clone stays, the lie about the first test's duration goes. A shared read-only frontend is a second, larger slice |

### R9 — `gate-set` (already fixed this week; verified)

`seon.fn/gate-sets` (`src/seon/fn.clj:1375`) now acquires the declared
dispatch and unresolved-file relations **once per supplied operation**; the
single-function `gate-set` (`:1406`) keeps its meaning. Whole-population run
went 9,041 ms → 7,518 ms; detector 7,044 ms / 166 subjects
([issue](../../../seon/issues/gate-set-rederives-the-declared-reference-population-on-every-call.md)).
**Still open**: 13 seeds cost **6,213.51 ms** ([test-execution-model](test-execution-model-2026-09-16.md) D4)
against a stage-1 budget of ≈50 ms, so the hoist helped and the walk itself is
now the cost. Not a recompute defect any more; a selection-budget one.

### Verified NOT instances

Named so no lane is slated against them.

| Site | Finding | Evidence |
|---|---|---|
| `seon.instrument` re-arm | **genuinely incremental.** `current-wrapper?` (`src/seon/instrument.clj:603-613`) compares the wrapper's captured Var identity, authored form, contract, and the exact `:seon.instrument/definitions` subset against the supplied projection; an unrelated wrapper keeps identity. AGENTS.md's claim at `:593` holds. | read at HEAD |
| `fork-cluster-ctx` | **correct.** Uses `sci/fork` (copy-on-write Vars, `reference-code/sci/src/sci/core.cljc:345`) over the one base ctx; only the two atoms are copied. `build-base-ctx` (`src/seon/sci/eval.clj:184`) is called from the acquisition path (`:1830`, `:2252`) and test fixtures, not per fork. | `src/seon/sci/eval.clj:1864-1893` |
| render candidate selection | **already cached on an observable.** `schema-producers` (`src/seon/render.clj:305`) is per call, but the render path carries an invocation cache keyed on `call-cache-evidence` (`:686`, `:1423-1436`) in the cluster's `shared-cache` (`:1534`). | read at HEAD |
| `record-tx` delta writer | **the model to copy.** `src/seon/test/runner.clj:2095` derives reach digests from the tested database value and writes a delta: an unchanged re-record writes **zero** datoms (157,981 → 0). | measured 2026-09-17, cited §5 of [test-execution-model](test-execution-model-2026-09-16.md) |
| selection by mtime | **none exists.** `input-digests` is SHA-256 over bytes; there is no `lastModified` in the selection path. | `src/seon/test/selection.clj:69-85` |

### `slurp` / `file-seq` / `io/resource` census under `src/`

53 occurrences across 20 files. Classified:

| Class | Count | Sites |
|---|---:|---|
| **per call — defect** | 6 | `schema/edn.clj:148,160,201,210,358,365` (all inside `resource-population`/`declaration-stamp`, i.e. R4) |
| **per operation — acceptable, could be hoisted** | 9 | `config.clj:291` (`default-document`), `fn/analyzer.clj:176,218,222,411,437`, `schema/admission.clj:80,102,128` |
| **per publication / per gate** | 8 | `cluster/source.clj:125` (the 2,089-file walk), `cluster.clj:1875,3679,3713`, `test/selection.clj:201`, `test/cache.clj:15`, `test/runner.clj:3694`, `test/arm.clj:53` |
| **once at boot / memoized** | 5 | `fn/analyzer.clj:189` (memoized), `schema.clj:812,841`, `artifact.clj:49`, `fn.clj:88` |
| **per request, correctly** | 6 | `render/web.clj:2812,2850`, `ai.clj:1368`, `issue.clj:104,115`, `cluster/status.clj:30` |
| **entry points / one-shot** | 19 | `test/runner.clj:391,434,1487,2439,2600,3830`, `test.clj:108`, `schema/admission.clj:442`, `cluster.clj:2169-2170`, `fs.clj`, `sci/eval.clj:1036` |

`seon.issue.clj:104,115` re-lists and `slurp`s all **1,743** issue notes per
call; it is a request-scoped read today, so it is filed here as a watch item,
not a defect — the moment a per-row caller appears it becomes the next
`packaged-forms`.

---

## 2. Ranked by cost × frequency

| Rank | Slate | Measured cost | Frequency | Expected recovery |
|---:|---|---|---|---|
| **1** | **R1+R2+R3** — a non-Clojure file must not force a complete publication; drop `docs/seon/issues` from the digest identity or give `.md` its own change class | 6,087 s of ≥30 s cycles in one day; p90 167 s | 40 of 47 hook decisions | the largest single number in this note |
| **2** | **R4** — memoize `resource-population` on `declaration-stamp` | 36.9 ms → 1.7 ms per call; 5.0 s per publication; 6,495 ms in one admission | 50 call sites, per call | a one-line change closing a four-time-recurring class |
| **3** | **R6** — `dev-cache` checks for a hit before taking the exclusive lock | 87,431 ms | per `bin/test` under any concurrency | 31% of a warm gate |
| **4** | **R8** — prime the canonical base in worker readiness | 15.6–16.8 s × 3 | per gate | ~47 s, plus the first test stops lying about its duration |
| **5** | **R7** — memoize `compile-manifest`; hand the compiled value to the second apply | 2,101 ms identical | twice per seeded cluster fixture, across ~1,860 tests | every cluster fixture in the suite |
| **6** | **R5** — relabel the announce; **do not** slate a program-graph-build lane | 0 (the work is R8's) | — | prevents a wasted lane |
| **7** | **R9** — the `gate-sets` walk itself against the ≈50 ms stage-1 budget | 6,213 ms / 13 seeds | per selection | stage 1's stated premise |

---

## 3. One class, not seven

**No seam turns "these files changed" into "these facts changed", and the
three places that need it each invented their own answer.**

| Consumer | Its own answer | Its failure mode |
|---|---|---|
| the edit hook | `incremental-source-refresh!` → `changed-source-paths` over absolute-path file digests (`src/seon/cluster.clj:1990-1998`) | anything it cannot project to a program row is `structural` → full rebuild (R2); a relocated checkout reads as 2,048 deletions (R3) |
| the gate launcher | `seon.test.selection/input-digests` + `changed-inputs` over repository-relative SHA-256 (`src/seon/test/selection.clj:69,85`) | a change under `widening-inputs` widens to *every* eligible test — the same "I cannot project this file, so do everything" answer, spelled differently |
| the coordinator | the published base's `manifest.edn` (`src/seon/test/cache.clj:147`) plus `bin/test`'s `find test -name '*_test.clj'` (`bin/test:660-666`, D2) | a filename convention ahead of every database-derived selection |

All three ask **"which facts does this file own?"** and all three answer it by
walking the filesystem. The fact that answers it already exists —
`:seon.fn.file/relative-path` with its `:seon.fn.file/digest` and
`:seon.fn.file/identities`, 339 rows on `default` at basis `:t` 536871857.
A file with no row owns no facts, which is exactly the honest answer for an
issue note or a `.md`, and is the answer R2 gets wrong.

**The second class, smaller:** *a derivation with an observable key that was
never keyed on it.* `packaged-forms` has `declaration-stamp` written for it and
unused (R4); `compile-manifest` is a pure function of three values nobody
holds (R7); `dev-cache` has `valid-cache` and locks around it anyway (R6).
`9cc181289` moved `program/shapes` **off** a process `defonce` and onto a
stamped derivation — 703 µs where it had been ~0 — which is the right
direction (a process cache is the pre-read law's target) executed without the
memo that makes it cheap. The general shape is not "add a cache": it is
**key the derivation on the observable its authority already publishes.**

**The third class, one instance:** *a progress line that names the wrong
subject* (R5). It cost two research notes a wrong attribution and nearly cost
a lane.

---

## 4. Verification boundary

No test was run; no test JVM was launched; no file outside this note was
modified. Exactly one read-only evaluation was issued — `mode: jvm`, cluster
`default`, explicit `(seon.operator/connection "default")` custody — at basis
`:t` **536871857**; it produced the `packaged-forms` / `declaration-stamp`
timings, the 182/2,781 resource counts, and 5,114 `:seon.fn/sym` / 339
`:seon.fn.file/relative-path` / 1,860 `:seon.test/sym` rows. The publication
cycle statistics were computed from `logs/hook-debug.log` at HEAD. File counts
were taken with `find` in this working tree. Every other number is cited to
the dated note or gate log that measured it and was not re-measured here.
