---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# Deletion-candidates audit — 2026-07-23

## Scope and method

This is a read-only census, not authorization to delete. It reconciles the
2026-07-23 reconciled completion plan, rulings 16–19, the conversion wiki, and
the `src/seon/AGENTS.md` one-mechanism table against the current tree.

Evidence was found with `rg`, then verified by reading the owning source and
its callers. Line counts are current `wc -l` weights, not estimates of lines
that a mixed-file refactor will remove. A zero literal require edge is not
enough to call a namespace dead: Shadow build mains, preloads, dynamic
`requiring-resolve`, and test-only consumers were checked separately.

The controlling architecture facts are:

- The destination routes ordinary execution to JVM sci; only
  `seon.packages.js.*` remains Bun-local
  (`program-synthesis-2026-07-21.md:1071-1126`, rulings 18–19).
- Loop migration precedes child/self-host deletion. Runs become
  CAS-claimable database state before the original W5 cut
  (`program-synthesis-2026-07-21.md:1118-1126`).
- `seon.eval` self-host and per-agent execution children die at cutover;
  UI/render/context, LLM I/O, and the client remain pod-legitimate
  (`program-synthesis-2026-07-21.md:1102-1110`).
- Package wrappers are platform-segregated by namespace, and diffusion keeps
  its quarantined bootstrap/build path
  (`program-synthesis-2026-07-21.md:1538-1551`; ruling 16).

## 1. Marked shims and compatibility residue

Raw matches for `compatibility|superseded|deprecated|TODO|FIXME|HACK` were
read in context. Most `superseded` matches are live latest-run semantics, and
there are no genuine source-code `TODO`, `FIXME`, or `HACK` markers.

| Candidate | Weight | Verified evidence and blockers | Migration that frees it | Unit |
|---|---:|---|---|---|
| `src/seon/host/context.clj:202-242` compatibility database entries | 1,404-file LOC; 41-line marked band | `writer-session`, `close-session!`, private `writer-call!`, and `resolve-head!` delegate to `seon.db.host`. Direct outer callers remain in `src/seon/host.clj:90,234,244,249,327`; `src/seon/host/sample.clj:242` and `src/seon/host/graduate.clj:323` still call context query wrappers; writer tests call `context/query-writer!` and `query-writer-at!` (for example `test/seon/host_graduate_writer_test.clj:48,143` and `test/seon/host_conformance_writer_test.clj:316-330`). Context internals below `host/context.clj:957` still route database work through `writer-call!`. | Move host construction/close/head resolution to `seon.db.host`; move context internals to `db.host/call!`; translate or retire legacy `::context/*` session keys at the boundary. | Existing NS-4/host-decomposition cleanup; small follow-on, **not safe now**. |
| `src/seon/agent/ctx/transcript.cljs:102-105` stored/profile compatibility dial | 1,551 LOC | `::result-handles?` is explicitly retained only as a compatibility dial. It is still written by `config/system.edn:425` and `src/seon/repl/autocomplete.cljs:151`, and read at `transcript.cljs:1319`. | Remove manifest/autocomplete population and the schema/read branch after confirming database-profile reset/migration policy; keep inline result rendering as the single behavior. | New small cleanup; **not safe now**. |
| `src/my/plan/internal.cljc:206` `compatibility-tree` | 2,120 LOC | Live projection called by `src/my/plan.cljc:1773-1778`; “compatibility” describes output shape, not a migration shim. | None. | Keep. |
| Other `superseded`/compatibility matches | n/a | `script/seon/dev/issues.clj:7,140` is the valid issue lifecycle. `src/seon/client.cljs:665,1880-1892`, `src/seon/agent/{loop,run,turn}.cljs`, `src/seon/runtime/admission.cljs:159`, and `src/seon/web/serve.cljs:1526` implement current latest-run/publication semantics. `src/seon/schema.cljc:678` documents a live overlay behavior. | None. | Keep. |

**Sweep result:** two genuine compatibility residues, zero safe-now removals.
The roadmap's “one marked compat shim” count sees the host band but misses the
stored transcript dial.

## 2. Cutover-death inventory

The original W5 deletion is not a list of whole files. Two roots are whole-file
deaths, two large owners are mixed, and several helpers must survive or move.

