---
type: research
status: draft
tags: [research, agent]
---

# Gym as the context-improvement loop engine — config-aware A/B — 2026-06-28

The gym now runs scenarios under a CHOSEN context config and measures
pass-rate-vs-context systematically, through the REAL `seon.config` seam (no
gym-local profile mechanism). This doc records the 14-scenario context-size
BASELINE, what changed in the driver to make a run config-aware, and the A/B
results (full `:default` vs a lean profile that drops `:live-tile`, keeps
`:namespaces`).

## TL;DR

- **Config-aware via the unified `seon.config` seam.** A run now carries an
  optional `:seon.gym/config` (`{:seon.gym.config/profile :minimal}` and/or
  `{:seon.gym.config/path "…edn"}`). The driver steers `SEON_PROFILE` /
  `SEON_CONFIG` around the run; the EXISTING seed paths already read them —
  `client/boot-seed!` (skills/routes via `resolve-skill-rows`/`resolve-routes`)
  and `agent/create!` → `ctx/seed-default-ctx!` → `config/resolve-loadout` (the
  per-role block loadout). So gym agents seed their `:seon.agent/ctx` FROM the
  named loadout with ZERO duplicated resolution. No config named ⇒ today's full
  context, byte-identical.
- **Free context-SIZE measurement for ALL 14 scenarios** via a new
  `measure-context!` verb (seeds + boots + captures the turn-1 context profile,
  never drives the LLM). Baseline ≈ **17.9k–18.1k ctx tokens** per scenario
  (block set `[:soul :skills-catalog :skill/repl :namespaces :live-tile
  :open-todos (:inventory) :transcript]`); the 6 scenarios with domain fixtures
  carry an extra `:inventory` block.
- **A/B (measured, free):** `:minimal` drops the always-on `:skill/repl` body =
  **−912 tok**; the lean manifest drops `:live-tile` = **−629 tok**. BOTH arms
  keep the load-bearing `:namespaces` block. The delta is small (~3–5%) because
  the big lever — `:namespaces` (~13k) — is correctly NOT dropped by any profile
  (its removal broke memory in the prior live experiment).
- **A/B (live DeepSeek, x3 memory scenario):** lean produced the **SAME verdict
  as full** (both `pass? false` — x3 is a hard pinned-red scenario) at **17,454
  vs 18,083** turn-1 ctx tokens. Dropping `:live-tile` did not change pass-rate
  — no regression — confirming the prior live experiment through the gym's own
  scoring.
- **Core-lane blocker (flag, no src edit):** the config seam adds/removes WHOLE
  blocks; it cannot trim WITHIN a block. So the only safe savings today are
  ~600–900 tok. The 64% win needs Core #42 — RENDER-trim `:namespaces` (keep the
  `register!→transact→query` worked example, drop the prose) — not a loadout
  removal. Until #42, the config-aware gym can measure pass-rate-vs-context but
  the available profiles move only ~5%.

## What changed in the driver (all under `test/seon/gym/`)

UNIFICATION decision: the seeding path (`create!` → `seed-default-ctx!` →
`resolve-loadout`) already routes through `seon.config`. Re-implementing
loadout resolution in the gym would FORK it (a "don't be a dumbass" violation).
Instead the driver steers the two env vars those real seed paths already read.

- `driver.cljs:~389` — new `:seon.gym/config` schema + added to
  `:seon.gym/run-request` (`:seon.gym.config/profile` keyword,
  `:seon.gym.config/path` string).
- `driver.cljs:~1357` — `apply-run-config!` / `env-get` / `env-set!` helpers
  set `SEON_PROFILE`/`SEON_CONFIG` from the config map and return the prior
  values; `run-scenario!` calls `apply-run-config!` in the binding before
  `client/open-agent-conn!` and restores in the `finally` (alongside the
  existing `*conn*`/fs/debug/schema-key restoration).
- `driver.cljs:~1621` — new public `measure-context!` verb +
  `:seon.gym/measure-request`/`-response`/`-total-tokens` schemas: the free
  context-size probe (seed under config → boot agent → land turn-1 message →
  `capture-turn-profile`, no LLM). Reuses `seed-scenario-world!`,
  `ensure-agent!`, `capture-turn-profile`, `apply-run-config!`.
