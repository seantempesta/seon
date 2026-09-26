# perf-boot-opus — landing 2026-09-26

**RESET NEEDED**: every change here is committed but unmeasured on a JVM. Root restarts onto HEAD and re-measures.

## Broken or not fixed here (first)

1. **Boot store→branch layer is dominated by a whole-program caller re-lint (not fixed here: `src/seon/fn.clj` is
   held).** `seon.fn/caller-files` (fn.clj:2695) re-lints the callers of every function whose contract references a
   changed schema. Patch `tmp/perf-boot-opus/fn-caller-files.patch` (+26/−46), task `ff74379b6565` in
   `tmp/perf-boot-opus/seon.fn-tasks.edn` (append to `resources/seon/tasks/seon.fn.edn`, which another lane holds
   with uncommitted edits). Cause 1 below.
2. **Adoption write is still 3.9 s** (`adopt-rows!` → one `seon.db/transact!` 3.8 s, of which
   `seon.db/validate-write-report` 1.76 s and `report-arity-comparison` 1.31 s). Existing task `d8f73961e44f`
   (db.clj, held).
3. **The profile reports scope wrappers' callback time as work** (task `5f54ae50d841`, seon.profile). The
   `call-with-projection-state` 755 s max is waiting, not work (cause 3).
4. **Boot publication phases are invisible**: boot publishes before anything is armed and `*source-progress!*` is
   bound nowhere (task `17027736987a`, seon.cluster).
5. `seon.fn/report-identities` costs 1.3 s per call (task `e09ead5c0cd6`, in the seon.fn tasks file).
6. **Fault fa60ad84 (ClassCastException in `valid-candidate-value?`)** was already fixed at its producer by
   f83931226 (`seon.turn/disposition`, 17:20Z, after the 17:13Z occurrence), with regressions
   `agent_test.clj:2250` and `turn_test.clj:2341`. Root: mark the fault resolved. The underlying class (Malli's
   `:map` validator throws on a sorted map with foreign keys, guarded twice) is task `5c8cf0549616`.
7. No regression for the operator catch (cause 4): the only way to force a non-`ExceptionInfo` out of
   `launch-child!` in a test is a Var redefinition, which the laws forbid.
8. The ledger names `src/seon/operator.clj`. The file is `script/seon/operator.clj`, and that is the file edited.

## Where 271 s / 141 s went (e405f51be move) and 82 s / 52 s (a34078273 move)

`:seon.boot/layers`, pid 59671 (e405f51be): store at 987 ms → `:seon.store/branch` at **133,625 ms** → ready at
141,124 ms. pid 70849 (a34078273): store at 1,564 ms → branch at **49,200 ms** → ready at 51,800 ms. The whole
layer is `stand-boot-layers!`'s resume publication (`refresh-source!` of the files that differ from `current-src`:
29 files incl. 7 schema resources at e405f51be, 7 files incl. 2 schema resources at a34078273). Nothing is armed
yet, so no profile row covers it. The post-ready adoption (`request! :init`) was 28.4 s at e405f51be and 8.8 s at
a34078273.

### Cause 1 — caller re-lint follows contract consumers (boot publication; proportional to the program)

Replay of the a34078273 publication on a scratch branch forked from its parent commit
(`tmp/perf-boot-opus/replay.clj`, progress bound to a clock, scratch retired). Warm, same JVM:

| phase | ms (cumulative) |
|---|---|
| capture 7 files | 57 |
| `seon.fn/analyze-rows` (515 rows) | 996 |
| schema population | 1,039 |
| contract rows 430 | 1,259 |
| reconciliation transaction | 1,306 → 4,554 |
| **"analysis callers: 238 files"** → clj-kondo over them → findings tx | 4,554 → **15,205** |

