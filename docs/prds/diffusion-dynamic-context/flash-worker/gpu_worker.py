import os, sys, json, asyncio
from runpod_flash import Endpoint, GpuType, PodTemplate

# Warm-worker model cache: persists across requests within one live worker, so a
# burst of calls loads the 50GB model ONCE. Scales to zero after idle_timeout.
_CACHE = {}

@Endpoint(
    name="diffgemma",
    gpu=GpuType.NVIDIA_A100_80GB_PCIe,   # 80GB → BF16 (~50GB) + long-context KV
    workers=(0, 1),                       # scale-to-zero; $0 when idle
    idle_timeout=600,                     # stay warm 10 min between bursts
    flashboot=True,
    template=PodTemplate(containerDiskInGb=120),
    dependencies=["transformers==5.11.0", "accelerate", "sentencepiece", "pillow"],
    env={"HF_TOKEN": os.environ.get("HF_TOKEN", "")},
    execution_timeout_ms=1_500_000,
)
def diffgemma(**payload):
    import os, time, subprocess, sys, traceback, contextlib
    MID = "google/diffusiongemma-26B-A4B-it"
    tok = os.environ.get("HF_TOKEN")

    # Gemma4Processor (pulled in loading diffusion_gemma) needs a vision backend.
    # --no-deps so torchvision CANNOT upgrade/corrupt the image's pinned torch
    # (a plain install pulls a mismatched torch and breaks torch._dynamo).
    try:
        import torchvision  # noqa: F401
    except ImportError:
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", "--no-deps", "torchvision==0.24.1"], check=False)

    import torch
    # Surface torch._dynamo health (transformers imports it; base-image torch
    # has been the recurring blocker).
    try:
        import torch._dynamo  # noqa: F401
        _dynamo_ok = True
    except Exception as _e:
        _dynamo_ok = f"{type(_e).__name__}: {_e}"[:160]
    # The base image's transformers imports torch.nn.attention.flex_attention,
    # which needs setup_compilation_env — absent in this image's torch 2.9.1
    # (version skew baked into the image). Shim it so the import succeeds; we
    # load with attn_implementation="eager" so flex_attention is never used.
    import torch._higher_order_ops.utils as _hou
    if not hasattr(_hou, "setup_compilation_env"):
        _hou.setup_compilation_env = lambda *a, **k: contextlib.nullcontext()

    import transformers
    info = {
        "transformers": transformers.__version__,
        "torch": torch.__version__,
        "dynamo": _dynamo_ok,
        "cuda": torch.cuda.is_available(),
        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
        "vram_gb": round(torch.cuda.get_device_properties(0).total_memory / 1e9, 1)
                   if torch.cuda.is_available() else None,
    }

    if payload.get("mode") == "probe":
        from transformers import AutoConfig, DiffusionGemmaForBlockDiffusion
        info["class_ok"] = bool(DiffusionGemmaForBlockDiffusion)
        try:
            cfg = AutoConfig.from_pretrained(MID, token=tok)
            info["config_ok"] = True
            info["model_type"] = getattr(cfg, "model_type", None)
        except Exception as e:
            info["config_ok"] = False
            info["config_err"] = f"{type(e).__name__}: {e}"[:200]
        return info

    # --- full generate (text-only) ---
    try:
        from transformers import AutoTokenizer, DiffusionGemmaForBlockDiffusion
        t0 = time.time()
        if "model" not in _CACHE:
            _CACHE["tok"] = AutoTokenizer.from_pretrained(MID, token=tok)
            _CACHE["model"] = DiffusionGemmaForBlockDiffusion.from_pretrained(
                MID, dtype="auto", device_map="auto", token=tok,
                attn_implementation="eager")
        info["load_s"] = round(time.time() - t0, 1)
        tkz, model = _CACHE["tok"], _CACHE["model"]
        msgs = [{"role": "user", "content": payload["prompt"]}]
        inp = tkz.apply_chat_template(
            msgs, tokenize=True, add_generation_prompt=True,
            return_dict=True, return_tensors="pt").to(model.device)
        nprompt = int(inp["input_ids"].shape[-1])
        g0 = time.time()
        out = model.generate(**inp, max_new_tokens=payload.get("max_new_tokens", 256))
        gen_s = time.time() - g0
        ncomp = int(out.shape[-1]) - nprompt
        info.update({
            "text": tkz.decode(out[0][nprompt:], skip_special_tokens=True),
            "prompt_tokens": nprompt, "completion_tokens": ncomp,
            "gen_s": round(gen_s, 2),
            "tok_per_s": round(ncomp / gen_s, 1) if gen_s else None,
        })
    except Exception as e:
        info["gen_error"] = f"{type(e).__name__}: {e}"[:300]
        info["trace"] = traceback.format_exc()[-1200:]
    return info


async def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "probe"
    if mode == "probe":
        r = await diffgemma({"mode": "probe"})
    else:
        prompt = ("Write an idiomatic Clojure function `mean` that returns the "
                  "average of a vector of numbers. Reply with ONLY the code in a "
                  "```clojure block.")
        r = await diffgemma({"mode": "generate", "prompt": prompt, "max_new_tokens": 256})
    print("RESULT:", json.dumps(r, indent=2))

if __name__ == "__main__":
    asyncio.run(main())
