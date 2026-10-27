# The reranker benchmarked (2026-10-27)

- File: `bge-reranker-v2-m3-Q8_0.gguf` (BAAI's bge-reranker-v2-m3, a 568M-parameter XLM-RoBERTa cross-encoder, quantized Q8_0 by gpustack).
- Source: https://huggingface.co/gpustack/bge-reranker-v2-m3-GGUF at repository commit 3093af03b1a635e67b084b1d8c03c5f5e020fd05 (last modified 2024-09-29).
- Size: 635,676,416 bytes. sha256: a43c7c9b11a4c1517e5bf95151960e1621d1b72f7a493364b01e386cf1aaa1d3 (checked after the download).
- Held in the main tree's ignored model cache, named by its sha256 as the embedding model is: `target/cache/models/a43c7c9b….gguf`. Nothing installed system-wide.
- Run by the pinned llama.cpp (`reference-code/llama.cpp`, v0.7.0, 620d2225c), its `llama-embedding` example built into `target/llama-rerank/` with `--pooling rank`.
- Approved by the owner in this session's chat, 2026-10-27.
