---
type: reference
status: active
tags: [architecture, vocabulary]
---

# Vocabulary

Use the library's word for what the library does; coin a Seon word only for what no library names. The order of
preference: Clojure's own name, then the closest dependency's (read its source under `reference-code/` and take its
name and its semantics), then, for a concept genuinely ours, one coined word recorded here once. Preserve literal
identifiers and historical quotations where accuracy needs them; do not carry retired spellings into new prose or
code. When you meet one, convert it in the same commit when in scope, otherwise file the issue.

[AGENTS.md "Words and where they come from"](../../../AGENTS.md) is the authority for the first table; this page
adds each library's source path, the git words, and Seon's own words. The approved renames (ledger §3.18, the CN entries,
and `script/seon/vocabulary.edn`, which the vocabulary check reads) apply to living documents now; code keeps its
old spelling until its CN rename converts it with every caller. One submission of forms is an **eval request**
(nREPL's word; code: `pass`, `seon.batch`, CN-20); its one transaction is the **recording transaction** (code:
`settlement`, CN-7); what returns is the **reply** (code: `envelope`, CN-8); the stored text is the **printed value**
(code: `shown`, CN-9).

## Concepts and their owning libraries

| Concept | Owner and source | Retired words |
|---|---|---|
| REPL round: read, eval, print, caught | `clojure.main/repl`, prepl, [nREPL](https://github.com/nrepl/nrepl) `eval` (`reference-code/clojure`, `reference-code/nrepl`) | pass, batch, own stage names |
| function schemas, instrumenting, validation, defaults | [Malli](https://github.com/metosin/malli): `m/-instrument`, `malli.instrument`, `m/validator`, `m/explain`, `malli.registry`, `default-value-transformer` (`reference-code/malli/src/malli`) | armed, projection, supply |
| transactions, branches, merge, data conversion | [Datahike](https://github.com/replikativ/datahike), our fork: `transact` → tx-report, `branch!`, `delete-branch!`, `merge!`, norms (`reference-code/datahike/src/datahike/versioning.cljc`, `norm/norm.clj`) | receipt, settlement, unlink, migration |
| interpreters for agents | [SCI](https://github.com/babashka/sci), our fork: `sci/init`, `sci/fork` (`reference-code/sci/src/sci/core.cljc`) | a second evaluator or reader |
| reading and editing Clojure text | [edamame](https://github.com/borkdude/edamame), [rewrite-clj](https://github.com/clj-commons/rewrite-clj) (`reference-code/edamame`, `reference-code/rewrite-clj`) | string or regex parsing of code |
| per-evaluation dynamic state | `binding`, `bound-fn*`, `with-bindings` (`clojure.core`) | world, environment |
| tests | `clojure.test` report maps, `malli.generator/check`, [kaocha](https://github.com/lambdaisland/kaocha) | member, accretion test |
| reloading code | `require :reload`, [clj-reload](https://github.com/tonsky/clj-reload) (`reference-code/clj-reload`) | own dependency ordering, adoption |
| static analysis | [clj-kondo](https://github.com/clj-kondo/clj-kondo) analysis and findings (`reference-code/clj-kondo`) | own analyzers |
| errors | `ex-info`, `ex-data`, `Throwable->map`, `clojure.main/ex-triage` | fault, own exception printers |
| printing | [fipp](https://github.com/brandonbloom/fipp) behind the one value printer (`reference-code/fipp`) | a second printer, `pr-str` copies |
| configuration files | [aero](https://github.com/juxt/aero): `aero.core/read-config` with `#profile`, `#env`, `#include`, `#ref`; Malli validates the result (`reference-code/aero`) | own EDN loaders |
| caching | [Caffeine](https://github.com/ben-manes/caffeine) through `seon.cache`, its `recordStats` | own counters, `memoize`, atoms |
| agent execution | `clojure.core.async.flow` procs and step-fns (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`) | own schedulers, state tags |
| durable bytes | [konserve](https://github.com/replikativ/konserve), our fork (`reference-code/konserve`) | own stores |
| merging | git, below | invented names |

## Git words

Datahike already speaks git (`branch!`, `delete-branch!`, `merge!`, `commit-id`, `parent-commit-ids` in
`reference-code/datahike/src/datahike/versioning.cljc`), so a merge uses git's words where git's meaning holds. The
reasoning and the rename slices live in [git-vocab](../../research/agent-platform/2026-10-08/git-vocab/git-vocab.md);
a word marked (target) names its rename, not yet merged.

| Term | Meaning | Retired words |
|---|---|---|
| branch | `branch!`: a named pointer to a commit; no copy | lane branch, worktree, fork of the database |
| main | a cluster's own branch, the line every accepted change is merged into; one per cluster, so a store has several | cluster head, home, "HEAD" for it |
| checked-out branch | the branch an agent's context holds (`my.agent/branch`, `git branch --show-current`); a new checkout is a new context | custody |
| merge, merged | `merge!` and git's merge: a source branch's changes enter the target branch, as a merge commit or a fast-forward; merged is the state after (owner 2026-10-12, CN-26) | land, landed, landing, unlanded |
| merge request | an assignment, as the thing root merges: source branch, target branch, merge base, outcome (`seon.merge/merge!`, `my.root/merge-requests`) | proposal, candidate |
| source branch, target branch | GitLab's pair: the branch proposed, the branch it is merged into | head and base (both mean something else here), the proposing branch |
| merge base | the newest common ancestor commit (`git merge-base`) | base digest |
| merge branch | the branch root's merge creates off the target, where tests run on the merged result before main moves | S, the candidate, the tested branch |
| ours, theirs | in a conflict, ours is the target and theirs is the source, as in `git merge`; Seon never rebases | head digest, branch digest, changed-on-head |
| unmerged | identities a conflict left unresolved on the merge branch; any write to one resolves it | conflict markers, unresolved |
| behind | the identities main modified since the merge base (`git diff branch...main`) | head-only |
| norm | Datahike's named, once-only data change (`:tx/norm`, `reference-code/datahike/src/datahike/norm/norm.clj`); a merge may carry one to convert main's entities | migration |
| delete branch | `delete-branch!`: the branch leaves `d/branches`, its commits wait for retention GC; "retire" is for agents only | unlink, retire a branch |
| commit | a Datahike commit with parents; the program part has its own content identity, the program digest | basis (for a commit) |
| squash merge | git's merge --squash as the copy-back: the three-way of declared cluster facts between the merge base, a source branch and main, merged as one plain transaction on main with no parent link to the source (`seon.merge/squash-merge`) | cherry-pick, data relay |

## Seon's words

Words no library names. Each has one meaning.

| Term | Meaning | Retired words |
|---|---|---|
| program context | the SCI context holding exactly the program one commit holds, frozen as a value (`seon.sci.eval/program-context`): keyed by program content in `seon.cache`, forked once per agent, never written; an agent's context is its fork plus its checked-out branch and its private layer | base context, overlay context, branch program copy, "environment" for the SCI context |
| task | the one family for work an agent does: an intent, linked facts, optional agent; a merge request is a task | issue, work packet |
| `:seon.cluster` | the entity-schema partition for facts copied onto main at an attempt's successful end, with conflict rules declared on the schema or its entries (`seon.program/cluster-attributes`); messages remain `:seon.data` | mergeable data roster |
| end copy | one transaction copying a successful attempt's cluster facts onto main, with source branch and commit as transaction metadata; an error end copies nothing | live relay |
| inbox | the Flow channel carrying message values to an agent; its recipient writes received messages on its own branch | stored transport |
| proof | a deftest naming a task in `:seon/tasks`; an Implement step requires its proofs green, and a Prove step requires its optional proofs green | gate (for a task's tests) |
| reaching test | a test whose current dependencies reach a changed definition (`my.program/tests-reaching`); differs from a test's recorded past `:seon.test/reach` | gate set, test closure |
| turn | the unit a model call answers: an agent's ordered evaluations and provider attempts, open until closed (`seon.turn`) | run (meaning one turn), `seon.cluster.run` |
| run | a budget of turns counted from a triggering message or task assignment to the reply or the cap; derived by query, never an entity (CN-19 retires "run" for one turn) | episode, session bound |
| render function | a function of the data that selects what to show: an AI/HTML pair per entity schema, read-only | producer, view, face |
| current-state block | the part of an agent's context rendered live from its record each turn | status tail |
| write-back | writing accepted declarations into source files, checking the files did not change since read, then reindexing (`seon.cluster.source/write-back!`) | export, push |
| wake | a listened event the agent has not yet answered (a message, a task change) | notification, trigger |
| answered t | `:seon.agent/answered-t`: the database basis `t` the agent has answered; wakes after it are due (Datomic's basis t) | answered basis, handled |
| subject, step | a subject task owns its step tasks as components (`:seon.task/children`); a step is an ordinary task with its own assignment, obligations and end, whose `:seon.task/step` is `:prove` or `:implement` (absent means `:implement`); a parent never runs and is done when its children are | stage (of one task), plan item, child task |
| product | the new red tests a Prove step supplies to its successors: the deftests it declares by symbol (`:seon.task/product`) and those tagged with tasks whose `:seon.task/needs` names it | proof-product entity |
| obligations | `seon.task/obligations` selects the step kind, its own tagged proofs and its successors' tagged product tests from one database value; Implement requires proofs, Prove requires product | duplicate proof selection |
| needs | `:seon.task/needs`: the tasks a task runs after; siblings without a need between them run in parallel; a join names several; inherited by descendants | after, position, depends-on |
| references | `:seon.task/references`: the qualified symbols a task's intent and instructions cite as code spans, derived by the task writer through the one reference parser (`seon.ref/names`) and re-derived whenever either changes (ingestion, `seon.task/create!`, `my.task/refine!`); never authored. A task is its name, its intent or instructions with inline references, and its optional step, children and needs | must-read, evidence, reading list |
| instructions | `:seon.task/instructions`: a step's own work, beside the goal (`:seon.task/intent`) its chain shares. A step's effective task (`seon.task/effective`) is `clojure.core/merge` of the intent and instructions declared from its root subject down `:seon.task/children` to it, its own keys last; content never merges along needs. `my.task/refine!` writes intent on the subject and instructions on the held step | handoff, per-step intent |
| ready | the derived set of tasks that should be running now: leaves, not done, needs done, intent permitting, no open attempt (code: `seon.task/ready`); never stored | frontier, ready list, queue, scheduler state |
| attempt | one assignment entity of a task to an agent on a branch, numbered within its task (`seon.id/id [handle number]`, `seon.task/attempt-tx`), ended once by its end fact | run (of a task), launch |
| intent | `:seon.task/execution`, the stored permission for an attempt: `:held` (never launched), `:running`, `:paused` (graph exists, nothing runs), `:stopped` (graph gone, resumable), `:cancelled` (terminal); cluster `:seon.cluster/execution` is `:running` or `:paused` | desired state, status, run state |
| db<->flow | the one Flow process in the cluster graph that links Datahike and Flow both ways (`seon.cluster.agent/db<->flow`): it reads the facts, asks `plan` what to do, writes the attempt first and makes Flow follow (start, resume, pause, stop); the only thing that starts agent graphs | reconciler, armer, scheduler, dispatcher, launcher |
| plan | the pure decision of db<->flow (`seon.cluster.agent/plan`): facts, the registry of running graphs and `:seon.config.agent/max-running` in, a vector of actions out (`:attempt`, `:start-flow`, `:problem`, `:pause`, `:stop`, `:cancel`) | arming pass, agents-to-arm |
| start-flow!, stop-flow! | create, start and (under intent `:running`) resume one agent's graph on its branch execution, recorded in the registry of running graphs under `:seon.agent/started`; its orderly stop and join (`seon.cluster.agent/start-flow!`, `stop-flow!`) | arm!, disarm!, arm an agent, arm (for contracts: instrument) |
| problem task | a task created by identity `[class subject]` when a problem is detected from facts (an end other than done, a send-back loop, a stale heartbeat, refused delivery, …), assigned to root or a fresh agent; its intent says what to do | alert, escalation, fault notification |
| clock | the one proc that writes a firing fact for every cron task at its due instant and calls a cron task's function when it has one | timer, schedule proc, `seon.schedule.task` |
| firing | `:seon.task.fire/*`: one due instant of a cron task, idempotent by `[task nominal-at]`; delivered to a live agent as a wake, else an attempt | tick, schedule fire |
| heartbeat | the cron task with a function that pings every graph and writes `:seon.agent/observed` on change and `:seon.cluster/heartbeat-t` per period; older than two periods reads as unknown | liveness probe, health poll |

## Other grounded terms

Standing order: update older code, docs and comments using a legacy spelling from the third column in the same
commit when in scope, or file the issue. The column is a recognition aid for reading, never an option for writing.
Entries marked **[TARGET]** describe a ruled integration not yet proven; update the entry with real source links when you
instantiate one.

| Term | Meaning and grounding | Legacy spellings |
|---|---|---|
| reference | Any span of prose that names exactly one entity; explicit backticks or resolve-only bare qualified symbols/keywords and path#anchor, anywhere in prose. `seon.ref` owns parsing/resolution; the entity’s declared render owns display (TASK-CHAIN §3). | reference as a display copy |
| functions, schemas, tests | ordinary Clojure constructs | verbs |
| database, `db` | the `seon.db` authority | store, inventory, memory |
| boot / environment / running | boot is the 0→1 construction in dependency order (REPL first); the environment is the one per-cluster value it produces (`seon.env` ↔ `resources/seon/schemas/seon.env.edn`), scoped per agent; running code receives it | the runtime, the platform, the tower, ambient |
| call preparation, supplied defaults | sci's hook seam supplying a function's declared-and-absent arguments from the environment; caller wins; unavailable is a flat error (`reference-code/sci/src/sci/core.cljc` init docstring) | ambient injection, batteries |
| **[TARGET] canvas** | the primary agent surface: the most recently updated by default, with a pin overriding recency. Target in [USER-SURFACES](../schedule/cards/USER-SURFACES.md); no installed canvas selection is claimed | tile, live-tile, world |
| surface; card (CSS only) | every HTML render an agent produces; a chat or schema-rendered data has its own surface. Card is only the CSS word | tile |
| web UI | `/`, `/ns/{namespace}`, `/agent/{id}`, their debug routes, and `/data` (`src/seon/render/route.clj`) | inspector |
| cluster | one database branch, its agents, and shared plumbing; produces one environment | environment (for the cluster itself) |
| attributes + connections | the Datahike model | entity kind/type |
| build, operator, artifact | the `bin/seon`/`bin/acme` supervisor scope; the digested publication output | flavor |
| get-in, path | paged navigation into a nested value | drill |
| **[TARGET] namespace agent**, `:seon.ns/agents` | An agent responsible for a namespace: its recorded errors, tasks and requests render into that agent's context. Responsibility is many-to-many, a cardinality-many ref set on the namespace (owner ruling 2026-09-19, D1); `:seon.agent/namespace` remains the REPL's current namespace, a different fact. Routing never uses membership: a trigger maps to a TASK (D2). Plan: `docs/prds/steward-platform/plan/namespace-agents-plan-2026-09-19.md` | steward, `:seon.ns/steward`, `steward-call` |
| **[TARGET] task**, `seon.task` | The one family for work an agent does: linked facts (subject refs, tests, errors, functions) plus an optional agent. A "template" is the render pair and units the task's linked data selects, never an entity or a registry; a detected defect is a task whose subject came from a detector; a conversation is derived from `seon.message` facts. A trigger resolves to a task identity at the writer: an existing task's agent receives the occurrence as a wake, otherwise the task is created and an agent spun up (owner ruling 2026-09-19, D1–D2) | issue (as the family name), `seon.issue`, `my.task`, task template, work packet |
| provider descriptor entity | one hosted provider's entity under the config singleton | adapter, integration |
| packages/, package.json, deps.edn | each ecosystem's own manifest names | npm-pkgs, maven-pkgs |
| contexts on hosts, binding tables | sci's own vocabulary for agent execution | sandbox, VM, jail |
| `:interrupt-fn` | the ONE zero-arg fn sci calls on every fn body entrance and `loop/recur` (`reference-code/sci/doc/interrupt.md` ↔ `src/seon/sci/eval.clj`) | the guard, the door, the cage |
| `interrupt!` | stops an eval uncatchably (`reference-code/sci/src/sci/interrupt.cljc`) | stop!, steering-error! |
| `time-limit` | the SCI execution deadline; query-work and AI presentation have separate bounds (`reference-code/sci/doc/interrupt.md`) | fuel, gas, step budget |
| `:seon.eval/fn-entries` | a RECORDED FINDING, never a limit | a step budget |
| every `fn` body entrance | where sci calls the `:interrupt-fn` | safepoint |
| `ctx`, `fork` | sci's own names (`reference-code/sci/src/sci/core.cljc`) | warm base, the agent's world |
| `:io` / `:compute` / `:mixed` | core.async's workload tags: `:io` may block but not compute, `:compute` must not block (`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj`) | eval pool, wait pool |
| entity, entity map, datom, transaction data | Datahike's terms: a datom is `[e a v tx added?]`; an entity is what a reference names, unique by its identity attribute: the datoms sharing one `e` (a pull returns it as a map); transaction data is the entity maps and `[:db/add e a v]` a writer submits. A function, namespace, schema declaration or config is an entity identified by its identity attribute (`:seon.fn/sym`, `:seon.ns/name`, `:seon.schema/key`, `:seon.config/cluster`) | row (owner 2026-10-12, CN-25), program row, schema row, config row |
| **[TARGET] session** | A chat between an ordinary user agent and another agent: explicit participant refs, layout render, ordered slots and optional canvas pin; messages name their session. It never sits between an agent and its evaluations. [USER-SURFACES](../schedule/cards/USER-SURFACES.md) owns this entity; an agent’s ongoing evaluator is its REPL. | session (for the REPL relationship), view entity |
| **[TARGET] slot** | A stable named position in a session’s ordered layout: a selector over session data plus the render that draws its result. Selection and rendering share read evidence; retained calls are identified by session and slot. [USER-SURFACES](../schedule/cards/USER-SURFACES.md). | tile, fixed page section |
| accretion / breakage | a change that requires no more and provides no less | graduation, nursery |
| **[TARGET]** source initialization entities, transaction data | Static source population is admitted transaction data. The static prefix is `(help)`; there is no opening or input eval request (agent-loop.md §3.4). | bootstrap-plan rows, seed bundle |
| process identity, (pid, start-instant) | exact OS identity used by the client and boot advertisement (`script/seon/operator.clj` ↔ `src/seon/cluster/process.clj`) | orphan registry, liveness flag |
| message subject | The nonempty string identity token supplied in `:my.message/about`, stored verbatim in `:seon.message/about`; no target lookup is required. Assignment/declination uses `:seon.message/assignment` independently (`src/seon/cluster/message.clj`, `resources/seon/schemas/seon.message.edn`; [program-facts PRD §§1h–1i](../../../docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md)). | about as subject, inside marker, and protocol correlation together |
| `seon.effect`, `effect/request!` | the system-side owner for declared capability requests (fs, web, llm); database writes enter `seon.db/transact!` (`src/seon/db.clj`) — about effects crossing out, never about which functions an agent may call | the door, capability dispatch |
| every function is callable | an agent may call ANY function in its cluster's program graph; what differs per agent is only what is RENDERED into its context, which never gates execution | toolkit, grants, allowlist |
| program graph | the collective `:seon.fn`/`:seon.ns`/`:seon.schema`/`:seon.test` facts | corpus |
| `my.program` | Agent-facing program reads over one supplied database value; the existing `seon.fn`, `seon.db` and `seon.issue` owners supply selection, facts and detected issue identity (`src/my/program.clj`). | my.refactor, my.code |
| `my.program/breaks` | Pure read of a subject's referrers, declaration spans, current gate set, advisory past reach and explicit unknowns, with a computed plan (`my.program/breaks`; Datahike reverse index: `reference-code/datahike/src/datahike/query.cljc`). Render pairs come from their materialized property datoms. No write or launch. | impact, blast radius |
| `my.program/callers` | Direct `:seon.fn/calls` referrers with the caller declaration's byte span and recorded argument counts (`my.program/callers`). `:seon.fn/references` stays a separate relation. | usages, dependents |
| `my.program/reads-key` | Contract references, declared writes and literal keyword mentions, as separate groups; mentions do not assert a read or block retraction (`my.program/reads-key`; `seon.fn/functions-using`). | keyword consumers |
| `my.program/history` | Source assertion/retraction events with exact root datom values and transaction provenance; optional `:seon.db/tx` selects the requested as-of definition, including metadata changed after its source assertion (`my.program/history`; `reference-code/datahike/src/datahike/db.cljc`, `as-of-pred`). | definition archive |
| referrer; caller; reference; subject; reach | Referrer is a live entity naming the subject; caller means `:seon.fn/calls`, prose references are recorded in `:seon.fn/references`, test subject is a present claim, and reach is advisory evidence from a past run. The program read preserves these relations separately (`resources/seon/schemas/seon.program.edn`). | dependency (without its attribute) |
| redefinition; retraction | Redefinition replaces definition facts at one identity; retraction removes facts and leaves the past to history/as-of (`seon.program/exact-replacement-tx`, Datahike `retractEntity`). On an agent branch, a REPL-valid change returns with findings for surviving dangling referrers; root enforces integrity at diff and merge (agent-loop.md §1.1; merge.md §2). | soft delete, retirement |
| proc, step-fn, conns, graph-def | `clojure.core.async.flow`'s own vocabulary (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`, `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`) | invented scheduler nouns |
| `(sliding-buffer 1)` tap | core.async's own newest-only delivery | latest-wins mailbox |
| tuple (`:db/tupleType`) | Datahike's single-value ordered construct; cardinality-many is a SET (`reference-code/datahike/src/datahike/index/persistent_set.cljc`) | small limited vector |
| `my.agents.<id>` | the DEFAULT namespace for a temp agent only; real agents own namespaces anywhere. ASSIGNMENT IS NOT IDENTITY: `:seon.agent/namespace` is not unique (`resources/seon/schemas/seon.agent.edn`) — several agents may share one, and an agent may `in-ns` anywhere its REPL reaches. Namespace responsibility follows the target many-to-many `:seon.ns/agents` relation in D1; it is separate from this REPL namespace | agent workspace, sandbox ns |
| **[TARGET]** render function | A function returning data for AI or hiccup for HTML. Selection follows the value override, a matching function in the viewer’s namespace, then the most specific declared schema pair. The form face returns a requery form as data and never executes it. Every render output passes through the one value printer (AGENTS.md “Presentation”). | producer, view, executable render output |
| `:seon.render/ai` | The AI render attachment: a function or rendered data. Its result is data for the value printer, never generated source executed by a system turn. Historical printed values stay as saved (AGENTS.md “Return data, not comments; renders return data”). | separate teaching prose, text render |
| `:seon.render/html` | the Hiccup consumed by the web UI, or the symbol naming its function (`src/seon/render/hiccup.clj`) | hiccup contract |
| live result object | The full evaluation result, reached through `result/e<id>`. Installed code retains results in the SCI context; the approved target holds them once in `seon.cache` under its byte budget. Database facts keep small facts and printed text, with larger printed text in blobs. A restart loses the live value; its handle reports that it is gone (agent-loop.md §3.4). | result serialization, restorable node |
| wire (external crossings only) | a crossing that LEAVES the process (provider HTTP, browser SSE); internal transport is channels, flow, facts | wire (internal) |
| namespace page | one namespace's web surface: route → namespace → owner agent → walk in `/html` (`src/seon/render/route.clj`) | page, screen, dashboard |
| **[TARGET]** block | Rendered data for one whole concern. Scalars share the owning value; components and declared derived queries supply related values. Every output goes through the value printer. The current-state block is read fresh from stored facts at each model call (agent-loop.md §3.4; AGENTS.md “Presentation”). | widget, component, panel, scalar block |
| package, keyframe, delta | the delivery units: one revisioned package per change; a revision gap snaps to keyframe | frame, bundle |
| base SCI context / agent SCI context / prompt | The cluster's program-only context derived by `seon.sci.eval/base-ctx` from one database value; each agent's retained handle receives a new fork with its private objects reapplied before later turns; the ordered rendering of its stored evaluations. Neither private objects nor prompt visibility gates callability ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/sci/eval.clj`; SCI `init`: `reference-code/sci/src/sci/core.cljc`, `fork`: `reference-code/sci/src/sci/core.cljc`, `intern`: `reference-code/sci/src/sci/core.cljc`) | turn fork, "the context" for all three |
| override | A function identity whose current admission is `:agent` in an indexed `src` namespace; `seon.program/overrides` queries current admission and historical declaration/file relations from one database value (`src/seon/program.cljc`). It is never a stored flag. | override flag, namespace kind |
| candidate context | a built context used to test a definition before installing it; `sci/fork` is admissible (copy-on-write Vars) | sandbox ctx, scratch fork |
| render profile | The presentation policy applied by the value renderer once at evaluation time; the evaluation stores the resulting printed value. History never clips again; HTML renders the live object without presentation clipping, or saved text after restart ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `resources/seon/schemas/seon.render.profile.edn`) | cap, window, separate result storage bound |
| elision value | ordinary data describing omitted count, path, next offset, and requery identity (`resources/seon/schemas/seon.print.edn` ↔ `src/seon/print.cljc`) | ellipsis, truncation marker |
| elision form | the runnable form shown at a cut: plain clojure.core (`drop`, `take`, `nth`, `get-in`, `subs`, `keys`, `count`) on the live result symbol, lazy first, no Seon helper (owner 2026-10-08; code converts in PR-02, PR-10) | dig form |
| `:seon.fn/external-sink`, `:seon.fn/projection-boundary` | queryable program-graph leaf facts; `seon.fn/output-path-report` derives projected/bypass/unresolved paths (`resources/seon/schemas/seon.fn.edn` ↔ `src/seon/fn.clj`) | sink roster, output allowlist |
| **[TARGET] root maintenance portfolio** | root's declared scheduled reclamation/inspection/repair tasks (current operation owner: [seon.maintenance](../../../src/seon/maintenance.clj); scheduling remains a target); update this entry when the owners are merged | maintenance daemon |
| private layer | The agent's defs and atoms as actual objects in its persistent SCI context, isolated from the base and other agents, lost on JVM restart. Installed functions, schemas, and tests are durable program facts ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); agent acquisition: `src/seon/cluster/agent.clj`; SCI isolation: `reference-code/sci/src/sci/core.cljc`) | `:seon.def/*`, session image, restored defs |
| evaluation entity | The stored facts of one evaluated form, attached to the agent in agent order with the call that produced it. It carries source and observed evidence; the result value stays live in the runtime, while its printed value is saved inline or in a blob (agent-loop.md §3.1/§3.4). Legacy code keys remain until their CN rename converts them. | frozen form entity, receipt, form-identity |
| the agent's history | Append-only history of the agent's own forms and results, using saved printed values. A static prefix and one fresh current-state block surround it; no opening, input or refresh eval request is inserted (agent-loop.md §3.4). | system-generated history, refreshed history |
| `doc`, `dir` | REPL documentation as DATA from public program entities: `dir` returns symbol, arglists, first docstring line, and input/output contract; `doc` returns the full docstring and contract ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/sci/eval.clj`, `src/seon/sci/eval.clj`) | faces tool, print face, separate teaching prose |
| `seon.id/id`, `seon.id/digest`, `seon.id/evaluation` | THE ONE IDENTITY DERIVATION: `id` hashes the value's structure with SHA-256, default length 12 or supplied length; zero arguments mint a fresh event. Equal maps and sets have equal ids regardless of order; vectors and lists differ (`src/seon/id.clj:246-261`). `digest` and `evaluation` call it ([owner](../../../src/seon/id.clj)). Ordinary turn IDs include branch, agent, and turn ordinal ([next-id](../../../src/seon/turn.clj)); their evaluation id is `(seon.id/evaluation turn ordinal)` because the turn already includes its branch, and the handle is `(seon.id/symbol-in "result" \e id)` ([handle](../../../src/seon/sci/admit.clj)). The explicit branch/turn/ordinal arity also remains available. `random-uuid` only for a genuinely fresh EVENT with no identity of its own. Never a new generator, never a second truncation (owner, 2026-09-08) | short-id, nanoid, hand-rolled hashes, `(str (random-uuid))` for things that have parts |
| read evidence | The dependencies a read observed, used to reuse a derivation while its inputs remain current. It does not authorize reexecuting historical forms or appending refresh evaluations; writes and effects are never replayed (agent-loop.md §3.4; AGENTS.md “Reuse content-valid work; derive without stamps”). | copied read result, inbox-only refresh |
| printed value | The exact value-printer output shown at evaluation time and saved with the observation. It includes elisions and retrieval forms and survives restart, but cannot restore the live value. Legacy `shown` keys keep their code spelling until CN-9 renames them (agent-loop.md §3.4). | result EDN, serialized result, second printed copy |
| candidates (per-render selection) | the contract-fitting render function selection consulted per render call (`src/seon/render.clj`) | roster, acquired index |
| **program entities** | the declared program partition on a branch: functions, tests, schemas, namespaces, render pairs, contracts, analysis facts and test results keyed by the definition digests they exercised; everything else is data and never merges. Declared as `:seon.program/partition` on entity schemas, read by `seon.program/program-attributes` ([data-modeling guide](data-modeling-guide.md#9-how-a-malli-declaration-becomes-datahike-schema)) | a hand list, a stamped kind |
| **[TARGET] host-bound entity** | a declaration SCI cannot interpret, computed per declaration | a namespace roster |
