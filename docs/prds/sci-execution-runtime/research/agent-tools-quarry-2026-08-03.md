---
type: research
status: active
tags: [research, agent, toolkit, capability, quarry]
---

# Agent tools quarry

## Executive verdict

The old system had enough functions for an agent to do real work: inspect and
edit files, run bounded processes and background jobs, search files and the
program graph, fetch and search the web, keep plans and knowledge, store large
content, schedule work, inspect failures, and build interactive canvas views.
It also paid for those functions with the deleted pod, Promise coloring,
per-family bindings, in-eval transactions, hand-maintained public-function
maps, and several overlapping result envelopes.

The useful lesson is the capability set, not any implementation. The fresh
system currently has an excellent narrow value surface (`my.run` and
`my.message`) and a substantial database surface (`seon.db`), but almost none
of the work surface. `src/my/` contains exactly two files and four public
functions. There is no `src/seon/effect.clj`, no `effect/request!`, and no
production function carrying `:seon.workload` metadata. Therefore the effect
owner has **zero current arms**. The architecture's `my.blob`,
`my.canvas`, `my.data`, `my.fs`, `my.kb`, `my.ns`, `my.plan`, `my.shell`,
`my.skills`, `my.ui`, and `my.web` catalog is target, not implementation
(`docs/seon/architecture/toolkit.md:67-88`).

There is a second, sharper truth. The cluster context installs every public Var
from every loaded first-party namespace as the real compiled host Var
(`src/seon/sci/eval.clj:798-823`). Thus every public function is callable, but
that does not make it a safe tool. `seon.ai/complete` performs direct HTTP
(`src/seon/ai.clj:926-964`), `seon.blob/put!` and `get` perform direct Konserve
I/O when handed custody (`src/seon/blob.clj:35-68`), and
`seon.fs/delete-recursively!` accepts a caller-selected root and deletes beneath
it (`src/seon/fs.clj:24-77`). None enters the nonexistent effect owner. The
current state is not “no powers”; it is **four curated functions plus a large,
uncurated compiled surface whose effect policy is not centralized**.

Overall toolkit grade: **D+**. The implemented contracts deserve an **A-** for
shape, the database namespace a **B+** for breadth, work coverage an **F**, and
effect-boundary integrity a **D**. The stale production documentation already
tracked in [[production-docstrings-teach-deleted-semantics]] keeps the contract
grade below A: `my.message` still claims `my.run` omits error kinds even though
the code includes them (`src/my/message.clj:49-54`;
`src/my/run.clj:62-83`).

## Scope and evidence

This audit used the quarry only for behavior and scars. It did not treat a
`.cljs` namespace, Promise, `await`, pod binding, function roster, stored
status, or in-eval transaction as a candidate implementation.

### Dependency ledger

| Dependency or mechanism | Revision | Evidence used |
|---|---|---|
| Datahike | `0e8601d7f2f6` | The fresh database surface preserves Datahike-shaped positional and argument-map operations in `src/seon/db.clj:386-814`. |
| SCI | `2db3358cba91` | Fresh acquisition installs loaded public host Vars at `src/seon/sci/eval.clj:798-823` and builds one cluster context at `:1201-1228`. |
| core.async Flow | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | Capability work must eventually leave compute at the effect boundary; no old job loop or scheduler is a surviving owner. |
| Malli | `80138076960e` | Fresh public contracts and program facts are the discovery substrate; no public-function registry survives. |
| ClojureScript quarry | `946d75f3483c0c8e784e6668bff2c71a25619a77` | Explains old `^:async`/`await` behavior only; the CLJS build remains off. |

Historical source was read at three useful boundaries:

- the last broad pod capability tree before Group 2 deletion,
  `f31aacd7408e6ab78735715df356be8f570a9ccf`;
- the last `my.data`/`my.ns` tree before Group 4 deletion,
  `3f1895b04e389ec494c97b00aafac6404780adf8`; and
