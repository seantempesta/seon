---
type: research
status: active
tags: [research, agent]
---

# Opus as a live substrate agent — measurement unit (task #19)

HEAD 0da9b47 (+ in-fence gym/harness edits, uncommitted). Driver: gym
`:deepseek` (paid) tier generalized to dispatch on `seon.ai/provider`
(`SEON_AI_PROVIDER=anthropic`, adapter default `claude-opus-4-8`,
adaptive thinking ON, timeout 300s). All runs on scratch `:memory`
worlds via the standard driver pipeline; live pod untouched and
healthy throughout (`bin/seon status` green, `/agents` 200).

## TL;DR

Opus is **mostly not the bottleneck — the harness is**. The model
did the right thing almost everywhere (minted step-todos unprompted,
designed clean schemas in proper data namespaces, wrote a passing
deftest, answered s32 perfectly from rendered context in ONE turn for
$0.26) — and the measurement pipeline lost or punished the result.
Top item, ROOT-CAUSED: **a gym agent running its own proving deftest
terminates the entire outer test suite** (`cljs.test` global env
shared between the agent's in-eval `run-tests` and the harness run →
shadow.test.node `process.exit(0)` mid-scenario) — three todo runs
were silently killed by their own passing tests until an in-fence
exit interposer deferred the exit and produced the first completed
todo scorecards. Also: provider steering wiped by an env-clobbering
unit test, and the s32 consult predicate scores post-#26 optimal
behavior (answer straight from the now-salient prompt) as RED. **#26
salience fix CONFIRMED on paid Opus: `:seeded-claim-rendered-in-prompt`
true (0/5 on the deepseek post-Wave-B cards, all pre-fix).** The one
honest BEHAVIORAL red: todo-teaching adherence is ~50% on Opus (run
1c minted 4 todos and completed them; run 3 minted zero with the
identical rendered teaching).

## Spend (cumulative, from per-call usage blocks; $5/M in, $25/M out)

| run | calls | input tok | output tok (thinking) | est. cost |
|---|---|---|---|---|
| todo 1c (8 turns, killed) | 8 | 428,033 | 15,933 (≥10k think t1) | $2.54 |
| todo 1d (1 turn, killed) | 1 | 49,503 | 13,051 (10,005 think) | $0.58 |
| todo run2 (3 calls, killed) | 3 | 155,140 | 23,599 (21,478 think) | $1.37 |
| s32 run1 (COMPLETE, card) | 1 | 50,487 | 284 (47 think) | $0.26 |
| todo run3 (COMPLETE, card) | 5 | 276,382 | 34,461 (25,913 think) | $2.24 |
| todo run4 (COMPLETE, card) | 6 | 337,210 | 19,761 (15,162 think) | $2.18 |
| s12 run (COMPLETE, card; A hit turn cap) | 29 | 1,614,229 | 14,689 | $8.44 |
| **unit total** | | | | **≈$17.61 — $2.61 over the $15 unit budget (see addendum 3); under the $20 evening cap** |

(The very first attempt accidentally drove DeepSeek — see limitation
2 — 4 calls, ~148k DeepSeek tokens, pennies, not Anthropic spend.)

## Per-run record

### Run "todo 1c" — :todo-multistep-tracking, Opus, 8 turns, HARNESS-KILLED

- No scorecard (process died mid-turn-8; predicates never ran).
- Behavioral evidence from log + prompt blobs
  (`logs/prompts/RjQ-2606112206/`, 8 blobs):
  - Minted **4** owner-scoped todos unprompted (message never says
    "todo"): "design the waterings schema", "log fern + monstera
    watered today", "write watered-on fn (lists plants watered on a
    date)", "write + run a test proving it works" — exactly the
    standing teaching's mint-one-per-step bar (≥2 required).
  - Completed as it went: eval source ";; Schema done — close that
    todo. (seon.agent.todo/complete! …)".
  - Registered `:my.plants.watering/{id,plant,date,at}` — a proper
    user-data domain (cf. DeepSeek below).
  - `my.plants/watered-on-test` deftest WROTE AND PASSED
    (`:seon.db/origin :test-run` row at 02:10:12).
  - Killed at turn 8 before reply/loop-close. Exit 0, no error, no
    test summary — see limitation 1.
