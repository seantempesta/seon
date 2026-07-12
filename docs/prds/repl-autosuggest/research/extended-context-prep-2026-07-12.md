---
type: research
status: active
tags: [research, agent]
---

# Extended-context prep — v2 dataset, fit at 2048/4096, extension smoke (2026-07-12)

**TL;DR — recommend 2048.** The v2 dataset (compact cards, next-form
targets, JSON-native columns) is built and measured with the real needle
tokenizer: **100% of rows fit a 2048 encoder envelope** (10.3% fit 1024 —
KT1's kill restated on v2), with a median **+14 additional compact cards
per row** of card budget at 2048 (**+55 at 4096**). The
position-interpolation extension scaffold is proven end-to-end: overfitting
10 REAL >1024-token v2 rows at 2048 (rope scale 2.0) drives loss
5.46 → 0.006 and reproduces all **10/10 targets token-exact** through the
interpolated path, in both the Clojure-target and JSON-native arms —
single process, **peak 3.3–3.6 GB**, ~10–19k tok/s. 4096 buys headroom no
current row uses, quadruples attention cost, and (at this model's explicit
f32 attention) would not train inside a 4 GB envelope on this machine
without checkpointing/fused-attention surgery. Context: owner direction —
extend the encoder via finetune and spend the extra window as CARD BUDGET;
context generation stays FROZEN (nothing in `src/seon` rendering changed).

## What was built (all in `src-needle/` + `data/tune/`)

- **`data/tune/acme-2026-07-12-v2.jsonl`** (213 rows) + provenance sidecar
  `acme-2026-07-12-v2.meta.json` (source sha256, transform list, counts,
  translatability stats). Pure transform of the frozen A1 export — no
  re-render; contexts byte-identical to v1.
- **`src-needle/scripts/split_forms.clj`** — bb/edamame (a real reader, no
  regexes; KT3-scorer lineage): byte-exact top-level form splitting via
  location metadata, junk rules, call-shape classification + JSON value
  translation, v1 full-card parsing (name/doc/spec slices).
- **`src-needle/src/seon_needle/build_v2.py`** — the v2 builder (reuses
  `kt1_envelope.compact_card` and KT2b's translation layer verbatim:
  `lint_probe.fn_to_tool`/`Registry`/`normalize_tools`/needle's own
  `to_snake_case`).
- **`src-needle/src/seon_needle/extended_fit.py`** — fit + card-budget
  measurement at 1024/2048/4096 (raw JSON: `src-needle/data/extfit/`).
- **`src-needle/src/seon_needle/extend.py`** + a `model.py` rope option —
  the extension-finetune scaffold (position-interpolation RoPE, packing,
  overfit smoke). `pytest` suite 11/11 green including JAX parity — the
  default path is proven byte-unchanged.

## 1 — Dataset variant v2

Row shape: `context` (FROZEN, byte-identical to v1) · `cards` (compact) ·
`target` (first clean form) · `target_substantive` (first non-ns-move
form; absent when none — 191/213 rows have it) · `target_bundle` (cleaned
whole-turn bundle, for the bundle-vs-next-form ablation) · `json_tools` +
`json_target` (needle-native, below) · `meta` (v1 meta + `v1_row`,
`bundle_forms`, `json_status`, and `dropped_forms`/`json_gaps` when apt).

### Compact cards

KT1's compaction applied to every card (name + docstring line-1 + arglist;
the `{:malli/schema …}` map stripped by the string-aware balanced-brace
scan — `kt1_envelope.compact_card`, reused not re-implemented). 487
distinct full cards → 485 distinct compact (two pairs merge). Note: KT1's
"462 distinct cards" was distinct card NAMES; 462 reappears below as the
distinct snake-cased tool-name count — same census, different key.

### Next-form targets (KT3's granularity finding)

