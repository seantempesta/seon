"""EmbeddingGemma 2 text embedding server on MLX (bf16 or 8-bit weights).
One command:  ~/src/embed-models/embeddinggemma-2/.venv/bin/python -I embed_server_mlx.py \
                  ~/src/embed-models/embeddinggemma-2/weights/embeddinggemma-2-bf16 [--port 8701]
POST /v1/embed {"texts": [...], "task": "clustering"|"classification"|"search_query"|"document"|"similarity"|"code"|null,
                "dim": 768|512|256|128, "encoding": "list"|"base64", "max_tokens": 8192}
  -> {"model","dim","n","input_tokens","ms","vectors"}  vectors L2-normalised (re-normalised after truncation).
`task` prepends the model's own prefix (config_sentence_transformers.json); null = text sent as given.
Documents with a title: pass the string already formatted `title: T | text: C` and task null.
GET /v1/models, /healthz. bf16 only (the model card forbids float16)."""
import argparse, base64, time
from pathlib import Path
import numpy as np, mlx.core as mx
from fastapi import FastAPI
from pydantic import BaseModel
from transformers import AutoTokenizer
from mlx_vlm.embedding_loader import load_embedding_model

ap = argparse.ArgumentParser(); ap.add_argument("weights"); ap.add_argument("--port", type=int, default=8701); ap.add_argument("--host", default="127.0.0.1")
args = ap.parse_args()
path = Path(args.weights); model = load_embedding_model(path); tok = AutoTokenizer.from_pretrained(path)
PREFIX = {"search_query": "task: search result | query: ", "document": "title: none | text: ", "classification": "task: classification | query: ",
          "clustering": "task: clustering | query: ", "similarity": "task: sentence similarity | query: ", "code": "task: code retrieval | query: ",
          "qa": "task: question answering | query: ", "fact": "task: fact checking | query: "}
app = FastAPI(); NAME = path.name

class Req(BaseModel):
    texts: list[str]; task: str | None = None; dim: int | None = None; encoding: str = "list"; max_tokens: int = 8192; batch: int = 32

@app.get("/healthz")
def healthz(): return {"ok": True, "model": NAME}
@app.get("/v1/models")
def models(): return {"models": [{"id": NAME, "dim": 768, "mrl": [128, 256, 512, 768], "max_tokens": 8192, "tasks": sorted(PREFIX)}]}

@app.post("/v1/embed")
async def embed(r: Req):  # async: runs on the main thread, where the MLX GPU stream lives
    t0 = time.perf_counter(); p = PREFIX.get(r.task, "") if r.task else ""
    xs = [p + t for t in r.texts]; order = sorted(range(len(xs)), key=lambda i: len(xs[i])); out = [None] * len(xs); ntok = 0
    for s in range(0, len(xs), r.batch):  # length-sorted batches keep padding small
        idx = order[s:s + r.batch]
        b = tok([xs[i] for i in idx], padding=True, truncation=True, max_length=r.max_tokens, return_tensors="np"); ntok += int(b["attention_mask"].sum())
        e = model(**{k: mx.array(v) for k, v in b.items()}).text_embeds
        if r.dim: e = e[:, :r.dim]
        e = e / mx.linalg.norm(e, axis=-1, keepdims=True); mx.eval(e); e = np.array(e.astype(mx.float32))
        for j, i in enumerate(idx): out[i] = e[j]
    v = np.stack(out) if out else np.zeros((0, r.dim or 768), np.float32)
    vec = base64.b64encode(v.astype("<f4").tobytes()).decode() if r.encoding == "base64" else v.tolist()
    return {"model": NAME, "dim": int(v.shape[1]), "n": len(v), "input_tokens": ntok, "ms": (time.perf_counter() - t0) * 1000, "vectors": vec}

if __name__ == "__main__":
    import uvicorn; uvicorn.run(app, host=args.host, port=args.port, log_level="warning")
