# seon-clm — CLM relevance scoring on Apple silicon

An MLX port of the inference half of [CLM](../reference-code/CLM) (Contrastive
Language Models): the Qwen3-8B last-token encoder that CLM's head was trained
against, plus the state and action projection heads. Seon uses it to score how
relevant each context candidate is to an agent's turn: one encoder pass per new
state, then a dot product per cached candidate.

## What is ported and what is reused

| part | reference (CUDA) | here (MLX) |
|---|---|---|
| encoder | vLLM `--runner pooling` server | `seon_clm.encoder.Encoder` — mlx-lm `Qwen3Model`, final norm, last token, L2 |
| heads | torch `clm.heads.HeadPair` | `seon_clm.heads.HeadPair` — same MLP, safetensors, hot reload on mtime |
| vector cache | CUDA arena | `seon_clm.engine.VectorCache` — LRU keyed by (weights, side, text) |
| `answer` / `rank` / `models` / questions / wire schema | `clm.engine`, `clm.schema` | **reused unchanged** — `Engine` subclasses the reference |
| HTTP API + playground | `clm.server.create_app` | **reused unchanged**, plus `/v1/project` and `/v1/score` |

The reference package is installed editable from `reference-code/CLM` without
its dependencies (no torch or vLLM at run time). torch is used only to convert the
`.pt` head once and in the parity tests.

## Setup

```bash
cd src-clm
uv venv --python 3.12 .venv
uv pip install -p .venv/bin/python -e ".[test]"
uv pip install -p .venv/bin/python --no-deps -e ../reference-code/CLM
.venv/bin/hf download Qwen/Qwen3-8B                                    # ~16 GB bf16
.venv/bin/hf download Contrastive-LM/CLM-v0.1-8B CLM_v0.1-8B.pt --local-dir ~/.cache/clm
.venv/bin/seon-clm-convert ~/.cache/clm/CLM_v0.1-8B.pt                 # -> .safetensors
```

## Serve

```bash
.venv/bin/seon-clm-serve              # http://127.0.0.1:8700/ — playground, /v1/*
```

### Routes for Seon

`POST /v1/project` — vectors in the head's space, so the caller can cache card
vectors by its own key (Seon: definition digest) and score them itself:

```json
{"side": "action", "texts": ["seon.turn/system-turn — builds the opening ..."], "encoding": "base64"}
→ {"model": "clm-latest", "dim": 512, "scale": 36.2, "input_tokens": 17, "vectors": "<LE float32 [n,512]>"}
```

A state's relevance to a card is `scale * dot(state_vec, card_vec)`; both
vectors are already L2-normalised.

`POST /v1/score` — the same, server side, for probes:

```json
{"state": "<task + recent evaluations>", "instructions": "What would help the agent next?",
 "candidates": ["card 1", "card 2"], "top_k": 20}
→ {"scored": [{"index": 1, "logit": 14.1, "cos": 0.39}, ...]}
```

Logits are raw, not normalised across candidates: take the top N, then apply a
threshold fitted on labelled turns.

The reference routes (`/v1/systemone` typed questions, `/v1/rank`) are unchanged.
See `reference-code/CLM/README.md`.

## Customizing

- **Your own head:** fine-tune with `reference-code/CLM/train/finetune.py`, convert
  the `.pt`, then pass `--ckpt` or `--model NAME=PATH`. Overwriting the file hot-reloads
  it, and cached projections from the old weights stop matching.
- **In-process:** `from seon_clm import Engine; Engine(checkpoint=path).score(state, cards)`.
- **Token limit:** `--max-tokens` (default 2048, the length the head was trained at).
  States keep their last tokens.

## Tests

```bash
.venv/bin/pytest -q          # parity with the torch head, HF Qwen3Model, padding, end-to-end rank
```
