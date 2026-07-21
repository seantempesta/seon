---
type: research
status: active
tags: [research, architecture]
---

# U11 code-removal inventory — Bun child fleet + self-host eval engine (2026-07-21)

Read-only audit on `codex/runtime-reliability-refactor`. Scope: exactly what
U11 ("children retirement, deletion commit") deletes, what survives, what must
move first, every rewiring point in surviving code, and where this lane
overlaps the source-cleanup PRD's Stage 1.5 sampler work.

Grounding: [[../roadmap]] (U1–U5 DONE, U11 honest limits carried in U1.5/U4),
[[../design]] §8/§9 ("render entrypoints stay pod-served" was the U1 seam;
U1.5 records the live divergence — renders actually run in the per-agent Bun
child today), `docs/seon/architecture/agent-runtime.md` (isolation contract),
`src/seon/AGENTS.md` one-mechanism table row "Code execution".

## Architecture after U11 (the target this inventory serves)

- JVM `seon.host` runs one sci context per agent (U1–U5 built: recording tee,
  def replay, registry, graduation). Eval batches route there via
  `:seon.execution.host/eval-socket-path` tier data
  (`src/seon/execution/host.cljs:41`).
- The Bun pod stays the application host (web UI, LLM, loop, rendering,
  shared JS capability host). C2's verdict is SINGLE-TIER: no Bun sci child
  is built; the js-eval need measured EMPTY.
- Renders (prompt/agent-view) are CLJS. Today they run in the child via
  `invoke-compiled!` with quoted symbols
  (`src/seon/agent/turn.cljs:293-297`, `src/seon/web/datastar.cljs:1074`).
  The pod's `:client` bundle ALREADY compiles the complete render closure —
  `seon.render`/`seon.render.canvas`/`seon.render.system`, every
  `seon.agent.ctx.*` block, and the full `my.*` toolkit
  (`src/seon/client.cljs:60-209`) — so after children die the render
  entrypoints run in the pod itself. Nothing about the render code requires a
  child; only the dispatch does.

## Summary table

LOC = current `wc -l`. Verdicts: **DELETE** (child-only, dies at U11),
**KEEP** (shared/envelope, survives), **MIGRATE** (function must move to the
JVM host or the pod before its current home can be deleted), **SPLIT** (one
file, more than one verdict — the split line is given).

### Production source

