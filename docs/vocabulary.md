# Vocabulary

The words this repo uses, and the namespace that owns each. Library words first (Clojure,
core.async.flow, Datahike, Malli, nREPL, git); a word below is ours only where no library
owns the concept. A new concept gets its word here before it appears in code. The words that replace house words (a function, not a verb; a merge, not a landing; the server, not the door) are `AGENTS.md` §7a's table; a stored keyword keeps its old spelling until it is migrated.

## Processes (core.async.flow's words)

The words for processes, in core.async Flow's terms, and the namespace that owns each.

| word | meaning | owner |
|---|---|---|
| **proc** | a Flow process: one step-fn Var with four arities (describe, init, transition, transform) | `seon.step` |
| **step-fn** | the four-arity function a proc runs; the one process abstraction | `seon.step` |
| **root** | the control process: one Flow graph of nine procs (api, writer, fork-server, flusher, reconciler, prove, vectors, render, web), one worker proc per effect kind (`:fx` in `resources/seon/system.edn`), and one proc per agent ([root](root.md#the-root-graph)) | `seon.root` |
| **root graph** | that one Flow graph, changed in place by `flow/update!` | `seon.root/start!` |
| **api** | root's request server proc: callers' reply promises, routing by request id | `seon.root/api-proc` |
| **writer** | root's writes to main (group commit), `d/branch!`, and every merge into main; an agent commits its own branch | `seon.writer/writer-proc` |
| **run-ahead write** | an agent's own write on its own branch: applied at once, committed when idle or at a turn's end, and synced by the next group commit | `seon.agent/transact!`, `seon.agent/commit!`, `datahike.writer/apply!` |
| **ids** | the store's entity ids and transaction ids, each issued once across every branch and process, from one shared page (a file beside a durable store, an anonymous page for one in memory; [storage](storage.md#ids)) | `seon.db/ids!`, `datahike.ids`, `native/ids.c` |
| **group commit** | one Datahike commit of every transaction the writer has queued for main (`datahike.writer/transact-batch!`, one transaction at a time only when the store refuses the batch); every write is answered when committed in memory; the flusher syncs everything unflushed at once, then again, so commits arriving during a sync share the next one; `:flush?` waits for the sync that covers it | `seon.writer/commit-batch`, `seon.writer/flusher-proc` |
| **flusher** | the proc that syncs everything unflushed, continuously, with no window | `seon.writer/flusher-proc` |
| **fork server** | the single-threaded process holding main's db value that forks agents ([processes](processes.md#the-fork-server)) | `seon.fork-server` |
| **standby** | a fork of the fork server, promoted when the server dies (root never forks after boot) | `seon.fork-server/promote` |
| **reconciler** | the proc that sees every main commit through a bounded buffer (`:seon.config.root/reconciler-buf`): a lagging reconciler parks the writer's send | `seon.reconcile` |
| **agent** | a process forked from the fork server, holding one branch, running a REPL ([processes](processes.md#the-agent-process)) | `seon.agent` |
| **agent proc** (was stand-in) | an agent's proc in root: owns root's end of its socket and its process | `seon.agent-proc/agent-proc` |
| **edge reader** | a thread doing one blocking read and feeding one proc's in-port, the one thread a proc may own | `seon.fork-server/start-event-reader!` |
| **run-loop** | the step-fn contract run on one thread, lifecycle as messages | `seon.step/run-loop` |
| **effect worker** | a proc performing one kind of effect (a file, a git commit, a build, a model call) off root's graph, its answer data | `seon.fx/procs`, `seon.fx/worker-proc` |
| **server** (was door) | root's request server: the `:op`s a caller sends over the socket, and the `seon_*` MCP tools `bin/mcp` relays ([server](server.md#ops)) | `seon.server/ops` |
| **holder** | a live agent holding a branch; a spawn on a held branch, or past the live-agent cap, is refused ([root](root.md#holders-and-refused-spawns)) | `seon.root/spawn!` |
| **dashboard** | the web page root serves, a render of main's facts pushed as they change ([dashboard](dashboard.md)) | `seon.web/web-proc`, `seon.render.proc/render-proc` |
| **config key** (was dial) | a config key: one documented value in `resources/seon/config/default.edn` (unit, provenance, bound), validated by its `seon.config.*` schema, read once at boot and written as facts on main | `seon.config`, `seon.config/from-db` |

## The REPL (nREPL's words)

The words for the agent's REPL, in nREPL's terms, and the namespace that owns each.

| word | meaning | owner |
|---|---|---|
| **request**, **response**, **session**, `eval`, `done`, `interrupt` | nREPL's message model on the agent's socket | `seon.repl` |
| **middleware** | `(fn [handler] (fn [request] ...))` over the base handler | `seon.repl/middleware` |
| **response** | one eval call's answer: `{:handle :value :out :err :ex :ms :ticks :bytes}`, each key only when present | `seon.repl/response` |
| **handle** | a live value kept in the agent, named by its eval, ``result/eN`` | `seon.repl/eval-string` |
| **value printer** | the one function that turns a value into text under a token budget, saying what a cut omitted ([render](render.md#one-printer)) | `seon.print/print-value` |
| **render** | a view of a value declared on its schema, over the one printer; the model's context is one ([render](render.md)) | `seon.render` |
| **interrupt** | SIGUSR1 to an agent, escaping the running form at a safe point | `seon.intr` |

## Contracts (Malli's words)

The words for contracts, in Malli's terms, and the namespace that owns each.

| word | meaning | owner |
|---|---|---|
| **contract** | a function's ``:malli/schema``, checked from its definition on (inputs, arity, output) | `seon.contract/defn` |
| **registry** | the named schemas, data in ``resources/seon/schemas/*.edn`` | `seon.contract/registry` |
| **invalid** (was refusal) | a contract's failure, explained (`m/explain`), thrown as the one error map (`:seon.error/type`) | `seon.contract/invalid` |
| **instrument** (was admit) | an agent's def armed by its contract, or warned (the uncontracted set grows) | `seon.repl/wrap-instrument` |
| **instrument mode** (was guarantees) | `:seon.config.contract/instrument` (`SEON_INSTRUMENT`): `:on`, `:contracts` (no recording), `:off` | `seon.contract/on?`, `record?` |

## Data (Datahike's and git's words)

The words for data and branches, in Datahike's and git's terms, and the namespace that owns each.

| word | meaning | owner |
|---|---|---|
| **main**, **branch**, **merge base**, **merge main**, **conflict** | git's words over Datahike branches | `seon.writer`, `seon.db` |
| **program** | the forms held as facts (`:form/src`, each definition pointing to the form that last made it, from the compiler), loaded where main or a branch changes them | `seon.program` |
| **store** | konserve under Datahike (LMDB, or the filestore), jfasl node codec | `seon.db` |
| **entity schema** | a Malli map with `{:seon.db/attributes true}`; its members are Datahike attributes | `seon.schema.datahike/declarations` |
| **recording** | an eval request's evaluations (on its agent entity, one EDN `:seon.eval/record` each) and its defs, written by the agent on its own branch | `seon.repl/record-tx` |
| **observation** | data on the branch that observed it (evaluations, answered t), never carried by a merge: every `seon.*` attribute but the carried task content is a platform record | `seon.merge/carried-platform-attrs` |
| **generation** | a build of Seon's own source: the running binary is one, and the next is the merges commit built by the `:build` effect, recorded on main (`:seon.root/generation`) and started on the same store by `bin/root next` ([source](source.md#the-next-generation)) | `seon.fx/build`, `my.root/generation`, `my.root/next-generation` |
| **prove** | run what a change reaches and reuse every test and check step green at its reach digest: `bin/prove` in a checkout, ``(my.agent/prove)`` in an agent (the process tests it reaches run only when root asks, or when they are a proof; [merging](merging.md#a-merge-runs-what-the-change-reaches)) | `seon.proofs/plan`, `my.agent/prove` |

## Tasks (TASK-CHAIN's words)

The words for tasks and their prerequisites, and the namespace that owns each.

| word | meaning | owner |
|---|---|---|
| **task** | an entity of desired state: plan, instructions, proofs, `:needs`, environment, execution | `seon.task` |
| **needs** | a task's prerequisites (refs); the only dependency edge, no chain entity | `seon.task/create-tx` |
| **accumulated** | a task's plan and proofs with its prerequisites', derived along `:needs` | `seon.task/tasks` |
| **run** (was attempt) | observed state: one agent working one task once (`task#n`); a spawn root refused is a run too (`:refused`, no agent, root's reason), named by `seon.reconcile/root-causes` at quiescence and asked again only once main moves for another reason. Its turns, model calls and tokens are what its end measured: absent when unknown (no end is outcome `:no-end`), never 0 ([root](root.md#runs-and-judging)) | `seon.task/run-start-tx` |
| **test run** | one clojure.test run of some tests (a shard, an agent's test fork): its report data is what the proofs read, never a run of an agent | `seon.proofs/run-tests!` |
| **root's run** | one process of root on a store; what `runs/` keeps (`:seon.config.root/runs-dir`), never a run of an agent | `seon.root/start!` |
| **template** | a task kind's defaults: work, design, implement, test, merge | `seon.template` |
| **merge task** | an ordinary task (kind `:merge`) whose new agent merges main into the branch and goes green | `seon.template/with-merge` |
| **request** | what someone asked, as its design-build task and its merge task on one branch, written in one transaction | `seon.template/request` |
| **held for root** | a task whose run was refused (done rule, dead agent, refused merge) or marked failed: its failure and evidence on it, execution `:held`, state `:failed`, until root releases, edits or cancels it; never retried on its own | `seon.reconcile/hold-tx` |
| **observed state** | what happened to a task (running, blocked, open, held, failed, done, merged, cancelled; `:seon.task/state`), derived once from the index | `seon.reconcile/task-state` |
| **done rule** | why a run's measured facts do not make its task done; empty means done | `seon.reconcile/done-explain` |
| **reconciler** | the pure function of main's db to actions, and the proc that carries them out | `seon.reconcile/index` then `seon.reconcile/actions`, and `reconciler-proc` |
| **flight** | one action carried out as a chain of requests and answers in the reconciler proc | `seon.reconcile/advance` |
| **turn loop** | inside an agent: observe, then end (green, or stopped with the outcome and reason) or act (context to the model, forms through the REPL path); every run merges main first, and a merge task again at each green until main brings nothing new | `seon.turn/work!` |
| **three-way** | git's merge of program maps (base, ours, theirs) keyed `[:var sym]`, `[:form id]`, `[:schema k]` (a schema entry), read only where the dbs differ ([storage](storage.md#merges-read-what-changed)) | `seon.merge/three-way`, `seon.merge/delta-three-way` |
| **unmerged entry** | a conflict kept on the branch (base, ours, theirs) until re-defn or `resolve!` | `seon.merge/unmerged` |
| **stage** (was harvest) | staging the live runtime onto the branch (unmaps, plain defs as values, aliases) | `seon.stage/stage!` |
| **failure point** (was dragon) | a place where the outside world can fail us, met by one named edge function | `docs/failure-points.md` |
| **problem** | an open fact that something needs fixing: a failed or blocked task, a task conflict, a recorded error; health is their absence ([errors](errors.md#problems)) | `seon.problems/report`, `my.root/problems` |
| **error fact** | an error recorded as data where it happened, folded per key until its cause is fixed ([errors](errors.md#errors)) | `seon.error/fold` |
| **section** | one heading of a wiki page as an entity on main: address `path#anchor`, heading, lead, own text, parent, and references (what its own text cites); its backlinks are who cites it | `seon.docs/load-tx` |

## Agent-facing functions (`my.*`)

The functions an agent has in its own namespaces, by namespace.

| ns | functions |
|---|---|
| `my.db` | `db`, `q`, `pull`, `transact!` (on the agent's own branch) |
| `my.agent` | `$`, `whoami`, `messages`, `send-message`, `help`, `callers`, `callees`, `grep`, `source-fn`, `source` (a macro), `pst`, `prove` |
| `my.task` | `current`, `update!`, `fail!` |
| `my.turn` | `evals`, `wait` |
| `my.schema` | `registration`, `forms`, `register!` (on the agent's own branch) |
| `my.fx` | `call`, `send` (effects through their owner) |
| `my.branch` | `merge-main!`, `unmerged`, `resolve!` |
| `my.root` (root's functions) | `create!`, `request!`, `run!`, `cancel!`, `task-files`, `load-tasks!`, `load-docs!`, `problems`, `generation`, `resume-args`, `build!`, `next-generation` |

