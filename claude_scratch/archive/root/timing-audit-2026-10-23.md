# Timing audit: are ticks, memory and wall clock recorded for every kind of call? (2026-10-23)

Read-only audit by reading source and docs (AGENTS.md section 5 "Estimates are proofs", docs/cost.md,
clocks.md, machine.md, health.md). Nothing was built, started or tested. Paths are relative to
next-self-contained/. "Recorded" means written as a fact or a result field by the code I read, not
confirmed in a live store.

## Verdict

No. Each call kind records wall time. Ticks are recorded only inside cost proofs (their rows) and as a budget
error when a run is stopped. Memory is recorded as allocated bytes on a REPL eval, as per-process real MB on
a few agent events (a transient view, not a fact), and nowhere for a turn, an effect, a Flow step, a merge or a
build. CPU time is recorded only in cost proofs (`:ns`) and in bin/check's `NAME.time` text files. Nothing
enforces that a call kind records all three: `bin/laws measures` inventories wall-clock reads only.

What the law asks (AGENTS.md:151; cost.md): cost is work done (ticks, bytes, calls); wall and CPU are measured,
recorded, shown, never red. The only red-vs-recorded split in the code is `seon.cost/exact` (cost.clj:50-56),
and that is right. The gap is that "recorded" and "ticks" are missing for runtime calls.

## Table

Abbreviations: ticks = engine ticks (`jolt.host/ticks-of`); bytes = `seon.profile/alloc-bytes` (this thread's
allocation, churn not live heap); real-mb = `seon.lib/real-mb` (process-owned pages); wall = `seon.profile/now-ns`
differences; cpu = `seon.profile/cpu-ns`.