| Path | LOC | Verdict | Blocker before delete |
|---|---:|---|---|
| `src/seon/execution.cljs` | 1302 | SPLIT | Contract band KEEP + promote `.cljc` (U1 recorded seam; `src/seon/host.clj:112` currently registers a hand JVM projection of the wire schemas). Program-read band KEEP. Engine + child-owner bands DELETE. |
| — lines 21–329 (protocol constants, message keywords, transit codec, `valid-parent-message?`/`valid-child-message?` :279/:297, `bounded-result` :304) | ~310 | KEEP → `.cljc` | Both sides of every surviving transport (pod dispatch + JVM host) speak this contract; `seon.host` must stop echoing a projection. |
| — lines 330–603 (authored-program queries, `canonical-program` :440, `source-digest` :524, `invocation-plan` :530, `compiled-invocation` :542, `prepare-invocations!` :567) | ~275 | KEEP | Pod callers survive: `src/seon/web/reactive/call.cljs:136-141`, `src/seon/execution/host.cljs:1098`. `source-digest` is also the host trust input (`src/seon/host.clj:21,112`). |
| — lines 604–838 (child program install: `acquire-program!` :625, `ensure-compile-state!` :647 — the `seon.eval/init-bootstrap!` call, `install-program!` :660, `ensure-program!` :697, `prepare-eval-program!` :705, `call-selected!` :777, `invoke-selected!` :811) | ~235 | DELETE / MIGRATE | Authored function invocation (`invoke-selected!` semantics behind `web.reactive.call`) is a U1 recorded seam with NO host implementation yet — must land on `seon.host` first. Program install into cljs.js dies outright. |
| — lines 839–1302 (child owner: `settle-active!` :878, `sample-live-value!` :930, `begin-invocation!` :1042, `receive!` :1181, `start-child!` :1217, `-main` :1257) | ~460 | DELETE | Child-side value sampler (:930) is Stage 1.5 surface — see collision list. Live retained values for eval must be host-owned before the child sampler dies (JVM parity exists: `src/seon/host.clj` conformance sampling). |
| `src/seon/execution/host.cljs` | 1239 | SPLIT | One dispatch mechanism, two lanes (`::children` :105 / `::host-sessions` :106). Child lane DELETE; host lane + shared machinery KEEP. |
| — child lane: `spawn-child!` :503, `startup-value` :482, `retire-child!` :642, `exit-child!` :262, `schedule-idle-stop!` :295, `kill-process!` :171, Bun-IPC `send-message!` :168 branch, `reload-required?` :796 | ~350 | DELETE | Renders must first stop invoking into children (rewiring 1–3); authored invocation must be host-served (rewiring 4). |
| — host lane + shared: `connect-host-session!` :563, `invoke-once!` :724, `invoke-in-lane!` :834, `invoke-now!` :858, `pull-eval-host-coordinate!` :807, `settle-active!` :354, `result-current?` :407, `invoke!` :1027, `invoke-plans!` :1090, `invoke-compiled!` :1120, `cancel!` :1142, `configure!` :687, `processes` :200, `stop!` :1227, sampler dispatch `sample-owner` :903 / `sample-once!` :912 / `sample-value!` :970 | ~890 | KEEP (simplify) | After deletion the tier fact becomes the only lane; dispatch collapses to host sessions. `sample-*` is Stage 1.5 shared surface. |
| `src/seon/execution/runtime.cljs` | 682 | SPLIT | The child bundle's compose/main. |
| — `render-prompt!` :265, `render-agent-view!` :427, block/prompt resolution :72–264, view members :352–426 | ~530 | MIGRATE → pod | This IS the render-in-child divergence. Code is ordinary CLJS whose full require closure the pod already compiles (`src/seon/client.cljs:60-209`); move the namespace out of the child artifact (or require it from `seon.client`) and call directly instead of through `invoke-compiled!`. Nothing new to write except the pod-side call boundary that replaces the child's db-value echo check (`src/seon/agent/turn.cljs:363-366`). |
| — `eval-batch!` :608, `setup-fault-kind!` :585, `compiled-functions` :659, `-main` :679 | ~150 | DELETE | `eval-batch!` here wraps `seon.eval/eval-batch!` + `setup-agent-ns!` — superseded by the host recording batch (U4). `-main` dies with the artifact. |
| `src/seon/eval.cljs` | 5389 | SPLIT | The largest file: engine vs envelope. Split verdicts below; the surviving band should land in a smaller owner (the schema registrations + pure envelope fns are candidates for `.cljc` so `seon.host.record` stops mirroring shapes). |
| — lines 1–126 schema registrations (`:seon.eval/id` :82 … `:seon.eval/agent` :112) | ~125 | KEEP | The `:seon.eval/*` vocabulary is the durable receipt schema both tiers write and every pod render reads (`agent.cljs`, `ctx.cljs`, `warn.cljs`, `web/serve.cljs` — see call-site sweep below). |
| — 127–289 `budget`/`Budgeted`/`defer`/`Deferred`/`timed-out?` :223/`race-timeout` :229 | ~160 | KEEP | Pod consumers: `src/seon/agent/loop.cljs`, `src/seon/agent/turn.cljs` (`eval/race-timeout`, `eval/timed-out?`). |
| — 290–483 engine bootstrap: `init-version` :290, warning/print ALS dispatchers :319–414, `expose-loaded-namespace-roots!` :415, `init-bootstrap!` :428 | ~195 | DELETE (agent path) | `seon.repl/dev-init!` (`src/seon/repl.cljs:93-113`) is the one surviving caller — a dev-only iteration surface, decision point below. |
| — 484–655 `lookup-ns-object` :490, `lookup-value` :502, `ns-fn-members` :533, `ns-data-members` :612 | ~150 | KEEP | `lookup-value` is the pod's symbol→var resolver for renders/routes (`src/seon/render.cljs`, `src/seon/web/router.cljs`, `src/seon/web/serve.cljs`, `src/seon/agent.cljs`, `src/seon/warn.cljs`). Needs no cljs.js at runtime (globalThis walk). `seed-toolkit-refers!` :584 is engine → DELETE. |
| — 656–975 analyzer/load: `truly-undeclared?` :656, `ns-loaded?` :728, `namespace-head` :760, `namespace-source` :770, `authored-sources` :807, `guarded-load*` :818, `load-authored-program!` :887 | ~320 | DELETE | The child's program-load machinery; the host's replay is `seon.host.context/restore-context-defs!` (U4). |
| — 976–1804 analyzer ns + result vars + engine: `ensure-analyzer-ns!` :984, `admit-result-value` :1088, `lookup-result` :1400, `bind-result-var!` :1615, `result-sampling-entry` :1584, `raw-eval` :1185, `eval` :1294, `setup-agent-ns!` :1714, `home-ns-alias-hint` :1651 | ~830 | DELETE / MIGRATE | Retained `result/<id>` values must be host-owned (B1 punch item "result-var caps" is still deferred — roadmap U4 honest limits). The child's retained-value store backs the Stage 1.5 sampler for child-lane evals; deleting it requires the host sampler to be the only live-value owner. |
| — 1805–1877 `maybe-await-value` | ~75 | DELETE | Moot on the sync host (U9 measured 0 awaits in persisted agent sources; C2). |
| — 1878–2760 tee builders: `read-all-forms` :1970, `changed-defs` :2025, `scratch-def-note` :2213, `read-error-message` :2261, `build-tee-entities` :2342, `edges->require-info` :2638, `ns-require-edges-tx` :2659, `fn-read-attrs-tx` :2678, `reject-core-overrides` :2728 | ~880 | SPLIT | `seon.host.record` (453 LOC, KEEP) already mirrors the tee DATA (U4) but with its own tools.reader pipeline. Pod keeps `scratch-def-note` (`ctx.cljs`) and `edges->require-info` (`ctx/menu.cljs`). The child-side tee itself dies with `eval-batch!`. Two-tee duplication ends only at U11 — the deletion is what restores one-mechanism. |
| — 2761–2956 `cap-edn` :2761, `render-error-string` :2789, `clip-result-body` :2825, `sanitize-result-edn` :2866, `render-result-edn` :2897 | ~195 | KEEP | Pod renders eval rows with these (`ctx.cljs`, `config.cljs` uses `clip-result-body`). |
| — 2957–3694 recording: `start-eval!` :2957, `record-eval!` :2995, `parity-intercept` :3195, `augment-ns-source` :3222, `merge-requires-into-ns-source` :3387, `require-decl-tx` :3456, tee acquisition/compile :3516–3694 | ~740 | DELETE / MIGRATE | Child-tier recording; host allocates receipts via `seon.db.id/candidate-manifest` (U4). `augment-ns-source` semantics (home-ns aliases in authored `(ns …)`) must exist host-side before deletion — U4 gives the host synthetic-ns aliases for home-ns evals, but authored `(ns …)` augmentation parity is unproven. |
| — 3695–5389 repl-form dispatch + repair + batch: `dispatch-repl-form!` :4730, `preflight-repair!` :4121, `eval-form-entry!` :4250, `dispatch-eval-entry!` :4934, `eval-batch!` :5077 | ~1695 | DELETE / MIGRATE | HARD BLOCKERS: the host batch has **no** in-ns/ns/require/alias/ns-unmap dispatch, no preflight/repair sub-loop, no ALS print capture, no run-fence CAS parity, no instrumentation over sci vars (U4 "honest limits", U6). Each is agent-visible REPL semantics the architecture doc promises (`agent-runtime.md` §"REPL forms"). U11 cannot delete this file band until the host owns those behaviors. |
| `src/seon/eval/bootstrap_cache.cljs` | 63 | KEEP | NOT child-only: `src/seon/worker_eval.cljs` (762 LOC, diffusion oracle `:worker-oracle-eval` build) requires it and the `out/bootstrap` artifact — a deliberate leaf, free of pod state (its own docstring). Dies only if the diffusion eval oracle is separately retired. |
| `src/seon/eval/internal.cljs` | 67 | KEEP | Pure receipt tx builders shared by BOTH tiers today: `src/seon/host/record.clj` and `src/seon/runtime/recovery.cljs` require it. Promote alongside the `:seon.eval/*` schemas. |
| `src/seon/host.clj` + `src/seon/host/{context,record,graduate}.clj` | 3206 | KEEP | The replacement. Loses its hand JVM wire-schema projection (`host.clj:112`) when `seon.execution` goes `.cljc`. |
| `src/seon/subprocess.cljs` | 259 | KEEP | Shared: `agent/shell`, `agent/search`, `runtime/recovery`, `repl/autocomplete` all spawn through it. Only `execution/host.cljs`'s use dies. |
| `src/seon/launch.cljc` | 526 | MIGRATE (shrink) | Descriptor fields `::execution-build-id`/`::execution-output`/`::execution-digest` (`launch.cljc:25-27,42-44,127-128,251-252`) leave the artifact identity; the digest set shrinks accordingly (coordinated with `script/seon/dev/config.clj` + `release.clj`). |
| `src/seon/render/value.cljc` | 1834 | KEEP | Pure bounded sampler — Stage 1.5's owner; consumed by child, JVM host, and web route alike. |
| `src/seon/runtime/recovery.cljs` | — | KEEP (rewire) | Pod-side crash recovery reads `:seon.execution.host/pid`/`exit-code`/`signal`/`stdout-tail` evidence (`recovery.cljs:254-293,461`). Host sessions carry `eval-socket-path` instead of `pid` (`host.cljs:83-85`); evidence shape narrows at U11. |

