---
type: reference
status: active
tags: [architecture, vocabulary]
---

# Vocabulary — grounded names, never invented ones

Use the actual operation or value when speaking and writing: SCI context,
SCI evaluation, JVM REPL, agent turn, effect execution, Datahike branch,
database value, and boot sequence. Do not use a metaphor as their common name.
Use **evaluation** and **result** in prose, not "receipt". The **[TARGET]** binding turn PRD
§15 requires ONE `:seon.eval` entity per branch/turn/ordinal, carrying source,
shown text, out, error, and read evidence. The live result is not a second
durable entity. Legacy `:seon.cluster.eval`, `:seon.cluster.run.form/*`, and
`receipt` identifiers are source references during the owning lane's cut;
they never justify a second entity or a duplicated attribute.
In particular, the MCP evaluation modes are `jvm` (the host REPL) and `sci`
(**SCI evaluation mode**: the cluster's shared SCI context). Preserve literal tool arguments,
identifiers and historical quotations where accuracy requires them, but do not
carry those spellings into new prose. Rendering functions are functions;
dependency-specific producers/consumers remain producers/consumers when that is
what the dependency actually calls them.

Inventing new vocabulary causes serious system problems: invented nouns
drift from the dependency, hide existing mechanisms, and poison every later
reader. The law, in order of preference:

1. **Use Clojure's own name for the concept**; else
2. **the closest integration seam's name** — Datahike, Malli, SCI,
   core.async already named their things; read the seam's source in
   `reference-code/` and take its name AND its semantics;
3. only when a concept is genuinely ours, coin once, record it here with
   sources on BOTH sides of the boundary, and use it everywhere.

## Ruled 2026-09-22 — one JVM, many realities (owner; written progressively, only what is grounded)

| term | is | grounded in | never |
|---|---|---|---|
| **branch** | `datahike.api/branch!`: a roster pointer to a commit; no copy, no environment | `reference-code/datahike/src/datahike/versioning.cljc:212` (`branch!`), `:279` (`delete-branch!`); Seon callers `src/seon/cluster/registry.clj:178` (`branch!`), `src/seon/cluster/store.clj:534` (`open-branch!`) | "worktree", "fork of the database" |
| **cluster** | one Datahike branch plus the agents working on it; the cluster row holds the pointer we advance | `resources/seon/schemas/seon.cluster.edn`; `src/seon/cluster/registry.clj` (the `cluster-default` branch) | an "environment" to start |
| **context** | the SCI world a cluster or agent evaluates in; a child is `sci/fork` | `reference-code/sci/src/sci/core.cljc:345` (`fork`); `src/seon/sci/eval.clj:2293` (`fork-cluster-ctx`, forks onto another connection) | "environment", "refork" |
| **loaded namespaces** | `require`'s output: the JVM's compiled Vars, derived from the files | Clojure `clojure.core/require`, `*loaded-libs*`; Seon's reload site `src/seon/cluster.clj:1983` | "compiled cache", "the runtime" |
| **reload** | `(require ns :reload)` of changed namespaces and their dependents, nothing more | Clojure; `src/seon/cluster.clj:1983` inside `refresh-source!` (`:2170`) | a system to restart |
| **unlink** | `registry/retire-branch!`: the branch leaves the roster; the retention sweep collects its data later | `src/seon/cluster/registry.clj:327` (`retire-branch!`), `:503` (`collect!`) | a per-branch cleanup |
| **[TARGET] program rows** | the declared program partition on a branch (functions, tests, schemas, namespaces, render pairs, contracts, analysis facts, and test results keyed by the definition digests they exercised — owner 2026-09-23); everything else is data | no partition fact exists yet — [program rows data pack](../../research/agent-platform/program-rows-data-pack-2026-09-22.md) §3 proposes `:seon.program/partition` on entity schemas | a hand list; a stamped kind |
| **[TARGET] host-bound row** | a declaration SCI cannot interpret, a computed per-declaration fact | `:seon.fn/defined-by` exists (`resources/seon/schemas/seon.fn.edn`); the body-reference half is discarded today — [host-bound data pack](../../research/agent-platform/host-bound-rows-data-pack-2026-09-22.md) §3 | a namespace roster |
| **[TARGET] live / isolated** | an agent's mode = which branch its custody points at | no agent branch attribute exists yet; custody seam `src/seon/db.clj:370` (`call-with-custody`) | "sandbox" |
| **[TARGET] merge** | program rows from a branch onto a cluster head via an intermediate branch, the gate, then a named accept; Datahike records the lineage | `versioning.cljc:734` (`merge!`, lineage only, caller supplies tx-data) — [merge data pack](../../research/agent-platform/merge-and-write-back-data-pack-2026-09-22.md) | "sync", "promote" |

Never assume you understand a row from its name alone: follow its links and
read that slice of code before building against it — that is how we avoid
rebuilding what a core library already built. Rows marked **[TARGET]** describe a ruled integration not yet proven complete
(the linked PRD sections own the target): design toward them with this vocabulary, and when you
instantiate one, update its row with real source links in the same commit.

**Standing order — retire drift on sight:** when you meet older code, docs,
or comments using a legacy spelling from this table, update them to the
current term in the same commit when in scope, or file the issue when not.
Deferring this is how garbage accumulates. Newly ruled terms land in this
table in the same turn they are ruled.

The third column lists legacy spellings you may still meet in older
material — they are recognition aids for reading, never options for
writing.

| Term | Meaning and grounding | Legacy spellings |
|---|---|---|
| functions, schemas, tests | ordinary Clojure constructs | verbs |
| database, `db` | the `seon.db` authority | store, inventory, memory |
| boot / environment / running | boot is the 0→1 construction in dependency order (REPL first); the environment is the one per-cluster value it produces (`seon.env` ↔ `resources/seon/schemas/seon.env.edn`), scoped per agent; running code receives it | the runtime, the platform, the tower, ambient |
| call preparation, supplied defaults | sci's hook seam supplying a function's declared-and-absent arguments from the environment; caller wins; unavailable is a flat error (`reference-code/sci/src/sci/core.cljc` init docstring) | ambient injection, batteries |
| **[TARGET] canvas** | the focal agent surface; design lives in `docs/seon/architecture/ui.md`; no declared attribute exists yet — update this row when it lands | tile, live-tile, world |
| surface; card (CSS only) | a context render; a visual component | tile |
| web UI | `/`, `/ns/{namespace}`, `/agent/{id}`, their debug routes, and `/data` (`src/seon/render/route.clj:5`) | inspector |
| cluster | one database branch, its agents, and shared plumbing; produces one environment | environment (for the cluster itself) |
| attributes + connections | the Datahike model | entity kind/type |
| build, operator, artifact | the `bin/seon`/`bin/acme` supervisor scope; the digested publication output | flavor |
| get-in, path | paged navigation into a nested value | drill |
| `my.plan`, "the plan" | The task system's authored plan facts and derived obligations; its render function chooses forms from current data and its writes return the changed entity ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `resources/seon/schemas/seon.agent.edn:151` declares the plan component; `src/my/plan.clj` follows it) | todo, bare "plan" for turn sources |
| **[TARGET] namespace agent**, `:seon.ns/agents` | An agent responsible for a namespace: its faults, tasks and requests render into that agent's context. Responsibility is many-to-many, a cardinality-many ref set on the namespace (owner ruling 2026-09-19, D1); `:seon.agent/namespace` remains the REPL's current namespace, a different fact. Routing never uses membership: a trigger maps to a TASK (D2). Plan: `docs/prds/steward-platform/plan/namespace-agents-plan-2026-09-19.md` | steward, `:seon.ns/steward`, `steward-call` |
| **[TARGET] task**, `seon.task` | The one family for work an agent does: linked facts (subject refs, tests, errors, functions) plus an optional agent. A "template" is the render pair and units the task's linked data selects, never an entity or a registry; a detected defect is a task whose subject came from a detector; a conversation is derived from `seon.message` facts. A trigger resolves to a task identity at the writer: an existing task's agent receives the occurrence as a wake, otherwise the task is created and an agent spun up (owner ruling 2026-09-19, D1–D2) | issue (as the family name), `seon.issue`, `my.task`, task template, work packet |
| provider descriptor row | one hosted provider's data row under the config singleton | adapter, integration |
| packages/, package.json, deps.edn | each ecosystem's own manifest names | npm-pkgs, maven-pkgs |
| contexts on hosts, binding tables | sci's own vocabulary for agent execution | sandbox, VM, jail |
| `:interrupt-fn` | the ONE zero-arg fn sci calls on every fn body entrance and `loop/recur` (`reference-code/sci/doc/interrupt.md` ↔ `src/seon/sci/eval.clj`) | the guard, the door, the cage |
| `interrupt!` | stops an eval uncatchably (`reference-code/sci/src/sci/interrupt.cljc`) | stop!, steering-error! |
| `time-limit` | the SCI execution deadline; query-work and AI presentation have separate bounds (`reference-code/sci/doc/interrupt.md`) | fuel, gas, step budget |
| `:seon.eval/fn-entries` | a RECORDED DIAGNOSTIC, never a limit | a step budget |
| every `fn` body entrance | where sci calls the `:interrupt-fn` | safepoint |
| `ctx`, `fork` | sci's own names (`reference-code/sci/src/sci/core.cljc`) | warm base, the agent's world |
| `:io` / `:compute` / `:mixed` | core.async's workload tags: `:io` may block but not compute, `:compute` must not block (`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj`) | eval pool, wait pool |
| turn | An agent's ordered evaluations and any provider attempts; open means no `:seon.turn/closed-tx`, enforced at the writer. `seon.turn` owns the transitions; its schema declares the history render pair ([open?](../../../src/seon/turn.clj:189), [open-call](../../../src/seon/turn.clj:348), [schema](../../../resources/seon/schemas/seon.turn.edn:1)); process provenance rides execution requests, never the turn entity. | run, `seon.cluster.run`, `:seon.cluster.run/process` |
| accretion / breakage | a change that requires no more and provides no less | graduation, nursery |
| **[TARGET]** source initialization rows, transaction data | Static source population is admitted transaction data; the agent's opening is separately evaluated and stored as system turn 0 ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/bootstrap.clj`) | bootstrap-plan rows, seed bundle |
| process identity, (pid, start-instant) | exact OS identity used by the client and boot advertisement (`script/seon/operator.clj` ↔ `src/seon/cluster/process.clj`) | orphan registry, liveness flag |
| system turn | An ordinary turn with a reply and no provider attempt; "system" is derived, never stamped. `seon.turn/system-turn` computes the opening and changed reads and optionally stores their evaluations ([owner](../../../src/seon/turn.clj:2191), [debug controls](../../../src/seon/render/web.clj:709)); the wake-answering `:t` rule remains specified by [turn PRD §14](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md) and implemented at `src/seon/turn.clj:2823`. | generated opening episode, generated run |
| message subject | The nonempty string identity token supplied in `:my.message/about`, stored verbatim in `:seon.message/about`; no target lookup is required. Assignment/declination uses `:seon.message/assignment` independently (`src/seon/cluster/message.clj`, `resources/seon/schemas/seon.message.edn`; [program-facts PRD §§1h–1i](../../../docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md)). | about as subject, inside marker, and protocol correlation together |
| inside wake | Population activity that cannot refill the recipient's turn bound. For messages, `:seon.message/from` is the sole marker, independent of subject or protocol; `wake/inside-wake?` consumes the declaration (`src/seon/cluster/wake.clj`, `resources/seon/schemas/seon.message.edn`). | subject presence implies inside |
| handled | A claim ref from the handling turn, written by `seon.turn/close-call` at settlement on `:seon.turn/handled`; message routing remains on listened `:seon.message/to` (`resources/seon/schemas/seon.message.edn`). Whether a wake is answered derives only from its `:t` and `seon.turn/latest-answering-turn-t`, independently of the handling claim. | inbox-edge retraction, read-tx |
| turn loop | The per-agent Flow proc derives work from database facts, advances open/call/evaluations/close, and rewakes when work remains. Its proc and transitions share `seon.turn` ([step](../../../src/seon/turn.clj:4933), [next-agent-work](../../../src/seon/turn.clj:2753), [turn](../../../src/seon/turn.clj:4723)); the agent owner supplies its [graph](../../../src/seon/cluster/agent.clj:422). The full additive-context algorithm remains specified by [turn PRD §14–§16](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md). | run loop, driver, driving |
| `seon.effect`, `effect/request!` | the system-side owner for declared capability requests (fs, web, llm); database writes enter `seon.db/transact!` (`src/seon/db.clj:3213`) — about effects crossing out, never about which functions an agent may call | the door, capability dispatch |
| every function is callable | an agent may call ANY function in its cluster's program graph; what differs per agent is only what is RENDERED into its context, which never gates execution | toolkit, grants, allowlist |
| program graph | the collective `:seon.fn`/`:seon.ns`/`:seon.schema`/`:seon.test` facts | corpus |
| `my.program` | Agent-facing program reads over one supplied database value; the existing `seon.fn`, `seon.db` and `seon.issue` owners supply selection, facts and detected issue identity (`src/my/program.clj`). | my.refactor, my.code |
| `my.program/breaks` | Pure read of a subject's referrers, declaration spans, current gate set, advisory past reach and explicit unknowns, with a computed plan (`src/my/program.clj:238`; Datahike reverse index: `reference-code/datahike/src/datahike/query.cljc`). Render pairs come from their materialized property datoms. No write or launch. | impact, blast radius |
| `my.program/callers` | Direct `:seon.fn/calls` referrers with the caller declaration's byte span and recorded argument counts (`src/my/program.clj:129`). `:seon.fn/references` stays a separate relation. | usages, dependents |
| `my.program/tests-reaching`; gate set | Current test selection through `seon.fn/gate-set`, including call, reference and declared-subject edges (`src/my/program.clj:144`, `src/seon/fn.clj`). This differs from a test's recorded past `:seon.test/reach`. | test closure |
| `my.program/reads-key` | Contract references, declared writes and literal keyword mentions, as separate groups; mentions do not assert a read or block retraction (`src/my/program.clj:183`; `seon.fn/functions-using`). | keyword consumers |
| `my.program/history` | Source assertion/retraction events with exact root datom values and transaction provenance; optional `:seon.db/tx` selects the requested as-of definition, including metadata changed after its source assertion (`src/my/program.clj:311`; `reference-code/datahike/src/datahike/db.cljc`, `as-of-pred`). | definition archive |
| referrer; caller; reference; subject; reach | Referrer is a live entity naming the subject; caller means `:seon.fn/calls`, reference means `:seon.fn/references`, test subject is a present claim, and reach is advisory evidence from a past run. The program read preserves these relations separately (`resources/seon/schemas/seon.program.edn`). | dependency (without its attribute) |
| plan of a refusal; detector; `seon.issue/subject-id` | One prospective issue per caller, identified by the detector plus the caller's installed identity value using the generator's same `seon.issue/subject-id`; tests come from the caller's gate set (`src/seon/issue.clj`, `src/my/program.clj`). `seon.program/unresolved-callers` is the target done condition. Launch is unavailable until the detector can truthfully represent the repair subjects; the read states that limitation. | work packet, separate task registry |
| redefinition; retraction | Redefinition replaces definition facts at one identity; retraction removes facts and leaves the past to history/as-of (`seon.program/exact-replacement-tx`, Datahike `retractEntity`). Surviving named referrers refuse deletion unless repaired in the same transaction. | soft delete, retirement |
| proc, step-fn, conns, graph-def | `clojure.core.async.flow`'s own vocabulary (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:78`, `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:165`) | invented scheduler nouns |
| `(sliding-buffer 1)` tap | core.async's own newest-only delivery | latest-wins mailbox |
| tuple (`:db/tupleType`) | Datahike's single-value ordered construct; cardinality-many is a SET (`reference-code/datahike/src/datahike/index/persistent_set.cljc`) | small limited vector |
| `my.agents.<id>` | the DEFAULT namespace for a temp agent only; real agents own namespaces anywhere. ASSIGNMENT IS NOT IDENTITY: `:seon.agent/namespace` is not unique (`resources/seon/schemas/seon.agent.edn:79`) — several agents may share one, and an agent may `in-ns` anywhere its REPL reaches. Namespace responsibility follows the target many-to-many `:seon.ns/agents` relation in D1; it is separate from this REPL namespace | agent workspace, sandbox ns |
| **[TARGET]** render function | A function of the data that chooses its forms. ONE AI/HTML pair per entity schema, never per scalar attribute; when no pair is declared, use the default attribute-map printer. Evaluation entities render saved shown text through `seon.repl/render-ai` and `render-html` ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj:387`) | producer, view, read form, `/form` face, `:seon.render/form` |
| `:seon.render/ai` | The entity's AI projection. Generated source is evaluated in an ordinary system turn; historical evaluations render stored shown text without executing their forms. The walk and evaluation schema pair share `seon.repl/text` as the REPL grammar ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj:213`) | separate teaching prose, text render, "the string consumed by agent context" |
| `:seon.render/html` | the Hiccup consumed by the web UI, or the symbol naming its function (`src/seon/render/hiccup.clj`) | hiccup contract |
| live result object | The actual result retained by evaluation id in the agent's SCI context, bound directly by `result/e<id>`. It is not serialized, admitted as a stored node, or restored after restart ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); binding: `src/seon/sci/eval.clj:513`; SCI `intern`: `reference-code/sci/src/sci/core.cljc:260`) | result serialization, stored print node, result blob, restorable node |
| wire (external crossings only) | a crossing that LEAVES the process (provider HTTP, browser SSE); internal transport is channels, flow, facts | wire (internal) |
| namespace page | one namespace's web surface: route → namespace → owner agent → walk in `/html` (`src/seon/render/route.clj`) | page, screen, dashboard |
| **[TARGET]** block | One entity rendered through its schema pair, covering a whole concern. Scalars share its own block; components and declared derived queries supply their blocks. The identified output is the HTML morph target ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/render/block.clj:61`) | widget, component, panel, scalar block |
| package, keyframe, delta | the delivery units: one revisioned package per change; a revision gap snaps to keyframe | frame, bundle |
| base SCI context / agent SCI context / prompt | The cluster's program-only context derived by `seon.sci.eval/base-ctx` from one database value; each agent's retained handle receives a new fork with its private objects reapplied before later turns; the ordered rendering of its stored evaluations. Neither private objects nor prompt visibility gates callability ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/sci/eval.clj:1709`; SCI `init`: `reference-code/sci/src/sci/core.cljc:331`, `fork`: `reference-code/sci/src/sci/core.cljc:345`, `intern`: `reference-code/sci/src/sci/core.cljc:260`) | turn fork, "the context" for all three |
| override | A function identity whose current admission is `:agent` in an indexed `src` namespace; `seon.program/overrides` queries current admission and historical declaration/file relations from one database value (`src/seon/program.cljc:20`). It is never a stored flag. | override flag, namespace kind |
| candidate context | a built context used to test a definition before installing it; `sci/fork` is admissible (copy-on-write Vars) | sandbox ctx, scratch fork |
| compaction | Wipe the agent's evaluations; the next system turn regenerates the opening from current record facts using the same algorithm. There is no manual curation path ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/turn.clj:2207`) | editor, revision, proof, curation, supersession |
| render profile | The presentation policy applied by the value renderer once at evaluation time; the evaluation stores the resulting shown text. History never clips again; HTML renders the live object without presentation clipping, or saved text after restart ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `resources/seon/schemas/seon.render.profile.edn`) | cap, window, separate result storage bound |
| elision value | ordinary data describing omitted count, path, next offset, and requery identity (`resources/seon/schemas/seon.print.edn` ↔ `src/seon/print.cljc`) | ellipsis, truncation marker |
| `:seon.fn/external-sink`, `:seon.fn/projection-boundary` | queryable program-graph leaf facts; `seon.fn/output-path-report` derives projected/bypass/unresolved paths (`resources/seon/schemas/seon.fn.edn` ↔ `src/seon/fn.clj`) | sink roster, output allowlist |
| **[TARGET] root maintenance portfolio** | root's declared scheduled reclamation/inspection/repair tasks (current operation owner: [seon.maintenance](../../../src/seon/maintenance.clj); scheduling remains a target); update this row when the owners land | maintenance daemon |
| **[TARGET] `my.branch`** | agent-facing branch/history functions over database branches — git vocabulary without claiming to be git (current branch/isolation plan: [D1](../../../docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md); this surface is not a claim of implementation); update this row when it lands | my.git, my.repo |
| private layer | The agent's defs and atoms as actual objects in its persistent SCI context, isolated from the base and other agents, lost on JVM restart. Installed functions, schemas, and tests are durable program facts ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); agent acquisition: `src/seon/cluster/agent.clj:636`; SCI isolation: `reference-code/sci/src/sci/core.cljc:345`) | `:seon.def/*`, session image, restored defs |
| **[TARGET]** evaluation entity | One `:seon.eval` entity per branch/turn/ordinal identity, carrying source and outcome evidence including shown text, out, error, and read evidence. Its actual result stays in memory; identity derives through `seon.id/evaluation` ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/id.clj:55`) | `:seon.cluster.eval`, `:seon.cluster.run.form/*`, frozen form entity, receipt, `form-identity` |
| the agent's history | The walk rendering `(seon.eval/of-agent db agent)` (`src/seon/eval.clj:9`) in chronological turn order and ordinal through the evaluation schema's pair, from stored shown text. All turns are shown by default; previous prompt bytes remain unchanged until compaction ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj:213`) | transcript, transcript entries, session units, run-form facts |
| `doc`, `dir` | REPL documentation as DATA from public program rows: `dir` returns symbol, arglists, first docstring line, and input/output contract; `doc` returns the full docstring and contract ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/sci/eval.clj:1194`, `src/seon/sci/eval.clj:1222`) | faces tool, print face, separate teaching prose |
| `seon.id/id`, `seon.id/digest`, `seon.id/evaluation` | THE ONE IDENTITY DERIVATION: `id` hashes `(pr-str data)` with SHA-256, default length 12 or supplied length; zero arguments mint a fresh event. `digest` and `evaluation` call it ([owner](../../../src/seon/id.clj:1)). Ordinary turn IDs include branch, agent, and turn ordinal ([next-id](../../../src/seon/turn.clj:452)); their evaluation id is `(seon.id/evaluation turn ordinal)` because the turn already includes its branch, and the handle is `(seon.id/symbol-in "result" \e id)` ([handle](../../../src/seon/sci/admit.clj:614)). The explicit branch/turn/ordinal arity also remains available. `random-uuid` only for a genuinely fresh EVENT with no identity of its own. Never a new generator, never a second truncation (owner, 2026-09-08) | short-id, nanoid, hand-rolled hashes, `(str (random-uuid))` for things that have parts |
| read evidence | The dependency plans and revisions a read observed, used with its evaluation `:t` to detect changed facts. Every distinct generated or agent-written read participates; writes and effects never rerun ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/db.clj:682`, `src/seon/db.clj:849`) | copied read result, inbox-only refresh |
| shown text | The exact value-renderer text the agent saw at evaluation time, saved with source, out, and error. It includes profile elisions and requery forms, survives restart, and cannot restore the live object ([turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/sci/eval.clj:1957`, `src/seon/repl.clj:52`) | result EDN, stored print node, serialized result |
| candidates (per-render selection) | the contract-fitting render function selection consulted per render call (`src/seon/render.clj`) | roster, acquired index |

