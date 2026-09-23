---
type: landing
status: landed
lane: wrapper-profiling (C1 minimal slice + boot arming P0)
created: 2026-09-23
spec: docs/prds/agent-platform/plan/lane-c1-wrapper-profiling.md
---

# Lane wrapper-profiling — always-on timing in the armed wrapper, boot arms the program

## What landed

- `src/seon/profile.clj` (new): one cell per installed callable (JDK
  `LongAdder` calls/total/throws, `LongAccumulator` max), keyed by symbol,
  `:seon.program/definition-digest` when the installer holds one, and scope
  (`:seon.profile/host` = the JVM Var wrapper, attributed to no cluster;
  `:seon.profile/context` = one SCI installation). `with-cell` binds the
  accumulators as hinted closure locals at install; `timed` brackets the call
  with two `System/nanoTime` reads and records on return and on throw
  (rethrowing the original object). No map, hash, database read or armed call
  on the call path. Cells are cumulative, never reset; a weak live set
  (reachable only through wrapper metadata) lets `cells`, `top`, `summary`,
  `begin`/`explain`/`explain-slow` read them.
- `src/seon/instrument.clj`: the host wrapper (`arm-var!`) and the SCI wrapper
  (`wrap-interpreted`) are timed; a same-digest re-arm keeps its cell
  (`profile/reusable?`); `apply!` accepts `:seon.profile/definition-digests`.
  Deleted `:seon.instrument/contract-digest` (metadata no production reader
  used; it cost ~4.2 s of every full arm — found by the profiler itself, below).
