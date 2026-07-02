---
type: research
status: active
tags: [research, agent]
---

# B2 memory-QA baseline — first honest external score (task #90)

## TL;DR

**`pass^4 = 0/16 = 0.000` · raw cell accuracy `21/64 = 0.328`** on the case-1
memory-QA benchmark (16 samples, 2 facts + 3 distractors each, host-side
`includes` scorer, K=4 epochs), driven through the isolation-fixed `/solve`
(commit `ae4cf701`) on the default pod.

The score is low, and the WHY is the important part: **the memory mechanism
works — the weak DeepSeek agent's OUTPUT DISCIPLINE does not.** In 39 of the 43
failing epochs (91%) the agent stored AND retrieved its facts correctly but
never emitted the crisp answer — it narrated "test passed", fiddled with live
tiles, re-oriented itself, and ran to `:turn-limit` (20 turns) or `:no-forms`.
Only 4 failing epochs were genuine wrong answers (hallucinated a different
value despite holding the facts). Zero failures were harness/isolation: no
sample ever quoted another sample's facts.

This is the pre-compaction baseline — the number D1 (memory-evolution) must
beat and the "before" for the Track-C namespace-compact flip.

## Config trend key (ACTUAL — differs from the originally-quoted key)

- git HEAD: **`ae4cf701`** (the `/solve` isolation fix)
- `config/system.edn` sha256: **`bb82992e49c217ee`**

NB the coordinator's task quoted git `ac23c1e6` + sha `9d78a65ba1a2f6ba`; the
config advanced between then and the measured run (the isolation fix + any
config-init landings). The baseline is keyed to what was ACTUALLY measured,
above — re-quoting the stale key would misattribute the number.

## Method

- **Dataset:** `memory_qa_dataset.jsonl`, 16 samples. Each stores 2 real facts +
  3 distractors, then asks a question answerable only by discriminating-retrieval
  in a later turn. Target (answer key) host-side only.
- **Solver:** `seon_pod_solver` → `POST /solve` (Seon owns the multi-turn loop;
  inspect never manages a turn). Fresh core-seeded `:memory` store per sample
  (isolation verified live: sample A stored `ZEBRA-8827`; a fresh sample B did
  NOT see it).
- **Scorer:** host-side `includes()` (ignore_case). A timeout/empty/no-answer
  reply scores as a MISS (includes won't match) — confirmed correct, NOT read as
  stale state.
- **Epochs:** `Epochs(4, pass_at(4))` — pass^4.
- **Run shape:** 4 chunks × 4 samples × K=4 (16 cells ≈ 25–35 min each), serial
  (`--max-samples 1`), merged. Chunking was FORCED: two prior full-64 runs were
  killed at ~1 hr / ~24–29 cells by a ~1-hour background-task lifetime cap in the
  environment (NOT a pod grab — pod pid was stable both times; NOT a harness
  bug). Mean drive = 116 s → 64 drives ≈ 124 min, over the cap. Each chunk fits
  under it and writes its own `.eval`; the four are merged by sample id.

## Per-sample result (pass^4: correct on all 4 epochs)

| sample | target | epochs (C/I) | pass^4 | failing-epoch cause |
|--------|--------|--------------|--------|---------------------|
| aurora | Voss | C I I I | miss | 3× ramble→turn-limit |
| beacon | Zhang | C C C I | miss | 1× ramble→turn-limit |
| cascade | Berg | C C I C | miss | 1× ramble→turn-limit |
| cobalt | Tanaka | I I C I | miss | 3× ramble→turn-limit |
| granite | Ellis | C C I I | miss | 2× ramble→turn-limit |
| harbor | Iglesias | C I I I | miss | 3× ramble (turn-limit/no-forms) |
| helios | 42 | I C I C | miss | 1× genuine-wrong + 1× ramble |
| lumen | Adeyemi | I C I I | miss | 3× ramble→turn-limit |
| meridian | Costa | C I C I | miss | 1× genuine-wrong + 1× ramble |
| orion | Raman | I I I I | miss | 4× ramble (turn-limit/no-forms) |
| pinnacle | Solberg | I I I I | miss | 4× ramble (turn-limit/waited) |
| quartz | Nasser | I I C I | miss | 3× ramble→turn-limit |
| solstice | Kim | I I I I | miss | 4× ramble→turn-limit |
| tidal | Moreau | I C I I | miss | 1× genuine-wrong + 2× ramble |
| verdant | Farouk | C I I C | miss | 1× genuine-wrong + 1× ramble |
| zephyr | Okafor | I C I I | miss | 2× ramble→timeout + 1× turn-limit |

- **pass^4 = 0/16 = 0.000** (no sample was correct on all 4 epochs)
- **raw cell accuracy = 21/64 = 0.328** (24 of 64 individual epochs scored C)
- **failing-epoch triage: 39 no-answer/ramble · 4 genuine-wrong · 0 harness**

## What the failures actually are (live-verified from replies)

The agents STORE and RETRIEVE correctly — the replies prove it:
- orion e1: *"successfully pulled one by lookup-ref, stored a new finding, and
  retrieved it back. The full cycle works."*
- tidal e1: stored 3 facts and pulled them back by id.

…but the weak DeepSeek then fails to CLOSE with the answer:
- **ramble→turn-limit (dominant, 91% of misses):** narrates "test passed",
  builds/updates a live tile, re-orients ("I'm here and ready"), and hits 20
  turns without ever writing the target surname. `includes` correctly scores
  MISS.
- **genuine-wrong (4):** hallucinates a different value despite holding the
  facts (e.g. meridian → "George Washington Bridge"; tidal e1 → wrong plant).
- **no failures were harness/isolation** — no cross-sample fact bleed anywhere.

## Levers this baseline identifies (for D1 and Track-C)

1. **Output discipline is the #1 lever, not memory capability.** The agent needs
   to say the answer and `complete` instead of rambling to turn-limit. This is a
   guidance/harness-prompt or a stronger-model concern, NOT a memory-mechanism
   one — the store→retrieve cycle already works.
2. **Context is 62% namespaces (~8k/13k tokens).** The Track-C compact-flip
   targets exactly this; re-run this baseline after C1 to measure the delta.
3. **Turn-limit = 20 may be too generous** for a task that needs ~3 turns; a
   tighter limit would convert ramble-timeouts to faster misses (cheaper runs)
   without changing the score.

## Artifacts

- Harness: `memory_qa_bench.py` + `memory_qa_dataset.jsonl` (commit `661a4b12`).
- Byte-level per-turn prompt sample: `baseline-prompt-sample.txt` (~17k tok =
  3936 system + 13039 context).
- The 4 chunk `.eval` logs live under `logs/` (git-ignored); merge with
  `scratchpad/merge_baseline.py` (session scratch).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
