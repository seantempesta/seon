---
type: research
status: active
tags: [research, agent]
---

# Execute-code / generate-code pipeline audit — 2026-07-21

Read-only audit of what exists, what is PoC, and what is missing for the
target behavior: a long dependency-ordered list of namespace updates is
applied in order, per-unit success/failure is durable data, failed units go
to fix-up subagents that see the full plan, a strong model does the first
pass, and a lighter model finishes.

## Current-state map

### 1. Ordered apply + per-unit ledger — EXISTS, live-proven (CLJS path)

The general mechanism is the CLJS pipeline, not the host loader:

- `seon.repl.internal/parse-program` (`src/seon/repl/internal.cljc:1508`)
  and `project-program` (`:1424`) read one planner reply once, fence entries
  by real `(ns …)` declarations, recognize aliased `schema/register!`, and
  derive namespace require edges; cycles return as structural errors before
  evaluation.
- `seon.agent.turn/reply-program` (`src/seon/agent/turn.cljs:578`) feeds the
  projection to the one `eval-batch!` in `:batch` mode: dependency order
  across namespaces, authored order within, schemas first, top-level Promises
  awaited, independent namespaces survive a failure, ordered
  `:seon.eval/ids` returned (`turn.cljs:660-676`).
- Per-unit ledger is durable data: every entry is a `:seon.eval` row
  (source, ok?, error, error-data, ns); test-runner counters land on the
  causing eval; unit completion is derived from that evidence
  (`src/my/plan/internal.cljc:797` "Derive positively completed generated
  namespaces from one exact eval batch").
- `publish-generated-program!` (`src/my/plan.cljc:1166`) receives the exact
  in-memory program + batch from the turn (`turn.cljs:677-690`) and
  `compile-namespace-dag` (`src/my/plan/internal.cljc:891`) idempotently
  reconciles namespace leaves under one durable `my.plan` root, with
  `:my.plan/needs` as the only dependency representation.

Live-proven 2026-07-21 (`da845f88` rerun, roadmap.md:67-96): 18/18 forms
across a hot reload, multi-unit DAG published, green units completed from
evidence, `:seon.fn/source-fingerprint` rows persisted, behavioral deftests
recorded, `:blocked` terminal envelope delivered to the caller.

**U5's loader ledger is host-boot-only.** `seon.host.context` discovers
`src/my` files on disk, orders them with `dependency-order`
(`src/seon/host/context.clj:725`), and records loaded/failed/excluded rows
(`context.clj:135-165`) for `load-portable-slice!` (`:817`). It is the JVM
SCI-host toolkit bootstrap, not a general apply-updates mechanism. Note the
repo now has two topological orderers over require edges (`project-program`
over parsed reply entries; `dependency-order` over parsed source files) —
different corpora and runtimes, but a convergence candidate the sci-host
lane should keep in view. `seon.host.record/ns-require-edges`
(`src/seon/host/record.clj:253`) and the CLJS analyzer both write the same
`:seon.ns/require-edges` facts (`src/seon/eval.cljs:774`), so the durable
edge vocabulary is already shared.

### 2. Error → fix-up loop — EXISTS as machinery; worker context is partial

Errors are values with plan-connected provenance: eval failure rows carry
`:seon.eval/error`/`:seon.eval/error-data`, belong to a turn, the turn to a
run, the run's `:seon.agent.run/cause` to the assignment message, the
message to the `my.plan` step. Nothing throws into the loop.

Fix-up dispatch is the reactive root scheduler in
`src/seon/ai/generate_code.cljs`:

- `observe-root!` (`:313`) computes `plan/generated-root-state`
  (`src/my/plan.cljc:674`) reactively; `root-notify` (`:485`) dispatches the
  ready frontier or commits the terminal.
- `dispatch-root-state!` (`:399`) → `ensure-and-claim!` (`:345`) →
  `agent/ensure-namespace-agent!` (`src/seon/agent.cljs:1082`) ensures one
  reusable namespace-resident worker, then `claim-namespace-step!` (`:281`)
  commits the `:db.fn/cas` claim + ordinary addressed assignment message +
  step-message link in ONE transaction. Competing claims classify a benign
  loss by rereading the committed claim (`claim-race-result`, `:262`).
- Blocked propagation: `my.plan/blocked!` (`src/my/plan.cljc:1406`),
  terminal fence + compact addressed result via
  `commit-generated-terminal!` (`src/my/plan.cljc:1035`), restart recovery
  via `restore-root-schedulers!` (`generate_code.cljs:536`).

What the failed-unit worker actually receives today is thinner than the
Stage 6 contract: the assignment message is a short pointer
(`assignment-content`, `generate_code.cljs:338`), and the worker's context
comes from the shared `:plan` block plus the `:namespaces` block. The
roadmap's "original complete planning reply, accepted prefix, failed eval
IDs, test errors, sibling status, full target/.internal source" bundle
(roadmap.md:729-732) is not yet rendered — Stage 6 is the open stage.

### 3. Model routing — EXISTS; Kimi K3 is cataloged and configured

- Per-agent provider overlay: the complete non-secret surface lives on the
  agent entity (`:seon.ai/agent-provider`, `/agent-model`, `/agent-base-url`,
  `/agent-api-key-env`, …; contract in `src/seon/ai/CLAUDE.md`), resolved
  request > agent > cluster row > defaults. Two agents in one cluster can
  and do run different providers — the 2026-07-21 gencode graduation ran a
  Kimi K3 planner and Muse workers in one cluster (roadmap.md:56-60).
- Named variants: `:seon.config/model-variants` in `config/system.edn:282-305`
  ships `:planning` = `kimi-k3` (`:openai-compat`,
  `https://api.moonshot.ai/v1`, `MOONSHOT_API_KEY`,
  `:max-completion-tokens`, 16384 out, 300s/360s fences, 1 retry) and
  `:execution` = `deepseek-v4-flash`. Birth-copy machinery:
  `model-variant-overrides` (`src/seon/agent.cljs:422`), consumed by
  `start!` (`:1058`) and `ensure-namespace-agent!` (`:1082`).
  `generate-code!` selects `:planning` for the planner and `:execution` for
  workers (`generate_code.cljs:690`, `:720`); callers never name a provider.
- Catalog: `docs/seon/reference/llm-adapters.md:165` (pricing row) and
  `:221-238` (Moonshot section, verified transport, `reasoning_effort`
  max-only, omit temperature). Caveat recorded there and in the roadmap:
  only single-response transport is verified; two live K3 planning calls
  timed out during graduation and Muse substituted (roadmap.md:57-59). The
  strong-first-pass/light-finish split is therefore configured and
  mechanically proven, but K3-as-planner task quality is NOT graduated.

### 4. Plan visibility for subagents — PARTIAL

The `:plan` block is agent-scoped: its frontier/active/done queries filter
on `:my.plan/agent ?agent` (`src/my/plan/internal.cljc:1270-1308`). A
namespace worker does NOT see the whole parent plan through those. What it
does see:

- `run-cause-step-query` (`internal.cljc:1310`) walks its current run's
  cause message to its assigned step; `ancestor-selector`
  (`internal.cljc:1326`) pulls the parent chain to the generated root,
  including root goal/description/expect; `root-rollup-query` (`:1332`)
  renders done/total counts (`anchor-section`, `:1673`).
- Specialized residents install `generate-code-plan-block`
  (`internal.cljc:1815`) on their existing `:plan` block, which prepends
  `development-teaching` (`:1764`) over the same acquisition.
- Full plan facts are cluster-global database data, so a worker CAN query
  sibling steps (`my.plan/tree`, `db/query`), but the rendered context does
  not include the sibling DAG/status band today; that is a Stage 6 exit
  item, and the original planner reply blob is likewise reachable but not
  rendered.

## Gap table

| Gap | Design or implement | Owner |
|---|---|---|
| Evidence-derived `:done` terminal (planner's own `done!` bypasses envelope delivery) | implement — settled design, one terminal owner ([[../../seon/issues/planner-self-done-bypasses-generated-terminal-delivery]]) | generate-code (Stage 8) |
| Planner redeclaring its home ns blocks root on self-recipient dispatch | implement ([[../../seon/issues/planner-home-ns-step-blocks-on-self-recipient]]) | generate-code (Stage 8) |
| Planner no-reply strand: root stays `:open` with no retry path | design (retry/deadline policy) + implement ([[../../seon/issues/generated-root-has-no-planner-retry-path]]) | generate-code (Stage 8) |
| Stage 6 worker context bundle: original reply, accepted prefix, failed eval IDs + test errors, sibling status, `:namespaces` full-source reconciliation to target + owners + `.internal` | implement — contract written (roadmap.md:703-732); only ranked-compact reconcile exists (`generate_code.cljs:199`) | generate-code (Stage 6) |
| Warm-worker preference query (reuse idle prior namespace worker) | implement — relations settled (roadmap.md:390-396) | generate-code (Stage 6) |
| Live embedding-ranked context proof (SEON_EMBED on); only unranked fallback live-proven | implement/prove | generate-code |
| Stage 8 full drive: two-namespace goal, deliberate defect, warm repair, dependent ordering, compact `:done` envelope, cost ledger | prove | generate-code (Stage 8) |
| K3 planner task-quality graduation (two live timeouts; Muse substituted) | prove; possibly design a planner-timeout fallback variant | generate-code + llm-adapters catalog |
| LONG-list scale: multi-reply plans, batching beyond one planner reply, cross-root concurrency policy | design — explicitly deferred (roadmap.md:791-799) | generate-code post-MVP |
| Dynamic per-namespace model escalation (repair variant upgrade per failure class) | design — deferred (roadmap.md:797) | generate-code post-MVP |
| Two require-edge topological orderers (CLJS `project-program` vs JVM `host.context/dependency-order`) | observation — different corpora/runtimes today; convergence question when host executes agent programs | sci-execution-runtime |
| Host-side pipeline parity (repair sub-loop, run-fence CAS, print capture absent host-side) | implement — named honest limits (sci-execution-runtime roadmap U2/U3 notes) | sci-execution-runtime |

## Recommended pipeline shape (data-first)

The shipped design already is the recommended shape; keep it and finish it
rather than adding anything:

- **Plan as facts**: one `my.plan` root (goal/description/expect), namespace
  leaves with `:my.plan/namespace` refs and `:my.plan/needs` edges — no
  order numbers, no stored ready state, no generation entity.
- **Units as facts**: `:seon.eval` rows + test refs on the causing eval are
  the per-unit ledger; completion is derived, never asserted by prose.
- **Fix-up as derived work queue**: the ready frontier is a reactive query;
  ownership is the CAS claim + one addressed assignment message; blocked is
  a plan transition with error evidence; terminal delivery is one fenced
  transaction that commits status + compact envelope together.
- The missing pieces are all context/terminal legs of that same shape:
  render the worker's full-plan orientation from the facts that already
  exist (root chain + sibling steps + failed eval rows + reply blob), and
  route every root-closing path through the one terminal owner.

## Answers in one line each

1. Ordered apply + ledger exists and is live-proven on the CLJS path
   (parse-once → dependency-ordered `eval-batch!` → durable eval rows →
   `my.plan` DAG); U5's loader is host-boot-only.
2. Errors are plan-connected values and failed units are CAS-claimed to
   namespace workers; the worker's rendered fix-up context is still the
   thin pointer + generic blocks, not the Stage 6 bundle.
3. Per-agent provider overlay + named `:planning`/`:execution` variants
   copied at birth; different models per agent in one cluster is proven
   live; Kimi K3 is a shipped `:planning` variant and catalog row, transport
   verified but task quality not graduated (two live timeouts).
4. A worker sees its step, the ancestor chain to the root contract, and the
   rollup — not the sibling DAG or original reply in context yet; all of it
   is queryable as facts.
5. Design is settled through Stage 8; remaining work is implementation and
   proof (three named issues + Stage 6 context + Stage 8 drive), with only
   long-list batching, cross-root concurrency, retry policy, and model
   escalation still needing design — the sci-host lane separately owns host
   execution parity.
