---
type: research
status: active
tags: [research, agent]
---

# Needle extension train — full-param 2048 finetune + the KT3b bar (2026-07-12)

**Status: HALTED — display defects (owner stop order, 2026-07-12
evening); checkpoint saved at step 9400/12311; eval deferred to display
v3.** The training-data display (cards missing malli specs, glyph tax,
stale cards) was ruled defective mid-run and a v3 display is coming;
eval results against the current display would be discarded, so the
eval battery was SKIPPED. The checkpoint retains value — the owner's
direction is to resume-finetune from it on v3 data rather than restart
cold (`train_extend --resume` exists and is proven; see Training run).

## TL;DR (of what DID run)

Goal (owner, spend cleared): a real extension finetune of the 26M needle
checkpoint at 2048 (position-interpolation RoPE, scale 2.0) that (a)
keeps its home tool-calling skill and (b) learns long-menu tool
selection. The 214 mined rows (`data/tune/*.jsonl`) stayed HELD-OUT
throughout.

- **Training reached step 9,400 of 12,311 planned** (phase 1 complete +
  phase 2 epoch 0 complete + ~60 steps of the final epoch) before the
  stop order. Val loss **3.046 → 0.458** (unweighted CE on the 431-row
  held-out val split), still falling monotonically. Checkpoints:
  `src-needle/checkpoints/extended-2048/best.safetensors` (step 9,341,
  val 0.4581) + `latest.safetensors` (step 9,400) + `config.json`
  carrying `enc_rope_scale 2.0` (loads via
  `seon_needle.train_extend.load_extended`). Peak 7.22 GB, single
  process, ~7.4–7.9k padded tok/s.
- **4096 is NOT trainable inside the 8 GB envelope** (measured: 10.04 GB
  plain, 9.66 GB with per-layer gradient checkpointing — chunked
  attention would be prerequisite surgery).
- **Two pre-train baselines were measured before the halt** (kept — they
  are display-independent-ish floors, to be re-run on v3): stock needle
  on the 213 held-out v2 rows through the bridge scores **useful 0.006**
  @1024 (tools truncated on 198/213) and **0.000** @2048 zero-shot
  position interpolation — the finetune has to earn everything;
  interpolation alone is worthless.
- **Bridge fidelity oracle: 0.986** (gold `json_target`s round-tripped
  through the JSON→Clojure bridge + KT3 scorer) — the bridge is not a
  bottleneck for any future eval.
- **Ops finding (cost a machine-wide incident):** MLX's Metal buffer
  cache is UNBOUNDED and, under fully dynamic batch shapes, grew to
  **103 GB** of cached buffers (invisible to `mx.get_peak_memory`),
  drove macOS into `vm-compressor-space-shortage`, and jetsam-SIGKILLed
  the trainer (rc=137) three times — plus hundreds of system daemons.
  Fix (in `train_extend.py`, load-bearing for ANY future MLX training
  here): `mx.set_cache_limit(2 GB)` + pad-shape quantization to
  multiples of 128 + periodic `mx.clear_cache()`. Same cap added to the
  eval script.

## Training setup

### 4096 is NOT trainable inside the machine discipline (measured)

Single forward+backward at enc 4096 (rope scale 4, f32 masters, AdamW),
B=1:

| config | peak | step | note |
|---|---|---|---|
| plain | **10.04 GB** | 1.1s (3.7k tok/s) | over the 8 GB envelope |
| per-layer `mx.checkpoint` on all 12 encoder layers | **9.66 GB** | 0.5s (7.9k tok/s) | still over — a single layer's f32 T² attention (8 heads × 4096² × 4 B ≈ 0.54 GB) times the live forward+backward temporaries dominates; checkpointing removes cross-layer storage only |

Getting under 8 GB at 4096 needs CHUNKED (query-block) attention — real
numerics surgery on the paritied kernel, out of scope for this unit. The
2048 train peaks well under the envelope (numbers below). 4096 appears
in the eval battery as SERVE-TIME zero-shot scale-4 interpolation
(inference has no T² gradient footprint), reported as exactly that.

### Training data (the held-out rule intact)

**Stratum 1 — home, packed long.** Needle's actual post-train set
(`Cactus-Compute/tool-calls`) is **PRIVATE** — HF returns 401 for
anonymous access and no HF token exists on this machine (verified; only
`needle-tokenizer` and two gemma repos are public under Cactus-Compute).
The home stratum is therefore an on-distribution substitute, documented
rather than hidden:

