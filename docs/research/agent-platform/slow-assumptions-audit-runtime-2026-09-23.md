---
type: reference
status: measured; fact-finding lane, no source edits
created: 2026-09-23
tags: [agent-platform, publication, adoption, runtime, sci, render, storage, audit, seconds-not-minutes]
---

# Slow assumptions audit: publication, adoption and runtime (2026-09-23)

Lane `slow-assumptions-runtime`. This covers publication, adoption and runtime in
the owner's audit (2026-09-23: "by design most ops should be sub second"). It is
fact-finding only. No src, test or resources file was edited.

Boot, reset and initial indexing belong to the sibling
[from-zero boot cost](from-zero-boot-cost-2026-09-23.md) lane. Test rituals belong to the
[tests audit](slow-assumptions-audit-tests-2026-09-23.md). Both are cited below, not re-measured.

Subject and boundary:

- Live probes ran on `default`, PID 51528, started 2026-09-22T18:46:42Z. Its loaded
  source is `394b58f09`. They used MCP `eval_clj`, explicit root, JVM mode, with
  `(seon.cluster.boot/connection "default")` or `seon.operator.runtime/running-instances`.
- Source was read at HEAD `ea41d9a1b` through `f31074521`, working tree. The tree
  was moving under concurrent lanes, so the cited line numbers are for that tree.
- Datahike fork: `reference-code/datahike` at `41c79c1a7`. clj-kondo:
  `reference-code/clj-kondo` at `57252e079`.
- Every probe was declared `read_only`. **Even so, each windowed MCP result wrote a
  transaction** (row 3). I caused about 12 such artifact transactions, each about
  1 MB of store (probe T14). No default stop, reset, reload or adoption happened.
  I started no JVM and no scratch root.

## Ranked rows (cost × frequency, top first)

"Recorded" marks a number I cite but did not re-measure. Every other number is from
the TIMINGS table below.

### 1. The RESET NEEDED ritual and the scratch from-zero boot used as a landing proof

- **Who and how often:** lanes and the orchestrator, on every schema or analyzer
  landing. The landing notes record at least 17 scratch `reset --force` boots on
  2026-09-22/23 (table in §RESET NEEDED). `default` itself was reset twice.
- **Cost:** recorded, not re-measured. Readiness was 101,378 / 101,576 / 103,142 /
  105,826 / 111,444 / 121,767 / 146,520 / 153,453 / 158,108 / 162,616 / 164,942 /
  166,166 / 180,027 / 199,190 ms. Two from the fresh-start note were 308,518 and
  366,653 ms. About 140 s median, about 40 min of wall per day, before counting the
  lane waiting on it.
- **Work:** proportional to the whole program. The sibling lane measured 170 s,
  75% of it one quadratic validator loop.
- **Assumption:** "A schema or analyzer change is only proven, or only installed, by
  rebuilding the store from zero."
