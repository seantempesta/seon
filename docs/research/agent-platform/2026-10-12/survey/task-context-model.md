---
title: "Survey task-context-model: how a task becomes model context at HEAD"
type: research
status: complete
basis: "HEAD b4814acf5; default counts from b14dc2b3f (behind HEAD, JVM mode, root, read-only)"
method: "rg over src test script bin and resources/seon/schemas and resources/seon/tasks; direct reads of seon.context, seon.ref, seon.task, my.task, seon.render.ns; git log -S on each attribute and function; one jvm-mode eval_clj on default counting :seon.task/* attributes over entities with :seon.task/sym (809 entities). Round2 capture 2-contract-after.md read for the agent-visible shape."
---

# Survey task-context-model

## The owner's model (verbatim)

> "context generation takes what's in the task and looks up all the refs, docs and schema symbols mentioned and pulls in that context. It does not pull in every test related to every symbol."
>
> "We are showing the tests that are required for the task and any symbols that resolve to tests pull in the test just like anything else."
>
> "It's all the same mechanism and we need to make sure that ALL ATTRIBUTES on the task get parsed and pulled in relevant context. ... We don't need to search around if we write up a good task and then the context (most of it) is there to start. As the task evolves ... better context is pulled in."
>
> "Agents are supposed to be updating the task as things get more clear too."
>
> "Every field should be getting parsed and pulling in references so we don't need duplicates."

## For root

Ranked divergences from the owner's model (HEAD b4814acf5):

1. **Authored duplication is required by the docs.** `:seon.task/intent` backticks and `:seon.task/must-read` name the same symbols; `AGENTS.md` "Write tasks as data" and `docs/seon/schedule/cards/TASK-CHAIN.md` require both. The model receives the names twice: raw in `:seon.context/task` (`:seon.task/intent`, `:seon.task/must-read`, `:seon.task/evidence` as pulled) and resolved in `:seon.context/must-reads`. `ref/references` dedupes resolution (`seon.ref/references`, `identity-of`), so the resolved list is single, but the raw copies are still shown. Root measured 949 of 1,915 must-read symbols already backticked in the intent. Evidence: 754 of 809 default tasks carry must-read, 571 carry evidence.
2. **No agent verb refines a held task.** `src/my/task.clj` defines only `my.task/current` and `my.task/send-back!`. No `my.task/refine!` exists (grep for `refine` in src and test finds none for tasks). `docs/seon/schedule/cards/TASK-CHAIN.md` and `docs/research/agent-platform/2026-10-11/runtime-follows-db.md` §2 name `refine!`, and `AGENTS.md` "Derive work and completion" lists it as an agent task verb. The only write path is `seon.task/create!` with `:seon.task/basis` (`seon.task/create!` docstring), which an agent can call but which is not a my.* verb and is unverified for upsert of its held task. Tasks cannot improve as work clarifies them.
3. **A read path exists for an attribute no code writes.** `:seon.task/instructions` is in `resources/seon/schemas/seon.task.edn` and read by `seon.context/task-selector` and `seon.context/task-references`, added in ea27cde53 (2026-10-12, "chain experiment 2 G5"). No src path writes it: not the file grammar (`seon.task/file-declarations` entry is a closed map without it), not `seon.task/create!`. Only test/my/task_test.clj and test/seon/task_test.clj transact it. Default: 0 of 809 tasks carry it. Its schema was added in 838d80e54 (Card S1) with children, needs, execution.
4. **Successors see a predecessor only as its intent.** A `:seon.task/needs` target resolves in `seon.ref/resolve-name` to `{:seon.ref/name … :seon.task/intent …}` only: not its must-reads, evidence, refined declaration, end or proof counts. The docs (TASK-CHAIN handoff; runtime-follows-db §2 "its intent, must-read and evidence are the handoff medium") expect the handoff to carry the refined subject. Default: 17 tasks carry needs, 0 carry children. Partial: the Prove product (`seon.task/obligations`) is how a successor's tests reach its predecessor's step.
5. **Evidence is a second pointer channel that shows less.** Evidence paths resolve through `seon.ref/resolve-path` to a heading plus an operation `(my.fs/section path)`; the body is not pulled (`dissoc :seon.ref/text`). The same task can name a document in intent, must-read-adjacent prose, and evidence. Default: 571 tasks carry evidence; root measured 19 of 737 evidence paths named in the intent. Evidence is pulled as data (a set of paths) in `:seon.context/task`, so it appears raw too (see 1).
6. **Tasks are projected three ways with no common selection.** `seon.context/task-selector` shows id, sym, intent, instructions, must-read, evidence; `seon.render.ns/task-ai` shows id, sym, name, kind, ns, intent, must-read (distance-based); `seon.render.ns/held-task-ai` shows name and intent only; `my.task/current` returns the held declaration from `seon.context/held-task` (the task-selector pull). Kind, name, step and ns are absent from the model's block except through the step section and the namespace. "Every field" is not parsed uniformly across faces.
7. **Reaching tests and callers are operations, not content.** Resolving a function name yields `doc`, `arglists`, `malli/schema` plus operations `(my.program/source …)`, `(my.program/callers …)`, `(my.program/tests-reaching …)` (`seon.ref/resolve-name`, added 7fc0ebb2a 2026-10-09). The agent must run them. This matches "not every test" and is consistent with the owner, but the reaching tests named by a must-read are never pulled as content. Proofs are pulled in full with source (`seon.context/proofs`), which matches the owner's rule for required tests.
8. **Unverified: proof names appear twice.** b4814acf5 ("the block no longer drops references to the task's own proofs") and 7b6163a07 ("Must-reads list the task's own proofs and count the rest") suggest a proof named in intent or must-read appears both in must-reads (with its source) and in the proofs section (with its source). I did not confirm this on a capture.

