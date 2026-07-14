---
type: prd
status: active
tags: [prd, agent]
---

# REPL autosuggest — roadmap (we-are-here)

Design: [[design.md]] · Review: [[research/design-review-2026-07-12.md]].
Started 2026-07-12; scope = general REPL autocomplete (owner), v0 model
contract = copy-heavy form kinds (plan/transact/register).

## WE ARE HERE (2026-07-13) — correctness push, model work PAUSED

Model work is paused behind a "garbage in, garbage out" correctness
push. **[[root-cause-fixes-2026-07-13]] is the restart anchor** — read
it first. Summary: our instruments lied (eval false-ok taints mined
data; 27% of generated pairs fail at eval; the runtime refactor
stabilized and must be adopted). 5-tier plan: adopt refactored runtime and
rebuild acme → fix eval boundary → clean-data pipeline (stage→render→
eval→keep-clean) → runtime hygiene → measurement instruments (display v3 and
fair scorer). **Next-session focus = DATA QUALITY** (owner): smarter
models generate proper trajectories; investigate LLM judges for
goal-achievement, calibrated against the benchmark oracles. The dated
sections below are the prior arc's evidence trail.

## Built

- `reference-code/needle` vendored (submodule); source fully read.
- **B1 SHIPPED** (`5481ab36`, `src-needle/`): MLX port, parity 20/20
  greedy-exact vs JAX; prefill ~128k tok/s @1024 (8ms), decode 428
  tok/s single-stream → ~0.25s per full suggestion; Clojure 2.45
  chars/token (1.82× English/JSON), envelope tight-but-workable;
  overfit smoke green (f32 master weights). Findings: pretrained
  contrastive head is all zeros (retrieval must be trained or stay
  deterministic); constrained decoding is load-bearing.
- **KT0 census FIRED**: ~224 ok-eval turns total (all acme; default
  history wiped). Data recipe inverted — synthetic/gold primary, mined
  turns = held-out eval only.
- Adversarial design review (9 agents): right-track-with-changes; all
  11 changes folded into design.md.
- Reused, not rebuilt: `my.plan/reconcile!` round-trip, turn capture
  (`prompt-blob`/`reply-blob`/`rendered-as-of`), per-form eval rows,
  program-graph cards, the menu candidate derivation.

## In flight

- Vocabulary cleanup: "verbs"-named surfaces → functions names;
  deprecated `:relevant-source` deletion.

## A1 — SHIPPED (`af67b188`, 2026-07-12)

- **`seon.repl.autocomplete`**: `context` (the byte-exact encoder input —
  `seon.agent.ctx/render-context` + the `:autocomplete` profile),
  `rate!` + `::rating`/`::tag` curation datoms on turns, `export!`
  (JSONL to `data/tune/`, per-row ingredients coverage, built-in
  double-render determinism self-check, token summaries).
- **Profile mechanism** (selection + caps ONLY — frozen-context
  compliant): `:seon.agent.ctx/profile` on `render-context`/`context-root`
  (absent ⇒ byte-parity); per-block `:seon.agent.ctx/token-cap`/`cap-keep`;
  transcript `::readline?`/`::result-handles?` dials (default true);
  profiles config→DB via `:seon.config/context-profiles` (as-of-versioned;
  seeded in `config/system.edn` — keep in sync with the code default).
- **Live proof (acme store)**: 214 rows / 262 turns walked / 17 agents;
  **0 determinism mismatches** over 214 double-renders; contexts 206–678
  tokens (all under the 700 budget); targets p50 34 tokens (7 rows over
  the 512 decoder budget); 0/214 handle or readline leaks; coverage
  mean .64 (40 rows < .25 — context-gap evidence). Form kinds
  (overlapping): my.plan 111 · db/query 57 · in-ns/ns/require 53 ·
  register! 24 · defn 15 · transact! 10 · other 34. File:
  `data/tune/acme-2026-07-12.jsonl` — this IS the held-out
  real-distribution eval set (KT1/KT3 unblocked).

