# Root's schedule (2026-10-23, updated afternoon)

Rules: one heavy run at a time, root schedules it; others REPL only. Every branch: merge main, revert check (`git diff main HEAD`), one `bin/prove` (plus the named process tests when it touches the turn loop, the gate or roots), a §9 review, then root merges (ff main, `bin/update`). Owner authorized pushing fork branches to seantempesta/* (no force, never overwrite main).

Owner rulings in force: GitHub fork URLs; the root at ~/src/seon-next (worktrees in .worktrees/NAME), made by a fresh clone; cost is work done (ticks, bytes, calls); process tests aren't automatic (the reach walk); content-derived ids; global addressing and the all-branches union; tests on change through Seon (one test service); renders are (value opts) fns with auto-render; "attempt" → "run" everywhere.

Owner (evening): all 9 forks are public (persistent-sorted-set and jolt-overlay made public). Step E paid DeepSeek runs are authorized within the $50 budget, productive only (stop and fix the context or code when a run shows a known defect). On resume: read AGENTS.md, docs/plan.md, docs/decisions.md and this file before replying to any session.

Merged since the list below: dry-run (8a901ac3), global-ids (94df9c26) plus the Malli gitlink fix (ea8f7c44), plan.md refresh (4adb7ee5), health's test service (bb59dabf), docs fixes (7e41b7aa). Loop turn 11.4 s. In the slot: reach-update; then refresh, divergence, dry-run's fork-server replica (c'), proof-ends, agent-view, rename-run (993c7864), self-contained (A, push, C, R).

Later evening: merged reach-update (c5359a22, plans 4.3 s → 1.1 s) and health caused O(changed) (ade54613). Loop turn ~5.7 s. Slot queue now: jolt/refresh (pure-tests, in slot) → jolt/agent-stage (global-ids fac50739: onto-tx nil fix, stage-tx 41→5 ms) → jolt/health wiki classes 72dd382e (+ git baseline) → dry-run's fork-server replica (c') → jolt/main-graph (shrink2 2917803, plans 76 ms) → jolt/divergence → jolt/proof-ends → jolt/agent-view (61e63a9, main-info flat) → jolt/rename-run → self-contained → step E paid runs (authorized).

State at handover (~12:00): main e2d69fe3. Merged since: refresh (c2abfd3d), agent-stage (ed56949d), replica (7a66bbac), main-graph (ce200899), proof-ticks (e2d69fe3). Loop turn 2.8 s (5 plans 55 ms). Proving now: health 47f37226 (turn :attention fix + git baseline; 86 of 139 code-citing sections stale), divergence d4333e27. Then: wiki-live fixes the 86 stale sections (jolt/wiki-stale, waits on health landing); proof-ends 3f39de69 (re-run seon.agent-test); agent-view 61e63a9 (messages as facts via seon.union, then prove, Haiku round, full run); the first full run; rename-run 993c7864; self-contained (A, push forks, C, R); step E paid runs (authorized, $50).

Afternoon (~12:10), owner: "the machine is basically idle so we really could be doing more". The rule is now up to 3 heavy runs at once while load < ~8. Cleanup: 19 merged worktrees removed (31 GB; 21 left); 5 old `seon mcp` relays killed (~1.3 GB; sessions reconnect to the 6 MB perl relay); orphans (2× jolt t4.clj in ../e10, a wlock in an old scratchpad) await the owner's word. Root RSS was 1.08 GB 10 min after start (budget 4096 MB): re-measure for growth.
Distributed plan (messages sent after the owner's next message; the app caps root at 10 cross-session messages per user message):
- Runs now (≤3): proof-ends (merge main, agent-test, prove; 2 doc fixes), agent-view 8c9abd5 (prove, then messages as facts, Haiku round, full run), divergence (when its review fixes are done).
- REPL/parallel, no run: health (7 review fixes, ~1–1.5 h), wiki-stale (5 Sonnet page batches off health d35f88d9), self-contained (push Jolt e0351af and Datahike b4596cf, step C edit, merge main, reconcile worktree-ports), dry-run (merge main, check the context, then one paid run on seon.context/the-map-reads-the-program-not-the-process), shrink2 (drivers-out: seon.check out of src), check-events (agent-ready2: merge main, prove, or drop it).
- Merge order: proof-ends, divergence, agent-view, health, wiki-stale, drivers-out, agent-ready2; then freeze main for rename-run; then self-contained A and R; then step E's further runs.

To assign next (no owner yet): every call kind records its work done (ticks, bytes, calls) as facts: turns, effects, Flow steps, merges, builds, REPL evals (with the reuse hit/miss the owner asked for); today only cost proofs record ticks, and `bin/laws measures` inventories wall-clock reads only (notes/timing-audit-2026-10-23.md). Also triage notes/jolt-debug-features-2026-10-23.md into tasks.

## Landed today (main 571423af)
land14 (hold-for-root, agent-ready, printer-eval, wiki-live), land15 (reach-index, process-tests, config set fix, Jolt pin f05de7db), rename pass 2 (store erased), the vocabulary link fix, cost-sizes, health part 1, test-root, wiki-live part 2 (docs/plan.md, docs/decisions.md), starter-fifo, the stop-hang fix, pure-tests (seeds, Malli 0762fb6b, time out of verdicts), brief3 (shared greens), shard-time (r3 one seed), ticks in cost proofs, no-idle-build, reach-scc2 (linear walk).
Measured: the cold automatic prove 26 min this morning → 176 s → 94 s → ~62 s (no-idle-build) → 54 s (dry-run's tip). Warm: planning only (~8–13 s).

## Now, in order (reprioritized)
1. **dry-run** (3cc3b58f): the gate's named process run is going, then merge. It carries the turn-cost fixes, the gate on demand and test-plan caching. The first measured loop turn comes from e2e-loop.
2. **health's test service** (18e3a8d9 + the 5 review fixes): one `seon_prove` for every agent. It's the owner's top ask ("cheap shared test runs so we catch other shit").
3. **global-ids** (15f627f+): the store-wide counter, delta merges (merge 2 s → 18 ms), content ids, verbatim merges, the union (≤ 5 ms per query at 100 branches). Needs one Jolt pin bump (hashset-bulk) plus Datahike and pss pins; then **pure-tests' divergence** right after.
4. **store-gc** (Datahike jolt/gc-cache-epoch 700c473 + Seon 2a033b5): a store-correctness bug. Merge with or right after global-ids, since both bump the Datahike pin, so they need one combined Datahike branch.
5. **The first full run** (`bin/test --all` + `bin/check 100`), alone, after 1–4.
6. **health**: caused at O(changed), then the Flow work (commit stream, :watch → :load procs), then every write checked (mg/check per var on change).
7. **shrink2**: the incremental reach update (one cache with dry-run's walk).
8. **agent-view** (6596484): prove, one Haiku round, then its full run. Messages as facts with global-ids' run-id addressing.
9. **proof-ends**: once its test is green.
10. **rename pass 3** (6f0c21c, attempt → run): it merges last before the move, re-running its script over main; erase the store.
11. **self-contained**: step A (test-results and worktree-ports inside the root), push the fork branches, step C (GitHub URLs), copy notes/archive, upstream and flowlab in, then **step R** (a fresh clone into ~/src/seon-next, build, prove, grep clean), the second full run, then move the sessions and copy the memories.

## Rescheduled or deferred
- **Step E with paid DeepSeek runs:** after 1–3 land (turns fast, the gate on demand, the union). That's the next real milestone: a model-made change merged end to end.
- **Parity gaps** (docs/parity.md, 13 missing): after step R; each one is built or recorded in BAGGAGE.
- **The planner `:in`-scalar defect** (8 ms vs 162 µs), **file-digest-keyed analysis reuse**, and **ns-map reading the program**: filed tasks, after the full run.
- **The Mach-port thread join** (lifecycle :os): deferred; lifecycle records the OS threads per row instead.
