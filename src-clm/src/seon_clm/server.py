"""``seon-clm-serve``: the reference CLM API and playground over the MLX engine,
plus two routes for callers that keep their own candidate vectors.

    seon-clm-serve                         # :8700, Qwen/Qwen3-8B, ~/.cache/clm/CLM_v0.1-8B.safetensors

    POST /v1/systemone, /v1/rank, GET /v1/models, /health, /   — clm.server, unchanged
    POST /v1/project {"side": "state"|"action", "texts": [..], "model"?, "encoding"?: "base64"|"float"}
         -> {"model", "dim", "scale", "input_tokens", "vectors"}
            base64 = little-endian float32, row-major [n, dim]
    POST /v1/score   {"state", "candidates": [..], "instructions"?, "model"?, "top_k"?}
         -> {"model", "scale", "input_tokens", "scored": [{"index", "logit", "cos"}, ..]}  best first
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import os
import time

from clm.engine import DEFAULT_MODEL, ModelNotFound
from clm.heads import DEFAULT_CKPT_DIR, HF_FILE, download
from clm.server import create_app
from fastapi import Header, HTTPException, Request
from fastapi.responses import JSONResponse
from starlette.routing import Mount

from .encoder import DEFAULT_MODEL as DEFAULT_ENCODER, Encoder
from .engine import Engine


def add_routes(app, engine: Engine, api_key: str | None) -> None:
    def auth(authorization: str | None):
        if api_key and authorization != f"Bearer {api_key}":
            raise HTTPException(401, "invalid API key")

    async def run(fn):
        t0 = time.perf_counter()
        try:
            out = await asyncio.get_running_loop().run_in_executor(None, fn)
        except ModelNotFound as e:
            raise HTTPException(422, str(e.args[0])) from e
        except (ValueError, KeyError, TypeError) as e:
            raise HTTPException(422, f"invalid request: {e}") from e
        return JSONResponse(out, headers={"X-CLM-Latency-Ms": f"{(time.perf_counter() - t0) * 1000:.1f}"})

    async def body_of(request: Request) -> dict:
        try:
            body = await request.json()
        except Exception as e:  # noqa: BLE001
            raise HTTPException(422, f"body is not JSON: {e}") from e
        if not isinstance(body, dict):
            raise HTTPException(422, "body must be a JSON object")
        return body

    @app.post("/v1/project")
    async def project(request: Request, authorization: str | None = Header(default=None)):
        auth(authorization)
        b = await body_of(request)
        texts, model = b.get("texts"), b.get("model") or DEFAULT_MODEL
        if not isinstance(texts, list) or not texts or not all(isinstance(t, str) for t in texts):
            raise HTTPException(422, "texts must be a non-empty list of strings")

        def go():
            z, scale, tokens = engine.project(b.get("side", "action"), texts, model)
            vectors = (base64.b64encode(z.astype("<f4").tobytes()).decode() if b.get("encoding", "base64") == "base64"
                       else z.tolist())
            return {"model": model, "dim": int(z.shape[1]), "scale": scale, "input_tokens": tokens, "vectors": vectors}
        return await run(go)

    @app.post("/v1/score")
    async def score(request: Request, authorization: str | None = Header(default=None)):
        auth(authorization)
        b = await body_of(request)
        cands = b.get("candidates")
        if "state" not in b or not isinstance(cands, list) or not cands or not all(isinstance(c, str) for c in cands):
            raise HTTPException(422, "body must be {state, candidates: [..]}")
        return await run(lambda: engine.score(b["state"], cands, b.get("instructions"),
                                              b.get("model") or DEFAULT_MODEL, b.get("top_k")))

    # the playground is mounted at "/" and would shadow routes added after it
    app.router.routes.sort(key=lambda r: isinstance(r, Mount))


def default_head(no_download: bool) -> str | None:
    st = os.path.join(DEFAULT_CKPT_DIR, os.path.splitext(HF_FILE)[0] + ".safetensors")
    if os.path.exists(st):
        return st
    pt = os.path.join(DEFAULT_CKPT_DIR, HF_FILE)
    if not os.path.exists(pt) and not no_download:
        pt = download()
    if os.path.exists(pt):
        from .convert import convert             # needs torch once
        return convert(pt, st)
    return None


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=int(os.environ.get("CLM_PORT", 8700)))
    ap.add_argument("--encoder", default=os.environ.get("CLM_ENCODER", DEFAULT_ENCODER),
                    help="Hugging Face repo or local path of the Qwen3-8B weights the head was trained against")
    ap.add_argument("--max-tokens", type=int, default=int(os.environ.get("CLM_EMB_MAX_TOKENS", 2048)))
    ap.add_argument("--ckpt", default=os.environ.get("CLM_CKPT"), help="head (.safetensors) served as clm-latest")
    ap.add_argument("--ckpt-dir", default=None, help="also serve every *.safetensors here under its file stem")
    ap.add_argument("--model", action="append", default=[], metavar="NAME=PATH")
    ap.add_argument("--cache-rows", type=int, default=int(os.environ.get("CLM_CACHE_ROWS", 200_000)))
    ap.add_argument("--no-download", action="store_true")
    ap.add_argument("--no-ui", action="store_true")
    ap.add_argument("--cors", action="store_true")
    a = ap.parse_args()
    extra = dict(s.partition("=")[::2] for s in a.model)
    engine = Engine(Encoder(a.encoder, a.max_tokens), checkpoint=a.ckpt or default_head(a.no_download),
                    models=extra, checkpoint_dir=a.ckpt_dir, cache_rows=a.cache_rows)
    api_key = os.environ.get("CLM_API_KEY")
    app = create_app(engine, api_key, ui=not a.no_ui, cors=a.cors)
    add_routes(app, engine, api_key)
    print(f"[seon-clm] models {[m['name'] for m in engine.models()]} on MLX; encoder {a.encoder}", flush=True)
    print(f"[seon-clm] http://{a.host}:{a.port}/  (POST /v1/project, /v1/score, /v1/systemone, /v1/rank)", flush=True)
    import uvicorn
    uvicorn.run(app, host=a.host, port=a.port, log_level="warning")


if __name__ == "__main__":
    main()