| File / band | Weight | Why it dies or survives; consumers to repoint first | Unit |
|---|---:|---|---|
| `src/seon/execution/runtime.cljs:1-706` | 706 | Entire namespace is the execution-child composition root (`:1-3`); child eval is `:632-681`, compiled render/eval/view table `:683-701`, and `-main` `:703-706`. Production entry edges are Shadow builds `shadow-cljs.edn:142-150,178-186`; tests require it at `test/seon/agent/ctx_teaching_test.cljs:10`, `test/seon/execution/integration_driver.cljs:11`, and `test/seon/execution/runtime_test.cljs:16`. Before deletion, move `render-prompt!`/agent-view rendering into the pod and route all eval to the JVM host. | Existing W5, **at cutover**. |
| `src/seon/execution.cljs:1-1514` protocol + child implementation | 1,514 | Mixed. Constants/schemas near `:20-285` survive by promotion to `.cljc`; codecs need a platform leaf decision. Child machinery begins decisively at `begin-invocation!` `:1310-1362`, then cancel/shutdown/receive `:1364-1427`, `start-child!` `:1429-1463`, and Bun IPC/artifact `-main` `:1469-1514`. Production consumers include `execution/host.cljs:29`, `execution/runtime.cljs:37`, `agent/turn.cljs:21`, `agent/loop.cljs:21`, `web/datastar.cljs:30`, and `web/reactive/call.cljs:53`; tests span agent-loop/retry, web, and execution suites. | Existing NS-5 + W5: promote shared protocol, delete child bands **at cutover**. |
| `src/seon/execution/host.cljs:111-120,179-209,493-572,655+` child lane | 1,404-file LOC | Mixed. Child state, subprocess send/kill/evidence, `spawn-child!`, and retirement die. JVM UDS lane `:574-653`, generic correlation, and mixed routing around `:817-1048` survive/rehome as `seon.execution.dispatch`. Consumers to repoint include `client.cljs:75`, `agent/turn.cljs:22`, `web/{datastar,serve}.cljs`, `web/reactive/call.cljs`, and their tests. | Existing NS-5 + W5; refactor after loop migration, remove child band **at cutover**. |
| `src/seon/eval.cljs:1-5387` | 5,387 | Self-host engine is planned to die: `cljs.js`/Shadow imports `:46-53`, bootstrap cache `:67`, bootstrap initialization `:415-455`, raw eval `:1185`, and public eval `:1294`. Whole-file deletion is blocked by live helper consumers: `repl.cljs:50`, `warn.cljs:43`, `render.cljs:30`, `agent/turn.cljs:20`, `agent/ctx.cljs:18`, `agent/ctx/menu.cljs:14`, route/web lookup and render callers, and loop helpers. Extract/repoint race timeout, lookup/ns introspection, error rendering, parser/tee, and other retained data transformations before deleting the engine owner. | Existing W5, **at cutover after helper extraction**. |
| `src/seon/eval/receipt.cljc:1` | 66 | Survives. It is the portable durable receipt owner used by `eval.cljs:68` today and `runtime/recovery.cljs:20`; its tests should migrate, not disappear. | Keep; W5 consumer repoint. |
| `src/seon/eval/bootstrap_cache.cljs:1` | 63 | Production edge from `eval.cljs:67` dies, but `diffusion/worker/eval.cljs:80` still needs it. Ruling 6 keeps the self-host bootstrap quarantined for diffusion. | Move/rename under the diffusion owner if needed; **not a whole-file deletion**. |
| `src/seon/repl.cljs:1-124` | 124 | Bootstrap compile-state/dev-init facade. No current production caller; direct consumers are self-host eval tests. It becomes orphaned with `seon.eval`. The roadmap does not explicitly rule on retaining this dev facade. | Likely W5 cutover death; owner decision required. |
| `src/seon/analyzer_info.cljs:1-287` | 287 | Bootstrap-state analyzer; only production edge is `eval.cljs:56`, plus `analyzer_info_test`. Persistent source parsing already lives elsewhere. No diffusion edge exists. | Likely W5 cutover death; confirm no intended dev surface. |
| `src/seon/subprocess.cljs:1-264` | 264 | **Survives.** Removing the child consumer at `execution/host.cljs:33,522-566` does not remove live Bun-leaf consumers `repl/autocomplete.cljs:42`, `agent/search/internal.cljs:14`, and `agent/shell/internal.cljs:16`. | Keep; only remove the host child edge at W5. |
| `src/seon/host/session.clj:12-78` hand protocol projection | 281-file LOC; 67-line band | The band is explicitly marked for W5 deletion. Replace it with promoted `seon.execution.cljc` constants/schemas; the remainder of session management survives. | Existing NS-5 + W5, **at cutover**. |
| Shadow/operator/release child artifact plumbing | mixed | Remove Shadow `:execution` and `:acme-execution` builds (`shadow-cljs.edn:142-150,178-186`). Preserve diffusion worker builds and bootstrap. Repoint execution/bootstrap digest assumptions in `script/seon/dev/artifact.clj:348,454-513,811-815,877-1024`, `release.clj:759-822,1035-1072`, and `process.clj:64,270,510-511,796-816,2811`. | Existing W5 packaging/operator subunit. |
| `src/seon/agent/loop.cljs:1-1331` | 1,331 | Live driver, required by `client.cljs:53` and lifecycle/scheduler paths. The reconciled plan says loop migration refactors `turn.cljs`/`run.cljs` into resumable database steps; it does **not** prove the whole loop namespace dies. The P4 design still assigns claim/drive work across loop/run/turn. | Band audit **after loop migration**; do not schedule whole-file deletion. |

