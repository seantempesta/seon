# Parity with v1 Seon

next replaces v1 Seon (`~/src/seon`, JVM, read-only): each capability v1 had is present here, in progress, or left behind on purpose with its reason in [BAGGAGE](../BAGGAGE.md). This page is the tracker: a change that closes a gap updates its entry in the same commit.

Status words: **present** (the code exists; not a claim that it passes), **partial**, **missing**, **dropped** (BAGGAGE says why), **new** (v1 had none). Each entry reads v1 → next: status.

## Operating the system

Each v1 way of starting, stopping and watching the system.

- ``bin/seon start``, ``status``, ``stop`` → `bin/system start`, `next`, `status`, `stop`; every end is a fact on main (`:seon.system/stopped`, `:seon.system/previous-end`, `:unclean`: `seon.system/stop!`): present.
- ``bin/seon logs`` → `runs/root.log`, no command: partial.
- ``bin/seon open``, ``init``, ``reset``, ``nuke`` → none (one root per checkout, ports by `bin/ports`): missing.
- ``bin/seon config apply`` → config layers plus `--set`; `seon.system/apply!` re-reads `resources/seon/system.edn`: partial.
- ``bin/worker`` → worktrees (`bin/worktree`) and root's merges: dropped.

## The MCP server

Each v1 MCP tool.

- ``eval_clj`` → `seon_eval` (no JVM mode): present.
- ``runtime_status`` → `seon_agents` lists live agents and running or judged tasks (`seon.server/live-tasks`); health only on the dashboard: partial.
- an outside agent works a task → `seon_attach`, `seon_context`, `seon_done`: new.
- defects answered per request → a defect answers that request `:seon.server/defect` and the server goes on (`seon.server/respond`): partial.