**Already filed:** no task found in resources/seon/tasks for refine, instructions or must-read/intent duplication (grep of resources/seon/tasks for refine, instructions, must-read; only unrelated must-read lines).

**Not filed:** the dead `:seon.task/instructions` read path (item 3); the missing `my.task/refine!` (item 2); the duplicated raw/resolved context (item 1); the successor handoff gap (item 4).

## Table

Attribute counts are default (b14dc2b3f) entities with `:seon.task/sym` = 809 tasks. Writers and readers are symbols; "reads" means the attribute is pulled or resolved in the model's path unless noted.

### Content (what the agent reads)

| Attribute | Declared (schema) | Default count | Written by | Read by | Owner's model | HEAD |
|---|---|---|---|---|---|---|
| `:seon.task/intent` | `seon.task/entry`, `seon.task/task` (1de64b45b) | 809 | `seon.task/file-declarations` (ingestion); `seon.task/create!` | `seon.context/task-selector`; `seon.context/task-references` (text spans → `seon.ref/references`); `seon.ref/resolve-name` (task → intent) | must parse | partial: parsed, and also shown raw |
| `:seon.task/must-read` | entry, task (1de64b45b) | 754 | file-declarations; create! | task-selector; task-references (names, via `seon.ref/references`); `seon.task/task-edges` referrers (no) | must parse | partial: parsed, and also shown raw |
| `:seon.task/evidence` | entry, task (1de64b45b) | 571 | file-declarations; create! | task-selector; task-references (paths → heading + `my.fs/section` op) | must parse and pull | partial: heading and op only |
| `:seon.task/instructions` | task, context (schema 838d80e54, Card S1) | 0 | none in src (tests only) | task-selector; task-references | must parse if present | read path with no writer (ea27cde53) |
| `:seon.task/needs` | entry, task (838d80e54) | 17 | file-declarations (lookup refs); create! | `seon.task/ready-rules`, `seon.task/waits-for`; task-references (as names, intent only) | must parse, shown as predecessor work | partial: intent only |
| `:seon.task/children` | entry, task (838d80e54) | 0 | file-declarations; create! | `seon.task/ready-rules`, `seon.task/done?` | runtime tree | not in the model's block |
| `:seon.task/step` | entry, task (1de64b45b) | 0 (absence = implement) | file-declarations; create! | `seon.task/obligations`, `seon.context/step-status`, `seon.context/step-section` | shown as step | matches (through step section) |

### Identity