- Had the process survived, predicates (a) minted>=2, (c)
  domain-attrs>=2, (d) deftest>=1 were already met; (b)
  zero-open-todos and reply were in progress.

### Run "todo 1d" — killed during turn-1 result processing

- One call: 49,503 in / 13,051 out (10,005 thinking). Turn 1 response
  landed; process died while its evals were being recorded.

### Run "todo run2" — killed right after turn-2 open

- 3 calls. Stderr captured a REAL substrate bug Opus exposed
  (limitation 6): registering an entity-shaped schema
  `(seon.schema/register! :my.garden.watering [:map {:seon.db/entity
  true} …])` makes `record-eval!`'s tee tx fail
  (`Malli validation failed for :seon.ns/name … got nil`) — the eval
  row is recovered but the program-graph tee row is DROPPED (resume
  durability loss for that registration).

### Run "s32" — :s32-consult-before-research, Opus, COMPLETE ✓ card

Card: `tmp/card-opus-s32-1.edn`. Prompt blob:
`logs/prompts/YHy-2606112225/isd-2606112225.txt`.

- mechanical pass? **false** — judge-pass? **true (100/100)**
- `:seeded-claim-rendered-in-prompt` **GREEN** — first paid
  confirmation of the #26 finding-salience fix (deepseek post-Wave-B:
  0/5 green, pre-fix).
- `:at-most-one-repo-search` green — actual: **0** searches.
- `:agent-replied-to-the-user`, `:agent-ends-idle`,
  `:terminates-under-cap` green. ONE turn, ONE eval.
- `:first-eval-consults-stored-findings` **RED — and the red is the
  finding**: Opus's first (only) eval was a correct
  `(seon.agent/reply! …)` quoting the exact envelope facts with
  source attribution. Post-#26 the seeded claim IS in the prompt, so
  a store-consult would be redundant re-derivation-from-store. The
  predicate's bar predates the salience fix and now punishes optimal
  behavior. Judge: 100, "exact success and failure maps from the
  ground truth, along with attribution to the source file."
- Cost of the whole scenario: **$0.26**, 284 output tokens, 47
  thinking tokens. Opus didn't deliberate because nothing needed
  deliberating.

### Run "todo run3" — with exit-interposer + durable cards

See addendum at the end of this doc (run was in flight at first
write).

## Harness limitations Opus exposed (ranked)

1. **The agent proving its work kills the referee — ROOT CAUSE
   PINNED.** Three Opus todo runs died mid-flight (turn 8 / turn 1 /
   turn 2), exit 0 or 1, no stack, no summary, buffered stdout
   (scorecards included) dropped. The run-3 exit interposer caught it
   with a stack trace:

   ```
   at process.exit (paid_test interposer)
   at target-fn (shadow/test/node.cljs:12)        ← suite-complete exit
   at eval (eval at cljs$js$js_eval (cljs/js.cljs:126))  ← INSIDE the
   at js/eval (cljs/test.cljs:448)                  agent's BOOTSTRAP eval
   ```

   When a gym agent runs its proving deftest (`cljs.test/run-tests`
   inside the self-hosted `cljs.js` eval), the agent's test run shares
   cljs.test's GLOBAL completion machinery with the OUTER suite — the
   agent's tests finishing fires shadow.test.node's on-complete →
   `js/process.exit(0)` mid-scenario. Explains everything: the todo
   scenario is the FIRST whose agent runs tests mid-run (deaths
   correlate with the agent's test-run evals — run 1c died right
   after `my.plants/watered-on-test` PASSED); s32/s21/s12 never run
   agent tests, so the 2026-06-10 paid sweep ("Ran 312 tests", 9
   cards) survived; exit 0 = the agent's own passing counters; 8GB
   heap/keepalive irrelevant. Side effect: the outer suite's counters
   are corrupted (run 3 reported "Ran 201 tests" for a ~425-test
   suite). In-fence countermeasures in `test/seon/gym/paid_test.cljs`
   (process.exit interposer that DEFERS exit while a paid run is in
   flight + sync-logs the caller stack; `writeFileSync` durable
   cards) — run 3 then completed and produced the first-ever todo
   scorecard (7 exits deferred). REAL fix (out of fence): isolate
   cljs.test env state between the substrate's in-eval test runner
   and the harness suite (e.g. the agent-side `run-tests` must not
   share `cljs.test/*current-env*`/async machinery with the host), or
   run paid scenarios outside cljs.test entirely.

