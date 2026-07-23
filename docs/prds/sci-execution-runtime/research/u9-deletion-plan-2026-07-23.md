---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# U9 — the great deletion: implementation plan (2026-07-23)

Read-only design deliverable for the U9 implementation lane. Everything below
was re-verified against HEAD `fe4bfed0c` on `codex/runtime-reliability-refactor`
unless explicitly marked INFERRED. The accepted
[[deletion-audit-2026-07-23]] predates U2/U4/U5/U6/U7/U8a/P5/test-integrity;
its drift from HEAD is reconciled in §8. Rulings that bind this plan: R24
(break-and-replace), R26 (topology), R28 (breaking CLJS authorized, JVM gates
authoritative, no dual maintenance), R43 spec staged (provenance trust), the
unified plan's U9 row and its P5 dependency (now satisfied — P5 landed
`411627db8`, router scans deleted, `:selected-tier` consumed).

Scope sentence: delete the per-agent Bun execution children, the `cljs.js`
self-host eval engine, the child bands of `seon.execution*`, the child
Shadow/operator/release plumbing, and the child/self-host tests — with the Bun
pod surviving ONLY as the disposable js-package leaf host plus the interim
phase-limited claimant (render/LLM/publish) and interim web server until web
slice 2.

## 1. Exact deletion inventory (refreshed against HEAD)

### 1.1 Whole-file deaths

