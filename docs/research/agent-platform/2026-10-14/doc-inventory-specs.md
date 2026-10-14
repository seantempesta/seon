---
type: research
status: inventory
created: 2026-10-14
tags: [research, docs, inventory]
---
# Doc inventory: docs/seon/specs/ against TASK-CHAIN (2026-10-14)

Lane inv-specs. Read-only inventory; no spec edited. Authority: `docs/seon/schedule/cards/TASK-CHAIN.md` (READY), `docs/seon/plan.md` §0. Installed claims checked by rg against `src/`, `resources/`.

## Totals

- Files in scope: 5 (plus `task-steps.md`, already a pointer, skipped).
- live and correct: 0
- needs edits: 4 (agent-loop.md, errors.md, merge.md, render-dedup.md)
- done historical record: 1 (run.md)
- superseded whole, delete or pointer: 0 (task-steps.md already a pointer)
- stale rows total: about 41 (agent-loop 22, merge 13, errors 6)

Labels: **stale-target** = describes the TASK-CHAIN target wrongly; **installed-until-S<n>** = accurately describes installed code that TASK-CHAIN changes at that slice.

## Per file

### agent-loop.md (needs edits; owns the REPL path, which TASK-CHAIN does not replace)

| # | Anchor | Claim (quoted) | Verdict | TASK-CHAIN says |
|---|---|---|---|---|
| 1 | Header | "the ledger, the only slice list" | stale-target | plan §0 and §7 (ledger superseded 8de13da14) |
| 2 | §4 Order of work | "[The ledger]... Every slice" | stale-target | §7 slices, plan §0 |
| 3 | §1 law 7 | "A task needs proofs (deftests naming it in `:seon/tasks`)" | stale-target (tags) / installed-until-S2c | §1 proofs field; §6 tags deleted |
| 4 | §1 law 7 | "assignment refuses a task with no proofs" | installed-until-S3a | §2.2 attempt start; §1 attempt |
| 5 | §1 law 7 | "A code task requests a merge when its proofs are green" | stale-target | §2.4 agent proposes `:done`; §2.6 one merge at chain end |
| 6 | §1 law 7 | answer task via `my.agent/answer!` | silent | TASK-CHAIN §8 "None open"; root ruling needed |
| 7 | §2 budgets table | "model calls per assignment grant" / `:capped` on assignment | stale-target | §2.7 `:capped` → problem task on main, attempt `:closed`, no relaunch |
| 8 | §3.2 entrant table | "an assigned model agent … the assignment's `:seon.task/branch`" | stale-target | §2.2 new agent per attempt on chain branch |
| 9 | §3.2 "What each entrant receives" | "An entrant with a task holds an assignment (§3.11)" | stale-target | §1 attempt, never on main |
| 10 | §3.2 taskless end | `lifecycle-facts`, "that no assignment names" | stale-target (wording) | taskless agents outside TASK-CHAIN; keep, reword "assignment" |
| 11 | §3.4 Task | "intent and the names it references"; "the update advances the assignment's revision" | stale-target | §1 `plan` (renamed from intent, installed until S1a); §3a `my.task/update!` |
| 12 | §3.4 Must-reads | "backticked in its intent" | installed-until-S1a | §1 `plan`, §3 references |
| 13 | §3.4 References | "(TASK-CHECK)" and "never a refusal" | stale-target | §3 (references; unresolved = problem); TASK-CHECK is superseded |
| 14 | §3.4 Turns | "`:done` once every proof is green" | stale-target | §2.4 done rule (`done-refusals`, not proofs alone) |
| 15 | §3.4 History | "history starts at the current assignment (ledger CX-13)" | stale-target | §1 attempt; ledger → plan §0 |
| 16 | §3.5 table | "`:done` … a code task requests a merge (merge.md §1)" (F5 target) | stale-target | §2.4, §2.6 |
| 17 | §3.5 table | "call cap reached … the assignment moves to the assigner … (ledger RT-13)" | installed-until-S3c | §2.7 problem task on main; §2.7 re-run by `my.root/run!` |
| 18 | §3.5 table | "`(my.task/complete! {:seon.task/reason …})` … the only use" | stale-target | §2.7 and §3a: `my.task/send-back!`; `my.task/complete!` is not installed (0 hits in src) |
| 19 | §3.5 tests | "first the task's proofs … then the tests reaching the eval request's change" | stale-target (order) | §2.3: branch-delta tests first, then the task's own proofs last |
| 20 | §3.5 cap | "the held assignment's grant" | stale-target | §1 attempt; §2.2 |
| 21 | §3.10 Experiments | "its task is its assignment"; "Root is whoever holds the merge request" | stale-target | §2.6 root merges the chain branch once |
| 22 | §3.11 Held tasks | "The agent's assignments are components of it (`:seon.agent/assignments`)" | installed-until-S3a | §1 attempt on the chain branch; §2.2 agent per attempt |
| 23 | §3.11 Answered basis | "[TARGET] `:seon.agent/answered-t` (not yet declared)" | verify | TASK-CHAIN silent; `answered-t` has 8 hits in src, so the TARGET tag may be stale; not concluded |
| 24 | §5 reset manifest | "dump runtime tasks …, assignments" | stale-target (minor) | §1 attempt; chain tasks are runtime (§1) |
| 25 | §6 Owner decisions | "Questions still open for the owner: the ledger's §6" | stale-target | TASK-CHAIN §8 "None open"; plan §0 |

