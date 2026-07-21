---
type: research
status: active
tags: [research, architecture]
---

# NS-0.5 / NS-5 design review — sol read-only pass (2026-07-21 night)

Authored by the read-only sol design-review lane on the post-NS-1/2/3/4 tree;
orchestrator-accepted 2026-07-21 night (see the anchor Execution-state entry
for the acceptance judgment and unit cut NS-0.5a/b/c). Supersedes the
namespace-hierarchy design's NS-0.5 counts and the NS-5 single-bundle framing.

# Read-only namespace design review

Snapshot: current working tree on 2026-07-21, including the in-flight NS-4 split. I made no edits and did not treat current `host.clj` line placement as stable.

## 1. NS-0.5 — internals extraction

### Current production require counts

I counted only actual `ns`-form requires under `src/`, excluding docstrings, comments, tests, and the permitted public parent.

| Internal namespace | Current violations | Production consumers |
|---|---:|---|
| `seon.repl.internal` | **10** | `my.plan.internal` (`src/my/plan/internal.cljc:20`), `worker-validator` (`src/seon/worker_validator.cljs:57`), `diffusion.retrieval` (`src/seon/diffusion/retrieval.cljs:47`), `ai.openai-compat` (`src/seon/ai/openai_compat.cljs:59`), `diffusion.oracle` (`src/seon/diffusion/oracle.cljs:35`), `client` (`src/seon/client.cljs:98`), `agent.turn` (`src/seon/agent/turn.cljs:24`), `eval` (`src/seon/eval.cljs:74`), `agent.ctx.menu` (`src/seon/agent/ctx/menu.cljs:15`), `agent.loop` (`src/seon/agent/loop.cljs:24`) |
| `my.plan.internal` | **4** | `repl.autocomplete` (`src/seon/repl/autocomplete.cljs:32`), `execution.runtime` (`src/seon/execution/runtime.cljs:13`), `ai.generate-code` (`src/seon/ai/generate_code.cljs:10`), `agent.loop` (`src/seon/agent/loop.cljs:10`) |
| `seon.db.internal` | **2** | `client` (`src/seon/client.cljs:72`), `db.id` (`src/seon/db/id.cljc:22`) |
| `seon.schema.internal` | **6** | `db.datahike.schema` (`src/seon/db/datahike/schema.clj:26`), `host.record` (`src/seon/host/record.clj:25`), `db.writer` (`src/seon/db/writer.clj:36`), `client` (`src/seon/client.cljs:111`), `db.internal` (`src/seon/db/internal.cljs:12`), `eval` (`src/seon/eval.cljs:77`) |
| `seon.eval.internal` | **1** | `runtime.recovery` (`src/seon/runtime/recovery.cljs:20`) |
| `seon.agent.internal` | **1** | `agent.lifecycle` (`src/seon/agent/lifecycle.cljs:13`) |

Direct test dependencies, excluded above, are respectively 6, 3, 2, 0, 1, and 1. Tests should be rewired with the production move, but counting them as architectural consumers obscures the production graph.

| Type | CORRECTION |
|---|---|
| **CORRECTION** | The roadmap’s `13 / 9 / 7 / 6 / 2 / 1` production counts at `program-synthesis-2026-07-21.md:211-215` are now **10 / 4 / 2 / 6 / 1 / 1**. At least some inflated raw counts can be reproduced by bracket-oriented searches matching wikilinks or quoted vectors rather than `ns` forms; for example `src/seon/repl.cljs:4` mentions `[[seon.repl.internal]]` but does not require it (`src/seon/repl.cljs:48-58`). |

### Recommended disposition by internal

#### `seon.repl.internal`: rename, do not extract

This is a coherent, shared parser contract, not private machinery. Its public operations cover fencing, source location, structural reads, parsing, and program projection (`src/seon/repl/internal.cljc:124`, `:868`, `:897`, `:941`, `:1161`, `:1424`, `:1508`). Production callers consistently use those parser operations: `parse-forms` in the validator (`src/seon/worker_validator.cljs:92`), diffusion (`src/seon/diffusion/retrieval.cljs:388`), and agent turn processing (`src/seon/agent/turn.cljs:587-589`); `form-source-at` is a client source-location service (`src/seon/client.cljs:1247-1265`).

Recommendation: rename the whole namespace to `seon.repl.parse`. Do not split it into several tiny parser namespaces.

