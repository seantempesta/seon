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
adds each library's source path, the git words, and Seon's own words.

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
a word marked (target) names its rename, not yet landed.

| Term | Meaning | Retired words |
|---|---|---|
| branch | `branch!`: a named pointer to a commit; no copy | lane branch, worktree, fork of the database |
| main | a cluster's own branch, the line every accepted change lands on; one per cluster, so a store has several | cluster head, home, "HEAD" for it |
| checked-out branch | the branch an agent's context holds (`my.agent/branch`, `git branch --show-current`); a new checkout is a new context | custody (internal word in `seon.db/call-with-custody` only) |
| merge request | a merge task asking root to merge a source branch into a target branch; a new commit from the same source updates it | proposal, candidate |
| source branch, target branch | GitLab's pair: the branch proposed, the branch it lands on | head and base (both mean something else here), the proposing branch |
| merge base | the newest common ancestor commit (`git merge-base`) | base digest |
| merge branch | the branch root's merge creates off the target, where tests run on the merged result before main moves | S, the candidate, the tested branch |
| ours, theirs | in a conflict, ours is the target and theirs is the source, as in `git merge`; Seon never rebases | head digest, branch digest, changed-on-head |
| unmerged | identities a conflict left unresolved on the merge branch; any write to one resolves it | conflict markers, unresolved |
| behind | the identities main modified since the merge base (`git diff branch...main`) | head-only |
| norm | Datahike's named, once-only data change (`:tx/norm`, `reference-code/datahike/src/datahike/norm/norm.clj`); a merge may carry one to migrate main's rows | migration |
| delete branch | `delete-branch!`: the branch leaves the roster, its commits wait for retention GC; "retire" is for agents only | unlink, retire a branch |
| commit | a Datahike commit with parents; the program part has its own content identity, the program digest | basis (for a commit) |

## Seon's words

Words no library names. Each has one meaning.

