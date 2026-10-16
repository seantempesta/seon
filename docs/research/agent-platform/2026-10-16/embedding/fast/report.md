---
title: "EmbeddingGemma 2, fast text-only path on MLX"
type: research
status: lane embed-fast (Opus) report, 2026-10-16; saved by root (the lane could not write it)
---

# EmbeddingGemma 2: fast text-only embedding on MLX

Lane embed-fast, 2026-10-16. Owner: "get a high performance version of the gemmaembedding 2 model so we can cheaply
embed everything." This builds on lane embed-model's run ([../report.md](../report.md), ce071b61e).

Machine: Apple M5 Max, 128 GB, MLX 0.32.4. Four busy JVMs shared the machine (load average 13-23), so timings vary by
about ±20% between runs. No Seon code was edited and the cluster was not touched.

## Chosen configuration

The chosen setup has four parts:

- **Weights:** a text-only bf16 checkpoint, `~/src/embed-models/embeddinggemma-2/weights/eg2-text-bf16`. It holds only
  the `language_model.*` tensors: 542 MB, against 1.49 GB with every encoder.
- **Code:** our own module, `gemma_text.py`, which needs only mlx, numpy and tokenizers. It mirrors
  `mlx_vlm.models.embedding_gemma2` at 3d87e884, with two changes:
  - Sliding-window layers use banded 512-block attention when the input is over 1,536 tokens.
  - RoPE uses the fused `mx.fast.rope`.
- **Batching:** inputs are sorted into length buckets and batched up to 16,384 padded tokens per GPU batch, with two
  batches in flight. `mx.compile` is off: it gave no measured gain (see below).
- **Dimension:** Matryoshka output at 768, 512, 256 or 128 per request, truncated and re-normalized. Store 256.

| measure | embed-model baseline (mlx_vlm, all encoders) | this lane |
|---|---|---|
| weights | 1.49 GB | **542 MB** |
| load | 0.8 s | **0.3-0.4 s** |
| peak RSS | 2.8 GB | **1.0 GB server, 1.2-1.6 GB batch** (MLX peak 2.9 GB at the 16k budget) |
| one text, 100 tokens | 10.7 ms | **5.7 ms** (4.0 ms at 50 tokens) |
| one text, 1k tokens | 16.5 ms | 15-21 ms (noisy) |
| one text, 8k tokens | 564 ms | **193-216 ms** |
| batched, 100 tokens | about 860 emb/s | **1,055 emb/s** (105k tokens/s) |
| batched, 50 tokens | – | **1,921 emb/s** |

**Throughput by length bucket** (bf16, no compile):

| tokens | one item | best batched | tokens/s |
|---|---|---|---|
| 50 | 4.0 ms | 0.52 ms (1,921/s) | 96k |
| 100 | 5.7 ms | 0.95 ms (1,055/s) | 105k |
| 200 | 5.9 ms | 1.83 ms (547/s) | 109k |
| 500 | 8.6 ms | 5.8 ms (172/s) | 86k |
| 1,000 | 20.8 ms | 15.9 ms (63/s) | 63k |
| 2,000 | 38.8 ms | 38.8 ms (26/s) | 52k |
| 4,000 | 91.7 ms | 91.7 ms (11/s) | 44k |
| 8,000 | 216 ms | 216 ms (5/s) | 37k |

**Batch size.**
- Up to 200 tokens, batching pays up to 16-64 items and is flat beyond about 32.
- From 1,000 tokens, 1-4 items already fill the GPU.
- Cap the total tokens per batch (16k), not the item count:
  - A 32k cap once made a second pass take 93 s instead of 20 s. Not investigated.
  - With no cap, 32 texts of 8k tokens took 31 GB.

## Whole-corpus run (reads files only)

The corpus is every top-level form in `src/**/*.clj` (4,875 items; a form starts at a column-0 `(`) plus every `##`
section of `docs/seon/**/*.md` (4,134 items).

