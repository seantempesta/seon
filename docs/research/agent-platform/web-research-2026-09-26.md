---
type: research
lane: web-research-opus
date: 2026-09-26
status: research only; no src edits
---

# Outside evidence for per-form cost and boot time (2026-09-26)

**What this is.** This note gathers what other Clojure and JVM projects do, and what they measured, for five questions:
- commit latency;
- incremental analysis;
- cached evaluation pipelines;
- instrumentation cost;
- Caffeine.

Every claim cites a URL or a vendored `file:line`. The pinned revisions under `reference-code/` are:
- datahike `67b00071` (Seon fork)
- konserve `5b39fdd`
- clj-kondo `57252e07`
- malli `43c6dca8` (fork; upstream compared at `origin/master` `76603d58`, 2026-09-25)
- sci `743d1c43`
- clerk `4c25999`
- clay `5ec4efe`
- portal `7f12494`
- nrepl `f3a0441`
- Caffeine `v3.2.4` (`deps.edn:30`), read from GitHub

Local measurements taken for this note are marked **measured here**. JVM: OpenJDK 26.0.1 (Homebrew). clj-kondo: the v2026.07.24 native binary.

## 1. Datahike and konserve commit latency

### What a commit writes

- **Index flushes.** `commit!` flushes every dirty index (`reference-code/datahike/src/datahike/writing.cljc:73-84`):
  - EAVT, AEVT and AVET;
  - with `:keep-history?` also the three temporal indexes, which is **six** dirty paths per commit instead of three.
- **The batch.** The flushed nodes (`pending-kvs`) are written as one ordered `k/multi-assoc` batch (writing.cljc:505-548). The batch holds:
  - the nodes (children before parents);
  - the schema-meta, when it changed;
  - the commit record `cid` (unless `:commit-graph? false`);
  - the mutable branch head, last.
- **Fused roots.** Seon sets `:fuse-index-roots? true`, `:branching-factor 4096` and `:keep-history?` (`src/seon/cluster/store.clj:173-192`). Fusion inlines the index roots into the commit record, so they are not separate keys.
- **The file store has no atomic multi-key write.** Each key goes through the default single-key write (`reference-code/konserve/src/konserve/impl/defaults.cljc:59-126`):
  1. write `<key>.new`;
  2. `-sync` the blob (a `FileChannel.force`), when `:sync-blob?` (defaults.cljc:107-109);
  3. atomic-move it over `<key>`;
  4. `-sync-store`, which forces the **store directory** (defaults.cljc:116-118; `filestore.clj:43-53`, `:219-222`).

  `:sync-blob? true` is the default (filestore.clj:758-760).
- **Result: two forces per key**, so a commit costs `2 × (dirty nodes + commit + head)` forces. That matches the 12-16 forces Seon counted on default (`docs/research/agent-platform/async-commit-design-2026-09-26.md`, "Broken first" §4).

### Why a force is expensive on this machine

