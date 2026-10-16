"""EmbeddingGemma 2 text bench/parity. Usage:
  bench_gemma.py st  WEIGHTS_DIR DTYPE(fp32|bf16) CORPUS.txt REF.npy [--save-ref]
  bench_gemma.py mlx WEIGHTS_DIR LABEL           CORPUS.txt REF.npy
CORPUS.txt: one text per line. Compares cosine vs the ST fp32 reference saved in REF.npy."""
import sys, time, resource, numpy as np
mode, path, third, corpus, ref = sys.argv[1:6]
PFX = "task: clustering | query: "
texts = [PFX + l.strip() for l in open(corpus) if l.strip()][:50]
rss = lambda: resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e9
base = open(corpus).read() * 30
def toks_text(tok, n):
    ids = tok(base, add_special_tokens=False)["input_ids"][:n]; return tok.decode(ids)
if mode == "st":
    import torch
    from sentence_transformers import SentenceTransformer
    dt = {"fp32": torch.float32, "bf16": torch.bfloat16}[third]
    t0 = time.time()
    m = SentenceTransformer(path, device="mps", model_kwargs={"torch_dtype": dt}, config_kwargs={"vision_config": None, "audio_config": None})
    print(f"[st {third}] load {time.time()-t0:.1f}s, params {sum(p.numel() for p in m.parameters())/1e6:.0f}M, mps alloc {torch.mps.current_allocated_memory()/1e9:.2f} GB")
    enc = lambda xs, bs=32: m.encode(xs, batch_size=bs, normalize_embeddings=True, convert_to_numpy=True).astype(np.float32)
    sync = torch.mps.synchronize; tok = m.tokenizer; m.max_seq_length = 8192
else:
    import mlx.core as mx
    from transformers import AutoTokenizer
    from mlx_vlm.embedding_loader import load_embedding_model
    t0 = time.time(); from pathlib import Path; path = Path(path); model = load_embedding_model(path); tok = AutoTokenizer.from_pretrained(path)
    print(f"[mlx {third}] load {time.time()-t0:.1f}s, mlx active {mx.get_active_memory()/1e9:.2f} GB")
    def enc(xs, bs=32):
        out = []
        for i in range(0, len(xs), bs):
            b = tok(xs[i:i+bs], padding=True, truncation=True, max_length=8192, return_tensors="np")
            e = model(**{k: mx.array(v) for k, v in b.items()}).text_embeds
            e = e / mx.linalg.norm(e, axis=-1, keepdims=True); mx.eval(e); out.append(np.array(e.astype(mx.float32)))
        return np.concatenate(out)
    sync = lambda: None
E = enc(texts)
if "--save-ref" in sys.argv: np.save(ref, E); print("saved ref", E.shape)
R = np.load(ref); cos = (E * R).sum(1)
print(f"parity vs ST fp32 (n={len(E)}): cos min {cos.min():.5f} mean {cos.mean():.5f}")
# ranking parity: top-1 neighbour agreement and MRL cosine
S, SR = E @ E.T, R @ R.T; np.fill_diagonal(S, -9); np.fill_diagonal(SR, -9)
print("top-1 neighbour agreement:", float((S.argmax(1) == SR.argmax(1)).mean()))
for k in (128, 256, 512):
    a = E[:, :k]; a = a / np.linalg.norm(a, axis=1, keepdims=True); r = R[:, :k]; r = r / np.linalg.norm(r, axis=1, keepdims=True)
    print(f"  MRL {k}: cos vs fp32 same-dim {float((a*r).sum(1).mean()):.5f}")
for n in (100, 1000, 8000):
    t = PFX + toks_text(tok, n); enc([t], 1); sync(); ts = []
    for _ in range(5):
        a = time.perf_counter(); enc([t], 1); sync(); ts.append((time.perf_counter() - a) * 1000)
    print(f"{n} tok: median {np.median(ts):.1f} ms")
for bs in (16, 64):
    xs = [PFX + toks_text(tok, 100) + f" #{i}" for i in range(bs)]
    enc(xs, bs); sync(); a = time.perf_counter(); enc(xs, bs); sync(); d = time.perf_counter() - a
    print(f"batch {bs} x100tok: {bs/d:.0f} emb/s")
print(f"peak RSS {rss():.2f} GB")
