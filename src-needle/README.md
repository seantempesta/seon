# seon-needle — MLX port of the needle 26M encoder-decoder

The [needle](../reference-code/needle/) "Simple Attention Network"
(26,315,421 params: d=512, 12 enc / 8 dec layers, 8H/4KV GQA, ZCRMSNorm +
sigmoid-gated residuals, non-interleaved RoPE, NO FFN, shared embedding +
tied output, BPE 8192 byte-fallback) ported to MLX for Apple Silicon —
Track B1 of `docs/prds/repl-autosuggest/`. Inference AND finetune run
locally; the JAX original stays in `reference-code/needle/` as the parity
oracle (test-only extra, imported via sys.path, never copied).

**Perf convention (owner): report in TOKENS/SECOND, always** — prefill and
decode separately.

## Layout

```
src/seon_needle/
  convert.py           needle.pkl (flax pickle) + tokenizer from HF
                       (Cactus-Compute/needle) -> needle.safetensors +
                       config.json; splits flax nn.scan's stacked leading
                       num_layers axis into per-layer keys
  model.py             MLX SimpleAttentionNetwork — encoder, KV-cached
                       decoder, contrastive head; `self.w` mirrors the
                       flax param tree verbatim
  generate.py          greedy decode matching run.py (dec buffer starts
                       [EOS], <tool_call> stripped), single + batch, tok/s
  tokenizer.py         SentencePiece wrapper, special ids PAD=0 EOS=1
                       BOS=2 UNK=3 TOOL_CALL=4 TOOLS=5
  token_efficiency.py  chars/token of real seon Clojure vs English JSON
  overfit.py           finetune-plumbing smoke: AdamW, PAD-masked CE,
                       memorizes 10 synthetic (context -> forms) pairs
  config.py            sole config surface (repo-root walk; SEON_* overrides)
tests/                 offline numerics tests + the JAX parity proof
checkpoints/           gitignored — populated by convert
```

## Run matrix

| Target | Command | Needs |
|---|---|---|
| Setup | `uv venv && uv pip install -e ".[test]"` | uv |
| Convert | `.venv/bin/python -m seon_needle.convert` | network (HF) |
| Inference demo | `.venv/bin/python -m seon_needle.generate` | checkpoint |
| Token efficiency | `.venv/bin/python -m seon_needle.token_efficiency` | checkpoint |
| Overfit smoke | `.venv/bin/python -m seon_needle.overfit` | checkpoint |
| Tests (incl. parity) | `.venv/bin/pytest` | checkpoint + `[test]` extra |

Offline tests (`tests/test_model.py`, encoder-input tests) run without the
checkpoint; parity/overfit/generate tests skip cleanly when it is missing.

## Measured (2026-07-12, M5 Max, macOS, MLX 0.29+)

- **Parity: 20/20 greedy-token-exact (100%)** vs the original JAX
  implementation on CPU-bf16 — README examples + tool-call phrasings +
  Clojure-ish queries, unconstrained greedy, byte-identical encoder
  inputs. Contrastive head: mechanism parity (cos ≥ 0.9954, retrieval
  order exact) with spliced random head weights — see the dead-head
  finding below.
- **Prefill** (encoder pass): ~19,700 tok/s on a 57-token input;
  **127,600 tok/s on the full 1024-token envelope (8.0 ms)**; 126,700
  tok/s aggregate at batch 8.
- **Decode** (KV-cached greedy, steady state): **428 tok/s single
  stream**; 2,719 tok/s aggregate at batch 8. Per-step Python + kernel
  launch overhead (~2.3 ms) dominates at this model size — vs cactus's
  reported 6,000 prefill / 1,200 decode (their optimized C++ runtime).
  A full turn suggestion (1024-token prefill + ~100-token decode) ≈ 0.25 s.
- **Overfit smoke**: loss 8.525 → 0.003 in 120 AdamW steps (~10,600
  target tok/s full-batch); 10/10 synthetic (context → forms) pairs
  memorized token-exact. Gradients flow; the real finetune loop is B2.
- **Clojure token efficiency** (needle tokenizer, real `src/seon/*.cljs` +
  a `reconcile!` markdown heredoc): **2.45 chars/token vs 4.48 for
  English/JSON — 1.82x more tokens per char.** The 1024-enc budget holds
  ~2,500 chars of Clojure-ish projection (~630 "chars/4" seon-estimated
  tokens); the 512-dec budget holds ~1,250 chars (~14 typical REPL forms;
  the flagship reconcile! heredoc example = 180 tokens = 35% of budget).
  Tight but workable — the projection must be genuinely compact; retrain
  the tokenizer only if real exported rows blow this (design.md).

## Findings / gotchas

- **The shipped checkpoint's contrastive head is EXACTLY zero** —
  `contrastive_hidden/{kernel,bias}`, `contrastive_proj/kernel`, and
  `log_temp` are all 0.0 (pretrain weight decay ate them; the safe-L2-norm
  comment in the reference architecture predicts this). `encode_contrastive`
  returns all-zero embeddings out of the box: **retrieval is untrained** and
  B2 must train the head from scratch.
- run.py enables trie-constrained decoding by DEFAULT; unconstrained greedy
  visibly degrades argument keys/values (e.g. `"city"` for `"location"`).
  The Clojure-grammar constrained port is B2.
- SentencePiece adds a dummy-prefix `▁` to the answer encoding, so decoded
  output after stripping `<tool_call>` carries ONE leading space (the
  reference behaves identically). Compare token ids, or lstrip at serve time.
- The pkl stores float16; run.py casts to bfloat16 at load — convert.py
  saves the fp16 bytes as stored and model.py does the same bf16 cast, so
  both sides see identical rounding.
- flax `nn.scan` stacks per-layer params on a leading num_layers axis
  (scan body auto-named `EncoderBlock_0`/`DecoderBlock_0`); convert.py
  splits it — `encoder/layers/{0..11}/...`.
- RoPE is non-interleaved (half-split, GPT-NeoX style) and cos/sin are
  float32, so roped attention runs in float32 (jax weak-typing promotion —
  matched deliberately). Decoder cross-attn has NO RoPE. Q/K get ZCRMSNorm
  before repeat and before RoPE.
