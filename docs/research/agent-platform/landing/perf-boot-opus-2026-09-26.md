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