`tmp/perf-boot-opus/callers.clj` splits the selection on the published commits: 4 changed schema keys
(`:seon.db/transaction-report`, `:seon.turn.loop/turn-report`, `:seon.db/durable`, `:seon.turn.loop/applied`),
closure 6, **57 contract consumers → 241 caller files**. The signature attributes alone select 6 symbols, whose
callers are 3 files, and all 3 are the changed files themselves, which reconcile already. Publication lints with
`:type-mismatch` and every type-derived linter off (`seon.fn.analyzer/publication-config`, analyzer.clj:44-55), so a
contract or schema change cannot change a caller's publication findings. The model error is a selection keyed on
contracts that the lint never reads. The patched selection
answered the same 3 files in 11 ms against 1,395 ms for the old selection (unarmed scratch copy, `:subset? true`).
At boot, cold, this pass is most of the 47.6 s (a34078273) and of the 132 s (e405f51be, where `sci/eval.clj`,
`error.clj` and 7 schema resources changed).
**Root must:** apply `tmp/perf-boot-opus/fn-caller-files.patch` through the seon.fn owner. In
`test/seon/fn/publication_cache_test.clj` the spec-only change then expects `#{}` for `caller-files` (it expected
`#{"src/beta.clj"}`). Run `bin/test-check default --test seon.fn.caller-lint-test/only-a-signature-change-selects-caller-files`
and `--ns seon.fn.publication-cache-test`.

### Cause 2 — post-boot adoption reloads and re-arms what the JVM just loaded (fixed)

`development-source-refresh!` (pid 70849): 8,486 ms = `adopt-rows!` 3,866 + `call-with-program-change` 3,859
(`load-development-definitions!` 2,915 + re-arming) + ~760 of reads. At e405f51be the same pair was 10.6 s and 11.6 s.
The JVM launched on the archive and published those bytes. `arm-host-program!` armed it against the same commit,
so it already runs the published commit, and reload/arming belong to the JVM, not the cluster.
Fix (commit below): `seon.cluster/loaded-commit` (pure) and `name-loaded-program!`, called by boot after
`arm-host-program!` (boot.clj). These name the published commit in `sci.eval/loaded-identity`, but only when every
published file the JVM loaded has the published bytes (launch digests win, later loads hashed then). When
the loaded identity already names the published commit, `development-source-refresh!` writes the rows and the record
only. The record transaction was lifted into one local `record!` shared by both paths. This also stops a second
cluster in the same JVM from reloading a commit the JVM already loaded.
REPL proof on pid 70849 (scratch copies, unarmed; inputs/outputs validated against `:seon.source/relative-file-digests`
and `:seon.source/commit-id`): 111 loaded first-party files (75 at launch). Current commit → `{:commit-id 6ab82e40…}`.
Parent commit → nil. One digest altered → nil. Check 9 ms. Hashing all 111 files took 570 ms, so only
post-launch loads are hashed (36 files, ~0.2 s).
Expected after: adoption ≈ `adopt-rows!` + record ≈ 4.5 s at a34078273 scale (from 8.5 s), ≈ 13 s at e405f51be
scale (from 26.4 s). **Unmeasured: RESET NEEDED.**
Regression: `seon.cluster.program-change-test/a-jvm-running-the-published-bytes-names-that-commit`.

### Why three `refresh-source!` calls and 51 `call-with-projection` calls

The move itself calls `refresh-source!` once, from the operator's `:init` adoption (`request!` ×1, 28.4 s). The
other two are `bin/test-check --gate` runs (`bin/test-check:68`). pid 70849 shows the same shape: 3 calls, 26.1 s,
max 8.7 s = the adoption, plus 2 gate runs holding `seon.test/run`. The boot publication happens before arming and
is not counted. `call-with-projection` is a binding wrapper that every scoped operation passes through (render, db,
fn, publication), so its count is the number of such operations and its time is its callbacks' (cause 3). The
counts are not a defect. The ×0-operations premise holds for the cluster reconcile: the cost was reload, the write
and the boot re-lint.

### Cause 3 — `call-with-projection-state` 5,223,273 ms total, 754,816 ms max, ×269: waiting, not work

`seon.flow/graph-executor` (flow.clj:79-98) wraps each Flow proc virtual thread's whole `.run` in
`schema/call-with-projection-state`. A proc loop blocks on its channels for the life of the graph, so the cell's
maximum is a proc thread's lifetime (755 s) and ×269 counts the proc threads started. The profile documents its
times as inclusive of waits (profile.clj:16-17). The defect is the ranking: scope wrappers lead every by-total and
over-second list. Task `5f54ae50d841` (seon.profile). No change to `schema.clj`: the wrappers are correct binding
owners.

### Cause 4 — a failed move could leave default down (fixed)