Every v1 target parsed with edamame (`:all true`, KT3's auto-resolve);
top-level forms sliced **byte-exact** via location metadata — join-back
fidelity was self-checked: 0/214 mismatches. `target` = the FIRST clean
form; the bundle is kept as a column, not exploded into rows. 116/214 v1
rows were single-form already; forms per row min 1 · median 1 · max 8.
52/213 v2 `target`s are ns-moves (trivially prependable boilerplate —
that is what `target_substantive` is for, and the JSON arm excludes them
naturally).

### Junk filter (computed rules, no name lists)

- **parses-clean** (a real reader): 0/214 targets failed to read — the
  filter exists as the gate, but on this export it fires zero times.
- **prose-form rule**: a list of ≥2 elements, ALL bare unnamespaced
  purely-alphabetic symbols — the mechanical signature of English prose
  that reads as a call. Catches exactly 3 forms, all verified junk:
  row 179's `(which is incorrect)` (the KT3-flagged case), row 170's
  `(title, author, year, pages)`, row 184's
  `(The plan has been laid down)`. Row 184's target was ONLY that form →
  row dropped (214 → 213); rows 170/179 lose the junk head and their
  target becomes the following defn.
- **Residual junk (reported, not silently patched):** 2 rows carry prose
  containing ids/numbers the alphabetic rule cannot catch —
  `(root azm-2607112358, 3 steps, multi-session)` (v1 row 31) and
  `(mint fresh agent, observe RSS, …)` (v1 row 139). Both are flagged
  mechanically anyway: their `json_gaps` carry `head not in the row's
  tools` + `unnamed positional args`, so the training-split curation can
  drop on that signal. Tightening the prose rule to catch them would be
  fitting the rule to two instances.

### JSON-native columns (owner addition, 2026-07-12)

The needle arm trains in its home format; the KT2b layer translates at
the boundary — reused verbatim from `lint_probe`:

- **`json_tools`** — the row's cards as needle tool defs
  (`{name, description, parameters}`), snake-cased via `normalize_tools`
  + needle's `to_snake_case`, in card order. Encoder tools slot =
  `json.dumps(row["json_tools"], separators=(",", ":"))`. Card → spec
  resolution: 111/487 cards match the fn-index (name + doc line-1;
  fully-qualified dumped spec — the exact KT2b path); 376 are
  agent-/core-defined fns outside the 168-fn index and translate
  card-locally from the card's own `{:malli/schema …}` slice, with
  `::alias` keywords resolved against the dumped registry when the name
  part is UNIQUE there (computed rule; 62 hits). 0 cards unparseable;
  309 tools carry KT2b-style lossiness notes (opaque request schemas
  etc. — the KT2b legibility findings restated, not new).
- **`json_target`** — the next-form target as needle-home
  `[{"name", "arguments"}]`. The call name joins the row's tools-slot
  snake name via KT3's symbol rule (alias-insensitive), so target and
  menu agree — 141/144 non-null targets join a tool in their own row
  (the 3 misses: the 2 residual-junk rows + one call to an
  agent-defined fn absent from the cards, i.e. a coverage gap). Arg-map
  keys use KT2b's param rule (last segment, snake_case, collision →
  qualified); positional args ride the tool's declared param order;
  keyword values keep their `":kw"` string; values JSON cannot carry
  (quoted datalog, symbols) become their **byte-exact EDN source
  string** and mark the row partial.

**Translatability (213 rows):** full **101 (47.4%)** · partial **43
(20.2%)** (edn-fallback values — dominated by `db/query` quoted
`:where` graphs, the v0-excluded kind) · none **69 (32.4%)** = 52
ns-move + 17 def-form first forms, exactly the expected fallout — those
rows are the coder-arm's exclusive food (`json_target` null). Top-level
form census across all bundles (404 kept forms): call 296 (73.3%) ·
ns-move 72 · def-form 31 · control 4 · interop 1. (KT3's "~93%
call-shaped" counted any symbol-head list including defn/ns-move,
recursively; under that definition these forms are ~100% — the two
numbers measure different things.)

## 2 — Fit at extended budgets (real needle tokenizer, v2, untruncated)

Encoder assemblies (`tokens(context) + 1 sep + tokens(tools slot)`):

