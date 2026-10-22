# Parity with v1 Seon

next replaces v1 Seon (`~/src/seon`, JVM, read-only): each capability v1 had is present here, in progress, or left behind on purpose with its reason in [BAGGAGE](../BAGGAGE.md). This page is the tracker: a change that closes a gap updates its row in the same commit. Inventory of 2026-10-22 against next `29c3a91`, checked in code unless a row says "doc".

Status words: **present** (the code exists here; not a claim that it passes), **partial**, **missing**, **dropped** (on purpose; BAGGAGE says why), **new** (v1 had none).

## Operating the system

| v1 | next | Status |
|---|---|---|
| `bin/seon start`, `start --head`, `status`, `stop` | `bin/root start`, `next` (the newest built generation), `status`, `stop`; every end is a fact on main (`:seon.root/stopped` with its cause, `:seon.root/previous-end` at the next boot, `:unclean` after a SIGKILL or a crash: `seon.root/stop!`) | present |
| `bin/seon logs` | `runs/root.log`, no command | partial |
| `bin/seon open`, `init`, `reset`, `nuke` | none (one root per checkout; per-checkout ports, `bin/ports`) | missing |
| `bin/seon config apply` (sparse, per branch) | config layers plus `--set`; `seon.root/apply!` re-reads `system.edn` | partial |
| `bin/lane` claims and proven commits | worktrees (`bin/worktree`) and root's merges | dropped |

## The MCP server

| v1 | next | Status |
|---|---|---|
| `eval_clj` (an agent's REPL or root's) | `seon_eval` (one evaluator: no JVM mode) | present |
| `runtime_status` (health, problems, flow, readiness) | `seon_agents` lists agents and tasks; health only on the dashboard | partial |
| an outside agent works a task | `seon_attach`, `seon_context`, `seon_done` | new |
| refusals and defects answered per request | a defect answers that request the typed `:seon.server/defect` and the server goes on (`seon.server/respond`); a refused attach still writes the task's state first (`seon.entrance/attach-writes-nothing-when-it-is-refused`) | partial |

The server's ops are one table, `seon.server/ops`: MCP lists each as the tool `seon_<op>` (`seon.server/mcp-reply`, answered by the server itself on its one port; `bin/mcp` is a stdio relay of a few MB, no Seon binary), so a new op is one row. An eval as root goes through the agents' REPL path (`seon.repl/eval-string`: the one printer, cost, error maps, `*1` and `*e`), in `root.repl` with `sys` a local, recorded on main by `seon.repl/record-tx` like an agent's evaluation. The dashboard's calls of root's functions are the requests the API fns send (`seon.root/request`), so the UI and `seon.root/merge!` cannot disagree on a request or a refusal. Not this: a second read/eval loop, a second recorder, or an `:op`'s request map written again in `seon.web`.

`seon_agents` returns every task (230+, mostly cancelled): too much context for an outside agent.

## The agent's REPL (`my.*`)