The ops and tools: [server](server.md#ops).

## The agent's REPL (`my.*`)

Each v1 function of the agent's REPL.

- `my.task/current`, `update!`, `send-back!`; `my.turn/wait` → `current`, `update!`, `fail!` (a failure is task data held for root: [tasks](tasks.md#a-refused-run-is-held-for-root)), `my.turn/wait`: present ([tasks](tasks.md#the-agent-knows-its-task)).
- `my.program/callers`, `source`, `tests-reaching` → `my.agent/callers`, `callees`, `grep`, `source-fn`; `my.agent/prove` runs the tests a change reaches (`seon.program/graph-index`): present.
- `my.program/history` → not built; the store keeps history ([storage](storage.md#history)): missing.
- `my.program/search`, `similar` → `my.agent/similar`, `seon.vector/near`, in process ([vectors](vector.md)): present, better.
- `my.program/diff`, `sizes`, `breaks` → `seon.system/branch-diff` (root only): partial.
- `my.program/write-branch!` → write-back at merge (`seon.source`, the `:git` effect): present, better.
- `my.message/inbox`, `send` → `my.agent/messages`, `send-message`; a message is one entity on main, delivered onto the recipient's branch ([users](users.md#messages)): partial.
- ``my.test/run``, ``check`` → proofs run after each eval; `my.agent/prove` at the REPL ([turn](turn.md#who-runs-which-tests)): present.
- `my.program/unmap-subject!` (refuses while a referrer remains) → plain `ns-unmap`, recorded on the branch (`seon.repl/unmapped`); a referrer fails where it loads: partial.
- `my.web/fetch`, `search` → none (no `:web` effect): missing.
- `my.fs/*`, `my.edit/*`, `my.shell/run!` and their policy → none; outside-world calls go through the effect owner (`my.fx/call`): missing.
- `my.schema/register!`, `my.branch/merge-main!`, `unmerged`, `resolve!` → the same names: present.
- an effect's receipt committed before its handler runs (`seon.effect`) → only the outcome, in the answer of the eval that asked: missing.

## Tasks, merges, tests

Each v1 capability for tasks, merges and tests.

- tasks as data, `:needs`, one reconciler → `seon.task`, `seon.reconcile`: present.
- ``chain!``, ``:seon.task/next`` → `:needs` only: dropped.
- step templates → `seon.template/request` by default; `seon.template/design-build` and `seon.template/stages` for experiments ([tasks](tasks.md#a-request-is-one-transaction)): present.
- three-way program merge, conflicts as entries → `seon.merge`, `my.branch/merge-main!`: present.
- many agents merging onto shared definitions (v1's swarm) → `seon.reconcile-test/concurrent-merges-into-main-converge-whatever-the-order`, n tasks over 5 shared vars: present.
- main wins a conflict → ours is kept: missing.
- a changed function no test reaches refuses the branch → `seon.reconcile/done-explain` has no coverage reason: missing.
- one done rule shared with merge → `seon.reconcile/done-explain` ([tasks](tasks.md#the-done-rule)): present.
- tests selected by reach, reused by digest → `seon.proofs/plan` over `seon.program/graph-index`, for `bin/prove`, `bin/test`, `bin/check` and agents (`seon.agent/test-plan`) ([merging](merging.md#verdicts-are-a-cache)): present.
- contracts armed at definition; cost proofs; profile → `seon.contract`, `seon.cost`, `seon.profile`: present.
- turns-to-green report → `seon.learn` charges overrun (``:seon.task/waste``): partial.
- scheduled work (``seon.schedule``) → the cron proc (``seon.cron``), one daily full run of main (`:seon.config.proofs/full-run-at`): partial.

## Context, printing, the page

Each v1 capability for the model's context, printing and the page.

- generated task context, one reference rule, forgiving reader → `seon.ctx`, `seon.ref`, `seon.reader/forgive`: present.
- `(help)` checked against the program → [agent](agent.md), `my.agent/help`, every form run by `seon.repl-test/the-template-instructions-and-the-help-page-run`: present.
- cached context sections → `seon.ctx.wire/request` ([ctx](ctx.md)): present.
- one printer with a budget; renders on schemas → `seon.print`, `seon.render`: present.
- wiki sections in the context → `seon.doc`: new.
- Datastar pages `/`, `/agent/{id}` → `seon.web`: present.
- `/agent/{id}/debug` → the same route (`seon.render.proc/debug-value`), each request remade from its records (`seon.ctx.wire/wire-turn`) and checked against `:seon.model-call/sent-hash` (`seon.ctx.wire-test/the-request-is-a-conversation-of-evals-and-the-page`): present.
- `/agent/{id}/message` → the page's input and the server's `:message` op, both `seon.system/message!`: present.
- `/ns/{ns}`, `/data`, `/chains`, `/health` → none (task `seon.render/namespace-agent-debug-and-data-pages`; [dashboard](dashboard.md)): missing.
- POSTs refuse cross-origin → `seon.web/same-origin?` (`seon.web-test/a-cross-origin-post-is-refused`): present.

## Storage, config, errors

Each v1 capability for storage, config and errors.

- one store, many Datahike branches → `seon.db` (LMDB), `seon.writer`: present.
- history kept → `:seon.config.store/keep-history?`, fixed when a store is made ([storage](storage.md#history)): present.
- collection, log rotation (``seon.maintenance``) → root collects at quiescence ([storage](storage.md#collection)); no log rotation: partial.
- blobs out of line (``seon.blob``) → large values out of line in Datahike's store ([storage](storage.md#values-out-of-line)), zstd ([storage](storage.md#compression)): present.
- every config key documented → one aero file, one resolver (`seon.config`): present.
- one error shape, recorded per signature → `seon.contract/error`, `seon.error`, `seon.problems`: present.
- named catch sites → [failure points](failure-points.md), `bin/laws`: new.

## Onboarding and docs

Each v1 way of onboarding agents and keeping docs.

- `CLAUDE.md` → `AGENTS.md` alone: present.
- 13 skills in ``.agents/skills`` → the task's context and this wiki: dropped.
- architecture pages → topic pages and the [map](README.md), loaded as sections (`seon.doc`); a citation of a lost name is a health problem ([health](health.md)): present.