`move-to-head!` (script/seon/operator.clj) caught only `ExceptionInfo` around the replacement launch, the head
restore and the resume. `launch-child!` also throws `ExecutionException` (its `CompletableFuture` reads) and
`SocketTimeoutException` (the coordinates `readLine`). Those skipped restore, terminate and resume, and left no JVM.
All three now catch `Exception` and pass the whole cause to `diagnostic`, so every launch failure takes the one
recovery path.

## Commits, sizes

- `fa6f8e8b0` — src/seon/cluster.clj +90/−31, src/seon/cluster/boot.clj +5, script/seon/operator.clj +3/−3,
  test/seon/cluster/program_change_test.clj +19; tasks appended to seon.profile/seon.cluster/seon.schema EDN; issue
  sightings in `a-three-file-changed-path-publication-takes-a-minute.md` and
  `move-to-head-takes-a-minute-or-more-not-seconds.md`. The source is net +64 lines. It deletes a reload, a re-arm
  and a JVM-wide unknown-identity window from every post-boot adoption, but no code. The fn.clj patch is net −20.
- Pre-commit static check: `seon.contracts-compile-test/check` over the a34078273 archive plus these three files → `[]`.
  No schema resource changed.

## Root runs

1. `bin/seon start --head`, then read `:seon.boot/layers` and the profile: `development-source-refresh!` should show
   no `call-with-program-change`/`load-development-definitions!` growth.
2. `bin/test-check default --test seon.cluster.program-change-test/a-jvm-running-the-published-bytes-names-that-commit`
   and `--ns seon.cluster.program-change-test`, `--ns seon.cluster.publication-adoption-test`, `--ns seon.cluster.publication-delta-test`.
3. Apply the fn.clj patch through its owner (cause 1), then re-measure a move that changes a schema resource.
4. Mark fault `fa60ad84…` resolved (f83931226).

## Resume 2026-09-26 ~21:35Z — c029d48cb move: total 128 s, ready 89.5 s (8e4ac24c9: 42 s / 28.5 s)

Stopped for root: the cause is clear, but the fix changes a root-ruled rule.

- **Layers (pid 86089):** store 1,205 ms → branch **83,483 ms** → ready 89,479 ms. Adoption `request!` 16.8 s:
  `development-source-refresh!` 16.2 s = `adopt-rows!` 13.1 s (one transact 12.7 s; `validate-write-report` max
  6.7 s, `report-arity-comparison` 2.8 s) + `published-changes` 2.5 s. There is no `call-with-program-change` row:
  fa6f8e8b0 works (`loaded-identity` = the published commit 6ab83943).
- **Cause:** the boot publication was `:all`. `:seon.source/analyzer-digest` changed (fc3a14dd… → 210d648c…)
  because **147 of the 430 declarations in analyzer reach** changed digest: the new seon.cache fns, db/schema/edn
  edits, and all 20 seon.program declarations. The last came only from program.cljc's require change, since the
  definition digest hashes the namespace resolver context. So boot re-analyzed and republished all 818 inputs
  (24,419 datoms), and the adoption rewrote them. This is the "~27 s warm per analyzer-reach commit" the move-to-head
  issue records as a root ruling; 82 s is its cold cost with 58 changed inputs. Nothing else regressed: the caller
  re-lint patch (d10d79061) removed that pass.
- **Tasks (patch for held files):** `tmp/perf-boot-opus/tasks-2.edn`, which holds `70007bd30a2e` (analyzer reach),
  `434fed5a3a67` (resolver context in digests) and their subject marks.
- **Options for root** (simplest first):
  1. Keep the ruling and accept ~80 s cold for any commit touching db/schema/fn/cache/program. Zero cost; the
     10-s law is broken on most platform commits.
  2. **(Recommended)** Make the analyzer identity what the rows derive from: `src/seon/fn/analyzer.clj` +
     `seon.fn`'s row-derivation fns + the kondo config/version, cut at the database/schema/cache seams, which only
     read prior rows for reuse. ~30 lines in `analyzer-reach`/`analyzer-declarations` (cluster.clj, cache-central
     holds it) plus a regression that a db.clj body edit is `:selected`. Risk: a row-shaping change hidden behind
     a cut seam goes unpublished until the next change to that file. The ordinary per-file reconcile limits that.
  3. Also fix `434fed5a3a67`: hash only the aliases a definition resolves. This is a program.cljc change of
     ~20 lines that also narrows adoption and test selection.

