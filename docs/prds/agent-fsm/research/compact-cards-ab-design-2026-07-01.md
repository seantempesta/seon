---
type: research
status: draft
tags: [research, agent, flow]
---

# Compact namespace cards — A/B validation design (ship gate)

> DESIGN ONLY. This is the ready-to-execute protocol that decides whether
> compact cards ([[compact-namespace-cards-spec]]) SHIP. It fires the instant the
> renderer + presence-set config are wired (`::full-source` on the namespaces
> block — decision 13/16, [[config-driven-agent-init-namespaces-additions-2026-07-01]]).
> Nothing here touches source; the mechanism it tests is not yet live.

## TL;DR

- **Hypothesis:** rendering the long-tail namespaces as COMPACT cards (register!
  block + one-line-docstring fn heads) matches-or-beats full-source on verb
  ADOPTION and task success, while cutting namespaces-block tokens 3–5×.
- **Why it could fail:** the render-prominence LAW — bare signatures drove **0×
  toolkit adoption**. Cards are the bet that the `register!` data model + typed
  `:malli/schema` contract clear the bar where bare signatures starved. This A/B
  is the falsification test for that bet.
- **Kill criterion (revert bar):** cards SHIP only if, over `k` samples per arm,
  Arm B's card-verb adoption is **≥ Arm A's** AND Arm B's pass^k rate is **≥ Arm A
  − 1 scenario** (within pass^k noise). If card adoption drops or pass^k regresses
  beyond noise on ANY scenario, cards do NOT ship — we keep full source and have
  reconfirmed the law cheaply.
- **Primary metric:** distinct verbs the agent CORRECTLY calls that it saw ONLY as
  a compact card (never full source, never a home-ns alias) — the "card-taught
  verb" count. Secondaries: pass^k, eval-error-rate, namespaces-block tokens.

## 1. Hypothesis + kill criterion

**H1 (adoption):** for a verb the agent must discover from context and call
correctly, a compact card is a sufficient teaching surface — card-verb adoption
in Arm B ≥ full-source adoption in Arm A.

**H0 (the law strikes again):** cards are effectively bare signatures with
ceremony; agents fail to discover/call card-only verbs, adoption craters like the
#42 signature-trim did (my.data 0×, eval-error-rate 0.357 RED).

**Kill criterion — the exact bar (all must hold to SHIP):**

1. **Adoption non-regression:** `card-taught-verbs(B) ≥ card-taught-verbs(A_equiv)`
   where `A_equiv` = the same verbs measured under full source. Concretely: the
   set of target verbs (§4) called correctly at least once across the `k` samples
   must not shrink under cards. A single-sample dip inside pass^k variance does not
   count — measure the union over `k`.
2. **Task non-regression:** `pass^k(B) ≥ pass^k(A) − 1 scenario`, per-scenario,
   AND no scenario flips PASS→FAIL beyond pass^k noise (a scenario that is 3/5 in A
   and 0/5 in B is a real regression; 5/5 vs 4/5 is not).
3. **Error non-regression:** `eval-error-rate(B) ≤ eval-error-rate(A) + 0.05`. A
   card that makes agents guess wrong call shapes shows up here.
4. **Token win realized:** `namespaces-block-tokens(B) < namespaces-block-tokens(A)`
   by the predicted 3–5× on carded nses (sanity that the arms actually differ).

If 1–3 hold → **SHIP cards** (keep ≥1 exemplar full, §2). If any of 1–3 fails →
**REVERT to full source**; write the negative result up — it re-proves the
prominence law and closes the "cards vs signatures" question.

## 2. The two arms

Both arms are the SAME cluster config except the `::full-source` presence-set on
the namespaces block. Selection of WHICH nses render at all is unchanged (#42,
config-driven) — this A/B varies ONLY the full-vs-compact DETAIL axis.

- **Arm A — baseline (today):** every long-tail ns in `::full-source`. Real full
  file source: multiline docstrings, full bodies, everything. This is the current
  world.
- **Arm B — cards:** the long-tail nses REMOVED from `::full-source` → they render
  as compact cards (register! block + one-line-doc fn heads, body elided).

**Held full in BOTH arms (controls):**

- The `my.*` exemplars (`#{:my.kb :my.data :my.ui :my.tile}` —
  `canonical-full-my-ns`). They stay full because the prominence law says
  COMPOSITION verbs need their worked example; they are the "how to think in this
  system" anchor, not the thing under test.
- The agent's current/home ns (`::current-full?` default true).

So the ONLY difference A→B is the detail level of the long-tail, simple-call
namespaces — the exact population the card format is designed for. `my.*`
composition verbs are deliberately NOT in the experiment; carding them is already
known-bad (the law).

