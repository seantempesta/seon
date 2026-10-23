# Notes triage: what is live truth, what is a rule, what is work, what is history (2026-10-23)

Read-only audit for root. Sources read: `../RESUME.md` (149 lines, 31 KB), `../notes/` (60 .md files, 4 dirs), `next/notes/` (15 .md), `next/AGENTS.md`, `next/BAGGAGE.md`, `next/docs/*.md` (19 pages), `next/resources/seon/tasks/*.edn` (44 files, 190 tasks). Main checked at `b39cef5`. Nothing was edited but this file.

Classes: **(a)** live truth for a wiki section; **(b)** rule or decision for AGENTS or DECISIONS; **(c)** plan or work state for a task entry or RESUME's in-flight list; **(d)** history (keep in git, out of the live docs).

---

## 1. Contradictions (fix these first; each is two live docs disagreeing)

| # | Subject | Says A | Says B | Current truth (owner's latest) | Fix |
|---|---|---|---|---|---|
| C1 | **Thinking default** | RESUME State, "Step E runs 6–7": thinking on "becomes the deepseek profile's default" | ASSESSMENT: "thinking off (the owner's default)"; `default.edn:304` `:seon.config.ai/thinking :disabled`; `jolt/dry-run` makes off the default | Off. **DECISIONS has no entry for it**, though ASSESSMENT says "Recorded rulings (DECISIONS, top): … thinking off by default" | Add the ruling to DECISIONS with its quote; delete RESUME's run-7 paragraph |
| C2 | **Agents' greens shared** | RESUME open decision "Should an agent's test greens be shared?… agents' greens stay in a store scoped to their root"; `docs/merging.md:34` "not shared … until the owner rules they may be"; land12 paragraph | DECISIONS "Owner answers, 2026-10-23 morning": shared ("tests that are the same … should not run again") | Shared (lands with `jolt/brief3`) | Rewrite `merging.md#…Agents` when brief3 lands; drop the RESUME open decision now |
| C3 | **Automatic retries / refinement** | DECISIONS "A task is the work… retries are tasks" (a retry adds tasks), "Merge is a step… attaches new design → implement → merge" (2026-10-20), DESIGN-tasks D2(b); RESUME open decision "Automatic refinement vs D2(a)"; `default.edn:206-222` refinement-chain bound; tasks `seon.reconcile/a-failure-attaches-a-refinement`, `stuck-is-observed-data`, `a-merged-fix-retries-what-it-failed` | DECISIONS top: "No automatic retries: a refused attempt is held for root"; `docs/tasks.md:36` already says "never retries it on its own" | Held for root (lands with `jolt/hold-for-root`) | Mark the three older DECISIONS entries superseded; delete the RESUME open decision; delete/close the three reconcile tasks and the config key when hold-for-root merges |
| C4 | **Migration of stored names** | RESUME open decision "Go for rename pass 2 (stored names + migration)?" (long, with migration timings); task `seon.db/stored-attributes-take-the-new-words` (migration); AGENTS §7a "stored as … until migrated" (×3) | DECISIONS top: "The standing store is test data: erase it, no migration" (also DECISIONS 2026-10-20 "No old stores exist… No migration") | Erase, no migration | Rewrite the task plan; AGENTS §7a: drop "until migrated"; drop the RESUME item |
| C5 | **stuck / waste words** | AGENTS §7a: stuck → blocked, waste → overrun; DECISIONS "Names are Clojure's" (owner approved stuck→blocked, waste→overrun); task `seon.db/stored-attributes…`: `:seon.task/stuck -> :seon.task/blocked` | RESUME open decision: stuck → `:seon.task/budget-spent`, waste stays; DECISIONS "Owner answers 2026-10-23": waste stays; hold-for-root deletes stuck and the budget entirely | waste stays `:seon.attempt/waste`; stuck goes with the budget | AGENTS §7a: delete the stuck row, change the waste row to "waste (kept)"; amend the DECISIONS names list |
| C6 | **Erasing the store vs "runs are kept"** | AGENTS §9 "Deleting a record (a store, a branch, history). Runs are kept, and reclaiming space is the owner's act"; DECISIONS 2026-10-21 "Runs are kept … Root never deletes a store"; `storage.md:87` "with history kept and runs kept" | DECISIONS top: the standing store is test data, root erases `runs/store` when pass 2 lands | Both, scoped: until there is real user data the standing store is test data, erased on the owner's go; history within a store is kept | One sentence in AGENTS §9 and DECISIONS "Runs are kept" naming the exception |
| C7 | **Printer trims (rows 36–38)** | RESUME open decision "The printer's three trims (… not acted on)"; DECISIONS "Root runs autonomously" / "The printer is the one printer; trimming lives only there" | DECISIONS top: "Output quality is decided by evidence, not line count" (blind Haiku scoring overrides earlier rulings); `jolt/printer-eval` doing it | Decided by blind scoring | Drop the RESUME open decision; the scored result goes into `render.md#one-printer` |
| C8 | **"How small can src get? is the dashboard src?"** | RESUME open decision | DECISIONS "Owner answers": the dashboard stays in `src` | Stays | Drop from RESUME; note in `machine.md` or a size section |
| C9 | **THE PLAN's home** | RESUME "THE PLAN" (A, B0, A2, B, C, D, E) | `notes/PLAN-v3.md` titles itself "The plan (current, 2026-10-21)"; `notes/PLAN-simple.md` says "Nothing in this file is built yet" (tasks-at-once landed it) | RESUME's | Archive PLAN-v2, PLAN-v3, PLAN-simple |
| C10 | **THE PLAN's steps that are done** | RESUME THE PLAN lists B0.1–3 (landed land2a/land5), A2 (store-native measured; compiled units deferred), B's "an unrun proof is unknown" (landed land2a) as to-do | RESUME's own State paragraphs and DECISIONS "Compiled units in the store: deferred" | Done or deferred | THE PLAN shrinks to E plus what still blocks E (§3 below) |
| C11 | **Paid-run budget** | DECISIONS 2026-10-21 "Paid DeepSeek runs: no cap while they are useful" | DECISIONS 2026-10-22 "up to $50, productive runs only"; AGENTS §8 "Paid model runs need the owner's go"; DECISIONS "Root runs autonomously" (paid within $50) | $50, productive only; root may run them autonomously | Mark the 10-21 entry superseded; AGENTS §8 says "within the owner's standing $50 budget" |
| C12 | **Agents creating tasks** | DECISIONS "Hand-offs are writes along the task graph": "Agents never create tasks or write needs" | DECISIONS "Tasks are unrestricted" (says it supersedes it) | Unrestricted | Delete or strike the older entry (it still reads as current) |
| C13 | **`:seon.task/kind`** | DECISIONS "Tasks are extensible, with no explicit kinds… `:seon.task/kind` goes" | all 190 tasks carry `:seon.task/kind :work` (one `:kind :design`) | Kind goes | A task: remove the attribute from the files and schema |
| C14 | **Merge trains / octopus merges** | DECISIONS "Root's calls on R3": trains off (`max-running 1`); task `seon.fences/merge-trains-off-as-decided` | `docs/merging.md` batches branches into main; North star item 1 "Merge trains" | Root batches git merges (worktrees); Seon's in-store octopus merge is still off | Say so in `merging.md` (git batches vs Seon merge trains) |
| C15 | **Dead links in the read path** | RESUME "Read, in this order" #8 and DECISIONS (agentic loop) cite `next/docs/DRAGONS.md`; DECISIONS line 3 cites `SESSION-STATE.md`, `HANDOFF.md`; `docs/forks.md:30` cites `notes/HANDOFF.md`; `notes/lessons-pending.md` targets `docs/worktrees.md`; BAGGAGE cites `../proto/BAGGAGE.md` | none of these exist (DRAGONS became `docs/failure-points.md`) | — | Fix each link; archive lessons-pending after checking its edits landed |
| C16 | **`src` size in "who you are"** | RESUME header "`src` stays small (16.8k lines today)" | ASSESSMENT 17,470 | 17,470 | RESUME's numbers live in one place (State line) |
| C17 | **The agent's model** | DECISIONS 2026-10-20 "DeepSeek is the default model (deepseek-flash)" | RESUME land6: "no default model: a root with no `SEON_PROFILE` answers `:seon.model/no-provider`" | No default; profiles `deepseek`/`scripted` | Mark superseded; this belongs in a wiki section (see §2, missing pages) |