Timing: coordinate with the repl/autosuggest checkout, but execute at its next clean handoff—not at W5. Only `seon.eval` among the ten consumers is clearly death-row; delaying does not materially reduce the surviving blast radius.

#### `my.plan.internal`: keep internal; repair its public boundary

The file is genuinely the implementation behind `my.plan`: the public parent requires it (`src/my/plan.cljc:9-12`) and delegates extensive plan projection/compiler work to it. Renaming 2,124 lines wholesale would expose exactly the machinery the convention intends to hide.

The violations have different fixes:

- `execution.runtime` already requires `my.plan` and separately requires the internal only for load reachability (`src/seon/execution/runtime.cljs:7-14`). Delete the redundant internal dependency.
- `repl.autocomplete` should load `my.plan`, not its internal. Its profile currently stores the internal render symbol (`src/seon/repl/autocomplete.cljs:130-138`); expose the render entrypoint through `my.plan`.
- `agent.loop` calls lifecycle policy `maybe-consult!` directly (`src/seon/agent/loop.cljs:499-503`). That is a real public plan/agent-loop contract and should be a `my.plan` entrypoint.
- `ai.generate-code` reaches generic failure and request-key helpers (`src/seon/ai/generate_code.cljs:621-681`). Those should be locally owned or exposed through a deliberately named public plan boundary, not imported from the internal.

There is also a deeper leakage: public schemas and `generate-code` exchange `:my.plan.internal/namespace-steps` and `:my.plan.internal/ready-steps` (`src/my/plan.cljc:74-81`, `src/seon/ai/generate_code.cljs:406-447`). If these remain a cross-namespace contract, extract that narrow generated-plan vocabulary/compiler surface to `my.plan.generation`. Do not use that observation to move ordinary plan rendering and reconciliation out of the internal.

Timing: wait for the other checkout’s coordinated boundary. Nothing touching `my.plan.internal`, its render symbols, or `repl.autocomplete` should proceed independently.

#### `seon.db.internal`: keep internal; extract one storage seam and delete one false dependency

Most of this file is honestly `seon.db` transaction machinery: process-local scope, Malli→Datahike derivation, normalization, validation, provenance, and error conversion (`src/seon/db/internal.cljs:17-75`, `:84-230`, `:266-511`).

Two consumers need different treatment:

- `db.id` calls `assert-invocation-shape!` at `src/seon/db/id.cljc:1358`, but immediately preceding validation already requires a map with vector transaction data at `src/seon/db/id.cljc:1287-1293`. Consolidate the allocator validation locally and remove the internal require; extraction is unnecessary.
- `client` calls `encode-edn-slot-values` when building initial data (`src/seon/client.cljs:1065-1087`), while `seon.db` uses the same encoder for normal transactions (`src/seon/db.cljs:825`). This is a real shared storage-normalization seam. Extract `edn-encoded-attr?` plus `encode-edn-slot-values` (`src/seon/db/internal.cljs:235-264`) to a proper narrow owner such as `seon.db.storage`.

I would not expose the entire internal through wrappers merely to satisfy the require law.

#### `seon.schema.internal`: real extraction

This internal mixes two categories:

- reusable Malli-form inspection—primitive forms, property extraction, map inspection, enum members, nilability (`src/seon/schema/internal.cljc:18-67`, `:154-162`);
- private `schema/register!` admission and entity-shape derivation (`src/seon/schema/internal.cljc:69-152`, `:164-214`).

The six external consumers use the first category: `host.record` reads attribute properties (`src/seon/host/record.clj:283-299`), the writer reads map shape/properties/entries (`src/seon/db/writer.clj:472-475`), and the CLJ schema bridge consumes the primitive registry (`src/seon/db/datahike/schema.clj:298`).

Recommendation: extract the reusable functions to `seon.schema.form` as `.cljc`; leave registration gates and parent-only machinery in `seon.schema.internal`. Also replace the duplicate form-property implementation currently described at `src/seon/schema/internal.cljc:22-27`.

This is the strongest genuine extraction in NS-0.5.

#### `seon.eval.internal`: promote as a receipt owner, not generic internal

The entire file is a coherent receipt contract: receipt state and start/terminal transaction builders (`src/seon/eval/internal.cljs:11-26`, `:28-67`). `runtime.recovery` uses the terminal builder (`src/seon/runtime/recovery.cljs:392`).

Recommendation: move it to `seon.eval.receipt` and promote it to `.cljc`. This also gives the JVM host a canonical receipt dependency, though `host.record`’s richer terminal row must still be composed around it.

