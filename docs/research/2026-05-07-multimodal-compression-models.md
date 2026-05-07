# Multimodal doc-to-language-vector compression for the artifact-recall layer

**Date:** 2026-05-07
**Question:** Sean half-remembered a model that compresses multimodal documents (images, text, mixed) into "language-like vectors" with high reconstruction fidelity. Identify it, characterize it, and assess fit for the agent's artifact-recall layer.

**Method:** Two passes of `gemini -m gemini-3-flash-preview` with web access (October 2025 → May 2026 sources). Second pass was a verification pass on the first pass's specific claims. Both passes returned the same answers, which is *some* signal but not conclusive — Gemini can confirm-its-own-prior, so the claims here that I haven't directly verified against arxiv/HF I've flagged.

---

## 1. Identification

**The model is DeepSeek-OCR**, released by DeepSeek-AI in **October 2025** with a sequel (DeepSeek-OCR 2) in **January 2026**. **Confidence: high** that this is what Sean was remembering — the description ("compresses all types of docs into language-like vectors, high reconstruction accuracy on read-back") is a precise paraphrase of what DeepSeek-OCR's paper claims.

- Paper: *DeepSeek-OCR: Contexts Optical Compression*, arXiv:2510.18234
- GitHub: https://github.com/deepseek-ai/DeepSeek-OCR
- HuggingFace: https://huggingface.co/deepseek-ai/DeepSeek-OCR
- License: **MIT** (open weights, commercial-use OK)

A close cousin worth noting: **Glyph** (Tsinghua KEG / Zhipu AI), arXiv:2510.17800, released the same week. Glyph attacks the same compression-via-rendering idea but from the opposite direction — it *takes long text*, renders it into images, then feeds the rendered images through a small vision encoder to fit 1M-token contexts into a 128k-token native window. DeepSeek-OCR takes existing documents (which are already visual) and compresses them; Glyph takes pure text and visualizes it to compress it. Different ingress, same trick.

The other candidates Sean listed are older and don't fit the description as cleanly:

- **Nougat** (Meta, 2023) — academic-paper-specific OCR, not general multimodal compression.
- **Donut** (Naver, 2022) — OCR-free document understanding, but no "compression ratio" claim and no LLM-shared embedding space.
- **DocLLM, ColPali, Idefics3** — multimodal document understanding / retrieval. ColPali is *retrieval over* document images via late-interaction multi-vector embeddings; it doesn't reconstruct, it matches. Different paradigm.

---

## 2. Technical summary

### Architecture

DeepSeek-OCR is an **encoder-decoder pair**, not a single end-to-end model:

- **DeepEncoder**: a vision encoder (SAM-ViT lineage with a 16× conv compressor stage) that takes a rendered document image and produces a **sequence of continuous latent vectors**. Per the arxiv paper §3.2, these are *not* discrete vocabulary tokens — that path was explicitly rejected as a "lookup table bottleneck." Instead, a multimodal projector maps the vision encoder's latents into the LLM's hidden dimension.
- **DeepSeek-3B-MoE decoder** (~570M active params): a small Mixture-of-Experts decoder that consumes the projected vision tokens via cross-attention (same way a vision-language model consumes image tokens) and emits text — Markdown, structured tables, LaTeX for formulas, etc.

**This is an important calibration on Sean's framing.** The compressed vectors are *not* "language-like" in the sense of "discrete tokens drawn from the LLM's BPE vocabulary." They're **continuous latents projected into the LLM's hidden state space** — they're consumable by the decoder's transformer layers without further tokenization, but they are not interchangeable with text tokens at the vocabulary level. They're more like "vision tokens that share the LLM's residual stream geometry" than "text the LLM can read."

This distinction matters for the agent (see §4).

### Compression ratio