## Resume 2 (owner ruling: keep full reach, fix re-analysis cost) — measured, stopped for a ruling

The ruling was fix it if clear; the cause is spread over many functions, so I stopped. I took cluster.clj from
cache-central as instructed but edited nothing this round.

**Replay of the bb07fdf08 `:all` publication** (all 819 inputs, scratch branch, `tmp/perf-boot-opus/replay2.clj`), warm,
under root's concurrent test load: **43.6 s**. `analyze-rows` took 34.8 s. The rest of `populate-source!`: contract
projection 2.3 s, contract rows 0.5 s, reconciliation transaction ~5 s. The transaction wrote 974 changed identities
out of 9,518 rows, so the reconcile already writes only differing rows. Boot pays this cold: 81.6 s store→branch.

**Stack sampling of one `analyze-rows`** (`tmp/perf-boot-opus/sample.clj`, 50 ms samples; quieter window: 21.0 s,
386 samples):

| where (inclusive samples) | share | what |
|---|---|---|
| clj-kondo `run!` (sequential) | 118 (31%) | ~6 s |
| `analyzer/analyze` after kondo | 85 (22%) | parsed-facts (a second parse of every file) 26, attributed-usages 26, of which O(usages × definitions per file) `contains-position?` 19, normalized-entries 25 |
| `analyzed-artifacts` outside `analyze` | 131 (34%) | analysis-rows-by-file 60, artifact 32, a third parse and sexpr of every file for `mentioned` 21, declared-function-targets 12 |
| `analyzed-files` outside artifacts | 50 (13%) | `file-rows` previous rows 27 (1.5 s), assert-capability-contracts! 17 |

Other armed costs in the window: `seon.schema/canonical-value-string` was called 1,122,608 times. It recurses through
its armed Var, so each value node pays the wrapper (1.8 s). `seon.db/with-declarations` ×14,067 took 3.8 s, and
`fs/relative-path` ×11,434 up to 2.2 s under load, because it canonicalizes on the filesystem each call.

**clj-kondo is not the lever.** I ran it on the whole program (499 .clj/.cljc) on scratch cache copies
(`tmp/perf-boot-opus/kondo-par.clj`). Sequential and `:parallel true` took 6.5 s and 6.9 s warm. The first cold run
took 33.8 s. Both modes give identical findings and identical analysis, except 3 `:meta` values whose `#()` gensyms
differ from run to run anyway. The "parallel corrupts" premise behind 9e635c7dc did not reproduce, but parallel buys
nothing warm here.

**The owner's cache hint does not help this case.** A per-file cache keyed by [file digest, analyzer identity] misses
on every file exactly when the identity changes. So `:all` cost is raw derivation speed. Our post-kondo work is about
2.5× kondo's own time.

Options (simplest first):
1. **Targeted algorithmic fixes in fn.clj/analyzer.clj**, ~60–80 changed lines, net ≈ 0, est. 21 s → ~10 s warm:
   - derive `mentioned` and the parsed facts from kondo's own analysis or one shared parse (deletes two of the
     three parses);
   - sorted-span lookup in `innermost-span` (O(log n) per usage);
   - make the recursive `canonical-value-string` call itself, not its armed Var;
   - drop the whole-population `file-rows` pull on `:all`.
2. **(Recommended) Option 1 plus per-file parallel derivation.** Kondo runs once over everything, then
   `analysis-rows-by-file`, `artifact` and the span attribution run per file on an executor (the work is already
   grouped by `:filename`). ~100 lines in fn.clj/analyzer.clj, est. ~7–9 s warm (kondo-bound), cold boot ~25–30 s.
   Needs analyzer.clj ownership, and seon.fs for `relative-path`.
3. **Keep a warm analysis across restarts.** Store per-file artifacts keyed by [file digest, analyzer identity] in
   the store, so a move whose analyzer identity is unchanged skips analysis entirely. This does not help `:all`,
   which the ruling makes the case that matters. Listed for completeness.