- `measure_test.cljs` (new) — the sweep harness, gated by `SEON_GYM_MEASURE=1`
  (no-op in the normal suite). Prints `SEON-GYM-MEASURE` / `-AB` / `-TABLE`
  lines. Run: `SEON_GYM_MEASURE=1 node out/test/test.js --test=seon.gym.measure-test`.
- `paid_test.cljs:~270` (new `config-ab-memory-paid`, gate key `:ab`) — the LIVE
  A/B: drives x3 under `:default` then the lean manifest, both `allow-paid?`,
  prints `SEON-GYM CONFIG-AB` (both `pass?` + turn-1 token totals).
- `configs/lean-no-live-tile.edn` (new) — a gym-local `seon.config` manifest:
  `:default` loadout `default-load [:repl] removes [:live-tile]`. Pointed at via
  `:seon.gym.config/path`.

### Stale ctx-vocab modernization (section → block; chars → tokens)

The context model was renamed `:seon.ctx/section` → `:seon.agent.ctx/block`,
`:seon.agent/sections` → `:seon.agent/ctx`. Brought the gym to the current
model:

- `:seon.gym.profile/sections` → **`:seon.gym.profile/blocks`**.
- `:seon.gym.profile/section-chars` (reported CHARS — a hard tokens-not-chars
  violation) → **`:seon.gym.profile/block-tokens`**, now in TOKENS via
  `seon.ai.tokens/estimate`. `capture-turn-profile` + the schema + the
  `:seon.gym/turn-profile` map + driver_test's telemetry assertions all updated.
- Prose/comment "section" → "block" across `driver.cljs`, `driver_test.cljs`,
  the scenario `.edn` docs (`todo-multistep`, `s32`, `todo-resume`), and the
  scenarios-draft format reference.
- Unchanged on purpose: `ctx/ctx-sections` + `:seon.render/section-texts` are
  `src/` names (the gym reads them; renaming them is a Core change, out of scope).

Verification: `node out/test/test.js --test=seon.gym.driver-test,seon.gym.paid-test`
→ **Ran 38 tests, 154 assertions, 0 failures, 0 errors** (includes the renamed
telemetry test + the new `config-profile-shapes-the-seeded-context` test).

## BASELINE — 14-scenario context size (free, turn-1 ctx tokens, `:default`)

Measured via `measure-context!` (real seed + `resolve-loadout` path, no spend).
"PASS (free)" = real pass/fail only for stub-active scenarios; paid scenarios
REFUSE without a key (so no free pass/fail), todo scenarios refuse (encoded
intent). Context SIZE is measured for all 14 regardless of tier.

| scenario | tier / status | ctx tokens | free pass? |
|----------|---------------|-----------:|------------|
| s01-stub-pipeline-smoke | stub / active | 17,856 | PASS |
| blank-message-refusal | stub / active | 17,870 | PASS |
| envelope-honesty | stub / active | 17,873 | PASS |
| todo-prompt-thin | paid / todo | 17,894 | refuse (todo) |
| finding-storage-shape | stub / active | 17,903 | PASS |
| todo-resume | stub / todo | 17,909 | refuse (todo) |
| consults-findings-run8 | paid / active | 17,927 | not-run (paid) |
| todo-multistep-tracking | paid / active | 17,940 | not-run (paid) |
| s21-log-workout-existing-schema | paid / active | 18,019 | not-run (paid) |
| err-recovery-unregistered-attr | paid / active | 18,045 | not-run (paid) |
| s32-consult-before-research | paid / active | 18,062 | not-run (paid) |
| x1-subscriptions-total-and-max | paid / active | 18,083 | not-run (paid) |
| x3-expense-reuse-and-category-total | paid / active | 18,083 | not-run (paid) |
| x12-narrow-question-no-over-retrieval | paid / active | 18,115 | not-run (paid) |

Free pass-count: **4/4 stub-active scenarios PASS** (verified in the gym suite,
38/0). The +`:inventory` scenarios (err, s21, s32, x1, x3, x12) measure ~120–250
tok larger — that block renders only when domain fixtures are present. (These
ctx-token totals exclude the ~3.1k system prompt, which rides the system role,
not a ctx block — full prompt ≈ ctx + 3.1k, matching the prior experiment's
~20k.)