| Term | Meaning | Retired words |
|---|---|---|
| task | the one family for work an agent does: an intent, linked facts, optional agent; a merge request is a task | issue, work packet |
| proof | a deftest naming a task in `:seon/tasks`; a task completes when its proofs are green | gate (for a task's tests) |
| reaching test | a test whose current dependencies reach a changed definition (`my.program/tests-reaching`); differs from a test's recorded past `:seon.test/reach` | gate set, test closure |
| turn | the unit a model call answers: an agent's ordered evaluations and provider attempts, open until closed (`seon.turn`) | run (meaning one turn), `seon.cluster.run` |
| run | a budget of turns counted from a triggering message or task assignment to the reply or the cap; derived by query, never an entity (CN-19 retires "run" for one turn) | episode, session bound |
| render function | a function of the data that selects what to show: an AI/HTML pair per entity schema, read-only | producer, view, face |
| current-state block | the part of an agent's context rendered live from its record each turn | status tail |
| write-back | writing accepted declarations into source files, checking the files did not change since read, then reindexing (`my.program/write-back!`) | export, push |
| wake | a listened event the agent has not yet answered (a message, a task change) | notification, trigger |
| answered t | `:seon.agent/answered-t`: the database basis `t` the agent has answered; wakes after it are due (Datomic's basis t) | answered basis, handled |

## Other grounded terms

Standing order: update older code, docs and comments using a legacy spelling from the third column in the same
commit when in scope, or file the issue. The column is a recognition aid for reading, never an option for writing.
Rows marked **[TARGET]** describe a ruled integration not yet proven; update the row with real source links when you
instantiate one.

| Term | Meaning and grounding | Legacy spellings |
|---|---|---|
| functions, schemas, tests | ordinary Clojure constructs | verbs |
| database, `db` | the `seon.db` authority | store, inventory, memory |
| boot / environment / running | boot is the 0→1 construction in dependency order (REPL first); the environment is the one per-cluster value it produces (`seon.env` ↔ `resources/seon/schemas/seon.env.edn`), scoped per agent; running code receives it | the runtime, the platform, the tower, ambient |
| call preparation, supplied defaults | sci's hook seam supplying a function's declared-and-absent arguments from the environment; caller wins; unavailable is a flat error (`reference-code/sci/src/sci/core.cljc` init docstring) | ambient injection, batteries |
| **[TARGET] canvas** | the focal agent surface; design lives in `docs/seon/architecture/ui.md`; no declared attribute exists yet — update this row when it lands | tile, live-tile, world |
| surface; card (CSS only) | a context render; a visual component | tile |
| web UI | `/`, `/ns/{namespace}`, `/agent/{id}`, their debug routes, and `/data` (`src/seon/render/route.clj`) | inspector |
| cluster | one database branch, its agents, and shared plumbing; produces one environment | environment (for the cluster itself) |
| attributes + connections | the Datahike model | entity kind/type |
| build, operator, artifact | the `bin/seon`/`bin/acme` supervisor scope; the digested publication output | flavor |
| get-in, path | paged navigation into a nested value | drill |
| task steps, `:seon.task/steps` | An agent's ordered plan: step components on its task at the task home (position, text, status, done-tx), written with `my.task/plan!`, `step!`, `done!` and shown in the agent's opening through `:seon.task/of-agent` (`src/seon/task.clj`, `src/my/task.clj`, `src/seon/render/ns.clj`); persists across compaction | `my.plan`, todo, bare "plan" for turn sources |
| **[TARGET] namespace agent**, `:seon.ns/agents` | An agent responsible for a namespace: its faults, tasks and requests render into that agent's context. Responsibility is many-to-many, a cardinality-many ref set on the namespace (owner ruling 2026-09-19, D1); `:seon.agent/namespace` remains the REPL's current namespace, a different fact. Routing never uses membership: a trigger maps to a TASK (D2). Plan: `docs/prds/steward-platform/plan/namespace-agents-plan-2026-09-19.md` | steward, `:seon.ns/steward`, `steward-call` |
| **[TARGET] task**, `seon.task` | The one family for work an agent does: linked facts (subject refs, tests, errors, functions) plus an optional agent. A "template" is the render pair and units the task's linked data selects, never an entity or a registry; a detected defect is a task whose subject came from a detector; a conversation is derived from `seon.message` facts. A trigger resolves to a task identity at the writer: an existing task's agent receives the occurrence as a wake, otherwise the task is created and an agent spun up (owner ruling 2026-09-19, D1–D2) | issue (as the family name), `seon.issue`, `my.task`, task template, work packet |
| provider descriptor entity | one hosted provider's entity under the config singleton | adapter, integration |
| packages/, package.json, deps.edn | each ecosystem's own manifest names | npm-pkgs, maven-pkgs |
| contexts on hosts, binding tables | sci's own vocabulary for agent execution | sandbox, VM, jail |
| `:interrupt-fn` | the ONE zero-arg fn sci calls on every fn body entrance and `loop/recur` (`reference-code/sci/doc/interrupt.md` ↔ `src/seon/sci/eval.clj`) | the guard, the door, the cage |
| `interrupt!` | stops an eval uncatchably (`reference-code/sci/src/sci/interrupt.cljc`) | stop!, steering-error! |
| `time-limit` | the SCI execution deadline; query-work and AI presentation have separate bounds (`reference-code/sci/doc/interrupt.md`) | fuel, gas, step budget |
| `:seon.eval/fn-entries` | a RECORDED DIAGNOSTIC, never a limit | a step budget |
| every `fn` body entrance | where sci calls the `:interrupt-fn` | safepoint |
| `ctx`, `fork` | sci's own names (`reference-code/sci/src/sci/core.cljc`) | warm base, the agent's world |
| `:io` / `:compute` / `:mixed` | core.async's workload tags: `:io` may block but not compute, `:compute` must not block (`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj`) | eval pool, wait pool |
| entity, entity map, datom, transaction data | Datahike's terms: a datom is `[e a v tx added?]`; an entity is the datoms sharing one `e` (a pull returns it as a map); transaction data is the entity maps and `[:db/add e a v]` a writer submits. A function, namespace, schema declaration or config is an entity identified by its identity attribute (`:seon.fn/sym`, `:seon.ns/name`, `:seon.schema/key`, `:seon.config/cluster`) | row, program row, schema row, config row |
| session **[TARGET]** | The agent's ongoing REPL: one per agent, attached to its record; holds the current namespace (a ref, written once by each turn's settlement from where SCI ended; a request may set it) and the ordered turns that are its context forms. The agent-data-model plan ([repl-session-entity-design](../../research/agent-platform/repl-session-entity-design-2026-09-27.md)) owns its storage; nREPL's session holds `*ns*` the same way ([prior art](../../research/agent-platform/repl-session-prior-art-2026-09-27.md)) | runtime (for the session), SCI world (for its stored state) |
| accretion / breakage | a change that requires no more and provides no less | graduation, nursery |
| **[TARGET]** source initialization entities, transaction data | Static source population is admitted transaction data; the agent's opening is separately evaluated and stored as system turn 0 ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/bootstrap.clj`) | bootstrap-plan rows, seed bundle |
| process identity, (pid, start-instant) | exact OS identity used by the client and boot advertisement (`script/seon/operator.clj` ↔ `src/seon/cluster/process.clj`) | orphan registry, liveness flag |
| message subject | The nonempty string identity token supplied in `:my.message/about`, stored verbatim in `:seon.message/about`; no target lookup is required. Assignment/declination uses `:seon.message/assignment` independently (`src/seon/cluster/message.clj`, `resources/seon/schemas/seon.message.edn`; [program-facts PRD §§1h–1i](../../../docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md)). | about as subject, inside marker, and protocol correlation together |
| `seon.effect`, `effect/request!` | the system-side owner for declared capability requests (fs, web, llm); database writes enter `seon.db/transact!` (`src/seon/db.clj`) — about effects crossing out, never about which functions an agent may call | the door, capability dispatch |
| every function is callable | an agent may call ANY function in its cluster's program graph; what differs per agent is only what is RENDERED into its context, which never gates execution | toolkit, grants, allowlist |
| program graph | the collective `:seon.fn`/`:seon.ns`/`:seon.schema`/`:seon.test` facts | corpus |
| `my.program` | Agent-facing program reads over one supplied database value; the existing `seon.fn`, `seon.db` and `seon.issue` owners supply selection, facts and detected issue identity (`src/my/program.clj`). | my.refactor, my.code |
| `my.program/breaks` | Pure read of a subject's referrers, declaration spans, current gate set, advisory past reach and explicit unknowns, with a computed plan ([[my.program/breaks]]; Datahike reverse index: `reference-code/datahike/src/datahike/query.cljc`). Render pairs come from their materialized property datoms. No write or launch. | impact, blast radius |
| `my.program/callers` | Direct `:seon.fn/calls` referrers with the caller declaration's byte span and recorded argument counts ([[my.program/callers]]). `:seon.fn/references` stays a separate relation. | usages, dependents |
| `my.program/reads-key` | Contract references, declared writes and literal keyword mentions, as separate groups; mentions do not assert a read or block retraction ([[my.program/reads-key]]; `seon.fn/functions-using`). | keyword consumers |
| `my.program/history` | Source assertion/retraction events with exact root datom values and transaction provenance; optional `:seon.db/tx` selects the requested as-of definition, including metadata changed after its source assertion ([[my.program/history]]; `reference-code/datahike/src/datahike/db.cljc`, `as-of-pred`). | definition archive |
| referrer; caller; reference; subject; reach | Referrer is a live entity naming the subject; caller means `:seon.fn/calls`, reference means `:seon.fn/references`, test subject is a present claim, and reach is advisory evidence from a past run. The program read preserves these relations separately (`resources/seon/schemas/seon.program.edn`). | dependency (without its attribute) |
| redefinition; retraction | Redefinition replaces definition facts at one identity; retraction removes facts and leaves the past to history/as-of (`seon.program/exact-replacement-tx`, Datahike `retractEntity`). Surviving named referrers refuse deletion unless repaired in the same transaction. | soft delete, retirement |
| proc, step-fn, conns, graph-def | `clojure.core.async.flow`'s own vocabulary (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`, `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`) | invented scheduler nouns |
| `(sliding-buffer 1)` tap | core.async's own newest-only delivery | latest-wins mailbox |
| tuple (`:db/tupleType`) | Datahike's single-value ordered construct; cardinality-many is a SET (`reference-code/datahike/src/datahike/index/persistent_set.cljc`) | small limited vector |
| `my.agents.<id>` | the DEFAULT namespace for a temp agent only; real agents own namespaces anywhere. ASSIGNMENT IS NOT IDENTITY: `:seon.agent/namespace` is not unique (`resources/seon/schemas/seon.agent.edn`) — several agents may share one, and an agent may `in-ns` anywhere its REPL reaches. Namespace responsibility follows the target many-to-many `:seon.ns/agents` relation in D1; it is separate from this REPL namespace | agent workspace, sandbox ns |
| **[TARGET]** render function | A function of the data that chooses its forms. ONE AI/HTML pair per entity schema, never per scalar attribute; when no pair is declared, use the default attribute-map printer. Evaluation entities render saved shown text through `seon.repl/render-ai` and `render-html` ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj`) | producer, view, read form, `/form` face, `:seon.render/form` |
| `:seon.render/ai` | The entity's AI projection. Generated source is evaluated in an ordinary system turn; historical evaluations render stored shown text without executing their forms. The walk and evaluation schema pair share `seon.repl/text` as the REPL grammar ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj`) | separate teaching prose, text render, "the string consumed by agent context" |
| `:seon.render/html` | the Hiccup consumed by the web UI, or the symbol naming its function (`src/seon/render/hiccup.clj`) | hiccup contract |
| live result object | The actual result retained by evaluation id in the agent's SCI context, bound directly by `result/e<id>`. It is not serialized, admitted as a stored node, or restored after restart ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); binding: `src/seon/sci/eval.clj`; SCI `intern`: `reference-code/sci/src/sci/core.cljc`) | result serialization, stored print node, result blob, restorable node |
| wire (external crossings only) | a crossing that LEAVES the process (provider HTTP, browser SSE); internal transport is channels, flow, facts | wire (internal) |
| namespace page | one namespace's web surface: route → namespace → owner agent → walk in `/html` (`src/seon/render/route.clj`) | page, screen, dashboard |
| **[TARGET]** block | One entity rendered through its schema pair, covering a whole concern. Scalars share its own block; components and declared derived queries supply their blocks. The identified output is the HTML morph target ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/render/block.clj`) | widget, component, panel, scalar block |
| package, keyframe, delta | the delivery units: one revisioned package per change; a revision gap snaps to keyframe | frame, bundle |
| base SCI context / agent SCI context / prompt | The cluster's program-only context derived by `seon.sci.eval/base-ctx` from one database value; each agent's retained handle receives a new fork with its private objects reapplied before later turns; the ordered rendering of its stored evaluations. Neither private objects nor prompt visibility gates callability ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/sci/eval.clj`; SCI `init`: `reference-code/sci/src/sci/core.cljc`, `fork`: `reference-code/sci/src/sci/core.cljc`, `intern`: `reference-code/sci/src/sci/core.cljc`) | turn fork, "the context" for all three |
| override | A function identity whose current admission is `:agent` in an indexed `src` namespace; `seon.program/overrides` queries current admission and historical declaration/file relations from one database value (`src/seon/program.cljc`). It is never a stored flag. | override flag, namespace kind |
| candidate context | a built context used to test a definition before installing it; `sci/fork` is admissible (copy-on-write Vars) | sandbox ctx, scratch fork |
| render profile | The presentation policy applied by the value renderer once at evaluation time; the evaluation stores the resulting shown text. History never clips again; HTML renders the live object without presentation clipping, or saved text after restart ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `resources/seon/schemas/seon.render.profile.edn`) | cap, window, separate result storage bound |
| elision value | ordinary data describing omitted count, path, next offset, and requery identity (`resources/seon/schemas/seon.print.edn` ↔ `src/seon/print.cljc`) | ellipsis, truncation marker |
| `:seon.fn/external-sink`, `:seon.fn/projection-boundary` | queryable program-graph leaf facts; `seon.fn/output-path-report` derives projected/bypass/unresolved paths (`resources/seon/schemas/seon.fn.edn` ↔ `src/seon/fn.clj`) | sink roster, output allowlist |
| **[TARGET] root maintenance portfolio** | root's declared scheduled reclamation/inspection/repair tasks (current operation owner: [seon.maintenance](../../../src/seon/maintenance.clj); scheduling remains a target); update this row when the owners land | maintenance daemon |
| private layer | The agent's defs and atoms as actual objects in its persistent SCI context, isolated from the base and other agents, lost on JVM restart. Installed functions, schemas, and tests are durable program facts ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); agent acquisition: `src/seon/cluster/agent.clj`; SCI isolation: `reference-code/sci/src/sci/core.cljc`) | `:seon.def/*`, session image, restored defs |
| **[TARGET]** evaluation entity | One `:seon.eval` entity per branch/turn/ordinal identity, carrying source and outcome evidence including shown text, out, error, and read evidence. Its actual result stays in memory; identity derives through `seon.id/evaluation` ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/id.clj`) | `:seon.cluster.eval`, `:seon.cluster.run.form/*`, frozen form entity, receipt, `form-identity` |
| the agent's history | The walk rendering `(seon.eval/of-agent db agent)` (`src/seon/eval.clj`) in chronological turn order and ordinal through the evaluation schema's pair, from stored shown text. All turns are shown by default; previous prompt bytes remain unchanged until compaction ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj`) | transcript, transcript entries, session units, run-form facts |
| `doc`, `dir` | REPL documentation as DATA from public program entities: `dir` returns symbol, arglists, first docstring line, and input/output contract; `doc` returns the full docstring and contract ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/sci/eval.clj`, `src/seon/sci/eval.clj`) | faces tool, print face, separate teaching prose |
| `seon.id/id`, `seon.id/digest`, `seon.id/evaluation` | THE ONE IDENTITY DERIVATION: `id` hashes `(pr-str data)` with SHA-256, default length 12 or supplied length; zero arguments mint a fresh event. `digest` and `evaluation` call it ([owner](../../../src/seon/id.clj)). Ordinary turn IDs include branch, agent, and turn ordinal ([next-id](../../../src/seon/turn.clj)); their evaluation id is `(seon.id/evaluation turn ordinal)` because the turn already includes its branch, and the handle is `(seon.id/symbol-in "result" \e id)` ([handle](../../../src/seon/sci/admit.clj)). The explicit branch/turn/ordinal arity also remains available. `random-uuid` only for a genuinely fresh EVENT with no identity of its own. Never a new generator, never a second truncation (owner, 2026-09-08) | short-id, nanoid, hand-rolled hashes, `(str (random-uuid))` for things that have parts |
| read evidence | The dependency plans and revisions a read observed, used with its evaluation `:t` to detect changed facts. Every distinct generated or agent-written read participates; writes and effects never rerun ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/db.clj`, `src/seon/db.clj`) | copied read result, inbox-only refresh |
| shown text | The exact value-renderer text the agent saw at evaluation time, saved with source, out, and error. It includes profile elisions and requery forms, survives restart, and cannot restore the live object ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/sci/eval.clj`, `src/seon/repl.clj`) | result EDN, stored print node, serialized result |
| candidates (per-render selection) | the contract-fitting render function selection consulted per render call (`src/seon/render.clj`) | roster, acquired index |
| **program entities** | the declared program partition on a branch: functions, tests, schemas, namespaces, render pairs, contracts, analysis facts and test results keyed by the definition digests they exercised; everything else is data and never merges. Declared as `:seon.program/partition` on entity schemas, read by `seon.program/program-attributes` ([data-modeling guide](data-modeling-guide.md#9-how-a-malli-declaration-becomes-datahike-schema)) | a hand list, a stamped kind |
| **[TARGET] host-bound entity** | a declaration SCI cannot interpret, computed per declaration | a namespace roster |