## KT2b — RUN (2026-07-12)

Legibility lint of the 168-fn agent surface through the STOCK checkpoint
via needle-home JSON translation
([[research/kt2b-legibility-lint-2026-07-12.md]] — leaderboard v0 +
attributions + the benchmark sampling). Headline: BFCL anchor 0.65 @8-tool
menus; ours **0.283 @8** (chance .125 — the weak band: informs, doesn't
kill); menu-of-1 parse 1.0; per-key arg copy 0.73 (copy machinery fine);
irrelevance false-suggestion rate 0.25; 16-tool menus overflow the 1024
envelope (165/169) — compact cards are mandatory. Worst tier
(`transact!`/`query`/`register!`/`step!`/`done!`/`next` at 0.00)
attributes to implementation-vocabulary docstring line-1s and
non-projecting opaque request schemas; fix list in the report,
owner-gated (context frozen). Probe: `src-needle/…/lint_probe.py`,
`cases/kt2b_cases.json`, `scripts/dump_fn_index.clj` (`ccf6abba`).

## KT3b — RUN (2026-07-12)

Local coder-model matrix on the same 214 rows/scorer
([[research/kt3b-coder-models-2026-07-12.md]], `289032ca`).
**Qwen2.5-Coder-1.5B-Instruct + 3 exemplars TIES the frontier**: .265
vs DeepSeek .261 (@cov≥.75 .383 vs .366; next-form .319 vs .307) at
0.92s/$0 — this instantiates the B3 $0-baseline ship gate;
needle-after-training must beat it while keeping its edges (0.25s,
parse 1.0, abstention, offer channel). Base-continuation FAILS stock
(.083-.121, display-grammar mimicry) — finetune required for that
framing. Coverage gradient reproduces in EVERY arm — third
confirmation: the projection binds, not the vehicle; context-gap
fixes are the highest-leverage move. Qwen3.5-0.8B verified real
(2026-03, GDN, 262k ctx, no FIM): .110 mid-pack. Clojure-pretraining
verification table in the report (Qwen2.5-Coder/DeepSeek-Coder/Stack
v1+v2/Granite = YES). Per-kind: 1.5B query .419 / plan .372 above
frontier; register .100 regresses; defn ~0 (exclusion re-confirmed).

## KT3-redux — HALTED mid-matrix (2026-07-12, owner stop order)