- **Size:** 9,009 items, 2.46M tokens. Token-length percentiles for the forms are p10 49, p50 200, p90 623, p99 1,766;
  the longest is 7,723. Padding overhead is 1.05×.
- **Time:** 37.2 s embedding, **39.1 s wall** (66k tokens/s, 240 emb/s). Forms alone are 1.47M tokens in 23.9 s.
- **Memory and output:** 1.6 GB RSS; 256 dimensions in float16, 4.9 MB of vectors.
- **Re-runs:** 0.97 s with the cache, nothing re-embedded. One new item takes 21 ms to embed plus about 0.5 s to start
  the process.

## Parity

**Reference:** sentence-transformers fp32 on MPS, one text at a time, over 200 texts, each with its real prefix (code
for forms, document for docs, clustering for prose):
- 50 CLAUDE.md paragraphs;
- 110 forms spread from 13 to 7,723 tokens;
- 40 doc sections.

| variant | min cosine at 768 | mean | top-10 overlap | top-1 | min cosine at 256 | min cosine at 128 |
|---|---|---|---|---|---|---|
| mlx_vlm bf16 (baseline) | 0.999823 | 0.999965 | 0.988 | 0.975 | 0.999830 | 0.999889 |
| **gemma_text bf16 (chosen)** | **0.999911** | 0.999965 | **0.9885** | 0.970 | 0.999924 | 0.999947 |
| gemma_text bf16, reference rope | 0.999917 | 0.999965 | 0.985 | 0.945 | 0.999919 | 0.999943 |
| gemma_text 8-bit | 0.999690 ✗ | 0.999838 | 0.9745 | 0.970 | 0.999719 | 0.999818 |

**Reading the table.**
- The chosen configuration passes the ≥0.9998 bar and is closer to fp32 than the baseline.
- The banded attention is exact: the 7,723-token form takes that path and is not among the worst cases.
- Top-1 stays below 1 for every bf16 variant, the baseline included. These are near-tie flips, so top-10 overlap is the
  better ranking measure.

**Rejected variants.**
- **8-bit:** parity 0.99969, below the bar. It is also slower, because embedding is compute-bound (613 vs about 990
  emb/s at 128 tokens), and saves only 254 MB.
- **fp32 compute:** 0.6× the speed and 1.08 GB more, with no parity gain.

## What helped and what did not

1. **Text-only weights.** Load drops from 0.8 s to 0.3 s and memory from 2.8 GB to 1.0 GB: the vision and audio
   encoders were 950 MB of unused weights. `make_text_only.py` writes the checkpoint.
2. **Banded sliding-window attention.** 20 of the 24 layers look only 512 tokens either side, but the reference computes
   them as full L×L masked attention. Splitting into 512-token blocks, each seeing three blocks, makes their cost linear
   in length (O(L·1536)). At 8k tokens, 564 ms becomes about 200 ms, with exact results.
3. **Length buckets under a token budget.** Lengths pad to a multiple of 16 up to 512 tokens, 64 up to 1,536, and 512
   beyond. Padding overhead on the real corpus is 1.05×, and the next batch is built while the GPU runs the current one.
4. **`mx.compile` is not worth it.** It gained 15-25% on short microbenchmarks, but over the whole forms corpus it took
   22.42 s against 22.35 s without. Each new batch shape costs 10-400 ms to compile, and a server sees many shapes. Off
   by default; `compiled=True` or `--compile` turns it on.
5. **Fused `mx.fast.rope`.** Same parity, timing within noise. It is the default; `EG2_FAST_ROPE=0` gives the
   reference rounding.
6. **Remaining cost.** Attention on the wide 256- and 512-dimension heads between 500 and 2,000 tokens: throughput falls
   from about 105k to about 50k tokens/s over that range. MLX's fused attention already beats a hand-written version
   (1.9 vs 3.3 ms), and no cheaper exact form was found.

## Server (`server.py`)