- **False.** In-place accretion is installed:
  - `declaration-changes` (`src/seon/cluster.clj:1072`; `:1033` in the brief's tree)
    transacts missing and accretive declarations.
  - `accretive-property-change?` (`:955`; `:968` in the brief's tree) follows
    Datahike's own rule (`reference-code/datahike/src/datahike/schema.cljc:257-290`).
  - Two properties Datahike accepts are still refused, so they are the only real
    reset triggers. Dropping `:db/noHistory` is refused (Seon requires
    `(some? declared-value)`). Adding `:db/unique` to an attribute with cardinality
    one and no uniqueness is also refused (Datahike accepts it at `schema.cljc:271-274`).
  - The sibling lane measured an attribute added in place: publication 7,064 ms plus
    adoption 1,798 ms. Retiring a writerless attribute took 7,119 ms.
  - Forking a fresh cluster from `current-src` took 4,256 ms.
  - Every RESET NEEDED in the table below needed accretion, a backfill transaction,
    a retraction, a GC sweep or a JVM restart. None needed store destruction.
- **Verdict:** **DELETE** the ritual.
  - A landing proves a schema change by the declaration transaction on a branch of
    the live store plus the 1.3e refusal. AGENTS.md already rules this (owner,
    2026-09-23, README §4 1.3e superseded text).
  - **REPLACE** the one misnamed refusal. `classify-paths`
    (`src/seon/cluster/source.clj:143-152`) answers "RESET NEEDED" when `deps.edn`
    or a gitlink changes. What that change needs is a JVM restart on the same store:
    recorded 6,248 ms (wrappers note, line 100) and 7.3 s warm (monitor notes, line 310).
- **After:** about 2–9 s per schema change, versus about 140 s. A dependency change
  costs one warm restart.
- **Fix size:**
  - Instructions: already done.
  - Rename `::reset-needed` to a restart refusal: 1 file, about 3 lines, B1.
  - Accept the two Datahike-accepted properties in `accretive-property-change?`:
    1 file, about 6 lines, plus the regression the adoption issue names. B1 file
    half, wave B.

### 2. SCI program re-acquisition on every new commit (every agent form, every MCP SCI eval)

- **Who and how often:** `seon.sci.eval/evaluate` on every evaluation whose database
  commit differs from the context's last acquisition.
  - The turn transacts `receipt-start-call` before each form
    (`src/seon/turn.clj:861,885`), so every form in an agent turn sees a new commit.
    That per-form claim is inferred from code; the MCP path was measured.
  - Any other commit re-arms the cost too: a message, a page artifact, or an MCP
    windowed result.
- **Measured:**
  - `(+ 1 1)` in SCI mode after a commit: **1,242 ms, 3.58 GB allocated.**
  - The next eval with no commit in between: **9 ms, 11 MB.**
  - Reproduced five times (T5–T8): 372 to 1,130 ms, 0.97 to 3.57 GB.
  - Sampled phases (T8, 157 samples): 106 in `seon.fn/reverse-closure` → `gate-sets-in`
    → `declared-reference-edges` → `seon.db/q` under `with-declarations`. 31 in pulls
    through `seon.db/lookup-ref-error` → `attribute-observation`. The rest was the
    whole-program `function-digests` queries and projection derivation.
  - The same `reverse-closure` call costs 778 ms on the first query of a new commit
    and 20 ms after (T9).
- **Work:** proportional to the whole program. It rebuilds `base-ctx`: every
  function row (4,436), both digest maps, the affected closure and namespace pulls
  (`src/seon/sci/eval.clj:2303-2330` `acquire!`, `:1794` `acquire-program!`,
  `:1852-1859`). The trigger is only "commit id differs"
  (`acquired-database?`, `:2283-2301`, called from `:3034-3037`). In the measured
  case the commit changed **no program row**: it held one
  `:seon.dev.mcp.artifact/*` datom pair (T4).
- **Assumption:** "A context is valid only for the exact commit it acquired, so any
  commit forces a full re-acquisition."
- **False.** Datahike already records which attributes each commit touched:
  `advance-query-cache-context` (`reference-code/datahike/src/datahike/query.cljc:2568-2589`)
  keeps `:datahike.cache/attribute-revisions`. Its own query cache carries results
  across commits with `source-context-unchanged?` (`:2963-2975`). The live value
  shows it (T1: `:cache-context` with per-attribute revisions).
  - Program rows are a known attribute set: `:seon.fn/*`, `:seon.ns/*`,
    `:seon.schema/*`, `:seon.program/*`.
  - If their revisions and the `:datahike.cache/conservative-revision` are unchanged,
    the acquisition is unchanged. The context can then advance its snapshot database
    in O(number of program attributes).
  - When they did change, the since-diff of those attributes names the changed rows.
    Overridden rows are then those rows, not a whole-program digest diff.
- **Verdict:** **REPLACE** the commit-id comparison in `acquired-database?` with the
  program-attribute revision comparison. Re-acquire only the changed rows.
  - The ruled design phrase "reacquired at turn start and cached by commit id"
    (AGENTS.md "One JVM, many realities") is where this assumption comes from.
  - Commit id is the right key for the *value*, but the wrong key for *program
    identity*. This row should be ruled into that sentence.
- **After:** sub-millisecond for a non-program commit. For a program commit it is
  proportional to the changed rows, around the 20 ms warm closure (T9).
- **Fix size:** `sci/eval.clj`, about 30 lines. B2 §2a / 1.3d commit 1 follow-on
  (agent half, wave A/B). File-disjoint from the file half. Fixes an agent-visible
  latency on every form.

### 3. MCP "read_only" evaluations write a transaction for every windowed result

- **Who and how often:** every `eval_clj` result above the MCP window, from the
  orchestrator and lanes, many per hour.
- **Measured:** each windowed result transacts `:seon.dev.mcp.artifact/id` and
  `digest` (T4, transactions 536871169–172). One such transaction grew the store by
  984 KB and 3 files (T14).
  - That commit then invalidates row 2 (about 0.4–1.2 s on the next SCI eval) and
    row 5 (page re-derivation, about 0.9–1.1 s on the next GET).
- **Work:** one row, but it carries per-commit costs everywhere downstream.
- **Assumption:** "An oversized probe result must be settled as a durable,
  GC-owned row in the cluster database."
- **Partly true.** The blob needs an owner for GC, so the row is KEEP for results
  that should persist. The result's lifetime, though, is the prepl session. The
  session already retains raw `*1`/`*2` (tool description). `read_only` is the
  caller's declaration that the probe changes nothing, and the tool currently
  contradicts it.
- **Verdict:** **REPLACE** for `read_only` probes. Keep the windowed value in the
  session's memory, keyed by digest (private state, AGENTS.md "Private defs, atoms
  and result objects stay in memory"). Write the durable row only when a caller asks
  to keep it.
- **After:** 0 transactions per read-only probe.
- **Fix size:** `script/seon/dev/mcp.clj` plus the `get_value` reader, about 40
  lines. B1 (MCP tool, "first" in README §4). Needs an owner ruling on durability,
  because it changes what `get_value` can reach after a JVM replacement.

### 4. Core-declaration publication: reload of 374 dependent namespaces and 1,674–1,678 wrapper replacements

- **Who and how often:** every save of a core namespace through the hook
  (`seon.id`, `seon.db`, `seon.schema`, ...). The hook is paused today. Once it
  re-enables it runs on every lane edit to those files: 246 src-path touches in
  commits on 2026-09-22 alone.
- **Recorded, not re-measured** (the reload-per-declaration lane,
  [landing](../../prds/agent-platform/landing/lane-reload-per-declaration-2026-09-23.md)):
  - Baseline hook: 24,799 ms. Commit A: 15,317 ms, 374 reloads.
  - Legacy script: 21,953 ms, 374 reloads, 1,678 wrapper replacements.
  - Wrappers lane: `seon.id` docstring adoption 28,467 ms with 1,674 replacements.
- **Work:** proportional to the dependents of the namespace, not to the change.
- **Assumption:** "A changed declaration requires recompiling every namespace that
  requires its namespace."
- **False for `defn` bodies.** Var indirection carries a new root to every caller
  compiled against the Var. Only macros, protocols, types/records, `:inline` and
  `definline` are compiled into callers. Clojure reads `:inline` from the resolved
  Var at compile time (`reference-code/clojure/src/jvm/clojure/lang/Compiler.java:7507-7525,7686-7688`).
  The reload lane cites this.
  - **Ruled** (README §4 wave A, owner 2026-09-23). Commit A's selector widens to
    typed unknown while the producer facts are missing.
  - A docstring edit needs no reload at all. `alter-meta!` on the Var is the whole
    change, and the digest ignores docstrings only if declared so. That is not
    verified here, so it is left as an observation.
- **Verdict:** **REPLACE**, owned and in progress: reload-per-declaration commit B.
  Persist `:seon.fn/inline?` and constant-def facts, then reload only the declaring
  namespace for a `defn` change. Wrapper replacements then follow the reload set
  automatically: the wrappers lane already arms only changed identities
  (`e0577a6fb`).
- **After:** about the leaf cost (row 6) for any `defn` edit, whatever the fan-in.
- **Fix size:** `fn.clj`/`fn/analyzer.clj` producer plus the reload span of
  `cluster.clj` (`development-namespaces`, `:1906`). The reload-per-declaration lane
  owns it, so it is not duplicated here.

### 5. Page GET/SSE derivation re-proves every retained read, even when the commit is unchanged

- **Who and how often:** every GET, the first SSE paint, and every render-proc pass
  after a commit for each watched tab. The render proc advanced 1,332 → 1,354 passes
  during this session's probes.
- **Measured:**
  - GET `/agent/root`, `/`, `/ns/my.agents.root` with the commit unchanged: 329–360 ms
    each (T10).
  - First GET after commits: 1,074 ms. Direct `current-page` after a commit:
    899 ms (T11).
  - Sampled: 130 of 193 samples are in `seon.db/read-evidence-current?` →
    `index-evidence-current` / `index-pattern-change` / `replay-read`. The rest are
    walk and render.
  - **`/agent/root/debug`: 44,252 ms, 194 KB** (T10). I did not repeat it; it is
    over the ten-second rule.
- **Work:** proportional to all retained render calls and their read evidence. It
  does not scale with the commit's change.
- **Assumption:** "A retained render call is current only after replaying its read
  patterns against the new value."
- **False.** Datahike's `attribute-revisions` answers whether any attribute a read
  depends on changed (`query.cljc:2963-2975`). README §7 already records the result:
  "detecting the change is Datahike's own per-attribute revision comparison (0.7 ms
  for 435 reads)".
  - At an unchanged commit nothing needs checking. A page is a function of (commit,
    program snapshot, registration key, profile). It should be memoized with
    `clojure.core.cache` on that key, per AGENTS.md "No stamps".
- **Verdict:** **REPLACE.** Memoize the page by commit + program snapshot. Replace
  replay currency with the attribute-revision comparison (A2 c1, cut 2, which edits
  `turn.clj`; B2 commit 5 / cut 4 delivery). **The 44 s debug page is a defect to
  open.** No existing issue names it. It belongs to the owner's ranked index, so I
  did not file it; the orchestrator should.
- **After:** about 0 ms for an unchanged commit. Around 1 ms of revision
  comparison, plus the rendering of changed blocks, for a changed one.
- **Fix size:** `render/web.clj` `derive-page!` (`:2145`), about 20 lines for the
  memo. A2 c1 already scopes the currency mechanism. It is a cut-2/cut-4 item and
  should not be launched before its step.

### 6. Leaf publication: whole-repository capture work on every save

- **Who and how often:** every hook save of any file (hook paused; the same
  frequency as row 4, but for all files).
- **Recorded:** baseline 6,257 ms, commit A 2,665 ms, legacy script 5,054 ms.
  - Commit A phases: source build (capture + classify + analyze) 1,717 ms, program
    reconciliation transaction 227 ms, development row adoption 203 ms, reload +
    verification 10 ms, re-arm 27 ms, adoption record 27 ms.
- **Measured whole-program parts of that 1,717 ms:**
  - `test.cache/input-digests` over **3,415 files**: 180–204 ms (T12), recomputed on
    every publication for `:seon.source/test-input-digest`
    (`src/seon/cluster.clj:1752`).
  - `gitlink-digests` spawns `git ls-files --stage` at 18–22 ms per call, called at
    least twice (`:1733` and inside `capture-paths`, `source.clj:111`).
  - clj-kondo cache walk (row 8): about 313 ms per walk, run up to twice per
    analysis. That is about 626 ms.
  - Together these are about 850 ms, about half the source build, all proportional
    to the repository.
  - The kondo analysis of one changed file costs 27 ms (sibling P8).
- **Assumption:** "Publication must re-derive the whole repository's input identity
  and gitlinks to know what changed."
- **False.** The capture already digests each changed file. The stored
  `:seon.fn.file/digest` rows hold every other file's digest (`stored-path-digests`,
  `source.clj:155`).
  - The test-input digest is a function of that map: the prior map with the changed
    entries replaced (`assoc`), then one `id/digest`.
  - Gitlinks change only when `git` changes them. One read per publication is
    enough, and a dependency change goes to row 1's restart anyway.