| File | LOC now | Was (audit) | Evidence at HEAD |
|---|---:|---:|---|
| `src/seon/execution/runtime.cljs` | 329 | 706 | U4/U7 already removed `render-prompt!`; remaining: `render-agent-view!` (:80-232), `eval-batch!` (:246-306), `compiled-functions` (:308-322), `-main` (:324-329). Dies after slices S0a/S0b repoint its two remaining jobs. |
| `src/seon/eval.cljs` | 5,300 | 5,387 | Self-host engine (`cljs.js` imports :46-53, bootstrap init ~:415-455, `eval-str` path ~:1185-1292). Surviving-helper extraction scope has SHRUNK dramatically — see §1.4. |
| `src/seon/repl.cljs` | 124 | 124 | Consumers at HEAD: self-host eval tests only, plus one apparently call-free require at `src/seon/web/serve.cljs:50` (`rg "\brepl/"` finds zero call sites in serve — INFERRED stale require; confirm by compile in S2). Owner confirmation from the audit stands; the evidence is now stronger for whole-file death. |
| `src/seon/analyzer_info.cljs` | 349 | 287 | Only consumers: `seon.eval` itself and `test/seon/analyzer_info_test.cljs` (203 LOC — the audit's test totals missed this file). Dies with the engine. |

### 1.2 Mixed-band cuts (file survives, bands die)

**`src/seon/execution.cljs` (1,519 LOC).** Verified structure at HEAD:

- SURVIVES — data contract :22-337 (`protocol-version` :24, message
  constants :36-45, `encode-message`/`decode-message` :221-231,
  `valid-parent-message?`/`valid-child-message?` :288-306,
  `bounded-result` :313).
- SURVIVES — authored-program acquisition :339-865: `canonical-program` :482
  (the P1 graph-identity acquisition boundary — wiki scar "Graph identity must
  be acquired at one immutable database value"), `source-digest` :566,
  `invocation-plan` :572, `compiled-invocation` :584, `prepare-invocations!`
  :609, the paged corpus acquisition :646-865. Live consumers:
  `agent/turn.cljs:407`, `web/reactive/call.cljs:139`.
- DIES — pod compile-state/program-install band :868-1055
  (`ensure-compile-state!`, `install-program!`, `ensure-program!`,
  `prepare-eval-program!`, `call-selected!`, `invoke-selected!`) — the
  self-host engine's loader; goes with `eval.cljs` (slice S2).
- DIES — child owner band :1056-1519 (`begin-invocation!` :1259 [audit said
  :1310 — drifted], `cancel-active!` :1369, `shutdown!` :1382, `receive!`
  :1398, `start-child!` :1434, `-main` :1474) — slice S1.
- NOTE: `compiled-invocation` :584 has exactly one production caller,
  `execution/host.cljs:1280` inside `invoke-compiled!`, which dies in S1;
  writer conformance fixtures construct compiled invocations directly. Delete
  `compiled-invocation` with S1 unless the S0a in-pod render keeps a digest-
  pinned shape (it should not — a direct pod call needs no wire invocation).

**`src/seon/execution/host.cljs` (1,382 LOC).** Verified band map:

- DIES — child lane: `child-lane` :108, child/evidence helpers :182-305
  (`child`, `child-evidence`, `exit-evidence`, `mark-retiring!`,
  `remove-child!`, `exit-child!` where child-specific, `schedule-idle-stop!`),
  `spawn-child!` :509-568, `retire-child!` :650, `stop-child!` :1338, the
  child arms of `invoke-now!` :977-1027 (the `:bun` eval arm :991-995, the
  unconditional compiled→child arm :997-999 with its own docstring admission
  "Artifact-digest prompt/view rendering stays on the Bun child until its
  claimant phase moves", the coordinate-absent child fallback in the `:else`
  arm), `invoke-compiled!` :1263-1283, `invoke-plans!` :1233-1261 (P5 deleted
  its prompt arm; remaining callers are TESTS ONLY —
  `execution/host_test.cljs:901`, `integration_driver.cljs:47` — verified;
  delete with them), child-side `cancel!` arm and `stop!`'s child sweep.
- SURVIVES (rehome as `seon.execution.dispatch`, the audit's NS-5 rename) —
  `host-lane` sessions: `connect-host-session!` :569-649, generic correlation
  (`receive!`/`settle-active!` are lane-generic — see risk R3), `invoke!`
  :1170, `configure!` :695 (trimmed of child launch fields), `sample-value!`
  :1113 + `sample-owner`/`sample-once!` :1046-1112 (already lane-spanning —
  host-lane sampling verified present), host reconcile
  :857-946, result-symbol ownership checks (P5 retained), `cancel!` host arm,
  `stop!` host arm.

**`src/seon/host/session.clj` (281 LOC).** The :12-78 hand protocol projection
band is still present verbatim at HEAD, self-marked "W5 deletes this section
when it promotes that contract to `.cljc`". Dies in slice S3 via promotion.

**`src/seon/agent/turn.cljs` (932 LOC).** `eval-parsed!` :540-~601 (the pod
eval dispatch through `invoke-compiled!` :557) dies. Its ONE production caller
is `agent/loop.cljs:556` (scheduled-fns fire) — see blocker B2. The render
path (:399-505) already runs in-pod through the guarded door and SURVIVES.
`seval/lookup-value` :425 and `seval/race-timeout`/`timed-out?` :777-781 need
the §1.4 helper extraction first.

**`src/seon/web/datastar.cljs`.** `agent-view-function` :1074 +
`render-agent-view!` :1093-1096 (child dispatch via `invoke-compiled!`) —
REWRITTEN in-pod (S0a), not deleted.

**`src/seon/runtime/recovery.cljs` (468 LOC).** The mechanism (durable fenced
unexpected-exit transition) SURVIVES. Child-evidence projection bands
:261-300 and the artifact-digest evidence read :468 trim to host-session-loss
vocabulary. Requires `seon.eval.receipt` (:20) — which survives.

**`src/seon/client.cljs`.** `execution.host/configure!` :2205-2207 and
`(execution.host/stop!)` :2776 — retarget to the survivor dispatch owner;
launch-descriptor execution fields drop (see operator band). SHARED FILE with
predfix and staged-P3 lanes — see §7.

### 1.3 Build / operator / release plumbing

- `shadow-cljs.edn`: DELETE builds `:execution` :150, `:acme-execution` :189,
  `:execution-integration-client` :177, and the DRIFT additions the audit
  never saw: `:execution-sci` :393 (B2 probe, source under
  `tmp/sci-probe/exec-src`), `:b2-driver` :407, `:u15-driver` :422. Delete
  `tmp/sci-probe/` and the untracked `out-b2/`, `.shadow-cljs-b2/` residue.
  PRESERVE the diffusion worker builds (:211, :248 region) and the client
  builds.
- `src/seon/launch.cljc`: `::execution-build-id`/`::execution-output`/
  `::execution-digest` schema+fields :26-28, :82-84, :175-176, :305-306.
- `script/seon/dev/artifact.clj`: execution digest/output/inventory manifest
  fields :47-70, :380, :418, :446-464, :502-535. CAREFUL SPLIT: the
  `:seon.execution.inventory/*` sidecar rows :35-38 and the exports-by-tier
  publication :427-429 are P1b's LIVE planner input — the per-artifact export
  inventory SURVIVES; only the child-artifact (out/execution/main.js) digest
  wiring dies. The Bun CLIENT artifact's inventory sidecar remains published.
- `script/seon/dev/release.clj`: `execution-protocol-version` :24, execution
  members :55-133, :608-620, :770-817.
- `script/seon/dev/process.clj`: execution digest/output process fields
  :46-78, :257-278, `:execution-build-id` in the flavor build vector :353-355
  (build selection must shrink to client-only), :392-430
  (`with-execution-artifact`).
- `script/seon/dev/config.clj` / `seon.dev.config`:
  `:seon.dev.config/execution-build-id`/`execution-output` rows.

All of `script/seon/dev/**` is inside the predfix lane's protected tree —
sequencing in §7.

### 1.4 The narrowed `seon.eval` helper extraction

Precise-require census at HEAD (`[seon.eval …]`): production consumers are
ONLY `repl.cljs`, `execution.cljs`, `agent/turn.cljs`, `web/serve.cljs`,
`web/router.cljs`, `execution/runtime.cljs`. The audit's larger list
(`warn`, `render`, `agent/ctx`, `menu`, route/web render callers, loop
helpers) is GONE — U7's ctx/render port already removed those edges. What
actually needs a new home:

| Helper | Users at HEAD | Disposition |
|---|---|---|
| `lookup-value` | `turn.cljs:425` (core prompt arm), `web/router.cljs:177` (route handler symbols), `web/serve.cljs:509,1812` (config-apply/quiesce control) | Needs a pod compiled-symbol resolution mechanism that does not depend on the self-host env — S0c, see decision D-U9-2. |
| `race-timeout` / `timed-out?` | `turn.cljs:777,781` (LLM attempt cap) | Pure Promise/timeout utility; extract to a small portable/pod-owned namespace; `test/seon/eval/race_timeout_test.cljs` (77 LOC) moves with it (audit agreed: keep/migrate). |
| `authored-sources` | `execution/runtime.cljs` only | Dies with runtime.cljs. |
| everything else (`init-bootstrap!`, `load-authored-program!`, result registry, sampling, parser/tee glue) | `repl.cljs`, `execution.cljs` dead bands | Dies. |

`src/seon/eval/receipt.cljc` (92 LOC) SURVIVES (consumers:
`runtime/recovery.cljs:20`, JVM host recording). `src/seon/eval/bootstrap_cache.cljs`
(63 LOC) RELOCATES under the diffusion owner (live consumer
`diffusion/worker/eval.cljs:80,145`; the `eval.cljs:67` edge dies) — ruling 16
quarantine, exactly as the audit ruled.

`src/seon/subprocess.cljs` (264 LOC) SURVIVES — live leaves
`repl/autocomplete.cljs`, `agent/search/internal.cljs`,
`agent/shell/{internal,leaf,core}`; only the `execution/host.cljs` child
consumer dies.

NOT U9 (unchanged separate cleanups, per the audit): the
`host/context.clj:202-242` compatibility band (NS-4), the transcript
`::result-handles?` dial, the mid-logic `node:fs` consolidation rows.

## 2. Consumer re-point verification ledger

For every surface U9 deletes: the surviving owner, the source evidence it
actually does the job, and the status. VERIFIED = read at HEAD.

| # | Deleted surface | Surviving owner | Evidence | Status |
|---|---|---|---|---|
| L1 | Child eval of agent turns | U2 claim driver, JVM claimant | `driver/host.clj:60-67` — `:eval` capability is unconditional; `eval-step!` → `:482` → `seon.host.invoke/execute-invocation!` → `host/eval.clj:336` `eval-batch-result` (sci + full corpus recording). Pod driver leaf advertises render/llm/publish ONLY (`driver/pod.cljs:60-67`). | VERIFIED |
| L2 | Child prompt render | U4/U7 in-pod + guarded door | `turn.cljs:465-505` renders in-pod via `ctx.driver/render-prompt!`; authored symbols go through `invoke-authored-render!` :399-417 → `execution.host/invoke!` → host-lane → JVM guarded door (`host/invoke.clj:144-150` authored arm). Spine door swap `cd7d3ebf8`. | VERIFIED |
| L3 | Child agent-VIEW render (`/agent/{id}` live feed) | **nobody yet** | `web/datastar.cljs:1093-1096` still dispatches `'seon.execution.runtime/render-agent-view!` through `invoke-compiled!`; `invoke-now!` routes every compiled invocation to `child-lane` (:997-999); the JVM host REFUSES render symbols by design (`host/invoke.clj:174-180` — "prompt and view rendering stay on the pod"); web slice 2 (JVM /agent pages) is sequenced AFTER U9. | **BLOCKER B1 — U9 must do the in-pod move (S0a)** |
| L4 | Child eval of scheduled fns | **nobody yet** | `agent/loop.cljs:556` (`exec-scheduled-fns!`) → `turn/eval-parsed!` → `invoke-compiled!` eval batch. Additionally `invoke-now!` :984-989 now errors any eval batch without a `:jvm`/`:bun` selected tier, and this path attaches none — the scheduled-fire path is plausibly ALREADY broken (R28 window). | **BLOCKER B2 — verify live, then re-point (S0b, decision D-U9-1)** |
| L5 | Child authored calls from web canvas | P5 routing + JVM door | `web/reactive/call.cljs:139-145` → `prepare-invocations!` → `invoke!`; authored invocations route host-lane when the agent has an eval-socket coordinate (`invoke-now!` `:else` arm). Residual: coordinate-absent falls back to child-lane — becomes a loud error in S1. | VERIFIED (with S1 edge) |
| L6 | Child value sampling (drill) | host-lane sampling | `sample-owner` :1046-1053 already spans `[child-lane host-lane]`; host sessions serve `value-sample` frames. Child arm just disappears. | VERIFIED |
| L7 | Cross-tier result references | P5 result-symbol ownership | retained runtime-local checks (`cross-tier-result-reference`), P5 scar "Route from `:seon.execution/selected-tier`". | VERIFIED |
| L8 | Self-host symbol resolution for routes/controls/core prompts | **nobody yet** | `web/router.cljs:177`, `web/serve.cljs:509,1812`, `turn.cljs:425` all resolve through `seval/lookup-value` (the self-host compile-state env). U7's precedent is the static trusted table (`render/core.cljc:16-32`); R43's staged spec will make classification computed but not resolution. | **BLOCKER B3 — S0c + decision D-U9-2** |
| L9 | LLM phase | U6 portable phase; pod stays interim claimant | pod driver `:open-attempt`/`:settle-attempt` → `turn/llm-phase!`; JVM claimant `:llm` when transport+blob leaves present (`driver/host.clj:60-67`). Needs only the §1.4 `race-timeout` extraction. | VERIFIED |
| L10 | `:bun`-tier package eval batches | **nobody until the JVM package leaf host unit (post-U9)** | `invoke-now!` :991-995 routes `:bun`-selected eval to the child; topology #4's wire-serving leaf host is a LATER queue unit. | **DECISION D-U9-3 (recommend fail-closed absence)** |
| L11 | Child unexpected-exit recovery | recovery.cljs durable transition | mechanism is child-agnostic (fenced pointer/run/turn repair); only evidence projection bands are child-specific. | VERIFIED (trim in S4) |
| L12 | Diffusion self-host bootstrap | quarantined diffusion owner | `diffusion/worker/eval.cljs` standalone Shadow main; `bootstrap_cache` relocation (S0e). | VERIFIED |

## 3. Ordered slices — each independently green

Gates per R28: JVM gates (`bin/test-writer`, operator gate) are authoritative;
CLJS suites run only where a slice claims a still-alive pod surface works.
Every slice ends in a path-limited commit; live proofs run on an isolated
cluster (`u9del`) ending in its own `bin/seon down`.

### S0 — pre-deletion rewires (pod stays fully green; file-disjoint from the cuts)

- **S0a — in-pod agent-view render.** Move `render-agent-view!`'s logic
  (`execution/runtime.cljs:80-232`) into a pod-owned view driver (natural
  owner: beside `ctx-driver`'s existing agent-view members —
  `agent/ctx/driver.cljs` already owns `agent-view-members` and the
  html-value helpers). `web/datastar.cljs:1074-1096` calls it directly;
  authored `:seon.render/html` symbols route through the SAME
  `invoke-authored!` door `turn.cljs` uses (L2 pattern); trusted core
  renderers through the U7 table. Delete the `agent-view-function` symbol
  indirection. Tests: migrate the retained view coverage out of
  `execution/runtime_test.cljs` (975 LOC) into the new owner's focused test;
  keep the byte/DOM-identity style assertions. Gate: focused CLJS web tests +
  live `/agent/{id}` second-morph proof on `u9del`.
- **S0b — scheduled-fns re-point** (after the D-U9-1 ruling). First falsify
  live: does a schedule fire currently produce the `invoke-now!`
  "no selected execution-plan tier" core-bug? Then implement per the ruling
  (recommended: the fire writes the durable scheduled TURN facts and wakes the
  run — the claim driver's eval phase executes it on the JVM like any other
  turn; `exec-scheduled-fns!` stops evaling locally; `turn/eval-parsed!`
  deletes). Rewrite the schedule-fire regression against receipts/phase
  cursor, not pod eval results.
- **S0c — compiled-symbol resolution without the self-host env** (after
  D-U9-2). Replace the three `lookup-value` consumer groups (L8): route
  handlers, serve control functions, `turn.cljs` core prompt arm. Keep ONE
  mechanism (extend the existing U7 static trusted-table owner or direct
  requires where the symbol set is closed); do not invent a second registry.
  Coordinate with the staged R43 lane (§7) — R43 dissolves the table's
  CLASSIFICATION into provenance; resolution stays a compiled table either
  way.
- **S0d — extract `race-timeout`/`timed-out?`** to a small pod/portable
  utility owner; move `race_timeout_test.cljs` beside it; repoint
  `turn.cljs:777-781`.
- **S0e — relocate `eval/bootstrap_cache.cljs`** under
  `src/seon/diffusion/` (rename its ns; repoint `diffusion/worker/eval.cljs`
  requires; diffusion builds recompile). Gate: diffusion worker Shadow builds
  compile.

### S1 — child retirement (the big cut)

Deletes together: `execution/host.cljs` child-lane bands (§1.2);
`execution.cljs` child owner band :1056-1519; `execution/runtime.cljs`
whole file; Shadow builds `:execution`, `:acme-execution`,
`:execution-integration-client`, `:execution-sci`, `:b2-driver`,
`:u15-driver` + `tmp/sci-probe/`; operator/release/artifact/launch execution
plumbing (§1.3, preserving the P1b export-inventory publication);
`client.cljs` configure!/stop! trim; `agent/turn.cljs` `eval-parsed!`;
the `invoke-now!` child arms — coordinate-absent authored calls and
`:bun`-selected evals become loud flat errors (D-U9-3).

Tests DELETED with justification (R28: their runtime dies in this slice):

- `test/seon/execution/integration_driver.cljs` (226) — child integration
  driver, drives the deleted build.
- `test/seon/execution/host_test.cljs` (1,655) — SPLIT: delete child
  spawn/IPC/retire/evidence assertions; RETAIN host-lane session,
  correlation, sampling, invoke routing coverage into the survivor's test
  (renamed with the S3 rehome).
- `test/seon/execution_test.cljs` (1,327) — SPLIT: retain protocol/codec/
  acquisition coverage (promotes dual-tier in S3); delete child-owner
  coverage (`begin-invocation!`, `start-child!`, receive loop).
- `test/seon/execution/runtime_test.cljs` (975) — delete remainder after
  S0a migrated the view coverage; the eval-batch child coverage is dead
  runtime.
- `test/seon/agent/turn_test.cljs` — delete the `eval-parsed!`/
  `invoke-compiled!` stub blocks (:257-355); render-path tests remain.
- Child assertions in `test/seon/agent/ctx_teaching_test.cljs`,
  `test/seon/agent/multiagent_test.cljs`, `test/seon/web/*` that stub
  `execution.host` child behavior — rewrite against the S0a direct render /
  host-lane dispatch.

Gate: `bin/test-writer` full (authoritative; the five `host_*_writer_test`
suites must stay green — see risk R2), operator gate, focused CLJS for the
surviving pod surfaces (web serve/datastar/reactive-call, driver.pod), and
the S4 live proof list can begin (pod boots to readiness with no child
artifact on disk).

### S2 — self-host engine death

Deletes together: `src/seon/eval.cljs`, `src/seon/repl.cljs`,
`src/seon/analyzer_info.cljs`, `execution.cljs` program-install band
:868-1055, the `web/serve.cljs:50` stale require.

Tests DELETED (pin dead engine semantics — R28): `test/seon/eval/`
`{auto_refer,memory_safety,print_capture,promise_ergonomics,prose_demote,
repair_batch,require,result_var}_test.cljs` (1,163 LOC),
`test/seon/repl_parity_test.cljs` (85), `test/seon/analyzer_info_test.cljs`
(203). Tests REWRITTEN/kept:

- `test/seon/eval/receipt_test.cljs` (717) — the receipt CONTRACT survives in
  `eval/receipt.cljc`; rewrite as a portable `.cljc` suite under a namespace
  directory both runners discover (conversion-wiki test-visibility rule);
  JVM recording coverage already exists in the writer suites — do not
  duplicate, keep the pure contract side.
- `test/seon/eval/race_timeout_test.cljs` (77) — moved in S0d.
- `test/seon/instrument_smoke_test.cljs` (84) — the audit says "move
  instrumentation proof to the JVM host surface": verify the widened writer
  gate already covers instrumented-wrapper behavior (host_registry suites);
  if covered, delete citing that owner; if not, ONE JVM regression first.

Gate: full CLJS compile of every surviving build (`:test`, client, diffusion
workers) — the decisive proof that no surviving namespace requires the
engine — plus `bin/test-writer`.

### S3 — protocol promotion + rehome (NS-5)

- Promote the surviving `seon.execution` contract (constants, message
  schemas, codecs, `prepare-invocations!` split as needed) to `.cljc`;
  require it from the JVM immediately (wiki: a rename is not a portability
  proof).
- Delete the `host/session.clj:12-78` hand-projection band; session.clj
  consumes the promoted contract.
- Rename/rehome the `execution/host.cljs` survivor as
  `seon.execution.dispatch` (repoint `client.cljs`, `web/*`, `turn.cljs`,
  tests). SHARED-TREE NOTE: this is a cross-cutting rename — orchestrator
  performs or freezes it per the shared-tree renames rule.
- Decision D-U9-4 executes here: the wire symbol
  `'seon.execution.runtime/eval-batch!` (still served by
  `host/eval.clj:336`, gated at `host/invoke.clj:151`, sent by
  `driver/host.clj:231`, asserted by the writer conformance/registry suites)
  either keeps its name as a pure wire constant relocated into the promoted
  contract, or renames everywhere in this one slice. It must NOT dangle
  pointing at a deleted namespace.

Tests: the retained protocol/codec coverage from `execution_test` becomes
dual-tier `.cljc`; `host_*_writer_test` fixtures update in the same commit as
the symbol decision. Gate: `bin/test-writer` full + dual-tier focused runs.

### S4 — residue, recovery, census flip

- Trim `runtime/recovery.cljs` child-evidence bands; REWRITE
  `test/seon/runtime/recovery_test.cljs` (468 — already down from the
  audit's 702) child-death modeling (`js/process.exit 17` pattern) into
  host-session-loss/claim-recovery evidence per the audit's row.
- Rewrite the two `ctx_test.cljs` child-guidance assertions (:242, :274-287
  "execution child stopped") when the rendered guidance text changes.
- Structural absence gates (one commit): `rg` proves zero references to
  `seon.eval`(non-receipt), `seon.execution.runtime`, `spawn-child!`,
  `:execution`/`:acme-execution` builds outside git history; the operator
  flavor build vector contains client builds only.
- **Census flip**: `test/seon/host_surface_writer_test.clj:23-25`
  `cutover-required?` `false` → `true` (§6). This is the LAST commit of U9.
- Live proof ledger on fresh-reset `u9del` (R38): `bin/seon up` to readiness,
  `/` and `/agent/{id}` render + second morph, one full agent turn
  (render→LLM→JVM eval→publish) with exact receipts, drill/value sample via
  host-lane, `bin/seon down`.

## 4. The leaf-host residue (what the Bun pod keeps)

Verified surviving pod surfaces after S1-S4:

- client boot/config reconciliation (`client.cljs`, minus execution-host
  child config);
- the interim web tier: `/`, `/agent/{id}`, debug, datastar SSE feeds,
  reactive registry, reactive calls (until web slice 2 — U5's JVM tier owns
  only `/data` + `/data/feed` today);
- the phase-limited pod claimant: `driver.pod` leaf (render/LLM/publish),
  `turn.cljs` render + LLM + publish phases, `loop.cljs` wake
  handling/ticker/scan (minus local scheduled eval);
- host-lane dispatch (the S3 `seon.execution.dispatch`) — the pod's UDS
  client to the JVM eval door;
- platform leaves: `subprocess.cljs` + shell/search/autocomplete/fs/blob
  leaves, `log`, `platform`, `warn`, db replica/UDS transport;
- diffusion workers + relocated bootstrap cache (quarantined self-host,
  ruling 16);
- js-package serving: NOT live at U9 — arrives with the JVM package leaf
  host unit's R17 wire (topology #4). Until then `:bun` placement is
  fail-closed absent (D-U9-3).

Assertion that nothing deleted is reachable from the residue: the S2 compile
gate (all surviving Shadow builds compile), the S4 `rg` absence gates, and
the S4 live boot — a pod that boots to readiness and completes a turn with
`out/execution/` absent from disk proves no runtime edge into the deleted
artifacts. The pod after U9 contains NO eval engine of any kind; every eval
is a JVM claimant step.

## 5. Risk register (the dangerous cuts)

| Risk | Shared plumbing | Falsifier |
|---|---|---|
| **R1 — live `/agent` page dies with the child.** The datastar agent-view feed is child-served TODAY (L3); web slice 2 is post-U9. Cutting S1 before S0a breaks the primary UI. | `web/datastar.cljs` ↔ `execution/host.cljs` ↔ child artifact | After S0a, before S1: load `/agent/{id}` on `u9del`, transact, observe the second morph server-side (gzip SSE recipe). After S1: same proof with `out/execution/` deleted from disk. |
| **R2 — the eval-batch wire symbol spans dead and live code.** `'seon.execution.runtime/eval-batch!` names a namespace deleted in S1 but is the LIVE JVM wire contract (`host/eval.clj:336`, `host/invoke.clj:151`, `driver/host.clj:231`, `host_{conformance,registry,interrupt,cancel,hostile_battery}_writer_test`). | JVM host + claimant + writer suites + fixfixture-owned test files | `bin/test-writer` full after S1 (symbol untouched) and after S3 (decision executed): 559-test gate green; a live JVM eval turn on `u9del` produces receipts. |
| **R3 — lane-generic correlation serves both lanes.** `receive!`, `settle-active!`, `exit-child!`, `mark-retiring!`, `same-child?` are parameterized by lane; deleting the child lane must not mutilate host-lane session lifecycle (ready/timeout/reconnect/retire). | `execution/host.cljs` :360-505 correlation core | Focused host-lane tests retained from `host_test` (ready, invoke, session-loss, reconnect) + live host-lane eval + value sample after S1. |
| **R4 — artifact/operator digest chain.** Execution digest/inventory fields thread manifest → process readiness → release membership → P1b sidecar inventories; over-deletion breaks `bin/seon up` or the planner's export-inventory input; the whole tree is predfix-protected. | `script/seon/dev/{artifact,release,process}.clj`, `launch.cljc`, P1b sidecars | Operator gate (`bin/seon test operator`) + fresh `bin/seon up` on `u9del` + a planner run that still sees the client artifact's export inventory (placement of a compiled terminal exact — P1b's regression). |
| **R5 — `:bun` package placement loses its only engine.** The planner emits `:bun` for `seon.packages.js.*` call graphs; post-S1 nothing serves it until the leaf-host unit. | `program/plan.cljc` policy ↔ `invoke-now!` `:bun` arm | Plan a js-package call on `u9del`: result is the loud named steering error (missing tier/leaf), never a hang or silent success. Confirm the default cluster carries zero package ledger rows before the cut (UNVERIFIED at design time). |
| **R6 — the scheduled-fire path may be latently broken pre-U9.** `invoke-now!` demands a selected tier for eval batches; `eval-parsed!` attaches none. If broken, S0b's rewrite must not "restore" the child path to prove a baseline. | `loop.cljs` ↔ `turn.cljs` ↔ P5 routing | Live probe FIRST: fire one due schedule on `u9del`; record the actual envelope; then re-point per D-U9-1 and assert the schedule turn's receipts on the JVM path. |

## 6. The census cutover assertion

- WHERE: `test/seon/host_surface_writer_test.clj:23-25` —
  `cutover-required?` (`"W5 cutover flips this only after every blocking
  disposition is closed."`), enforced at :389-393: when true, ZERO rows may be
  `:host/capability-pending`, `:host/platform-pending`, or
  `:host/excluded-with-reason`.
- WHAT FLIPS IT: editing the literal to `true` in S4, legal only when the
  computed table has no blocking rows. The table is honest by construction:
  computed rows derive from the real wrapper registry + portable base loader,
  and the `resolved = left ∩ registry` assertion (:368-376) FAILS if a seeded
  `:host/capability-pending` row has silently become registry-served — so
  U8-era landings force seed updates rather than rotting.
- CURRENT BLOCKING SEEDS (explicit `:host/capability-pending` rows still in
  the file, must be resolved or `:host/excluded-with-reason`-ruled before the
  flip): `my.canvas/{clear!,pinned,save!,show!,state,view}`, `my.data/rows`,
  `my.ns/{compact!,full!,functions}`, `seon.agent.search/{grep,grep-graph}`,
  `seon.ai/generate-code!`,
  `seon.schema/{enum-members,identity-attr?,registered-schemas,registered?,schemas-in-namespace}`.
  Whether the U8a landings already serve some of these through the registry
  is NOT verified here (the census printout at the next writer run is the
  authority); U9's S4 reconciles the seed table against that run and routes
  any genuinely-unserved row to the owner as an explicit exclusion ruling.
- The unified plan's "census cutover assertion flips at zero" = this literal;
  U10's graduation gate then cites the green census.

## 7. Protected-path map vs live lanes and staged specs

| Lane / spec | Their owned paths | Overlap with U9 | Sequencing |
|---|---|---|---|
| **predfix** (live at design time) | `src/seon/schema.cljc`, `src/seon/db/protocol.cljc`, `src/seon/runtime/admission*`, `src/seon/client.cljs` (start-runtime! band), `script/seon/dev/**` (per the fixfixture spec's protected list) | `client.cljs` (U9 trims :2205, :2776 — different band, same file) and the ENTIRE §1.3 operator tree | U9 is post-checkpoint and the checkpoint fires on predfix's accepted return — natural ordering. If predfix respawns, U9's S1 operator hunks wait or combined-commit per the wiki recipe. |
| **fixfixture** (HEAD `fe4bfed0c` "Repair durable defn test fixtures" is plausibly its landing — verify at dispatch) | `test/seon/host_registry_writer_test.clj:591` + sibling stale-fixture sweep | `host_registry_writer_test.clj:489` constructs the eval-batch wire symbol (risk R2); U9's S3 symbol decision edits the same file | Confirm fixfixture landed before S3; otherwise its files are PROTECTED for S1 (S1 does not touch them). |
| **staged R43** (`tmp/orchestrator/r43-trust-provenance-spec.md`) | `src/seon/error.cljc:211-225` (`agent-authored-sym?`), `src/seon/render/core.cljc:16-32` (static trusted table), `test/my/plan_test.cljs` | U9 S0c extends/uses the SAME trusted-table mechanism; `turn.cljs` (U9-owned) CALLS `agent-authored-sym?` (R43 keeps the name/callers) | Two orders both work; recommend R43 FIRST so S0c's resolution consumes the computed classification instead of widening a table R43 then dissolves. If U9 goes first, S0c must stay strictly resolution-side (no new classification rows) and R43's `{{LANE_MAP}}` gets U9's list below. |
| **staged P3** (`tmp/orchestrator/p3-registration-spec.md`) | `src/seon/db.cljc` (read admission + validation window), `src/seon/client.cljs:744` (`agent-bootstrap-attrs`) | `client.cljs` again (different bands) | File-disjoint by band; if concurrent, entangled-hunk combined-commit rule applies; nothing in U9 touches `db.cljc`. |
| **poll/timeout census** (read-only Fable) | report only | none | — |

**U9's own owned-path list** (for the orchestrator to paste into other specs'
`{{LANE_MAP}}`): `src/seon/execution.cljs`, `src/seon/execution/host.cljs`,
`src/seon/execution/runtime.cljs`, `src/seon/eval.cljs`, `src/seon/eval/**`
(minus `receipt.cljc` semantics — file moves only), `src/seon/repl.cljs`,
`src/seon/analyzer_info.cljs`, `src/seon/host/session.clj`,
`src/seon/agent/turn.cljs`, `src/seon/agent/loop.cljs` (S0b band),
`src/seon/web/datastar.cljs` (:1074-1096 band), `src/seon/web/router.cljs`
(:170-183), `src/seon/web/serve.cljs` (lookup/require bands),
`src/seon/web/reactive/call.cljs`, `src/seon/runtime/recovery.cljs`,
`src/seon/client.cljs` (execution-host bands), `shadow-cljs.edn`,
`src/seon/launch.cljc`, `script/seon/dev/{artifact,release,process,config}.clj`
(execution bands, post-predfix), `src/seon/diffusion/**` (S0e relocation),
plus every test file named in §3.

## 8. Audit-drift ledger (honesty section)

Where [[deletion-audit-2026-07-23]] no longer matches HEAD:

- `execution/runtime.cljs` 706 → 329 LOC; `render-prompt!` and the
  compiled-table prompt arm are ALREADY GONE (U4 + spine door swap); its
  audit-cited consumers `agent/turn.cljs`, `web/*` render arms are repointed.
- `agent/loop.cljs` 1,331 → 781; `test/seon/agent_loop_test.cljs` 1,048 → 16
  (the child-pid/retired-prose assertions the audit flagged are already
  deleted by U2; the file is now a pure claim-driver projection test).
- `test/seon/execution_process_test.clj` (301) and
  `test/seon/authority_density_test.clj` — ALREADY DELETED (test-integrity
  lane, justified per the wiki's "widened discovery" entry).
- `runtime/recovery_test.cljs` 702 → 468; `ctx_test.cljs` → 344 with only
  two residual child-prose assertions.
- `seon.eval` consumer set collapsed from ~12 production namespaces to 6
  (§1.4) — the audit's "extract race timeout, lookup/ns introspection, error
  rendering, parser/tee" list is mostly obsolete; only
  lookup-value + race-timeout remain load-bearing.
- NEW since the audit: Shadow builds `:execution-sci`, `:b2-driver`,
  `:u15-driver` (+ `tmp/sci-probe/`, `out-b2/`) — B2/U1.5 probe residue that
  must join the S1 cut; P1b's `:seon.execution.inventory/*` sidecar rows in
  `artifact.clj` (a SURVIVOR the audit could not have named); P5's
  `:selected-tier` routing inside `invoke-now!` (changes the child-arm
  shape); `host/session.clj` weight 281 unchanged but `execution.cljs` band
  boundaries shifted (~50 lines).
- Test-weight refresh: direct execution tests now 4,183 LOC
  (host_test 1,655 + execution_test 1,327 + runtime_test 975 +
  integration_driver 226); `test/seon/eval/` 1,957 LOC of which 717
  (receipt) + 77 (race-timeout) survive by rewrite/move; plus
  repl_parity 85, instrument_smoke 84, analyzer_info_test 203, and the
  scattered stub blocks in turn/ctx/web/multiagent tests. Total
  delete-or-rewrite surface ≈ 6.5k test LOC — consistent with the U9 row's
  "~5.7k" once the survivors are netted out.

UNVERIFIED / INFERRED items a lane must probe before relying on them:
`web/serve.cljs:50`'s `seon.repl` require being call-free (compile proof);
the scheduled-fire path's current live behavior (risk R6); whether the
default cluster has package ledger rows (risk R5); whether U8a registry
landings already serve any census-pending seed (§6); whether HEAD
`fe4bfedoc`'s fixture commit is fixfixture's landing (§7).

## 9. Owner/orchestrator decisions required before dispatch

- **D-U9-1 (scheduled fns):** where scheduled fires eval post-U9. Recommend:
  schedule fire = durable turn + wake; the claim driver's JVM eval phase
  executes it (no pod eval, no new mechanism). Alternative (rejected by R26
  spirit): pod keeps a local eval door.
- **D-U9-2 (compiled-symbol resolution):** the one mechanism replacing
  `seval/lookup-value` for route handlers/serve controls/core prompt calls.
  Recommend extending the U7 static trusted-table owner, sequenced with R43
  (§7). Constraint: no hand list that R43's computed rule then contradicts.
- **D-U9-3 (`:bun` placement window):** confirm fail-closed absence of the
  Bun eval tier between U9 and the JVM package leaf host unit (planner
  steering, loud). Matches R28 no-dual-maintenance; the alternative (keep
  child-lane alive for packages only) preserves the superseded path and is
  recommended AGAINST.
- **D-U9-4 (wire symbol name):** keep `'seon.execution.runtime/eval-batch!`
  as a relocated wire constant vs rename in S3 (risk R2). Vocabulary rule
  favors renaming to the promoted contract's namespace; either way one slice,
  all sites, conformance suites updated together.
- **D-U9-5 (repl.cljs / analyzer_info.cljs):** the audit's standing owner
  confirmation that no dev surface is retained — evidence now shows test-only
  consumers plus one stale require; recommend confirming whole-file death.
