# Vocabulary

The words this repo uses, and the namespace that owns each. Library words first (Clojure,
core.async.flow, Datahike, Malli, nREPL, git); a word below is ours only where no library
owns the concept. A new concept gets its word here before it appears in code. The words that replace house words (a function, not a verb; a merge, not a landing; the server, not the door) are `AGENTS.md` §7a's table; a stored keyword keeps its old spelling until it is migrated.

## Processes (core.async.flow's words)

| word | meaning | owner |
|---|---|---|
| **proc** | a Flow process: one step-fn Var with four arities (describe, init, transition, transform) | `seon.step` |
| **step-fn** | the four-arity function a proc runs; the one process abstraction | `seon.step/step-schema` |
| **root** | the control process: one Flow graph of four procs plus one proc per agent | `seon.root` |
| **root graph** | that one Flow graph, changed in place by `flow/update!` | `seon.root/start!` |
| **api** | root's request server proc: callers' reply promises, routing by request id | `seon.root/api-proc` |
| **writer** | root's writes to main (group commit), `d/branch!`, and every merge into main | `seon.writer/writer-proc` |
| **run-ahead write** | an agent's own write on its own branch: applied at once, committed when idle or at a turn's end, and synced by the next group commit | `seon.agent/transact!`, `seon.agent/commit!`, `datahike.writer/apply!` |
| **lease** | the block of entity ids an agent process mints on its branch, reserved from root's one counter ([storage](storage.md#leases)) | `:seon.config.store/eid-lease`, `seon.db/lease-block!`, `seon.db/after-fork!` |
| **group commit** | one Datahike commit per branch per writer step | `seon.writer/commit-batch` |
| **group commit** | every write is answered when committed in memory; the flusher syncs everything unflushed at once, then again, so commits arriving during a sync share the next one; `:flush?` waits for the sync that covers it | `seon.writer/flusher-proc` |
| **flusher** | the proc that syncs everything unflushed, continuously, with no window | `seon.writer/flusher-proc` |
| **fork server** | the single-threaded process holding main's db value that forks agents | `seon.fork-server` |
| **standby** | a fork of the fork server, promoted when the server dies (root never forks after boot) | `seon.fork-server/promote` |
| **reconciler** | the proc that sees main's commits through a sliding buffer of one | `seon.reconcile` |
| **agent** | a process forked from the fork server, holding one branch, running a REPL | `seon.agent` |
| **agent proc** (was stand-in) | an agent's proc in root: owns root's end of its socket and its process | `seon.agent-proc/agent-proc` |
| **edge reader** | a thread doing one blocking read and feeding one proc's in-port (physics) | `seon.root/start-reader!` |
| **run-loop** | the step-fn contract run on one thread, lifecycle as messages | `seon.step/run-loop` |
| **config key** (was dial) | a config key: one documented value in `resources/seon/config/default.edn` (unit, provenance, bound), validated by its `seon.config.*` schema, read once at boot and written as facts on main | `seon.config`, `seon.config/from-db` |

## The REPL (nREPL's words)

| word | meaning | owner |
|---|---|---|
| **request**, **response**, **session**, `eval`, `done`, `interrupt` | nREPL's message model on the agent's socket | `seon.repl` |
| **middleware** | `(fn [handler] (fn [request] ...))` over the base handler | `seon.repl/middleware` |
| **reply item** | one form's folded answer at root: printed value or error, handle, cost | `seon.repl/fold` |
| **handle** | a live value kept in the agent, named `result/hN` | `seon.repl` |
| **value printer** | the one function that turns a value into text | `seon.agent/print-value` |
| **interrupt** | SIGUSR1 to an agent, escaping the running form at a safe point | `seon.intr` |

## Contracts (Malli's words)

| word | meaning | owner |
|---|---|---|
| **contract** | a function's `:malli/schema`, checked from its definition on (inputs, arity, output) | `seon.contract/defn` |
| **registry** | the named schemas, data in `resources/seon/schemas/*.edn` | `seon.contract/registry` |
| **invalid** (was error) | a contract's failure, explained (`m/explain`), thrown as the one error map (`:seon.error/type`) | `seon.contract/invalid` |
| **instrument** (was admit) | an agent's def armed by its contract, or warned (the uncontracted set grows) | `seon.repl/wrap-instrument` |
| **instrument mode** (was guarantees) | `:seon.config.contract/instrument` (`SEON_INSTRUMENT`): `:on`, `:contracts` (no recording), `:off` | `seon.contract/on?`, `record?` |

## Data (Datahike's and git's words)

| word | meaning | owner |
|---|---|---|
| **main**, **branch**, **merge base**, **merge main**, **conflict** | git's words over Datahike branches | `seon.writer`, `seon.db` |
| **program** | the forms held as facts (`:form/src`, each definition pointing to the form that last made it, from the compiler), loaded where main or a branch changes them | `seon.program` |
| **store** | konserve under Datahike (LMDB, or the filestore), jfasl node codec | `seon.db` |
| **entity schema** | a Malli map with `{:seon.db/attributes true}`; its members are Datahike attributes | `seon.schema.datahike/declarations` |
| **recording** | an eval request's evaluations (on its agent entity, one EDN `:seon.cluster.eval/record` each) and its defs, written by the agent on its own branch | `seon.repl/record-tx` |
| **observation** | data on the branch that observed it (evaluations, answered t), never carried by a merge | `seon.db/observation-attrs` |
| **generation** | a build of Seon's own source: the running binary is one, and the next is the merges commit built by the `:build` effect, recorded on main (`:seon.root/generation`) and started on the same store by `bin/root next` ([source](source.md#the-next-generation)) | `seon.fx/build`, `my.root/next-generation` |