- the short-lived `my.db`/effect contract pilot before deletion,
  `c093bb9ead900104d6428c41b76ecca4be73fe31`.

The older `todo` → `my.plan`, `tile` → `canvas`, `inspect` → `debug`, and
instruction → system → shared histories were followed across renames rather
than counted as separate capabilities.

## What exists fresh

### Curated `my.*`

| Namespace | Current functions | Actual shape | Assessment |
|---|---|---|---|
| `my.run` | `wait`, `complete` (`src/my/run.clj:40-84`) | Pure disposition values. The run loop recognizes the schema and commits terminal facts (`src/seon/cluster/loop.clj:221-335`). | **Keep.** This is the conversion model: no capability and no transaction inside eval. |
| `my.message` | `send`, `decline` (`src/my/message.clj:100-159`) | Pure delivery values. The loop recognizes one or a vector and commits delivery (`src/seon/cluster/loop.clj:232-247`). | **Keep.** The old 590-line effectful message owner collapsed into constructors plus the existing terminal commit. |

There is no fresh `my.plan`, `my.kb`, `my.blob`, `my.fs`, `my.shell`,
`my.web`, `my.ns`, `my.skills`, `my.canvas`, `my.ui`, or `my.data` source.

### Fresh database work surface

`seon.db` is already a meaningful agent work surface:

- `db`, `q`, `pull`, `pull-many`, `entity`, and `datoms` cover current and
  explicit immutable database values (`src/seon/db.clj:386-628`);
- `commit-id`, `committed-value-identity`, `history`, `as-of`, and `since`
  cover identity and temporal reads (`:665-732`); and
- `transact!` supplies ambient custody, foreign-connection refusal, ordinary
  transaction reports, and flat refusal values (`:754-814`).

This is better than both the old `seon.db` wrapper vocabulary and the brief
`my.db` pilot. The pilot merely sent `q` and `transact` through a family enum
and invented a replay envelope
(`c093bb9e:src/my/db.cljc:67-99`). No `my.db` rebirth is warranted. Database
functions stay in `seon.db`; domain composition belongs in ordinary `my.*`
namespaces.

### Effect owner and current arms

| Intended family | Fresh public implementation | Enters `effect/request!`? | Verdict now |
|---|---|---:|---|
| Database | `seon.db` reads and `transact!` | No | Useful and live, but not an arm of the effect owner. Its ambient custody fence is real; its effect receipt/placement integration is absent. |
| Messaging/run lifecycle | `my.message`, `my.run` values | Correctly not applicable | These are loop-interpreted values, not capabilities. |
| LLM | `seon.ai/complete` direct JDK HTTP | No | System owner exists; no curated `my.*` call and no agent-effect receipt. |
| Blob | `seon.blob/put!`, `get` with explicit branch connection | No | System storage owner exists; no bounded agent wrapper and normally no usable custody. |
| Filesystem | `seon.fs/delete-recursively!` only | No | Cleanup primitive, not an agent filesystem. Public callability currently bypasses capability policy. |
| Shell/process | None fresh | No | Gap. |
| Web fetch/search | None fresh | No | Gap. |
| Tests | `seon.test.runner` system operation | No public `my.*` request | The one runner exists; agent composition does not. |

The zero-arm conclusion is source-conclusive: no `src/seon/effect.clj` exists;
`src/my/message.clj:19-25` explicitly says the owner does not exist; and the
only production readers/writers of `:seon.fn/workload` are the analyzer and
run-loop reachability logic, while no production `defn` declares
`:seon.workload` (`src/seon/fn.clj:240-273`;
`src/seon/cluster/loop.clj:337-363`).

## Quarry inventory and rebirth verdicts

The table groups aliases and successive implementations as one tool. “Rebirth”
means a fresh CLJ design under today's model, never moving the old source.