- **Verdict:** **REPLACE.** Derive the test-input digest from stored digests plus
  the changed digests. Read gitlinks once per publication.
- **After:** about 870 ms for the leaf source build, before the kondo fix. About
  250 ms after row 8.
- **Fix size:** `cluster.clj` source-build span (`:1705-1760`) plus
  `test/cache.clj` readers, about 30 lines. B1 1.2b remainder.
  **Collision:** `test/cache.clj` is dirty in the working tree under the B4 lane
  now. Hold until it frees.

### 7. The projection derived per call where no memo or supplied value exists

- **Who and how often:** 39 `projection-from-database` sites and 77
  `carried-projection` sites in src. They run on every constructor, context fork and
  read that lacks the projection.
- **Measured on `default` (old code):**
  - `projection-from-database`: 137–197 ms and 363 MB per call, 3,285 forms. The
    first call equals the second, so there is no memo (T1–T3).
  - `carried-projection`, which reads the metadata stamp: 0.008–0.04 ms.
  - `fork-cluster-ctx`, 3-arity (derives the projection): 161–171 ms. The 4-arity
    with a supplied projection state: 8.9–10.6 ms (T3). `sci/fork` itself:
    0.04–0.06 ms.
- **Recorded:** acquire! 1,658 ms, fixture projection 3,996 ms (README §5).
- **Assumption:** "A caller without the projection must derive it from declaration
  rows."