## A/B — pass-rate-vs-context

### Free (size only, `measure-context!`)

| scenario | kind | default | minimal | lean (−live-tile) | Δmin | Δlean |
|----------|------|--------:|--------:|------------------:|-----:|------:|
| x3-expense-reuse-and-category-total | memory | 18,083 | 17,171 | 17,454 | 912 | 629 |
| finding-storage-shape | memory | 17,903 | 16,991 | 17,274 | 912 | 629 |
| todo-multistep-tracking | planning | 17,940 | 17,028 | 17,311 | 912 | 629 |

Block-set deltas (identical across all three): `:minimal` drops `:skill/repl`
(the always-on repl skill body ≈ 912 tok); lean drops `:live-tile` (≈ 629 tok).
**Both arms keep `:namespaces`** (the load-bearing storage manual) and the
reactive blocks. The seam is proven: naming a profile/manifest changed the
SEEDED block set + token total, through `resolve-loadout`, with no gym-local
seeding logic.

### Live (DeepSeek, real pass/fail) — x3 memory scenario

`SEON_GYM_PAID=ab SEON_AI_PROVIDER=deepseek` drove x3 under `:default` then the
lean manifest:

```
SEON-GYM CONFIG-AB {:default/pass? false :default/tokens 18083
                    :lean/pass?    false :lean/tokens    17454}
```

- **Lean == full verdict** (both red) at **629 fewer tokens** → dropping
  `:live-tile` did NOT change pass-rate. No regression.
- x3 is a hard pinned cross-session scenario that is RED even at full context
  (the paid harness deliberately does not assert `pass?` — honest reds are the
  data), so this is a "no-regression / outcome-equivalent at lower cost" proof,
  NOT a clean green→green proof. A clean green-preservation proof needs a paid
  scenario that is GREEN at full context; none is currently green in the paid
  roster, so I could not produce one without authoring/greening a scenario.

**Function-preservation evidence** for "does the lean context still pass
memory" therefore rests on (a) lean == full verdict on x3 live (above), and (b)
the prior live experiment
[[docs/prds/agent-fsm/research/minimal-context-experiment-2026-06-28]] (agent B
dropped `:live-tile` and passed the memory task identically to full-context A;
agent C dropped `:namespaces` and BROKE). Both lean profiles here keep
`:namespaces`, the only block whose removal broke function.

## Honest scope notes

- **Ran live:** the x3 config A/B on DeepSeek (2 drives). **Skipped paid:** the
  other 7 paid-active scenarios (would be real spend; their context SIZE is in
  the baseline table, measured free).
- **Free pass/fail** exists only for the 4 stub-active scenarios. Stub scenarios
  script the LLM, so their pass/fail is INDEPENDENT of context — they cannot
  prove context-preservation; only paid drives can.
- **Core-lane blocker (no src edit made):** the `seon.config` seam operates on
  whole blocks (add/remove/upsert by name). It cannot RENDER-trim within a
  block, so it cannot realize the ~13k `:namespaces` saving safely. That is
  Core #42 (trim how `:namespaces` renders — keep the worked `register!`
  example, drop the prose). Until #42 lands, the config-aware gym measures
  pass-rate-vs-context but the available profiles move only ~5% of the context.
- A second, smaller seam gap: `resolve-skill-rows` curation (`:seon.config/skills`
  include/exclude) is wired through `boot-seed!` and IS honored by the gym (same
  env steering), but none of the current profiles exercise it — the lever is
  there, untested by an A/B because dropping a non-loaded skill body changes the
  catalog line, not the agent's loaded context.

## Reproduce

```
# build once
clojure -M:cljs compile test

# free 14-scenario size sweep + free A/B table
SEON_GYM_MEASURE=1 node out/test/test.js --test=seon.gym.measure-test

# gym suite (renames + new config test)
node out/test/test.js --test=seon.gym.driver-test,seon.gym.paid-test

# live config A/B (real spend, DeepSeek)
SEON_GYM_PAID=ab SEON_AI_PROVIDER=deepseek node out/test/test.js --test=seon.gym.paid-test
```
