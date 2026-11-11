# Vocabulary

The words this repo uses, and the namespace that owns each. Library words first (Clojure,
core.async.flow, Datahike, Malli, nREPL, git); a word below is ours only where no library
owns the concept. A new concept gets its word here before it appears in code. Names are Clojure's, its libraries' or Jolt's words (`AGENTS.md` §7a: a function, not a verb; a merge, not a landing; the server, not the door), and the words ruled out are [listed below](#words-we-do-not-use); a file or command keeps its old spelling until a rename task moves it, and a stored keyword is renamed by erasing the store, never by a migration.

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
| **run-ahead write** | an agent's own write on its own branch: applied at once, committed when idle or at a turn's end and always before the answer that reports it (a reply says what it did is on the branch: `seon.repl-test/a-reply-comes-after-its-commit`), and synced by the next group commit | `seon.agent/transact!`, `seon.agent/commit!`, `datahike.writer/apply!` |
| **ids** | the store's entity ids and transaction ids, each issued once across every branch and process, from one shared page (a file beside a durable store, an anonymous page for one in memory; [storage](storage.md#ids)) | `seon.db/ids!`, `datahike.ids`, `native/ids.c` |
| **group commit** | one Datahike commit of every transaction the writer has queued for main (each applied with `datahike.writer/apply!`, then one `commit-pending!`; one transaction at a time only when the store refuses the batch); a write is answered once the store holds it, a `:flush?` one after its sync; the flusher syncs everything unflushed at once, then again, so commits arriving during a sync share the next one; `:flush?` waits for the sync that covers it | `seon.writer/commit-batch`, `seon.writer/flusher-proc` |
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
| **response** | one eval call's answer, data: the keys ``:seon.eval/response`` declares, each only when present; its `:value` is the one Clojure answers | `seon.repl/response` |
| **handle** | a live value kept in the agent, named by a hash of its answer, ``result/a3f9c1d2`` | `seon.repl/eval-string` |
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
| **merge**, **pull**, **rebase**, **cherry-pick**, **upstream**, **head**, **behind**, **unmerged**, **gc** | git's words for what a branch does, used rather than our own (owner, 2026-11-07). A merge compares each side's net change by entity identity and attribute, not by line, and replays the source's transactions whole, data and program alike ([merging](merging.md#seons-merge-is-a-three-way-merge-of-the-program)). A rebase keeps each commit's facts and their tx. | `seon.merge`, `seon.writer` |
| **program** | the forms held as facts (`:form/src`, each definition pointing to the form that last made it, from the compiler), loaded where main or a branch changes them | `seon.program` |
| **store** | konserve under Datahike (LMDB, or the filestore), jfasl node codec | `seon.db` |
| **entity schema** | a Malli map with `{:seon.db/attributes true}`; its members are Datahike attributes | `seon.schema.datahike/declarations` |
| **recording** | an eval request's evaluations (on its agent entity, each eval's attributes: `:seon.eval/code`, `:seon.eval/response` and the rest) and its defs, written by the agent on its own branch | `seon.repl/record-tx` |
| **observation** | data on the branch that observed it (evaluations, answered t), never merged: every `seon.*` attribute but the carried task content is a platform record | `seon.merge/carried-platform-attrs` |
| **generation** | a build of Seon's own source: the running binary is one, and the next is the merges commit built by the `:build` effect, recorded on main (`:seon.root/generation`) and started on the same store by `bin/root next` ([source](source.md#the-next-generation)) | `seon.fx/build`, `my.root/generation`, `my.root/next-generation` |
| **prove** | run what a change reaches and reuse every test and check step green at its reach digest: `bin/prove` in a checkout, ``(my.agent/prove)`` in an agent (the process tests it reaches run at the judge's merge gate, on a checkout, or when they are a proof; [merging](merging.md#a-merge-runs-what-the-change-reaches)) | `seon.proofs/plan`, `my.agent/prove` |

## Words we do not use

The words the owner has ruled out, each with what to say instead: a vague word hides what the data is, and a design described in it drifts from the database. A name in code or a heading that still holds one keeps its spelling until a rename task moves it; new text uses the right word.

- **record** (noun): say entity, attribute, value, ref, pull pattern, the pulled map, datom. Owner, 2026-11-09: "Stop using 'record' btw ... I want us to be more clear about what we are talking about and to be more focused on data schemas and datahike terms so we are never far away from the truth."
- **draw**: a render function takes data and returns hiccup; the Web side writes html. Owner, 2026-11-09: "Ditch the 'draw' term. I only want to focus on data right now; let Web focus on the html serving."
- **page**, **walk**, **view** as loose nouns: say what it is in the library's terms (a ratom holding the pulled data, a Reaction per component, hiccup, html, a Datastar patch by element id, an SSE connection per browser tab), and name our var only as a citation after it. Owner, 2026-11-08: "What is a 'page' your predecessors keep making up words and I keep begging you to use specific clojure terms or their library interfaces". A wiki page is a page.
- **table**, **row**: say entity, attribute, ref, schema, and write a comparison as a list of entities and their attributes. Owner, 2026-11-07: "There are no tables in the database please don't use any table or row references when doing these designs."
- **run** (of an agent's work): an agent has an env, a branch and its evals; say a process, a test's verdict, a transaction, a commit, an experiment, `bin/prove`'s plan. Owner, 2026-11-07: "There are no runs and rows. Everything needs to be data driven and a run doesn't exist and neither does a row."
- **root**, for the system: root is an agent env, the one at the top of the agent hierarchy; say the system for the Flow procs and code that write on main (its writer, its reconciler, its fork server), and main for that branch, a branch like any other. Every other relation is said as the stored refs say it: an agent's sub-agents are its `:seon.agent/agents`, and its parent for a task is that task's `:seon.task/started-by`; no role word the data does not hold. Never "root writes", "root observes", "root tracks". Owner, 2026-11-11: "\"root\" refers to an agent env that is the top of the agent hierarchy and we use parents and subagents and keep this based on the literal data model that is stored in the db as refs and data"; "I want to stop calling anything that the system is doing without an active env as \"root\"".
- **kind**: a thing is what its schema says it is, and choosing among templates is a merge. Owner, 2026-11-07: "in general there are no "kinds" and those are generally code smells"; 2026-11-08: "kinds don't exist in datahike. Something is a thing if there's a schema for it".
- **cursor**, **offset**: say what is held (how far an agent has read is an attribute holding a `t`). Owner, 2026-11-07: "We don't have cursors."
- **part**: it is a component. Owner, 2026-11-06: "I really don't like the part name".
- **fence** for a guard: say guard; a fence is CommonMark's code fence. Owner, 2026-11-07: "Fences are always signs a design is off."
- **our own word where git has one**: merge (not save or landing), pull (not sync or track), rebase, cherry-pick, delete a branch, gc ([above](#data-datahikes-and-gits-words)). Owner, 2026-11-07: "I think we should stick to git terms whenever possible so agents have an intuitive understanding."

Words that stay: the library's (proc, step-fn, transact, branch, merge, schema, registry, contract for a Malli function schema, flusher, group commit, fork server, lease, generation), and **waste**, our word for the turns and tokens an agent spent past what its work needed (`:seon.agent/waste`; owner, 2026-10-23).

## Tasks (TASK-CHAIN's words)

The words for tasks and their prerequisites, and the namespace that owns each.

| word | meaning | owner |
|---|---|---|
| **task** | an entity of desired state: plan, instructions, proofs, `:needs`, environment, execution | `seon.task` |
| **needs** | a task's prerequisites (refs); the only dependency edge, no chain entity | `seon.task/create-tx` |
| **accumulated** | a task's plan and proofs with its prerequisites', derived along `:needs` | `seon.task/tasks` |
| **outcome** (a run was an attempt) | how a task's work stands or ended, an attribute of the task root writes when it starts and judges it: `:running`, `:parked`, `:told`, `:cap`, `:done`, `:ready`, `:gate`, `:merged`, `:behind`, `:dead`, `:lost`, `:killed`, `:refused` (root refused the start: named by `seon.reconcile/root-causes` at quiescence and asked again only once main moves for another reason), `:closed`. There is no run: say task, agent, work, wake, end. An agent's turns, model calls and tokens are what its end measured: absent when unknown, never 0 ([reconcile](reconcile.md#a-task-holds-how-its-work-ended)) | `seon.task/outcome-tx` |
| **test run** | one clojure.test run of some tests (a shard, an agent's test fork): its report data is what the proofs read, never the work of an agent | `seon.proofs/run-tests!` |
| **root's run** | one process of root on a store; what `runs/` keeps (`:seon.config.root/runs-dir`), never the work of an agent | `seon.root/start!` |
| **template** | a task kind's defaults: work, design, implement, test, merge | `seon.template` |
| **merge task** | an ordinary task (kind `:merge`) whose new agent merges main into the branch and goes green | `seon.template/with-merge` |
| **request** | what someone asked, as one task one agent designs, builds and merges, written in one transaction | `my.root/request!`, `seon.template/request` |
| **held for root** | a task whose end was refused (done rule, dead agent, refused merge) or marked failed: its failure and evidence on it, execution `:held`, state `:failed`, until root releases, edits or cancels it; never retried on its own | `seon.reconcile/hold-tx` |
| **observed state** | what happened to a task (running, blocked, open, held, failed, done, merged, cancelled; `:seon.task/state`), derived once from the index | `seon.reconcile/task-state` |
| **done rule** | why an end's measured facts do not make its task done; empty means done | `seon.reconcile/done-explain` |
| **reconciler** | the pure function of main's db to actions, and the proc that carries them out | `seon.reconcile/index` then `seon.reconcile/actions`, and `reconciler-proc` |
| **flight** | one action carried out as a chain of requests and answers in the reconciler proc | `seon.reconcile/advance` |
| **turn loop** | inside an agent: observe, then end (green, or stopped with the outcome and reason) or act (context to the model, forms through the REPL path); every work merges main first, and a merge task again at each green until there is nothing new on main to merge | `seon.turn/work!` |
| **three-way** | git's comparison of program maps (base, ours, theirs) keyed `[:var sym]`, `[:form id]`, `[:schema k]` (a schema entry), read only where the dbs differ ([storage](storage.md#merges-read-what-changed)): what judges a merge up and what a branch's merge carries, never what the merge writes | `seon.merge/three-way`, `seon.merge/delta-three-way` |
| **unmerged entry** | a conflict an earlier merge stored on the branch (base, ours, theirs), read until re-defn or `resolve!`; the one merge stores none, and a branch's conflicts with main are computed (```seon.agent/main-conflicts```) | `seon.merge/unmerged` |
| **stage** (was harvest) | staging the live runtime onto the branch (unmaps, plain defs as values, aliases) | `seon.stage/stage!` |
| **failure point** (was dragon) | a place where the outside world can fail us, met by one named edge function | `docs/failure-points.md` |
| **problem** | an open fact that something needs fixing: a failed or blocked task, a task conflict, a recorded error; health is their absence ([errors](errors.md#problems)) | `seon.problems/report`, `my.root/problems` |
| **error fact** | an error recorded as data where it happened, folded per key until its cause is fixed ([errors](errors.md#errors)) | `seon.error/fold` |
| **section** | one heading of a wiki page as an entity on main: address `path#anchor`, heading, lead, own text, parent, and references (what its own text cites); its backlinks are who cites it | `seon.doc/load-tx` |

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
| `my.root` (root's functions) | `create!`, `request!`, `run!`, `hold!`, `cancel!`, `load-tasks!`, `load-docs!`, `problems`, `generation`, `resume-args`, `build!`, `next-generation` |
