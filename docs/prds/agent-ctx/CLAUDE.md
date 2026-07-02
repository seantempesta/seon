---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# agent-ctx — the shared chunk (two lanes, one context)

**Auto-loads for BOTH lanes. This is the shared, LIVE coordination surface —
update it when a tension resolves or a new one appears, so the other lane sees
it.** The chunk: **compose the agent's context from functions over the db, then
MEASURE whether that context + the tool surface actually let agents get shit
done.** Two lanes, one contract, one ledger.

The idealized system is `docs/seon/architecture/` (read `architecture.md` +
`context.md` FIRST — don't restate them). This folder is the roadmap chunk.
Cross-lane channel: **[[coordination]]**. Shared truth: **`evals/scorecard.jsonl`**.

## The two lanes

Owner framing (2026-07-02, continuing the agent-fsm split): **platform/core**
vs **UI/UX + agent testing/benchmarking**. Same boundary as below — mechanism
vs content — with UI/UX presentation polish on the eval/UX side.

- **Tooling / engine lane (agent-fsm continuation).** Owns: the runtime + FSM
  (`seon.agent.*`), the **context engine** (`seon.agent.ctx`, `seon.render`,
  `seon.eval`, `seon.instrument`), and the **`my.*` tool surface**
  (`my.plan`, `my.blob`, `seon.agent.{shell,web,fs}`). Builds what agents
  *have* and *how* context renders. Flagship: required-key resolution → the
  current-ns render-fn auto-run → block/tile twins → `my.*`-as-namespace-scribed
  entities → canvas = last-updated. Design:
  [[research/explicit-deps-injection-2026-07-02]]; carryover patch in
  `scratchpad/agent-scope-carryover/`.
- **Eval / measurement lane.** Owns: the standing inspect-ai suite over the
  `/solve` door (`src-inspect-ai/`), the dev/milestone/test tiers, `pass^k`
  stability, the flake taxonomy, and the context-refinement A/Bs those numbers
  drive (per-row rendered-context audits; every trim/skill-edit/tool-tune is an
  A/B against frozen samples — the ledger decides, not taste). Measures whether
  it works and refines *what* agents see. Spec: [[eval-design]]; plan:
  [[eval-lane-plan]].

## The contract between the lanes

- **Boundary (as drawn in [[eval-lane-plan]], agreed):** the eval lane does NOT
  touch tool/runtime/ctx-engine internals; the tooling lane does NOT touch the
  harness — it gets the numbers. When a row fails, the eval lane **attributes**
  it (context defect vs tool defect vs flake vs model) and hands tool defects to
  the tooling lane **with captured rendered-context evidence**.
- **Shared surface = the CONTEXT.** The eval lane tunes context CONTENT (which
  nses/skills render, trims — via `config/system.edn`, measured); the tooling
  lane owns the render MECHANISM (the engine, required-keys, auto-run). A change
  to *which* content renders is eval-lane; a change to *how* it renders is
  tooling-lane. When in doubt, flag in [[coordination]] before editing the other
  lane's file.
- **Shared truth:** `evals/scorecard.jsonl` (one row per capability per run).
  **Cross-lane flags + handoffs:** [[coordination]].

## Open tensions & issues — LIVE (update as they resolve)

Tool defects queued for the tooling lane (with rendered-context evidence — eval lane):

- **Fresh-world `my.kb` renders "0 fns, 0 schemas"** — a boot/indexing gap; the
  kb card is empty on a fresh cluster. Tooling lane. (eval blocker #4)
- **Turn-6 recall visibility gap during `/solve`** — candidate root = the
  `seon.db/*conn*` single dynamic root (not fiber-local); documented in
  `docs/seon/orchestrator/issues/tx-feed-pump-timeouts.md`. Tooling lane +
  parallel-scoring lever.

Tooling-lane build issues:

- **SCI-bounding fallback on `my.plan.internal/plan-block`** (eval lane,
  evidence attached 2026-07-02): fresh boot logs "Unable to resolve symbol:
  db/*conn*" under SCI bounding → the tile renders on the UNBOUNDED compiled
  path (a hang there would wedge the pod). Candidate root: `:seon.ns/source`
  require aliases not stored. Issue:
  `docs/seon/orchestrator/issues/sci-bounding-fallback-plan-block.md`.

- ~~**`my.plan` verbs are in `seon.instrument/skip-syms`**~~ — ✅ RESOLVED
  (2026-07-02): removed from skip-syms; the verbs ride the one injecting
  wrapper and declare `:seon.agent/id` (the ambient `scoped-agent` read is
  deleted; semantic failures still return `::ok?` envelopes; shape-invalid
  input surfaces as the structured instrument error at the eval boundary).
- **The agent↔`my.plan`-entity ref direction** — a design detail to nail during
  the entity-ref build.

Post-merge units (slotted, both lanes care):

- **pub-socket feed migration** — the tx-feed follow-up in
  `docs/seon/orchestrator/issues/tx-feed-pump-timeouts.md`; a real post-merge
  unit (tooling lane; de-flakes the whole chunk).
- **transact-timeout ambiguity** — the second scoped follow-up in the same
  issue note; clarify RPC-timeout semantics for transacts.

Eval-lane blockers before the first dev pass (from [[eval-CLAUDE-notes]]):

1. `SEON_SHELL`/`SEON_WEB` grants — ✅ GRANTED in both supervisors (code
   defaults stay deny-when-unset); table in
   `docs/seon/components/capability-gates.md`.
2. Planning bench re-grounded on the redesigned `my.plan` (deps/pace/expect —
   the old bench references pre-rename verbs).
3. Tool-row generators (shell / web-fixture / file-edit) authored.
4. Fresh-world `my.kb` empty render + turn-6 recall (the two tool defects above).
5. ~~One calibration run~~ — ✅ DONE 2026-07-02
   ([[research/calibration-run-2026-07-02]]): per-pod `/solve` ceiling = **1**
   (live conn-swap collision evidence at c=2); QA timeout 240s / default 300s
   wired into `src-inspect-ai/src/seon_inspect/config.py`.

## Settled — do NOT re-litigate (both lanes)

- Chunk name = `agent-ctx` (ctx is the established word). Vocabulary maps to
  Clojure primitives: **required-keys** (not injection/ALS), **current-ns**
  (not "workspace"), **refs** link the agent entity to namespace-scribed
  entities. A new noun = parallel-system risk.
- `docs/seon/architecture/` is the SINGLE idealized-system set; this folder is
  the roadmap chunk, not a second doc system.
- **Eval:** tier names dev/milestone/test; milestone is aggregate-only. Scorers
  gate CORRECTNESS (parses ∧ spec validates ∧ runs ∧ right answer) — idiom/style
  is reported data, never a gate. Established benches over homemade; bespoke
  only where no standard bench exists (plan-survives-restart has no public
  equivalent — stays ours). Flakes are classified + excluded from capability
  means. Long-term planning is the headline row. Bench utility is pod-agnostic;
  grants/endpoints are cluster config.
- **Owner rule:** no maintained code in PRD dirs (`src-inspect-ai/` is the
  precedent — a real top-level package). Env never shadows config. Uniform
  0-scores → suspect the harness/context first, not a model ceiling.

## The load-bearing finding (binds the whole chunk)

**Every check a scorer makes MUST be stated in the agent's context, or the bench
measures prompt-omission, not capability.** (DeepSeek preflight: 0/2 → ~1.0 on
the contract sentence alone —
`docs/prds/diffusion-dynamic-context/research/deepseek-preflight-drives-2026-07-02.md`.)
This is why the two lanes are one chunk: the eval lane's numbers are only
meaningful if the tooling lane's context actually says what the task needs.

## How to run

```bash
bin/seon status                       # pods/pids/port (7890 default)
bin/seon restart pod                  # wait for "auto-boot ready" in logs/pod.log
bin/seon cluster reset default        # fresh world (shared pod: coordinate)
bin/seon print-env                    # verify SEON_SHELL/SEON_WEB grants
bin/gym-scorecard                     # free fitness signal (tooling-lane inner loop)
# eval suite (once live): src-inspect-ai/README.md run matrix → evals/scorecard.jsonl
# live-drive: (seon.db/with-agent "root" (fn [] (seon.agent/start! {:seon.agent/purpose "…"}))) ; then rearm-wake-triggers!
```

Pod ownership (owner ruling 2026-07-02): **tooling lane owns the default pod
(7890); eval lane owns acme (7980).** Separate systems — neither lane waits on
or coordinates restarts/resets with the other; restart/reset your OWN pod
freely and always verify you're on the latest build with the right context
(`bin/seon restart pod` after code changes, `cluster reset default` after
context/verb changes). Never touch the other lane's pod. Never the JVM track.

## Good practices (structurally enforced — inherited, not restated here)

**Owner standing directive (2026-07-02, both lanes): surface complexity
artifacts.** Much of the odd complexity is incomplete-context agent residue —
parallel mechanisms, hand-maintained exception lists (e.g. `skip-syms`),
silent fallback paths, dual homes for one corpus. Every unit's report includes
a "complexity artifacts found" section; each item names file:line + the
existing system that could subsume it, and gets ASKED to the owner with a
recommendation — never silently kept, never silently ripped out.

Read `docs/seon/architecture/architecture.md` + `context.md` first; the
`src/seon/CLAUDE.md` ONE-mechanism table auto-loads on any `src/` edit. Live-drive
don't infer; slow-is-fast (read `reference-code/`, verify in the REPL);
errors-as-values, derive-don't-store, one-mechanism-in-place; commit per unit
with EXPLICIT pathspecs (peers share the tree); `bin/test-cljs` green once per
unit; `cluster reset default` after a context/verb change.

## Metadata — the pointer index (docs · research · files-to-update · tests)

- **Architecture (idealized system):** [[architecture]] · [[context]] ·
  [[data-model]] · [[agent-runtime]] · [[ui]] · [[observability]] · [[toolkit]] ·
  [[laws]]
- **Eval-lane docs:** [[eval-design]] (the spec) · [[eval-lane-plan]] (work
  plan A–E + the boundary) · [[eval-CLAUDE-notes]] (absorbed above) ·
  [[research/tool-surface-survey-2026-07-02]] (per-row readiness + flake taxonomy)
- **Tooling-lane design:** [[research/explicit-deps-injection-2026-07-02]] ·
  the Phase-1 patch in `scratchpad/agent-scope-carryover/`
- **Harness (code — eval lane, do not maintain from the tooling lane):**
  `src-inspect-ai/` (`seon_inspect.solver`, `oracle_scorers.py`, `catalog.py`,
  `README.md` run matrix)
- **Issue notes to update as they resolve:**
  `docs/seon/orchestrator/issues/tx-feed-pump-timeouts.md` (pub-socket
  migration + transact-timeout) · `docs/seon/components/capability-gates.md`
  (gate table)
- **Evidence base:** the DeepSeek preflight battery (7/7) ·
  `docs/prds/agent-fsm/roadmap.md` (the shipped-2026-07-02 capstone this chunk
  builds on)
- **Ledger / shared truth:** `evals/scorecard.jsonl` · **channel:** [[coordination]]

## Build order

- **Tooling lane:** ~~apply the Phase-1 required-key patch~~ (✅ landed
  `a6362630` on `feature/agent-ctx`) → ~~register
  `:seon.render/at` + resolve `my.plan` skip-syms~~ (✅ 2026-07-02) → current-ns render-fn
  auto-run (block/tile twins) → `my.*` entity-ref composition (`my.plan` worked
  example) → canvas = last-updated → then the queued tool defects (my.kb empty
  render, recall visibility) + the pub-socket migration.
- **Eval lane:** calibration run → dataset freeze (three-way splits +
  `datasets.lock` + canary GUIDs) → tool-row generators → planning bench
  re-ground on the new `my.plan` → first dev pass → the ledger + `pass^k`
  regression alarm → cadence + per-row context A/Bs.

Each lands with a live DeepSeek drive / an eval row proving it — not inference.
