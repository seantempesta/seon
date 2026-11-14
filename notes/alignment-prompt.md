From the owner, to every session (context, web, runtime, tests, deps, data):

We now work the Seon way. Read AGENTS.md §0 and docs/plan.md on jolt/root (`git show jolt/root:AGENTS.md`), then act on this.

1. **The work lives in the system.** Every piece of work is a task with a plan and proofs, in its owning namespace. A design goes in the task's plan. What you learn about how the system works goes into its wiki section or docstring. A blocker is a task named in the blocked task's `:seon.task/needs`. No plans/ files, no notes folders, no board rows, no facts that live only in your context or memory: before you end a turn, anything you'd lose to a compaction is in a task, a wiki section or a docstring on your branch.

2. **What we keep reaches git.** A change you mean to keep is on a branch that will merge, with its file, before your turn ends. A task you create with `seon_create` also goes into its namespace's file by hand until data's sync lands.

3. **The priority is the path:** a Claude session's edit becomes a transaction on its agent's branch, is proved in the system, merges by `seon_done`, and git follows. Its tasks are in the store (`seon_agents` lists them; acceptance test: `seon.server/a-sessions-file-edit-merges-through-done`). Only those tasks land through a worktree and `bin/land`; nothing new is built for sessions outside the system. When the acceptance test is green, you work through your Seon agent.

4. **Coordinate directly with whoever shares your work.** Message the other owner to agree an interface (6 lines or fewer, evidence and one ask, no acknowledgements), and write what you agreed into the task's plan, naming one owner per function. Root gives releases and brings the owner his decisions; you don't route through root to agree with each other.

| Shared work | Who |
|---|---|
| Test child starts on its branch's environment (`hold-env!`); one deadline helper for the gate and the stop | runtime, tests |
| A session's files as its branch's transaction; one three-way merge (carry-tx out); merge applies every change; retain! keeps branch bases | runtime, data |
| `environment.edn`; compiled code reused by digest (merge cost); the sweep and compaction | runtime, deps |
| Verdicts as entities (schema); docs proved by their readers; admission that weighs a test booting a root as a whole system | tests, data |
| A task created in the store reaches its file; the task-spec render (specification is the first proof) | data, context |
| Recent-errors render; turns on the dashboard; the hand-list problem class; the MCP relay answering each call | context, web |

5. **Never block, never flood.** Anything that takes minutes runs in the background. A test run over a few minutes is a regression. No run makes more processes than the system's admission allows. Kill only pids you verified, after saving evidence.

6. **Say what you know.** Every claim is [M] with its evidence or [H] with what would settle it. When you find you were wrong, say so in the same message.

Reply to root in one line: what you're taking first, and who you've agreed with.