**Wiring assumption:** arms are selected purely by the presence-set config —
`(seon.agent.ctx/... ::full-source <ns-vector>)` differs between two `SEON_CONFIG`
manifests (or one manifest + a `#profile` variant). No code edit switches arms;
config is runtime EDN → restart, no rebuild.

## 3. The metric

Reuse the existing scorecard axes — do NOT reinvent
([[../CLAUDE.md]] Runbook; `test/seon/gym/scorecard.cljs`):

**Primary — card-taught-verb count (new derived axis, computed from the
transcript, not a stored flag).** For each arm, the set of distinct public verbs
the agent CALLED correctly (the eval parsed, ran, no `:seon/error`) whose ONLY
context appearance in that arm was a compact card — i.e. the verb's ns is NOT in
`::full-source`, and the call is fully-qualified (not a home-ns alias, which would
confound with the always-on aliases — the alias-blind guard, §7). This is the
direct measure of "did the card teach a verb the agent then used?" It is a
per-arm SET; the kill criterion compares the sets' cardinality over `k` samples.
It extends the existing `:seon.gym.battery/toolkit-calls` idea (summed `my.*`
references) but scoped to card-only, non-exemplar verbs and gated on CORRECT
execution — reuse the same alias-tolerant call-extraction the `toolkit-calls`
axis already uses (`test/seon/gym/scorecard.cljs`).