## Tasks (TASK-CHAIN's words; worktree sessions R1 and M1)

| word | meaning | owner |
|---|---|---|
| **task** | an entity of desired state: plan, instructions, proofs, `:needs`, environment, execution | `seon.task` |
| **needs** | a task's prerequisites (refs); the only dependency edge, no chain entity | `seon.task/create-tx` |
| **accumulated** | a task's plan and proofs with its prerequisites', derived along `:needs` | `seon.task/tasks` |
| **attempt** | observed state: one agent working one task once (`task#n`); a spawn root refused is an attempt too (`:refused`, no agent, root's reason), named by `seon.reconcile/root-causes` at quiescence and asked again only once main moves for another reason. Its turns, model calls and tokens are what its end measured: absent when unknown (no end is outcome `:no-end`), never 0 | `seon.task/attempt-start-tx` |
| **template** | a task kind's defaults: work, design, implement, test, merge | `seon.template` |
| **merge task** | an ordinary task (kind `:merge`) whose new agent merges main into the branch and goes green | `seon.template/with-merge` |
| **request** | what someone asked, as design → implement → merge tasks on one branch, written in one transaction | `seon.template/request` |
| **attach** | a failed merge's design → implement → test → merge tasks, each a new agent | `seon.template/attach-tx` |
| **observed state** | what happened to a task (running, blocked, open, held, done, merged, cancelled; `:seon.task/state`), derived once from the index | `seon.reconcile/task-state` |
| **done rule** | why an attempt's measured facts do not make its task done; empty means done | `seon.reconcile/done-explain` |
| **reconciler** | the pure function of main's db to actions, and the proc that carries them out | `seon.reconcile/reconcile`, `reconciler-proc` |
| **flight** | one action carried out as a chain of requests and answers in the reconciler proc | `seon.reconcile/advance` |
| **turn loop** | inside an agent: observe, then end or act (context to the model, forms through the REPL path) | `seon.turn/work!` |
| **three-way** | git's merge of program maps (base, ours, theirs) keyed `[:var sym]`, `[:form id]`, `[:schema k]` (a schema entry) | `seon.merge/three-way` |
| **unmerged entry** | a conflict kept on the branch (base, ours, theirs) until re-defn or `resolve!` | `seon.merge/unmerged` |
| **stage** (was harvest) | staging the live runtime onto the branch (unmaps, plain defs as values, aliases) | `seon.stage/stage!` |
| **failure point** (was dragon) | a place where the outside world can fail us, met by one named edge function | `docs/failure-points.md` |
| **section** | one heading of a wiki page as an entity on main: address `path#anchor`, heading, lead, own text, parent, and references (what its own text cites); its backlinks are who cites it | `seon.docs/load-tx` |

## Agent-facing functions (`my.*`)

| ns | functions |
|---|---|
| `my.db` | `db`, `q`, `pull`, `transact!` (on the agent's own branch) |
| `my.agent` | `$`, `whoami`, `messages`, `send-message`, `help`, `callers`, `callees`, `grep`, `source` |
| `my.task` | `current`, `update!`, `fail!` |
| `my.turn` | `wait` |
| `my.branch` | `merge-main!`, `unmerged`, `resolve!` |
| `my.root` (root's functions) | `create!`, `run!`, `cancel!` |

## Renamed from the prototypes

| prototype (proto/flow f2-final) | here |
|---|---|
| `proto.control` | `seon.root` (api, reader, boot, API), `seon.writer`, `seon.agent-proc`, `seon.reconcile`, `seon.config` |
| `proto.server` | `seon.fork-server` (both its loop and root's `:fork-server` proc) |
| `proto.agent` agent API | `my.db`, `my.agent` |
| `proto.proc` + `proto.step` | `seon.step` |
| `proto.lib`, `proto.sock`, `proto.wire`, `proto.intr`, `proto.program`, `proto.db`, `proto.repl` | `seon.lib`, `seon.sock`, `seon.wire`, `seon.intr`, `seon.program`, `seon.db`, `seon.repl` |
| `x1.codec` | `konserve.jolt.fasl` (in the konserve fork) |
| `proto.smoke` | `seon.check` |
| `proto.agents.*` (R1, M1) | `seon.task`, `seon.reconcile`, `seon.template`, `seon.turn`, `seon.context`, `seon.model`, `seon.proofs`, `seon.stage`, `seon.fixtures`, `seon.e2e`; `proto.merge` → `seon.merge` |
| `proto.agents.root` (executor thread, observer threads) | `seon.reconcile/reconciler-proc` (one step-fn) and `my.root` |
| `PROTO_<KEY>` | `SEON_<KEY>` |