### Build config, scripts, packaging

| Path | Verdict | Notes |
|---|---|---|
| `shadow-cljs.edn` `:execution` build (:142-150) | DELETE | The child artifact. |
| `shadow-cljs.edn` `:acme-execution` (:179-188) | DELETE | Downstream flavor's child artifact — coordinate with the ACME lane owner (bin/acme wraps the same operator). |
| `shadow-cljs.edn` `:execution-integration-client` (:167-176) | DELETE | Test-only child-fleet driver. |
| `shadow-cljs.edn` `:execution-sci`, `:b2-driver`, `:u15-driver` (:381-430) + `out-b2/`, `.shadow-cljs-b2/` | DELETE (already marked "delete when the decision gate lands") | TIMING: Stage 1.5's live proof explicitly requires B2 caches untouched (`stage1-5-child-sampler-retirement-proof-2026-07-21.md` §Disposition) — delete after its matrix closes. |
| `shadow-cljs.edn` `:bootstrap` (:324-380) + `bin/fix-bootstrap-macros` | KEEP | Consumed by `seon.worker-eval` (diffusion oracle) and `seon.repl/dev-init!`; only the agent-path consumer (`execution.cljs:647`) dies. Re-evaluate separately if the dev-REPL bootstrap surface is retired too. |
| `script/seon/dev/config.clj` (:31-34,82-88,162-175,211-226,335-340,380-383,436-439) | MIGRATE | Artifact schema/digest drops `execution-build-id`/`execution-output`; flavor build vectors shrink. |
| `script/seon/dev/release.clj` (:24,55,94-124,601-605,759-822,1001-1072) | MIGRATE | Packaged `runtime/execution.js` member + `execution-protocol-version` leave the release manifest; `out/bootstrap` packaging stays (worker/dev consumers). |
| `bin/mcp-server-cljs` (B7 auto-await bridge) | KEEP | Bridges the shadow nREPL pod runtime; does not require `seon.eval` directly. Its auto-await mirror becomes moot only if the pod dev-REPL engine is retired. |

