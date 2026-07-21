---
type: research
status: active
tags: [research, agent]
---

# Surface-tuning compliance sweep — presentation ablations over the KT2b lint

**Date:** 2026-07-12 · **Question (owner):** what do the PRETRAINED models
comply with when we tune our function PRESENTATION at the translation
layer — names, docstrings, schema shapes, facade tools — without touching
seon source? · **Method:** the exact KT2b methodology (same 169 cases,
same per-case-id seeded 8-tool menus, same scorer, stock needle 26M with
constrained decoding), one variant dimension at a time as card OVERRIDES
applied when the tool JSON is built (`seon_needle.surface_sweep`; zero
context-freeze conflict — no seon source or context generation touched) ·
**Cross-model:** the 6 most interesting variants re-run on
Qwen2.5-Coder-1.5B-Instruct-4bit zero-shot (same menu JSONs through a
JSON-tool-call chat prompt) · **Baseline:** KT2b menu8 = 0.283
([[kt2b-legibility-lint-2026-07-12.md]]); the base arm here reproduces it
byte-for-byte (0.2828, parse 0.9724, F1 0.374, 1 truncation) ·
**Spend:** $0 (all local MLX; needle arms ~10–45 s each, Qwen arms
~30–40 s each, peak RSS 1.07 GB, one model per process).

## TL;DR

- **Docstring line-1 rewrites are the one fix that helps BOTH models**:
  +0.041 needle / +0.048 Qwen headline; the 0.00-tier fix-list fns move
  0/24 → 6/24 (needle) and 7/24 → 14/24 (Qwen). Causal detail:
  `my.plan/next` goes 0/3 → 3/3 on BOTH models from the docstring alone
  (noun-phrase → "Get the next plan steps to work on."). And the current
  implementation-vocabulary docstrings measure **no better than an EMPTY
  description** (doc-none8 +0.007 vs doc-action +0.041) — the rewrite is
  pure gain. This is the evidence for the task #14 source fixes.
- **The biggest needle effect REVERSES on Qwen**: stripping namespaces
  from all tool names is +0.152 for needle (0.283 → 0.434, a broad
  21-fns-up effect) but **−0.055 for Qwen** — the ns prefix is noise to a
  26M assembler and semantic signal to a knowledgeable coder. It also
  raises needle's false-suggestion rate (0.25 → 0.33). NOT a serving
  default; it is a needle-finetune FORMAT question (KT5).
- **Facade tools move needle's 0.00 tier hardest** (16/24; headline
  +0.090) and help Qwen (+0.034) — **but the `stack` of
  translation-layer fixes on the REAL fns matches or beats them on both
  models** (needle 0.386 vs 0.372; Qwen 0.483 vs 0.455). Verdict: real
  facade fns in the toolkit are NOT justified by this evidence — rename
  nothing, wrap nothing; fix docstrings + param projections instead.
