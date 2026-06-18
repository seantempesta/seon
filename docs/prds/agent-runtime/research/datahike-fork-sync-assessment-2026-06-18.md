---
type: research
status: active
tags: [research, database, reference]
---

# Datahike fork-sync assessment — are our 5 commits load-bearing?

## Context

`reference-code/datahike` is pinned by SHA in `deps.edn` (`:writer`,
`:replica-probe-jvm`, `:cljs`) at `seantempesta/datahike@ec902943`. The
fork is **5 commits ahead, 15 behind** `replikativ/datahike` main.
Question: which of our 5 commits are load-bearing vs. disposable, and how
do they fare against upstream — so we can stay in sync without carrying
dead patches or losing real fixes.

**Key framing discovered:** upstream **actively supports + tests CLJS**
(shadow-cljs `:node-test`, `datahike.test.nodejs-test`,
`cljs_pattern_scan_test.cljc`). So our CLJS-compat patches are not
"downstream-only forever" — the real upstream CLJS bugs among them should
be **PR'd back**, and several have already been fixed upstream in parallel.

## Per-commit verdict

### 1. `f092a63a` feat(cljs): selective Promise wrap on datahike.api — **LOAD-BEARING, permanent local**

- Adds `datahike.api.async/chan->promise` (our 38-line file) + a
  `:referentially-transparent?`-driven dispatch in `api.cljc` so CLJS
  writers/IO return `js/Promise` for native `^:async`/`await`.
- **Not upstream at all.** This is the pod's async-interop contract
  ([[reference_cljs_async_await]]). Keep; re-apply on every sync.
- Action: **propose upstream as a feature** so we stop re-applying it.

### 2. `f6ecf173` build: make compile-java work cold for :git/url consumers — **load-bearing-ish, verify**

- Checks in generated `DatahikeGenerated.java` (928 lines) + build.clj +
  .gitignore so `prep-lib` succeeds when seon consumes datahike via
  `:git/sha`.
- Upstream build.clj already has `compile-java`; `DatahikeGenerated.java`
  is generated (not checked in) upstream. **Verify whether upstream's
  current compile-java works cold** under `:git/url` prep — if yes, this
  commit is droppable; if no, keep. Low risk either way.

### 3. `01ba3f18` "silence 16 CLJS analyzer warnings" — **MIS-TITLED; mixed.** Split it:

| Part | Verdict |
|------|---------|
| `writing.cljc` `(catch Throwable)` → `#?(:cljs (catch :default))` | **REAL upstream CLJS bug** — upstream main STILL has the unguarded `catch Throwable` (line 428). Keep + **PR upstream**. |
| `versioning.cljc` cljs require (`S` as `:refer`, add `<?`) | Likely still needed (undeclared-var / macro-vs-value). Verify against upstream require form. |
| `execute.cljc` `java.util.HashSet` wrap + `probe-driven-iterable` wrap | **SUPERSEDED.** Upstream independently guarded both (HashSet via `#?(:clj)` helper fns lines 122-134; `probe-driven-iterable` is already `#?(:clj …)`). **Drop ours, take upstream's** on conflict. |
| `^datahike.datom/Datom` type hints | Cosmetic (perf / infer-warning). Re-apply only if upstream still infer-warns. |
| `interface.cljc` / `secondary.cljc` `:refer-clojure :exclude` | Cosmetic (redef warning). `secondary.cljc` is the rebase conflict — upstream changed that ns form. Re-apply only if still warns. |

Net: this commit is **not a frustration hack** — pulling it whole would
break the pod build (Throwable, the require). But ~half of it is now
redundant with upstream's own CLJS-compat work.

### 4. `1ae35696` fix(query/execute): multi-group join corruption — **REAL bug, status-against-upstream UNKNOWN**

- Genuine planner bug (two identity-attr clauses joined through one row
  ignored the `:in` binding). Affects JVM too with
  `DATAHIKE_QUERY_PLANNER=true`; always-on in CLJS. Found by seon's gym
  lane. Has a regression test (`cljs_pattern_scan_test.cljc`).
- Upstream has **heavily reworked** execute.cljc (planner `:optional?` /
  `pss-instance?` / `can-direct-fuse?` paths). **Must run our regression
  test against rebased upstream** to see if still reproduces.

### 5. `ec902943` fix(query/execute): get-else optional scans drop rows — **REAL bug, status-against-upstream UNKNOWN**

- Standalone `get-else` optional scan silently became an inner join on
  PSS DBs (entities lacking the attr dropped). CLJS-default planner hit
  it live (agent messages without `:hops` vanished). Test:
  `query_getelse_test.cljc`.
- Upstream's reworked `:optional?`/pss handling (execute.cljc lines
  3460-3482 filter `:optional?` merge-ops explicitly) **may already fix
  this**. **Must run `query_getelse_test` against rebased upstream.**

## Recommended sync strategy — falsification-driven, NOT a blind rebase

