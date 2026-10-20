# Vocabulary

The words this repo uses, and the namespace that owns each. Library words first (Clojure,
core.async.flow, Datahike, Malli, nREPL, git); a word below is ours only where no library
owns the concept. A new concept gets its word here before it appears in code.

## Processes (core.async.flow's words)

| word | meaning | owner |
|---|---|---|
| **proc** | a Flow process: one step-fn Var with four arities (describe, init, transition, transform) | `seon.step` |
| **step-fn** | the four-arity function a proc runs; the one process abstraction | `seon.step/step-schema` |
| **root** | the control process: one Flow graph of four procs plus one proc per agent | `seon.root` |
| **root graph** | that one Flow graph, changed in place by `flow/update!` | `seon.root/start!` |
| **api** | root's entrance proc: callers' reply promises, routing by request id | `seon.root/api-proc` |
| **writer** | the only `d/transact`, `d/branch!` and merge caller; group commit | `seon.writer/writer-proc` |
| **group commit** | one Datahike commit per branch per writer step | `seon.writer/commit-batch` |
| **flush window** | every write is answered when committed in memory; one group flush within `FLUSH-MS` makes it durable; `:flush?` waits for it | `seon.writer/flusher-proc`, `seon.config/FLUSH-MS` |
| **flusher** | the proc that times the flush window and asks the writer to flush | `seon.writer/flusher-proc` |
| **fork server** | the single-threaded process holding main's db value that forks agents | `seon.fork-server` |
| **standby** | a fork of the fork server, promoted when the server dies (root never forks after boot) | `seon.fork-server/promote` |
| **reconciler** | the proc that sees main's commits through a sliding buffer of one | `seon.reconcile` |
| **agent** | a process forked from the fork server, holding one branch, running a REPL | `seon.agent` |
| **stand-in** | an agent's proc in root: owns root's end of its socket and its process | `seon.agent-proc/agent-proc` |
| **edge reader** | a thread doing one blocking read and feeding one proc's in-port (physics) | `seon.root/start-reader!` |
| **run-loop** | the step-fn contract run on one thread, lifecycle as messages | `seon.step/run-loop` |
| **dial** | a configured number, read once | `seon.config` |

## The REPL (nREPL's words)

| word | meaning | owner |
|---|---|---|
| **request**, **response**, **session**, `eval`, `done`, `interrupt` | nREPL's message model on the agent's socket | `seon.repl` |
| **middleware** | `(fn [handler] (fn [request] ...))` over the base handler | `seon.repl/middleware` |
| **reply item** | one form's folded answer at root: printed value or error, handle, cost | `seon.agent-proc/fold` |
| **handle** | a live value kept in the agent, named `result/hN` | `seon.repl` |
| **value printer** | the one function that turns a value into text | `seon.agent/print-value` |
| **interrupt** | SIGUSR1 to an agent, escaping the running form at a safe point | `seon.intr` |

## Contracts (Malli's words)

| word | meaning | owner |
|---|---|---|
| **contract** | a function's `:malli/schema`, checked from its definition on (inputs, arity, output) | `seon.contract/defn` |
| **registry** | the named schemas, data in `resources/seon/schemas/*.edn` | `seon.contract/registry` |
| **refusal** | a contract's failure, thrown as the one error map (`:seon.error/type`) | `seon.contract/refusal` |
| **admit** | an agent's def armed by its contract, or warned (the uncontracted set grows) | `seon.repl/wrap-admit` |
| **guarantees** | `SEON_GUARANTEES`: on, `contracts` (no recording), `off` | `seon.contract/on?`, `record?` |

## Data (Datahike's and git's words)

| word | meaning | owner |
|---|---|---|
| **main**, **branch**, **merge base**, **merge main**, **conflict** | git's words over Datahike branches | `seon.writer`, `seon.db` |
| **program** | the definitions held as facts (`:var/src`), loaded where main or a branch changes them | `seon.program` |
| **store** | konserve under Datahike (LMDB, or the filestore), jfasl node codec | `seon.db` |
| **entity schema** | a Malli map with `{:seon.db/attributes true}`; its members are Datahike attributes | `seon.schema.datahike/declarations` |
| **recording** | one transaction per eval request on the agent's branch: its evaluations on its agent entity, its defs | `seon.agent-proc/record-tx` |
| **observation** | data on the branch that observed it (evaluations, answered t), never carried by a merge | `seon.db/observation-attrs` |

## Agent-facing verbs (`my.*`)

| ns | verbs |
|---|---|
| `my.db` | `db`, `q`, `pull`, `transact!` (on the agent's own branch) |
| `my.agent` | `$`, `whoami`, `messages`, `send-message` |

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
| `PROTO_<DIAL>` | `SEON_<DIAL>` |