- **The JDK's macOS `force` is `F_FULLFSYNC`.** OpenJDK's macOS `FileDispatcherImpl.force0` calls `fcntl(fd, F_FULLFSYNC)` unconditionally. It falls back to `fsync` only on non-local filesystems, and it ignores the `metaData` flag
  (https://raw.githubusercontent.com/openjdk/jdk/master/src/java.base/macosx/native/libnio/ch/FileDispatcherImpl.c). This has been true since JDK 9 (https://bugs.openjdk.org/browse/JDK-8080589, "Fixed", 9 b66).
- **`F_FULLFSYNC` flushes the whole drive cache, not one file.** Apple's guidance is to use it for power-loss guarantees, and `F_BARRIERFSYNC` as the faster ordering-only alternative (summarised at <https://bonsaidb.io/blog/acid-on-apple/>, which links <https://developer.apple.com/documentation/xcode/reducing-disk-writes>).
- **Measured cost on current Apple hardware.** pnpm measured `F_FULLFSYNC` on an M5 Pro running macOS 26.6 (https://github.com/pnpm/pnpm/pull/15354):
  - **3.9 ms median per call with 1 writer, 10.7 ms with 12 writers**;
  - plain `fsync`: **0.02 ms / 0.10 ms**;
  - about 9,800 calls spent 64-66 s. pnpm's fix switched index markers to plain `fsync`.
- **Other stores' defaults.** SQLite's `PRAGMA fullfsync` and `checkpoint_fullfsync` both **default off** (https://www.sqlite.org/pragma.html). LMDB defines its data sync on Apple as `fcntl(fd, F_FULLFSYNC)`, but it syncs once per write transaction, not once per key
  (https://raw.githubusercontent.com/LMDB/lmdb/mdb.master/libraries/liblmdb/mdb.c, `MDB_FDATASYNC` at about line 176).

**What it means for us.** 12-16 `F_FULLFSYNC` calls × 3.9 ms ≈ **47-62 ms**. That is essentially the whole ~64 ms default commit, and most of the ~118 ms settlement. The cost is not Datahike CPU or bytes; it is the *number* of drive-cache flushes. Because each flush drains the drive's whole cache, the flushes after the first in a batch buy no extra durability for data that earlier `fsync`s have already delivered to the drive.

### Upstream state

- **No group commit or WAL.** Datahike has no write-ahead log: `grep` for WAL/tx-log in `reference-code/datahike/src` finds nothing. Datahike's writer already batches: the commit loop persists the last value of a drained batch (writer.cljc:229-264, `:commit-wait-time` at :285). Upstream calls group commit "the genuine writer opportunity (+50-80% in isolated experiments, ~35k tps direct-PSS ceiling)" and withdrew the async-ack flag `:sync-commit? false`, calling it a durability trade that deserves a design discussion (https://github.com/replikativ/datahike/pull/933).
- **Batching was the historical answer to slow transactions.** Issue #479: 200 entities took 30 s on the file store. The fix was batching with the persistent-sorted-set backend: 5,000 entities ≈ 500 ms (https://github.com/replikativ/datahike/issues/479).
- **Datahike's own guidance on the file store.** Its backend guide calls the file backend "Not ideal for databases with a lot of churn" and ranks LMDB above File for writes (https://github.com/replikativ/datahike/blob/main/doc/storage-backends.md).
- **konserve-lmdb.** The backend (Panama FFI, Java 22+) supports atomic multi-key operations in one LMDB transaction (https://github.com/replikativ/konserve-lmdb, README "Multi-key operations: Atomic bulk operations supported"; `MDB_NOSYNC` is optional). Datahike takes the `multi-assoc` path whenever a store is multi-key capable (writing.cljc:505). On LMDB the whole commit is therefore one transaction and one or two flushes.
- **Datomic logs first and indexes later.** Its transactor makes each transaction durable in the log, keeps a memory index of "transaction data that has been logged to disk but not yet committed to a disk index", and merges indexes in background jobs triggered by `memory-index-threshold` (https://docs.datomic.com/operation/capacity.html). Datahike writes index nodes on every commit, which is write amplification per transaction.

**Configuration that trades durability for latency.** These are listed for completeness; the owner has ruled against the first two for Seon.
- konserve `:config {:sync-blob? false}` skips both forces per key (defaults.cljc:107, :116).
- Datahike `:commit-graph? false` drops the commit record key (writing.cljc:515, :531).
- `:keep-history? false` halves the dirty index paths (writing.cljc:79-84).
- `:commit-wait-time` groups concurrent commits (async-commit-design measured 4 agents: 4.5 → 1.5 forces per form).

## 2. Incremental static analysis

**clj-kondo.**
- **Cache contents.** The cache is per namespace, one transit file of *exported* var data per `lang/ns` (`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:20-21`). It is written when a namespace is linted (`update-defs`, :146-169) and read only for namespaces the linted file *requires* (`sync-cache*`, :171-208).
- **Cost follows the linted file.** Linting one file therefore costs one file plus the cache reads of its requires. **Measured here:** `src/seon/fn.clj` (4,089 lines) linted in **0.11 s**, with or without the cache.
- **Source files have no content key.** A source file is re-linted whenever it is passed in.
- **Jars are skipped by name and configuration.** A skip mark is keyed `jar-name + config-hash` (core.clj:488-505). A configuration change (hooks, macroexpansion) therefore invalidates every dependency jar. SNAPSHOT jars are never skipped.
- **Full-lint timings.**
  - **Measured here**, whole Seon checkout (482 files, 190,912 lines, `--cache false`): **6.2 s** serial, **4.9 s** `--parallel`. With analysis output (arglists and keywords, EDN): **6.8 s** and a 92 MB result.
  - Upstream, a large Leiningen classpath: 59.4 s → 35.9 s with `--parallel` (https://github.com/clj-kondo/clj-kondo/issues/632).

**clojure-lsp** (fetched `lib/src/clojure_lsp/startup.clj`, `db.clj`, `kondo.clj` from <https://github.com/clojure-lsp/clojure-lsp>, master).
- **Analyzer identity is a small key.** The persisted analysis is reused only when four values all match (startup.clj:340-348):
  - `project-hash` (the classpath specs);
  - `settings-hash`;
  - `kondo-config-hash` (`clj-kondo.core/config-hash` over the resolved `.clj-kondo` configuration, kondo.clj:92-102);
  - the dependency scheme.

  A hand-bumped cache format number (`(def version 17)`, db.clj:24) guards the stored shape. The analyzer's own code is never hashed.
- **Per-file reuse.** Within a valid cache, only source files whose checksum (mtime) changed are re-analyzed; deleted files are evicted (startup.clj:91-133). This was issue #2316, "warm initialize here from ~13s to ~8s", where project clj-kondo analysis was 5.4 s of the 13 s (https://github.com/clojure-lsp/clojure-lsp/issues/2316).
- **Publishing, not analysis, dominated startup.** On a 3,651-file monorepo, warm `initialize` was 73.1 s, and **59.0 s of it was `publish-all-diagnostics`**: a serial drain with a 25 ms per-URI debounce, while clj-kondo analysis was 0 ms (cached) (https://github.com/clojure-lsp/clojure-lsp/issues/2326). The CHANGELOG records warm `initialize` "from ~73s to ~8s" once publishing moved off the critical path (https://github.com/clojure-lsp/clojure-lsp/blob/master/CHANGELOG.md).
- **Dependents.**
  - A dep-graph of namespace dependents is kept in the database and refreshed whenever analysis is merged (`dep_graph.clj`; kondo.clj:104-110).
  - `:notify-references-on-file-change` (default true) re-lints only the files that reference a changed file (https://clojure-lsp.io/settings/).
  - Dependency jars are analyzed with var bodies skipped: "Decrease uncached startup time by 60-70%" (CHANGELOG; <https://github.com/clj-kondo/clj-kondo/pull/1674>).
- **Allocation mattered too.** Metabase cut full analysis from 179 s to 47 s: 145 GB → 51.6 GB allocated, 95% of the work in clj-kondo analysis (https://www.metabase.com/blog/improving-performance-clojure-development-tools).

**What it means for us.** Seon's 83 s republish is triggered when `:seon.source/analyzer-digest` changes, and it changes whenever any of 430 declarations in analyzer reach changes (`docs/research/agent-platform/landing/perf-boot-opus-2026-09-26.md`, "Resume" section).
- **Neither tool hashes its own code.** clj-kondo keys on the jar plus the configuration; clojure-lsp keys on its configuration hashes plus a format version.
- **The work is elsewhere.** clj-kondo over the entire checkout is 5-7 s, so roughly 76 s of the 83 s is Seon's row derivation, transaction and publication. That is the same shape as clojure-lsp #2326, where publishing, not analysis, cost 81%.
- **What an identity change should cost.** "Analyzer identity changed" should mean the kondo version, kondo configuration or row-shape version changed. That case is rare and costs one full kondo pass (~5-7 s) plus a *diff* of rows. Every other change is per changed file.

## 3. Evaluation pipelines with caching

**Clerk** (`reference-code/clerk`).
- **What a form defines.** Clerk decides this with tools.analyzer. It macroexpands, collects the vars of `:def` nodes and the vars, classes and bindings each form references (`analyzer.clj:111-160`, `get-vars+forward-declarations` :96-109).
- **A block's hash is a Merkle node.** It is the SHA-1 of the canonicalized form text, the sorted hashes of its **immediate** dependencies, and the vars it defines (`hash-codeblock`, analyzer.clj:625-650). Hashes are computed in topological order of the dependency graph (`hash`, :652-667).
- **Dependencies from outside the notebook.** A dependency in a jar is hashed as the whole jar, or by the gitlib SHA. A dependency in a source file is analyzed per file, and that analysis is cached by the file's SHA-1 (`analyze-file`, :362-377; jar handling :545-566).
- **Results.** Results are cached in memory by block hash (`blob->result`) and on disk in a content-addressed store using nippy (`eval.clj:35-61`, `read+eval-cached` :181-225).
- **Not cached:** forms with namespace effects, `^:nextjournal.clerk/no-cache`, values that cannot be frozen, and redefinitions (`set-no-cache-on-redefs`, analyzer.clj:496-505). "No cache" is inherited by dependents (`make-deps-inherit-no-cache`, :252-265).

**Clay and Kindly.**
- Clay reads forms and evaluates each one with the plain Clojure `eval` (`clay/src/scicloj/clay/v2/notebook.clj:86-132`). It has no result cache.
- It recognizes a defining form by a hand list of head symbols (notebook.clj:21-36: `defn`, `defmacro`, …), which is weaker than analysis.
- Kindly's whole contract is metadata on the value, `{:kindly/kind k}` (`kindly/src/scicloj/kindly/v4/api.cljc:12-14`). Every renderer (Clay, Clerk through kindly-render, Portal) chooses its viewer from the value. This is the same shape as Seon's `:seon.render/*` override keys.

**Portal.**
- Values stay in the runtime's per-session `value-cache`, keyed to integer ids (`portal/src/portal/runtime.cljc:216-233`). The UI requests children by id, so only what is expanded crosses the wire and gets rendered.
- `::no-cache` metadata opts a value out (:311-318).
- This is the "hold references, render what is shown" pattern.

**nREPL.**
- Evaluation returns the value object. Printing is a separate middleware at the edge, with one byte budget: `*quota*`, implemented by a `QuotaBoundWriter` that throws past the limit (`nrepl/src/clojure/nrepl/middleware/print.clj:33-36`, :50-57).
- This is Seon's "print only at the edge; one clipping spot".

**SCI and Babashka.**
- `eval-form*` analyzes a form once (`ana/analyze`) into a node tree, then evaluates that tree (`sci/src/sci/impl/interpreter.cljc:51-60`). A top-level `do` is split and each child is analyzed separately (:34-43).
- There is no result cache; the reusable unit is the analyzed node.

**What it means for us.**
- **Reusable directly: Clerk's hash rule.** A definition digest should be form + the digests of the vars it *references* + the vars it defines. It should not include the namespace's full resolver context. This is exactly task `434fed5a3a67` ("hash only the aliases a definition resolves"). Clerk shows that the rule is correct under redefinition, and that jars can be treated as opaque identities.
- **Also reusable: Clerk's no-cache inheritance** for effectful forms.
- **Already matched: nREPL and Portal.** Both confirm Seon's print-at-edge and hold-by-reference design; there is nothing new to import.

## 4. Malli instrumentation cost

**Upstream guidance.**
- Var instrumentation "is intended for development time, but can also be used for production builds" (`reference-code/malli/docs/function-schemas.md:637`).
- The options are `:scope` (default `#{:input :output}`), `:report` and `:gen` (:335-337). `mi/instrument!` adds `:filters` (`src/malli/instrument.clj:18-45`).
- **Validator reuse.** `m/validator` caches on the schema *instance* (`-cached`, core.cljc:353-361; `validator` :2672-2677). Rebuilding the schema per call loses the cache. Issue #1269 shows the cost: 5 ms → 139 ms per router build when a schema with 150 refs was re-created each time, then fixed by moving the cost back to validation time (https://github.com/metosin/malli/issues/1269).
- **Seqex is slow for simple shapes.** The README states it: `[:* int?]` 2.7 µs versus `[:sequential int?]` 0.12 µs; "it is always better to use less general tools whenever possible" (`reference-code/malli/README.md:575-597`).

**Seon's fork versus upstream.**
- The fork commit `43c6dca8` does three things:
  - validates an item-only `:cat` by position, with no regex driver;
  - checks rest args without building a vector;
  - skips the guard check when no guard is declared.

  Measured there: a 1-argument predicate went from 334 ns / 704 B to 149 ns / 59 B per call, and a `[:cat map kw [:or nil int]]` validator from 224 ns / 528 B to 80 ns / 24 B.
- Upstream `origin/master` (76603d58, 2026-09-25) still has `validate-guard (or … any?)`, `(vec args)` on every call, and `(-validator [this] (regex-validator this))` for `:cat` (core.cljc:2222, :2226, :2420).
- A search of upstream issues and PRs for "cat validator", "regex performance", "instrument performance" and "instrument overhead" finds no equivalent proposal.
- Upstream does accept targeted perf PRs of this style, for example:
  - #756, regex driver cache: parse 7.9 µs → 6.5 µs (https://github.com/metosin/malli/pull/756);
  - #1219 and #1239;
  - open #1295, `clj-collect!` 534 ms → 45 ms.

  So the `:cat` fast path is a plausible upstream PR. That is not our call to make, since the laws forbid PRs without the owner.

**Guardrails.** It is the one Clojure project that publishes instrumentation overhead with Malli (https://github.com/fulcrologic/guardrails, README.adoc "Malli Performance" and "Limiting Max Checks Per Second"):
- Per-call overhead fell from ~4,000 ns to ~800 ns (trace on) or ~545 ns (trace off), by compiling validators with `m/validator` once and hoisting the options map to definition time.
- `:guardrails/mcps` samples: each function is checked at most N times per second. A check is ~20 µs, the limiter ~20 ns, and statecharts runs got "literally 1000x faster".

**What it means for us.**
- **Build validators once per arm.** Validators must be built when a contract is armed, never per call. Any per-call `m/schema` or `m/validator` with a vector form is the #1269 trap.
- **Avoid the variadic wrapper.** Malli's wrapper is `(fn [& args] … (apply f args))`, which allocates a seq and goes through `apply` on every call. Guardrails generates fixed-arity code at definition time. Generating per-arity wrappers for `:=>` schemas is the next allocation cut after the fork's `:cat` path. It is **unmeasured**.
- **Sampling needs the owner.** Sampling (MCPS) conflicts with "arm every valid contract: invalid inputs prevent entry" (CLAUDE.md), so it is an owner option, not a fix.

## 5. Caffeine from Clojure

**Idioms.**
- **Recursive derivations need an `AsyncCache`.** The Caffeine FAQ says a load or compute "may not write into the cache" recursively, and prescribes an `AsyncCache` holding a `CompletableFuture` completed outside the atomic scope (https://github.com/ben-manes/caffeine/wiki/Faq). `seon.cache/lookup` (`src/seon/cache.clj:115-175`) follows that recipe: `putIfAbsent` of its own future, then derivation on the caller's thread.
- **Weights are set at completion.** Map-view `putIfAbsent` attaches `handleCompletion`, which calls `replace(key, f, f)` on completion so that the weight is recomputed (Caffeine v3.2.4 `LocalAsyncCache.java:203-240`, :395-401). An in-flight future weighs 0 (`Async.java:156-158`). Seon's weigh-after-completion is therefore accounted.
- **Failures should complete normally.** An exceptionally completed future is logged at WARNING and removed (LocalAsyncCache.java:207-212). Seon's choice to complete normally with `::thrown` avoids a log line per refusal.
- **Weights are static.** They are "computed at entry creation and update time, are static thereafter", and "relative weights are not used when making eviction selections" (https://github.com/ben-manes/caffeine/wiki/Eviction).
- **Background work runs on the common pool.** Maintenance and removal listeners run on `ForkJoinPool.commonPool()` unless `executor` is set (`Caffeine.java:353`, :374). For deterministic tests, use `.executor(Runnable::run)`.
- **Wrappers.** AppsFlyer's cloffeine (https://github.com/AppsFlyer/cloffeine) is a thin builder over the four cache types. Its existence confirms interop is the idiom; there is no Clojure-specific semantics to adopt.

**Weigher accuracy.**
- clj-memory-meter wraps JAMM through dynamic agent attach. It needs `-Djdk.attach.allowAttachSelf`, uses `Unsafe` on Java 17+ ("can potentially crash the application"), and is meant for inspection, not production (https://github.com/clojure-goes-fast/clj-memory-meter).
- A deep measure walks the whole graph and counts structure shared with other entries. Persistent collections share heavily, so a retained size over-counts per entry.
- Seon's "count × per-kind calibration" is therefore the right runtime weigher. Use clj-memory-meter **offline** to fit each kind's factor and record the fit beside the constant.

**One pitfall to check.** `seon.cache` allows "the immutable value itself" as a key (cache.clj:4-6). `ConcurrentHashMap` compares keys with `equals` after a hash match. Clojure caches `hasheq` on collections, but an *equal, non-identical* large value costs a full structural comparison on every hit. For large values, prefer digest keys. This is unmeasured here.

## Ranked: five findings most likely to cut per-form cost or boot time

1. **Cut `F_FULLFSYNC` count per commit from ~2 per key to ~2 per commit.**
   - *Sources:* JDK macOS `force0` = `F_FULLFSYNC`; pnpm measured 3.9 ms median per call versus 0.02 ms for `fsync`; konserve forces blob plus directory per key (defaults.cljc:107-118); Seon counted 12-16 forces per default commit.
   - *Change:* in the fork's konserve file store, give `multi-assoc` a batch path:
     1. write and rename every node with plain `fsync` (through the Panama FFI; JDK 26 is available);
     2. issue one `F_FULLFSYNC` barrier before the branch-head write, and one after it.

     Order and ack-durability are unchanged, and `:sync-blob?` semantics are kept.
   - *Expected:* ~50-60 ms → ~8-10 ms per commit.
   - *Alternative:* the konserve-lmdb backend, where one LMDB transaction per commit gives the same count.
   - *Verify:* JFR `jdk.FileForce` counts plus a commit-latency clock row.
2. **Make analyzer identity = kondo version + kondo config hash + a hand-bumped row-shape version; re-derive per changed file.**
   - *Sources:* clojure-lsp startup.clj:340-348, db.clj:24, #2316; clj-kondo skip mark `jar + config-hash` (core.clj:488-505).
   - *Change:* replace the 430-declaration `analyzer-digest` (option 2 in `perf-boot-opus` "Resume").
   - *Expected:* removes the 83 s `:all` republish from ordinary platform commits. A full kondo pass is 5-7 s (measured here).
3. **Keep publication off the critical path, and stop paying per-item serial cost.**
   - *Source:* clojure-lsp #2326, where 59 of 73 s was per-URI serial publish while analysis was 0 ms.
   - *Change:* profile the remaining ~76 s of the 83 s republish outside kondo (row derivation, validation, the 12.7 s `adopt-rows!` transaction) for per-row serial work. Then publish readiness before the full row rewrite completes.
4. **Hash definitions like Clerk: form + referenced vars' digests + defined vars.**
   - *Source:* `clerk/analyzer.clj:625-650`.
   - *Change:* task `434fed5a3a67`. Drop the namespace resolver context from definition digests.
   - *Expected:* a `require` edit stops rehashing every declaration in the namespace (20 in `seon.program` at c029d48cb). That narrows adoption, test selection and analyzer reach together.
5. **Hold compiled validators per arm, and generate fixed-arity wrappers.**
   - *Sources:* Guardrails, ~4,000 → ~545-800 ns per call by compiling once and hoisting options; malli #1269 (schema re-creation per call); malli README (seqex versus simple schemas); fork `43c6dca8` (−55-70% ns on `:cat`).
   - *Change:* audit arm-time versus call-time schema construction, and replace `(fn [& args] (apply f args))` with per-arity closures for `:=>` and `:function` contracts.
   - *Owner option (conflicts with the arm-every-contract law):* sampling in the style of `:guardrails/mcps`.