Not stale: §1 laws 1-6, 8-10; §3.1 eval request; §3.3 reading and evaluation; §3.4 static prefix and block; §3.6 failure; §3.7-3.8; §3.5 test step bound (`:seon.config.test/step?` and `step-time-limit-ms` are installed). Dated redesign references (`redo/`, `2026-10-08/`) are cited history and stay.

### errors.md (needs edits; owns error shapes, which TASK-CHAIN does not replace)

| # | Anchor | Claim (quoted) | Verdict | TASK-CHAIN says |
|---|---|---|---|---|
| 1 | Header paragraph | "one question task, assigned to the producing function's steward if one is held, else to root (the assignment is the one notification … ledger RT-12)" | stale-target | §2.7: root writes a problem task on main, identity derived from the terminal attempt; no assignment. Steward routing is silent (78 hits in src: installed); root ruling needed |
| 2 | §6 Recording | "recorded once per signature at the task home (`seon.task/home` …)" | stale-target (name) | §2.7 puts problem tasks on main; `seon.task/home` has no defn (only `seon.cluster.host/home-call`) |
| 3 | §6 Opening | "`seon.task/generate-tx`, ledger RT-7, RT-8, RT-12) assigned to the function's steward" | stale-target | §6: generator survives as `seon.contracts/task`, writes `proofs`; `generate-tx` not installed (0 hits) |
| 4 | §6 Resolution | "every success test naming its task … Use `seon.task/task-edges` for the proofs" | installed-until-S2c | §1 proofs field; §6 deletes `seon.task/task-edges` |
| 5 | §6 Resolution | "A task with no proofs keeps its error open" | stale-target (wording) | §1 `:seon.task/proofs` field, absent means empty |
| 6 | §6 Resolution | "`seon.task/task-resolution` reads this through `seon.error/resolution`" | verify | TASK-CHAIN silent; `task-resolution` has no defn in src |
| 7 | Decisions item 5 | "no task until an agent or root files one … the task's success tests have a green run since" | stale-target | §1/§6 proofs field, tags deleted |

Not stale: the error map, constructors, boundary, cause chain, delivery, storage roles, and the cut's numbered decisions 1-4 and 6-8.

### merge.md (needs edits; header conflicts with the body, and the body describes installed merge mechanics that TASK-CHAIN does not replace)

