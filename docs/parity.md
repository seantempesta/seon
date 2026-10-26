# Parity with v1 Seon

next replaces v1 Seon (`~/src/seon`, JVM, read-only): each capability v1 had is present here, in progress, or left behind on purpose with its reason in [BAGGAGE](../BAGGAGE.md). This page is the tracker: a change that closes a gap updates its row in the same commit. Each row is checked in code unless it says "doc".

Status words: **present** (the code exists here; not a claim that it passes), **partial**, **missing**, **dropped** (on purpose; BAGGAGE says why), **new** (v1 had none).

## Operating the system

Each v1 way of starting, stopping and watching the system, and where it stands here.

| v1 | next | Status |
|---|---|---|
| ``bin/seon start``, ``start --head``, ``status``, ``stop`` | `bin/root start`, `next` (the newest built generation), `status`, `stop`; every end is a fact on main (`:seon.root/stopped` with its cause, `:seon.root/previous-end` at the next boot, `:unclean` after a SIGKILL or a crash: `seon.root/stop!`) | present |
| ``bin/seon logs`` | `runs/root.log`, no command | partial |
| ``bin/seon open``, ``init``, ``reset``, ``nuke`` | none (one root per checkout; per-checkout ports, `bin/ports`) | missing |
| ``bin/seon config apply`` (sparse, per branch) | config layers plus `--set`; `seon.root/apply!` re-reads `resources/seon/system.edn` | partial |
| ``bin/worker`` claims and proven commits | worktrees (`bin/worktree`) and root's merges | dropped |

## The MCP server

Each v1 MCP tool and where it stands here.

| v1 | next | Status |
|---|---|---|
| ``eval_clj`` (an agent's REPL or root's) | `seon_eval` (one evaluator: no JVM mode) | present |
| ``runtime_status`` (health, problems, flow, readiness) | `seon_agents` lists agents and tasks; health only on the dashboard | partial |
| an outside agent works a task | `seon_attach`, `seon_context`, `seon_done` | new |
| reasons and defects answered per request | a defect answers that request the typed `:seon.server/defect` and the server goes on (`seon.server/respond`); a refused attach still writes the task's state first (task `seon.server/attach-writes-nothing-when-it-is-refused`) | partial |