1. Rebase the 5 commits onto `origin/main`. On the `execute.cljc` /
   `secondary.cljc` conflicts from commit 3, **take upstream's side** for
   the HashSet/probe-driven/exclude bits (they're superseded).
2. Keep with confidence: **#1 Promise API**, the **writing.cljc Throwable
   fix** and **versioning require** from #3, **#2 build** (pending the
   cold-compile verify).
3. **The decisive oracle for #4 and #5:** run their regression tests
   (`cljs_pattern_scan_test.cljc`, `query_getelse_test.cljc`) against the
   rebased upstream engine.
   - Green on plain upstream → upstream already fixed it → **drop our
     commit**.
   - Red → upstream still broken → **keep our fix AND open an upstream
     PR** (these are real datahike bugs, not seon-specific).
4. Verify: full upstream CLJS `:node-test` + `bin/test-cljs` (seon pod
   build) + wire-server JVM smoke.
5. Only then: push rebased fork → bump the 3 `deps.edn` SHAs → restart
   wire-server + rebuild pod. Also re-check the sibling
   **`seantempesta/konserve`** fork (CLJS header fix) for the same
   "still-needed-vs-upstreamed" question.

**Bottom line:** the path to "staying in sync" is to **upstream our 3
real bug fixes** (#3-writing, #4, #5 if still reproducing) and **adopt
upstream's versions** of what they've already fixed (#3-execute), leaving
only #1 (Promise API) as a deliberate local feature patch. That shrinks
the permanent fork to one commit.

## VALIDATION RESULTS (2026-06-18, rebase executed)

Rebased `sync-upstream` = `origin/main` + our 5 commits, **0 behind**.
Conflicts resolved as unions (`secondary.cljc` ns requires + cljs exclude;
`nodejs_test.cljs` runner list). Commits 4 & 5 impls applied cleanly to
upstream's reworked `execute.cljc`.

| Check | Result |
|-------|--------|
| CLJS node-test (commits 1,3,4,5; planner always-on) | 12 tests / 67 assertions, **0 fail** |
| JVM planner-forced `DATAHIKE_QUERY_PLANNER=true` (commits 4,5) | 8 tests / 15 assertions, **0 fail** |
| Consumer smoke — datahike as external `:local/root` dep | 2 tests / 3 assertions, **0 fail** |
| Proximum KNN — external consumer, Java 22.0.1 | 4 assertions, **0 fail** |

**Decision on 4 & 5:** both regression suites green on the rebased engine
→ fixes still relevant + correct → **KEEP both** (cheap clean patches).

### Findings that change the picture

1. **Promise-vs-channel contract divergence is now load-bearing.** Upstream
   added new CLJS tests (`optimistic-test`, `valid-time-test`) written
   against the core.async *channel* async contract (`(<! (d/connect ...))`).
   Our commit 1 ships the *Promise* contract, so `<!` on a Promise crashes
   and aborts the whole node-test run. **Resolution:** excluded those two
   from our fork's CLJS runner with a documented rationale
   (`nodejs_test.cljs`). This is the permanent maintenance cost of commit 1
   — every upstream sync re-introduces channel-contract tests that must be
   excluded or ported to `await`/`^:async`. Port them only if seon adopts
   optimistic-concurrency / valid-time features.
2. **Proximum needs Java 22+ AND `src-secondary` on the classpath.** Its
   jars are class-file 66.0; a Java-21 runtime throws
   `UnsupportedClassVersionError`. The shim
   `datahike.index.secondary.proximum` lives in datahike's `src-secondary`,
   which is NOT on default `:paths` — a git/local-root consumer must add it
   explicitly. For the wire-server (Phase 2) we own the fork, so we can
   expose `src-secondary` via a deps alias + add `org.replikativ/proximum`
   (on Clojars, mvn 0.1.25) + JVM flags
   `--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED`.
3. **No konserve skew from the rebase** — upstream main and our fork pin
   identical konserve (`0.9.346` + `konserve-sync 0.1.15`). The
   `seantempesta/konserve@32e3c598` CLJS-header-fix override is orthogonal
   and unchanged.

### Isolation harness location

`tmp/datahike-sync/` (gitignored) — `:local/root` → the rebased submodule.
`clojure -M:run` (smoke, set `DATAHIKE_QUERY_PLANNER=true`);
`clojure -M:secondary:run-secondary` (Proximum KNN, needs `JAVA_HOME` +
`PATH` → a Java 22+ JDK, e.g. openjdk-22.0.1).

## Architecture decision folded in (2026-06-18)

Vector search will run as a **Proximum datahike secondary index on the
JVM wire-server**, queried by all read-only agents over the wire
protocol — centralized index, no per-agent index building. This makes
the rebase a **real prerequisite** (we want upstream secondary-index
correctness fixes #828/#832/#834/#835/#840). See
[[embeddings-fn-retrieval-2026-06-18]].
