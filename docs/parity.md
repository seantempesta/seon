# Parity with v1 Seon

next replaces v1 Seon (`~/src/seon`, JVM, read-only): each capability v1 had is present here, in progress, or left behind on purpose with its reason in [BAGGAGE](../BAGGAGE.md). This page is the tracker: a change that closes a gap updates its row in the same commit. Inventory of 2026-10-22 against next `29c3a91`, checked in code unless a row says "doc".

Status words: **present** (the code exists here; not a claim that it passes), **partial**, **missing**, **dropped** (on purpose; BAGGAGE says why), **new** (v1 had none).

## Operating the system

| v1 | next | Status |
|---|---|---|
| `bin/seon start`, `start --head`, `status`, `stop` | `bin/root start`, `next` (the newest built generation), `status`, `stop` | present |
| `bin/seon logs` | `runs/root.log`, no command | partial |
| `bin/seon open`, `init`, `reset`, `nuke` | none (one root per checkout; per-checkout ports, `bin/ports`) | missing |
| `bin/seon config apply` (sparse, per branch) | config layers plus `--set`; `seon.root/apply!` re-reads `system.edn` | partial |
| `bin/lane` claims and proven commits | worktrees (`bin/worktree`) and root's landings | dropped |

## The MCP door

| v1 | next | Status |
|---|---|---|
| `eval_clj` (an agent's REPL or root's) | `seon_eval` (one evaluator: no JVM mode) | present |
| `runtime_status` (health, problems, flow, readiness) | `seon_agents` lists agents and tasks; health only on the dashboard | partial |
| an outside agent works a task | `seon_attach`, `seon_context`, `seon_done` | new |
| refusals and defects answered per request | on the unmerged branch `jolt/door` | partial |

`seon_agents` returns every task (230+, mostly cancelled): too much context for an outside agent.

## The agent's REPL (`my.*`)

| v1 | next | Status |
|---|---|---|
| `my.task/current`, `update!`, `send-back!`; `my.turn/wait` | the same names | present (`my.task/current` answers nil in released agents: RESUME's plan, A) |
| `my.program/callers`, `source`, `tests-reaching` | `my.agent/callers`, `callees`, `grep`, `source`; `seon.program/reach-digests` | present |
| `my.program/history` | none: the store keeps no history (`seon.db/store-cfg`) | missing |
| `my.program/search`, `similar` (embeddings) | none; decided (DECISIONS, "Embeddings join the context"), not built | missing |
| `my.program/diff`, `sizes`, `breaks` | `seon.root/branch-diff` (root only) | partial |
| `my.program/write-branch!` | write-back at merge (`seon.source`, the `:git` effect) | present, better |
| `my.message/inbox`, `send` (stored facts) | `my.agent/messages`, `send-message`, held in the agent's process | partial |
| `my.test/run`, `check` | proofs run by themselves after each evaluation | partial (no agent verb) |
| `my.web/fetch`, `search` | none (no `:web` effect) | missing |
| `my.edit/*`, `my.fs/*`, `my.shell/run!` | agents work at the REPL only | dropped |
| `my.schema/register!`, `my.branch/merge-main!`, `unmerged`, `resolve!` | the same names | present |

## Tasks, merges, tests

| v1 | next | Status |
|---|---|---|
| tasks as data, `:needs`, one reconciler, green at start ends with no model call | `seon.task`, `seon.reconcile` | present |
| `chain!` and `:seon.task/next` | `:needs` only | dropped |
| step templates (design, prove, implement, merge) | `seon.template` (being redesigned: `../notes/DESIGN-tasks.md`) | present |
| three-way program merge, conflicts as entries, forward merge | `seon.merge`, `my.branch/merge-main!` | present |
| main wins a conflict (MERGE-STEP 5a) | ours is kept | missing |
| one done rule shared with merge | `seon.reconcile/done-refusals` (a proof that never ran counts red, not unknown) | partial |
| tests selected by reach; results reused by reach digest | `seon.program/reach-digests`; `bin/test --reach` reuses green results in `../test-results` | present for `bin/test`; agents' proof runs don't reuse |
| contracts armed at definition; cost proofs; per-function profile | `seon.contract`, `seon.cost`, `seon.profile` | present |
| turns-to-green report (median, p90) | `seon.learn` charges waste per attempt | partial |
| scheduled work (`seon.schedule`) | none (AGENTS names a cron proc; `system.edn` has none) | missing |

## Context, printing, the page

| v1 | next | Status |
|---|---|---|
| generated task context; one reference rule (`seon.ref`); forgiving reader | `seon.context`, `seon.ref`, `seon.reader/forgive` | present |
| `(help)` checked against the program | template instructions only | partial |
| one printer with a budget and runnable continuations; renders on schemas | `seon.print`, `seon.render` | present |
| wiki sections in the context | `seon.docs` | new |
| Datastar pages: `/`, `/agent/{id}` | `seon.web` | present |
| `/ns/{ns}` and its debug pages; `/agent/{id}/debug`; `/data` | none | missing |
| `/agent/{id}/message` (the owner's input bar) | none | missing |

## Storage, config, errors

| v1 | next | Status |
|---|---|---|
| one store, many Datahike branches | `seon.db` (LMDB), `seon.writer` | present |
| history kept | `:keep-history? false` | missing |
| storage collection, log rotation (`seon.maintenance`) | none; `datahike.gc/gc-storage!` runs on Jolt with a `java.util.Date` | missing |
| blobs out of line (`seon.blob`) | in progress: LZ4 node compression through liblz4, and large values out of line inside Datahike's own store | missing |
| every dial documented | one aero file, one resolver (`seon.config`) | present |
| one error shape, recorded once per signature, problem report | `seon.contract/error`, `seon.error`, `seon.problems` | present |
| named catch sites | `docs/DRAGONS.md`, `bin/dragons` | new |

## Onboarding and docs

| v1 | next | Status |
|---|---|---|
| `CLAUDE.md` (the laws) | `CLAUDE.md` is `AGENTS.md` | present |
| 13 skills in `.agents/skills` | the task's context and this wiki (DECISIONS: "process, not whim") | dropped |
| architecture pages | 9 topic pages and the [map](README.md); the wiki rewrite and `bin/check docs` are on the unmerged `jolt/wiki`, `jolt/patterns` and `jolt/patterns-wiki-proof` | partial |

## The gaps, in order

1. History: `:keep-history? false` (RESUME's plan, A).
2. Large values and storage reclaim: compression, values out of line, GC (RESUME's plan, A).
3. The door: health through MCP, a compact `seon_agents`, and `jolt/door`'s refusals as data.
4. The owner's view: `/ns` pages and the message input bar, with messages stored as facts.
5. Main wins a conflict; an unrun proof is unknown, not red.
6. The unmerged wiki work and `bin/check docs`.
7. Embeddings and `similar`.
8. A turns-to-green report.
9. Operator commands: `open`, `logs`, `reset`.
10. Scheduled work, web fetch.