| Old tool or lineage | What it did | Verdict | Surviving lesson |
|---|---|---|---|
| `seon.agent.todo` → `my.plan` | Created hierarchical plans, dependencies, active focus, completion/block/reopen/move/drop, reconciliation, next work, tree/document/status, and generated-code plan coordination. The early callable set is visible at `146c52f6^:src/seon/agent/todo.cljs:173-338`; the final quarry grew to `src-old/my/plan.cljc:849-1823`. | **Rebirth, radically smaller.** | Store intent and dependency refs, derive roll-up/frontier/document, reconcile one EDN tree. Delete generated-code orchestration, async database choreography, duplicate render logic, and in-eval writes. Mutations should return transaction intent or use the one database boundary; reads are pure over a database value. |
| `my.kb` plus `my.kb.instruction/system/shared` | Remembered sources and metadata; retitled, rated, retagged, forgot, and queried titles/authors/details (`src-old/my/kb.cljc:81-268`). The instruction lineage stored and rendered shared instructions. | **Rebirth only as teaching/domain composition.** | The useful product is a clean schema/query/transact worked example and perhaps small recall helpers. “Memory” is not another subsystem: durable knowledge is ordinary database facts and large bodies are blobs. Shared instructions already belong to cluster instruction facts/rendering. |
| `my.data` | Retrieved entities by attribute presence and offered `sum-by`, `max-by`, and `group-sum` over ordinary rows (`3f1895b0:src/my/data.cljs:45-129`). | **Keep as a small pure library, low priority.** | Plain Clojure reductions over explicit rows avoid surprising query aggregation. Do not restore the bespoke items/result envelope or an async row loader; `seon.db/q` already retrieves data. |
| `my.ns` | Listed schema-complete public functions and selected full versus compact namespace context (`3f1895b0:src/my/ns.cljs:50-232`). | **Rebirth, query-first.** | Discovery is a Datalog query over program facts, joined by schemas/call edges. Full/compact context changes should be ordinary declared facts or direct walk arguments, never a second context registry. |
| `seon.agent.search/grep` and `grep-graph` | Ran bounded ripgrep over allowed roots and searched stored code text, grouping results by namespace (`f31aacd7:src/seon/agent/search.cljs:157-350`). | **Split the lesson.** Filesystem search belongs in `my.fs`/`my.shell`; program discovery belongs in `my.ns` and Datalog. | Preserve bounded results, honest totals, file/line evidence, and program-graph coverage. Do not restore regex-over-structured-code as the default or a second search registry; call edges, source spans, schemas, and facts answer structured questions. |
| `my.skills` | Listed canonical skill rows and derived which skills were loaded (`src-old/my/skills.cljc:41-106`). Earlier versions also loaded/unloaded blocks. | **Rebirth after core work tools.** | Skill catalog and load state are database facts and derived context, not process registration. Import/list/load/unload should use the ordinary block/fact owners and blob/file capabilities. |
| `my.blob` | Content-addressed put/get/concat, bounded text paging, stat, media hints, durable publication, and branch overlay materialization (`src-old/my/blob.cljc:286-439`). | **Rebirth.** | SHA-256 identity, idempotent publication, verified read, bounded line windows, honest totals, and one canonical concatenation are all sound. The public core returns ordinary data; disk/Konserve work crosses one effect owner. Restore/materialization remains a system lifecycle concern, not an agent function. |
| `my.tile` → `my.canvas` | Built buttons/inputs/selects/toggles/forms; later pinned a renderer or literal content, read/saved agent-local state, and cleared the pin (`dd5f3fb^:src/my/tile.cljs:128-220`; `src-old/my/canvas.cljc:47-291`). | **Rebirth after the action contract settles.** | Pure Hiccup/control constructors and a focal value are worth keeping. Delete `show!`, `clear!`, `save!`, renderer lookup, and page mutation inside eval. Actions are values interpreted by the run loop or genuine capability requests. Namespace-owned render functions wire by declared contracts. |
| `my.ui` | Produced paired HTML/AI status lines, tables, badges, bullets, progress, and sections (`src-old/my/ui.cljc:63-271`). | **Rebirth as pure composition, but do not freeze a component catalog prematurely.** | Ordinary Hiccup and text are sufficient. Small helpers earn their place only when repeated use is measured. No stored render, UI registry, or dual-render envelope separate from the current two projection shapes. |
| `seon.agent.fs` plus `fs.match` | Granted roots; read, write, list, stat, walk, and view; edited by exact anchored replacement or line insertion with SHA, syntax check, candidate excerpts, and ambiguity refusal (`src-old/seon/agent/fs/leaf.clj:117-413`; `src-old/seon/agent/fs/match.cljc:341-380`). | **Highest-priority capability rebirth as `my.fs`.** | Keep lexical/canonical scope enforcement, no symlink escape, bounded paging, exact-match ambiguity refusal, pre/post digest, line evidence, syntax diagnostics, and errors as values. The pure edit decision can remain portable; all I/O must cross the one effect owner. Do not restore per-agent grants or a hand-built public-functions map. |
| `seon.agent.shell` | Ran bounded foreground commands and Python; started/stopped background jobs; listed status and paged stdout/stderr (`src-old/seon/agent/shell/leaf.clj:125-295`). | **Highest-value external capability rebirth as `my.shell`.** | Preserve argv rather than shell-text by default, explicit cwd/stdin, bounded output with honest truncation, completion/exit facts, and addressable job output. Background jobs need a Flow/system owner and durable receipts, not an atom plus polling API copied from the pod. |
| `seon.agent.web/fetch` and `search` | Enforced host policy and private-address refusal, extracted pages, blobbed full text, returned bounded previews/links, cached by age, and provided grounded search (`src-old/seon/agent/web.cljc:186-313`; host leaf at `src-old/seon/agent/web/host.clj:298-489`). | **High-priority rebirth as `my.web`.** | Keep redirect-hop policy, DNS/private-range checks, deadlines, byte/token bounds, final URL/status/content type, full-body blob identity, honest search totals, and errors as values. Search and fetch are two functions over one protected family, not separate transports or provider-shaped public APIs. |
| `seon.agent.message` and the short-lived effectful `my.message/message!` | Read recent messages and directly transacted sends with sender lookup, hop limits, leaf bindings, replay identity, and async calls (`src-old/seon/agent/message.cljc:145-565`; `c093bb9e:src/my/message.cljc:40-55`). | **Already reborn better.** | `my.message/send` and `decline` are values; the loop commits them. Preserve bounded chain depth and about-identity resolution at the loop, never restore reads/writes or effect dispatch inside eval. |
| `seon.agent.lifecycle` and old run APIs | Returned wait/complete/pause/resume/terminate dispositions; older run owners also claimed, renewed, released, and finished (`src-old/seon/agent/lifecycle.cljc:42-86`; `src-old/seon/agent/run/core.cljc:101-213`). | **Only `complete` and `wait` survive agent-facing.** | Run custody and recovery are system facts/transitions. Pause/resume/terminate were controls for a deleted agent-lifecycle entity. Administrative requests, if needed, should be values/facts at the owning boundary, not leaf-bound eval functions. |
| `seon.agent.schedule` | Parsed five-field cron, calculated due/next time, and opened due runs from a ticker (`f31aacd7:src/seon/agent/schedule.cljs:21-214,383-410`). | **Rebirth later, split pure schedule data from runtime firing.** | A schedule is a declared fact connecting an agent/function and expression. Pure parsing/next-occurrence logic can be a library; a Flow proc observes facts and opens runs. Do not port regex grammar, minute polling, tuned year scans, or in-eval scheduling. |
| `seon.agent.testrun` | Recognized pytest argv/output, parsed counts/failures, persisted the latest result, and fed reactive failure context (`f31aacd7:src/seon/agent/testrun.cljs:99-213`). | **No parser rebirth. Add a request to the existing test operation.** | The runner should emit structured results directly and facts should identify selection, assertions, failures, duration, and artifact. Parsing terminal prose and framework-specific regexes is the wrong boundary. |
| `seon.agent.inspect` → `debug` | Previewed exact context, inspected turns and diffs, listed errors, drilled one error, and built reproduction guidance (`c35677fa^:src/seon/agent/inspect.cljs:117-573`; final quarry `src-old/seon/agent/debug.cljs:74-482`). | **Rebirth as queries/rendered functions, not a special debug subsystem.** | Exact captured context, attempt/receipt history, error provenance, and reproducible database values are high-value. Most are already facts; expose small query functions and namespace pages. No file debug tree or alternate rendering path. |
| `findings`, `ctx.findings/jobs/testrun/inventory/relevant`, `turns` | Derived prompt sections pointing at matching domain facts, jobs, test failures, inventory, relevant source, and turn history. | **No tool rebirth.** | These were context projections, not work functions. The surviving distance walk plus declared renderers derives the same visibility from facts. Missing visibility means a missing fact/renderer, not another section namespace. |
| `ctx.admin/install!/remove!`, `home`, `runtime`, `authorization` | Mutated context blocks, selected home namespaces, hosted/unhosted/resumed agents, and enforced per-agent management. | **No general agent tool.** | Namespace ownership, cluster toolkit membership, agent creation, and context are declared facts handled by their system owners. The old per-agent grant/authorization model is explicitly rejected; capability policy applies to requests, not who may call a function. |
| `my.soul` and instruction file loaders | Read identity/instruction files live and rendered them into context (`a2481be9^:src/my/soul.cljs:68-91`). | **No rebirth.** | Instructions are database facts reached through the cluster and rendered as blocks. No live file read, soul store, or separate prompt authority. |
| `seon.agent.helpers/sql*`, trading `analysis`, and other domain helpers | Ran SQL and trading-specific analysis in core (`d90188d3^:src/seon/agent/helpers.clj:82-128`; `69379bfd^:src/seon/agent/analysis.clj:308-452`). | **No core rebirth.** | They prove agents benefit from ordinary domain functions, but consumer integrations and domain models belong downstream. Package/database capabilities make them authorable without adding Seon-core tool families. |
| Direct model completion | The old turn stack and today's `seon.ai/complete` call the hosted provider. | **Keep system-owned; expose only if a real agent composition requires nested model work.** | Normal model use belongs to the run loop. If nested model calls become a product capability, they cross the same effect owner with attempt facts and no independent retry mechanism. Do not mistake the loop's provider call for a missing everyday `my.llm` function. |

