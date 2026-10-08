---
type: research
status: notes-only
date: 2026-10-08
lane: research-agenda
tags: [architecture, vocabulary, research, agent-loop]
---

# Research agenda: use Clojure's mechanisms and names

Seon already composes SCI evaluation, Datahike transactions, Malli contracts, Flow processes, clj-kondo analysis
and Fipp layout. The smallest approach is to keep those owners, remove duplicate derivations and translations,
and name each remaining operation after the value or library function it actually uses. This is research, not a
replacement platform design or permission to change code.

Owner, verbatim: "identify key areas we need to properly research. I want us to make sure we are doing things the
clojure way and using proper vocab and names for other libraries and references like git. do not pretend you are
writing ruby/python/whatever and reuse those concepts."

## Evidence and limits

**Broken first.** Both `bin/seon status` and MCP `runtime_status` for `default` answered on this lane. The process
was available, but the cluster reported `:faulted`, six open faults and twelve regressions at basis 536871967,
commit `6ac72bdf-e8e1-5d08-b6df-1b0d3b9a6a0b`. Reported existing task identities included:

| Subject | Existing task IDs from health |
|---|---|
| `seon.render.ns/task-ai`, missing task id in its returned value | `68f9fc8b8da1` |
| merge acceptance / branch connection still held | `1f67a74c7846`, `fd586f8c0e99` |
| `seon.db/transact-call`, missing namespace entity | `8ab559348c1a` |
| `seon.flow/var-process`, Boolean treated as seq | `dbf4c0eff700` |
| `seon.turn.world/record-fault`, recording against the wrong branch | `3d2fb1b41dc8` |

No task names were guessed from these IDs. They are existing findings, not failures caused by this notes-only lane.
Status reported about 602 MB live heap, 10.997 GB maximum heap and 146 MB estimated cache weight against a 512 MB
cache bound. Shell status took 0.10 s; readiness's saved 9,854 ms is a prior boot measurement, not this audit's cost.
No new model run, mutation probe, reset or reload was performed. Source evidence below proves source behavior,
**not installation in that JVM**. Performance rankings below are research priorities, not new profile results.

Initial Seon HEAD: `5ea9783a8`. Read the requested CLAUDE.md, vocabulary guide, owner rulings and git-vocab note;
surveyed `src/`, `resources/seon/schemas/`, and `docs/seon/specs/`. The audit covers every requested candidate and
additional concept families found there. **UNVERIFIED: literal completeness over every invented word in every
docstring and historical spec.** A lexical search cannot prove that; this is a concept audit, not a claim to have
read every line of the repository. Repeated spellings share a row; distinct meanings get separate decisions.

Sources are repository-relative `file:line`. Line numbers describe the checked-out source at inspection and may
move under other lanes. Pins matched `git submodule status` (the unrelated http-kit checkout had untracked data):