- **Resolved by ruling.** The projection is a memoized function of the value, keyed
  by `:cache-context` (README §7, owner 2026-09-23). The working tree has it
  **uncommitted** in `src/seon/db.clj` `carried-projection`
  (`cache/lookup-or-miss projection-cache …`, around line 1253), but `default`
  predates it. It removes the per-call 137–197 ms, not the 3-arity fork's extra
  9 ms of environment reconstruction.
- **Verdict:** **REPLACE**, owned: the memoized-projection slice plus the 1.4 sweep
  (`lane-projection-as-a-read-sweep.md`). The one extra recommendation: its keying
  should use the same program-attribute revisions as row 2. That way a non-program
  commit hits the memo instead of re-deriving 3,285 forms once per commit.
- **After:** a cache hit is about 0.03 ms. A miss happens once per program change,
  not once per commit.
- **Fix size:** already in the owning lane. Adding the key change is about 5 lines
  in the same function.

### 8. clj-kondo cache: a whole-cache walk per analysis that still misses changed content

- **Who and how often:** every publication analysis, through `invoke-kondo`
  (`src/seon/fn/analyzer.clj:261-287`). It calls `discard-obsolete-cache-entries!`
  (`:224`) before `run!`, and again after it to decide on a second `run!`.
- **Measured:** 2,352 transit entries, 14 MB. Reading and parsing all of them takes
  313 ms (T13). One analysis therefore pays about 313–626 ms plus a possible second
  kondo run.