- `argilla/Synth-APIGen-v0.1` (public, 49,402 rows) — the APIGen/xlam
  lineage: `{query, tools, answers}` single-shot function calling, the
  task shape needle's README describes for its post-train ("2B tokens of
  single-shot function call dataset"). 43,390 rows validate (answers
  parse ∧ every called name in the row's own tools; 12 dropped); 6,000
  prose-answer rows ("cannot be answered") become ABSTENTION rows
  (target `[]`).
- `MadeAgents/xlam-irrelevance-7.5k` (public) — real-API irrelevance
  rows, also `[]` targets.
- **Distractor bank = needle's own home tool universe**: the 322 tool
  defs in the 33 `POOL_*` lists of the reference repo's OPEN
  dataset-generation source (`needle/dataset/generate.py`, extracted by
  AST literal-eval — the module imports `google.genai` at top level), +
  ~5,200 name-deduped tools harvested from other Synth-APIGen rows.

Packing (the dataset.py idea re-based — their concat-packing needs
block-diagonal masks in this enc-dec shape, noted in the prep unit as
deliberately unscaffolded): each row's TOOLS SLOT is enlarged with
shuffled distractors until the encoder assembly reaches a sampled target
length — 30% of rows target (400, 1024] (envelope retention), 70% target
(1024, 2044] (the extension). Tool order, per-tool param order, and
top-level key order are shuffled (the reference's
anti-position-memorization trick, ported).