---

## 2. Live truth (a): where each belongs in the wiki

Items now only in RESUME, notes or DECISIONS that describe how the system works today. "Has it?" says whether the target section already states it.

| Fact | Source | Target page#section | Has it? |
|---|---|---|---|
| Forked agents reset Jolt's carriers, timer and thread tables (`jolt-after-fork-child!`); `go` and memory Datahike work in agents | RESUME land9/10, "Step E's first blocker" | `core-async.md` (fork section) and `failure-points.md` | partly (core-async mentions carriers); state the rule, drop the story |
| A blocked condition wait now wakes on a signal (Chez `jolt/signal-wakes-wait`), so `interrupt!` and SIGIO reach it | RESUME "A blocked wait can't be interrupted" (now stale: it says can't); ASSESSMENT | `failure-points.md` (agent ends) and `forks.md` (Chez pin) | check; RESUME's paragraph is now false |
| Agents end with their root (SIGIO on the link's EOF; group kill) | RESUME "Agents outlive their root"; ASSESSMENT | `failure-points.md#root-dies` | yes (grep hits SIGIO) |
| Entity-id lease: each agent's block is reserved from main's one counter | RESUME land9/10 | `storage.md` (lease) | yes |
| Test tiers: per turn = the task's proofs + changed/added tests under `:seon.config.agent/turn-test-ticks` (a hit is unknown, not red); judge = the reached set once via prove; merge gate = deferred process tests | RESUME runs 6–7, "Agents outlive…" (dry-run ruling) | `merging.md` (Agents bullet) or a new `agent.md#tests` | **no**: no page mentions turn-test-ticks or deferred process tests |
| No default model: a root without `SEON_PROFILE` answers `:seon.model/no-provider`; profiles `deepseek`, `scripted`; thinking off by default; DeepSeek's `reasoning_content` must be sent back | RESUME land6, ASSESSMENT | **missing page**: `model.md` (provider, profiles, thinking, prompt caching, cached tokens, budgets, spend unknown ≠ 0) | **no**: only `source.md`/`storage.md` mention SEON_PROFILE in passing |
| `bin/mcp` is a 6.4 MB relay; root's server answers MCP | RESUME land5 | `parity.md` MCP rows | yes |
| A repeating turn ends `:no-progress`; a design can't end green before writing its proof | ASSESSMENT | `tasks.md` (done rule) | check; likely not |
| Reach digest inputs: procs named in `system.edn`, binary-launching tests, resolved config; a shard stores nothing if the tree changed | RESUME land9/10, ASSESSMENT | `merging.md#tests` / `source.md#tests-run-by-reach` | yes (merging.md:32-34) |
| Cost proofs count the calling thread's bytes and CPU | RESUME land12 | `cost.md` | yes |
| One printer, no cut before it, `:ai`/`:html` renders on schemas | DECISIONS "Root runs autonomously" | `render.md#one-printer` | yes |
| Merge mechanics: stop on a conflict; never merge into a batch while its proof runs; archive a session before removing its worktree | RESUME "Root's merge lessons" | `merging.md` (batches) and `machine.md` (worktrees) | **no**: these are lessons in RESUME only |
| Kill only by a checked path, never `pgrep -f` a test name | RESUME merge lessons | AGENTS §8 (already: "never pkill") plus `machine.md` | partly |
| A Claude session's `bin/mcp` holds its worktree; archive before removing | RESUME land2a | `machine.md` | check |
| Storage plan A: history kept, zstd nodes, large values out of line, native plain values, GC at quiescence, tx facts on the replica | RESUME "Reset done" | `storage.md` | yes |
| Spawn regression cause (LMDB 64 GiB map walked page by page) and its fix | `next/notes/spawn-regression-2026-10-22.md`, `lmdb-open-regression-2026-10-22.md` | `storage.md` (map size) or `failure-points.md` as a one-line "Not this" | check |
| Store identity: a moved store opens | RESUME land1, `store-native` note | `storage.md` | check |
| Compiled units: forks inherit the compiled program COW (0.68 ms p50); units are 15× source, hold absolute paths, die with a pin bump | `next/notes/store-native-2026-10-22.md` | `source.md` or `storage.md` "Not this: compiled units in the store" | check |
| Tx ids collide across branches; name transactions by commit id; Datahike `persistent_set.cljc` ~:290 walks two trees skipping shared nodes | RESUME THE PLAN D; LEARNING | `merging.md` or `storage.md` | check; the plan text is the only home |
| v1 lessons: a reach-chosen merge list misses chain-only helpers; per-branch tx ids; context naming helpers (R4: 7/32→22/24); prompt caching 85–91% | LEARNING "Failed in v1"/"Worked" | `merging.md`, `render.md#context` (the context section), `parity.md` | partly |
| Measurement method (estimate first, ≥3 sizes, footprint lies for forks) | `notes/method.md` | `cost.md` | check; method.md is the only home |
| Fork scheme: `jolt/main`, `jolt/<topic>`, `upstream/<topic>`; upstream only when 100% sure, no AI trailers | DECISIONS "Libraries on Jolt" | `forks.md` | likely yes |
| Step-fn rules 1–11 | DECISIONS "Step-fns done right" | `flow.md` (AGENTS §5 summarizes) | yes |
| Durability: continuous group commit | DECISIONS (superseded window entries) | `storage.md` | yes |
| `seon_agents` returns 230+ tasks, too much | `parity.md` | `parity.md` | yes |

**Pages the wiki lacks** (live truth with no home): `model.md` (provider, profiles, thinking, caching, spend, the $50 policy pointer), and a section for **the turn loop and its test tiers** (`agent.md` is only the help page; the turn loop, observe, judge, `:no-progress`, three tiers have no section). `tasks.md` should gain the hold-for-root flow once it merges.

---

## 3. Rules and decisions (b)

### Keep in DECISIONS (current, owner's words), newest first

1. No automatic retries; a refused attempt is held for root (2026-10-23).
2. The standing store is test data: erase, don't migrate (2026-10-23).
3. Output quality decided by blind scoring on real data, overriding earlier rulings (2026-10-23).
4. Owner answers 2026-10-23: waste stays; agents' greens shared; dashboard stays in `src`; one printer.
5. **Thinking off by default** (missing: add with its quote and date).
6. Root runs autonomously while there is good work; one printer; renders for important data (2026-10-22 night).
7. Root makes the whole linked task set in one transaction, then forks.
8. Names are Clojure's (with the list amended per C5).
9. Long runs only when the work is done; testing runs only what a change reaches.
10. Compiled units deferred, idea open.
11. Lanes (worktree sessions) are truly independent.
12. D4 (never happens; a conflict for root), D5 (a).
13. Paid runs: $50, productive only, context fixed first.
14. A failed task is task data (`fail!`), root reacts.
15. One store holds the whole system (the principle; its order bullets are history).
16. Our Datahike is Jolt-first; storage tuned for the workload.
17. Agents work at the REPL through tool calls; no git in agents; no source-file editing.
18. Tasks unrestricted; behaviour follows the data; effective config derived.
19. One proofs field; templates are held tasks in the task EDN; design → implement → merge.
20. RELOAD goes; DeepSeek every stage, measured.
21. Agents run only on a sound platform.
22. No locks, no lane cap, no waiting.
23. Every live run better than the last; no rerun matrix.
24. Direct linking stays.
25. The BUILD, SCHEMAS and PKG plans approved (keep one line each pointing at the plan; the plans themselves are history once built).
26. Minimal code: extend the existing systems.
27. Disk stays bounded.
28. Files/namespaces/symbols are plain Markdown; one reader.
29. `bin/root` restarts the next generation.
30. Process, not whim.
31. Line citations keep v1's shape.
32. The 2026-10-20/21 architecture rulings that still hold: everything on Jolt; fork server; Flow as the one model; ordering is dataflow; incremental graph changes; reconciliation over attributes; effects through owner procs; data-centric validation; system procs in one file; REPL semantics (namespaces); get out of the way; loading code is an effect; every definition once; programs from the compiler; agents stay forked and named; admission by measured workload; embeddings fused and measured; patterns live in topic sections; nothing changed → nothing; no clocks (with the three exceptions); a failure goes to whoever can fix it; only documented config; branches as in production; a merge carries program and agents' data, not platform records; estimates are proofs; renders attach to schemas; the UI spec; one messaging system; outside agents via thin MCP; no extra caches; native-code policy; contracts on every function.

Most of item 32 is already stated in AGENTS §§1–5 or a wiki page. DECISIONS should keep the ruling (one line, the quote, the date, a link to where it is now stated), not restate the mechanism.

### Mark superseded in DECISIONS (or move to an archive section)

- "A task is the work… a retry adds tasks" → superseded by no automatic retries (C3).
- "Merge is a step, and every step is a fresh agent" / "Merge is a task… refinement" / "The agent does the merge" / "M1's design" (send-back, catch-up, too-hairy) → superseded by hold-for-root and tasks-at-once (C3).
- "Hand-offs are writes along the task graph" (agents never create tasks) → "Tasks are unrestricted" (C12).
- "Names are Clojure's": the stored-name items stuck→blocked, waste→overrun, "with a schema migration" (C4, C5).
- "Runs are kept … Root never deletes a store" → scoped by "the standing store is test data" (C6).
- "Paid DeepSeek runs: no cap" → $50 (C11); "DeepSeek is the default model" → no default model, profiles (C17).
- "The audit's levers come before A2", "B0's levers and A2 run at once", "Priorities now", "Order: finish plan A" → done; history.
- "Leftovers" (remove next-laws, restore swarm) → the first done; the second is an open work item (RESUME's owner row).
- "One root per worktree, agents as fibers" → already marked superseded; move it out.
- The 2026-10-20 writer-design, durability-window, eval-durability, C-mem entries → superseded by continuous group commit and run-ahead; keep only the result lines in `storage.md`.
- "Owner's leaning … per-agent graphs", "Each agent is a proc in its own Flow graph", "Assembly choices" → check against the code; they read as open measurements.
- "Lanes become Seon agents" (order P1, door, one feature) → state: still the direction? It conflicts with "Lanes are truly independent" in spirit; ask the owner whether it stands.
- The "Good ideas worth keeping" and "Planned tests" sections → history (the proofs that exist are deftests now; the list names `proto/core`, `bin/proto`, `./demo.sh`, none of which exist).
- Header line: "owner-approved, 2026-10-19/20; read with SESSION-STATE.md and HANDOFF.md" → both gone.

### Belongs in AGENTS

- The merge lessons (stop on conflict; never merge into a batch while it proves; archive before removing a worktree) → AGENTS §3 or `merging.md`, linked from §9.
- AGENTS §7a table fixes (C4, C5).
- AGENTS §9 exception for the standing store (C6).
- AGENTS §8 paid runs: name the $50 standing budget (C11).
- RESUME's "Who you are" and "owner's direction" paragraphs duplicate AGENTS §0–§2; keep them only in AGENTS.

---

## 4. Plan and work state (c)

### RESUME "In flight" today (rows 18–32) sorted

| Row | Class | Where it goes |
|---|---|---|
| prove verb | (d) landed land12 | delete |
| shrink wave 3 (config-shape, drivers-leave-src, unitflake, task 15/17/10/19) | (c) | tasks `seon.main/drivers-leave-src`, `seon.fences/*`; one RESUME row "shrink brief 1" |
| small follow-ups (≈20 items in one cell) | (c) | each a task entry (several already are: `seon.render/*`, `seon.db`); delete the cell |
| owner: restore `bin/check swarm` with a verdict? | (b)/(c) | one-line open owner question |
| tasks-at-once, context-v2, store-native, reds, harvest, dup-views/helpers, mcp-relay, check-events, prove, duplicates audit, rename pass 1 | (d) landed | delete |
| rename pass 2 | (c) `jolt/rename`, after hold-for-root | RESUME row |

The current in-flight set is ASSESSMENT's table: `jolt/hold-for-root`, `jolt/rename`, `jolt/test-root`, `jolt/agent-ready`, `jolt/brief3`, `jolt/printer-eval`, `jolt/dry-run`, `jolt/agent-view`. RESUME should hold exactly that.

### RESUME "Open owner decisions" sorted

| Item | Status |
|---|---|
| Rename pass 2 go + migration | answered (erase, no migration): delete |
| Agents' greens shared | answered (shared): delete |
| Printer's three trims | answered (blind scoring): delete |
| stuck/waste words | answered (waste stays; stuck deleted): delete |
| Automatic refinement vs D2(a) | answered (no retries): delete |
| How small can src get / dashboard | answered (dashboard stays): delete; the size question is a standing goal, not a decision |
| Changed-since: tree diff vs `:seon.tx/changed` | **open**; move onto a task (`seon.merge/program-cost-follows-what-changed`) with "prototype the tree diff first" |
| DESIGN-tasks D2–D7 | D2 (a) done and superseded by no retries; D3 (a) done (tasks-at-once); D4, D5 ruled; **D6 names and D7 task config open** |
| ring-chez-adapter fork; paid runs; EMBED downloads | paid runs answered ($50); the other two **open**, one line each |
| `bin/check swarm` | **open** |
| "Lanes become Seon agents" still the direction? | **new**, raised by C-row above |

### THE PLAN, trimmed to current

- A storage: done. B0: done. A2: measured; compiled units deferred (DECISIONS). B: "unrun proof unknown" done; `seon.merge/program` speed, `#inst .SSS`, clocks LEARNING lists, `max-tokens` from measurement → each is or should be a task; RESUME keeps only a pointer.
- C parity gaps → `docs/parity.md` already orders them; THE PLAN names the page, not the list.
- D the task and merge model → explicit merge requests (`:seon.task/carry`), main wins a conflict (`:seon.merge/kept`), divergence map → tasks on `seon.merge`/`seon.template`, with DESIGN-tasks' facts copied into their plans.
- **E is the destination**, with the owner's current focus (ASSESSMENT "Recommended next"): (1) the real context sample and context tuning by evidence (thinking off, first-move scoring); (2) the agent debug view; (3) shrink brief 1.

### Task files (190 tasks, 44 files)

Findings:
- **No task records whether it is done.** The files carry `:plan`, `:proofs`, `:needs` and `:kind` only; landed tasks (`seon.entrance/the-server-answers-mcp-itself`, `seon.context/a-stable-prefix-is-sent-once`, `seon.template/a-request-is-one-linked-transaction`, `seon.check/todays-reds-at-their-causes`, `seon.agent/an-agent-ends-with-its-root`, …) read like open work. Either the store derives done from green proofs (then say so in `tasks.md`) or a done task leaves the file at its merge, its plan kept in git.
- **Superseded tasks still filed:** `seon.reconcile/a-failure-attaches-a-refinement`, `stuck-is-observed-data`, `a-merged-fix-retries-what-it-failed` (C3); `seon.db/stored-attributes-take-the-new-words` (its migration and stuck→blocked, C4/C5); `seon.fences/merge-trains-off-as-decided` (restates a ruling, not work); `seon.config/config-tests-follow-the-shipped-thinking-default` (check whether green).
- **Plans open with provenance, not the problem.** Of 191 plans, most open "Found by session X (2026-10-2x, …)", "Audit FENCES #n, re-read at 26363fe", "Worktree session DUP-ROOT, audit rows …", "Measured 2026-10-21 on the standing root"; 230+ dated stamps across the files. The biggest: `seon.docs/names-are-clojures-words` 8.2 KB, `seon.db/the-store-grows-with-what-it-keeps` 5.6 KB, `seon.program/refs-name-quoted-symbols-and-macro-uses` 5.3 KB, `seon.error/a-repeated-defect-writes-nothing` 5.2 KB, `seon.render/one-printer-and-declared-renders` 5.1 KB.

Trim rule for a task plan (AGENTS §3 already defines the shape):
1. **What exists** (cited vars, at the depth the context needs).
2. **What is wrong**, as a reproduction form and its result (one measurement, with its commit).
3. **The cause** at its owner; **what correct looks like**; **what it replaces and deletes**.
4. **What v1 did** (one line, cited).
5. **Proofs** (in `:proofs`, not prose).

Remove: who found it and when (git blame and the commit message keep it), audit row numbers, "re-read at <sha>" (except the one sha the reproduction ran on), superseded attempts' narratives (one line: "tried X, failed because Y"), owner quotes already in DECISIONS (cite the entry instead). A failed attempt's cause stays (AGENTS §3), as one line.

---

## 5. History (d)

### RESUME State section: all history

Every dated paragraph (land1 … land12, wave 1/2, reset, overnight plan, owner authorized, "Reach reuse is on main", "Step E's first blocker", "A blocked wait can't be interrupted", "Agents outlive their root", runs 6–7) is history once its live fact is in the wiki (§2). Also: "next main: clean", "Worktrees" (lists removed ones), "Branches holding unmerged work" (`jolt/door`, `jolt/runner`, … tagged `archive/*` and deleted in land1, so stale), "Verified facts the plan rests on". The git log and the merge commits already carry each batch's numbers.

Note: `git branch --list 'jolt/*'` in `next/` shows ~50+ branches (land1…land12, batch1/2, many landed topics). Retiring landed branches is a root task (tag `archive/` as land1 did).

### notes files

| File | Class | Destination |
|---|---|---|
| `notes/DECISIONS.md` | (b) | keep, trimmed (§3) |
| `notes/LEARNING-2026-10-22.md` | (a)+(c) | "Escaped" table rows → tasks (most done: history kept, blobs, unrun proof); "Failed in v1"/"Worked" → `merging.md`, `render.md` context section, `tasks.md`; "Recurring" clocks → tasks (`seon.root/one-clock`, `seon.fences/a-stall-…`); then archive |
| `notes/DESIGN-tasks.md` | (c)+(d) | §§0–7 mostly built (tasks-at-once, one agent entity?) → check what remains and copy to `seon.merge`/`seon.template` task plans; §9 D6/D7 → open-decisions line; §11 rows 1–7 done (storage), 8–12 partly → tasks; then archive |
| `notes/PLAN-v2.md`, `PLAN-v3.md`, `PLAN-simple.md`, `PLAN-lanes-as-seon-tasks.md`, `PLAN-audit-what-slid-in.md` | (d) | archive (C9) |
| `notes/PLAN-branches.md` | (c), cited by 2 tasks | its tasks (`seon.root/one-main-resumed`, `seon.check/a-run-takes-a-branch-and-deletes-it`) carry it; archive |
| `notes/TEMPLATES-v1-vs-next.md` | (d) | built by tasks-at-once; archive |
| `notes/NEEDS.md`, `IDEAS-MAP.md`, `REPORT.md`, `jolt-architecture.md`, `datahike-on-jolt.md`, `konserve-on-jolt.md`, `jolt-perf-deep-dive.md`, `seon-flow-sci-seam.md`, `seon-jvm-inventory*.{md,txt}`, `decision-bench.md` | (d) | archive (exploration era, 2026-10-19/20); `parity.md` supersedes NEEDS and IDEAS-MAP |
| `notes/method.md` | (a) | fold into `cost.md` (a "how we measure" section), then archive |
| `notes/AUDIT-2026-10-21-growth.md`, `AUDIT-2026-10-21-laws.md`, `audit-2026-10-21/` | (d), cited by tasks | archive; the tasks citing them keep their own reproduction |
| `notes/AUDIT-2026-10-22-agents.md` | (a)+(d) | the repetition-loop finding (18% turns, 45% tokens) → `render.md` context section; archive |
| `notes/lessons-pending.md` | (c) | apply or confirm each edit landed (it targets `docs/worktrees.md`, which no longer exists), then delete |
| `notes/option3-*.md` (45 files) | (d) | archive. Live citations: F1 (×3, BAGGAGE and flow), W1, R5, R4, R2, PKG, FX1, ECOSYSTEM, D1, D2, BUILD, SCHEMAS. Where a wiki page relies on a number, copy the number into the section; keep the link to the archived path |
| `notes/upstream/` | (c) | live: pending upstream PRs; move `STATUS.md` facts to `forks.md` |
| `notes/agent-work/`, `threads-proofs/`, `*.log`, `*.patch` | (d) | archive |
| `next/notes/ASSESSMENT-2026-10-23.md` | (c) now, (d) by evening | its in-flight table and "Recommended next" become RESUME; its "solid/not good" facts go to the wiki (§2); archive |
| `next/notes/AUDIT-v1-to-next-2026-10-22.md` | (a)+(c) | its §1 status → `parity.md`; §4 task shapes → task files (check which were filed); archive. Remove from RESUME's read order |
| `next/notes/AGENTS-review-2026-10-22.md` | (d) | applied to AGENTS; archive |
| `next/notes/agent-context-research-2026-10-22.md` | (a)+(c) | live: the context is the owner's next focus. Its field findings → `render.md` context section; its proposals → `seon.context` tasks |
| `next/notes/fork-test-merge-design-2026-10-22.md` | (d) | built as prove; `merging.md` states it; archive |
| `next/notes/store-native-2026-10-22.md` | (a) | the measurements → `storage.md`/`source.md`; the deferred decision stays on `seon.program/compiled-units-in-the-store`; archive |
| `next/notes/spawn-regression-…`, `lmdb-open-regression-…` | (a)→(d) | one "Not this" line in `storage.md`; archive |
| `next/notes/duplicates-audit-…`, `shrink-convergence-…`, `shrink-dead-code-…`, `shrink-research-…`, `fences-triage-…` | (c) | the remaining rows are work: each open row a task (brief 1 drivers-leave-src ≈ −1,800 is the big one); archive when filed |
| `next/notes/review-2026-10-22.md` | (d) | findings sent back and fixed; archive |
| `next/notes/corpus-2026-10-22.{md,tar.zst}` | (a) data | keep as the evidence corpus (the printer-eval scoring uses real values); move to a data/evidence location, not notes |
| `next/notes/worktrees/` | (d) | archive |
| `next/BAGGAGE.md` | (a) | keep; its first two sections cite `proto.*` and `../proto/BAGGAGE.md` (gone) and are history: trim to the v1 drops |

---

## 6. Proposed target structure

### RESUME.md (one screen, about 40 lines)

```
# RESUME
Read AGENTS.md first (who you are, the method). Then this. Then docs/README.md.

## Now (2026-10-23)
main <sha> · Jolt <sha> · Chez <sha> · src 17,470 (aim ~10k) · paid $0.144 of $50
Goal: step E — a Seon agent merges a model-made change. Not yet.
Owner's focus: (1) the context, tuned by evidence; (2) the agent debug view; (3) shrink brief 1.

## In flight
| branch / session | task | state | next |
(the 8 rows from ASSESSMENT, one line each, task names not prose)

## Open owner decisions
- D6 names (:seon.task/carry …); D7 task config
- changed-since: tree diff vs stamp (prototype first) → task seon.merge/…
- restore bin/check swarm with a verdict?
- "Lanes become Seon agents": still the direction?
- ring-chez-adapter fork; EMBED model downloads

## THE PLAN
E, and the three steps that block it, each a task name. Done steps leave.
```

No dated State log: each merge's facts go to the wiki section in its commit, and its numbers to the commit message (AGENTS §3, §7 already say so).

### DECISIONS.md

One entry per standing ruling: **bold rule** (owner, date, short quote), then one line naming where it is now stated (AGENTS §n or `docs/page#section`). No mechanism detail (that lives in the section). Superseded entries move to `notes/archive/DECISIONS-superseded.md` with "superseded by <entry>, <date>" on each, or are deleted (git keeps them). Target: under ~150 lines from 610.

### notes/

- `notes/archive/2026-10-19-exploration/` (REPORT, NEEDS, IDEAS-MAP, jolt-*, datahike/konserve-on-jolt, seon-*, decision-bench, option3-* ×45, threads-proofs, agent-work, logs, patches);
- `notes/archive/2026-10-21-22/` (PLAN-*, AUDIT-*, TEMPLATES, DESIGN-tasks once copied, LEARNING once copied, lessons-pending once applied);
- `next/notes/archive/` for the dated `next/notes/*` once their content is filed;
- live in `notes/`: DECISIONS.md, `upstream/` (pending PRs), and at most the current research note in progress.

AGENTS §7 should say: a note is a working paper; when its content is filed (wiki section, task or decision), it moves to `archive/` in the same commit, and nothing in the live docs cites it as current.

### Task plans

Apply §4's trim rule to all 190, starting with the five largest and the superseded ones; remove `:seon.task/kind`; decide and document how a done task is recognized (green proofs in the store, or leaving the file at merge).

### Wiki additions

- `docs/model.md` (new): provider resolution, profiles, no default model, thinking off by default, reasoning_content, prompt caching, spend unknown ≠ 0, the $50 policy pointer.
- `docs/agent.md` or a new `docs/turn.md`: the turn loop, observe, the judge, `:no-progress`, the three test tiers and `turn-test-ticks`.
- `merging.md`: agents' greens shared (after brief3); the merge lessons; git batches vs Seon octopus merges.
- `tasks.md`: hold-for-root (after it merges); how done is recognized.
- `storage.md`: the standing store is test data until there is user data.
- `cost.md`: method.md's measurement rules.
