---
type: research
status: active
tags: [research, agent]
---

# DeepSeek preflight drives — verifying the harness before GPU money (2026-07-02)

> Owner mandate: run everything testable with cheap DeepSeek agents on the acme
> pod FIRST, so the A100 session never again measures a broken harness instead
> of the model (the voided-E1 class). Every drive scored through the REAL
> oracles (`bin/oracle-server` bb parse/structural/phase + the
> `out/worker-oracle-eval/main.js` cljs.js eval tier); every agent's rendered
> context captured (`SEON_DEBUG_CAPTURE`) and read. Fresh acme world
> (`bin/acme build` + `cluster reset` this morning; pod 7980, DeepSeek =
> `deepseek/deepseek-chat` via the OpenRouter openai-compat provider from
> `.env.acme`).

## TL;DR — verdict table

| # | Test | Protects against | Verdict |
|---|---|---|---|
| 1 | Oracle-liveness gate (golden sample; then a dead bundle path) | scoring against a dead oracle (the voided-E1 cause) | **PASS** — golden green on live bundles; dead path → loud `SystemExit` |
| 2 | E1-shape task, live DeepSeek agents, contract-stated prompt (N=3, then N=3 after prompt fix) | the voided-E1 mistake repeating | **PASS after fix** — first pass 1/3 faithful exposed TWO residual prompt/scorer mismatches; fixed prompt → **3/3 faithful** |
| 3 | Contract-omission control (N=2) | believing 0-scores are model failures | **CONFIRMED** — 0/2 behavioral without the stated contract (strong AR model); the contract prompt is load-bearing |
| 4 | Gym path `bin/acme gym-diffusion` (2 scenarios, eval tier on) | the legacy scoring path rotting before inspect parity | **PASS** — both end-to-end, scorecards + asserts green, zero src edits |
| 5 | Agent-loop basics: plan → mid-task `bin/acme restart pod` → resume + recall | burning GPU sessions on a broken agent loop | **PASS** — durable todos + KB facts survive restart; resumed agent replies the correct sum (49) and self-completes |
| 6 | `:diffusiongemma` provider graceful-down + recovery | the provider wedging the loop when the endpoint is absent | **PASS** — dead endpoint = graceful `:seon.ai/error` + run closes after ONE turn; DeepSeek recovery clean. 3 observability nits (§6) |
| 7 | inspect-ai eval via `/solve` (memory_qa_smoke, 3 samples) | the go-forward standard harness broken on a fresh world | **PASS w/ finding** — end-to-end works (accuracy 2/3); the miss is a 300s TIMEOUT with a real fresh-world cause, not a wrong answer |

**Mistakes found that WOULD have wasted GPU money** (each now fixed or fenced):

1. **Two residual prompt/scorer contract mismatches in the E1 harness** (§2) —
   even after the 07-02 audit fix, a correct-code sample scored `structural: false`
   (the scorer demands the NAMED `-request`/`-response` registration pattern,
   `e1_kill_gate.py:367`, which the prompt never stated) and another scored
   `eval_ok: false` (the decoupled eval bundle throws **`No *load-fn* set`** on ANY
   `(require …)` form — pod-valid code, sandbox-invalid). A DiffusionGemma run
   would have hit both walls and under-scored every arm. **Fixed in the prompt**
   (states the naming convention + "no require/ns forms"); DeepSeek went 1/3 →
   **3/3 faithful**.
2. **Fresh-world `my.kb` renders empty** (§7) — on the just-reset store the
   namespace card shows "my.kb — 0 fns, 0 schemas", so a memory-task agent burned
   3 turns grepping for `remember` and timed out at 300s. Any fresh-world GPU
   bench with KB tasks inherits this. (agent-fsm lane; documented, not fixed here.)

## §1 Oracle-liveness gate

