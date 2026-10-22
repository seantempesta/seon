# An honest assessment of Seon on Jolt (2026-10-23, morning)

Written by root at the end of the overnight session, for the owner and the next root. Main is `f7afe29` (Jolt `4342b73c`, Chez `80e4b9f`); the standing root runs it on 7888/7777. Numbers are measured unless marked.

## Where the goal stands

The goal is real Seon agents fixing problems in the system (THE PLAN's step E). **Not there yet, but the path now runs end to end.** One small real request has gone design → implement (the merge gate is built but has not yet merged a model-made change) on DeepSeek, for $0.144 in 7 paid runs. Each run found a platform defect, every one now fixed at its cause:

- forked agents inherited dead core.async threads, so `go` blocks and in-memory Datahike hung (Jolt `jolt-after-fork-child!`);
- agents' new entity ids overlapped main's (the lease counter: one reservation from main's counter);
- a design could end green before writing its proof; a repeating turn now ends `:no-progress`;
- a refinement started on a fresh branch and lost all work (now: no automatic refinement at all; a refused attempt is held for root, owner's ruling; lands with hold-for-root);
- `(source ns)` printed nothing; `source` of a planned var said "not found" with no way forward;
- DeepSeek's `reasoning_content` was not sent back (a 400 on the second call with thinking on);
- a change in hub code made every turn's proof run ~294 tests in one fork (2.5 h): now three tiers (per turn: the task's proofs and changed tests under a tick bound; the judge: the reached set once, reused greens; the merge gate: process tests).

**The agent's behaviour is the real gap.** With thinking off (the owner's default: "the gut instincts of a smart model" show whether the system is intuitive), the model explores for 15–18 turns, two source reads a turn, before writing anything; it re-reads what the 32k history window dropped; a ranked namespace map changed nothing measurable. Thinking on made the first design act by turn 20 (run 7). Reading: the context does not tell a smart model clearly enough **what to change, where, and how it will know it is done**, and it loses what the agent learned. That is the next focus (the owner's): the context, and a web debug view to see each turn.

## What is solid now

- **Merging is disciplined:** every branch reviewed against AGENTS §9 before merge (the reviews caught a re-introduced sentinel, cut-before-printer sites, a placebo test, stale-green paths, a data-corruption bug), batches proven on a frozen tree, full `bin/test --all` + `bin/check 100` green on every batch since land8.
- **Testing is smart:** `bin/prove` / `bin/test` select by reach digest and reuse greens (an unchanged tree: 425/425 reused in ~14 s); procs named in `system.edn`, binary-launching tests and the resolved config are digest inputs; a shard stores nothing if the tree changed since the plan; the build rebuilds a stale Jolt or Chez by stamp. Known gap: `bin/test --reach` (an old flag) still passes silently on main until agent-ready lands its fix.
- **Checks wait on events:** idle waits for the flush and the fork server (both checked, not assumed); an unknown check mode fails; one root launcher for tests (lands with test-root) whose roots die with their starter.
- **One printer** (`seon.print`), no cut before it (a law test over the compiler's facts), declared `:ai`/`:html` renders for errors, tasks, attempts, problems; MCP answers are renders, not escaped EDN.
- **Processes:** agents end with their root (SIGIO on EOF); a signal now wakes a blocked condition wait in Chez, so `interrupt!` stops an eval blocked on a channel; `bin/mcp` is a 6.4 MB relay (was 730 MB each).
- **Cost proofs** measure the calling thread's own bytes and CPU (a per-thread count in Chez).
- **Words:** AGENTS §7a; code and docs renamed (pass 1); stored names pass 2 ready.

## What is not good

- **`src` grew: 16,549 → 17,470 lines** against a ~10k aim. Deletions (≈ −400: duplicates, dead code, shrink brief 4) were outweighed by capability (renders +190, prove +270, the task graph +106, process and test machinery). The big shrink lever is untouched: moving check/e2e/fixtures out of `src` (≈ −1,800, brief 1). Honest read: the code is cleaner and more correct, not smaller.
- **Step E has not merged a model-made change.** No agent has gone green on its own with thinking off.
- **The web UI has no real agent debug view.** `/agent/{id}` zooms an agent's numbers and context; there is no turn timeline, no "what it saw at turn N", no message bar, no `/ns` pages (v1 had them). agent-view is researching a design (no code).
- **Flakes under load were many and are mostly fixed;** expect more: every red tonight that "passed alone" had a real cause (an inherited env var, a clock window, ordering) when chased.
- **Process hygiene cost time:** orphaned roots and agents from tests, one `pgrep -f` kill of another worktree's test, one worktree removed before its session archived. Rules are in AGENTS §8 and RESUME's lessons; the holder now ends a check's roots with it.
- **Paid runs:** $0.144 of $50 spent.

## In flight (branches ahead of main)

| branch | what | state |
|---|---|---|
| `jolt/hold-for-root` (+5) | refused attempts held for root; refinement chain, budget, stuck deleted | proving on main; next to merge |
| `jolt/rename` (+20) | pass 2: stored names; no migration; root erases `runs/store` when it lands (owner's go) | green; after hold-for-root |
| `jolt/test-root` (+5) | one test-root launcher, `bin/held` the one holder, `bin/root` with no sleeps, bound port read back | finishing |
| `jolt/agent-ready` (+1) | spawn/attach answer when the agent is ready; `bin/test` refuses unknown flags | finishing |
| `jolt/brief3` (+3) | shrink brief 3 (≈ −90): program facts one owner; agents share greens | in progress |
| `jolt/printer-eval` (+2) | printer trims decided by blind Haiku scoring on real data (owner: evidence over line count) | full run, then report |
| `jolt/dry-run` (+22) | step E: the gate's `:tests` effect, three-tier test plan, thinking off default | in progress; owes the owner a real context sample |
| `jolt/agent-view` | the agent debug view: research and design only | researching |

## Owner decisions still open

- None blocking. Recorded rulings (DECISIONS, top): no automatic retries; the standing store is test data (erase, don't migrate); output choices decided by blind scoring; thinking off by default; agents' greens shared; the dashboard stays in `src`; waste stays.

## Recommended next (for the owner's focus)

1. **See a real context** (dry-run writes `notes/context-sample-design.md` in `../next-dry-run`), then tune the context by evidence: real task contexts given to fast models with thinking off, scored on their first move (do they act on turn 1, know what to change and how done is judged); iterate until they do; confirm with paid runs.
2. **The agent debug view** from agent-view's design: a turn timeline with the exact context per turn, the reply, evals, proofs, `:will-merge`, problems; eval box and message bar; live and ended agents.
3. **Then shrink brief 1** (drivers out of `src`, ≈ −1,800).