| Type | CORRECTION |
|---|---|
| **CORRECTION** | The deletion inventory says `host.record` requires `seon.eval.internal`; it does not. It independently mirrors `start-tx-data` (`src/seon/host/record.clj:305-334`) and builds a richer terminal transaction. The present direct production violation count is therefore one, not two. |

Do this after the NS-4 source freeze ends and with the U4/W5 owner’s approval, because `seon.eval` is a protected program surface.

#### `seon.agent.internal`: rename wholesale

This is not generic agent internals. It owns one management-authorization rule, its pull selector, and authorization error values (`src/seon/agent/internal.cljs:8-55`). Both `seon.agent` and `seon.agent.lifecycle` legitimately consume that policy (`src/seon/agent.cljs:865-937`, `src/seon/agent/lifecycle.cljs:176-181`).

Recommendation: rename the whole file to `seon.agent.authorization`. Extraction beyond that would be needless fragmentation.

### Dependency-safe order

1. Now, outside the NS-4 freeze: `seon.agent.authorization`; remove the redundant `db.id → db.internal` edge; extract the DB storage encoder if `client` is free.
2. After NS-4 lands: extract `seon.schema.form`; promote `seon.eval.receipt` with its host integration.
3. At the repl/plan checkout handoff: rename `seon.repl.internal → seon.repl.parse`, repair `my.plan` public seams, and move the generated-plan vocabulary if accepted.
4. Then run the structural internal-boundary gate over parsed `ns` forms, with tests separately reported.

## 2. NS-5 — W5-window bundle

### Judgment: split the bundle

The remaining W5 work is not one coherent “rename rider.” It contains three timing classes.

#### Move earlier

- `seon.repl.internal → seon.repl.parse`: do it at the repl-lane boundary. It remains widely shared after W5.
- `seon.eval.receipt`: promote before the cutover if possible, reducing W5’s semantic payload.
- Extract the surviving source parser from `seon.analyzer-info` before W5; see below.

#### Keep coupled to W5

- Delete child-only execution/eval bands.
- Collapse and rename `seon.execution.host → seon.execution.dispatch`. Today it explicitly owns two lanes (`src/seon/execution/host.cljs:102-108`); the name becomes more misleading when only host-session dispatch survives.
- Promote the portable execution wire contract and remove the duplicated projection from its new location, `seon.host.session`.
- Remove the diffusion fence allowlist only when the last edge dies. The remaining violation is real: `seon.eval` requires grammar at `src/seon/eval.cljs:63` and calls it at `:3990-3996`; the allowlist row is `test/seon/diffusion_fence_test.cljs:16-19`.
- Reset clusters with the `execution.host → dispatch` persisted-key rename.

#### Move later or make a separate extraction

The residual eval render/timeout/lookup organization should not be decided incidentally inside a mass deletion commit. A clean shape is:

- `seon.eval` `.cljc`: durable eval vocabulary and receipt transition contract;
- `seon.eval.render` `.cljc`: genuinely portable row rendering;
- `seon.eval.lookup` `.cljs`: `globalThis` symbol lookup;
- `seon.eval.timeout` `.cljs`: Promise race/defer mechanics.

That keeps `seon.eval` as the durable key owner without pretending JS edges are portable.

### Execution `.cljc` seam

The NS-4 implementer is directionally right: `seon.execution` is the honest cross-runtime wire-contract owner. The current duplication in `seon.host.session` is explicit (`src/seon/host/session.clj:1-13`) and includes protocol constants and schemas (`:14-78`).

However, only the data contract is portable now. The design’s “lines 21–329 PORTABLE-NOW” claim is too broad:

- Transit IPC writers/readers and `TextEncoder` are JS-specific (`src/seon/execution.cljs:204-206`).
- diagnostics test `js/Promise` and use `goog/typeOf` (`src/seon/execution.cljs:238-282`);
- bounded result size uses JS text encoding (`src/seon/execution.cljs:309-333`);
- the JVM host measures UDS Transit bytes through a different implementation (`src/seon/host/session.clj:219-244`).

| Type | CORRECTION |
|---|---|
| **CORRECTION** | Promote constants, schemas, and transport-neutral validation—not the Bun IPC codec as part of a supposedly portable wire contract. The Bun string codec dies with the child lane; UDS encoding remains in `seon.db.transport.uds`. |
| **CORRECTION** | NS-5 must delete band A from `src/seon/host/session.clj:12-78`, not from `host.clj`. The design document’s location is stale after NS-4. |