### Tests that die / split with each candidate

| Test file | LOC | Fate |
|---|---:|---|
| `test/seon/execution_process_test.clj` | 300 | DELETE (spawns real Bun children) |
| `test/seon/execution/integration_driver.cljs` | 226 | DELETE (child-fleet driver build) |
| `test/seon/execution/runtime_test.cljs` | 935 | SPLIT — render-entry tests MIGRATE to the pod-hosted render owner; eval-batch wiring tests die |
| `test/seon/execution/host_test.cljs` | 1400 | SPLIT — child-lane spawn/retire/IPC tests die; host-session, sampler-dispatch, tier tests keep |
| `test/seon/execution_test.cljs` | 1145 | SPLIT — protocol/codec/sampler-frame tests keep with the `.cljc` contract; child-owner tests die |
| `test/seon/eval/{auto_refer,memory_safety,print_capture,promise_ergonomics,prose_demote,repair_batch,require,result_var}_test.cljs` | 1163 | DELETE with the engine — each names a behavior the host must re-prove (auto-refer/home-aliases, print capture, promise ergonomics moot, repair, require persistence, result vars): they are the U11 parity checklist before they are deleted |
| `test/seon/eval/receipt_test.cljs` | 717 | SPLIT — `eval.internal` receipt builders keep; engine-driven paths move to host parity (`host_conformance_writer_test.clj` 667 already covers part) |
| `test/seon/eval/race_timeout_test.cljs` | 77 | KEEP (`race-timeout` survives in the pod) |
| `test/seon/repl_parity_test.cljs` | 85 | DELETE (self-host REPL parity) |
| `test/seon/instrument_smoke_test.cljs` | 84 | DELETE/MIGRATE (instrumentation over sci vars is U6's proof) |
| `test/seon/subprocess_test.cljs` | 285 | KEEP |
| `test/seon/runtime/recovery_test.cljs` | — | MIGRATE — child-crash fixtures `"(js/process.exit 17)"` (:101,:173-181) re-point at host-session loss (the C2 directive "re-point at U11") |
| `src-inspect-ai/src/seon_inspect/product_scenarios.py:22` `CHILD_RECOVERY_SOURCE = "(js/process.exit 17)"` | MIGRATE | Same C2 directive; the eval bench's crash drill must target the surviving runtime |
| `test/seon/host_{conformance,registry,graduate}_writer_test.clj` | 1551 | KEEP (the replacement's gates) |

## Rewiring points (surviving code that reaches child-lane code)

Every call from surviving code into the child lane, by exact site. These are
U11's edit list.

1. **`src/seon/agent/turn.cljs:293-297`** — quoted entrypoints
   `'seon.execution.runtime/render-prompt!` / `eval-batch!`;
   **`:361-362`** `render-prompt` → `execution.host/invoke-compiled!`;
   **`:412-413`** `eval-parsed!` → same. Render becomes a direct pod call;
   eval-batch routes host-lane unconditionally (tier fact becomes universal,
   then vestigial).
2. **`src/seon/web/datastar.cljs:1074`** (`agent-view-function` symbol) and
   **`:1093-1095`** (`render-agent-view!` via `invoke-compiled!`), consumed
   by the live feed at `:1103` and the historical feed at `:1158` — direct
   pod call; the pod-side hiccup half (`agent-view/render-agent-view` :1084)
   already lives in the pod.
3. **`src/seon/web/reactive/call.cljs:136-145`** — agent-authored callback
   invocation (`execution/invocation-plan` + `prepare-invocations!` +
   `execution.host/invoke!`). Authored invocation is a U1 recorded seam with
   NO host implementation — the only surviving consumer of the child's
   `invoke-selected!` path. Must be host-served (or pod-served for pure
   render callbacks) before child deletion.
4. **`src/seon/web/serve.cljs:444`** — value route →
   `execution-host/sample-value!` (falls to `sample-owner`/`sample-once!`,
   which spans both lanes); **`:955`** — `execution-host/processes` debug
   projection. Sampling collapses to host sessions.
5. **`src/seon/client.cljs:2136-2138`** — `execution.host/configure!` with
   the launch descriptor (execution artifact identity); **`:2587`** —
   `execution.host/stop!` at shutdown. Configuration drops the child
   artifact; host-session config stays.
6. **`src/seon/agent/loop.cljs:469-478`** — `::execution/child-retired?`
   branch in turn-failure handling; the keyword survives (U1.5 contract:
   host-session death synthesizes the exact child-exited error value with
   `child-retired? true`), but prose/semantics update at cutover.
7. **`src/seon/runtime/recovery.cljs:254-293,461`** — child exit evidence
   keys (`pid`, `exit-code`, `signal`, tails, `artifact-digest`); narrows to
   host-session evidence (`eval-socket-path` + echoed digest).
8. **`src/seon/host.clj:112`** — the JVM projection of the wire schemas;
   replaced by requiring the promoted `seon.execution` `.cljc`.
9. **`src/seon/db/protocol.cljc`** — uses `::execution/artifact-digest`
   (shared vocabulary; follows the `.cljc` promotion, no behavior change).
10. **Operator/packaging** — `script/seon/dev/config.clj` +
    `script/seon/dev/release.clj` execution-artifact fields (table above),
    `src/seon/launch.cljc:25-27,42-44,127-128,251-252`.
11. **`src/seon/agent/ctx/canvas.cljs` / `ctx/render_fns.cljs`** — read
    `::execution/invoke-selected?`/`function-symbol`/`arguments`/`value`
    keywords off invocation facts for transcript/canvas rendering; keywords
    survive with the contract, producer moves to the host.
12. **`src/seon/repl.cljs:93-124`** (`ensure-bootstrap!`/`dev-init!`) — the
    last `seon.eval/init-bootstrap!` caller after the child dies. DECISION
    POINT for the owner: keep a dev-only self-host iteration surface in the
    pod (keeps `init-bootstrap!` + `bootstrap-cache` + the engine's load
    path alive as dev weight), or retire it with the engine and make the
    MCP `eval_cljs` shadow-nREPL surface the only pod REPL.

## Collision list — source-cleanup Stage 1.5 (child sampler retirement)

Stage 1.5 (`docs/prds/source-cleanup/roadmap.md:99`) owns the universal data
browser: the bounded value sampler, its execution-IPC extension, and the live
retirement/route/UI proof matrix
(`research/stage1-5-child-sampler-retirement-proof-2026-07-21.md`). Its proof
deliberately exercises the exact machinery U11 deletes ("retirement while a
sample is active", "tier change does not redirect an old eval"). Files BOTH
lanes touch:

| File | Stage 1.5 interest | U11 interest |
|---|---|---|
| `src/seon/execution.cljs` | value-sample message frames (:37-39), child `sample-live-value!` (:930), closed-frame tests | deletes the child owner incl. that sampler; keeps/promotes the frame contract |
| `src/seon/execution/host.cljs` | `sample-owner` :903 / `sample-once!` :912 / `sample-value!` :970, retirement settling (`mark-retiring!` :240, `exit-child!` :262, sample-unavailable :139) | deletes the child lane those functions span; host-lane sampling survives |
| `src/seon/host.clj` | JVM retained-value parity + replacement-session unavailability (conformance test) | the surviving owner |
| `src/seon/web/serve.cljs` | value route authorization/status translation (:444) | rewiring point 4 |
| `src/seon/render/value.cljc` | pure sampler owner | untouched KEEP (both cite it) |
| `test/seon/execution_test.cljs`, `test/seon/execution/host_test.cljs`, `test/seon/host_conformance_writer_test.clj`, `test/seon/web/serve_test.cljs` | the focused sampler proofs | the SPLIT/KEEP test files above |

Sequencing constraints so the lanes don't collide:

- Stage 1.5's remaining gate is a LIVE drive of child/host retirement while a
  sample is in flight. Run it BEFORE U11 deletes the child lane, or the
  "retirement while sampling" cell can only ever be proven against host
  sessions — acceptable only if source-cleanup re-scopes that cell.
- Its proof doc forbids touching `.shadow-cljs-b2/`, `out-b2/`, `default`,
  and `u15`; U11's deletion of the B2/U15 experimental builds and caches
  must wait for Stage 1.5 matrix closure (or explicit re-scope).
- Stage 5 of source-cleanup owns B9 (`test/seon/agent/ctx/canvas_test.cljs`
  direct `datahike.api` use) — that test also requires `seon.eval`; whoever
  edits second reconciles.
- After U11, Stage 1.5's "child sampler retirement" vocabulary itself goes
  stale — the source-cleanup roadmap rows naming child-lane behavior need a
  one-pass update in the deletion commit's doc sweep (U11 explicitly owns
  "architecture docs + one-mechanism table": `src/seon/AGENTS.md` row
  "Code execution" currently names "the per-agent `seon.execution` child
  invoking `seon.eval` over its retained self-host compiler", and
  `agent-runtime.md` §Self-healing/§Isolation still teach the child + cljs.js
  contract).

## Hard blockers (must exist before the deletion commit)

Ordered; each names its owner unit.

1. **Renders in the pod** (rewiring 1–2): move
   `seon.execution.runtime/render-prompt!`/`render-agent-view!` out of the
   child artifact into a pod-hosted owner; ~530 LOC relocate, closure already
   compiled in `:client`.
2. **Authored invocation on the host** (rewiring 3; U1 recorded seam):
   `web.reactive.call` still needs `invoke-selected!` semantics somewhere.
3. **REPL-form dispatch parity** (`dispatch-repl-form!` band): in-ns/ns/
   require-persist/alias/ns-unmap/ns-unalias on the host — architecture-doc
   promised semantics with no host implementation.
4. **Repair/preflight, ALS print capture, result-var caps, run-fence CAS
   parity** — U4's carried honest-limits list.
5. **Instrumentation over sci vars** — U6.
6. **Retained-value single owner**: the host sampler must be the only live
   `result/<id>` owner (Stage 1.5 live matrix first, per the collision
   sequencing).
7. **U8 guidance re-alignment** (before cutover, per the ledger) — every
   docstring/skill teaching `^:async`/await/child vocabulary.
8. **Downstream (`acme`) coordination** — `:acme-execution` artifact and its
   operator flavor retire with the fleet.

## Net deletion estimate

Production source: ~460 (execution child owner) + ~235 (program install) +
~350 (host child lane) + ~150 (runtime eval wiring) + ~3,900 (eval engine
bands, after the ~1,500 KEEP band is extracted) ≈ **5,100 LOC**, plus the
~530 LOC render band that relocates rather than dies. Tests: ~2,800 LOC die
outright (process/integration/engine suites), ~2,000 more split. Build
config: 6 shadow builds, 2 release members, 3 launch-descriptor fields.