| call kind | ticks / work recorded where | memory recorded where | wall (and CPU) recorded where | shown? | gap |
|---|---|---|---|---|---|
| REPL eval (`:eval` op, `seon_eval`, agent eval) | Work: `:fns` = top-N contracted fns, exact calls + sampled ms (repl.clj:149-168, `profile/touched`). Ticks: only a budget (`intr/run` ticks, intr.clj:94-111; `:seon.repl/ticks-spent` on overrun); ticks used by a green form are not read | `:alloc-bytes` per form (repl.clj:160-167), stored in the eval record (repl.clj:313, record-tx). real-mb never per eval (agent.clj:88 docstring) | `:ms` wall per form (repl.clj:160-167); stored. CPU: none | Dashboard eval-meta: ms, alloc, big-alloc flag (ui.clj:487-493, 426); fns line. Model's context: NO (context.clj:135-143 keeps only form/tag/value/printed/out). Root AI context: top fns only (ai.clj:287) | G1 no ticks, G2 no CPU (a wall `:ms` that includes a model/effect wait cannot be told from work), G3 model never sees its eval cost, G4 GC during the form not recorded |
| model request / turn | Tokens: `:seon.turn/tokens`, cache hit/miss (turn.clj:104-114; facts.edn:300-303), `:seon.fx/prompt-tokens` etc. (agent.clj:136-157); attempt `:tokens` (facts.edn:183). Ticks: none (the turn's evals fall under G1) | none per turn | `:seon.turn/ms` (turn.clj:186), `:seon.turn/split` context/model/eval/record/tests ms (turn.clj:192, 220); `model.clj` completion `:ms` (model.clj:216) goes to the reply but the fx fact keeps the worker `us` instead | `:seon.turn/split` and `:seon.turn/ms`: not read by any render or context code (grep: only e2e.clj:267-271 and the turn writer). Tokens shown per task/attempt (ui.clj:281-341) | G5 split recorded but not shown (task seon.render.edn:19 owes it), G6 no memory, G7 no request/response bytes (the exact dimension for a network call) |
| effect `my.fx/call`, `my.fx/send` | Cost: `:seon.fx/cost` tokens for a model call, else none (fx.clj:407-415; agent.clj:155). Calls: one fx fact each | none | `:seon.fx/us` (worker perform wall, fx.clj:405-410) and `:seon.fx/queue-us` (admission wait), `:seon.fx/status` as an fx fact on the asker's branch (agent.clj:140-157; facts.edn:353-354). Both call and send go through `fx-record!` (agent.clj:181-193, 490). CPU: none | Not in render/context (no hit for `seon.fx/us` in render/ or context.clj) | G8 not shown; G9 an unanswered `send` (agent dies first) leaves no fact (task seon.fx.edn:15); G10 no bytes (file/git/web kinds) |
| Flow proc step (step-fn, workload, ping) | Message count only: `run-loop` counts steps `n` (step.clj:44-60), ping returns `:count :held :state`. Every contracted step Var gets the profile cell (calls exact, 1 in 64 timed; contract.clj:152) but only the eval/cost epoch reads it, and root's counts are "approximate" (profile.clj docstring) | none | none per step. Writer batch ms, render pass ms, fork lag, msg-us, main-seen-us are bespoke windows (see rows below). `:workload` is a declared tag, not a measurement (root.clj:1036 counts tags) | `seon.check/ping` shows count/state | G11 no per-step ticks/bytes/wall; "admitted by its measured workload" (AGENTS:102) has nothing measured to read |
| reconciler task run / attempt | Counts: turns, model-calls, tokens (facts.edn:181-183); cost verdicts of the attempt's cost proofs (`:seon.attempt/costs` = rows with ticks/ns/bytes/calls, reconcile.clj:586, proofs.clj:72) | none (`:mem-mb` only in root's view, never in `:seon.attempt/*`) | `:seon.attempt/start-ms`, `end-ms` epoch ms (facts.edn:174-175; reconcile.clj:537-642). CPU none | Attempt row: tokens and end-start ms (ui.clj:331-343); costs shown by cost-report (ui.clj:830) | G12 no attempt-level bytes/ticks/mem rollup; G5 split not on the row |
| test run (agent `:tests`, bin/test shard) | Ticks: only the whole-run budget (`:seon.test/ticks-spent`, agent.clj:367); a cost-proof test has its rows (cost.clj:166-179) and they ride the report to `:seon.attempt/costs`. Per test: pass/fail/error counts | none (child sets a GC trip of 16 MB, agent.clj:420, not a measure) | per test `:ms` (proofs.clj:216); run `:ms` (agent.clj:434); bin/test writes `target/prove/ms/<test>` (test_runner.clj:155) and shard `.time` (bin/test:42-44, wall+user+sys) | Run ms on the agent card/attempt (ui.clj:411, 883) | G13 the stored green keeps no cost: the agent writes `{:test :digest :pass 1 :fail 0 :error 0}` (agent.clj:438), bin/test shards store `ran` (with `:ms`, test_runner.clj:146); a reused green carries no measurement; G14 no per-test ticks/bytes |
| check step (bin/check, bin/prove, keyed by digest) | none; step stored as `{:step :digest :pass 1 :fail 0 :error 0}` (test_runner.clj:243) | `scale` step only: real-mb and free MB vs `:seon.config.machine/memory-mb` (check.clj scale; machine.md) | wall + user + sys seconds in `target/check/<time>/NAME.time` (bin/check:112-114), text, not keyed by digest; used to order the next run (clocks.md "Time orders a queue") | log lines only | G13 as above; G15 CPU/wall for steps is a file, hand-deleted (machine.md), not a result field |
| build (`:build` effect, `bin/build`) | none | none (doc claims "peaks under ~2 GB", machine.md; not measured by code) | `:ms` on the effect's value, written on main as `:seon.root/generation` (fx.clj:230-255; my/root.clj:243); on error `us/1000`. `bin/build` prints `date +%H:%M:%S` stamps only | generation fact; no render hit for it | G16 no memory, no CPU, no ticks (the work is an OS child: tick blind spot, bytes/children would be the counted dimension) |
| merge (agent `merge-main!`, octopus merge in writer) | Counts returned, not stored: `:keys`, `:datoms` (writer.clj:98-106); attempt `:merged-t` is a logical tx id | none | none dedicated. It sits inside the writer's batch ms (writer.clj:290) and the merge task's attempt start/end ms. merge.clj, program.clj read no clock | batch ms in the writer window only | G17 a merge's own wall/ticks/bytes/mem unrecorded, merged only as part of a batch window |
| Datahike transact / group commit | `tx-count`, `commits`, `flushes`, `n-tx` (writer.clj:287-290); `agent/transact!` returns `:datoms`, dropped. Bytes per commit: only the cost proof `seon.db-test/a-commit-costs-what-it-writes` | LMDB `map-use` used-bytes (db.clj:326-331), a gauge | `:batch-ms` per writer batch (writer.clj:290) into a 200-window in root's view (root.clj:404-407) and p50; tx/s (root.clj:806-812). The flusher's `pdb/sync!` (the fsync, writer.clj:299-315) is not timed | Dashboard writer tile (tx/s, batch p50), store size | G18 sync wall unmeasured, G19 windows are transient (root view), not facts, G20 no per-tx datoms/bytes at runtime |
| fork spawn (fork server, agent hello) | none (a C fork: tick blind spot) | `mem-mb` = real-mb at hello, each commit, tests reply, `:stats` (agent.clj:88-107, 487-507; fork_server.clj:80) in root's view; `:os-threads` | `:fork-ms` on the `:fork` event (fork_server.clj:72-77), `:start-ms` in hello (agent.clj:507), apply/lag ms (fork_server.clj:63-71) | Agent card: fork ms, mem MB (ui.clj:481-482); fork p50 tile (ui.clj:226) | G21 memory is in the view, not a fact: lost when root restarts and absent from the attempt; the 30x spawn regression task (seon.agent.edn:43) has no recorded split |
| render proc pass | `:patches` count, `:patch-bytes` window, `:rendered`/`:same-bytes` units (render/proc.clj:339-346, 349-357) | none | `:render-ms` and `:pass-ms` windows via now-ns (render/proc.clj:311-340); event age via currentTimeMillis | `/render.edn` link on the web stats strip (ui.clj:681) | G22 stats are in proc state only; no memory; no ticks |
| cost proof (the one place all four are measured) | ticks, ns (CPU), bytes, calls per size (cost.clj:166-179) | bytes (allocation) only; not live heap/RSS | cpu ns, never red | verdict box, cost-report (ui.clj:823-871) | G23 the stored green drops these rows (see G13) |