## Ranked gap list for agents doing proper work

Rank is product value and dependency order, not historical size. The Inspect
mapping provides one external measurement: 86 families / 133 tasks run without
capabilities; a real shell/exec boundary adds about 22 families / 63 tasks and
raises coverage from roughly 53% to 79%. Filesystem alone adds no benchmark
families but is a prerequisite for honest shell-based repository work
(`docs/prds/sci-execution-runtime/plan/inspect-review-brief-2026-08-02.md:20-30,48-58`).

1. **Build `seon.effect/request!` and make bypass impossible.** Before adding a
   family, one identity, policy/receipt boundary, `:io` handoff, flat result,
   and crash disposition must exist. The integrated falsifier is one real form
   using database, filesystem, shell, blob, and web calls while every effect is
   attributable and no compiled host call bypasses the owner.
2. **`my.fs`: bounded read/list/stat/walk plus exact editor operations.** This
   is the minimum for changing real source safely. Ship read/view and anchored
   replace/insert before broad write primitives; retain digests, ambiguity
   refusal, symlink safety, and syntax evidence.
3. **`my.shell`: bounded foreground process execution, then supervised jobs.**
   This unlocks builds, tests, linters, native package tools, and the measured
   benchmark tranche. A filesystem boundary must already provide honest cwd
   and artifact access. Background jobs follow only when durable identity and
   completion facts are settled.