- **Assumption:** "Cache entries are stale only when their source file moved or
  vanished."
- **False.** The [issue filed 2026-09-22](../../seon/issues/publication-analysis-reads-a-stale-kondo-cache-entry-for-an-unindexed-caller-target.md)
  shows a changed-content entry being trusted. clj-kondo loads an entry by
  namespace name with no content check (`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:127-144`
  `load-when-missing`), so freshness is the caller's job.
  - The capture already knows exactly which files changed, with digests
    (`source.clj:104-123`).
- **Verdict:** **REPLACE** the walk. For each changed checkout file, including
  `script/` (a declared root, `deps.edn:142`), `forget-namespaces!` (`:289`) its
  entry and lint it. The walk and the second `run!` go away.
- **After:** about 27 ms per changed file (sibling P8). The stale-arity class also
  closes.
- **Fix size:** `fn/analyzer.clj`, about −40 lines / +10, plus the issue's
  regression. B1 file half. `fn.clj` and `analyzer.clj` are hot files; the reload
  commit B lane holds them now, so this is sequenced after it.

### 9. Storage growth per transaction, and reclamation by reset instead of by sweep

- **Who and how often:** every transaction on any branch of the development store.
- **Measured:**
  - One 3-datom artifact transaction: +984 KB, +3 files (T14).
  - The store is 4.3 GB and 3,651 files (T14). The monitor recorded 146 MB after
    the reset of PID 51528; default's own branch has only about 270 transactions
    since then.
  - **Recorded:** about 150 MB per adoption and 7.4 GB in 15 min (README §4 1.3c).
    248 GiB before the incident fix.
  - GC sweep: 43.9 ms synthetic, 5 keys (retention landing, line 282).
