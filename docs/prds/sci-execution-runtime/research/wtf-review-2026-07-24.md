---
type: research
status: active
tags: [research, runtime]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# WTF review: one agent turn, traced at HEAD (2026-07-24)

Fresh-eyes review against the stated pitch: stateless agents, everything is
database facts, turn = derive context → LLM → guarded sci eval with direct db
access → facts committed → UI derives. Transaction processing. This report
judges the actual source on `codex/runtime-reliability-refactor` HEAD, not the
PRDs.

Verdicts: **NECESSARY** (inherent to crash-tolerant multi-process
coordination), **DEFENSIBLE** (real reason, could be simpler), **WTF**
(indirection with no load-bearing purpose at the current topology).

## The trace: one message-triggered turn, A → B

A = user message posted. B = agent's committed facts + reply visible in the UI.

| # | Hop | Namespaces crossed | What actually happens | Verdict |
|---|-----|--------------------|-----------------------|---------|
| 1 | Message + wake | `seon.web.serve` → `seon.agent.message` | message tx; wake trigger fires | NECESSARY |
| 2 | Open run | `seon.agent.loop` (`wake-handler`, `open-or-renew-message-run!`) → `seon.agent.run/open-run!` | CAS-guarded run row: bounds, trigger, started-at | NECESSARY |
| 3 | Claim | `seon.agent.driver.pod/dispatch-run!` → `seon.agent.driver/claim!` → `seon.agent.run.core/claim-plan` | `acquire-run-state!` (26-attr run pull + config pull + pending-input query), claim transition CAS tx, then a **second** full `acquire-run-state!` re-read | claim: NECESSARY; the double acquisition and 5-way transition taxonomy (acquire/attach-acquire/reacquire/held/steal): DEFENSIBLE |
| 4 | Step loop | `seon.agent.driver/drive-claim!` + `seon.agent.loop.core` (`eligible?`, `next-step`) | close-reason check, capability eligibility, phase → step dispatch through a leaf map | phase cursor: NECESSARY-ish; the capability/eligibility layer exists **only because one turn spans two processes** — see WTF-1 |
| 5 | `:render` (Bun pod) | `seon.agent.turn/render-phase!` → `render-prompt` → `seon.agent.ctx.driver/render-prompt!` → `seon.agent.ctx` + `ctx/*` block families | 3-member `execute-many` acquisition; stored blocks resolved; **authored render fns round-trip to the JVM over the UDS frame protocol** (`seon.host.session.leaf` → `seon.host` server → `seon.host.invoke/begin-invocation!` → guarded door → frame back), per block; second resolve pass for derived blocks; prompt blob put; turn row + `:rendered` tx under fence | derivation itself: NECESSARY. Doing it on the pod while eval lives on the JVM: WTF-1. The pod→JVM→pod bounce per authored block: WTF-2 |
| 6 | Handoff pod→JVM | `release!` tx → JVM `db.host/listen!` (interest = `:all`) → `scan!` re-queries **every** open run → new vthread → `claim!` again (epoch+1) | full release/wake/scan/reclaim cycle mid-turn | WTF-1 (cost of the split); the `:all`-dependency listener rescanning everything on every commit: DEFENSIBLE at current scale, won't survive load |
| 7 | `:open-attempt`/`:settle-attempt` (JVM) | `seon.agent.driver.host/execute-step!` → `seon.agent.turn.llm/llm-phase!` → `durable-attempt!` → `seon.ai.core` transport | pull turn; `as-of` at rendered-tx; `resolve-llm-context!` (2 more config pulls); read prompt blob; split on a magic boundary string; pull turn **again** inside `durable-attempt!`; crash-mark stale open attempt; allocate attempt id; open-attempt tx; transport with watchdog interrupt + partial-text no-history sink; reply blob put; terminal tx → `:reply-ready` | durable attempt rows + resume: NECESSARY for "LLM call survives run-holding process death". The ~40-optional-key attempt evidence row built by **two** overlapping builders (`turn.core/open-attempt-row` vs `turn.llm/attempt-row`, the open row derived by calling `attempt-row` with `{}` then dissoc'ing): WTF-5 |
| 8 | `:eval` — plan | `seon.agent.driver.host/eval-step!` → `seon.program.plan/acquire-planning-projection` + `plan-execution` → `seon.program.edge` → `seon.agent.driver/execution-plan-disposition` | read reply blob, parse once; then **three unbounded corpus queries** (every `:seon.program.edge` bundle, every schema, every fn contract), reconstruct bundles, digest the whole graph, statically analyze every reply form (`edge/analyze-function`), fold the call graph to a placement, build schema + capability manifests, classify into 6 dispositions | WTF-3. ~1,240 lines (`plan.cljc` 671 + `edge.cljc` 567) on the hot path of **every** reply, to answer "run here or hand to bun," with exactly one JVM tier inventory installed and the bun tier being retired |
| 9 | `:eval` — execute | `run-eval-batch!` → `seon.host.invoke/execute-invocation!` → `seon.host.guard` → `seon.host.eval/eval-batch-result` → `seon.host.preflight` / `seon.host.record` / `seon.host.graduate` / `seon.host.instrument` / `seon.host.context` | `:reply-ready→:evaling` tx; per-session context fork; provision bindings; eval-pool submit; guard policy pull (`sample/acquire-guard-policy!`); guard reset/arm; **standalone fence-only tx** (`claim-run-fence!`); per form: receipt tx → preflight (repair + `:malli/schema` admission) → sci eval with step budget + output cap → terminal tx (fence + eval row + `:seon.fn`/`:seon.ns`/`:seon.schema` tees) → nursery install → instrument reconcile → projection publish; then `resolve-head!`, pull turn, `:evaling→:evaled` tx | guard door, receipt-before-run, terminal-with-tees: NECESSARY — this is the actual product. The fence asserted at four layers (claim tx, standalone pre-batch tx, every receipt, every terminal): WTF-6 |
| 10 | Handoff JVM→pod | release → listener → scan → reclaim | second full mid-turn handoff, because `:publish` is a pod capability | WTF-1 |
| 11 | `:publish` (pod) | `seon.agent.turn/publish-phase!` → `my.plan/publish-generated-program!` | pull turn, read reply blob **again**, **re-parse the reply a second time** (`turn.core/reply-program` again), publish program, `:evaled→:published` tx, turn `:done` | the re-read/re-parse: WTF-4; the phase itself could be part of eval settlement |
| 12 | Close + UI | `drive-claim!` close tx (retract run-holding process + agent pointer); writer feed → `seon.reactive` → render units → Datastar SSE morph | | NECESSARY. The reactive/UI derivation path genuinely matches the pitch |

**Hop count: 12 macro-hops**, of which hop 8–9 internally contain ~10
sub-hops. Per-turn transaction count on the happy path: claim, rendered,
release, reclaim, attempt-open, partial×k, attempt-terminal, evaling,
fence-probe, (receipt + terminal)×N forms, evaled, release, reclaim,
published, close ≈ **13 + 2N transactions**, plus two authored-render UDS
round-trips per authored context block, plus 8+ separate pulls of the same
config singleton.

## The WTF list, ranked by unnecessary-complexity cost

1. **One turn spans three processes, twice bouncing through full claim
   arbitration.** Render and publish are pod capabilities; llm/eval are JVM
   capabilities (`driver/pod.cljs` vs `driver/host.clj
   claimant-capabilities`). So every turn does two release → all-tx listener
   wake → scan-every-open-run → reclaim(epoch+1) cycles. The
   capability/eligibility layer, the release disposition, the handoff-tier
   selection policy, and half of `drive-claim!`'s branches exist to service
   this split. The pitch ("context derived from the db → LLM → eval on a JVM
   run-holding process") contains no reason context assembly — a pure derivation over an
   immutable database value — can't run on the cluster JVM.
   `seon.host.invoke` even says so out loud: "render-prompt!/render-agent-view!
   remain pod-served: the host serves EVAL; the pod keeps rendering (design
   §1)." Interim or not, this is the single largest hop generator.

2. **Two IPC mechanisms execute agent code on the JVM inside the same turn.**
   The eval phase reaches the guarded door via the database claim cursor. The
   render phase reaches the *same door* via a UDS socket frame protocol
   (`seon.host.clj` server, `seon.host.session.leaf` client, frames,
   `begin-invocation!`/`settle!`/`cancel-active!`), because authored render fns
   inside a pod-rendered prompt must run on the JVM. Same concept — "invoke
   agent-authored code under the guard" — two complete transports with two
   error vocabularies. This is the vocabulary-table sin at the architecture
   level.

3. **The plan/disposition layer is far heavier than the decision it makes.**
   Per reply: 3 unbounded queries reify the entire program graph + schema
   corpus into a `planning-projection`; every reply form is statically
   analyzed; a call-graph fold computes eligible tiers, a schema manifest, and
   a capability manifest; `execution-plan-disposition` re-checks the manifests
   against the same projection they were computed from. The evidence map's
   `:seon.execution/observed-generation` is literally assigned from
   `planned-generation` (`driver.cljc` ~L363–365) — a vacuous check that will
   never fire. `cache-key` is computed on every plan; nothing in `src/` caches
   by it. All of this to choose between `:execute` here, `:release` to bun, or
   an error — in a deployment with one JVM tier inventory and a bun tier slated
   for deletion. The genuinely needed check — "do this reply's resolved calls
   have their capability bindings installed here?" — is a set-membership test
   at require-resolution time, not a whole-graph derivation. Derived placement
   is a fine *idea*; putting the full derivation on the per-reply hot path is
   ceremony.

4. **The crash-recovery path the cursor exists for is broken.**
   `seon.agent.driver.host/settle-eval-step!` (~L700) replays an
   empty-receipt batch with `(run-eval-batch! host storage-view run
   claim-epoch database)` — 5 args, `storage-view` in the `run` position —
   against a 7-arg signature `[host run claim-epoch database program
   invocation-configuration execution-plan]`. The run-holding process died-after-
   `:evaling` recovery would throw `ArityException`. A six-phase durable
   cursor whose distinguishing recovery arm doesn't compile-check is strong
   evidence the phase matrix is bigger than what's actually exercised.

5. **The superseded stack is still alive next to its replacement.** The old
   pod trio — `loop.cljs` (702 lines, now wake plumbing + dispatch),
   `run.cljs` (1,167), `turn.cljs` (811) — coexists with the portable driver
   (`driver.cljc` + `run.core` + `turn.core` + `turn.llm` + two leaves).
   Concrete duplications: two turn-row constructions (`turn/open-turn!` vs
   `render-phase!`); two reply parses per turn (eval-step and publish-phase
   each blob-get + `reply-program`); two attempt-evidence builders
   (`open-attempt-row` vs `attempt-row`); dead `pod-phases`/`host-phases` defs
   in `loop/core.cljc` (nothing references them); the retired "execution
   child" vocabulary (`::session/channel`, frames, "child retired?" checks in
   `turn.cljs`) still woven through live code; `artifact-digest` hardcoded to
   64 zeros in `driver/host.clj` and used as a *type tag* (`compiled? =
   contains :artifact-digest`) — a fake digest as a `:kind` discriminator.

6. **Fence and config ceremony.** The run fence (2 CAS) + phase fence (1 CAS)
   ride every phase tx — fine — but the fence is also transacted as a
   **standalone probe tx** before each batch (`claim-run-fence!`), and rebuilt
   by three different builders (`run.core/run-fence`,
   `eval.clj/run-fence-transaction`, `turn.core/phase-fence`). The config
   singleton is pulled independently by claim policy, `resolve-llm-context!`,
   `invocation-configuration!` (on two paths), `acquire-guard-policy!`,
   `acquire-sampling-policy!`, and prompt acquisition — 8+ pulls of one
   entity per turn, each with bespoke missing-key prose.

7. **Vocabulary drift across one call stack.** A single logical operation
   ("run the reply") crosses `:seon.repl/*` (parse) → `:seon.execution/*`
   (plan/invocation) → `:seon.agent.driver/*` (disposition) → `:seon.host/*` /
   `::session/*` (execution) → `:seon.eval/*` (receipts). "Invocation,"
   "execution child," "session," "leaf," "frame," "claim," "step," "phase" all
   name slices of the same turn. The interaction lane adds a parallel species:
   its own claim transition (`attach-acquire`), its own phase short-circuiting
   the turn FSM, its own status/tx vocabulary — the full claim machinery spent
   to call one authored function with arguments.

## What is genuinely good

- `seon.agent.run.core` (190 lines): the claim/epoch/lease/steal algebra is
  tight, pure, correct-looking, and exactly what the crash-tolerance story
  needs. Keep it verbatim.
- `seon.host.guard` (242 lines): step budget + deadline + output cap through
  one array-backed safepoint, with the caught-interrupt retention trick.
  Proportionate and sharp.
- Receipt-before-run / terminal-with-tees in `seon.host.eval`: the "no
  receipt, no run" boundary plus the fn/ns/schema tee is the actual product
  (code corpus as data) and earns its complexity.
- The UI side (facts → reactive derivation → SSE morph) matches the pitch as
  advertised.

## The minimal design for this exact goal

Stateless agents, one database, processes may die, agent code interpreted and
budgeted. The minimum I believe correct:

**One cluster JVM with all capabilities.** Keep `run.core` claims
exactly as-is (run-holding process + epoch CAS + heartbeat + steal). A claimed run is
driven start-to-finish in one process; another run-holding process steals only on death.
No capability sets, no eligibility, no mid-turn release disposition, no
handoff tier.

**A 3-checkpoint turn, not a 6-phase cursor.** Recovery needs a durable
checkpoint only where redo is expensive or effectful: (1) prompt blob
committed (context derivation is pure — recompute it on resume, don't
checkpoint it separately from the turn row), (2) reply blob + attempt outcome
committed (the LLM call is the expensive step; keep ordinal/outcome/deadline +
a config digest, move the 40-key evidence to a raw-response blob ref), (3)
eval receipts (already per-form durable). `rendered / replied / settled`
replaces `unstarted / rendered / attempt-open / reply-ready / evaling /
evaled / published`. Publish is part of settlement — parse once, use the parse
for both eval and program publication.

**Placement is a lookup, not a derivation.** At require-resolution time inside
the one sci context: if every capability binding a form needs is installed,
eval it; otherwise return an agent-visible error value naming the missing
binding. That is the entire admissible-here decision. The program-graph edge
analysis survives as a background indexer feeding context/search/purity facts
— off the turn's critical path.

**Keep**: guard door unchanged; receipt/terminal/tee recording unchanged;
errors-as-values unchanged; UI derivation unchanged.

The resulting A→B path: message tx → listener wake → claim CAS → derive
context (local pure fn over the pinned db value, authored blocks through the
same in-process guarded door) → prompt blob tx → LLM call under watchdog →
reply blob tx → per-form receipt/eval/terminal → close tx → feed morph.
**~7 hops, ~6 + 2N transactions, zero mid-turn process handoffs, zero IPC**,
with the identical crash story: epoch steal + resume from three checkpoints.

## Deletion / merge list

- **Delete** the render/publish pod capabilities and the mid-turn
  release-handoff machinery once prompt rendering runs on the cluster JVM:
  `driver/pod.cljs`, the capability/eligibility layer in `loop/core.cljc`,
  the `:release` disposition, `handoff-tier` selection policy.
- **Delete** the UDS invocation frame protocol for authored renders
  (`seon.host.session.leaf`, frame/settle/cancel machinery in
  `seon.host.invoke`, the `seon.host.clj` socket server) — same-process call
  through the guarded door replaces it.
- **Remove from the hot path** `seon.program.plan/plan-execution` +
  `acquire-planning-projection` + `execution-plan-disposition` + both
  manifests; replace with binding-resolution checks at eval time. `edge.cljc`
  becomes a background indexer only. Delete the vacuous
  planned/observed-generation evidence pair and the uncached `cache-key`.
- **Collapse** the 6-phase cursor to 3 checkpoints; merge `publish-phase!`
  into eval settlement (single parse, single blob read).
- **Delete** the old pod trio (`loop.cljs`, `run.cljs`, `turn.cljs`) at the
  great deletion; until then, at minimum delete the dead
  `pod-phases`/`host-phases` defs and the "execution child retired" residue.
- **Merge** `open-attempt-row`/`attempt-row` into one builder; move bulk
  attempt evidence to a blob ref.
- **Consolidate** config acquisition to one singleton pull per claim, threaded
  through the held state.
- **Drop** the standalone `claim-run-fence!` probe tx; the fence on the actual
  write transactions is the load-bearing one.
- **Replace** the zero-digest `artifact-digest` type-tag with an explicit
  invocation attribute (or delete the discriminator when authored/compiled
  unify).
- **Fix now** (independent of any redesign): the `settle-eval-step!` 5-arg
  call to 7-arg `run-eval-batch!` in `seon.agent.driver.host` (~L700), and add
  the run-holding process died-mid-`:evaling` recovery test that would have caught it.