- Golden sample against today's bundles: `[oracle-selfcheck] GOLDEN OK —
  parse/structural/behavioral live`.
- `EVAL_BUNDLE=/nonexistent/dead-bundle.js` → `SystemExit` with the full scorer
  dump and the rebuild instruction; `E1_ALLOW_DEGRADED=1` is the only override.
- Conclusion: the exact 06-29 failure mode (silently scoring a dead oracle) now
  aborts before any arm runs.

## §2 E1-shape task on live DeepSeek (contract-stated)

First pass (audit-fixed prompt, N=3 via `POST /solve`, fresh agent per sample):

| sample | parses | structural | eval | behavioral | faithful | failure named |
|---|---|---|---|---|---|---|
| #0 | ✓ | ✗ | ✓ | ✓ | ✗ | inlined the request/response maps in `:malli/schema` — correct code; scorer demands `-request`/`-response` NAMES (`e1_kill_gate.py:367`), prompt didn't say so |
| #1 | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| #2 | ✓ | ✓ | ✗ | ✗ | ✗ | reply opens `(require '[seon.schema :as schema])` → eval tier throws `No *load-fn* set` (confirmed by direct bundle probe) |

Prompt fix applied (`tmp/flash-diffgemma/e1_kill_gate.py` CELSIUS_TASK — states
the named-schema convention + "`seon.schema` is already aliased as `schema`; no
`require`/`ns` forms"). Re-drive N=3: **3/3 faithful** (parses, structural, eval,
behavioral all green; 2 turns each).

The lesson generalizes: **every check the scorer makes must be stated in the
context, or it measures prompt-omission, not capability.** This is the third
live confirmation of the owner's "wrong context causes 0; fixed context → ~1.0".

## §3 Contract-omission control

Same task, prompt with NO calling-convention contract ("Write a
`celsius->fahrenheit` function in Clojure"), N=2: both `parses: true` but
`structural/eval/behavioral: false` — a strong AR model CANNOT pass the
behavioral harness it was never told about. Quantifies the E1 context defect on
a competent model: the contract sentence alone is worth 0→~1.0. The GPU E1
re-run MUST keep the contract-stating prompt in all arms.

## §4 Gym path (legacy, kept until inspect parity)

- `bin/acme gym-diffusion pure-mean.edn --eval --assert` → scorecard
  `run=c13e97b4`, eval-tier ON, `ASSERT PASS (EARNS)`.
- `bin/acme gym-diffusion celsius-killgate.edn --assert` → `run=578abc9a`,
  `ASSERT PASS (EARNS)`.
- Hermetic (no pod, no store); cards land in `tmp/acme/`. Owner direction
  (2026-07-02): **standardize on inspect-ai via `/solve`; retire the gym once
  inspect reaches parity** — these runs verify the legacy path still works
  during the transition, nothing more.

## §5 Agent-loop basics (plan → restart → resume + recall)

**PASS.** Root got a 3-item plan task (store `preflight-alpha=42`, store
`preflight-beta=7`, reply with the sum) via `/chat`; `bin/acme restart pod`
mid-task; on the nudge "you were restarted — check your open plan items", the
resumed root (a) queried its durable todos (`todo/list-open` — correctly empty:
it had closed all items pre-restart), (b) re-pulled BOTH stored facts from the
DB by id, (c) replied "the sum remains **49**", (d) closed with its own
`complete` verb. Plan state and schema'd KB facts survive a restart; recall is
DB-backed, not context-echo.

Honest notes: the agent overshot "start item 1 and stop" (finished everything
pre-restart — instruction-following miss, does not weaken the resume proof);
one in-turn stumble recovered: `[:my.kb/id 2063]` lookup-ref errored
(`:my.kb/id` is not `:db.unique` — datahike.db.utils logged it) before a
successful pull. Small manual/context gap: agents reach for lookup-refs on
non-unique attrs.

**Bonus finding (shared-tree race, cost one dead boot):** a `cluster reset`
between tests died on `Could not require my.plan` — the OTHER lane had edited
`src/my/plan.cljs` at 12:36, AFTER the 11:47 acme bundle build; the fresh boot
indexes the CURRENT tree but boots the STALE bundle. Rule for the runbook:
**`bin/acme build` immediately before every `cluster reset` on a shared tree.**

## §6 Provider graceful-down (`:diffusiongemma` with no endpoint)

**PASS on the money question; three small findings.** Flip mechanism: the config
row is seed-once/DB-owned, so the flip was done via `.env.acme` + fresh reset.

- **Configured-but-dead endpoint (`DIFFGEMMA_EP=deadbeefdead`) — the
  GPU-relevant case: PASS, one turn, no churn.** The adapter surfaced the
  graceful error VALUE (`DiffusionGemma submit HTTP 404 … endpoint not found`,
  full detail preserved), the turn closed `error [0 "llm-error"]`, and the loop
  immediately halted: `halt turn :error → close run :error`. A dead RunPod
  endpoint costs exactly ONE turn and a cleanly-closed run.
- **Unconfigured (provider set, no EP var at all) → stub fallback, loop alive**
  (documented behavior) — but the stub "succeeds" every turn, so the run
  legitimately churned to the 20-turn cap (`halt turn-limit reached`) on a
  one-line greeting. Free with the stub; worth knowing the cap is the only brake
  on a "successful garbage" provider.
- **Findings:** (1) boot log says `using diffusiongemma LLM (API key set)`
  while actually serving the STUB — log-truth mismatch; (2) the stub's hint
  text names `DEEPSEEK_API_KEY` regardless of the configured provider;
  (3) `RUNPOD_API_KEY` presence makes the "API key set" claim true-but-misleading
  when the endpoint id is what's missing.
- **Recovery: PASS.** Reverted `.env.acme` (openai-compat/DeepSeek), reset, one
  `/solve` smoke → `reply "recovered"`, 1 turn, `:completed`, 19.6s.

## §7 inspect-ai eval via `/solve` (the go-forward standard)

Setup: the spike's own venv (`docs/prds/agent-fsm/research/inspect-bridge-spike/.venv`,
inspect-ai 0.1.dev1), `SEON_SOLVE_URL=http://127.0.0.1:7980/solve`,
`memory_qa_bench.py@memory_qa_smoke` (3 samples, `--max-samples 1`,
`--model mockllm/model` — never called; the pod owns every turn).

- **Harness: PASS.** All 3 samples drove real multi-turn acme agents
  (1-7 turns, 6-29 evals each), the host-side `includes()` scorer graded them,
  the eval log landed (`logs/2026-07-02T16-10-33…memory-qa-smoke….eval`).
  `/solve` is mounted and working on the fresh acme bundle.
- **Score: 2/3 (accuracy 0.667).** The miss (`orion`) is NOT a wrong answer —
  the completion is EMPTY because the solve hit its 300s timeout
  (`elapsed-ms 301006`, 7 turns, 29 evals; siblings finished in 51-104s).
- **Root cause chased (turn captures):** on the fresh world the agent's
  namespace card rendered "**my.kb — 0 fns, 0 schemas**", so it spent turns 1-3
  discovering `remember` via grep; at turn 6 its recall query returned `#{}`
  ("attribute might not be installed yet") and it ran out of clock
  mid-recovery. Two findings for the agent-fsm lane: (a) fresh-world `my.kb`
  renders empty to agents that are told to use it; (b) the turn-6 empty read
  after successful `remember` calls deserves a scoped look (scratch-child read
  visibility vs attr-install timing). High per-sample variance (51s → timeout)
  says GPU benches need `pass^k` + per-sample timeout headroom, exactly as the
  benchmarks survey recommends.

## GPU-session preconditions (what must be true before a dollar is spent)

1. `python3 verify_fresh.py` → `FRESH ✓` (worker sha) — existing discipline.
2. `assert_oracle_live` golden gate GREEN in the same shell that will score
   (oracle sha/liveness — the NEW discipline; never score without it).
3. The task prompts STATE every scorer demand (calling convention, named
   `-request`/`-response` schemas, no-`require` sandbox rule) — run one $0
   DeepSeek drive through the identical prompt+scorer first; expect ~1.0.
4. Raw generations persist (`e1_samples.jsonl`) so any anomaly is auditable
   after the fact.
5. Fresh-world context verified non-empty for whatever the task needs (the
   `my.kb` empty-render class) — read ONE captured prompt before the batch.
6. Per-sample timeout sized from the DeepSeek variance (51-300s on memory
   tasks): set solve timeouts ≥ 3× the observed median, and use pass^k.
7. Scorers portable to inspect-ai (owner direction): host-side scorer +
   `/solve` door, so the same dataset/scorer pair runs on the GPU model by
   switching the pod's provider row — no gym dependency.
8. `bin/acme build` immediately before every `cluster reset` on the shared
   tree (stale-bundle vs fresh-seed race, §5) — one dead boot costs minutes;
   on a paid session it costs money.
9. Provider flip verified BOTH directions on acme first: dead endpoint = ONE
   turn + closed run (proven §6); recovery smoke green before deploying.

## Pointers

- `tmp/flash-diffgemma/e1_kill_gate.py` — the fixed harness (liveness gate at
  :653, prompt contract fix in CELSIUS_TASK).
- scratchpad `ds_preflight.py` + `ds_preflight_samples.jsonl` — the drive
  driver + raw samples (both prompt variants).
- [[research/e1-behavioral-zero-audit-2026-07-02]] — the audit these drives
  verify the fixes of.
- [[research/inspect-seon-bridge-spike-2026-07-01]] (agent-fsm) — the /solve
  bridge design these drives exercise; the retire-the-gym direction.