2. **Unit tests clobber operator env → provider steering silently
   lost.** `seon.ai-test`'s env-row tests `aset`/`js-delete`
   `SEON_AI_*` on `process.env` and delete the OPERATOR's values in
   their `finally` (they restore nothing). The suite runs them before
   the gym, so `SEON_AI_PROVIDER=anthropic` was wiped and the first
   "Opus" run silently drove DeepSeek (caught only by the new usage
   telemetry naming the provider). General in-fence fix: paid_test
   snapshots `SEON_AI_*` at bundle load and re-asserts before every
   paid drive. The proper fix (out of fence): save/restore semantics
   in `test/seon/ai_test.cljs` itself.

3. **The s32 consult predicate punishes post-#26 optimal behavior.**
   `:first-eval-consults-stored-findings` requires a store-read first
   eval; after the salience fix renders the finding into the prompt,
   the correct behavior is to answer directly (Opus did, 100/100
   judge, $0.26, zero searches). "Behaved right, scored red." The
   predicate needs a post-#26 re-cut: e.g. pass when EITHER the first
   eval consults OR the seeded claim was rendered AND zero repo
   searches happened (the no-re-derivation intent, not the mechanical
   store-read).

4. **No prompt caching on the Anthropic adapter — the dominant cost
   driver.** Every turn re-bills the full ~50k-token prompt at $5/M
   (`cache_read_input_tokens 0` on every call); the todo run's 8
   turns cost $2.14 in input alone. DeepSeek's wire auto-caches
   (24-31k cached tokens/turn observed on the accidental run).
   Anthropic requires explicit `cache_control` breakpoints
   (src/seon/ai/anthropic.cljs sends none — src fix, out of fence).
   With a stable prefix (system + namespaces sections) most of the
   50k would cache at 10% read cost; estimated ≥4x cost cut on
   multi-turn runs.

5. **~110k-char context per turn meets a paid frontier model.** The
   gym world renders ~109-123k chars (~50k Opus tokens) EVERY turn —
   `:namespaces` alone is ~97.7k chars of the telemetry profile.
   DeepSeek hides this behind auto-cache; on Anthropic it is the
   whole bill. Context economy (section budgets, namespaces digest)
   is now a COST feature, not just a quality one.

6. **Entity-schema register! drops its program-graph tee row**
   (`record-eval!` tx fails: `:seon.ns/name … got nil` when an agent
   registers `[:map {:seon.db/entity true} …]` under a fresh
   namespace). Eval row recovered, tee row dropped → that
   registration would not resume after a pod restart. Opus triggered
   it naturally on run2 (`:my.garden.watering`). Smell filed; src fix
   out of fence.

7. **No spend telemetry existed.** Paid sweeps had zero per-call
   token/cost evidence (budget tracking was guesswork). Added
   in-fence: driver `usage-logging` wrapper prints one greppable
   `SEON-GYM LLM-USAGE <provider> {…usage}` line per call. This is
   also what caught limitation 2 (line said "deepseek").

8. **SEON_AI_* env knobs were dead in gym worlds** (world-parity
   gap): a live boot runs `ai/sync!` (env owns the `:seon.ai/config`
   row); the gym never did, so `SEON_AI_TIMEOUT_MS`/`_MODEL`/
   `_THINKING` silently no-opped in scenarios while working on live
   pods. Fixed in-fence: the driver runs `(ai/sync!)` after the world
   seed, mirroring `start-agent!`.

9. **Turn-1 think-tax.** With adaptive thinking, Opus spends 2.3-10k
   thinking tokens on turn 1 (planning over the 110k context) — 10k
   thinking ≈ $0.25/turn-1. Fine per se, but with limitation 4 the
   thinking re-bills nothing cached. (s32's turn 1: 47 thinking
   tokens — thinking scales with task, which is the desired
   behavior.)

