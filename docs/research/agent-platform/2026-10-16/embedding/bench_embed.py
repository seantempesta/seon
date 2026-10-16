"""Bench + sanity for Qwen3-VL-Embedding via sentence-transformers on MPS.
Usage: bench_embed.py WEIGHTS_DIR [--dtype bf16|fp16|fp32]"""
import sys, time, resource, json
import numpy as np, torch
from PIL import Image
from sentence_transformers import SentenceTransformer
w = sys.argv[1]; dt = {"bf16": torch.bfloat16, "fp16": torch.float16, "fp32": torch.float32}[sys.argv[sys.argv.index("--dtype")+1] if "--dtype" in sys.argv else "bf16"]
rss = lambda: resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e9
t0 = time.time(); m = SentenceTransformer(w, device="mps", model_kwargs={"torch_dtype": dt}); print(f"load {time.time()-t0:.1f}s peak RSS {rss():.2f} GB; mps alloc {torch.mps.current_allocated_memory()/1e9:.2f} GB")
def sync(): torch.mps.synchronize()
# sanity: model card example (card prints 0.8160/0.7155/0.7054 for query 0 on bf16 w/ default prompt)
q = ["A woman playing with her dog on a beach at sunset.", "Pet owner training dog outdoors near water.", "Woman surfing on waves during a sunny day.", "City skyline view from a high-rise building at night."]
d = ["A woman shares a joyful moment with her golden retriever on a sun-drenched beach at sunset, as the dog offers its paw in a heartwarming display of companionship and trust."]
qe = m.encode(q, normalize_embeddings=True); de = m.encode(d, normalize_embeddings=True)
print("text sims vs doc (card: 0.8160 0.5173 0.3863 0.1061 for text doc):", np.round(qe @ de.T, 4).ravel().tolist(), "dim", qe.shape[1])
# image: render a synthetic image with PIL (no download): red square vs captions
img = Image.new("RGB", (448, 448), (200, 30, 30))
ie = m.encode([img], normalize_embeddings=True)
te = m.encode(["a solid red image", "a solid blue image", "a city skyline at night"], normalize_embeddings=True)
print("image vs [red, blue, skyline]:", np.round(te @ ie.T, 4).ravel().tolist())
# MRL
for k in (256, 512, 1024):
    a = qe[:, :k] / np.linalg.norm(qe[:, :k], axis=1, keepdims=True); b = de[:, :k] / np.linalg.norm(de[:, :k], axis=1, keepdims=True)
    print("MRL", k, np.round(a @ b.T, 4).ravel().tolist())
# latency by token length
tok = m.tokenizer
base = open(__file__).read() * 40
def text_of(n): ids = tok(base, add_special_tokens=False)["input_ids"][:n]; return tok.decode(ids)
m.max_seq_length = 32768
res = {}
for n in (100, 1000, 8000):
    t = text_of(n); m.encode([t]); sync()
    ts = []
    for _ in range(5):
        a = time.perf_counter(); m.encode([t]); sync(); ts.append((time.perf_counter() - a) * 1000)
    res[n] = float(np.median(ts)); print(f"{n} tokens: median {res[n]:.0f} ms ({n/res[n]*1000:.0f} tok/s)")
# batch throughput of 100-token texts
for bs in (1, 16, 64):
    ts_ = [text_of(100) + f" #{i}" for i in range(bs)]
    m.encode(ts_, batch_size=bs); sync(); a = time.perf_counter(); m.encode(ts_, batch_size=bs); sync(); dt_ = time.perf_counter() - a
    print(f"batch {bs} x100tok: {dt_*1000:.0f} ms total, {bs/dt_:.1f} emb/s")
print(f"peak RSS {rss():.2f} GB; mps driver {torch.mps.driver_allocated_memory()/1e9:.2f} GB")
