"""Local embedding server for Qwen3-VL-Embedding (text + image), PyTorch MPS.

Usage:  embed_server.py WEIGHTS_DIR [--port 8701] [--device mps]
POST /v1/embed  {"texts": [...], "instruction": "...", "dim": 1024,
                 "images": ["<base64 png/jpg>", ...], "encoding": "base64"|"list"}
  -> {"model", "dim", "n", "ms", "vectors": [[...]] | "<LE float32 [n,dim] base64>"}
Texts and images are separate inputs (each element of `texts`, then each of `images`,
becomes one row, texts first). `dim` truncates (Matryoshka) then re-normalises.
GET /v1/models, GET /healthz.  Vectors are L2-normalised.
"""
import argparse, base64, io, time
import numpy as np
import torch
from fastapi import FastAPI
from pydantic import BaseModel
from PIL import Image
from sentence_transformers import SentenceTransformer

ap = argparse.ArgumentParser()
ap.add_argument("weights")
ap.add_argument("--port", type=int, default=8701)
ap.add_argument("--device", default="mps")
ap.add_argument("--host", default="127.0.0.1")
args = ap.parse_args()

model = SentenceTransformer(args.weights, device=args.device, model_kwargs={"torch_dtype": torch.bfloat16})
NAME = args.weights.rstrip("/").split("/")[-1]
app = FastAPI()

class Req(BaseModel):
    texts: list[str] = []
    images: list[str] = []
    instruction: str | None = None
    dim: int | None = None
    encoding: str = "list"

def encode(items, instruction, dim):
    kw = {"prompt": instruction} if instruction else {}
    v = model.encode(items, convert_to_numpy=True, normalize_embeddings=True, batch_size=16, **kw).astype(np.float32)
    if dim:
        v = v[:, :dim]
        v /= np.linalg.norm(v, axis=1, keepdims=True)
    return v

@app.get("/healthz")
def healthz(): return {"ok": True, "model": NAME}

@app.get("/v1/models")
def models(): return {"models": [{"id": NAME, "dim": model.get_sentence_embedding_dimension(), "modalities": ["text", "image"]}]}

@app.post("/v1/embed")
def embed(r: Req):
    t0 = time.perf_counter()
    items = list(r.texts) + [Image.open(io.BytesIO(base64.b64decode(b))).convert("RGB") for b in r.images]
    v = encode(items, r.instruction, r.dim) if items else np.zeros((0, r.dim or 0), np.float32)
    out = base64.b64encode(v.tobytes()).decode() if r.encoding == "base64" else v.tolist()
    return {"model": NAME, "dim": int(v.shape[1]) if len(v) else 0, "n": len(v), "ms": (time.perf_counter() - t0) * 1000, "vectors": out}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")