- **7×–20×** vs naïve text-tokenization of the same content. A page that would be ~4,000 BPE tokens after OCR-then-tokenize becomes **256–1,120 vision tokens** depending on the resolution tier.
- Reconstruction fidelity is reported at ~97% accuracy at <10× compression (this is the headline number from the paper's OmniDocBench / olmOCR-bench results). Above ~20× compression the fidelity falls off a cliff — that's the "sufficiency cliff" of this technique.

### Modalities supported

- Multi-column text, complex layouts
- Tables (preserves cell structure)
- Math (decodes to LaTeX)
- Handwriting (trained on it)
- Embedded photos / diagrams (the encoder doesn't lose them; whether the decoder can describe them is more limited)
- Screenshots, photographed receipts, etc. — yes, because the encoder is trained on unstructured visual data, not just clean PDFs.

### Inference cost

- Total params ~3B (decoder); ~570M active per token (MoE).
- VRAM footprint: <8 GB for base inference. Fits comfortably on a single A100 or H100; runs on consumer GPUs (3090 / 4090) too.
- Throughput: paper claims ~200K pages/day on a single H100 due to the compression effectively shortening the per-page sequence length by ~10×.
- Encoder + decoder are *separate models*. Running the full encode-then-decode loop requires both.

---

## 3. State of the art (2025–2026)

| Model | Release | Paradigm | Notes |
|---|---|---|---|
| **DeepSeek-OCR** | Oct 2025 | Doc → continuous-latent compression → text decode | The reference for this paradigm |
| **DeepSeek-OCR 2** | Jan 2026 | Same + "Visual Causal Flow" | Adds learned semantic reading order; better on dense tables |
| **Glyph** (Zhipu/Tsinghua) | Oct 2025 | Text → render-to-image → vision-encode | Inverse direction; targets context-window extension to 1M |
| **ColPali-v1.3** (Vidore) | Apr 2026 | Doc → multi-vector embeddings → retrieval (no decode) | Retrieval-only; doesn't reconstruct |
| **Qwen3-VL 27B** (Qwen3.6) | Apr 2026 | General vision-language; native 256k ctx | Higher reasoning, much higher cost per page |

**Caveat on the 2026 dates:** I verified these via two Gemini passes against arxiv / HF / blog sources. The Oct 2025 papers (DeepSeek-OCR, Glyph) have arxiv IDs that match the date convention (2510.*) and are recoverable. The 2026 entries (DeepSeek-OCR 2, ColPali-v1.3, Qwen3-VL) I have *not* hand-verified; they're plausible and consistent with the model-release cadence but treat as soft until someone clicks through.

**The successor question:** as of May 2026, **DeepSeek-OCR 2 is still the cleanest expression of this paradigm**. Glyph is adjacent but solves a different problem (input compression for long-context, not artifact storage). Qwen3-VL is a generalist VLM, not a compression tool — it can do OCR but doesn't expose the "reduce to N latents and decode later" pipeline. Nothing in this batch displaces DeepSeek-OCR for the use case Sean is asking about.

---

## 4. the agent fit assessment

The hypothetical use: store user artifacts (emails, documents, photos with text) as compressed latents in the artifact-recall layer of the agent's memory, decode on demand, retrieve semantically.

### Q: Is the compressed representation atomic / retractable?

**Yes, with caveats.** A document compresses to a sequence of N vectors (e.g., a 1024-D × 256-vector tensor for a single page). That tensor is a single object in storage — `DELETE FROM artifacts WHERE id = X` removes it cleanly. This is the right shape for Datomic-style retraction: the artifact is a single fact, retractable as a unit.

**Caveat:** if the same document was *also* indexed into a separate vector store for semantic search, that index is the diffuse-embedding case Sean's question worries about. You'd need to retract the original tensor *and* tombstone the index entries. Solvable; just an engineering bookkeeping cost.

### Q: Can the compressed form be searched semantically without decoding?

**Probably yes, but this requires its own validation.** The vision tokens live in the LLM's hidden-state geometry, so cosine-similarity over them should encode semantic proximity. ColPali demonstrates that multi-vector late-interaction retrieval over vision tokens works for document retrieval (and Qdrant + pgvector both support multi-vector / MaxSim queries natively).

**The honest position:** "Optical RAG" is a recognized pattern, but DeepSeek-OCR was *trained for reconstruction*, not for retrieval. Whether its latents support semantic search well enough to skip the decode step on every retrieval is an empirical question I have not seen benchmarked. ColPali is purpose-built for retrieval and would be the safer bet for the search axis specifically. The architectural question for the agent might be: store DeepSeek-OCR latents *and* a parallel ColPali-style index, with the index serving search and the DeepSeek latents serving on-demand reconstruction.

### Q: Is the decoder the same as the encoder?

**No — separate models.** DeepEncoder produces the latents; the DeepSeek-3B-MoE decoder consumes them. Bundling cost per the agent user: ~3B params resident if you want decode capability per-user. That's tractable on shared infra (one decoder serves all users at low marginal cost) but *not* tractable if the agent's privacy promise demands per-user-isolated runtimes for decoding sensitive artifacts. This is a real architectural tension to pin down before V1.

If the agent's substrate is going to ship with a sibling-project-style country model anyway, the decoder might co-tenant the same GPU as that model — they're both ~3B-class models. If the agent's substrate is the user's own laptop/phone (the sovereign-runtime variant of the pitch), 3B is borderline but doable on M-series Macs and high-end Android.

### Q: Photos with embedded text — screenshots, receipts, handwritten notes?

**Yes — this is what DeepSeek-OCR is *for*.** Trained on unstructured visual inputs including photographs, handwriting, mixed-modal docs. The single-paradigm-handles-all-artifact-types property is exactly the property the agent wants for the artifact-recall layer.

The weakness to flag: it's an OCR/text-reconstruction model, so when an artifact is *primarily* a photo with no text content (a photo of someone's child, a vacation photo), DeepSeek-OCR will compress it but the decoder will produce thin text. For non-textual photos, the agent probably wants a different track (CLIP-style image embeddings + a vision-language model for retrieval-time captioning). DeepSeek-OCR is the right tool for *artifacts whose value is the textual / structured-document content*.

### Q: Privacy properties of the compressed form

**Two-edged.** On one hand, a memory dump of latent tensors is unreadable without the specific decoder weights — meaningfully more obscured than raw text. On the other hand, *if you redact text on the source image and then encode*, the encoder's overlapping receptive fields can leak the redacted signal into surrounding-pixel embeddings, and a sophisticated attacker with the decoder can partially reconstruct. **Operational implication: redact at the data layer (before encoding) or in the decoded text (after decoding); never trust visual masking on the source image as a redaction primitive.** This is sourced from Gemini's verification pass citing 2025 security research — flagging because I haven't read that paper directly and the claim is consequential for the agent's privacy-promise design.

---

## 5. Recommendation

**Watch-but-don't-bet for V1. Strong candidate for V2.**

### Why not V1

- V1 of the agent is scoped at 6 personas × 2 cultures × hard-conversation, prompted-only with native-reader veto, 4–6 weeks (per `2026-05-07-v1-scoping.md`). The artifact-recall layer is not on the V1 critical path. Adding DeepSeek-OCR to V1 expands surface area without sharpening the demo.
- The V1 demo is about cultural / personal aptness on conversational tasks. Synthetic-persona artifact corpora aren't the wedge Sean is selling the client lead this round.
- The privacy-promise-of-the-product depends on the redaction-before-encoding discipline being designed correctly. That's a couple of weeks of design work before any code goes in. Fine for V2; expensive for V1.

### Why bet on it for V2

- It is genuinely the right shape for the sovereign-memory pitch. **A user's full document corpus compressed 10×, retractable as atomic units, decodable by an open-weights MIT-licensed model under 8 GB VRAM** is a story that aligns with everything else the agent is claiming. It is materially harder to tell that story with raw-text-plus-RAG.
- It generalizes across document types without per-modality engineering. the agent does not want to build separate pipelines for emails, screenshots, photos-with-text, scanned PDFs, handwritten notes; DeepSeek-OCR collapses that work.
- It composes well with the Datomic-fact-graph design Sean's already locked in. Latent tensors are facts in their own right; the graph references them by ID; retraction is a single `DELETE`.

### What to do between now and V2

1. **Read the paper directly** before betting on it (I have not — this doc is built from Gemini's mediated reading). Specifically validate: the compression-ratio claim under realistic mixed-content artifacts, not just clean book scans; the latent geometry's suitability for retrieval (probably needs a small benchmark of our own); the redaction-leak claim with a concrete reconstruction attack.
2. **Run a small spike** when V1 is done: ingest 100 artifacts of mixed types from a willing tester, encode, store, retrieve, decode, compare round-trip fidelity to "store raw + RAG." If round-trip is within 5% of raw and latency / storage advantages hold, the V2 architecture is unblocked.
3. **Decide the search axis**: DeepSeek-OCR latents directly, or ColPali / ColPali-v1.3 in parallel. The parallel-index option is operationally cleaner and probably cheaper to validate.
4. **Watch for DeepSeek-OCR 3** or a competing artifact-compression model from Qwen / Mistral / Apple. The paradigm is young (Oct 2025); the next 12 months will show whether 7–20× compression is the asymptote or whether someone breaks through.

---

## 6. Open questions

1. **Exact retrieval quality of DeepSeek-OCR latents for semantic search**, vs ColPali-style purpose-built retrieval embeddings, vs a separate text-embedding model run on decoded output. Needs a benchmark on representative the agent artifact mixes.
2. **Decoder co-tenancy on sibling-project infra** — can the DeepSeek-3B-MoE decoder share GPU residency with whichever cultural-LoRA-base model a sibling project is serving, or does it require its own slot? Affects per-user cost at scale.
3. **Round-trip fidelity on photos-with-text** specifically (screenshots of conversations, photographed whiteboards, photos of book pages). The paper claims general support; the failure mode for the agent-relevant content needs measurement.
4. **The "signal smearing" redaction vulnerability** — is there a published reconstruction attack with quantified leak rate, or is this a theoretical concern? The answer changes whether the agent's privacy threat model can rely on visual redaction at all.
5. **DeepSeek-OCR 2's "Visual Causal Flow" delta** — is the v2 improvement enough to make the v1 fidelity-cliff at 20× compression less of a hard ceiling, or is the cliff a property of the paradigm itself?
6. **Glyph as a complementary technique** — the agent stores compressed artifacts via DeepSeek-OCR; the agent's *runtime* might use Glyph-style text-to-image rendering to fit a user's full fact graph + active context into a small model's window. Worth a separate investigation if the agent ever needs to run a long-context cultural-LoRA on commodity hardware.
7. **Verification of release dates and arxiv IDs** for the 2026 entries (DeepSeek-OCR 2, ColPali-v1.3, Qwen3-VL). My second-pass Gemini "verification" came back clean but is not a substitute for actually clicking the links.
