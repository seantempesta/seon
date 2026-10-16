---
title: "EmbeddingGemma 2 locally (text first), fine-tuning, modality tokenization, Proximum"
type: research
status: lane embed-model report, saved by root 2026-10-16 (the lane could not write it)
---

# EmbeddingGemma 2: local run, fine-tuning, modalities, Proximum

Lane embed-model (Sonnet), 2026-10-16. Owner: "This is the embedding model. https://huggingface.co/google/embeddinggemma-2
keep pushing on getting embeddings working in the system ... get it running locally efficiently for text first. Look for
open source implementations for how to tokenize via different modalities."

## Summary

- **The paper's model is not available locally.** arXiv 2605.27295 is "Gemini Embedding 2", Google's API model
  (`gemini-embedding-2`). The paper gives no weights, license or size, and every benchmark in it was run by Google.
- **`google/embeddinggemma-2` is the open model to use.**
  - Apache-2.0 and ungated.
  - 740M parameters in total, with a 270M text path. Outputs 768 dimensions.
  - Handles text, code, image, video and audio.
  - I found no paper for it. Its card does not cite 2605.27295, so "same family" is my inference.
- **MLX bf16 performance** on Apple silicon (128 GB; raw numbers in `bench-gemma2.txt`):

  | measure | value |
  |---|---|
  | one embedding, 100 tokens | 10.7 ms |
  | one embedding, 1k tokens | 16.5 ms |
  | one embedding, 8k tokens | 564 ms |
  | batch of 64 × 100 tokens | about 860 embeddings/s |
  | weights, all encoders | 1.49 GB |
  | peak RSS | 2.8 GB |
  | load | 0.8 s |
  | one short text over HTTP | 10.9 ms |

- **Parity** against sentence-transformers fp32 on MPS, over 50 CLAUDE.md sentences:

  | variant | min cosine | top-1 agreement |
  |---|---|---|
  | MLX bf16 | 0.99995 | 0.96 (near-tie flips) |
  | MLX 8-bit | 0.9998 | 0.98 |
  | MLX 4-bit | 0.973 | 0.80 |

  Use bf16 or 8-bit. The model card forbids float16 (NaN risk).
- **Matryoshka dimensions.** On MTEB multilingual (run by Google), 768, 512, 256 and 128 dimensions score 61.36, 61.17,
  60.41 and 57.89. Store 256: it is 3× smaller and loses about 1 point. Test 128 on our own data first.
- **Server.** `embed_server_mlx.py` serves `POST /v1/embed` with `{texts, task, dim, encoding}`; `task` adds the model's
  own prefix.
- **Fallback for heavy multimodal work: Qwen3-VL-Embedding-2B.** It runs via PyTorch MPS and matches the model card's
  similarity matrix to within 0.002. It takes 31 ms at 100 tokens and 1.49 s at 8k, and uses 4.3 GB.

**Start the server:**

```sh
cd ~/src/embed-models/embeddinggemma-2 && .venv/bin/python -I /Users/sean/src/seon/docs/research/agent-platform/2026-10-16/embedding/embed_server_mlx.py weights/embeddinggemma-2-bf16 --port 8701
```

**Setup notes.**
- Pin `mlx-vlm` at `3d87e884`. That is the revision named on the mlx-community card, and it carries the model code.
- Download with `HF_HUB_DISABLE_XET=1`. The default download stalled twice.

## Fine-tuning recipe

Quality on Seon data is untested. The parity numbers above only check that the port matches the reference.

**First: train only a head on a frozen encoder.**
- Embed each state and each action once, cached by content digest together with model, dimension and task.
- For classes, train a logistic regression or small MLP. Add nearest-centroid classification so a new class needs only
  5–20 examples.
- For ranking, train a dot-product or bilinear head with in-batch softmax.
- Use the model's prefixes: `task: classification | query: ` for forms and state, and `title: … | text: …` for
  candidate documents.
- Split train and validation by task, never by step.

**Only if the head plateaus: contrastive fine-tuning of the encoder.**
- Use sentence-transformers v6 with MultipleNegativesRankingLoss (or its Cached variant for large batches), wrapped in
  MatryoshkaLoss over 768/512/256/128.
- Optionally add 5–15 mined hard negatives per query.
- Google's fine-tuning guide for the family uses `embeddinggemma-300m` with 5 epochs at learning rate 2e-5. Its
  EmbeddingGemma 2 variant was not read.

**On Apple silicon.**
- The 270M text path should train on MPS in bf16. Not run.
- Large contrastive batches want gradient caching or CUDA.
- MLX has no contrastive trainer. Train in PyTorch, convert with `mlx_vlm convert`, then re-run `bench_gemma.py` to
  check parity.

**Evidence so far.**
- The CLM prototype's trained probe reached 74–76% on 117 labelled forms.
- A third-party model, `mrm8488/embeddinggemma-2-ft-typed-decision`, adds a typed-decision head. Only its card was read.
  It ships `custom_code`, so do not run it without a review.

## How each modality becomes tokens (not built)

Sources: the model's `processor_config.json`, the `transformers` 5.20 classes, and the installed `mlx_vlm`.

**One shared sequence.** All inputs share one 8,192-token sequence. Non-text inputs sit behind `<|image|>`, `<|video|>`
and `<|audio|>` placeholders. One input gives one mean-pooled embedding.

| modality | how it is turned into tokens |
|---|---|
| image | resized keeping aspect ratio, rescaled by 1/255 only (no normalization), 16×16 patches with 3×3 pooling; 280 tokens by default, configurable from 70 to 1,120 |
| video | 1 frame per second, up to 32 frames, 140 tokens per frame, no timestamps |
| audio | mono 16 kHz, 128-bin mel features (20 ms frames every 10 ms), 25 tokens per second |
| code | plain text with the prefixes `task: code retrieval \| query: ` and `title: {filename} \| text: {code}` |