| Dependency | Checked-out revision |
|---|---|
| Clojure | `3bc2b3e91fdff462620d84bbdfd40379d9c562e5` |
| SCI | `ce0aa53b9bf35029e0bc27a962d799c196a37874` |
| Datahike | `e5453d967b749794944dbdfe64b751519983ba59` |
| Malli, maintained fork | `43c6dca8d2d8df3162b13725939954807be4a4ed` |
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051` |
| nREPL | `f3a044153eaa6050e7447655dab5bc42cfdd68c2` |
| clj-kondo | `57252e07975710aa579b24f0d1b2b1e04195caa2` |
| clj-reload | `61c6fa77855efc0b4d052f06c7cc8b2b8817c4aa` |
| Fipp / Puget | `255a19061fdc20d67eca09a17e9af282aa8bf909` / `f959e034ddf9c4910e290efe6b0eb49048ff1bec` |
| Kaocha | `8846f91c9bf4338c561ffb866b5a8890e22889cd` |
| editscript / rewrite-clj | `b493ccf2e987fed84a05badb2626eebae4b91328` / `60782e501aaf312cb90c9ff0bee05d5da5125563` |
| konserve | `8700ca7a95784bbe5d6fb4dce1d7bc2ad96cbc37` |

No standalone `reference-code/core.cache`, `core.memoize`, `tools.namespace`, `orchestra` or `orchestra-like`
checkout was found. Orchestra prior art is present through Kaocha's plugin. Supplementary cache and namespace
sources were read directly from local Maven jars without extracting or altering them:

- `~/.m2/repository/org/clojure/core.cache/1.1.234/core.cache-1.1.234.jar!/clojure/core/cache.clj:20`
  (`CacheProtocol`), `:605` (`lru-cache-factory`, **element-count**, not byte-weight bound);
  `!/clojure/core/cache/wrapped.clj:38` (`lookup-or-miss`, at-most-once value computation during retries).
- `~/.m2/repository/org/clojure/core.memoize/1.1.266/core.memoize-1.1.266.jar!/clojure/core/memoize.clj:247`
  (`memoizer`), `:278` (`memo`), `:398` (`lru`).
- `~/.m2/repository/org/clojure/tools.namespace/1.5.1/tools.namespace-1.5.1.jar!/clojure/tools/namespace/dependency.cljc:14`
  (dependency graph), `:110` (`graph`); `!/clojure/tools/namespace/repl.clj:169` (`refresh`), `:189` (`refresh-all`).

These are inspected artifact versions, **not verified loaded versions**. Do not fabricate reference-code paths for
them. Git comparisons below are grounded in the requested git-vocab note and Datahike's actual branch/merge
implementation; Git's own source was not vendored or audited here.

## 1. Vocabulary audit

KEEP means the name is already established in the relevant domain. RENAME means replace it in the stated sense,
not mechanically across all senses. JUSTIFIED means Seon adds a real distinction the cited library does not own.
That is not a claim the current implementation is necessary or correct. Recommendations below do not override an
owner ruling: notably internal `call-with-custody` is explicitly retained by the git-vocab ruling.

### REPL and recording

| Seon term and source | Decision and established term | Prior art and semantic boundary |
|---|---|---|
| pass, `docs/seon/specs/agent-loop.md:116` | **RENAME → evaluation request** when it means the submitted sequence of forms | `reference-code/nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:72`: evaluator consumes one message's code, evaluates serially. Keep ordinary algorithmic “one pass” for a traversal. One form is an evaluation, not the whole request. |
| turn, `resources/seon/schemas/seon.turn.edn:44` | **JUSTIFIED** for one conversational exchange; **RENAME → evaluation request** when merely an alias for execution | nREPL `interruptible_eval.clj:72` already names the execution. A model conversation has a distinct producer/response grouping; it does not need another durable entity solely because prose calls it a turn. |
| run, `src/seon/run.clj:15`; `src/seon/test.clj:1` | **KEEP** for a test run; **RENAME → evaluation request** for one submission of forms | `reference-code/clojure/src/clj/clojure/test.clj:797` has `run-test-var`; nREPL evaluator above. `seon.run` currently contains a walkthrough, not the proposed run-fold implementation. Namespace existence is not implementation evidence. |
| call, `src/seon/call_preparation.clj:5` | **KEEP**, qualified as function call or model call | `reference-code/malli/src/malli/core.cljc:2235` applies the wrapped function; `reference-code/clojure/src/clj/clojure/main.clj:400` names evaluation separately. Do not use one call counter for both meanings without naming its unit. |
| settlement, settle, `src/seon/turn/batch.clj:729` | **RENAME → record! / recording transaction**, with `tx-report` for its database answer | `reference-code/datahike/src/datahike/api/impl.cljc:134` returns a transaction report from `with`; `reference-code/datahike/src/datahike/versioning.cljc:738` documents the merge transaction report. Recording includes application facts, so it is not just a renamed raw report. |
| receipt, `src/seon/render/transcript.clj:247` | **RENAME → evaluation** for stored evaluation facts; **result** for the live return | `reference-code/clojure/src/clj/clojure/core/server.clj:194` distinguishes form/result/output; `docs/seon/architecture/vocabulary.md:11` already rejects receipt. A recording acknowledgement can be called a transaction report only if it actually is one. |
| envelope, `docs/seon/specs/agent-loop.md:151` | **RENAME → response map** | `reference-code/nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:156` sends `:ns` and `:value`; prepl `core/server.clj:200` emits a return map. No extra protocol abstraction follows from having a map. |
| batch, `src/seon/batch.clj:687` | **KEEP** as a finite batch of forms; use evaluation request at the entrance | nREPL `interruptible_eval.clj:93` accepts a code string or iterable of forms. Batch does not imply transactional rollback of form effects. |
| fold, `docs/seon/specs/run.md:8` | **KEEP** as reduction; prefer **reduce** when that is the operation | `reference-code/clojure/src/clj/clojure/core.clj:6951` defines reduce. Do not imply `clojure.core.reducers/fold` parallel semantics for sequential REPL evaluation. |
| session, `docs/seon/architecture/vocabulary.md:95` | **KEEP** for retained REPL bindings/context | `reference-code/nrepl/src/clojure/nrepl/middleware/session.clj:161`, `:305` create and route sessions. This does not establish that Seon needs a session entity between the agent and evaluations. |
| shown text, `resources/seon/schemas/seon.eval.edn:2`; `src/seon/repl.clj:6` | **JUSTIFIED**: exact saved printer output; use **printed value** as the plain gloss | prepl `core/server.clj:275` separates io-prepl printing from live `:val`. Saved rendered/elided output cannot honestly be called the live result or serialized result. |
| live result, `src/seon/sci/admit.clj:691` | **KEEP**, evaluation result | `reference-code/clojure/src/clj/clojure/main.clj:439` assigns `*1`; prepl `core/server.clj:243` carries the return value before printing. |
| handle, `result/e…`, `src/seon/sci/admit.clj:691` | **JUSTIFIED** stable **result symbol**; prefer that phrase to opaque “handle” | `reference-code/sci/src/sci/core.cljc:260` interns values; `clojure/main.clj:439` rotates `*1/*2/*3`. Stable old references need more than three rotating Vars. Do not rename them to `*1` and break old references. |
| elision / elision form, `src/seon/print.cljc:349` | **JUSTIFIED** omission plus a runnable expression for omitted data | `reference-code/fipp/src/fipp/edn.cljc:9` supports print length/level, but not result retrieval. The expression itself uses ordinary Clojure (`get-in`, `drop`, `take`, etc.), not a new navigation language. |
| opening / opening episode, `src/seon/run.clj:15`; `src/seon/turn.clj:252` | **RENAME → help / initial context**; delete generated opening evaluations under the newer ruling | `reference-code/clojure/src/clj/clojure/main.clj:376` separates `:init` from evaluation; `docs/seon/specs/agent-loop.md:217` says no opening pass. Help is information, not an invented prior act by the agent. |
| current-state block, `docs/seon/specs/agent-loop.md:220` | **JUSTIFIED**, fresh **agent state** in prompt assembly | Flow `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:191` exposes state via ping; neither Flow nor nREPL specifies task/proof/message context for a model. Never imply it is stored history. |
| history / transcript, `src/seon/render/transcript.clj:247` | **KEEP**, distinguish REPL history from database history | Datahike `reference-code/datahike/src/datahike/api/impl.cljc:185` supplies temporal history; nREPL sessions retain bindings, not this durable transcript. Renaming transcript to history alone does not remove its translation machinery. |

### Contexts, contracts and lifecycle

| Seon term and source | Decision and established term | Prior art and semantic boundary |
|---|---|---|
| custody, `src/seon/db.clj:430` | **RENAME → checked-out branch** in agent text; **connection binding** when describing the helper; internal name retained by the owner | `reference-code/datahike/src/datahike/api/impl.cljc:145` dereferences a connection; `reference-code/clojure/src/clj/clojure/main.clj:449` binds REPL Vars. The helper binds conn/read/receipt state, not a security capability. Git-vocab §2 explicitly keeps internal custody. |
| world, `src/seon/turn/world.clj:1`; `resources/seon/schemas/seon.batch.edn:2` | **RENAME → evaluation context** for the aggregate; say **SCI context** for its `ctx` member | SCI `reference-code/sci/src/sci/core.cljc:320` calls its own value a context. Seon's aggregate additionally has connection, writer, agent, task and observations; do not pass that whole map as though it were SCI's ctx. |
| supply, `src/seon/call_preparation.clj:8` | **JUSTIFIED → supplied defaults**, the existing clearer name | `reference-code/clojure/src/clj/clojure/main.clj:417` has explicit options/defaults; Malli `core.cljc:2210` validates arguments but does not fetch missing members by schema identity. “Dependency injection” would hide this specific rule. |
| arm / armed contract, `src/seon/sci/arm.clj:37`; `src/seon/instrument.clj:981` | **RENAME → instrument / instrumented** | `reference-code/malli/src/malli/core.cljc:3166` is `-instrument`, with input/output/guard checking at `:2210`. Keep Seon's attribution and default preparation separate from the wrapper's validation. |
| arm agent / disarm agent, `src/seon/cluster.clj:3383`, `:3490` | **RENAME → start / stop agent graph**, if that is the actual operation | Flow `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:108` starts a flow and `:123` stops it; its process lifecycle is distinct from Malli instrumentation. Rename only after checking whether “stop” waits for exit. |
| projection, `src/seon/schema.clj:589`, `:2313` | **RENAME → schema registry** only for the registry member; **JUSTIFIED** for the larger schema-derived value | `reference-code/malli/src/malli/registry.cljc:11`, `:55` own registry lookup/composition. Seon's projection also carries forms, fingerprints, compiled schemas and storage derivations; a global substitution would lie. |
| base context, `src/seon/sci/eval.clj:3230` | **KEEP → base SCI context** | `reference-code/sci/src/sci/core.cljc:320`, `:334`: init, then fork. “Base” names which context is shared, not another evaluator. |
| overlay, `src/seon/sci/eval.clj:2747`; `docs/seon/specs/agent-loop.md:266` | **RENAME → overrides** for config; **branch definitions** for program differences | SCI `core.cljc:329` has `merge-opts`, `:334` fork. Neither proves Seon's branch delta installation or private-Var preservation. State which is being merged. |
| fork, `src/seon/sci/eval.clj:3513` | **KEEP** for `sci/fork`; use **branch!** for database branches | SCI `core.cljc:334` guarantees new/redefined Vars do not become visible in the original context. Datahike `versioning.cljc:212` separately creates a branch. Mutable objects held by Vars are not thereby deep-copied. |
| private layer, `docs/seon/architecture/vocabulary.md:137` | **RENAME → agent-local Vars and values** | SCI `core.cljc:334` concerns Var changes in forked contexts; Clojure private Var metadata is a different concept. No generic “layer” mechanism is needed. |
| admission, `src/seon/schema.clj:2835` | **RENAME → validation**, **registration**, or **transaction**, according to the action | Malli `core.cljc:2672` validates; SCI `core.cljc:260` interns; Datahike `api/impl.cljc:30` transacts. “Admission” currently bundles decisions that occur at different times. Backpressure admission remains an ordinary concurrency term. |
| adoption, `src/seon/cluster.clj:2442` | **JUSTIFIED**, installing a selected stored program; plain gloss **load program** | clj-reload `reference-code/clj-reload/src/clj_reload/core.clj:334` reloads files; Seon additionally loads database entities, instruments and records the program identity. Do not pretend clj-reload owns branch adoption. |
| publication, `src/seon/cluster/source.clj:557` | **JUSTIFIED**, indexing and committing program declarations; gloss **publish program** | clj-kondo `reference-code/clj-kondo/src/clj_kondo/core.clj:67` returns analysis/findings, not a durable Seon program. Datahike owns the final transaction. |
| write-back, `src/my/program.clj:601`; `src/seon/cluster/source.clj:1265` | **JUSTIFIED**, source write-back | `reference-code/rewrite-clj/src/rewrite_clj/zip.cljc:335`, `:309` replace nodes and reconstruct source. Digest checks and declaration equivalence are Seon's addition. Calling this Git push would be false. |
| roster, `src/seon/cluster/agent.clj:612`; `resources/seon/schemas/seon.agent.edn:131` | **RENAME → contexts, graphs, branches**, whichever collection is meant | SCI `core.cljc:320` names contexts; Flow `flow.clj:78` names graph data; Datahike `versioning.cljc:212` names branches. The aggregate resource owner is **JUSTIFIED**; avoid replacing one vague noun with another global “registry.” |
| environment, `src/seon/turn/world.clj:32` | **KEEP** for explicit execution inputs, qualified by scope | nREPL `interruptible_eval.clj:91` passes temporary bindings; SCI `core.cljc:320` uses options. Seon environment, database connection and SCI context are not interchangeable. |
| cluster, `resources/seon/schemas/seon.agent.edn:1`; `docs/seon/architecture/vocabulary.md:41` | **JUSTIFIED**, branch plus agents and shared services | Datahike `versioning.cljc:212` only creates the branch; Flow `flow.clj:78` only the graph. Do not claim a distributed-computing cluster merely from this name. |
| host-bound, `docs/seon/architecture/vocabulary.md:47` | **JUSTIFIED**, a declaration whose execution remains in JVM code | SCI `core.cljc:410` evaluates a form in a ctx; compiled host Vars differ. This is a per-declaration interoperability decision, not a namespace class. |

### Errors, scheduling and tests

| Seon term and source | Decision and established term | Prior art and semantic boundary |
|---|---|---|
| finding, `src/seon/repl.clj:295`; `resources/seon/schemas/seon.fn.finding.edn:1` | **KEEP** for analyzer output; **JUSTIFIED** for Seon's measured bound findings | `reference-code/clj-kondo/src/clj_kondo/core.clj:103` returns findings. A runtime cost finding is not a clj-kondo finding merely because both are maps. |
| diagnostic, `src/seon/batch.clj:153`; `src/seon/error/refusal.clj:628` | **KEEP** as a general word; **RENAME → error / warning / finding** wherever the precise category is known | `reference-code/clojure/src/clj/clojure/main.clj:207` produces error triage data; clj-kondo `core.clj:103` findings. Do not create another error identity just to attach an error beside a successful value. |
| fault, `src/seon/fault.clj:138` | **RENAME → recorded error**; deletion already ruled | Clojure `reference-code/clojure/src/clj/clojure/core.clj:4836`, `:4845` carries exception data; `clojure/main.clj:207` triages it. Persistent occurrence counts and notifications remain domain data. |
| recorded error, `docs/seon/specs/errors.md:6` | **JUSTIFIED** durable occurrence record | `core.clj:4845` returns exception data but does not store/deduplicate occurrences. This is not a replacement Throwable class. |
| refusal, `src/seon/error/refusal.clj:699` | **JUSTIFIED** for a rejected operation; use **error** as the common representation | Malli `core.cljc:2231` reports invalid input. A refused mutation and a failed evaluation can have different facts without different carrier frameworks. |
| wake, `src/seon/cluster/wake.clj:96` | **RENAME → notification / input message** for channel delivery; **JUSTIFIED** for the durable work predicate | Flow `flow.clj:173` transforms state/input/message; Datahike `api/specification.cljc:1152` documents transaction listeners. A channel notification can be dropped/coalesced; it is not the durable fact to answer. |
| disposition, `resources/seon/schemas/seon.turn.edn:44` | **RENAME → status** for recorded state, **wait / complete** for the actual action | nREPL `interruptible_eval.clj:202` sends done status; Flow `flow.clj:172` changes state. Agent waiting and provider request done are different statuses, not a shared enum. |
| situation, `src/seon/bootstrap.clj:98`; `src/seon/cluster/agent.clj:371` | **RENAME → agent state** | Flow `flow.clj:191` publishes a state map. Task, messages and budget are ordinary data, not a special execution object. |
| answered basis, `docs/seon/specs/agent-loop.md:299` | **JUSTIFIED**, the database basis represented by the answered prompt | Datahike `api/impl.cljc:153` supplies temporal database values; nREPL done status does not prove which database state a model saw. Do not rename to latest tx or current basis, which are different facts. |
| request, `src/seon/call_preparation.clj:10`; `docs/seon/specs/agent-loop.md:154` | **KEEP**, qualify evaluation request, test request, merge request | nREPL `interruptible_eval.clj:72` consumes a request message. A map of options does not always deserve a request entity. |
| member (test), `resources/seon/schemas/seon.test.member.edn:1` | **RENAME → test result** for an executed test; **selected test** before execution | Clojure `reference-code/clojure/src/clj/clojure/test.clj:708` names test-var; Kaocha `reference-code/kaocha/src/kaocha/type/var.clj:30` merges testable with result counts. Do not rename schema map members to tests. |
| gate, `src/seon/fn.clj:2064` | **RENAME → required checks / selected tests / validation**, as appropriate | Clojure test `test.clj:723` runs selected Vars; Kaocha `reference-code/kaocha/src/kaocha/plugin/filter.clj:10` filters tests. Merge acceptance policy is Seon's addition, not a special runner. |
| reach, `src/seon/test.clj:73`; `src/seon/fn.clj:2064` | **RENAME → observed calls** for past execution, **test dependencies** for current selection | clj-kondo `core.clj:103` provides analysis; tools.namespace artifact `dependency.cljc:14` distinguishes immediate/transitive dependencies. Neither is test coverage by itself. |
| proof, `src/seon/task.clj:321`; `docs/seon/specs/agent-loop.md:53` | **RENAME → task test / passing test result**, according to the value | Clojure test `test.clj:351` reports assertions, not mathematical proofs. Seon's task-to-test relation is **JUSTIFIED**; the word proof overstates a bounded check. |
| proc, step, graph, ports, `src/seon/flow.clj:323` | **KEEP** | Flow `flow.clj:165` has process, describe/init/transition/transform; `:198` describes ins/outs. Use **step-fn**, **input/output** and **channel** where those are exact. |
| callback, sink, `src/seon/turn/world.clj:21` | **KEEP** when literal; qualify call observations or read observations | nREPL `interruptible_eval.clj:72` uses a closure and transport; prepl `core/server.clj:218` takes an output function. A sink is not automatically a queue or durable log. |
| fixture, `src/seon/test/runner.clj:8` | **KEEP** | Clojure test `test.clj:696` composes fixtures; `:723` applies once/each fixtures. Seon's branch preparation is an integration requirement, not a new fixture language. |
| kind / entity type, `resources/seon/schemas/seon.task.edn:9` | **RENAME → specific schema / subject ref** for redundant entity classification; remove duplicated tag facts | Malli `core.cljc:996` supports `:or`, `:1861` supports `:multi`. A protocol operation or error variant discriminator is not automatically an invented class hierarchy. Preserve the owner's explicitly retained error variants. |

### Git, data and remaining discovered names

The Git names in this table are already approved in `docs/research/agent-platform/2026-10-08/review/owner-rulings.md`
(“Git vocabulary approved”). The git-vocab note's final questions are therefore historical questions, not a new
approval requirement. Its names are targets unless independently verified as loaded.

| Seon term and source | Decision and established term | Prior art and boundary |
|---|---|---|
| branch / lane branch, `src/my/agent.clj:117` | **KEEP branch**, drop lane in agent text | `reference-code/datahike/src/datahike/versioning.cljc:212`; named branch, not a copied store or Git worktree. |
| unlink / retire-branch!, `src/my/agent.clj:168`; git-vocab §2 verbs | **RENAME → delete-branch!** | Datahike `versioning.cljc:279`; deletion of a branch reference is distinct from garbage collection. |
| cluster head / home for merge target, `docs/seon/specs/merge.md:127` | **RENAME → main / target branch**, according to the relationship | Datahike `versioning.cljc:461` names commit identity; git-vocab §2 separates main, checkout and source/target. Do not call every branch main. |
| proposal / propose-merge!, `src/my/program.clj:416` | **RENAME → merge request / request-merge!** | Datahike `versioning.cljc:738` owns commit creation, not a forge's request; git-vocab §2 supplies the approved collaboration concept. |
| candidate / S, `docs/seon/specs/merge.md:127` | **RENAME → merge branch** | Datahike `versioning.cljc:212` supplies the actual branch; git-vocab §2 describes the retained branch for testing/resolution. |
| prepare-merge! / accept-merge!, `src/my/program.clj:513`, `:547` | **RENAME → merge!**, with continue/abort behavior | Datahike `versioning.cljc:738` handles lineage and caller-supplied tx-data. Full merge policy remains Seon's work. The owner's combined verb changes semantics, not just spelling. |
| branch/head in conflict data, git-vocab §2 conflict | **RENAME → theirs/ours**, with base kept | Datahike `versioning.cljc:738` does not resolve conflicts. Use ours = target, theirs = source for this merge; do not generalize to Git rebase. |
| commit, merge base, diff, git-vocab §2 | **KEEP**, qualify database commit vs Git commit | Datahike `versioning.cljc:461`, `:467` expose commit and parents; editscript `reference-code/editscript/src/editscript/core.cljc:22` computes a two-value diff. Neither makes editscript a three-way merge algorithm. Multiple best common ancestors require research, not the assumption “the newest ancestor.” |
| migrations, `docs/seon/specs/merge.md:159` | **RENAME → norms**, per owner | `reference-code/datahike/src/datahike/norm/norm.clj:142` runs named changes. **Important mismatch:** the library calls tx-fn with a **connection** at `:155`; Seon's approved pure **db → tx-data** function is a new integration contract, not that existing signature. |
| transaction / tx-data / tx-report / database value | **KEEP** | Datahike `api/impl.cljc:30`, `:134`, `:145`. A connection is not a database value; a report is not only tx-data. |
| row / population / program graph, `src/seon/schema.clj:2818`; `docs/seon/architecture/vocabulary.md:102` | **RENAME row → entity map / datom** by value shape; **KEEP program graph** for actual relations; **JUSTIFIED population** for a set of declarations | Datahike `reference-code/datahike/src/datahike/datom.cljc:271`; `api/impl.cljc:134`; clj-kondo `core.clj:103`. Do not call an arbitrary vector a database row. |
| retraction / redefinition, `docs/seon/architecture/vocabulary.md:115` | **KEEP** | Clojure `core.clj:4208` ns-unmap; Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:998` retract handling. Deleting facts and redefining a Var are distinct operations. |
| contract, `src/seon/instrument.clj:2` | **KEEP** in prose, **function schema** when speaking Malli's API | Malli `core.cljc:3130` function-schema, `:3086` `:=>`. “Contract” must not conceal a second schema language. |
| analysis, findings, hooks, `src/seon/fn/analyzer.clj:668` | **KEEP** with producer named | clj-kondo `core.clj:67`, `:103`; SCI observations at `src/seon/sci/observe.clj:33` are execution observations, not static analysis. |
| render profile / render function, `docs/seon/architecture/vocabulary.md:132` | **JUSTIFIED** schema-specific presentation policy; ordinary functions | Fipp `edn.cljc:9` takes printer options; Puget `reference-code/puget/src/puget/printer.clj:245` formats a document. Neither owns Seon's schema-specific selection/read evidence. |
| package / keyframe / delta, `docs/seon/architecture/vocabulary.md:127` | **RENAME → full update / patch / revision** where those are exact; package alone says little | editscript `core.cljc:22`, `:67` diff/patch; this is an analogy unless the actual payload is an editscript edit script. **UNVERIFIED:** every web delivery payload's exact semantics. |
| cache kind, `src/seon/cache.clj:4` | **RENAME → cache name** in explanation; keep keyed partition only if needed | core.cache artifact `cache.clj:20` names a cache; Caffeine is already used at `src/seon/cache.clj:53`. A cache key prefix is not an entity class. |
| namespace agent / task / message, `docs/seon/architecture/vocabulary.md:79` | **JUSTIFIED domain nouns**, keep | Clojure namespaces (`core.clj:4170`) and Flow processes (`flow.clj:165`) do not supply task assignment or conversation semantics. No benefit from pretending an agent is an Actor or a namespace is a service. |

### Fifteen renames with the highest payoff

Ordered for conceptual clarity, not permission to perform a repository-wide search/replace:

1. **armed contract → instrumented function** — Malli already owns the mechanism.
2. **receipt → evaluation / result** — distinguish recorded facts from live values.
3. **pass → evaluation request** — one request, serial form evaluations.
4. **settlement → recording transaction** — says exactly what commits, without implying form rollback.
5. **envelope → response map** — an existing REPL concept.
6. **world → evaluation context** — name the actual inputs; distinguish SCI ctx.
7. **custody → checked-out branch** in agent text — retain the explicitly approved internal helper name.
8. **fault → recorded error** — one error vocabulary and carrier.
9. **disposition → status / wait / complete** — state and action are different.
10. **situation → agent state** — remove the extra noun.
11. **proof → task test / passing test result** — a bounded check is not a proof of the whole program.
12. **test member → selected test / test result** — identify whether it has run.
13. **gate → required checks / selected tests** — name the policy or selection, not a machine.
14. **proposal → merge request** — approved collaboration vocabulary.
15. **unlink / retire branch → delete branch** — Datahike's verb; GC is separate.

Projection → registry is deliberately absent from this top list: it would be wrong for the whole projection.
Likewise, result symbols, answered basis, write-back and recording requirements cannot be deleted by renaming them.

## 2. Re-implementations and the seams to research

These are ten concrete duplication/replacement candidates. “Source confirmed” means the overlapping mechanism is
visible, not that replacing it has been proven behavior-preserving. No runtime regression is asserted without a
probe. A keyword tag, `loop/recur`, an atom, a protocol or a Java library is not by itself non-Clojure style.

API spelling correction: clojure.main has `repl-read` and `repl-caught`; evaluation and printing are its **`:eval`
and `:print` hooks**, not verified Vars named `repl-eval` or `repl-print` (`main.clj:400`). nREPL also has explicit
`load-file` middleware (`reference-code/nrepl/src/clojure/nrepl/middleware/load_file.clj:72`), describe middleware
(`reference-code/nrepl/src/clojure/nrepl/middleware.clj:71`), interrupt/session handling (`middleware/session.clj:271`)
and separate `:out`/`:err` writers (`middleware/session.clj:125`). Those protocol distinctions belong in the first
research pack; Seon should not recreate them under new nouns.

| Priority | Seon evidence and problem | Library seam to reuse; what must remain / be proven |
|---|---|---|
| 1 | **Schema error explanations. Source confirmed.** `src/seon/error.clj:864` has schema-expectation; `src/seon/instrument.clj:428` builds violation descriptions. The platform teaches another interpretation of Malli's errors. | `reference-code/malli/src/malli/core.cljc:2688` explainer; `reference-code/malli/src/malli/error.cljc:288`, `:332`, `:374` error-message, with-error-messages, humanize; instrumentation report seam `core.cljc:2210`. Return explanation data through the one printer. Keep producer attribution and concise operation-specific errors. A report callback must throw if it is meant to prevent execution; returning does not refuse. |
| 2 | **Exception carrier/normalization layers. Source confirmed.** `src/seon/error/refusal.clj:628`, `:699`, `src/seon/batch.clj:153` and `src/seon/fault.clj:138` build/normalize/record overlapping error representations; `src/seon/db.clj:1890` vicinity includes refusal-handling composition. | `reference-code/clojure/src/clj/clojure/core.clj:4836`, `:4845` ex-info/ex-data; `reference-code/clojure/src/clj/clojure/main.clj:207`, `:342` ex-triage/err->msg. Reuse carrier and structured triage, not err->msg as a second printer. Keep validated domain fields, delivery and occurrence recording. `core_print.clj:473` Throwable->map lacks a cycle guard and suppressed traversal: Seon's cycle-safe chain at `src/seon/error/refusal.clj:392` is a justified extension. |
| 3 | **REPL response rebuilding and read-back. Source confirmed.** `src/seon/repl.clj:86` reads printed output back as EDN; `:275` manually splices a response-map string; `src/seon/render/transcript.clj:247` reshapes receipts. This is text round-tripping where the program originally had values. | prepl `reference-code/clojure/src/clj/clojure/core/server.clj:194` gives `:tag/:val/:ns/:ms/:form`; nREPL `interruptible_eval.clj:72`, `:156` provides read/eval hooks and response values; clojure.main `main.clj:368` provides read/eval/print/caught hooks. Keep Seon's one recording transaction and result symbol as additions. Do not transplant prepl wholesale: it uses host eval, starts in user, and does not preserve Seon's total result-plus-diagnostic behavior. |
| 4 | **Function-name spelling distance. Source confirmed algorithm overlap, not drop-in equivalence.** `src/seon/error.clj:1920` implements Levenshtein; `:1937` derives nearby program names. | `reference-code/malli/src/malli/error.cljc:256`, `:339` already provides spelling machinery via with-spell-checking. That public seam suggests schema map keys, not arbitrary function symbols. Use it directly for contract keys; investigate a maintained reusable seam for symbol suggestions or delete low-value suggestions. Do not call Malli's private distance function as a supported API. |
| 5 | **Test-report conversion and counters. Source confirmed partial duplication.** `src/seon/test/runner.clj:177`, `:232`, `:292`, `:348` translate/capture/reprint events and counts. It already uses clojure.test; claiming an entirely home-built runner would be false. | `reference-code/clojure/src/clj/clojure/test.clj:262`, `:351`, `:696`, `:708`, `:723` provide counters, report maps and fixtures. `reference-code/kaocha/src/kaocha/type/var.clj:30` demonstrates data results and zero-assertion reporting. Keep one event adapter to durable evidence and the one printer, plus branch-local test execution. Kaocha's whole harness is not automatically compatible with SCI or Seon's termination ownership. |
| 6 | **Reload dependency ordering. Source confirmed.** `src/seon/cluster.clj:1858` repeatedly scans remaining namespaces for a ready node. It is another topological-order implementation; on an unfavorable chain it repeatedly revisits remaining nodes. No timing was measured here. | `reference-code/clj-reload/src/clj_reload/parse.clj:163` topo-sort; `core.clj:185`, `:270`, `:304`, `:334` ordering/unload/reload. tools.namespace artifact `dependency.cljc:14` supplies dependency graph operations. Prove cycle behavior, tie order, deleted namespaces, private-state preservation and recorded adoption. Full filesystem scanning per entity change would reintroduce the wrong work. |
| 7 | **Context reconstruction around an existing fork. Source confirmed composition; excess cost is a research question.** `src/seon/sci/eval.clj:2747`, `:2831`, `:3036`, `:3171`, `:3230` maintain overlays, per-turn forks and bases; `src/seon/turn/world.clj:42` forks during world creation. | `reference-code/sci/src/sci/core.cljc:320`, `:329`, `:334`, `:410` init/merge-opts/fork/eval-form. Use the held context and advance changed definitions. Fork is not a promise of deep object isolation or an incremental program loader; measure changed definition work and private-value preservation before deleting the extra code. The owner's 428 MB base miss is prior evidence, not reproduced here. |
| 8 | **Execution state duplicated beside Flow. Source confirmed.** `src/seon/flow.clj:660` holds active-work, io-submissions and accepting? in separate atoms; `:764` adds status/active?/task atoms per submission. Agreement is spread across callbacks and watches. | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:165`, `:291` provide process and map->step with explicit state transitions. `flow/impl.clj:257` timed wait is not termination. Derive transitions over one owning state and retain an explicit actual-exit event for host work. A state machine is appropriate here; the problem is duplicated ownership, not the existence of states. |
| 9 | **Pretty-print layout prediction beside Fipp. Source confirmed partial overlap.** `src/seon/print.cljc:630` already calls Fipp stages, but `src/seon/render/value.clj:928`, `:952` predicts widths/group fits for budgeting. Audit whether this duplicates layout decisions or is necessary budget work. | `reference-code/fipp/src/fipp/engine.cljc:68`, `:92`, `:164`, `:247` own layout; `reference-code/fipp/src/fipp/edn.cljc:9` owns ordinary collection print limits; `reference-code/puget/src/puget/printer.clj:245`, `:770` separate document formatting/emission. Keep token budgeting, HTML and runnable elisions. Neither Fipp nor Puget is a drop-in replacement for those semantics. Never add a separate quota printer. |
| 10 | **Redundant entity classification. Source confirmed.** `resources/seon/schemas/seon.task.edn:4`, `:9`, `:29` makes kind part of task identity and schema; specific task data already states subject/merge facts. This is the owner's “no kinds” target, not proof that tags are unidiomatic Clojure. | `reference-code/malli/src/malli/core.cljc:996` `:or`, `:1861` `:multi`, and `reference-code/malli/src/malli/registry.cljc:55` composition. Specific open map schemas can express the variants; source/target refs identify a merge request. Preserve ambiguity checks, identity stability and allowed error variants. `clojure.test` report `:type` and prepl `:tag` are established protocol discriminators, not redundant entity classes. |

### Candidates that must not be falsely sold as duplicate mechanisms

- **Cache:** `src/seon/cache.clj:53` already delegates eviction/weighting to Caffeine. `src/seon/call_preparation.clj:37`
  and `src/seon/schema.clj:24` already use core.cache. Investigate why there are persistent caches outside the central
  owner, but distinguish per-operation memo tables and dependency-owned caches. Core.cache's inspected LRU threshold
  is a count, not the owner's byte budget. Caffeine's documented W-TinyLFU policy in `src/seon/cache.clj:8` is not
  strict LRU; the new live-result LRU ruling needs an explicit policy decision, not a misleading rename.
- **Three-way merge:** Datahike `versioning.cljc:738` records supplied content/parents. It explicitly does not decide
  which declarations to merge. editscript `core.cljc:22`, `:67` is diff/patch, not conflict resolution. Seon's semantic
  merge work is real; do not replace it with a two-way patch or call it rebase.
- **Norms:** Datahike `norm/norm.clj:142` transacts norms individually and passes a connection to tx-fn. Seon's pure
  db → tx-data composition inside an atomic program merge is additional work. It must be tested at the writer,
  including target data written after the rehearsal. “We already have norms” is not evidence the merge is atomic.
- **Source edits:** `rewrite-clj/zip.cljc:275`, `:309`, `:335` preserve syntax as source; editscript diffs data.
  Seon's exact-span fencing and reindex equivalence solve different problems. Research the join; do not add another parser.
- **Analysis:** `src/seon/fn/analyzer.clj:668` already uses clj-kondo. `src/seon/sci/observe.clj:33` observes executed
  definitions. Static analysis is not evidence a nested defn ran; observed writes are not complete static call analysis.
- **Storage:** konserve `reference-code/konserve/src/konserve/core.cljc:236`, `:348`, `:512` already provides key/value
  operations. Cache expiry, deleting a branch, retracting a fact and reclaiming a blob are different operations.
  No claim that Seon needs a new storage wrapper follows from this audit.
- **Orchestra:** `reference-code/kaocha/src/kaocha/plugin/orchestra.clj:8` instruments via Orchestra and unstruments
  after a run. That is useful prior art for lifecycle separation, not permission to add a second spec system beside
  Malli or mutate all host Vars during an isolated test.

## 3. Prioritized research agenda

Sizes are research estimates for one focused evidence pack, **not implementation estimates**: XS ≤ 1 hour,
S = 2–4 hours, M = 1 day, L = 2–3 days. Each pack must leave a minimal reproducible case, measured cost, a decision
and a small implementation boundary. Do not launch an L-sized code lane. Prefer deletion, then existing public
seam, then a documented maintained-fork change. Work below is ranked by reliable agent execution, not ease of renaming.

| Rank / size | Question | Why it matters for the agent loop | Prior art to read | Decision and required evidence |
|---|---|---|---|---|
| 1 — M | **What is the smallest serial REPL entrance preserving namespace, dynamic bindings, definitions, values, output and exceptions?** Compare clojure.main, prepl and nREPL semantics explicitly. | Every model reply, outside evaluation and test body enters here. A broken REPL makes all downstream evidence suspect. | `reference-code/clojure/src/clj/clojure/main.clj:368`; `core/server.clj:194`; `reference-code/nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:72`; `reference-code/sci/src/sci/core.cljc:410`; `reference-code/sci/src/sci/impl/interpreter.cljc:94`. | Decide one request/response vocabulary and the read/eval/print/caught adapters. Compare define/call/redefine/call, namespace after throw, read errors, print failure and `*1/*e`. Prove one read/eval per form and preserve the result when recording/printing fails. Cost proportional to submitted forms plus actual observation, not the whole program. |
| 2 — M | **Which library state can be shared, forked and advanced without rebuilding it or leaking private changes?** | Agents must retain private Vars and fit many contexts on a 16 GB machine. | `reference-code/sci/src/sci/core.cljc:320`, `:334`; `reference-code/sci/src/sci/impl/utils.cljc:410`; `reference-code/malli/src/malli/registry.cljc:17`, `:55`; `reference-code/malli/src/malli/core.cljc:353`. | Decide base context ownership, branch definition updates and schema-registry reuse. Measure unchanged/one-definition/100-definition acquisition, allocated vs retained memory, mutable object sharing, and redefined Var visibility. Target changed-input work; a cache hit must not rebuild its value. |
| 3 — M | **How do all failures return as useful data without losing the value, recursing during error construction, or introducing three error vocabularies?** | An agent that cannot inspect the failure cannot repair itself. | `reference-code/clojure/src/clj/clojure/core.clj:4836`; `core_print.clj:473`; `main.clj:207`; `reference-code/malli/src/malli/core.cljc:2210`; `reference-code/malli/src/malli/error.cljc:332`. | Decide one ex-info/ex-data adapter, domain variant schema and record/deliver path. Probe invalid input/output, malformed schema, cause cycle, suppressed exception, printer failure and failed recording. Preserve the existing cycle-safe extension. Cost one cause traversal and one explanation only on error. |
| 4 — M | **What event proves evaluation, provider work, a test and a graph actually exited? Where does that ownership live?** | Timeout mistaken for completion allows overlap and branch/resource corruption. | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:165`, `:291`; `flow/impl.clj:257`; `reference-code/sci/doc/interrupt.md:52`; `reference-code/nrepl/src/clojure/nrepl/middleware/session.clj:181`, `:271`. | Decide one Flow state owner, notification coalescing and actual-exit protocol. Probe interpreted interruption, a blocking host call, cancellation during launch and stop while work remains. State transitions scale with the affected work item; unrelated agents proceed. No second scheduler. |
| 5 — M | **Which transaction records an evaluation, and which effects already happened? What basis proves an observation current?** | Retrying a recording must not replay user effects; stale read evidence must not count as an answer. | `reference-code/datahike/src/datahike/api/impl.cljc:30`, `:134`, `:153`, `:185`; `reference-code/datahike/src/datahike/db/transaction.cljc:1172`, `:1225`; `reference-code/datahike/src/datahike/query.cljc:3010`. | Decide recording data, tx-report use, writer-time decisions and cross-branch read currency. Probe register-then-write, concurrent target write, failed recording and crash boundaries. One recording transaction does not mean one transaction for all agent effects. Cost follows changed facts; no speculative full validation per unrelated form. Maintained-fork revision seams must be labeled as such. |
| 6 — M | **How can task tests and affected tests use ordinary clojure.test report maps while recording current branch/program evidence?** | False green, stale green or hidden red makes autonomous completion unsafe. | `reference-code/clojure/src/clj/clojure/test.clj:262`, `:351`, `:696`, `:708`; `reference-code/kaocha/src/kaocha/type/var.clj:30`; `reference-code/kaocha/src/kaocha/plugin/filter.clj:10`; `reference-code/kaocha/src/kaocha/plugin/orchestra.clj:8`. | Decide one event adapter, selected-test/result vocabulary, fixture boundaries and evidence invalidation. Show pass/fail/error/zero-assertion/timeout data, including cleanup refusal. Use current dependencies, not past observed calls as selection authority. Cost selected tests plus changed dependencies; all relevant tests auto-run under the latest ruling. |
| 7 — L | **What exactly does merge! guarantee about target, conflicts, test results and norms?** | Agents can run locally yet never safely contribute useful code without a working merge path. | `reference-code/datahike/src/datahike/versioning.cljc:212`, `:461`, `:467`, `:738`; `reference-code/datahike/src/datahike/norm/norm.clj:31`, `:142`, `:204`; `reference-code/editscript/src/editscript/core.cljc:22`; requested git-vocab note §2–§4. | Decide source/target/base/ours/theirs data, kept merge branch, repeated merge and abort. Probe moved target, overlapping renames, target data written after rehearsal, repeated norm, and multiple merge bases. Semantic conflicts and atomic norm composition remain Seon's responsibility. Bound work by changed declarations and affected target data; no store copy. |
| 8 — S | **Which reload work is already handled by clj-reload/tools.namespace, and which is database program adoption?** | Reload must not leave the loop executing half of a new program or destroy an agent's private values. | `reference-code/clj-reload/src/clj_reload/parse.clj:163`; `core.clj:185`, `:270`, `:304`, `:334`; supplementary tools.namespace artifact `dependency.cljc:14`. | Decide one dependency ordering seam and a separate load/instrument/record adoption step. Probe renamed/deleted namespace, failed require, cycle, and concurrent evaluation. Measure actual affected graph size; avoid a filesystem scan per form. |
| 9 — M | **What minimum printer extension is needed beyond Fipp/Puget for bounded, retrievable values and HTML?** | A live result is useless if showing it exhausts time/memory or hides the corrective data. | `reference-code/fipp/src/fipp/edn.cljc:9`; `engine.cljc:68`, `:164`, `:247`; `reference-code/puget/src/puget/printer.clj:245`, `:770`; `reference-code/clojure/src/clj/clojure/core/server.clj:275`. | Decide the one value→document→text/HTML path and remove read-back/splicing. Probe lazy/infinite data, cycles, large source, exception data and a deep elision result symbol. Budget by shown structure; do not realize omitted tails or introduce a quota wrapper. Separate live value, rendered value and saved printed text. |
| 10 — S | **Which task/entity classifications are redundant, and which protocol discriminators are real?** | Schema/type scaffolding can become another source of invalid task identities and impossible transitions. | `reference-code/malli/src/malli/core.cljc:996`, `:1861`, `:3130`; `reference-code/malli/src/malli/registry.cljc:55`; `reference-code/clojure/src/clj/clojure/test.clj:351`; `core/server.clj:200`. | Decide specific schemas and subject refs; remove only redundant entity kind facts. Demonstrate unambiguous union membership, task identity across conversion and unchanged error variants. Do not interpret “Clojure way” as a ban on keyword dispatch. Compile once per changed schema; validate only the owning value. |
| 11 — S | **Where should live values, derived values, shown text and durable blobs live, and what eviction promise do agents receive?** | An old result must either remain inspectable or fail honestly; memory must remain bounded. | `reference-code/konserve/src/konserve/core.cljc:236`, `:348`, `:512`; `reference-code/datahike/src/datahike/gc.cljc:22`; `reference-code/malli/src/malli/core.cljc:353`; supplementary core.cache/core.memoize sources above. | Decide byte-weight bounds and LRU versus W-TinyLFU explicitly; inventory persistent side caches. Probe eviction, missing result after restart, branch deletion and blob retention. A hit should use held immutable content; compute insertion weight without walking a huge value. Core.cache is prior art, not an automatic replacement for Caffeine. |
| 12 — S | **How do observed definitions, static analysis and source edits join without reparsing/re-evaluating the program or confusing evidence?** | An agent's nested definition and later write-back must mean what SCI actually executed. | `reference-code/clj-kondo/src/clj_kondo/core.clj:67`, `:103`, `:242`; `reference-code/sci/src/sci/core.cljc:410`; `reference-code/rewrite-clj/src/rewrite_clj/zip.cljc:275`, `:309`, `:335`; `reference-code/editscript/src/editscript/core.cljc:22`. | Decide exact-span analysis joins, definition observation authority and edit/reindex equivalence. Probe nested/conditional defs, macros, aliases, duplicate text spans and comments. Static analysis cannot prove execution. Cost one analysis of changed text and one edit of affected declarations. |

Cross-cutting **XS prerequisite**: reconcile the live vocabulary/spec claims with the newest owner rulings before
assigning code. This lane does not edit those owners. Concrete contradictions visible at inspection:

- `docs/seon/architecture/vocabulary.md:94` onward still describes durable turns, a target session entity and system
  turns; `docs/seon/specs/agent-loop.md:217` and owner rulings remove opening/input passes and make current state fresh.
- Agent-loop `:54`, `:291`, `:393` still limits automatic work to task proofs and describes asynchronous proof work.
  The newer rulings require all relevant tests and describe serial testing inside the pass. Those are behavioral
  disagreements, not editorial synonyms.
- Vocabulary `:45` retains unlink; the approved Git mapping uses delete-branch!. Internal custody is specifically
  retained, so a blanket removal would also contradict the ruling.
- The guide's identity row describes printed-data hashing (`docs/seon/architecture/vocabulary.md:141`); the shared instructions
  require structural identity. Verify `src/seon/id.clj` before claiming either installed; no identity implementation
  verdict is made here.
- The data-oriented skill's pinned Datahike revision `d9121bbbad` is older than the inspected `e5453d967b`; its
  error-recording prose says task creation while the errors spec defers task creation to triage. Those skill claims
  need owner correction. This notes-only lane neither rewrites skills nor treats them as current implementation proof.

The first six packs should settle the ordinary agent path before more lifecycle machinery is built. Git/norms work
can be researched independently, but merge acceptance cannot rely on unproven test or recording semantics. Vocabulary
changes should land with their owners and callers; a mass rename ahead of semantic decisions would only disguise the
same complexity.

## Landing evidence

Owned path only: `docs/research/agent-platform/2026-10-08/clojure-way/research-agenda-clojure-way.md`.
Claimed with `bin/lane claim research-agenda` before creation. No code, schema, test, instruction, schedule, other
lane session or other lane file was edited. No worktree, delegation, paid model run or default restart.

Validation is recorded here before landing. Required test:
`seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings`
(`test/seon/contracts_compile_test.clj:961`). It verifies empty/non-namespace source selection gives no contract
findings; **it does not verify the research conclusions**. No test reaches Markdown content. The remainder is checked
by source citation review. Broad tiers belong to root; no pre-existing namespace reds are claimed green.

Foreign boundary: at start the shared tree included untracked http-kit data, `tmp/test-data-assertions/proofs.edn`,
untracked `docs/prds/next-turn/`, an untracked research path containing a newline, and `src-clm/`. Other lanes held
the agent-loop/merge specs and separate research files. These were neither included nor repaired. The lane candidate
contains HEAD plus this one note. No source/test line change; no production runtime performance change asserted.

`clj-kondo --lint src script test --cache false --parallel`: 4,806 ms, **0 errors, 1,463 warnings** (exit 2 for warnings). This is not zero findings; no source was changed to hide them. The lane gate separately checks the exact candidate and the requested test. All 167 full-path citation references present at the first citation check named existing files and in-range lines; semantic review corrected several vocabulary row offsets. Final gate/test/commit details are supplied in the landing reply; unavailable evidence is not a pass.