- `src/seon/cluster/boot.clj` + `src/seon/cluster.clj` (P0): boot arms the
  running program once (`cluster/arm-host-program!`): requires the published
  program's `src/` namespaces (`cluster/published-program`), compiles against
  the files' own declarations (`schema.edn/packaged-forms`), digests from the
  published source program. Adoption still re-arms only changed identities
  (e0577a6fb's narrowing stays) and now passes their digests.
- Surfacing, no dial: MCP `eval_clj` (jvm and sci) results over 1 s carry
  `seon.dev.mcp/profile` (`project-next-prepl-value!` opens the window,
  `mcp-valf` attaches); `runtime_status` and `bin/seon status` carry
  `seon.dev.mcp/profile` = `profile/summary` (top 5 by total, top 5 by max,
  over-1 s lines and count); `refresh-source!` (hook publication and
  adoption) reports the explanation as a progress line and in its result;
  `seon.sci.eval/evaluate` appends the lines to `:seon.cluster.eval/output`
  (the text an agent is shown) and carries `:seon.profile/explanation`.
- `resources/seon/schemas/seon.profile.edn` (new, values only, no stored
  attribute); `seon.instrument.edn` declares the new optional request member.
- `mcp-projection-error`: the 1-arity passed nil into its own Throwable-only
  contract, so the swallowing fallback in `mcp-valf` threw; the catch now
  passes the failure and the nil arity is deleted.

## Where boot stopped arming

`e0577a6fb` (2026-09-22 13:53, "Arm only changed function identities during
adoption"). Before it, dev adoption at boot called `instrument/apply!`
without `:seon.instrument/changed-identities` (`git show
dcc15e5b3^:src/seon/cluster.clj:2117-2125`), arming the loaded program; after
it, only changed identities arm, so a resume with no changes arms nothing, and
ordinary clusters never arm. Its own landing note records "Boot itself had
zero host wrappers" (`lane-wrappers-changed-identities-2026-09-22.md:146`).
Observed: default pid 70720 `(count (seon.instrument/instrumented))` = 0
(read-only); scratch boots of `090f58247` and `75788a8d8` = 0.

## Proof (scratch roots only; default untouched)

Snapshots: `git archive` of `75788a8d8` in `tmp/wrapper-profiling/src-75788a8d8`
with my files overlaid, `reference-code`, `target`, `.cpcache` linked,
`dependency-pins.txt` recorded. Its `src/seon/db.clj:1315` was patched in the
snapshot only (`[:fn #(instance? ValueKey %)]` -> `:seon.schema/value`)
because HEAD refuses a first publication there (reported to the orchestrator).

- (a) Boot arms the running program: resume boot, `{:armed 1800 :armable 1800
  :missing {} :boot-applied {:registered 1800 :instrumented 1800}}`, 1,799
  cells with a digest; `seon.id/valid?` cell digest `c29f0e66…` equals its row.
  The first attempt armed at the stand point before `serve!` and covered 1,651
  of 1,799 (lazily required `my.*`, `seon.render.ns`, …); requiring the
  published `src/` namespaces fixed it.
- (b) Hook path: `refresh-source!` of `my/note.clj` (docstring) with
  development cluster `wp`: 9,608 ms, `reloaded [my.note]`, 3 arming
  identities, armed 1,800 before and after = armable; `my.note/notes` got a
  new cell whose digest `7631e300…` equals the new row's (was `7229f5de…`).
  Test-file adoption: armed 1,809 = armable 1,809.
- Schema resource incrementally: the same hook adoption carried a
  `:seon.profile/calls` description change; the `wp` branch row reads
  `[:int {:min 0, :description "Completed calls: returns and throws."}]`. The
  change is additive (no retirement), so no 1.3e refusal applies.
- (c) SCI: `seon.profile-test/an-sci-installation-owns-its-own-cell` passes
  (two installs of one row: separate cells, counts [2 1], digest carried). A
  live agent `defn` could not be shown: MCP `sci` mode writes no program row
  (so installs no contract), and the scratch has no provider key for a turn.
- Slow call explained where agents look (MCP output, verbatim first lines):
  `"7320 ms elapsed; armed definitions by inclusive time (callers include
  callees; concurrent work included):", "8038 ms in
  seon.schema/canonical-value-string x794660", "1835 ms in
  seon.schema/canonical-data-string x1018", "1126 ms in
  seon.instrument/contract-definitions x989"` — this is how the dead
  contract-digest was found and deleted (cold arm 6,808 -> 2,560 ms).
- Throw: `a-host-call-is-counted-on-return-and-on-throw-without-changing-either`
  (3 calls, 1 throw, `identical?` original exception). Over-1 s list:
  `reads-rank-by-total-and-maximum-and-list-maxima-over-one-second` (a 1.5 s
  sample lists `probe/slow total 1500 ms, max 1500 ms, x1`).
- Tests (`bin/test-check` on the scratch): `seon.profile-test` run
  `da6660b21e02` 5 tests / 27 pass / 13,289 ms. Touched `seon.instrument-test`
  members run `c72f3912c627` 5 / 22 pass. Whole ns run `4419d4fd2674`: 10 red;
  the same 10 are red on the PARENT with the program armed (runs
  `25022655efb5`, `68fa8e29cc71`), so they predate this slice and appear once
  the program is armed (fixture rows missing `:seon.program/definition-digest`
  at `test_support.clj:746`; "A different SCI context is already armed";
  refusal shape). `cold-arming-covers-the-complete-armable-population` is red
  on the parent (11,894 ms over its 5 s bound) and green here.

## Hot path (same probe, parent vs this change)

- Isolated increment, same JVM, 8 alternating rounds x 2,000,000 calls of
  `seon.id/valid?`'s original under a bare closure vs the `with-cell`+`timed`
  closure (`tmp/wrapper-profiling/ab2.clj`): min 476.8 vs 499.5 ns (+22.8 ns),
  median delta +33.5 ns. Target ≤ ~40 ns: met.
- Whole armed call, same JVM, parent `arm-var!` vs profiled `arm-var!` loaded
  alternately (`ab.clj`, 4 rounds x 1M): armed − base medians 587 vs 758 ns;
  the host ran 5+ other JVMs, rounds vary 450–1,200 ns, so this does not
  resolve a 20–40 ns difference; the isolated probe above is the measurement.
- `profile/begin` (every MCP eval, SCI evaluation, publication): 1.08 ms warm
  over 1,653 cells, 1.06 ms over 3,600 synthetic cells (~0.3 µs/cell). Under
  the 50 ms rule; proportional to installed callables.
- Full arm: parent 8,089 ms (1,784 Vars) -> this change 2,560 ms (1,800).

## TIMINGS (every operation over 1 s)

| Operation | Wall ms | Justification / status |
|---|---:|---|
| Scratch start, fresh root, `090f58247` | 86,700 (ready 70,175) | from-zero publication of the whole program; DEFECT >10 s (existing from-zero-boot note); dependency classes `:miss :pins-unavailable` |
| Scratch start, fresh root, `75788a8d8`+C1 | 173,700 (ready 135,004) | same; DEFECT; classes `:miss :no-matching-cache` (digest e2ab6c57… never matches the shared cache — cache defect) |
| Parent start, fresh root | 185,000 (ready 152,652) | same; DEFECT |
| Resume starts | 44,181–98,460 (ready 12,399–61,598) | publish changed files + boot; DEFECT >10 s; ready 12.4 s with nothing changed |
| Full host arm, cold | 2,560 | 1,800 contracts compiled once per JVM; `contract-definitions` 820 ms remains (per-Var ref walk, memoizable per projection) |
| Full host arm, parent | 8,089 | includes the deleted contract-digest |
| No-op re-arm | 357–387 | `current-wrapper?` over 1,800 + declaration projection 242 ms |
| Hook adoption `my/note.clj` | 9,608 | `seon.db/with-declarations` x389 9,100 ms, `seon.fn/report-identities` 5,435 ms (publication-work lane) |
| Hook adoption test file | 10,540 | DEFECT >10 s: `with-declarations` x270 7,524 ms |
| Hook adoption instrument+cluster+… | 35,134 | DEFECT: `with-declarations` x15,944 18,891 ms, `analyzed-artifacts` 15,182 ms |
| First SCI eval after adoption | 46,263 (MCP timed out at 30 s) | DEFECT: `seon.sci.eval/acquire!` 44,810 ms, `record-acquisition-refusals!` 35,982 ms (transact! 35,179) |
| `init --dev wp` | 11,440 | `development-source-refresh!` 9,860 ms; DEFECT >10 s |
| `seon.profile-test` run | 13,289 | per-run selection-admission 3.4 s + acquisition 3.1 s; 5 members |
| `seon.instrument-test` whole ns | 112,849 | 46 members incl. two hot measurement tests; parent 123,067 |
| Bench probes | 4,777–24,508 | deliberate million-call loops |

The over-10 s rows are recorded here for the orchestrator to fold into the
shared from-zero/publication notes.

## Not done, handed off, limits

- `src/seon/test.clj` member explanation: hunk sent to the orchestrator for
  realities (apply after this commit). `turn.clj` untouched and released.
- `docs/seon/issues/adopting-the-instrumentation-owner-leaves-unreloaded-namespaces-unarmed.md`
  (new): self-adopting instrument.clj/cluster.clj left 537 wrappers in 22
  unreloaded namespaces unarmed; cause unknown.
- Not done: a live Seon-agent SCI `defn` armed and counted (no provider key;
  MCP sci writes no rows); SCI cells are shared by `sci/fork` children (the
  fork copies the wrapped root); host cells on a JVM with two clusters take the
  last-booted cluster's arming policy (existing JVM-global wrapper semantics).
- Inclusive times double-count self-delegating arities and recursion
  (`refresh-source!` x2 in one call); the explanation says so.
- Next step (not built): a sampling profiler to show where a running operation
  is. JFR is in the JDK (`jcmd <pid> JFR.start duration=30s
  filename=tmp/…jfr`), ~1–2% overhead at the default profile, no new
  dependency; async-profiler adds native-frame accuracy at the cost of a
  native agent. Recommend JFR on demand, attached to the explanation when an
  operation exceeds 10 s.
- `seon.cluster/mcp-valf`'s fallback still reduces the failure to its message
  (`:seon.dev.mcp/projection-failure-message`); the full cause chain is the
  mcp-and-stop patch's scope.

RESET NEEDED: no.

## Follow-ups (2026-09-23, after ed62a3e06)

| Commit | Change | Proof |
|---|---|---|
| `0913df3c7` | P0 0j: host wrapper validates with a supplied projection only when it carries the Var's armed contract and declares every schema the contract closes over; else the armed (files') declarations | Existing-store repro `tmp/wrapper-profiling/root-move` (bfcce39ad from zero, then resumed at HEAD): at ed62a3e06 the resume logged `:malli.core/invalid-schema` faults (01:14–01:15Z, `seon.schema/projection-cache-value`, armed `seon.profile/cell` compiled against the cluster projection, which lacks `:seon.profile/identity`) and `init --dev` refused with `seon.db/write-render-target-error refused argument count … got 2` (stored 1-arity contract vs loaded 2-arity). With the fix: no new invalid-schema occurrence, `init --dev` completes (37,960 ms), armed 1,824 = armable. Regression `a-handed-projection-without-the-contracts-declarations-uses-the-loaded-ones`. Handed-path armed call median 3.77 µs parent vs 3.94 µs (noise band) |
| `56c0941af` | MCP blob-only results (owner's patch) | read_only 5,000-element probe: commit-id before = after; `mcp-get-value` pages it after a restart; unknown digest → typed `value-not-found` |
| `ac7b61eb4` | `seon.turn/phase` rethrows InterruptedException | REPL: rethrown `identical?`; ex-info → phase-failed value |
| `225d0059c` | 24v: `runtime_status`/`bin/seon status` carry `:seon.dev.mcp/replaced-roots` | 4,481 defn rows, `[]` at rest, 55 ms; with-redefs of `seon.id/valid?` named with class `user$eval…`; regression `a-root-replaced-outside-a-reload-is-named` |
| `cdbc1434b` | summary lines say "inclusive" | `reads-rank-…` expects the labelled line |

Tests on scratch `root` after adoption + restart: run `ebc9ad6898f3` (7 instrument members incl. both new regressions) 32 pass; run `9eb030481076` profile ns 27 pass.

### P0 0j classes, as observed

1. `Cannot interpret my.agents.root/largest: :malli.core/invalid-schema` — caused by
   ed62a3e06 (armed host `seon.profile/*` inside `wrap-interpreted`, compiled
   against the retained cluster projection); fixed in `0913df3c7`.
2. `The loaded JVM has no core definition seon.*-test/...` and `Cannot interpret
   seon.cluster.reload-measure/-main: Host-bound declaration …` — produced by SCI
   acquisition (`src/seon/sci/eval.clj:2113-2122`, `:994-1006`) and made fatal by
   `src/seon/cluster/agent.clj:885-888` (`arm!` throws on any acquisition refusal);
   ed62a3e06 changes neither. Reproduced on scratch after every development
   adoption (a restart clears it); M9 owns it. Recording those refusals is itself
   slow (`record-acquisition-refusals!` 7–36 s in the writer).

### The 10 armed-red `seon.instrument-test` members (red on the armed parent too)

- Fixture rows missing `:seon.program/definition-digest` (`test_support.clj:746`,
  `seon.fn/source-rows` refuses the namespace row): `a-sci-only-arity-miss-…`,
  `host-diagnostics-use-the-loaded-vars-arglists`,
  `sci-installed-contracts-enforce-…`, likely `a-sovereign-sci-fork-…` — retired
  assumption in the fixture (B1 made the digest required): the canonical
  `program-fn-row`/namespace-row helpers must assoc
  `(seon.program/definition-digest row)`; realities' file.
- "A different SCI context is already armed on this thread": `a-deadline-firing-…`,
  `an-invalid-refusal-retains-…`, `hot-sci-declared-schema-check-measurement` —
  wanted behaviour of a surviving seam (armed host calls inside an SCI arm);
  `seon.sci.kernel`'s owner; not investigated further.
- `an-error-shaped-argument-…`, `instrumentation-observations-…-class-stamps`,
  `the-caller-frame-…`: the refusal is not the one the test expects (another armed
  boundary refuses first, or the test Var is unarmed when its namespace loads after
  boot arming). Unclassified: needs a per-test trace. Not fixed.

### Follow-up timings over 1 s

| Operation | Wall ms | Note |
|---|---:|---|
| bfcce39ad from-zero scratch start | 160,460 (ready 120,326) | from-zero publication; DEFECT |
| Resume at HEAD on that store | 143,950; later 51,428 / 54,130 / 99,200 | publication of changed files; DEFECT |
| `init --dev` after the fix | 37,960 | development adoption of the whole program; DEFECT |
| Hook adoption of 9 files | 14,026 | `development-source-refresh!` 13,170 ms; DEFECT |
| Test runs | 16,638–27,825 | selection-admission + acquisition per request |
| Hung test runs after adoption | 150,090 / 162,320 | acquisition refusal recording in the writer; DEFECT (M9) |

## Second follow-ups

- `7b66c924e` blob_publication_test roots via the error-occurrence blob row; adds the
  unreferenced-blob-until-sweep scenario. Not observed green: the test is red at its
  first unchanged scenario on the parent too (`:orphan-blob-batch-fixed` latch not
  seen in 20 s; 148,396 ms parent / 157,726 ms converted, dominated by a from-zero
  publication of its fixture root — DEFECT >10 s).
- New defect found (not my file): `seon.cluster.registry/collect!` on a store holding
  error-occurrence blobs refuses — `referenced-blobs` returns entity ids
  (`refused return value at [41112] … got an integer 41112`, `registry.clj:466`):
  `blob-digest-attributes` counts the ref attribute `:seon.error.occurrence/data-blob`
  as a digest. So no explicit sweep runs on such a store; the MCP blob-only ruling's
  "swept digest refuses as collected" is unreachable until the registry owner fixes it.
- `seon.dev.mcp.artifact.edn` NOT retired: `test/seon/sci/branch_execution_test.clj:156`
  (M9) still writes it.
- `59d8cc6a6` #63: comment-only adoption 5,043 ms, 0 reloaded; unchanged adoption
  1,634 ms (publication of zero files; `full-source-refresh!` digest walk).
- 24z (page-cache signal from post-eval evidence) not started.

## Third follow-ups

- `967bcb86e` registry: only digest-valued attributes are blob references (515
  ref/symbol attributes were read as digests through their properties maps); explicit
  sweep now runs, collects an unreferenced blob, `get_value` answers value-not-found.
  `blob_publication_test` 20/20 (112,312 ms; its fixture root's from-zero
  publication is most of it — DEFECT >10 s). Its last assertion ("a second collection
  sweeps zero") was a retired assumption per `collect!`'s docstring; it now asserts the
  rooted blob survives. Regression `only-digest-valued-attributes-are-swept-as-blob-references`
  (run `c7519f78574d`). #63 regression `an-adoption-that-changes-no-definition-is-not-an-arming-failure`
  (run `8b19e0e5fada`). New issue: `an-explicit-collection-sweeps-the-clusters-adopted-source-commit.md`
  (an explicit now-cutoff sweep made the scratch root unbootable).
- #63 phases, comment-only adoption 6,280 ms: store 210; source build 3,492 (kondo
  analysis of the changed file plus digesting every input); branch publication 1,756
  (contract projection over 3,394 schemas 334, program rows, reconciliation
  transaction 933); development reconciliation 467; arming + record 118. Unchanged
  adoption 1,292 ms: 1,105 ms is the source build's input digest walk (every input
  re-read and hashed) before the commit-id comparison converges. Sub-second needs the
  capture keyed by (path, size, mtime) → digest in `seon.cluster.source` (not my file).
- `81e620d4b` 24z: page-cache signal from post-evaluation evidence (`Var/rev` +
  namespace mappings identity), mark 236 µs, only for non-read_only evaluations.

## P0 stability and directive (fourth follow-ups)

- `ea048d617` resume reads a published commit whose contracts name a deleted predicate:
  `schema/projection-from-rows` gives such stored predicates (namespace loaded, Var
  gone) a callable that refuses by name; boot's digest comparison reads raw and
  includes deleted files. +34 ms per projection build (3,394 schemas, 1,939
  contracts). Regression `a-stored-declaration-naming-a-deleted-predicate-stays-readable`
  (run `9e17e91fffb3`). Scratch `root-p0` (booted at `9f39ae83d`, resumed from a HEAD
  archive): the predicate refusal is gone; readiness then refused on HEAD's own data —
  `test/seon/cluster/publication_declared_schema_test.clj:20` quotes the deleted
  `seon.test.runner/run!`, and two issue notes cite deleted files — reported to the
  orchestrator. Archives inside the repo enumerate inputs through the parent checkout's
  index (`test/cache.clj:35-37`) unless they carry `test-input-paths.txt`.
- `27c08129d` the >1 s explanation opens with the owner's directive; verbatim SCI output:
  `;; OVER ONE SECOND (3204 ms): this is your defect; fix it before continuing. ...`
- `480807ce9` `call-preparation/hook` per-call reads through definitions: 30,000 SCI calls
  of `seon.id/valid?` 3,182 ms → 943 ms warm (31 µs per call). Remaining per call:
  `seon.db/carry-projection-state` inside `db/db` (~13 µs, db.clj) and SCI interpretation.

| Operation | ms | Note |
|---|---:|---|
| Scratch from zero at 9f39ae83d | 112,520 (ready 82,714) | from-zero publication; DEFECT |
| Resume at HEAD archive (refused runs) | 34,382–102,440 | publication of the changed files; DEFECT |
| Hook adoption of schema.clj / call_preparation.clj | 31,872 / 38,827 | reloads 90 / 56 namespaces; DEFECT |
| Test run after adoption (hung, then refused) | 154,630 | acquisition-refusal recording (M9); DEFECT |
| seon.profile-test | 13,859 | 5 members |