```sh
~/src/embed-models/embeddinggemma-2/.venv/bin/python -I \
  /Users/sean/src/seon/docs/research/agent-platform/2026-10-16/embedding/fast/server.py \
  ~/src/embed-models/embeddinggemma-2/weights/eg2-text-bf16 --port 8701
```

**Endpoints.**
- `POST /v1/embed` takes `{"texts": [...], "task": "code"|"document"|"query"|"classification"|"clustering"|"similarity"|"qa"|"fact"|null, "dim": 768|512|256|128, "encoding": "list"|"f32"|"f16", "max_tokens": 8192}`
  and returns `{model, dim, n, input_tokens, ms, encoding, vectors}`. With `f32` or `f16`, the vectors are base64 of a
  little-endian `[n, dim]` array.
- `GET /healthz` and `GET /v1/models`.

**Batching.** One worker thread owns MLX. Requests that arrive while the GPU is busy are merged into one bucketed batch;
`--linger-ms` optionally waits longer to merge more. Built on Python's standard-library HTTP server, without FastAPI.

**Load test** (`loadtest.py`, code forms of about 100 tokens; RSS 1.0 GB throughout):

| load | result |
|---|---|
| one text at a time, 256 dims, f16 | p50 8.9 ms, p99 17.3 ms over HTTP |
| 8 concurrent clients | 267 emb/s |
| 32 concurrent clients | 506 emb/s (p50 63 ms) |
| 64 concurrent clients | 689 emb/s (p50 92 ms) |
| one request of 2,000 forms (602k tokens) | 9.7 s, 62k tokens/s |

## Batch CLI (`embed_batch.py`)

```sh
PY=~/src/embed-models/embeddinggemma-2/.venv/bin/python; F=/Users/sean/src/seon/docs/research/agent-platform/2026-10-16/embedding/fast
$PY -I $F/corpus.py forms /Users/sean/src/seon forms.jsonl          # or: docs | parity
$PY -I $F/embed_batch.py ~/src/embed-models/embeddinggemma-2/weights/eg2-text-bf16 forms.jsonl forms.safetensors \
    --task code --dim 256 --cache embed-cache/ [--dtype float16|float32] [--budget 16384]
```

**Input and output.** The input is JSONL of `{id, text}`. The run writes three files:
- `OUT.safetensors`, holding `vectors` and `digests`;
- `OUT.npy`, holding the vectors only;
- `OUT.ids.jsonl`, holding `{id, digest}` in row order.

**Cache.**
- Each vector is keyed by `sha256(model \0 task \0 dim \0 text)`. The model name is in the key so new weights never
  reuse old vectors.
- `--cache DIR` loads every `shard-*.safetensors` first, embeds only the misses, and appends them as one new shard.
- Duplicate texts are embedded once.

**Task per run.** Run once per kind: `--task code` for forms, `--task document` for docs. For a titled document,
pre-format the text as `title: T | text: C` and pass `--task none`.

## Not done

- No comparison with llama.cpp: it is not installed, and building it plus downloading the GGUF did not fit the time box.
- Quality on Seon's own data is not measured. This lane checked only that the port matches the fp32 reference.
- Cache shards accumulate; nothing compacts them yet.

## Files

All scripts are in this directory and use the embed-model venv.

| file | what it does |
|---|---|
| `gemma_text.py` | the encoder |
| `make_text_only.py` | writes the text-only checkpoint (`--bits 8` for 8-bit) |
| `server.py` | the HTTP server |
| `embed_batch.py` | the batch CLI |
| `corpus.py` | builds the corpora |
| `parity.py` | parity checks (`ref` / `ours` / `--vlm`) |
| `bench.py` | throughput benchmark |
| `loadtest.py` | server load test |

**Outside the repo:**
- Checkpoints in `~/src/embed-models/embeddinggemma-2/weights/`: `eg2-text-bf16` (542 MB) and `eg2-text-8bit` (288 MB,
  not recommended).
- Corpora, reference vectors and outputs were in the session scratchpad and were disposable.