Nothing is committed for this resume, so nothing new needs a restart. The next step waits on root's pick and on
analyzer.clj/fs.clj ownership.

## Resume 3 — option 1 landed (46ad08a41) + audit F10; RESET NEEDED

Ownership: I took `src/seon/cluster.clj` (released by cache-central) and `fn/analyzer.clj` and `fs.clj` (row 703).
`schema.clj`: only the `canonical-value-string` change, which root allowed to stay; defn-scan-opus owns the file.

**Measurements** (live default, bb07fdf08 inputs, all 819 files, `tmp/perf-boot-opus/ab.clj`):
- Unarmed scratch copies, HEAD vs this tree, same inputs, alternating runs: **10.9 / 11.2 s → 9.2 / 9.4 s** (−16%).
  Rows are equal: 9,518 = 9,518, set-equal.
- Armed live `analyze-rows` in a quiet window: 10.9 s (kondo 3.6 s, analyzer post-processing 2.4 s, the rest ~4.8 s).
  The earlier 21–35 s numbers were machine load (concurrent test runs), not our code.
- `canonical-value-string` on the same rows: 309–370 ms armed-recursive → 181–214 ms local, output equal.

**Remaining cost at boot.** What's left of the 81.6 s boot store→branch: this ~9 s warm analysis, ~2.8 s of contract
rows, a ~5 s transaction, and JIT-cold multipliers. Option 2 (per-file parallel derivation) would cut into the ~5 s
of post-kondo derivation. That is a ~5 s warm gain, not enough to meet the 10 s law, and kondo cold dominates at boot.
My recommendation: measure the next boot before starting a second slice.

**What changed:**
- `analyzer/parsed-facts` is the one parse of each captured source. It also yields `::mentioned-symbols`, so
  `analyzed-artifacts` no longer parses and sexprs every file a third time.
- `innermost-span` binary-searches start-ordered spans instead of sorting a filter over every span, per usage. It
  keeps the same answer rule: the last start among containing spans, ties in original order.
- The previous rows are read as names only (`:seon.ns/name`, `:seon.fn/sym`), not the whole rows.
- `fs/canonical-relative-path` is pure for canonical paths under a canonical directory; `artifact` uses it.
- The canonical printer recurses through a local fn.

**Audit F10:**
- Boot reads `source/current` once. It hands that commit to `published-program` (now `[store commit-id]`) and to
  `name-loaded-program!`.
- The loaded inputs now include every packaged schema resource, which with the source files are what arming compiles
  from. Supplied defaults come from the same pre-adoption cluster database on both paths.
- REPL proof: 330 inputs in 84 ms. The current commit is named; the parent and a one-resource-changed map name nothing.
- New regression: `seon.cluster.program-change-test/the-loaded-program-inputs-include-every-arming-input`.
- Not added: Astra's multi-cluster and publication-during-arming boot regressions. They need a destructive boot
  harness, which is root's.

**Static check:** `seon.contracts-compile-test/check` over the 7 changed files → `[]`.

**Root runs after the restart** (this commit changes the analyzer identity, so expect one full republication; report
its store→branch ms):
1. `bin/test-check default --ns seon.cluster.program-change-test`
2. `bin/test-check default --test seon.schema-test/canonical-encoding-remains-byte-identical-after-dispatch-reorder`,
   then `--ns seon.schema-encoding-test`
3. `bin/test-check default --ns seon.fn.caller-lint-test`, `--ns seon.fn.publication-cache-test`,
   `--ns seon.fn.incremental-deletion-test`, `--ns seon.fn.analyzer-test`
4. `bin/test-check default --ns seon.fs-test` (if present) and `--ns seon.cluster.publication-delta-test`
5. Read `:seon.boot/layers` and confirm `development-source-refresh!` shows no reload/arming growth.

## Resume 4 — reds on pid 12212 (d1f6a37fb)

- **Boot on 15e6a31 (pid 17853):** store→branch 24.2 s, ready 27.1 s, full republication. The c029d48cb boot was
  83.5 s / 89.5 s. The loaded identity equals the published commit, and no `call-with-program-change`,
  `load-development-definitions!` or `adopt-rows!` row grew.