The grounded W5 scope is therefore:

- whole roots: `execution/runtime.cljs`, then `eval.cljs` after extraction;
- likely whole roots needing owner confirmation: `repl.cljs`,
  `analyzer_info.cljs`;
- mixed-band cuts: `execution.cljs`, `execution/host.cljs`,
  `host/session.clj`;
- build/operator/release removal; and
- explicit retention of `eval/receipt.cljc`, the diffusion bootstrap, and
  `subprocess.cljs`.

## 3. Dead/orphaned code, rename residue, and requested classifications

Every apparent zero-source-fan-in namespace was checked for build, preload,
dynamic, downstream, and test edges. **No tracked dead namespace was
verified.**

| Namespace | Weight | Linkage evidence | Classification / unit |
|---|---:|---|---|
| `src/seon/demo.cljs:1` | 14 | Shadow preload in `shadow-cljs.edn:66,107,130`; downstream override fixture `examples/third-party-override/src/example/overrides.cljs:28,30`. | Live build fixture; keep. |
| `src/seon/warn.cljs:1` | 962 | Production requires `agent/loop.cljs:28`, `agent/message/pod.cljs:5`, `agent/ctx/warnings.cljs:13`; dedicated tests. | Live; keep. |
| `src/seon/log.cljs:1` | 459 | Broad production fan-in including `agent/{schedule,turn,run,loop}.cljs`, `db/session.cljs`, AI, web, reactive, diffusion, and client. The `eval.cljs` edge is only one consumer. | Live; keep after cutover. |
| `src/seon/platform.cljs:1` | 62 | Required by blob leaf, web, launch/config/client, providers, diffusion, UDS, and web pod. | Live platform leaf; keep. |
| `src/seon/items.cljs:1` | 20 | Required by `src/my/data.cljs:14`, `src/my/kb.cljc:16`, and `src/seon/client.cljs:137`; tested directly. | Live shared schema owner; keep. |
| `src/seon/derive.cljs:1` | 547 | Required by web, client, schedule, agent, loop, render/system, transcript, and subagent context; only `execution/runtime.cljs` consumer dies. | Live cycle-breaking projection owner; keep. |
| `src/seon/embed/preflight.clj:1` | 205 | Dynamically resolved by `src/seon/db/server.clj:569`. | Live JVM preflight entry; keep. |
| `src/seon/diffusion/worker/{parse,eval}.cljs:1` | 196 / 762 | Standalone Shadow build mains at `shadow-cljs.edn:211,248`; zero source require is expected. | Experimental linkage; no judgment. |

### Diffusion linkage

| Namespace | Weight | Current linkage |
|---|---:|---|
| `diffusion/grammar.cljc:1` | 73 | Used by `oracle.cljs:36`, `retrieval.cljs:45`, and death-row `eval.cljs:64`. |
| `diffusion/retrieval.cljs:1` | 678 | Used by `scaffold.cljs:43`, `oracle.cljs:37`; direct test consumer. |
| `diffusion/oracle.cljs:1` | 233 | Experimental entry surface with direct test consumer. |
| `diffusion/scaffold.cljs:1` | 180 | Experimental entry surface with direct test consumer. |
| `diffusion/gemma.cljs:1` | 707 | Opt-in provider; direct provider/typeahead tests. |
| `diffusion/worker/eval.cljs:1` | 762 | Standalone Shadow main. |
| `diffusion/worker/parse.cljs:1` | 196 | Standalone Shadow main. |