**Stratum 2 — seon long-menu rows.** Situation→call rows over the dumped
168-fn index through KT2b's translation layer (`fn_to_tool`, snake
names): queries generated by agy (gemini-3.5-flash) per fn — 6 distinct
phrasings each, mechanically gated (param keys ⊆ tool params ∧ required
present ∧ **every scalar argument value literally present in the query
text** — the ingredients-coverage rule applied to training data), plus
agy-generated irrelevance queries (~15-20% with all-distractor menus →
`[]`). Menus at sizes {8, 16, 24, 32} (64 needs 4096 — not trainable,
above), half same-namespace distractors (KT2b's hard case), expected
tool at a uniformly random recorded position.

**Leakage guards:** `cases/kt2b_cases.json` (the lint-probe EVAL bank)
is excluded verbatim from training queries; additionally a **seeded
10-fn holdout** of case-bank expected fns is never a training TARGET
(legal as distractors), so the probe re-run reports seen-fn vs
unseen-fn selection separately. Paraphrase-family style overlap between
agy training queries and the eval bank's own agy paraphrases is inherent
to the design and reported, not hidden. The v2/v1 mined rows are not
read by the data build at all.

### Mix schedule (documented per the brief)

| phase | epochs | home stratum | seon stratum |
|---|---|---|---|
| 1 | 1 | 100% | 25% (seeded sample) |
| 2 | 2 | 100% | 100% |

### Hyperparameters

Full-param (owner: at 26M, LoRA overhead isn't worth it), **f32 master
weights** (B1: bf16 masters silently stop learning), rope scale **2.0**
on the encoder path only. AdamW lr 1e-4 (warmup 3% → cosine to 0.1×),
weight decay 0.01. Loss = the reference's own post-train shape
(`train.py _text_loss_fn`): token-class weighted CE — **name 2.0 /
value 4.0 / key 1.5** (the `finetune.py` values), normalized by
non-pad token COUNT, + 1e-4 z-loss. Token classes ported from
`dataset.py _token_classes_for_answer` (re-derived; that module's import
chain needs the heavyweight `datasets` dep — the lint_probe
`normalize_tools` precedent). One deliberate improvement: non-scalar
argument values are marked with compact JSON separators so they actually
match the compacted answers (the reference dumps with spaces and never
marks them). Batching: pad-minimizing length-bucketed packing under an
encoder-token budget (`extend.pack_batches`). Single process, peak
target < 8 GB.

Code: `src-needle/src/seon_needle/data_extend.py` (data build),
`train_extend.py` (trainer + 4096 probe),
`scripts/gen_seon_queries.py` (agy generation),
`scripts/eval_extend.py` (eval battery). Checkpoints under
`src-needle/checkpoints/extended-2048/` (gitignored).

## Dataset build (RUN)

`data/extend/train.jsonl` 13,618 rows + `val.jsonl` 431 (3% seeded
hash split); encoder assemblies measured 387–2042 tokens (p50 ~1310
home / ~1558 seon), **0 rows over 2048**:

| stratum | rows | share | note |
|---|---|---|---|
| home (answered) | 9,995 | 73% | Synth-APIGen, packed with distractors |
| home-irrelevance | 1,800 | 13% | APIGen prose-answer + xlam-irrelevance → `[]` |
| seon | 1,894 | 14% of total | 1,007 agy queries × 2 menu variants (158 target fns) |
| seon-irrelevance | 360 | 16% of the seon stratum | 90 agy queries × 4 variants |

agy generation: 1,097 queries kept / 1 dropped by the mechanical gates
(`cases/extend_train_queries.json`, committed for reproducibility); all
168 index fns covered; 10-fn holdout enforced at build time (60 queries
excluded from targets): `my.blob/put!`, `my.data/group-sum`,
`my.kb/forget-source!`, `my.kb/remember`, `my.kb/remember-sources!`,
`my.plan/needs!`, `my.plan/next`, `my.plan/reconcile!`,
`seon.agent.shell/run`, `seon.db/new-id!`. 0 eval-bank queries appeared
verbatim. Seon menu sizes land at 8/16/24/32 (32 trims to ~22–25 under
the 2048 cap for longer queries); expected-tool positions recorded
per row.

**Bridge fidelity oracle (pre-train):** round-tripping the 144 GOLD
`json_target`s through the JSON→Clojure bridge and the KT3 scorer gives
**useful 0.986, parse 144/144** — the bridge is not the bottleneck in
any number below (1 agent-namespace fn falls out of the snake→sym maps;
documented, scored as the miss it produces).

## Training run

- Peak-memory tuning: token budget 6144 peaked 9.23 GB (over the 8 GB
  envelope) → shipped budget **4096** (measured 6.27 GB probe / 7.22 GB
  full-run peak, max batch 5).
- ~12,311 steps planned (phase 1 + 2×phase 2), single process.
- **Shared-GPU contention (disclosed):** two other lanes ran MLX
  workloads concurrently the whole time (a KT3-redux local-model eval
  queue + an `mlx_lm.lora` run) — the ops queue interleaves; wall and
  tok/s numbers are contended lower bounds.
- **The jetsam incident (root-caused, fixed):** the first three launches
  died by SIGKILL (rc=137) with no traceback — `JetsamEvent-*.ips`
  reports at each kill time show the kernel in
  `vm-compressor-space-shortage`, and `top -o cmprs` caught the trainer
  at **103 GB MEM** (RSS ~1 GB): MLX's Metal buffer cache holds every
  freed buffer for reuse, and fully dynamic (B, T) batch shapes meant a
  new buffer size per batch, never reused — unbounded growth invisible
  to `mx.get_peak_memory()`. Swap hit 33/34 GB; macOS killed hundreds
  of daemons. Fixes shipped in `train_extend.py`:
  `mx.set_cache_limit(2 GB)`, `_pad` quantizes padded lengths to
  multiples of 128 (bounds the shape vocabulary), `mx.clear_cache()`
  every 100 steps. The run also gained deterministic-plan RESUME
  (`--resume`: weights + global step saved every 200 steps; the batch
  plan re-derives from one seeded rng and skips executed steps; AdamW
  moments not persisted — fresh init + 100-step lr re-ramp) and a
  restart supervisor. After the fix: zero kills, memory flat at 7.2 GB,
  throughput doubled (memory pressure had also been throttling the
  steps).

### Training curves (to the halt at step 9,400)

Val = unweighted CE over the 431-row val split (batches of the same
token budget), measured every 600 steps:

| step | tag | val loss |
|---|---|---|
| 0 | pre (stock @scale 2) | 3.0461 |
| 600 | phase1 | 0.8226 |
| 1200 | phase1 | 0.6820 |
| 1800 | phase1 | 0.6147 |
| 2400 | phase1 | 0.5836 |
| 3000 | phase1 | 0.5531 |
| 3600 | phase1 | 0.5351 |
| 4200 | phase1 | 0.5191 |
| 4343 | phase1 END | 0.5173 |
| 4800 | phase2-e0 | 0.5023 |
| 5400 | phase2-e0 | 0.4912 |
| 6000 | phase2-e0 | 0.4869 |
| 6600 | phase2-e0 | 0.4790 |
| 7200 | phase2-e0 | 0.4732 |
| 7800 | phase2-e0 | 0.4672 |
| 8400 | phase2-e0 | 0.4615 |
| 9000 | phase2-e0 | 0.4596 |
| 9341 | phase2-e0 END | **0.4581** (= best checkpoint) |

Epoch means: phase1 train loss 1.823 (first-50 5.89 → last-50 1.60,
40 min, 7,358 tok/s); phase2-e0 train loss 1.101 (1.19 → 1.09, 43 min,
7,900 tok/s). The curve was still falling monotonically at the halt —
the final epoch (~5,000 steps) never ran past step ~9,400.

## Training curves ⏳

## Eval battery — SKIPPED (owner stop order; deferred to display v3)

The planned battery (all scaffolded and runnable in
`src-needle/scripts/eval_extend.py`): KT3 bridge eval over the 213
held-out v2 rows scored with the KT3-redux extended scorer (set-union
over `target_bundle` primary, next-form secondary, error decomposition —
the extended `kt3_score.clj` landed mid-unit and my probe detects/uses
it, with a HEAD-copy + Python-decomposition fallback), a
max-cards-at-trained-length arm (full fn-index top-up @2048 and
zero-shot @4096), the KT2b lint probe at menus 8/16/32/64 with
per-position curves (`probe`), BFCL live_simple 100 (`bfcl`), and the
latency micro-bench (`latency`). Run it against the v3-display retrain
with:

```bash
cd src-needle
.venv/bin/python scripts/eval_extend.py bridge   # all 5 arms, resumable
.venv/bin/python scripts/eval_extend.py probe    # stock stock-pi trained
.venv/bin/python scripts/eval_extend.py bfcl
.venv/bin/python scripts/eval_extend.py latency
```

### What was measured before the halt (keep — the floors)

Pre-train baselines over the 213 held-out v2 rows (bridge + extended
scorer, `src-needle/data/exteval/`):

| arm | bundle useful (all 213) | json 144 | next-form (json 144) | abstention | tools cut |
|---|---|---|---|---|---|
| stock-1024 | **0.006** | 0.006 | 0.005 | 0.737 | 198/213 |
| stock-2048-pi (zero-shot interpolation) | **0.000** | 0.000 | 0.000 | 0.131 | 0/213 |

Stock needle on profile contexts is a ~zero floor (the KT3b "bridge
probe = measurement only" expectation, now measured): at 1024 the
context alone fills the envelope (tools truncated 198/213, 74%
abstention); at 2048 zero-shot scale-2 interpolation the tools fit but
the untrained compressed positions destroy even that — it hallucinates
calls instead of abstaining (abstention 0.131) and matches nothing.
**Interpolation alone is worthless; only the finetune can earn the
window** — which is why the saved checkpoint (val 3.05 → 0.46 on
mixed home+seon long-menu data) is worth resuming from.

Stock BFCL anchors (KT2b, unchanged): live_simple name-acc 0.77 @menu-1
/ 0.65 @menu-8. Stock seon lint probe @1024: 0.669 @menu-1 / 0.283
@menu-8 / 0.131 @menu-16 (165/169 truncated).

## Verdict vs the bar — DEFERRED

No trained-model numbers exist (eval skipped by the stop order). The
KT3b bar stands unchallenged: Qwen2.5-Coder-1.5B + 3 exemplars .265
overall / .383 @cov≥.75. The whole-graph card-count/position questions
move to the v3-display retrain, where this unit's machinery (data
pipeline, trainer with resume, bridge, eval battery, holdout design)
reruns as-is on regenerated data.

## Resume plan (for the v3 retrain)

1. Regenerate the A1 export + v2 build under the v3 display (contexts
   AND cards change; the exporter re-derives, never patches).
2. Rebuild `data/extend/` (`seon_needle.data_extend`) — the agy query
   bank (`cases/extend_train_queries.json`) is display-independent
   (English situations + argument JSON) and reusable; the seon menus
   re-translate from the refreshed fn index automatically.
3. Either resume-finetune from
   `checkpoints/extended-2048/best.safetensors` (owner's lean; the
   length/selection skills likely transfer, display formatting is the
   delta) or cold-start — decide with a 500-step val-loss A/B, which
   `--limit` + `--resume` make a ~10-minute question.
4. Run the eval battery above; compare to the stock floors in this file
   and the KT3b bar.
