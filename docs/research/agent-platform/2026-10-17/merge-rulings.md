---
title: "Owner rulings for the merge and testing redesign"
type: input
status: owner rulings 2026-10-17, input to the merge hybrid (Fable) after merge-audit-opus.md and merge-cost-astra.md
---

# Merge and testing: the owner's rulings (2026-10-17)

**Merge.**
- One simple merge. Seon is not building git. Every component is judged by the work it must do, in reads and writes.
- **The whole chain merges.** There is no test-selected merge list.
- Make the merge branch (main plus the change). Look up existing results by reach digest, and run only the tests whose digest is new on the merged result.
- No conflict and green: merge in one transaction. A conflict goes to the merge agent (`seon.step.merge/template`).
- **A red test on the merged result refuses the merge, even if main had it red before.** Main must be green, and keeping it green is root's job. No "red at the merge base" excuse machinery.

**Testing.**
- **Reactive testing is core.** Writing a definition runs its reaching tests, and results are keyed by reach digest. An unchanged definition never reruns.
- **Agents never need to run tests.** Results arrive in the agent's next context: what is failing, with the failure data to dig into. Agents *can* run tests by hand through one simple verb, but help says that is almost never necessary.
- **The runner is stock clojure.test plus evidence.** Tests run through clojure.test's own run and report maps. Seon adds only two things: results keyed by reach digest, and storing them. `seon.test.runner` is rewritten toward that, and anything the reactive path makes unnecessary is deleted.
- **Tests run in the environment** (the agent's SCI fork on its branch). Only when a change reaches a platform-bound or file-reading test does it run in a separate JVM instance cloned from HEAD plus the changed files. That instance is not booted on every merge.

**Validation.** Every function is instrumented: inputs are validated before entry and outputs on return. A function that depends on `seon.widget/a` cannot run on invalid input. So the merge and done path never re-validates what contracts or the writer guarantee. The merge checks only:
1. conflicts;
2. tests whose digests are new;
3. contract completeness of new definitions, if the write does not already enforce it.

**The agent's context.** It always shows a running summary of what will merge: every function, test, schema and data entity the chain added, changed or deleted since its merge base, each with its test status. The agent uses it to delete scratch data and functions, and to tune its work before done.

**Help.** It says plainly that:
- all functions are instrumented, so data is validated in and out and agents need not validate;
- tests run automatically as things change, and the agent always gets those updates and can see which tests fail and dig into the results;
- agents may run a test by hand, but it is almost never necessary.

**No automatic JVM boots (owner, 2026-10-17, later):** "Can we do this on the JVM inside the JVM for most tests? We might just restrict platform work to JVM agents (root agents). I think automatic testing firing up an entire JVM is a recipe for disaster."
- Automatic testing never boots a JVM. Every reactive and merge test runs inside the one JVM.
- A file-reading test (for example `seon.babashka-load-test/declared-namespaces-load-in-babashka`) reads the candidate's files, written cheaply from the branch to a disposable directory. It may launch the external tool it tests (`bb`), never a JVM.
- Platform work, meaning changes to host-bound declarations SCI cannot interpret, belongs to root, which works in JVM mode and proves it deliberately with `bin/seon check`.
- When a chain's change reaches a host-bound declaration, the done rule says so as data and the chain bails to root. Nothing boots automatically.

**No guards; errors as data (owner, 2026-10-17):** "guards shouldn't be necessary with the system we are building. We check things at the entrance and exit of all functions and we should be explicitly handling anything that can throw an error and returning it as data so the agents can respond."
- Contracts check every function's input and output.
- A failure is produced once, where it is detected, as the one error map, and returned as data to the agent.
- Nothing downstream re-checks shapes, catches defensively or adds a refusal tier.
- The roughly 175 guard sites the Opus audit counted on the merge/done path (`merge-audit-opus.md`) get removed in this redesign, as their own final phase after P11. They are not deferred to a later conversion.

## The task model (owner, 2026-10-17, later): input to `task-model.md`

**What starts work: desired vs observed state.** Desired state is facts written by root, the task writer and config:
- the tasks;
- their dependency edges;
- each task's branch and environment.

Observed state is facts written only by their observer, at the event:
- whether a flow is running for a task;
- an attempt's end;
- test results;
- branch heads;
- that a merge happened.

One pure, idempotent reconciler reads both and does the minimum to resolve the difference. Run twice on the same state, it does nothing the second time. This replaces the chain entity, `run!` as a starter, relaunch, the per-assignment merge path, and every piece of code that keeps two copies of state in sync.

**The task graph is the scheduler.**
- A task is ready when every task it depends on is done and its own proofs are not yet green. Independent ready tasks run in parallel.
- Writing the tasks is the only start signal, and the reconciler reacts to it.
- There are still no automatic re-runs: a task that bails is held for root.

**No chain entity. One task schema.**
- `next` (or a dependency edge) links tasks, and a sequence is written all at once as linked tasks.
- Each task carries its branch and environment (config, model, cap) as desired state.
- When a running agent's environment differs from its task's, the reconciler swaps it at the agent's next idle boundary.

**Merge is an ordinary task, with no merge-specific attribute.**
- Its environment is a merge branch: main's head, plus whatever merged cleanly from its predecessor, plus each conflict resolved to main's version and recorded as an unmerged entry. This reuses the one merge (P4a).
- No conflict and green proofs: it is done at start, with no agent and no model call, and main is written once through the fenced transaction.
- Otherwise the agent fixes it on the merge branch, or uses `my.task/send-back!` to tell root to restart because too much has changed.

**Done and approval.**
- Every task is done automatically when the one done rule passes.
- Approval, when wanted, is another task in the graph. Its done condition is a fact a person writes.

**Conflicts are live in the agent's context.**
- The current-state block has a section, derived each request from the merge's stored unmerged entries. Each entry shows both versions as source references and the form that resolves it.
- A resolved conflict drops out of the block.
- Cost is proportional to the number of conflicts, and the section is cached by the branch's value, so it is computed at most once per turn.
- The "what will merge" summary carries each item's test status.

**Error law, applied as code is touched.** AGENTS.md's error law applies to code a change touches or writes. Code scheduled for deletion is never converted, and untouched code is never swept all at once. A ratchet census checks only functions changed since the law landed.