The fair ceiling test, owner-corrected (full 168-fn index in every arm,
in-document exemplars for base models, scoring v2 = set-union bundle F1 with
full decomposition/confusion/id lenses)
([[research/kt3-redux-full-index-2026-07-12.md]], status: draft).
**HALTED: the v2 display is defective** (cards spec-less — `:malli/schema`
stripped by KT1 compaction; stale deleted-fn cards; glyph tax) — re-run
pending the v3 export (spec-bearing cards, ASCII, stale filter).
Completed before the halt (defective-display baselines): DeepSeek 5 arms
incl. a 4-layout NIAH sweep + a rowcards control (full index ≤ curated
4 cards on F1 for every layout: .177-.202 vs .252; but register .85
@cov≥.75 — best KT3-series number), Muse first-class full run (.176),
1.5B-instruct (few-shot COLLAPSES under the index .270→.071 —
exemplar-parroting + temp-0 loops; zero-shot flat), 1.5B base
cont-bare→cont-few .067→.112 (the owner's exemplar fix works on base
models). Hallucinated fn heads ≤3% with the index; failure mass =
selection + missing emission; spurious ids ~97% grounded-but-wrong (the
validate-against-live-db gate stands). Qwen3.5-2B VERIFIED (2B dense
GDN-hybrid, 262k ctx, unified VL) + both variants converted to MLX
4-bit, ready. DeepSeek thinking arm mechanics verified (reasoning
traces saved in full — owner's data-generation recipe candidate). Tools
kept: extended `kt3_score.clj` (legacy mode byte-stable),
`scripts/kt3_redux.py` (verify/api/local/report, layouts, thinking,
rowcards controls), `src-needle/data/kt3redux/STATUS.md` (arm
inventory).

## Scorer false-negative audit — RUN (2026-07-12)

Adversarial audit of the useful-match frame itself
([[research/scorer-false-negative-audit-2026-07-12.md]]): 40 zero-scored
predictions, two blind-then-target LLM judges + adjudication, scorer
self-test 214/214 = 1.0 (PASS). **Frontier zeros are 40% reasonable
alternatives (8/20, Wilson .22–.61) — every one a `my.plan`-family call
on context-visible ids, 6/8 the block's own prescribed `active!` on the
next-ready id; small-model zeros are 0% (0/20) — real errors** (verbatim
repeats 14/32, invented fns, exemplar-leaked ids, template copies).
Corrected DeepSeek ceiling ≈ .36–.46 (vs measured .261) — KT3's
below-band STOP arithmetic softens to at-or-above the band floor; the
KT3b "1.5B ties the frontier" headline is a scorer artifact (corrected,
the frontier leads ~.10–.20; the $0 B3 bar itself is unchanged).
Low-coverage ".105 = noise" is partly artifact (3/4 judged low-cov
frontier zeros were the prescribed act). 3/214 junk targets found (31,
184 pure prose-as-call). Recommendation: NO LLM judge inside the metric;
add a mechanical prescribed-act accept-set (plan-block state-guarded —
signature alone is only .47 precise) as a separate reported column, drop
the junk targets, and calibrate any future band-gated verdict with a
~$0.50 judge pass. Script: `src-needle/scripts/fn_audit.py`.

## Extended-context prep — RUN (2026-07-12)

Prep unit for the 2048-vs-4096 extension decision
([[research/extended-context-prep-2026-07-12.md]] — fit/card-budget
tables + smoke evidence). **v2 dataset built**
(`data/tune/acme-2026-07-12-v2.jsonl`, 213 rows + provenance sidecar):
compact cards (KT1 compaction), next-form targets (byte-exact edamame
split, bundle kept as an ablation column), junk filtered (prose-form
rule; 1 row + 2 forms dropped), and JSON-NATIVE columns per owner ruling
— `json_tools` + `json_target` via KT2b's translation layer (full 47.4%
/ partial 20.2% / none 32.4%, the ns-move/def-form fallout as expected;
those rows are the coder arm's food). **Fit (real tokenizer): 2048 holds
100% of rows** in both slot encodings (1024: 7-10%); card budget median
**+14 compact cards @2048, +55 @4096** (measured median card 50 tok;
rows carry 4 today). **Extension scaffold SHIPPED**
(`seon_needle.extend` + `enc_rope_scale` position-interpolation in
`model.py`, default byte-parity — suite 11/11 incl. JAX parity): 2048
overfit smoke green in BOTH arms (Clojure and JSON-native) on real
>1024-token rows — loss 5.46→0.006, **10/10 token-exact**, peak 3.3-3.6
GB, single process. **Recommendation: 2048** (numbers in the research
file); 4096 re-runs the same scaffold at scale 4 if card-budget evidence
later demands it (needs attention-memory surgery to train gently).

## Toolkit contract gaps — SHIPPED (2026-07-12)

The two measured gaps from KT2b §Index-reconciliation + the sweep's
facade verdict, built as ordinary tested toolkit code (NOT a
context-generation change; docstrings written to the sweep's winning
action style):

- **`my.kb/recall`** — the symmetric ask to `remember`:
  `(my.kb/recall {:my.kb/about "…" :my.kb/limit 10})` → the
  `:seon.result/ok?` + `:seon.items/*` envelope of ranked stored facts
  (full pulled rows + derived `::match`/`::matched-tokens` labels,
  honest `::matched` total). Deterministic whole-token match over every
  `my.kb*` string attr; `SEON_EMBED` tops up unfilled slots via
  `seon.embed/search-pull` (reused, not forked). Evidence: the
  aggregation-ask shape 0/3 in ALL 22 sweep arms on both models — a
  missing CONTRACT, not presentation.
- **`my.ns/functions`** — the fn-listing gap:
  `(my.ns/functions {:my.ns/ns 'my.plan})` → the compact one-line cards
  through the ONE card mechanism
  (`seon.agent.ctx.namespaces/compact-fn-head`), private fns excluded,
  errors-as-values for unknown namespaces. New ns `src/my/ns.cljs`,
  required into the boot build.

Tests: `test/my/kb_test.cljs` (+3 recall deftests) +
`test/my/ns_test.cljs` (4 deftests), hermetic :memory conns. **Next
sweep re-run must include the NEW cards** so the 0/3 aggregation-ask
shape is re-measured against a real contract (and `my.ns/functions`
enters the leaderboard) — the dump script picks both up automatically
(`my.*` surface rule).

## Tool-surface overhaul — SHIPPED + re-lint-proven (2026-07-12)

The owner-cleared source fixes from the KT2b lint + surface sweep
([[research/tool-surface-overhaul-2026-07-12.md]]): ~50 docstring
line-1s rewritten to glyph-free capability sentences (the 8
sweep-measured FIX8 texts first), 5 schemas sharpened
(`:seon.db/query-form` / `entity-ref` / `pull-pattern` / `thunk` /
`time-point`) + `:my.plan/root?`→`:my.plan/root`, ZERO fn renames (the
sweep's no-rename verdict held; candidates deferred with evidence).
Re-lint on the LIVE re-dumped surface: **needle 0.283 → 0.372 (+0.090,
above the doc-action override's prediction, ≈ the full stack), 0.00
tier 21 → 14 fns, false-sug 0.25 → 0.208; Qwen FIX8 7/24 → 13/24**;
baselines reproduced byte-for-byte on a near-baseline control index.
Suite green in the isolated pin worktree (1236/5649/0/0). Residuals as
predicted: `db/query` aggregation-asks abstain everywhere (the `recall`
contract lane owns it); `transact!`/`register!`/`put!` stay 0/3 needle
(not-a-docstring failures). Commits `2de88e0f`/`608b2331`/`6b75705c`/
`f6cd9761`.

## Ordered path (spend-gated; thresholds in design.md §Measurement)

1. **KT1 tokenizer envelope — FIRED 2026-07-12**
   ([[research/kt1-tokenizer-envelope-2026-07-12.md]]): 95.3% of full
   encoder inputs >1024 (kill >25%); targets PASS (median 76 tok,
   byte-fallback 2.6%). Diagnosis: envelope-FIT, not tokenizer — the
   profile caps were denominated in `chars/4` (worth 1.86× needle
   tokens; "678 est" ≈ 1,265 real) + 15.2% context byte-fallback from
   render glyphs (~110-165 tok/row) + fat cards (−151 median when
   compacted). **OWNER DECISION (2026-07-12): extend the context** —
   B2 finetunes at 2048/4096 via position-interpolation RoPE (legal:
   tied embeddings forbid retokenizing, not longer contexts; ~26M
   makes it cheap — 4096 prefill ≈ 130ms). The extra window = CARD
   BUDGET (40-60 compact cards — "surface better data"). Contexts
   stay as rendered (no re-cap squeeze, glyph dial moot for now).
   **Prep unit RUN** — see "Extended-context prep" below;
   recommendation 2048. KT3 anchor stands: coverage binds harder
   than window size — context-gap fixes travel WITH extension.
2. **KT2 zero-shot copy fidelity** — stock checkpoint, hours (KT2b's
   0.73 per-key copy accuracy on ids/strings is favorable adjacent
   evidence).
3. **KT3 frontier signal-ceiling on the profile** — the 224 held-out
   turns; fixes the projection before any training.
4. **KT4 oracle-injection uptake** (inspect-ai) — proves the channel
   before training spend.
5. **A2 data build** — synthetic/gold primary (staged db states +
   real profile renders; structured-markdown cheap supervision with
   reconcile-oracle filtering; quotas per design.md), agy
   augmentation. **AUDIT LANDED (2026-07-12,
   [[research/lora-data-audit-2026-07-12]])**: the frontier-draft set's
   mechanical-only filter admitted 26.8% pairs that hard-fail the live
   REPL (49% of everything DeepSeek-authored; 0% of mechanical gold) —
   bare-head resolvability was the blindspot; eval+envelope gating on
   staged worlds is NON-OPTIONAL for A2, and query/report pairs need
   db-staged worlds (text-staging can't verify reads). Reusable gate
   harness: `src-needle/audit/` + `src-needle/scripts/lora_audit_*.py`.
6. **KT5 finetune reachability** (plan domain) → **B2 full train**
   (incl. Clojure-grammar constrained decoding; retrieval stays
   deterministic v0).
7. **B3 serve + `:suggest`** — call-once-then-derive, volatile-segment
   priority, diffusion offer channel; ship gate = $0 baseline loses +
   dead-weight criterion + frontier with/without arm.
8. **DG finetune (second consumer, owner 2026-07-12)** — the SAME
   canonical JSONL feeds a DiffusionGemma finetune ((context, forms) ≡
   (conditioning prefix, denoise region)); per-model formatter in the
   trainer, no second dataset pipeline. LoRA-on-MLX first (8-bit base with
   adapters), RunPod CUDA artifacts as fallback; training loop lives
   in the diffusion-gemma repo (model infra, not seon core); own
   reachability smoke before any long run. Independent of needle's
   KT5 outcome — gated only on the data build passing curation.

## Open questions

- Any persisted needle-embedding index = second index next to
  `seon.embed` — **owner-ruling gate**, must not land silently.
- ~200-line Python server scaffold copy from src-diffusion: recommend
  accept-for-v0, extract on a third server (flagged to owner).
- One `:openai-compat` gateway per cluster / one `SEON_DG_ENDPOINT`
  per pod — extend `agent-override-attrs` only when a second is
  actually needed.
- ~~Prompt-blob capture volume in prod~~ — RULED 2026-07-12: ONE
  capture dial gates prompt/reply/suggestion blobs; prod off, dev/acme
  on; `rendered-as-of` always-on. Lands with B3 (small turn.cljs +
  config change).

## Inspect takeover checkpoint — 2026-07-14

The stable and retired-gym lanes were reviewed against the canonical branch.
All stable implementation gains selected by `INTEGRATE-BACK.md` were already
present. The gym's remaining dirty predicate translations were not imported:
the gym is deleted and Inspect AI is the single harness.

The existing `long_term_planning` task and `planning_scorer` now encode the
measured three-arm plan experiment (pretransacted, model-authored, no-plan)
with database outcome, expect-verified close integrity, report-before-close,
and evidence-covered address-step discipline checks. Plan provenance and
bounded creation timing are derived from explicit plan-root/transaction
evidence; model-authored roots must fall inside the inclusive run interval and
pretransacted roots must precede it, with no root allowed after the observation
basis;
report-only runs and empty plan-arm observation sets fail. Focused offline
proof is 56 passed. The no-plan control also requires Datahike-history evidence
of zero plan roots/creations across the run interval and zero closes, so deleting
a plan cannot masquerade as the control. The
pretransacted/model-authored fixtures score 1.000 and the pilot's plausible but
database-wrong no-plan fixture scores 0.000. Standard-vs-pod execution guidance,
the verified local-35B HumanEval command, excluded stable scratch, and remaining
live gates are in [[research/inspect-harness-integration-2026-07-14]].

Model training remains paused. The next Inspect work is a live, equal-budget
three-arm drive after current run/turn/database adapters derive the pure scorer
inputs. The dirty stable fair-scoring implementation is evidence, not imported
code: it depends on the paused `src-needle` audit lane and must be rebuilt in the
canonical data-quality pipeline.