| Attribute | Declared | Default count | Written by | Read by | Owner's model | HEAD |
|---|---|---|---|---|---|---|
| `:seon.task/id` | `seon.task/handle`, identity (1de64b45b / d7139e8e5) | 809 | `seon.task/task-handle` (derived) | task-selector (dissoc'd in `current-state-ai`); many | not content | matches |
| `:seon.task/sym` | `seon.task/sym`, unique (1de64b45b) | 809 | `seon.task/task-sym` (derived) | task-selector; `seon.context/proofs` (task-edges) | content (name) | matches (shown as sym) |
| `:seon.task/ns` | `seon.task/ns` | 809 | `seon.task/file-declarations` (derived from file) | render.ns task-ai; agent-namespace | namespace | matches (namespace, not in task-selector) |
| `:seon.task/name` | `seon.task/name` | 809 | file-declarations; create! | render.ns task-ai, held-task-ai | content | not in model block (only in renders) |
| `:seon.task/kind` | `seon.task/kind`, required (1de64b45b) | 809 | file-declarations; create! | render.ns task-ai | content (kind of subject) | not in model block |

### Lifecycle and assignment

| Attribute | Declared | Default count | Written by | Read by | Owner's model | HEAD |
|---|---|---|---|---|---|---|
| `:seon.task/execution` | task (838d80e54) | 809 | `seon.task/held-tx` (on ingestion), create! | `seon.task/ready` | scheduling | matches |
| `:seon.task/assignment`, `:seon.task/assigned`, `:seon.task/agent`, `:seon.task/branch`, `:seon.task/attempt-of`, `:seon.task/merge-base`, `:seon.task/outcome`, `:seon.task/end` | assignment-row, end, outcome (assignment) | 182 usages in src (not a per-task count) | `seon.task/assign!`, `seon.task/attempt-tx`, `seon.merge` | `seon.task/agent-task`, `seon.context/held-task`, `seon.task/ready` | lifecycle | matches (not content) |
| `:seon.task/created-by` | task | 1 | `seon.task/create!` in runtime | file reconciliation | none | matches |
| `:seon.task/faults` | task | 0 | `seon.error` path | resolution | none | n/a |
| `:seon.task/cron`, `:seon.task/zone`, `:seon.task/function` | task (838d80e54) | 0 | none in src beyond tests | clock proc | scheduling | unused on default |

### Scheduling and proofs

| Attribute | Declared | Default count | Written by | Read by | Owner's model | HEAD |
|---|---|---|---|---|---|---|
| `:seon.task/tasks` (on deftests and definitions) | `:seon/tasks` on definitions, `seon.task/tasks` (index) | 272 definitions | `seon.task/index-tasks!` from `:seon/tasks` annotations | `seon.task/task-edges`, `seon.task/obligations`, `seon.context/proofs` | "the tests required for the task" | matches: proofs pulled with source |

## Notes

- Counts from default are as of b14dc2b3f (behind HEAD). rg counts in the table headers are all occurrences across src, test, script and bin, not one per task, and include tests; they are not used for any conclusion here.
- The 949 of 1,915 and 19 of 737 figures are root's measurements; I did not reproduce them.
- The context path traced: `seon.context/model-context` calls `exchanges` (agent's own evaluations), `current-state` (which calls `held-task`, `proofs`, `step-verdict`, `send-backs`, `unread`, `branch-cost`, `agent-namespace`, `task-references`), then `current-state-ai` is applied by the render of the block (`seon.render`, not traced line-by-line). `seon.context/task-references` is the one resolver call; it covers intent text, instructions text, must-read names, needs names and evidence paths.
- Verified in the round2 capture 2-contract-after.md: the block shows `:task` with sym, must-read set, evidence set and intent; `:proofs` with status and full test source; `:step`; `:must-reads` with arglists and operations. The raw must-read and evidence duplicate the resolved entries as expected.
- Unverified: whether an agent's `seon.task/create!` with a basis upserts its own held task on its branch; whether a successor's branch forks from its predecessor's attempt commit (`:seon.task.attempt/commit` is in the schema, the fork path was not traced); how refinements reach main (merge path not traced end to end); item 8 (proof duplication).
- Surprise: `seon.task/create!`'s docstring and `docs/research/agent-platform/2026-10-11/runtime-follows-db.md` describe refinement as create!'s upsert with basis; the code has the writer, but no my.* verb.
