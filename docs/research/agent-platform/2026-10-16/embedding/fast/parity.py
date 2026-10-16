"""Parity of MLX variants against the sentence-transformers fp32 (MPS) reference.
  python -I parity.py ref   HF_DIR    CORPUS.jsonl REF.npy      reference (torch fp32, MPS, batch 1: no padding effects)
  python -I parity.py ours  MLX_DIR   CORPUS.jsonl REF.npy [--no-compile] [--vlm]   compare one variant
Prefixes: ids under src/ get the code prefix, docs/ the document prefix, prose the clustering prefix."""
import sys, json, time, numpy as np
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
mode, path, corpus, ref = sys.argv[1:5]
rows = [json.loads(l) for l in open(corpus)]
def pfx(r):
    return ("task: code retrieval | query: " if r["id"].startswith("src/") else
            "title: none | text: " if r["id"].startswith("docs/") else "task: clustering | query: ")
texts = [pfx(r) + r["text"] for r in rows]
if mode == "ref":
    import torch
    from sentence_transformers import SentenceTransformer
    m = SentenceTransformer(path, device="mps", model_kwargs={"torch_dtype": torch.float32}, config_kwargs={"vision_config": None, "audio_config": None})
    m.max_seq_length = 8192; t0 = time.time()
    E = np.concatenate([m.encode([t], batch_size=1, normalize_embeddings=True, convert_to_numpy=True) for t in texts]).astype(np.float32)
    lens = [len(m.tokenizer(t)["input_ids"]) for t in texts]
    np.save(ref, E); print(f"ref {E.shape} in {time.time()-t0:.1f}s; tokens min {min(lens)} median {int(np.median(lens))} max {max(lens)}"); sys.exit()
R = np.load(ref)
if "--vlm" in sys.argv:
    import mlx.core as mx
    from transformers import AutoTokenizer
    from mlx_vlm.embedding_loader import load_embedding_model
    model = load_embedding_model(Path(path)); tok = AutoTokenizer.from_pretrained(path)
    def run(xs, dim=None):
        out = []
        for t in xs:
            b = tok([t], truncation=True, max_length=8192, return_tensors="np")
            e = model(**{k: mx.array(v) for k, v in b.items()}).text_embeds; mx.eval(e); out.append(np.array(e.astype(mx.float32)))
        return np.concatenate(out)
    label = "mlx_vlm " + Path(path).name
else:
    from gemma_text import Encoder
    enc = Encoder.load(path, compiled="--no-compile" not in sys.argv)
    run = lambda xs, dim=None: enc.embed(xs)
    label = "gemma_text " + Path(path).name + (" nocompile" if "--no-compile" in sys.argv else "")
t0 = time.time(); E = run(texts); dt = time.time() - t0
def norm(a, k): a = a[:, :k]; return a / np.linalg.norm(a, axis=1, keepdims=True)
print(f"{label}: {len(E)} texts in {dt:.2f}s")
for k in (768, 256, 128):
    e, r = norm(E, k), norm(R, k); cos = (e * r).sum(1)
    S, SR = e @ e.T, r @ r.T; np.fill_diagonal(S, -9); np.fill_diagonal(SR, -9)
    t10 = np.argsort(-S, 1)[:, :10]; r10 = np.argsort(-SR, 1)[:, :10]
    ov = np.mean([len(set(a) & set(b)) / 10 for a, b in zip(t10, r10)])
    top1 = float((t10[:, 0] == r10[:, 0]).mean())
    worst = np.argsort(cos)[:3]
    print(f"  dim {k}: cos min {cos.min():.6f} mean {cos.mean():.6f} | top-10 overlap {ov:.4f} top-1 {top1:.3f} | worst {[rows[i]['id'] for i in worst]}")