## Gaps

### Violations of the documents' own laws (the doc says X, the code or another doc does not)

V1. docs/agent.md:64, which every agent reads first, says "the judge counts ... allocated bytes 1.25x, calls 1.0x,
wall time 3x" and omits ticks. cost.md/AGENTS:151/cost.clj:50-56 say wall/CPU are never red and ticks are exact.
A stale doc is misleading an agent about what is red. Fix: the doc line only.

V2. docs/clocks.md lists `seon.agent/mem-mb` under "Decides by time, unmarked" (a once-a-second cache keyed by
time). agent.clj:88-93 now reads real-mb on demand and has no clock read (confirmed: it is not in the
`measures` sites, while the page still names it). The page's own rule says a site whose mark is gone leaves the
page in the same commit. Task `seon.agent/memory-is-read-when-asked` (seon.agent.edn:19) names exactly this.

V3. `seon.cost/measure` (cost.clj, the repeat loop) stops on accumulated CPU time (`spent >= 2e7`) or 25 calls.
A clock chooses how many repeats are taken before the least is kept, which can move `:bytes`/`:calls` only
if they vary across repeats (they do not), so the verdict is safe, but it is a time-decided branch that no mark
sees (`cpu-ns` is outside the `measures` pattern; clocks.md says `cpu-ns` "carries no mark"). It belongs in the
"Time decides (a check's measurement)" list or should loop a fixed count.

V4. AGENTS:151 says wall AND CPU "are measured, recorded, shown". CPU is recorded for cost proofs only, and as
text in `.time` files for bin/check and bin/test shards. For every runtime call kind above, CPU is not recorded.
This is the one place the stated law is not met as written.

V5. `bin/laws measures` (bin/laws:49) greps `currentTimeMillis|nanoTime|now-ns|now-ms`. The reads that matter
for this question (`cpu-ns`, `alloc-bytes`, `jolt.host/ticks-of`, `ticks`, `real-mb`, `gc-stats`) are not an
inventory anywhere, so no check can say which call kinds lack a dimension. I replayed the awk of the
`measures` kind to stdout (nothing written to target/): 54 sites, all named in clocks.md; the page names no
site that is missing from `measures`. So the wall-clock inventory is complete and honest; it is only
incomplete as an inventory of measurement.