- **(1) program-change-test — mine, fixed.** The regression passed "x" and "other bytes" as digests, which the
  armed contract of `loaded-commit` (`:seon.source/relative-file-digests`) correctly refuses. It now passes
  64-hex digests. REPL proof on pid 17853, armed: all four assertions are true, and
  `loaded-program-digests` returns 321 inputs.
- **(2) publication-delta-test/schema-referrers… — not mine, pre-existing.** The synthetic fixture row from
  `seon.fn/source-rows` has no `:seon.fn/spec` and no arities, so nothing can be selected. Scratch copies of the code
  before and after 46ad08a41, plus the live code, all give `[#:seon.fn{:sym my.note/arming-probe-x}]` with no spec,
  while `analyzer/analyze` does report the `:malli/schema` meta. Task `3452b9f07046` (seon.fn); subject marked on
  `seon.fn/source-rows`. The 6.2 s bound excess is the run total and is separate.
- **(3) publication-cache-test ran 0.** Its only test is `:seon.test/long` and needs `--include-long`. It is not
  broken.

**Root runs after the restart:**
- `bin/test-check default --ns seon.cluster.program-change-test`
- `bin/test-check default --ns seon.fn.publication-cache-test --include-long`

## Resume 5 — supplied defaults missing after boot (fixed), task 3452b9f07046 (fixed); 23a08e4ad; RESET NEEDED

**Broken, found and fixed: armed wrappers supplied no defaults after a boot.** This was my regression. It surfaced
through fa6f8e8b0 and the root is older.
- `arm-host-program!` compiled every wrapper against `schema/declaration-projection (schema.edn/packaged-forms)`.
  Those are schema resources only, with no function contract, so `call-preparation/arm-plan` found no contract for
  any Var.
- Live default (pid 17853): **0 of 2,174** armed Vars get a plan against that projection, and **821** against the
  published commit's carried projection. The supplied-default snapshot itself was present and equal to the live one.
- Before fa6f8e8b0, the post-boot adoption re-armed everything against the carried projection and hid this. My skip
  exposed it.
- Fix: `published-program` returns the commit's projection, and boot hands it to `arm-host-program!`. That is one
  commit, one arming, no reload and no second arming. F10's single commit is now also the arming projection.
- Regression: `seon.cluster.program-change-test/a-booted-wrapper-supplies-its-declared-defaults` calls
  `my.program/breaks` with no `:seon.db/db` inside a test member.

**Task 3452b9f07046 (fixed).** clj-kondo reports no `:meta` for a `#:malli{:schema ...}` attribute map, which is
what `pr-str` prints. `parsed-facts` now reads such maps from its one parse, by position, and `analyze` fills
`:meta` only where kondo gave none. Scratch copy check: namespaced map, docstring plus namespaced map, plain map,
and no map all come out right. The subject mark is removed.

**Boot toward 10 s, ranked from measurements:**
- Full-republication boot (15e6a31): store→branch 24.2 s. Of that, warm analysis is ~9 s (kondo 3.6 s, our
  derivation ~5.5 s), contract rows ~2.8 s and the reconcile transaction ~5 s; the rest is JIT-cold multiplier.
- Unchanged-program restart (pid 69326, a34078273): store→branch 0.58 s, ready **6.3 s** after boot start. The layers
  after that are config-result +1.6 s, SCI ctx +2.1 s and flow graph +1.2 s. JVM launch and operator phases are not
  measured here.

I made no speed change in this resume; the correctness regressions came first. If the operator's unchanged-restart
total is not near-instant, the first targets are the 2.1 s ctx build and the 1.6 s config layer. I have not yet
measured them.

**Root runs after the restart** (the analyzer changed, so expect one full republication; report store→branch ms):
- `bin/test-check default --ns seon.cluster.program-change-test`
- `bin/test-check default --test seon.cluster.publication-delta-test/schema-referrers-are-selected-for-arming-without-a-namespace-reload`
- `bin/test-check default --ns seon.fn.analyzer-test`
- `bin/test-check default --ns seon.fn.caller-lint-test`
- `bin/test-check default --test seon.instrument-test/boot-arming-arms-every-loaded-contract-with-its-definition-digest`
- `bin/test-check default --ns seon.call-preparation-test`
- Re-run the flows that failed: `seon.task/create!`, `my.program/accept-merge!` and `my.program/breaks` as root
  with no branch.