- **Assumption:** (a) "Store bytes are reclaimed by a reset." (b) "Each commit must
  rewrite whole index leaves."
- **(a) is false.** GC through Datahike's sweep and konserve `sweep!` is installed:
  `registry/collect!` (`src/seon/cluster/registry.clj:487`) over
  `reference-code/datahike/src/datahike/gc.cljc:83`. Implicit collection refuses
  when the retention policy is missing, by ruling. That is fine, but nothing ever
  runs it on `default`.
- **(b) is the Datahike persistent-set leaf-rewrite cost.** A2 1.3c owns it. It is
  not re-analysed here.
- **Verdict:** **KEEP** the sweep. **REPLACE** "RESET NEEDED to reclaim disk" (store
  growth landing, lines 10-12) with `collect!` under the declared window. The
  growth itself stays with A2 1.3c's publication-script rows.
- **After:** reclamation takes milliseconds to seconds, with no re-index.
- **Fix size:** operational. Also one orchestrator step, `collect!` at each
  check-in; the command already exists. Adding it to `bin/seon` is the operator's
  change, about 10 lines, B1.

### 10. Whole-program contract arming on adoption

- **Recorded:** after `e0577a6fb`, arming inspects only changed identities. The
  replacement count equals the reload set: 3 for a leaf edit, 1,674 for `seon.id`
  (wrappers landing, table at line 104).
- **Assumption (old):** "Every adoption re-inspects every contract."
- **Retired.** KEEP the current mechanism. Its remaining cost is row 4's reload set.
- **Recorded anomaly:** "Unchanged alignment" went 505 → 4,093 ms in the wrappers
  landing, while the reload lane then observed a 124–347 ms no-change. That is an
  unexplained earlier regression, not reproduced here. It should be re-read from the
  next committed clock row before anyone cites either.

### 11. Cheap operations (KEEP, with proof)

- `bin/seon status`: 74 ms wall (T15).
- MCP JVM eval of a trivial form: 1–2 ms in the JVM (T4). The MCP client round trip
  is not separately observable from here.
- `seon.config/effective`: 1.9–2.1 ms (T16). The per-page and arm-time config read
  is not a cost.
- `sci/fork`: 0.04 ms (T3). Sibling P7b: 0.0005 ms.
- `fork-cluster-ctx` with a supplied projection state: about 9 ms. This is the
  candidate/test handle cost once row 7 lands.
- Datahike raw query-result cache: repeated `d/q` 0.037 ms against 62.8 ms first.
  `seon.db/q` is 17 ms both times, because it does not reuse Datahike's result cache
  for identical reads at the same commit (T17). That is an A2 observation; I opened
  no row, because A2 c1 already owns read evidence.

## RESET NEEDED: every request and what it actually needed

This lists every landing note that asked for, or performed as proof, a reset
(`grep -in reset docs/prds/agent-platform/landing/*.md`, 2026-09-23). Scratch
readiness times are recorded, not re-measured.