V6. Root's profile counts are documented as approximate (profile.clj ns doc: several threads, one global epoch
via `begin!`). `seon_eval` runs `repl/eval-string` in root (server.clj:49-64), and its `:fns` and `:alloc-bytes`
are recorded on main like an agent's. `alloc-bytes` is per thread, so it is sound, but `:fns` can be wrong
under concurrent root evals and nothing marks that on the record. Honest limit, stated in code, not in the
record.

### Just missing (no law broken)

G1 ticks per eval; G2 CPU per eval/turn/effect/test; G3 eval cost not in the model's context; G4 collection time
inside a timed form; G5 turn split not shown; G6/G16/G21 memory for turns, builds, attempts (and a durable copy
of agent mem-mb); G7/G10 bytes in/out of model and other effects; G8 fx `us`/`queue-us` not shown; G9 `send`
outcome lost if the agent dies first; G11 per-step measures for Flow procs; G12 attempt rollups; G13/G14/G23
stored greens (tests and steps) carry no cost; G15 step CPU/wall only as hand-deleted files; G17 merge cost;
G18 fsync wall; G19/G22 windows live in proc/view state, not facts; G20 per-commit datoms/bytes at runtime.

## Top task proposals

All estimates below are derived from docs/cost.md numbers (a ticks read 4 ticks, 0 bytes; real-mb about 1.5 ms)
and must be measured across sizes by the implementing session before use.

### T1. Record ticks and CPU on every eval item