**Implementations to read:**
- `transformers/models/embedding_gemma2/*`
- `mlx_vlm/models/embedding_gemma2` and `mlx_vlm/embedding_loader.py`
- `ggml-org/embeddinggemma-2-GGUF`, which ships `mmproj` files, so llama.cpp's multimodal path should work (not run)
- ONNX, LiteRT and CoreML builds exist (seen by name only)

**MLX status.** The MLX bf16 checkpoint already contains the vision and audio encoders. The card describes loading the
text, image or audio encoders selectively in PyTorch (270M, 440M, 570M). No equivalent switch was found in `mlx_vlm`.

## Proximum / Datahike (for lane vector-db)

**The old claim is true, and the cost is in our own fork.** `docs/prds/next-turn/synthesis.md` line 195 ("sync hashes
the full index per commit") cites no source. The code behind it is `proximum.vectors/publish-mmap-generation!` at gitlib
`9846d3e`, triggered on every commit by `datahike.index.secondary.proximum/-sec-flush`. Datahike's
`doc/secondary-indices.md` says the same.

**What one commit does.**
- Three full-file SHA-256 passes, a full file copy, and an fsync.
- All of it is proportional to the preallocated mmap size (capacity × dimension × 4 bytes), not to the vectors changed.
- The vector and edge chunks themselves are already written incrementally.
- With `:crypto-hash? true`, the commit id is already a hash chain over the new chunk hashes. So the incremental design
  is already in Proximum, and only the generation file defeats it.

**Measured with `proximum_bench.clj`** on a scratch store, in its own JVM: dimension 1024, cosine distance, capacity
20,000 (an 82 MB mmap). One commit is one `d/transact` of 250 vectors, including the graph inserts.

| measure | stock | patched |
|---|---|---|
| commit at N=0 | 1,599 ms | 336 ms |
| commit at N=9,750 | 2,765 ms | 1,471 ms |
| one-vector commit | 634 ms | 265 ms |
| branch | 153 ms | 61 ms |
| mmap directory on disk at N=9,750 | 3.2 GB | 0.84 GB |
| kNN, k=10 over 10,000 vectors | 2.9 ms per query | 1.6 ms per query |

The stock build keeps a full generation per commit: 3.4 GB after 40 commits.

**The patch** (`proximum-incremental-generation.patch`; not committed, no dependency bump):
- Uses `/bin/cp -c` (the APFS clonefile) on macOS. The stock code calls `cp --reflink`, which is GNU syntax and fails on
  macOS, so here every branch and every generation is a full copy.
- Hashes only the used prefix of the file, not its whole capacity.
- Stops re-hashing a freshly cloned file before the move.

It is still O(N) in the bytes in use.

**The real incremental fix (not written).** Use the chunk chain that `sync!` already computes as the generation's
evidence. Full verification would then run only on restore or audit (`materialize-working-mmap!`, `proximum.audit`).
Crash safety of the guarded-force path, which reads `:mmap-generation-sha256` from the snapshot, is untested. Any change
to that evidence goes through the fork's guarded-force tests in `secondary_integration_test.clj`.

**The API as it exists.**
- **Declare:** `{:db/ident :idx/vectors :db.secondary/type :proximum :db.secondary/attrs [:doc/vec] :db.secondary/config
  {:dim 768 :distance :cosine :capacity N :store-config {:backend :file :path P :id uuid} :mmap-dir D}
  :db.secondary/status :ready}`. The konserve `:id` is required, and Proximum needs Java 22+.
- **Insert:** an ordinary `d/transact` of `{:doc/vec [floats]}`. A retraction deletes the vector.
- **Query:** there is no Datalog clause. Call `(sec/-search (get-in db [:secondary-indices :idx/vectors]) {:vector
  (float-array q) :k 10} entity-filter)`, which returns an entity bitset, or `sec/-slice-ordered` for
  `{:entity-id :distance}`. Bind the ids into Datalog with `:in [?e ...]`.
- **`:db.secondary/only true`:** the primary indices store only a `hasch` content hash, and the vector lives in Proximum
  alone. Vectors are then searchable but cannot be read back.
- **Branching:** `branch!` forks the index through `proximum.versioning/branch!`.
- **GC:** `-sec-mark` returns `#{}`, so Datahike's GC mark does not trace Proximum chunks. The docs say konserve-backed
  indices are swept by age.
- **Older audits disagree with the current direction.** `fork-audit-datahike-2026-09-23.md` §K and
  `fork-audit-others-2026-09-23.md` recommended dropping Proximum, which conflicts with the owner's direction now.

## Gaps

- No fine-tuning job was run, and EmbeddingGemma 2 was not run on any image, audio or video input.
- The Qwen3-VL-Embedding-8B download was started and stopped. The MLX 2B 4-bit port was downloaded but not run.
- The MLX server loads every encoder. No text-only load switch was found in `mlx_vlm`.
- The Proximum patch is only a first version.
- Next experiment: embed the 117 labelled forms from `clm/` with the classification prefix and train a head. Compare it
  against the CLM probe (74–76%) and the char-trigram baseline (71–74%).

## Files

- **Outside the repo** (models and venvs): `~/src/embed-models/embeddinggemma-2/` and
  `~/src/embed-models/qwen3-vl-embedding/`.
- **In this directory:**
  - `embed_server_mlx.py`, `bench_gemma.py`, `bench-gemma2.txt`
  - `embed_server.py`, `bench_embed.py`, `bench-2b-bf16.txt`
  - `proximum_bench.clj`, `proximum-incremental-generation.patch`
