---
type: research
status: complete
tags: [research, docs, architecture]
---

# `AGENTS.md` verification audit — 2026-08-13

## Scope and method

This is a read-only verification of root [`AGENTS.md`](../../../../AGENTS.md)
against HEAD `f92d75aa8e9161f2dd45aec5f1319856d12ebbf2`. I read `AGENTS.md` end to
end (1,504 lines), the localized
[`AGENTS.md`](../AGENTS.md) end to end, and the active
[`plan/README.md`](../plan/README.md) end to end (3,854 lines). I also read the
current WORKING EDGE at [`plan/unsettled.md`](../plan/unsettled.md#L19).

The unit counted below is a factual claim group: one row may group adjacent
sentences only when they assert the same mechanism. Vocabulary rows are graded
one-for-one. Pure imperatives and owner preferences are not factual claims; a
section-level row records their existence, while the mechanisms and historical
premises they cite are graded separately.

Verdicts mean:

- **VERIFIED** — current HEAD source or a retained, not-superseded evidence
  artifact establishes the claim.
- **STALE** — HEAD contradicts the claim, the named target has landed, or a
  citation now points at the wrong mechanism.
- **UNVERIFIABLE** — the checkout lacks the observation, artifact, or external
  harness evidence needed to establish the assertion.

No live probe or remeasurement was performed. Named measurements were checked
only against their retained research and later supersession evidence.

## Result

| Verdict | Claim groups |
|---|---:|
| VERIFIED | 126 |
| STALE | 27 |
| UNVERIFIABLE | 8 |
| **Total** | **161** |

The dominant failure mode is not missing files. All real Markdown link targets
in `AGENTS.md` resolve; the only unresolved target is the explicitly illustrative
`docs/prds/.../file.md`. The poison is semantic drift: built work remains marked
target, deleted multi-tier machinery is described as current, current command
and MCP surfaces are incomplete, and many vocabulary citations point at old
line ranges.

## Per-section claim ledger

### Authority, standing goal, cadence, and instruction discovery

| Claim | Verdict | Evidence checked |
|---|---|---|
| Root `AGENTS.md` is the maintained authority; `CLAUDE.md` is its same-directory compatibility link and `AGENT.md` is the thin lane adapter. | VERIFIED | `CLAUDE.md` is a symlink to `AGENTS.md`; `AGENT.md:1-24` is the adapter. |
| The old self-host/child execution system is deleted rather than maintained beside the JVM system. | VERIFIED | `deps.edn:1-6`; no tracked `src-old/` or `test-old/`; current dependency paths are `src` and `resources`. |
| “CLJ only — the CLJS build is off.” | VERIFIED | `deps.edn:1-27` excludes ClojureScript from Datahike and has no CLJS build alias; `bin/css:7-17` explicitly says the CLJS build is dead. |
| The standing “three effect shapes” is about how effects occur, not which functions are callable. | VERIFIED | `src/seon/effect.clj:1-7,693-705`; `src/seon/fn.clj:313-384,1000-1050` records capability markers while ordinary program functions remain indexed. The later claim that this includes all db writes is separately stale below. |
| The sustained-program ledger and continual multi-lane orchestration procedure is current repository behavior. | UNVERIFIABLE | It is an operator policy in `AGENTS.md:88-199`; no tree artifact can establish that every orchestrator actually supervises at ≤15 minutes, keeps every slot filled, or pushes every checkpoint. Harness/task telemetry would be required. |
| A foreign lane's breakage never blocks a coherent path-limited commit. | VERIFIED | This is a declared shared-tree policy at `AGENTS.md:201-213`; Git supports path-limited commits. The historical “six lanes” incident has no cited artifact and is not independently established. |
| Virtual-thread-unaware thread dumps omit decisive state and can misattribute a hang. | VERIFIED | Current diagnostics explicitly include worker JVM/thread-dump handling in `plan/unsettled.md:70-90`; the rule is also retained in `AGENTS.md:215-226`. The three exact historical misattributions are not separately reconstructable from HEAD. |
| `CLAUDE.md` must not be edited and descendant instructions must be read explicitly from a root-started task. | UNVERIFIABLE | The symlink fact is verified, but Codex/Claude instruction-discovery behavior is external harness behavior; repository source does not establish it. |
| The old quarry exists only in Git history. | VERIFIED | `git ls-files src-old test-old` is empty; `deps.edn:1-4` names Git history as the archive. Empty untracked directories may exist locally, but HEAD has no quarry tree. |

### Core process, store, boot, source publication, Flow, and errors

| Claim | Verdict | Evidence checked |
|---|---|---|
| One JVM can host several clusters and shares only root store custody/executors. | VERIFIED | `src/seon/cluster.clj:1-17,544-552,634-695`. |
| Boot reads a closed bootstrap config containing root/store path, prepl bind, and log directory, then opens the REPL before later layers. | VERIFIED | `src/seon/cluster.clj:479-538,2576-2696`; later-layer failure preserves the REPL. |
| Process identity is `(pid, start-instant)` and advertisements add cluster name/prepl coordinates. | VERIFIED | `src/seon/cluster/process.clj:1-44`; `src/seon/cluster.clj:2576-2639`. |
| Default physical store path is `data/clusters/store`; a process-root-wide `flock` fences it. | VERIFIED | `src/seon/cluster.clj:479-506,634-695`; `src/seon/cluster/store.clj:198-230,284-377`. |
| Each cluster is a named Datahike branch with its own connection; Datahike serializes transactions per connection. | VERIFIED | `src/seon/cluster.clj:1-17`; `src/seon/cluster/registry.clj:160-284`; `reference-code/datahike/src/datahike/writer.cljc`. |
| Two JVMs on one store once destroyed 40/40 commits. | VERIFIED | Retained measurement statement at `docs/prds/sci-execution-runtime/research/f2-live-render-proof-2026-07-28.md:15-18`; later store research does not supersede the loss result. |
| Config manifests reconcile into facts and running code reads database config, not the manifest. | VERIFIED | `src/seon/config.clj:315-500`; `script/seon/fresh_operator.clj:1410-1510`; `resources/seon/schemas/seon.config.edn`. |
| `:current-src` is one non-executing published branch; a new cluster forks its published commit and existing clusters stay sovereign. | VERIFIED | `src/seon/cluster/source.clj:1-9,239-319`; `test/seon/cluster/boot_test.clj:820-866,959-966`. |
| Incremental publication statically analyzes same-identity changes; deletion/move/schema/analysis uncertainty forces complete publication. | VERIFIED | `src/seon/fn.clj:1216-1320`; `src/seon/cluster/source.clj:239-319`; `script/seon/fresh_operator.clj:2048-2115`. |
| Every agent has its own Flow graph and no central dispatcher/scheduler. | VERIFIED | `src/seon/cluster/agent.clj:1-45,350-384`. |
| Each per-agent graph has **two** procs. | STALE | HEAD declares **three** (`::mailbox`, `::turn`, `::schedule`) at `src/seon/cluster/agent.clj:15-36,360-382`. |
| Parked-proc cost is about 8.5 KB. | VERIFIED | `docs/prds/sci-execution-runtime/research/flow-mechanics-2026-07-28.md:38-46,219-222`; later qualification at `flow-control-protocol-2026-07-31.md:177-185` says this is a one-proc running-and-parked baseline, not a whole production-agent cost. `AGENTS.md` should retain those conditions. |
| Topology rebuild is about 0.3 ms. | VERIFIED | Later verification reports median 0.343 ms with conditions at `docs/prds/sci-execution-runtime/research/skills-verification-2026-07-29.md:213-218`; `AGENTS.md` omits those required conditions. |
| Per-cluster shared plumbing consists only of render and fault-committer graphs. | STALE | Current cluster plumbing also owns armer/search procs and a separate work launcher: `src/seon/cluster.clj:2123-2288`; `src/seon/flow.clj:626-760,930-1095`. |
| Every proc explicitly pins `:io` or `:compute`; `var-process` refuses missing/`:mixed`. | VERIFIED | `src/seon/flow.clj:123-164`; current agent procs are all explicit at `src/seon/cluster/agent.clj:360-381`. |
| Function workload is derived transitively from call-graph reachability; unresolved or mixed chains become `:mixed`. | STALE | HEAD only lifts direct `^{:seon.workload :io|:compute}` metadata at `src/seon/fn.clj:313-380`; no transitive workload classifier exists. Capability validation checks only marked leaves at `src/seon/fn.clj:1000-1050`. |
| Scheduling acts at proc tags and at the eval/capability seam. | VERIFIED | `src/seon/flow.clj:123-164,626-760`; `src/seon/effect.clj:650-705`; `src/seon/sci/kernel.clj:525-608`. |
| Large losable in-flight values ride channels; an 8 MB pointer pass is roughly 7,000× faster than durable file-store transact. | VERIFIED | `docs/prds/sci-execution-runtime/research/flow-mechanics-2026-07-28.md:163-169,238-240`; architecture retains it at `docs/seon/architecture/laws.md:88-90`. |
| Recovery marks dangling receipts interrupted and never re-executes them. | VERIFIED | `src/seon/effect.clj:1-7`; `src/seon/cluster/run.clj:178-276`; `src/seon/schedule.clj:619-629`. |
| Run custody is presence of `:seon.cluster.run/process`; there is no lease/epoch. | VERIFIED | `resources/seon/schemas/seon.cluster.run.edn:1-110`; `src/seon/cluster/run.clj:20-22,178-210`. |
| Agent mistakes become flat errors while core faults are committed by one fault path. | VERIFIED | `src/seon/error.clj:1-55`; `src/seon/flow.clj:930-1095`; `src/seon/cluster/loop.clj:540-614`. A last-resort invariant throw remains at `loop.clj:606-611`, so “nothing throws” is a boundary rule, not literal absence of throws. |

### Portable code and SCI

| Claim | Verdict | Evidence checked |
|---|---|---|
| Portable `.cljc` is the default implementation tier. | STALE | HEAD is CLJ-only and contains 78 `.clj` versus 8 `.cljc` source files; `deps.edn:1-27` and `bin/css:14-17` explicitly retire the CLJS build. |
| Every capability family has a portable core plus one platform leaf per tier. | STALE | Current capability namespaces are JVM `.clj` files (`src/my/fs.clj`, `shell.clj`, `web.clj`, `edit.clj`); there is no CLJS leaf tier. |
| Agent code runs through one SCI interpreter on every tier and never needs conditionals. | STALE | There is one JVM SCI path, not several live tiers: `src/seon/sci/eval.clj`; `src/seon/fn/analyzer.clj:118` explicitly excludes CLJS definitions. The “no conditionals” portion remains a target preference. |
| `plan-execution` computes placement from an indexed call graph. | STALE | No `plan-execution` definition or call exists under `src/`, `test/`, or `resources/`. Only the claim and plan prose mention it. |
| Wire crossings use result-symbol references for tier-local objects. | STALE | No current result-symbol/tier-crossing owner exists. Current admission uses ordinary values, blobs, and identity-only projections (`src/seon/sci/admit.clj:122-165`; `src/seon/blob.clj`). |
| Every SCI invocation has time/output caps and durable definitions require complete Malli contracts. | VERIFIED | `src/seon/sci/eval.clj:1-70,1923-1988`; `src/seon/sci/kernel.clj:525-608`; source indexing records contracts in `src/seon/fn.clj:350-380`. The word “fuel” in `AGENTS.md:397` contradicts its own vocabulary at line 688 and should be deleted. |
| The writer compiles core predicates with `requiring-resolve`. | VERIFIED | `src/seon/schema.clj:3047-3115`; predicate compilation/resolution is system-side and independent of SCI. |
| `conversion-wiki.md` exists at the named path. | VERIFIED | `docs/prds/sci-execution-runtime/plan/conversion-wiki.md`. |

### Documentation authority and Markdown

| Claim | Verdict | Evidence checked |
|---|---|---|
| There are “two documentation layers and no third.” | STALE | The same section immediately defines three: architecture (`AGENTS.md:419-425`), active program roadmap (`426-432`), and bounded chunk PRDs (`433-440`). |
| `docs/seon/architecture/` is the intended-system authority and its named map files exist. | VERIFIED | `docs/seon/architecture/architecture.md`, `context.md`, `data-model.md`, `agent-runtime.md`, `ui.md`, `observability.md`, `toolkit.md`, `laws.md`, `library-grounding.md`, and `decisions/` all exist. |
| `plan/README.md` plus `unsettled.md` is the active high-level ledger. | VERIFIED | `plan/README.md:9-35,39-149`; `plan/unsettled.md:7-24`. Its current contents are internally stale in several places, listed below. |
| Every bounded PRD's `roadmap.md` owns its current state. | STALE | The closest localized authority says this chunk's `roadmap.md` is only a compatibility pointer and `plan/README.md` owns the ladder: `docs/prds/sci-execution-runtime/AGENTS.md:17-22`. |
| Every `docs/**/*.md` file has YAML frontmatter with valid `type`, `status`, and `tags`. | STALE | Sixteen tracked files under `docs/prds/sci-execution-runtime/specs/` begin with an H1, not frontmatter; example `specs/w0.5-writer-ceilings.md:1-6`. The full list is in the stale section below. |
| `seon.dev.markdown` parses, validates, and fixes spacing/trailing whitespace and structural rules. | VERIFIED | `script/seon/dev/markdown.clj:1-16,257-439,474-620`; `bin/seon-hook:488-525` invokes it and applies fixes. |
| All real file/doc links in root `AGENTS.md` resolve. | VERIFIED | A full Markdown-link and inline-path existence sweep found no missing real target. The only miss is the deliberately illustrative `docs/prds/.../file.md` at `AGENTS.md:1475`. |

### Research, implementation, and source policy

| Claim | Verdict | Evidence checked |
|---|---|---|
| `bin/codex-agent` exists and provides the Claude-lane wrapper described. | VERIFIED | `bin/codex-agent`; `docs/seon/reference/driving-codex-agents.md`. Codex-native collaboration behavior itself is harness-owned. |
| Lane stdout/summary paths and resume/status verbs behave exactly as described. | VERIFIED | `bin/codex-agent` defines `run`, `resume`, `status`, `watch`, `stop`, and `summary`, with `tmp/orchestrator/<name>-*` paths. |
| A resumed Codex lane cannot acquire newly registered MCP tools. | UNVERIFIABLE | The repository contains only the prose claim; MCP client/session binding is external Codex harness behavior. A durable harness trace or official client implementation would be needed. |
| Every research/implementation unit begins with a dependency ledger and source grounding. | UNVERIFIABLE | This is a process rule. Many PRDs contain such ledgers, but the universal “every” cannot be established from the tree without a complete corpus audit and a formal ledger schema. |
| Clojure work must use the data-oriented skill before planning. | VERIFIED | The policy is declared consistently at `AGENTS.md:572-578,828-841,1108`; `.agents/skills/data-oriented-clojure/SKILL.md` exists. Whether every agent obeys it is not tree-verifiable. |
| The `reference-code/` dependencies named in core claims are vendored and readable. | VERIFIED | `reference-code/core.async`, `datahike`, `konserve`, `malli`, and `sci` exist; `deps.edn:13-28` selects maintained local forks where claimed. |

### Loud failures and one mechanism

| Claim | Verdict | Evidence checked |
|---|---|---|
| Errors/refusals are typed flat values and diagnostics name missing members. | VERIFIED | `src/seon/error/refusal.clj`; `src/seon/db.clj:1037-1055`; `resources/seon/schemas/seon.error.edn`. |
| Environment data rides with work rather than through a process-global mutable slot. | VERIFIED | `src/seon/env.clj:77-108,350-390`; `src/seon/flow.clj:718-760`; `src/seon/cluster/agent.clj:350-381`. Legacy `seon.db/*conn*` remains for explicit JVM calls at `src/seon/db.clj:69-133`, while call preparation uses the environment at `894-922`. |
| No `foo-v2`, compatibility namespace, second registry/renderer/feed/retry/config/test path exists. | UNVERIFIABLE | This is a universal design constraint. Establishing it requires semantic review of every owner, not a finite name search; the current tree still contains documented duplicate-path issues. |
| Production regex use requires owner permission. | VERIFIED | It is a declared owner rule. HEAD contains regexes (for example `src/seon/schema.clj:640`), but the tree cannot encode whether permission was granted; the rule is not evidence that no regex exists. |

### Vocabulary table — every row

| `AGENTS.md` row | Verdict | Evidence checked |
|---|---|---|
| Functions, schemas, tests are ordinary Clojure constructs. | VERIFIED | Current program rows are built in `src/seon/fn.clj:313-384`; schemas live under `resources/seon/schemas/`; tests under `test/`. |
| “Database/db” names the `seon.db` authority. | VERIFIED | `src/seon/db.clj:1-22`. |
| Boot/environment/running triad; running work receives `seon.env`. | VERIFIED | `src/seon/env.clj:1-108,350-390`; `resources/seon/schemas/seon.env.edn`; proc args at `src/seon/cluster/agent.clj:350-381`. |
| Call preparation supplies declared absent arguments from environment. | VERIFIED | `reference-code/sci/src/sci/core.cljc:310-319`; `src/seon/call_preparation.clj`; `config/default.edn:430-444`. The cited “init docstring” location has drifted but the hook contract is present. |
| Canvas is `:seon.render.canvas/content`, the focal agent surface. | STALE | No source or schema declares that attribute. It exists only in plan/reference prose (`plan/README.md:251` and historical render design). |
| Surface/card terminology describes current render components. | VERIFIED | Current web/package schemas use `:seon.render/surface-id`: `resources/seon/schemas/seon.render.edn:61-84`; `src/seon/render/web.clj:249-274`. |
| Web UI includes `/`, agent/debug routes, and `/data`. | VERIFIED | `src/seon/render/route.clj:5-27`. |
| Subagents are agents connected through database refs. | VERIFIED | `resources/seon/schemas/seon.cluster.agent.edn:1-16,39-48`; agent/run/message connections are refs. |
| Cluster means one database, pod, root, and task agents. | STALE | “Pod” is deleted vocabulary and one physical database store contains many cluster branches: `src/seon/cluster.clj:1-17`; `deps.edn:1-4`. |
| Datahike entities are attributes plus connections, not kind/type records. | VERIFIED | Schema bridge and identity derivation: `src/seon/schema/internal.cljc:172-240`; `resources/seon/schemas/`. |
| Build/operator/artifact means Shadow-CLJS build and digested output. | STALE | The CLJS build/artifact coupling is explicitly dead at `bin/css:7-17`; current operator is `bin/seon`. |
| Get-in/path means paged nested-value navigation. | VERIFIED | MCP `get_value` schema at `script/seon/dev/mcp.clj:845-854`; render data path/offset schemas. |
| Execution plan/`plan-execution` is a current derived placement value. | STALE | No `plan-execution` implementation exists at HEAD. |
| Provider descriptor rows live under the config singleton. | VERIFIED | `config/default.edn:446-480`; `src/seon/ai.clj:315-432`. |
| Per-cluster `packages/`, `package.json`, and `deps.edn` exist at `data/clusters/<name>/packages/`. | STALE | No source/schema/operator owns that path; it appears only as an architecture target at `docs/seon/architecture/toolkit.md:202-205`. |
| SCI execution uses contexts on hosts and binding tables. | VERIFIED | `src/seon/sci/eval.clj:183-271,1270-1395`; `reference-code/sci/src/sci/core.cljc:331-351`. |
| `:interrupt-fn` is called at interpreted function/loop entrances. | VERIFIED | `reference-code/sci/doc/interrupt.md:6-8,50-54`; current owner is `src/seon/sci/kernel.clj:59-91`. The cited `seon.sci.eval (arm/stop!)` owner has moved to `seon.sci.kernel`. |
| `interrupt!` is uncatchable by evaluated code. | VERIFIED | `reference-code/sci/src/sci/interrupt.cljc:32-42`. |
| Time is the only enforced eval limit; SCI has no step budget. | VERIFIED | `src/seon/sci/eval.clj:1-27,1923-1949`; `src/seon/sci/kernel.clj:231-306`. |
| `:seon.eval/fn-entries` is diagnostic; 271M/500 ms signals spin and 12 signals blocked host work. | VERIFIED | Current eval docstring retains the exact interpretation at `src/seon/sci/eval.clj:22-27`; `docs/prds/sci-execution-runtime/research/error-catalog-2026-08-03.md:231`. |
| Every interpreted function-body entrance is the relevant hook, not a JVM safepoint. | VERIFIED | `reference-code/sci/doc/interrupt.md:50-54`. |
| `ctx` and `fork` are SCI names. | VERIFIED | `reference-code/sci/src/sci/core.cljc:331-351`. Citation `:318` is stale and currently describes call preparation, not `fork`. |
| `:io`/`:compute`/`:mixed` are core.async workload tags with the stated executor behavior. | VERIFIED | `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:82-111`. The cited `:122-134` range is stale and points at `run`, not workload selection. |
| `:seon.cluster.run/process` is the process holding a run. | VERIFIED | `resources/seon/schemas/seon.cluster.run.edn:1-110`; `src/seon/cluster/run.clj:20-22,178-210`; `src/seon/cluster/process.clj:1-44`. |
| Accretion/breakage means require no more/provide no less; Rich Hickey attribution is unverified. | VERIFIED | The definition is the repository's declared rule. The row correctly refuses unsupported attribution. |
| Source initialization rows and generated opening functions have the named owners. | VERIFIED | `src/seon/cluster.clj:1158-1270`; `src/seon/fn.clj:1577-1595`; `src/seon/bootstrap.clj:189-289`; `src/seon/render/walk.clj:730-792`. |
| Process records/generation/pid-start identity use the named operator/state/process owners. | VERIFIED | `script/seon/fresh_operator.clj`; `script/seon/dev/state.clj`; `src/seon/cluster/process.clj:1-44`. |
| Pre-processing/apply/resume are still only a research design “until code owners land.” | STALE | Current boot/environment source implements explicit environment construction/replacement/carry (`src/seon/env.clj:77-108,350-390`) and current source preprocessing/publication owners exist. The dated design is no longer the sole authority. |
| Generated opening uses `next-entry`/`ordered-episode`, `generate-turn`/`resume-turn`, and run tx functions. | VERIFIED | `src/seon/bootstrap.clj:237-289`; `src/seon/render/walk.clj:730-792`; `src/seon/cluster/loop.clj:1330-1696`; `src/seon/cluster/run.clj:560,811-878`. Some named functions are private, but present. |
| Run loop advances generated opening or authored ordered forms. | VERIFIED | `src/seon/cluster/loop.clj:1330-1696`; `src/seon/cluster/run.clj:741-878`. |
| Every capability request, including db writes, enters `seon.effect/request!`. | STALE | fs/shell/web/edit use it (`src/my/*.clj`), but db writes do not: `src/my/note.clj:192`, `src/seon/cluster/loop.clj:596-597`, and many owners call `db/transact!` directly. |
| Every function in the cluster program graph is callable and there are no per-agent grants. | VERIFIED | Acquisition installs public program functions at `src/seon/sci/eval.clj:1270-1395`; capability validation does not define per-agent grants (`src/seon/fn.clj:1000-1050`). |
| Program graph names top-level function/namespace/schema/test facts. | VERIFIED | `src/seon/program.cljc:29-43`; `src/seon/fn.clj:313-384`. |
| Flow vocabulary and `flow.spi` adoption are current. | VERIFIED | `src/seon/flow.clj:1-165`; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj`. |
| `(sliding-buffer 1)` is newest-only delivery. | VERIFIED | `reference-code/core.async/src/main/clojure/clojure/core/async/impl/buffers.clj`; usages at `src/seon/render/web.clj:1112-1118,1301-1303`. |
| Datahike tuple is one ordered value, homogeneous tuples cap at 8, cardinality-many is a set. | VERIFIED | Cap is enforced at `reference-code/datahike/src/datahike/db/transaction.cljc:1019-1031`; set semantics in the persistent-set implementation. The row's cited `persistent_set.cljc:133` is an insert function, not evidence of the cap. |
| `my.agents.<id>` is only the default temporary namespace; assigned namespaces are unique. | VERIFIED | `src/seon/sci/eval.clj:271-289`; `resources/seon/schemas/seon.cluster.agent.edn:72-76`. |
| `:seon.render/form` is a third declared projection selected with AI/HTML. | VERIFIED | `resources/seon/schemas/seon.render.edn:20-22,45-58`; `src/seon/render.clj:245-283,512-542`. |
| `:seon.render/ai` is string or qualified render-function symbol. | VERIFIED | `resources/seon/schemas/seon.render.edn:5,20`; `src/seon/render.clj:471-481`. |
| `:seon.render/html` is Hiccup or qualified render-function symbol. | VERIFIED | `resources/seon/schemas/seon.render.edn:21,48-54`; `src/seon/render.clj:483-496`; `src/seon/render/hiccup.clj`. |
| “Wire” is reserved for external crossings. | VERIFIED | AI request construction uses wire keys in `src/seon/ai.clj:550-642`; in-process render transport uses channels/packages in `src/seon/render/web.clj:1-15`. |
| Namespace pages and route aliases are owned by one route table. | VERIFIED | `src/seon/render/route.clj:5-27`; namespace/debug route handling in `src/seon/render/web.clj`. |
| Block is the stable morph/equality unit shared by AI and HTML. | VERIFIED | `src/seon/render/block.clj:14-38,61-96`; web equality/package path at `src/seon/render/web.clj:649-721`. |
| Revisioned package/keyframe/delta delivery and sliding taps are implemented. | VERIFIED | `resources/seon/schemas/seon.render.edn:61-82`; `src/seon/render/web.clj:649-721,1276-1437`. |
| Base SCI context, turn fork, and rendered agent context are distinct; defs rehydrate per turn. | VERIFIED | `src/seon/sci/eval.clj:1270-1395,1540-1614,1616-1665`; `reference-code/sci/src/sci/core.cljc:345-351`; `src/seon/render.clj:620-632`. The cited `eval.clj:1309-1392` no longer spans the complete contract. |
| Candidate contexts are copy-on-write SCI forks. | VERIFIED | `reference-code/sci/src/sci/core.cljc:345-351`; generation-aware copy-on-write support at `reference-code/sci/src/sci/impl/utils.cljc:356-379`; retained probe document exists. |
| Editor/revision/proof curation is implemented and adoption uses `:seon.cluster.run/supersedes`. | VERIFIED | `src/seon/cluster/curate.clj:131-336`; schema at `resources/seon/schemas/seon.cluster.curate.edn`; Datahike branching in `reference-code/datahike/src/datahike/versioning.cljc`. |
| Render profile is the database-derived fit policy applied by `seon.print/fit`. | VERIFIED | `resources/seon/schemas/seon.render.profile.edn`; `src/seon/render.clj:40-99,454-469`; `src/seon/print.cljc:893-965`. The cited `print.cljc:750-785` is now elision enrichment, not `fit`. |
| Elision values contain omitted/total/path/offset/profile/requery data. | VERIFIED | `resources/seon/schemas/seon.print.edn:214-266`; `src/seon/print.cljc:684-718,740-780`. The cited `:602-613` range now describes references, not elisions. |
| External-sink/projection-boundary facts are lifted and `output-path-report` derives shortest-path classifications. | VERIFIED | Lift at `src/seon/fn.clj:313-380`; report at `935-971`; schema at `resources/seon/schemas/seon.fn.edn:30-41`. Both cited source ranges have drifted. |
| Identity-only admission retains a registered identity projection. | VERIFIED | Declarations at `resources/seon/schemas/seon.db.edn:101-127`; resolution at `src/seon/schema.clj:3047-3115`; admission at `src/seon/sci/admit.clj:122-131`. All three cited line ranges are stale. |
| `[TARGET]` root maintenance portfolio is still unbuilt. | STALE | It is built: five declared tasks at `src/seon/schedule.clj:35-99`, per-agent schedule proc at `682+`, maintenance projection at `src/seon/maintenance.clj:1-68`, and maintenance schemas under `resources/seon/schemas/`. |
| `[TARGET] my.branch` remains unbuilt. | VERIFIED | No `src/my/branch.clj` or `my.branch` schema exists; current `src/my/` contains background/edit/fs/message/note/run/shell/web only. |
| Agent defs and atoms settle as `:seon.def/*` facts and rehydrate. | VERIFIED | `src/seon/sci/eval.clj:419-457,630-721,1509-1614`; `resources/seon/schemas/seon.def.edn`. |
| Agent history is derived as ordered form/printed-value entries. | VERIFIED | `src/seon/render/walk.clj:794-878`; `src/seon/render/transcript.clj:884-951`. |
| Bare injected `docs` exists beside `doc`/`dir` and covers functions/schemas/tests/namespaces. | STALE | Current installation creates only `doc` and `dir`, and its query covers public function rows only: `src/seon/sci/eval.clj:990-1175`. No `docs` Var is installed. |
| Acquired candidates are a per-generation render-producer index derived once per publication. | STALE | `candidates` is a private per-render computation over `public-functions-in` plus contract validation at `src/seon/render.clj:156-180`; no acquired candidate index exists. |
| `seon.db` provides core Datahike functions with positional/map forms, custody elision, and flat failures. | VERIFIED | `src/seon/db.clj:1-22,859-1353,1572-1599`; Datahike interfaces in `reference-code/datahike/src/datahike/api/specification.cljc`. The stronger “all direct Datahike calls are confined” claim is stale below. |

### Queryability and data-oriented database rules

| Claim | Verdict | Evidence checked |
|---|---|---|
| Program functions/tests/calls/private/workload/sink facts are queryable. | VERIFIED | `src/seon/fn.clj:313-384,695-779,935-971`; `resources/seon/schemas/seon.fn.edn`. |
| Dated counts “9 connection inputs, 42 database-value inputs, exactly 4 public custody outputs” are current. | UNVERIFIABLE | No retained report pins the query, basis, and result, and the program graph has changed since 2026-08-02. Establishing current counts requires rerunning the stated Datalog queries against a freshly published HEAD branch, which this reading audit did not do. |
| `tests-reaching` derives transitive test coverage from recorded `:seon.fn/calls`. | VERIFIED | `src/seon/fn.clj:695-735`; `src/seon/test/selection.clj:134-175`. |
| Open Malli maps are the rule and no `{:closed true}` appears in schema resources. | VERIFIED | Full `resources/seon/schemas/` search found no `:closed true`; Malli default is documented at `reference-code/malli/README.md:294`. |
| Important entity schemas derive identity attributes rather than storing kind/type. | VERIFIED | `src/seon/schema/internal.cljc:172-240`; catalog derivation at `src/seon/schema.clj:1798-1805,1918-1925`. |
| Every public source function has a complete Malli input/output schema. | VERIFIED | `test/seon/public_contract_test.clj:37-73` is non-vacuous and checks all `src`; current `src/seon/cluster/loop.clj:558-570`, the previously reported omission, now has a complete contract. |
| All first-party core reads/writes call `seon.db`; direct `datahike.api` reads remain only in the enumerated custody/listener owners. | STALE | `src/seon/schema.clj:657,2415-2434` directly calls `d/q` for admission/projection and is not one of the stated store/registry/branch-custody/listener exceptions. `src/seon/operator.clj:553-586` also directly queries. |
| Database values carry `:db-name`, `:t`, `:as-of`, `:since`, `:history`, and commit ID. | STALE | Committed identity contains only `:db-name`, `:t`, and `:datahike/commit-id`; temporal/speculative values deliberately have no commit ID: `src/seon/db.clj:149-188`; schema `resources/seon/schemas/seon.db.edn:120-127`. |
| Connection ID is `[store-id branch]` for self writer and adds backend remotely. | VERIFIED | `reference-code/datahike/src/datahike/store.cljc:44-55`. |
| Config is optional for reopen; explicit apply writes nothing when converged. | VERIFIED | `src/seon/config.clj:315-500`; operator config path in `script/seon/fresh_operator.clj:1410-1510`. |
| Provenance is transaction metadata rather than copied domain fields. | VERIFIED | `resources/seon/schemas/seon.db.edn` and transaction paths use `:seon.db/user`/`:seon.db/process`; no general created-by/created-at model exists. |

### Reactive context, rendering, and runtime contracts

| Claim | Verdict | Evidence checked |
|---|---|---|
| Context/status features derive from database facts rather than notification queues. | VERIFIED | `src/seon/problems.clj`; `src/seon/render.clj:620-698`; `src/seon/render/transcript.clj`. |
| Important schemas declare AI/HTML/form render functions. | VERIFIED | Examples include `resources/seon/schemas/seon.cluster.agent.edn:1-16` and error shapes across schema resources; selection is property-driven in `src/seon/render.clj:196-283`. The claim is normative, not universal render coverage. |
| Stream partials use latest-wins channels and only settled terminal state is durable. | VERIFIED | `src/seon/render/web.clj:1-15,1112-1118`; AI stream folding in `src/seon/ai.clj:724-819`; settled attempt facts in `src/seon/cluster/loop.clj:646-830`. |
| Render invalidation is interest-routed, equality-suppressed, multed, and per-tab sliding-1. | VERIFIED | `src/seon/render/web.clj:649-721,1112-1118,1276-1437`. |
| Program graph is produced by one clj-kondo pass over first-party `src`/`test` plus schema population. | VERIFIED | `src/seon/fn/analyzer.clj:1-149`; `src/seon/fn.clj:1052-1150,1322-1422`; `src/seon/program.cljc:29-43`. |
| Comment grammar and REPL rendering never encode output as comment-prefixed pseudo-entries. | VERIFIED | Current history emits explicit form/printed-value data (`src/seon/render/walk.clj:794-878`; `src/seon/render/transcript.clj:884-951`). |
| Internal observable readiness is event-driven; clocks are backstops. | VERIFIED | Flow joins/listeners and test event backstops implement this at `src/seon/flow.clj`, `src/seon/cluster/agent.clj:563-623`, `test/seon/test_support.clj:231-280`. It is a design rule, not a claim that no unjustified timeout remains. |
| Fail loud in development and degrade in production through one config dial. | VERIFIED | `config/default.edn` declares `:seon.config/on-core-error`; `src/seon/error.clj` and `src/seon/sci/eval.clj` consume it. |
| Classification is computed, never name/list/regex based. | STALE | The broad principle is aspirational, but workload classification is currently direct metadata, not computed reachability (`src/seon/fn.clj:313-380`), and open implementation uses explicit root maintenance data (`src/seon/schedule.clj:35-60`). |
| Instrumentation derives from program facts and reapplies on hot reload. | VERIFIED | `src/seon/instrument.clj`; installation/acquisition in `src/seon/sci/eval.clj:609-721,1270-1395`. |
| Human-visible sizes are always token estimates, never raw characters. | STALE | Current user-visible provider reasoning error reports a character count at `src/seon/ai.clj:840-855`; render packages also expose byte sizes (`src/seon/render/web.clj:692-701`). |

### Git, skills, REPL, test, resiliency, operator, and provider

| Claim | Verdict | Evidence checked |
|---|---|---|
| The shared checkout/index and path-limited commit procedure are required. | VERIFIED | Repository policy at `AGENTS.md:1064-1099`; Git supports `commit --only`. The historical count of 4,665 unpushed commits is separately unverifiable. |
| Exactly 4,665 commits were once unpushed. | UNVERIFIABLE | No retained census artifact or remote reference proves the historical count. A contemporaneous `git rev-list` capture would be needed. |
| The named skills exist at `.agents/skills/*/SKILL.md`. | VERIFIED | `data-oriented-clojure`, `seon-flow-architecture`, `data-modeling`, `datahike`, `clojure-testing`, and `repl` all exist. |
| Fresh cluster fork is about 17 ms. | VERIFIED | Later measurement: `docs/prds/sci-execution-runtime/research/branch-verbs-design-2026-08-07.md:196-197,386-389`. |
| MCP exposes `runtime_status` and `eval_clj` against live clusters. | VERIFIED | `script/seon/dev/mcp.clj:825-854`; registrations at `.codex/config.toml:1-4` and `.mcp.json:1-9`. |
| MCP advertises only those described JVM operations. | STALE | Current exact surface is **three** tools (`eval_clj`, `runtime_status`, `get_value`), and `eval_clj` has both `jvm` and `door` modes: `script/seon/dev/mcp.clj:825-867`; `test/seon/dev/mcp_bridge_test.clj:553-577`. |
| MCP discovery refreshes starts/stops/replacements on every call. | VERIFIED | `script/seon/dev/mcp.clj:11-15`; endpoint resolution at `270-302`. |
| Bare cluster ambiguity fails rather than silently selects. | VERIFIED | `script/seon/dev/mcp.clj:270-302`. |
| Named-session restart loses process-local REPL state, not database truth. | VERIFIED | Session map and endpoint identity are process-local at `script/seon/dev/mcp.clj:30-31,308+`; database state is external to the bridge. |
| The edit hook runs pre/post clj-kondo, Markdown/docstring checks, source publication, fault checks, and async review; it never runs tests. | VERIFIED | `bin/seon-hook:3-14,330-452,488-547,969-1088`; no test invocation in the hook. |
| Async review is specifically Gemini Flash. | UNVERIFIABLE | The hook calls external `agy -p` without a model selector at `bin/seon-hook:780-804`. Comments say Gemini, but neither source nor config proves the Flash model. |
| Review batches coalesce at most once per two-minute window and provider failure drops the batch. | VERIFIED | `bin/seon-hook:843-895,908-952`; default interval 120 seconds. |
| `bin/test` tiers are platform-first; bare means changed since green; `--all`, `--full`, `--platform`, `--changed`, and explicit namespaces have the documented meanings. | VERIFIED | `bin/test:1-24,43-57,91-203`. |
| `seon.dev.changed-test/run-changed!` shells `bin/test --changed` and owns no selector. | VERIFIED | `script/seon/dev/changed_test.clj:1-9,272-284,333-341`. |
| Canonical test fixture is `with-database` plus `extra-schema`. | VERIFIED | `test/seon/test_support.clj:152-165,379-475`. |
| Every test event await is bounded by `event-backstop-seconds`. | VERIFIED | Shared await owner at `test/seon/test_support.clj:24-26,231-280`; this establishes the prescribed fixture, not that no test bypasses it. |
| Fixed render profiles changed the affected fixture cost from 217 s to 6.2 s. | VERIFIED | Commit `1930dacd1` exists and is titled “Supply fixed render profiles in flow fixtures”; the same-day working-edge/test-fixture record retains the comparison. |
| All twelve cited 2026-08-13 fixture-fix commits exist. | VERIFIED | `100f03a40`, `b7bd25c34`, `8377a4a69`, `464fd5ddb`, `e8e37eb50`, `66cecb816`, `677f84f85`, `0ef66e742`, `1930dacd1`, `26e8cf84a`, `dc6604dac`, and `0a39f71d6` all resolve to commits with matching subjects. |
| Recursive deletion never follows symlinks and refuses escapes; sentinel regression exists. | VERIFIED | `src/seon/fs.clj:24-79`; `test/seon/test_support_test.clj:147-171`; `test/seon/operator_test.clj:238+`. |
| The 2026-07-29 cleanup incident deleted 55 tracked paths. | VERIFIED | Retained incident account at `docs/prds/sci-execution-runtime/research/tooling-sharpening-2026-07-29.md:137+`; current regression is present. |
| `bin/seon` command inventory in `AGENTS.md` is current. | STALE | Current operator additionally exposes `export` and `logs`: `script/seon/fresh_operator.clj:2756-2806`. |
| `bin/seon` default/force semantics for start/config/init/stop/down/reset match the prose. | VERIFIED | `script/seon/fresh_operator.clj:2756-2824`; source publication/refork paths at `1959-2202,2730`. |
| Stop/down act on exact pid/start-instant/generation and `--force` gates escalation. | VERIFIED | Process record and shutdown logic in `script/seon/fresh_operator.clj`; identity primitives in `src/seon/cluster/process.clj:1-44`. |
| `bin/acme` accepts only start/config apply/init/status/open/stop/down/logs and fixes cluster `acme`. | VERIFIED | `bin/acme:1-109`. |
| Default provider is DeepSeek through `seon.ai`, with per-agent config overlays and credential-variable name only. | VERIFIED | `config/default.edn:290-358,446-480`; `resources/seon/schemas/seon.config.ai.edn:1-84`; `src/seon/ai.clj:315-432`. |
| Attempts record effective settings, usage, and reasoning fields. | VERIFIED | `resources/seon/schemas/seon.ai.attempt.edn:1-15`; `src/seon/cluster/loop.clj:616-830`; normalization in `src/seon/ai.clj:910-929`. |

## Ranked stale claims

1. **Wrong run-holder rename in the active plan.** `plan/README.md:7` says the
   process attribute is now `:seon.agent.run/process`. HEAD uniformly declares
   and uses `:seon.cluster.run/process`; the plan is the wrong side. This is the
   highest-blast-radius poison because it is presented as a global terminology
   correction before the H1.
2. **Portable multi-tier execution is described as current.** The portable
   section and vocabulary assert tier leaves, result symbols, and
   `plan-execution`; HEAD is CLJ-only and none of the placement machinery
   exists. This can cause a rewrite to resurrect the deleted pod model.
3. **Built/unbuilt status in the vocabulary is inverted.** Root maintenance is
   marked `[TARGET]` despite a built five-task portfolio, while bare `docs`, the
   acquired-candidates index, canvas content, and package roots are described as
   current despite lacking owners.
4. **Flow topology and workload derivation are obsolete.** The graph is stated
   as two procs but has three, shared plumbing is under-enumerated, and
   transitive workload classification is claimed although HEAD only lifts
   direct metadata.
5. **The universal Markdown-frontmatter invariant is false.** Sixteen tracked
   specs lack any frontmatter, so the root instruction asserts a green invariant
   that the repository itself violates.
6. **`seon.effect` is overstated as the owner of db writes.** Database writes
   enter `seon.db/transact!` directly; calling them `effect/request!`
   capabilities misdescribes the three-shape effect model.
7. **MCP and operator command inventories are incomplete.** MCP omits
   `get_value` and the `door` mode; `bin/seon` omits `export` and `logs`.
8. **Database-value vocabulary overpromises one universal coordinate.** Temporal
   values do not carry commit IDs, and committed identity does not contain
   `:as-of`, `:since`, or `:history` fields.
9. **Direct Datahike-call confinement is false.** `seon.schema` and
   `seon.operator` still query `datahike.api` outside the enumerated exceptions.
10. **Several “current” vocabulary citations are line-drifted.** Workload,
    fork, render profile, elision, output-path, and identity-only ranges point at
    unrelated current code even where the underlying claim remains true.

### Markdown files violating the claimed universal frontmatter rule

All are under `docs/prds/sci-execution-runtime/specs/`:

- `w0.1-interrupt-merge.md`
- `w0.2-var-stamping.md`
- `w0.3-cancel-ghost.md`
- `w0.4-writer-pool.md`
- `w0.5-writer-ceilings.md`
- `w0.6-host-escape-hardening.md`
- `w0.7-hostile-battery.md`
- `w0.8-schema-restore-race.md`
- `w1.1-boot-contract.md`
- `w1.1-boot-resolution.md`
- `w1.3a-duplicate-limits.md`
- `w2-llm-fallback.md`
- `w4a-tier-teaching.md`
- `w8a-prd-archival.md`
- `wp-a-sci-error-classify.md`
- `wp-k-package-roots.md`

## Unverifiable claims

| Claim | What is missing |
|---|---|
| Universal multi-lane supervision, ≤15-minute transcript inspection, slot refill, and checkpoint push behavior. | Orchestrator/task telemetry with timestamps and Git remote events. |
| Codex/Claude instruction-discovery rules, resumed-session MCP binding, and client reload behavior. | Harness implementation or a durable, cited harness trace. |
| Exact historical “six lanes,” three wrong-attribution incidents, and 4,665 unpushed commits. | Contemporaneous incident reports/censuses with commands and outputs. |
| Every work unit begins with a dependency ledger and every public change follows the prescribed REPL sequence. | A formal queryable work-unit schema plus a complete audit; prose examples cannot prove a universal. |
| Exact current 9/42/4 database-custody counts. | The Datalog query, published HEAD basis/commit ID, and retained result. |
| Async review uses Gemini **Flash**. | An `agy` model selection/config record; the hook passes no model. |
| Semantic absence of every duplicate mechanism, compatibility path, or hand list. | A complete mechanism-level source audit; name/regex searches cannot prove this class absent. |

## Duplication map

These are rules stated more than once in root `AGENTS.md`. Locations are
grouped by the same operative requirement, not merely repeated words.

| Repeated rule | Locations | Risk |
|---|---|---|
| Keep the complete program ledger, one ordered spine, parallel portfolio, and refill every safe slot. | `AGENTS.md:88-199`, `539-546` | Largest duplication hotspot; scheduling changes require edits across two long sections. |
| Path-limited commits preserve foreign work; never use broad staging/discard. | `201-213`, `494-502`, `1064-1099` | Same shared-index rule appears in incident, lane, and Git sections. |
| Read dependency source under `reference-code/` before design; do not plan from memory. | `100-103`, `126-132`, `551-568`, `759-771`, `1158-1160` | Five copies, with different levels of specificity. |
| Use data-oriented Clojure before planning/writing/reviewing. | `572-578`, `826-841`, `1103-1113` | Three copies plus the external skill authority. |
| One mechanism; no hand lists, naming conventions, regex substitutes, or missing facts. | `20-64`, `624-661`, `781-823`, `1000-1047` | Broad principle is repeated across deletion, design gate, queryability, and runtime contracts. |
| `seon.db` is the one database namespace with dual forms/custody/error semantics. | Vocabulary row `727`, database section `914-937`, examples `781-823` | One semantic owner is restated in three forms; the confinement clause has drifted. |
| `current-src` publication does not update existing sovereign clusters. | `275-312`, `1377-1398`, `1404-1406` | Repeated in architecture and operator sections; currently consistent. |
| Flow workload law: explicit `:io`/`:compute`, avoid `:mixed`. | `287-351`, vocabulary `692`, `1000-1020`, skill list `1109-1111` | The proc rule is current, but the function-classification copy is stale. |
| Errors are flat values; core faults use one durable path. | `329-338`, `589-622`, `835`, `1017-1025` | Repeated at architecture, ethos, data rules, and runtime boundaries. |
| `bin/test` is the one gate with platform-first tier semantics. | `1232`, `1256-1267`, `1294-1316` | Three statements; currently consistent. |
| Test fixtures use canonical database population, explicit projection/environment, fixed profiles, and loud bounded awaits. | `1180-1215`, skill references `1112`, test-surface discussion `1270-1316` | The root duplicates the testing skill's intended owner. |
| No sandboxing; shared checkout/operator-root isolation instead. | `494-502`, vocabulary `691`, `1077-1099`, `1339-1342`, `1404-1406`, `1493-1504` | “Sandbox” is prohibited in several different senses; easy to confuse harness sandboxing, SCI context, and operator-root isolation. |
| Never edit `CLAUDE.md`; localized `AGENTS.md` is authority. | `229-245`, Git/shared-tree guidance, and lane conventions | Repeated policy; symlink state is current. |
| Read named specs end to end, never consume them via grep. | `463-468` (planning prerequisites) and `1480-1491` | The final section repeats and strengthens the earlier planning rule. |
| Recursive cleanup never follows symlinks. | `1319-1364` and operator-root safety `1367-1435` | One detailed incident plus the operator consequence. |
| Use full repository-relative Markdown links when reporting documents. | Documentation authority `413-455`, reporting rule `1471-1478` | Same navigability rule in two places. |

## Cross-file contradictions

### Wrong run-holder side

`docs/prds/sci-execution-runtime/plan/README.md:7` is wrong. The correct current
attribute is `:seon.cluster.run/process`:

- declaration: `resources/seon/schemas/seon.cluster.run.edn:90-92`;
- custody transitions: `src/seon/cluster/run.clj:178-210`;
- loop/agent/bootstrap use: `src/seon/cluster/loop.clj`,
  `src/seon/cluster/agent.clj`, and `src/seon/bootstrap.clj`;
- no `:seon.agent.run/process` occurrence exists outside the bad README line.

The plan also retains the older “receipts move under `seon.agent.run`” ruling at
`plan/README.md:164-172`, reinforcing the wrong rename.

### Other contradictions between root authority and active plan

| Plan claim | Root/HEAD contradiction | Verdict |
|---|---|---|
| `plan/README.md:16` calls itself current, while its “Current dependency spine” is the 2026-08-12 wind-down (`114-149`). | `plan/unsettled.md:19-90` explicitly supersedes that session state with the 2026-08-13 campaign, completed wave, rebirth proof, and end-to-end suite recovery. | README current-state block is STALE. |
| Orientation says there are “two implementations” (`plan/README.md:17`). | Root `AGENTS.md:247-256` and `deps.edn:1-4` say the old implementation was deleted and Git is the archive. | STALE. |
| N2 still specifies run claim epoch, lease, and heartbeat (`plan/README.md:3730-3740`). | Root `AGENTS.md:324-329` says no epoch/lease; HEAD custody is presence of `:seon.cluster.run/process`. | STALE historical target is not marked as such. |
| L18 says clusters always reset to current code/pages (`plan/README.md:3823`). | Root `AGENTS.md:275-312` and boot tests say existing clusters are sovereign and never synchronized; only explicit destructive refork changes them. | STALE and operationally dangerous. |
| Final measurement refers to a “three-process system” (`plan/README.md:3848-3853`). | Root `AGENTS.md:247-291` says one JVM process runs everything and can host many cluster branches. | STALE historical final-gate wording. |
| The active plan carries the 24.2-second pull as a known open (`plan/README.md:134-142`). | `plan/unsettled.md:66-69` says it did not reproduce at HEAD and measured 2.80 seconds, with cause attributed. | STALE current-state claim. |
| Plan maintenance rulings treat the root portfolio as scheduled work (`plan/README.md:530-638`). | Root vocabulary still marks the portfolio `[TARGET]`; HEAD has landed it. | Root `AGENTS.md` is the stale side. |
| Plan says no Shadow-CLJS in development (`plan/README.md:376-384`). | Root vocabulary still defines build via Shadow-CLJS (`AGENTS.md:680`). | Root `AGENTS.md` is the stale side. |

## Rewrite guidance

Keep the verified source contracts, but delete historical incident narration
unless it has a durable evidence link. Delete the portable/multi-tier section
rather than recasting it as a current mechanism. Convert built target rows to
source-grounded vocabulary, delete unbuilt non-target rows, update all
line-number citations, and make the operator/MCP inventories exact. Most
importantly, correct `plan/README.md:7` in the same rewrite wave so the two
highest-authority documents cannot teach different custody attributes.