- **Schema shape barely moves name selection** (±0.02 for every param
  variant). The only clear negative: a single request-object param
  (−0.028, and argkey accuracy → 0.00) — the KT2b flat-param projection
  is the right one. Projected params for the opaque fns (`query`,
  `entity`, `transact!`) move name-selection not at all ALONE, but are
  load-bearing inside facade/stack for args plausibility (facade argkey
  0.929, the sweep's best).
- **Menus: 8 stays, compact cards are a free win.** Compact cards
  (name + line-1 + required-only params, no param descriptions) at
  8-menu: +0.028, parse 1.0, zero truncations. At 16-menu everything now
  FITS the 1024-token envelope (median card 55 tok, 16 ≈ 880) and
  accuracy is still 0.097 ≈ 1.5× chance — 16-tool discrimination is a
  model limit, not an envelope limit. KT2b's menu16 number (0.131) was
  measured on truncated menus; this is the clean confirmation.
- **The aggregation-ask shape defeats every variant on both models**:
  `seon.db/query`'s cases ("total weight of caches heavier than 10 kg")
  score 0/3 in ALL 16 needle arms and ALL 6 Qwen arms — including as a
  purpose-named `recall(about)` facade. Both models abstain. No card
  rewrite fixes this; it is a missing-capability shape (the `my.kb`
  recall coverage gap KT2b already flagged), and for needle the finetune
  (KT5) is the real test.
- Qwen zero-shot baseline on our surface: **0.421** @8 (vs needle 0.283,
  BFCL-anchor 0.65), parse 1.0, **false-suggestion 0.00 across all six
  arms** (needle: 0.25) — abstention quality is model capacity; the
  serving-side confidence gate for needle remains necessary.

## The ablation table (stock needle, 8-tool menus unless noted)

145 targeted + 24 irrelevance cases; `Δ` vs base; `FIX8` = correct picks
across the 24 cases of the KT2b fix-list fns (`transact!`, `query`,
`register!`, `step!`, `done!`, `next`, `blob/put!`, `db/entity`).

| arm | what changes | name acc | Δ | FIX8 | parse | F1 | args/key | false-sug | trunc |
|---|---|---|---|---|---|---|---|---|---|
| base | KT2b as-is | 0.283 | — | 0/24 | 0.972 | 0.374 | 0.750 | 0.25 | 1 |
| **doc-action** | fix-list docstring rewrites (8 fns) | **0.324** | **+0.041** | **6/24** | 0.966 | 0.413 | 0.857 | 0.25 | 1 |
| doc-none8 | empty description, same 8 fns | 0.290 | +0.007 | 1/24 | 0.979 | 0.393 | 0.818 | 0.25 | 1 |
| doc-none-all | empty description, ALL fns | 0.117 | −0.166 | 1/24 | 0.979 | 0.226 | 0.750 | 0.21 | 0 |
| name-alias | action-alias name-parts, ns kept (8 fns) | 0.317 | +0.034 | 3/24 | 0.979 | 0.434 | 0.733 | 0.25 | 1 |
| **ns-strip** | ALL names lose ns (computed collision rule) | **0.434** | **+0.152** | 4/24 | 0.993 | 0.558 | 0.654 | **0.33** | 1 |
| param-nodesc | param descriptions emptied, all fns | 0.303 | +0.021 | 1/24 | 0.986 | 0.403 | 0.706 | 0.29 | 0 |
| param-engdesc | English param descriptions (8 fns) | 0.303 | +0.021 | 4/24 | 0.972 | 0.415 | 0.812 | 0.25 | 1 |
| param-reqonly | optional params dropped, all fns | 0.303 | +0.021 | 2/24 | 1.000 | 0.392 | 0.846 | 0.29 | 0 |
| param-camel | camelCase param names, all fns | 0.297 | +0.014 | 0/24 | 0.986 | 0.395 | 0.462 | 0.25 | 1 |
| param-reqobj | single request-object param, all fns | 0.255 | −0.028 | 2/24 | 1.000 | 0.390 | 0.000 | 0.17 | 0 |
| schema-project | documented params for `query`/`entity`/`transact!` | 0.283 | ±0.000 | 0/24 | 0.972 | 0.374 | 0.750 | 0.25 | 1 |
| **facade** | purpose-named wrapper cards (8 fns) | **0.372** | **+0.090** | **16/24** | 0.979 | 0.492 | **0.929** | 0.25 | 1 |
| **stack** | project + engdesc + doc-action + name-alias | **0.386** | **+0.103** | 14/24 | 0.979 | 0.486 | 0.842 | 0.25 | 1 |
| menu8-compact | compact cards @ 8 | 0.310 | +0.028 | 2/24 | 1.000 | 0.415 | 0.800 | 0.29 | 0 |
| menu16-compact | compact cards @ 16 | 0.097 | −0.186 | 2/24 | 0.986 | 0.147 | 0.500 | 0.21 | 0 |

Chance: 0.125 @8, 0.0625 @16. Compact card = name + docstring line-1 +
required-only params with type, no param descriptions (median 55 tokens
vs 70 fat, max 99 vs 245).

## Per-fn movement of the 0.00 tier (needle, correct/3)

| fn | base | doc-action | name-alias | schema-project | facade | stack |
|---|---|---|---|---|---|---|
| `seon.db/transact!` | 0 | 0 | 0 | 0 | 1 | 1 |
| `seon.db/query` | 0 | 0 | 0 | 0 | **0** | **0** |
| `seon.schema/register!` | 0 | 0 | 0 | 0 | 1 | 1 |
| `my.plan/step!` | 0 | 1 | 1 | 0 | 3 | 3 |
| `my.plan/done!` | 0 | 1 | 1 | 0 | 2 | 3 |
| `my.plan/next` | 0 | **3** | 0 | 0 | 3 | 3 |
| `my.blob/put!` | 0 | 0 | 0 | 0 | 3 | 2 |
| `seon.db/entity` | 0 | 1 | 1 | 0 | 3 | 1 |

Readings, per dimension:

- **Docstrings carry the discrimination signal.** doc-none-all collapses
  the whole surface to chance (0.117 ≈ 0.125) — needle picks by
  description, not name. And for the fix-list fns the CURRENT docstrings
  are indistinguishable from none (doc-none8 1/24 vs doc-action 6/24):
  "Commit tx-data — forwarded to the JVM writer" contributes literally
  nothing a blank wouldn't. The wins are surgical and causal at n=3:
  `next` 0→3 (question-shaped asks stop abstaining when the line-1 is an
  action), `done!`/`step!`/`entity` +1 each. `transact!`/`query`/
  `register!`/`put!` stay 0 — their failure is not (only) the docstring.
- **The name-part buys little** (+0.034 needle, 3/24; `save-records!`
  did not rescue `transact!` — it still loses to `as-of`'s pull).
  Renaming real fns is the weakest lever measured.
- **Param shape is second-order for selection, first-order for args.**
  All four param variants sit within ±0.03 of base on name acc. But:
  request-object zeroes args/key; camelCase params nearly halve it
  (0.462); dropped descriptions/optionals cost nothing. The
  namespaced-keyword param descriptions (`":my.plan/title"`) the
  translation currently emits are dead weight — param-nodesc ≥ base.
- **Facade cards fix exactly the fns whose card was structurally broken**
  — `put!` 0→3, `entity` 0→3 (opaque/mis-flattened params), `next` 0→3,
  `step!` 0→3 — and their simple purpose-shaped params give the best
  args plausibility of the sweep (0.929). The stack achieves the same
  tier movement (14/24 vs 16/24) with the real fn names.
- Under the stack the surface-wide 0.00 tier shrinks 22 fns → 14; the
  survivors (`query`, `embed/search`, `grep-graph`, `data/group-sum`,
  `fs/read-file`, `shell/run`, `plan/plan!`…) are dominated by
  question/aggregation-shaped asks and sibling-twin confusions — the
  shapes KT2b already attributed to abstention and near-twin cards.

## Cross-model compliance (Qwen2.5-Coder-1.5B-Instruct, zero-shot, same menus)

| arm | needle acc | needle Δ | qwen acc | qwen Δ | qwen FIX8 | transfers? |
|---|---|---|---|---|---|---|
| base | 0.283 | — | 0.421 | — | 7/24 | — |
| doc-action | 0.324 | +0.041 | **0.469** | **+0.048** | **14/24** | **YES** |
| name-alias | 0.317 | +0.034 | 0.421 | ±0.000 | 9/24 | no (nil on qwen) |
| ns-strip | 0.434 | +0.152 | 0.365 | **−0.055** | 7/24 | **REVERSES** |
| facade | 0.372 | +0.090 | 0.455 | +0.034 | 12/24 | yes, weaker |
| stack | 0.386 | +0.103 | **0.483** | **+0.062** | **15/24** | **YES** |

Qwen constants across all six arms: parse 0.99–1.0, false-suggestion
**0.00** (24/24 abstained, every arm), args/key 0.62–0.80. Notable
per-fn: `db/entity` 0/3 → 3/3 under doc-action (the docstring alone
fixes it for the coder too); `register!` 1/3 → 3/3 under facade/stack;
`query` 0/3 in every arm (see below).

What transfers is exactly what design.md predicts: **capability-
vocabulary descriptions and clean param projections help any assembler;
name surgery helps only the model with no world knowledge, and ns
stripping is a pure tiny-model artifact** (shared `seon_db_`/`my_plan_`
prefixes eat needle's discrimination; the same prefixes are semantic
routing signal for Qwen). A serving default tuned on needle alone would
have shipped a change that degrades every other consumer of the tool
JSON.

## The residual: aggregation asks defeat every card (both models)

`db-query` and both paraphrases ("Computed from the database … total
weight of all caches strictly heavier than 10 kg?") score 0/3 in **all
16 needle arms and all 6 Qwen arms** — as the raw card, with the
action docstring ("Ask the database a question: find, count, or sum
stored facts."), with projected `query`/`args` params, renamed
`find-records`, and even as the trivially-fillable facade
`recall(about)`. Both models abstain (`[]`) rather than commit a call.
Attribution: the ask requires composing an aggregation — one hop MORE
than copy-assembly — and abstention is the both-models' response to a
commitment they can't ground. Consequences:

- No docstring/schema/name fix will move `query`'s tier; don't spend
  task #14 budget expecting it to (the docstring fix is still right —
  it just pays on other asks).
- This is the same hole as KT2b's index-reconciliation finding: **no
  general `my.kb` recall entry point exists**. If any facade is ever
  built for real, it is this one — a genuine simpler CONTRACT
  (recall-over-findings), not a relabeled card, because relabeled cards
  measurably do nothing here.
- For needle, whether a finetuned checkpoint can learn query-assembly
  from in-context ingredients is exactly KT5's question — the KT3b
  per-kind table (query .419 @hi-coverage for 1.5B few-shot) says the
  CODER can do it with exemplars; the stock-anything cannot from cards.

## Recommendations (ranked, per the three buckets)

### (a) Source fixes — task #14, owner-gated

1. **Docstring line-1 rewrites for the 0.00 tier** — the measured
   strings from this sweep (both-model gains, and the current line-1s
   measure ≤ empty): `transact!` "Save records to the database —
   persist new facts durably." · `query` "Ask the database a question:
   find, count, or sum stored facts." · `register!` "Define a new field
   so facts using it can be saved and queried." · `step!` "Add a new
   step to the plan." · `done!` "Record that a plan step is finished
   and complete." · `next` "Get the next plan steps to work on." ·
   `put!` "Save a long text durably; read it back page by page later." ·
   `entity` "Fetch one stored record by its id, with all its fields."
   (Wording is the evidence-backed shape; final prose is the owner's.)
2. **Request-schema projections for the opaque trio** — give
   `:seon.db/query-request`, `entity`'s eid-or-lookup-ref arg, and
   `transact!`'s first arity shapes that project to named params
   (selection-neutral alone, but load-bearing for args plausibility:
   they are why facade/stack argkey hit 0.84–0.93). KT2b's "opaque
   single arg" gap notes list the full set.
3. **Do NOT rename fns.** name-alias: +0.034 needle, nil Qwen. The
   churn is not paid for.

### (b) Translation-layer serving defaults — ours to set

1. **Compact cards at 8-tool menus**: name + docstring line-1 +
   required-only params, types only, NO per-param descriptions (+0.028,
   parse 1.0, zero envelope truncations; median 55 tok/card). The
   namespaced-keyword param descriptions currently emitted are dead
   weight — drop them.
2. **Keep flat named params** (never a request-object: −0.028 and
   argkey 0.00) and **keep snake_case** (camel: argkey 0.462).
3. **Keep ns-prefixed tool names** on any surface a frontier/coder
   model reads (ns-strip: −0.055 Qwen, +0.08 false-suggestion needle).
   Bare names are a needle-FINETUNE format question, decided at KT5
   with the finetuned checkpoint, not a serving default.
4. **8 tools per menu stays the slot count** — 16 is a discrimination
   cliff (0.097, ≈1.5× chance) even when compact cards make it fit.
5. Card overrides (the SCHEMA_PROJECT/DOC_ACTION dicts in
   `surface_sweep.py`) can serve as interim translation-layer defaults
   until the source fixes land — they are presentation-only and die the
   day the source is fixed.

### (c) Toolkit facade candidates — owner decision

- **General-purpose facades are NOT justified**: the stack of real-fn
  fixes matches facade movement on needle (0.386 vs 0.372) and beats it
  on Qwen (0.483 vs 0.455) without a parallel surface (the
  don't-be-a-dumbass rule survives contact with the data).
- **The one genuine candidate is the kb recall entry point** — the only
  ask-shape no presentation change touched (0/3 everywhere, both
  models), and independently a coverage gap in KT2b's index
  reconciliation. That is a real missing CONTRACT, not a card problem.
- If it is built, the sweep's `remember`/`recall` results warn: the
  purpose NAME alone does nothing (`recall(about)` still abstained);
  the value must come from a simpler real semantics (question →
  findings) that the caller can commit to.

## Honest caveats

- n=3 per fn (KT2b's case bank, kept intact for comparability) — per-fn
  movements of ±1 are suggestive; the 24-case FIX8 aggregate and the
  145-case headline are the load-bearing numbers.
- One seeded menu draw per case (KT2b's); variant deltas share menus,
  so deltas are paired, but menu-draw variance is unmeasured.
- Facade/stack overrides also change those fns' cards when they appear
  as DISTRACTORS in other cases' menus — observed side-effects were
  small (facade: 4 non-fix-list fns −1 each; stack: 1), and are part of
  what a real deployment would experience anyway.
- The Qwen arm is ONE zero-shot prompt shape (generic JSON-array
  instruction, not Qwen's native hermes tool template) at temp 0;
  absolute Qwen numbers would shift with prompting — the DIRECTION of
  per-variant deltas is the finding.
- `param-engdesc` and `schema-project` overlap on `transact!` (both
  give `tx_data` an English description); their separate readings are
  still one-dimensional for the other seven fns.
- args/key columns ride on few keys (12–26 keys among name-hits), and
  KT2b's `expected_args` only exist for a subset of cases — treat args
  columns as directional.
- This measures ingredient quality on the stock checkpoints — a
  pretrained-compliance map for serving defaults and source fixes, and
  a starting-point ranking for the finetune. It does not predict the
  finetuned ceiling (KT5).

## Provenance / how to re-run

```
cd src-needle
.venv/bin/python -m seon_needle.surface_sweep needle          # 16 arms, ~4 min
.venv/bin/python -m seon_needle.surface_sweep qwen            # 6 arms, ~4 min
.venv/bin/python -m seon_needle.surface_sweep report          # the tables
```

Sweep module: `src-needle/src/seon_needle/surface_sweep.py` (reuses
`lint_probe`'s translation/menus/decoding/scoring; variants are card
transforms only). Raw per-case records:
`src-needle/data/kt2b/surface_sweep_{needle,qwen}.json` (gitignored,
re-derivable). Baseline: [[kt2b-legibility-lint-2026-07-12.md]]; coder
anchors: [[kt3b-coder-models-2026-07-12.md]]; doctrine: design.md §The
context holds the ingredients.