4. **`my.web`: fetch first, search second.** Fetch with SSRF/redirect policy,
   deadlines, bounded preview, and full body in a blob covers research and
   remote documentation. Search composes into fetch and must return source
   rows, not an opaque answer-only product.
5. **`my.ns`: program-graph discovery and structured source lookup.** Agents
   need to answer what functions, schemas, calls, tests, and owners exist. Use
   Datalog over recorded facts. Literal source search is a fallback through
   filesystem/shell, not the primary program query.
6. **`my.plan`: durable intent/dependencies/reconciliation.** Real multi-turn
   work needs a plan that survives restart. Rebuild the small data model and
   pure derivations; exclude generated-code coordination and renderer baggage.
7. **`my.blob`: bounded large-value write/read/stat/concat.** Web pages, command
   output, artifacts, large memory, and result history need an addressable
   content tier. The fresh system owner exists, but the agent wrapper and one
   effect crossing do not.
8. **Test request and observability queries.** Expose the existing test runner
   as a function request with structured results, plus small query functions
   for attempts, receipts, captured context, errors, and reproduction. Do not
   restore pytest parsing or an inspector subsystem.
9. **Schedules.** Durable schedule facts plus one Flow owner are valuable for
   recurring user work, but they depend on stable function identity, run
   opening, and capability execution already working.
