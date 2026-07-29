---
type: research
status: current
tags: [research, agent, runtime, generate-code]
---

# generate-code v0 — the loop, proven and falsified (2026-07-29)

The plan
([../plan/generate-code-v0-plan-2026-07-29.md](../plan/generate-code-v0-plan-2026-07-29.md),
rev 4) claimed a loop: a goal arrives as an ordinary message, the planner's one
turn is the whole-program attempt, every red form is routed to the agent owning
the namespace that form was WRITTEN under, and plan settlement is a derivation
that no agent's claim can override.

**Most of it is now real, and one arm of it is not.** The composition runs on
the local model over facts; the declination settles; the derivation contradicts
a planner that says it is finished. But an owner that REPAIRS its assigned
problem cannot settle anything, because the receipt it was assigned about is
immutable — so `:owner-fixed`, one of the model's three settling states, is
unreachable in production. That is filed as a blocker, not smoothed over.

## Dependency ledger

- Seon at `23192f6a1` (this lane's third commit); gate `bin/test` 478 tests /
  2021 assertions / 0 failures, from a 470/1978/0 baseline at `3a0e51356`.
- Preconditions consumed, not rebuilt: E3 `4a9b9161d` (about-carrying sends and
  the derived `(about, recipient)` assignment identity), E5 `200a447c0` (the
  declination value), E2′ `c7eecfb4f` + `8e57347d2` (owner routing, the
  seven-state settlement derivation, the unbound-var rule, the resume-artifact
  exclusion). The sealed reader is `90338c62a`.
- Provider: Ollama serving `qwen3.5:35b-a3b-coding-nvfp4` at
  `127.0.0.1:11434/v1/chat/completions`, reached with no credential through the
  landed `:seon.config.ai/no-auth` dial (`5f875c780` admitted it to the
  manifest; `seon.ai/targets` projects it).
- SCI `reference-code/sci`, Datahike `reference-code/datahike`, core.async
  flow — all as pinned in `deps.edn`; no coordinate was changed.
- Live drive:
  [scripts/generate-code-v0-drive-2026-07-29.clj](scripts/generate-code-v0-drive-2026-07-29.clj)
  on scratch cluster `generate-code-v0` under `tmp/generate-code-v0/clusters`.
  The owner's `default` cluster was never opened, reset, or stopped.
- Sealed suite: `test/seon/gen/loop_test.clj`.

## What this lane built

Three things, and nothing the plan did not name.

### 1. Plan freeze projects the reader's namespace-in-effect (§2.2, X1)

`:seon.cluster.run.form/ns` was declared and never written, so every red form
routed to its author and the whole owner-routing design was inert. The splitter
now reads through `seon.sci.reader` — retiring the second reader of model text
it used to own — and `seon.cluster.run/plan-call` upserts the `:seon.ns` entity
and points the form at it. It is a projection: the reader decides attribution,
freeze stores it, `work/form-owner` joins it.

Attribution is REPL semantics with the reader's absence rule intact, observed
live in the planner's frozen plan:

```text
ord  ns              source
  0  user            ; I will set up both namespaces.\n(ns my.gen.alpha)
  1  my.gen.alpha    (defn widget-total [n] (* n 3))
  2  my.gen.alpha    (alpha-helper-missing)          ← red, routed to alpha
  3  ABSENT          (ns my.gen.beta)
  4  my.gen.beta     (defn beta-label [] "beta")
  5  my.gen.beta     (beta-helper-missing)           ← red, routed to beta
  6  ABSENT          (my.run/wait "asked the namespace owners")
```

Ordinals 3 and 6 are absent because each follows an arbitrary top-level
invocation, after which the reader refuses to guess. Absence routes to the
author, which is total.

### 2. `my.message/decline` is bound, and discoverable

E5 landed the declination VALUE and stopped there. It was bound into no sci
context, so an owner calling it got `Unable to resolve symbol:
my.message/decline`, and no context block mentioned it, so no owner would have
called it anyway. Both halves are now closed: the var is copied into the base
ctx beside `send`, and `seon.context/assignment-ai` is a derived block present
exactly while an assignment exists, naming the problem identities, the
assigner, and the exact call.

This is the difference between a value existing and a surface existing. The
first live drive is the evidence: with the value unbound and untaught, both
owners tried to FIX their problem and neither could settle it.

### 3. The sealed suite

`test/seon/gen/loop_test.clj` — the composition none of the unit suites reach,
with only the provider's text stubbed. It pins the seven-state settlement over
a REAL turn, the routing join, the declination settling exactly one form, the
plan staying unsettled while a planner claims otherwise, and the two honest
disagreements below.

## The live drive

<!-- DRIVE EVIDENCE -->

## Honest disagreements, reported rather than hidden

1. **Parse and evaluation disagree about the namespace, by construction.** The
   evaluator rebinds `sci/ns` to the agent's own namespace per form, so a form
   attributed `my.gen.alpha` at parse defines
   `#'my.agents.planner/widget-total` at eval. The plan predicted this exactly
   (§2.2) and made E1 the complement that would make it per-form detectable.
   E1 has not landed; the suite pins the disagreement so it cannot be forgotten.
2. **A repaired form is still routed.** See the blocker below.

## Findings filed

| issue | severity | what it blocks |
|---|---|---|
| `an-owner-can-never-fix-a-red-form-into-settlement` | blocker | §2.4's `:owner-fixed`; the plan's ending 1 |
| `an-agent-can-be-assigned-its-own-red-form` | friction | D2's self-delegation refusal rule |
| `boot-cannot-select-a-config-manifest` | friction | pointing any cluster at a provider without `with-redefs` |

The first is the one that matters. `work/form-settlement` derives
`:owner-fixed` from a receipt that is not red plus an assignment — and an
assignment is only ever emitted for a red form, while a receipt is immutable.
So the only reachable settling arm for a red form is a declination. An owner
who fixes the problem leaves the plan unsettled forever; an owner who declines
settles it. The incentive is backwards from the design's intent, and the
landed suite reaches `:owner-fixed` only by planting a receipt shape the loop
cannot commit.

## What v0 does NOT yet do

Unchanged from the plan's §4 deferrals, and worth restating because the drive
makes two of them visible: accepted code is not composed into a corpus, so a
re-triggered planner re-attempts the WHOLE program (a second plan, with the
same forms red again) rather than building on what landed; and an owner's
repair lives in its own namespace, invisible to the planner's next attempt.
Both are N5's, and neither is a defect of this loop.