The server's ops, the MCP tools and root's evaluation path are [server](server.md#ops). `seon_agents` returns every task, the cancelled ones included: too much context for an outside agent.

## The agent's REPL (`my.*`)

Each v1 function of the agent's REPL and where it stands here.

| v1 | next | Status |
|---|---|---|
| `my.task/current`, `update!`, `send-back!`; `my.turn/wait` | `current`, `update!`, `fail!` (send-back replaced: a failure is task data, held for root: [tasks](tasks.md#a-refused-run-is-held-for-root)); `my.turn/wait` | present (an agent holds its task from the fork: [tasks](tasks.md#the-agent-knows-its-task)) |
| `my.program/callers`, `source`, `tests-reaching` | `my.agent/callers`, `callees`, `grep`, `source-fn`; `seon.program/reach-digests` (the digest of what a test reaches; `my.agent/prove` runs the tests a change reaches) | present |
| `my.program/history` | none yet; the store keeps history ([storage](storage.md#history)), the function is not built | missing |
| `my.program/search`, `similar` (embeddings) | `my.agent/similar` ('ns/f or text); `seon.vector/near` in any query; fused with the call graph ([vectors](vector.md)); EmbeddingGemma-2 in process (llama.cpp) where v1 ran MLX over HTTP | present, better |
| `my.program/diff`, `sizes`, `breaks` | `seon.root/branch-diff` (root only) | partial |
| `my.program/write-branch!` | write-back at merge (`seon.source`, the `:git` effect) | present, better |
| `my.message/inbox`, `send` (stored facts) | `my.agent/messages`, `send-message`: the owner's message a fact on main and on the taking agent's branch (`:seon.message/*`); another agent's send held in the agent's process | partial |
| `my.test/run`, `check` | proofs run by themselves after each evaluation, and `my.agent/prove` runs the same selection at the REPL ([turn](turn.md#who-runs-which-tests)) | present |
| removal that refuses while a referrer remains (`my.program/unmap-subject!`) | plain `ns-unmap`, nothing checked | missing |
| `my.web/fetch`, `search` | none (no `:web` effect) | missing |
| `my.fs/*` (read, write, list files), `my.edit/*` (exact-span text edits), `my.shell/run!` (a command, its output and exit), and their handlers' policy (fs roots, no symlink following, a digest precondition on write, argv-only shell with an env allowlist) | none; they go through the effect owner (`my.fx/call`), like every outside-world call. Seon does any work its user wants (AGENTS §1); only Seon's own code changes at the REPL | missing |
| `my.schema/register!`, `my.branch/merge-main!`, `unmerged`, `resolve!` | the same names (`merge-main!` stages the live runtime onto the branch, then merges main's changes since the merge base) | present |
| an effect's receipt committed before its handler runs, settled once, never sent again (`seon.effect`) | the outcome only, recorded after it (`seon.agent` writes `:seon.fx/status` on the answer); a dead agent's effect in flight leaves no record | missing |

## Tasks, merges, tests

Each v1 capability for tasks, merges and tests, and where it stands here.

| v1 | next | Status |
|---|---|---|
| tasks as data, `:needs`, one reconciler, green at start ends with no model call | `seon.task`, `seon.reconcile` | present |
| ``chain!`` and ``:seon.task/next`` | `:needs` only | dropped |
| step templates (design, prove, implement, merge) | `seon.template/request` by default (one task that merges itself), one transaction; for experiments, `seon.template/design-build` (design-build, then merge) and `seon.template/stages` (design → implement → merge on one branch) ([tasks](tasks.md#a-request-is-one-transaction)) | present |
| three-way program merge, conflicts as entries, forward merge | `seon.merge`, `my.branch/merge-main!` | present |
| many agents merging onto shared definitions at once (v1's swarm) | `bin/check swarm` (`seon.e2e`'s swarm row: 20 tasks over 5 shared vars, 8 agents at once; red unless conflicts arose and every one was resolved, every task done, and a fresh agent on main gets every proof's value) | present |
| main wins a conflict | ours is kept | missing |
| a changed function no test reaches refuses the branch (``:seon.test/unreached``, ``:seon.source/unreached``) | none: `seon.reconcile/done-explain` has no coverage reason | missing |
| one done rule shared with merge | `seon.reconcile/done-explain` (a proof that never ran is `:proof-unknown`, not red; a process test left out of a run is neither; a problem the branch made true is `:open-problem`; [tasks](tasks.md#the-done-rule)) | present |
| tests selected by reach; results reused by reach digest | `seon.proofs/plan` over `seon.program/reach-digests`: `bin/prove`, `bin/test` and `bin/check` reuse greens in the results directory every checkout shares (`:seon.config.source/test-results-dir`; check steps keyed too); agents' runs select and reuse by the same plan against their merge base (`seon.agent/test-plan`), also finding a green by its digest among main's result facts | present; an agent shares the greens when its root's binary is built from its source tree ([merging](merging.md#a-merge-runs-what-the-change-reaches)) |
| contracts armed at definition; cost proofs; per-function profile | `seon.contract`, `seon.cost`, `seon.profile` | present |
| turns-to-green report (median, p90) | `seon.learn` charges overrun per run (stored as ``:seon.run/waste``) | partial |
| scheduled work (``seon.schedule``) | none (AGENTS names a cron proc; `resources/seon/system.edn` has none) | missing |

## Context, printing, the page

Each v1 capability for building and printing the model's context, and where it stands here.

| v1 | next | Status |
|---|---|---|
| generated task context; one reference rule (`seon.ref`); forgiving reader | `seon.context`, `seon.ref`, `seon.reader/forgive` | present |
| `(help)` checked against the program | [agent](agent.md), the system message; `my.agent/help`; every form on it run in an agent by `seon.repl-test/the-help-page-runs` | present |
| context: stable run sections, cached; small turn sections; must-reads capped | ```seon.context/request``` (the help, the session's records, and the page at each model turn, whole then what changed: ```seon.context/world```); vars at their citation's depth, cut only by the printer at the context's budget; cache hits on each turn | present |
| one printer with a budget and runnable continuations; renders on schemas | `seon.print`, `seon.render` | present |
| wiki sections in the context | `seon.docs` | new |
| Datastar pages: `/`, `/agent/{id}` | `seon.web` | present |
| `/agent/{id}/debug`: each model call, what it was sent, its tokens | `/agent/{id}/debug` (`seon.render.proc/debug-value`): each model turn a row, newest first, read back from the records it sent (`:seon.turn/sent`, ```seon.context/parts-of```), each part beside the record that made it, with its tokens, cache share and, for a failed call, its typed error | present (task `seon.context/a-turn-records-what-it-sent`) |
| `/agent/{id}/message` (the owner's input bar) | the page's message bar and the server's `:message` op, both `seon.root/message!`: root's one delivery into the agent's inbox, which its next turn takes into its basis (`:messages` in the block) | present: a fact on main (from `"owner"`, to the run), and on the agent's branch when its turn takes it, named by id in the turn's basis |
| `/ns/{ns}` and its debug pages; `/data`; `/chains`; `/health` | none (task `seon.render/namespace-agent-debug-and-data-pages`; the routes here: [dashboard](dashboard.md)) | missing |
| POSTs refuse a cross-origin request (``::same-origin``) | `POST /call` reads no Origin or Sec-Fetch-Site (`seon.web/handler`): any page open in the owner's browser can post calls to every live agent | missing |

## Storage, config, errors

Each v1 capability for storage, config and errors, and where it stands here.

| v1 | next | Status |
|---|---|---|
| one store, many Datahike branches | `seon.db` (LMDB), `seon.writer` | present |
| history kept | `:seon.config.store/keep-history?` (default true), fixed when a store is made; a kept store made without it keeps its layout ([storage](storage.md#history)) | present |
| storage collection, log rotation (``seon.maintenance``) | root collects at quiescence with no agent live, when a branch is gone ([storage](storage.md#collection)); no log rotation | partial |
| blobs out of line (``seon.blob``) | large values out of line inside Datahike's own store, written once, kept by GC's mark ([storage](storage.md#values-out-of-line)); zstd for every stored value, built from source ([storage](storage.md#compression)); plain values stored as data, not EDN text ([storage](storage.md#plain-values)) | present |
| every config key documented | one aero file, one resolver (`seon.config`) | present |
| one error shape, recorded once per signature, problem report | `seon.contract/error`, `seon.error`, `seon.problems` | present |
| named catch sites | [failure points](failure-points.md), `bin/laws` | new |

## Onboarding and docs

Each v1 way of onboarding agents and keeping docs, and where it stands here.

| v1 | next | Status |
|---|---|---|
| `CLAUDE.md` (the laws) | `AGENTS.md` alone: Claude Code reads it when the folder has no `CLAUDE.md` | present |
| 13 skills in ``.agents/skills`` | the task's context and this wiki | dropped |
| architecture pages | topic pages and the [map](README.md), loaded into the store as sections (`seon.docs`); no check fails on a dangling citation (task `seon.docs/the-wiki-stays-true-and-healthy`) | partial |