- Name: `seon.repl/an-eval-records-its-ticks-and-cpu`.
- Estimate: per form, 2 tick reads (8 ticks, 0 bytes) plus 2 cpu reads (about 2 x 100 ns). O(1) per form.
- Write set: `seon.repl/wrap-cost` (read `jolt.host/ticks` or use `ticks-of` around `ef`, plus `profile/cpu-ns`; add `:ticks` and `:cpu-ns` beside `:ms`), `seon.repl/item-keys` and `record-tx` select-keys (repl.clj:293, 313; the eval record map is open, facts.edn:114, so no schema change), `seon.render.ui/eval-meta` (ui.clj:487: ticks first, ms muted), `seon.agent` is untouched.
- Proofs: `seon.repl-test`: a form `(loop [i 0] (when (< i 1000) (recur (inc i))))` records `:ticks` within 1.0x-3x of the unit table estimate and the same in 5 runs at different loads (exact dim, so the test asserts equality of two runs, never a bound on ms); a form that only blocks on `Thread/sleep` or an effect records ticks near 0 and `:ms` large (shows wait vs work); a cost proof that the wrapper adds <= 10 ticks and 0 bytes. No assertion on `:ms`/`:cpu-ns`.
- Replaces: the budget-only ticks path as the sole reader of ticks (one reader, `seon.intr/run`'s count, now also feeds the record); `eval-meta`'s ms as the headline figure. Adds no second clock.
- Risk: whether `ticks-of` nests inside `run-interruptible`'s budget (cost.md names `nested-budget-test.clj` in the Jolt reference code; I did not read it). Use the plain `jolt.host/ticks` read if not.

### T2. A stored green keeps what its run measured

- Name: `seon.proofs/a-green-keeps-its-measure`.
- Estimate: O(tests run) extra integers per result; reads are the existing per-test begin/end hooks (proofs.clj:216, 227), 2 tick + 2 alloc reads per test.
- Write set: `seon.proofs/run-tests!` (`done!` adds `:ticks` and `:bytes` read between `begin-test-var` and `end-test-var`; it already adds `:ms`), `seon.agent/run-tests-forked` (agent.clj:436-438: store the run's result map for each test instead of the literal `{:test :digest :pass 1 :fail 0 :error 0}`), `test-runner/store-steps!` (test_runner.clj:243: add the step's `:ms` and cpu from `target/prove/steps/NAME.time`, which bin/check already writes), `seon.proofs/result-file` docstring, `test-runner/shard!` (drop `ms-dir`, let `measured-ms` read `:ms` from the stored result).
- Proofs: `seon.proofs-test`: two runs of one deterministic test at the same digest store equal `:ticks` and `:bytes`; `plan`/`greens` still reuse by digest with the extra keys (green? unchanged); a stored green with no measure (old format) still counts green. Never red on `:ms`.
- Replaces: `target/prove/ms/<test>` files (a second store of the same number, test_runner.clj:86-95, 155) and the two hand-written result literals. Net lines should fall.
- Risk: results are shared across checkouts (`bin/env`); a changed result shape needs a reader tolerant of old files.

### T3. A per-step meter for Flow procs, read by ping

- Name: `seon.step/a-step-records-its-work`.
- Estimate: per step 2 tick reads + 2 alloc reads, about 8 ticks, 0 bytes; kept as 3 integers in the proc state; shown through `:ping-map-fn`.
- Write set: `seon.step/run-loop` (agent and fork server, step.clj:44-60) and the root graph's proc construction (where `flow/process` wraps each Var; I did not locate the single site), `seon.root/state` to roll them into the view, `seon.render.ui` tile.
- Proofs: `seon.step-test`: a step doing a known loop reports `:ticks` equal across runs; a parked proc reports 0; the meter does not change any step's outputs.
- Replaces: the declared-only `:workload` tag as the only workload evidence (it stays the declaration; the measurement becomes the check on it); `seon.check/ping` stays for liveness only.
- Risk: root procs run on several threads: the ticks read is per thread, so a step is measured on the thread it runs on (fine), but the profile epoch counts are not.

### T4. Memory as a fact on the attempt, and bytes on effects

- Names: `seon.turn/an-attempt-end-records-memory`, `seon.fx/an-effect-records-its-bytes`.
- Estimate: one real-mb read per attempt end (about 1.5 ms, once) and per turn if wanted; request/response byte counts are already in hand in `seon.model`/`seon.fx` perform results.
- Write set: `seon.agent/write-end!` (agent.clj:246-258: add `:seon.end/mem-mb`), judge copies it to `:seon.attempt/mem-mb` (reconcile.clj:586 area), `seon.fx/worker-proc` (fx.clj:405-410: add `:seon.fx/bytes-out/in` from the perform result), `fx-record!`, `resources/seon/schemas/seon.facts.edn`.
- Proofs: attempt-end carries `:mem-mb` positive; fx fact for the echo kind carries bytes equal to the request length (exact).
- Replaces: nothing removed; the four view-only `:mem-mb` writes (agent_proc.clj:129, 200, 204, 209) remain the live gauge, now with a durable end value beside it.

### T5. Doc corrections (no code)

docs/agent.md:64 (V1) and docs/clocks.md `seon.agent/mem-mb` entry (V2), in the commit that closes
`memory-is-read-when-asked` or on their own.

## What I could not verify

- Nothing ran. I did not read a live store, a dashboard, a root or `runs/`, so "shown" is from render code, not
  from pixels; "recorded" is from the write path, not from datoms.
- That a green cost proof's rows reach `:seon.attempt/costs`: by code (test-report keeps `:cost` for any
  report type with a map `:actual`, agent.clj:285-300; `proofs/costs` takes every report with `:cost`) but untested by me.
- `jolt.host/ticks-of` nesting inside `run-interruptible` (T1 risk).
- Where root builds each Flow proc (the one place T3 would wrap): only saw `flow/process #'owner-proc`
  (fx.clj:~355-380) and `step/run-loop`.
- `bin/build`, `bin/check`: read in part by grep (`time` lines), not in full. Whether any check step records
  memory besides `scale` is unverified.
- The merge path in `seon.program` and `seon.merge` was searched for clock reads only (none), not read in full.
- The `seon` MCP server (the `seon_eval` path) is down in this checkout (no root running); I read
  server.clj:49-64 and repl.clj only.
- `bin/laws measures` was not run (it writes `target/*.txt`). I replayed its awk to stdout. That left one
  scratch file, `sites.txt`, in the session scratchpad (outside the repo); nothing under the repo changed
  except this note.