| v1 | next | Status |
|---|---|---|
| `my.task/current`, `update!`, `send-back!`; `my.turn/wait` | `current`, `update!`, `fail!` (send-back replaced: a failure is task data, held for root: [tasks](tasks.md#a-failed-task-is-held-for-root)); `my.turn/wait` | present (an agent holds its task from the fork: [tasks](tasks.md#the-agent-knows-its-task)) |
| `my.program/callers`, `source`, `tests-reaching` | `my.agent/callers`, `callees`, `grep`, `source`; `seon.program/reach-digests` | present |
| `my.program/history` | none yet; the store keeps history now ([storage](storage.md#history)), the function is not built | missing |
| `my.program/search`, `similar` (embeddings) | none; decided (DECISIONS, "Embeddings join the context"), not built; the context's `:found` finds helpers by the call graph meanwhile (`seon.context/found`) | missing |
| `my.program/diff`, `sizes`, `breaks` | `seon.root/branch-diff` (root only) | partial |
| `my.program/write-branch!` | write-back at merge (`seon.source`, the `:git` effect) | present, better |
| `my.message/inbox`, `send` (stored facts) | `my.agent/messages`, `send-message`, held in the agent's process | partial |
| `my.test/run`, `check` | proofs run by themselves after each evaluation | partial (no agent function) |
| `my.web/fetch`, `search` | none (no `:web` effect) | missing |
| `my.fs/*` (read, write, list files), `my.edit/*` (exact-span text edits), `my.shell/run!` (a command, its output and exit) | none yet; they go through the effect owner (`my.fx/call`), like every outside-world call. Seon does any work its user wants (AGENTS §1); only Seon's own code changes at the REPL | missing |
| `my.schema/register!`, `my.branch/merge-main!`, `unmerged`, `resolve!` | the same names | present |

## Tasks, merges, tests

| v1 | next | Status |
|---|---|---|
| tasks as data, `:needs`, one reconciler, green at start ends with no model call | `seon.task`, `seon.reconcile` | present |
| `chain!` and `:seon.task/next` | `:needs` only | dropped |
| step templates (design, prove, implement, merge) | `seon.template/request`: design → implement → merge on one branch, one transaction ([tasks](tasks.md#a-request-is-one-transaction)) | present |
| three-way program merge, conflicts as entries, forward merge | `seon.merge`, `my.branch/merge-main!` | present |
| many agents merging onto shared definitions at once (v1's swarm) | `bin/check swarm` (`seon.e2e`'s swarm row: 20 tasks over 5 shared vars, 8 agents at once; red unless conflicts arose and every one was resolved, every task done, and a fresh agent on main gets every proof's value) | present |
| main wins a conflict (MERGE-STEP 5a) | ours is kept | missing |
| one done rule shared with merge | `seon.reconcile/done-refusals` (a proof that never ran counts red, not unknown) | partial |
| tests selected by reach; results reused by reach digest | `seon.proofs/plan` over `seon.program/reach-digests`: `bin/prove`, `bin/test` and `bin/check` reuse greens in the main tree's `test-results/` (check steps keyed too); agents' runs select and reuse by the same plan (`seon.agent/test-plan`) | present; agents' greens are per agent, not shared |
| contracts armed at definition; cost proofs; per-function profile | `seon.contract`, `seon.cost`, `seon.profile` | present |
| turns-to-green report (median, p90) | `seon.learn` charges waste per attempt | partial |
| scheduled work (`seon.schedule`) | none (AGENTS names a cron proc; `system.edn` has none) | missing |

## Context, printing, the page

| v1 | next | Status |
|---|---|---|
| generated task context; one reference rule (`seon.ref`); forgiving reader | `seon.context`, `seon.ref`, `seon.reader/forgive` | present |
| `(help)` checked against the program | [agent](agent.md), the system message; `my.agent/help`; every form on it run in an agent by `seon.repl-test/the-help-page-runs` | present |
| context: stable attempt sections, cached; small turn sections; must-reads capped | `seon.context/messages` (the help, the attempt sections, the scrollback, the turn); vars at their citation's depth, `:seon.config.render/reference-tokens`; cache hits on each turn | present |
| one printer with a budget and runnable continuations; renders on schemas | `seon.print`, `seon.render` | present |
| wiki sections in the context | `seon.docs` | new |
| Datastar pages: `/`, `/agent/{id}` | `seon.web` | present |
| `/ns/{ns}` and its debug pages; `/agent/{id}/debug`; `/data` | none | missing |
| `/agent/{id}/message` (the owner's input bar) | none | missing |

## Storage, config, errors

| v1 | next | Status |
|---|---|---|
| one store, many Datahike branches | `seon.db` (LMDB), `seon.writer` | present |
| history kept | `:seon.config.store/keep-history?` (default true), fixed when a store is made; a kept store made without it keeps its layout ([storage](storage.md#history)) | present |
| storage collection, log rotation (`seon.maintenance`) | root collects at quiescence with no agent live, when a branch is gone ([storage](storage.md#collection)); no log rotation | partial |
| blobs out of line (`seon.blob`) | large values out of line inside Datahike's own store, written once, kept by GC's mark ([storage](storage.md#values-out-of-line)); zstd for every stored value, built from source ([storage](storage.md#compression)); plain values stored as data, not EDN text ([storage](storage.md#plain-values)) | present |
| every config key documented | one aero file, one resolver (`seon.config`) | present |
| one error shape, recorded once per signature, problem report | `seon.contract/error`, `seon.error`, `seon.problems` | present |
| named catch sites | `docs/failure-points.md`, `bin/laws` | new |

## Onboarding and docs

| v1 | next | Status |
|---|---|---|
| `CLAUDE.md` (the laws) | `CLAUDE.md` is `AGENTS.md` | present |
| 13 skills in `.agents/skills` | the task's context and this wiki (DECISIONS: "process, not whim") | dropped |
| architecture pages | 9 topic pages and the [map](README.md); the wiki rewrite and `bin/check docs` are on the unmerged `jolt/wiki`, `jolt/patterns` and `jolt/patterns-wiki-proof` | partial |

## The gaps, in order

1. History: kept since 2026-10-22 (A.2); the `my.program/history` function is not built.
2. Storage: collection beside live agents (the design in [storage](storage.md#collection)); a trained zstd dictionary once a representative corpus exists; log rotation.
3. The server: health through MCP, a compact `seon_agents`; an outside attempt whose agent dies ends (`seon.reconcile/an-outside-attempts-agent-death-ends-it`); the turns, inspection and restore/replay of `archive/jolt/door` (the tasks in `seon.server`).
4. The owner's view: `/ns` pages and the message input bar, with messages stored as facts.
5. Main wins a conflict; an unrun proof is unknown, not red.
6. The wiki's missing concept pages (`seon.docs/every-concept-has-its-page`) and its health measures (`seon.docs/the-wiki-stays-true-and-healthy`).
7. Embeddings and `similar`.
8. A turns-to-green report.
9. Operator commands: `open`, `logs`, `reset`.
10. File and shell functions (`my.fs`, `my.edit`, `my.shell`) through the effect owner.
11. Scheduled work, web fetch.