There is no fundamental conflict with NS-4. The split improves the seam: `host.session` should retain session state, framing/error construction, and transport-specific byte accounting, while requiring the promoted contract for keywords and schemas.

### `analyzer-info` disposition

Do not move it into diffusion. Current production consumers are only `agent` for load-order/schema availability (`src/seon/agent.cljs:71`), `client` for source parsing (`src/seon/client.cljs:219`, `:1338`), and death-row `eval` (`src/seon/eval.cljs:56`). No current diffusion namespace requires it.

The file mixes two owners:

- bootstrap analyzer-state projection (`src/seon/analyzer_info.cljs:1-24`, `:103-212`, `:325-404`);
- persisted `:seon.ns.require/*` schemas and a pure source parser (`src/seon/analyzer_info.cljs:214-255`, `:257-323`).

Extract the latter to a portable namespace owner—preferably `seon.ns` or `seon.ns.source`—before W5. Then W5 deletes `seon.analyzer-info` with the self-host analyzer band.

| Type | CORRECTION |
|---|---|
| **CORRECTION** | “Delete or move remnant into diffusion” is unsupported by the current graph. The surviving remnant is core namespace-source parsing used by `client`, not diffusion functionality. |

## 3. Top residual hierarchy smells

### 1. `seon.analyzer-info` owns core namespace-source data

Severity: **worth an early unit**.

Its name promises analyzer-state projection, but it registers persistent `:seon.ns.require/*` data (`src/seon/analyzer_info.cljs:214-255`) and owns the index-time parser used by `client` (`src/seon/client.cljs:1323-1346`). This is the clearest remaining ownership lie.

Recommendation: extract `seon.ns`/`seon.ns.source`, then delete the analyzer remainder at W5.

### 2. `seon.web.serve` is several services behind one front door

Severity: **worth a later unit**, after the execution cutover.

The 2,112-line file combines:

- static assets and readiness (`src/seon/web/serve.cljs:73-160`);
- value authorization/sampling (`:218-455`);
- agent creation and control (`:650-740`);
- product-evidence/database-query APIs (`:887-930`);
- operator lifecycle and blob restore operations (`:1769-1900`);
- routing injection and server lifecycle (`:1925-2112`).

Keeping one HTTP server and one router is correct; keeping every handler implementation in the lifecycle namespace is not. Extract coherent handler owners such as `web.agent`, `web.value-route`, `web.product-evidence`, and `web.operator`, while `web.serve` retains assembly, security gates, and `start!`/`stop!`.

### 3. `seon.execution.host` plus duplicated contract projection

Severity: **worth the existing W5 unit, not a separate pre-W5 rename**.

The namespace currently supervises both Bun children and JVM host sessions (`src/seon/execution/host.cljs:1-20`, `:102-108`). After child deletion it is a client-side dispatcher, so the accepted `seon.execution.dispatch` name is better. The simultaneous duplicate contract in `host.session` (`src/seon/host/session.clj:12-78`) makes the current layering temporarily misleading, but both issues close together when the canonical contract is promoted.

`seon.db.transport.uds.cljc` is not a worthwhile hierarchy unit. It is JVM/Babashka NIO code with explicit `:bb`/`:clj` reader branches (`src/seon/db/transport/uds.cljc:12-22`, `:26-92`) and a same-namespace Bun sibling (`src/seon/db/transport/uds.cljs:1-10`). The `.cljc` suffix is unconventional but truthfully expresses JVM/Babashka source sharing; changing it would be extension hygiene, not an ownership repair.

## Ranked recommendations

1. **Split NS-5:** keep only execution cutover/dispatch rename/contract promotion/band-A deletion/fence cleanup together.
2. **Rename `seon.repl.internal` at the repl-lane handoff, before W5.**
3. **Extract `seon.schema.form`; it is the clearest genuine internals extraction.**
4. **Extract core namespace-source parsing from `seon.analyzer-info` before deleting analyzer state.**
5. **Rename `seon.agent.internal → seon.agent.authorization`.**
6. **Keep `my.plan.internal`; promote its real public seams and optionally extract only `my.plan.generation`.**
7. **Repair `seon.db.internal` consumers narrowly; do not expose or rename the whole implementation.**
8. **Schedule `web.serve` decomposition after W5; treat the UDS extension concern as noise.**