10. **Observability noise drowns evidence.** Trace-level datahike
    logging writes 27-49MB per suite run with single LINES of
    multiple MB (whole tx-data inline). Every grep/monitor over the
    logs fights binary-sized lines; the `bin/test-cljs` "Failing
    assertions" footer greps `FAIL|ERROR` and prints megabytes of
    matched tx blobs. Paid-run evidence needs a quiet lane (or
    log-level demotion for gym worlds).

11. **The turn budget is invisible to the agent.** No prompt section
    renders "turn N of M" or "turns remaining" (verified against the
    run-3/4 blobs). s12's agent A burned all 20 turns researching and
    delivered an incomplete reply (judge 40) — it cannot converge on
    a budget it cannot see. A one-line `<turns>` countdown (cf. the
    Anthropic task-budget pattern: the model self-moderates when it
    sees the meter) is a cheap, general affordance.

12. **Salience is verbatim-shaped, related-question-blind.** s12's
    agent B re-derived from the repo (first eval = grep) even though
    A's findings answered a RELATED question on the same store —
    while s32 (question paraphrases the stored row's question field)
    rendered and was answered instantly. The #26 fix covers the
    matched-question case; the related-question case is the next
    blind spot, and it is exactly the demo bar (S-12).

13. **Tier name `:deepseek` now means "paid".** Scenario EDNs carry
    `:seon.gym.scenario/tier :deepseek` while the driver dispatches
    on `seon.ai/provider`. Cosmetic but misleading; rename to `:paid`
    when scenario files are next touched (out of this unit's scope —
    minted-file churn mid-measurement).

## DeepSeek vs Opus — informal deltas (same scenarios)

- **s32**: deepseek post-Wave-B (pre-#26): 5/5 consult-first GREEN
  (it had to read the store — the claim wasn't rendered), salience
  0/5 RED, 1/5 over-searched, judges mostly pass. Opus post-#26:
  salience GREEN, zero searches, judge 100/100, consult-first red
  (see limitation 3). Net: with #26 in, the cheapest correct strategy
  changed and Opus took it immediately.
- **todo**: no completed card on either model (scenario is new;
  every paid attempt died — limitation 1). Partial-run deltas:
  DeepSeek (4 turns) minted 3 todos and filed the watering domain
  under `:my.kb.houseplant/*` — knowledge-base vocabulary for live
  user data (domain-modeling miss). Opus (8 turns) minted 4 todos,
  completed them as it went, and used proper data namespaces
  (`:my.plants.watering/*`, `:my.garden.watering/*` across runs),
  wrote AND ran the proving test. Opus's per-turn output is leaner
  (300-1300 tokens vs DeepSeek's similar) but its thinking tokens
  dominate output cost.
- **Eval discipline**: Opus batches more per eval (turn 2 of 1c ran
  6 evals: schema + register + transact + fn + test in two turns);
  DeepSeek spreads the same work across more turns.

## Hygiene

- Live pod: untouched (scratch `:memory` worlds only; driver swaps
  `seon.db/*conn*` and restores in `finally`). `bin/seon status`
  green (pod/cljs-watch/jvm/wire-server), `/agents` → 200.
- No agents minted on the live store by this unit → nothing to
  `complete!`.
- Scratch worlds: per-driver-pattern (in-memory, dropped with the
  process; minted schema keys removed in the driver's `finally`).
- Files: gym edits in `test/seon/gym/driver.cljs` +
  `test/seon/gym/paid_test.cljs` (uncommitted, in fence); cards in
  `tmp/card-opus-s32-1.edn` (+ run3 durable cards
  `tmp/gym-paid-card-todo-*.edn` if produced); logs
  `tmp/opus-todo-run{1,1b,1c,1d,2,3}.log/.err`,
  `tmp/opus-s32-run1.log/.err`.

## Addendum — run3 (exit interposer): FIRST COMPLETED TODO CARD

Card: `tmp/card-opus-todo-1.edn` (durable copy of
`tmp/gym-paid-card-todo-b463342d-….edn`). 5 LLM calls / 6 turns,
agent `sWS-2606112230`, prompt blobs `logs/prompts/sWS-2606112230/`.
Spend: 276,382 in / 34,461 out (25,913 thinking) ≈ **$2.24**.

- mechanical pass? **false** — ONE red:
  `:minted-todos-for-the-steps` rows=[] (zero todos minted; the
  `:closed-the-loop` green is vacuous by design — its vacuity guard
  is exactly the minted predicate).
- Everything else green: `:designed-a-domain-schema`
  (`:my.garden.watering/{at,id,on,plant}`), `:wrote-a-test-for-the-fn`
  (6/29 evals contain deftest), replied (hops>0), ends idle, 6 turns
  ≪ cap.
- **The red is BEHAVIORAL, not harness**: the todo teaching renders
  in the prompt every turn (system bullet at blob line 76 + the
  `seon.agent.todo` docstring arc) — Opus read it and skipped
  tracking anyway this run, while run 1c (same prompt) minted 4 and
  completed them as it went. Observed todo-teaching adherence on
  Opus: 1 of 2 runs. The teaching's WHEN-bullet does not reliably
  bind even on a frontier model — a context-iteration target, now
  quantified.

## Addendum 2 — run4 (second todo card)

Card: `tmp/card-opus-todo-2.edn`
(`tmp/gym-paid-card-todo-e2054089-….edn`). 6 calls / 7 turns. Spend:
337,210 in / 19,761 out (15,162 thinking) ≈ **$2.18**. Suite finished
CLEAN this run (NODE-EXIT=0, "Ran 452 tests / 2006 assertions / 0
failures"; exit-interposer fired exactly once = the legitimate
suite-end exit) — the agent defined deftests but didn't trigger the
cljs.test collision this run.

- **Same verdict signature as run 3**: pass? false, single red
  `:minted-todos-for-the-steps` rows=[]; all work green — and this
  run the model was even cleaner: a normalized TWO-entity model
  (`:my.garden.plant/{id,name}` + `:my.garden.watering/{at,id,plant}`
  with a ref between them), 7/25 deftest-matching evals, reply, idle,
  7 turns.
- Todo-teaching adherence on Opus across the unit: **1 of 3 runs**
  (1c: 4 todos minted+completed; run3: 0; run4: 0). The behavioral
  red is REAL and reproducible — a quantified context-iteration
  target, not noise.

## Addendum 3 — s12 (two-agent consultation, the demo bar)

Card: `tmp/card-opus-s12-1.edn` (`tmp/gym-paid-card-s12-12125968-…`).
29 calls, **1,614,229 in / 14,689 out ≈ $8.44** — the no-cache cost
driver (limitation 4) at full force: agent A burned the ENTIRE
20-turn cap at ~56k uncached input tokens per turn.

- mechanical pass? false; judge-pass? false (A's reply 40/100; B's
  100/100).
- Agent A: `:a-stored-at-least-two-findings-with-provenance` **GREEN
  (3 rows)** — the predicate deepseek failed on all 4 post-Wave-B
  runs. `:a-searched-the-repo` green (7/32 evals). BUT
  `:a-terminates-under-cap` **RED — A used all 20 turns** and its
  final reply omitted part of the asked walk-through (judge 40:
  "entirely omits the required second step"). Opus researches
  thoroughly and stores well but does not converge on open-ended
  "research and store" asks — a termination/closure teaching gap, now
  on a frontier model.
- Agent B: replied, 10 turns, judge **100/100**. BUT
  `:b-first-eval-consults-stored-findings` **RED**: B's first eval
  was `(seon.agent.search/grep {:pattern "validate-values" …})` — the
  exact run-7/run-8 re-derivation signature, ON OPUS. The
  consult-before-research failure is NOT a weak-model artifact.
  Caveat: B's question is related-but-differently-worded from A's
  stored finding rows, so #26's question-matched salience may not
  have rendered them; the blind spot between "salient when verbatim"
  and "salient when related" is the next context-iteration target.

**Budget honesty:** this run pushed the unit's Anthropic spend to
≈$17.61 — **$2.61 OVER the unit's $15 budget** (still under the $20
evening cap). The s12 estimate missed that A would burn the full
turn cap at ~56k uncached tokens/turn; with prompt caching
(limitation 4) the same run would have cost ~$2. No further paid
calls were made after this run.