| landing note | asked for | what the change needed | in-place accretion / new key / retraction / other | reset needed? |
|---|---|---|---|---|
| `lane-b1-definition-digest-2026-09-22.md:26` (RESET batch 1) | reset so existing rows carry the new required `:seon.program/definition-digest` | a new attribute, plus a value on every existing declaration row | **accretion** (a missing declaration is transacted by `declaration-changes`, `cluster.clj:1072`) **plus a backfill transaction**. The value is computable from the stored row: `program/definition-digest`, already used as the fallback at `sci/eval.clj:862-865` | no |
| `lane-a2-storage-retention-2026-09-22.md:187` | reset when the fault `:count`/`:last-at` gain `:db/noHistory` | a `:db/noHistory` property on two installed attributes | **accretion**. `:db/noHistory` is always updatable (`datahike/schema.cljc:287`; `accretive-property-change?` accepts it) | no |
| `lane-fault-no-history-2026-09-23.md:47,145` | reset of `default`; claims the fixture base "retains the old immutable Datahike schema until reset" | same as above | **accretion**. The "immutable" claim is false for this property. The fixture base needed republication, not reset | no |
| `lane-error-schemas-2026-09-22.md:22,237` | reset; historical observations lack declaration custody | a new declared schema name, with old error occurrences left without it | **new key** (accretion) plus a **retraction** of stale occurrences, which are disposable data (AGENTS.md "Turns, evaluations, messages, errors … are data: disposable"), or simply leaving them in history | no |
| `lane-indexer-facts-2026-09-22.md:268` | reset; old unchanged rows lack the new analyzer fact | re-analysis of every unchanged row under the new analyzer | **accretion** plus a **complete publication in place**. `classify-paths` already returns `:all` for a `.clj-kondo` change (`source.clj:149-152`); an analyzer-version input would do the same. The sibling lane measured whole-program publication on an open store at seconds, not a re-index from zero | no |
| `lane-store-growth-2026-09-22.md:10,246` | reset to reclaim 248 GiB and load the fixed wake contract | disk reclamation plus loading new code | **GC sweep** (`registry/collect!`, landed `1cc00a4a5`) plus **reload/restart** | no |
| `lane-three-way-comparison-2026-09-22.md:168` | "default predates `8a069b5e4`" | new code | **publication/reload** (hook or restart) | no |
| monitor notes 2026-09-23, RESET batch 2 (agent branch attr, partition, host-bound, no-history, budget schema, lock schema removal) | reset of `default` | four new attributes/schemas; one noHistory; one schema-family removal | **accretion** for the new attributes and noHistory. **Backfill** for host-bound (a computed fact, via an in-place publication). **Retraction path** for `:seon.operator.lock/*` (1.3e refusal, then retract; the sibling measured 7,119 ms for a writerless retirement) | no |
| `lane-publication-envelope-2026-09-22.md:38,344` (`::reset-needed` rule) | the code itself refuses with "RESET NEEDED" when `deps.edn` or a gitlink changes | new dependency classes loaded in the JVM | **JVM restart on the same store** (warm start 6,248 ms, wrappers note line 100) | no, restart only |
| scratch from-zero boots used as proof: `lane-a2-db-deletions` (162,616 ms), `lane-schema-retirement-refusal` (101,378 ms), `lane-three-way-comparison` (199,190 ms), `lane-indexer-facts` (164,942 ms), `lane-reload-per-declaration` (146,520 ms), `lane-wrappers-changed-identities` (121,767 / 180,027 ms), `lane-publication-envelope` (168,900 ms, failed), `lane-b1b` drills 5–8 (158,108 / 166,166 / 58,111 / 153,453 ms), `lane-fault-no-history`, `lane-root-seed-digest` (×3), `lane-realities-commit-1/2`, `lane-error-schemas`, `lane-hook-one-request`, `lane-search-deletion` (×3), `lane-boot-process-attribute`, `fresh-start` (308,518 / 366,653 ms) | a fresh store as "proof that HEAD loads / the schema change adopts" | a load of HEAD plus the declaration transaction | the **incremental proof on a branch of a live store** (ruled 2026-09-23). The b1b drills are the exception: they test reset itself, so reset is their subject | **KEEP only the b1b drills** (reset is the subject), plus the orchestrator's cut-boundary platform proof. DELETE the rest |

Two Seon refusals are stricter than Datahike, and these are the only schema edits
where a reset is truly forced today. Both are fixable in
`accretive-property-change?` (row 1):

- adding `:db/unique` to an attribute with cardinality one and no uniqueness
  (Datahike accepts it, `schema.cljc:271-274`);
- dropping `:db/noHistory`, which Datahike accepts as an update to `false`.

The genuinely non-accretive cases are a `:db/valueType` change, removing an index
and removing uniqueness. Datahike refuses those. The data-guide rule applies there
("different semantics needs a new key"), and a new key is accretion.

## TIMINGS (this lane's probes, on `default` PID 51528, JVM mode unless noted)