Current diffusion source weight is 2,829 LOC. This table reports linkage only.

### Rename residue

`rg` found zero current source/test/script/Shadow references to the completed
old names `seon.worker-validator`, `seon.worker-eval`,
`seon.ai.diffusiongemma`, `seon.repl.internal`, `seon.state`, or
`seon.indexing`. No stale alias from the completed NS-series was verified.
The pending `seon.execution.host` → `seon.execution.dispatch` rename remains
coupled to NS-5/W5 and is not residue yet.

## 4. Duplicate mechanisms against the landed seam

| Candidate | Weight | Evidence and blocker | Disposition / unit |
|---|---:|---|---|
| Direct `datahike.api` in `src/seon/embed.clj:76` | 1,288 | Calls occur at `:245,307,318,429,439,899,958,986,1118,1141,1167`. The namespace doc `:45-73` identifies it as the active JVM writer/heavy-work embedding authority composed by the writer server. | Intentional boundary exception named by runtime architecture; live experimental embedding lane, not duplicate/deletion. |
| Direct `datahike.api` in `src/seon/embed/preflight.clj:18` | 205 | Memory-database create/connect/transact/release/delete at `:128-169`; invoked as database-server preflight. | Intentional preflight leaf; keep. No other direct Datahike callers outside `src/seon/db/**`. |
| Declared direct filesystem leaves | varied | `agent/fs.cljs:9` (849), `agent/fs/internal.cljs:8` (314), `my/blob/leaf.cljs:9` (500), and `log.cljs:64` (459) own their native effect. Diffusion workers are experimental leaves. | Keep. |
| `eval/bootstrap_cache.cljs:23` direct filesystem | 63 | Self-host cache; main execution consumer dies, diffusion consumer remains. | W5 + diffusion relocation, not duplicate implementation. |
| Mid-logic direct filesystem reads | varied | `config.cljs:129,157` (1,114); `agent/ctx.cljs:130,144` (2,161); `agent/search/internal.cljs:76,293` (522); `web/brand.cljs:137` (238); `web/serve.cljs:24` (2,124); `client.cljs:1212` (3,067); `repl/autocomplete.cljs:25` (809); guarded CLJS fallback `my/skills.cljc:114` (350). These violate the conversion wiki's “platform residue at edges only” criterion, but their containing mechanisms are live. | New bounded platform-leaf consolidation units, except any edge naturally removed by W5. **Refactor candidates, not deletion candidates.** |
| Embedding retry in `embed.clj:611-690` | 1,288-file LOC | Uses the shared `seon.retry` strategy combinators but hand-runs a blocking JVM loop because `with-retry!` is CLJS async. It also classifies transient failures by message/regex at `:655-666`. | Not a second retry policy; retain. Move classification to the existing error/config contract in an embedding cleanup unit. |
| Other error/retry shapes | n/a | Agent LLM retries call shared `seon.retry`; DiffusionGemma delegates to the turn owner. Web reactive calls normalize through `seon.error`. Diffusion worker wire errors match the experimental execution protocol. | No verified duplicate deletion path. |

## 5. Stale and cutover-bound tests

These are current weights. “Delete” means only when the replacement host/loop
proof exists; “migrate/split” means retained contract coverage must move before
child/self-host assertions disappear.

| Test namespace / assertion | Weight | Blocker and fate | Unit |
|---|---:|---|---|
| `test/seon/execution_process_test.clj:1` | 301 | Real Bun execution children. Delete after child retirement proof. | W5 cutover. |
| `test/seon/execution/integration_driver.cljs:1` | 226 | Child integration driver. Delete with child build. | W5 cutover. |
| `test/seon/eval/{auto_refer,memory_safety,print_capture,promise_ergonomics,prose_demote,repair_batch,require,result_var}_test.cljs:1` plus `test/seon/repl_parity_test.cljs:1` | 1,038 + 85 | Pin self-host compiler/engine semantics. Delete only after retained helper/host parity tests are in place. | W5 cutover. |
| `test/seon/execution/runtime_test.cljs:1` | 955 | Split: migrate pod render/view coverage; delete child eval-batch coverage. | W5 render relocation. |
| `test/seon/execution/host_test.cljs:1` | 1,667 | Split: delete child spawn/IPC/retire; retain JVM session/dispatch coverage. | NS-5 + W5. |
| `test/seon/execution_test.cljs:1` | 1,325 | Split: retain promoted protocol/codec coverage; delete child-owner coverage. | NS-5 + W5. |
| `test/seon/eval/receipt_test.cljs:1` | 717 | Durable receipt contract survives through `eval/receipt.cljc`; migrate tier assumptions. | Keep/adapt in W5. |
| `test/seon/instrument_smoke_test.cljs:1` | 84 | Move relevant instrumentation proof to JVM host surface. | W5 host parity. |
| `test/seon/eval/race_timeout_test.cljs:1` | 77 | Tests a pod timeout utility that may survive extraction. | Keep/migrate, not deletion. |
| `test/seon/runtime/recovery_test.cljs:101,173-181` | 702-file LOC | `(js/process.exit 17)` models child death. Replace with host-session-loss/claim recovery evidence. | Loop migration + W5. |
| `test/seon/agent_loop_test.cljs:436-469` | 1,048-file LOC | Asserts child pid/retired prose. Replace with claimant/dispatch state. | Loop migration + W5. |
| `test/seon/ctx_test.cljs:230,275` | file mixed | Asserts retiring-child guidance. Replace when rendered guidance changes. | W5 teaching cleanup. |