**Secondary (all already registered — cite, don't rebuild):**

- `:seon.gym.battery/pass-k` — per-scenario pass^k over `k` samples (the honest
  task-success judge; noise-robust).
- `:seon.gym.battery/eval-error-rate` — mean whole-run eval-error-rate. A card
  that makes agents guess wrong call shapes spikes this.
- `:seon.gym.battery/block-tokens` — the per-block turn-1 context tokens; confirms
  the 3–5× namespaces-block shrink actually landed (the whole point — coverage per
  token). **Tokens never chars** (`seon.ai.tokens/estimate`).
- `:seon.gym.battery/toolkit-calls` — kept as a guard that Arm B did NOT
  accidentally suppress the FULL `my.*` exemplar usage (it should be flat A↔B;
  those nses are full in both).

## 4. Scenarios — verb-discovery-from-context

The card format is only tested when the agent must FIND a verb it has not been
handed and call it correctly. The battery axis that matters is
**toolkit-adoption + planning + DB-memory** with a verb the agent must locate in a
compact card. From the existing 23-scenario battery (`test/seon/gym/scenarios/`),
the load-bearing ones:

- **`todo-multistep-tracking`** — a 3-step data task that never mentions todos;
  the agent must discover + call `seon.agent.todo/add!` / `complete!`. `todo` is a
  prime carding candidate (simple-call verb, #74). Directly tests "did the card
  teach the todo verbs?"
- **`plan-resume-across-restart`** — long-planning continuity; exercises the todo
  verbs across a restart from context alone.
- **`database-memory-drive` / `finding-storage-shape`** — store-then-retrieve;
  the agent must find the `my.kb`/schema/db verbs. NB `my.kb` is full in BOTH arms
  (exemplar), so this scenario partly controls for the exemplar path; its
  card-sensitive part is any long-tail db/query verb it reaches for.
- **`x1-subscriptions-total-and-max` / `x3-expense-reuse-and-category-total`** —
  reuse-and-aggregate: the agent must re-find an existing schema + the aggregation
  verbs it saw only as cards.

**New scenario NEEDED — `card-only-verb-discovery`.** None of the above isolates a
verb whose ONLY teaching is a compact card AND that is NOT an exemplar and NOT a
home-ns alias. Author one scenario: a task that can only be completed by correctly
calling a specific long-tail verb (e.g. a `seon.derive/*` or `seon.agent.ctx/*`
query verb) that renders as a card in Arm B and full in Arm A, with a STRUCTURAL
predicate (the call landed + produced the right datom/return), never a title
match. This is the cleanest single discriminator for H1 vs H0. Follow the
`todo-multistep-tracking` predicate style (structural, alias-tolerant, no plant).

## 5. Sample size + noise (pass^k)

Single-sample drives are NOISE — weak-model variance flips a scenario run-to-run
(the `canvas-goal-board` miss was model variance). So:

- **k = 5 samples per arm per scenario.** pass^k with k=5 distinguishes a real
  regression (e.g. 3/5→0/5) from variance (5/5 vs 4/5). This is the
  `--paid --k=5` mode of `bin/gym-scorecard`.
- **Adoption is a UNION over the k samples** — a card-taught verb counts if the
  agent called it correctly in ANY of the 5 runs (discovery is the capability;
  we're asking "can the card teach it", not "does it every single run").
- **Task success is pass^k** — the per-scenario pass rate over the 5, compared A↔B
  with the ±1-scenario / no-flip-beyond-noise bar from §1.
- Same `k`, same scenario set, same seeds/config for both arms — the ONLY delta is
  the presence-set. Run A and B interleaved (not all-A-then-all-B) to avoid
  provider drift confounding the arms.

Budget note: 2 arms × (5 discriminator scenarios + the new one) × k=5 ≈ 60 paid
drives. DeepSeek tier (cheap, pre-authorized). Run on acme, never the shared
default pod.

## 6. Execution steps (fires when the renderer is wired)

Copy-pasteable, against the real runbook. Runs on the ISOLATED acme cluster
(pod 7980) — NEVER `bin/seon` the live default pod ([[../../../seon/components/acme-harness.md]]).

```bash
# 0. Prereqs: renderer + ::full-source presence-set wired; doc-lint green on the
#    carded nses (Arm B cards must not soft-clip mid-sentence — confound §7).
export SEON_AI_PROVIDER=deepseek DEEPSEEK_API_KEY=…   # cheap tier

# 1. Two config manifests differing ONLY in the ::full-source vector on the
#    namespaces block (config is runtime EDN → no rebuild):
#      config/gym-armA-fullsource.edn  -> long-tail nses IN  ::full-source
#      config/gym-armB-cards.edn       -> long-tail nses OUT (render as cards)
#    my.* exemplars + current-ns stay full in BOTH.

# 2. FREE scorecard sanity per arm — confirms the arms actually differ on
#    block-tokens before spending on paid drives (the token win, §3):
bin/gym-scorecard   # inspect :seon.gym.battery/block-tokens per profile
#   (the gym steers SEON_CONFIG through the real seon.config seam via
#    :seon.gym/config {:seon.gym.config/path "config/gym-armB-cards.edn"})

# 3. Paid A/B — k=5, interleaved arms, the discriminator scenarios (§4):
#    Arm A:
SEON_CONFIG=config/gym-armA-fullsource.edn bin/gym-scorecard --paid --k=5
#    Arm B:
SEON_CONFIG=config/gym-armB-cards.edn      bin/gym-scorecard --paid --k=5

# 4. (Optional, for a live-observed drive of the new discriminator scenario)
#    acme, isolated pod 7980 — drive DeepSeek + a dedicated observer:
bin/acme build && bin/acme up
#    mint a child, give the un-coached card-only-verb task, observe via
#    HTTP 7980 + wire REPL 7981 (NOT mcp__seon_cljs__* — those see the live
#    :client build, not :acme-client).
```

Compare the two scorecard runs on the axes in §3; apply the §1 kill criterion.

## 7. Confounds to control (measure the RIGHT thing)

The FREE `total-tokens` axis MISSED the #42 adoption regression (confounded by
scenario count + non-namespaces blocks). Guard against the same class:

- **Token-count difference is the independent variable, not a confound to
  minimize.** Report `block-tokens` (the namespaces-block slice), NOT
  `total-tokens` — the latter is confounded by scenario count and other blocks.
  Both arms run the identical scenario set so scenario count cancels.
- **Alias-blind guard.** A card-taught-verb must be a FULL-qualified call whose ns
  is NOT in `::full-source`. Home-ns aliases (`db/`/`todo/`/`message/`) are
  always-on regardless of arm (#73) — counting an aliased call as "card-taught"
  would false-positive B. The extraction must be alias-tolerant (the
  `[my.tile :as tile]` false-negative lesson) AND alias-EXCLUDING for the
  card-only metric.
- **Exemplars held full in both arms** — `my.*` and current-ns identical A↔B, so
  the delta is purely the long-tail detail level. `toolkit-calls` should be flat
  A↔B; if it moves, the arms leaked.
- **Doc-lint green on the carded nses BEFORE the run.** A card that soft-clips a
  mid-sentence docstring at 78 chars reads broken (the 81%-wrap finding) and would
  handicap Arm B for a reason unrelated to the card FORMAT. Only card nses whose
  line-1 docstrings pass `seon.dev.docstring`; else you're testing bad docstrings,
  not cards.
- **Same provider, same seeds, interleaved arms** — provider/model drift across a
  long run would otherwise map onto whichever arm ran second.
- **Structural predicates only** — the new scenario asserts the call landed +
  produced the right datom/return, never a title/plant match (gym integrity;
  agents never see the harness).
- **pass^k, not single runs** — no pass or regression is believed on one sample
  (§5).

## Settled inputs (from the spec — do not re-litigate here)

- Compact = attribute-PRESENCE (`::full-source` membership), not a `:map-of` or a
  `:compact` enum token. Absence = card.
- Cards keep the `register!` block verbatim + typed `:malli/schema` heads; NO
  examples (cache-stability). The card is static.
- ≥1 exemplar (`my.kb`) stays full regardless of outcome.
