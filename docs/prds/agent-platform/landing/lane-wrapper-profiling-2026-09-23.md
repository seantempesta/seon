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