The five direct execution test files total **4,474 LOC**
(`execution_test`, `execution/host_test`, `execution/runtime_test`,
`execution/integration_driver`, `execution_process_test`). That already exceeds
the roadmap's older “~2,800 test LOC” estimate
(`program-synthesis-2026-07-21.md:543`). The ten tests under
`test/seon/eval/` total 1,957 LOC, but 717 LOC of receipt coverage survives;
the self-host candidate portion is 1,240 LOC.

## Ordered deletion queue

### Safe now

- **None.** No tracked dead namespace or caller-free genuine shim was verified.
- Do not count untracked empty directories or build cruft as code deletion
  candidates; they were outside this source census.

### After loop migration

1. Repoint pod-local loop/run/turn scheduling to durable claimed steps and
   replace child-pid/retirement assertions with claimant/phase evidence.
2. Audit `agent/loop.cljs` bands against the landed driver. The current
   evidence does not authorize whole-namespace deletion.
3. Remove the transcript result-handles compatibility dial after its
   manifest/autocomplete writers and profile/reset policy are settled.
4. Finish the `host/context.clj:202-242` migration to `seon.db.host`; this is
   independent of child deletion once all host/context call shapes and keys
   have moved.
5. Consolidate mid-logic `node:fs` reads into existing platform leaves as
   bounded owner-specific cleanups. These reduce duplicate boundary residue
   but are not deletion units themselves.

### At cutover

1. Move `execution.runtime/render-prompt!` and agent-view rendering into the
   pod; prove all ordinary eval/authored calls use the JVM host.
2. Promote the shared execution protocol to `.cljc`, repoint
   `host/session.clj`, and retain platform codecs in one explicit leaf.
3. Remove the Bun child lane from `execution/host.cljs`, then rename/rehome
   the survivor as `seon.execution.dispatch`.
4. Delete `execution/runtime.cljs` and child bands of `execution.cljs`;
   remove the `:execution`/`:acme-execution` Shadow builds and their
   operator/release/artifact plumbing.
5. Extract retained `seon.eval` data transformations, keep
   `eval/receipt.cljc`, then delete `eval.cljs`.
6. Delete `repl.cljs` and `analyzer_info.cljs` if the owner confirms no
   retained dev surface; otherwise relocate the intended surface explicitly.
7. Move/quarantine `eval/bootstrap_cache.cljs` under diffusion; do not delete
   the diffusion bootstrap or worker builds.
8. Delete child/self-host tests and migrate the retained protocol, render,
   receipt, timeout, instrumentation, and recovery assertions.
9. Flip the cutover/census gate and run the planned U10 proof. This report does
   not perform or claim that proof.

## Bottom line

- Verified safe-now tracked namespace deletions: **0**.
- Genuine marked compatibility residues: **2**.
- Whole production roots scheduled to die at W5: **2 certain**
  (`execution/runtime.cljs`, `eval.cljs`) plus **2 owner-confirmation**
  (`repl.cljs`, `analyzer_info.cljs`).
- Mixed production owners requiring band deletion/promotion: **3**
  (`execution.cljs`, `execution/host.cljs`, `host/session.clj`).
- Verified dead/orphan namespaces: **0**.
- Verified stale completed-rename references: **0**.
- Direct execution test weight: **4,474 LOC**, plus self-host eval candidate
  tests and scattered child-specific assertions.