| # | Anchor | Claim (quoted) | Verdict | TASK-CHAIN says |
|---|---|---|---|---|
| 1 | Header | "superseded by docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md" | conflict (root ruling) | file exists; TASK-CHAIN §2.6 owns the chain's single merge; merge mechanics (§2-§5) still owned here. Header should say which parts are superseded |
| 2 | Opening (§0 words paragraph) | "Root is whoever holds a merge request … an ordinary task done through the ordinary REPL" | stale-target | §2.6: root's listener merges the chain branch once, at its last task |
| 3 | Header paragraph | "[the ledger] §3.10 … §3.16 … §3.15" | stale-target | plan §0, TASK-CHAIN §7 |
| 4 | §1 What root holds | "A task is a merge request because it carries `:seon.merge/source-branch`" | stale-target | §2.6: source is the chain branch and terminal attempt; no such key in src |
| 5 | §1 Forms table and "Today" column | `my.program/request-merge!` program.clj:416, `checkout!`, `abort-merge!`, `close-merge-request!`, `my.program/merge!` | needs edits (false current claim) | none installed (0 hits); installed: `my.root/merge!` (root.clj:94), `my.root/merge-requests` (root.clj:61), `my.root/close!` (root.clj:117), `my.program/diff` |
| 6 | §1 Updating a request | "The agent requests a merge when its proofs are green … The agent goes to `:wait` when it requests" | stale-target | §2.4 agent proposes `:done`; §2.6 no request |
| 7 | §3 Keep only new behavior | "A task's proof is preferably an existing test tagged with the task" | installed-until-S2c | §1 proofs field; §6 tags deleted |
| 8 | §4.5 Merge commit | "The source branch is deleted once its task is done or given back, its merge request is closed" | stale-target | §2.6 branch and base retired after publication, then chain fact retracted |
| 9 | §4.6 Publication | "`seon.merge/publish!` writes a merged task … into `resources/seon/tasks/<ns>.edn`" | stale-target (chain tasks) | §1: chain tasks are runtime and never written to task files; file tasks unchanged |
| 10 | §5 Automatic merging | "Root is an agent with a merge task"; "assigned to root when it opened (ledger RT-9, RT-11)"; `:seon.config.merge/auto-merge` | stale-target; dial not installed (0 hits) | §2.6 root's listener; no merge task; §8 silent on auto-merge: root ruling |
| 11 | §7 Exists | "(file:line at `81020da6a`, today's names)" | needs edits (convention) | CLAUDE.md: no file:line in living docs; move to dated evidence |
| 12 | §7 Slices and §8 Costs | "Rows of [the ledger] §3.10 …"; measured timings table | stale-target (ledger); needs edits (measurements in a spec) | plan §0; agent-loop §2 says measurements live in dated evidence |
| 13 | §9 Dogfood record | 2026-10-08 session narrative | done historical record (move) | move to dated evidence under docs/research |

Not stale: the diff value (§2), resolving on the merge branch (§3), norms (§4 norms paragraph), conflict cases (§6). TASK-CHAIN is silent on these, so they stay owned here.

### render-dedup.md (needs edits; not lifecycle)

| # | Anchor | Claim (quoted) | Verdict | TASK-CHAIN says |
|---|---|---|---|---|
| 1 | Header | `status: draft`, dated 2026-09-28 | needs edits | none; decide draft or superseded at the owner's pass |
| 2 | Decided items, item 6 | "`render-call` (render.clj:1496)" | needs edits (stale file:line) | none; `render-call` is at render.clj:1508 now; cite the symbol |

The word "ledger" in lines about debug and transcript views is the web ledger panel, not the schedule ledger. No action. Line 208 cites `resources/seon/tasks/seon.mcp.edn:9`; it was not verified.

### run.md (done historical record)

Header already says superseded by agent-loop.md (2026-10-08) and calls the body dated evidence. It contains no lifecycle claims. Its file:line citations throughout are dated evidence. Option: move the body to docs/research/agent-platform/2026-09-28 and leave a pointer, or keep as it is.

## Root rulings needed

1. merge.md header: name which merge sections TASK-CHAIN §2.6 supersedes and which stay owned here (the header now cites a 2026-10-12 doc).
2. Steward routing for error problem tasks (errors.md header, §6): keep, or fold into TASK-CHAIN §2.7 problem tasks.
3. Question tasks (`my.agent/answer!`, agent-loop §1 law 7): TASK-CHAIN is silent.
4. Taskless agent lifecycle (agent-loop §3.2): TASK-CHAIN is silent.
5. Auto-merge dial (merge.md §5): not installed; keep, drop, or defer.
6. `:seon.agent/answered-t` status (agent-loop §3.11): installed in src (8 hits) but tagged TARGET; verify.
7. CLAUDE.md "Merge is a root task … the merge request is the assignment" conflicts with TASK-CHAIN §2.6 (outside this lane; flagged).

## Suggested edit order

1. agent-loop.md header, §3.5 table, §3.4 Task and References, §3.11: the most traffic.
2. merge.md §1 forms table (false current claims) and §1, §4.5, §4.6 (chain merge).
3. errors.md header and §6 task routing.
4. merge.md §7-§9 to dated evidence; render-dedup.md file:line.