| # | form (abbreviated) | result |
|---|---|---|
| T1 | `seon.db/carried-projection` ×2; `seon.schema/projection-from-database` ×2 on `(seon.db/db conn)` | 0.043 / 0.030 ms; 196.7 / 192.7 ms; 3,285 forms; value carries a projection stamp |
| T2 | same after a new commit, with thread-allocated bytes | carried 0.008 ms / 16 KB; pfd 136.7 ms / 363 MB |
| T3 | `sci.core/fork ctx` ×2; `fork-cluster-ctx ctx db conn` ×2; with the ctx's projection-state ×2; `projection-state` | 0.063 / 0.041 ms; 170.8 / 161.2 ms; 10.6 / 8.9 ms; 0.075 ms; 4,436 installed functions |
| T4 | history query for the last transactions | 536871169–172 each `:seon.dev.mcp.artifact/{id,digest}` plus txInstant (windowed MCP results) |
| T5 | MCP SCI `(+ 1 1)` after commits, then `(+ 1 2)` | 1,242 ms / 3.58 GB, then 9 ms / 11 MB |
| T6 | MCP SCI `(+ 1 3)` after commits; `(+ 1 6)` with no commit | 1,129 ms / 3.54 GB; 7 ms / 11 MB |
| T7 | windowed JVM `(vec (range 200))` (1 tx), then MCP SCI `(+ 1 7)` | 372 ms / 967 MB |
| T8 | JVM `seon.sci.eval/evaluate` on the cluster ctx after 1 tx, 5 ms thread sampling | 1,108–1,130 ms / 3.54–3.57 GB; 157 samples: 106 `seon.fn/reverse-closure` → `declared-reference-edges` → `seon.db/q`; 25+6 `lookup-ref-error`/`attribute-observation`; 2 `function-digests`; 2 `projection-from-rows` |
| T9 | overridden set loaded-vs-branch; `reverse-closure` stamped (first on commit) vs later | loaded digests 5.9 ms, branch 2.5 ms, 4,436 rows, 1 overridden (`my.agents.root/largest`); closure 778 ms first on a commit, 20–24 ms after |
| T10 | `curl` GET `/` ×3, `/agent/root` ×2, `/ns/my.agents.root` ×2, `/agent/root/debug` ×1 | 1,074 / 344 / 360 ms; 359 / 330 ms; 335 / 328 ms (39,474 B); **44,252 ms** (194,499 B) |
| T11 | `#'seon.render.web/current-page` direct, 3 ms sampling | 899 ms; 193 samples: 130 `read-evidence-current?`, 72 `index-pattern-change`, 49 `replay-read`, 127 `page-result`, 93 `render.walk/neighborhood` (inclusive) |
| T12 | `test.cache/gitlink-digests` ×2; `test.cache/input-digests` ×2; `source/discover-paths` | 21.8 / 18.0 ms (20 gitlinks); 203.5 / 179.9 ms (3,415 files); 60.3 ms (714 paths) |
| T13 | read + transit-parse every `.clj-kondo/.cache/v1/*/*.transit.json` | 2,352 files, 312.6 ms (14 MB) |
| T14 | `du -sk data/store` and file count, before/after one windowed transaction | 4,512,328 → 4,513,312 KB (+984 KB), 3,651 → 3,654 files |
| T15 | `time bin/seon status` | 74 ms wall |
| T16 | `seon.config/effective db "default"` ×3 | 2.09 / 1.94 / 2.04 ms |
| T17 | `datahike.api/q` ×2 vs `seon.db/q` ×2, `[:find ?s ?t :where [?f :seon.fn/sym ?s] [?f :seon.fn/calls ?t]]` | 62.8 / 0.037 ms vs 16.8 / 17.1 ms (33,092 rows) |

## Limits

- Rows 4 and 6 cite the reload lane's clocks. I did not re-run the publication
  script: it needs a scratch JVM at about 100–200 s, over the rule.
- The per-form re-acquisition inside agent turns (row 2) is inferred from
  `receipt-start-call` preceding each evaluation. The MCP and direct `evaluate`
  paths were measured. An agent turn was not driven, because it would be a paid run.
- The 44 s debug page was observed once and not profiled.
- Store growth is attributed per transaction for one artifact commit only. The
  4.3 GB total is not attributed across branches.
- `default` runs `394b58f09`. Rows 2, 5 and 7 are measured there. The HEAD
  mechanisms were verified to be the same by reading source (the `acquired-database?`
  commit-id test and the `acquire!` rebuild are unchanged), except for the
  uncommitted projection memo.