| assembly | min | p25 | p50 | p75 | max | ≤1024 | ≤2048 | ≤4096 |
|---|---|---|---|---|---|---|---|---|
| context alone | 415 | 974 | 1089 | 1237 | 1481 | — | — | — |
| context + sep + compact cards | 648 | 1203 | 1332 | 1452 | 1839 | **10.3%** | **100%** | 100% |
| context + sep + json tools | 615 | 1252 | 1407 | 1544 | 1929 | 7.0% | **100%** | 100% |

KT1's verdict restated on v2: the current frozen profile does not fit
1024 even with compact cards, and fits 2048 with **~200–850 tokens of
per-row headroom** (median 716 for compact cards). Nothing needs 4096.

### The card-budget table (the owner's question)

Measured per-unit costs over the distinct pools (not the assumed ~40):
median compact card = **50 tokens** (incl. join newline; p25 44 / p75
59); median JSON tool def = **56 tokens** (incl. comma). Additional
units fitting in the leftover headroom, per row:

| budget | + compact cards (min/p25/p50/p75/max) | mean | + json tools p50 | mean |
|---|---|---|---|---|
| 1024 | 0 / 0 / 0 / 0 / 7 | 0.2 (zero for 197/213 rows) | 0 | 0.1 |
| 2048 | 4 / 11 / **14** / 16 / 28 | 14.2 | **11** | 11.3 |
| 4096 | 45 / 52 / **55** / 57 / 68 | 55.2 | **48** | 47.9 |

Rows currently carry median 4 cards (3–8). So 2048 grows the visible
surface ~4.5× (4 → ~18 cards/row); 4096 grows it ~15× (~59 — about a
third of the whole 168-fn agent surface on every row). Two honest
qualifiers: (a) this is headroom arithmetic, not proof more cards help —
KT2b measured name-accuracy DEGRADING with menu size on the stock
checkpoint (0.283 @8-tool), so the card-budget payoff must be validated
in the KT4/KT5 lane, not assumed; (b) the fit is measured on the CURRENT
frozen profile — a re-capped profile changes the arithmetic.

### Targets after the next-form split (512 decoder envelope)

| target | n | min | p25 | p50 | p75 | max | >512 |
|---|---|---|---|---|---|---|---|
| next-form (`target`) | 213 | 7 | 20 | **30** | 76 | 959 | 3 (1.4%) |
| first substantive | 191 | 7 | 29 | 48 | 84 | 959 | 3 |
| bundle (ablation) | 213 | 7 | 30 | 78 | 153 | 959 | 5 (2.3%) |
| json_target (compact) | 144 | 15 | 23 | 36 | 77 | 907 | 2 |

The split moves the median from 78 → 30 tokens and leaves only 3
over-envelope rows (single giant `reconcile!`/`register-book-schema`
forms — they are single forms, splitting cannot shrink them).

## 3 — Extension-finetune scaffold + smoke

**RoPE position interpolation** (`model.py`): `NeedleConfig` gains
`enc_rope_scale` (default 1.0 — stock behavior; parity suite proves the
default is byte-unchanged). The scale divides positions in the ENCODER
rope table only (per-scale cached), so a `scale×1024` input lands in the
trained rotary range; the decoder never scales (self-attn stays inside
the trained 512; cross-attn has no rope).

**Packing** (`extend.py pack_batches`): pad-minimizing length-bucketed
batching under a per-batch token budget (the T² attention term is the
memory constraint). Concat-packing multiple examples into one encoder
sequence needs block-diagonal masks in this enc-dec shape — noted as a
B2 option, deliberately not scaffolded.

**Smoke** (`.venv/bin/python -m seon_needle.extend`): the 10 LONGEST v2
rows with assemblies in (1024, 2048] — every input genuinely exceeds the
trained envelope — overfit at `max_enc_len=2048`, `rope_scale=2.0`, f32
master weights, AdamW 3e-4, B=1, 30 epochs, then greedy decode through
the same interpolated path. Both arms green:

| arm | enc lengths (tok) | loss curve (epoch 0/1/5/10/20/29) | memorized | train | tok/s | peak |
|---|---|---|---|---|---|---|
| Clojure (compact cards → form) | 1604–1839 | 5.46 / 2.84 / 0.35 / 0.064 / 0.015 / **0.0056** | **10/10 token-exact** | 54.9s | 9,585 (enc+target; 18,866 in a solo run) | **3.30 GB** |
| JSON-native (json_tools → json_target) | 1719–1929 | 4.30 / 2.33 / 0.35 / 0.076 / 0.015 / **0.0067** | **10/10 token-exact** | 52.0s | 10,901 | **3.58 GB** |

Machine discipline held: single process, gentle, peak well under 4 GB
(`mx.get_peak_memory`). This is a plumbing proof — gradients flow, the
interpolated positions are learnable, memorization works at the extended
length in needle's home format too. It is NOT evidence of
generalization quality at 2048; that is KT5/B2's question.

## Recommendation: 2048 (with the numbers)

1. **Fit:** 2048 already holds 100% of rows in both slot encodings with
   median 716 tokens spare; 4096 buys headroom above the observed max
   (1929) that nothing uses.
2. **Card budget:** 2048 gives +14 compact cards (a 4.5× surface growth)
   — a large first step whose payoff is unvalidated (KT2b's menu-size
   degradation cuts against "more is better" for a 26M picker). Buying
   +55 before measuring +14 is spending ahead of evidence.
3. **Interpolation factor:** scale 2.0 is the gentle, well-trodden
   position-interpolation regime and is now smoke-proven here; scale 4.0
   compresses positions twice as hard and needs more finetune signal to
   recover.
4. **Cost:** encoder attention is quadratic — 4096 is 4× the 2048
   train/serve attention cost. Concretely: the 2048 smoke peaks at
   3.3–3.6 GB with explicit f32 attention; the same at 4096 lands ~4×
   the attention footprint and does not fit a gentle <4 GB envelope on
   this machine without checkpointing or fused-attention surgery. B1's
   0.25s/suggestion serving budget also stretches ~4× at 4096 prefill.
5. **Reversibility:** the scaffold is scale-parametric
   (`enc_rope_scale`, `max_enc_len` args); if the KT4/KT5 card-budget
   evidence demands 4096 later, the same finetune path runs at scale 4
   with zero new mechanism — only the memory surgery above becomes
   prerequisite work.

## Runbook

```bash
cd src-needle
.venv/bin/python -m seon_needle.build_v2        # v1 -> v2 + sidecar (needs bb)
.venv/bin/python -m seon_needle.extended_fit    # fit/card-budget JSON
.venv/bin/python -m seon_needle.extend          # both smoke arms (~2 min)
.venv/bin/python -m pytest -q                   # 11/11 incl. parity
```

Raw measurement JSON: `src-needle/data/extfit/` (gitignored — this file
quotes it). The v2 file + sidecar are force-tracked next to the v1 export
under `data/tune/`.

## Honest caveats

- The fit tables are for THIS frozen profile render; re-capping the
  profile (KT1's option a) or ASCII-izing glyphs (owner-gated) shifts
  every number down.
- The +14/+55 card counts price cards at the measured medians (50/56
  tok); real added cards for a retrieval top-up may skew larger (the
  p75 card is 59, and the fattest is 408).
- `json_target` covers 67.6% of rows (144/213); the 69 null rows
  (ns-move/def-form next forms) are by design the coder arm's food —
  the JSON arm's dataset is those 144 (101 full + 43 partial).
- Partial rows' edn-fallback strings (quoted `:where` graphs) are
  copy-learnable but not JSON-semantic; the v0 kind exclusion already
  covers most of them.
- Two residual junk rows survive the prose rule (ids/numbers in prose);
  both are mechanically flagged via `json_gaps` — filter on
  `head not in the row's tools` at training-split curation.
- The smoke's tok/s varied between runs (9.6k–18.9k enc+target tok/s for
  the identical clojure arm — thermal/scheduling variance on shared
  hardware); the loss curves and memorization results are identical
  across runs.