10. **Canvas/UI controls.** The user experience benefit is real, but action
    values and namespace-owned render wiring must settle first. Constructors
    stay pure; there is no page-state mutation tool.
11. **Skills, KB teaching helpers, and small data combinators.** These improve
    discovery and pedagogy after agents can already read, change, run, fetch,
    plan, and inspect. `seon.db` is already the memory substrate; these are
    composition, not prerequisites.

Typed provider tool calls are deliberately absent from this ranking. Seon is
form-driven: every callable function is a tool, and provider tool arrays remain
empty by design (`docs/prds/sci-execution-runtime/plan/README.md:2375-2385`).

## Quality grade in detail

| Dimension | Grade | Evidence |
|---|---:|---|
| Value/effect/fact model | A | `my.run` and `my.message` are the cleanest conversion in the tree: constructors return ordinary values and the loop commits terminal facts. |
| Database capability | B+ | Twelve operational public functions cover database values, queries, pulls, datoms, temporal views, identities, and transactions with ambient custody and error values. Missing work/result ceilings described by the target keep this below A. |
| Curated discovery | D | Only two `my.*` namespaces exist. The architecture catalog is not source, and stale docstrings actively misteach current behavior. |
| Real-work coverage | F | No fresh safe file read/edit, shell, web fetch/search, blob wrapper, plan, schedule, test request, or canvas action surface exists. |
| Effect integrity | D | The intended single owner is absent while loaded public compiled functions can perform HTTP, storage, and deletion directly. The situation is visible and fixable, but not yet one mechanism. |
| Historical lesson quality | A- | The quarry contains unusually strong lessons: exact editor refusal, honest bounds, SSRF defense, content addressing, derived plan state, and errors as values. It also contains enough duplicate mechanisms to make direct porting actively harmful. |

The correct near-term claim is therefore: **the fresh runtime can reason,
author program facts, query/transact the database, message peers, and settle a
run; it cannot yet safely perform the host/network work that makes those
abilities useful across the user's system.**

## Exit criteria for the toolkit wave

The toolkit should not graduate on namespace count. It graduates when:

- every genuine effect reaches exactly one `seon.effect/request!`, including
  currently callable compiled system effects;
- capability membership and workload derive from program facts/call edges,
  with no public-function maps or family enum;
- `my.fs`, `my.shell`, `my.web`, and `my.blob` return bounded ordinary data or
  flat error values and survive a process loss without unsafe refire;
- `my.ns` can query every public function/schema/call/test/owner relationship
  the program graph actually records;
- one real agent episode reads and edits source, runs the relevant test,
  fetches external evidence, stores the large result by digest, messages a
  peer, and completes; and
- a fresh restart can explain every committed request, receipt, result, and
  unfinished external effect from database facts without replaying a
  ledgerless operation.
