# Vocabulary

The words this repo uses, and the namespace that owns each: a library's word first (Clojure, core.async.flow, Datahike, Malli, nREPL, git), ours only where no library owns the concept. A new concept gets its word here before it appears in code; the words ruled out are [listed below](#words-we-do-not-use). A file or command keeps its old spelling until a rename task moves it, and a stored keyword is renamed by erasing the store, never by a migration.

## Processes (core.async.flow's words)

The words for processes, in core.async Flow's terms, and the namespace that owns each.

| word | meaning | owner |
|---|---|---|
| **proc** | a Flow process: one step-fn Var with four arities (describe, init, transition, transform) | `seon.step` |
| **step-fn** | the four-arity function a proc runs; the one process abstraction | `seon.step` |
| **root** | the control process: one Flow graph of procs, one worker proc per effect kind and one proc per agent ([root](root.md#the-root-graph)) | `seon.system` |
| **root graph** | that Flow graph, declared in `resources/seon/system.edn`, built by `seon.graph`, changed in place by `flow/update!` | `seon.system/start!` |
| **api** | root's request server proc: reply promises, routing by request id | `seon.system/api-proc` |
| **writer** | root's writes to main (group commit), `d/branch!` and every merge into main | `seon.writer/writer-proc` |
| **run-ahead write** | an agent's write on its own branch: applied at once, committed before the answer that reports it, synced by the next group commit | `seon.agent/transact!`, `seon.agent/commit!` |
| **ids** | entity and transaction ids, each issued once across every branch and process ([storage](storage.md#ids)) | `seon.db/ids!` |
| **group commit** | one Datahike commit of every transaction the writer has queued for main | `seon.writer/commit-batch` |
| **flusher** | the proc that syncs everything unflushed, continuously, with no window | `seon.writer/flusher-proc` |
| **fork server** | the single-threaded process holding main's db value that forks agents ([processes](processes.md#the-fork-server)) | `seon.fork-server` |
| **standby** | a fork of the fork server, promoted when the server dies | `seon.fork-server/promote` |
| **reconciler** | the proc that sees every main commit through a bounded buffer | `seon.reconcile` |
| **agent** | a process forked from the fork server, holding one branch, running a REPL ([processes](processes.md#the-agent-process)) | `seon.agent` |
| **agent proc** | an agent's proc in root: root's end of its socket and its process | `seon.agent-proc/agent-proc` |
| **edge reader** | a thread doing one blocking read and feeding one proc's in-port | `seon.fork-server/start-event-reader!` |
| **run-loop** | the step-fn contract run on one thread, lifecycle as messages | `seon.step/run-loop` |
| **effect worker** | a proc performing one kind of effect off root's graph, its answer data | `seon.fx/worker-proc` |
| **server** | root's request server: the `:op`s over the socket and the `seon_*` MCP tools ([server](server.md#ops)) | `seon.server/ops` |
| **holder** | a live agent holding a branch ([root](root.md#holders-and-refused-spawns)) | `seon.system/spawn!` |
| **dashboard** | the web page root serves, a render of main's facts ([dashboard](dashboard.md)) | `seon.web/web-proc` |
| **config key** | one documented value in `resources/seon/config/default.edn`, read through the one resolver ([config](config.md)) | `seon.config` |

## The REPL (nREPL's words)

The words for the agent's REPL, in nREPL's terms, and the namespace that owns each.

| word | meaning | owner |
|---|---|---|
| **request**, **session**, `eval`, `done`, `interrupt` | nREPL's message model on the agent's socket | `seon.repl` |
| **middleware** | `(fn [handler] (fn [request] ...))` over the base handler | `seon.repl/middleware` |
| **response** | one eval call's answer as data, the keys ``:seon.eval/response`` declares | `seon.repl/response` |
| **handle** | a live value kept in the agent, named by its answer's hash, ``result/a3f9c1d2`` | `seon.repl/eval-string` |
| **value printer** | the one function from a value to text under a token budget ([render](render.md#one-printer)) | `seon.print/print-value` |
| **render** | a view of a value declared on its schema ([render](render.md)) | `seon.render` |
| **interrupt** | SIGUSR1 to an agent, escaping the running form at a safe point | `seon.intr` |

## Contracts (Malli's words)

The words for contracts, in Malli's terms, and the namespace that owns each.

| word | meaning | owner |
|---|---|---|
| **contract** | a function's ``:malli/schema``, checked from its definition on | `seon.contract/defn` |
| **registry** | the named schemas, data in ``resources/seon/schemas/*.edn`` | `seon.contract/registry` |
| **invalid** | a contract's failure, explained, thrown as the one error map | `seon.contract` |
| **instrument** | an agent's def armed by its contract | `seon.repl/wrap-instrument` |
| **instrument mode** | `:seon.config.contract/instrument`: `:on`, `:contracts`, `:off` | `seon.contract/on?` |

## Data (Datahike's and git's words)

The words for data and branches, in Datahike's and git's terms, and the namespace that owns each.

| word | meaning | owner |
|---|---|---|
| **main**, **branch**, **merge base**, **conflict**, **merge**, **pull**, **rebase**, **cherry-pick**, **upstream**, **head**, **behind**, **unmerged**, **gc** | git's words over Datahike branches. A merge compares by function, schema key and doc section; a pull from main takes program only | `seon.merge`, `seon.writer` |
| **program** | the forms held as facts (`:form/src`), loaded where main or a branch changes them | `seon.program` |
| **store** | konserve under Datahike | `seon.db` |
| **entity schema** | a Malli map with `{:seon.db/attributes true}`; its members are Datahike attributes | `seon.schema.datahike/declarations` |
| **observation** | data on the branch that observed it, never merged | `seon.merge/carried-platform-attrs` |
| **generation** | a build of Seon's own source, recorded on main ([source](source.md#the-next-generation)) | `seon.fx/build` |
| **prove** | run what a change reaches, reusing every green at its reach digest ([merging](merging.md#a-merge-runs-what-the-change-reaches)) | `seon.proofs/plan`, `my.agent/prove` |

## Words we do not use

The words the owner has ruled out, each with what to say instead; a name that still holds one keeps its spelling until a rename task moves it, and new text uses the right word.

- **record** (noun): say entity, attribute, value, ref, pull pattern, the pulled map, datom (owner, 2026-11-09).
- **draw**: a render takes data and returns hiccup; the Web side writes html (owner, 2026-11-09).
- **page**, **walk**, **view** as loose nouns: say the library's thing (a ratom of the pulled data, a Reaction per component, hiccup, html, a Datastar patch, an SSE connection), and name our var only as a citation. A wiki page is a page (owner, 2026-11-08).
- **table**, **row**: say entity, attribute, ref, schema; write a comparison as a list of entities (owner, 2026-11-07).
- **run** (of an agent's work): say process, verdict, transaction, commit, experiment, `bin/prove`'s plan (owner, 2026-11-07).
- **root**, for the system: root is the agent env at the top of the hierarchy; say "the system" for the Flow procs and code that write on main, and say relations as the stored refs do (`:seon.agent/parent`, `:seon.task/started-by`) (owner, 2026-11-11).
- **kind**: a thing is what its schema says it is; choosing among templates is a merge (owner, 2026-11-07).
- **cursor**, **offset**: say what is held, such as an attribute holding a `t` (owner, 2026-11-07).
- **part**: it is a component (owner, 2026-11-06).
- **fence** for a guard: say guard; a fence is CommonMark's code fence (owner, 2026-11-07).
- **our own word where git has one**: merge, pull, rebase, cherry-pick, delete a branch, gc ([above](#data-datahikes-and-gits-words)) (owner, 2026-11-07).

Words that stay: the libraries' (proc, step-fn, transact, branch, merge, schema, registry, contract, flusher, group commit, fork server, lease, generation), and **waste**, the turns and tokens an agent spent past what its work needed (`:seon.agent/waste`).

## Tasks (TASK-CHAIN's words)

The words for tasks and their prerequisites, and the namespace that owns each.

| word | meaning | owner |
|---|---|---|
| **task** | an entity of desired state: plan, instructions, proofs, `:needs` ([tasks](tasks.md)) | `seon.task` |
| **needs** | a task's prerequisites (refs); the only dependency edge | `seon.task/create-tx` |
| **accumulated** | a task's plan and proofs with its prerequisites' | `seon.task/tasks` |
| **outcome** | how a task's work stands or ended, an enum on `:seon.task/outcome` ([reconcile](reconcile.md#a-task-holds-how-its-work-ended)) | `seon.task/outcome-tx` |
| **test run** | one clojure.test run of some tests, never an agent's work | `seon.proofs/run-tests!` |
| **template** | a task's defaults by name | `seon.template` |
| **merge task** | a task whose agent merges main into the branch and goes green | `seon.template/with-merge` |
| **request** | what someone asked, as one task, written in one transaction | `my.root/request!` |
| **held for root** | a task whose end was refused or failed, kept with its evidence until released, edited or cancelled | `seon.reconcile/hold-tx` |
| **observed state** | what happened to a task (`:seon.task/state`), derived once from the index | `seon.reconcile/task-state` |
| **done rule** | why an end's facts do not make its task done; empty means done | `seon.reconcile/done-explain` |
| **reconciler** | the pure function of main's db to actions, and the proc that carries them out | `seon.reconcile/actions` |
| **flight** | one action carried out as a chain of requests in the reconciler proc | `seon.reconcile/advance` |
| **turn loop** | inside an agent: observe, then end or act | `seon.turn/work!` |
| **three-way** | git's merge of program maps keyed by var, form and schema entry ([storage](storage.md#merges-read-what-changed)) | `seon.merge/three-way` |
| **unmerged** | a branch's conflicts with main now, computed; settled by merging again with resolutions | `my.branch/unmerged` |
| **stage** | staging the live runtime onto the branch | `seon.stage/stage!` |
| **failure point** | a place the outside world can fail us, met by one named edge ([failure points](failure-points.md)) | |
| **problem** | an open fact that something needs fixing ([errors](errors.md#problems)) | `seon.problems/report` |
| **error fact** | an error recorded as data where it happened ([errors](errors.md#errors)) | `seon.error/fold` |
| **section** | one heading of a wiki page as an entity on main, address `path#anchor` | `seon.doc/load-tx` |

## Agent-facing functions (`my.*`)

The functions an agent has in its own namespaces live in `src/my/`; `(dir my.agent)` lists them, and [agent](agent.md) says how they are used.
